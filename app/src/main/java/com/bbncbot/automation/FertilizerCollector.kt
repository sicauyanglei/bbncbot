package com.bbncbot.automation

import android.view.accessibility.AccessibilityNodeInfo
import com.bbncbot.service.FarmAccessibilityService

/**
 * 平台肥料收集器接口
 *
 * 每个平台（支付宝/淘宝/UC极速版）实现此接口，封装该平台特有的：
 * - 导航到芭芭农场页
 * - 识别和收集直接可领取的肥料
 * - 打开任务列表
 * - 识别任务类型（看广告/浏览/游戏/付费等）
 * - 处理浏览类任务（滑动获取肥料）
 * - 关闭广告
 * - 返回农场页
 *
 * AutomationController 通过此接口调用平台特有逻辑，实现多平台差异化处理。
 */
interface FertilizerCollector {

    /** 平台枚举 */
    val platform: Platform

    /**
     * 导航到芭芭农场页
     * @return true 表示导航已触发（不保证成功，需要后续验证 isOnFarmPage）
     */
    fun navigateToFarm(service: FarmAccessibilityService): Boolean

    /**
     * 判断当前是否在芭芭农场种植页
     * - 每个平台的农场页内容可能略有不同
     * @return true 表示在农场页
     */
    fun isOnFarmPage(service: FarmAccessibilityService): Boolean

    /**
     * 查找农场主页上可直接领取的肥料按钮
     * - 如"兔兔挖肥料，50肥料，可领取"
     * @return 可点击的领取按钮列表，空列表表示没有可领取的
     */
    fun findDirectCollectButtons(service: FarmAccessibilityService): List<AccessibilityNodeInfo>

    /**
     * 查找"集肥料"按钮（打开任务列表的入口）
     * @return 可点击的"集肥料"按钮，null 表示未找到
     */
    fun findCollectFertilizerButton(service: FarmAccessibilityService): AccessibilityNodeInfo?

    /**
     * 判断"去完成"按钮对应的任务类型
     * @return 任务类型（AD=看广告, BROWSE=浏览, GAME=游戏, PAID=付费, UNKNOWN=未知）
     */
    fun classifyTask(service: FarmAccessibilityService, button: AccessibilityNodeInfo): TaskType

    /**
     * 查找广告/弹窗的关闭按钮
     * - 优先查找右上角关闭图标
     * - 其次查找"关闭"文字按钮
     * @return 可点击的关闭按钮，null 表示未找到
     */
    fun findAdCloseButton(service: FarmAccessibilityService): AccessibilityNodeInfo?

    /**
     * 查找左上角返回图标（退出浏览/下单页面用）
     * @return 可点击的返回按钮，null 表示未找到
     */
    fun findBackIcon(service: FarmAccessibilityService): AccessibilityNodeInfo?

    /**
     * 检测当前页面是否是搜索推荐页（需要退出的页面）
     * - 如"下单得肥料"、"搜索有惊喜"等
     * @return true 表示是搜索推荐页
     */
    fun isSearchRecommendPage(service: FarmAccessibilityService): Boolean

    /**
     * 检测当前页面是否是异常页面（交易/付款/商品详情等页面）
     * - 禁止交易获取肥料：所有交易相关页面都视为异常，遇到时立即退出
     * - 如收银台、商品详情页、订单确认页等
     * @return true 表示是异常页面
     */
    fun isOnAbnormalPage(service: FarmAccessibilityService): Boolean

    /**
     * 检测页面是否有"滑动获取肥料"提示，返回需要滑动的秒数
     * @return 需要滑动的秒数，0 表示没有找到提示
     */
    fun findSwipeForFertilizerHint(service: FarmAccessibilityService): Int
}

/** 任务类型 */
enum class TaskType {
    /** 看广告任务（观看视频广告得肥料） */
    AD,
    /** 浏览任务（滑动浏览商品/页面得肥料） */
    BROWSE,
    /** 游戏任务（需要玩游戏，跳过） */
    GAME,
    /** 付费任务（需要花钱，跳过） */
    PAID,
    /** 未知任务类型（默认按广告处理） */
    UNKNOWN
}
