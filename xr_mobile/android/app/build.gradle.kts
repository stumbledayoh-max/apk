plugins {
    id("com.android.application")
}

// ARSCLib bundles copies of Android's org.xmlpull interfaces. If they reach
// R8 as program classes it can rename them, making Android's XmlResourceParser
// incompatible with FileProvider. Compile and run against the platform APIs.
val arscLib = file(System.getenv("XR_ARSCLIB_JAR") ?: "libs/ARSCLib-1.4.0.jar")
val androidArscLib = tasks.register<Jar>("prepareArscLibForAndroid") {
    from(zipTree(arscLib)) {
        exclude("org/xmlpull/v1/**")
        exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/*.EC")
    }
    archiveFileName.set("ARSCLib-android.jar")
    destinationDirectory.set(layout.buildDirectory.dir("prepared-libs"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
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
    sourceSets["main"].assets.srcDir(
        System.getenv("XR_INJECTION_ASSETS_DIR") ?: "src/main/injection-assets"
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
    implementation(files(androidArscLib))
    implementation("com.android.tools.build:apksig:8.7.3")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
