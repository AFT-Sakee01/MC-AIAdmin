package io.github.aftsakee.aiadmin.config;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * config/aiadmin/config.json：接入点、触发方式和技术参数（字段名在文件里是 snake_case）。第一次启动按默认值生成。
 * 限制规则（权限 / 额度 / 启停）在独立的 rules.json（{@link Rules}），提示词在 prompts/ 下的独立文件（Prompts）。
 */
public final class BotConfig {
    public int schemaVersion = 1;
    public boolean enabled = true;
    /** 聊天里显示的名字：[AI管家] xxx。 */
    public String botName = "AI管家";
    public String activeProvider = "deepseek";
    /** 当前接入点第一次请求就失败（网络、额度、密钥）或余额不足被停用时依次改用的接入点。已经执行过动作后不再切换，避免重复执行。 */
    public List<String> fallbackProviders = new ArrayList<>();
    public Map<String, ProviderConfig> providers = defaultProviders();
    public Patrol patrol = new Patrol();
    public Events events = new Events();
    public Limits limits = new Limits();
    public Compression compression = new Compression();
    public Chat chat = new Chat();
    public Remote remote = new Remote();
    public Tpa tpa = new Tpa();
    public Persona persona = new Persona();
    public Social social = new Social();

    /**
     * 和玩家互动的细节：/ai 私聊入口、"别说了"、连续受挫时收敛、服务器指南。玩家自己的偏好存在 aiadmin/player-prefs.json（/ai style、/ai avoid…）。
     */
    public static final class Social {
        /** 允许玩家用 /ai &lt;内容&gt; 私下找机器人（回复只私聊给他）。 */
        public boolean privateChat = true;
        /** 玩家对机器人说"别说了""闭嘴"之类时自动安静几小时（/ai resume 恢复）。 */
        public int stopQuietHours = 2;
        /** /ai quiet 不带时长时安静几小时。 */
        public int quietDefaultHours = 24;
        /** 连续受挫：frustrated_window_minutes 分钟内死亡 frustrated_deaths 次，之后 frustrated_calm_minutes 分钟内别调侃、不能整。 */
        public int frustratedDeaths = 3;
        public int frustratedWindowMinutes = 20;
        public int frustratedCalmMinutes = 30;
        /** config/aiadmin/server-guide.md 最多放进提示词多少字。 */
        public int guideMaxChars = 6000;
        /**
         * 话量：quiet = 只回答直接问它的、不主动插话；normal = 默认；chatty = 话痨、热心：事件大多接一句（欢迎、祝贺、安慰/吐槽、起哄），
         * 公屏上能接梗能帮忙就插话，巡检时主动找在线玩家聊进展、给建议，玩家提到想做什么就主动查资料、帮他记目标和工程。
         * /aibot talk 临时切换（会记住）。玩家的"别说了"、只想认真答疑等偏好照样强制生效。
         */
        public String chattiness = "normal";
    }

    /**
     * 预设模式（机器人的说话风格），全服同一时间一个。提示词在 config/aiadmin/prompts/modes/&lt;模式&gt;.md（缺失自动生成）。
     * /aibot mode &lt;模式&gt;、/aibot prank &lt;档位&gt; 切换后记在 aiadmin/state/persona.json（重启后仍有效），/aibot mode reset 回到这里的值。
     */
    public static final class Persona {
        /** normal = 不加风格；helper = 帮助模式；roast = 吐槽模式；prank = 恶作剧模式；也可以是 prompts/modes/ 里服主自己加的文件名。 */
        public String mode = "normal";
        public Prank prank = new Prank();
    }

    /** 恶作剧模式下回复普通玩家聊天时能做什么（执行前强制检查，见 PrankPolicy）。 */
    public static final class Prank {
        /** chat_only = 只动嘴，不执行指令；harmless = 无害整蛊（音效、粒子、标题、几秒的效果、无害小动物、搞笑物品）；rough = 较狠（再加小伤害、短距离传送、更强的负面效果）。 */
        public String level = "harmless";
        /** 免疫名单（玩家名，不分大小写）：不会被整；名单非空时不能用 @a/@p/@r。 */
        public List<String> immunePlayers = new ArrayList<>();
        /** 每次回复最多召唤几只小动物。 */
        public int maxSummonsPerRun = 5;
    }

