package com.bbncbot.automation

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 节点查找工具类：递归遍历无障碍节点树，按关键词匹配可点击节点
 */
object NodeFinder {

    private const val TAG = "NodeFinder"

    /** 饲料收集关键词 */
    val FEED_KEYWORDS = listOf(
        "领取", "收集", "饲料", "签到", "去领取", "立即领取", "去收集", "点击领取"
    )

    /** 施肥关键词 */
    val FERTILIZE_KEYWORDS = listOf(
        "施肥", "给TA施肥", "帮Ta施肥", "帮TA施肥", "给Ta施肥"
    )

    /** 广告相关关键词（看广告获取肥料） */
    val AD_KEYWORDS = listOf(
        "去完成", "看广告", "看视频", "免费肥料", "免费饲料", "双倍", "加倍",
        "观看广告", "ad", "免费领取", "立即观看", "获取肥料", "获取饲料"
    )

    /** 关闭/确认弹窗关键词 */
    val DISMISS_KEYWORDS = listOf(
        "关闭", "×", "我知道了", "知道了", "确定", "领取", "好的", "继续"
    )

    /**
     * 在根节点下查找所有文本包含任一关键词的可点击节点
     * @param root 根节点
     * @param keywords 关键词列表
     * @return 匹配的节点列表（已去重）
     */
    fun findClickableNodesByText(
        root: AccessibilityNodeInfo?,
        keywords: List<String>
    ): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        val result = mutableListOf<AccessibilityNodeInfo>()
        val seen = HashSet<Int>()
        traverse(root, keywords, result, seen)
        return result
    }

    private fun traverse(
        node: AccessibilityNodeInfo,
        keywords: List<String>,
        out: MutableList<AccessibilityNodeInfo>,
        seen: HashSet<Int>
    ) {
        if (matches(node, keywords)) {
            val clickable = findClickableSelfOrParent(node)
            if (clickable != null) {
                val hash = System.identityHashCode(clickable)
                if (seen.add(hash)) {
                    out.add(clickable)
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverse(child, keywords, out, seen)
        }
    }

    /** 节点文本或内容描述是否包含任一关键词 */
    private fun matches(node: AccessibilityNodeInfo, keywords: List<String>): Boolean {
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        if (text.isEmpty() && desc.isEmpty()) return false
        return keywords.any { kw ->
            text.contains(kw, ignoreCase = true) ||
                    desc.contains(kw, ignoreCase = true)
        }
    }

    /** 向上查找最近的可点击父节点（含自身） */
    private fun findClickableSelfOrParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 10) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        // 没有可点击父节点时返回自身，由调用方决定是否 dispatchGesture
        return node
    }

    /**
     * 在节点树中查找第一个匹配的节点
     */
    fun findFirstByText(
        root: AccessibilityNodeInfo?,
        keywords: List<String>
    ): AccessibilityNodeInfo? {
        if (root == null) return null
        if (matches(root, keywords)) {
            return findClickableSelfOrParent(root)
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val matched = findFirstByText(child, keywords)
            if (matched != null) return matched
        }
        return null
    }

    /**
     * 尝试向下滚动可滚动节点，便于加载更多内容
     * @return true 表示执行了滚动
     */
    fun scrollDown(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val scrollable = findScrollableNode(root)
        if (scrollable != null) {
            val result = scrollable.performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id
            )
            Log.d(TAG, "scrollForward result=$result")
            return result
        }
        return false
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val matched = findScrollableNode(child)
            if (matched != null) return matched
        }
        return null
    }
}
