package com.bbncbot.automation

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 答题 AI 客户端（调用智谱 GLM API 获取答题答案）
 *
 * 用户需求：回答问题就可以领取肥料，可以思考下认真回答问题。
 * 答题任务只有两个选项（对/错、是/否、A/B），且只有一次机会，不能试错。
 * 因此需要调用 AI API 获取答案，选择最可能正确的选项。
 *
 * 实现：
 * - 调用智谱 GLM-4.7-Flash（首选，推理能力强）chat/completions 接口
 * - glm-4.7-flash 限流时自动降级到 glm-4-flash-250414（稳定兜底）
 * - 将问题 + 两个选项发给 AI，让 AI 返回正确选项的文本
 * - API Key 存储于 SharedPreferences（与 GitHub Token 同样的安全方式）
 *
 * 必须在后台线程调用（含网络 IO）。
 *
 * 智谱 API 文档：
 * - glm-4.7-flash: https://docs.bigmodel.cn/cn/guide/models/free/glm-4.7-flash
 * - glm-4-flash-250414: https://docs.bigmodel.cn/cn/guide/models/free/glm-4-flash-250414
 * - URL: https://open.bigmodel.cn/api/paas/v4/chat/completions
 * - Auth: Bearer {api_key}
 *
 * 关键注意：glm-4.7-flash 默认开启思考模式，会消耗大量 token（50 token 全用于 reasoning），
 * 导致 max_tokens=100 不够返回最终答案。必须关闭思考模式（thinking.type=disabled）。
 * 关闭后响应更快、token 更省（6 token 返回完整答案）、答案更规范。
 */
object QuizAnswerClient {

    private const val TAG = "QuizAnswerClient"

    /** 智谱 GLM API 端点 */
    private const val API_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions"

    /**
     * 答题文本模型优先级列表（按能力从强到弱，依次尝试）
     *
     * 模型选择说明：
     * - glm-4.7-flash：30B MoE 推理模型，AIME 91.6 分，推理能力强（首选）
     *   注意：默认开启思考模式会消耗大量 token，必须关闭思考模式（thinking.disabled）
     * - glm-4-flash-250414：基础免费文本模型，能力稍弱但稳定（兜底，避免 4.7 限流时失效）
     *
     * Fallback 策略：依次尝试列表中的模型，限流/失败时自动降级到下一个模型。
     */
    private val TEXT_MODELS = listOf("glm-4.7-flash", "glm-4-flash-250414")

    /** SharedPreferences 名称（独立于 GitHub Token 配置） */
    private const val PREFS_NAME = "ai_config"
    private const val KEY_API_KEY = "glm_api_key"

