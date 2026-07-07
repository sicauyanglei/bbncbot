package com.bbncbot.automation

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * AI 视觉识别助手
 *
 * 调用大模型视觉 API 识别屏幕截图中的元素位置，支持多个免费 API 提供商自由切换。
 *
 * 工作流程：
 * 1. App 内 AccessibilityService.takeScreenshot() 截取屏幕
 * 2. 把截图 Base64 编码后发送给大模型视觉 API
 * 3. 大模型返回需要点击的元素坐标和描述
 * 4. 用 dispatchGesture 点击对应坐标
 *
 * 支持的免费模型（均有免费额度）：
 * - GLM-4V-Plus（智谱AI）— GLM-4-Flash 完全免费，视觉模型有免费额度
 * - 通义千问 VL（阿里云百炼）— 新用户 7000 万 Tokens 免费
 * - Google Gemini — 永久免费层（5 RPM / 20 RPD）
 * - Kimi Vision（月之暗面）— 有免费额度
 * - 腾讯混元 Vision — 有免费额度
 * - SiliconFlow（硅基流动）— 开源模型免费 API
 * - MiniMax-01 — 多模态，有免费额度
 *
 * 所有提供商均兼容 OpenAI Chat Completions API 格式（Gemini 除外，单独适配）。
 */
object AiVisionHelper {

    private const val TAG = "AiVisionHelper"

    /**
     * 大模型 API 提供商（均有免费额度）
     */
    enum class ApiProvider(
        val displayName: String,
        val apiUrl: String,
        val modelName: String,
        /** API Key 前缀特征（用于自动识别） */
        val keyPrefix: String = "",
        /** 是否使用 Gemini 格式（而非 OpenAI 格式） */
        val isGeminiFormat: Boolean = false,
        /** 免费额度说明 */
        val freeQuotaDesc: String
    ) {
        /** 智谱 GLM-4V-Plus — GLM-4-Flash 完全免费，视觉模型有体验额度 */
        GLM_4V(
            displayName = "智谱 GLM-4V",
            apiUrl = "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            modelName = "glm-4v-plus",
            freeQuotaDesc = "新用户免费体验额度"
        ),
        /** 通义千问 VL — 阿里云百炼，新用户 7000 万 Tokens 免费 */
        QWEN_VL(
            displayName = "通义千问 VL",
            apiUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
            modelName = "qwen-vl-max",
            keyPrefix = "sk-",
            freeQuotaDesc = "新用户 7000 万 Tokens 免费"
        ),
        /** Google Gemini — 永久免费层（5 RPM / 20 RPD） */
        GEMINI(
            displayName = "Google Gemini",
            apiUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",
            modelName = "gemini-2.5-flash",
            isGeminiFormat = true,
            freeQuotaDesc = "永久免费层（5 RPM / 20 RPD）"
        ),
        /** Kimi Vision（月之暗面）— 有免费额度 */
        KIMI(
            displayName = "Kimi Vision",
            apiUrl = "https://api.moonshot.cn/v1/chat/completions",
            modelName = "moonshot-v1-8k-vision-preview",
            keyPrefix = "sk-",
            freeQuotaDesc = "新用户免费体验额度"
        ),
        /** 腾讯混元 Vision — 有免费额度 */
        HUNYUAN(
            displayName = "腾讯混元 Vision",
            apiUrl = "https://api.hunyuan.cloud.tencent.com/v1/chat/completions",
            modelName = "hunyuan-vision",
            freeQuotaDesc = "50 万 Tokens 免费（90 天）"
        ),
        /** SiliconFlow（硅基流动）— 开源模型免费 API */
        SILICONFLOW(
            displayName = "硅基流动 Qwen2-VL",
            apiUrl = "https://api.siliconflow.cn/v1/chat/completions",
            modelName = "Qwen/Qwen2-VL-72B-Instruct",
            keyPrefix = "sk-",
            freeQuotaDesc = "开源模型免费 API"
        ),
        /** MiniMax-01 — 多模态，有免费额度 */
        MINIMAX(
            displayName = "MiniMax-01",
            apiUrl = "https://api.minimax.chat/v1/chat/completions",
            modelName = "MiniMax-01",
            freeQuotaDesc = "新用户免费体验额度"
        );

        companion object {
            /** 获取所有提供商名称列表（用于 UI 下拉选择） */
            val displayNames: List<String> get() = entries.map { it.displayName }
        }
    }

