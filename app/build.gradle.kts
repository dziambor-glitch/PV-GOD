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
        versionCode = 179
        versionName = "1.7.9"
    }

    buildFeatures {
        resValues = true
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".mqttpreview"
            versionNameSuffix = "-mqtt-preview"
            resValue("string", "app_name", "PV Compact MQTT Preview")
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
