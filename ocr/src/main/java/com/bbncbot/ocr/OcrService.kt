package com.bbncbot.ocr

import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.IBinder
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * OCR 识别 Service
 *
 * 运行在独立 APK 进程（com.bbncbot.ocr）中，主 APK 通过 AIDL bindService 调用。
 *
 * 生命周期：
 * - 主 APK bindService 时创建，ML Kit recognizer 单例化（避免重复初始化）
 * - unbindService 后系统按需销毁
 * - recognizer 进程级缓存，多次调用复用同一实例
 *
 * 线程模型：
 * - AIDL onTransact 在 Binder 线程执行（主 APK 调用方在后台线程，不会阻塞双方主线程）
 * - ML Kit process() 是异步的，用 CountDownLatch 同步等待（超时 10s）
 *
 * 肥料数值提取策略（从原 FarmAccessibilityService 迁移，保持一致）：
 * 1. "肥料\s*\d{3,}" 或 "\d{3,}\s*肥料"
 * 2. 含"施肥"/"肥料"的行中取最大 3 位以上数字（排除"还差X次领肥料"）
 * 3. 兜底：全图最大 4 位以上数字（排除年份 19xx/20xx）
 */
class OcrService : Service() {

    private val tag = "OcrService"

    /** ML Kit 中文识别器（进程级单例，重复调用复用） */
    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(tag, "OcrService created, ML Kit recognizer initialized")
    }

    /**
     * 检查调用方包名是否在白名单内
     *
     * 替代 signature 级权限：不再强制要求同签名，只检查包名。
     * 第三方即使知道 action 也无法调用（包名不匹配 → 返回 null）。
     */
    private fun isCallerAllowed(): Boolean {
        val callerUid = android.os.Binder.getCallingUid()
        val callerPackages = packageManager.getPackagesForUid(callerUid) ?: arrayOf()
        val allowed = callerPackages.any { it == "com.bbncbot" }
        if (!allowed) {
            Log.w(tag, "onBind: rejected callerUid=$callerUid packages=${callerPackages.joinToString(",")} (非白名单包)")
        }
        return allowed
    }

    override fun onBind(intent: Intent?): IBinder? {
        if (!isCallerAllowed()) return null
        Log.i(tag, "onBind: accepted")
        return binder
    }

    private val binder = object : IOcrService.Stub() {

        override fun recognizeFertilizerAmount(jpegData: ByteArray?): Int {
            if (jpegData == null || jpegData.isEmpty()) {
                Log.d(tag, "recognizeFertilizerAmount: empty jpegData")
                return -1
            }
            val bitmap = try {
                BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
            } catch (e: Exception) {
                Log.d(tag, "recognizeFertilizerAmount: decode failed: ${e.message}")
                return -1
            }
            if (bitmap == null) {
                Log.d(tag, "recognizeFertilizerAmount: bitmap null")
                return -1
            }
            try {
                val text = recognizeSync(bitmap) ?: run {
                    Log.d(tag, "recognizeFertilizerAmount: recognize timeout/null")
                    return -1
                }
                val amount = extractFertilizerAmount(text)
                Log.d(tag, "recognizeFertilizerAmount: amount=$amount (lines=${text.textBlocks.sumOf { it.lines.size }})")
                return amount
            } finally {
                bitmap.recycle()
            }
        }

        override fun recognizeText(jpegData: ByteArray?): String {
            if (jpegData == null || jpegData.isEmpty()) return ""
            val bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size) ?: return ""
            try {
                val text = recognizeSync(bitmap) ?: return ""
                val sb = StringBuilder()
                for (block in text.textBlocks) {
                    for (line in block.lines) {
                        sb.append(line.text).append('\n')
                    }
                }
                return sb.toString()
            } finally {
                bitmap.recycle()
            }
        }
    }

    /**
     * 同步调用 ML Kit 识别（latch 等待，超时 10s）
     */
    private fun recognizeSync(bitmap: android.graphics.Bitmap): Text? {
        val image = InputImage.fromBitmap(bitmap, 0)
        val latch = CountDownLatch(1)
        var result: Text? = null
        recognizer.process(image)
            .addOnSuccessListener { result = it; latch.countDown() }
            .addOnFailureListener { e ->
                Log.d(tag, "recognizeSync failed: ${e.message}")
                latch.countDown()
            }
        latch.await(10, TimeUnit.SECONDS)
        return result
    }

    /**
     * 从 OCR 文本中提取肥料总数
     *
     * 与原 FarmAccessibilityService.findCurrentFertilizerAmountByOcr 逻辑完全一致，
     * 保证主 APK 切换到独立 OCR APK 后识别结果不变。
     */
    private fun extractFertilizerAmount(text: Text): Int {
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
            if (n in 1900..2099) continue
            if (n > globalBest) globalBest = n
        }
        return globalBest
    }
}
