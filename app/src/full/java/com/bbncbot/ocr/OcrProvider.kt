package com.bbncbot.ocr

import android.graphics.Bitmap
import android.util.Log
import com.bbncbot.service.FarmAccessibilityService

/**
 * OCR 肥料读取 - full flavor ML Kit 真实实现
 *
 * 此 flavor 打包 ML Kit 中文识别依赖（`text-recognition-chinese`），
 * 用于稳定版大包/上线，提供 H5 农场页无障碍树读不到文本时的 OCR 兜底。
 *
 * 区域裁剪优化（与 noOcr flavor 保持一致）：
 * 1. 通过 [FarmAccessibilityService.findFertilizerNodeBounds] 定位肥料文本节点屏幕区域
 * 2. bounds 上下扩展 padding → 裁剪 sub-bitmap
 * 3. 只对 sub-bitmap 跑 ML Kit（减少 80%+ 像素，识别更快 + 避免广告误识别）
 * 4. 定位失败/坐标异常 → fallback 全屏 OCR
 *
 * 与 noOcr flavor 的 [OcrProvider] 同包名同类名，编译期按 flavor 二选一注入，
 * 上层 [com.bbncbot.automation.RecordingManager] 无感知。
 *
 * 提取策略（按优先级）：
 * 1. "肥料\s*\d{3,}" 或 "\d{3,}\s*肥料"（"肥料8432" / "8432肥料"）
 * 2. 含"施肥"或"肥料"的行中，取最大 3 位以上数字（"施肥 8432 可施肥65次" → 8432）
 * 3. 兜底：全图最大 4 位以上数字（排除年份 19xx/20xx），慎用
 *
 * 必须在后台线程调用（截图+OCR 耗时约 0.5-2s）。
 */
object OcrProvider {

    private const val TAG = "OcrProvider"

    /**
     * 上次操作的详细失败原因（供上层 RecordingManager 记录到 recording.log 诊断）
     * - "screenshot_failed"：截图失败
     * - "recognize_timeout"：识别超时
     * - "recognize_failed:xxx"：识别失败
     * - "no_fertilizer_in_text"：识别成功但文本中无肥料数字
     * - ""：成功或未调用
     */
    @Volatile
    var lastError: String = ""
        private set

    /**
     * 肥料区域上下扩展的 padding（像素）
     *
     * 节点 bounds 可能只包含文本本身，扩展 padding 避免数字边缘被裁。
     * 80px 约等于 1-2 行文字高度，覆盖数字与"肥料"标签的间距。
     */
    private const val REGION_PADDING_PX = 80

    /**
     * 用 ML Kit OCR 识别屏幕文本，提取当前肥料总数
     *
     * 流程：
     * 1. 截全屏 Bitmap
     * 2. 定位肥料节点 bounds → 裁剪区域 sub-bitmap（失败用全屏 fallback）
     * 3. ML Kit 中文识别（latch 同步等待，超时 10s）
     * 4. 按优先级提取肥料数字
     *
     * @param service 无障碍服务实例（提供截图 + 节点定位）
     * @return 肥料数值；-1 表示截图失败/识别超时/未提取到
     */
    fun findCurrentFertilizerAmount(service: FarmAccessibilityService): Int {
        lastError = ""
        val fullBitmap = service.takeScreenshotBitmap() ?: run {
            lastError = "screenshot_failed"
            Log.d(TAG, "OCR fertilizer: screenshot failed")
            return -1
        }

        // 定位肥料区域 + 裁剪（失败用全屏 fallback）
        val cropBitmap = cropFertilizerRegion(service, fullBitmap) ?: fullBitmap
        val isCropped = cropBitmap !== fullBitmap

        try {
            val image = com.google.mlkit.vision.common.InputImage.fromBitmap(cropBitmap, 0)
            val recognizer = com.google.mlkit.vision.text.TextRecognition
                .getClient(com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions.Builder().build())
            val latch = java.util.concurrent.CountDownLatch(1)
            var ocrText: com.google.mlkit.vision.text.Text? = null
            var recognizeError: String? = null
            recognizer.process(image)
                .addOnSuccessListener { ocrText = it; latch.countDown() }
                .addOnFailureListener { e ->
                    recognizeError = e.message
                    Log.d(TAG, "OCR fertilizer: recognize failed: ${e.message}")
                    latch.countDown()
                }
            latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
            val text = ocrText ?: run {
                lastError = if (recognizeError != null) "recognize_failed:$recognizeError" else "recognize_timeout"
                Log.d(TAG, "OCR fertilizer: recognize timeout or null (cropped=$isCropped)")
                return -1
            }
            val amount = extractFertilizerFromOcrText(text)
            Log.d(TAG, "OCR fertilizer: amount=$amount cropped=$isCropped size=${cropBitmap.width}x${cropBitmap.height} (lines=${text.textBlocks.sumOf { it.lines.size }})")
            if (amount < 0) lastError = "no_fertilizer_in_text"
            return amount
        } catch (e: Exception) {
            lastError = "exception:${e.message}"
            Log.d(TAG, "OCR fertilizer: exception: ${e.message}")
            return -1
        } finally {
            cropBitmap.recycle()
            if (isCropped) fullBitmap.recycle()  // 裁剪成功时回收全屏，否则 cropBitmap==fullBitmap 已回收
        }
    }

