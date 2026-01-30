plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tinydictation.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tinydictation.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 265
        versionName = "1.18.42"
    }

    // Generate separate APKs per ABI to reduce size
    // Vosk native libs are ~15-20MB per architecture
    splits {
        abi {
            isEnable = true
            reset()
            // Only include ARM64 (most modern devices) and ARM32 (older devices)
            // x86/x86_64 only needed for emulators, skip to reduce complexity
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false // Don't generate a fat universal APK
        }
    }

    signingConfigs {
        // Use consistent debug keystore for reproducible signatures
        val debugKeystoreFile = file("debug.keystore")
        if (debugKeystoreFile.exists()) {
            getByName("debug") {
                storeFile = debugKeystoreFile
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            // Use release signing if available, otherwise fall back to debug
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")

    // Vosk - Offline speech recognition
    implementation("com.alphacephei:vosk-android:0.3.47")

    // Sherpa-ONNX - Whisper offline speech recognition
    implementation(files("libs/sherpa-onnx-1.12.20.aar"))
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.8.2")

    // Lifecycle & ViewModel
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // WebSocket (Deepgram)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // TensorFlow Lite (Silero VAD)
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")

    // Encrypted SharedPreferences
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")

    // Archive extraction (for tar.bz2 Whisper models)
    implementation("org.apache.commons:commons-compress:1.26.0")

    // Emoji Picker
    implementation("androidx.emoji2:emoji2-emojipicker:1.4.0")

    // Glide for loading GIF images
    implementation("com.github.bumptech.glide:glide:4.16.0")
}
