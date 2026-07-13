package com.bbncbot.automation

import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.bbncbot.service.FarmAccessibilityService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 录制模式管理器
 *
 * 工作模式：
 * - **关闭**（默认）：bot 自动执行场景规则，不询问用户
 * - **开启**：bot 暂停自动执行，用户的每个操作（滑动/返回/点击）被记录成规则
 *
 * 录制原理：
 * - 开启录制后，[FarmAccessibilityService.onAccessibilityEvent] 调用 [onUserGesture]
 * - 用户的每次操作前，先提取当前场景特征（操作前的页面状态）
 * - 操作类型通过 AccessibilityEvent.type 识别：
 *   - TYPE_VIEW_CLICKED → CLICK_BUTTON（找被点击节点的文本）
 *   - TYPE_VIEW_SCROLLED → SWIPE_UP/SWIPE_DOWN（根据 scrollDeltaY 方向）
 *   - TYPE_VIEW_TEXT_CHANGED → 忽略
 * - 用户按返回键（pressBack）由 service 拦截
 *
 * **任务流程闭环**：每次取得肥料 = 一次任务 = 一个规则（session）
 * - [start] 时创建 [SceneLibrary.RecordingSession]，记录初始肥料基线
 * - 录制期间每个操作按顺序记录为 [SceneLibrary.SceneCategory]（stepIndex=0,1,2,...）
 * - 录制中肥料监控每 4s 检测肥料数值，**检测到增量即自动结束 session 并保存为规则**
 *   （"以取得肥料为任务完成标志"——取得肥料 = 任务完成 = 操作集合已记录为规则）
 * - [stop] 时按"是否取得肥料"决定保存/丢弃：
 *   - 取得肥料（自动触发或手动停录且有增量）→ 保存，按"平台+任务描述"重命名
 *   - 未取得肥料 → 丢弃 session（任务未完成，不保留无效规则）
 * - bot 执行时若场景匹配 session 第一步，进入"按流程执行"模式，优先匹配下一步
 *
 * 录制完成后关闭录制，bot 恢复自动执行，遇到相同场景按录制规则操作。
 *
 * 中断机制：
 * - 浮窗常驻"中断"按钮
 * - 用户点击 → [interrupt] → 立即停止 AutomationController + 删除当前 session 的所有规则
 *   （表示"这个流程不对"）
 */
object RecordingManager {

    private const val TAG = "RecordingManager"

    /** 录制模式开关 */
    @Volatile
    var recording: Boolean = false
        private set

    /** 录制的规则数（本次会话） */
    @Volatile
    var recordedCount: Int = 0
        private set

    /** 当前录制会话 ID（录制中非空，停止后为空） */
    @Volatile
    private var currentSessionId: String? = null

    /** 本次录制开始时的肥料数值（-1 表示未能读取，无法判断是否获得肥料） */
    @Volatile
    private var initialFertilizer: Int = -1

    /** 录制日志（详细记录每次操作，用于事后分析） */
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** 停录后排队的残余事件计数器（合并打日志，避免刷屏） */
    private val pendingSkipCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** 0x800 (WINDOW_CONTENT_CHANGED) 事件节流：dedupKey -> last click time ms */
    private val lastClickSigTime = HashMap<String, Long>()

    /**
     * 录制中肥料监控周期（毫秒）
     *
     * 每 [FERT_MONITOR_INTERVAL_MS] 检查一次当前肥料数值，若比 [initialFertilizer] 增加，
     * 自动结束当前 session 并保存为规则（"以取得肥料为任务完成标志"）。
     *
     * 4 秒间隔平衡：
     * - 太短（<2s）：频繁截图+OCR/无障碍遍历，影响录制流畅度
     * - 太长（>8s）：肥料增加后用户可能已开始下一个任务，检测到时上下文已乱
     */
    private const val FERT_MONITOR_INTERVAL_MS = 4000L

    /** 肥料监控定时器（录制期间运行，检测到肥料增加自动结束 session） */
    @Volatile
    private var fertMonitor: java.util.concurrent.ScheduledFuture<*>? = null

