package com.bbncbot.automation

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * 视觉 AI 客户端（调用智谱 GLM-4.6V-Flash 免费多模态模型分析截图）
 *
 * 用户需求：有些不能处理的问题可以截图交给 API 来处理。
 *
 * 使用场景：状态机进入 UNKNOWN 场景（无法用规则识别页面内容）时，
 * 截图当前屏幕交给 AI，让 AI 判断该执行哪个预定义动作。
 *
 * 实现：
 * - 调用智谱 GLM-4.6V-Flash（免费视觉模型）chat/completions 接口
 * - 将截图 Base64 编码 + 文本提示词发给 AI
 * - AI 返回预定义动作之一（CLICK_CLOSE / CLICK_CLAIM / PRESS_BACK / SKIP_TASK / WAIT）
 * - API Key 与 QuizAnswerClient 共用（同一 SharedPreferences）
 *
 * 必须在后台线程调用（含网络 IO + Bitmap 编码）。
 *
 * 智谱视觉模型 API 文档：https://docs.bigmodel.cn/cn/guide/models/free/glm-4.6v-flash
 * - URL: https://open.bigmodel.cn/api/paas/v4/chat/completions
 * - Auth: Bearer {api_key}
 * - Body: { "model": "glm-4.6v-flash", "messages": [{"content": [{"type":"text",...}, {"type":"image_url",...}]}] }
 */
object AiVisionClient {

    private const val TAG = "AiVisionClient"

    /** 智谱 GLM API 端点（与文本模型共用） */
    private const val API_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions"

    /**
     * 视觉模型优先级列表（按能力从强到弱，依次尝试）
     *
     * 模型选择说明：
     * - glm-4.6v-flash：128K 上下文，视觉理解 SOTA，原生多模态（首选，能力最强）
     * - glm-4v-flash：旧版视觉模型，同样免费，能力稍弱但稳定（兜底，避免 4.6v 限流时失效）
     *
     * 注意：glm-4.7-flash 是纯文本模型（输入模态仅文本），不能处理图片，故不在此列表。
     * 截图分析必须用带 "v"（vision）的视觉模型。
     *
     * Fallback 策略：依次尝试列表中的模型，遇到 1305（访问量过大）等限流错误时
     * 自动降级到下一个模型，确保 bot 无人值守时 AI 视觉兜底始终可用。
     */
    private val VISION_MODELS = listOf("glm-4.6v-flash", "glm-4v-flash")

    /**
     * AI 视觉动作结果
     *
     * @param action 预定义动作
     * @param reason AI 给出的判断理由（用于日志，不参与执行）
     */
    data class VisionResult(
        val action: AiVisionAction,
        val reason: String
    )

    /**
     * 让 AI 分析截图并返回预定义动作
     *
     * @param context 任意 Context（用于读取 API Key）
     * @param bitmap 截图 Bitmap（将压缩为 JPEG Base64 上传）
     * @param sceneContext 当前场景上下文（如"广告播放中"/"任务列表页"等，帮助 AI 理解）
     * @return VisionResult；失败返回 null
     */
    fun analyzeScreenshot(
        context: Context,
        bitmap: Bitmap,
        sceneContext: String,
        ocrText: String? = null
    ): VisionResult? {
        val apiKey = QuizAnswerClient.loadApiKey(context)
        if (apiKey.isEmpty()) {
            Log.w(TAG, "analyzeScreenshot: GLM API Key not configured, skip AI vision")
            return null
        }

        // Bitmap → JPEG Base64（压缩到 < 1MB 避免请求体过大）
        val base64Image = encodeBitmapToBase64(bitmap)
        if (base64Image.isEmpty()) {
            Log.w(TAG, "analyzeScreenshot: encode bitmap failed")
            return null
        }

        val prompt = buildPrompt(sceneContext, ocrText)

        // 依次尝试视觉模型列表（glm-4.6v-flash → glm-4v-flash）
        // 遇到限流（1305）等可恢复错误时自动降级到下一个模型
        for (model in VISION_MODELS) {
            // P-429：单个模型限流时退避重试 2 次（5s + 10s），避免两个模型都限流时直接失败
            var retry = 0
            var result: VisionResult? = null
            while (retry <= 2) {
                result = callVisionModel(apiKey, model, prompt, base64Image, sceneContext)
                if (result != null) return result
                // result == null 时判断是否是限流（429/1305），是则退避后重试
                if (lastErrorCode == 429 || lastErrorMessage.contains("1305") || lastErrorMessage.contains("访问量过大")) {
                    retry++
                    if (retry <= 2) {
                        val backoffMs = if (retry == 1) 5000L else 10000L
                        Log.i(TAG, "analyzeScreenshot: $model rate-limited (429), backing off ${backoffMs}ms before retry $retry/2")
                        try {
                            Thread.sleep(backoffMs)
                        } catch (ie: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return null
                        }
                    }
                } else {
                    // 非限流错误（网络/解析失败/其他 HTTP 错误），不重试，直接换下一个模型
                    break
                }
            }
            // 限流重试耗尽或非限流错误，继续尝试下一个模型
        }
        Log.w(TAG, "analyzeScreenshot: all vision models failed (tried=${VISION_MODELS.joinToString()})")
        return null
    }

