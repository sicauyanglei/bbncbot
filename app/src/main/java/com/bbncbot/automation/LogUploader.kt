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
 * 用途：把 [recording.log] 和 [debug.log] 上传到 GitHub 仓库 `logs/` 目录，
 * 便于开发者远程查看手机运行日志（无需用户手动复制）。
 *
 * 实现方式：GitHub Contents API（PUT 单文件，自动 commit）
 * - 文件路径：`logs/{prefix}_{timestamp}.log`，时间戳防覆盖
 * - 鉴权：用户在 MainActivity 填入的 GitHub Personal Access Token（仅本地存储）
 *
 * 触发时机：每次 [RecordingManager.stop] 录制停止后自动调用（后台线程，不阻塞 UI）。
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

    /** 日志在仓库中的存放目录 */
    private const val REMOTE_DIR = "logs"

    /** SharedPreferences 名称（与 MainActivity 一致） */
    private const val PREFS_NAME = "github_config"
    private const val KEY_TOKEN = "gh_token"

    /** 日志文件本地路径 */
    private val logDir: File by lazy {
        File(
            android.os.Environment.getExternalStorageDirectory(),
            "Android/data/com.bbncbot/files"
        )
    }
    private val recordingLogFile: File get() = File(logDir, "recording.log")
    private val debugLogFile: File get() = File(logDir, "debug.log")

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
     * 上传日志到 GitHub
     *
     * 上传 [recording.log] 和 [debug.log]（若存在）到仓库 `logs/` 目录，
     * 文件名带时间戳避免覆盖。
     *
     * 必须在后台线程调用（含网络 IO）。
     *
     * @param context 任意 Context
     * @param tag 文件名前缀标记，如 "session_abc123"
     * @return 上传成功文件数（0=未配置 token，2=两个文件都成功）
     */
    fun upload(context: Context, tag: String): Int {
        val token = loadToken(context)
        if (token.isEmpty()) {
            Log.d(TAG, "skip upload: token not configured")
            return 0
        }

        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        var success = 0

        // 上传 recording.log
        if (recordingLogFile.exists() && recordingLogFile.length() > 0) {
            val remotePath = "$REMOTE_DIR/recording_${tag}_${ts}.log"
            if (uploadFile(token, remotePath, recordingLogFile, "upload recording log ($tag)")) {
                success++
            }
        } else {
            Log.d(TAG, "recording.log missing or empty, skip")
        }

        // 上传 debug.log
        if (debugLogFile.exists() && debugLogFile.length() > 0) {
            val remotePath = "$REMOTE_DIR/debug_${tag}_${ts}.log"
            if (uploadFile(token, remotePath, debugLogFile, "upload debug log ($tag)")) {
                success++
            }
        } else {
            Log.d(TAG, "debug.log missing or empty, skip")
        }

        Log.i(TAG, "upload done: $success files uploaded (tag=$tag)")
        return success
    }

    /**
     * 调用 GitHub Contents API 上传单个文件
     *
     * API: PUT https://api.github.com/repos/{owner}/{repo}/contents/{path}
     * - 创建或更新文件，每次调用都会产生一个新 commit
     * - 文件内容需 Base64 编码
     *
     * @return true 表示上传成功
     */
    private fun uploadFile(
        token: String,
        remotePath: String,
        localFile: File,
        commitMsg: String
    ): Boolean {
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
                    true
                }
                code == 401 -> {
                    Log.e(TAG, "upload failed 401 (token invalid?): $remotePath")
                    false
                }
                code == 403 -> {
                    Log.e(TAG, "upload failed 403 (token lacks permission or rate limit): $remotePath")
                    false
                }
                code == 404 -> {
                    Log.e(TAG, "upload failed 404 (repo not found / token can't access): $remotePath")
                    false
                }
                else -> {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    Log.e(TAG, "upload failed $code: $remotePath\n$err")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "upload exception: ${e.message}", e)
            return false
        } finally {
            conn?.disconnect()
        }
    }
}