    /** 当前 API 提供商 */
    @Volatile
    var provider: ApiProvider = ApiProvider.GLM_4V

    /** API Key（用户配置） */
    @Volatile
    var apiKey: String = ""

    /**
     * 识别结果
     * @param x 点击坐标 X（像素）
     * @param y 点击坐标 Y（像素）
     * @param description 元素描述
     * @param confidence 置信度（0-1）
     */
    data class DetectionResult(
        val x: Float,
        val y: Float,
        val description: String,
        val confidence: Float
    )

    /**
     * 在截图中查找指定描述的元素
     *
     * @param bitmap 屏幕截图
     * @param screenWidth 屏幕宽度（用于坐标换算）
     * @param screenHeight 屏幕高度（用于坐标换算）
     * @param targetDescription 要查找的元素描述，如"去完成按钮"、"右上角关闭图标"、"集肥料按钮"
     * @return 检测到的元素坐标，null 表示未找到或 API 调用失败
     */
    fun findElementByDescription(
        bitmap: Bitmap,
        screenWidth: Int,
        screenHeight: Int,
        targetDescription: String
    ): DetectionResult? {
        if (apiKey.isEmpty()) {
            Log.w(TAG, "API Key not configured, skip AI vision")
            return null
        }

        // 压缩截图（减少传输大小）
        val compressedBitmap = compressBitmap(bitmap, maxWidth = 720)
        val base64Image = bitmapToBase64(compressedBitmap)

        // 构建请求
        val prompt = buildPrompt(targetDescription, screenWidth, screenHeight)
        val requestBody = if (provider.isGeminiFormat) {
            buildGeminiRequestBody(base64Image, prompt)
        } else {
            buildOpenAIRequestBody(base64Image, prompt)
        }

        // 调用 API
        val response = callApi(requestBody) ?: return null

        // 解析返回
        return if (provider.isGeminiFormat) {
            parseGeminiResponse(response, screenWidth, screenHeight, compressedBitmap.width, compressedBitmap.height)
        } else {
            parseOpenAIResponse(response, screenWidth, screenHeight, compressedBitmap.width, compressedBitmap.height)
        }
    }

    /**
     * 构建提示词
     */
    private fun buildPrompt(targetDescription: String, screenWidth: Int, screenHeight: Int): String {
        return """
            你是一个屏幕元素识别助手。请在截图中找到"$targetDescription"。
            屏幕分辨率为 ${screenWidth}x${screenHeight}。

            要求：
            1. 找到目标元素后，返回其中心点的像素坐标
            2. 严格按 JSON 格式返回，不要包含其他文字
            3. 如果找不到目标元素，返回 {"found": false}

            返回格式：
            {"found": true, "x": <中心X坐标>, "y": <中心Y坐标>, "description": "<元素描述>", "confidence": <0-1的置信度>}
        """.trimIndent()
    }