    /** 记录上一次调用的错误码/消息，供调用方判断是否限流（429/1305）决定是否退避重试 */
    @Volatile
    private var lastErrorCode: Int = 0
    @Volatile
    private var lastErrorMessage: String = ""

    /**
     * 调用单个视觉模型
     *
     * @return VisionResult；遇到限流/网络错误返回 null（调用方会尝试下一个模型）；
     *         API 返回了内容但解析失败也返回 null（继续尝试下一个模型兜底）
     */
    private fun callVisionModel(
        apiKey: String,
        model: String,
        prompt: String,
        base64Image: String,
        sceneContext: String
    ): VisionResult? {
        var conn: java.net.HttpURLConnection? = null
        return try {
            val url = java.net.URL(API_URL)
            conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20000
                readTimeout = 45000  // 视觉模型推理较慢，给足时间
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("User-Agent", "bbncbot-app")
                doOutput = true
            }

            // 构造多模态请求体（OpenAI 兼容格式）
            val content = JSONArray().apply {
                // 文本提示词在前，图片在后（智谱推荐顺序）
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", prompt)
                })
                put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", "data:image/jpeg;base64,$base64Image")
                    })
                })
            }
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "你是安卓自动化助手的视觉决策模块。" +
                        "用户会给你一张手机屏幕截图，你需要判断该执行哪个预定义动作。" +
                        "只返回一个 JSON 对象，不要任何额外文字。")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", content)
                })
            }
            val jsonBody = JSONObject().apply {
                put("model", model)
                put("messages", messages)
                put("temperature", 0.1)  // 低温度，提高决策确定性
                put("max_tokens", 200)   // 返回 JSON 很短
            }.toString()

            conn.outputStream.use { os ->
                os.write(jsonBody.toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                // 记录错误信息，供调用方判断是否限流决定是否退避重试
                lastErrorCode = code
                lastErrorMessage = err
                // 1305: 访问量过大（限流）→ 返回 null 让调用方决定是否退避重试
                // 其他错误也返回 null，统一由调用方 fallback
                Log.w(TAG, "callVisionModel($model) failed (HTTP $code): ${err.take(200)}")
                return null
            }
            // 成功响应，清空错误状态
            lastErrorCode = 0
            lastErrorMessage = ""

            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val contentStr = parseContent(response)
            if (contentStr.isBlank()) {
                Log.w(TAG, "callVisionModel($model): empty content, response=${response.take(200)}")
                return null
            }

            val result = parseAction(contentStr)
            Log.i(TAG, "callVisionModel($model) success: sceneContext='$sceneContext', " +
                "action=${result.action}, reason='${result.reason.take(80)}'")
            result
        } catch (e: Exception) {
            Log.e(TAG, "callVisionModel($model) exception: ${e.message}")
            // 网络异常等：非限流错误，不退避重试，直接换下一个模型
            lastErrorCode = -1
            lastErrorMessage = e.message ?: "exception"
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 构造 AI 提示词
     *
     * 约束 AI 只返回 5 个预定义动作之一，便于代码解析执行。
     */
    private fun buildPrompt(sceneContext: String, ocrText: String? = null): String {
        return buildString {
            append("当前场景上下文：").append(sceneContext).append("\n\n")
            append("请分析这张手机截图，判断安卓自动化机器人下一步该执行哪个动作。\n\n")
            append("只能从以下 5 个预定义动作中选择一个，返回严格的 JSON 格式：\n")
            append("{\"action\": \"<动作>\", \"reason\": \"<简要理由>\"}\n\n")
            append("可选动作（action 字段值）：\n")
            append("- CLICK_CLOSE  : 点击关闭按钮（×/关闭/知道了/确定）关闭弹窗\n")
            append("- CLICK_CLAIM  : 点击领取/确认按钮（领取奖励/领取肥料/确定）领取奖励\n")
            append("- PRESS_BACK   : 按返回键（无法处理或应该退出当前页）\n")
            append("- SKIP_TASK    : 跳过当前任务（页面无肥料价值，如分享/评价/会员推销）\n")
            append("- WAIT         : 等待（页面正在加载或倒计时中，不应操作）\n\n")
            append("判断优先级：\n")
            append("1. 若有\"恭喜获得/领取奖励/领取肥料\"等领取按钮 → CLICK_CLAIM\n")
            append("2. 若有\"×/关闭/知道了/确定\"等关闭按钮且无肥料提示 → CLICK_CLOSE\n")
            append("3. 若页面是分享/评价/会员/活动推销等无肥料价值 → SKIP_TASK\n")
            append("4. 若页面正在加载/有倒计时/视频播放中 → WAIT\n")
            append("5. 其他无法处理的情况 → PRESS_BACK\n\n")
            // 若有 OCR 识别文本，附加给 AI 作为辅助判断依据（截断到 1500 字符避免 prompt 过长）
            if (!ocrText.isNullOrEmpty()) {
                append("\n页面 OCR 识别文本（仅供参考，可能与截图内容有出入）：\n")
                append(ocrText!!.take(1500))
                append("\n\n")
            }
            append("只返回 JSON，不要 markdown 代码块，不要解释。")
        }
    }

    /**
     * 解析 GLM API 响应，提取 content 文本
     */
    private fun parseContent(response: String): String {
        return try {
            val json = JSONObject(response)
            val choices = json.optJSONArray("choices") ?: return ""
            if (choices.length() == 0) return ""
            val message = choices.optJSONObject(0)?.optJSONObject("message") ?: return ""
            message.optString("content", "").trim()
        } catch (e: Exception) {
            Log.e(TAG, "parseContent failed: ${e.message}")
            ""
        }
    }

    /**
     * 解析 AI 返回的动作为枚举
     *
     * 容错策略：
     * - 尝试解析为 JSON
     * - 失败则在原文中匹配动作关键词
     * - 都失败返回 PRESS_BACK（最安全的兜底）
     */
    private fun parseAction(content: String): VisionResult {
        // 1. 尝试 JSON 解析
        try {
            // 兼容 AI 可能包裹的 markdown 代码块
            val cleaned = content
                .replace("```json", "")
                .replace("```", "")
                .trim()
            val json = JSONObject(cleaned)
            val actionStr = json.optString("action", "").uppercase()
            val reason = json.optString("reason", "")
            val action = parseActionEnum(actionStr)
            return VisionResult(action, reason)
        } catch (e: Exception) {
            Log.w(TAG, "parseAction: JSON parse failed, fallback to keyword match: ${e.message}")
        }

        // 2. 关键词兜底匹配
        val upper = content.uppercase()
        val action = when {
            upper.contains("CLICK_CLOSE") -> AiVisionAction.CLICK_CLOSE
            upper.contains("CLICK_CLAIM") -> AiVisionAction.CLICK_CLAIM
            upper.contains("SKIP_TASK") -> AiVisionAction.SKIP_TASK
            upper.contains("WAIT") -> AiVisionAction.WAIT
            else -> AiVisionAction.PRESS_BACK
        }
        return VisionResult(action, content.take(100))
    }

    private fun parseActionEnum(s: String): AiVisionAction {
        return when (s) {
            "CLICK_CLOSE" -> AiVisionAction.CLICK_CLOSE
            "CLICK_CLAIM" -> AiVisionAction.CLICK_CLAIM
            "SKIP_TASK" -> AiVisionAction.SKIP_TASK
            "WAIT" -> AiVisionAction.WAIT
            else -> AiVisionAction.PRESS_BACK
        }
    }

    // ---------- 肥料进度视觉识别（build529：用户要求"全部实现"） ----------
    // 用户需求：你能获取肥料进度的窗口吗，比如浏览了多少秒，标识肥的环形进度条等 → 全部实现
    //
    // 与 [analyzeScreenshot] 的区别：
    // - analyzeScreenshot 返回预定义动作（CLICK_CLOSE/WAIT 等），用于"该做什么"决策
    // - recognizeProgressFromScreenshot 返回进度数值（百分比/剩余秒数），用于"还要等多久"决策
    //
    // 使用场景：浏览任务/游戏停留中，文本识别拿不到进度时，截图交给 GLM-4.6V-Flash，
    // 让 AI 识别环形进度条的填充比例。

    /**
     * AI 视觉进度识别结果
     *
     * @param percent          进度百分比 0-100（0 表示刚开始，100 表示完成）
     * @param secondsRemaining 剩余秒数（AI 估计，0 表示无倒计时或已完成）
     * @param hasProgressBar   是否识别到进度条/进度环
     * @param reason            AI 给出的描述（用于日志）
     */
    data class ProgressResult(
        val percent: Int,
        val secondsRemaining: Int,
        val hasProgressBar: Boolean,
        val reason: String
    )

    /**
     * 让 AI 分析截图并识别环形进度条的填充比例
     *
     * @param context      任意 Context（用于读取 API Key）
     * @param bitmap       截图 Bitmap（将压缩为 JPEG Base64 上传）
     * @param sceneContext 当前场景上下文（如"浏览任务进行中"/"游戏停留中"等，帮助 AI 理解）
     * @return [ProgressResult]；失败返回 null（API Key 未配置/网络错误等）
     */
    fun recognizeProgressFromScreenshot(
        context: Context,
        bitmap: Bitmap,
        sceneContext: String
    ): ProgressResult? {
        val apiKey = QuizAnswerClient.loadApiKey(context)
        if (apiKey.isEmpty()) {
            Log.w(TAG, "recognizeProgressFromScreenshot: GLM API Key not configured, skip AI vision")
            return null
        }
        val base64Image = encodeBitmapToBase64(bitmap)
        if (base64Image.isEmpty()) {
            Log.w(TAG, "recognizeProgressFromScreenshot: encode bitmap failed")
            return null
        }
        val prompt = buildProgressPrompt(sceneContext)
        // 依次尝试视觉模型列表（与 analyzeScreenshot 相同的 fallback 策略）
        for (model in VISION_MODELS) {
            var retry = 0
            var result: ProgressResult? = null
            while (retry <= 2) {
                result = callVisionModelForProgress(apiKey, model, prompt, base64Image, sceneContext)
                if (result != null) return result
                if (lastErrorCode == 429 || lastErrorMessage.contains("1305") || lastErrorMessage.contains("访问量过大")) {
                    retry++
                    if (retry <= 2) {
                        val backoffMs = if (retry == 1) 5000L else 10000L
                        Log.i(TAG, "recognizeProgressFromScreenshot: $model rate-limited (429), backing off ${backoffMs}ms before retry $retry/2")
                        try {
                            Thread.sleep(backoffMs)
                        } catch (ie: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return null
                        }
                    }
                } else {
                    break
                }
            }
        }
        Log.w(TAG, "recognizeProgressFromScreenshot: all vision models failed (tried=${VISION_MODELS.joinToString()})")
        return null
    }

    /**
     * 调用视觉模型识别进度（与 [callVisionModel] 类似，但用进度专用提示词与解析器）
     */
    private fun callVisionModelForProgress(
        apiKey: String,
        model: String,
        prompt: String,
        base64Image: String,
        sceneContext: String
    ): ProgressResult? {
        var conn: java.net.HttpURLConnection? = null
        return try {
            val url = java.net.URL(API_URL)
            conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20000
                readTimeout = 45000
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("User-Agent", "bbncbot-app")
                doOutput = true
            }
            val content = JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", prompt)
                })
                put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", "data:image/jpeg;base64,$base64Image")
                    })
                })
            }
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "你是安卓自动化助手的视觉进度识别模块。" +
                        "用户会给你一张手机屏幕截图，你需要识别屏幕上的环形进度条、进度环、倒计时进度等。" +
                        "只返回一个 JSON 对象，不要任何额外文字。")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", content)
                })
            }
            val jsonBody = JSONObject().apply {
                put("model", model)
                put("messages", messages)
                put("temperature", 0.1)
                put("max_tokens", 200)
            }.toString()

            conn.outputStream.use { os ->
                os.write(jsonBody.toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                lastErrorCode = code
                lastErrorMessage = err
                Log.w(TAG, "callVisionModelForProgress($model) failed (HTTP $code): ${err.take(200)}")
                return null
            }
            lastErrorCode = 0
            lastErrorMessage = ""

            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val contentStr = parseContent(response)
            if (contentStr.isBlank()) {
                Log.w(TAG, "callVisionModelForProgress($model): empty content, response=${response.take(200)}")
                return null
            }
            val result = parseProgressResult(contentStr)
            Log.i(TAG, "callVisionModelForProgress($model) success: scene='$sceneContext', " +
                "percent=${result.percent}%, secondsRemaining=${result.secondsRemaining}s, " +
                "hasBar=${result.hasProgressBar}, reason='${result.reason.take(80)}'")
            result
        } catch (e: Exception) {
            Log.e(TAG, "callVisionModelForProgress($model) exception: ${e.message}")
            lastErrorCode = -1
            lastErrorMessage = e.message ?: "exception"
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 构造进度识别提示词
     *
     * 约束 AI 只返回 JSON，便于代码解析。
     *
     * build529（用户反馈）：
     * - 第一版只让 AI 扫描左侧，但广告设计者会把进度环放在不同位置——
     *   左侧、右侧、中央、顶部都可能，要看设计者的良苦用心（视觉重心/手指热区/品牌位置等）。
     * - 提示词改为：全屏扫描，明确告诉 AI 进度环可能在任意位置，
     *   让 AI 自己定位而非假设固定方位，避免遗漏。
     */
    private fun buildProgressPrompt(sceneContext: String): String {
        return buildString {
            append("当前场景上下文：").append(sceneContext).append("\n\n")
            append("请分析这张手机截图，识别屏幕上的肥料进度信息。\n\n")
            append("扫描策略：环形进度条/进度环可能位于屏幕任意位置——" +
                "广告设计者会根据视觉重心、手指热区、品牌位置等放在左侧/右侧/中央/顶部/底部，" +
                "请全屏扫描，不要只盯固定方位。识别每个圆形/环形的进度指示器，" +
                "圆环会有部分填充（如填充了一半表示 50%），通常伴随倒计时数字或图标。\n\n")
            append("需要识别的内容：\n")
            append("1. 是否存在环形进度条/进度环（圆形/环形，部分填充表示进度，位置任意）\n")
            append("2. 如果存在进度环，填充比例是多少（0-100%）——已填充部分占整个环的比例\n")
            append("3. 如果环内或环旁有倒计时数字（如\"15s\"\"剩余15秒\"\"15/30\"），剩余多少秒\n\n")
            append("返回严格的 JSON 格式：\n")
            append("{\"has_progress_bar\": <true|false>, \"percent\": <0-100整数>, \"seconds_remaining\": <整数>, \"reason\": \"<简要描述，含进度环位置与填充情况>\"}\n\n")
            append("说明：\n")
            append("- has_progress_bar：是否识别到任何形式的进度条/进度环/倒计时进度\n")
            append("- percent：进度环填充百分比（0-100，无进度条填 0）\n")
            append("- seconds_remaining：剩余秒数（无倒计时填 0）\n")
            append("- reason：简要描述进度元素的位置（如\"右侧环形进度条，约填充 50%\"、" +
                "\"中央倒计时圆环，填充 80%\"），位置信息帮助诊断广告设计\n\n")
            append("只返回 JSON，不要 markdown 代码块，不要解释。")
        }
    }

    /**
     * 解析进度识别结果
     *
     * 容错策略：先 JSON 解析；失败则从原文提取数字（百分比、秒数）。
     */
    private fun parseProgressResult(content: String): ProgressResult {
        try {
            val cleaned = content
                .replace("```json", "")
                .replace("```", "")
                .trim()
            val json = JSONObject(cleaned)
            val hasBar = json.optBoolean("has_progress_bar", false)
            val percent = json.optInt("percent", 0).coerceIn(0, 100)
            val secondsRemaining = json.optInt("seconds_remaining", 0).coerceAtLeast(0)
            val reason = json.optString("reason", "")
            return ProgressResult(percent, secondsRemaining, hasBar, reason)
        } catch (e: Exception) {
            Log.w(TAG, "parseProgressResult: JSON parse failed, fallback to regex: ${e.message}")
        }
        // 兜底：从原文提取百分比与秒数
        val percentMatch = Regex("(\\d{1,3})\\s*%").find(content)
        val percent = percentMatch?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 100) ?: 0
        val secondsMatch = Regex("(\\d+)\\s*[秒s]").find(content)
        val seconds = secondsMatch?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        return ProgressResult(percent, seconds, percent > 0 || seconds > 0, content.take(100))
    }

    /**
     * Bitmap → JPEG Base64
     *
     * 压缩策略：质量从 80 递减到 20，直到 < 800KB（避免请求体过大被拒绝）
     */
    private fun encodeBitmapToBase64(bitmap: Bitmap): String {
        var quality = 80
        while (quality >= 20) {
            try {
                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                val bytes = baos.toByteArray()
                if (bytes.size < 800_000) {
                    return Base64.encodeToString(bytes, Base64.NO_WRAP)
                }
                quality -= 20
            } catch (e: Exception) {
                Log.e(TAG, "encodeBitmapToBase64 failed at quality=$quality: ${e.message}")
                return ""
            }
        }
        // 最后兜底：用最低质量编码
        return try {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 20, baos)
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            ""
        }
    }
}
