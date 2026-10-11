plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.hawakeword"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.hawakeword"
        minSdk = 26
        targetSdk = 34
        // Shown at the top of the app. Bump both on every release you install.
        versionCode = 2
        versionName = "0.2.0"

        // Optional: bake in the Home Assistant address for managed rollouts, e.g.
        //   ./gradlew assembleManagedRelease -PhaUrl=http://homeassistant.local:8123
        val haUrl = (project.findProperty("haUrl") as String?).orEmpty()
        buildConfigField("String", "DEFAULT_HA_URL", "\"$haUrl\"")
    }

    // Two variants of the same app:
    //  - standard: everything is open.
    //  - managed : settings, word changes and stopping the listener sit behind an admin password
    //              set by the user on first run (institutions, kiosks). It installs side by side
    //              with standard (own application id and sign-in redirect scheme).
    flavorDimensions += "mode"
    productFlavors {
        create("standard") {
            dimension = "mode"
            buildConfigField("boolean", "LOCKED", "false")
            buildConfigField("String", "AUTH_SCHEME", "\"hawakeword\"")
            manifestPlaceholders["authScheme"] = "hawakeword"
            resValue("string", "app_name", "HA Wake Word")
        }
        create("managed") {
            dimension = "mode"
            applicationIdSuffix = ".managed"
            versionNameSuffix = "-managed"
            buildConfigField("boolean", "LOCKED", "true")
            buildConfigField("String", "AUTH_SCHEME", "\"hawakeword-managed\"")
            manifestPlaceholders["authScheme"] = "hawakeword-managed"
            resValue("string", "app_name", "HA Wake Word Managed")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so a release build installs straight away.
            // For real distribution, replace with your own signingConfig (see docs/MANAGED.md).
            signingConfig = signingConfigs.getByName("debug")
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
        buildConfig = true
    }
    // ONNX models must not be compressed inside the APK.
    androidResources {
        noCompress += "onnx"
    }
}

dependencies {
    // Runs the mel / embedding / classifier models (see WakeWordEngine.kt).
    // Its native libs are the only .so files we ship. 1.17.x is NOT 16 KB aligned; the fix for
    // libonnxruntime4j_jni.so landed in mid-2025, so stay on 1.24+ (verify, see docs/DEVELOPERS.md).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.1")
    implementation("androidx.core:core-ktx:1.13.1")
    // MasterKey (used in Prefs.kt) only exists from 1.1.0-alpha01; 1.0.0 has the older MasterKeys API.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // Pure-Java XSalsa20 + Poly1305 for the mobile_app webhook encryption (crypto_secretbox).
    // Deliberately not libsodium/JNA: those ship native .so files that must be 16 KB aligned.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
