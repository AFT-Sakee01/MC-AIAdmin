package io.github.aftsakee.aiadmin.neoforge;

import com.google.gson.JsonObject;
import io.github.aftsakee.aiadmin.agent.RunContext;
import io.github.aftsakee.aiadmin.agent.Tools;
import io.github.aftsakee.aiadmin.agent.Trigger;
import io.github.aftsakee.aiadmin.gamedata.CraftPlanner;
import io.github.aftsakee.aiadmin.gamedata.GameData;
import io.github.aftsakee.aiadmin.gamedata.GameDataQueries;
import io.github.aftsakee.aiadmin.goals.GoalEngine;
import io.github.aftsakee.aiadmin.goals.GoalStore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 游戏资料与目标工具：item_search、recipe_for、recipe_uses、tag_members、item_sources、loot_table、craft_plan（整条合成链路）、
 * goal_status、set_player_goal。
 * <p>数据来自 {@link GameDataIndex} 的不可变快照：先切到主线程取快照（必要时重建），查询本身在调用线程做，不占主线程。
 * 除 set_player_goal（只改目标清单，不影响世界）外全部只读。
 */
final class GameDataTools {
    private GameDataTools() {}

    static void register(Tools t, MinecraftServer server, McBridge bridge) {
        t.add(Tools.schema("item_search",
                        "按名字或 ID 片段找物品（含模组物品）。返回 ID、名字（英文 / 模组自带的中文）、所属模组，以及有几条配方能做出它、被几条配方用到、有几个掉落来源。"
                                + "原版物品服务端只有英文名，玩家说中文名时请换成英文名或 ID（如 铁锭 → iron_ingot）。")
                        .str("query", "名字或 ID 片段，如 iron ingot、andesite、安山合金；带命名空间只查那个模组，如 create:andesite", true)
                        .integer("limit", "最多返回几个，默认 10，最多 30", false).build(),
                (a, c) -> GameDataQueries.itemSearch(data(server, bridge), Tools.optStr(a, "query", ""), Tools.optInt(a, "limit", 10, 1, 30)).toString());

        t.add(Tools.schema("recipe_for",
                        "某个物品怎么做：列出所有能做出它的配方（工作台、熔炉、高炉、烟熏炉、营火、切石机、锻造台、酿造台，以及模组机器如 create:mixing），"
                                + "含原料（#开头是标签，任选其一）、产物和概率、有序合成的摆法 shape + key、模组机器的额外要求 extra（加热、时间等）。"
                                + "带药水的产物（药水、药箭等）会标出（药水 …）。要一整条从原料到成品的路线请用 craft_plan。")
                        .str("item", "物品 ID 或准确名字，如 minecraft:iron_pickaxe、iron_pickaxe", true)
                        .str("potion", "只看带这种药水的（药水、喷溅/滞留药水、药箭同一物品 ID 下有多种药水时），如 minecraft:poison、long_swiftness；不填 = 全部", false)
                        .integer("limit", "最多列几条，默认 8，最多 30", false).build(),
                (a, c) -> withItem(server, bridge, a, (d, item) -> GameDataQueries.recipeFor(d, item, Tools.optInt(a, "limit", 8, 1, 30))));

        t.add(Tools.schema("recipe_uses",
                        "某个物品能拿来做什么：列出所有用到它的配方（包括通过标签用到的）。")
                        .str("item", "物品 ID 或准确名字", true)
                        .integer("limit", "最多列几条，默认 10，最多 40", false).build(),
                (a, c) -> withItem(server, bridge, a, (d, item) -> GameDataQueries.recipeUses(d, item, Tools.optInt(a, "limit", 10, 1, 40))));

        t.add(Tools.schema("tag_members",
                        "物品标签里有哪些物品（配方里 #minecraft:planks 这种原料可以用其中任意一种）；或者传 item 查某物品属于哪些标签。")
                        .str("tag", "标签名，如 #minecraft:planks、c:ingots/iron（与 item 二选一）", false)
                        .str("item", "物品 ID：改为列出它所属的标签", false)
                        .integer("limit", "最多列几个物品，默认 40，最多 200", false).build(),
                (a, c) -> {
                    GameData d = data(server, bridge);
                    String item = Tools.optStr(a, "item", "");
                    if (!item.isBlank()) {
                        String id = d.resolveItem(item);
                        JsonObject o = new JsonObject();
                        if (id == null) return notFound(d, item).toString();
                        o.addProperty("item", id);
                        o.add("tags", GameDataQueries.tagsOf(d, id));
                        return o.toString();
                    }
                    return GameDataQueries.tagMembers(d, Tools.optStr(a, "tag", ""), Tools.optInt(a, "limit", 40, 1, 200)).toString();
                });

        t.add(Tools.schema("item_sources",
                        "某个物品从哪来：哪些方块挖了会掉、哪些生物会掉（含概率、是否需要玩家击杀、抢夺/时运加成）、哪些宝箱/钓鱼/考古/村民礼物能出，"
                                + "以及有几条配方能做出它。只含数据包战利品表；全局战利品修改器、代码写死的掉落和村民交易不在内。")
                        .str("item", "物品 ID 或准确名字", true)
                        .str("potion", "只看带这种药水的（药水、喷溅/滞留药水、药箭同一物品 ID 下有多种药水时），如 minecraft:poison、long_swiftness；不填 = 全部", false)
                        .integer("limit", "最多列几条，默认 12，最多 40", false).build(),
                (a, c) -> withItem(server, bridge, a, (d, item) -> GameDataQueries.itemSources(d, item, Tools.optInt(a, "limit", 12, 1, 40))));

        t.add(Tools.schema("loot_table",
                        "看一张战利品表的全部内容：某个生物/方块掉什么，某种宝箱里有什么（每个池抽几次、各物品概率、数量、条件）。")
                        .str("table", "表 ID，或简写：zombie（=entities/zombie）、diamond_ore（=blocks/diamond_ore）、chests/simple_dungeon、gameplay/fishing", true)
                        .integer("limit", "最多列几条，默认 30，最多 100", false).build(),
                (a, c) -> GameDataQueries.lootTable(data(server, bridge), Tools.optStr(a, "table", ""), Tools.optInt(a, "limit", 30, 1, 100)).toString());

        t.add(Tools.schema("craft_plan",
                        "整条合成链路：从原料到成品，按顺序列出每一步在哪做、做几次、每次用什么；对照某位在线玩家的背包（可选）扣掉已有的东西，"
                                + "列出还缺的原料以及去哪弄（挖什么、打什么、开什么箱子）、需要的液体、做完剩下的东西。"
                                + "每个物品只选一条配方（优先工作台/熔炉、优先用背包里已有的、避开方块↔锭互转）；可用 prefer_recipes 指定做法。")
                        .str("item", "目标物品 ID 或准确名字", true)
                        .str("potion", "要哪种药水（药水、喷溅/滞留药水、药箭同一物品 ID 下有多种药水时），如 minecraft:poison、long_swiftness；不填 = 任选一种", false)
                        .integer("count", "要做几个，默认 1，最多 10000", false)
                        .str("player", "按这位在线玩家的背包计算（不填 = 从零开始）", false)
                        .str("prefer_recipes", "指定某些物品用哪条配方，逗号分隔的 物品=配方ID，如 minecraft:iron_ingot=minecraft:iron_ingot_from_blasting_raw_iron", false)
                        .str("avoid_types", "不用这些配方类型，逗号分隔，如 minecraft:blasting,create:crushing", false)
                        .bool("vanilla_only", "只用原版工作站（工作台/熔炉/高炉/烟熏炉/营火/切石机/锻造台），默认 false", false).build(),
                (a, c) -> plan(server, bridge, a));

        t.add(Tools.schema("goal_status",
                        "某位玩家的目标：服主目标表（goals.json）里的阶段进度（哪些完成了、现在该做哪个）、支线、个人目标和进度、定时提示状态，"
                                + "在线时还给出下一步建议（含合成链路摘要）。玩家问\"我下一步干嘛\"时先用这个。")
                        .str("player", "玩家名", true).build(),
                (a, c) -> goalStatus(server, bridge, Tools.optStr(a, "player", "")));

        t.add(Tools.schema("set_player_goal",
                        "给玩家记个人目标（之后背包里凑够数量会自动祝贺，定时提示也会优先提醒它）。玩家说\"我想做 XX\"\"帮我记一下要 XX 个 YY\"时用。"
                                + "玩家聊天唤醒你时只能改正在说话的玩家自己的目标。")
                        .str("player", "玩家名", true)
                        .strEnum("action", "add = 添加/改数量，remove = 删除（item 填物品 ID 或序号），clear = 清空", true, "add", "remove", "clear")
                        .str("item", "物品 ID 或准确名字（add/remove 时需要）", false)
                        .integer("count", "数量，默认 1", false)
                        .str("note", "备注（可选），如 \"挖钻石用\"", false).build(),
                GameDataTools::setGoal);
    }

