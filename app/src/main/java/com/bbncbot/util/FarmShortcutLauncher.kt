package com.bbncbot.util

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import com.bbncbot.automation.Platform

/**
 * 桌面快捷方式启动器：遍历桌面快捷方式找到"芭芭农场"并启动
 *
 * - 利用 [LauncherApps] 枚举所有已安装 App 发布的动态/固定/静态快捷方式
 * - 按 label 匹配目标平台（支付宝/淘宝/UC）+ "芭芭农场"关键字
 * - 找到后用 [LauncherApps.startShortcut] 启动，等同从桌面点击该快捷方式
 *
 * **前置条件：本 App 必须被设为默认桌面**
 * - [LauncherApps.getShortcuts] 和 [LauncherApps.startShortcut] 要求调用方是默认桌面
 * - 非桌面调用会抛 SecurityException，本类已捕获并返回 false
 * - 用 [isDefaultLauncher] 检测，用 [requestDefaultLauncher] 引导用户设置
 */
object FarmShortcutLauncher {

    private const val TAG = "FarmShortcutLauncher"

    /** 快捷方式 label 中用于匹配"芭芭农场"的关键字 */
    private const val FARM_KEYWORD = "芭芭农场"

    /**
     * 检测本 App 是否为默认桌面
     *
     * - 通过比对系统默认桌面 ComponentName 与本 App 的 MainActivity 判断
     * - Android 无直接 API 查询"默认桌面"，需通过 resolveActivity 间接判断
     *
     * @return true 本 App 已是默认桌面
     */
    fun isDefaultLauncher(context: Context): Boolean {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolver = context.packageManager.resolveActivity(homeIntent, 0)
        val isDefault = resolver?.activityInfo?.let { info ->
            info.packageName == context.packageName
        } ?: false
        Log.d(TAG, "isDefaultLauncher: $isDefault (resolver=${resolver?.activityInfo?.packageName})")
        return isDefault
    }

    /**
     * 引导用户把本 App 设为默认桌面
     *
     * - 跳转系统"默认桌面"设置页（如有），否则跳转应用设置
     * - 实测 [Settings.ACTION_HOME_SETTINGS]（API 24+）可打开桌面设置
     */
    fun requestDefaultLauncher(context: Context) {
        val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            Intent(Settings.ACTION_HOME_SETTINGS)
        } else {
            Intent(Settings.ACTION_SETTINGS)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "requestDefaultLauncher: ${e.message}")
        }
    }

    /**
     * 查找并启动目标平台的"芭芭农场"桌面快捷方式
     *
     * 流程：
     * 1. 检测是否为默认桌面，否则返回 false（调用方引导用户设置）
     * 2. 用 [LauncherApps.getShortcuts] 枚举所有快捷方式
     * 3. 按 label 匹配：含"芭芭农场" + 平台关键字（支付宝/淘宝/UC）
     * 4. 用 [LauncherApps.startShortcut] 启动，等同桌面点击
     *
     * @param context 上下文（建议传 Activity）
     * @param platform 目标平台，用于精确匹配快捷方式
     * @return true 已成功发起启动，false 未找到快捷方式或无权限
     */
    fun startFarmShortcut(context: Context, platform: Platform): Boolean {
        if (!isDefaultLauncher(context)) {
            Log.w(TAG, "startFarmShortcut: not default launcher, cannot enumerate shortcuts")
            return false
        }

        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        if (launcherApps == null) {
            Log.w(TAG, "startFarmShortcut: LauncherApps service unavailable")
            return false
        }

        // 枚举所有快捷方式（动态 + 固定 + 静态 manifest）
        val query = LauncherApps.ShortcutQuery().apply {
            setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
            )
        }

        val user: UserHandle = Process.myUserHandle()
        val allShortcuts: List<ShortcutInfo> = try {
            launcherApps.getShortcuts(query, user) ?: emptyList()
        } catch (e: SecurityException) {
            Log.w(TAG, "startFarmShortcut: SecurityException - ${e.message}")
            return false
        } catch (e: Exception) {
            Log.w(TAG, "startFarmShortcut: getShortcuts failed - ${e.message}")
            return false
        }

        Log.d(TAG, "startFarmShortcut: found ${allShortcuts.size} shortcuts total")

        // 匹配目标平台的芭芭农场快捷方式
        val platformKeywords = platformKeyword(platform)
        val matched = allShortcuts.filter { info ->
            val label = info.shortLabel?.toString().orEmpty()
            val longLabel = info.longLabel?.toString().orEmpty()
            val combined = "$label $longLabel"
            // 必须含"芭芭农场"，且匹配平台关键字（或无平台关键字约束时只看"芭芭农场"）
            combined.contains(FARM_KEYWORD) &&
                (platformKeywords.isEmpty() || platformKeywords.any { combined.contains(it) })
        }

        Log.d(TAG, "startFarmShortcut: matched ${matched.size} for platform=$platform")
        for (m in matched) {
            Log.d(TAG, "  matched: pkg=${m.`package`} id=${m.id} label=${m.shortLabel}")
        }

        val target = matched.firstOrNull() ?: return false

        // 启动快捷方式前先 kill 目标平台的老进程
        // 原因：目标 App 可能残留旧实例（旧 Activity 栈/缓存的 H5 页面），
        // 直接启动快捷方式可能只是把旧实例拉到前台，无法回到干净的农场主页
        killPlatformApps(context, platform)

        // 启动快捷方式（等同桌面点击）
        return try {
            val sourceBounds = Rect()
            launcherApps.startShortcut(target.`package`, target.id, sourceBounds, null, user)
            Log.i(TAG, "startFarmShortcut: started shortcut pkg=${target.`package`} id=${target.id}")
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "startFarmShortcut: startShortcut SecurityException - ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "startFarmShortcut: startShortcut failed - ${e.message}")
            false
        }
    }

    /**
     * kill 指定平台的所有后台进程
     *
     * - 用 [ActivityManager.killBackgroundProcesses] 结束目标平台 App 的后台进程
     * - killBackgroundProcesses 只能 kill 后台进程；若目标 App 在前台，需调用方确保已退到后台
     *   （本 App 是默认桌面，startShortcut 前本 App 在前台，目标 App 通常在后台）
     * - 普通 App 无 FORCE_STOP_PACKAGES 权限，这是可用最强手段
     *
     * @param context 上下文
     * @param platform 目标平台
     */
    private fun killPlatformApps(context: Context, platform: Platform) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        // 收集目标平台所有需要 kill 的包名（主包名 + 内部包名前缀对应的所有包）
        // killBackgroundProcesses 需要精确包名，前缀无法直接用，
        // 但主包名 kill 后，同进程组的内部 WebView 子包通常会被一起回收
        val packages = platform.config.packageNames
        for (pkg in packages) {
            try {
                am.killBackgroundProcesses(pkg)
                Log.d(TAG, "killPlatformApps: killed $pkg for $platform")
            } catch (e: Exception) {
                Log.w(TAG, "killPlatformApps: failed to kill $pkg, ${e.message}")
            }
        }
    }

    /**
     * 平台对应的 label 关键字（用于匹配快捷方式）
     * - 空列表表示该平台无额外关键字约束（仅按"芭芭农场"匹配）
     */
    private fun platformKeyword(platform: Platform): List<String> = when (platform) {
        Platform.ALIPAY -> listOf("支付宝")
        Platform.TAOBAO -> listOf("淘宝")
        Platform.UC -> listOf("UC", "uc")
        Platform.UNKNOWN -> emptyList()
    }
}