    /**
     * 保存智谱 GLM API Key
     * @param context 任意 Context
     * @param apiKey API Key（为空则清除）
     */
    fun saveApiKey(context: Context, apiKey: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_API_KEY, apiKey.trim())
            .apply()
    }

    /**
     * 读取已保存的智谱 GLM API Key
     * @return API Key（未配置返回空字符串）
     */
    fun loadApiKey(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_API_KEY, "") ?: ""
    }

    /**
     * 询问 AI 获取答题答案
     *
     * @param context 任意 Context（用于读取 API Key）
     * @param question 问题文本
     * @param option1 第一个选项文本
     * @param option2 第二个选项文本
     * @return 正确选项的文本（option1 或 option2）；获取失败返回空字符串
     */
    fun askAnswer(
        context: Context,
        question: String,
        option1: String,
        option2: String
    ): String {
        val apiKey = loadApiKey(context)
        if (apiKey.isEmpty()) {
            Log.w(TAG, "askAnswer: GLM API Key not configured, cannot answer")
            return ""
        }
        if (question.isBlank() || option1.isBlank() || option2.isBlank()) {
            Log.w(TAG, "askAnswer: invalid input (question=$question, opt1=$option1, opt2=$option2)")
            return ""
        }

        // 构造提示词：要求 AI 只返回正确选项的完整文本
        // 严格约束输出格式，便于解析（避免 AI 输出多余解释）
        val prompt = buildPrompt(question, option1, option2)

        // 依次尝试文本模型列表（glm-4.7-flash → glm-4-flash-250414）
        // 限流/失败时自动降级到下一个模型
        for (model in TEXT_MODELS) {
            val content = callTextModel(apiKey, model, prompt)
            if (content.isNotBlank()) {
                // 匹配到对应选项（AI 返回的文本可能不完全一致，用包含关系匹配）
                val matched = matchOption(content, option1, option2)
                Log.i(TAG, "askAnswer: model=$model, question='${question.take(50)}', " +
                    "opt1='$option1', opt2='$option2', aiAnswer='$content', matched='$matched'")
                if (matched.isNotEmpty()) {
                    return matched
                }
                // 匹配失败但 AI 有返回，也继续尝试下一个模型（可能下一个模型返回更规范的文本）
                Log.w(TAG, "askAnswer: model=$model returned '$content' but matched no option, trying next")
            }
        }
        Log.w(TAG, "askAnswer: all text models failed or unmatched (tried=${TEXT_MODELS.joinToString()})")
        return ""
    }

    /**
     * 调用单个文本模型
     *
     * @param apiKey API Key
     * @param model 模型 ID
     * @param prompt 提示词
     * @return AI 返回的文本；失败返回空字符串（调用方会尝试下一个模型）
     */
    private fun callTextModel(apiKey: String, model: String, prompt: String): String {
        var conn: java.net.HttpURLConnection? = null
        return try {
            val url = java.net.URL(API_URL)
            conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("User-Agent", "bbncbot-app")
                doOutput = true
            }

            // 构造请求体（智谱 GLM 兼容 OpenAI 格式）
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "你是一个答题助手。用户会给你一道选择题（只有2个选项），你需要选出正确选项。" +
                        "只返回正确选项的完整文本，不要解释，不要加引号，不要加\"选项A\"等前缀。")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            }
            val jsonBody = JSONObject().apply {
                put("model", model)
                put("messages", messages)
                put("temperature", 0.1)  // 低温度，提高答案确定性
                put("max_tokens", 100)   // 答案很短，限制 token 节省费用
                // glm-4.7-flash 默认开启思考模式会消耗大量 token（50 token 全用于 reasoning）
                // 答题任务简单，关闭思考模式：响应更快、token 更省、答案更规范
                if (model == "glm-4.7-flash") {
                    put("thinking", JSONObject().apply {
                        put("type", "disabled")
                    })
                }
            }.toString()

            conn.outputStream.use { os ->
                os.write(jsonBody.toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Log.w(TAG, "callTextModel($model) failed (HTTP $code): ${err.take(200)}, will try next model")
                return ""
            }

            // 解析响应：choices[0].message.content
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val content = parseAnswer(response)
            if (content.isBlank()) {
                Log.w(TAG, "callTextModel($model): empty content, response=${response.take(200)}")
                return ""
            }
            Log.i(TAG, "callTextModel($model) success: content='${content.take(80)}'")
            content
        } catch (e: Exception) {
            Log.e(TAG, "callTextModel($model) exception: ${e.message}")
            ""
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 构造提示词
     *
     * 格式：
     * 问题：xxx
     * 选项A：xxx
     * 选项B：xxx
     * 请选出正确选项，只返回该选项的完整文本。
     */
    private fun buildPrompt(question: String, option1: String, option2: String): String {
        return buildString {
            append("问题：").append(question).append("\n")
            append("选项A：").append(option1).append("\n")
            append("选项B：").append(option2).append("\n")
            append("请选出正确选项，只返回该选项的完整文本（不要加\"选项A\"等前缀）。")
        }
    }

    /**
     * 解析 GLM API 响应，提取答案文本
     *
     * 响应格式：
     * {
     *   "choices": [
     *     { "message": { "role": "assistant", "content": "xxx" } }
     *   ]
     * }
     */
    private fun parseAnswer(response: String): String {
        return try {
            val json = JSONObject(response)
            val choices = json.optJSONArray("choices") ?: return ""
            if (choices.length() == 0) return ""
            val message = choices.optJSONObject(0)?.optJSONObject("message") ?: return ""
            message.optString("content", "").trim()
        } catch (e: Exception) {
            Log.e(TAG, "parseAnswer failed: ${e.message}")
            ""
        }
    }

    /**
     * 将 AI 返回的文本匹配到对应选项
     *
     * 匹配策略（按优先级）：
     * 1. 完全相等（忽略大小写和首尾空白）
     * 2. AI 文本包含选项文本
     * 3. 选项文本包含 AI 文本
     * 4. 都不匹配 → 返回空字符串（调用方可选默认选 A 或跳过）
     */
    private fun matchOption(aiAnswer: String, option1: String, option2: String): String {
        val ai = aiAnswer.trim()
        val opt1 = option1.trim()
        val opt2 = option2.trim()

        // 1. 完全相等（忽略大小写）
        if (ai.equals(opt1, ignoreCase = true)) return opt1
        if (ai.equals(opt2, ignoreCase = true)) return opt2

        // 2. AI 文本包含选项文本
        if (ai.contains(opt1)) return opt1
        if (ai.contains(opt2)) return opt2

        // 3. 选项文本包含 AI 文本
        if (opt1.contains(ai)) return opt1
        if (opt2.contains(ai)) return opt2

        // 4. 都不匹配
        Log.w(TAG, "matchOption: AI answer '$ai' matches neither '$opt1' nor '$opt2'")
        return ""
    }
}
