package com.bbncbot.automation.uc

import android.view.accessibility.AccessibilityNodeInfo
import com.bbncbot.automation.FertilizerCollector
import com.bbncbot.automation.Platform
import com.bbncbot.automation.TaskType
import com.bbncbot.service.FarmAccessibilityService

/**
 * UC 极速版肥料收集器
 *
 * 平台特点：
 * - 包名：com.ucmobile.lite
 * - 农场入口：UC 主页搜索"芭芭农场"或直接访问 URL
 * - 农场页 Activity：innerucmobile / mainactivity
 * - 坐标基于 v11 PC 端 ADB 方案经验（屏幕 896x1980）
 *
 * TODO: 根据用户截图补充 UC 极速版特有的任务列表和获取肥料路径
 */
object UcFertilizerCollector : FertilizerCollector {

    override val platform = Platform.UC

    // ---------- 任务关键词（UC 极速版特有） ----------
    // UC 广告设计特点：自有广告 + 穿山甲 SDK，游戏任务少，广告任务为主
    // 任务关键词已移至 PlatformConfig（UcPlatformConfig），此处保留 searchRecommendKeywords 供 isSearchRecommendPage 使用

    /** 搜索推荐页关键词（UC 特有，沿用旧逻辑） */
    private val searchRecommendKeywords = listOf(
        "下单得肥料", "当前页下单", "搜索有惊喜", "搜一搜浏览",
        "搜索有福利", "搜索后浏览立得奖励", "浏览宝贝得奖励"
    )

    override fun navigateToFarm(service: FarmAccessibilityService): Boolean {
        // UC 导航：主页搜索"芭芭农场"或直接访问 URL
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
        // UC 平台：直接委托给 service.isXxxTask（已合并平台专属关键词）
        // UC 特点：游戏任务少，广告任务为主，付费陷阱少
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