    @FunctionalInterface
    interface ItemQuery {
        JsonObject run(GameData d, String item);
    }

    private static GameData data(MinecraftServer server, McBridge bridge) {
        return bridge.onServer(() -> GameDataIndex.get(server));
    }

    /** 解析 item（和可选的 potion：带药水时换成变体键，见 GameData#withPotion）后查询。 */
    private static String withItem(MinecraftServer server, McBridge bridge, JsonObject a, ItemQuery q) {
        GameData d = data(server, bridge);
        String raw = Tools.optStr(a, "item", "");
        String id = d.resolveItem(raw);
        if (id == null) return notFound(d, raw).toString();
        return q.run(d, d.withPotion(id, Tools.optStr(a, "potion", ""))).toString();
    }

    private static JsonObject notFound(GameData d, String raw) {
        JsonObject o = GameDataQueries.itemSearch(d, raw, 8);
        o.addProperty("error", "不确定是哪个物品：「" + raw + "」。请从下面的候选里选一个 ID 再查（原版物品要用英文名或 ID）。");
        return o;
    }

    private static String plan(MinecraftServer server, McBridge bridge, JsonObject a) {
        GameData d = data(server, bridge);
        String raw = Tools.optStr(a, "item", "");
        String id = d.resolveItem(raw);
        if (id == null) return notFound(d, raw).toString();
        id = d.withPotion(id, Tools.optStr(a, "potion", ""));
        int count = Tools.optInt(a, "count", 1, 1, 10000);
        String player = Tools.optStr(a, "player", "").trim();
        Map<String, Integer> inv = Map.of();
        String whose = null;
        if (!player.isEmpty()) {
            Map<String, Integer> got = bridge.onServer(() -> {
                ServerPlayer p = server.getPlayerList().getPlayerByName(player);
                return p == null ? null : GoalHooks.inventory(p);
            });
            if (got == null) {
                JsonObject o = new JsonObject();
                o.addProperty("error", "玩家 " + player + " 不在线；可以不填 player 从零计算");
                return o.toString();
            }
            inv = got;
            whose = player;
        }
        Map<String, String> force = new HashMap<>();
        for (String kv : Tools.optStr(a, "prefer_recipes", "").split(",")) {
            int i = kv.indexOf('=');
            if (i > 0) force.put(GameData.norm(kv.substring(0, i)), kv.substring(i + 1).trim().toLowerCase(Locale.ROOT));
        }
        Set<String> avoid = new HashSet<>();
        for (String s : Tools.optStr(a, "avoid_types", "").split(",")) if (!s.isBlank()) avoid.add(GameData.norm(s));
        CraftPlanner.Options opt = new CraftPlanner.Options(12, avoid, force, !Tools.optBool(a, "vanilla_only", false));
        return GameDataQueries.plan(d, CraftPlanner.plan(d, id, count, inv, opt), whose).toString();
    }

