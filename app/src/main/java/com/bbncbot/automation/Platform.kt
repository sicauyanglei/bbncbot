package com.bbncbot.automation

/**
 * 农场平台
 *
 * - 通过无障碍服务自动检测当前前台 App，匹配对应平台
 * - 每个平台对应一份 [PlatformConfig]，包含包名、文案、坐标、广告策略等
 *
 * 平台广告设计特点（决定智能肥料获取策略）：
 * - **UC 极速版**：自有广告系统 + 穿山甲 SDK，广告 15-30s，特有"我要更快拿奖"跳转流程，
 *   倒计时结束后右上角"×"可关闭，H5 内嵌广告为主
 * - **支付宝**：H5 容器（xriveractivity）内嵌广告，Activity 可能不变需靠 adModeFlag + 内容检测，
 *   广告 15-30s，关闭按钮为 H5 内"×"图标，无"更快拿奖"流程，可更激进地短超时退出
 * - **淘宝**：小程序容器（TMSActivity）内嵌广告 + 信息流广告，广告 15-30s，
 *   关闭按钮右上角"×"或"跳过"，无"更快拿奖"流程
 *
 * 注：坐标比例基于 v11 PC 端 ADB 方案经验（屏幕 896x1980），其他屏幕由调用方按 metrics 缩放。
 * 支付宝/淘宝的坐标比例为合理默认值，需在实际设备上调试微调。
 */
enum class Platform {
    UC,       // UC 极速版（com.ucmobile.lite）的 UC 芭芭农场
    ALIPAY,   // 支付宝（com.eg.android.AlipayGphone）的芭芭农场
    TAOBAO,   // 淘宝（com.taobao.taobao）的芭芭农场
    UNKNOWN;

    val config: PlatformConfig
        get() = when (this) {
            UC -> UcPlatformConfig
            ALIPAY -> AlipayPlatformConfig
            TAOBAO -> TaobaoPlatformConfig
            UNKNOWN -> UcPlatformConfig // 默认沿用 UC 配置（兜底）
        }

    /** 获取该平台的肥料收集器 */
    val collector: FertilizerCollector
        get() = when (this) {
            UC -> com.bbncbot.automation.uc.UcFertilizerCollector
            ALIPAY -> com.bbncbot.automation.alipay.AlipayFertilizerCollector
            TAOBAO -> com.bbncbot.automation.taobao.TaobaoFertilizerCollector
            UNKNOWN -> com.bbncbot.automation.uc.UcFertilizerCollector // 兜底
        }

    companion object {
        /**
         * 根据前台包名匹配平台
         * - 优先匹配主包名；其次匹配平台内部包名前缀（如 UC 的 com.UCMobile）
         */
        fun fromPackage(pkg: String?): Platform {
            if (pkg.isNullOrEmpty()) return UNKNOWN
            // 优先精确匹配主包名
            for (p in listOf(UC, ALIPAY, TAOBAO)) {
                if (pkg in p.config.packageNames) return p
            }
            // 其次匹配内部包名前缀（严格 startsWith，避免 contains 误匹配无关包）
            for (p in listOf(UC, ALIPAY, TAOBAO)) {
                if (p.config.internalPackagePrefixes.any { pkg.startsWith(it) }) return p
            }
            return UNKNOWN
        }
    }
}

/**
 * 平台配置接口 - 每个平台的 UI 元素和包名识别
 */
interface PlatformConfig {
    /** 平台枚举 */
    val platform: Platform

    /** 平台 App 主包名列表 */
    val packageNames: List<String>

    /** 平台 App 内部包名前缀（如 UC 的 com.UCMobile） */
    val internalPackagePrefixes: List<String>

    /**
     * 农场页主 Activity 类名（小写关键字匹配，任一命中即视为在农场页）
     * - 空列表表示不通过 Activity 类名判断，仅通过窗口包名判断
     */
    val farmPageActivityKeywords: List<String>

    /** 集肥料按钮文本候选 */
    val collectFertilizerTexts: List<String>

