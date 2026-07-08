package com.bbncbot.automation

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 场景规则库
 *
 * 存储"场景签名 → 应执行动作"映射，并从用户的教导/批准记录中自动学习。
 *
 * 三种来源：
 * 1. **内置默认规则**：[defaultRules] 写死的兜底逻辑（任务完成→退出、异常页→退出等）
 * 2. **学习规则**：从 [teachingsLog] + [proposalsLog] 自动派生，存到 [rulesFile]
 * 3. **在线学习**：[recordTeaching] / [recordApproval] 实时记录用户决策，触发规则更新
 *
 * 规则匹配优先级：
 * - 学习规则（精确签名匹配） > 内置默认规则 > null（未命中，走原关键词逻辑）
 *
 * 数据格式（JSON）：
 * ```json
 * [
 *   {
 *     "signature": "p=UC|farm=false|type=browse|countdown=yes|progress=yes|btns=去完成,关闭",
 *     "action": "swipe_up",
 *     "source": "teaching",
 *     "createdAt": "2026-07-08T10:30:00",
 *     "hitCount": 3,
 *     "confidence": 0.9
 *   }
 * ]
 * ```
 *
 * 线程安全：所有公开方法都 synchronized。
 */
object SceneLibrary {

    private const val TAG = "SceneLibrary"

    /** 规则动作枚举（与 [AutomationController] 内部动作对齐） */
    enum class Action {
        SWIPE_UP,           // 向上滑动（页面向下滚）
        SWIPE_DOWN,         // 向下滑动（页面向上滚）
        BACK,               // 返回
        EXIT_TASK,          // 退出当前任务
        WAIT,               // 等待下次轮询
        CLICK_BUTTON,       // 点击按钮（按钮文案在 targetButton 字段）
        STOP_AUTOMATION,    // 停止自动化
        UNKNOWN             // 未知（未命中规则）
    }

    data class Rule(
        val signature: String,
        val action: Action,
        val targetButton: String?,     // action=CLICK_BUTTON 时填按钮文案
        val source: String,            // teaching / proposal / default
        val createdAt: String,
        var hitCount: Int = 0,
        var confidence: Double = 1.0   // 学习规则初始 0.5，每被命中/确认 +0.1
    )

    /** 规则文件路径 */
    private fun rulesFile(): File = File(
        android.os.Environment.getExternalStorageDirectory(),
        "Android/data/com.bbncbot/files/scene_rules.json"
    )

    /** teachings.log 路径（与 TeachCommandParser 一致） */
    private fun teachingsFile(): File = File(
        android.os.Environment.getExternalStorageDirectory(),
        "Android/data/com.bbncbot/files/teachings.log"
    )

    /** proposals.log 路径（与 ActionProposer 一致） */
    private fun proposalsFile(): File = File(
        android.os.Environment.getExternalStorageDirectory(),
        "Android/data/com.bbncbot/files/proposals.log"
    )

    /** 内存中的规则列表（启动时从文件加载 + 默认规则） */
    @Volatile
    private var rules: List<Rule> = emptyList()

    /** 是否已初始化（懒加载，第一次 [match] 时触发） */
    @Volatile
    private var initialized: Boolean = false

    private val lock = Any()

    /** 内置默认规则（最低优先级，可被学习规则覆盖） */
    private val defaultRules: List<Rule> = listOf(
        Rule(
            signature = "*type=complete*",
            action = Action.EXIT_TASK,
            targetButton = null,
            source = "default",
            createdAt = "builtin",
            hitCount = 0,
            confidence = 1.0
        ),
        Rule(
            signature = "*type=abnormal*",
            action = Action.EXIT_TASK,
            targetButton = null,
            source = "default",
            createdAt = "builtin",
            hitCount = 0,
            confidence = 1.0
        ),
        Rule(
            signature = "*type=paid_search*",
            action = Action.EXIT_TASK,
            targetButton = null,
            source = "default",
            createdAt = "builtin",
            hitCount = 0,
            confidence = 1.0
        ),
        Rule(
            signature = "*popup=red_packet*",
            action = Action.CLICK_BUTTON,
            targetButton = "关闭",   // findRedPacketCloseButton 已实现，这里示意
            source = "default",
            createdAt = "builtin",
            hitCount = 0,
            confidence = 1.0
        )
    )

