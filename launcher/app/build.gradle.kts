plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tv.minimal.home"
    compileSdk = 34

    defaultConfig {
        applicationId = "tv.minimal.home"
        minSdk = 25
        targetSdk = 28 // Fire OS 7 = Android 9
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // Optimized (non-debuggable) build, signed with the local debug key for sideloading.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false; abortOnError = false }
}
