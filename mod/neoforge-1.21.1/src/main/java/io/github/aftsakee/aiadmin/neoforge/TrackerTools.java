package io.github.aftsakee.aiadmin.neoforge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import io.github.aftsakee.aiadmin.agent.Activity;
import io.github.aftsakee.aiadmin.agent.PlayerTracker;
import io.github.aftsakee.aiadmin.agent.Tools;
import io.github.aftsakee.aiadmin.util.Text;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

/**
 * 玩家行为工具：get_players_overview（所有人一眼看全）、get_player_activity（某人最近 N 分钟的时间线和统计）、
 * get_offline_player（不在线的玩家：读存档里的位置、背包、末影箱）。
 * 历史数据来自 {@link PlayerTracker}；"此刻"的数据（正看着的方块/实体、周围实体、离家距离、身边的玩家）在服务端主线程现查。
 * 名字、位置、方块/实体显示名的写法用 {@link McFmt}（和 TrackerHooks 记下的事件一致）。
 */
final class TrackerTools {
    private TrackerTools() {}

    static void register(Tools t, MinecraftServer server, McBridge bridge) {
        t.add(Tools.schema("get_players_overview",
                        "所有在线玩家此刻在哪、在做什么：位置、群系、行为判断（挖矿/建造/战斗/种田/赶路/挂机…）、手持物品、血量饥饿、离重生点多远、身边的玩家，"
                                + "以及最近下线的玩家。想知道'大家在干嘛'先用这个。").build(),
                (a, c) -> bridge.onServer(() -> overview(server)).toString());

        t.add(Tools.schema("get_player_activity",
                        "某名玩家最近 N 分钟的详细行为：移动轨迹（时间+坐标+群系）、挖/放了哪些方块以及在哪一带（areas）、杀了什么、被什么打、"
                                + "合成/拾取/丢出了什么、交互过哪些方块和实体（含模组方块的名字、id 和所属模组，带坐标）、"
                                + "从哪个箱子/机器拿走或放入了什么、村民交易、挖到的贵重矿石坐标、危险物品（岩浆桶/打火石/TNT…）、"
                                + "死亡和换维度、聊天和指令、背包多了什么少了什么；在线时还有正看着的方块/实体和周围实体。"
                                + "下线一小时内的玩家也能查。events 里同一件事短时间重复会合并（times = 次数）。"
                                + "坐标、背包、矿石位置是玩家隐私，只用于管理判断，不要在公屏或私聊里透露给其他玩家。")
                        .str("name", "玩家名", true)
                        .integer("minutes", "看最近几分钟，默认 15，最多 60", false)
                        .integer("path_points", "轨迹最多几个点，默认 12，最多 60（采样每 10 秒一次）", false)
                        .integer("event_limit", "最多返回几条事件（取最新的），默认 40，最多 200", false)
                        .str("event_kinds", "只看这些事件，逗号分隔：use,interact_entity,container,trade,ore,danger,drop,kill,death,"
                                + "dimension,join,leave（空 = 全部）", false)
                        .build(),
                (a, c) -> {
                    Set<String> kinds = new HashSet<>();
                    for (String k : Tools.optStr(a, "event_kinds", "").split(",")) if (!k.isBlank()) kinds.add(k.trim());
                    return bridge.onServer(() -> activity(server, Tools.optStr(a, "name", ""), Tools.optInt(a, "minutes", 15, 1, 60),
                            Tools.optInt(a, "path_points", 12, 1, 60), Tools.optInt(a, "event_limit", 40, 1, 200), kinds)).toString();
                });

        t.add(Tools.schema("get_offline_player",
                        "不在线的玩家：从存档读最后的位置、维度、血量饥饿、经验、游戏模式、重生点、背包（含附魔耐久）、盔甲、副手、末影箱、"
                                + "游玩时长等统计，以及存档最后保存时间（≈下线时间）。在线玩家请用 get_player。"
                                + "坐标和背包是玩家隐私，只用于管理判断，不要在公屏或私聊里透露给其他玩家。")
                        .str("name", "玩家名（需要在服务器上出现过）", true).build(),
                (a, c) -> offline(server, bridge, Tools.optStr(a, "name", "")).toString());
    }