    /**
     * 传送请求：/tpa 玩家（传送到对方那里）、/tpah 玩家（把对方传送过来），对方 /tpa Y 接受、/tpa N 拒绝。
     * 拒绝和过期都不通知请求者。请求一发出就进入冷却。/tpa auto|manual 每名玩家自己设置是否自动接受 /tpa（/tpah 总是要手动确认）。
     */
    public static final class Tpa {
        /** 整个传送请求功能的开关（关掉后 /tpa、/tpah 都不可用）。 */
        public boolean enabled = true;
        public TpaCommand tpa = new TpaCommand();
        public TpaCommand tpah = new TpaCommand();
        /** 请求多少秒内有效。 */
        public int requestTimeoutSeconds = 60;
        public TpaCooldown cooldown = new TpaCooldown();
        /** 是否允许跨维度传送（主世界 ↔ 下界 / 末地）。 */
        public boolean allowCrossDimension = true;
    }

    public static final class TpaCommand {
        public boolean enabled = true;
        /** 使用这个指令需要的权限等级（0 = 所有玩家）。 */
        public int permissionLevel = 0;
    }

    public static final class TpaCooldown {
        /** true = /tpa 和 /tpah 共用一个冷却（seconds）；false = 各自独立（tpa_seconds、tpah_seconds）。 */
        public boolean shared = true;
        public int seconds = 300;
        public int tpaSeconds = 300;
        public int tpahSeconds = 300;
    }

    /**
     * 和玩家聊天。玩家的聊天消息由服务端事件送来（机器人自己发的是系统消息，不会触发自己）。
     * 聊天唤醒算自动运行：受 rules.json 的额度与启停限制。
     */
    public static final class Chat {
        /**
         * off = 不理聊天；mention = 聊天里带关键词（叫它名字、@AI…）才回；
         * custom = 关键词 + 自定义条件（conditions，任一满足即唤醒）；all = 每句聊天都看，自己判断要不要回（花费明显更高）。
         */
        public String mode = "mention";
        /** mention / custom 模式的关键词（包含即算、不分大小写）；空 = 用 bot_name、"@AI"、"@aibot"、"@ai管家"。 */
        public List<String> keywords = new ArrayList<>();
        /** custom 模式的条件，任一满足即唤醒（关键词也照样有效）。 */
        public List<Condition> conditions = new ArrayList<>(List.of(Condition.burst(5, 120, "all", 300)));
        /** 收到消息后再等几秒，把这段时间里的消息合并成一次回复。 */
        public int debounceSeconds = 2;
        /** 冷却（秒），各触发方式分开计：关键词触发按玩家计；all 模式按玩家计；自定义条件用各自的 cooldown_seconds。
         *  冷却内的消息不另起一次运行，但若有一次回复正在合并等待，会并入那一批。 */
        public int keywordCooldownSeconds = 8;
        /** all 模式下同一玩家两次唤醒的最短间隔。 */
        public int playerCooldownSeconds = 8;
        /** 每名玩家每小时最多唤醒几次（0 = 不限）。 */
        public int maxPerPlayerPerHour = 30;
        /** 带上最近几条聊天作为上下文。 */
        public int contextMessages = 12;
    }

    /**
     * custom 聊天模式的一个条件。type：
     * <ul>
     *   <li>burst：window_seconds 秒内的聊天超过 more_than 句（scope = all 全服合计 / player 同一玩家）；</li>
     *   <li>regex：消息匹配正则 pattern（不分大小写）；</li>
     *   <li>players：players 里的玩家说的任何话；</li>
     *   <li>question：问句（以 ? / ？ / 吗 / 呢 结尾）。</li>
     * </ul>
     * 每个条件有自己的冷却 cooldown_seconds：burst 且 scope = all 时全服共用一个冷却，其余（burst/player、regex、players、question）按玩家各自计。
     * note 会作为触发原因告诉模型（可空）。
     */
    public static final class Condition {
        public String type = "burst";
        public int moreThan = 5;
        public int windowSeconds = 120;
        public String scope = "all";
        public String pattern = "";
        public List<String> players = new ArrayList<>();
        public int cooldownSeconds = 300;
        public String note = "";

