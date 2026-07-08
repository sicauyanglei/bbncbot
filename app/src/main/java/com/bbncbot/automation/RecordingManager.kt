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
 * 录制完成后关闭录制，bot 恢复自动执行，遇到相同场景按录制规则操作。
 *
 * 中断机制：
 * - 浮窗常驻"中断"按钮
 * - 用户点击 → [interrupt] → 立即停止 AutomationController + 删除当前场景的规则
 *   （表示"这个场景不该这么操作"）
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
        Log.i(TAG, "=== 录制开始 ===")
        logToRecordingFile("=== 录制开始 ===")
        // 暂停自动化（避免 bot 干扰用户操作）
        if (AutomationController.isRunning) {
            Log.i(TAG, "pausing automation for recording")
            AutomationController.stop()
        }
        onRecordingChanged?.invoke(true)
    }

    /** 停止录制 */
    fun stop() {
        if (!recording) return
        recording = false
        Log.i(TAG, "=== 录制结束，本次录制 $recordedCount 条规则 ===")
        logToRecordingFile("=== 录制结束，本次录制 $recordedCount 条规则 ===")
        onRecordingChanged?.invoke(false)
    }

    /** 切换录制状态 */
    fun toggle() {
        if (recording) stop() else start()
    }

    /**
     * 处理用户手势事件（由 FarmAccessibilityService.onAccessibilityEvent 调用）
     *
     * 只在录制模式下生效，识别用户操作类型并记录规则。
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

        // 提取操作前的场景特征
        val features = SceneFeatureExtractor.extract(service, AutomationController.currentState.name)

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val text = event.text?.joinToString(" ")?.trim()
                    ?: event.className?.toString()?.trim()
                    ?: ""
                val source = event.source
                val nodeText = source?.text?.toString()?.trim()
                val desc = source?.contentDescription?.toString()?.trim()
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
                val deltaY = event.scrollDeltaY
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

    /**
     * 统一处理手势录制逻辑（点击/滑动共用）
     *
     * - Matched → 强化已有规则
     * - Unmapped → 自动命名 + 创建 category（不弹窗，通过 [onSceneAutoCreated] 回调通知 UI 显示 Toast）
     * - Defaulted/None → 命中默认规则，跳过录制
     */
    private fun handleGesture(
        features: SceneFeatures,
        action: SceneLibrary.Action,
        targetButton: String?,
        actionDesc: String
    ) {
        val matchResult = SceneLibrary.match(features)
        when (matchResult) {
            is SceneLibrary.MatchResult.Matched -> {
                // 已有 category（含 coreSignature 自动归类），强化规则
                Log.i(TAG, "已有 category '${matchResult.category.name}'，强化规则")
                SceneLibrary.recordRule(features, action, targetButton)
                recordedCount++
            }
            is SceneLibrary.MatchResult.Unmapped -> {
                // 全新场景，自动命名 + 创建（不弹窗）
                val name = SceneLibrary.autoName(features, action, targetButton)
                SceneLibrary.createCategory(features, name, action, targetButton)
                recordedCount++
                Log.i(TAG, "自动创建场景: name='$name' action=$action")
                logToRecordingFile("AUTO_CREATE name='$name' action=$action sig='${features.signature()}'")
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
     * - 删除当前场景的规则（表示"这个场景不该这么操作"）
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
            // 删除当前场景的所有规则（表示"这个场景不该这么操作"）
            SceneLibrary.removeRule(features)
        }

        // 停止自动化
        AutomationController.stop()

        // 停止录制（如果在录制）
        if (recording) {
            stop()
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
