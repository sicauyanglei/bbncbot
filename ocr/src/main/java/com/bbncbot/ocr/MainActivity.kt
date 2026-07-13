package com.bbncbot.ocr

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * OCR 模块启动页
 *
 * 仅用于在桌面显示一个图标，让用户确认 OCR 模块已安装。
 * 无实际交互功能——真正的识别能力由 [OcrContentProvider] 通过 ContentProvider call() 提供。
 *
 * 显示信息：
 * - 模块版本号
 * - ML Kit 模型状态（已加载）
 * - 主 APK 调用说明
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val textView = TextView(this).apply {
            setPadding(48, 96, 48, 48)
            textSize = 16f
            text = buildString {
                append("BBNCFarmBot OCR 模块\n\n")
                try {
                    val info = packageManager.getPackageInfo(packageName, 0)
                    append("版本: ${info.versionName} (${info.versionCode})\n")
                } catch (_: PackageManager.NameNotFoundException) {
                    append("版本: 未知\n")
                }
                append("ML Kit 中文识别: 已加载\n\n")
                append("此模块由主应用（bbncbot）自动调用，\n")
                append("安装一次后无需更新，除非 OCR 模型升级。\n\n")
                append("可安全地将此应用从最近任务列表划掉，\n")
                append("主应用调用时会自动唤醒。")
            }
        }
        setContentView(textView)
    }
}
