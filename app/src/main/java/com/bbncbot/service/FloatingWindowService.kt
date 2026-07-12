package com.bbncbot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.res.Resources
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.bbncbot.MainActivity
import com.bbncbot.R
import com.bbncbot.automation.ActionProposer
import com.bbncbot.automation.AutomationController
import com.bbncbot.automation.AutomationState
import com.bbncbot.automation.RecordingManager
import com.bbncbot.automation.SceneLibrary
import com.bbncbot.automation.SlowReplayController
import com.bbncbot.service.FarmAccessibilityService
import kotlin.math.abs

/**
 * 悬浮窗前台 Service
 *
 * - 通过 WindowManager 显示一个可拖动的悬浮按钮
 * - 按钮文本根据 [AutomationController] 状态切换：「开始施肥」/「停止施肥」
 * - 长按按钮切换 [ActionProposer.enabled] 交互模式：开启后每个拟动作都会弹询问浮窗
 * - 通过前台通知保活
 */
class FloatingWindowService : Service() {

    companion object {
        private const val TAG = "FloatingWindowService"
        private const val CHANNEL_ID = "bbnc_farm_bot_channel"
        private const val NOTIFICATION_ID = 1001
        private const val CLICK_THRESHOLD_PX = 10f
        /** 长按切换交互模式的阈值（毫秒） */
        private const val LONG_PRESS_THRESHOLD_MS = 800L
    }

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var actionButton: Button? = null

    /** 询问浮窗（拟动作展示 + 三按钮），默认不添加，[ActionProposer] 有提议时才显示 */
    private var proposalView: View? = null
    private var proposalActionTv: TextView? = null
    private var proposalReasonTv: TextView? = null
    private var proposalPageTv: TextView? = null

    /** 录制小图标（与主按钮同窗口），开启/停止录制 */
    private var recordToggleButton: Button? = null
    /** 中断小图标（与主按钮同窗口），立即停止 bot + 删除当前场景规则 */
    private var interruptButton: Button? = null

    /** 慢放回放控制面板（独立悬浮窗，慢放启动时显示） */
    private var slowReplayView: View? = null
    private var slowReplayStepTv: TextView? = null
    private var slowReplayPlayPauseBtn: Button? = null