    /** 去完成按钮文本候选 */
    val goCompleteTexts: List<String>

    /** 农场主页上直接可领取肥料的按钮文本候选（如"兔兔挖肥料，50肥料，可领取"） */
    val directCollectTexts: List<String>

    /** 集肥料按钮坐标比例候选 (xRatio, yRatio) */
    val collectFertilizerCoords: List<Pair<Float, Float>>

    /** 广告关闭按钮坐标比例候选 */
    val adCloseCoords: List<Pair<Float, Float>>

    /** 退回按钮坐标比例候选 */
    val backButtonCoords: List<Pair<Float, Float>>

    /**
     * 芭芭农场直达 deep link / URL（桌面快捷方式背后的链接）
     * - 非 null 时：用 [Intent.ACTION_VIEW] 直接打开，等同从桌面快捷方式进入
     * - null 时：走 App 内无障碍导航（启动 App → 找"芭芭农场"入口）
     * - 获取方法：adb logcat 抓取点击桌面快捷方式时的 startActivity 日志
     */
    val farmDeepLink: String?

    // ---------- 智能广告策略（平台差异化） ----------

    /** 广告默认最短等待时间（毫秒），页面无时长提示时使用 */
    val adDefaultMinDurationMs: Long

    /** 广告默认最长等待时间（毫秒），超时强制关闭 */
    val adDefaultMaxDurationMs: Long

    /** 广告结束检测间隔（毫秒），值越小检测越频繁、退出越快，但 CPU 消耗略增 */
    val adEndCheckIntervalMs: Long

    /** 是否处理"我要更快拿奖"跳转流程（UC 特有，其他平台为 false） */
    val supportsFasterReward: Boolean

    /** 平台特有的广告关闭按钮文本/描述提示（除通用的"×"/"关闭"外） */
    val adCloseButtonTexts: List<String>

    /**
     * 广告主转化按钮黑名单（陷阱按钮关键词）
     *
     * 广告设计者意图：诱导用户点击进入广告主落地页（下载/购买/注册），
     * 获取广告转化收益。这些按钮绝不能点击：
     * - "立即下载"/"立即安装" — 跳应用商店下载广告主 App
     * - "立即体验"/"立即试玩" — 跳广告主应用内试玩页
     * - "前往应用"/"打开应用" — 拉起广告主 App
     * - "查看详情"/"了解更多" — 跳广告主落地页
     * - "立即抢购"/"立即购买"/"去购买" — 跳商品交易页（违反禁止交易原则）
     * - "立即注册"/"立即开通" — 跳注册/开通会员页
     *
     * 用于：
     * - [FarmAccessibilityService.findClaimRewardButton] 排除诱导按钮（避免误识别为肥料领取按钮）
     * - [FarmAccessibilityService.findAdCloseButton] 排除诱导按钮（避免误识别为关闭按钮）
     * - [FarmAccessibilityService.isAdLandingPage] 检测误入广告主落地页
     * - [FarmAccessibilityService.findAdInstallButton] 检测诱导弹窗
     */
    val adInstallButtonTexts: List<String>
}

