# 代码质量审查报告（1.0.0.27）

- 适用版本：1.0.0.27（源码快照 2026-10-09 14:40 JST；之后到 14:44 只改了文档与 `scripts/deck-power/`，Java 源码与快照逐字节相同）
- 审查方式：只读。按 7 个范围并行审查（agent 主循环与云端、社交与服主功能、remote、llm/config/policy/util/log/tpa、gamedata/goals、NeoForge 适配层、测试/脚本/构建/文档），高严重度条目由主审逐条对照源码复核。
- 基线：core 单测 171 个全部通过；`:neoforge-1.21.1:compileJava` 通过；`Docs/validate_docs.py` PASS。没有跑 e2e（审查时另一个会话正在用开发服务端）。
- 署名：Fable5（Claude Opus 5.5）
- 路径简写：`core/` = `mod/core/src/main/java/io/github/aftsakee/aiadmin/`，`neo/` = `mod/neoforge-1.21.1/src/main/java/io/github/aftsakee/aiadmin/neoforge/`，`test/` = `mod/core/src/test/java/io/github/aftsakee/aiadmin/`。
- 标记：【已核】= 主审亲自对照源码或 JDK 语义确认；【实测】= 审查代理编译快照类做过小实验；【待验证】= 依赖线上模组组合或运行环境，未能确认。

不再重复报的已知决定：玩家打【已授权】可冒充授权（H1，用户决定不修）；Origin 校验不放行 `Origin: null`（用户要求保留）。

---

## 一、优先处理

### A. 线上 1.0.0.25 已存在

