plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bbncbot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bbncbot"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    // release 签名（CI 用仓库内固定的 release.keystore，保证每次签名一致可覆盖安装）
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            storeFile = file("release.keystore")
            storePassword = "bbncbot123"
            keyAlias = "bbncbot"
            keyPassword = "bbncbot123"
            // 启用 v1（JAR）签名：AGP 8.x 默认只生成 v2/v3，
            // 但 Android 7.0 以下及部分国产 ROM（华为/小米旧版）只认 v1，
            // 缺失 v1 会被识别为"未签名/签名冲突"导致安装失败。
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 优先用 release.keystore；本地没有则用 debug 签名（仍能安装测试）
            signingConfig = if (file("release.keystore").exists()) {
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