/** UC 极速版（com.ucmobile.lite）配置 - 沿用现有 v2 实现 */
object UcPlatformConfig : PlatformConfig {
    override val platform = Platform.UC
    override val packageNames = listOf("com.ucmobile.lite")
    override val internalPackagePrefixes = listOf("com.UCMobile")
    override val farmPageActivityKeywords = listOf("innerucmobile", "mainactivity")
    override val collectFertilizerTexts = listOf(
        "集肥料", "领取肥料", "获取肥料", "收集肥料", "开始施肥", "看视频", "看广告领奖"
    )
    override val goCompleteTexts = listOf("去完成", "立即完成", "去观看", "去领取", "立即观看", "去赚钱", "去签到", "去答题", "去逛逛")
    override val directCollectTexts = listOf("可领取", "挖肥料")
    override val collectFertilizerCoords = listOf(
        Pair(0.867f, 0.815f),  // 集肥料按钮（OCR 确认，右下角，1200x2664 屏幕）
        Pair(0.867f, 0.838f),  // 集肥料文字位置备用
        Pair(0.185f, 0.104f),  // 开始施肥/停止施肥按钮（顶部）
        Pair(0.883f, 0.483f),  // 看视频任务"去完成"按钮（OCR 确认，1200x2664 屏幕）
        Pair(0.871f, 0.561f),  // 浏览广告任务"去完成"按钮（OCR 确认，1200x2664 屏幕）
        Pair(0.855f, 0.515f),  // 旧看视频按钮坐标兜底
        Pair(0.5f, 0.184f),    // 旧配置兜底
        Pair(0.5f, 0.900f)     // 旧配置兜底
    )
    override val adCloseCoords = listOf(
        Pair(0.95f, 0.025f),
        Pair(0.96f, 0.025f),
        Pair(0.94f, 0.015f),
        Pair(0.97f, 0.015f),
        Pair(0.96f, 0.035f),
        Pair(0.93f, 0.010f),
        Pair(0.50f, 0.050f),
        Pair(0.95f, 0.960f)
    )
    override val backButtonCoords = listOf(
        Pair(0.112f, 0.874f),  // 左下角退回按钮
        Pair(0.050f, 0.050f),  // 左上角
        Pair(0.950f, 0.050f),  // 右上角关闭
        Pair(0.500f, 0.950f)   // 底部中央
    )
    override val farmDeepLink = "https://broccoli.uc.cn/apps/ucfarm/routes/farm"

    // ---------- 智能广告策略（UC 特有） ----------
    // UC 广告设计特点：自有广告系统 + 穿山甲 SDK，广告 15-30s，特有"我要更快拿奖"跳转流程
    override val adDefaultMinDurationMs = 30000L      // UC 广告通常需完整观看，默认 30s
    override val adDefaultMaxDurationMs = 90000L      // 含"更快拿奖"跳转流程，上限放宽到 90s
    override val adEndCheckIntervalMs = 5000L         // 5s 检测间隔（"更快拿奖"流程需较稳定轮询）
    override val supportsFasterReward = true          // UC 特有："我要更快拿奖"跳转流程
    override val adCloseButtonTexts = listOf("跳过广告", "跳过视频", "关闭广告")
    override val adInstallButtonTexts = listOf(
        "立即下载", "立即安装", "点击下载", "免费下载",
        "立即体验", "立即试玩", "开始试玩",
        "前往应用", "打开应用", "前往查看",
        "查看详情", "了解更多", "查看更多",
        "立即抢购", "立即购买", "去购买", "立即下单",
        "立即注册", "立即开通", "立即续费", "开通会员",
        "领取优惠", "领取福利", "立即领取福利"
    )
}

/**
 * 支付宝（com.eg.android.AlipayGphone）配置
 *
 * - 芭芭农场入口：支付宝首页搜索"芭芭农场"，或首页应用中心入口
 * - 农场页通常内嵌 H5（Activity 可能为 H5AppActivity 或 AlipayLogin 主容器）
 * - 坐标比例为合理默认值，需在实际设备上调试微调
 */