    static JsonObject overview(MinecraftServer server) {
        PlayerTracker tr = TrackerHooks.tracker();
        long now = System.currentTimeMillis();
        JsonObject o = new JsonObject();
        JsonArray online = new JsonArray();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            String n = McFmt.name(p);
            JsonObject x = new JsonObject();
            x.addProperty("name", n);
            x.addProperty("where", McFmt.where(p));
            p.level().getBiome(p.blockPosition()).unwrapKey().ifPresent(k -> x.addProperty("biome", k.location().getPath()));
            x.addProperty("doing", tr == null ? "（追踪未启用）" : tr.guess(n, now));
            if (!p.getMainHandItem().isEmpty()) x.addProperty("holding", McFmt.shortId(BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem())));
            x.addProperty("health", Math.round(p.getHealth()) + "/" + Math.round(p.getMaxHealth()));
            x.addProperty("food", p.getFoodData().getFoodLevel());
            BlockPos home = p.getRespawnPosition();
            if (home != null && p.getRespawnDimension().equals(p.level().dimension())) {
                x.addProperty("distance_from_respawn", Math.round(Math.sqrt(home.distSqr(p.blockPosition()))));
            }
            JsonArray near = new JsonArray();
            for (ServerPlayer q : server.getPlayerList().getPlayers()) {
                if (q == p || q.level() != p.level()) continue;
                double d = q.distanceTo(p);
                if (d <= 64) near.add(McFmt.name(q) + "（" + Math.round(d) + " 格）");
            }
            if (!near.isEmpty()) x.add("nearby_players", near);
            if (p.getVehicle() != null) x.addProperty("riding", McFmt.shortId(BuiltInRegistries.ENTITY_TYPE.getKey(p.getVehicle().getType())));
            if (tr != null) {
                JsonArray ev = tr.recentEvents(n, 3, System.currentTimeMillis(), 5 * 60_000L);
                if (!ev.isEmpty()) x.add("recent_events", ev); // 最近 5 分钟的最后 3 件事：交互了什么、开了哪个箱子…
            }
            online.add(x);
        }
        o.add("online", online);
        if (tr == null) o.addProperty("note", "行为追踪只在专用服务器上启用");
        return o;
    }

    static JsonObject activity(MinecraftServer server, String name, int minutes, int pathPoints, int eventLimit, Set<String> kinds) {
        PlayerTracker tr = TrackerHooks.tracker();
        if (tr == null) {
            JsonObject e = new JsonObject();
            e.addProperty("error", "行为追踪没有启用");
            return e;
        }
        ServerPlayer p = server.getPlayerList().getPlayerByName(name);
        if (p != null) TrackerHooks.sample(tr, p, System.currentTimeMillis()); // 先补一个最新采样，"现在"就是现在
        String key = p != null ? McFmt.name(p) : name;
        long now = System.currentTimeMillis();
        JsonObject o = tr.activity(key, minutes, now, pathPoints, eventLimit, kinds);
        JsonArray said = said(key, now - minutes * 60_000L);
        if (!said.isEmpty()) o.add("chat_and_commands", said);
        if (p == null) return o;
        JsonObject live = new JsonObject();
        HitResult hit = p.pick(6.0, 0f, false);
        if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult bh) {
            live.addProperty("looking_at", McFmt.label(p.level().getBlockState(bh.getBlockPos())) + " @ " + McFmt.where(p.level(), bh.getBlockPos()));
        }
        // 看着的实体（6 格内，视线方向上最近的那个）
        var eye = p.getEyePosition();
        var reach = eye.add(p.getViewVector(1f).scale(6.0));
        EntityHitResult eh = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(p, eye, reach,
                p.getBoundingBox().expandTowards(p.getViewVector(1f).scale(6.0)).inflate(1.0), en -> !en.isSpectator() && en.isPickable(), 36.0);
        if (eh != null) live.addProperty("looking_at_entity", McFmt.label(eh.getEntity()) + " @ " + McFmt.where(p.level(), eh.getEntity().blockPosition()));
        Map<String, Integer> around = new HashMap<>();
        for (Entity e : p.level().getEntities(p, p.getBoundingBox().inflate(16))) {
            if (e instanceof ItemEntity) continue;
            around.merge(McFmt.shortId(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType())), 1, Integer::sum);
        }
        JsonObject a = new JsonObject();
        around.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(12)
                .forEach(en -> a.addProperty(en.getKey(), en.getValue()));
        if (!a.isEmpty()) live.add("entities_within_16", a);
        Map<String, Integer> inv = TrackerHooks.inventoryCounts(p);
        JsonObject invTop = new JsonObject();
        inv.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(30)
                .forEach(en -> invTop.addProperty(en.getKey().replace("minecraft:", ""), en.getValue()));
        live.add("inventory_now", invTop);
        live.addProperty("light_level", p.level().getMaxLocalRawBrightness(p.blockPosition()));
        if (p.containerMenu != p.inventoryMenu) live.addProperty("open_screen", p.containerMenu.getClass().getSimpleName());
        o.add("live", live);
        return o;
    }

    /** 这名玩家自 since 以来的聊天、指令、tpa 记录（来自服务器动态记录，最近 600 条里找）。 */
    private static JsonArray said(String name, long since) {
        JsonArray a = new JsonArray();
        Activity act = ServerHooks.activity();
        if (act == null) return a;
        for (Activity.Entry e : act.recent(600, Set.of("chat", "command", "tpa"))) {
            if (e.time() < since || !name.equals(e.who())) continue;
            JsonObject x = new JsonObject();
            x.addProperty("time", Text.time(e.time()).substring(11));
            x.addProperty("kind", e.kind());
            x.addProperty("text", e.text());
            a.add(x);
        }
        while (a.size() > 40) a.remove(0);
        return a;
    }

    // ———————————————————— 离线玩家 ————————————————————

    /**
     * 读 world/playerdata/&lt;uuid&gt;.dat（服务器自己的存档，玩家下线时写入）。只读不改；
     * 文件是当前版本服务端写的，直接按当前格式解析物品，个别物品解析失败时跳过并计数。
     * <p>线程：扫存档目录、读 .dat 和统计 JSON 都是文件 IO，在调用线程（代理/远程线程）上做；只有判断在线、查名字缓存、
     * 把物品 NBT 转成显示信息（要用注册表）回主线程。以前整个在主线程里做，存储慢时会卡 tick、撞上 10 秒超时。
     */
    static JsonObject offline(MinecraftServer server, McBridge bridge, String name) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        Path dir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
        List<UUID> ids = new ArrayList<>();
        if (!name.isBlank() && Files.isDirectory(dir)) {
            try (var files = Files.newDirectoryStream(dir, "*.dat")) {
                for (Path f : files) {
                    String fn = f.getFileName().toString();
                    try {
                        ids.add(UUID.fromString(fn.substring(0, fn.length() - 4)));
                    } catch (IllegalArgumentException ex) {
                        // 不是 <uuid>.dat 的文件跳过
                    }
                }
            } catch (Exception ignored) {
                // 目录读不了：按找不到处理
            }
        }
        Object[] found = bridge.onServer(() -> findProfile(server, ids, name));
        if ((Boolean) found[1]) o.addProperty("note", "这名玩家现在在线，实时完整信息请用 get_player");
        Optional<GameProfile> prof = Optional.ofNullable((GameProfile) found[0]);
        if (prof.isEmpty()) {
            o.addProperty("error", "没找到这名玩家（存档里没有这个名字的玩家，可能从没进过服务器或名字写错）");
            return o;
        }
        UUID id = prof.get().getId();
        o.addProperty("name", prof.get().getName());
        o.addProperty("uuid", id.toString());
        Path dat = dir.resolve(id + ".dat");
        CompoundTag tag;
        try {
            if (!Files.isRegularFile(dat)) {
                o.addProperty("error", "这名玩家还没有存档文件（刚进服、还没保存过）");
                return o;
            }
            o.addProperty("last_saved", Text.time(Files.getLastModifiedTime(dat).toMillis()));
            tag = NbtIo.readCompressed(dat, NbtAccounter.unlimitedHeap());
        } catch (Exception ex) {
            o.addProperty("error", "读存档失败：" + ex.getMessage());
            return o;
        }
        ListTag pos = tag.getList("Pos", Tag.TAG_DOUBLE);
        String dim = tag.getString("Dimension");
        if (pos.size() == 3) {
            o.addProperty("where", PlayerTracker.where(new PlayerTracker.Sample(0, dim.isEmpty() ? "minecraft:overworld" : dim,
                    pos.getDouble(0), pos.getDouble(1), pos.getDouble(2), null, 0, 0, 0, null, null, null, false)));
        }
        o.addProperty("health", Math.round(tag.getFloat("Health")));
        o.addProperty("food", tag.getInt("foodLevel"));
        o.addProperty("xp_level", tag.getInt("XpLevel"));
        o.addProperty("gamemode", GameType.byId(tag.getInt("playerGameType")).getName());
        if (tag.contains("SpawnX")) {
            o.addProperty("respawn", tag.getString("SpawnDimension") + " " + tag.getInt("SpawnX") + " " + tag.getInt("SpawnY") + " " + tag.getInt("SpawnZ"));
        }
        ListTag invTags = tag.getList("Inventory", Tag.TAG_COMPOUND);
        ListTag enderTags = tag.getList("EnderItems", Tag.TAG_COMPOUND);
        o.add("items", bridge.onServer(() -> items(server, invTags, enderTags))); // 物品解析要用注册表：回主线程
        JsonObject items = o.remove("items").getAsJsonObject();
        for (String k : items.keySet()) o.add(k, items.get(k));
        Path stats = server.getWorldPath(LevelResource.PLAYER_STATS_DIR).resolve(id + ".json");
        stats(o, stats);
        PlayerTracker tr = TrackerHooks.tracker();
        if (tr != null && tr.known(prof.get().getName())) {
            o.addProperty("recent", tr.guess(prof.get().getName(), System.currentTimeMillis()) + "（一小时内的行为可用 get_player_activity 查）");
        }
        return o;
    }

    /** 主线程：背包、盔甲、副手、末影箱的 NBT 转成显示信息。 */
    private static JsonObject items(MinecraftServer server, ListTag invTags, ListTag enderTags) {
        JsonObject o = new JsonObject();
        JsonArray inv = new JsonArray(), armor = new JsonArray(), off = new JsonArray();
        int bad = 0;
        for (Tag t : invTags) {
            CompoundTag c = (CompoundTag) t;
            JsonObject it = parseItem(server, c);
            if (it == null) {
                bad++;
                continue;
            }
            int slot = c.getByte("Slot");
            it.addProperty("slot", slot);
            if (slot >= 100 && slot <= 103) armor.add(it);
            else if (slot == -106) off.add(it);
            else inv.add(it);
        }
        JsonArray ender = new JsonArray();
        for (Tag t : enderTags) {
            CompoundTag c = (CompoundTag) t;
            JsonObject it = parseItem(server, c);
            if (it == null) {
                bad++;
                continue;
            }
            it.addProperty("slot", c.getByte("Slot"));
            ender.add(it);
        }
        o.add("inventory", inv);
        o.add("armor", armor);
        o.add("offhand", off);
        o.add("ender_chest", ender);
        if (bad > 0) o.addProperty("unreadable_items", bad);
        return o;
    }

    /** 调用线程：读统计 JSON（文件 IO）。 */
    private static void stats(JsonObject o, Path stats) {
        try {
            if (Files.isRegularFile(stats)) {
                JsonObject custom = JsonParser.parseString(Files.readString(stats, StandardCharsets.UTF_8)).getAsJsonObject()
                        .getAsJsonObject("stats").getAsJsonObject("minecraft:custom");
                JsonObject st = new JsonObject();
                st.addProperty("play_time_minutes", num(custom, "minecraft:play_time") / 20 / 60);
                st.addProperty("deaths", num(custom, "minecraft:deaths"));
                st.addProperty("mob_kills", num(custom, "minecraft:mob_kills"));
                st.addProperty("player_kills", num(custom, "minecraft:player_kills"));
                o.add("stats", st);
            }
        } catch (Exception ignored) {
            // 统计只是附加信息，格式不对就不给
        }
    }

    /**
     * 按名字找玩家：先看在线玩家，再遍历 playerdata 里的 UUID、用服务器的名字缓存（usercache，内存里）对名字，不分大小写。
     * 故意不用 GameProfileCache.get(名字)：缓存里没有时它会同步请求 Mojang 接口，会卡住服务端主线程，还会对外泄露查询。
     */
    /** 主线程：返回 {GameProfile 或 null, 是否在线}。ids 是调用线程扫出来的 playerdata 文件名里的 UUID。 */
    private static Object[] findProfile(MinecraftServer server, List<UUID> ids, String name) {
        ServerPlayer on = server.getPlayerList().getPlayerByName(name);
        if (on != null) return new Object[] {on.getGameProfile(), true};
        var cache = server.getProfileCache();
        if (cache != null && !name.isBlank()) {
            for (UUID id : ids) {
                Optional<GameProfile> g = cache.get(id);
                if (g.isPresent() && g.get().getName().equalsIgnoreCase(name)) return new Object[] {g.get(), false};
            }
        }
        return new Object[] {null, false};
    }

    private static JsonObject parseItem(MinecraftServer server, CompoundTag c) {
        try {
            ItemStack st = ItemStack.parseOptional(server.registryAccess(), c);
            return st.isEmpty() ? null : McTools.item(st, 0);
        } catch (Exception ex) {
            return null;
        }
    }

    private static long num(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e == null ? 0 : e.getAsLong();
    }
}
