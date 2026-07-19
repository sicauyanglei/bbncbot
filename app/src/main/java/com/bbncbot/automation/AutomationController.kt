package com.bbncbot.automation

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.bbncbot.service.FarmAccessibilityService
import com.bbncbot.service.FarmAccessibilityService.PageScene
import java.lang.ref.WeakReference

/**
 * 自动化控制器 v2（单例）
 *
 * v2 重构 - 基于用户要求和v11 PC端ADB方案经验：
 *
 * 用户要求的导航路径：
 * 主页右下角任务进入 → 右上角芭芭农场 → 农场主页 → 点击集肥料按钮 → 点击各个去完成按钮获取肥料
 *
 * 用户要求的行为：
 * - 智能的点击判断，取完肥料就退回来继续获取下一个肥料任务
 * - 没有点对有返回按钮，返回到主界面重新开始进入广告获取肥料
 * - 机器人智能地适应退回按钮位置，不要只硬编码一个位置
 * - 只点击广告，邀请推广网页直接返回，任务不做
 * - 安装软件的广告也不做，不安装，直接退出
 * - 邀请好友的肥料不赚
 *
 * 状态机循环：NAVIGATING → OPENING_TASK_LIST → PROCESSING_TASK → WATCHING_AD → CLOSING_AD → RETURNING → PROCESSING_TASK (下一个) → ...
 */
object AutomationController {

    private const val TAG = "AutomationController"

    // ---------- 时间间隔（毫秒） ----------
    /** 通用点击间隔 */
    private const val INTERVAL_CLICK_MS = 2000L
    /** 等待页面加载 */
    private const val INTERVAL_PAGE_LOAD_MS = 5000L
    /** 一轮结束后等待时间 */
    private const val INTERVAL_WAIT_MS = 5000L
    /** 任务列表最大尝试次数（需大于平台坐标候选数，支付宝有11个候选） */
    private const val MAX_TASK_LIST_ATTEMPTS = 8
    /** 单个任务最大尝试次数 */
    private const val MAX_TASK_ATTEMPTS = 3
    /** 广告播放最短等待时间（默认值，单位毫秒） */
    private const val AD_MIN_DURATION_MS = 30000L
    /** 广告播放最大等待时间（秒），超时强制关闭 */
    private const val AD_MAX_DURATION_MS = 90000L
    /** 广告结束检测轮询间隔 */
    private const val AD_END_CHECK_INTERVAL_MS = 5000L
    /** 广告时长解析缓冲（毫秒）：在页面提示的规定时间基础上额外等待，确保肥料奖励到账 */
    private const val AD_DURATION_BUFFER_MS = 2000L
    /** "更快拿奖"流程：点击"允许"后新 App 打开的停留时间（毫秒） */
    private const val FASTER_REWARD_APP_STAY_MS = 16000L
    /**
     * 广告深链跳转进入其他 App（如快手）后的等待时间（毫秒）
     * - 用户要求：打开快手等其它app任务，等其它app打开后等2秒，把主界面激活到前台，同时kill掉打开的app
     * - 检测到深链 App 后等待此时间，然后激活农场 App 到前台并强杀被拉起的 App
     */
    private const val DEEP_LINK_MAX_DURATION_MS = 2000L
    /** 返回农场页最大尝试次数 */
    private const val MAX_RETURN_ATTEMPTS = 5
    /** 连续无进展轮次上限（超过则重新导航） */
    private const val MAX_NO_PROGRESS_ROUNDS = 3
    /** 施肥按钮最大点击次数（防止无限点击） */
    private const val MAX_FERTILIZE_CLICKS = 30
    /** 滑动浏览任务最大滑动次数 */
    private const val MAX_BROWSE_SWIPES = 6
    /** 滑动达标后等待"再逛xx秒后可领奖"倒计时结束的最大额外滑动次数（避免无限等待） */
    private const val MAX_BROWSE_WAIT_SWIPES = 30
    /** 每次滑动间隔（毫秒） */
    private const val BROWSE_SWIPE_INTERVAL_MS = 2000L
    /**
     * 游戏任务最大时长（硬超时，超时强制退出）
     *
     * 用户澄清：游戏过关卡任务实现不了，但"打开游戏停留玩一下"的任务可以获取肥料。
     * 这类任务只需在游戏内停留规定时长即可发放肥料，无需真正通关。
     * 因此硬超时从原 180s 收紧到 90s（覆盖加载 5s + 停留 30s + 退出余量）。
     */
    private const val GAME_MAX_DURATION_MS = 90000L
    /**
     * 游戏停留目标时长（"打开游戏停留玩一下"任务的核心指标）
     *
     * - 停留达到此时长后，主动按返回退出回农场，任务发放肥料
     * - 停留期间不按返回键（按返回会退出游戏导致停留失败）
     * - 仅检测：陷阱页（充值/交易）立即退出 / 完成页领奖 / 自动返回农场
     */
    private const val GAME_STAY_TARGET_MS = 30000L
    /** 游戏轮询间隔（每 4 秒检测一次页面状态） */
    private const val GAME_ACTION_INTERVAL_MS = 4000L
    /** 游戏加载等待时间 */
    private const val GAME_LOAD_MS = 5000L
    /** 浏览任务中连续关闭红包弹窗的最大次数（超过则视为误判，继续滑动） */
    private const val MAX_RED_PACKET_CLOSE_ATTEMPTS = 3

    /** 当前浏览任务的目标滑动次数（根据页面提示动态计算，无提示时用 MAX_BROWSE_SWIPES） */
    @Volatile
    private var browseTaskTargetSwipes: Int = MAX_BROWSE_SWIPES

    /**
     * 当前浏览任务中连续检测到红包弹窗的次数
     * - 防止 findRedPacketCloseButton 误判导致死循环：超过阈值后不再当红包弹窗处理，继续滑动
     * - 每次进入新的浏览任务（swipeCount=0）时重置
     */
    @Volatile
    private var browseRedPacketCloseAttempts: Int = 0

    /**
     * 标记当前浏览任务是否从"直接领取弹窗"进入
     * - true：浏览完成后回到 COLLECTING_DIRECT 继续找其他 direct 按钮
     * - false（默认）：浏览完成后回到 OPENING_TASK_LIST 任务列表流程
     * - 场景：点"立即领取"后弹窗不关闭，按钮变"点此逛一逛再赚1000肥料"，
     *   点该按钮进入浏览，完成后应回 direct 流程而非任务列表
     */
    @Volatile
    private var browseFromDirectPopup: Boolean = false

    /**
     * 标记当前浏览任务是否从"搜索后浏览立得奖励"任务页进入
     * - true：浏览完成后需要返回两次（搜索结果页 → 搜索任务页 → 芭芭农场）
     * - false（默认）：正常退出流程
     * - 场景：点击任务按钮进入"搜索后浏览立得奖励"页面，点击历史搜索词进入真正的浏览页面，
     *   滑动到"任务完成"后需要返回两次才能回到芭芭农场
     */
    @Volatile
    private var browseFromSearchBrowse: Boolean = false

    /**
     * 当前广告的最短观看时长（毫秒）
     * - 进入 WATCHING_AD 时按平台广告策略 + 页面提示动态设置（页面提示的秒数 + 缓冲）
     * - 无提示时使用平台默认值 [PlatformConfig.adDefaultMinDurationMs]
     * - 用户要求：太快退出可能获取不到肥料，需保持到规定时间+缓冲后再检测退出
     */
    @Volatile
    private var adMinDurationMs: Long = AD_MIN_DURATION_MS

    /**
     * 当前广告的最大等待时长（毫秒）
     * - 动态计算：max(平台默认上限, adMinDurationMs + 30s)
     * - 确保页面提示的长广告（如120秒）不会被提前强制关闭
     * - 在最短等待时间基础上留 30 秒余量让广告结束并发放奖励
     */
    @Volatile
    private var adMaxDurationMs: Long = AD_MAX_DURATION_MS

    /**
     * 当前广告的结束检测轮询间隔（毫秒）
     * - 进入 WATCHING_AD 时按平台广告策略设置 [PlatformConfig.adEndCheckIntervalMs]
     * - 支付宝/淘宝用 3s 更激进地快速检测退出，UC 用 5s 配合"更快拿奖"流程稳定轮询
     */
    @Volatile
    private var adEndCheckIntervalMs: Long = AD_END_CHECK_INTERVAL_MS

    /**
     * 深链跳转跟踪：广告任务跳转到其他 App 时记录的包名（null=未在深链状态）
     * - 进入 WATCHING_AD 时重置为 null
     * - 检测到不在农场 App 且不在广告 Activity 时，记录当前包名和时间戳
     * - 停留超过 [DEEP_LINK_MAX_DURATION_MS] 后强杀
     */
    @Volatile
    private var deepLinkAppPkg: String? = null

    /** 深链跳转进入其他 App 的时间戳（elapsedMs），配合 [deepLinkAppPkg] 使用 */
    @Volatile
    private var deepLinkEnterTimeMs: Long = 0L

    /** 上一轮广告检测时是否有倒计时（用于多信号融合检测倒计时消失） */
    @Volatile
    private var prevAdHadCountdown: Boolean = false

    /** 本次广告观看的农场平台（强杀深链 App 后重新启动此平台回到农场） */
    @Volatile
    private var watchingAdPlatform: Platform = Platform.UNKNOWN

    // ---------- 坐标比例候选 ----------
    // 注：坐标比例由当前平台 PlatformConfig 动态提供（UC/支付宝/淘宝各自不同），
    // 见 [PlatformConfig.collectFertilizerCoords] / [adCloseCoords] / [backButtonCoords]
    // 以下常量仅为兜底默认值（UC 配置），实际运行时优先使用 service.currentPlatformConfig()

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var state: AutomationState = AutomationState.IDLE

    @Volatile
    private var serviceRef: WeakReference<FarmAccessibilityService>? = null

    /** 状态变化回调（用于通知悬浮窗更新 UI） */
    var onStateChanged: ((AutomationState) -> Unit)? = null

    /** 已收集的肥料数量 */
    @Volatile
    private var collectedCount: Int = 0

    /** 当前任务列表中的按钮索引 */
    @Volatile
    private var currentTaskIndex: Int = 0

    /** 当前任务按钮列表（缓存） */
    @Volatile
    private var taskButtons: List<AccessibilityNodeInfo> = emptyList()

    /**
     * 本轮任务列表是否已通过点击"集肥料"按钮主动调出（闭环规则）
     *
     * 规则：支付宝每次进入 OPENING_TASK_LIST 状态时，必须先主动点击一次
     * "集肥料/施肥/领肥料/任务列表"等入口按钮，确保任务列表是本轮重新打开的，
     * 而非沿用上一轮残留可见的旧按钮。
     * - true：本轮已点击过集肥料入口（或已确认任务列表打开），可直接处理
     * - false：本轮还没点过，必须先点，不能因为有残留"去完成"按钮就跳过
     *
     * 每次进入新一轮任务循环（任务结束返回农场页重新 OPENING_TASK_LIST）时重置为 false。
     */
    @Volatile
    private var taskListOpenedThisRound: Boolean = false

    /** 连续无进展轮次 */
    @Volatile
    private var noProgressRounds: Int = 0

    /**
     * 当前任务剩余重玩次数（多次点击任务）
     * - 用户要求：有些任务按钮上有次数（如 1/3），表示可以多次点击
     * - 点击任务按钮时从按钮文本/上下文解析剩余次数，存入此字段
     * - 任务完成后：若 > 0，不递增 currentTaskIndex，重新打开任务列表点击同一任务
     * - 任务跳过/失败时：不重玩，直接递增 currentTaskIndex
     */
    @Volatile
    private var taskReplayRemaining: Int = 0

    /** 当前任务连续失败次数（未知页面/卡住等）。达到 MAX_TASK_FAILS 跳过任务 */
    @Volatile
    private var currentTaskFailCount: Int = 0

    /** 单个任务最大失败次数，超过则跳过该任务 */
    private const val MAX_TASK_FAILS = 2

    // ---------- build529：AI 视觉进度识别节流（用户要求"全部实现"） ----------
    // 用于 runGamePlaying / runWatchingAd 中截屏识别环形进度条填充比例
    // 节流：避免每次轮询都调 AI（视觉模型推理慢 + 限流），GAME/AD 期间最多 20s 调一次
    /** 上次 AI 视觉进度识别的时间戳（ms）；0 表示本任务还未调用过 */
    @Volatile
    private var lastAiProgressCheckMs: Long = 0L

    /** AI 视觉进度识别最小间隔（ms），避免每次轮询都打 AI */
    private const val AI_PROGRESS_CHECK_INTERVAL_MS = 20000L

    /**
     * "更快拿奖"弹窗处理状态
     * - 0=未处理（等待检测"我要更快拿奖"按钮）
     * - 1=已点入口按钮，等待确认弹窗出现并点"允许"
     * - 2=已点"允许"，新 App 已打开，停留16秒
     * - 3=已关闭新 App，等待"恭喜获得奖励提升"窗口，点右上角关闭
     * - 4=已完成，进入退出流程
     * - 每次进入 WATCHING_AD 时重置为 0
     */
    @Volatile
    private var fasterRewardStage: Int = 0

    /** "更快拿奖"流程：记录点击"允许"后打开的新 App 包名（用于关闭） */
    @Volatile
    private var fasterRewardAppPkg: String? = null

    /** "更快拿奖"流程：点击"允许"时的时间戳（用于计算16秒停留） */
    @Volatile
    private var fasterRewardAppEnterTimeMs: Long = 0L

    // ---------- 跨平台切换 ----------
    /** 跨平台切换：原平台（切换完成后回到此平台） */
    @Volatile
    private var switchOriginalPlatform: Platform = Platform.UNKNOWN
    /** 跨平台切换：目标平台 */
    @Volatile
    private var switchTargetPlatform: Platform = Platform.UNKNOWN
    /** 跨平台切换阶段：LAUNCH_TARGET=启动目标平台, FERTILIZE_TARGET=目标平台施肥, RETURN_ORIGINAL=返回原平台, RESUME=恢复原平台导航 */
    @Volatile
    private var switchStage: String = ""
    /** 跨平台切换重试计数 */
    @Volatile
    private var switchRetryCount: Int = 0
    /** 跨平台切换最大重试次数 */
    private const val MAX_SWITCH_RETRIES = 8

    val currentState: AutomationState get() = state
    val isRunning: Boolean
        get() = state != AutomationState.IDLE && state != AutomationState.STOPPING

    /** 绑定 FarmAccessibilityService */
    fun bindService(service: FarmAccessibilityService) {
        serviceRef = WeakReference(service)
        Log.i(TAG, "FarmAccessibilityService bound")
    }

    /** 解绑 */
    fun unbindService() {
        serviceRef = null
        if (isRunning) {
            stop()
        }
    }

    private fun getService(): FarmAccessibilityService? = serviceRef?.get()

