package com.bbncbot.automation

import android.util.Log
import com.bbncbot.service.FarmAccessibilityService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 教学指令解析器
 *
 * 把用户的自然语言指令解析成可执行动作。
 *
 * 支持的指令格式（关键词触发，不区分大小写）：
 * - **点击类**：
 *   - "点 XX" / "点击 XX" / "按 XX" → 用 AI 视觉找 XX 并点击
 *   - "点 坐标 600,1200" → 按坐标点击
 * - **滑动类**：
 *   - "滑动" / "向上" / "向上滑" → 向上滑动（页面向下滚）
 *   - "向下" / "向下滑" → 向下滑动（页面向上滚）
 * - **返回类**：
 *   - "返回" / "后退" / "back" → pressBack
 * - **等待类**：
 *   - "等待" / "等 x 秒" → 不动作，状态机下次轮询（或暂停指定秒数）
 * - **退出类**：
 *   - "退出" / "结束任务" → 退出浏览页（如适用）或停止自动化
 * - **停止类**：
 *   - "停止" / "停止自动化" → AutomationController.stop()
 *
 * 无法解析的指令：记录到日志，不执行。
 *
 * 所有指令（无论是否解析成功）都写到 `teachings.log`，作为后续规则学习的样本。
 */
object TeachCommandParser {

    private const val TAG = "TeachCommandParser"

    /** 解析结果 */
    sealed class ActionResult {
        /** 用 AI 视觉找元素并点击 */
        data class ClickByVision(val description: String) : ActionResult()
        /** 按坐标点击 */
        data class ClickByCoord(val x: Float, val y: Float) : ActionResult()
        /** 滑动 */
        data class Swipe(val direction: SwipeDir) : ActionResult()
        /** 返回 */
        object Back : ActionResult()
        /** 等待（毫秒），null 表示无限期等下次轮询 */
        data class Wait(val ms: Long?) : ActionResult()
        /** 退出当前浏览任务 */
        object ExitTask : ActionResult()
        /** 停止自动化 */
        object Stop : ActionResult()
        /** 无法解析 */
        object Unknown : ActionResult()
    }

    enum class SwipeDir { UP, DOWN }

    /**
     * 解析并执行用户指令。
     *
     * @param input 用户输入的指令文本
     * @param service 当前无障碍服务实例
     * @return true 表示指令已识别并执行（含 AI 视觉异步执行），false 表示无法解析
     */
    fun parseAndExecute(input: String, service: FarmAccessibilityService): Boolean {
        val raw = input.trim()
        if (raw.isEmpty()) return false
        // 注意：不 lowercase 中文指令，只 lowercase 英文关键词
        val cmd = raw.replace("　", " ").trim()
        Log.i(TAG, "parseAndExecute: raw='$raw' cmd='$cmd'")
        logTeach(raw)

        val result = parse(cmd) ?: run {
            Log.w(TAG, "无法解析指令: $raw")
            return false
        }
        Log.i(TAG, "parseAndExecute: parsed result=$result")

        return when (result) {
            is ActionResult.ClickByVision -> {
                Log.i(TAG, "执行: AI视觉点击 '${result.description}'")
                // 异步执行，不阻塞浮窗
                Thread {
                    try {
                        val ok = service.clickByAiVision(result.description)
                        Log.i(TAG, "AI视觉点击结果: $ok")
                    } catch (e: Exception) {
                        Log.e(TAG, "AI视觉点击异常", e)
                    }
                }.start()
                true
            }
            is ActionResult.ClickByCoord -> {
                Log.i(TAG, "执行: 坐标点击 (${result.x}, ${result.y})")
                try {
                    service.dispatchGestureClick(result.x, result.y)
                } catch (e: Exception) {
                    Log.e(TAG, "坐标点击异常", e)
                }
                true
            }
            is ActionResult.Swipe -> {
                val (startY, endY) = when (result.direction) {
                    SwipeDir.UP -> 1450f to 950f   // 向上滑，页面向下滚
                    SwipeDir.DOWN -> 950f to 1450f // 向下滑，页面向上滚
                }
                Log.i(TAG, "执行: 滑动 ${result.direction}")
                try {
                    service.dispatchGestureSwipe(600f, startY, 600f, endY, 500L)
                } catch (e: Exception) {
                    Log.e(TAG, "滑动异常", e)
                }
                true
            }
            ActionResult.Back -> {
                Log.i(TAG, "执行: 返回")
                try {
                    service.pressBack()
                } catch (e: Exception) {
                    Log.e(TAG, "返回异常", e)
                }
                true
            }
            is ActionResult.Wait -> {
                Log.i(TAG, "执行: 等待 ${result.ms}ms")
                true
            }
            ActionResult.ExitTask -> {
                Log.i(TAG, "执行: 退出当前任务")
                // 停止自动化，让用户手动检查后重启
                AutomationController.stop()
                true
            }
            ActionResult.Stop -> {
                Log.i(TAG, "执行: 停止自动化")
                AutomationController.stop()
                true
            }
            ActionResult.Unknown -> {
                Log.w(TAG, "未识别指令: $raw")
                false
            }
        }
    }

