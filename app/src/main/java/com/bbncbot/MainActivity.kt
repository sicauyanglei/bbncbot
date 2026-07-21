package com.bbncbot

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.bbncbot.automation.Platform
import com.bbncbot.service.FarmAccessibilityService
import com.bbncbot.service.FloatingWindowService
import com.bbncbot.util.FarmShortcutLauncher
import com.bbncbot.util.PermissionUtils

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val FARM_URL = "https://broccoli.uc.cn/apps/ucfarm/routes/farm"
        const val EXTRA_PLATFORM = "extra_platform"
    }

    private lateinit var tvStatus: TextView
    private lateinit var btnAccessibility: Button
    private lateinit var btnOverlay: Button
    private lateinit var btnStartFloating: Button
    private lateinit var btnOpenFarm: Button
    private lateinit var btnOpenAlipayFarm: Button
    private lateinit var btnOpenTaobaoFarm: Button
    private lateinit var etGhToken: EditText
    private lateinit var btnSaveGhToken: Button
    private lateinit var tvGhTokenStatus: TextView
    private lateinit var btnTestUpload: Button
    private lateinit var tvUploadResult: TextView
    private lateinit var etGlmApiKey: EditText
    private lateinit var btnSaveGlmApiKey: Button
    private lateinit var tvGlmApiKeyStatus: TextView

    /**
     * 待执行的平台快捷方式（来自桌面快捷方式或 shortcut intent）
     * - 在 [onResume] 中消费，避免在 onCreate 阶段权限未就绪时启动
     */
    private var pendingPlatform: String? = null

    /**
     * 上次引导申请的权限标识，用于避免 onResume 重复跳同一个设置页
     * - 值为 "overlay"/"accessibility"/"launcher" 之一，null 表示全部已就绪或未开始引导
     */
    private var lastGuidedPermission: String? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 结果忽略，仅尝试申请 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // 清除旧版日志（debug 由 LogUploader 清空）
        com.bbncbot.automation.LogUploader.clearLogsOnAppStart(this)

        // 启动 App 时先 kill 所有芭芭农场平台 App（UC/支付宝/淘宝）的老进程
        // 原因：残留旧实例（旧 Activity 栈/缓存的 H5 页面）会导致后续进入农场页时
        // 只是拉起旧实例，无法回到干净的农场主页
        // MainActivity 被拉到前台时，农场 App 已被推到后台，killBackgroundProcesses 可生效
        killPlatformApps()

        tvStatus = findViewById(R.id.tvStatus)
        btnAccessibility = findViewById(R.id.btnOpenAccessibility)
        btnOverlay = findViewById(R.id.btnRequestOverlay)
        btnStartFloating = findViewById(R.id.btnStartFloating)
        btnOpenFarm = findViewById(R.id.btnOpenFarm)
        btnOpenAlipayFarm = findViewById(R.id.btnOpenAlipayFarm)
        btnOpenTaobaoFarm = findViewById(R.id.btnOpenTaobaoFarm)
        etGhToken = findViewById(R.id.etGhToken)
        btnSaveGhToken = findViewById(R.id.btnSaveGhToken)
        tvGhTokenStatus = findViewById(R.id.tvGhTokenStatus)
        btnTestUpload = findViewById(R.id.btnTestUpload)
        tvUploadResult = findViewById(R.id.tvUploadResult)
        etGlmApiKey = findViewById(R.id.etGlmApiKey)
        btnSaveGlmApiKey = findViewById(R.id.btnSaveGlmApiKey)
        tvGlmApiKeyStatus = findViewById(R.id.tvGlmApiKeyStatus)

        // 加载已保存的 GitHub Token（日志上传用）
        val savedGhToken = com.bbncbot.automation.LogUploader.loadToken(this)
        if (savedGhToken.isNotEmpty()) {
            etGhToken.setText(savedGhToken)
            tvGhTokenStatus.text = "日志上传：已配置（${savedGhToken.take(4)}...${savedGhToken.takeLast(4)}）"
        } else {
            tvGhTokenStatus.text = "日志上传：未配置（点击测试上传按钮手动上传）"
        }

        btnSaveGhToken.setOnClickListener {
            val token = etGhToken.text.toString().trim()
            if (token.isNotEmpty()) {
                com.bbncbot.automation.LogUploader.saveToken(this, token)
                tvGhTokenStatus.text = "日志上传：已配置（${token.take(4)}...${token.takeLast(4)}）"
                Toast.makeText(this, "GitHub Token 已保存", Toast.LENGTH_SHORT).show()
            } else {
                com.bbncbot.automation.LogUploader.saveToken(this, "")
                tvGhTokenStatus.text = "日志上传：未配置"
                Toast.makeText(this, "GitHub Token 已清除", Toast.LENGTH_SHORT).show()
            }
        }

        // 加载已保存的智谱 GLM API Key（答题 AI 用）
        val savedGlmKey = com.bbncbot.automation.QuizAnswerClient.loadApiKey(this)
        if (savedGlmKey.isNotEmpty()) {
            etGlmApiKey.setText(savedGlmKey)
            tvGlmApiKeyStatus.text = "答题 AI：已配置（${savedGlmKey.take(4)}...${savedGlmKey.takeLast(4)}）"
        } else {
            tvGlmApiKeyStatus.text = "答题 AI：未配置（答题任务会跳过）"
        }

        btnSaveGlmApiKey.setOnClickListener {
            val key = etGlmApiKey.text.toString().trim()
            if (key.isNotEmpty()) {
                com.bbncbot.automation.QuizAnswerClient.saveApiKey(this, key)
                tvGlmApiKeyStatus.text = "答题 AI：已配置（${key.take(4)}...${key.takeLast(4)}）"
                Toast.makeText(this, "GLM API Key 已保存", Toast.LENGTH_SHORT).show()
            } else {
                com.bbncbot.automation.QuizAnswerClient.saveApiKey(this, "")
                tvGlmApiKeyStatus.text = "答题 AI：未配置"
                Toast.makeText(this, "GLM API Key 已清除", Toast.LENGTH_SHORT).show()
            }
        }

        // 立即测试上传：手动触发，方便用户快速验证 Token/网络/权限是否正常
        btnTestUpload.setOnClickListener {
            // 自动保存输入框中的 Token（避免用户填了 Token 但没点保存就测试）
            val tokenInBox = etGhToken.text.toString().trim()
            if (tokenInBox.isNotEmpty()) {
                com.bbncbot.automation.LogUploader.saveToken(this, tokenInBox)
                tvGhTokenStatus.text = "日志上传：已配置（${tokenInBox.take(4)}...${tokenInBox.takeLast(4)}）"
            }
            tvUploadResult.text = "上传中..."
            // 后台线程执行（含网络 IO），完成后回主线程更新 UI
            Thread {
                val n = com.bbncbot.automation.LogUploader.upload(this, "test")
                val msg = com.bbncbot.automation.LogUploader.lastResult
                runOnUiThread {
                    tvUploadResult.text = if (n > 0) {
                        "✓ $msg\n查看：https://github.com/sicauyanglei/bbncbot/tree/main/logs"
                    } else {
                        "✗ $msg"
                    }
                    // 提示用户日志已上传
                    if (n > 0) {
                        android.widget.Toast.makeText(
                            this, "日志已上传（logs/ 目录）",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }.start()
        }

        btnAccessibility.setOnClickListener {
            PermissionUtils.openAccessibilitySettings(this)
        }
        btnOverlay.setOnClickListener {
            PermissionUtils.requestOverlayPermission(this)
        }
        btnStartFloating.setOnClickListener {
            onStartFloatingClicked()
        }
        btnOpenFarm.setOnClickListener {
            openFarmInUcBrowser()
        }
        btnOpenAlipayFarm.setOnClickListener {
            openApp("com.eg.android.AlipayGphone", "支付宝")
        }
        btnOpenTaobaoFarm.setOnClickListener {
            openApp("com.taobao.taobao", "淘宝")
        }

        // 解析 shortcut intent 中的平台参数
        pendingPlatform = intent?.getStringExtra(EXTRA_PLATFORM)

        // Android 13+ 申请通知权限（系统对话框，不跳设置页）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        // 其余需要跳系统设置页的权限（悬浮窗/无障碍/默认桌面）在 onResume 串行引导
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        // singleTask 模式下复用 Activity，从桌面快捷方式再次进入会走这里
        pendingPlatform = intent?.getStringExtra(EXTRA_PLATFORM)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        // 消费待执行的平台快捷方式
        val platform = pendingPlatform
        if (platform != null) {
            pendingPlatform = null
            debugLog("onResume consume: platform=$platform")
            when (platform) {
                "uc" -> openFarmInUcBrowser()
                "alipay" -> openApp("com.eg.android.AlipayGphone", "支付宝")
                "taobao" -> openApp("com.taobao.taobao", "淘宝")
            }
            return
        }
        // 无待执行平台时，串行引导缺失权限（悬浮窗 → 无障碍 → 默认桌面）
        guideNextMissingPermission()
    }

    /**
     * 串行引导缺失权限：按优先级找第一个缺失项并跳设置页申请
     * - 同一个缺失项不重复跳（避免从设置页返回未授权时反复弹）
     * - 上一项授权后会自动引导下一项（lastGuidedPermission 会随缺失项变化更新）
     * - 通知权限已在 onCreate 用系统对话框申请，不在此处理
     */
    private fun guideNextMissingPermission() {
        val missing = when {
            !PermissionUtils.canDrawOverlays(this) -> "overlay"
            !PermissionUtils.isAccessibilityEnabled(this, FarmAccessibilityService::class.java) -> "accessibility"
            !FarmShortcutLauncher.isDefaultLauncher(this) -> "launcher"
            else -> null
        }
        if (missing == null) {
            if (lastGuidedPermission != null) {
                Toast.makeText(this, "所有权限已就绪", Toast.LENGTH_SHORT).show()
                lastGuidedPermission = null
            }
            return
        }
        // 与上次引导的是同一项，不重复跳设置页（用户可能正在设置或已主动返回）
        if (missing == lastGuidedPermission) return
        lastGuidedPermission = missing
        when (missing) {
            "overlay" -> {
                Toast.makeText(this, "需要悬浮窗权限以显示控制按钮，请授予", Toast.LENGTH_LONG).show()
                PermissionUtils.requestOverlayPermission(this)
            }
            "accessibility" -> {
                Toast.makeText(this, "需要开启无障碍服务以自动操作，请开启", Toast.LENGTH_LONG).show()
                PermissionUtils.openAccessibilitySettings(this)
            }
            "launcher" -> {
                Toast.makeText(this, "请将本应用设为默认桌面，以便直接打开芭芭农场", Toast.LENGTH_LONG).show()
                FarmShortcutLauncher.requestDefaultLauncher(this)
            }
        }
    }

    private fun onStartFloatingClicked() {
        if (!PermissionUtils.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.toast_overlay_required, Toast.LENGTH_LONG).show()
            PermissionUtils.requestOverlayPermission(this)
            return
        }
        val intent = Intent(this, FloatingWindowService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, R.string.toast_floating_started, Toast.LENGTH_SHORT).show()
    }

    /** 尝试用 UC 浏览器打开芭芭农场页面 */
    private fun openFarmInUcBrowser() {
        // 优先用桌面快捷方式打开（等同点击桌面"芭芭农场"组件）
        if (FarmShortcutLauncher.startFarmShortcut(this, Platform.UC) { msg -> debugLog("FarmShortcut: $msg") }) {
            Toast.makeText(this, "已从桌面快捷方式打开 UC 芭芭农场", Toast.LENGTH_SHORT).show()
            return
        }
        debugLog("openFarmInUcBrowser: shortcut unavailable, fallback to UC browser")
        val uri = Uri.parse(FARM_URL)
        // 尝试 UC 极速版 / UC 浏览器
        val ucPackages = listOf("com.ucmobile.lite", "com.UCMobile.x86", "com.UCMobile")
        for (pkg in ucPackages) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage(pkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                Toast.makeText(this, "正在用 UC 浏览器打开芭芭农场", Toast.LENGTH_SHORT).show()
                return
            } catch (_: ActivityNotFoundException) {
                // 该包名未安装，继续尝试下一个
            }
        }
        // 没有找到 UC 浏览器，用默认浏览器打开
        try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Toast.makeText(this, "未找到 UC 浏览器，已用默认浏览器打开", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开浏览器：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 启动指定包名的 App（支付宝/淘宝等）并进入芭芭农场
     * - 优先用 [PlatformConfig.farmDeepLink] 直达农场页（等同桌面快捷方式进入）
     * - 无 deep link 时回退：启动 App + 无障碍服务自动导航到芭芭农场页
     * - 需要：1) 无障碍服务已开启 2) 前台 App 平台被正确识别
     */
    private fun openApp(packageName: String, label: String) {
        val platform = Platform.fromPackage(packageName)
        // 优先用桌面快捷方式打开（等同点击桌面"芭芭农场"组件）
        if (FarmShortcutLauncher.startFarmShortcut(this, platform) { msg -> debugLog("FarmShortcut: $msg") }) {
            Toast.makeText(this, "已从桌面快捷方式打开 $label 芭芭农场", Toast.LENGTH_SHORT).show()
            return
        }
        debugLog("openApp: shortcut unavailable for $platform, fallback to deep link / navigation")
        // 快捷方式失败（未设默认桌面/未找到快捷方式），走 deep link / 启动 App 前
        // 也先 kill 目标平台老进程，确保进入干净的农场主页
        killPlatformApps(platform)
        val deepLink = platform.config.farmDeepLink
        // 优先用 deep link 直达农场页（等同从桌面快捷方式进入）
        if (deepLink != null) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                Toast.makeText(this, "正在用 $label 打开芭芭农场...", Toast.LENGTH_SHORT).show()
                return
            } catch (e: Exception) {
                // deep link 失败，回退到启动 App + 导航
                Toast.makeText(this, "直达链接失败，回退启动 $label...", Toast.LENGTH_SHORT).show()
            }
        }
        // 无 deep link：启动 App + 无障碍服务自动导航
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            Toast.makeText(this, "未安装 $label（$packageName）", Toast.LENGTH_LONG).show()
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法启动 $label：${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(
            this,
            "已打开 $label，正在自动导航到芭芭农场...",
            Toast.LENGTH_LONG
        ).show()
        // 延迟 2 秒等待 App 启动 + 无障碍服务识别平台，然后触发自动导航
        Handler(Looper.getMainLooper()).postDelayed({
            val svc = FarmAccessibilityService.getInstance()
            if (svc == null) {
                Toast.makeText(
                    this,
                    "无障碍服务未开启，无法自动导航，请先开启无障碍服务",
                    Toast.LENGTH_LONG
                ).show()
                return@postDelayed
            }
            val started = svc.navigateToFarm()
            if (!started) {
                Toast.makeText(
                    this,
                    "未识别到 $label 平台，请在 App 内手动打开芭芭农场",
                    Toast.LENGTH_LONG
                ).show()
            }
        }, 2000L)
    }

    /**
     * kill 指定平台的所有后台进程
     * - 通过 [android.app.ActivityManager.killBackgroundProcesses] 结束目标 App 后台进程
     * - 启动农场前先 kill 老进程，确保回到干净的农场主页（避免旧 Activity 栈残留）
     * - 普通 App 无 FORCE_STOP_PACKAGES 权限，killBackgroundProcesses 是可用最强手段
     */
    private fun killPlatformApps(platform: Platform? = null) {
        val am = getSystemService(ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return
        // platform 为 null 时 kill 所有支持的农场平台（UC/支付宝/淘宝）
        val platforms = if (platform != null) listOf(platform) else listOf(Platform.UC, Platform.ALIPAY, Platform.TAOBAO)
        for (p in platforms) {
            for (pkg in p.config.packageNames) {
                try {
                    am.killBackgroundProcesses(pkg)
                    debugLog("killPlatformApps: killed $pkg for $p")
                } catch (e: Exception) {
                    debugLog("killPlatformApps: failed to kill $pkg, ${e.message}")
                }
            }
        }
    }

    /** 调试日志写到外部存储文件（华为 logcat 加密，用文件替代） */
    private fun debugLog(msg: String) {
        Log.i(TAG, msg)
        try {
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
            val line = "$timestamp $msg\n"
            val file = java.io.File(getExternalFilesDir(null), "debug.log")
            file.appendText(line)
        } catch (_: Exception) { /* ignore */ }
    }

    private fun refreshStatus() {
        val overlayOk = PermissionUtils.canDrawOverlays(this)
        val accessibilityOk = PermissionUtils.isAccessibilityEnabled(this, FarmAccessibilityService::class.java)
        val sb = StringBuilder()
        sb.append(getString(R.string.status_overlay, if (overlayOk) "✓" else "✗"))
        sb.append("\n")
        sb.append(getString(R.string.status_accessibility, if (accessibilityOk) "✓" else "✗"))
        tvStatus.text = sb.toString()
        btnStartFloating.isEnabled = overlayOk
    }
}
