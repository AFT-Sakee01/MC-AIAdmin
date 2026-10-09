package io.github.aftsakee.aiadmin.agent;

import io.github.aftsakee.aiadmin.config.BotConfig;
import io.github.aftsakee.aiadmin.config.Rules;
import io.github.aftsakee.aiadmin.log.JsonlLog;
import io.github.aftsakee.aiadmin.util.Text;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 玩家聊天收集（从 AgentService 拆出，1.0.0.34）：公屏聊天按聊天模式 / 关键词 / 自定义条件判断要不要唤醒，/ai 私聊按人合并，
 * 冷却和每人每小时 / 每天唤醒上限，"别说了"，热闹度统计；合并到点把一批消息交给 AgentService 提交（云端优先、本地兜底）。
 * <ul>
 *   <li>锁：和 AgentService <b>共用同一把锁</b>（构造时传入的 lock 就是服务对象）。原因：判断时要读服务的锁定 / 启停状态，
 *       合并到点要回调服务的 autoBlock 和 submit；如果单独用一把锁，"服务 → 聊天"（status、resetOverrides、tick 清理）和
 *       "聊天 → 服务"（flush）两个方向会形成锁顺序反转。拆分只为可读性，并发模型和拆分前一样。</li>
 *   <li>线程：onChat / onPrivateChat 在服务端主线程调用，只做内存操作；合并到点的 flush 在调度定时线程上执行。
 *       锁内的 host.tellPlayer 只是把消息交给主线程就返回，不等待。</li>
 *   <li>时间：取自注入的时钟；合并的延迟交给 {@link Owner#timer()}。</li>
 * </ul>
 */
final class ChatIntake {
    /** 一批 /ai 私聊最多合并几条（多出来的告诉玩家等回复，不静默丢弃）。 */
    static final int MAX_PRIVATE_BATCH = 10;
    /** 没触发、但有一批在等待时作为上下文带上的消息，最多几条。 */
    private static final int MAX_CONTEXT_LINES = 20;
    static final long HOUR_MS = 3600_000L;
    static final long DAY_MS = 86_400_000L;

    /** 服务提供给聊天收集的几样东西。都在共用锁内调用（服务的这些方法本来就在同一把锁上，可重入）。 */
    interface Owner {
        /** 没被锁定（设置文件正常）且自动运行开着。 */
        boolean accepting();

        /** 自动触发现在能不能跑，见 AgentService 的 autoBlock：返回拒绝原因或 null。 */
        String autoBlock(Trigger.Kind k);

        /** 提交一次运行（云端优先、本地兜底），返回是否收下。 */
        boolean submit(Trigger t);

        /** 调度定时器；服务还没启动时为 null。 */
        Scheduler timer();
    }

    private final Object lock;
    private final Owner owner;
    private final Supplier<BotConfig> cfg;
    private final Supplier<Rules> rules;
    private final AgentService.Host host;
    private final PlayerPrefs prefs;
    private final JsonlLog log;
    private final Clock clock;

    private String modeOverride;
    private Activity activity;
    /** 关键词 / all 模式的冷却："kw:玩家" / "all:玩家" → 上次唤醒时间。 */
    private final Map<String, Long> chatLast = new HashMap<>();
    /** 每名玩家最近 24 小时的唤醒时间（同时用于每小时和每天上限）。 */
    private final Map<String, ArrayDeque<Long>> chatHour = new HashMap<>();
    private final List<String> pendingChat = new ArrayList<>();
    private boolean chatFlushPending;
    private final List<String> pendingReasons = new ArrayList<>();
    /** /ai 私聊：每名玩家各自合并（小写名 → 待发消息）。 */
    private final Map<String, List<String>> pendingPrivate = new HashMap<>();
    /** 热闹度统计：全服 / 每名玩家最近的聊天时间。 */
    private final ArrayDeque<Long> chatAll = new ArrayDeque<>();
    private final Map<String, ArrayDeque<Long>> chatByPlayer = new HashMap<>();
    /** 自定义条件各自的冷却。 */
    private final Map<String, Long> conditionFired = new HashMap<>();

    ChatIntake(Object lock, Owner owner, Supplier<BotConfig> cfg, Supplier<Rules> rules, AgentService.Host host, PlayerPrefs prefs,
               JsonlLog log, Clock clock) {
        this.lock = lock;
        this.owner = owner;
        this.cfg = cfg;
        this.rules = rules;
        this.host = host;
        this.prefs = prefs;
        this.log = log;
        this.clock = clock;
    }

    void setActivity(Activity a) {
        synchronized (lock) {
            activity = a;
        }
    }

    String mode() {
        synchronized (lock) {
            return modeOverride != null ? modeOverride : cfg.get().chat.mode;
        }
    }

    /** 运行时切换聊天模式（reload 或重启后恢复设置文件的值）。 */
    String setMode(String mode) {
        if (!Set.of("off", "mention", "custom", "all").contains(mode)) return "聊天模式只能是 off / mention / custom / all";
        synchronized (lock) {
            modeOverride = mode;
        }
        return "聊天模式已改为 " + mode + "（reload 或重启后恢复设置文件的 " + cfg.get().chat.mode + "）";
    }

    void resetMode() {
        synchronized (lock) {
            modeOverride = null;
        }
    }

    /** 按玩家 / 条件累计的冷却表：长期运行要定期清掉（冷却最长 24 小时，一天前的记录已经没用）。 */
    void prune(long now) {
        synchronized (lock) {
            chatLast.values().removeIf(t -> now - t > DAY_MS);
            conditionFired.values().removeIf(t -> now - t > DAY_MS);
        }
    }

    /**
     * 玩家公屏聊天（服务端主线程调用，只做内存操作）。返回这条消息是否会唤醒机器人。
     * <ul>
     *   <li>mention：只理会带关键词的消息；custom：关键词或 chat.conditions 任一满足；all：每条都看。</li>
     *   <li>同一玩家 player_cooldown_seconds 内不重复唤醒，但若一次回复正在合并等待中，新消息并入这一批；每小时有上限。</li>
     *   <li>debounce_seconds 内的消息合并成一次运行。</li>
     *   <li>rules.json permissions.chat_safety_off 命中的玩家，消息带【已授权】标记，提示词对他们不加安全限制（指令黑名单仍生效）。</li>
     * </ul>
     * 热闹度统计记录所有聊天（不论模式），所以切到 custom 后立即有数据。
     */
    boolean onChat(String player, String text, boolean isOp) {
        if (text == null || text.isBlank()) return false;
        synchronized (lock) {
            BotConfig c = cfg.get();
            long now = clock.millis();
            recordChat(player, now);
            String mode = mode();
            if (!owner.accepting() || mode.equals("off")) return false;
            // 按群体/个人解析上限（rules.json quota.chat_limits：指定玩家 > OP/普通玩家 > defaults > config.json 的 chat）
            Rules.ChatLimits.Effective lim = rules.get().quota.chatLimits.resolve(player, isOp, c.chat.maxPerPlayerPerHour,
                    c.chat.keywordCooldownSeconds, c.chat.playerCooldownSeconds);
            if (lim.ignore()) return false;
            boolean trusted = rules.get().permissions.chatSafetyOff.appliesTo(player, isOp);
            boolean called = mentioned(c, text);
            // 频道标记：叫了机器人的是 [@你]，其余是 [公屏]（玩家之间的聊天，被条件唤醒或作为同一段对话带上）
            String line = (called ? Trigger.TAG_MENTION : Trigger.TAG_PUBLIC) + " " + player + (trusted ? "【已授权】" : "") + "：" + text;
            String pkey = player.toLowerCase(Locale.ROOT);
            // "别说了"：只有对机器人说（叫了它，或它刚对这名玩家说过话）才算，玩家之间的"别说了"不算。直接回一句确认，不唤醒 AI
            if (PlayerPrefs.stopPhrase(text) && (called || addressedRecently(player, now))) {
                stopTalking(player, c.social.stopQuietHours, "聊天里说了：" + Text.brief(text, 40));
                return false;
            }
            // 安静中的玩家：没叫机器人的话不唤醒（照样作为上下文带上）；叫了机器人照常回答
            if (!called && prefs.quiet(player)) {
                addContext(line);
                return false;
            }
            // 各触发方式的冷却分开计：关键词 / all 按玩家；自定义条件在 matchCondition 里用各自的 cooldown_seconds
            String reason = null;
            String coolKey = null;
            long coolMs = 0;
            if (mode.equals("all")) {
                coolKey = "all:" + pkey;
                coolMs = lim.playerCooldownSeconds() * 1000L;
            } else if (called) {
                coolKey = "kw:" + pkey;
                coolMs = lim.keywordCooldownSeconds() * 1000L;
            } else if (mode.equals("custom")) {
                reason = matchCondition(c, player, text, now);
                if (reason == null) {
                    addContext(line); // 没触发，但有一批在等待：作为同一段对话带上
                    return false;
                }
            } else {
                return false; // mention 模式没叫它
            }
            if (coolKey != null) {
                Long last = chatLast.get(coolKey);
                if (last != null && now - last < coolMs) {
                    addContext(line); // 合并进正在等待的这一批
                    return false;
                }
            }
            String full = takeWakeSlot(pkey, lim, now);
            if (full != null) {
                log.info("agent", "chat.limited", player + (full.equals("hour") ? " 本小时唤醒次数已满（" + lim.maxPerHour() : " 24 小时内唤醒次数已满（" + lim.maxPerDay()) + "）",
                        null, null);
                return false;
            }
            if (coolKey != null) chatLast.put(coolKey, now);
            if (chatHour.size() > 500) chatHour.entrySet().removeIf(e -> e.getValue().isEmpty());
            if (reason != null && !pendingReasons.contains(reason)) pendingReasons.add(reason);
            pendingChat.add(line);
            Scheduler timer = owner.timer();
            if (!chatFlushPending && timer != null && !timer.isShutdown()) {
                chatFlushPending = true;
                timer.schedule(this::flushChat, c.chat.debounceSeconds * 1000L);
            }
            return true;
        }
    }

    /** 有一批正在合并等待时，把没触发的消息作为同一段对话带上（有上限）。只在锁内调用。 */
    private void addContext(String line) {
        if (chatFlushPending && pendingChat.size() < MAX_CONTEXT_LINES) pendingChat.add(line);
    }

    /**
     * /ai 私聊（服务端主线程调用，只做内存操作）。返回 null = 收下了（会合并 debounce 秒后回复），否则是要告诉玩家的原因。
     * 和公屏聊天共用每人每小时/每天上限；冷却按关键词冷却计；回复强制只私聊给他（ActionTools 的 send_chat）。
     */
    String onPrivateChat(String player, String text, boolean isOp) {
        BotConfig c = cfg.get();
        if (text == null || text.isBlank()) return "要说点什么：/ai <内容>";
        if (!c.social.privateChat) return "服主没有开放 /ai 私聊";
        synchronized (lock) {
            long now = clock.millis();
            if (!owner.accepting() || mode().equals("off")) return c.botName + " 现在不聊天";
            Rules.ChatLimits.Effective lim = rules.get().quota.chatLimits.resolve(player, isOp, c.chat.maxPerPlayerPerHour,
                    c.chat.keywordCooldownSeconds, c.chat.playerCooldownSeconds);
            if (lim.ignore()) return c.botName + " 现在不回你的消息（服主的设置）";
            String clean = text.replaceAll("[\\r\\n\\p{Cntrl}]", " ").trim();
            // 只有收下的消息才进动态：flushPrivate 按"最近的私聊往来去掉本批条数"拼上下文，被拒的也记进去会让上下文错位
            if (PlayerPrefs.stopPhrase(clean)) {
                if (activity != null) activity.add("ai_private", player, clean);
                stopTalking(player, c.social.stopQuietHours, "/ai 里说了：" + Text.brief(clean, 40));
                return null;
            }
            String pkey = player.toLowerCase(Locale.ROOT);
            boolean trusted = rules.get().permissions.chatSafetyOff.appliesTo(player, isOp);
            String line = Trigger.TAG_PRIVATE + " " + player + (trusted ? "【已授权】" : "") + "：" + clean;
            List<String> pend = pendingPrivate.get(pkey);
            if (pend != null) { // 正在合并：并入同一批，不另算一次
                if (pend.size() >= MAX_PRIVATE_BATCH) return "一次说太多了，等 " + c.botName + " 回复后再说";
                pend.add(line);
                if (activity != null) activity.add("ai_private", player, clean);
                return null;
            }
            Long last = chatLast.get("kw:" + pkey);
            if (last != null && now - last < lim.keywordCooldownSeconds() * 1000L) return "说得太快了，等几秒再发";
            String full = takeWakeSlot(pkey, lim, now);
            if (full != null) return (full.equals("hour") ? "这一小时" : "今天") + "找 " + c.botName + " 的次数用完了";
            chatLast.put("kw:" + pkey, now);
            if (activity != null) activity.add("ai_private", player, clean);
            List<String> list = new ArrayList<>();
            list.add(line);
            pendingPrivate.put(pkey, list);
            Scheduler timer = owner.timer();
            if (timer != null && !timer.isShutdown()) timer.schedule(() -> flushPrivate(pkey, player), c.chat.debounceSeconds * 1000L);
            return null;
        }
    }

    private void flushPrivate(String pkey, String player) {
        synchronized (lock) {
            List<String> batch = pendingPrivate.remove(pkey);
            if (batch == null || batch.isEmpty()) return;
            String why = owner.autoBlock(Trigger.Kind.CHAT);
            if (why != null) {
                log.info("agent", "trigger.dropped", "不回应私聊：" + why, null, JsonlLog.data("player", player));
                host.tellPlayer(player, cfg.get().botName + " 现在没法回复（" + why + "）");
                return;
            }
            // 上下文：和这名玩家最近的私聊往来 + 最近几句公屏（标频道）
            StringBuilder ctx = new StringBuilder();
            if (activity != null) {
                String botName = cfg.get().botName;
                List<Activity.Entry> mine = new ArrayList<>();
                for (Activity.Entry e : activity.recent(200, Set.of("ai_private", "ai_chat"))) {
                    if (e.kind().equals("ai_private") && e.who().equalsIgnoreCase(player)) mine.add(e);
                    else if (e.kind().equals("ai_chat") && e.text().toLowerCase(Locale.ROOT).startsWith("→" + pkey + " ")) mine.add(e);
                }
                int from = Math.max(0, mine.size() - 8 - batch.size());
                for (Activity.Entry e : mine.subList(from, Math.max(from, mine.size() - batch.size()))) {
                    ctx.append('[').append(Text.time(e.time()).substring(11)).append("] ")
                            .append(e.kind().equals("ai_private") ? "[私聊你] " + e.who() + "：" + e.text() : "[你私聊他] " + botName + "：" + e.text().substring(e.text().indexOf(' ') + 1))
                            .append('\n');
                }
                ctx.append(publicContext(4));
            }
            owner.submit(Trigger.privateChat(player, batch, ctx.toString().trim()));
        }
    }

    private void flushChat() {
        synchronized (lock) {
            chatFlushPending = false;
            if (pendingChat.isEmpty()) return;
            List<String> batch = new ArrayList<>();
            for (String r : pendingReasons) batch.add("（触发原因：" + r + "）");
            batch.addAll(pendingChat);
            pendingChat.clear();
            pendingReasons.clear();
            String why = owner.autoBlock(Trigger.Kind.CHAT);
            if (why != null) {
                log.info("agent", "trigger.dropped", "不回应聊天：" + why, null, JsonlLog.data("messages", batch));
                return;
            }
            owner.submit(Trigger.chat(batch, publicContext(cfg.get().chat.contextMessages).trim()));
        }
    }

    /** 最近 n 句公屏聊天（含机器人的公开发言），每句标频道；机器人私聊别人的话不放进来（别人的私聊不外泄）。只在锁内调用。 */
    private String publicContext(int n) {
        StringBuilder sb = new StringBuilder();
        if (activity == null || n <= 0) return "";
        BotConfig c = cfg.get();
        List<Activity.Entry> list = new ArrayList<>();
        for (Activity.Entry e : activity.recent(n * 3, Set.of("chat", "ai_chat"))) {
            if (e.kind().equals("ai_chat") && e.text().startsWith("→")) continue;
            list.add(e);
        }
        for (Activity.Entry e : list.subList(Math.max(0, list.size() - n), list.size())) {
            String tag = e.kind().equals("ai_chat") ? "[你·公屏]" : mentioned(c, e.text()) ? Trigger.TAG_MENTION : Trigger.TAG_PUBLIC;
            sb.append('[').append(Text.time(e.time()).substring(11)).append("] ").append(tag).append(' ').append(e.who()).append("：").append(e.text()).append('\n');
        }
        return sb.toString();
    }

    /** 机器人最近 3 分钟有没有对这名玩家说过话（私聊他，或公开发言里提到他）。只在锁内调用。 */
    private boolean addressedRecently(String player, long now) {
        if (activity == null) return false;
        String k = player.toLowerCase(Locale.ROOT);
        for (Activity.Entry e : activity.recent(30, Set.of("ai_chat"))) {
            if (now - e.time() > 180_000L) continue;
            String t = e.text().toLowerCase(Locale.ROOT);
            if (t.startsWith("→" + k + " ") || (k.length() >= 3 && t.contains(k))) return true;
        }
        return false;
    }

    /**
     * 每人每小时 / 每 24 小时唤醒上限（公屏聊天和 /ai 私聊共用）：没超就记一次并返回 null，超了返回 "hour" 或 "day"。
     * chatHour 里存这名玩家最近 24 小时的唤醒时间，同时用于两种上限。只在锁内调用。
     */
    private String takeWakeSlot(String pkey, Rules.ChatLimits.Effective lim, long now) {
        ArrayDeque<Long> hour = chatHour.computeIfAbsent(pkey, k -> new ArrayDeque<>());
        while (!hour.isEmpty() && now - hour.peekFirst() > DAY_MS) hour.pollFirst();
        long lastHour = hour.stream().filter(t -> now - t <= HOUR_MS).count();
        if (lim.maxPerHour() > 0 && lastHour >= lim.maxPerHour()) return "hour";
        if (lim.maxPerDay() > 0 && hour.size() >= lim.maxPerDay()) return "day";
        hour.addLast(now);
        return null;
    }

    /** 玩家说"别说了"：安静 hours 小时，直接回一句确认（不经过模型）。 */
    private void stopTalking(String player, int hours, String why) {
        prefs.extendQuiet(player, hours, "玩家自己（" + why + "）"); // 已有更长的安静（/ai quiet 24）不缩短
        log.info("agent", "prefs.quiet", player + " 让机器人别说了：安静 " + hours + " 小时", null, JsonlLog.data("player", player, "hours", hours));
        host.tellPlayer(player, "好的，我先不打扰你（" + hours + " 小时）。直接 @我 或 /ai 还会回答你；/ai resume 恢复，/ai style serious 以后只认真答疑。");
    }

    /** 热闹度统计：全服和每名玩家最近的聊天时间（最多保留 24 小时、各 500 条）。只在锁内调用。 */
    private void recordChat(String player, long now) {
        chatAll.addLast(now);
        while (chatAll.size() > 500 || (!chatAll.isEmpty() && now - chatAll.peekFirst() > DAY_MS)) chatAll.pollFirst();
        ArrayDeque<Long> d = chatByPlayer.computeIfAbsent(player.toLowerCase(Locale.ROOT), k -> new ArrayDeque<>());
        d.addLast(now);
        while (d.size() > 500 || (!d.isEmpty() && now - d.peekFirst() > DAY_MS)) d.pollFirst();
        if (chatByPlayer.size() > 1000) chatByPlayer.entrySet().removeIf(e -> e.getValue().isEmpty() || now - e.getValue().peekLast() > HOUR_MS);
    }

    /** custom 模式：返回第一个满足（且不在自身冷却中）的条件的说明，都不满足返回 null。只在锁内调用。 */
    private String matchCondition(BotConfig c, String player, String text, long now) {
        for (int i = 0; i < c.chat.conditions.size(); i++) {
            BotConfig.Condition cd = c.chat.conditions.get(i);
            boolean global = cd.type.equals("burst") && cd.scope.equals("all");
            String key = i + ":" + cd.type + (global ? "" : ":" + player.toLowerCase(Locale.ROOT));
            Long last = conditionFired.get(key);
            if (last != null && now - last < cd.cooldownSeconds * 1000L) continue;
            String why = switch (cd.type) {
                case "burst" -> {
                    ArrayDeque<Long> d = cd.scope.equals("player") ? chatByPlayer.getOrDefault(player.toLowerCase(Locale.ROOT), new ArrayDeque<>()) : chatAll;
                    long n = d.stream().filter(t -> now - t <= cd.windowSeconds * 1000L).count();
                    yield n > cd.moreThan ? (cd.scope.equals("player") ? player + " " : "聊天很热闹：") + cd.windowSeconds + " 秒内 " + n + " 句" : null;
                }
                case "regex" -> java.util.regex.Pattern.compile(cd.pattern, java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text).find()
                        ? "消息匹配规则 " + cd.pattern : null;
                case "players" -> cd.players.stream().anyMatch(p -> p.equalsIgnoreCase(player)) ? "关注的玩家 " + player + " 说话了" : null;
                case "question" -> text.trim().matches("(?s).*[?？吗呢]$") ? player + " 提了个问题" : null;
                default -> null;
            };
            if (why != null) {
                conditionFired.put(key, now);
                return cd.note.isBlank() ? why : why + "（" + cd.note + "）";
            }
        }
        return null;
    }

    /**
     * 生效的关键词：chat.keywords 为空时用默认的 bot_name、"@AI"、"@aibot"、"@ai管家"。
     * 匹配是"包含、不分大小写"，所以 "@AI" 本身已能匹配 "@aibot"；把 "@aibot" 单列出来是为了在 /aibot status 里让人看得到。
     */
    static List<String> keywords(BotConfig c) {
        return c.chat.keywords.isEmpty() ? List.of(c.botName, "@AI", "@aibot", "@ai管家") : c.chat.keywords;
    }

    static boolean mentioned(BotConfig c, String text) {
        String t = text.toLowerCase(Locale.ROOT);
        for (String k : keywords(c)) if (!k.isBlank() && t.contains(k.toLowerCase(Locale.ROOT))) return true;
        return false;
    }
}
