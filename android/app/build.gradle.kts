plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.zenfortv.dieselheater"
    compileSdk = 34
    defaultConfig {
        applicationId = "io.github.zenfortv.dieselheater"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }
    // the app shows the same page as GitHub Pages / the ESP32 box: DieselHeater/web
    sourceSets["main"].assets.srcDirs("../../web")
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
