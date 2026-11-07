plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.softppj.hometv"
    compileSdk = 36
    buildFeatures { buildConfig = true }

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

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // CI prefetches every SDK used by the Robolectric tests. Explicitly
        // enable offline resolution so Robolectric never falls back to a
        // one-shot MavenArtifactFetcher request.
        unitTests.all {
            it.systemProperty("robolectric.offline", "true")
            it.systemProperty(
                "robolectric.dependency.dir",
                layout.buildDirectory.dir("robolectric-deps").get().asFile.absolutePath
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.11.1")
    implementation("androidx.media3:media3-exoplayer-smoothstreaming:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    // OpenGL video effects used by the optional Media3 picture presets.
    implementation("androidx.media3:media3-effect:1.11.1")
    // Keep the LibVLC version aligned with the V6 build that was previously confirmed on the target TV.
    // GPLv3 decoder; retain only with appropriate distribution/licensing compliance.
    implementation("io.github.anilbeesetti:nextlib-media3ext:1.11.1-0.17.0")
    implementation("org.videolan.android:libvlc-all:3.5.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
