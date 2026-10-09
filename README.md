# AI 管理机器人（MC 服务端模组）

适用版本：1.0.0.39　Minecraft 1.21.1 + NeoForge 21.1.x（按 21.1.235 编译）　只需装在服务端，玩家不用装。

服务端 AI 管理员：接入 **GPT / Claude / DeepSeek / Antigravity**（官方 API 或中转站），能**看到服务器的一切信息**，但**只能通过后台指令和发送聊天**影响服务器。

- 唤醒方式：定时巡检、事件（进服、死亡、卡顿、报错…）、玩家聊天（叫它名字或 @它；也可以按自定义条件或所有聊天唤醒，按群体/个人设上限和授权）、管理员 `/aibot ask`。
- 远程管理：在 Tailscale 内网地址上提供 MCP 接口，云电脑上的 AI（Claude Code 等）可以直接用同一套工具管理服务器。
- **云端值守**（`remote.cloud`）：云端 AI 用 `wait_events` 24 小时守着收件箱，玩家聊天、事件、巡检优先交给云端，云端不在或超时没取再由本地 API 兜底；云端的回复和指令走和本地完全相同的检查，记忆两边共用。不能一直挂着长轮询的客户端（ChatGPT、dots）可以订阅 **MCP Events 推送**：收件箱有新消息时服务器主动推到它的回调地址把它叫醒。
- 限制规则独立成文件，分**权限 / 额度管理 / 启停限制**三层。
- 额度：按小时/天/周/月限制次数、token、估算花费；DeepSeek 等按钱计费的接入点直接查账户余额，低于提醒线自动警报、低于停用线自动停用。
- **游戏资料查询**：物品、全部配方（含模组机器）、标签、战利品表（方块/生物掉落、宝箱、钓鱼、考古），以及**整条合成链路**（对照玩家背包算出步骤和还缺什么、去哪弄）；数据直接取自运行中的服务端，换模组自动跟着变。
- **目标表**：服主写阶段进度表，玩家可以设个人目标；`/goal` 查进度、下一步、合成路线，定时私聊提示下一步（模板生成，不花 AI 额度）。
- **玩家互动**：聊天按频道标记（@机器人 / 公屏闲聊 / 私聊）；`/ai` 私下找机器人；每人的互动偏好（欢迎调侃 / 只想认真答疑 / 不想提的话题 / "别说了"），连续死亡时自动收敛，这些在发送前由代码强制检查；玩家的工程（`/goal project`）和"这栋房子是谁建的"；只推有意义的事件（第一次造出机器、捡到稀有物品、驯服、击败 Boss、救下队友、工程进展）；死亡附带前因后果（死前 30 秒、附近队友、有没有回去捡）；服主写的服务器指南（自定义指令的单位和恢复方式）。
- 每个接入点各自的**系统提示词**和**自定义提示词**放独立文件，缺失时自动生成。
- **自动上下文压缩**：时机和方法都可配置；长期记忆也会自动压缩。

## 安装

1. 把 `aiadmin-neoforge-1.21.1-<版本>.jar` 放进服务端 `mods/`，开服一次。
2. 自动生成 `config/aiadmin/`：`config.json`、`rules.json`、`prompts/`。
3. 在 `config.json` 里给要用的接入点填 `api_key`（或设环境变量，见 `api_key_env`），选好 `active_provider`。
4. 游戏里（OP）或后台执行 `/aibot reload`，再 `/aibot status` 确认。

## 文件一览（都在服务端目录下）

| 路径 | 内容 | 谁写 |
|---|---|---|
| `config/aiadmin/config.json` | 接入点、触发方式、技术参数、上下文压缩 | 服主（缺失时自动生成） |
| `config/aiadmin/rules.json` | 限制规则：权限、额度、启停 | 服主（缺失时自动生成） |
| `config/aiadmin/prompts/<接入点>.system.md` | 该接入点的系统提示词 | 服主（缺失时自动生成） |
| `config/aiadmin/prompts/<接入点>.custom.md` | 该接入点的自定义提示词（服规、性格），接在系统提示词后 | 服主（缺失时自动生成） |
| `config/aiadmin/prompts/compress.md`、`memory-compress.md` | 两种压缩用的提示词 | 服主（缺失时自动生成） |
| `config/aiadmin/prompts/modes/<模式>.md` | 预设模式（帮助/吐槽/恶作剧/自己加的）的提示词 | 服主（内置模式缺失时自动生成） |
| `config/aiadmin/goals.json` | 目标表：阶段进度、个人目标上限、定时提示设置，见下文"目标表" | 服主（缺失时自动生成，改完自动重读） |
| `config/aiadmin/remote-token.txt` | 远程管理接口的访问令牌（`remote.token` 留空时自动生成） | 模组 |
| `config/aiadmin/mcp-events.json` | 云端的推送订阅（回调地址、签名密钥、有效期），见"推送唤醒" | 模组 |
| `config/aiadmin/macros.json` | 服主写的宏（名字 + 参数 + 一串指令），AI 用 `run_macro` 执行，见"服主扩展" | 服主（改完立即生效） |
| `config/aiadmin/guides/*.md`、`*.txt` | 知识文档（服规细则、活动安排、模组玩法），AI 用 `read_guide` 按需查阅 | 服主（改完立即生效） |
| `config/aiadmin/config.json.bak-setting` | 云端 `set_setting` 改设置前的备份（只留最近一份） | 模组 |
| `config/aiadmin/server-guide.md` | 服务器指南：自定义指令的用途、参数、单位、怎么恢复，谁能要求什么（进 AI 的系统提示词） | 服主（缺失时生成模板） |
| `aiadmin/player-prefs.json` | 每名玩家的互动偏好（风格、不想提的话题、安静到何时、备注） | 玩家（/ai）和 AI |
| `aiadmin/projects.json` | 玩家的工程（标题、类型、地点、成员、进展、范围内放的方块数） | 玩家（/goal project）和 AI |
| `aiadmin/milestones.json` | 每名玩家和全服的"第一次"（第一次造出/捡到某物、击败某 Boss） | 模组 |
| `aiadmin/build-ledger.json` | 建筑账本：按区块记谁放了多少方块 | 模组 |
| `aiadmin/command-notes.json` | AI 用过模组指令后记下的笔记（单位、效果、怎么恢复；标明是否亲自验证过） | AI（服主可删改） |
| `aiadmin/quota.json` | 额度账本（重启不清零；读坏时原文件改名为 `.corrupt-时间戳`、本时段从 0 计并提醒管理员） | 模组 |
| `aiadmin/memory.json` | 行动记录 + 长期记忆摘要 | 模组 |
| `aiadmin/goals-progress.json` | 每个玩家完成了哪些阶段、个人目标、提示开关 | 模组 |
| `aiadmin/persona.json` | `/aibot mode`、`/aibot prank`、`/aibot talk` 切换后的模式、档位和话量（重启后仍有效） | 模组 |
| `logs/aiadmin/full.jsonl`、`error.jsonl` | 程序日志（每次运行、每条指令、每句聊天都有审计记录） | 模组 |

`aiadmin/` 下这些状态文件都**读坏不覆盖**：整份读不了时原文件改名为 `<文件名>.corrupt-<UTC 时间>` 保留、按空数据继续；个别记录坏了只跳过那几条并复制一份 `.corrupt-…` 留证；读坏、写失败都记进 `error.jsonl`（事件 `state/state.corrupt`、`state.partial`、`state.save_failed`）。

`config/aiadmin/` 里有密钥，代理的 `read_file` / `list_files` **永远读不到**这个目录（也挡住了 Windows 上 `aiadmin./`、短文件名、`:` 数据流、链接等换名写法）。只放一个密钥、没有键名的文件（`*.secret`、`.env`，如 Velocity 的 `forwarding.secret`）也永远读不到；`read_file` 读出的文本里，整行"键=值"、行内的 `"api_key": "…"` / `API_KEY=…`、`Bearer …`、连接串里的口令都换成 `[已隐藏]`。设置文件写错时**不会被覆盖**：`/aibot reload` 时继续用旧设置；开服时读不了则机器人**锁定**（连管理员提问也拒绝，不会用默认规则替你做决定），修好后 `/aibot reload` 解锁。能读但写得不对的地方会作为警告列出（`/aibot reload` 的回复和日志里）：拼错的键（"未知设置 patrol.interval_minute"）、布尔值写成字符串（`"enabled": "yes"` 会被当成 false）、超出范围被修正的数值（"remote.port = 70000 超出范围，按 65535 处理"）。

## config.json

```jsonc
{
  "enabled": true,
  "bot_name": "AI管家",                 // 聊天里显示 [AI管家] …
  "active_provider": "deepseek",
  "fallback_providers": ["claude"],     // 第一次请求就失败、或余额不足停用时改用；已执行过动作后不切换
  "providers": {
    "deepseek": {
      "type": "openai",                 // openai / anthropic / antigravity
      "base_url": "https://api.deepseek.com",
      "api_key": "",                    // 空则读环境变量 api_key_env
      "api_key_env": "DEEPSEEK_API_KEY",
      "model": "deepseek-flash",         // DeepSeek V4.1 Flash
      "extra_body": { "thinking": { "type": "enabled", "reasoning_effort": "max" } },   // 推理强度 none/low/high/max
      "timeout_seconds": 300,
      "price_input_per_million": 2.0,   // 估算花费用；请按官网当前价格核对
      "price_output_per_million": 3.0,
      "currency": "CNY",
      "balance_api": "auto"             // auto / deepseek / newapi / none
    }
  },
  "patrol": { "enabled": true, "interval_minutes": 15, "only_when_players_online": true, "extra_instructions": "" },
  "events": { "player_join": true, "player_leave": false, "player_death": true, "advancement": false,
              "tps_low": true, "tps_low_mspt": 60, "tps_low_seconds": 30, "server_error": true,
              "chat_keywords": [], "debounce_seconds": 5, "cooldown_seconds": 120, "alert_cooldown_seconds": 900 },
  "limits": { "tool_result_max_chars": 12000, "queue_max": 4, "memory_entries": 8, "run_timeout_seconds": 300 },
  "compression": { … 见下文 }
}
```

### 接入点类型

| type | 协议 | 适用 |
|---|---|---|
| `openai` | `POST {base_url}/chat/completions`，`Authorization: Bearer` | GPT、DeepSeek、绝大多数中转站。OpenAI 新模型把 `max_tokens_field` 设成 `max_completion_tokens` |
| `anthropic` | `POST {base_url}/v1/messages`（base_url 已以 `/v1` 结尾时不重复加），`x-api-key` + `anthropic-version` | Claude 官方或 Claude 中转。默认模型 `claude-opus-5-5`，`extra_body` 里带 `output_config.effort` |
| `antigravity` | `POST {base_url}/v1beta/interactions`，`x-goog-api-key` | Google Antigravity 托管代理（Gemini API 的 Interactions 接口）。`model` 填代理 id（如 `antigravity-preview-09-2026`），`environment` 默认 `remote`；内置的代码执行/搜索工具默认关闭（`builtin_tools` 可开） |

