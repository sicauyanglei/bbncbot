package com.bbncbot

import android.app.AlertDialog
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Switch
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.bbncbot.automation.SceneLibrary
import com.bbncbot.automation.SceneLibrary.Action
import com.bbncbot.automation.SceneLibrary.SceneCategory
import com.google.android.material.tabs.TabLayout

/**
 * 场景规则查看与编辑界面
 *
 * - 按"平台 + 任务内容"分组展示已录制的规则
 * - 支持启用/禁用单条规则（Switch 开关）
 * - 点击规则项弹出编辑对话框：修改名称、动作、目标按钮、优先级
 * - 长按规则项删除
 * - 优先级数值小的先执行（由 AutomationController 在任务调度时读取）
 */
class RuleEditorActivity : AppCompatActivity() {

    /** Tab 标签与对应的 platform 过滤值（null 表示"全部"） */
    private val tabs = listOf(
        "全部" to null,
        "淘宝" to "TAOBAO",
        "支付宝" to "ALIPAY",
        "UC极速版" to "UC"
    )

    /** 当前选中的 platform 过滤值 */
    private var currentPlatformFilter: String? = null

    /** 当前展示的规则列表（已按 priority 升序排序） */
    private var currentRules: List<SceneCategory> = emptyList()

    private lateinit var listView: ListView
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: RuleAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rule_editor)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.title = "场景规则编辑"
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        listView = findViewById(R.id.listView)
        tvEmpty = findViewById(R.id.tvEmpty)
        val btnDeleteAll = findViewById<Button>(R.id.btnDeleteAll)

        // 删除当前 Tab 平台的全部规则（带二次确认）
        btnDeleteAll.setOnClickListener {
            if (currentRules.isEmpty()) {
                Toast.makeText(this, "当前没有可删除的规则", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val tabLabel = tabs.firstOrNull { it.second == currentPlatformFilter }?.first ?: "全部"
            AlertDialog.Builder(this)
                .setTitle("批量删除")
                .setMessage("确定删除「$tabLabel」Tab 下的全部 ${currentRules.size} 条规则吗？\n删除后不可恢复。")
                .setPositiveButton("全部删除") { _, _ ->
                    val removed = SceneLibrary.deleteCategoriesByPlatform(currentPlatformFilter)
                    Toast.makeText(this, "已删除 $removed 条规则", Toast.LENGTH_SHORT).show()
                    refreshList()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 创建 Tab
        for ((label, _) in tabs) {
            tabLayout.addTab(tabLayout.newTab().setText(label))
        }
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentPlatformFilter = tabs[tab.position].second
                refreshList()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        adapter = RuleAdapter()
        listView.adapter = adapter

        // 点击规则项 → 编辑
        listView.setOnItemClickListener { _, _, position, _ ->
            val rule = currentRules.getOrNull(position) ?: return@setOnItemClickListener
            showEditDialog(rule)
        }

        // 长按规则项 → 删除确认
        listView.setOnItemLongClickListener { _, _, position, _ ->
            val rule = currentRules.getOrNull(position) ?: return@setOnItemLongClickListener true
            AlertDialog.Builder(this)
                .setTitle("删除规则")
                .setMessage("确定删除规则「${rule.name}」吗？\n删除后不可恢复。")
                .setPositiveButton("删除") { _, _ ->
                    SceneLibrary.deleteCategory(rule.id)
                    Toast.makeText(this, "已删除「${rule.name}」", Toast.LENGTH_SHORT).show()
                    refreshList()
                }
                .setNegativeButton("取消", null)
                .show()
            true
        }

        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    /** 重新加载规则列表并按 platform 过滤 + priority 排序 */
    private fun refreshList() {
        val allRules = SceneLibrary.listCategories()
        // 过滤平台：signature 中 p=XXX 段匹配
        currentRules = allRules.filter { cat ->
            // 通过 category 关联的 mappings 拿 signature 来判断平台
            val platform = extractPlatformFromCategory(cat.id)
            if (currentPlatformFilter == null) true else platform == currentPlatformFilter
        }.sortedWith(compareBy({ it.priority }, { it.name }))
        adapter.notifyDataSetChanged()
        tvEmpty.visibility = if (currentRules.isEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * 从 categoryId 关联的 mappings 中提取 platform
     * - signature 格式：p=UC|farm=false|...
     * - 取第一个 p= 字段值
     */
    private fun extractPlatformFromCategory(categoryId: String): String {
        val mappings = SceneLibrary.listMappingsForCategory(categoryId)
        val firstSig = mappings.firstOrNull()?.signature ?: return "UNKNOWN"
        val pPart = firstSig.split("|").firstOrNull { it.startsWith("p=") } ?: return "UNKNOWN"
        return pPart.substringAfter("p=").trim()
    }

    /** 解析 signature 中的 task= 字段（任务内容标识） */
    private fun extractTaskFromCategory(categoryId: String): String {
        val mappings = SceneLibrary.listMappingsForCategory(categoryId)
        val firstSig = mappings.firstOrNull()?.signature ?: return ""
        val taskPart = firstSig.split("|").firstOrNull { it.startsWith("task=") } ?: return ""
        return taskPart.substringAfter("task=").trim()
    }

    /** 获取 category 的第一条 signature（用于编辑界面只读展示） */
    private fun getFirstSignature(categoryId: String): String {
        return SceneLibrary.listMappingsForCategory(categoryId).firstOrNull()?.signature ?: ""
    }

    /** 动作转中文 */
    private fun actionToText(action: Action): String = when (action) {
        Action.SWIPE_UP -> "向上滑动"
        Action.SWIPE_DOWN -> "向下滑动"
        Action.BACK -> "返回"
        Action.EXIT_TASK -> "退出任务"
        Action.WAIT -> "等待"
        Action.CLICK_BUTTON -> "点击按钮"
        Action.STOP_AUTOMATION -> "停止自动化"
        Action.UNKNOWN -> "未知"
    }

    /** 显示编辑对话框 */
    private fun showEditDialog(rule: SceneCategory) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_edit_rule, null)
        val etName = view.findViewById<EditText>(R.id.etName)
        val spinnerAction = view.findViewById<Spinner>(R.id.spinnerAction)
        val etTargetButton = view.findViewById<EditText>(R.id.etTargetButton)
        val etPriority = view.findViewById<EditText>(R.id.etPriority)
        val tvSignature = view.findViewById<TextView>(R.id.tvSignature)

        etName.setText(rule.name)
        val actions = Action.values().filter { it != Action.UNKNOWN }
        val actionLabels = actions.map { actionToText(it) }
        spinnerAction.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, actionLabels)
        spinnerAction.setSelection(actions.indexOf(rule.action).coerceAtLeast(0))
        etTargetButton.setText(rule.targetButton ?: "")
        etPriority.setText(rule.priority.toString())
        tvSignature.text = getFirstSignature(rule.id)
        tvSignature.movementMethod = ScrollingMovementMethod.getInstance()

        AlertDialog.Builder(this)
            .setTitle("编辑规则")
            .setView(view)
            .setPositiveButton("保存") { _, _ ->
                val newName = etName.text.toString().trim()
                if (newName.isEmpty()) {
                    Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val newAction = actions[spinnerAction.selectedItemPosition]
                val newTarget = etTargetButton.text.toString().trim()
                val newPriority = etPriority.text.toString().trim().toIntOrNull() ?: 0
                SceneLibrary.updateCategory(
                    categoryId = rule.id,
                    name = newName,
                    action = newAction,
                    targetButton = newTarget,
                    priority = newPriority
                )
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
                refreshList()
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("删除") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("删除规则")
                    .setMessage("确定删除规则「${rule.name}」吗？\n删除后不可恢复。")
                    .setPositiveButton("删除") { _, _ ->
                        SceneLibrary.deleteCategory(rule.id)
                        Toast.makeText(this, "已删除「${rule.name}」", Toast.LENGTH_SHORT).show()
                        refreshList()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .show()
    }

    /** ListView 适配器 */
    private inner class RuleAdapter : BaseAdapter() {
        override fun getCount(): Int = currentRules.size
        override fun getItem(position: Int): SceneCategory = currentRules[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@RuleEditorActivity)
                .inflate(R.layout.item_rule, parent, false)
            val rule = getItem(position)
            // 左侧徽章显示规则序号（1, 2, 3...），让每条规则有明显的编号
            view.findViewById<TextView>(R.id.tvPriority).text = (position + 1).toString()
            view.findViewById<TextView>(R.id.tvName).text = rule.name
            view.findViewById<TextView>(R.id.tvAction).text = "动作：${actionToText(rule.action)}" +
                (if (rule.targetButton != null) "「${rule.targetButton}」" else "")
            val task = extractTaskFromCategory(rule.id)
            view.findViewById<TextView>(R.id.tvTask).text = if (task.isNotEmpty()) "任务：$task" else "任务：（无）"
            view.findViewById<TextView>(R.id.tvHits).text = "命中 ${rule.hitCount} 次  |  优先级 ${rule.priority}"
            val switch = view.findViewById<Switch>(R.id.switchEnabled)
            switch.setOnCheckedChangeListener(null)  // 先解绑，避免回调污染
            switch.isChecked = rule.enabled
            switch.setOnCheckedChangeListener { _, isChecked ->
                SceneLibrary.updateCategory(rule.id, enabled = isChecked)
                Toast.makeText(
                    this@RuleEditorActivity,
                    "「${rule.name}」已${if (isChecked) "启用" else "禁用"}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            return view
        }
    }
}