object AlipayPlatformConfig : PlatformConfig {
    override val platform = Platform.ALIPAY
    override val packageNames = listOf("com.eg.android.AlipayGphone")
    override val internalPackagePrefixes = listOf("com.eg.android.AlipayGphone", "com.alipay.mobile")
    override val farmPageActivityKeywords = listOf(
        "alipaylogin", "fragmenttabactivity", "mainactivity", "h5appactivity", "h5webviewactivity",
        // 支付宝新版 H5 容器（nebulax xriver），用于芭芭农场等小程序页面
        "xriveractivity", "xrivertransactivity"
    )
    override val collectFertilizerTexts = listOf(
        "集肥料", "获取肥料", "收集肥料",
        "打开任务列表", "任务列表",
        "今日可领", "去领取",
        "限时挑战", "挑战", "赚肥料"
        // 注意：不要加单独的"肥料"、"领肥料"、"领肥"、"得肥料"、"施肥"
        // 这些词太宽泛，会误匹配到"还差3次领肥料"、"再施肥99.76%就快拿到啦"等进度提示文字
        // "领取肥料"也移除：它会误匹配"2027.12.31前可领取肥料礼包"等直接领取入口，
        // 这类入口点击后弹窗，需在弹窗里点"立即领取"才能领到肥料，应走 directCollect 流程
    )
    override val goCompleteTexts = listOf("去完成", "去逛逛", "去观看", "立即完成", "去领取", "去赚", "去签到", "去答题", "去挑战", "开始挑战")
    override val directCollectTexts = listOf(
        "可领取", "挖肥料",
        // 支付宝芭芭农场：按钮锁定时显示"还差3次领肥[料]"，达到阈值后变成"立即领肥[料]"可点击领取
        // 用"立即领肥"精确匹配可领取状态，不会误匹配"还差3次领肥"
        "立即领肥",
        // "领取肥料"匹配"领取肥料礼包"、"立即领取肥料"等弹窗领取入口（点击后弹窗，需再点"立即领取"）
        "领取肥料"
    )
    override val collectFertilizerCoords = listOf(
        Pair(0.888f, 0.771f),  // 右侧浮动"肥料"按钮（OCR+颜色分析确认，1200x2664 屏幕）
        Pair(0.908f, 0.787f),  // "肥料"文字位置备用（OCR 确认）
        Pair(0.667f, 0.375f),  // 上中橙色按钮（颜色分析，可能是限时挑战入口）
        Pair(0.5f, 0.556f),    // 中部橙色按钮（颜色分析 ratio74%，可能是任务入口）
        Pair(0.3f, 0.616f),    // 中下橙色按钮（颜色分析）
        Pair(0.1f, 0.526f),    // 左中橙色按钮（颜色分析）
        Pair(0.417f, 0.974f),  // 底部橙色按钮
        Pair(0.501f, 0.761f),  // 施肥按钮（OCR 确认，1200x2664 屏幕）
        Pair(0.917f, 0.657f),  // 今日可领（右侧领取入口）
        Pair(0.5f, 0.184f),
        Pair(0.5f, 0.900f)
    )
    override val adCloseCoords = listOf(
        Pair(0.95f, 0.025f),
        Pair(0.96f, 0.035f),
        Pair(0.93f, 0.010f),
        Pair(0.50f, 0.050f),
        Pair(0.95f, 0.960f)
    )
    override val backButtonCoords = listOf(
        Pair(0.050f, 0.050f),
        Pair(0.950f, 0.050f),
        Pair(0.500f, 0.950f),
        Pair(0.112f, 0.874f)
    )
    // TODO: 用户提供支付宝芭芭农场桌面快捷方式的 deep link 后填入（如 alipays://platformapi/startapp?appId=xxx）
    override val farmDeepLink: String? = null

    // ---------- 智能广告策略（支付宝） ----------
    // 支付宝广告设计特点：H5 容器（xriveractivity）内嵌广告，Activity 可能不变需靠内容检测，
    // 广告 15-30s，无"更快拿奖"流程，可更激进地短超时快速退出
    override val adDefaultMinDurationMs = 15000L      // 支付宝广告普遍较短，默认 15s 即可检测退出
    override val adDefaultMaxDurationMs = 60000L      // 无跳转流程，上限收紧到 60s
    override val adEndCheckIntervalMs = 3000L         // 3s 检测间隔（更激进，快速发现广告结束）
    override val supportsFasterReward = false         // 支付宝无"更快拿奖"流程
    override val adCloseButtonTexts = listOf("关闭广告", "跳过", "关闭")
    // 陷阱按钮黑名单（支付宝 H5 广告与 UC 模式一致，沿用通用诱导文案）
    override val adInstallButtonTexts = listOf(
        "立即下载", "立即安装", "点击下载", "免费下载",
        "立即体验", "立即试玩", "开始试玩",
        "前往应用", "打开应用", "前往查看",
        "查看详情", "了解更多", "查看更多",
        "立即抢购", "立即购买", "去购买", "立即下单",
        "立即注册", "立即开通", "立即续费", "开通会员",
        "领取优惠", "领取福利", "立即领取福利"
    )
}

