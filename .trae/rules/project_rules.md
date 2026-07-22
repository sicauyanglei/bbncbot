# BBNC bbncbot 项目规则

> **适用范围**：本文件为 TRAE IDE 项目规则，对本仓库的所有会话生效。
> 每次会话开始时自动加载，作为行为约束的硬性规则。

---

## 规则 1：每次执行前先读修改记忆文件

**目标文件**：`/workspace/CONVERSATION_LOG.md`

**执行时机**：每次会话开始的第一步（在分析日志、修改代码、执行任何任务之前）。

**读取内容**：
- 顶部「项目当前状态和历史决策」章节，了解项目背景
- 「本轮会话修改历史」章节最新一条记录，了解上一次会话做了什么
- 相关文件的引用路径，避免重复探索代码库

**目的**：
- `CONVERSATION_LOG.md` 是本项目的修改记忆文件，记录了每一轮会话的用户需求、修改要点、根因分析、修复方案
- 先读记忆文件可以避免重复劳动、继承历史决策、了解当前实现状态
- 用户说"分析日志"等无具体内容的请求时，先读本文件再分析最新日志

---

## 规则 2：每次修改完代码默认推送

**执行时机**：每次完成代码修改（修复 bug / 新增功能 / 重构）后，无需用户额外确认，自动执行：

1. **提交**（git commit）：
   - commit message 格式：`feat: buildNNN 简要描述` 或 `fix: buildNNN 简要描述`
   - build 编号递增（参考 `CONVERSATION_LOG.md` 最新一条记录的 build 号 +1）
   - 使用 HEREDOC 格式传递 commit message
   - 只 add 相关改动文件（不要 `git add -A`，避免误提交 secrets/大文件）

2. **推送**（git push）：
   - 目标分支：远端 `main` 分支
   - 命令：`git push HEAD:main --force`（trae 工作分支覆盖远端 main 历史）
   - 认证：使用用户提供的 GitHub PAT（通过 URL 内嵌 token 方式，不写入 git config）
   - 推送后立即在输出中脱敏 token（`sed 's/github_pat_[A-Za-z0-9_]*/***TOKEN***/g'`）

3. **触发 CI**：
   - 推送后 GitHub Actions `Build APK` workflow 自动运行
   - 构建完成后 Release 页面发布对应 build 的 APK
   - 向用户报告推送结果（commit hash、推送范围、CI 触发状态）

**例外情况**（不自动推送，需先询问用户）：
- 用户明确说"先不要推送"/"只改不推"/"等一下再推"
- 修改涉及 secrets（.env、credentials.json 等）
- 修改仅涉及文档（*.md）且无源码改动时，可推送但需说明 CI 不会触发（build.yml paths-ignore *.md）

**同步更新**：每次推送前，必须先更新 `CONVERSATION_LOG.md` 的「本轮会话修改历史」章节，记录本轮的用户需求、输入日志、根因分析、修复方案、编译验证状态，然后再 commit + push。

---

## 附录：关键文件索引

| 文件 | 作用 |
|------|------|
| `CONVERSATION_LOG.md` | 修改记忆文件（每次会话必读 + 每次推送必更） |
| `app/src/main/java/com/bbncbot/automation/AutomationController.kt` | 自动化状态机主控 |
| `app/src/main/java/com/bbncbot/service/FarmAccessibilityService.kt` | 无障碍服务（页面识别/节点操作） |
| `app/src/main/java/com/bbncbot/automation/AiVisionClient.kt` | AI 视觉客户端（GLM-4.6V-Flash） |
| `app/src/main/java/com/bbncbot/automation/QuizAnswerClient.kt` | AI 文本答题客户端 |
| `app/src/main/java/com/bbncbot/automation/Platform.kt` | 平台配置（UC/ALIPAY/TAOBAO） |
| `.github/workflows/build.yml` | CI 构建配置（paths-ignore: *.md） |
| `logs/` | 测试日志目录（用户上传，用于分析） |
