package com.bbncbot.automation.taobao

import android.view.accessibility.AccessibilityNodeInfo
import com.bbncbot.automation.FertilizerCollector
import com.bbncbot.automation.Platform
import com.bbncbot.automation.TaskType
import com.bbncbot.service.FarmAccessibilityService

/**
 * 淘宝肥料收集器
 *
 * 平台特点：
 * - 包名：com.taobao.taobao
 * - 农场入口：淘宝首页"推荐"feed 中的"芭芭农场"卡片，或"我的淘宝"tab 进入
 * - 农场页 Activity：tmsactivity / multipagecontaineractivity
 * - 导航：在主页找"芭芭农场"节点点击，失败时兜底从"我的淘宝"tab → "芭芭农场"入口（手势坐标）
 * - 淘宝主页"芭芭农场"节点用 content-desc（不是 text）
 *
 * TODO: 根据用户截图补充淘宝特有的任务列表和获取肥料路径
 */
object TaobaoFertilizerCollector : FertilizerCollector {

    override val platform = Platform.TAOBAO

    // ---------- 任务关键词（淘宝特有） ----------
    // 淘宝广告设计特点：小程序容器 + 信息流，浏览任务最多，下单陷阱最多
    // 任务关键词已移至 PlatformConfig（TaobaoPlatformConfig），此处保留 searchRecommendKeywords

    /** 搜索推荐页关键词 */
    private val searchRecommendKeywords = listOf(
        "下单得肥料", "当前页下单", "搜索有惊喜", "搜一搜浏览",
        "搜索有福利", "搜索后浏览立得奖励", "浏览宝贝得奖励"
    )

    override fun navigateToFarm(service: FarmAccessibilityService): Boolean {
        // 淘宝导航：stepClickFarmTab（在主页找"芭芭农场"节点）失败后兜底 stepClickFarmTabByGesture
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
        // 淘宝平台：委托给 service.isXxxTask（已合并淘宝专属关键词）
        // 淘宝特点：浏览任务最多（逛会场/逛店铺），下单陷阱最多（下单得肥料/当前页下单）
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
