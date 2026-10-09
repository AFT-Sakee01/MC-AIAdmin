package io.github.aftsakee.aiadmin.agent;

import com.google.gson.JsonObject;
import io.github.aftsakee.aiadmin.config.BotConfig;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * 玩家互动偏好的工具（本地 AI 和远程/云端都能用）：get_player_prefs、set_player_pref。
 * 边界：玩家聊天唤醒时只能改正在说话的玩家自己的偏好（防止 A 让 AI 把 B 设成"欢迎调侃"）；远程接口关了动作时不能改；
 * 只能往"更克制"的方向替别人改的规则不做——玩家自己用 /ai 指令改，或服主/远程改，都算本人意愿或管理决定。
 */
public final class SocialTools {
    private SocialTools() {}

    public static void register(Tools t, Supplier<PlayerPrefs> prefs, Supplier<BotConfig> cfg) {
        register(t, prefs, cfg, () -> null);
    }

    public static void register(Tools t, Supplier<PlayerPrefs> prefs, Supplier<BotConfig> cfg, Supplier<CommandNotes> notes) {
        t.add(Tools.schema("note_command",
                        "用过一条不熟悉的指令（尤其模组指令）后，把学到的记下来：参数单位（秒/分钟/tick）、效果、持续多久、怎么撤销或恢复、谁能用、注意事项。"
                                + "下次本地和云端的 AI 用 get_commands 都能查到。verified=true 只有在最近 30 分钟内你真的成功执行过这条指令时才会生效；"
                                + "玩家在聊天里说的用法不算验证。记错了用 remove_index 删掉。")
                        .str("command", "指令名，如 pig（不带 /）", true)
                        .str("text", "学到的内容（300 字以内），例如：/pig <玩家> <分钟>，单位分钟，最多 30；/pig <玩家> 0 立即恢复；只有 OP 能用", false)
                        .bool("verified", "是否是你亲自执行确认过的", false)
                        .integer("remove_index", "删除第几条笔记（get_commands 里的 index）", false).build(),
                (a, ctx) -> noteCommand(notes.get(), cfg.get(), a, ctx));
        t.add(Tools.schema("get_player_prefs",
                        "读一名玩家的互动偏好：风格（normal 正常 / roast_ok 欢迎调侃 / serious 只想认真答疑）、不想被提的话题、是否说过\"别说了\"（安静到何时）、"
                                + "备注、是否连续受挫（最近死了好几次）。调侃、整蛊、主动搭话之前先看一眼。")
                        .str("player", "玩家名", true).build(),
                (a, ctx) -> {
                    PlayerPrefs p = prefs.get();
                    if (p == null) return "{\"error\":\"机器人还没启动\"}";
                    return p.json(Tools.optStr(a, "player", "").trim()).toString();
                });
        t.add(Tools.schema("set_player_pref",
                        "记下玩家对互动方式的要求，之后本地和云端都会遵守（发言前代码会强制检查）。玩家说\"别调侃我\"\"我只想问问题\"→ style=serious；"
                                + "\"随便吐槽我\"→ style=roast_ok；\"别提我的 XX\"→ avoid_add；\"别说了\"→ quiet_hours。"
                                + "玩家聊天唤醒你时只能改正在说话的玩家自己的偏好。")
                        .str("player", "玩家名", true)
                        .strEnum("style", "互动风格（可选）", false, PlayerPrefs.NORMAL, PlayerPrefs.ROAST_OK, PlayerPrefs.SERIOUS)
                        .str("avoid_add", "加一个不想被提的话题（可选，20 字以内）", false)
                        .str("avoid_remove", "删一个话题（可选）", false)
                        .integer("quiet_hours", "安静几小时（可选，0 = 解除安静）", false)
                        .str("note", "备注（可选，100 字以内；传空字符串清除）", false).build(),
                (a, ctx) -> set(prefs.get(), cfg.get(), a, ctx));
    }

    static String noteCommand(CommandNotes cn, BotConfig c, JsonObject a, RunContext ctx) {
        JsonObject o = new JsonObject();
        if (cn == null) {
            o.addProperty("error", "机器人还没启动");
            return o.toString();
        }
        if (ctx.trigger.kind() == Trigger.Kind.REMOTE && !c.remote.allowActions) {
            o.addProperty("error", "远程接口没有开放动作（remote.allow_actions=false）");
            return o.toString();
        }
        String cmd = Tools.optStr(a, "command", "");
        if (a.has("remove_index") && !a.get("remove_index").isJsonNull()) {
            o.addProperty("result", cn.remove(cmd, Tools.optInt(a, "remove_index", 0, 0, 100)));
            return o.toString();
        }
        Trigger tr = ctx.trigger;
        String by = tr.kind() == Trigger.Kind.CHAT ? "AI（玩家聊天：" + String.join("、", tr.speakers()) + "）"
                : "remote".equals(ctx.provider) || "cloud".equals(ctx.provider) ? "云端 AI（" + tr.label() + "）" : "本地 AI（" + tr.label() + "）";
        boolean want = a.has("verified") && !a.get("verified").isJsonNull() && a.get("verified").getAsBoolean();
        String r = cn.add(cmd, Tools.optStr(a, "text", ""), want, by);
        ctx.action(r);
        o.addProperty("result", r);
        o.add("notes", cn.json(cmd));
        return o.toString();
    }