回复被输出上限截断（`max_tokens` / `length`）时，其中的工具调用一个都不执行（参数可能是半截指令）。

通用可选字段：`auth_style`（`default`/`bearer`/`x-api-key`/`x-goog-api-key`，中转站鉴权方式不同时改）、`extra_headers`、`extra_body`（合并进请求体顶层）、`max_tokens`、`temperature`（不填就不发送；Claude 新模型不接受）、`timeout_seconds`（默认 180；超时不重试——单步最长就是这么久；429/5xx 重试 2 次，带 `Retry-After` 且不超过 30 秒时按它等，更长就直接失败交给备用接入点；接口返回 3xx 跳转时不跟随、直接报错，免得把密钥带去别的主机）、`system_prompt_file` / `custom_prompt_file`（默认 `prompts/<接入点>.system.md` / `.custom.md`）。

## rules.json：三层限制规则

```jsonc
{
  "permissions": {                     // —— 权限 ——
    "admin_permission_level": 3,       // 谁能用 /aibot
    "allow_commands": true, "allow_chat": true, "allow_private_chat": true,
    "disabled_tools": [],              // 例如 ["read_file", "list_files"]
    "command_permission_level": 4,     // 代理执行指令的权限等级
    "echo_to_ops": true,               // OP 能看到灰色的"[AI管家: 执行了…]"
    "deny_roots": ["stop", "op", "deop", "ban-ip", "pardon-ip", "save-off", "reload", "debug", "jfr", "perf", "publish", "transfer", "aibot"],
    "deny_patterns": ["^whitelist\\s+off\\b", "^kill\\s+@e(\\s|$)", "\\bkill\\s+@a(\\s|$)",
                      "^gamerule\\s+(logAdminCommands|sendCommandFeedback|commandBlockOutput)\\b", "^neoforge\\s+generate\\b",
                      "click_?event", "command_block", "\\bCommand\\s*:"],
    "allow_only_roots": [],            // 非空 = 白名单模式
    "expose_player_ips": false,
    "file_deny": ["**/*.jks", "**/*.pem", "**/*.key", "**/*.p12"],
    "file_read_max_bytes": 65536
  },
  "quota": {                           // —— 额度管理 ——
    "enabled": true,
    "per_run": { "max_steps": 12, "max_commands": 10, "max_chat": 4, "max_chat_length": 200 },
    "windows": [ { "period": "hour", "max_runs": 20 }, { "period": "day", "max_runs": 200, "max_tokens": 0, "max_cost": 0 } ],
    "count_admin_runs": true,
    "block_admin_when_exhausted": false,
    "warn_at_percent": 20,
    "alert_in_game": true,
    "balance_check_minutes": 30,
    "provider_balance": { "deepseek": { "warn_below": 5, "stop_below": 1 } }
  },
  "schedule": {                        // —— 启停限制 ——
    "active_hours": [],                // 例如 ["08:00-02:00"]，可跨午夜；空 = 全天
    "active_weekdays": [],             // 1 = 周一 … 7 = 周日；空 = 每天
    "startup_delay_seconds": 120,
    "min_online_players": 0,
    "pause_after_consecutive_failures": 3,
    "failure_pause_minutes": 30,
    "admin_bypass": true               // 管理员提问不受启停限制
  }
}
```

- **黑名单检查**会把 `execute … run …`、`return run …` 里嵌套的指令逐层拆出来查（每层只查一次，最多 16 层，指令最长 8192 字符），`minecraft:stop`、`/STOP` 等写法一样会被拦。被拒绝的指令记进审计日志。
- 默认还禁止**间接执行**：带点击事件的文本（`tellraw`/书/告示牌的 `clickEvent` 会以点击者——可能是 OP——的权限执行指令）、放置/召唤/修改命令方块。黑名单无法穷举所有危险写法（例如 `execute as @a run kill @s`），对安全要求高的服可以改用 `allow_only_roots` 白名单。
- **代码层硬限制**（改 rules.json 也去不掉）：`kill @e` 必须用 `type=` 排除玩家（`@e[type=minecraft:item]`、`@e[type=!player]`），`kill @a` / `@r` 必须用 `name=` 指定玩家（`kill @e[]`、`kill @e[distance=0..]` 这类换写法都拦）；`data modify/merge` 不能改命令方块（含命令方块矿车）的 `Command` / `auto`；任何地方出现 `aibot remote` 都拒绝。
- **额度**按自然时段（本地时区）统计：运行开始时 +1 次，结束时加 token 和估算花费（单价 × token，压缩摘要的调用也算）。某项剩余低于 `warn_at_percent` 时提醒一次，用完时自动运行暂停到时段结束；管理员提问默认不受限（但计数）。
- **余额**：`balance_api` = `deepseek`（`GET /user/balance`）或 `newapi`（one-api / new-api 类中转的 `/v1/dashboard/billing/*`）。低于 `warn_below` 提醒；低于 `stop_below` 停用该接入点（有 `fallback_providers` 就改用备用），充值后下次查询（或 `/aibot quota`）自动恢复。
- 提醒发到后台日志，`alert_in_game` 时同时发给在线 OP。

## 上下文压缩（config.json 的 `compression`）

```jsonc
"compression": {
  "enabled": true,
  "when":   { "input_tokens_over": 60000, "context_chars_over": 150000, "every_steps": 0 },   // 时机：任一满足即压缩，0 = 关
  "method": { "type": "digest",               // digest = 机械摘要（不花 token）；summarize = 让模型写摘要（失败退回 digest）
              "keep_recent_steps": 2,         // 最近几轮的工具结果保留原文
              "old_result_chars": 300,        // 更早的结果只留前 N 字
              "summary_provider": "",         // summarize 用的接入点，空 = 当前；可填便宜的
              "summary_prompt_file": "prompts/compress.md",
              "summary_max_chars": 3000,
              "max_per_run": 3 },
  "memory": { "enabled": true, "compress_when_over": 30, "keep_recent": 10,   // compress_when_over 最大 49（记录最多存 50 条）
              "type": "summarize", "prompt_file": "prompts/memory-compress.md", "summary_max_chars": 1500 }
}
```

- **运行内**：工具结果越积越多时，把已有进展压成摘要，再以"原任务 + 进展摘要 + 已执行动作清单"**重新开一个会话**继续（而不是改写历史：Claude 新模型会校验历史里的思考块，Antigravity 的历史在服务端）。两次压缩至少隔 2 轮，每次运行最多 `max_per_run` 次。
- **跨运行**：行动记录超过 `compress_when_over` 条时，把较旧的合并进"长期记忆摘要"，以后每次运行都带上。

## 提示词文件

`prompts/<接入点>.system.md` 和 `.custom.md`：每次运行重新读取，改完立即生效。`<!-- -->` 注释不会发给模型；`{server_name}`（MOTD）、`{bot_name}` 会被替换。自定义提示词去掉注释后为空就不追加。删除文件后 `/aibot reload` 会按默认内容重新生成。

## 指令

| 指令 | 作用 |
|---|---|
| `/aibot ask <内容>` | 向 AI 管理员提问或下达任务（回复发回提问者） |
| `/aibot askvia <接入点> <内容>` | 用指定接入点 |
| `/aibot status` | 状态、当前接入点、排队、启停/额度状态、上次运行 |
| `/aibot quota` | 各时段剩余额度 + 各接入点余额（实时查询） |
| `/aibot patrol` | 立即巡检一次 |
| `/aibot on` / `off` | 开关自动运行（巡检、事件；重启后恢复） |
| `/aibot cancel` | 取消当前任务 |
| `/aibot resume` | 解除"连续失败暂停" |
| `/aibot provider [名字]` | 查看 / 临时切换接入点（reload 或重启后恢复） |
| `/aibot reload` | 重读 config.json、rules.json，补齐提示词文件，按新设置重启远程接口 |
| `/aibot chat [off\|mention\|custom\|all]` | 查看 / 临时切换聊天回复模式（reload 或重启后恢复） |
| `/aibot talk [quiet\|normal\|chatty\|reset]` | 查看 / 切换话量：安静 / 正常 / 话痨（会记住），见"玩家互动" |
| `/aibot mode [模式\|reset]` | 查看 / 切换预设模式（会记住，重启后仍有效；reset 回到设置文件的值），见下文"预设模式" |
| `/aibot prank [chat_only\|harmless\|rough\|reset]` | 恶作剧档位：只动嘴 / 无害整蛊 / 较狠 |
| `/aibot remote` | 远程管理接口状态（监听地址或等待原因、OAuth 是否启用） |
| `/aibot remote approve [确认号]` | 不带参数：列出待批的 OAuth 授权请求；带确认号：批准批准页上显示这个号的请求（需要权限等级 4，控制台和 RCON 都是 4；代理永远不能执行 `aibot remote`） |
| `/aibot remote clients` | 已登记的 OAuth 客户端（名字、回调主机、最近拿令牌时间） |
| `/aibot remote revoke <client_id>` / `revoke-all` | 吊销一个 / 全部 OAuth 客户端及其令牌（固定令牌不受影响） |
| `/tpa`、`/tpah` | 玩家传送请求，见下文"传送请求" |
| `/goal` | （所有玩家）当前阶段、支线、个人目标和进度 |
| `/goal next` | 下一步做什么：优先个人目标，否则当前阶段；附合成路线和还缺的东西 |
| `/goal plan <物品> [数量]` | 按自己的背包算整条合成链路 |
| `/goal add <物品> [数量] [备注]` / `remove <序号>` / `clear` | 管理个人目标（凑够数量自动祝贺） |
| `/goal stages` | 全部阶段（✔ 已完成、➜ 当前） |
| `/goal find <名字>` | 按名字查物品 ID（原版物品用英文名） |
| `/goal hints on\|off` | 开关自己的定时目标提示 |

## 预设模式（config.json 的 `persona`）

机器人的说话风格，**全服同一时间一个**，管理员用 `/aibot mode <模式>` 切换（会记在 `aiadmin/persona.json`，重启后仍有效；`/aibot mode reset` 回到 `persona.mode`）。模式只改变说话的语气和方式，不会让机器人主动多做事：巡检、事件、管理原则、隐私要求都照旧。

| 模式 | 风格 |
|---|---|
| `normal` | 默认，不加风格 |
| `helper` 帮助模式 | 热心的新手向导：步骤、材料、机制讲清楚，提醒常见的坑，不嘲笑任何问题 |
| `roast` 吐槽模式 | 毒舌但心软：先吐槽一句再给真正有用的回答；对事不对人，不碰外貌、性别、地域、现实生活，不说脏话，对方不高兴或紧急求助时收起吐槽 |
| `prank` 恶作剧模式 | 捣蛋鬼：有人找它说话时，大约三次里顺手整蛊一次说话的人或旁边的人，整完说明是玩笑；对方说"别整我"就停 |

