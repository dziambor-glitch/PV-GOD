plugins {
    id("com.android.application")
}

android {
    namespace = "de.pvcompact.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.pvcompact.app.clean"
        minSdk = 26
        targetSdk = 36
        versionCode = 122
        versionName = "1.2.2"
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
