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
import com.bbncbot.MainActivity
import com.bbncbot.R
import com.bbncbot.automation.AutomationController
import com.bbncbot.automation.AutomationState
import com.bbncbot.service.FarmAccessibilityService
import kotlin.math.abs

/**
 * 悬浮窗前台 Service
 *
 * - 通过 WindowManager 显示一个可拖动的悬浮按钮
 * - 按钮文本根据 [AutomationController] 状态切换：「开始施肥」/「停止施肥」
 * - 通过前台通知保活
 */
class FloatingWindowService : Service() {

    companion object {
        private const val TAG = "FloatingWindowService"
        private const val CHANNEL_ID = "bbnc_farm_bot_channel"
        private const val NOTIFICATION_ID = 1001
        private const val CLICK_THRESHOLD_PX = 10f
    }

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var actionButton: Button? = null

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
            y = 200
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        setupFloatingWindow()
        AutomationController.onStateChanged = { state ->
            updateButtonUi(state)
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
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        AutomationController.stop()
        AutomationController.onStateChanged = null
        floatingView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "removeView failed: ${e.message}")
            }
        }
        floatingView = null
        actionButton = null
        Log.i(TAG, "FloatingWindowService destroyed")
    }

    private fun setupFloatingWindow() {
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.view_floating_button, null, false)
        val button = view.findViewById<Button>(R.id.btnAction)
        actionButton = button

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        button.setOnTouchListener { v: View, event: MotionEvent ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
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
                    if (!isDragging) {
                        v.performClick()
                        onActionButtonClicked()
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
            when (state) {
                AutomationState.IDLE -> {
                    btn.text = getString(R.string.btn_start_fertilize)
                    btn.setBackgroundResource(R.drawable.bg_floating_button)
                }
                AutomationState.STOPPING -> {
                    btn.text = getString(R.string.btn_stopping)
                    btn.setBackgroundResource(R.drawable.bg_floating_button_red)
                }
                else -> {
                    // v2: 根据状态显示不同文本
                    val text = when (state) {
                        AutomationState.NAVIGATING -> "导航中...停止"
                        AutomationState.OPENING_TASK_LIST -> "打开任务列表...停止"
                        AutomationState.PROCESSING_TASK -> "处理任务...停止"
                        AutomationState.WATCHING_AD -> "看广告...停止"
                        AutomationState.CLOSING_AD -> "关闭广告...停止"
                        AutomationState.RETURNING -> "返回中...停止"
                        AutomationState.WAITING -> "等待中...停止"
                        else -> getString(R.string.btn_stop_fertilize)
                    }
                    btn.text = text
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
