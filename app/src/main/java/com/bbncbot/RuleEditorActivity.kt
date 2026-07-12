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
    /** 记录每个 viewHolder 是否已进入"停留显示删除按钮"状态（松手后跳过 ItemTouchHelper 回弹帧的覆写） */
    private val stayOpen = mutableMapOf<RecyclerView.ViewHolder, Boolean>()

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

        // ItemTouchHelper：左滑显示删除按钮（停留）+ 右滑隐藏 + 长按拖动排序
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,  // 拖动方向
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT  // 左滑显示删除，右滑隐藏
        ) {
            /** 记录每个 viewHolder 滑动状态：是否正在由用户拖动 */
            private val swipeActive = mutableMapOf<RecyclerView.ViewHolder, Boolean>()
            /** 记录每个 viewHolder 最后一次滑动时的 dX（用于松手判断停留/回弹） */
            private val lastSwipeDx = mutableMapOf<RecyclerView.ViewHolder, Float>()

            /**
             * 获取删除按钮的实际宽度（px）
             * - deleteBackground 在 XML 中是 wrap_content，宽度取决于"删除"文字 + padding
             * - 不能硬编码，否则不同屏幕密度下前景偏移量与删除按钮宽度不匹配
             * - 第一次调用时测量 deleteBackground 的实际宽度并缓存
             */
            private fun getDeleteWidth(viewHolder: RecyclerView.ViewHolder): Float {
                val bg = (viewHolder as RuleAdapter.RuleViewHolder).deleteBackground
                if (bg.width > 0) return bg.width.toFloat()
                // View 尚未布局完成，手动测量
                val wSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                val hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                bg.measure(wSpec, hSpec)
                val w = bg.measuredWidth.toFloat()
                return if (w > 0) w else 200f  // 兜底默认值
            }

            override fun onMove(
                rv: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
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
                persistOrderAfterDrag()
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                android.util.Log.w("RuleEditor", "onSwiped called unexpectedly dir=$direction pos=${viewHolder.bindingAdapterPosition}")
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
                if (actionState != ItemTouchHelper.ACTION_STATE_SWIPE) {
                    super.onChildDraw(c, rv, viewHolder, dX, dY, actionState, isCurrentlyActive)
                    return
                }
                // 用户开始新的滑动 → 清除停留状态，允许重新滑动
                val foreground = (viewHolder as RuleAdapter.RuleViewHolder).foreground
                if (isCurrentlyActive && stayOpen[viewHolder] == true) {
                    stayOpen[viewHolder] = false
                    foreground.animate().cancel()
                }
                // 已进入停留状态且非用户主动滑动 → 跳过 ItemTouchHelper 回弹帧的覆写
                // （松手后 ItemTouchHelper 会跑回弹动画持续回调 onChildDraw，会覆盖我们的停留状态）
                if (stayOpen[viewHolder] == true && !isCurrentlyActive) {
                    return
                }
                val background = viewHolder.deleteBackground
                val deleteWidthPx = getDeleteWidth(viewHolder)
                // 限制左滑最多到删除按钮宽度，右滑限制为 0
                val clampedDx = when {
                    dX < -deleteWidthPx -> -deleteWidthPx
                    dX > 0 -> 0f
                    else -> dX
                }
                val wasActive = swipeActive[viewHolder] ?: false
                swipeActive[viewHolder] = isCurrentlyActive
                lastSwipeDx[viewHolder] = clampedDx

                // 左滑时显示红色删除背景
                background.visibility = if (clampedDx < 0) View.VISIBLE else View.GONE
                foreground.translationX = clampedDx

                // 关键：检测用户松手（isCurrentlyActive 从 true→false）
                if (wasActive && !isCurrentlyActive) {
                    android.util.Log.i("RuleEditor", "swipe released: lastDx=$clampedDx threshold=${-deleteWidthPx/2} pos=${viewHolder.bindingAdapterPosition}")
                    if (clampedDx < -deleteWidthPx / 2) {
                        // 左滑超过一半 → 停留显示删除按钮，标记 stayOpen 阻止后续回弹帧覆写
                        android.util.Log.i("RuleEditor", "swipe STAY OPEN: animating to $(-deleteWidthPx)")
                        stayOpen[viewHolder] = true
                        background.visibility = View.VISIBLE
                        foreground.animate()
                            .translationX(-deleteWidthPx)
                            .setDuration(150)
                            .start()
                    } else {
                        // 不够一半 → 回弹隐藏
                        android.util.Log.i("RuleEditor", "swipe SPRING BACK: animating to 0")
                        foreground.animate()
                            .translationX(0f)
                            .setDuration(150)
                            .withEndAction { background.visibility = View.GONE }
                            .start()
                    }
                }
            }

            override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(rv, viewHolder)
                val foreground = (viewHolder as RuleAdapter.RuleViewHolder).foreground
                val background = viewHolder.deleteBackground
                val lastDx = lastSwipeDx[viewHolder] ?: 0f
                val deleteWidthPx = getDeleteWidth(viewHolder)
                swipeActive.remove(viewHolder)
                lastSwipeDx.remove(viewHolder)
                android.util.Log.i("RuleEditor", "clearView: lastDx=$lastDx currentTx=${foreground.translationX} bgVis=${background.visibility}")
                // clearView 是松手后的最终回调，确保状态与松手决策一致
                // 如果 onChildDraw 已经处理了停留（translationX 已是 -deleteWidthPx），这里不重复处理
                // 只处理 onChildDraw 未捕获到的边界情况（如直接调用 ItemTouchHelper.stopSwipe）
                if (foreground.translationX == 0f && lastDx < -deleteWidthPx / 2) {
                    android.util.Log.w("RuleEditor", "clearView FIXUP: tx was 0 but lastDx indicates stay-open, re-animating")
                    background.visibility = View.VISIBLE
                    foreground.animate().translationX(-deleteWidthPx).setDuration(150).start()
                }
            }

            override fun isLongPressDragEnabled(): Boolean = true
            override fun isItemViewSwipeEnabled(): Boolean = true

            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = Float.MAX_VALUE
            override fun getSwipeEscapeVelocity(defaultValue: Float): Float = Float.MAX_VALUE
            override fun getSwipeVelocityThreshold(defaultValue: Float): Float = Float.MAX_VALUE
        })
        touchHelper.attachToRecyclerView(recyclerView)

        // 点击规则项 → 编辑（适配器内设置点击监听，避免与拖动冲突）
        adapter.onItemClick = { position ->
            val rule = currentRules.getOrNull(position)
            if (rule != null) showEditDialog(rule)
        }
        // 点击左滑出现的删除按钮 → 弹删除确认
        adapter.onDeleteClick = { position ->
            android.util.Log.i("RuleEditor", "onDeleteClick callback: position=$position rulesSize=${currentRules.size}")
            val rule = currentRules.getOrNull(position)
            if (rule != null) {
                android.util.Log.i("RuleEditor", "onDeleteClick showing confirm for rule='${rule.name}' id=${rule.id}")
                showDeleteConfirm(rule, position)
            } else {
                android.util.Log.w("RuleEditor", "onDeleteClick: rule null at position=$position")
            }
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
     *
     * 注意：不调用 notifyDataSetChanged，否则会触发 onBindViewHolder 重置所有 ViewHolder
     * 的滑动状态（translationX/visibility/stayOpen），导致拖动前展开的删除按钮被收起。
     * 这里改为遍历可见 ViewHolder 直接更新序号文本。
     */
    private fun persistOrderAfterDrag() {
        for ((idx, rule) in currentRules.withIndex()) {
            val newPriority = idx * 10
            if (rule.priority != newPriority) {
                SceneLibrary.updateCategory(rule.id, priority = newPriority)
                rule.priority = newPriority
            }
        }
        // 只更新序号显示，不重新绑定（避免重置滑动状态）
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i) ?: continue
            val holder = recyclerView.getChildViewHolder(child)
            if (holder is RuleAdapter.RuleViewHolder) {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    holder.tvPriority.text = (pos + 1).toString()
                }
            }
        }
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
        /** 点击删除按钮回调 */
        var onDeleteClick: ((Int) -> Unit)? = null

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
                // 点击前景区域：如果删除按钮已展开则先收起，否则触发编辑
                foreground.setOnClickListener {
                    val pos = bindingAdapterPosition
                    android.util.Log.i("RuleEditor", "foreground click: pos=$pos tx=${foreground.translationX} bgVis=${deleteBackground.visibility}")
                    if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                    if (foreground.translationX != 0f) {
                        // 删除按钮展开状态，点击前景收起删除按钮
                        this@RuleEditorActivity.stayOpen[this@RuleViewHolder] = false
                        foreground.animate().translationX(0f).setDuration(150).start()
                        deleteBackground.visibility = View.GONE
                    } else {
                        onItemClick?.invoke(pos)
                    }
                }
                // 点击删除按钮触发删除
                deleteBackground.setOnClickListener {
                    val pos = bindingAdapterPosition
                    android.util.Log.i("RuleEditor", "delete button click: pos=$pos tx=${foreground.translationX} bgVis=${deleteBackground.visibility}")
                    if (pos != RecyclerView.NO_POSITION) onDeleteClick?.invoke(pos)
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
            // 重置滑动状态（回收的 ViewHolder 可能有残留的偏移和停留标记）
            this@RuleEditorActivity.stayOpen.remove(holder)
            holder.deleteBackground.visibility = View.GONE
            holder.foreground.translationX = 0f
        }

        override fun getItemCount(): Int = currentRules.size
    }
}
