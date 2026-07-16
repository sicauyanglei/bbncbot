package com.bbncbot.automation

/**
 * 场景规则库（已精简）
 *
 * 录制 / 匹配 / 持久化功能已随"去除录制功能"整体移除，本对象仅保留被
 * [AutomationController] 等处继续引用的最小成员：
 *
 * - [Action]：规则动作枚举，AutomationController 的 MatchedRule 与多处 when 分支引用
 * - [getPriorityForTask]：AutomationController 用于任务排序（规则库已移除，统一返回 0）
 */
object SceneLibrary {

    /** 规则动作枚举 */
    enum class Action {
        SWIPE_UP,           // 向上滑动
        SWIPE_DOWN,         // 向下滑动
        BACK,               // 返回
        EXIT_TASK,          // 退出当前任务
        WAIT,               // 等待
        CLICK_BUTTON,       // 点击按钮（targetButton 字段）
        STOP_AUTOMATION,    // 停止自动化
        UNKNOWN
    }

    /**
     * 查询指定平台 + 任务内容标识对应的规则优先级。
     *
     * 规则库已移除，所有任务优先级相同，统一返回 0。
     *
     * @param platform 平台标识（"UC" / "ALIPAY" / "TAOBAO"）
     * @param taskContent 任务内容标识
     * @return 优先级（数值小的先执行），统一返回 0
     */
    fun getPriorityForTask(platform: String, taskContent: String): Int = 0
}
