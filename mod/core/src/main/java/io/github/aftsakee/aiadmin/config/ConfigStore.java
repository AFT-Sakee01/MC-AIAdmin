package io.github.aftsakee.aiadmin.config;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 读写 config/aiadmin/config.json 和 rules.json。
 * 文件不存在：按默认值生成。文件写坏了：抛 {@link ConfigException}，<b>绝不覆盖用户的文件</b>（调用方继续用旧设置或停用机器人）。
 */
public final class ConfigStore {
    public static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();
    private static final Set<String> TYPES = Set.of(ProviderConfig.OPENAI, ProviderConfig.ANTHROPIC, ProviderConfig.ANTIGRAVITY);
    private static final Set<String> AUTH = Set.of("default", "bearer", "x-api-key", "x-goog-api-key");
    private static final Set<String> PERIODS = Set.of("hour", "day", "week", "month");
    private static final Set<String> BALANCE_APIS = Set.of("auto", "deepseek", "newapi", "none");
    private static final Pattern HOURS = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d-([01]\\d|2[0-3]):[0-5]\\d");

    private ConfigStore() {}

    public static final class ConfigException extends Exception {
        private static final long serialVersionUID = 1L;

        public ConfigException(String msg) {
            super(msg);
        }
    }

    public record Loaded<T>(T value, boolean created, List<String> warnings) {}

    public static Loaded<BotConfig> loadConfig(Path file) throws ConfigException {
        Loaded<BotConfig> l = load(file, BotConfig.class, BotConfig::new);
        List<String> w = new ArrayList<>(l.warnings());
        w.addAll(validate(l.value()));
        return new Loaded<>(l.value(), l.created(), w);
    }

    /** rules 的校验要知道有哪些接入点（provider_balance 里的名字），所以传入已读好的 config。 */
    public static Loaded<Rules> loadRules(Path file, BotConfig cfg) throws ConfigException {
        Loaded<Rules> l = load(file, Rules.class, Rules::new);
        List<String> w = new ArrayList<>(l.warnings());
        w.addAll(validate(l.value(), cfg));
        return new Loaded<>(l.value(), l.created(), w);
    }

