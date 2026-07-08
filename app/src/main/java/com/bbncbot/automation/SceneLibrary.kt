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
 * 设计核心：**场景由用户定义**，自动签名仅作辅助识别。
 *
 * - **sceneCategory**：用户命名的场景分类（如"浏览15秒任务"）
 *   相同 category 的多个签名共享同一规则
 * - **signature**：自动提取的场景特征签名（用于运行时匹配）
 *   一个 category 可能有多个 signature（如倒计时秒数不同），但规则相同
 *
 * 工作模式：
 * 1. **录制**：用户开启录制 → 手动操作 → bot 提取场景特征 + 询问场景归类
 *    - 用户输入场景名（如"浏览15秒任务"）
 *    - bot 记录 signature → category 映射 + 对应规则
 * 2. **执行**：bot 遇到场景 → 提取 signature → 查找所属 category → 按规则执行
 *    - 如果 signature 已归属某 category，直接按规则执行
 *    - 如果 signature 未归属任何 category，bot 询问用户归类
 * 3. **中断**：用户点中断 → 停止 bot + 可选删除当前 category 的规则
 *
 * 规则匹配优先级：
 * 1. signature 精确匹配 → 直接执行对应规则
 * 2. signature 所属 category → 执行 category 的规则
 * 3. 默认规则（type=complete 等通配）
 * 4. 未命中 → 不执行
 *
 * 数据结构：
 * - **SceneCategory**：用户命名的场景（含规则）
 * - **SignatureMapping**：signature → categoryId 映射（同 category 可有多个 signature）
 *
 * 文件格式（scene_rules.json）：
 * ```json
 * {
 *   "categories": [
 *     {
 *       "id": "cat_1",
 *       "name": "浏览15秒任务",
 *       "action": "SWIPE_UP",
 *       "targetButton": null,
 *       "createdAt": "...",
 *       "hitCount": 3
 *     }
 *   ],
 *   "mappings": [
 *     {
 *       "signature": "p=UC|farm=false|type=browse|countdown=yes|...",
 *       "categoryId": "cat_1",
 *       "firstSeen": "...",
 *       "matchCount": 2
 *     }
 *   ]
 * }
 * ```
 */
object SceneLibrary {

    private const val TAG = "SceneLibrary"

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

    /** 用户命名的场景分类（一个分类对应一条规则，可被多个 signature 匹配） */
    data class SceneCategory(
        val id: String,                 // cat_1, cat_2, ...
        var name: String,               // 用户命名，如"浏览15秒任务"
        val action: Action,
        val targetButton: String?,
        val createdAt: String,
        var hitCount: Int = 0
    )

    /** signature → categoryId 映射 */
    data class SignatureMapping(
        val signature: String,
        val categoryId: String,
        val firstSeen: String,
        var matchCount: Int = 0
    )

    /** 旧版 Rule 类型（向后兼容读取，读后转换为 SceneCategory） */
    private data class LegacyRule(
        val signature: String,
        val action: Action,
        val targetButton: String?,
        val source: String,
        val createdAt: String,
        val hitCount: Int,
        val confidence: Double
    )

    private fun rulesFile(): File = File(
        android.os.Environment.getExternalStorageDirectory(),
        "Android/data/com.bbncbot/files/scene_rules.json"
    )

    private fun recordingLogFile(): File = File(
        android.os.Environment.getExternalStorageDirectory(),
        "Android/data/com.bbncbot/files/recording.log"
    )

    @Volatile private var categories: MutableList<SceneCategory> = mutableListOf()
    @Volatile private var mappings: MutableList<SignatureMapping> = mutableListOf()
    @Volatile private var initialized: Boolean = false
    private val lock = Any()

    /** 内置默认规则（按 signature 通配匹配） */
    private data class DefaultRule(val signaturePattern: String, val action: Action, val targetButton: String?)
    private val defaultRules: List<DefaultRule> = listOf(
        DefaultRule("*type=complete*", Action.EXIT_TASK, null),
        DefaultRule("*type=abnormal*", Action.EXIT_TASK, null),
        DefaultRule("*type=paid_search*", Action.EXIT_TASK, null),
        DefaultRule("*popup=red_packet*", Action.CLICK_BUTTON, "关闭")
    )

