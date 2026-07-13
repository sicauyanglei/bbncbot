plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bbncbot.ocr"
    compileSdk = 34

    defaultConfig {
        // 独立 applicationId，与主 APK 区分
        // 主 APK 通过 bindService("com.bbncbot.ocr.action.RECOGNIZE") 连接此模块
        applicationId = "com.bbncbot.ocr"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // 与主 APK 用同一 release.keystore 签名：
    // signature 级自定义权限要求调用方与声明方同签名，只有同签名的 :app 才能绑定 :ocr 的 Service
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("app/release.keystore")
            storePassword = "bbncbot123"
            keyAlias = "bbncbot"
            keyPassword = "bbncbot123"
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (rootProject.file("app/release.keystore").exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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

    buildFeatures {
    }
}

dependencies {
    // ML Kit 中文文本识别（bundled 模型）
    // 此 APK 唯一职责：提供 OCR 识别能力，安装一次后模型不变，主 APK 频繁更新无需重装此包
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.0")
}
