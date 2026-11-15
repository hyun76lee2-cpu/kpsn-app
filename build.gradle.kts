// 모듈 하나짜리 구조: 이 파일 하나가 앱 전체 설정이다.
plugins {
    id("com.android.application") version "8.5.2"
}

android {
    namespace = "com.kpsn.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kpsn.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
