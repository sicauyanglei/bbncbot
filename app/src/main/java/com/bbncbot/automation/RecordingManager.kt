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
 * **任务流程闭环**：每次录制 = 一个完整任务流程
 * - [start] 时创建 [SceneLibrary.RecordingSession]
 * - 录制期间每个规则按顺序记录 [SceneLibrary.SceneCategory.stepIndex]（0,1,2,...）
 * - [stop] 时标记 session 完成
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

    /** 录制日志（详细记录每次操作，用于事后分析） */
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** 当录制状态变化时通知浮窗更新 UI */
    @Volatile
    var onRecordingChanged: ((Boolean) -> Unit)? = null

    /** 开启录制 */
    fun start() {
        if (recording) return
        recording = true
        recordedCount = 0
        // 创建新录制会话（任务流程闭环）
        val sessionName = "流程${System.currentTimeMillis() % 10000}"
        val sess = SceneLibrary.startSession(sessionName)
        currentSessionId = sess.id
        Log.i(TAG, "=== 录制开始 session=${sess.id} name='$sessionName' ===")
        logToRecordingFile("=== 录制开始 session=${sess.id} name='$sessionName' ===")
        // 暂停自动化（避免 bot 干扰用户操作）+ 重置执行上下文
        if (AutomationController.isRunning) {
            Log.i(TAG, "pausing automation for recording")
            AutomationController.stop()
        }
        SceneLibrary.resetSessionContext()
        onRecordingChanged?.invoke(true)
    }

    /** 停止录制 */
    fun stop() {
        if (!recording) return
        recording = false
        val sessId = currentSessionId
        if (sessId != null) {
            SceneLibrary.endSession(sessId, recordedCount)
        }
        Log.i(TAG, "=== 录制结束 session=$sessId 本次录制 $recordedCount 条规则 ===")
        logToRecordingFile("=== 录制结束 session=$sessId 本次录制 $recordedCount 条规则 ===")
        currentSessionId = null
        onRecordingChanged?.invoke(false)
    }

    /** 切换录制状态 */
    fun toggle() {
        if (recording) stop() else start()
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

        // 主线程：仅读取事件原始字段（廉价），不访问节点树/文件
        val eventType = event.eventType
        val eventText: String? = event.text?.joinToString(" ")?.trim()
        val classNameStr: String = event.className?.toString()?.trim() ?: ""
        val source = event.source
        val nodeText: String? = source?.text?.toString()?.trim()
        val desc: String? = source?.contentDescription?.toString()?.trim()
        val deltaY = event.scrollDeltaY
        val stateName = AutomationController.currentState.name

        // 后台线程：特征提取（节点遍历）+ 规则匹配/记录（文件 IO + 锁）
        recordExecutor.execute {
            // 停录后排队的残余事件直接跳过，快速排空队列
            if (!recording) return@execute
            val features = SceneFeatureExtractor.extract(service, stateName)
            when (eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    val text = eventText ?: classNameStr
                    val targetButton = when {
                        !nodeText.isNullOrEmpty() -> nodeText
                        !desc.isNullOrEmpty() -> desc
                        text.isNotEmpty() -> text
                        else -> null
                    }
                    Log.i(TAG, "录制点击: target='$targetButton' sig=${features.signature()}")
                    logToRecordingFile("CLICK target='$targetButton' sig='${features.signature()}' btns=[${features.clickableButtons.joinToString(",")}]")
                    handleGesture(features, SceneLibrary.Action.CLICK_BUTTON, targetButton, "点击 ${targetButton ?: "按钮"}")
                }
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                    val action = if (deltaY > 0) SceneLibrary.Action.SWIPE_UP else SceneLibrary.Action.SWIPE_DOWN
                    Log.i(TAG, "录制滑动: deltaY=$deltaY action=$action sig=${features.signature()}")
                    logToRecordingFile("SWIPE deltaY=$deltaY action=$action sig='${features.signature()}'")
                    handleGesture(features, action, null, "滑动 $action")
                }
                else -> {
                    // 忽略其他事件
                }
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
        actionDesc: String
    ) {
        val sessId = currentSessionId ?: return // 录制已停止（残余事件）
        val matchResult = SceneLibrary.match(features)
        when (matchResult) {
            is SceneLibrary.MatchResult.Matched -> {
                // 已有 category（含 coreSignature 自动归类），强化规则
                Log.i(TAG, "已有 category '${matchResult.category.name}'，强化规则")
                SceneLibrary.recordRule(features, action, targetButton)
                recordedCount++
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
                // 通知 UI 显示 Toast
                onSceneAutoCreated?.invoke(name)
            }
            is SceneLibrary.MatchResult.Defaulted, SceneLibrary.MatchResult.None -> {
                // 命中默认规则，不需要录制
                Log.d(TAG, "命中默认规则，跳过录制: actionDesc=$actionDesc")
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
}
