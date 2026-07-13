package com.bbncbot.ocr

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.bbncbot.service.FarmAccessibilityService
import java.io.ByteArrayOutputStream

/**
 * OCR 肥料读取 - noOcr flavor ContentProvider 客户端
 *
 * 架构：主 APK（:app）不含 ML Kit，运行时通过 ContentResolver.call() 调用独立安装的
 * OCR APK（com.bbncbot.ocr）的 OcrContentProvider 识别能力。
 *
 * 替代旧 AIDL bindService 方案——ContentProvider 天然跨进程，不受 bindService
 * 状态/进程状态影响（无障碍服务 context 下 bindService 反复返回 false）。
 *
 * 区域裁剪优化（避免全屏识别广告/商品图）：
 * 1. 通过 [FarmAccessibilityService.findFertilizerNodeBounds] 定位肥料文本节点屏幕区域
 * 2. bounds 上下扩展 padding（避免数字被裁）→ 裁剪 sub-bitmap
 * 3. sub-bitmap 压缩 JPEG → Bundle 传给 OCR APK（体积从 ~200-400KB 降到 ~10-30KB）
 * 4. 定位失败/坐标异常 → fallback 全屏 OCR（保证兜底可用）
 *
 * 优势：
 * - 主 APK 体积小（不含 ~20-30MB ML Kit 模型）
 * - OCR APK 安装一次后不变，主 APK 频繁更新无需重装 OCR
 * - 区域裁剪减少 80%+ 像素，识别更快 + 避免广告误识别
 *
 * 必须在后台线程调用（OCR 耗时约 0.5-2s）。
 *
 * fallback：若 OCR APK 未安装或调用失败，上层 RecordingManager 会按
 * "无障碍读不到 → OCR 不可用"记录日志，不影响录制流程。
 */
object OcrProvider {

    private const val TAG = "OcrProvider"

    /** OCR APK 包名 */
    private const val OCR_PACKAGE = "com.bbncbot.ocr"

    /** ContentProvider authorities */
    private const val OCR_PROVIDER_URI = "content://com.bbncbot.ocr.provider"

    /** call() 方法名 */
    private const val METHOD_RECOGNIZE_FERTILIZER = "recognizeFertilizer"

    /** JPEG 压缩质量（85 在体积和识别准确率间平衡） */
    private const val JPEG_QUALITY = 85

    /**
     * 上次操作的详细失败原因（供上层 RecordingManager 记录到 recording.log 诊断）
     *
     * 区分以下失败场景：
     * - "ocr_apk_not_installed"：OCR APK 未安装
     * - "screenshot_failed"：截图失败
     * - "jpeg_compress_failed"：JPEG 压缩失败
     * - "recognize_returned_-1"：ContentProvider 调用成功但返回 -1
     * - "security_exception:xxx"：权限被拒（signature 不匹配等）
     * - "provider_exception:xxx"：ContentProvider 调用异常
     * - "recognize_failed"：兜底（其他失败场景未设置时）
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
     * 通过 ContentProvider call() 调用 OCR APK 识别当前屏幕的肥料总数
     *
     * 流程：
     * 1. 检查 OCR APK 已安装
     * 2. 截全屏 Bitmap
     * 3. 定位肥料节点 bounds → 裁剪区域 sub-bitmap（失败用全屏 fallback）
     * 4. 压缩 JPEG → ContentResolver.call() 调用 OCR APK
     *
     * @param service 无障碍服务实例（提供截图 + 节点定位 + Context 用于 ContentResolver）
     * @return 肥料数值；-1 表示 OCR APK 未安装/调用失败/识别失败
     */
    fun findCurrentFertilizerAmount(service: FarmAccessibilityService): Int {
        lastError = ""
        // 1. 检查 OCR APK 是否已安装
        if (!isOcrAppInstalled(service)) {
            lastError = "ocr_apk_not_installed"
            Log.d(TAG, "OCR fertilizer: $OCR_PACKAGE not installed, skip")
            return -1
        }

        // 2. 截全屏 Bitmap
        val fullBitmap = service.takeScreenshotBitmap() ?: run {
            lastError = "screenshot_failed"
            Log.d(TAG, "OCR fertilizer: screenshot failed")
            return -1
        }

        // 3. 定位肥料区域 + 裁剪（失败用全屏 fallback）
        val cropBitmap = cropFertilizerRegion(service, fullBitmap) ?: fullBitmap
        val isCropped = cropBitmap !== fullBitmap

        // 4. 压缩 JPEG + ContentProvider 调用
        val jpegData = bitmapToJpeg(cropBitmap)
        cropBitmap.recycle()
        if (isCropped) fullBitmap.recycle()  // 裁剪成功时回收全屏，否则 cropBitmap==fullBitmap 已回收
        if (jpegData == null || jpegData.isEmpty()) {
            lastError = "jpeg_compress_failed"
            Log.d(TAG, "OCR fertilizer: jpeg compress failed (cropped=$isCropped)")
            return -1
        }

        Log.d(TAG, "OCR fertilizer: cropped=$isCropped jpegSize=${jpegData.size} bytes")
        val result = callOcrRemote(service.applicationContext, jpegData)
        if (result < 0 && lastError.isEmpty()) {
            lastError = "recognize_failed"
        }
        return result
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
     * 通过 ContentProvider call() 调用 OCR APK
     *
     * 替代 bindService 方案——ContentProvider 天然跨进程，不受绑定状态限制。
     *
     * @param context Context（用 applicationContext 避免 Activity 生命周期影响）
     * @param jpegData JPEG 压缩的截图数据
     * @return 肥料数值；-1 表示失败
     */
    private fun callOcrRemote(context: Context, jpegData: ByteArray): Int {
        val uri = Uri.parse(OCR_PROVIDER_URI)
        val extras = Bundle().apply { putByteArray("jpegData", jpegData) }
        return try {
            val result = context.applicationContext.contentResolver.call(
                uri, METHOD_RECOGNIZE_FERTILIZER, null, extras
            )
            val amount = result?.getInt("result", -1) ?: -1
            if (amount < 0) {
                lastError = "recognize_returned_-1"
                Log.d(TAG, "OCR Provider call returned -1")
            } else {
                Log.d(TAG, "OCR Provider call success: amount=$amount")
            }
            amount
        } catch (e: SecurityException) {
            lastError = "security_exception:${e.message}"
            Log.e(TAG, "OCR Provider call SecurityException: ${e.message}")
            -1
        } catch (e: Exception) {
            lastError = "provider_exception:${e.javaClass.simpleName}:${e.message}"
            Log.e(TAG, "OCR Provider call failed: ${e.message}")
            -1
        }
    }
}
