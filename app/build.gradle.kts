plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val signingKeystore = System.getenv("HOMETV_KEYSTORE_FILE")
val signingStorePassword = System.getenv("HOMETV_SIGNING_STORE_PASSWORD")
val signingKeyAlias = System.getenv("HOMETV_SIGNING_KEY_ALIAS")
val signingKeyPassword = System.getenv("HOMETV_SIGNING_KEY_PASSWORD")
val hasReleaseSigning = listOf(signingKeystore, signingStorePassword, signingKeyAlias, signingKeyPassword)
    .all { !it.isNullOrBlank() }

android {
    namespace = "com.softppj.hometv"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.softppj.hometv"
        minSdk = 23
        targetSdk = 32
        versionCode = System.getenv("HOMETV_VERSION_CODE")?.toIntOrNull() ?: 9
        versionName = System.getenv("HOMETV_VERSION_NAME") ?: "0.9.0"
    }
    if (hasReleaseSigning) {
        signingConfigs.create("hometvRelease") {
            storeFile = file(signingKeystore!!)
            storePassword = signingStorePassword!!
            keyAlias = signingKeyAlias!!
            keyPassword = signingKeyPassword!!
        }
        buildTypes.getByName("release") {
            signingConfig = signingConfigs.getByName("hometvRelease")
        }
    }
    applicationVariants.all {
        outputs.all {
            val apkOutput = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            apkOutput.outputFileName = "HomeTV.apk"
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("org.videolan.android:libvlc-all:3.5.1")
}