    /**
     * 定位肥料节点区域并裁剪 sub-bitmap
     *
     * 流程：
     * 1. 调 [FarmAccessibilityService.findFertilizerNodeBounds] 拿肥料文本节点 Rect
     * 2. bounds 上下扩展 [REGION_PADDING_PX]，左右也扩展（数字可能在"肥料"文字旁边）
     * 3. 裁剪后 bounds 限制在屏幕范围内（避免越界）
     * 4. Bitmap.createBitmap 裁出 sub-bitmap
     *
     * @param service 无障碍服务（提供节点定位）
     * @param fullBitmap 全屏截图
     * @return 裁剪后的 sub-bitmap；null 表示定位失败（调用方用全屏 fallback）
     */
    private fun cropFertilizerRegion(
        service: FarmAccessibilityService,
        fullBitmap: Bitmap
    ): Bitmap? {
        val bounds = service.findFertilizerNodeBounds() ?: run {
            Log.d(TAG, "cropFertilizerRegion: bounds null (定位失败), fallback 全屏")
            return null
        }

        // 扩展 padding（上下左右都扩展，数字可能在"肥料"文字旁边）
        val left = (bounds.left - REGION_PADDING_PX).coerceAtLeast(0)
        val top = (bounds.top - REGION_PADDING_PX).coerceAtLeast(0)
        val right = (bounds.right + REGION_PADDING_PX).coerceAtMost(fullBitmap.width)
        val bottom = (bounds.bottom + REGION_PADDING_PX).coerceAtMost(fullBitmap.height)

        val width = right - left
        val height = bottom - top

        // 校验裁剪区域有效（WebView 坐标异常可能导致 width/height<=0）
        if (width <= 0 || height <= 0) {
            Log.d(TAG, "cropFertilizerRegion: invalid region left=$left top=$top right=$right bottom=$bottom (bounds=$bounds), fallback 全屏")
            return null
        }

        return try {
            val cropped = Bitmap.createBitmap(fullBitmap, left, top, width, height)
            Log.d(TAG, "cropFertilizerRegion: success bounds=$bounds region=[$left,$top,$right,$bottom] ${width}x${height}")
            cropped
        } catch (e: Exception) {
            Log.d(TAG, "cropFertilizerRegion: createBitmap failed: ${e.message}, fallback 全屏")
            null
        }
    }

    /**
     * 从 OCR 识别结果中提取肥料总数
     *
     * @param text ML Kit 识别结果
     * @return 肥料数值；-1 表示未提取到
     */
    private fun extractFertilizerFromOcrText(text: com.google.mlkit.vision.text.Text): Int {
        val lines = mutableListOf<String>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                lines.add(line.text)
            }
        }
        val fullText = lines.joinToString(" ")

        // 1. "肥料\s*\d{3,}" 或 "\d{3,}\s*肥料"
        Regex("肥料\\s*(\\d{3,})").find(fullText)?.let { m ->
            m.groupValues[1].toIntOrNull()?.let { if (it >= 100) return it }
        }
        Regex("(\\d{3,})\\s*肥料").find(fullText)?.let { m ->
            m.groupValues[1].toIntOrNull()?.let { if (it >= 100) return it }
        }

        // 2. 含"施肥"或"肥料"的行中，取最大 3 位以上数字
        var lineBest = -1
        for (line in lines) {
            if (!line.contains("施肥") && !line.contains("肥料")) continue
            // 排除进度提示行（"还差4次领肥料"等无意义的肥料总数候选）
            if (line.contains("还差") || line.contains("次领")) continue
            for (m in Regex("\\d{3,}").findAll(line)) {
                val n = m.value.toIntOrNull() ?: continue
                if (n > lineBest) lineBest = n
            }
        }
        if (lineBest >= 100) return lineBest

        // 3. 兜底：全图最大 4 位以上数字，排除年份(19xx/20xx)
        var globalBest = -1
        for (m in Regex("\\d{4,}").findAll(fullText)) {
            val n = m.value.toIntOrNull() ?: continue
            if (n in 1900..2099) continue  // 排除年份
            if (n > globalBest) globalBest = n
        }
        return globalBest
    }
}
