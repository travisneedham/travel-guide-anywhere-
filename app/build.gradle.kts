import java.net.URL
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) {
    localPropsFile.inputStream().use { localProps.load(it) }
}

android {
    namespace = "com.travelguide.anywhere"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.travelguide.anywhere"
        minSdk = 26
        targetSdk = 35
        versionCode = 19
        versionName = "1.6.3"

        buildConfigField(
            "String",
            "ANTHROPIC_API_KEY",
            "\"${localProps.getProperty("ANTHROPIC_API_KEY", "YOUR_API_KEY_HERE")}\""
        )
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }
}

// Auto-download sherpa-onnx AAR from GitHub releases on first build (~54 MB, one-time)
run {
    val aarFile = file("${projectDir}/libs/sherpa-onnx-1.13.0.aar")
    if (!aarFile.exists()) {
        aarFile.parentFile.mkdirs()
        println("Downloading sherpa-onnx-1.13.0.aar (~54 MB)…")
        try {
            URL("https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.0/sherpa-onnx-1.13.0.aar")
                .openStream()
                .use { input: java.io.InputStream -> aarFile.outputStream().use { input.copyTo(it) } }
            println("sherpa-onnx download complete.")
        } catch (e: Exception) {
            logger.warn("Failed to download sherpa-onnx AAR: ${e.message}. Build may fail for Kokoro TTS.")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.material)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)

    // Lifecycle
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.lifecycle.service)
    implementation(libs.lifecycle.runtime.ktx)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Retrofit + OkHttp
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp.logging)

    // Coroutines
    implementation(libs.coroutines.android)

    // Location
    implementation(libs.play.services.location)

    // Sherpa-ONNX (on-device Kokoro TTS) — AAR auto-downloaded by the run{} block above
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    // Commons Compress (tar.bz2 extraction for Kokoro model download)
    implementation(libs.commons.compress)
}
