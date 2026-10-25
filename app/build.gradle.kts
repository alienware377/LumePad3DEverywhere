plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lume3d.everywhere"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lume3d.everywhere"
        minSdk = 30          // CNSDK requires >= 30; Lume Pad 2 ships Android 13 (API 33)
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"

        // CNSDK native libs ship arm64-v8a only (Lume Pad 2 is arm64).
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Tiny C shim used to reach CNSDK's non-exported SetInterlaceMode (see cpp/leiahack.c).
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { viewBinding = true }

    // Do not compress the model or GLSL so they can be mmap'd / read fast.
    androidResources { noCompress += listOf("tflite", "glsl") }

    packaging {
        // CNSDK and TFLite may both bundle libc++_shared on some setups.
        jniLibs.pickFirsts += "**/libc++_shared.so"
        // Extract .so files to nativeLibraryDir on install. The Hexagon DSP's fastrpc
        // loads libQnnHtpV68Skel.so by real filesystem path; it can't read it from
        // inside the (un-extracted) APK, so we must un-bundle the native libs.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Leia Content Native SDK (face-tracked lightfield interlacer).
    implementation(files("../CNSDK/sdk-final-aar-release.aar"))

    // On-device monocular depth (Depth Anything V2) via TensorFlow Lite.
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-gpu-api:2.16.1")

    // Qualcomm QNN delegate: run the ViT depth model on the Snapdragon 888 Hexagon NPU
    // (v68). The GPU delegate can't run transformer ops; this is the only real-time path.
    // 2.34.0 still bundles libQnnHtpV68Skel.so (Hexagon 780 / SM8350).
    implementation("com.qualcomm.qti:qnn-runtime:2.34.0")
    implementation("com.qualcomm.qti:qnn-litert-delegate:2.34.0")

    // Shizuku: run privileged (shell-uid) calls without root — trusted virtual
    // display, launch-on-display, and input injection.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // Access hidden system AIDL stubs (IDisplayManager/IInputManager) on API 29+.
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
}