    static String set(PlayerPrefs p, BotConfig c, JsonObject a, RunContext ctx) {
        if (p == null) return "{\"error\":\"机器人还没启动\"}";
        String player = Tools.optStr(a, "player", "").trim();
        JsonObject o = new JsonObject();
        if (player.isEmpty() || !player.matches("[A-Za-z0-9_]{1,16}")) {
            o.addProperty("error", "需要正确的玩家名");
            return o.toString();
        }
        Trigger tr = ctx.trigger;
        if (tr.kind() == Trigger.Kind.REMOTE && !c.remote.allowActions) {
            o.addProperty("error", "远程接口没有开放动作（remote.allow_actions=false）");
            return o.toString();
        }
        // 只认这一轮真正找机器人（@ 了机器人或 /ai 私聊）的人：同一批里还会带上没叫机器人的公屏发言（custom/all 模式、
        // 安静中玩家的话），用"所有发言者"判定的话，A 说"@AI 解除 B 的安静"只要 B 同批说过话就能改 B 的偏好
        if (tr.kind() == Trigger.Kind.CHAT && !tr.direct().contains(player.toLowerCase(Locale.ROOT))) {
            o.addProperty("error", "玩家聊天唤醒时只能改 @ 了机器人（或私聊它）的玩家自己的偏好");
            return o.toString();
        }
        // 事件、巡检唤醒时没有"本人意愿"：只允许往克制的方向改（认真、安静、加回避话题），不允许替人打开"欢迎调侃"或解除安静
        boolean selfOrOwner = tr.kind() == Trigger.Kind.CHAT || tr.kind() == Trigger.Kind.ADMIN || tr.kind() == Trigger.Kind.REMOTE;
        String by = tr.kind() == Trigger.Kind.CHAT ? player + "（聊天里说的，AI 记下）" : "AI（" + tr.label() + "）";
        StringBuilder done = new StringBuilder();
        String style = Tools.optStr(a, "style", null);
        if (style != null && !style.isBlank()) {
            String st = style.trim().toLowerCase(Locale.ROOT);
            if (!selfOrOwner && st.equals(PlayerPrefs.ROAST_OK)) {
                o.addProperty("error", "事件或巡检里不能替玩家设成\"欢迎调侃\"，要他本人说");
                return o.toString();
            }
            done.append(p.setStyle(player, st, by)).append('；');
        }
        String add = Tools.optStr(a, "avoid_add", null);
        if (add != null && !add.isBlank()) done.append(p.addAvoid(player, add, by)).append('；');
        String rm = Tools.optStr(a, "avoid_remove", null);
        if (rm != null && !rm.isBlank()) {
            if (!selfOrOwner) {
                o.addProperty("error", "事件或巡检里不能替玩家删掉回避话题");
                return o.toString();
            }
            done.append(p.removeAvoid(player, rm, by)).append('；');
        }
        if (a.has("quiet_hours") && !a.get("quiet_hours").isJsonNull()) {
            int h = Tools.optInt(a, "quiet_hours", 0, 0, 720);
            if (h == 0 && !selfOrOwner) {
                o.addProperty("error", "事件或巡检里不能替玩家解除安静");
                return o.toString();
            }
            // 本人或服主可以设成任意时长（含缩短）；事件/巡检里只能延长，不能借"设安静"变相提前解除
            done.append(selfOrOwner ? p.setQuiet(player, h, by) : p.extendQuiet(player, h, by)).append('；');
        }
        if (a.has("note") && !a.get("note").isJsonNull()) done.append(p.setNote(player, Tools.optStr(a, "note", ""), by)).append('；');
        if (done.length() == 0) {
            o.addProperty("error", "没有要改的内容（style / avoid_add / avoid_remove / quiet_hours / note 至少给一个）");
            return o.toString();
        }
        ctx.action("记下 " + player + " 的互动偏好：" + done);
        o.addProperty("ok", done.toString());
        o.add("prefs", p.json(player));
        return o.toString();
    }
}
