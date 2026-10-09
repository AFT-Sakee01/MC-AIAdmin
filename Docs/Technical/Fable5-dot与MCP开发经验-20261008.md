# dot 与 MCP 开发经验（MC-AIAdmin 1.0.0.10 → 1.0.0.19）

适用版本：1.0.0.19　生成模型：Fable5　日期：2026-10-08

本文记录把 ChatGPT / dot（OpenAI 的常驻云端代理）接到这个服务端模组的 MCP 远程管理接口时踩过的坑和做出的决定，供下次接别的客户端或改协议时查。历史和验证证据见 `Docs/Maintenance/CHANGELOG.jsonl`（1.0.0.10、13、15、17、19），接口细节见 `Docs/Interfaces/INTERFACE_INDEX.jsonl` 的 `provided_api.aiadmin.mcp*` 和 `provided_api.aiadmin.oauth`。

## 1. 先说结论

| 想做的事 | 走得通的路 | 走不通的路 |
|---|---|---|
| 让 ChatGPT 用模组的工具 | 开发者模式 → 插件 → 添加自定义 MCP 服务器，认证选 OAuth；服务端实现 OAuth 2.1（动态注册 + PKCE + 批准页确认号） | ChatGPT 不能填固定 Bearer 请求头；不支持 client_credentials、API key、自备 mTLS |
| 让 dot 用模组的工具 | 在 dot 的资料页启用 ChatGPT 账号里已连好的插件（文档原话："Your existing ChatGPT app permissions apply to your dot"） | 让 dot 在自己的云端电脑上做 OAuth：它的客户端把回调指向 `127.0.0.1:<随机端口>`，dot 的云端浏览器拦截本机地址，授权永远完不成 |
| 24 小时值守 | **MCP Events 推送**：客户端订阅事件，服务器有消息时 POST 到它的回调地址把它叫醒 | `wait_events` 长轮询：ChatGPT / dot 不会一直挂着，工具调用超时也短；定时任务只能做巡检，接不了实时聊天 |
| 自建 Crafty 式网页让 AI 点 | 否决 | 对 AI 慢、脆、费 token，而且在公网上多开一个登录面板 |

## 2. OAuth：ChatGPT 连接器这一侧的经验

- **发现流程**：客户端先 POST `/mcp` 不带令牌 → 401 + `WWW-Authenticate: Bearer resource_metadata="…"`（RFC 9728）→ 读 `/.well-known/oauth-protected-resource` → 读授权服务器元数据 → 动态注册 → 授权页 → PKCE 换令牌。401 不能算认证失败，否则正常发现流程就把自己封了（1.0.0.13 修的）。
- **批准方式**：批准页不输密码，显示 6 位确认号，服主在后台 `/aibot remote approve <确认号>`（权限 4）。原因：批准页在公网上，任何能注册客户端的人都能打开它，不能靠页面里的口令。`CommandPolicy` 硬拒绝文本里出现 `aibot remote`，代理自己不能批准自己。
- **Origin 校验**：批准页曾因 `Referrer-Policy: no-referrer` 让浏览器提交表单时发 `Origin: null`，被严格校验挡住（用户要求不能放松校验）。改成 `Referrer-Policy: same-origin` 浏览器就会带真实 Origin。教训：**表单页的 Referrer-Policy 会影响 Origin 头**。
- **回调地址**：ChatGPT 用固定的 `https://chatgpt.com/connector_platform_oauth_redirect`（授权服务器支持 RFC 9207 `iss` 时）或 `https://chatgpt.com/connector/oauth/{callback_id}`；`allowed_redirect_hosts` 白名单里要有 `chatgpt.com`。
- **令牌生命周期**：access 1 小时、refresh 轮换、从首次批准起 90 天绝对期限（refresh 重试宽限 30 秒，补发的旧 refresh 标 poisoned 再出现即吊销）。ChatGPT 每小时自动续期，不用管。
- **注册防刷**：动态注册是公开端点，要按来源限流、限制未批准客户端数量、淘汰最忙来源；请求体 16 KiB / 5 秒内传完。
- **ChatGPT 侧的事实**：写操作工具只在开发者模式下提供；工具调用超时大约 30 秒量级；ChatGPT 连接时会出示 OpenAI 的客户端证书、有公布的出口 IP 段（我们没用，Funnel 把来源都变成 127.0.0.1，真正的防线是令牌 + 封禁）。

## 3. dot 是什么、怎么对待它

