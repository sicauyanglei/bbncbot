package com.bbncbot.automation

/**
 * 场景特征 - 把当前页面抽象成结构化数据，作为决策依据
 *
 * 设计原则：
 * - 字段尽量"原始"，不做判断（判断在 [SceneLibrary] 规则里做）
 * - 可序列化为 JSON 持久化（用于规则匹配 + 学习样本）
 * - 不包含瞬时数据（如时间戳），保证同一场景多次提取结果一致
 *
 * 提取来源：AccessibilityService 节点树文本（[FarmAccessibilityService.collectAllText]）
 * 不依赖 OCR / 截图，毫秒级完成
 */
data class SceneFeatures(

    // ---------- 页面定位 ----------
    /** 当前前台包名（如 "com.ucmobile.lite"） */
    val windowPackage: String,

    /** 当前 Activity 类名（小写，如 "ttdetailactivity"） */
    val windowActivity: String,

    /** 平台枚举名（UC / ALIPAY / TAOBAO / UNKNOWN） */
    val platform: String,

    /** 是否在农场主页 */
    val onFarmPage: Boolean,

    // ---------- 任务识别 ----------
    /** 是否在浏览任务页（有"浏览得奖励""每浏览x秒"等提示） */
    val isBrowseTaskPage: Boolean,

    /** 是否在"浏览x分钟得xxx肥料"长停留任务页 */
    val isBrowseDurationTask: Boolean,

    /** 长停留任务总时长（秒，0 表示非此类任务） */
    val browseDurationSeconds: Int,

    /** 是否在搜索浏览任务页（右上角"搜索后浏览立得奖励"） */
    val isSearchBrowseTaskPage: Boolean,

    /** 是否在付费搜索推荐页（"下单得肥料"等） */
    val isPaidSearchPage: Boolean,

    /** 是否在异常页（交易/收银台等需花钱） */
    val isAbnormalPage: Boolean,

    /** 是否任务完成页（"任务完成""已领取全部奖励"等） */
    val isTaskComplete: Boolean,

    // ---------- 进度状态 ----------
    /** "再逛xx秒后可领奖"倒计时剩余秒数（0 表示无倒计时） */
    val countdownSeconds: Int,

    /** 是否有"每浏览x秒可得1次奖励"进度提示 */
    val hasBrowseProgressHint: Boolean,

    /** 是否有红包弹窗遮挡 */
    val hasRedPacketPopup: Boolean,

    /** 是否有"更快拿奖"入口/弹窗 */
    val hasFasterRewardEntry: Boolean,

    /** 是否有"恭喜获得奖励提升"窗口 */
    val hasRewardUpgradePopup: Boolean,

    // ---------- 关键信号 ----------
    /** 页面所有可见文本（截断到前 30 条避免太长） */
    val pageTexts: List<String>,

    /** 可点击按钮文案列表（去重，最多 20 个） */
    val clickableButtons: List<String>,

    /** 当前状态机状态名（如 BROWSING_TASK / PROCESSING_TASK） */
    val controllerState: String,

    /**
     * 当前任务的内容标识（任务列表里任务行的描述文本，已清理）
     *
     * - 仅在 PROCESSING_TASK 状态点击"去完成"按钮时提取（来自任务行上下文文本）
     * - 已去掉次数后缀（如"(1/4)""（2/4）"）和按钮文案（如"去完成"）
     * - 用于区分"看严选推荐商品""浏览X商品""看广告"等不同任务内容
     * - 录制规则时记录，执行规则时只有任务内容相同的规则才命中
     * - 空字符串表示非任务列表场景（如浏览页/农场主页），不影响 signature
     *
     * 示例："看严选推荐商品" / "浏览精选好物" / "搜一搜你心仪得宝贝"
     */
    val taskContentText: String = "",

    /**
     * 肥料任务描述（从页面文本提取的稳定标识，用于智能匹配同类任务）
     *
     * 提取规则（[SceneFeatureExtractor.extractFertilizerTaskDesc]）：
     * - 从 [pageTexts] 中匹配含"得X肥料""领X肥料""立即领肥""做任务集肥料"等模式的文本
     * - 商品名/数字/搜索词等易变文本不会进入此字段
     *
     * 为什么需要这个字段：
     * - 同一肥料任务（如"看精选商品得肥料 0/3"）的不同轮次，页面展示的商品可能不同
     * - 如果 signature 用 [clickableButtons]（商品名）或 [taskContentText]（整页文本）做匹配键，
     *   同一任务的不同轮次会被误判为不同场景，导致规则无法复用
     * - 用稳定的肥料任务描述作为 signature 核心，同一任务的不同页面自动归为同一类规则
     *
     * 示例：
     * - "看精选商品得肥料" / "逛好物最高得1500肥料" / "每浏览15s最高得2000肥"
     * - "立即领肥" / "还差4次领肥料" / "100肥料已发放"
     * - 空字符串表示当前页面无肥料任务描述
     */
    val fertilizerTaskDesc: String = "",

    /**
     * 肥料卡片结构签名（记录肥料元素所在容器的子节点类型序列）
     *
     * 格式：`c=<容器简写>|n=<子节点数>|ch=<子节点类型序列>`
     * 例：`c=Lin|n=3|ch=TV,Btn,TV`
     *
     * 提取规则（[SceneFeatureExtractor.extractFertStructSig]）：
     * 1. 找到页面中文本匹配肥料模式的节点
     * 2. 向上找最近的卡片容器（childCount >= 2 或 Layout/RecyclerView）
     * 3. 记录容器的 className 简写 + 子节点数量 + 子节点类型序列
     *
     * 为什么需要这个字段：
     * - fertTaskDesc 是文本标识，但同一种肥料任务在不同平台/不同版本可能文案略有差异
     * - 结构签名记录的是页面布局结构（节点类型序列），不依赖文本内容
     * - 结构极度相似（[SceneFeatureExtractor.isStructSimilar]）的页面归为同一类规则
     * - 广告元素不在肥料节点附近，自然被忽略
     * - 空字符串表示当前页面无肥料元素或无法提取结构
     */
    val fertStructSig: String = ""
) {
    /**
     * 生成场景签名：用于规则匹配的稳定键
     *
     * 签名包含"页面类型 + 任务类型 + 进度状态"组合，
     * 同一签名视为同一类场景，应执行相同动作。
     *
     * 不包含瞬时数据（倒计时秒数、pageTexts 全量列表等），
     * 只保留分类性的稳定信号。
     */
    fun signature(): String {
        val parts = mutableListOf<String>()
        // 平台 + 是否农场页
        parts.add("p=$platform")
        parts.add("farm=$onFarmPage")
        // 任务类型（互斥优先级：完成 > 异常 > 付费 > 浏览时长 > 搜索浏览 > 浏览 > 其他）
        parts.add(when {
            isTaskComplete -> "type=complete"
            isAbnormalPage -> "type=abnormal"
            isPaidSearchPage -> "type=paid_search"
            isBrowseDurationTask -> "type=browse_duration(${browseDurationSeconds}s)"
            isSearchBrowseTaskPage -> "type=search_browse"
            isBrowseTaskPage -> "type=browse"
            onFarmPage -> "type=farm_home"
            else -> "type=unknown"
        })
        // 进度状态
        if (hasRedPacketPopup) parts.add("popup=red_packet")
        if (hasFasterRewardEntry) parts.add("popup=faster_reward_entry")
        if (hasRewardUpgradePopup) parts.add("popup=reward_upgrade")
        if (countdownSeconds > 0) parts.add("countdown=yes")
        else parts.add("countdown=no")
        if (hasBrowseProgressHint) parts.add("progress=yes")
        else parts.add("progress=no")
        // 肥料任务描述（优先级最高）：稳定的肥料任务标识，同一任务的不同商品页面自动归为同一类规则
        // 有 fertTask 时不再用 btns=（商品名易变）和 task=（整页文本易变）做匹配键
        if (fertilizerTaskDesc.isNotEmpty()) {
            parts.add("fertTask=$fertilizerTaskDesc")
            // 肥料结构签名：附加在 fertTask 后，作为结构维度的辅助标识
            // 结构签名相同/相似的页面归为同一类（即使 fertTask 文案略有差异）
            if (fertStructSig.isNotEmpty()) {
                parts.add("fertStruct=$fertStructSig")
            }
        } else {
            // 无肥料任务描述时退回原逻辑：用 btns 和 task 做辅助信号
            if (clickableButtons.isNotEmpty()) {
                parts.add("btns=${clickableButtons.toSet().toList().sorted().joinToString(",")}")
            }
            if (taskContentText.isNotEmpty()) {
                parts.add("task=$taskContentText")
            }
        }
        return parts.joinToString("|")
    }

    /**
     * 核心签名：去掉 [clickableButtons] 等易变字段后的签名
     *
     * 用于"明显一样的场景"自动归类：核心签名相同 → 视为同一场景
     * （按钮文案/顺序略变不影响场景判断）
     */
    fun coreSignature(): String {
        val parts = mutableListOf<String>()
        parts.add("p=$platform")
        parts.add("farm=$onFarmPage")
        parts.add(when {
            isTaskComplete -> "type=complete"
            isAbnormalPage -> "type=abnormal"
            isPaidSearchPage -> "type=paid_search"
            isBrowseDurationTask -> "type=browse_duration(${browseDurationSeconds}s)"
            isSearchBrowseTaskPage -> "type=search_browse"
            isBrowseTaskPage -> "type=browse"
            onFarmPage -> "type=farm_home"
            else -> "type=unknown"
        })
        if (hasRedPacketPopup) parts.add("popup=red_packet")
        if (hasFasterRewardEntry) parts.add("popup=faster_reward_entry")
        if (hasRewardUpgradePopup) parts.add("popup=reward_upgrade")
        if (countdownSeconds > 0) parts.add("countdown=yes")
        else parts.add("countdown=no")
        if (hasBrowseProgressHint) parts.add("progress=yes")
        else parts.add("progress=no")
        // 肥料任务描述是规则匹配的核心维度，优先级高于 task=
        // 有 fertTask 时只用 fertTask（稳定），不用 task=（易变）
        if (fertilizerTaskDesc.isNotEmpty()) {
            parts.add("fertTask=$fertilizerTaskDesc")
        } else if (taskContentText.isNotEmpty()) {
            parts.add("task=$taskContentText")
        }
        return parts.joinToString("|")
    }

    /**
     * 简短摘要：用于日志 / 浮窗展示
     */
    fun summary(): String {
        val sb = StringBuilder()
        sb.append("sig=").append(signature()).append(" ")
        sb.append("btns=").append(clickableButtons.take(5))
        if (pageTexts.isNotEmpty()) {
            sb.append(" texts=").append(pageTexts.take(5))
        }
        return sb.toString()
    }
}
