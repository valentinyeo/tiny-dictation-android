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
        versionCode = 4
        versionName = "1.1.2"
    }

    signingConfigs {
        create("release") {
            // Try to get signing config from environment variables first
            val keystoreBase64 = System.getenv("KEYSTORE_BASE64")
            val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
            val keyAlias = System.getenv("KEY_ALIAS")
            val keyPassword = System.getenv("KEY_PASSWORD")

            if (keystoreBase64 != null && keystorePassword != null && keyAlias != null && keyPassword != null) {
                // Decode base64 keystore to temporary file
                val keystoreFile = File(rootProject.buildDir, "keystore.jks")
                keystoreFile.parentFile?.mkdirs()
                keystoreFile.writeBytes(java.util.Base64.getDecoder().decode(keystoreBase64))

                storeFile = keystoreFile
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            } else {
                // Fall back to debug keystore for local development
                val debugKeystore = File(System.getProperty("user.home"), ".android/debug.keystore")
                if (debugKeystore.exists()) {
                    storeFile = debugKeystore
                    storePassword = "android"
                    this.keyAlias = "androiddebugkey"
                    this.keyPassword = "android"
                }
            }
        }
    }

    buildTypes {
        debug {
            // Use release signing config for debug builds to ensure consistency
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
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
}
