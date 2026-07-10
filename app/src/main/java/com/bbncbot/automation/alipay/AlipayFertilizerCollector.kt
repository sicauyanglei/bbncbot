package com.bbncbot.automation.alipay

import android.view.accessibility.AccessibilityNodeInfo
import com.bbncbot.automation.FertilizerCollector
import com.bbncbot.automation.Platform
import com.bbncbot.automation.TaskType
import com.bbncbot.service.FarmAccessibilityService

/**
 * 支付宝肥料收集器
 *
 * 平台特点：
 * - 包名：com.eg.android.AlipayGphone
 * - 农场入口：支付宝首页搜索"芭芭农场"或首页应用中心入口
 * - 农场页 Activity：alipaylogin / fragmenttabactivity / h5appactivity
 * - 支付宝和淘宝的导航、任务列表、获取肥料流程不同，需要单独适配
 *
 * TODO: 根据用户截图补充支付宝特有的任务列表和获取肥料路径
 */
object AlipayFertilizerCollector : FertilizerCollector {

    override val platform = Platform.ALIPAY

    // ---------- 任务关键词（支付宝特有） ----------
    /** 浏览任务关键词 */
    private val browseKeywords = listOf(
        "浏览", "逛逛", "精选好物", "心仪", "严选推荐", "发现精选",
        "搜一搜", "宝贝", "好物", "推荐商品", "发现", "严选", "滑动"
    )

    /** 游戏任务关键词 */
    private val gameKeywords = listOf(
        "玩游戏", "游戏", "挑战", "闯关", "消消乐", "斗地主",
        "赢肥料", "玩一玩", "小游戏", "通关", "得分",
        "大转盘", "抽奖", "摇一摇"
    )

    /** 付费任务关键词 */
    private val paidKeywords = listOf("购买", "付款", "充值", "付费", "消费满")

    /** 搜索推荐页关键词 */
    private val searchRecommendKeywords = listOf(
        "下单得肥料", "当前页下单", "搜索有惊喜", "搜一搜浏览",
        "搜索有福利", "搜索后浏览立得奖励", "浏览宝贝得奖励"
    )

    override fun navigateToFarm(service: FarmAccessibilityService): Boolean {
        // 支付宝导航：策略1找"芭芭农场"入口，策略2搜索"芭芭农场"
        service.navigateToFarm()
        return true
    }

    override fun isOnFarmPage(service: FarmAccessibilityService): Boolean {
        return service.isOnFarmPage()
    }

    override fun findDirectCollectButtons(service: FarmAccessibilityService): List<AccessibilityNodeInfo> {
        return service.findDirectCollectButtons()
    }

    override fun findCollectFertilizerButton(service: FarmAccessibilityService): AccessibilityNodeInfo? {
        return service.findCollectFertilizerButton()
    }

    override fun classifyTask(service: FarmAccessibilityService, button: AccessibilityNodeInfo): TaskType {
        val contextText = service.collectTaskContextText(button)

        // 付费任务
        if (paidKeywords.any { contextText.contains(it) }) return TaskType.PAID

        // 游戏任务
        if (gameKeywords.any { contextText.contains(it) }) return TaskType.GAME

        // 浏览任务
        if (browseKeywords.any { contextText.contains(it) }) return TaskType.BROWSE

        // 默认按广告处理
        return TaskType.AD
    }

    override fun findAdCloseButton(service: FarmAccessibilityService): AccessibilityNodeInfo? {
        return service.findAdCloseButton()
    }

    override fun findBackIcon(service: FarmAccessibilityService): AccessibilityNodeInfo? {
        return service.findBackIcon()
    }

    override fun isSearchRecommendPage(service: FarmAccessibilityService): Boolean {
        return service.isSearchRecommendPage()
    }

    override fun isOnAbnormalPage(service: FarmAccessibilityService): Boolean {
        return service.isOnAbnormalPage()
    }

    override fun findSwipeForFertilizerHint(service: FarmAccessibilityService): Int {
        return service.findSwipeForFertilizerHint()
    }
}
