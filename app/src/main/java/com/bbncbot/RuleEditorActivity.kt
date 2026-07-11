package com.bbncbot

import android.app.AlertDialog
import android.graphics.Canvas
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
 * - 左滑规则项显示删除按钮（再点删除按钮删除）
 * - 长按规则项拖动调整执行顺序（拖动后自动更新 priority）
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

    /** 当前展示的规则列表（已按 priority 升序排序，拖动后实时调整顺序） */
    private val currentRules: MutableList<SceneCategory> = mutableListOf()

    private lateinit var recyclerView: RecyclerView
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
        recyclerView = findViewById(R.id.recyclerView)
        tvEmpty = findViewById(R.id.tvEmpty)
        val btnDeleteAll = findViewById<Button>(R.id.btnDeleteAll)

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = RuleAdapter()
        recyclerView.adapter = adapter

        // ItemTouchHelper：左滑删除 + 长按拖动排序
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,  // 拖动方向
            ItemTouchHelper.LEFT  // 滑动方向（只允许左滑显示删除）
        ) {
            /** 拖动开始时记录是否长按触发的（避免点击误触发拖动） */
            private var isDragging = false

            override fun onMove(
                rv: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                // 交换列表中的位置
                java.util.Collections.swap(currentRules, from, to)
                adapter.notifyItemMoved(from, to)
                return true
            }

            override fun onMoved(
                rv: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                fromPos: Int,
                target: RecyclerView.ViewHolder,
                toPos: Int,
                x: Int,
                y: Int
            ) {
                super.onMoved(rv, viewHolder, fromPos, target, toPos, x, y)
                // 拖动结束后，按新顺序重新分配 priority 并持久化
                persistOrderAfterDrag()
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // 左滑到阈值后直接触发删除
                val pos = viewHolder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return
                val rule = currentRules.getOrNull(pos) ?: return
                showDeleteConfirm(rule, pos)
            }

            override fun onChildDraw(
                c: Canvas,
                rv: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                // 左滑时显示红色删除背景
                val foreground = (viewHolder as RuleAdapter.RuleViewHolder).foreground
                val background = viewHolder.deleteBackground
                if (dX < 0) {
                    background.visibility = View.VISIBLE
                } else {
                    background.visibility = View.GONE
                }
                // 限制左滑最多到删除按钮宽度（约 200px）
                val maxSwipe = -200f
                val clampedDx = if (dX < maxSwipe) maxSwipe else dX
                getDefaultUIUtil().onDraw(
                    c, rv, foreground, clampedDx, dY, actionState, isCurrentlyActive
                )
            }

            override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(rv, viewHolder)
                val foreground = (viewHolder as RuleAdapter.RuleViewHolder).foreground
                getDefaultUIUtil().clearView(foreground)
                viewHolder.deleteBackground.visibility = View.GONE
            }

            override fun isLongPressDragEnabled(): Boolean = true  // 长按拖动排序
            override fun isItemViewSwipeEnabled(): Boolean = true  // 左滑删除
        })
        touchHelper.attachToRecyclerView(recyclerView)

        // 点击规则项 → 编辑（适配器内设置点击监听，避免与拖动冲突）
        adapter.onItemClick = { position ->
            val rule = currentRules.getOrNull(position)
            if (rule != null) showEditDialog(rule)
        }

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

        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    /** 重新加载规则列表并按 platform 过滤 + priority 排序 */
    private fun refreshList() {
        val allRules = SceneLibrary.listCategories()
        currentRules.clear()
        currentRules.addAll(allRules.filter { cat ->
            val platform = extractPlatformFromCategory(cat.id)
            if (currentPlatformFilter == null) true else platform == currentPlatformFilter
        }.sortedWith(compareBy({ it.priority }, { it.name })))
        adapter.notifyDataSetChanged()
        tvEmpty.visibility = if (currentRules.isEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * 拖动结束后，按列表新顺序重新分配 priority 并持久化
     * - 第 0 位 priority=0，第 1 位 priority=10，依次递增（间隔 10 方便后续插入）
     * - 调用 SceneLibrary.updateCategory 批量更新
     */
    private fun persistOrderAfterDrag() {
        for ((idx, rule) in currentRules.withIndex()) {
            val newPriority = idx * 10
            if (rule.priority != newPriority) {
                SceneLibrary.updateCategory(rule.id, priority = newPriority)
                rule.priority = newPriority
            }
        }
        // 更新序号显示
        adapter.notifyDataSetChanged()
    }

    /**
     * 左滑删除确认对话框
     * @param rule 要删除的规则
     * @param position 在列表中的位置（删除失败时恢复显示）
     */
    private fun showDeleteConfirm(rule: SceneCategory, position: Int) {
        AlertDialog.Builder(this)
            .setTitle("删除规则")
            .setMessage("确定删除规则「${rule.name}」吗？\n删除后不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                SceneLibrary.deleteCategory(rule.id)
                currentRules.removeAt(position)
                adapter.notifyItemRemoved(position)
                // 更新后续项的序号显示
                adapter.notifyItemRangeChanged(position, currentRules.size - position)
                tvEmpty.visibility = if (currentRules.isEmpty()) View.VISIBLE else View.GONE
                Toast.makeText(this, "已删除「${rule.name}」", Toast.LENGTH_SHORT).show()
                // 删除后重新分配 priority
                persistOrderAfterDrag()
            }
            .setNegativeButton("取消") { _, _ ->
                // 取消则恢复原位
                adapter.notifyItemChanged(position)
            }
            .show()
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

    /** RecyclerView 适配器 */
    private inner class RuleAdapter : RecyclerView.Adapter<RuleAdapter.RuleViewHolder>() {

        /** 点击规则项回调（外部设置，避免与拖动冲突） */
        var onItemClick: ((Int) -> Unit)? = null

        inner class RuleViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val foreground: View = view.findViewById(R.id.foreground)
            val deleteBackground: View = view.findViewById(R.id.deleteBackground)
            val tvPriority: TextView = view.findViewById(R.id.tvPriority)
            val tvName: TextView = view.findViewById(R.id.tvName)
            val tvAction: TextView = view.findViewById(R.id.tvAction)
            val tvTask: TextView = view.findViewById(R.id.tvTask)
            val tvHits: TextView = view.findViewById(R.id.tvHits)
            val switchEnabled: Switch = view.findViewById(R.id.switchEnabled)

            init {
                view.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) onItemClick?.invoke(pos)
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RuleViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_rule, parent, false)
            return RuleViewHolder(view)
        }

        override fun onBindViewHolder(holder: RuleViewHolder, position: Int) {
            val rule = currentRules[position]
            // 左侧徽章显示规则序号（1, 2, 3...）
            holder.tvPriority.text = (position + 1).toString()
            holder.tvName.text = rule.name
            holder.tvAction.text = "动作：${actionToText(rule.action)}" +
                (if (rule.targetButton != null) "「${rule.targetButton}」" else "")
            val task = extractTaskFromCategory(rule.id)
            holder.tvTask.text = if (task.isNotEmpty()) "任务：$task" else "任务：（无）"
            holder.tvHits.text = "命中 ${rule.hitCount} 次  |  优先级 ${rule.priority}"
            // 先解绑 listener，避免回收复用时的回调污染
            holder.switchEnabled.setOnCheckedChangeListener(null)
            holder.switchEnabled.isChecked = rule.enabled
            holder.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                SceneLibrary.updateCategory(rule.id, enabled = isChecked)
                Toast.makeText(
                    this@RuleEditorActivity,
                    "「${rule.name}」已${if (isChecked) "启用" else "禁用"}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            // 重置滑动状态（回收的 ViewHolder 可能有残留的偏移）
            holder.deleteBackground.visibility = View.GONE
            holder.foreground.translationX = 0f
        }

        override fun getItemCount(): Int = currentRules.size
    }
}