    private val layoutParams: WindowManager.LayoutParams by lazy {
        WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            format = PixelFormat.RGBA_8888
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 400
        }
    }

    /** 询问浮窗的 LayoutParams（独立位置，避免与主按钮重叠） */
    private val proposalLayoutParams: WindowManager.LayoutParams by lazy {
        WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            format = PixelFormat.RGBA_8888
            // 注意：不加 FLAG_NOT_FOCUSABLE，以便按钮可点击响应
            flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.CENTER
        }
    }

    /** 慢放回放面板的 LayoutParams（底部居中，可点击按钮） */
    private val slowReplayLayoutParams: WindowManager.LayoutParams by lazy {
        WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            format = PixelFormat.RGBA_8888
            flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 120
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        setupFloatingWindow()
        setupProposalWindow()
        setupSlowReplayPanel()
        setupSceneAutoCreatedCallback()
        AutomationController.onStateChanged = { state ->
            updateButtonUi(state)
        }
        // 监听提议变化：有提议显示浮窗，无提议隐藏
        ActionProposer.onProposalChanged = { proposal ->
            if (proposal != null) showProposal(proposal) else hideProposal()
        }
        // 慢放回放：状态变化 → 更新播放/暂停按钮；步变化 → 更新步数文本；
        // 执行结果 → Toast；结束 → 隐藏面板
        SlowReplayController.onStateChanged = { st ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                updateSlowReplayPlayPauseBtn(st)
            }
        }
        SlowReplayController.onStepChanged = { idx, total, rule ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                updateSlowReplayStepText(idx, total, rule)
            }
        }
        SlowReplayController.onStepExecuted = { rule, success, message ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val icon = if (success) "✓" else "✗"
                Toast.makeText(this, "$icon ${rule.name}: $message", Toast.LENGTH_SHORT).show()
            }
        }
        SlowReplayController.onFinished = {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                hideSlowReplayPanel()
                Toast.makeText(this, "慢放回放已结束", Toast.LENGTH_SHORT).show()
                updateButtonUi(AutomationController.currentState)
            }
        }
        // 初始化按钮显示
        updateButtonUi(AutomationController.currentState)
        Log.i(TAG, "FloatingWindowService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 支持通过 adb 发广播触发自动化：adb shell am broadcast -a com.bbncbot.START_AUTOMATION
        if (intent?.action == "com.bbncbot.START_AUTOMATION") {
            Log.i(TAG, "Received START_AUTOMATION broadcast")
            onActionButtonClicked()
        } else if (intent?.action == "com.bbncbot.STOP_AUTOMATION") {
            Log.i(TAG, "Received STOP_AUTOMATION broadcast")
            AutomationController.stop()
        } else if (intent?.action == "com.bbncbot.DUMP_NODES") {
            Log.i(TAG, "Received DUMP_NODES broadcast")
            FarmAccessibilityService.getInstance()?.dumpNodeTree()
        } else if (intent?.action == "com.bbncbot.TOGGLE_INTERACTIVE") {
            // adb shell am broadcast -a com.bbncbot.TOGGLE_INTERACTIVE
            Log.i(TAG, "Received TOGGLE_INTERACTIVE broadcast")
            toggleInteractiveMode()
        } else if (intent?.action == "com.bbncbot.OPEN_TEACH") {
            // adb shell am broadcast -a com.bbncbot.OPEN_TEACH
            // 录制控制已并入主悬浮窗，此广播改为切换交互模式
            Log.i(TAG, "Received OPEN_TEACH broadcast")
            toggleInteractiveMode()
        } else if (intent?.action == "com.bbncbot.TOGGLE_RECORDING") {
            // adb shell am broadcast -a com.bbncbot.TOGGLE_RECORDING
            Log.i(TAG, "Received TOGGLE_RECORDING broadcast")
            RecordingManager.toggle()
        } else if (intent?.action == "com.bbncbot.INTERRUPT") {
            // adb shell am broadcast -a com.bbncbot.INTERRUPT
            Log.i(TAG, "Received INTERRUPT broadcast")
            RecordingManager.interrupt()
        } else if (intent?.action == "com.bbncbot.DUMP_RULES") {
            // adb shell am broadcast -a com.bbncbot.DUMP_RULES
            Log.i(TAG, "Received DUMP_RULES broadcast")
            val text = SceneLibrary.dumpRulesExplanationToFile()
            Log.i(TAG, "规则判断依据：\n$text")
            Toast.makeText(this, "规则依据已写入文件并打印到 logcat", Toast.LENGTH_LONG).show()
        } else if (intent?.action == "com.bbncbot.START_SLOW_REPLAY") {
            // 从规则编辑界面启动慢放回放
            // mode=session → 回放该 session 的所有子步；mode=single → 回放单条独立规则
            Log.i(TAG, "Received START_SLOW_REPLAY broadcast")
            val mode = intent.getStringExtra("mode") ?: "session"
            if (mode == "single") {
                val categoryId = intent.getStringExtra("categoryId")
                if (categoryId != null) startSlowReplaySingle(categoryId)
                else Toast.makeText(this, "缺少规则 ID", Toast.LENGTH_SHORT).show()
            } else {
                val sessionId = intent.getStringExtra("sessionId")
                if (sessionId != null) startSlowReplay(sessionId)
                else Toast.makeText(this, "缺少会话 ID", Toast.LENGTH_SHORT).show()
            }
        } else if (intent?.action == "com.bbncbot.STOP_SLOW_REPLAY") {
            Log.i(TAG, "Received STOP_SLOW_REPLAY broadcast")
            SlowReplayController.stop()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        AutomationController.stop()
        SlowReplayController.stop()
        AutomationController.onStateChanged = null
        ActionProposer.onProposalChanged = null
        SlowReplayController.onStateChanged = null
        SlowReplayController.onStepChanged = null
        SlowReplayController.onStepExecuted = null
        SlowReplayController.onFinished = null
        RecordingManager.onRecordingChanged = null
        RecordingManager.onSceneAutoCreated = null
        RecordingManager.onRecordingStopped = null
        proposalView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "removeView proposal failed: ${e.message}")
            }
        }
        slowReplayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "removeView slowReplay failed: ${e.message}")
            }
        }
        floatingView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "removeView failed: ${e.message}")
            }
        }
        floatingView = null
        actionButton = null
        recordToggleButton = null
        interruptButton = null
        proposalView = null
        slowReplayView = null
        slowReplayStepTv = null
        slowReplayPlayPauseBtn = null
        Log.i(TAG, "FloatingWindowService destroyed")
    }

    private fun setupFloatingWindow() {
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.view_floating_button, null, false)
        val button = view.findViewById<Button>(R.id.btnAction)
        actionButton = button

        // 录制 / 中断小图标（与主按钮同窗口，避免独立窗口触摸卡死）
        val recordBtn = view.findViewById<Button>(R.id.btnRecordToggle)
        val interruptBtn = view.findViewById<Button>(R.id.btnInterrupt)
        val homeBtn = view.findViewById<Button>(R.id.btnHome)
        recordToggleButton = recordBtn
        interruptButton = interruptBtn

        recordBtn.setOnClickListener {
            RecordingManager.toggle()
        }
        interruptBtn.setOnClickListener {
            RecordingManager.interrupt()
            Toast.makeText(this, "已中断，删除当前场景规则", Toast.LENGTH_SHORT).show()
        }
        // 回主界面：启动 MainActivity（singleTask，已存在则带到前台）
        homeBtn.setOnClickListener {
            try {
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "startActivity MainActivity failed: ${e.message}")
            }
        }

        // 录制状态变化时更新小图标样式
        RecordingManager.onRecordingChanged = { isRecording ->
            recordBtn.post {
                if (isRecording) {
                    recordBtn.text = "停录"
                    recordBtn.setBackgroundResource(R.drawable.bg_floating_button_red)
                } else {
                    recordBtn.text = "录制"
                    recordBtn.setBackgroundResource(R.drawable.bg_floating_button)
                }
            }
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        var pressDownTime = 0L

        button.setOnTouchListener { v: View, event: MotionEvent ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    pressDownTime = System.currentTimeMillis()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (abs(dx) > CLICK_THRESHOLD_PX || abs(dy) > CLICK_THRESHOLD_PX) {
                        isDragging = true
                        layoutParams.x = initialX + dx.toInt()
                        layoutParams.y = initialY + dy.toInt()
                        try {
                            windowManager.updateViewLayout(view, layoutParams)
                        } catch (e: Exception) {
                            Log.w(TAG, "updateViewLayout failed: ${e.message}")
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val pressDuration = System.currentTimeMillis() - pressDownTime
                    if (!isDragging) {
                        if (pressDuration >= LONG_PRESS_THRESHOLD_MS) {
                            // 长按：切换交互模式（每个拟动作弹询问浮窗）
                            Log.i(TAG, "long press detected, toggling interactive mode")
                            toggleInteractiveMode()
                        } else {
                            // 单击：启停自动化
                            v.performClick()
                            onActionButtonClicked()
                        }
                    }
                }
            }
            true
        }

        floatingView = view
        try {
            windowManager.addView(view, layoutParams)
        } catch (e: Exception) {
            Log.e(TAG, "addView failed: ${e.message}")
        }
    }

    /** 切换交互模式开关（长按悬浮按钮触发） */
    private fun toggleInteractiveMode() {
        ActionProposer.enabled = !ActionProposer.enabled
        val msg = if (ActionProposer.enabled) "交互模式已开启：每个动作都会询问你" else "交互模式已关闭：自动执行"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        Log.i(TAG, "interactive mode toggled: enabled=${ActionProposer.enabled}")
        // 关闭交互模式时如果有未响应的提议，按 APPROVE 自动放行
        if (!ActionProposer.enabled && ActionProposer.hasPending()) {
            ActionProposer.respond(ActionProposer.Response.APPROVE)
        }
    }

    /** 初始化询问浮窗（默认不添加到 WindowManager） */
    private fun setupProposalWindow() {
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.view_proposal_dialog, null, false)
        proposalActionTv = view.findViewById(R.id.tvProposalAction)
        proposalReasonTv = view.findViewById(R.id.tvProposalReason)
        proposalPageTv = view.findViewById(R.id.tvProposalPage)
        view.findViewById<Button>(R.id.btnApprove).setOnClickListener {
            ActionProposer.respond(ActionProposer.Response.APPROVE)
        }
        view.findViewById<Button>(R.id.btnReject).setOnClickListener {
            ActionProposer.respond(ActionProposer.Response.REJECT)
        }
        view.findViewById<Button>(R.id.btnSkip).setOnClickListener {
            ActionProposer.respond(ActionProposer.Response.SKIP)
        }
        proposalView = view
    }

    /** 显示询问浮窗（主线程调用） */
    private fun showProposal(proposal: ActionProposer.Proposal) {
        val view = proposalView ?: return
        proposalActionTv?.text = proposal.action
        proposalReasonTv?.text = proposal.reason
        proposalPageTv?.text = proposal.pageSummary
        try {
            // 已添加则更新布局即可
            windowManager.removeView(view)
        } catch (e: Exception) {
            // 未添加过，忽略
        }
        try {
            windowManager.addView(view, proposalLayoutParams)
        } catch (e: Exception) {
            Log.w(TAG, "showProposal addView failed: ${e.message}")
        }
    }

    /** 隐藏询问浮窗（主线程调用） */
    private fun hideProposal() {
        val view = proposalView ?: return
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            // 未添加，忽略
        }
    }

    // ===== 慢放回放面板 =====

    /** 初始化慢放回放控制面板（默认不添加到 WindowManager，慢放启动时才显示） */
    private fun setupSlowReplayPanel() {
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.view_slow_replay_panel, null, false)
        slowReplayStepTv = view.findViewById(R.id.tvStepInfo)
        slowReplayPlayPauseBtn = view.findViewById(R.id.btnPlayPause)

        view.findViewById<Button>(R.id.btnBack).setOnClickListener {
            SlowReplayController.stepBackward()
        }
        view.findViewById<Button>(R.id.btnPlayPause).setOnClickListener {
            when (SlowReplayController.currentState) {
                SlowReplayController.State.PLAYING -> SlowReplayController.pause()
                SlowReplayController.State.PAUSED -> SlowReplayController.play()
                SlowReplayController.State.IDLE -> { /* 不应发生 */ }
            }
        }
        view.findViewById<Button>(R.id.btnStepFwd).setOnClickListener {
            // 单步前进：先确保暂停，再执行一步
            if (SlowReplayController.currentState == SlowReplayController.State.PLAYING) {
                SlowReplayController.pause()
            }
            SlowReplayController.stepForward()
        }
        view.findViewById<Button>(R.id.btnEdit).setOnClickListener {
            // 编辑当前规则：启动对话框风格的编辑界面
            if (SlowReplayController.getCurrentRule() == null) {
                Toast.makeText(this, "无当前规则可编辑", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 编辑前先暂停自动播放
            if (SlowReplayController.currentState == SlowReplayController.State.PLAYING) {
                SlowReplayController.pause()
            }
            try {
                val intent = Intent(this, com.bbncbot.SlowReplayEditActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "start SlowReplayEditActivity failed: ${e.message}")
                Toast.makeText(this, "打开编辑界面失败", Toast.LENGTH_SHORT).show()
            }
        }
        view.findViewById<Button>(R.id.btnStop).setOnClickListener {
            SlowReplayController.stop()
        }
        slowReplayView = view
    }

    /** 启动单条规则（session 流程）的慢放回放 */
    private fun startSlowReplay(sessionId: String) {
        if (FarmAccessibilityService.getInstance() == null) {
            Toast.makeText(this, "无障碍服务未开启，无法回放", Toast.LENGTH_LONG).show()
            Log.w(TAG, "startSlowReplay: accessibility service not bound")
            return
        }
        val count = SlowReplayController.start(sessionId)
        if (count == 0) {
            Toast.makeText(this, "该规则没有可回放的子步", Toast.LENGTH_SHORT).show()
            return
        }
        showSlowReplayPanel()
        val platform = SlowReplayController.activePlatformName
        val platformPart = if (platform.isNotEmpty() && platform != "UNKNOWN") "（正在跳转$platform 芭芭农场…）\n" else ""
        Toast.makeText(
            this,
            "${platformPart}规则回放开始：${SlowReplayController.activeName} 共 $count 步\n▶ 自动播放 / ⏭ 单步（慢放）/ ✎ 编辑",
            Toast.LENGTH_LONG
        ).show()
        updateButtonUi(AutomationController.currentState)
    }

    /** 启动单条独立规则的慢放回放 */
    private fun startSlowReplaySingle(categoryId: String) {
        if (FarmAccessibilityService.getInstance() == null) {
            Toast.makeText(this, "无障碍服务未开启，无法回放", Toast.LENGTH_LONG).show()
            Log.w(TAG, "startSlowReplaySingle: accessibility service not bound")
            return
        }
        val count = SlowReplayController.startSingle(categoryId)
        if (count == 0) {
            Toast.makeText(this, "该规则不可回放", Toast.LENGTH_SHORT).show()
            return
        }
        showSlowReplayPanel()
        val platform = SlowReplayController.activePlatformName
        val platformPart = if (platform.isNotEmpty() && platform != "UNKNOWN") "（正在跳转$platform 芭芭农场…）\n" else ""
        Toast.makeText(
            this,
            "${platformPart}规则回放开始：${SlowReplayController.activeName}\n▶ 自动播放 / ⏭ 单步（慢放）/ ✎ 编辑",
            Toast.LENGTH_LONG
        ).show()
        updateButtonUi(AutomationController.currentState)
    }

    /** 显示慢放回放面板 */
    private fun showSlowReplayPanel() {
        val view = slowReplayView ?: return
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            // 未添加过，忽略
        }
        try {
            windowManager.addView(view, slowReplayLayoutParams)
        } catch (e: Exception) {
            Log.w(TAG, "showSlowReplayPanel addView failed: ${e.message}")
        }
        updateSlowReplayPlayPauseBtn(SlowReplayController.currentState)
        updateSlowReplayStepText(
            SlowReplayController.currentStepIndex,
            SlowReplayController.playlistSize,
            SlowReplayController.getCurrentRule()
        )
    }

    /** 隐藏慢放回放面板 */
    private fun hideSlowReplayPanel() {
        val view = slowReplayView ?: return
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            // 未添加，忽略
        }
    }

    /** 更新播放/暂停按钮文本 */
    private fun updateSlowReplayPlayPauseBtn(st: SlowReplayController.State) {
        val btn = slowReplayPlayPauseBtn ?: return
        btn.text = when (st) {
            SlowReplayController.State.PLAYING -> "⏸"
            SlowReplayController.State.PAUSED -> "▶"
            SlowReplayController.State.IDLE -> "▶"
        }
    }

    /** 更新步数信息文本（显示平台 + 规则名 + 当前步 + 动作） */
    private fun updateSlowReplayStepText(
        idx: Int,
        total: Int,
        rule: com.bbncbot.automation.SceneLibrary.SceneCategory?
    ) {
        val tv = slowReplayStepTv ?: return
        val displayIdx = if (total == 0) 0 else idx + 1
        val ruleName = SlowReplayController.activeName
        val platform = SlowReplayController.activePlatformName
        val platformTag = if (platform.isNotEmpty() && platform != "UNKNOWN") "[$platform] " else ""
        val rulePart = if (rule != null) {
            val actionLabel = actionToTextShort(rule.action)
            val targetPart = if (rule.action == com.bbncbot.automation.SceneLibrary.Action.CLICK_BUTTON && !rule.targetButton.isNullOrEmpty()) {
                "• ${rule.targetButton}"
            } else ""
            "$platformTag$ruleName\n$displayIdx/$total • $actionLabel $targetPart"
        } else {
            "$platformTag$ruleName\n$displayIdx/$total"
        }
        tv.text = rulePart
    }

    /** 动作转简短中文（用于面板显示） */
    private fun actionToTextShort(action: com.bbncbot.automation.SceneLibrary.Action): String = when (action) {
        com.bbncbot.automation.SceneLibrary.Action.SWIPE_UP -> "上滑"
        com.bbncbot.automation.SceneLibrary.Action.SWIPE_DOWN -> "下滑"
        com.bbncbot.automation.SceneLibrary.Action.BACK -> "返回"
        com.bbncbot.automation.SceneLibrary.Action.EXIT_TASK -> "退出任务"
        com.bbncbot.automation.SceneLibrary.Action.WAIT -> "等待"
        com.bbncbot.automation.SceneLibrary.Action.CLICK_BUTTON -> "点击"
        com.bbncbot.automation.SceneLibrary.Action.STOP_AUTOMATION -> "停止"
        com.bbncbot.automation.SceneLibrary.Action.UNKNOWN -> "未知"
    }

    /**
     * 注册场景自动创建回调
     *
     * 录制时遇到全新场景（coreSignature 也未命中），bot 自动按特征命名并创建 category，
     * 通过此回调在主线程显示 Toast 提示用户"已录制场景：xxx"。
     */
    private fun setupSceneAutoCreatedCallback() {
        RecordingManager.onSceneAutoCreated = { name ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(this, "已录制场景：$name", Toast.LENGTH_SHORT).show()
            }
        }
        // 录制停止后的结果通知：保存或丢弃
        RecordingManager.onRecordingStopped = { saved, initial, finalAmount, stepCount ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val msg = if (saved) {
                    if (initial < 0 || finalAmount < 0) {
                        // 肥料读取失败但保留了
                        "录制已保存（$stepCount 步，肥料读取失败但已保留规则）"
                    } else {
                        "录制已保存：肥料 $initial → $finalAmount (+${finalAmount - initial})，共 $stepCount 步"
                    }
                } else {
                    when {
                        initial < 0 || finalAmount < 0 ->
                            "录制已丢弃：肥料数值读取失败（开始=$initial 结束=$finalAmount），请确保在农场主页停录"
                        finalAmount == initial ->
                            "录制已丢弃：肥料无变化（$initial），本次任务未获得肥料"
                        else ->
                            "录制已丢弃：肥料减少（$initial → $finalAmount）"
                    }
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun onActionButtonClicked() {
        Log.i(TAG, "onActionButtonClicked: isRunning=${AutomationController.isRunning}")
        if (AutomationController.isRunning) {
            AutomationController.stop()
        } else {
            // 检查无障碍服务是否已连接
            if (!FarmAccessibilityService.isConnected()) {
                Log.w(TAG, "FarmAccessibilityService not connected, cannot start")
                android.widget.Toast.makeText(
                    this,
                    "请先开启无障碍服务",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return
            }
            AutomationController.start()
        }
    }

    /** 检查农场页面是否在前台 */
    private fun isFarmPageInForeground(): Boolean {
        try {
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            val runningTasks = am.getRunningTasks(1)
            if (runningTasks.isNotEmpty()) {
                val topPackage: String = runningTasks[0].topActivity?.packageName ?: ""
                Log.d(TAG, "current top package: $topPackage")
                return topPackage == "com.ucmobile.lite" ||
                    topPackage.startsWith("com.UCMobile") ||
                    topPackage == "com.bbncbot"
            }
        } catch (e: Exception) {
            Log.w(TAG, "isFarmPageInForeground failed: ${e.message}")
        }
        return false
    }

    private fun updateButtonUi(state: AutomationState) {
        actionButton?.post {
            val btn = actionButton ?: return@post
            // 紧凑小图标：仅区分空闲/运行，详细状态见日志与通知
            when (state) {
                AutomationState.IDLE -> {
                    btn.text = "开始"
                    btn.setBackgroundResource(R.drawable.bg_floating_button)
                }
                else -> {
                    // 运行中（含 STOPPING 及各业务状态）
                    btn.text = "停止"
                    btn.setBackgroundResource(R.drawable.bg_floating_button_red)
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_floating_button)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_LOW)
            .build()
    }

    @Suppress("unused")
    private val res: Resources get() = resources
}
