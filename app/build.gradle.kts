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
        versionCode = 184
        versionName = "1.8.4"
    }

    buildFeatures {
        resValues = true
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".mqttv184"
            versionNameSuffix = "-live-preview"
            resValue("string", "app_name", "PV Compact Prognose 1.8.4")
        }
        release {
            isMinifyEnabled = false
            resValue("string", "app_name", "PV Compact")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
}
