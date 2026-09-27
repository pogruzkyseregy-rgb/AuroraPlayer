plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.avrora.player"
    compileSdk = 34

    defaultConfig {
        applicationId = "ru.avrora.player"
        minSdk = 26          // Android 8.0 и новее
        targetSdk = 34
        versionCode = 10
        versionName = "2.8"
    }

    // Постоянный ключ подписи: благодаря ему новые версии
    // устанавливаются поверх старых, без удаления приложения.
    signingConfigs {
        getByName("debug") {
            storeFile = file("aurora-debug.jks")
            storePassword = "aurora123"
            keyAlias = "aurora"
            keyPassword = "aurora123"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1") // радиостанции в формате HLS
    implementation("com.google.guava:guava:33.2.1-android")
}
