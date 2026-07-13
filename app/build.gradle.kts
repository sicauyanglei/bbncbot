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
        versionCode = 12
        versionName = "2.1"
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

    // ========== Build Flavors：按是否打包 OCR 模型区分 APK 体积 ==========
    //
    // 背景问题：ML Kit 中文识别（text-recognition-chinese）是 bundled 模型，
    // 体积约 20-30MB，是 APK 体积的大头。调试阶段每次打 debug 包都带上 OCR 模型
    // 拖慢构建/安装速度，而调试时主要验证规则匹配/回放/录制逻辑，OCR 只是兜底。
    //
    // 方案：拆成两个 flavor
    //   - noOcr：不打包 ML Kit，OcrProvider 返回 -1（APK 小，用于日常调试）
    //   - full ：打包 ML Kit，OcrProvider 走真实 OCR（用于稳定版大包/上线）
    //
    // 构建命令：
    //   日常调试： ./gradlew assembleNoOcrDebug        → app-noOcr-debug.apk（小）
    //   稳定大包： ./gradlew assembleFullRelease       → app-full-release.apk（含 OCR）
    //
    // 源码组织：
    //   src/main/java/...           共用代码（RecordingManager 调 OcrProvider）
    //   src/noOcr/java/.../OcrProvider.kt   空实现（返回 -1）
    //   src/full/java/.../OcrProvider.kt    ML Kit 真实实现
    flavorDimensions += "ocr"
    productFlavors {
        create("noOcr") {
            dimension = "ocr"
            // 不带 ML Kit，applicationId 不变，可直接覆盖安装
        }
        create("full") {
            dimension = "ocr"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // ML Kit 中文文本识别（bundled 模型，不依赖 Play Services，国产 ROM 友好）
    // 仅 full flavor 打包：noOcr 调试包不带此依赖，APK 体积小 ~20-30MB
    // 用于录制时 OCR 读取农场主页肥料总数（无障碍节点树在 H5 页读不到）
    fullImplementation("com.google.mlkit:text-recognition-chinese:16.0.0")
}