| # | 严重度 | 位置 | 问题 | 建议 |
|---|---|---|---|---|
| A1 | 高【已核】 | `core/agent/CloudTools.java:291-317` | 云端 `bot_control` 的"不能切进恶作剧、不能调高档位"拦截区分大小写，`rank()` 不认识的值返回 -1 放行；而 `AgentService.setPersonaMode/setPrankLevel`（:645、:660）先转小写再生效。传 `"Prank"`、`"ROUGH"` 即可绕过 1.0.0.17 加的这条边界。`test/CloudServiceTest` 只测了小写。 | `control()` 入口先 `toLowerCase(Locale.ROOT)`；`rank()` 遇到未知值拒绝；补大小写单测。 |
| A2 | 高【已核】 | `neo/McBridge.java:35-47` | `server.submit` 底层是 `CompletableFuture.supplyAsync`。任务已在主线程执行时 `cancel(false)` 照样返回 true，于是提示"这一步已取消、没有执行"可能是假的，AI 会重试导致执行两次（1.0.0.17 想防的正是这个）。中断分支也没取消任务。 | 给 supplier 包一层 `AtomicInteger` 状态机（未开始/执行中/已取消），主线程 CAS 成功才执行，超时按 CAS 结果给文案；这段逻辑可下沉 core 单测。 |
| A3 | 中【实测】 | `core/config/Rules.java:44-52` | 默认指令黑名单按字面匹配：`kill @e[]`、`kill @e[distance=0..]`、`kill @a[]` 放行（效果等于 `kill @e`）；`data modify block … Command set value …` 没有冒号，绕过 `\bCommand\s*:`。 | `kill` 配 `@e` 时要求有非玩家的 `type=`，空 `[]` 视为无筛选；增加 `data (modify|merge) block … (Command|auto)` 拦截。 |
| A4 | 中【实测】 | `core/policy/CommandPolicy.java:79-86` | 每个 `run` 都递归检查后半段，耗时随嵌套层数指数增长：`execute run ` ×14 + 2 KB 文本约 2 秒，×16 约 8 秒；指令长度无上限。代理是单线程，远程也走这条检查。 | 只在最外层对每个 `run` 后缀各查一次；限制指令长度。 |
| A5 | 中【已核】 | `core/llm/HttpTransport.java:23`、`core/llm/BaseSession.java:44-52` | 模型接口客户端 `Redirect.NORMAL`。JDK 跨主机重定向会去掉 `Authorization`，但照转 `x-api-key`、`x-goog-api-key` 和 extra_headers：中转站一个 307 就能把 Claude/Antigravity 密钥送到别的主机（代理本机实测）。 | 模型请求改 `Redirect.NEVER`，3xx 直接报错。 |
| A6 | 中 | `core/llm/BaseSession.java:24-25, 62-85` | 超时也重试，单步最长约 3×300+8 ≈ 908 秒，远超 `run_timeout_seconds=300`；截止时间只在两步之间检查，单线程代理会卡十几分钟，已计费请求重复花钱；429 不看 Retry-After。 | 超时不重试，或把剩余运行时间作为总预算传进 `request()`。 |
| A7 | 中【已核】 | `core/agent/ActionTools.java:129-135`、`core/agent/PlayerPrefs.java:320-343` | "别说了"和回避话题号称代码层强制，但 `chatBlocked` 只在 `send_chat` 里调用；`run_command` 执行 `tell`/`msg`/`tellraw`/`title`/`say` 不检查，默认黑名单也不拦这些。 | `run_command`（含 `execute … run` 嵌套）遇到说话类指令时取出目标和文本，同样过 `chatBlocked`。 |
| A8 | 中 | `neo/McTools.java:88-107, 141-147` | `remote.hide_player_ips` 只作用于 `get_player`/`get_access_lists`。原版登录日志带 `名字[/IP:端口]`，`get_server_log`、`read_file logs/latest.log`、`banned-ips.json` 都在远程只读白名单里，云端照样拿到 IP。1.0.0.27 的 `host_info logs aiadmin` 同理。 | `showIps` 为 false 时对日志与文件输出做 IP 正则脱敏（或在 `LogTap` 入缓冲前脱敏）。 |
| A9 | 中【待验证】 | `core/remote/OAuthProvider.java:526-544`、`test/OAuthTest.java:467-470` | 已知的 M7 经推演真实存在：同一 refresh token 30 秒内并发两次，后一次作废前一次签发的令牌并把它标为 poisoned；先到的那个工作进程拿旧令牌 401 后用 rt1 刷新，命中 poisoned，整个客户端被吊销。测试把这种行为断言成"判定被盗"。ChatGPT 这类多进程连接器会并发刷新。 | 宽限期内改成幂等重放：缓存 30 秒"rt 哈希 → 上次响应"，原样返回，不作废不标记。 |
| A10 | 中【待验证】 | `neo/TrackerHooks.java:207-272`、`neo/StoryHooks.java:177-322` | 只判断 `instanceof ServerPlayer`，NeoForge 的 FakePlayer 是其子类（只有 GoalHooks:122 过滤了）。Create 机械手等自动放置/挖掘会写进建筑账本、刷里程碑、唤醒 AI。 | 抽 `isRealPlayer(p)`（非 FakePlayer，或 dev 属性开启时放行），两个 Hooks 入口统一调用。 |
| A11 | 中 | `neo/ServerHooks.java:344-366` | 除 msg/tell/teammsg/ai 外所有玩家指令按原文记进动态；离线服登录模组的 `/login 密码`、`/register 密码 密码` 会被 AI 读到；`/execute as @s run msg …` 绕过私聊脱敏。 | 增加敏感根指令名单（login、l、register、changepassword…）只记"执行了 /login"；对 `execute … run` 后缀再判一次根指令。 |

### B. 1.0.0.27（未部署）——部署前必须修

