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
        // 构建标识：CI 通过 -PBUILD_LABEL=build{number}-{sha} 注入
        // 本地构建为 "local"，日志打印此字段可证明 APK 来源版本
        buildConfigField(
            "String",
            "BUILD_LABEL",
            "\"${project.findProperty("BUILD_LABEL") ?: "local"}\""
        )
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
        buildConfig = true
    }

    // ========== Build Flavors：OCR 架构 - 独立 OCR APK + ContentProvider 跨进程调用 ==========
    //
    // 架构：
    //   - 主 APK（:app）不打包 ML Kit 模型（~20-30MB），通过 ContentProvider call() 调用独立安装的
    //     OCR APK（com.bbncbot.ocr）的识别能力。OCR APK 装一次后不变，主包频繁更新无需重装 OCR。
    //   - :ocr 模块是独立 application，含 ML Kit 中文识别模型，提供 OcrContentProvider
    //   - 安全：OcrContentProvider.call() 内部包名白名单（com.bbncbot）校验调用方，不依赖 signature 权限
    //
    // Flavor 区别：
    //   - noOcr（默认/发布）：OcrProvider 通过 ContentResolver.call() 调用 :ocr 模块
    //     → 主 APK 体积小，依赖外部 OCR APK（需单独安装一次）
    //   - full（fallback）：OcrProvider 直接内联 ML Kit 调用
    //     → 主 APK 自带 OCR，体积大，作为 :ocr 模块不可用时的备用方案
    //
    // 构建命令：
    //   日常调试主包： ./gradlew assembleNoOcrDebug    → app-noOcr-debug.apk（小，需装 OCR APK）
    //   发布主包：     ./gradlew assembleNoOcrRelease  → app-noOcr-release.apk
    //   OCR 模块包：   ./gradlew :ocr:assembleRelease  → ocr-release.apk（装一次后不变）
    //   fallback 包：  ./gradlew assembleFullRelease   → app-full-release.apk（自带 OCR，大）
    //
    // 源码组织：
    //   src/noOcr/java/.../OcrProvider.kt       ContentProvider 客户端（ContentResolver.call 调用 :ocr）
    //   src/full/java/.../OcrProvider.kt        ML Kit 内联实现（fallback）
    flavorDimensions += "ocr"
    productFlavors {
        create("noOcr") {
            dimension = "ocr"
            // 不带 ML Kit，通过 ContentProvider call() 调用独立 OCR APK（需安装 com.bbncbot.ocr）
        }
        create("full") {
            dimension = "ocr"
            // 自带 ML Kit，作为 fallback（OCR APK 不可用时使用）
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
    // 仅 full flavor 打包：noOcr 通过 ContentProvider 调用独立 :ocr 模块，主 APK 不含此依赖
    // 用于自动化运行时 OCR 读取农场主页肥料总数（无障碍节点树在 H5 页读不到）
    // 注意：Kotlin DSL 不自动生成 fullImplementation() 函数，需用 add() 显式添加到 flavor 配置
    add("fullImplementation", "com.google.mlkit:text-recognition-chinese:16.0.0")
}
