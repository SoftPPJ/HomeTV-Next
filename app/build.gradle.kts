plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.softppj.hometv"
    compileSdk = 35

    defaultConfig {
        // Separate application ID: installs beside V6 without sharing app data.
        applicationId = "com.softppj.myhometv.next"
        minSdk = 23
        targetSdk = 32
        versionCode = 1
        versionName = "1.0.0-dev"
    }

    applicationVariants.all {
        outputs.all {
            val apkOutput = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            apkOutput.outputFileName = "HomeTV-Next-V1-debug.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.5.1")
    implementation("androidx.media3:media3-exoplayer-smoothstreaming:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    // GPLv3 decoder; retain only with appropriate distribution/licensing compliance.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")
    implementation("org.videolan.android:libvlc-all:3.5.1")
}