| # | 严重度 | 位置 | 问题 | 建议 |
|---|---|---|---|---|
| B1 | 高【已核】 | `core/remote/McpServer.java:705-714`、`core/agent/OwnerTools.java:260-269` | 只读模式（`remote.allow_actions=false`）的隐藏名单不含 `run_macro`，而 `runMacro` 直接调用 `run_command` 的处理器，后者只查 rules、不查 `allowActions`。只读令牌或已批准的 OAuth 客户端能通过宏执行 kick/ban/tp。`memory_note` 同样没拦，写进共享记忆后会喂给能执行指令的本地 AI。 | `hidden()` 改为"只读模式下隐藏所有 `!ToolAccess.readOnly(name)` 的工具"（默认拒绝）；`runMacro` 遇到 REMOTE 触发再查一次 `allowActions`；补测试。 |
| B2 | 高【已核】 | `core/config/SettingsEditor.java:35-52`、`core/config/BotConfig.java:202-207` | 默认可改名单包含 `patrol.extra_instructions`，它被原样拼进巡检提示词（`Prompts.java:201`）。被玩家聊天注入的云端 AI 可以永久写入最多 2000 字的指令，本地每次巡检都以巡检身份执行，不受聊天触发的限制——正是类注释说要防的"云端给自己加权限"。 | 把进提示词的自由文本键（`patrol.extra_instructions`、`compression.*.prompt_file`、`summary_provider`）加入硬性禁止。 |
| B3 | 中 | `core/config/SettingsEditor.java:122-168` | 写回用 `GSON.toJson` 整体重写，服主 jsonc 注释全丢；静态 `set()` 读改写不加锁、pending 文件名固定，并发时一方的 finally 可能删掉另一方的 pending，使校验读到"文件不存在 → 默认配置"而误判通过；第二次修改就覆盖 `.bak-setting` 原始备份；没有任何单测。 | 加类级锁、pending 随机名；首次修改另存原始备份；写回改文本级替换或在 README 说明注释会丢；加 `SettingsEditorTest`。 |
| B4 | 中 | `core/agent/SocialTools.java:88-97`、`core/agent/ActionTools.java:132`、`core/agent/OwnerTools.java:251` | 三处把 REMOTE 当服主本人放行。云端处理玩家聊天时，除 `reply_chat`/`run_command_for` 外的工具都以 REMOTE 身份调用，于是"只能改自己"、`from_player_chat`、安静检查全部失效。 | 这几个工具也带 `item_id`，改用 `cloudContext` 判定身份。 |
| B5 | 中 | `core/agent/SocialTools.java:92`、`neo/StoryHooks.java:556` | "只能改正在说话的玩家"用的是 `speakers()`（同批所有发言者），不是 `direct()`（真正 @ 机器人的人）。A 说"@AI 解除 B 的安静"，只要 B 同批说过话就通过。 | 改用 `tr.direct()`。 |
| B6 | 低 | `core/agent/OwnerTools.java:140-145` | text 参数只转义 `\` 和 `"`，不处理单引号；1.21 组件常用单引号 SNBT，`x',enchantments={…},a='` 能跳出模板（仍过黑名单）。 | 拒绝或转义 `'`、`{`、`}`、`[`、`]`。 |
| B7 | 低【已核】 | `scripts/deck-power/aiadmin-power:13`、`sudoers-aiadmin-power` | sudoers 已改为放行任意参数，脚本也做了统一字符白名单（:43-45）和 `__run` 的 SUDO_USER 拒绝，实现没问题；但第 13 行注释仍写"sudoers 只放行上面三条精确参数，所以这里是唯一的把关处"，与第 12 行矛盾。 | 改注释。 |

---

## 二、结构与可维护性

1. **AgentService 1431 行，约 45 个可变字段、40 多个 synchronized 方法**，还通过 12 个包内 getter 充当 CloudTools 的服务定位器。建议按下表逐块搬出，AgentService 只留生命周期与编排（约 400 行）：

   | 新类 | 搬出内容（`core/agent/AgentService.java` 行号） |
   |---|---|
   | ChatIntake | 117-119、137-146、311-608、700-704（公屏/私聊收集、条件、合并） |
   | CloudRouter | 94-109、783-857、867-990、tick 中 753-761（云端优先/兜底，CloudTools 只依赖它） |
   | BalanceMonitor | 147-149、711-719、1215-1287 |
   | RunExecutor | 992-1200（`run()` 单方法 124 行，可再拆选接入点/尝试循环/结算） |
   | PersonaSettings | 612-697 |
   | ScheduleGate | 1363-1407、1105-1118、1410-1415（纯函数） |

   同类重复："交云端就不查额度 → 查启停 → 记 trigger.dropped"重复 4 次（440、597、726、770）；每人每小时/天上限重复 2 次（369、420）；免整名单在 `AgentService:623` 与 `ActionTools:73` 各算一遍。死代码：`privateFlush` 只写不读、`onChat` 两参数重载与 `Prompts.user` 五参数重载只有测试在用。

2. **McpServer 957 行**：建议拆为 生命周期装配（105-318）/ RemoteGate HTTP 闸门（320-528）/ McpProtocol 双代协议分发（530-703）/ RemoteToolGateway 工具目录与限额（705-920，`hidden()` 并入 ToolAccess）/ JSON-RPC 构造工具（922-956）。401 构造、Content-Length 判断、每小时窗口剪枝各重复 2 处；限流一处回 -32603（Internal error）、一处回 isError 结果，不一致。

