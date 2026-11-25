plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.vehiclescanner"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vighnesh.vehiclescanner"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    // Play Store upload key: supplied by CI from GitHub secrets (never stored in the repo).
    val uploadKeystore = System.getenv("UPLOAD_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (uploadKeystore != null) create("upload") {
            storeFile = uploadKeystore
            storePassword = System.getenv("UPLOAD_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("UPLOAD_KEY_ALIAS") ?: "upload"
            keyPassword = System.getenv("UPLOAD_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            // Kept unminified so OpenCV / LiteRT / ML Kit JNI classes are never stripped.
            isMinifyEnabled = false
            // Upload key when available (Play Store), otherwise debug key so test APKs still install.
            signingConfig = if (uploadKeystore != null) signingConfigs.getByName("upload") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    androidResources {
        // TFLite models must stay uncompressed so they can be memory-mapped.
        noCompress += "tflite"
    }
    packaging {
        jniLibs { useLegacyPackaging = false }
    }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.navigation.compose)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)

    implementation(libs.mlkit.text)          // bundled Latin model – works offline
    implementation(libs.litert)             // YOLO plate detector inference (LiteRT = TensorFlow Lite successor, 16 KB pages)
    implementation(libs.opencv)              // image enhancement + classical plate detector
    implementation(libs.exifinterface)
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
}