        static Condition burst(int moreThan, int windowSeconds, String scope, int cooldown) {
            Condition c = new Condition();
            c.moreThan = moreThan;
            c.windowSeconds = windowSeconds;
            c.scope = scope;
            c.cooldownSeconds = cooldown;
            return c;
        }
    }

    /**
     * 远程管理接口：在 Tailscale 内网地址上开一个 MCP（Streamable HTTP）端点，云电脑上的 AI（Claude Code 等）
     * 加入同一个 tailnet 后，用 Bearer 令牌连接，就能使用和机器人相同的观察/动作工具；权限、隐藏工具、指令黑名单照样生效。
     */
    public static final class Remote {
        public boolean enabled = false;
        /** tailscale = 自动找 Tailscale 网卡地址（100.64.0.0/10）；也可以填 127.0.0.1 或具体 IP。找不到地址时每分钟重试。 */
        public String bind = "tailscale";
        public int port = 25580;
        /** 访问令牌；空 = 自动生成并保存到 config/aiadmin/remote-token.txt。 */
        public String token = "";
        /** 允许连接的来源地址段（Tailscale IPv4/IPv6 和本机）。 */
        public List<String> allowedCidrs = new ArrayList<>(List.of("100.64.0.0/10", "fd7a:115c:a1e0::/48", "127.0.0.0/8", "::1/128"));
        /** false = 只读（不提供 run_command / send_chat）。 */
        public boolean allowActions = true;
        public int maxCallsPerMinute = 60;
        public int maxCommandsPerHour = 120;
        /**
         * 认证失败防护（经 Tailscale Funnel 暴露到公网时尤其重要）：同一来源 auth_fail_window_seconds 秒内令牌错误
         * auth_fail_limit 次后封禁 auth_block_seconds 秒（封禁期间带正确令牌也拒绝）。来源取 X-Forwarded-For 最右值（仅当 TCP 来源是本机反向代理时）。
         */
        public int authFailLimit = 5;
        public int authFailWindowSeconds = 600;
        public int authBlockSeconds = 900;
        /**
         * 对外地址（不带路径），例如 https://steamdeck-server.xxxx.ts.net。OAuth 的发现信息和 401 里的 resource_metadata 都用它，
         * 不从请求的 Host 头推断（Host 头可以伪造）。留空 = 不提供 OAuth，只能用固定令牌。
         */
        public String publicBaseUrl = "";
        /** 经远程接口（云端 AI）调用 get_player、get_access_lists 时不显示玩家 IP 和 IP 封禁（rules.json 的 expose_player_ips 只管本地 AI）。 */
        public boolean hidePlayerIps = true;
        public OAuth oauth = new OAuth();
        public Cloud cloud = new Cloud();
        /**
         * 云端 set_setting 能改的 config.json 键（点分隔，snake_case）。接入点/密钥、remote.*、预设模式与恶作剧、压缩、会进提示词的文字
         * （bot_name、patrol.extra_instructions 等）即使写在这里也改不了（见 SettingsEditor）。
         * 改动写进文件并立即 reload，是永久的。清空 = 云端只能看不能改。
         */
        public List<String> settableSettings = new ArrayList<>(List.of(
                "chat.mode", "chat.keywords", "chat.keyword_cooldown_seconds", "chat.player_cooldown_seconds", "chat.max_per_player_per_hour",
                "patrol.enabled", "patrol.interval_minutes", "patrol.only_when_players_online",
                "events.player_join", "events.player_leave", "events.player_death", "events.advancement", "events.milestones",
                "events.death_followup", "events.tps_low", "events.tps_low_mspt", "events.server_error", "events.chat_keywords",
                "events.cooldown_seconds", "social.chattiness", "social.private_chat"));
    }

