package com.bbncbot.ocr

import com.bbncbot.service.FarmAccessibilityService

/**
 * OCR 肥料读取 - noOcr flavor 空实现
 *
 * 此 flavor 不打包 ML Kit 依赖，APK 体积小（不含 ~20-30MB 的中文识别模型），
 * 用于日常调试（规则匹配/回放/录制逻辑验证不需要 OCR）。
 *
 * 调用 [findCurrentFertilizerAmount] 直接返回 -1，表示 OCR 不可用，
 * 上层 [com.bbncbot.automation.RecordingManager] 会按"无障碍读不到 → 无 OCR 兜底"记录日志。
 *
 * 调试稳定后切到 full flavor（`./gradlew assembleFullRelease`）即自动启用真实 OCR。
 */
object OcrProvider {

    /**
     * 空实现：不识别，直接返回 -1
     *
     * @param service 无障碍服务实例（noOcr flavor 不使用）
     * @return 固定 -1（OCR 不可用）
     */
    fun findCurrentFertilizerAmount(service: FarmAccessibilityService): Int = -1
}