    /** 初始化：加载文件规则 + 默认规则 */
    private fun ensureInitialized() {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            val loaded = loadRulesFromFile()
            rules = loaded + defaultRules
            initialized = true
            Log.i(TAG, "SceneLibrary initialized: ${loaded.size} learned + ${defaultRules.size} default rules")
        }
    }

    /**
     * 匹配场景特征，返回建议动作
     *
     * 匹配顺序：
     * 1. 学习规则（精确签名匹配，confidence 高的优先）
     * 2. 默认规则（通配符匹配 signature 子串）
     *
     * @param features 当前场景特征
     * @return 匹配的规则，null 表示未命中
     */
    fun match(features: SceneFeatures): Rule? {
        ensureInitialized()
        val sig = features.signature()
        synchronized(lock) {
            // 1. 学习规则：精确签名匹配
            val learned = rules.filter { it.source != "default" && it.signature == sig }
                .maxByOrNull { it.confidence }
            if (learned != null) {
                learned.hitCount++
                Log.i(TAG, "matched learned rule: sig=$sig action=${learned.action} hits=${learned.hitCount}")
                persistRulesAsync()
                return learned
            }
            // 2. 默认规则：通配符匹配（signature 含规则的子串，规则两端可加 *）
            val defaulted = rules.filter { it.source == "default" }
                .firstOrNull { rule ->
                    val pattern = rule.signature
                    val pure = pattern.trim('*')
                    if (pattern.startsWith("*") && pattern.endsWith("*")) {
                        sig.contains(pure)
                    } else if (pattern.startsWith("*")) {
                        sig.endsWith(pure)
                    } else if (pattern.endsWith("*")) {
                        sig.startsWith(pure)
                    } else {
                        sig == pattern
                    }
                }
            if (defaulted != null) {
                defaulted.hitCount++
                Log.d(TAG, "matched default rule: action=${defaulted.action}")
                return defaulted
            }
            return null
        }
    }

    /**
     * 记录用户教导：用户在场景 X 下明确指示执行动作 Y
     * - 自动生成或更新规则
     * - 命中已有规则时 confidence +0.1（最多 1.0）
     */
    fun recordTeaching(features: SceneFeatures, action: Action, targetButton: String? = null) {
        ensureInitialized()
        val sig = features.signature()
        synchronized(lock) {
            val existing = rules.firstOrNull { it.signature == sig && it.action == action }
            if (existing != null) {
                existing.hitCount++
                existing.confidence = (existing.confidence + 0.1).coerceAtMost(1.0)
                Log.i(TAG, "reinforced rule: sig=$sig action=$action confidence=${existing.confidence}")
            } else {
                val newRule = Rule(
                    signature = sig,
                    action = action,
                    targetButton = targetButton,
                    source = "teaching",
                    createdAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()),
                    hitCount = 1,
                    confidence = 0.5
                )
                rules = rules + newRule
                Log.i(TAG, "learned new rule: sig=$sig action=$action")
            }
            persistRulesAsync()
        }
    }

    /**
     * 记录用户对提议的响应：
     * - APPROVE：强化"当前场景 → 拟动作"规则
     * - REJECT：降低该规则 confidence（若有），并记录反例
     */
    fun recordApproval(features: SceneFeatures, proposedAction: Action, response: ActionProposer.Response) {
        ensureInitialized()
        val sig = features.signature()
        synchronized(lock) {
            when (response) {
                ActionProposer.Response.APPROVE -> {
                    val existing = rules.firstOrNull { it.signature == sig && it.action == proposedAction }
                    if (existing != null) {
                        existing.hitCount++
                        existing.confidence = (existing.confidence + 0.1).coerceAtMost(1.0)
                    } else {
                        rules = rules + Rule(
                            signature = sig,
                            action = proposedAction,
                            targetButton = null,
                            source = "proposal",
                            createdAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()),
                            hitCount = 1,
                            confidence = 0.5
                        )
                    }
                    Log.i(TAG, "approval reinforced: sig=$sig action=$proposedAction")
                }
                ActionProposer.Response.REJECT -> {
                    val existing = rules.firstOrNull { it.signature == sig && it.action == proposedAction }
                    if (existing != null) {
                        existing.confidence = (existing.confidence - 0.2).coerceAtLeast(0.0)
                        Log.i(TAG, "reject reduced: sig=$sig action=$proposedAction confidence=${existing.confidence}")
                        // confidence 降到 0 删除规则
                        if (existing.confidence <= 0.0) {
                            rules = rules - existing
                            Log.i(TAG, "rule removed due to low confidence: sig=$sig")
                        }
                    }
                }
                ActionProposer.Response.SKIP -> {
                    // 跳过不影响规则
                }
            }
            persistRulesAsync()
        }
    }

    /** 从文件加载规则 */
    private fun loadRulesFromFile(): List<Rule> {
        val file = rulesFile()
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Rule(
                    signature = o.getString("signature"),
                    action = Action.valueOf(o.getString("action")),
                    targetButton = o.optString("targetButton", null),
                    source = o.getString("source"),
                    createdAt = o.getString("createdAt"),
                    hitCount = o.optInt("hitCount", 0),
                    confidence = o.optDouble("confidence", 0.5)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "loadRulesFromFile failed: ${e.message}")
            emptyList()
        }
    }

    /** 异步持久化规则到文件（避免阻塞主线程） */
    private fun persistRulesAsync() {
        Thread {
            try {
                val arr = JSONArray()
                rules.filter { it.source != "default" }.forEach { rule ->
                    arr.put(JSONObject().apply {
                        put("signature", rule.signature)
                        put("action", rule.action.name)
                        put("targetButton", rule.targetButton)
                        put("source", rule.source)
                        put("createdAt", rule.createdAt)
                        put("hitCount", rule.hitCount)
                        put("confidence", rule.confidence)
                    })
                }
                rulesFile().apply { parentFile?.mkdirs() }.writeText(arr.toString(2))
                Log.d(TAG, "persisted ${arr.length()} rules to ${rulesFile().absolutePath}")
            } catch (e: Exception) {
                Log.w(TAG, "persistRulesAsync failed: ${e.message}")
            }
        }.start()
    }

    /** 调试用：列出所有规则 */
    fun dumpRules(): List<Rule> {
        ensureInitialized()
        return synchronized(lock) { rules.toList() }
    }

    /**
     * 从 teachings.log + proposals.log 重新派生规则（离线学习）
     *
     * 启动时调用一次，把历史日志转成规则。
     * 注：当前实现仅扫描 teachings.log，proposals.log 因格式复杂暂未解析。
     */
    fun rebuildFromLogs() {
        ensureInitialized()
        synchronized(lock) {
            var learned = 0
            // teachings.log 格式：HH:mm:ss.SSS TEACH input='xxx'
            // 但 teachings.log 没记录场景签名，需要场景特征配合才能学
            // 这里只做"清空规则文件触发重新学习"的入口，实际学习在 recordTeaching/recordApproval 时发生
            Log.i(TAG, "rebuildFromLogs: skipped (teachings need scene features, learned at runtime)")
        }
    }
}
