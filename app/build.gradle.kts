plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val media3 = "1.9.0"          // must match the Jellyfin ffmpeg decoder version below
val libtorrent4j = "2.1.0-39"

android {
    namespace = "com.tormeo.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tormeo.app"
        minSdk = 23            // Android 6.0 – covers old TV boxes
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    flavorDimensions += "device"
    productFlavors {
        // Android TV / TV boxes: 32-bit ARM (armeabi-v7a). Runs on 32-bit boxes
        // and on 64-bit chips with a 32-bit userland (most cheap Amlogic/Rockchip/Allwinner boxes).
        create("tv") {
            dimension = "device"
            applicationIdSuffix = ".tv"
            versionNameSuffix = "-tv"
            buildConfigField("boolean", "IS_TV_BUILD", "true")
            ndk { abiFilters += listOf("armeabi-v7a") }
        }
        // Phones / tablets
        create("mobile") {
            dimension = "device"
            buildConfigField("boolean", "IS_TV_BUILD", "false")
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so the APK is installable out of the box.
            // Replace with your own signingConfig before publishing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions { unitTests.isReturnDefaultValues = true }

    lint {
        checkReleaseBuilds = true
        abortOnError = false
    }

    packaging {
        jniLibs { useLegacyPackaging = true } // extract .so files, required for some old devices
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.12.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")

    // Player: hardware decoding via MediaCodec, ffmpeg only as audio fallback (DTS/TrueHD/AC3 on devices without decoders)
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:$media3+1")

    // Torrent engine (native libtorrent)
    implementation("org.libtorrent4j:libtorrent4j:$libtorrent4j")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:$libtorrent4j")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:$libtorrent4j")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")

    testImplementation("junit:junit:4.13.2")
}