    /**
     * 云端值守（"云端优先，本地兜底"）：云端 AI 经远程接口用 wait_events 长轮询收件箱。云端在线时，route 里列出的触发
     * （chat 玩家聊天 / event 事件 / patrol 定时巡检）放进收件箱交给云端，本地 API 不运行、不花钱；放进去 handoff_seconds 秒
     * 还没被取走就交给本地 API。云端在线 = online_timeout_seconds 秒内调用过云端值守工具（wait_events、reply_chat…；普通查询不算），或正有 wait_events 挂着；
     * 取走后 2×handoff_seconds 秒内没有任何动作的条目收回交给本地。
     * 需要 remote.enabled 且 remote.allow_actions（云端要能回复）。管理员 /aibot ask 永远走本地。
     */
    public static final class Cloud {
        public boolean enabled = false;
        public List<String> route = new ArrayList<>(List.of("chat", "event", "patrol"));
        public int onlineTimeoutSeconds = 120;
        public int handoffSeconds = 60;
        /** wait_events 默认等多久；客户端的工具调用超时、Funnel/代理的空闲超时都要比它长。 */
        public int defaultWaitSeconds = 55;
        public int maxWaitSeconds = 300;
        /** 同时挂起的 wait_events 上限（每个占远程接口一个工作线程）。 */
        public int maxWaiters = 2;
        public int maxPending = 100;
        /** 云端取走后多少分钟还没 complete_item 就自动关闭（已执行的动作照样记进记忆）。 */
        public int autoCloseMinutes = 15;
        /**
         * MCP Events 推送（ChatGPT / dots 订阅 duty.item 后，收件箱新消息会 POST 到它们的回调地址）送达确认后，这条消息最多等多少秒
         * 让云端来取（从放入算起）；云端代理从被叫醒到调用 wait_events 可能要一两分钟。没推送或推送失败的仍按 handoff_seconds。
         */
        public int webhookHandoffSeconds = 150;
        /** 允许的回调主机：完全相同或 "*.example.com" 匹配子域名；空 = 任何公网 https 主机（内网/本机地址一律拒绝）。 */
        public List<String> webhookHosts = new ArrayList<>();
        /** 客户端没指定时授予的订阅有效期（小时）；客户端会在到期前刷新。 */
        public int webhookTtlHours = 24;
        /** 订阅最长有效期（天）；客户端要求"永不过期"也只给这么久。 */
        public int webhookMaxTtlDays = 30;
        /**
         * 宿主机接口：云端可以用 host_info（主机状态、日志、备份清单等只读动作）和 server_power（备份、重启、停服等）。
         * 默认关：打开前宿主机要装好 /usr/local/sbin/aiadmin-power 和对应的 sudoers（动作清单和真正的把关都在那个程序里，见 ServerPower）；
         * 没装时工具只会回"宿主机接口不可用"。
         */
        public boolean powerEnabled = false;
    }

    /**
     * 远程接口的 OAuth 2.1（MCP 标准授权，给 ChatGPT 连接器、claude.ai 这类不能填固定请求头的客户端用）。
     * 需要 remote.public_base_url；固定令牌方式照常可用，两种方式共用同一套工具权限和限额。
     */
    public static final class OAuth {
        public boolean enabled = true;
        public int accessTokenMinutes = 60;
        public int refreshTokenDays = 30;
        /** 从首次批准算起，最长多少天必须重新批准（refresh token 轮换不会延长这个期限，避免变成永久凭据）。 */
        public int refreshMaxDays = 90;
        /** 最多几个已批准的客户端（在批准时检查）。 */
        public int maxClients = 20;
        /**
         * 最多几个"注册了但还没被批准"的客户端（动态注册是公开的，要防止被刷爆存储）：1 小时后自动清理，满了淘汰注册超过 10 分钟的最旧一个。
         */
        public int maxUnusedClients = 50;
        /** 每个来源每 10 分钟最多注册几次。没有全服共享的上限：共享出口 IP 上的其他人耗不掉全服的额度。 */
        public int registerPerSource = 10;
        /**
         * 注册时 redirect_uris 只允许这些主机（不分大小写、完全相同，不含子域名）；空列表 = 不限制。
         * 防止有人注册一个跳到自己网站的客户端，再骗服主在批准页输入口令。localhost/127.0.0.1/[::1] 允许 http，其他必须 https。
         */
        public List<String> allowedRedirectHosts = new ArrayList<>(List.of("chatgpt.com", "chat.openai.com", "claude.ai", "claude.com",
                "localhost", "127.0.0.1", "[::1]"));
    }

