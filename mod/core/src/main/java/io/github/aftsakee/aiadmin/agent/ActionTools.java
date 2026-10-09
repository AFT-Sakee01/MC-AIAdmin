package io.github.aftsakee.aiadmin.agent;

import com.google.gson.JsonObject;
import io.github.aftsakee.aiadmin.config.BotConfig;
import io.github.aftsakee.aiadmin.config.Rules;
import io.github.aftsakee.aiadmin.log.JsonlLog;
import io.github.aftsakee.aiadmin.policy.CommandPolicy;
import io.github.aftsakee.aiadmin.policy.PrankPolicy;
import io.github.aftsakee.aiadmin.util.Text;

import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 代理影响服务器的仅有两个工具：run_command（后台指令）和 send_chat（聊天）。
 * 所有限制都在这里集中执行，平台层（NeoForge）只负责真正执行：黑名单 → 每次运行的次数上限 → 执行 → 审计日志。
 */
public final class ActionTools {
    public static final String RUN_COMMAND = "run_command";
    public static final String SEND_CHAT = "send_chat";
    private static final Pattern FORMAT = Pattern.compile("§.?");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");
    /** 选择器里指定的玩家名（name=Steve；name=!Steve 不算）。 */
    private static final Pattern SELECTOR_NAME = Pattern.compile("(?i)name=([A-Za-z0-9_]{1,16})(?![A-Za-z0-9_])");

    private ActionTools() {}

    /** 平台层实现：在服务端主线程执行。 */
    public interface ServerActions {
        record CommandOutcome(boolean success, int result, String output) {}

        CommandOutcome runCommand(String command, RunContext ctx) throws Exception;

        /** 成功返回 null，失败返回原因（例如玩家不在线）。target 为 null = 全服广播。 */
        String sendChat(String message, String target, RunContext ctx) throws Exception;
    }

    /**
     * 只读模式的兜底：远程接口 remote.allow_actions=false 时，经远程调用到执行指令/说话的处理器一律拒绝。
     * 正常情况下这些工具在只读模式下根本不会出现在远程工具表里（ToolAccess.hidden）；这里防的是以后新加的工具
     * （宏之类）在内部转调本处理器、而远程隐藏名单漏登记的情况——1.0.0.27 的 run_macro 就是这样漏的。
     */
    static void requireRemoteActions(BotConfig c, RunContext ctx) {
        if (ctx != null && ctx.trigger != null && ctx.trigger.kind() == Trigger.Kind.REMOTE && !c.remote.allowActions) {
            throw new IllegalStateException("远程接口没有开放动作（remote.allow_actions=false），不能执行指令或说话");
        }
    }

    /**
     * 说话类指令的目标和文本：tell/msg/w/tellraw/title（第二个参数是目标）、say/me/teammsg/tm（广播）。
     * 返回 [目标玩家名或 null（广播/选不出具体玩家）, 文本（tellraw/title 是 JSON 原文，按子串检查就够）]；不是说话类返回 null。
     */
    static String[] speech(String layer) {
        String root = CommandPolicy.root(layer);
        String[] t = layer.split(" ", 3);
        return switch (root) {
            case "say", "me", "teammsg", "tm" -> new String[] {null, t.length > 1 ? layer.substring(t[0].length() + 1) : ""};
            case "tell", "msg", "w", "tellraw", "title" -> t.length < 2 ? null : new String[] {targetName(t[1]), t.length > 2 ? t[2] : ""};
            default -> null;
        };
    }

    private static String targetName(String target) {
        if (!target.startsWith("@")) return target;
        java.util.regex.Matcher m = SELECTOR_NAME.matcher(target);
        return m.find() ? m.group(1) : null;
    }

    /**
     * "别说了"/回避话题（PlayerPrefs，代码层强制）对所有 AI 发言生效：send_chat，以及 run_command 里的说话类指令
     * （含 execute … run 嵌套的）。只有管理员 /aibot ask 不受限。远程（云端）也受限：云端处理玩家聊天时
     * 除了 reply_chat 外的调用都以远程身份进来，豁免远程等于让玩家聊天里的提示注入绕过本人意愿。返回拒绝原因或 null。
     */
    static String speechBlocked(PlayerPrefs pp, RunContext ctx, String message, String target) {
        if (pp == null || ctx == null || ctx.trigger == null || ctx.trigger.kind() == Trigger.Kind.ADMIN) return null;
        return pp.chatBlocked(message, target, ctx.trigger.direct());
    }

