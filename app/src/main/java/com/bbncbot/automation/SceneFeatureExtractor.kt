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
     * 提取当前页面场景特征
     *
     * @param service 无障碍服务实例
     * @param controllerState 当前状态机状态名（如 "BROWSING_TASK"）
     * @param taskButton 当前任务按钮节点（仅 PROCESSING_TASK 决策点传入，用于提取任务行上下文文本）
     *                   传入时会把任务行描述文本清理为任务标识，区分不同任务内容的规则
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

        // 任务内容标识：仅当传入 taskButton 时提取（PROCESSING_TASK 决策点 / 录制点击事件）
        val taskText = taskButton?.let { extractTaskContentText(service, it) } ?: ""

        // 肥料任务描述：从页面文本提取稳定的肥料任务标识（用于智能匹配同类任务）
        // 同一肥料任务的不同轮次商品可能不同，但任务描述（如"看精选商品得肥料"）稳定
        val fertTaskDesc = extractFertilizerTaskDesc(pageTexts)

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
            taskContentText = taskText,
            fertilizerTaskDesc = fertTaskDesc
        )
    }

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
     * 5. 失败时返回空字符串（不影响 signature）
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

    /**
     * 肥料任务描述匹配模式（从页面文本提取稳定的肥料任务标识）
     *
     * 匹配形如：
     * - "浏览15s得300肥料" / "每浏览15s最高得2000肥"
     * - "看精选商品得肥料" / "逛好物最高得1500肥料"
     * - "30个居民订单必得3000肥"
     * - "还差4次领肥料" / "立即领肥" / "做任务集肥料"
     * - "100肥料已发放" / "肥料奖励"
     *
     * 这些描述是稳定的（同一任务的不同轮次不会变），适合作为 signature 核心标识。
     * 商品名/数字/搜索词等易变文本不会被这些模式匹配。
     */
    private val FERTILIZER_DESC_PATTERNS = listOf(
        // 得X肥料 / 得肥料 / 最高得X肥 / 领X肥料 / 获X肥
        Regex("[^\\n]{0,20}(?:得|领|获)\\s*\\d*\\s*肥[肥料料]?[^\\n]{0,15}"),
        // X肥料已发放 / 肥料奖励 / X肥料
        Regex("\\d+\\s*肥料?[^\\n]{0,10}"),
        // 还差X次领肥料 / 立即领肥 / 做任务集肥料 / 领取 / 施肥
        Regex("(?:还差\\d+次领肥料|立即领肥|做任务集肥料|领取|施肥)[^\\n]{0,15}")
    )

    /**
     * 肥料关键字（用于判断页面是否含肥料相关信息）
     */
    private val FERTILIZER_KEYWORDS = listOf(
        "肥料", "领肥", "施肥", "已发放", "领取成功", "做任务集肥料"
    )

    /**
     * 从页面文本中提取肥料任务描述（稳定的肥料任务标识）
     *
     * 提取策略：
     * 1. 遍历 [pageTexts]，用 [FERTILIZER_DESC_PATTERNS] 匹配含肥料的任务描述
     * 2. 优先返回含明确奖励数额的描述（如"得300肥料""最高得2000肥"）
     * 3. 清理多余空白，限制长度（避免整页文本污染）
     *
     * 为什么这样提取：
     * - 同一肥料任务（如"看精选商品得肥料"）的不同轮次，页面展示的商品可能不同
     * - 但任务描述"看精选商品得肥料"是稳定的，可作为 signature 核心标识
     * - 这样同一任务的不同页面自动归为同一类规则，实现智能匹配
     *
     * @param pageTexts 页面可见文本列表
     * @return 肥料任务描述（如"看精选商品得肥料"），无匹配返回 ""
     */
    fun extractFertilizerTaskDesc(pageTexts: List<String>): String {
        for (pattern in FERTILIZER_DESC_PATTERNS) {
            for (pageText in pageTexts) {
                val match = pattern.find(pageText)
                if (match != null) {
                    val desc = match.value.trim()
                        .replace(Regex("\\s+"), " ")
                        .take(30)  // 限制长度，避免整页文本
                    if (desc.isNotEmpty() && FERTILIZER_KEYWORDS.any { desc.contains(it) || desc.contains("肥") }) {
                        return desc
                    }
                }
            }
        }
        return ""
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
