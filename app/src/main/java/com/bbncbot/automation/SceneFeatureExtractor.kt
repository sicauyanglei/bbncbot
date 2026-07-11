package com.bbncbot.automation

import android.view.accessibility.AccessibilityNodeInfo
import com.bbncbot.service.FarmAccessibilityService

/**
 * 场景特征提取器
 *
 * 把当前页面（AccessibilityService 节点树）抽象成 [SceneFeatures]。
 *
 * 设计：
 * - **纯本地**：不调 OCR / 不调 AI，毫秒级完成
 * - **复用现有检测方法**：[FarmAccessibilityService] 已有 isXxxPage / findXxxHint 等方法，
 *   这里只是聚合调用，不重复实现识别逻辑
 * - **可点击按钮提取**：遍历节点树收集 clickable + 有文本的节点，作为"操作候选"
 *
 * 调用时机：每个决策点（滑动前 / 退出前 / 点按钮前）调用 [extract]，
 * 结果传给 [SceneLibrary] 做规则匹配。
 */
object SceneFeatureExtractor {

    /**
     * 任务内容关键词提取的停用词（出现即丢弃，不作为关键词）
     *
     * - 通用按钮文案：去完成 / 领取 / 已完成 / 立即 / 去 / 等等
     * - 数字相关：纯数字（如任务次数 "1/3"）
     * - 单字（信息量太低）
     */
    private val TASK_STOPWORDS = setOf(
        "去完成", "去逛逛", "去浏览", "去下单", "去搜索", "去看", "去试玩",
        "领取", "立即领取", "开心收下", "收下", "知道了", "好的",
        "已完成", "完成", "已领取", "未完成",
        "立即", "马上", "前往",
        "任务", "奖励", "肥料", "得", "可得", "可领取",
        "去", "看", "玩", "得", "了", "的", "和", "与",
        "1/3", "2/3", "3/3"
    )

    /**
     * 提取当前页面场景特征
     *
     * @param service 无障碍服务实例
     * @param controllerState 当前状态机状态名（如 "BROWSING_TASK"）
     * @param taskButton 当前任务按钮节点（仅 PROCESSING_TASK 决策点传入，用于提取任务行上下文关键词）
     *                   传入时会把任务行描述文本提取为关键词，区分不同任务内容的规则
     */
    fun extract(
        service: FarmAccessibilityService,
        controllerState: String,
        taskButton: AccessibilityNodeInfo? = null
    ): SceneFeatures {
        // 页面定位
        val pkg = service.getCurrentWindowPackage() ?: "null"
        val activity = service.getCurrentActivityName() ?: "null"
        val platform = service.currentPlatform.name
        val onFarm = service.isOnFarmPage()

        // 任务识别（按"非互斥"方式独立检测，因为某些页面可能同时触发多个信号）
        val isTaskComplete = service.isTaskCompletePage()
        val isAbnormal = service.isOnAbnormalPage()
        val isPaidSearch = service.isSearchRecommendPage()
        val isSearchBrowseTask = service.isSearchBrowseTaskPage()
        val browseDurationSeconds = service.findBrowseDurationRewardHint()
        val isBrowseDuration = browseDurationSeconds > 0
        // isBrowseTaskPage：有"每浏览x秒""再逛x秒"提示 或 浏览时长提示 或 搜索浏览任务
        val hasCountdownHint = service.findBrowseRewardCountdownHint() > 0
        val hasProgressHint = service.hasBrowseRewardProgressHint()
        val isBrowseTask = hasCountdownHint || hasProgressHint || isBrowseDuration || isSearchBrowseTask

        // 弹窗检测
        val hasRedPacket = service.findRedPacketCloseButton() != null
        val hasFasterRewardEntry = service.findFasterRewardEntryButton() != null
        val hasFasterRewardPopup = service.isFasterRewardPopupShown()
        val hasRewardUpgrade = service.isRewardUpgradePopupShown()

        // 倒计时
        val countdown = service.findBrowseRewardCountdownHint()

        // 页面文本 + 可点按钮
        val root = service.getRootInFarmApp()
        val pageTexts = root?.let { collectTexts(it) } ?: emptyList()
        val clickableButtons = root?.let { collectClickableTexts(it) } ?: emptyList()

        // 任务内容关键词：仅当传入 taskButton 时提取（PROCESSING_TASK 决策点 / 录制点击事件）
        val taskKeywords = taskButton?.let { extractTaskKeywords(service, it) } ?: emptyList()

        return SceneFeatures(
            windowPackage = pkg,
            windowActivity = activity,
            platform = platform,
            onFarmPage = onFarm,
            isBrowseTaskPage = isBrowseTask,
            isBrowseDurationTask = isBrowseDuration,
            browseDurationSeconds = browseDurationSeconds,
            isSearchBrowseTaskPage = isSearchBrowseTask,
            isPaidSearchPage = isPaidSearch,
            isAbnormalPage = isAbnormal,
            isTaskComplete = isTaskComplete,
            countdownSeconds = countdown,
            hasBrowseProgressHint = hasProgressHint,
            hasRedPacketPopup = hasRedPacket,
            hasFasterRewardEntry = hasFasterRewardEntry,
            hasRewardUpgradePopup = hasRewardUpgrade,
            pageTexts = pageTexts.take(30),
            clickableButtons = clickableButtons.distinct().take(20),
            controllerState = controllerState,
            taskContentKeywords = taskKeywords
        )
    }