    /**
     * 构建 OpenAI 格式请求体（通义千问/GLM/Kimi/混元/硅基流动/MiniMax 通用）
     */
    private fun buildOpenAIRequestBody(base64Image: String, prompt: String): String {
        return JSONObject().apply {
            put("model", provider.modelName)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", "data:image/jpeg;base64,$base64Image")
                            })
                        })
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", prompt)
                        })
                    })
                })
            })
            put("max_tokens", 200)
        }.toString()
    }

    /**
     * 构建 Gemini 格式请求体
     */
    private fun buildGeminiRequestBody(base64Image: String, prompt: String): String {
        return JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", prompt)
                        })
                        put(JSONObject().apply {
                            put("inline_data", JSONObject().apply {
                                put("mime_type", "image/jpeg")
                                put("data", base64Image)
                            })
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", 200)
            })
        }.toString()
    }

    /**
     * 调用大模型 API
     */
    private fun callApi(requestBody: String): String? {
        return try {
            // Gemini API 需要把 key 放在 URL 参数中
            val urlStr = if (provider.isGeminiFormat) {
                "${provider.apiUrl}?key=$apiKey"
            } else {
                provider.apiUrl
            }
            val url = URL(urlStr)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 30000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                if (!provider.isGeminiFormat) {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
            }

            val writer = OutputStreamWriter(conn.outputStream, "UTF-8")
            writer.write(requestBody)
            writer.flush()
            writer.close()

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                val errorStream = conn.errorStream?.bufferedReader()?.readText()
                Log.e(TAG, "API call failed: provider=${provider.displayName}, code=$responseCode, error=$errorStream")
                return null
            }

            val response = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            Log.d(TAG, "API response from ${provider.displayName}: length=${response.length}")
            response
        } catch (e: Exception) {
            Log.e(TAG, "API call exception (${provider.displayName}): ${e.message}", e)
            null
        }
    }

    /**
     * 解析 OpenAI 格式返回
     */
    private fun parseOpenAIResponse(
        response: String,
        screenWidth: Int,
        screenHeight: Int,
        imgWidth: Int,
        imgHeight: Int
    ): DetectionResult? {
        return try {
            val json = JSONObject(response)
            val choices = json.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                Log.w(TAG, "No choices in response")
                return null
            }

            val content = choices.getJSONObject(0)
                .optJSONObject("message")
                ?.optString("content")
                ?: return null

            val jsonStr = extractJsonFromContent(content)
            val resultJson = JSONObject(jsonStr)

            if (!resultJson.optBoolean("found", false)) {
                Log.d(TAG, "Element not found in screenshot")
                return null
            }

            val imgX = resultJson.optDouble("x", -1.0).toFloat()
            val imgY = resultJson.optDouble("y", -1.0).toFloat()
            val description = resultJson.optString("description", "")
            val confidence = resultJson.optDouble("confidence", 0.5).toFloat()

            if (imgX < 0 || imgY < 0) return null

            val screenX = imgX * screenWidth / imgWidth
            val screenY = imgY * screenHeight / imgHeight

            Log.i(TAG, "Detected: ($screenX, $screenY) desc='$description' confidence=$confidence")
            DetectionResult(screenX, screenY, description, confidence)
        } catch (e: Exception) {
            Log.e(TAG, "Parse OpenAI response failed: ${e.message}", e)
            null
        }
    }

    /**
     * 解析 Gemini 格式返回
     */
    private fun parseGeminiResponse(
        response: String,
        screenWidth: Int,
        screenHeight: Int,
        imgWidth: Int,
        imgHeight: Int
    ): DetectionResult? {
        return try {
            val json = JSONObject(response)
            val candidates = json.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                Log.w(TAG, "No candidates in Gemini response")
                return null
            }

            val content = candidates.getJSONObject(0)
                .optJSONObject("content")
                ?.optJSONArray("parts")
                ?.getJSONObject(0)
                ?.optString("text")
                ?: return null

            val jsonStr = extractJsonFromContent(content)
            val resultJson = JSONObject(jsonStr)

            if (!resultJson.optBoolean("found", false)) {
                Log.d(TAG, "Element not found (Gemini)")
                return null
            }

            val imgX = resultJson.optDouble("x", -1.0).toFloat()
            val imgY = resultJson.optDouble("y", -1.0).toFloat()
            val description = resultJson.optString("description", "")
            val confidence = resultJson.optDouble("confidence", 0.5).toFloat()

            if (imgX < 0 || imgY < 0) return null

            val screenX = imgX * screenWidth / imgWidth
            val screenY = imgY * screenHeight / imgHeight

            Log.i(TAG, "Gemini detected: ($screenX, $screenY) desc='$description' confidence=$confidence")
            DetectionResult(screenX, screenY, description, confidence)
        } catch (e: Exception) {
            Log.e(TAG, "Parse Gemini response failed: ${e.message}", e)
            null
        }
    }

    /**
     * 从大模型返回内容中提取 JSON
     */
    private fun extractJsonFromContent(content: String): String {
        val codeBlockRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)```")
        val match = codeBlockRegex.find(content)
        if (match != null) {
            return match.groupValues[1].trim()
        }
        val jsonStart = content.indexOf('{')
        val jsonEnd = content.lastIndexOf('}')
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            return content.substring(jsonStart, jsonEnd + 1).trim()
        }
        return content.trim()
    }

    /**
     * 压缩 Bitmap
     */
    private fun compressBitmap(bitmap: Bitmap, maxWidth: Int = 720): Bitmap {
        if (bitmap.width <= maxWidth) return bitmap
        val scale = maxWidth.toFloat() / bitmap.width
        val scaledHeight = (bitmap.height * scale).toInt()
        return Bitmap.createScaledBitmap(bitmap, maxWidth, scaledHeight, true)
    }

    /**
     * Bitmap 转 Base64
     */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * 根据提供商名称设置当前提供商
     * @param displayName 提供商显示名称（如"智谱 GLM-4V"）
     * @return true 表示设置成功
     */
    fun setProviderByDisplayName(displayName: String): Boolean {
        val target = ApiProvider.entries.find { it.displayName == displayName }
        if (target != null) {
            provider = target
            Log.i(TAG, "Provider switched to: ${target.displayName}")
            return true
        }
        return false
    }

    /**
     * 从 API Key 推断提供商
     */
    fun inferProviderFromKey(key: String): ApiProvider? {
        // 仅基于 key 前缀做简单推断，用户可手动切换
        return when {
            key.startsWith("sk-") && provider == ApiProvider.QWEN_VL -> ApiProvider.QWEN_VL
            key.startsWith("sk-") && provider == ApiProvider.KIMI -> ApiProvider.KIMI
            key.startsWith("sk-") && provider == ApiProvider.SILICONFLOW -> ApiProvider.SILICONFLOW
            key.startsWith("sk-") && provider == ApiProvider.MINIMAX -> ApiProvider.MINIMAX
            key.startsWith("sk-") -> ApiProvider.QWEN_VL // sk- 默认推断为通义千问
            key.contains("AIza") -> ApiProvider.GEMINI // Gemini key 以 AIza 开头
            else -> null // 无法推断，保持当前设置
        }
    }

    /**
     * AI 决策的页面动作
     * @param actionType 动作类型：click / swipe_up / swipe_down / back / wait / none
     * @param x 点击坐标 X（仅 click 有效，基于原始屏幕分辨率）
     * @param y 点击坐标 Y（仅 click 有效，基于原始屏幕分辨率）
     * @param description 动作说明（AI 给出的理由）
     */
    data class PageAction(
        val actionType: String,
        val x: Float = 0f,
        val y: Float = 0f,
        val description: String = ""
    )

    /**
     * 动态分析当前屏幕，决定下一步动作以达成目标
     *
     * 这是"动态适应"的核心：不依赖固定关键词/坐标，让大模型理解屏幕内容并给出动作建议。
     * 用于机器人卡住或遇到未知页面时的 fallback。
     *
     * @param bitmap 屏幕截图
     * @param screenWidth 屏幕宽度（像素）
     * @param screenHeight 屏幕高度（像素）
     * @param goal 当前目标，如"收集肥料"、"关闭广告"、"返回农场页"
     * @return 建议的动作，null 表示 API 调用失败
     */
    fun analyzePageForAction(
        bitmap: Bitmap,
        screenWidth: Int,
        screenHeight: Int,
        goal: String
    ): PageAction? {
        if (apiKey.isEmpty()) {
            Log.w(TAG, "API Key not configured, skip AI page analysis")
            return null
        }

        val compressedBitmap = compressBitmap(bitmap, maxWidth = 720)
        val base64Image = bitmapToBase64(compressedBitmap)

        val prompt = """
            你是一个手机自动化助手。当前目标是：$goal
            屏幕分辨率为 ${screenWidth}x${screenHeight}。

            请分析截图，决定下一步最合适的动作以达成目标。可选动作：
            - click：点击屏幕上某个元素（需提供中心坐标 x, y）
            - swipe_up：向上滑动（浏览下方内容）
            - swipe_down：向下滑动（浏览上方内容）
            - back：按返回键（退出当前页面）
            - wait：等待（页面正在加载）
            - none：无法判断/无合适动作

            动作选择建议：
            - 如果页面上有"领取/收集/去完成/签到/立即领取"等与肥料相关的按钮 → click
            - 如果是商品列表/浏览页面，需要继续浏览 → swipe_up
            - 如果是付款/收银台/订单确认/提交订单页 → back（绝不产生交易，不点击购买/支付/下单按钮）
            - 如果是邀请/关注/分享/下载App/开通会员等非广告任务 → back（跳过，不浪费时间）
            - 如果是广告播放中 → wait
            - 如果是任务完成弹窗，有"关闭/返回"按钮 → click 该按钮

            **重要约束：绝不产生任何交易。不点击"立即购买/加入购物车/提交订单/立即支付/去结算"等交易按钮。**

            严格按 JSON 格式返回，不要包含其他文字：
            {"action": "click|swipe_up|swipe_down|back|wait|none", "x": <坐标>, "y": <坐标>, "description": "<动作说明>"}
            注意：click 动作必须提供 x, y 坐标（基于 ${screenWidth}x${screenHeight} 的像素坐标），其他动作 x/y 可为 0。
        """.trimIndent()

        val requestBody = if (provider.isGeminiFormat) {
            buildGeminiRequestBody(base64Image, prompt)
        } else {
            buildOpenAIRequestBody(base64Image, prompt)
        }

        val response = callApi(requestBody) ?: return null

        val content = if (provider.isGeminiFormat) {
            parseGeminiTextContent(response)
        } else {
            parseOpenAITextContent(response)
        } ?: return null

        return parsePageAction(content, screenWidth, screenHeight, compressedBitmap.width, compressedBitmap.height)
    }

    /** 从 OpenAI 格式响应中提取文本内容 */
    private fun parseOpenAITextContent(response: String): String? {
        return try {
            val json = JSONObject(response)
            val choices = json.optJSONArray("choices")
            if (choices == null || choices.length() == 0) return null
            choices.getJSONObject(0)
                .optJSONObject("message")
                ?.optString("content")
        } catch (e: Exception) {
            Log.e(TAG, "parseOpenAITextContent failed: ${e.message}", e)
            null
        }
    }

    /** 从 Gemini 格式响应中提取文本内容 */
    private fun parseGeminiTextContent(response: String): String? {
        return try {
            val json = JSONObject(response)
            val candidates = json.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) return null
            candidates.getJSONObject(0)
                .optJSONObject("content")
                ?.optJSONArray("parts")
                ?.getJSONObject(0)
                ?.optString("text")
        } catch (e: Exception) {
            Log.e(TAG, "parseGeminiTextContent failed: ${e.message}", e)
            null
        }
    }

    /** 解析 AI 返回的动作 JSON */
    private fun parsePageAction(
        content: String,
        screenWidth: Int,
        screenHeight: Int,
        imgWidth: Int,
        imgHeight: Int
    ): PageAction? {
        return try {
            val jsonStr = extractJsonFromContent(content)
            val json = JSONObject(jsonStr)
            val action = json.optString("action", "none").lowercase()
            val imgX = json.optDouble("x", 0.0).toFloat()
            val imgY = json.optDouble("y", 0.0).toFloat()
            val desc = json.optString("description", "")
            // 坐标换算：压缩图坐标 → 原始屏幕坐标
            val screenX = if (imgX > 0) imgX * screenWidth / imgWidth else 0f
            val screenY = if (imgY > 0) imgY * screenHeight / imgHeight else 0f
            Log.i(TAG, "AI action: $action at ($screenX, $screenY) desc='$desc'")
            PageAction(action, screenX, screenY, desc)
        } catch (e: Exception) {
            Log.e(TAG, "parsePageAction failed: ${e.message}", e)
            null
        }
    }
}
