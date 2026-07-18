package com.bbncbot.service

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.bbncbot.automation.AutomationController
import com.bbncbot.automation.Platform
import com.bbncbot.automation.PlatformConfig

/**
 * 芭芭农场无障碍服务（多平台版）
 *
 * - 自动检测前台 App 平台（UC / 支付宝 / 淘宝），逻辑委托给对应 [PlatformConfig]
 * - 监听窗口变化事件（仅记录日志，不直接驱动逻辑）
 * - 暴露 [rootInActiveWindowSafe] 与 [performClickSafe] 供 Controller 调用
 * - 在 [onServiceConnected] 中绑定到 [AutomationController]
 *
 * 兼容性：保留 [getRootInUcBrowser] / [isUcBrowserInForeground] 旧方法，
 * 内部委托到平台通用方法 [getRootInFarmApp] / [isFarmAppInForeground]。
 */
class FarmAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "FarmAccessibility"

        @Volatile
        private var instance: FarmAccessibilityService? = null

        /** 当前是否已连接 */
        fun isConnected(): Boolean = instance != null

        /** 获取服务实例（用于调用 navigateToFarm 等） */
        fun getInstance(): FarmAccessibilityService? = instance

        // ---------- 广告活动识别关键词 ----------
        // 通用广告活动识别（适用于 UC / 支付宝 / 淘宝内嵌的第三方广告 SDK）
        // 字节跳动穿山甲 SDK（TTRewardVideoActivity + 混淆类名 com.byazt.od.*）
        // 汇川、Noah、趣盟等广告 SDK
        private val AD_ACTIVITY_KEYWORDS = listOf(
            "rewardvideo",       // HCRewardVideoActivity / TTRewardVideoActivity 的类名小写
            "hcvideo",
            "huichuan",          // 汇川
            "noah.adn",          // Noah ADN
            "qumeng",            // 趣盟
            "rewardvideoactivity",
            "byazt",             // 字节跳动广告 SDK 混淆类名前缀（com.byazt.od.*）
            "bytedance.sdk",     // 字节跳动 SDK
            "openadsdk",         // 穿山甲 openadsdk
            "csj",               // 穿山甲 CSJ
            "pangolin"           // 穿山甲 Pangolin
        )

        // 第三方广告 SDK 包名关键词（适用于所有平台）
        private val AD_PKG_KEYWORDS = listOf(
            "bytedance", "adsdk", "pangolin", "csj",
            "tencentad", "gdt", "qq.e",
            "baidu.mobads", "mintegral", "unity3d.ads",
            "adcolony", "vungle", "applovin", "ironsource",
            "noah.adn", "qumeng"
        )
    }

    /**
     * 当前前台 Activity 类名（通过事件跟踪）
     */
    @Volatile
    private var currentActivityName: String? = null

    /** 当前前台包名（通过事件跟踪） */
    @Volatile
    private var currentEventPkg: String? = null

    /**
     * 当前检测到的平台（自动更新）
     * - 通过 [getCurrentWindowPackage] 在需要时刷新
     * - UNKNOWN 表示未识别到任何支持的平台
     */
    @Volatile
    var currentPlatform: Platform = Platform.UNKNOWN
        private set

    /**
     * 广告模式标志（App 内部广告检测）
     * - App（UC/支付宝/淘宝）WebView 内部播放广告时，Activity 可能不变
     * - 由 AutomationController 在点击施肥按钮后设置为 true
     * - 在领取奖励或返回农场页后清除为 false
     */
    @Volatile
    private var adModeFlag: Boolean = false

    /** 设置广告模式标志（由 AutomationController 调用） */
    fun setAdMode(enabled: Boolean) {
        if (adModeFlag != enabled) {
            Log.i(TAG, "setAdMode: $enabled")
        }
        adModeFlag = enabled
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AutomationController.bindService(this)
        // 服务重新连接时重置截图能力标志（用户可能在系统设置中重新开启了服务）
        screenshotCapabilityDisabled = false
        Log.i(TAG, "FarmAccessibilityService connected, bound to AutomationController")
        debugLog("FarmAccessibilityService connected, API=${android.os.Build.VERSION.SDK_INT}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString().orEmpty()
        val className = event.className?.toString().orEmpty()

        // 跟踪窗口状态变化，记录当前前台 Activity 和包名
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            pkg.isNotEmpty() && className.isNotEmpty()) {
            currentActivityName = className
            currentEventPkg = pkg
            // 自动检测平台
            val detected = Platform.fromPackage(pkg)
            if (detected != Platform.UNKNOWN) {
                currentPlatform = detected
            }
            Log.d(TAG, "window-state-changed pkg=$pkg class=$className platform=$currentPlatform")
        }

        // 仅记录已识别平台的事件，过滤无关包
        if (pkg.isNotEmpty() && !isFarmRelatedPackage(pkg) && !isAdPkg(pkg)) {
            return
        }
        // 仅记录事件类型，逻辑由 AutomationController 主动驱动
        Log.v(TAG, "event type=0x${event.eventType.toString(16)} pkg=$pkg class=$className")
    }

    /** 调试日志写到外部存储文件（华为 logcat 加密，用文件替代） */
    private fun debugLog(msg: String) {
        Log.i(TAG, msg)
        try {
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date())
            val line = "$timestamp $msg\n"
            val file = java.io.File(
                android.os.Environment.getExternalStorageDirectory(),
                "Android/data/com.bbncbot/files/debug.log"
            )
            file.parentFile?.mkdirs()
            file.appendText(line)
        } catch (_: Exception) { /* ignore */ }
    }

    /** 节流日志：同一 key 在 throttleMs 内只输出一次，避免高频轮询导致日志爆炸 */
    private val throttleLogTimestamps = HashMap<String, Long>()
    private fun throttledLog(key: String, msg: String, throttleMs: Long = 3000L) {
        val now = System.currentTimeMillis()
        val last = throttleLogTimestamps[key] ?: 0L
        if (now - last >= throttleMs) {
            throttleLogTimestamps[key] = now
            debugLog(msg)
        }
    }

    /** 判断包名是否与本应用支持的农场 App 相关（含本应用自身） */
    private fun isFarmRelatedPackage(pkg: String): Boolean {
        if (pkg == "com.bbncbot") return true
        return Platform.fromPackage(pkg) != Platform.UNKNOWN
    }

    /** 判断包名是否为广告 SDK */
    private fun isAdPkg(pkg: String): Boolean {
        val lower = pkg.lowercase()
        return AD_PKG_KEYWORDS.any { lower.contains(it) }
    }

    override fun onInterrupt() {
        Log.w(TAG, "onInterrupt")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Log.i(TAG, "FarmAccessibilityService unbind")
        instance = null
        AutomationController.unbindService()
        return super.onUnbind(intent)
    }

    /**
     * 安全获取当前活动窗口根节点
     * - 自动处理 null 与异常
     */
    fun rootInActiveWindowSafe(): AccessibilityNodeInfo? {
        return try {
            rootInActiveWindow
        } catch (e: Exception) {
            Log.w(TAG, "rootInActiveWindow failed: ${e.message}")
            null
        }
    }

    /**
     * 当前平台配置（兜底返回 UC 配置）
     */
    fun currentPlatformConfig(): PlatformConfig = currentPlatform.config

    /**
     * 主动检测当前前台 App 平台（通过 windows 遍历）
     * - 无障碍服务刚连接时可能还没收到 WINDOW_STATE_CHANGED 事件，currentPlatform 为 UNKNOWN
     * - 此方法遍历所有窗口，找到已知的平台 App 包名并更新 currentPlatform
     * @return 检测到的平台，UNKNOWN 表示未识别
     */
    fun refreshPlatform(): Platform {
        if (currentPlatform != Platform.UNKNOWN) return currentPlatform
        try {
            val windows = windows
            for (w in windows) {
                val pkg = w.root?.packageName?.toString().orEmpty()
                if (pkg.isNotEmpty() && pkg != "com.bbncbot" && pkg != "android") {
                    val detected = Platform.fromPackage(pkg)
                    if (detected != Platform.UNKNOWN) {
                        currentPlatform = detected
                        debugLog("refreshPlatform: detected platform=$detected from pkg=$pkg")
                        return detected
                    }
                }
            }
        } catch (e: Exception) {
            debugLog("refreshPlatform: exception: ${e.message}")
        }
        return currentPlatform
    }

    /**
     * 查找当前农场 App 窗口的根节点
     * - 遍历所有窗口找到当前平台 App 的窗口
     * - 多个窗口匹配时优先选内容最丰富的（避免拿到仅有 1 个子节点的 splash/popup 窗口）
     * @return 当前平台 App 窗口的根节点，找不到时返回 null
     */
    fun getRootInFarmApp(): AccessibilityNodeInfo? {
        return try {
            val windows = windows
            val cfg = currentPlatformConfig()
            var bestRoot: AccessibilityNodeInfo? = null
            var bestPkg = ""
            var bestDescendants = -1
            for (w in windows) {
                val root = w.root ?: continue
                val pkg = root.packageName?.toString().orEmpty()
                if (pkg.isEmpty()) continue
                // 必须是当前平台主包名或内部包前缀（严格匹配，避免 UC 平台拿到 com.oray.sunlogin 等无关包）
                val isMainPkg = pkg in cfg.packageNames
                val isInternalPkg = cfg.internalPackagePrefixes.any { pkg.startsWith(it) }
                if (!isMainPkg && !isInternalPkg) continue
                // 估算窗口内容量（递归节点数有性能开销，用 childCount + 二级 childCount 近似）
                val descendantEstimate = root.childCount + (0 until root.childCount).sumOf {
                    (root.getChild(it)?.childCount ?: 0)
                }
                if (descendantEstimate > bestDescendants) {
                    bestDescendants = descendantEstimate
                    bestRoot = root
                    bestPkg = pkg
                }
            }
            if (bestRoot != null) {
                Log.d(TAG, "getRootInFarmApp: found ${currentPlatform} window, pkg=$bestPkg childCount=${bestRoot.childCount} descendants~$bestDescendants")
            } else {
                Log.w(TAG, "getRootInFarmApp: ${currentPlatform} window not found")
            }
            bestRoot
        } catch (e: Exception) {
            Log.w(TAG, "getRootInFarmApp failed: ${e.message}")
            null
        }
    }

    /** 当前农场 App 是否在前台（是否有当前平台窗口） */
    fun isFarmAppInForeground(): Boolean {
        return try {
            val windows = windows
            val cfg = currentPlatformConfig()
            for (w in windows) {
                val pkg = w.root?.packageName?.toString().orEmpty()
                if (pkg in cfg.packageNames || cfg.internalPackagePrefixes.any { pkg.startsWith(it) }) {
                    return true
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检查农场 H5 页面是否已加载完成（有可交互内容）
     * - H5 页面在 WebView 中渲染，刚进入时所有节点 bounds 为 [0,0][0,0] 或只有"加载中..."
     * - 页面加载完成后会有实际的文本内容和正常 bounds
     * @return true 表示页面已加载完成
     */
    fun hasFarmContentLoaded(root: AccessibilityNodeInfo): Boolean {
        // 检查是否有有效的文本内容（非"加载中..."）
        val textContent = collectAllText(root)
        debugLog("hasFarmContentLoaded: text count=${textContent.size}, sample=${textContent.take(10)}")
        if (textContent.isEmpty()) return false
        // 排除占位文本
        val placeholderTexts = listOf("加载中...", "请稍候", "正在加载", "loading", "Loading", "网络异常", "点击重试")
        val realContent = textContent.filter { text ->
            text.isNotEmpty() && text.length > 1 && placeholderTexts.none { text.equals(it, ignoreCase = true) }
        }
        debugLog("hasFarmContentLoaded: realContent count=${realContent.size}")
        return realContent.isNotEmpty()
    }

    /** 收集节点树中所有文本 */
    fun collectAllText(node: AccessibilityNodeInfo, depth: Int = 0): List<String> {
        if (depth > 30) return emptyList()
        val result = mutableListOf<String>()
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) result.add(text)
        if (desc.isNotEmpty()) result.add(desc)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            result.addAll(collectAllText(child, depth + 1))
        }
        return result
    }

    /**
     * 获取页面类型（用于诊断日志）
     * 返回简短的页面类型字符串：farm_home / browse / browse_duration / search_browse /
     * complete / ad / abnormal / unknown
     */
    fun getPageType(): String {
        val root = rootInActiveWindowSafe() ?: return "unknown(no_root)"
        if (isAdActivity() || isAdContentShown()) return "ad"
        if (!isFarmAppInForeground()) return "non_farm"
        val allText = collectAllText(root)
        val hasComplete = allText.any { it.contains("全部完成") || it.contains("已完成") }
        val hasAbnormal = allText.any { it.contains("异常") || it.contains("网络错误") || it.contains("加载失败") }
        val hasBrowseDurationHint = allText.any { it.contains("滑动浏览") && it.contains("得肥料") }
        val hasSearchBrowse = allText.any { it.contains("搜索") && it.contains("浏览") }
        val hasBrowseTask = allText.any { it.contains("浏览") && it.contains("奖励") }
        val onFarm = isOnFarmPage()
        return when {
            hasComplete -> "complete"
            hasAbnormal -> "abnormal"
            hasBrowseDurationHint -> "browse_duration"
            hasSearchBrowse -> "search_browse"
            hasBrowseTask -> "browse"
            onFarm -> "farm_home"
            else -> "unknown"
        }
    }

    /** 收集页面所有文本的快照（用于诊断日志），最多 maxCount 条 */
    fun collectAllTextSnapshot(maxCount: Int = 15): List<String> {
        val root = rootInActiveWindowSafe() ?: return emptyList()
        return collectAllText(root).take(maxCount)
    }

    // ---------- 旧 API 兼容（委托到平台通用实现） ----------

    /** @deprecated 使用 [getRootInFarmApp] */
    fun getRootInUcBrowser(): AccessibilityNodeInfo? = getRootInFarmApp()

    /** @deprecated 使用 [isFarmAppInForeground] */
    fun isUcBrowserInForeground(): Boolean = isFarmAppInForeground()

    /**
     * 获取当前前台窗口的包名
     * - 用于判断是否进入了广告页面
     * - 优先返回非当前农场 App 的活动窗口（可能是广告）
     */
    fun getCurrentWindowPackage(): String? {
        return try {
            val windows = windows
            val cfg = currentPlatformConfig()
            // 优先返回非当前平台的活动窗口（可能是广告）
            for (w in windows) {
                val pkg = w.root?.packageName?.toString().orEmpty()
                if (pkg.isNotEmpty() &&
                    pkg !in cfg.packageNames &&
                    cfg.internalPackagePrefixes.none { pkg.startsWith(it) } &&
                    pkg != "com.bbncbot" &&
                    pkg != "android") {
                    return pkg
                }
            }
            // 如果只有当前平台 App，返回其包名
            for (w in windows) {
                val pkg = w.root?.packageName?.toString().orEmpty()
                if (pkg in cfg.packageNames || cfg.internalPackagePrefixes.any { pkg.startsWith(it) }) {
                    return pkg
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 是否正在播放广告
     * - 优先检测广告模式标志（App 内部广告，Activity 不变）
     * - 其次检测当前 Activity 类名（广告活动）
     * - 最后检测第三方广告 SDK 包名
     */
    fun isAdPlaying(): Boolean {
        // 0. 广告模式标志（App 内部广告，由 AutomationController 设置）
        if (adModeFlag) {
            return true
        }

        // 1. 优先检测当前 Activity 类名（广告活动）
        val activity = currentActivityName?.lowercase().orEmpty()
        if (activity.isNotEmpty() && AD_ACTIVITY_KEYWORDS.any { activity.contains(it) }) {
            return true
        }

        // 2. 检测第三方广告 SDK 包名
        val pkg = getCurrentWindowPackage() ?: return false
        val pkgLower = pkg.lowercase()
        if (AD_PKG_KEYWORDS.any { pkgLower.contains(it) }) {
            return true
        }
        return false
    }

    /**
     * 内容级广告识别：通过页面 UI 元素判断是否在播放广告
     *
     * 充分理解各种广告设计意图：
     * - 视频广告：有"跳过/跳过广告/跳过视频"按钮、倒计时
     * - 全屏/插屏广告：有"关闭/×"按钮 + "查看详情/立即下载"CTA
     * - 完成广告：有"领取奖励/领取肥料/已得"等
     *
     * 当 Activity 类名/包名无法识别广告时（如 App 内 WebView 广告、自定义广告页），用此方法兜底。
     * @return true 表示当前页面正在播放广告或广告已完成可领取
     */
    fun isAdContentShown(): Boolean {
        if (isOnFarmPage()) return false
        val root = rootInActiveWindowSafe() ?: return false
        val allText = collectAllText(root)
        // 广告播放中/结束的明确 UI 信号（避免误判，不用过于宽泛的"跳过"/"广告"）
        val adSignals = listOf(
            "跳过广告", "跳过视频", "跳过奖励",      // 跳过按钮（带"广告/视频/奖励"后缀，更精确）
            "关闭广告", "关闭视频",                   // 关闭广告
            "查看详情", "立即体验",                   // 广告 CTA（落地页引导）
            "领取奖励", "领取肥料", "已领取",         // 广告结束领取
            "reward", "skip ad", "close ad"          // 英文/SDK 标识
        )
        val matched = allText.any { text ->
            adSignals.any { sig -> text.contains(sig, ignoreCase = true) }
        }
        if (matched) {
            debugLog("isAdContentShown: YES, matched ad signal in ${allText.take(8)}")
        }
        return matched
    }

    /**
     * 内容级非广告页面识别：通过页面文本判断是否是非广告任务页
     *
     * 用户要求：只看广告获取肥料。以下任务不是看广告，应跳过：
     * - 邀请好友/助力/帮忙
     * - 关注店铺/关注公众号
     * - 分享得奖励/分享给好友
     * - 下载 App/立即下载（非广告落地页，而是任务要求下载）
     * - 开通会员/连续包月/订阅
     * - 完善资料/填写问卷
     *
     * 注意：仅在非广告、非农场页时调用此方法判断。
     * @return true 表示当前页面是非广告任务页，应跳过该任务
     */
    fun isNonAdTaskPage(): Boolean {
        if (isAdActivity() || isAdPlaying() || isAdContentShown()) return false
        if (isOnFarmPage()) return false
        val root = rootInActiveWindowSafe() ?: return false
        val allText = collectAllText(root)
        val nonAdKeywords = listOf(
            "邀请好友", "邀请", "助力", "帮忙助", "帮帮好友",   // 邀请类
            "关注店铺", "关注公众号", "关注得", "关注领",      // 关注类
            "分享给", "分享得", "分享好友", "分享有奖", "分享",   // 分享类
            "合种", "合种树",                                     // 合种类
            "开通会员", "连续包月", "立即订阅", "立即开通",     // 会员类
            "完善资料", "填写问卷", "完善信息",                 // 资料类
            "去授权", "授权获取",                                // 授权类
            "加入购物车", "立即购买", "去结算",                  // 购物类（非浏览）
            "提交订单", "确认订单", "立即支付", "去支付", "确认支付",  // 交易确认页（不产生交易，跳过）
            "充值", "话费充值", "流量充值", "充值得", "立即充值",   // 充值类任务（不产生交易，跳过）
            "投资", "理财", "保险", "开户",                        // 金融类任务（不产生交易，跳过）
            "到店支付", "线下支付", "扫码支付",                    // 到店支付类任务（不产生交易，跳过）
            // 小程序/游戏页面：自动化无法操作小程序游戏，跳过
            "本小程序服务由", "本小程序由", "小游戏", "开始游戏", "立即开始游戏"
        )
        val matched = allText.any { text ->
            nonAdKeywords.any { kw -> text.contains(kw) }
        }
        if (matched) {
            debugLog("isNonAdTaskPage: YES, matched non-ad task in ${allText.take(8)}")
        }
        return matched
    }

    /**
     * 检测当前页面是否是商品详情页（有"加入购物车"+"立即购买"按钮的页面）
     *
     * 禁止交易获取肥料：商品详情页是交易前置页面，命中后由 [isOnAbnormalPage] 统一处理，
     * 调用方（浏览任务/游戏任务/导航）会立即退出任务，不再浏览或滑动。
     *
     * - 商品详情页典型特征：同时含"加入购物车"+"立即购买"按钮
     * - 只匹配单个关键词会误判（如列表页的"立即购买"标签），要求两者同时出现
     *
     * @return true 表示当前在商品详情页，应退出
     */
    fun isProductDetailPage(): Boolean {
        val root = rootInActiveWindowSafe() ?: return false
        val allText = collectAllText(root)
        // 必须同时出现"加入购物车"和"立即购买"才认定为商品详情页
        // 单独出现"立即购买"可能只是列表页的商品标签，会误判
        val hasAddToCart = allText.any { it.contains("加入购物车") }
        val hasBuyNow = allText.any { it.contains("立即购买") }
        val isDetail = hasAddToCart && hasBuyNow
        if (isDetail) {
            debugLog("isProductDetailPage: YES (hasAddToCart=$hasAddToCart, hasBuyNow=$hasBuyNow)")
        }
        return isDetail
    }

    /**
     * 检测当前页面是否是小程序/游戏页面（不受 adModeFlag 干扰）
     *
     * 用于 WATCHING_AD 状态下的兜底检测：
     * - 任务跳转到小程序/游戏页面被误判为广告深链任务
     * - adModeFlag=true 导致 isAdPlaying()=true，但页面实际没有广告特征
     * - 本方法直接检测页面文本，不受 adModeFlag 影响
     *
     * @return true 表示当前页面是小程序/游戏页面（非广告）
     */
    fun isMiniProgramOrGamePage(): Boolean {
        val root = rootInActiveWindowSafe() ?: return false
        val allText = collectAllText(root)
        val miniProgramKeywords = listOf(
            "本小程序服务由", "本小程序由",   // 支付宝小程序标识
            "小游戏", "开始游戏", "立即开始游戏",  // 游戏入口
            "腾讯小游戏", "微信小游戏"          // 微信小游戏标识
        )
        val matched = allText.any { text ->
            miniProgramKeywords.any { kw -> text.contains(kw) }
        }
        if (matched) {
            debugLog("isMiniProgramOrGamePage: YES, sample=${allText.take(8)}")
        }
        return matched
    }

    /**
     * 是否在芭芭农场页面
     * - 当前农场 App 在前台 且 不在广告活动
     * - 通过平台配置的 farmPageActivityKeywords 判断 Activity
     * - 兜底通过 adModeFlag 和广告包名判断
     */
    /** 缓存 isOnFarmPage 内容检查结果，避免每次调用都遍历节点树 */
    @Volatile
    private var farmPageCache: Boolean? = null

    /** 缓存有效期（毫秒） */
    @Volatile
    private var farmPageCacheTime: Long = 0L

    fun isOnFarmPage(): Boolean {
        // 0. 广告模式标志（App 内部广告，Activity 不变）
        if (adModeFlag) {
            return false
        }
        if (!isFarmAppInForeground()) return false
        val activity = currentActivityName?.lowercase().orEmpty()
        // 1. 如果当前活动包含广告关键词，则不在农场页
        if (activity.isNotEmpty() && AD_ACTIVITY_KEYWORDS.any { activity.contains(it) }) {
            return false
        }
        // 2. 如果当前活动已知且不在农场页 Activity 关键词列表中，则不在农场页
        val farmKeywords = currentPlatformConfig().farmPageActivityKeywords
        if (activity.isNotEmpty() && farmKeywords.isNotEmpty() &&
            farmKeywords.none { activity.contains(it) } &&
            activity != "android.widget.framelayout") {
            debugLog("isOnFarmPage: activity=$activity not in farm keywords, not on farm page")
            farmPageCache = false
            return false
        }
        // 3. 检查是否有第三方广告 SDK 窗口
        val pkg = getCurrentWindowPackage() ?: return true
        val pkgLower = pkg.lowercase()
        if (AD_PKG_KEYWORDS.any { pkgLower.contains(it) }) return false

        // 4. 内容验证（使用缓存，3秒有效期）
        val now = System.currentTimeMillis()
        if (farmPageCache != null && (now - farmPageCacheTime) < 3000L) {
            return farmPageCache!!
        }
        val root = getRootInFarmApp()
        if (root != null) {
            val allText = collectAllText(root)

            // 排除搜索推荐页（"下单得肥料"等搜索推荐页面不是种植页）
            // 注意：任务列表弹窗中也包含"下单得肥料"等任务描述文字，
            // 所以只有在没有种植页核心元素（"集肥料"、"施肥"等）时才判断为搜索推荐页
            val hasFarmCore = allText.any { text ->
                text.contains("集肥料") || text.contains("施肥") ||
                    text.contains("换种") || text.contains("好友林") ||
                    text.contains("一起种") ||
                    // 支付宝农场页特有关键词（H5 页面，文本可能不暴露，需多重兜底）
                    text.contains("领肥料") || text.contains("限时挑战") ||
                    text.contains("得1000肥") || text.contains("赚肥料") ||
                    text.contains("得肥料")
            }
            val searchPageKeywords = listOf(
                "下单得肥料", "当前页下单", "搜索有惊喜", "搜一搜浏览",
                "搜索有福利"
            )
            val matchCount = searchPageKeywords.count { text -> allText.any { it.contains(text) } }
            val hasSearchOnlyKeyword = allText.any { it.contains("搜一搜") || it.contains("搜索有惊喜") }
            // 只有在没有种植页核心元素，且匹配到搜索推荐页特征时才判断为搜索推荐页
            // 注意："搜索后浏览立得奖励"、"浏览宝贝得奖励"是免费浏览任务（需点击历史搜索词进入），
            // 不属于付费搜索推荐页，不应在此误判
            val isSearchPage = !hasFarmCore && (matchCount >= 2 || (matchCount >= 1 && hasSearchOnlyKeyword))
            if (isSearchPage) {
                debugLog("isOnFarmPage: search recommend page detected (matchCount=$matchCount, hasFarmCore=$hasFarmCore), not farm page")
                farmPageCache = false
                farmPageCacheTime = now
                return false
            }

            val hasFarmContent = allText.any { text ->
                // 种植页特有元素：只在种植页出现的文本，搜索推荐页不会有
                text.contains("集肥料") || text.contains("施肥") ||
                    text.contains("换种") || text.contains("去砍价") ||
                    text.contains("好友林") || text.contains("一起种") ||
                    text.contains("超惠买") || text.contains("兔兔挖肥料") ||
                    text.contains("任务完成") || text.contains("返回首页") ||
                    text.contains("领取奖励") || text.contains("肥料明细") ||
                    // 支付宝农场页特有关键词（H5 页面，文本可能不暴露，需多重兜底）
                    text.contains("领肥料") || text.contains("限时挑战") ||
                    text.contains("得1000肥") || text.contains("赚肥料") ||
                    text.contains("得肥料")
            }
            // 单独的"芭芭农场"不算，搜索推荐页也会有这个文字
            if (!hasFarmContent) {
                // H5 WebView 兜底：支付宝/淘宝农场页是 H5 页面，WebView 可能不暴露文本节点，
                // 导致 collectAllText 返回空列表或极少文本，内容关键词检查必然失败。
                // 此时若 Activity 是农场 H5 容器（h5appactivity/h5webviewactivity/webviewactivity），
                // 且可访问文本节点很少（< 3），假设是农场 H5 页面返回 true。
                // 前提：到达此处前 Activity 已通过 farmKeywords 检查（步骤 2），
                // 且包名已通过农场 App 检查（步骤 3），非广告 Activity（步骤 1）。
                val isH5FarmActivity = activity.contains("h5") || activity.contains("webview")
                if (allText.size < 3 && isH5FarmActivity) {
                    debugLog("isOnFarmPage: H5 WebView fallback triggered (allText.size=${allText.size}, activity=$activity), assuming farm H5 page")
                    farmPageCache = true
                    farmPageCacheTime = now
                    return true
                }
                debugLog("isOnFarmPage: no farm content, sample=${allText.take(8)}")
                farmPageCache = false
                farmPageCacheTime = now
                return false
            }
            farmPageCache = true
            farmPageCacheTime = now
        }

        return true
    }

    /** 获取当前前台 Activity 类名（用于日志和判断） */
    fun getCurrentActivityName(): String? = currentActivityName

    /** 获取当前前台事件包名 */
    fun getCurrentEventPackage(): String? = currentEventPkg

    /**
     * 安全点击节点
     * - 优先 ACTION_CLICK；若节点不可点击则向上找可点击父节点
     * - 仍失败时使用 dispatchGesture 在节点中心位置点击
     * @return true 表示至少触发了一种点击
     */
    fun performClickSafe(node: AccessibilityNodeInfo): Boolean {
        val nodeText = node.text?.toString()?.take(30).orEmpty()
        val nodeDesc = node.contentDescription?.toString()?.take(30).orEmpty()
        val rectInfo = android.graphics.Rect().also { node.getBoundsInScreen(it) }.toShortString()
        debugLog("performClickSafe: text='$nodeText' desc='$nodeDesc' bounds=$rectInfo clickable=${node.isClickable}")
        var target: AccessibilityNodeInfo? = node
        var depth = 0
        while (target != null && depth < 10) {
            if (target.isClickable) {
                val result = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (result) {
                    debugLog("performClickSafe: ACTION_CLICK success at depth=$depth")
                    // WebView 中的元素 ACTION_CLICK 可能不生效（返回 true 但不触发 JS），
                    // 仅在弹窗内的 WebView 节点才额外用手势点击确保。
                    // 非弹窗节点 ACTION_CLICK 已经生效，不需要手势（避免误触悬浮窗）
                    if (isInsideWebViewPopup(node)) {
                        dispatchGestureClickWithWebViewFix(node)
                    }
                    return true
                }
            }
            target = target.parent
            depth++
        }

        debugLog("performClickSafe: ACTION_CLICK failed, trying gesture")
        // 退而求其次：使用手势点击节点中心（带 WebView 坐标修正）
        return dispatchGestureClickWithWebViewFix(node)
    }

    /**
     * 使用 dispatchGesture 点击节点中心位置，处理 WebView 坐标问题
     *
     * WebView 内部节点的 getBoundsInScreen 可能返回不正确的坐标：
     * - 任务列表弹窗节点的 bounds 中 top > bottom（如 [910,3455][1139,2666]）
     * - 这是因为 WebView 的 getBoundsInScreen 返回的是内容坐标，未减去滚动偏移
     *
     * 修正策略：
     * 1. 先尝试 getBoundsInScreen（正常节点直接可用）
     * 2. 如果无效（width<=0 或 top>bottom），尝试 getBoundsInWindow
     * 3. 如果仍无效，使用节点的 left/right 值和 top 值减去估算的滚动偏移
     */
    private fun dispatchGestureClickWithWebViewFix(node: AccessibilityNodeInfo): Boolean {
        val rectScreen = android.graphics.Rect()
        val rectWindow = android.graphics.Rect()
        node.getBoundsInScreen(rectScreen)
        node.getBoundsInWindow(rectWindow)

        debugLog("dispatchGesture: screen=[${rectScreen.toShortString()}] window=[${rectWindow.toShortString()}]")

        // 判断是否在 WebView 弹窗内（弹窗内节点的 screen bounds 是内容坐标，不是真实屏幕坐标）
        val isInPopup = isInsideWebViewPopup(node)

        // 1. 如果不在弹窗内且 screen bounds 有效，直接使用
        if (!isInPopup && rectScreen.width() > 0 && rectScreen.height() > 0 && rectScreen.top < rectScreen.bottom) {
            val x = rectScreen.exactCenterX()
            val y = rectScreen.exactCenterY()
            debugLog("dispatchGesture: not in popup, screen bounds valid, click at ($x, $y)")
            return dispatchGestureClick(x, y)
        }

        // 2. 在弹窗内或 screen bounds 无效，需要修正坐标
        debugLog("dispatchGesture: inPopup=$isInPopup, trying coordinate fix")

        // 2a. 尝试 getBoundsInWindow
        if (rectWindow.width() > 0 && rectWindow.height() > 0 && rectWindow.top < rectWindow.bottom) {
            val x = rectWindow.exactCenterX()
            val y = rectWindow.exactCenterY()
            debugLog("dispatchGesture: window bounds valid, click at ($x, $y)")
            return dispatchGestureClick(x, y)
        }

        // 2b. 用弹窗偏移修正坐标
        val popupOffset = estimateWebViewPopupOffset(node)
        debugLog("dispatchGesture: estimated popupOffset=$popupOffset")
        if (popupOffset != 0 && rectScreen.left > 0) {
            // 用节点的中心X和Y（内容坐标）加上偏移量
            val x = rectScreen.exactCenterX()
            val y = rectScreen.exactCenterY() + popupOffset
            if (x > 0 && y > 0 && x < 1200 && y < 2664) {
                debugLog("dispatchGesture: WebView popup fix, click at ($x, $y) offset=$popupOffset")
                return dispatchGestureClick(x, y)
            }
        }

        debugLog("dispatchGestureClick: all methods failed, node bounds ${rectScreen.toShortString()}")
        return false
    }

    /**
     * 检查节点是否在 WebView 弹窗容器内
     * 弹窗容器的特征：
     * - bounds 的 top == bottom 且 top > 100（高度为0的锚点容器）
     * - 容器的子节点中包含"关闭"或"肥料明细"文本
     */
    private fun isInsideWebViewPopup(node: AccessibilityNodeInfo): Boolean {
        // 检查祖先节点是否有弹窗容器特征
        // 弹窗容器特征：
        // 1. bounds 的 top == bottom 且 top > 100（高度为0的锚点容器，CSS overlay 的 anchor）
        // 2. top >= 2500 且子节点中包含"关闭"或"肥料明细"
        var parent: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (parent != null && depth < 15) {
            val rect = android.graphics.Rect()
            parent.getBoundsInScreen(rect)
            // 弹窗容器特征：top == bottom（高度为0的容器，通常是 CSS overlay 的锚点）
            if (rect.top == rect.bottom && rect.top > 100) {
                return true
            }
            // 弹窗容器特征：top >= 2500 且子节点中有关闭按钮或肥料明细
            if (rect.top >= 2500 && parent.childCount > 0) {
                if (hasChildText(parent, "关闭") || hasChildText(parent, "肥料明细")) {
                    return true
                }
            }
            parent = parent.parent
            depth++
        }
        return false
    }

    /** 递归检查节点及其子节点是否包含指定文本（最多2层） */
    private fun hasChildText(node: AccessibilityNodeInfo, keyword: String): Boolean {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val text = child.text?.toString().orEmpty()
            if (text.contains(keyword)) return true
            for (j in 0 until child.childCount) {
                val grandChild = child.getChild(j) ?: continue
                val gcText = grandChild.text?.toString().orEmpty()
                if (gcText.contains(keyword)) return true
            }
        }
        return false
    }

    /**
     * 估算 WebView 弹窗内节点的内容坐标到屏幕坐标的偏移量
     *
     * 原理：WebView 弹窗内的节点 getBoundsInScreen 返回的是 WebView 内容坐标，
     * 不是真实的屏幕坐标。需要加上偏移量才能转换为屏幕坐标。
     *
     * 计算方法：
     * 1. 找到弹窗内容区域的容器（在弹窗锚点 View 下）
     * 2. 获取弹窗标题区域的 contentY（如"关闭"按钮的 top=546）
     * 3. 假设弹窗标题在屏幕上的位置（约 y=770，即屏幕高度的 29%）
     * 4. 偏移 = 屏幕弹窗标题Y - 内容标题Y
     *
     * @return 偏移量（像素），加到内容坐标上得到屏幕坐标；0 表示无法估算
     */
    private fun estimateWebViewPopupOffset(targetNode: AccessibilityNodeInfo): Int {
        // 先找到弹窗锚点容器
        var anchorNode: AccessibilityNodeInfo? = null
        var parent: AccessibilityNodeInfo? = targetNode.parent
        var depth = 0
        while (parent != null && depth < 10) {
            val rect = android.graphics.Rect()
            parent.getBoundsInScreen(rect)
            if (rect.top == rect.bottom && rect.top > 100) {
                anchorNode = parent
                break
            }
            parent = parent.parent
            depth++
        }

        if (anchorNode == null) {
            debugLog("estimateWebViewPopupOffset: no anchor found")
            return 0
        }

        // 在锚点的子节点中找弹窗标题区域（包含"关闭"按钮的节点）
        // 弹窗标题区域的 contentY 就是其 top 值
        var titleContentY = -1
        for (i in 0 until anchorNode.childCount) {
            val child = anchorNode.getChild(i) ?: continue
            if (hasChildText(child, "关闭")) {
                val rect = android.graphics.Rect()
                child.getBoundsInScreen(rect)
                titleContentY = rect.top
                debugLog("estimateWebViewPopupOffset: found title area, contentY=$titleContentY")
                break
            }
        }

        if (titleContentY < 0) {
            debugLog("estimateWebViewPopupOffset: no title area found, using default offset=410")
            // 使用默认偏移（基于之前的分析：弹窗标题 contentY≈546, 屏幕Y≈770）
            return 410
        }

        // 弹窗标题在屏幕上的位置（估算）
        // 弹窗通常从屏幕高度的约 29% 位置开始（y ≈ 770 on 2664 screen）
        val screenTitleY = (2664 * 0.29).toInt()  // ≈ 772
        val offset = screenTitleY - titleContentY
        debugLog("estimateWebViewPopupOffset: titleContentY=$titleContentY screenTitleY=$screenTitleY offset=$offset")
        return offset
    }

    /** 在指定坐标执行手势点击（200ms触摸时间，兼容淘宝等App） */
    fun dispatchGestureClick(x: Float, y: Float): Boolean {
        val path = android.graphics.Path().apply { moveTo(x, y) }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 200))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * 模拟滑动（上下滑动浏览页面获取肥料）
     * @param startX 起始X坐标
     * @param startY 起始Y坐标
     * @param endX 结束X坐标
     * @param endY 结束Y坐标
     * @param durationMs 滑动持续时间（毫秒）
     */
    fun dispatchGestureSwipe(
        startX: Float, startY: Float, endX: Float, endY: Float,
        durationMs: Long = 500
    ): Boolean {
        val path = android.graphics.Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        debugLog("dispatchGestureSwipe: ($startX,$startY) -> ($endX,$endY) duration=${durationMs}ms")
        return dispatchGesture(gesture, null, null)
    }

    // ============== 游戏任务支持（AI 游戏达人） ==============

    /**
     * 检测游戏完成页面
     * - 游戏完成信号：领取奖励/恭喜/完成/升级/通关/挑战成功/获得肥料/任务完成
     *
     * 上下文校验（防广告陷阱）：
     * - 若页面同时是广告主落地页（含多个诱导按钮且无农场/游戏核心），说明"恭喜/完成任务"
     *   是广告伪装的诱导文案，不应识别为游戏完成
     * - 严格匹配"恭喜获得"等关键词，避免"恭喜获得优惠券"等诱导文案误判
     *
     * @return true 表示当前是游戏完成页面
     */
    fun isGameCompletePage(): Boolean {
        val root = rootInActiveWindowSafe() ?: return false
        val allText = collectAllText(root)
        // 收紧完成关键词：移除过宽的"恭喜"（"恭喜您获得优惠券"等广告文案会误匹配）
        // 保留更明确的"恭喜获得"/"挑战成功"/"通关"等组合
        val isComplete = allText.any { text ->
            text.contains("领取奖励") ||
                text.contains("挑战成功") || text.contains("通关") ||
                text.contains("升级成功") || text.contains("获得肥料") ||
                text.contains("任务完成") || text.contains("已完成") ||
                text.contains("恭喜获得") || text.contains("游戏结束")
        }
        if (!isComplete) return false
        // 上下文校验：若同时是广告主落地页（含多个诱导按钮且无农场/游戏核心文案），
        // 说明"恭喜获得/任务完成"是广告伪装，不识别为游戏完成
        if (isAdLandingPage()) {
            debugLog("isGameCompletePage: NO (text matched but isAdLandingPage=true, suspected ad bait)")
            return false
        }
        // 进一步校验：页面必须存在游戏/农场相关文案（避免纯诱导文案被误判）
        val hasGameOrFarmContext = allText.any { text ->
            text.contains("肥料") || text.contains("游戏") || text.contains("挑战") ||
                text.contains("关卡") || text.contains("升级") || text.contains("通关") ||
                text.contains("芭芭农场") || text.contains("任务")
        }
        if (!hasGameOrFarmContext) {
            debugLog("isGameCompletePage: NO (no game/farm context, suspected ad bait)")
            return false
        }
        debugLog("isGameCompletePage: YES, sample=${allText.take(5)}")
        return true
    }

    /**
     * 查找并点击"领取奖励"/"确认"/"完成"按钮
     * @return true 表示成功点击
     */
    fun clickClaimRewardButton(): Boolean {
        // 优先用 findClaimRewardButton（已有方法）
        val claimBtn = findClaimRewardButton() ?: findClaimRewardButtonExact()
        if (claimBtn != null) {
            debugLog("clickClaimRewardButton: found claim button, clicking")
            return performClickSafe(claimBtn)
        }
        // fallback：搜索常见领取/确认/完成按钮文本
        val root = rootInActiveWindowSafe() ?: return false
        val keywords = listOf("领取", "领取奖励", "确认", "确定", "完成", "继续", "下一步")
        for (kw in keywords) {
            val node = findNodeByText(root, kw)
            if (node != null) {
                debugLog("clickClaimRewardButton: found by text='$kw', clicking")
                if (performClickSafe(node)) return true
            }
        }
        debugLog("clickClaimRewardButton: no claim button found")
        return false
    }

    /**
     * 检测当前页面是否有"滑动获取肥料"的提示，并解析需要滑动的时间
     * - 查找页面文本中的提示，如"滑动浏览15秒"、"浏览30秒"、"滑动30s"等
     * - 如果找到提示，返回需要滑动的秒数；否则返回 0
     * @return 需要滑动的秒数，0 表示没有找到提示
     */
    fun findSwipeForFertilizerHint(): Int {
        val root = getRootInFarmApp() ?: return 0
        val allText = collectAllText(root)
        // 匹配"滑动浏览15秒"、"浏览30秒"、"滑动30s"、"浏览15s"等
        val swipeHintKeywords = listOf("滑动", "浏览", "划动", "上滑", "下滑", "swipe", "browse")
        for (text in allText) {
            val lower = text.lowercase()
            // 检查是否包含滑动提示关键词
            if (swipeHintKeywords.any { lower.contains(it) }) {
                // 尝试解析秒数：查找数字 + 秒/s 的模式
                val secondsMatch = Regex("(\\d+)\\s*[秒s]").find(text)
                if (secondsMatch != null) {
                    val seconds = secondsMatch.groupValues[1].toIntOrNull() ?: 0
                    if (seconds > 0 && seconds <= 300) {  // 合理范围：1-300秒
                        debugLog("findSwipeForFertilizerHint: found hint '$text', seconds=$seconds")
                        return seconds
                    }
                }
            }
        }
        return 0
    }

    /**
     * 检测浏览任务页面是否有"再逛xx秒后可领奖"倒计时提示
     *
     * UC 极速版等平台的浏览任务会有倒计时提示（如"再逛15秒后可领奖"），
     * 必须等到该提示消失（倒计时结束、可领奖）才能退出浏览页面，
     * 否则提前退出会拿不到肥料。
     *
     * 识别文本示例：
     * - "再逛15秒后可领奖" / "再逛15s后可领奖" / "再逛15s"
     * - "逛15秒后可领取" / "逛15s后可领取"
     * - "再逛15秒" / "逛15秒"
     *
     * @return 剩余秒数（>0 表示倒计时还在，0 表示没有提示或倒计时已结束）
     */
    fun findBrowseRewardCountdownHint(): Int {
        val root = getRootInFarmApp() ?: return 0
        val allText = collectAllText(root)
        for (text in allText) {
            // 匹配"再逛15秒"、"逛15s"、"再逛15秒后可领奖"等
            val match = Regex("(?:再逛|逛)\\s*(\\d+)\\s*[秒s]").find(text)
            if (match != null) {
                val seconds = match.groupValues[1].toIntOrNull() ?: 0
                if (seconds > 0 && seconds <= 300) {  // 合理范围：1-300秒
                    throttledLog("browseCountdown_$seconds", "findBrowseRewardCountdownHint: found '$text', seconds=$seconds")
                    return seconds
                }
            }
        }
        return 0
    }

    /**
     * 解析广告页面显示的"指定观看时长"提示
     *
     * 用户需求：有些广告页面需要指定时间才能领取肥料，太快退出会获取不到肥料。
     * 应保持到"规定时间+1秒"后再检测退出。
     *
     * 识别的提示文本示例：
     * - "观看15秒后可领取" / "观看30s"
     * - "15秒后可关闭" / "30s后可领取"
     * - "倒计时15s" / "倒计时30秒"
     * - "还剩15秒" / "剩余15s"
     * - "15s" / "15秒"（广告页面上独立的倒计时数字）
     *
     * 注意：仅在广告页面上调用，避免误匹配农场页面的其他文本。
     * @return 需要观看的秒数，0 表示没有找到时长提示
     */
    fun findAdDurationHint(): Int {
        // 在农场页面上不解析广告时长（避免误匹配）
        if (isOnFarmPage()) return 0
        val root = rootInActiveWindowSafe() ?: return 0
        val allText = collectAllText(root)
        // 优先匹配带关键词的时长提示（更精确）
        val adDurationKeywords = listOf(
            "观看", "倒计时", "还剩", "剩余", "后可领取", "后可关闭",
            "后领取", "后关闭", "watch", "countdown", "remain"
        )
        var bestSeconds = 0
        for (text in allText) {
            val lower = text.lowercase()
            // 检查是否包含广告时长关键词
            if (adDurationKeywords.any { lower.contains(it) }) {
                val secondsMatch = Regex("(\\d+)\\s*[秒s]").find(text)
                if (secondsMatch != null) {
                    val seconds = secondsMatch.groupValues[1].toIntOrNull() ?: 0
                    if (seconds > 0 && seconds <= 300) {  // 合理范围：1-300秒
                        debugLog("findAdDurationHint: found hint '$text', seconds=$seconds")
                        if (seconds > bestSeconds) bestSeconds = seconds
                    }
                }
            }
        }
        if (bestSeconds > 0) return bestSeconds
        // 兜底：在广告页面上查找独立的 "Ns" / "N秒" 倒计时（最后手段）
        // 仅当确认在广告页面时才使用，避免误匹配
        if (isAdActivity() || isAdPlaying() || isAdContentShown()) {
            for (text in allText) {
                // 精确匹配纯倒计时文本，如 "15s" / "30秒" / "15 s"
                val exactMatch = Regex("^(\\d+)\\s*[秒s]$").find(text.trim())
                if (exactMatch != null) {
                    val seconds = exactMatch.groupValues[1].toIntOrNull() ?: 0
                    if (seconds > 0 && seconds <= 300) {
                        debugLog("findAdDurationHint: found countdown '$text', seconds=$seconds")
                        if (seconds > bestSeconds) bestSeconds = seconds
                    }
                }
            }
        }
        return bestSeconds
    }

    /**
     * 多信号融合的广告结束检测（优化方案）
     *
     * 相比单一信号检测，融合多个广告结束信号，提高检测准确率：
     *
     * 信号1：倒计时消失（广告播放中→倒计时文字消失）
     * 信号2：领取奖励按钮出现（广告结束后常显示"领取奖励"）
     * 信号3：关闭按钮变为可点击（倒计时期间灰色→结束后可点击）
     * 信号4：任务完成页（isTaskCompletePage）
     * 信号5：广告 Activity 切换（isAdActivity 从 true→false）
     *
     * 任一强信号命中即认为广告结束：
     * - 强信号：任务完成页 + 领取奖励按钮 + 广告Activity切换
     * - 弱信号：倒计时消失（需配合仍在广告页才认定）
     *
     * @param prevHadCountdown 上一轮是否有倒计时（用于检测倒计时消失）
     * @return true 表示广告已结束
     */
    fun isAdEndedMultiSignal(prevHadCountdown: Boolean): Boolean {
        // 强信号1：任务完成页（已含广告陷阱防护）
        if (isTaskCompletePage()) {
            debugLog("isAdEndedMultiSignal: YES (task complete page)")
            return true
        }
        // 强信号2：广告 Activity 切换（从广告 Activity 退出）
        if (!isAdActivity() && !isAdPlaying()) {
            // 进一步确认：不在农场页（避免误判回农场为广告结束）
            if (!isOnFarmPage()) {
                debugLog("isAdEndedMultiSignal: YES (ad activity ended, not on farm)")
                return true
            }
        }
        // 强信号3：领取奖励按钮出现（广告结束后的领取入口）
        val claimBtn = findClaimRewardButton()
        if (claimBtn != null) {
            debugLog("isAdEndedMultiSignal: YES (claim reward button appeared)")
            return true
        }
        // 弱信号：倒计时消失（上一轮有倒计时，本轮没有，且仍在广告页）
        // 需配合仍在广告页才认定，单独倒计时消失可能是页面刷新
        if (prevHadCountdown) {
            val currentCountdown = findAdDurationHint()
            if (currentCountdown == 0 && (isAdActivity() || isAdContentShown())) {
                debugLog("isAdEndedMultiSignal: YES (countdown disappeared while still on ad page)")
                return true
            }
        }
        return false
    }


    /**
     * 查找"我要更快拿奖"按钮（UC 芭芭农场广告页第一层入口）
     *
     * 用户需求流程：
     * 1. 广告页显示"我要更快拿奖"按钮 → 点击它
     * 2. 弹出"15秒更快拿奖"确认弹窗 → 点击"取消"
     * 3. 回到弹窗前的广告页面，停留 1 秒后回芭芭农场
     *
     * 注意：本方法只查找"我要更快拿奖"按钮（精确匹配，不含"取消"）
     * 由调用方在点击后等待弹窗，再用 findFasterRewardCancelButton() 查找取消按钮
     *
     * @return "我要更快拿奖"按钮节点，null 表示未找到
     */
    fun findFasterRewardEntryButton(): AccessibilityNodeInfo? {
        val root = rootInActiveWindowSafe() ?: return null
        // 精确匹配"我要更快拿奖"，避免误匹配弹窗里的"取消"
        val node = findNodeByText(root, "我要更快拿奖")
        if (node != null) {
            debugLog("findFasterRewardEntryButton: found '我要更快拿奖'")
            return node
        }
        return null
    }

    /**
     * 检测是否显示了"更快拿奖"确认弹窗（第二层）
     *
     * 用户需求：点击"我要更快拿奖"后会弹出确认弹窗（含"15秒更快拿奖"文案和"取消"按钮），
     * 遇到此弹窗点"取消"返回到弹窗前的广告页面。
     *
     * 检测条件：页面文本包含"更快拿奖"（确认弹窗的标题/文案通常含此关键词）
     * 注意：初始的"我要更快拿奖"按钮页面也会包含此关键词，调用方应先检查是否已点击过入口按钮
     *
     * @return true 表示检测到"更快拿奖"相关弹窗
     */
    fun isFasterRewardPopupShown(): Boolean {
        val root = rootInActiveWindowSafe() ?: return false
        val allText = collectAllText(root)
        val hasFasterReward = allText.any { text ->
            text.contains("更快拿奖")
        }
        if (hasFasterReward) {
            debugLog("isFasterRewardPopupShown: YES, faster reward popup detected")
        }
        return hasFasterReward
    }

    /**
     * 查找"更快拿奖"确认弹窗的"取消"按钮
     *
     * 确认弹窗通常有两个按钮：
     * - 确认类（不点，避免进入额外的广告停留流程）
     * - "取消" / "暂不" / "关闭" / "×"（点这个，返回弹窗前的广告页面）
     *
     * @return 取消按钮节点，null 表示未找到
     */
    fun findFasterRewardCancelButton(): AccessibilityNodeInfo? {
        val root = rootInActiveWindowSafe() ?: return null
        // 匹配纯取消按钮文案
        val cancelKeywords = listOf("取消", "暂不", "不了", "关闭", "×", "close", "以后再说", "残忍拒绝")
        for (kw in cancelKeywords) {
            val node = findNodeByText(root, kw)
            if (node != null) {
                debugLog("findFasterRewardCancelButton: found '$kw'")
                return node
            }
        }
        return null
    }

    /**
     * 查找"更快拿奖"确认弹窗的"允许"按钮
     *
     * 用户需求：点击"我要更快拿奖"后弹出确认弹窗，点击"允许"会打开新的 App 窗口，
     * 停留16秒后关闭新打开的 App，回到"恭喜获得奖励提升"窗口，点右上角关闭回芭芭农场。
     *
     * @return "允许"按钮节点，null 表示未找到
     */
    fun findFasterRewardAllowButton(): AccessibilityNodeInfo? {
        val root = rootInActiveWindowSafe() ?: return null
        val allowKeywords = listOf("允许", "同意", "确定", "确认", "立即开启", "去开启", "继续")
        for (kw in allowKeywords) {
            val node = findNodeByText(root, kw)
            if (node != null) {
                debugLog("findFasterRewardAllowButton: found '$kw'")
                return node
            }
        }
        return null
    }

    /**
     * 检测是否显示了"恭喜获得奖励提升"窗口
     *
     * 用户需求：关闭新打开的 App 后会回到"恭喜获得奖励提升"窗口，
     * 需要点击右上角关闭按钮回到芭芭农场页面。
     *
     * @return true 表示检测到"恭喜获得奖励提升"窗口
     */
    fun isRewardUpgradePopupShown(): Boolean {
        val root = rootInActiveWindowSafe() ?: return false
        val allText = collectAllText(root)
        val hasRewardUpgrade = allText.any { text ->
            text.contains("恭喜获得") && text.contains("奖励提升") ||
                text.contains("奖励提升") || text.contains("获得奖励提升")
        }
        if (hasRewardUpgrade) {
            debugLog("isRewardUpgradePopupShown: YES, reward upgrade popup detected")
        }
        return hasRewardUpgrade
    }

    /** 调试：dump 整个节点树到文件（通过 adb broadcast -a com.bbncbot.DUMP_NODES 触发） */
    fun dumpNodeTree() {
        try {
            val root = getRootInFarmApp()
            if (root == null) {
                debugLog("dumpNodeTree: root is null")
                return
            }
            val sb = StringBuilder()
            sb.appendLine("=== Node Tree Dump (platform=$currentPlatform) ===")
            dumpNodeRecursive(root, 0, sb)
            val file = java.io.File(
                android.os.Environment.getExternalStorageDirectory(),
                "Android/data/com.bbncbot/files/node_dump.txt"
            )
            file.parentFile?.mkdirs()
            file.writeText(sb.toString())
            debugLog("dumpNodeTree: written to ${file.absolutePath}, size=${sb.length}")
            Log.i(TAG, "dumpNodeTree: written to ${file.absolutePath}")
        } catch (e: Exception) {
            debugLog("dumpNodeTree: error ${e.message}")
            Log.e(TAG, "dumpNodeTree failed", e)
        }
    }

    private fun dumpNodeRecursive(node: AccessibilityNodeInfo, depth: Int, sb: StringBuilder) {
        if (depth > 30) return
        val indent = "  ".repeat(depth)
        val rectScreen = android.graphics.Rect()
        val rectWindow = android.graphics.Rect()
        node.getBoundsInScreen(rectScreen)
        node.getBoundsInWindow(rectWindow)
        val text = node.text?.toString()?.take(50).orEmpty()
        val desc = node.contentDescription?.toString()?.take(50).orEmpty()
        val className = node.className?.toString()?.substringAfterLast('.').orEmpty()
        val clickable = node.isClickable
        val enabled = node.isEnabled
        val scrollable = node.isScrollable
        val checkable = node.isCheckable
        val checked = node.isChecked
        // 仅在 screen 和 window 坐标不同时输出两者
        val boundsStr = if (rectScreen == rectWindow) rectScreen.toShortString()
            else "${rectScreen.toShortString()}/win=${rectWindow.toShortString()}"
        sb.appendLine("$indent[$className] bounds=$boundsStr text='$text' desc='$desc' click=$clickable en=$enabled scroll=$scrollable check=$checkable/$checked childCount=${node.childCount}")
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            dumpNodeRecursive(child, depth + 1, sb)
        }
    }

    /** 模拟按下返回键（用于关闭广告/弹窗返回农场页） */
    fun pressBack(): Boolean {
        return try {
            performGlobalAction(GLOBAL_ACTION_BACK)
        } catch (e: Exception) {
            Log.w(TAG, "pressBack failed: ${e.message}")
            false
        }
    }

    // ============== v2 智能按钮查找方法（多平台版） ==============

    /**
     * 查找"集肥料"按钮（橙色大按钮）
     * - 优先使用节点树查找当前平台配置的文本候选
     * - 失败时返回 null，由调用方尝试坐标候选位置
     * @return 按钮节点或null
     */
    fun findCollectFertilizerButton(): AccessibilityNodeInfo? {
        val root = getRootInFarmApp()
        if (root == null) {
            debugLog("findCollectFertilizerButton: root is null!")
            return null
        }
        val keywords = currentPlatformConfig().collectFertilizerTexts
        for (kw in keywords) {
            val node = findNodeByText(root, kw)
            if (node != null) {
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                debugLog("findCollectFertilizerButton: found by text='$kw' bounds=${rect.toShortString()}")
                Log.d(TAG, "findCollectFertilizerButton: found by text='$kw'")
                return node
            }
        }
        debugLog("findCollectFertilizerButton: not found by text, root childCount=${root.childCount}")
        Log.d(TAG, "findCollectFertilizerButton: not found by text")
        return null
    }

    /**
     * 查找所有"去完成"按钮
     * - 使用当前平台配置的文本候选
     * @return 按钮节点列表
     */
    fun findGoCompleteButtons(): List<AccessibilityNodeInfo> {
        val root = getRootInFarmApp() ?: return emptyList()
        val keywords = currentPlatformConfig().goCompleteTexts
        val result = mutableListOf<AccessibilityNodeInfo>()
        val seen = HashSet<Int>()
        collectNodesByText(root, keywords, result, seen)
        Log.d(TAG, "findGoCompleteButtons: found ${result.size} buttons")
        return result
    }

    /**
     * 查找直接领取弹窗内的"逛一逛/再赚"浏览入口按钮
     * - 用户场景：点"立即领取"领到肥料后，弹窗不关闭，
     *   原按钮文字变为"点此逛一逛再赚1000肥料"等浏览任务入口
     * - 需要继续点击这个按钮进入浏览流程
     * - 不走 findGoCompleteButtons：因为弹窗里这些文字是按钮自身的 text，
     *   不属于平台 goCompleteTexts 配置，且需要更宽松匹配（含"再赚"/"赚N肥料"）
     * @return 浏览入口按钮节点，null 表示未找到
     */
    fun findBrowseEntryInPopup(): AccessibilityNodeInfo? {
        val root = rootInActiveWindowSafe() ?: return null
        val keywords = listOf("点此逛一逛", "逛一逛", "再赚", "赚1000", "逛农货", "去逛逛")
        for (kw in keywords) {
            val node = findNodeByText(root, kw)
            if (node != null) {
                val text = node.text?.toString().orEmpty()
                val desc = node.contentDescription?.toString().orEmpty()
                val combined = text + desc
                // 必须包含"逛"或"赚"，确保是浏览入口而非其他按钮
                if (combined.contains("逛") || combined.contains("赚")) {
                    debugLog("findBrowseEntryInPopup: found by text='$kw', full='$combined'")
                    Log.d(TAG, "findBrowseEntryInPopup: found by text='$kw'")
                    return node
                }
            }
        }
        debugLog("findBrowseEntryInPopup: not found")
        return null
    }

    /**
     * 在商品列表页面随便点击一个商品（模拟用户浏览行为）
     * - 查找屏幕中下部区域的可点击节点（通常是商品卡片）
     * - 排除"去完成"、"下单"等按钮，排除顶部导航栏
     * - 找到后点击并返回 true，找不到返回 false
     */
    fun clickFirstProductInList(): Boolean {
        val root = getRootInFarmApp() ?: return false
        // 查找屏幕中下部区域（Y > 500）的可点击节点
        // 排除顶部的导航栏和底部的任务栏
        val target = findFirstClickableProductCard(root)
        if (target != null) {
            val rect = android.graphics.Rect()
            target.getBoundsInScreen(rect)
            debugLog("clickFirstProductInList: clicking card at bounds=$rect")
            return performClickSafe(target)
        }
        // 诊断日志：列出页面上所有可点击节点，帮助定位为什么找不到商品卡片
        val clickableNodes = mutableListOf<String>()
        collectClickableNodes(root, clickableNodes, maxCount = 15)
        debugLog("clickFirstProductInList: no product card found, page type=${getPageType()}, clickable nodes: $clickableNodes")
        return false
    }

    /** 递归收集页面上所有可点击节点及其文本/位置，用于诊断 */
    private fun collectClickableNodes(node: AccessibilityNodeInfo, out: MutableList<String>, maxCount: Int = 15) {
        if (out.size >= maxCount) return
        if (node.isClickable) {
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val label = if (text.isNotEmpty()) text else if (desc.isNotEmpty()) desc else "(no text)"
            out.add("$label@[$rect.top,$rect.bottom]")
            if (out.size >= maxCount) return
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectClickableNodes(child, out, maxCount)
            if (out.size >= maxCount) return
        }
    }

    /**
     * 递归查找第一个可点击的商品卡片节点
     * - 在屏幕中下部区域（Y > 500 且 Y < 2300）
     * - 是可点击的节点
     * - 不包含"去完成"、"下单"、"购买"等按钮文字
     * - 优先选择较小的可点击节点（商品卡片），而非大容器
     */
    private fun findFirstClickableProductCard(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        // 检查节点是否在屏幕中下部区域
        val inTargetArea = rect.top > 500 && rect.bottom < 2300 && rect.height() > 100 && rect.width() > 200
        if (inTargetArea && node.isClickable) {
            // 排除包含交易/购买/下单等文字的节点（确保浏览任务不产生交易）
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val combined = "$text $desc"
            val excludeKeywords = listOf(
                "去完成", "下单", "购买", "付款", "充值", "付费", "返回", "关闭", "搜索",  // 原有
                "立即购买", "加入购物车", "立即下单", "提交订单", "确认订单",              // 交易按钮
                "立即支付", "去支付", "去结算", "订金", "定金"                            // 支付/结算按钮
            )
            if (excludeKeywords.none { combined.contains(it) }) {
                return node
            }
        }
        // 递归查找子节点
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFirstClickableProductCard(child)
            if (result != null) return result
        }
        return null
    }

    /**
     * 判断"去完成"按钮对应的任务是否需要花钱
     * - 通过检查按钮所在行的兄弟/父节点文本来判断
     * - 花钱关键词：下单、购买、付款、开店、消费
     * @return true 表示是花钱任务，应跳过
     */
    fun isPaidTask(button: AccessibilityNodeInfo): Boolean {
        // 注意："下单得奖励"不是花钱任务！是浏览搜索结果页面就能得肥料的任务
        // 只有明确要求花钱/交易的才是付费任务，跳过
        // 作为广告设计专家：识别广告主的真实付费意图（CPA/CPS/充值类任务）
        // 通用付费关键词 + 平台专属付费关键词（差异化：淘宝下单陷阱多，支付宝金融陷阱多）
        val paidKeywords = listOf(
            "购买", "付款", "充值", "付费", "消费满",   // 基础交易
            "首充", "首单", "首购",                       // 首次交易引导
            "开通会员", "立即开通", "订阅", "续费",       // 会员/订阅类
            "投资", "理财", "保证金", "押金",             // 金融类
            "下单购买", "立即购买",                       // 明确购买（注意："下单得"不含"购买"，浏览任务不受影响）
            "去支付", "立即支付", "确认支付",             // 支付类
            "到店支付", "线下支付",                        // 到店支付类
            "合种"                                        // 合种类（需邀请好友，非广告任务）
        ) + currentPlatformConfig().paidTaskKeywords
        val contextText = collectTaskContextText(button)
        val isPaid = paidKeywords.any { contextText.contains(it) }
        if (isPaid) {
            debugLog("isPaidTask: YES, context='$contextText'")
        }
        return isPaid
    }

    /**
     * 判断"去完成"按钮对应的任务是否是游戏类任务
     * - 游戏关键词：玩游戏、游戏、挑战、闯关、消消乐、斗地主、赢肥料
     * - 这类任务需要进入游戏 App 玩游戏，自动化无法完成，应跳过
     * @return true 表示是游戏任务，应跳过
     */
    fun isGameTask(button: AccessibilityNodeInfo): Boolean {
        // 通用游戏关键词 + 平台专属游戏关键词（差异化：支付宝小游戏入口最多）
        val gameKeywords = listOf(
            "玩游戏", "游戏", "挑战", "闯关", "消消乐", "斗地主",
            "赢肥料", "玩一玩", "小游戏", "通关", "得分",
            "大转盘", "抽奖", "摇一摇",
            "浪漫餐厅", "农场分色瓶", "继续玩",
            "对战", "完成1局", "完成一局", "局对战", "打一局"
        ) + currentPlatformConfig().gameTaskKeywords
        val contextText = collectTaskContextText(button)
        val isGame = gameKeywords.any { contextText.contains(it) }
        if (isGame) {
            debugLog("isGameTask: YES, context='$contextText'")
        }
        return isGame
    }

    /**
     * 判断"去完成"按钮对应的任务是否是"滑动浏览"类型
     * - 滑动浏览关键词：浏览、逛逛、滑动、看一看
     * - 这类任务不需要点击进入页面，而是模拟上下滑动
     * @return true 表示是滑动浏览任务
     */
    fun isBrowseTask(button: AccessibilityNodeInfo): Boolean {
        // "下单得奖励"是浏览搜索结果页面的任务，浏览后就能得肥料
        // "发现精选好物"、"搜一搜你心仪得宝贝"、"看严选推荐商品" 需要点击商品并滑动浏览
        // 通用浏览关键词 + 平台专属浏览关键词（差异化：淘宝浏览任务最多）
        val browseKeywords = listOf(
            "浏览", "逛逛", "滑动", "看一看", "看商品", "下单得", "搜索",
            "精选好物", "心仪", "严选推荐", "发现精选", "搜一搜",
            "宝贝", "好物", "推荐商品", "发现", "严选"
        ) + currentPlatformConfig().browseTaskKeywords
        val contextText = collectTaskContextText(button)
        val isBrowse = browseKeywords.any { contextText.contains(it) }
        debugLog("isBrowseTask: context='$contextText', isBrowse=$isBrowse")
        return isBrowse
    }

    /**
     * 检测是否误入非农场小程序陷阱（支付宝/淘宝特有）
     *
     * 广告设计者意图：诱导用户点击后跳转到其他小程序（非芭芭农场），
     * 通过小程序内广告/下载/购买获取转化收益。
     * - 误入的小程序页面底部常显示"本小程序由 XXX 提供"
     * - 且页面无农场核心文案（集肥料/施肥/芭芭农场）
     *
     * UC 无小程序，此方法对 UC 平台始终返回 false。
     *
     * @return true 表示误入了非农场小程序陷阱，应立即返回
     */
    fun isMiniProgramTrap(): Boolean {
        val trapKeywords = currentPlatformConfig().miniProgramTrapKeywords
        if (trapKeywords.isEmpty()) return false  // UC 无小程序
        val root = rootInActiveWindowSafe() ?: return false
        val allText = collectAllText(root)
        // 必须含小程序陷阱关键词 + 无农场核心文案
        val hasTrapKeyword = allText.any { text ->
            trapKeywords.any { kw -> text.contains(kw) }
        }
        if (!hasTrapKeyword) return false
        // 农场核心文案存在则不是陷阱（说明还在芭芭农场小程序内）
        val hasFarmCore = allText.any { text ->
            text.contains("集肥料") || text.contains("施肥") ||
                text.contains("芭芭农场") || text.contains("任务列表")
        }
        if (hasFarmCore) return false
        debugLog("isMiniProgramTrap: YES (mini-program trap detected, no farm core)")
        return true
    }

    /**
     * 收集任务按钮所在行的上下文文本
     * - 向上查找任务行容器，然后收集该容器内所有文本
     * - 任务列表中每一行结构通常是：[描述文本] + [去完成按钮]
     */
    fun collectTaskContextText(button: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        // 向上找2层父节点作为任务行容器，避免收集整个列表的文本
        var container: AccessibilityNodeInfo? = button.parent
        if (container != null) {
            container = container.parent
        }
        if (container != null) {
            // 只收集该容器内的文本（深度4层，确保覆盖任务行内所有文本）
            collectTextRecursive(container, sb, maxDepth = 4)
        }
        val result = sb.toString()
        debugLog("collectTaskContextText: result='$result', buttonText='${button.text}', containerFound=${container != null}")
        return result
    }

    /** 递归收集节点及其子节点的文本 */
    private fun collectTextRecursive(node: AccessibilityNodeInfo, sb: StringBuilder, maxDepth: Int) {
        if (maxDepth <= 0) return
        node.text?.toString()?.let { if (it.isNotBlank()) sb.append(it).append(" ") }
        node.contentDescription?.toString()?.let { if (it.isNotBlank()) sb.append(it).append(" ") }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTextRecursive(child, sb, maxDepth - 1)
        }
    }

    /**
     * 检测浏览任务页面是否有红包弹窗，并返回可关闭/领取的按钮
     *
     * 用户需求：浏览任务页面会弹出红包窗口，需要先关闭它才能继续滑动获取肥料。
     * 红包弹窗通常有"红包"字样，关闭/领取按钮文案如"开心收下"、"收下"、
     * "立即领取"、"好的"、"知道了"、"继续赚钱"等。
     *
     * 识别策略：
     * 1. 先确认页面文本包含"红包"（避免误点任务列表的"领取"按钮）
     * 2. 在包含"红包"的前提下，查找关闭/领取按钮
     *
     * @return 红包弹窗的关闭/领取按钮节点，null 表示没有红包弹窗
     */
    fun findRedPacketCloseButton(): AccessibilityNodeInfo? {
        val root = getRootInFarmApp() ?: return null
        val allText = collectAllText(root)
        // 1. 必须先确认有红包弹窗（页面文本包含"红包"）
        // 排除农场主页常驻的"钓红包"按钮：它只是入口，不是弹窗
        // 真正的红包弹窗会有"立即领取""开心收下""领取红包"等弹窗专属按钮文案
        val hasRedPacketText = allText.any { it.contains("红包") }
        if (!hasRedPacketText) return null
        val hasRedPacketPopupButton = allText.any { text ->
            // 红包弹窗的领取/关闭按钮文案，"钓红包"入口不在此列
            text.contains("开心收下") || text.contains("立即领取") ||
                text.contains("领取红包") || text.contains("继续赚钱") ||
                text.contains("继续逛") || text.contains("去使用")
        }
        if (!hasRedPacketPopupButton) {
            // 有"红包"字样但无弹窗专属按钮，可能是农场主页"钓红包"入口，不当弹窗处理
            return null
        }
        // 2. 查找红包弹窗的关闭/领取按钮
        // 注意："立即领取"放最前：红包弹窗的领取按钮通常是这个文字
        // "×"和"关闭"用于右上角关闭图标
        val keywords = listOf(
            "开心收下", "收下", "立即领取", "领取红包", "好的", "知道了",
            "我知道了", "知道啦", "继续赚钱", "继续逛", "去使用", "×", "关闭", "close"
        )
        for (kw in keywords) {
            val node = findNodeByText(root, kw)
            if (node != null) {
                throttledLog("redPacket_$kw", "findRedPacketCloseButton: found '$kw' in red packet popup")
                return node
            }
        }
        // 3. 文字没找到，尝试查找右上角区域的可点击小图标（红包弹窗关闭按钮通常在右上角）
        val closeIcon = findTopRightClickableIcon(root, isRight = true)
        if (closeIcon != null) {
            debugLog("findRedPacketCloseButton: found top-right close icon in red packet popup")
            return closeIcon
        }
        debugLog("findRedPacketCloseButton: red packet popup detected but no close button found")
        return null
    }

    /**
     * 判断当前页面是否显示红包弹窗（仅检测，不返回按钮）
     * @return true 表示页面有红包弹窗
     */
    fun isRedPacketPopupShown(): Boolean {
        val root = getRootInFarmApp() ?: return false
        val allText = collectAllText(root)
        // 同 findRedPacketCloseButton：排除农场主页"钓红包"入口
        val hasRedPacketText = allText.any { it.contains("红包") }
        if (!hasRedPacketText) return false
        return allText.any { text ->
            text.contains("开心收下") || text.contains("立即领取") ||
                text.contains("领取红包") || text.contains("继续赚钱") ||
                text.contains("继续逛") || text.contains("去使用")
        }
    }

    /**
     * 查找广告关闭按钮
     * - 优先按平台特有关闭文本查找（[platformTexts]），更精确匹配平台广告 SDK 的关闭按钮
     * - 其次查找通用"×"、"关闭"、"close"、"跳过"、"skip"节点
     * - 最后查找右上角可点击小图标
     * - 失败时返回null，由调用方尝试坐标候选
     * @param platformTexts 平台特有的广告关闭按钮文本（如 UC 的"跳过广告"/"关闭广告"）
     * @return 按钮节点或null
     */
    fun findAdCloseButton(platformTexts: List<String> = emptyList()): AccessibilityNodeInfo? {
        val root = rootInActiveWindowSafe() ?: return null
        // 陷阱按钮黑名单（来自平台配置）：避免把"立即下载/去购买/查看详情"等诱导按钮误识别为关闭
        val trapTexts = currentPlatformConfig().adInstallButtonTexts
        // 检查节点是否是诱导按钮（按子串匹配，如"领取优惠"含"领取"会被误识别为奖励按钮，
        // 故严格按整段文本/desc 检查是否包含陷阱关键词）
        fun isTrapNode(node: AccessibilityNodeInfo): Boolean {
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val combined = text + desc
            return trapTexts.any { trap -> combined.contains(trap) }
        }
        // 优先按平台特有关闭文本查找（更精确，避免误匹配），且排除诱导按钮
        for (kw in platformTexts) {
            val node = findNodeByText(root, kw)
            if (node != null && !isTrapNode(node)) {
                Log.d(TAG, "findAdCloseButton: found by platform text='$kw'")
                return node
            }
        }
        // 通用关闭按钮文本（精确匹配关闭类关键词，避免"立即关闭下载"等诱导文案子串误匹配）
        val keywords = listOf("×", "关闭", "close", "跳过", "skip", "关闭广告", "跳过广告", "跳过视频")
        for (kw in keywords) {
            val node = findNodeByText(root, kw)
            if (node != null && !isTrapNode(node)) {
                Log.d(TAG, "findAdCloseButton: found by text='$kw'")
                return node
            }
        }
        // 文字没找到，尝试查找右上角区域的可点击小图标（游戏/广告的关闭按钮通常是右上角X图标）
        val closeIcon = findTopRightClickableIcon(root, isRight = true)
        if (closeIcon != null) {
            debugLog("findAdCloseButton: found top-right close icon")
            return closeIcon
        }
        return null
    }

    /**
     * 查找"下单得奖励"等浏览页面的左上角返回图标
     * - 淘宝搜索结果页面的返回按钮通常在左上角，是一个可点击的小图标
     * @return 返回按钮节点或null
     */
    fun findBackIcon(): AccessibilityNodeInfo? {
        val root = rootInActiveWindowSafe() ?: return null
        // 先找文字匹配
        val keywords = listOf("返回", "返回首页", "back")
        for (kw in keywords) {
            val node = findNodeByText(root, kw)
            if (node != null) {
                debugLog("findBackIcon: found by text='$kw'")
                return node
            }
        }
        // 文字没找到，查找左上角的可点击小图标
        val backIcon = findTopRightClickableIcon(root, isRight = false)
        if (backIcon != null) {
            debugLog("findBackIcon: found top-left back icon")
            return backIcon
        }
        return null
    }

    /**
     * 查找屏幕顶部角落区域的可点击小图标
     * @param isRight true=右上角（关闭按钮），false=左上角（返回按钮）
     */
    private fun findTopRightClickableIcon(root: AccessibilityNodeInfo, isRight: Boolean): AccessibilityNodeInfo? {
        val screenW = 1200  // 屏幕1080dp * density
        val screenH = 2664
        // 搜索区域：顶部1/8，左/右侧1/4
        val regionLeft = if (isRight) (screenW * 0.7).toInt() else 0
        val regionRight = if (isRight) screenW else (screenW * 0.3).toInt()
        val regionTop = 0
        val regionBottom = (screenH * 0.12).toInt()  // 顶部12%区域

        var bestNode: AccessibilityNodeInfo? = null
        var bestArea = Int.MAX_VALUE  // 优先找最小的可点击节点（图标通常很小）

        fun searchNode(node: AccessibilityNodeInfo) {
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            // 检查是否在目标区域
            if (rect.left >= regionLeft && rect.right <= regionRight &&
                rect.top >= regionTop && rect.bottom <= regionBottom) {
                // 检查是否是可点击的小图标（面积 < 300x300）
                val w = rect.width()
                val h = rect.height()
                if (node.isClickable && w in 30..300 && h in 30..300) {
                    val area = w * h
                    if (area < bestArea) {
                        bestArea = area
                        bestNode = node
                    }
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                searchNode(child)
            }
        }

        searchNode(root)
        if (bestNode != null) {
            val rect = android.graphics.Rect()
            bestNode!!.getBoundsInScreen(rect)
            debugLog("findTopCornerIcon: found ${if (isRight) "right" else "left"} icon at ${rect.toShortString()}")
        }
        return bestNode
    }

    /**
     * 在当前页面查找包含指定文本的最小可点击节点（供 AutomationController 调用）
     * - 优先找精确匹配 content-desc 的可点击节点
     * - 如果没有精确匹配，找包含文本的最小可点击节点
     */
    fun findSmallestClickableNodeByText(text: String): AccessibilityNodeInfo? {
        val root = getRootInFarmApp() ?: return null
        var bestNode: AccessibilityNodeInfo? = null
        var bestArea = Int.MAX_VALUE

        fun searchNode(node: AccessibilityNodeInfo) {
            val nodeText = node.text?.toString().orEmpty()
            val nodeDesc = node.contentDescription?.toString().orEmpty()
            val matched = nodeText.contains(text) || nodeDesc.contains(text)
            if (matched && node.isClickable) {
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                val area = rect.width() * rect.height()
                // 只考虑合理大小的节点（面积在 1000 ~ 500000 之间）
                if (area in 1000..500000 && area < bestArea) {
                    bestArea = area
                    bestNode = node
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                searchNode(child)
            }
        }

        searchNode(root)
        return bestNode
    }

    /**
     * 检测当前页面是否是"下单得肥料"付费搜索推荐页面
     * - 这个页面有搜索框和商品推荐列表，不是芭芭农场种植页
     * - 需要从这个页面退出回到种植页
     * - 注意："搜索后浏览立得奖励"、"浏览宝贝得奖励"是免费浏览任务（需点击历史搜索词进入），
     *   不属于付费搜索推荐页，不应在此误判（由 isSearchBrowseTaskPage 检测）
     * @return true 表示是付费搜索推荐页面
     */
    fun isSearchRecommendPage(): Boolean {
        val root = getRootInFarmApp() ?: return false
        val allText = collectAllText(root)
        // 只有在没有种植页核心元素（"集肥料"、"施肥"等）时才判断为搜索推荐页
        // 避免任务列表弹窗中的任务描述被误判
        val hasFarmCore = allText.any { text ->
            text.contains("集肥料") || text.contains("施肥") ||
                text.contains("换种") || text.contains("好友林") ||
                text.contains("一起种")
        }
        if (hasFarmCore) return false
        val searchPageKeywords = listOf(
            "下单得肥料", "当前页下单", "搜索有惊喜", "搜一搜浏览",
            "搜索有福利"
        )
        val matchCount = searchPageKeywords.count { text -> allText.any { it.contains(text) } }
        val hasSearchOnlyKeyword = allText.any { it.contains("搜一搜") || it.contains("搜索有惊喜") }
        val isSearchPage = matchCount >= 2 || (matchCount >= 1 && hasSearchOnlyKeyword)
        if (isSearchPage) {
            debugLog("isSearchRecommendPage: YES (matchCount=$matchCount)")
        }
        return isSearchPage
    }

    /**
     * 检测当前页面是否是"搜索后浏览立得奖励"任务页面（免费浏览任务）
     *
     * 用户需求：任务窗口右上角提示"搜索后浏览立得奖励"，需要点击一个历史搜索内容
     * （如"纽安思甘露糖"），进入真正的任务页面（显示"滑动浏览得肥料"），上下滑动
     * 直到出现"任务完成"，返回两次回到芭芭农场页面。
     *
     * 检测条件：页面文本包含"搜索后浏览立得奖励"或"浏览宝贝得奖励"
     *
     * @return true 表示是搜索浏览任务页面
     */
    fun isSearchBrowseTaskPage(): Boolean {
        val root = getRootInFarmApp() ?: return false
        val allText = collectAllText(root)
        val isSearchBrowse = allText.any { text ->
            text.contains("搜索后浏览立得奖励") || text.contains("浏览宝贝得奖励")
        }
        if (isSearchBrowse) {
            debugLog("isSearchBrowseTaskPage: YES, search browse task page detected")
        }
        return isSearchBrowse
    }

    /**
     * 在"搜索后浏览立得奖励"任务页面查找历史搜索词并返回可点击节点
     *
     * 用户需求：点击历史搜索内容（如"纽安思甘露糖"）进入真正的任务页面。
     * 历史搜索词通常在搜索框下方的列表中，是可点击的文本节点。
     *
     * 策略：排除搜索框占位符（"搜索"/"搜一搜"）和任务提示文案（"搜索后浏览立得奖励"等），
     * 找到第一个非占位符的可点击文本节点作为历史搜索词。
     *
     * @return 历史搜索词节点，null 表示未找到
     */
    fun findHistorySearchKeyword(): AccessibilityNodeInfo? {
        val root = getRootInFarmApp() ?: return null
        // 排除的文本：搜索框占位符、任务提示文案、按钮文案等
        val excludeTexts = listOf(
            "搜索", "搜一搜", "搜索后浏览立得奖励", "浏览宝贝得奖励",
            "取消", "返回", "关闭", "×", "确定", "历史搜索",
            "搜索发现", "搜索历史", "大家都在搜", "热门搜索",
            "换一换", "更多", "展开", "收起"
        )
        // 查找所有可点击节点，排除占位符后返回第一个
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectClickableTextNodes(root, candidates, maxDepth = 10)
        for (node in candidates) {
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val nodeText = if (text.isNotEmpty()) text else desc
            if (nodeText.isEmpty()) continue
            // 排除占位符和任务提示文案
            val isExcluded = excludeTexts.any { nodeText.contains(it) }
            if (isExcluded) continue
            // 排除纯数字或过短的文本
            if (nodeText.length < 2) continue
            // 排除过长的文案（历史搜索词通常较短）
            if (nodeText.length > 20) continue
            debugLog("findHistorySearchKeyword: found candidate '$nodeText'")
            return node
        }
        debugLog("findHistorySearchKeyword: no history search keyword found")
        return null
    }

    /** 递归收集所有可点击的文本节点 */
    private fun collectClickableTextNodes(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>,
        maxDepth: Int
    ) {
        if (maxDepth <= 0) return
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        if ((text.isNotEmpty() || desc.isNotEmpty()) && node.isClickable) {
            out.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectClickableTextNodes(child, out, maxDepth - 1)
        }
    }

    /**
     * 检测是否在异常页面（交易/付款/商品详情等页面）
     *
     * 禁止交易获取肥料：所有交易相关页面都视为异常，遇到时立即退出。
     *
     * 异常页面包括：
     * - 商品详情页（ttdetailactivity）— 有"加入购物车"+"立即购买"按钮，交易前置页面
     * - 订单确认页（orderconfirm）— 交易流程页面
     * - 收银台/支付页（cashdesk/cashier）— 实际付款页面
     * - 交易确认页（tradeconfirm）— 实际提交付款
     * - 结算页（checkout）— 实际付款
     *
     * 检测方式：activity 名 + 页面内容双重检测
     * - activity 名检测覆盖已知交易页面
     * - 内容级检测（[isProductDetailPage]）兜底捕获 H5/WebView 内的交易页
     *
     * 用户要求：禁止交易获取肥料，所有交易相关页面都不进入，按返回退出。
     */
    fun isOnAbnormalPage(): Boolean {
        val activity = currentActivityName?.lowercase().orEmpty()
        // 1. activity 级检测：交易相关页面
        val abnormalKeywords = listOf(
            "cashdesk",          // 支付宝收银台（com.taobao.tao.alipay.cashdesk.cashdeskactivity）
            "cashier",           // 收银台/支付页
            "tradeconfirm",      // 交易确认页（实际提交付款）
            "checkout",          // 结算页（实际付款）
            "ttdetailactivity",  // 淘宝商品详情页（交易前置页面）
            "orderconfirm"       // 订单确认页（交易流程页面）
        )
        if (activity.isNotEmpty() && abnormalKeywords.any { activity.contains(it) }) {
            debugLog("isOnAbnormalPage: YES, activity=$activity")
            return true
        }
        // 2. 内容级检测：商品详情页（有"加入购物车"+"立即购买"按钮）
        // 兜底捕获 H5/WebView 内的交易页（activity 名可能不暴露）
        if (isProductDetailPage()) {
            debugLog("isOnAbnormalPage: YES, product detail page detected by content")
            return true
        }
        return false
    }

    /**
     * 检测当前页面是否为充值/付费页面（内容级检测，用于游戏内充值引导页）
     * - 检测页面文本中的充值/购买/付费关键词
     * @return true 表示当前是充值页面
     */
    fun isRechargePage(): Boolean {
        val root = rootInActiveWindowSafe() ?: return false
        val allText = collectAllText(root)
        val isRecharge = allText.any { text ->
            text.contains("充值") || text.contains("立即充值") ||
                text.contains("马上充值") || text.contains("去充值") ||
                text.contains("开通会员") || text.contains("续费") ||
                text.contains("立即购买") || text.contains("确认支付") ||
                text.contains("去支付") || text.contains("立即支付") ||
                text.contains("购买道具") || text.contains("购买金币") ||
                text.contains("充值金币") || text.contains("充值钻石")
        }
        if (isRecharge) {
            debugLog("isRechargePage: YES, sample=${allText.take(5)}")
        }
        return isRecharge
    }

    /**
     * 在充值/付费页面上查找并点击关闭按钮
     * - 优先找：×图标/关闭/暂不充值/取消/不买了/再想想/残忍拒绝 等关闭类按钮
     * @return true 表示成功点击了关闭按钮
     */
    fun clickCloseOnRechargePage(): Boolean {
        val root = rootInActiveWindowSafe() ?: return false
        // 关闭类按钮关键词（按优先级排序）
        val closeKeywords = listOf(
            "暂不充值", "暂不购买", "暂不支付", "暂不开通",
            "不买了", "再想想", "残忍拒绝", "残忍离别",
            "取消", "关闭", "以后再说", "下次再说",
            "不了", "拒绝", "返回"
        )
        for (kw in closeKeywords) {
            val node = findNodeByText(root, kw)
            if (node != null) {
                debugLog("clickCloseOnRechargePage: found close button by text='$kw', clicking")
                if (performClickSafe(node)) return true
            }
        }
        // fallback：尝试找 content-description 含"关闭"的可点击节点（×图标通常 desc="关闭"）
        val closeByDesc = findNodeByText(root, "关闭")
        if (closeByDesc != null) {
            debugLog("clickCloseOnRechargePage: found close by desc='关闭', clicking")
            if (performClickSafe(closeByDesc)) return true
        }
        debugLog("clickCloseOnRechargePage: no close button found")
        return false
    }

    // ============== 广告陷阱防护（防诱导点击/防落地页陷阱） ==============

    /**
     * 检测当前页面是否是广告主落地页（下载/安装/应用商店诱导页）
     *
     * 广告设计者意图：诱导用户点击进入广告主落地页（下载/购买/注册），
     * 获取广告转化收益。这类页面通常同时有多个"立即下载/立即安装/立即体验/查看详情"
     * 等转化按钮，但缺乏农场页核心元素（集肥料/施肥/任务列表/任务完成）。
     *
     * 误入此类页面时，应立即按返回退出，避免被诱导点击安装/购买。
     *
     * 判定条件：
     * - 页面文本含 ≥2 个广告主转化按钮黑名单关键词
     * - 且无农场页核心文案（避免误判农场任务列表为落地页）
     *
     * @return true 表示当前在广告主落地页
     */
    fun isAdLandingPage(): Boolean {
        val root = rootInActiveWindowSafe() ?: return false
        val allText = collectAllText(root)
        // 农场页核心文案：若存在则肯定不是落地页
        // （注意："任务完成"也在农场核心文案里，避免误判完成页）
        val hasFarmCore = allText.any { text ->
            text.contains("集肥料") || text.contains("施肥") ||
                text.contains("换种") || text.contains("芭芭农场") ||
                text.contains("任务列表") || text.contains("任务完成") ||
                text.contains("已领取全部奖励")
        }
        if (hasFarmCore) return false
        // 广告正常播放页特征：含倒计时/跳过提示（如"剩余15秒"/"跳过广告"），不算落地页
        // （广告播放页通常也有"立即下载"按钮，但页面主体是视频而非落地页布局）
        val isAdPlayingPage = allText.any { text ->
            text.contains("跳过广告") || text.contains("跳过视频") ||
                text.contains("关闭广告") ||
                // 倒计时模式：XX秒后可关闭 / 剩余XX秒
                text.matches(Regex(".*\\d+\\s*[秒s].*(可关闭|后可关闭|后跳过|后关闭).*")) ||
                text.matches(Regex(".*(剩余|还有)\\s*\\d+\\s*[秒s].*"))
        }
        if (isAdPlayingPage) return false
        // 统计广告主转化按钮出现次数（来自平台配置的陷阱按钮黑名单）
        val trapTexts = currentPlatformConfig().adInstallButtonTexts
        val matchCount = allText.count { text ->
            trapTexts.any { trap -> text.contains(trap) }
        }
        // ≥2 个转化按钮 + 无农场核心 + 非广告播放页 = 广告主落地页
        val isLanding = matchCount >= 2
        if (isLanding) {
            debugLog("isAdLandingPage: YES, matchCount=$matchCount (advertiser landing page)")
        }
        return isLanding
    }

    /**
     * 查找广告主诱导按钮（立即下载/立即安装/立即体验/查看详情/去购买 等）
     *
     * 用于：
     * - 检测诱导弹窗存在（返回非 null 表示有诱导按钮，需配合 closeAdInstallPopup 关闭）
     * - 配合 closeAdInstallPopup 优先点击关闭类按钮而非诱导按钮
     *
     * @return 第一个匹配的诱导按钮节点，或 null
     */
    fun findAdInstallButton(): AccessibilityNodeInfo? {
        val root = rootInActiveWindowSafe() ?: return null
        val trapTexts = currentPlatformConfig().adInstallButtonTexts
        for (kw in trapTexts) {
            val node = findNodeByText(root, kw)
            if (node != null) {
                debugLog("findAdInstallButton: found trap button by text='$kw'")
                return node
            }
        }
        return null
    }

    /**
     * 关闭广告诱导弹窗（检测到立即下载等按钮时，优先点关闭/暂不/拒绝类按钮）
     *
     * 场景：浏览广告/游戏期间突然弹出"立即下载"诱导弹窗，遮盖页面或诱导跳转应用商店。
     * 策略：在存在诱导按钮的页面上，优先点击"暂不下载/关闭/拒绝"等关闭类按钮，
     *      绝不点击"立即下载/立即体验"等转化按钮。
     *
     * @return true 表示成功关闭了诱导弹窗
     */
    fun closeAdInstallPopup(): Boolean {
        val root = rootInActiveWindowSafe() ?: return false
        // 关闭类按钮关键词（按优先级排序，避免误点诱导按钮）
        // 优先更精确的"暂不X"组合，再到通用关闭类
        val closeKeywords = listOf(
            "暂不下载", "暂不安装", "暂不体验", "暂不试玩", "暂不购买", "暂不支付", "暂不开通",
            "不下载", "不安装", "不体验",
            "残忍拒绝", "残忍离别", "拒绝",
            "以后再说", "下次再说", "稍后再说",
            "取消", "关闭", "返回",
            "不了", "算了", "再想想"
        )
        for (kw in closeKeywords) {
            val node = findNodeByText(root, kw)
            if (node != null) {
                debugLog("closeAdInstallPopup: found close button by text='$kw', clicking")
                if (performClickSafe(node)) return true
            }
        }
        debugLog("closeAdInstallPopup: no close button found for install popup")
        return false
    }

    /**
     * 关闭广告主落地页（误入落地页时优先点右上角关闭，而非按返回）
     *
     * 设计理由：
     * - 落地页通常有"返回"诱导按钮（如"返回领奖励"等组合文案），按返回可能触发隐藏跳转
     * - 右上角"×"是落地页唯一安全的退出入口（应用商店规范要求）
     * - 找不到"×"时再 fallback 到物理返回键
     *
     * @return true 表示成功点击了关闭按钮或已按返回
     */
    fun closeAdLandingPage(): Boolean {
        val root = rootInActiveWindowSafe() ?: return false
        // 1. 优先找平台特有关闭按钮文本（关闭/跳过广告等）
        val platformCloseTexts = currentPlatformConfig().adCloseButtonTexts
        for (kw in platformCloseTexts) {
            val node = findNodeByText(root, kw)
            if (node != null) {
                debugLog("closeAdLandingPage: found platform close button by text='$kw'")
                if (performClickSafe(node)) return true
            }
        }
        // 2. 找通用关闭按钮文本（×/关闭/close/skip），排除诱导按钮
        //    （findAdCloseButton 已内置诱导黑名单过滤）
        val closeBtn = findAdCloseButton(platformCloseTexts)
        if (closeBtn != null) {
            debugLog("closeAdLandingPage: found close button via findAdCloseButton")
            if (performClickSafe(closeBtn)) return true
        }
        // 3. 兜底：按物理返回键
        debugLog("closeAdLandingPage: no close button found, pressing back as fallback")
        pressBack()
        return true
    }


    /**
     * 查找"放弃奖励离开"对话框按钮
     * @return 按钮节点或null
     */
    fun findAbandonRewardButton(): AccessibilityNodeInfo? {
        val root = rootInActiveWindowSafe() ?: return null
        val keywords = listOf("放弃奖励离开", "放弃奖励", "确定离开", "离开", "放弃")
        for (kw in keywords) {
            val node = findNodeByText(root, kw)
            if (node != null) {
                Log.d(TAG, "findAbandonRewardButton: found by text='$kw'")
                return node
            }
        }
        return null
    }

    /**
     * 查找"领取奖励"按钮（广告结束后）
     * - 不包含"关闭"（避免误关闭任务列表）
     * - 排除广告主诱导按钮（"领取优惠"/"领取福利"/"立即领取福利"会被"领取"子串匹配，
     *   点击后跳转交易页或下载广告主 App，违反禁止交易原则）
     * @return 按钮节点或null
     */
    fun findClaimRewardButton(): AccessibilityNodeInfo? {
        val root = rootInActiveWindowSafe() ?: return null
        val trapTexts = currentPlatformConfig().adInstallButtonTexts
        val keywords = listOf("领取奖励", "领取", "确定", "知道了")
        for (kw in keywords) {
            val node = findNodeByText(root, kw)
            if (node != null) {
                val text = node.text?.toString().orEmpty()
                val desc = node.contentDescription?.toString().orEmpty()
                // 排除广告主诱导按钮（如"领取优惠"/"领取福利"/"立即领取福利"会被"领取"子串匹配命中）
                if (trapTexts.any { trap -> (text + desc).contains(trap) }) {
                    debugLog("findClaimRewardButton: skip trap button text='$text' desc='$desc' (matched kw='$kw')")
                    continue
                }
                Log.d(TAG, "findClaimRewardButton: found by text='$kw'")
                return node
            }
        }
        return null
    }

    /**
     * 精确查找确认领取按钮（用于直接领取肥料弹窗）
     * - 仅匹配"领取奖励"、"领取"、"确定"，不匹配"关闭"
     * - 排除包含"施肥"的节点
     * - 排除广告主诱导按钮（"领取优惠"/"领取福利"/"立即领取福利"等）
     * @return 按钮节点或null
     */
    fun findClaimRewardButtonExact(): AccessibilityNodeInfo? {
        val root = rootInActiveWindowSafe() ?: return null
        val trapTexts = currentPlatformConfig().adInstallButtonTexts
        // "立即领取"放最前：弹窗里的确认按钮文字通常是"立即领取"，优先精确匹配
        // （"领取"为子串匹配，会命中"立即领取"，但放前面更明确，避免先命中无关的"领取"文案）
        val keywords = listOf("立即领取", "领取奖励", "领取", "确定", "知道了")
        for (kw in keywords) {
            val node = findNodeByText(root, kw)
            if (node != null) {
                val text = node.text?.toString().orEmpty()
                val desc = node.contentDescription?.toString().orEmpty()
                // 排除包含"施肥"的节点
                if ((text + desc).contains("施肥")) continue
                // 排除广告主诱导按钮
                if (trapTexts.any { trap -> (text + desc).contains(trap) }) {
                    debugLog("findClaimRewardButtonExact: skip trap button text='$text' desc='$desc'")
                    continue
                }
                Log.d(TAG, "findClaimRewardButtonExact: found by text='$kw'")
                return node
            }
        }
        return null
    }

    /**
     * 判断是否显示"任务完成"页面（得到肥料后的完成页）
     * - 检测页面是否包含"任务完成"、"已完成"、"全部完成"、"已领取全部奖励"等关键词
     * - 滑动浏览任务中，出现这些标志表示可以退出浏览页返回主界面
     *
     * 用户场景：浏览任务页面提示"每浏览x秒获得一次奖励"，等这个文字变成
     * "已领取全部奖励"后表示任务完成，应返回芭芭农场页面。
     *
     * @return true 表示是任务完成页面
     */
    fun isTaskCompletePage(): Boolean {
        val root = getRootInFarmApp() ?: return false
        val allText = collectAllText(root)
        // 收紧完成关键词：移除"恭喜获得"、"获得肥料"等过宽关键词
        // 原因：浏览任务进行中页面常显示"已获得肥料 xxx"（已领取的部分奖励），
        // 会被误判为任务完成而提前退出。只有明确的完成标志才算完成。
        val isComplete = allText.any { text ->
            text.contains("任务完成") || text.contains("已完成") ||
                text.contains("全部完成") || text.contains("已完成浏览") ||
                text.contains("已领取全部奖励") || text.contains("全部奖励已领取") ||
                text.contains("奖励已领取")
        }
        if (!isComplete) return false
        // 上下文校验：若页面同时是广告主落地页（含多个诱导按钮且无农场核心），
        // 说明"任务完成"文字是广告伪装的诱导文案，不应识别为完成页
        // 注意：isAdLandingPage 用 rootInActiveWindowSafe，可能取到不同 root，
        // 但落地页伪装通常在同一窗口内，故安全
        if (isAdLandingPage()) {
            debugLog("isTaskCompletePage: NO (text matched but isAdLandingPage=true, suspected ad bait)")
            return false
        }
        debugLog("isTaskCompletePage: YES, sample=${allText.take(5)}")
        return true
    }

    /**
     * 严格版任务完成页检测（含农场上下文 + 肥料到账证据）
     *
     * 用于关键决策点（如：决定回农场/计数肥料），避免被诱导文案欺骗。
     * 相比 [isTaskCompletePage]，额外要求：
     * - 页面必须有农场/任务上下文文案（集肥料/施肥/任务/已领取/已发放 等）
     * - 排除广告主落地页（已包含在 isTaskCompletePage 内）
     *
     * @return true 表示是真实任务完成页（不是广告伪装）
     */
    fun isRealTaskCompletePage(): Boolean {
        if (!isTaskCompletePage()) return false
        val root = getRootInFarmApp() ?: return false
        val allText = collectAllText(root)
        // 上下文校验：必须有农场/任务相关文案，且不能仅含纯诱导文案
        val hasFarmOrTaskContext = allText.any { text ->
            text.contains("肥料") || text.contains("芭芭农场") ||
                text.contains("任务") || text.contains("集肥料") ||
                text.contains("施肥") || text.contains("已发放") ||
                text.contains("已领取") || text.contains("奖励")
        }
        if (!hasFarmOrTaskContext) {
            debugLog("isRealTaskCompletePage: NO (no farm/task context)")
            return false
        }
        debugLog("isRealTaskCompletePage: YES (farm context verified)")
        return true
    }

    /**
     * 检测是否是"肥料已发放/已获得肥料"奖励到账提示页
     *
     * 用户场景：
     * - 广告观看结束后，页面弹出"肥料已发放"提示，表示奖励已到账，应立即回到芭芭农场主页
     * - 浏览任务完成后，页面显示"已获得肥料"，表示任务完成应退出
     *
     * 注意：与 isTaskCompletePage 的区别
     * - isTaskCompletePage 故意排除了"获得肥料"关键词（进行中页面也会显示"已获得肥料 xxx"）
     * - 本方法检测"已获得肥料"（纯粹的完成提示，无数字后缀）
     * - 若文案是"已获得肥料 500"等带数字的进行中状态，不会匹配（contains 只匹配子串，
     *   但 "已获得肥料" 是 "已获得肥料 500" 的子串，会匹配 → 需调用方结合上下文判断）
     *
     * 识别文本示例：
     * - "肥料已发放" / "肥料已发放到账户" / "肥料已发放成功"
     * - "奖励已发放" / "奖励已到账"
     * - "已获得肥料"（浏览任务完成提示）
     *
     * @return true 表示当前页面是肥料到账提示页
     */
    fun isFertilizerGrantedPage(): Boolean {
        val root = rootInActiveWindowSafe() ?: return false
        val allText = collectAllText(root)
        val granted = allText.any { text ->
            // "肥料已发放"/"奖励已发放"/"奖励已到账"/"肥料已到账" 是明确的到账提示，直接匹配
            text.contains("肥料已发放") || text.contains("奖励已发放") ||
                text.contains("奖励已到账") || text.contains("肥料已到账") ||
                // "已获得肥料" 需精确匹配：排除"已获得肥料 500"等带数字的进行中状态
                // 只匹配纯粹的"已获得肥料"（任务完成提示）
                (text.contains("已获得肥料") && !text.matches(Regex(".*已获得肥料\\s*\\d+.*")))
        }
        if (granted) {
            debugLog("isFertilizerGrantedPage: YES, sample=${allText.take(5)}")
        }
        return granted
    }

    /**
     * 检测浏览任务页面是否有"每浏览x秒获得一次奖励"进度提示
     *
     * 用户场景：浏览任务页面会显示"每浏览15秒获得一次奖励"等进度提示，
     * 表示任务还在进行中，必须继续滑动直到该提示变成"已领取全部奖励"才能退出。
     *
     * 识别文本示例：
     * - "每浏览15秒获得一次奖励" / "每浏览15s获得一次奖励"
     * - "每浏览15秒得奖励" / "每浏览15秒可领取"
     * - "浏览15秒得一次奖励"
     *
     * 注意：仅检测是否在进行中，不解析秒数（用 isTaskCompletePage 检测是否完成）
     *
     * @return true 表示检测到"每浏览x秒获得一次奖励"进度提示（任务进行中）
     */
    fun hasBrowseRewardProgressHint(): Boolean {
        val root = getRootInFarmApp() ?: return false
        val allText = collectAllText(root)
        // 匹配"每浏览15秒获得一次奖励"、"每浏览15秒得奖励"、"每浏览15秒可领取"等
        val progressPattern = Regex("每浏览\\s*\\d+\\s*[秒s]")
        for (text in allText) {
            if (progressPattern.containsMatchIn(text)) {
                throttledLog("browseProgress", "hasBrowseRewardProgressHint: found progress hint '$text'")
                return true
            }
        }
        return false
    }

    /**
     * 检测浏览任务页面是否有"浏览x分钟得xxx肥料"停留等待提示
     *
     * 用户场景：页面提示"浏览5分钟得600肥料"，这类任务需要停留等待（不滑动），
     * 直到页面出现"已完成"才退出。
     *
     * 与 [hasBrowseRewardProgressHint] 的区别：
     * - "每浏览x秒可得1次奖励"（秒级）→ 需要滑动获取
     * - "浏览x分钟得xxx肥料"（分钟级）→ 需要停留等待，不滑动
     *
     * 识别文本示例：
     * - "浏览5分钟得600肥料" / "浏览3分钟得300肥料"
     * - "浏览5分钟可领取" / "浏览5分钟即可领取"
     * - "浏览5min得600肥料"
     *
     * @return 需要等待的秒数（分钟×60），0 表示未找到
     */
    fun findBrowseDurationRewardHint(): Int {
        val root = getRootInFarmApp() ?: return 0
        val allText = collectAllText(root)
        // 匹配"浏览5分钟"、"浏览3min"、"浏览5分钟得600肥料"等
        val durationPattern = Regex("浏览\\s*(\\d+)\\s*(?:分钟|min|分)")
        for (text in allText) {
            val match = durationPattern.find(text)
            if (match != null) {
                val minutes = match.groupValues[1].toIntOrNull() ?: continue
                // 合理性检查：1~60分钟
                if (minutes in 1..60) {
                    val seconds = minutes * 60
                    debugLog("findBrowseDurationRewardHint: found '$text', need ${seconds}s (${minutes}min)")
                    return seconds
                }
            }
        }
        return 0
    }

    /**
     * 查找"返回首页"按钮（任务完成弹窗）
     * @return 按钮节点或null
     */
    fun findBackToHomeButton(): AccessibilityNodeInfo? {
        val root = getRootInFarmApp() ?: return null
        val keywords = listOf("返回首页", "回首页", "返回")
        for (kw in keywords) {
            val node = findNodeByText(root, kw)
            if (node != null) {
                Log.d(TAG, "findBackToHomeButton: found by text='$kw'")
                return node
            }
        }
        return null
    }

    /**
     * - 在农场主页上查找"施肥"文本节点
     * - 区别于"集肥料"入口按钮（打开任务列表），"施肥"是直接给作物施肥的操作
     * @return 按钮节点或null
     */
    fun findFertilizeButton(): AccessibilityNodeInfo? {
        val root = getRootInFarmApp() ?: return null
        val keywords = listOf("施肥")
        for (kw in keywords) {
            val node = findNodeByText(root, kw)
            if (node != null) {
                Log.d(TAG, "findFertilizeButton: found by text='$kw'")
                return node
            }
        }
        Log.d(TAG, "findFertilizeButton: not found")
        return null
    }

    /**
     * 读取农场主页施肥大按钮上的当前肥料数值
     *
     * 施肥按钮的 text/contentDescription 形如：
     * - "施肥，肥料39442，可施肥65次"
     * - "施肥，肥料 39442"
     * - "施肥，肥料39442克"
     *
     * H5 农场页中，可点击"施肥"按钮自身 text 可能只是"施肥"二字，肥料数字可能在：
     * - 父节点 contentDescription（H5 容器常把完整描述放在可点击元素上）
     * - 子节点 text（多个 span 拼出"施肥 8432 肥料"）
     * 因此本方法在自身匹配失败时，会向上遍历父节点、向下收集子树文本兜底提取，
     * 最后兜底遍历整页查找"肥料XXXX"。
     *
     * @return 当前肥料数值；找不到施肥按钮或解析失败返回 -1
     */
    fun findCurrentFertilizerAmount(): Int {
        val root = getRootInFarmApp() ?: rootInActiveWindowSafe() ?: run {
            Log.d(TAG, "findCurrentFertilizerAmount: root is null")
            return -1
        }
        val btn = findFertilizeButton()
        val amount = extractFertilizerAmount(root, btn)
        Log.d(TAG, "findCurrentFertilizerAmount: amount=$amount (btn found=${btn != null})")
        return amount
    }

    /**
     * 读取当前肥料数值并返回详细失败原因（用于自动化失败诊断）
     *
     * 失败原因分类：
     * - no_root：拿不到无障碍根节点（可能不在农场 App）
     * - no_fertilize_button：找不到"施肥"按钮（不在农场主页）
     * - parse_failed：找到按钮但自身/父节点/子树/整页均无"肥料XXXX"格式
     * - sample=...：失败时附带当前页面文本样本，便于判断当前在哪个页面
     *
     * @return Pair(amount, reason) amount=-1 时 reason 为失败原因；成功时 reason 为空
     */
    fun findCurrentFertilizerAmountWithReason(): Pair<Int, String> {
        val root = getRootInFarmApp() ?: rootInActiveWindowSafe()
        if (root == null) {
            return Pair(-1, "no_root (拿不到无障碍根节点，可能不在农场 App 或服务未就绪)")
        }
        val btn = findFertilizeButton()
        val amount = extractFertilizerAmount(root, btn)
        if (amount >= 0) {
            return Pair(amount, "")
        }
        // 失败诊断
        val sample = collectVisibleTextSample(root, 8)
        val reason = if (btn == null) {
            "no_fertilize_button (找不到施肥按钮，可能不在农场主页；页面样本: $sample)"
        } else {
            val t = btn.text?.toString().orEmpty()
            val d = btn.contentDescription?.toString().orEmpty()
            "parse_failed (找到施肥按钮但自身/父节点/子树/整页均无'肥料XXXX'格式；text='$t' desc='$d'；页面样本: $sample)"
        }
        return Pair(-1, reason)
    }

    /**
     * 核心提取逻辑：从施肥按钮节点树（自身/父节点/子树）提取肥料数值，失败时遍历整页兜底
     *
     * H5 农场页结构示例（施肥按钮自身可能不含数字）：
     * - 按钮 text="施肥"，父节点 desc="施肥，肥料8432，可施肥65次"
     * - 按钮 text="施肥"，子节点 span 拼出"施肥 8432 肥料"
     * - 按钮自身 desc="施肥，肥料8432"（最理想情况）
     *
     * @param root 页面根节点（兜底遍历用）
     * @param btn  施肥按钮节点（可为 null）
     * @return 肥料数值；-1 表示未提取到
     */
    private fun extractFertilizerAmount(root: AccessibilityNodeInfo, btn: AccessibilityNodeInfo?): Int {
        if (btn != null) {
            // 1. 自身 text/desc
            val selfAmt = matchFertilizerNumber(btn.text?.toString().orEmpty(), btn.contentDescription?.toString().orEmpty())
            if (selfAmt >= 0) return selfAmt
            // 2. 向上遍历父节点（最多 3 层），H5 容器常把完整 desc 放在可点击元素上
            var parent: AccessibilityNodeInfo? = btn.parent
            var depth = 0
            while (parent != null && depth < 3) {
                val pAmt = matchFertilizerNumber(parent.text?.toString().orEmpty(), parent.contentDescription?.toString().orEmpty())
                if (pAmt >= 0) return pAmt
                parent = parent.parent
                depth++
            }
            // 3. 向下收集子树文本拼接后匹配
            val sb = StringBuilder()
            collectTextInto(btn, sb, maxDepth = 3)
            val subAmt = matchFertilizerNumber(sb.toString(), "")
            if (subAmt >= 0) return subAmt
        }
        // 4. 兜底：遍历整页查找"肥料\s*\d{3,}"，取最大值（肥料总数通常最大）
        return extractFertilizerFromEntireTree(root)
    }

    /**
     * 从 text/desc 中匹配"肥料\s*(\d+)"，先匹配 text 再匹配 desc
     * @return 肥料数值；-1 表示未匹配
     */
    private fun matchFertilizerNumber(text: String, desc: String): Int {
        val regex = Regex("肥料\\s*(\\d+)")
        regex.find(text)?.let { m ->
            m.groupValues[1].toIntOrNull()?.let { if (it >= 0) return it }
        }
        regex.find(desc)?.let { m ->
            m.groupValues[1].toIntOrNull()?.let { if (it >= 0) return it }
        }
        return -1
    }

    /**
     * 收集节点及子节点的 text/contentDescription 拼接到 [sb]（用于兜底提取数字）
     */
    private fun collectTextInto(node: AccessibilityNodeInfo, sb: StringBuilder, maxDepth: Int, depth: Int = 0) {
        if (depth > maxDepth) return
        val t = node.text?.toString().orEmpty()
        if (t.isNotEmpty()) sb.append(t).append(' ')
        val d = node.contentDescription?.toString().orEmpty()
        if (d.isNotEmpty()) sb.append(d).append(' ')
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTextInto(child, sb, maxDepth, depth + 1)
        }
    }

    /**
     * 遍历整棵树查找"肥料\s*\d{3,}"，返回最大的匹配数字
     *
     * - 要求至少 3 位数字，过滤"还差3次领肥料"等小数字/无数字误匹配
     * - 排除含进度提示字样的节点（"还差"、"次领"、"%"等）
     * - 取最大值：施肥按钮 desc 形如"施肥，肥料8432，可施肥65次"，8432 最大
     *
     * @return 肥料数值；-1 表示未找到
     */
    private fun extractFertilizerFromEntireTree(root: AccessibilityNodeInfo): Int {
        val regex = Regex("肥料\\s*(\\d{3,})")
        val excludeKeywords = listOf("还差", "次领", "%")
        var bestAmount = -1
        fun walk(node: AccessibilityNodeInfo) {
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            for (combined in listOf(text, desc)) {
                if (!combined.contains("肥料")) continue
                if (excludeKeywords.any { combined.contains(it) }) continue
                val match = regex.find(combined) ?: continue
                val amount = match.groupValues[1].toIntOrNull() ?: continue
                if (amount > bestAmount) bestAmount = amount
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child)
            }
        }
        walk(root)
        return bestAmount
    }

    /**
     * 定位肥料文本节点在屏幕上的区域（用于 OCR 区域裁剪，避免全屏识别广告/商品图）
     *
     * 定位优先级（与 [extractFertilizerAmount] 一致）：
     * 1. 施肥按钮自身 text/desc 含"肥料XXXX" → 返回按钮 bounds
     * 2. 父节点（最多 3 层）含"肥料XXXX" → 返回父节点 bounds（H5 常把完整 desc 放在容器上）
     * 3. 子树文本含"肥料XXXX" → 返回按钮 bounds（数字在子节点，但区域用按钮范围近似）
     * 4. 整页遍历找含"肥料\s*\d{3,}"的节点 → 返回该节点 bounds
     * 5. 都失败 → 返回施肥按钮 bounds（数字可能在按钮附近，OCR 裁上下扩展区域）
     *
     * WebView 坐标修正：
     * - H5 农场页 getBoundsInScreen 可能返回内容坐标（top>bottom 或 width<=0）
     * - 异常时 fallback 到 getBoundsInWindow，再异常返回 null（调用方走全屏 OCR）
     *
     * @return 肥料区域 Rect（屏幕坐标）；null 表示无法定位，调用方应 fallback 全屏 OCR
     */
    fun findFertilizerNodeBounds(): android.graphics.Rect? {
        val root = getRootInFarmApp() ?: rootInActiveWindowSafe() ?: return null
        val btn = findFertilizeButton()
        val regex = Regex("肥料\\s*\\d{3,}")
        val excludeKeywords = listOf("还差", "次领", "%")

        // 1. 施肥按钮自身
        if (btn != null) {
            val selfText = btn.text?.toString().orEmpty()
            val selfDesc = btn.contentDescription?.toString().orEmpty()
            if (regex.containsMatchIn(selfText) || regex.containsMatchIn(selfDesc)) {
                return nodeBoundsSafe(btn)
            }
            // 2. 父节点（最多 3 层）
            var parent: AccessibilityNodeInfo? = btn.parent
            var depth = 0
            while (parent != null && depth < 3) {
                val pText = parent.text?.toString().orEmpty()
                val pDesc = parent.contentDescription?.toString().orEmpty()
                if (regex.containsMatchIn(pText) || regex.containsMatchIn(pDesc)) {
                    return nodeBoundsSafe(parent)
                }
                parent = parent.parent
                depth++
            }
            // 3. 子树文本含"肥料XXXX" → 用按钮 bounds 近似
            val sb = StringBuilder()
            collectTextInto(btn, sb, maxDepth = 3)
            if (regex.containsMatchIn(sb.toString())) {
                return nodeBoundsSafe(btn)
            }
        }

        // 4. 整页遍历找含"肥料\s*\d{3,}"的节点
        var foundNode: AccessibilityNodeInfo? = null
        fun walk(node: AccessibilityNodeInfo) {
            if (foundNode != null) return
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            for (combined in listOf(text, desc)) {
                if (!combined.contains("肥料")) continue
                if (excludeKeywords.any { combined.contains(it) }) continue
                if (regex.containsMatchIn(combined)) {
                    foundNode = node
                    return
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child)
                if (foundNode != null) return
            }
        }
        walk(root)
        if (foundNode != null) {
            return nodeBoundsSafe(foundNode!!)
        }

        // 5. fallback：返回施肥按钮 bounds（数字可能在按钮附近，OCR 上下扩展）
        return btn?.let { nodeBoundsSafe(it) }
    }

    /**
     * 安全获取节点屏幕坐标，处理 WebView 坐标异常
     *
     * WebView 坐标问题（参考 dispatchGestureClickWithWebViewFix）：
     * - getBoundsInScreen 在 H5 页可能返回内容坐标（top>bottom 或 width<=0）
     * - 异常时 fallback 到 getBoundsInWindow
     * - 仍异常返回 null（调用方走全屏 OCR）
     *
     * @return 节点屏幕坐标 Rect；null 表示坐标无效
     */
    private fun nodeBoundsSafe(node: AccessibilityNodeInfo): android.graphics.Rect? {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        // 校验坐标有效性（WebView 可能返回内容坐标导致 top>bottom 或宽高<=0）
        if (rect.width() > 0 && rect.height() > 0 && rect.top <= rect.bottom) {
            return rect
        }
        // fallback: getBoundsInWindow
        val winRect = android.graphics.Rect()
        node.getBoundsInWindow(winRect)
        if (winRect.width() > 0 && winRect.height() > 0 && winRect.top <= winRect.bottom) {
            return winRect
        }
        return null
    }

    /**
     * 截图能力是否已确认不可用（避免重复抛异常刷屏）。
     * - takeScreenshot 抛出 "Services don't have the capability" 时置 true
     * - 修复方式：用户需在系统设置中关闭再重新开启无障碍服务（config 变更后能力缓存不会自动刷新）
     */
    @Volatile
    private var screenshotCapabilityDisabled: Boolean = false

    /**
     * 截取当前屏幕并返回 software Bitmap（ARGB_8888），供 OCR 等需要 software bitmap 的场景使用
     *
     * - API < 30 不支持 takeScreenshot，返回 null
     * - 截图能力不可用时返回 null（见 [screenshotCapabilityDisabled]）
     * - 同步等待最多 5s，在后台线程调用（不可在主线程）
     * - 调用方负责 [Bitmap.recycle]
     *
     * @return 屏幕 software bitmap，失败返回 null
     */
    fun takeScreenshotBitmap(): android.graphics.Bitmap? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            debugLog("takeScreenshotBitmap: API < 30, not supported")
            return null
        }
        if (screenshotCapabilityDisabled) {
            debugLog("takeScreenshotBitmap: skipped (capability disabled, re-enable accessibility service to retry)")
            return null
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        var resultSwBitmap: android.graphics.Bitmap? = null
        try {
            // takeScreenshot(displayId, executor, callback)
            // displayId 必须是 Display.DEFAULT_DISPLAY(0)，不是 SDK_INT
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                java.util.concurrent.Executor { it.run() },
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        try {
                            val hardwareBuffer = result.hardwareBuffer
                            val hwBitmap = android.graphics.Bitmap.wrapHardwareBuffer(hardwareBuffer, result.colorSpace)
                            hardwareBuffer.close()
                            if (hwBitmap == null) {
                                debugLog("takeScreenshotBitmap: hwBitmap null")
                                return
                            }
                            // hardware bitmap 不能用于部分操作，copy 成 software bitmap
                            resultSwBitmap = try {
                                hwBitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                            } finally {
                                hwBitmap.recycle()
                            }
                            if (resultSwBitmap == null) {
                                debugLog("takeScreenshotBitmap: copy to software bitmap failed")
                            }
                        } catch (e: Exception) {
                            debugLog("takeScreenshotBitmap: process failed: ${e.message}")
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        // ERROR_TAKE_SCREENSHOT_NO_CAPABILITY(3)：服务无截图能力
                        if (errorCode == 3) {
                            screenshotCapabilityDisabled = true
                            debugLog("takeScreenshotBitmap: NO_CAPABILITY(code=3) API=${android.os.Build.VERSION.SDK_INT} — 请在系统设置中关闭再重新开启无障碍服务")
                        } else {
                            debugLog("takeScreenshotBitmap: takeScreenshot failed, errorCode=$errorCode")
                        }
                        latch.countDown()
                    }
                }
            )
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            if (msg.contains("capability", ignoreCase = true)) {
                screenshotCapabilityDisabled = true
                debugLog("takeScreenshotBitmap: CAPABILITY_DISABLED API=${android.os.Build.VERSION.SDK_INT} — ${e.javaClass.simpleName}: $msg — 修复：系统设置→无障碍→bbncbot→关闭→重新开启")
            } else {
                debugLog("takeScreenshotBitmap: takeScreenshot threw ${e.javaClass.simpleName}: $msg")
            }
            latch.countDown()
        }
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        return resultSwBitmap
    }

    // 注：OCR 肥料识别已迁移到 com.bbncbot.ocr.OcrProvider（按 flavor 二选一注入）
    // - noOcr flavor：空实现返回 -1（调试包不带 ML Kit 模型，APK 体积小）
    // - full  flavor：ML Kit 中文识别真实实现（稳定版大包）

    /**
     * 采样当前页面可见文本（用于失败诊断，判断当前在哪个页面）
     * 只取前 [maxCount] 个非空文本节点，每个截断到 20 字
     */
    private fun collectVisibleTextSample(root: AccessibilityNodeInfo, maxCount: Int): String {
        val out = mutableListOf<String>()
        fun walk(node: AccessibilityNodeInfo) {
            if (out.size >= maxCount) return
            val t = node.text?.toString()?.trim().orEmpty()
            if (t.isNotEmpty()) out.add(t.take(20))
            val d = node.contentDescription?.toString()?.trim().orEmpty()
            if (d.isNotEmpty() && d != t) out.add(d.take(20))
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child)
            }
        }
        walk(root)
        return out.joinToString(" | ")
    }

    /**
     * 查找农场主页上所有直接可领取肥料的按钮
     * - 如"兔兔挖肥料，50肥料，可领取"、"4100，肥料，明日7点可领"
     * - 使用 currentPlatformConfig().directCollectTexts 匹配关键词
     * - 排除包含"施肥"的按钮（如"施肥，肥料39442，可施肥65次"不是领取按钮）
     * @return 按钮节点列表
     */
    fun findDirectCollectButtons(): List<AccessibilityNodeInfo> {
        val root = getRootInFarmApp() ?: return emptyList()
        val keywords = currentPlatformConfig().directCollectTexts
        val result = mutableListOf<AccessibilityNodeInfo>()
        val seen = HashSet<Int>()
        collectNodesByText(root, keywords, result, seen)
        // 过滤掉不可领取的按钮
        val filtered = result.filter { node ->
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val combined = text + desc
            // 排除包含"施肥"的按钮（施肥操作按钮不是直接领取按钮）
            !combined.contains("施肥") &&
            // 排除已领取/生产中的按钮
            !combined.contains("生产中") &&
            !combined.contains("明日") &&
            !combined.contains("已领取") &&
            // 排除"还差X次领肥"等锁定状态进度提示（达到阈值后会变成"立即领肥"）
            !combined.contains("还差")
        }
        Log.d(TAG, "findDirectCollectButtons: found ${result.size} raw, ${filtered.size} after filter")
        return filtered
    }

    /**
     * 判断当前是否在广告Activity
     * - 通用广告活动识别（适用于所有平台）
     */
    fun isAdActivity(): Boolean {
        val activity = currentActivityName?.lowercase().orEmpty()
        if (activity.isEmpty()) return false
        return AD_ACTIVITY_KEYWORDS.any { activity.contains(it) }
    }

    /**
     * 判断当前是否在非广告页面（如邀请链接、安装软件页面）
     * - 用户要求：只点击广告，邀请推广网页直接返回
     * - 用户要求：安装软件的广告也不做，不安装，直接退出
     * @return true表示在非广告页面，需要返回
     */
    fun isNonAdPage(): Boolean {
        if (isAdActivity() || isAdPlaying()) return false
        if (isOnFarmPage()) return false
        val pkg = getCurrentWindowPackage() ?: return false
        val pkgLower = pkg.lowercase()
        // 已知的非广告页面包名（邀请链接、应用商店等）
        val nonAdKeywords = listOf("market", "appstore", "appgallery", "installer", "packageinstaller")
        return nonAdKeywords.any { pkgLower.contains(it) }
    }

    /** 递归查找包含指定文本的节点（返回可点击的自身或父节点） */
    fun findNodeByText(root: AccessibilityNodeInfo, keyword: String): AccessibilityNodeInfo? {
        return findNodeByTextInternal(root, keyword)
    }

    /** 精确匹配 content-description 的节点（避免子串误匹配） */
    /** 查找第一个可编辑的 EditText 节点（用于搜索输入） */
    private fun findFirstEditText(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return findFirstEditTextInternal(root)
    }

    private fun findFirstEditTextInternal(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isEnabled) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFirstEditTextInternal(child)
            if (result != null) return result
        }
        return null
    }

    private fun findNodeByExactDesc(root: AccessibilityNodeInfo, exactDesc: String): AccessibilityNodeInfo? {
        return findNodeByExactDescInternal(root, exactDesc)
    }

    private fun findNodeByExactDescInternal(node: AccessibilityNodeInfo, exactDesc: String): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        if (desc == exactDesc && node.isClickable) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val matched = findNodeByExactDescInternal(child, exactDesc)
            if (matched != null) return matched
        }
        if (desc == exactDesc) {
            return findClickableSelfOrParentInternal(node)
        }
        return null
    }

    private fun findNodeByTextInternal(node: AccessibilityNodeInfo, keyword: String): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val selfMatched = text.contains(keyword, ignoreCase = true) ||
            desc.contains(keyword, ignoreCase = true)
        // 自身匹配且可点击，直接返回（最精确，常见于按钮场景）
        if (selfMatched && node.isClickable) {
            return node
        }
        // 递归子节点查找（子节点可能有更精确的可点击节点，例如外层容器含 desc 但不可点击，
        // 内层有相同 desc 且可点击的情况，如淘宝主页"芭芭农场"卡片入口）
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val matched = findNodeByTextInternal(child, keyword)
            if (matched != null) return matched
        }
        // 子节点没找到，但自身匹配，返回最近的可点击父节点（兜底）
        if (selfMatched) {
            return findClickableSelfOrParentInternal(node)
        }
        return null
    }

    /** 收集所有包含指定关键词的可点击节点 */
    private fun collectNodesByText(
        node: AccessibilityNodeInfo,
        keywords: List<String>,
        out: MutableList<AccessibilityNodeInfo>,
        seen: HashSet<Int>
    ) {
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        if (text.isNotEmpty() || desc.isNotEmpty()) {
            for (kw in keywords) {
                if (text.contains(kw, ignoreCase = true) ||
                    desc.contains(kw, ignoreCase = true)) {
                    val clickable = findClickableSelfOrParentInternal(node)
                    if (clickable != null) {
                        val hash = System.identityHashCode(clickable)
                        if (seen.add(hash)) {
                            out.add(clickable)
                        }
                    }
                    break
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNodesByText(child, keywords, out, seen)
        }
    }

    /** 向上查找最近的可点击父节点（含自身） */
    private fun findClickableSelfOrParentInternal(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 10) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return node
    }

    // ============== 自动导航到农场页 ==============

    private val navHandler = Handler(Looper.getMainLooper())

    /**
     * 用 deep link 重新打开芭芭农场页面（等同从桌面快捷方式进入）
     *
     * - 用于任务完成后回到农场主页，替代按返回键逐步退回
     * - 若当前平台未配置 [PlatformConfig.farmDeepLink]，返回 false，由调用方回退到导航逻辑
     * - 可指定 [targetPlatform]，用于跨平台切换场景；默认用当前检测到的平台
     *
     * @return true 表示已发起打开请求，false 表示无 deep link 或打开失败
     */
    fun reopenFarmByDeepLink(targetPlatform: Platform = currentPlatform): Boolean {
        // 1. 优先用桌面快捷方式（等同点击桌面"芭芭农场"组件，内部含 kill 老进程）
        if (com.bbncbot.util.FarmShortcutLauncher.startFarmShortcut(this, targetPlatform) { msg -> debugLog("FarmShortcut: $msg") }) {
            debugLog("reopenFarmByDeepLink: started shortcut for $targetPlatform")
            if (targetPlatform != currentPlatform) {
                currentPlatform = Platform.UNKNOWN
            }
            return true
        }
        // 2. kill 目标平台老进程后重开（deep link 直达 或 启动 App + 导航）
        // 任务完成时目标 App 在前台，killBackgroundProcesses 只能 kill 后台进程，
        // 所以先按 HOME 键把目标 App 退到后台，再 kill
        debugLog("reopenFarmByDeepLink: pressing HOME to background $targetPlatform before kill")
        performGlobalAction(GLOBAL_ACTION_HOME)
        // 等待 HOME 切换生效后再 kill（无延迟，killBackgroundProcesses 会异步生效）
        for (pkg in targetPlatform.config.packageNames) {
            forceKillApp(pkg, pressBackFirst = false)
        }
        // 2a. deep link 直达农场页
        val deepLink = targetPlatform.config.farmDeepLink
        if (!deepLink.isNullOrEmpty()) {
            return try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                debugLog("reopenFarmByDeepLink: opened $deepLink for $targetPlatform")
                if (targetPlatform != currentPlatform) {
                    currentPlatform = Platform.UNKNOWN
                }
                true
            } catch (e: ActivityNotFoundException) {
                debugLog("reopenFarmByDeepLink: no app handles $deepLink (${e.message})")
                false
            } catch (e: Exception) {
                debugLog("reopenFarmByDeepLink: failed $deepLink (${e.javaClass.simpleName}: ${e.message})")
                false
            }
        }
        // 2b. 无 deep link：kill 后启动 App 主 Activity，由无障碍服务自动导航到芭芭农场
        debugLog("reopenFarmByDeepLink: no deep link for $targetPlatform, killed + relaunch app, will navigate")
        val pkg = targetPlatform.config.packageNames.firstOrNull() ?: return false
        return try {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent == null) {
                debugLog("reopenFarmByDeepLink: no launch intent for $targetPlatform ($pkg)")
                return false
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            // 重置平台，等待新平台 H5 加载后被自动识别
            if (targetPlatform != currentPlatform) {
                currentPlatform = Platform.UNKNOWN
            }
            debugLog("reopenFarmByDeepLink: relaunched $targetPlatform ($pkg)")
            true
        } catch (e: Exception) {
            debugLog("reopenFarmByDeepLink: relaunch failed for $targetPlatform (${e.javaClass.simpleName}: ${e.message})")
            false
        }
    }

    /**
     * 自动导航到芭芭农场页面（淘宝/支付宝）
     * - 假设 App 已启动到首页，由 [MainActivity.openApp] 调用
     * - 淘宝/支付宝主页直接有"芭芭农场"标签，直接查找并点击，无需搜索
     * - 若平台尚未识别，自动重试最多 8 次（每秒一次）
     * @return true 表示已开始导航流程（含重试），false 表示重试耗尽仍未识别平台
     */
    fun navigateToFarm(retryCount: Int = 0): Boolean {
        val platform = currentPlatform
        // 如果自动化控制器正在运行且不在 NAVIGATING/SWITCHING_PLATFORM 状态，跳过导航（避免干扰）
        // 注：SWITCHING_PLATFORM 阶段需要调用 navigateToFarm 导航到目标/原平台芭芭农场
        val ctrlState = AutomationController.currentState
        if (ctrlState != com.bbncbot.automation.AutomationState.IDLE &&
            ctrlState != com.bbncbot.automation.AutomationState.NAVIGATING &&
            ctrlState != com.bbncbot.automation.AutomationState.SWITCHING_PLATFORM &&
            ctrlState != com.bbncbot.automation.AutomationState.STOPPING) {
            debugLog("navigateToFarm: automation running (state=$ctrlState), skip navigation")
            return false
        }
        if (platform == Platform.UNKNOWN) {
            if (retryCount < 8) {
                Log.i(TAG, "navigateToFarm: platform=$platform not ready, retry $retryCount in 1s")
                navHandler.postDelayed({
                    // 重试前再次检查自动化状态
                    val st = AutomationController.currentState
                    if (st != com.bbncbot.automation.AutomationState.IDLE &&
                        st != com.bbncbot.automation.AutomationState.NAVIGATING &&
                        st != com.bbncbot.automation.AutomationState.SWITCHING_PLATFORM &&
                        st != com.bbncbot.automation.AutomationState.STOPPING) {
                        debugLog("navigateToFarm retry: automation running (state=$st), abort retry")
                        return@postDelayed
                    }
                    navigateToFarm(retryCount + 1)
                }, 1000L)
                return true
            }
            Log.w(TAG, "navigateToFarm: platform=$platform not recognized after $retryCount retries, abort")
            return false
        }
        Log.i(TAG, "navigateToFarm: start, platform=$platform (after $retryCount retries)")
        // 清除可能残留的导航回调
        navHandler.removeCallbacksAndMessages(null)
        // 设置导航中标志，暂停 AutomationController 的自动操作避免干扰
        isNavigatingToFarm = true
        // 根据平台选择不同导航方式
        when (platform) {
            Platform.ALIPAY -> navHandler.postDelayed({ stepNavigateAlipayFarm() }, 1500L)
            Platform.TAOBAO -> {
                // 淘宝主页"芭芭农场"入口会进入搜索推荐页而不是直接进农场H5
                // 改用"我的淘宝"路径更可靠
                debugLog("navigateToFarm: taobao, using 我的淘宝 path")
                navHandler.postDelayed({ stepClickFarmTabByGesture(platform, 0) }, 1500L)
            }
            else -> navHandler.postDelayed({ stepClickFarmTab(platform) }, 1500L)
        }
        return true
    }

    /**
     * 导航中标志 - AutomationController 应跳过自动操作避免干扰
     */
    @Volatile
    var isNavigatingToFarm: Boolean = false
        private set

    /** 清除导航中标志（导航完成或失败后调用） */
    private fun clearNavigatingFlag() {
        if (isNavigatingToFarm) {
            isNavigatingToFarm = false
            Log.i(TAG, "navigateToFarm: navigating flag cleared")
        }
    }

    /** 取消所有导航回调（由 AutomationController 在启动自动化时调用，避免干扰） */
    fun cancelNavigation() {
        navHandler.removeCallbacksAndMessages(null)
        isNavigatingToFarm = false
        debugLog("cancelNavigation: all navigation callbacks cancelled")
    }

    /**
     * 启动指定平台的 App（跨平台切换用）
     *
     * - 用于在支付宝/淘宝/UC 之间切换以获取跨平台肥料奖励
     * - 启动后重置 currentPlatform 为 UNKNOWN，等待新平台被自动检测
     *
     * @param targetPlatform 目标平台
     * @return true 启动成功，false 失败
     */
    fun launchPlatformApp(targetPlatform: com.bbncbot.automation.Platform): Boolean {
        // 优先用 deep link 直达目标平台农场页（等同桌面快捷方式进入）
        if (reopenFarmByDeepLink(targetPlatform)) {
            debugLog("launchPlatformApp: opened $targetPlatform via deep link")
            return true
        }
        // 无 deep link 或失败：启动 App 主页，由无障碍服务导航
        val pkg = targetPlatform.config.packageNames.firstOrNull() ?: return false
        return try {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent == null) {
                debugLog("launchPlatformApp: no launch intent for $targetPlatform ($pkg)")
                return false
            }
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            // 重置平台，等待新平台被检测到
            currentPlatform = com.bbncbot.automation.Platform.UNKNOWN
            debugLog("launchPlatformApp: launched $targetPlatform ($pkg), platform reset to UNKNOWN")
            true
        } catch (e: Exception) {
            debugLog("launchPlatformApp: failed to launch $targetPlatform, ${e.message}")
            false
        }
    }

    /**
     * 强杀指定 App（广告深链跳转超时后调用）
     *
     * - 使用 [android.app.ActivityManager.killBackgroundProcesses] 结束目标 App 后台进程
     * - 默认先按返回键尝试退出目标 App（使其退到后台），再 kill，效果更好
     * - 若调用方已通过 [launchPlatformApp] 将农场 App 激活到前台（被拉起的 App 已被推到后台），
     *   应传入 pressBackFirst = false 跳过返回键，避免误伤已激活的农场 App
     * - 普通 App 无 FORCE_STOP_PACKAGES 权限，killBackgroundProcesses 是可用的最强手段
     *
     * @param pkg 目标 App 包名
     * @param pressBackFirst 是否先按返回键把目标 App 退到后台（默认 true）
     * @return true 已调用 kill，false 失败
     */
    fun forceKillApp(pkg: String, pressBackFirst: Boolean = true): Boolean {
        return try {
            debugLog("forceKillApp: killing $pkg (pressBackFirst=$pressBackFirst)")
            // 先按返回键尝试把目标 App 退到后台（若调用方已激活主界面则跳过）
            if (pressBackFirst) {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            // 调用 killBackgroundProcesses 结束后台进程
            val am = getSystemService(android.content.Context.ACTIVITY_SERVICE)
                as android.app.ActivityManager
            am.killBackgroundProcesses(pkg)
            debugLog("forceKillApp: killBackgroundProcesses($pkg) called")
            true
        } catch (e: Exception) {
            debugLog("forceKillApp: failed to kill $pkg, ${e.message}")
            false
        }
    }

    /**
     * 在主页上找"芭芭农场"标签并点击
     * - 带重试机制（最多 6 次，每 2 秒一次），等待主页加载完成
     * - 自动处理弹出的权限对话框（淘宝 PermissionActivity 等）
     */
    private fun stepClickFarmTab(platform: Platform, retry: Int = 0) {
        // 自动化正在运行且不在 NAVIGATING/SWITCHING_PLATFORM 状态时，停止导航重试（避免干扰任务列表等操作）
        // 注：SWITCHING_PLATFORM 阶段需要导航到目标/原平台芭芭农场，此处应放行
        val ctrlState = AutomationController.currentState
        if (ctrlState != com.bbncbot.automation.AutomationState.IDLE &&
            ctrlState != com.bbncbot.automation.AutomationState.NAVIGATING &&
            ctrlState != com.bbncbot.automation.AutomationState.SWITCHING_PLATFORM &&
            ctrlState != com.bbncbot.automation.AutomationState.STOPPING) {
            debugLog("navigate stepTab: automation running (state=$ctrlState), abort retry")
            clearNavigatingFlag()
            return
        }
        val root = rootInActiveWindowSafe()
        if (root == null) {
            if (retry < 6) {
                Log.w(TAG, "navigate stepTab: root is null, retry $retry in 2s")
                navHandler.postDelayed({ stepClickFarmTab(platform, retry + 1) }, 2000L)
                return
            }
            Log.w(TAG, "navigate stepTab: root is null after $retry retries, abort")
            clearNavigatingFlag()
            return
        }
        // 先处理可能弹出的权限对话框（淘宝 PermissionActivity 等）
        if (handlePermissionDialog(root)) {
            Log.i(TAG, "navigate stepTab: permission dialog handled, retry in 2s")
            navHandler.postDelayed({ stepClickFarmTab(platform, retry) }, 2000L)
            return
        }
        // 直接在主页上找"芭芭农场"标签
        // 优先精确匹配 content-desc="芭芭农场"（避免误匹配"芭芭农场机器人"等）
        // 然后 findNodeByText 兜底，但排除超大容器节点（如"芭芭农场-固搜-interact" bounds 占满全屏）
        var tab = findNodeByExactDesc(root, "芭芭农场")
        if (tab != null) {
            val rect = android.graphics.Rect()
            tab.getBoundsInScreen(rect)
            // 检查节点是否合理大小（排除占满全屏的容器，正常入口约 400x200）
            if (rect.height() > 600 || (rect.width() > 1000 && rect.height() > 400)) {
                debugLog("navigate stepTab: exactDesc node too large ${rect.toShortString()}, skipping")
                tab = null
            }
        }
        if (tab == null) {
            tab = findNodeByText(root, "芭芭农场")
            if (tab != null) {
                val rect = android.graphics.Rect()
                tab.getBoundsInScreen(rect)
                if (rect.height() > 600 || (rect.width() > 1000 && rect.height() > 400)) {
                    debugLog("navigate stepTab: text node too large ${rect.toShortString()}, skipping")
                    tab = null
                }
            }
        }
        if (tab != null) {
            Log.i(TAG, "navigate stepTab: click 芭芭农场 tab on home page, platform=$platform")
            debugLog("navigate stepTab: click 芭芭农场 tab via node, platform=$platform")
            performClickSafe(tab)
            Log.i(TAG, "navigateToFarm: done, platform=$platform, waiting 8s for farm page to load")
            navHandler.postDelayed({ clearNavigatingFlag() }, 8000L)
            return
        }
        // 芭芭农场标签未找到，可能不在淘宝首页（如在直播/视频页等）
        // 策略：先按返回键回到淘宝主页，再重试找芭芭农场
        if (retry < 8) {
            if (retry == 0 || retry == 2 || retry == 4) {
                // 按返回键回到淘宝主页
                debugLog("navigate stepTab: 芭芭农场 not found (retry=$retry), pressing back to go home")
                pressBack()
                navHandler.postDelayed({ stepClickFarmTab(platform, retry + 1) }, 3000L)
                return
            }
            Log.w(TAG, "navigate stepTab: 芭芭农场 tab not found, retry $retry in 2s")
            navHandler.postDelayed({ stepClickFarmTab(platform, retry + 1) }, 2000L)
            return
        }
        // 6次重试都失败，尝试从"我的淘宝"路径进入
        debugLog("navigate stepTab: 芭芭农场 tab not found after 6 retries, trying 我的淘宝 path")
        stepClickFarmTabByGesture(platform, 0)
    }

    /**
     * 支付宝专属导航：从支付宝首页进入芭芭农场
     *
     * 支付宝芭芭农场入口和淘宝不同：
     * - 首页可能有"芭芭农场"文字入口（在应用中心区域）
     * - 或者首页搜索框搜索"芭芭农场"
     * - 农场页面在 H5 WebView 中
     */
    private fun stepNavigateAlipayFarm(retry: Int = 0) {
        // 自动化正在运行且不在 NAVIGATING/SWITCHING_PLATFORM 状态时，停止导航
        // 注：SWITCHING_PLATFORM 阶段需要导航到目标/原平台芭芭农场，此处应放行
        val ctrlState = AutomationController.currentState
        if (ctrlState != com.bbncbot.automation.AutomationState.IDLE &&
            ctrlState != com.bbncbot.automation.AutomationState.NAVIGATING &&
            ctrlState != com.bbncbot.automation.AutomationState.SWITCHING_PLATFORM &&
            ctrlState != com.bbncbot.automation.AutomationState.STOPPING) {
            debugLog("navigateAlipay: automation running (state=$ctrlState), abort")
            clearNavigatingFlag()
            return
        }

        if (retry >= 8) {
            debugLog("navigateAlipay: max retries reached, abort")
            clearNavigatingFlag()
            return
        }

        val root = getRootInFarmApp() ?: rootInActiveWindowSafe()
        if (root == null) {
            debugLog("navigateAlipay: root is null, retry=$retry")
            navHandler.postDelayed({ stepNavigateAlipayFarm(retry + 1) }, 2000L)
            return
        }

        // 策略1：直接在支付宝首页找"芭芭农场"入口
        val farmEntry = findNodeByText(root, "芭芭农场")
        if (farmEntry != null) {
            val rect = android.graphics.Rect()
            farmEntry.getBoundsInScreen(rect)
            val entryDesc = farmEntry.contentDescription?.toString()?.trim().orEmpty()
            val entryText = farmEntry.text?.toString()?.trim().orEmpty()
            // 排除搜索框/搜索按钮：搜索框内部含"芭芭农场"占位文字（如"搜索 芭芭农场"）时，
            // findNodeByText 会把搜索框本身作为最近可点击父节点返回，导致误点搜索框
            val isSearchNode = entryDesc.contains("搜索") || entryText.contains("搜索")
            // 排除超大容器，且必须 bounds 合法（left<right, top<bottom，且在屏幕范围内）
            // 否则可能拿到 WebView 内的离屏节点（如 bounds=[4476,822][1200,1139]），点击无效
            val boundsValid = rect.width() > 0 && rect.height() > 0 &&
                rect.left < rect.right && rect.top < rect.bottom &&
                rect.left >= 0 && rect.top >= 0
            if (boundsValid && !isSearchNode && rect.height() < 600 && rect.width() < 1000) {
                debugLog("navigateAlipay: found 芭芭农场 entry at ${rect.toShortString()}, clicking")
                performClickSafe(farmEntry)
                navHandler.postDelayed({ clearNavigatingFlag() }, 8000L)
                return
            }
            debugLog("navigateAlipay: 芭芭农场 entry invalid (searchNode=$isSearchNode, bounds=${rect.toShortString()}), fallback to search")
        }

        // 策略2：点击首页搜索框，搜索"芭芭农场"
        val searchBtn = findNodeByText(root, "搜索")
        if (searchBtn != null) {
            debugLog("navigateAlipay: clicking search button")
            performClickSafe(searchBtn)
            // 等搜索框打开后输入"芭芭农场"（3 秒等待，搜索页加载较慢）
            navHandler.postDelayed(searchStep@{
                val searchRoot = getRootInFarmApp() ?: rootInActiveWindowSafe()
                if (searchRoot != null) {
                    val editNode = findFirstEditText(searchRoot)
                    if (editNode != null) {
                        val args = android.os.Bundle()
                        args.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "芭芭农场")
                        editNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                        debugLog("navigateAlipay: entered search text '芭芭农场'")
                        // 等搜索结果出现后点击"芭芭农场"
                        navHandler.postDelayed(resultStep@{
                            val resultRoot = getRootInFarmApp() ?: rootInActiveWindowSafe()
                            if (resultRoot != null) {
                                val result = findNodeByText(resultRoot, "芭芭农场")
                                if (result != null) {
                                    val rRect = android.graphics.Rect()
                                    result.getBoundsInScreen(rRect)
                                    val rValid = rRect.width() > 0 && rRect.height() > 0 &&
                                        rRect.left < rRect.right && rRect.top < rRect.bottom &&
                                        rRect.left >= 0 && rRect.top >= 0
                                    if (rValid) {
                                        debugLog("navigateAlipay: clicking search result '芭芭农场' at ${rRect.toShortString()}")
                                        performClickSafe(result)
                                        navHandler.postDelayed({ clearNavigatingFlag() }, 8000L)
                                        return@resultStep
                                    }
                                    debugLog("navigateAlipay: search result bounds invalid: ${rRect.toShortString()}")
                                }
                            }
                            debugLog("navigateAlipay: search result not found, retry")
                            pressBack()
                            navHandler.postDelayed({ stepNavigateAlipayFarm(retry + 1) }, 3000L)
                        }, 3000L)
                        return@searchStep
                    }
                }
                debugLog("navigateAlipay: search edit not found, retry")
                pressBack()
                navHandler.postDelayed({ stepNavigateAlipayFarm(retry + 1) }, 3000L)
            }, 3000L)
            return
        }

        // 策略3：找不到搜索框，按返回重试
        debugLog("navigateAlipay: no search or farm entry found, retry=$retry")
        navHandler.postDelayed({ stepNavigateAlipayFarm(retry + 1) }, 2000L)
    }

    /**
     * 手势点击导航到芭芭农场（节点查找失败时的兜底路径）
     *
     * 导航流程（基于用户确认的"我的淘宝"入口路径）：
     * 1. 点击底部"我的淘宝"tab（bounds=[960,2481][1200,2664]，中心 (1080, 2572)）
     * 2. 等待页面加载
     * 3. 点击"芭芭农场"入口（bounds=[52,1424][270,1644]，中心 (161, 1534)）
     *
     * 坐标基于淘宝主页 dump 确认（1200x2664 屏幕）
     * "我的淘宝"页面上的"芭芭农场"入口比首页推荐 feed 更稳定可靠
     */
    private fun stepClickFarmTabByGesture(platform: Platform, retry: Int) {
        // 自动化正在运行且不在 NAVIGATING/SWITCHING_PLATFORM 状态时，停止导航
        // 注：SWITCHING_PLATFORM 阶段需要导航到目标/原平台芭芭农场，此处应放行
        val ctrlState = AutomationController.currentState
        if (ctrlState != com.bbncbot.automation.AutomationState.IDLE &&
            ctrlState != com.bbncbot.automation.AutomationState.NAVIGATING &&
            ctrlState != com.bbncbot.automation.AutomationState.SWITCHING_PLATFORM &&
            ctrlState != com.bbncbot.automation.AutomationState.STOPPING) {
            debugLog("navigate stepTab gesture: automation running (state=$ctrlState), abort")
            clearNavigatingFlag()
            return
        }

        // 检查当前是否在淘宝主页（通过底部 tab 栏节点检测判断）
        val activity = currentActivityName?.lowercase().orEmpty()
        val isOnTaobaoMainPage = run {
            val root = rootInActiveWindowSafe()
            root != null && isOnTaobaoHomePage(root)
        }

        if (!isOnTaobaoMainPage) {
            // 不在淘宝主页（可能在商品详情页、支付宝收银台等），
            // 先按返回退出异常页面，再重新导航
            debugLog("navigate stepTab gesture: not on taobao main page (activity=$activity), pressing back first")
            pressBack()
            navHandler.postDelayed({
                val ctrlState2 = AutomationController.currentState
                if (ctrlState2 != com.bbncbot.automation.AutomationState.IDLE &&
                    ctrlState2 != com.bbncbot.automation.AutomationState.NAVIGATING &&
                    ctrlState2 != com.bbncbot.automation.AutomationState.STOPPING) {
                    clearNavigatingFlag()
                    return@postDelayed
                }
                // 按返回后重新检查，最多重试 5 次
                if (retry < 5) {
                    stepClickFarmTabByGesture(platform, retry + 1)
                } else {
                    debugLog("navigate stepTab gesture: failed to reach main page after $retry retries")
                    clearNavigatingFlag()
                }
            }, 2000L)
            return
        }

        // 已在淘宝主页，执行手势导航
        debugLog("navigate stepTab gesture: on taobao main page, step 1 - click 我的淘宝 tab")
        dispatchGestureClick(1080f, 2572f)
        navHandler.postDelayed({
            debugLog("navigate stepTab gesture: step 2 - click 芭芭农场 at (161, 1534)")
            dispatchGestureClick(161f, 1534f)
            debugLog("navigate stepTab gesture: done, waiting 8s for farm page")
            Log.i(TAG, "navigateToFarm: done (gesture), platform=$platform, waiting 8s for farm page to load")
            navHandler.postDelayed({ clearNavigatingFlag() }, 8000L)
        }, 2000L)
    }

    /**
     * 检测是否在淘宝主页
     * - 淘宝主页特征：底部 tab 栏包含"首页"、"逛逛"、"消息"、"我的淘宝"等文字
     */
    private fun isOnTaobaoHomePage(root: AccessibilityNodeInfo): Boolean {
        val tabTexts = listOf("首页", "逛逛", "消息", "我的淘宝")
        var found = 0
        for (tab in tabTexts) {
            if (findNodeByText(root, tab) != null) {
                found++
            }
        }
        // 至少找到 3 个底部 tab 文字才认定在主页
        if (found >= 3) {
            debugLog("isOnTaobaoHomePage: found $found tabs, on main page")
            return true
        }
        return false
    }

    /**
     * 处理权限对话框 - 点击"允许"/"同意"等按钮
     * @return true 表示处理了权限对话框（点击了允许按钮）
     */
    private fun handlePermissionDialog(root: AccessibilityNodeInfo): Boolean {
        val allowTexts = listOf("允许", "同意", "始终允许", "仅在使用中允许", "确定", "我知道了")
        for (text in allowTexts) {
            val node = findNodeByText(root, text)
            if (node != null) {
                Log.i(TAG, "handlePermissionDialog: click '$text'")
                performClickSafe(node)
                return true
            }
        }
        return false
    }
}
