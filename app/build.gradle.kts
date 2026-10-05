plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Glyph-подсветка (Nothing Phone 2, личная фишка) собирается, только если сам
// положил app/libs/glyph-matrix-sdk-2.0.aar. Нет файла — используется заглушка
// без единого внешнего импорта, и сборка проходит как обычно, без Glyph.
// Объявлено на уровне всего файла — видно и в android{}, и в dependencies{} ниже.
val hasGlyphSdk = file("libs/glyph-matrix-sdk-2.0.aar").exists()

android {
    namespace = "ru.avrora.player"
    compileSdk = 34

    defaultConfig {
        applicationId = "ru.avrora.player"
        minSdk = 26          // Android 8.0 и новее
        targetSdk = 34
        versionCode = 18
        versionName = "3.6"
    }

    sourceSets {
        getByName("main") {
            java.srcDir(if (hasGlyphSdk) "src/glyphOn/java" else "src/glyphOff/java")
        }
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
    // Glyph-подсветка на Nothing Phone (2), личная фишка. Файл клади сам в app/libs/ —
    // он не в Maven, официально раздаётся только как AAR внутри репозитория Nothing.
    // Подключается, только если файл реально на месте (см. hasGlyphSdk выше).
    if (hasGlyphSdk) implementation(files("libs/glyph-matrix-sdk-2.0.aar"))
    implementation("com.google.guava:guava:33.2.1-android")
}