3. **持久化各写一套，"读坏不覆盖"整体缺失**（违反 AGENTS.md 约束）：`GoalStore:90-92`、`PlayerPrefs:364`、`ProjectStore:278`、`BuildLedger:174`、`Milestones:70`、`CommandNotes:210`、`Quota:57`、`Memory:51`、`PersonaState:36`、`TpaManager:45-52`、`mcp-events.json` 都是读失败后按空数据继续，下一次写入就用空数据覆盖原文件；运行中写失败大多静默。`quota.json` 坏了等于额度被悄悄清零；Milestones 坏了所有"第一次"事件会重发。原子写也有 4 套（PlayerPrefs、ConfigStore、SettingsEditor、JsonFiles），部分没有 `ATOMIC_MOVE` 或回退。
   - 建议：统一收敛到 `util/JsonFiles`：读失败把原文件改名 `.corrupt-<UTC时间戳>` 并写日志后再允许写盘；写失败经 JsonlLog 报错；补"读坏不覆盖"单测。

4. **共享可变对象跨线程**：`ProjectStore.get/all/forPlayer`（:136、:185-202）返回活对象，`AgentService:520-530`（代理/云端线程）与 `StoryHooks:411/436/494/585` 锁外读写 `members`/`notes`/`radius`，可能 `ConcurrentModificationException`。gamedata 的 record 也没有防御性拷贝（`GameData:15,117`、`RecipeJson:105`、`CraftPlanner:41-50`），跨线程安全只靠约定。
   - 建议：查询返回不可变快照，修改走带锁方法；record 紧凑构造器 `List.copyOf/Map.copyOf`。

5. **锁内 IO 与主线程 IO**：`BuildLedger:131-156` 每 5 分钟在锁内序列化最多 3 万区块并写盘，主线程每放一个方块要抢这把锁；`Milestones.first()` 在主线程同步重写整个文件；`JsonlLog:117-145` 每条 error（含被排重的）都在锁内重写排重状态文件；`TrackerTools` 的 `get_offline_player` 在主线程遍历 playerdata 读 NBT；`AgentService` 类注释说"提醒在锁外发出"，实际 `tellPlayer`/`publish` 在锁内。
   - 建议：锁内只拍快照、锁外写盘；Milestones/GoalStore 改"置脏 + 定时落盘"。

6. **唯一的定时线程做阻塞网络请求**：`AgentService:219-227` 的聊天合并、云端兜底、收回都跑在同一个单线程定时器上，而余额查询（`Balance` 每个接入点超时 20 秒）也在这个线程同步发 HTTP，接口卡住时聊天回复和兜底被推迟。
   - 建议：余额查询放独立执行器。

7. **云端条目"检查后执行"不是原子的**：`CloudTools:160-167` 不持锁读 `state==TAKEN` 再建 ctx，`CloudInbox.reclaimIdle`（:216-227）在两步之间改成 FALLBACK 交本地，同一条消息可能云端、本地各回一次。`wait_events` 的工具说明（`CloudTools:47`）与实际收回规则（2×handoff 秒内必须建过 ctx）也不一致。
   - 建议：CloudInbox 提供 `synchronized beginAction(id, Supplier<RunContext>)`；更正说明。

8. **NeoForge 事件层重复**：方块放置、伤害、拾取、合成在 TrackerHooks 与 StoryHooks 各订一次，LivingDeathEvent 3 处，4 个 tick 监听各自计数；`TrackerHooks.where` 丢维度命名空间；`ServerHooks:282`、`McTools:326` 用 `(int)getX()` 截断，而 CombatLog 用 `getBlockX()` 向下取整，负坐标差 1；物品显示名两套格式。
   - 建议：抽 `McFmt`（名字/维度/方块坐标/物品标签）和一个事件分发器。

9. **设置文件错误被静默吞掉**：`ConfigStore:74-84,389` 拼错的键直接忽略、`"enabled":"yes"` 被宽松解析成 false（机器人直接停用）、越界数值静默修正，warnings 都是空——与 :84 注释"返回给服主看的警告"不符。`GoalTable:161-228` 同理，且缺 id 的阶段用位置当 id，服主中间插一个阶段所有玩家进度错位。
   - 建议：先解析成 JsonObject 与默认结构比对，未知键、类型不符逐条警告；阶段 id 必填。

10. **玩家键不一致**：`BuildLedger`、`PlayerTracker` 区分大小写，`PlayerPrefs`、`CombatLog`、`Milestones` 转小写，`ProjectStore` 忽略大小写比较；都不用 UUID。改名后偏好失效、拿到旧名的新玩家继承数据。
    - 建议：统一键函数，持久化存 UUID，名字只作显示。

