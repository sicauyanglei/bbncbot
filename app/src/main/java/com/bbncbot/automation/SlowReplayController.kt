package com.bbncbot.automation

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.bbncbot.service.FarmAccessibilityService

/**
 * 慢放回放控制器
 *
 * **针对单条规则回放**，慢放发生在回放过程中。
 *
 * 一条规则 = 一次录制流程（[SceneLibrary.RecordingSession]），包含多个有序子步
 * （[SceneLibrary.SceneCategory]，每步一个动作如点击/滑动/返回）。
 *
 * 回放流程：
 * 1. 用户在规则编辑界面选一条规则（session 或独立规则）
 * 2. [start] / [startSingle] 构建播放列表（该 session 的所有子步，按 stepIndex 排序）
 * 3. 默认暂停在第一步（PAUSED），用户可选择：
 *    - ▶ **自动播放**（正常回放）：每 [autoStepDelayMs] 执行一步，连续跑完
 *    - ⏭ **单步**（慢放）：每点一次执行一步，停在下一步观察效果
 *    - ⏸ **暂停**：随时暂停自动播放，光标不动
 *    - ◀ **回退**：光标回退一步，可重新执行上一步（用于重试/修改后重跑）
 *    - ✎ **编辑**：暂停时编辑当前子步规则，保存后立即生效
 *    - ⏹ **停止**：结束回放
 *
 * 心智模型（与调试器一致）：
 * - [currentIndex] = "光标位置" = 下一步要执行的子步
 * - [stepForward]：执行 [currentIndex] 处的子步，光标后移一位
 * - [play]：自动播放（正常回放速度），每 [autoStepDelayMs] 执行一次 [stepForward]
 * - [pause]：暂停自动播放
 *
 * 与 [AutomationController] 互斥：回放启动时自动停止 AutomationController 和录制。
 */
object SlowReplayController {

    private const val TAG = "SlowReplay"

    /** 慢放自动单步间隔（毫秒）默认值 */
    const val DEFAULT_AUTO_STEP_DELAY_MS = 5000L

    /** 慢放状态 */
    enum class State {
        /** 未启动 */
        IDLE,
        /** 已启动，暂停在某一步（等待用户操作） */
        PAUSED,
        /** 自动播放中（每 [autoStepDelayMs] 执行一步） */
        PLAYING
    }

    @Volatile private var playlist: List<SceneLibrary.SceneCategory> = emptyList()
    @Volatile private var currentIndex: Int = -1
    @Volatile private var state: State = State.IDLE
    @Volatile private var autoStepDelayMs: Long = DEFAULT_AUTO_STEP_DELAY_MS
    /** 当前回放的规则名（session 名或独立规则名，用于 UI 显示） */
    @Volatile private var activeRuleName: String = ""
    /** 当前回放对应的 sessionId（独立规则为空） */
    @Volatile private var activeSessionId: String = ""
    /** 当前回放规则的平台（用于跳转芭芭农场，"UC"/"ALIPAY"/"TAOBAO"，空=未知不跳转） */
    @Volatile private var activePlatform: String = ""

    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()

    /** 状态变化通知（浮窗用来更新按钮 ▶/⏸） */
    @Volatile var onStateChanged: ((State) -> Unit)? = null

    /** 当前步变化通知（浮窗用来更新步数显示：index, total, rule） */
    @Volatile var onStepChanged: ((currentIndex: Int, total: Int, rule: SceneLibrary.SceneCategory?) -> Unit)? = null

    /** 单步执行结果通知（成功/失败原因，浮窗用来 Toast） */
    @Volatile var onStepExecuted: ((rule: SceneLibrary.SceneCategory, success: Boolean, message: String) -> Unit)? = null

    /** 播放结束/停止通知（浮窗用来隐藏面板） */
    @Volatile var onFinished: (() -> Unit)? = null

    val currentState: State get() = state
    val currentStepIndex: Int get() = currentIndex
    val playlistSize: Int get() = playlist.size
    val isRunning: Boolean get() = state != State.IDLE
    val activeName: String get() = activeRuleName
    val activePlatformName: String get() = activePlatform
    val autoDelayMs: Long get() = autoStepDelayMs

