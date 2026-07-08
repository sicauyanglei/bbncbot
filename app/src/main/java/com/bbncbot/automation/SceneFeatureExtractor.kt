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
     * 提取当前页面场景特征
     *
     * @param service 无障碍服务实例
     * @param controllerState 当前状态机状态名（如 "BROWSING_TASK"）
     */
    fun extract(service: FarmAccessibilityService, controllerState: String): SceneFeatures {
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
            controllerState = controllerState
        )
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