每个模式的文字在 `config/aiadmin/prompts/modes/<模式>.md`，可以随意改；放一个新的 `.md`（文件名只能用小写字母、数字、`_`、`-`）就多一个模式，`/aibot mode` 会列出来。

**恶作剧档位**（`/aibot prank`，或 `persona.prank.level`）。只管"恶作剧模式下回复普通玩家聊天"时机器人执行的指令，执行前由模组强制检查（不靠模型自觉），不在名单里的指令直接拒绝；巡检、事件、管理员提问，以及聊天里有【已授权】玩家时照常：

| 档位 | 允许 |
|---|---|
| `chat_only` 只动嘴 | 不执行任何指令，只用聊天开玩笑 |
| `harmless` 无害整蛊（默认） | `playsound`、`particle`（≤500 个）、`title`/`tellraw`（屏幕大字、假消息）、`effect give` 几秒的无害效果（发光、夜视、速度、缓慢、跳跃、反胃、缓降、隐身、急迫、海豚的恩惠、水下呼吸，≤10 秒；漂浮 ≤3 秒且只能 0 级，不会摔伤）、`summon` 鸡/蝙蝠/兔子/鹦鹉/青蛙/猪/羊（不能带 NBT，每次最多 `max_summons_per_run` 只）、`give` 搞笑物品（毒马铃薯、腐肉、枯灌木、木棍…，≤16 个，不能带组件） |
| `rough` 较狠 | 无害档全部，再加：失明/黑暗 ≤15 秒、饥饿/挖掘疲劳/虚弱 ≤20 秒、中毒 ≤8 秒（中毒不会致死）、漂浮 ≤6 秒（只能 0 级）；`tp <玩家> ~x ~y ~z` 相对传送（水平各 ≤8 格、y 在 0~1，可只转视角）；`damage <玩家> <≤3>`（1.5 颗心） |

任何档位都不允许：杀死、清背包、改方块、封禁/踢人、`effect clear`、`execute` 嵌套、`@e`/`@s` 选择器。`persona.prank.immune_players` 里的玩家不会被整（名单非空时不能用 `@a`/`@p`/`@r`，必须点名）。黑名单（rules.json）照样先生效。

```json
"persona": {
  "mode": "normal",
  "prank": { "level": "harmless", "immune_players": ["服主的名字"], "max_summons_per_run": 5 }
}
```

## 和玩家聊天（config.json 的 `chat`）

```jsonc
"chat": {
  "mode": "mention",            // off 不回应 / mention 提到关键词才回 / custom 关键词或自定义条件 / all 所有聊天都看、自己判断回不回（花费最高）
  "keywords": [],               // 空 = bot_name、"@AI"、"@aibot"、"@ai管家"（包含即算、不分大小写）；例如 ["管家", "@AI"]
  "conditions": [               // custom 模式：任一满足即唤醒（关键词也照样有效），每个条件有自己的冷却
    { "type": "burst", "more_than": 5, "window_seconds": 120, "scope": "all", "cooldown_seconds": 300 },  // 2 分钟内超过 5 句（all 全服 / player 同一人）
    { "type": "question", "cooldown_seconds": 60 },                       // 问句（以 ? ？ 吗 呢 结尾）
    { "type": "regex", "pattern": "钻石|下界合金", "cooldown_seconds": 30 }, // 正则
    { "type": "players", "players": ["Steve"], "cooldown_seconds": 10, "note": "服主朋友" }  // 这些人说话就唤醒
  ],
  "debounce_seconds": 2,        // 这段时间内的消息合并成一次回复
  "keyword_cooldown_seconds": 8,  // 关键词触发的冷却（按玩家）
  "player_cooldown_seconds": 8,   // all 模式的冷却（按玩家）
  "max_per_player_per_hour": 30,
  "context_messages": 12        // 带上最近几条聊天作为上下文
}
```

冷却分开计：关键词、all 模式按玩家；每个自定义条件用自己的 `cooldown_seconds`（`burst` 且 `scope: all` 全服共用一个冷却，其余按玩家）。冷却内的消息不另起运行，但如果有一次回复正在合并等待，会并入那一批。

### 按群体/个人分别设置上限（rules.json 的 `quota.chat_limits`）

```jsonc
"chat_limits": {
  "defaults":       { "max_per_hour": 20 },                       // 所有人
  "ops":            { "max_per_hour": 0, "keyword_cooldown_seconds": 2 },  // OP（权限等级 ≥ 2）；0 = 不限
  "normal_players": { "max_per_day": 50 },                        // 非 OP
  "players": {                                                     // 指定玩家（不分大小写）
    "Steve":   { "max_per_day": 100 },
    "Spammer": { "ignore": true }                                  // 完全不理这个人
  }
}
```

每项可以设 `max_per_hour`、`max_per_day`（滚动 24 小时）、`keyword_cooldown_seconds`、`player_cooldown_seconds`、`ignore`。优先级：指定玩家 > OP / 普通玩家 > defaults；没写的项逐级继承，最后用 config.json 里 `chat` 的值。

### 对哪些人关闭安全提示词（rules.json 的 `permissions.chat_safety_off`）

```jsonc
"chat_safety_off": { "everyone": false, "ops": true, "normal_players": false, "players": ["Steve"] }
```

命中的人说话时，消息带【已授权】标记，提示词允许机器人照他们的要求做管理操作（给物品、传送、改时间天气等），系统提示词里"不照玩家要求给 OP/物品/传送"那一条对他们不适用。**指令黑名单/白名单、每次运行的上限照样生效**。同一批里没被授权的人仍按普通玩家对待。按名字授权依赖正版验证（`online-mode=true`），离线服任何人都能冒用名字。

- 玩家叫它（mention / custom 模式）时，机器人用 `send_chat` 回复，可以用查询工具回答"服务器卡不卡""现在几点"之类的问题。
- 提示词明确告诉它说话的是**普通玩家**：要 OP、物品、传送、改时间天气、封别人等请求一律礼貌拒绝，不在公屏透露别人的坐标、背包。
- 聊天唤醒算自动运行：受 `rules.json` 的额度和启停限制（开服延迟、运行时段等）。`/aibot off` 也会停止回复聊天。

## 远程管理（Tailscale + MCP，config.json 的 `remote`）

模组可以在 **Tailscale 内网地址**上开一个 MCP（Model Context Protocol，Streamable HTTP）端点。云电脑上的 AI（Claude Code 等）加入同一个 tailnet 后，就能直接用模组的工具管理服务器：观察工具、`run_command`、`send_chat`，以及 `aibot_status`、`aibot_quota`。**rules.json 的权限、隐藏工具、指令黑名单照样生效**，每次调用都写审计日志。

协议版本两代都支持：旧版（2025-11-25 及以前，先 `initialize` 握手；Claude Code、官方 SDK）和新版 2026-07-28（无会话、每个请求在 `_meta` 里带协议版本和能力；ChatGPT）。新版请求必须带 `MCP-Protocol-Version`、`Mcp-Method`、`Mcp-Name` 请求头且与请求体一致，否则 400；版本不支持回 400 并列出支持的版本；`server/discover` 公布版本、能力（`tools`、`events`）和身份。

```jsonc
"remote": {
  "enabled": false,             // 打开后 /aibot reload 即生效
  "bind": "tailscale",          // 自动找 Tailscale 网卡地址（100.64.0.0/10）；找不到时每分钟重试。也可填 127.0.0.1 或具体 IP
  "port": 25580,
  "token": "",                  // 空 = 自动生成 32 字节随机令牌，保存到 config/aiadmin/remote-token.txt
  "allowed_cidrs": ["100.64.0.0/10", "fd7a:115c:a1e0::/48", "127.0.0.0/8", "::1/128"],
  "allow_actions": true,        // false = 只读：只提供 remote/ToolAccess 只读白名单里的工具（宏、写记忆、说话、指令都不提供；没登记的新工具默认算会改动）
  "max_calls_per_minute": 60,      // 超了：tools/call 回 isError 并写明约几秒后可再试；events/subscribe、unsubscribe 回 429 + Retry-After
  "max_commands_per_hour": 120,
  "auth_fail_limit": 5,             // 同一来源窗口内认证失败（令牌错、OAuth 批准页令牌错、/token 认证错）这么多次就封禁；没带令牌不算失败
  "auth_fail_window_seconds": 600,
  "auth_block_seconds": 900,        // 封禁期间失败的请求和批准表单回 429；持有效令牌/授权码/refresh token 的请求照常放行；封禁期间的失败照样计数，每再错 30 次续封一次
  "public_base_url": "",            // 对外地址，如 https://steamdeck-server.xxxx.ts.net；OAuth 需要它，留空 = 不提供 OAuth
  "oauth": {
    "enabled": true,
    "access_token_minutes": 60,
    "refresh_token_days": 30,
    "refresh_max_days": 90,         // 从首次批准算起最长多少天必须重新批准（轮换不会延长）
    "max_clients": 20,              // 最多几个已批准的客户端（批准时检查）
    "max_unused_clients": 50,       // 最多几个注册了但还没批准的客户端（1 小时后自动清理）
    "register_per_source": 10,      // 每个来源每 10 分钟最多注册几次
    "allowed_redirect_hosts": ["chatgpt.com", "chat.openai.com", "claude.ai", "claude.com", "localhost", "127.0.0.1", "[::1]"]
  }
}
```

安全措施：只接受 `allowed_cidrs` 里的来源（默认只有 Tailscale 和本机）；`Authorization: Bearer <令牌>`（常量时间比较，令牌不进日志）；同一来源令牌错误达到 `auth_fail_limit` 次后封禁（只拦失败的请求：持有效令牌的请求照常放行，所以别人在同一个出口 IP 上故意输错，封不掉你已授权的客户端；没带令牌的请求是正常的发现流程，不算失败）；封禁期间仍在试错的来源照样计数、续封，但持有效令牌的请求永远放行（令牌 256 位随机，试不出来；别留手填短令牌）；IPv6 来源按 /64 计；请求头 10 秒内必须传完，没带有效令牌的 /mcp 读完请求头就回 401（不等请求体），OAuth 端点的请求体限 16 KiB、5 秒内传完，没带有效令牌的请求每个来源最多 3 个、全局最多 8 个同时在读（换令牌的 /token 另算、最多 4 个，不和别人的慢请求抢；处理线程 16 个），慢速发送占不住线程；认证失败日志按来源合并（窗口内只记第一条，封禁时记一条汇总）；带 `Origin` 头的浏览器请求只接受本机来源（防 DNS 重绑定；先于令牌检查，不计入认证失败）；正文 ≤ 1 MiB；旧版协议的 JSON-RPC 批量最多 20 条，新版协议（2026-07-28）不能批量，请求头写新版而请求体不带 `_meta` 的直接拒绝；设置写坏导致机器人锁定时远程调用全部拒绝；代理永远读不到 `config/aiadmin/`（包括令牌文件）。

### 经 Tailscale Funnel 暴露到公网（云端沙盒进不了 tailnet 时）