11. **游戏资料解析会悄悄降级**：
    - `core/gamedata/RecipeJson.java:72-98`（高）：原料槽解析不出时直接丢掉，配方不标"原料不全"，三槽配方读成两槽后 `craft_plan` 会说"已备齐"——1.0.0.18 只修了"一个都读不到"。建议统计丢槽数，按 `inputsUnknown` 处理。
    - `core/gamedata/LootJson.java:132-135`（中）：标签条目没读 `expand`，`test/GameDataTest.java:138-141` 把错误概率（20/60，原版应为 20/70）固定成了断言。
    - `core/gamedata/GameData.java:188-198`（中，1.0.0.18 引入）：搜索只比路径后，带命名空间的 `create:andesite` 永远查不到。
    - `core/gamedata/CraftPlanner.java:180-237`（中）：打分用 `int[9]` 魔法下标，[7][8] 不参与排序；打分与 need 对概率配方次数估算不一致；"有配方"判断与 `choose` 的过滤不一致；防溢出只做一半（:138-162 仍有 int 运算）。
    - `RecipeJson:36-88`：Domum、mctradepost 特例对所有配方生效，`SKIP_KEYS` 跳过 `extra_inputs` 可能让其它模组配方看起来更省料；`has && isJsonArray ? … : new JsonArray()` 样板在 LootJson/GoalTable 重复约 10 处。建议按序列化器 ID 登记适配表，抽 `util/Json`。

12. **日志规则偏差**（`core/log/JsonlLog.java`）：未处理异常走普通 `error()` 也被排重（主代码没有调用 `fatal()`）；同一错误第 100 次时删指纹重开窗口；条目闲置 24 小时被清理时累计次数不写汇总；脱敏只看 data 第一层键名，`x-api-key`、`accessToken`、嵌套 `headers.Authorization`、message 里的 `Bearer …` 都明文落盘；`logs/aiadmin/` 不在 FileGuard 永久禁止目录里，代理可 `read_file`；gzip 失败会留下永不清理的未压缩滚动文件；data 有不可序列化值时整行静默丢失。`FileGuard:21-22` 的脱敏正则要求键名在行首，单行 JSON、`export API_KEY=…`、`Authorization: Bearer …` 都漏。

13. **其他正确性小问题**：`Memory` 压缩假设"期间只在末尾追加"已不成立（云端会从 HTTP/定时线程写记忆，可能误删未压缩记录）；`ServerPower` 超时/异常时不写审计、超时只杀 sudo 进程；`McpEvents` webhook 响应体无上限、到期订阅不清理却占名额、`close()` 后重试会重建线程；JSON-RPC 批量无条数上限（1 MiB 可塞约 2 万条 `tools/list`）；`/mcp` 实际顺序是 CIDR → Bearer → Origin，与 AGENTS.md 规定顺序不符（安全影响小）；`PrankPolicy` 漂浮效果与其它效果共用等级上限，无害档可升约 8 格摔伤，与"无害档不掉血"矛盾（按公式估算，待游戏内实测）；`ProjectStore` 进行中工程永不淘汰，`seq` 缺失时新建会覆盖已有工程；`LogTap` 会把机器人自己指令引发的 ERROR 回灌成错误事件。

14. **过期与缺失的"原因、边界、约束"注释**：`FileGuard:17`（说不跟随符号链接，实际 toRealPath）、`ConfigStore:84`、`JsonlLog:39`、`TpaManager:27`（说原子写，实际没 ATOMIC_MOVE）、`StoryHooks:66`（说全部主线程、只做内存操作）、`McBridge:41`、`McpServer:705`（只读模式唯一闸门没写清哪些算动作，B1 正出在这里）、`McpServer:364`（死过滤器 `"token|"`）、`HttpTransport:23`、`CommandPolicy:17`、`CraftPlanner:312-315` 分档常量、`aiadmin-power:13`。

---

## 三、测试与工程