    /**
     * 启动单条规则的慢放回放（针对一个 session 流程的所有子步）
     *
     * 回放针对"一条规则"——即一次录制流程（session），包含多个有序子步（SceneCategory）。
     * 慢放发生在回放过程中：默认暂停在第一步，用户可选择 ▶ 自动播放（正常回放）或 ⏭ 单步（慢放）。
     *
     * @param sessionId 录制会话 ID
     * @return 播放列表大小（子步数），0 表示无规则可回放
     */
    fun start(sessionId: String): Int {
        synchronized(lock) {
            if (state != State.IDLE) {
                Log.w(TAG, "start called but state=$state, ignoring")
                return 0
            }
            // 构建播放列表：该 session 的所有子步，按 stepIndex 排序
            val allRules = SceneLibrary.listCategories()
            val sess = SceneLibrary.listSessions().firstOrNull { it.id == sessionId }
            playlist = allRules
                .filter { it.enabled && it.sessionId == sessionId }
                .sortedBy { it.stepIndex }

            if (playlist.isEmpty()) {
                Log.w(TAG, "no rules to replay (session=$sessionId)")
                return 0
            }

            // 与 AutomationController / RecordingManager 互斥
            if (AutomationController.isRunning) {
                AutomationController.stop()
            }
            if (RecordingManager.recording) {
                RecordingManager.stop()
            }

            activeSessionId = sessionId
            activeRuleName = sess?.name ?: sessionId
            // 取第一步的平台作为整条规则的平台（同一 session 的子步平台一致）
            activePlatform = playlist.firstOrNull()?.platform ?: ""
            currentIndex = 0
            moveTo(State.PAUSED)
            Log.i(TAG, "slow replay started: session=$sessionId name='${activeRuleName}' platform='$activePlatform' ${playlist.size} steps")
            notifyStepChanged()
            // 自动跳转到该平台的芭芭农场
            launchPlatformOf(activePlatform)
            return playlist.size
        }
    }

    /**
     * 启动单条独立规则的慢放回放（无 session 的规则，播放列表只含自身一步）
     *
     * @param categoryId 规则 ID
     * @return 播放列表大小（1 或 0）
     */
    fun startSingle(categoryId: String): Int {
        synchronized(lock) {
            if (state != State.IDLE) {
                Log.w(TAG, "startSingle called but state=$state, ignoring")
                return 0
            }
            val rule = SceneLibrary.listCategories().firstOrNull { it.id == categoryId && it.enabled }
            if (rule == null) {
                Log.w(TAG, "no rule to replay (categoryId=$categoryId)")
                return 0
            }

            if (AutomationController.isRunning) {
                AutomationController.stop()
            }
            if (RecordingManager.recording) {
                RecordingManager.stop()
            }

            playlist = listOf(rule)
            activeSessionId = ""
            activeRuleName = rule.name
            activePlatform = rule.platform
            currentIndex = 0
            moveTo(State.PAUSED)
            Log.i(TAG, "slow replay started: single rule='${rule.name}' platform='$activePlatform'")
            notifyStepChanged()
            // 自动跳转到该平台的芭芭农场
            launchPlatformOf(activePlatform)
            return 1
        }
    }

    /**
     * 单步前进：执行当前步规则动作，然后光标后移一位
     *
     * - PAUSED 状态调用 → 执行一步后保持 PAUSED
     * - PLAYING 状态调用（由 [autoStepRunnable] 触发）→ 执行一步后调度下一次自动
     * - 到达末尾 → 结束慢放（[onFinished]）
     */
    fun stepForward() {
        synchronized(lock) {
            if (state == State.IDLE) return
            val wasPlaying = state == State.PLAYING
            if (currentIndex < 0 || currentIndex >= playlist.size) {
                if (wasPlaying) {
                    handler.removeCallbacks(autoStepRunnable)
                    moveTo(State.PAUSED)
                }
                return
            }
            executeCurrentStep()
            currentIndex++
            if (currentIndex >= playlist.size) {
                // 播放结束
                handler.removeCallbacks(autoStepRunnable)
                Log.i(TAG, "slow replay finished (reached end)")
                moveTo(State.IDLE)
                playlist = emptyList()
                currentIndex = -1
                activeRuleName = ""
                activeSessionId = ""
                activePlatform = ""
                onFinished?.invoke()
                return
            }
            notifyStepChanged()
            if (wasPlaying) {
                handler.postDelayed(autoStepRunnable, autoStepDelayMs)
            }
        }
    }

