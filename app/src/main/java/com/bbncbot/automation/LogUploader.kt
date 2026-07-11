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
 * 用途：把本地日志文件上传到 GitHub 仓库，便于开发者远程查看手机运行日志（无需用户手动复制）。
 *
 * 上传内容：
 * - 文本日志（logs/）：recording.log / debug.log / proposals.log / teachings.log
 * - 规则数据（rules/）：scene_rules.json / rules_explanation.txt
 * - 截图样本（sessions/）：最近一次采集会话的截图 + 元数据 JSON
 *
 * 实现方式：GitHub Contents API（PUT 单文件，自动 commit）
 * - 文件路径：`{子目录}/{prefix}_{timestamp}.{ext}`，时间戳防覆盖
 * - 鉴权：用户在 MainActivity 填入的 GitHub Personal Access Token（仅本地存储）
 *
 * 触发时机：每次 [RecordingManager.stop] 录制停止后自动调用（后台线程，不阻塞 UI）。
 *
 * 日志维护：
 * - 上传前自动刷新 rules_explanation.txt（确保文件存在且为最新）
 * - 上传后清理已上传的 session 截图目录（释放磁盘空间）
 * - app 启动时由 [RecordingManager.clearLogOnAppStart] 清理旧日志
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

    /** 上传截图数量上限（避免上传过多截图导致请求过多） */
    private const val MAX_SCREENSHOTS_PER_UPLOAD = 10

    /** 日志文件本地根目录 */
    private val logDir: File by lazy {
        File(
            android.os.Environment.getExternalStorageDirectory(),
            "Android/data/com.bbncbot/files"
        )
    }
    private val recordingLogFile: File get() = File(logDir, "recording.log")
    private val debugLogFile: File get() = File(logDir, "debug.log")
    private val proposalsLogFile: File get() = File(logDir, "proposals.log")
    private val teachingsLogFile: File get() = File(logDir, "teachings.log")
    private val sceneRulesFile: File get() = File(logDir, "scene_rules.json")
    private val rulesExplanationFile: File get() = File(logDir, "rules_explanation.txt")
    private val sessionsDir: File get() = File(logDir, "sessions")

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
     * 上传所有日志到 GitHub
     *
     * 上传内容：
     * 1. 文本日志 → `logs/`：recording.log / debug.log / proposals.log / teachings.log
     * 2. 规则数据 → `rules/`：scene_rules.json / rules_explanation.txt（上传前自动刷新）
     * 3. 截图样本 → `sessions/{tag}/`：最近一次采集会话的截图（最多 [MAX_SCREENSHOTS_PER_UPLOAD] 张）
     *
     * 大文件处理：超过 [MAX_LOG_BYTES] 的日志文件会截断保留末尾部分（最近的日志更有诊断价值）。
     *
     * 必须在后台线程调用（含网络 IO）。
     *
     * @param context 任意 Context
     * @param tag 文件名前缀标记，如 "session_abc123"
     * @return 上传成功文件数（0=未配置 token 或全部失败）
     */
    fun upload(context: Context, tag: String): Int {
        val token = loadToken(context)
        if (token.isEmpty()) {
            lastResult = "未配置 GitHub Token，跳过上传。请在上方输入框填入 Token 后点保存。"
            Log.d(TAG, "skip upload: token not configured")
            return 0
        }

        // 上传前刷新 rules_explanation.txt，确保文件存在且为最新规则状态
        try {
            SceneLibrary.dumpRulesExplanationToFile()
            Log.d(TAG, "rules_explanation.txt refreshed before upload")
        } catch (e: Exception) {
            Log.w(TAG, "refresh rules_explanation.txt failed: ${e.message}")
        }

        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        var success = 0
        val errors = mutableListOf<String>()

        // 1. 文本日志：(本地文件, 远端文件名前缀, 远端子目录)
        val textLogs = listOf(
            Triple(recordingLogFile, "recording", "logs"),
            Triple(debugLogFile, "debug", "logs"),
            Triple(proposalsLogFile, "proposals", "logs"),
            Triple(teachingsLogFile, "teachings", "logs")
        )
        for ((localFile, namePrefix, subDir) in textLogs) {
            val uploadFile = prepareLogForUpload(localFile) ?: run {
                errors.add("${localFile.name}: 文件不存在或为空")
                continue
            }
            val remotePath = "$subDir/${namePrefix}_${tag}_${ts}.log"
            val (ok, err) = uploadFile(token, remotePath, uploadFile, "upload ${localFile.name} ($tag)")
            if (ok) success++ else errors.add("${localFile.name}: $err")
            // 清理临时截断文件
            if (uploadFile !== localFile) uploadFile.delete()
        }

        // 2. 规则数据
        val ruleFiles = listOf(
            Triple(sceneRulesFile, "scene_rules", "rules", "json"),
            Triple(rulesExplanationFile, "rules_explanation", "rules", "txt")
        )
        for ((localFile, namePrefix, subDir, ext) in ruleFiles) {
            if (!localFile.exists() || localFile.length() == 0L) {
                errors.add("${localFile.name}: 文件不存在或为空")
                continue
            }
            val remotePath = "$subDir/${namePrefix}_${tag}_${ts}.$ext"
            val (ok, err) = uploadFile(token, remotePath, localFile, "upload ${localFile.name} ($tag)")
            if (ok) success++ else errors.add("${localFile.name}: $err")
        }

        // 3. 截图样本：上传最近一次 session 的截图（最多 MAX_SCREENSHOTS_PER_UPLOAD 张）
        val screenshotCount = uploadLatestSessionScreenshots(token, tag, ts)
        success += screenshotCount

        lastResult = if (errors.isEmpty()) {
            "上传成功：$success 个文件（tag=$tag，含 $screenshotCount 张截图）"
        } else {
            "成功 $success 个（含 $screenshotCount 张截图），失败：${errors.joinToString("; ")}"
        }
        Log.i(TAG, "upload done: $success files uploaded (tag=$tag, screenshots=$screenshotCount), errors=$errors")
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
     * 上传最近一次采集会话的截图
     *
     * - 找到 sessions/ 下最新修改的会话目录
     * - 上传其中的截图文件（.png）和元数据（.json），最多 [MAX_SCREENSHOTS_PER_UPLOAD] 个
     * - 截图较大，只上传最近的几张（按文件名排序取最后几张，即任务退出前的截图）
     *
     * @return 上传成功的截图文件数
     */
    private fun uploadLatestSessionScreenshots(token: String, tag: String, ts: String): Int {
        if (!sessionsDir.exists()) return 0
        // 找最新的 session 目录
        val sessionDirs = sessionsDir.listFiles()?.filter { it.isDirectory } ?: return 0
        if (sessionDirs.isEmpty()) return 0
        val latestDir = sessionDirs.maxByOrNull { it.lastModified() } ?: return 0

        // 收集所有截图文件（.png）和元数据（.json），按文件名排序
        val allFiles = latestDir.listFiles()?.filter { it.isFile } ?: return 0
        if (allFiles.isEmpty()) return 0

        // 按文件名排序，取最后 MAX_SCREENSHOTS_PER_UPLOAD 个文件（任务退出前的截图最有诊断价值）
        val filesToUpload = allFiles.sortedBy { it.name }.takeLast(MAX_SCREENSHOTS_PER_UPLOAD)
        val remoteSubDir = "sessions/${tag}_${ts}"

        var count = 0
        for (file in filesToUpload) {
            val ext = file.extension.ifEmpty { "bin" }
            val remotePath = "$remoteSubDir/${file.name}"
            val (ok, _) = uploadFile(token, remotePath, file, "upload screenshot ${file.name} ($tag)")
            if (ok) count++
        }
        Log.i(TAG, "session screenshots uploaded: $count from ${latestDir.name}")
        return count
    }

    /**
     * 清理过期的 session 截图目录
     *
     * - 保留最近 [keepCount] 个 session 目录，删除更早的
     * - 在 app 启动时调用，避免截图无限累积占满磁盘
     *
     * @param keepCount 保留的 session 目录数量
     */
    fun cleanupOldSessions(keepCount: Int = 3) {
        try {
            if (!sessionsDir.exists()) return
            val sessionDirs = sessionsDir.listFiles()?.filter { it.isDirectory } ?: return
            if (sessionDirs.size <= keepCount) return
            // 按最后修改时间排序，删除最旧的
            val sorted = sessionDirs.sortedBy { it.lastModified() }
            val toDelete = sorted.dropLast(keepCount)
            for (dir in toDelete) {
                dir.deleteRecursively()
                Log.i(TAG, "cleaned old session dir: ${dir.name}")
            }
            Log.i(TAG, "cleanupOldSessions: removed ${toDelete.size} dirs, kept ${sorted.size - toDelete.size}")
        } catch (e: Exception) {
            Log.w(TAG, "cleanupOldSessions failed: ${e.message}")
        }
    }

    /**
     * 清理旧日志文件（app 启动时调用）
     *
     * - recording.log 由 RecordingManager.clearLogOnAppStart 清空
     * - debug.log / proposals.log / teachings.log 在此清空（写入版本标识）
     * - 同时清理过期 session 目录
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
        val logsToClear = listOf(debugLogFile, proposalsLogFile, teachingsLogFile)
        for (file in logsToClear) {
            try {
                file.parentFile?.mkdirs()
                file.writeText(header)
            } catch (e: Exception) {
                Log.w(TAG, "clear ${file.name} failed: ${e.message}")
            }
        }
        Log.i(TAG, "logs cleared on app start: debug.log, proposals.log, teachings.log")

        // 清理过期 session 截图目录
        cleanupOldSessions()
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