    /** 纯解析，不执行（用于测试 / 日志） */
    fun parse(cmd: String): ActionResult? {
        // 去除前后空格，统一全角空格
        val c = cmd.trim()

        // 停止（优先匹配，避免被"退出"等截断）
        if (c == "停止" || c.startsWith("停止") || c.equals("stop", ignoreCase = true)) return ActionResult.Stop

        // 退出
        if (c.startsWith("退出") || c.startsWith("结束任务") || c.startsWith("结束")) {
            return ActionResult.ExitTask
        }

        // 返回
        if (c.startsWith("返回") || c.startsWith("后退") || c.equals("back", ignoreCase = true)) {
            return ActionResult.Back
        }

        // 等待
        if (c.startsWith("等待") || c.startsWith("等") && c.contains("秒")) {
            // 解析"等待 5 秒" / "等3秒"
            val match = Regex("(?:等待|等)\\s*(\\d+)\\s*秒").find(c)
            val seconds = match?.groupValues?.getOrNull(1)?.toIntOrNull()
            return ActionResult.Wait(seconds?.let { it * 1000L })
        }

        // 滑动
        if (c.contains("向上") || c == "滑动" || c.contains("上滑") || c == "上") {
            return ActionResult.Swipe(SwipeDir.UP)
        }
        if (c.contains("向下") || c.contains("下滑") || c == "下") {
            return ActionResult.Swipe(SwipeDir.DOWN)
        }

        // 点击：优先匹配"坐标 x,y"
        val coordMatch = Regex("(?:点|点击|按)\\s*坐标\\s*(\\d+)\\s*[,，]\\s*(\\d+)").find(c)
        if (coordMatch != null) {
            val x = coordMatch.groupValues[1].toFloatOrNull()
            val y = coordMatch.groupValues[2].toFloatOrNull()
            if (x != null && y != null) return ActionResult.ClickByCoord(x, y)
        }

        // 点击："点 XX" / "点击 XX" / "按 XX"（XX 至少 1 个字符）
        val clickMatch = Regex("(?:点|点击|按)\\s+(.+)").find(c)
        if (clickMatch != null) {
            val desc = clickMatch.groupValues[1].trim()
            if (desc.isNotEmpty()) return ActionResult.ClickByVision(desc)
        }

        // 兜底：纯数字 x,y 当坐标
        val pureCoord = Regex("^(\\d+)\\s*[,，]\\s*(\\d+)$").find(c)
        if (pureCoord != null) {
            val x = pureCoord.groupValues[1].toFloatOrNull()
            val y = pureCoord.groupValues[2].toFloatOrNull()
            if (x != null && y != null) return ActionResult.ClickByCoord(x, y)
        }

        return ActionResult.Unknown
    }

    private fun logTeach(input: String) {
        try {
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            val line = "$time TEACH input='${input.replace("'", "\\'").replace("\n", " ")}'\n"
            File(teachingsLogFile()).apply { parentFile?.mkdirs() }.appendText(line)
        } catch (e: Exception) {
            Log.w(TAG, "logTeach failed: ${e.message}")
        }
    }

    private fun teachingsLogFile(): String =
        File(
            android.os.Environment.getExternalStorageDirectory(),
            "Android/data/com.bbncbot/files/teachings.log"
        ).absolutePath
}