- dots 于 2026-09-29 发布，Pro / Business Premium 可用；每个 dot 有自己的云端电脑和浏览器，"keeps working between conversations"，能用账号里连好的插件、能定时、能响应"支持事件监控的服务"。
- **不要把 dot 当成能挂长连接的进程**。它每次被触发是一次任务运行，运行完就停。所以服务端不能用"最近 2 分钟有没有调用"判断它在不在，要用"有没有能投递的推送订阅"。
- dot 的云端浏览器会拦本机回调，所以它自己做不了标准 OAuth 本机回调流程；授权必须在 ChatGPT 本体完成再共享给它。
- OpenAI 没有文档说 dot 能不能用开发者模式下的自定义插件；本项目实测可以（10-08，dot 通过 ChatGPT 插件调了 `get_server_status`、`bot_control` 等）。
- dot 每次醒来要读上下文再调用工具，从推送送达到它调用 `wait_events` 可能一两分钟，所以模组把推送送达后的兜底时间单独设成 `webhook_handoff_seconds`（默认 150 秒），不沿用长轮询的 60 秒。

## 4. MCP 2026-07-28（"MCP 2.0"）要注意什么

ChatGPT 的 MCP Events 要求这一版协议。和之前相比：

- **没有握手**：没有 `initialize` / `notifications/initialized`，每个请求在 `params._meta` 里带 `io.modelcontextprotocol/protocolVersion`（必填）、`clientCapabilities`（必填）、`clientInfo`（建议）。没有会话头，`ping`、GET 流、`Last-Event-ID` 都没了。
- **请求头必须镜像请求体**：`MCP-Protocol-Version`、`Mcp-Method`、`tools/call` 还要 `Mcp-Name`（非 ASCII 用 `=?base64?…?=`）。不一致或缺失 → 400 + `-32020 HeaderMismatch`。版本不支持 → 400 + `-32022` 并在 `data.supported` 列出支持的版本。缺必填 `_meta` → 400 + `-32602`。未知方法 → **404** + `-32601`（旧版是 200）。
- **结果格式**：每个结果带 `resultType: "complete"`，`_meta` 带 `io.modelcontextprotocol/serverInfo`；`tools/list` 等列表结果要带 `ttlMs` 和 `cacheScope`（工具表按 rules.json 变化，所以是 `private`）。
- **`server/discover`**：服务端必须实现，返回 `supportedVersions`、`capabilities`、`instructions`；MCP Events 的 `events: {}` 能力就是在这里公布的（ChatGPT 扫插件时读）。
- **双代并存**：按请求有没有 `_meta.io.modelcontextprotocol/protocolVersion` 判断新旧；旧客户端（Claude Code、官方 Python SDK 1.28）照常走 `initialize`，完全不受影响。不要靠 `MCP-Protocol-Version` 头判断，旧版 2025-06-18 起也发这个头。
- 规范里 `-32000` 到 `-32019` 是实现自留区，MCP Events 草案用的 `-32015`（CallbackEndpointError）就在这里。

## 5. MCP Events（webhook 推送）实现要点

规范还是草案（Triggers & Events 工作组的 design sketch），OpenAI 只支持其中的 **webhook 投递 + 回调验证**，不支持 poll / push，也不支持 `gap` / `terminated` 控制通知。做的时候按 OpenAI 的页面为准：

1. `events/list`：事件名、描述、`delivery: ["webhook"]`、`inputSchema`（订阅参数，我们只有 `kinds`）、`payloadSchema`（推送体 `data` 的结构）。描述里写清"收到后该调什么工具"，因为推送体里不该夹带指令。
2. `events/subscribe`：
   - 密钥 `delivery.secret` 必须 `whsec_` + base64，解码后 24–64 字节；回调 `delivery.url` 必须 https 公网（解析后每个地址都检查：回环、内网、链路本地、CGNAT 100.64/10 即 Tailscale 段、IPv6 ULA、组播一律拒绝），不跟随重定向。
   - 订阅 id 由 principal + url + 事件名 + **规范化参数**决定（键序、去重），再来一次就是刷新（幂等）。principal 就是认证身份：固定令牌 = `token`，OAuth = client_id。
   - 建立前先 POST `{"type":"verification","challenge":…}` 到回调，`webhook-id` 用 `msg_verification_…`，同样签名；对方必须 2xx 并回显 `{"challenge":…}`，常量时间比较。验证结果按 principal + url 缓存 24 小时，刷新不重复挑战；换密钥必须用新密钥重新验证。
   - `ttlMs`：缺省给默认有效期；给了就不超过它；`null` 表示要"永不过期"——我们不给，最多 `webhook_max_ttl_days`。返回 `refreshBefore`（ISO 时间）、`cursor: null`（不支持重放）、`truncated: false`。
