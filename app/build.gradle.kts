plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.softppj.hometv"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.softppj.hometv"
        minSdk = 23
        targetSdk = 32
        versionCode = 14
        versionName = "6.0.0"
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
    // FFmpeg software audio decoder. Media3 hardware/platform decoders remain preferred.
    // Jellyfin 1.5.0+1 matches the existing Media3 1.5.x API; GPLv3 applies.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")
    implementation("org.videolan.android:libvlc-all:3.5.1")
}
