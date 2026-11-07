plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.screenshot)
}

android {
    namespace = "dev.luna.assistant"
    // The official A2UI Compose libraries (androidx.a2ui, alpha) need compileSdk 37.1.
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "dev.luna.assistant"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            // TODO: Create your own release signing config
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    experimentalProperties["android.experimental.enableScreenshotTest"] = true

    lint {
        disable.add("NullSafeMutableLiveData")
    }
}

dependencies {

    // For local development with the LiveKit Compose SDK only.
    // implementation("io.livekit:livekit-compose-components")

    implementation(libs.livekit.lib)
    implementation(libs.livekit.components)

    implementation(libs.accompanist.permissions)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.constraintlayout.compose)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.timberkt)

    // Widgets: the official A2UI renderer (alpha) with its Material 3 basic catalog
    implementation(libs.a2ui.model)
    implementation(libs.a2ui.engine)
    implementation(libs.a2ui.compose.runtime)
    implementation(libs.a2ui.compose.ui)
    implementation(libs.a2ui.material3)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Wake words: openWakeWord's ONNX models, run on the phone (wakeword/OpenWakeWord.kt). Both artifacts have the same
    // `ai.onnxruntime` API; unit tests run on a plain JVM, so they get the desktop build instead of the Android one.
    implementation(libs.onnxruntime.android)
    testImplementation(libs.onnxruntime.jvm)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)   // org.json is part of Android but not of a plain JVM
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    screenshotTestImplementation(libs.screenshot.validation.api)
    screenshotTestImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
// The unit tests run on a plain JVM: keep the Android build of ONNX Runtime (an .aar with phone-only native code) off their classpath.
configurations.configureEach {
    if (name.contains("UnitTest")) exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
}
