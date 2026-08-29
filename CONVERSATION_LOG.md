# BBNC bbncbot 对话记录与工作上下文

> **使用规则**：每次会话开始时,先读取本文件了解项目当前状态和历史决策。
> 每次提交代码时同步更新本文件,记录本轮对话的用户需求、修改要点和当前实现状态。
> 用户说"分析日志"等无具体内容的请求时,先读本文件再分析最新日志。

---

## 项目概述

- **项目**: BBNC bbncbot - 安卓无障碍服务自动化 App,自动收取芭芭农场肥料
- **平台**: UC 极速版 / 支付宝 / 淘宝 三平台
- **技术栈**: Kotlin + Android Accessibility Service + 状态机 + ML Kit OCR + 智谱 GLM-4.6V-Flash 视觉 AI
- **GitHub**: `https://github.com/sicauyanglei/bbncbot`
- **当前分支**: `trae/agent-qxjDbm`（强制推送到 main）
- **构建**: `JAVA_HOME=/root/.local/share/mise/installs/java/17.0.2`（JDK 25 与 AGP 8.2.0 不兼容）

---

## 核心架构

### 状态机阶段
- `IDLE` → `NAVIGATING` → `COLLECTING_DIRECT` → `OPENING_TASK_LIST` → `PROCESSING_TASK` → `WATCHING_AD` → `BROWSING_TASK` → `FERTILIZING` → 回 `OPENING_TASK_LIST`

### 关键文件
- **`app/src/main/java/com/bbncbot/automation/AutomationController.kt`** (~270KB) - 状态机主控制器
- **`app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt`** (~220KB) - 无障碍服务,节点查找/点击/截图
- **`app/src/main/java/com/bbncbot/automation/AiVisionClient.kt`** (~25KB) - GLM 视觉 AI 客户端
- **`app/src/main/java/com/bbncbot/automation/Platform.kt`** (~17KB) - 平台配置(UC/ALIPAY/TAOBAO)

---

## 本轮会话修改历史（最新在上）

### commit <待填> - feat: build751 "我要更快拿奖"直接跳转外来App后停留15秒,从底部按住上滑(上滑停顿)打开最近任务点击UC卡片切回跳转前广告页(替代立即forceKill杀不掉方案)

**用户需求**: "点击跳转后，过15秒回到跳转前的页面，操作应该是从底部手指按住不放，
往上拖动，然后把之前切走前的页面设置为前台页面"（针对 debug_test_20260829_154730.log
build749 的 15:44:53 跳淘宝 / 15:45:21 跳支付宝 两次 stage=1 无确认弹窗直接跳转场景）

**旧方案问题(已修)**:
- 旧逻辑(build746): stage=1 检测到直接跳转 → 立即 forceKillApp 杀外来App——
  但前台App杀不掉(killBackgroundProcesses 限制, build697 已知),5s后 trap 分支
  深链回UC+杀App(此时已退后台才杀成功) → 广告被弃,每次~15s 无收益

**新方案(build751, 按用户描述实现)**:
1. stage=1 检测到直接跳转外来App: 记录包名+时刻(fasterRewardStage1JumpPkg/
   fasterRewardStage1JumpStartMs), 停留等待15秒(FASTER_REWARD_STAGE1_JUMP_STAY_MS,
   每5s轮询,期间 return 不会走到 scene/TRAP/超时分支,等待有保障)
2. 停留满15秒: swipeUpFromBottomToOpenRecents() 从底部手指按住不放往上拖动
   (上滑停顿手势)打开系统最近任务——API26+ 两段式 continuation: 底部中点慢速
   上滑到屏高42%(450ms, willContinue=true 保持按住)+原地停顿500ms松手,
   系统判定"上滑停顿"打开最近任务(快速上滑会回桌面,停顿是关键);
   API<26 退化单段慢速长滑800ms
3. 1.2s后 findAndClickRecentTaskCard() 点击UC卡片把跳转前的页面设置为前台:
   最近任务刚打开时第一张卡片就是刚切走的UC广告页; 优先按关键词
   (UC极速版/UC浏览器/芭芭农场/UC)找卡片节点点击, 找不到坐标兜底点击屏幕
   中上部(第一张卡片主体区域)
4. INTERVAL_PAGE_LOAD_MS 后验证 rootInActiveWindow 包名:
   - 成功(UC包名): 继续正常广告结束/奖励检测(最近任务切回恢复原任务栈,
     广告页原样保留可继续, 比深链拉起新页面更可靠, 也更像真人操作)
   - 失败(未开手势导航/无UC卡片): 深链兜底回UC+杀外来App(UC拉前台后外来App
     退后台可杀), 继续广告等待
5. 守卫: 跳转等待期间跳过 isTaskCompletePage 检查(外来App页面文本可能含
   "已完成"误判); 跳转期间自然回到农场App则清记录继续正常流程;
   watchAd 入口重置两个新状态变量

**新增**: FASTER_REWARD_STAGE1_JUMP_STAY_MS=15000L 常量; fasterRewardStage1JumpPkg/
fasterRewardStage1JumpStartMs 状态变量; service.swipeUpFromBottomToOpenRecents()/
findAndClickRecentTaskCard() 两个手势函数

**修复后预期时间线**: 点击"我要更快拿奖"跳淘宝 → 停留15s(模拟真人浏览) →
上滑停顿打开最近任务 → 点UC卡片切回 → 广告页恢复继续正常流程(不再深链重开);
日志应出现 "停留15秒后手势切回UC" → "上滑停顿打开最近任务" → "手势切回UC成功" 链路

**编译验证**: sandbox 无 Android SDK, 等 CI 构建验证(括号平衡静态检查已过)。

---

### commit 6c45231 - fix: build750 快手"扭一扭"陷阱互动广告退出浪费40s,CLOSING_AD检测无领取/下载按钮直接forceKill宿主+深链重开(温和关闭多轮验证无效)

**用户需求**: "分析日志"（debug_test_20260829_154730.log, build749-0a7470c, 597行, 15:43:52-15:47:24 约3.5分钟, 用户手动停止）

**build749 验证结果**:
- ✓ NAVIGATING 死循环已解决: 15:44:05 attempt=0 UC不在前台(pkg=null)→reopenFarmByDeepLink
  深链直达→15:44:12 on farm page→15:44:18 farmContentLoaded→COLLECTING_DIRECT, 仅13秒
  (本轮未触发 h02.c 死循环场景——普通启动时 UC 未起来走了深链分支; 修复B内容兜底无副作用,
  forceKill UC 重开后 RETURNING→NAVIGATING→农场判定全程正常)
- ✓ 快照 pkg 全程正确, 无卡死
- ✗ build748 未验证: 本轮快手广告是"点击跳转拿奖励"陷阱变体(非"立即获取"可点变体),
  findInteractiveAdClickToClaimButton 正确返回 null 不误点, 但该变体无收益

**问题(已修)**: 快手陷阱互动广告退出流程一次浪费 40 秒(15:46:05-15:46:45):
- 本轮文案: "扭一扭或点击跳转详情页或第三方应用"+"点击跳转拿奖励"+"可直接拿奖励"
  (淘宝闪购推广)——是 build748 明确排除的跳转陷阱变体, 无可点领取按钮
- 时间线: WATCHING_AD 干等18s(countdown stuck at 10s 静态文本)→CLOSING_AD 策略0
  点'跳过'ACTION_CLICK success 但页面不关(15:46:07)→策略1 8坐标盲点无效(15:46:12)
  →策略3 pressBack 无效→RETURNING 温和退出3次无效(15:46:30-15:46:45)→attempt=3
  forceKill UC+深链重开才退出(6秒生效)。整轮广告 15:45:41-15:46:51 共70秒零收益
- 多轮日志(build706/734/750)反复验证: 此类广告对所有温和关闭手段(跳过按钮/坐标盲点/
  pressBack/back坐标)免疫, forceKillApp(宿主)+reopenFarmByDeepLink 是唯一可靠退出手段
- 修复: runClosingAd strategy=0 入口检测互动陷阱广告(isInteractiveAdPage 且无
  findInteractiveAdClickToClaimButton 按钮且无 findInteractiveAdDownloadButton 按钮且
  非 isFertilizerGrantedPage)→跳过全部温和策略(0-4)和 RETURNING 温和退出,直接
  forceKill 宿主+深链重开+NAVIGATING(与 build734 RETURNING 兜底同款)
- isInteractiveAdPage 从 private 改 public 供 controller 调用
- 安全性: 有"立即获取"按钮(build748 可点变体)或下载按钮(穿山甲下载类)或肥料已发放页
  时不触发,保留原有流程; 预期单次广告退出从 ~40s 缩短到 ~7s

**已知未修(次要)**:
- ①"我要更快拿奖"无确认弹窗变体×2(15:44:53跳淘宝/15:45:21跳支付宝): stage=1 检测
  跳转后 forceKillApp 杀不掉前台App(killBackgroundProcesses 限制,build697 已知),
  5s后 trap 分支深链回UC+杀App(此时已退后台杀成功)→广告被弃。每次~15s,广告奖励
  跳转后本来就拿不到,恢复流程正常无卡死,暂可接受
- ②15:44:20 第一次点击"看广告领奖"无效(H5响应慢),第二次(15:44:41)才进广告,浪费~20s
- ③forceKill UC 重开后 collectDirect 的 lastClickedButton 未重置,"看广告领奖"被
  skip(影响小,任务列表阶段正常点"去完成"进广告)

**编译验证**: sandbox 无 Android SDK, 等 CI 构建验证。

---

### commit 0a7470c - fix: build749 UC更新新增首页activity=h02.c不在farmPageActivityKeywords,isOnFarmPage永远false+stepTab点击入口12次无效,NAVIGATING死循环2分11秒(深链兜底+内容兜底双修复)

**用户需求**: "分析日志"（debug_test_20260829_084927.log, build747-d37ff9c, 202行, 08:46:50-08:49:24 约2.5分钟, 用户手动停止）

**问题(已修)**: UC 极速版更新引入新首页容器 activity=h02.c, NAVIGATING 死循环 2 分 11 秒:
- 死循环链条:
  1. h02.c 为历史日志从未出现的 UC 新 activity(不在 UC farmPageActivityKeywords
     [innerucmobile/mainactivity/pz1] 中);本次 openFarmInUcBrowser 普通启动 UC
     (非深链)停在 h02.c 首页
  2. isOnFarmPage 第2步 activity 不匹配直接 return false,永不做内容检查
  3. runNavigating else 分支(in farm app but not farm page)→ navigateToFarm →
     stepTab 每轮都找到首页"芭芭农场，免费领水果，助果农增收"入口卡片
     (bounds=[363,451][838,520])并 gesture 点击,但新版首页点击无效
     (12 次均未进入农场页, 08:47:16-08:49:24 每~11s循环一次)
  4. else 分支无任何兜底; attempt>=10 仅重置计数继续循环 → 死循环到用户手动停止
- 佐证: build746 日志证实深链打开农场 URL 后 activity=InnerUCMobile(在 keywords 中,
  isOnFarmPage 正常判定);普通启动 UC 停在 h02.c 是新版 UC 行为变化

**修复(双保险)**:
- 修复A: runNavigating else 分支 attempt>=6(约30s)仍不在农场页时,放弃 stepTab
  点击入口,改用 reopenFarmByDeepLink(killCurrentFirst=false) 深链直达农场页;
  深链后 attempt 重置为 0,给 H5 加载留 6 轮(~30s)窗口,加载慢时才会再次深链。
  killCurrentFirst=false: UC 在前台未被杀,深链直接切换(build721 教训:kill 后
  Honor 后台启动限制可能拉不起 UC)
- 修复B: isOnFarmPage activity 不匹配时不立即 return false,标记 activityMismatch
  继续走内容检查,页面含农场核心元素(hasFarmCoreEffective: 集肥料/施肥/换种等)
  则内容兜底判定为农场页;无农场核心元素才返回 false。防止未来 UC 再换容器
  activity 名后农场页判定彻底失效。用严格核心词(非宽泛 hasFarmContent 的
  "领取奖励/任务完成")降低非农场页误判风险
- 安全性: 广告页 activity 在 farmKeywords 检查之前已被 AD_ACTIVITY_KEYWORDS
  拦截(776行),内容兜底不会放过广告页;h02.c 首页无农场核心文本(日志
  claim-text-nodes NONE 证实),不会被误判为农场页

**修复后预期时间线**: 启动 UC 停 h02.c → attempt 0-5 stepTab 尝试(~30s) →
attempt=6 深链直达农场页(InnerUCMobile) → H5 加载 → isOnFarmPage=true →
COLLECTING_DIRECT 正常流程;若新版 UC 深链打开后仍是 h02.c 承载农场页,
修复B 内容兜底生效

**注意**: 本轮日志未验证 build748(快手互动广告点击领取)修复——自动化卡死在
NAVIGATING 死循环,未进入广告流程,build748 修复待下轮日志验证

**编译验证**: sandbox 无 Android SDK, 等 CI 构建验证。

---

### commit 3464529 - fix: build748 快手"扭一扭或点击立即获取"互动广告点击可替代摇一摇拿奖励,不再当陷阱干等60s强退无收益

**用户需求**: "分析日志"（debug_test_20260822_195844.log, build747-d37ff9c, 1097行, 19:49-19:58 约9.5分钟）

**build747 验证结果**:
- ✓ 签到误判守卫生效: 3次看视频任务点击后页面有"已领取/明天领肥料"静态标识但**不再误判**,
  正确进入广告检测 → WATCHING_AD
- ✓ 任务收益确认: 看视频任务计数 (1/10)→(3/10), 穿山甲广告2次"领取成功"
- ✓ "去领取(1s)"弹窗未再现; 淘宝卡死未再现; 快照 pkg 全程正确
- ✓ build746 弱信号守卫继续生效
- 流程完整跑通多轮, 无卡死, 用户在最后一轮 NAVIGATING(H5渲染慢)时手动停止

**问题(已修)**: 快手摇一摇互动广告被当"无法自动化陷阱",每次~60s全浪费且无收益:
- 19:52:55/19:55:30/19:57:01 共3次: KsRewardVideoActivity 文案
  "扭一扭或点击立即获取"+"可直接拿奖励"——**点击可替代物理摇动直接拿奖励**
- 旧逻辑: 干等15s(countdown stuck at 10s) → CLOSING_AD(跳过+8坐标×2轮≈25s) →
  RETURNING(pressBack×3无效+forceKill UC+深链重开≈25s) → 奖励丢失
- 3次共~3分钟浪费(本轮9.5分钟的1/3)
- 修复: TRAP_INTERACTIVE 分支优先检测"扭一扭或点击立即获取"类按钮
  (findInteractiveAdClickToClaimButton, 精确文案+contains兜底, **排除**
  "点击跳转详情页/第三方应用"陷阱变体) → 点击 → 后续轮询由 isAdEndedMultiSignal
  检测"领取成功"走正常 REWARD_POPUP 流程; interactiveAdClickClaimClicked
  每轮广告只点一次, 广告入口处重置。点击无效则走原15s等待+CLOSING_AD,无回退风险

**已知未修(次要)**: ①"我要加速"跳转包名记录错误(19:49:45记录微信,实际19:49:51
  在汽车之家)→10s停留后bringFarmAppToFront失败→trap分支兜底杀汽车之家,广告被弃
  (恢复正常无卡死,跳转包名追踪深层修复复杂,暂不动); ②最后一轮NAVIGATING H5渲染
  卡住(realContent=22不涨)35s后用户手动停止(UC WebView渲染问题,attempt上限内会
  reopenFarmByDeepLink重试)

**编译验证**: sandbox 无 Android SDK, 等 CI 构建验证。

---

### commit d37ff9c - fix: build747 "去领取(1s)"倒计时弹窗无人点击奖励丢失+签到误判守卫+淘宝前台pressBack无效卡死70s

**用户需求**: "分析日志"（debug_test_20260822_192917.log, build746-40246b3, 330行, 19:24:40-19:28:55 约4.5分钟; 192857.log 是同会话前20秒的子集）

**build746 验证结果**:
- ✓ 弱信号守卫生效: 19:26:52-19:27:04 微信前台12秒(疑用户手动切换)被正确识别
  "activeRootPkg='com.tencent.mm' is foreign, activity weak signal ignored" →
  attempt=6 reopenFarmByDeepLink 拉回UC,不再像 build721/746 之前那样桌面卡死
- ✓ 快照 pkg 全程正确(UC/微信/淘宝各归其位)
- 流程: 19:25:28 看广告领奖→快手激励视频(摇一摇互动)15s倒计时卡住→CLOSING_AD→
  pressBack×3无效→forceKill UC+深链重开→NAVIGATING 2分37秒(H5加载慢+微信12s)→
  farmContentLoaded→COLLECTING_DIRECT→AI视觉15s超时→OPENING_TASK_LIST→
  19:27:47 PROCESSING_TASK task#1"看视频得巨额肥料(1/10)"点击"去完成"

**问题A(已修)**: "去领取(1s)"倒计时弹窗无人点击,看视频任务奖励丢失:
- 19:27:52 checkTaskResult 快照: text='去领取(1s)' bounds=[222,1637][979,1801]
  clickable=false——这是看视频任务的倒计时领取弹窗(N秒后变可点)
- 旧逻辑不识别它;页面静态"已领取/明天领肥料"(签到区标识,签到早已完成)触发
  build668 签到判定(误判) → advance → 弹窗没人点 → 奖励丢失
- 修复1: checkTaskResult 优先检测"去领取(Ns)"弹窗(findCountdownClaimButton,
  Regex 去领取\((\d+)s?\)) → 等 N+2 秒 → 重新找"去领取"节点点击 → 继续结果检测
- 修复2: build668 签到判定加守卫——当前任务上下文须含"签到"才允许"已领取"判定
  (签到按钮被 drop 时 task#0 是看视频,不该用签到标识判定完成);
  runProcessingTask 点击时快照 currentTaskContextText 供 checkTaskResult 使用

**问题B(已修)**: 误判 advance 后页面跳淘宝,processTask 卡死70秒:
- 19:27:55-19:28:55 淘宝首页前台(TBMainActivity),isOnFarmPage false → pressBack
  ×3 无效(淘宝首页拦截返回键) → skip task → 下一个任务 → pressBack×3……
  8任务轮完需2分钟+,用户19:28:55手动停止时已卡70s
- 修复: runProcessingTask 非农场分支——attempt>=1(pressBack已试一次无效)且活动
  窗口是外来App(非农场/非systemui/非android) → forceKill 该App +
  reopenFarmByDeepLink 回农场。深链任务停留期间状态是WATCHING_AD(checkTaskResult
  深链分支),不经过此分支,不受影响

**已知未修(次要)**: ①快手摇一摇互动广告倒计时卡10s静态→15s放弃进CLOSING_AD,
  claim-text-nodes NONE 无领取按钮,该广告无收益(互动广告需摇动手机,自动化无解);
  ②UC杀掉重开后H5加载慢,NAVIGATING耗2分37秒(realContent 12~22个未达阈值),
  系UC WebView冷启动固有耗时

**编译验证**: sandbox 无 Android SDK, 等 CI 构建验证。

---

### commit 40246b3 - fix: build746 "我要更快拿奖"点击直接跳广告主App干等25s广告被弃×3+美团overlay后UC未起桌面卡死2分17秒

**用户需求**: "分析日志"（debug_test_20260822_191049.log, build745-ebc33a1, 972行, 19:02-19:10 约8分钟）

**build744/745 验证结果**:
- build745 ✓ 微信误报修复生效: 全程快照 pkg 正确（UC 农场/UC 广告= com.ucmobile.lite,
  跳转抖音= com.ss.android.ugc.aweme.lite, 美团 overlay= com.sankuai.meituan）,
  trap 分支不再误杀微信
- build744 ✓ 防重复生效: 19:05:26 "skip button[0] text='看广告领奖' (same as last
  clicked)"——text-only 比较免疫 bounds 抖动; 无死循环
- build744 ✓ 安装类广告跳过任务生效: 19:07:15 "install-ad abandon skips task,
  next taskIndex=1"
- build743 深链链路: 本轮仍无深链任务执行（"去美团刷视频"未轮到）,待验证

**问题A(已修)**: "我要更快拿奖"点击直接跳转广告主App,确认弹窗不出现,25s干等后广告被弃:
- 19:05:38/19:06:25/19:07:35 共3次: PortraitADActivity(腾讯优量汇)广告页点击
  '我要更快拿奖' → 直接跳抖音极速版(aweme.lite),确认弹窗根本不出现
  (与 build686 华为应用市场变体同类)
- stage=1 干等 25s 超时 → stage=4 → 30s 轮询 trap 分支才发现外来App →
  杀抖音+重开农场 → 广告被放弃,奖励丢失;每次浪费 ~35s
- 修复: stage=1 每次轮询先查活动窗口(rootInActiveWindow)是否外来App → 是则立即
  forceKill 杀掉回 UC 广告页继续观看(广告Activity未被关闭,杀覆盖其上的外来App后
  自动恢复前台),同时放弃 faster reward(stage=4);必须用 rootInActiveWindowSafe
  而非 getCurrentWindowPackage(后者 systemui 覆盖时退回 windows 扫描可能误报微信残留)

**问题B(已修)**: 美团 overlay 后 UC 未起来,桌面卡死 2分17秒:
- 19:08:14 openTaskList 点'集肥料'后美团莫名到前台(疑坐标点击命中悬浮banner);
  overlay 分支 reopenFarmByDeepLink(killCurrentFirst=true) 杀UC重开,但 Honor 后台
  启动限制导致 UC 未起来,桌面成为活动窗口
- 19:08:38-19:10:46: activeRootPkg=launcher 但 currentActivityName=
  'com.uc.browser.innerucmobile'(深链拉起UC时残留的窗口事件) → isFarmAppInForeground
  弱信号误判"在农场App" → navigateToFarm→stepTab 在桌面找'芭芭农场'必然失败→
  pressBack→abort→循环 2分17秒直到用户手动停止(与 build721 08:02 场景同根源)
- 修复: isFarmAppInForeground 弱信号加守卫——活动窗口是外来App(桌面/第三方)时
  activity 弱信号不可信(残留窗口事件),返回 false → navigate 走 "farm app not in
  foreground" 分支 → reopenFarmByDeepLink(killCurrentFirst=false) 正确拉起UC;
  仅 systemui/android/null 活动窗口时保留弱信号(kill+relaunch 过渡期)

**已知未修(次要)**: 19:03-19:05 穿山甲"去体验15秒可立即领奖"广告变体: 广告无结束
信号,watchAd 干等满 90s 才 CLOSING_AD,且仅观看不点体验CTA无奖励(任务计数 1/10 未变)。
需专门设计"点击体验CTA→跳转停留15s→返回领奖"流程,暂不动(避免破坏正常激励视频流)。

**编译验证**: sandbox 无 Android SDK, 等 CI 构建验证。

---

### commit ebc33a1 - fix: build745 微信残留窗口致getCurrentWindowPackage误报,trap分支0ms误杀微信+放弃UC真激励视频广告×2

**用户需求**: "分析日志"（debug_test_20260822_184814.log, build744-e415ef3, 311行, 18:46-18:48 约2分钟）

**日志现象**:
- 全程所有快照 `pkg=com.tencent.mm`（微信）——即使 UC 农场页(onFarm=true)、UC 激励视频
  广告(act=TTRewardVideoActivity, adActivity=true)真正在前台
- 18:47:40 watchAd trap 分支第一次轮询(0ms)即误判"ad button trap"：forceKill 杀微信 +
  深链重开UC + 跳过任务 → "看广告领奖"打开的真激励视频广告被当场放弃
- 18:48:03 任务#1"看视频"再次打开激励视频广告 → 同样 0ms 误杀
- 用户 18:48:12 手动停止；0 肥料收集
- 另注：启动导航耗 10 次 attempt(~55s)，hasFarmContentLoaded 一直 false（farm 文本
  已齐全但 realContent count=17~22 未达阈值）——次要问题未修

**根因**: [FarmAccessibilityService.kt](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt)
`getCurrentWindowPackage()` 旧逻辑"返回 windows 列表第一个非农场包名"——手机上微信
（悬浮窗/分屏残留）窗口持续存在于 windows 列表且排在前面，导致永远返回
com.tencent.mm。与 build506（systemui 误报）、build638（后台淘宝误报）同一类历史坑：
windows 扫描不可靠，rootInActiveWindowSafe() 才是用户实际看到的活动窗口。

**修复**（getCurrentWindowPackage 重构优先级）:
1. 活动窗口(rootInActiveWindowSafe)是外来包→返回（千问跳转/深链任务跳转/
   packageinstaller寄宿广告[build725]场景保持不变）
2. 活动窗口是农场App包→直接返回（**核心修复**：前台就是农场/UC广告时，后台残留的
   微信窗口不再被误报）
3. 活动窗口无效(null/systemui/android)→退回原 windows 扫描兜底（build607 systemui
   排除逻辑保持）
4. 提取 `isForeignForegroundPkg()` 公共判定（排除农场包/内部前缀/bbncbot/android/
   systemui），活动窗口与窗口扫描共用

**影响面**: getCurrentWindowPackage 的全部调用点受益——watchAd trap 分支（本 bug）、
processTask 深链检测(otherPkg)、isAdPlaying 第2步、NAVIGATING 前台判断、
logPageSnapshot 诊断日志等，前台判定全部改为以活动窗口为准。

**编译验证**: sandbox 无 Android SDK, 等 CI 构建验证。

---

### commit e415ef3 - fix: build744 安装类广告放弃后"看广告领奖"按钮死循环(22s/轮×4)+任务索引不推进

**用户需求**: "分析日志"（debug_test_20260822_182904.log, build743-4289e9c, 691行, 18:24-18:29 约4分钟）

**build741/743 验证结果**:
- build741 ✓ 完美生效: 4次安装类广告全部 **0ms** 立即放弃（"下载安装类广告...立即forceKill杀宿主+重开农场放弃"），不再干等90秒
- build743 ✓ 广告会话 trap 正常: 18:26:07"我要更快拿奖"跳京东,25s stage=1超时后 trap 分支杀京东+重开UC恢复（~45s,比旧版快）
- build743 深链分支: 本次无深链任务执行（"去美团刷视频"任务未轮到）,待下轮验证

**新问题(死循环,已修)**:
- 18:27:06 任务#1"看视频"→安装类广告放弃→重开农场→COLLECTING_DIRECT 点击主页"看广告领奖"
  →10s→又是安装类广告→放弃→NAVIGATING→COLLECTING_DIRECT **又点同一按钮**→22秒一轮无限循环
- 根因1: collectDirect 防重复(build581)比较 text+bounds,按钮 bounds 抖动4px([641,1152]→[641,1156])
  绕过防护→重复点击
- 根因2: build741 放弃分支不推进任务索引(taskReplayRemaining/currentTaskIndex 不变)→
  重进 OPENING_TASK_LIST 后又从任务#1死磕
- 用户 18:29:01 手动停止

**修复**（[AutomationController.kt](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt)）:
1. collectDirect 防重复改为 **text-only**（同 text 即跳过,bounds 仅记录日志）——彻底免疫 bounds 抖动
2. build741 放弃分支推进任务: `currentTaskIndex++; taskReplayRemaining=0`（放弃不消耗
   multi-click replay 次数,下一轮轮到别的任务,OPENING_TASK_LIST 整轮重扫时被跳过的
   任务自然恢复）
3. 连续放弃停止防护: `installAdAbandonStreak`——放弃时若 collectedCount 与基线相同
   （期间无任何成功）则 streak++,不同则重置;连续 ≥8 次(一轮任务数上限)→ stop()
   停止自动化（当天广告池全是安装类广告,继续跑只会无限杀UC重开）。start() 处重置

**编译验证**: sandbox 无 Android SDK, 等 CI 构建验证。

---

### commit 4289e9c - fix: build743 深链分支自build737起是死代码——ad button trap分支先执行杀掉深链跳转App,build742全部恢复路径不可达

**用户需求**: "分析日志"（debug_test_20260822_094757.log, build739-e38c7ac, 1283行, 两段会话）

**日志总览**: 两段会话共 10 次广告遭遇，**0 肥料收集**（广告池当天几乎全下发安装类/
互动类广告）。build740 修复的 TRAP_INSTALL 误判（4次, 07:41-07:43）、build741 修复的
下载安装类广告（4次, 07:44+09:42-09:47）均覆盖。本日志无深链任务执行（任务#1从未
完成，未推进到深链任务）。

**问题(bug,已修——build742 的真正根因)**: 用户反馈"任务完成切到任务开始的页面"，
build742 修的落地页恢复逻辑根本不可达：
- `runWatchingAd` 中 "ad button trap" 分支（build714）在深链任务分支（build737, L6212）
  **之前**执行，条件不排除深链跳转的 App
- 深链任务点击 → 跳转其它App → **第一次轮询** trap 分支就触发：杀掉跳转App +
  重开UC + 跳过任务（currentTaskIndex++）→ UC WebView 停在任务落地页
- 深链分支自 build737 引入以来**从未执行过**（全部历史日志 grep "entered deep-linked
  app" 零出现，证实死代码；build737 验证只验证了 parseDeepLinkStayMs 解析日志）
- build742 的三条恢复路径全部挂在 `deepLinkAppPkg != null` 上，该变量只在死代码分支
  赋值 → build742 无效

**修复**（[AutomationController.kt](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt)）:
1. 新增 `watchingAdFromDeepLinkTask` 标记：WATCHING_AD 三入口赋值——processTask
   深链入口=true，processTask/collectDirect 广告入口=false
2. trap 分支条件加 `!watchingAdFromDeepLinkTask`：深链任务跳转放行，交给深链分支
   （等够任务时长 → 保留现场切回 → build742 恢复回农场主页）
3. 自然返回分支 `wasDeepLinkTask = deepLinkAppPkg != null || watchingAdFromDeepLinkTask`
   （5s内早期返回、deepLinkAppPkg 未记录时也走落地页恢复）
4. 落地页检测分支条件同样扩展（否则早期返回时会把农场包名误记为跳转App）
5. 广告会话行为完全不变（flag=false，trap 照常拦截广告误跳转，如 07:43:29
   "立即打开"广告自动跳 article.lite 被 trap 5s 恢复——该变体文案不含 build741
   匹配词，但 trap 恢复仅 ~17s，可接受）

**编译验证**: sandbox 无 Android SDK, 等 CI 构建验证。

---

### commit a5443d8 - fix: build742 深链任务完成后停在任务落地页(任务开始页),未回芭芭农场主页

**用户需求**: "uc芭芭农场中点击任务后，任务完成切到任务开始的页面，不是切到芭芭农场页面"

**根因**: 深链任务（如"去头条极速版浏览15秒"）点击"去完成"时，农场 H5 先导航到
任务落地页（任务开始页）再拉起其它App；任务完成保留现场切回农场后，WebView 停在
落地页而非农场主页。旧逻辑直接进 OPENING_TASK_LIST，在落地页上找不到"去完成"
按钮（任务列表弹窗已被 H5 导航关闭），走坐标兜底乱点，用户看到的就是"停在任务
开始页不回农场主页"。

**修复**（[AutomationController.kt](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt)）:
1. 新增 `isOnRealFarmPageForDeepLinkReturn()`: 严格判定"真"农场主页——须同时含
   "芭芭农场"标题 + 核心元素（集肥料/施肥/换种/免费领水果）。落地页只有
   "任务完成"/"得肥料"等文案，不含核心元素，不会误判。
2. 新增 `runDeepLinkReturnToFarm(attempt)`: 深链任务完成后确保回农场主页——
   ①已在农场主页（任务列表可见或标题+核心齐全）→ OPENING_TASK_LIST；
   ②农场App前台但停在落地页 → pressBack 弹出 WebView 历史栈中的落地页（不重载
   保留会话），最多2次；
   ③仍无效 → reopenFarmByDeepLink 深链重开农场页（killCurrentFirst=false 不杀进程）。
3. 三条深链完成路径全部接入 runDeepLinkReturnToFarm：
   - 自然返回分支（isOnFarmPage=true，wasDeepLinkTask 时不再直接进 OPENING_TASK_LIST）
   - 新增落地页检测分支：deepLinkAppPkg!=null 且农场App回前台但无农场关键词
     （isOnFarmPage=false，旧逻辑会一直干等超时）→ 视为任务完成并恢复回农场页
   - 定时切回分支：bringFarmAppToFront 成功后先确认在农场主页；失败走深链重开
     必然回农场页，维持原直接 OPENING_TASK_LIST
4. 不影响"我要加速"流程：adSpeedUpJumpStage=1/2 停留阶段在上方已 return，
   不会进入深链分支。

**编译验证**: sandbox 无 Android SDK, 等 CI 构建验证。

---

### commit bf52d38 - fix: build741 下载安装类广告干等90s+盲点坐标误触packageinstaller,检测到立即放弃

**用户需求**: "分析日志"（debug_test_20260822_094757.log, build739-e38c7ac, 1283行, 09:42-09:47新会话段）

**背景**: 用户尚未安装 build740（日志仍为 build739），本日志验证了 build734/735 的 RETURNING
forceKill 兜底有效（09:45:23 广告Activity温和退出3次无效→forceKill→恢复正常）。

**问题(bug,已修)**: "看广告领奖"拉起腾讯 PortraitADActivity **下载安装类广告**（"抖音极速版",
文案"点击打开或下载第三方应用"+"完成App安装，即可获得奖励",无视频无倒计时）——只有安装App
才发奖励,用户策略绝不安装,等待永远无奖励:
- 第一次(09:43:06-09:45:23): scene=AD_ENDED（findAdInstallButton 不匹配此类文案）→ 干等90s
  超时 → CLOSING_AD 找不到关闭按钮 → **盲点坐标误触下载拉起 packageinstaller(09:45:08,可能误装App!)**
  → RETURNING pressBack无效 → forceKill 兜底恢复(耗时2分17秒)
- 第二次(09:45:44): 点"我要更快拿奖"入口直接进 packageinstaller(09:45:59) → 检测系统包 pressBack
  退出 → 恢复
- 第三次(09:46:58): 又是同类广告,干等到45s用户手动停止
- 一小时段内连遇3次,广告池高频下发此类广告

**修复**:
1. [FarmAccessibilityService.kt](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt)
   新增 `isDownloadInstallAd()`: 匹配"完成App安装"/"完成APP安装"/"点击打开或下载第三方应用"
   (无歧义子集,仅下载类广告出现;视频广告安装CTA"立即安装"不含这些文案,不误伤)
2. [AutomationController.kt watchAd](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt):
   快照日志后、"更快拿奖"检测前加早期退出——`fasterRewardStage==0 && isDownloadInstallAd()` 时
   立即 forceKill杀宿主+reopenFarmByDeepLink+NAVIGATING(该手段07:41已验证100%有效),
   全程不点广告页任何元素(不盲点坐标、不点更快拿奖),不等90s。
   仅 stage==0 检测,不影响更快拿奖阶段2停留的第三方App页面。

**效果**: 下载安装类广告从"90s等待+误触安装器风险+2分钟恢复"变为"~15s放弃重开"，
且彻底消除误装App风险。广告池轮换后正常视频广告不受影响。

**编译验证**: sandbox 无 Android SDK, 等 CI 构建验证。

---

### commit 7e0559c - fix: build740 腾讯PortraitAD安装类创意广告误判TRAP_INSTALL,任务#1死循环3轮+无法完成

**用户需求**: "拉取github日志" → "分析日志"（debug_test_20260822_074553.log, build739-e38c7ac, 1011行）

**build736/737 验证情况**: 两修复均已生效——
- build737: L210/463 `parseDeepLinkStayMs: no duration hint, using default 15s, stay=20000ms` ✓
- build736: L219/472 `processTask: ad open, skip red packet detection` ✓（红包守卫生效,广告正常进WATCHING_AD）

**问题(bug,已修)**: 任务#1"看视频得巨额肥料(0/10)"点击去完成 → 腾讯 PortraitADActivity 激励视频,
广告创意为安装类("立即安装"按钮,无倒计时无点击商品文案) → 误判 TRAP_INSTALL →
pressBack×4无效(广告Activity拦截back键) → build735 forceKill兜底杀宿主+重开农场 →
currentTaskIndex 重置0 → 再点任务#1 → 同类广告 → 循环3轮+(07:41:08-07:42:29+),任务永远
无法完成(0/10),每轮浪费约20s。
- 根因: PortraitADActivity 是广告SDK自己的Activity(激励视频正在播放),"立即安装"是广告
  创意的转化CTA而非陷阱弹窗;步骤8 TRAP_INSTALL 判定(无倒计时+无isClickProductAd文案)
  未豁免此场景。真陷阱场景(误点CTA跳转商店/安装器)时 currentActivityName 已变成商店的,
  isAdActivity()=false,与本场景可区分。
- 另注: build735 的 forceKill 兜底本身工作正常(20s退出不再死循环36s+),本次是消除误判源头。

**修复** [FarmAccessibilityService.kt identifyCurrentScene 步骤8](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt):
TRAP_INSTALL 判定加第三个豁免条件 `!isAdActivity()`——当前Activity是广告SDK Activity
(PortraitAD/TTRewardVideo/HCRewardVideo/KsRewardVideo等)时,"立即安装"是广告创意CTA,
不判陷阱,落到步骤11/12 按 AD_ENDED/AD_PLAYING 处理;广告播完出现"恭喜获取奖励"后由
isAdEndedMultiSignal → CLOSING_AD 正常关闭领奖。
安全性: 即使真弹窗叠加在广告Activity上(罕见),build735 的 pressBack×4+forceKill 兜底仍在。

**效果**: "看视频得巨额肥料"安装创意广告 → 正常观看等播完(AD_ENDED多信号检测) →
"恭喜获取奖励"/关闭按钮出现 → 关闭领奖 collectedCount++ → 任务#1 从(0/10)开始正常累计,
不再 forceKill 循环。

**编译验证**: sandbox 无 Android SDK, 等 CI 构建验证。

---

### commit 9bb7ce8 - feat: build737 深链任务改为"等够任务文案时长+保留现场切回农场"(原等2秒kill重开)

**用户需求**: "进行芭芭农场任务时进入到其它应用，等待足够的时间后，切回芭芭农场应用时，只是切换回芭芭农场页面，保持切换时的现场，还是切走时的样子"——经确认选型：**等够时间+保留现场切回**，停留时长**解析任务文案**（无提示用默认值）。

**原行为**: 深链任务（去头条极速版逛逛等）进入其它App 5s检测到后只等2秒（DEEP_LINK_MAX_DURATION_MS=2000，旧需求"等2秒激活+kill"）就 `launchPlatformApp()`（HOME+杀农场进程+deep link 重开，**WebView重载，现场丢失**）+ kill 被跳转App，回的是重新加载的农场主页。
（对比："我要加速"流程 build716 已是 moveTaskToFront 保留现场切回，本次把深链任务对齐到同样机制）

**修改** [AutomationController.kt](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt):
1. 常量: `DEEP_LINK_MAX_DURATION_MS=2000` 替换为 `DEEP_LINK_DEFAULT_STAY_MS=15000`(默认)+`DEEP_LINK_STAY_BUFFER_MS=5000`(缓冲)
2. 新增字段 `deepLinkTaskStayMs`（默认20s），processTask 点击任务按钮前用 `parseDeepLinkStayMs(fullTaskText)` 解析:
   - 匹配"浏览15秒"/"逛15秒"/"停留15秒"/"观看15秒"/"滑动15s"等 关键词+数字+秒 模式 → 秒数*1000+5s缓冲
   - 无匹配或>300s(误匹配)时用默认 15s+5s=20s
3. runWatchingAd 深链分支重写:
   - 检测到深链App后等 `deepLinkTaskStayMs`（而非2s）
   - 到时用 `bringFarmAppToFront`（moveTaskToFront）**保留现场切回**——不杀农场App、WebView不重载,
     页面保持切走时的样子（任务列表弹窗原样恢复，OPENING_TASK_LIST 检测到已打开直接续处理）
   - bringFarmAppToFront 失败时（系统未返回农场任务）fallback `launchPlatformApp(killCurrentFirst=false)`（deep link拉起，不杀农场进程）
   - 被跳转App切后台后 kill 释放内存（跳过农场平台自身包名防误杀）
   - 任务完成计数对齐自然返回分支: `collectedCount++ + advanceTaskIndex()`（原为 currentTaskIndex++）
   - 自然返回分支不变：任务在其它App内自然跳回农场时取消定时切回
4. 广告超时守卫: `elapsedMs >= adMaxDurationMs` 加 `deepLinkAppPkg == null`——深链等待期间
   不受广告90s超时打断（"浏览60秒"类长任务需等 5s+65s=70s+）

**效果**: "去头条极速版浏览15秒得1000肥料"任务 → 进头条极速版停留20s → moveTaskToFront 切回UC → 农场页任务列表原样恢复 → 继续下一个任务。

**编译验证**: CI Build 737 (run#737) 构建成功, Release 已发布 APK (bbncbot-9bb7ce8.apk / full / ocr 三变体)。

---

### commit b5cb4d1 - fix: build736 红包样式广告创意误判红包弹窗,checkTaskResult每2s点击广告横幅58s死循环

**用户需求**: "分析日志"（debug_test_20260822_070102.log + debug_test_20260817_194249.log, 均 build735-da9740a）

**build735 验证情况**: 两份日志均无"点击跳转后停留"汇川广告出现（广告池随机）,该修复待后续日志验证;
194249 日志流程基本健康(签到✓/穿山甲广告✓/快手互动广告✓),快手免疫end-card在CLOSING_AD阶段
用户即手动停止,build734 RETURNING forceKill兜底未及触发(非新问题)。

**问题(bug,已修)**: 穿山甲"淘宝闪购"红包样式广告创意被误判为红包弹窗,58s死循环
- 06:59:52 task#2"看视频得巨额肥料"点去完成 → TTRewardVideoActivity(穿山甲)广告打开
- 广告创意横幅"朋友快来，淘宝闪购请客啦！领取红包吃美食啦！"(顶部[186,314][1011,455]+中部
  [474,1263][989,1358]两个节点),页面含"红包"+"领取红包"
- checkTaskResult 中 findRedPacketCloseButton 误判为红包弹窗 → "closing red packet popup"
  → 每2s手势点击横幅中心(598.5,384.5)——**点的是广告创意本身,还可能触发跳转淘宝**
- 06:59:57~07:00:55 循环29次58s无效果,广告(adActivity=true/adPlaying=true)从未进入
  WATCHING_AD 处理,07:00:57用户手动停止
- 根因: checkTaskResult 红包弹窗分支位于广告检测分支之前,且无次数上限
  (browseTask 同分支有 MAX_RED_PACKET_CLOSE_ATTEMPTS=3 防御,此处漏加)

**修复** [AutomationController.kt checkTaskResult 红包分支](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt):
1. 双层守卫:
   - 广告打开时(isAdActivity/isAdPlaying/isAdContentShown)跳过红包弹窗处理,
     让流程落到下方广告检测分支进入 WATCHING_AD(红包文案=广告创意非弹窗)
   - 次数上限 MAX_RED_PACKET_CLOSE_ATTEMPTS=3(同 browseTask 防御),
     超过后不再当红包弹窗处理,防其他误判死循环
2. 新增 taskRedPacketCloseAttempts 计数器(L114-131),
   runProcessingTask(attempt=0) 新任务开始时重置(L2259-2264)

**效果**: 红包样式广告直接进 WATCHING_AD 正常观看等待结束(与本日志第一个红果短剧广告
相同流程);农场页真红包弹窗最多点3次后继续后续检测,不再无限循环。

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit da9740a - fix: build735 汇川"点击跳转后停留"广告误判TRAP_INSTALL死循环,补完跳转停留领奖流程

**用户需求**: "分析日志"（debug_test_20260817_191553.log, build733）

**问题(bug)**: 汇川"点击跳转后停留15秒立即获奖"变体广告(千问APP)奖励丢失
- 19:15:10 HCRewardVideoActivity 打开,页面"点击跳转后停留\n15秒立即获奖",
  转化按钮"立即点击领取"(clickable=false,bounds=[166,1657][1033,1821])+"查看详情"
- identifyCurrentScene 步骤8: findAdInstallButton 匹配"查看详情"+无倒计时
  → 误判 TRAP_INSTALL → closeAdInstallPopup 无关闭按钮 → pressBack 每5s循环
- 广告 Activity 拦截 back 键,36s+ 无效果直到用户手动停止(分支无超时保护),奖励未领
- **上一轮会话中断遗留**: trapInstallBackCount 已声明未使用、isClickJumpStayAd 已声明未调用、
  守卫扩展不生效(huichuanMerchantPending 在此流程从未置true),本次全部补完

**修复**(5处):
1. [FarmAccessibilityService.kt identifyCurrentScene 步骤8](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt):
   TRAP_INSTALL 分类加 isClickProductAd() 豁免——页面含点击商品/点击跳转后停留等CTA时,
   "查看详情"是广告转化按钮而非安装陷阱
2. [identifyCurrentScene 步骤12]: AD_PLAYING 判定改用 isClickProductAd() 统一关键词
   (新增"点击跳转后停留"),消除两份列表不同步
3. [AutomationController.kt TRAP_INSTALL 分支](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt):
   接入 trapInstallBackCount,pressBack 连续4次(约20s)无效或超时
   → forceKillApp杀宿主+reopenFarmByDeepLink重开农场(同build732,已验证100%有效)
4. [huichuanMerchantPending 守卫]: 补 AD_ENDED 场景——落地页在广告Activity内
   无倒计时无CTA时判为AD_ENDED,原守卫会漏→误走AD_ENDED关闭流程放弃奖励
5. [isClickProductAd 阶段1]: 点击转化按钮后 isClickJumpStayAd() → 设
   huichuanMerchantPending=true,由守卫块在落地页等"奖励已发放"(停留15秒),
   不再2s关闭广告

**预期流程**: 广告打开→scene=AD_PLAYING→findAdProductNode回退找"立即点击领取"文本节点
→手势点击→跳转落地页→守卫等15s→"奖励已发放"→claimRewardViaCloseIcon点关闭领奖

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 210e06e(用户快照,代码已含) - fix: build734 快手扭一扭end-card广告免疫所有温和退出,RETURNING加forceKill兜底

**用户需求**: "分析日志"（debug_test_20260816_194357.log, build732-05b8ac6）

**build732 修复验证 ✓ 全部生效**:
1. TRAP_RECHARGE forceKill兜底(19:42:24-19:42:32): 第一个快手扭一扭广告误判充值陷阱,
   pressBack 4次无效后 forceKillApp杀宿主+reopenFarmByDeepLink 重开农场 **成功**
   (19:42:38回农场继续任务,共耗时约20s,不再像build730卡190s)
2. 空过渡态防误判: 本次无息屏场景,未触发(保留)

**TRAP_INTERACTIVE 分支验证 ✓**: 第二个快手广告正确识别为互动广告,
   等待15s后"countdown stuck at 10s"检测正确识别静态文案→进CLOSING_AD

**残留问题(bug,已修)**: 快手扭一扭 end-card 对**所有温和退出手段免疫**
- 19:43:18 CLOSING_AD 点'跳过'按钮 ACTION_CLICK **success** 但页面不关
- 19:43:23 8个坐标关闭点击全部无效
- 19:43:41-19:43:51 RETURNING: pressBack无效(无'确认要离开吗'弹窗),back-1/back-2坐标点击无效
- 19:43:55 用户手动停止(全程40s未退出广告)

**修复** [AutomationController.kt runReturning L6546-6566](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt):
- RETURNING 中 attempt>=3(已试pressBack+2坐标,约15s)且仍在广告Activity
  → forceKillApp杀宿主 + reopenFarmByDeepLink重开农场 + NAVIGATING
  (与build732 TRAP_RECHARGE兜底同款,本日志已验证该手段100%有效)

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 518e2a8 - fix: build733 广告SDK无填充时点击无效果被误判完成循环+findBackIcon误点宠物面板

**用户需求**: "分析日志"（debug_test_20260816_193200.log, build731-c67ca3e）

**核心发现**: 本轮会话**一个广告都没打开**（环境性问题: 广告SDK无填充）
- 19:28:37-19:29:47 "看广告领奖"直领按钮点5次,每次10s后仍在农场页(无广告Activity)
- 19:29:54-19:31:55 task#2"看视频得巨额肥料(1/10)"去完成点5次,同样无效果
- 共10次点击全部无效果,坐标正确((1005,1285)是按钮中心),是SDK侧无广告可拉

**问题1(bug,已修)**: 无效果点击被误判为"任务完成"循环重放
- 农场页本身含"已完成"文案 → isTaskCompletePage YES
- → 误判完成 → advanceTaskIndex 重放(remainingReplays=9) → 每轮约25s无效循环
- remainingReplays 9→3 直到用户手动停止(共浪费约2分钟)

**问题2(bug,已修)**: findBackIcon 误点宠物面板
- "back" 关键词 contains 匹配命中 "tree-pet-panel-pet-**back**ground-"
- 每轮退出时点击屏幕中心(615,1210)的宠物面板节点,可能触发无关UI

**修复**:
1. [AutomationController.kt](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt):
   - 新增 taskClickLeftFarm 标志(L367-376): runProcessingTask(attempt=0)重置false,
     checkTaskResult 检测到不在农场页时置true
   - isTaskCompletePage 分支加无效果守卫(L3755-3792): 仍在农场页且从未离开过农场,
     且无"点击领取"按钮/肥料到账弹窗 → 判定点击无效果:
     重试点击(≤MAX_TASK_ATTEMPTS=3次) → 仍无效果则 taskReplayRemaining=0 跳过任务
2. [FarmAccessibilityService.kt](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt):
   - findBackIcon(L3837-3864): "back"改为精确匹配(新增 findNodeByTextExact L5800-5827),
     中文"返回/返回首页"保持 contains
   - 效果: 不会再误点 tree-pet-panel-pet-background- 节点

**收益**: 广告无填充时,单任务最多浪费 3次点击(约30s)后跳过,不再9次重放约4分钟;
不再误点宠物面板。

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 05b8ac6 - fix: build732 快手广告扭一扭页误判充值陷阱pressBack死循环190s+空过渡态误判广告结束

**用户需求**: "分析日志"（debug_test_20260816_190740.log, **build730**-0c58ffc）

**注意**: 本日志测试的是 build730(build731 的 APK 当时未构建完),汇川广告部分重演 build729 纯等待问题,build731 已修复该问题待验证。

**发现1(好消息)**: 奖励最终发放了
- 19:01:42 汇川广告(55s倒计时)→点商品→2s关闭→弹窗→"返回点击商家"回广告→纯等待
- 19:04:27.178 `isAdEndedMultiSignal: YES (ad ended text detected: '奖励已发放')` — **奖励已发放文案出现**(等了2分45秒)
- 但 19:03:34 已因误判提前退出 WATCHING_AD(见发现3),奖励文案在 NAVIGATING 时才被检测到

**发现2(bug,已修)**: 快手广告"扭一扭"互动页误判 TRAP_RECHARGE,pressBack 死循环 190 秒
- 19:05:51 第二个广告(快手 KsRewardVideoActivity)"扭一扭或点击跳转详情页或第三方应用"互动页
- isRechargePage 匹配页面转化按钮文案 → scene=TRAP_RECHARGE
- clickCloseOnRechargePage 无关闭按钮 → pressBack 每5s循环,广告 Activity 拦截 back 退不出去
- **elapsed=190000ms/90000ms 超时仍不停**(TRAP_RECHARGE 分支在超时检查之前 return,无超时保护)
- 19:07:15 用户手动停止

**发现3(bug,已修)**: 47秒轮询间隙后空过渡态误判"倒计时消失"
- 19:02:47.750 → 19:03:34.599 有 47s 轮询间隙(手机息屏 pkg=com.hihonor.aod,handler 被冻结)
- 恢复后 texts=[](过渡态),findAdDurationHint=0 → "countdown disappeared"弱信号误判 AD_ENDED
- 提前进 CLOSING_AD 点关闭图标

**修复**:
1. [AutomationController.kt](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt) TRAP_RECHARGE 分支(L5286-5311):
   - 新增 trapRechargeBackCount 计数器(L1969,新广告开始重置 L4758)
   - pressBack 连续 4 次(约20s)无效或 elapsed≥adMaxDurationMs 时:
     forceKillApp 杀宿主 + reopenFarmByDeepLink 重开农场 + NAVIGATING(最可靠退出手段)
2. [FarmAccessibilityService.kt](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt) isAdEndedMultiSignal 弱信号(L1747-1761):
   - 当前页面文本 <2 个(空过渡态)时不判定"倒计时消失",等下一轮页面恢复

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit c67ca3e - fix: build731 汇川"返回点击商家"后需再点商家等奖励(纯等待90s无效)

**用户需求**: "分析日志"（debug_test_20260816_185130.log, build729-ba3371b）

**build728/729 验证** ✓：
- 18:49:43 "确认要离开吗弹窗含'点击商家后立即领奖'(第1次)" → 正确点"返回点击商家"回广告
- 18:49:59-18:51:27 huichuanMerchantPending=true 生效,isClickProductAd 自动关闭被跳过

**新问题**: 返回广告后**纯等待 90 秒,"奖励已发放"从未出现**
- 返回后页面=淘宝精选商品列表(scene=AD_PLAYING),页面内容 90s 纹丝不动
- AI progress=0% hasBar=false(无倒计时),90s 超时进 CLOSING_AD,1.3s 后用户手动停止
- **结论: build728"跳过isClickProductAd纯等待"设计错误**。
  "点击商家后立即领奖"语义=返回后需**再点击商家**,奖励才发放。

**修复** [AutomationController.kt](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt):
1. isClickProductAd 块条件恢复为 `if (service.isClickProductAd())`(去掉 !huichuanMerchantPending)
2. 弹窗处理(build731):
   - 第1次弹窗(!huichuanMerchantPending): 点"返回点击商家",
     **重置adProductClicked=false**(阶段1会再点商品=点击商家),设huichuanMerchantPending=true
   - 第2次弹窗(huichuanMerchantPending=true): 已点商家仍未领奖,
     "放弃奖励离开"退出(**防死循环**)
3. 阶段2 huichuanMerchantPending 分支(L5599-5620):
   - detectRewardGrantedText → claimRewardViaCloseIcon 点右上角关闭图标领奖
   - 15s 无奖励文案 → 点关闭退出(再弹窗走第2次分支退出)
4. claimRewardViaCloseIcon 清 huichuanMerchantPending(L4678)
5. 保留 build730 守卫(详情页形态 TRAP scene 跳过防御等待)+build729 通用奖励检测

**新流程**:
```
点商品 → 2s关闭 → 第1次弹窗"点击商家后立即领奖" → 返回点击商家
→ 回广告(列表页) → 阶段1再点商品(=点击商家) → 等待"奖励已发放"(最多15s)
  ├─ 出现 → 点右上角关闭图标 → 奖励到手 ✓
  └─ 15s超时 → 点关闭 → 第2次弹窗 → 放弃奖励离开退出(防死循环)
```

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 0c58ffc - fix: build730 修复汇川商家商品页被误判TRAP_RECHARGE导致pressBack死循环

**用户需求**: "分析日志"（debug_test_20260816_184008.log, build728-6252367）

**build728 修复验证** ✓：
- 18:39:35.650 "确认要离开吗弹窗含'点击商家后立即领奖'(奖励未触发), clicking 返回点击商家
  回广告等自然结束(不自动关闭)" — 正确点了"返回点击商家"而非"放弃奖励离开",
  huichuanMerchantPending=true 生效,isClickProductAd 自动关闭被跳过。

**新问题**: 返回广告后,商家商品详情页被误判 TRAP_RECHARGE,pressBack 死循环 25s+
- 18:39:31 findAdProductNode 5次找不到商品节点(build722 fallback: 点中心(600,1200))
- 18:39:33 2s后点右上角关闭图标 → 弹"确认要离开吗"+"点击商家后立即领奖"
- 18:39:35 build728 点"返回点击商家"回广告 ✓
- 18:39:40 广告 WebView 显示商家商品详情页(店铺/客服/加购/立即购买)
  → isRechargePage 匹配"立即购买" → scene=TRAP_RECHARGE
- 18:39:40-18:40:05 clickCloseOnRechargePage 无关闭按钮 → pressBack 每5s循环,
  页面无变化(pressBack 被 WebView 拦截),25s+ 直到用户手动停止,**奖励未领**
- 根因: huichuanMerchantPending=true 时商家商品页是预期状态(有意返回等待奖励计时),
  陷阱防御在 build729"奖励已发放"检测**之前**执行,永远轮不到领奖逻辑;
  pressBack 还可能打断奖励计时。

**修复** [AutomationController.kt](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt):
1. 提取 `detectRewardGrantedText()`/`claimRewardViaCloseIcon()` 辅助函数(L4647-4690),
   build729 分支重构为调用辅助函数(逻辑不变)
2. when(scene) 前新增 huichuanMerchantPending 守卫(L5224-5254):
   huichuanMerchantPending=true 且仍处于广告Activity 且 scene 为陷阱类
   (TRAP_RECHARGE/TRAP_ABNORMAL/TRAP_LANDING/TRAP_MINIPROGRAM)时:
   - 检测到"奖励已发放" → claimRewardViaCloseIcon 点右上角关闭图标领奖
   - 超时(adMaxDurationMs) → CLOSING_AD 多策略兜底关闭
   - 否则每5s轮询耐心等待,不触发陷阱防御

**完整流程**（用户确认的正确路径）:
点击商品 → 关闭 → "点击商家后立即领奖"弹窗 → 返回点击商家 → 商家商品页耐心等待
→ "奖励已发放"出现 → 点右上角关闭图标 → 奖励到手回农场

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit ba3371b - feat: build729 检测"奖励已发放"立即点击右上角关闭图标领取奖励

**用户需求**: "遇到奖励已发放，右边的关闭图标，需要点击关闭，就获得奖励了"

**问题**: 广告页面出现"奖励已发放"等已领奖标志时,奖励已到账,
点击右上角关闭图标退出即获得奖励。但原逻辑:
- watchAd L5832 最短等待分支 `elapsedMs < adMinDurationMs(30s) && scene != AD_ENDED` 先返回,
  "奖励已发放"关键词检测(isAdEndedMultiSignal)在 min wait 之后才执行 → 白等20s+。
- build728 汇川"返回点击商家"回广告后,奖励计时结束显示"奖励已发放",
  也要等满30s min duration 才检测,浪费时间。

**修复** [AutomationController.kt](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt) L5550-5588:
- runWatchingAd 新增分支(位于 min-wait 检查之前):
  检测到"奖励已发放/奖励已到账/领取成功/已领取奖励/肥料已到账/肥料已发放/恭喜获取奖励/恭喜获得奖励"
  且仍在广告中(isAdActivity||isAdPlaying)时:
  1. 立即 findAdCloseButton(enforceSceneWhitelist=false) 找右上角关闭图标并点击
     (找不到则 pressBack 兜底)
  2. setAdMode(false) + collectedCount++ + 进 RETURNING
- 关键词取 isAdEndedMultiSignal adEndedKeywords 的无歧义子集
  (排除"恭喜获得"/"获取奖励"/"获得肥料"等易误判落地页营销文案的泛化词)
- 守卫:仍处于广告Activity/广告播放中才触发,避免农场页/落地页文案误判

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 6252367 - fix: build728 修复汇川广告"放弃奖励离开"丢奖励(2s关闭过早+build727死循环)

**用户需求**: "放弃奖励离开，是等待的时间不够吗"

**根因分析** (debug_test_20260816_181940.log, build726, 18:18:13-18:18:22):
- 汇川广告 HCRewardVideoActivity "点击商品，领取奖励"流程:
  - 18:18:15.543 点击商品(adProductClicked=true)
  - 18:18:17.851 **2s后自动关闭广告**(sinceClick=2320ms) → 弹出"确认要离开吗"+"点击商家后立即领奖"
  - 18:18:20.094 点击"放弃奖励离开" → 奖励丢失
- **"点击商家后立即领奖"明确说明奖励未触发**,需要点击商家(非商品),2s等待不够。
- **日志铁证**: "已领取"文本bounds=[894,933][1123,1031]在广告前(18:17:46)和广告后(18:18:22)完全一致,
  任务进度未变,奖励未发放。三个连续广告(18:18/18:18/18:19)全部"放弃奖励离开",奖励从未触发。
- build727初版修复有缺陷: 点击"返回点击商家"回广告后重置adProductClicked=false,
  导致下轮又点击商品→2s关闭→弹窗→返回→点击商品→**死循环**到90s超时。

**修复** [AutomationController.kt](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt):
1. 新增 `huichuanMerchantPending` 标志(L1954)
2. 新广告开始时重置(L4694)
3. `isClickProductAd` 条件加 `&& !huichuanMerchantPending`(L5463):
   huichuanMerchantPending=true时跳过整个isClickProductAd块(不自动关闭)
4. "点击商家后立即领奖"弹窗处理(L5587-5601):
   点击"返回点击商家",**不重置adProductClicked**(保持true),设huichuanMerchantPending=true。
   下轮isClickProductAd块被跳过,不再2s自动关闭,等广告自然结束
   (isAdEndedMultiSignal检测"领取成功",或90s超时进CLOSING_AD)
5. 跳过时输出诊断日志(L5546),便于追踪等待状态

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### 2026-08-16 分析 - debug_test_20260816_181940.log ~~无需修复~~(build728修正)

**用户需求**: "分析日志"（debug_test_20260816_181940.log, build727-338adca=build726代码）

**日志分析** (debug_test_20260816_181940.log, 18:15:29-18:19:37, 约4分钟):

**build726 修复验证** ✓：无 zero-size 按钮触发(本次广告无零尺寸节点)。
**build725 修复验证** ✓：无 packageinstaller 卡死。

**流程总结** (共4个广告):
1. 第一个广告(美数摇一摇互动,40s): TRAP_INTERACTIVE 30s → 点击中心 → 跳 appmarket → kill → 回农场。正常。
2. 第二个广告(汇川点击商品,7s): 点击商品 → 2s关闭 → "确认要离开吗"+"点击商家后立即领奖"
   → 点击"放弃奖励离开" → RETURNING → isTaskCompletePage=YES。**任务完成,正常**。
3. 第三个广告(汇川点击商品,7s): 同上,任务完成。
4. 第四个广告(汇川点击商品,8s): 同上,任务完成。

**观察到的非bug现象**:
- 18:16:49 点击"去完成"任务按钮跳转到 com.hihonor.appmarket(华为应用市场),
  非广告跳转。processTask: non-ad package page, skipping task。任务本身行为,非bug。
- 18:17:36 手机息屏(pkg=com.hihonor.aod),导致 navigate 在美数广告卡6次(30s),
  最终 forceKillApp(UC)+reopenFarmByDeepLink 恢复。息屏是非代码问题。

**汇川广告"放弃奖励离开"机制确认**:
- 弹窗显示"点击商家后立即领奖"+"放弃奖励离开"
- 点击"放弃奖励离开"后 isTaskCompletePage=YES(任务完成)
- 说明点击商品后奖励已触发,"点击商家后立即领奖"是广告SDK诱导文案
- build704 的"放弃奖励离开"逻辑正确,无需修改。

**结论**: 本次日志无代码bug,所有广告完成,任务完成。无需修复,不推送。

---

### commit (待提交) - fix: build726 修复穿山甲广告零尺寸"可立即领奖"按钮反复无效点击30秒

**用户需求**: "分析日志"（debug_test_20260816_161405.log, build726-f0d9807=build725代码）

**日志分析** (debug_test_20260816_161405.log, 16:10:48-16:13:58, 约3分钟):

**build725 修复验证** ✓：无 "ad button trap" 触发,无 packageinstaller 卡死,流程顺畅。
  两个广告均正常完成,无卡死。

**新问题**: 穿山甲TTRewardVideoActivity "可立即领奖"按钮零尺寸,反复无效点击30秒
- 16:12:39 进入第二个广告(穿山甲体验类,"去体验15秒可立即领奖")
- 16:12:51 scene=AD_ENDED, isAdEndedMultiSignal=YES (claim reward button appeared)
- 16:12:52 点击"可立即领奖",bounds=[0,121][0,121] (零尺寸!),ACTION_CLICK failed
  → dispatchGesture 用 ancestor bounds [0,121][1199,2662] 计算坐标 (599.5,1391.5)
  但该坐标不是实际按钮位置,点击无效
- 16:12:57-16:13:22 反复点击7次(每5秒),全部失败(bounds一直是[0,121][0,121])
- 16:13:22 广告自动结束,检测到"领取成功",进入 CLOSING_AD

**根因**: 穿山甲体验类广告的"可立即领奖"是 WebView 内零尺寸文本节点(width=0,height=0),
  无法通过无障碍 ACTION_CLICK 或 dispatchGesture 点击。广告会自动播放结束并发放奖励,
  不需要手动点击。中间31秒反复点击零尺寸按钮是无效操作。

**修复**: [AutomationController.kt](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt) L5892:
  watchAd adEnded 分支,findClaimRewardButton 找到按钮后,检查 bounds 是否零尺寸。
  零尺寸(width<=1||height<=1)时跳过点击,只等待广告自动结束
  (isAdEndedMultiSignal 会检测"领取成功",或 max=90000ms 超时进入 CLOSING_AD)。

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit (待提交) - fix: build725 修复watchAd kill系统应用packageinstaller导致handler回调延迟8分44秒卡死

**用户需求**: "分析日志"（debug_test_20260816_160006.log, build725-d5271ec=build724代码）

**日志分析** (debug_test_20260816_160006.log, 15:41:07-16:00:04, 约19分钟):

**build724 修复验证** ✓：15:44:39 stage=1 timeout (25s) 正常触发 aborting faster reward，
  超时计数器正常工作（弹窗未出现走 !isFasterRewardPopupShown 分支 count++）。

**新问题**: watchAd kill 系统应用 packageinstaller 导致 handler 回调延迟 8分44秒
- 15:44:12 进入腾讯广告 PortraitADActivity（pkg=com.android.packageinstaller, 广告寄宿在系统安装器task中）
- 15:44:14 点击'我要更快拿奖' → stage=1 等待确认弹窗
- 15:44:39 stage=1 timeout(25s), aborting faster reward, 回到正常广告等待
- 15:44:44 watchAd: current pkg='com.android.packageinstaller' is not farm app, exiting (ad button trap)
- 15:44:44.927 reopenFarmByDeepLink: skip kill, directly relaunching UC
- 15:44:45.019 forceKillApp: killing com.android.packageinstaller (pressBackFirst=false)
- 15:44:45.142 forceKillApp: killBackgroundProcesses(packageinstaller) called
- **15:44:45.142-15:53:29 (8分44秒!) 无任何日志,handler.postDelayed 回调延迟执行**
- 15:53:29 终于执行 WATCHING_AD -> OPENING_TASK_LIST

**根因**: killBackgroundProcesses 对系统应用(packageinstaller)无效或导致系统状态异常,
  广告Activity寄宿在系统应用task中,kill系统应用风险高,导致 handler 回调延迟8分44秒。
  （第二个广告15:55:36-15:58:27也有2分51秒gap,疑似手机息屏导致,非代码问题）

**修复**: [AutomationController.kt](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt) L5109:
  watchAd "ad button trap" 分支,对 com.android.* 系统应用包名,不 kill,只 pressBack 退出广告Activity。
  ```kotlin
  val isSystemPkg = currentPkg.startsWith("com.android.") || currentPkg.startsWith("android")
  if (isSystemPkg) {
      debugLog("watchAd: system pkg '$currentPkg' detected, pressBack instead of kill (avoid system instability)")
      service.pressBack()
  } else {
      service.forceKillApp(currentPkg, pressBackFirst = false)
  }
  ```

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit (待提交) - fix: build724 修复faster reward stage=1弹窗出现但无允许按钮时无限重试+任务完成未退出

**用户需求**: "分析日志"（debug_test_20260816_152759.log, build=build723-1d54f90 注意:1d54f90是build722的commit,用户未装上build723的APK）

**日志分析** (debug_test_20260816_152759.log, 15:08:12-15:18:32, 约10分钟):

**问题1（第一个广告 15:11:56-15:16:15, 持续4分19秒）**:
- 15:11:58 点击'我要更快拿奖' → stage=1 等待确认弹窗
- 15:12:19 isFasterRewardPopupShown=YES,但 findFasterRewardAllowButton 找不到
  (skip '继续了解详情',广告CTA不是确认按钮) → "retrying" 分支
- 15:12:28 isTaskCompletePage=YES(任务已完成!) 但 stage=1 不检查,继续等
- 15:12:28.567 stage=1 timeout(25s),fasterRewardStage=4,回到正常广告等待
- 15:12:33 页面已变化,isTaskCompletePage=false,isRechargePage 误判=YES
  → scene=TRAP_RECHARGE,点击"关闭"(实际点农场页元素),页面状态混乱
- 15:12:36-15:16:15 scene=AD_ENDED 干等 90s 超时才 CLOSING_AD
  (15:13:10-15:15:57 有2分47秒 gap,手机息屏 pkg=com.hihonor.aod)

**问题2（第二个广告 15:17:05-15:18:24, 持续1分19秒）**:
- 15:17:07 点击'我要更快拿奖' → stage=1 等待确认弹窗
- 15:17:33-15:18:18 (45秒) isFasterRewardPopupShown=YES 但 findFasterRewardAllowButton
  skip '继续了解详情' → "retrying" 分支无限重试,无超时
  (fasterRewardStage1WaitCount 只在 !isFasterRewardPopupShown 分支才 count++)
- 15:18:18 stage=1 timeout(25s)才 abort,但 elapsed=135000ms 已超 max=90000ms
- 15:18:24 scene=AD_ENDED elapsed=140000ms,直接 CLOSING_AD

**根因1**: stage=1 等待期间任务可能已完成(确认弹窗未出现但奖励已发放),
  但 stage=1 不检查 isTaskCompletePage,继续等确认弹窗,错过退出时机。
  timeout 后页面已变化,isRechargePage 误判,点击"关闭"导致页面混乱,干等90s。

**根因2**: 弹窗出现但 findFasterRewardAllowButton 返回 null 时(只有"继续了解详情"广告CTA),
  retry 分支没有 count++ 和超时检查,无限重试直到 WATCHING_AD 90s 超时。

**修复**: [AutomationController.kt](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt):
1. stage=1 开头增加 isTaskCompletePage 检查(L4745):任务已完成时直接退出
   (close/back icon + advanceTaskIndex),不继续等确认弹窗
2. stage=1 retry 分支增加超时(L4793):复用 fasterRewardStage1WaitCount,
   超过 4 次重试(约8s)仍无允许按钮,放弃 faster reward(stage=4)

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

**注意**: 推送策略改为 fetch+rebase（不再 --force）,避免覆盖用户通过 API 上传的日志 commit。

---

### commit (待提交) - fix: build723 修复watchAd scene=AD_ENDED时仍按adMinDurationMs等待导致20分钟无日志+诊断盲区

**用户需求**: "分析日志"（下载 debug_test_20260811_085303.log 后分析）

**日志分析** (build723=build722代码, debug_test_20260811_085303.log, 08:31:24-08:53:00, 约21分36秒):

**build722 修复验证** ✓（第一个广告穿山甲"去体验9秒"正确处理 scene=AD_PLAYING → REWARD_POPUP → CLOSING_AD → RETURNING → PROCESSING_TASK,全程未卡死）。
**build721 修复验证** ✓（return: ad activity detected, pressBack instead of reopenFarmByDeepLink,UC 保活成功）。

**新问题**: watchAd 第二个广告(快手 KsRewardVideoActivity 互动广告) scene=AD_ENDED 后 20 分钟无日志
- 08:33:39 processTask 进入第二个广告 → WATCHING_AD（快手 KsRewardVideoActivity）
- 08:33:42 scene=TRAP_INTERACTIVE(摇一摇), elapsed=0ms, download button not found, waiting
- 08:33:47 scene=AD_ENDED, elapsed=5000ms ← 广告已结束(无倒计时)
- 08:33:47-08:52:57 (20分钟!) 无任何 watchAd debugLog
- 08:52:57.530 findAdDurationHint: found countdown '10秒'（疑似广告页面恢复或新广告）
- 08:52:57.552 watchAd: scene=AD_PLAYING, elapsed=10000ms（elapsed 被重置,说明 08:52:47 左右有新的 runWatchingAd(0L) 调用,来源不明）
- 08:53:00 用户手动停止

**根因1(诊断盲区)**:
watchAd L5697 "最短等待时间未到" 分支,elapsedMs(5000) < adMinDurationMs(12000) 时进入此分支,
但诊断日志条件 `elapsedMs % 15000L < adEndCheckIntervalMs` 在 elapsed=5000/10000 时不满足
(5000 < 5000 = false),且 Log.d 不上传到 debug.log,导致大量轮询无日志,看起来像卡死。

**根因2(逻辑缺陷)**:
scene=AD_ENDED 说明广告已结束(无倒计时+在广告Activity),但仍按 adMinDurationMs 等待。
广告已结束时应尽快进入广告结束检测(isAdEndedMultiSignal)并关闭,不需要等满 min duration。

**修复**: [AutomationController.kt](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt) L5697:
- 条件改为 `elapsedMs < adMinDurationMs && scene != PageScene.AD_ENDED`
  scene=AD_ENDED 时跳过 min wait,直接进入广告结束检测
- 诊断日志改为每次都输出(移除 `% 15s` 条件),确保 runWatchingAd 调度可追踪

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit (待提交) - fix: build722 修复腾讯广告PortraitADActivity"点击广告拿奖励"scene误判AD_ENDED干等20秒

**用户需求**: "下载最新日志" → 下载 debug_test_20260811_081229.log 后"分析日志"

**日志分析** (build722=build721代码, debug_test_20260811_081229.log, 08:11:29-08:12:25):

**build721修复验证** ✓（本日志未触发 navigate !isFarmAppInForeground 分支,但 UC 全程未卡死桌面）。

**新问题**: 腾讯广告 PortraitADActivity "点击广告拿奖励" scene=AD_ENDED 干等 20 秒
- 08:11:49 点击'看广告领奖' → 跳转腾讯广告 PortraitADActivity（非淘宝,build694 修复点未触发）
- 08:11:59 collectDirect 正确识别为广告 → WATCHING_AD ✓
- 08:12:01 watchAd: scene=AD_ENDED, elapsed=0ms ← 一开始就 AD_ENDED!
  - isAdPlaying()=true（在广告 Activity）
  - findAdDurationHint()=0（无倒计时）
  - 页面不含"去体验"CTA（build718 修复点未触发）
  - → scene=AD_ENDED（identifyCurrentScene L1880）
- 08:12:01-08:12:25 isClickProductAd=true（页面含"点击广告拿奖励"等文字）
  - findAdProductNode()=null（商品节点在 WebView 内不可访问,clickable=false）
  - "点击商品 ad detected but no clickable product node, retrying in 2s" 循环
  - 每 2s 重试,elapsed 0→20000ms,无超时兜底
- 08:12:25 用户手动停止（WATCHING_AD -> STOPPING）

**根因**:
1. identifyCurrentScene L1867：广告 Activity 内无倒计时时直接返回 AD_ENDED。
   build718 只修复了"去体验"CTA,未覆盖"点击商品"/"点击广告拿奖励"CTA。
   这类广告需点击商品/广告才能领奖,广告还在播放,应 scene=AD_PLAYING。
2. watchAd isClickProductAd 分支 L5396：findAdProductNode 找不到节点时每 2s 重试,
   无超时上限。腾讯广告商品在 WebView 内不可访问(clickable=false),永远找不到 → 干等。

**修复**:
1. [FarmAccessibilityService.kt](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt) L1872-1877:
   - identifyCurrentScene 在广告 Activity 无倒计时时,增加"点击商品"/"点击广告拿奖励"等
     isClickProductAd 文字检测,命中则 scene=AD_PLAYING（与 build718 "去体验"修复一致）。
2. [AutomationController.kt](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt):
   - 新增 adProductNodeFindFailCount 计数器（L1935,声明 + L4668 watchAd 初始化重置）
   - watchAd L5396-5416：findAdProductNode 连续失败 >= 5 次(约10s)后,改用
     dispatchGestureClick(600,1200) 点击屏幕中部触发奖励,标记 adProductClicked=true
     进入阶段2等 2s 后关闭广告,避免无限重试。
   - checkTaskListOpened L2002-2018：同步修复（与 watchAd 一致）。

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit (待提交) - fix: build721 修复navigate杀UC后Honor后台限制导致UC重启失败卡死桌面

**用户需求**: "分析日志"

**日志分析** (build721=build720代码, debug_test_20260811_080219.log, 08:00:55-08:02:17):

**build720修复验证** ✓（本日志未进入广告阶段,未触发 build720 修复点）。

**新问题**: navigate 杀 UC 后 Honor 后台限制导致 UC 重启失败,卡死桌面
- 08:01:25 点击'看广告领奖' → 跳转淘宝 TMSActivity(非广告)
- 08:01:35 collectDirect build694 修复✓: forceKillApp(taobao)+launchPlatformApp(killCurrentFirst=false) 回 UC 农场
- 08:01:40 回到 UC 农场但 found 0 direct buttons → AI 视觉超时 15s
- 08:01:55 AI 视觉超时回退 task list 时又检测到淘宝 TMSActivity → NAVIGATING
- 08:01:57 farm app not in foreground → `reopenFarmByDeepLink()` 默认 killCurrentFirst=true
  - 08:01:57.741 killing com.ucmobile.lite ← 杀 UC!
  - 08:01:57.799 opened deeplink for UC
- 08:02:03 activeRootPkg='com.hihonor.android.launcher' ← UC 没起来,卡在桌面!
- 08:02:04-08:02:17 navigate stepTab 找'芭芭农场'全节点 too large [0,0][1200,2241](桌面节点),重试5次失败 → STOPPING

**根因**: navigate `!isFarmAppInForeground()` 分支调用 `reopenFarmByDeepLink()` 默认 `killCurrentFirst=true`,
会先 HOME + kill UC。UC 被杀后 Honor 后台启动限制导致 deep link 重启失败,卡死在桌面。
与 build692 修复 collectDirect 时发现的问题完全一致（"UC 被杀后重启可能因 Honor 后台限制失败,卡死在桌面"）,
但 navigate 此处仍用默认 killCurrentFirst=true,未同步修复。

**修复**: [AutomationController.kt](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt) L1041:
- `service.reopenFarmByDeepLink()` → `service.reopenFarmByDeepLink(killCurrentFirst = false)`
- 不杀 UC,直接用 deep link 拉起 UC 农场页。UC 进程未被 kill,deep link 可正常拉起到前台,避免 Honor 限制。
- 即使 UC 进程已被系统回收(非 kill),deep link 冷启动也不受 Honor 限制影响。

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit (待提交) - fix: build720 修复"领取成功"弹窗循环点击45秒,奖励已领取后直接关闭广告退出

**用户需求**: "分析日志"

**日志分析** (build720=build719代码, debug_test_20260809_094239.log, 09:42:06-09:42:37):

**build719修复验证** ✓:
- isAdLandingPage不再误判广告Activity为落地页(无TRAP_LANDING)
- "去体验"CTA正确识别scene=AD_PLAYING

**新问题**: "领取成功"弹窗循环点击45秒
- 09:42:06 广告播放完毕,显示"领取成功"弹窗(clickable=false)
- 09:42:07 isAdEndedMultiSignal=YES(claim reward button appeared)
  - findClaimRewardButton找到"领取成功"的可点击父节点(text='',bounds=[722,202][982,264])
  - watchAd点击该节点,弹窗不消失(已领取,点击无效)
- 09:42:12→09:42:34 每5秒重复点击同一节点,循环45秒
- 09:42:37 用户手动停止

**根因**: isAdEndedMultiSignal中强信号3(findClaimRewardButton)在强信号4("领取成功"文本检测)之前执行
- findClaimRewardButton匹配到"领取成功"的可点击父节点(text='')
- "领取成功"文本节点skip检查只看node自身text,不看子节点text
- 导致findClaimRewardButton返回可点击父节点,重复点击无法关闭弹窗

**修复**:
1. [FarmAccessibilityService.kt](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt) L1706-1756:
   - isAdEndedMultiSignal:将"领取成功"等已领取标志文字检测(原强信号4)提到findClaimRewardButton(原强信号3)之前
   - 如果"领取成功"已显示,直接返回true(广告已结束),不调用findClaimRewardButton
2. [AutomationController.kt](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt) L5740-5758:
   - watchAd adEnded处理:先检测"领取成功"等已领取标志
   - 如果已领取,直接进入CLOSING_AD关闭广告,不再调用findClaimRewardButton点击
   - 避免误点"领取成功"可点击父节点导致循环

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit (待提交) - fix: build719 修复isAdLandingPage误判广告Activity为落地页+互动广告"上滑或点击查看"干等60秒

**用户需求**: "分析日志"

**日志分析** (build719=build718代码, 两份日志):

**问题1** (debug_test_20260809_092433.log, 09:23:18-09:24:30):
- 穿山甲 TTRewardVideoActivity 互动体验广告
- 页面含"上滑或点击查看"提示,需要用户互动才能继续播放/结束
- build718修复验证✓: scene=AD_PLAYING(不再误判AD_ENDED)
- 但bot不互动 → scene=AD_PLAYING干等60秒直到用户手动停止
- texts=[穿山甲, 上滑或点击查看, 钟, 团, 平时懒得下楼买东西, 平, 时]

**问题2** (debug_test_20260809_092828.log, 09:27:49-09:28:26):
- 穿山甲 TTRewardVideoActivity 激励视频广告刚进入(elapsed=0ms)
- 页面有4个"领取红包"按钮(clickable=false)
- isAdLandingPage matchCount=2 → 误判为广告主落地页 → TRAP_LANDING
- pressBack退出 → 卡在com.byazt.jz.ew Activity → NAVIGATING反复waiting → 用户手动停止

**修复**:
1. [FarmAccessibilityService.kt](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt) L4220:
   - isAdLandingPage()增加 `if (isAdActivity()) return false`
   - 在激励视频广告Activity内不判定为落地页(落地页是广告SDK跳转到的外部浏览器/应用页面)
   - 广告Activity内的endcard由isAdEndedMultiSignal/findClaimRewardButton处理
2. [AutomationController.kt](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt) L5313-5334:
   - 新增adSwipeHintClicked标记(每轮广告重置,L4634)
   - 检测到"上滑或点击查看"提示且elapsed>=15秒时,点击屏幕中部(600,1200)触发广告继续
   - 避免互动广告干等60秒

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit (待提交) - fix: build718 修复"去体验"广告干等90秒+closeAd误触"确定要退出吗"弹窗死循环10分钟

**用户需求**: "分析日志"

**日志分析** (build717, debug_test_20260809_091652.log, 09:05:27-09:16:50):

**问题1（根因）**: "去体验15秒可立即领奖"广告被误判 AD_ENDED,干等90秒
- 字节穿山甲 TTRewardVideoActivity 体验类广告
- texts含"去体验15秒可立即领奖"CTA
- findAdDurationHint 因含"可立即领奖"跳过倒计时检测返回0
- identifyCurrentScene L1855-1859: 在广告Activity但无倒计时 → AD_ENDED
- watchAd 一直 scene=AD_ENDED,干等90秒(09:05:29→09:07:07)

**问题2（死循环）**: closeAd误触"确定要退出吗"弹窗,循环10分钟
- 09:07:07 closeAd 点击坐标(ad-close-0~7)误触 → 触发"体验几秒就能领奖～确定要退出吗？"弹窗
- findClaimRewardButton "确定"关键词匹配"确定要退出吗"提示文字 → 返回该节点
- isAdEndedMultiSignal 误判广告结束 → CLOSING_AD → closeAd点坐标 → RETURNING → pressBack
- → 又触发"确定要退出吗"弹窗 → NAVIGATING → isAdEndedMultiSignal又YES → 死循环
- 09:07:30→09:16:50 循环10分钟,用户手动停止

**修复**: [FarmAccessibilityService.kt](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt)
1. identifyCurrentScene 修复(L1858-L1869):
   - 页面含"去体验"CTA时,广告还在播放(需体验才能领奖),scene=AD_PLAYING
   - 避免误判 AD_ENDED 导致干等90秒
2. findClaimRewardButton 修复(L4560-L4572):
   - 对"确定"关键词排除退出确认弹窗提示文字(含"退出吗"/"离开吗")
   - 避免匹配"确定要退出吗？"导致 isAdEndedMultiSignal 误判广告结束
   - 打断死循环

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit (待提交) - fix: build717 isClickProductAd扩展检测"点击广告拿奖励",避免干等90秒+closeAd误触跳转京东

**用户需求**: "分析日志"

**日志分析** (build717, debug_test_20260809_083541.log, 08:32:33-08:35:39):

**问题1（核心）**: 广告"点击广告拿奖励"未被识别,干等90秒
- 08:32:33 进入腾讯广告 PortraitADActivity
- texts=[点击广告，即可获得奖励, 点击广告拿奖励, 京东全球购, 香港好物节, 点击打开或下载第三方应用]
- isClickProductAd() 只检测"点击商品",没检测"点击广告拿奖励" → 返回 false
- watchAd 一直 scene=AD_ENDED,干等90秒(08:32:36→08:34:20)

**问题2**: closeAd 误触跳转京东
- 08:34:22 closeAd 点击多个坐标(ad-close-0~7),误触广告内容区域
- 08:34:44 pkg=com.jingdong.app.mall(跳转京东了)

**问题3**: 京东首页被误判充值页
- 08:35:19 isRechargePage: YES, sample=[推荐, 推荐, 家品8折, 医药, 医药]

**根因**: isClickProductAd() 只检测"点击商品",没检测"点击广告拿奖励"
- 如果识别出这种广告类型并主动点击广告内容,就不会干等90秒
- 也不会在 closeAd 时误触跳转京东

**修复**: [FarmAccessibilityService.kt](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt)
1. isClickProductAd() 扩展检测(L3685-L3693):
   - 新增"点击广告拿奖励"/"点击广告，即可获得奖励"/"点击广告拿"匹配
   - 识别腾讯PortraitADActivity的"点击广告拿奖励"类型广告
2. findAdProductNode() 排除提示文字(L3778-L3779):
   - 排除含"点击广告"的节点(提示文案,不是商品)
   - 避免误点提示文字

**修复后流程**: watchAd 识别"点击广告拿奖励" → findAdProductNode 找可点击广告区域 → 点击 → 2秒后关闭 → 弹出"确认要离开吗" → 点击"放弃奖励离开" → 完成

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit (待提交) - fix: build716 恢复"我要加速"点击,跳转停留10秒后切芭芭农场到前台(不重启)领奖

**用户需求**: "我要加速，也需要点击，你需要等待指定的时间后回到芭芭农场广告点击时的页面领取，不是关闭芭芭农场重新打开，只有在原来店里我要加速时的页面才能领取奖励"

**用户补充**: "我们切换app后，芭芭农场app只是切换到后台，不是关闭，等待多少秒后，我们需要把芭芭农场切到前台，是切到前台，不是重新打开"

**日志分析** (build713, debug_test_20260809_064604.log):
- build715 错误回退:移除了"我要加速"匹配,导致广告无法通过跳转加速
- 用户澄清:"我要加速"确实需要点击,点击后跳转淘宝/闲鱼是正常流程
- 关键:不能关闭芭芭农场重新打开(会丢失广告会话),必须把后台的芭芭农场切到前台
- 切前台用 moveTaskToFront,不是 launchPlatformApp(会重启)、不是 pressBack(可能无效)

**修复**:
1. [AutomationController.kt](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt)
   - 恢复"我要加速"匹配,优先"点我加速"(不跳转),其次"我要加速"(跳转)
   - 新增"我要加速"跳转状态机 adSpeedUpJumpStage(0/1/2) + adSpeedUpJumpPkg + adSpeedUpJumpTimeMs
   - 新增常量 SPEED_UP_JUMP_STAY_MS = 10000L(停留10秒)
   - 点击"我要加速"后设 stage=1,记录跳转目标App
   - stage=1 停留处理:首次记录包名时间戳,停留期间检测陷阱页(充值/交易),满10秒切前台
   - 停留满10秒后调用 bringFarmAppToFront(切前台,不重启、不pressBack,保留广告会话)
   - build714的"非农场App退出"检测放行stage=1
2. [FarmAccessibilityService.kt#bringFarmAppToFront](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L6187-L6223)
   - 新增方法:用 ActivityManager.moveTaskToFront 把后台芭芭农场任务切到前台
   - 查找芭芭农场App的 taskId,用 MOVE_TASK_WITH_HOME flag 切前台
   - 不重启、不丢失广告会话,回到原广告页面继续领奖
   - 失败时 fallback 到 pressBack
3. [AndroidManifest.xml](file:///workspace/app/src/main/AndroidManifest.xml#L18)
   - 新增 REORDER_TASKS 权限(moveTaskToFront 需要)

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit (待提交) - fix: build715 移除"我要加速"匹配,该文案是穿山甲陷阱CTA导致跳转淘宝/闲鱼

**用户需求**: "分析日志"

**日志分析** (build713, debug_test_20260809_064604.log, 06:42:17-06:46:02):

**build714 验证**: ✓ watchAd 检测到跳转并退出(3次都成功)
- 06:42:19 `current pkg='com.taobao.taobao' is not farm app, exiting (ad button trap)`
- 06:44:16 `current pkg='com.taobao.taobao' is not farm app, exiting (ad button trap)`
- 06:44:57 `current pkg='com.taobao.idlefish' is not farm app, exiting (ad button trap)`

**问题 (严重)**: "我要加速"是穿山甲 TTRewardVideoActivity 的陷阱CTA,点击后跳转淘宝/闲鱼
- text='我要加速' bounds=[212,1535][989,1738] clickable=false(大区域横幅,非真正按钮)
- 06:42:17 点击"我要加速" → 06:42:19 跳转淘宝 → build714退出 → UC重启卡launcher
- 06:44:14 点击"我要加速" → 06:44:16 跳转淘宝 → build714退出
- 06:44:49 点击"我要加速" → 06:44:57 跳转闲鱼 → build714退出 → UC重启卡launcher → 用户手动停止
- 根因:每轮广告 adSpeedUpClicked 重置,每次进入新广告都点"我要加速"→每次都跳转
- 广告白看无法领奖励,还触发UC重启卡死

**根因**: build700 扩展匹配"我要加速"(用户当时要求"应该点击我要加速")
- 但日志证明"我要加速"在 TTRewardVideoActivity 里是陷阱CTA(clickable=false 大横幅)
- 点击后跳转淘宝/闲鱼,不是加速广告倒计时
- "点我加速"(KsRewardVideoActivity)才是真正加速按钮

**修复**: [AutomationController.kt#L5333-L5354](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L5333-L5354)
- 移除"我要加速"匹配,只保留"点我加速"
- 不点击"我要加速",广告正常播放到 minDuration 后检测结束领奖励
- 避免跳转陷阱,避免UC重启卡死

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit (待提交) - fix: build714 watchAd检测跳转到非农场App(千问)并退出,避免卡死误杀UC

**用户需求**: "分析日志"

**日志分析** (build712, debug_test_20260808_165631.log, 10:12:11-10:29:03):

**build713 验证**: 本次日志 build=build712,build713 未部署。本次未遇到"我要更快拿奖"场景,未触发。

**问题 (严重)**: 点击"我要加速"跳转千问App,watchAd 没检测到跳转,卡死5分钟,误杀UC
- 10:12:11 点击"我要加速"(穿山甲广告按钮) → 10:12:18 pkg=com.aliyun.tongyi(千问App)
- 10:12:18-10:12:59 一直在千问App里等待(scene=AD_ENDED,因 isAdActivity()=true 缓存了旧值)
- 10:12:59 灭屏(com.hihonor.aod),卡5分钟
- 10:17:03 亮屏,findAdDurationHint 误把锁屏"10"当倒计时 → scene=AD_PLAYING
- 10:27:40 误判广告结束 → closeAd 在千问页点击8个位置无效
- 10:28:00-25 pressBack 3次无效,还在千问
- 10:29:00 forceKillApp 误杀UC(com.ucmobile.lite),应kill千问 → STOPPING

**根因**: isAdActivity() 基于 currentActivityName(缓存值),跳转千问后仍返回 true
- isNonAdPage() 因 isAdActivity()=true 而返回 false,没检测到跳转到非农场App
- watchAd 没有"当前包名是否是农场App"的检测,一直在千问App里等待
- 最终 navigate 流程的 forceKillApp 写死了杀UC,而非杀当前前台App(千问)

**修复**: [AutomationController.kt#L4882-L4916](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L4882-L4916)
- watchAd 主循环中(fasterRewardStage when 块之后、scene 判断之前),检测 getCurrentWindowPackage()
- 如果当前包名不是农场App(且不是系统UI launcher/aod/systemui),说明被广告按钮带偏
- 退出当前App:launchPlatformApp(农场, killCurrentFirst=false) 拉起农场覆盖 + forceKillApp(当前App) kill
- 跳过任务(currentTaskIndex++),进入 OPENING_TASK_LIST 重新导航
- fasterRewardStage=2 停留阶段已在 when 块内 return,不会走到此检测,不受影响

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit (待提交) - fix: build713 排除"继续了解详情"避免误点广告CTA导致误杀UC卡死

**用户需求**: "分析日志"

**日志分析** (build711, debug_test_20260808_095101.log, 09:48:36-09:50:58):

**build712 验证**: 本次未遇到"可立即领奖"体验倒计时场景,未触发。

**问题 (严重)**: "我要更快拿奖"流程中,误点"继续了解详情"广告CTA,新App包名未记录,16秒后误杀UC,卡死
- 09:48:36 点击"我要更快拿奖" → stage=1
- 09:48:41 findFasterRewardAllowButton: found '继续' ← 实际匹配到"继续了解详情"(广告CTA)
- 09:48:41 点击"继续了解详情" → stage=2
- 09:48:46-56 faster reward staying in new app ← 但 fasterRewardAppPkg=null(没有跳转到新App)
- 09:49:01 16s elapsed, killing new app 'null' + activating farm ← 包名是null
- 09:49:01 forceKillApp: killing com.ucmobile.lite ← **误杀UC!**
- 09:49:01 no pkg recorded, pressing back ← pressBack也无效
- 09:49:06 activeRootPkg='com.hihonor.android.launcher' ← UC被杀后没重启
- 09:49:07-50:56 waiting for '恭喜获得奖励提升' popup (stage=3) ← 一直等,卡死
- 09:50:58 用户手动停止

**根因**: findFasterRewardAllowButton 用 findNodeByText(root, "继续") 匹配
- findNodeByText 用 contains 匹配,命中"继续了解详情"(包含"继续")
- "继续了解详情"是广告CTA按钮,点击后在广告内打开详情页,不跳转到新App
- fasterRewardAppPkg 未记录(null),16秒后误杀UC(ForceKillApp(com.ucmobile.lite))
- UC被杀后deep link拉起失败,卡死在launcher等待"恭喜获得奖励提升"弹窗

**修复**: [FarmAccessibilityService.kt#L2443-L2467](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L2443-L2467)
- findFasterRewardAllowButton 中,"继续"关键词排除"继续了解详情"等长文本
- 真正的"继续"按钮文本应该就是"继续"两个字,不应包含"了解详情"等广告文案
- 排除条件:kw=="继续" && (nodeText.contains("了解详情") || nodeText.length > 4)

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 7e79171 - fix: build712 排除体验倒计时中的"可立即领奖"提示文案避免穿山甲小游戏广告循环点击

**用户需求**: "分析日志"

**日志分析** (build710, debug_test_20260808_094210.log, 09:41:06-09:42:08):

**build711 验证**: 本次未遇到"确认要离开吗"弹窗场景,未触发。

**问题 (严重)**: 穿山甲"天降福利小游戏"广告,反复点击"可立即领奖"文案无效,循环60秒,用户手动停止
- 09:41:06 进入穿山甲广告 TTRewardVideoActivity
- 09:41:08 text='可立即领奖' bounds=[622,975][989,1064] clickable=false ← 提示文案,不可点击
- 09:41:08 text='去体验15秒可立即领奖' bounds=[543,176][1015,235] clickable=false
- 09:41:28 isAdEndedMultiSignal: YES (claim reward button appeared) ← 误判广告结束
- 09:41:28 点击 text='可立即领奖' bounds=[641,929][1005,1021] clickable=false ← gesture 点击无效
- 09:41:34-42:04 反复点击同一"可立即领奖"文案 8 次,都无效 ← 循环卡死
- 09:42:08 用户手动停止

**根因**: findClaimRewardButton 用"可立即领奖"关键词匹配命中 clickable=false 的文本节点
- "可立即领奖"是广告中的提示文案(clickable=false),不是真正的领取按钮
- 页面显示"再体验4秒可立即领奖" ← 说明还需体验4秒,广告没结束
- isAdEndedMultiSignal 强信号3 因 findClaimRewardButton 返回非null而返回 true ← 误判
- watchAd 通过 gesture 点击坐标无效,页面不变
- isAdEndedMultiSignal 重复触发→重复点击→循环60秒

**修复**: [FarmAccessibilityService.kt#L4517-L4534](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L4517-L4534)
- findClaimRewardButton 中,如果"可立即领奖"节点 clickable=false
- 且页面有"再体验"开头的文案(说明还在体验倒计时中)
- 跳过该节点(不是真正的领取按钮,只是提示文案)
- 真正的领取按钮应该是 clickable=true 的节点

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 1fc4a83 - fix: build711 点击放弃奖励离开后清除adModeFlag并进入RETURNING避免误判充值页

**用户需求**: "分析日志"

**日志分析** (build709, debug_test_20260808_092128.log, 09:20:57-09:21:25):

**build703 验证** (点击商品后2s关闭): ✓
- 09:20:57 点击商品 → 09:20:59 `closing ad window 2s after product click (sinceClick=2213ms)`

**build704 验证** (确认要离开弹窗): ✓
- 09:21:01 `watchAd: 确认要离开吗弹窗, clicking 放弃奖励离开`
- 09:21:01 点击"放弃奖励离开"成功

**问题 (严重)**: 点击"放弃奖励离开"后回到农场主页,adModeFlag未清除,误判为充值页,卡死
- 09:21:01 点击"放弃奖励离开"成功 → 广告关闭,回到农场主页
- 09:21:04 `isRechargePage: YES` ← **误判!** 农场主页被误判为充值页
- 09:21:04 `scene=TRAP_RECHARGE` ← 误判
- 09:21:04 `clickCloseOnRechargePage: found close button by text='关闭'` ← 在农场主页误点"关闭"
- 09:21:06-22 `scene=AD_ENDED` 但 `adPlaying=true` 标记未清除,卡在 watchAd 等待
- 09:21:25 用户手动停止

**根因**: 点击"放弃奖励离开"后,未清除 adModeFlag 就继续 runWatchingAd 轮询
- 下一轮 runWatchingAd 时,页面已回到农场主页,但 adModeFlag=true
- isOnFarmPage() 第695行 `if (adModeFlag) { return false }` → 返回 false
- isRechargePage() 因 isOnFarmPage()=false 而继续检查
- 农场主页文本(含"下单得")被误判为充值页 → scene=TRAP_RECHARGE
- clickCloseOnRechargePage 在农场主页误点"关闭"按钮,卡死

**修复**: [AutomationController.kt#L5275-L5295](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L5275-L5295)
- 点击"放弃奖励离开"后,立即清除 adModeFlag(setAdMode(false))
- 计入 collectedCount(肥料已在点击商品时获取)
- 进入 RETURNING 流程,不再继续 runWatchingAd 轮询

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 5f00c83 - fix: build710 RETURNING在广告Activity中时pressBack而非forceKillApp(UC)避免卡死

**用户需求**: "分析日志"

**日志分析** (build708, debug_test_20260808_064321.log, 06:42:27-06:43:17):

**build709 验证**: 未遇穿山甲 endcard,未触发。

**build707 验证**: ✓ findAdCloseButton 找到"跳过"按钮
- 06:42:48 performClickSafe: text='跳过' bounds=[1019,162][1109,224] clickable=true → ACTION_CLICK success

**问题 (严重)**: 快手"扭一扭"广告"跳过"按钮点击成功但广告没关闭,RETURNING → forceKillApp(UC) → 卡死
- 06:42:48 点击"跳过"成功,但广告没关闭(假跳过按钮)
- 06:42:53 策略1 坐标点击 8 个位置都无效
- 06:43:03 isInteractiveAdPage: YES ← 广告还在
- 06:43:08 所有策略失败 → RETURNING
- 06:43:10 runReturning attempt=0 → reopenFarmByDeepLink() → forceKillApp(UC)
- 06:43:15-17 UC 无法回到前台,卡在 com.hihonor.android.launcher
- 06:43:17 用户手动停止

**根因**: runReturning attempt=0 直接调用 reopenFarmByDeepLink()
- reopenFarmByDeepLink 内部 forceKillApp(UC) 杀掉 UC 进程
- UC 被杀后 deep link 拉起 UC 浏览器失败(可能需要浏览器进程已存在)
- 卡在 launcher,用户手动停止

**修复**: [AutomationController.kt#L5840-L5870](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L5840-L5870)
- runReturning attempt=0 时,如果仍在广告 Activity 中(isAdActivity()=true)
- 不用 reopenFarmByDeepLink(会杀 UC),改为 pressBack 退出广告 Activity
- pressBack 对快手广告通常会弹出"确认要离开吗?"弹窗
- 下一轮 RETURNING 会通过 findAbandonRewardButton 点击"放弃奖励离开"退出
- 只有不在广告 Activity 时才用 reopenFarmByDeepLink 重开农场

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 50d5a14 - fix: build709 排除"领取成功"静态文案避免穿山甲广告endcard循环点击

**用户需求**: "分析日志"

**日志分析** (build707, debug_test_20260806_074253.log, 07:42:07-07:42:51):

**build708 验证**: 本次未遇到跨 App 浏览任务,未触发。

**问题 (严重)**: 穿山甲广告"领取成功"endcard 后循环点击,用户手动停止
- 07:42:07 进入穿山甲广告 TTRewardVideoActivity
- 07:42:09 text='领取成功' bounds=[798,202][982,264] clickable=false ← **广告刚开始就显示"领取成功"**
- 07:42:09 scene=REWARD_POPUP,等待 30 秒 min duration
- 07:42:41 isAdEndedMultiSignal: YES (claim reward button appeared) ← 误判
- 07:42:42 点击 bounds=[722,202][982,264] clickable=true ← "领取成功"的父节点容器
- 07:42:47 页面没变化,isAdEndedMultiSignal 再次触发
- 07:42:47 再次点击同一区域
- 07:42:51 用户手动停止

**根因**: findClaimRewardButton 用"领取"子串匹配命中"领取成功"
- "领取成功"是广告 endcard 的静态标题文案(clickable=false)
- 但其父节点 clickable=true(bounds=[722,202][982,264])
- findClaimRewardButton 返回该节点,watchAd 点击父节点容器无效
- 页面不变,isAdEndedMultiSignal 重复触发→重复点击→循环

**修复**: [FarmAccessibilityService.kt#L4504-L4516](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L4504-L4516)
- findClaimRewardButton 排除"领取成功"文本
- "领取成功"是领取后的结果提示,不是领取动作按钮
- 真正的领取按钮文案是"领取奖励"/"立即领取"/"可立即领奖"等

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit eb3240d - fix: build708 第三方overlay直接reopenFarmByDeepLink避免UC进程被回收后无法拉起

**用户需求**: "分析日志"

**日志分析** (build705, debug_test_20260806_071848.log, 07:16:22-07:18:45):

**build706/707 验证**: 本次未遇到"扭一扭"广告场景,未触发。

**问题 (严重)**: UC→淘宝跨 App 浏览任务完成后,无法返回农场页,卡死在 launcher,用户手动停止
- 07:16:22 点击"看广告领奖" → 跳转淘宝(pkg=com.taobao.taobao)
- 07:16:32 forceKillApp(taobao) + reopenFarmByDeepLink(killCurrentFirst=false) → UC 重开成功
- 07:16:38 点击"签到" → 07:16:44 点击"已领取" → 07:17:01 AI vision 超时 → OPENING_TASK_LIST
- 07:17:02 找到 6 个"去完成"任务,task #1="浏览广告赚肥料"
- 07:17:07 点击"去完成" → 跳转淘宝(跨 App 浏览任务,含"浏览得奖励"+"30秒")
- 07:17:17-07:18:05 滑动 22 次完成浏览 → exitBrowsePage 点击淘宝"返回"图标
- 07:18:05 performClickSafe: text='' desc='返回' bounds=[33,147][125,239] → ACTION_CLICK success
- 07:18:11 activeRootPkg='com.taobao.taobao' ← **返回没生效!** 淘宝 H5 页面返回按钮 ACTION_CLICK 无效
- 07:18:13 navigate 检测到第三方 overlay(taobao) → forceKillApp(taobao, pressBackFirst=false)
- 07:18:18-07:18:29 UC 还是没回到前台(UC 进程可能已被系统回收)
- 07:18:29 reopenFarmByDeepLink → forceKillApp(UC) + deep link
- 07:18:35-07:18:45 UC 无法启动,卡在 launcher,用户手动停止

**根因**: build697 修复逻辑有缺陷
- attempt=0 时 forceKillApp(taobao) 对前台 App 无效(killBackgroundProcesses 只能 kill 后台进程)
- 等 attempt=1 才用 reopenFarmByDeepLink,但此时 UC 进程已被系统回收
- UC 被回收后 deep link 拉起失败,卡死在 launcher

**修复**: [AutomationController.kt#L1044-L1066](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L1044-L1066)
- 第三方 overlay 场景直接用 reopenFarmByDeepLink(killCurrentFirst=true)
- 先 HOME+kill 第三方 App,再用 deep link 拉起农场 App 到前台
- 不再先 forceKillApp 再等下一轮,避免 UC 进程被回收后无法拉起
- killCurrentFirst=true 会先按 HOME 把第三方 App 退到后台,再 killBackgroundProcesses 才有效

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 3710d9a - fix: build707 CLOSING_AD策略0允许TRAP_INTERACTIVE场景查找关闭按钮

**用户需求**: "分析日志"

**日志分析** (build705, debug_test_20260803_203219.log, 20:31:00-20:32:16):

**build706 验证**: ✓ 强信号识别生效
- 20:31:00 isInteractiveAdPage: YES (strong interactive signal: 摇一摇/扭一扭)
- 20:31:00 watchAd: scene=TRAP_INTERACTIVE, elapsed=0ms

**问题 (严重)**: CLOSING_AD 关闭失败,forceKillApp(UC) 后无法回到农场页,用户手动停止
- 20:31:15 countdown stuck at 10s → CLOSING_AD
- 20:31:17 findAdCloseButton: scene not allowed for close (scene=TRAP_INTERACTIVE), skip ← **关闭按钮被白名单阻止**
- 20:31:17 坐标点击右上角 8 个位置都无效(快手关闭按钮不在常规右上角位置)
- 20:31:28 findClaimRewardButton: scene not allowed for claim (scene=TRAP_INTERACTIVE), skip
- 20:31:33 所有策略失败 → RETURNING
- 20:31:35 forceKillApp(UC) + reopenFarmByDeepLink
- 20:31:40-20:32:16 UC 无法回到前台,activeRootPkg 一直是 com.hihonor.android.launcher
- 20:32:16 用户手动停止

**根因**: findAdCloseButton 场景白名单不包括 TRAP_INTERACTIVE
- build706 将"扭一扭"广告识别为 TRAP_INTERACTIVE(正确)
- 但 CLOSING_AD 策略0 调用 findAdCloseButton 时 scene=TRAP_INTERACTIVE
- isCloseAdAllowedScene 白名单:AD_PLAYING/AD_ENDED/REWARD_POPUP/SIGN_IN/GENERIC_POPUP
- TRAP_INTERACTIVE 不在白名单 → findAdCloseButton 返回 null → 跳过策略0
- 广告页明明有"跳过"按钮(build704 日志 20:08:58 确认可点击),但因白名单被跳过
- 坐标点击无效 → 所有策略失败 → RETURNING → forceKillApp(UC) → deep link 失败 → 卡死

**修复**: [AutomationController.kt#L5708-L5717](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L5708-L5717)
- CLOSING_AD 策略0 调用 findAdCloseButton 时传入 enforceSceneWhitelist=false
- 允许在 TRAP_INTERACTIVE 场景查找关闭按钮(如"跳过"按钮)
- 安全性:CLOSING_AD 是主动关闭流程,不会误点陷阱(已有 isFakeCloseButton 检测)
- 修复后:"扭一扭"广告 CLOSING_AD 策略0 能找到"跳过"按钮并点击 → 广告正常关闭 → 不再进入 RETURNING/forceKillApp 流程

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 4115fa8 - fix: build706 快手"扭一扭"互动广告误判为非互动广告导致卡死循环

**用户需求**: "分析日志"

**日志分析** (build704, debug_test_20260803_201027.log, 20:08:39-20:10:24):

**build705 验证**: 已生效(commit ced4322),但本次广告用"10秒"带后缀格式,纯数字分支未触发。

**问题 (严重)**: 快手"扭一扭"互动广告重复触发,卡死循环,用户手动停止
- 20:08:39 进入快手广告,texts=[扭一扭或点击跳转详情页或第三方应用, shake_title, rotate_view_container, rotate_view, 看, 10秒, 可直接拿奖励, 京东]
- 20:08:39 isInteractiveAdPage: has interactive keyword but no download button, not interactive ad (likely reward video) ← **误判!**
- 20:08:39-20:08:55 "10秒"是静态文案(体验时长),15秒后 countdown stuck → CLOSING_AD
- 20:08:58-20:09:18 CLOSING_AD 关闭耗时 20 秒(策略0点跳过+策略1坐标+策略2大区域)
- 20:09:18 进入 RETURNING,deep link 重开农场
- 20:09:36 第二次点击"看广告领奖" → 又进入同样的快手"扭一扭"广告
- 20:09:48-20:10:24 完全重复第一次的卡死流程,用户手动停止

**根因**: isInteractiveAdPage 误判"扭一扭"广告为非互动广告
- 文案明确含"扭一扭或点击跳转详情页或第三方应用" + shake_title/rotate_view 互动组件
- 但 findInteractiveAdDownloadButton 找的是"点击打开或者下载第三方应用"按钮
- 文案不匹配 → 找不到下载按钮 → isInteractiveAdPage 返回 false
- scene 被判定为 AD_PLAYING → 走正常激励视频流程
- "10秒"是静态文案,15秒后 countdown stuck 才 CLOSING_AD,关闭耗时 20 秒
- 重复触发同一广告,用户手动停止

**修复**: [FarmAccessibilityService.kt#L1945-L1988](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L1945-L1988)
- 区分强信号和弱信号:
  - 强信号(扭一扭/摇一摇等明确动作提示):直接判定为互动广告,无需下载按钮
    这类广告无障碍服务无法模拟摇动,必须立即退出,不应干等 15 秒
  - 弱信号(可直接拿奖励/shake_title/rotate_view):需配合下载按钮才判定
    避免误判普通激励视频(build695 修复保留)
- 修复后:"扭一扭"广告立即识别为 TRAP_INTERACTIVE → 走 TRAP_INTERACTIVE 分支
  → 无下载按钮时 fall through 到广告结束检测 → CLOSING_AD 关闭
  避免干等 15 秒 countdown stuck

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit ced4322 - fix: build705 快手广告纯数字倒计时检测+30秒后无法关闭

**用户需求**: "分析日志"

**日志分析** (build703, debug_test_20260803_195642.log, 19:55:50-19:56:35):

**build703+704 验证**: 本次未遇到"点击商品广告"场景,2s 等待和"确认要离开吗"弹窗修复未触发。

**问题 (严重)**: 快手广告 30 秒后未关闭,用户手动停止
- 19:55:50 进入快手 KsRewardVideoActivity 广告
- 19:55:52 texts=[16, 免费获取, 跳过, 快手, 广告...] → 倒计时是纯数字"16"/"1"(无"秒"后缀)
- 19:56:23 elapsed=30000ms,30 秒到了,checking ad end
- 19:56:23-19:56:35 isAdEndedMultiSignal 一直返回 false,广告无法关闭
- 19:56:35 用户手动停止

**根因**: findAdDurationHint 兜底正则不匹配纯数字倒计时
- 快手广告倒计时是纯数字"16"/"1",无"秒"/"s"后缀
- 原正则 `^(\d+)\s*[秒s]$` 只匹配"15s"/"30秒",不匹配纯数字
- findAdDurationHint 一直返回 0 → prevHadCountdown 一直 false
- 30 秒后 isAdEndedMultiSignal 弱信号(倒计时消失)不触发
- "免费获取"不匹配 findClaimRewardButton 关键词
- 一直干等到用户手动停止

**修复**: [FarmAccessibilityService.kt#L1651-L1669](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L1651-L1669)
- findAdDurationHint 兜底逻辑增加纯数字倒计时匹配
- 正则 `^(\d{1,2})$` 匹配 1-2 位纯数字,范围 1-60
- 安全性:在 isAdActivity() 条件下,纯数字 1-60 通常是倒计时
- 商品价格通常含"¥"/"元"或更长文本,不会误匹配
- 修复后:findAdDurationHint 检测到"16"→prevHadCountdown=true→30秒后倒计时消失→弱信号触发→广告关闭

**其他问题 (未修复,非严重)**:
1. navigate hasFarmContentLoaded=false 持续 50 秒(阈值>=30,realContent=18-25),attempt=10 超时兜底
2. "看广告领奖"点击无效(可能网络慢/广告未加载),第三次检测到"same as last clicked"放弃

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit bc65d07 - fix: build703+704 点击商品后缩短等待2s+处理"确认要离开吗"弹窗

**用户需求**: "点击商品后，右上角点击关闭任务就完成了"

**日志分析** (build701, debug_test_20260803_080115.log, 08:00:32-08:01:14):

**问题1**: 点击商品后等待 5s 才关闭广告,时间太长
- 08:00:34 点击商品(汇川 HCRewardVideoActivity)
- 08:00:41 5s 后才点击关闭图标(等太久)

**修复1 (build703)**: [AutomationController.kt#L5213-L5217](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L5213-L5217)
- 点击商品后等待时间从 5s 缩短到 2s
- `sinceClick >= 5000L` → `sinceClick >= 2000L`
- 更快关闭广告,减少等待

**问题2**: 点击关闭按钮后弹出"确认要离开吗？"弹窗,干等 30 秒
- 08:00:41 点击关闭按钮
- 08:00:53 弹出"确认要离开吗？"弹窗,texts=[点击商家后立即领奖, 确认要离开吗？, 返回点击商家, 放弃奖励离开]
- 08:00:53-08:01:14 原逻辑未识别此弹窗,scene=AD_ENDED,干等 30 秒直到用户手动停止

**根因**: watchAd 未检测"确认要离开吗"弹窗,无对应处理逻辑
- 点击关闭按钮后广告 SDK 弹出退出确认对话框
- 弹窗含"放弃奖励离开"按钮,但 watchAd 没有检测和点击逻辑
- 商品奖励已在点击商品时触发,"放弃奖励离开"只是确认退出广告

**修复2 (build704)**:
- [FarmAccessibilityService.kt#L4394-L4406](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L4394-L4406): 新增 `findLeaveConfirmAbandonButton()`
  - 必须同时含"确认要离开"标题和"放弃奖励离开"按钮才返回(避免误匹配)
- [AutomationController.kt#L5266-L5282](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L5266-L5282): watchAd 增加弹窗检测
  - 检测到"确认要离开吗"弹窗时,点击"放弃奖励离开"立即退出
  - 不再干等 30 秒

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 5b719d5 - fix: build702 点击商品广告循环+adProductClickCount次数限制

**用户需求**: "分析日志"

**日志分析** (build701, debug_test_20260803_080115.log, 08:00:32-08:01:14):

**build701 验证**: ✓ 3个修复全部生效
- isTaskCompletePage 没有误判(广告页"奖励已领取"不再触发)
- isRechargePage 没有误判(农场页"下单得"不再触发)
- findClaimRewardButton 不再匹配"点击商家后立即领奖"(没有反复点击空节点)

**问题**: 点击商品广告循环,用户手动停止
- 08:00:34 汇川 HCRewardVideoActivity 广告,点击商品(✓ build642)
- 08:00:41 5秒后点击关闭图标
- 08:00:53 出现"确认要离开吗？"弹窗,waiting 30秒(build701修复生效,没有循环)
- 08:01:09 30秒后弹窗消失,回到广告页,isClickProductAd() 又返回 true
- 08:01:09 adProductClicked 已被重置为 false → 再次点击商品 → 循环
- 08:01:14 用户手动停止

**根因**: adProductClicked 重置后无记忆,导致循环
- 阶段2关闭广告后,adProductClicked 重置为 false(原设计:下一轮重新尝试)
- 但"确认要离开吗？"弹窗消失后回到广告页,isClickProductAd() 又返回 true
- adProductClicked=false → 再次点击商品 → 循环

**修复**: [AutomationController.kt#L1878-L1884](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L1878-L1884) + [L5179-L5193](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L5179-L5193)
- 增加 adProductClickCount 计数,每轮广告最多点击2次商品
- 超过后不再点击,直接 pressBack 退出
- 新广告开始时重置 adProductClickCount = 0

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit e533369 - fix: build701 isRechargePage/isTaskCompletePage误判+点击商家后弹窗循环

**用户需求**: "分析日志"

**日志分析** (build699, debug_test_20260803_075404.log, 07:51:45-07:54:02):

**build699 验证**: ✓ "去体验15秒可立即领奖"CTA 不再点击
- 但出现了新的循环问题

**问题1**: isTaskCompletePage 误判 → 白等30秒
- 07:51:47 字节穿山甲 TTRewardVideoActivity 广告页面含"奖励已领取"静态文案
- isTaskCompletePage: YES → scene=AD_ENDED
- 一直 waiting min=30000ms,直到 07:52:19 才点击关闭

**问题2**: isRechargePage 误判 → 场景被误判为 TRAP_RECHARGE
- 07:53:02 农场任务列表页面含"下单得"(如"下单得 肥料 +80000")
- isRechargePage: YES → scene=TRAP_RECHARGE
- findClaimRewardButton 被场景白名单阻止

**问题3**: "点击商家后立即领奖"+"确认要离开吗？"弹窗循环卡死
- 07:53:11 汇川 HCRewardVideoActivity 广告,点击商品(✓ build642逻辑生效)
- 07:53:18 5秒后点击关闭图标
- 07:53:30 出现"确认要离开吗？"弹窗,含"点击商家后立即领奖"
- 07:53:46-07:54:01 findClaimRewardButton 匹配"立即领奖"→点击空节点 bounds=[120,912][1080,1751]
- 反复点击空节点(整个弹窗背景),循环卡死,用户手动停止

**修复1**: [FarmAccessibilityService.kt#L4057-L4066](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L4057-L4066)
- isRechargePage 增加农场页检测:若 isOnFarmPage() 返回 false
- 避免农场任务列表页面的"下单得"被误判为充值页

**修复2**: [FarmAccessibilityService.kt#L4600-L4607](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L4600-L4607)
- isTaskCompletePage 增加广告页面检测:若 isAdActivity() 返回 false
- 避免广告页面的"奖励已领取"静态文案被误判为任务完成

**修复3**: [FarmAccessibilityService.kt#L4434-L4442](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L4434-L4442)
- findClaimRewardButton 排除"点击商家后"开头的文本
- 避免匹配"点击商家后立即领奖"(提示文案,不是领取按钮)
- 防止反复点击空节点循环卡死

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 945dccc - feat: build700 扩展加速按钮匹配"我要加速"

**用户需求**: "应该点击我要加速"

**背景**: build699 修复后,"去体验15秒可立即领奖"CTA 不再点击(避免"确定要退出吗？"弹窗循环)。
但用户希望点击"我要加速"按钮加速广告倒计时,减少等待时间。

**问题**: 当前 watchAd 只匹配"点我加速"(穿山甲 KsRewardVideoActivity),
不匹配"我要加速"(字节穿山甲 TTRewardVideoActivity)。

**修复**: [AutomationController.kt#L5238-L5263](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L5238-L5263)
- 扩展加速按钮匹配:优先"点我加速",其次"我要加速"
- 两个文案对应不同广告 SDK 的加速按钮:
  - "点我加速":穿山甲 KsRewardVideoActivity
  - "我要加速":字节穿山甲 TTRewardVideoActivity
- 仍用 adSpeedUpClicked 标记防重入,每轮广告只点一次

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 3b4e509 - fix: build699 findClaimRewardButton误匹配体验CTA+continue_button循环卡死

**用户需求**: "分析日志"

**日志分析** (build698, debug_test_20260803_074314.log, 07:42:07-07:43:11):

**build698 验证**: ✓ forceKillApp HOME键问题已解决
- 07:42:07 进入字节穿山甲 TTRewardVideoActivity → WATCHING_AD
- findAdDurationHint: found '15秒'+'29s' → scene=AD_PLAYING (build696修复生效)

**问题**: "确定要退出吗？"弹窗循环卡死60秒,用户手动停止
- 07:42:10 页面显示"恭喜获得奖励"+"无法播放媒体"(广告视频无法播放)
- 07:42:20 点击"去体验15秒可立即领奖"CTA(build696逻辑) → adjusted min=17000ms
- 07:42:22 出现"确定要退出吗？"弹窗(穿山甲退出确认) + "去领取奖励"(continue_button)
- 07:42:28 点击"去领取奖励" → 回到广告页
- 07:42:33 findClaimRewardButton又找到"去体验15秒可立即领奖" → 点击
- 07:42:38 又出现"确定要退出吗？" → 又点"去领取奖励" → 循环
- 持续到 07:43:11(约60秒),用户手动停止

**根因**: findClaimRewardButton 误匹配体验CTA和continue_button
1. findClaimRewardButton 含"可立即领奖"关键词 → 匹配"去体验15秒可立即领奖"
2. 点击它触发"确定要退出吗？"弹窗
3. 弹窗含"去领取奖励"(desc='continue_button') → findClaimRewardButton匹配"领取奖励"
4. 点击continue_button关闭弹窗回到广告页 → 又匹配"去体验15秒可立即领奖" → 循环

**修复1**: [FarmAccessibilityService.kt#L4411-L4426](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L4411-L4426)
- findClaimRewardButton 排除"去体验"开头的文本(体验CTA,不是领取按钮)
- findClaimRewardButton 排除 desc='continue_button' 的节点(继续看广告按钮,不是领取奖励按钮)

**修复2**: [AutomationController.kt#L5261-L5267](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L5261-L5267)
- 移除 build696 的"去体验N秒可立即领奖"CTA 点击逻辑
- 不点击CTA,避免触发"确定要退出吗？"弹窗循环
- findAdDurationHint 仍会检测倒计时(build696修复保留),广告会在 min=30000ms 后正常结束

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 9f9d954 - fix: build698 回退forceKillApp HOME键副作用

**用户需求**: "分析日志"

**日志分析** (build697, debug_test_20260803_073303.log, 07:32:26-07:33:01):

**build697 验证**: forceKillApp HOME键逻辑生效但有副作用
- 07:32:36 forceKillApp(淘宝, pressBackFirst=true) → `(with HOME)` 日志确认
- 淘宝被成功退到后台,UC deep link 拉起

**问题**: HOME键副作用导致UC农场页加载失败
- 07:32:36 HOME键把所有前台App(包括UC)都退到桌面
- 07:32:41 UC deep link 拉起,但农场页加载不完整 → found 0 direct buttons
- 07:32:41 触发 AI vision 找"点击领取" → 白等15秒
- 07:32:56 AI vision timeout → activeRootPkg='com.hihonor.android.launcher'(桌面)
- 07:32:57 openTaskList: root is null → NAVIGATING
- 07:32:59 仍在 launcher → reopenFarmByDeepLink → forceKillApp(UC) 杀UC重启
- 07:33:01 用户手动停止

**根因**: HOME键是全局的,会把所有前台App都退到桌面
- 原意:把淘宝退到后台
- 实际:UC也被退到桌面,deep link 拉起后农场页加载不完整
- 导致 AI vision 白等15秒,最终卡在桌面

**修复**: [FarmAccessibilityService.kt#L5997-L6026](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L5997-L6026)
- 移除 build697 的 HOME 键,回到 build694 的纯 pressBack + killBackgroundProcesses
- 对前台 App 无效的问题,由 navigate 的 third-party overlay 分支兜底:
  attempt >= 1 时改用 reopenFarmByDeepLink(killCurrentFirst=false) 拉起农场 App 覆盖
- navigate 的 third-party overlay 分支修复(build697)保留

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit c78e2d2 - fix: build697 forceKillApp前台App无效+third-party overlay卡死

**用户需求**: "分析日志"

**日志分析** (build696, debug_test_20260803_072453.log, 07:23:31-07:24:51):

**build696 修复验证**: ✓ "看广告领奖"跳淘宝问题处理正确
- 07:23:41 跳淘宝 → forceKillApp(淘宝) → relaunch UC (attempt=0)
- 07:23:56 又跳淘宝 → forceKillApp(淘宝) → give up (attempt=1) → OPENING_TASK_LIST
- build693/build694 的跳转按钮放弃逻辑生效

**问题**: 进入 OPENING_TASK_LIST 后卡在淘宝直播间,用户手动停止
- 07:23:59-07:24:01 仍在淘宝(TaoLiveVideoActivity 直播间)
- 07:24:01 isNonAdTaskPage: YES → NAVIGATING
- 07:24:05 误判签到弹窗(SIGN_IN) → 点击空节点
- 07:24:13 误判 generic popup → 点关闭按钮
- 07:24:24 误判 isRechargePage(百亿补贴) → 触发 third-party overlay 逻辑
- 07:24:24-40 反复 forceKillApp(淘宝) 但淘宝仍在前台 → 卡死
- 07:24:51 用户手动停止

**根因**: forceKillApp 对前台 App 无效
- killBackgroundProcesses 只能杀后台进程,淘宝直播间是前台 Activity
- pressBack 可能被直播间拦截(直播间常拦截返回键)
- navigate 的 third-party overlay 分支反复 forceKillApp 失败

**修复1**: [FarmAccessibilityService.kt#L5997-L6027](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L5997-L6027)
- forceKillApp 的 pressBackFirst=true 时,pressBack 后再按 HOME 键
- HOME 键能保证 App 退到后台(系统级行为,无法被 App 拦截)
- App 退到后台后 killBackgroundProcesses 才能生效

**修复2**: [AutomationController.kt#L1044-L1064](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L1044-L1064)
- navigate 的 third-party overlay 分支:forceKillApp 失败后(attempt >= 1)
- 改用 reopenFarmByDeepLink(killCurrentFirst=false) 强制重启农场 App
- 通过 deep link 把农场 App 拉到前台,覆盖淘宝直播间

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 45c28c4 - fix: build696 体验类广告CTA点击+findAdDurationHint跳过逻辑修复

**用户需求**: "分析日志"

**日志分析** (build695, debug_test_20260803_071531.log, 07:14:50-07:15:29):

**build695 修复验证**: ✓ KsRewardVideoActivity 问题已解决
- 这次进入字节穿山甲 TTRewardVideoActivity(不同广告 SDK),没有卡在 pressBack 无效问题

**问题**: 字节穿山甲激励视频广告卡死,用户手动停止
- 07:15:00 进入 TTRewardVideoActivity → WATCHING_AD
- 07:15:02 页面含"去体验15秒可立即领奖" → findAdDurationHint 跳过检测返回 0
- 07:15:02 scene=AD_ENDED(无倒计时+isAdPlaying), min=30000ms(默认)
- 07:15:02-28 一直 waiting(elapsedMs < 30000ms)
- 07:15:29 用户手动停止(等不到30秒)

**根因**: build688 的 findAdDurationHint 跳过逻辑过度防护
- 页面含"可立即领奖"/"立即领奖"时直接返回 0
- 导致 scene=AD_ENDED(无倒计时), min=30000ms(默认)
- 一直 waiting 30秒,用户手动停止
- build688 原意是避免兜底逻辑误匹配体验时长"15秒",但误伤了没有独立"15秒"节点的广告

**修复1**: [FarmAccessibilityService.kt#L1593-L1653](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L1593-L1653)
- findAdDurationHint 不再直接返回 0,只跳过兜底逻辑(纯"Ns"/"N秒"节点)
- 带关键词的倒计时(如"观看15秒"/"剩余15秒")仍会被检测到
- 避免误匹配体验时长的"15秒",同时不影响正常倒计时检测

**修复2**: [AutomationController.kt#L5250-L5288](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L5250-L5288)
- 添加"去体验N秒可立即领奖"CTA 点击逻辑(类似"点我加速")
- 检测"可立即领奖"CTA 节点,点击后提取体验时长N秒
- 设置 adMinDurationMs = N秒 + 缓冲(如15秒+2秒=17秒),避免等30秒默认值
- 用 adExperienceClicked 标记防重入,每轮广告只点一次

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 1c01f7c - fix: build695 激励视频广告TRAP_INTERACTIVE误判+pressBack无效卡死

**用户需求**: "分析日志"

**日志分析** (build694, debug_test_20260802_202311.log / 202313.log / 203250.log, 20:22:14-20:23:10):

**build694 修复验证**: ✓ 千问跳转问题已解决
- "看广告领奖"成功进入穿山甲激励视频广告(KsRewardVideoActivity),没有跳到通义千问
- forceKillApp 逻辑未被触发(因为没跳非广告App)

**问题1**: TRAP_INTERACTIVE 误判
- 20:22:36 scene=TRAP_INTERACTIVE(误判为互动广告)
- 根因: `isInteractiveAdPage()` 仅凭"可直接拿奖励"关键词判定为互动广告
- 但穿山甲 KsRewardVideoActivity 既播放互动广告(摇一摇)也播放普通激励视频,两者都可能含此文案
- 普通激励视频广告没有"点击打开或者下载第三方应用"下载按钮

**问题2**: 倒计时卡死检测 pressBack 无效
- 20:22:36 found countdown '10秒' → 20:22:52(16秒后)'10秒'仍在 → countdown stuck
- pressBack 退出 → KsRewardVideoActivity pressBack 无效 → 卡在广告 Activity
- 20:22:57-23:10 NAVIGATING 反复"waiting instead of pressBack" 3次 → 用户手动停止

**修复1**: [FarmAccessibilityService.kt#L1923-L1951](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L1923-L1951)
- `isInteractiveAdPage()` 增加下载按钮检测
- 必须同时含互动关键词 **且** 找到"点击打开或者下载第三方应用"下载按钮,才判定为 TRAP_INTERACTIVE
- 普通激励视频广告(无下载按钮)不会被误判,scene 改为 AD_PLAYING,走正常激励视频等待流程

**修复2**: [AutomationController.kt#L5250-L5300](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L5250-L5300)
- 倒计时卡死检测:检测当前是否是激励视频广告 Activity(KsRewardVideoActivity/kwad)
- 如果是,不 pressBack(对 KsRewardVideoActivity 无效),直接进入 CLOSING_AD 多策略关闭
- CLOSING_AD 会尝试:策略0找关闭按钮/策略1坐标点击右上角/策略2放弃奖励/策略3 pressBack/策略4领取奖励
- 不跳过任务(CLOSING_AD 可能成功关闭并获奖励)
- 非激励视频广告(如淘宝 TMSActivity)仍走原 pressBack 退出逻辑

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 14435e6 - fix: build694 跳转千问后先退出千问再回农场页

**用户需求**: "跳到千问后，需要退出千问，回到芭芭农场页面"

**问题**: build693 的 `launchPlatformApp(killCurrentFirst=false)` 直接用 deep link 拉起 UC,但没有退出千问,千问可能仍在后台或遮挡,用户要求先退出千问再回农场页。

**修复**: [AutomationController.kt#L1479-L1505](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L1479-L1505)
- 跳到非广告 App(千问/淘宝等)时,先 `forceKillApp(nonAdPkg, pressBackFirst=true)` 退出千问
- 再 `launchPlatformApp(killCurrentFirst=false)` 用 deep link 拉起 UC 农场页
- `forceKillApp` 的 `pressBackFirst=true` 会先按返回键把千问退到后台,再 killBackgroundProcesses 结束千问进程
- 这样 UC deep link 拉起时千问已退出,UC 农场页能正常显示

**编译验证**: CI 构建通过,APK 已发布(Build 694)。

---

### commit 43f1116 - fix: build693 跳转按钮跳非广告App更快放弃(attempt>=1)

**用户需求**: "修复问题"

**日志分析** (build692, debug_test_20260802_194248.log, 19:42:02-19:42:46):

**build692 修复验证**: ✓ 生效
- 19:42:45.220 collectDirect: jump button '看广告领奖' repeatedly led to non-ad app, giving up (attempt=2), opening task list
- 19:42:45.221 state: COLLECTING_DIRECT -> OPENING_TASK_LIST ← 成功跳出循环!

**问题**: "看广告领奖"反复跳通义千问浪费约 32 秒
- attempt 0: 19:42:02 点击 → 19:42:12 跳通义千问 → relaunch (10s等待 + 5s重启)
- attempt 1: 19:42:18 点击 → 19:42:28 跳通义千问 → relaunch (10s + 5s)
- attempt 2: 19:42:35 点击 → 19:42:45 give up (10s + 放弃)
- 总计约 32 秒,用户看到反复跳转手动停止(19:42:46 STOPPING)

**根因**: build692 的 attempt >= 2 条件太宽松,需要 3 次跳转(attempt 0,1,2)才放弃,每次 10s 等待 + 5s relaunch,总浪费约 32 秒。

**修复**: [AutomationController.kt#L1481-L1490](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L1481-L1490)
- attempt >= 2 改为 attempt >= 1 (第 2 次跳非广告 App 就放弃)
- 减少到约 16 秒(1 次 10s 等待 + 1 次 5s relaunch),更快进入任务列表处理其他任务

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 2f5f390 - fix: build692 跳转按钮反复跳非广告App陷入循环

**用户需求**: "分析日志"

**日志分析** (build691, debug_test_20260802_193426.log, 19:33:21-19:34:23):

**build691 修复验证**: ✓ 生效
- 19:33:22 isOnFarmPage: onFarm=true (pz1.c 被 build691 的 farmPageActivityKeywords 添加 "pz1" 识别) ✓
- 19:33:22 state: NAVIGATING -> COLLECTING_DIRECT ← 成功进入农场页!

**问题**: "看广告领奖"跳转按钮反复跳到通义千问 App,陷入循环
- 19:33:25 点击'看广告领奖' → 19:33:35 activeRootPkg='com.aliyun.tongyi' → relaunching farm app
- 19:33:41 重试'看广告领奖' → 19:33:51 又跳到通义千问 → relaunching farm app
- 19:33:57 重试'看广告领奖' → 19:34:07 又跳到通义千问 → relaunching farm app
- 19:34:13 重试'看广告领奖' → 19:34:23 STOPPING ← 4 次都跳到通义千问,陷入循环!

**根因**: 跳转按钮跳到非广告 App(通义千问)时:
1. 每次重启 UC 后,按钮 bounds 略有不同(页面重载位置差异):
   - attempt 1: [641,1156][822,1205]
   - attempt 2: [641,1146][822,1198]
   - attempt 3: [641,1152][822,1205]
   - attempt 4: [641,1149][822,1201]
2. lastDirectClickedText+bounds 防死循环判断要求 text+bounds 完全一致,bounds 不同导致判断失效
3. 反复点击同一按钮,每次都跳到通义千问,直到 attempt=4 后 STOPPING

**修复**: [AutomationController.kt#L1477-L1486](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L1477-L1486)
- 跳转按钮跳到非广告 App 时,attempt >= 2 后放弃此按钮
- 清除 lastDirectClickedText/lastDirectClickedBounds,进入任务列表(OPENING_TASK_LIST)
- 避免反复点击同一跳转按钮陷入循环

**预期效果**:
- 跳转按钮跳到非广告 App 时,最多重试 2 次,之后放弃此按钮进入任务列表
- 任务列表中有其他可完成的任务(看视频得巨额肥料等),不会卡死

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 5e611ed - fix: build691 UC浏览器pz1.c混淆Activity名识别+立即领取/签到不点击

**用户需求**: "分析日志，立即领取 和签到 怎么不点击"

**日志分析** (build690, debug_test_20260802_190038.log, 18:59:36-19:00:36):

**问题**: bot 无法进入农场页,导致无法点击"立即领取"和"签到"按钮
- 18:59:36 state: IDLE -> NAVIGATING
- 18:59:37 isOnFarmPage: activity=pz1.c not in farm keywords, not on farm page ← pz1.c 不被识别!
- 18:59:40 navigate stepTab: click 芭芭农场 tab via node
  ← performClickSafe: text='芭芭农场，免费领水果，助果农增收' (页面已是农场页,但Activity名不对)
- 18:59:40-19:00:34 反复 navigate stepTab 点击"芭芭农场"文本,但 Activity 始终是 pz1.c
- 19:00:36 STOPPING ← 卡死 60 秒后停止

**根因**: UC 浏览器新版本使用混淆 Activity 名 `pz1.c` 渲染 H5 页面,
  UC 的 `farmPageActivityKeywords = listOf("innerucmobile", "mainactivity")` 不包含 `pz1.c`,
  `isOnFarmPage()` 第 725-732 行检查 Activity 名不匹配直接返回 false,
  导致 bot 认为不在农场页,反复 navigateToFarm 但 Activity 不变(已在页面,只是名不对)。

**修复**: [Platform.kt#L203](file:///workspace/app/src/main/java/com/bbncbot/automation/Platform.kt#L203)
- UC 的 `farmPageActivityKeywords` 添加 `"pz1"`
- `pz1.c`.contains("pz1") = true,`isOnFarmPage()` 能识别 pz1.c 为农场页

**关于"立即领取"和"签到"按钮**:
- `directCollectTexts` 已包含 `"点击领取", "签到", "立即领取"` (Platform.kt#L217, L223)
- build689 日志验证: 10:41:16 点击'签到' → 10:41:22 变成'已领取' ← 签到成功 ✓
- build690 日志中 bot 没进入农场页(pz1.c 不识别),所以无法点击任何按钮
- 修复 pz1.c 识别后,bot 能进入农场页,就能点击签到和立即领取

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit dda5046 - fix: build690 跨App浏览任务"30秒"误识别+UC广告卡死20分钟

**用户需求**: "分析日志"

**日志分析** (build689, debug_test_20260802_111340.log, 10:40:51-11:13:33):

**问题1**: 跨 App 浏览任务(UC→淘宝)"30秒"误识别为广告倒计时,卡死 90 秒
- 10:42:36 点击"去完成"(看视频得巨额肥料任务) → 跳转到淘宝
- 10:42:44 processTask: deep-link ad task, activeRoot=com.taobao.taobao, entering WATCHING_AD
- 10:42:52 findAdDurationHint: found countdown '30秒', seconds=30 ← 误匹配淘宝页面文字!
- 10:42:52-10:44:20 一直 scene=AD_PLAYING,findClaimRewardButton 被场景白名单阻止
- 根因：跨 App 浏览任务进入 WATCHING_AD 后 adModeFlag=true,isAdPlaying()=true,
  findAdDurationHint 兜底逻辑 `isAdActivity() || isAdPlaying() || isAdContentShown()` 执行,
  误匹配淘宝页面商品描述中的"30秒"为广告倒计时。

**修复1**: [FarmAccessibilityService.kt#L1627-L1636](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L1627-L1636)
- findAdDurationHint 兜底逻辑改为只 `isAdActivity()`,去掉 isAdPlaying()(可能因 adModeFlag 误判)和 isAdContentShown()
- 跨 App 浏览任务(adActivity=false)不再执行兜底逻辑,不会误匹配"30秒"

**问题2**: 快手广告 KsRewardVideoActivity 卡死 20 分钟(10:52:35-11:13:33)
- 10:52:02 点击'看广告领奖' → 快手 KsRewardVideoActivity → WATCHING_AD
- 10:52:15 findAdDurationHint: found countdown '10秒', seconds=10
- 10:52:30 countdown stuck at 10s (static text), pressing back to exit → NAVIGATING
- 10:52:38 navigate: UC ad (KsRewardVideoActivity), waiting instead of pressBack
- 10:52:38-11:13:33 一直 waiting instead of pressBack ← 卡死 20 分钟!(中间屏幕熄灭)
- 11:13:33 STOPPING ← 最终超时
- 根因：快手广告倒计时卡在"10秒"(静态文本),watchAd stall exit 后进入 NAVIGATING,
  但仍在广告 Activity,navigate 选择等待而不是 pressBack,广告已卡住,等待永远无法结束。

**修复2**: [AutomationController.kt#L968-L985](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L968-L985)
- navigate UC ad waiting 分支增加 attempt >= 6 超时(约30秒)
- 超过后强制 reopenFarmByDeepLink 退出卡住的广告 Activity

**build688 修复验证**: ✓ 生效
- 10:41:51 findAdDurationHint: skip duration hint (page contains '可立即领奖') ← build688 修复生效
- 10:41:52 scene=REWARD_POPUP ← scene 正确识别
- 10:42:07 奖励已领取
- 10:42:23 task complete (multi-signal), exiting via close/back icon ← 成功退出
- 任务进度 0/10 → 1/10 ← 奖励领取成功

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 44c4ee4 - fix: build689 跳转按钮跨App时launchPlatformApp不杀UC

**用户需求**: "分析日志"

**日志分析** (build688, debug_test_20260802_102429.log, 10:23:51-10:24:25):

**问题**: "看广告领奖"跳转按钮跳到淘宝,重启 UC 失败卡死在桌面 launcher
- 10:23:51.416 activeRootPkg='com.taobao.taobao' ← 跳转到淘宝
- 10:23:51.434 collectDirect: jump button led to non-farm app (pkg=com.taobao.taobao), relaunching farm app
- 10:23:51.438 launchPlatformApp(killCurrentFirst=true 默认值)
- 10:23:51.439 reopenFarmByDeepLink: HOME + kill UC
  ← HOME 把淘宝推到后台显示桌面,kill UC(已在后台),启动 UC deep link
- 10:23:56.488 collectDirect: found 0 direct buttons, attempt=1
- 10:24:11.567 activeRootPkg='com.hihonor.android.launcher' ← UC 启动失败,还在桌面!
- 10:24:14.554 activeRootPkg='com.hihonor.android.launcher' ← 反复 navigate 重试失败
- 10:24:25.704 WATCHING_AD -> STOPPING ← 卡死

**根因**: 当前前台是淘宝不是 UC,`launchPlatformApp` 默认 `killCurrentFirst=true`:
1. HOME 把淘宝推到后台 → 显示桌面 launcher
2. kill UC(UC 已经在后台被淘宝覆盖)
3. 启动 UC deep link → 但 UC 被杀后重启可能因 Honor 后台限制失败,卡死在桌面

**修复**: [AutomationController.kt#L1437-L1458](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L1437-L1458)
- 跳转按钮跳转到其他 App 时,`launchPlatformApp` 传 `killCurrentFirst=false`
- 不杀 UC,直接用 deep link 拉起 UC 农场页,让 UC 回到前台覆盖淘宝
- 避免杀进程导致 UC 重启失败

**预期效果**:
- 跳转按钮跳转到淘宝等非广告 App 时,UC 不被杀,deep link 直接拉起农场页
- 避免卡死在桌面 launcher

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 1cbbd60 - fix: build688 体验类广告"可立即领奖"按钮被场景白名单阻止

**用户需求**: "分析日志"

**日志分析** (build687, debug_test_20260802_100757.log, 10:06:50-10:07:54):

**问题**: "去体验15秒可立即领奖"广告卡死,WATCHING_AD 超时 STOPPING
- 10:06:50 点击'看广告领奖' → jump button led to ad (TTRewardVideoActivity), entering WATCHING_AD ✓
- 10:07:02 进入广告页,页面文本含"可立即领奖"+"去体验15秒可立即领奖"
- 10:07:02-10:07:51 findAdDurationHint 一直返回 15(误把"15秒"识别为倒计时)
  ← "15秒"是体验时长(CTA描述),不是广告倒计时
- 10:07:02-10:07:51 scene=AD_PLAYING(因 isAdPlaying=true 且 findAdDurationHint>0)
- 10:07:02-10:07:51 findClaimRewardButton: scene not allowed for claim (scene=AD_PLAYING), skip
  ← 场景白名单只允许 AD_ENDED/REWARD_POPUP/SIGN_IN,AD_PLAYING 被阻止
- 10:07:54 WATCHING_AD -> STOPPING ← 卡死 52 秒后超时

**根因**: 
1. findAdDurationHint 兜底逻辑误匹配独立"15秒"节点(来自"去体验15秒可立即领奖"文案),
   导致 scene=AD_PLAYING
2. findClaimRewardButton 场景白名单阻止 AD_PLAYING 场景点击领取按钮
3. findClaimRewardButton keywords 不含"可立即领奖"/"立即领奖",即使放行也匹配不到

**修复**: 
1. [FarmAccessibilityService.kt#L1598-L1606](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L1598-L1606):
   findAdDurationHint 开头检测页面含"可立即领奖"/"立即领奖"时返回0(15秒是体验时长不是倒计时)
   → scene 变为 AD_ENDED(广告页无倒计时),findClaimRewardButton 场景白名单放行
2. [FarmAccessibilityService.kt#L4372-L4376](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L4372-L4376):
   findClaimRewardButton keywords 增加"可立即领奖"/"立即领奖"

**预期效果**:
- 体验类广告("去体验15秒可立即领奖")不再卡死,scene 识别为 AD_ENDED
- findClaimRewardButton 能匹配并点击"可立即领奖"按钮获取奖励

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 9f0bbfd - docs: build686 注释调整 - 确认"我要更快拿奖"应被点击

**用户需求**: "分析日志"

**日志分析** (build685, debug_test_20260802_095406.log, 09:52:02-09:53:56):

**问题1**: 跳转按钮10秒后在农场页面 pressBack,退出 UC 农场回到 bbncbot MainActivity
- 09:52:13.776 点击'看广告领奖' → waiting 10000ms
- 09:52:25.175 10s elapsed, isOnFarmPage()=true → pressBack (build685 走 pressBack 分支)
- 09:52:45.222 activity=com.bbncbot.mainactivity ← pressBack 退出 UC 农场回到 bbncbot!
- 09:52:45.564 not on farm page, re-navigating → NAVIGATING → 重新回到农场
- 根因：本来就在农场主页(广告没打开或已自动关闭),pressBack 反而后退一步退出 UC 农场 H5。

**修复1**: [AutomationController.kt#L1444-L1454](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L1444-L1454)
- 跳转按钮10秒后如果在农场页面,不 pressBack(本来就在主页),直接继续下一轮 COLLECTING_DIRECT

**问题2**: WATCHING_AD 点击"我要更快拿奖"后 stage=1 卡死等 confirm popup
- 09:53:02.485 jump button '看广告领奖' led to ad (act=com.qq.e.ads.PortraitADActivity), entering WATCHING_AD ✓ (build685 修复生效)
- 09:53:04.620 findFasterRewardEntryButton: found '我要更快拿奖' → 点击（按钮应被点击,用户确认）
- 09:53:09-09:53:52 一直 waiting for faster reward confirm popup (stage=1)
- 09:53:19.828 pkg=com.hihonor.appmarket ← 广告内容切换到华为应用市场
- 09:53:56.957 WATCHING_AD -> STOPPING ← 卡死 47 秒后超时
- 根因：点击"我要更快拿奖"后,部分广告未弹出确认弹窗(广告内容已切换),
  stage=1 没有超时机制,一直等 confirm popup 直到 WATCHING_AD 超时。

**修复2**: [AutomationController.kt#L4598-L4626](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L4598-L4626)
- 新增 `fasterRewardStage1WaitCount` 计数器(每次进入 stage=0 时重置为 0)
- stage=1 每次重试等待 confirm popup 时计数器 +1
- 超过 4 次(约20秒)仍未出现 confirm popup → 放弃 faster reward 流程(stage=4)
- stage=4 为空处理,会 fall through 到正常广告等待逻辑
- **注意：不阻止点击"我要更快拿奖"**,仅作为确认弹窗未出现的兜底

**预期效果**:
- 跳转按钮点击后如果还在农场页面,不会 pressBack 退出 UC,直接继续下一轮
- "我要更快拿奖"点击后如果跳转到应用市场(无 confirm popup),20秒后自动放弃,回到正常广告等待

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 673c167 - fix: build685 跳转按钮跨App返回 + stepTab 误点系统提示

**用户需求**: "分析日志，点击了'点击跳转拿奖励'，需要10秒后返回页面" + "分析日志"

**日志分析** (build684, debug_test_20260801_211023.log & debug_test_20260801_211034.log, 21:07:46-21:09:44):

**问题1**: 点击"看广告领奖"跳转按钮后,10秒 pressBack 无法从淘宝返回 UC,卡死 STOPPING
- 21:07:46.359 点击'看广告领奖' bounds=[641,1156][822,1205] (jump button, waiting 10000ms)
- 21:07:56.393 10s elapsed, pressing back to return to farm home (build684 走 pressBack 分支)
- 21:08:16.466 activeRootPkg='com.taobao.taobao', act=SearchActivity ← 跳转到淘宝搜索页!
- → 不是广告 Activity,isAdActivity()=false,走 pressBack 分支
- → pressBack 无法跨 App 从淘宝返回 UC,反复 reopenFarmByDeepLink 失败 → STOPPING
- 21:08:55.507 第二次点击'看广告领奖' → 同样跳转淘宝 → 卡死

**根因1**: 跳转按钮可能跳转到淘宝/其他 App(非广告),pressBack 无法跨 App 返回。

**修复1**: [AutomationController.kt#L1424-L1450](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L1424-L1450)
- 跳转按钮10秒等待结束后,检查三种情况:
  1. 是广告 Activity → WATCHING_AD（原 build683 逻辑）
  2. 在农场页面 → pressBack 返回主页继续 COLLECTING_DIRECT
  3. 不在农场页面（其他 App 如淘宝）→ `service.launchPlatformApp()` 重新启动农场 App 返回主页

**问题2**: stepTab 误点系统提示"芭芭农场机器人正在其他应用的上层显示内容"跳转到设置页
- 21:08:31.677 performClickSafe: text='芭芭农场机器人正在其他应用的上层显示内容。'
  bounds=[215,563][958,621] clickable=false
  ← findNodeByText contains 匹配到无障碍服务系统提示（含"芭芭农场"+机器人名）
  ← bounds 有效(top=563<bottom=621), clickable=false, 走 ancestor bounds 点击
- 21:08:35.977 activeRootPkg='com.android.settings' ← 跳转到系统设置页!

**根因2**: 系统无障碍服务提示文本"芭芭农场机器人正在其他应用的上层显示内容"包含关键词"芭芭农场",
  且 bounds 有效,被 findNodeByText 误识别为可点击的农场标签,通过 ancestor bounds 点击后
  跳转到系统无障碍服务设置页。

**修复2**: [FarmAccessibilityService.kt#L6058-L6072](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L6058-L6072)
- findNodeByText 匹配到的节点增加系统提示过滤:
  文本/desc 含"正在其他应用"/"机器人正在"/"上层显示内容"则跳过
- 这样只会点击真正的"芭芭农场"标签,不会误点无障碍服务系统提示

**预期效果**:
- 跳转按钮跳转到淘宝等非广告 App 时,能自动重启农场 App 返回主页继续任务
- stepTab 导航不再误点无障碍服务系统提示,避免跳转到系统设置页

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit a399fb7 - fix: build684 stepClickFarmTab 误点跨平台跳转入口

**用户需求**: "分析日志"

**日志分析** (build683, debug_test_20260801_205040.log, 20:49:58-20:50:38):

**问题**: NAVIGATING 阶段误点"前往手机支付宝-芭芭农场"跳转到支付宝,卡死 STOPPING
- 20:50:03.762 navigate stepTab: click 芭芭农场 tab via node, platform=UC
- 20:50:03.763 performClickSafe: text='前往手机支付宝-芭芭农场' bounds=[330,3877][815,2509] clickable=false
  ← findNodeByText contains 匹配到跨平台跳转入口
  ← bounds=[330,3877][815,2509] top=3877 > bottom=2509 无效!
- 20:50:06.634 activeRootPkg='com.eg.android.AlipayGphone' (跳转到支付宝了)
- 20:50:38.262 state: NAVIGATING -> STOPPING (卡死)

**根因**: findNodeByText 使用 contains 匹配,text='前往手机支付宝-芭芭农场' 匹配了 keyword "芭芭农场"。
  该节点 clickable=false, bounds 无效(top>bottom),但代码仍尝试点击(通过 ancestor bounds 点击),
  导致跳转到支付宝 App,UC 农场自动化卡死。

**修复**: [FarmAccessibilityService.kt#L6039-L6059](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L6039-L6059)
- findNodeByText 匹配到的节点增加两项检查:
  1. 排除跨平台跳转入口: 文本/desc 含"支付宝"/"前往"则跳过
  2. 排除无效 bounds: top>bottom 或 left>right 或 宽高<=0 则跳过
- 这样只会点击 UC 自己的"芭芭农场"标签,不会误点跨平台跳转入口

**预期效果**:
- NAVIGATING 阶段不再误点"前往手机支付宝-芭芭农场"跳转到支付宝
- 无效 bounds 的节点不会被点击

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit (待提交) - fix: build683 跳转按钮10秒后进入广告Activity则切 WATCHING_AD

**用户需求**: "分析日志，点击了'点击跳转拿奖励'，需要10秒后返回页面"

**日志分析** (build682, debug_test_20260801_202144.log, 20:20:14-20:21:41):

**成功部分**: 两个跳转按钮都被正确点击 (20:20:37-20:21:02)
- 20:20:37.145 点击'看广告领奖' bounds=[641,1149][822,1201]
- 20:20:37.154 jump button '看广告领奖' detected, waiting 10000ms then pressBack
- 20:20:47.156 10s elapsed, pressing back to return to farm home ✓
- 20:20:52.441 点击'点击跳转拿奖励' bounds=[201,1401][1000,1578] clickable=true
- 20:20:52.520 jump button '点击跳转拿奖励' detected, waiting 10000ms then pressBack
- 20:21:02.527 10s elapsed, pressing back to return to farm home ✓

**问题**: pressBack 后进入广告 Activity,卡死导致 STOPPING (20:21:02-20:21:41)
- 20:21:07.555 collectDirect: found 0 direct buttons (实际已进入广告 Activity)
- 20:21:22.601 activeRootPkg='com.antgroup.leopard.android' (穿山甲广告)
- 20:21:22.778 act=com.kwad.sdk.api.proxy.app.KsRewardVideoActivity, adActivity=true
- → pressBack 未能关闭广告(UC 激励视频 pressBack 无效)
- → bot 继续走 COLLECTING_DIRECT 但实际卡在广告页
- → AI 视觉超时后 fallback 到 OPENING_TASK_LIST → NAVIGATING
- → 反复"UC ad, waiting instead of pressBack" → STOPPING

**根因**: 跳转类按钮点击后,10秒内页面可能跳转到穿山甲激励视频广告 Activity。
  pressBack 无法关闭此类广告,应该进入 WATCHING_AD 状态处理广告。

**修复**: [AutomationController.kt#L1394-L1428](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L1394-L1428)
- 跳转按钮点击后,10秒等待结束时先检查是否进入广告 Activity
- `if (service.isAdActivity() || service.isAdPlaying())`:
  - 是 → 进入 WATCHING_AD 状态,调用 runWatchingAd 处理广告
  - 否 → pressBack 返回主页,继续 COLLECTING_DIRECT 下一轮（原逻辑）
- 这样跳转类按钮无论是跳转到活动页还是广告页,都能正确处理

**预期效果**:
- 跳转按钮点击后若进入广告,会走完整的广告处理流程（识别广告结束 → 关闭 → 返回）
- 不再因 pressBack 无效而卡死在广告 Activity

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit (待提交) - fix: build682 TRAP_INTERACTIVE fall through 误入 download clicked 分支

**用户需求**: "分析日志"

**日志分析** (build680, debug_test_20260801_201024.log, 第二段 20:09:08-20:10:18):

**问题**: TRAP_INTERACTIVE 广告卡死 31 秒 (20:09:47-20:10:18)
- 20:09:47.928 watchAd: scene=TRAP_INTERACTIVE, elapsed=0ms
- 20:10:03.650 watchAd: interactive ad no download button, min wait elapsed, fall through to ad-end check
- 20:10:03.652 watchAd: interactive ad download clicked, waiting for download complete  ← 误入此分支
- 20:10:13.931 watchAd: interactive ad download clicked, waiting for download complete  ← 反复卡死
- 20:10:18.679 state: WATCHING_AD -> STOPPING (用户手动停止)

**根因**: build680 修复的 bug
- build680 修复让无下载按钮且 elapsedMs >= adMinDurationMs 时 fall through（不 return）
- 但 fall through 后,代码继续执行到下方"已点击下载按钮"分支（无条件 return）
- 这个分支原本是给"已点击下载按钮"场景用的,现在被无下载按钮场景错误触发
- 导致后续的 isAdEndedMultiSignal 检测永远不执行,卡死直到用户手动停止

**修复**: [AutomationController.kt#L4857-L4933](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L4857-L4933)
- 将"已点击下载按钮"分支改为 `else` 分支（仅当 interactiveAdDownloadClicked=true 时执行）
- 无下载按钮 fall through 时不会误入 else 分支,直接跳出 TRAP_INTERACTIVE 分支
- 执行后续的广告结束检测（isAdEndedMultiSignal、no_root 主动关闭等）

**预期效果**:
- TRAP_INTERACTIVE 无下载按钮时,min wait 后能正确 fall through 到广告结束检测
- 不再误入"已点击下载按钮"分支导致卡死
- 广告结束后能识别并关闭（包括 no_root 时的主动关闭）

**备注**: 此日志还包含第一段（15:23:40-15:25:07）的已知问题（build681 已修复）：
- "看广告领奖"AI 视觉超时（build681: 加入 directCollectTexts）
- 广告 no_root 卡死（build681: no_root 主动关闭）
但设备运行的是 build680,build681 代码未部署到设备。

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit (待提交) - fix: build681 "看广告领奖"变体文案 + 广告 no_root 卡死

**用户需求**: "分析日志"

**日志分析** (build680, debug_test_20260801_152504.log, 15:23:40-15:24:58):

**问题1**: "点击跳转拿奖励"AI 视觉 15s 超时未点击 (15:24:01-15:24:17)
- 15:24:01.834 collectDirect: trying AI vision to locate '点击跳转拿奖励' button (dailyClaimed=true)
- 15:24:16.877 collectDirect: AI vision timed out after 15000ms, fallback to task list
- 根因：AI 视觉调用超时（GLM-4.6V-Flash 慢/网络问题）
- 但日志 line 63 显示页面有 text='看广告领奖' bounds=[641,1159][822,1211] clickable=false
  这是"点击跳转拿奖励"的变体文案（扩展资源位区域的广告入口）
- 原本 UC directCollectTexts 不含"看广告领奖",findDirectCollectButtons 返回 0,才触发 AI 视觉

**问题2**: 广告结束后 no_root 导致卡死 4 秒 (15:24:54-15:24:58)
- 15:24:54.320 watchAd: scene=AD_ENDED, elapsed=25000ms/90000ms
- 15:24:54.372 watchAd: checking ad end, pageType=unknown(no_root), adActivity=true, adPlaying=true, texts=[]
- 15:24:58.475 state: WATCHING_AD -> STOPPING (用户手动停止)
- 根因：广告结束后 root 暂时不可用（no_root），isAdEndedMultiSignal 无法执行信号 3/4
  （findClaimRewardButton、collectAllText 都需要 root）
  但 isAdActivity()/isAdPlaying() 基于 Activity 名仍返回 true（KsRewardVideoActivity）
  导致 adEnded=false,走到"继续等待"分支,bot 卡死直到用户手动停止

**修复 1**: [Platform.kt#L229-L236](file:///workspace/app/src/main/java/com/bbncbot/automation/Platform.kt#L229-L236)
- UC directCollectTexts 加入"看广告领奖"
- 让 findDirectCollectButtons 直接识别此变体文案,无需 AI 视觉,响应更快
- 与"点击跳转拿奖励"同类（跳转类广告入口按钮）

**修复 2**: [AutomationController.kt#L1392-L1408](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L1392-L1408)
- runCollectingDirect 中"点击跳转拿奖励"专用流程扩展为同时识别"看广告领奖"
- isJumpButton 变量统一判断两种文案,都走 10 秒 + pressBack 返回专用流程

**修复 3**: [AutomationController.kt#L5346-L5365](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L5346-L5365)
- watchAd 中"继续等待"分支前增加 no_root 主动关闭逻辑
- 当 pageType=unknown(no_root) && adActivity=true && elapsedMs >= adMinDurationMs + 10s buffer 时,
  主动进入 CLOSING_AD 关闭广告,避免 no_root 时卡死
- no_root 通常意味着广告页面正在切换（结束动画/弹窗加载中）,
  此时主动尝试关闭比无脑等待更合理（CLOSING_AD 会找关闭按钮,找不到再 pressBack 兜底）

**预期效果**:
- "看广告领奖"变体文案被 findDirectCollectButtons 直接识别,无需 AI 视觉,响应更快
- 广告结束后 no_root 时不再卡死,主动进入 CLOSING_AD 关闭广告

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit (待提交) - fix: build680 isActivityFarmPage 跳过 AI 视觉 + TRAP_INTERACTIVE 卡死

**用户需求**: "分析日志 为什么没有点击'点击跳转拿奖励'" + "解决所有问题"

**日志分析** (build679, debug_test_20260801_151301.log, 15:11:43-15:12:56):

**问题1**: "点击跳转拿奖励"仍未被识别 (15:12:03)
- 15:12:03.577 collectDirect: found 0 direct buttons, attempt=0
- 15:12:03.607 collectDirect: **activity farm page detected (今日任务余/完成即领)**, no 点击领取 button, skip AI vision, go to task list
- 根因：页面同时有"已领取"+"明天领肥料"+"8.4内完成3天即领"(活动标题),
  isActivityFarmPage() 返回 true 直接跳过 AI 视觉(build673 修复),
  build679 只给 hasDailyRewardClaimedIndicator 加了 aiVisionDirectClickAttempted 检查,
  isActivityFarmPage 没加,导致 AI 视觉仍被跳过,"点击跳转拿奖励"未被识别。

**问题2**: task #1 "看视频得巨额肥料" 卡在 TRAP_INTERACTIVE 广告 40 秒 (15:12:16-15:12:56)
- 15:12:16.470 findAdDurationHint: found countdown '10秒', seconds=10
- 15:12:16.474 watchAd: parsed ad duration hint=10s, min wait=12000ms
- 15:12:16-15:12:53 scene=TRAP_INTERACTIVE, no download button, continue waiting
- 15:12:56 用户手动停止(卡死 40 秒)
- 根因：TRAP_INTERACTIVE 无下载按钮时一直 return,后续的 isAdEndedMultiSignal 检测
  永远不执行,即使广告倒计时 10s 已结束(min wait=12s),也无法识别广告结束并关闭。

**修复 1**: [AutomationController.kt#L1095](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L1095)
- isActivityFarmPage 跳过条件增加 `aiVisionDirectClickAttempted` 检查
- 与 build679 的 hasDailyRewardClaimedIndicator 修复保持一致
- 尚未尝试 AI 视觉时不跳过,让 AI 视觉有机会识别"点击跳转拿奖励"按钮

**修复 2**: [AutomationController.kt#L4898-L4907](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L4898-L4907)
- TRAP_INTERACTIVE 无下载按钮时,增加 elapsedMs < adMinDurationMs 条件判断:
  - 未到 min wait: 继续轮询等待(原逻辑)
  - 已到 min wait: 不 return,跳出 TRAP_INTERACTIVE 分支
- 让后续的 isAdEndedMultiSignal 检测有机会执行,识别广告结束后走 CLOSING_AD 关闭
- 避免互动广告倒计时结束后仍卡死在 TRAP_INTERACTIVE 分支

**预期效果**:
- 活动页面+每日奖励已领取时,AI 视觉不再被跳过,会找"点击跳转拿奖励"按钮
- 互动广告无下载按钮时,倒计时结束后能识别广告结束并关闭,不再卡死 40 秒

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit (待提交) - fix: build679 每日奖励已领取时 AI 视觉找"点击跳转拿奖励"按钮

**用户需求**: "分析日志 为啥点击'点击跳转拿奖励'" + "希望点击"

**日志分析** (build677, debug_test_20260801_144605.log, 14:44:54):
- 14:44:54.401 collectDirect: found 0 direct buttons, attempt=0
- 14:44:54.413 collectDirect: daily reward already claimed (已领取/明天领肥料 detected), skip AI vision, go to task list
- 14:44:54.415 state: COLLECTING_DIRECT -> OPENING_TASK_LIST

**根因**: UC 主页"点击跳转拿奖励"是 H5/Canvas 图像按钮,文本不在 accessibility tree 中,
findDirectCollectButtons 返回 0。build666 优化导致 hasDailyRewardClaimedIndicator() 为 true 时
（"已领取"+"明天领肥料"）直接跳过 AI 视觉,进入 OPENING_TASK_LIST。
这会同时跳过"点击领取"和"点击跳转拿奖励"的 AI 视觉识别。
但"点击跳转拿奖励"和"点击领取"是不同按钮,即使每日签到奖励已领,"点击跳转拿奖励"仍可点击。

**修复 1**: [AutomationController.kt#L1076](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L1076)
- hasDailyRewardClaimedIndicator 跳过条件增加 `aiVisionDirectClickAttempted` 检查
- 尚未尝试 AI 视觉时不跳过,让 AI 视觉有机会识别"点击跳转拿奖励"按钮

**修复 2**: [AutomationController.kt#L1118-L1258](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L1118-L1258)
- AI 视觉目标根据每日奖励状态切换：
  - dailyClaimed=true → AI 视觉找"点击跳转拿奖励"按钮
  - dailyClaimed=false → AI 视觉找"点击领取"按钮（原逻辑）
- sceneContext 根据 dailyClaimed 切换位置提示
- AI 视觉找到"点击跳转拿奖励"后走专用流程（等 10 秒 + pressBack 返回主页）
  - 与 L1356 的按钮点击流程一致：点击 → 等待 10 秒 → pressBack → 继续下一轮
- AI 视觉找到"点击领取"后继续下一轮 COLLECTING_DIRECT（原逻辑）

**预期效果**:
- 每日奖励已领取时,AI 视觉不再被跳过,而是找"点击跳转拿奖励"按钮
- 找到后点击,等 10 秒拿奖励,pressBack 返回主页继续
- 找不到时（15 秒超时或 AI 未找到）,fallback 到 OPENING_TASK_LIST

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit (待提交) - fix: build678 watchAd 未处理"点击商品,领取奖励"广告 + 番茄畅听下载确认对话框

**用户需求**: "分析日志"

**日志分析** (build677, debug_test_20260801_144605.log, 14:44:22-14:45:58):
- 14:44:22-14:44:54 启动正常,导航到农场页,已领取检测生效(跳过 AI 视觉) ✅
- 14:44:56 任务列表打开,6 个"去完成"按钮
- 14:44:59 处理 task #1 "看视频得巨额肥料" (0/10) → 点击 → 进入穿山甲激励视频
- 14:45:04 isAdContentShown: YES, matched ad signal in [点击商品，领取奖励, 广告] ← 关键
- 14:45:05 进入 WATCHING_AD 状态
- 14:45:08-14:45:34 scene 一直被识别为 AD_ENDED ← **bug 1**
  - 实际页面有商品列表(淘宝精选/医用不锈钢剪刀直头弯/正品保证/¥7.8 等)
  - bot 没有点击商品,只是干等到超时
- 14:45:39 弹出"番茄畅听下载确认"系统对话框 ← **bug 2**
  - act=android.app.AlertDialog
  - texts=[您已下载的"番茄畅听"未下载完成（文件大小98.24 M），要继续下载吗, 取消, 确认]
  - bot 未识别此对话框,继续干等广告结束
- 14:45:49 AI progress percent=0%, remaining=0s, hasBar=false
- 14:45:58 用户手动停止(卡死 53 秒)

**根因 1**: `isClickProductAd()` 和 `findAdProductNode()` 已在 FarmAccessibilityService.kt 实现,
且在 `checkTaskListOpened()` (OPENING_TASK_LIST 状态) 中有调用逻辑(L1766-1810)。
但本次广告是从 `processTask` 进入 `WATCHING_AD` 状态的,`runWatchingAd` 函数中
**没有**处理"点击商品,领取奖励"广告的逻辑,导致 scene 误判 AD_ENDED 后干等到超时。

**根因 2**: 点击商品广告中的商品后,广告 SDK 可能触发应用下载,退出广告时弹出系统
AlertDialog("番茄畅听未下载完成,要继续下载吗")。原代码未识别此对话框,没有点"取消",
导致卡在对话框页面 13 秒直到用户手动停止。

**修复 1**: [AutomationController.kt#L4428-L4431](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L4428-L4431)
- watchAd 初始化时(elapsedMs == 0L)重置 `adProductClicked` 和 `adProductClickTimeMs`
- (原本只在 openTaskList 和 checkTaskListOpened 中重置,从 processTask 进入 watchAd 时未重置)

**修复 2**: [AutomationController.kt#L4940-L4996](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L4940-L4996)
- 在 watchAd 主循环 scene 检测之后、"点我加速"检测之前,添加 `isClickProductAd()` 处理:
  - 阶段1: 未点击商品时,`findAdProductNode()` 找商品节点并点击
  - 阶段2: 已点击商品后等 5s(让奖励触发),找关闭按钮或 pressBack 关闭广告
  - 重置标记,继续轮询(若仍在广告页,会重新尝试点击商品)
- 用户需求："点击商品，领取奖励，这只是一个提示，不需要点击，通过点击商品去获取奖励"

**修复 3**: [AutomationController.kt#L4998-L5019](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L4998-L5019)
- 在 watchAd 中添加"下载确认对话框"检测,优先点"取消"按钮
- 继续轮询等待广告恢复

**修复 4**: [FarmAccessibilityService.kt#L3605-L3652](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L3605-L3652)
- 新增 `isDownloadConfirmDialog()`: 检测 act=android.app.AlertDialog + 含"未下载完成"
- 新增 `findDownloadConfirmCancelButton()`: 查找"取消"按钮的可点击祖先节点

**预期效果**:
- "点击商品,领取奖励"广告出现时,主动点击商品获取奖励,等 5s 后关闭广告
- 不再干等 53 秒直到超时
- 退出广告时弹出"番茄畅听下载确认"对话框时,自动点"取消"不继续下载
- 不再卡在对话框页面

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit (待提交) - fix: build677 "我要更快拿奖"提前完成后未关闭广告页

**用户需求**: "分析日志，恭喜获得奖励 需要点击右上角关闭图标"

**日志分析** (build675, debug_test_20260801_111614.log, 11:15:23-11:16:14):
- 11:15:38 点击 task #1 "看视频得巨额肥料" → 进入穿山甲激励视频
- 11:15:45 点击"我要更快拿奖" → 弹窗点"继续了解详情" → 跳转新 App
- 11:16:00 检测到"已完成浏览10秒，提前获得奖励"(taskComplete=true) ← 奖励已发放
- 11:16:05 仍在广告页（faster reward staying in new app, 15176/16000ms）← 没关闭
- 11:16:10 用户手动停止（卡在广告页 16 秒）

**根因**: watchAd "我要更快拿奖"流程阶段2（新 App 停留期间）检测到 taskComplete=true
（"已完成浏览10秒，提前获得奖励"）时,奖励已发放,但代码继续等满 16 秒才进入阶段3
（关闭新 App,等"恭喜获得奖励提升"弹窗点右上角关闭图标）。

**修复**: [AutomationController.kt#L4556-L4580](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L4556-L4580)
- 阶段2 停留期间,检测到 isTaskCompletePage() 时（"已完成浏览10秒，提前获得奖励"）
- 提前进入阶段3：关闭新 App + 激活农场 App + 等待"恭喜获得奖励提升"弹窗
- 阶段3 会点击右上角关闭图标（findAdCloseButton/findBackIcon）退出广告
- 不再继续等满 16 秒

**预期效果**:
- "已完成浏览10秒，提前获得奖励"出现后立即关闭新 App
- 进入阶段3 点击右上角关闭图标退出"恭喜获得奖励提升"弹窗
- 不再卡在广告页等满 16 秒

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit (待提交) - fix: build676 倒计时停滞退出后未回农场页的修复

**用户需求**: "分析日志"（build675 日志 debug_test_20260801_100054.log）

**日志分析** (build675, UC 平台, 09:59:28-10:00:54):
- ✅ build666 已领取跳过 AI 视觉生效
- ✅ build674 修复2（"已领取"误判）有效（task #1 "看视频"没被误判完成）
- ✅ build671 倒计时停滞检测生效（15秒后仍 30秒,pressBack 退出）
- ❌ **bug**: 倒计时停滞退出后仍在淘宝页,openTaskList 误把淘宝页当农场页 reset 任务进度
  - 10:00:07 pressBack 退出广告,仍在淘宝 TMSActivity
  - 10:00:12 WATCHING_AD → OPENING_TASK_LIST（错误！应该先回农场页）
  - 10:00:15 openTaskList reset currentTaskIndex=0,丢失 task #1 进度
  - 10:00:18 navigate 误判 isRechargePage=YES（淘宝百亿补贴页）
  - 10:00:23-10:00:34 杀淘宝后跳到桌面 launcher,卡死找不到"芭芭农场"入口

**根因**: watchAd 倒计时停滞退出后,只 pressBack 一次,如果没回农场页就继续 OPENING_TASK_LIST,
导致 openTaskList 误把非农场页当农场页处理,reset 任务进度,后续 navigate 误判。

**修复**: [AutomationController.kt#L4949-L4967](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L4949-L4967)
- 倒计时停滞 pressBack 退出后,检测是否回到农场页
- 如果没回农场页,走 NAVIGATING 重新导航回农场页（而不是直接 OPENING_TASK_LIST）
- 已回农场页时才继续 OPENING_TASK_LIST

**预期效果**:
- 倒计时停滞退出后不再误把淘宝页当农场页
- 不丢失任务进度（不再 reset currentTaskIndex=0）
- 正确回农场页后继续处理任务

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit (待提交) - feat: build675 点击"点我加速"按钮加速广告

**用户需求**: "点击'我要加速'"

**日志证据** (debug_test_20260801_094058.log, build673, 09:40:42.914):
- 穿山甲激励视频广告页面 texts=[..., 点我加速, 限时福利, 13秒后失效, 去体验, 15秒]
- "点我加速"是穿山甲激励视频的加速按钮,点击后可加速倒计时,让广告更快结束

**实现**: [AutomationController.kt#L264-L266](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L264-L266) + [L4426-L4427](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L4426-L4427) + [L4911-L4932](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L4911-L4932)
- 新增字段 `adSpeedUpClicked`（每轮广告只点一次）
- 广告初始化时重置标记
- watchAd 非陷阱场景中,广告播放期间（elapsedMs < min wait 且 >= 1000ms）检测"点我加速"按钮
- 检测到时点击,加速倒计时,节省等待时间

**预期效果**:
- 穿山甲激励视频广告播放时自动点击"点我加速"按钮
- 加速倒计时,让广告更快结束,节省等待时间

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit (待提交) - fix: build674 活动页面任务加载等待 + "已领取"误判修复

**用户需求**: "分析日志"（build673 日志 debug_test_20260801_094058.log）

**日志分析** (build673, UC 平台, 09:38:16-09:40:55):
- ✅ build672 阈值生效（realContent=37 通过）
- ✅ build673 活动页面检测生效（跳过 AI 视觉）
- ❌ **bug1**: openTaskList 卡死 62 秒（09:38:36-09:39:38）
  - 活动版页面任务直接在主页显示,但首次 findGoCompleteButtons 返回空（页面加载慢）
  - 原逻辑点"集肥料"5 次试图打开任务列表（活动页面根本不需要点"集肥料"）
  - 09:39:45 第 6 次终于找到 7 个任务按钮（69 秒后页面才加载出任务节点）
  - 根因：活动版页面不需要点"集肥料",应该直接等待 findGoCompleteButtons 加载
- ❌ **bug2**: task #2 "看视频"误判完成 5 次（09:39:58-09:40:35）
  - 09:39:58 点击 task #2 "看视频得巨额肥料" → 没进入广告（仍在农场页）
  - 09:40:05 checkTaskResult 检测到"已领取"+"明天领肥料" → 误判 task #2 完成
  - 但这是 task #1 "签到"的标识,不是 task #2 完成的标识
  - 导致 task #2 重试 5 次都误判完成,直到第 6 次点击真正进入广告
  - 根因：checkTaskResult 的"已领取"检测不区分任务,所有任务都用这个标识判断完成

**修复1**: [AutomationController.kt#L1521-L1533](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L1521-L1533)
- openTaskList 中检测到活动版页面时,不点"集肥料"
- 直接重试 findGoCompleteButtons 等待任务节点加载
- 每次重试间隔 INTERVAL_PAGE_LOAD_MS,直到任务加载完成

**修复2**: [AutomationController.kt#L3411-L3426](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L3411-L3426)
- checkTaskResult 的"已领取"检测加 currentTaskIndex == 0 条件
- 只对 task #1 "签到"有效,后续任务不能用"已领取"标识判断完成
- "已领取"+"明天领肥料"是签到的标识,与后续任务（看视频/浏览等）无关

**预期效果**:
- 活动版页面不再点"集肥料"5 次,直接等待任务加载
- task #2 及后续任务不再因"已领取"标识误判完成
- 避免无效重试（5 次误判完成 + 6 秒/次 = 30 秒浪费）

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit (待提交) - fix: build673 活动版农场页面检测（跳过 AI 视觉）

**用户需求**: "解决bug"（基于 build671 日志 debug_test_20260801_092504.log 分析）

**日志分析** (build671, UC 平台, 09:23:48-09:25:04):
- UC 芭芭农场改版（"8.4内完成3天即领"活动页面）,页面结构完全不同：
  - 没有标准版的"点击领取"按钮和"已领取"/"明天领肥料"标识
  - 任务直接显示在主页（"施肥3次"/"完整观看广告1次"/"去支付宝逛蚂蚁庄园"）
  - 有"今日任务余 3"任务剩余数标识
  - realContent=36-40（build672 阈值 >= 30 可通过）
- collectDirect 找不到按钮 → hasDailyRewardClaimedIndicator 返回 false → 触发 AI 视觉 15 秒超时
- 根因：活动页面根本没有"点击领取"按钮,AI 视觉识别必然失败

**修复**: [FarmAccessibilityService.kt#L5383-L5411](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L5383-L5411) + [AutomationController.kt#L1081-L1093](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L1081-L1093)
- 新增 `isActivityFarmPage()` 方法检测活动版农场页面：
  - 特征1："今日任务余"（活动版独有标识,标准版没有）
  - 特征2："完成"+"即领"（活动标题,如"8.4内完成3天即领"）
- collectDirect 中 hasDailyRewardClaimedIndicator 之后加 isActivityFarmPage 检测
- 检测到活动页面时,跳过 AI 视觉直接进入 OPENING_TASK_LIST 处理任务

**预期效果**:
- 活动版农场页面不再触发 AI 视觉 15 秒超时
- 直接进入 OPENING_TASK_LIST 处理活动任务（施肥3次/完整观看广告1次等）

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit (待提交) - fix: build672 农场页阈值降回 + 锁屏检测

**用户需求**: "分析日志"（build671 日志 debug_test_20260801_082040.log）

**日志分析** (build671, UC 平台, 08:16:28-08:20:36):
- ❌ **问题1**: build668 阈值 >= 80 过高,UC 简化版农场页 realContent=35 过不了
  - 08:17:03-08:20:33 持续 3 分多钟,text count=37, realContent=35
  - sample=[..., UC芭芭农场, 8.4内完成3天即领, 1天, 2天] ← 已含农场专属内容
  - 这是活动期间简化版农场页,节点数少但已加载完成
  - build668 阈值 >= 80 过于激进,导致永远过不了阈值
- ❌ **问题2**: 锁屏期间 navigate 误判为 generic popup,无限 pressBack
  - 08:17:37 手机锁屏（activeRootPkg='com.android.systemui'）
  - navigate 识别为 GENERIC_POPUP（"手电筒已关闭"desc）
  - 每 5 秒循环 pressBack（锁屏状态无效）,卡死 2 分 44 秒
  - 直到用户手动解锁才恢复

**修复1**: [FarmAccessibilityService.kt#L389-L398](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L389-L398)
- hasFarmContentLoaded 阈值从 >= 80 降为 >= 30
- realContent=35（简化版农场页）能通过,realContent=12/33（未渲染完）不通过
- 配合 hasDailyRewardClaimedIndicator 和 AI 视觉兜底,即使节点少也能正确处理

**修复2**: [AutomationController.kt#L890-L905](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L890-L905)
- navigate generic popup 处理前加锁屏检测
- 检测到 com.android.systemui 时,不当作 generic popup 处理
- 延长等待间隔到 15 秒,等用户解锁后继续
- 避免锁屏期间无限 pressBack 循环

**预期效果**:
- UC 简化版农场页（realContent=35）正常通过加载检测
- 锁屏期间不再误判为 generic popup,避免无限 pressBack 循环

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit (待提交) - fix: build671 watchAd 倒计时停滞检测（静态文字伪装倒计时）

**用户需求**: "分析日志"（build669 日志 debug_test_20260731_214538.log）

**日志分析** (build669, UC 平台, 21:43:32-21:45:34):
- ✅ build666 已领取跳过 AI 视觉生效
- ✅ build669 "助力"误匹配修复生效（task #1 "看视频得巨额肥料" 不再被跳过）
- ❌ **问题**: watchAd 卡死 62 秒直到用户手动停止
  - 21:44:25 点击 task #1 "看视频得巨额肥料" → 跳转淘宝 TMSActivity
  - 21:44:32 检测到 "30秒" 倒计时,设置 min wait=32000ms, max wait=90000ms
  - 21:44:32-21:45:34 持续 62 秒, "30秒" 倒计时从未减少（30+ 次检测全是 30秒）
  - texts=[返回, 淘宝精选, 更多_细9 按钮, ...] → "淘宝精选" 说明是淘宝商品页,不是广告页
  - "30秒" 是静态文字（商品页描述）,非动态倒计时
  - 根因：watchAd 检测到 "30秒" 就认为是广告倒计时,不检测倒计时是否在减少
  - 影响：bot 卡在 WATCHING_AD 62 秒,直到 90 秒超时或用户手动停止

**修复**: [AutomationController.kt#L254-L263](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L254-L263) + [L4379-L4381](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L4379-L4381) + [L4859-L4887](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L4859-L4887)
- 新增字段 `adInitialCountdownSeconds`（记录初始倒计时值）和 `adCountdownStallHandled`（避免重复退出）
- elapsedMs==0 时记录初始倒计时值
- 在场景识别之后、超时检查之前,加倒计时停滞检测：
  - 若 elapsedMs >= 15000 且当前倒计时 == 初始倒计时（未减少）
  - 判定为静态文字伪装倒计时,pressBack 退出跳过任务
- 不影响真正广告（真正广告 15 秒后倒计时会减少到约 15-20 秒）

**预期效果**:
- 淘宝商品页的静态 "30秒" 文字不再导致 bot 卡死 62 秒
- 15 秒后检测到倒计时未减少,直接退出跳过任务
- 真正广告不受影响（倒计时会正常减少）

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit (待提交) - fix: build670 "去支付"误匹配"去支付宝"

**用户需求**: "继续修复"（基于 build669 日志 debug_test_20260731_213229.log 分析）

**日志分析** (build669, UC 平台, 21:31:36-21:32:26):
- ✅ build668 阈值生效：realContent=12 时判定未加载完,等 6 秒到 realContent=135 才进入下一阶段
- ✅ build666 已领取跳过 AI 视觉生效：检测到"已领取"+"明天领肥料",跳过 AI 视觉 15 秒超时
- ✅ build669 修复1 生效：task #1 "看视频得巨额肥料 (0/10) 助力果树光速升级↑" 不再被"助力"误跳过
- ❌ **问题**: isPaidTask 误判"去支付宝农场领肥料"和"去支付宝逛蚂蚁庄园"为付费任务
  - 21:32:03.676 isPaidTask: YES, context='去支付宝农场领肥料 去支付宝农场领肥料 逛逛可得 肥料 +600 去完成 '
  - 21:32:03.711 isPaidTask: YES, context='去支付宝逛蚂蚁庄园 去支付宝逛蚂蚁庄园 本次可得 肥料 +600 去完成 '
  - 根因：paidKeywords 含"去支付",而"去支付宝".contains("去支付")=true（"去支付宝"包含"去支付"三字子串）
  - 影响：跨平台浏览任务被误判为付费任务(priority=1),可能被跳过无法领取跨平台肥料

**修复**: [FarmAccessibilityService.kt#L2807-L2813](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L2807-L2813)
- paidKeywords 移除"去支付"关键词
- "立即支付"/"确认支付"已足够覆盖支付类任务（支付按钮文案通常是"立即支付"/"确认支付",不是"去支付"）
- "去支付宝农场领肥料"/"去支付宝逛蚂蚁庄园"不再被误判为付费任务

**预期效果**:
- 跨平台浏览任务（去支付宝农场/去支付宝逛蚂蚁庄园）正确识别为非付费任务
- 不再被错误标记为 paid priority=1,正常进入任务处理流程

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit (待提交) - fix: build669 "助力"误匹配 + 浏览任务无进度信号提前退出

**用户需求**: "分析日志"（build666 日志 debug_test_20260731_211710.log, 第二次运行 21:14:43-21:17:06）

**日志分析** (build666, UC 平台, 第二次运行):
- ✅ build666 修复生效：页面加载完整（realContent=135）时 hasDailyRewardClaimedIndicator 正确返回 true
  - 21:15:00.113 collectDirect: daily reward already claimed, skip AI vision, go to task list
  - 跳过 AI 视觉 15 秒超时,直接走任务列表
- ✅ build664 修复生效：跨 App 浏览任务正确识别（UC→淘宝, "cross-app browse task page detected"）
- ❌ **问题1**: task #1 "看视频得巨额肥料" 被错误跳过
  - 21:15:05.645 processTask: skip list task #1, text='去完成', context='看视频得巨额肥料 (0/10) 助力果树光速升级↑ 肥料 +1200 去完成'
  - 根因：skipTaskTexts 含"助力"关键词,误匹配"助力果树光速升级"宣传语
  - "助力"是 build650 为跳过"邀请好友助力"任务加的,但"助力果树光速升级"是看视频任务的宣传语,不是邀请任务
- ❌ **问题2**: task #2 "浏览广告赚肥料" 跳转淘宝后滑动 47 次（约 90 秒）未完成
  - 21:15:14 页面 texts=[..., 浏览得奖励, 下单得奖励, 30秒, ] ← "30秒"是静态文字,非动态倒计时
  - swipe #1~#47: countdown=0s, progress=false, remainingProgress=false（三个信号全无）
  - 21:17:06 用户手动停止（滑动 47 次还在 "keep swiping within wait limit"）
  - 根因：滑动达标（18次）后,原逻辑继续滑到 waitLimit（48次）才退出,浪费时间

**修复1**: [AutomationController.kt#L2023-L2031](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L2023-L2031)
- skipTaskTexts 移除单独的"助力"关键词,只保留"邀请好友"
- "邀请好友"已足够覆盖"邀请好友助力"等所有邀请类任务
- "看视频得巨额肥料 助力果树光速升级↑"不再被误匹配,正常进入看视频流程

**修复2**: [AutomationController.kt#L2698-L2713](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L2698-L2713)
- browseTask 滑动达标（browseTaskTargetSwipes）后,检测三个进度信号：
  countdownSeconds、hasProgressHint、hasRemainingProgress
- 若三个信号全无（说明浏览计时未触发,可能需要点击商品或下单才完成）,
  再给 3 次机会（约 6 秒）确认,仍无信号则退出跳过
- 不影响正常浏览任务（正常任务滑动后有动态倒计时/进度提示,hasProgressHint=true 会继续等待）
- 避免无意义滑动 47 次（90 秒）浪费时间

**预期效果**:
- "看视频得巨额肥料"任务不再被误跳过,正常点击进入看视频广告流程
- "浏览广告赚肥料"跳转淘宝后,滑动 18+3=21 次（约 42 秒）无进度信号即退出跳过
- 不再卡在浏览任务上空滑 90 秒,提高整体任务处理效率

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit (待提交) - fix: build668 农场页加载阈值 + 签到后"已领取"跳过

**用户需求**: "分析日志"（build666 日志 debug_test_20260731_210558.log）

**日志分析** (build666, UC 平台):
- 21:04:55 进入农场页 hasFarmContentLoaded=true,但 text count=34（正常 150+）,realContent count=33
- → 页面 H5 未渲染完就判定加载完成 → COLLECTING_DIRECT 找不到"已领取"标识
- → hasDailyRewardClaimedIndicator 返回 false（"已领取"文字还没渲染出来）
- → 触发 AI 视觉找"点击领取" 15 秒超时（截图也是加载不完整的页面）
- 21:05:23 点击 task #1 "签到"成功 → 21:05:30 页面出现"已领取"+"明天领肥料"
- → 签到 = 当天"点击领取",已领成功,但 processTask 未识别
- 21:05:31 误判 isRechargePage=YES（sample 只有底部导航 6 个文字,页面仍在加载）
- → 跳过签到,重试点击 task #2,进入广告,用户手动停止

**根因1**: [FarmAccessibilityService.kt#hasFarmContentLoaded#L390](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L390)
- 原 `realContent.isNotEmpty()` 阈值过低,realContent count=33 就判定加载完成
- UC 芭芭农场主页完整加载后 realContent count 通常 >= 130（build664 日志 count=144）

**修复1**: [FarmAccessibilityService.kt#L390-L396](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L390-L396)
- 提高阈值到 `realContent.size >= 80`
- 确保页面渲染出主要节点（含"已领取"/"明天领肥料"/任务列表等）再进入下一阶段
- 避免页面未加载完就触发 AI 视觉 15 秒超时

**根因2**: [AutomationController.kt#checkTaskResult#L3338](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L3338)
- 点击"签到"后页面变"已领取"+"明天领肥料",但 checkTaskResult 没识别这一状态
- 继续走原流程,误判 isRechargePage,重试点击签到（实际已领成功）

**修复2**: [AutomationController.kt#L3343-L3361](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L3343-L3361)
- checkTaskResult 最前面加"已领取"+"明天领肥料"检测
- 检测到时认定当前签到/领取任务已完成,collectedCount++ + advanceTaskIndex() + 前进下一任务
- 不再重试点击签到,避免误判 isRechargePage 跳过

**预期效果**:
- 进入农场页时等页面渲染完（realContent >= 80）再进 COLLECTING_DIRECT
- 页面渲染完后"已领取"标识已出现,hasDailyRewardClaimedIndicator 正确返回 true,跳过 AI 视觉
- 点击签到后页面变"已领取"时,正确识别签到已完成,前进下一任务,不再误判 isRechargePage
- 签到领肥料流程顺畅,不再卡在签到任务上

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit (待提交) - fix: build667 AI 视觉识别"点击领取"加入位置提示（果树右下侧）

**用户需求**: "点击领取按钮在uc芭芭农场主页，果树的右下侧区域"（用户截图反馈按钮位置）

**问题**: build666 已让 attempt=0 即触发 AI 视觉识别"点击领取"按钮坐标，但 sceneContext 只传了"UC芭芭农场主页, 平台=UC, pkg=..., act=..."，GLM-4.6V-Flash 在页面有多处装饰性"领取/可领取/签到肥料"文字时可能误识别坐标。

**修复**: [AutomationController.kt#L1090-L1096](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L1090-L1096)
- collectDirect 触发 AI 视觉时的 sceneContext 追加位置提示：
  `目标「点击领取」按钮位于果树右下侧区域（图像按钮，橙色/红色彩色背景）`
- 让 GLM-4.6V-Flash 优先在果树右下侧区域定位，避免误识别签到肥料/任务卡片的装饰文字

**预期效果**:
- AI 视觉识别"点击领取"更精准，减少误识别其他装饰性文字的概率
- 不改变原有识别流程，仅增强 prompt 上下文

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit (待提交) - fix: build666 UC 主页"点击领取"优先点击 + 已领取标识跳过 AI 视觉

**用户需求**: "uc芭芭农场主页的'点击领取'按钮应该优先点击"

**日志分析** (build664, debug_test_20260730_074843.log):
- 07:47:19.098 [collectDirect-start] claim-text-nodes: text='已领取' bounds=[894,933][1123,1031]
- 07:47:19.118 collectDirect: found 0 direct buttons, attempt=0
- → 当天"点击领取"奖励已领（按钮已变"已领取"），但原逻辑仍等 AI 视觉识别 15 秒超时才走任务列表
- → UC 主页"点击领取"是 H5/Canvas 图像按钮，无障碍树抓不到 text 节点，需 AI 视觉识别
- → 原 attempt >= 1 才触发 AI 视觉，attempt=0 空转一次浪费时间

**修复1**: [FarmAccessibilityService.kt#L5360](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L5360)
- 新增 `hasDailyRewardClaimedIndicator()` 方法
- 检测页面是否有"已领取"+"明天领肥料"组合（UC 主页每日奖励领取后的标准标识）
- 若有，说明当天奖励已领，无需 AI 视觉识别

**修复2**: [AutomationController.kt#L1040](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L1040)
- collectDirect 在 buttons 为空且 attempt=0 时，先检测 hasDailyRewardClaimedIndicator
- 若已领取，直接跳过 AI 视觉走 OPENING_TASK_LIST（省 15 秒超时）
- 若未领取，attempt=0 就触发 AI 视觉识别（原 attempt>=1 改为 attempt>=0），让"点击领取"更快被识别点击

**预期效果**:
- 当天已领过时：collectDirect 立即跳过 AI 视觉走任务列表（省 15 秒）
- 当天未领时：attempt=0 即触发 AI 视觉识别"点击领取"按钮坐标并点击（优先点击）
- 不再有空转和无效等待

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 04205d7 - fix: build665 UC 跨 App 浏览任务页 hint 检测失效修复

**用户需求**: "分析日志"（从 GitHub 拉取 build664 日志 debug_test_20260730_074843.log）

**日志分析** (build664, UC 平台):
- ✅ build664 修复生效：跨 App 跳转淘宝不再误判为恶意跳转（无"cross-app jump detected"）
- ❌ **问题**: 跳转淘宝后 findSwipeForFertilizerHint 等 4 指标全 false → "not a browse task, exiting"
  - 07:47:53.072 browseTask: after clicking 'go browse', page type=non_farm, onFarm=false
  - 07:47:53.125 browseTask: no swipe hint and no browse reward indicator, not a browse task, exiting without swiping
  - 页面含"浏览得奖励, 下单得奖励, 30秒" —— 是正常浏览任务页，但 4 个指标都没识别到

**根因**: [FarmAccessibilityService.kt#findSwipeForFertilizerHint#L1302](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L1302)
- findSwipeForFertilizerHint/findBrowseProgressInfo/findBrowseRewardCountdownHint 等都用 getRootInFarmApp()
- UC 平台跳转淘宝后，淘宝包名 com.taobao.taobao 不在 UC 的 packageNames 里
- getRootInFarmApp() 返回 null → 所有 hint 检测返回 0/NONE → 4 指标全 false → 误判"not a browse task"退出

**修复**: [AutomationController.kt#L2347](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L2347)
- 在 build664 的 isBrowseTaskPageOnCrossApp 检测后，如果是跨 App 浏览任务页
- 从已采集的 allText 中解析"N秒"倒计时（如"30秒"），设置 browseTaskTargetSwipes
- 直接开始滑动，不依赖 findSwipeForFertilizerHint（它因 getRootInFarmApp 返回 null 无法工作）
- 兜底 30 秒（若解析不到具体秒数）

**预期效果**:
- UC 浏览任务跳转淘宝后，正确识别为跨 App 浏览任务页，滑动 30 秒（15 次）等待完成
- 不再因 getRootInFarmApp 返回 null 导致 4 指标全 false 误判退出
- 滑动完成后由 isFertilizerGrantedPage/isTaskCompletePage 检测完成并退出

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 1ed8067 - fix: build664 UC 浏览任务跳转淘宝不误判为跨 App 跳转

**用户需求**: "分析日志"（从 GitHub 拉取 build663 日志 debug_test_20260730_074150.log）

**日志分析** (build663, UC 平台):
- ✅ build663 修复生效：isPaidTask 识别付费任务、task #1 "看视频得巨额肥料" 正确跳过（cross-platform）
- ❌ **问题**: task #2 "浏览广告赚肥料" 点击"去完成"后跳转到淘宝浏览任务页，被误判为跨 App 跳转
  - 07:41:29.399 browseTask: after clicking 'go browse', page type=non_farm, onFarm=false
  - 07:41:29.406 browseTask: cross-app jump detected (currentPkg=com.taobao.taobao, farmPkg=[com.ucmobile.lite])
  - 页面内容含"浏览得奖励, 下单得奖励, 30秒"和商品列表 —— 是正常浏览任务页
  - 误判后 pressBack 退出 → re-launch UC 失败 → NAVIGATING -> STOPPING

**根因**: [AutomationController.kt#browseTask#L2309](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L2309)
- 跨 App 检测仅看包名（com.taobao.taobao != com.ucmobile.lite），未判断跳转后的页面是否是浏览任务页
- UC 农场浏览任务会跳转到淘宝（UC 和淘宝共种一棵树），这是正常行为，不是恶意跨 App 跳转

**修复**: [AutomationController.kt#L2321](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L2321)
- 跨 App 检测前，先判断页面是否是浏览任务页（含"浏览得奖励"/"浏览得"且含"N秒"倒计时）
- 若是浏览任务页，不视为恶意跨 App 跳转，继续走浏览流程（滑动等待完成）
- 区分依据：build648 的闲鱼跳转页面无"浏览得奖励"文案，本次淘宝浏览页有

**预期效果**:
- UC 浏览任务跳转到淘宝时，识别为浏览任务页，继续滑动浏览 30 秒等待完成
- 闲鱼等无关 App 跳转仍会被正确识别并退出（无"浏览得奖励"文案）
- 不再因误判导致 pressBack 退出 → re-launch 失败 → STOPPING

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 7e826b1 - fix: build663 browseTask 滑动后仍在农场主页则提前退出跳过任务

**用户需求**: "分析日志"（从 GitHub 拉取 build662 日志 debug_test_20260728_083516.log）

**日志分析** (build662, UC 平台):
- ✅ build662 两个修复均生效：
  - isPaidTask clickable 检查修复生效（`isPaidTask: YES, context='去支付宝逛蚂蚁庄园...'`，不再 "skip, button not clickable"）
  - 互动广告 pressBack 过早修复生效（本次广告是 HCRewardVideoActivity 汇川广告，流程正常）
- ✅ task #1 "签到" 已签（点击后页面变"已领取"），3 次重试后正确跳过
- ❌ **问题**: task #2 "浏览广告赚肥料 (0/10)" 点击"去完成"后未跳转到商品浏览页
  - 08:34:41.950 browseTask: found progress info type=FRACTION, cur=0, tot=10, percent=0%
  - 08:34:41.958 browseTask: skipping product click, swiping in list page directly
  - 08:34:47~08:35:10 swipe #1~#7（pageType=farm_home, countdown=0s, progress=false）
  - → 在农场主页滑动 7 次无效，进度一直 0/10，浪费 25 秒
  - → 08:35:10 用户手动停止（STOPPING 后立即上传日志）

**根因**: UC 平台"去完成"按钮 clickable=false，dispatchGesture 坐标点击在某些 WebView 场景下未触发跳转，仍留在农场主页（onFarm=true, pageType=farm_home）。browseTask 滑动循环中无"仍在农场主页"检测，导致空滑 7 次浪费时间。

**修复**: [AutomationController.kt#scheduleNextBrowseCheck#L2832](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L2832)
- 滑动 2 次后若仍在农场主页（onFarm=true 且 pageType=farm_home）且无倒计时/进度
- 说明"去完成"点击未跳转，提前退出跳过任务，避免空滑浪费时间
- 不影响正常浏览任务（正常浏览任务点击后会跳转到商品/活动页，pageType != farm_home）

**预期效果**:
- UC 平台"去完成"点击未跳转时，滑动 2 次（约 8 秒）后即退出跳过，不再空滑 7 次（25 秒）
- 正常浏览任务不受影响（落地页是商品/活动页，不会误判为 farm_home）

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit f807e14 - fix: build662 UC 平台 isPaidTask clickable 检查过严 + 互动广告 pressBack 过早

**用户需求**: "分析日志"（从 GitHub 拉取 build661 日志 debug_test_20260727_072301.log）

**日志分析** (build661, UC 平台):
- ✅ build660/661 编译成功，UC 平台导航、collectDirect、跨平台切换、看广告流程基本正常
- ❌ **问题1**: UC 平台所有任务"去完成"按钮 clickable=false，isPaidTask 全部返回 false
  - 07:21:28.949 isPaidTask: skip, button not clickable（task #1 "看视频得巨额肥料"）
  - 07:21:30.147 isPaidTask: skip, button not clickable（task #2 "去头条极速版逛逛"）
  - 07:21:31.362 isPaidTask: skip, button not clickable（task #3 "去头条看热点"）
  - 07:21:32.576 isPaidTask: skip, button not clickable（task #4 "去支付宝农场领肥料"）
  - 07:22:14.199 isPaidTask: skip, button not clickable（task #5）
  - → build658 的付费任务识别（余额宝/攒一笔等）在 UC 平台完全失效
  - → 如果 UC 出现付费任务（如"攒一笔到余额宝"），会被点击执行 → 跳转 → isNonAdTaskPage 误判 → 死循环
- ❌ **问题2**: 互动广告无下载按钮时 5s 就 pressBack，但广告倒计时 10s 没结束，pressBack 无效
  - 07:22:21.859 findAdDurationHint: found countdown '10秒', seconds=10
  - 07:22:27.326 watchAd: interactive ad no download button, pressBack to exit（5s 时 pressBack）
  - 07:22:39.584 openTaskList: resetting currentTaskIndex to 0（状态已切但实际卡在广告 Activity）
  - 07:22:42.124 navigate: UC ad (act=KsRewardVideoActivity), waiting instead of pressBack
  - 07:22:56.746 state: NAVIGATING -> STOPPING（反复等待 3 次失败）

**根因1**: [FarmAccessibilityService.kt#isPaidTask#L2761](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L2761)
- 原防御性检查 `if (!button.isClickable) return false` 过严
- UC 平台任务列表"去完成"按钮在 WebView 内 clickable=false（需 dispatchGesture 点击）
- 支付宝平台 clickable=true，所以 build658 在支付宝能正常识别付费任务，UC 失效

**修复1**: [FarmAccessibilityService.kt#L2755-2779](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L2755)
- 去掉 `if (!button.isClickable) return false` 检查
- 仅保留文本特征检查（条款编号开头 `^[0-9]+[、.）)]` 或长度>50 字）
- 规则条款文本节点仍会被文本特征检查过滤，不会回归原防御目的
- UC 平台 clickable=false 的真实任务按钮现在能正常走付费关键词检查

**根因2**: [AutomationController.kt#runWatchingAd#L4629](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L4629)
- 互动广告无下载按钮时 5s 就 `service.pressBack()` + `currentTaskIndex++` + 切 OPENING_TASK_LIST
- 但 UC 激励视频广告倒计时可能还没结束（如 10s），pressBack 对激励视频无效（build580 已确认）
- 状态切了但 Activity 没退出 → navigate 阶段 "UC ad, waiting instead of pressBack" 反复等待 → STOPPING

**修复2**: [AutomationController.kt#L4629-4646](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L4629)
- 互动广告无下载按钮时不主动 pressBack，继续轮询等待广告自然结束
- 倒计时结束后 `isAdEndedMultiSignal` 检测"恭喜获取奖励"等信号 → CLOSING_AD 主动关闭
- 或 `adMaxDurationMs`（hint+buffer）超时后兜底退出
- 两种方式都比 5s 盲目 pressBack 更可靠

**预期效果**:
- UC 平台付费任务（余额宝/攒一笔/充值等）能被正确识别跳过，不再因 clickable=false 失效
- 互动广告无下载按钮时不再 5s 盲目 pressBack，等广告自然结束后由 CLOSING_AD 流程关闭
- 不再出现"状态切了但卡在广告 Activity"导致 NAVIGATING 反复等待 STOPPING

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 2951330 - fix: build661 修复 build660 编译错误（currentPlatform 应为 service.currentPlatform）

**用户需求**: "分析日志"（从 GitHub 拉取 build659 日志 debug_test_20260726_213808.log）

**日志分析** (build659, 支付宝平台):
- ✅ build658 修复生效：task #1 "攒一笔到余额宝" 被识别为付费任务跳过
- ✅ build659 修复生效：task #2 "逛好物最高得3000肥" 浏览任务正常滑动
  - 21:36:24.074 findSwipeForFertilizerHint: found hint '滑动浏览15秒得肥料', seconds=15
  - 21:36:24.128 browseTask: target swipes = 9 (hint=15 seconds)
  - 21:36:53.721 isFertilizerGrantedPage: YES (已获得肥料)
  - 21:36:53.723 browseTask: fertilizer granted detected during swipe, exiting via RETURNING ✅ 获得肥料
- ❌ **新问题**: RETURNING 阶段 kill 支付宝后无法重启到前台，NAVIGATING 反复搜索失败直到 STOPPING

**根因**: [AutomationController.kt#runBrowsingTask#L2569](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L2569) + [FarmAccessibilityService.kt#reopenFarmByDeepLink#L5689](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L5689)
- isFertilizerGrantedPage 命中后无条件走 RETURNING → reopenFarmByDeepLink
- ALIPAY 无 farmDeepLink（=null），也无桌面快捷方式（FarmShortcutLauncher.startFarmShortcut 返回 false）
- reopenFarmByDeepLink 走 2b 分支：HOME + kill ALIPAY + relaunch 主 Activity
- Honor 后台启动限制导致支付宝 relaunch 后没到前台（activeRootPkg='com.hihonor.android.launcher'）
- NAVIGATING 反复搜索"芭芭农场"，搜索结果点击不跳转 → 3 次失败 → STOPPING
- 但浏览页和农场页都是支付宝内 WebView（XRiverActivity），pressBack 就能退回农场主页，根本不需要 kill

**日志证据** ([debug_test_20260726_213808.log#L170-266](file:///workspace/logs/debug_test_20260726_213808.log)):
- 21:36:53.724 state: BROWSING_TASK -> RETURNING
- 21:36:56.358 reopenFarmByDeepLink: no deep link for ALIPAY, killed + relaunch app, will navigate
- 21:36:56.380 reopenFarmByDeepLink: relaunched ALIPAY (com.eg.android.AlipayGphone)
- 21:37:01.416 isOnFarmPage: activeRootPkg='com.hihonor.android.launcher' is not farm app (支付宝没到前台)
- 21:37:02.499 navigate: farm app not in foreground (platform=ALIPAY, attempt=0), calling reopenFarmByDeepLink
- 21:37:21.469 navigateAlipay: 芭芭农场 entry is search node, skip and fallback to search
- 21:37:27.781 clicking search result '芭芭农场' (candidates=10) → click did not navigate
- 21:37:44.090 clicking search result → dispatchGesture also failed, pressBack and retry
- 21:38:03.480 state: NAVIGATING -> STOPPING

**修复**: [AutomationController.kt#L2580-2595](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L2580)
- isFertilizerGrantedPage 命中后，根据平台是否有 deep link 选择退回方式：
  - 有 deep link（UC/TAOBAO）：走 RETURNING → reopenFarmByDeepLink（原逻辑不变）
  - 无 deep link（ALIPAY）：走 exitBrowsePage（pressBack 退回农场主页）
- exitBrowsePage 会点击返回图标或 pressBack，等 INTERVAL_PAGE_LOAD_MS 后检查是否回到农场页
- 已回到农场页 → OPENING_TASK_LIST 继续处理下一个任务
- 未回到农场页 → NAVIGATING 重新导航（兜底）

**注意**:
- ALIPAY 浏览页和农场页都是 XRiverActivity 内的 WebView，pressBack 可靠
- UC/TAOBAO 有 deep link，kill+relaunch 后能直达农场页，保持原 RETURNING 逻辑
- exitBrowsePage 已有完善的退回逻辑（findBackIcon/pressBack/二次返回/商品列表页处理等）

**预期效果**:
- ALIPAY 浏览任务获得肥料后，pressBack 退回农场主页 → OPENING_TASK_LIST → 处理 task #3
- 不再 kill 支付宝导致无法重启到前台
- 不再 NAVIGATING 反复搜索失败
- UC/TAOBAO 平台行为不变（仍走 RETURNING）

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 0b6d616 - fix: build659 支付宝浏览页"滑动浏览15秒得肥料"被误判为任务描述跳过，导致浏览任务死循环

**用户需求**: "分析日志"（从 GitHub 拉取 build658 日志 debug_test_20260726_212518.log）

**日志分析** (build658, 支付宝平台):
- ✅ build658 修复生效：task #1 "攒一笔到余额宝" 被识别为付费任务跳过（21:18:25.045 processTask: skip paid task #$1）
- ❌ **新问题**: task #2 "逛好物最高得3000肥" 浏览任务死循环 9 轮直到 STOPPING
  - 点击"去完成"进入浏览页（isBrowseProductListPage=YES，hasFertilizerHint=true）
  - findSwipeForFertilizerHint 把"滑动浏览15秒得肥料"误判为任务描述跳过 → 返回 0
  - browseTask 4 个指标（countdown/progress/duration/progressInfo）全 false
  - "not a browse task, exiting without swiping" → 退出浏览
  - OPENING_TASK_LIST: resetting currentTaskIndex to 0（重置索引）
  - 又从 task #1 开始 → task #1 跳过 → task #2 又进浏览页 → 又退出 → 死循环

**根因**: [FarmAccessibilityService.kt#findSwipeForFertilizerHint#L1323](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L1323)
- build531 修复时把 `isTaskDescription` 判断设为：`text.contains("得") && (text.contains("肥料") || text.contains("肥") || text.contains("奖励"))`
- 目的是跳过任务列表里的"浏览15秒得1000肥料"（含具体奖励数字）
- 但支付宝浏览页提示文案"滑动浏览15秒得肥料"也含"得"+"肥料"（无具体数字）→ 被误判为任务描述跳过
- findSwipeForFertilizerHint 返回 0 → browseTask 误判"not a browse task" → 立即退出 → 重置索引 → 死循环

**日志证据** ([debug_test_20260726_212518.log#L1938-1947](file:///workspace/logs/debug_test_20260726_212518.log)):
- 21:24:57.602 browseTask: page type=browse_duration, texts=[..., 滑动浏览15秒得肥料, ...]
- 21:24:57.647 isBrowseProductListPage: YES (hasFertilizerHint=true)
- 21:24:57.671 findSwipeForFertilizerHint: skip task description '滑动浏览15秒得肥料'  ← 误跳过
- 21:24:57.741 browseTask: no swipe hint and no browse reward indicator (countdown=false, progress=false, duration=false, progressInfo=false), not a browse task, exiting without swiping
- 21:24:57.749 exitBrowsePage: clicking back icon to exit
- 21:25:00.790 state: BROWSING_TASK -> STOPPING（防大循环兜底生效）
- 此循环在 21:18:35 / 21:18:54 / 21:19:13 / 21:19:32 / 21:19:51 / 21:20:16 / 21:20:35 / 21:20:53 / 21:21:12 / ... 重复 9+ 次

**修复**: [FarmAccessibilityService.kt#L1331](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L1331)
- 旧: `val isTaskDescription = text.contains("得") && (text.contains("肥料") || text.contains("肥") || text.contains("奖励"))`
- 新: `val isTaskDescription = Regex("得\\s*\\d+\\s*(?:肥料|肥|奖励)").containsMatchIn(text)`
- 要求"得"后面紧跟数字才视为任务描述：
  - "浏览15秒得1000肥料" → 匹配"得1000肥料" → 视为任务描述跳过 ✅
  - "滑动浏览15秒得肥料" → 不匹配（无具体数字）→ 视为页面提示，返回 15 秒 ✅
  - "每浏览15秒得一次奖励" → 不匹配（"一次"非纯数字）→ 视为页面提示，返回 15 秒 ✅

**注意**:
- 历史日志中只出现过"滑动浏览15秒得肥料"被误 skip 的案例，没有"得+数字+肥"任务描述被实际 skip 的案例
- 收紧条件不会回归 build531 修复（build531 针对的"浏览15秒得1000肥料"仍会被正确识别为任务描述）
- 修复后浏览页会正确返回 15 秒，browseTask 进入滑动流程，不再"not a browse task"立即退出

**预期效果**:
- 支付宝浏览页"滑动浏览15秒得肥料" → findSwipeForFertilizerHint 返回 15 → browseTask 滑动 9 次（15s/2s + 2 余量）
- 滑动完成后正常退出浏览 → 任务进度推进 → 不再死循环
- task #2 完成后继续处理 task #3（看精选商品得肥料）/ task #4（去庄园喂鸡）等

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 8937d00 - fix: build658 支付宝"攒一笔到余额宝"任务识别为付费任务，避免 isNonAdTaskPage 误判导致 currentTaskIndex 重置死循环

**用户需求**: "分析日志"（从 GitHub 拉取 build657 日志 debug_test_20260726_210643.log）

**日志分析** (build657, 支付宝平台):
- ✅ build657 支付宝导航修复生效：21:05:09 navigate: on farm page, platform=ALIPAY（搜索结果过滤生效，进入农场页）
- ✅ 任务列表正常打开：21:05:37 found 7 goComplete buttons
- ❌ **新问题**: task #1 "攒一笔到余额宝" 未识别为付费任务（paid=false）→ 点击"去完成"跳转余额宝转入页 → isNonAdTaskPage 误判 → currentTaskIndex 重置 → 死循环 4 轮直到 STOPPING

**根因**: [FarmAccessibilityService.kt#isPaidTask#L2765](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L2765)
- task #1 文案 "攒一笔到余额宝当前进度0，总进度1 完成得500肥料 去完成"
- paidKeywords 含"投资"/"理财"但不含"余额宝"/"攒一笔"/"转入"
- isPaidTask 返回 false → processTask 不跳过 → 点击"去完成"跳转余额宝转入页
- 余额宝转入页含"转入余额宝"/"立即转入"/"扣款"等文案
- isNonAdTaskPage 误判为非广告任务页（matched non-ad task）
- processTask: non-ad task page, skipping task #$1
- OPENING_TASK_LIST: resetting currentTaskIndex to 0（重置索引）
- 又回到 task #1 → 又跳转 → 又误判 → 又重置 → 死循环

**日志证据** ([debug_test_20260726_210643.log#L126-L148](file:///workspace/logs/debug_test_20260726_210643.log)):
- 21:05:39.998 processTask: current task #1/7, text='去完成' (攒一笔到余额宝)
- 21:05:40.006 isBrowseTask: isBrowse=false (未识别为浏览任务)
- 21:05:40.015 点击"去完成" → 跳转余额宝转入页
- 21:05:45.507 isNonAdTaskPage: YES, matched non-ad task in [转入余额宝, 1028, 元, ...立即转入...]
- 21:05:46.562 processTask: non-ad task page, skipping task #$1
- 21:05:51.569 state: PROCESSING_TASK -> OPENING_TASK_LIST
- 21:05:54.333 openTaskList: resetting currentTaskIndex to 0 (was 1, taskButtons.size=7)
- 此循环在 21:05:39 / 21:05:56 / 21:06:13 / 21:06:30 重复 4 次
- 21:06:34.775 state: PROCESSING_TASK -> STOPPING（防大循环兜底生效）

**修复**: [FarmAccessibilityService.kt#L2770-2776](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L2770)
- paidKeywords 新增 "余额宝"、"攒一笔"、"转入" 三个关键词
- task #1 文案 "攒一笔到余额宝..." 命中"攒一笔"和"余额宝" → isPaidTask 返回 true
- processTask 跳过 task #1 → currentTaskIndex++ → 处理 task #2
- 不再点击"去完成" → 不跳转余额宝转入页 → 不触发 isNonAdTaskPage 误判 → 不重置索引 → 不死循环

**注意**:
- "转入"是余额宝转入页的关键动词，作为付费任务关键词安全（浏览任务文案不会含"转入"）
- "攒一笔"是支付宝余额宝任务的专属营销文案
- 与 build649（淘宝秒杀下单）/ build633（限时折扣下单）的修复思路一致：识别金融/交易类任务直接跳过

**预期效果**:
- 支付宝平台 task #1 "攒一笔到余额宝" 直接被识别为付费任务跳过
- 不再跳转余额宝转入页 → 不再 isNonAdTaskPage 误判 → 不再 currentTaskIndex 重置
- 继续处理后续任务（逛好物/看精选商品/去庄园喂鸡等）
- 7 个任务中 task #1（余额宝）和 task #6（金豆夺宝签到）均识别为付费任务跳过，其余 5 个任务正常处理

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit b012ed9 - fix: build657 支付宝搜索结果过滤搜索框区域联想词，选真正搜索结果

**用户需求**: "分析日志"（从 GitHub 拉取 build656 日志 debug_test_20260726_205318.log）

**日志分析** (build656, 支付宝平台):
- ❌ **新问题**: 支付宝导航失败，NAVIGATING → STOPPING → IDLE
- 搜索"芭芭农场"后点击搜索结果，3 次重试都失败

**根因**: [FarmAccessibilityService.kt#stepNavigateAlipayFarm#L6199-6209](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L6199)
- `collectNodesByText` 收集 10 个"芭芭农场"文本节点（candidates=10）
- `allResults.first()` 选了 bounds=[0,278][1200,431]，top=278 在搜索框区域（< screenHeight*0.20=508.6）
- 这个节点是搜索框区域的联想词，不是真正的搜索结果
- 点击联想词不跳转 → dispatchGesture 点坐标也无效 → pressBack 重试 → 反复 3 次失败 → STOPPING
- 策略1（找首页入口）已用搜索框区域过滤（line 6121 `top < screenHeight*0.20`），但策略2（选搜索结果）未过滤

**日志证据** ([debug_test_20260726_205318.log#L16-22](file:///workspace/logs/debug_test_20260726_205318.log)):
- 20:50:50.956 clicking search result '芭芭农场' at [0,278][1200,431] (candidates=10)
- 20:50:54.044 search result click did not navigate
- 20:50:58.070 dispatchGesture also failed, pressBack and retry
- 20:51:14.523 dispatchGesture also failed, pressBack and retry
- 20:51:44.067 state: NAVIGATING -> STOPPING

**修复**: [FarmAccessibilityService.kt#L6203-6217](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L6203)
- 新增搜索框区域过滤：`val searchAreaThreshold = resources.displayMetrics.heightPixels * 0.20f`
- `filteredResults = allResults.filter { node.getBoundsInScreen(r); r.top >= searchAreaThreshold }`
- `candidateList = filteredResults.ifEmpty { allResults }`（过滤后为空则回退原列表）
- `mpNode` 从 `candidateList` 中选，优先带"小程序"/"生活号"/"官方"标识的节点
- 过滤后选的搜索结果 top >= 508.6（屏幕顶部 20% 以下），是真正的搜索结果

**预期效果**:
- 过滤掉搜索框联想词（top=278 < 508.6）
- 选真正的搜索结果（top >= 508.6），点击后跳转到芭芭农场小程序
- 不再反复点击无效的联想词 → 导航成功

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 92a1ed0 - fix: build656 跳过"去头条极速版逛逛"等头条系跳转外部 App 任务

**用户需求**: "修复所有问题"（基于 build655 日志 debug_test_20260726_204010.log）

**日志分析** (UC 平台测试):
- ✅ build655 防大循环生效：导航失败后自动 STOPPING → IDLE
- ✅ 任务跳过逻辑生效（task #1 "看视频得巨额肥料" skip）
- ❌ **新问题**: task #2 "去头条极速版逛逛" 被误识别为 browseTask，跳转外部 App

**根因**: [AutomationController.kt#skipTaskTexts](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L2007)
- task #2 文案 "去头条极速版逛逛 去头条极速版逛逛 本次可得 肥料 +684 去完成"
- skipTaskTexts 只含"趣头条"，不含"头条极速版"
- task #2 不命中 skipTaskTexts → 进入 isBrowseTask 判断
- isBrowseTask 因文案含"逛逛"返回 true → 走 BROWSING_TASK 流程
- 点击"去完成"跳转到头条极速版 App (com.ss.android.article.lite)
- 浏览后 forceKillApp 失败 → 反复重试 6 次 → 最终 STOPPING

**日志证据** ([debug_test_20260726_204010.log#L433-453](file:///workspace/logs/debug_test_20260726_204010.log)):
- 20:35:53.519 collectTaskContextText: result='去头条极速版逛逛 ...'
- 20:35:53.522 isBrowseTask: buttonText='去完成', context='去头条极速版逛逛...', isBrowse=true
- 20:35:53.523 processTask: browse task #2, entering BROWSING_TASK
- 20:36:01.165 activeRootPkg='com.ss.android.article.lite' (跳转到头条极速版)
- 20:36:01.400 browseTask: cross-app jump detected, skipping task
- 20:38-20:39 反复重试导航失败 → STOPPING

**修复**: [AutomationController.kt#L2008-2016](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L2008)
- skipTaskTexts 新增 "头条" 关键词
- "头条"覆盖"头条极速版"/"今日头条"等所有头条系任务
- skipTaskTexts 在 isBrowseTask 之前判断（line 2028 vs 2201），命中直接跳过
- 避免被 isBrowseTask 误识别为浏览任务

**注意**:
- pure claim 按钮（领取/收下等）仍优先于 skipTaskTexts，已完成奖励仍可领取
- 该修复与 build655 的 fertilize 防护独立，UC 平台未进入 fertilize 阶段，淘宝平台 build655 应已生效

**预期效果**:
- "去头条极速版逛逛..." → skipTaskTexts 命中"头条"，直接跳过
- 不再走 BROWSING_TASK → 不再跳转头条极速版 App → 不再卡在导航重试

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 352d37d - fix: build655 修复施肥 hint 正则不匹配"再施肥X次"及"可施肥0次"未终止导致大循环

**用户需求**: "分析日志"（从 GitHub 拉取 build654 日志 debug_test_20260726_202554.log）

**日志分析**:
- ✅ build652 弹窗关闭修复持续生效
- ✅ build653 "可施肥0次"过滤生效
- ✅ build654 hint 过滤生效（不再点击"再施肥 2 次可领"hint 节点）
- ✅ 任务跳过逻辑全部生效
- ❌ **新问题**: FERTILIZING→WAITING→NAVIGATING→...→FERTILIZING 大循环 5 轮直到用户手动停止

**根因1**: [FarmAccessibilityService.kt#findRemainingFertilizerHintNode#L4841](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L4841)
- 旧正则 `(?:还差\s*\d+\s*次.*肥|再施\s*\d+\s*次.*(?:礼包|肥))` 要求"再施"后跟数字
- 但实际页面文案是"再施肥 2 次可领"（"再施"+"肥"不是数字）
- 正则不匹配 → findRemainingFertilizerHintNode 返回 null
- fertilize 走到 fallback "无 hint 无 direct 无 button" 分支
- 切 WAITING → startNextRound → 又一轮（无任务可做）→ 又到此分支 → 大循环

**根因2**: [AutomationController.kt#L5428-5431](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L5428) fallback 分支
- 切 WAITING 后调 startNextRound，没有终止条件
- 即使"可施肥0次"（肥料用尽）也会无限循环

**日志证据** ([debug_test_20260726_202554.log#L295-298](file:///workspace/logs/debug_test_20260726_202554.log)):
- 19:52:24.795 fertilize: findFertilizeButton=false, clickCount=1
- 19:52:24.898 findRemainingFertilizerHintNode: no hint node found
- 19:52:24.899 state: FERTILIZING -> WAITING
- 19:52:29.903 state: WAITING -> NAVIGATING （startNextRound 触发新一轮）
- 此循环在 19:52-19:54 重复 3 次，20:22-20:25 又重复 3 次直到用户手动停止

**修复**:
1. **修复 findRemainingFertilizerHintNode 正则** [FarmAccessibilityService.kt#L4852](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L4852)
   - 旧: `(?:还差\s*\d+\s*次.*肥|再施\s*\d+\s*次.*(?:礼包|肥))`
   - 新: `(?:还差\s*\d+\s*次.*肥|再施(?:肥\s*)?\d+\s*次.*(?:礼包|肥|可领))`
   - 支持"再施肥X次"格式（"再施"+"肥"+空格+数字+"次"+...）
   - 支持"可领"作为终止词（"再施肥 2 次可领"）

2. **修复 parseFertilizeRemainingCount 正则** [FarmAccessibilityService.kt#L4802](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L4802)
   - 旧: `(?:还差|再施)\s*(\d+)\s*次`
   - 新: `(?:还差|再施(?:肥\s*)?)\s*(\d+)\s*次`
   - 支持"再施肥 2 次"格式

3. **新增 hasZeroFertilizerButton 检测** [FarmAccessibilityService.kt#L5425](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L5425)
   - 检测页面是否有"可施肥0次"按钮（肥料用尽）

4. **修改 fertilize fallback** [AutomationController.kt#L5439-5449](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L5439)
   - 在切 WAITING 前检测 hasZeroFertilizerButton
   - 若"可施肥0次"则切 STOPPING → IDLE，避免大循环

**预期效果**:
- 正常场景（有肥料"可施肥Y次"Y>0）: hint 兜底点击真按钮 → remainCount 递减 → 施肥完成
- 肥料用尽（"可施肥0次"）: hint 兜底 3 轮无进展 → 切 WAITING → 再进 fertilize → 检测到"可施肥0次" → 切 STOPPING → IDLE
- 不再大循环

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 65cb2e7 - fix: build654 修复 hint 提示"再施肥 X 次可领"被误识别为施肥按钮导致无限循环

**用户需求**: "分析日志"（从 GitHub 拉取 build653 日志 debug_test_20260726_194313.log）

**日志分析**:
- ✅ build652 弹窗关闭修复持续生效
- ✅ build653 "可施肥0次"过滤生效（不再点击"施肥，肥料53，可施肥0次"）
- ✅ 任务跳过逻辑全部生效
- ❌ **新问题**: findFertilizeButton 转而匹配 hint 提示节点，无限点击 16 次直到用户手动停止

**根因**: [FarmAccessibilityService.kt#findFertilizeButton](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L4702)
- build653 过滤"可施肥0次"后，真施肥按钮被排除
- findFertilizeButton 转而匹配 hint 提示节点"再施肥 2 次可领 icon icon"（含"施肥"二字）
- hint 节点被当作施肥按钮直接点击，触发 hint 弹窗展开/收起，交替点击两个 hint 节点
- 无限循环 16 次直到用户手动停止

**日志证据** ([debug_test_20260726_194313.log#L257-282](file:///workspace/logs/debug_test_20260726_194313.log)):
- clickCount=1: 点击"再施肥 2 次可领 icon icon" bounds=[393,1418][808,1513]
- clickCount=2: 点击"close icon 再施肥 2 次 领 200 肥料" bounds=[356,1300][844,1513]
- clickCount=3: 又点击"再施肥 2 次可领 icon icon"
- ... 交替点击 16 次 ...
- 19:43:10.219 用户手动停止 FERTILIZING -> STOPPING

**修复**: [FarmAccessibilityService.kt#L4719-4738](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L4719)
- 新增 `hintKeywords = listOf("再施肥", "可领")`
- walk 函数中: `isHint = hintKeywords.any { s.contains(it) }`
- 与 isProgress/isZeroFertilizer 同等处理: `if (!isProgress && !isZeroFertilizer && !isHint)` 才加入候选
- 真施肥按钮文案为"施肥，肥料X，可施肥Y次"，不含"再施肥"也不含"可领"

**预期效果**:
- "再施肥 2 次可领 icon icon" → 被过滤（含"再施肥"+"可领"）
- "close icon 再施肥 2 次 领 200 肥料" → 被过滤（含"再施肥"）
- "施肥，肥料53，可施肥0次" → 被过滤（build653 已做，含"可施肥0次"）
- findFertilizeButton 返回 null → fertilize 进入 fallback
- findRemainingFertilizerHintNode 识别"再施肥 2 次"hint → 点击 hint 下方坐标（真施肥按钮位置）
- 真施肥按钮"可施肥0次"点击无反应 → hint remainCount 不变 → noProgressStreak 递增
- 3 轮后切 WAITING，不再无限循环

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 88cfd77 - fix: build653 修复施肥按钮"可施肥0次"时仍被识别为可点击导致无限循环

**用户需求**: "分析日志"（从 GitHub 拉取 build652 日志 debug_test_20260726_193125.log）

**日志分析**:
- ✅ build652 弹窗关闭修复生效：`click close button '关闭' to close it`
- ✅ 任务跳过逻辑全部生效（paid task #1/#2/#5, list task #3/#4/#6/#7/#8）
- ❌ **新问题**: 施肥阶段无限点击 19 次直到用户手动停止

**根因**: [FarmAccessibilityService.kt#findFertilizeButton#L4702](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L4702)
- 施肥按钮文案为 `'施肥，肥料53，可施肥0次'` 表示已无肥料可施（可施肥次数=0）
- findFertilizeButton 只过滤了"进度"关键词，没过滤"可施肥0次"
- 导致该按钮被识别为可点击施肥按钮，fertilize 阶段无限点击

**日志证据** ([debug_test_20260726_193125.log#L279-329](file:///workspace/logs/debug_test_20260726_193125.log)):
- 19:30:30.769 ~ 19:31:18.771 共 19 次 `performClickSafe: text='施肥，肥料53，可施肥0次'`
- 每次都 `ACTION_CLICK success`，但肥料数和次数都没变化
- 19:31:22.100 用户手动停止 `state: FERTILIZING -> STOPPING`

**修复**: [FarmAccessibilityService.kt#L4713-4729](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L4713)
- 新增 `zeroFertilizerPatterns` 列表: `["可施肥0次", "可施肥 0 次", "可施肥0", "可施肥 0"]`
- walk 函数中: `isZeroFertilizer = zeroFertilizerPatterns.any { s.contains(it) }`
- 与 `isProgress` 同等处理: `if (!isProgress && !isZeroFertilizer)` 才加入候选

**预期效果**:
- "施肥，肥料53，可施肥0次" → findFertilizeButton 返回 null
- fertilize 进入 fallback: 无 hint 节点（日志无"还差X次领肥料"）→ 无 direct 按钮 → 切 WAITING
- startNextRound 进入下一轮，不再无限循环

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit ec514e3 - fix: build652 修复施肥阶段任务列表弹窗关闭按钮识别失败导致无限循环

**用户需求**: "分析日志"（基于 build651 日志 debug_test_20260726_175653.log 分析）

**日志分析**:
- 任务跳过逻辑全部生效 ✅
  - task #1/#2/#5: `skip paid task` (秒杀下单) - build649 生效
  - task #6: `skip list task` (邀请好友助力) - build650 生效
  - task #7/#8: `skip list task` (趣头条) - build651 生效
- **新问题**: 施肥阶段无限循环 6 次直到用户手动停止 ❌

**根因**: [AutomationController.kt#L5279-5288](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L5279)
- 任务列表弹窗展开后，fertilize 阶段第一步检测 `isTaskListOpen=true`
- 找专用关闭按钮 `findNodeByText("关闭做任务集肥料弹窗")` 失败（此按钮在某些版本不存在）
- 退而求其次用 `pressBack()`，但 pressBack 既关弹窗又退出主页
- 17:51:15.839 `fertilize: not on farm page, re-navigate` → 离开农场
- 重新导航 → 又到 processTask（全 skip）→ 又到 fertilize → 又 pressBack → 无限循环

**日志证据** ([debug_test_20260726_175653.log#L217-L273](file:///workspace/logs/debug_test_20260726_175653.log)):
- 任务列表弹窗展开：8 个"去完成"按钮 + "已完成"按钮
- **没有**"关闭做任务集肥料弹窗"按钮
- **有**"关闭"按钮 bounds=[1084,546][1185,645]（任务列表弹窗右上角关闭按钮，centerX=1134, centerY=595）
- 屏幕宽 1200, 高 2543

**修复**:
1. 新增 [FarmAccessibilityService.kt#findTaskListCloseButton](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L5410)
   - 精确匹配 text == "关闭"（不是 contains，避免误匹配"关闭做任务集肥料弹窗"等长文本）
   - clickable=true
   - 位置约束: centerX > screenWidth*0.8 且 centerY < screenHeight*0.4（屏幕右上角，避免误点主页广告"关闭"按钮）
2. 修改 [AutomationController.kt#L5279-5285](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L5279) fertilize 关闭弹窗逻辑：
   - 优先找"关闭做任务集肥料弹窗"（原逻辑保留）
   - 找不到则调 `findTaskListCloseButton`（新增）
   - 还找不到才 pressBack 兜底

**预期效果**:
- 任务列表弹窗展开时，能找到"关闭"按钮点击关闭弹窗（不退主页）
- 不再 pressBack 退出主页 → 不再无限循环
- 施肥阶段能正常进入 findFertilizeButton 流程

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 30c9f6a - fix: build651 去逛逛趣头条任务直接跳过

**用户需求**: "去逛逛趣头条任务，不需要做"

**问题**: "去逛逛趣头条赚金币(0/1) 逛逛得 +300 去完成" 任务未被跳过
- 任务跳转到趣头条 App，bot 无法在趣头条 App 内自动完成"逛逛赚金币"动作
- 原本会点击"去完成"跳转趣头条 App，最终任务失败浪费时间

**修复**: skipTaskTexts 新增 1 个关键词
- [AutomationController.kt#L2004-2007](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L2004)
- 新增: "趣头条"
- 匹配"去逛逛趣头条赚金币"等所有趣头条类任务
- processTask 中 `skip list task` 直接跳过，不点击"去完成"

**注意**: 该任务按钮是"去完成"（非 pureClaim 按钮），所以会被 skipTaskTexts 跳过；pure claim 按钮（"领取"/"收下"等）仍优先于 skipTaskTexts，已完成的奖励仍可领取。

**预期效果**:
- "去逛逛趣头条赚金币(0/1) 逛逛得 +300 去完成" → skipTaskTexts 命中"趣头条"，直接跳过
- 不再点击"去完成"跳转趣头条 App 浪费时间

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 9e46eb5 - fix: build650 邀请好友助力任务直接跳过

**用户需求**: "邀请好友助力任务，不做"

**问题**: "邀请好友助力(0/3) 邀请成功得 +1000 去完成" 类社交任务未被跳过
- 任务需分享给好友/好友助力才能完成，bot 无社交账号、无法分享，无法自动完成
- 原本会点击"去完成"进入邀请页面，最终也是任务失败浪费时间

**修复**: skipTaskTexts 新增 2 个关键词
- [AutomationController.kt#L1999-2003](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L1999)
- 新增: "邀请好友"、"助力"
- "邀请好友"覆盖"邀请好友助力"等邀请类任务
- "助力"覆盖"邀请成功得"/"助力得"等变体
- processTask 中 `skip list task` 直接跳过，不点击"去完成"

**注意**: pure claim 按钮（"领取"/"收下"等）优先于 skipTaskTexts，所以如果邀请好友任务有已完成的奖励领取按钮，仍然可以领取（不会被误跳过）。

**预期效果**:
- "邀请好友助力(0/3) 邀请成功得 +1000 去完成" → skipTaskTexts 命中"邀请好友"/"助力"，直接跳过
- 不再点击"去完成"进入邀请页面浪费时间

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 9509a82 - fix: build649 修复淘宝秒杀下单任务未识别为付费任务导致无限循环

**用户需求**: "分析github日志，解决秒杀下单任务不做"

**输入日志**: `logs/debug_test_20260726_165828.log`（build648，511 行，16:56:40~16:58:28 约 2 分钟）

**问题现象**: task #5 "淘宝秒杀下单肥料加码(0/1) 下单最高得4-5万肥料 +50000 去完成" 没有被正确跳过，导致无限循环：
- 16:57:53 点击"去完成" → 跳转淘宝秒杀页（TMSActivity）
- 16:58:02 秒杀页 H5 加载/关闭后回到芭芭农场主页
- isOnFarmPage 返回 false（farmPkgWindowVisible=false，activity fallback only）
- isNonAdTaskPage 误判返回 YES（芭芭农场主页含"邀请好友"/"合种"等入口关键词）
- `processTask: non-ad task page, skipping task #$5` → 任务被"误判跳过"
- 状态切回 OPENING_TASK_LIST → currentTaskIndex 重置为 0 → 又从 task #1 开始 → 无限循环（用户手动停止）

**根因**: `isPaidTask` 没有识别"淘宝秒杀下单肥料加码 下单最高得4-5万肥料"为付费任务
- 任务文案: `淘宝秒杀下单肥料加码(0/1) 下单最高得4-5万肥料 +50000 去完成`
- 现有 paidKeywords: "下单领"/"下单赢"/"下单大额"/"下单高额"/"下单得大额" 均不匹配 "下单最高得"
- 秒杀下单任务需用户实际下单购买才能得肥料，bot 无法自动完成，应直接跳过

**修复**: isPaidTask 新增 3 个付费关键词
- [FarmAccessibilityService.kt#L2775-2778](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L2775)
- 新增: "秒杀下单"、"下单肥料加码"、"下单最高得"
- 三重匹配确保"淘宝秒杀下单肥料加码"任务被识别为付费任务（paid=true）
- processTask 中 `skip paid task` 直接跳过，不点击"去完成"，避免跳转秒杀页→回到主页→isNonAdTaskPage 误判→currentTaskIndex 重置无限循环

**预期效果**:
- "淘宝秒杀下单肥料加码(0/1) 下单最高得4-5万肥料 +50000 去完成" → isPaidTask=YES，直接跳过
- 不再点击"去完成"跳转秒杀页，不再触发 isNonAdTaskPage 误判，不再无限循环
- 后续任务（去快手app领福利/邀请好友助力/趣头条赚金币等）正常执行

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit (待提交) - fix: build648 修复跨 App 跳转卡死和浏览5s任务误跳过
**用户需求**: "分析日志"

**输入日志**: `logs/debug_test_20260726_164258.log`（build647，511 行，16:39:39~16:42:58 约 3 分钟）

**build647 验证结果**: ✅ 闯关类游戏跳过生效
- task #3 "玩1局斗地主 打完一局领 +400" → 被 skipTaskTexts 跳过（含"玩1局"/"斗地主"）
- task #4 "玩游戏领免费包邮鲜花 完成1次得肥料" → 被 skipTaskTexts 跳过（含"玩游戏"/"完成1次"）
- 注：build647 的 isGameLevelTask 没机会执行，因为 skipTaskTexts 已包含"玩1局"/"斗地主"等关键词

**新发现问题 1**: task #5 "去闲鱼币领现金红包 逛逛得" 跳转到闲鱼 App，bot 卡死
- 16:41:52.929 browseTask: clicking 'go browse' button
- 16:41:57.943 page type=unknown(no_root), onFarm=false, texts=[]
- 16:41:57.986 not a browse task, exiting without swiping
- 16:42:03.445 exitBrowsePage: not on farm page after exit, re-navigating
- 16:42:06.063 [navigate-start] pkg=com.taobao.idlefish ← 跳转到闲鱼
- 16:42:06~16:42:50 在闲鱼 App 循环 pressBack 无法返回淘宝（用户手动停止）

**根因 1**: 点击"去完成"后跳转到闲鱼 App，bot 走到 else 分支（无浏览奖励指标），
调用 exitBrowsePage → pressBack，但闲鱼 App 的 pressBack 只是在闲鱼内部返回，
无法回到淘宝，导致 bot 卡在闲鱼 App 循环 pressBack。

**新发现问题 2**: task #4 "去省钱卡领红包 浏览5s得" 落地页是百亿补贴活动页
- 16:41:25.036 browseTask: browse product list page detected, clicking product
- 16:41:31.716 browseTask: not on product detail page (activity=TMSActivity), skipping task
- ← 6 秒后跳过任务，但"浏览5s得"只需 5 秒停留

**根因 2**: 落地页是百亿补贴活动页（含"滑动浏览得肥料"+商品价格列表），
被 isBrowseProductListPage 误判为商品列表页，点击商品后进入活动详情页
（activity=TMSActivity，不是商品详情页），bot 立即跳过任务，
但实际"浏览5s得"任务只需在活动页停留 5 秒。

**修复 1**: browseTask 点击"去完成"后检测跨 App 跳转
- [AutomationController.kt#L2275-2316](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L2275)
- 点击"去完成"后获取当前窗口包名（getCurrentWindowPackage）
- 若包名不是农场平台包名（跨 App 跳转），pressBack 两次 + launchPlatformApp 重新启动农场
- 跳过此任务（currentTaskIndex++），避免卡死

**修复 2**: browseTask 点击商品后未进入商品详情页时不立即跳过
- [AutomationController.kt#L2348-2379](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L2348)
- 检测落地页是否是直播页（"直播中"/"宝贝讲解"/"直播间"）或登录对话框
- 若是直播页/登录对话框：跳过任务（原逻辑）
- 若是活动详情页/其他页面：等待 5 秒（browseTaskTargetSwipes=3，5s/2s≈3 次轮询）
- 短时浏览任务（"浏览5s得"）只需停留即可获得肥料

**预期效果**:
- "去闲鱼币领现金红包 逛逛得" → 检测到跨 App 跳转，pressBack + 重新启动淘宝，不卡死
- "去省钱卡领红包 浏览5s得" → 活动页等待 5 秒后退出领肥料，不跳过任务

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit cb0e24b - feat: build647 闯关类游戏任务跳过（bot 无法自动完成）
**用户需求**: "游戏闯关类的游戏不完成"

**问题**: 闯关类游戏（如"玩消消乐得肥料 进入任意消3下得 +400"）需要用户实际操作游戏（消除/出牌/对战）才能完成，bot 只能停留无法操作，任务不会完成，肥料得不到，浪费时间。
- 日志证据（debug_test_20260726_162937.log）:
  - 16:26:51 "玩消消乐得肥料(0/1) 进入任意消3下得 +400" 进入 GAME_PLAYING
  - 16:27:01 isGameCompletePage 误判（build646 已修复悬浮窗误判）
  - 即使修复误判，bot 等待 30 秒后退出，"消3下"要求未完成，肥料得不到

**修复**:
1. 新增 [FarmAccessibilityService.isGameLevelTask()](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L2824)
   - 识别闯关类游戏关键词：消消乐/斗地主/闯关/通关/对战/完成1局/完成一局/局对战/打一局/浪漫餐厅/农场分色瓶/砸蛋/砸金蛋/得分/消3下/消3次/消除
   - 这类游戏需要实际操作，bot 无法自动完成

2. [AutomationController.processTask()](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L2125)
   - isGameTask 返回 true 后，检查是否是试玩类（"试玩"/"新游"）
   - 非试玩的闯关类游戏任务直接跳过（类似 isPaidTask 跳过逻辑）
   - 试玩类游戏保留（停留 10 分钟完成）

**预期效果**:
- "玩消消乐得肥料 进入任意消3下得 +400" → 跳过（闯关类，无法自动完成）
- "试玩热门新游 访问必得500肥料" → 保留（试玩类，停留 10 分钟）
- 不再浪费时间在无法完成的闯关游戏上

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 7a66856 - fix: build646 修复 isGameCompletePage 误判系统悬浮窗为游戏完成页
**用户需求**: "分析日志"

**输入日志**: `logs/debug_test_20260726_162937.log`（build645，187 行，16:25:52~16:29:37 约 4 分钟）

**build645 验证结果**: ✅ collectTaskContextText 兜底策略修复生效
- task #1~#3 全部正确获取任务上下文（containerHeight=203）
- task #1 "买限时折扣好物得奖励" → isPaidTask=YES，正确跳过
- task #2 "买限时折扣好物得奖励(0/3)" → isPaidTask=YES，正确跳过
- task #3 "玩消消乐得肥料" → isGameTask=YES，进入 GAME_PLAYING
- ❌ task #3 游戏任务被误判为完成（系统悬浮窗页面被误判）

**新发现问题**: isGameCompletePage 误判系统悬浮窗权限提示为游戏完成页
- 16:26:51 processTask: game task #3, text='去完成', trialPlay=false, stayTargetMs=30000
- 16:26:51 state: PROCESSING_TASK -> GAME_PLAYING
- 16:27:01 isGameCompletePage: YES, sample=[Android 系统, 1 分钟前, 收起,
  芭芭农场机器人正在其他应用的上层显示内容。, 如果您不想让此功能...]
  ← sample 全是系统悬浮窗权限提示文案，不是游戏完成页
- 16:27:01~16:27:14 gamePlay: game complete detected, waiting (no click)
  ← 等待 17 秒后误退出游戏（stayTargetMs=30000 未到）
- 16:27:18 gamePlay: back to farm, assuming complete
  ← 肥料未获得，游戏任务失败

**根因分析**:
- 游戏任务点击"去完成"后，系统弹出无障碍服务悬浮窗权限提示
- 该系统 UI 页面（com.android.systemui）的 root 被读取
- collectAllText 收集到的文本含"芭芭农场"（hasGameOrFarmContext=true）
- 且某个文本匹配了 isComplete 关键词（如"已完成"/"任务完成"，可能来自系统 UI 的其他文本）
- isGameCompletePage 误判为 YES，游戏任务被误认为已完成
- 等待 17 秒后（远未到 30 秒 stayTargetMs）误退出游戏，肥料未获得

**修复**: isGameCompletePage 排除系统 UI 窗口
- [FarmAccessibilityService.kt#L1178-1202](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L1178)
- 检测 root 包名是否为 `com.android.systemui`
- 或前 8 个文本是否含"上层显示内容"/"Android 系统"
- 命中则返回 false（不是游戏完成页，是系统悬浮窗权限提示）
- 增加 matchedKeywords 诊断日志，便于排查误匹配关键词

**预期效果**:
- 游戏任务点击"去完成"后，系统悬浮窗不再被误判为游戏完成页
- isGameCompletePage 返回 false，游戏继续等待 30 秒（stayTargetMs）
- 30 秒后正常退出游戏，获得肥料

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit d236154 - fix: build645 修复 collectTaskContextText 兜底策略 root 来源错误
**用户需求**: "分析日志"

**输入日志**: `logs/debug_test_20260726_161521.log`（build644，约 1500 行，16:08:25~16:15）

**build644 验证结果**: 部分修复生效
1. ✅ task #3 "去玩支付宝蚂蚁庄园" 跨平台识别成功（containerHeight=203，detectCrossPlatformTarget 检测到"支付宝"）
2. ✅ 切换到 ALIPAY 并完成任务（16:09:45~16:12:28）
3. ❌ task #4 "去完成" 跨平台任务兜底策略失效（containerHeight=-1，result='去完成 '）
4. ❌ 误入"专供会场"页面循环无法恢复（16:12:37~16:15）

**新发现问题**: task #4 的 collectTaskContextText 兜底策略未触发/无效
- 16:12:31 processTask: current task #4/8, text='去完成', bounds=[910,1797][1139,1896]
- 16:12:31 collectTaskContextText: result='去完成 ', containerHeight=-1
- ← 兜底策略应该触发（container==null），但 result 仍是空按钮文案
- 16:12:31 isBrowseTask: buttonText='去完成', context='去完成 ', isBrowse=false
- 16:12:37 isRechargePage: YES（误入"专供会场"页面循环）

**根因分析**:
- build644 兜底策略用 `getRootInFarmApp()` 获取 root
- 但 `getRootInFarmApp()` 会过滤"当前平台主包名或内部包前缀"的窗口
- task #4 时可能返回了错误的窗口（如"专供会场"页面的 root，而非任务列表所在窗口）
- 或 `getRootInFarmApp()` 返回的 root 不包含任务标题节点
- 导致 `collectTextByYOverlap` 遍历的 root 不对，找不到任务标题

**修复**: collectTaskContextText 兜底策略改用 `rootInActiveWindowSafe`
- [FarmAccessibilityService.kt#L3254-3271](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L3254)
- 原: `val root = getRootInFarmApp()`（可能返回错误窗口）
- 新: `val root = rootInActiveWindowSafe()`（获取当前活跃窗口的 root，更可靠）
- 增加诊断日志：`fallback triggered, btnTop=X btnBottom=Y fallbackResult='...'`

**优化**: collectTextByYOverlap 扩大搜索范围
- [FarmAccessibilityService.kt#L3288-3310](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L3288)
- y 范围从按钮上方 150px 扩大到 250px（任务标题常在按钮上方较远位置）
- maxDepth 从 8 提升到 10（任务列表 DOM 较深）

**预期效果**:
- task #4 的兜底策略能正确获取任务标题（如"去淘金币逛买更省钱(0/1) 浏览5s得 +300"）
- detectCrossPlatformTarget/detectBrowseTask 等识别逻辑能正确工作
- 不再误入"专供会场"页面循环

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit e461f14 - fix: build644 修复跨平台任务返回后 collectTaskContextText 找不到容器导致跨平台识别失败
**用户需求**: "分析日志"

**输入日志**: `logs/debug_test_20260726_160016.log`（build643，961 行，15:55:48~16:00:10 约 4 分钟）

**build643 验证结果**: ✅ 全部修复生效
1. ✅ build642 COLLECTING_DIRECT 修复生效: 15:56:07 找到"兔兔挖肥料，50肥料，可领取"并点击
2. ✅ build642 isTaskCompletePage 修复生效: 多次返回 NO "has direct collect button"（农场主页不再误判）
3. ✅ build643 浏览任务修复生效: "发现精选好物(4/5)" 任务完成 8 次滑动（15:56:57~15:57:24，约 22 秒）
4. ✅ 跨平台任务"逛逛支付宝芭芭农场"成功完成（15:57:49~15:58:34）

**新发现问题**: task #4 "去玩支付宝蚂蚁庄园" 跨平台任务识别失败，误入"专供会场"页面循环
- 15:58:36 processTask: current task #4/8, text='去逛逛'
- 15:58:36 collectTaskContextText: result='去逛逛 '（containerHeight=-1，只获取到按钮文案）
- 15:58:36 isBrowseTask: button text is '去逛逛', treat as click task (not browse)
- 15:58:43 isRechargePage: YES（误入"专供会场"页面，sample=[专供会场, 农场好货特供, hot, 万人疯抢中]）
- 15:58:43~16:00:10 在"专供会场"页面循环（COLLECTING_DIRECT → OPENING_TASK_LIST → NAVIGATING），无法恢复
- 用户手动停止（16:00:10 state: STOPPING -> IDLE）

**根因分析**:
1. **collectTaskContextText 找不到容器（containerHeight=-1）**:
   - 跨平台任务返回后（15:58:34 从 ALIPAY 回到 TAOBAO），任务列表 DOM 结构变化
   - task #4 按钮 bounds=[910,1797][1139,1896]，但父节点不再满足容器条件
     （高度 100~600px, 宽度 > 500px, childCount >= 2）
   - 策略1（向上找首个含多个子节点的祖先）和策略2（向上找更高祖先）都失败
   - 最终用 button 自己作为容器，只收集到"去逛逛"

2. **detectCrossPlatformTarget 检测失败**:
   - `buttonText + " " + taskContextText` = '去逛逛 去逛逛 '，不含"支付宝"关键词
   - detectCrossPlatformTarget 返回 null，没识别为跨平台任务
   - 被当作普通点击任务处理，点击"去逛逛"后误入淘宝"专供会场"页面

3. **"专供会场"页面循环无法恢复**:
   - "专供会场"页面有"下单领肥料"等文案，isRechargePage=YES
   - findClaimRewardButton 跳过（scene not allowed）
   - COLLECTING_DIRECT 找不到 direct 按钮（页面是会场，不是任务列表）
   - OPENING_TASK_LIST 找不到 goComplete 按钮（WebView not ready）
   - 循环持续到用户手动停止

**修复**: collectTaskContextText 增加兜底策略3：当容器找不到时，按按钮 y 坐标范围收集文本
- [FarmAccessibilityService.kt#L3238-3301](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L3238)
- 新增 `collectTextByYOverlap` 方法：遍历 root，收集与按钮 y 坐标重叠（上下放宽 150px）的文本节点
- 任务行高度约 200px，包含任务标题（如"去玩支付宝蚂蚁庄园(0/1) 逛逛得 +300"）和按钮（"去逛逛"）
- 通过 y 坐标重叠可以找到同行的任务标题，即使 DOM 结构变化也能工作
- 只有当兜底结果比原结果更丰富时才使用（避免空结果覆盖）

**预期效果**:
- task #4 "去玩支付宝蚂蚁庄园" 的 collectTaskContextText 会返回完整任务上下文
- detectCrossPlatformTarget 检测到"支付宝"关键词，识别为跨平台任务
- 切换到 ALIPAY 完成任务，不再误入"专供会场"页面
- 跨平台任务返回后不再循环卡死

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit ebcfae1 - fix: build643 修复 "看严选推荐商品" 视频任务立即退出（0秒等待）
**用户需求**: "分析日志 淘宝芭芭农场，看严选推荐商品，去完成，打开的看视频任务，需要看够15秒后才能得肥料"

**输入日志**: `logs/debug_test_20260726_153127.log`（build641，1100+ 行，15:25:22~15:30:05 约 5 分钟）

**问题**: "看严选推荐商品(0/5) 浏览得奖励 +600" 任务点击"去完成"后，立即退出（0 秒等待），肥料未获得
- 15:26:57 isBrowseTask=true（context 含"浏览"）→ 进入 BROWSING_TASK
- 15:27:01 点击"去完成" → 落地页是商品详情页（activity=newdetailactivity，含视频播放器）
- 15:27:07 page type=browse_duration, texts=[..., 滑动浏览得肥料, 视频，按钮。双击可暂停或播放视频。, ...]
- 15:27:07 "no swipe hint and no browse reward indicator → not a browse task, exiting without swiping"
- ← 0 秒等待，肥料未获得，任务进度仍是 0/5

**根因分析**:
1. **isBrowseProductListPage 返回 false**:
   - 落地页是商品详情页（含视频），不是商品列表页
   - isBrowseProductListPage 检查 hasProductPrice（"¥"或"元"），但视频详情页无价格符号 → false
   - 跳过了 TAOBAO 点击商品+停留 15 秒的分支

2. **findSwipeForFertilizerHint 返回 0**:
   - 页面有"滑动浏览得肥料"文案，但无具体秒数（如"滑动浏览15秒"）
   - findSwipeForFertilizerHint 解析不到秒数 → 返回 0

3. **4 个浏览奖励指标全 false**:
   - hasCountdown=false, hasProgress=false, hasDuration=false, hasProgressInfo=false
   - "not a browse task" → 立即退出（0 秒等待）

4. **未检测视频/商品详情页**:
   - browseTask 在 swipeCount=0 分支只检测 isBrowseProductListPage
   - 未检测 isProductDetailPageByAnyMeans（商品详情页）或视频页
   - 商品详情页（含视频）需要停留 15 秒看视频才能得肥料，但被当作"非浏览任务"立即退出

**修复**: browseTask 在 swipeCount=0 分支，isBrowseProductListPage 返回 false 后，增加视频/商品详情页检测
- [AutomationController.kt#L2310-2347](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L2310)
- 检测 1: `isProductDetailPageByAnyMeans()` = true（activity 含"detailactivity"）
  - 视为看视频任务，设 browsingProductEntered=true, browseTaskTargetSwipes=8（15秒/2秒）
  - 等待 15 秒后通过 browsing_product_target_reached 退出
- 检测 2: 兜底检测视频页（含"视频，按钮"或"双击可暂停或播放视频"文案）
  - 防止 activity 名不含 detail 关键字的视频页漏检
- 复用现有 browsingProductEntered 机制：
  - 滑动期间 isOnAbnormalPage 检查被豁免（line 2732-2734）
  - 8 次滑动后（~16 秒）通过 browsing_product_target_reached 退出（line 2502-2511）

**预期效果**:
- "看严选推荐商品" 任务点击"去完成"后，识别落地页为商品详情页/视频页
- 等待 15 秒（8 次滑动轮询）后退出，获得肥料
- 任务进度从 0/5 推进到 1/5、2/5... 直到 5/5 完成

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit eeb57f1 - fix: build642 修复 COLLECTING_DIRECT 页面未加载完就放弃 + isTaskCompletePage 农场主页误判
**用户需求**: "分析日志"

**输入日志**: `logs/debug_test_20260726_153127.log`（build641，1100+ 行，15:25:22~15:30:05 约 5 分钟）

**build641 验证结果**: ❌ "1500，肥料，点击领取" 按钮仍未被点击
- 15:25:43 state: NAVIGATING -> COLLECTING_DIRECT
- 15:25:45 collectDirect: found 0 direct buttons, attempt=0  ← 页面未加载完
- 15:25:47 [openTaskList-start] text='1500，肥料，点击领取'  ← 2 秒后按钮才出现
- 15:25:47~15:30:05 "1500，肥料，点击领取" 按钮在农场主页始终存在（clickable=true），从未被点击
- build641 在 checkTaskResult 加的 "click direct claim button before exit" 逻辑从未被触发
- 原因: checkTaskResult 只在任务执行后调用，PROCESSING_TASK 不检查 isTaskCompletePage

**根因分析**:

**根因 1**: COLLECTING_DIRECT 在 attempt=0 时若 buttons 为空，立即跳到 OPENING_TASK_LIST
- TAOBAO 农场页加载较慢，"1500，肥料，点击领取" 按钮在进入农场页 4 秒后才出现
- COLLECTING_DIRECT 在 2 秒时就检查并放弃了（attempt=0 → buttons 空 → 立即 OPENING_TASK_LIST）
- 之后流程进入 OPENING_TASK_LIST → PROCESSING_TASK → BROWSING_TASK 循环，再也没回 COLLECTING_DIRECT
- 1500 肥料奖励始终未领取

**根因 2**: isTaskCompletePage 误判农场主页为任务完成页
- 农场主页任务列表中的已完成任务显示"已完成"状态标签
- isTaskCompletePage 匹配 "已完成" 关键词，返回 YES
- 日志: `isTaskCompletePage: YES, matched=[已完成], sample=[芭芭农场, 13级, 1500，肥料，点击领取, ...]`
- sample 全是农场主页元素，"已完成" 来自任务列表中已完成任务的状态标签
- 虽然这只是诊断日志的误报（processTask 不检查 isTaskCompletePage），但会干扰后续 checkTaskResult 判断

**修复 1**: COLLECTING_DIRECT 在 attempt=0 时若 buttons 为空，等待 3 秒后重试
- [AutomationController.kt#L1213-1232](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L1213)
- 原: attempt=0 时 buttons 空 → 立即跳到 OPENING_TASK_LIST
- 新: attempt=0 时 buttons 空 → 等待 INTERVAL_PAGE_LOAD_MS (3s) 后重试 attempt=1
- attempt>=1 仍为空才走 AI 视觉/跨平台跳转/OPENING_TASK_LIST（保持原逻辑）
- 给页面充分加载时间，"1500，肥料，点击领取" 按钮在 3 秒后会被找到并点击

**修复 2**: isTaskCompletePage 排除农场主页（含直接领取按钮）
- [FarmAccessibilityService.kt#L4354-4372](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L4354)
- 原: 匹配 "已完成" 关键词 → 检查 isAdLandingPage → 返回 YES
- 新: 匹配 "已完成" 关键词 → 检查 isAdLandingPage → 检查是否有直接领取按钮 → 若有则返回 NO
- 直接领取按钮: "点击领取"/"立即领取"/"可领取"/"挖肥料"
- 任务完成页只有"关闭"/"返回"/"确认"按钮，不会有 direct collect 按钮
- 农场主页有 direct collect 按钮，可据此区分

**预期效果**:
- COLLECTING_DIRECT 在页面未加载完时不再立即放弃，等待 3 秒后重试
- "1500，肥料，点击领取" 按钮会被 COLLECTING_DIRECT 阶段找到并点击
- 1500 肥料奖励不再丢失
- isTaskCompletePage 不再误判农场主页为任务完成页

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 6607614 - fix: build641 修复 TAOBAO "1500，肥料，点击领取" 按钮始终未领取
**用户需求**: "长时间循环是对的。每次都要根据任务具体的时间来完成，任务的操作要对，点击开始任务了，如何是视频播放任务，需要播放15秒后结束，不是马上退出得不到肥料"

**输入日志**: `logs/debug_test_20260726_142922.log`（build639，1878 行，14:20:10~14:29:15 约 9 分钟）

**用户澄清**: 撤回 build640 的任务重复处理计数器，长时间循环是正确行为，bot 应根据任务要求完成（如视频播放 15 秒），不应跳过任务。

**build640 撤回**:
- 移除 `lastProcessedTaskText`、`sameTaskProcessedCount`、`MAX_SAME_TASK_PROCESS` 字段
- 移除 processTask 中 isBrowseTask 分支的重复检测逻辑
- 移除 reset() 中的计数器重置

**核心问题发现**: "1500，肥料，点击领取" 按钮始终未领取
- 日志显示 TAOBAO 农场主页始终有 "1500，肥料，点击领取" 按钮（bounds=[966,1441][1179,1676] clickable=true）
- 从 14:21:22 到 14:29:15（约 8 分钟）该按钮始终存在，从未被点击
- 1500 肥料奖励始终未领取

**根因分析**:
1. **TAOBAO directCollectTexts 缺失 "点击领取"**:
   - TAOBAO `directCollectTexts = listOf("可领取", "挖肥料")`，不含 "点击领取"
   - ALIPAY 和 UC 都已包含 "点击领取"，TAOBAO 缺失
   - COLLECTING_DIRECT 阶段 `findDirectCollectButtons` 找不到该按钮，直接跳到 OPENING_TASK_LIST

2. **isTaskCompletePage 误判**:
   - processTask 第 3110 行先检测 `isTaskCompletePage() == true` 就直接退出
   - 退出时只点 "关闭"/"返回" 按钮，不点 "点击领取" 按钮
   - 1500 肥料奖励始终未领取

**修复 1**: TAOBAO directCollectTexts 添加 "点击领取" 和 "立即领取"
- 与 ALIPAY 保持一致
- 过滤逻辑已排除 "已领取"/"还差"/"明日"/"施肥"/"生产中" 等锁定状态，加入是安全的

**修复 2**: isTaskCompletePage 添加匹配关键词调试日志
- 打印 `matched=${matchedKeywords.take(3)}`，方便定位是哪个关键词触发了 YES
- 用于诊断 isTaskCompletePage 误判的根因

**修复 3**: processTask 在 isTaskCompletePage 退出前先点击 "点击领取" 按钮
- 退出前调用 `findDirectCollectButtons()` 查找 "点击领取"/"立即领取" 按钮
- 找到则先点击领取，等待 INTERVAL_CLICK_MS 后再点关闭/返回按钮退出
- 确保奖励领取按钮被点击，避免 1500 肥料奖励丢失

**预期效果**:
- TAOBAO 农场主页的 "1500，肥料，点击领取" 按钮会被 COLLECTING_DIRECT 阶段找到并点击
- 即使 isTaskCompletePage 误判，processTask 也会先点击 "点击领取" 按钮再退出
- 1500 肥料奖励不再丢失

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit fcdf75e - fix: build640 修复浏览任务重复处理循环（发现精选好物 0/5 任务）【已撤回】
**用户需求**: "分析日志"

**撤回原因**: 用户澄清"长时间循环是对的"，bot 应根据任务要求完成，不应跳过任务。

---

### commit 9f5f2f2 - fix: build639 修复 RESUME_ORIGINAL_FARM 超时 + 搜索任务误判 + 登录对话框
**用户需求**: "全部解决"

**输入日志**: `logs/debug_test_20260726_140951.log`（build638，3993 行，13:42:55~14:09:37 约 27 分钟）

**build638 验证结果**: ✅ 跨平台切换修复完全生效
- 13:44:02~13:44:35 RESUME_ORIGINAL_FARM 阶段：`skip kill (killCurrentFirst=false)` 4 次 ✅
- 13:44:05 `original platform loaded (activeRootPkg=com.taobao.taobao)` 正确识别 ✅
- 2 次完整的 TAOBAO→ALIPAY→TAOBAO 跨平台任务都成功完成 ✅

**发现 3 个新问题**:

**问题 1**: RESUME_ORIGINAL_FARM 阶段 30 秒超时（13:44:05~13:44:35）
- 13:44:05 RETURN_ORIGINAL 正确识别原平台已加载（activeRootPkg=com.taobao.taobao）
- 13:44:16~13:44:35 RESUME_ORIGINAL_FARM retry 0~7，`isOnFarmPage` 一直 false
- 根因：TAOBAO 启动后停在淘宝主页（act=TBMainActivity），不在农场页，需要主动 navigateToFarm
- build638 只在 retry=2,4,6 时才主动 relaunch + navigateToFarm，但：
  - runSwitchingPlatform 间隔 2 秒，navigateToFarm 在 5 秒后才执行
  - 下一次 retry=4 触发 cancelNavigation 会取消之前 postDelayed 的 navigateToFarm
  - 导致 navigateToFarm 永远不会执行，30 秒后超时

**问题 2**: 任务列表无限循环（13:59:25~14:09:37，10 分钟）
- task #1 "买限时折扣好物得奖励" → isPaidTask=YES → skip
- task #2 "搜一搜你心仪的宝贝(1/5)" → isBrowseTask=true（命中"搜索"/"搜一搜"/"心仪"/"宝贝"关键词）
- 进入 BROWSING_TASK → 点击"去完成"进入搜索页 → 搜索页没有 swipe hint → 退出
- exitBrowsePage → OPENING_TASK_LIST → 重置 currentTaskIndex=0 → 又从 task #1 开始 → 循环

**问题 3**: 跨平台任务触发登录对话框（14:09:31）
- TAOBAO 出现登录对话框（com.ali.user.mobile.ui.widget.aliuserdialog）→ 登录页
- stepClickFarmTabByGesture pressBack 无法关闭登录对话框，重试 5 次后失败
- 用户手动停止

**修复 1**: RESUME_ORIGINAL_FARM 立即调用 navigateToFarm
- retry=0 时立即调用 `service.navigateToFarm()`（不等 retry=2）
- retry=4 时才主动 relaunch（如果 navigateToFarm 完全失败）
- 不再在 retry=2 触发 relaunch（避免 cancelNavigation 取消正在执行的 navigateToFarm）
- 增加间隔到 6 秒，让 navigateToFarm 有时间完成（内部多次 stepClickFarmTabByGesture）

**修复 2**: isBrowseTask 排除搜索任务
- 从 browseKeywords 移除"搜索"、"搜一搜"、"心仪"、"宝贝"关键词
- 添加排除条件：`!contextText.contains("搜一搜") && !contextText.contains("搜索你心仪")`
- 搜索任务会被当作普通点击任务处理，点击"去完成"进入搜索页
- checkTaskResult 检测到不在农场页/异常页面，跳过任务（currentTaskIndex++）

**修复 3**: stepClickFarmTabByGesture 处理登录对话框
- 检测到登录对话框/登录页时（activity 含"aliuserdialog"或"userloginactivity"）
- 主动 kill + relaunch TAOBAO App，绕过登录状态
- 等待 App 启动后重新导航

**预期效果**:
- RESUME_ORIGINAL_FARM 阶段 6 秒内回到农场页（不再 30 秒超时）
- 搜索任务不再误判为浏览任务，避免无限循环
- 登录对话框自动 kill + relaunch，不再卡死

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 4ebbf06 - fix: build638 修复 RESUME_ORIGINAL_FARM 反复 kill TAOBAO 导致无法启动到前台
**用户需求**: "分析日志"

**输入日志**: `logs/debug_test_20260726_125646.log`（build637，565 行，TAOBAO→ALIPAY 跨平台，12:42:34~12:47:54 约 5 分钟）

**build637 验证结果**: ❌ 修复未生效，问题更严重
- 12:46:44 跨平台任务"逛逛支付宝芭芭农场"点击"去完成"
- 12:46:50 ALIPAY 自动跳转，XRiver H5 fallback 生效 ✅
- 12:47:01 `switchPlatform: target farm page loaded, fertilizing` ✅
- 12:47:03 ALIPAY 施肥完成（11 个坐标点击）✅
- 12:47:09 `switchPlatform: returning to original TAOBAO` → RETURN_ORIGINAL
- **12:47:09.326 `switchPlatform: original platform loaded, resuming farm navigation`** ← **误判！**
- 12:47:19~12:47:36 RESUME_ORIGINAL_FARM retry 0~7，`pkg=com.hihonor.android.launcher` 一直在桌面
- 12:47:23/28/33 retry 2/4/6 触发 build637 修复逻辑，但每次都 `forceKillApp: killing com.taobao.taobao`
- 12:47:36 `original farm not loaded, re-navigating from start` → 放弃
- 12:47:47 用户手动停止

**根因 1**: RETURN_ORIGINAL 误判原平台已加载
- [AutomationController.kt#L5389](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L5389) 用 `getCurrentWindowPackage()` 判断原平台是否在前台
- `getCurrentWindowPackage()` 扫描所有 `windows`，可能返回 TAOBAO 包名（即使 `activeRootPkg='com.hihonor.android.launcher'`）
- `service.currentPlatform == switchOriginalPlatform` 在前面 `setCurrentPlatform()` 后必然为 true，无意义
- 导致误判"原平台已加载"，进入 RESUME_ORIGINAL_FARM 后 `isOnFarmPage()` 一直 false

**根因 2**: reopenFarmByDeepLink 反复 kill TAOBAO
- [FarmAccessibilityService.kt#L5360-5365](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L5360) 每次 relaunch 都先 HOME + `forceKillApp`
- 但 RESUME_ORIGINAL_FARM 场景当前前台是 ALIPAY（不是 TAOBAO），kill TAOBAO 没意义
- 反复 kill 让 TAOBAO 更难启动到前台（Honor 后台启动限制）
- 日志显示 retry 2/4/6 都触发 kill，但 `pkg` 一直是 `com.hihonor.android.launcher`

**根因 3**: build637 修复的 relaunch 调用链触发 kill 循环
- `service.launchPlatformApp(switchOriginalPlatform)` → `reopenFarmByDeepLink` → `forceKillApp`
- 形成 kill-relaunch-kill 循环，TAOBAO 永远无法启动到前台

**修复 1**: RETURN_ORIGINAL 用 activeRootPkg 判断原平台是否真正在前台
- 原: `getCurrentWindowPackage()` 扫描所有 windows（可能返回非前台窗口的包名）
- 新: `rootInActiveWindowSafe()?.packageName`（activeRootPkg，用户实际看到的窗口）
- 同时 retry 时传 `killCurrentFirst=false`

**修复 2**: reopenFarmByDeepLink/launchPlatformApp 添加 `killCurrentFirst` 参数
- 默认 `killCurrentFirst=true`（保持原有行为，任务完成场景需要 kill 老进程）
- 跨平台切换场景传 `killCurrentFirst=false`，直接 startActivity 启动目标平台，不 kill
- 避免 Honor 系统下反复 kill 导致 App 无法启动到前台

**修复 3**: 所有 7 处跨平台切换调用点传 `killCurrentFirst=false`
- LAUNCH_TARGET: `launchPlatformApp(switchTargetPlatform, killCurrentFirst = false)`
- LAUNCH_TARGET 失败: `launchPlatformApp(switchOriginalPlatform, killCurrentFirst = false)`
- NAVIGATE_TARGET_FARM 失败: `launchPlatformApp(switchOriginalPlatform, killCurrentFirst = false)`
- RETURN_ORIGINAL 入口: `launchPlatformApp(switchOriginalPlatform, killCurrentFirst = false)`
- RETURN_ORIGINAL retry: `launchPlatformApp(switchOriginalPlatform, killCurrentFirst = false)`
- RETURN_ORIGINAL MAX_SWITCH_RETRIES 失败: `launchPlatformApp(switchOriginalPlatform, killCurrentFirst = false)`
- RESUME_ORIGINAL_FARM retry: `launchPlatformApp(switchOriginalPlatform, killCurrentFirst = false)`

**预期效果**:
- RETURN_ORIGINAL 不再误判原平台已加载（用 activeRootPkg 判断）
- 跨平台切换时不再 kill 原平台，直接 startActivity 启动
- 解决 Honor 系统下反复 kill 导致 TAOBAO 无法启动到前台的问题
- RESUME_ORIGINAL_FARM 能快速回到原平台芭芭农场继续下一个任务

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 86ec158 - fix: build637 修复 RESUME_ORIGINAL_FARM 阶段 TAOBAO 未真正启动到前台
**用户需求**: "继续修复所有问题"

**输入日志**: `logs/debug_test_20260726_112042.log`（build636，303 行，TAOBAO 平台，11:18:28~11:20:42 约 2 分钟）

**build636 验证结果**: ✅ 修复1 完美生效
- 11:19:14 LAUNCH_TARGET → NAVIGATE_TARGET_FARM（移除 navigateToFarm 调用）
- 11:19:25 `target farm page loaded, fertilizing` ✅
- 11:19:27 11 个坐标点击施肥完成
- 11:19:33 RETURN_ORIGINAL retry=0
- **11:19:33.579 `switchPlatform: original platform loaded, resuming farm navigation`** ← **仅 0.1 秒就检测到原平台已加载！**（build635 要 17 秒）
- **没有 navigateAlipay 干扰**（无 `actively relaunching farm app` 日志）✅

**新问题**: RESUME_ORIGINAL_FARM 阶段 TAOBAO 未真正启动到前台（17 秒）

**问题详情**:
1. 11:19:33.579 `original platform loaded, resuming farm navigation` ← 误判（currentPkg 短暂是 TAOBAO）
2. 11:19:43~11:19:59 7 次 retry, `pkg=com.hihonor.android.launcher, act=com.taobao.themis.container.app.TMSActivity`
3. `activeRootPkg='com.hihonor.android.launcher'`（一直在桌面, TAOBAO 未真正启动到前台）
4. navigateToFarm 内部 retry 8 次后放弃
5. 11:19:59 `original farm not loaded, re-navigating from start`
6. 11:20:36 用户手动停止

**根因**:
- RETURN_ORIGINAL line 5389 `currentPkg == originalPkg` 误判（currentPkg 可能短暂是 TAOBAO 但实际仍在 launcher）
- RESUME_ORIGINAL_FARM 只在入口调用一次 navigateToFarm (line 5394), 之后只等待, 未主动 relaunch
- Honor 系统下 launchPlatformApp 可能因后台启动限制未成功, TAOBAO 进程存在但 Activity 不在前台

**修复**: RESUME_ORIGINAL_FARM retry 时主动 relaunch 原平台 + 重新 navigateToFarm ([AutomationController.kt#L5430-5447](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L5430))
- 原: retry 时只等待, 不主动 relaunch
- 新: retry > 0 且 % 2 == 0 时:
  1. `cancelNavigation()` 取消可能残留的导航队列
  2. `setCurrentPlatform(switchOriginalPlatform)` 恢复原平台
  3. `launchPlatformApp(switchOriginalPlatform)` 主动 relaunch 原平台
  4. 延迟 INTERVAL_PAGE_LOAD_MS 后 `navigateToFarm()` 重新导航到芭芭农场

**预期效果**:
- RESUME_ORIGINAL_FARM retry 2/4/6 时主动 relaunch TAOBAO + 重新 navigateToFarm
- 解决 Honor 系统后台启动限制导致 TAOBAO 未真正启动到前台的问题
- 不再卡在 launcher 17 秒, 快速回到原平台芭芭农场继续下一个任务

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 0e4fcc2 - fix: build636 修复跨平台任务完成后 RETURN_ORIGINAL 阶段无法返回原平台 TAOBAO
**用户需求**: "修复所有问题"

**输入日志**: `logs/debug_test_20260726_110139.log`（build635，300 行，TAOBAO 平台，10:59:32~11:01:39 约 2 分钟）

**build634/635 验证结果**: ✅ XRiver H5 fallback 完美生效
- 11:00:15 act=XRiverActivity, sample=[松开刷新, 稍等片刻, 返回, 更多...]（无 hasFarmContent 关键词）
- 11:00:26 `[switchPlatform-NAVIGATE_TARGET_FARM] snapshot: onFarm=true` ← build634 XRiver H5 fallback 生效
- 11:00:26 `switchPlatform: target farm page loaded, fertilizing` ← 直接进入施肥阶段
- 11:00:29 11 个坐标点击施肥完成
- 跨平台任务"逛逛支付宝芭芭农场"成功完成（不再死循环 4 分钟）

**新问题**: RETURN_ORIGINAL 阶段无法返回原平台 TAOBAO（11:00:34~11:00:51 共 17 秒）

**问题详情**:
1. 11:00:34 FERTILIZE_TARGET 完成 → RETURN_ORIGINAL
2. 11:00:34 `switchPlatform: returning to original TAOBAO` → launchPlatformApp(TAOBAO)
3. 11:00:34.965 `launchPlatformApp: opened TAOBAO via deep link`
4. 11:00:35.024 `refreshPlatform: detected platform=ALIPAY from pkg=com.eg.android.AlipayGphone` ← **关键！currentPlatform 变为 ALIPAY**
5. 11:00:37~11:00:51 7 次 retry, `activeRootPkg='com.hihonor.android.launcher'`（一直在桌面）
6. 11:00:42 `navigateAlipay: active root pkg=com.hihonor.android.launcher is launcher, retry=3, actively relaunching farm app` ← **navigateAlipay 被错误触发！**
7. 11:00:42 `forceKillApp: killing com.eg.android.AlipayGphone` + relaunch ALIPAY ← **把 ALIPAY kill 又重启, TAOBAO 永远无法启动**
8. 11:00:51 `failed to return to original, skipping task`
9. 11:00:53~11:01:29 后续 PROCESSING_TASK 仍在桌面/淘宝首页, 未回到农场
10. 11:01:32 用户手动停止

**根因（双重）**:

1. **LAUNCH_TARGET 阶段调用 navigateToFarm 启动了 stepNavigateAlipayFarm 链**:
   - line 5306 `service.navigateToFarm()` 根据 currentPlatform（已变为 ALIPAY）启动 stepNavigateAlipayFarm
   - stepNavigateAlipayFarm 在 navHandler 队列中持续执行
   - 即使后续 switchStage 切换到 RETURN_ORIGINAL, stepNavigateAlipayFarm 仍在运行
   - 在 RETURN_ORIGINAL retry 期间触发 `navigateAlipay: actively relaunching farm app`, 把 ALIPAY kill 后又 relaunch

2. **RETURN_ORIGINAL 阶段 currentPlatform 已变为 ALIPAY**:
   - LAUNCH_TARGET 的 `service.refreshPlatform()` 把 currentPlatform 改成了 ALIPAY（line 217）
   - RETURN_ORIGINAL line 5379 检查 `service.currentPlatform == switchOriginalPlatform`（TAOBAO）
   - currentPlatform=ALIPAY ≠ TAOBAO, 条件为 false, 一直 retry
   - retry 时未主动 relaunch 原平台, 只等 launcher 自动恢复（Honor 系统下不会自动恢复）

**修复（2 处）**:

#### 修复1: LAUNCH_TARGET 不再调用 navigateToFarm ([AutomationController.kt#L5306-5314](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L5306))
- 原: `service.navigateToFarm()` 启动 stepNavigateAlipayFarm 链
- 新: 移除 navigateToFarm 调用, 依赖 auto-jump（点击"去完成"已自动跳转到目标平台芭芭农场 H5 页面）
- NAVIGATE_TARGET_FARM 阶段会用 isOnFarmPage 等待加载完成（build634 XRiver H5 fallback 已能正确识别）

#### 修复2: RETURN_ORIGINAL retry 时主动 relaunch 原平台 ([AutomationController.kt#L5377-5418](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L5377))
- 原: retry 0 时 launchPlatformApp 一次, 之后只等待
- 新:
  - retry 0 时先 `cancelNavigation()`（取消残留的 navigateAlipay 队列）+ `setCurrentPlatform(TAOBAO)`（恢复原平台）+ launchPlatformApp
  - retry > 0 且 % 2 == 0 时主动 relaunch 原平台（Honor 系统下 launchPlatformApp 可能因后台启动限制未成功）

**预期效果**:
- 跨平台任务完成后 RETURN_ORIGINAL 阶段:
  - 立即 cancelNavigation 取消残留的 navigateAlipay 队列（不再 kill ALIPAY 又 relaunch）
  - setCurrentPlatform(TAOBAO) 恢复原平台（refreshPlatform 检测到 TAOBAO 时 currentPlatform==TAOBAO 匹配）
  - retry 2/4/6 时主动 relaunch TAOBAO（解决 Honor 后台启动限制）
- 不再卡在 launcher 17 秒, 快速返回原平台继续下一个任务

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 909aa33 - fix: build635 修复 build634 编译错误（val 声明提前避免 else 后语法错误）
**用户需求**: "分析日志" → 选择方案 C "修复 navigateAlipay 入口识别（top=156 < 508.6 判断逻辑）"

**输入日志**: `logs/debug_test_20260726_102123.log`（build633，593 行，TAOBAO 平台，10:15:28~10:21:23 约 6 分钟）

**build633 验证结果**: ✅ 完美生效
- 两种"买限时折扣好物得奖励"变体都被识别为 `paid=true` 跳过（line 94, 99）
- isBrowseTask 兜底排除"限时折扣"/"折扣好物"（未触发，因 isPaidTask 已优先识别）

**新问题**: 跨平台任务"逛逛支付宝芭芭农场"切换失败导致死循环（4 分钟）

**问题详情**:
1. 10:16:04 点击"逛逛支付宝芭芭农场" → SWITCHING_PLATFORM
2. 10:16:10 ALIPAY 已自动跳转到芭芭农场 H5 页面（act=XRiverActivity）
3. 10:16:12 navigateAlipay 找到"芭芭农场"入口（bounds=[125,156][567,242], top=156）
   - 屏幕高 2543, 阈值 = 2543 * 0.20 = 508.6
   - 156 < 508.6, 被搜索栏区域过滤跳过 → fallback to search 失败
4. 10:16:10-10:16:35 NAVIGATE_TARGET_FARM 7 次 retry, isOnFarmPage 始终返回 false
   - sample=[松开刷新, 稍等片刻, 返回, 支付宝·芭芭农场, 更多...] 只有 native 框架元素
   - 无 hasFarmContent 关键词 → isOnFarmPage=false
5. 10:16:35 max retries reached, abort → RETURN_ORIGINAL
6. 10:16:53-10:17:15 回到 TAOBAO 但未回到农场（sample=[下拉刷新, 百亿补贴, 淘宝秒杀]）
7. 10:17:15 重新导航 → 又遇到相同任务列表 → 又选 #3 跨平台任务 → 又 SWITCHING_PLATFORM → 死循环 4 分钟
8. 10:21:19 用户手动停止

**根因（双重）**:
1. **navigateAlipay 入口识别错误**: H5 页面（act=XRiverActivity）的"芭芭农场"标题（top=156）被搜索栏区域过滤跳过
2. **isOnFarmPage 未识别 H5 页面**: act=XRiverActivity 时 H5 内容未加载完, sample 只有 native 框架元素, 无 hasFarmContent 关键词 → 返回 false

**修复（2 处）**:

#### 修复1: isOnFarmPage 增加 XRiver H5 fallback ([FarmAccessibilityService.kt#L854-870](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L854))
- 在 `if (!hasFarmContentEffective)` 分支中, 增加 XRiverActivity + 含"芭芭农场"文字的兜底
- 当 act=XRiverActivity 且页面含"芭芭农场"文字（如"支付宝·芭芭农场"）且 farmPkgWindowVisible 时, 返回 true
- 安全性: 蚂蚁庄园等其他 XRiverActivity 页面不含"芭芭农场"文字, 不会误判

#### 修复2: navigateAlipay 搜索栏区域过滤增加 act 检查 ([FarmAccessibilityService.kt#L5837-5845](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L5837))
- 在 `rect.top < screenHeight * 0.20f` 条件中, 增加 `&& !isH5ContainerAct`
- isH5ContainerAct = act 包含 "xriver" 或 "h5" 或 "webview"
- H5 容器时, "芭芭农场"文字是页面顶部标题, 不应被当作搜索框占位文字跳过

**预期效果**:
- TAOBAO→ALIPAY 跨平台跳转后, ALIPAY 自动进入芭芭农场 H5 页面（act=XRiverActivity）
- isOnFarmPage 通过 XRiver H5 fallback 返回 true → NAVIGATE_TARGET_FARM 成功 → FERTILIZE_TARGET
- navigateAlipay 不再误过滤 H5 页面顶部标题（即使触发, isOnFarmPage 也能快速返回 true）
- 不再死循环, 跨平台任务能正常完成

**编译验证**: sandbox 网络限制无法本地编译, 等 CI 构建验证。

---

### commit 10e18c8 - fix: build633 修复"买限时折扣好物得奖励"任务被误判为浏览任务
**用户需求**: "分析日志，'买限时折扣好物得奖励'不完成这类任务"

**输入日志**: `logs/debug_test_20260726_100620.log`（build632，442 行，TAOBAO 平台，10:03:32~10:06:20 约 3 分钟）

**build632 验证结果**: ✅ 完美生效
- 10:04:49 `isProductDetailPageByAnyMeans: YES by activity` → `entered product detail page after clicking product, starting swipes` — build631 修复生效
- 10:05:18 `exitBrowsePage: clicking back icon to exit` → `still on browse product list page after first back, pressing back again` → `returned to farm after second back (from product list)` — build630 二次退出继续生效

**问题**: "买限时折扣好物得奖励"任务有两种文案变体，识别不一致

| 文案变体 | isPaidTask | isBrowseTask | 实际处理 | 结果 |
|---------|-----------|-------------|---------|------|
| `买限时折扣好物得奖励(0/1) 下单领大额肥料最高 +130000` | ✅ YES（命中"下单领"） | — | 跳过 | ✅ 正确 |
| `买限时折扣好物得奖励(0/3) 下单大额肥料 最高 +300000` | ❌ NO（无"下单领"，是"下单大额"） | ✅ YES（命中"好物"+"得奖励"） | 进入 BROWSING_TASK 滑动 | ❌ 错误 |

**错误后果**（10:05:40-10:06:02）:
- 10:05:40 `isBrowseTask: isBrowse=true` → 进入 BROWSING_TASK
- 10:05:44 点击"去完成"进入折扣商品列表页
- 10:05:49 `isBrowseProductListPage: YES` → 点击商品"快看！这台扇真不错！容声出品纯铜电机 ¥63.9"
- 10:05:54 进入商品详情页 → 开始 8 次滑动
- 10:06:02 用户手动停止

**根因**:
- `isPaidTask` 的关键词 `"下单领"` 只匹配含"领"字的文案，无法识别 `"下单大额肥料"` 这种无"领"字变体
- `isBrowseTask` 的关键词 `"好物"` 命中"折扣好物"，且 `!contextText.contains("签到")` 排除条件未覆盖购物任务

**修复（2 处）**:

#### 修复1: isPaidTask 加"下单大额"等关键词 ([FarmAccessibilityService.kt#L2729-L2731](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L2729))
- 新增关键词：`"下单大额"`, `"下单高额"`, `"下单得大额"`
- 覆盖文案：`买限时折扣好物得奖励(0/3) 下单大额肥料 最高 +300000`

#### 修复2: isBrowseTask 排除"限时折扣"/"折扣好物" ([FarmAccessibilityService.kt#L2808-L2812](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L2808))
- 在 `!contextText.contains("签到")` 后新增 `&& !contextText.contains("限时折扣") && !contextText.contains("折扣好物")`
- 兜底防止 isPaidTask 漏判时仍不走浏览流程

**预期效果**:
- 两种"买限时折扣好物得奖励"变体都被 isPaidTask 识别为付费任务，跳过
- 即使 isPaidTask 漏判，isBrowseTask 也会因含"限时折扣"/"折扣好物"返回 false，不走 BROWSING_TASK
- 走普通 processTask 流程（点击"去完成"进入折扣页 → checkTaskResult 兜底 → 不完成此任务）

**编译验证**: sandbox 网络限制无法本地编译，等 CI 构建验证。

---

### commit fa32268 - fix: build632 修复 build631 编译错误（currentActivityName 私有访问改用 getCurrentActivityName()）
**用户需求**: "分析日志" → "修复"

**输入日志**: `logs/debug_test_20260726_094309.log`（build630，888 行，TAOBAO 平台，09:38:04~09:43:09 约 5 分钟）

**build630 验证结果**: ✅ 完美生效
- 5 分钟内完成 3 次浏览任务（任务进度 1/5 → 4/5）
- 3 次 `exitBrowsePage: still on browse product list page after first back, pressing back again` → `returned to farm after second back (from product list)`
- 不再陷入 2 分钟导航循环

**新问题**: 第 4 次浏览任务点击的商品是直播商品
- 09:41:46 点击商品"仁和多种维生素b族复合片..."（bounds=[26,307][589,1234]）
- 09:41:51 `activity=com.ali.user.mobile.ui.widget.auprogressdialog` — 阿里登录对话框弹出
- 09:41:51-09:42:07 swipe #1~#8 期间 activity 一直是 `auprogressdialog`（对话框挡住页面）
- 09:42:09 `browsing product detail page reached target swipes (9/8), pressing back to exit`
- 09:42:14 落地页是**直播页**（`直播中, 商品视频, 开启声音, 1/6 宝贝讲解`）— 不是商品列表页
- 09:42:14 `not on farm page after exit, re-navigating`（build630 二次退出未触发，因为 `isBrowseProductListPage=false`）
- 09:42:22 重新导航回到农场主页
- 09:42:50 `AUProgressDialog` 又出现，09:43:04 用户手动停止

**根因**:
- `findBrowseProductNode` 通过 "¥" 找商品节点，但直播商品也包含 "¥"
- 商品列表页文本不含"直播"字样，无法预先识别直播商品
- 点击商品后直接开始 8 次滑动，未检测落地页是否是商品详情页
- 直播页/登录对话框挡住页面，8 次滑动无效，浪费时间

**修复（1 处）**:

#### 修复: 点击商品后检测落地页是否是商品详情页 ([AutomationController.kt#L2261-L2281](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L2261))
- 原：点击商品后 `INTERVAL_PAGE_LOAD_MS`（5秒）后直接 `runBrowsingTask(1)` 开始滑动
- 新：5 秒后先检测 `isProductDetailPageByAnyMeans()`：
  - 若是商品详情页 → 正常 `runBrowsingTask(1)` 开始滑动
  - 若不是 → `browsingProductEntered=false`（复位，避免误触发 build630 二次退出）+ `currentTaskIndex++`（跳过此任务）+ `exitBrowsePage` 退出
- 不增加 `collectedCount`（任务未真正完成）

**预期效果**:
- 点击直播商品 → 5 秒后检测到非商品详情页 → pressBack 退出 → build630 二次退出（若回到商品列表页）或 re-navigate（若回到直播页）→ 跳过此任务继续下一个
- 不再浪费 15 秒（8 次滑动）在直播页/登录对话框上
- 登录对话框（auprogressdialog）的 activity 不是 detail activity，`isProductDetailPageByAnyMeans` 返回 false，触发退出

**未解决问题（需下次日志验证）**:
- 跳过直播商品后，下一个任务是否正常执行
- 登录对话框持续出现时是否需要额外处理（如等待或取消）

**编译验证**: sandbox 网络限制无法本地编译，等 CI 构建验证。

---

### commit d31845d - fix: build630 修复商品详情页退出后停留在商品列表页导致导航循环
**用户需求**: "分析日志" → "修复"

**输入日志**: `logs/debug_test_20260726_092123.log`（build627，1489 行，TAOBAO 平台，08:52:58~09:21:13 约 28 分钟，包含 Run1+Run2 两段运行）

**问题**: build627/628 的 browsingProductEntered 退出逻辑（`exitBrowsePage`）只 pressBack 一次，但淘宝浏览任务页面层级是 `任务列表 → 商品列表页 → 商品详情页`，第一次 pressBack 只从商品详情页回到**商品列表页**，未回到任务列表。

**循环现象**（09:17:47-09:19:43 约 2 分钟）:
1. 09:17:42 `wait limit exceeded (swipes=39/38)` → `exitBrowsePage: clicking back icon to exit`
2. 09:17:47 落地页是商品列表页（含"芭芭农场-interact"+"浏览得奖励"+商品价格列表）
3. 09:17:55 `hasFarmContentLoaded=true`（text count=14）— **误判商品列表页为农场主页**
4. 09:17:57 `collectDirect: found 0 direct buttons` — 商品列表页无领取按钮
5. COLLECTING_DIRECT → OPENING_TASK_LIST → "WebView not ready" → NAVIGATING → 循环
6. 09:18:14 锁屏（com.hihonor.aod）— 系统介入
7. 09:19:36 解锁
8. 09:19:43 回到真正农场主页（text count=140，含"芭芭农场, 12级"）— 终于恢复

**根因**:
- `exitBrowsePage` 退出后只检测 `isOnFarmPage()`，未检测是否仍在商品列表页
- 商品列表页含"芭芭农场-interact"（H5 容器名）等关键词，被 `hasFarmContentLoaded` 误判为农场主页
- 误判后 COLLECTING_DIRECT 找不到领取按钮，陷入循环

**修复（1 处）**:

#### 修复: exitBrowsePage 加商品列表页二次退出 ([AutomationController.kt#L2771-L2796](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L2771))
- 原：第一次 pressBack 后只检测 `isOnFarmPage()`，是则 OPENING_TASK_LIST，否则 NAVIGATING
- 新：第一次 pressBack 后先检测 `isBrowseProductListPage()`，若是则**再 pressBack 一次**（找 backIcon 或 pressBack），等待后再检测 `isOnFarmPage() && !isBrowseProductListPage()`
- 流程：商品详情页 → (pressBack) → 商品列表页 → (检测到 isBrowseProductListPage=true) → (pressBack) → 任务列表/农场主页
- 与 `fromSearchBrowse` 二次退出逻辑并列，互斥（return@postDelayed）

**预期效果**:
- 商品详情页退出后：商品详情 → 商品列表 → (检测) → 任务列表 → OPENING_TASK_LIST
- 不再陷入导航循环（2 分钟 → ~10 秒）
- UC 平台不点商品，不会触发此分支（isBrowseProductListPage=false 时直接走原逻辑）

**编译验证**: sandbox 网络限制无法本地编译，等 CI 构建验证。

---

### commit a79813a - fix: build629 UC 滑动浏览任务不点商品直接滑动（仅 TAOBAO 点商品停留15秒）
**用户需求**: "uc滑动浏览任务，不需要点击商品进入下一个页面"

**问题**: build620 实现的浏览商品任务逻辑（点击商品进入详情页停留 15 秒）对 UC 和 TAOBAO 两个平台都生效。但用户反馈 UC 平台的滑动浏览任务**不需要点击商品**，直接在列表页滑动即可获得肥料。点击商品反而会进入商品详情页，浪费时间且可能触发风控。

**平台差异**:
- **TAOBAO**: 任务要求"浏览商品"，需点击商品进入详情页停留 15 秒（build620/626/627/628 实现）
- **UC**: 滑动浏览任务，直接在列表页滑动即可，不需要点击商品

**修复（2 处）**:

#### 修复1: swipeCount=0 分支加平台判断 ([AutomationController.kt#L2248-L2275](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L2248))
- 原：`if (service.isBrowseProductListPage())` → 点商品（UC/TAOBAO 都点）
- 新：`if (service.isBrowseProductListPage()) { if (TAOBAO) { 点商品 } else { UC 直接滑动 } }`
- UC 平台：检测到商品列表页后只打日志，不点商品，不设 `browsingProductEntered`，走普通滑动流程（靠 isFertilizerGrantedPage/isTaskCompletePage 退出）

#### 修复2: swipeCount>0 分支加平台判断 ([AutomationController.kt#L2585-L2606](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L2585))
- 原：`if (!browsingProductEntered && service.isBrowseProductListPage())` → 点商品
- 新：`if (!browsingProductEntered && service.currentPlatform == Platform.TAOBAO && service.isBrowseProductListPage())` → 仅 TAOBAO 点商品
- UC 平台：滑动过程中即使检测到商品列表页也不点商品，继续滑动

**预期效果**:
- UC 平台：检测到商品列表页 → 直接滑动浏览 → isFertilizerGrantedPage/isTaskCompletePage 命中后退出
- TAOBAO 平台：保持原逻辑（点商品进入详情页停留 15 秒）

**编译验证**: sandbox 网络限制无法本地编译，等 CI 构建验证。

---

### commit 1b7490f - fix: build628 修复 browsingProductEntered 15秒后未退出导致无限滑动
**用户需求**: "分析日志"

**输入日志**: `logs/debug_test_20260726_085823.log`（build627，726 行，TAOBAO 平台，08:52:58~08:57:39 约 5 分钟，用户手动停止）

**build627 修复验证结果**:
- ✅ swipe #1 之前豁免成功（line 139: `exempting from abnormal page check, keep waiting for fertilizer (swipe #1/8)`）
- ✅ 滑动后 `scheduleNextBrowseCheck` 豁免成功（line 145 起: `in product detail page during swipe (browsingProductEntered=true), exempting`）
- ✅ **任务进度从 0/4 变为 2/4**（line 73: `发现精选好物(2/4) 浏览15秒得 +700`）— build625/626/627 累计完成 2 次商品浏览！

**新问题**: 15 秒（8 次滑动）后未退出，无限滑动直到用户手动停止
- line 198: `swipe #8/8` 完成（08:54:33.623）
- line 202: `keep swiping within wait limit (countdown=0s, progress=false, remainingProgress=false, swipe #9/38)` — 走 waitLimit 分支
- line 204: `swipe #9/8`（08:54:36.928）
- line 303: `swipe #20/8`（08:55:18.192）
- ... 持续滑动到 swipe #100/8，最终 08:57:39 用户手动停止

**根因**: `runBrowsingTask`（[AutomationController.kt#L2414](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L2414)）的退出逻辑：
1. line 2389 `isFertilizerGrantedPage()` — 商品详情页不显示"肥料发放"文案 → false
2. line 2402 `isTaskCompletePage()` — 商品详情页不显示"任务完成"文案 → false
3. line 2414 `if (swipeCount > browseTaskTargetSwipes)` — swipeCount=9 > 8 → 进入 waitLimit 分支
4. line 2420 `waitLimit = browseTaskTargetSwipes + MAX_BROWSE_WAIT_SWIPES = 8 + 30 = 38` — 继续滑动 30 次（60 秒）
5. 60 秒后退出，但商品详情页 isFertilizerGrantedPage/isTaskCompletePage 永远不命中 → 实际一直滑到 waitLimit=38

**问题本质**: `browsingProductEntered=true`（点击商品进入详情页停留 15 秒）的场景下，商品详情页不会显示任务相关文案，上方的肥料发放/任务完成检测永远不会命中，导致走 waitLimit 无限滑动。

**修复（1 处）**:

#### 修复: browsingProductEntered 15秒后直接退出 ([AutomationController.kt#L2414-L2430](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L2414))
- 在 `if (swipeCount > browseTaskTargetSwipes)` waitLimit 分支**之前**，新增 `browsingProductEntered` 优先退出分支：
  ```kotlin
  if (browsingProductEntered && swipeCount > browseTaskTargetSwipes) {
      debugLog("browseTask: browsing product detail page reached target swipes, pressing back to exit")
      currentTaskIndex++
      collectedCount++
      browsingProductEntered = false  // 复位
      exitBrowsePage(service, reason = "browsing_product_target_reached")
      return
  }
  ```
- `browsingProductEntered=true` 时，swipeCount > 8 直接 pressBack 退出，不走 waitLimit
- 退出后 `runOpeningTaskList` 检测任务进度（如 2/4 → 3/4），若任务确实完成则继续下一个

**预期效果**:
- swipe #8 完成后（15 秒），swipe #9 进入 `browsingProductEntered && swipeCount > browseTaskTargetSwipes` 分支
- 直接 pressBack 退出商品详情页 → 返回任务列表 → 检测任务进度 2/4 → 3/4
- 不再无限滑动 30 次（60 秒）

**未解决问题（需下次日志验证）**:
- pressBack 退出商品详情页后，是否能正确返回任务列表（淘宝 WebView 返回键可能不可靠）
- 任务进度是否从 2/4 变为 3/4（若任务实际未完成，需检查是否需要点击其他商品继续浏览）

**编译验证**: sandbox 网络限制无法本地编译，等 CI 构建验证。

---

### commit 65a183c - fix: build627 修复 scheduleNextBrowseCheck 滑动后 isOnAbnormalPage 检测无 browsingProductEntered 豁免
**用户需求**: "分析日志"

**输入日志**: `logs/debug_test_20260726_084442.log`（build626，341 行，TAOBAO 平台，08:40:56~08:44:42 约 4 分钟）

**build626 修复验证结果**:
- ✅ `isBrowseProductListPage: YES`（line 131）— 商品列表页识别成功
- ✅ `browse product list page detected on swipeCount=0`（line 132）— 触发商品浏览流程
- ✅ `findBrowseProductNode: found product node bounds=[26,307][589,1421]`（line 133）— 找到商品
- ✅ `isProductDetailPageByAnyMeans: YES by activity (ttdetailactivity)`（line 137）— **build626 修复生效！**
- ✅ `browseTask: in product detail page (browsingProductEntered=true), exempting from abnormal page check, keep waiting for fertilizer (swipe #1/8)`（line 138）— 豁免成功！
- ✅ 任务进度从 0/4 变为 **1/4**（line 72）— 说明 build625 已成功完成 1 次商品浏览！

**新问题**: 滑动 1 次后立即退出（line 141-144）
- line 141-142: 执行 swipe #1 滑动（1450.0 -> 950.0）
- line 143: `isOnAbnormalPage: YES, activity=ttdetailactivity` — 滑动**之后**又检测到异常页
- line 144: `browseTask: abnormal/trading page during swipe, exiting immediately` — 退出

**根因**: `scheduleNextBrowseCheck`（[AutomationController.kt#L2608](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L2608)）滑动后的 `isOnAbnormalPage` 检测（L2626）**没有 `browsingProductEntered` 豁免**！

build626 只修复了 `runBrowsingTask` 开头的豁免（L2443，swipeCount 之前的检测），但 `scheduleNextBrowseCheck` 是滑动**之后**的回调，有独立的 `isOnAbnormalPage` 检测（L2626），未加豁免。

**流程**:
1. swipeCount=1 进入 `runBrowsingTask` → L2443 豁免成功（line 138）
2. 执行 swipe #1（line 141-142）
3. `scheduleNextBrowseCheck` 回调 → L2626 `isOnAbnormalPage=true`（ttdetailactivity）→ **无豁免** → 退出（line 144）

**修复（1 处）**:

#### 修复: scheduleNextBrowseCheck 加 browsingProductEntered 豁免 ([AutomationController.kt#L2624-L2641](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L2624))
- 原：`if (service.isOnAbnormalPage()) { 退出 }`
- 新：`if (browsingProductEntered && service.isProductDetailPageByAnyMeans()) { 豁免,继续滑动 } else if (service.isOnAbnormalPage()) { 退出 }`
- 与 `runBrowsingTask` L2443 的豁免逻辑一致

**预期效果**:
- swipe #1 之后 `scheduleNextBrowseCheck` 检测到 ttdetailactivity → `isProductDetailPageByAnyMeans=true` → 豁免 → 继续 swipe #2~#8
- 8 次滑动（15 秒）完成后，`isFertilizerGrantedPage`/`isTaskCompletePage` 检测肥料发放
- 任务进度从 1/4 变为 2/4

**未解决问题（需下次日志验证）**:
- 15 秒后 `isFertilizerGrantedPage`/`isTaskCompletePage` 是否命中（肥料发放检测）
- 若未命中会走 waitLimit 超时退出，任务可能未完成

**编译验证**: sandbox 网络限制无法本地编译，等 CI 构建验证。

---

### commit 713b23d - fix: build626 修复商品详情页 activity=ttdetailactivity 触发 isOnAbnormalPage 退出（browsingProductEntered 豁免失效）
**用户需求**: "分析日志"（日志在 GitHub 上）

**输入日志**: `logs/debug_test_20260726_083256.log`（build625，365 行，TAOBAO 平台，08:29:39~08:32:56 约 3 分钟）

**build625 修复验证结果**:
- ✅ `isBrowseProductListPage: YES (hasFertilizerHint=true, isFarmHome=false, isProductDetail=false, isNovelOrDrama=false, hasProductPrice=true)`（line 160）— build625 修复1+2 都生效
- ✅ `browseTask: browse product list page detected on swipeCount=0, clicking product to enter detail`（line 161）— build625 修复1 触发商品浏览流程
- ✅ `findBrowseProductNode: found product node bounds=[26,307][589,1234]`（line 162）— 找到商品节点
- ✅ `browseTask: browse product target swipes = 8 (15s / 2s interval)`（line 163）— 设置 15 秒滑动
- ✅ 点击商品 `performClickSafe(text='妇炎洁甲硝唑氯己定洗剂...')`（line 164-165）— 成功点击商品

**新问题**: 点击商品进入详情页后立即退出（line 166-167）
- `isOnAbnormalPage: YES, activity=com.taobao.android.detail.alittdetail.ttdetailactivity`（line 166）
- `browseTask: abnormal/trading page detected, exiting immediately`（line 167）— 立即退出

**根因**: build620 的 `browsingProductEntered` 豁免条件 `browsingProductEntered && service.isProductDetailPage()`（[AutomationController.kt#L2439](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L2439)）依赖 `isProductDetailPage()` 返回 true，但：
1. `isOnAbnormalPage` 通过 **activity 名** `ttdetailactivity` 直接判为异常页（[FarmAccessibilityService.kt#L3690](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L3690)），不需要内容检测
2. `isProductDetailPage()` 通过"加入购物车"+"立即购买"内容检测，但淘宝商品详情页（ttdetailactivity）的按钮可能在 H5/WebView 内，**不暴露给无障碍树** → `isProductDetailPage()` 返回 false
3. 豁免条件 `browsingProductEntered && isProductDetailPage()` 整体为 false → 走 `else if (isOnAbnormalPage())` 分支退出

**修复方案（2 处）**:

#### 修复1: 新增 isProductDetailPageByAnyMeans 方法 ([FarmAccessibilityService.kt#L615-L642](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L615))
- activity 名检测：`ttdetailactivity`/`detailactivity`/`goodsdetail`/`productdetail`
- 内容检测兜底：`isProductDetailPage()`（"加入购物车"+"立即购买"）
- 两者任一匹配即返回 true

#### 修复2: 豁免条件改用 isProductDetailPageByAnyMeans ([AutomationController.kt#L2439-L2443](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L2439))
- 原：`if (browsingProductEntered && service.isProductDetailPage())`
- 新：`if (browsingProductEntered && service.isProductDetailPageByAnyMeans())`
- `browsingProductEntered=true` 时，只要 activity 是 ttdetailactivity 就豁免，继续停留 15 秒

**预期效果**:
- 点击商品进入 ttdetailactivity → `isProductDetailPageByAnyMeans` 返回 true（activity 名匹配）→ 豁免 isOnAbnormalPage → 继续 8 次滑动（15 秒）→ 等待肥料发放
- 不再点击商品后立即退出

**未解决问题（需下次日志验证）**:
- 滑动 8 次（15 秒）后 `isFertilizerGrantedPage`/`isTaskCompletePage` 是否命中（肥料发放检测）
- 若 15 秒后未检测到肥料发放，会走 waitLimit 超时退出，任务可能未完成

**编译验证**: sandbox 网络限制无法本地编译，等 CI 构建验证。

---

### commit 932571c - fix: build625 修复商品浏览任务 isBrowseProductListPage 检测未触发 + isFarmHome 误判"芭芭农场-interact"
**用户需求**: "分析日志"

**输入日志**: `logs/debug_test_20260726_081800.log`（build624，4437 行，TAOBAO 平台，07:17:49~08:18:00 约 1 小时）

**核心问题**: bot 卡在 idx=0 "发现精选好物(0/4) 浏览15秒得 +700" 任务死循环约 1 小时（07:18:54~08:17），每 25 秒一轮，共约 140 轮。

**任务列表全貌**（line 62-86）:
- idx=0: 发现精选好物(0/4) 浏览15秒得 +700 ← **商品浏览任务（build620 目标）**
- idx=1: 搜一搜你心仪的宝贝(0/5) 浏览得奖励 +600 ← 也是商品浏览任务
- idx=2: 逛逛支付宝芭芭农场(0/1) 还可领 +1900
- idx=3: 去玩支付宝蚂蚁庄园(0/1) 逛逛得 +300
- idx=7: 领肥料礼包(0/1) 去领取

**2 个根因**:

#### 根因1：build620 的 isBrowseProductListPage 检测在 swipeCount=0 分支被"not a browse task"提前 return，根本没机会执行
- **现象**: line 130 显示点击"去完成"后落地页是商品列表页（"浏览得奖励/滑动浏览得肥料"+商品价格列表 ¥30.18/¥16.91 等），但 line 131 显示 `browseTask: no swipe hint and no browse reward indicator ... not a browse task, exiting without swiping` 直接退出
- **根因**: `runBrowsingTask` 的 swipeCount=0 分支顺序：
  1. 点击"去完成"按钮
  2. 等待页面加载
  3. 检测 `findSwipeForFertilizerHint`（"滑动浏览N秒"）→ 页面是"滑动浏览得肥料"无N秒 → 返回 0
  4. 检测 `findBrowseRewardCountdownHint`/`hasBrowseRewardProgressHint`/`findBrowseDurationRewardHint` → 都需要N秒数字 → 全 false
  5. 4 个指标全 false → `exitBrowsePage` 退出
  6. **build620 的 `isBrowseProductListPage` 检测在 swipeCount>0 分支（L2530），根本到不了**
- **后果**: 商品浏览任务被误判为"not a browse task"退出，任务进度始终 0/4，下一轮 currentTaskIndex 重置回 0 又重试 → 死循环

#### 根因2：isBrowseProductListPage 的 isFarmHome 误判"芭芭农场-interact"为农场主页
- **现象**: 即使 isBrowseProductListPage 被调用，也会因 isFarmHome=true 返回 false
- **根因**: `isFarmHome` 判断包含 `it.contains("芭芭农场")`，但商品列表页 H5 容器名是"芭芭农场-interact"（line 130），也包含"芭芭农场" → 误判为农场主页 → isProductList=false
- **后果**: 商品列表页被排除，isBrowseProductListPage 返回 false

**修复方案（2 处）**:

#### 修复1：runBrowsingTask swipeCount=0 分支优先检测 isBrowseProductListPage ([AutomationController.kt#L2242-L2266](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L2242))
- 在点击"去完成"后等待页面加载，**先**检测 `service.isBrowseProductListPage()`
- 若是商品列表页 → `findBrowseProductNode` 找商品 → 点击商品 → `browseTaskTargetSwipes=8`（15秒/2秒）→ 进入滑动循环
- 若无商品节点 → 仍设 `browseTaskTargetSwipes=8` 在列表页直接滑动
- 然后才走原来的滑动提示检测（findSwipeForFertilizerHint 等）
- 确保 build620 的商品浏览逻辑在 swipeCount=0 就能触发，不被"not a browse task"提前退出

#### 修复2：isBrowseProductListPage 的 isFarmHome 移除"芭芭农场"判断 ([FarmAccessibilityService.kt#L2886-L2894](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L2886))
- 移除 `it.contains("芭芭农场")`（太宽泛，H5 容器名"芭芭农场-interact"会误判）
- 改用更精确的农场主页核心元素：`集肥料`/`施肥`/`换种`/`好友林`/`合种`/`帮种`
- 商品列表页没有这些核心元素，能正确识别为非农场主页

**预期效果**:
- idx=0 "发现精选好物"任务：点击"去完成" → 商品列表页被识别 → 点商品 → 停留15秒（8次滑动）→ 任务完成 1/4 → 下一轮继续 idx=1
- 不再死循环 1 小时卡在 idx=0

**未解决问题（需下次日志验证）**:
- `findBrowseProductNode` 的屏幕中部范围 `rect.top < 300 || rect.bottom > 2400` 可能过滤掉所有商品节点，需日志验证是否走兜底逻辑
- build622 的 3 个修复（AI 坐标校验/搜索任务识别/onFarm 兜底）本日志无答题任务触发，未验证

**编译验证**: sandbox 网络限制无法本地编译，等 CI 构建验证。

---

### commit (待提交) - fix: build622 跳过"招募英雄"类游戏任务（三国冰河常规招募英雄1次，bot 无法在游戏内完成该动作）
**用户需求**: "三国冰河常规招募英雄1次，属于游戏任务，不需要完成"

**背景**: 现有跳过名单 `skipTaskTexts`（AutomationController L1966）已包含"玩游戏"/"玩1局"/"完成1局"/"打一局"等无法靠停留完成的纯游戏动作类任务。但"招募英雄"这类任务需要真正在游戏内执行"招募英雄N次"动作才能得肥料，bot 无法通过无障碍自动化完成（与"玩游戏"停留拿肥料不同，无法靠停留 GAME_PLAYING 流程完成）。

**修改（1 处）**:

**修复1: skipTaskTexts 追加"招募英雄"关键词** ([AutomationController.kt#L1966-L1980](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L1966))
- 修改前: skipTaskTexts 含"玩游戏"/"玩1局"/"完成1局"/"打一局"等游戏动作关键词
- 修改后: 追加"招募英雄"关键词（注释说明：三国冰河常规招募英雄1次，无法靠停留完成）
- 匹配规则: `buttonText.contains(it) || taskContextText.contains(it)`，"招募英雄"会命中任务上下文文本"三国冰河常规招募英雄1次"，直接跳过该任务（currentTaskIndex++ → 处理下一任务）
- 覆盖范围: "招募英雄"子串匹配，覆盖"三国冰河常规招募英雄1次"/"招募英雄3次"/"完成招募英雄"等所有变体

**与 isGameTask 的区别**:
- `isGameTask` 返回 true → 进入 GAME_PLAYING 状态机停留玩一下拿肥料（适用于"打开游戏停留 N 秒得肥料"类任务）
- `skipTaskTexts` 命中 → 直接跳过任务不点击（适用于"必须在游戏内完成特定动作"类任务，如"招募英雄N次"/"完成1局对战"）
- "招募英雄"属于后者：必须真正在游戏内点招募按钮完成招募动作，bot 无法执行，应直接跳过

**预期效果**: 任务列表里出现"三国冰河常规招募英雄1次"等任务时，processTask 检测到 taskContextText 含"招募英雄" → 直接 currentTaskIndex++ 跳过 → 处理下一个任务，不点击"去完成"按钮进入游戏页（避免卡死在游戏内无法完成）。

**编译验证**: sandbox 网络限制无法本地编译，等 CI 构建验证。

---

### commit (待提交) - fix: build621 修复 build619 AI 视觉答题奖励按钮重试 Runnable 编译失败（rewardFirstCheckDone 声明在 Runnable 之后 + val 自引用）
**用户需求**: "流水线编译出错"

**CI 构建状态**: build620 (run #621) **失败** - compileNoOcrReleaseKotlin / compileFullReleaseKotlin 编译报错

**编译错误**（3 处，均在 [AutomationController.kt#L3461-L3465](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L3461)）:
```
e: AutomationController.kt:3461:34 Unresolved reference: rewardFirstCheckDone
e: AutomationController.kt:3462:33 Unresolved reference: rewardFirstCheckDone
e: AutomationController.kt:3465:53 Unresolved reference: checkRewardAndProceed
```

**根因**: build619 引入的奖励按钮重试 Runnable 有两个作用域问题：
1. `var rewardFirstCheckDone = false` 声明在 `val checkRewardAndProceed = Runnable {...}` **之后**（原 L3486），但 Runnable 内部（原 L3461/L3462）引用它。Kotlin 局部变量作用域从声明处开始，声明在后的变量对前面的 lambda 不可见 → `Unresolved reference: rewardFirstCheckDone`
2. `val checkRewardAndProceed = Runnable {...}` 在自身初始化表达式的 lambda 内部（原 L3465）引用 `checkRewardAndProceed`（自引用递归调度）。Kotlin 局部 `val` 在初始化表达式内部不可引用自身 → `Unresolved reference: checkRewardAndProceed`

**修复（1 处）**:

**修复1: 调整声明顺序 + 改用 var 延迟赋值** ([AutomationController.kt#L3440-L3493](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L3440))
- `var rewardFirstCheckDone = false` 移到 Runnable 定义之前（L3445），确保闭包可见
- `val checkRewardAndProceed = Runnable {...}` 改为 `var checkRewardAndProceed: Runnable? = null` 先声明（L3446），再赋值 `checkRewardAndProceed = Runnable {...}`（L3447），解决 val 自引用问题
- lambda 内部引用改为 `checkRewardAndProceed!!`（L3472 重试 / L3493 首次调度）
- 逻辑保持 build619 原意：首次 2.5 秒未检测到奖励按钮 → 标记 rewardFirstCheckDone=true → 再等 2.5 秒重试 → 第二次仍未找到则失败计数+1

**预期效果**: 编译通过，build619 的奖励按钮重试逻辑生效（2.5 秒首次 + 2.5 秒重试 = 共 5 秒检测窗口）

**编译验证**: sandbox 网络限制无法本地编译，等 CI 构建验证。

---

### commit 85a4c7b - feat: build620 UC 浏览商品任务点击商品停留15秒得肥料
**用户需求**: "uc浏览商品任务，需要点击某个商品后停留15秒才可以得到肥料"

**背景**: 现有 `runBrowsingTask` 在 swipeCount=0 阶段明确跳过点商品（L2271-2274 注释"不主动点击商品进入详情页"），只在商品列表页滑动。但 UC 浏览商品任务需要点击商品进入详情页停留 15 秒才能获得肥料。商品详情页有"加入购物车"+"立即购买"按钮，正常会被 `isOnAbnormalPage` 判为异常页立即退出，无法停留。

**修改（3 文件 5 处）**:

**修复1: 新增 isBrowseProductListPage / findBrowseProductNode 方法** ([FarmAccessibilityService.kt#L2867-L2951](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L2867))
- `isBrowseProductListPage()`: 检测商品列表页（特征：有"得肥料"+价格符号¥，无"加入购物车"/"立即购买"，无"开始阅读"/"开始观看"，无农场主页核心元素）
- `findBrowseProductNode()`: 找含"¥"的可点击商品节点，优先屏幕中部（top 300~2000，bottom 500~2400），无 clickable 祖先时保留节点自身（dispatchGesture 按坐标点击，与 findGoCompleteButtons 一致）
- 参考模板：`isShortDramaPage` / `findShortDramaPlayButton`（build590）

**修复2: 新增 browsingProductEntered 字段 + 复位点** ([AutomationController.kt#L189-L190](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L189))
- 新增 `@Volatile private var browsingProductEntered: Boolean = false`
- 复位点1: `resetState()` ([line 572](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L572))
- 复位点2: `runBrowsingTask(swipeCount=0)` 入口 ([line 2209](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L2209))

**修复3: isOnAbnormalPage 调用加 browsingProductEntered 豁免** ([AutomationController.kt#L2405-L2418](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L2405))
- 修改前: `if (service.isOnAbnormalPage())` → 商品详情页立即退出
- 修改后: `if (browsingProductEntered && service.isProductDetailPage())` 豁免（继续停留等待肥料），`else if (service.isOnAbnormalPage())` 才退出
- 理由: 商品详情页是我们主动点击进入的，需要停留 15 秒等待肥料发放，不应视为异常页

**修复4: runBrowsingTask 新增浏览商品任务分支** ([AutomationController.kt#L2514-L2544](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L2514))
- 插入位置: 短剧任务检测之后（L2512）、执行滑动之前（L2546）
- 流程: 检测 `isBrowseProductListPage()` → `findBrowseProductNode()` 找商品 → `performClickSafe` 点击 → `browsingProductEntered=true` + `browseTaskTargetSwipes=8`（15秒/2秒=8次）→ 等待 `INTERVAL_PAGE_LOAD_MS` 后继续滑动循环
- 滑动循环中: `isFertilizerGrantedPage` 命中 → RETURNING（reopenFarmByDeepLink 重开农场）；`isTaskCompletePage` 命中 → pressBack 退出；15秒超时 → waitLimit 退出
- 参考模板: build590 短剧任务分支（L2487-L2512）

**修复5: UC browseTaskKeywords 追加商品关键词** ([Platform.kt#L278](file:///workspace/app/src/main/java/com/bbncbot/automation/Platform.kt#L278))
- 修改前: `listOf("搜索浏览", "浏览搜索")`
- 修改后: `listOf("搜索浏览", "浏览搜索", "逛商品", "浏览商品")`
- 通用 `browseKeywords` 已含"看商品"/"宝贝"/"好物"/"推荐商品"等，此处追加 UC 平台特有文案"逛商品"/"浏览商品"

**预期效果**:
- UC 浏览商品任务（描述含"逛商品"/"浏览商品"）被 `isBrowseTask` 识别为浏览任务 → 进入 BROWSING_TASK
- 点击"去完成"进入商品列表页 → `isBrowseProductListPage` 检测到 → `findBrowseProductNode` 找到商品 → 点击进入商品详情页
- 商品详情页停留 15 秒（8 次 × 2 秒滑动模拟活跃）→ `isFertilizerGrantedPage`/`isTaskCompletePage` 检测到完成 → pressBack 退出回主页
- 商品详情页不被 `isOnAbnormalPage` 误判退出（browsingProductEntered 豁免）

**遗留问题（下次观察）**:
- `isBrowseProductListPage` 依赖"¥"/"元"价格符号识别商品列表，若 UC 商品列表页无价格符号（如纯图片商品卡片），需补充识别特征
- `findBrowseProductNode` 取第一个含"¥"的中部节点，可能点到非商品元素（如"¥0.01运费"等），下次日志验证
- 15 秒停留后若未检测到肥料发放（`isFertilizerGrantedPage`/`isTaskCompletePage` 都没命中），会走 waitLimit 超时退出（pressBack），任务可能未完成

**编译验证**: sandbox 网络限制 gradle wrapper 下载超时，无法本地编译。代码逻辑审查通过（参考 build590 短剧任务模式，复用 collectNodesByText/findClickableSelfOrParentInternal 既有方法），等 CI 构建验证。

---

### commit (待提交) - fix: build619 AI 视觉答题奖励按钮检测增加重试（2.5 秒首次+2.5 秒重试，提高渲染慢时检测成功率）
**用户需求**: "修复所有问题"（build616 修复3 的可靠性增强）

**背景**: build616 修复3 在 AI 视觉答题点击答案后等 2.5 秒检测奖励按钮。但日志显示奖励按钮可能渲染较慢（H5/Canvas 弹窗动画），2.5 秒可能不够，导致 findQuizRewardButton 返回 null，错误计入失败次数。

**修复（1 处）**:

**修复1: 奖励按钮检测增加重试** ([AutomationController.kt#L3391-L3438](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L3391))
- 修改前（build616）: 点击答案 → 等 2.5 秒 → 检测奖励按钮 1 次 → 找到则领取，未找到则失败计数+1
- 修改后（build619）: 点击答案 → 等 2.5 秒 → 首次检测 → 未找到则再等 2.5 秒 → 第二次检测 → 仍未找到才失败计数+1
- 用 Runnable + rewardFirstCheckDone 标记实现重试（避免嵌套 postDelayed 过深）
- 总等待时间：5 秒（2.5+2.5），比 build616 的 2.5 秒更宽容，给奖励按钮更多渲染时间
- 找到奖励按钮立即领取，不再等待第二次

**预期效果**:
- 奖励按钮渲染快（< 2.5 秒）: 首次检测即领取（与 build616 相同）
- 奖励按钮渲染慢（2.5-5 秒）: 第二次检测领取（build616 会误判为失败）
- 奖励按钮未弹出（> 5 秒或坐标不准）: 失败计数+1（build617 失败计数器兜底）

**编译验证**: 等 CI 构建验证（Runnable 引用局部变量 rewardFirstCheckDone，需确认 Kotlin 闭包捕获语义正确）

---

### commit (待提交) - fix: build618 修复 AiVisionClient.kt:970 字符串中未转义的中文双引号导致编译失败
**用户需求**: "流水线编译出错"

**CI 构建状态**: build617 (run #618) **失败** - Build APKs 步骤报错

**编译错误**:
```
e: AiVisionClient.kt:970:40 Unresolved reference: 可以
e: AiVisionClient.kt:970:40 Unsupported [literal prefixes and suffixes]
e: AiVisionClient.kt:970:45 Unsupported [literal prefixes and suffixes]
... (共 14 条错误，均集中在第 970 行)
```

**根因**: build616 修复1 增强提示词时，在 `append()` 字符串中使用了未转义的中文双引号 `"可以"/"不能"/"是"/"否"/"A"/"B"`，Kotlin 编译器把第一个 `"` 当作字符串结束，后面的中文字符被当作标识符引用，导致 "Unresolved reference: 可以" 和 "Unsupported [literal prefixes and suffixes]"

**修复（1 处）**:

**修复1: 字符串中双引号转义** ([AiVisionClient.kt#L970](file:///workspace/app/src/main/java/com/bbncbot/automation/AiVisionClient.kt#L970))
- 修改前: `append("- 选项是可点击的彩色区域（含文字如"可以"/"不能"/"是"/"否"/"A"/"B"），坐标应指向该彩色区域中心\n")`
- 修改后: `append("- 选项是可点击的彩色区域（含文字如\"可以\"/\"不能\"/\"是\"/\"否\"/\"A\"/\"B\"），坐标应指向该彩色区域中心\n")`
- 用 `\"` 转义双引号，使其成为字符串内容而非结束符

**编译验证**: 等 CI 构建验证（修复后应恢复 build616/build617 的所有逻辑）

---

### commit (待提交) - fix: build617 AI 视觉答题连续失败计数器（build616 修复3 在坐标不准时仍死循环的兜底）
**用户需求**: "修复"（针对 build616 修复3 的潜在风险增强）

**背景**: build616 修复3 在"找到奖励按钮"时能解决死循环，但在"未找到奖励按钮"时（AI 坐标仍不准，没点到选项，奖励按钮没弹出），仍会前进下一任务 → 任务进度 0/1 → openTaskList 重置 currentTaskIndex=0 → 又回到答题任务 → 死循环。

**日志证据**（debug_test_20260725_195306.log）:
- line 137-139: AI 8 次都返回固定 `x_ratio=0.5, y_ratio=0.917`（屏幕底部）
- line 143: 第 1 次点击后屏幕弹出"领取奖励 500"（答对了，但 build611 跳过领取）
- line 1010-1019: 第 8 次点击 (600, 2326) 后应用退出到桌面（com.hihonor.android.launcher），用户手动停止

**潜在风险**: build616 修复3 的 findQuizRewardButton 依赖奖励按钮弹出。若 AI 坐标仍不准（修复1 增强提示词无效），点击不到选项，奖励按钮不会弹出，findQuizRewardButton 返回 null，仍前进下一任务，死循环。

**修复（1 处，3 个修改点）**:

**修复1: 新增 quizVisionFailCount 计数器** ([AutomationController.kt#L326-L333](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L326))
- 新增 `@Volatile private var quizVisionFailCount: Int = 0`
- 新增 `private val QUIZ_VISION_FAIL_THRESHOLD = 3`（阈值 3 次）
- start() 中重置（[line 560](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L560)）

**修复2: AI 视觉答题分支累计失败次数** ([AutomationController.kt#L3388-L3396](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L3388))
- 检测到奖励按钮（答题成功）→ `quizVisionFailCount = 0`（重置）
- 未检测到奖励按钮（AI 坐标不准）→ `quizVisionFailCount++` 并记录日志 `fail count=$quizVisionFailCount/$QUIZ_VISION_FAIL_THRESHOLD`

**修复3: openTaskList 超阈值跳过答题任务** ([AutomationController.kt#L1388-L1400](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L1388))
- 修改前: 每轮 OPENING_TASK_LIST 重置 `currentTaskIndex = 0`（又回到答题任务）
- 修改后: 若 `quizVisionFailCount >= QUIZ_VISION_FAIL_THRESHOLD`（3 次），从 `currentTaskIndex = 1` 开始（跳过 idx=0 的答题任务）
- 跳过后重置 `quizVisionFailCount = 0`（给下一轮答题任务机会，可能题目已刷新或 AI 坐标已修正）

**预期效果**:
- AI 坐标准确（修复1生效）: 点击正确选项 → 弹出奖励按钮 → 点击领取 → 任务完成 1/1 → 计数器重置 → 不死循环
- AI 坐标不准（修复1无效）: 连续 3 次未检测到奖励按钮 → 计数器达阈值 → openTaskList 跳过答题任务（从 idx=1 开始）→ 不死循环，最坏情况跳过该任务

**遗留问题（下次观察）**: 若 AI 坐标仍不准，答题任务会被跳过（任务进度仍 0/1，但不再死循环）。需要进一步优化 AI 提示词或换用其他坐标识别方案（如 OCR 识别选项文字位置）。

**编译验证**: sandbox 网络限制 gradle wrapper 下载超时，无法本地编译。代码逻辑审查通过（@Volatile 保证多线程可见性，handler.post 内修改主线程安全），等 CI 构建验证。

---

### commit (待提交) - fix: build616 AI 视觉答题点击后检测奖励按钮+点击领取（build611 跳过领取导致死循环 8 次）
**用户需求**: "全部修复"（修复1+2+3 三处问题一起处理）

**输入日志**: `logs/debug_test_20260725_195306.log` (build615-2766047, ~1000 行, 2026-07-25 19:53 上传, TAOBAO 平台)

**build613/614/615 修复验证**: ✅ 全部生效
- build613 日志可见性: AI 调用细节完整写入 debug.log（starting/calling/got content/success）
- build614 max_tokens=2048: glm-4.6v-flash 直接返回正确结果，不再 fallback 到 glm-4v-flash
- build615 max_tokens 按模型区分: glm-4.6v-flash=2048 不再触发 HTTP 400

**日志暴露的新问题（核心）**: build611 "答题后直接前进下一任务" 跳过了领取奖励步骤，死循环 8 次

**时间线**（第 1 次答题，19:41:14 ~ 19:48:00 共 8 次循环）:

| 行号 | 时间 | 现象 |
|------|------|------|
| 126-139 | 19:41:14-32 | 第 1 次：AI 视觉答题点击 (600, 2331)（屏幕底部空白，坐标不准）|
| 140 | 19:41:37 | **`state: PROCESSING_TASK -> OPENING_TASK_LIST`**（build611 直接前进下一任务，跳过领取）|
| 143 | 19:41:43 | **`[openTaskList-start] text='领取奖励 500' bounds=[117,2358][1084,2489] clickable=true`**（答对了！奖励按钮已弹出，但未领取）|
| 172 | 19:41:50 | `collectTaskContextText: result='农场百科问答(0/1)...'`（进度仍 0/1，未完成）|
| 209-216 | 19:41:53-54 | 第 2 次：`[processTask-start]` 屏幕上仍有"领取奖励 500"按钮，但 bot 不识别，重新点"去答题"|
| 595 | 19:44:51 | 第 6 次：答错，弹出"领取鼓励奖 150"（鼓励奖也是肥料），仍未领取 |
| 974 | 19:47:39 | 第 8 次：仍在循环，用户未停止但日志结束 |

**根因（核心）**: build611 的修复"答题后直接前进下一任务"是为了避免误点"返回首页"退出农场，但跳过了关键步骤——**点击答题后弹出的"领取奖励 500"/"领取鼓励奖 150"按钮**。
- 答题答对/答错都会弹出可点击的奖励按钮（bounds=[117,2358][1084,2489]）
- 不点击该按钮，任务进度始终 0/1，任务列表重置 currentTaskIndex=0 又回到答题任务
- AI 截图坐标不准（返回固定 0.5/0.917 屏幕底部）只是次要原因——即使坐标不准，只要奖励按钮弹出，点击它就能完成任务

**修复（3 处，本 build 完成全部）**:

**修复1: 增强提示词强制真实坐标检测** ([AiVisionClient.kt#L962-L972](file:///workspace/app/src/main/java/com/bbncbot/automation/AiVisionClient.kt#L962))
- 在 buildQuizAnswerPrompt 末尾追加"坐标精度要求（非常重要）"章节
- 明确要求：
  - `x_ratio/y_ratio 必须是截图中正确答案选项按钮的真实中心坐标比例`
  - `必须根据截图实际像素位置计算，不要返回 0.5/0.9 之类的默认值或估算值`
  - `选项按钮通常位于屏幕中部（y_ratio 约 0.4-0.7），而不是屏幕底部（y_ratio 接近 1.0）`
  - `若选项按钮在屏幕上半部，y_ratio 应小于 0.5；在下半部，y_ratio 应大于 0.5`
- 目的：解决 AI 8 次都返回固定 `x_ratio=0.5, y_ratio=0.917`（屏幕底部空白区域）的问题

**修复2: max_tokens 按模型区分** ([AiVisionClient.kt#L883-L890](file:///workspace/app/src/main/java/com/bbncbot/automation/AiVisionClient.kt#L883))
- 修改前: `put("max_tokens", 2048)`（glm-4v-flash 不支持，HTTP 400 "max_tokens参数非法：限制数值范围[1,1024]"）
- 修改后: `val maxTokens = if (model.contains("4.6v") || model.contains("thinking")) 2048 else 1024`
- 推理模型（glm-4.6v-flash）用 2048（足够 reasoning_content + content），非推理模型（glm-4v-flash）用 1024（API 上限）

**修复3: 答题点击后检测并点击奖励按钮** ([AutomationController.kt#L3354-L3391](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L3354) + [FarmAccessibilityService.kt#L4052-L4088](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L4052))
- 新增 `findQuizRewardButton` 方法（FarmAccessibilityService）：
  - 不受场景白名单限制（与 findClaimRewardButton 的关键区别）
  - 精确匹配"领取奖励"/"领取鼓励奖"前缀（不匹配"领取"子串，避免诱导按钮）
  - 必须可点击才返回（避免误识别纯文案节点）
- 修改 AI 视觉答题分支点击答案后的逻辑（AutomationController）：
  - 修改前（build611）: 点击答案 → `currentTaskIsQuiz=false` + `currentTaskIndex++` → `moveTo(OPENING_TASK_LIST)`（直接前进，跳过领取）
  - 修改后（build616）: 点击答案 → 等 2.5 秒让奖励按钮渲染 → `findQuizRewardButton` 检测 → 找到则 `performClickSafe` 领取 → 等 `INTERVAL_PAGE_LOAD_MS`（5 秒）让弹窗消失 → 前进下一任务
  - 未找到奖励按钮也前进下一任务（保持 build611 行为，避免 onFarm 误点返回首页）

**预期效果**:
- 答对：弹出"领取奖励 500" → 检测到 → 点击领取 → 任务完成 1/1 → 前进下一任务
- 答错：弹出"领取鼓励奖 150" → 检测到 → 点击领取 → 任务完成 1/1 → 前进下一任务
- 都没检测到：仍前进下一任务（不再死循环，最坏情况跳过该任务）

**遗留问题（下次观察）**: AI 仍可能返回固定坐标 (0.5, 0.917)（修复1 增强提示词后下次日志验证）。若坐标仍不准但奖励按钮能弹出，本修复已足够解决死循环。若坐标完全点击不到任何选项（答错也没奖励），需要进一步分析截图问题。

**编译验证**: sandbox 网络限制 gradle wrapper 下载超时，无法本地编译。代码逻辑审查通过（findQuizRewardButton 复用 findNodeByText 既有方法，performClickSafe 在主线程 handler.post 内调用安全），等 CI 构建验证。

---

### commit (待提交) - fix: build614 glm-4.6v-flash max_tokens 从 300 增到 2048（推理模型 reasoning_content 耗尽 token 导致 content 为空）
**用户需求**: "分析日志" → "修复"

**输入日志**: `logs/debug_test_20260725_192257.log` (build615-2766047, 241 行, 2026-07-25 19:20 上传, TAOBAO 平台)

**修复验证总结（全部生效）**:

| 修复项 | 日志证据 | 状态 |
|--------|---------|------|
| build613 日志可见性 | line 126-137 完整显示 AI 调用细节（models/apiKeyLen/imgLen/bodyLen/HTTP 响应/content 预览/错误原因）| ✅ 完美生效 |
| build612 重试机制 | line 126-136 glm-4.6v-flash 失败 → fallback glm-4v-flash 成功（重试+模型 fallback 生效）| ✅ 生效 |
| build611 答题后不误点返回首页 | line 138 `PROCESSING_TASK -> OPENING_TASK_LIST`（直接前进，没走 onFarm 分支误点返回首页）| ✅ 生效 |

**日志暴露的新问题（核心）**: glm-4.6v-flash `max_tokens=300` 太小，reasoning_content 耗尽 token 导致 content 为空

**时间线**（第 1 次答题，19:21:49 ~ 19:22:18）:

| 行号 | 时间 | 现象 |
|------|------|------|
| 126-127 | 19:21:56 | `answerQuizByVision: starting` → `calling glm-4.6v-flash (bodyLen=333291)` |
| 128 | 19:22:10 | **`empty content, response={"choices":[{"finish_reason":"length","index":0,"message":{"content":"","reasoning_content":"用户现在需要分析截图中的题目..."}}]}`** ← `finish_reason: "length"`，max_tokens=300 耗尽 |
| 129-130 | 19:22:10-13 | fallback `glm-4v-flash` → 3 秒返回成功 |
| 136-137 | 19:22:13 | `success: xRatio=0.5, yRatio=0.8` → 点击 (600.0, 2034.4) |

**根因（核心）**: glm-4.6v-flash 是推理模型，会先输出 `reasoning_content`（思考过程），再输出 `content`（正式答案）。300 tokens 不够思考完，正式 content 没输出就被截断（`finish_reason: "length"`）。

**修复（1 处）**:

**修复1: max_tokens 从 300 增到 2048** ([AiVisionClient.kt#L883-L889](file:///workspace/app/src/main/java/com/bbncbot/automation/AiVisionClient.kt#L883))
- 修改前: `put("max_tokens", 300)`（推理模型思考过程耗尽 token，content 为空）
- 修改后: `put("max_tokens", 2048)`（让推理模型有足够空间完成思考并输出 content）
- 首选模型 glm-4.6v-flash 将直接返回正确结果，不用 fallback 到 glm-4v-flash（响应更快、坐标更准）

**遗留问题（下次观察）**: glm-4v-flash 返回的坐标 `xRatio=0.5, yRatio=0.8`（点击 (600, 2034)）可能不准——答题后任务进度仍是 (0/1)（line 169），任务列表重置又回到答题任务。修复 max_tokens 后首选 glm-4.6v-flash 成功，坐标应更准（推理模型理解力更强），下次日志验证。

**编译验证**: sandbox 网络限制 gradle wrapper 下载超时，无法本地编译。代码逻辑审查通过（单行数值修改，无依赖问题），等 CI 构建验证。

---

### commit (待提交) - fix: build613 AI 视觉答题增加日志可见性（AiVisionClient 日志通过回调写入 debug.log）
**用户需求**: "分析日志" → "修复"

**输入日志**: `logs/debug_test_20260725_191033.log` (build614-acfb33f, 135 行, 2026-07-25 19:08 上传, TAOBAO 平台)

**build612 修复验证**: ✅ 重试机制生效
- line 128 `AI vision quiz retry 1/3` + line 132 `retry 2/3`（3 次循环执行）

**日志暴露的新问题（核心）**: AI 视觉答题连续 3 次都失败，但看不到失败原因

| 行号 | 时间 | 现象 |
|------|------|------|
| 119-123 | 19:09:44 | quiz task detected → 点击"去答题" |
| 124-127 | 19:09:50-51 | checkTaskResult: onFarm=true, isQuizPage=false → AI 视觉答题触发（第 1 次）|
| 128 | 19:10:09 | retry 1/3（第 1 次失败，约 18 秒）|
| 129-131 | 19:10:24 | 用户手动停止 |
| 132 | 19:10:26 | retry 2/3（用户停止后后台线程还在跑，日志滞后）|

**根因**: AiVisionClient 用 `android.util.Log`（写 logcat），不写 debug.log 文件。测试日志里完全看不到 AI 调用的失败原因：
- HTTP 错误码（401/403/429/500 等）
- 空 content（API 返回但内容空）
- not found（截图不是答题页，AI 返回 found=false）
- 异常（网络超时、JSON 解析失败等）

无法判断是 API 调用失败还是截图问题，无法针对性修复。

**关键对比**:
- build612 日志（18:47）：AI 视觉答题**成功**
- build613/614 日志（19:00/19:08）：AI 视觉答题**连续失败**
- 间隔很短（13 分钟/8 分钟），同一设备，可能是 API 限流或临时故障，但无法确认

**修复（2 处）**:

**修复1: AiVisionClient.answerQuizByVision 增加 logger 回调** ([AiVisionClient.kt#L767-L818](file:///workspace/app/src/main/java/com/bbncbot/automation/AiVisionClient.kt#L767))
- 新增 `logger: ((String) -> Unit)? = null` 参数
- 关键日志同时写 logcat + logger 回调：
  - `starting (models=..., apiKeyLen=..., imgLen=...)`（调用前参数）
  - `calling $model (bodyLen=...)`（HTTP 请求发送）
  - `failed (HTTP $code): ${err.take(200)}`（HTTP 错误码+错误信息）
  - `empty content, response=...`（API 返回空内容）
  - `got content (len=..., head='...')`（API 返回内容预览）
  - `quiz answer not found in screenshot`（AI 判断不是答题页）
  - `success: scene=..., xRatio=..., yRatio=..., reason=...`（成功）
  - `exception: ${e.message}`（异常）
  - `all vision models failed (lastError=..., lastMsg=...)`（全部失败汇总）

**修复2: callVisionModelForQuizAnswer 增加 logger 参数** ([AiVisionClient.kt#L825-L927](file:///workspace/app/src/main/java/com/bbncbot/automation/AiVisionClient.kt#L825))
- 同样增加 `logger` 参数，传递给上述日志输出

**修复3: AutomationController 传递 debugLog 回调** ([AutomationController.kt#L3328-L3331](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L3328))
- `answerQuizByVision(..., logger = { msg -> debugLog("processTask: $msg") })`
- 让 AI 调用细节写入 debug.log 文件，下次测试日志可看到失败原因

**目的**: 下次测试日志能看到 AI 调用的具体失败原因，针对性修复（API 限流加退避/截图问题加等待/模型问题换模型）。

**编译验证**: sandbox 网络限制 gradle wrapper 下载超时，无法本地编译。代码逻辑审查通过（lambda 回调线程安全，debugLog 已有后台线程调用先例），等 CI 构建验证。

---

### commit (待提交) - fix: build612 AI 视觉答题增加等待+重试机制（答题页 H5/Canvas 加载慢导致截图拿到农场页，AI 返回 null）
**用户需求**: "分析日志" → "修复"

**输入日志**: `logs/debug_test_20260725_190222.log` (build613-4f2eb2f, 221 行, 2026-07-25 19:00 上传, TAOBAO 平台)

**build611 修复验证**: 未触发（本轮 AI 视觉答题失败，没走到点击答案步骤，无法验证 build611 的"答题后不误点返回首页"修复）

**日志暴露的新问题（核心）**: AI 视觉答题返回 null，答题任务失败跳过

| 行号 | 时间 | 现象 |
|------|------|------|
| 119-123 | 19:01:21 | 第 1 次：quiz task detected → 点击"去答题" |
| 124-127 | 19:01:27-28 | checkTaskResult: onFarm=true, isQuizPage=false → AI 视觉答题触发（点击后仅 6 秒，仍 onFarm=true 答题页可能还在加载）|
| 128 | 19:01:41 | **`AI vision quiz failed (no answer found), skipping task`**（耗时 13 秒） |
| 203-211 | 19:01:58 | 第 2 次：quiz task detected → 点击"去答题"（任务列表重置 currentTaskIndex=0，又回到答题任务）|
| 212-215 | 19:02:04 | checkTaskResult: onFarm=true, isQuizPage=false → AI 视觉答题触发 |
| 216-218 | 19:02:15 | 用户手动停止（AI 视觉还在执行中）|

**根因**: 点击"去答题"后答题页 H5/Canvas 加载慢，checkTaskResult 触发 AI 视觉时（点击后仅 6 秒），onFarm=true 仍农场页，答题页可能还没渲染完成。
- AI 截图拿到的是农场页或半渲染答题页
- AI 判断 found=false（不是答题页），返回 null
- 答题任务失败跳过（currentTaskIndex++ → OPENING_TASK_LIST）
- 任务列表重置 currentTaskIndex=0，又回到答题任务，死循环

**关键对比**:
- build612 日志（18:47）：AI 视觉答题**成功**（`reason='题目：大米存放时生虫了...'`）
- build613 日志（19:00）：AI 视觉答题**失败**（`no answer found`）
- 两次都是 TAOBAO 平台，同一设备，间隔仅 13 分钟，说明是时序问题（答题页加载快慢），不是 AI 模型问题

**修复（1 处）**:

**修复1: AI 视觉答题增加等待 + 截图重试机制** ([AutomationController.kt#L3302-L3330](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L3302))
- 修改前: 截图 1 次 → AI 识别 1 次 → null 则跳过任务
- 修改后: 循环最多 3 次：截图 → AI 识别 → 非 null 则 break，null 则 sleep 2 秒后重试
- 重试间隔 2 秒（让答题页 H5/Canvas 继续加载）
- 3 次都失败才跳过任务（日志区分 "screenshot null" 和 "no answer found"）
- 总耗时上限：3 次 × (AI 识别 ~13 秒 + 等待 2 秒) ≈ 45 秒（远小于用户手动停止的耐心阈值）

**编译验证**: sandbox 网络限制 gradle wrapper 下载超时，无法本地编译。代码逻辑审查通过（ButtonLocationResult 全限定名引用正确，Thread.sleep 在后台线程安全），等 CI 构建验证。

---

### commit (待提交) - fix: build611 AI 视觉答题后误点"返回首页"退出农场卡死淘宝首页 80 秒
**用户需求**: "分析日志" → "1"（选择修复方向 1：答题后不走 onFarm 分支"返回首页"）

**输入日志**: `logs/debug_test_20260725_185110.log` (build612-887aa28, 492 行, 2026-07-25 18:47 上传, TAOBAO 平台)

**build609/610 修复验证全部生效** ✅:
- build609 签到任务不再死循环: line 249 `isBrowse=false`（"去红包签到得肥料"不再误判为浏览任务），line 262-264 找"返回首页"退出，无死循环滑动
- build610 AI 视觉答题标记: line 355 `quiz task detected (button='去答题', context contains 答题/问答)`
- build610 AI 视觉答题触发: line 362 `quiz task but isQuizPage=false, trying AI vision to answer`
- build610 AI 视觉答题成功: line 363 `AI vision quiz clicking answer at (600.0,2034.4), reason='题目：大米存放时生虫了但是没发霉还能吃吗？正确答案：不能。选项位于屏幕中部'`（AI 识别题目+选项+正确答案+点击坐标全部正确）

**日志暴露的新问题（核心）**: 答题后误点"返回首页"退出农场，卡死淘宝首页 ~80 秒，用户手动停止

| 行号 | 时间 | 现象 |
|------|------|------|
| 362-363 | 18:49:13-26 | AI 视觉答题点击正确答案 (600.0, 2034.4) |
| 364-367 | 18:49:32 | checkTaskResult(attempt+1): onFarm=true, claim-text-nodes=1（只有"兔兔挖肥料"），isTaskCompletePage=false |
| 368-370 | 18:49:33 | **onFarm 分支: `found 返回首页 button, clicking`** → 点击 bounds=[1082,160][1148,226]（农场页右上角"返回首页"按钮 desc='返回首页'）→ **退出农场** |
| 372-378 | 18:49:40 | `act=com.taobao.tao.welcome.Welcome, onFarm=false` → 到了淘宝首页 |
| 379-419 | 18:49:41 | openTaskList 在淘宝首页找到"去领取"按钮（鲜食加补券/消费券等优惠券），误当农场任务处理 |
| 420-486 | 18:49:43-18:51:03 | **卡死 80 秒**: 每 5 秒检测 isOnFarmPage=false，一直找不到农场 |
| 487-489 | 18:51:03 | 用户手动停止 |

**根因**: build610 AI 视觉答题分支点击答案后 `currentTaskIsQuiz=false` 重置，然后 `checkTaskResult(attempt+1)`。
第二次 checkTaskResult 时 currentTaskIsQuiz 已是 false，不知道是答题后续，走到 onFarm 分支:
- onFarm=true, claim-text-nodes=1（只有"兔兔挖肥料"，无答题奖励按钮），isTaskCompletePage=false
- onFarm 分支 findBackToHomeButton 找到农场页右上角"返回首页"按钮（desc='返回首页'），点击退出农场
- 但农场页右上角的"返回首页"按钮是**退出农场**的按钮，不是关闭答题页的按钮

**修复（1 处）**:

**修复1: AI 视觉答题点击答案后直接前进下一任务，不走 checkTaskResult** ([AutomationController.kt#L3328-L3351](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L3328))
- 修改前: 点击答案 → `currentTaskIsQuiz=false` → `checkTaskResult(attempt+1)` → 走 onFarm 分支误点"返回首页"
- 修改后: 点击答案 → 等待 INTERVAL_PAGE_LOAD_MS → `currentTaskIsQuiz=false` + `currentTaskIndex++` → `moveTo(OPENING_TASK_LIST)` + `runOpeningTaskList(0)`
- 理由: AI 已选正确答案并点击，答题任务完成，直接前进到下一任务，继续在农场页处理下一个任务，不触发"返回首页"退出逻辑
- 与 build610 QUIZ_PAGE 文本答题分支的差异: 文本答题分支也走 checkTaskResult(attempt+1)，但文本答题场景下 checkTaskResult 能找到答题奖励按钮；AI 视觉答题场景下无障碍树抓不到内容，checkTaskResult 找不到奖励按钮会误退农场，所以需要直接前进

**编译验证**: sandbox 网络限制 gradle wrapper 下载超时，无法本地编译。代码逻辑审查通过，等 CI 构建验证。

---

### commit (待提交) - feat: build610 AI 视觉答题（答题页 H5/Canvas 绘制，无障碍树抓不到内容时截图交 AI 选答案）
**用户需求**: "分析日志，去答题，任务需要选择一个答案，可以借助AI接口来选择答案"

**输入日志**: `logs/debug_test_20260722_222228.log` (build609-5433d7c, 344 行, 2026-07-22 22:22 上传, TAOBAO 平台)

**build609 修复验证**: "去红包签到得肥料"任务未再死循环滑动（日志中无红包签到任务，本轮未触发）。welcome 闪屏页/AI 视觉超时/点 App 图标进农场均正常。

**日志暴露的新问题（核心）**: "去答题"任务点击后答题页内容抓不到，走 onFarm 分支点"返回首页"退出农场，答题失败

| 行号 | 现象 |
|------|------|
| 68/113/283 | task='农场百科问答(0/1) 每天答题领肥料 +150~500 去答题',点击"去答题"按钮 |
| 122-128 (第一次) | checkTaskResult: onFarm=true, claim-text-nodes 仅 1 个（下单领肥料），无答题问题/选项 → 找到"返回首页"按钮点击退出农场 |
| 290-298 (第二次) | checkTaskResult: onFarm=true, claim-text-nodes 仅 2 个, isRechargePage=YES 误判 → 找到"返回首页"按钮点击退出农场 |

**根因**: 答题页是 H5/Canvas 绘制，无障碍树抓不到问题+选项文本
1. `isQuizPage()` 依赖 `findQuizOptions`（找 2 个可点击文字节点）+ `findQuizQuestion`（找含"？"的文本）→ 抓不到返回 false
2. `identifyCurrentScene` 不返回 QUIZ_PAGE → checkTaskResult 不走 QUIZ_PAGE 的文本答题分支（QuizAnswerClient）
3. 走到 onFarm 分支 → 找到"返回首页"按钮点击 → 退出农场，答题任务失败

**修复（3 处）**:

**修复1: AiVisionClient 新增 answerQuizByVision** ([AiVisionClient.kt#L767-L809](file:///workspace/app/src/main/java/com/bbncbot/automation/AiVisionClient.kt#L767))
- 截图交给 GLM-4.6V-Flash，让 AI 识别答题页题目和选项，选出正确答案，返回正确选项坐标
- 复用 ButtonLocationResult（xRatio/yRatio 归一化坐标）和 parseButtonLocationResult 解析
- 新增 buildQuizAnswerPrompt：明确"这是答题页，选出正确答案，返回正确选项中心坐标"
- 新增 callVisionModelForQuizAnswer：与 callVisionModelForButtonLocation 类似，system message 改为"答题模块"
- 同样的限流 fallback 策略（glm-4.6v-flash → glm-4v-flash，429 退避重试 2 次）

**修复2: AutomationController 新增 currentTaskIsQuiz 标记** ([AutomationController.kt#L320-L324](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L320))
- 新增 `@Volatile private var currentTaskIsQuiz: Boolean = false`
- start() 中重置（[line 550](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L550)）
- processTask 点击按钮前设置（[line 2139](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L2139)）：`buttonText.contains("答题") || taskContextText.contains("答题") || taskContextText.contains("问答")`

**修复3: checkTaskResult 新增 AI 视觉答题分支** ([AutomationController.kt#L3292-L3346](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L3292))
- 插入位置：QUIZ_PAGE 文本答题分支之后、onFarm 分支之前
- 触发条件：`currentTaskIsQuiz=true && scene != QUIZ_PAGE`（答题任务但无障碍树没抓到答题内容）
- 流程：后台线程 takeScreenshotBitmap → AiVisionClient.answerQuizByVision → handler.post 按坐标 dispatchGestureClick 正确答案 → checkTaskResult(attempt+1) 检测结果
- 失败兜底：截图 null 或 AI 返回 null → 跳过任务（currentTaskIndex++ → OPENING_TASK_LIST）
- 点击后重置 currentTaskIsQuiz=false（避免后续 checkTaskResult 重复触发）

**与现有 QUIZ_PAGE 文本答题分支的关系**:
- QUIZ_PAGE 分支：无障碍树抓到问题+选项文本 → QuizAnswerClient.askAnswer（纯文本 API）→ 按节点点击
- AI 视觉答题分支：无障碍树抓不到 → AiVisionClient.answerQuizByVision（视觉 API）→ 按坐标点击
- 两者互补，覆盖原生答题页 + H5/Canvas 答题页两种场景

**编译验证**: sandbox 网络限制 gradle wrapper 下载超时，无法本地编译。代码逻辑审查通过（takeScreenshotBitmap/dispatchGestureClick 方法签名与现有用法一致），等 CI 构建验证。

---

### commit (待提交) - fix: build609 淘宝"去红包签到得肥料"任务死循环滑动（签到天数误识别为浏览进度 + 签到任务误判为浏览任务）
**用户需求**: "拉取最新日志分析" → "修复" + "签到的肥料任务需要"

**输入日志**: `logs/debug_test_20260722_213059.log` (build608-bdc9eef, 287 行, 2026-07-22 21:30 上传, TAOBAO 平台)

**build607/608 修复验证全部生效** ✅:
- build608 淘宝 welcome 闪屏页卡死: line 18 直接 `act=TMSActivity, onFarm=true`,无 welcome 卡死
- build607 点 App 图标即进农场: line 5 `onResume: first resume & permissions ready, default to opening UC farm`
- build607 AI 视觉 15s 超时保护: line 41 `AI vision timed out after 15000ms, fallback to task list`,未卡死
- "去签到"任务完成: line 127 `isTaskCompletePage: YES`,签到成功

**日志暴露的新问题（核心）**: "去红包签到得肥料"任务死循环滑动 9 次/27 秒,用户手动停止

| 行号 | 现象 |
|------|------|
| 224-228 | task='去红包签到得肥料(0/1) 浏览10s得 +300 去完成',`isBrowseTask=true`（描述含"浏览"命中 browseKeywords）|
| 241-242 | 点击"去完成"后进入红包签到页（五棱镜poplayer/再攒67360元宝提现/累计签到奖励(1/7)）,不是浏览页 |
| 243-245 | `findProgressFraction` 把"累计签到奖励(1/7)"签到连续天数误识别为浏览进度 cur=1/tot=7 → `target swipes = 5` |
| 248-281 | 滑动 #1-9,"累计签到奖励(1/7)"永远不变（签到天数不会因滑动改变）|
| 263-278 | swipeCount>5 后 `hasRemainingProgress=true`（1/7,remaining=6>0）→ "keep swiping within wait limit" 持续滑动 |
| 282 | 用户手动停止（否则会滑到 waitLimit=35 次/~105 秒）|

**根因（2 处误判叠加）**:
1. **findProgressFraction 误识别**: 正则 `(\d+)/(\d+)` 匹配到"累计签到奖励(1/7)",把签到连续天数（第1天/共7天）当作浏览进度（1秒/7秒）。导致 `browseTaskTargetSwipes=5` + `hasRemainingProgress` 永远为 true
2. **isBrowseTask 误判**: browseKeywords 含"浏览",任务描述"去红包签到得肥料(0/1) 浏览10s得 +300"命中 → 判为浏览任务。但落地页是红包签到页（内容固定,滑动无意义）

**修复（2 处互补）**:

**修复1: findProgressFraction 排除签到分数** ([FarmAccessibilityService.kt#L1420-L1438](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L1420))
- plainFraction 匹配后,检查文本是否含"签到",含则跳过（签到天数不是浏览进度）
- secondsFraction 不受影响（要求"[秒s]"后缀,"1/7"不匹配）

```kotlin
if (cur in 0..300 && tot in 1..300 && cur <= tot) {
    // build609 修复：排除签到相关分数。
    if (text.contains("签到")) {
        debugLog("findProgressFraction: skip sign-in day fraction '$text' (not browse progress)")
        continue
    }
    debugLog("findProgressFraction: found plain fraction '$text', cur=$cur, tot=$tot")
    return BrowseProgressInfo(ProgressType.FRACTION, cur, tot, text)
}
```

**修复2: isBrowseTask 排除签到任务** ([FarmAccessibilityService.kt#L2766-L2776](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L2766))
- browseKeywords 匹配后,加排除条件 `!contextText.contains("签到")`
- 签到任务走普通点击流程（processTask 点击"去完成" → checkTaskResult 兜底处理）,不走 BROWSING_TASK 滑动流程
- "去签到"等纯签到任务本不含"浏览"不会命中 browseKeywords,此处只防御含"浏览"的签到任务

```kotlin
val isBrowse = browseKeywords.any { contextText.contains(it) } &&
    // build609 修复：排除签到类任务。
    !contextText.contains("签到")
```

**两处修复互补**:
- 即使 isBrowseTask 误判（含"浏览"）,findProgressFraction 排除签到分数后浏览流程也能快速退出（无进度提示 → "not a browse task, exiting without swiping"）
- isBrowseTask 排除签到后,签到任务走普通点击流程,processTask 点击进入签到页,checkTaskResult 兜底处理（红包签到页 onFarm=false,scene≠SIGN_IN,会走 unknown page 调 AI 视觉兜底）

**编译验证**: sandbox 网络限制 gradle wrapper 下载超时,无法本地编译。代码逻辑审查通过,等 CI 构建验证。

---

### commit (待提交) - fix: build608 淘宝 welcome 闪屏页卡死（isOnTaobaoHomePage 误判）
**用户需求**: "分析日志"

**输入日志**: `logs/debug_test_20260722_205618.log` (build606-3bfc8bc, 56 行, 2026-07-22 20:56 上传)

**⚠️ 重要**: 日志 line 1 显示 `build=build606-3bfc8bc`,**用户还在用 build606,没装 build607**!build607 Release downloads=0 印证。build607 的 3 个修复(点 App 图标进农场/systemui/AI 视觉超时)都未生效。build608 包含 build607 全部修复,用户直接装 build608 即可。

**日志暴露的问题**: 淘宝 welcome 闪屏页卡死（line 10-22）

| 行号 | 现象 |
|------|------|
| 10-22 | `isOnTaobaoHomePage: NOT on main page, activity not main (act=com.taobao.tao.welcome.welcome)` × 6 次,found=5 tabs 全找到 |
| 22 | `failed to reach main page after 5 retries` |
| 27-36 | 自动化启动后 `isOnFarmPage: activity=welcome not in farm keywords, not on farm page` × 多次 |
| 50-52 | 用户手动停止 |

**根因**: [isOnTaobaoHomePage](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L5930) 的 `isMainAct` 判定列表含 `welcometaobao` 但不含 `welcome.welcome`。淘宝启动 Activity 是 `com.taobao.tao.welcome.welcome`(闪屏页),主页 UI 加载后通常切换到 mainactivity,但有时停留在 welcome。found=5 tabs 全找到说明主页已加载,但 activity 判定否决,导航逻辑按 back 5 次想返回主页,反而退出淘宝。

**修复** ([FarmAccessibilityService.kt#L5954-L5958](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt)):
- `isMainAct` 判定增加 `activity.contains("taobao.tao.welcome")`
- found>=3 tabs 时几乎不可能是其他页面(商品详情页 ttdetailactivity 等不会有完整 5 tab)

**编译验证**: GitHub Actions run #608 (build608-bdc9eef) ✅ success (2026-07-22T13:04:17Z, 仅 2 分钟)
- Release: https://github.com/sicauyanglei/bbncbot/releases/tag/v_29922143570
- APK 下载（3 个）:
  - `bbncbot-bdc9eef.apk` (4.7MB, 主应用 noOcr)
  - `bbncbot-full-bdc9eef.apk` (44MB, 自带 OCR,推荐)
  - `bbncbot-ocr-bdc9eef.apk` (43.7MB, OCR 模块)
- 内嵌 BUILD_LABEL: `build608-bdc9eef`

---

### commit (待提交) - fix: build607 点 App 图标即进农场（不依赖默认桌面）+ systemui 误判 + AI 视觉卡死超时
**用户需求**: "分析日志"

**输入日志**: `logs/debug_test_20260722_075045.log` (build606-3bfc8bc, 66 行, 2026-07-22 07:50 上传)

**日志暴露的 4 个问题**:

| # | 问题 | 行号 | 严重性 |
|---|------|------|--------|
| 1 | build606 核心功能依赖默认桌面,用户荣耀手机未设置 → 按 HOME 进农场未触发 | 5 | 致命(新功能失效) |
| 2 | `pkg=com.android.systemui` 但 Activity 是淘宝 TMSActivity → activeRootPkg 取错窗口 | 18/35/45 | 高(影响平台判断) |
| 3 | 第一次 Automation Started 后 11ms 就 STOPPING → state 异常翻转 | 28-30 | 中(疑似用户快速点了两次) |
| 4 | AI 视觉识别卡死 28 秒后用户手动 STOPPING,无超时保护 | 59-60 | 高(直接卡死) |

**修复 1: 点 App 图标即进农场** ([MainActivity.kt](file:///workspace/app/src/main/java/com/bbncbot/MainActivity.kt))
- 用户选择"不依赖默认桌面,另设计" → 方案: 点 App 图标即进农场
- `isFirstResume` 字段初始化为 true(onCreate 时即触发),去掉 `onNewIntent` 的 CATEGORY_HOME 检测
- `allPermissionsReady()` 去掉 `launcherOk` 检查,只保留悬浮窗 + 无障碍
- `guideNextMissingPermission` 不再强制引导默认桌面(改为可选)
- `openFarmInUcBrowser` 内部优先用 FarmShortcutLauncher(需默认桌面),失败回退 UC 浏览器 deep link(不依赖默认桌面),两条路径都能工作

**修复 2: systemui 误判** ([FarmAccessibilityService.kt](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L454-L485))
- `getCurrentWindowPackage()` 在排除列表中增加 `com.android.systemui`
- 原逻辑: 优先返回非农场包名的窗口,状态栏 systemui 排在 windows 列表前面被误返回
- 修复后: 跳过 systemui,继续找真正的农场 App 窗口

**修复 3: AI 视觉卡死超时** ([AutomationController.kt](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L1046-L1137))
- 加 15 秒超时保护: `aiVisionTimeoutRunnable` + `AtomicBoolean aiVisionCompleted`
- 超时后 fallback 到 `OPENING_TASK_LIST`,不再卡在 COLLECTING_DIRECT
- AI 视觉子线程的所有返回点(截图失败/成功/未找到/异常)都取消超时
- 用 `AtomicBoolean.getAndSet` 保证超时 runnable 和 AI 完成回调只有一个能执行后续逻辑

**未处理(问题 3)**: state 异常翻转(11ms STOPPING)
- 疑似用户快速点了两次"开始施肥"按钮,或 STOPPING 触发逻辑问题
- 日志样本太少(只有 1 次),暂不处理,等更多日志确认

**编译验证**: sandbox 网络限制 gradle wrapper 下载超时,无法本地编译。代码逻辑审查通过,等 CI 构建验证。

**CI 构建结果**: GitHub Actions run #607 (build607-c75efa4) ✅ success (2026-07-22T00:05:39Z, 仅 2 分 34 秒)
- Release: https://github.com/sicauyanglei/bbncbot/releases/tag/v_29879050274
- APK 下载（3 个）:
  - `bbncbot-c75efa4.apk` (4.7MB, 主应用 noOcr,需配合 OCR APK)
  - `bbncbot-full-c75efa4.apk` (44MB, 自带 OCR,推荐单包方案)
  - `bbncbot-ocr-c75efa4.apk` (43.7MB, OCR 模块)
- 内嵌 BUILD_LABEL: `build607-c75efa4`

---

### commit 51e4f57 - feat: 芭芭农场默认从桌面快捷方式进入（按 HOME 一键进农场）
**用户需求**: "芭芭农场默认从桌面快捷方式进入"

**背景**: 本 App 已注册为默认桌面（AndroidManifest 含 CATEGORY_HOME）。用户按 HOME 键回到本应用时,期望直接进入 UC 芭芭农场,无需手动点击"打开 UC 芭芭农场"按钮。

**修改要点**（[MainActivity.kt](file:///workspace/app/src/main/java/com/bbncbot/MainActivity.kt)）:
1. 新增字段 `isFirstResume: Boolean = true`
   - true: 本次 onResume 应触发默认进入农场（权限就绪时调 `openFarmInUcBrowser()`）
   - false: 跳过默认行为,仅展示主界面（让用户配置 Token / 启动悬浮窗等）
2. `onNewIntent()` 检测 `Intent.CATEGORY_HOME`:
   - 用户按 HOME 键回到本应用（默认桌面）时,置 `isFirstResume = true`
   - 程序化拉起（悬浮窗 home 按钮 / 桌面快捷方式 intent）不带 CATEGORY_HOME,不会触发,从而允许用户回到主界面配置
3. `onResume()` 新增默认进入农场分支:
   - 条件: `isFirstResume && allPermissionsReady() && !AutomationController.isRunning`
   - 满足时调 `openFarmInUcBrowser()`（内部优先用 `FarmShortcutLauncher.startFarmShortcut` 从桌面快捷方式进入,失败回退 UC 浏览器打开 deep link）
   - 真正执行后清零 `isFirstResume = false`,避免从农场按返回键回到 MainActivity 时被反复拉起
   - 权限未就绪时不清零,等下次 onResume 权限齐备再触发（确保用户授完权限后能自动进入农场）
4. 新增 `allPermissionsReady()` 私有方法: 检测悬浮窗 + 无障碍 + 默认桌面三项权限齐备
   - 默认进入农场流程要求三项齐备,否则仍走 `guideNextMissingPermission()` 引导

**关键代码片段**（onResume 默认进入分支）:
```kotlin
if (isFirstResume && allPermissionsReady() &&
    !com.bbncbot.automation.AutomationController.isRunning
) {
    isFirstResume = false
    debugLog("onResume: first resume & all permissions ready & automation idle, default to opening UC farm via shortcut")
    openFarmInUcBrowser()
    return
}
```

**关键代码片段**（onNewIntent 检测 HOME 键）:
```kotlin
if (intent?.categories?.contains(Intent.CATEGORY_HOME) == true) {
    isFirstResume = true
    debugLog("onNewIntent: CATEGORY_HOME detected, will default to opening farm on resume")
}
```

**边界处理**:
- 自动化运行中（`AutomationController.isRunning`）不触发默认进入,避免干扰施肥流程
- 待执行平台快捷方式（`pendingPlatform != null`）优先级最高,默认进入农场分支不执行
- 用户从农场按 BACK 返回 MainActivity 时,onNewIntent 不会被调用（BACK 不触发 onNewIntent）,`isFirstResume` 保持 false,不会反复拉起

**编译验证**: commit 51e4f57 已提交（含本修改 + gradle-wrapper.properties 误改,见下条修复）

---

### commit (待提交) - fix: logs/ 目录被误提交到 git 仓库 + 还原 gradle-wrapper 误改
**用户需求**: "继承之前的任务,有个操作文件一直上传到了github" + "有个操作记录文件,你的任务应该延续这个记录文件"

**问题1（核心）**: `logs/` 目录的 352 个 `debug_test_*.log` / `debug_sess_*.log` 文件被提交到了 git 仓库
- 根因: `.gitignore` 只有 `*.log` 规则（git 的 `*.log` 不带前缀斜杠时,会递归匹配所有目录下的 .log 文件,但**只对未跟踪文件生效**）;历史 commit 已把 logs/ 下的文件加入索引后,.gitignore 规则不再对它们生效
- 影响: 每次 LogUploader 通过 GitHub Contents API 上传日志后,本地 logs/ 目录会新增文件,git status 会显示 untracked,容易误提交污染版本历史

**修复1**: [.gitignore](file:///workspace/.gitignore)
- 在 `# Logs` 段下新增 `logs/` 规则（明确忽略整个目录,而非依赖 `*.log` 递归匹配）
- 注释说明: "App 运行时上传的调试日志目录（LogUploader 通过 GitHub Contents API 上传,不应进入版本库,否则每次上传日志都会产生 commit 污染历史）"
- 执行 `git rm -r --cached logs/` 从 git 索引移除 352 个文件（本地保留,只从版本库移除）
- 验证: `git check-ignore -v logs/debug_test.log` 命中 `.gitignore:26:logs/` 规则 ✅

**问题2**: commit 51e4f57 误改了 `gradle/wrapper/gradle-wrapper.properties`
- 当时为尝试本地编译（gradle 下载超时）,临时把 `networkTimeout=10000` 改成 `120000`
- 这个修改不该入库,会改变 CI 构建行为

**修复2**: [gradle/wrapper/gradle-wrapper.properties](file:///workspace/gradle/wrapper/gradle-wrapper.properties) 还原 `networkTimeout=10000`

**问题3（工作流程）**: 没有按 CONVERSATION_LOG.md 的工作流程操作
- 规则要求: "每次会话开始时,先读取本文件了解项目当前状态和历史决策。每次提交代码时同步更新本文件"
- 本轮前两次提交没有先读本文件,也没有提交后更新本文件
- 修复: 本次提交同步更新 CONVERSATION_LOG.md,后续严格遵循工作流程

**待验证**: CI 构建通过后,确认 `logs/` 目录不再出现在 git status 中

**编译验证**: GitHub Actions run #606 (build606-3bfc8bc) ✅ success (2026-07-21T23:04:28Z)
- Release: https://github.com/sicauyanglei/bbncbot/releases/tag/v_29875927387
- APK 下载（3 个）:
  - `bbncbot-3bfc8bc.apk` (4.7MB, 主应用 noOcr,需配合 OCR APK)
  - `bbncbot-full-3bfc8bc.apk` (44MB, 自带 OCR,推荐单包方案)
  - `bbncbot-ocr-3bfc8bc.apk` (43.7MB, OCR 模块,装一次后不变)
- 内嵌 BUILD_LABEL: `build606-3bfc8bc`

---

### commit 5784d81 - fix: build595 修复 UC 推送权限弹窗干扰 + 支付宝搜索框误识别 + 跨平台跳转保守化
**用户需求**: "全部修复"（build594 新日志 debug_test_20260722_023550.log 发现的 3 个问题）
**日志**: debug_test_20260722_023550.log (build594-6f39eea, 02:33-02:35)

**问题1 (核心): UC 推送权限弹窗干扰任务列表打开**
- 02:34:46 弹出 Activity=com.uc.base.push.permission.guide.e 权限授权弹窗
- 弹窗遮住任务列表, checkTaskListOpened 5 次找不到"去完成"按钮
- isOnFarmPage 返回 false (activity 不在 farm keywords)
- 流程回退 NAVIGATING → 重进农场又弹权限弹窗, 死循环失败

**修复1**:
- [FarmAccessibilityService#L1876](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L1876) 新增 `isSystemPermissionPopup()` 识别权限弹窗
  - activity 含 permission + (push|guide|notification) 即识别
  - 文案匹配"开启通知/允许通知/打开通知"等推送授权文案
- [FarmAccessibilityService#L1914](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L1914) 新增 `findSystemPermissionDenyButton()` 查找拒绝按钮
  - 优先级: 拒绝 > 不允许 > 暂不开启 > 暂不 > 以后再说 > 关闭 > 取消
  - 绝不点"允许/开启"避免开启推送权限
- PageScene 枚举新增 SYSTEM_PERMISSION
- [identifyCurrentScene#L1680](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L1680) 前置检测权限弹窗 (最高优先级,在 TRAP_RECHARGE 之前)
- [AutomationController#L1383](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L1383) `checkTaskListOpened` 加权限弹窗处理
- [AutomationController#L766](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L766) `runNavigating` 加权限弹窗处理 (前置在 GENERIC_POPUP 之前)
- [AutomationController#L3908](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L3908) `runWatchingAd` 加权限弹窗处理 (广告播放期间也可能弹出)

**问题2: navigateAlipay 搜索框误识别为芭芭农场入口**
- 02:35:09 找到 bounds=[214,147][1035,254] clickable=true desc='搜索框'
- 原条件 `isSearchBarArea && !isSearchNode` 在 isSearchNode=true 时
  (desc='搜索框'含"搜索") 变成 true && !true = false, 没跳过搜索框

**修复2**: [FarmAccessibilityService#L5489](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L5489)
- `isSearchNode=true` 时直接跳过 (不需要 isSearchBarArea 联合条件)
- 搜索框/搜索按钮绝不是农场入口, 无论位置在哪里都不应该点击

**问题3: 跨平台跳转保守化**
- UC 主页底部"和淘宝,支付宝农场共种一棵树"区域含"去支付宝农场领肥料"横幅
- COLLECTING_DIRECT 第一轮 (attempt=0) 就触发跨平台跳转
- 任务列表还没打开就跳走, UC 任务流程完全无法执行

**修复3**: [AutomationController#L1000](file:///workspace/app/src/main/java/com/bbncbot/automation/AutomationController.kt#L1000)
- `collectDirect` 中只有在 `buttons.isEmpty() && attempt >= 1` 时
  才检查跨平台跳转按钮
- 首次进入优先找 direct 按钮 + 打开任务列表
- 避免主页横幅在第一轮就误触发跳转

**编译验证**: GitHub Actions run #29858309063 (build595-5784d81) ✅ success (2026-07-21T18:43:41Z)

---

### commit 6f39eea - fix: build594 修复 UC "去完成"按钮无 clickable 祖先被全部 drop
**用户需求**: "分析日志"（延续 build593 测试日志 debug_test_20260721_222311.log 分析）
**日志**: debug_test_20260721_222311.log (build593-6e03bc2)

**build593 修复验证**:
- line 22-30: `collectDirect: found 1 direct buttons` → "签到"被找到并点击 ✅
  - `collectDirect: clicking button[0] text='签到' bounds=[894,933][1123,1031]`
  - 点击后变 `text='已领取'` → 签到成功 ✅
- line 23: `findCrossPlatformJumpButton: all 1 nodes have invalid bounds ... skip` ✅ bounds 异常过滤生效

**遗留问题**:
- line 43-57: "签到肥料"(10 个) 和 "去完成"(5 个) 都 `drop non-clickable (no clickable ancestor within 10 levels)`
- build593 已把层数从 5 增到 10, 仍不够 — UC H5 WebView 层级非常深
- 导致 findGoCompleteButtons 返回 0 个按钮 → 反复点"集肥料"重试 → STOPPING

**根因**: UC H5 WebView 无障碍树层级非常深, "去完成"按钮本身不可点击,
向上 10 层仍找不到 clickable 祖先。继续增层数不是好方案（层级可能 20+,
且越向上找祖先 bounds 越大,可能误点相邻区域）。

**修复**: [findGoCompleteButtons#L2254](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L2254)
当向上 10 层找不到 clickable 祖先时, 不直接 drop, 而是检查节点自身 bounds
是否合法 (width>0 且 height>0 且 top<bottom), 若合法则保留节点自身, 让
performClickSafe 用 dispatchGesture 坐标点击 (不依赖 clickable 属性,
与"签到"/"已领取"等主页按钮点击方式一致):

```kotlin
if (!clickTarget.isClickable) {
    val selfRect = android.graphics.Rect()
    node.getBoundsInScreen(selfRect)
    val boundsValid = selfRect.width() > 0 && selfRect.height() > 0 && selfRect.top < selfRect.bottom
    if (boundsValid) {
        debugLog("findGoCompleteButtons: no clickable ancestor for '$buttonText', keep node itself for coordinate click (bounds=${selfRect.toShortString()})")
        // clickTarget 保持为 node 自身
    } else {
        debugLog("findGoCompleteButtons: drop non-clickable node text='$buttonText' (no clickable ancestor within 10 levels and invalid bounds=${selfRect.toShortString()})")
        return@mapNotNull null
    }
}
```

**安全性**: "签到肥料"等装饰性文字会在签到精确过滤分支 (build592) 被 drop,
不会被误点。只有 "去完成" 等真正的任务按钮文案会保留到最终列表。

**附加修改**: 把 `buttonText` 提取移到 clickable 祖先检查之前, 以便
no-clickable-ancestor 日志能引用 buttonText (原代码 buttonText 在 clickable
检查之后才声明, 会编译失败)。

**编译验证**: GitHub Actions run #594 (build594-6f39eea) ✅ success (2026-07-21T14:29:28Z)

---

### commit 6e03bc2 - fix: build593 修复 UC "点击领取"和"签到"不点击 + "去完成"按钮 clickable 祖先查找层数不足
**用户需求**: "uc浏览器到'点击领取'，和'签到'没有点击"
**日志**: debug_test_20260721_213604.log (build591-605226a)

**问题1：UC directCollectTexts 不含"点击领取"和"签到"**
- UC directCollectTexts = ["可领取","挖肥料"],缺"点击领取"和"签到"
- UC 主页的"点击领取"按钮（每日登录奖励/7天奖励）和"签到"按钮（每日签到入口）
  是主页独立按钮,不在任务列表结构内,不会被 OPENING_TASK_LIST 找到
- build535 已在支付宝 directCollectTexts 加"点击领取",UC 同步缺失
- 日志 line 24: `collectDirect: found 0 direct buttons` → "点击领取"被漏掉

**修复1**：[Platform.kt#L208](file:///workspace/app/src/main/java/com/bbncbot/automation/Platform.kt#L208) UC directCollectTexts 加"点击领取"和"签到"
```kotlin
override val directCollectTexts = listOf(
    "可领取", "挖肥料",
    "点击领取", "签到"  // build593 新增
)
```

**问题2：directCollectTexts 加"签到"后会误匹配"签到肥料"等非按钮文字**
- "签到肥料"是装饰性文字（clickable=false）
- "已签到"是已完成状态,"签到有礼"是标题,"每日签到"是标题
- build592 已在 findGoCompleteButtons 加签到精确过滤,findDirectCollectButtons 需同步

**修复2**：[findDirectCollectButtons#L4714](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L4714) filter 加签到精确过滤
```kotlin
!(combined.contains("签到") && combined !in setOf("签到","去签到","立即签到","马上签到","补签到"))
```

**问题3：UC 任务列表"去完成"按钮 clickable 祖先查找层数不足**
- 日志 line 31-35: 5 个"去完成"按钮都 `drop non-clickable (no clickable ancestor)`
- UC H5 WebView 层级深,"去完成"本身不可点击,向上 5 层找不到 clickable 祖先
- 导致 findGoCompleteButtons 返回 0 个按钮 → 反复点"集肥料"重试 → STOPPING

**修复3**：[findGoCompleteButtons#L2262](file:///workspace/app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt#L2262) 向上找 clickable 祖先从 5 层增到 10 层
```kotlin
while (p != null && depth < 10)  // 原 depth < 5
```

**编译验证**: GitHub Actions run #593 (build593-6e03bc2) ✅ success

---

### commit e974b10 - fix: build592 修复 UC 极速版芭芭农场"签到"按钮不点击问题
**用户需求**: "uc极速版芭芭农场，'签到'为什么不点击" + "不是'去签到'，按钮就叫'签到'"

**根因（排查确认）**:
UC goCompleteTexts 只含"去签到",不含纯"签到"。UC 极速版芭芭农场任务列表里的
签到按钮文字就叫"签到"（不是"去签到"），不会被 findGoCompleteButtons 找到，
导致签到任务被漏掉。

**修复（2 文件 2 处）**:

1. **Platform.kt UC goCompleteTexts 加纯"签到"**:
   ```kotlin
   override val goCompleteTexts = listOf(
       "去完成", "立即完成", "去观看", "去领取", "立即观看",
       "去赚钱", "去签到", "去答题", "去逛逛", "签到"  // ← 新增纯"签到"
   )
   ```

2. **FarmAccessibilityService.findGoCompleteButtons 加签到精确过滤**:
   "签到"会误匹配"签到肥料"（装饰性文字 clickable=false）、"已签到"（已完成）、
   "签到有礼"（标题非按钮）、"每日签到"（标题）等非按钮文字。
   当 buttonText 含"签到"时,只接受纯按钮文案：
   ```kotlin
   if (buttonText.contains("签到")) {
       val allowedSignInTexts = setOf("签到", "去签到", "立即签到", "马上签到", "补签到")
       if (buttonText !in allowedSignInTexts) {
           debugLog("findGoCompleteButtons: drop non-button sign-in node text='$buttonText'")
           return@mapNotNull null
       }
   }
   ```

**sortTaskButtonsByPriority** 的 easyClaimKeywords 已含纯"签到"（line 1548），
纯"签到"按钮会被识别为 priority 0 易完成任务，优先处理。

**排查过程（search agent 深度分析）**:
- findDirectCollectButtons 只用 directCollectTexts（UC=["可领取","挖肥料"]），
  "签到肥料"不含这两个关键词，按理不应被匹配
- build580 日志中"签到肥料"被 findDirectCollectButtons 返回 11 个按钮的现象，
  最可能是 H5 页面在 49ms 内异步渲染了新子节点（子节点 text="可领取"），
  collectNodesByText 匹配子节点后 findClickableSelfOrParentInternal 向上找祖先，
  fallback 返回原节点（clickable=false），祖先 text="签到肥料" 进入列表
- build581 已加 chosenIdx 防死循环跳过逻辑，不再连续 5 次点击同一无效按钮
- 这个旧 bug 与当前"签到"按钮不点击问题无关，当前问题是 goCompleteTexts 配置缺失

**编译验证**: GitHub Actions run #592 (build592-e974b10) ✅ success

---

### commit 0d6cc77 - fix: build591 修复 build590 短剧页检测失效（isShortDramaPage/isNovelReadPage 前置到 runNavigating 开头）
**用户需求**: "分析日志"（debug_test_20260721_195055.log, build589-fef7ce2 + debug_test_20260721_210949.log, build590-85dc28e）

**build590 测试日志分析（debug_test_20260721_210949.log）**:

build588/590 修复生效确认：
- line 24: `findCrossPlatformJumpButton: all 2 nodes have invalid bounds ... skip to avoid misclick on ad` ✅ build588 bounds 异常过滤生效
- line 52: `isOnFarmPage: novel/short-drama page detected (isShortDramaPage=true), exclude hasFarmCore` ✅ build590 短剧页识别生效
- line 92: `reopenFarmByDeepLink: opened ... for UC (pkg=com.ucmobile.lite)` ✅ build588 setPackage 生效

build590 遗留问题（build591 修复）：
- line 52-58: 短剧页 isOnFarmPage=false（build590 排除生效），但 navigate 没检测 isShortDramaPage
  → 走 "不在农场页" 分支 → navigateToFarm → 反复 reopenFarmByDeepLink + navigate stepTab 失败 → 死循环
- **根因**: build590 的 isShortDramaPage 检测只加在 navigate 的 generic popup 分支前,
  而短剧页 isGenericPopup 返回 false（"得肥料"匹配 fertilizerKeywords）,
  identifyCurrentScene 返回 UNKNOWN,不会走 generic popup 分支,
  导致 isShortDramaPage 检测永不触发

**修复**: 在 runNavigating 最开头（isOnFarmPage 之前）前置 isShortDramaPage/isNovelReadPage 检测,
若是短剧页/小说页直接进 BROWSING_TASK,不依赖 isOnFarmPage/identifyCurrentScene。

**关键代码片段**（line ~651）:
```kotlin
// build591 修复：在 runNavigating 最开头前置 isShortDramaPage/isNovelReadPage 检测
if (service.isShortDramaPage()) {
    Log.i(TAG, "navigate: short drama page detected at entry (开始观看得肥料), entering BROWSING_TASK")
    browsingShortDramaStarted = false
    taskButtons = emptyList()
    currentTaskIndex = 0
    moveTo(AutomationState.BROWSING_TASK)
    handler.postDelayed({ runBrowsingTask(swipeCount = 1) }, INTERVAL_CLICK_MS)
    return
}
if (service.isNovelReadPage()) {
    // 同处理（小说页也前置检测）
    ...
}
```

**编译验证**: GitHub Actions run #591 (build591-605226a) ✅ success

**build590 日志暴露的其他问题（build592 待修）**:
- line 60-67: navigate stepTab 找到"前往手机支付宝-芭芭农场" bounds=[330,4080][815,2509]（top=4080 > bottom=2509 异常）,
  performClickSafe fallback 到 ancestor 中心 (600.5, 1840.5) → 又落在中国移动广告上
  (line 76: com.greenpoint.android.mc10086.activity)
- 但 build591 修复后短剧页会在 runNavigating 开头被 isShortDramaPage 拦截,不会走到 navigate stepTab,
  所以这个问题在短剧页场景不会出现。其他场景（如农场主页导航）仍可能触发,留待 build592 修复。

---

### commit 85dc28e - feat: build590 新增 UC 短剧任务"开始观看得肥料"处理（点击播放+等待15秒+退出回主页）
**用户需求**: "uc '开始观看得费劲'，如果是短剧，需要点击视频播放15秒，然后退出到uc芭芭农场主页"
（"开始观看得费劲"是"开始观看得肥料"的语音输入误识别）

**设计思路**: 复用小说阅读任务（build584/585）的 BROWSING_TASK 框架,区别是短剧只需点一次"开始观看"
（不像小说要点"开始阅读"+再点一部小说）。视频自动播放,滑动只是模拟活跃避免挂机判定。

**修改要点（2 文件 9 处）**:

1. **FarmAccessibilityService.isOnFarmPage 排除短剧页**:
   - 扩展 `isNovelReadPage` 检测为 `isNovelOrShortDramaPage`,同时覆盖"开始观看"/"继续观看"+"得肥料"的短剧任务页
   - 短剧页排除 hasFarmCore/hasFarmContent（避免误判为农场主页）

2. **FarmAccessibilityService.isBrowseTask 加短剧关键词**:
   - browseKeywords 加"短剧"/"观看"/"看一部"关键词

3. **FarmAccessibilityService 新增方法**:
   - `isShortDramaPage()`: 检测短剧任务页（"开始观看"/"继续观看" + "得肥料",排除农场主页）
   - `findShortDramaPlayButton()`: 找"开始观看"/"继续观看"按钮

4. **AutomationController 新增字段**:
   - `browsingShortDramaStarted`: 已点"开始观看"（在短剧播放页,可以开始等待/滑动）

5. **AutomationController.start() / runBrowsingTask swipeCount==0 复位**:
   - `browsingShortDramaStarted = false`

6. **AutomationController.navigate generic popup 分支前检测短剧页**:
   - 避免短剧页被 isGenericPopup 误判为弹窗反复点关闭按钮（与小说页同处理）

7. **AutomationController.runBrowsingTask 新增短剧任务分支**:
   - 检测 isShortDramaPage → 点"开始观看" → 设置 browseTaskTargetSwipes=8（15秒）
   - 等待 5 秒页面加载后开始滑动（模拟活跃,避免挂机判定）
   - 15 秒后 isTaskCompletePage/isFertilizerGrantedPage 检测到完成 → pressBack 退出回主页

**编译验证**: GitHub Actions run #590 (build590-85dc28e) ✅ success

**关键代码片段 - isShortDramaPage**（line ~2590）:
```kotlin
fun isShortDramaPage(): Boolean {
    val root = rootInActiveWindowSafe() ?: return false
    val allText = collectAllText(root)
    val hasWatchBtn = allText.any { it.contains("开始观看") || it.contains("继续观看") }
    val hasFertilizerHint = allText.any { it.contains("得肥料") || it.contains("肥料") }
    val isFarmHome = allText.any { it.contains("集肥料") || it.contains("施肥") || it.contains("芭芭农场") }
    val isShortDrama = hasWatchBtn && hasFertilizerHint && !isFarmHome
    return isShortDrama
}
```

**关键代码片段 - runBrowsingTask 短剧任务分支**（line ~2180）:
```kotlin
if (!browsingShortDramaStarted && service.isShortDramaPage()) {
    val playBtn = service.findShortDramaPlayButton()
    if (playBtn != null) {
        browsingShortDramaStarted = true
        browseTaskTargetSwipes = 8  // 15秒 / 2秒间隔 = 8 次滑动
        service.performClickSafe(playBtn)
        handler.postDelayed({
            if (state == AutomationState.BROWSING_TASK) runBrowsingTask(swipeCount)
        }, INTERVAL_PAGE_LOAD_MS)
        return
    }
    browsingShortDramaStarted = true
    browseTaskTargetSwipes = 8
}
```

**遗留问题**:
- 短剧播放页的滑动坐标 (600, 1200) ± 250 与小说任务一致,可能误触视频控制区（暂停/进度条）
- 若用户反馈滑动影响视频播放,可改为屏幕顶部 (600, 400) ± 100 滑动或纯等待不滑动
- 短剧"开始观看"按钮也可能有 bounds 异常（如 build588 跨平台跳转按钮 bounds top>bottom）
  若用户反馈短剧页点击无效,可仿照 findCrossPlatformJumpButton 加 bounds 异常过滤

---

### commit fef7ce2 - fix: build588 跨平台跳转按钮误点广告 + UC deep link 被 Chrome 截获 + switchPlatform 失败未恢复 currentPlatform
**用户需求**: "分析日志"（debug_test_20260721_184040.log, build587-1582380, UC 平台 18:20-18:24, 200 行）

**日志分析（3 个严重问题）**:
- **问题1（line 25-33）**: UC 主页"去支付宝农场领肥料"按钮 bounds=[255,3042][694,2509]（top=3042 > bottom=2509 异常），performClickSafe ACTION_CLICK 失败 → dispatchGesture 回退到 ancestor bounds 中心 (600.5, 1840.5) 点击 → 拉起中国移动 APP（`com.greenpoint.android.mc10086.activity`），触发了广告跳转
- **问题2（line 89-194）**: switchPlatform 失败后 `currentPlatform=UNKNOWN` → navigate 用 UNKNOWN 平台 deep link（实际是 UC 的 `https://broccoli.uc.cn/...`）→ 但 `Intent.ACTION_VIEW` 没指定 `setPackage`，HTTPS URL 被 Chrome（`com.android.chrome`）打开，而不是 UC 浏览器（`com.ucmobile.lite`）→ 反复 reopenFarmByDeepLink 始终进不了 UC 芭芭农场
- **问题3（line 69-82）**: switchPlatform 失败分支直接 `moveTo(PROCESSING_TASK)`，没有恢复 `service.currentPlatform` 到 `switchOriginalPlatform`，导致后续 navigate 时 `currentPlatform=UNKNOWN`，`isFarmAppInForeground` 判断错误

**修改要点（3 处修复）**:
- **FarmAccessibilityService.findCrossPlatformJumpButton（问题1）**: 跳过 bounds 异常节点（top >= bottom 或 left >= right 或宽高<=0），避免 performClickSafe 回退到 ancestor 中心点错位置触发广告跳转
- **FarmAccessibilityService.reopenFarmByDeepLink（问题2）**: `intent.setPackage(targetPkg)` 强制用目标平台 App 打开 deep link，避免 HTTPS URL 被 Chrome 截获
- **AutomationController.runSwitchingPlatform（问题3）**: LAUNCH_TARGET/RETURN_ORIGINAL 失败分支恢复 `service.setCurrentPlatform(switchOriginalPlatform)` + `service.launchPlatformApp(switchOriginalPlatform)`，确保失败后能回到原平台继续任务

**编译修复**:
- 第一次提交（c75a423）编译失败：`Cannot assign to 'currentPlatform': the setter is private in 'FarmAccessibilityService'`
- 第二次提交（fef7ce2）：新增 `FarmAccessibilityService.setCurrentPlatform(platform: Platform)` public 方法（属性 setter 是 private，通过此方法暴露受控的外部写入入口），AutomationController 改用 `service.setCurrentPlatform(switchOriginalPlatform)`

**编译验证**: GitHub Actions run #589 (build589-fef7ce2) ✅ success

**关键代码片段 - findCrossPlatformJumpButton bounds 异常过滤**（line ~2582）:
```kotlin
// build588 修复：跳过 bounds 异常节点（top >= bottom 或 left >= right 或宽高<=0），
// 宁可不点击也不要点错位置触发广告跳转。
val valid = result.firstOrNull { node ->
    val r = android.graphics.Rect().also { node.getBoundsInScreen(it) }
    r.width() > 0 && r.height() > 0 && r.top < r.bottom && r.left < r.right
}
if (valid == null) {
    debugLog("findCrossPlatformJumpButton: all ${result.size} nodes have invalid bounds ..., skip to avoid misclick on ad")
    return null
}
```

**关键代码片段 - reopenFarmByDeepLink setPackage**（line ~4833）:
```kotlin
val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)).apply {
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val targetPkg = targetPlatform.config.packageNames.firstOrNull()
    if (targetPkg != null) {
        try { setPackage(targetPkg) } catch (e: Exception) { /* fallback */ }
    }
}
```

**关键代码片段 - switchPlatform 失败恢复 currentPlatform**（line ~4592, 4660）:
```kotlin
debugLog("switchPlatform: restoring currentPlatform to $switchOriginalPlatform and relaunching")
service.setCurrentPlatform(switchOriginalPlatform)
service.launchPlatformApp(switchOriginalPlatform)
currentTaskIndex++
moveTo(AutomationState.PROCESSING_TASK)
```

**遗留问题**:
- UC H5 页"去支付宝农场领肥料"按钮 bounds 持续异常（top > bottom），build588 选择"宁可不点击也不要点错位置触发广告跳转"。如果用户反馈希望即使 bounds 异常也要尝试点击，可考虑：
  - 用 ancestor bounds 的 bottom 区域（按钮文案实际位于 ancestor 底部）作为点击坐标
  - 或调用 AI 视觉识别按钮真实位置
- 后续 build590+ 待用户测试 build589 后反馈新日志

---

### commit 5a91bb6 ~ 1582380 - build582~587 修复（已合并,详情见 git log）
本轮会话前置提交（build582~587）已合并到 main，包括：
- build583: onAccessibilityEvent 无条件覆盖 currentPlatform（淘宝跳转后回不到 UC）
- build584: hasFarmContentLoaded 误判小说页为农场主页（isNovelReadPage 排除 hasFarmCore/hasFarmContent）
- build585: navigate 把小说页当 generic popup（两步进入小说内容页 + 滑动 15 秒）
- build586: 跨平台跳转按钮（"去支付宝农场领肥料"）未处理（findCrossPlatformJumpButton）
- build587: navigateAlipay 搜索框区域误判入口 + 搜索结果点击未验证跳转

---

### commit 00640ac - fix: 广告结束后'恭喜获取奖励'页主动关闭 + collectDirect 防死循环 (build581)
**用户需求**: 分析日志（debug_test_20260721_152904.log, build580-851d3ea, UC 平台）→ 用户明确指出："右上角'恭喜获取奖励'，右侧有个关闭按钮，获得奖励后需要点击关闭退出广告页面"

**日志分析**:
- **问题1（用户反馈核心）**: 15:21 line 178-200 腾讯优量汇 `com.qq.e.ads.PortraitADActivity` 广告结束后页面显示"恭喜获取奖励"+右侧关闭按钮（图像×，无障碍树抓不到 text 节点）。AI 视觉返回 WAIT（误判"页面正在加载或倒计时中"），claim-text-nodes: NONE → 卡在广告 Activity 6 分钟
- **问题2（根因）**: `AD_ACTIVITY_KEYWORDS` 缺少腾讯优量汇 GDT SDK 关键词，PortraitADActivity 不被识别为广告 Activity
- **问题3**: navigate 在广告 Activity 中反复 navigateToFarm → stepTab 找不到"芭芭农场" → 卡 6 分钟
- **问题4**: 15:21 line 44-102 `签到肥料` clickable=false 被 runCollectingDirect 重复点击 5 次（attempt 1-5），每次 performClickSafe fallback gesture 点击 (143.5, 975.5) 无效。findDirectCollectButtons 每次重新返回 11 个 buttons，buttons[0] 永远是签到肥料

**修改要点（4 处修复）**:
- **FarmAccessibilityService.AD_ACTIVITY_KEYWORDS（问题2）**: 新增腾讯优量汇/快手等广告 SDK 关键词
  - `qq.e.ads` / `portraitad` / `landscapead` / `interstitial` / `ksrewardvideo` / `kwad`
- **FarmAccessibilityService.isAdEndedMultiSignal（问题1核心）**: 新增信号4——遍历所有 windows 收集文本，检测"恭喜获取奖励/恭喜获得/奖励已到账/领取成功/已领取奖励/肥料已到账/肥料已发放/获得肥料"等广告结束标志文字
- **AutomationController.navigate（问题3）**: 检测到广告 Activity 时，先检查 `isAdEndedMultiSignal`，若已结束（"恭喜获取奖励"等文字出现）进 CLOSING_AD 主动关闭，而不是无限等待
- **AutomationController.runCollectingDirect（问题4死循环）**: 新增 `lastDirectClickedText`/`lastDirectClickedBounds` 字段，记录上次点击的按钮。若本轮 buttons[0] 与上次相同（页面无变化），跳过 buttons[0] 改用 buttons[1]；若所有按钮都与上次相同，直接进 OPENING_TASK_LIST。start() 中复位标记

**关键代码片段 - isAdEndedMultiSignal 信号4**（line ~1504）:
```kotlin
val allTexts = mutableListOf<List<String>>()
try {
    val allWindows = windows
    for (w in allWindows) {
        val root = w.root ?: continue
        allTexts.add(collectAllText(root))
    }
} catch (e: Exception) { ... }
val adEndedKeywords = listOf(
    "恭喜获取奖励", "恭喜获得奖励", "恭喜获得", "获取奖励",
    "奖励已到账", "奖励已发放", "领取成功", "已领取奖励",
    "肥料已到账", "肥料已发放", "获得肥料"
)
for (texts in allTexts) {
    for (text in texts) {
        if (adEndedKeywords.any { text.contains(it) }) {
            debugLog("isAdEndedMultiSignal: YES (ad ended text detected: '$text')")
            return true
        }
    }
}
```

**关键代码片段 - navigate 广告检测分支**（line ~745）:
```kotlin
if (service.isAdPlaying() || service.isAdActivity()) {
    // build580 修复：广告结束后"恭喜获取奖励"页需要主动关闭
    if (service.isAdEndedMultiSignal(prevAdHadCountdown)) {
        Log.i(TAG, "navigate: ad ended while in ad activity (恭喜获取奖励 etc), entering CLOSING_AD")
        service.setAdMode(true)
        moveTo(AutomationState.CLOSING_AD)
        handler.postDelayed({ runClosingAd(strategy = 0) }, INTERVAL_CLICK_MS)
        return
    }
    // ... 原有逻辑
}
```

**关键代码片段 - runCollectingDirect 防死循环**（line ~887）:
```kotlin
// build581 防死循环：跳过与上次点击相同（text+bounds 一致）的按钮
var chosenIdx = -1
for (i in buttons.indices) {
    val b = buttons[i]
    val bText = b.text?.toString().orEmpty()
    val bBoundsStr = android.graphics.Rect().also { b.getBoundsInScreen(it) }.toShortString()
    if (bText == lastDirectClickedText && bBoundsStr == lastDirectClickedBounds) {
        debugLog("collectDirect: skip button[$i] text='$bText' bounds=$bBoundsStr (same as last clicked)")
        continue
    }
    chosenIdx = i
    break
}
if (chosenIdx < 0) {
    // 所有按钮都和上次点击相同（页面无任何变化），放弃 direct 阶段
    moveTo(AutomationState.OPENING_TASK_LIST)
    handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
    return
}
val button = buttons[chosenIdx]
// ... 记录本次点击
lastDirectClickedText = btnText
lastDirectClickedBounds = btnBoundsStr
service.performClickSafe(button)
```

**待验证（build581 测试）**:
- 腾讯优量汇 PortraitADActivity 广告结束后"恭喜获取奖励"页是否能被 isAdEndedMultiSignal 信号4 检测到并主动关闭
- CLOSING_AD 策略链（策略0 findAdCloseButton → 策略1 坐标候选点击右上角 → 策略2 放弃奖励 → 策略3 pressBack → 策略4 领取奖励）是否能成功关闭广告页
- collectDirect 签到肥料死循环是否被防死循环逻辑跳过

### commit (待提交) - fix: UC 激励视频广告页 WATCHING_AD 点击商品 + collectDirect 死循环 + scene=AD_ENDED 误判 + AI 误点广告位 + reopenFarmByDeepLink 误杀农场 App
**用户需求**: 分析日志（build579 UC 平台 085502 + 133522）→ 修复所有问题

**日志分析**:
- **debug_test_20260721_085502.log (build579, UC)**:
  1. **问题2（核心）**: 08:53:47 UC 集肥料点击"去完成"→ 弹激励视频广告（HCRewardVideoActivity），顶部出现"点击商品，领取奖励"提示 bounds=[628,59][1033,111] clickable=false。原代码只在 runNavigating 和 rewardJumpClicked（ALIPAY）分支检测"点击商品"，UC processTask → WATCHING_AD 流程不触发 → 广告页卡 30s 直至用户手动停止
  2. **问题1**: 08:52:59-08:53:37 collectDirect AI 视觉返回坐标 (0.8,0.45)→(960,1144) 点击，但实际签到按钮在 (143,975)，AI 坐标偏差。随后 tryClaimDirectPopup 找到 '签到肥料'（clickable=false H5 Canvas 文字标签），performClickSafe fallback gesture 点击 (143.5,975.5) 无效，重复 6 次死循环 38 秒
  3. **问题3**: 08:53:50 watchAd scene=AD_ENDED 误判（elapsed=0ms 广告刚开始）。根因：isTaskCompletePage 用 getRootInFarmApp 遍历所有窗口，UC 广告 Activity 覆盖时农场 H5 后台窗口仍在 windows 列表，残留"已完成"文字被误判 → identifyCurrentScene 返回 AD_ENDED
- **debug_test_20260721_133522.log (build579, UC)**:
  4. **问题4（新）**: 13:34:29 AI 视觉返回 CLICK_CLAIM (0.8,0.6)→(960,1525) 点击屏幕右侧中部，点到了 UC 农场主页右侧的"领水果"广告位，11 秒后跳转到第三方 App `com.ss.android.article.lite`（抖音/头条 lite）。tryClaimDirectPopup 期间没检测第三方 App 跳转，3 次重试耗尽后进 OPENING_TASK_LIST，再检测到 overlay 才处理
  5. **问题5（新）**: 13:34:42 forceKillApp(第三方 App) 后，下一轮 runNavigating 检测到 !isFarmAppInForeground()（UC 还没回前台），触发 line 809 调 reopenFarmByDeepLink，它内部 HOME + kill UC + reopen deep link。但 UC 被杀后 deep link 启动停在启动页/首页，没进农场页，导致 UC 一直停在 launcher，navigate 超时停止

**修改要点**:
- **AutomationController.runWatchingAd（问题2核心修复）**: 在 rewardJumpClicked 分支之后、fasterRewardStage 分支之前，新增通用"点击商品"检测（对所有平台生效）：
  - 检测 `isClickProductAd()` → 调 `findAdProductNode()` 找可点击商品 → `performClickSafe` 点击 → 等 2s → 继续轮询
  - 用新标志位 `watchingAdProductClicked` 避免重复点击（与 `adProductClicked`/`rewardJumpProductClicked` 独立，避免状态冲突）
  - 进入 WATCHING_AD 时（elapsedMs==0L）重置 `watchingAdProductClicked = false`
- **AutomationController.tryClaimDirectPopup（问题1死循环 + 问题4第三方 App 跳转检测）**:
  - 开头检测 `getThirdPartyOverlayPkg()`：若检测到第三方 App（说明 AI 误点广告位拉起第三方），kill 第三方 App + `launchPlatformApp` 激活农场 + 直接进 OPENING_TASK_LIST（不再用 AI 策略，避免再次点错）
  - 记录上次点击的 claimBtn 的 text+bounds，若新一轮找到完全相同节点（text+bounds 一样），说明点击无效（页面没变化），放弃重试直接进 OPENING_TASK_LIST
- **FarmAccessibilityService.isTaskCompletePage（问题3误判修复）**: 开头加 `if (isAdActivity()) return false`，广告 Activity 活跃时不判为任务完成页（避免后台农场 H5 窗口残留文字误判）
- **FarmAccessibilityService.reopenFarmByDeepLink（问题5误杀农场 App 修复）**: 在 HOME+kill+reopen 之前加检查：如果当前活跃窗口已经是目标农场 App（说明农场 App 已在前台，只是不在农场 H5 页），跳过 kill+reopen，直接返回 true，让 navigate 流程通过 navigateToFarm 处理。避免误杀已在前台的农场 App 导致 deep link 启动后停在首页

**关键代码片段 - runWatchingAd 通用"点击商品"检测**（line ~3742）:
```kotlin
if (!watchingAdProductClicked && service.isClickProductAd()) {
    val productNode = service.findAdProductNode()
    if (productNode != null) {
        val rect = android.graphics.Rect()
        productNode.getBoundsInScreen(rect)
        service.performClickSafe(productNode)
        watchingAdProductClicked = true
        handler.postDelayed({
            if (state == AutomationState.WATCHING_AD) runWatchingAd(elapsedMs + 2000L)
        }, 2000L)
        return
    }
}
```

**关键代码片段 - tryClaimDirectPopup 第三方 App 跳转检测 + 防死循环**（line ~1012）:
```kotlin
fun attemptClaim() {
    if (state != AutomationState.COLLECTING_DIRECT) return
    // 检测第三方 App 跳转（AI 误点广告位）
    val overlayPkg = service.getThirdPartyOverlayPkg()
    if (overlayPkg != null) {
        service.forceKillApp(overlayPkg, pressBackFirst = false)
        if (service.currentPlatform != Platform.UNKNOWN) {
            service.launchPlatformApp(service.currentPlatform)
        }
        moveTo(AutomationState.OPENING_TASK_LIST)
        handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_PAGE_LOAD_MS)
        return
    }
    val claimBtn = service.findClaimRewardButtonExact()
    if (claimBtn != null) {
        val btnText = claimBtn.text?.toString().orEmpty()
        val btnBoundsStr = android.graphics.Rect().also { claimBtn.getBoundsInScreen(it) }.toShortString()
        // 防死循环：若与上次点击的节点完全相同,放弃重试
        if (lastClickedText == btnText && lastClickedBounds == btnBoundsStr) {
            moveTo(AutomationState.OPENING_TASK_LIST)
            handler.postDelayed({ runOpeningTaskList(attempt = 0) }, INTERVAL_CLICK_MS)
            return
        }
        lastClickedText = btnText
        lastClickedBounds = btnBoundsStr
        service.performClickSafe(claimBtn)
        // ...
    }
}
```

**关键代码片段 - isTaskCompletePage 广告 Activity 短路**（line ~3712）:
```kotlin
fun isTaskCompletePage(): Boolean {
    if (isAdActivity()) {
        return false
    }
    val root = getRootInFarmApp() ?: return false
    // ...
}
```

**关键代码片段 - reopenFarmByDeepLink 农场 App 已在前台时跳过 kill+reopen**（line ~4743）:
```kotlin
val activeRootPkg = rootInActiveWindowSafe()?.packageName?.toString().orEmpty()
val isFarmAppAlreadyInForeground = activeRootPkg.isNotEmpty() &&
    targetPlatform.config.packageNames.any { activeRootPkg == it || activeRootPkg.startsWith("${it}.") }
if (isFarmAppAlreadyInForeground) {
    debugLog("reopenFarmByDeepLink: $targetPlatform (pkg=$activeRootPkg) already in foreground, skip kill+reopen, let navigateToFarm handle farm page navigation")
    if (targetPlatform != currentPlatform) {
        currentPlatform = Platform.UNKNOWN
    }
    return true
}
```

### commit (待提交) - fix: UC 激励视频广告页"点击商品,领取奖励"检测点击商品（runNavigating 广告分支加 isClickProductAd 检测）
**用户需求**: 右上角有个"点击商品,领取奖励",页面应该还是在uc浏览器,可以点击商品；分析日志,uc芭芭农场"签到","点击领取",没有去点击

**日志分析**（debug_test_20260719_153945.log, build555, UC 平台 line 113-119）:
1. 15:35:49 NAVIGATING 状态进入 UC 激励视频广告页（HCRewardVideoActivity）
2. claim-text-nodes 显示"点击商品，领取奖励" text bounds=[628,59][1033,111] clickable=false
3. 页面有淘宝商品信息（"盼盼家庭号薯片虾条 ¥19.69"）— UC 激励视频里的商品广告,不跳转淘宝
4. 原逻辑只在 adActivity=true 时 pressBack/等待,不检测"点击商品"提示 → 商品没被点击,拿不到额外奖励

**UC 芭芭农场"签到"/"点击领取"问题**：
- 144835.log line 768-771: UC COLLECTING_DIRECT 阶段 claim-text-nodes 只有 2 个"去支付宝农场领肥料"
- 完全没有"签到"/"点击领取"文本节点 → 证实是 H5/Canvas 绘制的图像按钮,无障碍树抓不到
- build565 已加 AI 视觉兜底（runCollectingDirect attempt==0 && buttons.isEmpty() 时调 AI 视觉识别截图）
- 这份日志是 build555,没有 AI 视觉兜底,装 build566 后应能触发,不需要额外改代码

**修改要点**:
- AutomationController.runNavigating 广告分支（isAdPlaying || isAdActivity）头部新增"点击商品"检测:
  1. 检测 isClickProductAd() — 页面文本是否含"点击商品"
  2. 若检测到,调 findAdProductNode() 找可点击商品卡片（已排除陷阱按钮和关闭按钮,只点击屏幕中部 y 500~2400 的商品）
  3. 找到则 performClickSafe 点击商品 → 等待 INTERVAL_CLICK_MS → 下一轮 runNavigating
  4. 找不到商品节点或未检测到"点击商品"提示 → 走原 pressBack/等待逻辑
- 复用 findAdProductNode（原为 reward-jump 跳转淘宝后点击商品设计）,适配 UC 激励视频页内商品点击场景

### commit 9299edb - fix: 修复流水线编译错误 - buttonText 提前到 clickable 块之前定义
**用户需求**: 分析日志（支付宝/UC 平台 build555 日志）

**日志分析**（debug_test_20260719_144835.log, build555-41e3bbc, UC 平台 line 1075-1098）:
1. 14:48:10 UC 任务列表已打开（有 10 个"去完成"按钮节点）
2. 但 10 个"去完成"按钮全是 `clickable=false` 且无 clickable 祖先（H5 JS 绑定点击事件,无障碍树无 clickable 属性）
3. findGoCompleteButtons line 2227-2230 直接丢弃 → taskButtons 为空
4. checkTaskListOpened 反复 5 次找到 0 个 goComplete buttons → openTaskList 重试失败
5. 14:48:26 state: OPENING_TASK_LIST -> NAVIGATING → STOPPING

**修改要点**:
- FarmAccessibilityService.findGoCompleteButtons clickable=false 节点处理修复:
  - 原逻辑：clickable=false 且无 clickable 祖先 → 直接丢弃
  - 新逻辑：校验 node 本身 bounds 合法性（width>0, height>0, top<bottom, top in 0..2800）
    - bounds 合法：保留 node 本身（processTask 调 performClickSafe 时 ACTION_CLICK 失败 → fallback dispatchGestureClickWithWebViewFix 按坐标点击）
    - bounds 无效：丢弃（避免保留完全无效的节点）
  - 适用场景：UC/支付宝/淘宝 H5 虚拟列表,JS 绑定点击事件,无障碍树无 clickable 属性

**问题 2（UC NAVIGATING 激励视频 STOPPING）经核查非 bug**：
- 日志 line 1100-1105 显示 14:48:26 进入 NAVIGATING,14:48:28 就 STOPPING,仅 2 秒
- 这是用户手动点停止按钮,不是代码 bug,无需修复

### commit 9d15cb2 - fix: 修复流水线编译错误 - AutomationController.kt:817-818 Unresolved reference: currentPlatform
**用户需求**: 流水线出错了

**错误**: commit 7b523b6（三平台逻辑隔离）在 navigate 第三方 overlay 分支新增 launchPlatformApp 调用,误用了 currentPlatform 而非 service.currentPlatform,导致 Kotlin 编译失败:
- e: AutomationController.kt:817:21 Unresolved reference: currentPlatform
- e: AutomationController.kt:818:47 Unresolved reference: currentPlatform

**修复**: currentPlatform 改为 service.currentPlatform。流水线 #576 成功。

### commit 7b523b6 - refactor: 三平台逻辑隔离（UC/支付宝/淘宝 独立配置 + reward-jump/pressBack/claimButton 门控）
**用户需求**: 三个平台都执行逻辑需要区分开来,不要修改其中一个平台的逻辑影响到其它平台

**调研结论**（search agent 全量梳理）:
- Platform.kt 配置层:除 farmDeepLink（UC 特有非 null）和 supportsFasterReward（UC 特有 true）外,所有字段三平台都填,差异化通过字段值不同实现
- AutomationController.kt:仅 2 处真正的平台分支影响执行逻辑——runNavigating 行 748 的 Platform.UC 硬编码 + runWatchingAd 的 supportsFasterReward 配置门控
- FarmAccessibilityService.kt:仅 1 处显式平台分支——navigateToFarm 行 4822 的 when(platform)
- findClaimRewardButton/findClaimRewardButtonExact 的 keywords 硬编码 14 个关键词三平台共用,但只有 ALIPAY 的 directCollectTexts 配了"拿奖励"系列 → UC/淘宝广告页若出现"拿奖励"文案会误触发 reward-jump 流程

**修改要点**:
1. PlatformConfig 接口新增 4 个字段,三平台独立配置:
   - `supportsRewardJump: Boolean` — UC=false/ALIPAY=true/TAOBAO=false
   - `adPressBackEnabled: Boolean` — UC=false/ALIPAY=true/TAOBAO=true
   - `claimRewardButtonTexts: List<String>` — UC/TAOBAO 基础领取关键词,ALIPAY 含"拿奖励/跳转拿"系列
   - `claimRewardButtonExactTexts: List<String>` — 同上,加上"立即领取"放最前
2. FarmAccessibilityService.findClaimRewardButton/findClaimRewardButtonExact:
   - keywords 从硬编码 14 个改为读 `currentPlatformConfig().claimRewardButtonTexts/claimRewardButtonExactTexts`
3. AutomationController reward-jump 流程加 supportsRewardJump 门控:
   - executeAiVisionAction CLICK_CLAIM 分支:`if (supportsRewardJump && isRewardJumpButtonText(claimText))` 才设置 rewardJumpClicked=true
   - AI 视觉兜底分支:`else if (supportsRewardJump && targetX >= 0f && targetY >= 0f)` 才走 reward-jump 流程
   - 新增 else if 分支:UC/TAOBAO 的 AI 坐标点击直接 dispatchGestureClick,不设置 rewardJumpClicked
   - runWatchingAd `if (rewardJumpClicked)` 块加注释说明 UC/TAOBAO 不会进入
4. AutomationController.runNavigating 行 748 的 Platform.UC 硬编码改为 `!currentPlatformConfig().adPressBackEnabled`:
   - UC=false → 不 pressBack,等广告自然结束（激励视频 pressBack 无效）
   - ALIPAY=true、TAOBAO=true → pressBack 尝试关闭 H5 广告

### commit 5f3a66b - fix: 跳转第三方 App 后激活农场到前台 + kill 跳转 App（4 处统一顺序 + deep link setPackage + forceKillApp HOME 兜底）
**用户需求**: 跳到另外一个app,能在完成任务后把跳转前的app激活到前台窗口,然后kill掉跳转到的app

**日志分析**（debug_test_20260719_163645.log, build561-e4467db, UC 平台）:
1. UC 点击"集肥料"被劫持到 `com.greenpoint.android.mc10086.activity`（移动 10086 充值页）
2. `forceKillApp(10086, pressBackFirst=false)` 直接调 `killBackgroundProcesses` → 10086 在前台 kill 不掉
3. `reopenFarmByDeepLink` 打开 `https://broccoli.uc.cn/...` 未 `setPackage`,被 Chrome 拦截
4. `navigate stepTab` 在 Chrome/桌面反复找"芭芭农场"6+5 次失败 → STOPPING → IDLE

**修改要点**:
1. `FarmAccessibilityService.reopenFarmByDeepLink` deep link Intent 加 `setPackage(农场 App 主包名)`:
   - 强制用农场 App 打开 https deep link,避免被 Chrome 等其他浏览器拦截
   - 农场 App 未安装时 ActivityNotFoundException,catch 后回退到启动 App 主 Activity
2. `FarmAccessibilityService.forceKillApp` 内部新增 `performGlobalAction(GLOBAL_ACTION_HOME)`:
   - kill 前先按 HOME 把目标 App 推到后台,再调 `killBackgroundProcesses`（只能 kill 后台进程）
   - 解决 10086 在前台 kill 不掉的问题
3. `AutomationController` 4 处 `launchPlatformApp + forceKillApp` 调用顺序统一调整为"先 kill → 再激活":
   - reward-jump 满停留时长分支（runWatchingAd）
   - faster-reward 异常页分支（isOnAbnormalPage/isRechargePage）
   - faster-reward 16s 满分支
   - deep-link 2s 满分支
   - navigate 第三方 overlay 分支（新增 launchPlatformApp 激活农场）
   - 原顺序问题：先 launchPlatformApp 激活农场 → forceKillApp HOME 把农场也推到后台
   - 新顺序：先 forceKillApp（HOME 推第三方到后台 + kill）→ 再 launchPlatformApp 激活农场到前台

### commit 5fa74a1 - fix: 支付宝滑动浏览任务不滑动（isBrowseTask 关键词 + H5 虚拟列表 bounds 过滤）
**用户需求**: 支付宝芭芭农场,滑动浏览任务,怎么不滑动了

**日志分析**（debug_test_20260719_144835.log, build555-41e3bbc, 支付宝平台）:
1. 支付宝任务列表是 H5 虚拟列表,未滚动到的列表项 bounds 出现 `top > bottom` 倒置
   （如 `[884,2823][1113,2666]`,bottom 固定为 WebView 高度 2666）
2. `findGoCompleteButtons` 的 `rect.width()<=0 || rect.height()<=0` 过滤把这些按钮当 zero-size 丢弃
   → 任务列表只剩顶部 4 个可见按钮,"去逛逛"等被丢弃的浏览任务按钮永远无法被 processTask 处理
3. 4 个可见按钮里 `#3` 是"【福利】试玩热门新游 访问必得500 - 3500肥"任务,
   `isBrowseTask` 关键词不含"试玩/访问必得" → 返回 false → 走普通点击流程
   → 点"去完成"进入新游页 → AI 视觉 CLICK_CLOSE → 永远拿不到肥料

**修改要点**:
1. `FarmAccessibilityService.isBrowseTask` browseKeywords 新增"访问必得/试玩热门/试玩新游"关键词
   - "访问必得":精确匹配"访问必得500肥"等访问类任务文案
   - "试玩热门"/"试玩新游":匹配"试玩热门新游"任务（访问试玩类,非真玩游戏）
   - 注:纯"试玩游戏"类任务通常需点击进入游戏,不应走浏览流程,故用更精确的"试玩热门/试玩新游"
2. `FarmAccessibilityService.findGoCompleteButtons` bounds 过滤逻辑修复:
   - 对于 `width > 0 但 top > bottom`（H5 虚拟列表倒置矩形）,不直接丢弃
   - 尝试 `getBoundsInWindow` 修正:window bounds 合法则保留,无效也保留
   - 保留的节点由 `dispatchGestureClickWithWebViewFix` 在点击时修正坐标
     （该方法已有 ancestor bounds 兜底逻辑处理虚拟列表项）

### commit 62b39ec - docs: CONVERSATION_LOG.md 追加本次创建并上传记录
**用户需求**: 每次提交代码的时候,把我们所有的对话记录到 md 文件中上传,每次分析的时候都从这个 md 文件继续工作

**修改要点**:
1. 新建 `/workspace/CONVERSATION_LOG.md` 文件,内容包括:
   - 项目概述（GitHub 地址/构建配置/分支信息）
   - 核心架构（状态机阶段/关键文件路径）
   - 本轮会话修改历史（7 个 commit 的用户需求 + 修改要点）
   - 8 项关键技术决策（AI 视觉接口/reward-jump 流程/UC 点击商品广告/UC 更快拿奖/H5 虚拟列表 bounds/系统包黑名单等）
   - 日志分析常用路径
   - 下次会话工作指引
2. `git push --force` 推送 `trae/agent-qxjDbm:main` 到远端 main 分支
3. **使用规则**: 每次会话开始先读本文件,每次提交代码时同步更新本文件

### commit 1fdeffc - feat: reward-jump 跳转第三方 App 后检测并点击"点击商品,领取奖励"
**用户需求**: "点击跳转拿奖励"/"我要直接拿奖励"进入到淘宝 App 右上方有"点击商品,领取奖励"文字提示,需要点击个商品,然后等待之前设定的时长 10秒/15秒等,再切换回到 UC 芭芭农场,然后 kill 淘宝 App

**修改要点**:
1. 新增字段 `rewardJumpProductClicked: Boolean` 跟踪是否已点击商品
2. `runWatchingAd` 的 reward-jump 等待分支新增商品点击逻辑:
   - 等待期间检测 `isClickProductAd()` 且 `!rewardJumpProductClicked` 时调 `findAdProductNode()`
   - 找到则 `performClickSafe` 点击商品,设置 `rewardJumpProductClicked=true`,等 2s 让商品详情页加载
3. 6 处重置点同步重置 `rewardJumpProductClicked`: openTaskList/checkTaskListOpened/executeAiVisionAction 2 处 CLICK_CLAIM/runWatchingAd 3 处重置块
4. 复用现有 `isClickProductAd()` 和 `findAdProductNode()` 方法（原本用于 UC 集肥料激励视频广告）

### commit 787057c - fix: COLLECTING_DIRECT 阶段加 AI 视觉兜底识别图像类型签到/领取按钮
**用户需求**: uc芭芭农场没有优先点击"点击领取","签到"完成任务

**日志分析**: UC 芭芭农场主页 COLLECTING_DIRECT 阶段 claim-text-nodes 只有"去支付宝农场领肥料"/"领肥料",根本没出现"签到"/"立即领取"/"点击领取"按钮 → 印证按钮是 H5/Canvas 绘制的图像

**修改要点**:
1. `runCollectingDirect` 的 `buttons.isEmpty()` 分支,attempt==0 时调 AI 视觉:
   - 子线程调 `AiVisionClient.analyzeScreenshot`
   - AI 返回 CLICK_CLAIM + 有效坐标 → `dispatchGestureClick` 按坐标点击图像按钮 → 复用 `tryClaimDirectPopup` 领取弹窗奖励
   - AI 返回其他动作或无坐标 → 正常进 OPENING_TASK_LIST
2. `AiVisionClient.buildPrompt` 的 CLICK_CLAIM 描述和优先级 1 判断条件新增"签到/点击领取/立即领取"关键词

### commit 15d7371 - feat: AI 视觉返回按钮坐标,无障碍树找不到时按坐标点击图像按钮
**用户需求**: "点击跳转拿奖励"/"我要直接拿奖励"可能是图像类型文本（H5/Canvas 绘制）,无障碍树抓不到

**修改要点**:
1. `AiVisionClient.VisionResult` 新增 `targetX`/`targetY` 字段（0-1 归一化比例）
2. `parseAction` 解析 AI 返回的 `target:{x,y}` 或简写 `x/y`,范围校验 0-1
3. `buildPrompt` 提示词新增 target 字段说明:
   - CLICK_CLOSE/CLICK_CLAIM 必须返回按钮位置（0-1 归一化）
   - 注明部分页面是图像类型按钮必须靠 target 坐标点击
   - PRESS_BACK/SKIP_TASK/WAIT 可省略 target
4. `FarmAccessibilityService` 新增 `screenMetrics` 只读属性封装 `resources.displayMetrics`
5. `executeAiVisionAction` 新增 `targetX/targetY` 参数:
   - CLICK_CLAIM: `findClaimRewardButton` 找不到时,若 AI 返回有效坐标,按坐标点击 + 统一按 reward-jump 流程处理
   - CLICK_CLOSE: `findAdCloseButton` 找不到时同样按 AI 坐标点击

### commit f98a2e1 - fix: "点击跳转拿奖励"与"我要直接拿奖励"统一识别为跳转奖励任务
**用户需求**: "点击跳转拿奖励"与"我要直接拿奖励"都是一类的跳转奖励任务

**修改要点**: 在 4 个文件统一扩展"跳转拿"关键词:
1. `AutomationController.isRewardJumpButtonText` 新增 `text.contains("跳转拿")`
2. `FarmAccessibilityService.findClaimRewardButton` 关键词新增"点击跳转拿奖励/跳转拿奖励/拿奖励/跳转拿"
3. `FarmAccessibilityService.findClaimRewardButtonExact` 同步扩展
4. `FarmAccessibilityService.collectClaimTextNodesForDiag` 新增"跳转拿"关键词
5. `Platform.kt` ALIPAY directCollectTexts 新增"点击跳转拿奖励/跳转拿奖励/跳转拿"
6. `AiVisionClient.buildPrompt` 提示词新增"点击跳转拿奖励/跳转拿奖励"

### commit 4a5402e - feat: UC 主页"签到"/"立即领取"按钮优先点击完成
**用户需求**: uc极速版芭芭农场,签到,立即领取,这些可以优先点击完成

**修改要点**:
1. UC `directCollectTexts` 新增"立即领取"/"签到"关键词（之前只有"可领取"/"挖肥料"）
2. `findDirectCollectButtons` 过滤逻辑新增 `!contains("已签到")` 排除（避免签到日历里"已签到"格子的锁定状态文本被误匹配）
3. 注：任务列表里的"去签到"/"立即领取"任务按钮已由 `sortTaskButtonsByPriority` 排到 priority 0,`isPureClaimClick` 已包含"立即领取",无需重复处理

### commit d8eafdf - refactor: "我要直接拿奖励"改用切农场 App + kill 跳转 App 方式
**用户需求**: 上面一般有x秒之后拿奖励,x可能是15秒,20秒,25秒,30秒等,具体看弹窗显示值;如果广告是在新的app里面,我们能不能先把当前窗口切到芭芭农场的app后,然后kill掉之前切换的app

**修改要点**:
1. `FarmAccessibilityService` 新增 `findRewardJumpDurationHint()` 方法,解析弹窗"x秒之后拿奖励"/"x秒后可领取奖励"等文本（必须同时含"拿奖励/领取奖励/拿肥料/领取肥料"关键词 + "x秒/xs"数字）
2. `AutomationController` 用 `rewardJumpStayMs`/`rewardJumpAppPkg` 替换原来的 `rewardJumpPressBackAttempts`
3. `executeAiVisionAction` CLICK_CLAIM: 检测到跳转按钮时先解析弹窗时长再设置 `rewardJumpClicked`
4. `runWatchingAd` reward-jump 块改为:
   - 仍在第三方 App 且 < 停留时长: 继续等待,首次进入时记录包名到 `rewardJumpAppPkg`
   - 仍在第三方 App 且 ≥ 停留时长: `launchPlatformApp` 切回芭芭农场 + `forceKillApp` kill 跳转的 App
   - 已回到农场: 直接重置 + 任务前进 + OPENING_TASK_LIST

### commit c575b5c - feat: 实现"我要直接拿奖励"15s 跳转奖励任务流程
**用户需求**: 这个我要直接拿奖励,需要跳转15秒后,回到跳转前页面菜可以获得肥料

**修改要点**:
1. 新增字段 `rewardJumpClicked`/`rewardJumpClickTimeMs`/`rewardJumpStayMs`/`rewardJumpAppPkg`
2. 新增 `REWARD_JUMP_STAY_MS = 15000L` 常量（默认停留时长）
3. 新增 `isRewardJumpButtonText(text)` 方法判断按钮文案是否为跳转奖励按钮（含"拿奖励/直接拿/立即拿/马上拿"）
4. `executeAiVisionAction` CLICK_CLAIM 分支: 检测到跳转按钮时设置 `rewardJumpClicked=true` + 时间戳 + 停留时长
5. `runWatchingAd` 新增 reward-jump 块处理跳转奖励任务
6. 深链 kill 检测条件新增 `!rewardJumpClicked` 排除

---

## 关键技术决策

### 1. AI 视觉接口（GLM-4.6V-Flash）
- **何时调用**: `processTask` UNKNOWN 页面兜底 + `runCollectingDirect` buttons 为空时兜底
- **返回格式**: `{action, reason, target:{x,y}}` - target 是 0-1 归一化坐标
- **5 个预定义动作**: CLICK_CLOSE / CLICK_CLAIM / PRESS_BACK / SKIP_TASK / WAIT
- **fallback**: glm-4.6v-flash → glm-4v-flash,429 限流时退避重试 2 次（5s+10s）
- **图像按钮**: 无障碍树抓不到 H5/Canvas 绘制的按钮文本时,靠 AI 返回的 target 坐标 dispatchGestureClick 点击

### 2. "我要直接拿奖励"/"点击跳转拿奖励"跳转奖励任务流程
- **检测**: AI 视觉识别 CLICK_CLAIM + 按钮文案含"拿奖励/跳转拿/直接拿/立即拿/马上拿"
- **解析时长**: `findRewardJumpDurationHint()` 解析弹窗"x秒之后拿奖励"（必须含奖励关键词 + 秒数）
- **跳转后处理**:
  - 检测 `isClickProductAd()` → `findAdProductNode()` 点击商品（淘宝"点击商品,领取奖励"页面）
  - 等待 `rewardJumpStayMs` 毫秒
  - `launchPlatformApp` 切回农场 + `forceKillApp` kill 跳转的 App
- **状态字段**: `rewardJumpClicked`/`rewardJumpClickTimeMs`/`rewardJumpStayMs`/`rewardJumpAppPkg`/`rewardJumpProductClicked`

### 3. UC 集肥料激励视频广告"点击商品,领取奖励"流程
- **复用方法**: `isClickProductAd()` + `findAdProductNode()`
- **状态字段**: `adProductClicked`/`adProductClickTimeMs`
- **流程**: 检测 → 点击商品 → 等 5s → 关闭广告
- **状态机**: `checkTaskListOpened` 检测 `isClickProductAd()` 时切换到 WATCHING_AD

### 4. UC "我要更快拿奖"流程（UC 特有,`supportsFasterReward=true`）
- **状态机**: 0=待检测入口按钮 / 1=已点入口等待确认弹窗 / 2=已点允许新 app 打开停留 16s / 3=已关闭新 app 等待奖励提升窗口 / 4=已完成
- **状态字段**: `fasterRewardStage`/`fasterRewardAppPkg`/`fasterRewardAppEnterTimeMs`

### 5. H5 虚拟列表零尺寸 bounds
- 现象: H5 懒加载时节点 bounds 出现 `top>bottom` 异常（如 `[884,3271][1113,2666]`）
- 修复: `findGoCompleteButtons` bounds 过滤从 `width<=0 || height<=0` 改为只 `width<=0`

### 6. `getCurrentWindowPackage()` 系统包黑名单
- 排除 systemui/launcher/IME/android/bbncbot,避免系统 UI 被误判为前台 App

### 7. `getRootInFarmApp()` 兜底
- FERTILIZING 任务列表弹窗检测时,`getRootInFarmApp()` 返回 null 时 fallback 到 `service.rootInActiveWindowSafe()`

### 8. navigateAlipay 搜索框死循环修复
- retry>=2 时 fallback 到 `reopenFarmByDeepLink()`,避免在搜索框死循环

---

## 日志分析常用路径

- **日志目录**: `/workspace/logs/debug_test_*.log`
- **关键字段**: `state` / `claim-text-nodes` / `snapshot` / `processTask` / `watchAd` / `collectDirect`
- **AI 视觉日志**: `processTask: AI vision action=...` / `callVisionModel(...) success:`
- **reward-jump 日志**: `watchAd: reward-jump ...`

---

## 待办与遗留

- 无具体待办,等待用户测试新版本后反馈日志

---

## 下次会话工作指引

1. 用户说"分析日志"时:先读本文件了解当前实现状态,再读取最新 `debug_test_*.log`,重点搜索:
   - `reward-jump` 关键词（看跳转奖励任务流程是否正常执行）
   - `collectDirect: AI vision` 关键词（看 UC 主页图像按钮 AI 视觉兜底是否生效）
   - `点击商品` 关键词（看跳转到淘宝后是否成功点击商品）
   - `processTask: AI vision action=` 关键词（看 AI 视觉识别准确率）
2. 根据日志发现的问题,继续修改代码并更新本文件
3. 每次提交后同步更新本文件的"本轮会话修改历史"章节
