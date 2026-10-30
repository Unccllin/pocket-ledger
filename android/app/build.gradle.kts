plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.pocketledger"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pocketledger"
        minSdk = 26
        // 35 keeps Android 15/16 behaviour opt-ins explicit; the UI is tested
        // on an Android 16 device.
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
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

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    // The .so files are built by cargo-ndk and dropped in here.
    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")
}

dependencies {
    // Compose 1.11.x (BOM 2026.05.01) is the newest line that still supports
    // AGP 8.x and compileSdk 36; Compose 1.12 requires AGP 9.1 and SDK 37.
    val composeBom = platform("androidx.compose:compose-bom:2026.05.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.12.4")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
