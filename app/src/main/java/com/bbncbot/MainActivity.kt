package com.bbncbot

import android.Manifest
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.bbncbot.service.FarmAccessibilityService
import com.bbncbot.service.FloatingWindowService
import com.bbncbot.util.PermissionUtils

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val FARM_URL = "https://broccoli.uc.cn/apps/ucfarm/routes/farm"
        const val EXTRA_PLATFORM = "extra_platform"
        const val EXTRA_USER = "extra_user"
    }

    private lateinit var tvStatus: TextView
    private lateinit var btnAccessibility: Button
    private lateinit var btnOverlay: Button
    private lateinit var btnStartFloating: Button
    private lateinit var btnOpenFarm: Button
    private lateinit var btnOpenAlipayFarm: Button
    private lateinit var btnOpenTaobaoFarm: Button
    private lateinit var etApiKey: EditText
    private lateinit var btnSaveApiKey: Button
    private lateinit var tvApiKeyStatus: TextView
    private lateinit var spinnerApiProvider: Spinner
    private lateinit var tvProviderQuota: TextView
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

    /** 待执行的用户类型："main" 主账号 / "clone" 分身 */
    private var pendingUser: String = "main"

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
        etApiKey = findViewById(R.id.etApiKey)
        btnSaveApiKey = findViewById(R.id.btnSaveApiKey)
        tvApiKeyStatus = findViewById(R.id.tvApiKeyStatus)
        spinnerApiProvider = findViewById(R.id.spinnerApiProvider)
        tvProviderQuota = findViewById(R.id.tvProviderQuota)
        etGhToken = findViewById(R.id.etGhToken)
        btnSaveGhToken = findViewById(R.id.btnSaveGhToken)
        tvGhTokenStatus = findViewById(R.id.tvGhTokenStatus)
        btnTestUpload = findViewById(R.id.btnTestUpload)
        tvUploadResult = findViewById(R.id.tvUploadResult)

        // 初始化 API 提供商下拉选择
        val providers = com.bbncbot.automation.AiVisionHelper.ApiProvider.displayNames
        spinnerApiProvider.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, providers
        )

        // 加载已保存的配置
        val prefs = getSharedPreferences("ai_config", Context.MODE_PRIVATE)
        val savedKey = prefs.getString("api_key", "") ?: ""
        val savedProviderIndex = prefs.getInt("provider_index", 0)

        spinnerApiProvider.setSelection(savedProviderIndex.coerceIn(0, providers.size - 1))
        updateProviderInfo(savedProviderIndex)

        if (savedKey.isNotEmpty()) {
            etApiKey.setText(savedKey)
            com.bbncbot.automation.AiVisionHelper.apiKey = savedKey
            com.bbncbot.automation.AiVisionHelper.provider =
                com.bbncbot.automation.AiVisionHelper.ApiProvider.entries[savedProviderIndex.coerceIn(0, providers.size - 1)]
            tvApiKeyStatus.text = "AI 视觉识别：已配置 (${com.bbncbot.automation.AiVisionHelper.provider.displayName})"
        }

        // 提供商切换时更新免费额度说明
        spinnerApiProvider.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                updateProviderInfo(position)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        btnSaveApiKey.setOnClickListener {
            val key = etApiKey.text.toString().trim()
            val providerIndex = spinnerApiProvider.selectedItemPosition
            val selectedProvider = com.bbncbot.automation.AiVisionHelper.ApiProvider.entries[providerIndex]

            if (key.isNotEmpty()) {
                getSharedPreferences("ai_config", Context.MODE_PRIVATE)
                    .edit()
                    .putString("api_key", key)
                    .putInt("provider_index", providerIndex)
                    .apply()
                com.bbncbot.automation.AiVisionHelper.apiKey = key
                com.bbncbot.automation.AiVisionHelper.provider = selectedProvider
                tvApiKeyStatus.text = "AI 视觉识别：已配置 (${selectedProvider.displayName})"
                Toast.makeText(this, "API Key 已保存 (${selectedProvider.displayName})", Toast.LENGTH_SHORT).show()
            } else {
                getSharedPreferences("ai_config", Context.MODE_PRIVATE)
                    .edit().clear().apply()
                com.bbncbot.automation.AiVisionHelper.apiKey = ""
                tvApiKeyStatus.text = "AI 视觉识别：未配置"
                Toast.makeText(this, "API Key 已清除", Toast.LENGTH_SHORT).show()
            }
        }

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
            openFarmInUcBrowser(isClone = false)
        }
        btnOpenAlipayFarm.setOnClickListener {
            openApp("com.eg.android.AlipayGphone", "支付宝", isClone = false)
        }
        btnOpenTaobaoFarm.setOnClickListener {
            openApp("com.taobao.taobao", "淘宝", isClone = false)
        }

        // 解析 shortcut intent 中的平台参数
        pendingPlatform = intent?.getStringExtra(EXTRA_PLATFORM)
        pendingUser = intent?.getStringExtra(EXTRA_USER) ?: "main"

        // Android 13+ 申请通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        // singleTask 模式下复用 Activity，从桌面快捷方式再次进入会走这里
        pendingPlatform = intent?.getStringExtra(EXTRA_PLATFORM)
        pendingUser = intent?.getStringExtra(EXTRA_USER) ?: "main"
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        // 消费待执行的平台快捷方式
        val platform = pendingPlatform
        if (platform != null) {
            pendingPlatform = null
            val isClone = pendingUser == "clone"
            debugLog("onResume consume: platform=$platform, user=$pendingUser, isClone=$isClone")
            pendingUser = "main"
            when (platform) {
                "uc" -> openFarmInUcBrowser(isClone = isClone)
                "alipay" -> openApp("com.eg.android.AlipayGphone", "支付宝", isClone = isClone)
                "taobao" -> openApp("com.taobao.taobao", "淘宝", isClone = isClone)
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
    private fun openFarmInUcBrowser(isClone: Boolean = false) {
        if (isClone) {
            // 分身 UC：用 LauncherApps 启动分身 UC（无法跨 user 传 URL，启动后由用户手动进入芭芭农场）
            val userHandle = findCloneUserHandle()
            // 设置 pendingAppLabel 给无障碍服务，用于自动处理华为 HwChooserActivity 选择器
            FarmAccessibilityService.getInstance()?.setPendingApp("UC 浏览器", "clone")
            if (userHandle != null && launchAppForUser("com.ucmobile.lite", "UC 浏览器", userHandle)) {
                Toast.makeText(
                    this,
                    "已打开 UC 浏览器（分身），请手动进入芭芭农场",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }
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
     * 启动指定包名的 App（支付宝/淘宝等）
     * - 启动后通过无障碍服务自动导航到芭芭农场页面（点击主页上的"芭芭农场"标签）
     * - 需要：1) 无障碍服务已开启 2) 前台 App 平台被正确识别
     * @param isClone true 时启动分身 App（work profile user 128）
     */
    private fun openApp(packageName: String, label: String, isClone: Boolean = false) {
        val userHandle = if (isClone) findCloneUserHandle() else Process.myUserHandle()
        if (userHandle == null) {
            Toast.makeText(this, "未找到 $label 分身，请确认分身已创建", Toast.LENGTH_LONG).show()
            return
        }
        // 设置 pendingAppLabel 给无障碍服务，用于自动处理华为 HwChooserActivity 选择器
        val service = FarmAccessibilityService.getInstance()
        service?.setPendingApp(label, if (isClone) "clone" else "main")
        if (!launchAppForUser(packageName, label, userHandle)) {
            return
        }
        val suffix = if (isClone) "（分身）" else ""
        Toast.makeText(
            this,
            "已打开 $label$suffix，正在自动导航到芭芭农场...",
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
                    "未识别到 $label$suffix 平台，请在 App 内手动打开芭芭农场",
                    Toast.LENGTH_LONG
                ).show()
            }
        }, 2000L)
    }

    /**
     * 查找分身 user handle（work profile，非当前 user）
     * @return 第一个非当前 user 的 profile，找不到返回 null
     */
    private fun findCloneUserHandle(): UserHandle? {
        val userManager = getSystemService(Context.USER_SERVICE) as UserManager
        val currentUser = Process.myUserHandle()
        val profiles = userManager.userProfiles
        debugLog("findCloneUserHandle: profiles=${profiles.size}, currentUser=$currentUser")
        for (profile in profiles) {
            if (profile != currentUser) {
                debugLog("found clone profile=$profile")
                return profile
            }
        }
        debugLog("no clone profile found")
        return null
    }

    /**
     * 通过 LauncherApps 启动指定 user 下的 App
     *
     * 关键：主账号和分身都用 [LauncherApps.startMainActivity]，
     * 直接指定 ComponentName + UserHandle 启动，绕过华为 HwChooserActivity 选择器
     * （华为在有原 App+分身时，startActivity(Intent setComponent) 会被 HwChooserActivity 拦截）
     *
     * @return true 启动成功，false 失败
     */
    private fun launchAppForUser(packageName: String, label: String, user: UserHandle): Boolean {
        debugLog("launchAppForUser start: pkg=$packageName, user=$user")
        val launcherApps = getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val activities = launcherApps.getActivityList(packageName, user)
        debugLog("getActivityList returned ${activities?.size ?: "null"} activities in $user")
        if (activities.isNullOrEmpty()) {
            Toast.makeText(this, "未安装 $label（$packageName）", Toast.LENGTH_LONG).show()
            return false
        }
        val activity = activities[0]
        val componentName = activity.componentName
        debugLog("found $componentName in $user")
        val isMainUser = user == Process.myUserHandle()
        return try {
            // 统一用 startMainActivity 启动，绕过华为选择器
            launcherApps.startMainActivity(componentName, user, null, null)
            debugLog("startMainActivity SUCCESS (${if (isMainUser) "main" else "clone"}) for $componentName")
            true
        } catch (e: SecurityException) {
            debugLog("SecurityException: ${e.message}")
            Toast.makeText(this, "无法启动 $label（权限不足）：${e.message}", Toast.LENGTH_LONG).show()
            false
        } catch (e: Exception) {
            debugLog("Exception: ${e.javaClass.simpleName}: ${e.message}")
            Toast.makeText(this, "无法启动 $label：${e.message}", Toast.LENGTH_LONG).show()
            false
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

    /** 更新选中 API 提供商的免费额度说明 */
    private fun updateProviderInfo(index: Int) {
        val providers = com.bbncbot.automation.AiVisionHelper.ApiProvider.entries
        if (index in providers.indices) {
            val provider = providers[index]
            tvProviderQuota.text = "免费额度：${provider.freeQuotaDesc}"
        }
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
