package com.bbncbot.automation

import android.view.accessibility.AccessibilityNodeInfo
import com.bbncbot.service.FarmAccessibilityService

/**
 * 场景特征提取器（已精简）
 *
 * 录制 / 规则匹配系统已整体移除，本对象仅保留任务排序用到的任务内容标识提取：
 * - [extractTaskContentText]：从任务按钮所在行提取稳定的任务内容标识（如"看严选推荐商品"），
 *   供 [AutomationController.sortTaskButtons] 做任务排序去重。
 */
object SceneFeatureExtractor {

    /**
     * 任务内容标识提取时需要去掉的按钮文案片段
     *
     * 任务行上下文通常是 "[任务描述] [次数] [去完成]" 形式，
     * 去掉这些通用按钮文案后剩下的就是任务标识。
     */
    private val TASK_BUTTON_TEXTS = listOf(
        "去完成", "去逛逛", "去浏览", "去下单", "去搜索", "去看", "去试玩", "去参与",
        "领取", "立即领取", "开心收下", "收下", "知道了", "好的",
        "已完成", "完成", "已领取", "未完成",
        "立即", "马上", "前往"
    )

    /**
     * 从任务按钮所在行的上下文文本中提取任务内容标识
     *
     * 任务行结构通常是："[任务描述] [次数] [去完成]"
     * 例："看严选推荐商品 (1/4) 去完成"
     *
     * 清理规则：
     * 1. 调用 [FarmAccessibilityService.collectTaskContextText] 获取任务行所有文本
     * 2. 去掉次数后缀："(1/4)" "（2/4）" "[1/4]" "1/4" 等（任务进度会变，不稳定）
     * 3. 去掉按钮文案："去完成" "去逛逛" "领取" 等（通用文案，无区分度）
     * 4. 去掉多余空白，trim
     * 5. 失败时返回空字符串（不影响排序）
     *
     * @return 任务内容标识（如"看严选推荐商品"），失败返回 ""
     */
    fun extractTaskContentText(
        service: FarmAccessibilityService,
        taskButton: AccessibilityNodeInfo
    ): String {
        return try {
            val contextText = service.collectTaskContextText(taskButton)
            if (contextText.isBlank()) return ""
            var text = contextText
            // 1. 去掉次数后缀："(1/4)" "（2/4）" "[1/4]" "1/4" 等
            //    匹配括号内或独立的 "数字/数字" 形式
            text = text.replace(Regex("[\\(（\\[]\\s*\\d+\\s*/\\s*\\d+\\s*[\\)）\\]]"), "")
            text = text.replace(Regex("\\b\\d+\\s*/\\s*\\d+\\b"), "")
            // 2. 去掉按钮文案（通用文案，无区分度）
            for (btn in TASK_BUTTON_TEXTS) {
                text = text.replace(btn, "")
            }
            // 3. 去掉纯数字片段（如剩余的次数等）
            text = text.replace(Regex("\\b\\d+\\b"), "")
            // 4. 合并多余空白，trim
            text = text.trim().replace(Regex("\\s+"), " ")
            // 5. 长度限制：超过 40 字符视为噪音（H5 页面 collectTaskContextText 向上 2 层
            //    parent 可能收集到整页广告横幅文本，非真实任务行）
            //    真实任务内容如"看严选推荐商品""浏览精选好物"通常 5-15 字
            if (text.length > 40) {
                ""
            } else {
                text
            }
        } catch (e: Exception) {
            ""
        }
    }
}
