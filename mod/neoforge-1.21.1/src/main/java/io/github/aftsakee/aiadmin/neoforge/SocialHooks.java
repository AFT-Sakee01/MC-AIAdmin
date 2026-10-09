package io.github.aftsakee.aiadmin.neoforge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import io.github.aftsakee.aiadmin.agent.AgentService;
import io.github.aftsakee.aiadmin.agent.PlayerPrefs;
import io.github.aftsakee.aiadmin.agent.Tools;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * 玩家和机器人互动的入口（MC 侧）：
 * <ul>
 *   <li>/ai &lt;内容&gt;：私下找机器人（回复只私聊给他，见 AgentService.onPrivateChat）；</li>
 *   <li>/ai quiet [小时] | resume | style normal|roast_ok|serious | avoid &lt;话题&gt; | unavoid &lt;话题&gt; | prefs：自己的互动偏好；</li>
 *   <li>get_commands 工具：从服务器的指令树列出指令、谁能用、参数格式（单位和含义要看服务器指南）。</li>
 * </ul>
 * 所有玩家都能用（权限 0）；偏好只能改自己的。指令注册的调用顺序见 {@link HookBus}。
 */
final class SocialHooks {
    private SocialHooks() {}

    static void onRegisterCommands(RegisterCommandsEvent e) {
        register(e.getDispatcher());
    }