    private static String goalStatus(MinecraftServer server, McBridge bridge, String name) {
        GoalEngine e = GoalHooks.engine();
        if (e == null) return "{\"error\":\"目标功能没有启动（只在专用服务器上启用）\"}";
        if (name.isBlank()) return "{\"error\":\"需要 player\"}";
        // status 要读玩家背包和进度（主线程），整个在主线程算；planner 很快，不会卡服
        return bridge.onServer(() -> {
            GameDataIndex.get(server);
            ServerPlayer p = server.getPlayerList().getPlayerByName(name);
            return e.status(p == null ? name : p.getGameProfile().getName(), p == null ? null : GoalHooks.facts(server, p)).toString();
        });
    }

    private static String setGoal(JsonObject a, RunContext ctx) {
        GoalEngine e = GoalHooks.engine();
        if (e == null) return "{\"error\":\"目标功能没有启动\"}";
        String player = Tools.optStr(a, "player", "").trim();
        String action = Tools.optStr(a, "action", "add");
        JsonObject o = new JsonObject();
        if (player.isEmpty()) {
            o.addProperty("error", "需要 player");
            return o.toString();
        }
        // 边界：远程接口关了动作（allow_actions=false）时也不允许改；玩家聊天唤醒时只能改说话者自己的目标（防止 A 让 AI 改 B 的目标）
        if (ctx.trigger.kind() == Trigger.Kind.REMOTE && !ctx.cfg.remote.allowActions) {
            o.addProperty("error", "远程接口没有开放动作（remote.allow_actions=false）");
            return o.toString();
        }
        // 只认这一轮 @ 了机器人或 /ai 私聊它的人（同批里只是在公屏说话的人不算，见 SocialTools.set 的同一条约束）
        if (ctx.trigger.kind() == Trigger.Kind.CHAT && !ctx.trigger.direct().contains(player.toLowerCase(java.util.Locale.ROOT))) {
            o.addProperty("error", "玩家聊天唤醒时只能修改 @ 了机器人（或私聊它）的玩家自己的目标");
            return o.toString();
        }
        GameData d = GameDataIndex.cached();
        String setBy = ctx.trigger.kind() == Trigger.Kind.CHAT ? player : "AI（" + ctx.trigger.label() + "）";
        switch (action) {
            case "clear" -> o.addProperty("cleared", e.store().clearPersonal(player));
            case "remove" -> {
                String item = Tools.optStr(a, "item", "").trim();
                String key = item.matches("\\d+") || d == null ? item : d.resolveItem(item);
                GoalStore.Personal x = key == null ? null : e.store().removePersonal(player, key);
                if (x == null) o.addProperty("error", "没有这个目标（可以用 goal_status 看序号）");
                else o.addProperty("removed", x.item + " ×" + x.count);
            }
            default -> {
                String raw = Tools.optStr(a, "item", "");
                String id = d == null ? GameData.norm(raw) : d.resolveItem(raw);
                if (id == null) return notFound(d, raw).toString();
                int count = Tools.optInt(a, "count", 1, 1, 100000);
                var t = e.table().get();
                int max = t == null ? 10 : t.settings().personalMax();
                String err = e.store().addPersonal(player, id, count, Tools.optStr(a, "note", ""), setBy, max, System.currentTimeMillis());
                if (err != null) o.addProperty("error", err);
                else {
                    o.addProperty("added", id + "（" + (d == null ? id : d.label(id)) + "）×" + count);
                    o.addProperty("tip", "可以告诉玩家：输入 /goal 查看目标，/goal plan " + id + " 看合成路线");
                }
            }
        }
        if (!o.has("error")) ctx.action("目标 " + player + " " + action + " " + Tools.optStr(a, "item", ""));
        return o.toString();
    }

    /** 聊天触发里每条是 "玩家名：内容"（可能带【已授权】标记）。 */
    static String speaker(String ev) {
        return Trigger.speaker(ev);
    }
}
