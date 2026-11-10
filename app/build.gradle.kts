import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val cueVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use(::load)
}
val cueSigningNames = listOf("CUEFLOW_KEYSTORE_FILE", "CUEFLOW_KEYSTORE_PASSWORD", "CUEFLOW_KEY_ALIAS", "CUEFLOW_KEY_PASSWORD")
val cueSigning = cueSigningNames.associateWith { providers.environmentVariable(it).orNull }
val cueHasSigning = cueSigning.values.all { !it.isNullOrBlank() }

android {
    ndkVersion = "28.2.13676358"
    namespace = "com.novaflow.cueflow"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.novaflow.cueflow"
        minSdk = 29
        targetSdk = 37
        versionCode = cueVersion.getProperty("versionCode").toInt()
        versionName = cueVersion.getProperty("versionName")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        if (cueHasSigning) {
            create("official") {
                storeFile = rootProject.file(checkNotNull(cueSigning["CUEFLOW_KEYSTORE_FILE"]))
                storePassword = cueSigning["CUEFLOW_KEYSTORE_PASSWORD"]
                keyAlias = cueSigning["CUEFLOW_KEY_ALIAS"]
                keyPassword = cueSigning["CUEFLOW_KEY_PASSWORD"]
            }
        }
    }
    buildTypes {
        release {
            isDebuggable = false
            if (cueHasSigning) signingConfig = signingConfigs.getByName("official")
            optimization {
                // Partial library optimization currently creates an IllegalAccessError in
                // ProcessLifecycleInitializer at startup. Keep the first official APK reliable.
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    androidResources {
        noCompress += "onnx"
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    sourceSets["main"].assets.directories.add(rootProject.file(".cueflow-local/sherpa/assets").absolutePath)
}

val requireReleaseSigning = tasks.register("requireReleaseSigning") {
    doLast {
        check(cueHasSigning) {
            "Official release builds require CUEFLOW_KEYSTORE_FILE, CUEFLOW_KEYSTORE_PASSWORD, CUEFLOW_KEY_ALIAS, and CUEFLOW_KEY_PASSWORD."
        }
    }
}
tasks.matching { it.name == "packageRelease" || it.name == "validateSigningRelease" }.configureEach {
    dependsOn(requireReleaseSigning)
}

dependencies {
    implementation(files(rootProject.file(".cueflow-local/sherpa/sherpa-onnx-2.0.1.aar")))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.camera.viewfinder:viewfinder-view:1.6.2")
    implementation("com.google.guava:listenablefuture:1.0")
    implementation("org.commonmark:commonmark:0.32.0")
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
