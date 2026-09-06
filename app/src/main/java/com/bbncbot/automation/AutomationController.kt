package com.bbncbot.automation

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.bbncbot.service.FarmAccessibilityService
import com.bbncbot.service.FarmAccessibilityService.PageScene
import java.lang.ref.WeakReference

/**
 * 自动化控制器 v2（单例）
 *
 * v2 重构 - 基于用户要求和v11 PC端ADB方案经验：
 *
 * 用户要求的导航路径：
 * 主页右下角任务进入 → 右上角芭芭农场 → 农场主页 → 点击集肥料按钮 → 点击各个去完成按钮获取肥料
 *
 * 用户要求的行为：
 * - 智能的点击判断，取完肥料就退回来继续获取下一个肥料任务
 * - 没有点对有返回按钮，返回到主界面重新开始进入广告获取肥料
 * - 机器人智能地适应退回按钮位置，不要只硬编码一个位置
 * - 只点击广告，邀请推广网页直接返回，任务不做
 * - 安装软件的广告也不做，不安装，直接退出
 * - 邀请好友的肥料不赚
 *
 * 状态机循环：NAVIGATING → OPENING_TASK_LIST → PROCESSING_TASK → WATCHING_AD → CLOSING_AD → RETURNING → PROCESSING_TASK (下一个) → ...
 */
object AutomationController {

    private const val TAG = "AutomationController"

    // ---------- 时间间隔（毫秒） ----------
    /** 通用点击间隔 */
    private const val INTERVAL_CLICK_MS = 2000L
    /** 等待页面加载 */
    private const val INTERVAL_PAGE_LOAD_MS = 5000L
    /** 一轮结束后等待时间 */
    private const val INTERVAL_WAIT_MS = 5000L
    /** 任务列表最大尝试次数（需大于平台坐标候选数，支付宝有11个候选） */
    private const val MAX_TASK_LIST_ATTEMPTS = 8
    /** 单个任务最大尝试次数 */
    private const val MAX_TASK_ATTEMPTS = 3
    /** 广告播放最短等待时间（默认值，单位毫秒） */
    private const val AD_MIN_DURATION_MS = 30000L
    /** 广告播放最大等待时间（秒），超时强制关闭 */
    private const val AD_MAX_DURATION_MS = 90000L
    /** 广告结束检测轮询间隔 */
    private const val AD_END_CHECK_INTERVAL_MS = 5000L
    /** 广告时长解析缓冲（毫秒）：在页面提示的规定时间基础上额外等待，确保肥料奖励到账 */
    private const val AD_DURATION_BUFFER_MS = 2000L
    /** "更快拿奖"流程：点击"允许"后新 App 打开的停留时间（毫秒） */
    private const val FASTER_REWARD_APP_STAY_MS = 16000L
    /** build751: stage=1 无确认弹窗直接跳转外来App的停留时间（毫秒）
     * 用户需求："点击跳转后，过15秒回到跳转前的页面"——停留15秒后从底部上滑停顿
     * 手势打开最近任务，把之前切走前的页面（UC广告页）设置为前台页面 */
    private const val FASTER_REWARD_STAGE1_JUMP_STAY_MS = 15000L
    /** build716: "我要加速"点击后跳转App的停留时间（毫秒）
     * 用户需求："点击我要加速后,需要等待指定的时间后回到芭芭农场广告点击时的页面领取"
     * - 点击"我要加速"会跳转到淘宝/闲鱼等App(穿山甲TTRewardVideoActivity的CTA)
     * - 需要在跳转的App里停留10秒,然后回到广告页面才能领取奖励
     * - 不能关闭芭芭农场重新打开,只能在原来点击"我要加速"时的页面领取 */
    private const val SPEED_UP_JUMP_STAY_MS = 10000L
    /**
     * build737: 深链任务在其它App的默认停留时长（毫秒，任务文案无秒数提示时使用）
     * 需求变更（原为"等2秒激活+kill"）：进入其它App后等够任务要求的时间再切回农场
     */
    private const val DEEP_LINK_DEFAULT_STAY_MS = 15000L
    /** build737: 深链任务停留时长缓冲（毫秒），加在任务文案要求的秒数之上，确保任务判定有效停留 */
    private const val DEEP_LINK_STAY_BUFFER_MS = 5000L
    /** build761: 千问App包名（任务"打开千问发起对话"跳转目标） */
    private const val QIANWEN_PKG = "com.aliyun.tongyi"
    /**
     * build760: bringFarmAppToFront 手势切换（底部上滑停顿开最近任务→点App卡片）的
     * 全流程最坏耗时（毫秒）：800ms 等最近任务 + 最多 5×500ms 卡片重试 + 600ms 验证。
     * 切回后的 kill 被拉起App / runDeepLinkReturnToFarm 页面检查须等该时间后执行，
     * 否则会打断手势流程（kill 前台App）或误判"不在农场页"触发深链重开（WebView 刷新）。
     */
    private const val GESTURE_BRING_FRONT_SETTLE_MS = 4000L
    /** 返回农场页最大尝试次数 */
    private const val MAX_RETURN_ATTEMPTS = 5
    /** 连续无进展轮次上限（超过则重新导航） */
    private const val MAX_NO_PROGRESS_ROUNDS = 3
    /**
     * build744: 连续"下载安装类广告放弃"（期间无任何成功收集）上限，超过则停止自动化
     * （一轮任务数约 6-8，全失败说明当天广告池全是安装类广告，继续跑只会无限杀UC重开）
     */
    private const val MAX_CONSECUTIVE_INSTALL_AD_ABANDON = 8
    /**
     * build754: 陷阱广告（互动陷阱/充值误判陷阱/安装陷阱）连续退出且无进展的跳过阈值
     * 达到后跳过视频类广告入口（"看广告领奖"按钮 + 看视频类任务），改做其他任务；
     * 一旦有进展（collectedCount 增加）视频入口自动恢复
     */
    private const val TRAP_AD_SKIP_THRESHOLD = 2
    /** 施肥按钮最大点击次数（防止无限点击） */
    private const val MAX_FERTILIZE_CLICKS = 30
    /** 滑动浏览任务最大滑动次数 */
    private const val MAX_BROWSE_SWIPES = 6
    /** 滑动达标后等待"再逛xx秒后可领奖"倒计时结束的最大额外滑动次数（避免无限等待） */
    private const val MAX_BROWSE_WAIT_SWIPES = 30
    /** 每次滑动间隔（毫秒） */
    private const val BROWSE_SWIPE_INTERVAL_MS = 2000L
    /**
     * 游戏任务最大时长（硬超时，超时强制退出）
     *
     * 用户澄清：游戏过关卡任务实现不了，但"打开游戏停留玩一下"的任务可以获取肥料。
     * 这类任务只需在游戏内停留规定时长即可发放肥料，无需真正通关。
     * 因此硬超时从原 180s 收紧到 90s（覆盖加载 5s + 停留 30s + 退出余量）。
     */
    private const val GAME_MAX_DURATION_MS = 90000L
    /**
     * 游戏停留目标时长（"打开游戏停留玩一下"任务的核心指标）
     *
     * - 停留达到此时长后，主动按返回退出回农场，任务发放肥料
     * - 停留期间不按返回键（按返回会退出游戏导致停留失败）
     * - 仅检测：陷阱页（充值/交易）立即退出 / 完成页领奖 / 自动返回农场
     */
    private const val GAME_STAY_TARGET_MS = 30000L
    /** 游戏轮询间隔（每 4 秒检测一次页面状态） */
    private const val GAME_ACTION_INTERVAL_MS = 4000L
    /** 游戏加载等待时间 */
    private const val GAME_LOAD_MS = 5000L
    /** 浏览任务中连续关闭红包弹窗的最大次数（超过则视为误判，继续滑动） */
    private const val MAX_RED_PACKET_CLOSE_ATTEMPTS = 3

    /** 当前浏览任务的目标滑动次数（根据页面提示动态计算，无提示时用 MAX_BROWSE_SWIPES） */
    @Volatile
    private var browseTaskTargetSwipes: Int = MAX_BROWSE_SWIPES

    /**
     * 当前浏览任务中连续检测到红包弹窗的次数
     * - 防止 findRedPacketCloseButton 误判导致死循环：超过阈值后不再当红包弹窗处理，继续滑动
     * - 每次进入新的浏览任务（swipeCount=0）时重置
     */
    @Volatile
    private var browseRedPacketCloseAttempts: Int = 0

    /**
     * build736: checkTaskResult 中连续"关闭红包弹窗"的次数
     *
     * 日志证据(debug_test_20260822_070102.log, build735, 06:59:57-07:00:55):
     * 穿山甲"淘宝闪购"红包样式广告,广告创意横幅"朋友快来，淘宝闪购请客啦！领取红包吃美食啦！"
     * 被 findRedPacketCloseButton 误判为红包弹窗(页面含"红包"+"领取红包"),
     * checkTaskResult 每2s点击横幅中心(点的是广告创意,还可能触发跳转),58s死循环
     * 直到用户手动停止,广告从未进入 WATCHING_AD 处理。
     *
     * 防御(双层):
     * 1. 广告打开时(isAdActivity/isAdPlaying/isAdContentShown)跳过红包弹窗处理,
     *    让流程落到下方广告检测分支进入 WATCHING_AD(红包文案是广告创意非弹窗);
     * 2. 非广告页也加次数上限(同 browseTask 的 MAX_RED_PACKET_CLOSE_ATTEMPTS),
     *    超过后不再当红包弹窗处理,防止其他误判死循环。
     * - 每次开始处理新任务(runProcessingTask attempt=0)时重置
     */
    @Volatile
    private var taskRedPacketCloseAttempts: Int = 0

    /**
     * 标记当前浏览任务是否从"直接领取弹窗"进入
     * - true：浏览完成后回到 COLLECTING_DIRECT 继续找其他 direct 按钮
     * - false（默认）：浏览完成后回到 OPENING_TASK_LIST 任务列表流程
     * - 场景：点"立即领取"后弹窗不关闭，按钮变"点此逛一逛再赚1000肥料"，
     *   点该按钮进入浏览，完成后应回 direct 流程而非任务列表
     */
    @Volatile
    private var browseFromDirectPopup: Boolean = false

    /**
     * 上一次 COLLECTING_DIRECT 阶段点击的 direct 按钮文本+bounds（防死循环）
     *
     * build581 修复（debug_test_20260721_152904.log, build580-851d3ea）：
     * - 历史问题：findDirectCollectButtons 返回 11 个按钮,buttons[0]='签到肥料' clickable=false。
     *   performClickSafe fallback dispatchGesture 点击 (143.5,975.5) 无效（签到肥料是装饰性文字
     *   不可点击),但下一轮 findDirectCollectButtons 又返回相同 11 个按钮 buttons[0]='签到肥料'。
     *   runCollectingDirect 重复点击同一按钮 5 次（attempt 1-5）才放弃,浪费时间。
     * - 修复：记录上次点击的 text+bounds,若本轮 buttons[0] 与上次相同（页面无变化,说明点击无效），
     *   跳过 buttons[0] 改用 buttons[1]；若 buttons[1] 也不存在或与上次相同,直接进 OPENING_TASK_LIST。
     * - 进入 COLLECTING_DIRECT 阶段时复位（避免上一轮残留影响本轮判断）。
     */
    @Volatile
    private var lastDirectClickedText: String = ""
    @Volatile
    private var lastDirectClickedBounds: String = ""

    /**
     * build596: AI 视觉识别"点击领取"按钮坐标的防死循环标记
     * - false：本轮未用 AI 视觉识别点击过"点击领取"
     * - true：已用 AI 视觉识别点击过一次,不再重复（避免无限循环点击同一坐标）
     * - 进入 COLLECTING_DIRECT 阶段时复位
     *
     * 用户反馈："uc芭芭农场主页,'点击领取'没有执行点击操作"
     * UC 主页"点击领取"是 H5/Canvas 图像按钮（文字+彩色背景）,无障碍树抓不到 text 节点,
     * findDirectCollectButtons 返回 0。需用 AI 视觉识别按钮坐标并点击。
     * 每轮只尝试一次 AI 视觉识别,避免无限循环。
     */
    @Volatile
    private var aiVisionDirectClickAttempted: Boolean = false

    /**
     * build599 v2: 互动广告（摇一摇/扭一扭）下载按钮点击标记
     * - false：本轮广告未点击过"点击打开或者下载第三方应用"按钮
     * - true：已点击一次,不再重复（避免无限循环点击）
     * - 进入 WATCHING_AD 阶段时复位（每个新广告重置）
     *
     * 用户反馈："这种广告的处理方式为点击按钮'点击打开或者下载第三方应用'，
     * 然后下载完成，获取肥料"
     */
    @Volatile
    private var interactiveAdDownloadClicked: Boolean = false

    /**
     * build584 小说阅读任务：是否已点击"开始阅读"/"继续阅读"按钮进入小说内容页
     * - false：未点击，滑动前检测到小说页需先点按钮
     * - true：已点击或非小说页，直接滑动
     * - 进入 BROWSING_TASK 时（swipeCount=0）重置
     *
     * build585 扩展：用户需求"需要点击一部小说进入，停留15秒上下滑动"
     * - 流程：小说任务页(开始阅读) → 点"开始阅读" → 小说列表页 → 点击一部小说 → 小说内容页 → 上下滑动15秒
     * - browsingNovelStarted: 已点"开始阅读"（进入小说列表页）
     * - browsingNovelEnteredContent: 已点击一部小说（进入小说内容页,可以开始滑动）
     *
     * build590 扩展：用户需求"开始观看得肥料"短剧任务,点击视频播放15秒后退出回主页
     * - 流程：短剧任务页(开始观看) → 点"开始观看" → 短剧播放页 → 等待15秒（滑动模拟活跃）→ pressBack 退出
     * - browsingShortDramaStarted: 已点"开始观看"（进入短剧播放页,可以开始等待/滑动）
     *
     * build620 扩展：用户需求"UC 浏览商品任务,点击某个商品后停留15秒才可以得到肥料"
     * - 流程：商品列表页(商品卡片+得肥料) → 点击一个商品 → 商品详情页 → 等待15秒（滑动模拟活跃）→ pressBack 退出
     * - browsingProductEntered: 已点击商品（进入商品详情页,可以开始等待/滑动）
     * - 与短剧任务区别：商品任务点击商品卡片进入详情页（短剧点"开始观看"进入播放页）
     * - 商品详情页有"加入购物车"+"立即购买"按钮,正常会被 isOnAbnormalPage 判为异常页退出,
     *   browsingProductEntered=true 时豁免（继续停留等待肥料发放）
     */
    @Volatile
    private var browsingNovelStarted: Boolean = false
    @Volatile
    private var browsingNovelEnteredContent: Boolean = false
    @Volatile
    private var browsingShortDramaStarted: Boolean = false
    @Volatile
    private var browsingProductEntered: Boolean = false  // build620: 浏览商品任务已进入商品详情页

    /**
     * 标记当前浏览任务是否从"搜索后浏览立得奖励"任务页进入
     * - true：浏览完成后需要返回两次（搜索结果页 → 搜索任务页 → 芭芭农场）
     * - false（默认）：正常退出流程
     * - 场景：点击任务按钮进入"搜索后浏览立得奖励"页面，点击历史搜索词进入真正的浏览页面，
     *   滑动到"任务完成"后需要返回两次才能回到芭芭农场
     */
    @Volatile
    private var browseFromSearchBrowse: Boolean = false

    /**
     * 当前 GAME_PLAYING 任务的目标停留时长（毫秒）
     * - 默认 [GAME_STAY_TARGET_MS]（30s，普通"打开游戏停留玩一下"任务）
     * - "试玩热门新游"等需长停留任务设为 10 分钟（用户反馈：试玩热门新游需等待 10 分钟退出）
     * - 每次进入新的 GAME_PLAYING 前重置
     */
    @Volatile
    private var gamePlayingStayTargetMs: Long = GAME_STAY_TARGET_MS

    /**
     * 当前广告的最短观看时长（毫秒）
     * - 进入 WATCHING_AD 时按平台广告策略 + 页面提示动态设置（页面提示的秒数 + 缓冲）
     * - 无提示时使用平台默认值 [PlatformConfig.adDefaultMinDurationMs]
     * - 用户要求：太快退出可能获取不到肥料，需保持到规定时间+缓冲后再检测退出
     */
    @Volatile
    private var adMinDurationMs: Long = AD_MIN_DURATION_MS

    /**
     * 当前广告的最大等待时长（毫秒）
     * - 动态计算：max(平台默认上限, adMinDurationMs + 30s)
     * - 确保页面提示的长广告（如120秒）不会被提前强制关闭
     * - 在最短等待时间基础上留 30 秒余量让广告结束并发放奖励
     */
    @Volatile
    private var adMaxDurationMs: Long = AD_MAX_DURATION_MS

    /**
     * 当前广告的结束检测轮询间隔（毫秒）
     * - 进入 WATCHING_AD 时按平台广告策略设置 [PlatformConfig.adEndCheckIntervalMs]
     * - 支付宝/淘宝用 3s 更激进地快速检测退出，UC 用 5s 配合"更快拿奖"流程稳定轮询
     */
    @Volatile
    private var adEndCheckIntervalMs: Long = AD_END_CHECK_INTERVAL_MS

    /**
     * 深链跳转跟踪：广告任务跳转到其他 App 时记录的包名（null=未在深链状态）
     * - 进入 WATCHING_AD 时重置为 null
     * - 检测到不在农场 App 且不在广告 Activity 时，记录当前包名和时间戳
     * - 停留超过 [deepLinkTaskStayMs] 后保留现场切回农场并强杀被跳转 App
     */
    @Volatile
    private var deepLinkAppPkg: String? = null

    /** 深链跳转进入其他 App 的时间戳（elapsedMs），配合 [deepLinkAppPkg] 使用 */
    @Volatile
    private var deepLinkEnterTimeMs: Long = 0L

    /**
     * build737: 当前深链任务在其它App的停留时长（毫秒）
     *
     * 需求变更（原"等2秒激活+kill"）：深链任务（如"去头条极速版逛逛15秒"）进入其它App后，
     * 等够任务文案要求的时间（解析"浏览15秒"等，无提示默认15s）+5s缓冲，
     * 再用 bringFarmAppToFront(moveTaskToFront) 保留现场切回农场——不杀农场App、
     * WebView不重载，页面保持切走时的样子（任务列表弹窗原样恢复）。
     *
     * - processTask 点击任务按钮前从任务文案解析（如"浏览15秒得1000肥料"→20s）
     * - 值 = 解析秒数*1000 + [DEEP_LINK_STAY_BUFFER_MS]，无提示用 [DEEP_LINK_DEFAULT_STAY_MS]
     */
    @Volatile
    private var deepLinkTaskStayMs: Long = DEEP_LINK_DEFAULT_STAY_MS + DEEP_LINK_STAY_BUFFER_MS

    /**
     * build743: 本次 WATCHING_AD 会话是否由"任务深链跳转"进入
     * （processTask 点击任务后检测到跳转到其他 App，非广告 Activity）。
     *
     * 根因（debug_test_20260822_094757.log 结构分析）：runWatchingAd 中"ad button trap"
     * 分支（build714，检测非农场App前台→杀+重开农场+跳过任务）在深链任务分支
     * （build737，等够任务时长后保留现场切回）**之前**执行，且条件不排除深链跳转的
     * App → 深链任务刚跳转就被当作"广告按钮陷阱"杀掉，任务被跳过；深链分支从未
     * 执行过（全部历史日志中"entered deep-linked app"零出现，证实为死代码），
     * build742 的三条落地页恢复路径也因此全部不可达。
     *
     * 修复：深链入口置 true，广告入口置 false；true 时 trap 分支放行，交给深链
     * 任务分支处理（等够时长→保留现场切回→build742 恢复回农场主页）。
     * 在 WATCHING_AD 三个入口处赋值（processTask 广告入口/深链入口、collectDirect 广告入口）。
     */
    @Volatile
    private var watchingAdFromDeepLinkTask: Boolean = false

    /**
     * build744: 连续"下载安装类广告放弃"计数与基线（防无限循环）
     *
     * debug_test_20260822_182904.log（build743, 18:27:06-18:29:01）：
     * 任务#1"看视频"与主页"看广告领奖"点击后全是安装类广告，build741 立即放弃
     * （0ms forceKill+重开农场）→ NAVIGATING → COLLECTING_DIRECT 又点同一按钮 →
     * 22 秒一轮无限循环，4 轮后用户手动停止。
     *
     * - 放弃时若 collectedCount 与基线相同（期间无任何成功）→ streak++；不同则重置为 1
     * - streak >= [MAX_CONSECUTIVE_INSTALL_AD_ABANDON] → 停止自动化（当天广告池
     *   全是安装类广告，继续跑只会无限杀UC重开）
     */
    @Volatile
    private var installAdAbandonStreak: Int = 0

    /** 上次安装类广告放弃时的 collectedCount 快照（检测期间是否有成功） */
    @Volatile
    private var installAdAbandonBaseCount: Int = -1

    /**
     * build754: 陷阱广告连续退出计数（互动陷阱/充值误判陷阱/安装陷阱 forceKill 退出）
     *
     * debug_test_20260829_173308.log（build753, 17:29:46-17:33:03, 3.5min）：
     * 7 次广告全是快手互动陷阱（"扭一扭或点击跳转详情页或第三方应用"，淘宝/灵光推广），
     * forceKill 退出回农场后 COLLECTING_DIRECT 再点"看广告领奖"、任务列表每轮重置
     * currentTaskIndex=0 再点任务#1"看视频得巨额肥料(1/10)" → 同类陷阱广告 → 循环 7 轮
     * 零收益，任务计数卡 1/10，松鼠大战(+2400)/头条极速版(+684)等任务从未被尝试。
     *
     * - 陷阱退出时若 collectedCount 与基线相同（期间无任何成功）→ streak++；不同则重置为 1
     * - streak >= [TRAP_AD_SKIP_THRESHOLD] 且仍无进展 → 跳过视频类广告入口，改做其他任务
     */
    @Volatile
    private var trapAdExitStreak: Int = 0

    /** 上次陷阱广告退出时的 collectedCount 快照（检测期间是否有成功） */
    @Volatile
    private var trapAdExitBaseCount: Int = -1

    /**
     * build747: 当前正在处理的任务上下文文本（runProcessingTask 点击时快照）
     * 供 checkTaskResult 判断当前任务类型（如是否签到任务）
     */
    @Volatile
    private var currentTaskContextText: String = ""

    /**
     * build748: 互动广告"点击立即获取"按钮已点击标记（每轮广告只点一次）
     */
    @Volatile
    private var interactiveAdClickClaimClicked: Boolean = false

    /** build781: 互动广告"点击立即获取"按钮点击时刻（检测点击无跳转而死等用） */
    @Volatile
    private var interactiveAdClickClaimTimeMs: Long = 0L

    /** build781: 互动广告"点击立即获取"已重试过一次标记（每轮广告最多重试一次） */
    @Volatile
    private var interactiveAdClaimRetried: Boolean = false

    /**
     * build756: 本轮 COLLECTING_DIRECT 是否点击过"签到"类直达按钮
     * 点击成功后按钮变"已领取"（每日直达领取完成），后续 0 direct 按钮时
     * 应跳过 AI 视觉找"点击领取"（lite 渲染态无该 canvas 按钮必然 15s 超时）
     */
    @Volatile
    private var directButtonSignInClicked: Boolean = false

    /** 上一轮广告检测时是否有倒计时（用于多信号融合检测倒计时消失） */
    @Volatile
    private var prevAdHadCountdown: Boolean = false

    // build671 修复（debug_test_20260731_214538.log, build669）：
    // UC"看视频得巨额肥料"任务点击后跳转淘宝 TMSActivity,页面"30秒"是静态文字（商品页描述）,
    // 非动态倒计时。watchAd 误判为广告倒计时,设置 min wait=32000ms,卡死 62 秒直到用户手动停止。
    // 修复：记录初始倒计时值,15 秒后若倒计时未减少,判定为静态文字,直接退出跳过任务。
    /** 本次广告初始倒计时秒数（elapsedMs==0 时记录） */
    @Volatile
    private var adInitialCountdownSeconds: Int = 0
    /** 倒计时停滞检测已触发（避免重复退出） */
    @Volatile
    private var adCountdownStallHandled: Boolean = false
    /** build675: "点我加速"按钮已点击（穿山甲激励视频加速按钮,每轮广告只点一次） */
    @Volatile
    private var adSpeedUpClicked: Boolean = false
    /** build716: "我要加速"跳转状态机
     * - 0=未跳转（正常广告观看）
     * - 1=已点击"我要加速"并跳转到其他App,停留中
     * - 2=停留结束已pressBack回广告页,继续领奖
     * 每次进入 WATCHING_AD 时重置为 0 */
    @Volatile
    private var adSpeedUpJumpStage: Int = 0
    /** build716: "我要加速"跳转的目标App包名（用于判断是否还在跳转App中） */
    @Volatile
    private var adSpeedUpJumpPkg: String? = null
    /** build716: 点击"我要加速"跳转时的时间戳（用于计算停留时间） */
    @Volatile
    private var adSpeedUpJumpTimeMs: Long = 0L

    /** build696: "去体验N秒可立即领奖"CTA 已点击（体验类广告,每轮广告只点一次） */
    @Volatile
    private var adExperienceClicked: Boolean = false

    // build719: 互动广告"上滑或点击查看"提示已点击标记(每轮广告重置)
    @Volatile
    private var adSwipeHintClicked: Boolean = false

    /** 本次广告观看的农场平台（强杀深链 App 后重新启动此平台回到农场） */
    @Volatile
    private var watchingAdPlatform: Platform = Platform.UNKNOWN

    // ---------- 坐标比例候选 ----------
    // 注：坐标比例由当前平台 PlatformConfig 动态提供（UC/支付宝/淘宝各自不同），
    // 见 [PlatformConfig.collectFertilizerCoords] / [adCloseCoords] / [backButtonCoords]
    // 以下常量仅为兜底默认值（UC 配置），实际运行时优先使用 service.currentPlatformConfig()

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var state: AutomationState = AutomationState.IDLE

    @Volatile
    private var serviceRef: WeakReference<FarmAccessibilityService>? = null

    /** 状态变化回调（用于通知悬浮窗更新 UI） */
    var onStateChanged: ((AutomationState) -> Unit)? = null

    /** 已收集的肥料数量 */
    @Volatile
    private var collectedCount: Int = 0

    /** 当前任务列表中的按钮索引 */
    @Volatile
    private var currentTaskIndex: Int = 0

    /** 当前任务按钮列表（缓存） */
    @Volatile
    private var taskButtons: List<AccessibilityNodeInfo> = emptyList()

    /**
     * 本轮任务列表是否已通过点击"集肥料"按钮主动调出（闭环规则）
     *
     * 规则：支付宝每次进入 OPENING_TASK_LIST 状态时，必须先主动点击一次
     * "集肥料/施肥/领肥料/任务列表"等入口按钮，确保任务列表是本轮重新打开的，
     * 而非沿用上一轮残留可见的旧按钮。
     * - true：本轮已点击过集肥料入口（或已确认任务列表打开），可直接处理
     * - false：本轮还没点过，必须先点，不能因为有残留"去完成"按钮就跳过
     *
     * 每次进入新一轮任务循环（任务结束返回农场页重新 OPENING_TASK_LIST）时重置为 false。
     */
    @Volatile
    private var taskListOpenedThisRound: Boolean = false

    /** 连续无进展轮次 */
    @Volatile
    private var noProgressRounds: Int = 0

    /** build775: navigate 锁屏解锁尝试次数（>5 次说明有密码锁屏,退避等用户解锁） */
    @Volatile
    private var lockUnlockAttempts: Int = 0

    /** build775: RETURNING 中"UC已前台但非农场页"的渲染等待次数（最多2次后不kill发深链） */
    @Volatile
    private var returnForegroundWaitCount: Int = 0

    // build545：施肥连续无进展次数（肥料数值未增加），用于防卡死
    @Volatile
    private var noProgressStreak: Int = 0

    // build549：上一轮施肥时的 remainCount（"还差X次领肥料"中的 X），用于检测施肥是否生效
    @Volatile
    private var lastRemainCount: Int = -1

    /**
     * 当前任务剩余重玩次数（多次点击任务）
     * - 用户要求：有些任务按钮上有次数（如 1/3），表示可以多次点击
     * - 点击任务按钮时从按钮文本/上下文解析剩余次数，存入此字段
     * - 任务完成后：若 > 0，不递增 currentTaskIndex，重新打开任务列表点击同一任务
     * - 任务跳过/失败时：不重玩，直接递增 currentTaskIndex
     */
    @Volatile
    private var taskReplayRemaining: Int = 0

    /** 当前任务连续失败次数（未知页面/卡住等）。达到 MAX_TASK_FAILS 跳过任务 */
    @Volatile
    private var currentTaskFailCount: Int = 0

    /**
     * build733: 本次任务按钮点击后是否离开过农场页
     *
     * true=点击后检测到不在农场页(进了广告/浏览页等);
     * false=点击后从未离开农场页(点击无效果,广告未拉出)。
     * 用于 checkTaskResult 区分"真任务完成"与"农场页含'已完成'文案的误判"。
     * runProcessingTask 开始处理新任务时重置为 false。
     */
    @Volatile
    private var taskClickLeftFarm: Boolean = false

    /**
     * build758: 点击任务"去完成"按钮时记录的 activity 名。
     * checkTaskResult 判定"任务点击无效果"时要求 activity 未变——
     * 点击后 activity 变化说明页面已切换（如"玩松鼠大战"H5游戏页打开，
     * act: InnerUCMobile→LinearLayout），点击是有效果的，
     * 不应判"无效果"死磕重试3次浪费26s后跳过任务。
     */
    @Volatile
    private var taskClickActivityName: String? = null

    /**
     * build759: 互动广告"点击跳转拿奖励"按钮已点击，等待跳转落地页停留后返回。
     * 该类按钮点击后会拉起第三方App（如淘宝闪购页），落地页常含"立即购买/查看详情"
     * 等文案，会被识别为 TRAP_ABNORMAL/TRAP_LANDING 陷阱——但这是预期跳转（拿奖励），
     * 不应触发陷阱防御，应等够停留时长（deepLinkTaskStayMs）后保留现场切回农场。
     */
    @Volatile
    private var interactiveAdJumpPending: Boolean = false

    /**
     * build761: 千问对话任务状态
     * 任务"打开千问发起对话"跳转千问App后，需在对话框发送"你吃饭了吗"才算发起对话。
     * typed=文本已填入输入框；sent=发送按钮已点击（对话已发起）。
     */
    @Volatile
    private var qianwenChatTyped: Boolean = false
    @Volatile
    private var qianwenChatSent: Boolean = false

    /** 单个任务最大失败次数，超过则跳过该任务 */
    private const val MAX_TASK_FAILS = 2

    // build610：标记当前任务是答题任务（"去答题"按钮 / 上下文含"答题"/"问答"）
    // 用于 checkTaskResult 中：答题页是 H5/Canvas 绘制，无障碍树抓不到问题+选项文本，
    // isQuizPage() 返回 false 时，用 AI 视觉接口截图识别答题页并选出正确答案。
    @Volatile
    private var currentTaskIsQuiz: Boolean = false

    // build617：AI 视觉答题连续失败计数器（防止死循环）
    // 场景：AI 坐标不准（返回固定 0.5/0.917 屏幕底部）→ 点击不到正确选项 →
    //       未弹出奖励按钮 → findQuizRewardButton 返回 null → 前进下一任务 →
    //       任务进度仍 0/1 → openTaskList 重置 currentTaskIndex=0 → 又回到答题任务 → 死循环
    // 修复：累计失败次数，超过阈值后强制跳过答题任务（openTaskList 从 currentTaskIndex=1 开始）
    @Volatile
    private var quizVisionFailCount: Int = 0
    private val QUIZ_VISION_FAIL_THRESHOLD = 3

    // ---------- build529：AI 视觉进度识别节流（用户要求"全部实现"） ----------
    // 用于 runGamePlaying / runWatchingAd 中截屏识别环形进度条填充比例
    // 节流：避免每次轮询都调 AI（视觉模型推理慢 + 限流），GAME/AD 期间最多 20s 调一次
    /** 上次 AI 视觉进度识别的时间戳（ms）；0 表示本任务还未调用过 */
    @Volatile
    private var lastAiProgressCheckMs: Long = 0L

    /** AI 视觉进度识别最小间隔（ms），避免每次轮询都打 AI */
    private const val AI_PROGRESS_CHECK_INTERVAL_MS = 20000L

    /**
     * "更快拿奖"弹窗处理状态
     * - 0=未处理（等待检测"我要更快拿奖"按钮）
     * - 1=已点入口按钮，等待确认弹窗出现并点"允许"
     * - 2=已点"允许"，新 App 已打开，停留16秒
     * - 3=已关闭新 App，等待"恭喜获得奖励提升"窗口，点右上角关闭
     * - 4=已完成，进入退出流程
     * - 每次进入 WATCHING_AD 时重置为 0
     */
    @Volatile
    private var fasterRewardStage: Int = 0

    /** "更快拿奖"流程：记录点击"允许"后打开的新 App 包名（用于关闭） */
    @Volatile
    private var fasterRewardAppPkg: String? = null

    /** "更快拿奖"流程：点击"允许"时的时间戳（用于计算16秒停留） */
    @Volatile
    private var fasterRewardAppEnterTimeMs: Long = 0L

    /** "更快拿奖"流程：stage=1 等待 confirm popup 的重试次数（超时放弃） */
    @Volatile
    private var fasterRewardStage1WaitCount: Int = 0

    /** build751: stage=1 无确认弹窗直接跳转的外来App包名（停留15秒手势切回期间记录） */
    @Volatile
    private var fasterRewardStage1JumpPkg: String = ""

    /** build751: stage=1 直接跳转发生时刻（System.currentTimeMillis，用于计算15秒停留） */
    @Volatile
    private var fasterRewardStage1JumpStartMs: Long = 0L

    /** build780: stage=1 手势切回失败标记（本会话内不再点"我要更快拿奖"入口，改正常看广告拿奖励） */
    @Volatile
    private var fasterRewardRecentsFailed: Boolean = false

    // ---------- 跨平台切换 ----------
    /** 跨平台切换：原平台（切换完成后回到此平台） */
    @Volatile
    private var switchOriginalPlatform: Platform = Platform.UNKNOWN
    /** 跨平台切换：目标平台 */
    @Volatile
    private var switchTargetPlatform: Platform = Platform.UNKNOWN
    /** 跨平台切换阶段：LAUNCH_TARGET=启动目标平台, FERTILIZE_TARGET=目标平台施肥, RETURN_ORIGINAL=返回原平台, RESUME=恢复原平台导航 */
    @Volatile
    private var switchStage: String = ""
    /** 跨平台切换重试计数 */
    @Volatile
    private var switchRetryCount: Int = 0
    /** 跨平台切换最大重试次数 */
    private const val MAX_SWITCH_RETRIES = 8

    val currentState: AutomationState get() = state
    val isRunning: Boolean
        get() = state != AutomationState.IDLE && state != AutomationState.STOPPING

    /** 绑定 FarmAccessibilityService */
    fun bindService(service: FarmAccessibilityService) {
        serviceRef = WeakReference(service)
        Log.i(TAG, "FarmAccessibilityService bound")
    }

    /** 解绑 */
    fun unbindService() {
        serviceRef = null
        if (isRunning) {
            stop()
        }
    }

    private fun getService(): FarmAccessibilityService? = serviceRef?.get()

    /** 调试日志写到外部存储文件（华为 logcat 加密，用文件替代） */
    private fun debugLog(msg: String) {
        Log.i(TAG, msg)
        try {
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date())
            val line = "$timestamp $msg\n"
            // 必须用 Context.getExternalFilesDir(null) 而非 Environment.getExternalStorageDirectory()
            // 原因：Android 11+ 限制外部存储访问，Environment 路径写入会静默失败
            // 通过 serviceRef 拿到 Service Context（Service 是 Context 子类）
            // 与 LogUploader.getLogDir / FarmAccessibilityService.debugLog 保持同一路径
            val ctx = serviceRef?.get()
            val file = if (ctx != null) {
                java.io.File(ctx.getExternalFilesDir(null), "debug.log")
            } else {
                // Service 已销毁的兜底：用 filesDir（App 内部存储，必定可写）
                java.io.File(android.app.Application().filesDir, "debug.log")
            }
            file.parentFile?.mkdirs()
            file.appendText(line)
        } catch (_: Exception) { /* ignore */ }
    }

    /**
     * 页面状态快照日志：输出当前所有关键页面判断结果，用于诊断"卡在哪个页面"问题
     * 调用时机：状态转换、关键决策点
     */
    private fun logPageSnapshot(service: FarmAccessibilityService, tag: String) {
        try {
            val pkg = service.getCurrentWindowPackage() ?: "null"
            val activity = service.getCurrentActivityName() ?: "null"
            // 注意：这些检测方法部分有缓存或副作用，按从轻到重排序
            val onFarm = service.isOnFarmPage()
            val adActivity = service.isAdActivity()
            val adPlaying = service.isAdPlaying()
            val adContent = service.isAdContentShown()
            val abnormal = service.isOnAbnormalPage()
            val nonAdTask = service.isNonAdTaskPage()
            val nonAdPkg = service.isNonAdPage()
            val taskComplete = service.isTaskCompletePage()
            val searchRec = service.isSearchRecommendPage()
            debugLog("[$tag] snapshot: pkg=$pkg, act=$activity, onFarm=$onFarm, adActivity=$adActivity, adPlaying=$adPlaying, adContent=$adContent, abnormal=$abnormal, nonAdTask=$nonAdTask, nonAdPkg=$nonAdPkg, taskComplete=$taskComplete, searchRec=$searchRec")
            // build539 诊断（用户反馈"芭芭农场主页上的'点击领取'看不到吗"）：
            // 原日志只打印前 10 个文本（take(10)），无法判断"点击领取"是否在 accessibility tree 里。
            // 这里 dump 所有含"领取"/"领肥"/"立即领"/"点击领"/"可领取"的文本节点（含 bounds 和 clickable），
            // 用于诊断"点击领取"按钮是否被 accessibility 暴露，以及它的 bounds 是否合法、是否可点击。
            // 注意：H5 WebView 内的按钮若未暴露给 accessibility，这里也找不到 → 需要坐标兜底或 AiVision。
            try {
                val root = service.getRootInFarmApp()
                if (root != null) {
                    val claimTexts = mutableListOf<String>()
                    service.collectClaimTextNodesForDiag(root, claimTexts)
                    if (claimTexts.isNotEmpty()) {
                        debugLog("[$tag] claim-text-nodes (count=${claimTexts.size}):")
                        for (line in claimTexts) {
                            debugLog("[$tag]   $line")
                        }
                    } else {
                        debugLog("[$tag] claim-text-nodes: NONE (no node text contains 领取/领肥/立即领/点击领/可领取)")
                    }
                }
            } catch (e: Exception) {
                debugLog("[$tag] claim-text-nodes dump error: ${e.message}")
            }
        } catch (e: Exception) {
            debugLog("[$tag] snapshot error: ${e.message}")
        }
    }

    /** 当前平台配置的集肥料按钮坐标候选 */
    private fun collectFertilizerCandidates(service: FarmAccessibilityService) =
        service.currentPlatformConfig().collectFertilizerCoords

    /**
     * 从任务按钮文本和上下文中解析任务剩余次数
     * - 匹配 "x/y" 格式（如 "1/3" → 剩余 2 次）
     * - 匹配 "剩余x次" 格式
     * - 无次数标记返回 0（单次任务）
     */
    private fun parseTaskRemainingCount(buttonText: String, contextText: String): Int {
        // 匹配 "x/y" 格式，如 "1/3", "2/3", "(1/3)"
        val countPattern = Regex("""(\d+)\s*/\s*(\d+)""")
        // 优先从上下文中解析（任务标题旁通常有次数标记）
        val match = countPattern.find(contextText) ?: countPattern.find(buttonText)
        if (match != null) {
            val completed = match.groupValues[1].toIntOrNull() ?: 0
            val total = match.groupValues[2].toIntOrNull() ?: 0
            if (total > 0 && total <= 20 && completed < total) {
                val remaining = total - completed
                debugLog("parseTaskCount: completed=$completed, total=$total, remaining=$remaining")
                return remaining
            }
            if (total > 20) {
                debugLog("parseTaskCount: skip x/y=$completed/$total (total>20, likely fertilizer progress not replay count)")
            }
        }
        // 匹配 "剩余x次" 格式
        val remainingPattern = Regex("""剩余\s*(\d+)\s*次""")
        val remainingMatch = remainingPattern.find(contextText)
        if (remainingMatch != null) {
            val remaining = remainingMatch.groupValues[1].toIntOrNull() ?: 0
            debugLog("parseTaskCount: 剩余${remaining}次")
            return remaining
        }
        return 0
    }

    /**
     * build737: 从任务文案解析深链任务的其它App停留时长（毫秒）
     *
     * 匹配"浏览15秒"/"逛15秒"/"停留15秒"/"观看15秒"/"滑动15s"等 关键词+数字+秒 模式，
     * 返回 秒数*1000 + [DEEP_LINK_STAY_BUFFER_MS]（缓冲确保任务判定有效停留）；
     * 无匹配或超范围(>300s视为误匹配)时用默认 [DEEP_LINK_DEFAULT_STAY_MS]+缓冲。
     *
     * 示例："去头条极速版浏览15秒得1000肥料" → 15000+5000=20000ms
     */
    private fun parseDeepLinkStayMs(taskText: String): Long {
        val match = Regex("""(?:浏览|逛|停留|观看|滑动|看)\s*(\d+)\s*[秒sS]""").find(taskText)
        val parsedSec = match?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val baseSec = if (parsedSec in 1..300) parsedSec else (DEEP_LINK_DEFAULT_STAY_MS / 1000L).toInt()
        if (parsedSec in 1..300) {
            debugLog("parseDeepLinkStayMs: parsed ${parsedSec}s from task text (matched '${match?.value}'), stay=${baseSec * 1000L + DEEP_LINK_STAY_BUFFER_MS}ms")
        } else {
            debugLog("parseDeepLinkStayMs: no duration hint (parsed=$parsedSec), using default ${baseSec}s, stay=${baseSec * 1000L + DEEP_LINK_STAY_BUFFER_MS}ms")
        }
        return baseSec * 1000L + DEEP_LINK_STAY_BUFFER_MS
    }

    /**
     * 任务完成后决定是否重玩同一任务或前进到下一个任务
     * - [taskReplayRemaining] > 0：递减，不递增 currentTaskIndex（重玩同一任务）
     * - [taskReplayRemaining] <= 0：递增 currentTaskIndex（前进到下一个任务）
     * @return true=已前进到下一个任务，false=将重玩同一任务
     */
    private fun advanceTaskIndex(): Boolean {
        if (taskReplayRemaining > 0) {
            taskReplayRemaining--
            debugLog("advanceTask: replaying same task, remainingReplays=$taskReplayRemaining")
            return false
        }
        currentTaskIndex++
        taskReplayRemaining = 0
        return true
    }

    /** 当前平台配置的广告关闭按钮坐标候选 */
    private fun adCloseCandidates(service: FarmAccessibilityService) =
        service.currentPlatformConfig().adCloseCoords

    /** 当前平台配置的退回按钮坐标候选 */
    private fun backButtonCandidates(service: FarmAccessibilityService) =
        service.currentPlatformConfig().backButtonCoords

    /**
     * build754: 记录一次陷阱广告退出（互动陷阱/充值误判陷阱/安装陷阱 forceKill 退出）
     * 期间无进展（collectedCount 与基线相同）则 streak++，有进展则重置为 1
     */
    private fun recordTrapAdExit() {
        if (collectedCount == trapAdExitBaseCount) {
            trapAdExitStreak++
        } else {
            trapAdExitStreak = 1
            trapAdExitBaseCount = collectedCount
        }
        debugLog("trapAdExit: streak=$trapAdExitStreak (base=$trapAdExitBaseCount, collected=$collectedCount)")
    }

    /**
     * build754: 是否应跳过视频类广告入口（"看广告领奖"按钮 + 看视频类任务）
     * 条件：连续 TRAP_AD_SKIP_THRESHOLD 次陷阱退出且期间无任何进展（collectedCount 未变）
     */
    private fun shouldSkipVideoAdEntries(): Boolean =
        trapAdExitStreak >= TRAP_AD_SKIP_THRESHOLD && collectedCount == trapAdExitBaseCount

    /** build754: 判断任务/按钮文本是否视频类广告任务（看视频/看广告等） */
    private fun isVideoAdTask(text: String): Boolean =
        text.contains("看视频") || text.contains("看广告") ||
            text.contains("观看视频") || text.contains("视频得")

    /** 启动自动化 */
    fun start() {
        val service = getService()
        if (service == null) {
            Log.w(TAG, "start: FarmAccessibilityService not bound")
            debugLog("start: FarmAccessibilityService not bound")
            return
        }
        if (isRunning) {
            Log.d(TAG, "start: already running, ignore")
            return
        }
        Log.i(TAG, "=== Automation v2 Started ===")
        debugLog("=== Automation v2 Started === platform=${service.currentPlatform}")
        // 取消所有导航回调，避免 stepClickFarmTab 在后台干扰自动化
        service.cancelNavigation()
        collectedCount = 0
        currentTaskIndex = 0
        noProgressRounds = 0
        taskButtons = emptyList()
        browseFromDirectPopup = false  // 复位 direct 弹窗标记，避免上一轮残留
        browseFromSearchBrowse = false  // 复位搜索浏览任务标记，避免上一轮残留
        lastDirectClickedText = ""  // build581: 复位 direct 防死循环标记
        lastDirectClickedBounds = ""
        installAdAbandonStreak = 0  // build744: 复位安装类广告连续放弃计数
        trapAdExitStreak = 0  // build754: 复位陷阱广告连续退出计数
        fasterRewardRecentsFailed = false  // build780: 复位"更快拿奖"手势切回失败标记
        trapAdExitBaseCount = -1
        installAdAbandonBaseCount = -1
        aiVisionDirectClickAttempted = false  // build596: 复位 AI 视觉识别点击标记
        directButtonSignInClicked = false  // build756: 复位签到直达按钮点击标记
        browsingNovelStarted = false  // build584: 复位小说阅读任务标记
        currentTaskIsQuiz = false  // build610: 复位答题任务标记
        quizVisionFailCount = 0  // build617: 复位 AI 视觉答题失败计数器
        browsingNovelEnteredContent = false  // build585: 复位小说内容页标记
        browsingShortDramaStarted = false  // build590: 复位短剧观看任务标记
        browsingProductEntered = false  // build620: 复位浏览商品任务标记
        // 重置当前平台的广告完成标记（新一轮运行可重新标记完成）
        resetCurrentPlatformComplete(service)
        moveTo(AutomationState.NAVIGATING)
        handler.post { runNavigating(attempt = 0) }
    }

    /** 停止自动化 */
    fun stop() {
        if (state == AutomationState.IDLE) return
        Log.i(TAG, "automation stopping")
        moveTo(AutomationState.STOPPING)
        handler.removeCallbacksAndMessages(null)
        // 取消进行中的导航回调，避免 stop 后导航继续干扰用户操作
        getService()?.cancelNavigation()
        getService()?.setAdMode(false)
        moveTo(AutomationState.IDLE)
    }

    // ============== 三平台广告完成跟踪 ==============
    private const val PREFS_NAME = "platform_ads_status"
    private const val KEY_UC = "uc_complete"
    private const val KEY_ALIPAY = "alipay_complete"
    private const val KEY_TAOBAO = "taobao_complete"
    /** 三平台广告全部完成的通知 channel id */
    private const val NOTIF_CHANNEL_ID = "all_ads_complete"

    /**
     * 重置当前平台的广告完成标记
     * 在 [start] 时调用，使新一轮运行可重新标记完成
     */
    private fun resetCurrentPlatformComplete(service: com.bbncbot.service.FarmAccessibilityService) {
        val prefs = service.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val key = platformPrefsKey(service.currentPlatform) ?: return
        prefs.edit().putBoolean(key, false).apply()
        debugLog("reset platform complete: ${service.currentPlatform}")
    }

    /**
     * 标记当前平台的广告已获取完，并检查三平台是否全部完成
     * 若三平台都完成 → 发送通知 + Toast 提示用户
     */
    private fun markPlatformAdsComplete(service: com.bbncbot.service.FarmAccessibilityService) {
        val prefs = service.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val key = platformPrefsKey(service.currentPlatform) ?: return
        prefs.edit().putBoolean(key, true).apply()
        debugLog("marked platform complete: ${service.currentPlatform}")

        val ucDone = prefs.getBoolean(KEY_UC, false)
        val alipayDone = prefs.getBoolean(KEY_ALIPAY, false)
        val taobaoDone = prefs.getBoolean(KEY_TAOBAO, false)
        debugLog("platform status: UC=$ucDone, ALIPAY=$alipayDone, TAOBAO=$taobaoDone")

        if (ucDone && alipayDone && taobaoDone) {
            Log.i(TAG, "=== All 3 platforms' ads complete! Notifying user ===")
            debugLog("=== All 3 platforms (UC/Alipay/Taobao) ads complete! ===")
            notifyAllPlatformsComplete(service)
            // 通知后重置所有平台标记，以便次日可重新触发
            prefs.edit().clear().apply()
        }
    }

    /** 获取当前平台对应的 SharedPreferences key */
    private fun platformPrefsKey(platform: Platform): String? = when (platform) {
        Platform.UC -> KEY_UC
        Platform.ALIPAY -> KEY_ALIPAY
        Platform.TAOBAO -> KEY_TAOBAO
        Platform.UNKNOWN -> null
    }

    /** 三平台全部完成时发送通知 + Toast */
    private fun notifyAllPlatformsComplete(service: com.bbncbot.service.FarmAccessibilityService) {
        // Toast 提示（在主线程）
        handler.post {
            android.widget.Toast.makeText(
                service,
                "🎉 淘宝、支付宝、UC极速版的广告肥料已全部获取完成！",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }

        // 系统通知
        try {
            val nm = service.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            // Android 8+ 需要 NotificationChannel
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    NOTIF_CHANNEL_ID,
                    "广告肥料完成通知",
                    android.app.NotificationManager.IMPORTANCE_HIGH
                )
                nm.createNotificationChannel(channel)
            }
            val notification = androidx.core.app.NotificationCompat.Builder(service, NOTIF_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("三平台广告肥料已全部完成")
                .setContentText("淘宝、支付宝、UC极速版的广告肥料已全部获取完成！")
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            nm.notify(1001, notification)
        } catch (e: Exception) {
            Log.e(TAG, "notifyAllPlatformsComplete failed: ${e.message}", e)
        }
    }

    private fun moveTo(newState: AutomationState) {
        if (state == newState) return
        Log.d(TAG, "state: $state -> $newState")
        debugLog("state: $state -> $newState")
        state = newState
        onStateChanged?.invoke(newState)
    }

    // ============== 阶段1: 导航到农场页 ==============

    /**
     * 导航阶段：确保在农场页
     * - 如果已在农场页，直接进入打开任务列表阶段
     * - 如果不在农场页，等待用户手动打开或尝试返回
     */
    private fun runNavigating(attempt: Int) {
        if (state != AutomationState.NAVIGATING) return
        val service = getService() ?: run { stop(); return }

        // build753: forceKill宿主后屏幕可能熄灭进入AOD(无障碍只能读到AOD窗口),
        // 先确保屏幕点亮再导航(日志现象: navigate-start snapshot pkg=com.hihonor.aod)
        service.ensureScreenOn()

        // 主动检测当前前台 App 平台（无障碍服务刚连接时可能还没检测到）
        service.refreshPlatform()

        if (attempt == 0) {
            logPageSnapshot(service, "navigate-start")
        }

        // build591 修复（debug_test_20260721_195055.log, build589-fef7ce2, UC 平台 line 50-96）：
        // 历史问题：UC "开始观看得肥料"短剧任务页 root 是 systemui 的 FrameLayout,
        // collectAllText 只拿到 [开始观看, 得肥料] 2 个文本。
        // build590 在 isOnFarmPage 加了 isShortDramaPage 排除（让短剧页不被判为农场主页）,
        // 但 navigate 的 isShortDramaPage 检测只加在 generic popup 分支前,
        // 而短剧页 isGenericPopup 返回 false（"得肥料"匹配 fertilizerKeywords）,
        // identifyCurrentScene 返回 UNKNOWN,不会走 generic popup 分支,
        // 导致 isShortDramaPage 检测永不触发,短剧页死循环：
        //   NAVIGATING(onFarm=false) → 跳过 line 677 → signInScene=UNKNOWN →
        //   跳过 SIGN_IN/GENERIC_POPUP → attempt<10 重试 → 无限循环。
        // 修复：在 runNavigating 最开头（isOnFarmPage 之前）前置 isShortDramaPage/isNovelReadPage
        // 检测,若是短剧页/小说页直接进 BROWSING_TASK,不依赖 isOnFarmPage/identifyCurrentScene。
        if (service.isShortDramaPage()) {
            Log.i(TAG, "navigate: short drama page detected at entry (开始观看得肥料), entering BROWSING_TASK")
            debugLog("navigate: short drama page at entry, entering BROWSING_TASK to click 开始观看 + wait 15s")
            browsingShortDramaStarted = false
            taskButtons = emptyList()
            currentTaskIndex = 0
            moveTo(AutomationState.BROWSING_TASK)
            handler.postDelayed({ runBrowsingTask(swipeCount = 1) }, INTERVAL_CLICK_MS)
            return
        }
        if (service.isNovelReadPage()) {
            Log.i(TAG, "navigate: novel read page detected at entry (看一本喜欢的小说), entering BROWSING_TASK")
            debugLog("navigate: novel read page at entry, entering BROWSING_TASK to click 开始阅读 + swipe")
            browsingNovelStarted = false
            taskButtons = emptyList()
            currentTaskIndex = 0
            moveTo(AutomationState.BROWSING_TASK)
            handler.postDelayed({ runBrowsingTask(swipeCount = 1) }, INTERVAL_CLICK_MS)
            return
        }

        if (service.isOnFarmPage()) {
            // build538 修复（日志 debug_test_20260719_092915.log 暴露的问题）：
            // 历史问题：isOnFarmPage() 对 H5 商品详情页判断为 true（XRiverActivity 是农场
            // H5 容器 Activity，包名是支付宝，且 hasFarmContentLoaded=true 因为商品页也有
            // 大量文本），直接进入 COLLECTING_DIRECT 死循环：
            //   NAVIGATING → COLLECTING_DIRECT(空) → OPENING_TASK_LIST → NAVIGATING → ...
            // 日志证据：
            //   09:27:56.251 isProductDetailPage: YES (hasAddToCart=true, hasBuyNow=true)
            //   09:27:56.252 isOnAbnormalPage: YES, product detail page detected by content
            //   09:27:56.350 [openTaskList-start] snapshot: ... abnormal=true
            //   sample=[1/6, 距结束还有14时32分, ¥, 100, 抵扣后约, ¥, 52.9, 全网热销100万+,
            //         官方立减40.1元]
            //   这是商品详情页（"1/6"图片切换 + "距结束还有"倒计时 + "加入购物车"+"立即购买"），
            //   非农场页。
            //
            // 修复：进入 COLLECTING_DIRECT 前先检查 isOnAbnormalPage，若是异常页则按返回退出，
            // 不进入死循环。原 L671 的 isOnAbnormalPage 分支永远到不了，因为 isOnFarmPage 先命中。
            if (service.isOnAbnormalPage()) {
                Log.w(TAG, "navigate: on farm app but abnormal page (product detail/trading), pressing back to exit")
                debugLog("navigate: abnormal page in farm app, pressing back (isOnFarmPage=true but isOnAbnormalPage=true)")
                service.pressBack()
                handler.postDelayed({
                    if (state == AutomationState.NAVIGATING) runNavigating(attempt + 1)
                }, INTERVAL_PAGE_LOAD_MS)
                return
            }
            Log.i(TAG, "navigate: on farm page, collecting direct fertilizer first")
            debugLog("navigate: on farm page, platform=${service.currentPlatform}")
            collectedCount = 0
            currentTaskIndex = 0
            noProgressRounds = 0
            // H5 页面可能仍在加载中（WebView Activity 已显示但内容未渲染），
            // 检查页面是否有可交互内容，没有则等待重试（最多等10次，每次5秒）
            // build756 修复（debug_test_20260829_205529.log, build754, 20:50:56-20:51:49 共54s）：
            //   深链重开农场后页面常处于"lite 渲染态"：首屏横幅+签到组件已渲染
            //   （文字数 12→25 稳定，低于阈值 30），折叠下方内容（任务列表/果树区）
            //   懒渲染未触发，hasFarmContentLoaded 连续 10 次=false 干等 54s 后才
            //   强制进入 COLLECTING_DIRECT——实际页面功能完全正常（签到/集肥料/
            //   看广告领奖均可点，签到点击成功领取）。20:55:10-20:55:26 同样 lite 态
            //   （29 texts）等待中被用户手动停止。
            //   修复：集肥料任务列表入口存在即视为页面可用（核心交互元素已渲染），
            //   不再干等文字数阈值；无入口时仍按原逻辑等待（真加载中）。
            val root = service.getRootInFarmApp()
            val hasContent = root != null && service.hasFarmContentLoaded(root)
            val hasCoreEntry = service.findCollectFertilizerButton() != null
            debugLog("navigate: hasFarmContentLoaded=$hasContent, hasCoreEntry=$hasCoreEntry, attempt=$attempt")
            if (!hasContent && !hasCoreEntry && attempt < 10) {
                Log.i(TAG, "navigate: farm H5 page still loading, waiting...")
                handler.postDelayed({
                    if (state == AutomationState.NAVIGATING) runNavigating(attempt + 1)
                }, INTERVAL_PAGE_LOAD_MS)
                return
            }
            // build768-1 隐患3: 农场页已加载，检查并关闭 UC 多余标签页（深链重开历史累积，
            // 多窗口计数 5→6→7...；关闭全部后 isOnFarmPage=false → 深链重开恢复，农场 H5 无状态）
            // build773 修复（debug_test_20260905_073855.log, build772, 07:36:25-07:36:27）：
            //   原实现点击"多窗口"后立即进 COLLECTING_DIRECT，与清理流程竞态
            //   （清理 1.2s 后找"关闭全部"时 collectDirect 已在操作农场页）。
            //   改为回调式：等清理结束（最坏 ~5s）再走下一步；
            //   关闭全部会连带关掉农场页 → 不在农场页则回 NAVIGATING 深链重开。
            service.closeUcExtraTabsIfNeeded(onComplete = {
                if (state != AutomationState.NAVIGATING) {
                    debugLog("navigate: tab cleanup done but state=$state, skip transition")
                } else if (service.isOnFarmPage()) {
                    moveTo(AutomationState.COLLECTING_DIRECT)
                    handler.postDelayed({ runCollectingDirect(attempt = 0) }, INTERVAL_CLICK_MS)
                } else {
                    debugLog("navigate: tabs cleaned, farm page closed, re-navigating via deep link")
                    handler.postDelayed({ runNavigating(attempt = 0) }, INTERVAL_PAGE_LOAD_MS)
                }
            })
            return
        }

        // 签到日历弹窗检测（进入农场页时自动弹出，遮挡农场页内容导致 isOnFarmPage=false）
        // 实测日志（debug_test_20260715_051932.log）：
        // - 进入农场页时弹出签到日历（"第7天"/"已领取"/"今天"）
        // - 自动签到后显示"签到成功！每天来芭芭农场..."
        // - isOnFarmPage 返回 false（签到日历遮挡了农场核心元素）
        // - 若不关闭签到弹窗，runNavigating 会误判为"不在农场页"反复调用 navigateToFarm
        val signInScene = service.identifyCurrentScene()
        if (signInScene == FarmAccessibilityService.PageScene.SIGN_IN) {
            Log.i(TAG, "navigate: sign-in calendar popup detected, closing it")
            debugLog("navigate: sign-in popup detected (scene=SIGN_IN), attempting to close")
            // 优先点击签到按钮（未签到状态可能有"立即签到"按钮）
            val claimBtn = service.findClaimRewardButton()
            if (claimBtn != null) {
                debugLog("navigate: clicking sign-in button (text='${claimBtn.text}')")
                service.performClickSafe(claimBtn)
            } else {
                // 已签到状态（签到成功提示），关闭弹窗：
                // 优先找"关闭做任务集肥料弹窗"/"知道了"/"确定"按钮
                val closeBtn = service.findAdCloseButton()
                if (closeBtn != null) {
                    debugLog("navigate: closing sign-in popup via close button")
                    service.performClickSafe(closeBtn)
                } else {
                    debugLog("navigate: no close button for sign-in popup, pressing back")
                    service.pressBack()
                }
            }
            // 等待弹窗关闭后重新检测农场页
            handler.postDelayed({
                if (state == AutomationState.NAVIGATING) runNavigating(attempt + 1)
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // build595: 系统级权限弹窗（UC 推送权限授权等，最高优先级前置）
        // 必须在 GENERIC_POPUP 之前：权限弹窗会遮住农场页,isOnFarmPage 返回 false,
        // identifyCurrentScene 会返回 SYSTEM_PERMISSION,但需要 navigate 主动关闭
        if (signInScene == FarmAccessibilityService.PageScene.SYSTEM_PERMISSION) {
            Log.i(TAG, "navigate: system permission popup detected, closing it (deny button)")
            debugLog("navigate: system permission popup detected, attempting to close")
            val denyBtn = service.findSystemPermissionDenyButton()
            if (denyBtn != null) {
                debugLog("navigate: clicking deny button on permission popup (text='${denyBtn.text}')")
                service.performClickSafe(denyBtn)
            } else {
                debugLog("navigate: no deny button found, pressing back to close popup")
                service.pressBack()
            }
            handler.postDelayed({
                if (state == AutomationState.NAVIGATING) runNavigating(attempt + 1)
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 通用弹窗检测（无肥料提示的弹窗，如分享好友/开通会员/评价/活动等）
        // 用户需求：弹框窗口如何没有肥料提示，需要关闭弹窗
        // 策略：主动关闭弹窗，避免 bot 卡在 UNKNOWN 场景反复调用 navigateToFarm
        if (signInScene == FarmAccessibilityService.PageScene.GENERIC_POPUP) {
            // build585 修复（debug_test_20260721_171301.log, build584, UC 平台 line 55-112）：
            // 历史问题：UC "看一本喜欢的小说"任务页有右上角关闭按钮,isGenericPopup 误判为通用弹窗,
            // navigate 反复点击关闭按钮（line 56 [912,151][1043,268]）,但关闭后页面仍刷新成小说页,
            // 形成"检测小说页 → 误判弹窗 → 点关闭 → 页面刷新 → 再检测小说页"死循环 3 分钟。
            // 修复：generic popup 分支前先检测 isNovelReadPage,若是小说页直接进 BROWSING_TASK
            // （点击"开始阅读"→ 点击一部小说 → 停留15秒上下滑动）。
            if (service.isNovelReadPage()) {
                Log.i(TAG, "navigate: novel read page detected (看一本喜欢的小说), entering BROWSING_TASK")
                debugLog("navigate: novel read page (开始阅读+得肥料), entering BROWSING_TASK to click 开始阅读 + swipe")
                browsingNovelStarted = false  // 复位,让 runBrowsingTask 重新走"点开始阅读"流程
                taskButtons = emptyList()  // 小说页没有 taskButton,runBrowsingTask swipeCount=0 会跳过点击 taskButton 步骤
                currentTaskIndex = 0
                moveTo(AutomationState.BROWSING_TASK)
                // 直接从 swipeCount=1 开始（跳过 runBrowsingTask 的"点击去完成按钮"步骤,
                // 因为已经在小说任务页了,下一步是点"开始阅读"）
                handler.postDelayed({ runBrowsingTask(swipeCount = 1) }, INTERVAL_CLICK_MS)
                return
            }
            // build590 修复：UC "开始观看得肥料"短剧任务页同样可能被 isGenericPopup 误判为弹窗
            // （短剧页可能有右上角关闭按钮,且 collectAllText 可能拿不到"得肥料"文本导致
            // isGenericPopup 的 fertilizerKeywords 检查失效）。
            // 修复：generic popup 分支前先检测 isShortDramaPage,若是短剧页直接进 BROWSING_TASK
            // （点击"开始观看" → 等待15秒 → pressBack 退出回主页）。
            if (service.isShortDramaPage()) {
                Log.i(TAG, "navigate: short drama page detected (开始观看得肥料), entering BROWSING_TASK")
                debugLog("navigate: short drama page (开始观看+得肥料), entering BROWSING_TASK to click 开始观看 + wait 15s")
                browsingShortDramaStarted = false  // 复位,让 runBrowsingTask 重新走"点开始观看"流程
                taskButtons = emptyList()  // 短剧页没有 taskButton,runBrowsingTask swipeCount=0 会跳过点击 taskButton 步骤
                currentTaskIndex = 0
                moveTo(AutomationState.BROWSING_TASK)
                // 直接从 swipeCount=1 开始（跳过 runBrowsingTask 的"点击去完成按钮"步骤,
                // 因为已经在短剧任务页了,下一步是点"开始观看"）
                handler.postDelayed({ runBrowsingTask(swipeCount = 1) }, INTERVAL_CLICK_MS)
                return
            }
            Log.i(TAG, "navigate: generic popup detected (no fertilizer hint), closing it")
            debugLog("navigate: generic popup (no fertilizer), attempting to close")
            // build672 修复（debug_test_20260801_082040.log, build671）：
            // 手机锁屏时 activeRootPkg='com.android.systemui',navigate 误判为 generic popup,
            // 每 5 秒 pressBack 循环（锁屏状态 pressBack 无效）,卡死 2 分 44 秒。
            // build775 修复（debug_test_20260905_111247.log, build773, 10:53:52-11:02:16）：
            //   build672 用 getCurrentWindowPackage() 判 systemui,但其 windows 列表兜底
            //   会返回后台存活的 UC 包名 → 锁屏 8.5 分钟里检查从未命中,锁屏被当 generic popup
            //   每 5s pressBack 死循环 101 轮(attempt 到 101),直到用户手动上滑解锁。
            //   修复:改用 isLockScreenShowing()(KeyguardManager+活动窗口直查,不走 windows
            //   兜底),且不再干等——点亮屏幕后主动上滑解锁(无密码滑动锁屏直接解开;
            //   连续 5 次解不开说明有密码锁屏,退回 15s 等待用户)。
            if (service.isLockScreenShowing()) {
                lockUnlockAttempts++
                Log.w(TAG, "navigate: lock screen detected (unlock attempt=$lockUnlockAttempts), wake + swipe up to unlock")
                debugLog("navigate: 锁屏/熄屏检测命中(isLockScreenShowing),点亮+上滑解锁 attempt=$lockUnlockAttempts(不再按back)")
                service.ensureScreenOn()
                if (lockUnlockAttempts <= 5) {
                    service.swipeUpToUnlock()
                    handler.postDelayed({
                        if (state == AutomationState.NAVIGATING) runNavigating(attempt + 1)
                    }, 3000L)
                } else {
                    // 有密码锁屏,手势解不开,延长等待等用户解锁
                    handler.postDelayed({
                        if (state == AutomationState.NAVIGATING) runNavigating(attempt + 1)
                    }, 15000L)
                }
                return
            }
            lockUnlockAttempts = 0
            val closeBtn = service.findAdCloseButton()
            if (closeBtn != null) {
                debugLog("navigate: clicking close button on generic popup (text='${closeBtn.text}')")
                service.performClickSafe(closeBtn)
            } else {
                debugLog("navigate: no close button found for generic popup, pressing back")
                service.pressBack()
            }
            handler.postDelayed({
                if (state == AutomationState.NAVIGATING) runNavigating(attempt + 1)
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        if (attempt >= 10) {
            Log.w(TAG, "navigate: failed after $attempt attempts, waiting and retrying")
            handler.postDelayed({
                if (state == AutomationState.NAVIGATING) runNavigating(0)
            }, INTERVAL_WAIT_MS)
            return
        }

        // 不在农场页，尝试导航
        if (service.isNavigatingToFarm) {
            // 正在自动导航到农场页，跳过 controller 的自动操作避免干扰
            Log.d(TAG, "navigate: navigating to farm in progress, skip")
            handler.postDelayed({
                if (state == AutomationState.NAVIGATING) runNavigating(attempt)
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }
        if (service.isAdPlaying() || service.isAdActivity()) {
            // build559 修复（debug_test_20260719_153945.log, build558-44cd648）：
            // 历史问题：UC 平台"集肥料"点击后弹激励视频广告(穿山甲/汇川),广告期间 runNavigating
            // 检测到 adActivity=true → pressBack 想关闭广告。但 UC 配置明确说广告需完整观看
            // (adDefaultMinDurationMs=30s, supportsFasterReward=true),pressBack 对激励视频无效,
            // 反而可能干扰广告流程或误关闭导致拿不到肥料奖励。
            //
            // 修复：UC 平台(激励视频广告)不 pressBack,只等待广告自然结束。
            // 其他平台(支付宝/淘宝)保留原 pressBack 行为(可能是可关闭的横幅/H5 广告)。
            //
            // build580 修复（debug_test_20260721_152904.log, build580, UC 平台 line 230-515）：
            // 历史问题：腾讯优量汇 PortraitADActivity 广告结束后,页面显示"恭喜获取奖励"+关闭按钮,
            // 但仍在广告 Activity（adActivity=true）。原逻辑只等待,不主动关闭,导致 navigate
            // 反复检测广告 → navigateToFarm → stepTab 找不到"芭芭农场" → 卡 6 分钟。
            // 修复：检测到广告 Activity 时,先检查 isAdEndedMultiSignal。若广告已结束（"恭喜获取奖励"等
            // 文字出现）,进入 CLOSING_AD 主动关闭广告,而不是无限等待。
            if (service.isAdEndedMultiSignal(prevAdHadCountdown)) {
                Log.i(TAG, "navigate: ad ended while in ad activity (恭喜获取奖励 etc), entering CLOSING_AD")
                debugLog("navigate: ad ended detected (multi-signal), entering CLOSING_AD to close ad page")
                service.setAdMode(true)
                moveTo(AutomationState.CLOSING_AD)
                handler.postDelayed({ runClosingAd(strategy = 0) }, INTERVAL_CLICK_MS)
                return
            }
            if (service.currentPlatform == Platform.UC) {
                // build690 修复（debug_test_20260802_111340.log, build689, 10:52:35-11:13:33）：
                //   10:52:30 watchAd 检测到 countdown stuck at 10s,pressBack 退出 → NAVIGATING
                //   10:52:38 navigate: UC ad (KsRewardVideoActivity), waiting instead of pressBack
                //   10:52:38-11:13:33 一直 waiting instead of pressBack ← 卡死 20 分钟!
                //   根因：快手广告 KsRewardVideoActivity 倒计时卡在"10秒"(静态文本),
                //         watchAd stall exit 后进入 NAVIGATING,但仍在广告 Activity,
                //         navigate 选择等待而不是 pressBack,广告已卡住,等待永远无法结束。
                //   修复：UC ad waiting 分支增加 attempt >= 6 超时(约30秒),
                //         超过后强制 reopenFarmByDeepLink 退出卡住的广告 Activity。
                if (attempt >= 6) {
                    Log.w(TAG, "navigate: UC ad stuck after $attempt attempts, forcing reopenFarmByDeepLink to exit")
                    debugLog("navigate: UC ad (act=${service.getCurrentActivityName()}) stuck after $attempt attempts, forcing reopenFarmByDeepLink")
                    service.reopenFarmByDeepLink()
                } else {
                    Log.i(TAG, "navigate: UC reward video ad playing, waiting for it to finish (not pressing back)")
                    debugLog("navigate: UC ad (act=${service.getCurrentActivityName()}), waiting instead of pressBack (attempt=$attempt)")
                }
            } else {
                Log.w(TAG, "navigate: in ad, trying to close")
                service.pressBack()
            }
        } else if (service.isSearchRecommendPage()) {
            // 搜索推荐页 — 芭芭农场H5页没有加载出来
            // 关闭搜索页，下次导航改用"我的淘宝"路径（更可靠）
            Log.i(TAG, "navigate: search recommend page, closing (farm H5 didn't load)")
            debugLog("navigate: closing search page, will try 我的淘宝 path next")
            service.pressBack()
        } else if (service.isOnAbnormalPage()) {
            // 异常页面（支付宝收银台、商品详情页等），按返回退出
            Log.w(TAG, "navigate: on abnormal page, pressing back to exit")
            debugLog("navigate: abnormal page detected, pressing back")
            service.pressBack()
        } else if (!service.isFarmAppInForeground()) {
            // 农场 App 不在前台（如刚启动自动化时 bbncbot.MainActivity 还在前台，
            // 或农场 App 被切到后台/被系统回收）。
            // 历史问题：原逻辑只打印"waiting for relaunch"等待用户手动切到农场 App，
            // 但用户期望点"开始自动化"后 bot 应自己拉起农场 App。
            // 修复：attempt==0 时主动调用 reopenFarmByDeepLink 拉起农场 App；
            //       attempt>=1 时若仍未切到农场 App（可能 deep link 失败或 App 启动慢），
            //       再次调用 reopenFarmByDeepLink 重新拉起（避免卡死在 "waiting for relaunch"）。
            //       间隔由 INTERVAL_PAGE_LOAD_MS (5s) 控制，避免短时间内重复 kill+launch。
            if (attempt == 0 || attempt % 3 == 0) {
                // build767 修复（用户需求："底部手指按住，往上滑动，切换到uc浏览器，不要触发浏览器刷新"）：
                //   农场App（UC）还活着但不在前台时（如 bbncbot 在前台启动自动化/互动广告拉起
                //   第三方App后），深链拉起会让 UC 重新加载农场页（触发浏览器刷新）且每次
                //   新开一个标签页（"多窗口"计数累积主因）。优先用最近任务手势切换
                //   （底部按住上滑→最近任务→点UC卡片）：恢复原任务栈，页面原样不刷新。
                //   失败（手势导航未开/无卡片/10s防重）返回 false，自动落回下方深链原路径。
                if (service.tryGestureSwitchToFarmApp(service.currentPlatform)) {
                    debugLog("navigate: gesture switch to farm app initiated (no reload, no new tab), retry verifying in 5s (attempt=$attempt)")
                    handler.postDelayed({
                        if (state == AutomationState.NAVIGATING) runNavigating(attempt)
                    }, INTERVAL_PAGE_LOAD_MS)
                    return
                }
                // attempt==0：首次拉起；attempt % 3 == 0：每 3 轮（15s）重试一次拉起
                Log.i(TAG, "navigate: farm app not in foreground (platform=${service.currentPlatform}), actively relaunching (attempt=$attempt)")
                debugLog("navigate: farm app not in foreground (platform=${service.currentPlatform}, attempt=$attempt), calling reopenFarmByDeepLink")
                // build721 修复（debug_test_20260811_080219.log, build720, 08:01:57-08:02:17）：
                //   08:01:25 点击'看广告领奖' → 跳转淘宝 TMSActivity
                //   08:01:35 collectDirect build694 修复✓: forceKillApp(taobao)+launchPlatformApp(killCurrentFirst=false) 回 UC 农场
                //   08:01:55 AI 视觉超时回退 task list 时又检测到淘宝 TMSActivity → NAVIGATING
                //   08:01:57 farm app not in foreground → reopenFarmByDeepLink() 默认 killCurrentFirst=true
                //   08:01:57.741 killing com.ucmobile.lite ← 杀 UC!
                //   08:01:57.799 opened deeplink for UC
                //   08:02:03 activeRootPkg='com.hihonor.android.launcher' ← UC 没起来,卡在桌面!
                //   08:02:04-08:02:17 navigate stepTab 找'芭芭农场'全节点 too large(桌面节点),重试5次失败 → STOPPING
                // 根因：UC 被杀后 Honor 后台启动限制导致 deep link 重启失败,卡死在桌面。
                //   与 build692 修复 collectDirect 时发现的问题一致（"UC 被杀后重启可能因 Honor
                //   后台限制失败,卡死在桌面"）,但 navigate 此处仍用默认 killCurrentFirst=true。
                // 修复：传 killCurrentFirst=false,不杀 UC,直接用 deep link 拉起 UC 农场页。
                //   UC 进程未被 kill,deep link 可正常拉起到前台,避免 Honor 限制。
                //   即使 UC 进程已被系统回收(非 kill),deep link 冷启动也不受 Honor 限制影响。
                service.reopenFarmByDeepLink(killCurrentFirst = false)
                // 延迟调用 navigateToFarm：等 App 启动后通过 AccessibilityEvent 识别平台，
                // 再调用 navigateToFarm 导航到芭芭农场 H5 页（reopenFarmByDeepLink 只启动 App 主页，
                // 不会自动进入农场页，必须靠 navigateToFarm 完成后续导航）。
                handler.postDelayed({
                    if (state == AutomationState.NAVIGATING && !service.isFarmAppInForeground()) {
                        debugLog("navigate: still not in farm app after relaunch, calling navigateToFarm to push navigation")
                        service.navigateToFarm()
                    }
                }, INTERVAL_PAGE_LOAD_MS)
            } else {
                Log.w(TAG, "navigate: farm app still not in foreground after relaunch (platform=${service.currentPlatform}, attempt=$attempt), waiting")
                debugLog("navigate: farm app still not in foreground after relaunch (attempt=$attempt), waiting for next retry")
            }
        } else {
            // 在农场 App 内但不在农场页（如淘宝主页），主动导航到芭芭农场
            // build558 修复（debug_test_20260719_152545.log, build557-1a9b06f）：
            // 历史问题：UC 平台集肥料按钮点击后,UC 浏览器内的 H5 跳转拉起美团/中国移动 10086
            // 等第三方 App,这些 App 以 overlay 覆盖在 UC 上。isFarmAppInForeground 通过
            // activity 兜底(com.uc.browser.innerucmobile)判定为"在农场 App",进入此 else 分支。
            // 原 else 直接调用 navigateToFarm() → stepClickFarmTab,但当前活动 root 是美团/10086,
            // 找不到"芭芭农场"tab → fallback 到 stepClickFarmTabByGesture(淘宝专用,UC 无效)
            // → 反复重试 90 秒后超时停止,任务失败。
            //
            // 修复：进入 navigateToFarm 前先检测第三方 App overlay,识别到则 forceKillApp
            // 结束该 App,让 UC 浏览器重新成为活动窗口,下一轮 runNavigating 正常导航。
            val overlayPkg = service.getThirdPartyOverlayPkg()
            if (overlayPkg != null) {
                // build697 修复（debug_test_20260803_072453.log, build696, 07:24:24-07:24:40）：
                //   淘宝直播间(TaoLiveVideoActivity)是前台 Activity,forceKillApp 的
                //   killBackgroundProcesses 对前台 App 无效,反复 forceKillApp 失败卡死。
                //   修复:forceKillApp 失败后(attempt >= 1),改用 reopenFarmByDeepLink 强制重启农场 App,
                //         通过 deep link 把农场 App 拉到前台,覆盖淘宝直播间。
                // build708 修复（debug_test_20260806_071848.log, build705, 07:18:13-07:18:45）：
                //   UC→淘宝跨 App 浏览任务完成后,exitBrowsePage 点击淘宝"返回"按钮没生效(H5 页面),
                //   一直停留在淘宝。navigate attempt=0 时 forceKillApp(taobao) 对前台 App 无效,
                //   等 attempt=1 才用 reopenFarmByDeepLink,但此时 UC 进程已被系统回收,
                //   deep link 拉起 UC 失败,卡死在 launcher,用户手动停止。
                //   修复:第三方 overlay 场景直接用 reopenFarmByDeepLink(killCurrentFirst=true),
                //         先 HOME+kill 第三方 App,再用 deep link 拉起农场 App 到前台。
                //         不再先 forceKillApp 再等下一轮,避免 UC 进程被回收后无法拉起。
                Log.w(TAG, "navigate: third-party overlay pkg=$overlayPkg detected, forcing reopenFarmByDeepLink to restore farm app")
                debugLog("navigate: third-party overlay pkg=$overlayPkg detected, reopenFarmByDeepLink(killCurrentFirst=true)")
                service.reopenFarmByDeepLink(killCurrentFirst = true)
                handler.postDelayed({
                    if (state == AutomationState.NAVIGATING) runNavigating(attempt + 1)
                }, INTERVAL_PAGE_LOAD_MS)
                return
            }
            // build749 修复（debug_test_20260829_084927.log, build747, 08:47:13-08:49:24 死循环2分11秒）：
            //   UC 极速版更新后新增首页容器 activity=h02.c（不在 UC farmPageActivityKeywords
            //   innerucmobile/mainactivity/pz1 中），openFarmInUcBrowser 普通启动 UC 停在 h02.c 首页。
            //   stepTab 每轮都能找到首页"芭芭农场，免费领水果，助果农增收"入口卡片并 gesture 点击，
            //   但新版首页点击该卡片无效（12 次均未进入农场页），本分支无兜底 → 死循环到用户手动停止。
            //   修复：attempt>=6（约30s）仍不在农场页时，放弃 stepTab 点击入口，
            //   改用 reopenFarmByDeepLink(killCurrentFirst=false) 深链直达农场页
            //   （build746 日志证实深链打开后 activity=InnerUCMobile，isOnFarmPage 正常判定）。
            //   killCurrentFirst=false：UC 在前台未被杀，深链直接切换；
            //   build721 教训：kill 后 Honor 后台启动限制可能拉不起 UC。
            //   深链后 attempt 重置为 0，给 H5 加载留 6 轮（~30s）窗口，加载慢时才会再次深链。
            if (attempt >= 6) {
                Log.w(TAG, "navigate: still not on farm page after $attempt attempts (act=${service.getCurrentActivityName()}), deep link fallback")
                debugLog("navigate: stepTab ineffective after $attempt attempts (act=${service.getCurrentActivityName()}), reopenFarmByDeepLink(killCurrentFirst=false)")
                service.reopenFarmByDeepLink(killCurrentFirst = false)
                handler.postDelayed({
                    if (state == AutomationState.NAVIGATING) runNavigating(0)
                }, INTERVAL_PAGE_LOAD_MS)
                return
            }
            Log.i(TAG, "navigate: in farm app but not farm page (platform=${service.currentPlatform}), calling navigateToFarm")
            debugLog("navigate: calling navigateToFarm, platform=${service.currentPlatform}")
            service.navigateToFarm()
        }

        handler.postDelayed({
            if (state == AutomationState.NAVIGATING) runNavigating(attempt + 1)
        }, INTERVAL_PAGE_LOAD_MS)
    }

    // ============== 阶段2: 收集直接可领取的肥料 ==============

    /**
     * 直接收集阶段：在农场主页上点击可直接领取的肥料
     * - 如"兔兔挖肥料，50肥料，可领取"、"4100，肥料，明日7点可领"
     * - 点击后等待弹窗，尝试关闭，然后检查是否还有更多可领取项
     * - 完成后进入打开任务列表阶段
     */
    private fun runCollectingDirect(attempt: Int) {
        if (state != AutomationState.COLLECTING_DIRECT) return
        val service = getService() ?: run { stop(); return }

        // build753: 屏幕熄灭(AOD)时无障碍读不到农场页面按钮(会误转 AI vision 干等),
        // 先确保屏幕点亮再收集(日志现象: collectDirect-start snapshot pkg=com.hihonor.aod)
        service.ensureScreenOn()

        if (attempt == 0) {
            logPageSnapshot(service, "collectDirect-start")
            directButtonSignInClicked = false  // build756: 新一轮 collectDirect 重置签到点击标记
        }

        if (attempt >= 5) {
            Log.i(TAG, "collectDirect: done after $attempt attempts, opening task list")
            moveTo(AutomationState.OPENING_TASK_LIST)
            handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
            return
        }

        // 查找所有直接可领取的肥料按钮
        val buttons = service.findDirectCollectButtons()
        debugLog("collectDirect: found ${buttons.size} direct buttons, attempt=$attempt")

        // build666 优化（用户需求"uc芭芭农场主页的'点击领取'按钮应该优先点击"）：
        // 当 findDirectCollectButtons 返回 0 时，先检测页面是否有"已领取"/"明天领肥料"等已领取标识。
        // 若有，说明当天"点击领取"奖励已领（按钮已变成"已领取"），无需再等 AI 视觉识别（15秒超时），
        // 直接走 OPENING_TASK_LIST，避免浪费时间。
        // 日志证据 debug_test_20260730_074843.log (build664):
        //   07:47:19.098 [collectDirect-start] claim-text-nodes: text='已领取' bounds=[894,933][1123,1031]
        //   07:47:19.118 collectDirect: found 0 direct buttons, attempt=0
        //   → 当天已领过，"点击领取"按钮已变"已领取"，AI 视觉识别必然失败，15 秒超时无意义
        if (buttons.isEmpty() && attempt == 0 && service.hasDailyRewardClaimedIndicator() && aiVisionDirectClickAttempted) {
            debugLog("collectDirect: daily reward already claimed (已领取/明天领肥料 detected), AI vision already tried, skip to task list")
            Log.i(TAG, "collectDirect: daily reward already claimed, AI vision already tried, opening task list")
            moveTo(AutomationState.OPENING_TASK_LIST)
            handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
            return
        }

        // build673 修复（debug_test_20260801_092504.log, build671）：
        // UC 芭芭农场活动期间（如"8.4内完成3天即领"）页面改版,没有"点击领取"按钮和"已领取"标识,
        // 任务直接显示在主页（"施肥3次"/"完整观看广告1次"/"今日任务余3"）。
        // 这种页面 findDirectCollectButtons 返回 0,hasDailyRewardClaimedIndicator 返回 false,
        // 导致触发 AI 视觉找"点击领取"15 秒超时（活动页面根本没这个按钮）。
        // 修复：检测到活动页面特征时,跳过 AI 视觉直接进入 OPENING_TASK_LIST 处理任务。
        // build680 修复（debug_test_20260801_151301.log, build679, 15:12:03）：
        //   日志显示页面同时有"已领取"+"明天领肥料"+"8.4内完成3天即领"(活动标题),
        //   isActivityFarmPage() 返回 true 直接跳过 AI 视觉,导致"点击跳转拿奖励"未被识别。
        //   修复：与 hasDailyRewardClaimedIndicator 同样,增加 aiVisionDirectClickAttempted 检查,
        //   尚未尝试 AI 视觉时不跳过,让 AI 视觉有机会识别"点击跳转拿奖励"按钮。
        if (buttons.isEmpty() && attempt == 0 && service.isActivityFarmPage() && aiVisionDirectClickAttempted) {
            debugLog("collectDirect: activity farm page detected (今日任务余/完成即领), AI vision already tried, skip to task list")
            Log.i(TAG, "collectDirect: activity farm page detected, AI vision already tried, opening task list")
            moveTo(AutomationState.OPENING_TASK_LIST)
            handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
            return
        }

        // build596 诊断（用户反馈"uc芭芭农场主页,'点击领取'没有执行点击操作"）：
        // 当 findDirectCollectButtons 返回 0 时,dump 主页全部 text/desc 节点（含 bounds+clickable）,
        // 确认"点击领取"是否在无障碍树里。H5/Canvas 图像按钮不暴露 text 节点时,
        // 日志会显示无"点击领取",需用 AI 视觉识别坐标。
        if (buttons.isEmpty() && attempt == 0) {
            try {
                val root = service.getRootInFarmApp()
                if (root != null) {
                    val allNodes = mutableListOf<String>()
                    service.collectAllTextNodesForDiag(root, allNodes, maxCount = 200)
                    debugLog("collectDirect: dump all text/desc nodes (count=${allNodes.size}) for '点击领取' diagnosis:")
                    for (line in allNodes) {
                        debugLog("collectDirect:   $line")
                    }
                }
            } catch (e: Exception) {
                debugLog("collectDirect: dump all text/desc nodes error: ${e.message}")
            }
        }

        // build596 修复（用户反馈"uc芭芭农场主页,'点击领取'没有执行点击操作"）：
        // UC 主页"点击领取"是 H5/Canvas 图像按钮（文字+彩色背景）,无障碍树抓不到 text 节点,
        // findDirectCollectButtons 返回 0。需用 AI 视觉识别按钮坐标并点击。
        // 策略：当 buttons 为空 且 未尝试过 AI 视觉识别 时,
        // 截图交给 GLM-4.6V-Flash 识别按钮坐标,用 dispatchGesture 点击。
        // 防死循环：每轮只尝试一次 AI 视觉识别（aiVisionDirectClickAttempted 标记）。
        // build666 优化（用户需求"点击领取"应该优先点击）：原 attempt >= 1 先空转一次再触发 AI 视觉，
        // 改为 attempt >= 0 在首次就触发 AI 视觉，让"点击领取"更快被识别点击。
        // build678 修复（用户需求"希望点击点击跳转拿奖励"）：
        // 当 hasDailyRewardClaimedIndicator=true 时,"点击领取"已变"已领取",
        // 但"点击跳转拿奖励"按钮可能仍然可点击（H5 图像按钮,不在 accessibility tree）。
        // AI 视觉目标根据每日奖励状态切换：已领取→找"点击跳转拿奖励",未领取→找"点击领取"。
        if (buttons.isEmpty() && !aiVisionDirectClickAttempted && attempt >= 0) {
            // build756 修复（debug_test_20260829_205529.log, build754, 20:52:00-20:52:15 共15s）：
            //   点击'签到'direct按钮成功变'已领取'后（每日直达领取完成），lite 渲染态无
            //   '看广告领奖'入口 → 0 direct 按钮；dailyClaimed=false（'已领取'+'明天领'
            //   组合标识在 lite 态缺'明天领肥料'）→ AI 视觉找'点击领取'必然 15s 超时。
            //   修复：本轮刚点过签到且页面已显示'已领取'，跳过 AI 视觉直接进任务列表。
            if (directButtonSignInClicked && service.hasFarmAlreadyClaimedText()) {
                debugLog("collectDirect: 签到直达按钮已点击且页面显示'已领取',每日直达领取完成,跳过AI视觉直接进任务列表")
                Log.i(TAG, "collectDirect: sign-in just claimed (已领取 visible), skip AI vision, opening task list")
                moveTo(AutomationState.OPENING_TASK_LIST)
                handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                return
            }
            aiVisionDirectClickAttempted = true
            // build678: 根据每日奖励状态决定 AI 视觉目标
            val dailyClaimed = service.hasDailyRewardClaimedIndicator()
            val aiVisionTarget = if (dailyClaimed) "点击跳转拿奖励" else "点击领取"
            debugLog("collectDirect: no direct buttons found, trying AI vision to locate '$aiVisionTarget' button (dailyClaimed=$dailyClaimed)")
            Log.i(TAG, "collectDirect: trying AI vision to locate '$aiVisionTarget' button (H5/Canvas image button, dailyClaimed=$dailyClaimed)")
            val appContext = service.applicationContext
            // build667 优化（用户截图反馈"点击领取"按钮位置）：
            // UC 芭芭农场主页"点击领取"按钮位于"果树右下侧区域"（H5/Canvas 图像按钮）。
            // 把位置提示写入 sceneContext 让 GLM-4.6V-Flash 更精准定位，避免误识别
            // 页面其他装饰性"领取/可领取"文字（如签到肥料/任务卡片的装饰文字）。
            // build678: 当 dailyClaimed=true 时,目标改为"点击跳转拿奖励"按钮（跳转广告/活动页拿奖励）。
            val sceneContext = if (dailyClaimed) {
                "UC芭芭农场主页, 平台=${service.currentPlatform}, " +
                    "pkg=${service.getCurrentWindowPackage()}, act=${service.getCurrentActivityName()}, " +
                    "目标「点击跳转拿奖励」按钮位于页面中（图像按钮，点击后跳转广告/活动页停留拿奖励）"
            } else {
                "UC芭芭农场主页, 平台=${service.currentPlatform}, " +
                    "pkg=${service.getCurrentWindowPackage()}, act=${service.getCurrentActivityName()}, " +
                    "目标「点击领取」按钮位于果树右下侧区域（图像按钮，橙色/红色彩色背景）"
            }
            // build607 修复（debug_test_20260722_075045.log line 59-60）：
            // AI 视觉是异步子线程,主线程只 postDelayed 等结果。若 AI 视觉调用慢
            // （2 个 model × 3 次重试 × 45s readTimeout,最坏接近 5 分钟）,主线程
            // 会一直停在 COLLECTING_DIRECT 状态。日志显示用户 28 秒后手动停止。
            // 加 15 秒超时保护：超时后 fallback 到 OPENING_TASK_LIST,避免卡死。
            val aiVisionTimeoutMs = 15_000L
            val aiVisionCompleted = java.util.concurrent.atomic.AtomicBoolean(false)
            val aiVisionTimeoutRunnable = Runnable {
                if (aiVisionCompleted.getAndSet(true)) return@Runnable
                if (state != AutomationState.COLLECTING_DIRECT) return@Runnable
                debugLog("collectDirect: AI vision timed out after ${aiVisionTimeoutMs}ms, fallback to task list")
                Log.w(TAG, "collectDirect: AI vision timed out after ${aiVisionTimeoutMs}ms, fallback to OPENING_TASK_LIST")
                // 超时后改走任务列表,不再等 AI 视觉结果
                handler.post {
                    if (state == AutomationState.COLLECTING_DIRECT) {
                        state = AutomationState.OPENING_TASK_LIST
                        runOpeningTaskList(0)
                    }
                }
            }
            handler.postDelayed(aiVisionTimeoutRunnable, aiVisionTimeoutMs)
            Thread {
                val bitmap = service.takeScreenshotBitmap()
                if (bitmap == null) {
                    debugLog("collectDirect: AI vision skipped, screenshot not available")
                    handler.post {
                        // AI 视觉结束（截图失败）,取消超时
                        if (!aiVisionCompleted.getAndSet(true)) {
                            handler.removeCallbacks(aiVisionTimeoutRunnable)
                        }
                        if (state != AutomationState.COLLECTING_DIRECT) return@post
                        // 截图失败,继续 attempt+1 重试
                        handler.postDelayed({
                            if (state == AutomationState.COLLECTING_DIRECT) runCollectingDirect(attempt + 1)
                        }, INTERVAL_CLICK_MS)
                    }
                    return@Thread
                }
                try {
                    val result = AiVisionClient.findButtonLocationByVision(
                        appContext, bitmap, sceneContext, aiVisionTarget
                    )
                    bitmap.recycle()
                    handler.post {
                        // AI 视觉结束（成功或未找到）,取消超时
                        if (!aiVisionCompleted.getAndSet(true)) {
                            handler.removeCallbacks(aiVisionTimeoutRunnable)
                        }
                        if (state != AutomationState.COLLECTING_DIRECT) return@post
                        if (result == null) {
                            debugLog("collectDirect: AI vision did not find '$aiVisionTarget' button, continue to cross-platform/task-list")
                            Log.i(TAG, "collectDirect: AI vision did not find '$aiVisionTarget' button")
                            // AI 未找到,继续 attempt+1 重试（会走跨平台跳转/OPENING_TASK_LIST）
                            handler.postDelayed({
                                if (state == AutomationState.COLLECTING_DIRECT) runCollectingDirect(attempt + 1)
                            }, INTERVAL_CLICK_MS)
                            return@post
                        }
                        // 计算屏幕坐标
                        val metrics = service.resources.displayMetrics
                        val clickX = result.xRatio * metrics.widthPixels
                        val clickY = result.yRatio * metrics.heightPixels
                        debugLog("collectDirect: AI vision found '$aiVisionTarget' at ratio=(${result.xRatio}, ${result.yRatio}), " +
                            "screen=(${clickX}, ${clickY}), reason='${result.reason.take(80)}', clicking")
                        Log.i(TAG, "collectDirect: AI vision found '$aiVisionTarget' at (${clickX}, ${clickY}), clicking")
                        // 记录防死循环（用 AI 视觉点击的坐标作为 bounds 标记）
                        lastDirectClickedText = "$aiVisionTarget(AI)"
                        lastDirectClickedBounds = "(${result.xRatio},${result.yRatio})"
                        // 用 dispatchGesture 坐标点击
                        val clicked = service.dispatchGestureClick(clickX, clickY)
                        debugLog("collectDirect: AI vision click dispatched=$clicked at ($clickX, $clickY) for '$aiVisionTarget'")
                        // build678: "点击跳转拿奖励"专用流程（等 10 秒 + pressBack 返回主页）
                        // 与 L1356 的按钮点击流程一致：点击 → 等待 10 秒 → pressBack 返回 → 继续下一轮
                        if (dailyClaimed) {
                            debugLog("collectDirect: '点击跳转拿奖励' (AI) clicked, waiting 10000ms then pressBack to return")
                            handler.postDelayed({
                                if (state != AutomationState.COLLECTING_DIRECT) return@postDelayed
                                debugLog("collectDirect: 10s elapsed after AI '点击跳转拿奖励' click, pressing back to return to farm home")
                                service.pressBack()
                                handler.postDelayed({
                                    if (state == AutomationState.COLLECTING_DIRECT) runCollectingDirect(attempt + 1)
                                }, INTERVAL_PAGE_LOAD_MS)
                            }, 10000L)
                        } else {
                            // "点击领取"流程：点击后等待页面变化,继续 COLLECTING_DIRECT 下一轮
                            handler.postDelayed({
                                if (state == AutomationState.COLLECTING_DIRECT) runCollectingDirect(attempt + 1)
                            }, INTERVAL_PAGE_LOAD_MS)
                        }
                    }
                } catch (e: Exception) {
                    debugLog("collectDirect: AI vision exception: ${e.message}")
                    try { bitmap.recycle() } catch (_: Exception) {}
                    handler.post {
                        // AI 视觉结束（异常）,取消超时
                        if (!aiVisionCompleted.getAndSet(true)) {
                            handler.removeCallbacks(aiVisionTimeoutRunnable)
                        }
                        if (state != AutomationState.COLLECTING_DIRECT) return@post
                        handler.postDelayed({
                            if (state == AutomationState.COLLECTING_DIRECT) runCollectingDirect(attempt + 1)
                        }, INTERVAL_CLICK_MS)
                    }
                }
            }
            return
        }

        // build586: 跨平台跳转按钮检测（"去支付宝农场领肥料"等）
        // 用户需求（debug_test_20260721_173039.log, build585, UC 平台 line 25）：
        // UC 主页底部"去支付宝农场领肥料"按钮,点击跳转支付宝领跨平台奖励,需像 processTask 的
        // cross-platform 任务一样处理（跳转→领奖励→返回原平台）。
        // 防死循环：用 lastDirectClickedText 记录已点击的跳转按钮,避免重复触发跨平台切换。
        //
        // build595 保守化（debug_test_20260722_023550.log, build594 line 72-83）：
        // 历史问题：UC 农场主页底部"和淘宝,支付宝农场共种一棵树"区域含"去支付宝农场领肥料"
        // 横幅,即使有 direct 按钮可领也会被优先识别触发跳转,且任务列表还没打开就跳走了。
        // 当 COLLECTING_DIRECT 因权限弹窗/任务列表打不开回退到 NAVIGATING 重进后,
        // 又会触发跨平台跳转,导致 UC 任务流程完全无法执行。
        // 修复：只有在没有 direct 按钮可领 且 至少尝试过一次（attempt >= 1）时,才检查跨平台跳转。
        // 首次进入 COLLECTING_DIRECT (attempt=0) 优先找 direct 按钮,找不到则进 OPENING_TASK_LIST,
        // 避免主页横幅在第一轮就误触发跳转。
        if (buttons.isEmpty() && attempt >= 1) {
            val jumpBtn = service.findCrossPlatformJumpButton()
            if (jumpBtn != null) {
                val jumpText = jumpBtn.text?.toString().orEmpty()
                val jumpBoundsStr = android.graphics.Rect().also { jumpBtn.getBoundsInScreen(it) }.toShortString()
                // 检测目标平台
                val jumpTarget = service.detectCrossPlatformJumpTarget(jumpText)
                if (jumpTarget != null && jumpTarget != service.currentPlatform) {
                    // 防死循环：若与上次点击的跳转按钮相同,跳过（避免跨平台切换失败后重复触发）
                    if (jumpText == lastDirectClickedText && jumpBoundsStr == lastDirectClickedBounds) {
                        debugLog("collectDirect: cross-platform jump button '$jumpText' same as last clicked, skip (already tried)")
                    } else {
                        Log.i(TAG, "collectDirect: cross-platform jump button detected '$jumpText', switching to $jumpTarget (like processTask cross-platform)")
                        debugLog("collectDirect: cross-platform jump '$jumpText' → $jumpTarget, entering SWITCHING_PLATFORM")
                        // 记录本次点击,防死循环
                        lastDirectClickedText = jumpText
                        lastDirectClickedBounds = jumpBoundsStr
                        // 复用 processTask 的 cross-platform 流程
                        switchOriginalPlatform = service.currentPlatform
                        switchTargetPlatform = jumpTarget
                        switchStage = "LAUNCH_TARGET"
                        switchRetryCount = 0
                        // 点击跳转按钮（部分任务点击后自动跳转目标平台）
                        service.performClickSafe(jumpBtn)
                        moveTo(AutomationState.SWITCHING_PLATFORM)
                        handler.postDelayed({ runSwitchingPlatform() }, INTERVAL_PAGE_LOAD_MS)
                        return
                    }
                }
            }
        }

        if (buttons.isEmpty()) {
            // build642 修复（debug_test_20260726_153127.log）：
            // 历史问题: COLLECTING_DIRECT 在 attempt=0 时若页面未完全加载，
            // findDirectCollectButtons 返回 0，立即跳到 OPENING_TASK_LIST。
            // 但 TAOBAO 农场页加载较慢，"1500，肥料，点击领取" 按钮在进入农场页 4 秒后才出现，
            // COLLECTING_DIRECT 在 2 秒时就检查并放弃了，导致 1500 肥料奖励始终未领取。
            // 日志证据:
            //   15:25:43 state: NAVIGATING -> COLLECTING_DIRECT
            //   15:25:45 collectDirect: found 0 direct buttons, attempt=0  ← 页面未加载完
            //   15:25:47 [openTaskList-start] text='1500，肥料，点击领取'  ← 2 秒后按钮才出现
            // 修复: attempt=0 时若 buttons 为空，等待 INTERVAL_PAGE_LOAD_MS (3s) 后重试 (attempt=1)，
            // 给页面充分加载时间。attempt>=1 仍为空才走 AI 视觉/跨平台跳转/OPENING_TASK_LIST。
            if (attempt == 0) {
                debugLog("collectDirect: no direct buttons found at attempt=0, waiting ${INTERVAL_PAGE_LOAD_MS}ms for page to fully load before retry")
                Log.i(TAG, "collectDirect: no direct buttons at attempt=0, waiting for page load then retry")
                handler.postDelayed({
                    if (state == AutomationState.COLLECTING_DIRECT) runCollectingDirect(attempt + 1)
                }, INTERVAL_PAGE_LOAD_MS)
                return
            }
            // 历史说明（build538 → build542 撤销）：
            // 用户反馈"点击领取在领肥料上方"，build538 加了坐标兜底点击 (0.917, 0.657)。
            // 但日志（debug_test_20260719_113316.log, build541-801abe4）显示：
            //   - 主页 claim-text-nodes 只有 1 项：'还差3次领肥料'（锁定状态，已过滤）
            //   - 坐标兜底点击 (1100.4, 1670.751) 两次都没找到弹窗（claim button not found）
            // 用户反馈："点击领取每天只能点击一次"——已领过则主页根本不显示"点击领取"按钮
            // （会变成"明日7点可领"/"还差X次领肥"等锁定状态文本，被 !contains("明日")/"还差"过滤）。
            // 所以找不到 direct 按钮是正常的，直接进入 OPENING_TASK_LIST，不再坐标兜底空点。
            Log.i(TAG, "collectDirect: no direct collect buttons found, opening task list")
            moveTo(AutomationState.OPENING_TASK_LIST)
            handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
            return
        }

        // build581 防死循环：跳过与上次点击相同（text+bounds 一致）的按钮
        // 场景：buttons[0]='签到肥料' clickable=false,dispatchGesture 点击无效,
        // 下一轮 findDirectCollectButtons 仍返回 buttons[0]='签到肥料',避免重复点击。
        // build744 修复（debug_test_20260822_182904.log, build743, 18:27-18:29）:
        //   "看广告领奖"点击→安装类广告放弃(build741 forceKill+重开农场)→重进
        //   COLLECTING_DIRECT,按钮 text 相同但 bounds 抖动 4px([641,1152]→[641,1156]),
        //   text+bounds 双重比较被绕过→重复点击同一按钮,22秒一轮死循环4次。
        //   修复：改为 text-only 比较(同 text 即视为同一按钮跳过)。
        var chosenIdx = -1
        for (i in buttons.indices) {
            val b = buttons[i]
            val bText = b.text?.toString().orEmpty()
            val bBoundsStr = android.graphics.Rect().also { b.getBoundsInScreen(it) }.toShortString()
            if (bText == lastDirectClickedText) {
                debugLog("collectDirect: skip button[$i] text='$bText' bounds=$bBoundsStr (same as last clicked, click had no effect)")
                continue
            }
            chosenIdx = i
            break
        }
        if (chosenIdx < 0) {
            // 所有按钮都和上次点击相同（页面无任何变化），放弃 direct 阶段
            Log.i(TAG, "collectDirect: all buttons same as last clicked (no progress), opening task list")
            debugLog("collectDirect: all ${buttons.size} buttons match last clicked, give up direct collect")
            lastDirectClickedText = ""
            lastDirectClickedBounds = ""
            moveTo(AutomationState.OPENING_TASK_LIST)
            handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
            return
        }

        // 点击选中的可领取按钮（跳过了与上次相同的那一个）
        val button = buttons[chosenIdx]
        val btnText = button.text?.toString().orEmpty()
        val btnDesc = button.contentDescription?.toString().orEmpty()
        val btnBoundsStr = android.graphics.Rect().also { button.getBoundsInScreen(it) }.toShortString()
        debugLog("collectDirect: clicking button[$chosenIdx] text='$btnText' desc='$btnDesc' bounds=$btnBoundsStr (attempt ${attempt + 1})")
        Log.i(TAG, "collectDirect: clicking '$btnText' (attempt ${attempt + 1})")
        // build754: 陷阱广告连续退出且无进展时,跳过"看广告领奖"等视频类入口按钮,
        // 直接打开任务列表改做其他任务(松鼠大战/头条极速版等)
        if (shouldSkipVideoAdEntries() && isVideoAdTask("$btnText $btnDesc")) {
            Log.i(TAG, "collectDirect: skip video-ad button '$btnText' (trap ad exit streak=$trapAdExitStreak, no progress)")
            debugLog("collectDirect: 连续${trapAdExitStreak}次陷阱广告退出无进展,跳过视频类入口'$btnText',直接打开任务列表")
            moveTo(AutomationState.OPENING_TASK_LIST)
            handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
            return
        }
        // 记录本次点击,下一轮若仍是同一按钮则跳过
        lastDirectClickedText = btnText
        lastDirectClickedBounds = btnBoundsStr
        // build756: 记录是否点击了"签到"类直达按钮（供 AI 视觉跳过判定，
        // 点击成功后按钮变"已领取"即每日直达领取完成）
        directButtonSignInClicked = btnText.contains("签到") || btnDesc.contains("签到")
        service.performClickSafe(button)

        // build598 修复（用户反馈"'点击跳转拿奖励'，需要点击，后等待10秒"）：
        // UC 主页"点击跳转拿奖励"是跳转类按钮,点击后跳转到广告/活动页,
        // 需停留 10 秒拿奖励再返回主页继续。与普通 direct 按钮不同（不是弹窗领取）。
        // 识别此文案后走专用流程：点击 → 等待 10 秒 → pressBack 返回 → 继续下一轮。
        // 用 contains 匹配,兼容"点击跳转拿奖励"含额外文字（如"点击跳转拿奖励 50 肥料"）。
        // build681 扩展（debug_test_20260801_152504.log, build680）：
        //   "看广告领奖"是"点击跳转拿奖励"的变体文案（同一类跳转类广告入口按钮）,
        //   日志显示 bounds=[641,1159][822,1211] clickable=false,加入 directCollectTexts 后
        //   会被 findDirectCollectButtons 识别。同样走 10 秒 + pressBack 专用流程。
        val isJumpButton = btnText.contains("点击跳转拿奖励") || btnDesc.contains("点击跳转拿奖励") ||
            btnText.contains("看广告领奖") || btnDesc.contains("看广告领奖")
        if (isJumpButton) {
            Log.i(TAG, "collectDirect: jump button '$btnText' clicked, waiting 10s before back")
            debugLog("collectDirect: jump button '$btnText' detected, waiting 10000ms then pressBack to return")
            handler.postDelayed({
                if (state != AutomationState.COLLECTING_DIRECT) return@postDelayed
                // build683 修复（debug_test_20260801_202144.log, build682, 20:21:02-20:21:41）：
                //   20:20:52.520 点击'点击跳转拿奖励', waiting 10000ms
                //   20:21:02.527 10s elapsed, pressing back
                //   20:21:07.555 found 0 direct buttons (实际已进入广告 Activity)
                //   20:21:22.601 activeRootPkg='com.antgroup.leopard.android' (穿山甲广告)
                //   20:21:22.778 act=com.kwad.sdk.api.proxy.app.KsRewardVideoActivity, adActivity=true
                //   → pressBack 未能关闭广告(UC 激励视频 pressBack 无效),bot 继续走 COLLECTING_DIRECT
                //     但实际卡在广告页,AI 视觉超时后 fallback 到 OPENING_TASK_LIST → NAVIGATING,
                //     反复"UC ad, waiting instead of pressBack" → STOPPING
                // 根因：跳转类按钮点击后,10秒内页面可能跳转到穿山甲激励视频广告 Activity。
                //   pressBack 无法关闭此类广告,应该进入 WATCHING_AD 状态处理广告。
                // 修复：10秒后先检查是否进入广告 Activity,是则进入 WATCHING_AD 处理广告；
                //   否则 pressBack 返回主页继续 COLLECTING_DIRECT。
                //
                // build685 修复（debug_test_20260801_211023.log, build684, 21:07:46-21:08:53）：
                //   21:07:46 点击'看广告领奖' → waiting 10000ms
                //   21:07:56 10s elapsed, pressing back
                //   21:08:16 activeRootPkg='com.taobao.taobao', act=SearchActivity  ← 跳转到淘宝搜索页!
                //   → 不是广告 Activity,isAdActivity()=false,走了 pressBack 分支
                //   → pressBack 无法从淘宝返回 UC,反复 reopenFarmByDeepLink 失败 → STOPPING
                // 根因：跳转按钮可能跳转到淘宝/其他 App（非广告）,pressBack 无法跨 App 返回。
                // 修复：10秒后检查三种情况：
                //   1. 是广告 Activity → WATCHING_AD（原 build683 逻辑）
                //   2. 在农场页面 → 继续下一轮 COLLECTING_DIRECT
                //   3. 不在农场页面（其他 App）→ 重新启动农场 App 返回主页
                if (service.isAdActivity() || service.isAdPlaying()) {
                    Log.i(TAG, "collectDirect: jump button led to ad activity, entering WATCHING_AD")
                    debugLog("collectDirect: jump button '$btnText' led to ad (act=${service.getCurrentActivityName()}), entering WATCHING_AD")
                    watchingAdFromDeepLinkTask = false  // build743: 广告入口，非深链任务
                    service.setAdMode(true)
                    moveTo(AutomationState.WATCHING_AD)
                    handler.postDelayed({ runWatchingAd(elapsedMs = 0L) }, INTERVAL_CLICK_MS)
                    return@postDelayed
                }
                // 检查是否还在农场页面
                if (!service.isOnFarmPage()) {
                    // 不在农场页面（跳转到淘宝等其他 App）,重新启动农场 App 返回主页
                    // build689 修复（debug_test_20260802_102429.log, build688, 10:23:51-10:24:25）：
                    //   10:23:51 activeRootPkg='com.taobao.taobao' ← 跳转到淘宝
                    //   10:23:51 launchPlatformApp(killCurrentFirst=true 默认值)
                    //   10:23:51 reopenFarmByDeepLink: HOME + kill UC
                    //   ← HOME 把淘宝推到后台显示桌面,kill UC(已在后台),启动 UC deep link
                    //   10:23:56 activeRootPkg='com.hihonor.android.launcher' ← UC 启动失败,还在桌面!
                    //   → 反复 navigate 重试 reopenFarmByDeepLink 失败 → STOPPING
                    //   根因：当前前台是淘宝不是 UC,killCurrentFirst=true 会 HOME + kill UC,
                    //         UC 被杀后重启可能因 Honor 后台限制失败,卡死在桌面。
                    //   修复：传 killCurrentFirst=false,不杀 UC,直接用 deep link 拉起 UC 农场页,
                    //         让 UC 回到前台覆盖淘宝,避免杀进程导致重启失败。
                    //
                    // build692 修复（debug_test_20260802_193426.log, build691, 19:33:25-19:34:23）：
                    //   19:33:25 点击'看广告领奖' → 跳到通义千问 → relaunching farm app
                    //   19:33:41 重试'看广告领奖' → 又跳到通义千问 → relaunching farm app
                    //   19:33:57 重试'看广告领奖' → 又跳到通义千问 → relaunching farm app
                    //   19:34:13 重试'看广告领奖' → 又跳到通义千问 → relaunching farm app
                    //   19:34:23 STOPPING ← 4 次都跳到通义千问,陷入循环!
                    //   根因：跳转按钮跳到非广告 App(通义千问)时,bounds 每次略有不同(页面重载位置差异),
                    //         lastDirectClickedText+bounds 防死循环判断失效,反复点击同一按钮。
                    //   build692 修复：跳转按钮跳到非广告 App 时,attempt >= 2 后放弃此按钮,进入任务列表。
                    //   build693 优化（debug_test_20260802_194248.log, build692）：
                    //     attempt 0,1,2 共 3 次跳通义千问,每次 10s 等待 + 5s relaunch,总浪费约 32 秒。
                    //     用户看到反复跳转手动停止。改为 attempt >= 1 即放弃(第 2 次跳非广告 App 就放弃),
                    //     减少到约 16 秒,更快进入任务列表处理其他任务。
                    //   build694 修复（用户反馈"跳到千问后，需要退出千问，回到芭芭农场页面"）：
                    //     build693 的 launchPlatformApp(killCurrentFirst=false) 直接用 deep link 拉起 UC,
                    //     但没有退出千问,千问可能仍在后台或遮挡。用户要求先退出千问再回农场页。
                    //     修复：先 forceKillApp(千问pkg, pressBackFirst=true) 退出千问,再 launchPlatformApp 回农场。
                    val nonAdPkg = service.getCurrentWindowPackage()
                    Log.i(TAG, "collectDirect: jump button led to other app (pkg=$nonAdPkg), exiting it then relaunching farm app")
                    debugLog("collectDirect: jump button '$btnText' led to non-farm app (pkg=$nonAdPkg), exiting it first, then relaunching farm app (attempt=$attempt)")
                    // build694: 先退出跳转到的非广告 App(千问/淘宝等),再回农场页
                    if (!nonAdPkg.isNullOrEmpty()) {
                        service.forceKillApp(nonAdPkg, pressBackFirst = true)
                    }
                    // build693: 跳转按钮跳到非广告 App 时,第 2 次(attempt>=1)即放弃此按钮,进入任务列表
                    if (attempt >= 1) {
                        Log.w(TAG, "collectDirect: jump button '$btnText' keeps leading to non-ad app (attempt=$attempt), giving up this button, opening task list")
                        debugLog("collectDirect: jump button '$btnText' repeatedly led to non-ad app, giving up (attempt=$attempt), opening task list")
                        // build780 修复（debug_test_20260906_085438.log, 08:52:19-08:52:34）：
                        //   "看广告领奖"跳到淘宝(非广告App)，放弃按钮但未计陷阱退出，
                        //   streak 不增长，视频类入口跳过守卫不激活，循环继续。
                        recordTrapAdExit()
                        lastDirectClickedText = ""
                        lastDirectClickedBounds = ""
                        moveTo(AutomationState.OPENING_TASK_LIST)
                        handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                        return@postDelayed
                    }
                    service.launchPlatformApp(service.currentPlatform, killCurrentFirst = false)
                    // 等待农场 App 重新打开,继续 COLLECTING_DIRECT 下一轮
                    handler.postDelayed({
                        if (state == AutomationState.COLLECTING_DIRECT) runCollectingDirect(attempt + 1)
                    }, INTERVAL_PAGE_LOAD_MS)
                    return@postDelayed
                }
                // 在农场页面,说明跳转按钮点击后未跳转或已自动返回主页
                // build686 修复（debug_test_20260802_095406.log, build685, 09:52:25）：
                //   09:52:13 点击'看广告领奖' → waiting 10000ms
                //   09:52:25 10s elapsed, isOnFarmPage()=true → pressBack
                //   09:52:45 activity=com.bbncbot.mainactivity ← pressBack 退出 UC 农场回到 bbncbot!
                //   根因：本来就在农场主页,pressBack 反而后退一步退出 UC 农场 H5 页面。
                //   修复：在农场页面时不 pressBack,直接继续下一轮 COLLECTING_DIRECT。
                debugLog("collectDirect: 10s elapsed, already on farm page, continue next round (no pressBack)")
                handler.postDelayed({
                    if (state == AutomationState.COLLECTING_DIRECT) runCollectingDirect(attempt + 1)
                }, INTERVAL_CLICK_MS)
            }, 10000L)
            return
        }

        // 等待弹窗或页面变化（多策略领取：弹窗可能延迟出现，多次尝试找确认按钮）
        tryClaimDirectPopup(service, attempt, maxRetry = 3)
    }

    /**
     * 多策略领取直接肥料弹窗（优化方案）
     *
     * 弹窗可能延迟出现，单次查找会漏领。本方法多次尝试找确认按钮：
     * - 每次尝试间隔 INTERVAL_CLICK_MS
     * - 找到则点击，并继续检测浏览入口
     * - 未找到则减少剩余重试次数，继续等待
     * - 重试耗尽则继续下一个 direct 按钮
     *
     * @param service 无障碍服务
     * @param attempt 当前 direct 按钮的尝试序号
     * @param maxRetry 最大弹窗确认重试次数
     */
    private fun tryClaimDirectPopup(
        service: FarmAccessibilityService,
        attempt: Int,
        maxRetry: Int
    ) {
        var retryLeft = maxRetry
        fun attemptClaim() {
            if (state != AutomationState.COLLECTING_DIRECT) return
            // 尝试点击确认领取按钮（精确匹配，已含诱导黑名单过滤）
            val claimBtn = service.findClaimRewardButtonExact()
            if (claimBtn != null) {
                Log.i(TAG, "collectDirect: found exact claim button (retry left=$retryLeft), clicking")
                debugLog("collectDirect: claim button found, clicking (retry left=$retryLeft)")
                service.performClickSafe(claimBtn)
                // 领取后等待弹窗按钮文字更新（"立即领取"→"点此逛一逛再赚1000肥料"）
                handler.postDelayed({
                    if (state != AutomationState.COLLECTING_DIRECT) return@postDelayed
                    // 检查弹窗内是否出现浏览入口（"点此逛一逛"/"再赚"等）
                    val browseEntry = service.findBrowseEntryInPopup()
                    if (browseEntry != null) {
                        Log.i(TAG, "collectDirect: found browse entry in popup after claim, entering BROWSING_TASK")
                        debugLog("collectDirect: browse entry found, switching to BROWSING_TASK")
                        taskButtons = listOf(browseEntry)
                        currentTaskIndex = 0
                        taskListCheckAttempt = 0
                        browseFromDirectPopup = true  // 标记：浏览完成后回 COLLECTING_DIRECT
                        moveTo(AutomationState.BROWSING_TASK)
                        handler.postDelayed({ runBrowsingTask(swipeCount = 0) }, INTERVAL_CLICK_MS)
                        return@postDelayed
                    }
                    // 没有浏览入口，继续检查下一个 direct 按钮
                    if (state == AutomationState.COLLECTING_DIRECT) runCollectingDirect(attempt + 1)
                }, INTERVAL_CLICK_MS)
                return
            }
            // 未找到确认按钮，减少重试次数
            retryLeft--
            if (retryLeft > 0) {
                debugLog("collectDirect: claim button not found, retrying (retry left=$retryLeft)")
                handler.postDelayed({ attemptClaim() }, INTERVAL_CLICK_MS)
            } else {
                // 重试耗尽，继续下一个 direct 按钮
                debugLog("collectDirect: claim button not found after $maxRetry retries, moving to next direct button")
                if (state == AutomationState.COLLECTING_DIRECT) runCollectingDirect(attempt + 1)
            }
        }
        // 首次等待页面加载后再开始尝试
        handler.postDelayed({ attemptClaim() }, INTERVAL_PAGE_LOAD_MS)
    }

    // ============== 阶段3: 打开任务列表 ==============

    /**
     * 打开任务列表阶段：点击"集肥料"按钮
     * - 优先使用节点树查找"集肥料"按钮
     * - 失败时尝试坐标候选位置
     * - 成功后进入处理任务阶段
     */
    private fun runOpeningTaskList(attempt: Int) {
        if (state != AutomationState.OPENING_TASK_LIST) return
        val service = getService() ?: run { stop(); return }

        if (attempt == 0) {
            logPageSnapshot(service, "openTaskList-start")
            // 闭环规则：每轮任务循环重新打开任务列表时，重置"已点集肥料"标志
            // 确保支付宝每次新一轮都会先点集肥料调出任务列表，而非沿用上轮残留按钮。
            // attempt>0 的重试不重置（避免点完集肥料后又重置导致死循环）。
            if (taskListOpenedThisRound) {
                taskListOpenedThisRound = false
                debugLog("openTaskList: reset taskListOpenedThisRound for new round (attempt=0)")
            }
            // 重置广告等待时间戳：每轮开始时清零,避免上一轮残留的 adWaitStartMs
            // 影响本轮的广告等待计时(如上一轮广告已结束但 adWaitStartMs 未清)
            if (adWaitStartMs != 0L) {
                debugLog("openTaskList: resetting adWaitStartMs for new round (was non-zero)")
                adWaitStartMs = 0L
            }
            // 重置"点击商品"广告的标记:每轮开始时清零,避免上一轮残留状态影响本轮
            if (adProductClicked) {
                debugLog("openTaskList: resetting adProductClicked for new round")
                adProductClicked = false
                adProductClickTimeMs = 0L
            }
            // build530 修复（debug_test_20260719_045429.log, build530-9ab1929）：
            // 历史问题：第一轮 PROCESSING_TASK 结束回 OPENING_TASK_LIST 后，currentTaskIndex
            // 保留上一轮的值（如 currentTaskIndex=2），第二轮 processTask 从 #3 开始，
            // 跳过了 priority=0 的 pure claim "领取"按钮（在排序后的 idx=0 位置）。
            // 日志证据：
            //   第一轮：currentTaskIndex=0 → skip #1 → currentTaskIndex=1 → skip #2 → currentTaskIndex=2
            //   第二轮：processTask: current task #3/5  ← 从 #3 开始，跳过了 #1 和 #2
            //   （#1 是排序后的 priority=0 "领取"按钮，被跳过导致肥料没领取）
            //
            // 修复：每轮 OPENING_TASK_LIST 开始时（attempt==0），重置 currentTaskIndex=0
            // 并清空 taskButtons，确保 checkTaskListOpened 会重新填充并重置索引。
            // build617: 若 AI 视觉答题连续失败次数超阈值，从 currentTaskIndex=1 开始，
            //           跳过 idx=0 的答题任务（避免死循环），并重置计数器给下一轮机会。
            if (currentTaskIndex != 0 || taskButtons.isNotEmpty()) {
                val startIndex = if (quizVisionFailCount >= QUIZ_VISION_FAIL_THRESHOLD) {
                    debugLog("openTaskList: quiz vision failed $quizVisionFailCount times (>= $QUIZ_VISION_FAIL_THRESHOLD), skipping quiz task (start from index 1)")
                    quizVisionFailCount = 0  // 重置计数器，给下一轮答题任务机会（可能题目已刷新）
                    1
                } else {
                    debugLog("openTaskList: resetting currentTaskIndex to 0 for new round (was $currentTaskIndex, taskButtons.size=${taskButtons.size})")
                    0
                }
                currentTaskIndex = startIndex
                taskButtons = emptyList()
            }
        }

        if (attempt >= MAX_TASK_LIST_ATTEMPTS) {
            Log.w(TAG, "openTaskList: failed after $attempt attempts, re-navigating")
            noProgressRounds++
            if (noProgressRounds >= MAX_NO_PROGRESS_ROUNDS) {
                Log.w(TAG, "openTaskList: too many no-progress rounds, navigating")
                moveTo(AutomationState.NAVIGATING)
                handler.postDelayed({ runNavigating(0) }, INTERVAL_WAIT_MS)
            } else {
                moveTo(AutomationState.NAVIGATING)
                handler.postDelayed({ runNavigating(0) }, INTERVAL_CLICK_MS)
            }
            return
        }

        // 闭环规则：支付宝/淘宝/UC 每次进入 OPENING_TASK_LIST 必须保证在任务列表页（截图页面）
        // 用户要求：三个平台农场页起始画面（任务开始前）和任务结束后都要停在任务列表页，形成闭环。
        //           具体点哪个按钮进去任务由内置启发式逻辑决定（不写死"集肥料"）。
        // 实现：每轮重新打开任务列表时（taskListOpenedThisRound=false）：
        //   - 先检查页面是否已有"去完成"按钮（任务列表本就开着，如 UC 主页）→ 直接处理，无需再点
        //   - 没有则用 findCollectFertilizerButton 文本查找入口按钮 + 坐标兜底
        // 这样既保证闭环（任务结束后回到任务列表页），又不破坏 UC 任务入口原本在主页的行为。
        if (service.currentPlatform != Platform.UNKNOWN && !taskListOpenedThisRound) {
            // 先检查页面上是否已有可见的"去完成"按钮（UC 主页任务入口直接可见的情况）
            val visibleGoComplete = service.findGoCompleteButtons()
            if (visibleGoComplete.isEmpty()) {
                // build674 修复（debug_test_20260801_094058.log, build673）：
                // UC 活动版农场页面（"8.4内完成3天即领"）任务直接显示在主页,不需要点"集肥料"。
                // 但活动页面任务节点加载较慢（日志显示首次 findGoCompleteButtons 返回空,
                // 69 秒后第 6 次才返回 7 个按钮）。原逻辑会点"集肥料"5 次卡死 62 秒。
                // 修复：检测到活动版页面时,不点"集肥料",直接重试 findGoCompleteButtons 等待加载。
                if (service.isActivityFarmPage()) {
                    debugLog("openTaskList: [${service.currentPlatform}闭环] activity farm page detected, tasks should be on homepage, waiting for goComplete buttons to load (attempt=$attempt)")
                    taskListOpenedThisRound = true  // 标记本轮已处理,避免重复进入
                    handler.postDelayed({
                        if (state == AutomationState.OPENING_TASK_LIST) runOpeningTaskList(attempt + 1)
                    }, INTERVAL_PAGE_LOAD_MS)
                    return
                }
                // build779 修复（debug_test_20260905_133337.log, 13:33:10-13:33:15）：
                //   UC 全部任务完成（isTaskCompletePage=YES matched=[已完成]，节点=已领取/明天领肥料），
                //   页面上已无"去完成"按钮。原流程不识别完成态，继续走启发式开任务列表，
                //   findCollectFertilizerButton 遇瞬时 root null → 误判 WebView not ready → re-navigate，
                //   重新导航撞见京东快手广告空等 15s，直到用户手动停止。
                //   修复：无"去完成"按钮且 isRealTaskCompletePage()（完成关键词+农场上下文双重确认）
                //   时判定任务全部完成，标记平台完成并进入施肥阶段，不再重新打开任务列表。
                if (service.isRealTaskCompletePage()) {
                    Log.i(TAG, "openTaskList: [${service.currentPlatform}闭环] real task complete page & no goComplete buttons, all tasks done → FERTILIZING (build779)")
                    debugLog("openTaskList: [${service.currentPlatform}闭环] isRealTaskCompletePage=YES & no goComplete buttons, mark platform complete → FERTILIZING (build779)")
                    markPlatformAdsComplete(service)
                    moveTo(AutomationState.FERTILIZING)
                    handler.postDelayed({ runFertilizing(clickCount = 0) }, INTERVAL_CLICK_MS)
                    return
                }
                // 任务列表未打开：用文本查找 + 坐标兜底调出任务列表
                taskListOpenedThisRound = true  // 标记本轮已尝试调出，避免重复进入死循环
                debugLog("openTaskList: [${service.currentPlatform}闭环] no goComplete buttons visible, opening task list (attempt=$attempt, using heuristic)")
                // 启发式逻辑：用 collectFertilizerTexts 文本查找入口按钮
                debugLog("openTaskList: [${service.currentPlatform}闭环] heuristic text search (attempt=$attempt)")
                val entryButton = service.findCollectFertilizerButton()
                if (entryButton != null) {
                    debugLog("openTaskList: [${service.currentPlatform}闭环] clicking entry button by text (attempt=$attempt)")
                    service.performClickSafe(entryButton)
                } else {
                    // 坐标兜底前必须确认在农场页：
                    // 历史问题——当 gamePlay 后未真正回到农场页（仍在蚂蚁庄园等小程序页）时，
                    // findCollectFertilizerButton 返回 null，但代码会继续按坐标点击"集肥料"按钮位置，
                    // 结果点开了相邻页面的入口（如"小鸡乐园活动规则"页），打开无关规则条款页 → 误识别为
                    // 任务按钮 → isPaidTask 误判 → 整个平台被错误标记为"已完成"。
                    // 修复：不在农场页时禁用坐标兜底，转回 NAVIGATING 重新导航到农场页。
                    if (!service.isOnFarmPage()) {
                        debugLog("openTaskList: [${service.currentPlatform}闭环] not on farm page (no entry button and not on farm), re-navigating instead of coordinate fallback")
                        taskListOpenedThisRound = false  // 重置，让导航回农场后下一轮还能尝试
                        moveTo(AutomationState.NAVIGATING)
                        handler.postDelayed({ runNavigating(0) }, INTERVAL_CLICK_MS)
                        return
                    }
                    // 在农场页才允许坐标兜底（H5 页面 WebView 文本不暴露的情况）
                    val candidates = collectFertilizerCandidates(service)
                    if (candidates.isNotEmpty()) {
                        // build532 修复（debug_test_20260719_063933.log, build532-6d5c936）：
                        // 历史问题：findCollectFertilizerButton 返回 null 时，原逻辑立即坐标兜底点击
                        // (0.888, 0.771) 即 (1065, 1960)。但日志显示当时 root childCount=1，
                        // 说明 WebView 还在加载，主页内容未渲染。提前点击该坐标可能误触发系统事件，
                        // 导致 bot 自己切到前台（activity=com.bbncbot.mainactivity），后续 root 一直为 null。
                        //
                        // 修复：坐标兜底前先检查 root 内容量。若 root 子节点太少（childCount<3
                        // 或估算后代<10），说明 WebView 还在加载，不执行坐标点击，转回 NAVIGATING
                        // 重新等待页面加载（最多重试若干次）。
                        val rootForGuard = service.getRootInFarmApp()
                        val rootChildCount = rootForGuard?.childCount ?: 0
                        val descendantEstimate = if (rootForGuard != null) {
                            rootChildCount + (0 until rootChildCount).sumOf {
                                (rootForGuard.getChild(it)?.childCount ?: 0)
                            }
                        } else 0
                        if (rootChildCount < 3 || descendantEstimate < 10) {
                            debugLog("openTaskList: [${service.currentPlatform}闭环] WebView not ready (rootChildCount=$rootChildCount, descendantEstimate=$descendantEstimate), skip coordinate click, re-navigate")
                            taskListOpenedThisRound = false
                            moveTo(AutomationState.NAVIGATING)
                            handler.postDelayed({ runNavigating(0) }, INTERVAL_PAGE_LOAD_MS)
                            return
                        }
                        val coordIndex = attempt % candidates.size
                        val (xRatio, yRatio) = candidates[coordIndex]
                        debugLog("openTaskList: [${service.currentPlatform}闭环] no entry button, clicking by coordinate #$coordIndex (attempt=$attempt)")
                        clickAtRatio(service, xRatio, yRatio, "集肥料")
                    } else {
                        debugLog("openTaskList: [${service.currentPlatform}闭环] no entry found (text+coord), reset flag for next round")
                        taskListOpenedThisRound = false  // 重置，让下一轮还能尝试
                    }
                }
                handler.postDelayed({
                    if (state == AutomationState.OPENING_TASK_LIST) checkTaskListOpened(service, attempt)
                }, INTERVAL_PAGE_LOAD_MS)
                return
            } else {
                // 任务列表本就开着（UC 主页有去完成按钮），标记本轮已确认，直接走下面的处理
                taskListOpenedThisRound = true
                debugLog("openTaskList: [${service.currentPlatform}闭环] ${visibleGoComplete.size} goComplete buttons already visible, task list open, processing directly")
            }
        }

        // 优先检查：页面上是否已有"去完成"按钮（UC 等平台任务入口直接在主页上，无需点击"集肥料"打开任务列表）
        // 用户需求：UC 主页上有多个任务入口（看视频、浏览广告等），选择一个打开，没获取到肥料就选另一个
        val existingButtons = service.findGoCompleteButtons()
        if (existingButtons.isNotEmpty()) {
            Log.i(TAG, "openTaskList: found ${existingButtons.size} goComplete buttons directly on page (no need to click 集肥料), platform=${service.currentPlatform}")
            debugLog("openTaskList: ${existingButtons.size} goComplete buttons already visible, processing directly (attempt=$attempt)")
            existingButtons.forEachIndexed { idx, btn ->
                val rect = Rect()
                btn.getBoundsInScreen(rect)
                val txt = btn.text?.toString().orEmpty()
                val desc = btn.contentDescription?.toString().orEmpty()
                debugLog("taskButton[$idx]: text='$txt', desc='$desc', bounds=${rect.toShortString()}, clickable=${btn.isClickable}")
            }
            if (taskButtons.isEmpty() || currentTaskIndex >= existingButtons.size) {
                debugLog("openTaskList: resetting currentTaskIndex to 0 (was $currentTaskIndex)")
                currentTaskIndex = 0
            }
            taskButtons = sortTaskButtonsByPriority(service, existingButtons)
            taskListCheckAttempt = 0
            moveTo(AutomationState.PROCESSING_TASK)
            handler.postDelayed({ runProcessingTask(attempt = 0) }, INTERVAL_CLICK_MS)
            return
        }

        // 优先检查：页面上是否有直接可领取的肥料按钮（如"领取肥料礼包"、"立即领取肥料"）
        // 用户需求：点击这类按钮会弹出窗口，需在窗口里点"立即领取"才能领到肥料，应走 directCollect 流程
        // 而非当成"集肥料"入口去找任务列表
        val directButtons = service.findDirectCollectButtons()
        // build755 修复（debug_test_20260829_192115.log, build754, 19:18:38-19:19:41）:
        //   陷阱广告连续退出后 collectDirect 跳过视频类入口"看广告领奖"转回 OPENING_TASK_LIST,
        //   而此处发现主页"看广告领奖"direct 按钮又转回 COLLECTING_DIRECT —— 两边互相
        //   转换形成 4 轮死循环(每轮~20s,点"集肥料"5次检查0按钮无进展)。
        //   修复:视频入口跳过激活期间,若 direct 按钮全是视频类入口则不转换,继续正常
        //   任务列表流程(集肥料→任务列表→processTask 跳过视频任务改做其他任务)。
        val videoSkipActive = shouldSkipVideoAdEntries()
        val directButtonsAllVideo = directButtons.all { node ->
            val t = node.text?.toString().orEmpty()
            val d = node.contentDescription?.toString().orEmpty()
            isVideoAdTask("$t $d")
        }
        if (directButtons.isNotEmpty() && !(videoSkipActive && directButtonsAllVideo)) {
            Log.i(TAG, "openTaskList: found ${directButtons.size} direct collect buttons, switching to COLLECTING_DIRECT")
            debugLog("openTaskList: ${directButtons.size} direct collect buttons found, switching to COLLECTING_DIRECT (attempt=$attempt)")
            moveTo(AutomationState.COLLECTING_DIRECT)
            handler.postDelayed({ runCollectingDirect(attempt = 0) }, INTERVAL_CLICK_MS)
            return
        }
        if (directButtons.isNotEmpty() && videoSkipActive && directButtonsAllVideo) {
            debugLog("openTaskList: ${directButtons.size} direct buttons all video-ad entries (trap skip active), continue task list flow")
        }

        // 优先查找"集肥料"按钮节点
        val button = service.findCollectFertilizerButton()
        debugLog("openTaskList: findCollectFertilizerButton=${button != null}, attempt=$attempt")
        if (button != null) {
            Log.i(TAG, "openTaskList: found 集肥料 button by text, clicking (attempt ${attempt + 1})")
            service.performClickSafe(button)
            handler.postDelayed({
                if (state == AutomationState.OPENING_TASK_LIST) checkTaskListOpened(service, attempt)
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 失败时尝试坐标候选位置
        // P0-3（build513 修复）：加 isOnFarmPage 守卫，避免在非农场页（如 systemui 覆盖、
        // 子小程序页、广告页）按坐标点"集肥料"位置误触其他 App 元素。
        // 与第一处坐标兜底（行 877-890）保持一致的守卫策略。
        if (!service.isOnFarmPage()) {
            debugLog("openTaskList: [坐标兜底] not on farm page (findCollectFertilizerButton=null), re-navigating instead of coordinate fallback")
            taskListOpenedThisRound = false
            moveTo(AutomationState.NAVIGATING)
            handler.postDelayed({ runNavigating(0) }, INTERVAL_CLICK_MS)
            return
        }
        // build532 修复（与第一处坐标兜底守卫一致）：WebView 未渲染完成时（root childCount
        // 太小）不执行坐标点击，避免误触发系统事件导致 bot 切到前台。
        val rootForGuard = service.getRootInFarmApp()
        val rootChildCount = rootForGuard?.childCount ?: 0
        val descendantEstimate = if (rootForGuard != null) {
            rootChildCount + (0 until rootChildCount).sumOf {
                (rootForGuard.getChild(it)?.childCount ?: 0)
            }
        } else 0
        if (rootChildCount < 3 || descendantEstimate < 10) {
            debugLog("openTaskList: [坐标兜底] WebView not ready (rootChildCount=$rootChildCount, descendantEstimate=$descendantEstimate), skip coordinate click, re-navigate")
            taskListOpenedThisRound = false
            moveTo(AutomationState.NAVIGATING)
            handler.postDelayed({ runNavigating(0) }, INTERVAL_PAGE_LOAD_MS)
            return
        }
        val candidates = collectFertilizerCandidates(service)
        val coordIndex = attempt % candidates.size
        val (xRatio, yRatio) = candidates[coordIndex]
        Log.i(TAG, "openTaskList: clicking 集肥料 by coordinate #$coordIndex (attempt ${attempt + 1}) platform=${service.currentPlatform}")
        clickAtRatio(service, xRatio, yRatio, "集肥料")
        handler.postDelayed({
            if (state == AutomationState.OPENING_TASK_LIST) checkTaskListOpened(service, attempt)
        }, INTERVAL_PAGE_LOAD_MS)
    }

    /** 检查任务列表是否已打开（带等待重试） */
    private var taskListCheckAttempt: Int = 0

    /**
     * 广告等待起始时间戳（仅用于 [checkTaskListOpened] 中检测集肥料点击后弹出的激励视频广告）
     *
     * - 0L 表示当前未在广告等待中
     * - 非 0 表示检测到广告时记录的时间戳,用于限制广告等待上限(避免无限等待)
     */
    private var adWaitStartMs: Long = 0L

    /**
     * "点击商品,领取奖励"广告的商品点击状态
     *
     * 场景：UC 集肥料点击后弹激励视频广告,顶部提示"点击商品,领取奖励",
     * 必须主动点击广告中的商品才能触发肥料奖励,广告结束后不发肥料。
     *
     * - false=未点击商品(或已重置)
     * - true=已点击商品,等待奖励触发后关闭广告
     */
    private var adProductClicked: Boolean = false

    /**
     * build702: "点击商品,领取奖励"广告中商品点击次数
     *
     * 用于限制点击商品的次数,避免关闭广告后再次点击商品循环。
     * 每轮广告最多点击2次商品,超过后不再点击,直接 pressBack 退出。
     */
    private var adProductClickCount: Int = 0

    /**
     * "点击商品,领取奖励"广告中 findAdProductNode 找不到可点击节点的连续失败次数
     *
     * build722 修复（debug_test_20260811_081229.log, build721, 08:11:59-08:12:25）：
     * 腾讯广告 PortraitADActivity,商品节点在 WebView 内不可访问(clickable=false),
     * findAdProductNode 一直返回 null,每 2s 重试无超时,干等 20s 用户手动停止。
     * 修复:连续失败超过 5 次(约10s)后,改用 dispatchGestureClick 点击屏幕中部
     * (广告内容区域)触发奖励,避免无限重试。
     */
    private var adProductNodeFindFailCount: Int = 0

    /**
     * "点击商品,领取奖励"广告中商品点击的时间戳
     *
     * 用于在点击商品后等待一段时间(5s)再关闭广告,让广告主落地页加载/奖励触发
     */
    private var adProductClickTimeMs: Long = 0L

    /**
     * build768-2: "点击商品"广告退出阶段 back 无效计数
     *
     * 日志证据(debug_test_20260905_063248.log, build770, 06:32:10-06:32:44):
     * 腾讯 PortraitADActivity 点击商品广告,click count>=2 后每 2.5s pressBack
     * 连续 17 次无效(back 被广告 SDK 拦截);且该分支 return 在 90s 超时守卫之前,
     * 超时永远不可达 → 无限循环 61 秒直到用户手动停止。
     * 连续 3 次无效后升级 CLOSING_AD 多策略关闭(forceKill 兜底)。
     */
    private var adProductExitBackCount: Int = 0

    /**
     * build731: 汇川广告"点击商家后立即领奖"返回后,等待点击商家→奖励发放
     *
     * 汇川 HCRewardVideoActivity "点击商品，领取奖励"广告:
     * 点击商品2s后关闭广告→弹出"确认要离开吗"+"点击商家后立即领奖"(奖励未触发)。
     * "点击商家后立即领奖"语义: 返回后需再点击商家,奖励才发放(185130日志证明纯等待90s无效)。
     *
     * 状态机:
     * - 第一次弹窗: 点"返回点击商家",重置adProductClicked(阶段1再点商品=点击商家),设此flag=true
     * - flag=true 且 isClickProductAd(列表页): 阶段2不2s自动关闭,等待"奖励已发放"(最多15s)
     *   → 检测到后 claimRewardViaCloseIcon 点右上角关闭图标领奖
     *   → 15s超时无奖励: 点关闭退出;若第二次弹窗出现,直接"放弃奖励离开"(防死循环)
     * - flag=true 且 TRAP场景(详情页形态): build730守卫跳过陷阱防御,等待"奖励已发放"
     * - 新广告开始/领奖成功 时重置为false
     */
    private var huichuanMerchantPending: Boolean = false

    /**
     * build732: WATCHING_AD 中 TRAP_RECHARGE 分支 pressBack 连续无效计数
     *
     * 快手广告(KsRewardVideoActivity)"扭一扭"互动页被 isRechargePage 误判为充值陷阱时,
     * clickCloseOnRechargePage 找不到关闭按钮 → pressBack,但广告 Activity 拦截 back 键退不出去,
     * 每5s循环(190740日志 elapsed=190s仍不停,原分支无超时保护)。
     * 连续 4 次(约20s)无效后 forceKillApp 杀宿主 + 重开农场兜底。新广告开始时重置。
     */
    private var trapRechargeBackCount: Int = 0

    /**
     * build735: WATCHING_AD 中 TRAP_INSTALL 分支 pressBack 连续无效计数
     *
     * 汇川"点击跳转后停留"广告(千问APP)整页无关闭按钮,closeAdInstallPopup 失败 → pressBack,
     * 但广告 Activity 拦截 back 键退不出去,每5s循环(191553日志 36s 无效果直到用户手动停止,
     * 分支无超时保护)。连续 4 次(约20s)无效后 forceKillApp 杀宿主 + 重开农场兜底(同 build732)。
     */
    private var trapInstallBackCount: Int = 0

    private fun checkTaskListOpened(service: FarmAccessibilityService, openingAttempt: Int) {
        if (state != AutomationState.OPENING_TASK_LIST) return

        // build595 修复（debug_test_20260722_023550.log, build594 line 30-49）：
        // UC 在打开任务列表过程中弹出推送权限授权弹窗（Activity=
        // com.uc.base.push.permission.guide.e），弹窗遮住任务列表，
        // checkTaskListOpened 找不到"去完成"按钮，5 次重试后 isOnFarmPage 返回 false
        // → 回退 NAVIGATING → 重进农场又弹权限弹窗，死循环失败。
        // 修复：检测到权限弹窗时,主动点击"拒绝/暂不/关闭"按钮关闭它,再继续找 goComplete。
        if (service.isSystemPermissionPopup()) {
            Log.i(TAG, "checkTaskListOpened: system permission popup detected, closing it (deny button)")
            debugLog("checkTaskListOpened: system permission popup detected, attempting to close")
            val denyBtn = service.findSystemPermissionDenyButton()
            if (denyBtn != null) {
                debugLog("checkTaskListOpened: clicking deny button on permission popup (text='${denyBtn.text}')")
                service.performClickSafe(denyBtn)
            } else {
                debugLog("checkTaskListOpened: no deny button found, pressing back to close popup")
                service.pressBack()
            }
            // 不增加 taskListCheckAttempt,继续等待任务列表打开
            handler.postDelayed({
                if (state == AutomationState.OPENING_TASK_LIST) checkTaskListOpened(service, openingAttempt)
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }
        // build559 修复（debug_test_20260719_153945.log, build558-44cd648）：
        // 历史问题：UC 平台"集肥料"按钮点击后会弹激励视频广告(穿山甲/汇川,30~42s),
        // 广告期间 rootInActiveWindow 是广告 Activity,findGoCompleteButtons 找不到"去完成"按钮。
        // checkTaskListOpened 5 次重试(10s)超时 → NAVIGATING → 在广告 Activity 上 pressBack
        // (对激励视频无效) → 等广告结束回主页 → 又点集肥料又弹新广告 → 死循环直至超时停止。
        //
        // 修复：检测到广告时,延长等待时间直到广告结束(不增加 taskListCheckAttempt,避免 5 次超时)。
        // 等待上限使用平台的 adDefaultMaxDurationMs(UC=90s),超过则放弃等待走原超时逻辑。
        // 广告结束后(检测不到广告)重置 adWaitStartMs,继续找 goComplete 按钮。
        if (service.isAdActivity() || service.isAdPlaying()) {
            val now = System.currentTimeMillis()
            if (adWaitStartMs == 0L) {
                adWaitStartMs = now
                debugLog("checkTaskListOpened: ad detected (act=${service.getCurrentActivityName()}), start waiting for ad to finish (taskListCheckAttempt=$taskListCheckAttempt not incremented)")
            }

            // build560 修复（debug_test_20260719_153945.log, build558-44cd648）：
            // UC"点击商品,领取奖励"激励视频广告:必须主动点击广告中的商品才能触发肥料奖励。
            // 不点击商品的话广告结束不发肥料,且广告结束后会重新弹新广告,死循环。
            // 用户需求:点击商品 → 等待几秒(让奖励触发) → 关闭广告窗口
            if (service.isClickProductAd()) {
                if (!adProductClicked) {
                    // 阶段1:找商品节点点击
                    val productNode = service.findAdProductNode()
                    if (productNode != null) {
                        val rect = Rect()
                        productNode.getBoundsInScreen(rect)
                        Log.i(TAG, "checkTaskListOpened: clicking ad product to trigger reward (bounds=${rect.toShortString()})")
                        debugLog("checkTaskListOpened: gesture-clicking ad product bounds=${rect.toShortString()} center (${rect.centerX()},${rect.centerY()})")
                        // build774: 汇川 WebView 广告不计 ACTION_CLICK(页面不跳转不发奖),
                        // 改按节点 bounds 中心手势点击(与 watchAd 同步修复)
                        service.dispatchGestureClick(rect.centerX().toFloat(), rect.centerY().toFloat())
                        adProductClicked = true
                        adProductClickTimeMs = now
                    } else {
                        // 找不到可点击商品节点:可能是页面还没渲染,或商品是 WebView 内不可访问节点
                        // 等待 2s 后重试(不立即放弃,广告可能还在加载商品卡)
                        // build722 修复：连续失败超过 5 次(约10s)后,改用 dispatchGestureClick
                        //   点击屏幕中部触发奖励,避免无限重试(与 watchAd 同步修复)。
                        adProductNodeFindFailCount++
                        if (adProductNodeFindFailCount >= 5) {
                            Log.w(TAG, "checkTaskListOpened: 点击商品 ad no clickable product node after ${adProductNodeFindFailCount} retries, clicking center to trigger reward")
                            debugLog("checkTaskListOpened: 点击商品 ad no clickable product node after ${adProductNodeFindFailCount} retries, clicking center (600,1200) to trigger reward")
                            service.dispatchGestureClick(600f, 1200f)
                            adProductNodeFindFailCount = 0
                            adProductClicked = true
                            adProductClickTimeMs = now
                        } else {
                            debugLog("checkTaskListOpened: 点击商品 ad detected but no clickable product node, retrying in 2s (failCount=${adProductNodeFindFailCount})")
                        }
                    }
                } else {
                    // 阶段2:已点击商品,等待 5s 让奖励触发后关闭广告
                    val sinceClick = now - adProductClickTimeMs
                    if (sinceClick >= 5000L) {
                        Log.i(TAG, "checkTaskListOpened: 5s after clicking ad product, closing ad window (sinceClick=${sinceClick}ms)")
                        debugLog("checkTaskListOpened: closing ad window 5s after product click")
                        // 优先找关闭按钮,找不到 pressBack
                        val closeBtn = service.findAdCloseButton(service.currentPlatformConfig().adCloseButtonTexts, enforceSceneWhitelist = false)
                        if (closeBtn != null) {
                            debugLog("checkTaskListOpened: clicking close button on ad (text='${closeBtn.text}')")
                            service.performClickSafe(closeBtn)
                        } else {
                            debugLog("checkTaskListOpened: no close button, pressing back to close ad")
                            service.pressBack()
                        }
                        // 重置标记:下一轮如果还在广告中,会重新尝试点击商品
                        adProductClicked = false
                        adProductClickTimeMs = 0L
                    } else {
                        debugLog("checkTaskListOpened: waiting ${sinceClick}ms/5000ms after clicking ad product")
                    }
                }
                // 点击商品广告:用较短间隔(2s)轮询,而非 INTERVAL_PAGE_LOAD_MS(5s)
                handler.postDelayed({
                    if (state == AutomationState.OPENING_TASK_LIST) checkTaskListOpened(service, openingAttempt)
                }, INTERVAL_CLICK_MS)
                return
            }

            val maxWaitMs = service.currentPlatformConfig().adDefaultMaxDurationMs
            val elapsed = now - adWaitStartMs
            if (elapsed < maxWaitMs) {
                debugLog("checkTaskListOpened: ad still playing (elapsed=${elapsed}ms, max=${maxWaitMs}ms), waiting...")
                handler.postDelayed({
                    if (state == AutomationState.OPENING_TASK_LIST) checkTaskListOpened(service, openingAttempt)
                }, INTERVAL_PAGE_LOAD_MS)
                return
            }
            debugLog("checkTaskListOpened: ad wait timeout (elapsed=${elapsed}ms >= max=${maxWaitMs}ms), giving up ad wait, fall through to normal check")
            // 超时后不立即 return,继续走下面的 findGoCompleteButtons 逻辑(可能广告刚好结束)
        } else if (adWaitStartMs != 0L) {
            // 广告刚结束,重置等待时间戳
            debugLog("checkTaskListOpened: ad finished (waited ${System.currentTimeMillis() - adWaitStartMs}ms), resetting adWaitStartMs")
            adWaitStartMs = 0L
            // 同时重置商品点击标记(避免跨广告残留)
            if (adProductClicked) {
                debugLog("checkTaskListOpened: resetting adProductClicked (ad finished)")
                adProductClicked = false
                adProductClickTimeMs = 0L
            }
        }

        // 查找"去完成"按钮
        val buttons = service.findGoCompleteButtons()
        debugLog("checkTaskListOpened: found ${buttons.size} goComplete buttons, checkAttempt=$taskListCheckAttempt, currentIndex=$currentTaskIndex")
        if (buttons.isNotEmpty()) {
            Log.i(TAG, "openTaskList: task list opened with ${buttons.size} tasks")
            // 输出每个任务按钮的详细信息（text/desc/bounds），用于诊断"点击位置是否正确"
            buttons.forEachIndexed { idx, btn ->
                val rect = Rect()
                btn.getBoundsInScreen(rect)
                val txt = btn.text?.toString().orEmpty()
                val desc = btn.contentDescription?.toString().orEmpty()
                debugLog("taskButton[$idx]: text='$txt', desc='$desc', bounds=${rect.toShortString()}, clickable=${btn.isClickable}")
            }
            // 只在首次打开（currentTaskIndex 超出范围或 taskButtons 为空）时重置索引
            // 保留 currentTaskIndex 的值，避免重新打开任务列表后重复点击已跳过的任务
            if (taskButtons.isEmpty() || currentTaskIndex >= buttons.size) {
                debugLog("checkTaskListOpened: resetting currentTaskIndex to 0 (was $currentTaskIndex, taskButtons was empty=${taskButtons.isEmpty()})")
                currentTaskIndex = 0
            }
            taskButtons = sortTaskButtonsByPriority(service, buttons)
            taskListCheckAttempt = 0
            moveTo(AutomationState.PROCESSING_TASK)
            handler.postDelayed({ runProcessingTask(attempt = 0) }, INTERVAL_CLICK_MS)
            return
        }

        // 任务列表可能还在加载中，等待重试（最多5次，每次2秒）
        taskListCheckAttempt++
        if (taskListCheckAttempt < 5) {
            Log.i(TAG, "openTaskList: task list not opened yet, waiting (check $taskListCheckAttempt)")
            handler.postDelayed({
                if (state == AutomationState.OPENING_TASK_LIST) checkTaskListOpened(service, openingAttempt)
            }, INTERVAL_CLICK_MS)
            return
        }

        // 等待超时，重新点击"集肥料"
        taskListCheckAttempt = 0
        Log.w(TAG, "openTaskList: task list not opened after 5 checks, retrying click")
        handler.postDelayed({
            if (state == AutomationState.OPENING_TASK_LIST) runOpeningTaskList(openingAttempt + 1)
        }, INTERVAL_CLICK_MS)
    }

    /**
     * 任务按钮排序（稳定排序，保留原有视觉顺序）
     *
     * 优先级策略（用户需求：点击领取/签到就能完成的任务优先处理）：
     * - priority 0：签到/领取类任务（无需跳转、点击即完成，最容易拿肥料）
     *   识别关键词：去签到/签到、去领取/领取/立即领取/点击领取、立即完成
     *   注意：必须排除付费陷阱（如"立即完成购买"），用 isPaidTask 兜底过滤
     * - priority 1：其他普通任务（去完成/去逛逛/去观看 等）
     *
     * 同优先级内保持原有视觉顺序（稳定排序）。
     */
    private fun sortTaskButtonsByPriority(
        service: FarmAccessibilityService,
        buttons: List<AccessibilityNodeInfo>
    ): List<AccessibilityNodeInfo> {
        if (buttons.size <= 1) return buttons
        val platform = service.currentPlatform.name
        if (platform == "UNKNOWN") return buttons
        // 签到/领取类任务识别关键词（任务标题或按钮文字命中即视为"易完成任务"）
        // P1-3（build513 修复）：收紧关键词，避免"领取后退款将扣回肥料"等付费陷阱被误判为 easyClaim。
        // - 移除宽泛的"领取"/"收下"
        // - 保留带动词前缀的精确短语："去签到""立即领取""点击领取""开心收下"
        // - 额外排除"退款"/"扣回"/"下单后"等付费暗示词
        // build518 补丁：恢复 button.text == "领取" 精确匹配（用户反馈纯"领取"按钮应优先），
        // 但 contextText 含"领取"仍不匹配（避免"领取后退款"等陷阱描述命中）
        val easyClaimKeywords = listOf(
            "去签到",        // 每日签到任务（明确动词）
            "签到领",        // "签到领肥料"等
            "去领取",        // 明确"去领取"动作
            "立即领取",      // 红包弹窗的立即领取
            "点击领取",      // 用户明确提到的"点击领取"
            "开心收下",      // 红包弹窗的收下按钮
            "签到"           // 每日签到（短词，但任务列表里"签到"通常是真的签到任务）
        )
        // 付费/退款暗示词：即使命中 easyClaimKeywords，若上下文含这些词也降级到 priority 1
        val paidHintKeywords = listOf(
            "退款", "扣回", "扣减", "下单后", "购买后", "充值后", "消费满",
            "任意下单", "下单领", "下单赢", "任意充值"
        )
        return buttons.mapIndexed { idx, btn ->
            val taskContent = SceneFeatureExtractor.extractTaskContentText(service, btn)
            // 检查任务上下文（含任务标题）和按钮文本本身
            val contextText = service.collectTaskContextText(btn)
            val buttonText = btn.text?.toString().orEmpty()
            val fullText = "$contextText $buttonText"
            // 排除付费陷阱：即使是"领取"类，若 isPaidTask=true 也降级到 priority 1
            val isPaid = service.isPaidTask(btn)
            // P1-3：额外检查付费暗示词，避免 isPaidTask 漏判（paidKeywords 不全）
            val hasPaidHint = paidHintKeywords.any { fullText.contains(it) }
            // build518 补丁：button.text 精确等于"领取"或"收下"也视为 easyClaim
            // （纯领取按钮是点击即得肥料的简单任务），但 contextText 不能含付费暗示词
            // build523：扩展为所有纯领取按钮文案（与 processTask 的 isPureClaimClick 保持一致）
            val pureClaimTexts = setOf(
                "领取", "收下", "立即领取", "点击领取", "开心收下",
                "立即领肥", "领取肥料", "立即领肥[料]"
            )
            val isPureClaimButton = buttonText in pureClaimTexts && !hasPaidHint
            val isEasyClaim = !isPaid && !hasPaidHint &&
                (easyClaimKeywords.any { fullText.contains(it) } || isPureClaimButton)
            val priority = if (isEasyClaim) 0 else 1
            debugLog("sortTaskButtons: idx=$idx task='$taskContent' priority=$priority (easyClaim=$isEasyClaim, paid=$isPaid, paidHint=$hasPaidHint, pureClaim=$isPureClaimButton, button='$buttonText')")
            Triple(priority, idx, btn)
        }.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }
    }

    // ============== 阶段3: 处理任务（点击去完成按钮） ==============

    /**
     * 处理任务阶段：点击"去完成"按钮
     * - 用户要求：只点击广告，邀请推广网页直接返回
     * - 用户要求：安装软件的广告也不做，不安装，直接退出
     * - 点击后检测是否进入广告，如果是广告则进入看广告阶段
     * - 如果是非广告页面，直接返回继续下一个任务
     */
    private fun runProcessingTask(attempt: Int) {
        if (state != AutomationState.PROCESSING_TASK) return
        val service = getService() ?: run { stop(); return }

        // build733: 重置"本次点击后是否离开过农场页"标记(新一轮任务点击开始)
        if (attempt == 0) {
            taskClickLeftFarm = false
            // build736: 重置红包弹窗关闭计数(新一轮任务开始)
            taskRedPacketCloseAttempts = 0
        }

        // 兜底：从 direct 弹窗进入浏览后若节点失效被踢回 PROCESSING_TASK，
        // 不应继续处理仅含浏览节点的 taskButtons，应回 COLLECTING_DIRECT 重新找 direct 按钮
        if (browseFromDirectPopup) {
            debugLog("processTask: entered with browseFromDirectPopup=true, returning to COLLECTING_DIRECT")
            browseFromDirectPopup = false
            moveTo(AutomationState.COLLECTING_DIRECT)
            handler.postDelayed({ runCollectingDirect(attempt = 0) }, INTERVAL_CLICK_MS)
            return
        }

        if (attempt == 0) {
            logPageSnapshot(service, "processTask-start")
        }

        // 如果任务列表为空或已处理完，进入施肥阶段
        if (taskButtons.isEmpty() || currentTaskIndex >= taskButtons.size) {
            Log.i(TAG, "processTask: all tasks processed (collected=$collectedCount), starting fertilizing")
            debugLog("processTask: all ads/tasks done on ${service.currentPlatform}, collected=$collectedCount")
            // 标记当前平台广告已获取完，并检查三平台是否全部完成
            markPlatformAdsComplete(service)
            moveTo(AutomationState.FERTILIZING)
            handler.postDelayed({ runFertilizing(clickCount = 0) }, INTERVAL_CLICK_MS)
            return
        }

        if (attempt >= MAX_TASK_ATTEMPTS) {
            Log.w(TAG, "processTask: task #$currentTaskIndex failed after $attempt attempts, skipping")
            currentTaskIndex++
            noProgressRounds++
            handler.postDelayed({
                if (state == AutomationState.PROCESSING_TASK) runProcessingTask(0)
            }, INTERVAL_CLICK_MS)
            return
        }

        // 确保在农场页
        if (!service.isOnFarmPage()) {
            // build747 修复（debug_test_20260822_192917.log, build746, 19:27:55-19:28:55 卡死70s）：
            //   看视频任务误判 advance 后页面跳到淘宝首页,本分支 pressBack×3 无效
            //   （淘宝首页拦截返回键）→ skip task → 下一个任务 → pressBack×3 …… 8个任务
            //   轮完需 2 分钟+,用户手动停止时已卡 70s。
            // 修复：attempt>=1（pressBack 已试过一次无效）且活动窗口是外来App（非农场/
            //   非systemui/非android）→ forceKill 该App + reopenFarmByDeepLink 回农场。
            //   注:深链任务停留期间状态是 WATCHING_AD（processTask 点击后走 checkTaskResult
            //   深链分支）,不会进入本分支,forceKill 不影响深链任务浏览。
            val activeRootPkg = service.rootInActiveWindowSafe()?.packageName?.toString().orEmpty()
            val cfg = service.currentPlatformConfig()
            val isForeignActiveApp = activeRootPkg.isNotEmpty() &&
                activeRootPkg !in cfg.packageNames &&
                cfg.internalPackagePrefixes.none { activeRootPkg.startsWith(it) } &&
                activeRootPkg != "com.bbncbot" &&
                activeRootPkg != "android" &&
                activeRootPkg != "com.android.systemui"
            if (isForeignActiveApp && attempt >= 1) {
                Log.w(TAG, "processTask: foreign app '$activeRootPkg' in foreground, pressBack ineffective, forceKill + reopen farm")
                debugLog("processTask: 外来App'$activeRootPkg'前台且pressBack无效, forceKill它+深链重开农场")
                service.forceKillApp(activeRootPkg, pressBackFirst = false)
                service.reopenFarmByDeepLink(killCurrentFirst = false)
                handler.postDelayed({
                    if (state == AutomationState.PROCESSING_TASK) runProcessingTask(0)
                }, INTERVAL_PAGE_LOAD_MS)
                return
            }
            Log.w(TAG, "processTask: not on farm page, returning")
            service.pressBack()
            handler.postDelayed({
                if (state == AutomationState.PROCESSING_TASK) runProcessingTask(attempt + 1)
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 判断当前任务类型
        val button = taskButtons[currentTaskIndex]
        val buttonText = button.text?.toString().orEmpty()
        val btnRect = Rect()
        button.getBoundsInScreen(btnRect)
        debugLog("processTask: current task #${currentTaskIndex + 1}/${taskButtons.size}, text='$buttonText', bounds=${btnRect.toShortString()}, attempt=$attempt")

        // 1. 花钱任务：跳过不处理
        if (service.isPaidTask(button)) {
            Log.i(TAG, "processTask: task #${currentTaskIndex + 1} is paid task, skipping (text='$buttonText')")
            debugLog("processTask: skip paid task #$${currentTaskIndex + 1}, text='$buttonText'")
            currentTaskIndex++
            handler.postDelayed({
                if (state == AutomationState.PROCESSING_TASK) runProcessingTask(0)
            }, 500L)
            return
        }

        // 1a-bis. "下单"字眼任务一律跳过（用户明确要求）
        // 用户反馈："任意下单领大额肥料"这个任务为啥还点击，"下单"字眼任务都不点击
        // 注意：必须排除"下单得"（浏览搜索结果得肥料，无需下单），但"任意下单"/"下单领"/"下单赢"都要跳过
        // 同时检查 buttonText 和 taskContextText（任务标题在上下文中）
        val taskContextText = service.collectTaskContextText(button)
        val fullTaskText = "$buttonText $taskContextText"
        // 明确要求下单的文案（"下单"前后跟动词/量词，不是单独的"下单得"）
        val hasPaidOrderKeyword = fullTaskText.contains("任意下单") ||
            fullTaskText.contains("下单领") ||
            fullTaskText.contains("下单赢") ||
            fullTaskText.contains("下单返") ||
            fullTaskText.contains("下单得大额") ||
            fullTaskText.contains("下单购买") ||
            fullTaskText.contains("立即下单")
        if (hasPaidOrderKeyword) {
            Log.i(TAG, "processTask: task #${currentTaskIndex + 1} has paid order keyword (下单), skipping (text='$buttonText', context='$taskContextText')")
            debugLog("processTask: skip order task #$${currentTaskIndex + 1}, button='$buttonText', context='$taskContextText'")
            currentTaskIndex++
            handler.postDelayed({
                if (state == AutomationState.PROCESSING_TASK) runProcessingTask(0)
            }, 500L)
            return
        }

        // 1a. 已完成任务直接跳过
        // 用户反馈："(1/1)" "(2/2)" 这种 X/X 样式的任务已完成，不需要再点。
        // 任务列表里 "X/Y" 表示 "当前进度/总进度"，X==Y 说明已完成。
        // 匹配模式：(N/N) 或 （N/N），N 为正整数（也兼容无括号的 N/N 但要避免误匹配日期）
        // 注：taskContextText 已在 1a-bis 块声明，这里复用
        val completedRegex = Regex("""[(（](\d+)/\1[)）]""")
        val completedMatch = completedRegex.find(taskContextText) ?: completedRegex.find(buttonText)
        if (completedMatch != null) {
            Log.i(TAG, "processTask: task #${currentTaskIndex + 1} already completed (matched '${completedMatch.value}'), skipping (text='$buttonText', context='$taskContextText')")
            debugLog("processTask: skip completed task #$${currentTaskIndex + 1}, matched='${completedMatch.value}', context='$taskContextText'")
            currentTaskIndex++
            handler.postDelayed({
                if (state == AutomationState.PROCESSING_TASK) runProcessingTask(0)
            }, 500L)
            return
        }

        // 1a-ter. build754: 陷阱广告连续退出防护——连续 TRAP_AD_SKIP_THRESHOLD 次广告陷阱
        // 退出且期间无进展(collectedCount 不变)时,跳过视频类任务(看视频/看广告),改做其他任务。
        // debug_test_20260829_173308.log: 7 次快手互动陷阱广告 forceKill 退出后,任务列表每轮
        // 重置 currentTaskIndex=0 都从任务#1"看视频得巨额肥料"重新开始 → 死循环 3.5min 零收益,
        // 松鼠大战(+2400)/头条极速版(+684)等任务从未被尝试。有进展后视频任务自动恢复(广告池会轮换创意)。
        if (shouldSkipVideoAdEntries() && isVideoAdTask(fullTaskText)) {
            Log.i(TAG, "processTask: task #${currentTaskIndex + 1} is video-ad task, skipping (trap ad exit streak=$trapAdExitStreak, no progress)")
            debugLog("processTask: 连续${trapAdExitStreak}次陷阱广告退出无进展,跳过视频类任务'$taskContextText',改做其他任务")
            currentTaskIndex++
            handler.postDelayed({
                if (state == AutomationState.PROCESSING_TASK) runProcessingTask(0)
            }, 500L)
            return
        }

        // 1b. 跳过名单：特定任务直接跳过，不点击
        // 用户要求：过滤=不点击直接跳过
        // 同时检查按钮文本和任务上下文文本（任务标题在上下文中，不在按钮文本里）
        val skipTaskTexts = listOf(
            "继续玩浪漫餐厅", "继续玩农场分色瓶", "充值", "砸蛋", "砸金蛋",
            "分享", "合种", "到店支付",
            // 玩游戏类任务：自动化无法玩小程序游戏，跳过
            // build554 修复（用户反馈"试玩热门游戏也是可以完成的"）：
            // 移除"试玩"/"新游"——"试玩热门新游"任务实际可完成（访问必得肥料），
            // 不应跳过，应进入 GAME_PLAYING 流程尝试完成（isGameTask 含"游戏"关键词会识别）
            "玩游戏", "玩1局", "玩一局", "开局", "对战",
            "完成1局", "完成一局", "打一局",
            // build622 修复（用户反馈"三国冰河常规招募英雄1次，属于游戏任务，不需要完成"）：
            // "招募英雄"类任务需在游戏内真正完成"招募英雄N次"动作才能得肥料，
            // bot 无法在游戏内执行该操作（与"玩游戏"停留拿肥料不同，无法靠停留完成），
            // 直接跳过不点击。匹配"招募英雄"覆盖"三国冰河常规招募英雄1次"等所有变体。
            "招募英雄",
            // build650 修复（用户反馈"邀请好友助力任务，不做"）：
            // "邀请好友助力(0/3) 邀请成功得 +1000 去完成" 类社交任务需分享给好友/好友助力，
            // bot 无法自动完成（无社交账号、无法分享），直接跳过不点击。
            // 匹配"邀请好友"覆盖"邀请好友助力"等邀请类任务。
            // build669 修复（debug_test_20260731_211710.log, build666）：
            // 原 "助力" 关键词太宽泛,误匹配"看视频得巨额肥料 (0/10) 助力果树光速升级↑"里的
            // "助力果树光速升级"宣传语,导致"看视频得巨额肥料"任务被错误跳过。
            // 移除单独的"助力"关键词,"邀请好友"已足够覆盖所有邀请类任务。
            "邀请好友",
            // build651 修复（用户反馈"去逛逛趣头条任务，不需要做"）：
            // "去逛逛趣头条赚金币(0/1) 逛逛得 +300 去完成" 跳转到趣头条 App，
            // bot 无法在趣头条 App 内自动完成"逛逛赚金币"动作，直接跳过不点击。
            "趣头条",
            // build656 修复（debug_test_20260726_204010.log, build655-352d37d）：
            //   20:35:53.523 processTask: browse task #2, text='去完成', entering BROWSING_TASK
            //   task #2 文案 "去头条极速版逛逛 去头条极速版逛逛 本次可得 肥料 +684 去完成"
            //   被 isBrowseTask 误识别为浏览任务（含"逛逛"），但实际跳转到头条极速版 App
            //   (com.ss.android.article.lite)，bot 无法在外部 App 自动完成。
            //   日志：跳转后 forceKillApp 失败 → 反复重试 6 次 → 最终 STOPPING。
            // 修复：skipTaskTexts 新增"头条"关键词，在 isBrowseTask 之前命中直接跳过。
            //   "头条"覆盖"头条极速版"/"今日头条"等所有头条系任务。
            "头条"
        )
        // build530 修复（debug_test_20260719_045429.log, build530-9ab1929）：
        // 历史问题：【福利】试玩热门新游 访问必得500-3500肥 的"领取"按钮被 skipTaskTexts
        // 中的"试玩"/"新游"关键词跳过，导致 pure claim 路径没机会执行，肥料没领取。
        // 日志证据：
        //   sortTaskButtons: idx=3 task='' priority=0 (pureClaim=true, button='领取')
        //   processTask: current task #1/5, text='领取', context='【福利】试玩热门新游 访问必得500-3500肥(0/3500) ...'
        //   processTask: skip list task #1, text='领取', context='...'  ← 被 skipTaskTexts 跳过！
        //   processTask: all ads/tasks done on ALIPAY, collected=0  ← 没拿到肥料
        //
        // 修复：pure claim 按钮（领取/收下/立即领取等，且非付费任务）优先于 skipTaskTexts，
        // 不被"试玩"/"新游"等关键词跳过。pure claim 按钮点击即得肥料，与任务文案无关。
        val pureClaimEarlyTexts = setOf(
            "领取", "收下", "立即领取", "点击领取", "开心收下",
            "立即领肥", "领取肥料"
        )
        val isPureClaimEarly = buttonText in pureClaimEarlyTexts && !fullTaskText.let { ft ->
            listOf("退款", "扣回", "扣减", "下单后", "购买后", "充值后", "消费满",
                "任意下单", "下单领", "下单赢", "任意充值").any { ft.contains(it) }
        }
        val shouldSkip = !isPureClaimEarly &&
            skipTaskTexts.any { buttonText.contains(it) || taskContextText.contains(it) }
        if (shouldSkip) {
            Log.i(TAG, "processTask: task #${currentTaskIndex + 1} in skip list, skipping (text='$buttonText', context='$taskContextText')")
            debugLog("processTask: skip list task #${currentTaskIndex + 1}, text='$buttonText', context='$taskContextText'")
            currentTaskIndex++
            handler.postDelayed({
                if (state == AutomationState.PROCESSING_TASK) runProcessingTask(0)
            }, 500L)
            return
        }

        // 2. 游戏类任务：打开游戏停留玩一下即可获取肥料（无法实现过关卡，仅靠停留拿奖励）
        // P0-D（build520 修复）：纯"领取"/"收下"按钮是 easyClaim（点击即得肥料），
        // 必须在 isGameTask 之前短路，避免 contextText 含"蚂蚁庄园"/"蚂蚁森林"等
        // 游戏关键词时被误判为 game task，导致进入 GAME_PLAYING 反而关闭了任务列表弹窗。
        //
        // 历史问题（debug_test_20260718_211741.log, build518-93f7a54）：
        // - 蚂蚁庄园浏览任务（buttonText='领取', contextText='逛一逛蚂蚁庄园...限时加倍浏览即得500肥'）
        // - isGameTask 检测 contextText 含"蚂蚁庄园" → 返回 true
        // - 进入 GAME_PLAYING 后点击了任务列表弹窗的"关闭"按钮，关闭了任务列表而非领取肥料
        // - 实际"领取"按钮就是直接点击领取，无需进入游戏流程
        //
        // 修复：buttonText 精确等于"领取"/"收下" 且无付费暗示 → 直接点击领取，
        // 等待领取结果后回 OPENING_TASK_LIST（与 isPaidTask 跳过路径的等待时长保持一致）
        //
        // build521 增强（用户反馈："显示'肥料已发放'，就应该返回之前窗口"）：
        // 点击"领取"后弹窗会显示"肥料已发放"领取成功提示，此时必须 pressBack 关闭弹窗
        // 返回任务列表，而不是直接回 OPENING_TASK_LIST 走"找任务列表按钮"路径
        // （弹窗未关闭时 findGoCompleteButtons 找不到"去完成"按钮，会误判需要重新打开任务列表）
        //
        // build523 修复（debug_test_20260718_214200.log, build521-c2f9e26）：
        // - 日志发现 task #1/1 buttonText='立即领取' bounds=[278,1660][923,1807]
        // - isPureClaimClick 只匹配 '领取'/'收下'，'立即领取' 不匹配 → 没走 pureClaimClick 路径
        // - 走了标准 checkTaskResult 路径，点击了左上角"返回首页"按钮（desc='返回' bounds=[26,127]）
        // - 返回到支付宝首页，而不是 pressBack 关闭"肥料已发放"弹窗返回任务列表
        // - 虽然 task #1/1 实际已领取成功（第二轮任务列表里"立即领取"消失了），
        //   但 collected=0 没正确统计，且多走了"返回首页→重新进任务列表"的弯路
        //
        // 修复：扩展 isPureClaimClick 匹配所有 Platform.goCompleteTexts/directCollectTexts 里
        // 定义的纯领取按钮文案（立即领取/点击领取/开心收下/立即领肥/领取肥料 等）
        // 这些按钮都是"点击即得肥料，无需跳转"的纯领取入口，应走 pureClaimClick 路径
        val pureClaimButtonTexts = setOf(
            "领取", "收下", "立即领取", "点击领取", "开心收下",
            "立即领肥", "领取肥料", "立即领肥[料]"  // 支付宝按钮文案可能带方括号占位
        )
        val isPureClaimClick = buttonText in pureClaimButtonTexts && !fullTaskText.let { ft ->
            // 复用 paidHintKeywords 判断，避免重复声明
            listOf("退款", "扣回", "扣减", "下单后", "购买后", "充值后", "消费满",
                "任意下单", "下单领", "下单赢", "任意充值").any { ft.contains(it) }
        }
        if (isPureClaimClick) {
            Log.i(TAG, "processTask: task #${currentTaskIndex + 1} is pure claim button, clicking to claim (text='$buttonText', context='${taskContextText.take(60)}')")
            debugLog("processTask: pure claim task #${currentTaskIndex + 1}, button='$buttonText', direct click to claim fertilizer")
            service.performClickSafe(button)
            // build524 修复：pureClaimClick 后不 currentTaskIndex++，重置为 0 让下一轮重新扫描
            // 不会死循环：每次 pureClaimClick 后任务列表都会变化（"领取"按钮消失或变成"已领取"）
            currentTaskIndex = 0
            collectedCount++
            // 等 2 秒让"肥料已发放"弹窗渲染（点击后弹窗需要短暂时间出现）
            handler.postDelayed({
                if (state != AutomationState.PROCESSING_TASK) return@postDelayed
                // 检测"肥料已发放"/"领取成功"等领取到账提示弹窗
                if (service.isFertilizerGrantedPage()) {
                    debugLog("processTask: pure claim success (肥料已发放 detected), pressing back to return to task list")
                    service.pressBack()
                    // pressBack 后等待弹窗动画关闭，再回 OPENING_TASK_LIST
                    handler.postDelayed({
                        if (state == AutomationState.PROCESSING_TASK) {
                            moveTo(AutomationState.OPENING_TASK_LIST)
                            handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                        }
                    }, INTERVAL_CLICK_MS)
                } else {
                    // build525 修复（debug_test_20260719_031802.log, build524-4a224b1）：
                    // 历史问题：build524 的"未检测到弹窗也 pressBack"修复反而有害：
                    // - 实际点击"领取"后肥料可能直接到账，没有弹窗
                    // - pressBack 反而退出了芭芭农场小程序，回到支付宝主页 AlipayLogin
                    // - 支付宝主页的"消息盒子"等元素被 findGoCompleteButtons 误识别为 taskButton
                    // - bounds=[26,1872][1174,2031] 是主页元素，不是任务按钮
                    // - processTask 处理这个无效 taskButton 浪费时间，最终 launcher 占屏
                    //
                    // 修复：撤销 build524 的"未检测到弹窗也 pressBack"，改回 build521 的逻辑：
                    // 未检测到弹窗直接回 OPENING_TASK_LIST，不 pressBack
                    // （领取直接到账时不会遮挡任务列表，无需 pressBack）
                    debugLog("processTask: pure claim but no 肥料已发放 popup detected (fertilizer may have been credited directly), returning to OPENING_TASK_LIST without pressBack")
                    moveTo(AutomationState.OPENING_TASK_LIST)
                    handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                }
            }, INTERVAL_CLICK_MS)
            return
        }

        if (service.isGameTask(button)) {
            // build556 修复（用户反馈"试玩热门新游，需要等待10分钟退出"）：
            // "试玩热门新游"等访问时长类任务需在游戏页停留 10 分钟才得肥料，
            // 不是普通游戏 30 秒停留即可。这类任务文案含"试玩"/"新游"关键词。
            // 用 gamePlayingStayTargetMs 区分：
            // - 含"试玩"/"新游" → 10 分钟
            // - 其他普通游戏 → 30 秒（GAME_STAY_TARGET_MS）
            val isTrialPlayTask = fullTaskText.contains("试玩") || fullTaskText.contains("新游")
            // build647 修复（用户需求："游戏闯关类的游戏不完成"）：
            // 闯关类游戏（消消乐/斗地主/对战/通关等）需要实际操作游戏才能完成，
            // bot 只能停留无法操作，任务不会完成，肥料得不到，浪费时间。
            // 试玩类游戏（"试玩"/"新游"）只需打开停留即可，bot 可以完成。
            // 因此：非试玩的闯关类游戏任务直接跳过，不进入 GAME_PLAYING。
            if (!isTrialPlayTask && service.isGameLevelTask(button)) {
                Log.i(TAG, "processTask: task #${currentTaskIndex + 1} is level game (cannot auto-complete), skipping (text='$buttonText')")
                debugLog("processTask: skip level game task #${currentTaskIndex + 1}, text='$buttonText'")
                currentTaskIndex++
                handler.postDelayed({
                    if (state == AutomationState.PROCESSING_TASK) runProcessingTask(0)
                }, 500L)
                return
            }
            gamePlayingStayTargetMs = if (isTrialPlayTask) 10 * 60 * 1000L else GAME_STAY_TARGET_MS
            Log.i(TAG, "processTask: task #${currentTaskIndex + 1} is game task, entering GAME_PLAYING (text='$buttonText', stayTarget=${gamePlayingStayTargetMs}ms, trialPlay=$isTrialPlayTask)")
            debugLog("processTask: game task #${currentTaskIndex + 1}, text='$buttonText', trialPlay=$isTrialPlayTask, stayTargetMs=$gamePlayingStayTargetMs")
            // 点击"去完成"进入游戏
            service.performClickSafe(button)
            // build529：进入游戏时重置 AI 视觉进度识别节流（每个新任务独立计数）
            lastAiProgressCheckMs = 0L
            moveTo(AutomationState.GAME_PLAYING)
            handler.postDelayed({ runGamePlaying(elapsedMs = 0L) }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // build556：试玩热门新游任务（含"试玩"/"新游"关键词，但不是 isGameTask）
        // 也按 10 分钟停留处理。文案如"【福利】试玩热门新游 访问必得500 - 3500肥"
        if (fullTaskText.contains("试玩") || fullTaskText.contains("新游")) {
            gamePlayingStayTargetMs = 10 * 60 * 1000L
            Log.i(TAG, "processTask: task #${currentTaskIndex + 1} is trial-play task, entering GAME_PLAYING (text='$buttonText', stayTarget=${gamePlayingStayTargetMs}ms)")
            debugLog("processTask: trial-play task #${currentTaskIndex + 1}, text='$buttonText', context='$taskContextText', stayTargetMs=$gamePlayingStayTargetMs")
            service.performClickSafe(button)
            lastAiProgressCheckMs = 0L
            moveTo(AutomationState.GAME_PLAYING)
            handler.postDelayed({ runGamePlaying(elapsedMs = 0L) }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 2b. 蚂蚁森林领落叶肥料任务：关弹窗→领奖励→逛农场得落叶肥料
        // P1-1（build513 修复）：clickable 父节点下 buttonText 可能为空，
        // 必须同时检查 taskContextText（任务标题在上下文中）。
        if (buttonText.contains("森林") || buttonText.contains("落叶") ||
            taskContextText.contains("森林") || taskContextText.contains("落叶")) {
            Log.i(TAG, "processTask: task #${currentTaskIndex + 1} is forest task, entering FOREST_COLLECTING (text='$buttonText', context='$taskContextText')")
            debugLog("processTask: forest task #${currentTaskIndex + 1}, text='$buttonText', will close popups → claim reward → 逛农场得落叶肥料")
            service.performClickSafe(button)
            moveTo(AutomationState.FOREST_COLLECTING)
            handler.postDelayed({ runForestCollecting(step = 0, retryCount = 0) }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 3. 跨平台切换任务：在支付宝/淘宝/UC 之间切换获取肥料
        // P1-2（build513 修复）：clickable 父节点下 buttonText 可能为空，
        // 跨平台任务关键词（"淘宝"/"支付宝"/"UC"）在任务标题（上下文）中，
        // 必须同时检查 taskContextText。
        val crossTarget = detectCrossPlatformTarget(buttonText + " " + taskContextText)
        if (crossTarget != null && crossTarget != service.currentPlatform) {
            Log.i(TAG, "processTask: task #${currentTaskIndex + 1} is cross-platform task, switching to $crossTarget (text='$buttonText')")
            debugLog("processTask: cross-platform task #${currentTaskIndex + 1}, text='$buttonText', from=${service.currentPlatform}, to=$crossTarget")
            switchOriginalPlatform = service.currentPlatform
            switchTargetPlatform = crossTarget
            switchStage = "LAUNCH_TARGET"
            switchRetryCount = 0
            // 先点击"去完成"按钮（部分任务点击后会自动跳转到目标平台）
            service.performClickSafe(button)
            moveTo(AutomationState.SWITCHING_PLATFORM)
            handler.postDelayed({ runSwitchingPlatform() }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 4. 滑动浏览任务：模拟滑动而非点击进入
        if (service.isBrowseTask(button)) {
            Log.i(TAG, "processTask: task #${currentTaskIndex + 1} is browse task, swiping (text='$buttonText')")
            debugLog("processTask: browse task #${currentTaskIndex + 1}, text='$buttonText', entering BROWSING_TASK")
            moveTo(AutomationState.BROWSING_TASK)
            handler.postDelayed({ runBrowsingTask(swipeCount = 0) }, INTERVAL_CLICK_MS)
            return
        }

        // 3. 普通任务（看广告、答题、签到等）：点击按钮
        Log.i(TAG, "processTask: clicking task #${currentTaskIndex + 1}/${taskButtons.size} (attempt ${attempt + 1})")
        currentTaskFailCount = 0 // 新任务开始，重置失败计数
        // build610: 标记当前任务是否为答题任务。
        // 答题页是 H5/Canvas 绘制，无障碍树抓不到问题+选项文本，isQuizPage() 返回 false，
        // checkTaskResult 中需要用 AI 视觉接口截图识别答题页并选出正确答案。
        currentTaskIsQuiz = buttonText.contains("答题") || taskContextText.contains("答题") || taskContextText.contains("问答")
        if (currentTaskIsQuiz) {
            debugLog("processTask: quiz task detected (button='$buttonText', context contains 答题/问答)")
        }
        // 解析任务剩余次数（如 "1/3" → 还可重玩 2 次），仅首次点击时解析
        if (attempt == 0 && taskReplayRemaining == 0) {
            taskReplayRemaining = parseTaskRemainingCount(buttonText, taskContextText)
            if (taskReplayRemaining > 0) {
                debugLog("processTask: multi-click task detected, remainingReplays=$taskReplayRemaining")
            }
        }
        // build737: 点击前从任务文案解析深链任务停留时长（如"浏览15秒得1000肥料"→15s+5s缓冲）。
        // 若点击后深链跳转到其它App，等够该时长再保留现场切回农场（见 runWatchingAd 深链分支）。
        deepLinkTaskStayMs = parseDeepLinkStayMs(fullTaskText)
        // build747: 快照当前任务上下文，供 checkTaskResult 判断任务类型（签到误判守卫等）
        currentTaskContextText = taskContextText
        // build758: 记录点击时 activity，checkTaskResult 判"点击无效果"时要求未变
        // （activity 变化 = 页面已切换 = 点击有效果，如"玩松鼠大战"H5游戏页打开）
        taskClickActivityName = service.getCurrentActivityName()
        service.performClickSafe(button)

        // 等待检测是否进入广告
        handler.postDelayed({
            if (state == AutomationState.PROCESSING_TASK) checkTaskResult(service, attempt)
        }, INTERVAL_PAGE_LOAD_MS)
    }

    // ============== 阶段3b: 滑动浏览任务 ==============

    /**
     * 滑动浏览任务：模拟上下滑动浏览页面获取肥料
     * - 不点击进入商品页面，只在当前页面上下滑动
     * - 在屏幕中部轻微上下交替滑动（避免一直向下滑动超出页面）
     * - 滑动足够次数后关闭并返回任务列表
     */
    private fun runBrowsingTask(swipeCount: Int) {
        if (state != AutomationState.BROWSING_TASK) return
        val service = getService() ?: run { stop(); return }

        if (swipeCount == 0) {
            logPageSnapshot(service, "browseTask-start")
            // 重置红包弹窗关闭计数器（新的浏览任务开始）
            browseRedPacketCloseAttempts = 0
            // build584: 重置小说阅读任务标记（新一轮浏览任务开始）
            browsingNovelStarted = false
            browsingNovelEnteredContent = false
            // build590: 重置短剧观看任务标记（新一轮浏览任务开始）
            browsingShortDramaStarted = false
            // build620: 重置浏览商品任务标记（新一轮浏览任务开始）
            browsingProductEntered = false
            // 第一步：点击"去完成"按钮进入浏览页面
            val button = taskButtons.getOrNull(currentTaskIndex)
            if (button == null) {
                debugLog("browseTask: button gone, back to processing")
                // 从 direct 弹窗进入的浏览：节点失效回 COLLECTING_DIRECT
                val fromDirect = browseFromDirectPopup
                browseFromDirectPopup = false
                if (fromDirect) {
                    debugLog("browseTask: was from direct popup, returning to COLLECTING_DIRECT")
                    moveTo(AutomationState.COLLECTING_DIRECT)
                    handler.postDelayed({ runCollectingDirect(attempt = 0) }, INTERVAL_CLICK_MS)
                } else {
                    moveTo(AutomationState.PROCESSING_TASK)
                    handler.postDelayed({ runProcessingTask(0) }, INTERVAL_CLICK_MS)
                }
                return
            }
            debugLog("browseTask: clicking 'go browse' button, then will swipe")
            service.performClickSafe(button)
            // 等待页面加载后，先点击一个商品再开始滑动
            handler.postDelayed({
                if (state == AutomationState.BROWSING_TASK) {
                    // 诊断日志：点击"去完成"后页面到底变成了什么
                    val pageType = service.getPageType()
                    val onFarm = service.isOnFarmPage()
                    val allText = service.collectAllTextSnapshot(maxCount = 15)
                    debugLog("browseTask: after clicking 'go browse', page type=$pageType, onFarm=$onFarm, texts=$allText")
                    // build648 修复（debug_test_20260726_164258.log）：
                    // 历史问题: "去闲鱼币领现金红包 逛逛得" 任务点击"去完成"后跳转到闲鱼 App
                    // （com.taobao.idlefish），page type=unknown(no_root)，bot 走到 else 分支
                    // （无浏览奖励指标），调用 exitBrowsePage → pressBack，但闲鱼 App 的 pressBack
                    // 只是在闲鱼内部返回，无法回到淘宝，导致 bot 卡在闲鱼 App 循环 pressBack。
                    // 日志证据:
                    //   16:41:52.929 browseTask: clicking 'go browse' button
                    //   16:41:57.943 browseTask: after clicking 'go browse', page type=unknown(no_root), onFarm=false, texts=[]
                    //   16:41:57.986 browseTask: not a browse task, exiting without swiping
                    //   16:42:03.445 exitBrowsePage: not on farm page after exit, re-navigating
                    //   16:42:06.063 [navigate-start] snapshot: pkg=com.taobao.idlefish ← 跳转到闲鱼
                    //   16:42:06~16:42:50 在闲鱼 App 循环 pressBack 无法返回淘宝（用户手动停止）
                    // 修复: 点击"去完成"后若当前包名不是农场平台包名（跨 App 跳转），
                    // 直接 pressBack 退出跨 App，跳过此任务，避免卡死。
                    val currentPkg = service.getCurrentWindowPackage()
                    val farmPkg = service.currentPlatformConfig().packageNames
                    val isCrossAppJump = currentPkg != null && farmPkg.isNotEmpty() &&
                        !farmPkg.any { currentPkg == it || currentPkg.startsWith(it) }
                    // build664 修复（debug_test_20260730_074150.log, build663-7e826b1）：
                    //   07:41:29.399 browseTask: after clicking 'go browse', page type=non_farm, onFarm=false
                    //   07:41:29.406 browseTask: cross-app jump detected (currentPkg=com.taobao.taobao, farmPkg=[com.ucmobile.lite])
                    //   → UC 农场浏览任务会跳转到淘宝（UC 和淘宝共种一棵树），页面含"浏览得奖励, 下单得奖励, 30秒"
                    //   → 这是正常浏览任务页，不是恶意跨 App 跳转（如闲鱼）
                    //   → 误判后 pressBack 退出 → re-launch UC 失败 → NAVIGATING -> STOPPING
                    // 根因：跨 App 检测仅看包名，未判断跳转后的页面是否是浏览任务页。
                    // 修复：跨 App 检测前，先判断页面是否是浏览任务页（含"浏览得奖励"/"浏览得"且含"秒"倒计时）。
                    //   若是浏览任务页，不视为恶意跨 App 跳转，继续走浏览流程（滑动等待完成）。
                    //   区分依据：build648 的闲鱼跳转页面无"浏览得奖励"文案，本次淘宝浏览页有。
                    val isBrowseTaskPageOnCrossApp = isCrossAppJump && allText.any { it.contains("浏览得") || it.contains("浏览 得") } &&
                        allText.any { Regex("\\d+\\s*秒").containsMatchIn(it) }
                    if (isCrossAppJump && !isBrowseTaskPageOnCrossApp) {
                        debugLog("browseTask: cross-app jump detected after clicking '去完成' (currentPkg=$currentPkg, farmPkg=$farmPkg), skipping task and pressing back")
                        Log.i(TAG, "browseTask: cross-app jump to $currentPkg, skipping task")
                        currentTaskIndex++
                        collectedCount++
                        // 先 pressBack 退出跨 App，再导航回农场
                        service.pressBack()
                        handler.postDelayed({
                            if (state != AutomationState.BROWSING_TASK) return@postDelayed
                            service.pressBack()  // 再按一次确保退出跨 App
                            handler.postDelayed({
                                if (state != AutomationState.BROWSING_TASK) return@postDelayed
                                // 强制 kill 跨 App 并重新启动农场平台
                                debugLog("browseTask: re-launching farm platform after cross-app jump")
                                service.launchPlatformApp(service.currentPlatform)
                                handler.postDelayed({
                                    if (state != AutomationState.BROWSING_TASK) return@postDelayed
                                    moveTo(AutomationState.NAVIGATING)
                                    handler.postDelayed({ runNavigating(0) }, INTERVAL_PAGE_LOAD_MS)
                                }, INTERVAL_PAGE_LOAD_MS)
                            }, INTERVAL_PAGE_LOAD_MS)
                        }, INTERVAL_CLICK_MS)
                        return@postDelayed
                    }
                    // build665 修复（debug_test_20260730_074843.log, build664-1ed8067）：
                    //   07:47:53.072 browseTask: after clicking 'go browse', page type=non_farm, onFarm=false
                    //   07:47:53.125 browseTask: no swipe hint and no browse reward indicator, not a browse task, exiting without swiping
                    //   → UC 浏览任务跳转淘宝后，findSwipeForFertilizerHint/findBrowseProgressInfo 等用 getRootInFarmApp()
                    //   → 淘宝包名不在 UC 的 packageNames 里 → getRootInFarmApp() 返回 null → 4 指标全 false → 误判退出
                    // 根因：跨 App 浏览任务页（UC→淘宝）的 hint 检测依赖 getRootInFarmApp()，但跨 App 后取不到窗口。
                    // 修复：build664 已识别为跨 App 浏览任务页（isBrowseTaskPageOnCrossApp=true，跳过恶意跳转退出），
                    //   此处从已采集的 allText 中解析"N秒"倒计时，设置 browseTaskTargetSwipes 后直接开始滑动，
                    //   不依赖 findSwipeForFertilizerHint（它因 getRootInFarmApp 返回 null 无法工作）。
                    if (isCrossAppJump && isBrowseTaskPageOnCrossApp) {
                        val secondsMatch = allText.firstNotNullOfOrNull { text ->
                            Regex("(\\d+)\\s*秒").find(text)?.groupValues?.get(1)?.toIntOrNull()
                        } ?: 0
                        val targetSec = if (secondsMatch in 1..300) secondsMatch else 30  // 兜底 30 秒
                        browseTaskTargetSwipes = (targetSec / (BROWSE_SWIPE_INTERVAL_MS / 1000)).toInt() + 2
                            .coerceIn(3, 30)
                        debugLog("browseTask: cross-app browse task page detected (UC→$currentPkg), target swipes=$browseTaskTargetSwipes (seconds=$targetSec)")
                        Log.i(TAG, "browseTask: cross-app browse task page (UC→$currentPkg), swiping $browseTaskTargetSwipes times")
                        handler.postDelayed({
                            if (state == AutomationState.BROWSING_TASK) runBrowsingTask(1)
                        }, INTERVAL_PAGE_LOAD_MS)
                        return@postDelayed
                    }
                    // build625 修复：先检测商品列表页，若是则走商品浏览流程（点商品+停留15秒）。
                    // 日志 debug_test_20260726_081800.log 显示 idx=0 "发现精选好物(0/4) 浏览15秒得 +700"
                    // 任务点击"去完成"后落地页是商品列表页（含"滑动浏览得肥料"+商品价格列表），
                    // 但 findSwipeForFertilizerHint 因无"N秒"返回 0，4 个指标全 false → "not a browse task" 退出，
                    // 导致 build620 的 isBrowseProductListPage 检测（在 swipeCount>0 分支）根本没机会执行。
                    // 修复：在 swipeCount=0 分支优先检测 isBrowseProductListPage，若是则直接点商品进入详情页停留。
                    if (service.isBrowseProductListPage()) {
                        // build629: 区分平台
                        // - TAOBAO: 点击商品进入详情页停留 15 秒（任务要求"浏览商品"）
                        // - UC: 滑动浏览任务不需要点击商品，直接在列表页滑动即可（用户明确反馈）
                        //         不设 browsingProductEntered，走普通滑动流程，靠 isFertilizerGrantedPage/isTaskCompletePage 退出
                        if (service.currentPlatform == Platform.TAOBAO) {
                            debugLog("browseTask: browse product list page detected on swipeCount=0 (TAOBAO), clicking product to enter detail (will wait 15s)")
                            Log.i(TAG, "browseTask: browse product list page detected, clicking product to enter detail")
                            val productNode = service.findBrowseProductNode()
                            if (productNode != null) {
                                browsingProductEntered = true
                                browseTaskTargetSwipes = 8  // build620: 15秒 / 2秒 = 8 次滑动
                                debugLog("browseTask: browse product target swipes = 8 (15s / 2s interval)")
                                service.performClickSafe(productNode)
                                // build631 修复：点击商品后检测落地页是否是商品详情页
                                // 问题（日志 debug_test_20260726_094309.log 09:41:46）：
                                //   点击的商品可能是直播商品，进入的是直播页（含"直播中"/"宝贝讲解"）而非商品详情页，
                                //   或弹出阿里登录对话框（auprogressdialog），导致 8 次滑动无效，退出后落地页是直播页。
                                // 修复：INTERVAL_PAGE_LOAD_MS 后检测 isProductDetailPageByAnyMeans，
                                //   若不是商品详情页，pressBack 退出，跳过此任务（不增加 collectedCount）。
                                handler.postDelayed({
                                    if (state != AutomationState.BROWSING_TASK) return@postDelayed
                                    if (service.isProductDetailPageByAnyMeans()) {
                                        debugLog("browseTask: entered product detail page after clicking product, starting swipes")
                                        runBrowsingTask(1)
                                    } else {
                                        // build648 修复（debug_test_20260726_164258.log）：
                                        // 历史问题: "去省钱卡领红包 浏览5s得" 任务落地页是百亿补贴活动页
                                        // （含"滑动浏览得肥料"+商品价格列表），被 isBrowseProductListPage 误判为商品列表页，
                                        // 点击商品后进入活动详情页（activity=TMSActivity，不是商品详情页），
                                        // bot 立即跳过任务，但实际"浏览5s得"任务只需在活动页停留 5 秒。
                                        // 日志证据:
                                        //   16:41:25.036 browseTask: browse product list page detected, clicking product
                                        //   16:41:31.716 browseTask: not on product detail page (activity=TMSActivity), skipping task
                                        //   ← 6 秒后跳过任务，但"浏览5s得"只需 5 秒停留
                                        // 修复: 未进入商品详情页时，不立即跳过，而是等待 5 秒（短时浏览任务可能只需停留），
                                        // 然后退出并尝试领取肥料。只有明确是直播页/登录对话框才跳过。
                                        val allTextAfterClick = service.collectAllTextSnapshot(maxCount = 20)
                                        val isLivePage = allTextAfterClick.any {
                                            it.contains("直播中") || it.contains("宝贝讲解") || it.contains("直播间")
                                        }
                                        val isLoginDialog = service.getCurrentActivityName()?.lowercase()?.contains("auprogressdialog") == true ||
                                            allTextAfterClick.any { it.contains("登录") && it.contains("淘宝") }
                                        if (isLivePage || isLoginDialog) {
                                            debugLog("browseTask: not on product detail page (live=$isLivePage, login=$isLoginDialog), skipping task")
                                            browsingProductEntered = false
                                            currentTaskIndex++
                                            exitBrowsePage(service, reason = "not_product_detail_after_click")
                                        } else {
                                            // 活动详情页/其他页面：等待 5 秒后退出（短时浏览任务）
                                            debugLog("browseTask: not on product detail page but not live/login (activity=${service.getCurrentActivityName()}), waiting 5s for short browse task")
                                            Log.i(TAG, "browseTask: activity page detected, waiting 5s for short browse task")
                                            browsingProductEntered = true
                                            browseTaskTargetSwipes = 3  // 5s / 2s ≈ 3 次轮询
                                            runBrowsingTask(1)
                                        }
                                    }
                                }, INTERVAL_PAGE_LOAD_MS)
                                return@postDelayed
                            }
                            debugLog("browseTask: browse product list page but no product node found, swiping in list directly")
                            browsingProductEntered = true
                            browseTaskTargetSwipes = 8
                        } else {
                            // build629: UC 平台不点商品，直接在列表页滑动
                            debugLog("browseTask: browse product list page detected on swipeCount=0 (${service.currentPlatform}), swiping in list directly (no product click)")
                        }
                    }
                    // build643 修复（debug_test_20260726_153127.log）：
                    // 历史问题: "看严选推荐商品(0/5) 浏览得奖励 +600" 任务点击"去完成"后，
                    // 落地页是商品详情页（activity=newdetailactivity，含视频播放器），
                    // 不是商品列表页（isBrowseProductListPage=false，因为无"加入购物车"+"立即购买"且无"¥"价格）。
                    // 之后 findSwipeForFertilizerHint 返回 0（"滑动浏览得肥料"无具体秒数），
                    // 4 个指标全 false → "not a browse task" → 立即退出（0 秒等待）。
                    // 用户需求: "看严选推荐商品，去完成，打开的看视频任务，需要看够15秒后才能得肥料"
                    // 修复: 点击"去完成"后若落地页是商品详情页（isProductDetailPageByAnyMeans），
                    // 或视频页（含"视频，按钮"文案），视为看视频任务，等待 15 秒后退出领肥料。
                    // 日志证据:
                    //   15:27:07 page type=browse_duration, texts=[..., 滑动浏览得肥料, 视频，按钮。双击可暂停或播放视频。, ...]
                    //   15:27:07 no swipe hint and no browse reward indicator → exiting without swiping
                    //   ← 0 秒等待，肥料未获得
                    if (service.isProductDetailPageByAnyMeans()) {
                        debugLog("browseTask: landed on product detail page after clicking '去完成' (likely video task), waiting 15s before exit")
                        Log.i(TAG, "browseTask: product detail page detected (video task), waiting 15s")
                        browsingProductEntered = true
                        browseTaskTargetSwipes = 8  // 15s / 2s = 8 次滑动轮询
                        handler.postDelayed({
                            if (state == AutomationState.BROWSING_TASK) runBrowsingTask(1)
                        }, INTERVAL_PAGE_LOAD_MS)
                        return@postDelayed
                    }
                    // 兜底: 检测视频页（含"视频，按钮"文案，可能 activity 名不含 detail 关键字）
                    val allTextForVideoCheck = service.collectAllTextSnapshot(maxCount = 30)
                    val isVideoPage = allTextForVideoCheck.any {
                        it.contains("视频，按钮") || it.contains("双击可暂停或播放视频")
                    }
                    if (isVideoPage) {
                        debugLog("browseTask: landed on video page after clicking '去完成' (video play task), waiting 15s before exit")
                        Log.i(TAG, "browseTask: video page detected (video play task), waiting 15s")
                        browsingProductEntered = true
                        browseTaskTargetSwipes = 8  // 15s / 2s = 8 次滑动轮询
                        handler.postDelayed({
                            if (state == AutomationState.BROWSING_TASK) runBrowsingTask(1)
                        }, INTERVAL_PAGE_LOAD_MS)
                        return@postDelayed
                    }
                    // 检测页面是否有"滑动获取肥料"提示，解析需要滑动的时间
                    val hintSeconds = service.findSwipeForFertilizerHint()
                    // build529（用户要求"全部实现"）：同时识别"已浏览15s"/"15/30秒"/"进度50%"等进度信息
                    // 用于动态调整滑动次数（如果 total 已知，按剩余秒数计算更精确的 swipe 上限）
                    val progressInfo = service.findBrowseProgressInfo()
                    if (progressInfo.isFound) {
                        debugLog("browseTask: found progress info type=${progressInfo.type}, " +
                            "cur=${progressInfo.current}, tot=${progressInfo.total}, " +
                            "percent=${progressInfo.percent}%, remaining=${progressInfo.remainingSeconds}s, " +
                            "raw='${progressInfo.rawText.take(60)}'")
                    }
                    if (hintSeconds > 0) {
                        debugLog("browseTask: found swipe hint, need $hintSeconds seconds")
                        // 根据提示时间计算滑动次数：每次滑动间隔2秒，额外加2次余量
                        val requiredSwipes = (hintSeconds / (BROWSE_SWIPE_INTERVAL_MS / 1000)).toInt() + 2
                        browseTaskTargetSwipes = requiredSwipes.coerceAtLeast(3).coerceAtMost(30)
                        debugLog("browseTask: target swipes = $browseTaskTargetSwipes (hint=$hintSeconds seconds)")
                    } else if (progressInfo.type == FarmAccessibilityService.ProgressType.FRACTION && progressInfo.total > 0) {
                        // build529：没有"滑动浏览x秒"提示，但有"已浏览 15/30秒"型进度 →
                        // 按剩余秒数计算滑动次数（每次滑动间隔2秒 + 2次余量）
                        val remaining = progressInfo.remainingSeconds
                        val requiredSwipes = (remaining / (BROWSE_SWIPE_INTERVAL_MS / 1000)).toInt() + 2
                        browseTaskTargetSwipes = requiredSwipes.coerceAtLeast(3).coerceAtMost(30)
                        debugLog("browseTask: no swipe hint but found fraction progress, " +
                            "target swipes = $browseTaskTargetSwipes (remaining=${remaining}s, " +
                            "cur=${progressInfo.current}/${progressInfo.total})")
                    } else {
                        // 没有找到"滑动浏览得肥料"提示：再检查其他浏览奖励指标
                        // （倒计时"再逛xx秒"、进度"每浏览x秒"、停留"浏览x分钟"）
                        // 若都没有，说明此任务被 isBrowseTask 误判为滑动任务，实际不是滑动任务 → 直接退出，不滑动
                        val hasCountdown = service.findBrowseRewardCountdownHint() > 0
                        val hasProgress = service.hasBrowseRewardProgressHint()
                        val hasDuration = service.findBrowseDurationRewardHint() > 0
                        // build529：findBrowseProgressInfo 识别到的进度也视作 browse reward 指标
                        // （例如"进度50%"等百分比型，避免被误判为 not_browse_task 而提前退出）
                        val hasProgressInfo = progressInfo.isFound
                        if (!hasCountdown && !hasProgress && !hasDuration && !hasProgressInfo) {
                            debugLog("browseTask: no swipe hint and no browse reward indicator (countdown=$hasCountdown, progress=$hasProgress, duration=$hasDuration, progressInfo=$hasProgressInfo), not a browse task, exiting without swiping")
                            currentTaskIndex++
                            collectedCount++
                            exitBrowsePage(service, reason = "not_browse_task")
                            return@postDelayed
                        }
                        browseTaskTargetSwipes = MAX_BROWSE_SWIPES
                        debugLog("browseTask: no swipe hint but has browse reward indicator (countdown=$hasCountdown, progress=$hasProgress, duration=$hasDuration, progressInfo=$hasProgressInfo), using default $browseTaskTargetSwipes swipes")
                    }
                    // 不主动点击商品进入详情页：用户要求浏览任务只在商品列表页滑动，
                    // 不要进入有"加入购物车"按钮的商品详情页
                    // （滑动循环中会检测是否意外进入详情页，若是则按返回退回列表页）
                    debugLog("browseTask: skipping product click, swiping in list page directly")
                    // 等待页面加载后开始滑动
                    handler.postDelayed({
                        if (state == AutomationState.BROWSING_TASK) runBrowsingTask(1)
                    }, INTERVAL_PAGE_LOAD_MS)
                }
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 优先检测：是否有红包弹窗 → 先关闭它，才能继续滑动获取肥料
        // 红包弹窗会遮挡页面，不关闭无法滑动；关闭后保持 swipeCount 重新进入
        // 防御：限制连续关闭次数，避免 findRedPacketCloseButton 误判导致死循环
        val redPacketBtn = if (browseRedPacketCloseAttempts < MAX_RED_PACKET_CLOSE_ATTEMPTS) {
            service.findRedPacketCloseButton()
        } else {
            // 超过阈值仍检测到"红包弹窗"：很可能是误判（如农场主页"钓红包"入口），
            // 不再当红包弹窗处理，继续走滑动逻辑
            if (swipeCount == 1 || browseRedPacketCloseAttempts == MAX_RED_PACKET_CLOSE_ATTEMPTS) {
                debugLog("browseTask: red packet close attempts exceeded ($browseRedPacketCloseAttempts), ignoring red packet detection and continuing swipe")
            }
            null
        }
        if (redPacketBtn != null) {
            browseRedPacketCloseAttempts++
            Log.i(TAG, "browseTask: red packet popup detected, closing it first (attempt $browseRedPacketCloseAttempts/$MAX_RED_PACKET_CLOSE_ATTEMPTS)")
            debugLog("browseTask: closing red packet popup before swiping (swipe #$swipeCount, attempt $browseRedPacketCloseAttempts/$MAX_RED_PACKET_CLOSE_ATTEMPTS)")
            val scheduleReentry = {
                // 等待弹窗关闭后重新进入（保持 swipeCount 不变，不消耗滑动次数）
                handler.postDelayed({
                    if (state == AutomationState.BROWSING_TASK) runBrowsingTask(swipeCount)
                }, INTERVAL_CLICK_MS)
            }
            service.performClickSafe(redPacketBtn)
            scheduleReentry()
            return
        }

        // 优先检测："浏览x分钟得xxx肥料"提示 → 停留等待，不滑动，直到"已完成"出现
        // 与"每浏览x秒可得1次奖励"（需滑动）不同，这类任务只需停留等待
        // 必须放在 isTaskCompletePage() 之前检测：因为"浏览5分钟得600肥料"页面可能同时
        // 包含"获得肥料"等文案，会被 isTaskCompletePage() 误判为已完成而提前退出
        val browseDurationSeconds = service.findBrowseDurationRewardHint()
        if (browseDurationSeconds > 0) {
            // 动态设置等待上限：提示秒数/2（每次等待2秒）+ 30次缓冲
            val durationWaitLimit = (browseDurationSeconds / (BROWSE_SWIPE_INTERVAL_MS / 1000)).toInt() + 30
            if (swipeCount > durationWaitLimit) {
                // 超过等待上限仍未出现"已完成"，强制退出（避免卡死）
                Log.w(TAG, "browseTask: browse duration wait timeout (swipes=$swipeCount, limit=$durationWaitLimit, duration=${browseDurationSeconds}s), exiting")
                debugLog("browseTask: duration wait timeout, exiting")
                currentTaskIndex++
                collectedCount++
                exitBrowsePage(service, reason = "timeout_duration_wait")
                return
            }
            // 在停留等待期间，用精确的完成检测（而非宽泛的 isTaskCompletePage）
            // 完成标志：页面出现"已完成"或"任务完成"，且不再显示"浏览x分钟"提示
            // 注意：findBrowseDurationRewardHint 已返回 >0 表示提示还在，所以这里一定不会退出
            // 只有当提示消失（文案变成"已完成"等）才会跳出此分支，进入下面的 isTaskCompletePage 检测
            Log.i(TAG, "browseTask: browse duration hint (${browseDurationSeconds}s), waiting without swiping (swipe #$swipeCount/$durationWaitLimit)")
            debugLog("browseTask: waiting for '已完成' (duration=${browseDurationSeconds}s, swipe #$swipeCount/$durationWaitLimit)")
            handler.postDelayed({
                if (state == AutomationState.BROWSING_TASK) runBrowsingTask(swipeCount + 1)
            }, BROWSE_SWIPE_INTERVAL_MS)
            return
        }

        // 优先检测：是否显示了"肥料已发放/已获得肥料"等奖励到账提示 → 任务完成，直接回农场主页
        // 注意：此检测用 rootInActiveWindowSafe，能覆盖 WebView 内的文案
        // isTaskCompletePage 故意排除了"获得肥料"关键词（进行中页面也会显示"已获得肥料 xxx"），
        // 但纯粹的"已获得肥料"（无数字后缀）是任务完成信号，这里单独检测
        // 直接走 RETURNING：reopenFarmByDeepLink 会 kill 目标 App + 用桌面快捷方式重开农场页，
        // 不依赖返回键（WebView 里返回键不可靠）
        //
        // build660 修复（debug_test_20260726_213808.log, build659-0b6d616）：
        //   21:36:53.723 browseTask: fertilizer granted detected during swipe, exiting via RETURNING
        //   21:36:56.358 reopenFarmByDeepLink: no deep link for ALIPAY, killed + relaunch app
        //   21:37:01.416 activeRootPkg='com.hihonor.android.launcher' (支付宝被 kill 后没启动到前台)
        //   21:37:02.499 navigate: farm app not in foreground → 反复搜索"芭芭农场"失败 → STOPPING
        // 根因：ALIPAY 无 farmDeepLink（=null），也无桌面快捷方式，reopenFarmByDeepLink 走 kill+relaunch
        // 主 Activity 分支。Honor 后台启动限制导致支付宝 relaunch 后没到前台（停在桌面 launcher）。
        // 但浏览页和农场页都是支付宝内 WebView（XRiverActivity），pressBack 就能退回农场主页。
        // 修复：对无 deep link 的平台（ALIPAY）走 exitBrowsePage（pressBack 退回），
        // 不走 RETURNING 的 kill+relaunch。有 deep link 的平台（UC/TAOBAO）仍走 RETURNING。
        if (service.isFertilizerGrantedPage()) {
            currentTaskIndex++
            collectedCount++
            browseFromSearchBrowse = false
            browseFromDirectPopup = false
            val hasDeepLink = !service.currentPlatformConfig().farmDeepLink.isNullOrEmpty()
            if (hasDeepLink) {
                debugLog("browseTask: fertilizer granted page detected, exiting via RETURNING (swipes=$swipeCount/$browseTaskTargetSwipes)")
                moveTo(AutomationState.RETURNING)
                handler.postDelayed({ runReturning(0) }, INTERVAL_CLICK_MS)
            } else {
                debugLog("browseTask: fertilizer granted page detected, but no deep link for ${service.currentPlatform}, exiting via pressBack (swipes=$swipeCount/$browseTaskTargetSwipes)")
                exitBrowsePage(service, reason = "fertilizer_granted_no_deep_link")
            }
            return
        }

        // 优先检测：是否已显示"任务完成" → 这是浏览任务的真正退出信号
        // UC 极速版等平台：滑动获取肥料，直到显示"任务完成"才退出（倒计时"再逛xx秒"只是过程提示）
        if (service.isTaskCompletePage()) {
            debugLog("browseTask: task complete detected, exiting (swipes=$swipeCount/$browseTaskTargetSwipes)")
            currentTaskIndex++
            collectedCount++
            exitBrowsePage(service, reason = "task_complete")
            return
        }

        // 已完成所有滑动次数：判断是否需要继续等待"任务完成"
        // 滑动次数达标后，在 waitLimit 内继续滑动等待"任务完成"出现
        // 原因：滑动后页面文案可能短暂消失导致进度提示检测失败，但任务实际还在进行
        // 只有超过 waitLimit 或检测到任务完成（上方 isTaskCompletePage）才退出
        //
        // build628 修复：browsingProductEntered=true 时（点击商品进入详情页停留 15 秒），
        // 商品详情页不会显示"任务完成"/"肥料发放"文案（页面是商品详情，不是任务页），
        // 上方 isFertilizerGrantedPage/isTaskCompletePage 永远不会命中。
        // 若走 waitLimit 会无限滑动 30 次（60 秒）直到超时，浪费时间且可能触发风控。
        // 修复：browsingProductEntered=true 时，swipeCount > browseTaskTargetSwipes 直接 pressBack 退出。
        // 退出后 runOpeningTaskList 会检测任务进度（如 2/4 → 3/4），若任务确实完成则继续下一个。
        if (browsingProductEntered && swipeCount > browseTaskTargetSwipes) {
            debugLog("browseTask: browsing product detail page reached target swipes ($swipeCount/$browseTaskTargetSwipes), pressing back to exit")
            currentTaskIndex++
            collectedCount++
            browsingProductEntered = false  // 复位
            browseFromSearchBrowse = false
            browseFromDirectPopup = false
            exitBrowsePage(service, reason = "browsing_product_target_reached")
            return
        }
        if (swipeCount > browseTaskTargetSwipes) {
            val countdownSeconds = service.findBrowseRewardCountdownHint()
            val hasProgressHint = service.hasBrowseRewardProgressHint()
            // build529：同时检测"已浏览15/30秒"等具体进度信息，若 remaining > 0 说明任务未完成
            val progressInfo = service.findBrowseProgressInfo()
            val hasRemainingProgress = progressInfo.isFound && progressInfo.remainingSeconds > 0
            val waitLimit = browseTaskTargetSwipes + MAX_BROWSE_WAIT_SWIPES
            // build669 修复（debug_test_20260731_211710.log, build666）：
            // UC"浏览广告赚肥料"跳转淘宝后,页面"浏览得奖励, 下单得奖励, 30秒"是静态文字,
            // 非动态倒计时。滑动 18 次后 countdown=0/progress=false/remainingProgress=false,
            // 三个信号都没有,说明浏览计时未触发（可能需要点击商品才触发,或需要下单才完成）。
            // 原逻辑继续滑到 waitLimit(48次/90秒)才退出,浪费时间。
            // 修复：滑动达标后,若三个进度信号全无,再给 3 次机会（约 6 秒）确认,仍无信号则退出跳过。
            // 不影响正常浏览任务（正常任务滑动后有动态倒计时/进度提示,hasProgressHint=true 会继续等待）。
            if (swipeCount > browseTaskTargetSwipes + 3 &&
                countdownSeconds == 0 && !hasProgressHint && !hasRemainingProgress) {
                Log.i(TAG, "browseTask: target swipes reached but no progress signals (countdown=0, progress=false, remaining=false), exiting (swipes=$swipeCount/$browseTaskTargetSwipes)")
                debugLog("browseTask: no progress signals after target swipes, exiting browse page (swipes=$swipeCount/$browseTaskTargetSwipes)")
                currentTaskIndex++
                collectedCount++
                exitBrowsePage(service, reason = "no_progress_signals")
                return
            }
            if (swipeCount <= waitLimit) {
                // 还在等待上限内，继续滑动等待"任务完成"出现
                Log.i(TAG, "browseTask: swipes reached target, keep waiting for task complete (countdown=${countdownSeconds}s, progressHint=$hasProgressHint, remainingProgress=$hasRemainingProgress, swipe #$swipeCount/$waitLimit)")
                debugLog("browseTask: keep swiping within wait limit (countdown=${countdownSeconds}s, progress=$hasProgressHint, remainingProgress=$hasRemainingProgress, swipe #$swipeCount/$waitLimit)")
                // 继续走下面的滑动逻辑（不 return）
            } else {
                debugLog("browseTask: wait limit exceeded (swipes=$swipeCount/$waitLimit, countdown=${countdownSeconds}s, progress=$hasProgressHint, remainingProgress=$hasRemainingProgress), exiting browse page")
                currentTaskIndex++
                collectedCount++
                exitBrowsePage(service, reason = "timeout_wait_limit")
                return
            }
        }

        // 滑动前检测：是否在异常页面（交易页面、商品详情页、收银台等）→ 立即退出
        // 禁止交易获取肥料：所有交易相关页面都视为异常
        // build620 豁免：browsingProductEntered=true 时,商品详情页是我们主动点击进入的,
        // 需要停留15秒等待肥料发放,不视为异常页退出。
        // build626 修复：原豁免用 isProductDetailPage()(内容检测"加入购物车"+"立即购买"),
        // 但淘宝商品详情页 activity=ttdetailactivity 触发 isOnAbnormalPage(true) 时,
        // 页面内容可能没有"加入购物车"/"立即购买"文案(H5/WebView 不暴露),导致豁免失效。
        // 改用 isProductDetailPageByAnyMeans()(activity 名 + 内容双重检测)。
        if (browsingProductEntered && service.isProductDetailPageByAnyMeans()) {
            debugLog("browseTask: in product detail page (browsingProductEntered=true), exempting from abnormal page check, keep waiting for fertilizer (swipe #$swipeCount/$browseTaskTargetSwipes)")
            // 不退出,继续走下面的滑动逻辑（滑动模拟活跃,等待 isFertilizerGrantedPage/isTaskCompletePage）
        } else if (service.isOnAbnormalPage()) {
            debugLog("browseTask: abnormal/trading page detected, exiting immediately")
            currentTaskIndex++
            collectedCount++
            exitBrowsePage(service, reason = "abnormal_page")
            return
        }

        // 滑动前检测：是否在付费搜索推荐页（"下单得肥料"等）→ 直接退出
        // 注意：UC 浏览任务有"再逛xx秒"倒计时或"每浏览x秒"进度提示时不属于此类，跳过本检测
        val hasCountdown = service.findBrowseRewardCountdownHint() > 0
        val hasProgressHint = service.hasBrowseRewardProgressHint()
        if (!hasCountdown && !hasProgressHint && service.isSearchRecommendPage()) {
            debugLog("browseTask: paid search recommend page detected, exiting without swiping")
            currentTaskIndex++
            collectedCount++
            exitBrowsePage(service, reason = "paid_search_recommend")
            return
        }

        // build584/585: 小说阅读任务页检测——两步进入小说内容页
        // 用户需求（debug_test_20260721_171301.log）："需要点击一部小说进入，停留15秒上下滑动"
        // 流程：
        //   1) 小说任务页(开始阅读, 得肥料) → 点"开始阅读" → 进入小说列表页
        //   2) 小说列表页(多本小说卡片) → 点击一部小说 → 进入小说内容页
        //   3) 小说内容页 → 上下滑动15秒累积阅读时长 → 得肥料
        // 标志位：
        //   browsingNovelStarted: 已点"开始阅读"（在小说列表页）
        //   browsingNovelEnteredContent: 已点击一部小说（在小说内容页,可以滑动）
        if (!browsingNovelStarted && service.isNovelReadPage()) {
            // 步骤1：小说任务页,点击"开始阅读"进入小说列表页
            val readBtn = service.findNovelReadButton()
            if (readBtn != null) {
                val btnText = readBtn.text?.toString().orEmpty()
                debugLog("browseTask: novel read page detected, clicking '$btnText' to enter novel list")
                Log.i(TAG, "browseTask: clicking '$btnText' on novel read page (step 1: enter novel list)")
                browsingNovelStarted = true
                service.performClickSafe(readBtn)
                // 等待小说列表页加载后继续（下一轮会走步骤2）
                handler.postDelayed({
                    if (state == AutomationState.BROWSING_TASK) runBrowsingTask(swipeCount)
                }, INTERVAL_PAGE_LOAD_MS)
                return
            }
            debugLog("browseTask: novel read page detected but no 开始阅读/继续阅读 button found, swiping directly")
            browsingNovelStarted = true  // 避免重复检测
        }
        // build585 步骤2：已点"开始阅读"但未进入小说内容页,点击一部小说
        if (browsingNovelStarted && !browsingNovelEnteredContent) {
            val novelNode = service.findNovelBookNode()
            if (novelNode != null) {
                val novelText = novelNode.text?.toString().orEmpty()
                debugLog("browseTask: novel list page detected, clicking novel '$novelText' to enter content")
                Log.i(TAG, "browseTask: clicking novel '$novelText' (step 2: enter novel content)")
                browsingNovelEnteredContent = true
                // build585: 用户需求"停留15秒上下滑动" → 15秒 / 2秒间隔 = 8 次滑动
                browseTaskTargetSwipes = 8
                debugLog("browseTask: novel content target swipes = 8 (15s / 2s interval)")
                service.performClickSafe(novelNode)
                // 等待小说内容页加载后开始滑动
                handler.postDelayed({
                    if (state == AutomationState.BROWSING_TASK) runBrowsingTask(swipeCount)
                }, INTERVAL_PAGE_LOAD_MS)
                return
            }
            // 兜底：找不到小说节点,可能在内容页或已进入,直接滑动
            debugLog("browseTask: no novel book node found (browsingNovelStarted=true), proceeding to swipe")
            browsingNovelEnteredContent = true
            browseTaskTargetSwipes = 8  // build585: 小说任务默认 8 次滑动（15秒）
        }

        // build590: 短剧观看任务页检测——点击"开始观看"进入播放页,等待15秒后退出
        // 用户需求："开始观看得肥料"短剧任务,点击视频播放15秒,然后退出到uc芭芭农场主页
        // 流程：
        //   1) 短剧任务页(开始观看, 得肥料) → 点"开始观看" → 进入短剧播放页
        //   2) 短剧播放页 → 视频自动播放,滑动模拟活跃（避免被判定挂机）→ 等待15秒
        //   3) 15秒后 isTaskCompletePage/isFertilizerGrantedPage 检测到完成 → pressBack 退出回主页
        // 标志位：
        //   browsingShortDramaStarted: 已点"开始观看"（在短剧播放页,可以开始等待/滑动）
        // 与小说任务区别：短剧只需点一次"开始观看"（不像小说要点"开始阅读"+再点一部小说）
        if (!browsingShortDramaStarted && service.isShortDramaPage()) {
            val playBtn = service.findShortDramaPlayButton()
            if (playBtn != null) {
                val btnText = playBtn.text?.toString().orEmpty()
                debugLog("browseTask: short drama page detected, clicking '$btnText' to enter player")
                Log.i(TAG, "browseTask: clicking '$btnText' on short drama page (enter player, wait 15s)")
                browsingShortDramaStarted = true
                // build590: 用户需求"播放15秒" → 15秒 / 2秒间隔 = 8 次滑动（与小说任务一致）
                browseTaskTargetSwipes = 8
                debugLog("browseTask: short drama target swipes = 8 (15s / 2s interval)")
                service.performClickSafe(playBtn)
                // 等待短剧播放页加载后开始滑动（模拟活跃,避免挂机判定）
                handler.postDelayed({
                    if (state == AutomationState.BROWSING_TASK) runBrowsingTask(swipeCount)
                }, INTERVAL_PAGE_LOAD_MS)
                return
            }
            debugLog("browseTask: short drama page detected but no 开始观看/继续观看 button found, swiping directly")
            browsingShortDramaStarted = true  // 避免重复检测
            browseTaskTargetSwipes = 8  // build590: 短剧任务默认 8 次滑动（15秒）
        }

        // build620: 浏览商品任务页检测——点击一个商品进入详情页,停留15秒后退出
        // 用户需求：UC 浏览商品任务,需要点击某个商品后停留15秒才可以得到肥料
        // 流程：
        //   1) 商品列表页(商品卡片 + 得肥料 + 价格¥) → 点击一个商品 → 进入商品详情页
        //   2) 商品详情页(加入购物车 + 立即购买) → 停留15秒（滑动模拟活跃）→ 等待肥料发放
        //   3) 15秒后 isTaskCompletePage/isFertilizerGrantedPage 检测到完成 → pressBack 退出回主页
        // 标志位：
        //   browsingProductEntered: 已点击商品（在商品详情页,可以开始等待/滑动）
        // 与短剧任务区别：商品任务点击商品卡片进入详情页（短剧点"开始观看"进入播放页）
        // 商品详情页有"加入购物车"+"立即购买"按钮,正常会被 isOnAbnormalPage 判为异常页退出,
        // 上方 build620 豁免逻辑已处理（browsingProductEntered=true 时豁免）
        // build629: 仅 TAOBAO 平台点击商品进入详情页停留 15 秒
        // UC 平台滑动浏览任务不需要点击商品（用户明确反馈），直接在列表页滑动
        if (!browsingProductEntered && service.currentPlatform == Platform.TAOBAO && service.isBrowseProductListPage()) {
            val productNode = service.findBrowseProductNode()
            if (productNode != null) {
                debugLog("browseTask: browse product list page detected (TAOBAO), clicking product to enter detail")
                Log.i(TAG, "browseTask: clicking product on browse product list page (enter detail, wait 15s)")
                browsingProductEntered = true
                // build620: 用户需求"停留15秒" → 15秒 / 2秒间隔 = 8 次滑动（与小说/短剧任务一致）
                browseTaskTargetSwipes = 8
                debugLog("browseTask: browse product target swipes = 8 (15s / 2s interval)")
                service.performClickSafe(productNode)
                // 等待商品详情页加载后开始滑动（模拟活跃,避免挂机判定）
                handler.postDelayed({
                    if (state == AutomationState.BROWSING_TASK) runBrowsingTask(swipeCount)
                }, INTERVAL_PAGE_LOAD_MS)
                return
            }
            debugLog("browseTask: browse product list page detected but no product node found, swiping directly")
            browsingProductEntered = true  // 避免重复检测
            browseTaskTargetSwipes = 8  // build620: 商品任务默认 8 次滑动（15秒）
        }

        // 执行滑动：在屏幕中部轻微上下交替滑动（不需要一直向下滑，小幅上下滑动即可模拟浏览）
        // 若开启交互模式，执行前会弹询问浮窗等用户批准
        val centerX = 600f
        val baseY = 1200f      // 屏幕中部基准点
        val swipeRange = 250f  // 轻微滑动距离
        val (startY, endY, dirText) = if (swipeCount % 2 == 1) {
            // 奇数次：向上滑（页面向下滚动）
            Triple(baseY + swipeRange, baseY - swipeRange, "up")
        } else {
            // 偶数次：向下滑（页面向上滚动）
            Triple(baseY - swipeRange, baseY + swipeRange, "down")
        }

        // 直接执行滑动（滑动是浏览任务的常规高频动作）
        // 诊断日志：滑动前记录页面状态、倒计时、进度，帮助定位"为什么不如人工操作"
        val browsePageType = service.getPageType()
        val browseCountdown = service.findBrowseRewardCountdownHint()
        val browseProgress = service.hasBrowseRewardProgressHint()
        debugLog("browseTask: swipe #$swipeCount/$browseTaskTargetSwipes $dirText ($startY -> $endY), pageType=$browsePageType, countdown=${browseCountdown}s, progress=$browseProgress")
        service.dispatchGestureSwipe(centerX, startY, centerX, endY, 500L)
        scheduleNextBrowseCheck(service, swipeCount)
    }

    /**
     * 安排滑动后的下一轮检测
     * - 滑动后等 BROWSE_SWIPE_INTERVAL_MS，重新进入 runBrowsingTask
     * - 包含中途的搜索推荐页/异常页/任务完成检测
     */
    private fun scheduleNextBrowseCheck(service: FarmAccessibilityService, swipeCount: Int) {
        // 成功执行一次滑动说明红包弹窗已关闭（或本就无弹窗），重置连续关闭计数器
        browseRedPacketCloseAttempts = 0
        handler.postDelayed({
            if (state != AutomationState.BROWSING_TASK) return@postDelayed
            // 任务进行中（倒计时或"每浏览x秒"进度提示仍在）时跳过搜索推荐页检测，避免误判提前退出
            val countdownActive = service.findBrowseRewardCountdownHint() > 0
            val progressActive = service.hasBrowseRewardProgressHint()
            // build663 修复（debug_test_20260728_083516.log, build662-f807e14）：
            //   08:34:33.765 processTask: browse task #2, entering BROWSING_TASK
            //   08:34:41.950 browseTask: found progress info type=FRACTION, cur=0, tot=10, percent=0%
            //   08:34:41.958 browseTask: skipping product click, swiping in list page directly
            //   08:34:47~08:35:10 swipe #1~#7（pageType=farm_home, countdown=0s, progress=false）
            //   → 点击"去完成"后未跳转到商品浏览页，仍留在农场主页（onFarm=true, pageType=farm_home）
            //   → 在农场主页滑动 7 次无效，进度一直 0/10，浪费 25 秒
            // 根因：UC 平台"去完成"按钮 clickable=false，dispatchGesture 坐标点击在某些 WebView 场景下未触发跳转。
            // 修复：滑动 2 次后若仍在农场主页（onFarm=true 且 pageType=farm_home）且无倒计时/进度，
            //   说明"去完成"点击未跳转，提前退出跳过任务，避免空滑浪费时间。
            if (swipeCount >= 2 && !countdownActive && !progressActive &&
                service.isOnFarmPage() && service.getPageType() == "farm_home") {
                debugLog("browseTask: still on farm home after $swipeCount swipes (去完成 click did not navigate), skipping task")
                Log.i(TAG, "browseTask: still on farm home after $swipeCount swipes, skipping (click did not navigate)")
                currentTaskIndex++
                collectedCount++
                exitBrowsePage(service, reason = "still_on_farm_home_after_swipes")
                return@postDelayed
            }
            // 检测搜索推荐页（"当前页下单得肥料"）→ 直接退出
            if (!countdownActive && !progressActive && service.isSearchRecommendPage()) {
                debugLog("browseTask: search recommend page during swipe, exiting")
                currentTaskIndex++
                collectedCount++
                exitBrowsePage(service, reason = "paid_search_in_swipe")
                return@postDelayed
            }
            // 检测异常页面（交易页面、商品详情页、收银台等）→ 立即退出
            // 禁止交易获取肥料：所有交易相关页面都视为异常
            // build627 修复：滑动后 isOnAbnormalPage 检测也需加 browsingProductEntered 豁免。
            // 日志 debug_test_20260726_084442.log 显示：
            // - swipe #1 之前（swipeCount=1）豁免成功（line 138: in product detail page, exempting）
            // - swipe #1 之后 scheduleNextBrowseCheck 检测 isOnAbnormalPage=true（ttdetailactivity）
            // - 但此处无 browsingProductEntered 豁免 → 直接退出，15 秒停留失败
            // 修复：browsingProductEntered=true 且在商品详情页时，豁免 isOnAbnormalPage，继续滑动等待肥料。
            if (browsingProductEntered && service.isProductDetailPageByAnyMeans()) {
                debugLog("browseTask: in product detail page during swipe (browsingProductEntered=true), exempting from abnormal page check, keep waiting for fertilizer (swipe #$swipeCount/$browseTaskTargetSwipes)")
                // 不退出,继续走下面的滑动逻辑
            } else if (service.isOnAbnormalPage()) {
                debugLog("browseTask: abnormal/trading page during swipe, exiting immediately")
                currentTaskIndex++
                collectedCount++
                exitBrowsePage(service, reason = "abnormal_in_swipe")
                return@postDelayed
            }
            // 检测是否已完成任务（得到肥料）
            if (service.isFertilizerGrantedPage()) {
                debugLog("browseTask: fertilizer granted detected during swipe, exiting via RETURNING")
                collectedCount++
                currentTaskIndex++
                // 直接走 RETURNING：reopenFarmByDeepLink 会 kill 目标 App 老进程 +
                // 用桌面快捷方式/deep link 重新打开农场页，不依赖返回键（WebView 里返回键不可靠）
                browseFromSearchBrowse = false
                browseFromDirectPopup = false
                moveTo(AutomationState.RETURNING)
                handler.postDelayed({ runReturning(0) }, INTERVAL_CLICK_MS)
                return@postDelayed
            }
            // 检测是否已完成任务（得到肥料）
            if (service.isTaskCompletePage()) {
                debugLog("browseTask: task complete detected during swipe, exiting")
                // 优先点右上角关闭或左上角返回图标
                val closeBtn = service.findAdCloseButton()
                val backIcon = service.findBackIcon()
                when {
                    closeBtn != null -> { debugLog("browseTask: clicking close icon"); service.performClickSafe(closeBtn) }
                    backIcon != null -> { debugLog("browseTask: clicking back icon"); service.performClickSafe(backIcon) }
                    else -> { debugLog("browseTask: pressing back"); service.pressBack() }
                }
                collectedCount++
                currentTaskIndex++
                // 从"搜索后浏览立得奖励"任务页进入的浏览：需要返回两次回芭芭农场
                val fromSearchBrowse = browseFromSearchBrowse
                browseFromSearchBrowse = false  // 复位
                handler.postDelayed({
                    // 搜索浏览任务：第一次返回后还需再按一次返回退出搜索任务页
                    if (fromSearchBrowse && !service.isOnFarmPage()) {
                        debugLog("browseTask: from search browse, pressing back again to exit search task page")
                        service.pressBack()
                    } else if (!service.isOnFarmPage()) {
                        // 普通浏览任务：不在农场页时按一次返回
                        service.pressBack()
                    }
                    handler.postDelayed({
                        // 从 direct 弹窗进入的浏览：完成后回 COLLECTING_DIRECT
                        val fromDirect = browseFromDirectPopup
                        browseFromDirectPopup = false  // 复位
                        if (fromDirect) {
                            debugLog("browseTask: was from direct popup, returning to COLLECTING_DIRECT")
                            moveTo(AutomationState.COLLECTING_DIRECT)
                            handler.postDelayed({ runCollectingDirect(attempt = 0) }, INTERVAL_CLICK_MS)
                        } else {
                            moveTo(AutomationState.OPENING_TASK_LIST)
                            handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                        }
                    }, INTERVAL_CLICK_MS)
                }, INTERVAL_PAGE_LOAD_MS)
                return@postDelayed
            }
            runBrowsingTask(swipeCount + 1)
        }, BROWSE_SWIPE_INTERVAL_MS)
    }

    /** 退出浏览页面：优先用左上角返回图标，否则按返回键，然后重新打开任务列表 */
    private fun exitBrowsePage(service: FarmAccessibilityService, reason: String = "exit_browse_page") {
        // 用户同意退出，执行原退出逻辑
        // 优先点击左上角返回图标退出（"下单得奖励"、"当前页下单得肥料"等搜索推荐页面）
        val backIcon = service.findBackIcon()
        if (backIcon != null) {
            debugLog("exitBrowsePage: clicking back icon to exit")
            service.performClickSafe(backIcon)
        } else {
            debugLog("exitBrowsePage: no back icon found, pressing back")
            service.pressBack()
        }
        // 等待页面返回，然后检查是否回到农场页
        handler.postDelayed({
            // 从 direct 弹窗进入的浏览：完成后回 COLLECTING_DIRECT 继续找其他 direct 按钮
            val fromDirect = browseFromDirectPopup
            browseFromDirectPopup = false  // 复位
            if (fromDirect) {
                debugLog("exitBrowsePage: browse was from direct popup, returning to COLLECTING_DIRECT")
                moveTo(AutomationState.COLLECTING_DIRECT)
                handler.postDelayed({ runCollectingDirect(attempt = 0) }, INTERVAL_CLICK_MS)
                return@postDelayed
            }
            // 从"搜索后浏览立得奖励"任务页进入的浏览：完成后需要返回两次回芭芭农场
            // 第一次返回已执行（上方 backIcon/pressBack），这里再按一次返回
            val fromSearchBrowse = browseFromSearchBrowse
            browseFromSearchBrowse = false  // 复位
            if (fromSearchBrowse && !service.isOnFarmPage()) {
                debugLog("exitBrowsePage: browse was from search browse task, pressing back again to return to farm")
                service.pressBack()
                handler.postDelayed({
                    if (service.isOnFarmPage()) {
                        debugLog("exitBrowsePage: returned to farm after second back")
                        moveTo(AutomationState.OPENING_TASK_LIST)
                        handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                    } else {
                        debugLog("exitBrowsePage: still not on farm after second back, re-navigating")
                        moveTo(AutomationState.NAVIGATING)
                        handler.postDelayed({ runNavigating(0) }, INTERVAL_CLICK_MS)
                    }
                }, INTERVAL_PAGE_LOAD_MS)
                return@postDelayed
            }
            // build630 修复：browsingProductEntered 商品详情页退出后，第一次 pressBack 只是回到商品列表页
            // （层级：任务列表 → 商品列表页 → 商品详情页）。商品列表页含"芭芭农场-interact"+"浏览得奖励"等
            // 关键词，会被 hasFarmContentLoaded 误判为农场主页，导致 COLLECTING_DIRECT 找不到领取按钮
            // 陷入循环（日志 debug_test_20260726_092123.log 09:17:47-09:19:43 约 2 分钟）。
            // 修复：第一次返回后若仍在商品列表页（isBrowseProductListPage=true），再 pressBack 一次回到任务列表。
            if (service.isBrowseProductListPage()) {
                debugLog("exitBrowsePage: still on browse product list page after first back, pressing back again to return to task list")
                val backIcon2 = service.findBackIcon()
                if (backIcon2 != null) {
                    service.performClickSafe(backIcon2)
                } else {
                    service.pressBack()
                }
                handler.postDelayed({
                    if (service.isOnFarmPage() && !service.isBrowseProductListPage()) {
                        debugLog("exitBrowsePage: returned to farm after second back (from product list)")
                        moveTo(AutomationState.OPENING_TASK_LIST)
                        handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                    } else {
                        debugLog("exitBrowsePage: still not on farm after second back (from product list), re-navigating")
                        moveTo(AutomationState.NAVIGATING)
                        handler.postDelayed({ runNavigating(0) }, INTERVAL_CLICK_MS)
                    }
                }, INTERVAL_PAGE_LOAD_MS)
                return@postDelayed
            }
            if (service.isOnFarmPage()) {
                // 已回到农场页，重新打开任务列表
                moveTo(AutomationState.OPENING_TASK_LIST)
                handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
            } else {
                // 不在农场页，可能回到了淘宝主页，需要重新导航到农场
                debugLog("exitBrowsePage: not on farm page after exit, re-navigating")
                moveTo(AutomationState.NAVIGATING)
                handler.postDelayed({ runNavigating(0) }, INTERVAL_CLICK_MS)
            }
        }, INTERVAL_PAGE_LOAD_MS)
    }

    // ============== 阶段3c: 玩游戏任务（停留拿肥料） ==============

    /**
     * 玩游戏任务：打开游戏停留玩一下即可获取肥料（无法实现过关卡）
     *
     * 基于用户澄清："玩游戏过关卡任务你实现不了，但是只是打开游戏停留玩一下的任务你是可以获取到肥料的"
     * 这类任务只需在游戏内停留规定时长即可发放肥料，无需真正通关。
     *
     * 策略：
     * 1. 等待游戏加载（GAME_LOAD_MS）
     * 2. 停留轮询：每 GAME_ACTION_INTERVAL_MS 检测一次页面状态
     *    - 陷阱页（充值/交易页）→ 立即点关闭/返回退出，跳过任务（不领肥料，安全优先）
     *    - 完成页（"领取奖励"/"恭喜"/"完成"/"升级"等）→ 领取奖励后返回农场
     *    - 自动返回农场页 → 假设任务已结算，肥料已发放
     *    - 其他页面 → 继续停留（不按返回，避免退出游戏导致停留失败）
     * 3. 停留达到 gamePlayingStayTargetMs（普通 30s，试玩热门新游 10min）且仍在游戏内 → 主动按返回退出回农场
     * 4. 硬超时 GAME_MAX_DURATION_MS（90s）→ 强制退出，跳过任务
     *
     * 关键：停留期间绝不按返回键（按返回会退出游戏导致停留失败），仅靠页面状态检测驱动退出。
     *
     * @param elapsedMs 已用时
     */
    private fun runGamePlaying(elapsedMs: Long) {
        if (state != AutomationState.GAME_PLAYING) return
        val service = getService() ?: run { stop(); return }

        if (elapsedMs == 0L) {
            logPageSnapshot(service, "gamePlay-start")
        }

        // 硬超时强制退出（覆盖加载5s + 停留目标 + 60s 退出余量）
        // build556：用 gamePlayingStayTargetMs 替代硬编码 GAME_STAY_TARGET_MS
        // - 普通游戏：stayTarget=30s，硬超时 30s+60s=90s（GAME_MAX_DURATION_MS）
        // - 试玩热门新游：stayTarget=10min，硬超时 10min+60s=11min
        val gameMaxDurationMs = gamePlayingStayTargetMs + 60000L
        if (elapsedMs >= gameMaxDurationMs) {
            Log.w(TAG, "gamePlay: hard timeout (elapsed=${elapsedMs}ms, max=${gameMaxDurationMs}ms), exiting")
            debugLog("gamePlay: hard timeout, exiting game, skipping task")
            service.pressBack()
            handler.postDelayed({
                if (state == AutomationState.GAME_PLAYING) {
                    if (!service.isOnFarmPage()) service.pressBack()
                    handler.postDelayed({
                        if (state == AutomationState.GAME_PLAYING) {
                            currentTaskIndex++
                            moveTo(AutomationState.OPENING_TASK_LIST)
                            handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                        }
                    }, INTERVAL_PAGE_LOAD_MS)
                }
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 诱导弹窗防护：游戏过程中弹出"立即下载/立即体验"等诱导按钮
        // 策略：优先点"关闭/暂不/拒绝"关闭弹窗，绝不点诱导按钮，避免跳转应用商店中断停留
        // 注意：仅在停留期间检测，弹窗关闭后继续停留计时长
        if (service.findAdInstallButton() != null) {
            Log.w(TAG, "gamePlay: install popup detected, attempting to close (stay protection)")
            debugLog("gamePlay: install popup trap detected during stay, trying closeAdInstallPopup")
            val closed = service.closeAdInstallPopup()
            if (!closed) {
                // 找不到关闭类按钮，可能整个页面已变成落地页 → 按返回退出
                debugLog("gamePlay: no close button for install popup, pressing back to exit")
                service.pressBack()
                handler.postDelayed({
                    if (state == AutomationState.GAME_PLAYING) {
                        currentTaskIndex++
                        moveTo(AutomationState.OPENING_TASK_LIST)
                        handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                    }
                }, INTERVAL_CLICK_MS)
                return
            }
            // 弹窗已关闭，继续停留（不增加 elapsedMs，下一轮重新检测）
            debugLog("gamePlay: install popup closed, continuing stay")
            handler.postDelayed({
                if (state == AutomationState.GAME_PLAYING) runGamePlaying(elapsedMs + GAME_ACTION_INTERVAL_MS)
            }, GAME_ACTION_INTERVAL_MS)
            return
        }

        // 陷阱页（充值/付费/交易）→ 立即退出，跳过任务（安全优先，不领肥料）
        if (service.isRechargePage() || service.isOnAbnormalPage()) {
            Log.w(TAG, "gamePlay: trap page (recharge/abnormal) detected, exiting immediately")
            debugLog("gamePlay: trap page detected, trying to click close button")
            val closed = service.clickCloseOnRechargePage()
            if (!closed) {
                debugLog("gamePlay: no close button found, pressing back to exit trap")
                service.pressBack()
            }
            handler.postDelayed({
                if (state == AutomationState.GAME_PLAYING) {
                    // 再次检测是否还在陷阱页（关闭失败的情况）
                    if (service.isRechargePage() || service.isOnAbnormalPage()) {
                        debugLog("gamePlay: still on trap page, pressing back again")
                        service.pressBack()
                    }
                    handler.postDelayed({
                        if (state == AutomationState.GAME_PLAYING) {
                            debugLog("gamePlay: exited trap page, skipping task (no fertilizer)")
                            currentTaskIndex++
                            moveTo(AutomationState.OPENING_TASK_LIST)
                            handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                        }
                    }, INTERVAL_CLICK_MS)
                }
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 游戏完成页（"领取奖励"/"恭喜"/"完成"/"升级"等）→ 不主动点击，只等待
        // build528（用户反馈）：试玩游戏但不是完成游戏任务（如对战、关卡、过关、订单等任务），
        // 打开游戏 App 后，只需要等待拿肥料，不需要任何操作。
        // 历史问题：isGameCompletePage 检测到"任务完成"/"已完成"等文案后主动调用
        // clickClaimRewardButton 点击"领取奖励"/"确认"/"确定"/"完成"/"继续"/"下一步"等按钮，
        // 这些点击可能误触发游戏内的其他操作（如"开始游戏"/"下一关"等），干扰游戏流程。
        // 修复：检测到游戏完成页后不主动点击，只继续等待，让游戏自动发放肥料，
        // 直到检测到"肥料全部取完"或停留超时后退出。
        if (service.isGameCompletePage()) {
            Log.i(TAG, "gamePlay: game complete page detected, waiting for fertilizer to be credited (no click)")
            debugLog("gamePlay: game complete detected, waiting (no click, user request: don't operate)")
            handler.postDelayed({
                if (state == AutomationState.GAME_PLAYING) runGamePlaying(elapsedMs + GAME_ACTION_INTERVAL_MS)
            }, GAME_ACTION_INTERVAL_MS)
            return
        }

        // build528（用户反馈）：检测"肥料全部取完"/"肥料已全部领取"等提示 → pressBack 退出游戏
        // 用户需求：显示肥料全部取完后，退出游戏 App
        if (service.isFertilizerAllClaimed()) {
            Log.i(TAG, "gamePlay: fertilizer all claimed detected, exiting game")
            debugLog("gamePlay: 肥料全部取完 detected, pressing back to exit game")
            service.pressBack()
            handler.postDelayed({
                if (state != AutomationState.GAME_PLAYING) return@postDelayed
                if (service.isOnFarmPage()) {
                    debugLog("gamePlay: returned to farm after 肥料全部取完, task complete")
                    collectedCount++
                    currentTaskIndex++
                    moveTo(AutomationState.OPENING_TASK_LIST)
                    handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                } else {
                    // 不在农场页，可能退到中间页，再按一次返回
                    debugLog("gamePlay: not on farm after 肥料全部取完, pressing back once more")
                    service.pressBack()
                    handler.postDelayed({
                        if (state == AutomationState.GAME_PLAYING) {
                            if (service.isOnFarmPage()) {
                                debugLog("gamePlay: returned to farm on second back after 肥料全部取完, task complete")
                                collectedCount++
                            }
                            currentTaskIndex++
                            moveTo(AutomationState.OPENING_TASK_LIST)
                            handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                        }
                    }, INTERVAL_PAGE_LOAD_MS)
                }
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 已回到农场页（游戏自动返回，说明任务已结算）→ 假设完成
        if (elapsedMs > GAME_LOAD_MS && service.isOnFarmPage()) {
            Log.i(TAG, "gamePlay: back to farm page, game task likely complete")
            debugLog("gamePlay: back to farm, assuming complete")
            collectedCount++
            currentTaskIndex++
            moveTo(AutomationState.OPENING_TASK_LIST)
            handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
            return
        }

        // 停留达到目标时长且仍在游戏内 → 主动按返回退出回农场，任务发放肥料
        // build556：用 gamePlayingStayTargetMs 替代硬编码 GAME_STAY_TARGET_MS
        // - 普通游戏停留 30s，试玩热门新游停留 10min
        if (elapsedMs >= gamePlayingStayTargetMs) {
            Log.i(TAG, "gamePlay: stay target reached (elapsed=${elapsedMs}ms, target=${gamePlayingStayTargetMs}ms), exiting to farm")
            debugLog("gamePlay: stay ${elapsedMs}ms reached (target=${gamePlayingStayTargetMs}ms), pressing back to collect fertilizer")
            service.pressBack()
            handler.postDelayed({
                if (state != AutomationState.GAME_PLAYING) return@postDelayed
                if (service.isOnFarmPage()) {
                    debugLog("gamePlay: returned to farm after stay, task complete")
                    collectedCount++
                    currentTaskIndex++
                    moveTo(AutomationState.OPENING_TASK_LIST)
                    handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                } else {
                    // 不在农场页，可能退到中间页，再按一次返回
                    debugLog("gamePlay: not on farm after back, pressing back once more")
                    service.pressBack()
                    handler.postDelayed({
                        if (state == AutomationState.GAME_PLAYING) {
                            if (service.isOnFarmPage()) {
                                debugLog("gamePlay: returned to farm on second back, task complete")
                                collectedCount++
                            }
                            currentTaskIndex++
                            moveTo(AutomationState.OPENING_TASK_LIST)
                            handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                        }
                    }, INTERVAL_PAGE_LOAD_MS)
                }
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 其他页面：继续停留（不按返回，避免退出游戏），下一轮检测
        if (elapsedMs < GAME_LOAD_MS) {
            debugLog("gamePlay: waiting for game to load (elapsed=${elapsedMs}ms)")
        } else {
            debugLog("gamePlay: staying in game (elapsed=${elapsedMs}ms, target=${gamePlayingStayTargetMs}ms)")
        }

        // build529（用户要求"全部实现"）：AI 视觉识别环形进度条（节流到 20s 一次）
        // 用途：游戏页面通常无可读文本进度，截图交 GLM-4.6V-Flash 识别进度环填充比例，
        // 输出 percent/seconds_remaining 到日志，便于诊断"游戏卡在哪里、还要等多久"。
        // 不主动触发提前退出（AI 判断存在误差），仅作信息补充；退出仍由上方既有条件负责。
        if (elapsedMs >= GAME_LOAD_MS &&
            state == AutomationState.GAME_PLAYING &&
            System.currentTimeMillis() - lastAiProgressCheckMs >= AI_PROGRESS_CHECK_INTERVAL_MS) {
            lastAiProgressCheckMs = System.currentTimeMillis()
            val appContext = service.applicationContext
            val snapshotElapsed = elapsedMs
            Thread {
                val bitmap = service.takeScreenshotBitmap()
                if (bitmap == null) {
                    Log.w(TAG, "gamePlay: AI progress screenshot unavailable")
                    return@Thread
                }
                try {
                    val sceneCtx = "game playing (elapsed=${snapshotElapsed}ms, target=${gamePlayingStayTargetMs}ms)"
                    val result = AiVisionClient.recognizeProgressFromScreenshot(appContext, bitmap, sceneCtx)
                    bitmap.recycle()
                    if (result == null) {
                        Log.w(TAG, "gamePlay: AI progress returned null")
                    } else {
                        Log.i(TAG, "gamePlay: AI progress percent=${result.percent}%, " +
                            "secondsRemaining=${result.secondsRemaining}s, " +
                            "hasBar=${result.hasProgressBar}, reason='${result.reason.take(80)}'")
                        debugLog("gamePlay: AI progress percent=${result.percent}%, " +
                            "remaining=${result.secondsRemaining}s, hasBar=${result.hasProgressBar}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "gamePlay: AI progress exception: ${e.message}", e)
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }.start()
        }

        handler.postDelayed({
            if (state == AutomationState.GAME_PLAYING) runGamePlaying(elapsedMs + GAME_ACTION_INTERVAL_MS)
        }, GAME_ACTION_INTERVAL_MS)
    }

    /**
     * 检查任务点击结果
     * - 如果进入广告 → 看广告阶段
     * - 如果是非广告页面 → 返回继续下一个任务
     * - 如果无变化 → 重试或跳过
     */
    private fun checkTaskResult(service: FarmAccessibilityService, attempt: Int) {
        if (state != AutomationState.PROCESSING_TASK) return

        logPageSnapshot(service, "checkTaskResult")

        // build733: 点击后检测到不在农场页 → 标记离开过农场(区分真完成 vs 农场页误判)
        if (!service.isOnFarmPage()) {
            taskClickLeftFarm = true
        }

        // build747 修复（debug_test_20260822_192917.log, build746, 19:27:47-19:27:53）：
        //   task#1 "看视频得巨额肥料(1/10)" 点击"去完成"后,任务列表上弹出倒计时领取弹窗
        //   text='去领取(1s)' bounds=[222,1637][979,1801] clickable=false。
        //   旧逻辑不识别该弹窗,而页面静态的"已领取/明天领肥料"(签到区标识)触发了下方
        //   build668 的签到误判 → advance → 弹窗无人点击 → 看视频任务奖励丢失。
        //   且 advance 后 replay 循环中页面跳到淘宝,又卡死 70s(见 runProcessingTask
        //   build747 非农场恢复)。
        // 修复：检测到"去领取(Ns)"弹窗 → 等 N+2 秒倒计时结束 → 重新查找"去领取"节点点击
        //   领取 → 继续结果检测。优先级放在"已领取"签到判定之前。
        val countdownClaim = service.findCountdownClaimButton()
        if (countdownClaim != null) {
            val (cdNode, cdSecs) = countdownClaim
            val cdBounds = Rect().also { cdNode.getBoundsInScreen(it) }
            Log.i(TAG, "processTask: countdown claim popup detected ('去领取($cdSecs s)'), waiting ${cdSecs}s then claim")
            debugLog("processTask: 检测到'去领取(${cdSecs}s)'倒计时弹窗 bounds=${cdBounds.toShortString()}, ${cdSecs + 2}s后点击领取")
            handler.postDelayed({
                if (state != AutomationState.PROCESSING_TASK) return@postDelayed
                // 倒计时结束后文本变为"去领取",重新查找（旧节点可能已失效）
                val root = service.rootInActiveWindowSafe() ?: return@postDelayed
                val claimNodes = root.findAccessibilityNodeInfosByText("去领取")
                    .filter { it.text?.toString() == "去领取" || Regex("去领取\\(\\d+s?\\)").matches(it.text?.toString().orEmpty()) }
                val claimBtn = claimNodes.firstOrNull()
                if (claimBtn != null) {
                    debugLog("processTask: countdown ended, clicking '去领取' to claim reward")
                    service.performClickSafe(claimBtn)
                    // 点击领取后等待肥料到账/任务完成提示，重新评估
                    handler.postDelayed({
                        if (state == AutomationState.PROCESSING_TASK) checkTaskResult(service, attempt + 1)
                    }, INTERVAL_PAGE_LOAD_MS)
                } else {
                    debugLog("processTask: '去领取' node gone after countdown (claimed automatically?), re-checking")
                    handler.postDelayed({
                        if (state == AutomationState.PROCESSING_TASK) checkTaskResult(service, attempt + 1)
                    }, INTERVAL_CLICK_MS)
                }
            }, (cdSecs + 2) * 1000L)
            return
        }

        // build668 修复（debug_test_20260731_210558.log, build666）：
        // 点击 task #1 "签到"后页面变"已领取"+"明天领肥料"（签到 = 当天点击领取,已领成功）,
        // 但 processTask 未识别,继续重试点击 task #1（实际签到已成功）,还误判 isRechargePage
        // 跳过签到。修复：检测到"已领取"+"明天领肥料"组合时,认定当前签到/领取任务已完成,
        // 前进到下一任务,不再重试点击。
        // 日志证据:
        //   21:05:30.090 [checkTaskResult] text='已领取' bounds=[894,933][1123,1031]
        //   21:05:30.090 [checkTaskResult] text='明天领肥料' bounds=[894,1054][1123,1110]
        //   21:05:31.037 processTask: still on farm page, retry task click attempt=0  ← 不应重试
        // build674 修复（debug_test_20260801_094058.log, build673）：
        //   task #2 "看视频"点击后没进广告,checkTaskResult 检测到"已领取"+"明天领肥料"
        //   （这是 task #1 "签到"的标识,不是 task #2 完成的标识）→ 误判 task #2 完成,
        //   重试 5 次都误判完成,直到第 6 次点击真正进入广告。
        // 修复："已领取"检测只对 task #1 "签到"有效（currentTaskIndex == 0）,
        // 后续任务不能用这个标识判断完成（"已领取"是签到的标识,与后续任务无关）。
        // build747 修复（debug_test_20260822_192917.log, build746, 19:27:53）：
        //   签到按钮被 findGoCompleteButtons drop（"签到肥料"不匹配纯签到规则）时,
        //   task#0 是"看视频得巨额肥料"而非签到任务;页面静态的"已领取/明天领肥料"
        //   （签到区标识,签到早已完成）触发本判定 → 误判看视频任务完成 → advance
        //   → 奖励丢失 + replay 循环跳淘宝卡死。
        //   修复：本判定仅对签到任务生效——当前任务上下文须含"签到"字样。
        val curTaskIsSignIn = currentTaskContextText.contains("签到") ||
            taskButtons.getOrNull(currentTaskIndex)?.text?.toString().orEmpty().contains("签到")
        if (currentTaskIndex == 0 && curTaskIsSignIn && service.isOnFarmPage() && service.hasDailyRewardClaimedIndicator()) {
            Log.i(TAG, "processTask: daily reward already claimed (已领取+明天领肥料 detected), advance to next task")
            debugLog("processTask: 已领取+明天领肥料 detected, task #1 签到 done, advance")
            collectedCount++
            advanceTaskIndex()
            handler.postDelayed({
                if (state == AutomationState.PROCESSING_TASK) runProcessingTask(0)
            }, INTERVAL_CLICK_MS)
            return
        }

        // 最高优先级：肥料图标 + 领取按钮 → 直接点击领取（绕过场景白名单）
        // 用户需求：如果有带肥图标，并有"领取"的按钮，可以直接点击领取
        // 比任务完成/肥料到账检测更早，因为领取按钮还没点就没有"完成"状态
        // findFertilizerClaimButton 已自行排除农场主页/广告播放中/任务列表场景
        val fertClaimBtn = service.findFertilizerClaimButton()
        if (fertClaimBtn != null) {
            val claimText = fertClaimBtn.text?.toString().orEmpty()
            Log.i(TAG, "processTask: fertilizer claim button found (text='$claimText'), clicking directly")
            debugLog("processTask: fertilizer icon + claim button detected (text='$claimText'), clicking directly (bypass scene whitelist)")
            service.performClickSafe(fertClaimBtn)
            // 点击领取后，等待肥料到账/任务完成提示，重新评估场景
            handler.postDelayed({
                if (state == AutomationState.PROCESSING_TASK) checkTaskResult(service, attempt + 1)
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 优先检测：是否显示"任务完成"页面 → 得到肥料后立即退出
        if (service.isTaskCompletePage()) {
            Log.i(TAG, "processTask: task complete page detected, exiting")
            debugLog("processTask: task complete, exiting via close/back icon")
            // build641 修复（debug_test_20260726_142922.log）：
            // 历史问题: TAOBAO 农场主页有"1500，肥料，点击领取"按钮（奖励领取），
            // 但 isTaskCompletePage 误判为任务完成，直接点关闭/返回按钮退出，
            // 没有点击"点击领取"按钮，1500 肥料奖励始终未领取。
            // 修复: 退出前先尝试点击页面上的"点击领取"/"立即领取"按钮（直接领取奖励）。
            val directClaimBtn = service.findDirectCollectButtons().firstOrNull { btn ->
                val text = btn.text?.toString().orEmpty()
                val desc = btn.contentDescription?.toString().orEmpty()
                val btnText = if (text.isNotEmpty()) text else desc
                btnText.contains("点击领取") || btnText.contains("立即领取")
            }
            // build733 修复（debug_test_20260816_193200.log, build731, 19:29:54-19:31:55）：
            //   本轮会话广告SDK无填充: "看广告领奖"直领按钮点5次 + task#2"看视频得巨额肥料"去完成点5次,
            //   共10次点击全部无效果(从未离开农场页,无广告Activity)。但农场页本身含"已完成"文案
            //   → isTaskCompletePage YES → 误判"任务完成" → advanceTaskIndex 重放(remainingReplays=9),
            //   每轮约25s无效循环(退出时 findBackIcon 还误点宠物面板),直到用户手动停止。
            //   修复: 判定"完成"前,若仍在农场页且本次点击后从未离开过农场(taskClickLeftFarm=false),
            //   且无"点击领取"按钮/肥料到账弹窗 → 判定点击无效果(广告未拉出),走重试/跳过逻辑,
            //   不再误判完成、不触发退出点击。
            // build758 修复（debug_test_20260829_230753.log, build757, 23:06:47-23:07:13）：
            //   task#2"玩松鼠大战15秒"点击"去完成"后打开了H5游戏页（activity:
            //   InnerUCMobile→LinearLayout，页面已切换），但任务列表弹层下的农场节点
            //   （集肥料等）仍可读 → isOnFarmPage content fallback 误判true →
            //   taskClickLeftFarm 未置位 → 误判"点击无效果"重试3次死磕26s后跳过任务
            //   （+2400肥料丢失）。
            //   修复: 点击后 activity 已变化 = 页面已切换 = 点击有效果，
            //   不再判"无效果"重试，走任务完成退出路径（关闭/返回→advance）。
            val curActName = service.getCurrentActivityName()
            val actChangedSinceTaskClick = taskClickActivityName != null &&
                curActName != null && curActName != taskClickActivityName
            if (actChangedSinceTaskClick) {
                debugLog("processTask: activity changed since task click ('$taskClickActivityName' -> '$curActName'), page switched (task page opened), not a no-effect click")
            }
            if (service.isOnFarmPage() && !taskClickLeftFarm && !actChangedSinceTaskClick &&
                directClaimBtn == null && !service.isFertilizerGrantedPage()) {
                if (attempt < MAX_TASK_ATTEMPTS) {
                    Log.w(TAG, "processTask: task click no-effect (still on farm, never left, ad not loaded), retry attempt=$attempt")
                    debugLog("processTask: 任务点击无效果(未离开农场页,广告未拉出), 重试点击 attempt=$attempt")
                    val buttons = service.findGoCompleteButtons()
                    if (buttons.isNotEmpty() && currentTaskIndex < buttons.size) {
                        taskButtons = buttons
                        // build758: 重试点击后刷新记录的 activity（以重试点击时为基准）
                        taskClickActivityName = service.getCurrentActivityName()
                        service.performClickSafe(buttons[currentTaskIndex])
                    } else {
                        Log.w(TAG, "processTask: task buttons changed, reopening task list")
                        moveTo(AutomationState.OPENING_TASK_LIST)
                        handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                        return
                    }
                    handler.postDelayed({
                        if (state == AutomationState.PROCESSING_TASK) checkTaskResult(service, attempt + 1)
                    }, INTERVAL_PAGE_LOAD_MS)
                    return
                }
                Log.w(TAG, "processTask: task click no-effect after $MAX_TASK_ATTEMPTS attempts, skipping task")
                debugLog("processTask: 任务点击连续${MAX_TASK_ATTEMPTS}次无效果(广告未拉出), 跳过该任务")
                taskReplayRemaining = 0
                currentTaskIndex++
                noProgressRounds++
                handler.postDelayed({
                    if (state == AutomationState.PROCESSING_TASK) runProcessingTask(0)
                }, INTERVAL_CLICK_MS)
                return
            }
            if (directClaimBtn != null) {
                val claimText = directClaimBtn.text?.toString().orEmpty()
                Log.i(TAG, "processTask: found direct claim button before exit, clicking (text='$claimText')")
                debugLog("processTask: clicking direct claim button before exit, text='$claimText'")
                service.performClickSafe(directClaimBtn)
                // 领取后等待一下，再退出
                handler.postDelayed({
                    if (state != AutomationState.PROCESSING_TASK) return@postDelayed
                    val closeBtn = service.findAdCloseButton()
                    val backIcon = service.findBackIcon()
                    when {
                        closeBtn != null -> { debugLog("processTask: clicking close icon"); service.performClickSafe(closeBtn) }
                        backIcon != null -> { debugLog("processTask: clicking back icon"); service.performClickSafe(backIcon) }
                        else -> { debugLog("processTask: pressing back"); service.pressBack() }
                    }
                    collectedCount++
                    advanceTaskIndex()
                    handler.postDelayed({
                        if (service.isOnFarmPage()) {
                            moveTo(AutomationState.OPENING_TASK_LIST)
                            handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                        } else {
                            debugLog("processTask: not on farm page after task complete, re-navigating")
                            moveTo(AutomationState.NAVIGATING)
                            handler.postDelayed({ runNavigating(0) }, INTERVAL_CLICK_MS)
                        }
                    }, INTERVAL_PAGE_LOAD_MS)
                }, INTERVAL_CLICK_MS)
                return
            }
            // 优先点右上角关闭或左上角返回图标
            val closeBtn = service.findAdCloseButton()
            val backIcon = service.findBackIcon()
            when {
                closeBtn != null -> { debugLog("processTask: clicking close icon"); service.performClickSafe(closeBtn) }
                backIcon != null -> { debugLog("processTask: clicking back icon"); service.performClickSafe(backIcon) }
                else -> { debugLog("processTask: pressing back"); service.pressBack() }
            }
            collectedCount++
            advanceTaskIndex()  // 多次任务重玩同一任务，否则前进到下一个
            handler.postDelayed({
                if (service.isOnFarmPage()) {
                    moveTo(AutomationState.OPENING_TASK_LIST)
                    handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                } else {
                    // 不在农场页，需要重新导航
                    debugLog("processTask: not on farm page after task complete, re-navigating")
                    moveTo(AutomationState.NAVIGATING)
                    handler.postDelayed({ runNavigating(0) }, INTERVAL_CLICK_MS)
                }
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 优先检测：是否显示"肥料已发放/奖励已到账"提示 → 肥料已到账，直接退出
        // 与 isTaskCompletePage 的区别：isTaskCompletePage 匹配"任务完成"等关键词，
        // 而签到/直接领取类任务弹出的是"肥料奖励已发放""奖励已到账"提示，不含"任务完成"字样，
        // 需单独检测，否则 bot 不识别为完成、不退出。
        if (service.isFertilizerGrantedPage()) {
            Log.i(TAG, "processTask: fertilizer granted page detected, exiting")
            debugLog("processTask: fertilizer granted, exiting via close/back icon")
            val closeBtn = service.findAdCloseButton()
            val backIcon = service.findBackIcon()
            when {
                closeBtn != null -> { debugLog("processTask: clicking close icon (fertilizer granted)"); service.performClickSafe(closeBtn) }
                backIcon != null -> { debugLog("processTask: clicking back icon (fertilizer granted)"); service.performClickSafe(backIcon) }
                else -> { debugLog("processTask: pressing back (fertilizer granted)"); service.pressBack() }
            }
            collectedCount++
            advanceTaskIndex()
            handler.postDelayed({
                if (service.isOnFarmPage()) {
                    moveTo(AutomationState.OPENING_TASK_LIST)
                    handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                } else {
                    debugLog("processTask: not on farm page after fertilizer granted, re-navigating")
                    moveTo(AutomationState.NAVIGATING)
                    handler.postDelayed({ runNavigating(0) }, INTERVAL_CLICK_MS)
                }
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 优先检测：是否有红包弹窗 → 先关闭它，才能继续处理
        // 红包弹窗会遮挡页面，不关闭会干扰后续检测
        // build736 修复（debug_test_20260822_070102.log, build735, 06:59:57-07:00:55）:
        //   穿山甲"淘宝闪购"红包样式广告,创意横幅"…领取红包吃美食啦！"含"红包"+"领取红包",
        //   被 findRedPacketCloseButton 误判为红包弹窗 → 每2s点击横幅中心(点的是广告创意,
        //   还可能触发跳转淘宝),58s死循环直到用户手动停止,广告从未进入 WATCHING_AD。
        //   原因: 本分支位于广告检测分支之前,广告打开时红包文案是广告创意而非弹窗。
        // 修复(双层):
        //   1. 广告打开时(isAdActivity/isAdPlaying/isAdContentShown)跳过红包处理,
        //      落到下方广告检测分支进入 WATCHING_AD;
        //   2. 次数上限(同 browseTask 防御): 超过 MAX_RED_PACKET_CLOSE_ATTEMPTS 后
        //      不再当红包弹窗处理,防止非广告页误判死循环。
        val adCurrentlyOpen = service.isAdActivity() || service.isAdPlaying() || service.isAdContentShown()
        val redPacketBtn = if (!adCurrentlyOpen && taskRedPacketCloseAttempts < MAX_RED_PACKET_CLOSE_ATTEMPTS) {
            service.findRedPacketCloseButton()
        } else {
            if (adCurrentlyOpen) {
                debugLog("processTask: ad open, skip red packet detection (red packet text is ad creative, not popup)")
            } else if (taskRedPacketCloseAttempts >= MAX_RED_PACKET_CLOSE_ATTEMPTS) {
                debugLog("processTask: red packet close attempts exceeded ($taskRedPacketCloseAttempts), ignoring red packet detection")
            }
            null
        }
        if (redPacketBtn != null) {
            taskRedPacketCloseAttempts++
            Log.i(TAG, "processTask: red packet popup detected, closing it first (attempt $taskRedPacketCloseAttempts/$MAX_RED_PACKET_CLOSE_ATTEMPTS)")
            debugLog("processTask: closing red packet popup (attempt $taskRedPacketCloseAttempts/$MAX_RED_PACKET_CLOSE_ATTEMPTS)")
            service.performClickSafe(redPacketBtn)
            // 等待弹窗关闭后重新检测
            handler.postDelayed({
                if (state == AutomationState.PROCESSING_TASK) checkTaskResult(service, attempt)
            }, INTERVAL_CLICK_MS)
            return
        }

        // 检测：是否是浏览奖励页面（"每浏览x秒可得1次奖励"等）→ 切换到浏览滑动流程
        // 用户需求：这类页面需要上下滑动获取肥料，直到变成"已领取全部奖励"才返回
        if (service.hasBrowseRewardProgressHint()) {
            Log.i(TAG, "processTask: browse reward page detected (每浏览x秒), switching to BROWSING_TASK")
            debugLog("processTask: browse reward progress hint detected, entering BROWSING_TASK")
            // 已在浏览页面（点击任务按钮后进入），跳过 runBrowsingTask 的初始点击步骤
            browseTaskTargetSwipes = MAX_BROWSE_SWIPES  // 默认滑动次数，由进度提示驱动继续滑动
            browseFromDirectPopup = false  // 来自任务列表，完成后回 OPENING_TASK_LIST
            browseFromSearchBrowse = false
            moveTo(AutomationState.BROWSING_TASK)
            handler.postDelayed({ runBrowsingTask(swipeCount = 1) }, INTERVAL_CLICK_MS)
            return
        }

        // 检测：是否是"浏览x分钟得xxx肥料"停留等待页面 → 切换到浏览流程（不滑动，只等待）
        // 用户需求：这类页面需要停留等待，直到出现"已完成"才返回
        if (service.findBrowseDurationRewardHint() > 0) {
            Log.i(TAG, "processTask: browse duration page detected (浏览x分钟), switching to BROWSING_TASK")
            debugLog("processTask: browse duration hint detected, entering BROWSING_TASK (wait-only)")
            browseTaskTargetSwipes = MAX_BROWSE_SWIPES  // 初始值，runBrowsingTask 内会根据时长动态调整等待上限
            browseFromDirectPopup = false
            browseFromSearchBrowse = false
            moveTo(AutomationState.BROWSING_TASK)
            handler.postDelayed({ runBrowsingTask(swipeCount = 1) }, INTERVAL_CLICK_MS)
            return
        }

        // 检测：是否是"搜索后浏览立得奖励"任务页面 → 点击历史搜索词进入浏览流程
        // 用户需求：点击历史搜索内容进入真正的任务页面（显示"滑动浏览得肥料"），
        // 上下滑动直到"任务完成"，返回两次回到芭芭农场
        if (service.isSearchBrowseTaskPage()) {
            val historyKeyword = service.findHistorySearchKeyword()
            if (historyKeyword != null) {
                Log.i(TAG, "processTask: search browse task page detected, clicking history search keyword")
                debugLog("processTask: clicking history search keyword to enter browse page")
                service.performClickSafe(historyKeyword)
                // 标记来自搜索浏览任务，退出时返回两次
                browseFromSearchBrowse = true
                browseFromDirectPopup = false
                browseTaskTargetSwipes = MAX_BROWSE_SWIPES
                // 等待进入真正的浏览页面（"滑动浏览得肥料"），然后切换到 BROWSING_TASK
                moveTo(AutomationState.BROWSING_TASK)
                handler.postDelayed({ runBrowsingTask(swipeCount = 1) }, INTERVAL_PAGE_LOAD_MS)
                return
            } else {
                // 找不到历史搜索词，按返回退出
                debugLog("processTask: search browse task page but no history keyword found, exiting")
                service.pressBack()
                currentTaskIndex++
                handler.postDelayed({
                    if (state == AutomationState.PROCESSING_TASK) {
                        moveTo(AutomationState.OPENING_TASK_LIST)
                        handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                    }
                }, INTERVAL_PAGE_LOAD_MS)
                return
            }
        }

        // 检测：是否在"下单得肥料"搜索推荐页面 → 退出这个页面
        if (service.isSearchRecommendPage()) {
            Log.i(TAG, "processTask: search recommend page detected, exiting")
            debugLog("processTask: search recommend page, exiting via back icon")
            val backIcon = service.findBackIcon()
            if (backIcon != null) {
                debugLog("processTask: clicking back icon to exit search page")
                service.performClickSafe(backIcon)
            } else {
                debugLog("processTask: no back icon, pressing back")
                service.pressBack()
            }
            currentTaskIndex++
            // 等待返回，然后检查是否回到农场页
            handler.postDelayed({
                if (service.isOnFarmPage()) {
                    // 已回到农场页，重新打开任务列表
                    moveTo(AutomationState.OPENING_TASK_LIST)
                    handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                } else {
                    // 不在农场页（可能回到淘宝主页），需要重新导航到农场
                    debugLog("processTask: not on farm page after exiting search page, re-navigating")
                    service.pressBack()
                    handler.postDelayed({
                        moveTo(AutomationState.NAVIGATING)
                        handler.postDelayed({ runNavigating(0) }, INTERVAL_CLICK_MS)
                    }, INTERVAL_PAGE_LOAD_MS)
                }
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 检测：是否在异常页面（交易页面、商品详情页、收银台等）→ 立即退出
        if (service.isOnAbnormalPage()) {
            Log.i(TAG, "processTask: abnormal/trading page detected, exiting immediately")
            debugLog("processTask: abnormal page, pressing back")
            service.pressBack()
            currentTaskIndex++
            handler.postDelayed({
                if (service.isOnFarmPage()) {
                    moveTo(AutomationState.OPENING_TASK_LIST)
                    handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                } else {
                    debugLog("processTask: not on farm page after abnormal exit, re-navigating")
                    moveTo(AutomationState.NAVIGATING)
                    handler.postDelayed({ runNavigating(0) }, INTERVAL_CLICK_MS)
                }
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 检测是否进入广告（Activity/包名识别 + 内容级识别）
        // 充分理解各种广告设计意图：视频广告/插屏广告/WebView 广告等
        if (service.isAdActivity() || service.isAdPlaying() || service.isAdContentShown()) {
            Log.i(TAG, "processTask: ad opened! watching ad (activity=${service.isAdActivity()}, playing=${service.isAdPlaying()}, content=${service.isAdContentShown()})")
            debugLog("processTask: ad detected, entering WATCHING_AD")
            watchingAdFromDeepLinkTask = false  // build743: 广告入口，非深链任务
            service.setAdMode(true)
            moveTo(AutomationState.WATCHING_AD)
            handler.postDelayed({ runWatchingAd(elapsedMs = 0L) }, INTERVAL_CLICK_MS)
            return
        }

        // 检测非广告任务页面（邀请/关注/分享/下载App/开通会员等）→ 跳过任务
        // 用户要求：只看广告获取肥料，非广告任务不做
        // build758 修复（debug_test_20260829_230753.log, build757, 23:07:18-23:07:27）：
        //   深链浏览任务"去中国移动领话费"点击"去完成"后跳转到移动APP首页，
        //   运营商APP首页必含"充值/理财"等词 → isNonAdTaskPage YES → 误判非广告任务
        //   pressBack 跳过（仅停留9s < 任务要求15s，+300肥料奖励丢失）。
        //   该检测语义是"农场App内WebView任务详情页"（邀请/充值类任务详情页），
        //   深链跳转到外部App后页面内容不可控，不应再用关键词拦截，
        //   交给下方深链分支进 WATCHING_AD 停留等待（stay时长+奖励到账检测）。
        //   守卫：仅当 activeRoot 仍是农场包（或 systemui/空，保持原行为）时才检测。
        val nonAdGuardRootPkg = service.rootInActiveWindowSafe()?.packageName?.toString().orEmpty()
        val nonAdGuardCfg = service.currentPlatformConfig()
        val nonAdGuardActiveRootIsFarm = nonAdGuardRootPkg.isNotEmpty() && (
            nonAdGuardCfg.packageNames.contains(nonAdGuardRootPkg) ||
            nonAdGuardCfg.internalPackagePrefixes.any { nonAdGuardRootPkg.startsWith(it) } ||
            nonAdGuardRootPkg == "com.bbncbot")
        val nonAdGuardIsSystemUi = nonAdGuardRootPkg.isEmpty() || nonAdGuardRootPkg == "com.android.systemui"
        val skipNonAdTaskCheck = !nonAdGuardActiveRootIsFarm && !nonAdGuardIsSystemUi
        if (skipNonAdTaskCheck) {
            debugLog("processTask: deep-linked to external app (activeRoot='$nonAdGuardRootPkg'), skip non-ad-task-page check, fall through to deep-link branch")
        }
        if (!skipNonAdTaskCheck && service.isNonAdTaskPage()) {
            Log.i(TAG, "processTask: non-ad task page detected (invite/share/download/membership), skipping task")
            debugLog("processTask: non-ad task page, skipping task #$${currentTaskIndex + 1}")
            service.pressBack()
            currentTaskIndex++
            handler.postDelayed({
                if (state == AutomationState.PROCESSING_TASK) {
                    moveTo(AutomationState.OPENING_TASK_LIST)
                    handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                }
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 检测是否在非广告页面（邀请链接、应用商店安装页面等）→ 跳过任务
        if (service.isNonAdPage()) {
            Log.i(TAG, "processTask: non-ad package page detected, skipping task")
            debugLog("processTask: non-ad package page, skipping task #$${currentTaskIndex + 1}")
            service.pressBack()
            currentTaskIndex++
            handler.postDelayed({
                if (state == AutomationState.PROCESSING_TASK) {
                    moveTo(AutomationState.OPENING_TASK_LIST)
                    handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                }
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 检测广告深链跳转：跳转到其他App（如淘宝/京东/拼多多浏览商品）
        // 不是应用商店下载、不是非广告任务、不在农场App → 视为广告深链任务进行中
        // 等待用户/机器人在其他App执行任务后回到农场App
        if (!service.isOnFarmPage()) {
            val otherPkg = service.getCurrentWindowPackage()
            // P0-C（build519 修复）：Honor 设备 getCurrentWindowPackage 会误报 systemui
            // 历史问题（debug_test_20260718_205618.log, build517-0885ae7）：
            // - 点击"去完成"按钮后支付宝内部跳转到搜索页（样本含"芭芭农场,搜索,全部,服饰鞋包"）
            // - isOnFarmPage 正确返回 false（搜索页不是农场页）
            // - 但 getCurrentWindowPackage 返回 "com.android.systemui"（Honor 顶部状态栏窗口误报，
            //   详见 FarmAccessibilityService.kt:4264-4266 的注释）
            // - 误判为"深链跳转到 systemui"进入 WATCHING_AD，16ms 后 STOPPING，自动化直接结束
            //
            // 修复：用 rootInActiveWindowSafe().packageName 判断用户实际看到的真实页面
            // - 真实页面是农场包名（支付宝内部跳转）→ getCurrentWindowPackage 误报 systemui，
            //   不进 WATCHING_AD，继续后续 scene 检测（identifyCurrentScene 会处理搜索页等）
            // - 真实页面是 systemui（真正的下拉通知栏/控制中心）→ 等待用户关闭后重试
            // - 真实页面是其他非农场 App（真的深链跳转）→ 进入 WATCHING_AD 等待返回
            val activeRootPkg = service.rootInActiveWindowSafe()?.packageName?.toString().orEmpty()
            val cfg = service.currentPlatformConfig()
            val isActiveRootFarmPkg = activeRootPkg.isNotEmpty() && (
                cfg.packageNames.contains(activeRootPkg) ||
                cfg.internalPackagePrefixes.any { activeRootPkg.startsWith(it) } ||
                activeRootPkg == "com.bbncbot")
            val isRealSystemUiOverlay = otherPkg == "com.android.systemui" &&
                (activeRootPkg.isEmpty() || activeRootPkg == "com.android.systemui")
            when {
                // 真实页面是农场包名 → getCurrentWindowPackage 误报 systemui，不进 WATCHING_AD
                // 继续后续 scene 检测（支付宝内部跳转的搜索页/任务页等）
                isActiveRootFarmPkg -> {
                    debugLog("processTask: not on farm page but activeRoot='$activeRootPkg' is farm pkg, otherPkg='$otherPkg' (systemui false positive), skip WATCHING_AD, continue scene detection")
                }
                // 真实页面是 systemui（真正的下拉通知栏/控制中心）→ 等待用户关闭后重试
                isRealSystemUiOverlay -> {
                    debugLog("processTask: real systemui overlay detected (activeRoot='$activeRootPkg'), waiting for dismissal, retry attempt=$attempt")
                    handler.postDelayed({
                        if (state == AutomationState.PROCESSING_TASK) runProcessingTask(attempt + 1)
                    }, INTERVAL_PAGE_LOAD_MS)
                    return
                }
                // 真实页面是非农场 App（真的深链跳转）→ 进入 WATCHING_AD
                else -> {
                    Log.i(TAG, "processTask: deep-linked to another app (otherPkg=$otherPkg, activeRoot=$activeRootPkg), treating as ad task")
                    debugLog("processTask: deep-link ad task, otherPkg=$otherPkg, activeRoot=$activeRootPkg, entering WATCHING_AD to wait for return to farm")
                    watchingAdFromDeepLinkTask = true  // build743: 深链任务入口，trap 分支放行交给深链分支处理
                    service.setAdMode(true)
                    moveTo(AutomationState.WATCHING_AD)
                    handler.postDelayed({ runWatchingAd(elapsedMs = 0L) }, INTERVAL_CLICK_MS)
                    return
                }
            }
        }

        // 检测是否还在农场页（点击无效果 / 签到答题弹窗 / 任务完成弹窗）
        // 或签到页面（签到页在农场 App WebView 内，但 isOnFarmPage 可能返回 false，
        // 因为签到页没有"集肥料"/"施肥"等农场核心元素；用场景识别 SIGN_IN 兜底进入此分支）
        val scene = service.identifyCurrentScene()

        // 答题页面检测（问题 + 2 个选项，需调用 AI API 获取答案）
        // 用户需求：回答问题就可以领取肥料，可以思考下认真回答问题
        // 答题只有一次机会，不能试错，必须调用 AI 获取正确答案后再点击
        if (scene == FarmAccessibilityService.PageScene.QUIZ_PAGE) {
            val question = service.findQuizQuestion()
            val options = service.findQuizOptions()
            if (question.isBlank() || options.size != 2) {
                Log.w(TAG, "processTask: QUIZ_PAGE but question/options invalid (q='$question', opts=${options.size}), skipping")
                debugLog("processTask: quiz page but invalid question/options, skipping task")
                service.pressBack()
                currentTaskIndex++
                handler.postDelayed({
                    if (state == AutomationState.PROCESSING_TASK) {
                        moveTo(AutomationState.OPENING_TASK_LIST)
                        handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                    }
                }, INTERVAL_PAGE_LOAD_MS)
                return
            }

            val opt1Text = options[0].text?.toString().orEmpty().ifBlank {
                options[0].contentDescription?.toString().orEmpty()
            }
            val opt2Text = options[1].text?.toString().orEmpty().ifBlank {
                options[1].contentDescription?.toString().orEmpty()
            }
            Log.i(TAG, "processTask: quiz page detected (q='${question.take(60)}', opt1='$opt1Text', opt2='$opt2Text'), asking AI")
            debugLog("processTask: quiz detected (q='${question.take(60)}', opt1='$opt1Text', opt2='$opt2Text'), calling GLM API")

            // 网络请求必须在后台线程（QuizAnswerClient.askAnswer 含网络 IO）
            // 保存节点引用的文本和索引，避免后台线程返回后节点失效
            val context = service.applicationContext
            val opt1NodeText = opt1Text
            val opt2NodeText = opt2Text
            Thread {
                val aiAnswer = QuizAnswerClient.askAnswer(context, question, opt1NodeText, opt2NodeText)
                handler.post {
                    if (state != AutomationState.PROCESSING_TASK) return@post
                    if (aiAnswer.isBlank()) {
                        // AI 获取答案失败：默认选第一个选项（兜底，避免放弃任务）
                        Log.w(TAG, "processTask: AI answer empty, defaulting to first option '$opt1NodeText'")
                        debugLog("processTask: AI answer failed, defaulting to first option")
                        val opts = service.findQuizOptions()
                        if (opts.isNotEmpty()) {
                            service.performClickSafe(opts[0])
                        } else {
                            debugLog("processTask: quiz options gone after AI call, pressing back")
                            service.pressBack()
                        }
                    } else {
                        // AI 返回了答案，重新查找选项节点（后台线程期间节点可能已失效）
                        val opts = service.findQuizOptions()
                        val target = opts.firstOrNull { opt ->
                            val t = opt.text?.toString().orEmpty().ifBlank {
                                opt.contentDescription?.toString().orEmpty()
                            }
                            t == aiAnswer
                        }
                        if (target != null) {
                            Log.i(TAG, "processTask: clicking AI-selected answer '$aiAnswer'")
                            debugLog("processTask: clicking AI answer '$aiAnswer'")
                            service.performClickSafe(target)
                        } else {
                            // 文本匹配失败，按位置兜底（AI 答案是 opt1 则点第一个，否则点第二个）
                            val fallbackIndex = if (aiAnswer == opt1NodeText) 0 else 1
                            Log.w(TAG, "processTask: AI answer '$aiAnswer' node not found, fallback to option #$fallbackIndex")
                            debugLog("processTask: AI answer node not found, fallback to option #$fallbackIndex")
                            if (opts.isNotEmpty() && fallbackIndex < opts.size) {
                                service.performClickSafe(opts[fallbackIndex])
                            } else {
                                service.pressBack()
                            }
                        }
                    }
                    // 点击答案后，等待答题结果（答对领取肥料 / 答错提示）
                    handler.postDelayed({
                        if (state == AutomationState.PROCESSING_TASK) checkTaskResult(service, attempt + 1)
                    }, INTERVAL_PAGE_LOAD_MS)
                }
            }.start()
            return
        }

        // build610: AI 视觉答题（答题页是 H5/Canvas 绘制，无障碍树抓不到问题+选项文本）
        // 用户需求："去答题，任务需要选择一个答案，可以借助AI接口来选择答案"
        // 日志 debug_test_20260722_222228.log 显示：点击"去答题"后答题页内容抓不到，
        // isQuizPage()=false（找不到 2 个选项 + 问题），scene 不是 QUIZ_PAGE，
        // 走到 onFarm 分支点"返回首页"退出农场，答题任务失败。
        // 修复：若 currentTaskIsQuiz=true 且 isQuizPage()=false，截图交给 AI 视觉模型，
        // 让 AI 识别题目和选项，选出正确答案，返回正确选项坐标，按坐标点击。
        if (currentTaskIsQuiz && scene != FarmAccessibilityService.PageScene.QUIZ_PAGE) {
            debugLog("processTask: quiz task but isQuizPage=false (H5/Canvas content not in a11y tree), trying AI vision to answer")
            val context = service.applicationContext
            Thread {
                // build612 修复：AI 视觉答题增加等待 + 截图重试机制。
                // 日志 debug_test_20260725_190222.log 显示：点击"去答题"后 6 秒 checkTaskResult 触发 AI 视觉，
                // 但此时 onFarm=true 仍农场页，答题页 H5/Canvas 可能还在加载，
                // AI 截图拿到的是农场页或半渲染答题页，返回 null（no answer found），答题失败跳过。
                // 修复：截图前等待 2 秒让答题页加载；AI 返回 null 时重试截图+识别（最多 3 次，每次间隔 2 秒）。
                val maxRetries = 3
                var result: com.bbncbot.automation.AiVisionClient.ButtonLocationResult? = null
                var lastBitmapNull = false
                for (retry in 0 until maxRetries) {
                    if (retry > 0) {
                        try {
                            Thread.sleep(2000)
                        } catch (ie: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return@Thread
                        }
                        debugLog("processTask: AI vision quiz retry $retry/$maxRetries (previous attempt returned null)")
                    }
                    val bitmap = service.takeScreenshotBitmap()
                    if (bitmap == null) {
                        lastBitmapNull = true
                        Log.w(TAG, "processTask: AI vision quiz screenshot null (retry $retry/$maxRetries)")
                        continue
                    }
                    lastBitmapNull = false
                    result = AiVisionClient.answerQuizByVision(
                        context, bitmap, "淘宝/支付宝芭芭农场答题页（农场百科问答）",
                        logger = { msg -> debugLog("processTask: $msg") }
                    )
                    if (result != null) break
                }
                handler.post {
                    if (state != AutomationState.PROCESSING_TASK) return@post
                    if (result == null) {
                        val reason = if (lastBitmapNull) "screenshot null" else "no answer found"
                        Log.w(TAG, "processTask: AI vision quiz failed ($reason) after $maxRetries retries, skipping task")
                        debugLog("processTask: AI vision quiz failed ($reason) after $maxRetries retries, skipping task")
                        currentTaskIsQuiz = false
                        currentTaskIndex++
                        moveTo(AutomationState.OPENING_TASK_LIST)
                        handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_PAGE_LOAD_MS)
                        return@post
                    }
                    // 按坐标比例点击正确答案选项
                    val screenW = service.resources.displayMetrics.widthPixels
                    val screenH = service.resources.displayMetrics.heightPixels
                    val x = result.xRatio * screenW
                    val y = result.yRatio * screenH
                    Log.i(TAG, "processTask: AI vision quiz answer at ($x,$y) [ratio=${result.xRatio},${result.yRatio}], reason='${result.reason.take(80)}'")
                    debugLog("processTask: AI vision quiz clicking answer at ($x,$y), reason='${result.reason.take(60)}'")
                    service.dispatchGestureClick(x, y)
                    // build616 修复3：点击答案后检测"领取奖励"/"领取鼓励奖"按钮并点击领取，
                    // 否则任务进度始终 0/1，任务列表重置 currentTaskIndex=0 又回到答题任务，死循环。
                    //
                    // 日志 debug_test_20260725_195306.log 显示：
                    // - 19:41:32.232 AI 点击答案 (600, 2331)（屏幕底部空白区域，坐标不准）
                    // - 19:41:37.239 build611 修复直接前进下一任务（PROCESSING_TASK -> OPENING_TASK_LIST）
                    // - 19:41:43.376 openTaskList 检测到屏幕上已有"领取奖励 500"按钮（答对了！）
                    //   但 build611 跳过了领取步骤，没点击该按钮
                    // - 19:41:50.017 任务列表显示"去答题 (0/1)"（进度仍 0/1，未完成）
                    // - 重新点"去答题" → 死循环 8 次（19:41:32~19:48:00）
                    //
                    // 修复：点击答案后等 2.5 秒让奖励按钮渲染，查找并点击"领取奖励"/"领取鼓励奖"
                    // 按钮（不受场景白名单限制），再等 INTERVAL_PAGE_LOAD_MS 让弹窗消失，然后才前进下一任务。
                    // build619 增强：首次 2.5 秒未检测到奖励按钮时，再等 2.5 秒重试一次（共 2 次检测），
                    //              提高奖励按钮渲染慢时的检测成功率。
                    // - 答对：弹出"领取奖励 500"，点击领取 → 任务完成 1/1
                    // - 答错：弹出"领取鼓励奖 150"，点击领取 → 也有肥料奖励，任务完成 1/1
                    // - 都没检测到：仍前进下一任务（保持 build611 行为，避免 onFarm 误点返回首页）
                    //
                    // build621 修复：原 build619 把 rewardFirstCheckDone 声明在 Runnable 之后，
                    // 导致 Runnable 内部引用时 Unresolved reference；同时 val checkRewardAndProceed
                    // 在自身初始化 lambda 内自引用也不允许。改为：rewardFirstCheckDone 提前声明，
                    // checkRewardAndProceed 用 var + 可空类型延迟赋值解决自引用。
                    var rewardFirstCheckDone = false  // build619: 标记是否已做过首次检测（移到 Runnable 之前，确保闭包可见）
                    var checkRewardAndProceed: Runnable? = null  // build621: 用 var 延迟赋值，解决 val 自引用问题
                    checkRewardAndProceed = Runnable {
                        if (state != AutomationState.PROCESSING_TASK) return@Runnable
                        val rewardBtn = service.findQuizRewardButton()
                        if (rewardBtn != null) {
                            val rewardText = rewardBtn.text?.toString().orEmpty()
                            Log.i(TAG, "processTask: AI vision quiz reward found (text='$rewardText'), clicking to claim")
                            debugLog("processTask: AI vision quiz reward found (text='$rewardText'), clicking to claim")
                            service.performClickSafe(rewardBtn)
                            // build617: 答题成功（检测到奖励按钮），重置失败计数器
                            quizVisionFailCount = 0
                            // 领取后等待弹窗消失，再前进下一任务
                            handler.postDelayed({
                                if (state == AutomationState.PROCESSING_TASK) {
                                    currentTaskIsQuiz = false
                                    currentTaskIndex++
                                    moveTo(AutomationState.OPENING_TASK_LIST)
                                    handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_PAGE_LOAD_MS)
                                }
                            }, INTERVAL_PAGE_LOAD_MS)
                        } else {
                            // build619: 首次检测未找到，标记后等 2.5 秒再检测一次
                            if (!rewardFirstCheckDone) {
                                rewardFirstCheckDone = true
                                Log.w(TAG, "processTask: AI vision quiz no reward button yet, retrying in 2.5s")
                                debugLog("processTask: AI vision quiz no reward button yet, retrying in 2.5s")
                                handler.postDelayed(checkRewardAndProceed!!, 2500L)
                            } else {
                                // 第二次仍未找到，确认为失败
                                Log.w(TAG, "processTask: AI vision quiz no reward button detected after 2 checks")
                                debugLog("processTask: AI vision quiz no reward button detected after 2 checks")
                                // build617: 未检测到奖励按钮（AI 坐标不准没点到选项，或答题页未响应）
                                // 累计失败次数，超过阈值后 openTaskList 会强制跳过答题任务，避免死循环
                                quizVisionFailCount++
                                debugLog("processTask: AI vision quiz fail count=$quizVisionFailCount/$QUIZ_VISION_FAIL_THRESHOLD")
                                // 仍前进下一任务（保持 build611 行为，避免 onFarm 误点返回首页）
                                handler.postDelayed({
                                    if (state == AutomationState.PROCESSING_TASK) {
                                        currentTaskIsQuiz = false
                                        currentTaskIndex++
                                        moveTo(AutomationState.OPENING_TASK_LIST)
                                        handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_PAGE_LOAD_MS)
                                    }
                                }, INTERVAL_PAGE_LOAD_MS)
                            }
                        }
                    }
                    handler.postDelayed(checkRewardAndProceed!!, 2500L)  // 等待 2.5 秒让奖励按钮渲染
                }
            }.start()
            return
        }

        if (service.isOnFarmPage() || scene == FarmAccessibilityService.PageScene.SIGN_IN) {
            // 尝试点击"返回首页"按钮（任务完成弹窗）
            val backBtn = service.findBackToHomeButton()
            if (backBtn != null) {
                Log.i(TAG, "processTask: found '返回首页' button on farm page, clicking")
                debugLog("processTask: found 返回首页 button, clicking")
                service.performClickSafe(backBtn)
                handler.postDelayed({
                    if (state == AutomationState.PROCESSING_TASK) {
                        // 返回首页后重新打开任务列表
                        moveTo(AutomationState.OPENING_TASK_LIST)
                        handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                    }
                }, INTERVAL_PAGE_LOAD_MS)
                return
            }
            // 尝试点击签到确认/答题/领取奖励按钮
            // 场景白名单已放行 SIGN_IN，findClaimRewardButton 会匹配"立即签到"/"签到领取"/"签到"
            val claimBtn = service.findClaimRewardButton()
            if (claimBtn != null) {
                val claimText = claimBtn.text?.toString().orEmpty()
                Log.i(TAG, "processTask: found claim/confirm button on farm page (text='$claimText'), clicking")
                debugLog("processTask: claim button on farm (text='$claimText', scene=$scene), clicking")
                service.performClickSafe(claimBtn)
                // 点击签到/领取按钮后，不立即跳下一个任务：
                // 签到按钮点击后会弹出"签到成功，获得 xxx 肥料"提示，需要先关闭弹窗才能继续。
                // 改为 checkTaskResult(attempt+1) 重新检测，让 isFertilizerGrantedPage 分支
                // 检测签到成功弹窗并关闭，那个分支会 currentTaskIndex++ 前进到下一个任务。
                // （答题确认等场景同样安全：点击确认后重新检测，确认任务真的完成了再前进）
                handler.postDelayed({
                    if (state == AutomationState.PROCESSING_TASK) checkTaskResult(service, attempt + 1)
                }, INTERVAL_PAGE_LOAD_MS)
                return
            }
            // WebView 中 performClickSafe 已经尝试了 ACTION_CLICK + dispatchGesture 修正坐标
            // 如果仍然在农场页，说明按钮点击确实无效（可能按钮已失效、需要滚动等）
            if (attempt < MAX_TASK_ATTEMPTS - 1) {
                Log.i(TAG, "processTask: still on farm page (attempt $attempt), retry clicking task button")
                debugLog("processTask: still on farm page, retry task click attempt=$attempt")
                // 重新获取任务按钮并点击（可能列表已刷新）
                val buttons = service.findGoCompleteButtons()
                if (buttons.isNotEmpty() && currentTaskIndex < buttons.size) {
                    taskButtons = buttons
                    service.performClickSafe(buttons[currentTaskIndex])
                } else {
                    // 按钮列表变了，需要重新打开任务列表
                    Log.w(TAG, "processTask: task buttons changed, reopening task list")
                    moveTo(AutomationState.OPENING_TASK_LIST)
                    handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                    return
                }
                handler.postDelayed({
                    if (state == AutomationState.PROCESSING_TASK) checkTaskResult(service, attempt + 1)
                }, INTERVAL_PAGE_LOAD_MS)
                return
            }
            Log.w(TAG, "processTask: still on farm page after $MAX_TASK_ATTEMPTS attempts, skipping task")
            debugLog("processTask: still on farm page after $MAX_TASK_ATTEMPTS attempts, skipping task")
            // 跳过当前任务，继续下一个
            currentTaskIndex++
            noProgressRounds++
            handler.postDelayed({
                if (state == AutomationState.PROCESSING_TASK) runProcessingTask(0)
            }, INTERVAL_CLICK_MS)
            return
        }

        // 其他情况：不在农场页，也不是广告（如商品详情页、逛逛页面等），按返回键
        // 这种情况说明点击了"去逛逛"等按钮进入了非广告页面，需要返回
        if (service.isOnAbnormalPage()) {
            debugLog("processTask: abnormal/trading page detected, pressing back and skipping task")
            service.pressBack()
            currentTaskIndex++
            handler.postDelayed({
                if (!service.isOnFarmPage()) service.pressBack()
                handler.postDelayed({
                    moveTo(AutomationState.OPENING_TASK_LIST)
                    handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                }, INTERVAL_CLICK_MS)
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }
        Log.i(TAG, "processTask: unknown page (not farm, not ad), failCount=$currentTaskFailCount/$MAX_TASK_FAILS")
        debugLog("processTask: unknown page, pkg=${service.getCurrentWindowPackage()}, act=${service.getCurrentActivityName()}, failCount=$currentTaskFailCount/$MAX_TASK_FAILS")

        // build778 修复（debug_test_20260905_171627.log, 16:55:48）：
        //   "大圣顶住"游戏任务加载中（AI 已正确判 WAIT，加载 20%），30s 后复查时前台
        //   瞬时变成 com.hihonor.secime（系统键盘 IME 窗口）→ 被当 unknown page
        //   计 failCount=2/2 → MAX_TASK_FAILS 误跳任务。
        //   IME/键盘窗口是瞬时的（弹出即消），不应计入任务失败次数。
        //   修复：当前窗口/活动窗口包名疑似 IME（含 "ime"/"inputmethod"）时，
        //   等待后重新检测，不计 failCount；attempt 达上限后回退正常计数，防死循环。
        if (attempt < MAX_TASK_ATTEMPTS) {
            val winPkgNow = service.getCurrentWindowPackage().orEmpty().lowercase()
            val activePkgNow = service.rootInActiveWindowSafe()?.packageName?.toString()?.lowercase().orEmpty()
            val isTransientIme = winPkgNow.contains("ime") || winPkgNow.contains("inputmethod") ||
                activePkgNow.contains("ime") || activePkgNow.contains("inputmethod")
            if (isTransientIme) {
                debugLog("processTask: transient IME/keyboard window (winPkg=$winPkgNow, activePkg=$activePkgNow), wait & recheck without failCount++ (build778)")
                Log.i(TAG, "processTask: transient IME/keyboard window (winPkg=$winPkgNow), wait & recheck without failCount++ (build778)")
                handler.postDelayed({
                    if (state == AutomationState.PROCESSING_TASK) checkTaskResult(service, attempt + 1)
                }, INTERVAL_PAGE_LOAD_MS)
                return
            }
        }

        // 未知页面 → 失败计数
        currentTaskFailCount++

        // 失败次数已达上限 → 跳过该任务，避免在无法完成的任务上死循环
        if (currentTaskFailCount >= MAX_TASK_FAILS) {
            Log.w(TAG, "processTask: task failed $currentTaskFailCount times (AI exhausted), skipping task #${currentTaskIndex + 1}")
            debugLog("processTask: reached MAX_TASK_FAILS after AI exhausted, skipping task #${currentTaskIndex + 1}")
            service.pressBack()
            currentTaskIndex++
            noProgressRounds++
            handler.postDelayed({
                if (state == AutomationState.PROCESSING_TASK) {
                    moveTo(AutomationState.OPENING_TASK_LIST)
                    handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                }
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 用户需求：有些不能处理的问题可以截图交给 API 来处理
        // 未知页面兜底：截图交给智谱 GLM-4.6V-Flash 视觉模型，让 AI 决定下一步动作
        // 不限制调用次数（用户明确选择），每次进入 UNKNOWN 都调（除非 API Key 未配置）
        val appContext = service.applicationContext
        val sceneContext = "task #${currentTaskIndex + 1}, failCount=$currentTaskFailCount/$MAX_TASK_FAILS, " +
            "pkg=${service.getCurrentWindowPackage()}, act=${service.getCurrentActivityName()}"
        debugLog("processTask: unknown page, asking AI vision for action")
        Thread {
            val bitmap = service.takeScreenshotBitmap()
            if (bitmap == null) {
                debugLog("processTask: AI vision skipped, screenshot not available")
                handler.post {
                    if (state != AutomationState.PROCESSING_TASK) return@post
                    debugLog("processTask: screenshot unavailable, pressing back")
                    service.pressBack()
                    handler.postDelayed({
                        if (state == AutomationState.PROCESSING_TASK) {
                            moveTo(AutomationState.OPENING_TASK_LIST)
                            handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                        }
                    }, INTERVAL_PAGE_LOAD_MS)
                }
                return@Thread
            }
            try {
                val result = AiVisionClient.analyzeScreenshot(appContext, bitmap, sceneContext)
                bitmap.recycle()
                handler.post {
                    if (state != AutomationState.PROCESSING_TASK) return@post
                    if (result == null) {
                        debugLog("processTask: AI vision returned null, pressing back")
                        service.pressBack()
                        handler.postDelayed({
                            if (state == AutomationState.PROCESSING_TASK) {
                                moveTo(AutomationState.OPENING_TASK_LIST)
                                handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                            }
                        }, INTERVAL_PAGE_LOAD_MS)
                        return@post
                    }
                    debugLog("processTask: AI vision action=${result.action}, reason='${result.reason.take(80)}'")
                    Log.i(TAG, "processTask: AI vision action=${result.action}, reason='${result.reason.take(80)}'")
                    executeAiVisionAction(service, result.action)
                }
            } catch (e: Exception) {
                Log.e(TAG, "processTask: AI vision exception: ${e.message}", e)
                if (!bitmap.isRecycled) bitmap.recycle()
                handler.post {
                    if (state != AutomationState.PROCESSING_TASK) return@post
                    debugLog("processTask: AI vision exception, pressing back")
                    service.pressBack()
                    handler.postDelayed({
                        if (state == AutomationState.PROCESSING_TASK) {
                            moveTo(AutomationState.OPENING_TASK_LIST)
                            handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                        }
                    }, INTERVAL_PAGE_LOAD_MS)
                }
            }
        }.start()
    }

    /**
     * 执行 AI 视觉决策返回的动作
     *
     * 5 个预定义动作的执行逻辑：
     * - CLICK_CLOSE  : 查找关闭按钮（×/关闭/知道了/确定）并点击，失败按返回
     * - CLICK_CLAIM  : 查找领取按钮（领取/领取奖励/领取肥料/确定）并点击，失败按返回
     * - PRESS_BACK   : 直接按返回键
     * - SKIP_TASK    : 跳过当前任务（currentTaskIndex++）并打开任务列表
     * - WAIT         : 不操作，等待后重新检测场景
     *
     * 所有动作执行后都通过 [checkTaskResult] 重新评估场景（WAIT 除外，WAIT 直接重试 processTask）。
     */
    private fun executeAiVisionAction(
        service: FarmAccessibilityService,
        action: AiVisionAction
    ) {
        when (action) {
            AiVisionAction.CLICK_CLOSE -> {
                debugLog("executeAiVisionAction: CLICK_CLOSE - finding close button")
                val closeBtn = service.findAdCloseButton(enforceSceneWhitelist = false)
                if (closeBtn != null) {
                    service.performClickSafe(closeBtn)
                } else {
                    debugLog("executeAiVisionAction: no close button found, pressing back")
                    service.pressBack()
                }
                handler.postDelayed({
                    if (state == AutomationState.PROCESSING_TASK) checkTaskResult(service, 0)
                }, INTERVAL_PAGE_LOAD_MS)
            }
            AiVisionAction.CLICK_CLAIM -> {
                debugLog("executeAiVisionAction: CLICK_CLAIM - finding claim button")
                val claimBtn = service.findClaimRewardButton(enforceSceneWhitelist = false)
                if (claimBtn != null) {
                    service.performClickSafe(claimBtn)
                } else {
                    debugLog("executeAiVisionAction: no claim button found, pressing back")
                    service.pressBack()
                }
                handler.postDelayed({
                    if (state == AutomationState.PROCESSING_TASK) checkTaskResult(service, 0)
                }, INTERVAL_PAGE_LOAD_MS)
            }
            AiVisionAction.PRESS_BACK -> {
                debugLog("executeAiVisionAction: PRESS_BACK")
                service.pressBack()
                handler.postDelayed({
                    if (state == AutomationState.PROCESSING_TASK) checkTaskResult(service, 0)
                }, INTERVAL_PAGE_LOAD_MS)
            }
            AiVisionAction.SKIP_TASK -> {
                debugLog("executeAiVisionAction: SKIP_TASK - skipping task #${currentTaskIndex + 1}")
                Log.i(TAG, "processTask: AI suggested SKIP_TASK, skipping task #${currentTaskIndex + 1}")
                service.pressBack()
                currentTaskIndex++
                noProgressRounds++
                handler.postDelayed({
                    if (state == AutomationState.PROCESSING_TASK) {
                        moveTo(AutomationState.OPENING_TASK_LIST)
                        handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                    }
                }, INTERVAL_PAGE_LOAD_MS)
            }
            AiVisionAction.WAIT -> {
                debugLog("executeAiVisionAction: WAIT - will recheck scene after delay")
                handler.postDelayed({
                    if (state == AutomationState.PROCESSING_TASK) checkTaskResult(service, 0)
                }, INTERVAL_PAGE_LOAD_MS * 2)
            }
        }
    }

    // ============== 阶段3c: 蚂蚁森林领落叶肥料 ==============

    /**
     * 蚂蚁森林领落叶肥料任务
     * - 用户要求：关闭弹出页面上的其它弹窗 → 点击"领奖励" → 找到"逛农场得落叶肥料"
     *
     * 步骤：
     * - step 0: 关闭弹窗（查找 × / 关闭 / 知道了 / 我知道了 等按钮并点击）
     * - step 1: 查找并点击"领奖励"按钮
     * - step 2: 查找并点击"逛农场得落叶肥料"入口
     * - step 3: 等待回到农场页，任务完成
     *
     * 每步最多重试 [MAX_FOREST_RETRIES] 次，失败则用 AI 视觉辅助
     */
    private fun runForestCollecting(step: Int, retryCount: Int) {
        if (state != AutomationState.FOREST_COLLECTING) return
        val service = getService() ?: run { stop(); return }

        debugLog("forestCollect: step=$step, retry=$retryCount")

        // 回到农场页 → 任务完成
        if (service.isOnFarmPage()) {
            Log.i(TAG, "forestCollect: back on farm page, task complete")
            debugLog("forestCollect: back on farm, done")
            currentTaskIndex++
            moveTo(AutomationState.OPENING_TASK_LIST)
            handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
            return
        }

        when (step) {
            0 -> {
                // 关闭弹窗：查找 × / 关闭 / 知道了 / 我知道了 / 知道啦 等按钮
                val popupKeywords = listOf("知道了", "我知道了", "知道啦", "关闭", "×", "确定", "好的")
                val root = service.rootInActiveWindowSafe()
                var closedPopup = false
                if (root != null) {
                    for (kw in popupKeywords) {
                        val node = service.findNodeByText(root, kw)
                        if (node != null) {
                            debugLog("forestCollect: closing popup '$kw'")
                            service.performClickSafe(node)
                            closedPopup = true
                            break
                        }
                    }
                }
                if (closedPopup) {
                    // 弹窗关闭后，继续检查是否还有弹窗
                    handler.postDelayed({
                        if (state == AutomationState.FOREST_COLLECTING) runForestCollecting(step = 0, retryCount = 0)
                    }, INTERVAL_CLICK_MS)
                } else {
                    // 没有弹窗了，进入下一步（领奖励）
                    debugLog("forestCollect: no more popups, moving to claim reward")
                    handler.postDelayed({
                        if (state == AutomationState.FOREST_COLLECTING) runForestCollecting(step = 1, retryCount = 0)
                    }, INTERVAL_CLICK_MS)
                }
            }
            1 -> {
                // 查找并点击"领奖励"按钮
                val root = service.rootInActiveWindowSafe()
                var claimed = false
                if (root != null) {
                    val claimKeywords = listOf("领奖励", "领取奖励", "立即领取", "领取", "收下", "好的领")
                    for (kw in claimKeywords) {
                        val node = service.findNodeByText(root, kw)
                        if (node != null) {
                            debugLog("forestCollect: clicking claim button '$kw'")
                            service.performClickSafe(node)
                            claimed = true
                            break
                        }
                    }
                }
                if (claimed) {
                    // 领奖励后，进入下一步（逛农场得落叶肥料）
                    handler.postDelayed({
                        if (state == AutomationState.FOREST_COLLECTING) runForestCollecting(step = 2, retryCount = 0)
                    }, INTERVAL_PAGE_LOAD_MS)
                } else if (retryCount < 3) {
                    // 重试查找领奖励按钮
                    debugLog("forestCollect: claim button not found, retry $retryCount")
                    handler.postDelayed({
                        if (state == AutomationState.FOREST_COLLECTING) runForestCollecting(step = 1, retryCount = retryCount + 1)
                    }, INTERVAL_CLICK_MS)
                } else {
                    // 多次重试失败，跳过此步直接找"逛农场得落叶肥料"
                    debugLog("forestCollect: claim button not found after retries, skipping to step 2")
                    runForestCollecting(step = 2, retryCount = 0)
                }
            }
            2 -> {
                // 查找并点击"逛农场得落叶肥料"入口
                val root = service.rootInActiveWindowSafe()
                var found = false
                if (root != null) {
                    val farmKeywords = listOf("逛农场得落叶肥料", "逛农场", "落叶肥料", "得落叶肥料", "回农场", "回芭芭农场")
                    for (kw in farmKeywords) {
                        val node = service.findNodeByText(root, kw)
                        if (node != null) {
                            debugLog("forestCollect: clicking farm entry '$kw'")
                            service.performClickSafe(node)
                            found = true
                            break
                        }
                    }
                }
                if (found) {
                    // 点击后等待回到农场页
                    handler.postDelayed({
                        if (state == AutomationState.FOREST_COLLECTING) runForestCollecting(step = 3, retryCount = 0)
                    }, INTERVAL_PAGE_LOAD_MS)
                } else if (retryCount < 3) {
                    debugLog("forestCollect: farm entry not found, retry $retryCount")
                    handler.postDelayed({
                        if (state == AutomationState.FOREST_COLLECTING) runForestCollecting(step = 2, retryCount = retryCount + 1)
                    }, INTERVAL_CLICK_MS)
                } else {
                    // 重试失败，按返回回到农场
                    debugLog("forestCollect: farm entry not found, pressing back to return to farm")
                    service.pressBack()
                    handler.postDelayed({
                        if (state == AutomationState.FOREST_COLLECTING) runForestCollecting(step = 3, retryCount = 0)
                    }, INTERVAL_PAGE_LOAD_MS)
                }
            }
            else -> {
                // step 3+: 等待回到农场页
                if (service.isOnFarmPage()) {
                    Log.i(TAG, "forestCollect: back on farm page, task complete")
                    debugLog("forestCollect: back on farm, done")
                    currentTaskIndex++
                    moveTo(AutomationState.OPENING_TASK_LIST)
                    handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                } else if (retryCount < 5) {
                    // 还没回到农场，按返回重试
                    debugLog("forestCollect: not on farm yet, pressing back (retry $retryCount)")
                    service.pressBack()
                    handler.postDelayed({
                        if (state == AutomationState.FOREST_COLLECTING) runForestCollecting(step = 3, retryCount = retryCount + 1)
                    }, INTERVAL_PAGE_LOAD_MS)
                } else {
                    // 超过重试次数，放弃此任务
                    Log.w(TAG, "forestCollect: failed to return to farm after retries, skipping task")
                    debugLog("forestCollect: failed to return, skipping task")
                    currentTaskIndex++
                    moveTo(AutomationState.OPENING_TASK_LIST)
                    handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                }
            }
        }
    }

    // ============== 阶段4: 看广告 ==============

    /**
     * build729/build730: 检测广告页面是否出现"奖励已发放"等已领奖标志
     *
     * 用户需求："遇到奖励已发放，右边的关闭图标，需要点击关闭，就获得奖励了"
     * 奖励到账标志出现说明奖励已发放,点击右上角关闭图标退出即获得奖励。
     *
     * 关键词与 isAdEndedMultiSignal 的 adEndedKeywords 保持一致(取无歧义子集,
     * 排除"恭喜获得"/"获取奖励"/"获得肥料"等易误判落地页营销文案的泛化词)。
     */
    private fun detectRewardGrantedText(service: FarmAccessibilityService): Boolean {
        val root = service.getRootInFarmApp() ?: return false
        val texts = service.collectAllText(root)
        return texts.any {
            it.contains("奖励已发放") || it.contains("奖励已到账") ||
            it.contains("领取成功") || it.contains("已领取奖励") ||
            it.contains("肥料已到账") || it.contains("肥料已发放") ||
            it.contains("恭喜获取奖励") || it.contains("恭喜获得奖励")
        }
    }

    /**
     * build729/build730: 点击右上角关闭图标退出广告,领取已发放的奖励
     *
     * 检测到"奖励已发放"等已领奖标志后调用:
     * 1. findAdCloseButton 找右上角关闭图标并点击(找不到则 pressBack 兜底)
     * 2. setAdMode(false) + collectedCount++ + 进 RETURNING 回农场
     */
    private fun claimRewardViaCloseIcon(service: FarmAccessibilityService, elapsedMs: Long) {
        Log.i(TAG, "watchAd: reward granted text detected (奖励已发放), clicking right close icon to claim reward (elapsed=${elapsedMs}ms)")
        debugLog("watchAd: 检测到'奖励已发放'(奖励已到账), 立即点击右上角关闭图标退出广告获得奖励 (elapsed=${elapsedMs}ms)")
        // build731: 奖励已领,清除汇川商家等待标志(防止 RETURNING 后残留影响后续逻辑)
        huichuanMerchantPending = false
        val closeIcon = service.findAdCloseButton(service.currentPlatformConfig().adCloseButtonTexts, enforceSceneWhitelist = false)
        if (closeIcon != null) {
            debugLog("watchAd: clicking right close icon to close ad and secure reward")
            service.performClickSafe(closeIcon)
        } else {
            debugLog("watchAd: close icon not found, pressing back to close ad")
            service.pressBack()
        }
        service.setAdMode(false)
        collectedCount++
        Log.i(TAG, "=== FERTILIZER COLLECTED! (total: $collectedCount) ===")
        moveTo(AutomationState.RETURNING)
        handler.postDelayed({ runReturning(attempt = 0) }, INTERVAL_PAGE_LOAD_MS)
    }

    /**
     * 看广告阶段：等待广告播放完成
     * - 进入时解析广告页面时长提示（如"观看15秒"），动态设置最短等待时间
     * - 最短等待 adMinDurationMs（默认30秒，或页面提示时长+缓冲）
     * - 超时 adMaxDurationMs（默认90秒，长广告动态延长）强制关闭
     * - 动态检测广告是否结束
     *
     * 用户要求：太快退出可能获取不到肥料，需保持到规定时间+缓冲后再检测退出
     */
    private fun runWatchingAd(elapsedMs: Long) {
        if (state != AutomationState.WATCHING_AD) return
        val service = getService() ?: run { stop(); return }

        // 进入广告时（首次调用）解析页面时长提示，动态设置最短等待时间
        // 用户要求：有些广告需要指定时间才能领取肥料，保持到规定时间+1秒后再检测退出
        if (elapsedMs == 0L) {
            deepLinkAppPkg = null       // 重置深链跳转跟踪，等待检测是否进入其他 App
            fasterRewardStage = 0       // 重置"更快拿奖"弹窗处理状态
            fasterRewardAppPkg = null   // 重置新 App 包名记录
            fasterRewardAppEnterTimeMs = 0L  // 重置新 App 进入时间戳
            fasterRewardStage1WaitCount = 0  // 重置 stage=1 等待计数器
            fasterRewardStage1JumpPkg = ""   // build751: 重置 stage=1 直接跳转外来App记录
            fasterRewardStage1JumpStartMs = 0L  // build751: 重置 stage=1 直接跳转时间戳
            prevAdHadCountdown = false  // 重置倒计时状态，供多信号融合检测用
            // build529：进入广告时重置 AI 视觉进度识别节流（每个广告独立计数）
            lastAiProgressCheckMs = 0L
            watchingAdPlatform = service.currentPlatform  // 记录农场平台，强杀深链 App 后重新启动此平台
            interactiveAdDownloadClicked = false  // build599 v2: 重置互动广告下载按钮点击标记
            interactiveAdClickClaimClicked = false  // build748: 重置互动广告"点击立即获取"按钮点击标记
            interactiveAdClickClaimTimeMs = 0L  // build781: 重置点击时刻
            interactiveAdClaimRetried = false   // build781: 重置重试标记
            // build759: 重置互动广告"点击跳转拿奖励"跳转状态
            interactiveAdJumpPending = false
            // build761: 重置千问对话任务状态
            qianwenChatTyped = false
            qianwenChatSent = false
            // build671: 重置倒计时停滞检测状态
            adCountdownStallHandled = false
            // build675: 重置"点我加速"按钮点击标记
            adSpeedUpClicked = false
            // build716: 重置"我要加速"跳转状态机
            adSpeedUpJumpStage = 0
            adSpeedUpJumpPkg = null
            adSpeedUpJumpTimeMs = 0L
            // build696: 重置"去体验N秒可立即领奖"CTA 点击标记
            adExperienceClicked = false
            // build719: 重置"上滑或点击查看"互动提示点击标记
            adSwipeHintClicked = false
            // build678: 重置"点击商品,领取奖励"广告商品点击标记
            // (checkTaskListOpened 中也有此广告处理,但 watchAd 从 processTask 进入时未重置)
            adProductClicked = false
            adProductClickTimeMs = 0L
            // build702: 重置商品点击次数计数
            adProductClickCount = 0
            // build722: 重置 findAdProductNode 失败计数
            adProductNodeFindFailCount = 0
            // build768-2: 重置退出阶段 back 无效计数
            adProductExitBackCount = 0
            // build728: 重置汇川广告"点击商家后立即领奖"返回标记
            huichuanMerchantPending = false
            // build732: 重置充值陷阱 pressBack 无效计数
            trapRechargeBackCount = 0
            // build735: 重置安装陷阱 pressBack 无效计数
            trapInstallBackCount = 0
            // 按平台广告策略加载默认时长与检测间隔（UC/支付宝/淘宝差异化）
            val platformCfg = service.currentPlatformConfig()
            adEndCheckIntervalMs = platformCfg.adEndCheckIntervalMs
            val hintSeconds = service.findAdDurationHint()
            // build671: 记录初始倒计时值,用于后续检测倒计时是否停滞（静态文字伪装倒计时）
            adInitialCountdownSeconds = hintSeconds
            if (hintSeconds > 0) {
                // 页面提示的秒数 + 缓冲时间（毫秒）
                adMinDurationMs = hintSeconds * 1000L + AD_DURATION_BUFFER_MS
                // 最大等待时间随最短时间动态调整：最短+30秒余量，且不小于平台默认上限
                adMaxDurationMs = maxOf(platformCfg.adDefaultMaxDurationMs, adMinDurationMs + 30000L)
                debugLog("watchAd: parsed ad duration hint=${hintSeconds}s, min wait=${adMinDurationMs}ms, max wait=${adMaxDurationMs}ms (hint+buffer), checkInterval=${adEndCheckIntervalMs}ms")
            } else {
                // 无时长提示：使用平台默认值（UC=30s/90s, 支付宝=15s/60s, 淘宝=20s/75s）
                adMinDurationMs = platformCfg.adDefaultMinDurationMs
                adMaxDurationMs = platformCfg.adDefaultMaxDurationMs
                debugLog("watchAd: no duration hint, platform=${platformCfg.platform}, default min=${adMinDurationMs}ms, max=${adMaxDurationMs}ms, checkInterval=${adEndCheckIntervalMs}ms")
            }
        }

        // 每 15 秒输出一次页面快照（避免日志过多）
        if (elapsedMs % 15000L < adEndCheckIntervalMs) {
            logPageSnapshot(service, "watchAd-${elapsedMs}ms")
        }

        // build741 修复（debug_test_20260822_094757.log, build739, 09:43:06-09:45:23）:
        //   "看广告领奖"拉起腾讯 PortraitADActivity"抖音极速版"下载安装类广告
        //   ("完成App安装，即可获得奖励"),无视频无倒计时,只有安装App才发奖励,
        //   用户策略绝不安装 → 等待永远无奖励:
        //   - 第一次: scene=AD_ENDED 干等90s超时 → CLOSING_AD 无关闭按钮 → 盲点坐标
        //     误触下载拉起 packageinstaller(09:45:08 危险,可能误装App)
        //   - 第二次: 点"我要更快拿奖"直接进 packageinstaller(09:45:59)
        //   修复: 检测到下载安装类广告立即放弃——forceKill杀宿主+重开农场
        //   (该手段 07:41 已验证 100% 有效),全程不点广告页任何元素,不等90s不盲点坐标。
        //   仅在 fasterRewardStage==0 时检测(阶段2停留的第三方App页面不受影响)。
        if (fasterRewardStage == 0 && service.isDownloadInstallAd()) {
            Log.w(TAG, "watchAd: download-install ad detected (完成App安装才给奖励), abandoning immediately")
            debugLog("watchAd: 下载安装类广告(完成App安装才给奖励),等待无意义,立即forceKill杀宿主+重开农场放弃")
            recordTrapAdExit()  // build754: 陷阱退出计数（连续无进展则跳过视频类任务入口）
            // build744: 跳过当前任务（直接推进索引，不消耗 multi-click replay 次数——
            // 放弃不是成功，重进 OPENING_TASK_LIST 后轮到下一个任务，避免同一任务无限重试）
            currentTaskIndex++
            taskReplayRemaining = 0
            debugLog("watchAd: install-ad abandon skips task, next taskIndex=$currentTaskIndex")
            // build744: 连续放弃防护——期间无任何成功（collectedCount 与基线相同）连续
            // MAX_CONSECUTIVE_INSTALL_AD_ABANDON 次则停止（当天广告池全是安装类广告）
            if (collectedCount == installAdAbandonBaseCount) {
                installAdAbandonStreak++
            } else {
                installAdAbandonStreak = 1
                installAdAbandonBaseCount = collectedCount
            }
            if (installAdAbandonStreak >= MAX_CONSECUTIVE_INSTALL_AD_ABANDON) {
                Log.w(TAG, "watchAd: $installAdAbandonStreak consecutive install-ad abandons with no progress, stopping automation")
                debugLog("watchAd: 连续${installAdAbandonStreak}次安装类广告放弃且无进展,停止自动化(当天广告池异常)")
                installAdAbandonStreak = 0
                installAdAbandonBaseCount = -1
                stop()
                return
            }
            service.setAdMode(false)
            val farmPkgs = service.currentPlatformConfig().packageNames
            for (pkg in farmPkgs) {
                service.forceKillApp(pkg, pressBackFirst = false)
            }
            // build753: 杀宿主后屏幕可能熄灭进AOD,先唤醒再深链重开(同陷阱广告快速退出路径)
            service.ensureScreenOn()
            service.reopenFarmByDeepLink(killCurrentFirst = false)
            moveTo(AutomationState.NAVIGATING)
            handler.postDelayed({ runNavigating(attempt = 0) }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // "更快拿奖"流程仅在支持的平台执行（UC 特有，支付宝/淘宝跳过）
        val supportsFasterReward = service.currentPlatformConfig().supportsFasterReward
        if (supportsFasterReward) {
            // 优先检测：UC 芭芭农场广告页"更快拿奖"弹窗处理
            // 用户需求：点"我要更快拿奖" → 弹窗点"允许" → 新app打开停留16秒
            // → 关闭新打开的app → 回到"恭喜获得奖励提升"窗口 → 点右上角关闭 → 回芭芭农场
            // 状态机：0=待检测入口按钮 / 1=已点入口等待确认弹窗点允许 /
            //         2=已点允许新app打开停留16秒 / 3=已关闭新app等待奖励提升窗口点关闭 / 4=已完成
            when (fasterRewardStage) {
                0 -> {
                    // 阶段0：查找"我要更快拿奖"按钮
                    // build780 修复（debug_test_20260906_072308.log, 07:14:27-07:19:32 五轮循环）：
                    //   本机（荣耀）stage=1 手势切回 100% 失败（"recents not open"）→ 深链兜底
                    //   重开的是农场主页而非广告页 → 原广告被放弃、奖励不到账 → "看广告领奖"
                    //   仍在 → collectDirect 再点 → 无限循环（每轮~60s，UC 标签页 6→10 累积）。
                    //   修复：手势切回失败过一次后，本会话不再点"我要更快拿奖"入口，
                    //   让广告正常播完自然发奖（倒计时 29s 结束即可拿奖励）。
                    val entryBtn = if (fasterRewardRecentsFailed) {
                        null
                    } else {
                        service.findFasterRewardEntryButton()
                    }
                    if (entryBtn != null) {
                        Log.i(TAG, "watchAd: found '我要更快拿奖' button, clicking it")
                        debugLog("watchAd: clicking '我要更快拿奖' entry button")
                        service.performClickSafe(entryBtn)
                        fasterRewardStage = 1
                        fasterRewardStage1WaitCount = 0  // 重置等待计数器
                        // 等待确认弹窗出现（"15秒更快拿奖"）
                        handler.postDelayed({
                            if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                        }, INTERVAL_PAGE_LOAD_MS)
                        return
                    }
                }
                1 -> {
                    // 阶段1：已点入口按钮，等待"15秒更快拿奖"确认弹窗出现，然后点"允许"
                    // build724 修复（debug_test_20260816_152759.log, build723, 15:12:19-15:16:15）：
                    //   15:11:58 点击'我要更快拿奖' → stage=1 等待确认弹窗
                    //   15:12:19 isFasterRewardPopupShown=YES,但 findFasterRewardAllowButton 找不到
                    //     (skip '继续了解详情'),retrying
                    //   15:12:28 isTaskCompletePage=YES(任务已完成!) 但 stage=1 不检查,继续等
                    //   15:12:28.567 stage=1 timeout(25s),fasterRewardStage=4,回到正常广告等待
                    //   15:12:33 页面已变化,isTaskCompletePage=false,isRechargePage 误判=YES
                    //     → scene=TRAP_RECHARGE,点击"关闭"(实际点农场页元素),页面状态混乱
                    //   15:12:36-15:16:15 scene=AD_ENDED 干等 90s 超时才 CLOSING_AD
                    //   根因:stage=1 等待期间任务可能已完成(确认弹窗未出现但奖励已发放),
                    //     应立即退出,不应继续等确认弹窗或回到正常广告等待。
                    //   修复:stage=1 每次轮询先检查 isTaskCompletePage,已完成则直接退出。
                    // build751 守卫：直接跳转外来App停留期间（fasterRewardStage1JumpPkg非空）
                    //   跳过此检查——外来App页面（淘宝/支付宝）文本可能含"已完成"等误判词。
                    if (fasterRewardStage1JumpPkg.isEmpty() && service.isTaskCompletePage()) {
                        Log.i(TAG, "watchAd: task complete detected during faster reward stage=1 wait, exiting")
                        debugLog("watchAd: task complete during stage=1 (确认弹窗未出现但任务已完成), exiting via close/back icon")
                        val closeBtn = service.findAdCloseButton(service.currentPlatformConfig().adCloseButtonTexts)
                        val backIcon = service.findBackIcon()
                        when {
                            closeBtn != null -> { debugLog("watchAd: clicking close icon"); service.performClickSafe(closeBtn) }
                            backIcon != null -> { debugLog("watchAd: clicking back icon"); service.performClickSafe(backIcon) }
                            else -> { debugLog("watchAd: pressing back"); service.pressBack() }
                        }
                        service.setAdMode(false)
                        collectedCount++
                        advanceTaskIndex()
                        handler.postDelayed({
                            if (!service.isOnFarmPage()) service.pressBack()
                            handler.postDelayed({
                                if (state == AutomationState.WATCHING_AD) {
                                    moveTo(AutomationState.OPENING_TASK_LIST)
                                    handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                                }
                            }, INTERVAL_CLICK_MS)
                        }, INTERVAL_PAGE_LOAD_MS)
                        return
                    }
                    // build751 重写（原 build746 立即强杀方案，debug_test_20260829_154730.log, build749,
                    //   15:44:53 跳淘宝/15:45:21 跳支付宝 共2次暴露问题）：
                    //   旧逻辑检测到直接跳转立即 forceKillApp 杀外来App——但前台App杀不掉
                    //   (killBackgroundProcesses 限制,build697 已知),5s后 trap 分支深链回UC+杀App
                    //   (此时已退后台才杀成功)→ 广告被弃,每次浪费 ~15s 且无收益。
                    // 用户新需求："点击跳转后，过15秒回到跳转前的页面，操作应该是从底部手指
                    //   按住不放，往上拖动，然后把之前切走前的页面设置为前台页面"——
                    //   停留15秒后用上滑停顿手势(从底部按住不放往上拖动)打开最近任务，
                    //   点击UC卡片把跳转前的广告页切回前台（最近任务切回恢复原任务栈，
                    //   广告页原样保留可继续，比深链拉起新页面更可靠，也更像真人操作）。
                    // 注意：必须用 rootInActiveWindowSafe() 直接取活动窗口,不能用
                    //   getCurrentWindowPackage()——后者在 systemui 覆盖时会退回 windows
                    //   扫描,可能误报后台残留窗口(如微信)。
                    val stage1ActivePkg = service.rootInActiveWindowSafe()?.packageName?.toString().orEmpty()
                    if (stage1ActivePkg.isNotEmpty() && watchingAdPlatform != Platform.UNKNOWN &&
                        stage1ActivePkg !in watchingAdPlatform.config.packageNames &&
                        watchingAdPlatform.config.internalPackagePrefixes.none { stage1ActivePkg.startsWith(it) } &&
                        stage1ActivePkg != "com.bbncbot" &&
                        stage1ActivePkg != "android" &&
                        stage1ActivePkg != "com.android.systemui"
                    ) {
                        // 首次检测到直接跳转：记录包名和时刻，开始15秒停留
                        if (fasterRewardStage1JumpPkg.isEmpty()) {
                            fasterRewardStage1JumpPkg = stage1ActivePkg
                            fasterRewardStage1JumpStartMs = System.currentTimeMillis()
                            Log.w(TAG, "watchAd: faster reward entry jumped to foreign app '$stage1ActivePkg' directly (no confirm popup variant), staying ${FASTER_REWARD_STAGE1_JUMP_STAY_MS}ms then gesture switch back")
                            debugLog("watchAd: stage=1 点击'我要更快拿奖'直接跳转'$stage1ActivePkg'(无确认弹窗变体),停留${FASTER_REWARD_STAGE1_JUMP_STAY_MS / 1000}秒后手势切回UC")
                        }
                        val jumpStayedMs = System.currentTimeMillis() - fasterRewardStage1JumpStartMs
                        if (jumpStayedMs < FASTER_REWARD_STAGE1_JUMP_STAY_MS) {
                            // 未满15秒：继续等待
                            debugLog("watchAd: stage=1 在'$stage1ActivePkg'停留 ${jumpStayedMs}/${FASTER_REWARD_STAGE1_JUMP_STAY_MS}ms,等停留满后手势切回")
                            handler.postDelayed({
                                if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                            }, adEndCheckIntervalMs)
                            return
                        }
                        // 停留满15秒：从底部按住上滑停顿打开最近任务 → 点击UC卡片切回跳转前的页面
                        Log.i(TAG, "watchAd: stage=1 jump stay ${jumpStayedMs}ms done, gesture switching back to farm ad page")
                        debugLog("watchAd: stage=1 停留满${FASTER_REWARD_STAGE1_JUMP_STAY_MS / 1000}秒,从底部按住上滑(上滑停顿)打开最近任务,切回跳转前的UC广告页")
                        val jumpPkg = fasterRewardStage1JumpPkg
                        fasterRewardStage = 4  // 手势切回流程接管,放弃 faster reward 轮询
                        fasterRewardStage1JumpPkg = ""
                        fasterRewardStage1WaitCount = 0
                        service.swipeUpFromBottomToOpenRecents()
                        handler.postDelayed({
                            if (state != AutomationState.WATCHING_AD) return@postDelayed
                            // 最近任务已打开(第一张卡片即刚切走的UC),点击它切回前台
                            val clicked = service.findAndClickRecentTaskCard(listOf("UC极速版", "UC浏览器", "芭芭农场", "UC"))
                            debugLog("watchAd: stage=1 最近任务UC卡片点击结果=$clicked,等待验证前台...")
                            handler.postDelayed({
                                if (state != AutomationState.WATCHING_AD) return@postDelayed
                                val nowPkg = service.rootInActiveWindowSafe()?.packageName?.toString().orEmpty()
                                if (nowPkg in watchingAdPlatform.config.packageNames) {
                                    // 手势切回成功：UC广告页恢复前台,继续正常广告结束/奖励检测
                                    Log.i(TAG, "watchAd: stage=1 gesture switch back to '$nowPkg' success, resuming ad watch")
                                    debugLog("watchAd: stage=1 手势切回UC成功(跳转前广告页已恢复前台),继续广告等待")
                                    runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                                } else {
                                    // 手势切回失败(未开手势导航/最近任务无UC卡片)：深链兜底回UC+杀外来App
                                    Log.w(TAG, "watchAd: stage=1 gesture switch back failed (pkg=$nowPkg), deep link fallback")
                                    debugLog("watchAd: stage=1 手势切回失败(pkg=$nowPkg),深链兜底回UC+杀'$jumpPkg'")
                                    // build780: 深链兜底重开的是农场主页而非广告页，原广告已被放弃、奖励不到账。
                                    // 记录失败，本会话不再点"我要更快拿奖"入口（stage=0 跳过），
                                    // 让后续广告正常播完拿奖励，避免"看广告领奖"无限循环。
                                    if (!fasterRewardRecentsFailed) {
                                        fasterRewardRecentsFailed = true
                                        Log.w(TAG, "watchAd: stage=1 recents gesture failed once, disable faster-reward entry for this session (build780)")
                                    }
                                    service.reopenFarmByDeepLink(killCurrentFirst = false)
                                    if (jumpPkg.isNotEmpty()) {
                                        service.forceKillApp(jumpPkg, pressBackFirst = false)
                                    }
                                    handler.postDelayed({
                                        if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                                    }, INTERVAL_PAGE_LOAD_MS)
                                }
                            }, INTERVAL_PAGE_LOAD_MS)
                        }, 1200)
                        return
                    }
                    // build751: 跳转等待期间若已自然回到农场App(外来App自行退出),清除跳转记录继续正常广告流程
                    if (fasterRewardStage1JumpPkg.isNotEmpty() &&
                        stage1ActivePkg in watchingAdPlatform.config.packageNames
                    ) {
                        debugLog("watchAd: stage=1 已自然回到农场App(pkg=$stage1ActivePkg),清除跳转等待记录,继续广告等待")
                        fasterRewardStage1JumpPkg = ""
                        fasterRewardStage1JumpStartMs = 0L
                        fasterRewardStage = 4
                        handler.postDelayed({
                            if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                        }, adEndCheckIntervalMs)
                        return
                    }
                    if (service.isFasterRewardPopupShown()) {
                        val allowBtn = service.findFasterRewardAllowButton()
                        if (allowBtn != null) {
                            Log.i(TAG, "watchAd: faster reward confirm popup detected, clicking allow")
                            debugLog("watchAd: clicking allow on faster reward confirm popup")
                            service.performClickSafe(allowBtn)
                            fasterRewardStage = 2
                            // 记录点击"允许"时的时间戳，用于计算16秒停留
                            fasterRewardAppEnterTimeMs = System.currentTimeMillis()
                            // 等待新 App 打开，然后停留16秒
                            handler.postDelayed({
                                if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                            }, INTERVAL_PAGE_LOAD_MS)
                            return
                        } else {
                            // 弹窗出现但"允许"按钮未渲染，短暂等待后重试
                            // build724 修复（debug_test_20260816_152759.log, build723, 15:17:33-15:18:18）：
                            //   15:17:33 isFasterRewardPopupShown=YES,findFasterRewardAllowButton skip
                            //     '继续了解详情'(广告CTA),返回 null → "retrying" 分支
                            //   15:17:33-15:18:18 (45秒) 无限重试,无超时,直到 90s max 超时才 abort
                            //   根因:弹窗出现但无"允许"按钮时(只有"继续了解详情"广告CTA),
                            //     retry 分支没有 count++ 和超时检查,无限重试直到 WATCHING_AD 超时。
                            //   修复:复用 fasterRewardStage1WaitCount,超过 4 次重试(约8s)仍无允许按钮,
                            //     放弃 faster reward 流程(stage=4),回到正常广告等待。
                            fasterRewardStage1WaitCount++
                            if (fasterRewardStage1WaitCount > 4) {
                                Log.w(TAG, "watchAd: faster reward popup shown but no allow button after ${fasterRewardStage1WaitCount * 2}s, aborting faster reward flow")
                                debugLog("watchAd: stage=1 timeout (popup shown but no allow button after ${fasterRewardStage1WaitCount * 2}s), aborting faster reward (广告CTA无允许按钮,回到正常广告等待)")
                                fasterRewardStage = 4
                                fasterRewardStage1WaitCount = 0
                                handler.postDelayed({
                                    if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                                }, adEndCheckIntervalMs)
                                return
                            }
                            debugLog("watchAd: faster reward popup shown but allow button not found, retrying (wait=$fasterRewardStage1WaitCount)")
                            handler.postDelayed({
                                if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                            }, INTERVAL_CLICK_MS)
                            return
                        }
                    } else {
                        // 确认弹窗还没出现，继续等待（可能页面切换中）
                        // build686 修复（debug_test_20260802_095406.log, build685, 09:53:04-09:53:56）：
                        //   09:53:04 点击'我要更快拿奖' → stage=1（按钮应被点击,用户确认）
                        //   09:53:19 pkg=com.hihonor.appmarket ← 广告内容切换到华为应用市场
                        //   09:53:04-09:53:52 一直 waiting for confirm popup (stage=1)
                        //   09:53:56 WATCHING_AD -> STOPPING ← 卡死 47 秒后超时
                        //   根因：点击"我要更快拿奖"后,部分广告未弹出确认弹窗(广告内容已切换),
                        //         stage=1 没有超时机制,一直等 confirm popup 直到 WATCHING_AD 超时。
                        //   修复：stage=1 等待超过 4 次重试(约20秒)仍未出现 confirm popup,
                        //         放弃 faster reward 流程(stage=4),回到正常广告等待。
                        //         （注意：不阻止点击"我要更快拿奖",仅作为确认弹窗未出现的兜底）
                        fasterRewardStage1WaitCount++
                        if (fasterRewardStage1WaitCount > 4) {
                            Log.w(TAG, "watchAd: faster reward confirm popup not shown after ${fasterRewardStage1WaitCount * 5}s, aborting faster reward flow")
                            debugLog("watchAd: stage=1 timeout (${fasterRewardStage1WaitCount * 5}s), aborting faster reward (确认弹窗未出现,回到正常广告等待)")
                            fasterRewardStage = 4  // 放弃 faster reward,回到正常广告等待
                            fasterRewardStage1WaitCount = 0
                            handler.postDelayed({
                                if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                            }, adEndCheckIntervalMs)
                            return
                        }
                        debugLog("watchAd: waiting for faster reward confirm popup (stage=1, wait=$fasterRewardStage1WaitCount)")
                        handler.postDelayed({
                            if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                        }, adEndCheckIntervalMs)
                        return
                    }
                }
                2 -> {
                    // 阶段2：已点"允许"，新 App 已打开，停留16秒
                    val currentPkg = service.getCurrentWindowPackage()
                    // 首次进入此阶段时记录新 App 包名
                    if (fasterRewardAppPkg == null && currentPkg != null) {
                        // 排除农场 App 自身（防止误判）
                        val farmPkgs = watchingAdPlatform.config.packageNames
                        if (!farmPkgs.contains(currentPkg)) {
                            fasterRewardAppPkg = currentPkg
                            Log.i(TAG, "watchAd: faster reward new app '$currentPkg' opened, staying ${FASTER_REWARD_APP_STAY_MS}ms")
                            debugLog("watchAd: faster reward app '$currentPkg' opened, staying 16s")
                        }
                    }
                    // 陷阱防护：停留期间检测诱导弹窗（立即下载等）→ 优先关闭
                    if (service.findAdInstallButton() != null) {
                        Log.w(TAG, "watchAd: faster reward stay interrupted by install popup, closing it")
                        debugLog("watchAd: install popup during faster reward stay, attempting close")
                        service.closeAdInstallPopup()
                        handler.postDelayed({
                            if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                        }, INTERVAL_CLICK_MS)
                        return
                    }
                    // 陷阱防护：停留期间检测交易页（违反禁止交易原则）→ 立即结束流程
                    if (service.isOnAbnormalPage() || service.isRechargePage()) {
                        Log.w(TAG, "watchAd: faster reward stay hit abnormal/recharge page, aborting")
                        debugLog("watchAd: faster reward stay hit trap page, killing new app immediately")
                        service.setAdMode(false)
                        if (watchingAdPlatform != Platform.UNKNOWN) {
                            service.launchPlatformApp(watchingAdPlatform)
                        }
                        val killedPkg = fasterRewardAppPkg
                        if (killedPkg != null) {
                            service.forceKillApp(killedPkg, pressBackFirst = false)
                        } else {
                            service.pressBack()
                        }
                        currentTaskIndex++
                        handler.postDelayed({
                            if (state == AutomationState.WATCHING_AD) {
                                moveTo(AutomationState.OPENING_TASK_LIST)
                                handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                            }
                        }, INTERVAL_PAGE_LOAD_MS)
                        return
                    }
                    // build677 修复（debug_test_20260801_111614.log, build675, 11:16:00）：
                    // 阶段2 停留期间,新 App 页面已显示"已完成浏览10秒，提前获得奖励"(taskComplete=true),
                    // 说明奖励已发放,不需要继续等满 16 秒。原逻辑继续等到 16000ms,用户手动停止。
                    // 修复：检测到 taskComplete 时,提前进入阶段3（关闭新 App,等"恭喜获得奖励提升"弹窗点关闭）。
                    if (service.isTaskCompletePage()) {
                        Log.i(TAG, "watchAd: faster reward task complete detected during stay (提前获得奖励), advancing to stage3 early")
                        debugLog("watchAd: task complete detected (已完成浏览10秒), skip waiting, go stage3 to close popup")
                        service.setAdMode(false)
                        // 1. 激活农场 App 到前台
                        if (watchingAdPlatform != Platform.UNKNOWN) {
                            service.launchPlatformApp(watchingAdPlatform)
                        }
                        // 2. kill 掉新打开的 App
                        val killedPkg = fasterRewardAppPkg
                        if (killedPkg != null) {
                            service.forceKillApp(killedPkg, pressBackFirst = false)
                        } else {
                            service.pressBack()
                        }
                        fasterRewardStage = 3
                        handler.postDelayed({
                            if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                        }, INTERVAL_PAGE_LOAD_MS)
                        return
                    }
                    // 计算停留时间
                    val stayedMs = if (fasterRewardAppEnterTimeMs > 0) {
                        System.currentTimeMillis() - fasterRewardAppEnterTimeMs
                    } else 0L
                    if (stayedMs >= FASTER_REWARD_APP_STAY_MS) {
                        // 停留满16秒，关闭新打开的 App 并激活农场 App 到前台
                        Log.i(TAG, "watchAd: faster reward stayed ${stayedMs}ms, killing new app and activating farm")
                        debugLog("watchAd: 16s elapsed, killing new app '${fasterRewardAppPkg}' + activating farm")
                        service.setAdMode(false)
                        // 1. 激活农场 App 到前台
                        if (watchingAdPlatform != Platform.UNKNOWN) {
                            debugLog("watchAd: launching farm platform $watchingAdPlatform to foreground")
                            service.launchPlatformApp(watchingAdPlatform)
                        }
                        // 2. 同时 kill 掉新打开的 App
                        val killedPkg = fasterRewardAppPkg
                        if (killedPkg != null) {
                            service.forceKillApp(killedPkg, pressBackFirst = false)
                        } else {
                            // 没有记录到包名，按返回键尝试关闭
                            debugLog("watchAd: no pkg recorded, pressing back to close new app")
                            service.pressBack()
                        }
                        fasterRewardStage = 3
                        // 等待回到"恭喜获得奖励提升"窗口
                        handler.postDelayed({
                            if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                        }, INTERVAL_PAGE_LOAD_MS)
                        return
                    } else {
                        // 还未满16秒，继续等待
                        debugLog("watchAd: faster reward staying in new app, ${stayedMs}/${FASTER_REWARD_APP_STAY_MS}ms elapsed")
                        handler.postDelayed({
                            if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                        }, adEndCheckIntervalMs)
                        return
                    }
                }
                3 -> {
                    // 阶段3：已关闭新 App，等待"恭喜获得奖励提升"窗口，点右上角关闭
                    // 陷阱防护：阶段3 等待期间也可能误入落地页/诱导弹窗
                    if (service.isAdLandingPage()) {
                        Log.w(TAG, "watchAd: faster reward stage3 hit landing page, closing via closeAdLandingPage")
                        debugLog("watchAd: stage3 landing page trap, using closeAdLandingPage")
                        service.closeAdLandingPage()
                        handler.postDelayed({
                            if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                        }, INTERVAL_CLICK_MS)
                        return
                    }
                    if (service.isRewardUpgradePopupShown()) {
                        Log.i(TAG, "watchAd: reward upgrade popup detected, clicking close")
                        debugLog("watchAd: '恭喜获得奖励提升' popup detected, clicking top-right close")
                        // 点右上角关闭按钮
                        val closeBtn = service.findAdCloseButton()
                        val backIcon = service.findBackIcon()
                        when {
                            closeBtn != null -> { debugLog("watchAd: clicking close icon"); service.performClickSafe(closeBtn) }
                            backIcon != null -> { debugLog("watchAd: clicking back icon"); service.performClickSafe(backIcon) }
                            else -> { debugLog("watchAd: pressing back to close popup"); service.pressBack() }
                        }
                        fasterRewardStage = 4
                        collectedCount++
                        advanceTaskIndex()
                        // 等待返回到芭芭农场页面
                        handler.postDelayed({
                            if (state == AutomationState.WATCHING_AD) {
                                if (!service.isOnFarmPage()) {
                                    debugLog("watchAd: not on farm after close, pressing back")
                                    service.pressBack()
                                }
                                handler.postDelayed({
                                    if (state == AutomationState.WATCHING_AD) {
                                        moveTo(AutomationState.OPENING_TASK_LIST)
                                        handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                                    }
                                }, INTERVAL_CLICK_MS)
                            }
                        }, INTERVAL_PAGE_LOAD_MS)
                        return
                    } else {
                        // 还未出现"恭喜获得奖励提升"窗口，继续等待
                        debugLog("watchAd: waiting for '恭喜获得奖励提升' popup (stage=3)")
                        handler.postDelayed({
                            if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                        }, adEndCheckIntervalMs)
                        return
                    }
                }
                4 -> {
                    // 阶段4：已完成，不重复处理（避免重复点击）
                }
            }
        }

        // build716: "我要加速"跳转停留阶段处理
        // 用户需求："点击我要加速后,需要等待指定的时间后回到芭芭农场广告点击时的页面领取,
        //           不是关闭芭芭农场重新打开,只有在原来点击我要加速时的页面才能领取奖励"
        // - 点击"我要加速"会跳转到淘宝/闲鱼等App(穿山甲TTRewardVideoActivity的CTA)
        // - 需要在跳转的App里停留10秒,然后pressBack回到广告页面继续领奖
        // - 不能关闭芭芭农场重新打开(会丢失广告会话,无法领奖励)
        if (adSpeedUpJumpStage == 1) {
            val currentPkg = service.getCurrentWindowPackage()
            // 首次进入此阶段时记录跳转目标App包名
            if (adSpeedUpJumpPkg == null && currentPkg != null &&
                currentPkg !in watchingAdPlatform.config.packageNames &&
                !currentPkg.contains("launcher", ignoreCase = true) &&
                !currentPkg.contains("aod", ignoreCase = true) &&
                currentPkg != "android" && currentPkg != "com.android.systemui" &&
                currentPkg != "com.bbncbot") {
                adSpeedUpJumpPkg = currentPkg
                adSpeedUpJumpTimeMs = System.currentTimeMillis()
                Log.i(TAG, "watchAd: '我要加速' jumped to '$currentPkg', staying ${SPEED_UP_JUMP_STAY_MS}ms then pressBack to ad page")
                debugLog("watchAd: '我要加速' jumped to app '$currentPkg', staying 10s")
            }
            // 停留期间检测陷阱页(充值/交易/异常页),若命中则立即放弃跳转
            if (service.isRechargePage() || service.isOnAbnormalPage()) {
                Log.w(TAG, "watchAd: '我要加速' stay hit trap page, aborting jump and exiting task")
                debugLog("watchAd: speedUp jump stay hit trap page, killing jumped app immediately")
                service.setAdMode(false)
                val jumpedPkg = adSpeedUpJumpPkg
                if (jumpedPkg != null) {
                    service.forceKillApp(jumpedPkg, pressBackFirst = false)
                } else {
                    service.pressBack()
                }
                adSpeedUpJumpStage = 2
                currentTaskIndex++
                handler.postDelayed({
                    if (state == AutomationState.WATCHING_AD) {
                        moveTo(AutomationState.OPENING_TASK_LIST)
                        handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                    }
                }, INTERVAL_PAGE_LOAD_MS)
                return
            }
            // 计算停留时间
            val stayedMs = if (adSpeedUpJumpTimeMs > 0) {
                System.currentTimeMillis() - adSpeedUpJumpTimeMs
            } else 0L
            if (stayedMs >= SPEED_UP_JUMP_STAY_MS) {
                // build716: 停留满10秒,把芭芭农场App切到前台(不重启、不pressBack,保留广告会话)
                // 用户需求:"芭芭农场app只是切换到后台,不是关闭,等待多少秒后,
                //           我们需要把芭芭农场切到前台,是切到前台,不是重新打开"
                // 用 moveTaskToFront 把后台的芭芭农场任务切到前台,广告会话保留,可继续领奖
                Log.i(TAG, "watchAd: '我要加速' stayed ${stayedMs}ms, bringing farm app to front (not relaunching)")
                debugLog("watchAd: 10s elapsed, bringFarmAppToFront (not relaunch, keep ad session)")
                service.bringFarmAppToFront(watchingAdPlatform)
                adSpeedUpJumpStage = 2
                handler.postDelayed({
                    if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                }, INTERVAL_PAGE_LOAD_MS)
                return
            } else {
                // 还未满10秒,继续等待
                debugLog("watchAd: '我要加速' staying in jumped app, ${stayedMs}/${SPEED_UP_JUMP_STAY_MS}ms elapsed")
                handler.postDelayed({
                    if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                }, adEndCheckIntervalMs)
                return
            }
        }

        // build714 修复（debug_test_20260808_165631.log, build712, 10:12:11-10:29:03）:
        //   10:12:11 点击"我要加速" → 10:12:18 pkg=com.aliyun.tongyi（千问App）
        //   "我要加速"是穿山甲广告的陷阱按钮,点击后跳转/打开千问App
        //   watchAd 没有检测到跳转,一直在千问App里等待(isAdActivity()=true 因缓存了旧 Activity 名)
        //   10:12:59 灭屏卡5分钟 → 10:27:40 误判广告结束 → closeAd 在千问页点击无效
        //   10:28:00-25 pressBack 3次无效 → 10:29:00 forceKillApp 误杀UC(应kill千问)
        //   根因:isAdActivity() 基于 currentActivityName(缓存值),跳转后仍返回 true,
        //         isNonAdPage() 因此返回 false,没有检测到跳转到非农场App
        //   修复:直接检测 getCurrentWindowPackage() 是否是农场App包名,
        //         如果不是(且不是系统UI),说明被广告按钮带偏,退出当前App并重新激活农场App
        //         (fasterRewardStage=2 停留阶段已在 when 块内 return,不会走到这里)
        //         (adSpeedUpJumpStage=1 停留阶段已在上方处理并 return,不会走到这里)
        //   build716 调整:此检测现在只处理"非我要加速跳转"的其他意外跳转(如千问误点CTA)
        //   build743 修复:深链任务跳转也放行——watchingAdFromDeepLinkTask=true 时跳过此
        //   分支,交给下方深链任务分支(等够任务时长后保留现场切回农场)处理。此前此分支
        //   在深链分支之前执行且不区分两者,深链任务刚跳转就被当陷阱杀掉+跳过任务,
        //   深链分支自 build737 引入以来从未执行过(死代码)。
        //   build762 修复（debug_test_20260830_060258.log, build759, 06:01:15-06:01:22）:
        //   快手互动广告"点击跳转拿奖励"按钮点击后跳转 com.antgroup.leopard.android
        //   (广告落地App),build759 的 interactiveAdJumpPending 守卫只覆盖了场景降级和
        //   深链分支条件,漏了本分支——跳转 7s 后被本分支当"广告按钮陷阱"forceKill+
        //   深链重开农场,奖励流程被打断(看广告领奖按钮仍在,奖励未到账,循环重看广告,
        //   两轮均如此,第一轮还引发 47s NAVIGATING 恢复)。修复:interactiveAdJumpPending
        //   =true 时放行,交给深链分支(停留 20s→保留现场切回→广告关闭回调发奖)。
        val currentPkg = service.getCurrentWindowPackage()
        if (watchingAdPlatform != Platform.UNKNOWN && currentPkg != null &&
            adSpeedUpJumpStage != 1 &&  // 放行"我要加速"跳转停留阶段
            !watchingAdFromDeepLinkTask &&  // build743: 放行深链任务跳转,交给深链分支处理
            !interactiveAdJumpPending &&  // build762: 放行互动广告"点击跳转拿奖励"跳转,交给深链分支处理
            currentPkg !in watchingAdPlatform.config.packageNames &&
            !currentPkg.contains("launcher", ignoreCase = true) &&
            !currentPkg.contains("aod", ignoreCase = true) &&
            currentPkg != "android" && currentPkg != "com.android.systemui" &&
            currentPkg != "com.bbncbot") {
            Log.w(TAG, "watchAd: left farm app to '$currentPkg' (ad button trap), exiting it and relaunching farm (elapsed=${elapsedMs}ms)")
            debugLog("watchAd: current pkg='$currentPkg' is not farm app, exiting (ad button trap) and relaunching farm")
            service.setAdMode(false)
            // 1. 拉起农场App到前台(覆盖千问等非广告App),不杀农场App进程
            service.launchPlatformApp(watchingAdPlatform, killCurrentFirst = false)
            // 2. kill 非广告App(此时已在后台,killBackgroundProcesses 有效),
            //    同时清除 currentActivityName/currentEventPkg 缓存避免 isAdActivity 误判
            // build725 修复（debug_test_20260816_160006.log, build724, 15:44:44-15:53:29）：
            //   15:44:44 pkg=com.android.packageinstaller (腾讯广告PortraitADActivity寄宿在系统安装器task中)
            //   15:44:45 forceKillApp(packageinstaller) 后,UC deep link 拉起,但系统状态异常,
            //   handler.postDelayed 回调延迟 8分44秒才执行,卡死直到 15:53:29。
            //   根因:killBackgroundProcesses 对系统应用(packageinstaller)无效或导致系统状态异常,
            //   广告Activity寄宿在系统应用task中,kill系统应用风险高。
            //   修复:对 com.android.* 系统应用包名,不 kill,只 pressBack 退出广告Activity。
            val isSystemPkg = currentPkg.startsWith("com.android.") || currentPkg.startsWith("android")
            if (isSystemPkg) {
                debugLog("watchAd: system pkg '$currentPkg' detected, pressBack instead of kill (avoid system instability)")
                service.pressBack()
            } else {
                service.forceKillApp(currentPkg, pressBackFirst = false)
            }
            // build780 修复（debug_test_20260906_072308.log, 07:15:06/07:16:08/07:17:18...）：
            //   "我要更快拿奖"跳荣耀应用市场→深链兜底放弃广告→本分支退出，但漏调
            //   recordTrapAdExit → trapAdExitStreak 永远为 0 → shouldSkipVideoAdEntries
            //   永不激活 → collectDirect 反复点"看广告领奖"，无限循环（13分钟零奖励）。
            //   修复：广告按钮陷阱退出也计入陷阱退出计数，连续无进展 2 次后跳过视频类入口。
            recordTrapAdExit()
            currentTaskIndex++
            handler.postDelayed({
                if (state == AutomationState.WATCHING_AD) {
                    moveTo(AutomationState.OPENING_TASK_LIST)
                    handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                }
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 误入非广告页面兜底检测
        // 场景：任务跳转到小程序/游戏页面（非广告），被误判为 deep-link ad task 进入 WATCHING_AD
        // 此时 adModeFlag=true 导致 isAdPlaying()=true，但页面实际没有广告特征
        // 检测：不是广告 Activity + 没有广告内容 + 有小程序/游戏特征 → 退出
        if (!service.isAdActivity() && !service.isAdContentShown() &&
            service.isMiniProgramOrGamePage()) {
            Log.w(TAG, "watchAd: mini-program/game page detected (non-ad), exiting task")
            debugLog("watchAd: non-ad page in WATCHING_AD (mini-program/game), clearing adMode and exiting")
            service.setAdMode(false)
            service.pressBack()
            currentTaskIndex++
            handler.postDelayed({
                if (!service.isOnFarmPage()) service.pressBack()
                handler.postDelayed({
                    if (state == AutomationState.WATCHING_AD) {
                        moveTo(AutomationState.OPENING_TASK_LIST)
                        handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                    }
                }, INTERVAL_PAGE_LOAD_MS)
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 场景识别驱动：陷阱防护统一调度（聪明思考，识别各种场景）
        //
        // 用户反馈："学会聪明思考，需要识别各种场景"
        // 重构思路：从"关键词黑名单驱动 + 被动检测"升级为"场景识别驱动 + 白名单优先"。
        // 一次性调用 identifyCurrentScene() 识别当前页面场景（多信号指纹融合），
        // 基于场景类型统一决策动作，替代零散的 if-else 检测。
        //
        // 优势：
        // 1. 场景优先级（充值→交易→小程序→落地页→诱导弹窗→复看）确保最危险的陷阱先处理
        //    （充值陷阱造成金钱损失，优先级最高；落地页只是浪费时间，优先级较低）
        // 2. 一次识别 + 短路返回，避免 5 次 isXxx() 重复遍历节点树
        // 3. 调用方基于场景类型决策，意图清晰，新增陷阱场景只需扩展 PageScene 枚举
        // 4. 与 findClaimRewardButton/findAdCloseButton 的场景白名单形成闭环防护
        val scene = service.identifyCurrentScene()
        debugLog("watchAd: scene=$scene, elapsed=${elapsedMs}ms/${adMaxDurationMs}ms")

        // build768 修复（debug_test_20260830_203822.log, build768-2248194, 19:52:06-20:37:43 卡死46分钟）:
        //   快手广告(KsRewardVideoActivity, 25s倒计时)播完后系统落在桌面(荣耀 MagicOS, root=
        //   com.hihonor.android.launcher 显示"第3屏/天气"小组件), 但 currentActivityName 残留
        //   广告Activity名 → isAdActivity()=true → scene=AD_PLAYING 干等; findAdDurationHint
        //   把桌面文本"第 3 屏"误解析为倒计时3秒(冻结不动, 像广告卡在最后3秒)。
        //   桌面上无视频持有亮屏 → 锁屏深睡, handler 计时器(uptime)冻结45分钟, 期间 elapsed
        //   只走了5s。用户20:37唤醒手机后靠 build681 的 no_root 防卡死守卫才退出。
        //   修复: 活动窗口是桌面时广告必然已结束, 立即进 RETURNING(手势切回/深链兜底),
        //   不进 CLOSING_AD——closeAd 在桌面上找不到关闭按钮会盲点8个坐标, 可能误开App。
        if (service.isLauncherRoot()) {
            Log.w(TAG, "watchAd: stranded on launcher (ad gone, stale adActivity name), entering RETURNING")
            debugLog("watchAd: 活动窗口是桌面(launcher),广告已结束(Activity名残留),直接RETURNING手势切回农场(防46分钟卡死)")
            service.setAdMode(false)
            moveTo(AutomationState.RETURNING)
            handler.postDelayed({
                if (state == AutomationState.RETURNING) runReturning(0)
            }, INTERVAL_CLICK_MS)
            return
        }

        // build730 修复（debug_test_20260816_184008.log, build728, 18:39:35-18:40:05）：
        //   汇川广告"返回点击商家"回广告后,广告 WebView 显示商家商品详情页
        //   (isProductDetailPage=YES: 加购/立即购买按钮),isRechargePage 匹配"立即购买"
        //   → scene=TRAP_RECHARGE,clickCloseOnRechargePage 无关闭按钮 → pressBack 循环 25s+,
        //   页面无变化(pressBack 被 WebView 拦截),直到用户手动停止,奖励未领。
        //   根因: huichuanMerchantPending=true 时商家商品页是预期状态(有意返回等待奖励计时结束),
        //   不应触发陷阱防御(pressBack 可能打断奖励计时,且永远退不出去)。
        //   修复: huichuanMerchantPending=true 且仍处于广告Activity时,陷阱类场景
        //   (TRAP_RECHARGE/TRAP_ABNORMAL/TRAP_LANDING/TRAP_MINIPROGRAM)跳过防御,
        //   在商家页耐心等待"奖励已发放"(build729: 检测到后点击右上角关闭图标领奖)。
        //   超时(adMaxDurationMs)兜底进 CLOSING_AD 多策略关闭。
        // build735 扩展（debug_test_20260817_191553.log, build733）:
        //   1. TRAP_INSTALL 加入跳过列表: 汇川"点击跳转后停留"变体点击转化按钮后落地页
        //      含"查看详情"等文案→误判 TRAP_INSTALL→pressBack 循环,打断落地页停留计时。
        //   2. 覆盖落地页无提示文案场景(isClickProductAd=false 且 scene=AD_PLAYING/UNKNOWN/AD_ENDED):
        //      点击跳转后落地页不再含"点击跳转后停留"提示,原守卫不命中,点击商品块也不命中,
        //      奖励等待丢失。落地页在广告Activity内无倒计时无CTA时 classify 为 AD_ENDED,
        //      必须一并覆盖,否则误走 AD_ENDED 关闭流程放弃奖励。统一在本守卫等"奖励已发放"。
        val trapLikeScene = scene == PageScene.TRAP_RECHARGE || scene == PageScene.TRAP_ABNORMAL ||
            scene == PageScene.TRAP_LANDING || scene == PageScene.TRAP_MINIPROGRAM ||
            scene == PageScene.TRAP_INSTALL
        if (huichuanMerchantPending && service.isAdActivity() && (trapLikeScene ||
            (!service.isClickProductAd() && (scene == PageScene.AD_PLAYING || scene == PageScene.UNKNOWN ||
             scene == PageScene.AD_ENDED)))) {
            if (detectRewardGrantedText(service)) {
                claimRewardViaCloseIcon(service, elapsedMs)
                return
            }
            if (elapsedMs >= adMaxDurationMs) {
                Log.w(TAG, "watchAd: huichuanMerchantPending timeout (${elapsedMs}ms/${adMaxDurationMs}ms), force closing")
                debugLog("watchAd: 商家页等待'奖励已发放'超时(${elapsedMs}ms), 进入CLOSING_AD兜底关闭")
                moveTo(AutomationState.CLOSING_AD)
                handler.postDelayed({ runClosingAd(strategy = 0) }, INTERVAL_CLICK_MS)
                return
            }
            debugLog("watchAd: huichuanMerchantPending=true, scene=$scene is merchant page inside ad (expected), skip trap defense, waiting for 奖励已发放 (elapsed=${elapsedMs}ms/${adMaxDurationMs}ms)")
            handler.postDelayed({
                if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
            }, adEndCheckIntervalMs)
            return
        }

        // build759 修复（debug_test_20260829_230753.log, build757, 23:05:27-23:05:45）：
        //   快手扭一扭广告页含"看10秒可直接拿奖励"+"点击跳转拿奖励"按钮。旧关键词
        //   只认"立即获取/立即领奖"→按钮漏识别→被当无奖励陷阱forceKill,奖励丢失。
        //   修复链路: findInteractiveAdClickToClaimButton 关键词已扩展→点击该按钮→
        //   跳转淘宝闪购落地页。落地页含"立即购买/查看详情"等文案会被识别为陷阱场景
        //   （TRAP_ABNORMAL/TRAP_LANDING等）→陷阱防御pressBack退出→奖励又丢。
        //   守卫: interactiveAdJumpPending=true 时陷阱场景降级为UNKNOWN（预期跳转
        //   落地页,非陷阱）,让下方深链分支处理停留计时+保留现场切回农场+任务推进;
        //   同时每轮检测"奖励已发放"（出现在农场App WebView时点击关闭图标拿奖励）。
        if (interactiveAdJumpPending && detectRewardGrantedText(service)) {
            claimRewardViaCloseIcon(service, elapsedMs)
            return
        }
        val trapLikeSceneForJump = scene == PageScene.TRAP_RECHARGE || scene == PageScene.TRAP_ABNORMAL ||
            scene == PageScene.TRAP_LANDING || scene == PageScene.TRAP_MINIPROGRAM ||
            scene == PageScene.TRAP_INSTALL
        val effectiveScene = if (interactiveAdJumpPending && trapLikeSceneForJump) PageScene.UNKNOWN else scene
        if (effectiveScene != scene) {
            debugLog("watchAd: interactive ad jump pending, scene=$scene downgraded to UNKNOWN (expected landing page, skip trap defense)")
        }

        when (effectiveScene) {
            // 陷阱1：充值/付费页（最高优先级，违反禁止交易原则，可能造成金钱损失）
            // 策略：优先点关闭按钮，否则按返回退出，继续轮询（不退出任务，可能是广告内弹窗）
            // build732 修复（debug_test_20260816_190740.log, build730, 19:05:51-19:07:15）：
            //   快手广告(KsRewardVideoActivity)"扭一扭或点击跳转详情页"互动页
            //   isRechargePage 匹配页面转化按钮文案→误判 TRAP_RECHARGE,
            //   clickCloseOnRechargePage 找不到关闭按钮→pressBack 每5s循环,
            //   广告 Activity 拦截 back 键退不出去,elapsed=190s 超时也不停(分支无超时保护),
            //   直到用户手动停止。
            //   修复: pressBack 连续 4 次(约20s)无效或 elapsed 超时时,
            //   forceKillApp 杀宿主 App + reopenFarmByDeepLink 重开农场(最可靠的退出手段)。
            PageScene.TRAP_RECHARGE -> {
                if (trapRechargeBackCount >= 4 || elapsedMs >= adMaxDurationMs) {
                    Log.w(TAG, "watchAd: recharge trap back x$trapRechargeBackCount ineffective (elapsed=${elapsedMs}ms), force killing host app and relaunching farm")
                    debugLog("watchAd: 充值陷阱pressBack ${trapRechargeBackCount}次无效(elapsed=${elapsedMs}ms), forceKillApp杀宿主+重开农场兜底")
                    recordTrapAdExit()  // build754: 陷阱退出计数（连续无进展则跳过视频类任务）
                    service.setAdMode(false)
                    val farmPkgs = service.currentPlatformConfig().packageNames
                    for (pkg in farmPkgs) {
                        service.forceKillApp(pkg, pressBackFirst = false)
                    }
                    service.reopenFarmByDeepLink(killCurrentFirst = false)
                    moveTo(AutomationState.NAVIGATING)
                    handler.postDelayed({ runNavigating(attempt = 0) }, INTERVAL_PAGE_LOAD_MS)
                    return
                }
                Log.w(TAG, "watchAd: recharge page detected (scene=$scene), exiting immediately (trap defense)")
                debugLog("watchAd: recharge page trap detected, clicking close on recharge page")
                val closed = service.clickCloseOnRechargePage()
                if (!closed) {
                    debugLog("watchAd: no close on recharge, pressing back")
                    service.pressBack()
                    trapRechargeBackCount++
                }
                handler.postDelayed({
                    if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                }, INTERVAL_CLICK_MS)
                return
            }
            // 陷阱2：交易/下单页（违反禁止交易原则）
            // 策略：保留 5s 等待避免深链跳转期间页面切换的误判，确认后退出任务
            // 注意：用 currentTaskIndex++（而非 advanceTaskIndex）— 陷阱任务直接跳过，不重玩
            PageScene.TRAP_ABNORMAL -> {
                if (elapsedMs < 5000L) {
                    // 未满 5s，可能是页面切换中的瞬时状态，继续轮询等待页面稳定
                    debugLog("watchAd: TRAP_ABNORMAL scene but elapsed=${elapsedMs}ms < 5000ms, waiting for page to stabilize")
                    handler.postDelayed({
                        if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                    }, adEndCheckIntervalMs)
                    return
                }
                Log.w(TAG, "watchAd: abnormal/trading page detected (scene=$scene, elapsed=${elapsedMs}ms), exiting immediately")
                debugLog("watchAd: abnormal page trap detected, pressing back to exit")
                service.setAdMode(false)
                service.pressBack()
                currentTaskIndex++  // 陷阱任务直接跳过，不重玩（避免再次掉入同一陷阱）
                handler.postDelayed({
                    if (state == AutomationState.WATCHING_AD) {
                        if (!service.isOnFarmPage()) service.pressBack()
                        handler.postDelayed({
                            if (state == AutomationState.WATCHING_AD) {
                                moveTo(AutomationState.OPENING_TASK_LIST)
                                handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                            }
                        }, INTERVAL_PAGE_LOAD_MS)
                    }
                }, INTERVAL_PAGE_LOAD_MS)
                return
            }
            // 陷阱3：非农场小程序（支付宝/淘宝特有，广告诱导跳转）
            // 策略：立即按返回退出，跳过任务（UC 无小程序，此场景不会触发）
            // 注意：用 currentTaskIndex++（而非 advanceTaskIndex）— 陷阱任务直接跳过，不重玩
            PageScene.TRAP_MINIPROGRAM -> {
                Log.w(TAG, "watchAd: mini-program trap detected (scene=$scene), exiting immediately")
                debugLog("watchAd: mini-program trap (non-farm mini-program), pressing back to exit")
                service.setAdMode(false)
                service.pressBack()
                currentTaskIndex++  // 陷阱任务直接跳过，不重玩（避免再次掉入同一陷阱）
                handler.postDelayed({
                    if (state == AutomationState.WATCHING_AD) {
                        if (!service.isOnFarmPage()) service.pressBack()
                        handler.postDelayed({
                            if (state == AutomationState.WATCHING_AD) {
                                moveTo(AutomationState.OPENING_TASK_LIST)
                                handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                            }
                        }, INTERVAL_PAGE_LOAD_MS)
                    }
                }, INTERVAL_PAGE_LOAD_MS)
                return
            }
            // 陷阱4：广告主落地页（含多个"立即下载/查看详情/去购买"等转化按钮）
            // 策略：误入即按返回退出，绝不点击任何按钮（避免转化收益），跳过任务
            // 注意：用 currentTaskIndex++（而非 advanceTaskIndex）— 陷阱任务直接跳过，不重玩
            PageScene.TRAP_LANDING -> {
                Log.w(TAG, "watchAd: ad landing page detected (scene=$scene), exiting immediately (trap defense)")
                debugLog("watchAd: ad landing page trap detected, pressing back to exit")
                service.setAdMode(false)
                service.pressBack()
                currentTaskIndex++  // 陷阱任务直接跳过，不重玩（避免再次掉入同一陷阱）
                handler.postDelayed({
                    if (state == AutomationState.WATCHING_AD) {
                        if (!service.isOnFarmPage()) service.pressBack()
                        handler.postDelayed({
                            if (state == AutomationState.WATCHING_AD) {
                                moveTo(AutomationState.OPENING_TASK_LIST)
                                handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                            }
                        }, INTERVAL_PAGE_LOAD_MS)
                    }
                }, INTERVAL_PAGE_LOAD_MS)
                return
            }
            // build599 v2: 摇一摇/扭一扭互动广告 — 点击"点击打开或者下载第三方应用"按钮等待下载完成
            // 用户反馈（修订 v1 的 pressBack 退出策略）：
            // "这种广告的处理方式为点击按钮'点击打开或者下载第三方应用'，然后下载完成，获取肥料"
            //
            // 策略：
            // 1. findInteractiveAdDownloadButton 找"点击打开或者下载第三方应用"按钮
            // 2. 点击该按钮 → 跳转应用商店/下载页 → 等待下载完成（最多 60s 轮询检测）
            // 3. 下载完成后回广告页 → 领取肥料 → AD_ENDED → 正常关闭流程
            // 4. 若找不到下载按钮，pressBack 退出避免卡死
            //
            // 防重入：用 interactiveAdDownloadClicked 标记,每轮广告只点一次下载按钮
            PageScene.TRAP_INTERACTIVE -> {
                // build748 修复（debug_test_20260822_195844.log, build747, 19:52:55/19:55:30/19:57:01 共3次）：
                //   快手互动广告文案"扭一扭或点击立即获取"——**点击可替代物理摇动直接获取奖励**。
                //   旧逻辑把所有"扭一扭"当无法自动化的陷阱：干等15s → CLOSING_AD(≈25s) →
                //   RETURNING forceKill UC(≈25s) → 每次浪费~60s 且奖励丢失,本轮3次共3分钟。
                //   修复：先检测"扭一扭或点击立即获取"类按钮（findInteractiveAdClickToClaimButton
                //   已排除"点击跳转详情页/第三方应用"陷阱变体）→ 点击它 → 后续轮询由
                //   isAdEndedMultiSignal 检测"领取成功"等结束信号,走正常 REWARD_POPUP 流程。
                if (!interactiveAdDownloadClicked) {
                    // build781 修复（debug_test_20260906_092636.log, 09:24:54-09:26:33 死等102s用户手动停）：
                    //   百度 MobRewardVideoActivity 互动广告:点"去体验9秒可直接拿奖励"后
                    //   无任何跳转(按钮clickable=false,ACTION_CLICK点在depth=3容器上,第三方App
                    //   未拉起),interactiveAdClickClaimClicked一次性标记阻止重试 → 视频已播完
                    //   (AI进度0%,静态endcard)干等到90s超时,同家族广告三份日志零奖励。
                    //   修复:点击后12s仍停留在广告Activity(未跳转)→清除一次性标记重试一次;
                    //   重试再失败→点"跳过"提前退出死广告(静态endcard不会自动结束,
                    //   跳过比干等90s强;视频已播完,跳过大概率仍按已播发奖励)。
                    if (interactiveAdClickClaimClicked && interactiveAdJumpPending) {
                        val sinceClick = System.currentTimeMillis() - interactiveAdClickClaimTimeMs
                        val stillInAd = service.isAdActivity() || service.isAdPlaying()
                        if (interactiveAdClickClaimTimeMs > 0L && sinceClick > 12000L && stillInAd) {
                            if (!interactiveAdClaimRetried) {
                                interactiveAdClaimRetried = true
                                interactiveAdClickClaimClicked = false
                                interactiveAdJumpPending = false
                                Log.w(TAG, "watchAd: click-to-claim no jump after ${sinceClick}ms, allow one retry (build781)")
                                debugLog("watchAd: 去体验点击${sinceClick}ms无跳转,清除一次性标记重试一次 (build781)")
                                // 落入下方点击逻辑重试一次
                            } else {
                                Log.w(TAG, "watchAd: click-to-claim retry also no jump, click 跳过 to exit dead interactive ad (build781)")
                                debugLog("watchAd: 去体验重试仍无跳转,点'跳过'退出死广告 (build781)")
                                interactiveAdJumpPending = false
                                recordTrapAdExit()  // 死广告=未发奖,计入陷阱退出(发奖会自动重置streak)
                                val rootNow = service.rootInActiveWindowSafe()
                                val skipNode = rootNow?.let {
                                    service.findNodeByText(it, "跳过")
                                        ?: service.findNodeByText(it, "跳过广告")
                                        ?: service.findNodeByText(it, "跳过视频")
                                }
                                if (skipNode != null && service.performClickSafe(skipNode)) {
                                    debugLog("watchAd: clicked '跳过' to exit dead interactive ad (build781)")
                                } else {
                                    debugLog("watchAd: no 跳过 node found, pressBack to exit dead interactive ad (build781)")
                                    service.pressBack()
                                }
                                // 交给后续轮询的广告结束/离开检测处理
                                handler.postDelayed({
                                    if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                                }, INTERVAL_PAGE_LOAD_MS)
                                return
                            }
                        }
                    }
                    val clickClaimBtn = service.findInteractiveAdClickToClaimButton()
                    if (clickClaimBtn != null && !interactiveAdClickClaimClicked) {
                        interactiveAdClickClaimClicked = true
                        interactiveAdClickClaimTimeMs = System.currentTimeMillis()  // build781: 记录点击时刻
                        val ccText = clickClaimBtn.text?.toString().orEmpty()
                        // build759: "点击跳转拿奖励"类按钮点击后会拉起第三方App（淘宝闪购等），
                        // 置跳转守卫标志——落地页的陷阱场景降级（见 when 前 effectiveScene），
                        // 由深链分支处理停留计时+保留现场切回农场。
                        if (ccText.contains("跳转") || ccText.contains("拿奖励")) {
                            interactiveAdJumpPending = true
                            debugLog("watchAd: 跳转类拿奖励按钮, 点击后将拉起第三方App, 跳转守卫已置位(interactiveAdJumpPending=true)")
                        }
                        Log.i(TAG, "watchAd: interactive ad click-to-claim button '$ccText' found (click replaces shake), clicking to get reward")
                        debugLog("watchAd: 互动广告'${ccText}'按钮(点击可替代摇一摇), 点击直接获取奖励")
                        service.performClickSafe(clickClaimBtn)
                        // 点击后等待"领取成功"/奖励弹窗,继续轮询广告结束检测
                        handler.postDelayed({
                            if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                        }, INTERVAL_PAGE_LOAD_MS)
                        return
                    }
                    val downloadBtn = service.findInteractiveAdDownloadButton()
                    if (downloadBtn != null) {
                        interactiveAdDownloadClicked = true
                        val btnText = downloadBtn.text?.toString().orEmpty()
                        Log.i(TAG, "watchAd: interactive ad detected, clicking download button '$btnText' to start download")
                        debugLog("watchAd: interactive ad (摇一摇/扭一扭), click download button '$btnText', wait for download complete")
                        service.performClickSafe(downloadBtn)
                        // 点击后等待应用商店打开/下载,延长等待时间（最长 120s 给下载留足时间）
                        adMaxDurationMs = maxOf(adMaxDurationMs, 120000L)
                        handler.postDelayed({
                            if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                        }, INTERVAL_PAGE_LOAD_MS)
                        return
                    }
                    // 找不到下载按钮,等 5s 让页面加载（首次可能按钮还没渲染）
                    if (elapsedMs < 5000L) {
                        debugLog("watchAd: interactive ad detected but download button not found (elapsed=${elapsedMs}ms), waiting")
                        handler.postDelayed({
                            if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                        }, adEndCheckIntervalMs)
                        return
                    }
                    // build662 修复（debug_test_20260727_072301.log, build661-2951330）：
                    //   07:22:21.859 findAdDurationHint: found countdown '10秒', seconds=10
                    //   07:22:22.035 watchAd: scene=TRAP_INTERACTIVE, elapsed=0ms
                    //   07:22:27.326 watchAd: interactive ad no download button, pressBack to exit (5s 时 pressBack)
                    //   → 但广告倒计时 10s 还没结束，pressBack 退出失败
                    //   → 状态切到 OPENING_TASK_LIST 但实际仍卡在 KsRewardVideoActivity
                    //   → NAVIGATING 阶段 "UC ad, waiting instead of pressBack" 反复等待 → STOPPING
                    // 根因：互动广告无下载按钮时 5s 就 pressBack，但广告倒计时可能还没结束（如 10s），
                    //   UC 激励视频 pressBack 无效（build580 已确认），导致卡在广告 Activity。
                    // 修复：无下载按钮时不主动 pressBack，继续轮询等待广告自然结束。
                    //   - 倒计时结束后 isAdEndedMultiSignal 检测"恭喜获取奖励"等信号 → CLOSING_AD 主动关闭
                    //   - 或 adMaxDurationMs（hint+buffer）超时后兜底退出
                    //   两种方式都比 5s 盲目 pressBack 更可靠。
                    //
                    // build680 修复（debug_test_20260801_151301.log, build679, 15:12:16-15:12:56）：
                    //   15:12:16.470 findAdDurationHint: found countdown '10秒', seconds=10
                    //   15:12:16.474 watchAd: parsed ad duration hint=10s, min wait=12000ms
                    //   15:12:16-15:12:53 scene=TRAP_INTERACTIVE, no download button, continue waiting
                    //   15:12:56 用户手动停止(卡死 40 秒)
                    // 根因：无下载按钮时一直 return,后续的 isAdEndedMultiSignal 检测永远不执行,
                    //   即使广告倒计时 10s 已结束(min wait=12s),也无法识别广告结束并关闭。
                    // 修复：无下载按钮 且 elapsedMs >= adMinDurationMs 时,不 return,跳出 TRAP_INTERACTIVE 分支,
                    //   让后续的广告结束检测(isAdEndedMultiSignal)有机会执行,识别广告结束后走 CLOSING_AD 关闭。
                    //
                    // build682 修复（debug_test_20260801_201024.log, build680, 20:10:03-20:10:18）：
                    //   原代码 fall through 后误入下方"已点击下载按钮"分支（无条件 return）,
                    //   导致后续 isAdEndedMultiSignal 检测永远不执行,卡死直到用户手动停止。
                    //   20:10:03.650 watchAd: interactive ad no download button, min wait elapsed, fall through to ad-end check
                    //   20:10:03.652 watchAd: interactive ad download clicked, waiting for download complete  ← 误入此分支
                    //   20:10:13.931 watchAd: interactive ad download clicked, waiting for download complete  ← 反复卡死
                    //   20:10:18.679 state: WATCHING_AD -> STOPPING (用户手动停止)
                    // 修复：将"已点击下载按钮"分支改为 else 分支（仅当 interactiveAdDownloadClicked=true 时执行）,
                    //   无下载按钮 fall through 时不会误入,直接执行后续的广告结束检测。
                    if (elapsedMs < adMinDurationMs) {
                        // build759: 每轮 dump 页面文本，追踪倒计时变化/奖励按钮出现时机（类型调试用）
                        val trapTexts = service.collectAllTextSnapshot(maxCount = 10)
                        debugLog("watchAd: interactive ad no download button, continue waiting for ad to end (elapsed=${elapsedMs}ms/${adMaxDurationMs}ms), texts=$trapTexts")
                        handler.postDelayed({
                            if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                        }, adEndCheckIntervalMs)
                        return
                    }
                    // elapsedMs >= adMinDurationMs: 广告倒计时应该已结束,跳出 TRAP_INTERACTIVE 分支,
                    // 让后续的 isAdEndedMultiSignal 检测广告结束状态并关闭广告
                    debugLog("watchAd: interactive ad no download button, min wait elapsed (${elapsedMs}ms/${adMinDurationMs}ms), fall through to ad-end check")
                    // 不 return,跳出 TRAP_INTERACTIVE 分支,执行后续广告结束检测
                } else {
                    // 已点击下载按钮,继续轮询等待下载完成 + 肥料发放
                    debugLog("watchAd: interactive ad download clicked, waiting for download complete (elapsed=${elapsedMs}ms/${adMaxDurationMs}ms)")
                    handler.postDelayed({
                        if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                    }, adEndCheckIntervalMs)
                    return
                }
            }
            // 陷阱5：诱导弹窗（页面上有"立即下载"等按钮，可能是广告播放中弹出的诱导遮罩）
            // 策略：优先点"关闭/暂不/拒绝"关闭弹窗，绝不点诱导按钮，继续轮询
            PageScene.TRAP_INSTALL -> {
                // build735 修复（debug_test_20260817_191553.log, build733, 19:15:13-19:15:49）:
                //   汇川"点击跳转后停留15秒立即获奖"广告(千问APP)整页无关闭按钮,
                //   closeAdInstallPopup 失败 → pressBack 每5s循环,广告 Activity 拦截 back 键
                //   退不出去,36s+无效果直到用户手动停止(本分支原无超时保护)。
                //   pressBack 连续 4 次(约20s)无效或 elapsed 超时时,
                //   forceKillApp 杀宿主 + reopenFarmByDeepLink 重开农场兜底
                //   (同 build732 TRAP_RECHARGE 修复,该日志已验证此手段 100% 有效)。
                if (trapInstallBackCount >= 4 || elapsedMs >= adMaxDurationMs) {
                    Log.w(TAG, "watchAd: install trap back x$trapInstallBackCount ineffective (elapsed=${elapsedMs}ms), force killing host app and relaunching farm")
                    debugLog("watchAd: 安装陷阱pressBack ${trapInstallBackCount}次无效(elapsed=${elapsedMs}ms), forceKillApp杀宿主+重开农场兜底")
                    recordTrapAdExit()  // build754: 陷阱退出计数（连续无进展则跳过视频类任务）
                    service.setAdMode(false)
                    val farmPkgs = service.currentPlatformConfig().packageNames
                    for (pkg in farmPkgs) {
                        service.forceKillApp(pkg, pressBackFirst = false)
                    }
                    service.reopenFarmByDeepLink(killCurrentFirst = false)
                    moveTo(AutomationState.NAVIGATING)
                    handler.postDelayed({ runNavigating(attempt = 0) }, INTERVAL_PAGE_LOAD_MS)
                    return
                }
                Log.w(TAG, "watchAd: install popup detected (scene=$scene), trying to close it (trap defense)")
                debugLog("watchAd: install popup trap detected, attempting closeAdInstallPopup")
                val closed = service.closeAdInstallPopup()
                if (!closed) {
                    // 找不到关闭类按钮，可能弹窗本身就是全屏落地页 → 按返回退出
                    debugLog("watchAd: no close button for install popup, pressing back")
                    service.pressBack()
                    trapInstallBackCount++
                }
                handler.postDelayed({
                    if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                }, INTERVAL_CLICK_MS)
                return
            }
            // 陷阱6：广告复看陷阱（"再看一个"/"加倍领取"诱导继续看广告）
            // 策略：优先点关闭类按钮，绝不点诱导按钮，避免被套娃看更多广告，继续轮询
            PageScene.TRAP_REPLAY -> {
                Log.w(TAG, "watchAd: ad replay trap detected (scene=$scene), closing (再看一个/加倍领取)")
                debugLog("watchAd: replay trap handled, continuing to check ad end")
                service.closeAdReplayTrap()
                handler.postDelayed({
                    if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                }, INTERVAL_CLICK_MS)
                return
            }
            // 通用弹窗（无肥料提示，需主动关闭）
            // 用户需求：弹框窗口如何没有肥料提示，需要关闭弹窗
            // 策略：优先点关闭按钮（×/关闭/知道了/确定等），否则按返回键，继续轮询等待广告恢复
            PageScene.GENERIC_POPUP -> {
                Log.i(TAG, "watchAd: generic popup detected (no fertilizer hint), closing it")
                debugLog("watchAd: generic popup (no fertilizer), attempting to close")
                // build775: 与 navigate 同理——锁屏被误判 generic popup 时按 back 无效,
                // 先判 isLockScreenShowing,命中则点亮+上滑解锁,不按 back
                if (service.isLockScreenShowing()) {
                    Log.w(TAG, "watchAd: lock screen detected, wake + swipe up to unlock (not a popup)")
                    debugLog("watchAd: 锁屏/熄屏检测命中,点亮+上滑解锁(不按back)")
                    service.ensureScreenOn()
                    service.swipeUpToUnlock()
                    handler.postDelayed({
                        if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                    }, 3000L)
                    return
                }
                val closeBtn = service.findAdCloseButton()
                if (closeBtn != null) {
                    debugLog("watchAd: clicking close button on generic popup (text='${closeBtn.text}')")
                    service.performClickSafe(closeBtn)
                } else {
                    debugLog("watchAd: no close button found for generic popup, pressing back")
                    service.pressBack()
                }
                handler.postDelayed({
                    if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                }, INTERVAL_CLICK_MS)
                return
            }
            // build595: 系统级权限弹窗（如 UC 推送权限授权弹窗，广告播放期间也可能弹出）
            // 策略：点击"拒绝/暂不/关闭"按钮,绝不点"允许/开启",继续轮询等待广告恢复
            PageScene.SYSTEM_PERMISSION -> {
                Log.i(TAG, "watchAd: system permission popup detected, closing it (deny button)")
                debugLog("watchAd: system permission popup, attempting to close with deny button")
                val denyBtn = service.findSystemPermissionDenyButton()
                if (denyBtn != null) {
                    debugLog("watchAd: clicking deny button on permission popup (text='${denyBtn.text}')")
                    service.performClickSafe(denyBtn)
                } else {
                    debugLog("watchAd: no deny button found, pressing back to close popup")
                    service.pressBack()
                }
                handler.postDelayed({
                    if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                }, INTERVAL_CLICK_MS)
                return
            }
            // 非陷阱场景（FARM_PAGE / AD_PLAYING / AD_ENDED / REWARD_POPUP / UNKNOWN），
            // 继续后续流程检测（超时、深链、最短等待、广告结束等）
            else -> {
                // 场景识别未命中陷阱，由后续超时/深链/广告结束检测处理
            }
        }

        // build678 修复（debug_test_20260801_144605.log, build677, 14:45:04-14:45:58）：
        // UC"看视频得巨额肥料"任务点击后进入穿山甲激励视频,顶部提示"点击商品，领取奖励"
        // (clickable=false),页面含商品列表（淘宝精选/医用不锈钢剪刀等）。
        // 原本 isClickProductAd 只在 checkTaskListOpened(OPENING_TASK_LIST 状态)中处理,
        // 但实际广告是从 processTask 进入 WATCHING_AD 状态的,watchAd 没有对应处理逻辑,
        // 导致 scene 误判 AD_ENDED,bot 干等 53 秒直到用户手动停止。
        //
        // 用户需求："点击商品，领取奖励，这只是一个提示，不需要点击，通过点击商品去获取奖励"
        // build719 修复（debug_test_20260809_092433.log, build719, 09:23:18-09:24:30）：
        //   穿山甲 TTRewardVideoActivity 互动体验广告,页面含"上滑或点击查看"提示,
        //   需要用户互动(上滑或点击)才能继续播放/结束。bot 不互动 → scene=AD_PLAYING
        //   干等60秒直到用户手动停止。
        //   修复:检测到"上滑或点击查看"提示且超过15秒时,点击屏幕中部(广告内容区域)
        //   触发广告继续播放。用 adSwipeHintClicked 标记每轮广告只点一次。
        if (!adSwipeHintClicked && elapsedMs >= 15000L) {
            val root = service.getRootInFarmApp()
            if (root != null) {
                val pageTexts = service.collectAllText(root)
                if (pageTexts.any { it.contains("上滑") || it.contains("点击查看") }) {
                    debugLog("watchAd: interactive ad '上滑或点击查看' detected (elapsed=${elapsedMs}ms), clicking center to continue")
                    adSwipeHintClicked = true
                    // 点击屏幕中部(广告内容区域)触发继续
                    service.dispatchGestureClick(600f, 1200f)
                    handler.postDelayed({
                        if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                    }, INTERVAL_CLICK_MS)
                    return
                }
            }
        }
        // 策略（复用 checkTaskListOpened 中的 adProductClicked 标记）：
        // - 阶段1：isClickProductAd() 且未点击商品时,找商品节点(findAdProductNode)并点击
        // - 阶段2：已点击商品后等 5s（让奖励触发），找关闭按钮或 pressBack 关闭广告
        // - 重置标记,继续轮询（若仍在广告页,会重新尝试点击商品）
        // build731（debug_test_20260816_185130.log, build729）：
        //   build728 曾在 huichuanMerchantPending=true 时跳过整块纯等待 90s,
        //   但日志证明"奖励已发放"从未出现 → "点击商家后立即领奖"语义是返回后需
        //   再点击商家,奖励才发放。恢复本块执行(返回后重置 adProductClicked,
        //   阶段1会再点一次商品=点击商家),阶段2在 huichuanMerchantPending=true 时
        //   不 2s 自动关闭,改为等待"奖励已发放"(最多15s),超时点关闭退出。
        if (service.isClickProductAd()) {
            val now = System.currentTimeMillis()
            // build702 修复（debug_test_20260803_080115.log, build701, 08:01:09-08:01:14）：
            //   关闭广告后出现"确认要离开吗？"弹窗,30秒后弹窗消失回到广告页,
            //   isClickProductAd() 又返回 true,adProductClicked 已被重置为 false,
            //   导致再次点击商品 → 循环。
            //   修复:增加 adProductClickCount 计数,每轮广告最多点击2次商品,
            //   超过后不再点击,直接 pressBack 退出。
            if (!adProductClicked && adProductClickCount >= 2) {
                // build768-2 修复（debug_test_20260905_063248.log, build770, 06:32:10-06:32:44 死循环61秒）：
                //   腾讯 PortraitADActivity 点击商品广告,back 被广告 SDK 拦截,
                //   "pressing back to exit" 每 2.5s 一次连续 17 次无效;且本分支
                //   return 在 90s 超时强制关闭守卫之前,超时永远不可达 → 无限循环。
                //   修复:back 连续 3 次无效(约7.5s)或总时长超 max 时,升级 CLOSING_AD
                //   多策略关闭(关闭按钮→坐标盲点→RETURNING forceKill 兜底,build750 已验证)。
                adProductExitBackCount++
                if (adProductExitBackCount >= 3 || elapsedMs >= adMaxDurationMs) {
                    Log.w(TAG, "watchAd: 点击商品 ad back×$adProductExitBackCount ineffective (elapsed=${elapsedMs}ms/${adMaxDurationMs}ms), escalating to CLOSING_AD")
                    debugLog("watchAd: 点击商品广告 back×${adProductExitBackCount}无效(广告SDK拦截back),升级CLOSING_AD多策略关闭")
                    moveTo(AutomationState.CLOSING_AD)
                    handler.postDelayed({ runClosingAd(strategy = 0) }, INTERVAL_CLICK_MS)
                    return
                }
                Log.w(TAG, "watchAd: 点击商品 ad detected but already clicked ${adProductClickCount} times, pressing back to exit (elapsed=${elapsedMs}ms, backCount=$adProductExitBackCount)")
                debugLog("watchAd: 点击商品 ad detected but click count=${adProductClickCount} >= 2, pressing back to exit (backCount=$adProductExitBackCount/3)")
                service.pressBack()
                handler.postDelayed({
                    if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                }, INTERVAL_CLICK_MS)
                return
            }
            if (!adProductClicked) {
                // 阶段1：找商品节点点击
                val productNode = service.findAdProductNode()
                if (productNode != null) {
                    val rect = Rect()
                    productNode.getBoundsInScreen(rect)
                    Log.i(TAG, "watchAd: clicking ad product to trigger reward (bounds=${rect.toShortString()})")
                    debugLog("watchAd: 点击商品 ad detected, gesture-clicking product bounds=${rect.toShortString()} center (${rect.centerX()},${rect.centerY()}) (count=${adProductClickCount + 1})")
                    // build774 修复（debug_test_20260905_100732.log, build772, 汇川广告三轮对比）：
                    //   #1/#3 用 performClickSafe ACTION_CLICK 点商品节点（depth=0 成功）,
                    //   页面无任何跳转,等15s无"奖励已发放"放弃; #2 找不到节点改点屏幕中心手势
                    //   → 跳进商品详情页 → "奖励已发放" → 拿奖(任务进度 1/10→2/10)。
                    //   结论:汇川 WebView 广告不把无障碍 ACTION_CLICK 计为"点击商家",
                    //   只有真实触摸手势才触发跳转发奖。改为按节点 bounds 中心手势点击。
                    service.dispatchGestureClick(rect.centerX().toFloat(), rect.centerY().toFloat())
                    adProductClicked = true
                    adProductClickTimeMs = now
                    adProductClickCount++
                    // build735（debug_test_20260817_191553.log, build733）:
                    //   汇川"点击跳转后停留N秒立即获奖"变体: 点击转化按钮后跳转落地页,
                    //   停留15秒奖励才发放。阶段2原逻辑2s后关闭广告会放弃奖励,
                    //   设 huichuanMerchantPending=true 改为等待"奖励已发放"
                    //   (跳转后落地页无CTA文案,由上方守卫块接管等待)。
                    if (service.isClickJumpStayAd()) {
                        huichuanMerchantPending = true
                        Log.i(TAG, "watchAd: click-jump-stay ad detected, set huichuanMerchantPending=true to wait for reward on landing page")
                        debugLog("watchAd: '点击跳转后停留'广告已点击转化按钮,等待落地页停留计时'奖励已发放'(不2s关闭)")
                    }
                } else {
                    // 找不到可点击商品节点：可能是页面还没渲染,等 2s 后重试
                    // build722 修复（debug_test_20260811_081229.log, build721, 08:11:59-08:12:25）：
                    //   腾讯广告 PortraitADActivity 商品节点在 WebView 内不可访问,
                    //   findAdProductNode 一直返回 null,每 2s 重试无超时,干等 20s 用户手动停止。
                    //   修复:连续失败超过 5 次(约10s)后,改用 dispatchGestureClick 点击屏幕中部
                    //   (广告内容区域)触发奖励,避免无限重试。
                    adProductNodeFindFailCount++
                    if (adProductNodeFindFailCount >= 5) {
                        Log.w(TAG, "watchAd: 点击商品 ad no clickable product node after ${adProductNodeFindFailCount} retries, clicking center to trigger reward (elapsed=${elapsedMs}ms)")
                        debugLog("watchAd: 点击商品 ad no clickable product node after ${adProductNodeFindFailCount} retries (elapsed=${elapsedMs}ms), clicking center (600,1200) to trigger reward")
                        service.dispatchGestureClick(600f, 1200f)
                        adProductNodeFindFailCount = 0  // 重置,避免下次立即再点
                        // 标记为已点击,进入阶段2等待 2s 后关闭广告
                        adProductClicked = true
                        adProductClickTimeMs = now
                        adProductClickCount++
                    } else {
                        debugLog("watchAd: 点击商品 ad detected but no clickable product node, retrying in 2s (elapsed=${elapsedMs}ms, failCount=${adProductNodeFindFailCount})")
                    }
                }
            } else {
                // 阶段2：已点击商品
                if (huichuanMerchantPending) {
                    // build731: 汇川"返回点击商家"后已点击商家(阶段1再点的商品),
                    // 等待"奖励已发放"出现(build729: 检测到后点右上角关闭图标领奖),
                    // 不再 2s 自动关闭(185130 日志证明纯等待也无效,必须点击商家+等待)。
                    if (detectRewardGrantedText(service)) {
                        claimRewardViaCloseIcon(service, elapsedMs)
                        return
                    }
                    val sinceMerchantClick = now - adProductClickTimeMs
                    if (sinceMerchantClick >= 15000L) {
                        // 点击商家 15s 后仍无"奖励已发放",点关闭图标退出
                        // (若再弹"确认要离开吗"弹窗,弹窗块走第二次分支"放弃奖励离开"防死循环)
                        Log.i(TAG, "watchAd: merchant clicked ${sinceMerchantClick}ms ago but no reward granted text, clicking close to exit")
                        debugLog("watchAd: 商家点击后${sinceMerchantClick}ms仍无'奖励已发放', 点关闭图标退出(再弹确认弹窗则放弃奖励离开)")
                        val closeIcon = service.findAdCloseButton(service.currentPlatformConfig().adCloseButtonTexts, enforceSceneWhitelist = false)
                        if (closeIcon != null) {
                            service.performClickSafe(closeIcon)
                        } else {
                            service.pressBack()
                        }
                        // huichuanMerchantPending 保持 true: 若弹窗再出现,弹窗块识别为第二次,直接放弃奖励离开
                    } else {
                        debugLog("watchAd: 商家已点击,等待'奖励已发放' (${sinceMerchantClick}ms/15000ms)")
                    }
                } else {
                    // 原逻辑:点击商品 2s 后关闭广告
                    // build703: 用户需求"点击商品后,右上角点击关闭任务就完成了"
                    //   原 5s 等待太长,缩短到 2s,点击商品后更快关闭广告。
                    val sinceClick = now - adProductClickTimeMs
                    if (sinceClick >= 2000L) {
                        Log.i(TAG, "watchAd: 2s after clicking ad product, closing ad window (sinceClick=${sinceClick}ms)")
                        debugLog("watchAd: closing ad window 2s after product click (sinceClick=${sinceClick}ms)")
                        // 优先找关闭按钮,找不到 pressBack
                        val closeBtn = service.findAdCloseButton(service.currentPlatformConfig().adCloseButtonTexts, enforceSceneWhitelist = false)
                        if (closeBtn != null) {
                            debugLog("watchAd: clicking close button on ad (text='${closeBtn.text}')")
                            service.performClickSafe(closeBtn)
                        } else {
                            debugLog("watchAd: no close button, pressing back to close ad")
                            service.pressBack()
                        }
                        // 重置标记：下一轮如果还在广告中,会重新尝试点击商品
                        adProductClicked = false
                        adProductClickTimeMs = 0L
                    } else {
                        debugLog("watchAd: waiting ${sinceClick}ms/2000ms after clicking ad product")
                    }
                }
            }
            // 点击商品广告：用较短间隔(2s)轮询
            handler.postDelayed({
                if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + INTERVAL_CLICK_MS)
            }, INTERVAL_CLICK_MS)
            return
        }

        // build731: huichuanMerchantPending=true 且页面非点击商品广告(isClickProductAd=false)时,
        // 说明返回后页面是商家详情页等形态,无可点商品节点,耐心等待"奖励已发放"
        // (isClickProductAd=true 的列表页形态由上方 isClickProductAd 块处理:点商家→等奖励)
        if (huichuanMerchantPending && elapsedMs % 15000L < adEndCheckIntervalMs) {
            debugLog("watchAd: huichuanMerchantPending=true (merchant page form), waiting for 奖励已发放 (elapsed=${elapsedMs}ms/${adMaxDurationMs}ms)")
        }

        // build729 修复（用户需求："遇到奖励已发放，右边的关闭图标，需要点击关闭，就获得奖励了"）：
        //   广告页面出现"奖励已发放"等已领奖标志时,奖励已到账,
        //   立即点击右上角关闭图标退出广告即可获得奖励。
        //   无需等满最短观看时间(adMinDurationMs),也无需再走领取按钮/放弃奖励流程。
        //   (汇川广告"返回点击商家"回广告后,奖励计时结束显示"奖励已发放",
        //    原逻辑要等满30s min duration 才检测,白等20s+)
        // 守卫:仍处于广告Activity/广告播放中才触发(避免农场页/落地页文案误判)。
        // build730: 检测与领奖逻辑提取为 detectRewardGrantedText()/claimRewardViaCloseIcon()
        //   供下方 huichuanMerchantPending 守卫块复用。
        if (service.isAdActivity() || service.isAdPlaying()) {
            if (detectRewardGrantedText(service)) {
                claimRewardViaCloseIcon(service, elapsedMs)
                return
            }
        }

        // build678 修复（debug_test_20260801_144605.log, build677, 14:45:39-14:45:58）：
        // 点击商品广告后弹出"番茄畅听下载确认"系统对话框(act=android.app.AlertDialog)：
        // texts=[您已下载的"番茄畅听"未下载完成（文件大小98.24 M），要继续下载吗, 取消, 确认]
        // 原逻辑未识别此对话框,继续干等广告结束,卡死 13 秒直到用户手动停止。
        //
        // 策略：检测到下载确认对话框时,优先点"取消"（不继续下载）,继续轮询等待广告恢复。
        // 特征：AlertDialog + 含"未下载完成" + 含"取消"/"确认"按钮
        if (service.isDownloadConfirmDialog()) {
            val cancelBtn = service.findDownloadConfirmCancelButton()
            if (cancelBtn != null) {
                Log.i(TAG, "watchAd: download confirm dialog detected, clicking cancel")
                debugLog("watchAd: 番茄畅听下载确认对话框, clicking 取消")
                service.performClickSafe(cancelBtn)
            } else {
                debugLog("watchAd: download confirm dialog detected but no cancel button, pressing back")
                service.pressBack()
            }
            handler.postDelayed({
                if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
            }, INTERVAL_CLICK_MS)
            return
        }

        // build704 修复（debug_test_20260803_080115.log, build701, 08:00:41-08:01:14）：
        //   点击商品广告→2s后点关闭按钮→弹出"确认要离开吗？"对话框,
        //   texts=[点击商家后立即领奖, 确认要离开吗？, 返回点击商家, 放弃奖励离开]
        //   原逻辑未识别此弹窗,scene=AD_ENDED,干等 30 秒直到用户手动停止。
        //   用户需求："点击商品后,右上角点击关闭任务就完成了"
        //   修复:检测到"确认要离开吗"弹窗时,点击"放弃奖励离开"立即退出,不再干等。
        //   (商品奖励已在点击商品时触发,放弃奖励离开只是确认退出广告)
        // build711 修复（debug_test_20260808_092128.log, build709, 09:21:01-09:21:25）：
        //   点击"放弃奖励离开"后,广告关闭回到农场主页。但 adModeFlag 仍为 true,
        //   下一轮 runWatchingAd 时 isOnFarmPage() 因 adModeFlag=true 返回 false,
        //   isRechargePage() 因 isOnFarmPage()=false 而继续检查,
        //   农场主页文本(含"下单得")被误判为充值页 → scene=TRAP_RECHARGE,
        //   clickCloseOnRechargePage 在农场主页误点"关闭"按钮,卡死,用户手动停止。
        //   修复:点击"放弃奖励离开"后,立即清除 adModeFlag 并进入 RETURNING 流程,
        //   不再继续 runWatchingAd 轮询(广告已关闭,无需继续等待)。
        val leaveConfirmBtn = service.findLeaveConfirmAbandonButton()
        if (leaveConfirmBtn != null) {
            // build727→731 修复（debug_test_20260816_181940.log + 185130.log）：
            //   汇川广告(HCRewardVideoActivity)"点击商品，领取奖励"流程:
            //   点击商品 → 2s后关闭广告 → 弹出"确认要离开吗"弹窗
            //   弹窗内容: [点击商家后立即领奖, 确认要离开吗？, 返回点击商家, 放弃奖励离开]
            //   原逻辑(build704): 点击"放弃奖励离开"退出,假设"商品奖励已在点击商品时触发"。
            //   实际(日志证据): "已领取"文本bounds在广告前后完全一致,奖励未发放。
            //
            //   build728: 点"返回点击商家"回广告后纯等待(huichuanMerchantPending跳过isClickProductAd)。
            //   185130 日志证明纯等待 90s 无效:"奖励已发放"从未出现,页面纹丝不动。
            //   → "点击商家后立即领奖"语义是:返回后需再点击商家,奖励才发放。
            //
            //   build731 正确策略:
            //   - 第一次弹窗(huichuanMerchantPending=false): 点"返回点击商家"回广告,
            //     重置adProductClicked=false(阶段1会再点一次商品=点击商家),
            //     设huichuanMerchantPending=true(阶段2改为等待"奖励已发放",不2s自动关闭)
            //   - 第二次弹窗(huichuanMerchantPending=true): 已试过点击商家仍未领奖,
            //     点"放弃奖励离开"退出(防死循环),正常走下方退出流程
            val root = service.getRootInFarmApp()
            val hasClickMerchantHint = root != null && run {
                val texts = service.collectAllText(root)
                texts.any { it.contains("点击商家后立即领奖") || it.contains("点击商家") }
            }
            if (hasClickMerchantHint) {
                val returnBtn = root?.let { service.findNodeByText(it, "返回点击商家") }
                if (returnBtn != null && !huichuanMerchantPending) {
                    Log.i(TAG, "watchAd: leave confirm dialog with '点击商家后立即领奖' (1st), clicking '返回点击商家' to click merchant (elapsed=${elapsedMs}ms)")
                    debugLog("watchAd: 确认要离开吗弹窗含'点击商家后立即领奖'(第1次,奖励未触发), 点击 返回点击商家 回广告再点击商家等奖励")
                    service.performClickSafe(returnBtn)
                    // build731: 重置adProductClicked,回广告后阶段1再点一次商品(=点击商家)
                    adProductClicked = false
                    adProductClickTimeMs = 0L
                    huichuanMerchantPending = true
                    handler.postDelayed({
                        if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                    }, adEndCheckIntervalMs)
                    return
                }
                if (huichuanMerchantPending) {
                    Log.i(TAG, "watchAd: leave confirm dialog with '点击商家后立即领奖' (2nd, merchant clicked but no reward), abandoning reward to exit")
                    debugLog("watchAd: 确认要离开吗弹窗含'点击商家后立即领奖'(第2次,已点商家仍未领奖), 放弃奖励离开退出(防死循环)")
                    huichuanMerchantPending = false
                }
            }
            Log.i(TAG, "watchAd: leave confirm dialog detected, clicking abandon reward to exit (elapsed=${elapsedMs}ms)")
            debugLog("watchAd: 确认要离开吗弹窗, clicking 放弃奖励离开, clearing adMode and entering RETURNING")
            service.performClickSafe(leaveConfirmBtn)
            // 立即清除广告模式标志(广告已通过放弃奖励离开关闭)
            service.setAdMode(false)
            collectedCount++
            Log.i(TAG, "=== FERTILIZER COLLECTED! (total: $collectedCount) ===")
            moveTo(AutomationState.RETURNING)
            handler.postDelayed({ runReturning(0) }, INTERVAL_CLICK_MS)
            return
        }

        // build675 优化（用户需求"点击我要加速"）：
        // 穿山甲激励视频广告页面有"点我加速"按钮（日志证据 debug_test_20260801_094058.log:
        // texts=[..., 点我加速, 限时福利, 13秒后失效, 去体验, 15秒]）。
        // 点击后可加速倒计时,让广告更快结束,节省等待时间。
        // 策略：在广告播放期间（elapsedMs < min wait）,检测到"点我加速"按钮时点击一次。
        // 防重入：用 adSpeedUpClicked 标记,每轮广告只点一次。
        // build700: 用户需求"应该点击我要加速",扩展匹配"我要加速"(字节穿山甲 TTRewardVideoActivity 用此文案)。
        // build715 回退:曾因"我要加速"跳转淘宝/闲鱼而移除匹配,但用户澄清"我要加速也需要点击,
        //   需要等待指定的时间后回到芭芭农场广告点击时的页面领取,不是关闭重新打开"。
        // build716 恢复+完善:恢复"我要加速"匹配,点击后进入跳转停留状态机(adSpeedUpJumpStage),
        //   在跳转App停留10秒后pressBack回广告页继续领奖(不关闭重开UC,保留广告会话)。
        if (!adSpeedUpClicked && elapsedMs < adMinDurationMs && elapsedMs >= 1000L) {
            val root = service.getRootInFarmApp()
            if (root != null) {
                // 优先匹配"点我加速"(穿山甲 KsRewardVideoActivity 真正加速按钮,不跳转)
                // 其次匹配"我要加速"(字节穿山甲 TTRewardVideoActivity CTA,会跳转淘宝/闲鱼,需停留后返回)
                val speedUpNode = service.findNodeByText(root, "点我加速")
                    ?: service.findNodeByText(root, "我要加速")
                if (speedUpNode != null) {
                    adSpeedUpClicked = true
                    val speedUpText = speedUpNode.text?.toString().orEmpty()
                    Log.i(TAG, "watchAd: found speedUp button '$speedUpText', clicking to speed up ad (elapsed=${elapsedMs}ms)")
                    debugLog("watchAd: clicking speedUp button '$speedUpText' to speed up ad countdown")
                    service.performClickSafe(speedUpNode)
                    // build716: 如果点击的是"我要加速",进入跳转停留状态机
                    // (点击"点我加速"不跳转,无需进入状态机)
                    if (speedUpText == "我要加速") {
                        adSpeedUpJumpStage = 1
                        debugLog("watchAd: '我要加速' clicked, entering jump stay state (will stay 10s then pressBack to ad page)")
                    }
                    handler.postDelayed({
                        if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                    }, INTERVAL_CLICK_MS)
                    return
                }
            }
        }

        // build699 回退（debug_test_20260803_074314.log, build698, 07:42:20-07:43:11）：
        //   build696 添加了"去体验N秒可立即领奖"CTA 点击逻辑,但点击 CTA 会触发
        //   "确定要退出吗？"弹窗(穿山甲退出确认),弹窗含"去领取奖励"(continue_button)按钮。
        //   findClaimRewardButton 误匹配"去体验15秒可立即领奖"和"去领取奖励",
        //   两者循环卡死60秒,用户手动停止。
        //   回退:移除 CTA 点击逻辑,不点击"去体验N秒可立即领奖",避免触发"确定要退出吗？"弹窗。
        //   findAdDurationHint 仍会检测倒计时(build696 修复保留),广告会在 min=30000ms 后正常结束。

        // build671 修复（debug_test_20260731_214538.log, build669）：
        // UC"看视频得巨额肥料"任务点击后跳转淘宝 TMSActivity,页面"30秒"是静态文字,
        // 非动态倒计时。watchAd 误判为广告倒计时,卡死 62 秒直到用户手动停止。
        // 修复：15 秒后检测倒计时是否减少,若初始值 > 0 且当前倒计时 == 初始值（未减少）,
        // 判定为静态文字伪装倒计时,直接 pressBack 退出跳过任务。
        // 不影响真正广告（真正广告 15 秒后倒计时会减少到约 15-20 秒）。
        if (!adCountdownStallHandled && adInitialCountdownSeconds > 0 && elapsedMs >= 15000L) {
            val currentCountdown = service.findAdDurationHint()
            if (currentCountdown == adInitialCountdownSeconds) {
                adCountdownStallHandled = true
                // build695 修复（debug_test_20260802_202311.log, build694, 20:22:52）:
                //   穿山甲 KsRewardVideoActivity 激励视频广告"10秒"是静态文本(广告总时长提示),
                //   15秒后仍在,触发 countdown stuck 检测 pressBack 退出。
                //   但 KsRewardVideoActivity 的 pressBack 无效,卡在广告 Activity,
                //   NAVIGATING 反复"waiting instead of pressBack" → 用户手动停止。
                //   修复:检测当前是否是激励视频广告 Activity(KsRewardVideoActivity/kwad),
                //         这类广告 pressBack 无效,直接进入 CLOSING_AD 多策略关闭
                //         (策略0找关闭按钮/策略1坐标点击右上角/策略2放弃奖励/策略3 pressBack/策略4领取奖励),
                //         比单纯 pressBack 更有效。不跳过任务(CLOSING_AD 可能成功关闭并获奖励)。
                val actName = service.getCurrentActivityName()?.lowercase().orEmpty()
                val isRewardVideoAd = actName.contains("ksrewardvideo") || actName.contains("kwad")
                if (isRewardVideoAd) {
                    Log.w(TAG, "watchAd: countdown stalled at ${adInitialCountdownSeconds}s, reward video ad (act=$actName), entering CLOSING_AD (pressBack ineffective, elapsed=${elapsedMs}ms)")
                    debugLog("watchAd: countdown stuck at ${adInitialCountdownSeconds}s (static text), reward video ad pressBack ineffective, entering CLOSING_AD")
                    service.setAdMode(true)
                    moveTo(AutomationState.CLOSING_AD)
                    handler.postDelayed({ runClosingAd(strategy = 0) }, INTERVAL_CLICK_MS)
                    return
                }
                // 原逻辑:非激励视频广告(如淘宝 TMSActivity),pressBack 退出跳过任务
                Log.w(TAG, "watchAd: countdown stalled at ${adInitialCountdownSeconds}s for 15s, static text detected, exiting (elapsed=${elapsedMs}ms)")
                debugLog("watchAd: countdown stuck at ${adInitialCountdownSeconds}s (static text, not real ad), pressing back to exit")
                service.setAdMode(false)
                service.pressBack()
                currentTaskIndex++  // 跳过任务,不重玩
                // build676 修复（debug_test_20260801_100054.log, build675）：
                // 原逻辑只 pressBack 一次,如果退出后仍在淘宝页（非农场页）,
                // 会直接进入 OPENING_TASK_LIST,导致 openTaskList 误把淘宝页当农场页 reset 任务进度。
                // 修复：检测退出后是否回到农场页,如果没回,走 NAVIGATING 重新导航回农场页。
                handler.postDelayed({
                    if (state == AutomationState.WATCHING_AD) {
                        if (!service.isOnFarmPage()) {
                            // 仍在非农场页（如淘宝）,走 NAVIGATING 重新回农场页
                            Log.i(TAG, "watchAd: not on farm page after exit, navigating back to farm")
                            debugLog("watchAd: not on farm after stall exit, go NAVIGATING")
                            moveTo(AutomationState.NAVIGATING)
                            handler.postDelayed({ runNavigating(attempt = 0) }, INTERVAL_CLICK_MS)
                        } else {
                            // 已回农场页,继续任务列表
                            moveTo(AutomationState.OPENING_TASK_LIST)
                            handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                        }
                    }
                }, INTERVAL_PAGE_LOAD_MS)
                return
            }
        }

        // 超时强制关闭
        // build737: 深链任务等待期间（deepLinkAppPkg!=null）不受广告超时限制——
        // 深链停留时长由任务文案决定（deepLinkTaskStayMs，可达60s+），超过 adMaxDurationMs
        // （广告专用，通常90s）时不能强制关闭，否则会打断深链等待（下方深链分支有自己的调度）
        if (elapsedMs >= adMaxDurationMs && deepLinkAppPkg == null) {
            Log.w(TAG, "watchAd: timeout (${elapsedMs}ms/${adMaxDurationMs}ms), force closing")
            // build780 修复（debug_test_20260906_072308.log 07:21:33-07:23:01 /
            //   debug_test_20260906_085438.log 08:53:49-08:54:35）：
            //   百度 MobRewardVideoActivity "摇一摇/去体验9秒可直接拿奖励"互动广告等满
            //   90s 超时零奖励（两轮均如此），超时路径未计陷阱退出 → "看广告领奖"
            //   反复被点。修复：广告超时=未发奖，计入陷阱退出，连续无进展 2 次后
            //   跳过视频类入口。若超时广告实际发了奖，collectedCount 变化会自动重置 streak。
            recordTrapAdExit()
            moveTo(AutomationState.CLOSING_AD)
            handler.postDelayed({ runClosingAd(strategy = 0) }, INTERVAL_CLICK_MS)
            return
        }

        // 深链跳转任务：检测是否已回到芭芭农场App（任务完成返回）
        // 深链任务跳转到其他App执行后，回到农场App表示任务完成
        // 要求 elapsedMs >= 5s 避免广告刚打开时短暂显示农场的误判
        // build742: 深链任务返回农场App后，WebView 可能停在任务落地页（任务开始页）而非
        // 农场主页——点击"去完成"时 H5 先导航到落地页再拉起其它App，返回后页面保持落地页。
        // 此时不能直接进 OPENING_TASK_LIST（找不到"去完成"按钮会走坐标兜底乱点），
        // 由 runDeepLinkReturnToFarm 先确保回到农场主页。
        if (elapsedMs >= 5000L && service.isOnFarmPage()) {
            // build743: 深链任务会话（入口标记）或已记录深链包名都算深链任务——
            // 早期返回（5s内回农场，deepLinkAppPkg 尚未记录）也要走落地页恢复
            val wasDeepLinkTask = deepLinkAppPkg != null || watchingAdFromDeepLinkTask
            Log.i(TAG, "watchAd: returned to farm app (${elapsedMs}ms), task complete")
            debugLog("watchAd: returned to farm, deep-link task complete (wasDeepLinkTask=$wasDeepLinkTask)")
            // 取消可能已调度的深链 kill（若曾进入其他 App 又自然返回）
            deepLinkAppPkg = null
            service.setAdMode(false)
            collectedCount++
            advanceTaskIndex()  // 多次任务重玩同一任务，否则前进到下一个
            if (wasDeepLinkTask) {
                // build742: 深链任务——返回的可能是任务落地页（isOnFarmPage 因"任务完成"/
                // "得肥料"文案误判 true），先确保回到农场主页再开任务列表
                runDeepLinkReturnToFarm(0)
            } else {
                handler.postDelayed({
                    if (state == AutomationState.WATCHING_AD) {
                        moveTo(AutomationState.OPENING_TASK_LIST)
                        handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                    }
                }, INTERVAL_CLICK_MS)
            }
            return
        }

        // 深链跳转任务：广告任务跳转到其他 App（如快手，非农场/非广告Activity/非异常页）
        // build737 需求变更（原"等2秒激活+kill"）：
        //   进入其它App后等够任务文案要求的时长（processTask 点击时解析存入 deepLinkTaskStayMs，
        //   如"浏览15秒"→20s，无提示默认20s），再用 bringFarmAppToFront(moveTaskToFront)
        //   保留现场切回农场——不杀农场App、WebView不重载，页面保持切走时的样子
        //   （任务列表弹窗原样恢复，OPENING_TASK_LIST 检测到已打开直接续处理）。
        //   bringFarmAppToFront 失败时（系统未返回农场任务）fallback deep link 拉起（不杀农场进程）。
        //   被跳转App已被切到后台，随后 kill 释放内存（防止干扰后续流程）。
        // 注意：此检查在"最短等待时间"检查之前，确保深链任务用自己的停留超时（而非默认 30s 广告等待）
        // 注：异常交易页（isOnAbnormalPage）已在上方场景识别 TRAP_ABNORMAL 中统一处理
        // build759: interactiveAdJumpPending=true 时放宽 isOnAbnormalPage——
        // "点击跳转拿奖励"的落地页（淘宝闪购等）常含"立即购买"类文案（isOnAbnormalPage
        // =true），但这是预期跳转，也应进入本分支做停留计时+保留现场切回农场。
        // build763: `!isAdPlaying()` 改为 `!isAdPlayingReal()`——
        // 深链任务入口（processTask）与广告入口均 setAdMode(true)，adModeFlag 在整个
        // 广告会话期间恒为 true，isAdPlaying() 恒 true → 本分支条件恒不满足，
        // 深链停留逻辑（build737/761 千问对话/762 互动广告跳转）从未触发过
        // （全部历史日志零匹配 "deep-linked app detected"，淘宝深链任务干等 90s 实证）。
        // isAdPlayingReal() 只检测真实广告 Activity/SDK 包名，深链目标 App
        // （taobao/tongyi/leopard）不在广告关键词中，正确放行进本分支。
        // build764: interactiveAdJumpPending/watchingAdFromDeepLinkTask=true 时跳过
        // isAdActivity()/isAdPlayingReal() 检查——跳转预期已确认，但 activity 名不可靠：
        // debug_test_20260830_083111.log (build763) 08:29:13 互动广告"点击跳转拿奖励"点击后
        // pkg 已变成 com.antgroup.leopard.android（蚂蚁落地App），currentActivityName 却仍停留在
        // com.kwad.sdk.api.proxy.app.KsRewardVideoActivity（TYPE_WINDOW_STATE_CHANGED 事件跟踪
        // 滞留/落地页复用快手广告组件）→ `!isAdActivity()` 恒 false → 深链分支仍被挡死
        // → 25s 走 AD_ENDED→CLOSING_AD 坐标乱点→RETURNING pressBack 无效→forceKillApp UC。
        // 放宽后子分支用 pkg（可靠）精确分流，act 仅作普通广告场景的辅助判断。
        val jumpExpected = interactiveAdJumpPending || watchingAdFromDeepLinkTask
        if (elapsedMs >= 5000L && !service.isOnFarmPage() &&
            ((!service.isAdActivity() && !service.isAdPlayingReal()) || jumpExpected) &&
            (!service.isOnAbnormalPage() || interactiveAdJumpPending)) {
            val currentPkg = service.getCurrentWindowPackage()
            if (currentPkg != null) {
                // build742: 已进入过其它App(deepLinkAppPkg!=null)后农场App自身又回到前台——
                // 深链任务自然完成返回，但 WebView 停在任务落地页（无农场关键词，isOnFarmPage=false）。
                // 视为任务完成，runDeepLinkReturnToFarm 先回农场主页（pressBack/深链重开）再开任务列表
                // build743: watchingAdFromDeepLinkTask=true（深链任务会话，5s内早期返回、
                // deepLinkAppPkg 尚未记录）也走此分支——否则下方会把农场包名误记为跳转App
                // build764: 去掉单独的 interactiveAdJumpPending——互动广告跳转按钮点击后
                // 可能还没跳出去（仍停在 UC 广告落地页，pkg=UC），必须 deepLinkAppPkg!=null
                // （真的离开过农场App又回来）才算"跳转完成返回"，防止误判任务完成提前退出。
                if (watchingAdPlatform != Platform.UNKNOWN &&
                    currentPkg in watchingAdPlatform.config.packageNames &&
                    (deepLinkAppPkg != null || watchingAdFromDeepLinkTask)) {
                    Log.i(TAG, "watchAd: back to farm app '$currentPkg' on task landing page (no farm keywords), deep-link task complete")
                    debugLog("watchAd: farm app back in foreground on task landing page, task complete, recovering to farm page")
                    deepLinkAppPkg = null  // 清除后已调度的切回定时器会自动取消
                    service.setAdMode(false)
                    collectedCount++
                    advanceTaskIndex()
                    runDeepLinkReturnToFarm(0)
                    return
                }
                // 首次检测到深链跳转：记录包名，等够任务停留时长后"保留现场切回农场 + kill 被拉起的 App"
                // build763: 排除农场平台自身包名——普通广告（非深链任务/非互动跳转）结束后
                // 广告 Activity 关闭、WebView 停在 UC 内广告落地页（pkg=UC 但 isOnFarmPage=false），
                // 不排除会把农场包名误记为深链跳转 App，空等 20s 停留后才切回。
                val isFarmPlatformPkg = watchingAdPlatform != Platform.UNKNOWN &&
                    currentPkg in watchingAdPlatform.config.packageNames
                if (deepLinkAppPkg == null && !isFarmPlatformPkg) {
                    deepLinkAppPkg = currentPkg
                    deepLinkEnterTimeMs = elapsedMs
                    val stayMs = deepLinkTaskStayMs
                    Log.i(TAG, "watchAd: entered deep-linked app '$currentPkg', will bring farm to front (preserve scene) after ${stayMs}ms")
                    debugLog("watchAd: deep-linked app '$currentPkg' detected, waiting ${stayMs}ms (task hint) then bringFarmAppToFront")
                    // build761: 千问对话任务——跳转千问App后在对话框发送"你吃饭了吗"
                    // （任务"打开千问发起对话"要求发起对话才算完成；广告跳转
                    //   interactiveAdJumpPending 场景不是对话任务，不触发）
                    if (currentPkg == QIANWEN_PKG && !interactiveAdJumpPending) {
                        debugLog("watchAd: entered Qianwen app (chat task), will type '你吃饭了吗' and send in 3s")
                        handler.postDelayed({
                            if (state == AutomationState.WATCHING_AD) runQianwenChat(0)
                        }, 3000L)
                    }
                    val jumpedPkg = currentPkg
                    handler.postDelayed({
                        if (state != AutomationState.WATCHING_AD) return@postDelayed
                        // 若已自然回到农场页（任务完成），取消切回
                        if (deepLinkAppPkg == null) {
                            debugLog("watchAd: deep-link app already returned, cancel scheduled bring-to-front")
                            return@postDelayed
                        }
                        Log.w(TAG, "watchAd: ${stayMs}ms elapsed, bringing farm to front (preserve scene) and killing '$jumpedPkg'")
                        debugLog("watchAd: bringFarmAppToFront (preserve scene) + killing '$jumpedPkg'")
                        service.setAdMode(false)
                        // 1. 保留现场切回农场（build760: 手势切换——底部上滑停顿开最近任务→
                        //    点农场App卡片切回，与真实用户切换一致，WebView 不重载不刷新；
                        //    手势发起失败 fallback moveTaskToFront，再失败 deep link 拉起）。
                        //    手势流程异步完成（最坏 ~4s：800ms 等最近任务 + 卡片重试 + 验证），
                        //    返回 true 只代表已发起，不能立即做 kill/页面检查等后续动作。
                        var broughtToFront = false
                        if (watchingAdPlatform != Platform.UNKNOWN) {
                            val brought = service.bringFarmAppToFront(watchingAdPlatform)
                            if (brought) {
                                broughtToFront = true
                            } else {
                                debugLog("watchAd: bringFarmAppToFront failed, fallback launchPlatformApp(deep link, killCurrentFirst=false)")
                                service.launchPlatformApp(watchingAdPlatform, killCurrentFirst = false)
                            }
                        }
                        val isFarmPkg = watchingAdPlatform != Platform.UNKNOWN &&
                            jumpedPkg in watchingAdPlatform.config.packageNames
                        deepLinkAppPkg = null
                        // 等够任务时长视为任务完成（同自然返回分支），计入收集数并推进任务
                        collectedCount++
                        advanceTaskIndex()
                        // build742: 保留现场切回后，WebView 可能停在任务落地页
                        // （点击"去完成"时 H5 先导航到落地页再拉起App）而非农场主页 →
                        // runDeepLinkReturnToFarm 确保回农场主页再开任务列表。
                        // build760: 手势切换是异步流程（滑动→最近任务→点卡片→验证，
                        // 最坏 800ms+5×500ms 重试+600ms 验证≈4s），期间不能：
                        //   a. 立即 kill 被拉起App（手势流程中它可能仍是前台/可见，
                        //      killBackgroundProcesses 无效且会打断最近任务界面）
                        //   b. 立即 runDeepLinkReturnToFarm（页面检查发现不在农场页会走
                        //      分支3 深链重开 → WebView 重载 = 用户要避免的刷新）
                        // 故 kill + deepLinkReturn 统一延迟 4s（手势全流程最坏时间）执行。
                        if (broughtToFront) {
                            handler.postDelayed({
                                if (state != AutomationState.WATCHING_AD) return@postDelayed
                                // kill 被拉起的 App（手势切换完成后它必已切到后台，
                                // killBackgroundProcesses 有效；跳过农场平台自身包名防误杀）
                                if (!isFarmPkg) {
                                    service.forceKillApp(jumpedPkg, pressBackFirst = false)
                                } else {
                                    debugLog("watchAd: jumped pkg '$jumpedPkg' is farm platform pkg, skip kill")
                                }
                                runDeepLinkReturnToFarm(0)
                            }, GESTURE_BRING_FRONT_SETTLE_MS)
                        } else {
                            // deep link 拉起路径（必然重新加载农场页）：立即 kill 被拉起App（已切后台）
                            if (!isFarmPkg) {
                                service.forceKillApp(jumpedPkg, pressBackFirst = false)
                            }
                            handler.postDelayed({
                                if (state == AutomationState.WATCHING_AD) {
                                    moveTo(AutomationState.OPENING_TASK_LIST)
                                    handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                                }
                            }, INTERVAL_PAGE_LOAD_MS)
                        }
                    }, stayMs)
                }
                // 已调度切回，继续轮询兜底（若任务自然完成返回农场，上方"returned to farm"分支会取消切回）
                // build764: deepLinkAppPkg==null 时（pkg=农场包名但从未跳转出去——
                // 互动广告跳转按钮点击后仍停在 UC 广告落地页，或普通广告结束后的 UC 内
                // H5 落地页），不算深链：不在此 return（否则空转到 90s 超时），
                // 掉出本分支继续正常广告流程（min wait / AD_ENDED 检测等）。
                if (deepLinkAppPkg != null) {
                    val remainMs = deepLinkTaskStayMs - (elapsedMs - deepLinkEnterTimeMs)
                    debugLog("watchAd: in deep-linked app '$currentPkg', bring-to-front scheduled in ${maxOf(remainMs, 0)}ms, polling as fallback")
                    handler.postDelayed({
                        if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                    }, adEndCheckIntervalMs)
                    return
                }
                debugLog("watchAd: pkg '$currentPkg' is farm platform pkg with no deep-link jump recorded, continue normal ad flow")
            }
        }

        // 最短等待时间未到，继续等待
        // 用户要求：太快退出可能获取不到肥料，必须等够页面提示的规定时间+缓冲
        // build723 修复（debug_test_20260811_085303.log, build722, 08:33:42-08:52:57）：
        //   08:33:42 快手 KsRewardVideoActivity 互动广告(摇一摇), scene=TRAP_INTERACTIVE, elapsed=0ms
        //   08:33:47 scene=AD_ENDED, elapsed=5000ms ← 广告已结束(无倒计时)
        //   08:33:47-08:52:57 (20分钟!) 无任何 watchAd debugLog,最终用户手动停止
        //   根因1(诊断盲区): elapsedMs < adMinDurationMs(12000ms) 时走此分支,但诊断日志条件
        //     `elapsedMs % 15000L < adEndCheckIntervalMs` 在 elapsed=5000/10000 时不满足(5000<5000=false),
        //     且 Log.d 不上传到 debug.log,导致大量轮询无日志,看起来像卡死。
        //   根因2(逻辑缺陷): scene=AD_ENDED 说明广告已结束,但仍按 adMinDurationMs 等待,
        //     不合理。广告已结束时应尽快进入广告结束检测(isAdEndedMultiSignal)并关闭。
        //   修复1: 诊断日志改为每次都输出(移除 % 15s 条件),确保 runWatchingAd 调度可追踪。
        //   修复2: scene=AD_ENDED 时跳过 min wait,直接进入广告结束检测。
        if (elapsedMs < adMinDurationMs && scene != PageScene.AD_ENDED) {
            // 诊断日志：每次都记录广告页面状态（build723: 移除 % 15s 条件,避免诊断盲区）
            val adPageType = service.getPageType()
            val adActivity = service.isAdActivity()
            val adPlaying = service.isAdPlaying()
            val adContent = service.isAdContentShown()
            val adTexts = service.collectAllTextSnapshot(maxCount = 8)
            debugLog("watchAd: waiting ${elapsedMs}ms/${adMinDurationMs}ms (min), pageType=$adPageType, adActivity=$adActivity, adPlaying=$adPlaying, adContent=$adContent, texts=$adTexts")
            handler.postDelayed({
                if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
            }, adEndCheckIntervalMs)
            return
        }

        // 最短等待时间已过，检测广告是否结束
        // 广告结束的标志：不再在广告Activity，或出现领取奖励按钮，或任务完成
        // 诊断日志：记录广告结束检测时的页面状态
        val adEndedPageType = service.getPageType()
        val adEndedActivity = service.isAdActivity()
        val adEndedPlaying = service.isAdPlaying()
        val adEndedContent = service.isAdContentShown()
        val adEndedTexts = service.collectAllTextSnapshot(maxCount = 8)
        debugLog("watchAd: checking ad end (${elapsedMs}ms/${adMaxDurationMs}ms), pageType=$adEndedPageType, adActivity=$adEndedActivity, adPlaying=$adEndedPlaying, adContent=$adEndedContent, texts=$adEndedTexts")

        // 多信号融合广告结束检测（优化方案）
        // 融合：任务完成页 + 广告Activity切换 + 领取奖励按钮 + 倒计时消失
        // 相比单一信号检测，准确率更高，能更早发现广告结束
        val adEnded = service.isAdEndedMultiSignal(prevAdHadCountdown)
        // 更新上一轮倒计时状态（供下一轮多信号检测用）
        prevAdHadCountdown = service.findAdDurationHint() > 0

        // build776 修复（debug_test_20260905_152919.log, build775, 15:28:19-15:29:16）:
        //   穿山甲"我要加速"跳淘宝停留10s后手势切回UC,广告Activity已自行结束落在
        //   农场页(集肥料可见)。但"我要加速"是非深链路径,没有任何出口清 adModeFlag →
        //   ① isAdPlaying()恒true → isAdEndedMultiSignal 信号2(!isAdPlaying)永不成立;
        //   ② isOnFarmPage() 被 adModeFlag 守卫强制 false → isRechargePage 农场守卫
        //      失效,农场页误判 TRAP_RECHARGE 误点"关闭"(1134,600),把"已完成"标记
        //      和集肥料按钮点没 → 信号1也失效;
        //   所有出口都要 adEnded=true → 死锁空转85s直到用户手动停止(差7s到90s超时)。
        //   修复:scene=AD_ENDED 且不在广告Activity 且农场内容已加载(hasFarmContentLoaded
        //   为纯文本检测,不受 adModeFlag 影响)→ 广告会话已结束落在农场页,
        //   清 adModeFlag 并退出(同任务完成出口,斩断死锁链)。
        if (!adEnded && scene == PageScene.AD_ENDED && !adEndedActivity) {
            val farmRoot = service.rootInActiveWindowSafe()
            if (farmRoot != null && service.hasFarmContentLoaded(farmRoot)) {
                Log.i(TAG, "watchAd: ad ended back on farm page (farm content loaded, breaking adModeFlag deadlock), exiting")
                debugLog("watchAd: scene=AD_ENDED+农场内容已加载(不受adModeFlag影响),广告会话已结束落在农场页,清adModeFlag退出")
                service.setAdMode(false)
                collectedCount++
                advanceTaskIndex()  // 多次任务重玩同一任务，否则前进到下一个
                handler.postDelayed({
                    moveTo(AutomationState.OPENING_TASK_LIST)
                    handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                }, INTERVAL_PAGE_LOAD_MS)
                return
            }
        }

        // build755 修复（debug_test_20260829_192115.log, build754, 19:14:51-19:16:34）:
        //   "去支付宝逛蚂蚁庄园"深链任务,19:14:40 奖励弹窗"恭喜获得奖励 UC元宝 180g饲料"
        //   自动领取,19:14:51 页面变"UC元宝、180g饲料已发放"——任务已成功;
        //   但蚂蚁庄园页面无"已完成"类文本,isTaskCompletePage()=false 不满足完成分支,
        //   AD_ENDED 干等轮询到 90s 超时才 CLOSING_AD(假关闭按钮+坐标盲点+RETURNING
        //   forceKill 又 20s),一次成功任务浪费约 2 分钟。
        //   修复:深链任务第三方App页面(非农场包前台)出现"已发放/领取成功"奖励到账文本
        //   时按任务完成处理,立即退出回农场。
        val nonFarmPkgForeground = run {
            val pkg = service.getCurrentWindowPackage()
            !pkg.isNullOrEmpty() && service.currentPlatformConfig().packageNames.none { it == pkg }
        }
        if (adEnded && nonFarmPkgForeground && service.hasRewardGrantedText()) {
            Log.i(TAG, "watchAd: reward granted text on non-farm app page (deep-link task done), exiting")
            debugLog("watchAd: 深链任务奖励已到账(已发放/领取成功文本),任务成功,立即退出不等超时")
            val closeBtn = service.findAdCloseButton(service.currentPlatformConfig().adCloseButtonTexts)
            val backIcon = service.findBackIcon()
            when {
                closeBtn != null -> { debugLog("watchAd: clicking close icon"); service.performClickSafe(closeBtn) }
                backIcon != null -> { debugLog("watchAd: clicking back icon"); service.performClickSafe(backIcon) }
                else -> { debugLog("watchAd: pressing back"); service.pressBack() }
            }
            service.setAdMode(false)
            collectedCount++
            advanceTaskIndex()
            handler.postDelayed({
                if (!service.isOnFarmPage()) service.pressBack()
                handler.postDelayed({
                    moveTo(AutomationState.OPENING_TASK_LIST)
                    handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                }, INTERVAL_CLICK_MS)
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        if (adEnded && service.isTaskCompletePage()) {
            Log.i(TAG, "watchAd: task complete page detected (multi-signal), exiting")
            debugLog("watchAd: task complete (multi-signal), exiting via close/back icon")
            // 优先点右上角关闭按钮（游戏/广告退出，含平台特有关闭文本）
            val closeBtn = service.findAdCloseButton(service.currentPlatformConfig().adCloseButtonTexts)
            val backIcon = service.findBackIcon()
            when {
                closeBtn != null -> { debugLog("watchAd: clicking close icon"); service.performClickSafe(closeBtn) }
                backIcon != null -> { debugLog("watchAd: clicking back icon"); service.performClickSafe(backIcon) }
                else -> { debugLog("watchAd: pressing back"); service.pressBack() }
            }
            service.setAdMode(false)
            collectedCount++
            advanceTaskIndex()  // 多次任务重玩同一任务，否则前进到下一个
            handler.postDelayed({
                if (!service.isOnFarmPage()) service.pressBack()
                handler.postDelayed({
                    moveTo(AutomationState.OPENING_TASK_LIST)
                    handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                }, INTERVAL_CLICK_MS)
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        if (adEnded && !service.isAdActivity() && !service.isAdPlaying()) {
            // 广告结束后检查是否进入了交易页面
            if (service.isOnAbnormalPage()) {
                Log.i(TAG, "watchAd: abnormal/trading page after ad, exiting immediately")
                debugLog("watchAd: abnormal page after ad, pressing back")
                service.pressBack()
                service.setAdMode(false)
                currentTaskIndex++
                handler.postDelayed({
                    if (!service.isOnFarmPage()) service.pressBack()
                    handler.postDelayed({
                        moveTo(AutomationState.OPENING_TASK_LIST)
                        handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                    }, INTERVAL_CLICK_MS)
                }, INTERVAL_PAGE_LOAD_MS)
                return
            }

            // 广告结束后优先检测领取奖励按钮（节点查找，免费精确）
            // 用户要求：非滑动广告页出现肥料图标后，也需等"已完成"等标志再退出
            // 因此点击领取按钮后不立即退出，继续轮询等待 isTaskCompletePage() 检测到"已完成"标志
            val claimBtn = service.findClaimRewardButton()
            if (claimBtn != null) {
                Log.i(TAG, "watchAd: claim button found after ad finished, clicking and waiting for '已完成' marker")
                debugLog("watchAd: clicking claim reward button, will wait for '已完成' marker before exiting")
                service.performClickSafe(claimBtn)
                // 不立即退出：继续轮询，由 isTaskCompletePage() 检测到"全部完成/已完成"后退出
                handler.postDelayed({
                    if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                }, adEndCheckIntervalMs)
                return
            }

            Log.i(TAG, "watchAd: ad finished (${elapsedMs}ms, multi-signal), closing")
            moveTo(AutomationState.CLOSING_AD)
            handler.postDelayed({ runClosingAd(strategy = 0) }, INTERVAL_CLICK_MS)
            return
        }

        // 多信号检测到广告结束（倒计时消失等弱信号），但仍在广告 Activity
        // 尝试找领取奖励按钮，找到则点击，否则进入关闭流程
        if (adEnded) {
            // build720 修复：如果"领取成功"等已领取标志已显示,奖励已发放,
            // 不需要再点领取按钮(会误点"领取成功"的可点击父节点导致循环),
            // 直接进入 CLOSING_AD 关闭广告退出。
            val root = service.getRootInFarmApp()
            val alreadyClaimed = root != null && run {
                val texts = service.collectAllText(root)
                texts.any {
                    it.contains("领取成功") || it.contains("奖励已到账") ||
                    it.contains("奖励已发放") || it.contains("已领取奖励") ||
                    it.contains("肥料已到账") || it.contains("肥料已发放")
                }
            }
            if (alreadyClaimed) {
                Log.i(TAG, "watchAd: ad ended, reward already claimed (领取成功), entering CLOSING_AD")
                debugLog("watchAd: ad ended, reward already claimed, skip claim button, entering CLOSING_AD")
                moveTo(AutomationState.CLOSING_AD)
                handler.postDelayed({ runClosingAd(strategy = 0) }, INTERVAL_CLICK_MS)
                return
            }
            val claimBtn = service.findClaimRewardButton()
            if (claimBtn != null) {
                // build726 修复（debug_test_20260816_161405.log, build725, 16:12:51-16:13:22）：
                //   穿山甲TTRewardVideoActivity "可立即领奖"按钮 bounds=[0,121][0,121] (零尺寸退化节点),
                //   performClickSafe ACTION_CLICK failed,dispatchGesture 用 ancestor bounds 计算坐标
                //   (599.5,1391.5) 也不是实际按钮位置,点击无效。16:12:52-16:13:22 反复点击7次全失败,
                //   16:13:22 广告自动结束检测到"领取成功"才 CLOSING_AD。中间31秒无效点击浪费时间。
                //   根因:穿山甲体验类广告的"可立即领奖"是 WebView 内零尺寸文本节点,无法通过无障碍点击。
                //   修复:检测到零尺寸按钮(width<=1||height<=1)时跳过点击,只等待广告自动结束
                //   (isAdEndedMultiSignal 会检测"领取成功",或 max=90000ms 超时进入 CLOSING_AD)。
                val rect = android.graphics.Rect()
                claimBtn.getBoundsInScreen(rect)
                val isZeroSize = rect.width() <= 1 || rect.height() <= 1
                if (isZeroSize) {
                    Log.w(TAG, "watchAd: claim button is zero-size (bounds=$rect), skip click, waiting for ad auto-end")
                    debugLog("watchAd: claim button zero-size (bounds=$rect), skip click (穿山甲零尺寸节点无法点击,等待广告自动结束)")
                    handler.postDelayed({
                        if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                    }, adEndCheckIntervalMs)
                    return
                }
                Log.i(TAG, "watchAd: multi-signal ad ended, claim button found, clicking")
                debugLog("watchAd: multi-signal ad ended (countdown disappeared), clicking claim button")
                service.performClickSafe(claimBtn)
                handler.postDelayed({
                    if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
                }, adEndCheckIntervalMs)
                return
            }
            // 倒计时消失但无领取按钮，进入关闭流程
            Log.i(TAG, "watchAd: multi-signal ad ended (no claim button), closing")
            debugLog("watchAd: multi-signal ad ended, no claim button, entering CLOSING_AD")
            moveTo(AutomationState.CLOSING_AD)
            handler.postDelayed({ runClosingAd(strategy = 0) }, INTERVAL_CLICK_MS)
            return
        }

        // 检测是否有领取奖励按钮（广告仍在播放时出现）
        // 用户要求：出现肥料图标后也需等"已完成"等标志再退出，因此不立即关闭，继续等待
        val claimButton = service.findClaimRewardButton()
        if (claimButton != null) {
            Log.i(TAG, "watchAd: claim button found while ad playing, waiting for '已完成' marker")
            debugLog("watchAd: claim button found but waiting for '已完成' marker before exiting")
            handler.postDelayed({
                if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
            }, adEndCheckIntervalMs)
            return
        }

        // build681 修复（debug_test_20260801_152504.log, build680, 15:24:54-15:24:58）：
        //   15:24:54.320 watchAd: scene=AD_ENDED, elapsed=25000ms/90000ms
        //   15:24:54.372 watchAd: checking ad end, pageType=unknown(no_root), adActivity=true, adPlaying=true, texts=[]
        //   15:24:58.475 state: WATCHING_AD -> STOPPING (用户手动停止,卡死 4 秒)
        // 根因：广告结束后 root 暂时不可用（no_root），isAdEndedMultiSignal 无法执行信号 3/4
        //   （findClaimRewardButton、collectAllText 都需要 root）。
        //   但 isAdActivity()/isAdPlaying() 基于 Activity 名仍返回 true（KsRewardVideoActivity）,
        //   导致 adEnded=false,走到"继续等待"分支,bot 卡死直到用户手动停止。
        // 修复：当 pageType=unknown(no_root) 且 adActivity=true 且 elapsedMs >= adMinDurationMs + 10s buffer 时,
        //   主动进入 CLOSING_AD 关闭广告。no_root 通常意味着广告页面正在切换（结束动画/弹窗加载中）,
        //   此时主动尝试关闭比无脑等待更合理（CLOSING_AD 会找关闭按钮,找不到再 pressBack 兜底）。
        if (adEndedPageType == "unknown(no_root)" && adEndedActivity &&
            elapsedMs >= adMinDurationMs + 10_000L) {
            Log.i(TAG, "watchAd: no_root + adActivity + elapsed ${elapsedMs}ms >= min+10s, entering CLOSING_AD (avoid stall)")
            debugLog("watchAd: no_root with adActivity and elapsed ${elapsedMs}ms >= ${adMinDurationMs + 10_000L}ms, proactively closing ad to avoid stall")
            service.setAdMode(false)
            moveTo(AutomationState.CLOSING_AD)
            handler.postDelayed({ runClosingAd(strategy = 0) }, INTERVAL_CLICK_MS)
            return
        }

        // 继续等待
        Log.d(TAG, "watchAd: still playing (${elapsedMs}ms/${adMaxDurationMs}ms)")

        // build529（用户要求"全部实现"）：AI 视觉识别环形进度条（节流到 20s 一次）
        // 用途：广告页面有时无可读倒计时文本，截图交 GLM-4.6V-Flash 识别进度环填充比例，
        // 输出 percent/seconds_remaining 到日志，便于诊断"广告还要等多久"。
        // 不主动触发提前退出（AI 判断存在误差），仅作信息补充；退出仍由上方既有条件负责。
        if (elapsedMs >= adMinDurationMs &&
            state == AutomationState.WATCHING_AD &&
            System.currentTimeMillis() - lastAiProgressCheckMs >= AI_PROGRESS_CHECK_INTERVAL_MS) {
            lastAiProgressCheckMs = System.currentTimeMillis()
            val appContext = service.applicationContext
            val snapshotElapsed = elapsedMs
            Thread {
                val bitmap = service.takeScreenshotBitmap()
                if (bitmap == null) {
                    Log.w(TAG, "watchAd: AI progress screenshot unavailable")
                    return@Thread
                }
                try {
                    val sceneCtx = "ad watching (elapsed=${snapshotElapsed}ms, " +
                        "min=${adMinDurationMs}ms, max=${adMaxDurationMs}ms)"
                    val result = AiVisionClient.recognizeProgressFromScreenshot(appContext, bitmap, sceneCtx)
                    bitmap.recycle()
                    if (result == null) {
                        Log.w(TAG, "watchAd: AI progress returned null")
                    } else {
                        Log.i(TAG, "watchAd: AI progress percent=${result.percent}%, " +
                            "secondsRemaining=${result.secondsRemaining}s, " +
                            "hasBar=${result.hasProgressBar}, reason='${result.reason.take(80)}'")
                        debugLog("watchAd: AI progress percent=${result.percent}%, " +
                            "remaining=${result.secondsRemaining}s, hasBar=${result.hasProgressBar}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "watchAd: AI progress exception: ${e.message}", e)
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }.start()
        }

        handler.postDelayed({
            if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + adEndCheckIntervalMs)
        }, adEndCheckIntervalMs)
    }

    // ============== build742: 深链任务完成后确保回到芭芭农场主页 ==============

    /**
     * build742: 判定当前页面是否"真"芭芭农场主页（区分农场主页 vs 深链任务落地页/任务开始页）
     *
     * 背景（用户反馈"任务完成切到任务开始的页面，不是切到芭芭农场页面"）：
     * 点击深链任务"去完成"时，农场 H5 先导航到任务落地页（任务开始页）再拉起其它App；
     * 任务完成保留现场切回后，WebView 停在落地页。落地页常含"任务完成"/"得肥料"等文案，
     * 会让 isOnFarmPage() 误判为 true（hasFarmContent 关键词过宽），导致 OPENING_TASK_LIST
     * 在落地页上找不到"去完成"按钮而走坐标兜底乱点。
     *
     * 判定标准：页面含"芭芭农场"标题 且 含农场核心元素（集肥料/施肥/换种/免费领水果）。
     * 落地页无种植页核心元素，不会误判。
     */
    private fun isOnRealFarmPageForDeepLinkReturn(service: FarmAccessibilityService): Boolean {
        val texts = service.collectAllTextSnapshot(maxCount = 300)
        val hasFarmTitle = texts.any { it.contains("芭芭农场") }
        val hasFarmCore = texts.any {
            it.contains("集肥料") || it.contains("施肥") || it.contains("换种") ||
                it.contains("免费领水果") || it.contains("领水果")
        }
        if (!hasFarmTitle || !hasFarmCore) {
            debugLog("isOnRealFarmPageForDeepLinkReturn: NO (hasFarmTitle=$hasFarmTitle, hasFarmCore=$hasFarmCore), sample=${texts.take(8)}")
        }
        return hasFarmTitle && hasFarmCore
    }

    /**
     * build742: 深链任务完成后确保回到芭芭农场主页（而非任务开始页/落地页）
     *
     * 调用时机：深链任务已计完成（collectedCount++/advanceTaskIndex 已执行），仍在 WATCHING_AD。
     * 处理流程：
     * 1. 已在农场主页（任务列表可见 或 农场标题+核心元素齐全）→ 进入 OPENING_TASK_LIST 继续下一任务
     * 2. 农场App在前台但停在任务落地页 → pressBack 回退（WebView 历史栈弹出落地页回到农场页，
     *    不重载、保留农场会话），最多 2 次
     * 3. 仍不在农场主页（或农场App不在前台）→ reopenFarmByDeepLink 深链重开农场页（不杀农场进程）
     */
    private fun runDeepLinkReturnToFarm(attempt: Int) {
        if (state != AutomationState.WATCHING_AD) return
        val service = getService() ?: run { stop(); return }

        // 1. 已回到真农场页（任务列表开着 或 农场标题+核心元素齐全）
        val taskListVisible = service.findGoCompleteButtons().isNotEmpty()
        if (taskListVisible || isOnRealFarmPageForDeepLinkReturn(service)) {
            debugLog("deepLinkReturn: on farm page now (taskListVisible=$taskListVisible), opening task list")
            handler.postDelayed({
                if (state == AutomationState.WATCHING_AD) {
                    moveTo(AutomationState.OPENING_TASK_LIST)
                    handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
                }
            }, INTERVAL_CLICK_MS)
            return
        }

        // 2. 农场App在前台但停在任务落地页：pressBack 弹出落地页（WebView 历史回退到农场页）
        if (attempt < 2 && service.isFarmAppInForeground()) {
            debugLog("deepLinkReturn: farm app foreground but on task landing page (attempt=$attempt), pressBack to pop it")
            service.pressBack()
            handler.postDelayed({
                if (state == AutomationState.WATCHING_AD) runDeepLinkReturnToFarm(attempt + 1)
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // build773 修复（debug_test_20260905_073855.log, build772, 07:37:22-07:37:23 竞态）：
        //   07:37:22.606 最近任务卡片点击成功（UC 切前台中）→ 07:37:23.109 本函数检查：
        //   UC 窗口切换/H5 首帧渲染未完成（sample=[]，isFarmAppInForeground=false）
        //   → 直接落分支3发深链（多窗口+1）→ 07:37:23.706 卡片切换成功日志才打出。
        //   即卡片切换其实成功了，只是检查太早。修复：农场App尚不在前台时给最多2次
        //   宽限重试（每次 INTERVAL_PAGE_LOAD_MS），让卡片切换/渲染落地后再判定；
        //   宽限耗尽仍不在前台（手势真失败）才落分支3发深链，行为与原逻辑一致仅多等 ~5s。
        if (!service.isFarmAppInForeground() && attempt < 2) {
            debugLog("deepLinkReturn: farm app not foreground yet (attempt=$attempt), grace retry in ${INTERVAL_PAGE_LOAD_MS}ms (recents card switch may still be landing)")
            handler.postDelayed({
                if (state == AutomationState.WATCHING_AD) runDeepLinkReturnToFarm(attempt + 1)
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 3. pressBack 无效或农场App不在前台：深链重开农场页（killCurrentFirst=false 保留农场进程）
        Log.w(TAG, "deepLinkReturn: still not on farm page after $attempt back attempts, reopening farm by deep link")
        debugLog("deepLinkReturn: pressBack x$attempt 无效, reopenFarmByDeepLink 重开农场页(killCurrentFirst=false)")
        if (watchingAdPlatform != Platform.UNKNOWN) {
            service.reopenFarmByDeepLink(watchingAdPlatform, killCurrentFirst = false)
        }
        handler.postDelayed({
            if (state == AutomationState.WATCHING_AD) {
                moveTo(AutomationState.OPENING_TASK_LIST)
                handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_PAGE_LOAD_MS)
            }
        }, INTERVAL_PAGE_LOAD_MS)
    }

    /**
     * build761: 千问对话任务——在千问App对话框输入"你吃饭了吗"并发送
     *
     * 用户需求："打开千问发起对话，得在千问对话框中发送个'你吃饭了吗'"——
     * 任务"打开千问发起对话"仅跳转到千问App不够，需在对话框实际发出消息才算发起对话。
     *
     * 流程（深链分支检测到 QIANWEN_PKG 后 3s 启动，等千问首页加载）：
     *   1. 找可编辑输入框（千问对话框）→ ACTION_SET_TEXT 填入"你吃饭了吗"
     *   2. 等 1.2s（输入文本后发送按钮才出现/启用）→ 找"发送"按钮点击
     *   3. 两步各最多重试 6 次（2s 间隔）——千问首次打开可能有隐私弹窗/登录页/加载慢
     *
     * 对话发起后不影响原有停留计时（deepLinkTaskStayMs 到点仍保留现场切回农场）。
     */
    private fun runQianwenChat(attempt: Int) {
        if (state != AutomationState.WATCHING_AD || qianwenChatSent) return
        val service = getService() ?: return
        if (attempt >= 6) {
            debugLog("runQianwenChat: giving up after $attempt attempts (input/send button not found, task may need manual completion)")
            return
        }
        // 第一步：填入文本
        if (!qianwenChatTyped) {
            val typed = service.qianwenTypeChatMessage("你吃饭了吗")
            if (typed) {
                qianwenChatTyped = true
                debugLog("runQianwenChat: typed '你吃饭了吗', waiting 1.2s for send button to appear, then click send")
                handler.postDelayed({
                    if (state == AutomationState.WATCHING_AD) runQianwenChat(attempt)
                }, 1200L)
            } else {
                val texts = service.collectAllTextSnapshot(maxCount = 8)
                debugLog("runQianwenChat: input not found (attempt=$attempt, texts=$texts), retrying in 2s")
                handler.postDelayed({
                    if (state == AutomationState.WATCHING_AD) runQianwenChat(attempt + 1)
                }, 2000L)
            }
            return
        }
        // 第二步：点击发送
        val sent = service.qianwenClickSendButton()
        if (sent) {
            qianwenChatSent = true
            debugLog("runQianwenChat: send button clicked, chat initiated with '你吃饭了吗' (task requirement fulfilled)")
        } else {
            debugLog("runQianwenChat: send button not found (attempt=$attempt), retrying in 2s")
            handler.postDelayed({
                if (state == AutomationState.WATCHING_AD) runQianwenChat(attempt + 1)
            }, 2000L)
        }
    }

    // ============== 阶段5: 关闭广告（多策略） ==============

    /**
     * 关闭广告阶段（多策略，基于v11 PC端ADB方案经验）
     * - 策略0：查找并点击"×"/"关闭"按钮节点
     * - 策略1：尝试坐标候选位置（右上角多个位置）
     * - 策略2：查找并点击"放弃奖励离开"对话框按钮
     * - 策略3：按返回键
     * - 策略4：查找并点击"领取奖励"按钮
     * - 每个策略尝试后检测是否成功关闭
     */
    private fun runClosingAd(strategy: Int) {
        if (state != AutomationState.CLOSING_AD) return
        val service = getService() ?: run { stop(); return }

        // 诊断日志：记录关闭广告时的页面状态，帮助定位"为什么关不掉"
        if (strategy == 0) {
            val closePageType = service.getPageType()
            val closeTexts = service.collectAllTextSnapshot(maxCount = 10)
            debugLog("closeAd: start closing, pageType=$closePageType, texts=$closeTexts")
        }
        Log.i(TAG, "closeAd: trying strategy #$strategy")

        // 陷阱防护：策略0 之前先检测是否误入广告主落地页
        // 落地页的"×"位置可能不同于广告关闭按钮，使用专门的 closeAdLandingPage（已内置诱导黑名单过滤）
        if (strategy == 0 && service.isAdLandingPage()) {
            Log.w(TAG, "closeAd: ad landing page detected during closing, using closeAdLandingPage")
            debugLog("closeAd: landing page trap detected, using specialized close")
            service.closeAdLandingPage()
            handler.postDelayed({
                if (state == AutomationState.CLOSING_AD) checkAdClosed(service, 0)
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // build750 修复（debug_test_20260829_154730.log, build749, 15:46:05-15:46:45 共40秒）：
        //   快手"扭一扭"陷阱互动广告（本轮文案"扭一扭或点击跳转详情页或第三方应用"+
        //   "点击跳转拿奖励"，是 build748 明确排除的跳转陷阱变体，无可点领取按钮）：
        //   CLOSING_AD 策略0 点'跳过'ACTION_CLICK success 但页面不关(15:46:07)，
        //   策略1 8坐标盲点无效(15:46:12)，策略3 pressBack 无效，
        //   RETURNING 温和退出3次(attempt 0-2)无效(15:46:30-15:46:45)，
        //   最终 attempt=3 forceKill UC+深链重开才退出(15:46:45-15:46:51,6秒生效)。
        //   多轮日志(build706/734/750)反复验证：此类广告对所有温和关闭手段免疫，
        //   forceKillApp(宿主)+reopenFarmByDeepLink 是唯一可靠退出手段。
        //   修复：CLOSING_AD 入口检测互动陷阱广告（无可领"立即获取"按钮、无下载按钮、
        //   非"肥料已发放"页）时，跳过全部温和策略(0-4)和 RETURNING 温和退出，
        //   直接 forceKill 宿主+深链重开+NAVIGATING（与 build734 RETURNING 兜底同款，
        //   该手段已验证有效），单次广告退出从 ~40s 缩短到 ~7s。
        //   安全性：有"立即获取"按钮（build748 可点变体）或下载按钮（穿山甲下载类）
        //   或肥料已发放页时不触发，保留原有流程。
        // build756 修复（debug_test_20260829_205529.log, build754, 20:53:43-20:54:42 共59s）：
        //   本检测原在 findAdInstallButton 诱导弹窗检测之后执行：快手"扭一扭"陷阱
        //   广告变体带"立即购买"电商CTA（广告创意按钮，同 build754 Fix B 的
        //   isRechargePage 误判同源问题），被 findAdInstallButton 误命中 → 走
        //   closeAdInstallPopup（广告页无"暂不下载"类关闭按钮可点）→ 策略1 八坐标
        //   盲点 → 盲点/角标点击触发广告跳转到淘宝（20:54:23 activeRootPkg=
        //   com.taobao.taobao TMSActivity）→ RETURNING pressBack×3 无效 → NAVIGATING
        //   深链重开，59s 才回农场；且该路径漏调 recordTrapAdExit（streak 停在 1）。
        //   修复：本检测移到诱导弹窗检测**之前**——强互动信号（摇一摇/扭一扭）+无
        //   立即获取/下载按钮的陷阱广告，无论含不含"立即购买"CTA 都直接 forceKill
        //   快速退出（~7s），recordTrapAdExit 正常计数。真安装弹窗（穿山甲下载类，
        //   无互动信号）不受影响，仍走原 closeAdInstallPopup 路径。
        if (strategy == 0 && !service.isFertilizerGrantedPage() &&
            service.isInteractiveAdPage() &&
            service.findInteractiveAdClickToClaimButton() == null &&
            service.findInteractiveAdDownloadButton() == null) {
            Log.w(TAG, "closeAd: interactive trap ad (no claim/download button), skipping gentle strategies, force killing host app")
            debugLog("closeAd: 互动陷阱广告(无立即获取/下载按钮),温和关闭已验证无效,直接forceKill宿主+深链重开")
            recordTrapAdExit()  // build754: 陷阱退出计数（连续无进展则跳过视频类任务）
            service.setAdMode(false)
            val farmPkgs = service.currentPlatformConfig().packageNames
            for (pkg in farmPkgs) {
                service.forceKillApp(pkg, pressBackFirst = false)
            }
            // build753: 杀宿主后 KEEP_SCREEN_ON 随进程死亡,若屏幕超时已到会立即熄灭进 AOD,
            // 无障碍读到 AOD 窗口导致后续 navigate/collectDirect 失明——先唤醒屏幕再深链重开
            service.ensureScreenOn()
            service.reopenFarmByDeepLink(killCurrentFirst = false)
            moveTo(AutomationState.NAVIGATING)
            handler.postDelayed({ runNavigating(0) }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 陷阱防护：策略0 之前先检测诱导弹窗（立即下载弹窗）
        // 若存在诱导弹窗，优先用 closeAdInstallPopup 关闭弹窗，再尝试常规关闭
        // build756: 本块移到上方互动陷阱快速退出检测之后（原在前）——广告 Activity 内
        // 的"立即购买"等电商CTA 是广告创意按钮非真安装弹窗，先被互动陷阱检测拦截
        if (strategy == 0 && service.findAdInstallButton() != null) {
            Log.w(TAG, "closeAd: install popup detected during closing, trying closeAdInstallPopup first")
            debugLog("closeAd: install popup trap detected, attempting closeAdInstallPopup")
            val closed = service.closeAdInstallPopup()
            handler.postDelayed({
                if (state == AutomationState.CLOSING_AD) {
                    // 弹窗关闭成功 → 继续常规策略0；失败 → 跳过策略0直接走坐标策略
                    if (closed) checkAdClosed(service, 0) else runClosingAd(1)
                }
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        when (strategy) {
            0 -> {
                // 策略0：查找并点击"×"/"关闭"按钮节点（优先平台特有关闭文本）
                val platformCloseTexts = service.currentPlatformConfig().adCloseButtonTexts
                // build707 修复（debug_test_20260803_203219.log, build705, 20:31:17）:
                //   快手"扭一扭"互动广告被 build706 正确识别为 TRAP_INTERACTIVE,
                //   但 CLOSING_AD 策略0 调用 findAdCloseButton 时 scene=TRAP_INTERACTIVE
                //   不在白名单(AD_PLAYING/AD_ENDED/REWARD_POPUP/SIGN_IN/GENERIC_POPUP)中,
                //   返回 null,跳过策略0。坐标点击右上角也无效,所有策略失败 → RETURNING,
                //   forceKillApp(UC) 后 deep link 拉起失败,卡死在 launcher,用户手动停止。
                //   修复:CLOSING_AD 已确认在关闭流程中,传入 enforceSceneWhitelist=false,
                //   允许在 TRAP_INTERACTIVE 场景查找关闭按钮(如"跳过"按钮)。
                //   安全性:CLOSING_AD 是主动关闭流程,不会误点陷阱(已有 isFakeCloseButton 检测)。
                val closeBtn = service.findAdCloseButton(platformCloseTexts, enforceSceneWhitelist = false)
                if (closeBtn != null) {
                    // 虚假关闭按钮检测：尺寸过大或位置居中的"关闭"可能是诱导跳转
                    if (service.isFakeCloseButton(closeBtn)) {
                        Log.w(TAG, "closeAd: fake close button detected (size/position suspicious), skipping strategy 0")
                        debugLog("closeAd: fake close button (too large or centered), trying next strategy")
                        runClosingAd(1)
                        return
                    }
                    Log.i(TAG, "closeAd: found close button, clicking")
                    service.performClickSafe(closeBtn)
                } else {
                    // 未找到关闭按钮节点，进入下一策略
                    debugLog("closeAd: close button node not found, trying next strategy")
                    runClosingAd(1)
                    return
                }
            }
            1 -> {
                // 策略1：尝试坐标候选位置
                val adCloseList = adCloseCandidates(service)
                for ((index, candidate) in adCloseList.withIndex()) {
                    val (xRatio, yRatio) = candidate
                    Log.d(TAG, "closeAd: trying coordinate #$index ($xRatio, $yRatio)")
                    clickAtRatio(service, xRatio, yRatio, "ad-close-$index")
                }
            }
            2 -> {
                // 策略2：查找并点击"放弃奖励离开"对话框按钮
                val abandonBtn = service.findAbandonRewardButton()
                if (abandonBtn != null) {
                    Log.i(TAG, "closeAd: found abandon reward button, clicking")
                    service.performClickSafe(abandonBtn)
                } else {
                    runClosingAd(3)
                    return
                }
            }
            3 -> {
                // 策略3：按返回键
                Log.i(TAG, "closeAd: pressing back")
                service.pressBack()
            }
            4 -> {
                // 策略4：查找并点击"领取奖励"按钮
                val claimBtn = service.findClaimRewardButton()
                if (claimBtn != null) {
                    Log.i(TAG, "closeAd: found claim button, clicking")
                    service.performClickSafe(claimBtn)
                }
            }
            else -> {
                // 所有策略都失败，清除广告标志，进入返回阶段
                Log.w(TAG, "closeAd: all strategies failed, clearing ad mode")
                service.setAdMode(false)
                moveTo(AutomationState.RETURNING)
                handler.postDelayed({ runReturning(attempt = 0) }, INTERVAL_CLICK_MS)
                return
            }
        }

        // 检测是否成功关闭广告
        handler.postDelayed({
            if (state == AutomationState.CLOSING_AD) checkAdClosed(service, strategy)
        }, INTERVAL_PAGE_LOAD_MS)
    }

    /** 检查广告是否已关闭 */
    private fun checkAdClosed(service: FarmAccessibilityService, lastStrategy: Int) {
        if (state != AutomationState.CLOSING_AD) return

        // 优先检测"肥料已发放"提示页：广告结束后弹出的奖励到账提示
        // 出现此提示说明广告已结束、肥料已到账，直接回芭芭农场主页
        if (service.isFertilizerGrantedPage()) {
            Log.i(TAG, "closeAd: fertilizer granted page detected, ad finished")
            service.setAdMode(false)
            collectedCount++
            Log.i(TAG, "=== FERTILIZER COLLECTED! (total: $collectedCount) ===")
            moveTo(AutomationState.RETURNING)
            handler.postDelayed({ runReturning(attempt = 0) }, INTERVAL_CLICK_MS)
            return
        }

        // 广告已关闭
        if (!service.isAdActivity() && !service.isAdPlaying()) {
            Log.i(TAG, "closeAd: ad closed successfully (strategy #$lastStrategy)")
            service.setAdMode(false)
            // 肥料收集成功
            collectedCount++
            Log.i(TAG, "=== FERTILIZER COLLECTED! (total: $collectedCount) ===")
            moveTo(AutomationState.RETURNING)
            handler.postDelayed({ runReturning(attempt = 0) }, INTERVAL_CLICK_MS)
            return
        }

        // 广告仍在，尝试下一个策略
        val nextStrategy = lastStrategy + 1
        if (nextStrategy <= 4) {
            Log.d(TAG, "closeAd: ad still playing, trying strategy #$nextStrategy")
            runClosingAd(nextStrategy)
        } else {
            // 所有策略都失败，清除广告标志，进入返回阶段
            Log.w(TAG, "closeAd: all strategies failed, clearing ad mode")
            service.setAdMode(false)
            moveTo(AutomationState.RETURNING)
            handler.postDelayed({ runReturning(attempt = 0) }, INTERVAL_CLICK_MS)
        }
    }

    // ============== 阶段6: 从广告返回任务列表 ==============

    /**
     * 返回阶段：从广告返回任务列表
     * - 用户要求：智能地适应退回按钮位置，不要只硬编码一个位置
     * - 交替使用系统返回键和多个候选退回按钮位置
     * - 回到任务列表后继续下一个任务
     */
    private fun runReturning(attempt: Int) {
        if (state != AutomationState.RETURNING) return
        val service = getService() ?: run { stop(); return }

        if (attempt == 0) {
            logPageSnapshot(service, "return-start")
            // build710 修复（debug_test_20260808_064321.log, build708, 06:43:10-06:43:17）:
            //   快手"扭一扭"互动广告 CLOSING_AD 所有策略(0-4)失败后进入 RETURNING。
            //   attempt=0 直接调用 reopenFarmByDeepLink() → forceKillApp(UC) → UC 被杀,
            //   deep link 拉起 UC 浏览器失败(可能需要浏览器进程已存在),
            //   卡在 launcher(com.hihonor.android.launcher),用户手动停止。
            //   修复:RETURNING attempt=0 时,如果仍在广告 Activity 中(isAdActivity()=true),
            //   不用 reopenFarmByDeepLink(会杀 UC),改为 pressBack 退出广告 Activity。
            //   pressBack 对快手广告通常会弹出"确认要离开吗?"弹窗,
            //   下一轮 RETURNING 会通过 findAbandonRewardButton 点击"放弃奖励离开"退出。
            //   只有不在广告 Activity 时才用 reopenFarmByDeepLink 重开农场。
            if (service.isAdActivity()) {
                Log.i(TAG, "return: still in ad activity, pressing back to exit ad (not using deepLink to avoid killing UC)")
                debugLog("return: ad activity detected, pressBack instead of reopenFarmByDeepLink to preserve UC process")
                service.pressBack()
                handler.postDelayed({
                    if (state == AutomationState.RETURNING) runReturning(attempt + 1)
                }, INTERVAL_PAGE_LOAD_MS)
                return
            }
            // build765 修复（debug_test_20260830_091342.log, build764, 09:12:33-09:12:46）:
            //   汇川广告点关闭图标退出/"放弃奖励离开"后, UC 浏览器已直接回到农场页
            //   (return-start snapshot onFarm=true, act=InnerUCMobile, 农场任务文本可见),
            //   但 attempt=0 无条件 reopenFarmByDeepLink → HOME+forceKillApp(UC)+深链重开
            //   → NAVIGATING 重新等农场 H5 加载, 每轮广告 RETURNING 多花 ~13s
            //   (本日志 2 轮共 ~26s, 约占全程 1/5; 且反复 kill UC 加剧"多窗口"标签页累积)。
            //   修复: attempt=0 先查 isOnFarmPage()——已在农场页则复用当前页面直接进
            //   COLLECTING_DIRECT(与 NAVIGATING 成功后的下一状态完全一致, collectDirect
            //   的 same-as-last 守卫/OPENING_TASK_LIST 的任务重扫照常生效), 不杀进程不重开。
            //   安全性: isOnFarmPage 内部含农场内容校验(广告落地页/新标签页不会误判),
            //   且 adModeFlag 未清时返回 false → 自动落回下方原 kill+重开路径;
            //   不在农场页(异常页/其他App)时行为与原来完全一致。
            if (service.isOnFarmPage()) {
                Log.i(TAG, "return: already on farm page, skip kill+relaunch, reusing current page")
                debugLog("return: already on farm page (onFarm=true), skip reopenFarmByDeepLink(kill), reuse page -> COLLECTING_DIRECT")
                moveTo(AutomationState.COLLECTING_DIRECT)
                handler.postDelayed({ runCollectingDirect(attempt = 0) }, INTERVAL_CLICK_MS)
                return
            }
            // build767 修复（用户需求："底部手指按住，往上滑动，切换到uc浏览器，不要触发浏览器刷新"）：
            //   互动广告"点击跳转拿奖励"拉起第三方App后，UC 在后台活着且多半停在农场页/广告页。
            //   此处 kill+深链重开会触发 UC 重新加载农场页（浏览器刷新）且新开标签页；
            //   优先用最近任务手势切换（底部按住上滑→点UC卡片）恢复原页面，不刷新不新开标签。
            //   5s后重跑 RETURNING attempt=0：切回成功且在农场页 → 走上方 build765 复用路径；
            //   UC 已前台但不在农场页 → 手势不适用（已在前台），落回 kill+深链原路径；
            //   切换失败（仍第三方App）→ 10s防重内不再发起，落回 kill+深链原路径。
            if (service.tryGestureSwitchToFarmApp(service.currentPlatform)) {
                Log.i(TAG, "return: gesture switch to farm app initiated (no reload), retry in 5s")
                debugLog("return: farm app alive in background, bottom-swipe-up-hold -> recents -> click card, retry verify in 5s")
                handler.postDelayed({
                    if (state == AutomationState.RETURNING) runReturning(0)
                }, INTERVAL_PAGE_LOAD_MS)
                return
            }
            // build775 修复（debug_test_20260905_111247.log, build773, 10:52:56-10:53:52 连锁）：
            //   互动广告落 launcher → 手势切卡 UC 成功(10:52:57.1) → 1.3s 后本函数判定
            //   UC 前台但 onFarm=false(多窗口 13 标签当前标签非农场页/页面未渲染完) →
            //   reopenFarmByDeepLink 默认 killCurrentFirst=true → HOME+kill UC+深链冷启动,
            //   UC 冷启动 ~1 分钟停在 launcher,期间无真实触摸屏幕超时熄灭 → 锁屏死循环 8.5 分钟。
            //   修复:UC 已在前台但 onFarm=false 时,先等 2.5s 让页面渲染(最多 2 次),
            //   仍不行则 reopenFarmByDeepLink(killCurrentFirst=false) 不杀进程发深链
            //   (UC 活着时深链直接路由农场,无冷启动;标签累积由 closeUcExtraTabsIfNeeded 兜底)。
            if (service.isFarmAppInForeground() && !service.isOnFarmPage()) {
                if (returnForegroundWaitCount < 2) {
                    returnForegroundWaitCount++
                    Log.i(TAG, "return: farm app foreground but onFarm=false, wait 2.5s for render (#$returnForegroundWaitCount, avoid kill)")
                    debugLog("return: 农场App已前台但非农场页(标签错/渲染中),等2.5s重试#$returnForegroundWaitCount(不kill)")
                    handler.postDelayed({
                        if (state == AutomationState.RETURNING) runReturning(0)
                    }, 2500L)
                    return
                }
                returnForegroundWaitCount = 0
                Log.i(TAG, "return: farm app foreground but still not farm page after waits, deep link WITHOUT kill")
                debugLog("return: 前台等待仍非农场页,reopenFarmByDeepLink(killCurrentFirst=false)不杀进程发深链")
                if (service.reopenFarmByDeepLink(killCurrentFirst = false)) {
                    moveTo(AutomationState.NAVIGATING)
                    handler.postDelayed({ runNavigating(0) }, INTERVAL_PAGE_LOAD_MS)
                    return
                }
            }
            returnForegroundWaitCount = 0
            // 优先用 deep link 重开农场主页（等同从桌面快捷方式进入），替代按返回键逐步退回
            // 成功后进入 NAVIGATING 等待页面加载，再重新走 COLLECTING_DIRECT → OPENING_TASK_LIST
            if (service.reopenFarmByDeepLink()) {
                Log.i(TAG, "return: reopened farm by deep link, switching to NAVIGATING")
                moveTo(AutomationState.NAVIGATING)
                handler.postDelayed({ runNavigating(0) }, INTERVAL_PAGE_LOAD_MS)
                return
            }
            // 无 deep link 或重开失败，走原有按返回键逐步退回逻辑
            Log.i(TAG, "return: no deep link, fallback to back-key return")
        }

        // 检测异常页面（交易页面等），按返回退出
        if (service.isOnAbnormalPage()) {
            debugLog("return: abnormal/trading page detected, pressing back")
            service.pressBack()
            handler.postDelayed({
                if (state == AutomationState.RETURNING) runReturning(attempt + 1)
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 已回到农场页，检查是否在任务列表
        if (service.isOnFarmPage()) {
            // 查找任务按钮，确认是否在任务列表
            val buttons = service.findGoCompleteButtons()
            if (buttons.isNotEmpty()) {
                Log.i(TAG, "return: back on task list with ${buttons.size} tasks, next task")
                taskButtons = buttons
                advanceTaskIndex()  // 多次任务重玩同一任务，否则前进到下一个
                noProgressRounds = 0
                moveTo(AutomationState.PROCESSING_TASK)
                handler.postDelayed({ runProcessingTask(0) }, INTERVAL_CLICK_MS)
                return
            }

            // 在农场页但不在任务列表，需要重新打开任务列表
            Log.i(TAG, "return: on farm page but not task list, opening task list")
            moveTo(AutomationState.OPENING_TASK_LIST)
            handler.postDelayed({ runOpeningTaskList(0) }, INTERVAL_CLICK_MS)
            return
        }

        // build734 修复（debug_test_20260816_194357.log, build732, 19:43:15-19:43:55）：
        //   快手"扭一扭"互动广告 end-card 对所有温和退出手段免疫:
        //   CLOSING_AD 点'跳过'成功但页面不关(19:43:18), 8个坐标关闭点击无效,
        //   RETURNING pressBack 无效(无'确认要离开吗'弹窗), back-1/back-2 坐标点击无效。
        //   直到用户手动停止(19:43:55)。
        //   而本日志首个广告 TRAP_RECHARGE 兜底(19:42:32)证明:
        //   forceKillApp(宿主)+reopenFarmByDeepLink 是最可靠退出手段,杀后重开成功回农场。
        //   修复: RETURNING 中 attempt>=3(温和退出已试 pressBack+2坐标,约15s)且仍在广告Activity
        //   → forceKillApp + 重开农场 + NAVIGATING(与 build732 TRAP_RECHARGE 兜底同款)。
        if (attempt >= 3 && service.isAdActivity()) {
            Log.w(TAG, "return: still in ad activity after $attempt gentle attempts, force killing host app and relaunching farm")
            debugLog("return: 广告Activity温和退出${attempt}次无效, forceKillApp杀宿主+重开农场兜底")
            val farmPkgs = service.currentPlatformConfig().packageNames
            for (pkg in farmPkgs) {
                service.forceKillApp(pkg, pressBackFirst = false)
            }
            service.reopenFarmByDeepLink(killCurrentFirst = false)
            moveTo(AutomationState.NAVIGATING)
            handler.postDelayed({ runNavigating(0) }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 尝试次数超限
        if (attempt >= MAX_RETURN_ATTEMPTS) {
            Log.w(TAG, "return: failed after $attempt attempts, re-navigating")
            moveTo(AutomationState.NAVIGATING)
            handler.postDelayed({ runNavigating(0) }, INTERVAL_CLICK_MS)
            return
        }

        // 智能选择退回方式：交替使用系统返回键和候选位置
        val backList = backButtonCandidates(service)
        val candidateIndex = attempt % (backList.size + 1)
        if (candidateIndex == backList.size) {
            Log.i(TAG, "return: pressing system back (attempt ${attempt + 1})")
            service.pressBack()
        } else {
            val (xRatio, yRatio) = backList[candidateIndex]
            Log.i(TAG, "return: clicking back button #$candidateIndex (attempt ${attempt + 1})")
            clickAtRatio(service, xRatio, yRatio, "back-$candidateIndex")
        }

        handler.postDelayed({
            if (state == AutomationState.RETURNING) runReturning(attempt + 1)
        }, INTERVAL_PAGE_LOAD_MS)
    }

    // ============== 阶段7: 施肥 ==============

    /**
     * 施肥阶段：所有肥料收集完后，点击施肥按钮
     *
     * 流程：
     * 1. 先关闭任务列表（按返回键或点击关闭，回到农场主页）
     * 2. 在农场主页找"施肥"按钮并点击
     * 3. 重复点击施肥，直到没有肥料可施或达到最大次数
     *
     * 用户要求："先获取完所有的肥料后，再来施肥"
     */
    private fun runFertilizing(clickCount: Int) {
        if (state != AutomationState.FERTILIZING) return
        val service = getService() ?: run { stop(); return }

        if (clickCount == 0) {
            logPageSnapshot(service, "fertilize-start")
            // build548：dump 所有 clickable 节点，用于诊断施肥按钮真实坐标
            // 历史问题：H5 未暴露施肥按钮文本，findFertilizeButton 找不到；
            // 只能从 dump 里反推真实施肥按钮坐标，下次根据日志修正坐标兜底
            service.dumpClickableNodes("fertilize-start")
        }

        // 检测异常页面（交易页面等），按返回退出并重新导航
        if (service.isOnAbnormalPage()) {
            debugLog("fertilize: abnormal/trading page detected, pressing back and re-navigating")
            service.pressBack()
            moveTo(AutomationState.NAVIGATING)
            handler.postDelayed({ runNavigating(0) }, INTERVAL_PAGE_LOAD_MS)
            return
        }

        // 防止无限点击
        if (clickCount >= MAX_FERTILIZE_CLICKS) {
            Log.i(TAG, "fertilize: reached max clicks ($MAX_FERTILIZE_CLICKS), done")
            moveTo(AutomationState.WAITING)
            handler.postDelayed({ startNextRound() }, INTERVAL_WAIT_MS)
            return
        }

        // 第一步：关闭任务列表回到农场主页（仅第一次）
        if (clickCount == 0) {
            // build553 修复（用户反馈"'施肥'按钮就在芭芭农场主页面上"）：
            // 历史问题（debug_test_20260719_140819.log, build552-f036a26）：
            //   14:07:12.119 fertilize: hint at (600.5, 1578.0), click 施肥 button below at (600.5, 1718.4)
            //   14:07:14.718 findRemainingFertilizerHintNode: no '还差X次领肥' hint node found
            //   14:07:14.720 state: FERTILIZING -> WAITING (误判施肥完成)
            //   14:07:22.950 [navigate-start] sample=[search_icon, 芭芭农场, 搜索...] (跳到搜索页)
            // 根因：FERTILIZING 从 PROCESSING_TASK 切入时，任务列表弹窗仍展开（dumpClickableNodes
            //   含"任务列表"/"去完成"/"已领取"/"更多肥料"等节点），弹窗遮住了主页"施肥"按钮。
            //   点击坐标 (600.5, 1718.4) 落在任务列表弹窗的空白区域，触发 H5 跳转到搜索页。
            //   build544 误以为 isOnFarmPage()=true 就不需要 pressBack，但任务列表弹窗还在屏幕上。
            //
            // 修复：FERTILIZING 进入时检测任务列表是否展开，若展开先 pressBack 关闭弹窗。
            // 任务列表展开特征：dumpClickableNodes 含"做任务集肥料"/"关闭做任务集肥料弹窗"/
            //   "去完成"等关键词（任务列表弹窗独有）。
            // build777 修复（debug_test_20260905_171627.log, 17:15:22）：
            //   支付宝农场【主页】右下角入口按钮文本就是"任务列表"（bounds=[949,1942][1175,2168]），
            //   旧关键词列表含"任务列表"→ isTaskListOpen 在主页误判=true → 找不到弹窗关闭按钮
            //   → pressBack 直接退出农场到支付宝首页（sample=[松开刷新, 深圳, 阴 31℃...]）
            //   → FERTILIZING→NAVIGATING 循环。
            //   修复：移除过宽的"任务列表"单词，改为强信号判定——
            //   弹窗标题/关闭按钮（"做任务集肥料"系）任一出现，或任务动作按钮
            //   （"去完成"/"去逛逛"/"去分享"/"去邀请"/"更多肥料"）出现≥2个才算弹窗展开。
            //   主页只有"任务列表"入口、无任务动作按钮，两种强信号都不命中。
            val taskListTitleKeywords = listOf("做任务集肥料", "关闭做任务集肥料弹窗")
            val taskListActionKeywords = listOf("去完成", "去逛逛", "去分享", "去邀请", "更多肥料")
            val root = service.getRootInFarmApp()
            val allText = if (root != null) service.collectAllText(root) else emptyList()
            val hasTaskListTitle = allText.any { text ->
                taskListTitleKeywords.any { kw -> text.contains(kw) }
            }
            val actionHitCount = taskListActionKeywords.count { kw ->
                allText.any { text -> text.contains(kw) }
            }
            val isTaskListOpen = hasTaskListTitle || actionHitCount >= 2
            if (isTaskListOpen) {
                // build555 修复（debug_test_20260719_143107.log, build553-0218141）：
                //   14:25:19.989 fertilize: task list popup detected, pressBack to close it
                //   14:25:26.009 fertilize: findFertilizeButton=false, clickCount=1 (pressBack 后 hint 消失)
                //   14:25:26.072 fertilize: not on farm page, re-navigate (误判退出主页)
                //   14:25:26.073 state: FERTILIZING -> NAVIGATING
                // 根因：pressBack 一次既关弹窗又退出主页，导致 hint 消失，被误判为"不在主页"切回 NAVIGATING，
                //   形成 FERTILIZING → NAVIGATING → COLLECTING_DIRECT → OPENING_TASK_LIST → PROCESSING_TASK → FERTILIZING 无限循环
                //
                // 修复：优先点击任务列表弹窗的专用"关闭做任务集肥料弹窗"按钮（不退主页），
                //   找不到再用 pressBack 兜底。
                // 日志证据：dumpClickableNodes 含 '关闭做任务集肥料弹窗' bounds=[1051,854][1152,953]
                val closeButton = if (root != null) {
                    // build652 修复：优先找专用"关闭做任务集肥料弹窗"按钮，
                    // 找不到则找文字精确为"关闭"且位置在屏幕右上角的按钮（任务列表弹窗关闭按钮），
                    // 避免误点主页广告"关闭"按钮。
                    service.findNodeByText(root, "关闭做任务集肥料弹窗")
                        ?: service.findTaskListCloseButton(root)
                } else null
                if (closeButton != null) {
                    val closeBtnText = closeButton.text?.toString().orEmpty()
                    debugLog("fertilize: task list popup detected, click close button '$closeBtnText' to close it")
                    Log.i(TAG, "fertilize: task list popup detected, click close button '$closeBtnText' to close it")
                    service.performClickSafe(closeButton)
                } else {
                    debugLog("fertilize: task list popup detected but no close button, pressBack to close it")
                    Log.i(TAG, "fertilize: task list popup detected but no close button, pressBack to close it")
                    service.pressBack()
                }
                handler.postDelayed({
                    if (state == AutomationState.FERTILIZING) runFertilizing(clickCount + 1)
                }, INTERVAL_PAGE_LOAD_MS)
                return
            }
            // 任务列表未展开，已在主页，直接进入施肥逻辑
            val onFarm = service.isOnFarmPage()
            if (!onFarm) {
                debugLog("fertilize: not on farm page and no task list popup, re-navigate")
                moveTo(AutomationState.NAVIGATING)
                handler.postDelayed({ runNavigating(0) }, INTERVAL_PAGE_LOAD_MS)
                return
            }
            debugLog("fertilize: on farm page (no task list popup), go fertilize directly")
            Log.i(TAG, "fertilize: on farm page (no task list popup), go fertilize directly")
            handler.postDelayed({
                if (state == AutomationState.FERTILIZING) runFertilizing(clickCount + 1)
            }, INTERVAL_CLICK_MS)
            return
        }

        // 查找并点击"施肥"按钮（主页上若可见 / 点击 hint 后弹出的"施肥"大按钮）
        val fertilizeButton = service.findFertilizeButton()
        debugLog("fertilize: findFertilizeButton=${fertilizeButton != null}, clickCount=$clickCount")
        if (fertilizeButton != null && fertilizeButton.isClickable) {
            // build549 修复（用户反馈"施肥那么大个按钮在，点击就是施肥了"）：
            // 历史问题（build548-aed53e2, debug_test_20260719_134803.log）：
            //   13:46:46.733 performClickSafe: text='还差3次领肥料' (点击 hint)
            //   13:46:49.918 performClickSafe: text='立即施肥' bounds=[278,1660][923,1807] (大按钮，645x147)
            //     注：accessibility 报告 text='立即施肥'，但 UI 实际显示"施肥"两字（用户确认）
            //   13:46:49.930 ACTION_CLICK success
            //   13:46:52.996 state: FERTILIZING -> WAITING  ← 错误：施肥成功却切 WAITING
            // 原因：build548 点击"施肥"后调 findFertilizeButton 检查 stillHasButton，
            //   弹窗关闭后主页暂时没"施肥"按钮（要再点 hint 才会弹出）→ 误判"施肥完成"切 WAITING。
            //   导致每次只施 1 次肥就退出，3 轮循环里始终是"还差3次领肥料"，从没施够 3 次。
            // 用户反馈："还差3次施肥，那我们就施肥3次，然后还差3次施肥会变成'立即领取'"
            //   ——需要连续施肥直到 hint 变成"立即领取"，由 hint 状态驱动终止，而非按钮存在与否。
            //
            // 修复：移除 stillHasButton 检查，点击"施肥"后等 2.5 秒（动画/施肥结算）后递归继续。
            // 终止条件改为 hint 状态：
            //   - hint 变"立即领取"/"立即领肥"/"点击领取"等 → findDirectCollectButtons 命中 → 切 COLLECTING_DIRECT
            //   - hint 消失且无 direct 按钮 → 切 WAITING
            //   - hint remainCount 连续 3 轮不递减 → 卡死保护切 WAITING
            val btnRect = android.graphics.Rect()
            fertilizeButton.getBoundsInScreen(btnRect)
            Log.i(TAG, "fertilize: click 施肥 button text='${fertilizeButton.text}' bounds=${btnRect.toShortString()} (clickCount=${clickCount + 1})")
            service.performClickSafe(fertilizeButton)
            handler.postDelayed({
                if (state == AutomationState.FERTILIZING) runFertilizing(clickCount + 1)
            }, 2500L)  // 2.5 秒等待施肥动画/结算
            return
        }

        // 没找到"施肥"按钮（H5 未暴露"施肥"文本），检查是否在主页
        Log.d(TAG, "fertilize: no 施肥 button found (clickCount=$clickCount)")
        // build548 修复（用户反馈"'还差x次施肥'，不是让你去点击这个按钮，而是去点击施肥按钮"）：
        // 历史问题（build547-1e07e0e）：
        // - build543 用坐标兜底点击 (0.501, 0.761)，但日志（debug_test_20260719_130559.log,
        //   build546-c55eb0b）证明这个坐标不是施肥按钮，点击会触发 gameTaskSuspend.html 弹窗：
        //     13:02:39.929 fertilize-coord: click at (601.2, 1935.2229)
        //     13:02:51.518 sample=[更多, 关闭, gameTaskSuspend.html?caprMode=sync]
        //   导致 FERTILIZING 退出主页 → NAVIGATING → 反复循环 4 次无进展。
        // - build547 改为直接点击"还差X次领肥料"按钮本身（clickable=true），但用户反馈：
        //   "'还差x次施肥'，不是让你去点击这个按钮，而是去点击施肥按钮" —— 这是提示文字，
        //   点击它本身不会施肥，应该点击它附近的真实施肥按钮。
        //
        // build549 实测发现（debug_test_20260719_134803.log）：
        // - 点击 hint "还差3次领肥料"会弹出"施肥"大按钮（645x147），点击即施肥
        // - 所以 hint 是入口，点击 hint → 弹"施肥" → 点击"施肥" → 施肥一次 → 回主页
        // - 循环此过程直到 hint 变成"立即领取"
        val onFarm = service.isOnFarmPage()
        if (!onFarm) {
            debugLog("fertilize: not on farm page, re-navigate")
            moveTo(AutomationState.NAVIGATING)
            handler.postDelayed({ runNavigating(0) }, INTERVAL_PAGE_LOAD_MS)
            return
        }
        // 检测是否有可领取的按钮（"立即领取"/"立即领肥"/"点击领取"等）→ 切 COLLECTING_DIRECT 领取
        // 这是施肥完成的终止条件：hint "还差X次领肥"施够后变成"立即领取"
        val directButtons = service.findDirectCollectButtons()
        if (directButtons.isNotEmpty()) {
            debugLog("fertilize: found ${directButtons.size} direct collect buttons, switch to COLLECTING_DIRECT")
            Log.i(TAG, "fertilize: direct collect buttons found (hint became 立即领取), switch to COLLECTING_DIRECT to claim")
            moveTo(AutomationState.COLLECTING_DIRECT)
            handler.postDelayed({ runCollectingDirect(attempt = 0) }, INTERVAL_CLICK_MS)
            return
        }
        // 找"还差X次领肥料"提示文字节点，根据其 bounds 推算施肥按钮坐标
        val hintNode = service.findRemainingFertilizerHintNode()
        if (hintNode != null) {
            val remainCount = service.parseFertilizeRemainingCount()
            // build549：用 hint remainCount 防卡死
            // 连续 3 轮 hint remainCount 不变（施肥没生效）→ 切 WAITING 避免无限循环
            if (remainCount > 0 && remainCount == lastRemainCount) {
                noProgressStreak++
                debugLog("fertilize: hint stuck at 还差${remainCount}次, noProgressStreak=$noProgressStreak")
                if (noProgressStreak >= 3) {
                    Log.i(TAG, "fertilize: hint stuck at 还差${remainCount}次 for $noProgressStreak rounds, give up")
                    moveTo(AutomationState.WAITING)
                    handler.postDelayed({ startNextRound() }, INTERVAL_WAIT_MS)
                    return
                }
            } else if (remainCount > 0 && remainCount != lastRemainCount) {
                debugLog("fertilize: hint remainCount changed $lastRemainCount -> $remainCount, reset noProgressStreak")
                noProgressStreak = 0
                lastRemainCount = remainCount
            }
            // build551 修复（用户反馈"'施肥'按钮就在芭芭农场主页面上"）：
            // 历史问题（build548/549）：
            // - 误以为施肥按钮要通过点击 hint 弹窗触发，实际上"施肥"按钮就在主页上
            // - 但 H5 没暴露施肥按钮的 accessibility 节点（无 text/desc/clickable），
            //   dumpClickableNodes 里 23 个节点都没"施肥"按钮
            // - 用户确认：施肥按钮在"还差X次领肥料"提示文字的下方
            //
            // 修复：用 hint bounds 推算施肥按钮坐标（hint 下方），用 dispatchGestureClick 点击
            // 日志证据（debug_test_20260719_134803.log）：
            //   hint bounds=[442,1539][759,1617]，中心 (600, 1578)
            //   点击 hint 弹出的弹窗里"施肥"按钮 bounds=[278,1660][923,1807]，中心 (600, 1733)
            //   → 弹窗按钮和主页施肥按钮位置接近，主页施肥按钮中心约在 hint 下方 ~150px
            // 主页施肥按钮坐标：hint 中心 X，hint 下方 1.8 倍 hint 高度（hint 高 78px，下方 ~140px）
            val hintRect = android.graphics.Rect()
            hintNode.getBoundsInScreen(hintRect)
            val fertCx = hintRect.exactCenterX()
            val fertCy = hintRect.exactCenterY() + (hintRect.height() * 1.8f)  // hint 下方 1.8 倍 hint 高度
            debugLog("fertilize: hint at (${hintRect.exactCenterX()}, ${hintRect.exactCenterY()}), click 施肥 button below at ($fertCx, $fertCy) (clickCount=$clickCount)")
            Log.i(TAG, "fertilize: click 主页施肥 button at ($fertCx, $fertCy) (还差${remainCount}次领肥, clickCount=${clickCount + 1})")
            service.dispatchGestureClick(fertCx, fertCy)
            handler.postDelayed({
                if (state == AutomationState.FERTILIZING) runFertilizing(clickCount + 1)
            }, 2500L)  // 2.5 秒等待施肥动画/结算
            return
        }
        // 没有"还差X次领肥料"提示，也没有 direct 按钮，也没有施肥按钮，认为施肥完成
        // build655 修复（debug_test_20260726_202554.log, build654-65cb2e7）：
        //   19:52:24.899 state: FERTILIZING -> WAITING → startNextRound → 又一轮 → 又 WAITING
        //   大循环 FERTILIZING→WAITING→NAVIGATING→COLLECTING_DIRECT→OPENING_TASK_LIST→
        //   PROCESSING_TASK→FERTILIZING 重复多次直到用户手动停止。
        // 根因：build654 让 findFertilizeButton 返回 null（过滤"可施肥0次"），
        //   但旧正则 findRemainingFertilizerHintNode 也找不到"再施肥 X 次可领"hint
        //   → 走到此分支 → 切 WAITING → startNextRound → 又一轮（无任务可做）→ 又到此分支 → 大循环。
        // 修复：检测页面是否有"可施肥0次"按钮（肥料用尽），若是则切 STOPPING 避免大循环。
        //   build655 已修复 findRemainingFertilizerHintNode 正则，hint 会被找到走 hint 兜底逻辑，
        //   此处作为最终兜底：若 hint 兜底 3 轮无进展切 WAITING 后，再进入此分支时检查"可施肥0次"。
        if (service.hasZeroFertilizerButton()) {
            Log.i(TAG, "fertilize: no fertilizer left (可施肥0次), stop automation to avoid loop")
            debugLog("fertilize: 可施肥0次 detected, stop automation")
            moveTo(AutomationState.STOPPING)
            handler.postDelayed({
                if (state == AutomationState.STOPPING) {
                    moveTo(AutomationState.IDLE)
                }
            }, INTERVAL_PAGE_LOAD_MS)
            return
        }
        Log.i(TAG, "fertilize: no remaining-fertilizer hint and no direct button, done")
        moveTo(AutomationState.WAITING)
        handler.postDelayed({ startNextRound() }, INTERVAL_WAIT_MS)
    }

    /** 开始下一轮（集肥料→施肥循环） */
    private fun startNextRound() {
        if (state != AutomationState.WAITING) return
        Log.i(TAG, "=== Starting new round ===")
        collectedCount = 0
        currentTaskIndex = 0
        noProgressRounds = 0
        noProgressStreak = 0  // build545：重置施肥无进展计数
        lastRemainCount = -1  // build549：重置施肥 remainCount 跟踪
        taskButtons = emptyList()
        moveTo(AutomationState.NAVIGATING)
        handler.post { runNavigating(0) }
    }

    // ============== 跨平台切换 ==============

    /**
     * 检测跨平台任务的目标平台
     *
     * 任务文本包含"去淘宝"/"切换淘宝"→ 淘宝
     * 任务文本包含"去支付宝"/"切换支付宝"→ 支付宝
     * 任务文本包含"去UC"/"切换UC"→ UC
     *
     * @param text 任务按钮文本
     * @return 目标平台，null 表示不是跨平台任务
     */
    private fun detectCrossPlatformTarget(text: String): Platform? {
        val lower = text.lowercase()
        return when {
            text.contains("淘宝") -> Platform.TAOBAO
            text.contains("支付宝") || text.contains("蚂蚁庄园") -> Platform.ALIPAY
            text.contains("UC") || lower.contains("ucmobile") || text.contains("uc极速") -> Platform.UC
            else -> null
        }
    }

    /**
     * 跨平台切换阶段
     *
     * 用户需求：肥料获取可能从支付宝/淘宝芭芭农场之间切换，切换动作也可以获取肥料，
     * 但切换完了应该回到原来 app。
     *
     * 流程：
     * 1. LAUNCH_TARGET: 启动目标平台 app，等待加载
     * 2. NAVIGATE_TARGET_FARM: 导航到目标平台芭芭农场，等待加载
     * 3. FERTILIZE_TARGET: 在目标平台点击施肥/集肥料按钮获取切换奖励
     * 4. RETURN_ORIGINAL: 返回原平台 app，等待加载
     * 5. RESUME_ORIGINAL_FARM: 导航回原平台芭芭农场，恢复任务列表
     */
    private fun runSwitchingPlatform() {
        if (state != AutomationState.SWITCHING_PLATFORM) return
        val service = getService() ?: run { stop(); return }

        logPageSnapshot(service, "switchPlatform-$switchStage")
        debugLog("switchPlatform: stage=$switchStage, retry=$switchRetryCount, target=$switchTargetPlatform, original=$switchOriginalPlatform")

        when (switchStage) {
            "LAUNCH_TARGET" -> {
                // 首次进入时，检查点击"去完成"是否已自动跳转到目标平台
                service.refreshPlatform()
                val currentPkg = service.getCurrentWindowPackage() ?: ""
                val targetPkg = switchTargetPlatform.config.packageNames.firstOrNull() ?: ""
                if (currentPkg == targetPkg || service.currentPlatform == switchTargetPlatform) {
                    debugLog("switchPlatform: target ${switchTargetPlatform} already loaded (auto-jump), navigating to farm")
                    switchStage = "NAVIGATE_TARGET_FARM"
                    switchRetryCount = 0
                    service.cancelNavigation()
                    // build636 修复（debug_test_20260726_110139.log line 217-265）：
                    // 历史问题：这里调用 service.navigateToFarm() 会根据 currentPlatform（已变为 ALIPAY）
                    // 启动 stepNavigateAlipayFarm 的 navHandler 链。即使后续 switchStage 切换到
                    // RETURN_ORIGINAL，stepNavigateAlipayFarm 仍在 navHandler 队列中持续执行，
                    // 在 RETURN_ORIGINAL retry 期间触发 navigateAlipay: actively relaunching farm app，
                    // 把 ALIPAY kill 后又 relaunch，导致 TAOBAO 永远无法启动到前台。
                    // 修复：跨平台任务依赖 auto-jump（点击"去完成"已自动跳转到目标平台芭芭农场 H5 页面），
                    // 不再主动调用 navigateToFarm。NAVIGATE_TARGET_FARM 阶段会用 isOnFarmPage 等待加载完成。
                    handler.postDelayed({ runSwitchingPlatform() }, INTERVAL_PAGE_LOAD_MS * 2)
                    return
                }
                // 未自动跳转，主动启动目标平台
                if (switchRetryCount == 0) {
                    debugLog("switchPlatform: launching target ${switchTargetPlatform} manually")
                    // build638: killCurrentFirst=false，当前前台是原平台，kill 目标平台没意义
                    service.launchPlatformApp(switchTargetPlatform, killCurrentFirst = false)
                }
                switchRetryCount++
                if (switchRetryCount >= MAX_SWITCH_RETRIES) {
                    debugLog("switchPlatform: failed to launch target, skipping task")
                    // build588 修复（debug_test_20260721_184040.log, build587 line 69-82）：
                    // 历史问题：switchPlatform 失败后直接 moveTo(PROCESSING_TASK),没有恢复
                    // service.currentPlatform 到 switchOriginalPlatform,导致后续 navigate
                    // 时 currentPlatform=UNKNOWN,用 UNKNOWN 平台 deep link（实际是 UC 的）,
                    // 但 isFarmAppInForeground 判断错误,反复 reopenFarmByDeepLink 始终进不了农场。
                    // 修复：失败后恢复 currentPlatform 到原平台,并重新启动原平台 App。
                    debugLog("switchPlatform: restoring currentPlatform to $switchOriginalPlatform and relaunching")
                    service.setCurrentPlatform(switchOriginalPlatform)
                    // build638: killCurrentFirst=false，当前前台是目标平台，kill 原平台没意义
                    service.launchPlatformApp(switchOriginalPlatform, killCurrentFirst = false)
                    currentTaskIndex++
                    moveTo(AutomationState.PROCESSING_TASK)
                    handler.postDelayed({ runProcessingTask(0) }, INTERVAL_CLICK_MS)
                    return
                }
                handler.postDelayed({ runSwitchingPlatform() }, 2000L)
            }

            "NAVIGATE_TARGET_FARM" -> {
                // 等待目标平台芭芭农场加载
                if (service.isOnFarmPage()) {
                    debugLog("switchPlatform: target farm page loaded, fertilizing")
                    switchStage = "FERTILIZE_TARGET"
                    switchRetryCount = 0
                    handler.postDelayed({ runSwitchingPlatform() }, INTERVAL_CLICK_MS)
                    return
                }
                switchRetryCount++
                if (switchRetryCount >= MAX_SWITCH_RETRIES) {
                    debugLog("switchPlatform: target farm not loaded, returning to original")
                    switchStage = "RETURN_ORIGINAL"
                    switchRetryCount = 0
                    // build638: killCurrentFirst=false，当前前台是目标平台，kill 原平台没意义
                    service.launchPlatformApp(switchOriginalPlatform, killCurrentFirst = false)
                    handler.postDelayed({ runSwitchingPlatform() }, INTERVAL_PAGE_LOAD_MS)
                    return
                }
                handler.postDelayed({ runSwitchingPlatform() }, 2000L)
            }

            "FERTILIZE_TARGET" -> {
                // 在目标平台点击施肥/集肥料按钮获取切换奖励
                // build777 修复（debug_test_20260905_171627.log, 17:14:58）：
                //   淘宝农场页有可见可点节点"2000，肥料，点击领取"(clickable=true,
                //   bounds=[966,1441][1179,1676])，但旧逻辑无视节点、盲点全部候选坐标，
                //   顶部坐标(272,272)/(300,279)/(600,467)误触商品区跳进商品详情页。
                //   修复：优先用 findDirectCollectButtons 找可见领取节点点击
                //   （TAOBAO directCollectTexts 含"点击领取"，过滤施肥/已领取/还差），
                //   找不到节点才回退盲点坐标。
                val directButtons = service.findDirectCollectButtons()
                if (directButtons.isNotEmpty()) {
                    val btn = directButtons.first()
                    val btnText = btn.text?.toString() ?: btn.contentDescription?.toString().orEmpty()
                    debugLog("switchPlatform-fertilize: found visible direct-claim node '$btnText', clicking node instead of blind coords (build777)")
                    Log.i(TAG, "switchPlatform-fertilize: found visible direct-claim node '$btnText', clicking node instead of blind coords (build777)")
                    service.performClickSafe(btn)
                } else {
                    // 无可见领取节点，回退目标平台的 collectFertilizerCoords 候选坐标
                    val coords = switchTargetPlatform.config.collectFertilizerCoords
                    debugLog("switchPlatform: fertilizing on ${switchTargetPlatform}, no direct-claim node, ${coords.size} coord candidates")
                    for ((xRatio, yRatio) in coords) {
                        clickAtRatio(service, xRatio, yRatio, "switchPlatform-fertilize")
                    }
                }
                // 等待施肥/领取完成
                switchStage = "RETURN_ORIGINAL"
                switchRetryCount = 0
                handler.postDelayed({ runSwitchingPlatform() }, INTERVAL_PAGE_LOAD_MS)
            }

            "RETURN_ORIGINAL" -> {
                // 启动原平台
                if (switchRetryCount == 0) {
                    debugLog("switchPlatform: returning to original ${switchOriginalPlatform}")
                    // build636 修复：取消可能残留的 navigateAlipay 队列（LAUNCH_TARGET 阶段可能触发）
                    service.cancelNavigation()
                    service.setCurrentPlatform(switchOriginalPlatform)
                    // build638: killCurrentFirst=false，当前前台是目标平台，kill 原平台没意义
                    service.launchPlatformApp(switchOriginalPlatform, killCurrentFirst = false)
                }
                service.refreshPlatform()
                // build638 修复（debug_test_20260726_125646.log line 461-534）：
                // 历史问题：用 getCurrentWindowPackage() 判断原平台是否在前台，但该方法扫描所有 windows，
                // 可能返回 TAOBAO 包名（即使 activeRootPkg='com.hihonor.android.launcher'），导致误判
                // "原平台已加载"，进入 RESUME_ORIGINAL_FARM 后 isOnFarmPage 一直 false，17 秒后超时。
                // 修复：用 rootInActiveWindowSafe().packageName（activeRootPkg）判断，这才是用户实际看到的窗口。
                val activeRootPkg = service.rootInActiveWindowSafe()?.packageName?.toString().orEmpty()
                val originalPkg = switchOriginalPlatform.config.packageNames.firstOrNull() ?: ""
                val isOriginalInForeground = activeRootPkg.isNotEmpty() && (
                    activeRootPkg == originalPkg ||
                    switchOriginalPlatform.config.internalPackagePrefixes.any { activeRootPkg.startsWith(it) }
                )
                if (isOriginalInForeground) {
                    debugLog("switchPlatform: original platform loaded (activeRootPkg=$activeRootPkg), resuming farm navigation")
                    switchStage = "RESUME_ORIGINAL_FARM"
                    switchRetryCount = 0
                    service.cancelNavigation()
                    service.navigateToFarm()
                    handler.postDelayed({ runSwitchingPlatform() }, INTERVAL_PAGE_LOAD_MS * 2)
                    return
                }
                // build638 修复：retry 期间若仍在 launcher，每隔 2 次主动 relaunch 原平台
                // （Honor 系统下 launchPlatformApp 可能因后台启动限制未成功）
                // 注意：killCurrentFirst=false，因为当前前台是其他平台（如 ALIPAY），kill 原平台没意义，
                // 反而会让原平台更难启动到前台（Honor 后台启动限制）
                if (switchRetryCount > 0 && switchRetryCount % 2 == 0) {
                    debugLog("switchPlatform: still not on original after $switchRetryCount retries (activeRootPkg=$activeRootPkg), actively relaunching ${switchOriginalPlatform}")
                    service.setCurrentPlatform(switchOriginalPlatform)
                    service.launchPlatformApp(switchOriginalPlatform, killCurrentFirst = false)
                }
                switchRetryCount++
                if (switchRetryCount >= MAX_SWITCH_RETRIES) {
                    debugLog("switchPlatform: failed to return to original, skipping task")
                    // build588: 恢复 currentPlatform 到原平台（与 LAUNCH_TARGET 失败分支同理）
                    debugLog("switchPlatform: restoring currentPlatform to $switchOriginalPlatform and relaunching")
                    service.setCurrentPlatform(switchOriginalPlatform)
                    // build638: killCurrentFirst=false，当前前台是目标平台/launcher，kill 原平台没意义
                    service.launchPlatformApp(switchOriginalPlatform, killCurrentFirst = false)
                    currentTaskIndex++
                    moveTo(AutomationState.PROCESSING_TASK)
                    handler.postDelayed({ runProcessingTask(0) }, INTERVAL_CLICK_MS)
                    return
                }
                handler.postDelayed({ runSwitchingPlatform() }, 2000L)
            }

            "RESUME_ORIGINAL_FARM" -> {
                // 等待原平台芭芭农场加载
                if (service.isOnFarmPage()) {
                    debugLog("switchPlatform: original farm page loaded, resuming task list")
                    // 跨平台切换任务完成，继续下一个任务
                    currentTaskIndex++
                    moveTo(AutomationState.PROCESSING_TASK)
                    handler.postDelayed({ runProcessingTask(0) }, INTERVAL_CLICK_MS)
                    return
                }
                // build639 修复（debug_test_20260726_140951.log line 246-352）：
                // 历史问题: build638 只在 retry=2,4,6 时才主动 relaunch + navigateToFarm，但
                //   runSwitchingPlatform 间隔 2 秒，navigateToFarm 在 5 秒后（INTERVAL_PAGE_LOAD_MS）才执行，
                //   下一次 retry=4 触发 cancelNavigation 会取消之前 postDelayed 的 navigateToFarm，
                //   导致 navigateToFarm 永远不会执行，30 秒后超时。
                // 根因: TAOBAO 启动后停在淘宝主页（act=TBMainActivity），不在农场页，
                //   需要主动 navigateToFarm 才能进入农场页。
                // 修复:
                //   1. retry=0 时立即调用 navigateToFarm（不等 retry=2）
                //   2. retry=2,4,6 时不再 relaunch（避免 cancelNavigation 取消正在执行的 navigateToFarm）
                //   3. 增加间隔到 6 秒，让 navigateToFarm 有时间完成（navigateToFarm 内部多次 stepClickFarmTabByGesture）
                //   4. 只有 retry=4 时才 relaunch（如果 navigateToFarm 完全失败）
                if (switchRetryCount == 0) {
                    debugLog("switchPlatform: original farm not loaded, calling navigateToFarm immediately")
                    service.navigateToFarm()
                }
                // retry=4 时，如果还没到农场页，主动 relaunch + navigateToFarm
                // 注意：不再在 retry=2 触发 relaunch，避免 cancelNavigation 取消正在执行的 navigateToFarm
                if (switchRetryCount == 4) {
                    debugLog("switchPlatform: original farm not loaded after $switchRetryCount retries, actively relaunching ${switchOriginalPlatform} and re-navigating")
                    service.cancelNavigation()
                    service.setCurrentPlatform(switchOriginalPlatform)
                    service.launchPlatformApp(switchOriginalPlatform, killCurrentFirst = false)
                    // 等待 app 启动后再 navigateToFarm
                    handler.postDelayed({
                        if (state == AutomationState.SWITCHING_PLATFORM) {
                            service.navigateToFarm()
                        }
                    }, INTERVAL_PAGE_LOAD_MS)
                }
                switchRetryCount++
                if (switchRetryCount >= MAX_SWITCH_RETRIES) {
                    debugLog("switchPlatform: original farm not loaded, re-navigating from start")
                    moveTo(AutomationState.NAVIGATING)
                    handler.postDelayed({ runNavigating(0) }, INTERVAL_CLICK_MS)
                    return
                }
                // build639: 增加间隔到 6 秒，让 navigateToFarm 有时间完成
                handler.postDelayed({ runSwitchingPlatform() }, 6000L)
            }

            else -> {
                debugLog("switchPlatform: unknown stage $switchStage, skipping task")
                currentTaskIndex++
                moveTo(AutomationState.PROCESSING_TASK)
                handler.postDelayed({ runProcessingTask(0) }, INTERVAL_CLICK_MS)
            }
        }
    }

    // ============== 通用工具 ==============

    /** 按坐标比例点击屏幕（基于屏幕宽高百分比） */
    private fun clickAtRatio(
        service: FarmAccessibilityService,
        xRatio: Float,
        yRatio: Float,
        label: String
    ) {
        val metrics = service.resources.displayMetrics
        val x = metrics.widthPixels * xRatio
        val y = metrics.heightPixels * yRatio
        Log.i(TAG, "$label: click at ($x, $y) screen=${metrics.widthPixels}x${metrics.heightPixels}")
        debugLog("$label: click at ($x, $y), ratio=($xRatio, $yRatio), screen=${metrics.widthPixels}x${metrics.heightPixels}")
        service.dispatchGestureClick(x, y)
    }
}
