package com.bbncbot

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bbncbot.automation.SceneLibrary
import com.bbncbot.automation.SlowReplayController

/**
 * 慢放回放中的规则编辑界面（对话框风格）
 *
 * 从悬浮窗"✎ 编辑"按钮启动，编辑 [SlowReplayController] 当前光标位置的规则。
 * 保存后调用 [SlowReplayController.updateCurrentRule]，修改立即生效，下一步按新规则执行。
 *
 * 复用 [R.layout.dialog_edit_rule] 布局（与 RuleEditorActivity 编辑对话框一致）。
 */
class SlowReplayEditActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "编辑当前规则（慢放）"

        val rule = SlowReplayController.getCurrentRule()
        if (rule == null) {
            Toast.makeText(this, "无当前规则可编辑", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // 复用 dialog_edit_rule.xml 的表单内容，但该布局没有保存/取消按钮，
        // 用垂直 LinearLayout 包裹：ScrollView 表单 + 水平按钮栏
        val inflater = android.view.LayoutInflater.from(this)
        val formView = inflater.inflate(R.layout.dialog_edit_rule, null, false)
        val etName = formView.findViewById<EditText>(R.id.etName)
        val spinnerAction = formView.findViewById<Spinner>(R.id.spinnerAction)
        val etTargetButton = formView.findViewById<EditText>(R.id.etTargetButton)
        val etPriority = formView.findViewById<EditText>(R.id.etPriority)
        val tvSignature = formView.findViewById<TextView>(R.id.tvSignature)

        etName.setText(rule.name)
        val actions = SceneLibrary.Action.values().filter { it != SceneLibrary.Action.UNKNOWN }
        val actionLabels = actions.map { actionToText(it) }
        spinnerAction.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, actionLabels)
        spinnerAction.setSelection(actions.indexOf(rule.action).coerceAtLeast(0))
        etTargetButton.setText(rule.targetButton ?: "")
        etPriority.setText(rule.priority.toString())
        tvSignature.text = SceneLibrary.listMappingsForCategory(rule.id).firstOrNull()?.signature ?: ""
        tvSignature.movementMethod = android.text.method.ScrollingMovementMethod.getInstance()

        val buttonBar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            setPadding(20, 0, 20, 20)
        }
        val btnSave = android.widget.Button(this).apply {
            text = "保存"
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = 12
            layoutParams = lp
            setOnClickListener { performSave(etName, actions, spinnerAction, etTargetButton, etPriority) }
        }
        val btnCancel = android.widget.Button(this).apply {
            text = "取消"
            setOnClickListener { finish() }
        }
        buttonBar.addView(btnSave)
        buttonBar.addView(btnCancel)

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(formView, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ))
            addView(buttonBar, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        setContentView(container)
    }

    private fun performSave(
        etName: EditText,
        actions: List<SceneLibrary.Action>,
        spinnerAction: Spinner,
        etTargetButton: EditText,
        etPriority: EditText
    ) {
        val newName = etName.text.toString().trim()
        if (newName.isEmpty()) {
            Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show()
            return
        }
        val newAction = actions[spinnerAction.selectedItemPosition]
        val newTarget = etTargetButton.text.toString().trim()
        val newPriority = etPriority.text.toString().trim().toIntOrNull() ?: 0
        val ok = SlowReplayController.updateCurrentRule(
            name = newName,
            action = newAction,
            targetButton = newTarget,
            priority = newPriority
        )
        if (ok) {
            Toast.makeText(this, "已保存，下一步按新规则执行", Toast.LENGTH_SHORT).show()
            finish()
        } else {
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()
        }
    }

    /** 动作转中文（与 RuleEditorActivity 保持一致） */
    private fun actionToText(action: SceneLibrary.Action): String = when (action) {
        SceneLibrary.Action.SWIPE_UP -> "向上滑动"
        SceneLibrary.Action.SWIPE_DOWN -> "向下滑动"
        SceneLibrary.Action.BACK -> "返回"
        SceneLibrary.Action.EXIT_TASK -> "退出任务"
        SceneLibrary.Action.WAIT -> "等待"
        SceneLibrary.Action.CLICK_BUTTON -> "点击按钮"
        SceneLibrary.Action.STOP_AUTOMATION -> "停止自动化"
        SceneLibrary.Action.UNKNOWN -> "未知"
    }
}
