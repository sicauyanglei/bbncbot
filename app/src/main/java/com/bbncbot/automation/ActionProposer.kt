package com.bbncbot.automation

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 动作提议器 —— "执行前先问用户"机制
 *
 * 当 [enabled] = true 时，自动化控制器在关键决策点（滑动/退出/点击前）会调用
 * [requestApproval] 把拟动作展示到浮窗，等用户响应后才执行。
 *
 * 设计：
 * - **不阻塞主线程**：[requestApproval] 接收回调，用户响应后回调在主线程触发
 * - **总开关**：[enabled] 默认 false，通过浮窗长按或广播切换；关闭时直接 APPROVE 不打断
 * - **全程记录**：提议和响应都写到 `proposals.log`，作为后续规则学习的训练样本
 *
 * 用户响应含义：
 * - [APPROVE]：同意执行原动作
 * - [REJECT]：拒绝，不执行原动作（状态机下次轮询重新决策）
 * - [SKIP]：跳过本次询问，直接执行（用于"我觉得这个判断没问题，后面别问了"——暂未实现累积跳过）
 *
 * 通信链路：
 * AutomationController → [requestApproval] → [pendingProposal] + [onProposalChanged] 回调
 * → FloatingWindowService 收到回调显示浮窗 → 用户点按钮 → [respond] → 触发 onResult 回调
 */
object ActionProposer {

    private const val TAG = "ActionProposer"

    /** 用户响应 */
    enum class Response { APPROVE, REJECT, SKIP }

    /** 一条提议 */
    data class Proposal(
        val id: Long,
        val action: String,        // 拟执行动作，如 "滑动 #3 向上"
        val reason: String,        // 拟执行原因，如 "未检测到任务完成，继续滑动等待"
        val pageSummary: String,   // 当前页面文本摘要
        val timestamp: Long
    )

    /** 总开关：开启后每个拟动作都会询问用户；关闭时直接 APPROVE */
    @Volatile
    var enabled: Boolean = false

    /** 当前待响应的提议，null 表示无 */
    @Volatile
    private var pendingProposal: Proposal? = null

    /** 当前提议的响应回调（由 [requestApproval] 设置，[respond] 触发） */
    @Volatile
    private var responder: ((Response) -> Unit)? = null

    /** 浮窗通过此回调感知提议变化：proposal 非 null 表示要显示，null 表示要隐藏 */
    @Volatile
    var onProposalChanged: ((Proposal?) -> Unit)? = null

    /** 主线程 Handler，确保回调在主线程触发 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 请求用户批准动作。
     * - enabled=false：同步直接调用 onResult(APPROVE)
     * - enabled=true：存提议 → 通知浮窗显示 → 等用户响应 → onResult 在主线程触发
     *
     * @param action 拟执行动作描述
     * @param reason 拟执行原因
     * @param pageSummary 当前页面文本摘要（便于用户判断）
     * @param onResult 响应回调（一定在主线程触发）
     */
    fun requestApproval(
        action: String,
        reason: String,
        pageSummary: String,
        onResult: (Response) -> Unit
    ) {
        if (!enabled) {
            mainHandler.post { onResult(Response.APPROVE) }
            return
        }
        val proposal = Proposal(
            id = System.currentTimeMillis(),
            action = action,
            reason = reason,
            pageSummary = pageSummary,
            timestamp = System.currentTimeMillis()
        )
        pendingProposal = proposal
        responder = onResult
        logProposal(proposal)
        // 通知浮窗显示
        mainHandler.post { onProposalChanged?.invoke(proposal) }
    }

    /**
     * 用户在浮窗点了按钮后，浮窗调用此方法提交响应。
     * - 会触发 [requestApproval] 传入的 onResult 回调（主线程）
     * - 清除 pending 状态并通知浮窗隐藏
     */
    fun respond(response: Response) {
        val proposal = pendingProposal ?: run {
            Log.w(TAG, "respond: no pending proposal, ignore")
            return
        }
        val cb = responder ?: run {
            Log.w(TAG, "respond: no responder, ignore")
            return
        }
        pendingProposal = null
        responder = null
        logResponse(proposal, response)
        // 先通知浮窗隐藏，再触发回调
        mainHandler.post {
            onProposalChanged?.invoke(null)
            cb(response)
        }
    }

    /** 是否有待响应的提议 */
    fun hasPending(): Boolean = pendingProposal != null

    /** 获取当前提议（浮窗读取显示） */
    fun currentProposal(): Proposal? = pendingProposal

    private fun logProposal(p: Proposal) {
        try {
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(p.timestamp))
            val line = "$time PROPOSE action='${escape(p.action)}' reason='${escape(p.reason)}' page=${escape(p.pageSummary)}\n"
            File(proposalsLogFile()).apply { parentFile?.mkdirs() }.appendText(line)
        } catch (e: Exception) {
            Log.w(TAG, "logProposal failed: ${e.message}")
        }
    }

    private fun logResponse(p: Proposal, r: Response) {
        try {
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            val line = "$time RESPOND action='${escape(p.action)}' response=$r\n"
            File(proposalsLogFile()).apply { parentFile?.mkdirs() }.appendText(line)
        } catch (e: Exception) {
            Log.w(TAG, "logResponse failed: ${e.message}")
        }
    }

    private fun escape(s: String): String = s.replace("'", "\\'").replace("\n", " ")

    private fun proposalsLogFile(): String =
        File(
            android.os.Environment.getExternalStorageDirectory(),
            "Android/data/com.bbncbot/files/proposals.log"
        ).absolutePath
}