    /**
     * 回退一步：光标前移一位（不重新执行），停在上一歩
     *
     * 用途：上一步执行失败/效果不对 → 回退 → 编辑修正 → 重新单步执行
     */
    fun stepBackward() {
        synchronized(lock) {
            if (state == State.IDLE) return
            if (state == State.PLAYING) {
                handler.removeCallbacks(autoStepRunnable)
                moveTo(State.PAUSED)
            }
            if (currentIndex > 0) {
                currentIndex--
                moveTo(State.PAUSED)
                notifyStepChanged()
                Log.i(TAG, "stepped back to index=$currentIndex")
            } else {
                Log.w(TAG, "already at first step, cannot go back")
            }
        }
    }

    /**
     * 自动播放：每 [autoStepDelayMs] 执行一步
     *
     * 从当前光标位置立即执行一步，然后按间隔自动前进，直到结束或被 [pause]/[stop]
     */
    fun play() {
        synchronized(lock) {
            if (state == State.IDLE) return
            if (state == State.PLAYING) return
            if (currentIndex < 0 || currentIndex >= playlist.size) return
            moveTo(State.PLAYING)
            // 立即执行当前步（stepForward 会因 wasPlaying=true 调度下一次）
            stepForward()
            Log.i(TAG, "auto play started, interval=${autoStepDelayMs}ms")
        }
    }

    /** 暂停自动播放（光标不动） */
    fun pause() {
        synchronized(lock) {
            if (state == State.PLAYING) {
                handler.removeCallbacks(autoStepRunnable)
                moveTo(State.PAUSED)
                Log.i(TAG, "paused at index=$currentIndex")
            }
        }
    }

    /** 停止慢放回放 */
    fun stop() {
        synchronized(lock) {
            handler.removeCallbacks(autoStepRunnable)
            val wasRunning = state != State.IDLE
            moveTo(State.IDLE)
            playlist = emptyList()
            currentIndex = -1
            activeRuleName = ""
            activeSessionId = ""
            activePlatform = ""
            Log.i(TAG, "slow replay stopped")
            if (wasRunning) onFinished?.invoke()
        }
    }

    /** 获取当前步（光标位置）的规则 */
    fun getCurrentRule(): SceneLibrary.SceneCategory? {
        return playlist.getOrNull(currentIndex)
    }

    /**
     * 动态修改当前规则（暂停时编辑，立即生效）
     *
     * 修改后播放列表中该位置的规则引用也更新，下一步按新规则执行。
     * 注意：不会重新排序播放列表（避免规则在回放中途跳位）。
     *
     * @return true 表示修改成功
     */
    fun updateCurrentRule(
        name: String? = null,
        action: SceneLibrary.Action? = null,
        targetButton: String? = null,
        priority: Int? = null
    ): Boolean {
        synchronized(lock) {
            val rule = getCurrentRule() ?: return false
            val ok = SceneLibrary.updateCategory(rule.id, name, action, targetButton, priority = priority)
            if (ok) {
                // 从 SceneLibrary 重新读取这条规则，更新播放列表引用
                val updated = SceneLibrary.listCategories().firstOrNull { it.id == rule.id }
                if (updated != null) {
                    playlist = playlist.map { if (it.id == rule.id) updated else it }
                    notifyStepChanged()
                    Log.i(TAG, "rule updated in-place: ${updated.name} action=${updated.action} target=${updated.targetButton}")
                }
            }
            return ok
        }
    }

    /** 设置自动播放间隔（最小 1000ms） */
    fun setAutoStepDelay(ms: Long) {
        autoStepDelayMs = ms.coerceAtLeast(1000L)
        Log.i(TAG, "auto step delay set to ${autoStepDelayMs}ms")
    }

