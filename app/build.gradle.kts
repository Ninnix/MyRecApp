import java.util.Properties

plugins { id("com.android.application") }

android {
    namespace = "org.myrec.app"
    compileSdk = 37
    ndkVersion = "28.2.13676358"
    providers.gradleProperty("myrecNdkPath").orNull?.let { ndkPath = it }
    defaultConfig {
        applicationId = "org.myrec.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 8
        versionName = "0.1.7"
        testInstrumentationRunner = "org.myrec.app.DeviceTests"
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
    val signingFile = rootProject.file("keystore.properties")
    if (signingFile.isFile) {
        val settings = Properties().apply { signingFile.inputStream().use { load(it) } }
        signingConfigs.create("release") {
            storeFile = rootProject.file(settings.getProperty("storeFile"))
            storePassword = settings.getProperty("storePassword")
            keyAlias = settings.getProperty("keyAlias")
            keyPassword = settings.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (signingFile.isFile) signingConfig = signingConfigs.getByName("release")
        }
    }
}

tasks.register<JavaExec>("testEditor") {
    dependsOn("compileDebugUnitTestKotlin")
    mainClass.set("org.myrec.app.EditorTestsKt")
    classpath = files(tasks.named("compileDebugUnitTestKotlin"), tasks.named("compileDebugKotlin")) +
        configurations.getByName("debugUnitTestRuntimeClasspath")
}