    /** 肥料监控使用的调度器（单线程，录制开始时启动，结束时关闭） */
    private val fertExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "RecordingManager-fertMonitor").apply { isDaemon = true }
    }

    /**
     * 最近一次监控到的肥料数值（用于增量检测，避免重复触发自动结束）
     *
     * 与 [initialFertilizer] 区别：
     * - [initialFertilizer]：录制开始时的基线，整个 session 不变
     * - [lastMonitoredFertilizer]：监控过程中最新读到的值，用于检测"比上次又增加了"
     */
    @Volatile
    private var lastMonitoredFertilizer: Int = -1

    /** 当录制状态变化时通知浮窗更新 UI */
    @Volatile
    var onRecordingChanged: ((Boolean) -> Unit)? = null

    /**
     * 录制停止后的结果通知（由 FloatingWindowService 设置，用于显示 Toast）
     *
     * 参数：
     * - saved：true 表示肥料增加，已保存为规则；false 表示无变化，已丢弃
     * - initial：开始时的肥料数值（-1 表示读取失败）
     * - finalAmount：结束时的肥料数值（-1 表示读取失败）
     * - stepCount：本次录制的步骤数
     */
    @Volatile
    var onRecordingStopped: ((saved: Boolean, initial: Int, finalAmount: Int, stepCount: Int) -> Unit)? = null

    /** 开启录制 */
    fun start() {
        if (recording) return
        try {
            recording = true
            recordedCount = 0
            // 创建新录制会话（任务流程闭环）
            // 临时命名，session 保存时会用"平台+任务描述"重命名（保存时才能确定任务描述）
            val sessionName = "流程${System.currentTimeMillis() % 10000}"
            val sess = SceneLibrary.startSession(sessionName)
            currentSessionId = sess.id
            // 记录录制开始时的肥料数值（用于停录时对比 + 录制中自动检测增量）——后台线程读取
            recordExecutor.execute {
                initialFertilizer = readFertilizerAmountWithDiag("start")
                lastMonitoredFertilizer = initialFertilizer
                Log.i(TAG, "initialFertilizer=$initialFertilizer (async)")
            }
            Log.i(TAG, "=== 录制开始 session=${sess.id} name='$sessionName' ===")
            logToRecordingFile("=== 录制开始 session=${sess.id} name='$sessionName' ===")
            // 暂停自动化（避免 bot 干扰用户操作）+ 重置执行上下文
            if (AutomationController.isRunning) {
                Log.i(TAG, "pausing automation for recording")
                AutomationController.stop()
            }
            SceneLibrary.resetSessionContext()
            // 启动肥料监控：检测到肥料增加 → 自动结束 session 并保存为规则
            // "以取得肥料为任务完成标志"：用户无需手动停录，取得肥料即任务完成
            startFertilizerMonitor()
        } catch (e: Exception) {
            Log.e(TAG, "start recording failed: ${e.message}", e)
            recording = false
        }
        onRecordingChanged?.invoke(recording)
    }

    /** 停止录制 */
    fun stop() {
        if (!recording) return
        recording = false
        // 捕获自动结束标志（stopFertilizerMonitor 会重置它）
        val autoStop = autoStopByFertilizer
        // 停止肥料监控定时任务（避免停录后 monitor 仍在跑）
        stopFertilizerMonitor()
        val sessId = currentSessionId
        val initial = initialFertilizer
        val steps = recordedCount
        // 诊断日志：recordedCount 在主线程的读取值（排查 steps=0 问题）
        Log.i(TAG, "stop: recordedCount=$steps thread=${Thread.currentThread().name} autoStop=$autoStop sessId=$sessId")
        logToRecordingFile("STOP_ENTER recordedCount=$steps thread=${Thread.currentThread().name} autoStop=$autoStop sessId=$sessId")
        currentSessionId = null
        initialFertilizer = -1
        // UI 立即响应（按钮变回"录制"）
        onRecordingChanged?.invoke(false)

        // 肥料读取 + 对比 + 保存/丢弃都在后台线程，避免主线程阻塞
        recordExecutor.execute {
            try {
                val finalFertilizer = readFertilizerAmountWithDiag("stop")
                val gained = if (initial >= 0 && finalFertilizer >= 0) finalFertilizer - initial else null

                // 保存条件（优先级从高到低）：
                // 1. autoStop=true → 肥料监控确认增量，必须保存
                // 2. gained > 0 → 手动停录且肥料有增量，保存
                // 3. gained == null 且 steps > 0 → 肥料读取失败（H5 Canvas/OCR 不可用），
                //    回退保存：不因读取机制不可靠而丢弃用户的有效录制
                // 4. gained == 0 且 steps > 0 → 确实没取得肥料，丢弃
                // 5. steps == 0 → 没录制任何操作，丢弃
                val shouldSave = when {
                    autoStop -> true
                    gained != null && gained > 0 -> true
                    gained == null && steps > 0 -> true  // 肥料读取失败回退
                    else -> false
                }

                if (sessId != null) {
                    if (shouldSave && steps > 0) {
                        // 保存为规则，按"平台+任务描述"重命名 session
                        val (platform, fertTask) = SceneLibrary.getSessionFirstStepInfo(sessId)
                        val newName = buildSessionName(platform, fertTask)
                        SceneLibrary.renameSession(sessId, newName)
                        SceneLibrary.endSession(sessId, steps)
                        val saveReason = when {
                            autoStop -> "自动结束(取得肥料)"
                            gained != null && gained > 0 -> "手动结束(取得肥料 +$gained)"
                            else -> "肥料读取失败回退保存(initial=$initial final=$finalFertilizer)"
                        }
                        Log.i(TAG, "=== 录制结束 session=$sessId → '$newName' $saveReason steps=$steps ===")
                        logToRecordingFile("=== 录制结束 session=$sessId → '$newName' SAVE reason=$saveReason steps=$steps ===")
                        onRecordingStopped?.invoke(true, initial, finalFertilizer, steps)
                    } else {
                        // 丢弃 session
                        SceneLibrary.deleteSession(sessId)
                        val reason = when {
                            steps == 0 -> "未录制任何操作(steps=0)"
                            gained == 0 -> "肥料无变化($initial → $finalFertilizer)，任务未完成"
                            else -> "肥料减少($initial → $finalFertilizer)，任务未完成"
                        }
                        Log.w(TAG, "=== 录制结束 session=$sessId $reason，丢弃 ===")
                        logToRecordingFile("=== 录制结束 session=$sessId DISCARD reason=$reason steps=$steps autoStop=$autoStop ===")
                        onRecordingStopped?.invoke(false, initial, finalFertilizer, steps)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "stop recording background task failed: ${e.message}", e)
                logToRecordingFile("ERROR stop failed: ${e.message}")
            } finally {
                // 上传日志到 GitHub（若用户在 MainActivity 配置了 Token）
                // 放在 finally 里确保无论保存/丢弃都上传，方便排查问题
                // 不论成功失败都写日志，便于在 recording.log 中看到上传结果
                try {
                    val ctx = FarmAccessibilityService.getInstance()
                    if (ctx == null) {
                        logToRecordingFile("LOG_UPLOAD_SKIP reason=service_null (无障碍服务未连接)")
                    } else {
                        val shortId = sessId?.takeLast(6) ?: "nosess"
                        val n = LogUploader.upload(ctx, "sess_$shortId")
                        // LogUploader.lastResult 包含失败原因（401/403/404/timeout 等）
                        logToRecordingFile("LOG_UPLOAD_RESULT n=$n tag=sess_$shortId detail=${LogUploader.lastResult}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "upload logs failed: ${e.message}")
                    logToRecordingFile("LOG_UPLOAD_EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    /** 切换录制状态 */
    fun toggle() {
        if (recording) stop() else start()
    }

    /**
     * 启动肥料监控定时任务
     *
     * 录制期间每 [FERT_MONITOR_INTERVAL_MS] 读取一次当前肥料数值：
     * - 比基线 [initialFertilizer] 增加 → 判定为"取得肥料"，自动结束 session 并保存为规则
     * - 未增加 → 继续监控，更新 [lastMonitoredFertilizer]
     *
     * 自动结束语义：用户选择"以取得肥料为任务完成标志"。
     * 取得肥料 = 一次任务完成 = 这个任务的所有操作集合已记录为一个规则（session）。
     */
    private fun startFertilizerMonitor() {
        stopFertilizerMonitor()
        fertMonitor = fertExecutor.scheduleWithFixedDelay({
            try {
                if (!recording) return@scheduleWithFixedDelay
                val initial = initialFertilizer
                if (initial < 0) {
                    // 基线还没读到，跳过本轮（start 时的异步读取可能还没完成）
                    return@scheduleWithFixedDelay
                }
                val current = readFertilizerAmountWithDiag("monitor")
                if (current < 0) {
                    // 读不到（可能不在农场主页），跳过本轮
                    return@scheduleWithFixedDelay
                }
                lastMonitoredFertilizer = current
                if (current > initial) {
                    val gained = current - initial
                    Log.i(TAG, "=== 肥料监控检测到增量: $initial → $current (+$gained)，自动结束 session ===")
                    logToRecordingFile("=== FERT_MONITOR_TRIGGERED initial=$initial current=$current gained=$gained → 自动结束 session ===")
                    // 自动保存并停止录制（autoSave=true 表示这是取得肥料的自动结束，必须保存）
                    autoStopByFertilizer = true
                    stop()
                }
            } catch (e: Exception) {
                Log.w(TAG, "fertMonitor tick failed: ${e.message}")
            }
        }, FERT_MONITOR_INTERVAL_MS, FERT_MONITOR_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        Log.i(TAG, "肥料监控已启动 (间隔 ${FERT_MONITOR_INTERVAL_MS}ms)")
    }

    /** 停止肥料监控定时任务 */
    private fun stopFertilizerMonitor() {
        fertMonitor?.let {
            it.cancel(false)
            fertMonitor = null
        }
        autoStopByFertilizer = false
    }

    /**
     * 标记：当前 stop() 是由肥料监控自动触发的（取得肥料自动结束）
     *
     * - true：检测到肥料增加，必须保存 session（这是有效任务）
     * - false：用户手动停录，按"是否取得肥料"决定保存/丢弃
     */
    @Volatile
    private var autoStopByFertilizer: Boolean = false

    /**
     * 构建 session 名称："平台+任务描述"格式
     *
     * 用户要求：每次取得肥料是一次任务，按"平台+任务描述"命名这个任务的操作集合。
     *
     * 示例：
     * - platform="UC" fertTask="浏览15秒得300肥料" → "UC-浏览15秒得300肥料"
     * - platform="ALIPAY" fertTask="逛好物最高得1500肥料" → "支付宝-逛好物最高得1500肥料"
     * - platform="" fertTask="" → "未知-肥料任务"（兜底）
     *
     * @param platform 平台标识（"UC" / "ALIPAY" / "TAOBAO" / "UNKNOWN" / ""）
     * @param fertTask 肥料任务描述（从 [SceneFeatures.fertilizerTaskDesc] 提取）
     * @return "平台-任务描述" 格式的 session 名称
     */
    private fun buildSessionName(platform: String, fertTask: String): String {
        val p = when (platform) {
            "UC" -> "UC"
            "ALIPAY" -> "支付宝"
            "TAOBAO" -> "淘宝"
            "UNKNOWN", "" -> "未知"
            else -> platform
        }
        val task = if (fertTask.isNotEmpty()) fertTask else "肥料任务"
        return "$p-$task"
    }

    /**
     * 读取当前肥料数值（在主线程外调用，因为 findFertilizeButton 会遍历节点树）
     *
     * @return 肥料数值；-1 表示读取失败（不在农场主页/找不到施肥按钮/解析失败）
     */
    private fun readFertilizerAmount(): Int {
        val service = FarmAccessibilityService.getInstance() ?: run {
            Log.w(TAG, "readFertilizerAmount failed: service null")
            return -1
        }
        return try {
            service.findCurrentFertilizerAmount()
        } catch (e: Exception) {
            Log.w(TAG, "readFertilizerAmount failed: ${e.message}")
            -1
        }
    }

    /**
     * 读取当前肥料数值并附带详细诊断信息（失败时记录原因，便于定位）
     *
     * 读取链路（逐级降级，任一成功即返回）：
     * 1. 无障碍节点树提取（[FarmAccessibilityService.findCurrentFertilizerAmountWithReason]）
     *    - 自身/父节点/子树/整页遍历正则匹配"肥料XXXX"
     * 2. OCR 截图识别兜底（[com.bbncbot.ocr.OcrProvider]）
     *    - H5 农场页施肥按钮是图标/Canvas、数字单独渲染，无障碍树无文本节点时使用
     *    - ML Kit 中文 OCR 识别全屏文本，按"肥料+数字"格式或施肥行数字提取
     *    - noOcr flavor 下 OcrProvider 返回 -1（调试包不带 OCR 模型，仅记录日志）
     *
     * @param tag 上下文标记："start" 或 "stop"，用于日志区分
     * @return 肥料数值；-1 表示读取失败
     */
    private fun readFertilizerAmountWithDiag(tag: String): Int {
        val service = FarmAccessibilityService.getInstance()
        if (service == null) {
            logToRecordingFile("[$tag] FERTILIZER_READ_FAIL reason=service_null (无障碍服务未连接)")
            return -1
        }
        return try {
            // 1. 无障碍节点树提取
            val (amount, reason) = service.findCurrentFertilizerAmountWithReason()
            if (amount >= 0) {
                logToRecordingFile("[$tag] FERTILIZER_READ_OK amount=$amount (source=accessibility)")
                return amount
            }
            logToRecordingFile("[$tag] FERTILIZER_ACCESSIBILITY_FAIL reason=$reason → 尝试 OCR 兜底")

            // 2. OCR 截图识别兜底（H5 页无障碍树读不到文本时）
            //    OcrProvider 由 flavor 注入：noOcr 返回 -1，full 走 ML Kit
            //    诊断：检查 OCR APK 是否安装（noOcr flavor 依赖独立 OCR APK）
            val ocrApkInstalled = try {
                service.packageManager.getPackageInfo("com.bbncbot.ocr", 0)
                true
            } catch (_: Exception) {
                false
            }
            val ocrAmount = com.bbncbot.ocr.OcrProvider.findCurrentFertilizerAmount(service)
            if (ocrAmount >= 0) {
                logToRecordingFile("[$tag] FERTILIZER_READ_OK amount=$ocrAmount (source=ocr)")
                return ocrAmount
            }
            logToRecordingFile("[$tag] FERTILIZER_READ_FAIL reason=ocr_fail ocrApkInstalled=$ocrApkInstalled ocrError='${com.bbncbot.ocr.OcrProvider.lastError}' (无障碍和OCR均未识别到肥料总数)")
            -1
        } catch (e: Exception) {
            logToRecordingFile("[$tag] FERTILIZER_READ_FAIL reason=exception:${e.message}")
            -1
        }
    }

    /**
     * 后台执行器：特征提取（节点遍历）+ 规则匹配/记录（文件 IO + 锁）都在此线程完成，
     * 不阻塞主线程——否则录制时每个无障碍事件都占主线程，悬浮窗按钮（如"停录"）无法响应。
     */
    private val recordExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "RecordingManager-worker").apply { isDaemon = true }
    }

    /**
     * 处理用户手势事件（由 FarmAccessibilityService.onAccessibilityEvent 调用）
     *
     * 只在录制模式下生效，识别用户操作类型并记录规则。
     *
     * 线程策略：主线程仅读取事件原始字段（廉价），重活丢到 [recordExecutor]，
     * 保证主线程不被阻塞、悬浮窗按钮可即时响应。
     *
     * 流程：
     * 1. 提取操作前的场景特征
     * 2. 识别操作类型（点击/滑动）
     * 3. 调用 [SceneLibrary.match] 查询匹配结果
     *    - [SceneLibrary.MatchResult.Matched] → 已有 category（含 coreSignature 自动归类），强化规则
     *    - [SceneLibrary.MatchResult.Unmapped] → 全新场景，**自动命名 + 创建 category**（不弹窗）
     *    - [SceneLibrary.MatchResult.Defaulted] / [SceneLibrary.MatchResult.None] → 命中默认规则，跳过
     *
     * @param event 无障碍事件
     * @param service 无障碍服务实例
     */
    fun onUserGesture(event: AccessibilityEvent, service: FarmAccessibilityService) {
        if (!recording) return

        // 过滤掉本应用（悬浮窗按钮）的事件，避免误录自己的录制/停录/中断按钮
        val eventPkg = event.packageName?.toString() ?: ""
        if (eventPkg == "com.bbncbot") {
            return
        }

        // 主线程：仅读取事件原始字段（廉价），不访问节点树/文件
        val eventType = event.eventType
        val eventText: String? = event.text?.joinToString(" ")?.trim()
        val classNameStr: String = event.className?.toString()?.trim() ?: ""
        val source = event.source
        val nodeText: String? = source?.text?.toString()?.trim()
        val desc: String? = source?.contentDescription?.toString()?.trim()
        val deltaY = event.scrollDeltaY
        val stateName = AutomationController.currentState.name

        // 主线程捕获 sessionId 副本，避免 stop() 在事件处理前置空 sessionId 导致丢失事件
        val sessIdAtEnqueue = currentSessionId

        // 后台线程：特征提取（节点遍历）+ 规则匹配/记录（文件 IO + 锁）
        recordExecutor.execute {
            try {
                // 停录后排队的残余事件直接跳过（合并打一行汇总，避免刷屏）
                if (!recording && sessIdAtEnqueue == null) {
                    pendingSkipCount.incrementAndGet()
                    return@execute
                }
                // 若有累积的残余事件，先 flush 一行汇总日志
                val skipped = pendingSkipCount.getAndSet(0)
                if (skipped > 0) {
                    logToRecordingFile("SKIP残余事件 x$skipped (recording=false)")
                }
                val features = SceneFeatureExtractor.extract(service, stateName, taskButton = source)
                // H5 WebView 上点击触发的事件类型：
                // - TYPE_VIEW_CLICKED(0x1) 原生点击
                // - TYPE_WINDOW_STATE_CHANGED(0x20) 页面跳转
                // - TYPE_WINDOW_CONTENT_CHANGED(0x800) WebView 内容变化（H5 点击主要触发这个）
                // 支付宝/淘宝芭芭农场都是 H5 页面，普通按钮点击主要触发 0x800。
                val isClickLikeEvent = eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                    eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
                    eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                    eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                when {
                    eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                        val action = if (deltaY > 0) SceneLibrary.Action.SWIPE_UP else SceneLibrary.Action.SWIPE_DOWN
                        Log.i(TAG, "录制滑动: deltaY=$deltaY action=$action sig=${features.signature()}")
                        logToRecordingFile("SWIPE deltaY=$deltaY action=$action sig='${features.signature()}'")
                        handleGesture(features, action, null, "滑动 $action", sessIdAtEnqueue)
                    }
                    isClickLikeEvent -> {
                        // 从事件 source 提取目标按钮信息（text/desc），用于规则匹配和命名
                        val text = eventText ?: classNameStr
                        val rawTarget = when {
                            !nodeText.isNullOrEmpty() -> nodeText
                            !desc.isNullOrEmpty() -> desc
                            text.isNotEmpty() -> text
                            else -> null
                        }
                        // 0x800 (WINDOW_CONTENT_CHANGED) 噪音多（每次内容变化都触发），
                        // 用 signature + targetButton 做节流：同一场景同一目标 1.5 秒内只录一次
                        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                            // 无可识别 target 的 0x800 直接丢弃（噪音）
                            if (rawTarget == null) {
                                return@execute
                            }
                            val now = System.currentTimeMillis()
                            val sig = features.signature()
                            val dedupKey = "$sig|$rawTarget"
                            val lastTime = lastClickSigTime[dedupKey] ?: 0L
                            if (now - lastTime < 1500L) {
                                // 1.5 秒内重复，静默丢弃
                                return@execute
                            }
                            lastClickSigTime[dedupKey] = now
                        }
                        // 不过滤操作：录制期间记录用户所有操作，由"取得肥料才保存"机制保证规则有效性
                        // （未取得肥料的录制会在 stop() 时自动丢弃，无需在此预过滤）
                        // 保留原始按钮文本作为 target，便于回放时按文本匹配按钮
                        val targetButton = rawTarget
                        // WINDOW_STATE_CHANGED 无 source 时，跳过（避免误把页面跳转记成点击）
                        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                            nodeText.isNullOrEmpty() && desc.isNullOrEmpty() && eventText.isNullOrEmpty()) {
                            logToRecordingFile("SKIP_WINDOW_STATE type=0x${eventType.toString(16)} class=$classNameStr (无文本，仅页面切换)")
                            return@execute
                        }
                        Log.i(TAG, "录制点击(H5): type=0x${eventType.toString(16)} target='$targetButton' sig=${features.signature()}")
                        logToRecordingFile("CLICK_H5 type=0x${eventType.toString(16)} target='$targetButton' sig='${features.signature()}' btns=[${features.clickableButtons.joinToString(",")}]")
                        handleGesture(features, SceneLibrary.Action.CLICK_BUTTON, targetButton, "点击 ${targetButton ?: "按钮"}", sessIdAtEnqueue)
                    }
                    else -> {
                        // 其他事件类型忽略，但记录到日志便于后续诊断
                        logToRecordingFile("IGNORE type=0x${eventType.toString(16)} pkg=${event.packageName} class=$classNameStr")
                    }
                }
            } catch (e: Exception) {
                // 防止异常被 Executors 默认 handler 静默吞掉，导致录制无声失败
                Log.e(TAG, "onUserGesture background task failed: ${e.message}", e)
                logToRecordingFile("ERROR onUserGesture failed: ${e.message}")
            }
        }
    }

    /**
     * 统一处理手势录制逻辑（点击/滑动共用）
     *
     * 录制模式下，所有规则都归属当前 session（任务流程闭环）：
     * - Matched → 已有 category，强化规则（若同 session 同 stepIndex 已存在，跳过重复录制）
     * - Unmapped → 全新场景，自动命名 + 创建 category（带 sessionId + stepIndex）
     * - Defaulted/None → 命中默认规则，跳过录制
     *
     * stepIndex 取 recordedCount（本次会话已录制规则数），保证同一 session 内步骤序号连续。
     */
    private fun handleGesture(
        features: SceneFeatures,
        action: SceneLibrary.Action,
        targetButton: String?,
        actionDesc: String,
        sessId: String?
    ) {
        if (sessId == null) {
            logToRecordingFile("SKIP handleGesture: sessionId=null (录制已停止)")
            return // 录制已停止（残余事件）
        }
        val matchResult = SceneLibrary.match(features)
        when (matchResult) {
            is SceneLibrary.MatchResult.Matched -> {
                // 已有 category（含 coreSignature 自动归类），强化规则
                Log.i(TAG, "已有 category '${matchResult.category.name}'，强化规则")
                logToRecordingFile("MATCHED name='${matchResult.category.name}' action=$action")
                SceneLibrary.recordRule(features, action, targetButton)
                recordedCount++
                logToRecordingFile("RECORD_INC matched recordedCount=$recordedCount thread=${Thread.currentThread().name}")
            }
            is SceneLibrary.MatchResult.Unmapped -> {
                // 全新场景，自动命名 + 创建（归属当前 session，stepIndex = recordedCount）
                val name = SceneLibrary.autoName(features, action, targetButton)
                SceneLibrary.createCategory(
                    features, name, action, targetButton,
                    sessionId = sessId,
                    stepIndex = recordedCount
                )
                recordedCount++
                Log.i(TAG, "自动创建场景: name='$name' action=$action session=$sessId step=${recordedCount - 1}")
                logToRecordingFile("AUTO_CREATE name='$name' action=$action session=$sessId step=${recordedCount - 1} sig='${features.signature()}'")
                logToRecordingFile("RECORD_INC unmapped recordedCount=$recordedCount thread=${Thread.currentThread().name}")
                // 通知 UI 显示 Toast
                onSceneAutoCreated?.invoke(name)
            }
            is SceneLibrary.MatchResult.Defaulted, SceneLibrary.MatchResult.None -> {
                // 录制模式：默认规则不应吞掉用户的点击，仍创建新规则（不带 popup=* 的通配才真正跳过）
                // 仅对 popup=red_packet 且用户点的就是"关闭"这种通用按钮跳过
                val sig = features.signature()
                val isGenericCloseButton = targetButton == "关闭" && sig.contains("popup=red_packet")
                if (isGenericCloseButton) {
                    Log.d(TAG, "命中默认规则且为通用关闭按钮，跳过录制: actionDesc=$actionDesc")
                    logToRecordingFile("SKIP_DEFAULTED action=$action actionDesc=$actionDesc sig='$sig' (命中默认规则，不录制)")
                } else {
                    // 当作全新场景创建规则
                    val name = SceneLibrary.autoName(features, action, targetButton)
                    SceneLibrary.createCategory(
                        features, name, action, targetButton,
                        sessionId = sessId,
                        stepIndex = recordedCount
                    )
                    recordedCount++
                    Log.i(TAG, "自动创建场景(默认规则跳过录制): name='$name' action=$action session=$sessId step=${recordedCount - 1}")
                    logToRecordingFile("AUTO_CREATE name='$name' action=$action session=$sessId step=${recordedCount - 1} sig='$sig'")
                    onSceneAutoCreated?.invoke(name)
                }
            }
        }
    }

    /**
     * 自动创建场景后的回调（由 FloatingWindowService 设置，用于显示 Toast 提示用户）
     *
     * 参数：自动生成的场景名
     */
    @Volatile
    var onSceneAutoCreated: ((name: String) -> Unit)? = null

    /**
     * 中断当前 bot 动作（用户点击浮窗"中断"按钮时调用）
     *
     * - 立即停止 AutomationController
     * - 删除当前场景所属规则（若归属 session，删除整个 session，表示"这个流程不对"）
     * - 重置执行上下文（不再按 session 流程执行）
     * - 如果在录制中，也停止录制
     */
    fun interrupt() {
        Log.i(TAG, "=== 用户中断 ===")
        logToRecordingFile("=== 用户中断 ===")

        // 停止肥料监控（中断表示"这个流程不对"，不应再因肥料增量自动保存）
        stopFertilizerMonitor()

        // 获取当前场景特征
        val service = FarmAccessibilityService.getInstance()
        if (service != null) {
            val features = SceneFeatureExtractor.extract(service, AutomationController.currentState.name)
            Log.i(TAG, "中断场景: sig=${features.signature()}")
            // 删除当前场景的规则（若归属 session，删除整个 session）
            SceneLibrary.removeRule(features)
        } else {
            // 没有场景信息时也重置执行上下文
            SceneLibrary.resetSessionContext()
        }

        // 停止自动化
        AutomationController.stop()

        // 停止录制（如果在录制）— stop 会调用 endSession，但中断应删除 session
        val sessId = currentSessionId
        if (recording) {
            stop()
            // 录制中中断 → 删除未完成的 session（表示"这个流程不对"）
            // stop() 的后台逻辑可能因肥料增量保存 session，这里确保中断时 session 被删除
            if (sessId != null) {
                SceneLibrary.deleteSession(sessId)
                Log.i(TAG, "录制中中断：删除未完成 session=$sessId")
            }
        }
    }

    private fun logToRecordingFile(line: String) {
        try {
            val time = dateFormat.format(Date())
            val file = java.io.File(
                android.os.Environment.getExternalStorageDirectory(),
                "Android/data/com.bbncbot/files/recording.log"
            )
            file.parentFile?.mkdirs()
            file.appendText("$time $line\n")
        } catch (e: Exception) {
            Log.w(TAG, "logToRecordingFile failed: ${e.message}")
        }
    }

    /**
     * 清除旧录制日志，并在新日志开头写入版本号
     *
     * 用途：app 启动时调用，确保 [recording.log] 是当前版本产生的，
     * 避免旧版本日志混在新版本日志里导致误判。
     */
    fun clearLogOnAppStart(context: android.content.Context) {
        try {
            val file = java.io.File(
                android.os.Environment.getExternalStorageDirectory(),
                "Android/data/com.bbncbot/files/recording.log"
            )
            file.parentFile?.mkdirs()
            // 读取版本号
            val pm = context.packageManager
            val versionName = try {
                pm.getPackageInfo(context.packageName, 0).versionName ?: "?"
            } catch (e: Exception) {
                "?"
            }
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                try {
                    pm.getPackageInfo(context.packageName, 0).longVersionCode.toString()
                } catch (e: Exception) { "?" }
            } else {
                @Suppress("DEPRECATION")
                try { pm.getPackageInfo(context.packageName, 0).versionCode.toString() } catch (e: Exception) { "?" }
            }
            // 清空并写入版本标识
            file.writeText("=== recording.log cleared on app start (version=$versionName/$versionCode, time=${dateFormat.format(Date())}) ===\n")
            Log.i(TAG, "recording.log cleared, version=$versionName/$versionCode")
        } catch (e: Exception) {
            Log.w(TAG, "clearLogOnAppStart failed: ${e.message}")
        }
    }
}