云端会话（沙盒容器）通常没法加入 tailnet，可以改用 Funnel：Tailscale 提供一个公网 HTTPS 地址，把整站转发到 Deck 本机的 `127.0.0.1:25580`。模组自己只认 `/mcp` 和 OAuth 的几个路径（`/.well-known/oauth-protected-resource`、`/.well-known/oauth-authorization-server`、`/register`、`/authorize`、`/token`），其他一律 404。

- 在 Deck 上：`bash deck-remote-setup.sh enable-funnel`（`remote.bind` 改成 `127.0.0.1`、`remote.public_base_url` 设成 `https://<本机>.ts.net`、`/aibot reload`、删掉 tailscale0 上的防火墙规则、执行 `tailscale funnel --bg --https=443 --set-path=/ http://127.0.0.1:25580`）；关闭用 `disable-funnel`（只关 443 这条，不动其他端口的 Funnel；**不要**用 `tailscale funnel reset`，会把别的转发一起清掉）。第一次开 Funnel 时 Tailscale 会给一个开启链接（HTTPS 证书和 funnel 节点属性），要在浏览器里确认。
- 对外地址：`https://steamdeck-server.<你的 tailnet>.ts.net/mcp`（443 端口）。云电脑：
  ```bash
  claude mcp add --transport http mc-admin https://steamdeck-server.<你的 tailnet>.ts.net/mcp --header "Authorization: Bearer <令牌>"
  ```
- **Funnel 下来源网段限制不起作用**：所有请求在模组看来都来自本机反向代理（127.0.0.1），全网都能连到端点，**防线是令牌 / OAuth**，加上认证失败封禁。真实来源取 `X-Forwarded-For` 的最右一个值（只在 TCP 来源是本机时才信任这个头，客户端自己伪造的左侧值不算）；如果代理没带这个头、分不清是谁，只合并日志、不封禁，免得扫描器把正常客户端一起封掉。
- **令牌泄露了怎么办**（贴进了聊天、留在云端会话记录里、换了云电脑……）：在服务器上删掉 `config/aiadmin/remote-token.txt`，再执行 `/aibot reload`，会立即生成新令牌、旧令牌失效；然后在云电脑上用新令牌重新 `claude mcp add`。如果在 `config.json` 里手写了 `remote.token`，就改这个值再 reload。
- 不用时 `disable-funnel` 关掉，公网地址就失效了。

### OAuth（ChatGPT 连接器、claude.ai 等不能填固定请求头的客户端）

ChatGPT 的自定义连接器只支持 OAuth 或"无认证"，不能填 `Authorization` 头。配了 `remote.public_base_url`（`enable-funnel` 会自动设）后，模组按 MCP 授权规范提供 OAuth 2.1：受保护资源元数据（RFC 9728）、授权服务器元数据（RFC 8414）、动态注册（RFC 7591）、授权码 + PKCE（只支持 S256）、刷新令牌。固定令牌方式照常可用，两种方式共用同一套工具权限、隐藏工具、指令黑名单和调用限额。

接入步骤（以 ChatGPT 为例）：

1. ChatGPT 里添加连接器：服务器地址 `https://steamdeck-server.<你的 tailnet>.ts.net/mcp`，认证选 **OAuth**（客户端 ID/密钥留空，它会自动注册）。
2. 浏览器跳到模组的批准页，页面会写明是哪个应用、批准后跳到哪个网站（应该是 `chatgpt.com`）。
3. 批准页上会显示一个 6 位**确认号**（例如 `K7Q2MX`）。在服务器后台或 RCON 执行 `/aibot remote approve K7Q2MX`，再回到批准页点"继续"。确认号只对应你屏幕上的这一个请求，别人发起的请求批不了。也可以在批准页直接填固定令牌，但用后台批准更安全：不用把长期令牌敲进网页。
4. 之后 ChatGPT 自动续期，不用再批准。`/aibot remote clients` 看已授权的客户端，`/aibot remote revoke <client_id>` 或 `revoke-all` 吊销。

安全设计：

- **批准必须证明是服主**：动态注册对谁都开放，所以批准要么在服务器后台执行 `/aibot remote approve <确认号>`（确认号绑定批准页上的这一个请求；指令要求权限等级 4，代理的 `run_command` 在代码层被禁止执行 `aibot remote`，不依赖黑名单），要么在批准页输入固定令牌；没有"谁都能点"的按钮。令牌错误计入认证失败封禁，同一个授权请求最多试 5 次。批准表单只接受本站 Origin（批准页用 `Referrer-Policy: same-origin`，浏览器提交时会带上真实 Origin；跳回客户端时用 no-referrer）。refresh token 每用一次换新；30 秒内用同一个 refresh token 再来（响应丢了在重试，或连接器的多个进程同时刷新）原样拿到同一对令牌，谁都不会被踢；旧的 refresh token 过了 30 秒又出现就判定被盗、吊销这个客户端的全部令牌。批准页还显示客户端的注册时间、注册来源和本次请求来源，方便判断。
- **回调地址**：注册时只允许 `allowed_redirect_hosts` 里的主机（https，本机地址可以 http），防止有人注册一个跳到自己网站的客户端再骗你批准；授权时 `redirect_uri` 必须和注册时完全一致，不对就只显示错误页、不跳转。
- **授权码**：60 秒过期、只能用一次（哪怕校验失败也作废），绑定客户端、回调地址和 PKCE。
- **令牌**：access token 默认 60 分钟，只在内存里存哈希（重启后客户端用 refresh token 自动续）；refresh token 默认 30 天，存哈希到 `config/aiadmin/oauth-state.json`（创建时就是 660 权限，代理的文件工具永远读不到这个目录），每用一次换新的，但从首次批准算起最多 `refresh_max_days`（默认 90 天）就必须重新批准。已经换掉的旧 refresh token 在 30 秒内再出现当作网络重试（作废上一次签发的那一对、重新签发）；超过 30 秒再出现当作被盗，立即吊销这个客户端的全部令牌（这份记录也存在状态文件里，重启后照样认得）。
- **批准页**：禁止被嵌进别的网页（`X-Frame-Options: DENY`、CSP `frame-ancestors 'none'`），表单只能提交到本站和该客户端的回调网站；授权参数存在服务端，表单只带一个随机请求号；POST 还校验 `Origin`。每个来源最多 10 个待批请求（满了淘汰这个来源最早的），外人挤不掉你正在批的请求。
- **注册限流**：请求体最大 16 KiB、最多 5 个 `redirect_uris`；每个来源每 10 分钟 `register_per_source` 次；"注册了但还没批准"的客户端最多 `max_unused_clients` 个、1 小时后自动清理，满了淘汰注册超过 10 分钟的最旧一个；已批准的客户端最多 `max_clients` 个（批准时检查）。没有全服共享的次数上限，共享出口 IP 上的其他人耗不掉全服的额度。
- 令牌、授权码、client secret 都不写日志；审计日志只记 client_id、客户端名和来源，工具调用记成 `来源 oauth:客户端名 [client_id]`。

### 云端值守（config.json 的 `remote.cloud`）

让云端 AI 不只是"被叫来干活"，而是 24 小时值班：**云端优先，本地兜底**。

```json
"remote": {
  "enabled": true, "allow_actions": true,
  "cloud": {"enabled": true, "route": ["chat", "event", "patrol"], "online_timeout_seconds": 120,
            "handoff_seconds": 60, "default_wait_seconds": 55, "max_wait_seconds": 300,
            "max_waiters": 2, "max_pending": 100, "auto_close_minutes": 15,
            "webhook_handoff_seconds": 150, "webhook_hosts": [], "webhook_ttl_hours": 24, "webhook_max_ttl_days": 30,
            "power_enabled": false}
}
```

- **云端在线** = `online_timeout_seconds`（默认 120）秒内调用过云端值守工具（`wait_events`、`reply_chat`、`run_command_for`、`complete_item`、记忆和管理工具），或者正有 `wait_events` 挂着。只查状态、查资料的普通工具调用不算——服主偶尔用连接器查一下，不会让玩家聊天去等云端。
- 云端取走一条消息后 2×`handoff_seconds` 秒内一个动作都没有（多半是客户端已经断开、回应没送到），这条收回交给本地 API。
- 云端在线时，`route` 里的触发（`chat` 玩家聊天、`event` 进服/死亡/卡顿/报错等事件、`patrol` 定时巡检）放进收件箱，**本地 API 不运行、不花钱、不占本地额度**；
  启停限制（时段、星期、人数）照样生效，`/aibot off` 后两边都不处理。管理员 `/aibot ask` 永远走本地。
- 放进收件箱 `handoff_seconds`（默认 60）秒还没被云端取走 → 交给本地 API 处理（只交一次），玩家不会没人理；云端掉线后自然全部回到本地。
- 云端取走后 `auto_close_minutes`（默认 15）分钟还没 `complete_item` → 自动关闭，已执行的动作照样记进记忆。
- 需要 `remote.enabled` 和 `remote.allow_actions` 都为 true（云端要能回复），否则不交给云端。

云端的工作循环（MCP 工具，只经远程接口提供，本地 AI 看不到）：

| 工具 | 作用 |
|---|---|
| `get_bot_prompt` | 开工时读一次：和本地 AI 相同的系统提示词（服规、性格、补充规则）+ 当前预设模式 |
| `wait_events` | 等收件箱：有消息立即返回，没有就等 `timeout_seconds`（默认 `default_wait_seconds`）再返回空；同时是心跳。每条消息带 `item_id` 和 `instructions`（和本地 AI 收到的触发说明同一份：聊天原文、上下文、【已授权】标记、怎么回应） |
| `reply_chat` / `run_command_for` | 针对某条消息说话 / 执行指令（带 `item_id`）：调用本地同一个 `send_chat` / `run_command`，用那条消息的上下文，所以**黑名单、恶作剧档位、授权规则、每次运行的条数上限和本地完全一样** |
| （其他工具 + `item_id`） | 为某条消息调用其它会改动的工具（`set_player_pref`、`run_macro`、`send_chat` …）时在参数里加 `item_id`：按那条消息的身份检查（玩家聊天时只能改本人偏好、宏要 `from_player_chat`）；不带就是服主远程身份。查询带上 `item_id` 会刷新这条消息的活动时间。取走后 2×`handoff_seconds` 秒内对它没有任何动作的，当作云端已断线、交给本地 AI |
| `complete_item` | 处理完写一句总结，进共享的行动记录 |
| `memory_read` / `memory_note` | 读写和本地 AI 共用的长期记忆、行动记录 |
| `bot_control` | 开关自动运行、切聊天模式 / 预设模式 / 恶作剧档位、解除失败暂停、取消本地任务（OAuth 批准、远程接口设置不提供） |
| `host_info` / `server_power` | 宿主机接口：只读动作（`status`、`check`、`host`、`logs` …）/ 会改动的动作（`backup`、`restart`、`stop` …），动作清单由宿主机提供；默认关，见下面"宿主机接口" |
| `get_settings` / `set_setting` | 读改服主允许的设置项（`remote.settable_settings`），写进 config.json 并立即生效，见下面"云端改设置" |

