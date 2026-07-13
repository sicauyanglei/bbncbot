// AIDL 接口：主 APK（:app）跨进程调用 :ocr 的识别能力
//
// 设计说明：
// - 截图以 JPEG 压缩后的 byte[] 传输（Binder 事务缓冲区 ~1MB，全分辨率 Bitmap 会超限）
// - recognizeFertilizerAmount: 直接返回肥料数值（-1 表示未识别到）
// - recognizeText: 返回全图识别文本（调试用，主 APK 目前不调用）
package com.bbncbot.ocr;

interface IOcrService {

    /**
     * 识别截图中的肥料总数
     *
     * @param jpegData 截图的 JPEG 字节数组（主 APK 截图后压缩成 JPEG 传入）
     * @return 肥料数值；-1 表示识别失败或未识别到
     */
    int recognizeFertilizerAmount(in byte[] jpegData);

    /**
     * 识别截图中的全部文本（调试用）
     *
     * @param jpegData 截图的 JPEG 字节数组
     * @return 全图识别文本（换行分隔）
     */
    String recognizeText(in byte[] jpegData);
}
