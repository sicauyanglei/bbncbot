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
        // 腾讯优量汇 SDK（com.qq.e.ads.PortraitADActivity 等）
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
            "pangolin",          // 穿山甲 Pangolin
            "qq.e.ads",          // 腾讯优量汇 GDT SDK（com.qq.e.ads.PortraitADActivity 等）
            "portraitad",        // 腾讯优量汇 PortraitADActivity / LandscapeADActivity
            "landscapead",       // 腾讯优量汇横屏广告 Activity
            "interstitial",      // 插屏广告 Activity（多 SDK 通用命名）
            "ksrewardvideo",     // 快手 KsRewardVideoActivity
            "kwad"               // 快手广告 SDK
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
     * build588: 供 AutomationController 在跨平台切换失败时恢复 currentPlatform 到原平台
     * （属性 setter 是 private,通过此方法暴露受控的外部写入入口）
     */
    fun setCurrentPlatform(platform: Platform) {
        currentPlatform = platform
    }

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
                // build582 修复（debug_test_20260721_162351.log, build581, UC 平台 line 66）：
                // 历史问题：UC 集肥料点击后 H5 跳转拉起淘宝 App,onAccessibilityEvent 收到
                // WINDOW_STATE_CHANGED(pkg=com.taobao.taobao) 事件,currentPlatform 被覆盖成 TAOBAO。
                // 但 AutomationController 启动时锁定的平台是 UC,后续 navigateToFarm(TAOBAO) 走
                // stepClickFarmTabByGesture（淘宝专用路径），在淘宝芭芭农场 H5 页面点 (1080,2572)
                // 我的淘宝 tab → (161,1534) 芭芭农场入口，反而退出农场页，卡 6 分钟。
                // 修复：自动化运行期间（非 IDLE/SWITCHING_PLATFORM/STOPPING）不修改 currentPlatform,
                // 保持 AutomationController 启动时锁定的平台。SWITCHING_PLATFORM 阶段是主动跨平台
                // 切换,需要 currentPlatform 跟随目标平台更新,所以放行。
                val ctrlState = AutomationController.currentState
                val allowPlatformUpdate = ctrlState == com.bbncbot.automation.AutomationState.IDLE ||
                    ctrlState == com.bbncbot.automation.AutomationState.SWITCHING_PLATFORM ||
                    ctrlState == com.bbncbot.automation.AutomationState.STOPPING
                if (allowPlatformUpdate) {
                    currentPlatform = detected
                } else {
                    Log.d(TAG, "window-state-changed: automation running (state=$ctrlState), keep platform=$currentPlatform (ignore detected=$detected from pkg=$pkg)")
                }
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
            // 必须用 getExternalFilesDir(null) 而非 Environment.getExternalStorageDirectory()
            // 原因：Android 11+ 限制外部存储访问，Environment 路径写入会静默失败
            // 与 LogUploader.getLogDir 保持一致，确保写入方和读取方路径相同
            val file = java.io.File(
                getExternalFilesDir(null),
                "debug.log"
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

    /**
     * 最近一次 [isFarmAppInForeground] 调用时，windows 中是否真的看到农场平台包名窗口。
     *
     * 用途：区分"通过 windows 找到 farm 包名"（强信号）与"仅靠 Activity 类名兜底"（弱信号）。
     * - 弱信号场景下，[isOnFarmPage] 必须能拿到 root 并通过内容验证才算真在农场页，
     *   避免把"蚂蚁庄园 XRiverActivity"等同样命中 farmPageActivityKeywords 的小程序页
     *   误判为农场页（实际根本没回农场，导致 gamePlay 提前退出、误标任务完成）。
     */
    @Volatile
    private var farmPkgWindowVisible: Boolean = false

    /** 当前农场 App 是否在前台（是否有当前平台窗口） */
    fun isFarmAppInForeground(): Boolean {
        return try {
            val cfg = currentPlatformConfig()
            // 0. 先排除本应用包名：bbncbot 自己绝不会被当作"农场 App"
            //    原因：bbncbot 的 MainActivity 类名为 com.bbncbot.MainActivity，
            //    小写后含 "mainactivity"，会被 farmPageActivityKeywords 误匹配，
            //    导致自动化启动后即使没切到农场 App 也以为切到了 → 空转。
            val BBNCBOT_PKG = "com.bbncbot"
            // 1. 优先遍历 windows 查找农场平台包名（最可靠）
            farmPkgWindowVisible = false  // 默认未看到，下面找到才置 true
            val windows = windows
            for (w in windows) {
                val pkg = w.root?.packageName?.toString().orEmpty()
                // 显式排除本应用包名（即使 windows 中出现也不算农场 App）
                if (pkg == BBNCBOT_PKG) continue
                if (pkg in cfg.packageNames || cfg.internalPackagePrefixes.any { pkg.startsWith(it) }) {
                    farmPkgWindowVisible = true
                    return true
                }
            }
            // 2. Activity 类名兜底：kill+relaunch 期间 windows 可能短暂不含农场包名窗口，
            //    但 currentActivityName 已通过 TYPE_WINDOW_STATE_CHANGED 更新为农场 Activity。
            //    若 Activity 类名匹配农场平台 farmPageActivityKeywords，认为仍在农场 App 内。
            //    （forceKillApp 已清除 currentActivityName，所以此处非 null 一定是 kill 后新事件设置的）
            //
            //    注意：此兜底为"弱信号"——XRiverActivity 是支付宝小程序通用容器，
            //    蚂蚁庄园/蚂蚁森林等也命中该关键词。调用方（如 isOnFarmPage）必须配合
            //    内容验证，不能仅凭此就认为在农场页（详见 farmPkgWindowVisible 注释）。
            val activity = currentActivityName?.lowercase().orEmpty()
            if (activity.isNotEmpty() && cfg.farmPageActivityKeywords.isNotEmpty() &&
                cfg.farmPageActivityKeywords.any { activity.contains(it) }
            ) {
                // 兜底场景也排除 bbncbot 自己：com.bbncbot.mainactivity 会匹配 "mainactivity" 关键词，
                // 但这显然不是农场 App。通过判断 activity 是否以 "com.bbncbot" 开头来排除。
                if (activity.startsWith("com.bbncbot")) {
                    debugLog("isFarmAppInForeground: activity=$activity is bbncbot itself, NOT treat as farm app")
                    return false
                }
                debugLog("isFarmAppInForeground: windows miss farm pkg, but activity=$activity matches farm keywords, treat as in farm app (weak signal, requires content check)")
                return true
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
        // P0-2（build513 修复）：systemui 覆盖时（Honor 下拉通知栏/控制中心/锁屏），
        // 即使支付宝后台窗口还在 windows 列表里（isFarmAppInForeground 返回 true），
        // 用户实际看到的是 systemui，不能执行点击操作（会误触通知栏/快捷开关）。
        // 用 rootInActiveWindowSafe().packageName 判断用户实际看到的窗口。
        val activeRootPkg = rootInActiveWindowSafe()?.packageName?.toString().orEmpty()
        if (activeRootPkg.isNotEmpty()) {
            val cfg = currentPlatformConfig()
            val isActiveFarmPkg = activeRootPkg in cfg.packageNames ||
                cfg.internalPackagePrefixes.any { activeRootPkg.startsWith(it) } ||
                activeRootPkg == "com.bbncbot"
            if (!isActiveFarmPkg) {
                // 用户实际看到的窗口不是农场包名（如 systemui），不点击
                if (farmPageCache != false || (System.currentTimeMillis() - farmPageCacheTime) > 1000L) {
                    debugLog("isOnFarmPage: activeRootPkg='$activeRootPkg' is not farm app, not on farm page (systemui/lock screen/other app overlay)")
                    farmPageCache = false
                    farmPageCacheTime = System.currentTimeMillis()
                }
                return false
            }
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
        //    拿不到当前窗口包名时：
        //    - 若 windows 中真的看到了农场包名（farmPkgWindowVisible=true），可能是窗口切换瞬间，
        //      保守认为还在农场页（向下兼容旧逻辑）。
        //    - 若仅靠 Activity 兜底进入（farmPkgWindowVisible=false，如蚂蚁庄园 XRiverActivity），
        //      说明当前页根本不是农场页，直接返回 false，避免误判导致 gamePlay 提前退出等连锁问题。
        val pkg = getCurrentWindowPackage()
        if (pkg == null) {
            if (!farmPkgWindowVisible) {
                debugLog("isOnFarmPage: no window pkg and no farm pkg window visible (activity fallback only, activity=$activity), not on farm page")
                farmPageCache = false
                farmPageCacheTime = System.currentTimeMillis()
                return false
            }
            return true
        }
        val pkgLower = pkg.lowercase()
        if (AD_PKG_KEYWORDS.any { pkgLower.contains(it) }) return false

        // 4. 内容验证（使用缓存，3秒有效期）
        val now = System.currentTimeMillis()
        if (farmPageCache != null && (now - farmPageCacheTime) < 3000L) {
            return farmPageCache!!
        }
        val root = getRootInFarmApp()
        if (root == null) {
            // 拿不到 root：与步骤 3 同样的策略，仅当 windows 中确实看到农场包名窗口时才认为在农场页
            if (!farmPkgWindowVisible) {
                debugLog("isOnFarmPage: root=null and no farm pkg window visible (activity fallback only, activity=$activity), not on farm page")
                farmPageCache = false
                farmPageCacheTime = now
                return false
            }
            return true
        }
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
                text.contains("得肥料") ||
                // 签到成功弹窗（仅在签到后短暂显示，是农场页强信号；
                // 实测日志 debug_sess_949197_20260715_051916.log 显示
                // "签到成功！每天来芭芭农场，更快1分钱领水果~" 不匹配任何上面关键词，
                // 导致 isOnFarmPage 误判为 false，触发 AutomationController 重新导航）
                text.contains("签到成功") || text.contains("1分钱领水果")
        }
        // build584 修复（debug_test_20260721_164711.log, build583, UC 平台 line 55/139）：
        // 历史问题："看一本喜欢的小说"任务页显示"开始阅读"/"继续阅读"+"得肥料",
        // hasFarmCore 因 "得肥料" 子串匹配返回 true → isOnFarmPage=true → 误判为农场主页,
        // state 进 COLLECTING_DIRECT/OPENING_TASK_LIST,从不点击小说、不滑动,任务失败。
        // 修复：检测到小说/阅读任务页特征文字时,排除 hasFarmCore（小说页不是农场主页）。
        // build590 扩展：UC "开始观看得肥料"短剧任务页同样有"得肥料"文案,会被 hasFarmCore
        // 误判为农场主页,这里同时检测短剧页特征（"开始观看"/"继续观看" + "得肥料"）一并排除。
        val isNovelReadPage = allText.any { text ->
            (text.contains("开始阅读") || text.contains("继续阅读")) &&
            allText.any { it.contains("得肥料") || it.contains("肥料") }
        }
        val isShortDramaPage = allText.any { text ->
            (text.contains("开始观看") || text.contains("继续观看")) &&
            allText.any { it.contains("得肥料") || it.contains("肥料") }
        }
        val isNovelOrShortDramaPage = isNovelReadPage || isShortDramaPage
        val hasFarmCoreEffective = if (isNovelOrShortDramaPage) {
            debugLog("isOnFarmPage: novel/short-drama page detected (isNovelReadPage=$isNovelReadPage, isShortDramaPage=$isShortDramaPage), exclude hasFarmCore")
            false
        } else {
            hasFarmCore
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
        val isSearchPage = !hasFarmCoreEffective && (matchCount >= 2 || (matchCount >= 1 && hasSearchOnlyKeyword))
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
                text.contains("得肥料") ||
                // 签到成功弹窗（与 hasFarmCore 同步添加，保持两者判定一致）
                text.contains("签到成功") || text.contains("1分钱领水果")
        }
        // build584: 小说阅读页同样不应判为农场主页（与上方 hasFarmCoreEffective 同步）
        // build590: 短剧任务页同处理（"开始观看得肥料"会被 hasFarmContent 误判）
        val hasFarmContentEffective = if (isNovelOrShortDramaPage) false else hasFarmContent
        // 单独的"芭芭农场"不算，搜索推荐页也会有这个文字
        if (!hasFarmContentEffective) {
            // H5 WebView 兜底：支付宝/淘宝农场页是 H5 页面，WebView 可能不暴露文本节点，
            // 导致 collectAllText 返回空列表或极少文本，内容关键词检查必然失败。
            // 此时若 Activity 是农场 H5 容器（h5appactivity/h5webviewactivity/webviewactivity），
            // 且可访问文本节点很少（< 3），假设是农场 H5 页面返回 true。
            // 前提：到达此处前 Activity 已通过 farmKeywords 检查（步骤 2），
            // 且包名已通过农场 App 检查（步骤 3），非广告 Activity（步骤 1）。
            //
            // 安全收紧：仅当 windows 中确实看到农场包名（farmPkgWindowVisible=true）时才启用 H5 兜底，
            // 避免蚂蚁庄园 XRiverActivity（非 H5 容器但通过 Activity 兜底进入）误中此分支。
            val isH5FarmActivity = activity.contains("h5") || activity.contains("webview")
            if (allText.size < 3 && isH5FarmActivity && farmPkgWindowVisible) {
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

        // build531 修复（debug_test_20260719_063018.log, build531-a39eb89）：
        // 历史问题：在 WebView 弹窗内（如任务列表弹窗），即使节点的 getBoundsInScreen
        // 返回了合法坐标（如 [225,1660][975,1807]，中心 (600,1733)），原逻辑也跳过
        // 直接使用 screen bounds 的路径，强制走 popup offset 修正路径：
        //   - estimateWebViewPopupOffset 找不到锚点 → 用默认 offset=410
        //   - 点击坐标变成 (600, 1733+410)=(600, 2143) ← 偏离按钮实际位置 410px！
        //   - 错误坐标点击没触发跳转，bot 在农场主页滑动 35 次直到超时
        //   - 用户看到"已到倒计时时间还在上下移动"
        //
        // 修复：在弹窗内时，先验证 screen bounds 是否落在合理屏幕范围内
        // （width>0, height>0, top<bottom, y 在屏幕内），若是则直接使用 screen bounds 中心，
        // 不再盲目加 popup offset。popup offset 仅在 screen bounds 明显非法时使用。
        if (isInPopup && rectScreen.width() > 0 && rectScreen.height() > 0 &&
            rectScreen.top < rectScreen.bottom && rectScreen.top in 50..2600 && rectScreen.bottom in 100..2664) {
            val x = rectScreen.exactCenterX()
            val y = rectScreen.exactCenterY()
            debugLog("dispatchGesture: in popup but screen bounds valid, click at ($x, $y) (no popup offset)")
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

        // build557 兜底（debug_test_20260719_125715.log）：节点 bounds 倒置/越界且无 popup 偏移可用时，
        // 尝试向上找最近一个 bounds 合法的祖先节点，用其中心点击。
        // 适用场景：WebView 内部子节点 bounds 是内容坐标（如 [72,4038][890,2666]），
        // 但其外层容器/祖先的 bounds 可能是真实屏幕坐标（WebView 容器自身的 bounds）。
        var ancestor: AccessibilityNodeInfo? = node.parent
        var ancDepth = 0
        while (ancestor != null && ancDepth < 8) {
            val ancRect = android.graphics.Rect()
            ancestor.getBoundsInScreen(ancRect)
            if (ancRect.width() > 0 && ancRect.height() > 0 &&
                ancRect.top < ancRect.bottom && ancRect.left < ancRect.right &&
                ancRect.top in 50..2600 && ancRect.bottom in 100..2664) {
                val x = ancRect.exactCenterX()
                val y = ancRect.exactCenterY()
                debugLog("dispatchGesture: ancestor bounds valid at depth=$ancDepth, click at ($x, $y) ancBounds=${ancRect.toShortString()}")
                return dispatchGestureClick(x, y)
            }
            ancestor = ancestor.parent
            ancDepth++
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
     * 检测游戏页面是否显示"肥料全部取完"/"肥料已全部领取"等提示
     *
     * build528（用户反馈）：试玩游戏但不是完成游戏任务（如对战、关卡、过关、订单等任务），
     * 打开游戏 App 后，只需要等待拿肥料，不需要任何操作，
     * 显示肥料全部取完后，退出游戏 App。
     *
     * 检测以下提示文案：
     * - "肥料全部取完"/"肥料已全部取完"
     * - "肥料已全部领取"/"肥料全部领取"
     * - "今日肥料已全部领取"/"今日肥料全部领取"
     * - "肥料领取完毕"/"肥料已领取完毕"
     * - "没有更多肥料了"/"肥料已领完"
     *
     * @return true 表示肥料已全部取完，应退出游戏
     */
    fun isFertilizerAllClaimed(): Boolean {
        val root = rootInActiveWindowSafe() ?: return false
        val allText = collectAllText(root)
        val isAllClaimed = allText.any { text ->
            text.contains("肥料全部取完") || text.contains("肥料已全部取完") ||
                text.contains("肥料已全部领取") || text.contains("肥料全部领取") ||
                text.contains("今日肥料已全部领取") || text.contains("今日肥料全部领取") ||
                text.contains("肥料领取完毕") || text.contains("肥料已领取完毕") ||
                text.contains("没有更多肥料") || text.contains("肥料已领完") ||
                text.contains("全部肥料已领取") || text.contains("全部肥料已取完")
        }
        if (isAllClaimed) {
            debugLog("isFertilizerAllClaimed: YES, sample=${allText.take(5)}")
        }
        return isAllClaimed
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
                        // build531 修复（debug_test_20260719_063018.log, build531-a39eb89）：
                        // 历史问题：bot 点击"点此逛一逛"按钮后没成功跳转到浏览页面（仍停在农场主页），
                        // 但 findSwipeForFertilizerHint 识别到任务列表的描述文案"浏览15秒得1000肥料"
                        // 误以为是浏览页面的进度提示，于是在农场主页滑动 35 次，countdown=0s 一直没变。
                        //
                        // 修复：排除任务列表的奖励描述文案——"浏览N秒得X肥料"/"浏览N秒得X肥"是
                        // 任务列表里的任务描述（"浏览15秒得1000肥料"），不是浏览页面的进度提示。
                        // 浏览页面的提示通常是"每浏览N秒得一次奖励"/"滑动浏览N秒"等。
                        val isTaskDescription = text.contains("得") && (
                            text.contains("肥料") || text.contains("肥") || text.contains("奖励")
                        )
                        if (isTaskDescription) {
                            debugLog("findSwipeForFertilizerHint: skip task description '$text'")
                            continue
                        }
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

    // ---------- 肥料进度识别（build529：用户要求"全部实现"） ----------
    // 用户需求：你能获取肥料进度的窗口吗，比如浏览了多少秒，标识肥的环形进度条等 → 全部实现
    // 1) 文本进度识别（本节）：覆盖"已浏览15s"/"15/30秒"/"进度50%"等多种文案
    // 2) AI 视觉识别环形进度条：见 AiVisionClient.recognizeProgressFromScreenshot

    /**
     * 肥料进度类型
     */
    enum class ProgressType {
        /** 未识别到进度 */
        NONE,
        /** 已浏览/已观看秒数（如"已浏览15s"），可能含 total（"15/30秒"）也可能 total=0 表示未知 */
        SECONDS,
        /** 分子/分母型（如"15/30秒"或"2/3"） */
        FRACTION,
        /** 百分比型（如"进度50%"） */
        PERCENT
    }

    /**
     * 肥料进度信息（文本识别结果）
     *
     * @param type    进度类型
     * @param current 当前进度值（SECONDS/FRACTION 时为已完成秒数/分子；PERCENT 时为百分比 0-100）
     * @param total   总值（SECONDS/FRACTION 时为总秒数/分母，total=0 表示未知；PERCENT 时固定 100）
     * @param rawText 命中的原始文本（用于日志）
     */
    data class BrowseProgressInfo(
        val type: ProgressType,
        val current: Int,
        val total: Int,
        val rawText: String
    ) {
        companion object {
            val NONE = BrowseProgressInfo(ProgressType.NONE, 0, 0, "")
        }

        val isFound: Boolean get() = type != ProgressType.NONE
        /** 进度百分比 0-100（total 未知时返回 0，调用方可降级到 AI 视觉识别） */
        val percent: Int
            get() = when (type) {
                ProgressType.SECONDS, ProgressType.FRACTION ->
                    if (total > 0) (current * 100 / total).coerceIn(0, 100) else 0
                ProgressType.PERCENT -> current.coerceIn(0, 100)
                ProgressType.NONE -> 0
            }
        /** 剩余秒数（仅 SECONDS/FRACTION 有意义，否则 0） */
        val remainingSeconds: Int
            get() = when (type) {
                ProgressType.SECONDS, ProgressType.FRACTION -> (total - current).coerceAtLeast(0)
                else -> 0
            }
    }

    /**
     * 解析"已浏览/已观看 xx 秒"类已浏览时长进度
     *
     * 用户场景：浏览任务进行中页面会显示已浏览时长，例如：
     * - "已浏览15s" / "已浏览15秒" / "已观看15秒"
     * - "浏览了15s" / "逛了15秒"
     * - "已浏览15/30秒"（同时含分子分母，一并解析 total）
     *
     * @return [BrowseProgressInfo]；含 current（已浏览秒数），若同一文本中能解析到 total 则一并填入
     */
    fun findBrowseProgressSeconds(): BrowseProgressInfo {
        val root = getRootInFarmApp() ?: return BrowseProgressInfo.NONE
        val allText = collectAllText(root)
        val browseDoneKeywords = listOf("已浏览", "已观看", "已逛", "浏览了", "观看了", "逛了")
        val doneSecondsPattern = Regex("(\\d+)\\s*[秒s]")
        val fractionPattern = Regex("(\\d+)\\s*/\\s*(\\d+)\\s*[秒s]")
        for (text in allText) {
            if (browseDoneKeywords.none { text.contains(it) }) continue
            // 1) 优先匹配分子/分母（更精确，可一次性拿到 current 与 total）
            val f = fractionPattern.find(text)
            if (f != null) {
                val cur = f.groupValues[1].toIntOrNull() ?: 0
                val tot = f.groupValues[2].toIntOrNull() ?: 0
                if (cur in 0..300 && tot in 1..300 && cur <= tot) {
                    debugLog("findBrowseProgressSeconds: found fraction '$text', cur=$cur, tot=$tot")
                    return BrowseProgressInfo(ProgressType.FRACTION, cur, tot, text)
                }
            }
            // 2) 退化为单秒数（current only，total=0 表示未知）
            val m = doneSecondsPattern.find(text)
            if (m != null) {
                val cur = m.groupValues[1].toIntOrNull() ?: 0
                if (cur in 0..300) {
                    debugLog("findBrowseProgressSeconds: found seconds '$text', cur=$cur, tot=0")
                    return BrowseProgressInfo(ProgressType.SECONDS, cur, 0, text)
                }
            }
        }
        return BrowseProgressInfo.NONE
    }

    /**
     * 解析分子/分母型进度（不要求前缀"已浏览"，更通用）
     *
     * 识别示例：
     * - "15/30秒" / "15 / 30s"
     * - "(1/1)" / "(2/2)" / "进度 2/3"
     *
     * 注意：与 [findBrowseProgressSeconds] 互为补充——前者要求带"已浏览"前缀更精确，
     * 此函数对任意"X/Y"形式都识别，调用方需自行根据上下文判断是否有意义。
     *
     * @return [BrowseProgressInfo]；total 为分母，current 为分子
     */
    fun findProgressFraction(): BrowseProgressInfo {
        val root = getRootInFarmApp() ?: return BrowseProgressInfo.NONE
        val allText = collectAllText(root)
        val secondsFraction = Regex("(\\d+)\\s*/\\s*(\\d+)\\s*[秒s]")
        val plainFraction = Regex("(\\d+)\\s*/\\s*(\\d+)")
        for (text in allText) {
            val m1 = secondsFraction.find(text)
            if (m1 != null) {
                val cur = m1.groupValues[1].toIntOrNull() ?: 0
                val tot = m1.groupValues[2].toIntOrNull() ?: 0
                if (cur in 0..300 && tot in 1..300 && cur <= tot) {
                    debugLog("findProgressFraction: found seconds fraction '$text', cur=$cur, tot=$tot")
                    return BrowseProgressInfo(ProgressType.FRACTION, cur, tot, text)
                }
            }
            val m2 = plainFraction.find(text)
            if (m2 != null) {
                val cur = m2.groupValues[1].toIntOrNull() ?: 0
                val tot = m2.groupValues[2].toIntOrNull() ?: 0
                if (cur in 0..300 && tot in 1..300 && cur <= tot) {
                    debugLog("findProgressFraction: found plain fraction '$text', cur=$cur, tot=$tot")
                    return BrowseProgressInfo(ProgressType.FRACTION, cur, tot, text)
                }
            }
        }
        return BrowseProgressInfo.NONE
    }

    /**
     * 解析百分比型进度
     *
     * 识别示例：
     * - "进度50%" / "当前进度 50%"
     * - "已完成50%" / "完成度50%" / "完成率50%"
     *
     * @return [BrowseProgressInfo]；current 即百分比（0-100），total 固定 100
     */
    fun findProgressPercent(): BrowseProgressInfo {
        val root = getRootInFarmApp() ?: return BrowseProgressInfo.NONE
        val allText = collectAllText(root)
        val percentKeywords = listOf("进度", "完成", "完成度", "完成率")
        val percentPattern = Regex("(\\d{1,3})\\s*%")
        for (text in allText) {
            if (percentKeywords.none { text.contains(it) }) continue
            val m = percentPattern.find(text)
            if (m != null) {
                val pct = m.groupValues[1].toIntOrNull() ?: 0
                if (pct in 0..100) {
                    debugLog("findProgressPercent: found '$text', percent=$pct")
                    return BrowseProgressInfo(ProgressType.PERCENT, pct, 100, text)
                }
            }
        }
        return BrowseProgressInfo.NONE
    }

    /**
     * 统一获取浏览任务进度信息（优先级聚合）
     *
     * 优先级顺序（前者命中即返回）：
     * 1. [findBrowseProgressSeconds] - 已浏览秒数（最常见，最精确，含"15/30秒"形式）
     * 2. [findProgressPercent]       - 百分比型
     * 3. [findProgressFraction]      - 纯"X/Y"型（兜底）
     *
     * @return 命中的进度信息，未命中返回 [BrowseProgressInfo.NONE]
     */
    fun findBrowseProgressInfo(): BrowseProgressInfo {
        val seconds = findBrowseProgressSeconds()
        if (seconds.isFound) return seconds
        val percent = findProgressPercent()
        if (percent.isFound) return percent
        val fraction = findProgressFraction()
        if (fraction.isFound) return fraction
        return BrowseProgressInfo.NONE
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
        // build580 修复（debug_test_20260721_152904.log, build580, UC 平台 line 178-200）：
        // UC 集肥料点击"去完成"→ 弹腾讯优量汇 PortraitADActivity 广告,广告结束后页面显示
        // "恭喜获取奖励"+右侧关闭按钮（图像×,无障碍树抓不到 text 节点）。原信号 1/2/3 都不触发：
        // - 信号1：isTaskCompletePage 在广告 Activity 中返回 false（build579 修复）
        // - 信号2：仍在广告 Activity（PortraitADActivity）,adActivity=true
        // - 信号3：claim-text-nodes: NONE（关闭按钮是图像,无"领取奖励"文字）
        // 导致 watchAd 一直 waiting min duration,最后 processTask unknown page → AI 误判 WAIT → 跳过任务。
        //
        // 修复：加信号4——检测到"恭喜获得/获取奖励/奖励已到账/领取成功"等广告结束标志文字时,
        // 判定广告结束。这些文字说明广告已播完、奖励已发放,只需要点关闭按钮退出即可。
        // 注意：遍历所有 windows 收集文本（覆盖独立弹窗窗口）,避免漏检。
        val allTexts = mutableListOf<List<String>>()
        try {
            val allWindows = windows
            for (w in allWindows) {
                val root = w.root ?: continue
                allTexts.add(collectAllText(root))
            }
        } catch (e: Exception) {
            val root = rootInActiveWindowSafe()
            if (root != null) allTexts.add(collectAllText(root))
        }
        if (allTexts.isEmpty()) {
            val root = rootInActiveWindowSafe()
            if (root != null) allTexts.add(collectAllText(root))
        }
        val adEndedKeywords = listOf(
            "恭喜获取奖励", "恭喜获得奖励", "恭喜获得", "获取奖励",
            "奖励已到账", "奖励已发放", "领取成功", "已领取奖励",
            "肥料已到账", "肥料已发放", "获得肥料"
        )
        for (texts in allTexts) {
            for (text in texts) {
                if (adEndedKeywords.any { text.contains(it) }) {
                    debugLog("isAdEndedMultiSignal: YES (ad ended text detected: '$text')")
                    return true
                }
            }
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
     * 页面场景类型（场景识别引擎）
     *
     * 聪明思考的核心：先理解"当前页面是什么场景"，再决定动作。
     * 相比关键词黑名单驱动，场景识别能防御未知文案陷阱——
     * 广告设计者换个诱导文案，但页面场景特征不变，仍能被正确识别。
     *
     * 场景优先级（从高到低，先识别陷阱场景再识别正常场景）：
     * 1. TRAP_RECHARGE   — 充值/付费页（违反禁止交易原则，立即退出）
     * 2. TRAP_ABNORMAL   — 交易/下单页（违反禁止交易原则，立即退出）
     * 3. TRAP_MINIPROGRAM — 非农场小程序陷阱（支付宝/淘宝，立即退出）
     * 4. TRAP_LANDING    — 广告主落地页（下载/安装/应用商店，立即退出）
     * 5. TRAP_INSTALL    — 诱导弹窗（立即下载/去购买，关闭后继续）
     * 6. TRAP_REPLAY     — 复看陷阱（再看一个/加倍领取，关闭后继续）
     * 7. SIGN_IN         — 签到页面（可点击签到/领取签到奖励）
     * 8. REWARD_POPUP    — 奖励领取弹窗（可点击领取奖励）
     * 9. AD_ENDED        — 广告已结束（可关闭/领取奖励）
     * 10. AD_PLAYING     — 广告播放中（等待，勿点诱导按钮）
     * 11. FARM_PAGE      — 农场主页（正常状态）
     * 12. GENERIC_POPUP  — 未知弹窗（无肥料提示，需主动关闭）
     * 13. UNKNOWN        — 未知场景（保守等待）
     */
    enum class PageScene {
        FARM_PAGE,           // 农场主页
        AD_PLAYING,          // 广告播放中（有倒计时/跳过按钮）
        AD_ENDED,            // 广告已结束（无倒计时，可能有领取按钮）
        REWARD_POPUP,        // 奖励领取弹窗（恭喜获得/领取奖励）
        SIGN_IN,             // 签到页面（立即签到/签到领取）
        GENERIC_POPUP,       // 未知弹窗（无肥料提示，需主动关闭）
        QUIZ_PAGE,           // 答题页面（问题 + 2个选项，需网络请求获取答案）
        TRAP_LANDING,        // 广告主落地页陷阱
        TRAP_INSTALL,        // 诱导弹窗陷阱（立即下载）
        TRAP_REPLAY,         // 复看陷阱（再看一个/加倍领取）
        TRAP_RECHARGE,       // 充值/付费页陷阱
        TRAP_ABNORMAL,       // 交易/下单页陷阱
        TRAP_MINIPROGRAM,    // 非农场小程序陷阱
        UNKNOWN              // 未知场景
    }

    /**
     * 识别当前页面场景（场景识别引擎核心）
     *
     * 多信号指纹融合，按优先级返回第一个匹配的场景：
     * - 陷阱场景优先识别（安全第一，宁可不领肥料也不能掉陷阱）
     * - 正常场景按广告生命周期识别（播放中→已结束→奖励弹窗）
     *
     * 相比零散的 isXxx() 检测，本方法提供统一的场景视图，
     * 调用方基于场景类型决策，而非分散的 if-else。
     *
     * @return 当前页面场景类型
     */
    fun identifyCurrentScene(): PageScene {
        // 1. 充值/付费页（最高优先级，违反禁止交易原则）
        if (isRechargePage()) return PageScene.TRAP_RECHARGE
        // 2. 交易/下单页（违反禁止交易原则）
        if (isOnAbnormalPage()) return PageScene.TRAP_ABNORMAL
        // 3. 非农场小程序陷阱（支付宝/淘宝）
        if (isMiniProgramTrap()) return PageScene.TRAP_MINIPROGRAM
        // 4. 广告主落地页陷阱（下载/安装/应用商店）
        if (isAdLandingPage()) return PageScene.TRAP_LANDING
        // 5. 签到页面（必须在 FARM_PAGE 之前检测：签到页在农场 App 的 WebView 内，
        //    isOnFarmPage 会返回 true，但签到页有专属签到按钮需要点击领取签到肥料）
        if (isSignInPage()) return PageScene.SIGN_IN
        // 6. 答题页面（必须在 FARM_PAGE 之前检测：答题页在农场 App 的 WebView 内，
        //    恰好 2 个选项 + 问题文本，需调用 AI API 获取答案）
        if (isQuizPage()) return PageScene.QUIZ_PAGE
        // 7. 农场主页（正常状态，优先识别避免误判为广告）
        if (isOnFarmPage()) return PageScene.FARM_PAGE
        // 8. 诱导弹窗陷阱（立即下载/去购买，需检测是否在广告页上弹出）
        val installBtn = findAdInstallButton()
        if (installBtn != null) {
            // 诱导弹窗：有诱导按钮 + 无倒计时（倒计时存在说明广告还在播放，诱导按钮是广告CTA）
            val hasCountdown = findAdDurationHint() > 0
            if (!hasCountdown) return PageScene.TRAP_INSTALL
        }
        // 9. 复看陷阱（再看一个/加倍领取）
        if (isReplayTrapPage()) return PageScene.TRAP_REPLAY
        // 10. 奖励领取弹窗（恭喜获得 + 领取按钮）
        if (isRewardPopupPage()) return PageScene.REWARD_POPUP
        // 11. 广告已结束（任务完成页 或 无倒计时且不在广告Activity）
        if (isTaskCompletePage()) return PageScene.AD_ENDED
        // 12. 广告播放中（有倒计时 或 在广告Activity）
        if (isAdPlaying() || isAdContentShown()) {
            val hasCountdown = findAdDurationHint() > 0
            if (hasCountdown) return PageScene.AD_PLAYING
            // 在广告页但无倒计时，可能是广告已结束等待用户操作
            return PageScene.AD_ENDED
        }
        // 13. 通用弹窗（无肥料提示，需主动关闭）
        // 用户需求：弹框窗口如何没有肥料提示，需要关闭弹窗
        // 注意：必须放在 UNKNOWN 之前，避免未知弹窗被保守等待卡住
        if (isGenericPopup()) return PageScene.GENERIC_POPUP
        // 14. 未知场景（保守等待）
        return PageScene.UNKNOWN
    }

    /**
     * 检测签到页面（供场景识别引擎用）
     *
     * 签到页面特征：含"签到"/"立即签到"/"签到领取"/"今日签到"/"连续签到"等文案
     * - 签到页面通常在农场 App 的 WebView 内（Activity 是农场 H5 容器，isOnFarmPage 返回 true）
     * - 签到页面有专属签到按钮需要点击才能领取签到肥料
     * - 必须在 FARM_PAGE 之前识别，否则会被 isOnFarmPage 误判为农场主页
     *
     * 排除：
     * - 任务列表里的"去签到"任务按钮（任务列表不在签到页面，且"去签到"是任务入口文案）
     * - 广告主落地页（已在前置场景识别中过滤）
     *
     * @return true 表示当前是签到页面
     */
    private fun isSignInPage(): Boolean {
        val root = rootInActiveWindowSafe() ?: return false
        val allText = collectAllText(root)
        // 签到页面特征文案（签到日历/连续签到/今日签到等是签到页独有，任务列表不会有）
        // 实测日志特征（debug_test_20260715_051932.log）：
        // - "第7天"/"已领取"/"今天" — 签到日历格子文字
        // - "签到成功！每天来芭芭农场，更快1分钱领水果~" — 签到成功提示
        // - "关闭做任务集肥料弹窗" — 关闭按钮（签到日历弹窗和任务列表共用）
        val signInPageKeywords = listOf(
            "立即签到", "签到领取", "今日签到", "连续签到",
            "签到日历", "签到成功", "已签到", "补签",
            "签到得", "签到可领", "签到奖励",
            // 签到成功提示（签到日历弹窗自动签到后显示）
            "签到！每天", "签到！每", "每天来芭芭农场"
        )
        // 至少匹配 1 个签到页面特征文案（这些文案足够特异，1 个即可确认）
        return allText.any { text ->
            signInPageKeywords.any { kw -> text.contains(kw) }
        }
    }

    /**
     * 检测复看陷阱页面（供场景识别引擎用）
     * @return true 表示当前页面是复看陷阱弹窗
     */
    private fun isReplayTrapPage(): Boolean {
        val root = rootInActiveWindowSafe() ?: return false
        val allText = collectAllText(root)
        val replayTrapKeywords = listOf(
            "再看一个", "再看看", "再看视频", "再看一次",
            "加倍领取", "翻倍领取", "翻倍奖励", "双倍奖励",
            "看视频翻倍", "看视频加倍", "看广告翻倍",
            "立即翻倍", "立即加倍", "领取双倍",
            "再看15秒", "再看30秒", "再看视频领"
        )
        return allText.any { text ->
            replayTrapKeywords.any { kw -> text.contains(kw) }
        }
    }

    /**
     * 检测奖励领取弹窗页面（供场景识别引擎用）
     *
     * 特征：含"恭喜获得"/"奖励"/"领取"等文案 + 领取按钮
     * 排除：广告主落地页（已在前置场景识别中过滤）
     * @return true 表示当前是奖励领取弹窗
     */
    private fun isRewardPopupPage(): Boolean {
        val root = rootInActiveWindowSafe() ?: return false
        val allText = collectAllText(root)
        // 奖励弹窗特征文案
        val rewardPopupKeywords = listOf(
            "恭喜获得", "获得奖励", "领取成功", "奖励已到账",
            "完成任务", "全部完成", "已完成"
        )
        val hasRewardText = allText.any { text ->
            rewardPopupKeywords.any { kw -> text.contains(kw) }
        }
        if (!hasRewardText) return false
        // 进一步确认：有领取/确定/知道了按钮（排除诱导按钮）
        // 注意：必须传 enforceSceneWhitelist=false，否则会与 identifyCurrentScene 形成无限递归
        // （findClaimRewardButton → isClaimRewardAllowedScene → identifyCurrentScene → isRewardPopupPage）
        val claimBtn = findClaimRewardButton(enforceSceneWhitelist = false)
        return claimBtn != null
    }

    /**
     * 检测通用弹窗（无肥料提示的弹窗）
     *
     * 用户需求：弹框窗口如何没有肥料提示，需要关闭弹窗。
     *
     * 用于处理未知弹窗（如分享好友/开通会员/评价/更新/活动等弹窗）：
     * - 弹窗存在（有"关闭/×/知道了/确定/取消"等关闭按钮文案）
     * - 但不包含肥料领取/奖励/签到等提示文案
     *
     * 此类弹窗需要主动关闭，避免 bot 卡在 UNKNOWN 场景保守等待。
     *
     * 排除（已在前置场景识别中过滤，这里兜底防御）：
     * - 奖励领取弹窗（isRewardPopupPage）
     * - 签到页面（isSignInPage）
     * - 复看陷阱（isReplayTrapPage）
     * - 诱导弹窗陷阱（findAdInstallButton）
     * - 充值/交易/小程序/落地页陷阱
     *
     * 安全策略：含肥料相关文案的弹窗不视为通用弹窗（保守不关闭），
     * 避免误关闭正在显示肥料领取的奖励弹窗。
     *
     * @return true 表示当前是通用弹窗（无肥料提示，需主动关闭）
     */
    private fun isGenericPopup(): Boolean {
        val root = rootInActiveWindowSafe() ?: return false
        val allText = collectAllText(root)
        if (allText.isEmpty()) return false

        // 肥料/奖励相关文案：存在则不视为通用弹窗（保守不关闭）
        // 注意：包含"肥料"/"得肥"等广泛关键词，覆盖所有可能含肥料提示的场景
        val fertilizerKeywords = listOf(
            "肥料", "得肥", "集肥", "领肥",
            "恭喜获得", "获得奖励", "领取奖励", "领取成功", "奖励已到账",
            "签到",  // 签到日历弹窗（含签到肥料）
            "再看一个", "加倍领取", "翻倍领取", "翻倍奖励",  // 复看陷阱文案
            "去完成", "去逛逛", "去签到", "去做任务",  // 任务列表入口
            "做任务集肥料", "关闭做任务集肥料弹窗",  // 任务列表弹窗
            "任务列表", "任务规则"
        )
        val hasFertilizerText = allText.any { text ->
            fertilizerKeywords.any { kw -> text.contains(kw) }
        }
        if (hasFertilizerText) return false

        // 关闭按钮文案：存在则视为弹窗
        // 注意：使用足够特异的关闭文案，避免把"立即关闭下载"等诱导按钮文案误识别
        val closeKeywords = listOf(
            "×", "关闭", "close", "知道了", "我知道了",
            "确定", "取消", "暂不", "以后再说", "稍后再说",
            "残忍拒绝", "拒绝", "关闭弹窗", "不再提示"
        )
        val hasCloseButton = allText.any { text ->
            closeKeywords.any { kw -> text.contains(kw) }
        }
        return hasCloseButton
    }

    /**
     * 检测答题页面（问题 + 2个选项，需网络请求获取答案）
     *
     * 用户需求：回答问题就可以领取肥料，可以思考下认真回答问题。
     * 答题任务特征：
     * - 有问题文本（含"？"问号，或"以下哪个"/"哪个是"/"是否"/"对吗"等疑问词）
     * - 恰好 2 个可点击的选项按钮（用户确认只有两个选项）
     * - 通常在农场 App 的 WebView 内（与签到页类似）
     *
     * 排除（已在前置场景识别中过滤）：
     * - 奖励领取弹窗（恭喜获得 + 领取按钮，不是答题）
     * - 签到页面（签到日历特征文案）
     * - 通用弹窗（无肥料提示的关闭类弹窗）
     * - 陷阱页面（充值/交易/小程序/落地页/诱导弹窗）
     *
     * 安全策略：
     * - 必须恰好 2 个选项（用户明确说"只有两个选项"）
     * - 必须有问题文本（含"？"或疑问词）
     * - 不含陷阱按钮文案（立即下载/去购买等）
     *
     * @return true 表示当前是答题页面
     */
    private fun isQuizPage(): Boolean {
        val options = findQuizOptions()
        if (options.size != 2) return false
        val question = findQuizQuestion()
        return question.isNotEmpty()
    }

    /**
     * 查找答题问题文本
     *
     * 答题问题特征：
     * - 含"？"问号（中文/英文问号）
     * - 或含疑问词："以下哪个"/"哪个是"/"哪个不是"/"是否"/"对吗"/"是什么"/"哪一项"
     *
     * @return 问题文本（找不到返回空字符串）
     */
    fun findQuizQuestion(): String {
        val root = rootInActiveWindowSafe() ?: return ""
        val allText = collectAllText(root)
        // 疑问词特征（足够特异，命中即视为问题）
        val questionKeywords = listOf(
            "以下哪个", "以下哪项", "以下哪一", "哪个是", "哪个不是",
            "哪一项", "哪项是", "是否", "对吗", "对不对", "是什么",
            "是多少", "是真是假", "正确的是", "错误的是"
        )
        for (text in allText) {
            if (text.isBlank()) continue
            // 含问号（中/英）且长度足够（避免短文本误匹配）
            if ((text.contains("？") || text.contains("?")) && text.length >= 5) {
                return text.trim()
            }
            // 含疑问词
            if (questionKeywords.any { kw -> text.contains(kw) } && text.length >= 5) {
                return text.trim()
            }
        }
        return ""
    }

    /**
     * 查找答题选项按钮（恰好 2 个）
     *
     * 用户确认：答题只有两个选项（对/错、是/否、A/B 等）。
     *
     * 选项按钮特征：
     * - 可点击的节点（clickable=true）
     * - 有文本（非空）或 contentDescription
     * - 排除陷阱按钮（立即下载/去购买/查看详情等）
     * - 排除关闭按钮（×/关闭/知道了 等，这些是弹窗关闭按钮不是答题选项）
     *
     * @return 选项按钮列表（按页面顺序，通常 2 个；其他数量返回空列表）
     */
    fun findQuizOptions(): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindowSafe() ?: return emptyList()
        // 陷阱按钮文案（来自平台配置 + 通用诱导文案）
        val trapTexts = currentPlatformConfig().adInstallButtonTexts + listOf(
            "立即下载", "去购买", "查看详情", "立即购买", "立即开通",
            "去安装", "立即安装", "下载应用"
        )
        // 关闭类按钮文案（不是答题选项）
        val closeTexts = listOf(
            "×", "关闭", "close", "知道了", "我知道了", "确定", "取消",
            "暂不", "以后再说", "残忍拒绝", "拒绝", "关闭弹窗", "不再提示",
            "返回", "back"
        )
        // 排除文案（陷阱 + 关闭 + 任务入口）
        val excludeTexts = trapTexts + closeTexts + listOf(
            "去完成", "去逛逛", "去签到", "去答题", "去观看", "立即观看",
            "领取奖励", "领取肥料", "领取", "签到", "提交"
        )

        val options = mutableListOf<AccessibilityNodeInfo>()
        val seenTexts = mutableSetOf<String>()

        // 递归遍历查找可点击的文字节点
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val combined = if (text.isNotEmpty()) text else desc
            if (combined.isNotEmpty() && node.isClickable) {
                // 排除陷阱/关闭/任务入口按钮
                val isExcluded = excludeTexts.any { combined.contains(it) }
                if (!isExcluded && !seenTexts.contains(combined)) {
                    // 选项文本通常较短（< 50 字符），且不是长段描述
                    if (combined.length <= 50) {
                        options.add(node)
                        seenTexts.add(combined)
                    }
                }
            }
            for (i in 0 until node.childCount) {
                walk(node.getChild(i))
            }
        }
        walk(root)

        // 恰好 2 个选项才返回（用户确认只有两个选项）
        return if (options.size == 2) options else emptyList()
    }

    /**
     * 判断当前场景是否允许点击"领取奖励"按钮（场景白名单）
     *
     * 聪明思考：只在确认是"广告已结束"/"奖励弹窗"/"签到页面"场景才允许点击领取按钮，
     * 避免广告播放中的"领取优惠"/"领取福利"诱导按钮被误点击。
     * 签到页面有专属签到按钮（立即签到/签到领取）需要点击才能领取签到肥料，必须放行。
     *
     * @return true 表示当前场景允许点击领取按钮
     */
    fun isClaimRewardAllowedScene(): Boolean {
        val scene = identifyCurrentScene()
        return scene == PageScene.AD_ENDED || scene == PageScene.REWARD_POPUP ||
            scene == PageScene.SIGN_IN
    }

    /**
     * 判断当前场景是否允许点击"关闭广告"按钮（场景白名单）
     *
     * 聪明思考：只在"广告播放中"或"广告已结束"或"奖励弹窗"或"签到页面"或"通用弹窗"场景才允许点击关闭按钮，
     * 避免在农场页/陷阱页误点"关闭"导致退出农场或掉入陷阱。
     * 签到页面领取后需要点关闭/确定退出，必须放行。
     * 通用弹窗（无肥料提示）需要主动关闭，必须放行。
     *
     * @return true 表示当前场景允许点击关闭按钮
     */
    fun isCloseAdAllowedScene(): Boolean {
        val scene = identifyCurrentScene()
        return scene == PageScene.AD_PLAYING || scene == PageScene.AD_ENDED ||
            scene == PageScene.REWARD_POPUP || scene == PageScene.SIGN_IN ||
            scene == PageScene.GENERIC_POPUP
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
        // build557 修复：跳过 bounds 严重非法的节点（WebView 内部内容坐标会导致点击失败）。
        // 不过滤屏幕外但 bounds 合法的节点（任务列表可滚动，屏幕外按钮下一帧可能滚入屏幕）。
        val screenHeight = try {
            resources.displayMetrics.heightPixels
        } catch (_: Exception) { 2664 }
        for (kw in keywords) {
            val node = findNodeByText(root, kw)
            if (node != null) {
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                // bounds 合法性：width>0、height>0、top<bottom（不倒置）、top 不严重越出屏幕
                // 容许小幅越界（+200）应对弹窗滚动偏移，但 top 远超屏幕高度（如 4038）说明是
                // WebView 内容坐标，dispatchGesture 无法转换 → 跳过此节点继续找下一个 keyword
                val boundsValid = rect.width() > 0 && rect.height() > 0 &&
                    rect.top < rect.bottom && rect.left < rect.right &&
                    rect.top in 0..(screenHeight + 200)
                if (!boundsValid) {
                    debugLog("findCollectFertilizerButton: drop invalid-bounds node text='$kw' bounds=${rect.toShortString()}")
                    continue
                }
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
     *
     * 过滤规则（防御扫描器把规则条款文本误识别为任务按钮，引发 isPaidTask / collectedCount 误判）：
     * 1. 必须可点击（[findClickableSelfOrParentInternal] 找不到可点击父节点时返回 node 本身，
     *    但规则条款节点 clickable=false，不应作为任务按钮）
     * 2. 按钮文本必须简短（≤50 字）—— 真任务按钮文案一般 <30 字；规则条款常 >100 字
     * 3. 按钮文本不能以条款编号开头（"1、"/"2、"/"3. "/"4）"...）
     * 4. bounds 必须合法（width > 0 且 height > 0），过滤屏幕外/缓存失效的非法矩形
     */
    fun findGoCompleteButtons(): List<AccessibilityNodeInfo> {
        val root = getRootInFarmApp() ?: return emptyList()
        val keywords = currentPlatformConfig().goCompleteTexts
        val raw = mutableListOf<AccessibilityNodeInfo>()
        val seen = HashSet<Int>()
        collectNodesByText(root, keywords, raw, seen)
        // 过滤规则条款 / 不可点击 / bounds 非法的节点
        // 注意：不过滤屏幕外的按钮（top > 屏幕高度）—— 任务列表是可滚动的，
        // 屏幕外的按钮只是不可见，不是不存在。processTask 处理完当前任务后会
        // 重新打开任务列表，taskButtons 会重新计算，所以屏幕外按钮不影响实际运行。
        // 如果过滤掉屏幕外按钮，taskButtons.size 会变小，processTask 会误判
        // "全部任务完成"（currentTaskIndex >= taskButtons.size），跳过剩余任务。
        val result = raw.mapNotNull { node ->
            // 1. 必须可点击（UC 平台"去完成"本身不可点击，向上找最近的 clickable 父节点）
            var clickTarget = node
            if (!node.isClickable) {
                var p: AccessibilityNodeInfo? = node.parent
                var depth = 0
                while (p != null && depth < 5) {
                    if (p.isClickable) { clickTarget = p; break }
                    p = p.parent; depth++
                }
                if (!clickTarget.isClickable) {
                    debugLog("findGoCompleteButtons: drop non-clickable node text='${node.text?.toString()?.take(30)}' (no clickable ancestor)")
                    return@mapNotNull null
                }
            }
            // 2. 文本长度过滤（防止规则条款被误识别）
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val buttonText = if (text.isNotEmpty()) text else desc
            if (buttonText.length > 50) {
                debugLog("findGoCompleteButtons: drop long-text node (len=${buttonText.length}, text='${buttonText.take(30)}...')")
                return@mapNotNull null
            }
            // 3. 条款编号开头过滤
            if (Regex("^[0-9]+[、.）)]").containsMatchIn(buttonText)) {
                debugLog("findGoCompleteButtons: drop rule-clause node (text='$buttonText')")
                return@mapNotNull null
            }
            // P0-D（build520 修复）：过滤已完成任务的"已领取"按钮
            // 历史问题（debug_test_20260718_211741.log, build518-93f7a54）：
            // - 第一轮点击"领取"按钮成功领取蚂蚁庄园任务肥料后，第二轮任务列表刷新
            // - 原按钮文字从"领取"变成"已领取"（clickable=true 仍可点击）
            // - findGoCompleteButtons 用 contains 匹配"领取"关键词，"已领取"命中"领取"被误加入列表
            // - processTask 又把"已领取"当成 game task 处理（contextText 含"蚂蚁庄园"），重复无意义流程
            // 修复：精确匹配 buttonText == "已领取" 直接过滤（clickable=true 但任务已完成，不应再点击）
            if (buttonText == "已领取") {
                debugLog("findGoCompleteButtons: drop already-claimed node text='$buttonText' (task completed)")
                return@mapNotNull null
            }
            // build540 修复（用户反馈"'明日7点可领'这种就不要点击了，这是明天的"）：
            // 历史问题：ALIPAY goCompleteTexts 含"领取"（contains 匹配），会匹配到
            // "明日7点可领取"/"明日可领取"/"明天可领取"等未来时间按钮，被当成任务按钮点击。
            // 点击后通常弹窗提示"明日再来"或无响应，浪费一轮任务尝试。
            // 修复：过滤含"明日"/"明天"/"后天"/"次日"等未来时间提示的按钮。
            // 注意：findDirectCollectButtons 已有 !contains("明日") 过滤，
            //       此处同步给 findGoCompleteButtons 加上同样过滤，避免任务列表也误点。
            val futureTimeKeywords = listOf("明日", "明天", "后天", "次日")
            if (futureTimeKeywords.any { buttonText.contains(it) }) {
                debugLog("findGoCompleteButtons: drop future-time node text='$buttonText' (not claimable today)")
                return@mapNotNull null
            }
            // build592 修复（用户反馈"uc极速版芭芭农场，'签到'为什么不点击"）：
            // 历史问题：UC goCompleteTexts 只含"去签到",不含纯"签到"。UC 极速版芭芭农场
            // 任务列表里的签到按钮文字就叫"签到"（不是"去签到"）,不会被 findGoCompleteButtons 找到,
            // 导致签到任务被漏掉。
            // 修复：UC goCompleteTexts 加纯"签到"。但"签到"会误匹配"签到肥料"（装饰性文字,
            // clickable=false,findClickableSelfOrParentInternal fallback 返回原节点）、
            // "已签到"（已完成）、"签到有礼"（标题非按钮）、"每日签到"（标题）等非按钮文字。
            // 这里加精确过滤：当 buttonText 匹配"签到"关键词时,只接受 buttonText=="签到"
            // 或 buttonText=="去签到"/"立即签到"等纯按钮文案,排除其他含"签到"的非按钮文字。
            if (buttonText.contains("签到")) {
                // 允许的签到按钮文案（纯按钮文字）
                val allowedSignInTexts = setOf("签到", "去签到", "立即签到", "马上签到", "补签到")
                if (buttonText !in allowedSignInTexts) {
                    debugLog("findGoCompleteButtons: drop non-button sign-in node text='$buttonText' (only allow pure '签到'/'去签到' etc.)")
                    return@mapNotNull null
                }
            }
            // 4. bounds 合法性过滤（仅过滤非法矩形，不过滤屏幕外按钮，原因见上方注释）
            val rect = android.graphics.Rect()
            clickTarget.getBoundsInScreen(rect)
            if (rect.width() <= 0 || rect.height() <= 0) {
                debugLog("findGoCompleteButtons: drop zero-size node text='$buttonText' bounds=${rect.toShortString()}")
                return@mapNotNull null
            }
            if (clickTarget !== node) {
                debugLog("findGoCompleteButtons: use clickable ancestor for '$buttonText' (original not clickable, ancestor bounds=${rect.toShortString()})")
            }
            clickTarget
        }
        Log.d(TAG, "findGoCompleteButtons: found ${result.size} buttons (raw=${raw.size}, dropped=${raw.size - result.size})")
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

        // 防御性检查：传入的节点必须是一个真实的"去完成/去逛逛"任务按钮。
        // 历史问题：当 taskButton 扫描器把规则条款文本节点（如"3、用户未通过活动指定的入口去完成任务的，无法获得红包权益；"）
        // 误识别为任务按钮时，isPaidTask 会因文本里含"红包"/"充值"等关键词误判为付费任务，
        // 进而触发"全部任务完成"误判（实际 1 个真任务都没做）。这里加双重防御：
        // 1. 节点必须可点击（规则条款节点 clickable=false）
        // 2. 节点文本不能是条款编号开头（"1、"/"2、"/"3、"...）或异常长（>50 字，任务按钮文案一般 <30 字）
        if (!button.isClickable) {
            debugLog("isPaidTask: skip, button not clickable")
            return false
        }
        val buttonText = button.text?.toString()?.trim().orEmpty()
        if (buttonText.length > 50 || Regex("^[0-9]+[、.）)]").containsMatchIn(buttonText)) {
            debugLog("isPaidTask: skip, button text looks like rule/term clause (len=${buttonText.length}, text='$buttonText')")
            return false
        }

        val paidKeywords = listOf(
            "购买", "付款", "充值", "付费", "消费满",   // 基础交易
            "首充", "首单", "首购",                       // 首次交易引导
            "开通会员", "立即开通", "订阅", "续费",       // 会员/订阅类
            "投资", "理财", "保证金", "押金",             // 金融类
            "下单购买", "立即购买",                       // 明确购买（注意："下单得"不含"购买"，浏览任务不受影响）
            "任意下单", "下单领", "下单赢",                // 明确要求下单才能得肥料（注意：不含"下单得"，那是浏览任务）
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
            "对战", "完成1局", "完成一局", "局对战", "打一局",
            // 砸蛋类任务（砸蛋N次/砸金蛋）：需要在小游戏里过关，自动化无法完成，跳过
            "砸蛋", "砸金蛋"
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
        // 用户需求："去逛逛"按钮应该点击进入任务页，而不是走滑动浏览流程
        // 很多"去逛逛"任务是点击进入页面后停留/浏览获取肥料，需要点击进入
        // 因此按钮文字恰好是"去逛逛"时，不走浏览流程，走普通任务点击流程
        val buttonText = button.text?.toString().orEmpty()
        if (buttonText == "去逛逛") {
            debugLog("isBrowseTask: button text is '去逛逛', treat as click task (not browse)")
            return false
        }
        // "下单得奖励"是浏览搜索结果页面的任务，浏览后就能得肥料
        // "发现精选好物"、"搜一搜你心仪得宝贝"、"看严选推荐商品" 需要点击商品并滑动浏览
        // build584: "看一本喜欢的小说"任务需点击小说+上下滑动阅读得肥料,归类为 browse 任务
        // build590: "开始观看得肥料"短剧任务需点击播放视频+等待15秒,归类为 browse 任务
        // 通用浏览关键词 + 平台专属浏览关键词（差异化：淘宝浏览任务最多）
        val browseKeywords = listOf(
            "浏览", "逛逛", "滑动", "看一看", "看商品", "下单得", "搜索",
            "精选好物", "心仪", "严选推荐", "发现精选", "搜一搜",
            "宝贝", "好物", "推荐商品", "发现", "严选",
            // 小说阅读任务（UC 平台"看一本喜欢的小说"）
            "小说", "阅读", "看一本",
            // 短剧观看任务（UC 平台"开始观看得肥料"）
            "短剧", "观看", "看一部"
        ) + currentPlatformConfig().browseTaskKeywords
        val contextText = collectTaskContextText(button)
        val isBrowse = browseKeywords.any { contextText.contains(it) }
        debugLog("isBrowseTask: buttonText='$buttonText', context='$contextText', isBrowse=$isBrowse")
        return isBrowse
    }

    /**
     * build584: 检测当前页面是否是小说阅读任务页
     *
     * 日志 debug_test_20260721_164711.log 显示 UC "看一本喜欢的小说"任务页特征：
     * - 文本极少（text count=2），sample=[开始阅读, 得肥料] 或 [继续阅读, 得肥料]
     * - "开始阅读"（首次进入）/"继续阅读"（非首次）按钮 + "得肥料"奖励提示
     * - 与农场主页区别：无"集肥料"/"施肥"/"芭芭农场"等种植页核心元素
     *
     * @return true 表示当前是小说阅读任务页（需先点"开始阅读"再滑动）
     */
    fun isNovelReadPage(): Boolean {
        val root = rootInActiveWindowSafe() ?: return false
        val allText = collectAllText(root)
        val hasReadBtn = allText.any {
            it.contains("开始阅读") || it.contains("继续阅读")
        }
        val hasFertilizerHint = allText.any {
            it.contains("得肥料") || it.contains("肥料")
        }
        // 排除农场主页：农场主页也有"得肥料"文案,但不会有"开始阅读"/"继续阅读"按钮
        val isFarmHome = allText.any {
            it.contains("集肥料") || it.contains("施肥") || it.contains("芭芭农场")
        }
        val isNovel = hasReadBtn && hasFertilizerHint && !isFarmHome
        if (isNovel) {
            debugLog("isNovelReadPage: YES (hasReadBtn=$hasReadBtn, hasFertilizerHint=$hasFertilizerHint, isFarmHome=$isFarmHome)")
        }
        return isNovel
    }

    /**
     * build584: 查找小说阅读页的"开始阅读"/"继续阅读"按钮
     *
     * @return 按钮节点；找不到返回 null
     */
    fun findNovelReadButton(): AccessibilityNodeInfo? {
        val root = rootInActiveWindowSafe() ?: return null
        // findNodeByText 内部用 contains 匹配，优先"开始阅读"再"继续阅读"
        var btn = findNodeByText(root, "开始阅读")
        if (btn == null) btn = findNodeByText(root, "继续阅读")
        return btn
    }

    /**
     * build590: 检测当前页面是否是短剧观看任务页
     *
     * 用户需求：UC 平台"开始观看得肥料"短剧任务,点击视频播放15秒后退出回主页。
     * 短剧任务页特征：
     * - "开始观看"（首次进入）/"继续观看"（非首次）按钮 + "得肥料"奖励提示
     * - 与农场主页区别：无"集肥料"/"施肥"/"芭芭农场"等种植页核心元素
     * - 与小说阅读页区别：按钮文案是"开始观看"/"继续观看"而非"开始阅读"/"继续阅读"
     *
     * @return true 表示当前是短剧观看任务页（需先点"开始观看"再等待15秒）
     */
    fun isShortDramaPage(): Boolean {
        val root = rootInActiveWindowSafe() ?: return false
        val allText = collectAllText(root)
        val hasWatchBtn = allText.any {
            it.contains("开始观看") || it.contains("继续观看")
        }
        val hasFertilizerHint = allText.any {
            it.contains("得肥料") || it.contains("肥料")
        }
        // 排除农场主页：农场主页也有"得肥料"文案,但不会有"开始观看"/"继续观看"按钮
        val isFarmHome = allText.any {
            it.contains("集肥料") || it.contains("施肥") || it.contains("芭芭农场")
        }
        val isShortDrama = hasWatchBtn && hasFertilizerHint && !isFarmHome
        if (isShortDrama) {
            debugLog("isShortDramaPage: YES (hasWatchBtn=$hasWatchBtn, hasFertilizerHint=$hasFertilizerHint, isFarmHome=$isFarmHome)")
        }
        return isShortDrama
    }

    /**
     * build590: 查找短剧观看任务页的"开始观看"/"继续观看"按钮
     *
     * @return 按钮节点；找不到返回 null
     */
    fun findShortDramaPlayButton(): AccessibilityNodeInfo? {
        val root = rootInActiveWindowSafe() ?: return null
        // findNodeByText 内部用 contains 匹配，优先"开始观看"再"继续观看"
        var btn = findNodeByText(root, "开始观看")
        if (btn == null) btn = findNodeByText(root, "继续观看")
        return btn
    }

    /**
     * build586: 查找主页上的跨平台跳转按钮（"去支付宝农场领肥料"/"去淘宝农场领肥料"等）
     *
     * 用户需求（debug_test_20260721_173039.log, build585, UC 平台 line 25）：
     * UC 主页底部有"去支付宝农场领肥料"按钮（clickable=false，bounds=[255,3245][694,2509]），
     * 点击会跳转到支付宝芭芭农场领取跨平台奖励。需像 processTask 的 cross-platform 任务一样处理
     * （跳转→领奖励→返回原平台）。
     *
     * @return 跨平台跳转按钮节点；找不到返回 null
     */
    fun findCrossPlatformJumpButton(): AccessibilityNodeInfo? {
        val root = getRootInFarmApp() ?: return null
        // 跨平台跳转按钮文案特征："去XX农场领肥料"/"去XX农场"等
        val jumpKeywords = listOf(
            "去支付宝农场", "去淘宝农场", "去UC农场",
            "支付宝农场领", "淘宝农场领", "UC农场领",
            "去蚂蚁庄园", "蚂蚁庄园领"
        )
        val result = mutableListOf<AccessibilityNodeInfo>()
        val seen = HashSet<Int>()
        collectNodesByText(root, jumpKeywords, result, seen)
        if (result.isEmpty()) return null
        // build588 修复（debug_test_20260721_184040.log, build587 line 25-33）：
        // 历史问题：UC H5 页面"去支付宝农场领肥料"节点 bounds=[255,3042][694,2509]
        // （top=3042 > bottom=2509 异常），performClickSafe 回退到 ancestor bounds 中心
        // (600.5, 1840.5) 点击，落在中国移动广告上（com.greenpoint.android.mc10086.activity），
        // 导致 SWITCHING_PLATFORM 失败 + currentPlatform=UNKNOWN，整个流程崩溃。
        // 修复：跳过 bounds 异常节点（top >= bottom 或 left >= right 或宽高<=0），
        // 宁可不点击也不要点错位置触发广告跳转。
        val valid = result.firstOrNull { node ->
            val r = android.graphics.Rect().also { node.getBoundsInScreen(it) }
            r.width() > 0 && r.height() > 0 && r.top < r.bottom && r.left < r.right
        }
        if (valid == null) {
            val sample = result.first()
            val sampleRect = android.graphics.Rect().also { sample.getBoundsInScreen(it) }
            debugLog("findCrossPlatformJumpButton: all ${result.size} nodes have invalid bounds (top>=bottom or zero size), e.g. text='${sample.text}' bounds=${sampleRect.toShortString()}, skip to avoid misclick on ad")
            return null
        }
        val chosenText = valid.text?.toString().orEmpty()
        val chosenRect = android.graphics.Rect().also { valid.getBoundsInScreen(it) }
        debugLog("findCrossPlatformJumpButton: found text='$chosenText' bounds=${chosenRect.toShortString()} clickable=${valid.isClickable}")
        return valid
    }

    /**
     * build586: 检测跨平台跳转按钮的目标平台
     *
     * @param text 按钮文本
     * @return 目标平台；无法识别返回 null
     */
    fun detectCrossPlatformJumpTarget(text: String): Platform? {
        return when {
            text.contains("支付宝") || text.contains("蚂蚁庄园") -> Platform.ALIPAY
            text.contains("淘宝") -> Platform.TAOBAO
            text.contains("UC") -> Platform.UC
            else -> null
        }
    }

    /**
     * build585: 查找小说列表页的一部小说节点（点击进入小说内容页）
     *
     * 用户需求："需要点击一部小说进入，停留15秒上下滑动"
     * 小说列表页特征：多本小说卡片排列,每个卡片有书名（如"都市之最强仙帝"等）。
     * 由于小说列表页是 H5 页面,无障碍树可能不暴露书名文本节点,
     * 策略：找页面中部（y: 30%-70%）的可点击节点,优先文本节点,其次任意可点击节点。
     *
     * @return 小说节点；找不到返回 null
     */
    fun findNovelBookNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindowSafe() ?: return null
        val metrics = resources.displayMetrics
        val screenHeight = metrics.heightPixels
        val centerYMin = screenHeight * 0.30f
        val centerYMax = screenHeight * 0.70f

        // 收集所有可点击节点,筛选在屏幕中部区域的
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectClickableNodesInYRange(root, centerYMin, centerYMax, candidates)
        if (candidates.isEmpty()) {
            debugLog("findNovelBookNode: no clickable node in y range [$centerYMin, $centerYMax], screenH=$screenHeight")
            return null
        }
        // 优先选有文本的节点（书名）,其次选第一个
        val withText = candidates.firstOrNull {
            !it.text?.toString().isNullOrBlank()
        }
        val chosen = withText ?: candidates.first()
        val chosenText = chosen.text?.toString().orEmpty()
        val chosenRect = android.graphics.Rect().also { chosen.getBoundsInScreen(it) }
        debugLog("findNovelBookNode: found ${candidates.size} candidates, chosen text='$chosenText' bounds=${chosenRect.toShortString()}")
        return chosen
    }

    /** 递归收集在 y 范围内的可点击节点 */
    private fun collectClickableNodesInYRange(
        node: AccessibilityNodeInfo,
        yMin: Float,
        yMax: Float,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (node.isClickable) {
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            val centerY = (rect.top + rect.bottom) / 2f
            if (centerY in yMin..yMax) {
                out.add(node)
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectClickableNodesInYRange(child, yMin, yMax, out)
        }
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
     *
     * 历史问题（P0-1，build513 修复）：
     * findGoCompleteButtons 返回 clickable 父节点（UC 平台"去完成"文本节点不可点击，找父节点）。
     * 旧代码无脑向上 2 层，当 button 已是 clickable 父节点（任务行容器本身）时，
     * button.parent.parent 是任务列表容器（RecyclerView/ListView），
     * collectTextRecursive 会收集整个任务列表所有行的文本，
     * 导致 isPaidTask/isGameTask/skipTaskTexts 误判相邻任务。
     *
     * 修复：根据 button 的 bounds 高度自适应向上层数。
     * - 任务行高度通常 100~300px（描述+按钮）
     * - 如果 button 高度 < 400px，说明 button 就是任务行容器，向上 1 层即可
     * - 如果 button 高度 >= 400px 或向上 1 层容器高度 > 600px，说明已超出任务行，
     *   退化到只收集 button 自身子树（至少能拿到按钮内文本）
     */
    fun collectTaskContextText(button: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        val btnRect = android.graphics.Rect()
        button.getBoundsInScreen(btnRect)
        val btnHeight = btnRect.height()

        // 自适应选择容器：向上找第一个"合理高度"的祖先（任务行高度 < 600px）
        // 但不能只看高度——纯"去完成"按钮 clickTarget 高度 82px，
        // 向上找父节点高度也 82px（按钮容器），再向上父节点可能仍是窄的容器，
        // 导致找到的容器只包含按钮自身，没收集到任务标题。
        // 策略：先向上找"有兄弟节点含任务描述文本"的祖先（任务行），
        // 兜底再按高度找。
        var container: AccessibilityNodeInfo? = null

        // 策略1：向上找首个"含多个子节点的兄弟节点"或"高度 100~500px 的祖先"
        var p: AccessibilityNodeInfo? = button.parent
        var depth = 0
        while (p != null && depth < 4) {
            val pRect = android.graphics.Rect()
            p.getBoundsInScreen(pRect)
            val pHeight = pRect.height()
            val pWidth = pRect.width()
            // 任务行容器特征：高度 100~500px，宽度接近屏幕宽（> 500px），
            // 含多个子节点（任务描述 + 按钮）
            // 列表容器高度通常 > 800px
            if (pHeight in 100..600 && pWidth > 500 && p.childCount >= 2) {
                container = p
                break
            }
            // 如果父节点太高（> 600px），说明已超出任务行，停止向上找
            if (pHeight > 600) break
            p = p.parent
            depth++
        }

        // 策略2兜底：如果策略1没找到，向上找第一个高度 > 当前按钮的祖先（任务行通常比按钮高）
        if (container == null) {
            var p2: AccessibilityNodeInfo? = button.parent
            var depth2 = 0
            while (p2 != null && depth2 < 3) {
                val p2Rect = android.graphics.Rect()
                p2.getBoundsInScreen(p2Rect)
                val p2Height = p2Rect.height()
                if (p2Height > btnHeight && p2Height <= 600) {
                    container = p2
                    break
                }
                if (p2Height > 600) break
                p2 = p2.parent
                depth2++
            }
        }

        // 最终兜底：如果都没找到，用 button 自己作为容器（收集子树文本）
        val effectiveContainer = container ?: button
        collectTextRecursive(effectiveContainer, sb, maxDepth = 4)
        val result = sb.toString()
        debugLog("collectTaskContextText: result='$result', buttonText='${button.text}', btnHeight=$btnHeight, containerHeight=${container?.let { android.graphics.Rect().apply { it.getBoundsInScreen(this) }.height() } ?: -1}")
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
     * - 场景白名单：默认只在 AD_PLAYING/AD_ENDED/REWARD_POPUP 场景才返回按钮（聪明思考）
     *   避免在农场页/陷阱页误点"关闭"导致退出农场或掉入陷阱（如落地页的"关闭交易"按钮）
     * @param platformTexts 平台特有的广告关闭按钮文本（如 UC 的"跳过广告"/"关闭广告"）
     * @param enforceSceneWhitelist 是否强制场景白名单（默认 true）
     * @return 按钮节点或null
     */
    fun findAdCloseButton(
        platformTexts: List<String> = emptyList(),
        enforceSceneWhitelist: Boolean = true
    ): AccessibilityNodeInfo? {
        // 场景白名单：只在广告播放中/已结束/奖励弹窗场景才允许点击关闭按钮
        // 聪明思考：陷阱页面的"关闭"按钮可能是"关闭交易"/"关闭订单"等诱导文案，
        // 农场主页的"关闭"按钮会退出农场，都不应点击
        if (enforceSceneWhitelist && !isCloseAdAllowedScene()) {
            debugLog("findAdCloseButton: scene not allowed for close (scene=${identifyCurrentScene()}), skip")
            return null
        }
        val root = rootInActiveWindowSafe() ?: return null
        // 陷阱按钮黑名单（来自平台配置）：避免把"立即下载/去购买/查看详情"等诱导按钮误识别为关闭
        val trapTexts = currentPlatformConfig().adInstallButtonTexts
        // Honor/Android 系统通知栏控件黑名单：通知栏下拉后会出现"手电筒/WiFi/蓝牙/飞行模式"等开关，
        // 这些开关的 desc 可能是"手电筒已关闭"等含"关闭"字样 → 被误识别为广告关闭按钮 → 误点击。
        // 同时它们的 bounds 常在屏幕底部（top=2792 > bottom=2664 这种非法矩形也是通知栏控件的特征）。
        // 黑名单：节点 text/desc 含以下关键词时直接排除。
        val systemControlKeywords = listOf(
            "手电筒", " flashlight", "蓝牙", "bluetooth", "飞行模式", "airplane mode",
            "wifi", "热点", "hotspot", "自动旋转", "auto rotate", "请勿打扰", "do not disturb"
        )
        // 检查节点是否是诱导按钮（按子串匹配，如"领取优惠"含"领取"会被误识别为奖励按钮，
        // 故严格按整段文本/desc 检查是否包含陷阱关键词）
        fun isTrapNode(node: AccessibilityNodeInfo): Boolean {
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val combined = text + desc
            return trapTexts.any { trap -> combined.contains(trap) }
        }
        // 检查节点是否是系统通知栏控件（手电筒/WiFi 等开关）
        fun isSystemControlNode(node: AccessibilityNodeInfo): Boolean {
            val text = node.text?.toString().orEmpty().lowercase()
            val desc = node.contentDescription?.toString().orEmpty().lowercase()
            val combined = "$text $desc"
            return systemControlKeywords.any { kw -> combined.contains(kw) }
        }
        // 检查 bounds 是否合法（top > bottom、width <= 0、height <= 0 都是非法矩形）
        // Honor 通知栏的控件 bounds 常出现 top > bottom 的非法矩形，必须过滤
        fun hasInvalidBounds(node: AccessibilityNodeInfo): Boolean {
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() <= 0 || rect.height() <= 0 || rect.top > rect.bottom) {
                debugLog("findAdCloseButton: drop invalid bounds node text='${node.text?.toString()?.take(20)}' desc='${node.contentDescription?.toString()?.take(20)}' bounds=${rect.toShortString()}")
                return true
            }
            return false
        }
        // 综合校验节点：陷阱 / 系统控件 / 非法 bounds 都排除
        fun isValidCloseNode(node: AccessibilityNodeInfo): Boolean {
            if (isTrapNode(node)) return false
            if (isSystemControlNode(node)) {
                debugLog("findAdCloseButton: drop system control node text='${node.text?.toString()?.take(20)}' desc='${node.contentDescription?.toString()?.take(20)}'")
                return false
            }
            if (hasInvalidBounds(node)) return false
            return true
        }
        // 优先按平台特有关闭文本查找（更精确，避免误匹配），且排除诱导按钮
        for (kw in platformTexts) {
            val node = findNodeByText(root, kw)
            if (node != null && isValidCloseNode(node)) {
                Log.d(TAG, "findAdCloseButton: found by platform text='$kw'")
                return node
            }
        }
        // 通用关闭按钮文本（精确匹配关闭类关键词，避免"立即关闭下载"等诱导文案子串误匹配）
        val keywords = listOf("×", "关闭", "close", "跳过", "skip", "关闭广告", "跳过广告", "跳过视频")
        for (kw in keywords) {
            val node = findNodeByText(root, kw)
            if (node != null && isValidCloseNode(node)) {
                Log.d(TAG, "findAdCloseButton: found by text='$kw'")
                return node
            }
        }
        // 文字没找到，尝试查找右上角区域的可点击小图标（游戏/广告的关闭按钮通常是右上角X图标）
        val closeIcon = findTopRightClickableIcon(root, isRight = true)
        if (closeIcon != null && isValidCloseNode(closeIcon)) {
            debugLog("findAdCloseButton: found top-right close icon")
            return closeIcon
        }
        return null
    }

    /**
     * 检测当前广告页是否为"点击商品，领取奖励"类型激励视频
     *
     * 场景（debug_test_20260719_153945.log, build558）：
     * - UC 集肥料点击后弹激励视频（穿山甲/汇川）
     * - 顶部出现"点击商品，领取奖励"提示文字（clickable=false）
     * - 用户必须主动点击广告中的商品（图片/卡片）才能触发奖励
     * - 不点击商品的话广告结束不发肥料
     *
     * @return true 表示当前是"点击商品,领取奖励"广告
     */
    fun isClickProductAd(): Boolean {
        val root = rootInActiveWindowSafe() ?: return false
        val allText = collectAllText(root)
        return allText.any { it.contains("点击商品") }
    }

    /**
     * 在"点击商品，领取奖励"广告页中查找可点击的商品节点
     *
     * 选择策略：
     * - 必须是 isClickable=true 的节点（广告商品通常是可点击的卡片/图片）
     * - 排除陷阱按钮（立即下载/立即购买/查看详情 等 adInstallButtonTexts）
     * - 排除关闭/跳过按钮（×/关闭/跳过/skip 等）
     * - 排除提示文字本身（含"点击商品"的节点）
     * - bounds 必须合法（width>0, height>0, top<=bottom）
     * - 优先屏幕中部节点（y 在 500~2400 区域，避免点到顶部提示或底部导航）
     *
     * @return 商品节点或 null
     */
    fun findAdProductNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindowSafe() ?: return null
        val trapTexts = currentPlatformConfig().adInstallButtonTexts
        val closeTexts = listOf("×", "关闭", "close", "跳过", "skip", "关闭广告", "跳过广告", "跳过视频", "领取奖励")

        val candidates = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo) {
            if (node.isClickable) {
                val text = node.text?.toString().orEmpty()
                val desc = node.contentDescription?.toString().orEmpty()
                val combined = "$text $desc"
                // 排除陷阱按钮（立即下载/立即购买 等）
                if (trapTexts.any { combined.contains(it) }) {
                    return
                }
                // 排除关闭/跳过/领取奖励按钮
                if (closeTexts.any { combined.contains(it, ignoreCase = true) }) {
                    return
                }
                // 排除提示文字本身
                if (combined.contains("点击商品")) return
                // bounds 合法性
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() <= 0 || rect.height() <= 0 || rect.top > rect.bottom) return
                // 优先屏幕中部的可点击节点
                if (rect.top >= 500 && rect.bottom <= 2400) {
                    candidates.add(node)
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { walk(it) }
            }
        }
        walk(root)
        return candidates.firstOrNull().also {
            if (it != null) {
                val rect = android.graphics.Rect()
                it.getBoundsInScreen(rect)
                debugLog("findAdProductNode: found clickable product node bounds=${rect.toShortString()}")
            } else {
                debugLog("findAdProductNode: no clickable product node found")
            }
        }
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
        // 使用真实屏幕尺寸（适配所有设备，替代硬编码 1200x2664）
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
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
            debugLog("findTopCornerIcon: found ${if (isRight) "right" else "left"} icon at ${rect.toShortString()} (screen=${screenW}x${screenH})")
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
     * 检测当前是否有第三方 App 覆盖在农场 App 上方
     *
     * 场景（debug_test_20260719_152545.log, build557-1a9b06f）：
     * - UC 平台集肥料按钮点击后,UC 浏览器内的 H5 跳转拉起了美团/中国移动 10086 等第三方 App
     * - 这些 App 以 overlay 形式覆盖在 UC 浏览器上方
     * - rootInActiveWindow 返回第三方 App 的 root,但 currentActivityName 仍是 UC 的 Activity
     *   (com.uc.browser.innerucmobile,通过 isFarmAppInForeground 的 activity 兜底被判定为"在农场 App")
     * - runNavigating 的 else 分支调用 navigateToFarm() → stepClickFarmTab
     *   只在当前页找"芭芭农场"tab,找不到就 fallback 到 stepClickFarmTabByGesture(淘宝专用,UC 无效)
     * - 最终超时停止,任务失败
     *
     * 修复策略：
     * - 检测 activeRootPkg 不是农场包名、不是 systemui/android、不是 launcher、不是 bbncbot
     * - 返回该包名,由调用方 forceKillApp 结束该 App,使其从 UC 浏览器上消失
     * - UC 浏览器重新成为活动窗口后,正常导航流程继续
     *
     * @return 第三方 App 的包名,无 overlay 时返回 null
     */
    fun getThirdPartyOverlayPkg(): String? {
        val activeRootPkg = rootInActiveWindowSafe()?.packageName?.toString().orEmpty()
        if (activeRootPkg.isEmpty()) return null
        val cfg = currentPlatformConfig()
        // 农场 App 自身或内部包前缀: 不是 overlay
        val isFarmPkg = activeRootPkg in cfg.packageNames ||
            cfg.internalPackagePrefixes.any { activeRootPkg.startsWith(it) }
        if (isFarmPkg) return null
        // 系统/桌面/bbncbot 自身: 不是 overlay
        // (systemui 弹窗走 isOnFarmPage=false 的 pressBack 分支;launcher 走等待分支;
        //  bbncbot 是本应用,出现在 ROOT 时说明用户切到设置界面,不应 kill)
        val isSystemPkg = activeRootPkg == "com.android.systemui" ||
            activeRootPkg == "android" ||
            activeRootPkg == "com.bbncbot" ||
            activeRootPkg.endsWith(".launcher")
        if (isSystemPkg) return null
        // 其他用户 App(美团/10086/微信等): 是 overlay
        debugLog("getThirdPartyOverlayPkg: detected overlay pkg=$activeRootPkg (platform=$currentPlatform)")
        return activeRootPkg
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
                text.contains("充值金币") || text.contains("充值钻石") ||
                // build526（用户反馈）：进入其他小程序玩游戏时弹出的"完成订单"类干扰弹窗
                // 这类弹窗诱导用户完成订单（购买），应识别为陷阱页并关闭
                text.contains("完成订单") || text.contains("提交订单") ||
                text.contains("去下单") || text.contains("立即下单") ||
                text.contains("确认下单") || text.contains("去结算") ||
                text.contains("立即结算") || text.contains("确认结算")
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
            // build526（用户反馈）：进入其他小程序玩游戏时弹出的"完成订单"类干扰弹窗
            // 这类弹窗的关闭按钮可能是"暂不下单"/"暂不结算"
            "暂不下单", "暂不结算", "暂不提交",
            "不买了", "再想想", "再考虑", "残忍拒绝", "残忍离别",
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
        //    注意：必须传 enforceSceneWhitelist=false，因为本方法专门处理 TRAP_LANDING 场景，
        //    而 isCloseAdAllowedScene 在 TRAP_LANDING 场景返回 false（默认白名单只允许广告场景关闭）
        val closeBtn = findAdCloseButton(platformCloseTexts, enforceSceneWhitelist = false)
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
     * 检测广告复看陷阱（"再看一个"/"加倍领取"/"看视频翻倍"诱导继续看广告）
     *
     * 广告设计者意图：广告结束后弹出"再看一个视频可加倍领取"/"看视频翻倍奖励"等诱导弹窗，
     * 利用用户"贪多"心理诱导继续观看更多广告，获取更多广告收益。
     * 这类弹窗通常有：
     * - 诱导按钮："再看一个"/"加倍领取"/"翻倍领取"/"看视频翻倍"
     * - 关闭按钮："不了"/"放弃"/"关闭"/"暂不"（较小或不显眼）
     *
     * 策略：检测到复看陷阱时，优先点关闭类按钮，绝不点诱导按钮。
     * 若无关闭按钮，按返回退出。
     *
     * @return true 表示检测到复看陷阱并已处理（点击关闭或按返回）
     */
    fun closeAdReplayTrap(): Boolean {
        val root = rootInActiveWindowSafe() ?: return false
        val allText = collectAllText(root)
        // 复看陷阱诱导按钮关键词
        val replayTrapKeywords = listOf(
            "再看一个", "再看看", "再看视频", "再看一次",
            "加倍领取", "翻倍领取", "翻倍奖励", "双倍奖励",
            "看视频翻倍", "看视频加倍", "看广告翻倍",
            "立即翻倍", "立即加倍", "领取双倍",
            "再看15秒", "再看30秒", "再看视频领"
        )
        val hasReplayTrap = allText.any { text ->
            replayTrapKeywords.any { kw -> text.contains(kw) }
        }
        if (!hasReplayTrap) return false
        debugLog("closeAdReplayTrap: replay trap detected, looking for close button")
        // 优先点关闭类按钮（与 closeAdInstallPopup 类似，但针对复看场景）
        val closeKeywords = listOf(
            "不了", "不要", "放弃", "暂不",
            "关闭", "返回", "取消",
            "下次再说", "以后再说", "残忍拒绝"
        )
        // 排除诱导按钮（避免"放弃加倍"被"放弃"匹配但实际是诱导）
        val trapTexts = currentPlatformConfig().adInstallButtonTexts
        for (kw in closeKeywords) {
            val node = findNodeByText(root, kw)
            if (node != null) {
                val text = node.text?.toString().orEmpty()
                val desc = node.contentDescription?.toString().orEmpty()
                // 排除诱导按钮
                if (trapTexts.any { trap -> (text + desc).contains(trap) }) continue
                // 排除复看诱导按钮（如"放弃翻倍"可能含"放弃"但实际是诱导）
                if (replayTrapKeywords.any { trap -> (text + desc).contains(trap) }) continue
                debugLog("closeAdReplayTrap: found close button by text='$kw', clicking")
                if (performClickSafe(node)) return true
            }
        }
        // 无关闭按钮，按返回退出
        debugLog("closeAdReplayTrap: no close button found, pressing back")
        pressBack()
        return true
    }

    /**
     * 检测虚假关闭按钮（位置异常或尺寸过大的"关闭"按钮，可能是诱导跳转）
     *
     * 广告设计者意图：在广告页面放置一个尺寸很大、位置居中的"关闭"按钮，
     * 用户以为是关闭广告，实际点击后跳转到广告主落地页或应用商店。
     * 真正的关闭按钮通常是右上角的小图标（30-150px），而非居中大按钮。
     *
     * 本方法检测 findAdCloseButton 找到的节点是否可疑：
     * - 尺寸过大（宽度 > 屏幕30%）→ 可疑
     * - 位置居中（非右上角区域）→ 可疑
     *
     * @param closeBtn 待检测的关闭按钮节点
     * @return true 表示是虚假关闭按钮（不应点击）
     */
    fun isFakeCloseButton(closeBtn: AccessibilityNodeInfo): Boolean {
        val rect = android.graphics.Rect()
        closeBtn.getBoundsInScreen(rect)
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val w = rect.width()
        val h = rect.height()
        // 检查1：尺寸过大（宽度 > 屏幕30% 或 高度 > 屏幕10%）
        // 真正的关闭按钮是小图标，不会占屏幕大比例
        if (w > screenW * 0.3f || h > screenH * 0.1f) {
            debugLog("isFakeCloseButton: YES (size too large: ${w}x${h}, screen=${screenW}x${screenH})")
            return true
        }
        // 检查2：位置居中（非右上角区域）
        // 真正的关闭按钮在右上角（right > 屏幕70%，top < 屏幕15%）
        val isRightTop = rect.left > screenW * 0.7f && rect.top < screenH * 0.15f
        if (!isRightTop) {
            // 非右上角，但可能是底部"跳过"按钮（也合法）
            val isBottom = rect.top > screenH * 0.85f
            if (!isBottom) {
                debugLog("isFakeCloseButton: YES (position not right-top or bottom: ${rect.toShortString()})")
                return true
            }
        }
        return false
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
     * - 场景白名单：默认只在 AD_ENDED/REWARD_POPUP 场景才返回按钮（聪明思考）
     *   避免广告播放中的"领取优惠"诱导按钮被误点击
     * @param enforceSceneWhitelist 是否强制场景白名单（默认 true）
     * @return 按钮节点或null
     */
    fun findClaimRewardButton(enforceSceneWhitelist: Boolean = true): AccessibilityNodeInfo? {
        // 场景白名单：只在广告已结束/奖励弹窗/签到页面场景才允许点击领取按钮
        // 聪明思考：广告播放中出现的"领取"文字几乎都是诱导按钮，不应点击
        if (enforceSceneWhitelist && !isClaimRewardAllowedScene()) {
            debugLog("findClaimRewardButton: scene not allowed for claim (scene=${identifyCurrentScene()}), skip")
            return null
        }
        val root = rootInActiveWindowSafe() ?: return null
        val trapTexts = currentPlatformConfig().adInstallButtonTexts
        // 签到专属关键词放最前：签到页面的"立即签到"/"签到领取"按钮需要优先匹配，
        // 避免"领取"子串先命中无关文案。签到按钮不是诱导按钮，无需 trapTexts 过滤。
        // 通用关键词"领取奖励"/"领取"/"确定"/"知道了"用于广告结束/奖励弹窗。
        val keywords = listOf(
            "立即签到", "签到领取", "签到",
            "领取奖励", "领取", "确定", "知道了"
        )
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
     * 查找"肥料图标 + 领取按钮"组合，绕过场景白名单直接领取
     *
     * 用户需求：如果有带肥图标，并有"领取"的按钮，可以直接点击领取。
     *
     * 设计原理：
     * - 肥料图标/文案 + 领取按钮 = 强信号，几乎可确定是肥料奖励弹窗
     * - 比场景识别（identifyCurrentScene）更直接可靠
     * - 绕过 isClaimRewardAllowedScene 白名单，避免被场景识别失败卡住
     *
     * 检测条件（全部满足才返回）：
     * 1. 页面有"领取"按钮（复用 findClaimRewardButtonExact，已排除陷阱按钮）
     * 2. 页面有肥料相关文案（肥料图标代理信号）：
     *    "肥料"/"得肥"/"集肥"/"领肥"/"肥"等
     * 3. 排除任务列表场景（任务列表有"做任务集肥料"/"去完成"等特征，不是领取弹窗）
     * 4. 排除农场主页（避免误点农场主页的"集肥料"按钮）
     * 5. 排除广告播放中（广告播放中的"领取"几乎都是诱导按钮）
     *
     * @return 领取按钮节点；不满足条件返回 null
     */
    fun findFertilizerClaimButton(): AccessibilityNodeInfo? {
        val root = rootInActiveWindowSafe() ?: return null

        // 1. 排除农场主页（避免误点"集肥料"按钮）
        if (isOnFarmPage()) {
            debugLog("findFertilizerClaimButton: on farm page, skip (avoid clicking 集肥料 button)")
            return null
        }

        // 2. 排除广告播放中（广告播放中的"领取"几乎都是诱导按钮）
        val scene = identifyCurrentScene()
        if (scene == PageScene.AD_PLAYING) {
            debugLog("findFertilizerClaimButton: ad playing, skip (领取 button likely trap)")
            return null
        }

        val allText = collectAllText(root)

        // 3. 排除任务列表场景（任务列表有"做任务集肥料"/"任务列表"/"去完成"等特征）
        //    任务列表里虽然有很多"得肥料"文案，但按钮是"去完成"不是"领取"
        val taskListKeywords = listOf(
            "做任务集肥料", "关闭做任务集肥料弹窗", "任务列表",
            "去完成", "去逛逛", "去分享", "去邀请"
        )
        val isTaskList = allText.any { text ->
            taskListKeywords.any { kw -> text.contains(kw) }
        }
        if (isTaskList) {
            debugLog("findFertilizerClaimButton: task list detected, skip (not a claim popup)")
            return null
        }

        // 4. 必须有肥料相关文案（肥料图标代理信号）
        //    注意：用广泛的"肥"字匹配，覆盖"肥料"/"得肥"/"集肥"/"领肥"等所有变体
        val hasFertilizerText = allText.any { text ->
            text.contains("肥")
        }
        if (!hasFertilizerText) {
            debugLog("findFertilizerClaimButton: no fertilizer text, skip")
            return null
        }

        // 5. 查找"领取"按钮（复用 findClaimRewardButtonExact，已排除陷阱按钮）
        //    enforceSceneWhitelist=false 绕过场景白名单（本函数已自行做场景排除）
        val claimBtn = findClaimRewardButtonExact()
        if (claimBtn == null) {
            debugLog("findFertilizerClaimButton: has fertilizer text but no claim button, skip")
            return null
        }

        val claimText = claimBtn.text?.toString().orEmpty()
        Log.i(TAG, "findFertilizerClaimButton: found fertilizer claim button (text='$claimText', scene=$scene)")
        debugLog("findFertilizerClaimButton: fertilizer text + claim button '$claimText' detected (scene=$scene), bypassing scene whitelist")
        return claimBtn
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
     * 用户反馈：显示了"肥料已发放"为啥还在滑动 → 增强检测：
     * 1. 扩展关键词覆盖更多变体（"肥料已经发放"/"肥料发放成功"/"已发放肥料"等）
     * 2. 遍历所有 windows（不仅 rootInActiveWindow），覆盖 Toast 和独立弹窗窗口
     *
     * 注意：与 isTaskCompletePage 的区别
     * - isTaskCompletePage 故意排除了"获得肥料"关键词（进行中页面也会显示"已获得肥料 xxx"）
     * - 本方法检测"已获得肥料"（纯粹的完成提示，无数字后缀）
     * - 若文案是"已获得肥料 500"等带数字的进行中状态，不会匹配（contains 只匹配子串，
     *   但 "已获得肥料" 是 "已获得肥料 500" 的子串，会匹配 → 需调用方结合上下文判断）
     *
     * 识别文本示例：
     * - "肥料已发放" / "肥料已发放到账户" / "肥料已发放成功" / "肥料已经发放"
     * - "肥料发放成功" / "已发放肥料" / "肥料领取成功"
     * - "奖励已发放" / "奖励已到账" / "奖励发放成功"
     * - "已获得肥料"（浏览任务完成提示）
     *
     * @return true 表示当前页面是肥料到账提示页
     */
    fun isFertilizerGrantedPage(): Boolean {
        // 遍历所有 windows 收集文本（覆盖 Toast 和独立弹窗窗口）
        // rootInActiveWindow 只拿活动窗口根节点，Toast/独立弹窗在单独窗口层会漏检
        val allTexts = mutableListOf<List<String>>()
        try {
            val allWindows = windows
            for (w in allWindows) {
                val root = w.root ?: continue
                allTexts.add(collectAllText(root))
            }
        } catch (e: Exception) {
            // windows 遍历失败时兜底用 rootInActiveWindow
            val root = rootInActiveWindowSafe() ?: return false
            allTexts.add(collectAllText(root))
        }
        if (allTexts.isEmpty()) {
            val root = rootInActiveWindowSafe() ?: return false
            allTexts.add(collectAllText(root))
        }

        // 肥料到账关键词（覆盖各种变体）
        // "已发放"/"发放成功"/"领取成功"/"已到账" + "肥料"/"奖励" 的组合
        val grantedKeywords = listOf(
            // "肥料" + 到账动词
            "肥料已发放", "肥料已经发放", "肥料发放成功", "已发放肥料",
            "肥料已到账", "肥料已经到账", "肥料到账成功", "肥料领取成功",
            "肥料已领取", "肥料领取完成",
            // "奖励" + 到账动词
            "奖励已发放", "奖励已经发放", "奖励发放成功",
            "奖励已到账", "奖励已经到账", "奖励到账成功",
            "奖励领取成功", "奖励已领取",
            // 其他完成提示
            "施肥成功", "领取成功"
        )

        for (allText in allTexts) {
            // 1. 匹配明确的到账关键词
            val matched = allText.any { text ->
                grantedKeywords.any { kw -> text.contains(kw) }
            }
            if (matched) {
                debugLog("isFertilizerGrantedPage: YES (matched keyword), sample=${allText.take(5)}")
                return true
            }
            // 2. "已获得肥料" 需精确匹配：排除"已获得肥料 500"等带数字的进行中状态
            // 只匹配纯粹的"已获得肥料"（任务完成提示）
            val gained = allText.any { text ->
                text.contains("已获得肥料") && !text.matches(Regex(".*已获得肥料\\s*\\d+.*"))
            }
            if (gained) {
                debugLog("isFertilizerGrantedPage: YES (已获得肥料 without number), sample=${allText.take(5)}")
                return true
            }
        }
        return false
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
        // build546 修复：排除"合种/帮帮种多人施肥当前进度X，总进度Y 得Z肥，需N"这种进度文本节点。
        // 历史问题（debug_test_20260719_125715.log, build545-2da7d39）：
        //   12:56:26.272 performClickSafe: text=' 合种/帮帮种多人施肥当前进度0，总进度1 得500肥，需2'
        //     desc='' bounds=[72,4038][890,2666] clickable=false
        //   performClickSafe 失败 19 次（clickCount=1 到 19），每次都点这个 progress 节点。
        // 原因：该进度文本含"施肥"二字，被 findNodeByText 匹配为"施肥按钮"。
        // 修复：跳过含"进度"/"总进度"/"当前进度"/"得X肥"/"需N"等进度描述特征的节点。
        //       同时跳过 clickable=false 和 zero-size bounds 的节点。
        val progressKeywords = listOf("进度", "总进度", "当前进度", "得", "肥，需", "需", "合种", "帮帮种")
        // 收集所有含"施肥"的候选节点，按优先级选择：clickable=true > bounds 合法 > 长度短
        data class Candidate(val node: AccessibilityNodeInfo, val text: String, val clickable: Boolean, val boundsValid: Boolean, val textLen: Int)
        val candidates = mutableListOf<Candidate>()
        fun walk(node: AccessibilityNodeInfo) {
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            for (s in listOf(text, desc)) {
                if (s.contains("施肥")) {
                    val isProgress = progressKeywords.any { s.contains(it) }
                    if (!isProgress) {
                        val rect = android.graphics.Rect()
                        node.getBoundsInScreen(rect)
                        val boundsValid = rect.width() > 0 && rect.height() > 0 && rect.top < rect.bottom
                        candidates.add(Candidate(node, s, node.isClickable, boundsValid, s.length))
                        break
                    }
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { walk(it) }
            }
        }
        walk(root)
        if (candidates.isEmpty()) {
            Log.d(TAG, "findFertilizeButton: not found (no non-progress 施肥 node)")
            return null
        }
        // 优先级：clickable=true > bounds 合法 > 文本短（"施肥"按钮文本通常很短）
        val selected = candidates.sortedWith(
            compareByDescending<Candidate> { it.clickable }
                .thenByDescending { it.boundsValid }
                .thenBy { it.textLen }
        ).first()  // candidates 已确认非空（上方 isEmpty 检查）
        // build557 修复（debug_test_20260719_125715.log, build545-2da7d39）：
        // 历史问题：候选节点可能 bounds 倒置/越界（如 [72,4038][890,2666]，top>bottom 且
        // top 超出屏幕高度），这是 WebView 内部内容坐标，dispatchGestureClickWithWebViewFix
        // 无法转换为屏幕坐标，performClickSafe 会连续 19 次 "all methods failed" 浪费时间。
        // 修复：不返回 boundsValid=false 的节点，让调用方走坐标兜底或重新导航。
        if (!selected.boundsValid) {
            debugLog("findFertilizeButton: best candidate has invalid bounds, skip (text='${selected.text.take(30)}' boundsInvalid)")
            Log.d(TAG, "findFertilizeButton: skip invalid-bounds candidate (clickable=${selected.clickable})")
            return null
        }
        Log.d(TAG, "findFertilizeButton: selected '${selected.text}' clickable=${selected.clickable} boundsValid=${selected.boundsValid} (from ${candidates.size} candidates)")
        return selected.node
    }

    /**
     * 解析主页"还差X次领肥料"按钮上的剩余施肥次数 X
     *
     * build543 添加（用户反馈"还差3次施肥，那我们就施肥3次，然后还差3次施肥会变成'立即领取'"）：
     * - 主页"还差3次领肥料"按钮表示需要再施肥 3 次才能解锁领取
     * - 施肥 3 次后按钮会变成"立即领取"/"立即领肥"（被 directCollectTexts 识别）
     * - 本方法递归遍历 accessibility tree 找含"还差"且含"次"的文本节点，解析其中的数字
     *
     * 匹配格式：
     * - "还差3次领肥料" / "还差 3 次领肥料" / "还差3次领肥"
     * - "还差3次" / "还差 3 次"
     *
     * @return 剩余施肥次数（≥1）；找不到返回 0
     */
    fun parseFertilizeRemainingCount(): Int {
        val root = getRootInFarmApp() ?: return 0
        val pattern = Regex("""还差\s*(\d+)\s*次""")
        // 递归遍历所有节点的 text 和 contentDescription
        fun walk(node: AccessibilityNodeInfo): Int {
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            for (s in listOf(text, desc)) {
                val m = pattern.find(s)
                if (m != null) {
                    val n = m.groupValues[1].toIntOrNull() ?: 0
                    if (n > 0) {
                        debugLog("parseFertilizeRemainingCount: found '$s' → $n")
                        return n
                    }
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val r = walk(child)
                if (r > 0) return r
            }
            return 0
        }
        val n = walk(root)
        if (n == 0) {
            debugLog("parseFertilizeRemainingCount: no '还差X次' node found")
        }
        return n
    }

    /**
     * 找主页"还差X次领肥料"提示文字节点（用于定位施肥按钮附近位置）
     *
     * build548 添加（用户反馈"'还差x次施肥'，不是让你去点击这个按钮，而是去点击施肥按钮"）：
     * - "还差X次领肥料"是提示文字，不是施肥按钮，点击它本身不会施肥
     * - 真正的施肥按钮在它附近（H5 未暴露文本，无法用文本查找）
     * - 本方法只找提示文字位置，调用方根据该位置推算施肥按钮坐标
     *
     * 历史问题（build547-1e07e0e）：曾用 findRemainingFertilizerButton 直接 performClickSafe
     * 该 hint 文字本身（clickable=true 但不是施肥按钮），不会施肥，必须改为找附近真正的施肥按钮。
     *
     * @return 提示文字节点或null
     */
    fun findRemainingFertilizerHintNode(): AccessibilityNodeInfo? {
        val root = getRootInFarmApp() ?: return null
        val pattern = Regex("""还差\s*\d+\s*次.*肥""")
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            for (s in listOf(text, desc)) {
                if (pattern.containsMatchIn(s)) {
                    val rect = android.graphics.Rect()
                    node.getBoundsInScreen(rect)
                    val boundsValid = rect.width() > 0 && rect.height() > 0 && rect.top < rect.bottom
                    if (boundsValid) {
                        debugLog("findRemainingFertilizerHintNode: found '$s' bounds=${rect.toShortString()}")
                        return node
                    }
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child)?.let { return it }
            }
            return null
        }
        val node = walk(root)
        if (node == null) {
            debugLog("findRemainingFertilizerHintNode: no '还差X次领肥' hint node found")
        }
        return node
    }

    /**
     * 转储主页所有 clickable=true 的节点（bounds + text/desc），用于诊断施肥按钮位置
     *
     * build548 添加：用户反馈"还差x次施肥不是点击这个按钮，而是去点击施肥按钮"。
     * 但 H5 未暴露施肥按钮文本，无法用文本查找。本方法 dump 所有可点击节点，
     * 帮助从日志里定位施肥按钮的真实坐标。
     */
    fun dumpClickableNodes(tag: String) {
        val root = getRootInFarmApp() ?: run {
            debugLog("[$tag] dumpClickableNodes: root is null")
            return
        }
        data class Clickable(val text: String, val desc: String, val bounds: android.graphics.Rect)
        val list = mutableListOf<Clickable>()
        fun walk(node: AccessibilityNodeInfo) {
            if (node.isClickable) {
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0 && rect.top < rect.bottom) {
                    list.add(Clickable(node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty(), rect))
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { walk(it) }
            }
        }
        walk(root)
        debugLog("[$tag] clickable nodes (count=${list.size}):")
        for (c in list) {
            debugLog("[$tag]   text='${c.text}' desc='${c.desc}' bounds=${c.bounds.toShortString()}")
        }
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
     * 诊断用：递归遍历节点树，收集所有含"领取"/"领肥"/"立即领"/"点击领"/"可领取"等关键字的
     * 文本节点信息（text/desc/bounds/clickable）。
     *
     * build539 添加（用户反馈"芭芭农场主页上的'点击领取'看不到吗"）：
     * - 旧日志只打印前 10 个文本（take(10)），无法判断"点击领取"是否在 accessibility tree 里
     * - 此函数 dump 所有领取相关文本节点，用于：
     *   1. 确认"点击领取"是否被 H5 WebView 暴露给 accessibility
     *   2. 若暴露，检查其 bounds 是否合法（非 zero-size）
     *   3. 若 bounds 合法，检查 clickable 字段，确认 findClickableSelfOrParentInternal 是否能找到
     *
     * 调用方：[AutomationController.logPageSnapshot]
     *
     * @param out 输出列表，每项格式 "text='xxx' desc='yyy' bounds=[a,b][c,d] clickable=true/false"
     */
    fun collectClaimTextNodesForDiag(node: AccessibilityNodeInfo, out: MutableList<String>) {
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val combined = text + desc
        if (combined.isNotEmpty()) {
            val kws = listOf("领取", "领肥", "立即领", "点击领", "可领取", "领取肥料", "点击领取")
            if (kws.any { combined.contains(it) }) {
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                out.add("text='$text' desc='$desc' bounds=${rect.toShortString()} clickable=${node.isClickable}")
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectClaimTextNodesForDiag(child, out)
        }
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
                    // build588 修复（debug_test_20260721_184040.log, build587 line 89-194）：
                    // 历史问题：UC deep link "https://broccoli.uc.cn/..." 是 HTTPS URL,
                    // Intent.ACTION_VIEW 没指定 setPackage,Android 系统默认用 Chrome 打开
                    // (activeRootPkg='com.android.chrome'),而不是 UC 浏览器(com.ucmobile.lite),
                    // 导致 navigate 反复 reopenFarmByDeepLink 始终进不了 UC 芭芭农场。
                    // 修复：强制指定目标平台包名,确保用 UC 浏览器打开 UC deep link。
                    val targetPkg = targetPlatform.config.packageNames.firstOrNull()
                    if (targetPkg != null) {
                        try {
                            setPackage(targetPkg)
                        } catch (e: Exception) {
                            debugLog("reopenFarmByDeepLink: setPackage($targetPkg) failed (${e.message}), fallback to default")
                        }
                    }
                }
                startActivity(intent)
                debugLog("reopenFarmByDeepLink: opened $deepLink for $targetPlatform (pkg=${targetPlatform.config.packageNames.firstOrNull()})")
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
            // 清除残留的 Activity/包名缓存：kill 后旧窗口失效，但 currentActivityName
            // 只在 TYPE_WINDOW_STATE_CHANGED 事件中更新，残留旧值会导致 isOnFarmPage 等判断矛盾
            // （日志现象：act=xriveractivity 但 onFarm=false，因为 windows 里已无农场包名窗口）
            currentActivityName = null
            currentEventPkg = null
            debugLog("forceKillApp: killBackgroundProcesses($pkg) called, cleared currentActivityName/currentEventPkg")
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
        // build581 加固：stepClickFarmTabByGesture 是淘宝专用方法（依赖淘宝主页底部 tab 栏
        // "我的淘宝"入口 + 固定坐标 (1080,2572)/(161,1534)），对 UC/支付宝完全无效。
        // 日志 debug_test_20260721_152904.log line 4916-4917 在 UC 平台 PortraitADActivity
        // 卡死时,错误 fallback 到 stepClickFarmTabByGesture,打出"not on taobao main page"
        // 误导用户以为切到了淘宝。修复：仅在 TAOBAO 平台时才走淘宝专用路径,
        // UC/ALIPAY 平台直接放弃导航（clearNavigatingFlag），由 AutomationController
        // 下一轮 runNavigating 重新决策（如 reopenFarmByDeepLink）。
        if (platform == Platform.TAOBAO) {
            debugLog("navigate stepTab: 芭芭农场 tab not found after 6 retries, trying 我的淘宝 path (platform=$platform)")
            stepClickFarmTabByGesture(platform, 0)
        } else {
            Log.w(TAG, "navigate stepTab: 芭芭农场 tab not found after 6 retries on $platform, abort (stepClickFarmTabByGesture is taobao-only)")
            debugLog("navigate stepTab: abort on $platform (taobao-only gesture fallback skipped)")
            clearNavigatingFlag()
        }
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

        // 守卫：判断"用户实际看到的窗口"是不是 farm App
        //
        // 关键：必须用 rootInActiveWindowSafe().packageName 判断，不能用 getCurrentWindowPackage()。
        // getCurrentWindowPackage 设计目的是找广告窗口（优先返回"非 farm 包名"窗口），
        // 但 Honor 设备上 windows 列表常含顶部状态栏（com.android.systemui）窗口，
        // 即使支付宝在前台，getCurrentWindowPackage 也会返回 systemui → 误判为 systemui 占屏。
        //
        // 历史问题 1（debug_test_20260718_175404.log）：
        // - 17:53:46 真正的下拉通知栏占用屏幕，rootInActiveWindow 是 systemui
        // - getRootInFarmApp 仍能从 windows 找到支付宝后台窗口的 root
        // - 在支付宝 root 里找到"芭芭农场"节点，bounds=[108,402][298,510]
        // - dispatchGestureClick(203, 456) 点击屏幕坐标，但屏幕显示 systemui → 误触
        // - 4 秒后 bbncbot MainActivity 被拉到前台
        //
        // 历史问题 2（debug_test_20260718_193727.log，build506）：
        // - 19:36:21-28 用 getCurrentWindowPackage 误判 systemui 占屏 → 6 次 pressBack 无效
        // - 19:36:41 支付宝刚启动到 AlipayLogin，pressBack 误退出支付宝
        //
        // 历史问题 3（debug_test_20260718_194749.log，build507）：
        // - 19:46:58 支付宝已启动到 AlipayLogin（主容器 Activity，不是闪屏页）
        // - sample 显示支付宝首页内容（松开刷新/天气/搜索框）
        // - 但 getCurrentWindowPackage 误判为 systemui（顶部状态栏窗口）
        // - act=AlipayLogin 触发"启动中"等待分支，永远等不到启动完成
        // - 用户被迫手动停止
        //
        // 修复：用 rootInActiveWindowSafe().packageName 判断用户实际看到的窗口：
        // - build507 场景：rootInActiveWindow 是支付宝首页 root → isFarmPkg=true → 继续点击"芭芭农场"
        // - build505 场景：rootInActiveWindow 是 systemui root → isFarmPkg=false → 不点击屏幕坐标
        val activeRootPkg = rootInActiveWindowSafe()?.packageName?.toString().orEmpty()
        val cfg = currentPlatformConfig()
        val isFarmPkg = activeRootPkg.isNotEmpty() && (
            activeRootPkg in cfg.packageNames ||
            cfg.internalPackagePrefixes.any { activeRootPkg.startsWith(it) }
        )
        if (!isFarmPkg) {
            // activeRoot 不是 farm 包名（如 systemui 下拉通知栏、控制中心、锁屏等真正占屏场景）
            // 此时绝不能点击屏幕坐标——会误触 systemui 的元素
            //
            // build522 修复：区分"系统弹窗"和"用户主动切换到其他 App"
            // 历史问题（debug_test_20260718_212740.log, build518-93f7a54）：
            // - 用户在分析日志时切到 TRAE Code 编辑器（com.bytedance.trae.cn）查看代码
            // - bot 调用 navigateAlipay 时 rootInActiveWindow 是 TRAE 编辑器 root
            // - isFarmPkg=false → 当成 systemui 弹窗，pressBack 想关闭"弹窗"
            // - 但 TRAE 编辑器不是弹窗，pressBack 切到后台又拉起其他 App
            // - 同时 isRechargePage/isAdContentShown 把 TRAE 编辑器界面文字（"立即支付"等
            //   被 contains 误匹配）当成陷阱页 → 反复 pressBack → 最终 STOPPING
            //
            // 修复：识别真实的"非系统弹窗的其他 App"（activeRootPkg 不是 systemui/android
            // 等系统包名，而是真实用户 App），这种情况说明用户主动切走了，bot 应该安静等待用户切回，
            // 不要 pressBack 干扰用户操作。只有真正的 systemui 弹窗才 pressBack 尝试关闭。
            //
            // build524 修复（debug_test_20260719_025229.log, build522-bdb61fe）：
            // - 历史问题：build522 把 launcher（com.hihonor.android.launcher）归到 isSystemPopupPkg，
            //   走 pressBack 路径。但 launcher 不是弹窗，pressBack 关不掉
            // - 场景：RETURNING 后 reopenFarmByDeepLink kill 了支付宝，支付宝启动中 launcher 在前台
            // - navigateAlipay 检测到 launcher → pressBack → 无效（回到桌面）→ 用户停止
            // - 修复：launcher 归到"等待"分支（不 pressBack），等支付宝启动完自动切回
            val isSystemPopupPkg = activeRootPkg == "com.android.systemui" ||
                activeRootPkg == "android" ||
                activeRootPkg.isEmpty()
            // launcher（桌面）单独处理：不 pressBack，等待 app 启动或用户切回
            val isLauncherPkg = activeRootPkg.endsWith(".launcher")
            if (!isSystemPopupPkg && !isLauncherPkg) {
                // 用户切到了其他真实 App（如 TRAE 编辑器、微信等），不 pressBack，安静等待
                debugLog("navigateAlipay: active root pkg=$activeRootPkg is another user app (not systemui/launcher), waiting silently for user to switch back to farm app")
                navHandler.postDelayed({ stepNavigateAlipayFarm(retry + 1) }, 3000L)
                return
            }
            if (isLauncherPkg) {
                // launcher 在前台：可能是 RETURNING 后 kill 了 app，app 正在启动中
                // 不 pressBack（pressBack 对 launcher 无效），等待 app 启动完自动切回
                //
                // build525 修复（debug_test_20260719_030452.log, build523-6b7f55f）：
                // - 历史问题：build524 只等待不主动拉起，但支付宝 kill 后可能没真正重启
                //   （bbncbot 自己在前台，launcher 一直在前台，支付宝没被拉起）
                // - 日志：retry=0..7 一直 isLauncherPkg，最终 max retries reached, abort
                // - 修复：retry % 3 == 0 时主动调用 reopenFarmByDeepLink 拉起支付宝
                //   避免支付宝 kill 后没重启导致死循环等待
                if (retry % 3 == 0) {
                    debugLog("navigateAlipay: active root pkg=$activeRootPkg is launcher, retry=$retry, actively relaunching farm app")
                    reopenFarmByDeepLink()
                } else {
                    debugLog("navigateAlipay: active root pkg=$activeRootPkg is launcher (app probably restarting), waiting for app to come to foreground (retry=$retry)")
                }
                navHandler.postDelayed({ stepNavigateAlipayFarm(retry + 1) }, 2000L)
                return
            }
            if (retry < 2) {
                // 前 2 次：pressBack 尝试关闭下拉通知栏等可关闭的 systemui 弹窗
                debugLog("navigateAlipay: active root pkg=$activeRootPkg (systemui popup), pressing back to dismiss (retry=$retry)")
                pressBack()
                navHandler.postDelayed({ stepNavigateAlipayFarm(retry + 1) }, 2000L)
                return
            }
            // retry >= 2：pressBack 无效（Honor 系统弹窗），改为等待
            // 让支付宝自己启动完，或 systemui 弹窗自己消失，或用户手动处理
            debugLog("navigateAlipay: systemui still occupying screen after $retry retries, waiting (pkg=$activeRootPkg)")
            navHandler.postDelayed({ stepNavigateAlipayFarm(retry + 1) }, 3000L)
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
            // build587 修复（debug_test_20260721_175556.log, build586, UC→ALIPAY 跨平台 line 276-300）：
            // 历史问题：搜索框区域的"芭芭农场"文字（历史搜索词/推荐词, bounds=[268,121][1020,278],
            // y=121 在屏幕顶部 5%）isClickable=true, isSearchNode=false（text 不含"搜索"）,
            // 被当作有效入口反复点击,但点击后不跳转（搜索框文字不是入口）,卡 2 分钟。
            // 修复：排除屏幕顶部 y < 20% 的"芭芭农场"节点（搜索框区域）,直接走策略2 搜索。
            val screenHeight = resources.displayMetrics.heightPixels
            val isSearchBarArea = screenHeight > 0 && rect.top < screenHeight * 0.20f
            if (isSearchBarArea && !isSearchNode) {
                debugLog("navigateAlipay: 芭芭农场 entry at ${rect.toShortString()} is in search bar area (top=${rect.top} < ${screenHeight * 0.20f}), skip and fallback to search")
                // 直接走策略2（搜索框搜索）
                farmEntry.recycle()
            } else
            // 排除超大容器，且必须 bounds 合法（left<right, top<bottom，且在屏幕范围内）
            // 否则可能拿到 WebView 内的离屏节点（如 bounds=[4476,822][1200,1139]），点击无效
            if (rect.width() > 0 && rect.height() > 0 &&
                rect.left < rect.right && rect.top < rect.bottom &&
                rect.left >= 0 && rect.top >= 0) {
                // build534 修复（debug_test_20260719_072400.log, build534-6db91cf）：
                // 历史问题：原逻辑只检查 bounds 合法就点击，不检查 clickable。
                // 但日志显示在支付宝首页找到了"芭芭农场"文本节点（位置 [111,494][298,598]，
                // 在左上角，很可能是首页顶部的标题文本），clickable=false。
                // ACTION_CLICK failed → dispatchGesture 手势派发返回 true（仅表示派发成功）
                // 但点击 (204.5, 546.0) 落在不可点击的标题文本上，没触发跳转。
                // 8 秒后重试又找到同一节点，又点击，又失败——死循环 7 次直至用户停止。
                //
                // 修复：跳过 clickable=false 的"芭芭农场"节点，直接走策略 2（搜索框搜索）。
                // Honor 桌面下的"芭芭农场"快捷入口在桌面图标里（不在支付宝首页），
                // 支付宝首页找到的"芭芭农场"文本若是不可点击的标题，不应尝试点击。
                if (!farmEntry.isClickable) {
                    debugLog("navigateAlipay: 芭芭农场 entry at ${rect.toShortString()} is not clickable (probably title text), skip and fallback to search")
                } else {
                    debugLog("navigateAlipay: found 芭芭农场 entry at ${rect.toShortString()}, clickable=${farmEntry.isClickable}, clicking")
                    val clickResult = performClickSafe(farmEntry)
                    debugLog("navigateAlipay: 芭芭农场 entry click result=$clickResult (will verify navigation)")
                    // Honor 桌面下"芭芭农场"入口常 clickable=false，手势兜底点击后可能未真正进入农场页。
                    // 修复：performClickSafe 返回后等待几秒，让界面切换；
                    // 如果仍在支付宝首页（没进入芭芭农场 H5 页），下一轮 stepNavigateAlipayFarm 会重试。
                    // 这里设置 8 秒超时清除导航标志，足够 H5 加载。
                    //
                    // build533 修复（debug_test_20260719_064828.log, build532-6d5c936）：
                    // 历史问题：原逻辑只 postDelayed clearNavigatingFlag(8s) 然后 return，
                    // 8 秒后只清除导航标志，不重新调用 stepNavigateAlipayFarm 重试。
                    // 若点击的"芭芭农场"节点 clickable=false（ACTION_CLICK failed，手势兜底可能
                    // 没真正触发跳转），bot 卡死等待 8 秒后什么也不做，造成 26 秒空白直至用户停止。
                    // 修复：8 秒后除了清除标志，还要再调用一次 stepNavigateAlipayFarm(retry+1)
                    // 让 bot 检查是否仍在支付宝首页；若是则换其他策略（搜索框）重新导航。
                    navHandler.postDelayed({
                        clearNavigatingFlag()
                        stepNavigateAlipayFarm(retry + 1)
                    }, 8000L)
                    return
                }
            } else {
                debugLog("navigateAlipay: 芭芭农场 entry invalid (searchNode=$isSearchNode, bounds=${rect.toShortString()}), fallback to search")
            }
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
                                // build587 修复（debug_test_20260721_175556.log, build586, UC→ALIPAY 跨平台）：
                                // 历史问题：搜索结果列表有多个"芭芭农场"条目（小程序/生活号/网页等）,
                                // findNodeByText 只返回第一个,可能点错条目（如点到了网页结果）。
                                // 且点击后只 postDelayed 8s 清 flag,不验证是否真的跳转到芭芭农场,
                                // 导致卡在搜索结果页反复点击无效条目。
                                // 修复：
                                // 1. 收集所有"芭芭农场"文本节点,优先选带"小程序"标识的（支付宝小程序入口）
                                // 2. 点击后 3s 验证 isOnFarmPage,未跳转则用 dispatchGesture 点坐标兜底
                                // 3. 仍失败则 pressBack 退出搜索页重试
                                val allResults = mutableListOf<AccessibilityNodeInfo>()
                                val seen = HashSet<Int>()
                                collectNodesByText(resultRoot, listOf("芭芭农场"), allResults, seen)
                                if (allResults.isNotEmpty()) {
                                    // 优先选带"小程序"标识的节点（支付宝芭芭农场是小程序）
                                    val mpNode = allResults.firstOrNull { node ->
                                        val parent = node.parent
                                        parent != null && collectAllText(parent).any {
                                            it.contains("小程序") || it.contains("生活号") || it.contains("官方")
                                        }
                                    } ?: allResults.first()
                                    val rRect = android.graphics.Rect()
                                    mpNode.getBoundsInScreen(rRect)
                                    val rValid = rRect.width() > 0 && rRect.height() > 0 &&
                                        rRect.left < rRect.right && rRect.top < rRect.bottom &&
                                        rRect.left >= 0 && rRect.top >= 0
                                    if (rValid) {
                                        debugLog("navigateAlipay: clicking search result '芭芭农场' at ${rRect.toShortString()} (candidates=${allResults.size})")
                                        // 先用 performClickSafe（向上找 clickable 祖先）
                                        performClickSafe(mpNode)
                                        // build587: 3s 后验证是否跳转到芭芭农场,未跳转用 dispatchGesture 点坐标兜底
                                        navHandler.postDelayed(verifyStep@{
                                            if (isOnFarmPage()) {
                                                debugLog("navigateAlipay: search result click succeeded, on farm page now")
                                                clearNavigatingFlag()
                                                return@verifyStep
                                            }
                                            // 未跳转,用 dispatchGesture 直接点击文本节点坐标
                                            debugLog("navigateAlipay: search result click did not navigate, trying dispatchGesture at (${rRect.exactCenterX()}, ${rRect.exactCenterY()})")
                                            dispatchGestureClick(rRect.exactCenterX(), rRect.exactCenterY())
                                            navHandler.postDelayed({
                                                if (isOnFarmPage()) {
                                                    debugLog("navigateAlipay: dispatchGesture click succeeded, on farm page now")
                                                    clearNavigatingFlag()
                                                } else {
                                                    debugLog("navigateAlipay: dispatchGesture also failed, pressBack and retry")
                                                    pressBack()
                                                    navHandler.postDelayed({ stepNavigateAlipayFarm(retry + 1) }, 3000L)
                                                }
                                            }, 4000L)
                                        }, 3000L)
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
        // 历史问题：当 gamePlay 等场景卡在子小程序页（如蚂蚁庄园）时，页面 root 有内容但
        // 既无"芭芭农场"入口也无"搜索"按钮（蚂蚁庄园没有这两个元素），原逻辑只是 postDelayed 重试，
        // 在同一子页死循环 8 次直到 abort，期间不会主动 pressBack 退出子小程序。
        // 修复：retry=0 时还可能是页面未加载完，等待重试即可；retry>=1 时主动 pressBack 退出当前子页
        // （退到支付宝主容器后再重试，能找到搜索框或入口）。
        if (retry >= 1) {
            debugLog("navigateAlipay: no search or farm entry found, pressing back to exit sub-page (retry=$retry)")
            pressBack()
        } else {
            debugLog("navigateAlipay: no search or farm entry found, retry=$retry")
        }
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
     *
     * build557 修复（debug_test_20260719_145710.log, build556-d5f334c）：
     * 历史问题：UC 浏览器弹出系统级"是否允许打开"对话框时，整段文本节点形如
     *   '"UC浏览器极速版" 想要打开 "美团"，是否允许？' (clickable=false, bounds=[131,1920][1069,2058])
     * findNodeByText 用 contains 匹配"允许"会命中"是否允许"中的"允许"，把整段文本节点
     * （而非真正的"允许"按钮）当作目标，performClickSafe 连续 8 次手势点击文本中心均无效。
     *
     * 修复策略：
     * 1. 优先精确匹配短文本按钮（buttonText == "允许" / "同意" 等，长度 ≤ 8），
     *    避免匹配到包含"允许"字样的整段弹窗文本。
     * 2. 找到候选节点后再次校验 isClickable（向上找父节点也算）。
     * 3. 仅当所有精确匹配都失败时，才允许 contains 兜底匹配（保守起见保留旧行为）。
     *
     * @return true 表示处理了权限对话框（点击了允许按钮）
     */
    private fun handlePermissionDialog(root: AccessibilityNodeInfo): Boolean {
        // 短文本按钮（系统权限对话框的标准按钮文案）
        val allowTexts = listOf("始终允许", "仅在使用中允许", "允许", "同意", "确定", "我知道了")
        // 第一阶段：精确匹配 buttonText == 关键词（且节点 isClickable 或有 clickable 父节点）
        for (text in allowTexts) {
            val node = findExactClickableNodeByText(root, text)
            if (node != null) {
                debugLog("handlePermissionDialog: click '$text' (exact match)")
                Log.i(TAG, "handlePermissionDialog: click '$text' (exact)")
                performClickSafe(node)
                return true
            }
        }
        // 第二阶段：兜底用旧 contains 匹配（仅在精确匹配全失败时使用，并要求结果 isClickable）
        // 注：这是为了兼容某些厂商 ROM 自定义的按钮文案（如"始终允许"被拆成"始终"+"允许"等）
        for (text in allowTexts) {
            val node = findNodeByText(root, text) ?: continue
            // 校验：整段文本节点（如"是否允许？"）的 isClickable=false 且父节点链路无可点击节点，
            // findNodeByText 已向上找 clickable 父节点；若返回的 node 仍不可点击，则跳过
            // （避免把弹窗整段文本当成按钮）
            if (!node.isClickable) {
                debugLog("handlePermissionDialog: skip non-clickable contains-match '$text' (likely popup text, not button)")
                continue
            }
            // 校验文本长度：真正的按钮文案 ≤ 8 字符，整段弹窗文本一般 > 20 字符
            val btnText = node.text?.toString()?.trim().orEmpty()
            if (btnText.length > 8) {
                debugLog("handlePermissionDialog: skip long-text contains-match '$text' (len=${btnText.length}, text='${btnText.take(20)}')")
                continue
            }
            debugLog("handlePermissionDialog: click '$text' (contains fallback, text='$btnText')")
            Log.i(TAG, "handlePermissionDialog: click '$text' (fallback)")
            performClickSafe(node)
            return true
        }
        return false
    }

    /**
     * 精确匹配节点文本/内容描述等于 [exactText] 的可点击节点
     * - 用于权限对话框按钮识别，避免 contains 误匹配整段弹窗文本（如"是否允许？"命中"允许"）
     * - 优先返回自身匹配且 isClickable=true 的节点；其次返回自身匹配且最近父节点可点击的节点
     */
    private fun findExactClickableNodeByText(root: AccessibilityNodeInfo, exactText: String): AccessibilityNodeInfo? {
        return findExactClickableNodeByTextInternal(root, exactText)
    }

    private fun findExactClickableNodeByTextInternal(
        node: AccessibilityNodeInfo,
        exactText: String
    ): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val selfMatched = text == exactText || desc == exactText
        if (selfMatched && node.isClickable) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val matched = findExactClickableNodeByTextInternal(child, exactText)
            if (matched != null) return matched
        }
        if (selfMatched) {
            return findClickableSelfOrParentInternal(node)
        }
        return null
    }
}
