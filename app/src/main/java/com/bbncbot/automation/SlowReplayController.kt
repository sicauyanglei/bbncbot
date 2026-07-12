package com.bbncbot.automation

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.bbncbot.service.FarmAccessibilityService

/**
 * 慢放回放控制器
 *
 * 按规则列表顺序逐步回放，像调试器一样支持：
 * - **慢放**：每步之间自动等待 [autoStepDelayMs]，让用户观察效果
 * - **暂停**：随时暂停，停留在当前步骤（光标位置）
 * - **回退**：光标回退一步，可重新执行上一步（用于重试/修改后重跑）
 * - **动态修改**：暂停时编辑当前规则，保存后立即生效，下一步按新规则执行
 *
 * 心智模型（与调试器一致）：
 * - [currentIndex] = "光标位置" = 下一步要执行的规则
 * - [stepForward]：执行 [currentIndex] 处的规则，光标后移一位
 * - [stepBackward]：光标前移一位（回到上一步，可重新执行）
 * - [play]：自动播放，每 [autoStepDelayMs] 执行一次 [stepForward]
 * - [pause]：停止自动播放，光标不动
 * - [updateCurrentRule]：编辑 [currentIndex] 处的规则，立即生效
 *
 * 与 [AutomationController] 互斥：慢放启动时自动停止 AutomationController 和录制。
 *
 * 使用流程：
 * 1. 用户在规则编辑界面点"慢放回放" + 选择肥料任务（或全部）
 * 2. [start] 构建播放列表（按 fertTask 过滤，按 priority/stepIndex 排序）
 * 3. 浮窗显示慢放控制面板：◀ 回退  ▶/⏸ 播放/暂停  ⏭ 单步  ✎ 编辑  ⏹ 停止
 * 4. 每步执行规则动作（点击/滑动/返回），用户观察效果
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
    /** 启动时使用的 fertTask 过滤（用于 UI 显示） */
    @Volatile private var activeFilter: String? = null

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
    val activeFertTaskFilter: String? get() = activeFilter
    val autoDelayMs: Long get() = autoStepDelayMs

    /**
     * 启动慢放回放
     *
     * @param fertTaskFilter 肥料任务过滤（null = 回放全部启用规则）
     * @return 播放列表大小，0 表示无规则可回放
     */
    fun start(fertTaskFilter: String?): Int {
        synchronized(lock) {
            if (state != State.IDLE) {
                Log.w(TAG, "start called but state=$state, ignoring")
                return 0
            }
            // 构建播放列表：启用的规则，按 fertTask 过滤，按 priority/stepIndex/createdAt 排序
            val allRules = SceneLibrary.listCategories()
            playlist = allRules
                .filter { it.enabled && (fertTaskFilter == null || it.fertTask == fertTaskFilter) }
                .sortedWith(compareBy({ it.priority }, { it.sessionId }, { it.stepIndex }, { it.createdAt }))

            if (playlist.isEmpty()) {
                Log.w(TAG, "no rules to replay (filter=$fertTaskFilter)")
                return 0
            }

            // 与 AutomationController / RecordingManager 互斥
            if (AutomationController.isRunning) {
                AutomationController.stop()
            }
            if (RecordingManager.recording) {
                RecordingManager.stop()
            }

            activeFilter = fertTaskFilter
            currentIndex = 0
            moveTo(State.PAUSED)
            Log.i(TAG, "slow replay started: ${playlist.size} rules, filter=$fertTaskFilter")
            notifyStepChanged()
            return playlist.size
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
                val total = playlist.size
                moveTo(State.IDLE)
                playlist = emptyList()
                currentIndex = -1
                activeFilter = null
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
            activeFilter = null
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
