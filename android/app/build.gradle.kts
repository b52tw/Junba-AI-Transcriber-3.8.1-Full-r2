plugins {
    id("com.android.application")
}

android {
    namespace = "tw.junba.transcriber"
    compileSdk = 35

    defaultConfig {
        applicationId = "tw.junba.transcriber"
        minSdk = 26
        targetSdk = 35
        versionCode = 381
        versionName = "3.8.1"
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
