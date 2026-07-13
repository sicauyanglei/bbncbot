package com.bbncbot.ocr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.IBinder
import android.util.Log
import com.bbncbot.service.FarmAccessibilityService
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * OCR 肥料读取 - noOcr flavor AIDL 客户端
 *
 * 架构：主 APK（:app）不含 ML Kit，运行时通过 AIDL bindService 调用独立安装的
 * OCR APK（com.bbncbot.ocr）的识别能力。
 *
 * 优势：
 * - 主 APK 体积小（不含 ~20-30MB ML Kit 模型）
 * - OCR APK 安装一次后不变，主 APK 频繁更新无需重装 OCR
 * - 调试阶段只更新主 APK，OCR 模型固定不变
 *
 * 调用流程：
 * 1. 检查 com.bbncbot.ocr 是否已安装（未装则返回 -1，日志提示）
 * 2. 截图 → 压缩成 JPEG byte[]（Binder 事务缓冲区 ~1MB，必须压缩）
 * 3. bindService 连接 OcrService（超时 5s）
 * 4. 调用 IOcrService.recognizeFertilizerAmount(jpegData)（同步，Binder 线程）
 * 5. unbindService，返回结果
 *
 * 必须在后台线程调用（bindService + OCR 耗时约 1-3s）。
 *
 * fallback：若 OCR APK 未安装或连接失败，上层 RecordingManager 会按
 * "无障碍读不到 → OCR 不可用"记录日志，不影响录制流程。
 */
object OcrProvider {

    private const val TAG = "OcrProvider"

    /** OCR APK 包名 */
    private const val OCR_PACKAGE = "com.bbncbot.ocr"

    /** OCR Service action */
    private const val OCR_ACTION = "com.bbncbot.ocr.action.RECOGNIZE"

    /** JPEG 压缩质量（85 在体积和识别准确率间平衡，~200-400KB/张） */
    private const val JPEG_QUALITY = 85

    /**
     * 通过 AIDL 调用 OCR APK 识别当前屏幕的肥料总数
     *
     * @param service 无障碍服务实例（提供截图能力 + Context 用于 bindService）
     * @return 肥料数值；-1 表示 OCR APK 未安装/连接失败/识别失败
     */
    fun findCurrentFertilizerAmount(service: FarmAccessibilityService): Int {
        // 1. 检查 OCR APK 是否已安装
        if (!isOcrAppInstalled(service)) {
            Log.d(TAG, "OCR fertilizer: $OCR_PACKAGE not installed, skip")
            return -1
        }

        // 2. 截图
        val bitmap = service.takeScreenshotBitmap() ?: run {
            Log.d(TAG, "OCR fertilizer: screenshot failed")
            return -1
        }

        // 3. 压缩成 JPEG byte[]（Binder 传输需要）
        val jpegData = bitmapToJpeg(bitmap)
        bitmap.recycle()
        if (jpegData == null || jpegData.isEmpty()) {
            Log.d(TAG, "OCR fertilizer: jpeg compress failed")
            return -1
        }

        // 4. bindService + AIDL 调用
        return callOcrRemote(service, jpegData)
    }

    /**
     * 检查 OCR APK 是否已安装
     */
    private fun isOcrAppInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(OCR_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Bitmap → JPEG byte[]（Binder 事务缓冲区 ~1MB，全分辨率 Bitmap 会超限）
     */
    private fun bitmapToJpeg(bitmap: Bitmap): ByteArray? {
        return try {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
            baos.toByteArray()
        } catch (e: Exception) {
            Log.d(TAG, "bitmapToJpeg failed: ${e.message}")
            null
        }
    }

    /**
     * bindService 连接 OCR APK，调用 recognizeFertilizerAmount
     *
     * 同步等待 Service 连接 + 远程调用完成（超时 15s = bind 5s + recognize 10s）
     */
    private fun callOcrRemote(context: Context, jpegData: ByteArray): Int {
        val intent = Intent(OCR_ACTION).apply { setPackage(OCR_PACKAGE) }
        val latch = CountDownLatch(1)
        var result = -1
        var bound = false

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                try {
                    val ocr = IOcrService.Stub.asInterface(service)
                    result = ocr.recognizeFertilizerAmount(jpegData)
                    Log.d(TAG, "OCR remote call success: amount=$result")
                } catch (e: Exception) {
                    Log.d(TAG, "OCR remote call failed: ${e.message}")
                    result = -1
                } finally {
                    latch.countDown()
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.d(TAG, "OCR service disconnected")
            }

            override fun onBindingDied(name: ComponentName?) {
                Log.d(TAG, "OCR binding died")
                latch.countDown()
            }

            override fun onNullBinding(name: ComponentName?) {
                Log.d(TAG, "OCR null binding")
                latch.countDown()
            }
        }

        try {
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                Log.d(TAG, "bindService returned false (OCR APK not installed or Service not exported)")
                return -1
            }
            // 等待 onServiceConnected + 远程调用完成（超时 15s）
            latch.await(15, TimeUnit.SECONDS)
            if (latch.count > 0) {
                Log.d(TAG, "OCR call timeout (15s)")
            }
        } catch (e: Exception) {
            Log.d(TAG, "callOcrRemote exception: ${e.message}")
            result = -1
        } finally {
            if (bound) {
                try {
                    context.unbindService(connection)
                } catch (_: Exception) {
                    // unbindService 失败可忽略（Service 可能已断开）
                }
            }
        }
        return result
    }
}
