plugins { id("com.android.application") }

android {
    namespace = "com.xrpocket.probe"
    compileSdk = 35
    ndkVersion = "27.0.12077973"
    defaultConfig {
        applicationId = "com.xrpocket.probe"
        minSdk = 29
        targetSdk = 32
        versionCode = 1
        versionName = "1"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=none") } }
    }
    sourceSets["main"].jniLibs.srcDir(System.getenv("XR_LOADER_DIR") ?: "src/main/jniLibs")
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
