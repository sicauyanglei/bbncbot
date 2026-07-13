// 主 APK（:app）侧的 AIDL 接口定义
// 必须与 :ocr 模块的 IOcrService.aidl 完全一致（包名、方法签名），
// 编译后双方使用同一份 Stub/Proxy 代码进行 Binder 事务。
package com.bbncbot.ocr;

interface IOcrService {

    /**
     * 识别截图中的肥料总数
     *
     * @param jpegData 截图的 JPEG 字节数组
     * @return 肥料数值；-1 表示识别失败或未识别到
     */
    int recognizeFertilizerAmount(in byte[] jpegData);

    /**
     * 识别截图中的全部文本（调试用）
     */
    String recognizeText(in byte[] jpegData);
}
