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
    // 支付宝广告设计特点：H5 容器 + 小程序，小游戏入口最多，金融类陷阱多
    // 任务关键词已移至 PlatformConfig（AlipayPlatformConfig），此处保留 searchRecommendKeywords

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
        // 支付宝平台：委托给 service.isXxxTask（已合并支付宝专属关键词）
        // 支付宝特点：小游戏入口多（蚂蚁庄园/蚂蚁森林），金融类陷阱多（充值/理财/会员）
        if (service.isPaidTask(button)) return TaskType.PAID
        if (service.isGameTask(button)) return TaskType.GAME
        if (service.isBrowseTask(button)) return TaskType.BROWSE
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
