plugins { id("com.android.application") }

android {
    namespace = "org.yourec.app"
    compileSdk = 37
    ndkVersion = "28.2.13676358"
    providers.gradleProperty("yourecNdkPath").orNull?.let { ndkPath = it }
    defaultConfig {
        applicationId = "org.yourec.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "org.yourec.app.DeviceTests"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=none" } }
    }
    externalNativeBuild { cmake { path = file("src/main/c/CMakeLists.txt"); version = "3.31.6" } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs.getByName("debug") {
        val key = rootProject.file(".build/debug.keystore")
        if (key.exists()) {
            storeFile = key
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes { release { isMinifyEnabled = false } }
}

tasks.register<JavaExec>("testEditor") {
    dependsOn("compileDebugUnitTestKotlin")
    mainClass.set("org.yourec.app.EditorTestsKt")
    classpath = files(tasks.named("compileDebugUnitTestKotlin"), tasks.named("compileDebugKotlin")) +
        configurations.getByName("debugUnitTestRuntimeClasspath")
}
