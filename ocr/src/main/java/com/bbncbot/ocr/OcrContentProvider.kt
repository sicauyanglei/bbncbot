package com.bbncbot.ocr

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * OCR ContentProvider——替代 AIDL Service 的跨进程调用方案
 *
 * 优势：
 * - ContentProvider 天然跨进程，不受 bindService 状态限制
 * - call() 方法走 Binder，支持 Bundle 传输（JPEG byte[] 可放入 Bundle）
 * - 用 signature 权限保护（在 manifest 的 <provider> 声明 android:permission）
 *
 * 调用约定：
 * - uri: content://com.bbncbot.ocr.provider
 * - method: "recognizeFertilizer" / "recognizeText"
 * - extras: "jpegData" -> ByteArray (JPEG 压缩的截图)
 * - 返回 Bundle: "result" -> Int (肥料数值, -1=失败) 或 String (文本)
 */
class OcrContentProvider : ContentProvider() {

    private val tag = "OcrContentProvider"

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    // 调用方包名白名单（替代 signature 权限的额外校验）
    private val allowedCallers = setOf("com.bbncbot")

    override fun onCreate(): Boolean {
        Log.i(tag, "OcrContentProvider created")
        return true
    }

    // 只实现 call()，query/insert/update/delete 不支持
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun getType(uri: Uri): String? = null

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        // 检查调用方包名
        val callerUid = android.os.Binder.getCallingUid()
        val callerPackages = context?.packageManager?.getPackagesForUid(callerUid) ?: arrayOf()
        val allowed = callerPackages.any { it in allowedCallers }
        if (!allowed) {
            Log.w(tag, "call: rejected caller packages=${callerPackages.joinToString(",")} method=$method")
            return null
        }

        when (method) {
            "recognizeFertilizer" -> {
                val jpegData = extras?.getByteArray("jpegData")
                if (jpegData == null || jpegData.isEmpty()) {
                    Log.d(tag, "recognizeFertilizer: empty jpegData")
                    return bundleResult(-1)
                }
                val bitmap = try {
                    BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
                } catch (e: Exception) {
                    Log.d(tag, "recognizeFertilizer: decode failed: ${e.message}")
                    return bundleResult(-1)
                }
                if (bitmap == null) {
                    Log.d(tag, "recognizeFertilizer: bitmap null")
                    return bundleResult(-1)
                }
                try {
                    val text = recognizeSync(bitmap) ?: return bundleResult(-1)
                    val amount = extractFertilizerAmount(text)
                    Log.d(tag, "recognizeFertilizer: amount=$amount (lines=${text.textBlocks.sumOf { it.lines.size }})")
                    return bundleResult(amount)
                } finally {
                    bitmap.recycle()
                }
            }
            "recognizeText" -> {
                val jpegData = extras?.getByteArray("jpegData")
                if (jpegData == null || jpegData.isEmpty()) return bundleResult("")
                val bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size) ?: return bundleResult("")
                try {
                    val text = recognizeSync(bitmap) ?: return bundleResult("")
                    val sb = StringBuilder()
                    for (block in text.textBlocks) {
                        for (line in block.lines) {
                            sb.append(line.text).append('\n')
                        }
                    }
                    return bundleResult(sb.toString())
                } finally {
                    bitmap.recycle()
                }
            }
            else -> {
                Log.w(tag, "call: unknown method=$method")
                return null
            }
        }
    }

    private fun bundleResult(amount: Int): Bundle = Bundle().apply { putInt("result", amount) }
    private fun bundleResult(text: String): Bundle = Bundle().apply { putString("result", text) }

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

    // 从 OCR 文本提取肥料总数（与原 OcrService 逻辑一致）
    private fun extractFertilizerAmount(text: Text): Int {
        val lines = mutableListOf<String>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                lines.add(line.text)
            }
        }
        val fullText = lines.joinToString(" ")

        Regex("肥料\\s*(\\d{3,})").find(fullText)?.let { m ->
            m.groupValues[1].toIntOrNull()?.let { if (it >= 100) return it }
        }
        Regex("(\\d{3,})\\s*肥料").find(fullText)?.let { m ->
            m.groupValues[1].toIntOrNull()?.let { if (it >= 100) return it }
        }

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

        var globalBest = -1
        for (m in Regex("\\d{4,}").findAll(fullText)) {
            val n = m.value.toIntOrNull() ?: continue
            if (n in 1900..2099) continue
            if (n > globalBest) globalBest = n
        }
        return globalBest
    }
}
