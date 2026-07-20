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

### commit (待提交) - fix: 支付宝滑动浏览任务不滑动（isBrowseTask 关键词 + H5 虚拟列表 bounds 过滤）
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
