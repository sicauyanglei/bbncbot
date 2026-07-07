package com.bbncbot.automation

/**
 * 自动化状态机状态
 *
 * v2 重构状态机 - 匹配用户要求的导航流程：
 * 主页右下角任务进入 → 右上角芭芭农场 → 农场主页 → 点击集肥料按钮 → 点击各个去完成按钮获取肥料
 *
 * 流程：NAVIGATING → OPENING_TASK_LIST → PROCESSING_TASK → WATCHING_AD → CLOSING_AD → RETURNING → PROCESSING_TASK (下一个) → ...
 * 如果任务列表所有任务处理完，回到 NAVIGATING 重新开始
 */
enum class AutomationState {
    /** 空闲 */
    IDLE,
    /** 导航中：主页→任务→芭芭农场→农场主页 */
    NAVIGATING,
    /** 收集农场主页上直接可领取的肥料（兔兔挖肥料、定时肥料等） */
    COLLECTING_DIRECT,
    /** 打开任务列表中：点击集肥料按钮 */
    OPENING_TASK_LIST,
    /** 处理任务中：点击去完成/去签到/去答题/去领取按钮（看广告类任务） */
    PROCESSING_TASK,
    /** 滑动浏览任务中：模拟上下滑动浏览页面获取肥料 */
    BROWSING_TASK,
    /** 玩游戏任务中：AI 游戏达人分析画面并操作完成游戏升级 */
    GAME_PLAYING,
    /** 看广告中：等待广告播放完成 */
    WATCHING_AD,
    /** 关闭广告中：尝试关闭广告并领取奖励 */
    CLOSING_AD,
    /** 从广告返回任务列表中 */
    RETURNING,
    /** 施肥中：所有肥料收集完后，点击施肥按钮 */
    FERTILIZING,
    /** 跨平台切换中：在支付宝/淘宝之间切换获取肥料，完成后回到原平台 */
    SWITCHING_PLATFORM,
    /** 一轮结束，等待下一轮 */
    WAITING,
    /** 正在停止 */
    STOPPING
}
