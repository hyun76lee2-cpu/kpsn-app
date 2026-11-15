package com.kpsn.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.Locale;

/**
 * KPSN 공유 관측망 앱.
 * 화면·계산·공유는 모두 KPSN 사이트가 그대로 맡고, 이 앱은 두 가지만 한다.
 *  1) 가속도 센서를 최고 속도·전체 정밀도로 읽어 100 ms마다 사이트에 넘긴다
 *     (브라우저는 값을 0.1 m/s² 단위로 잘라 주지만, 앱은 센서 원래 분해능 그대로 준다).
 *     넘기는 형식: window.nativeSamples([t(초), x, y, z(gal), t, x, y, z, ...])
 *  2) 위치 권한 요청과 화면 켜 두기.
 * 사이트를 고치면 앱을 다시 만들 필요 없이 바로 반영된다.
 */
public class MainActivity extends Activity implements SensorEventListener {

    private static final String SITE = "https://hyun76lee2-cpu.github.io/KPSN-KOREAN_PUBLIC_SEISMIC_NETWORK/";
    private static final String SITE_HOST = "hyun76lee2-cpu.github.io";
    private static final float MS2_TO_GAL = 100f;
    private static final long FLUSH_MS = 100;
    private static final int LOCATION_REQUEST = 2001;

    private WebView web;
    private SensorManager sensorManager;
    private Sensor accel;
    private volatile boolean running = false;

    private final Object lock = new Object();
    private final StringBuilder buffer = new StringBuilder();
    private int buffered = 0;

    /** 사이트가 위치를 물었을 때, 권한 대화상자 결과를 기다리는 동안 보관 */
    private String geoOrigin;
    private GeolocationPermissions.Callback geoCallback;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable flush = new Runnable() {
        @Override
        public void run() {
            String data;
            synchronized (lock) {
                data = buffer.toString();
                buffer.setLength(0);
                buffered = 0;
            }
            if (!data.isEmpty() && web != null) {
                web.evaluateJavascript("window.nativeSamples && window.nativeSamples([" + data + "])", null);
            }
            if (running) handler.postDelayed(this, FLUSH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accel = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " KPSNApp/1.0");
        web.addJavascriptInterface(new Bridge(), "ImmNative");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String host = u.getHost() == null ? "" : u.getHost();
                // KPSN 사이트와 로그인용 Firebase 주소는 앱 안에서, 나머지 링크(기상청 등)는 브라우저로
                if (host.equals(SITE_HOST) || host.endsWith("firebaseapp.com") || host.endsWith("google.com")) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) showOffline();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (hasLocationPermission()) {
                    callback.invoke(origin, true, false);
                    return;
                }
                geoOrigin = origin;
                geoCallback = callback;
                if (Build.VERSION.SDK_INT >= 23) {
                    requestPermissions(new String[]{
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_REQUEST);
                } else {
                    callback.invoke(origin, true, false);
                }
            }
        });

        setContentView(web);
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(SITE);
    }

    private boolean hasLocationPermission() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == LOCATION_REQUEST && geoCallback != null) {
            geoCallback.invoke(geoOrigin, hasLocationPermission(), false);
            geoCallback = null;
            geoOrigin = null;
            return;
        }
        super.onRequestPermissionsResult(requestCode, permissions, results);
    }

    /** 인터넷이 안 될 때 보여 줄 간단한 화면 */
    private void showOffline() {
        String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
                + "<body style='font-family:sans-serif;padding:32px;color:#222'>"
                + "<h2>KPSN에 연결하지 못했어요</h2>"
                + "<p>인터넷 연결을 확인한 뒤 다시 시도해 주세요.</p>"
                + "<p><a href='" + SITE + "' style='font-size:18px'>다시 시도</a></p></body></html>";
        web.loadDataWithBaseURL(SITE, html, "text/html", "utf-8", null);
    }

    private void startSensor() {
        if (running || accel == null) return;
        running = true;
        sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_FASTEST);
        handler.postDelayed(flush, FLUSH_MS);
    }

    private void stopSensor() {
        running = false;
        if (sensorManager != null) sensorManager.unregisterListener(this);
        handler.removeCallbacks(flush);
        synchronized (lock) {
            buffer.setLength(0);
            buffered = 0;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (!running) return;
        double t = e.timestamp / 1e9;   // 부팅 후 경과 시간(초), 단조 증가
        synchronized (lock) {
            if (buffered > 0) buffer.append(',');
            buffer.append(t).append(',')
                  .append(e.values[0] * MS2_TO_GAL).append(',')
                  .append(e.values[1] * MS2_TO_GAL).append(',')
                  .append(e.values[2] * MS2_TO_GAL);
            buffered++;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null) web.saveState(out);
    }

    @Override
    protected void onDestroy() {
        stopSensor();
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    /** 사이트의 자바스크립트에서 window.ImmNative.start() / stop() / info() 로 호출 */
    private class Bridge {
        @JavascriptInterface
        public boolean start() {
            if (accel == null) return false;
            runOnUiThread(MainActivity.this::startSensor);
            return true;
        }

        @JavascriptInterface
        public void stop() {
            runOnUiThread(MainActivity.this::stopSensor);
        }

        @JavascriptInterface
        public String info() {
            if (accel == null) return "가속도 센서 없음";
            return accel.getName() + ", 최대 " + Math.round(accel.getMaximumRange() * 100) + " gal, 분해능 "
                    + String.format(Locale.US, "%.3f", accel.getResolution() * 100) + " gal";
        }
    }
}
