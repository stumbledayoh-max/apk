plugins {
    id("com.android.application")
}

android {
    namespace = "com.xrpocket.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xrpocket.mobile"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-experimental"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    sourceSets["main"].assets.srcDir(
        System.getenv("XR_HAND_MODEL_DIR") ?: "src/main/assets"
    )
    sourceSets["androidTest"].assets.srcDir(
        System.getenv("XR_HAND_TEST_IMAGE_DIR") ?: "src/androidTest/assets"
    )
    androidResources { noCompress += listOf("task") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core:1.15.0")
    implementation("com.google.mediapipe:tasks-vision:0.10.35")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
