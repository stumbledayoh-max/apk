plugins {
    id("com.android.application")
}

android {
    namespace = "com.bigxr.embedded"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bigxr.embedded.support"
        minSdk = 29
        targetSdk = 35
        ndk { abiFilters += "arm64-v8a" }
    }

    // This APK is a build input, not an application to be installed separately.
    // Repackage dependency internals to reduce collisions with the game dex.
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    sourceSets["main"].assets.srcDir(
        System.getenv("XR_HAND_MODEL_DIR") ?: "src/main/assets"
    )
    androidResources { noCompress += listOf("task") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.mediapipe:tasks-vision:0.10.35")
    testImplementation("junit:junit:4.13.2")
}