    static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("ai").requires(src -> ServerHooks.service() != null)
                .executes(SocialHooks::help)
                .then(Commands.literal("prefs").executes(SocialHooks::show))
                .then(Commands.literal("resume").executes(c -> withPrefs(c, (p, n) -> p.setQuiet(n, 0, n + "（/ai resume）"))))
                .then(Commands.literal("quiet")
                        .executes(c -> withPrefs(c, (p, n) -> p.setQuiet(n, ServerHooks.config().social.quietDefaultHours, n + "（/ai quiet）")))
                        .then(Commands.argument("hours", IntegerArgumentType.integer(1, 720))
                                .executes(c -> withPrefs(c, (p, n) -> p.setQuiet(n, IntegerArgumentType.getInteger(c, "hours"), n + "（/ai quiet）")))))
                .then(Commands.literal("style").then(Commands.argument("style", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(PlayerPrefs.STYLES, b))
                        .executes(c -> withPrefs(c, (p, n) -> p.setStyle(n, StringArgumentType.getString(c, "style").toLowerCase(Locale.ROOT), n + "（/ai style）")))))
                .then(Commands.literal("avoid").then(Commands.argument("topic", StringArgumentType.greedyString())
                        .executes(c -> withPrefs(c, (p, n) -> p.addAvoid(n, StringArgumentType.getString(c, "topic"), n + "（/ai avoid）")))))
                .then(Commands.literal("unavoid").then(Commands.argument("topic", StringArgumentType.greedyString())
                        .executes(c -> withPrefs(c, (p, n) -> p.removeAvoid(n, StringArgumentType.getString(c, "topic"), n + "（/ai unavoid）")))))
                // Brigadier 只要第一个词命中子命令就走子命令（"/ai avoid 苦力怕怎么打"会被记成回避话题）：/ai ask <内容> 什么都能问
                .then(Commands.literal("ask").then(Commands.argument("message", StringArgumentType.greedyString()).executes(SocialHooks::privateChat)))
                .then(Commands.argument("message", StringArgumentType.greedyString()).executes(SocialHooks::privateChat)));
    }

    private interface PrefOp {
        String apply(PlayerPrefs p, String name);
    }

    private static ServerPlayer player(CommandContext<CommandSourceStack> c) {
        return Cmds.player(c);
    }

    private static int withPrefs(CommandContext<CommandSourceStack> c, PrefOp op) {
        ServerPlayer p = player(c);
        AgentService svc = ServerHooks.service();
        if (p == null || svc == null) return 0;
        String msg = op.apply(svc.prefs(), McFmt.name(p));
        c.getSource().sendSuccess(() -> Component.literal(msg).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int show(CommandContext<CommandSourceStack> c) {
        ServerPlayer p = player(c);
        AgentService svc = ServerHooks.service();
        if (p == null || svc == null) return 0;
        String n = McFmt.name(p);
        PlayerPrefs.Pref pr = svc.prefs().get(n);
        String line = svc.prefs().line(n);
        String msg = line == null ? "你没有设置任何互动偏好（风格：正常）。" : "你的互动偏好 —— " + line.substring(line.indexOf('：') + 1);
        if (pr != null && !pr.updatedBy.isEmpty()) msg += "\n最后修改：" + pr.updatedBy;
        String m = msg;
        c.getSource().sendSuccess(() -> Component.literal(m).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int help(CommandContext<CommandSourceStack> c) {
        String bot = ServerHooks.config().botName;
        c.getSource().sendSuccess(() -> Component.literal("""
                /ai <内容>  私下问%s（回复只有你看得到）；内容以 quiet/style/avoid 等开头时用 /ai ask <内容>
                /ai quiet [小时]  让它别主动找你、别在公屏提你（直接 @它 或 /ai 照样回答）；/ai resume 恢复
                /ai style normal|roast_ok|serious  正常 / 欢迎调侃 / 只想认真答疑
                /ai avoid <话题>  不想被提的话题；/ai unavoid <话题> 删掉
                /ai prefs  看自己的设置""".formatted(bot)).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int privateChat(CommandContext<CommandSourceStack> c) {
        ServerPlayer p = player(c);
        AgentService svc = ServerHooks.service();
        if (p == null || svc == null) return 0;
        MinecraftServer server = c.getSource().getServer();
        boolean op = Cmds.isOp(server, p);
        String why = svc.onPrivateChat(McFmt.name(p), StringArgumentType.getString(c, "message"), op);
        if (why != null) {
            c.getSource().sendFailure(Component.literal(why));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.literal("（已私下发给 " + ServerHooks.config().botName + "）").withStyle(ChatFormatting.DARK_GRAY), false);
        return 1;
    }

    // ———————————————————— get_commands ————————————————————

    static void registerTools(Tools t, MinecraftServer server, McBridge bridge, Supplier<String> guide) {
        t.add(Tools.schema("get_commands",
                        "服务器上的指令：不填 name 列出全部（可用 query 筛选）和谁能用；填 name 给出这条指令的参数格式，"
                                + "原版指令附带现成资料（单位、怎么恢复、风险），模组指令附带服务器指南里服主写的说明和之前用过后记下的笔记。"
                                + "都没有的模组指令：先用最小、无害的参数试，确认后用 note_command 记下。")
                        .str("name", "指令名（不带 /），例如 pig、tpa", false)
                        .str("query", "列表时按名字筛选（包含即可）", false).build(),
                (a, ctx) -> {
                    String g = guide.get(); // 读服务器指南文件在调用线程上做，不占主线程
                    return bridge.onServer(() -> commands(server, Tools.optStr(a, "name", "").trim(), Tools.optStr(a, "query", "").trim(), g)).toString();
                });
    }

    static JsonObject commands(MinecraftServer server, String name, String query, String guide) {
        CommandDispatcher<CommandSourceStack> d = server.getCommands().getDispatcher();
        CommandSourceStack console = server.createCommandSourceStack();
        JsonObject o = new JsonObject();
        if (!name.isEmpty()) {
            String n = name.startsWith("/") ? name.substring(1) : name;
            CommandNode<CommandSourceStack> node = d.getRoot().getChild(n.toLowerCase(Locale.ROOT));
            if (node == null) {
                o.addProperty("error", "没有这条指令：" + n);
                JsonArray like = new JsonArray();
                for (CommandNode<CommandSourceStack> c : d.getRoot().getChildren()) if (c.getName().contains(n.toLowerCase(Locale.ROOT)) && like.size() < 20) like.add(c.getName());
                o.add("similar", like);
                return o;
            }
            o.addProperty("command", "/" + node.getName());
            o.addProperty("who_can_use", who(node, console));
            JsonArray usage = new JsonArray();
            String[] all = d.getAllUsage(node, console, true);
            for (int i = 0; i < all.length && i < 60; i++) usage.add("/" + node.getName() + (all[i].isEmpty() ? "" : " " + all[i]));
            if (all.length > 60) usage.add("…（共 " + all.length + " 种写法，只列前 60 种）");
            if (all.length == 0) usage.add("/" + node.getName());
            o.add("usage", usage);
            JsonArray g = new JsonArray();
            if (guide != null) {
                for (String l : guide.split("\n")) {
                    if (l.contains("/" + node.getName()) && g.size() < 20) g.add(l.trim());
                }
            }
            o.add("server_guide_lines", g);
            JsonObject ref = io.github.aftsakee.aiadmin.agent.CommandNotes.vanilla(node.getName());
            if (ref != null) {
                o.add("vanilla_reference", ref);
                o.addProperty("units", io.github.aftsakee.aiadmin.agent.CommandNotes.vanilla().get("units").getAsString());
            }
            AgentService svc = ServerHooks.service();
            JsonArray learned = svc == null ? new JsonArray() : svc.commandNotes().json(node.getName());
            o.add("learned_notes", learned);
            if (ref == null && g.isEmpty() && learned.isEmpty()) {
                o.addProperty("note", "这是模组指令，还没有资料。只在有人要求时再用：先按上面的格式用最小、无害的参数试一次（例如时长先给最小值、目标先选要求的人自己），"
                        + "看指令输出和效果，确认单位和怎么恢复后用 note_command 记下（verified=true）。拿不准会不会造成损失时先问服主。");
            } else if (ref == null && learned.isEmpty()) {
                o.addProperty("note", "以服务器指南为准；用过后可以用 note_command 补充指南里没写的细节。");
            }
            return o;
        }
        List<CommandNode<CommandSourceStack>> list = new ArrayList<>(d.getRoot().getChildren());
        list.sort((x, y) -> x.getName().compareTo(y.getName()));
        JsonArray arr = new JsonArray();
        int total = 0;
        AgentService svc = ServerHooks.service();
        for (CommandNode<CommandSourceStack> c : list) {
            if (!query.isEmpty() && !c.getName().contains(query.toLowerCase(Locale.ROOT))) continue;
            total++;
            if (arr.size() >= 400) continue;
            String tag = io.github.aftsakee.aiadmin.agent.CommandNotes.vanilla(c.getName()) != null ? "，原版资料"
                    : svc != null && svc.commandNotes().has(c.getName()) ? "，有笔记" : "";
            arr.add("/" + c.getName() + "（" + who(c, console) + tag + "）");
        }
        o.addProperty("count", total);
        o.add("commands", arr);
        o.addProperty("hint", "查某条的参数格式和资料：get_commands name=<指令名>");
        JsonObject v = io.github.aftsakee.aiadmin.agent.CommandNotes.vanilla();
        if (v.has("units")) o.addProperty("units", v.get("units").getAsString());
        return o;
    }

    /** 谁能用：用不同权限等级的来源试 requires。 */
    private static String who(CommandNode<CommandSourceStack> node, CommandSourceStack console) {
        if (node.canUse(console.withPermission(0))) return "所有玩家";
        if (node.canUse(console.withPermission(2))) return "OP";
        if (node.canUse(console.withPermission(4))) return "高权限 OP / 后台";
        return "当前不可用";
    }
}