    /**
     * 跳转到指定平台的芭芭农场页面
     *
     * 复用 [FarmAccessibilityService.launchPlatformApp]：先试桌面快捷方式，
     * 再试 deep link（仅 UC），最后 fallback 到 packageManager 启动 + 无障碍服务导航。
     *
     * - 平台为空或 UNKNOWN：不跳转，让用户自己切到目标 App
     * - 平台有效：异步调用 launchPlatformApp，跳转后无障碍服务会自动导航到农场页
     *
     * 注意：此方法在主线程调用（launchPlatformApp 内部用 navHandler.postDelayed），
     * 不阻塞回放流程——回放面板已显示，用户可在等待跳转完成时操作面板。
     */
    private fun launchPlatformOf(platformStr: String) {
        if (platformStr.isEmpty() || platformStr == "UNKNOWN") {
            Log.i(TAG, "launchPlatformOf: platform empty/unknown, skip navigation")
            return
        }
        val service = FarmAccessibilityService.getInstance() ?: run {
            Log.w(TAG, "launchPlatformOf: accessibility service null")
            return
        }
        val platform = try {
            Platform.valueOf(platformStr)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "launchPlatformOf: unknown platform '$platformStr'")
            return
        }
        // 异步跳转：launchPlatformApp 内部会用 navHandler.postDelayed 调度导航步骤
        val ok = try {
            service.launchPlatformApp(platform)
        } catch (e: Exception) {
            Log.e(TAG, "launchPlatformOf: launchPlatformApp failed", e)
            false
        }
        Log.i(TAG, "launchPlatformOf: platform=$platform launched=$ok")
    }

    // ---- 内部实现 ----

    private val autoStepRunnable = Runnable { stepForward() }

    /**
     * 执行当前步规则动作
     *
     * 动作派发复用 FarmAccessibilityService 的手势/点击 API：
     * - SWIPE_UP/DOWN → dispatchGestureSwipe
     * - BACK/EXIT_TASK → pressBack
     * - CLICK_BUTTON → findNodeByText + performClickSafe
     * - WAIT → 无操作
     * - STOP_AUTOMATION → 停止慢放
     * - UNKNOWN → 无操作，报告
     */
    private fun executeCurrentStep() {
        val rule = playlist.getOrNull(currentIndex) ?: return
        val s = FarmAccessibilityService.getInstance()
        var success = false
        var message = ""

        if (s == null) {
            message = "无障碍服务未连接"
            onStepExecuted?.invoke(rule, false, message)
            Log.w(TAG, "execute: service null, rule='${rule.name}'")
            return
        }

        try {
            val root = s.rootInActiveWindowSafe()
            val dm = s.resources.displayMetrics
            val centerX = dm.widthPixels / 2f
            val screenH = dm.heightPixels

            when (rule.action) {
                SceneLibrary.Action.SWIPE_UP -> {
                    success = s.dispatchGestureSwipe(centerX, screenH * 0.7f, centerX, screenH * 0.3f, 500L)
                    message = if (success) "向上滑动" else "滑动失败"
                }
                SceneLibrary.Action.SWIPE_DOWN -> {
                    success = s.dispatchGestureSwipe(centerX, screenH * 0.3f, centerX, screenH * 0.7f, 500L)
                    message = if (success) "向下滑动" else "滑动失败"
                }
                SceneLibrary.Action.BACK -> {
                    success = s.pressBack()
                    message = if (success) "返回" else "返回失败"
                }
                SceneLibrary.Action.CLICK_BUTTON -> {
                    val target = rule.targetButton
                    if (target.isNullOrEmpty()) {
                        message = "targetButton 为空，无法点击"
                    } else if (root == null) {
                        message = "无可用节点树"
                    } else {
                        val node = s.findNodeByText(root, target)
                        if (node != null) {
                            success = s.performClickSafe(node)
                            message = if (success) "点击: $target" else "点击失败: $target"
                        } else {
                            message = "未找到按钮: $target"
                        }
                    }
                }
                SceneLibrary.Action.WAIT -> {
                    success = true
                    message = "等待"
                }
                SceneLibrary.Action.EXIT_TASK -> {
                    success = s.pressBack()
                    message = "退出任务(返回)"
                }
                SceneLibrary.Action.STOP_AUTOMATION -> {
                    message = "规则要求停止"
                    success = true
                    Log.i(TAG, "rule STOP_AUTOMATION triggered, stopping slow replay")
                    onStepExecuted?.invoke(rule, true, message)
                    // 异步停止，避免在锁内回调引发重入
                    handler.post { stop() }
                    return
                }
                SceneLibrary.Action.UNKNOWN -> {
                    message = "未知动作，跳过"
                }
            }
        } catch (e: Exception) {
            message = "执行异常: ${e.message}"
            Log.e(TAG, "execute step error", e)
        }

        onStepExecuted?.invoke(rule, success, message)
        Log.i(TAG, "step executed: idx=$currentIndex rule='${rule.name}' action=${rule.action} success=$success msg=$message")
    }

    private fun moveTo(newState: State) {
        state = newState
        onStateChanged?.invoke(newState)
    }

    private fun notifyStepChanged() {
        val rule = getCurrentRule()
        onStepChanged?.invoke(currentIndex, playlist.size, rule)
    }
}
