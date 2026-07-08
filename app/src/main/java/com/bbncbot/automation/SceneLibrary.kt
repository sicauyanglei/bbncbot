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
 * 存储"场景签名 → 应执行动作"映射，支持三种来源：
 *
 * 1. **内置默认规则**：[defaultRules] 写死的兜底逻辑（任务完成→退出、异常页→退出等）
 * 2. **录制规则**：用户开启录制模式后，每个操作（滑动/返回/点击）都会被记录成规则
 * 3. **手动规则**：直接编辑 scene_rules.json
 *
 * 工作模式：
 * - **自动执行**（默认）：bot 遇到决策点先查规则库，命中规则直接执行，**不询问用户**
 * - **录制模式**：用户开启后 bot 暂停自动执行，用户的每个操作被记录成规则
 * - **中断**：用户随时点浮窗"中断"按钮，bot 立即停止当前动作
 *
 * 规则匹配优先级：
 * - 学习/录制规则（精确签名匹配，confidence 高的优先） > 内置默认规则 > null（未命中）
 *
 * 数据格式（JSON）：
 * ```json
 * [
 *   {
 *     "signature": "p=UC|farm=false|type=browse|countdown=yes|progress=yes",
 *     "action": "SWIPE_UP",
 *     "targetButton": null,
 *     "source": "recording",
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
        val source: String,            // recording / manual / default
        val createdAt: String,
        var hitCount: Int = 0,
        var confidence: Double = 1.0
    )

    /** 规则文件路径 */
    private fun rulesFile(): File = File(
        android.os.Environment.getExternalStorageDirectory(),
        "Android/data/com.bbncbot/files/scene_rules.json"
    )

    /** 录制日志文件（记录每次录制操作的完整上下文） */
    private fun recordingLogFile(): File = File(
        android.os.Environment.getExternalStorageDirectory(),
        "Android/data/com.bbncbot/files/recording.log"
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
            targetButton = "关闭",
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
     * 1. 学习/录制规则（精确签名匹配，confidence 高的优先）
     * 2. 默认规则（通配符匹配 signature 子串）
     *
     * @param features 当前场景特征
     * @return 匹配的规则，null 表示未命中
     */
    fun match(features: SceneFeatures): Rule? {
        ensureInitialized()
        val sig = features.signature()
        synchronized(lock) {
            // 1. 学习/录制规则：精确签名匹配
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
     * 录制规则：用户在场景 X 下执行了动作 Y，记录成规则
     *
     * - 自动生成或更新规则（同签名同动作 → confidence +0.1）
     * - 完整记录到 recording.log（含场景特征、动作、时间戳）
     * - 新规则初始 confidence = 1.0（用户亲自操作，可信度高）
     *
     * @param features 当前场景特征（录制时提取）
     * @param action 用户执行的动作
     * @param targetButton 点击动作的目标按钮文案（可为 null）
     */
    fun recordRule(features: SceneFeatures, action: Action, targetButton: String? = null) {
        ensureInitialized()
        val sig = features.signature()
        synchronized(lock) {
            // 查找是否已有同签名同动作的规则
            val existing = rules.firstOrNull {
                it.signature == sig && it.action == action && it.targetButton == targetButton
            }
            if (existing != null) {
                // 已有规则：强化（confidence +0.1，最多 1.0）
                existing.hitCount++
                existing.confidence = (existing.confidence + 0.1).coerceAtMost(1.0)
                Log.i(TAG, "recording: reinforced rule sig=$sig action=$action confidence=${existing.confidence} hits=${existing.hitCount}")
            } else {
                // 新规则：source=recording, confidence=1.0（用户亲自操作）
                val newRule = Rule(
                    signature = sig,
                    action = action,
                    targetButton = targetButton,
                    source = "recording",
                    createdAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()),
                    hitCount = 1,
                    confidence = 1.0
                )
                rules = rules + newRule
                Log.i(TAG, "recording: added new rule sig=$sig action=$action target=$targetButton")
            }
            // 记录录制日志
            logRecording(features, action, targetButton)
            persistRulesAsync()
        }
    }

    /**
     * 删除指定场景的规则（用户中断后可调用，表示"这个场景不该这么操作"）
     */
    fun removeRule(features: SceneFeatures, action: Action? = null) {
        ensureInitialized()
        val sig = features.signature()
        synchronized(lock) {
            val before = rules.size
            rules = rules.filter { rule ->
                // 保留默认规则
                if (rule.source == "default") return@filter true
                // 删除匹配的规则
                if (rule.signature != sig) return@filter true
                if (action != null && rule.action != action) return@filter true
                false
            }
            val removed = before - rules.size
            if (removed > 0) {
                Log.i(TAG, "removeRule: removed $removed rules for sig=$sig action=$action")
                persistRulesAsync()
            }
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

    /** 录制日志 */
    private fun logRecording(features: SceneFeatures, action: Action, targetButton: String?) {
        try {
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            val line = "$time RECORD sig='${features.signature()}' action=$action target='$targetButton' " +
                "state=${features.controllerState} btns=[${features.clickableButtons.joinToString(",")}]\n"
            recordingLogFile().apply { parentFile?.mkdirs() }.appendText(line)
        } catch (e: Exception) {
            Log.w(TAG, "logRecording failed: ${e.message}")
        }
    }

    /** 统计：录制规则数量 */
    fun recordingRuleCount(): Int {
        ensureInitialized()
        return synchronized(lock) { rules.count { it.source == "recording" } }
    }
}
