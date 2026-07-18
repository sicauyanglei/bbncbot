package com.bbncbot.automation

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志上传到 GitHub 仓库
 *
 * 用途：把本地 debug.log 日志上传到 GitHub 仓库，便于开发者远程查看手机运行日志（无需用户手动复制）。
 *
 * 上传内容：
 * - 文本日志（logs/）：debug.log
 *
 * 实现方式：GitHub Contents API（PUT 单文件，自动 commit）
 * - 文件路径：`{子目录}/{prefix}_{timestamp}.{ext}`，时间戳防覆盖
 * - 鉴权：用户在 MainActivity 填入的 GitHub Personal Access Token（仅本地存储）
 *
 * 触发时机：由用户在 MainActivity 点击"立即测试上传日志"手动触发（后台线程，不阻塞 UI）。
 *
 * 日志维护：
 * - app 启动时由 [clearLogsOnAppStart] 清理旧日志
 *
 * 安全说明：
 * - 仓库为 public，上传的日志内容（设备型号、操作记录）会公开，仅包含调试信息
 * - Token 不写入代码，由用户输入并存于 SharedPreferences
 * - 建议用 fine-grained PAT 仅授权 contents:write 给本仓库
 */
object LogUploader {

    private const val TAG = "LogUploader"

    /** 仓库所有者/名称（写死，因为本项目固定上传到此仓库） */
    private const val REPO_OWNER = "sicauyanglei"
    private const val REPO_NAME = "bbncbot"

    /** SharedPreferences 名称（与 MainActivity 一致） */
    private const val PREFS_NAME = "github_config"
    private const val KEY_TOKEN = "gh_token"

    /** 单个日志文件大小上限（2MB），超过则截断保留末尾部分 */
    private const val MAX_LOG_BYTES = 2L * 1024 * 1024

    /**
     * 日志文件本地根目录（App 私有外部存储，无需任何权限）
     *
     * 重要：必须用 Context.getExternalFilesDir(null)，不能用 Environment.getExternalStorageDirectory()。
     * 原因：Android 11+ (API 30+) 严格限制外部存储访问，App 无权访问
     * /sdcard/Android/data/{pkg}/files/ 路径（除非用 SAF/SAF DocumentFile）。
     * getExternalFilesDir 返回的路径（/sdcard/Android/data/{pkg}/files/）虽然物理路径相同，
     * 但通过 Context 访问时 App 拥有完全读写权限，无需 MANAGE_EXTERNAL_STORAGE 权限。
     *
     * 注意：所有写日志的代码（Service/Controller/Activity）必须用同一路径，否则会出现
     * "写入方写到 A 路径，LogUploader 读 B 路径读不到文件" 的 bug。
     */
    fun getLogDir(context: Context): File {
        return context.getExternalFilesDir(null) ?: File(context.filesDir, "external").also {
            it.mkdirs()
        }
    }

    /** 获取 debug.log 文件（必须传入 Context 以使用正确的私有目录） */
    fun getDebugLogFile(context: Context): File = File(getLogDir(context), "debug.log")

    /** 上次操作结果（供 UI 测试按钮显示，避免用户看不到失败原因） */
    @Volatile
    var lastResult: String = "未执行过上传"
        private set