    /**
     * 上下文压缩。
     * <ul>
     *   <li>运行内：一次运行里工具结果越积越多时，满足 when（时机）任一条件就按 method（方法）把已有进展压成摘要，
     *       然后以"原任务 + 进展摘要"重新开一个会话继续。重开而不是改写历史：Claude 新模型会校验历史里的思考块，
     *       Antigravity 的历史在服务端，改写都不安全。</li>
     *   <li>跨运行：行动记录超过 memory.compress_when_over 条时，把较旧的记录合并进"长期记忆摘要"。</li>
     * </ul>
     */
    public static final class Compression {
        public boolean enabled = true;
        public When when = new When();
        public Method method = new Method();
        public MemoryCompression memory = new MemoryCompression();
    }

    /** 压缩时机：任一条件满足即压缩（0 = 该条件关闭）。 */
    public static final class When {
        /** 上一次模型回复报告的输入 token 超过这个数。 */
        public long inputTokensOver = 60000;
        /** 本次会话累计的上下文字符数（提示词 + 回复 + 工具参数和结果）超过这个数。 */
        public int contextCharsOver = 150000;
        /** 每隔这么多轮压缩一次。 */
        public int everySteps = 0;
    }

    /** 压缩方法。 */
    public static final class Method {
        /** digest = 机械摘要（不花 token）；summarize = 让模型写进展摘要（失败时退回 digest）。 */
        public String type = "digest";
        /** 最近几轮的工具结果保留原文。 */
        public int keepRecentSteps = 2;
        /** 更早的工具结果只保留前这么多字。 */
        public int oldResultChars = 300;
        /** summarize 用的接入点（空 = 当前运行的接入点；可以填一个便宜的）。 */
        public String summaryProvider = "";
        /** summarize 的提示词文件（相对 config/aiadmin/，不存在时自动生成）。 */
        public String summaryPromptFile = "prompts/compress.md";
        /** 摘要最长字数（写进提示词里的 {max_chars}，超出会被截断）。 */
        public int summaryMaxChars = 3000;
        /** 一次运行最多压缩几次（防止提示词本身就超阈值时反复压缩）。 */
        public int maxPerRun = 3;
    }

    public static final class MemoryCompression {
        public boolean enabled = true;
        /** 行动记录超过这么多条时压缩。 */
        public int compressWhenOver = 30;
        /** 压缩后保留最近几条原始记录。 */
        public int keepRecent = 10;
        /** summarize = 让模型合并进长期记忆摘要；digest = 只做机械合并（不花 token）。 */
        public String type = "summarize";
        public String promptFile = "prompts/memory-compress.md";
        public int summaryMaxChars = 1500;
    }

    public static final class Patrol {
        public boolean enabled = true;
        public int intervalMinutes = 15;
        /** 没人在线时不巡检（省 token）。 */
        public boolean onlyWhenPlayersOnline = true;
        /** 巡检时额外交代的事项（可空）。 */
        public String extraInstructions = "";
    }