3. 投递：一条事件一个请求，`Content-Type: application/json`、`webhook-id`（= `eventId`）、`webhook-timestamp`（秒）、`webhook-signature`（Standard Webhooks：`v1,` + base64(HMAC-SHA256(secret, `id.timestamp.body`))，轮换窗口内多个签名用空格分隔）、`X-MCP-Subscription-Id`。请求体 ≤ 256 KiB。**先序列化一次再签名、再发同一份字节**。
4. 重试：非 2xx 退避重试、次数有限（我们 10 秒 / 1 分钟 / 5 分钟共 4 次）；每次重试换新时间戳和签名但保留 `eventId`；410 / 413 不重试；连续失败多次暂停订阅，客户端重新订阅恢复。
5. `eventId` 要跨重启唯一：收件箱编号重启后从头来，所以前面加一段开服时的随机前缀，不然对方按 `webhook-id` 去重会把重启后的事件丢掉。
6. 订阅要持久化（含密钥，签名要用，所以只能明文、文件 660、不写日志）；投递前复查 principal 还在不在（OAuth 客户端吊销要删订阅）。
7. "在线"的定义要改：有能投递的订阅即在线，消息进收件箱并推送；推送送达（2xx）后兜底放宽；推送没送达的仍按原来的秒数交本地。

## 6. 安全上的取舍

- 出站请求是服务器主动连外网，这是新的攻击面。做了：https + 公网地址校验、不跟随重定向、连接 5 秒 / 整次 10 秒超时、256 KiB 上限、每 principal 最多 5 个订阅共 20 个、订阅/退订计入每分钟调用上限。
- 没做：按固定地址连接同时保留主机名做 TLS 校验（Java HttpClient 做不到），所以解析和连接之间有极小的 DNS 重绑定窗口；补救是 `remote.cloud.webhook_hosts` 主机白名单。ChatGPT 的回调主机文档没写，第一次订阅时看日志 `remote.events_subscribe` 的 `host` 再决定要不要加白名单。
- 推送体只放摘要（玩家原话算数据），完整说明让客户端用 `wait_events` 取；不在推送体里写"请你怎么做"，避免成为提示注入通道。
- 订阅权限和工具一样：没有有效令牌连 `events/list` 都调不到。

## 7. 测试方法

- 单元测试在同一台机器起 `com.sun.net.httpserver.HttpServer` 当回调接收端，服务端用 `-Daiadmin.devWebhookInsecure=true`（或测试构造参数）放行 http + 本机地址；接收端验证挑战回显、按密钥重算签名；重试间隔用 `retryDelays(150, 150, 150)` 缩短。注意重试间隔小于 1 秒时 `webhook-timestamp` 相同、签名也相同，不要断言"每次签名不同"。
- 端到端 `scripts/e2e_events.py`：Python `ThreadingHTTPServer` 当接收端，走新版协议头调 `server/discover` / `events/subscribe`，玩家聊天 → 收到推送 → `wait_events` 取走 → 退订后不再推送。
- 真正的互通只能在线上做：按 OpenAI 的"Test in ChatGPT"清单——插件页重新扫描看到事件 → Work 聊天或 dot 里说"监控 duty.item" → 日志出现 `remote.events_subscribe` → 触发一条聊天看 `remote.events_delivered` → 停止监控看 `remote.events_unsubscribe`。

## 8. 零散的坑

- 多会话并行改同一仓库：各会话写文件行尾不一致，三方合并前先统一 LF 再 `git merge-file`。
- Gson 的 `JsonObject.asMap()` 是 2.10 才有，服务端带的版本不一定够，用 `keySet()`。
- Windows Git Bash 里 `python - <<'EOF'` 的 heredoc 偶尔被引号打断，长补丁脚本写成文件再执行更稳。
- Python 读 RCON / 日志输出要显式 `encoding="utf-8", errors="replace"`，默认 cp1252 会炸。
- Tailscale Funnel 的 TCP 转发按 SNI 路由，裸 `--tcp` 不通，SSH 要 `--tls-terminated-tcp` + 客户端 `openssl s_client` 做 ProxyCommand。
- CHANGELOG 的 `change_type` 是封闭词表（`deployment` 不是 `deploy`），写之前看一眼校验器。

## 9. 参考

- OpenAI：MCP Events（`developers.openai.com/plugins/build/mcp-events`）、插件认证（`developers.openai.com/plugins/build/auth`）、dots 文档（`learn.chatgpt.com/docs/dots/*`）
- MCP 规范 2026-07-28：changelog、basic/versioning、basic/transports/streamable-http、server/discover（`modelcontextprotocol.io/specification/2026-07-28/`）
- MCP Triggers & Events 草案：`github.com/modelcontextprotocol/experimental-ext-triggers-events`
- Standard Webhooks：`github.com/standard-webhooks/standard-webhooks`
