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
 * 区域裁剪优化（避免全屏识别广告/商品图）：
 * 1. 通过 [FarmAccessibilityService.findFertilizerNodeBounds] 定位肥料文本节点屏幕区域
 * 2. bounds 上下扩展 padding（避免数字被裁）→ 裁剪 sub-bitmap
 * 3. sub-bitmap 压缩 JPEG → AIDL 传给 OCR APK（体积从 ~200-400KB 降到 ~10-30KB）
 * 4. 定位失败/坐标异常 → fallback 全屏 OCR（保证兜底可用）
 *
 * 优势：
 * - 主 APK 体积小（不含 ~20-30MB ML Kit 模型）
 * - OCR APK 安装一次后不变，主 APK 频繁更新无需重装 OCR
 * - 区域裁剪减少 80%+ 像素，识别更快 + 避免广告误识别
 *
 * 必须在后台线程调用（bindService + OCR 耗时约 0.5-2s）。
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

    /** JPEG 压缩质量（85 在体积和识别准确率间平衡） */
    private const val JPEG_QUALITY = 85

    /**
     * 肥料区域上下扩展的 padding（像素）
     *
     * 节点 bounds 可能只包含文本本身，扩展 padding 避免数字边缘被裁。
     * 80px 约等于 1-2 行文字高度，覆盖数字与"肥料"标签的间距。
     */
    private const val REGION_PADDING_PX = 80

    /**
     * 通过 AIDL 调用 OCR APK 识别当前屏幕的肥料总数
     *
     * 流程：
     * 1. 检查 OCR APK 已安装
     * 2. 截全屏 Bitmap
     * 3. 定位肥料节点 bounds → 裁剪区域 sub-bitmap（失败用全屏 fallback）
     * 4. 压缩 JPEG → AIDL 调用 OCR APK
     *
     * @param service 无障碍服务实例（提供截图 + 节点定位 + Context 用于 bindService）
     * @return 肥料数值；-1 表示 OCR APK 未安装/连接失败/识别失败
     */
    fun findCurrentFertilizerAmount(service: FarmAccessibilityService): Int {
        // 1. 检查 OCR APK 是否已安装
        if (!isOcrAppInstalled(service)) {
            Log.d(TAG, "OCR fertilizer: $OCR_PACKAGE not installed, skip")
            return -1
        }

        // 2. 截全屏 Bitmap
        val fullBitmap = service.takeScreenshotBitmap() ?: run {
            Log.d(TAG, "OCR fertilizer: screenshot failed")
            return -1
        }

        // 3. 定位肥料区域 + 裁剪（失败用全屏 fallback）
        val cropBitmap = cropFertilizerRegion(service, fullBitmap) ?: fullBitmap
        val isCropped = cropBitmap !== fullBitmap

        // 4. 压缩 JPEG + AIDL 调用
        val jpegData = bitmapToJpeg(cropBitmap)
        cropBitmap.recycle()
        if (isCropped) fullBitmap.recycle()  // 裁剪成功时回收全屏，否则 cropBitmap==fullBitmap 已回收
        if (jpegData == null || jpegData.isEmpty()) {
            Log.d(TAG, "OCR fertilizer: jpeg compress failed (cropped=$isCropped)")
            return -1
        }

        Log.d(TAG, "OCR fertilizer: cropped=$isCropped jpegSize=${jpegData.size} bytes")
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
     * Bitmap → JPEG byte[]（Binder 事务缓冲区 ~1MB，必须压缩）
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