    /**
     * 恶作剧白名单只管"恶作剧模式下回复普通玩家聊天"：巡检、事件、管理员提问照常；
     * 这批聊天里有【已授权】玩家时也照常（他们的管理要求本来就允许执行，见 Prompts.user）。
     */
    static boolean prankApplies(RunContext ctx) {
        return Personas.PRANK.equals(ctx.persona()) && ctx.trigger.kind() == Trigger.Kind.CHAT
                && ctx.trigger.events().stream().noneMatch(e -> e.contains("【已授权】"));
    }

    public static void register(Tools tools, ServerActions actions, Supplier<BotConfig> cfg, Supplier<Rules> rules, JsonlLog log, Activity activity) {
        register(tools, actions, cfg, rules, log, activity, () -> null);
    }

    /** prefs：玩家互动偏好（可能为 null，例如机器人还没启动）——发言前检查"别说了"和不想提的话题，恶作剧免疫加上不该整的玩家。 */
    public static void register(Tools tools, ServerActions actions, Supplier<BotConfig> cfg, Supplier<Rules> rules, JsonlLog log, Activity activity,
                                Supplier<PlayerPrefs> prefs) {
        register(tools, actions, cfg, rules, log, activity, prefs, () -> null);
    }

    /** notes：指令资料（可能为 null）——每次执行的成败记进去，note_command 的"已验证"以此为准（模型自己说了不算）。 */
    public static void register(Tools tools, ServerActions actions, Supplier<BotConfig> cfg, Supplier<Rules> rules, JsonlLog log, Activity activity,
                                Supplier<PlayerPrefs> prefs, Supplier<CommandNotes> notes) {
        tools.add(Tools.schema(RUN_COMMAND,
                        "以服务器后台控制台的身份执行一条 Minecraft 指令（不带 /），返回是否成功和指令输出。"
                                + "可以用来查询（如 data get、scoreboard、execute if）或处理问题（tp、kick、kill @e[type=item]、weather 等）。"
                                + "部分危险指令被服主禁止，被拒绝时不要换写法绕过。")
                        .str("command", "完整指令，例如 time set day 或 kill @e[type=minecraft:item,distance=..64]", true)
                        .build(),
                (args, ctx) -> {
                    String cmd = CommandPolicy.normalize(Tools.optStr(args, "command", ""));
                    if (cmd.isEmpty()) throw new IllegalArgumentException("command 不能为空");
                    BotConfig c = cfg.get();
                    requireRemoteActions(c, ctx);
                    Rules.Permissions perm = rules.get().permissions;
                    if (!perm.allowCommands) throw new IllegalStateException("服主已禁止代理执行指令");
                    CommandPolicy.Verdict v = new CommandPolicy(perm).check(cmd);
                    if (v.allowed()) {
                        // 用指令私聊/点名（tell、tellraw、title、say…）同样要过"别说了"和回避话题，否则换个工具就绕过了
                        PlayerPrefs pp = prefs.get();
                        for (String layer : CommandPolicy.layers(cmd)) {
                            String[] sp = speech(layer);
                            String why = sp == null ? null : speechBlocked(pp, ctx, sp[1], sp[0]);
                            if (why != null) {
                                v = new CommandPolicy.Verdict(false, why);
                                break;
                            }
                        }
                    }
                    boolean prank = prankApplies(ctx);
                    if (v.allowed() && prank) {
                        java.util.Set<String> immune = PlayerPrefs.prankImmune(c, prefs.get()); // 和提示词里列出的同一份
                        v = PrankPolicy.check(cmd, ctx.prankLevel(), immune, ctx::summons, c.persona.prank.maxSummonsPerRun);
                    }
                    JsonObject out = new JsonObject();
                    if (!v.allowed()) {
                        log.warn("agent", "tool.command_denied", "拒绝执行：" + cmd, ctx.cid, JsonlLog.data("command", cmd, "reason", v.reason()));
                        ctx.action("被拒绝的指令 " + cmd);
                        out.addProperty("allowed", false);
                        out.addProperty("reason", v.reason());
                        return out.toString();
                    }
                    if (!ctx.tryCommand()) {
                        out.addProperty("allowed", false);
                        out.addProperty("reason", "本次运行可执行的指令数已用完（上限 " + ctx.rules.quota.perRun.maxCommands + "）");
                        return out.toString();
                    }
                    if (prank && cmd.toLowerCase(java.util.Locale.ROOT).startsWith("summon")) ctx.addSummon();
                    ServerActions.CommandOutcome r = actions.runCommand(cmd, ctx);
                    log.info("agent", "tool.command", "执行指令：" + cmd, ctx.cid,
                            JsonlLog.data("command", cmd, "success", r.success(), "result", r.result(), "output", Text.brief(r.output(), 500)));
                    activity.add("ai_command", c.botName, cmd);
                    ctx.action("指令 " + cmd + (r.success() ? "" : "（失败）"));
                    CommandNotes cn = notes.get();
                    if (cn != null) cn.recordRun(cmd, r.success());
                    out.addProperty("allowed", true);
                    out.addProperty("success", r.success());
                    out.addProperty("result", r.result());
                    out.addProperty("output", r.output());
                    return out.toString();
                });

        tools.add(Tools.schema(SEND_CHAT,
                        "在游戏聊天里以机器人身份说话。不填 player = 全服公告；填 player = 只私聊这名在线玩家。"
                                + "消息要简短（建议 50 字以内），用玩家的语言。")
                        .str("message", "要发送的内容（纯文本，不支持颜色代码）", true)
                        .str("player", "私聊对象的玩家名（可选）", false)
                        .build(),
                (args, ctx) -> {
                    BotConfig c = cfg.get();
                    requireRemoteActions(c, ctx);
                    Rules.Permissions perm = rules.get().permissions;
                    int maxLen = ctx.rules.quota.perRun.maxChatLength;
                    String msg = CONTROL.matcher(FORMAT.matcher(Tools.optStr(args, "message", "")).replaceAll("")).replaceAll(" ").trim(); // 去掉 § 颜色代码和控制字符
                    if (msg.isEmpty()) throw new IllegalArgumentException("message 不能为空");
                    if (msg.length() > maxLen) msg = msg.substring(0, maxLen) + "…";
                    String target = Tools.optStr(args, "player", null);
                    if (target != null && target.isBlank()) target = null;
                    // 私聊对话（/ai）：回复只能私聊给他本人，不填 player 也一样——私聊内容不会被发到公屏或别人那里
                    if (ctx.trigger.isPrivate()) {
                        if (target == null) target = ctx.trigger.requester();
                        else if (!target.equalsIgnoreCase(ctx.trigger.requester())) {
                            throw new IllegalStateException("这是 " + ctx.trigger.requester() + " 的私聊，只能回复他本人");
                        }
                    }
                    // 玩家互动偏好（代码层强制）：说过"别说了"的人不私聊、不点名；不想被提的话题不提。只有管理员提问不受限（见 speechBlocked）
                    String why = speechBlocked(prefs.get(), ctx, msg, target);
                    if (why != null) throw new IllegalStateException("未发送：" + why);
                    if (!perm.allowChat) throw new IllegalStateException("服主已禁止代理发送聊天");
                    if (target != null && !perm.allowPrivateChat) throw new IllegalStateException("服主已禁止代理私聊，只能全服公告");
                    if (!ctx.tryChat()) return "未发送：本次运行可发送的聊天条数已用完（上限 " + ctx.rules.quota.perRun.maxChat + "）";
                    String err = actions.sendChat(msg, target, ctx);
                    if (err != null) throw new IllegalStateException(err);
                    log.info("agent", "tool.chat", (target == null ? "公告：" : "私聊 " + target + "：") + msg, ctx.cid,
                            JsonlLog.data("target", target, "message", msg));
                    activity.add("ai_chat", c.botName, (target == null ? "" : "→" + target + " ") + msg);
                    ctx.action((target == null ? "公告 " : "私聊 " + target + " ") + msg);
                    return "已发送";
                });
    }
}