- `wait_events` 的等待时长要**小于云端客户端的工具调用超时**，也要小于反向代理（Funnel）的空闲超时；不确定就用默认 55 秒。
- 同时挂起的 `wait_events` 最多 `max_waiters`（默认 2）个，每个占远程接口一个工作线程。
- 云端用普通 `run_command` 做的事不按某条消息的上下文检查（按远程管理身份），所以处理玩家消息时要用 `run_command_for`；远程每小时指令上限两者合计。
- `/aibot status` 显示云端在线情况和收件箱统计（待取、处理中、已完成、转本地、超时）。

#### 通用入口（工具表不用再刷新）

ChatGPT 连接器拉取过工具表后不会自己更新，点"刷新"也不一定生效，所以服务器新加的工具它看不到。远程接口因此固定提供三个通用入口，以后加工具不用重装连接器：

| 工具 | 作用 |
|---|---|
| `list_tools` | 列出当前全部工具和读写类别（`read` / `action`）；带 `name` 给完整说明和参数 |
| `call_read_tool` | `{"name": 工具名, "arguments": {...}}` 调用只读工具；会改动服务器的工具在这里一律拒绝 |
| `call_action_tool` | 同样格式调用会改动服务器的工具；`remote.allow_actions=false` 时隐藏 |

- 通用入口拆出真正的工具名后走和直接调用**同一条路径**：服主隐藏/禁用的工具调不到，黑名单、每小时指令上限、在线人数检查、心跳和审计都一样；整次调用只算一次调用频率；不能嵌套调用通用入口。`arguments` 被客户端序列化成字符串也接受。
- 读写类别由 `remote/ToolAccess.java` 的只读白名单决定，没登记的工具（包括新加的）一律算 `action`：最多多一次确认，不会把写操作放进只读入口。
- `tools/list` 里每个工具都带 `annotations.readOnlyHint`（同一份分类），ChatGPT 据此决定调用前要不要让你确认。

#### 宿主机接口（`host_info` / `server_power`，`remote.cloud.power_enabled`，默认关）

云端可以看主机状态和日志、不停服备份、重启、停服。模组只转发"动作名 + 最多 3 个参数"（固定字符集，不经过 shell）；**能做什么由宿主机上 root 拥有的 `/usr/local/sbin/aiadmin-power` 决定**（源码 `scripts/deck-power/`）：它的 `actions` 列出每个动作是只读（`read`，走 `host_info`）还是会改动（`action`，走 `server_power` 且必须写 `reason`），以后加动作只改这个脚本，不用改模组、不用重启。sudoers 只放行服务端用户执行这一个程序，参数由程序统一校验（字符集、长度、个数、动作名），内部的 `__run` 只接受 root 直接调用，云端被提示注入也拿不到 shell。

| 动作 | 类别 | 作用 |
|---|---|---|
| `status` | read | 当前运行的服务器、现有备份（定时 3 份 + AI 手动 2 份）、最近的启停/备份记录 |
| `check` | read | 现在能不能重启/停服 |
| `host` | read | 负载、内存、磁盘、温度、各服务状态、MC 在线人数、Terraria 连接数 |
| `logs <mc\|aiadmin\|crafty\|frp\|terraria\|system> [行数≤200]` | read | 看日志（密钥、密码类值脱敏，每行截到 600 字符） |
| `backup` / `restart` / `stop` | action | 见下 |

- **重启 / 停服**：只动当前运行的服务器；模组先看玩家表，宿主机程序再用 RCON `list` 数一次（不用 TCP 连接数：内网穿透会挂着空闲连接），两边都得 0 人；60 分钟最多 3 次、间隔至少 5 分钟；10:00、22:00 定时备份前 5 到后 15 分钟不执行；真正的停/启放进 `systemd-run` 的独立单元里延迟 8 秒执行（调用方就是要被停掉的进程，得先把结果回给云端），动手前再数一次人。
- **停服之后云端就连不上了**（远程接口在游戏服务端里），只有服主能再开；重启约 2~4 分钟后云端重连即可。
- **备份**：触发 Crafty 的第二份备份配置"AI manual backup"（不停服、排除 Distant Horizons 库、只留 2 份，和定时备份各自轮换，不会挤掉定时备份）；有人在线也可以；2 小时最多 1 次；剩余空间要够"服务器目录 × 1.2 + 16 GiB"。TCN2 实测约 1 分钟、1.85 GB。
- backup / restart / stop 必须写 `reason`，结果记进共享行动记录（`云端：服务器备份/重启/停服`）和日志事件 `agent/cloud.power`；宿主机侧日志 `/var/log/aiadmin-power.log`。
- 安装（Deck，mcadmin）：`sudo install -o root -g root -m 755 aiadmin-power /usr/local/sbin/`、`sudo install -o root -g root -m 700 aiadmin-crafty-backup.py /usr/local/lib/`、`sudo visudo -cf sudoers-aiadmin-power && sudo install -o root -g root -m 440 sudoers-aiadmin-power /etc/sudoers.d/aiadmin-power`；在 Crafty 里给服务器加一份备份配置，把它的 ID 写进 `/var/lib/aiadmin-power/manual-backup-<服务器ID>`；然后 `config.json` 设 `remote.cloud.power_enabled=true` 并 `/aibot reload`。没装时工具只会回"宿主机接口不可用"。

#### 云端改设置（`get_settings` / `set_setting`）

`remote.settable_settings` 列出云端能改的 config.json 键（点分隔 snake_case；默认是聊天模式与冷却、巡检、各类事件开关、话量、私聊开关；清空 = 只能看不能改）。

- 改动**写进 config.json 并立即 reload**，是永久的（不像 `bot_control` 重启后还原）；改前的文件备份成 `config.json.bak-setting`（最近一次），第一次被云端修改前的原样另存 `config.json.bak-setting-original`（之后不再覆盖）。
- 只能改默认设置里已有的键，值的类型必须和原来一致（布尔 / 数字 / 文字 / 文字列表）；新内容先用正式的读取校验跑一遍，不通过就不动原文件；写入用同目录临时文件 + 原子改名，保留原文件权限和属组；同时来的几个修改排队执行，不会互相覆盖。
- 写回是重新生成 JSON：你在 config.json 里手写的 `//` 注释会丢失（原样留在 `bak-setting-original`）。
- **永远改不了**（白名单写了也不行）：`providers`、`active_provider`、`fallback_providers`、`remote.*`、`persona.*`（预设模式和恶作剧）、`compression.*`、`bot_name`、`patrol.extra_instructions`，键名某一段是 key / token / secret / password 的，最后一段带 prompt / instruction / file 的——会原样进提示词的文字被云端改写，等于把一段指令永久塞进以后每次本地运行。
- 必须写 `reason`，改动记进共享行动记录和日志事件 `agent/cloud.setting`。
- 远程接口这一节设置和令牌都没变时，reload **不再重启远程接口**（以前每次 `/aibot reload` 都会断开正在进行的请求和长轮询）。

#### 服主扩展：宏与知识文档（本地 AI 和远程都能用，改文件立即生效）

**宏** `config/aiadmin/macros.json`：

```json
{"macros": {
  "newbie_kit": {
    "description": "给新玩家发新手礼包",
    "from_player_chat": true,
    "params": {"player": {"type": "player", "description": "玩家名"},
               "n": {"type": "int", "min": 1, "max": 64, "default": "16"}},
    "commands": ["give ${player} minecraft:bread ${n}", "tellraw ${player} {\"text\":\"欢迎来到服务器！\"}"]
  }
}}
```

- AI 用 `list_macros` 查、`run_macro`（`name` + `params` 对象）执行。**每条指令都经同一个 `run_command`**：服主黑名单、恶作剧档位、每次运行的指令上限、审计照旧；远程调用时宏实际执行的条数计入 `remote.max_commands_per_hour`。某条被拒绝或失败就停下并报告。
- 参数类型：`player`（3~16 位字母数字下划线）、`int` / `number`（可设 `min` / `max`）、`word`（字母数字和 `_ : . - # /`，最长 64，适合物品 ID）、`choice`（`values` 列表之一）、`text`（最长 200 字，去掉换行、控制字符和 `§`，`\` 和 `"` 转义，所以只适合放在 JSON 字符串里）；可写 `default`。参数用 `${名字}` 引用，引用了没声明的参数，这个宏会被标成写错、不能用（其他宏不受影响）。
- 默认**回应普通玩家聊天时不能用**；`"from_player_chat": true` 的宏才行（比如发礼包）。最多 20 条指令。

**知识文档** `config/aiadmin/guides/`：放 `.md` / `.txt`（只认这一层），AI 用 `list_guides`（名字、第一行标题、大小）和 `read_guide`（可不带扩展名，每页约 6000 字分页）按需查阅；不进系统提示词，不占每次的上下文。只在目录列表里按名字匹配，写 `../` 也读不到别处。

#### 推送唤醒（MCP Events，给 ChatGPT / dots 这类不能挂长轮询的客户端）

ChatGPT 和它的 dots 不支持长轮询，但支持 **MCP Events**（MCP 的 Triggers & Events 草案扩展，webhook 投递）：客户端订阅事件 `duty.item`，服务器在收件箱有新消息时主动 `POST` 一份摘要到它给的回调地址，客户端被叫醒后再调用 `wait_events` 取走处理。有能投递的订阅时，云端就算**在线**（不需要心跳），`route` 里的触发照样进收件箱。

- 事件 `duty.item`：参数 `kinds`（可选，只订阅 `chat` / `event` / `patrol` 中的几种）；推送体 `data` 只有 `item_id`、`kind`、`title`、`summary`（摘要，玩家原话算数据）、`created`、`online_players`，完整说明由 `wait_events` 取。
- 订阅前服务器先向回调地址发一个签名挑战，对方 2xx 并原样回显才建立；投递按 Standard Webhooks 签名（`webhook-id` / `webhook-timestamp` / `webhook-signature` 头，HMAC-SHA256，密钥是客户端给的 `whsec_…`）。
- 推送送达后这条消息最多等 `webhook_handoff_seconds`（默认 150）秒让云端来取（云端代理从被叫醒到调用 `wait_events` 要一会儿），没推送或推送失败的仍按 `handoff_seconds` 交本地。
- 投递失败按 10 秒 / 1 分钟 / 5 分钟退避重试共 4 次；`410` / `413` 不重试；连续 10 条都投不出去就暂停这个订阅（客户端重新订阅恢复）。每次投递（含读回应）最多 15 秒、回应最多读 16 KiB；关掉远程接口或重读设置后，排队中的投递全部丢弃。
- 到期的订阅在下次订阅时清掉；某个客户端的名额满了时，先删掉它已暂停的订阅（回调地址换过、旧订阅没退的情况不会一直占名额）。
- 安全：回调地址必须 `https` 且是公网主机（本机、内网、Tailscale 段一律拒绝），不跟随重定向；`webhook_hosts` 可以把回调主机限制成白名单（如 `["chatgpt.com", "*.openai.com"]`，`*.` 匹配子域名；空 = 任何公网主机）；订阅归属发起它的令牌/OAuth 客户端，客户端被吊销时订阅一起删；订阅总数最多 20、每个客户端最多 5；有效期 `webhook_ttl_hours`（默认 24 小时，上限 `webhook_max_ttl_days`），客户端到期前会自己刷新。
- 订阅和签名密钥存在 `config/aiadmin/mcp-events.json`（660，密钥必须明文才能签名，别外传）；`/aibot remote events` 列出订阅；日志事件 `remote.events_subscribe` / `events_delivered` / `events_retry` / `events_failed` / `events_suspended` / `events_unsubscribe`。