    /** 保存 GitHub Token（由 MainActivity 调用） */
    fun saveToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TOKEN, token.trim())
            .apply()
    }

    /** 读取已保存的 GitHub Token */
    fun loadToken(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, "") ?: ""
    }

    /**
     * 上传 debug.log 日志到 GitHub
     *
     * 大文件处理：超过 [MAX_LOG_BYTES] 的日志文件会截断保留末尾部分（最近的日志更有诊断价值）。
     *
     * 必须在后台线程调用（含网络 IO）。
     *
     * @param context 任意 Context
     * @param tag 文件名前缀标记，如 "test"
     * @return 上传成功文件数（0=未配置 token 或全部失败）
     */
    fun upload(context: Context, tag: String): Int {
        val token = loadToken(context)
        if (token.isEmpty()) {
            lastResult = "未配置 GitHub Token，跳过上传。请在上方输入框填入 Token 后点保存。"
            Log.d(TAG, "skip upload: token not configured")
            return 0
        }

        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        var success = 0
        val errors = mutableListOf<String>()

        // 上传 debug.log
        val debugLogFile = getDebugLogFile(context)
        val uploadFile = prepareLogForUpload(debugLogFile)
        if (uploadFile == null) {
            errors.add("${debugLogFile.name}: 文件不存在或为空")
        } else {
            val remotePath = "logs/debug_${tag}_${ts}.log"
            val (ok, err) = uploadFile(token, remotePath, uploadFile, "upload ${debugLogFile.name} ($tag)")
            if (ok) success++ else errors.add("${debugLogFile.name}: $err")
            // 清理临时截断文件
            if (uploadFile !== debugLogFile) uploadFile.delete()
        }

        lastResult = if (errors.isEmpty()) {
            "上传成功：$success 个文件（tag=$tag）"
        } else {
            "成功 $success 个，失败：${errors.joinToString("; ")}"
        }
        Log.i(TAG, "upload done: $success files uploaded (tag=$tag), errors=$errors")
        return success
    }

    /**
     * 准备日志文件用于上传
     *
     * - 文件不存在或为空 → 返回 null
     * - 文件大小超过 [MAX_LOG_BYTES] → 截断保留末尾部分，返回临时文件
     * - 正常大小 → 返回原文件
     *
     * @return 用于上传的文件（可能是原文件或临时截断文件），null 表示不可上传
     */
    private fun prepareLogForUpload(file: File): File? {
        if (!file.exists() || file.length() == 0L) return null
        if (file.length() <= MAX_LOG_BYTES) return file
        // 截断：保留末尾 MAX_LOG_BYTES 的内容（最近的日志更有诊断价值）
        return try {
            val bytes = file.readBytes()
            val truncatedBytes = bytes.copyOfRange((bytes.size - MAX_LOG_BYTES).toInt(), bytes.size)
            val tmpFile = File(file.parentFile, "${file.nameWithoutExtension}_truncated.tmp")
            tmpFile.writeBytes(truncatedBytes)
            Log.i(TAG, "log truncated: ${file.name} ${file.length()} -> ${tmpFile.length()} bytes")
            tmpFile
        } catch (e: Exception) {
            Log.w(TAG, "truncate ${file.name} failed: ${e.message}, using original")
            file
        }
    }

    /**
     * 清理旧日志文件（app 启动时调用）
     *
     * - debug.log 在此清空（写入版本标识）
     *
     * @param context 用于读取版本号
     */
    fun clearLogsOnAppStart(context: Context) {
        val versionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (e: Exception) { "?" }
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val header = "=== cleared on app start (version=$versionName, time=$timeStr) ===\n"

        // 清空追加型日志文件（写入版本标识，保留文件存在性）
        try {
            val debugLogFile = getDebugLogFile(context)
            debugLogFile.parentFile?.mkdirs()
            debugLogFile.writeText(header)
        } catch (e: Exception) {
            Log.w(TAG, "clear debug.log failed: ${e.message}")
        }
        Log.i(TAG, "logs cleared on app start: debug.log")
    }

    /**
     * 调用 GitHub Contents API 上传单个文件
     *
     * API: PUT https://api.github.com/repos/{owner}/{repo}/contents/{path}
     * - 创建或更新文件，每次调用都会产生一个新 commit
     * - 文件内容需 Base64 编码
     *
     * @return (是否成功, 失败原因描述)
     */
    private fun uploadFile(
        token: String,
        remotePath: String,
        localFile: File,
        commitMsg: String
    ): Pair<Boolean, String> {
        var conn: java.net.HttpURLConnection? = null
        try {
            val url = java.net.URL(
                "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/contents/$remotePath"
            )
            conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "PUT"
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                // GitHub API 强制要求 User-Agent，否则返回 403
                setRequestProperty("User-Agent", "bbncbot-app")
                doOutput = true
            }

            // 读取文件 → Base64（GitHub Contents API 要求 Base64 编码）
            // 用 NO_WRAP 避免 Android Base64 默认每 76 字符插入换行
            val fileBytes = localFile.readBytes()
            val base64Content = Base64.encodeToString(fileBytes, Base64.NO_WRAP)

            // 构造 JSON body（用 org.json.JSONObject 简单拼，避免引入 Gson）
            val jsonBody = org.json.JSONObject().apply {
                put("message", commitMsg)
                put("content", base64Content)
                // 分支不指定 → 默认 push 到仓库默认分支（main）
            }.toString()

            conn.outputStream.use { os ->
                os.write(jsonBody.toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            return when {
                code in 200..299 -> {
                    Log.i(TAG, "uploaded: $remotePath (${fileBytes.size} bytes)")
                    Pair(true, "")
                }
                code == 401 -> Pair(false, "401 Token 无效或已过期")
                code == 403 -> Pair(false, "403 Token 权限不足（需 contents:write）或触发限流")
                code == 404 -> Pair(false, "404 仓库不存在或 Token 无权访问")
                else -> {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    Log.e(TAG, "upload failed $code: $remotePath\n$err")
                    Pair(false, "HTTP $code: ${err.take(200)}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "upload exception: ${e.message}", e)
            return Pair(false, "异常: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }
}
