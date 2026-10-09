# MC-AIAdmin 项目规则

适用版本：1.0.0.39。全局规则见 `~/.codex/AGENTS.md`，本文件只写本项目的约束。

## 项目

Minecraft 1.21.1 / NeoForge 服务端模组：AI 管理机器人。`mod/core` 是与 MC 无关的纯 Java 核心（协议、代理循环、调度、额度、规则、压缩、日志），`mod/neoforge-1.21.1` 是 MC 适配层（观察工具、指令执行、事件、`/aibot`）。

- 版本唯一来源：仓库根 `version.json`（Gradle 直接读取）。
- 构建/测试：`cd mod && ./gradlew :core:test :neoforge-1.21.1:build`；端到端：`python scripts/e2e.py`、`python scripts/e2e_tracker.py`（玩家行为追踪）、`python scripts/e2e_tpa.py`（传送请求）、`python scripts/e2e_oauth.py`（远程接口 OAuth，含官方 MCP SDK 互通）、`python scripts/e2e_persona.py`（预设模式与恶作剧档位）、`python scripts/e2e_gamedata.py`（游戏资料查询、合成链路、目标表）、`python scripts/e2e_cloud.py`（云端值守：云端优先、本地兜底）、`python scripts/e2e_events.py`（MCP Events 推送：新版协议、订阅验证、签名投递）、`python scripts/e2e_social.py`（玩家互动：频道、/ai 私聊、"别说了"、偏好、工程、首次成就事件、死亡前因后果）；九者共用开发服务端，不能同时跑；共用的检查、等待、开服、远程调用在 `scripts/e2e_common.py`，新脚本复用它，等待用 `wait_until` 按条件轮询而不是固定 sleep。
- 文档 Gate：`python Docs/validate_docs.py --root .`，触碰 `Docs/**` 后必须全绿（含 version.json 与维护日志最新版本一致、接口索引路径存在）。
- 一键检查：`python scripts/check_all.py`（单测 + 构建 + 文档校验 + 版本一致；`--e2e` 再跑全部端到端）。
- 模型简称：Codex / Fable5 / Dsv4（全局登记）。本项目由 Fable5 创建，**不登记** `creator_model_exempt`。

## 主题 → owner 文档

| 主题 | owner |
|---|---|
| 用法、设置文件、规则、指令、工具清单 | `README.md` |
| 功能定位 | `Docs/Indexes/FEATURE_INDEX.jsonl` |
| 接口、文件格式、外部 API | `Docs/Interfaces/INTERFACE_INDEX.jsonl` |
| 变更与验证历史 | `Docs/Maintenance/CHANGELOG.jsonl` |

## 约束

- 安全边界集中在 core：`policy/CommandPolicy`（黑名单含嵌套 execute 与代码层硬限制）、`agent/ActionTools`（指令/聊天上限与开关、"别说了"、远程只读兜底）、`util/FileGuard`（文件访问边界与脱敏）、`remote/ToolAccess`（远程只读白名单：只读模式默认拒绝）、`config/SettingsEditor`（云端改设置的硬性禁止项）、`agent/ServerPower` 与 `remote/McpEvents` 的回调地址检查、`util/CommandPrivacy` 与 `Text.redactIps`（隐私）。改动这些必须同步单测。
- 新增工具时：只读的登记进 `ToolAccess.READ_ONLY`；会改动的不用登记（默认按动作处理），但凡是"代表玩家聊天"可能被调用的，要按 `ctx.trigger` 判断身份，不能把 REMOTE 当成服主放行敏感操作（云端带 `item_id` 时 ctx 是那条消息的身份）。
- 代理只能通过 `run_command` / `send_chat` 影响服务器；新增"动作"类工具前先征得用户同意。远程管理接口（`remote/McpServer` 管生命周期，`remote/RemoteGate` 管 HTTP 闸门，`remote/McpProtocol` 管协议分发，`remote/RemoteTools` 管工具目录和调用路径）复用同一套工具与规则，不得绕过 `ActionTools` 直接执行指令。
- 远程接口安全边界：来源 CIDR → Origin → Bearer 令牌 → 规则/限流，顺序不能打乱（`RemoteGate` 的 early 和 handle 共用同一组检查方法）；令牌不得进日志、不得对代理可读。
- NeoForge 事件订阅一律登记在 `neoforge/HookBus.register`（同一事件 + 优先级 + 是否收已取消只订一次、按登记顺序分发、处理者逐个隔离异常，tick 用 `every(N)`）；名字、维度、坐标、物品标签的格式化用 `neoforge/McFmt`，不要各写一份。
- 游戏状态只能在服务端主线程读写（`McBridge.onServer`）；代理线程不得直接碰 MC 对象；主线程上不做文件和网络 IO（读文件在调用线程上做，结果再交给主线程；主线程上周期性的读写交给后台线程，例如 `GoalHooks` 的 aiadmin-goals-io 和 `GoalTable.offMainThread`）。
- 锁顺序：`AgentService`（`ChatIntake`、`CloudRouter`、`RunExecutor` 和它共用同一把锁，彼此会回调）→ 收件箱 / 余额监控 / 启停（`ScheduleGate`）/ 预设（`PersonaSettings`）/ 各状态仓库；后者持锁时不得回调 `AgentService`。业务时间取注入的 `Clock`，延时任务交给注入的 `Scheduler`。模组自己的 JSON 状态文件一律经 `util/JsonFiles`（读坏改名隔离、写失败上报）。
- 单测共用 `TestRig`（`FakeActions` / `FakeHost` / `TestClock` / `ManualScheduler`），不要在测试类里再手写 ServerActions / Host；涉及合并、兜底时序的用假时钟 + 手动定时器，不要真睡或断言耗时上限。
- 设置文件读坏了不得覆盖用户文件；密钥不进日志（`JsonlLog` 脱敏 + `config/aiadmin/` 对代理不可见）。
- 部署到 Steam Deck 服务器、改 OneDrive 交付目录、git 推送都要用户明确同意。