### 设置步骤

Deck 上可以直接用脚本 `scripts/deck-remote-setup.sh`（拷到 Deck 的 home 下执行）：

```bash
bash deck-remote-setup.sh status
```

依次是 `install`（装 Tailscale 并打印登录网址，登录要你本人确认）→ `enable`（只在 `tailscale0` 网卡上放行 25580、打开 `remote.enabled`、`/aibot reload`）→ `info`（打印云电脑接入命令；加 `--show-token` 才显示令牌）。`disable` 反向关掉。脚本只改 `config.json` 的 `remote` 一节，改前备份，不打印 API key；重复执行没有副作用。手动操作的步骤如下：

1. **服务器（Deck）装 Tailscale 并登录**（登录要你本人在浏览器里完成）：
   ```bash
   curl -fsSL https://tailscale.com/install.sh | sh
   ```
   ```bash
   sudo tailscale up
   ```
   命令会打印一个登录网址，用你的 Tailscale 账号打开确认。`tailscale ip -4` 能看到本机 `100.x.y.z` 地址。
2. 在 `config/aiadmin/config.json` 里把 `remote.enabled` 改成 `true`，执行 `/aibot reload`，再 `/aibot remote` 应显示 `已监听 http://100.x.y.z:25580/mcp`。
3. 在服务器上取令牌：`config/aiadmin/remote-token.txt`（只给云电脑用，别贴到聊天或公开的地方）。
4. **云电脑**装 Tailscale、登录同一个账号（或被邀请进同一个 tailnet），然后把接口加给 AI。以 Claude Code 为例：
   ```bash
   claude mcp add --transport http mc-admin http://100.x.y.z:25580/mcp --header "Authorization: Bearer <令牌>"
   ```
   之后在 Claude Code 里直接说"看看服务器在线情况""把天气调成晴天"即可。
5. 建议在 Tailscale 后台用 ACL 只允许云电脑访问 Deck 的 25580 端口。
6. **ChatGPT / dot 24 小时值守**（要先按上文开 Funnel 整站转发和 `public_base_url`）：ChatGPT 设置里打开开发者模式 → 插件 → 添加自定义 MCP 服务器，地址填 `https://<你的域名>/mcp`，认证选 OAuth → 批准页出现确认号后在后台执行 `/aibot remote approve <确认号>` → 在 dot 的资料页把这个插件启用给它（dot 复用 ChatGPT 账号里已连好的插件，不要让 dot 自己再授权一次）→ 对 dot 说"监控这个服务器的 `duty.item` 事件，收到后先 `get_bot_prompt` 读规矩，再用 `wait_events` 取走消息处理，处理完 `complete_item`"。之后 `/aibot remote events` 能看到它的订阅，`/aibot status` 显示"在线（推送）"。

## 代理能用的工具

云端值守（只经远程接口提供，见上文"云端值守"）：`wait_events`、`reply_chat`、`run_command_for`、`complete_item`、`memory_read`、`memory_note`、`get_bot_prompt`、`bot_control`、`host_info`、`server_power`、`get_settings`、`set_setting`；通用入口 `list_tools`、`call_read_tool`、`call_action_tool`。

服主扩展（本地和远程都能用）：`list_macros`、`run_macro`、`list_guides`、`read_guide`。

观察（只读）：`get_server_status`、`list_players`、`get_players_overview`、`get_player_activity`（见下一节）、`get_player`（背包含附魔/耐久/潜影盒内容、末影箱、效果、统计）、`get_recent_activity`（聊天、进出、死亡、成就、玩家指令）、`get_server_log`、`get_entities`（卡服排查：实体最多的区块）、`get_world_info`（游戏规则、边界、种子、数据包）、`get_access_lists`、`list_mods`、`list_files`、`read_file`（支持 .gz 日志，密码类配置值自动隐藏）。

游戏资料（只读，见"游戏资料与合成链路"）：`item_search`、`recipe_for`、`recipe_uses`、`tag_members`、`item_sources`、`loot_table`、`craft_plan`；目标：`goal_status`（只读）、`set_player_goal`（只改玩家的个人目标清单）。

玩家互动（见"玩家互动"）：`get_player_prefs`、`set_player_pref`（玩家聊天唤醒时只能改说话者自己的）、`get_commands`（指令列表、谁能用、参数格式；原版指令带现成资料，模组指令带服务器指南的说明和学到的笔记）、`note_command`（用过模组指令后记笔记）、`get_death_story`、`list_projects`、`set_project`、`who_built`。

影响服务器的动作（仅这两个）：`run_command`（控制台身份执行指令，返回输出）、`send_chat`（全服公告或私聊）。

## 游戏资料与合成链路

开服时（以及每次数据包 `/reload` 之后）模组从运行中的服务端读一份**游戏资料快照**：全部物品（英文名 + 模组 jar 自带的中文名）、全部配方、物品标签、全部战利品表。线上整合包大约 2000 多条配方、1000 多张战利品表，建一次几百毫秒，日志里有一行"游戏资料已建立"。

- **配方**：每条配方用它自己的编码器转成 JSON 再解析，所以原版工作站和模组机器（Create 的搅拌/压制/粉碎等）走同一套逻辑，能读到原料（含标签、数量、液体）、产物（含概率产出）、有序合成的摆法和机器的额外要求（加热、时间）。个别写法特殊的配方只能读到产物，染色/复制之类的特殊配方只记类型。**有原料槽读不出来的配方**（模组自定义的原料写法）标为"原料不全"，`recipe_for` 会注明"有 N 个原料槽读不出来"，合成规划不会拿它说"已备齐"；开服日志的警告里有这类配方的条数。少数模组的特殊字段按序列化器的命名空间单独适配（Domum 切割机、MCTradePost、JustEnoughBreeding）。
- **掉落**：战利品表展平成"哪张表、掉什么、几个、单次抽中的大致概率、条件（玩家击杀/精准采集/剪刀/作物成熟…）、加成（时运/抢夺）"。标签条目按原版语义算：`expand` 为真时标签里每种物品各自是一个条目（"每种各自抽取"），为假时抽中这一条每种各给一个（"每种各给一个"）。内联的子表叠加外层的概率和条件；有算不出概率的条件时整条注明"概率算不出"。引用其它表的条目（钓鱼的鱼/垃圾/宝藏子表）展开进来，子表物品的概率乘上引用条目的选中概率（钓到鳕鱼约 51%），最多 6 层、防环，同一子表被多处引用时分别列出；带 quality 的条目按幸运 0 计算，池里只有一条带条件的条目时按它也在场计算（都在说明里注明）。组合条目（alternatives / group / sequence）按原版语义展开，子条目各带权重进入池的总权重。不含 NeoForge 全局战利品修改器在运行时追加的掉落、代码写死的掉落、村民交易。
- **药水**：配方产物、原料和战利品函数（`set_potion`、`set_components`）里的药水内容都读出来，同一物品不同药水用变体键区分（如 `minecraft:tipped_arrow[potion=minecraft:poison]`）；`recipe_for`、`item_sources`、`craft_plan` 有可选参数 `potion`（可模糊写 `poison`）。酿造台配方（原版 PotionBrewing 和 NeoForge 的酿造配方）开服时枚举进快照，按"3 瓶 + 1 份材料 → 3 瓶"记；原版药箭是代码实现的特殊配方，按药水展开成各自的配方。开服日志里有"酿造台 N 条"。
- **搜索**：`item_search` 可以带命名空间（`create:andesite`、只写 `create:` 列出这个模组的物品），不带时只比 ID 的路径部分和名字。
- **合成链路**（`craft_plan`、`/goal plan`）：从目标物品往下展开，每个物品选一条配方——优先背包里已经凑得齐的、避开"方块↔锭""锭↔粒"这类互转、优先工作台/熔炉、避开要精准采集的原料；能直接挖到的东西（煤、粗铁）如果配方只剩拆方块或烧矿石，就记为"直接采集"。子原料做完再做上一层，同一配方多处用到合并次数，多做出来的（4 个木板用 3 个）留给后面用。结果给出步骤、还缺的原料和去哪弄、需要的液体、做完剩下的东西；只能概率产出的配方按期望值算次数、显示为"×约 N"，同等条件下优先确定产出的配方；数量极大时封顶并注明。层数上限 12、节点上限 4000，超出会注明。想换做法：`prefer_recipes` 指定配方，`avoid_types` 排除类型，`vanilla_only` 只用原版工作站。
- 中文名：服务端只有模组自带的中文名，原版物品没有中文名（原版中文语言文件只在客户端）。AI 认识原版中文名，查之前会换成英文名或 ID；玩家用 `/goal find` 时请用英文名。

## 玩家互动

让 AI 知道"玩家正在做什么、为什么、希望机器人怎么参与"，而不只是坐标和背包。

**聊天频道**：送给 AI 的每句聊天都带标记——`[@你]` 公屏上叫了机器人的话，`[公屏]` 没叫它的话（多半是玩家之间在聊，AI 被告知不要当成请求），`[私聊你]` 玩家用 `/ai` 私下说的，`[你·公屏]` / `[你私聊他]` 机器人自己说过的。机器人私聊别人的话不放进公屏的上下文。玩家之间的 `/msg`、`/tell`、`/w`、`/teammsg` 等**只记"谁私聊了谁"，不记内容**。

**/ai 私聊**（所有玩家可用，`social.private_chat`）：`/ai <内容>` 私下问机器人，回复只私聊给他（代码强制：不填收件人也只发给他，发给别人会被拒绝）；和公屏聊天共用每人每小时/每天上限。内容以 `quiet`、`style`、`avoid` 等子命令开头时用 `/ai ask <内容>`；一批合并等待时最多 10 条，再多会提示等回复后再说。

**互动偏好**（`aiadmin/player-prefs.json`）：

| 指令 | 作用 |
|---|---|
| `/ai style normal\|roast_ok\|serious` | 正常 / 欢迎调侃 / 只想认真答疑 |
| `/ai avoid <话题>`、`/ai unavoid <话题>` | 不想被提的话题（每人最多 10 个） |
| `/ai quiet [小时]`、`/ai resume` | 让机器人别主动找他、别在公屏提他（直接 @它或 `/ai` 照样回答）；默认 `social.quiet_default_hours` 小时 |
| `/ai prefs` | 看自己的设置 |