    private fun ensureInitialized() {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            loadFromFile()
            initialized = true
            Log.i(TAG, "SceneLibrary initialized: ${categories.size} categories, ${mappings.size} mappings")
        }
    }

    /**
     * 匹配场景：signature → category → 规则
     *
     * 返回三元组：
     * - [Matched]：已命中规则（category 不为 null）
     * - [Unmapped]：signature 未归属任何 category（需要用户归类）
     * - [Defaulted]：未命中 category 但命中默认规则
     */
    sealed class MatchResult {
        data class Matched(val category: SceneCategory) : MatchResult()
        data class Unmapped(val signature: String) : MatchResult()
        data class Defaulted(val action: Action, val targetButton: String?) : MatchResult()
        object None : MatchResult()
    }

    /**
     * 查询场景的匹配结果
     *
     * @param features 当前场景特征
     * @return [MatchResult]
     */
    fun match(features: SceneFeatures): MatchResult {
        ensureInitialized()
        val sig = features.signature()
        synchronized(lock) {
            // 1. signature 精确匹配 → 找到所属 category
            val mapping = mappings.firstOrNull { it.signature == sig }
            if (mapping != null) {
                val cat = categories.firstOrNull { it.id == mapping.categoryId }
                if (cat != null) {
                    mapping.matchCount++
                    cat.hitCount++
                    Log.i(TAG, "matched: sig=$sig -> category='${cat.name}' action=${cat.action} hits=${cat.hitCount}")
                    persistAsync()
                    return MatchResult.Matched(cat)
                }
            }
            // 2. 默认规则
            for (dr in defaultRules) {
                val pattern = dr.signaturePattern
                val pure = pattern.trim('*')
                val matched = when {
                    pattern.startsWith("*") && pattern.endsWith("*") -> sig.contains(pure)
                    pattern.startsWith("*") -> sig.endsWith(pure)
                    pattern.endsWith("*") -> sig.startsWith(pure)
                    else -> sig == pattern
                }
                if (matched) {
                    Log.d(TAG, "default rule matched: action=${dr.action}")
                    return MatchResult.Defaulted(dr.action, dr.targetButton)
                }
            }
            // 3. signature 未归属任何 category
            Log.d(TAG, "unmapped signature: $sig")
            return MatchResult.Unmapped(sig)
        }
    }

    /**
     * 创建新场景分类（用户录制时调用）
     *
     * @param features 场景特征
     * @param categoryName 用户命名的场景名（如"浏览15秒任务"）
     * @param action 执行动作
     * @param targetButton 点击动作的目标按钮文案
     * @return 创建的 SceneCategory
     */
    fun createCategory(
        features: SceneFeatures,
        categoryName: String,
        action: Action,
        targetButton: String? = null
    ): SceneCategory {
        ensureInitialized()
        val sig = features.signature()
        synchronized(lock) {
            val cat = SceneCategory(
                id = "cat_${System.currentTimeMillis()}",
                name = categoryName,
                action = action,
                targetButton = targetButton,
                createdAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()),
                hitCount = 1
            )
            categories.add(cat)
            mappings.add(SignatureMapping(
                signature = sig,
                categoryId = cat.id,
                firstSeen = cat.createdAt,
                matchCount = 1
            ))
            Log.i(TAG, "createCategory: name='$categoryName' action=$action sig=$sig")
            logRecording(features, action, targetButton, categoryName)
            persistAsync()
            return cat
        }
    }

    /**
     * 将 signature 归到已有 category（用户确认"这个场景和某场景相同"时调用）
     *
     * @param features 场景特征
     * @param categoryId 已有 category 的 id
     */
    fun mapToExistingCategory(features: SceneFeatures, categoryId: String) {
        ensureInitialized()
        val sig = features.signature()
        synchronized(lock) {
            // 避免重复映射
            if (mappings.any { it.signature == sig && it.categoryId == categoryId }) return
            val cat = categories.firstOrNull { it.id == categoryId } ?: return
            mappings.add(SignatureMapping(
                signature = sig,
                categoryId = categoryId,
                firstSeen = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()),
                matchCount = 0
            ))
            Log.i(TAG, "mapToExistingCategory: sig=$sig -> category='${cat.name}'")
            persistAsync()
        }
    }

    /**
     * 列出所有场景分类（供用户选择归到哪个 category）
     */
    fun listCategories(): List<SceneCategory> {
        ensureInitialized()
        return synchronized(lock) { categories.toList() }
    }

    /**
     * 删除指定 category（及其所有 signature 映射）
     */
    fun deleteCategory(categoryId: String) {
        ensureInitialized()
        synchronized(lock) {
            val before = categories.size
            categories.removeAll { it.id == categoryId }
            mappings.removeAll { it.categoryId == categoryId }
            val removed = before - categories.size
            if (removed > 0) {
                Log.i(TAG, "deleteCategory: removed $removed category id=$categoryId")
                persistAsync()
            }
        }
    }

    /**
     * 中断：删除当前场景所属 category（表示"这个场景不该这么操作"）
     */
    fun deleteCategoryForSignature(features: SceneFeatures) {
        ensureInitialized()
        val sig = features.signature()
        synchronized(lock) {
            val mapping = mappings.firstOrNull { it.signature == sig }
            if (mapping != null) {
                val catId = mapping.categoryId
                categories.removeAll { it.id == catId }
                mappings.removeAll { it.categoryId == catId }
                Log.i(TAG, "interrupt: deleted category for sig=$sig (categoryId=$catId)")
                persistAsync()
            }
        }
    }

    /** 统计 */
    fun categoryCount(): Int {
        ensureInitialized()
        return synchronized(lock) { categories.size }
    }

    private fun loadFromFile() {
        val file = rulesFile()
        if (!file.exists()) return
        try {
            val text = file.readText()
            val root = JSONObject(text)
            // 新格式：{ categories: [...], mappings: [...] }
            if (root.has("categories")) {
                val cats = root.getJSONArray("categories")
                for (i in 0 until cats.length()) {
                    val o = cats.getJSONObject(i)
                    categories.add(SceneCategory(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        action = Action.valueOf(o.getString("action")),
                        targetButton = o.optString("targetButton", null),
                        createdAt = o.getString("createdAt"),
                        hitCount = o.optInt("hitCount", 0)
                    ))
                }
                val maps = root.getJSONArray("mappings")
                for (i in 0 until maps.length()) {
                    val o = maps.getJSONObject(i)
                    mappings.add(SignatureMapping(
                        signature = o.getString("signature"),
                        categoryId = o.getString("categoryId"),
                        firstSeen = o.getString("firstSeen"),
                        matchCount = o.optInt("matchCount", 0)
                    ))
                }
                Log.i(TAG, "loaded new format: ${categories.size} categories, ${mappings.size} mappings")
                return
            }
            // 旧格式：[ { signature, action, ... }, ... ]
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val legacy = LegacyRule(
                    signature = o.getString("signature"),
                    action = Action.valueOf(o.getString("action")),
                    targetButton = o.optString("targetButton", null),
                    source = o.optString("source", "manual"),
                    createdAt = o.getString("createdAt"),
                    hitCount = o.optInt("hitCount", 0),
                    confidence = o.optDouble("confidence", 0.5)
                )
                // 转换为新格式：每个 legacy rule 独成一个 category
                val catId = "cat_legacy_${i}"
                categories.add(SceneCategory(
                    id = catId,
                    name = "legacy_${i}",
                    action = legacy.action,
                    targetButton = legacy.targetButton,
                    createdAt = legacy.createdAt,
                    hitCount = legacy.hitCount
                ))
                mappings.add(SignatureMapping(
                    signature = legacy.signature,
                    categoryId = catId,
                    firstSeen = legacy.createdAt,
                    matchCount = legacy.hitCount
                ))
            }
            Log.i(TAG, "migrated ${arr.length()} legacy rules to new format")
        } catch (e: Exception) {
            Log.w(TAG, "loadFromFile failed: ${e.message}")
        }
    }

    private fun persistAsync() {
        Thread {
            try {
                val root = JSONObject()
                val cats = JSONArray()
                categories.forEach { c ->
                    cats.put(JSONObject().apply {
                        put("id", c.id)
                        put("name", c.name)
                        put("action", c.action.name)
                        put("targetButton", c.targetButton)
                        put("createdAt", c.createdAt)
                        put("hitCount", c.hitCount)
                    })
                }
                val maps = JSONArray()
                mappings.forEach { m ->
                    maps.put(JSONObject().apply {
                        put("signature", m.signature)
                        put("categoryId", m.categoryId)
                        put("firstSeen", m.firstSeen)
                        put("matchCount", m.matchCount)
                    })
                }
                root.put("categories", cats)
                root.put("mappings", maps)
                rulesFile().apply { parentFile?.mkdirs() }.writeText(root.toString(2))
                Log.d(TAG, "persisted ${cats.length()} categories, ${maps.length()} mappings")
            } catch (e: Exception) {
                Log.w(TAG, "persistAsync failed: ${e.message}")
            }
        }.start()
    }

    private fun logRecording(features: SceneFeatures, action: Action, targetButton: String?, categoryName: String) {
        try {
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            val line = "$time RECORD sig='${features.signature()}' category='$categoryName' action=$action target='$targetButton' " +
                "state=${features.controllerState} btns=[${features.clickableButtons.joinToString(",")}]\n"
            recordingLogFile().apply { parentFile?.mkdirs() }.appendText(line)
        } catch (e: Exception) {
            Log.w(TAG, "logRecording failed: ${e.message}")
        }
    }

    /** 调试用：列出所有 category 和 mapping */
    fun dumpAll(): String {
        ensureInitialized()
        return synchronized(lock) {
            val sb = StringBuilder()
            sb.append("=== ${categories.size} categories ===\n")
            categories.forEach { c ->
                sb.append("  [${c.id}] '${c.name}' action=${c.action} target='${c.targetButton}' hits=${c.hitCount}\n")
            }
            sb.append("=== ${mappings.size} mappings ===\n")
            mappings.forEach { m ->
                val cat = categories.firstOrNull { it.id == m.categoryId }
                sb.append("  sig=${m.signature} -> '${cat?.name}' (hits=${m.matchCount})\n")
            }
            sb.toString()
        }
    }

    /** 兼容旧调用：录制规则（用 action 直接创建 category，名字用 action 名） */
    fun recordRule(features: SceneFeatures, action: Action, targetButton: String? = null) {
        ensureInitialized()
        val sig = features.signature()
        synchronized(lock) {
            // 如果 signature 已映射到 category，强化它
            val existing = mappings.firstOrNull { it.signature == sig }
            if (existing != null) {
                val cat = categories.firstOrNull { it.id == existing.categoryId }
                if (cat != null) {
                    cat.hitCount++
                    existing.matchCount++
                    Log.i(TAG, "recordRule: reinforced existing category '${cat.name}' hits=${cat.hitCount}")
                    persistAsync()
                    return
                }
            }
            // 否则创建新 category（名字用 action）
            val name = when (action) {
                Action.SWIPE_UP -> "向上滑动"
                Action.SWIPE_DOWN -> "向下滑动"
                Action.BACK -> "返回"
                Action.EXIT_TASK -> "退出任务"
                Action.WAIT -> "等待"
                Action.CLICK_BUTTON -> "点击${targetButton ?: "按钮"}"
                Action.STOP_AUTOMATION -> "停止自动化"
                Action.UNKNOWN -> "未知"
            }
            createCategory(features, name, action, targetButton)
        }
    }

    /** 兼容旧调用：删除规则 */
    fun removeRule(features: SceneFeatures, action: Action? = null) {
        deleteCategoryForSignature(features)
    }

    /** 兼容旧调用：录制规则数 */
    fun recordingRuleCount(): Int = categoryCount()
}
