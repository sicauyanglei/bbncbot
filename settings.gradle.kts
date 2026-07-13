pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "BBNCFarmBot"
include(":app")
// 独立 OCR APK 模块：含 ML Kit 中文识别模型，安装一次后不变
// 主 APK（:app）通过 AIDL 跨进程调用 :ocr 的识别能力，避免每次主包更新都打包 ~20-30MB 模型
include(":ocr")