- 玩家对机器人说"别说了""闭嘴""别烦我""shut up"之类（@了机器人，或机器人 3 分钟内刚对他说过话）→ 自动安静 `social.stop_quiet_hours`（默认 2）小时，直接回一句确认，不经过模型。玩家之间的"别说了"不算。
- **代码层强制**（不靠模型自觉）：安静中的玩家不会被私聊、不会在公屏被点名（按完整名字匹配，Tom 不会挡掉 tomorrow；他自己直接找机器人那一轮除外），他的进服/死亡/成就等事件不交给 AI，all/custom 模式下他没叫机器人的话不唤醒；发言里出现某玩家不想被提的话题且和他有关时不发；只想认真答疑、安静中、连续受挫的玩家进恶作剧免疫名单。`send_chat` 和 `run_command` 里的说话类指令（`tell`/`msg`/`w`/`tellraw`/`title`/`say`/`me`/`teammsg`，含 `execute … run` 嵌套的）都检查；本地、云端、远程调用都受限，只有管理员 `/aibot ask` 不受限。
- 说"别说了"自动安静不会缩短已有的更长安静（`/ai quiet 24` 之后再说"别说了"仍是 24 小时）；本人用 `/ai quiet` 设的时长以本人为准。
- **连续受挫**：`social.frustrated_window_minutes`（20）分钟内死亡 `social.frustrated_deaths`（3）次，之后 `social.frustrated_calm_minutes`（30）分钟内 AI 被告知收敛、别调侃，恶作剧指令不能对他执行。
- AI 也能用 `set_player_pref` 记下玩家说的"别调侃我"等；玩家聊天唤醒时只能改这一轮 @ 了机器人（或 `/ai` 私聊它）的玩家自己的——同一批里只是在公屏说话的人不算；事件和巡检里只能往克制的方向改（不能替人打开"欢迎调侃"、解除或缩短安静）。

**工程**（`aiadmin/projects.json`）：`/goal project add <build|boss|explore|farm|other> <名字>` 以当前位置为中心开一个工程（默认范围 48 格），`/goal project`（列出自己的）、`note <编号> <进展>`、`done`、`drop`、`join`、`leave`、`radius <编号> <格数>`、`here`（这一带是谁建的）。成员在范围内放的方块计入进度，跨过 100 / 500 / 1000 / 2000 / 5000 / 10000… 时发一次"工程进展"事件。聊天和事件里涉及的玩家正在做的工程会附在 AI 的提示词里。AI 用 `set_project` 帮玩家记（玩家聊天唤醒时只能替 @ 了机器人的玩家自己记；事件和巡检里只能记进展）。

**建筑账本**（`aiadmin/build-ledger.json`）：按区块记谁放了多少方块，`who_built` 和 `/goal project here` 回答"这栋房子是谁的"——按放方块多少推断，只是参考，从 1.0.0.22 起才开始记。

**有意义的事件**（`events.milestones`，默认开）：第一次造出机器或重要方块（原版信标、附魔台、下界合金装备等；模组里带方块实体的方块，排除管线/玻璃/台阶这类大量合成的）、第一次捡到稀有物品（钻石、远古残骸、下界之星、鞘翅、不死图腾、唱片……或稀有度≥稀有的模组物品）、驯服动物、击败 Boss（带一起打的玩家）、救下队友（击杀了 8 秒内把队友打到半血以下的生物）、工程进展。带"全服第一个"标记。每人每类单独冷却 `events.cooldown_seconds`。开箱、挖方块这类不推送。

**死亡的前因后果**：死亡事件里附带死前 30 秒挨了几下、谁打的（生物名/玩家名/武器）、最后一击、32 格内的队友（是否也在挨打）、掉了多少东西；之后跟进（`events.death_followup`）：回死亡点捡到东西发一次"捡回了"，5 分钟没回去发一次"掉落物大概消失了"；没有掉落物时注明"多半被墓碑类模组收起来了"。`get_death_story` 给出每一下的明细。死亡记录只在内存里，重启清空。

**服务器指南**（`config/aiadmin/server-guide.md`，缺失时生成带说明的模板）：写自定义指令的用途、参数、**时间单位**、效果持续多久、怎么恢复，谁能要求什么。去掉注释后进本地 AI 的系统提示词和云端的 `get_bot_prompt`（超过 `social.guide_max_chars` 截断），保存即生效。`get_commands` 从服务器指令树列出指令、谁能用、参数格式，并附上指南里提到这条指令的行。

**话量**（`social.chattiness`，`/aibot talk quiet|normal|chatty|reset` 临时切换并记住，状态行显示）：`quiet` 只回答直接问它的（[@你]、[私聊你]），事件不打招呼、巡检不说话；`normal` 默认；`chatty` 话痨、热心——被叫到就多聊几句、顺着话题接梗，公屏上能接梗能帮忙就插话，事件大多接一句（进服打招呼并提他的工程/目标、成就和里程碑夸一下、死亡按偏好安慰或吐槽并提醒去捡、Boss 和救人起哄），巡检时顺手解决小问题并挑一两位最近没聊过的玩家问进展、给建议（同一人 30 分钟内不重复），玩家提到想做什么就主动查资料、帮他记目标和工程。话量只改变"要不要主动说话、做事"的指引，权限、检查和玩家偏好（"别说了"、只想认真答疑）照样强制生效；想让它被更多聊天唤醒，另把 `chat.mode` 设成 `custom` 并加 `question` / `burst` 条件。云端 `bot_control` 的 `chattiness` 也能切换。

**指令资料**：原版指令（1.21.1 常用的约 50 条）有一份随模组打包的现成资料：写法、时间单位（tick / 秒）、怎么恢复、风险高低，`get_commands name=<指令>` 时附上；系统提示词里提醒 AI 改动前先记原状态。模组指令没有现成资料，AI 只在玩家或服主提出要求时才去试：先看参数格式，用最小、无害的参数试一次，确认后用 `note_command` 记下单位、效果、怎么恢复，存进 `aiadmin/command-notes.json`，以后本地和云端都能查到。"已验证"由代码判断：只有最近 30 分钟内机器人真的成功执行过这条指令（包括 `execute … run` 里嵌套的；别的模组的同名指令 `othermod:pig` 不算），笔记才标成已验证（模型自己说的、玩家在聊天里说的都不算）——它只证明这条指令能用，笔记里写的单位和效果仍是 AI 的观察；每条指令最多 10 条笔记。服务器指南里服主写的说明优先于笔记。

**隐私**：经远程接口（云端 AI）调用时默认不给玩家 IP（`remote.hide_player_ips`，rules.json 的 `expose_player_ips` 只管本地 AI）：`get_player`、`get_access_lists` 不带 IP 和 IP 封禁，`get_server_log`、`read_file`（`logs/latest.log`、`banned-ips.json` 等）和宿主机 `host_info logs` 的输出里登录地址、`"ip"` 字段换成 `[IP已隐藏]`。玩家指令写进动态（AI 能看到）时，私聊和队伍消息只记"谁私聊了谁"，登录/注册/改密码这类指令（`/login`、`/register`、`/changepassword`…）只记指令名，套一层 `execute … run` 也一样。

```jsonc
"social": {"private_chat": true, "stop_quiet_hours": 2, "quiet_default_hours": 24,
           "frustrated_deaths": 3, "frustrated_window_minutes": 20, "frustrated_calm_minutes": 30, "guide_max_chars": 6000},
"events": {"milestones": true, "death_followup": true, ...}
```

## 目标表（config/aiadmin/goals.json）

文件不存在时自动生成一份原版进度表（木器 → 石器 → 铁 → 铁镐 → 铁甲 → 钻石 → 下界 → 附魔 → 钻石甲 → 末影之眼 → 末地 → 末影龙），外加装了 MineColonies 时才出现的支线"建立殖民地"。改完保存即生效（按修改时间自动重读；检查和重读在后台线程上做，最多晚一轮检查用上新表）；写错时保留上一次的内容，并在日志和 `goal_status` 里报错。

```json
{"id": "iron", "name": "来硬的", "description": "挖铁矿，用熔炉烧出铁锭",
 "targets": [{"item": "minecraft:iron_ingot", "count": 3}],
 "done_when": {"advancement": "minecraft:story/smelt_iron"},
 "optional": false, "requires_mod": ""}
```

- `targets`：这个阶段要准备的东西，用来算合成链路和提示还缺什么（可以为空）。
- `done_when` 完成条件：`{"item": "minecraft:iron_ingot", "count": 3}`（背包+盔甲栏+副手里有；`item` 可写 `#标签`）、`{"advancement": "命名空间:进度ID"}`、`{"stat": "minecraft:used", "key": "minecolonies:blockhuttownhall", "min": 1}`（统计：used/crafted/mined/killed/picked_up/custom…）、`{"all": [...]}`、`{"any": [...]}`。
- 阶段按列表顺序推进；已完成的永远算完成（丢了东西不倒退）；当前阶段 = 最后一个已完成主线阶段之后的第一个未完成的（老玩家不会被提示去做木镐）。`optional: true` 是支线，`requires_mod` 填了而服务器没装这个模组时整条跳过。
- `personal.max_per_player`（默认 10）：每人最多几个未完成的个人目标；`announce_complete`：完成时是否全服播报（默认只告诉本人）。完成一天后的个人目标自动清掉。
- `hints`：定时私聊提示，由模组按模板生成，**不调用 AI、不花额度**。每人最多每 `interval_minutes`（默认 20）分钟一条；上线不满 `min_online_minutes`（默认 5）分钟不提示；内容和上次一样时至少隔 `repeat_unchanged_minutes`（默认 60）分钟；玩家可以 `/goal hints off` 自己关。
- `check_interval_seconds`（默认 30）：多久检查一次在线玩家的进度。

玩家跟 AI 说"我想做 XX"时，AI 可以用 `set_player_goal` 帮他记下（玩家聊天唤醒时只能改 @ 了机器人的玩家自己的目标；远程接口 `allow_actions=false` 时不能改）；问"我下一步干嘛"时用 `goal_status`。

- goals.json 写错时：字段类型不对（如 `"enabled": "false"`）能看出意思的按意思理解、看不出的用默认值，都会在开服日志和 `goal_status` 的 `table_warnings` 里提示；没写 `id` 的阶段用位置当 id 并提示（中间插阶段会让玩家进度错位，请给每个阶段写 id）。
- `aiadmin/goals-progress.json` 读坏时原文件改名为 `.corrupt-时间戳` 保留、从空开始；个别玩家记录坏了只跳过那一条并复制一份留证。

## 传送请求（/tpa、/tpah，config.json 的 `tpa`）