    /**
     * 从任务按钮所在行的上下文文本中提取任务内容关键词
     *
     * - 调用 [FarmAccessibilityService.collectTaskContextText] 获取任务行描述
     * - 按标点/空格切分，过滤停用词 / 纯数字 / 单字
     * - 取前 5 个关键词（去重，保持出现顺序）
     * - 失败时返回空列表（不影响 signature）
     *
     * @return 任务内容关键词列表（如 ["浏览", "精选", "好物"]），最多 5 个
     */
    private fun extractTaskKeywords(
        service: FarmAccessibilityService,
        taskButton: AccessibilityNodeInfo
    ): List<String> {
        return try {
            val contextText = service.collectTaskContextText(taskButton)
            if (contextText.isBlank()) return emptyList()
            // 按标点 / 空格 / 数字边界切分
            val tokens = contextText.split(Regex("[\\s,，。.；;、:：!！?？/]+"))
            val keywords = mutableListOf<String>()
            for (token in tokens) {
                val t = token.trim()
                if (t.isEmpty()) continue
                if (t in TASK_STOPWORDS) continue
                if (t.matches(Regex("\\d+"))) continue              // 纯数字
                if (t.length < 2) continue                           // 单字信息量太低
                if (t.any { it.isDigit() } && t.length <= 3) continue // "15秒"等数字+单位太具体，不稳定
                if (t !in keywords) keywords.add(t)
                if (keywords.size >= 5) break
            }
            keywords
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 递归收集节点树所有文本（同 [FarmAccessibilityService.collectAllText]，避免改可见性） */
    private fun collectTexts(node: AccessibilityNodeInfo, depth: Int = 0): List<String> {
        if (depth > 30) return emptyList()
        val result = mutableListOf<String>()
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { result.add(it) }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { result.add(it) }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { result.addAll(collectTexts(it, depth + 1)) }
        }
        return result
    }

    /** 递归收集可点击节点的文本（按钮文案） */
    private fun collectClickableTexts(node: AccessibilityNodeInfo, depth: Int = 0): List<String> {
        if (depth > 30) return emptyList()
        val result = mutableListOf<String>()
        if (node.isClickable) {
            val text = node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            val desc = node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            (text ?: desc)?.let { result.add(it) }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { result.addAll(collectClickableTexts(it, depth + 1)) }
        }
        return result
    }
}
