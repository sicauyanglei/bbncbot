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

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        setupFloatingWindow()
        setupProposalWindow()
        setupSceneAutoCreatedCallback()
        AutomationController.onStateChanged = { state ->
            updateButtonUi(state)
        }
        // 监听提议变化：有提议显示浮窗，无提议隐藏
        ActionProposer.onProposalChanged = { proposal ->
            if (proposal != null) showProposal(proposal) else hideProposal()
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
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        AutomationController.stop()
        AutomationController.onStateChanged = null
        ActionProposer.onProposalChanged = null
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
