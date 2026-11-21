plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.iptv.tv"
    compileSdk = 34

    defaultConfig {
        // Uygulamanin Android / Play kimligi (kod paketi namespace olarak com.iptv.tv kalir)
        // Orjinal GitHub deposuyla uyumluluk icin dedplay applicationId korundu.
        applicationId = "nl.dedplay.iptv"
        minSdk = 23
        targetSdk = 34
        versionCode = 58
        versionName = "1.9.31"
        // Sadece ARM islemciler (TV kutulari, telefon, tablet, arac kutulari): APK daha kucuk
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = "dedplay.apk"
        }
    }

    // Kendi surum anahtari: ayarlar ~/.gradle/gradle.properties dosyasinda (proje disinda, GitHub'a gitmez).
    // Ayarlar yoksa (orn. projeyi baska biri derlerse) debug anahtariyla imzalanir.
    val releaseStore = (project.findProperty("DEDPLAY_STORE_FILE") as String?)?.let { file(it) }
    val hasReleaseKey = releaseStore != null && releaseStore.exists()

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = releaseStore
                storePassword = project.findProperty("DEDPLAY_STORE_PASSWORD") as String?
                keyAlias = project.findProperty("DEDPLAY_KEY_ALIAS") as String?
                keyPassword = project.findProperty("DEDPLAY_KEY_PASSWORD") as String?
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // FFmpeg ses cozucu kutuphanesi icin gerekli
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.3.1")
    implementation("androidx.media3:media3-extractor:1.3.1")
    // FFmpeg ses cozucu: cihazda AC3/E-AC3/DTS vb. yoksa (UHD/4K kanallarda ses yok sorunu)
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.3.1+1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

// Release derlemesinden sonra surum numarali bir kopya da olustur (orn. dedplay-v1.6.apk).
// GitHub release'e ikisi de yuklenir: dedplay.apk sabit indirme linki icin, numarali olan kullanicilar icin.
tasks.matching { it.name == "assembleRelease" }.configureEach {
    val vName = android.defaultConfig.versionName
    doLast {
        val dir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        val src = File(dir, "dedplay.apk")
        if (src.exists()) src.copyTo(File(dir, "dedplay-v$vName.apk"), overwrite = true)
    }
}
