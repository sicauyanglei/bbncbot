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
        var action: Action,             // 改为 var，支持编辑界面修改
        var targetButton: String?,      // 改为 var，支持编辑界面修改
        val createdAt: String,
        var hitCount: Int = 0,
        /** 录属的录制会话 ID（旧数据为空字符串，表示独立规则） */
        var sessionId: String = "",
        /** 在 session 中的步骤序号（从 0 开始，独立规则为 -1） */
        var stepIndex: Int = -1,
        /** 是否启用此规则（false 时 match() 跳过此 category） */
        var enabled: Boolean = true,
        /** 任务执行优先级（数值越小越先执行，0=默认，由编辑界面配置） */
        var priority: Int = 0,
        /**
         * 这条规则属于哪个肥料任务（如"看精选商品得肥料""逛好物最高得1500肥料"）
         *
         * - 录制时从 [SceneFeatures.fertilizerTaskDesc] 提取，写入此字段
         * - 执行时按 fertTask 分组回放：bot 优先执行与当前场景 fertTask 相同的 session 步骤
         * - 这样 bot 像人一样——"先做完看精选商品任务的所有步骤，再做下一个肥料任务"
         * - 空字符串表示非肥料任务规则（如农场主页通用操作）
         */
        var fertTask: String = ""
    )

    /**
     * 录制会话：一次"开始→结束"的完整任务流程闭环
     *
     * - 一次录制对应一个 session，包含多个有序 [SceneCategory]
     * - 执行时若场景匹配 session 第一步，bot 进入"按流程执行"模式，
     *   优先匹配 session 下一步（stepIndex+1），不匹配则回退全局匹配
     * - 任务完成/异常页时自动结束 session 上下文
     */
    data class RecordingSession(
        val id: String,                 // sess_1, sess_2, ...
        var name: String,               // 自动命名，如"流程1-UC-浏览任务"
        val createdAt: String,
        var stepCount: Int = 0,         // 录制的步骤数
        var status: String = "RECORDING" // RECORDING / COMPLETED / ABORTED
    )

    /**
     * signature → categoryId 映射
     *
     * @param coreSignature 核心签名（去掉 btns 等易变字段）。
     *   用于"明显相同场景"自动归类：coreSignature 命中即视为同一场景，自动添加新 signature 映射。
     *   旧数据可能为空字符串，匹配时跳过。
     */
    data class SignatureMapping(
        val signature: String,
        val categoryId: String,
        val firstSeen: String,
        var matchCount: Int = 0,
        val coreSignature: String = ""
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
    @Volatile private var sessions: MutableList<RecordingSession> = mutableListOf()
    @Volatile private var initialized: Boolean = false
    private val lock = Any()

    /**
     * 当前进行中的 session 执行上下文（运行时状态，不持久化）
     *
     * - [currentSessionId] 非空表示 bot 正在按某 session 流程执行
     * - [currentStepIndex] 表示已执行到的步骤序号，下次优先匹配 stepIndex == currentStepIndex + 1
     * - 任务完成/异常页/手动停止时重置为 null
     */
    @Volatile private var currentSessionId: String? = null
    @Volatile private var currentStepIndex: Int = -1

    /**
     * fertTask 过滤模式（运行时状态，不持久化）
     *
     * - 非空时，[match] 只返回 fertTask 等于此值的规则（其他规则被暂时屏蔽）
     * - 用户在规则编辑界面选择"只回放某个肥料任务"时设置
     * - 调用 [resetSessionContext] 或 [clearFertTaskFilter] 清除
     *
     * 使用场景：用户只想执行"看精选商品得肥料"这一个任务的所有步骤，
     * 不希望 bot 跑去执行其他肥料任务。
     */
    @Volatile private var fertTaskFilter: String? = null

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
     * 匹配优先级：
     * 1. **session 流程下一步** → 若当前在 session 执行上下文中，优先匹配 stepIndex+1 的规则（有上下文感）
     *    任务完成/异常页时自动重置 session 上下文
     * 2. **signature 精确匹配** → 直接执行对应规则（高频命中场景）
     * 3. **coreSignature 自动归类** → 明显相同的场景自动归到已有 category（不打扰用户）
     *    核心签名去掉按钮文案等易变字段，只看平台/页面类型/任务类型/popup/countdown/progress
     *    命中后自动添加新 signature → category 映射，下次直接走精确匹配
     * 4. **默认规则** → type=complete 等通配规则
     * 5. **未命中** → 返回 [MatchResult.Unmapped]，由调用方决定是否询问用户归类
     *
     * @param features 当前场景特征
     * @return [MatchResult]
     */
    fun match(features: SceneFeatures): MatchResult {
        ensureInitialized()
        val sig = features.signature()
        synchronized(lock) {
            // fertTask 过滤模式：只匹配指定 fertTask 的规则
            // 用户选择"只回放某个肥料任务"时启用，其他规则的 mapping 被暂时跳过
            val filter = fertTaskFilter
            // 任务完成/异常页 → 自动结束 session 上下文（流程闭环）
            if (features.isTaskComplete || features.isAbnormalPage) {
                if (currentSessionId != null) {
                    Log.i(TAG, "session context reset: task complete/abnormal (sessionId=$currentSessionId)")
                    currentSessionId = null
                    currentStepIndex = -1
                }
            }
            // 1. session 流程下一步优先匹配
            val sessId = currentSessionId
            if (sessId != null) {
                val nextStep = currentStepIndex + 1
                val nextMapping = mappings.firstOrNull { m ->
                    val cat = categories.firstOrNull { it.id == m.categoryId }
                    cat != null && cat.enabled && cat.sessionId == sessId && cat.stepIndex == nextStep && m.signature == sig &&
                        // fertTask 过滤：如果开启过滤，只匹配 fertTask 相同的规则
                        (filter == null || cat.fertTask == filter)
                }
                if (nextMapping != null) {
                    val cat = categories.firstOrNull { it.id == nextMapping.categoryId }!!
                    nextMapping.matchCount++
                    cat.hitCount++
                    currentStepIndex = nextStep
                    Log.i(TAG, "session step matched: session=$sessId step=$nextStep -> category='${cat.name}' action=${cat.action}")
                    persistAsync()
                    return MatchResult.Matched(cat)
                }
                // session 下一步场景不匹配 → 不强制流程，回退全局匹配（混合模式）
                Log.d(TAG, "session next step not matched, fallback to global (session=$sessId expectedStep=$nextStep)")
            }
            // 1.5 fertTask 上下文匹配（核心改进：像人一样按肥料任务分组回放）
            // 当前场景有 fertTask 时，优先匹配同一 fertTask 的规则（不管 session 是否匹配）
            // 这样 bot 在做"看精选商品得肥料"任务时，优先执行该任务的步骤，不会被其他任务干扰
            // 注意：如果开启 fertTask 过滤，则 curFertTask 必须等于 filter 才匹配
            val curFertTask = features.fertilizerTaskDesc
            if (curFertTask.isNotEmpty() && (filter == null || curFertTask == filter)) {
                val fertMatch = mappings.firstOrNull { m ->
                    val cat = categories.firstOrNull { it.id == m.categoryId }
                    cat != null && cat.enabled && cat.fertTask == curFertTask && m.signature == sig
                }
                if (fertMatch != null) {
                    val cat = categories.firstOrNull { it.id == fertMatch.categoryId }!!
                    fertMatch.matchCount++
                    cat.hitCount++
                    // 进入该规则所属的 session 上下文（按 fertTask 分组回放）
                    if (cat.sessionId.isNotEmpty() && cat.sessionId != sessId) {
                        currentSessionId = cat.sessionId
                        currentStepIndex = cat.stepIndex
                        Log.i(TAG, "entered session context by fertTask: session=${cat.sessionId} step=${cat.stepIndex} fertTask='$curFertTask'")
                    }
                    Log.i(TAG, "fertTask matched: fertTask='$curFertTask' -> category='${cat.name}' action=${cat.action}")
                    persistAsync()
                    return MatchResult.Matched(cat)
                }
            }
            // 2. signature 精确匹配 → 找到所属 category
            val mapping = mappings.firstOrNull { m ->
                m.signature == sig && (filter == null || run {
                    val cat = categories.firstOrNull { it.id == m.categoryId }
                    cat != null && cat.fertTask == filter
                })
            }
            if (mapping != null) {
                val cat = categories.firstOrNull { it.id == mapping.categoryId }
                if (cat != null && cat.enabled) {
                    mapping.matchCount++
                    cat.hitCount++
                    // 命中带 sessionId 的规则 → 进入该 session 上下文（流程起点或中断后恢复）
                    if (cat.sessionId.isNotEmpty() && cat.sessionId != sessId) {
                        currentSessionId = cat.sessionId
                        currentStepIndex = cat.stepIndex
                        Log.i(TAG, "entered session context: session=${cat.sessionId} step=${cat.stepIndex}")
                    }
                    Log.i(TAG, "matched: sig=$sig -> category='${cat.name}' action=${cat.action} hits=${cat.hitCount}")
                    persistAsync()
                    return MatchResult.Matched(cat)
                }
            }
            // 3. coreSignature 自动归类（明显相同场景，不弹窗）
            val coreSig = features.coreSignature()
            val coreMatch = mappings.firstOrNull { m ->
                m.coreSignature == coreSig && m.coreSignature.isNotEmpty() &&
                    // fertTask 过滤：如果开启过滤，只匹配 fertTask 相同的规则
                    (filter == null || run {
                        val cat = categories.firstOrNull { it.id == m.categoryId }
                        cat != null && cat.fertTask == filter
                    })
            }
            if (coreMatch != null) {
                val cat = categories.firstOrNull { it.id == coreMatch.categoryId }
                if (cat != null && cat.enabled) {
                    // 自动添加新 signature 映射（下次直接走精确匹配，不重复走 coreSignature 路径）
                    val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
                    mappings.add(SignatureMapping(
                        signature = sig,
                        categoryId = cat.id,
                        firstSeen = now,
                        matchCount = 1,
                        coreSignature = coreSig
                    ))
                    cat.hitCount++
                    // 命中带 sessionId 的规则 → 进入 session 上下文
                    if (cat.sessionId.isNotEmpty() && cat.sessionId != sessId) {
                        currentSessionId = cat.sessionId
                        currentStepIndex = cat.stepIndex
                        Log.i(TAG, "entered session context via coreSignature: session=${cat.sessionId} step=${cat.stepIndex}")
                    }
                    Log.i(TAG, "auto-categorized by coreSignature: sig=$sig coreSig=$coreSig -> category='${cat.name}' (auto-added mapping)")
                    persistAsync()
                    return MatchResult.Matched(cat)
                }
            }
            // 4. 默认规则
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
            // 5. signature 未归属任何 category
            Log.d(TAG, "unmapped signature: $sig coreSig=$coreSig")
            return MatchResult.Unmapped(sig)
        }
    }

    /**
     * 重置当前 session 执行上下文（手动停止 / 用户中断时调用）
     *
     * 同时清除 fertTask 过滤（如果开启），恢复全局匹配模式。
     */
    fun resetSessionContext() {
        synchronized(lock) {
            if (currentSessionId != null) {
                Log.i(TAG, "session context reset manually (was session=$currentSessionId step=$currentStepIndex)")
            }
            currentSessionId = null
            currentStepIndex = -1
            if (fertTaskFilter != null) {
                Log.i(TAG, "fertTask filter cleared (was '$fertTaskFilter')")
                fertTaskFilter = null
            }
        }
    }

    /**
     * 设置 fertTask 过滤：bot 只执行指定肥料任务的规则
     *
     * 使用场景：用户在规则编辑界面选择"只回放某个肥料任务"，
     * bot 开启自动化后只执行该任务的步骤，不会跑去做其他任务。
     *
     * 调用 [AutomationController.start] 前设置，bot 执行过程中生效，
     * 直到调用 [resetSessionContext] 或 [clearFertTaskFilter] 清除。
     *
     * @param fertTask 要回放的肥料任务描述（如"看精选商品得肥料"），传空字符串或 null 清除过滤
     */
    fun setFertTaskFilter(fertTask: String?) {
        synchronized(lock) {
            val newFilter = if (fertTask.isNullOrEmpty()) null else fertTask
            if (newFilter != fertTaskFilter) {
                Log.i(TAG, "fertTask filter set: '$newFilter' (was '$fertTaskFilter')")
                fertTaskFilter = newFilter
                // 切换过滤模式时重置 session 上下文，避免残留的 session 干扰新过滤模式
                currentSessionId = null
                currentStepIndex = -1
            }
        }
    }

    /** 清除 fertTask 过滤，恢复全局匹配模式 */
    fun clearFertTaskFilter() = setFertTaskFilter(null)

    /** 获取当前 fertTask 过滤（null 表示未开启过滤） */
    fun getFertTaskFilter(): String? = fertTaskFilter

    /**
     * 根据场景特征自动生成场景名
     *
     * 命名规则：`平台-页面类型-[弹窗]-动作`
     * - 平台：UC / 支付宝 / 淘宝 / 未知
     * - 页面类型：任务完成 / 异常页 / 付费搜索 / 浏览X秒任务 / 搜索浏览任务 / 浏览任务 / 农场主页 / 未知页
     * - 弹窗（可选）：红包弹窗 / 更快拿奖 / 奖励提升
     * - 动作：向上滑动 / 向下滑动 / 返回 / 退出任务 / 等待 / 点击XX / 停止 / 未知动作
     *
     * 示例：
     * - "UC-浏览15秒任务-向上滑动"
     * - "淘宝-农场主页-点击收能量"
     * - "UC-浏览任务-红包弹窗-点击关闭"
     *
     * @param features 场景特征
     * @param action 执行动作
     * @param targetButton 点击动作的目标按钮文案
     * @return 自动生成的场景名
     */
    fun autoName(features: SceneFeatures, action: Action, targetButton: String? = null): String {
        val platform = when (features.platform) {
            "UC" -> "UC"
            "ALIPAY" -> "支付宝"
            "TAOBAO" -> "淘宝"
            else -> "未知"
        }
        val pageType = when {
            features.isTaskComplete -> "任务完成"
            features.isAbnormalPage -> "异常页"
            features.isPaidSearchPage -> "付费搜索"
            features.isBrowseDurationTask -> "浏览${features.browseDurationSeconds}秒任务"
            features.isSearchBrowseTaskPage -> "搜索浏览任务"
            features.isBrowseTaskPage -> "浏览任务"
            features.onFarmPage -> "农场主页"
            else -> "未知页"
        }
        val popup = when {
            features.hasRedPacketPopup -> "红包弹窗"
            features.hasFasterRewardEntry -> "更快拿奖"
            features.hasRewardUpgradePopup -> "奖励提升"
            else -> null
        }
        val actionDesc = when (action) {
            Action.SWIPE_UP -> "向上滑动"
            Action.SWIPE_DOWN -> "向下滑动"
            Action.BACK -> "返回"
            Action.EXIT_TASK -> "退出任务"
            Action.WAIT -> "等待"
            Action.CLICK_BUTTON -> "点击${targetButton ?: "按钮"}"
            Action.STOP_AUTOMATION -> "停止"
            Action.UNKNOWN -> "未知动作"
        }
        val parts = mutableListOf<String>()
        parts.add(platform)
        // 任务内容标识（如"看严选推荐商品"），优先于页面类型展示，更直观
        if (features.taskContentText.isNotEmpty()) {
            parts.add(features.taskContentText)
        } else {
            parts.add(pageType)
        }
        if (popup != null) parts.add(popup)
        parts.add(actionDesc)
        return parts.joinToString("-")
    }

    /**
     * 创建新场景分类（录制时调用）
     *
     * @param features 场景特征
     * @param categoryName 场景名（自动生成或用户输入）
     * @param action 执行动作
     * @param targetButton 点击动作的目标按钮文案
     * @param sessionId 录制会话 ID（独立规则传空字符串）
     * @param stepIndex 在 session 中的步骤序号（独立规则传 -1）
     * @return 创建的 SceneCategory
     */
    fun createCategory(
        features: SceneFeatures,
        categoryName: String,
        action: Action,
        targetButton: String? = null,
        sessionId: String = "",
        stepIndex: Int = -1
    ): SceneCategory {
        ensureInitialized()
        val sig = features.signature()
        val coreSig = features.coreSignature()
        synchronized(lock) {
            val cat = SceneCategory(
                id = "cat_${System.currentTimeMillis()}_${stepIndex}",
                name = categoryName,
                action = action,
                targetButton = targetButton,
                createdAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()),
                hitCount = 1,
                sessionId = sessionId,
                stepIndex = stepIndex,
                // 记录这条规则属于哪个肥料任务（执行时按 fertTask 分组回放）
                fertTask = features.fertilizerTaskDesc
            )
            categories.add(cat)
            mappings.add(SignatureMapping(
                signature = sig,
                categoryId = cat.id,
                firstSeen = cat.createdAt,
                matchCount = 1,
                coreSignature = coreSig
            ))
            Log.i(TAG, "createCategory: name='$categoryName' action=$action sig=$sig coreSig=$coreSig session=$sessionId step=$stepIndex fertTask='${features.fertilizerTaskDesc}'")
            logRecording(features, action, targetButton, categoryName)
            persistAsync()
            return cat
        }
    }

    /**
     * 开始一次录制会话（任务流程闭环）
     *
     * @param name 会话名（如"流程1-UC-浏览任务"）
     * @return 创建的 RecordingSession
     */
    fun startSession(name: String): RecordingSession {
        ensureInitialized()
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
        val sess = RecordingSession(
            id = "sess_${System.currentTimeMillis()}",
            name = name,
            createdAt = now,
            stepCount = 0,
            status = "RECORDING"
        )
        synchronized(lock) {
            sessions.add(sess)
            Log.i(TAG, "startSession: id=${sess.id} name='$name'")
            persistAsync()
        }
        return sess
    }

    /**
     * 结束录制会话（用户停止录制时调用）
     *
     * @param sessionId 会话 ID
     * @param stepCount 录制的步骤数
     */
    fun endSession(sessionId: String, stepCount: Int) {
        ensureInitialized()
        synchronized(lock) {
            val sess = sessions.firstOrNull { it.id == sessionId } ?: return
            sess.status = "COMPLETED"
            sess.stepCount = stepCount
            Log.i(TAG, "endSession: id=$sessionId stepCount=$stepCount")
            persistAsync()
        }
    }

    /**
     * 中断会话：删除指定 session 及其所有规则（用户点击"中断"时调用）
     */
    fun deleteSession(sessionId: String) {
        ensureInitialized()
        synchronized(lock) {
            val catIds = categories.filter { it.sessionId == sessionId }.map { it.id }.toSet()
            if (catIds.isEmpty()) {
                sessions.removeAll { it.id == sessionId }
                persistAsync()
                return
            }
            categories.removeAll { it.sessionId == sessionId }
            mappings.removeAll { it.categoryId in catIds }
            sessions.removeAll { it.id == sessionId }
            // 如果中断的是当前执行中的 session，重置上下文
            if (currentSessionId == sessionId) {
                currentSessionId = null
                currentStepIndex = -1
            }
            Log.i(TAG, "deleteSession: id=$sessionId removed ${catIds.size} categories")
            persistAsync()
        }
    }

    /**
     * 列出所有录制会话
     */
    fun listSessions(): List<RecordingSession> {
        ensureInitialized()
        return synchronized(lock) { sessions.toList() }
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
        val coreSig = features.coreSignature()
        synchronized(lock) {
            // 避免重复映射
            if (mappings.any { it.signature == sig && it.categoryId == categoryId }) return
            val cat = categories.firstOrNull { it.id == categoryId } ?: return
            mappings.add(SignatureMapping(
                signature = sig,
                categoryId = categoryId,
                firstSeen = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()),
                matchCount = 0,
                coreSignature = coreSig
            ))
            Log.i(TAG, "mapToExistingCategory: sig=$sig coreSig=$coreSig -> category='${cat.name}'")
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
     * 列出所有已录制规则中存在的肥料任务（去重，用于规则编辑界面的"按 fertTask 回放"选择列表）
     *
     * @return 肥料任务描述列表（如["看精选商品得肥料", "逛好物最高得1500肥料"]），按名称排序
     */
    fun listFertTasks(): List<String> {
        ensureInitialized()
        return synchronized(lock) {
            categories.map { it.fertTask }.filter { it.isNotEmpty() }.toSet().sorted()
        }
    }

    /**
     * 列出指定 category 的所有 signature 映射（供编辑界面展示匹配条件）
     */
    fun listMappingsForCategory(categoryId: String): List<SignatureMapping> {
        ensureInitialized()
        return synchronized(lock) { mappings.filter { it.categoryId == categoryId }.toList() }
    }

    /**
     * 查询指定平台+任务内容标识对应的规则优先级
     *
     * - 用于 AutomationController 在收集任务按钮后按用户配置的 priority 排序执行
     * - 在 mappings 中查找 signature 同时包含 `p=$platform|` 和 `task=$taskContent` 的映射
     * - 未匹配到规则 / 规则被禁用时返回 [Int.MAX_VALUE]（排到最后，保持原有视觉顺序）
     *
     * @param platform 平台标识（"UC" / "ALIPAY" / "TAOBAO"）
     * @param taskContent 任务内容标识（如"看严选推荐商品"，由 SceneFeatureExtractor.extractTaskContentText 提取）
     * @return 规则优先级（数值小的先执行），未匹配返回 Int.MAX_VALUE
     */
    fun getPriorityForTask(platform: String, taskContent: String): Int {
        ensureInitialized()
        if (taskContent.isEmpty()) return Int.MAX_VALUE
        synchronized(lock) {
            for (m in mappings) {
                if (!m.signature.contains("p=$platform|")) continue
                if (!m.signature.contains("task=$taskContent")) continue
                val cat = categories.firstOrNull { it.id == m.categoryId }
                if (cat != null && cat.enabled) return cat.priority
            }
            return Int.MAX_VALUE
        }
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
     * 批量删除指定平台的全部规则
     *
     * - 通过 mappings 中 signature 的 `p=XXX|` 字段判断平台归属
     * - platform 为 null 时删除全部规则（"全部" Tab）
     * - 返回删除的规则数量
     *
     * @param platform 平台标识（"UC" / "ALIPAY" / "TAOBAO"），null 表示全部
     * @return 实际删除的 category 数量
     */
    fun deleteCategoriesByPlatform(platform: String?): Int {
        ensureInitialized()
        synchronized(lock) {
            // 收集需要删除的 categoryId
            val idsToRemove = mutableSetOf<String>()
            if (platform == null) {
                // 删除全部
                idsToRemove.addAll(categories.map { it.id })
            } else {
                // 按 signature 中的 p= 字段过滤
                for (m in mappings) {
                    if (m.signature.contains("p=$platform|")) {
                        idsToRemove.add(m.categoryId)
                    }
                }
            }
            if (idsToRemove.isEmpty()) return 0
            val before = categories.size
            categories.removeAll { it.id in idsToRemove }
            mappings.removeAll { it.categoryId in idsToRemove }
            val removed = before - categories.size
            if (removed > 0) {
                Log.i(TAG, "deleteCategoriesByPlatform: platform=$platform removed $removed categories")
                persistAsync()
            }
            return removed
        }
    }

    /**
     * 更新指定 category 的可编辑字段
     *
     * 由规则编辑界面调用，支持修改：name / action / targetButton / enabled / priority
     * 修改后立即异步持久化。
     *
     * @return true 表示更新成功（category 存在），false 表示未找到
     */
    fun updateCategory(
        categoryId: String,
        name: String? = null,
        action: Action? = null,
        targetButton: String? = null,
        enabled: Boolean? = null,
        priority: Int? = null
    ): Boolean {
        ensureInitialized()
        synchronized(lock) {
            val cat = categories.firstOrNull { it.id == categoryId } ?: return false
            if (name != null) cat.name = name
            if (action != null) cat.action = action
            // targetButton 传非 null 才更新；传空字符串表示清空
            // 注意：此处用 "是否提供了参数" 区分，调用方想清空传 "" 即可
            if (targetButton != null) cat.targetButton = targetButton.ifEmpty { null }
            if (enabled != null) cat.enabled = enabled
            if (priority != null) cat.priority = priority
            Log.i(TAG, "updateCategory: id=$categoryId name='${cat.name}' action=${cat.action} enabled=${cat.enabled} priority=${cat.priority}")
            persistAsync()
            return true
        }
    }

    /**
     * 批量更新 category 的优先级（用于编辑界面拖动排序后保存）
     *
     * @param order Map<categoryId, newPriority>
     */
    fun updateCategoryPriorities(order: Map<String, Int>) {
        ensureInitialized()
        synchronized(lock) {
            for ((catId, prio) in order) {
                val cat = categories.firstOrNull { it.id == catId } ?: continue
                cat.priority = prio
            }
            Log.i(TAG, "updateCategoryPriorities: updated ${order.size} priorities")
            persistAsync()
        }
    }

    /**
     * 中断：删除当前场景所属规则
     *
     * - 若规则归属某 session，删除整个 session（表示"这个流程不对"）
     * - 否则只删除单个 category
     * - 同时重置执行上下文
     */
    fun deleteCategoryForSignature(features: SceneFeatures) {
        ensureInitialized()
        val sig = features.signature()
        synchronized(lock) {
            val mapping = mappings.firstOrNull { it.signature == sig } ?: run {
                // 即使没匹配到，也重置 session 上下文
                currentSessionId = null
                currentStepIndex = -1
                return
            }
            val cat = categories.firstOrNull { it.id == mapping.categoryId }
            if (cat != null && cat.sessionId.isNotEmpty()) {
                // 归属 session → 删除整个 session
                deleteSessionLocked(cat.sessionId)
            } else {
                // 独立规则 → 只删除该 category
                categories.removeAll { it.id == mapping.categoryId }
                mappings.removeAll { it.categoryId == mapping.categoryId }
                Log.i(TAG, "interrupt: deleted category for sig=$sig (categoryId=${mapping.categoryId})")
            }
            currentSessionId = null
            currentStepIndex = -1
            persistAsync()
        }
    }

    /** 内部方法：删除 session 及其所有规则（调用方持有锁） */
    private fun deleteSessionLocked(sessionId: String) {
        val catIds = categories.filter { it.sessionId == sessionId }.map { it.id }.toSet()
        categories.removeAll { it.sessionId == sessionId }
        mappings.removeAll { it.categoryId in catIds }
        sessions.removeAll { it.id == sessionId }
        Log.i(TAG, "deleteSessionLocked: id=$sessionId removed ${catIds.size} categories")
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
            // 新格式：{ categories: [...], mappings: [...], sessions: [...] }
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
                        hitCount = o.optInt("hitCount", 0),
                        // 旧数据没有 sessionId/stepIndex，默认空/-1（独立规则）
                        sessionId = o.optString("sessionId", ""),
                        stepIndex = o.optInt("stepIndex", -1),
                        // 新增字段：旧数据没有 enabled/priority，默认 true/0
                        enabled = o.optBoolean("enabled", true),
                        priority = o.optInt("priority", 0),
                        // 肥料任务描述：旧数据没有，默认空
                        fertTask = o.optString("fertTask", "")
                    ))
                }
                val maps = root.getJSONArray("mappings")
                for (i in 0 until maps.length()) {
                    val o = maps.getJSONObject(i)
                    mappings.add(SignatureMapping(
                        signature = o.getString("signature"),
                        categoryId = o.getString("categoryId"),
                        firstSeen = o.getString("firstSeen"),
                        matchCount = o.optInt("matchCount", 0),
                        // 旧数据可能没有 coreSignature 字段，默认空字符串（match 时跳过自动归类）
                        coreSignature = o.optString("coreSignature", "")
                    ))
                }
                // sessions（可选，旧数据可能没有）
                if (root.has("sessions")) {
                    val sess = root.getJSONArray("sessions")
                    for (i in 0 until sess.length()) {
                        val o = sess.getJSONObject(i)
                        sessions.add(RecordingSession(
                            id = o.getString("id"),
                            name = o.getString("name"),
                            createdAt = o.getString("createdAt"),
                            stepCount = o.optInt("stepCount", 0),
                            status = o.optString("status", "COMPLETED")
                        ))
                    }
                }
                Log.i(TAG, "loaded new format: ${categories.size} categories, ${mappings.size} mappings, ${sessions.size} sessions")
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
                        put("sessionId", c.sessionId)
                        put("stepIndex", c.stepIndex)
                        put("enabled", c.enabled)
                        put("priority", c.priority)
                        put("fertTask", c.fertTask)
                    })
                }
                val maps = JSONArray()
                mappings.forEach { m ->
                    maps.put(JSONObject().apply {
                        put("signature", m.signature)
                        put("categoryId", m.categoryId)
                        put("firstSeen", m.firstSeen)
                        put("matchCount", m.matchCount)
                        put("coreSignature", m.coreSignature)
                    })
                }
                val sess = JSONArray()
                sessions.forEach { s ->
                    sess.put(JSONObject().apply {
                        put("id", s.id)
                        put("name", s.name)
                        put("createdAt", s.createdAt)
                        put("stepCount", s.stepCount)
                        put("status", s.status)
                    })
                }
                root.put("categories", cats)
                root.put("mappings", maps)
                root.put("sessions", sess)
                rulesFile().apply { parentFile?.mkdirs() }.writeText(root.toString(2))
                Log.d(TAG, "persisted ${cats.length()} categories, ${maps.length()} mappings, ${sess.length()} sessions")
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
            sb.append("=== ${sessions.size} sessions ===\n")
            sessions.forEach { s ->
                val cur = if (s.id == currentSessionId) " <CURRENT(step=$currentStepIndex)>" else ""
                sb.append("  [${s.id}] '${s.name}' steps=${s.stepCount} status=${s.status}$cur\n")
            }
            sb.append("=== ${categories.size} categories ===\n")
            categories.forEach { c ->
                val sessTag = if (c.sessionId.isNotEmpty()) " session=${c.sessionId} step=${c.stepIndex}" else ""
                sb.append("  [${c.id}] '${c.name}' action=${c.action} target='${c.targetButton}' hits=${c.hitCount}$sessTag\n")
            }
            sb.append("=== ${mappings.size} mappings ===\n")
            mappings.forEach { m ->
                val cat = categories.firstOrNull { it.id == m.categoryId }
                val coreTag = if (m.coreSignature.isNotEmpty()) " core=${m.coreSignature}" else ""
                sb.append("  sig=${m.signature} -> '${cat?.name}' (hits=${m.matchCount})$coreTag\n")
            }
            sb.toString()
        }
    }

    /**
     * 生成规则判断依据的人类可读说明
     *
     * **规则单元 = 一个完整任务流程（RecordingSession）**，不是单个录制操作。
     *
     * 一次录制 = 一个完整任务闭环（从开始到获得肥料），bot 命中流程起点后按顺序执行各步骤。
     * 因此 explainRules 按 session 展示：每个 session 是一条规则，内含有序步骤，
     * 每个步骤有独立的判断依据（场景特征）。
     *
     * 独立规则（无 session 归属的 category，旧数据）单独展示。
     *
     * 输出格式示例：
     * ```
     * === 规则判断依据（共 2 条规则）===
     *
     * 【规则1】流程1234-UC-浏览任务（完整任务流程）
     *   流程状态：已完成
     *   步骤数：3
     *   总命中：5 次
     *
     *   步骤1：向上滑动
     *     触发条件（bot 进入此步骤时依据）：
     *       平台：UC
     *       农场主页：否
     *       任务类型：浏览时长任务（browse_duration(15s)）
     *       倒计时：有
     *       进度提示：有
     *     核心匹配（按钮文案变化时也命中）：
     *       平台：UC ···
     *
     *   步骤2：点击按钮「领取」
     *     触发条件：···
     *
     *   步骤3：退出任务
     *     触发条件：···
     * ```
     */
    fun explainRules(): String {
        ensureInitialized()
        return synchronized(lock) {
            val sb = StringBuilder()
            // 规则单元：session（完整流程）+ 独立规则（无 session 的 category）
            val sessionCats = categories.filter { it.sessionId.isNotEmpty() }
            val standaloneCats = categories.filter { it.sessionId.isEmpty() }
            val sessionsWithCats = sessions.map { sess ->
                sess to sessionCats.filter { it.sessionId == sess.id }.sortedBy { it.stepIndex }
            }
            val ruleCount = sessionsWithCats.size + standaloneCats.size

            sb.append("=== 规则判断依据（共 $ruleCount 条规则）===\n\n")
            if (ruleCount == 0) {
                sb.append("（暂无规则，请先录制）\n")
                return sb.toString()
            }

            var ruleIdx = 0
            // 1. 按 session 展示（一条规则 = 一个完整流程）
            sessionsWithCats.forEach { (sess, cats) ->
                ruleIdx++
                sb.append("【规则$ruleIdx】${sess.name}（完整任务流程）\n")
                sb.append("  流程状态：${sessionStatusToText(sess.status)}\n")
                sb.append("  步骤数：${cats.size}\n")
                val totalHits = cats.sumOf { it.hitCount }
                sb.append("  总命中：${totalHits} 次\n\n")
                if (cats.isEmpty()) {
                    sb.append("  （流程无步骤，可能录制中断已丢弃）\n\n")
                    return@forEach
                }
                cats.forEachIndexed { stepIdx, c ->
                    sb.append("  步骤${stepIdx + 1}：${actionToText(c.action, c.targetButton)}")
                    sb.append("  [命中${c.hitCount}次]\n")
                    val relatedMaps = mappings.filter { it.categoryId == c.id }
                    if (relatedMaps.isEmpty()) {
                        sb.append("    （无 signature 映射，此步骤无法触发）\n\n")
                        return@forEachIndexed
                    }
                    val firstMap = relatedMaps.first()
                    sb.append("    触发条件（精确匹配，所有字段必须一致）：\n")
                    sb.append(parseSignatureReadable(firstMap.signature, indent = "      "))
                    if (firstMap.coreSignature.isNotEmpty()) {
                        sb.append("    核心匹配（按钮文案变化时也命中）：\n")
                        sb.append(parseSignatureReadable(firstMap.coreSignature, indent = "      "))
                    }
                    if (relatedMaps.size > 1) {
                        sb.append("    其他签名变体（${relatedMaps.size - 1} 个，同步骤的不同页面状态）：\n")
                        relatedMaps.drop(1).forEach { m ->
                            sb.append("      - ${m.signature}\n")
                        }
                    }
                    sb.append("\n")
                }
            }
            // 2. 独立规则（无 session 归属，旧数据或兼容调用产生的单步规则）
            if (standaloneCats.isNotEmpty()) {
                if (ruleIdx > 0) sb.append("\n")
                sb.append("--- 独立规则（无流程归属，单步匹配）---\n\n")
                standaloneCats.forEach { c ->
                    ruleIdx++
                    sb.append("【规则$ruleIdx】${c.name}（独立单步）\n")
                    sb.append("  执行动作：${actionToText(c.action, c.targetButton)}\n")
                    sb.append("  命中次数：${c.hitCount} 次\n")
                    val relatedMaps = mappings.filter { it.categoryId == c.id }
                    if (relatedMaps.isEmpty()) {
                        sb.append("  （无 signature 映射，规则无法命中）\n\n")
                        return@forEach
                    }
                    val firstMap = relatedMaps.first()
                    sb.append("  触发条件（精确匹配，所有字段必须一致）：\n")
                    sb.append(parseSignatureReadable(firstMap.signature, indent = "    "))
                    if (firstMap.coreSignature.isNotEmpty()) {
                        sb.append("  核心匹配（按钮文案变化时也命中）：\n")
                        sb.append(parseSignatureReadable(firstMap.coreSignature, indent = "    "))
                    }
                    sb.append("\n")
                }
            }
            sb.toString()
        }
    }

    private fun sessionStatusToText(status: String): String = when (status) {
        "RECORDING" -> "录制中"
        "COMPLETED" -> "已完成"
        "ABORTED" -> "已中断"
        else -> status
    }

    /** 把 signature 字符串解析成中文可读字段列表 */
    private fun parseSignatureReadable(sig: String, indent: String): String {
        val sb = StringBuilder()
        val parts = sig.split("|")
        for (part in parts) {
            val eqIdx = part.indexOf('=')
            if (eqIdx < 0) continue
            val key = part.substring(0, eqIdx)
            val value = part.substring(eqIdx + 1)
            val readable = when (key) {
                "p" -> "平台：${platformToText(value)}"
                "farm" -> "农场主页：${if (value == "true") "是" else "否"}"
                "type" -> "任务类型：${typeToText(value)}"
                "popup" -> "弹窗：${popupToText(value)}"
                "countdown" -> "倒计时：${if (value == "yes") "有" else "无"}"
                "progress" -> "进度提示：${if (value == "yes") "有" else "无"}"
                "btns" -> "按钮集合：$value"
                "task" -> "任务内容：$value"
                else -> "$key：$value"
            }
            sb.append("$indent$readable\n")
        }
        return sb.toString()
    }

    private fun platformToText(p: String): String = when (p) {
        "UC" -> "UC"
        "ALIPAY" -> "支付宝"
        "TAOBAO" -> "淘宝"
        "UNKNOWN" -> "未知"
        else -> p
    }

    private fun typeToText(t: String): String = when {
        t == "complete" -> "任务完成页"
        t == "abnormal" -> "异常页（交易/收银台）"
        t == "paid_search" -> "付费搜索推荐页"
        t.startsWith("browse_duration") -> "浏览时长任务（$t）"
        t == "search_browse" -> "搜索浏览任务"
        t == "browse" -> "浏览任务"
        t == "farm_home" -> "农场主页"
        else -> "未知页（$t）"
    }

    private fun popupToText(p: String): String = when (p) {
        "red_packet" -> "红包弹窗"
        "faster_reward_entry" -> "更快拿奖入口"
        "reward_upgrade" -> "奖励提升窗口"
        else -> p
    }

    private fun actionToText(action: Action, target: String?): String = when (action) {
        Action.SWIPE_UP -> "向上滑动"
        Action.SWIPE_DOWN -> "向下滑动"
        Action.BACK -> "返回"
        Action.EXIT_TASK -> "退出任务"
        Action.WAIT -> "等待"
        Action.CLICK_BUTTON -> "点击按钮「${target ?: "未知"}」"
        Action.STOP_AUTOMATION -> "停止自动化"
        Action.UNKNOWN -> "未知动作"
    }

    /** 把规则判断依据写入文件，供用户查看 */
    fun dumpRulesExplanationToFile(): String {
        val text = explainRules()
        val file = java.io.File(
            android.os.Environment.getExternalStorageDirectory(),
            "Android/data/com.bbncbot/files/rules_explanation.txt"
        )
        try {
            file.parentFile?.mkdirs()
            file.writeText(text)
            Log.i(TAG, "规则判断依据已写入 ${file.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "dumpRulesExplanationToFile failed: ${e.message}")
        }
        return text
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
                    // 补充 fertTask（旧规则可能没有，录制时补上）
                    if (cat.fertTask.isEmpty() && features.fertilizerTaskDesc.isNotEmpty()) {
                        cat.fertTask = features.fertilizerTaskDesc
                        Log.i(TAG, "recordRule: updated fertTask='${cat.fertTask}' for category '${cat.name}'")
                    }
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
