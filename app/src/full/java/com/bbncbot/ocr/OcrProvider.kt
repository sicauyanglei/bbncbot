package com.bbncbot.ocr

import android.util.Log
import com.bbncbot.service.FarmAccessibilityService

/**
 * OCR 肥料读取 - full flavor ML Kit 真实实现
 *
 * 此 flavor 打包 ML Kit 中文识别依赖（`text-recognition-chinese`），
 * 用于稳定版大包/上线，提供 H5 农场页无障碍树读不到文本时的 OCR 兜底。
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
     * 用 ML Kit OCR 识别屏幕文本，提取当前肥料总数
     *
     * 流程：
     * 1. 通过 [FarmAccessibilityService.takeScreenshotBitmap] 截图
     * 2. ML Kit 中文识别（latch 同步等待，超时 10s）
     * 3. 按优先级提取肥料数字
     *
     * @param service 无障碍服务实例（提供截图能力）
     * @return 肥料数值；-1 表示截图失败/识别超时/未提取到
     */
    fun findCurrentFertilizerAmount(service: FarmAccessibilityService): Int {
        val bitmap = service.takeScreenshotBitmap() ?: run {
            Log.d(TAG, "OCR fertilizer: screenshot failed")
            return -1
        }
        try {
            val image = com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0)
            val recognizer = com.google.mlkit.vision.text.TextRecognition
                .getClient(com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions.Builder().build())
            val latch = java.util.concurrent.CountDownLatch(1)
            var ocrText: com.google.mlkit.vision.text.Text? = null
            recognizer.process(image)
                .addOnSuccessListener { ocrText = it; latch.countDown() }
                .addOnFailureListener { e ->
                    Log.d(TAG, "OCR fertilizer: recognize failed: ${e.message}")
                    latch.countDown()
                }
            latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
            val text = ocrText ?: run {
                Log.d(TAG, "OCR fertilizer: recognize timeout or null")
                return -1
            }
            val amount = extractFertilizerFromOcrText(text)
            Log.d(TAG, "OCR fertilizer: amount=$amount (lines=${text.textBlocks.sumOf { it.lines.size }})")
            return amount
        } catch (e: Exception) {
            Log.d(TAG, "OCR fertilizer: exception: ${e.message}")
            return -1
        } finally {
            bitmap.recycle()
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