/**
 * 淘宝（com.taobao.taobao）配置
 *
 * - 芭芭农场入口：淘宝首页搜索"芭芭农场"，点击搜索结果中的小程序入口
 * - 农场页 Activity：com.taobao.themis.container.app.TMSActivity（淘宝小程序容器）
 * - 坐标比例为合理默认值，需在实际设备上调试微调
 */
object TaobaoPlatformConfig : PlatformConfig {
    override val platform = Platform.TAOBAO
    override val packageNames = listOf("com.taobao.taobao")
    override val internalPackagePrefixes = listOf("com.taobao.taobao", "com.taobao")
    override val farmPageActivityKeywords = listOf(
        "tmsactivity", "mainactivity", "taobaomain", "fragmenttabactivity",
        "h5webviewactivity", "webviewactivity", "multipagecontaineractivity"
    )
    override val collectFertilizerTexts = listOf(
        "集肥料", "领取肥料", "获取肥料", "收集肥料", "打开任务列表", "任务列表"
    )
    override val goCompleteTexts = listOf("去完成", "去逛逛", "去观看", "立即完成", "去领取", "去赚", "去签到", "去答题")
    override val directCollectTexts = listOf("可领取", "挖肥料")
    override val collectFertilizerCoords = listOf(
        Pair(0.227f, 0.107f),  // 打开任务列表按钮（OCR 确认，1200x2664 屏幕）
        Pair(0.25f, 0.11f),    // 打开任务列表备用
        Pair(0.5f, 0.184f),
        Pair(0.5f, 0.900f)
    )
    override val adCloseCoords = listOf(
        Pair(0.95f, 0.025f),
        Pair(0.96f, 0.035f),
        Pair(0.93f, 0.010f),
        Pair(0.50f, 0.050f),
        Pair(0.95f, 0.960f)
    )
    override val backButtonCoords = listOf(
        Pair(0.050f, 0.050f),
        Pair(0.950f, 0.050f),
        Pair(0.500f, 0.950f),
        Pair(0.112f, 0.874f)
    )
    // TODO: 用户提供淘宝芭芭农场桌面快捷方式的 deep link 后填入
    override val farmDeepLink: String? = null

    // ---------- 智能广告策略（淘宝） ----------
    // 淘宝广告设计特点：小程序容器（TMSActivity）内嵌广告 + 信息流广告，广告 15-30s，
    // 无"更快拿奖"流程，关闭按钮右上角"×"或"跳过"
    override val adDefaultMinDurationMs = 20000L      // 淘宝广告略长于支付宝，默认 20s
    override val adDefaultMaxDurationMs = 75000L      // 无跳转流程，上限 75s
    override val adEndCheckIntervalMs = 3000L         // 3s 检测间隔（更激进，快速发现广告结束）
    override val supportsFasterReward = false         // 淘宝无"更快拿奖"流程
    override val adCloseButtonTexts = listOf("跳过广告", "跳过", "关闭")
    // 陷阱按钮黑名单（淘宝小程序/信息流广告与 UC 模式一致，沿用通用诱导文案）
    override val adInstallButtonTexts = listOf(
        "立即下载", "立即安装", "点击下载", "免费下载",
        "立即体验", "立即试玩", "开始试玩",
        "前往应用", "打开应用", "前往查看",
        "查看详情", "了解更多", "查看更多",
        "立即抢购", "立即购买", "去购买", "立即下单",
        "立即注册", "立即开通", "立即续费", "开通会员",
        "领取优惠", "领取福利", "立即领取福利"
    )
}
