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

    /**
     * 待执行的平台快捷方式（来自桌面快捷方式或 shortcut intent）
     * - 在 [onResume] 中消费，避免在 onCreate 阶段权限未就绪时启动
     */
    private var pendingPlatform: String? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 结果忽略，仅尝试申请 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // 清除旧版录制日志，避免旧版本日志混在新版本里导致误判
        com.bbncbot.automation.RecordingManager.clearLogOnAppStart(this)

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

        // 加载已保存的 GitHub Token（日志上传用）
        val savedGhToken = com.bbncbot.automation.LogUploader.loadToken(this)
        if (savedGhToken.isNotEmpty()) {
            etGhToken.setText(savedGhToken)
            tvGhTokenStatus.text = "日志上传：已配置（${savedGhToken.take(4)}...${savedGhToken.takeLast(4)}）"
        } else {
            tvGhTokenStatus.text = "日志上传：未配置（录制停止不会自动上传）"
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

        // 立即测试上传：不依赖录制停止，方便用户快速验证 Token/网络/权限是否正常
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

        // Android 13+ 申请通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // 引导设置默认桌面（首次启动或未设置时弹出）
        // 默认桌面身份是 LauncherApps 枚举/启动桌面快捷方式的前置条件
        if (!FarmShortcutLauncher.isDefaultLauncher(this)) {
            Toast.makeText(this, "请将本应用设为默认桌面，以便直接打开芭芭农场", Toast.LENGTH_LONG).show()
            FarmShortcutLauncher.requestDefaultLauncher(this)
        }
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
        } else {
            debugLog("onResume: no pending platform")
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
        if (FarmShortcutLauncher.startFarmShortcut(this, Platform.UC)) {
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
        if (FarmShortcutLauncher.startFarmShortcut(this, platform)) {
            Toast.makeText(this, "已从桌面快捷方式打开 $label 芭芭农场", Toast.LENGTH_SHORT).show()
            return
        }
        debugLog("openApp: shortcut unavailable for $platform, fallback to deep link / navigation")
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
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