- **单测缺口**（按风险排序）：SettingsEditor 零测试；只读模式下 `run_macro`/`memory_note`；`bot_control` 大小写；并发 refresh；ActionTools 的聊天条数上限、长度截断、`allowPrivateChat=false`、处理器内部开关（现有测试走"隐藏工具"那条路，打不到）；FileGuard 的 toRealPath 复查分支；各 Store 的"读坏不覆盖"；`run()` 的备用接入点切换；McpEvents 的 NAT64/6to4 地址、慢响应体、close 后不再投递。AGENTS.md 的安全边界清单只列 3 个类，应加上 SettingsEditor、ToolAccess、ServerPower、McpEvents 回调地址检查。
- **单测质量**：断言普遍落在具体行为上，用临时目录和端口 0，没有可变静态共享状态。问题是 `test/FakeHttp.java:22-32` 线程不安全（测试线程无锁读 ArrayList）；11 个测试类各写一遍 ServerActions 假实现、7 个各写一遍 Host，约 250 行重复；AgentService 内部写死 `System::currentTimeMillis`，测试只能真睡并断言"<2800ms""≥140ms"，负载高时易误报。建议注入时钟、抽 TestRig。
- **e2e 脚本**：9 个脚本都没有按进程名杀 java（好）；但不杀子进程树——`subprocess.run(timeout=900)` 超时只结束 gradlew.bat，服务端 JVM 成孤儿；`finally: proc.wait(600)` 超时直接抛异常；300 秒就绪循环不看 `proc.poll()`，端口被孤儿占着时会把孤儿当成新服务端。约 400 行（22%）复制粘贴，大量硬编码 sleep，跨午夜运行会误报。建议抽 `scripts/e2e_common.py` 的 `dev_server()` 上下文管理器（Windows `taskkill /T /F`，POSIX `killpg`），改为按日志事件轮询。`mock_llm.py` 处理函数无 try/except，请求格式变了会被 Java 端当网络错误重试，e2e 只报"轮数不对"。
- **宿主机脚本** `scripts/deck-power/aiadmin-power`：参数白名单和注入防护扎实。`set -euo pipefail` 下 `find … | sort | while`（备份目录不存在时）和 `$(( $(du -sb …) ))`（du 非 0）会让 `status`/`backup` 中途静默退出（bash 实测）；restart/stop 的 precheck 不检查刚开始的 AI 手动备份（打 zip 是异步 1~3 分钟），可能打断备份；root 执行 `/home/mcadmin/*.sh` 与加载 `/opt/steamdeck-deploy/crafty-integrate.py`，若这些文件对 crafty 或 mcshared 可写就是提权路径【待验证】。
- **构建与文档**：版本链路 version.json → Gradle → mods.toml → Implementation-Version 完整；neoforge 子项目没开 `-Xlint`；没有一条命令跑完 Gradle + 文档校验 + 版本一致性；`validate_docs.py` 不比对 CHANGELOG 最新版本与 version.json、不查 INTERFACE_INDEX 路径和推荐测试、缺文件只打 SKIP 退出码仍为 0；`neoforge-1.21.1/build.gradle:16` 注释指向不存在的 `scripts/e2e.ps1`；`live_deepseek.py` 运行后开发服务端 config.json 留有明文密钥、`server-live.log` 未脱敏。

---

## 四、整体评价

- **做得好的**：core 与 NeoForge 分层干净，游戏状态基本都经主线程读取并返回不可变快照；远程接口外层防线扎实（CIDR、常量时间比较、未认证时限与名额、一次性授权码、PKCE、批准页 CSP），审计日志里没有凭据；FileGuard 路径规范化（真实路径、8.3 短名、ADS、大小写、尾随点）没找到绕过；CraftPlanner 一定终止且结果确定；单测断言具体、e2e 有日志密钥泄露检查。
- **主要短板**：一天内多个会话并行加功能，安全边界在**跨通道**处出现缺口——只读模式漏了宏、"别说了"漏了 run_command、云端以 REMOTE 身份被当成服主、调档拦截漏了大小写、IP 隐藏漏了日志。共同原因是边界用"黑名单式隐藏/逐处检查"实现，新工具默认被放行。建议把只读判定统一到 ToolAccess 白名单（默认拒绝），身份判定统一到一个 `Caller` 抽象。
- **可维护性**：AgentService、McpServer 已是大杂烩类，门控与限流逻辑复制多份；持久化各写一套且都缺"读坏不覆盖"。建议先做第二节第 3 条（统一 JsonFiles）和 AgentService 拆分。
- **建议顺序**：B1、B2（1.0.0.27 部署前）→ A1、A2、A3、A5、A7 → 第二节第 3、4 条 → A4、A6、A8–A11 与 B3–B5 → 结构拆分与测试工程。
