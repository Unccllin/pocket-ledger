plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pocketledger"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pocketledger"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            // Matches the cargo-ndk targets in the README.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
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

    kotlinOptions {
        jvmTarget = "17"
    }

    // The .so files are built by cargo-ndk and dropped in here.
    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")
}

// No third-party runtime dependencies on purpose: the app is framework-only, so
// there is nothing to phone home and nothing to keep in sync.