| 指令 | 作用 |
|---|---|
| `/tpa <玩家>` | 请求传送到对方身边 |
| `/tpah <玩家>` | 请求把对方传送到自己身边 |
| `/tpa Y [发起者]` / `/tpa N [发起者]` | 接受 / 拒绝（不写名字 = 最新的一条请求）；消息里也有可点击的【接受】【拒绝】 |
| `/tpa auto` / `/tpa manual` | 自己的设置：自动接受 /tpa（/tpah 总是要手动确认）/ 手动确认（默认）。按玩家保存在 `aiadmin/tpa-prefs.json` |

- **拒绝不通知请求者**；请求过期（默认 60 秒）也不通知请求者，只告诉接收者——否则请求者能从"没收到过期通知"推断出被拒绝。
- **请求一发出就进入冷却**（不管对方接受还是拒绝）。默认 /tpa 和 /tpah 共用 5 分钟冷却；可改成各自独立。冷却只在内存里，重启清零。
- 玩家下线时，他发出和收到的请求都作废。跨维度传送可关。

```jsonc
"tpa": {
  "enabled": true,                       // 整体开关（关掉后 /tpa、/tpah 都成为未知指令）
  "tpa":  { "enabled": true, "permission_level": 0 },   // 0 = 所有玩家；2 = 只有 OP …
  "tpah": { "enabled": true, "permission_level": 0 },
  "request_timeout_seconds": 60,
  "cooldown": { "shared": true, "seconds": 300, "tpa_seconds": 300, "tpah_seconds": 300 },   // shared=false 时用后两个
  "allow_cross_dimension": true
}
```

改完 `/aibot reload` 立即生效（会给在线玩家重新下发指令列表）。这是玩家指令：后台和机器人以控制台身份执行时会提示"需要玩家"，不能替玩家发起或接受。玩家的传送操作会记进最近动态（机器人可见）。

## 玩家行为追踪

光靠日志和某一瞬间的快照，代理不知道玩家"在做什么"。模组在服务端持续记录每名玩家（只在内存里，保留最近 60 分钟；下线后一小时内仍可查）：

- **每 10 秒采样**：维度、坐标、生物群系、手持物品、血量饥饿、游戏模式、骑乘、是否在睡觉；
- **行为事件**（按分钟计数）：挖了哪些方块、放了哪些（另按区块统计"在哪一带挖/建"）、击杀了什么、被什么伤害、合成、拾取、丢出、用了什么物品；
- **交互**（带坐标）：右键了哪些能交互的方块（箱子、熔炉、门、拉杆、按钮、模组机器……没有方块实体的模组方块也算，只要它自己实现了右键逻辑）和哪些实体（村民、动物、展示框、模组生物……）。每条都写**英文名 + 注册 id + 所属模组**，例如 `Mechanical Press [create:mechanical_press · Create]`（服务端只有英文语言文件）；对着普通方块右键放方块不算交互；
- **容器存取**：打开容器时给容器格子拍快照、关闭时对比，记下"从哪个箱子/机器拿走了什么、放入了什么"（打开期间漏斗等自动进出的物品也会算进去）；
- **其他单独记录**：村民交易（付了什么得到什么）、贵重矿石（钻石/绿宝石/远古残骸/模组矿石，带坐标）、危险物品（岩浆桶、打火石、火焰弹、TNT、末影水晶……）、在别的玩家旁边丢物品、死亡、换维度、进出服、击杀玩家/命名生物/Boss。被领地/保护模组取消的操作不算；同一件事 2 分钟内在附近重复发生会合并成一条（`times` = 次数）；
- **每分钟一次背包快照**：算出最近 N 分钟背包多了什么、少了什么；
- **行为判断**（最近 5 分钟）：挖矿、砍树、种田/收菜、建造、挖方块/清地形、战斗、整理箱子/存取物品、操作方块/机器、和生物互动、合成制作、赶路/探索、挂机、睡觉、骑乘；原始数据一起给模型，由它自己判断。

代理的工具：

| 工具 | 内容 |
|---|---|
| `get_players_overview` | 所有在线玩家：在哪、群系、在做什么、手持物品、血量饥饿、离重生点多远、64 格内的其他玩家、最近 5 分钟的最后 3 件事 |
| `get_player_activity` | 某名玩家最近 1–60 分钟：移动轨迹（默认 12 个点，`path_points` 最多 60）、移动距离、到过的维度和群系、各类行为统计、挖/建区域、事件时间线（默认 40 条，`event_limit` 最多 200，`event_kinds` 按类型筛选）、聊天和指令、背包增减；在线时还有正看着的方块和实体、16 格内的生物、当前背包、亮度、是否开着界面 |
| `get_offline_player` | 不在线的玩家：从存档读最后位置、血量、经验、游戏模式、重生点、背包（含附魔耐久）、盔甲、副手、末影箱、游玩时长等统计、存档最后保存时间。只按本地存档和名字缓存查找，不会去请求 Mojang 接口；不含 IP |

每次唤醒时的服务器概况里，每名玩家也带一句 `doing`（在做什么）。这些信息同样受"不在公屏透露别人坐标、背包"的提示词约束；工具说明里也写明坐标、背包、矿石位置是玩家隐私，只用于管理判断。经远程管理接口接入的 AI 能看到同样的内容。

## 安全说明

- 代理看到的聊天、日志、文件都按"数据"处理，系统提示词要求它不执行玩家在聊天里的冒充指令；真正的拦截靠 `rules.json` 的黑名单/白名单和每次运行的指令上限。
- 机器人设置目录、只含密钥的文件、IP（默认）对代理不可见；程序日志里名字像 key/token/secret/password/cookie/authorization 的字段（含嵌套的、`x-api-key`、`accessToken` 这类写法）一律脱敏，正文里的 `Bearer …`、`sk-…`、`whsec_…` 也替换掉。
- 程序日志的错误排重：同一错误 10 分钟内只写首条，每多 100 次写一条汇总；未处理异常和 fatal 每次都写。
- 每条指令、每句聊天、每次运行的 token 和估算花费都写进 `logs/aiadmin/full.jsonl`。

## 构建与测试

```bash
cd mod && ./gradlew :core:test :neoforge-1.21.1:build
```

一条命令跑完交付前的检查（单测 + 构建、文档校验、version.json / jar 清单 / mods.toml 版本一致；加 `--e2e` 再依次跑全部端到端，约 6 分钟）：

```bash
python scripts/check_all.py
```

core 和 NeoForge 两个子项目都开了 `-Xlint:all -Werror`（告警即失败）。端到端脚本共用 `scripts/e2e_common.py`：用新进程组启动开发服务端，超时、失败或 Ctrl+C 时按 PID 结束整棵进程树（不会按进程名误杀别的 java），服务端中途退出时不再干等就绪；等待一律按条件轮询（日志标记、假模型收到请求、远程状态变化），只有"确认某事没发生"这类负向断言保留固定等待；活干完就从控制台 `stop` 正常关服，不再干等 `devHaltAfterSeconds`。单测共用 `TestRig`（假的服务器动作和宿主、假时钟、手动推进的定时器）：调度层的合并、云端兜底这些时序由测试推进，不用真睡。

产物：`mod/neoforge-1.21.1/build/libs/aiadmin-neoforge-1.21.1-<版本>.jar`。端到端测试（真实开发服务端 + 模拟三种协议的假模型服务）：

```bash
python scripts/e2e.py
```

远程接口 OAuth 的端到端测试（真实开发服务端：发现 → 动态注册 → 授权页（确认号）→ 批准 → PKCE 换令牌 → 调工具 → 刷新轮换，另验证后台 `/aibot remote approve` 指令；装了 `mcp` Python 包时还会用官方 MCP SDK 的 OAuth 客户端跑一遍互通）：

```bash
python scripts/e2e_oauth.py
```

预设模式的端到端测试（切换模式并重启确认记住；吐槽模式进系统提示词；恶作剧无害档下模拟玩家聊天，`time set night` 被拒绝、`playsound` 放行）：

```bash
python scripts/e2e_persona.py
```

云端值守的端到端测试（脚本扮演云端：在线时拿到聊天、reply_chat / run_command_for 生效且黑名单照样拒绝、complete_item 进记忆；在线不取 → handoff 后本地接手；掉线 → 直接本地）：

```bash
python scripts/e2e_cloud.py
```

MCP Events 推送的端到端测试（脚本扮演云端客户端和它的回调接收端：新版协议 `server/discover`、`events/subscribe` 的签名挑战、玩家聊天推送到回调且签名正确、送达后 handoff 放宽、退订后不再推送）：

```bash
python scripts/e2e_events.py
```

玩家互动的端到端测试（开发服务端里的假玩家走真实的指令和事件路径：/msg 脱敏、/ai 私聊回复只发给本人、/goal project 和放方块计数、造信标、捡钻石、驯服、死亡前因后果和捡回跟进；另验证频道标记、"别说了"和新工具）：

```bash
python scripts/e2e_social.py
```

游戏资料与目标表的端到端测试（原版数据：查配方/标签/掉落/战利品表、铁镐和信标的合成链路；假玩家执行 /goal 各子指令、判定和定时提示；自动生成 goals.json）：

```bash
python scripts/e2e_gamedata.py
```

玩家行为追踪的端到端测试（开发服务端里用 NeoForge 假玩家真的挖方块、打僵尸、挖钻石矿、拉拉杆、右键猪、从箱子拿东西，再写一份离线存档，然后经远程接口查工具；和 e2e.py 共用开发服务端，不要同时跑）：

```bash
python scripts/e2e_tracker.py
```

e2e 含重启续接、接入点卡死、设置写坏三个场景。用真实 DeepSeek 实测（脚本自己从文本文件里取 `sk-` 密钥，任何输出都不显示密钥）：

```bash
python scripts/live_deepseek.py <存放密钥的文件>
```

版本唯一来源：仓库根 `version.json`。文档校验：`python Docs/validate_docs.py --root .`。

接 ChatGPT / dot 和实现 MCP 2026-07-28、MCP Events 的经验总结见 `Docs/Technical/Fable5-dot与MCP开发经验-20261008.md`。

## 已知限制

- DeepSeek 已用真实接口实测（余额查询、多轮工具调用、黑名单拒绝、聊天）；OpenAI、Claude 只用按官方格式构造的假服务测过。
- Antigravity 按官方文档（2026-10）的 Interactions 接口实现，只用假服务测过，没有用真实密钥验证；字段如有出入请看 `logs/aiadmin/error.jsonl` 里的返回内容。
- `gpt` 接入点默认模型名 `gpt-5` 只是占位，按你的账号或中转站实际模型名填写。
- 单价是估算用的参考值，请按各家官网当前价格核对；DeepSeek 的真实余额以余额接口为准。
- 只支持在线玩家的详细信息；离线玩家的存档数据不解析。
- 游戏资料：原版物品服务端没有中文名；掉落不含全局战利品修改器、代码写死的掉落和村民交易；合成链路每个物品只选一条配方，不保证是最省的做法；数据组件只解析药水内容（附魔、烟花等不解析），背包按物品 ID 汇总，带药水的原料不会从背包里扣；模组新增的药水瓶容器枚举不到它的酿造配方。
