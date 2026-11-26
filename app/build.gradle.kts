import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.familyhub.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.familyhub.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "2.0.0"

        val webClientId = project.findProperty("GOOGLE_WEB_CLIENT_ID")?.toString()
            ?: System.getenv("GOOGLE_WEB_CLIENT_ID")
            ?: ""

        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$webClientId\"")

        // Gemini key for the AI assistant. Stored reversed and base64-encoded so it is not a plain string in the APK.
        val geminiKey = (project.findProperty("GEMINI_API_KEY")?.toString()
            ?: System.getenv("GEMINI_API_KEY")
            ?: "").trim()
        val geminiEncoded = Base64.getEncoder().encodeToString(geminiKey.reversed().toByteArray())
        buildConfigField("String", "GEMINI_KEY_ENC", "\"$geminiEncoded\"")
    }

    signingConfigs {
        getByName("debug") {
            val ks = System.getenv("DEBUG_KEYSTORE_PATH")
            if (ks != null && File(ks).exists()) {
                storeFile = File(ks)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        create("release") {
            val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    val firebaseBom = platform("com.google.firebase:firebase-bom:33.5.1")
    implementation(firebaseBom)
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.firebase:firebase-messaging-ktx")
    implementation("com.google.firebase:firebase-crashlytics")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // Steps from Google Fit, watches and other apps via Health Connect (Google Fit's own APIs are being retired).
    implementation("androidx.health.connect:connect-client:1.1.0-alpha07")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    // Family invite QR: drawn with ZXing; scanned with Google's code scanner, which needs no camera permission.
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // Family chat emoji keyboard (AndroidX, Apache 2.0, works offline).
    implementation("androidx.emoji2:emoji2-emojipicker:1.4.0")

    implementation("com.google.dagger:hilt-android:2.52")
    ksp("com.google.dagger:hilt-compiler:2.52")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // Voice and video calls (Beta): free, BSD-licensed WebRTC build, no account or service needed.
    implementation("io.github.webrtc-sdk:android:137.7151.04")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
}