    public static final class Events {
        public boolean playerJoin = true;
        public boolean playerLeave = false;
        public boolean playerDeath = true;
        public boolean advancement = false;
        /**
         * 有意义的行为（每类每人冷却 cooldown_seconds）：第一次造出机器/重要方块、第一次捡到稀有物品、驯服动物、击败 Boss、救下队友、
         * 工程进展（范围内放方块跨过 100/500/1000…）。比每次开箱、挖方块都推送有用得多。
         */
        public boolean milestones = true;
        /** 死亡之后的跟进：回去捡回了东西 / 掉落物消失了没捡回（需要 player_death 也开着）。 */
        public boolean deathFollowup = true;
        /** 平均每 tick 耗时（毫秒）持续高于阈值时唤醒。50 ms = 20 TPS 的极限。 */
        public boolean tpsLow = true;
        public double tpsLowMspt = 60;
        public int tpsLowSeconds = 30;
        /** 服务端日志出现 ERROR/FATAL 时唤醒（本模组自己的日志除外）。 */
        public boolean serverError = true;
        /** 旧设置项（1.0.0.2 及以前）：非空时并入 chat.keywords。新设置请用 chat 一节。 */
        public List<String> chatKeywords = new ArrayList<>();
        /** 事件到来后再等几秒，把同一时间段的事件合并成一次唤醒。 */
        public int debounceSeconds = 5;
        /** 同一玩家同一类事件的冷却（秒）。 */
        public int cooldownSeconds = 120;
        /** tps_low、server_error 这类告警的冷却（秒）。 */
        public int alertCooldownSeconds = 900;
    }

    /** 技术参数（与"规则"无关的运行开销控制）。 */
    public static final class Limits {
        /** 每个工具结果最多返回给模型多少字符（多的截断）。 */
        public int toolResultMaxChars = 12000;
        /** 等待中的任务上限，满了丢弃新的自动任务。 */
        public int queueMax = 4;
        /** 带进提示词的"最近行动记录"条数。 */
        public int memoryEntries = 8;
        /** 整个运行（含所有轮）最长秒数。 */
        public int runTimeoutSeconds = 300;
    }

    static Map<String, ProviderConfig> defaultProviders() {
        Map<String, ProviderConfig> m = new LinkedHashMap<>();
        ProviderConfig gpt = ProviderConfig.of(ProviderConfig.OPENAI, "https://api.openai.com/v1", "gpt-5", "OPENAI_API_KEY");
        gpt.maxTokensField = "max_completion_tokens";
        m.put("gpt", gpt);
        ProviderConfig claude = ProviderConfig.of(ProviderConfig.ANTHROPIC, "https://api.anthropic.com", "claude-opus-5-5", "ANTHROPIC_API_KEY");
        claude.maxTokens = 16000;
        JsonObject oc = new JsonObject();
        oc.addProperty("effort", "medium");
        claude.extraBody.add("output_config", oc);
        claude.priceInputPerMillion = 4.0;
        claude.priceOutputPerMillion = 20.0;
        claude.currency = "USD";
        m.put("claude", claude);
        // DeepSeek V4.1 Flash（API 模型名 deepseek-flash），思考模式 + 最高推理强度；max 强度思考更久，超时放宽到 300 秒
        ProviderConfig ds = ProviderConfig.of(ProviderConfig.OPENAI, "https://api.deepseek.com", "deepseek-flash", "DEEPSEEK_API_KEY");
        JsonObject thinking = new JsonObject();
        thinking.addProperty("type", "enabled");
        thinking.addProperty("reasoning_effort", "max");
        ds.extraBody.add("thinking", thinking);
        ds.timeoutSeconds = 300;
        // 单价是写配置时的参考值，请按官网当前价格核对；真实余额以 DeepSeek 余额接口为准
        ds.priceInputPerMillion = 2.0;
        ds.priceOutputPerMillion = 3.0;
        ds.currency = "CNY";
        m.put("deepseek", ds);
        ProviderConfig agy = ProviderConfig.of(ProviderConfig.ANTIGRAVITY, "https://generativelanguage.googleapis.com",
                "antigravity-preview-09-2026", "GEMINI_API_KEY");
        m.put("agy", agy);
        return m;
    }
}