    private static <T> Loaded<T> load(Path file, Class<T> type, java.util.function.Supplier<T> def) throws ConfigException {
        String name = file.getFileName().toString();
        try {
            if (!Files.exists(file)) {
                T d = def.get();
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(name + ".tmp");
                Files.writeString(tmp, GSON.toJson(d), StandardCharsets.UTF_8);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                return new Loaded<>(d, true, List.of());
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (!text.isEmpty() && text.charAt(0) == '﻿') text = text.substring(1); // 记事本存的 UTF-8 BOM
            T v = GSON.fromJson(text, type);
            if (v == null) throw new ConfigException(name + " 是空的");
            List<String> w = new ArrayList<>();
            schemaWarnings(com.google.gson.JsonParser.parseString(text), type, "", w);
            return new Loaded<>(v, false, w);
        } catch (JsonParseException e) {
            throw new ConfigException(name + " 格式错误：" + e.getMessage());
        } catch (IOException e) {
            throw new ConfigException("读写 " + name + " 失败：" + e.getMessage());
        }
    }

    /** 修正明显越界的数值、补默认对象，返回给服主看的警告；结构性错误抛异常。 */
    static List<String> validate(BotConfig c) throws ConfigException {
        List<String> w = new ArrayList<>();
        if (c.providers == null || c.providers.isEmpty()) throw new ConfigException("providers 不能为空");
        if (c.patrol == null) c.patrol = new BotConfig.Patrol();
        if (c.events == null) c.events = new BotConfig.Events();
        if (c.limits == null) c.limits = new BotConfig.Limits();
        // Gson 会把 JSON 里的 null 原样放进对象和列表：统一换成默认值/去掉，后面的代码就不用到处判空
        c.fallbackProviders = clean(c.fallbackProviders);
        c.events.chatKeywords = clean(c.events.chatKeywords);
        if (c.activeProvider == null) throw new ConfigException("active_provider 不能为空");
        if (c.botName == null || c.botName.isBlank()) c.botName = "AI管家";
        if (c.patrol.extraInstructions == null) c.patrol.extraInstructions = "";
        for (Map.Entry<String, ProviderConfig> e : c.providers.entrySet()) {
            String k = "providers." + e.getKey();
            ProviderConfig p = e.getValue();
            if (p == null) throw new ConfigException(k + " 是空的");
            if (!e.getKey().matches("[A-Za-z0-9_.-]{1,40}")) {
                throw new ConfigException("接入点名 \"" + e.getKey() + "\" 只能用字母、数字、_ . -（会用作提示词文件名和指令参数）");
            }
            if (p.type == null || !TYPES.contains(p.type)) throw new ConfigException(k + ".type 只能是 openai / anthropic / antigravity");
            if (p.authStyle == null || !AUTH.contains(p.authStyle)) throw new ConfigException(k + ".auth_style 只能是 default / bearer / x-api-key / x-goog-api-key");
            if (p.balanceApi == null || !BALANCE_APIS.contains(p.balanceApi)) throw new ConfigException(k + ".balance_api 只能是 auto / deepseek / newapi / none");
            if (p.baseUrl == null || !(p.baseUrl.startsWith("http://") || p.baseUrl.startsWith("https://"))) {
                throw new ConfigException(k + ".base_url 要以 http:// 或 https:// 开头");
            }
            if (p.model == null) p.model = "";
            if (p.apiKey == null) p.apiKey = "";
            if (p.apiKeyEnv == null) p.apiKeyEnv = "";
            if (p.extraHeaders == null) p.extraHeaders = new LinkedHashMap<>();
            if (p.extraBody == null) p.extraBody = new JsonObject();
            p.builtinTools = clean(p.builtinTools);
            p.extraHeaders.values().removeIf(v -> v == null);
            if (p.maxTokensField == null || p.maxTokensField.isBlank()) p.maxTokensField = "max_tokens";
            if (p.environment == null || p.environment.isBlank()) p.environment = "remote";
            if (p.agentModel == null) p.agentModel = "";
            if (p.currency == null) p.currency = "";
            if (p.systemPromptFile == null) p.systemPromptFile = "";
            if (p.customPromptFile == null) p.customPromptFile = "";
            if (p.type.equals(ProviderConfig.ANTHROPIC) && p.maxTokens <= 0) p.maxTokens = 16000;
            if (p.timeoutSeconds < 10) p.timeoutSeconds = 10;
            if (p.priceInputPerMillion < 0 || p.priceOutputPerMillion < 0) throw new ConfigException(k + " 的单价不能是负数");
        }
        if (!c.providers.containsKey(c.activeProvider)) throw new ConfigException("active_provider \"" + c.activeProvider + "\" 不在 providers 里");
        for (String f : c.fallbackProviders) {
            if (!c.providers.containsKey(f)) w.add("fallback_providers 里的 \"" + f + "\" 不在 providers 里，已忽略");
        }
        if (c.chat == null) c.chat = new BotConfig.Chat();
        if (!oneOf(c.chat.mode, "off", "mention", "custom", "all")) throw new ConfigException("chat.mode 只能是 off / mention / custom / all");
        if (c.chat.conditions == null) c.chat.conditions = new ArrayList<>();
        c.chat.conditions.removeIf(x -> x == null);
        for (BotConfig.Condition cd : c.chat.conditions) {
            if (!oneOf(cd.type, "burst", "regex", "players", "question")) throw new ConfigException("chat.conditions 的 type 只能是 burst / regex / players / question");
            if (cd.scope == null || cd.scope.isBlank()) cd.scope = "all";
            if (!oneOf(cd.scope, "all", "player")) throw new ConfigException("chat.conditions 的 scope 只能是 all / player");
            if (cd.pattern == null) cd.pattern = "";
            if (cd.note == null) cd.note = "";
            cd.players = clean(cd.players);
            if (cd.type.equals("regex")) {
                try {
                    Pattern.compile(cd.pattern);
                } catch (PatternSyntaxException e) {
                    throw new ConfigException("chat.conditions 的正则写错了：" + cd.pattern);
                }
                if (cd.pattern.isBlank()) throw new ConfigException("chat.conditions 的 regex 条件需要 pattern");
            }
            if (cd.type.equals("players") && cd.players.isEmpty()) throw new ConfigException("chat.conditions 的 players 条件需要 players 名单");
            cd.moreThan = clamp(w, "cd.moreThan", cd.moreThan, 0, 10000);
            cd.windowSeconds = clamp(w, "cd.windowSeconds", cd.windowSeconds, 1, 24 * 3600);
            cd.cooldownSeconds = clamp(w, "cd.cooldownSeconds", cd.cooldownSeconds, 0, 24 * 3600);
        }
        c.chat.keywords = clean(c.chat.keywords);
        for (String k : c.events.chatKeywords) if (!k.isBlank() && !c.chat.keywords.contains(k)) c.chat.keywords.add(k); // 兼容旧设置
        c.chat.debounceSeconds = clamp(w, "c.chat.debounceSeconds", c.chat.debounceSeconds, 0, 60);
        c.chat.playerCooldownSeconds = clamp(w, "c.chat.playerCooldownSeconds", c.chat.playerCooldownSeconds, 0, 3600);
        c.chat.keywordCooldownSeconds = clamp(w, "c.chat.keywordCooldownSeconds", c.chat.keywordCooldownSeconds, 0, 3600);
        c.chat.maxPerPlayerPerHour = clamp(w, "c.chat.maxPerPlayerPerHour", c.chat.maxPerPlayerPerHour, 0, 1000);
        c.chat.contextMessages = clamp(w, "c.chat.contextMessages", c.chat.contextMessages, 0, 100);
        if (c.tpa == null) c.tpa = new BotConfig.Tpa();
        if (c.tpa.tpa == null) c.tpa.tpa = new BotConfig.TpaCommand();
        if (c.tpa.tpah == null) c.tpa.tpah = new BotConfig.TpaCommand();
        if (c.tpa.cooldown == null) c.tpa.cooldown = new BotConfig.TpaCooldown();
        c.tpa.tpa.permissionLevel = clamp(w, "c.tpa.tpa.permissionLevel", c.tpa.tpa.permissionLevel, 0, 4);
        c.tpa.tpah.permissionLevel = clamp(w, "c.tpa.tpah.permissionLevel", c.tpa.tpah.permissionLevel, 0, 4);
        c.tpa.requestTimeoutSeconds = clamp(w, "c.tpa.requestTimeoutSeconds", c.tpa.requestTimeoutSeconds, 5, 3600);
        c.tpa.cooldown.seconds = clamp(w, "c.tpa.cooldown.seconds", c.tpa.cooldown.seconds, 0, 24 * 3600);
        c.tpa.cooldown.tpaSeconds = clamp(w, "c.tpa.cooldown.tpaSeconds", c.tpa.cooldown.tpaSeconds, 0, 24 * 3600);
        c.tpa.cooldown.tpahSeconds = clamp(w, "c.tpa.cooldown.tpahSeconds", c.tpa.cooldown.tpahSeconds, 0, 24 * 3600);
        if (c.persona == null) c.persona = new BotConfig.Persona();
        if (c.persona.prank == null) c.persona.prank = new BotConfig.Prank();
        // 模式名直接拼成 prompts/modes/<模式>.md，只允许安全字符，防路径穿越
        if (c.persona.mode == null || !c.persona.mode.matches("[a-z0-9_-]{1,32}")) {
            if (c.persona.mode != null) w.add("persona.mode 只能用小写字母、数字、_、-，已改为 normal");
            c.persona.mode = "normal";
        }
        if (!io.github.aftsakee.aiadmin.policy.PrankPolicy.Level.valid(c.persona.prank.level)) {
            w.add("persona.prank.level 只能是 chat_only / harmless / rough，已改为 harmless");
            c.persona.prank.level = "harmless";
        }
        c.persona.prank.immunePlayers = clean(c.persona.prank.immunePlayers);
        c.persona.prank.maxSummonsPerRun = clamp(w, "c.persona.prank.maxSummonsPerRun", c.persona.prank.maxSummonsPerRun, 0, 20);
        if (c.social == null) c.social = new BotConfig.Social();
        c.social.stopQuietHours = clamp(w, "c.social.stopQuietHours", c.social.stopQuietHours, 1, 720);
        c.social.quietDefaultHours = clamp(w, "c.social.quietDefaultHours", c.social.quietDefaultHours, 1, 720);
        c.social.frustratedDeaths = clamp(w, "c.social.frustratedDeaths", c.social.frustratedDeaths, 2, 50);
        c.social.frustratedWindowMinutes = clamp(w, "c.social.frustratedWindowMinutes", c.social.frustratedWindowMinutes, 1, 120);
        c.social.frustratedCalmMinutes = clamp(w, "c.social.frustratedCalmMinutes", c.social.frustratedCalmMinutes, 1, 240);
        c.social.guideMaxChars = clamp(w, "c.social.guideMaxChars", c.social.guideMaxChars, 500, 30000);
        if (c.social.chattiness == null || !java.util.List.of("quiet", "normal", "chatty").contains(c.social.chattiness)) {
            if (c.social.chattiness != null) w.add("social.chattiness 只能是 quiet / normal / chatty，已改为 normal");
            c.social.chattiness = "normal";
        }
        if (c.remote == null) c.remote = new BotConfig.Remote();
        if (c.remote.bind == null || c.remote.bind.isBlank()) c.remote.bind = "tailscale";
        if (c.remote.token == null) c.remote.token = "";
        if (!c.remote.token.isEmpty() && c.remote.token.length() < 16) throw new ConfigException("remote.token 太短（至少 16 个字符），或留空自动生成");
        c.remote.allowedCidrs = clean(c.remote.allowedCidrs);
        for (String cidr : c.remote.allowedCidrs) {
            try {
                io.github.aftsakee.aiadmin.util.Cidr.parse(cidr);
            } catch (IllegalArgumentException e) {
                throw new ConfigException("remote.allowed_cidrs 写错了：" + cidr);
            }
        }
        c.remote.port = clamp(w, "c.remote.port", c.remote.port, 1, 65535);
        c.remote.maxCallsPerMinute = clamp(w, "c.remote.maxCallsPerMinute", c.remote.maxCallsPerMinute, 1, 10000);
        c.remote.maxCommandsPerHour = clamp(w, "c.remote.maxCommandsPerHour", c.remote.maxCommandsPerHour, 0, 100000);
        c.remote.authFailLimit = clamp(w, "c.remote.authFailLimit", c.remote.authFailLimit, 1, 1000);
        c.remote.authFailWindowSeconds = clamp(w, "c.remote.authFailWindowSeconds", c.remote.authFailWindowSeconds, 10, 24 * 3600);
        c.remote.authBlockSeconds = clamp(w, "c.remote.authBlockSeconds", c.remote.authBlockSeconds, 10, 7 * 24 * 3600);
        c.remote.publicBaseUrl = normalizeBaseUrl(c.remote.publicBaseUrl);
        if (c.remote.oauth == null) c.remote.oauth = new BotConfig.OAuth();
        c.remote.oauth.accessTokenMinutes = clamp(w, "c.remote.oauth.accessTokenMinutes", c.remote.oauth.accessTokenMinutes, 5, 24 * 60);
        c.remote.oauth.refreshMaxDays = clamp(w, "c.remote.oauth.refreshMaxDays", c.remote.oauth.refreshMaxDays, 1, 3650);
        c.remote.oauth.refreshTokenDays = clamp(w, "c.remote.oauth.refreshTokenDays", c.remote.oauth.refreshTokenDays, 1, c.remote.oauth.refreshMaxDays);
        c.remote.oauth.maxClients = clamp(w, "c.remote.oauth.maxClients", c.remote.oauth.maxClients, 1, 500);
        c.remote.oauth.maxUnusedClients = clamp(w, "c.remote.oauth.maxUnusedClients", c.remote.oauth.maxUnusedClients, 1, 1000);
        c.remote.oauth.registerPerSource = clamp(w, "c.remote.oauth.registerPerSource", c.remote.oauth.registerPerSource, 1, 1000);
        if (c.remote.cloud == null) c.remote.cloud = new BotConfig.Cloud();
        c.remote.settableSettings = clean(c.remote.settableSettings);
        BotConfig.Cloud cl = c.remote.cloud;
        cl.route = clean(cl.route);
        for (String r : cl.route) {
            if (!r.equals("chat") && !r.equals("event") && !r.equals("patrol")) throw new ConfigException("remote.cloud.route 只能包含 chat、event、patrol：" + r);
        }
        cl.onlineTimeoutSeconds = clamp(w, "cl.onlineTimeoutSeconds", cl.onlineTimeoutSeconds, 30, 3600);
        cl.handoffSeconds = clamp(w, "cl.handoffSeconds", cl.handoffSeconds, 5, 3600);
        cl.maxWaitSeconds = clamp(w, "cl.maxWaitSeconds", cl.maxWaitSeconds, 10, 900);
        cl.defaultWaitSeconds = clamp(w, "cl.defaultWaitSeconds", cl.defaultWaitSeconds, 1, cl.maxWaitSeconds);
        cl.maxWaiters = clamp(w, "cl.maxWaiters", cl.maxWaiters, 1, 4);
        cl.maxPending = clamp(w, "cl.maxPending", cl.maxPending, 1, 1000);
        cl.autoCloseMinutes = clamp(w, "cl.autoCloseMinutes", cl.autoCloseMinutes, 1, 24 * 60);
        cl.webhookHandoffSeconds = clamp(w, "cl.webhookHandoffSeconds", cl.webhookHandoffSeconds, cl.handoffSeconds, 3600);
        cl.webhookHosts = clean(cl.webhookHosts);
        cl.webhookMaxTtlDays = clamp(w, "cl.webhookMaxTtlDays", cl.webhookMaxTtlDays, 1, 365);
        cl.webhookTtlHours = clamp(w, "cl.webhookTtlHours", cl.webhookTtlHours, 1, cl.webhookMaxTtlDays * 24);
        if (cl.enabled && (!c.remote.enabled || !c.remote.allowActions)) w.add("remote.cloud.enabled 需要 remote.enabled 和 remote.allow_actions 都为 true，云端值守暂不生效");
        c.remote.oauth.allowedRedirectHosts = clean(c.remote.oauth.allowedRedirectHosts);
        if (c.compression == null) c.compression = new BotConfig.Compression();
        BotConfig.Compression z = c.compression;
        if (z.when == null) z.when = new BotConfig.When();
        if (z.method == null) z.method = new BotConfig.Method();
        if (z.memory == null) z.memory = new BotConfig.MemoryCompression();
        if (!oneOf(z.method.type, "digest", "summarize")) throw new ConfigException("compression.method.type 只能是 digest / summarize");
        if (!oneOf(z.memory.type, "digest", "summarize")) throw new ConfigException("compression.memory.type 只能是 digest / summarize");
        if (z.method.summaryProvider == null) z.method.summaryProvider = "";
        if (!z.method.summaryProvider.isBlank() && !c.providers.containsKey(z.method.summaryProvider)) {
            throw new ConfigException("compression.method.summary_provider \"" + z.method.summaryProvider + "\" 不在 providers 里");
        }
        if (z.method.summaryPromptFile == null || z.method.summaryPromptFile.isBlank()) z.method.summaryPromptFile = "prompts/compress.md";
        if (z.memory.promptFile == null || z.memory.promptFile.isBlank()) z.memory.promptFile = "prompts/memory-compress.md";
        if (z.when.inputTokensOver < 0) z.when.inputTokensOver = 0;
        z.when.contextCharsOver = Math.max(0, z.when.contextCharsOver);
        z.when.everySteps = clamp(w, "z.when.everySteps", z.when.everySteps, 0, 50);
        z.method.keepRecentSteps = clamp(w, "z.method.keepRecentSteps", z.method.keepRecentSteps, 0, 20);
        z.method.oldResultChars = clamp(w, "z.method.oldResultChars", z.method.oldResultChars, 0, 20000);
        z.method.summaryMaxChars = clamp(w, "z.method.summaryMaxChars", z.method.summaryMaxChars, 200, 50000);
        z.method.maxPerRun = clamp(w, "z.method.maxPerRun", z.method.maxPerRun, 0, 20);
        z.memory.keepRecent = clamp(w, "z.memory.keepRecent", z.memory.keepRecent, 1, 49);
        // 上限 49：行动记录最多存 50 条（Memory.HARD_MAX），阈值等于 50 时永远触发不了压缩
        z.memory.keepRecent = Math.min(z.memory.keepRecent, 48);
        z.memory.compressWhenOver = clamp(w, "z.memory.compressWhenOver", z.memory.compressWhenOver, z.memory.keepRecent + 1, 49);
        z.memory.summaryMaxChars = clamp(w, "z.memory.summaryMaxChars", z.memory.summaryMaxChars, 200, 20000);
        c.patrol.intervalMinutes = clamp(w, "c.patrol.intervalMinutes", c.patrol.intervalMinutes, 1, 24 * 60);
        c.limits.toolResultMaxChars = clamp(w, "c.limits.toolResultMaxChars", c.limits.toolResultMaxChars, 1000, 200000);
        c.limits.queueMax = clamp(w, "c.limits.queueMax", c.limits.queueMax, 1, 50);
        c.limits.memoryEntries = clamp(w, "c.limits.memoryEntries", c.limits.memoryEntries, 0, 50);
        c.limits.runTimeoutSeconds = clamp(w, "c.limits.runTimeoutSeconds", c.limits.runTimeoutSeconds, 30, 3600);
        c.events.debounceSeconds = clamp(w, "c.events.debounceSeconds", c.events.debounceSeconds, 0, 120);
        c.events.tpsLowSeconds = clamp(w, "c.events.tpsLowSeconds", c.events.tpsLowSeconds, 5, 3600);
        return w;
    }

    static List<String> validate(Rules r, BotConfig cfg) throws ConfigException {
        List<String> w = new ArrayList<>();
        if (r.permissions == null) r.permissions = new Rules.Permissions();
        if (r.quota == null) r.quota = new Rules.QuotaRules();
        if (r.schedule == null) r.schedule = new Rules.Schedule();
        Rules.Permissions p = r.permissions;
        p.disabledTools = clean(p.disabledTools);
        p.denyRoots = clean(p.denyRoots);
        p.denyPatterns = clean(p.denyPatterns);
        p.allowOnlyRoots = clean(p.allowOnlyRoots);
        p.fileDeny = clean(p.fileDeny);
        if (p.chatSafetyOff == null) p.chatSafetyOff = new Rules.ChatSafetyOff();
        p.chatSafetyOff.players = clean(p.chatSafetyOff.players);
        for (String re : p.denyPatterns) {
            try {
                Pattern.compile(re);
            } catch (PatternSyntaxException e) {
                throw new ConfigException("rules.json permissions.deny_patterns 正则写错了：" + re);
            }
        }
        p.adminPermissionLevel = clamp(w, "p.adminPermissionLevel", p.adminPermissionLevel, 0, 4);
        p.commandPermissionLevel = clamp(w, "p.commandPermissionLevel", p.commandPermissionLevel, 0, 4);
        p.fileReadMaxBytes = clamp(w, "p.fileReadMaxBytes", p.fileReadMaxBytes, 1024, 4 << 20);

        Rules.QuotaRules q = r.quota;
        if (q.perRun == null) q.perRun = new Rules.PerRun();
        if (q.windows == null) q.windows = new ArrayList<>();
        if (q.providerBalance == null) q.providerBalance = new LinkedHashMap<>();
        for (Rules.Window win : q.windows) {
            if (win == null || win.period == null || !PERIODS.contains(win.period)) throw new ConfigException("rules.json quota.windows 里的 period 只能是 hour / day / week / month");
            if (win.maxRuns < 0 || win.maxTokens < 0 || win.maxCost < 0) throw new ConfigException("rules.json quota.windows 里的上限不能是负数");
        }
        for (Map.Entry<String, Rules.BalanceRule> e : q.providerBalance.entrySet()) {
            if (e.getValue() == null) throw new ConfigException("rules.json quota.provider_balance." + e.getKey() + " 是空的");
            if (!cfg.providers.containsKey(e.getKey())) w.add("rules.json quota.provider_balance 里的 \"" + e.getKey() + "\" 不在 config.json 的 providers 里");
        }
        if (q.chatLimits == null) q.chatLimits = new Rules.ChatLimits();
        Rules.ChatLimits cl = q.chatLimits;
        if (cl.defaults == null) cl.defaults = new Rules.ChatLimit();
        if (cl.ops == null) cl.ops = new Rules.ChatLimit();
        if (cl.normalPlayers == null) cl.normalPlayers = new Rules.ChatLimit();
        if (cl.players == null) cl.players = new LinkedHashMap<>();
        cl.players.values().removeIf(v -> v == null);
        List<Rules.ChatLimit> all = new ArrayList<>(List.of(cl.defaults, cl.ops, cl.normalPlayers));
        all.addAll(cl.players.values());
        for (Rules.ChatLimit l : all) {
            for (Integer v : new Integer[] {l.maxPerHour, l.maxPerDay, l.keywordCooldownSeconds, l.playerCooldownSeconds}) {
                if (v != null && v < 0) throw new ConfigException("rules.json quota.chat_limits 里的上限和冷却不能是负数");
            }
        }
        q.perRun.maxSteps = clamp(w, "q.perRun.maxSteps", q.perRun.maxSteps, 1, 50);
        q.perRun.maxCommands = clamp(w, "q.perRun.maxCommands", q.perRun.maxCommands, 0, 100);
        q.perRun.maxChat = clamp(w, "q.perRun.maxChat", q.perRun.maxChat, 0, 20);
        q.perRun.maxChatLength = clamp(w, "q.perRun.maxChatLength", q.perRun.maxChatLength, 20, 1000);
        q.warnAtPercent = clamp(w, "q.warnAtPercent", q.warnAtPercent, 0, 99);
        q.balanceCheckMinutes = clamp(w, "q.balanceCheckMinutes", q.balanceCheckMinutes, 0, 24 * 60);

        Rules.Schedule s = r.schedule;
        if (s.activeHours == null) s.activeHours = new ArrayList<>();
        if (s.activeWeekdays == null) s.activeWeekdays = new ArrayList<>();
        for (String h : s.activeHours) {
            if (h == null || !HOURS.matcher(h.trim()).matches()) throw new ConfigException("rules.json schedule.active_hours 格式是 \"HH:mm-HH:mm\"：" + h);
        }
        for (Integer d : s.activeWeekdays) {
            if (d == null || d < 1 || d > 7) throw new ConfigException("rules.json schedule.active_weekdays 只能是 1（周一）到 7（周日）");
        }
        s.startupDelaySeconds = clamp(w, "s.startupDelaySeconds", s.startupDelaySeconds, 0, 3600);
        s.minOnlinePlayers = clamp(w, "s.minOnlinePlayers", s.minOnlinePlayers, 0, 1000);
        s.pauseAfterConsecutiveFailures = clamp(w, "s.pauseAfterConsecutiveFailures", s.pauseAfterConsecutiveFailures, 0, 100);
        s.failurePauseMinutes = clamp(w, "s.failurePauseMinutes", s.failurePauseMinutes, 1, 24 * 60);
        return w;
    }

    private static List<String> clean(List<String> l) {
        List<String> out = new ArrayList<>();
        if (l != null) for (String s : l) if (s != null) out.add(s);
        return out;
    }

    private static boolean oneOf(String v, String... allowed) {
        if (v == null) return false;
        for (String a : allowed) if (a.equals(v)) return true;
        return false;
    }

    /**
     * remote.public_base_url：去掉末尾的 /；必须是 https，或者指向本机的 http（测试用）；不能带路径、查询、用户信息。
     */
    static String normalizeBaseUrl(String s) throws ConfigException {
        if (s == null || s.isBlank()) return "";
        String v = s.trim();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        java.net.URI u;
        try {
            u = new java.net.URI(v);
        } catch (java.net.URISyntaxException e) {
            throw new ConfigException("remote.public_base_url 不是有效网址：" + s);
        }
        String host = u.getHost();
        boolean local = host != null && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]"));
        // 去掉默认端口：浏览器发的 Origin 不带 :443 / :80，带着就永远对不上
        if (("https".equals(u.getScheme()) && u.getPort() == 443) || ("http".equals(u.getScheme()) && u.getPort() == 80)) {
            v = u.getScheme() + "://" + host;
        }
        if (host == null || u.getUserInfo() != null || u.getRawQuery() != null || u.getRawFragment() != null
                || (u.getRawPath() != null && !u.getRawPath().isEmpty())
                || !("https".equals(u.getScheme()) || ("http".equals(u.getScheme()) && local))) {
            throw new ConfigException("remote.public_base_url 只能是 https://主机名[:端口]（本机测试可用 http://127.0.0.1:端口），不能带路径：" + s);
        }
        return v;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** 越界就修正，并把"原值 → 修正值"写进警告（以前静默修正，服主看不出为什么设置没按写的生效）。 */
    private static int clamp(List<String> w, String key, int v, int lo, int hi) {
        int r = clamp(v, lo, hi);
        if (r != v) w.add(display(key) + " = " + v + " 超出范围 [" + lo + ", " + hi + "]，按 " + r + " 处理");
        return r;
    }

    /** Java 字段路径 → 设置文件里的键名：c.remote.maxCallsPerMinute → remote.max_calls_per_minute。 */
    static String display(String javaPath) {
        String s = javaPath;
        String[][] prefixes = {{"c.", ""}, {"cl.", "remote.cloud."}, {"z.", "compression."}, {"cd.", "chat.conditions[]."}, {"p.", "permissions."},
                {"r.", ""}, {"q.", "quota."}};
        for (String[] pf : prefixes) {
            if (s.startsWith(pf[0])) {
                s = pf[1] + s.substring(pf[0].length());
                break;
            }
        }
        return s.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 拼错的键、类型不对的值：Gson 会静默忽略未知键，把字符串 "yes" 宽松解析成布尔 false（机器人直接停用），
     * 服主完全看不出来。这里按设置类的字段（含嵌套对象、Map 的值、列表元素）逐个比对，给出警告；不改任何值。
     */
    static void schemaWarnings(com.google.gson.JsonElement json, Class<?> type, String path, List<String> w) {
        if (json == null || !json.isJsonObject() || w.size() > 50) return;
        Map<String, java.lang.reflect.Field> fields = new LinkedHashMap<>();
        for (Class<?> k = type; k != null && k != Object.class; k = k.getSuperclass()) {
            for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                int mod = f.getModifiers();
                if (java.lang.reflect.Modifier.isStatic(mod) || java.lang.reflect.Modifier.isTransient(mod)) continue;
                fields.putIfAbsent(GSON.fieldNamingStrategy().translateName(f), f);
            }
        }
        for (Map.Entry<String, com.google.gson.JsonElement> e : json.getAsJsonObject().entrySet()) {
            String p = path.isEmpty() ? e.getKey() : path + "." + e.getKey();
            java.lang.reflect.Field f = fields.get(e.getKey());
            if (f == null) {
                w.add("未知设置 " + p + "（拼错了？这一项不会生效）");
                continue;
            }
            Class<?> ft = f.getType();
            com.google.gson.JsonElement v = e.getValue();
            if ((ft == boolean.class || ft == Boolean.class) && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) {
                w.add(p + " 应该写 true 或 false（不带引号），现在是 \"" + v.getAsString() + "\"，按 " + Boolean.parseBoolean(v.getAsString()) + " 处理");
            } else if (configClass(ft)) {
                schemaWarnings(v, ft, p, w);
            } else if (Map.class.isAssignableFrom(ft) && v.isJsonObject()) {
                Class<?> vt = typeArg(f, 1);
                if (configClass(vt)) for (Map.Entry<String, com.google.gson.JsonElement> x : v.getAsJsonObject().entrySet()) schemaWarnings(x.getValue(), vt, p + "." + x.getKey(), w);
            } else if (List.class.isAssignableFrom(ft) && v.isJsonArray()) {
                Class<?> et = typeArg(f, 0);
                if (configClass(et)) for (int i = 0; i < v.getAsJsonArray().size(); i++) schemaWarnings(v.getAsJsonArray().get(i), et, p + "[" + i + "]", w);
            }
        }
    }

    private static boolean configClass(Class<?> c) {
        return c != null && c.getName().startsWith("io.github.aftsakee.aiadmin.config.") && !c.isEnum();
    }

    private static Class<?> typeArg(java.lang.reflect.Field f, int i) {
        if (f.getGenericType() instanceof java.lang.reflect.ParameterizedType pt && pt.getActualTypeArguments().length > i
                && pt.getActualTypeArguments()[i] instanceof Class<?> c) return c;
        return null;
    }
}
