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

        // 肥料卡片结构签名：记录肥料元素所在容器的子节点类型序列
        // 用于"结构极度相似的页面归为一类"——商品名/数字变化但卡片布局不变则归为同类
        val fertStructSig = root?.let { extractFertStructSig(it, pageTexts) } ?: ""

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
            fertilizerTaskDesc = fertTaskDesc,
            fertStructSig = fertStructSig
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

    /**
     * 提取肥料卡片结构签名
     *
     * 算法：
     * 1. 遍历节点树，找到文本匹配 [FERTILIZER_DESC_PATTERNS] 的节点（肥料文本节点）
     * 2. 从该节点向上找最近的"卡片容器"（childCount >= 2 的祖先，或 Layout/RecyclerView 的直接子项）
     * 3. 记录容器的结构指纹：容器类型简写 + 子节点数量 + 子节点类型序列
     *
     * 为什么这样设计：
     * - 同一肥料任务的不同轮次，商品名/数字变化但卡片布局结构不变
     * - 结构签名只看节点类型（TextView/Button/ImageView 等），不看具体文本内容
     * - 广告元素不在肥料节点附近，自然被忽略
     * - 格式紧凑（如 "c=Lin|n=3|ch=TV,Btn,TV"），便于模糊比较
     *
     * @param root 页面根节点
     * @param pageTexts 页面文本列表（用于快速判断是否有肥料元素）
     * @return 结构签名，无肥料元素返回 ""
     */
    fun extractFertStructSig(root: AccessibilityNodeInfo, pageTexts: List<String>): String {
        // 快速判断：页面无肥料文本则不提取结构
        val hasFertText = pageTexts.any { text ->
            FERTILIZER_DESC_PATTERNS.any { p -> p.containsMatchIn(text) } ||
                FERTILIZER_KEYWORDS.any { text.contains(it) || text.contains("肥") }
        }
        if (!hasFertText) return ""

        // 找到第一个肥料文本节点
        val fertNode = findFirstFertilizerNode(root) ?: return ""
        // 向上找卡片容器
        val container = findCardContainer(fertNode) ?: fertNode
        // 生成结构签名
        return buildStructSig(container)
    }

    /**
     * 递归查找第一个文本匹配肥料模式的节点
     */
    private fun findFirstFertilizerNode(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 30) return null
        val text = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
        if (text.isNotEmpty() && FERTILIZER_DESC_PATTERNS.any { it.containsMatchIn(text) }) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstFertilizerNode(child, depth + 1)
            if (found != null) return found
        }
        return null
    }

    /**
     * 从肥料节点向上找最近的"卡片容器"
     *
     * 卡片容器定义：
     * - childCount >= 2（有多个子节点，是布局容器而非叶子）
     * - 或 className 含 "Layout"/"RecyclerView"/"CardView"
     * - 最多向上找 5 层，避免走到整个页面根节点
     *
     * @return 卡片容器节点，找不到返回原节点
     */
    private fun findCardContainer(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var steps = 0
        while (current != null && steps < 5) {
            val parent = current.parent ?: break
            val cls = parent.className?.toString() ?: ""
            // 容器判定：子节点数 >= 2 或是布局类
            if (parent.childCount >= 2 || cls.contains("Layout") || cls.contains("RecyclerView") || cls.contains("CardView")) {
                return parent
            }
            current = parent
            steps++
        }
        return null
    }

    /**
     * 构建容器的结构签名
     *
     * 格式：`c=<容器简写>|n=<子节点数>|ch=<子节点类型序列>`
     * 例：`c=Lin|n=3|ch=TV,Btn,TV`
     *
     * - 容器简写：LinearLayout→Lin, RelativeLayout→Rel, FrameLayout→FL, RecyclerView→RV, TextView→TV, Button→Btn, ImageView→Img, WebView→Web, 其他取类名末段
     * - 子节点类型序列：直接子节点的简写列表，逗号分隔
     */
    private fun buildStructSig(container: AccessibilityNodeInfo): String {
        val containerShort = classNameToShort(container.className?.toString())
        val childCount = container.childCount
        val childTypes = mutableListOf<String>()
        for (i in 0 until childCount) {
            val child = container.getChild(i) ?: continue
            childTypes.add(classNameToShort(child.className?.toString()))
        }
        return "c=$containerShort|n=$childCount|ch=${childTypes.joinToString(",")}"
    }

    /**
     * className 转简写（用于结构签名，紧凑且稳定）
     *
     * 映射常见 Android 视图类到 2-3 字母简写：
     * - android.widget.LinearLayout → Lin
     * - android.widget.RelativeLayout → Rel
     * - android.widget.FrameLayout → FL
     * - android.widget.TextView → TV
     * - android.widget.Button → Btn
     * - android.widget.ImageView → Img
     * - android.view.View → V
     * - android.webkit.WebView → Web
     * - androidx.recyclerview.widget.RecyclerView → RV
     * - 其他 → 取类名末段（最后一个 `.` 后的部分），限 6 字符
     */
    private fun classNameToShort(className: String?): String {
        if (className.isNullOrEmpty()) return "?"
        val simple = className.substringAfterLast(".")
        return when (simple) {
            "LinearLayout" -> "Lin"
            "RelativeLayout" -> "Rel"
            "FrameLayout" -> "FL"
            "TextView" -> "TV"
            "Button" -> "Btn"
            "ImageView" -> "Img"
            "View" -> "V"
            "WebView" -> "Web"
            "RecyclerView" -> "RV"
            "CardView" -> "CV"
            "EditText" -> "ET"
            "CheckBox" -> "CB"
            "ImageButton" -> "ImgBtn"
            else -> simple.take(6)
        }
    }

    /**
     * 比较两个结构签名的相似度（模糊匹配）
     *
     * 判定"极度相似"的条件（全部满足）：
     * 1. 容器类型相同（c= 相同）
     * 2. 子节点数量差异 ≤ 1（|n1 - n2| ≤ 1）
     * 3. 子节点类型序列的编辑距离 ≤ 1（允许增删1个节点类型）
     *
     * @return true 表示结构极度相似，可归为同一类
     */
    fun isStructSimilar(sig1: String, sig2: String): Boolean {
        if (sig1.isEmpty() || sig2.isEmpty()) return false
        if (sig1 == sig2) return true
        val p1 = parseStructSig(sig1) ?: return false
        val p2 = parseStructSig(sig2) ?: return false
        // 1. 容器类型相同
        if (p1.first != p2.first) return false
        // 2. 子节点数量差异 <= 1
        if (kotlin.math.abs(p1.second - p2.second) > 1) return false
        // 3. 子节点类型序列编辑距离 <= 1
        val dist = editDistance(p1.third, p2.third)
        return dist <= 1
    }

    /** 解析结构签名为 (容器类型, 子节点数, 子节点类型列表) */
    private fun parseStructSig(sig: String): Triple<String, Int, List<String>>? {
        // 格式：c=Lin|n=3|ch=TV,Btn,TV
        val cMatch = Regex("c=([^|]+)").find(sig)?.groupValues?.getOrNull(1) ?: return null
        val nMatch = Regex("n=(\\d+)").find(sig)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
        val chMatch = Regex("ch=([^|]*)").find(sig)?.groupValues?.getOrNull(1) ?: return null
        val chList = if (chMatch.isEmpty()) emptyList() else chMatch.split(",")
        return Triple(cMatch, nMatch, chList)
    }

    /** 计算两个列表的编辑距离（Levenshtein） */
    private fun editDistance(a: List<String>, b: List<String>): Int {
        val m = a.size
        val n = b.size
        if (m == 0) return n
        if (n == 0) return m
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // 删除
                    dp[i][j - 1] + 1,      // 插入
                    dp[i - 1][j - 1] + cost // 替换
                )
            }
        }
        return dp[m][n]
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
