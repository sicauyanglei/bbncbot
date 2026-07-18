package com.bbncbot.automation

import android.content.Context
import android.util.Base64
import android.util.Log
import com.bbncbot.BuildConfig
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
     * 把诊断日志写入 debug.log 文件（同时也输出到 logcat）
     *
     * 必要性：LogUploader 的诊断信息（token 首尾、HTTP 响应码、GitHub 错误体）只写 logcat 时，
     * 用户无法通过分享 debug.log 把这些信息发给我，难以远程诊断"401 失败"等问题。
     * 因此关键诊断信息也写入 debug.log，与 Service/Controller 的日志格式一致。
     */
    private fun debugLog(context: Context, msg: String) {
        Log.i(TAG, msg)
        try {
            val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            val line = "$timestamp $msg\n"
            val file = getDebugLogFile(context)
            file.parentFile?.mkdirs()
            file.appendText(line)
        } catch (_: Exception) { /* ignore */ }
    }

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

    /**
     * 清理 Token 字符串
     *
     * 问题背景：用户从聊天软件/网页复制 Token 粘贴时，可能带入：
     * - 零宽空格（U+200B）、不换行空格（U+00A0）、BOM（U+FEFF）等不可见字符
     * - 换行符、制表符（多行复制时）
     * - 首尾空白
     *
     * 这些字符肉眼看不见，但会随 Token 一起发给 GitHub → 401 Bad credentials。
     * 标准 .trim() 只去除 ASCII 空白，无法处理 Unicode 不可见字符。
     *
     * GitHub Token 合法字符集（PAT/classic）：
     * - 前缀：github_pat_ / ghp_ / gho_ / ghu_ / ghs_ / ghr_
     * - 内容：仅 A-Z a-z 0-9 _ -
     *
     * 处理策略：保留 Token 中所有合法字符（字母/数字/下划线/连字符），剔除其他所有字符。
     * 这样即使粘贴带不可见字符也能恢复成正确 Token。
     */
    private fun sanitizeToken(raw: String): String {
        // 1. 去除首尾 ASCII 空白
        var t = raw.trim()
        // 2. 剔除所有非合法字符（仅保留 A-Za-z0-9_-）
        //    这会清掉零宽空格、BOM、NBSP、换行、引号等所有不可见/非法字符
        t = t.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        return t
    }

    /** 保存 GitHub Token（由 MainActivity 调用） */
    fun saveToken(context: Context, token: String) {
        val cleaned = sanitizeToken(token)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TOKEN, cleaned)
            .apply()
        // 诊断日志：保存后立即打印 token 长度和首尾，方便用户对比输入和保存是否一致
        // 同时写入 debug.log 文件，便于远程诊断
        if (cleaned.isNotEmpty()) {
            debugLog(context, "saveToken: saved (len=${cleaned.length}, head='${cleaned.take(4)}', tail='${cleaned.takeLast(4)}')")
        } else {
            debugLog(context, "saveToken: cleared")
        }
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
            debugLog(context, "upload: skip, token not configured")
            return 0
        }

        // 诊断日志：打印 token 前后 4 位、长度、字符集（用于排查"App 端 token 与 curl 测试不一致"问题）
        // 不泄露完整 token，只打印首尾各 4 字符 + 总长度
        // 同时写入 debug.log，方便用户分享日志后远程诊断
        val tokenDiag = "len=${token.length}, head='${token.take(4)}', tail='${token.takeLast(4)}', " +
            "hasNewline=${token.contains('\n') || token.contains('\r')}, " +
            "hasSpace=${token.contains(' ') || token.contains('\t')}"
        debugLog(context, "upload start: tag=$tag, token=***$tokenDiag")

        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        var success = 0
        val errors = mutableListOf<String>()

        // 上传 debug.log
        val debugLogFile = getDebugLogFile(context)
        val uploadFile = prepareLogForUpload(debugLogFile)
        if (uploadFile == null) {
            errors.add("${debugLogFile.name}: 文件不存在或为空")
            debugLog(context, "upload: debug.log not exist or empty, skip")
        } else {
            val remotePath = "logs/debug_${tag}_${ts}.log"
            val (ok, err) = uploadFile(context, token, remotePath, uploadFile, "upload ${debugLogFile.name} ($tag)")
            if (ok) {
                success++
                debugLog(context, "upload: success, remotePath=$remotePath")
            } else {
                // 失败时把 token 诊断信息附在错误后面，方便用户对比 App 端 token 与 curl 用的 token 是否一致
                errors.add("${debugLogFile.name}: $err [token: $tokenDiag]")
                debugLog(context, "upload: FAILED, err=$err, tokenDiag=$tokenDiag")
            }
            // 清理临时截断文件
            if (uploadFile !== debugLogFile) uploadFile.delete()
        }

        lastResult = if (errors.isEmpty()) {
            "上传成功：$success 个文件（tag=$tag）"
        } else {
            "成功 $success 个，失败：${errors.joinToString("; ")}"
        }
        debugLog(context, "upload done: $success files uploaded (tag=$tag), errors=$errors")
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
        // BUILD_LABEL 来自 BuildConfig，CI 构建时注入（如 build500-3c9691b），本地构建为 "local"
        // 写入 header 用于远程诊断时确认用户实际运行的 APK 版本
        val buildLabel = try { BuildConfig.BUILD_LABEL } catch (_: Throwable) { "unknown" }
        val header = "=== cleared on app start (version=$versionName, build=$buildLabel, time=$timeStr) ===\n"

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
        context: Context,
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
            debugLog(context, "uploadFile: PUT $url, localFile=${localFile.name} (${localFile.length()} bytes)")
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
            // 读取 GitHub 返回的错误响应体（所有失败码都读，便于诊断）
            // GitHub 401 响应体通常含 {"message":"Bad credentials",...}，
            // 403 含 {"message":"Resource not accessible by personal access token",...}
            // 直接展示给用户，避免"401 Token 无效"这种笼统信息掩盖真实原因
            val errBody = try {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (_: Exception) { "" }
            // 把响应码和 GitHub 原始错误体写入 debug.log，方便远程诊断
            debugLog(context, "uploadFile resp: code=$code, url=$remotePath, errBody=${errBody.take(500)}")
            return when {
                code in 200..299 -> {
                    debugLog(context, "uploaded: $remotePath (${fileBytes.size} bytes)")
                    Pair(true, "")
                }
                code == 401 -> Pair(false, "401 Token 无效或已过期。GitHub 返回: ${errBody.take(200)}")
                code == 403 -> Pair(false, "403 权限不足或限流。GitHub 返回: ${errBody.take(200)}")
                code == 404 -> Pair(false, "404 仓库不存在或 Token 无权访问: ${errBody.take(200)}")
                else -> Pair(false, "HTTP $code: ${errBody.take(200)}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "upload exception: ${e.message}", e)
            debugLog(context, "uploadFile exception: ${e.javaClass.simpleName}: ${e.message}")
            return Pair(false, "异常: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }
}