    /** 调试日志写到外部存储文件（华为 logcat 加密，用文件替代） */
    private fun debugLog(msg: String) {
        Log.i(TAG, msg)
        try {
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date())
            val line = "$timestamp $msg\n"
            // 必须用 Context.getExternalFilesDir(null) 而非 Environment.getExternalStorageDirectory()
            // 原因：Android 11+ 限制外部存储访问，Environment 路径写入会静默失败
            // 通过 serviceRef 拿到 Service Context（Service 是 Context 子类）
            // 与 LogUploader.getLogDir / FarmAccessibilityService.debugLog 保持同一路径
            val ctx = serviceRef?.get()
            val file = if (ctx != null) {
                java.io.File(ctx.getExternalFilesDir(null), "debug.log")
            } else {
                // Service 已销毁的兜底：用 filesDir（App 内部存储，必定可写）
                java.io.File(android.app.Application().filesDir, "debug.log")
            }
            file.parentFile?.mkdirs()
            file.appendText(line)
        } catch (_: Exception) { /* ignore */ }
    }

    /**
     * 页面状态快照日志：输出当前所有关键页面判断结果，用于诊断"卡在哪个页面"问题
     * 调用时机：状态转换、关键决策点
     */
    private fun logPageSnapshot(service: FarmAccessibilityService, tag: String) {
        try {
            val pkg = service.getCurrentWindowPackage() ?: "null"
            val activity = service.getCurrentActivityName() ?: "null"
            // 注意：这些检测方法部分有缓存或副作用，按从轻到重排序
            val onFarm = service.isOnFarmPage()
            val adActivity = service.isAdActivity()
            val adPlaying = service.isAdPlaying()
            val adContent = service.isAdContentShown()
            val abnormal = service.isOnAbnormalPage()
            val nonAdTask = service.isNonAdTaskPage()
            val nonAdPkg = service.isNonAdPage()
            val taskComplete = service.isTaskCompletePage()
            val searchRec = service.isSearchRecommendPage()
            debugLog("[$tag] snapshot: pkg=$pkg, act=$activity, onFarm=$onFarm, adActivity=$adActivity, adPlaying=$adPlaying, adContent=$adContent, abnormal=$abnormal, nonAdTask=$nonAdTask, nonAdPkg=$nonAdPkg, taskComplete=$taskComplete, searchRec=$searchRec")
        } catch (e: Exception) {
            debugLog("[$tag] snapshot error: ${e.message}")
        }
    }

    /** 当前平台配置的集肥料按钮坐标候选 */
    private fun collectFertilizerCandidates(service: FarmAccessibilityService) =
        service.currentPlatformConfig().collectFertilizerCoords

    /**
     * 从任务按钮文本和上下文中解析任务剩余次数
     * - 匹配 "x/y" 格式（如 "1/3" → 剩余 2 次）
     * - 匹配 "剩余x次" 格式
     * - 无次数标记返回 0（单次任务）
     */
    private fun parseTaskRemainingCount(buttonText: String, contextText: String): Int {
        // 匹配 "x/y" 格式，如 "1/3", "2/3", "(1/3)"
        val countPattern = Regex("""(\d+)\s*/\s*(\d+)""")
        // 优先从上下文中解析（任务标题旁通常有次数标记）
        val match = countPattern.find(contextText) ?: countPattern.find(buttonText)
        if (match != null) {
            val completed = match.groupValues[1].toIntOrNull() ?: 0
            val total = match.groupValues[2].toIntOrNull() ?: 0
            if (total > 0 && total <= 20 && completed < total) {
                val remaining = total - completed
                debugLog("parseTaskCount: completed=$completed, total=$total, remaining=$remaining")
                return remaining
            }
            if (total > 20) {
                debugLog("parseTaskCount: skip x/y=$completed/$total (total>20, likely fertilizer progress not replay count)")
            }
        }
        // 匹配 "剩余x次" 格式
        val remainingPattern = Regex("""剩余\s*(\d+)\s*次""")
        val remainingMatch = remainingPattern.find(contextText)
        if (remainingMatch != null) {
            val remaining = remainingMatch.groupValues[1].toIntOrNull() ?: 0
            debugLog("parseTaskCount: 剩余${remaining}次")
            return remaining
        }
        return 0
    }

    /**
     * 任务完成后决定是否重玩同一任务或前进到下一个任务
     * - [taskReplayRemaining] > 0：递减，不递增 currentTaskIndex（重玩同一任务）
     * - [taskReplayRemaining] <= 0：递增 currentTaskIndex（前进到下一个任务）
     * @return true=已前进到下一个任务，false=将重玩同一任务
     */
    private fun advanceTaskIndex(): Boolean {
        if (taskReplayRemaining > 0) {
            taskReplayRemaining--
            debugLog("advanceTask: replaying same task, remainingReplays=$taskReplayRemaining")
            return false
        }
        currentTaskIndex++
        taskReplayRemaining = 0
        return true
    }

    /** 当前平台配置的广告关闭按钮坐标候选 */
    private fun adCloseCandidates(service: FarmAccessibilityService) =
        service.currentPlatformConfig().adCloseCoords

    /** 当前平台配置的退回按钮坐标候选 */
    private fun backButtonCandidates(service: FarmAccessibilityService) =
        service.currentPlatformConfig().backButtonCoords

    /** 启动自动化 */
    fun start() {
        val service = getService()
        if (service == null) {
            Log.w(TAG, "start: FarmAccessibilityService not bound")
            debugLog("start: FarmAccessibilityService not bound")
            return
        }
        if (isRunning) {
            Log.d(TAG, "start: already running, ignore")
            return
        }
        Log.i(TAG, "=== Automation v2 Started ===")
        debugLog("=== Automation v2 Started === platform=${service.currentPlatform}")
        // 取消所有导航回调，避免 stepClickFarmTab 在后台干扰自动化
        service.cancelNavigation()
        collectedCount = 0
        currentTaskIndex = 0
        noProgressRounds = 0
        taskButtons = emptyList()
        browseFromDirectPopup = false  // 复位 direct 弹窗标记，避免上一轮残留
        browseFromSearchBrowse = false  // 复位搜索浏览任务标记，避免上一轮残留
        // 重置当前平台的广告完成标记（新一轮运行可重新标记完成）
        resetCurrentPlatformComplete(service)
        moveTo(AutomationState.NAVIGATING)
        handler.post { runNavigating(attempt = 0) }
    }

    /** 停止自动化 */
    fun stop() {
        if (state == AutomationState.IDLE) return
        Log.i(TAG, "automation stopping")
        moveTo(AutomationState.STOPPING)
        handler.removeCallbacksAndMessages(null)
        // 取消进行中的导航回调，避免 stop 后导航继续干扰用户操作
        getService()?.cancelNavigation()
        getService()?.setAdMode(false)
        moveTo(AutomationState.IDLE)
    }

    // ============== 三平台广告完成跟踪 ==============
    private const val PREFS_NAME = "platform_ads_status"
    private const val KEY_UC = "uc_complete"
    private const val KEY_ALIPAY = "alipay_complete"
    private const val KEY_TAOBAO = "taobao_complete"
    /** 三平台广告全部完成的通知 channel id */
    private const val NOTIF_CHANNEL_ID = "all_ads_complete"

    /**
     * 重置当前平台的广告完成标记
     * 在 [start] 时调用，使新一轮运行可重新标记完成
     */
    private fun resetCurrentPlatformComplete(service: com.bbncbot.service.FarmAccessibilityService) {
        val prefs = service.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val key = platformPrefsKey(service.currentPlatform) ?: return
        prefs.edit().putBoolean(key, false).apply()
        debugLog("reset platform complete: ${service.currentPlatform}")
    }

    /**
     * 标记当前平台的广告已获取完，并检查三平台是否全部完成
     * 若三平台都完成 → 发送通知 + Toast 提示用户
     */
    private fun markPlatformAdsComplete(service: com.bbncbot.service.FarmAccessibilityService) {
        val prefs = service.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val key = platformPrefsKey(service.currentPlatform) ?: return
        prefs.edit().putBoolean(key, true).apply()
        debugLog("marked platform complete: ${service.currentPlatform}")

        val ucDone = prefs.getBoolean(KEY_UC, false)
        val alipayDone = prefs.getBoolean(KEY_ALIPAY, false)
        val taobaoDone = prefs.getBoolean(KEY_TAOBAO, false)
        debugLog("platform status: UC=$ucDone, ALIPAY=$alipayDone, TAOBAO=$taobaoDone")

        if (ucDone && alipayDone && taobaoDone) {
            Log.i(TAG, "=== All 3 platforms' ads complete! Notifying user ===")
            debugLog("=== All 3 platforms (UC/Alipay/Taobao) ads complete! ===")
            notifyAllPlatformsComplete(service)
            // 通知后重置所有平台标记，以便次日可重新触发
            prefs.edit().clear().apply()
        }
    }

    /** 获取当前平台对应的 SharedPreferences key */
    private fun platformPrefsKey(platform: Platform): String? = when (platform) {
        Platform.UC -> KEY_UC
        Platform.ALIPAY -> KEY_ALIPAY
        Platform.TAOBAO -> KEY_TAOBAO
        Platform.UNKNOWN -> null
    }

    /** 三平台全部完成时发送通知 + Toast */
    private fun notifyAllPlatformsComplete(service: com.bbncbot.service.FarmAccessibilityService) {
        // Toast 提示（在主线程）
        handler.post {
            android.widget.Toast.makeText(
                service,
                "🎉 淘宝、支付宝、UC极速版的广告肥料已全部获取完成！",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }

        // 系统通知
        try {
            val nm = service.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            // Android 8+ 需要 NotificationChannel
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    NOTIF_CHANNEL_ID,
                    "广告肥料完成通知",
                    android.app.NotificationManager.IMPORTANCE_HIGH
                )
                nm.createNotificationChannel(channel)
            }
            val notification = androidx.core.app.NotificationCompat.Builder(service, NOTIF_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("三平台广告肥料已全部完成")
                .setContentText("淘宝、支付宝、UC极速版的广告肥料已全部获取完成！")
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            nm.notify(1001, notification)
        } catch (e: Exception) {
            Log.e(TAG, "notifyAllPlatformsComplete failed: ${e.message}", e)
        }
    }

    private fun moveTo(newState: AutomationState) {
        if (state == newState) return
        Log.d(TAG, "state: $state -> $newState")
        debugLog("state: $state -> $newState")
        state = newState
        onStateChanged?.invoke(newState)
    }

    // ============== 阶段1: 导航到农场页 ==============

    /**
     * 导航阶段：确保在农场页
     * - 如果已在农场页，直接进入打开任务列表阶段
     * - 如果不在农场页，等待用户手动打开或尝试返回
     */
    private fun runNavigating(attempt: Int) {
        if (state != AutomationState.NAVIGATING) return
        val service = getService() ?: run { stop(); return }

        // 主动检测当前前台 App 平台（无障碍服务刚连接时可能还没检测到）
        service.refreshPlatform()

        if (attempt == 0) {
            logPageSnapshot(service, "navigate-start")
        }

        if (service.isOnFarmPage()) {
            Log.i(TAG, "navigate: on farm page, collecting direct fertilizer first")
            debugLog("navigate: on farm page, platform=${service.currentPlatform}")
            collectedCount = 0
            currentTaskIndex = 0
            noProgressRounds = 0
            // H5 页面可能仍在加载中（WebView Activity 已显示但内容未渲染），
            // 检查页面是否有可交互内容，没有则等待重试（最多等5次，每次5秒）
            val root = service.getRootInFarmApp()
            val hasContent = root != null && service.hasFarmContentLoaded(root)
            debugLog("navigate: hasFarmContentLoaded=$hasContent, attempt=$attempt")
            if (!hasContent && attempt < 10) {
                Log.i(TAG, "navigate: farm H5 page still loading, waiting...")
                handler.postDelayed({
                    if (state == AutomationState.NAVIGATING) runNavigating(attempt + 1)
                }, INTERVAL_PAGE_LOAD_MS)
                return
            }
            moveTo(AutomationState.COLLECTING_DIRECT)
            handler.postDelayed({ runCollectingDirect(attempt = 0) }, INTERVAL_CLICK_MS)
            return
        }

        // 签到日历弹窗检测（进入农场页时自动弹出，遮挡农场页内容导致 isOnFarmPage=false）
        // 实测日志（debug_test_20260715_051932.log）：
        // - 进入农场页时弹出签到日历（"第7天"/"已领取"/"今天"）
        // - 自动签到后显示"签到成功！每天来芭芭农场..."
        // - isOnFarmPage 返回 false（签到日历遮挡了农场核心元素）
        // - 若不关闭签到弹窗，runNavigating 会误判为"不在农场页"反复调用 navigateToFarm
        val signInScene = service.identifyCurrentScene()
        if (signInScene == FarmAccessibilityService.PageScene.SIGN_IN) {
            Log.i(TAG, "navigate: sign-in calendar popup detected, closing it")
            debugLog("navigate: sign-in popup detected (scene=SIGN_IN), attempting to close")
            // 优先点击签到按钮（未签到状态可能有"立即签到"按钮）
            val claimBtn = service.findClaimRewardButton()
            if (claimBtn != null) {
                debugLog("navigate: clicking sign-in button (text='${claimBtn.text}')")
                service.performClickSafe(claimBtn)
            } else {
                // 已签到状态（签到成功提示），关闭弹窗：
                // 优先找"关闭做任务集肥料弹窗"/"知道了"/"确定"按钮
                val closeBtn = service.findAdCloseButton()
                if (closeBtn != null) {
                    debugLog("navigate: closing sign-in popup via close button")
                    service.performClickSafe(closeBtn)
                } else {
                    debugLog("navigate: no close button for sign-in popup, pressing back")
                    service.pressBack()
                }
            }
            // 等待弹窗关闭后重新检测农场页
            handler.postDelayed({
                if (state == AutomationState.NAVIGATING) runNavigating(attempt + 1)
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 通用弹窗检测（无肥料提示的弹窗，如分享好友/开通会员/评价/活动等）
        // 用户需求：弹框窗口如何没有肥料提示，需要关闭弹窗
        // 策略：主动关闭弹窗，避免 bot 卡在 UNKNOWN 场景反复调用 navigateToFarm
        if (signInScene == FarmAccessibilityService.PageScene.GENERIC_POPUP) {
            Log.i(TAG, "navigate: generic popup detected (no fertilizer hint), closing it")
            debugLog("navigate: generic popup (no fertilizer), attempting to close")
            val closeBtn = service.findAdCloseButton()
            if (closeBtn != null) {
                debugLog("navigate: clicking close button on generic popup (text='${closeBtn.text}')")
                service.performClickSafe(closeBtn)
            } else {
                debugLog("navigate: no close button found for generic popup, pressing back")
                service.pressBack()
            }
            handler.postDelayed({
                if (state == AutomationState.NAVIGATING) runNavigating(attempt + 1)
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        if (attempt >= 10) {
            Log.w(TAG, "navigate: failed after $attempt attempts, waiting and retrying")
            handler.postDelayed({
                if (state == AutomationState.NAVIGATING) runNavigating(0)
            }, INTERVAL_WAIT_MS)
            return
        }

        // 不在农场页，尝试导航
        if (service.isNavigatingToFarm) {
            // 正在自动导航到农场页，跳过 controller 的自动操作避免干扰
            Log.d(TAG, "navigate: navigating to farm in progress, skip")
            handler.postDelayed({
                if (state == AutomationState.NAVIGATING) runNavigating(attempt)
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }
        if (service.isAdPlaying() || service.isAdActivity()) {
            Log.w(TAG, "navigate: in ad, trying to close")
            service.pressBack()
        } else if (service.isSearchRecommendPage()) {
            // 搜索推荐页 — 芭芭农场H5页没有加载出来
            // 关闭搜索页，下次导航改用"我的淘宝"路径（更可靠）
            Log.i(TAG, "navigate: search recommend page, closing (farm H5 didn't load)")
            debugLog("navigate: closing search page, will try 我的淘宝 path next")
            service.pressBack()
        } else if (service.isOnAbnormalPage()) {
            // 异常页面（支付宝收银台、商品详情页等），按返回退出
            Log.w(TAG, "navigate: on abnormal page, pressing back to exit")
            debugLog("navigate: abnormal page detected, pressing back")
            service.pressBack()
        } else if (!service.isFarmAppInForeground()) {
            // 农场 App 不在前台（如刚启动自动化时 bbncbot.MainActivity 还在前台，
            // 或农场 App 被切到后台/被系统回收）。
            // 历史问题：原逻辑只打印"waiting for relaunch"等待用户手动切到农场 App，
            // 但用户期望点"开始自动化"后 bot 应自己拉起农场 App。
            // 修复：attempt==0 时主动调用 reopenFarmByDeepLink 拉起农场 App；
            //       attempt>=1 时若仍未切到农场 App（可能 deep link 失败或 App 启动慢），
            //       再次调用 reopenFarmByDeepLink 重新拉起（避免卡死在 "waiting for relaunch"）。
            //       间隔由 INTERVAL_PAGE_LOAD_MS (5s) 控制，避免短时间内重复 kill+launch。
            if (attempt == 0 || attempt % 3 == 0) {
                // attempt==0：首次拉起；attempt % 3 == 0：每 3 轮（15s）重试一次拉起
                Log.i(TAG, "navigate: farm app not in foreground (platform=${service.currentPlatform}), actively relaunching (attempt=$attempt)")
                debugLog("navigate: farm app not in foreground (platform=${service.currentPlatform}, attempt=$attempt), calling reopenFarmByDeepLink")
                service.reopenFarmByDeepLink()
                // 延迟调用 navigateToFarm：等 App 启动后通过 AccessibilityEvent 识别平台，
                // 再调用 navigateToFarm 导航到芭芭农场 H5 页（reopenFarmByDeepLink 只启动 App 主页，
                // 不会自动进入农场页，必须靠 navigateToFarm 完成后续导航）。
                handler.postDelayed({
                    if (state == AutomationState.NAVIGATING && !service.isFarmAppInForeground()) {
                        debugLog("navigate: still not in farm app after relaunch, calling navigateToFarm to push navigation")
                        service.navigateToFarm()
                    }
                }, INTERVAL_PAGE_LOAD_MS)
            } else {
                Log.w(TAG, "navigate: farm app still not in foreground after relaunch (platform=${service.currentPlatform}, attempt=$attempt), waiting")
                debugLog("navigate: farm app still not in foreground after relaunch (attempt=$attempt), waiting for next retry")
            }
        } else {
            // 在农场 App 内但不在农场页（如淘宝主页），主动导航到芭芭农场
            Log.i(TAG, "navigate: in farm app but not farm page (platform=${service.currentPlatform}), calling navigateToFarm")
            debugLog("navigate: calling navigateToFarm, platform=${service.currentPlatform}")
            service.navigateToFarm()
        }

        handler.postDelayed({
            if (state == AutomationState.NAVIGATING) runNavigating(attempt + 1)
        }, INTERVAL_PAGE_LOAD_MS)
    }

    // ============== 阶段2: 收集直接可领取的肥料 ==============

    /**
     * 直接收集阶段：在农场主页上点击可直接领取的肥料
     * - 如"兔兔挖肥料，50肥料，可领取"、"4100，肥料，明日7点可领"
     * - 点击后等待弹窗，尝试关闭，然后检查是否还有更多可领取项
     * - 完成后进入打开任务列表阶段
     */
    private fun runCollectingDirect(attempt: Int) {
        if (state != AutomationState.COLLECTING_DIRECT) return
        val service = getService() ?: run { stop(); return }

        if (attempt == 0) {
            logPageSnapshot(service, "collectDirect-start")
        }

        if (attempt >= 5) {
            Log.i(TAG, "collectDirect: done after $attempt attempts, opening task list")
            moveTo(AutomationState.OPENING_TASK_LIST)
            handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
            return
        }

        // 查找所有直接可领取的肥料按钮
        val buttons = service.findDirectCollectButtons()
        debugLog("collectDirect: found ${buttons.size} direct buttons, attempt=$attempt")
        if (buttons.isEmpty()) {
            // build536 修复（用户反馈"'点击领取'在主页下方"）：
            // 历史问题：主页下方的"点击领取"按钮（每日登录/7天奖励等）在屏幕外，
            // H5 WebView 未渲染该区域，accessibility tree 里也找不到对应节点。
            // 直接跳到 OPENING_TASK_LIST 会漏掉这些主页下方的领取入口。
            //
            // 日志证据（debug_test_20260719_091413.log, build536-3bd28a1）：
            //   09:10:09.812 collectDirect: found 0 direct buttons, attempt=0
            //   主页 sample=[松开刷新, 任务列表, 农场乐园入口, 还差3次领肥料, 切换摇钱树,
            //             逛农货, 我的收获, 芭芭农场,种果树得水果，助农增收, 钓红包, 亲友树]
            //   主页第一屏根本没有"点击领取"——在屏幕下方未渲染。
            //
            // 修复：找不到 direct 按钮时，先滚动主页让下方内容进入可见区域，再重试。
            // 滚动策略（双重兜底）：
            //   - 优先 NodeFinder.scrollDown（performAction ACTION_SCROLL_FORWARD，精确）
            //   - 失败回退 dispatchGestureSwipe（手势滑动，兼容 H5 WebView）
            //   - 向上滑（startY > endY）让页面向下滚动，露出下方内容
            //   - attempt 0~3 各滚动一次（共 4 次），相当于露出主页下方约 4 屏内容；
            //     attempt 4 仍找不到才进入 OPENING_TASK_LIST。
            if (attempt < 4) {
                val root = service.getRootInFarmApp()
                val scrolled = NodeFinder.scrollDown(root)
                if (!scrolled) {
                    // 手势兜底：从屏幕下部 (600, 1800) 向上滑到 (600, 600)，
                    // 页面向下滚动约 1200 像素，露出下方内容。
                    // 屏幕 1200x2664，baseY=1800 在屏幕下半部（避开顶部状态栏和底部 tab 栏）。
                    service.dispatchGestureSwipe(600f, 1800f, 600f, 600f, 500L)
                    debugLog("collectDirect: no scrollable node, used gesture swipe down (attempt=$attempt)")
                } else {
                    debugLog("collectDirect: scrolled via ACTION_SCROLL_FORWARD (attempt=$attempt)")
                }
                handler.postDelayed({ runCollectingDirect(attempt + 1) }, INTERVAL_PAGE_LOAD_MS)
                return
            }
            Log.i(TAG, "collectDirect: no direct collect buttons found after scrolling, opening task list")
            moveTo(AutomationState.OPENING_TASK_LIST)
            handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
            return
        }

        // 点击第一个可领取的按钮
        val button = buttons[0]
        val btnText = button.text?.toString().orEmpty()
        val btnDesc = button.contentDescription?.toString().orEmpty()
        debugLog("collectDirect: clicking text='$btnText' desc='$btnDesc' (attempt ${attempt + 1})")
        Log.i(TAG, "collectDirect: clicking '$btnText' (attempt ${attempt + 1})")
        service.performClickSafe(button)

        // 等待弹窗或页面变化（多策略领取：弹窗可能延迟出现，多次尝试找确认按钮）
        tryClaimDirectPopup(service, attempt, maxRetry = 3)
    }

    /**
     * 多策略领取直接肥料弹窗（优化方案）
     *
     * 弹窗可能延迟出现，单次查找会漏领。本方法多次尝试找确认按钮：
     * - 每次尝试间隔 INTERVAL_CLICK_MS
     * - 找到则点击，并继续检测浏览入口
     * - 未找到则减少剩余重试次数，继续等待
     * - 重试耗尽则继续下一个 direct 按钮
     *
     * @param service 无障碍服务
     * @param attempt 当前 direct 按钮的尝试序号
     * @param maxRetry 最大弹窗确认重试次数
     */
    private fun tryClaimDirectPopup(
        service: FarmAccessibilityService,
        attempt: Int,
        maxRetry: Int
    ) {
        var retryLeft = maxRetry
        fun attemptClaim() {
            if (state != AutomationState.COLLECTING_DIRECT) return
            // 尝试点击确认领取按钮（精确匹配，已含诱导黑名单过滤）
            val claimBtn = service.findClaimRewardButtonExact()
            if (claimBtn != null) {
                Log.i(TAG, "collectDirect: found exact claim button (retry left=$retryLeft), clicking")
                debugLog("collectDirect: claim button found, clicking (retry left=$retryLeft)")
                service.performClickSafe(claimBtn)
                // 领取后等待弹窗按钮文字更新（"立即领取"→"点此逛一逛再赚1000肥料"）
                handler.postDelayed({
                    if (state != AutomationState.COLLECTING_DIRECT) return@postDelayed
                    // 检查弹窗内是否出现浏览入口（"点此逛一逛"/"再赚"等）
                    val browseEntry = service.findBrowseEntryInPopup()
                    if (browseEntry != null) {
                        Log.i(TAG, "collectDirect: found browse entry in popup after claim, entering BROWSING_TASK")
                        debugLog("collectDirect: browse entry found, switching to BROWSING_TASK")
                        taskButtons = listOf(browseEntry)
                        currentTaskIndex = 0
                        taskListCheckAttempt = 0
                        browseFromDirectPopup = true  // 标记：浏览完成后回 COLLECTING_DIRECT
                        moveTo(AutomationState.BROWSING_TASK)
                        handler.postDelayed({ runBrowsingTask(swipeCount = 0) }, INTERVAL_CLICK_MS)
                        return@postDelayed
                    }
                    // 没有浏览入口，继续检查下一个 direct 按钮
                    if (state == AutomationState.COLLECTING_DIRECT) runCollectingDirect(attempt + 1)
                }, INTERVAL_CLICK_MS)
                return
            }
            // 未找到确认按钮，减少重试次数
            retryLeft--
            if (retryLeft > 0) {
                debugLog("collectDirect: claim button not found, retrying (retry left=$retryLeft)")
                handler.postDelayed({ attemptClaim() }, INTERVAL_CLICK_MS)
            } else {
                // 重试耗尽，继续下一个 direct 按钮
                debugLog("collectDirect: claim button not found after $maxRetry retries, moving to next direct button")
                if (state == AutomationState.COLLECTING_DIRECT) runCollectingDirect(attempt + 1)
            }
        }
        // 首次等待页面加载后再开始尝试
        handler.postDelayed({ attemptClaim() }, INTERVAL_PAGE_LOAD_MS)
    }

    // ============== 阶段3: 打开任务列表 ==============

    /**
     * 打开任务列表阶段：点击"集肥料"按钮
     * - 优先使用节点树查找"集肥料"按钮
     * - 失败时尝试坐标候选位置
     * - 成功后进入处理任务阶段
     */
    private fun runOpeningTaskList(attempt: Int) {
        if (state != AutomationState.OPENING_TASK_LIST) return
        val service = getService() ?: run { stop(); return }

        if (attempt == 0) {
            logPageSnapshot(service, "openTaskList-start")
            // 闭环规则：每轮任务循环重新打开任务列表时，重置"已点集肥料"标志
            // 确保支付宝每次新一轮都会先点集肥料调出任务列表，而非沿用上轮残留按钮。
            // attempt>0 的重试不重置（避免点完集肥料后又重置导致死循环）。
            if (taskListOpenedThisRound) {
                taskListOpenedThisRound = false
                debugLog("openTaskList: reset taskListOpenedThisRound for new round (attempt=0)")
            }
            // build530 修复（debug_test_20260719_045429.log, build530-9ab1929）：
            // 历史问题：第一轮 PROCESSING_TASK 结束回 OPENING_TASK_LIST 后，currentTaskIndex
            // 保留上一轮的值（如 currentTaskIndex=2），第二轮 processTask 从 #3 开始，
            // 跳过了 priority=0 的 pure claim "领取"按钮（在排序后的 idx=0 位置）。
            // 日志证据：
            //   第一轮：currentTaskIndex=0 → skip #1 → currentTaskIndex=1 → skip #2 → currentTaskIndex=2
            //   第二轮：processTask: current task #3/5  ← 从 #3 开始，跳过了 #1 和 #2
            //   （#1 是排序后的 priority=0 "领取"按钮，被跳过导致肥料没领取）
            //
            // 修复：每轮 OPENING_TASK_LIST 开始时（attempt==0），重置 currentTaskIndex=0
            // 并清空 taskButtons，确保 checkTaskListOpened 会重新填充并重置索引。
            if (currentTaskIndex != 0 || taskButtons.isNotEmpty()) {
                debugLog("openTaskList: resetting currentTaskIndex to 0 for new round (was $currentTaskIndex, taskButtons.size=${taskButtons.size})")
                currentTaskIndex = 0
                taskButtons = emptyList()
            }
        }

        if (attempt >= MAX_TASK_LIST_ATTEMPTS) {
            Log.w(TAG, "openTaskList: failed after $attempt attempts, re-navigating")
            noProgressRounds++
            if (noProgressRounds >= MAX_NO_PROGRESS_ROUNDS) {
                Log.w(TAG, "openTaskList: too many no-progress rounds, navigating")
                moveTo(AutomationState.NAVIGATING)
                handler.postDelayed({ runNavigating(0) }, INTERVAL_WAIT_MS)
            } else {
                moveTo(AutomationState.NAVIGATING)
                handler.postDelayed({ runNavigating(0) }, INTERVAL_CLICK_MS)
            }
            return
        }

        // 闭环规则：支付宝/淘宝/UC 每次进入 OPENING_TASK_LIST 必须保证在任务列表页（截图页面）
        // 用户要求：三个平台农场页起始画面（任务开始前）和任务结束后都要停在任务列表页，形成闭环。
        //           具体点哪个按钮进去任务由内置启发式逻辑决定（不写死"集肥料"）。
        // 实现：每轮重新打开任务列表时（taskListOpenedThisRound=false）：
        //   - 先检查页面是否已有"去完成"按钮（任务列表本就开着，如 UC 主页）→ 直接处理，无需再点
        //   - 没有则用 findCollectFertilizerButton 文本查找入口按钮 + 坐标兜底
        // 这样既保证闭环（任务结束后回到任务列表页），又不破坏 UC 任务入口原本在主页的行为。
        if (service.currentPlatform != Platform.UNKNOWN && !taskListOpenedThisRound) {
            // 先检查页面上是否已有可见的"去完成"按钮（UC 主页任务入口直接可见的情况）
            val visibleGoComplete = service.findGoCompleteButtons()
            if (visibleGoComplete.isEmpty()) {
                // 任务列表未打开：用文本查找 + 坐标兜底调出任务列表
                taskListOpenedThisRound = true  // 标记本轮已尝试调出，避免重复进入死循环
                debugLog("openTaskList: [${service.currentPlatform}闭环] no goComplete buttons visible, opening task list (attempt=$attempt, using heuristic)")
                // 启发式逻辑：用 collectFertilizerTexts 文本查找入口按钮
                debugLog("openTaskList: [${service.currentPlatform}闭环] heuristic text search (attempt=$attempt)")
                val entryButton = service.findCollectFertilizerButton()
                if (entryButton != null) {
                    debugLog("openTaskList: [${service.currentPlatform}闭环] clicking entry button by text (attempt=$attempt)")
                    service.performClickSafe(entryButton)
                } else {
                    // 坐标兜底前必须确认在农场页：
                    // 历史问题——当 gamePlay 后未真正回到农场页（仍在蚂蚁庄园等小程序页）时，
                    // findCollectFertilizerButton 返回 null，但代码会继续按坐标点击"集肥料"按钮位置，
                    // 结果点开了相邻页面的入口（如"小鸡乐园活动规则"页），打开无关规则条款页 → 误识别为
                    // 任务按钮 → isPaidTask 误判 → 整个平台被错误标记为"已完成"。
                    // 修复：不在农场页时禁用坐标兜底，转回 NAVIGATING 重新导航到农场页。
                    if (!service.isOnFarmPage()) {
                        debugLog("openTaskList: [${service.currentPlatform}闭环] not on farm page (no entry button and not on farm), re-navigating instead of coordinate fallback")
                        taskListOpenedThisRound = false  // 重置，让导航回农场后下一轮还能尝试
                        moveTo(AutomationState.NAVIGATING)
                        handler.postDelayed({ runNavigating(0) }, INTERVAL_CLICK_MS)
                        return
                    }
                    // 在农场页才允许坐标兜底（H5 页面 WebView 文本不暴露的情况）
                    val candidates = collectFertilizerCandidates(service)
                    if (candidates.isNotEmpty()) {
                        // build532 修复（debug_test_20260719_063933.log, build532-6d5c936）：
                        // 历史问题：findCollectFertilizerButton 返回 null 时，原逻辑立即坐标兜底点击
                        // (0.888, 0.771) 即 (1065, 1960)。但日志显示当时 root childCount=1，
                        // 说明 WebView 还在加载，主页内容未渲染。提前点击该坐标可能误触发系统事件，
                        // 导致 bot 自己切到前台（activity=com.bbncbot.mainactivity），后续 root 一直为 null。
                        //
                        // 修复：坐标兜底前先检查 root 内容量。若 root 子节点太少（childCount<3
                        // 或估算后代<10），说明 WebView 还在加载，不执行坐标点击，转回 NAVIGATING
                        // 重新等待页面加载（最多重试若干次）。
                        val rootForGuard = service.getRootInFarmApp()
                        val rootChildCount = rootForGuard?.childCount ?: 0
                        val descendantEstimate = if (rootForGuard != null) {
                            rootChildCount + (0 until rootChildCount).sumOf {
                                (rootForGuard.getChild(it)?.childCount ?: 0)
                            }
                        } else 0
                        if (rootChildCount < 3 || descendantEstimate < 10) {
                            debugLog("openTaskList: [${service.currentPlatform}闭环] WebView not ready (rootChildCount=$rootChildCount, descendantEstimate=$descendantEstimate), skip coordinate click, re-navigate")
                            taskListOpenedThisRound = false
                            moveTo(AutomationState.NAVIGATING)
                            handler.postDelayed({ runNavigating(0) }, INTERVAL_PAGE_LOAD_MS)
                            return
                        }
                        val coordIndex = attempt % candidates.size
                        val (xRatio, yRatio) = candidates[coordIndex]
                        debugLog("openTaskList: [${service.currentPlatform}闭环] no entry button, clicking by coordinate #$coordIndex (attempt=$attempt)")
                        clickAtRatio(service, xRatio, yRatio, "集肥料")
                    } else {
                        debugLog("openTaskList: [${service.currentPlatform}闭环] no entry found (text+coord), reset flag for next round")
                        taskListOpenedThisRound = false  // 重置，让下一轮还能尝试
                    }
                }
                handler.postDelayed({
                    if (state == AutomationState.OPENING_TASK_LIST) checkTaskListOpened(service, attempt)
                }, INTERVAL_PAGE_LOAD_MS)
                return
            } else {
                // 任务列表本就开着（UC 主页有去完成按钮），标记本轮已确认，直接走下面的处理
                taskListOpenedThisRound = true
                debugLog("openTaskList: [${service.currentPlatform}闭环] ${visibleGoComplete.size} goComplete buttons already visible, task list open, processing directly")
            }
        }

        // 优先检查：页面上是否已有"去完成"按钮（UC 等平台任务入口直接在主页上，无需点击"集肥料"打开任务列表）
        // 用户需求：UC 主页上有多个任务入口（看视频、浏览广告等），选择一个打开，没获取到肥料就选另一个
        val existingButtons = service.findGoCompleteButtons()
        if (existingButtons.isNotEmpty()) {
            Log.i(TAG, "openTaskList: found ${existingButtons.size} goComplete buttons directly on page (no need to click 集肥料), platform=${service.currentPlatform}")
            debugLog("openTaskList: ${existingButtons.size} goComplete buttons already visible, processing directly (attempt=$attempt)")
            existingButtons.forEachIndexed { idx, btn ->
                val rect = Rect()
                btn.getBoundsInScreen(rect)
                val txt = btn.text?.toString().orEmpty()
                val desc = btn.contentDescription?.toString().orEmpty()
                debugLog("taskButton[$idx]: text='$txt', desc='$desc', bounds=${rect.toShortString()}, clickable=${btn.isClickable}")
            }
            if (taskButtons.isEmpty() || currentTaskIndex >= existingButtons.size) {
                debugLog("openTaskList: resetting currentTaskIndex to 0 (was $currentTaskIndex)")
                currentTaskIndex = 0
            }
            taskButtons = sortTaskButtonsByPriority(service, existingButtons)
            taskListCheckAttempt = 0
            moveTo(AutomationState.PROCESSING_TASK)
            handler.postDelayed({ runProcessingTask(attempt = 0) }, INTERVAL_CLICK_MS)
            return
        }

        // 优先检查：页面上是否有直接可领取的肥料按钮（如"领取肥料礼包"、"立即领取肥料"）
        // 用户需求：点击这类按钮会弹出窗口，需在窗口里点"立即领取"才能领到肥料，应走 directCollect 流程
        // 而非当成"集肥料"入口去找任务列表
        val directButtons = service.findDirectCollectButtons()
        if (directButtons.isNotEmpty()) {
            Log.i(TAG, "openTaskList: found ${directButtons.size} direct collect buttons, switching to COLLECTING_DIRECT")
            debugLog("openTaskList: ${directButtons.size} direct collect buttons found, switching to COLLECTING_DIRECT (attempt=$attempt)")
            moveTo(AutomationState.COLLECTING_DIRECT)
            handler.postDelayed({ runCollectingDirect(attempt = 0) }, INTERVAL_CLICK_MS)
            return
        }

        // 优先查找"集肥料"按钮节点
        val button = service.findCollectFertilizerButton()
        debugLog("openTaskList: findCollectFertilizerButton=${button != null}, attempt=$attempt")
        if (button != null) {
            Log.i(TAG, "openTaskList: found 集肥料 button by text, clicking (attempt ${attempt + 1})")
            service.performClickSafe(button)
            handler.postDelayed({
                if (state == AutomationState.OPENING_TASK_LIST) checkTaskListOpened(service, attempt)
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 失败时尝试坐标候选位置
        // P0-3（build513 修复）：加 isOnFarmPage 守卫，避免在非农场页（如 systemui 覆盖、
        // 子小程序页、广告页）按坐标点"集肥料"位置误触其他 App 元素。
        // 与第一处坐标兜底（行 877-890）保持一致的守卫策略。
        if (!service.isOnFarmPage()) {
            debugLog("openTaskList: [坐标兜底] not on farm page (findCollectFertilizerButton=null), re-navigating instead of coordinate fallback")
            taskListOpenedThisRound = false
            moveTo(AutomationState.NAVIGATING)
            handler.postDelayed({ runNavigating(0) }, INTERVAL_CLICK_MS)
            return
        }
        // build532 修复（与第一处坐标兜底守卫一致）：WebView 未渲染完成时（root childCount
        // 太小）不执行坐标点击，避免误触发系统事件导致 bot 切到前台。
        val rootForGuard = service.getRootInFarmApp()
        val rootChildCount = rootForGuard?.childCount ?: 0
        val descendantEstimate = if (rootForGuard != null) {
            rootChildCount + (0 until rootChildCount).sumOf {
                (rootForGuard.getChild(it)?.childCount ?: 0)
            }
        } else 0
        if (rootChildCount < 3 || descendantEstimate < 10) {
            debugLog("openTaskList: [坐标兜底] WebView not ready (rootChildCount=$rootChildCount, descendantEstimate=$descendantEstimate), skip coordinate click, re-navigate")
            taskListOpenedThisRound = false
            moveTo(AutomationState.NAVIGATING)
            handler.postDelayed({ runNavigating(0) }, INTERVAL_PAGE_LOAD_MS)
            return
        }
        val candidates = collectFertilizerCandidates(service)
        val coordIndex = attempt % candidates.size
        val (xRatio, yRatio) = candidates[coordIndex]
        Log.i(TAG, "openTaskList: clicking 集肥料 by coordinate #$coordIndex (attempt ${attempt + 1}) platform=${service.currentPlatform}")
        clickAtRatio(service, xRatio, yRatio, "集肥料")
        handler.postDelayed({
            if (state == AutomationState.OPENING_TASK_LIST) checkTaskListOpened(service, attempt)
        }, INTERVAL_PAGE_LOAD_MS)
    }

    /** 检查任务列表是否已打开（带等待重试） */
    private var taskListCheckAttempt: Int = 0

    private fun checkTaskListOpened(service: FarmAccessibilityService, openingAttempt: Int) {
        if (state != AutomationState.OPENING_TASK_LIST) return

        // 查找"去完成"按钮
        val buttons = service.findGoCompleteButtons()
        debugLog("checkTaskListOpened: found ${buttons.size} goComplete buttons, checkAttempt=$taskListCheckAttempt, currentIndex=$currentTaskIndex")
        if (buttons.isNotEmpty()) {
            Log.i(TAG, "openTaskList: task list opened with ${buttons.size} tasks")
            // 输出每个任务按钮的详细信息（text/desc/bounds），用于诊断"点击位置是否正确"
            buttons.forEachIndexed { idx, btn ->
                val rect = Rect()
                btn.getBoundsInScreen(rect)
                val txt = btn.text?.toString().orEmpty()
                val desc = btn.contentDescription?.toString().orEmpty()
                debugLog("taskButton[$idx]: text='$txt', desc='$desc', bounds=${rect.toShortString()}, clickable=${btn.isClickable}")
            }
            // 只在首次打开（currentTaskIndex 超出范围或 taskButtons 为空）时重置索引
            // 保留 currentTaskIndex 的值，避免重新打开任务列表后重复点击已跳过的任务
            if (taskButtons.isEmpty() || currentTaskIndex >= buttons.size) {
                debugLog("checkTaskListOpened: resetting currentTaskIndex to 0 (was $currentTaskIndex, taskButtons was empty=${taskButtons.isEmpty()})")
                currentTaskIndex = 0
            }
            taskButtons = sortTaskButtonsByPriority(service, buttons)
            taskListCheckAttempt = 0
            moveTo(AutomationState.PROCESSING_TASK)
            handler.postDelayed({ runProcessingTask(attempt = 0) }, INTERVAL_CLICK_MS)
            return
        }

        // 任务列表可能还在加载中，等待重试（最多5次，每次2秒）
        taskListCheckAttempt++
        if (taskListCheckAttempt < 5) {
            Log.i(TAG, "openTaskList: task list not opened yet, waiting (check $taskListCheckAttempt)")
            handler.postDelayed({
                if (state == AutomationState.OPENING_TASK_LIST) checkTaskListOpened(service, openingAttempt)
            }, INTERVAL_CLICK_MS)
            return
        }

        // 等待超时，重新点击"集肥料"
        taskListCheckAttempt = 0
        Log.w(TAG, "openTaskList: task list not opened after 5 checks, retrying click")
        handler.postDelayed({
            if (state == AutomationState.OPENING_TASK_LIST) runOpeningTaskList(openingAttempt + 1)
        }, INTERVAL_CLICK_MS)
    }

    /**
     * 任务按钮排序（稳定排序，保留原有视觉顺序）
     *
     * 优先级策略（用户需求：点击领取/签到就能完成的任务优先处理）：
     * - priority 0：签到/领取类任务（无需跳转、点击即完成，最容易拿肥料）
     *   识别关键词：去签到/签到、去领取/领取/立即领取/点击领取、立即完成
     *   注意：必须排除付费陷阱（如"立即完成购买"），用 isPaidTask 兜底过滤
     * - priority 1：其他普通任务（去完成/去逛逛/去观看 等）
     *
     * 同优先级内保持原有视觉顺序（稳定排序）。
     */
    private fun sortTaskButtonsByPriority(
        service: FarmAccessibilityService,
        buttons: List<AccessibilityNodeInfo>
    ): List<AccessibilityNodeInfo> {
        if (buttons.size <= 1) return buttons
        val platform = service.currentPlatform.name
        if (platform == "UNKNOWN") return buttons
        // 签到/领取类任务识别关键词（任务标题或按钮文字命中即视为"易完成任务"）
        // P1-3（build513 修复）：收紧关键词，避免"领取后退款将扣回肥料"等付费陷阱被误判为 easyClaim。
        // - 移除宽泛的"领取"/"收下"
        // - 保留带动词前缀的精确短语："去签到""立即领取""点击领取""开心收下"
        // - 额外排除"退款"/"扣回"/"下单后"等付费暗示词
        // build518 补丁：恢复 button.text == "领取" 精确匹配（用户反馈纯"领取"按钮应优先），
        // 但 contextText 含"领取"仍不匹配（避免"领取后退款"等陷阱描述命中）
        val easyClaimKeywords = listOf(
            "去签到",        // 每日签到任务（明确动词）
            "签到领",        // "签到领肥料"等
            "去领取",        // 明确"去领取"动作
            "立即领取",      // 红包弹窗的立即领取
            "点击领取",      // 用户明确提到的"点击领取"
            "开心收下",      // 红包弹窗的收下按钮
            "签到"           // 每日签到（短词，但任务列表里"签到"通常是真的签到任务）
        )
        // 付费/退款暗示词：即使命中 easyClaimKeywords，若上下文含这些词也降级到 priority 1
        val paidHintKeywords = listOf(
            "退款", "扣回", "扣减", "下单后", "购买后", "充值后", "消费满",
            "任意下单", "下单领", "下单赢", "任意充值"
        )
        return buttons.mapIndexed { idx, btn ->
            val taskContent = SceneFeatureExtractor.extractTaskContentText(service, btn)
            // 检查任务上下文（含任务标题）和按钮文本本身
            val contextText = service.collectTaskContextText(btn)
            val buttonText = btn.text?.toString().orEmpty()
            val fullText = "$contextText $buttonText"
            // 排除付费陷阱：即使是"领取"类，若 isPaidTask=true 也降级到 priority 1
            val isPaid = service.isPaidTask(btn)
            // P1-3：额外检查付费暗示词，避免 isPaidTask 漏判（paidKeywords 不全）
            val hasPaidHint = paidHintKeywords.any { fullText.contains(it) }
            // build518 补丁：button.text 精确等于"领取"或"收下"也视为 easyClaim
            // （纯领取按钮是点击即得肥料的简单任务），但 contextText 不能含付费暗示词
            // build523：扩展为所有纯领取按钮文案（与 processTask 的 isPureClaimClick 保持一致）
            val pureClaimTexts = setOf(
                "领取", "收下", "立即领取", "点击领取", "开心收下",
                "立即领肥", "领取肥料", "立即领肥[料]"
            )
            val isPureClaimButton = buttonText in pureClaimTexts && !hasPaidHint
            val isEasyClaim = !isPaid && !hasPaidHint &&
                (easyClaimKeywords.any { fullText.contains(it) } || isPureClaimButton)
            val priority = if (isEasyClaim) 0 else 1
            debugLog("sortTaskButtons: idx=$idx task='$taskContent' priority=$priority (easyClaim=$isEasyClaim, paid=$isPaid, paidHint=$hasPaidHint, pureClaim=$isPureClaimButton, button='$buttonText')")
            Triple(priority, idx, btn)
        }.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }
    }

    // ============== 阶段3: 处理任务（点击去完成按钮） ==============

    /**
     * 处理任务阶段：点击"去完成"按钮
     * - 用户要求：只点击广告，邀请推广网页直接返回
     * - 用户要求：安装软件的广告也不做，不安装，直接退出
     * - 点击后检测是否进入广告，如果是广告则进入看广告阶段
     * - 如果是非广告页面，直接返回继续下一个任务
     */
    private fun runProcessingTask(attempt: Int) {
        if (state != AutomationState.PROCESSING_TASK) return
        val service = getService() ?: run { stop(); return }

        // 兜底：从 direct 弹窗进入浏览后若节点失效被踢回 PROCESSING_TASK，
        // 不应继续处理仅含浏览节点的 taskButtons，应回 COLLECTING_DIRECT 重新找 direct 按钮
        if (browseFromDirectPopup) {
            debugLog("processTask: entered with browseFromDirectPopup=true, returning to COLLECTING_DIRECT")
            browseFromDirectPopup = false
            moveTo(AutomationState.COLLECTING_DIRECT)
            handler.postDelayed({ runCollectingDirect(attempt = 0) }, INTERVAL_CLICK_MS)
            return
        }

        if (attempt == 0) {
            logPageSnapshot(service, "processTask-start")
        }

        // 如果任务列表为空或已处理完，进入施肥阶段
        if (taskButtons.isEmpty() || currentTaskIndex >= taskButtons.size) {
            Log.i(TAG, "processTask: all tasks processed (collected=$collectedCount), starting fertilizing")
            debugLog("processTask: all ads/tasks done on ${service.currentPlatform}, collected=$collectedCount")
            // 标记当前平台广告已获取完，并检查三平台是否全部完成
            markPlatformAdsComplete(service)
            moveTo(AutomationState.FERTILIZING)
            handler.postDelayed({ runFertilizing(clickCount = 0) }, INTERVAL_CLICK_MS)
            return
        }

        if (attempt >= MAX_TASK_ATTEMPTS) {
            Log.w(TAG, "processTask: task #$currentTaskIndex failed after $attempt attempts, skipping")
            currentTaskIndex++
            noProgressRounds++
            handler.postDelayed({
                if (state == AutomationState.PROCESSING_TASK) runProcessingTask(0)
            }, INTERVAL_CLICK_MS)
            return
        }

        // 确保在农场页
        if (!service.isOnFarmPage()) {
            Log.w(TAG, "processTask: not on farm page, returning")
            service.pressBack()
            handler.postDelayed({
                if (state == AutomationState.PROCESSING_TASK) runProcessingTask(attempt + 1)
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 判断当前任务类型
        val button = taskButtons[currentTaskIndex]
        val buttonText = button.text?.toString().orEmpty()
        val btnRect = Rect()
        button.getBoundsInScreen(btnRect)
        debugLog("processTask: current task #${currentTaskIndex + 1}/${taskButtons.size}, text='$buttonText', bounds=${btnRect.toShortString()}, attempt=$attempt")

        // 1. 花钱任务：跳过不处理
        if (service.isPaidTask(button)) {
            Log.i(TAG, "processTask: task #${currentTaskIndex + 1} is paid task, skipping (text='$buttonText')")
            debugLog("processTask: skip paid task #$${currentTaskIndex + 1}, text='$buttonText'")
            currentTaskIndex++
            handler.postDelayed({
                if (state == AutomationState.PROCESSING_TASK) runProcessingTask(0)
            }, 500L)
            return
        }

        // 1a-bis. "下单"字眼任务一律跳过（用户明确要求）
        // 用户反馈："任意下单领大额肥料"这个任务为啥还点击，"下单"字眼任务都不点击
        // 注意：必须排除"下单得"（浏览搜索结果得肥料，无需下单），但"任意下单"/"下单领"/"下单赢"都要跳过
        // 同时检查 buttonText 和 taskContextText（任务标题在上下文中）
        val taskContextText = service.collectTaskContextText(button)
        val fullTaskText = "$buttonText $taskContextText"
        // 明确要求下单的文案（"下单"前后跟动词/量词，不是单独的"下单得"）
        val hasPaidOrderKeyword = fullTaskText.contains("任意下单") ||
            fullTaskText.contains("下单领") ||
            fullTaskText.contains("下单赢") ||
            fullTaskText.contains("下单返") ||
            fullTaskText.contains("下单得大额") ||
            fullTaskText.contains("下单购买") ||
            fullTaskText.contains("立即下单")
        if (hasPaidOrderKeyword) {
            Log.i(TAG, "processTask: task #${currentTaskIndex + 1} has paid order keyword (下单), skipping (text='$buttonText', context='$taskContextText')")
            debugLog("processTask: skip order task #$${currentTaskIndex + 1}, button='$buttonText', context='$taskContextText'")
            currentTaskIndex++
            handler.postDelayed({
                if (state == AutomationState.PROCESSING_TASK) runProcessingTask(0)
            }, 500L)
            return
        }

        // 1a. 已完成任务直接跳过
        // 用户反馈："(1/1)" "(2/2)" 这种 X/X 样式的任务已完成，不需要再点。
        // 任务列表里 "X/Y" 表示 "当前进度/总进度"，X==Y 说明已完成。
        // 匹配模式：(N/N) 或 （N/N），N 为正整数（也兼容无括号的 N/N 但要避免误匹配日期）
        // 注：taskContextText 已在 1a-bis 块声明，这里复用
        val completedRegex = Regex("""[(（](\d+)/\1[)）]""")
        val completedMatch = completedRegex.find(taskContextText) ?: completedRegex.find(buttonText)
        if (completedMatch != null) {
            Log.i(TAG, "processTask: task #${currentTaskIndex + 1} already completed (matched '${completedMatch.value}'), skipping (text='$buttonText', context='$taskContextText')")
            debugLog("processTask: skip completed task #$${currentTaskIndex + 1}, matched='${completedMatch.value}', context='$taskContextText'")
            currentTaskIndex++
            handler.postDelayed({
                if (state == AutomationState.PROCESSING_TASK) runProcessingTask(0)
            }, 500L)
            return
        }

        // 1b. 跳过名单：特定任务直接跳过，不点击
        // 用户要求：过滤=不点击直接跳过
        // 同时检查按钮文本和任务上下文文本（任务标题在上下文中，不在按钮文本里）
        val skipTaskTexts = listOf(
            "继续玩浪漫餐厅", "继续玩农场分色瓶", "充值", "砸蛋", "砸金蛋",
            "分享", "合种", "到店支付",
            // 试玩/玩游戏类任务：自动化无法玩小程序游戏，跳过
            "试玩", "新游", "玩游戏", "玩1局", "玩一局", "开局", "对战",
            "完成1局", "完成一局", "打一局"
        )
        // build530 修复（debug_test_20260719_045429.log, build530-9ab1929）：
        // 历史问题：【福利】试玩热门新游 访问必得500-3500肥 的"领取"按钮被 skipTaskTexts
        // 中的"试玩"/"新游"关键词跳过，导致 pure claim 路径没机会执行，肥料没领取。
        // 日志证据：
        //   sortTaskButtons: idx=3 task='' priority=0 (pureClaim=true, button='领取')
        //   processTask: current task #1/5, text='领取', context='【福利】试玩热门新游 访问必得500-3500肥(0/3500) ...'
        //   processTask: skip list task #1, text='领取', context='...'  ← 被 skipTaskTexts 跳过！
        //   processTask: all ads/tasks done on ALIPAY, collected=0  ← 没拿到肥料
        //
        // 修复：pure claim 按钮（领取/收下/立即领取等，且非付费任务）优先于 skipTaskTexts，
        // 不被"试玩"/"新游"等关键词跳过。pure claim 按钮点击即得肥料，与任务文案无关。
        val pureClaimEarlyTexts = setOf(
            "领取", "收下", "立即领取", "点击领取", "开心收下",
            "立即领肥", "领取肥料"
        )
        val isPureClaimEarly = buttonText in pureClaimEarlyTexts && !fullTaskText.let { ft ->
            listOf("退款", "扣回", "扣减", "下单后", "购买后", "充值后", "消费满",
                "任意下单", "下单领", "下单赢", "任意充值").any { ft.contains(it) }
        }
        val shouldSkip = !isPureClaimEarly &&
            skipTaskTexts.any { buttonText.contains(it) || taskContextText.contains(it) }
        if (shouldSkip) {
            Log.i(TAG, "processTask: task #${currentTaskIndex + 1} in skip list, skipping (text='$buttonText', context='$taskContextText')")
            debugLog("processTask: skip list task #${currentTaskIndex + 1}, text='$buttonText', context='$taskContextText'")
            currentTaskIndex++
            handler.postDelayed({
                if (state == AutomationState.PROCESSING_TASK) runProcessingTask(0)
            }, 500L)
            return
        }

        // 2. 游戏类任务：打开游戏停留玩一下即可获取肥料（无法实现过关卡，仅靠停留拿奖励）
        // P0-D（build520 修复）：纯"领取"/"收下"按钮是 easyClaim（点击即得肥料），
        // 必须在 isGameTask 之前短路，避免 contextText 含"蚂蚁庄园"/"蚂蚁森林"等
        // 游戏关键词时被误判为 game task，导致进入 GAME_PLAYING 反而关闭了任务列表弹窗。
        //
        // 历史问题（debug_test_20260718_211741.log, build518-93f7a54）：
        // - 蚂蚁庄园浏览任务（buttonText='领取', contextText='逛一逛蚂蚁庄园...限时加倍浏览即得500肥'）
        // - isGameTask 检测 contextText 含"蚂蚁庄园" → 返回 true
        // - 进入 GAME_PLAYING 后点击了任务列表弹窗的"关闭"按钮，关闭了任务列表而非领取肥料
        // - 实际"领取"按钮就是直接点击领取，无需进入游戏流程
        //
        // 修复：buttonText 精确等于"领取"/"收下" 且无付费暗示 → 直接点击领取，
        // 等待领取结果后回 OPENING_TASK_LIST（与 isPaidTask 跳过路径的等待时长保持一致）
        //
        // build521 增强（用户反馈："显示'肥料已发放'，就应该返回之前窗口"）：
        // 点击"领取"后弹窗会显示"肥料已发放"领取成功提示，此时必须 pressBack 关闭弹窗
        // 返回任务列表，而不是直接回 OPENING_TASK_LIST 走"找任务列表按钮"路径
        // （弹窗未关闭时 findGoCompleteButtons 找不到"去完成"按钮，会误判需要重新打开任务列表）
        //
        // build523 修复（debug_test_20260718_214200.log, build521-c2f9e26）：
        // - 日志发现 task #1/1 buttonText='立即领取' bounds=[278,1660][923,1807]
        // - isPureClaimClick 只匹配 '领取'/'收下'，'立即领取' 不匹配 → 没走 pureClaimClick 路径
        // - 走了标准 checkTaskResult 路径，点击了左上角"返回首页"按钮（desc='返回' bounds=[26,127]）
        // - 返回到支付宝首页，而不是 pressBack 关闭"肥料已发放"弹窗返回任务列表
        // - 虽然 task #1/1 实际已领取成功（第二轮任务列表里"立即领取"消失了），
        //   但 collected=0 没正确统计，且多走了"返回首页→重新进任务列表"的弯路
        //
        // 修复：扩展 isPureClaimClick 匹配所有 Platform.goCompleteTexts/directCollectTexts 里
        // 定义的纯领取按钮文案（立即领取/点击领取/开心收下/立即领肥/领取肥料 等）
        // 这些按钮都是"点击即得肥料，无需跳转"的纯领取入口，应走 pureClaimClick 路径
        val pureClaimButtonTexts = setOf(
            "领取", "收下", "立即领取", "点击领取", "开心收下",
            "立即领肥", "领取肥料", "立即领肥[料]"  // 支付宝按钮文案可能带方括号占位
        )
        val isPureClaimClick = buttonText in pureClaimButtonTexts && !fullTaskText.let { ft ->
            // 复用 paidHintKeywords 判断，避免重复声明
            listOf("退款", "扣回", "扣减", "下单后", "购买后", "充值后", "消费满",
                "任意下单", "下单领", "下单赢", "任意充值").any { ft.contains(it) }
        }
        if (isPureClaimClick) {
            Log.i(TAG, "processTask: task #${currentTaskIndex + 1} is pure claim button, clicking to claim (text='$buttonText', context='${taskContextText.take(60)}')")
            debugLog("processTask: pure claim task #${currentTaskIndex + 1}, button='$buttonText', direct click to claim fertilizer")
            service.performClickSafe(button)
            // build524 修复：pureClaimClick 后不 currentTaskIndex++，重置为 0 让下一轮重新扫描
            // 不会死循环：每次 pureClaimClick 后任务列表都会变化（"领取"按钮消失或变成"已领取"）
            currentTaskIndex = 0
            collectedCount++
            // 等 2 秒让"肥料已发放"弹窗渲染（点击后弹窗需要短暂时间出现）
            handler.postDelayed({
                if (state != AutomationState.PROCESSING_TASK) return@postDelayed
                // 检测"肥料已发放"/"领取成功"等领取到账提示弹窗
                if (service.isFertilizerGrantedPage()) {
                    debugLog("processTask: pure claim success (肥料已发放 detected), pressing back to return to task list")
                    service.pressBack()
                    // pressBack 后等待弹窗动画关闭，再回 OPENING_TASK_LIST
                    handler.postDelayed({
                        if (state == AutomationState.PROCESSING_TASK) {
                            moveTo(AutomationState.OPENING_TASK_LIST)
                            handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                        }
                    }, INTERVAL_CLICK_MS)
                } else {
                    // build525 修复（debug_test_20260719_031802.log, build524-4a224b1）：
                    // 历史问题：build524 的"未检测到弹窗也 pressBack"修复反而有害：
                    // - 实际点击"领取"后肥料可能直接到账，没有弹窗
                    // - pressBack 反而退出了芭芭农场小程序，回到支付宝主页 AlipayLogin
                    // - 支付宝主页的"消息盒子"等元素被 findGoCompleteButtons 误识别为 taskButton
                    // - bounds=[26,1872][1174,2031] 是主页元素，不是任务按钮
                    // - processTask 处理这个无效 taskButton 浪费时间，最终 launcher 占屏
                    //
                    // 修复：撤销 build524 的"未检测到弹窗也 pressBack"，改回 build521 的逻辑：
                    // 未检测到弹窗直接回 OPENING_TASK_LIST，不 pressBack
                    // （领取直接到账时不会遮挡任务列表，无需 pressBack）
                    debugLog("processTask: pure claim but no 肥料已发放 popup detected (fertilizer may have been credited directly), returning to OPENING_TASK_LIST without pressBack")
                    moveTo(AutomationState.OPENING_TASK_LIST)
                    handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                }
            }, INTERVAL_CLICK_MS)
            return
        }

        if (service.isGameTask(button)) {
            Log.i(TAG, "processTask: task #${currentTaskIndex + 1} is game task, entering GAME_PLAYING (text='$buttonText')")
            debugLog("processTask: game task #${currentTaskIndex + 1}, text='$buttonText', staying in game to earn fertilizer")
            // 点击"去完成"进入游戏
            service.performClickSafe(button)
            // build529：进入游戏时重置 AI 视觉进度识别节流（每个新任务独立计数）
            lastAiProgressCheckMs = 0L
            moveTo(AutomationState.GAME_PLAYING)
            handler.postDelayed({ runGamePlaying(elapsedMs = 0L) }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 2b. 蚂蚁森林领落叶肥料任务：关弹窗→领奖励→逛农场得落叶肥料
        // P1-1（build513 修复）：clickable 父节点下 buttonText 可能为空，
        // 必须同时检查 taskContextText（任务标题在上下文中）。
        if (buttonText.contains("森林") || buttonText.contains("落叶") ||
            taskContextText.contains("森林") || taskContextText.contains("落叶")) {
            Log.i(TAG, "processTask: task #${currentTaskIndex + 1} is forest task, entering FOREST_COLLECTING (text='$buttonText', context='$taskContextText')")
            debugLog("processTask: forest task #${currentTaskIndex + 1}, text='$buttonText', will close popups → claim reward → 逛农场得落叶肥料")
            service.performClickSafe(button)
            moveTo(AutomationState.FOREST_COLLECTING)
            handler.postDelayed({ runForestCollecting(step = 0, retryCount = 0) }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 3. 跨平台切换任务：在支付宝/淘宝/UC 之间切换获取肥料
        // P1-2（build513 修复）：clickable 父节点下 buttonText 可能为空，
        // 跨平台任务关键词（"淘宝"/"支付宝"/"UC"）在任务标题（上下文）中，
        // 必须同时检查 taskContextText。
        val crossTarget = detectCrossPlatformTarget(buttonText + " " + taskContextText)
        if (crossTarget != null && crossTarget != service.currentPlatform) {
            Log.i(TAG, "processTask: task #${currentTaskIndex + 1} is cross-platform task, switching to $crossTarget (text='$buttonText')")
            debugLog("processTask: cross-platform task #${currentTaskIndex + 1}, text='$buttonText', from=${service.currentPlatform}, to=$crossTarget")
            switchOriginalPlatform = service.currentPlatform
            switchTargetPlatform = crossTarget
            switchStage = "LAUNCH_TARGET"
            switchRetryCount = 0
            // 先点击"去完成"按钮（部分任务点击后会自动跳转到目标平台）
            service.performClickSafe(button)
            moveTo(AutomationState.SWITCHING_PLATFORM)
            handler.postDelayed({ runSwitchingPlatform() }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 4. 滑动浏览任务：模拟滑动而非点击进入
        if (service.isBrowseTask(button)) {
            Log.i(TAG, "processTask: task #${currentTaskIndex + 1} is browse task, swiping (text='$buttonText')")
            debugLog("processTask: browse task #${currentTaskIndex + 1}, text='$buttonText', entering BROWSING_TASK")
            moveTo(AutomationState.BROWSING_TASK)
            handler.postDelayed({ runBrowsingTask(swipeCount = 0) }, INTERVAL_CLICK_MS)
            return
        }

        // 3. 普通任务（看广告、答题、签到等）：点击按钮
        Log.i(TAG, "processTask: clicking task #${currentTaskIndex + 1}/${taskButtons.size} (attempt ${attempt + 1})")
        currentTaskFailCount = 0 // 新任务开始，重置失败计数
        // 解析任务剩余次数（如 "1/3" → 还可重玩 2 次），仅首次点击时解析
        if (attempt == 0 && taskReplayRemaining == 0) {
            taskReplayRemaining = parseTaskRemainingCount(buttonText, taskContextText)
            if (taskReplayRemaining > 0) {
                debugLog("processTask: multi-click task detected, remainingReplays=$taskReplayRemaining")
            }
        }
        service.performClickSafe(button)

        // 等待检测是否进入广告
        handler.postDelayed({
            if (state == AutomationState.PROCESSING_TASK) checkTaskResult(service, attempt)
        }, INTERVAL_PAGE_LOAD_MS)
    }

    // ============== 阶段3b: 滑动浏览任务 ==============

    /**
     * 滑动浏览任务：模拟上下滑动浏览页面获取肥料
     * - 不点击进入商品页面，只在当前页面上下滑动
     * - 在屏幕中部轻微上下交替滑动（避免一直向下滑动超出页面）
     * - 滑动足够次数后关闭并返回任务列表
     */
    private fun runBrowsingTask(swipeCount: Int) {
        if (state != AutomationState.BROWSING_TASK) return
        val service = getService() ?: run { stop(); return }

        if (swipeCount == 0) {
            logPageSnapshot(service, "browseTask-start")
            // 重置红包弹窗关闭计数器（新的浏览任务开始）
            browseRedPacketCloseAttempts = 0
            // 第一步：点击"去完成"按钮进入浏览页面
            val button = taskButtons.getOrNull(currentTaskIndex)
            if (button == null) {
                debugLog("browseTask: button gone, back to processing")
                // 从 direct 弹窗进入的浏览：节点失效回 COLLECTING_DIRECT
                val fromDirect = browseFromDirectPopup
                browseFromDirectPopup = false
                if (fromDirect) {
                    debugLog("browseTask: was from direct popup, returning to COLLECTING_DIRECT")
                    moveTo(AutomationState.COLLECTING_DIRECT)
                    handler.postDelayed({ runCollectingDirect(attempt = 0) }, INTERVAL_CLICK_MS)
                } else {
                    moveTo(AutomationState.PROCESSING_TASK)
                    handler.postDelayed({ runProcessingTask(0) }, INTERVAL_CLICK_MS)
                }
                return
            }
            debugLog("browseTask: clicking 'go browse' button, then will swipe")
            service.performClickSafe(button)
            // 等待页面加载后，先点击一个商品再开始滑动
            handler.postDelayed({
                if (state == AutomationState.BROWSING_TASK) {
                    // 诊断日志：点击"去完成"后页面到底变成了什么
                    val pageType = service.getPageType()
                    val onFarm = service.isOnFarmPage()
                    val allText = service.collectAllTextSnapshot(maxCount = 15)
                    debugLog("browseTask: after clicking 'go browse', page type=$pageType, onFarm=$onFarm, texts=$allText")
                    // 检测页面是否有"滑动获取肥料"提示，解析需要滑动的时间
                    val hintSeconds = service.findSwipeForFertilizerHint()
                    // build529（用户要求"全部实现"）：同时识别"已浏览15s"/"15/30秒"/"进度50%"等进度信息
                    // 用于动态调整滑动次数（如果 total 已知，按剩余秒数计算更精确的 swipe 上限）
                    val progressInfo = service.findBrowseProgressInfo()
                    if (progressInfo.isFound) {
                        debugLog("browseTask: found progress info type=${progressInfo.type}, " +
                            "cur=${progressInfo.current}, tot=${progressInfo.total}, " +
                            "percent=${progressInfo.percent}%, remaining=${progressInfo.remainingSeconds}s, " +
                            "raw='${progressInfo.rawText.take(60)}'")
                    }
                    if (hintSeconds > 0) {
                        debugLog("browseTask: found swipe hint, need $hintSeconds seconds")
                        // 根据提示时间计算滑动次数：每次滑动间隔2秒，额外加2次余量
                        val requiredSwipes = (hintSeconds / (BROWSE_SWIPE_INTERVAL_MS / 1000)).toInt() + 2
                        browseTaskTargetSwipes = requiredSwipes.coerceAtLeast(3).coerceAtMost(30)
                        debugLog("browseTask: target swipes = $browseTaskTargetSwipes (hint=$hintSeconds seconds)")
                    } else if (progressInfo.type == FarmAccessibilityService.ProgressType.FRACTION && progressInfo.total > 0) {
                        // build529：没有"滑动浏览x秒"提示，但有"已浏览 15/30秒"型进度 →
                        // 按剩余秒数计算滑动次数（每次滑动间隔2秒 + 2次余量）
                        val remaining = progressInfo.remainingSeconds
                        val requiredSwipes = (remaining / (BROWSE_SWIPE_INTERVAL_MS / 1000)).toInt() + 2
                        browseTaskTargetSwipes = requiredSwipes.coerceAtLeast(3).coerceAtMost(30)
                        debugLog("browseTask: no swipe hint but found fraction progress, " +
                            "target swipes = $browseTaskTargetSwipes (remaining=${remaining}s, " +
                            "cur=${progressInfo.current}/${progressInfo.total})")
                    } else {
                        // 没有找到"滑动浏览得肥料"提示：再检查其他浏览奖励指标
                        // （倒计时"再逛xx秒"、进度"每浏览x秒"、停留"浏览x分钟"）
                        // 若都没有，说明此任务被 isBrowseTask 误判为滑动任务，实际不是滑动任务 → 直接退出，不滑动
                        val hasCountdown = service.findBrowseRewardCountdownHint() > 0
                        val hasProgress = service.hasBrowseRewardProgressHint()
                        val hasDuration = service.findBrowseDurationRewardHint() > 0
                        // build529：findBrowseProgressInfo 识别到的进度也视作 browse reward 指标
                        // （例如"进度50%"等百分比型，避免被误判为 not_browse_task 而提前退出）
                        val hasProgressInfo = progressInfo.isFound
                        if (!hasCountdown && !hasProgress && !hasDuration && !hasProgressInfo) {
                            debugLog("browseTask: no swipe hint and no browse reward indicator (countdown=$hasCountdown, progress=$hasProgress, duration=$hasDuration, progressInfo=$hasProgressInfo), not a browse task, exiting without swiping")
                            currentTaskIndex++
                            collectedCount++
                            exitBrowsePage(service, reason = "not_browse_task")
                            return@postDelayed
                        }
                        browseTaskTargetSwipes = MAX_BROWSE_SWIPES
                        debugLog("browseTask: no swipe hint but has browse reward indicator (countdown=$hasCountdown, progress=$hasProgress, duration=$hasDuration, progressInfo=$hasProgressInfo), using default $browseTaskTargetSwipes swipes")
                    }
                    // 不主动点击商品进入详情页：用户要求浏览任务只在商品列表页滑动，
                    // 不要进入有"加入购物车"按钮的商品详情页
                    // （滑动循环中会检测是否意外进入详情页，若是则按返回退回列表页）
                    debugLog("browseTask: skipping product click, swiping in list page directly")
                    // 等待页面加载后开始滑动
                    handler.postDelayed({
                        if (state == AutomationState.BROWSING_TASK) runBrowsingTask(1)
                    }, INTERVAL_PAGE_LOAD_MS)
                }
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 优先检测：是否有红包弹窗 → 先关闭它，才能继续滑动获取肥料
        // 红包弹窗会遮挡页面，不关闭无法滑动；关闭后保持 swipeCount 重新进入
        // 防御：限制连续关闭次数，避免 findRedPacketCloseButton 误判导致死循环
        val redPacketBtn = if (browseRedPacketCloseAttempts < MAX_RED_PACKET_CLOSE_ATTEMPTS) {
            service.findRedPacketCloseButton()
        } else {
            // 超过阈值仍检测到"红包弹窗"：很可能是误判（如农场主页"钓红包"入口），
            // 不再当红包弹窗处理，继续走滑动逻辑
            if (swipeCount == 1 || browseRedPacketCloseAttempts == MAX_RED_PACKET_CLOSE_ATTEMPTS) {
                debugLog("browseTask: red packet close attempts exceeded ($browseRedPacketCloseAttempts), ignoring red packet detection and continuing swipe")
            }
            null
        }
        if (redPacketBtn != null) {
            browseRedPacketCloseAttempts++
            Log.i(TAG, "browseTask: red packet popup detected, closing it first (attempt $browseRedPacketCloseAttempts/$MAX_RED_PACKET_CLOSE_ATTEMPTS)")
            debugLog("browseTask: closing red packet popup before swiping (swipe #$swipeCount, attempt $browseRedPacketCloseAttempts/$MAX_RED_PACKET_CLOSE_ATTEMPTS)")
            val scheduleReentry = {
                // 等待弹窗关闭后重新进入（保持 swipeCount 不变，不消耗滑动次数）
                handler.postDelayed({
                    if (state == AutomationState.BROWSING_TASK) runBrowsingTask(swipeCount)
                }, INTERVAL_CLICK_MS)
            }
            service.performClickSafe(redPacketBtn)
            scheduleReentry()
            return
        }

        // 优先检测："浏览x分钟得xxx肥料"提示 → 停留等待，不滑动，直到"已完成"出现
        // 与"每浏览x秒可得1次奖励"（需滑动）不同，这类任务只需停留等待
        // 必须放在 isTaskCompletePage() 之前检测：因为"浏览5分钟得600肥料"页面可能同时
        // 包含"获得肥料"等文案，会被 isTaskCompletePage() 误判为已完成而提前退出
        val browseDurationSeconds = service.findBrowseDurationRewardHint()
        if (browseDurationSeconds > 0) {
            // 动态设置等待上限：提示秒数/2（每次等待2秒）+ 30次缓冲
            val durationWaitLimit = (browseDurationSeconds / (BROWSE_SWIPE_INTERVAL_MS / 1000)).toInt() + 30
            if (swipeCount > durationWaitLimit) {
                // 超过等待上限仍未出现"已完成"，强制退出（避免卡死）
                Log.w(TAG, "browseTask: browse duration wait timeout (swipes=$swipeCount, limit=$durationWaitLimit, duration=${browseDurationSeconds}s), exiting")
                debugLog("browseTask: duration wait timeout, exiting")
                currentTaskIndex++
                collectedCount++
                exitBrowsePage(service, reason = "timeout_duration_wait")
                return
            }
            // 在停留等待期间，用精确的完成检测（而非宽泛的 isTaskCompletePage）
            // 完成标志：页面出现"已完成"或"任务完成"，且不再显示"浏览x分钟"提示
            // 注意：findBrowseDurationRewardHint 已返回 >0 表示提示还在，所以这里一定不会退出
            // 只有当提示消失（文案变成"已完成"等）才会跳出此分支，进入下面的 isTaskCompletePage 检测
            Log.i(TAG, "browseTask: browse duration hint (${browseDurationSeconds}s), waiting without swiping (swipe #$swipeCount/$durationWaitLimit)")
            debugLog("browseTask: waiting for '已完成' (duration=${browseDurationSeconds}s, swipe #$swipeCount/$durationWaitLimit)")
            handler.postDelayed({
                if (state == AutomationState.BROWSING_TASK) runBrowsingTask(swipeCount + 1)
            }, BROWSE_SWIPE_INTERVAL_MS)
            return
        }

        // 优先检测：是否显示了"肥料已发放/已获得肥料"等奖励到账提示 → 任务完成，直接回农场主页
        // 注意：此检测用 rootInActiveWindowSafe，能覆盖 WebView 内的文案
        // isTaskCompletePage 故意排除了"获得肥料"关键词（进行中页面也会显示"已获得肥料 xxx"），
        // 但纯粹的"已获得肥料"（无数字后缀）是任务完成信号，这里单独检测
        // 直接走 RETURNING：reopenFarmByDeepLink 会 kill 目标 App + 用桌面快捷方式重开农场页，
        // 不依赖返回键（WebView 里返回键不可靠）
        if (service.isFertilizerGrantedPage()) {
            debugLog("browseTask: fertilizer granted page detected, exiting via RETURNING (swipes=$swipeCount/$browseTaskTargetSwipes)")
            currentTaskIndex++
            collectedCount++
            browseFromSearchBrowse = false
            browseFromDirectPopup = false
            moveTo(AutomationState.RETURNING)
            handler.postDelayed({ runReturning(0) }, INTERVAL_CLICK_MS)
            return
        }

        // 优先检测：是否已显示"任务完成" → 这是浏览任务的真正退出信号
        // UC 极速版等平台：滑动获取肥料，直到显示"任务完成"才退出（倒计时"再逛xx秒"只是过程提示）
        if (service.isTaskCompletePage()) {
            debugLog("browseTask: task complete detected, exiting (swipes=$swipeCount/$browseTaskTargetSwipes)")
            currentTaskIndex++
            collectedCount++
            exitBrowsePage(service, reason = "task_complete")
            return
        }

        // 已完成所有滑动次数：判断是否需要继续等待"任务完成"
        // 滑动次数达标后，在 waitLimit 内继续滑动等待"任务完成"出现
        // 原因：滑动后页面文案可能短暂消失导致进度提示检测失败，但任务实际还在进行
        // 只有超过 waitLimit 或检测到任务完成（上方 isTaskCompletePage）才退出
        if (swipeCount > browseTaskTargetSwipes) {
            val countdownSeconds = service.findBrowseRewardCountdownHint()
            val hasProgressHint = service.hasBrowseRewardProgressHint()
            // build529：同时检测"已浏览15/30秒"等具体进度信息，若 remaining > 0 说明任务未完成
            val progressInfo = service.findBrowseProgressInfo()
            val hasRemainingProgress = progressInfo.isFound && progressInfo.remainingSeconds > 0
            val waitLimit = browseTaskTargetSwipes + MAX_BROWSE_WAIT_SWIPES
            if (swipeCount <= waitLimit) {
                // 还在等待上限内，继续滑动等待"任务完成"出现
                Log.i(TAG, "browseTask: swipes reached target, keep waiting for task complete (countdown=${countdownSeconds}s, progressHint=$hasProgressHint, remainingProgress=$hasRemainingProgress, swipe #$swipeCount/$waitLimit)")
                debugLog("browseTask: keep swiping within wait limit (countdown=${countdownSeconds}s, progress=$hasProgressHint, remainingProgress=$hasRemainingProgress, swipe #$swipeCount/$waitLimit)")
                // 继续走下面的滑动逻辑（不 return）
            } else {
                debugLog("browseTask: wait limit exceeded (swipes=$swipeCount/$waitLimit, countdown=${countdownSeconds}s, progress=$hasProgressHint, remainingProgress=$hasRemainingProgress), exiting browse page")
                currentTaskIndex++
                collectedCount++
                exitBrowsePage(service, reason = "timeout_wait_limit")
                return
            }
        }

        // 滑动前检测：是否在异常页面（交易页面、商品详情页、收银台等）→ 立即退出
        // 禁止交易获取肥料：所有交易相关页面都视为异常
        if (service.isOnAbnormalPage()) {
            debugLog("browseTask: abnormal/trading page detected, exiting immediately")
            currentTaskIndex++
            collectedCount++
            exitBrowsePage(service, reason = "abnormal_page")
            return
        }

        // 滑动前检测：是否在付费搜索推荐页（"下单得肥料"等）→ 直接退出
        // 注意：UC 浏览任务有"再逛xx秒"倒计时或"每浏览x秒"进度提示时不属于此类，跳过本检测
        val hasCountdown = service.findBrowseRewardCountdownHint() > 0
        val hasProgressHint = service.hasBrowseRewardProgressHint()
        if (!hasCountdown && !hasProgressHint && service.isSearchRecommendPage()) {
            debugLog("browseTask: paid search recommend page detected, exiting without swiping")
            currentTaskIndex++
            collectedCount++
            exitBrowsePage(service, reason = "paid_search_recommend")
            return
        }

        // 执行滑动：在屏幕中部轻微上下交替滑动（不需要一直向下滑，小幅上下滑动即可模拟浏览）
        // 若开启交互模式，执行前会弹询问浮窗等用户批准
        val centerX = 600f
        val baseY = 1200f      // 屏幕中部基准点
        val swipeRange = 250f  // 轻微滑动距离
        val (startY, endY, dirText) = if (swipeCount % 2 == 1) {
            // 奇数次：向上滑（页面向下滚动）
            Triple(baseY + swipeRange, baseY - swipeRange, "up")
        } else {
            // 偶数次：向下滑（页面向上滚动）
            Triple(baseY - swipeRange, baseY + swipeRange, "down")
        }

        // 直接执行滑动（滑动是浏览任务的常规高频动作）
        // 诊断日志：滑动前记录页面状态、倒计时、进度，帮助定位"为什么不如人工操作"
        val browsePageType = service.getPageType()
        val browseCountdown = service.findBrowseRewardCountdownHint()
        val browseProgress = service.hasBrowseRewardProgressHint()
        debugLog("browseTask: swipe #$swipeCount/$browseTaskTargetSwipes $dirText ($startY -> $endY), pageType=$browsePageType, countdown=${browseCountdown}s, progress=$browseProgress")
        service.dispatchGestureSwipe(centerX, startY, centerX, endY, 500L)
        scheduleNextBrowseCheck(service, swipeCount)
    }

    /**
     * 安排滑动后的下一轮检测
     * - 滑动后等 BROWSE_SWIPE_INTERVAL_MS，重新进入 runBrowsingTask
     * - 包含中途的搜索推荐页/异常页/任务完成检测
     */
    private fun scheduleNextBrowseCheck(service: FarmAccessibilityService, swipeCount: Int) {
        // 成功执行一次滑动说明红包弹窗已关闭（或本就无弹窗），重置连续关闭计数器
        browseRedPacketCloseAttempts = 0
        handler.postDelayed({
            if (state != AutomationState.BROWSING_TASK) return@postDelayed
            // 任务进行中（倒计时或"每浏览x秒"进度提示仍在）时跳过搜索推荐页检测，避免误判提前退出
            val countdownActive = service.findBrowseRewardCountdownHint() > 0
            val progressActive = service.hasBrowseRewardProgressHint()
            // 检测搜索推荐页（"当前页下单得肥料"）→ 直接退出
            if (!countdownActive && !progressActive && service.isSearchRecommendPage()) {
                debugLog("browseTask: search recommend page during swipe, exiting")
                currentTaskIndex++
                collectedCount++
                exitBrowsePage(service, reason = "paid_search_in_swipe")
                return@postDelayed
            }
            // 检测异常页面（交易页面、商品详情页、收银台等）→ 立即退出
            // 禁止交易获取肥料：所有交易相关页面都视为异常
            if (service.isOnAbnormalPage()) {
                debugLog("browseTask: abnormal/trading page during swipe, exiting immediately")
                currentTaskIndex++
                collectedCount++
                exitBrowsePage(service, reason = "abnormal_in_swipe")
                return@postDelayed
            }
            // 检测是否已完成任务（得到肥料）
            if (service.isFertilizerGrantedPage()) {
                debugLog("browseTask: fertilizer granted detected during swipe, exiting via RETURNING")
                collectedCount++
                currentTaskIndex++
                // 直接走 RETURNING：reopenFarmByDeepLink 会 kill 目标 App 老进程 +
                // 用桌面快捷方式/deep link 重新打开农场页，不依赖返回键（WebView 里返回键不可靠）
                browseFromSearchBrowse = false
                browseFromDirectPopup = false
                moveTo(AutomationState.RETURNING)
                handler.postDelayed({ runReturning(0) }, INTERVAL_CLICK_MS)
                return@postDelayed
            }
            // 检测是否已完成任务（得到肥料）
            if (service.isTaskCompletePage()) {
                debugLog("browseTask: task complete detected during swipe, exiting")
                // 优先点右上角关闭或左上角返回图标
                val closeBtn = service.findAdCloseButton()
                val backIcon = service.findBackIcon()
                when {
                    closeBtn != null -> { debugLog("browseTask: clicking close icon"); service.performClickSafe(closeBtn) }
                    backIcon != null -> { debugLog("browseTask: clicking back icon"); service.performClickSafe(backIcon) }
                    else -> { debugLog("browseTask: pressing back"); service.pressBack() }
                }
                collectedCount++
                currentTaskIndex++
                // 从"搜索后浏览立得奖励"任务页进入的浏览：需要返回两次回芭芭农场
                val fromSearchBrowse = browseFromSearchBrowse
                browseFromSearchBrowse = false  // 复位
                handler.postDelayed({
                    // 搜索浏览任务：第一次返回后还需再按一次返回退出搜索任务页
                    if (fromSearchBrowse && !service.isOnFarmPage()) {
                        debugLog("browseTask: from search browse, pressing back again to exit search task page")
                        service.pressBack()
                    } else if (!service.isOnFarmPage()) {
                        // 普通浏览任务：不在农场页时按一次返回
                        service.pressBack()
                    }
                    handler.postDelayed({
                        // 从 direct 弹窗进入的浏览：完成后回 COLLECTING_DIRECT
                        val fromDirect = browseFromDirectPopup
                        browseFromDirectPopup = false  // 复位
                        if (fromDirect) {
                            debugLog("browseTask: was from direct popup, returning to COLLECTING_DIRECT")
                            moveTo(AutomationState.COLLECTING_DIRECT)
                            handler.postDelayed({ runCollectingDirect(attempt = 0) }, INTERVAL_CLICK_MS)
                        } else {
                            moveTo(AutomationState.OPENING_TASK_LIST)
                            handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                        }
                    }, INTERVAL_CLICK_MS)
                }, INTERVAL_PAGE_LOAD_MS)
                return@postDelayed
            }
            runBrowsingTask(swipeCount + 1)
        }, BROWSE_SWIPE_INTERVAL_MS)
    }

    /** 退出浏览页面：优先用左上角返回图标，否则按返回键，然后重新打开任务列表 */
    private fun exitBrowsePage(service: FarmAccessibilityService, reason: String = "exit_browse_page") {
        // 用户同意退出，执行原退出逻辑
        // 优先点击左上角返回图标退出（"下单得奖励"、"当前页下单得肥料"等搜索推荐页面）
        val backIcon = service.findBackIcon()
        if (backIcon != null) {
            debugLog("exitBrowsePage: clicking back icon to exit")
            service.performClickSafe(backIcon)
        } else {
            debugLog("exitBrowsePage: no back icon found, pressing back")
            service.pressBack()
        }
        // 等待页面返回，然后检查是否回到农场页
        handler.postDelayed({
            // 从 direct 弹窗进入的浏览：完成后回 COLLECTING_DIRECT 继续找其他 direct 按钮
            val fromDirect = browseFromDirectPopup
            browseFromDirectPopup = false  // 复位
            if (fromDirect) {
                debugLog("exitBrowsePage: browse was from direct popup, returning to COLLECTING_DIRECT")
                moveTo(AutomationState.COLLECTING_DIRECT)
                handler.postDelayed({ runCollectingDirect(attempt = 0) }, INTERVAL_CLICK_MS)
                return@postDelayed
            }
            // 从"搜索后浏览立得奖励"任务页进入的浏览：完成后需要返回两次回芭芭农场
            // 第一次返回已执行（上方 backIcon/pressBack），这里再按一次返回
            val fromSearchBrowse = browseFromSearchBrowse
            browseFromSearchBrowse = false  // 复位
            if (fromSearchBrowse && !service.isOnFarmPage()) {
                debugLog("exitBrowsePage: browse was from search browse task, pressing back again to return to farm")
                service.pressBack()
                handler.postDelayed({
                    if (service.isOnFarmPage()) {
                        debugLog("exitBrowsePage: returned to farm after second back")
                        moveTo(AutomationState.OPENING_TASK_LIST)
                        handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                    } else {
                        debugLog("exitBrowsePage: still not on farm after second back, re-navigating")
                        moveTo(AutomationState.NAVIGATING)
                        handler.postDelayed({ runNavigating(0) }, INTERVAL_CLICK_MS)
                    }
                }, INTERVAL_PAGE_LOAD_MS)
                return@postDelayed
            }
            if (service.isOnFarmPage()) {
                // 已回到农场页，重新打开任务列表
                moveTo(AutomationState.OPENING_TASK_LIST)
                handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
            } else {
                // 不在农场页，可能回到了淘宝主页，需要重新导航到农场
                debugLog("exitBrowsePage: not on farm page after exit, re-navigating")
                moveTo(AutomationState.NAVIGATING)
                handler.postDelayed({ runNavigating(0) }, INTERVAL_CLICK_MS)
            }
        }, INTERVAL_PAGE_LOAD_MS)
    }

    // ============== 阶段3c: 玩游戏任务（停留拿肥料） ==============

    /**
     * 玩游戏任务：打开游戏停留玩一下即可获取肥料（无法实现过关卡）
     *
     * 基于用户澄清："玩游戏过关卡任务你实现不了，但是只是打开游戏停留玩一下的任务你是可以获取到肥料的"
     * 这类任务只需在游戏内停留规定时长即可发放肥料，无需真正通关。
     *
     * 策略：
     * 1. 等待游戏加载（GAME_LOAD_MS）
     * 2. 停留轮询：每 GAME_ACTION_INTERVAL_MS 检测一次页面状态
     *    - 陷阱页（充值/交易页）→ 立即点关闭/返回退出，跳过任务（不领肥料，安全优先）
     *    - 完成页（"领取奖励"/"恭喜"/"完成"/"升级"等）→ 领取奖励后返回农场
     *    - 自动返回农场页 → 假设任务已结算，肥料已发放
     *    - 其他页面 → 继续停留（不按返回，避免退出游戏导致停留失败）
     * 3. 停留达到 GAME_STAY_TARGET_MS（30s）且仍在游戏内 → 主动按返回退出回农场，任务发放肥料
     * 4. 硬超时 GAME_MAX_DURATION_MS（90s）→ 强制退出，跳过任务
     *
     * 关键：停留期间绝不按返回键（按返回会退出游戏导致停留失败），仅靠页面状态检测驱动退出。
     *
     * @param elapsedMs 已用时
     */
    private fun runGamePlaying(elapsedMs: Long) {
        if (state != AutomationState.GAME_PLAYING) return
        val service = getService() ?: run { stop(); return }

        if (elapsedMs == 0L) {
            logPageSnapshot(service, "gamePlay-start")
        }

        // 硬超时强制退出（90s，覆盖加载5s + 停留30s + 退出余量）
        if (elapsedMs >= GAME_MAX_DURATION_MS) {
            Log.w(TAG, "gamePlay: hard timeout (elapsed=${elapsedMs}ms), exiting")
            debugLog("gamePlay: hard timeout, exiting game, skipping task")
            service.pressBack()
            handler.postDelayed({
                if (state == AutomationState.GAME_PLAYING) {
                    if (!service.isOnFarmPage()) service.pressBack()
                    handler.postDelayed({
                        if (state == AutomationState.GAME_PLAYING) {
                            currentTaskIndex++
                            moveTo(AutomationState.OPENING_TASK_LIST)
                            handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                        }
                    }, INTERVAL_PAGE_LOAD_MS)
                }
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 诱导弹窗防护：游戏过程中弹出"立即下载/立即体验"等诱导按钮
        // 策略：优先点"关闭/暂不/拒绝"关闭弹窗，绝不点诱导按钮，避免跳转应用商店中断停留
        // 注意：仅在停留期间检测，弹窗关闭后继续停留计时长
        if (service.findAdInstallButton() != null) {
            Log.w(TAG, "gamePlay: install popup detected, attempting to close (stay protection)")
            debugLog("gamePlay: install popup trap detected during stay, trying closeAdInstallPopup")
            val closed = service.closeAdInstallPopup()
            if (!closed) {
                // 找不到关闭类按钮，可能整个页面已变成落地页 → 按返回退出
                debugLog("gamePlay: no close button for install popup, pressing back to exit")
                service.pressBack()
                handler.postDelayed({
                    if (state == AutomationState.GAME_PLAYING) {
                        currentTaskIndex++
                        moveTo(AutomationState.OPENING_TASK_LIST)
                        handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                    }
                }, INTERVAL_CLICK_MS)
                return
            }
            // 弹窗已关闭，继续停留（不增加 elapsedMs，下一轮重新检测）
            debugLog("gamePlay: install popup closed, continuing stay")
            handler.postDelayed({
                if (state == AutomationState.GAME_PLAYING) runGamePlaying(elapsedMs + GAME_ACTION_INTERVAL_MS)
            }, GAME_ACTION_INTERVAL_MS)
            return
        }

        // 陷阱页（充值/付费/交易）→ 立即退出，跳过任务（安全优先，不领肥料）
        if (service.isRechargePage() || service.isOnAbnormalPage()) {
            Log.w(TAG, "gamePlay: trap page (recharge/abnormal) detected, exiting immediately")
            debugLog("gamePlay: trap page detected, trying to click close button")
            val closed = service.clickCloseOnRechargePage()
            if (!closed) {
                debugLog("gamePlay: no close button found, pressing back to exit trap")
                service.pressBack()
            }
            handler.postDelayed({
                if (state == AutomationState.GAME_PLAYING) {
                    // 再次检测是否还在陷阱页（关闭失败的情况）
                    if (service.isRechargePage() || service.isOnAbnormalPage()) {
                        debugLog("gamePlay: still on trap page, pressing back again")
                        service.pressBack()
                    }
                    handler.postDelayed({
                        if (state == AutomationState.GAME_PLAYING) {
                            debugLog("gamePlay: exited trap page, skipping task (no fertilizer)")
                            currentTaskIndex++
                            moveTo(AutomationState.OPENING_TASK_LIST)
                            handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                        }
                    }, INTERVAL_CLICK_MS)
                }
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 游戏完成页（"领取奖励"/"恭喜"/"完成"/"升级"等）→ 不主动点击，只等待
        // build528（用户反馈）：试玩游戏但不是完成游戏任务（如对战、关卡、过关、订单等任务），
        // 打开游戏 App 后，只需要等待拿肥料，不需要任何操作。
        // 历史问题：isGameCompletePage 检测到"任务完成"/"已完成"等文案后主动调用
        // clickClaimRewardButton 点击"领取奖励"/"确认"/"确定"/"完成"/"继续"/"下一步"等按钮，
        // 这些点击可能误触发游戏内的其他操作（如"开始游戏"/"下一关"等），干扰游戏流程。
        // 修复：检测到游戏完成页后不主动点击，只继续等待，让游戏自动发放肥料，
        // 直到检测到"肥料全部取完"或停留超时后退出。
        if (service.isGameCompletePage()) {
            Log.i(TAG, "gamePlay: game complete page detected, waiting for fertilizer to be credited (no click)")
            debugLog("gamePlay: game complete detected, waiting (no click, user request: don't operate)")
            handler.postDelayed({
                if (state == AutomationState.GAME_PLAYING) runGamePlaying(elapsedMs + GAME_ACTION_INTERVAL_MS)
            }, GAME_ACTION_INTERVAL_MS)
            return
        }

        // build528（用户反馈）：检测"肥料全部取完"/"肥料已全部领取"等提示 → pressBack 退出游戏
        // 用户需求：显示肥料全部取完后，退出游戏 App
        if (service.isFertilizerAllClaimed()) {
            Log.i(TAG, "gamePlay: fertilizer all claimed detected, exiting game")
            debugLog("gamePlay: 肥料全部取完 detected, pressing back to exit game")
            service.pressBack()
            handler.postDelayed({
                if (state != AutomationState.GAME_PLAYING) return@postDelayed
                if (service.isOnFarmPage()) {
                    debugLog("gamePlay: returned to farm after 肥料全部取完, task complete")
                    collectedCount++
                    currentTaskIndex++
                    moveTo(AutomationState.OPENING_TASK_LIST)
                    handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                } else {
                    // 不在农场页，可能退到中间页，再按一次返回
                    debugLog("gamePlay: not on farm after 肥料全部取完, pressing back once more")
                    service.pressBack()
                    handler.postDelayed({
                        if (state == AutomationState.GAME_PLAYING) {
                            if (service.isOnFarmPage()) {
                                debugLog("gamePlay: returned to farm on second back after 肥料全部取完, task complete")
                                collectedCount++
                            }
                            currentTaskIndex++
                            moveTo(AutomationState.OPENING_TASK_LIST)
                            handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                        }
                    }, INTERVAL_PAGE_LOAD_MS)
                }
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 已回到农场页（游戏自动返回，说明任务已结算）→ 假设完成
        if (elapsedMs > GAME_LOAD_MS && service.isOnFarmPage()) {
            Log.i(TAG, "gamePlay: back to farm page, game task likely complete")
            debugLog("gamePlay: back to farm, assuming complete")
            collectedCount++
            currentTaskIndex++
            moveTo(AutomationState.OPENING_TASK_LIST)
            handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
            return
        }

        // 停留达到目标时长（30s）且仍在游戏内 → 主动按返回退出回农场，任务发放肥料
        if (elapsedMs >= GAME_STAY_TARGET_MS) {
            Log.i(TAG, "gamePlay: stay target reached (elapsed=${elapsedMs}ms), exiting to farm")
            debugLog("gamePlay: stay ${elapsedMs}ms reached, pressing back to collect fertilizer")
            service.pressBack()
            handler.postDelayed({
                if (state != AutomationState.GAME_PLAYING) return@postDelayed
                if (service.isOnFarmPage()) {
                    debugLog("gamePlay: returned to farm after stay, task complete")
                    collectedCount++
                    currentTaskIndex++
                    moveTo(AutomationState.OPENING_TASK_LIST)
                    handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                } else {
                    // 不在农场页，可能退到中间页，再按一次返回
                    debugLog("gamePlay: not on farm after back, pressing back once more")
                    service.pressBack()
                    handler.postDelayed({
                        if (state == AutomationState.GAME_PLAYING) {
                            if (service.isOnFarmPage()) {
                                debugLog("gamePlay: returned to farm on second back, task complete")
                                collectedCount++
                            }
                            currentTaskIndex++
                            moveTo(AutomationState.OPENING_TASK_LIST)
                            handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                        }
                    }, INTERVAL_PAGE_LOAD_MS)
                }
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 其他页面：继续停留（不按返回，避免退出游戏），下一轮检测
        if (elapsedMs < GAME_LOAD_MS) {
            debugLog("gamePlay: waiting for game to load (elapsed=${elapsedMs}ms)")
        } else {
            debugLog("gamePlay: staying in game (elapsed=${elapsedMs}ms, target=${GAME_STAY_TARGET_MS}ms)")
        }

        // build529（用户要求"全部实现"）：AI 视觉识别环形进度条（节流到 20s 一次）
        // 用途：游戏页面通常无可读文本进度，截图交 GLM-4.6V-Flash 识别进度环填充比例，
        // 输出 percent/seconds_remaining 到日志，便于诊断"游戏卡在哪里、还要等多久"。
        // 不主动触发提前退出（AI 判断存在误差），仅作信息补充；退出仍由上方既有条件负责。
        if (elapsedMs >= GAME_LOAD_MS &&
            state == AutomationState.GAME_PLAYING &&
            System.currentTimeMillis() - lastAiProgressCheckMs >= AI_PROGRESS_CHECK_INTERVAL_MS) {
            lastAiProgressCheckMs = System.currentTimeMillis()
            val appContext = service.applicationContext
            val snapshotElapsed = elapsedMs
            Thread {
                val bitmap = service.takeScreenshotBitmap()
                if (bitmap == null) {
                    Log.w(TAG, "gamePlay: AI progress screenshot unavailable")
                    return@Thread
                }
                try {
                    val sceneCtx = "game playing (elapsed=${snapshotElapsed}ms, target=${GAME_STAY_TARGET_MS}ms)"
                    val result = AiVisionClient.recognizeProgressFromScreenshot(appContext, bitmap, sceneCtx)
                    bitmap.recycle()
                    if (result == null) {
                        Log.w(TAG, "gamePlay: AI progress returned null")
                    } else {
                        Log.i(TAG, "gamePlay: AI progress percent=${result.percent}%, " +
                            "secondsRemaining=${result.secondsRemaining}s, " +
                            "hasBar=${result.hasProgressBar}, reason='${result.reason.take(80)}'")
                        debugLog("gamePlay: AI progress percent=${result.percent}%, " +
                            "remaining=${result.secondsRemaining}s, hasBar=${result.hasProgressBar}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "gamePlay: AI progress exception: ${e.message}", e)
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }.start()
        }

        handler.postDelayed({
            if (state == AutomationState.GAME_PLAYING) runGamePlaying(elapsedMs + GAME_ACTION_INTERVAL_MS)
        }, GAME_ACTION_INTERVAL_MS)
    }

    /**
     * 检查任务点击结果
     * - 如果进入广告 → 看广告阶段
     * - 如果是非广告页面 → 返回继续下一个任务
     * - 如果无变化 → 重试或跳过
     */
    private fun checkTaskResult(service: FarmAccessibilityService, attempt: Int) {
        if (state != AutomationState.PROCESSING_TASK) return

        logPageSnapshot(service, "checkTaskResult")

        // 最高优先级：肥料图标 + 领取按钮 → 直接点击领取（绕过场景白名单）
        // 用户需求：如果有带肥图标，并有"领取"的按钮，可以直接点击领取
        // 比任务完成/肥料到账检测更早，因为领取按钮还没点就没有"完成"状态
        // findFertilizerClaimButton 已自行排除农场主页/广告播放中/任务列表场景
        val fertClaimBtn = service.findFertilizerClaimButton()
        if (fertClaimBtn != null) {
            val claimText = fertClaimBtn.text?.toString().orEmpty()
            Log.i(TAG, "processTask: fertilizer claim button found (text='$claimText'), clicking directly")
            debugLog("processTask: fertilizer icon + claim button detected (text='$claimText'), clicking directly (bypass scene whitelist)")
            service.performClickSafe(fertClaimBtn)
            // 点击领取后，等待肥料到账/任务完成提示，重新评估场景
            handler.postDelayed({
                if (state == AutomationState.PROCESSING_TASK) checkTaskResult(service, attempt + 1)
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 优先检测：是否显示"任务完成"页面 → 得到肥料后立即退出
        if (service.isTaskCompletePage()) {
            Log.i(TAG, "processTask: task complete page detected, exiting")
            debugLog("processTask: task complete, exiting via close/back icon")
            // 优先点右上角关闭或左上角返回图标
            val closeBtn = service.findAdCloseButton()
            val backIcon = service.findBackIcon()
            when {
                closeBtn != null -> { debugLog("processTask: clicking close icon"); service.performClickSafe(closeBtn) }
                backIcon != null -> { debugLog("processTask: clicking back icon"); service.performClickSafe(backIcon) }
                else -> { debugLog("processTask: pressing back"); service.pressBack() }
            }
            collectedCount++
            advanceTaskIndex()  // 多次任务重玩同一任务，否则前进到下一个
            handler.postDelayed({
                if (service.isOnFarmPage()) {
                    moveTo(AutomationState.OPENING_TASK_LIST)
                    handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                } else {
                    // 不在农场页，需要重新导航
                    debugLog("processTask: not on farm page after task complete, re-navigating")
                    moveTo(AutomationState.NAVIGATING)
                    handler.postDelayed({ runNavigating(0) }, INTERVAL_CLICK_MS)
                }
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 优先检测：是否显示"肥料已发放/奖励已到账"提示 → 肥料已到账，直接退出
        // 与 isTaskCompletePage 的区别：isTaskCompletePage 匹配"任务完成"等关键词，
        // 而签到/直接领取类任务弹出的是"肥料奖励已发放""奖励已到账"提示，不含"任务完成"字样，
        // 需单独检测，否则 bot 不识别为完成、不退出。
        if (service.isFertilizerGrantedPage()) {
            Log.i(TAG, "processTask: fertilizer granted page detected, exiting")
            debugLog("processTask: fertilizer granted, exiting via close/back icon")
            val closeBtn = service.findAdCloseButton()
            val backIcon = service.findBackIcon()
            when {
                closeBtn != null -> { debugLog("processTask: clicking close icon (fertilizer granted)"); service.performClickSafe(closeBtn) }
                backIcon != null -> { debugLog("processTask: clicking back icon (fertilizer granted)"); service.performClickSafe(backIcon) }
                else -> { debugLog("processTask: pressing back (fertilizer granted)"); service.pressBack() }
            }
            collectedCount++
            advanceTaskIndex()
            handler.postDelayed({
                if (service.isOnFarmPage()) {
                    moveTo(AutomationState.OPENING_TASK_LIST)
                    handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                } else {
                    debugLog("processTask: not on farm page after fertilizer granted, re-navigating")
                    moveTo(AutomationState.NAVIGATING)
                    handler.postDelayed({ runNavigating(0) }, INTERVAL_CLICK_MS)
                }
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 优先检测：是否有红包弹窗 → 先关闭它，才能继续处理
        // 红包弹窗会遮挡页面，不关闭会干扰后续检测
        val redPacketBtn = service.findRedPacketCloseButton()
        if (redPacketBtn != null) {
            Log.i(TAG, "processTask: red packet popup detected, closing it first")
            debugLog("processTask: closing red packet popup")
            service.performClickSafe(redPacketBtn)
            // 等待弹窗关闭后重新检测
            handler.postDelayed({
                if (state == AutomationState.PROCESSING_TASK) checkTaskResult(service, attempt)
            }, INTERVAL_CLICK_MS)
            return
        }

        // 检测：是否是浏览奖励页面（"每浏览x秒可得1次奖励"等）→ 切换到浏览滑动流程
        // 用户需求：这类页面需要上下滑动获取肥料，直到变成"已领取全部奖励"才返回
        if (service.hasBrowseRewardProgressHint()) {
            Log.i(TAG, "processTask: browse reward page detected (每浏览x秒), switching to BROWSING_TASK")
            debugLog("processTask: browse reward progress hint detected, entering BROWSING_TASK")
            // 已在浏览页面（点击任务按钮后进入），跳过 runBrowsingTask 的初始点击步骤
            browseTaskTargetSwipes = MAX_BROWSE_SWIPES  // 默认滑动次数，由进度提示驱动继续滑动
            browseFromDirectPopup = false  // 来自任务列表，完成后回 OPENING_TASK_LIST
            browseFromSearchBrowse = false
            moveTo(AutomationState.BROWSING_TASK)
            handler.postDelayed({ runBrowsingTask(swipeCount = 1) }, INTERVAL_CLICK_MS)
            return
        }

        // 检测：是否是"浏览x分钟得xxx肥料"停留等待页面 → 切换到浏览流程（不滑动，只等待）
        // 用户需求：这类页面需要停留等待，直到出现"已完成"才返回
        if (service.findBrowseDurationRewardHint() > 0) {
            Log.i(TAG, "processTask: browse duration page detected (浏览x分钟), switching to BROWSING_TASK")
            debugLog("processTask: browse duration hint detected, entering BROWSING_TASK (wait-only)")
            browseTaskTargetSwipes = MAX_BROWSE_SWIPES  // 初始值，runBrowsingTask 内会根据时长动态调整等待上限
            browseFromDirectPopup = false
            browseFromSearchBrowse = false
            moveTo(AutomationState.BROWSING_TASK)
            handler.postDelayed({ runBrowsingTask(swipeCount = 1) }, INTERVAL_CLICK_MS)
            return
        }

        // 检测：是否是"搜索后浏览立得奖励"任务页面 → 点击历史搜索词进入浏览流程
        // 用户需求：点击历史搜索内容进入真正的任务页面（显示"滑动浏览得肥料"），
        // 上下滑动直到"任务完成"，返回两次回到芭芭农场
        if (service.isSearchBrowseTaskPage()) {
            val historyKeyword = service.findHistorySearchKeyword()
            if (historyKeyword != null) {
                Log.i(TAG, "processTask: search browse task page detected, clicking history search keyword")
                debugLog("processTask: clicking history search keyword to enter browse page")
                service.performClickSafe(historyKeyword)
                // 标记来自搜索浏览任务，退出时返回两次
                browseFromSearchBrowse = true
                browseFromDirectPopup = false
                browseTaskTargetSwipes = MAX_BROWSE_SWIPES
                // 等待进入真正的浏览页面（"滑动浏览得肥料"），然后切换到 BROWSING_TASK
                moveTo(AutomationState.BROWSING_TASK)
                handler.postDelayed({ runBrowsingTask(swipeCount = 1) }, INTERVAL_PAGE_LOAD_MS)
                return
            } else {
                // 找不到历史搜索词，按返回退出
                debugLog("processTask: search browse task page but no history keyword found, exiting")
                service.pressBack()
                currentTaskIndex++
                handler.postDelayed({
                    if (state == AutomationState.PROCESSING_TASK) {
                        moveTo(AutomationState.OPENING_TASK_LIST)
                        handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                    }
                }, INTERVAL_PAGE_LOAD_MS)
                return
            }
        }

        // 检测：是否在"下单得肥料"搜索推荐页面 → 退出这个页面
        if (service.isSearchRecommendPage()) {
            Log.i(TAG, "processTask: search recommend page detected, exiting")
            debugLog("processTask: search recommend page, exiting via back icon")
            val backIcon = service.findBackIcon()
            if (backIcon != null) {
                debugLog("processTask: clicking back icon to exit search page")
                service.performClickSafe(backIcon)
            } else {
                debugLog("processTask: no back icon, pressing back")
                service.pressBack()
            }
            currentTaskIndex++
            // 等待返回，然后检查是否回到农场页
            handler.postDelayed({
                if (service.isOnFarmPage()) {
                    // 已回到农场页，重新打开任务列表
                    moveTo(AutomationState.OPENING_TASK_LIST)
                    handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                } else {
                    // 不在农场页（可能回到淘宝主页），需要重新导航到农场
                    debugLog("processTask: not on farm page after exiting search page, re-navigating")
                    service.pressBack()
                    handler.postDelayed({
                        moveTo(AutomationState.NAVIGATING)
                        handler.postDelayed({ runNavigating(0) }, INTERVAL_CLICK_MS)
                    }, INTERVAL_PAGE_LOAD_MS)
                }
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 检测：是否在异常页面（交易页面、商品详情页、收银台等）→ 立即退出
        if (service.isOnAbnormalPage()) {
            Log.i(TAG, "processTask: abnormal/trading page detected, exiting immediately")
            debugLog("processTask: abnormal page, pressing back")
            service.pressBack()
            currentTaskIndex++
            handler.postDelayed({
                if (service.isOnFarmPage()) {
                    moveTo(AutomationState.OPENING_TASK_LIST)
                    handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                } else {
                    debugLog("processTask: not on farm page after abnormal exit, re-navigating")
                    moveTo(AutomationState.NAVIGATING)
                    handler.postDelayed({ runNavigating(0) }, INTERVAL_CLICK_MS)
                }
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 检测是否进入广告（Activity/包名识别 + 内容级识别）
        // 充分理解各种广告设计意图：视频广告/插屏广告/WebView 广告等
        if (service.isAdActivity() || service.isAdPlaying() || service.isAdContentShown()) {
            Log.i(TAG, "processTask: ad opened! watching ad (activity=${service.isAdActivity()}, playing=${service.isAdPlaying()}, content=${service.isAdContentShown()})")
            debugLog("processTask: ad detected, entering WATCHING_AD")
            service.setAdMode(true)
            moveTo(AutomationState.WATCHING_AD)
            handler.postDelayed({ runWatchingAd(elapsedMs = 0L) }, INTERVAL_CLICK_MS)
            return
        }

        // 检测非广告任务页面（邀请/关注/分享/下载App/开通会员等）→ 跳过任务
        // 用户要求：只看广告获取肥料，非广告任务不做
        if (service.isNonAdTaskPage()) {
            Log.i(TAG, "processTask: non-ad task page detected (invite/share/download/membership), skipping task")
            debugLog("processTask: non-ad task page, skipping task #$${currentTaskIndex + 1}")
            service.pressBack()
            currentTaskIndex++
            handler.postDelayed({
                if (state == AutomationState.PROCESSING_TASK) {
                    moveTo(AutomationState.OPENING_TASK_LIST)
                    handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                }
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 检测是否在非广告页面（邀请链接、应用商店安装页面等）→ 跳过任务
        if (service.isNonAdPage()) {
            Log.i(TAG, "processTask: non-ad package page detected, skipping task")
            debugLog("processTask: non-ad package page, skipping task #$${currentTaskIndex + 1}")
            service.pressBack()
            currentTaskIndex++
            handler.postDelayed({
                if (state == AutomationState.PROCESSING_TASK) {
                    moveTo(AutomationState.OPENING_TASK_LIST)
                    handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                }
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 检测广告深链跳转：跳转到其他App（如淘宝/京东/拼多多浏览商品）
        // 不是应用商店下载、不是非广告任务、不在农场App → 视为广告深链任务进行中
        // 等待用户/机器人在其他App执行任务后回到农场App
        if (!service.isOnFarmPage()) {
            val otherPkg = service.getCurrentWindowPackage()
            // P0-C（build519 修复）：Honor 设备 getCurrentWindowPackage 会误报 systemui
            // 历史问题（debug_test_20260718_205618.log, build517-0885ae7）：
            // - 点击"去完成"按钮后支付宝内部跳转到搜索页（样本含"芭芭农场,搜索,全部,服饰鞋包"）
            // - isOnFarmPage 正确返回 false（搜索页不是农场页）
            // - 但 getCurrentWindowPackage 返回 "com.android.systemui"（Honor 顶部状态栏窗口误报，
            //   详见 FarmAccessibilityService.kt:4264-4266 的注释）
            // - 误判为"深链跳转到 systemui"进入 WATCHING_AD，16ms 后 STOPPING，自动化直接结束
            //
            // 修复：用 rootInActiveWindowSafe().packageName 判断用户实际看到的真实页面
            // - 真实页面是农场包名（支付宝内部跳转）→ getCurrentWindowPackage 误报 systemui，
            //   不进 WATCHING_AD，继续后续 scene 检测（identifyCurrentScene 会处理搜索页等）
            // - 真实页面是 systemui（真正的下拉通知栏/控制中心）→ 等待用户关闭后重试
            // - 真实页面是其他非农场 App（真的深链跳转）→ 进入 WATCHING_AD 等待返回
            val activeRootPkg = service.rootInActiveWindowSafe()?.packageName?.toString().orEmpty()
            val cfg = service.currentPlatformConfig()
            val isActiveRootFarmPkg = activeRootPkg.isNotEmpty() && (
                cfg.packageNames.contains(activeRootPkg) ||
                cfg.internalPackagePrefixes.any { activeRootPkg.startsWith(it) } ||
                activeRootPkg == "com.bbncbot")
            val isRealSystemUiOverlay = otherPkg == "com.android.systemui" &&
                (activeRootPkg.isEmpty() || activeRootPkg == "com.android.systemui")
            when {
                // 真实页面是农场包名 → getCurrentWindowPackage 误报 systemui，不进 WATCHING_AD
                // 继续后续 scene 检测（支付宝内部跳转的搜索页/任务页等）
                isActiveRootFarmPkg -> {
                    debugLog("processTask: not on farm page but activeRoot='$activeRootPkg' is farm pkg, otherPkg='$otherPkg' (systemui false positive), skip WATCHING_AD, continue scene detection")
                }
                // 真实页面是 systemui（真正的下拉通知栏/控制中心）→ 等待用户关闭后重试
                isRealSystemUiOverlay -> {
                    debugLog("processTask: real systemui overlay detected (activeRoot='$activeRootPkg'), waiting for dismissal, retry attempt=$attempt")
                    handler.postDelayed({
                        if (state == AutomationState.PROCESSING_TASK) runProcessingTask(attempt + 1)
                    }, INTERVAL_PAGE_LOAD_MS)
                    return
                }
                // 真实页面是非农场 App（真的深链跳转）→ 进入 WATCHING_AD
                else -> {
                    Log.i(TAG, "processTask: deep-linked to another app (otherPkg=$otherPkg, activeRoot=$activeRootPkg), treating as ad task")
                    debugLog("processTask: deep-link ad task, otherPkg=$otherPkg, activeRoot=$activeRootPkg, entering WATCHING_AD to wait for return to farm")
                    service.setAdMode(true)
                    moveTo(AutomationState.WATCHING_AD)
                    handler.postDelayed({ runWatchingAd(elapsedMs = 0L) }, INTERVAL_CLICK_MS)
                    return
                }
            }
        }

        // 检测是否还在农场页（点击无效果 / 签到答题弹窗 / 任务完成弹窗）
        // 或签到页面（签到页在农场 App WebView 内，但 isOnFarmPage 可能返回 false，
        // 因为签到页没有"集肥料"/"施肥"等农场核心元素；用场景识别 SIGN_IN 兜底进入此分支）
        val scene = service.identifyCurrentScene()

        // 答题页面检测（问题 + 2 个选项，需调用 AI API 获取答案）
        // 用户需求：回答问题就可以领取肥料，可以思考下认真回答问题
        // 答题只有一次机会，不能试错，必须调用 AI 获取正确答案后再点击
        if (scene == FarmAccessibilityService.PageScene.QUIZ_PAGE) {
            val question = service.findQuizQuestion()
            val options = service.findQuizOptions()
            if (question.isBlank() || options.size != 2) {
                Log.w(TAG, "processTask: QUIZ_PAGE but question/options invalid (q='$question', opts=${options.size}), skipping")
                debugLog("processTask: quiz page but invalid question/options, skipping task")
                service.pressBack()
                currentTaskIndex++
                handler.postDelayed({
                    if (state == AutomationState.PROCESSING_TASK) {
                        moveTo(AutomationState.OPENING_TASK_LIST)
                        handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                    }
                }, INTERVAL_PAGE_LOAD_MS)
                return
            }

            val opt1Text = options[0].text?.toString().orEmpty().ifBlank {
                options[0].contentDescription?.toString().orEmpty()
            }
            val opt2Text = options[1].text?.toString().orEmpty().ifBlank {
                options[1].contentDescription?.toString().orEmpty()
            }
            Log.i(TAG, "processTask: quiz page detected (q='${question.take(60)}', opt1='$opt1Text', opt2='$opt2Text'), asking AI")
            debugLog("processTask: quiz detected (q='${question.take(60)}', opt1='$opt1Text', opt2='$opt2Text'), calling GLM API")

            // 网络请求必须在后台线程（QuizAnswerClient.askAnswer 含网络 IO）
            // 保存节点引用的文本和索引，避免后台线程返回后节点失效
            val context = service.applicationContext
            val opt1NodeText = opt1Text
            val opt2NodeText = opt2Text
            Thread {
                val aiAnswer = QuizAnswerClient.askAnswer(context, question, opt1NodeText, opt2NodeText)
                handler.post {
                    if (state != AutomationState.PROCESSING_TASK) return@post
                    if (aiAnswer.isBlank()) {
                        // AI 获取答案失败：默认选第一个选项（兜底，避免放弃任务）
                        Log.w(TAG, "processTask: AI answer empty, defaulting to first option '$opt1NodeText'")
                        debugLog("processTask: AI answer failed, defaulting to first option")
                        val opts = service.findQuizOptions()
                        if (opts.isNotEmpty()) {
                            service.performClickSafe(opts[0])
                        } else {
                            debugLog("processTask: quiz options gone after AI call, pressing back")
                            service.pressBack()
                        }
                    } else {
                        // AI 返回了答案，重新查找选项节点（后台线程期间节点可能已失效）
                        val opts = service.findQuizOptions()
                        val target = opts.firstOrNull { opt ->
                            val t = opt.text?.toString().orEmpty().ifBlank {
                                opt.contentDescription?.toString().orEmpty()
                            }
                            t == aiAnswer
                        }
                        if (target != null) {
                            Log.i(TAG, "processTask: clicking AI-selected answer '$aiAnswer'")
                            debugLog("processTask: clicking AI answer '$aiAnswer'")
                            service.performClickSafe(target)
                        } else {
                            // 文本匹配失败，按位置兜底（AI 答案是 opt1 则点第一个，否则点第二个）
                            val fallbackIndex = if (aiAnswer == opt1NodeText) 0 else 1
                            Log.w(TAG, "processTask: AI answer '$aiAnswer' node not found, fallback to option #$fallbackIndex")
                            debugLog("processTask: AI answer node not found, fallback to option #$fallbackIndex")
                            if (opts.isNotEmpty() && fallbackIndex < opts.size) {
                                service.performClickSafe(opts[fallbackIndex])
                            } else {
                                service.pressBack()
                            }
                        }
                    }
                    // 点击答案后，等待答题结果（答对领取肥料 / 答错提示）
                    handler.postDelayed({
                        if (state == AutomationState.PROCESSING_TASK) checkTaskResult(service, attempt + 1)
                    }, INTERVAL_PAGE_LOAD_MS)
                }
            }.start()
            return
        }

        if (service.isOnFarmPage() || scene == FarmAccessibilityService.PageScene.SIGN_IN) {
            // 尝试点击"返回首页"按钮（任务完成弹窗）
            val backBtn = service.findBackToHomeButton()
            if (backBtn != null) {
                Log.i(TAG, "processTask: found '返回首页' button on farm page, clicking")
                debugLog("processTask: found 返回首页 button, clicking")
                service.performClickSafe(backBtn)
                handler.postDelayed({
                    if (state == AutomationState.PROCESSING_TASK) {
                        // 返回首页后重新打开任务列表
                        moveTo(AutomationState.OPENING_TASK_LIST)
                        handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                    }
                }, INTERVAL_PAGE_LOAD_MS)
                return
            }
            // 尝试点击签到确认/答题/领取奖励按钮
            // 场景白名单已放行 SIGN_IN，findClaimRewardButton 会匹配"立即签到"/"签到领取"/"签到"
            val claimBtn = service.findClaimRewardButton()
            if (claimBtn != null) {
                val claimText = claimBtn.text?.toString().orEmpty()
                Log.i(TAG, "processTask: found claim/confirm button on farm page (text='$claimText'), clicking")
                debugLog("processTask: claim button on farm (text='$claimText', scene=$scene), clicking")
                service.performClickSafe(claimBtn)
                // 点击签到/领取按钮后，不立即跳下一个任务：
                // 签到按钮点击后会弹出"签到成功，获得 xxx 肥料"提示，需要先关闭弹窗才能继续。
                // 改为 checkTaskResult(attempt+1) 重新检测，让 isFertilizerGrantedPage 分支
                // 检测签到成功弹窗并关闭，那个分支会 currentTaskIndex++ 前进到下一个任务。
                // （答题确认等场景同样安全：点击确认后重新检测，确认任务真的完成了再前进）
                handler.postDelayed({
                    if (state == AutomationState.PROCESSING_TASK) checkTaskResult(service, attempt + 1)
                }, INTERVAL_PAGE_LOAD_MS)
                return
            }
            // WebView 中 performClickSafe 已经尝试了 ACTION_CLICK + dispatchGesture 修正坐标
            // 如果仍然在农场页，说明按钮点击确实无效（可能按钮已失效、需要滚动等）
            if (attempt < MAX_TASK_ATTEMPTS - 1) {
                Log.i(TAG, "processTask: still on farm page (attempt $attempt), retry clicking task button")
                debugLog("processTask: still on farm page, retry task click attempt=$attempt")
                // 重新获取任务按钮并点击（可能列表已刷新）
                val buttons = service.findGoCompleteButtons()
                if (buttons.isNotEmpty() && currentTaskIndex < buttons.size) {
                    taskButtons = buttons
                    service.performClickSafe(buttons[currentTaskIndex])
                } else {
                    // 按钮列表变了，需要重新打开任务列表
                    Log.w(TAG, "processTask: task buttons changed, reopening task list")
                    moveTo(AutomationState.OPENING_TASK_LIST)
                    handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                    return
                }
                handler.postDelayed({
                    if (state == AutomationState.PROCESSING_TASK) checkTaskResult(service, attempt + 1)
                }, INTERVAL_PAGE_LOAD_MS)
                return
            }
            Log.w(TAG, "processTask: still on farm page after $MAX_TASK_ATTEMPTS attempts, skipping task")
            debugLog("processTask: still on farm page after $MAX_TASK_ATTEMPTS attempts, skipping task")
            // 跳过当前任务，继续下一个
            currentTaskIndex++
            noProgressRounds++
            handler.postDelayed({
                if (state == AutomationState.PROCESSING_TASK) runProcessingTask(0)
            }, INTERVAL_CLICK_MS)
            return
        }

        // 其他情况：不在农场页，也不是广告（如商品详情页、逛逛页面等），按返回键
        // 这种情况说明点击了"去逛逛"等按钮进入了非广告页面，需要返回
        if (service.isOnAbnormalPage()) {
            debugLog("processTask: abnormal/trading page detected, pressing back and skipping task")
            service.pressBack()
            currentTaskIndex++
            handler.postDelayed({
                if (!service.isOnFarmPage()) service.pressBack()
                handler.postDelayed({
                    moveTo(AutomationState.OPENING_TASK_LIST)
                    handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                }, INTERVAL_CLICK_MS)
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }
        Log.i(TAG, "processTask: unknown page (not farm, not ad), failCount=$currentTaskFailCount/$MAX_TASK_FAILS")
        debugLog("processTask: unknown page, pkg=${service.getCurrentWindowPackage()}, act=${service.getCurrentActivityName()}, failCount=$currentTaskFailCount/$MAX_TASK_FAILS")

        // 未知页面 → 失败计数
        currentTaskFailCount++

        // 失败次数已达上限 → 跳过该任务，避免在无法完成的任务上死循环
        if (currentTaskFailCount >= MAX_TASK_FAILS) {
            Log.w(TAG, "processTask: task failed $currentTaskFailCount times (AI exhausted), skipping task #${currentTaskIndex + 1}")
            debugLog("processTask: reached MAX_TASK_FAILS after AI exhausted, skipping task #${currentTaskIndex + 1}")
            service.pressBack()
            currentTaskIndex++
            noProgressRounds++
            handler.postDelayed({
                if (state == AutomationState.PROCESSING_TASK) {
                    moveTo(AutomationState.OPENING_TASK_LIST)
                    handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                }
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 用户需求：有些不能处理的问题可以截图交给 API 来处理
        // 未知页面兜底：截图交给智谱 GLM-4.6V-Flash 视觉模型，让 AI 决定下一步动作
        // 不限制调用次数（用户明确选择），每次进入 UNKNOWN 都调（除非 API Key 未配置）
        val appContext = service.applicationContext
        val sceneContext = "task #${currentTaskIndex + 1}, failCount=$currentTaskFailCount/$MAX_TASK_FAILS, " +
            "pkg=${service.getCurrentWindowPackage()}, act=${service.getCurrentActivityName()}"
        debugLog("processTask: unknown page, asking AI vision for action")
        Thread {
            val bitmap = service.takeScreenshotBitmap()
            if (bitmap == null) {
                debugLog("processTask: AI vision skipped, screenshot not available")
                handler.post {
                    if (state != AutomationState.PROCESSING_TASK) return@post
                    debugLog("processTask: screenshot unavailable, pressing back")
                    service.pressBack()
                    handler.postDelayed({
                        if (state == AutomationState.PROCESSING_TASK) {
                            moveTo(AutomationState.OPENING_TASK_LIST)
                            handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                        }
                    }, INTERVAL_PAGE_LOAD_MS)
                }
                return@Thread
            }
            try {
                val result = AiVisionClient.analyzeScreenshot(appContext, bitmap, sceneContext)
                bitmap.recycle()
                handler.post {
                    if (state != AutomationState.PROCESSING_TASK) return@post
                    if (result == null) {
                        debugLog("processTask: AI vision returned null, pressing back")
                        service.pressBack()
                        handler.postDelayed({
                            if (state == AutomationState.PROCESSING_TASK) {
                                moveTo(AutomationState.OPENING_TASK_LIST)
                                handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                            }
                        }, INTERVAL_PAGE_LOAD_MS)
                        return@post
                    }
                    debugLog("processTask: AI vision action=${result.action}, reason='${result.reason.take(80)}'")
                    Log.i(TAG, "processTask: AI vision action=${result.action}, reason='${result.reason.take(80)}'")
                    executeAiVisionAction(service, result.action)
                }
            } catch (e: Exception) {
                Log.e(TAG, "processTask: AI vision exception: ${e.message}", e)
                if (!bitmap.isRecycled) bitmap.recycle()
                handler.post {
                    if (state != AutomationState.PROCESSING_TASK) return@post
                    debugLog("processTask: AI vision exception, pressing back")
                    service.pressBack()
                    handler.postDelayed({
                        if (state == AutomationState.PROCESSING_TASK) {
                            moveTo(AutomationState.OPENING_TASK_LIST)
                            handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                        }
                    }, INTERVAL_PAGE_LOAD_MS)
                }
            }
        }.start()
    }

    /**
     * 执行 AI 视觉决策返回的动作
     *
     * 5 个预定义动作的执行逻辑：
     * - CLICK_CLOSE  : 查找关闭按钮（×/关闭/知道了/确定）并点击，失败按返回
     * - CLICK_CLAIM  : 查找领取按钮（领取/领取奖励/领取肥料/确定）并点击，失败按返回
     * - PRESS_BACK   : 直接按返回键
     * - SKIP_TASK    : 跳过当前任务（currentTaskIndex++）并打开任务列表
     * - WAIT         : 不操作，等待后重新检测场景
     *
     * 所有动作执行后都通过 [checkTaskResult] 重新评估场景（WAIT 除外，WAIT 直接重试 processTask）。
     */
    private fun executeAiVisionAction(
        service: FarmAccessibilityService,
        action: AiVisionAction
    ) {
        when (action) {
            AiVisionAction.CLICK_CLOSE -> {
                debugLog("executeAiVisionAction: CLICK_CLOSE - finding close button")
                val closeBtn = service.findAdCloseButton(enforceSceneWhitelist = false)
                if (closeBtn != null) {
                    service.performClickSafe(closeBtn)
                } else {
                    debugLog("executeAiVisionAction: no close button found, pressing back")
                    service.pressBack()
                }
                handler.postDelayed({
                    if (state == AutomationState.PROCESSING_TASK) checkTaskResult(service, 0)
                }, INTERVAL_PAGE_LOAD_MS)
            }
            AiVisionAction.CLICK_CLAIM -> {
                debugLog("executeAiVisionAction: CLICK_CLAIM - finding claim button")
                val claimBtn = service.findClaimRewardButton(enforceSceneWhitelist = false)
                if (claimBtn != null) {
                    service.performClickSafe(claimBtn)
                } else {
                    debugLog("executeAiVisionAction: no claim button found, pressing back")
                    service.pressBack()
                }
                handler.postDelayed({
                    if (state == AutomationState.PROCESSING_TASK) checkTaskResult(service, 0)
                }, INTERVAL_PAGE_LOAD_MS)
            }
            AiVisionAction.PRESS_BACK -> {
                debugLog("executeAiVisionAction: PRESS_BACK")
                service.pressBack()
                handler.postDelayed({
                    if (state == AutomationState.PROCESSING_TASK) checkTaskResult(service, 0)
                }, INTERVAL_PAGE_LOAD_MS)
            }
            AiVisionAction.SKIP_TASK -> {
                debugLog("executeAiVisionAction: SKIP_TASK - skipping task #${currentTaskIndex + 1}")
                Log.i(TAG, "processTask: AI suggested SKIP_TASK, skipping task #${currentTaskIndex + 1}")
                service.pressBack()
                currentTaskIndex++
                noProgressRounds++
                handler.postDelayed({
                    if (state == AutomationState.PROCESSING_TASK) {
                        moveTo(AutomationState.OPENING_TASK_LIST)
                        handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                    }
                }, INTERVAL_PAGE_LOAD_MS)
            }
            AiVisionAction.WAIT -> {
                debugLog("executeAiVisionAction: WAIT - will recheck scene after delay")
                handler.postDelayed({
                    if (state == AutomationState.PROCESSING_TASK) checkTaskResult(service, 0)
                }, INTERVAL_PAGE_LOAD_MS * 2)
            }
        }
    }

    // ============== 阶段3c: 蚂蚁森林领落叶肥料 ==============

    /**
     * 蚂蚁森林领落叶肥料任务
     * - 用户要求：关闭弹出页面上的其它弹窗 → 点击"领奖励" → 找到"逛农场得落叶肥料"
     *
     * 步骤：
     * - step 0: 关闭弹窗（查找 × / 关闭 / 知道了 / 我知道了 等按钮并点击）
     * - step 1: 查找并点击"领奖励"按钮
     * - step 2: 查找并点击"逛农场得落叶肥料"入口
     * - step 3: 等待回到农场页，任务完成
     *
     * 每步最多重试 [MAX_FOREST_RETRIES] 次，失败则用 AI 视觉辅助
     */
    private fun runForestCollecting(step: Int, retryCount: Int) {
        if (state != AutomationState.FOREST_COLLECTING) return
        val service = getService() ?: run { stop(); return }

        debugLog("forestCollect: step=$step, retry=$retryCount")

        // 回到农场页 → 任务完成
        if (service.isOnFarmPage()) {
            Log.i(TAG, "forestCollect: back on farm page, task complete")
            debugLog("forestCollect: back on farm, done")
            currentTaskIndex++
            moveTo(AutomationState.OPENING_TASK_LIST)
            handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
            return
        }

        when (step) {
            0 -> {
                // 关闭弹窗：查找 × / 关闭 / 知道了 / 我知道了 / 知道啦 等按钮
                val popupKeywords = listOf("知道了", "我知道了", "知道啦", "关闭", "×", "确定", "好的")
                val root = service.rootInActiveWindowSafe()
                var closedPopup = false
                if (root != null) {
                    for (kw in popupKeywords) {
                        val node = service.findNodeByText(root, kw)
                        if (node != null) {
                            debugLog("forestCollect: closing popup '$kw'")
                            service.performClickSafe(node)
                            closedPopup = true
                            break
                        }
                    }
                }
                if (closedPopup) {
                    // 弹窗关闭后，继续检查是否还有弹窗
                    handler.postDelayed({
                        if (state == AutomationState.FOREST_COLLECTING) runForestCollecting(step = 0, retryCount = 0)
                    }, INTERVAL_CLICK_MS)
                } else {
                    // 没有弹窗了，进入下一步（领奖励）
                    debugLog("forestCollect: no more popups, moving to claim reward")
                    handler.postDelayed({
                        if (state == AutomationState.FOREST_COLLECTING) runForestCollecting(step = 1, retryCount = 0)
                    }, INTERVAL_CLICK_MS)
                }
            }
            1 -> {
                // 查找并点击"领奖励"按钮
                val root = service.rootInActiveWindowSafe()
                var claimed = false
                if (root != null) {
                    val claimKeywords = listOf("领奖励", "领取奖励", "立即领取", "领取", "收下", "好的领")
                    for (kw in claimKeywords) {
                        val node = service.findNodeByText(root, kw)
                        if (node != null) {
                            debugLog("forestCollect: clicking claim button '$kw'")
                            service.performClickSafe(node)
                            claimed = true
                            break
                        }
                    }
                }
                if (claimed) {
                    // 领奖励后，进入下一步（逛农场得落叶肥料）
                    handler.postDelayed({
                        if (state == AutomationState.FOREST_COLLECTING) runForestCollecting(step = 2, retryCount = 0)
                    }, INTERVAL_PAGE_LOAD_MS)
                } else if (retryCount < 3) {
                    // 重试查找领奖励按钮
                    debugLog("forestCollect: claim button not found, retry $retryCount")
                    handler.postDelayed({
                        if (state == AutomationState.FOREST_COLLECTING) runForestCollecting(step = 1, retryCount = retryCount + 1)
                    }, INTERVAL_CLICK_MS)
                } else {
                    // 多次重试失败，跳过此步直接找"逛农场得落叶肥料"
                    debugLog("forestCollect: claim button not found after retries, skipping to step 2")
                    runForestCollecting(step = 2, retryCount = 0)
                }
            }
            2 -> {
                // 查找并点击"逛农场得落叶肥料"入口
                val root = service.rootInActiveWindowSafe()
                var found = false
                if (root != null) {
                    val farmKeywords = listOf("逛农场得落叶肥料", "逛农场", "落叶肥料", "得落叶肥料", "回农场", "回芭芭农场")
                    for (kw in farmKeywords) {
                        val node = service.findNodeByText(root, kw)
                        if (node != null) {
                            debugLog("forestCollect: clicking farm entry '$kw'")
                            service.performClickSafe(node)
                            found = true
                            break
                        }
                    }
                }
                if (found) {
                    // 点击后等待回到农场页
                    handler.postDelayed({
                        if (state == AutomationState.FOREST_COLLECTING) runForestCollecting(step = 3, retryCount = 0)
                    }, INTERVAL_PAGE_LOAD_MS)
                } else if (retryCount < 3) {
                    debugLog("forestCollect: farm entry not found, retry $retryCount")
                    handler.postDelayed({
                        if (state == AutomationState.FOREST_COLLECTING) runForestCollecting(step = 2, retryCount = retryCount + 1)
                    }, INTERVAL_CLICK_MS)
                } else {
                    // 重试失败，按返回回到农场
                    debugLog("forestCollect: farm entry not found, pressing back to return to farm")
                    service.pressBack()
                    handler.postDelayed({
                        if (state == AutomationState.FOREST_COLLECTING) runForestCollecting(step = 3, retryCount = 0)
                    }, INTERVAL_PAGE_LOAD_MS)
                }
            }
            else -> {
                // step 3+: 等待回到农场页
                if (service.isOnFarmPage()) {
                    Log.i(TAG, "forestCollect: back on farm page, task complete")
                    debugLog("forestCollect: back on farm, done")
                    currentTaskIndex++
                    moveTo(AutomationState.OPENING_TASK_LIST)
                    handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                } else if (retryCount < 5) {
                    // 还没回到农场，按返回重试
                    debugLog("forestCollect: not on farm yet, pressing back (retry $retryCount)")
                    service.pressBack()
                    handler.postDelayed({
                        if (state == AutomationState.FOREST_COLLECTING) runForestCollecting(step = 3, retryCount = retryCount + 1)
                    }, INTERVAL_PAGE_LOAD_MS)
                } else {
                    // 超过重试次数，放弃此任务
                    Log.w(TAG, "forestCollect: failed to return to farm after retries, skipping task")
                    debugLog("forestCollect: failed to return, skipping task")
                    currentTaskIndex++
                    moveTo(AutomationState.OPENING_TASK_LIST)
                    handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                }
            }
        }
    }

    // ============== 阶段4: 看广告 ==============

    /**
     * 看广告阶段：等待广告播放完成
     * - 进入时解析广告页面时长提示（如"观看15秒"），动态设置最短等待时间
     * - 最短等待 adMinDurationMs（默认30秒，或页面提示时长+缓冲）
     * - 超时 adMaxDurationMs（默认90秒，长广告动态延长）强制关闭
     * - 动态检测广告是否结束
     *
     * 用户要求：太快退出可能获取不到肥料，需保持到规定时间+缓冲后再检测退出
     */
    private fun runWatchingAd(elapsedMs: Long) {
        if (state != AutomationState.WATCHING_AD) return
        val service = getService() ?: run { stop(); return }

        // 进入广告时（首次调用）解析页面时长提示，动态设置最短等待时间
        // 用户要求：有些广告需要指定时间才能领取肥料，保持到规定时间+1秒后再检测退出
        if (elapsedMs == 0L) {
            deepLinkAppPkg = null       // 重置深链跳转跟踪，等待检测是否进入其他 App
            fasterRewardStage = 0       // 重置"更快拿奖"弹窗处理状态
            fasterRewardAppPkg = null   // 重置新 App 包名记录
            fasterRewardAppEnterTimeMs = 0L  // 重置新 App 进入时间戳
            prevAdHadCountdown = false  // 重置倒计时状态，供多信号融合检测用
            // build529：进入广告时重置 AI 视觉进度识别节流（每个广告独立计数）
            lastAiProgressCheckMs = 0L
            watchingAdPlatform = service.currentPlatform  // 记录农场平台，强杀深链 App 后重新启动此平台
            // 按平台广告策略加载默认时长与检测间隔（UC/支付宝/淘宝差异化）
            val platformCfg = service.currentPlatformConfig()
            adEndCheckIntervalMs = platformCfg.adEndCheckIntervalMs
            val hintSeconds = service.findAdDurationHint()
            if (hintSeconds > 0) {
                // 页面提示的秒数 + 缓冲时间（毫秒）
                adMinDurationMs = hintSeconds * 1000L + AD_DURATION_BUFFER_MS
                // 最大等待时间随最短时间动态调整：最短+30秒余量，且不小于平台默认上限
                adMaxDurationMs = maxOf(platformCfg.adDefaultMaxDurationMs, adMinDurationMs + 30000L)
                debugLog("watchAd: parsed ad duration hint=${hintSeconds}s, min wait=${adMinDurationMs}ms, max wait=${adMaxDurationMs}ms (hint+buffer), checkInterval=${adEndCheckIntervalMs}ms")
            } else {
                // 无时长提示：使用平台默认值（UC=30s/90s, 支付宝=15s/60s, 淘宝=20s/75s）
                adMinDurationMs = platformCfg.adDefaultMinDurationMs
                adMaxDurationMs = platformCfg.adDefaultMaxDurationMs
                debugLog("watchAd: no duration hint, platform=${platformCfg.platform}, default min=${adMinDurationMs}ms, max=${adMaxDurationMs}ms, checkInterval=${adEndCheckIntervalMs}ms")
            }
        }

        // 每 15 秒输出一次页面快照（避免日志过多）
        if (elapsedMs % 15000L < adEndCheckIntervalMs) {
            logPageSnapshot(service, "watchAd-${elapsedMs}ms")
        }

        // "更快拿奖"流程仅在支持的平台执行（UC 特有，支付宝/淘宝跳过）
        val supportsFasterReward = service.currentPlatformConfig().supportsFasterReward
        if (supportsFasterReward) {
            // 优先检测：UC 芭芭农场广告页"更快拿奖"弹窗处理
            // 用户需求：点"我要更快拿奖" → 弹窗点"允许" → 新app打开停留16秒
            // → 关闭新打开的app → 回到"恭喜获得奖励提升"窗口 → 点右上角关闭 → 回芭芭农场
            // 状态机：0=待检测入口按钮 / 1=已点入口等待确认弹窗点允许 /
            //         2=已点允许新app打开停留16秒 / 3=已关闭新app等待奖励提升窗口点关闭 / 4=已完成
            when (fasterRewardStage) {
                0 -> {
                    // 阶段0：查找"我要更快拿奖"按钮
                    val entryBtn = service.findFasterRewardEntryButton()
                    if (entryBtn != null) {
                        Log.i(TAG, "watchAd: found '我要更快拿奖' button, clicking it")
                        debugLog("watchAd: clicking '我要更快拿奖' entry button")
                        service.performClickSafe(entryBtn)
                        fasterRewardStage = 1
                        // 等待确认弹窗出现（"15秒更快拿奖"）
                        handler.postDelayed({
                            if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                        }, INTERVAL_PAGE_LOAD_MS)
                        return
                    }
                }
                1 -> {
                    // 阶段1：已点入口按钮，等待"15秒更快拿奖"确认弹窗出现，然后点"允许"
                    if (service.isFasterRewardPopupShown()) {
                        val allowBtn = service.findFasterRewardAllowButton()
                        if (allowBtn != null) {
                            Log.i(TAG, "watchAd: faster reward confirm popup detected, clicking allow")
                            debugLog("watchAd: clicking allow on faster reward confirm popup")
                            service.performClickSafe(allowBtn)
                            fasterRewardStage = 2
                            // 记录点击"允许"时的时间戳，用于计算16秒停留
                            fasterRewardAppEnterTimeMs = System.currentTimeMillis()
                            // 等待新 App 打开，然后停留16秒
                            handler.postDelayed({
                                if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                            }, INTERVAL_PAGE_LOAD_MS)
                            return
                        } else {
                            // 弹窗出现但"允许"按钮未渲染，短暂等待后重试
                            debugLog("watchAd: faster reward popup shown but allow button not found, retrying")
                            handler.postDelayed({
                                if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                            }, INTERVAL_CLICK_MS)
                            return
                        }
                    } else {
                        // 确认弹窗还没出现，继续等待（可能页面切换中）
                        debugLog("watchAd: waiting for faster reward confirm popup (stage=1)")
                        handler.postDelayed({
                            if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                        }, adEndCheckIntervalMs)
                        return
                    }
                }
                2 -> {
                    // 阶段2：已点"允许"，新 App 已打开，停留16秒
                    val currentPkg = service.getCurrentWindowPackage()
                    // 首次进入此阶段时记录新 App 包名
                    if (fasterRewardAppPkg == null && currentPkg != null) {
                        // 排除农场 App 自身（防止误判）
                        val farmPkgs = watchingAdPlatform.config.packageNames
                        if (!farmPkgs.contains(currentPkg)) {
                            fasterRewardAppPkg = currentPkg
                            Log.i(TAG, "watchAd: faster reward new app '$currentPkg' opened, staying ${FASTER_REWARD_APP_STAY_MS}ms")
                            debugLog("watchAd: faster reward app '$currentPkg' opened, staying 16s")
                        }
                    }
                    // 陷阱防护：停留期间检测诱导弹窗（立即下载等）→ 优先关闭
                    if (service.findAdInstallButton() != null) {
                        Log.w(TAG, "watchAd: faster reward stay interrupted by install popup, closing it")
                        debugLog("watchAd: install popup during faster reward stay, attempting close")
                        service.closeAdInstallPopup()
                        handler.postDelayed({
                            if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                        }, INTERVAL_CLICK_MS)
                        return
                    }
                    // 陷阱防护：停留期间检测交易页（违反禁止交易原则）→ 立即结束流程
                    if (service.isOnAbnormalPage() || service.isRechargePage()) {
                        Log.w(TAG, "watchAd: faster reward stay hit abnormal/recharge page, aborting")
                        debugLog("watchAd: faster reward stay hit trap page, killing new app immediately")
                        service.setAdMode(false)
                        if (watchingAdPlatform != Platform.UNKNOWN) {
                            service.launchPlatformApp(watchingAdPlatform)
                        }
                        val killedPkg = fasterRewardAppPkg
                        if (killedPkg != null) {
                            service.forceKillApp(killedPkg, pressBackFirst = false)
                        } else {
                            service.pressBack()
                        }
                        currentTaskIndex++
                        handler.postDelayed({
                            if (state == AutomationState.WATCHING_AD) {
                                moveTo(AutomationState.OPENING_TASK_LIST)
                                handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                            }
                        }, INTERVAL_PAGE_LOAD_MS)
                        return
                    }
                    // 计算停留时间
                    val stayedMs = if (fasterRewardAppEnterTimeMs > 0) {
                        System.currentTimeMillis() - fasterRewardAppEnterTimeMs
                    } else 0L
                    if (stayedMs >= FASTER_REWARD_APP_STAY_MS) {
                        // 停留满16秒，关闭新打开的 App 并激活农场 App 到前台
                        Log.i(TAG, "watchAd: faster reward stayed ${stayedMs}ms, killing new app and activating farm")
                        debugLog("watchAd: 16s elapsed, killing new app '${fasterRewardAppPkg}' + activating farm")
                        service.setAdMode(false)
                        // 1. 激活农场 App 到前台
                        if (watchingAdPlatform != Platform.UNKNOWN) {
                            debugLog("watchAd: launching farm platform $watchingAdPlatform to foreground")
                            service.launchPlatformApp(watchingAdPlatform)
                        }
                        // 2. 同时 kill 掉新打开的 App
                        val killedPkg = fasterRewardAppPkg
                        if (killedPkg != null) {
                            service.forceKillApp(killedPkg, pressBackFirst = false)
                        } else {
                            // 没有记录到包名，按返回键尝试关闭
                            debugLog("watchAd: no pkg recorded, pressing back to close new app")
                            service.pressBack()
                        }
                        fasterRewardStage = 3
                        // 等待回到"恭喜获得奖励提升"窗口
                        handler.postDelayed({
                            if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                        }, INTERVAL_PAGE_LOAD_MS)
                        return
                    } else {
                        // 还未满16秒，继续等待
                        debugLog("watchAd: faster reward staying in new app, ${stayedMs}/${FASTER_REWARD_APP_STAY_MS}ms elapsed")
                        handler.postDelayed({
                            if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                        }, adEndCheckIntervalMs)
                        return
                    }
                }
                3 -> {
                    // 阶段3：已关闭新 App，等待"恭喜获得奖励提升"窗口，点右上角关闭
                    // 陷阱防护：阶段3 等待期间也可能误入落地页/诱导弹窗
                    if (service.isAdLandingPage()) {
                        Log.w(TAG, "watchAd: faster reward stage3 hit landing page, closing via closeAdLandingPage")
                        debugLog("watchAd: stage3 landing page trap, using closeAdLandingPage")
                        service.closeAdLandingPage()
                        handler.postDelayed({
                            if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                        }, INTERVAL_CLICK_MS)
                        return
                    }
                    if (service.isRewardUpgradePopupShown()) {
                        Log.i(TAG, "watchAd: reward upgrade popup detected, clicking close")
                        debugLog("watchAd: '恭喜获得奖励提升' popup detected, clicking top-right close")
                        // 点右上角关闭按钮
                        val closeBtn = service.findAdCloseButton()
                        val backIcon = service.findBackIcon()
                        when {
                            closeBtn != null -> { debugLog("watchAd: clicking close icon"); service.performClickSafe(closeBtn) }
                            backIcon != null -> { debugLog("watchAd: clicking back icon"); service.performClickSafe(backIcon) }
                            else -> { debugLog("watchAd: pressing back to close popup"); service.pressBack() }
                        }
                        fasterRewardStage = 4
                        collectedCount++
                        advanceTaskIndex()
                        // 等待返回到芭芭农场页面
                        handler.postDelayed({
                            if (state == AutomationState.WATCHING_AD) {
                                if (!service.isOnFarmPage()) {
                                    debugLog("watchAd: not on farm after close, pressing back")
                                    service.pressBack()
                                }
                                handler.postDelayed({
                                    if (state == AutomationState.WATCHING_AD) {
                                        moveTo(AutomationState.OPENING_TASK_LIST)
                                        handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                                    }
                                }, INTERVAL_CLICK_MS)
                            }
                        }, INTERVAL_PAGE_LOAD_MS)
                        return
                    } else {
                        // 还未出现"恭喜获得奖励提升"窗口，继续等待
                        debugLog("watchAd: waiting for '恭喜获得奖励提升' popup (stage=3)")
                        handler.postDelayed({
                            if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                        }, adEndCheckIntervalMs)
                        return
                    }
                }
                4 -> {
                    // 阶段4：已完成，不重复处理（避免重复点击）
                }
            }
        }

        // 误入非广告页面兜底检测
        // 场景：任务跳转到小程序/游戏页面（非广告），被误判为 deep-link ad task 进入 WATCHING_AD
        // 此时 adModeFlag=true 导致 isAdPlaying()=true，但页面实际没有广告特征
        // 检测：不是广告 Activity + 没有广告内容 + 有小程序/游戏特征 → 退出
        if (!service.isAdActivity() && !service.isAdContentShown() &&
            service.isMiniProgramOrGamePage()) {
            Log.w(TAG, "watchAd: mini-program/game page detected (non-ad), exiting task")
            debugLog("watchAd: non-ad page in WATCHING_AD (mini-program/game), clearing adMode and exiting")
            service.setAdMode(false)
            service.pressBack()
            currentTaskIndex++
            handler.postDelayed({
                if (!service.isOnFarmPage()) service.pressBack()
                handler.postDelayed({
                    if (state == AutomationState.WATCHING_AD) {
                        moveTo(AutomationState.OPENING_TASK_LIST)
                        handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                    }
                }, INTERVAL_PAGE_LOAD_MS)
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 场景识别驱动：陷阱防护统一调度（聪明思考，识别各种场景）
        //
        // 用户反馈："学会聪明思考，需要识别各种场景"
        // 重构思路：从"关键词黑名单驱动 + 被动检测"升级为"场景识别驱动 + 白名单优先"。
        // 一次性调用 identifyCurrentScene() 识别当前页面场景（多信号指纹融合），
        // 基于场景类型统一决策动作，替代零散的 if-else 检测。
        //
        // 优势：
        // 1. 场景优先级（充值→交易→小程序→落地页→诱导弹窗→复看）确保最危险的陷阱先处理
        //    （充值陷阱造成金钱损失，优先级最高；落地页只是浪费时间，优先级较低）
        // 2. 一次识别 + 短路返回，避免 5 次 isXxx() 重复遍历节点树
        // 3. 调用方基于场景类型决策，意图清晰，新增陷阱场景只需扩展 PageScene 枚举
        // 4. 与 findClaimRewardButton/findAdCloseButton 的场景白名单形成闭环防护
        val scene = service.identifyCurrentScene()
        debugLog("watchAd: scene=$scene, elapsed=${elapsedMs}ms/${adMaxDurationMs}ms")
        when (scene) {
            // 陷阱1：充值/付费页（最高优先级，违反禁止交易原则，可能造成金钱损失）
            // 策略：优先点关闭按钮，否则按返回退出，继续轮询（不退出任务，可能是广告内弹窗）
            PageScene.TRAP_RECHARGE -> {
                Log.w(TAG, "watchAd: recharge page detected (scene=$scene), exiting immediately (trap defense)")
                debugLog("watchAd: recharge page trap detected, clicking close on recharge page")
                val closed = service.clickCloseOnRechargePage()
                if (!closed) {
                    debugLog("watchAd: no close on recharge, pressing back")
                    service.pressBack()
                }
                handler.postDelayed({
                    if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                }, INTERVAL_CLICK_MS)
                return
            }
            // 陷阱2：交易/下单页（违反禁止交易原则）
            // 策略：保留 5s 等待避免深链跳转期间页面切换的误判，确认后退出任务
            // 注意：用 currentTaskIndex++（而非 advanceTaskIndex）— 陷阱任务直接跳过，不重玩
            PageScene.TRAP_ABNORMAL -> {
                if (elapsedMs < 5000L) {
                    // 未满 5s，可能是页面切换中的瞬时状态，继续轮询等待页面稳定
                    debugLog("watchAd: TRAP_ABNORMAL scene but elapsed=${elapsedMs}ms < 5000ms, waiting for page to stabilize")
                    handler.postDelayed({
                        if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                    }, adEndCheckIntervalMs)
                    return
                }
                Log.w(TAG, "watchAd: abnormal/trading page detected (scene=$scene, elapsed=${elapsedMs}ms), exiting immediately")
                debugLog("watchAd: abnormal page trap detected, pressing back to exit")
                service.setAdMode(false)
                service.pressBack()
                currentTaskIndex++  // 陷阱任务直接跳过，不重玩（避免再次掉入同一陷阱）
                handler.postDelayed({
                    if (state == AutomationState.WATCHING_AD) {
                        if (!service.isOnFarmPage()) service.pressBack()
                        handler.postDelayed({
                            if (state == AutomationState.WATCHING_AD) {
                                moveTo(AutomationState.OPENING_TASK_LIST)
                                handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                            }
                        }, INTERVAL_PAGE_LOAD_MS)
                    }
                }, INTERVAL_PAGE_LOAD_MS)
                return
            }
            // 陷阱3：非农场小程序（支付宝/淘宝特有，广告诱导跳转）
            // 策略：立即按返回退出，跳过任务（UC 无小程序，此场景不会触发）
            // 注意：用 currentTaskIndex++（而非 advanceTaskIndex）— 陷阱任务直接跳过，不重玩
            PageScene.TRAP_MINIPROGRAM -> {
                Log.w(TAG, "watchAd: mini-program trap detected (scene=$scene), exiting immediately")
                debugLog("watchAd: mini-program trap (non-farm mini-program), pressing back to exit")
                service.setAdMode(false)
                service.pressBack()
                currentTaskIndex++  // 陷阱任务直接跳过，不重玩（避免再次掉入同一陷阱）
                handler.postDelayed({
                    if (state == AutomationState.WATCHING_AD) {
                        if (!service.isOnFarmPage()) service.pressBack()
                        handler.postDelayed({
                            if (state == AutomationState.WATCHING_AD) {
                                moveTo(AutomationState.OPENING_TASK_LIST)
                                handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                            }
                        }, INTERVAL_PAGE_LOAD_MS)
                    }
                }, INTERVAL_PAGE_LOAD_MS)
                return
            }
            // 陷阱4：广告主落地页（含多个"立即下载/查看详情/去购买"等转化按钮）
            // 策略：误入即按返回退出，绝不点击任何按钮（避免转化收益），跳过任务
            // 注意：用 currentTaskIndex++（而非 advanceTaskIndex）— 陷阱任务直接跳过，不重玩
            PageScene.TRAP_LANDING -> {
                Log.w(TAG, "watchAd: ad landing page detected (scene=$scene), exiting immediately (trap defense)")
                debugLog("watchAd: ad landing page trap detected, pressing back to exit")
                service.setAdMode(false)
                service.pressBack()
                currentTaskIndex++  // 陷阱任务直接跳过，不重玩（避免再次掉入同一陷阱）
                handler.postDelayed({
                    if (state == AutomationState.WATCHING_AD) {
                        if (!service.isOnFarmPage()) service.pressBack()
                        handler.postDelayed({
                            if (state == AutomationState.WATCHING_AD) {
                                moveTo(AutomationState.OPENING_TASK_LIST)
                                handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                            }
                        }, INTERVAL_PAGE_LOAD_MS)
                    }
                }, INTERVAL_PAGE_LOAD_MS)
                return
            }
            // 陷阱5：诱导弹窗（页面上有"立即下载"等按钮，可能是广告播放中弹出的诱导遮罩）
            // 策略：优先点"关闭/暂不/拒绝"关闭弹窗，绝不点诱导按钮，继续轮询
            PageScene.TRAP_INSTALL -> {
                Log.w(TAG, "watchAd: install popup detected (scene=$scene), trying to close it (trap defense)")
                debugLog("watchAd: install popup trap detected, attempting closeAdInstallPopup")
                val closed = service.closeAdInstallPopup()
                if (!closed) {
                    // 找不到关闭类按钮，可能弹窗本身就是全屏落地页 → 按返回退出
                    debugLog("watchAd: no close button for install popup, pressing back")
                    service.pressBack()
                }
                handler.postDelayed({
                    if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                }, INTERVAL_CLICK_MS)
                return
            }
            // 陷阱6：广告复看陷阱（"再看一个"/"加倍领取"诱导继续看广告）
            // 策略：优先点关闭类按钮，绝不点诱导按钮，避免被套娃看更多广告，继续轮询
            PageScene.TRAP_REPLAY -> {
                Log.w(TAG, "watchAd: ad replay trap detected (scene=$scene), closing (再看一个/加倍领取)")
                debugLog("watchAd: replay trap handled, continuing to check ad end")
                service.closeAdReplayTrap()
                handler.postDelayed({
                    if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                }, INTERVAL_CLICK_MS)
                return
            }
            // 通用弹窗（无肥料提示，需主动关闭）
            // 用户需求：弹框窗口如何没有肥料提示，需要关闭弹窗
            // 策略：优先点关闭按钮（×/关闭/知道了/确定等），否则按返回键，继续轮询等待广告恢复
            PageScene.GENERIC_POPUP -> {
                Log.i(TAG, "watchAd: generic popup detected (no fertilizer hint), closing it")
                debugLog("watchAd: generic popup (no fertilizer), attempting to close")
                val closeBtn = service.findAdCloseButton()
                if (closeBtn != null) {
                    debugLog("watchAd: clicking close button on generic popup (text='${closeBtn.text}')")
                    service.performClickSafe(closeBtn)
                } else {
                    debugLog("watchAd: no close button found for generic popup, pressing back")
                    service.pressBack()
                }
                handler.postDelayed({
                    if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                }, INTERVAL_CLICK_MS)
                return
            }
            // 非陷阱场景（FARM_PAGE / AD_PLAYING / AD_ENDED / REWARD_POPUP / UNKNOWN），
            // 继续后续流程检测（超时、深链、最短等待、广告结束等）
            else -> {
                // 场景识别未命中陷阱，由后续超时/深链/广告结束检测处理
            }
        }

        // 超时强制关闭
        if (elapsedMs >= adMaxDurationMs) {
            Log.w(TAG, "watchAd: timeout (${elapsedMs}ms/${adMaxDurationMs}ms), force closing")
            moveTo(AutomationState.CLOSING_AD)
            handler.postDelayed({ runClosingAd(strategy = 0) }, INTERVAL_CLICK_MS)
            return
        }

        // 深链跳转任务：检测是否已回到芭芭农场App（任务完成返回）
        // 深链任务跳转到其他App执行后，回到农场App表示任务完成
        // 要求 elapsedMs >= 5s 避免广告刚打开时短暂显示农场的误判
        if (elapsedMs >= 5000L && service.isOnFarmPage()) {
            Log.i(TAG, "watchAd: returned to farm app (${elapsedMs}ms), task complete")
            debugLog("watchAd: returned to farm, deep-link task complete")
            // 取消可能已调度的深链 kill（若曾进入其他 App 又自然返回）
            deepLinkAppPkg = null
            service.setAdMode(false)
            collectedCount++
            advanceTaskIndex()  // 多次任务重玩同一任务，否则前进到下一个
            handler.postDelayed({
                if (state == AutomationState.WATCHING_AD) {
                    moveTo(AutomationState.OPENING_TASK_LIST)
                    handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                }
            }, INTERVAL_CLICK_MS)
            return
        }

        // 深链跳转任务：广告任务跳转到其他 App（如快手，非农场/非广告Activity/非异常页）
        // 用户要求：等其它app打开后等2秒，把主界面激活到前台，同时kill掉打开的app
        // 注意：此检查在"最短等待时间"检查之前，确保深链任务用 2s 超时（而非默认 30s 广告等待）
        // 注：异常交易页（isOnAbnormalPage）已在上方场景识别 TRAP_ABNORMAL 中统一处理
        if (elapsedMs >= 5000L && !service.isOnFarmPage() && !service.isAdActivity() &&
            !service.isAdPlaying() && !service.isOnAbnormalPage()) {
            val currentPkg = service.getCurrentWindowPackage()
            if (currentPkg != null) {
                // 首次检测到深链跳转：记录包名，调度 2 秒后"激活主界面 + kill 被拉起的 App"
                if (deepLinkAppPkg == null) {
                    deepLinkAppPkg = currentPkg
                    deepLinkEnterTimeMs = elapsedMs
                    Log.i(TAG, "watchAd: entered deep-linked app '$currentPkg', will activate farm + kill in ${DEEP_LINK_MAX_DURATION_MS}ms")
                    debugLog("watchAd: deep-linked app '$currentPkg' detected, scheduling activate+kill in ${DEEP_LINK_MAX_DURATION_MS}ms")
                    val killedPkg = currentPkg
                    handler.postDelayed({
                        if (state != AutomationState.WATCHING_AD) return@postDelayed
                        // 若已自然回到农场页（任务完成），取消 kill
                        if (deepLinkAppPkg == null) {
                            debugLog("watchAd: deep-link app already returned, cancel scheduled kill")
                            return@postDelayed
                        }
                        Log.w(TAG, "watchAd: ${DEEP_LINK_MAX_DURATION_MS}ms elapsed, activating farm to foreground and killing '$killedPkg'")
                        debugLog("watchAd: activating farm to foreground + killing '$killedPkg' simultaneously")
                        service.setAdMode(false)
                        // 1. 激活农场 App 到前台（同时把被拉起的 App 推到后台）
                        if (watchingAdPlatform != Platform.UNKNOWN) {
                            debugLog("watchAd: launching farm platform $watchingAdPlatform to foreground")
                            service.launchPlatformApp(watchingAdPlatform)
                        }
                        // 2. 同时 kill 掉被拉起的 App（跳过返回键，避免误伤已激活的农场 App）
                        service.forceKillApp(killedPkg, pressBackFirst = false)
                        deepLinkAppPkg = null
                        currentTaskIndex++
                        handler.postDelayed({
                            if (state == AutomationState.WATCHING_AD) {
                                moveTo(AutomationState.OPENING_TASK_LIST)
                                handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                            }
                        }, INTERVAL_PAGE_LOAD_MS)
                    }, DEEP_LINK_MAX_DURATION_MS)
                }
                // 已调度 kill，继续轮询兜底（若任务自然完成返回农场，上方"returned to farm"分支会取消 kill）
                debugLog("watchAd: in deep-linked app '$currentPkg', kill scheduled in ${DEEP_LINK_MAX_DURATION_MS}ms, polling as fallback")
                handler.postDelayed({
                    if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                }, adEndCheckIntervalMs)
                return
            }
        }

        // 最短等待时间未到，继续等待
        // 用户要求：太快退出可能获取不到肥料，必须等够页面提示的规定时间+缓冲
        if (elapsedMs < adMinDurationMs) {
            // 诊断日志：每 15 秒记录一次广告页面状态，帮助定位"广告是否真的在播放"
            if (elapsedMs % 15000L < adEndCheckIntervalMs) {
                val adPageType = service.getPageType()
                val adActivity = service.isAdActivity()
                val adPlaying = service.isAdPlaying()
                val adContent = service.isAdContentShown()
                val adTexts = service.collectAllTextSnapshot(maxCount = 8)
                debugLog("watchAd: waiting ${elapsedMs}ms/${adMinDurationMs}ms (min), pageType=$adPageType, adActivity=$adActivity, adPlaying=$adPlaying, adContent=$adContent, texts=$adTexts")
            }
            Log.d(TAG, "watchAd: waiting (${elapsedMs}ms/${adMinDurationMs}ms)")
            handler.postDelayed({
                if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
            }, adEndCheckIntervalMs)
            return
        }

        // 最短等待时间已过，检测广告是否结束
        // 广告结束的标志：不再在广告Activity，或出现领取奖励按钮，或任务完成
        // 诊断日志：记录广告结束检测时的页面状态
        val adEndedPageType = service.getPageType()
        val adEndedActivity = service.isAdActivity()
        val adEndedPlaying = service.isAdPlaying()
        val adEndedContent = service.isAdContentShown()
        val adEndedTexts = service.collectAllTextSnapshot(maxCount = 8)
        debugLog("watchAd: checking ad end (${elapsedMs}ms/${adMaxDurationMs}ms), pageType=$adEndedPageType, adActivity=$adEndedActivity, adPlaying=$adEndedPlaying, adContent=$adEndedContent, texts=$adEndedTexts")

        // 多信号融合广告结束检测（优化方案）
        // 融合：任务完成页 + 广告Activity切换 + 领取奖励按钮 + 倒计时消失
        // 相比单一信号检测，准确率更高，能更早发现广告结束
        val adEnded = service.isAdEndedMultiSignal(prevAdHadCountdown)
        // 更新上一轮倒计时状态（供下一轮多信号检测用）
        prevAdHadCountdown = service.findAdDurationHint() > 0

        if (adEnded && service.isTaskCompletePage()) {
            Log.i(TAG, "watchAd: task complete page detected (multi-signal), exiting")
            debugLog("watchAd: task complete (multi-signal), exiting via close/back icon")
            // 优先点右上角关闭按钮（游戏/广告退出，含平台特有关闭文本）
            val closeBtn = service.findAdCloseButton(service.currentPlatformConfig().adCloseButtonTexts)
            val backIcon = service.findBackIcon()
            when {
                closeBtn != null -> { debugLog("watchAd: clicking close icon"); service.performClickSafe(closeBtn) }
                backIcon != null -> { debugLog("watchAd: clicking back icon"); service.performClickSafe(backIcon) }
                else -> { debugLog("watchAd: pressing back"); service.pressBack() }
            }
            service.setAdMode(false)
            collectedCount++
            advanceTaskIndex()  // 多次任务重玩同一任务，否则前进到下一个
            handler.postDelayed({
                if (!service.isOnFarmPage()) service.pressBack()
                handler.postDelayed({
                    moveTo(AutomationState.OPENING_TASK_LIST)
                    handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                }, INTERVAL_CLICK_MS)
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        if (adEnded && !service.isAdActivity() && !service.isAdPlaying()) {
            // 广告结束后检查是否进入了交易页面
            if (service.isOnAbnormalPage()) {
                Log.i(TAG, "watchAd: abnormal/trading page after ad, exiting immediately")
                debugLog("watchAd: abnormal page after ad, pressing back")
                service.pressBack()
                service.setAdMode(false)
                currentTaskIndex++
                handler.postDelayed({
                    if (!service.isOnFarmPage()) service.pressBack()
                    handler.postDelayed({
                        moveTo(AutomationState.OPENING_TASK_LIST)
                        handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                    }, INTERVAL_CLICK_MS)
                }, INTERVAL_PAGE_LOAD_MS)
                return
            }

            // 广告结束后优先检测领取奖励按钮（节点查找，免费精确）
            // 用户要求：非滑动广告页出现肥料图标后，也需等"已完成"等标志再退出
            // 因此点击领取按钮后不立即退出，继续轮询等待 isTaskCompletePage() 检测到"已完成"标志
            val claimBtn = service.findClaimRewardButton()
            if (claimBtn != null) {
                Log.i(TAG, "watchAd: claim button found after ad finished, clicking and waiting for '已完成' marker")
                debugLog("watchAd: clicking claim reward button, will wait for '已完成' marker before exiting")
                service.performClickSafe(claimBtn)
                // 不立即退出：继续轮询，由 isTaskCompletePage() 检测到"全部完成/已完成"后退出
                handler.postDelayed({
                    if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                }, adEndCheckIntervalMs)
                return
            }

            Log.i(TAG, "watchAd: ad finished (${elapsedMs}ms, multi-signal), closing")
            moveTo(AutomationState.CLOSING_AD)
            handler.postDelayed({ runClosingAd(strategy = 0) }, INTERVAL_CLICK_MS)
            return
        }

        // 多信号检测到广告结束（倒计时消失等弱信号），但仍在广告 Activity
        // 尝试找领取奖励按钮，找到则点击，否则进入关闭流程
        if (adEnded) {
            val claimBtn = service.findClaimRewardButton()
            if (claimBtn != null) {
                Log.i(TAG, "watchAd: multi-signal ad ended, claim button found, clicking")
                debugLog("watchAd: multi-signal ad ended (countdown disappeared), clicking claim button")
                service.performClickSafe(claimBtn)
                handler.postDelayed({
                    if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                }, adEndCheckIntervalMs)
                return
            }
            // 倒计时消失但无领取按钮，进入关闭流程
            Log.i(TAG, "watchAd: multi-signal ad ended (no claim button), closing")
            debugLog("watchAd: multi-signal ad ended, no claim button, entering CLOSING_AD")
            moveTo(AutomationState.CLOSING_AD)
            handler.postDelayed({ runClosingAd(strategy = 0) }, INTERVAL_CLICK_MS)
            return
        }

        // 检测是否有领取奖励按钮（广告仍在播放时出现）
        // 用户要求：出现肥料图标后也需等"已完成"等标志再退出，因此不立即关闭，继续等待
        val claimButton = service.findClaimRewardButton()
        if (claimButton != null) {
            Log.i(TAG, "watchAd: claim button found while ad playing, waiting for '已完成' marker")
            debugLog("watchAd: claim button found but waiting for '已完成' marker before exiting")
            handler.postDelayed({
                if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
            }, adEndCheckIntervalMs)
            return
        }

        // 继续等待
        Log.d(TAG, "watchAd: still playing (${elapsedMs}ms/${adMaxDurationMs}ms)")

        // build529（用户要求"全部实现"）：AI 视觉识别环形进度条（节流到 20s 一次）
        // 用途：广告页面有时无可读倒计时文本，截图交 GLM-4.6V-Flash 识别进度环填充比例，
        // 输出 percent/seconds_remaining 到日志，便于诊断"广告还要等多久"。
        // 不主动触发提前退出（AI 判断存在误差），仅作信息补充；退出仍由上方既有条件负责。
        if (elapsedMs >= adMinDurationMs &&
            state == AutomationState.WATCHING_AD &&
            System.currentTimeMillis() - lastAiProgressCheckMs >= AI_PROGRESS_CHECK_INTERVAL_MS) {
            lastAiProgressCheckMs = System.currentTimeMillis()
            val appContext = service.applicationContext
            val snapshotElapsed = elapsedMs
            Thread {
                val bitmap = service.takeScreenshotBitmap()
                if (bitmap == null) {
                    Log.w(TAG, "watchAd: AI progress screenshot unavailable")
                    return@Thread
                }
                try {
                    val sceneCtx = "ad watching (elapsed=${snapshotElapsed}ms, " +
                        "min=${adMinDurationMs}ms, max=${adMaxDurationMs}ms)"
                    val result = AiVisionClient.recognizeProgressFromScreenshot(appContext, bitmap, sceneCtx)
                    bitmap.recycle()
                    if (result == null) {
                        Log.w(TAG, "watchAd: AI progress returned null")
                    } else {
                        Log.i(TAG, "watchAd: AI progress percent=${result.percent}%, " +
                            "secondsRemaining=${result.secondsRemaining}s, " +
                            "hasBar=${result.hasProgressBar}, reason='${result.reason.take(80)}'")
                        debugLog("watchAd: AI progress percent=${result.percent}%, " +
                            "remaining=${result.secondsRemaining}s, hasBar=${result.hasProgressBar}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "watchAd: AI progress exception: ${e.message}", e)
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }.start()
        }

        handler.postDelayed({
            if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
        }, adEndCheckIntervalMs)
    }

    // ============== 阶段5: 关闭广告（多策略） ==============

    /**
     * 关闭广告阶段（多策略，基于v11 PC端ADB方案经验）
     * - 策略0：查找并点击"×"/"关闭"按钮节点
     * - 策略1：尝试坐标候选位置（右上角多个位置）
     * - 策略2：查找并点击"放弃奖励离开"对话框按钮
     * - 策略3：按返回键
     * - 策略4：查找并点击"领取奖励"按钮
     * - 每个策略尝试后检测是否成功关闭
     */
    private fun runClosingAd(strategy: Int) {
        if (state != AutomationState.CLOSING_AD) return
        val service = getService() ?: run { stop(); return }

        // 诊断日志：记录关闭广告时的页面状态，帮助定位"为什么关不掉"
        if (strategy == 0) {
            val closePageType = service.getPageType()
            val closeTexts = service.collectAllTextSnapshot(maxCount = 10)
            debugLog("closeAd: start closing, pageType=$closePageType, texts=$closeTexts")
        }
        Log.i(TAG, "closeAd: trying strategy #$strategy")

        // 陷阱防护：策略0 之前先检测是否误入广告主落地页
        // 落地页的"×"位置可能不同于广告关闭按钮，使用专门的 closeAdLandingPage（已内置诱导黑名单过滤）
        if (strategy == 0 && service.isAdLandingPage()) {
            Log.w(TAG, "closeAd: ad landing page detected during closing, using closeAdLandingPage")
            debugLog("closeAd: landing page trap detected, using specialized close")
            service.closeAdLandingPage()
            handler.postDelayed({
                if (state == AutomationState.CLOSING_AD) checkAdClosed(service, 0)
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 陷阱防护：策略0 之前先检测诱导弹窗（立即下载弹窗）
        // 若存在诱导弹窗，优先用 closeAdInstallPopup 关闭弹窗，再尝试常规关闭
        if (strategy == 0 && service.findAdInstallButton() != null) {
            Log.w(TAG, "closeAd: install popup detected during closing, trying closeAdInstallPopup first")
            debugLog("closeAd: install popup trap detected, attempting closeAdInstallPopup")
            val closed = service.closeAdInstallPopup()
            handler.postDelayed({
                if (state == AutomationState.CLOSING_AD) {
                    // 弹窗关闭成功 → 继续常规策略0；失败 → 跳过策略0直接走坐标策略
                    if (closed) checkAdClosed(service, 0) else runClosingAd(1)
                }
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        when (strategy) {
            0 -> {
                // 策略0：查找并点击"×"/"关闭"按钮节点（优先平台特有关闭文本）
                val platformCloseTexts = service.currentPlatformConfig().adCloseButtonTexts
                val closeBtn = service.findAdCloseButton(platformCloseTexts)
                if (closeBtn != null) {
                    // 虚假关闭按钮检测：尺寸过大或位置居中的"关闭"可能是诱导跳转
                    if (service.isFakeCloseButton(closeBtn)) {
                        Log.w(TAG, "closeAd: fake close button detected (size/position suspicious), skipping strategy 0")
                        debugLog("closeAd: fake close button (too large or centered), trying next strategy")
                        runClosingAd(1)
                        return
                    }
                    Log.i(TAG, "closeAd: found close button, clicking")
                    service.performClickSafe(closeBtn)
                } else {
                    // 未找到关闭按钮节点，进入下一策略
                    debugLog("closeAd: close button node not found, trying next strategy")
                    runClosingAd(1)
                    return
                }
            }
            1 -> {
                // 策略1：尝试坐标候选位置
                val adCloseList = adCloseCandidates(service)
                for ((index, candidate) in adCloseList.withIndex()) {
                    val (xRatio, yRatio) = candidate
                    Log.d(TAG, "closeAd: trying coordinate #$index ($xRatio, $yRatio)")
                    clickAtRatio(service, xRatio, yRatio, "ad-close-$index")
                }
            }
            2 -> {
                // 策略2：查找并点击"放弃奖励离开"对话框按钮
                val abandonBtn = service.findAbandonRewardButton()
                if (abandonBtn != null) {
                    Log.i(TAG, "closeAd: found abandon reward button, clicking")
                    service.performClickSafe(abandonBtn)
                } else {
                    runClosingAd(3)
                    return
                }
            }
            3 -> {
                // 策略3：按返回键
                Log.i(TAG, "closeAd: pressing back")
                service.pressBack()
            }
            4 -> {
                // 策略4：查找并点击"领取奖励"按钮
                val claimBtn = service.findClaimRewardButton()
                if (claimBtn != null) {
                    Log.i(TAG, "closeAd: found claim button, clicking")
                    service.performClickSafe(claimBtn)
                }
            }
            else -> {
                // 所有策略都失败，清除广告标志，进入返回阶段
                Log.w(TAG, "closeAd: all strategies failed, clearing ad mode")
                service.setAdMode(false)
                moveTo(AutomationState.RETURNING)
                handler.postDelayed({ runReturning(attempt = 0) }, INTERVAL_CLICK_MS)
                return
            }
        }

        // 检测是否成功关闭广告
        handler.postDelayed({
            if (state == AutomationState.CLOSING_AD) checkAdClosed(service, strategy)
        }, INTERVAL_PAGE_LOAD_MS)
    }

    /** 检查广告是否已关闭 */
    private fun checkAdClosed(service: FarmAccessibilityService, lastStrategy: Int) {
        if (state != AutomationState.CLOSING_AD) return

        // 优先检测"肥料已发放"提示页：广告结束后弹出的奖励到账提示
        // 出现此提示说明广告已结束、肥料已到账，直接回芭芭农场主页
        if (service.isFertilizerGrantedPage()) {
            Log.i(TAG, "closeAd: fertilizer granted page detected, ad finished")
            service.setAdMode(false)
            collectedCount++
            Log.i(TAG, "=== FERTILIZER COLLECTED! (total: $collectedCount) ===")
            moveTo(AutomationState.RETURNING)
            handler.postDelayed({ runReturning(attempt = 0) }, INTERVAL_CLICK_MS)
            return
        }

        // 广告已关闭
        if (!service.isAdActivity() && !service.isAdPlaying()) {
            Log.i(TAG, "closeAd: ad closed successfully (strategy #$lastStrategy)")
            service.setAdMode(false)
            // 肥料收集成功
            collectedCount++
            Log.i(TAG, "=== FERTILIZER COLLECTED! (total: $collectedCount) ===")
            moveTo(AutomationState.RETURNING)
            handler.postDelayed({ runReturning(attempt = 0) }, INTERVAL_CLICK_MS)
            return
        }

        // 广告仍在，尝试下一个策略
        val nextStrategy = lastStrategy + 1
        if (nextStrategy <= 4) {
            Log.d(TAG, "closeAd: ad still playing, trying strategy #$nextStrategy")
            runClosingAd(nextStrategy)
        } else {
            // 所有策略都失败，清除广告标志，进入返回阶段
            Log.w(TAG, "closeAd: all strategies failed, clearing ad mode")
            service.setAdMode(false)
            moveTo(AutomationState.RETURNING)
            handler.postDelayed({ runReturning(attempt = 0) }, INTERVAL_CLICK_MS)
        }
    }

    // ============== 阶段6: 从广告返回任务列表 ==============

    /**
     * 返回阶段：从广告返回任务列表
     * - 用户要求：智能地适应退回按钮位置，不要只硬编码一个位置
     * - 交替使用系统返回键和多个候选退回按钮位置
     * - 回到任务列表后继续下一个任务
     */
    private fun runReturning(attempt: Int) {
        if (state != AutomationState.RETURNING) return
        val service = getService() ?: run { stop(); return }

        if (attempt == 0) {
            logPageSnapshot(service, "return-start")
            // 优先用 deep link 重开农场主页（等同从桌面快捷方式进入），替代按返回键逐步退回
            // 成功后进入 NAVIGATING 等待页面加载，再重新走 COLLECTING_DIRECT → OPENING_TASK_LIST
            if (service.reopenFarmByDeepLink()) {
                Log.i(TAG, "return: reopened farm by deep link, switching to NAVIGATING")
                moveTo(AutomationState.NAVIGATING)
                handler.postDelayed({ runNavigating(0) }, INTERVAL_PAGE_LOAD_MS)
                return
            }
            // 无 deep link 或重开失败，走原有按返回键逐步退回逻辑
            Log.i(TAG, "return: no deep link, fallback to back-key return")
        }

        // 检测异常页面（交易页面等），按返回退出
        if (service.isOnAbnormalPage()) {
            debugLog("return: abnormal/trading page detected, pressing back")
            service.pressBack()
            handler.postDelayed({
                if (state == AutomationState.RETURNING) runReturning(attempt + 1)
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 已回到农场页，检查是否在任务列表
        if (service.isOnFarmPage()) {
            // 查找任务按钮，确认是否在任务列表
            val buttons = service.findGoCompleteButtons()
            if (buttons.isNotEmpty()) {
                Log.i(TAG, "return: back on task list with ${buttons.size} tasks, next task")
                taskButtons = buttons
                advanceTaskIndex()  // 多次任务重玩同一任务，否则前进到下一个
                noProgressRounds = 0
                moveTo(AutomationState.PROCESSING_TASK)
                handler.postDelayed({ runProcessingTask(0) }, INTERVAL_CLICK_MS)
                return
            }

            // 在农场页但不在任务列表，需要重新打开任务列表
            Log.i(TAG, "return: on farm page but not task list, opening task list")
            moveTo(AutomationState.OPENING_TASK_LIST)
            handler.postDelayed({ runOpeningTaskList(0) }, INTERVAL_CLICK_MS)
            return
        }

        // 尝试次数超限
        if (attempt >= MAX_RETURN_ATTEMPTS) {
            Log.w(TAG, "return: failed after $attempt attempts, re-navigating")
            moveTo(AutomationState.NAVIGATING)
            handler.postDelayed({ runNavigating(0) }, INTERVAL_CLICK_MS)
            return
        }

        // 智能选择退回方式：交替使用系统返回键和候选位置
        val backList = backButtonCandidates(service)
        val candidateIndex = attempt % (backList.size + 1)
        if (candidateIndex == backList.size) {
            Log.i(TAG, "return: pressing system back (attempt ${attempt + 1})")
            service.pressBack()
        } else {
            val (xRatio, yRatio) = backList[candidateIndex]
            Log.i(TAG, "return: clicking back button #$candidateIndex (attempt ${attempt + 1})")
            clickAtRatio(service, xRatio, yRatio, "back-$candidateIndex")
        }

        handler.postDelayed({
            if (state == AutomationState.RETURNING) runReturning(attempt + 1)
        }, INTERVAL_PAGE_LOAD_MS)
    }

    // ============== 阶段7: 施肥 ==============

    /**
     * 施肥阶段：所有肥料收集完后，点击施肥按钮
     *
     * 流程：
     * 1. 先关闭任务列表（按返回键或点击关闭，回到农场主页）
     * 2. 在农场主页找"施肥"按钮并点击
     * 3. 重复点击施肥，直到没有肥料可施或达到最大次数
     *
     * 用户要求："先获取完所有的肥料后，再来施肥"
     */
    private fun runFertilizing(clickCount: Int) {
        if (state != AutomationState.FERTILIZING) return
        val service = getService() ?: run { stop(); return }

        if (clickCount == 0) {
            logPageSnapshot(service, "fertilize-start")
        }

        // 检测异常页面（交易页面等），按返回退出并重新导航
        if (service.isOnAbnormalPage()) {
            debugLog("fertilize: abnormal/trading page detected, pressing back and re-navigating")
            service.pressBack()
            moveTo(AutomationState.NAVIGATING)
            handler.postDelayed({ runNavigating(0) }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 防止无限点击
        if (clickCount >= MAX_FERTILIZE_CLICKS) {
            Log.i(TAG, "fertilize: reached max clicks ($MAX_FERTILIZE_CLICKS), done")
            moveTo(AutomationState.WAITING)
            handler.postDelayed({ startNextRound() }, INTERVAL_WAIT_MS)
            return
        }

        // 第一步：关闭任务列表回到农场主页（仅第一次）
        if (clickCount == 0) {
            Log.i(TAG, "fertilize: closing task list, returning to farm page")
            service.pressBack()
            handler.postDelayed({
                if (state == AutomationState.FERTILIZING) runFertilizing(clickCount + 1)
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 查找并点击"施肥"按钮
        val fertilizeButton = service.findFertilizeButton()
        debugLog("fertilize: findFertilizeButton=${fertilizeButton != null}, clickCount=$clickCount")
        if (fertilizeButton != null) {
            Log.i(TAG, "fertilize: found 施肥 button, clicking (count=${clickCount + 1})")
            service.performClickSafe(fertilizeButton)
            handler.postDelayed({
                if (state == AutomationState.FERTILIZING) {
                    // 检查是否还有肥料可施
                    val stillHasButton = service.findFertilizeButton()
                    if (stillHasButton != null) {
                        Log.d(TAG, "fertilize: still has fertilize button, continue")
                        runFertilizing(clickCount + 1)
                    } else {
                        Log.i(TAG, "fertilize: no more fertilizer button, done")
                        moveTo(AutomationState.WAITING)
                        handler.postDelayed({ startNextRound() }, INTERVAL_WAIT_MS)
                    }
                }
            }, INTERVAL_CLICK_MS)
            return
        }

        // 没找到施肥按钮，可能需要滚动或已施肥完毕
        Log.d(TAG, "fertilize: no 施肥 button found (clickCount=$clickCount)")
        if (clickCount <= 2) {
            // 刚开始就没找到，可能是页面还没加载好，重试
            handler.postDelayed({
                if (state == AutomationState.FERTILIZING) runFertilizing(clickCount + 1)
            }, INTERVAL_CLICK_MS)
        } else {
            // 多次找不到，认为施肥完成
            Log.i(TAG, "fertilize: done (no button after $clickCount checks)")
            moveTo(AutomationState.WAITING)
            handler.postDelayed({ startNextRound() }, INTERVAL_WAIT_MS)
        }
    }

    /** 开始下一轮（集肥料→施肥循环） */
    private fun startNextRound() {
        if (state != AutomationState.WAITING) return
        Log.i(TAG, "=== Starting new round ===")
        collectedCount = 0
        currentTaskIndex = 0
        noProgressRounds = 0
        taskButtons = emptyList()
        moveTo(AutomationState.NAVIGATING)
        handler.post { runNavigating(0) }
    }

    // ============== 跨平台切换 ==============

    /**
     * 检测跨平台任务的目标平台
     *
     * 任务文本包含"去淘宝"/"切换淘宝"→ 淘宝
     * 任务文本包含"去支付宝"/"切换支付宝"→ 支付宝
     * 任务文本包含"去UC"/"切换UC"→ UC
     *
     * @param text 任务按钮文本
     * @return 目标平台，null 表示不是跨平台任务
     */
    private fun detectCrossPlatformTarget(text: String): Platform? {
        val lower = text.lowercase()
        return when {
            text.contains("淘宝") -> Platform.TAOBAO
            text.contains("支付宝") || text.contains("蚂蚁庄园") -> Platform.ALIPAY
            text.contains("UC") || lower.contains("ucmobile") || text.contains("uc极速") -> Platform.UC
            else -> null
        }
    }

    /**
     * 跨平台切换阶段
     *
     * 用户需求：肥料获取可能从支付宝/淘宝芭芭农场之间切换，切换动作也可以获取肥料，
     * 但切换完了应该回到原来 app。
     *
     * 流程：
     * 1. LAUNCH_TARGET: 启动目标平台 app，等待加载
     * 2. NAVIGATE_TARGET_FARM: 导航到目标平台芭芭农场，等待加载
     * 3. FERTILIZE_TARGET: 在目标平台点击施肥/集肥料按钮获取切换奖励
     * 4. RETURN_ORIGINAL: 返回原平台 app，等待加载
     * 5. RESUME_ORIGINAL_FARM: 导航回原平台芭芭农场，恢复任务列表
     */
    private fun runSwitchingPlatform() {
        if (state != AutomationState.SWITCHING_PLATFORM) return
        val service = getService() ?: run { stop(); return }

        logPageSnapshot(service, "switchPlatform-$switchStage")
        debugLog("switchPlatform: stage=$switchStage, retry=$switchRetryCount, target=$switchTargetPlatform, original=$switchOriginalPlatform")

        when (switchStage) {
            "LAUNCH_TARGET" -> {
                // 首次进入时，检查点击"去完成"是否已自动跳转到目标平台
                service.refreshPlatform()
                val currentPkg = service.getCurrentWindowPackage() ?: ""
                val targetPkg = switchTargetPlatform.config.packageNames.firstOrNull() ?: ""
                if (currentPkg == targetPkg || service.currentPlatform == switchTargetPlatform) {
                    debugLog("switchPlatform: target ${switchTargetPlatform} already loaded (auto-jump), navigating to farm")
                    switchStage = "NAVIGATE_TARGET_FARM"
                    switchRetryCount = 0
                    service.cancelNavigation()
                    service.navigateToFarm()
                    handler.postDelayed({ runSwitchingPlatform() }, INTERVAL_PAGE_LOAD_MS * 2)
                    return
                }
                // 未自动跳转，主动启动目标平台
                if (switchRetryCount == 0) {
                    debugLog("switchPlatform: launching target ${switchTargetPlatform} manually")
                    service.launchPlatformApp(switchTargetPlatform)
                }
                switchRetryCount++
                if (switchRetryCount >= MAX_SWITCH_RETRIES) {
                    debugLog("switchPlatform: failed to launch target, skipping task")
                    currentTaskIndex++
                    moveTo(AutomationState.PROCESSING_TASK)
                    handler.postDelayed({ runProcessingTask(0) }, INTERVAL_CLICK_MS)
                    return
                }
                handler.postDelayed({ runSwitchingPlatform() }, 2000L)
            }

            "NAVIGATE_TARGET_FARM" -> {
                // 等待目标平台芭芭农场加载
                if (service.isOnFarmPage()) {
                    debugLog("switchPlatform: target farm page loaded, fertilizing")
                    switchStage = "FERTILIZE_TARGET"
                    switchRetryCount = 0
                    handler.postDelayed({ runSwitchingPlatform() }, INTERVAL_CLICK_MS)
                    return
                }
                switchRetryCount++
                if (switchRetryCount >= MAX_SWITCH_RETRIES) {
                    debugLog("switchPlatform: target farm not loaded, returning to original")
                    switchStage = "RETURN_ORIGINAL"
                    switchRetryCount = 0
                    service.launchPlatformApp(switchOriginalPlatform)
                    handler.postDelayed({ runSwitchingPlatform() }, INTERVAL_PAGE_LOAD_MS)
                    return
                }
                handler.postDelayed({ runSwitchingPlatform() }, 2000L)
            }

            "FERTILIZE_TARGET" -> {
                // 在目标平台点击施肥/集肥料按钮获取切换奖励
                // 使用目标平台的 collectFertilizerCoords 候选坐标
                val coords = switchTargetPlatform.config.collectFertilizerCoords
                debugLog("switchPlatform: fertilizing on ${switchTargetPlatform}, ${coords.size} coord candidates")
                for ((xRatio, yRatio) in coords) {
                    clickAtRatio(service, xRatio, yRatio, "switchPlatform-fertilize")
                }
                // 等待施肥/领取完成
                switchStage = "RETURN_ORIGINAL"
                switchRetryCount = 0
                handler.postDelayed({ runSwitchingPlatform() }, INTERVAL_PAGE_LOAD_MS)
            }

            "RETURN_ORIGINAL" -> {
                // 启动原平台
                if (switchRetryCount == 0) {
                    debugLog("switchPlatform: returning to original ${switchOriginalPlatform}")
                    service.launchPlatformApp(switchOriginalPlatform)
                }
                service.refreshPlatform()
                val currentPkg = service.getCurrentWindowPackage() ?: ""
                val originalPkg = switchOriginalPlatform.config.packageNames.firstOrNull() ?: ""
                if (currentPkg == originalPkg || service.currentPlatform == switchOriginalPlatform) {
                    debugLog("switchPlatform: original platform loaded, resuming farm navigation")
                    switchStage = "RESUME_ORIGINAL_FARM"
                    switchRetryCount = 0
                    service.cancelNavigation()
                    service.navigateToFarm()
                    handler.postDelayed({ runSwitchingPlatform() }, INTERVAL_PAGE_LOAD_MS * 2)
                    return
                }
                switchRetryCount++
                if (switchRetryCount >= MAX_SWITCH_RETRIES) {
                    debugLog("switchPlatform: failed to return to original, skipping task")
                    currentTaskIndex++
                    moveTo(AutomationState.PROCESSING_TASK)
                    handler.postDelayed({ runProcessingTask(0) }, INTERVAL_CLICK_MS)
                    return
                }
                handler.postDelayed({ runSwitchingPlatform() }, 2000L)
            }

            "RESUME_ORIGINAL_FARM" -> {
                // 等待原平台芭芭农场加载
                if (service.isOnFarmPage()) {
                    debugLog("switchPlatform: original farm page loaded, resuming task list")
                    // 跨平台切换任务完成，继续下一个任务
                    currentTaskIndex++
                    moveTo(AutomationState.PROCESSING_TASK)
                    handler.postDelayed({ runProcessingTask(0) }, INTERVAL_CLICK_MS)
                    return
                }
                switchRetryCount++
                if (switchRetryCount >= MAX_SWITCH_RETRIES) {
                    debugLog("switchPlatform: original farm not loaded, re-navigating from start")
                    moveTo(AutomationState.NAVIGATING)
                    handler.postDelayed({ runNavigating(0) }, INTERVAL_CLICK_MS)
                    return
                }
                handler.postDelayed({ runSwitchingPlatform() }, 2000L)
            }

            else -> {
                debugLog("switchPlatform: unknown stage $switchStage, skipping task")
                currentTaskIndex++
                moveTo(AutomationState.PROCESSING_TASK)
                handler.postDelayed({ runProcessingTask(0) }, INTERVAL_CLICK_MS)
            }
        }
    }

    // ============== 通用工具 ==============

    /** 按坐标比例点击屏幕（基于屏幕宽高百分比） */
    private fun clickAtRatio(
        service: FarmAccessibilityService,
        xRatio: Float,
        yRatio: Float,
        label: String
    ) {
        val metrics = service.resources.displayMetrics
        val x = metrics.widthPixels * xRatio
        val y = metrics.heightPixels * yRatio
        Log.i(TAG, "$label: click at ($x, $y) screen=${metrics.widthPixels}x${metrics.heightPixels}")
        debugLog("$label: click at ($x, $y), ratio=($xRatio, $yRatio), screen=${metrics.widthPixels}x${metrics.heightPixels}")
        service.dispatchGestureClick(x, y)
    }
}
