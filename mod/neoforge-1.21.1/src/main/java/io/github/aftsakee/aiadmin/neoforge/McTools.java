package io.github.aftsakee.aiadmin.neoforge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.aftsakee.aiadmin.agent.Activity;
import io.github.aftsakee.aiadmin.agent.Tools;
import io.github.aftsakee.aiadmin.config.Rules;
import io.github.aftsakee.aiadmin.util.FileGuard;
import io.github.aftsakee.aiadmin.util.Text;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.UserBanListEntry;
import net.minecraft.stats.Stats;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.border.WorldBorder;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;

/**
 * 代理的"观察"工具：只读，不改变服务器。读游戏状态的都通过 {@link McBridge#onServer} 切到主线程。
 * 玩家名、维度 id、方块坐标的写法用 {@link McFmt}（和事件层一致）。
 */
final class McTools {
    private McTools() {}

    static void register(Tools t, MinecraftServer server, McBridge bridge, Activity activity, LogTap tap, Path gameDir,
                         Supplier<Rules> rules) {
        t.add(Tools.schema("get_server_status", "服务器概况：版本、在线人数、TPS/MSPT、内存、CPU、运行时长、各维度的区块/实体/玩家数、时间天气。").build(),
                (a, c) -> bridge.onServer(() -> overview(server, false)).toString());

        t.add(Tools.schema("list_players", "所有在线玩家的简要信息：维度、坐标、血量、饥饿、游戏模式、延迟、OP 等级、经验等级。").build(),
                (a, c) -> bridge.onServer(() -> {
                    JsonArray arr = new JsonArray();
                    for (ServerPlayer p : server.getPlayerList().getPlayers()) arr.add(playerBrief(server, p));
                    return arr;
                }).toString());

        t.add(Tools.schema("get_player", "一名在线玩家的完整信息：背包（含附魔、耐久、潜影盒内容）、盔甲、副手、末影箱、状态效果、重生点、坐骑、统计数据等。")
                        .str("name", "玩家名", true).build(),
                (a, c) -> bridge.onServer(() -> {
                    ServerPlayer p = server.getPlayerList().getPlayerByName(Tools.optStr(a, "name", ""));
                    if (p == null) throw new IllegalArgumentException("玩家不在线：" + Tools.optStr(a, "name", ""));
                    return playerFull(server, p, showIps(rules, c));
                }).toString());

        t.add(Tools.schema("get_recent_activity", "最近的服务器动态：聊天、进出服、死亡、成就、玩家执行的指令、机器人自己的动作。")
                        .integer("limit", "条数，默认 50，最多 300", false)
                        .str("kinds", "只看这些类型，逗号分隔：chat,join,leave,death,advancement,command,whisper（玩家之间私聊，只记谁对谁、不记内容）,ai_private（玩家用 /ai 私聊机器人）,ai_chat,ai_command（空 = 全部）", false)
                        .build(),
                (a, c) -> {
                    Set<String> kinds = new HashSet<>();
                    for (String k : Tools.optStr(a, "kinds", "").split(",")) if (!k.isBlank()) kinds.add(k.trim());
                    return activity.toJson(Tools.optInt(a, "limit", 50, 1, 300), kinds).toString();
                });

        t.add(Tools.schema("get_server_log", "服务端日志（内存里最近 1500 行，INFO 及以上）。更早的日志用 read_file 读 logs/ 目录。")
                        .integer("lines", "行数，默认 100，最多 500", false)
                        .strEnum("min_level", "最低级别，默认 INFO", false, "INFO", "WARN", "ERROR")
                        .str("contains", "只看包含这段文字的行（不分大小写）", false)
                        .build(),
                (a, c) -> {
                    int min = switch (Tools.optStr(a, "min_level", "INFO").toUpperCase(Locale.ROOT)) {
                        case "WARN" -> 1;
                        case "ERROR" -> 2;
                        default -> 0;
                    };
                    String contains = Tools.optStr(a, "contains", "").toLowerCase(Locale.ROOT);
                    StringBuilder sb = new StringBuilder();
                    for (LogTap.Line l : tap.ring.recent(Tools.optInt(a, "lines", 100, 1, 500),
                            l -> levelRank(l.level()) >= min && (contains.isEmpty() || l.message().toLowerCase(Locale.ROOT).contains(contains)))) {
                        sb.append(Text.time(l.time()).substring(11)).append(" [").append(l.level()).append("] [")
                                .append(shortLogger(l.logger())).append("] ").append(l.message()).append('\n');
                    }
                    // 原版登录日志带"名字[/IP:端口]"：服主没允许看 IP、或远程调用且 hide_player_ips 开着时隐藏
                    return sb.length() == 0 ? "（没有符合条件的日志）" : showIps(rules, c) ? sb.toString() : Text.redactIps(sb.toString());
                });

        t.add(Tools.schema("get_entities", "实体统计（找卡服原因用）：按类型计数、实体最多的区块；也可以列出某玩家附近的实体。")
                        .str("dimension", "维度 id，如 minecraft:overworld（空 = 全部维度）", false)
                        .str("type", "只统计这种实体，如 minecraft:item", false)
                        .str("near_player", "列出这名玩家附近的实体", false)
                        .integer("radius", "near_player 的半径，默认 32，最大 128", false)
                        .integer("top_chunks", "列出实体最多的前几个区块，默认 10", false)
                        .build(),
                (a, c) -> bridge.onServer(() -> entities(server, a)).toString());

        t.add(Tools.schema("get_world_info", "世界设置：难度、极限模式、种子、出生点、世界边界、游戏规则（全部）、强制加载区块、数据包。")
                        .str("dimension", "维度 id（默认主世界）", false).build(),
                (a, c) -> bridge.onServer(() -> world(server, Tools.optStr(a, "dimension", "minecraft:overworld"))).toString());

        t.add(Tools.schema("get_access_lists", "OP 名单、白名单（及是否启用）、封禁名单（含原因、期限）；服主允许时还有 IP 封禁。").build(),
                (a, c) -> bridge.onServer(() -> access(server, showIps(rules, c))).toString());

        t.add(Tools.schema("list_mods", "服务端加载的所有模组（id、名称、版本）。").build(), (a, c) -> {
            JsonArray arr = new JsonArray();
            for (IModInfo m : ModList.get().getMods()) {
                JsonObject o = new JsonObject();
                o.addProperty("id", m.getModId());
                o.addProperty("name", m.getDisplayName());
                o.addProperty("version", m.getVersion().toString());
                arr.add(o);
            }
            return arr.toString();
        });

        t.add(Tools.schema("list_files", "列出服务器目录下某个文件夹的内容（相对服务器根目录，如 . 、config、logs、world）。")
                        .str("path", "文件夹路径，默认 .", false).build(),
                (a, c) -> listFiles(gameDir, Tools.optStr(a, "path", "."), rules.get().permissions));

        t.add(Tools.schema("read_file", "读服务器目录下的文本文件（配置、日志等，支持 .gz 日志）。密码类配置值会被隐藏。")
                        .str("path", "文件路径，如 server.properties、logs/latest.log、config/xxx.toml", true)
                        .integer("start_line", "从第几行开始（1 起），默认 1", false)
                        .integer("max_lines", "最多读几行，默认 200，最多 2000", false)
                        .integer("tail_lines", "改为读最后 N 行（看日志用；填了就忽略 start_line）", false)
                        .build(),
                (a, c) -> {
                    String r = readFile(gameDir, a, rules.get().permissions);
                    return showIps(rules, c) ? r : Text.redactIps(r); // logs/latest.log、banned-ips.json 里都有玩家 IP
                });
    }

    // ———————————————————— 概况 ————————————————————

    static JsonObject overview(MinecraftServer server, boolean withPlayers) {
        JsonObject o = new JsonObject();
        o.addProperty("version", server.getServerVersion());
        o.addProperty("motd", server.getMotd());
        o.addProperty("players_online", server.getPlayerCount());
        o.addProperty("players_max", server.getMaxPlayers());
        double mspt = server.getAverageTickTimeNanos() / 1_000_000.0;
        float target = server.tickRateManager().tickrate();
        o.addProperty("mspt_avg", round(mspt));
        o.addProperty("tps", round(Math.min(target, 1000.0 / Math.max(mspt, 1e-3))));
        o.addProperty("tps_target", target);
        Runtime rt = Runtime.getRuntime();
        o.addProperty("memory_used_mb", (rt.totalMemory() - rt.freeMemory()) >> 20);
        o.addProperty("memory_max_mb", rt.maxMemory() >> 20);
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            o.addProperty("process_cpu_percent", round(os.getProcessCpuLoad() * 100));
            o.addProperty("system_cpu_percent", round(os.getCpuLoad() * 100));
        }
        o.addProperty("uptime_minutes", ManagementFactory.getRuntimeMXBean().getUptime() / 60000);
        o.addProperty("difficulty", server.getWorldData().getDifficulty().getKey());
        JsonArray dims = new JsonArray();
        for (ServerLevel l : server.getAllLevels()) {
            JsonObject d = new JsonObject();
            d.addProperty("id", McFmt.dimId(l));
            d.addProperty("loaded_chunks", l.getChunkSource().getLoadedChunksCount());
            int ents = 0;
            for (Entity ignored : l.getAllEntities()) ents++;
            d.addProperty("entities", ents);
            d.addProperty("players", l.players().size());
            d.addProperty("day_time", l.getDayTime() % 24000);
            d.addProperty("day", l.getDayTime() / 24000);
            d.addProperty("raining", l.isRaining());
            d.addProperty("thundering", l.isThundering());
            dims.add(d);
        }
        o.add("dimensions", dims);
        if (withPlayers) {
            JsonArray ps = new JsonArray();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) ps.add(playerBrief(server, p));
            o.add("players", ps);
        }
        return o;
    }

    static JsonObject playerBrief(MinecraftServer server, ServerPlayer p) {
        JsonObject o = new JsonObject();
        o.addProperty("name", McFmt.name(p));
        o.addProperty("dimension", McFmt.dimId(p));
        o.addProperty("pos", McFmt.xyz(p.blockPosition())); // 方块坐标（向下取整）
        o.addProperty("health", round(p.getHealth()) + "/" + round(p.getMaxHealth()));
        o.addProperty("food", p.getFoodData().getFoodLevel());
        o.addProperty("gamemode", p.gameMode.getGameModeForPlayer().getName());
        o.addProperty("ping_ms", p.connection.latency());
        o.addProperty("op_level", server.getProfilePermissions(p.getGameProfile()));
        o.addProperty("xp_level", p.experienceLevel);
        io.github.aftsakee.aiadmin.agent.PlayerTracker tr = TrackerHooks.tracker();
        if (tr != null) o.addProperty("doing", tr.guess(McFmt.name(p), System.currentTimeMillis())); // 最近 5 分钟在做什么
        return o;
    }

    /** 是否给出玩家 IP：rules.json 允许，且不是经远程接口（云端 AI）调用——remote.hide_player_ips 开着时远程一律不给。 */
    static boolean showIps(java.util.function.Supplier<Rules> rules, io.github.aftsakee.aiadmin.agent.RunContext c) {
        if (!rules.get().permissions.exposePlayerIps) return false;
        boolean remote = c != null && ("remote".equals(c.provider) || "cloud".equals(c.provider));
        return !(remote && ServerHooks.config().remote.hidePlayerIps);
    }

    static JsonObject playerFull(MinecraftServer server, ServerPlayer p, boolean ip) {
        JsonObject o = playerBrief(server, p);
        o.addProperty("uuid", p.getUUID().toString());
        o.addProperty("exact_pos", round(p.getX()) + " " + round(p.getY()) + " " + round(p.getZ()));
        o.addProperty("saturation", round(p.getFoodData().getSaturationLevel()));
        o.addProperty("air", p.getAirSupply());
        o.addProperty("on_fire", p.isOnFire());
        o.addProperty("flying", p.getAbilities().flying);
        o.addProperty("sleeping", p.isSleeping());
        o.addProperty("xp_total", p.totalExperience);
        if (p.getVehicle() != null) o.addProperty("vehicle", BuiltInRegistries.ENTITY_TYPE.getKey(p.getVehicle().getType()).toString());
        BlockPos sp = p.getRespawnPosition();
        o.addProperty("respawn", sp == null ? null : p.getRespawnDimension().location() + " " + McFmt.xyz(sp));
        if (ip) o.addProperty("ip", p.getIpAddress());
        JsonArray eff = new JsonArray();
        for (MobEffectInstance e : p.getActiveEffects()) {
            JsonObject x = new JsonObject();
            x.addProperty("effect", e.getEffect().getRegisteredName());
            x.addProperty("level", e.getAmplifier() + 1);
            x.addProperty("seconds", e.isInfiniteDuration() ? -1 : e.getDuration() / 20);
            eff.add(x);
        }
        o.add("effects", eff);
        var inv = p.getInventory();
        o.add("inventory", items(inv.items, 0));
        o.add("armor", items(inv.armor, 0));
        o.add("offhand", items(inv.offhand, 0));
        o.addProperty("selected_hotbar_slot", inv.selected);
        o.add("ender_chest", container(p.getEnderChestInventory()));
        JsonObject st = new JsonObject();
        var stats = p.getStats();
        st.addProperty("play_time_minutes", stats.getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)) / 20 / 60);
        st.addProperty("deaths", stats.getValue(Stats.CUSTOM.get(Stats.DEATHS)));
        st.addProperty("mob_kills", stats.getValue(Stats.CUSTOM.get(Stats.MOB_KILLS)));
        st.addProperty("player_kills", stats.getValue(Stats.CUSTOM.get(Stats.PLAYER_KILLS)));
        st.addProperty("minutes_since_death", stats.getValue(Stats.CUSTOM.get(Stats.TIME_SINCE_DEATH)) / 20 / 60);
        o.add("stats", st);
        return o;
    }

    private static JsonArray items(List<ItemStack> list, int depth) {
        JsonArray a = new JsonArray();
        for (int i = 0; i < list.size(); i++) {
            ItemStack s = list.get(i);
            if (s.isEmpty()) continue;
            JsonObject o = item(s, depth);
            o.addProperty("slot", i);
            a.add(o);
        }
        return a;
    }

    private static JsonArray container(Container c) {
        List<ItemStack> l = new ArrayList<>();
        for (int i = 0; i < c.getContainerSize(); i++) l.add(c.getItem(i));
        return items(l, 0);
    }

    static JsonObject item(ItemStack s, int depth) {
        JsonObject o = new JsonObject();
        o.addProperty("id", BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
        o.addProperty("count", s.getCount());
        if (s.has(DataComponents.CUSTOM_NAME)) o.addProperty("name", s.getHoverName().getString());
        if (s.isDamageableItem()) o.addProperty("durability", (s.getMaxDamage() - s.getDamageValue()) + "/" + s.getMaxDamage());
        String ench = enchants(s.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY));
        if (!ench.isEmpty()) o.addProperty("enchantments", ench);
        String stored = enchants(s.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY));
        if (!stored.isEmpty()) o.addProperty("stored_enchantments", stored);
        ItemContainerContents cc = s.get(DataComponents.CONTAINER);
        if (cc != null && depth < 1) {
            List<ItemStack> inner = new ArrayList<>();
            cc.nonEmptyItems().forEach(inner::add);
            if (!inner.isEmpty()) o.add("contents", items(inner, depth + 1));
        }
        return o;
    }

    private static String enchants(ItemEnchantments e) {
        List<String> out = new ArrayList<>();
        for (Object2IntMap.Entry<Holder<Enchantment>> x : e.entrySet()) {
            out.add(x.getKey().unwrapKey().map(k -> k.location().getPath()).orElse("?") + " " + x.getIntValue());
        }
        return String.join(", ", out);
    }

    // ———————————————————— 实体 ————————————————————

    static JsonObject entities(MinecraftServer server, JsonObject a) {
        String dim = Tools.optStr(a, "dimension", "");
        String type = Tools.optStr(a, "type", "");
        String near = Tools.optStr(a, "near_player", "");
        int radius = Tools.optInt(a, "radius", 32, 1, 128);
        int top = Tools.optInt(a, "top_chunks", 10, 0, 50);
        JsonObject out = new JsonObject();
        if (!near.isBlank()) {
            ServerPlayer p = server.getPlayerList().getPlayerByName(near);
            if (p == null) throw new IllegalArgumentException("玩家不在线：" + near);
            JsonArray list = new JsonArray();
            Map<String, Integer> counts = new HashMap<>();
            for (Entity e : p.serverLevel().getEntities(p, p.getBoundingBox().inflate(radius))) {
                String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
                if (!type.isBlank() && !id.equals(type)) continue;
                counts.merge(id, 1, Integer::sum);
                if (list.size() < 60) {
                    JsonObject o = new JsonObject();
                    o.addProperty("type", id);
                    if (e.hasCustomName()) o.addProperty("name", e.getName().getString());
                    o.addProperty("pos", McFmt.xyz(e.blockPosition())); // 向下取整，和死亡记录、方块坐标一致
                    o.addProperty("distance", round(e.distanceTo(p)));
                    if (e instanceof ItemEntity ie) o.addProperty("item", BuiltInRegistries.ITEM.getKey(ie.getItem().getItem()) + " x" + ie.getItem().getCount());
                    list.add(o);
                }
            }
            out.add("counts", sorted(counts, 50));
            out.add("entities", list);
            return out;
        }
        JsonArray dims = new JsonArray();
        for (ServerLevel l : server.getAllLevels()) {
            String id = McFmt.dimId(l);
            if (!dim.isBlank() && !id.equals(dim)) continue;
            Map<String, Integer> counts = new HashMap<>();
            Map<Long, Integer> chunks = new HashMap<>();
            Map<Long, Map<String, Integer>> chunkTypes = new HashMap<>();
            int total = 0;
            for (Entity e : l.getAllEntities()) {
                String t = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
                if (!type.isBlank() && !t.equals(type)) continue;
                total++;
                counts.merge(t, 1, Integer::sum);
                long cp = e.chunkPosition().toLong();
                chunks.merge(cp, 1, Integer::sum);
                chunkTypes.computeIfAbsent(cp, k -> new HashMap<>()).merge(t, 1, Integer::sum);
            }
            JsonObject d = new JsonObject();
            d.addProperty("dimension", id);
            d.addProperty("total", total);
            d.add("by_type", sorted(counts, 40));
            JsonArray tops = new JsonArray();
            chunks.entrySet().stream().sorted(Map.Entry.<Long, Integer>comparingByValue().reversed()).limit(top).forEach(en -> {
                JsonObject c = new JsonObject();
                int cx = (int) (long) en.getKey(), cz = (int) (en.getKey() >> 32);
                c.addProperty("chunk", cx + "," + cz);
                c.addProperty("block_center", (cx * 16 + 8) + " ~ " + (cz * 16 + 8));
                c.addProperty("count", en.getValue());
                c.add("types", sorted(chunkTypes.get(en.getKey()), 5));
                tops.add(c);
            });
            d.add("top_chunks", tops);
            dims.add(d);
        }
        out.add("dimensions", dims);
        return out;
    }

    private static JsonObject sorted(Map<String, Integer> m, int limit) {
        JsonObject o = new JsonObject();
        m.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(limit)
                .forEach(e -> o.addProperty(e.getKey(), e.getValue()));
        return o;
    }

    // ———————————————————— 世界、名单 ————————————————————

    static JsonObject world(MinecraftServer server, String dim) {
        ServerLevel level = null;
        for (ServerLevel l : server.getAllLevels()) if (McFmt.dimId(l).equals(dim)) level = l;
        if (level == null) throw new IllegalArgumentException("没有这个维度：" + dim);
        JsonObject o = new JsonObject();
        o.addProperty("level_name", server.getWorldData().getLevelName());
        o.addProperty("dimension", dim);
        o.addProperty("difficulty", server.getWorldData().getDifficulty().getKey());
        o.addProperty("hardcore", server.isHardcore());
        o.addProperty("seed", level.getSeed());
        BlockPos sp = level.getSharedSpawnPos();
        o.addProperty("spawn", McFmt.xyz(sp));
        WorldBorder b = level.getWorldBorder();
        o.addProperty("world_border", "中心 " + round(b.getCenterX()) + "," + round(b.getCenterZ()) + " 边长 " + round(b.getSize()));
        o.addProperty("forced_chunks", level.getForcedChunks().size());
        o.addProperty("game_time", level.getGameTime());
        o.addProperty("day_time", level.getDayTime());
        JsonObject rules = new JsonObject();
        GameRules gr = server.getGameRules();
        GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override
            public <T extends GameRules.Value<T>> void visit(GameRules.Key<T> key, GameRules.Type<T> type) {
                rules.addProperty(key.getId(), gr.getRule(key).serialize());
            }
        });
        o.add("gamerules", rules);
        JsonArray packs = new JsonArray();
        server.getPackRepository().getSelectedIds().forEach(packs::add);
        o.add("datapacks", packs);
        return o;
    }

    static JsonObject access(MinecraftServer server, boolean ip) {
        var pl = server.getPlayerList();
        JsonObject o = new JsonObject();
        o.add("ops", strings(pl.getOpNames()));
        o.addProperty("whitelist_enabled", pl.isUsingWhitelist());
        o.add("whitelist", strings(pl.getWhiteListNames()));
        JsonArray bans = new JsonArray();
        for (UserBanListEntry e : pl.getBans().getEntries()) {
            JsonObject b = new JsonObject();
            b.addProperty("name", e.getDisplayName().getString());
            b.addProperty("reason", e.getReason());
            b.addProperty("source", e.getSource());
            b.addProperty("expires", e.getExpires() == null ? "永久" : e.getExpires().toString());
            bans.add(b);
        }
        o.add("bans", bans);
        if (ip) o.add("ip_bans", strings(pl.getIpBans().getUserList()));
        return o;
    }

    private static JsonArray strings(String[] a) {
        JsonArray arr = new JsonArray();
        Arrays.stream(a).sorted().forEach(arr::add);
        return arr;
    }

    // ———————————————————— 文件 ————————————————————

    static String listFiles(Path root, String rel, Rules.Permissions perm) throws IOException {
        Path dir = FileGuard.checked(root, rel, perm.fileDeny);
        String r = FileGuard.rel(root, dir);
        if (!Files.isDirectory(dir)) throw new IllegalArgumentException("不是文件夹：" + r);
        List<Path> ps = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) ps.add(p);
        }
        ps.sort(Comparator.comparing((Path p) -> !Files.isDirectory(p)).thenComparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));
        JsonArray arr = new JsonArray();
        int hidden = 0;
        for (Path p : ps) {
            try {
                FileGuard.checked(root, FileGuard.rel(root, p), perm.fileDeny); // 同样的规则（含链接指向）决定是否列出
            } catch (IllegalArgumentException e) {
                hidden++;
                continue;
            }
            if (arr.size() >= 300) break;
            JsonObject o = new JsonObject();
            o.addProperty("name", p.getFileName().toString() + (Files.isDirectory(p) ? "/" : ""));
            if (!Files.isDirectory(p)) o.addProperty("bytes", Files.size(p));
            o.addProperty("modified", Text.time(Files.getLastModifiedTime(p).toMillis()));
            arr.add(o);
        }
        JsonObject out = new JsonObject();
        out.addProperty("path", r);
        out.add("entries", arr);
        if (hidden > 0) out.addProperty("hidden_by_rules", hidden);
        if (ps.size() - hidden > 300) out.addProperty("truncated", true);
        return out.toString();
    }

    /** 读文本：单行最多 4000 字，最多扫描 64 MiB（解压后），防止超长单行或高压缩比 .gz 撑爆服务端内存。 */
    @SuppressWarnings("unchecked")
    static String readFile(Path root, JsonObject a, Rules.Permissions perm) throws IOException {
        Path f = FileGuard.checked(root, Tools.optStr(a, "path", ""), perm.fileDeny);
        String r = FileGuard.rel(root, f);
        if (!Files.isRegularFile(f)) throw new IllegalArgumentException("不是文件：" + r);
        boolean gz = r.toLowerCase(Locale.ROOT).endsWith(".gz");
        try (InputStream raw = Files.newInputStream(f)) {
            InputStream in = gz ? new GZIPInputStream(raw) : raw;
            byte[] head = in.readNBytes(8192);
            if (FileGuard.looksBinary(head, head.length)) throw new IllegalArgumentException("这是二进制文件，不能按文本读：" + r);
            InputStream all = new java.io.SequenceInputStream(new java.io.ByteArrayInputStream(head), in);
            int start = Tools.optInt(a, "start_line", 1, 1, Integer.MAX_VALUE);
            int max = Tools.optInt(a, "max_lines", 200, 1, 2000);
            int tail = Tools.optInt(a, "tail_lines", 0, 0, 2000);
            int budget = perm.fileReadMaxBytes;
            List<String> lines = new ArrayList<>();
            ArrayDeque<String> tailBuf = new ArrayDeque<>();
            int[] n = {0};
            boolean[] more = {false};
            boolean cut = FileGuard.readLines(all, 4000, 64L << 20, line -> {
                n[0]++;
                if (tail > 0) {
                    tailBuf.addLast(line);
                    if (tailBuf.size() > tail) tailBuf.pollFirst();
                    return true;
                }
                if (n[0] >= start) {
                    if (lines.size() >= max) {
                        more[0] = true; // 已经够了，后面不再读
                        return false;
                    }
                    lines.add(line);
                }
                return true;
            });
            if (tail > 0) lines.addAll(tailBuf);
            List<Object> red = FileGuard.redact(lines);
            List<String> safe = (List<String>) red.get(0);
            StringBuilder sb = new StringBuilder();
            int first = tail > 0 ? Math.max(1, n[0] - lines.size() + 1) : start;
            int shown = 0;
            for (String s : safe) {
                if (sb.length() + s.length() > budget) break;
                sb.append(first + shown).append(": ").append(s).append('\n');
                shown++;
            }
            JsonObject out = new JsonObject();
            out.addProperty("path", r);
            if (!more[0] && !cut) out.addProperty("total_lines", n[0]);
            else out.addProperty("more_lines_after", true);
            if (cut) out.addProperty("scan_limit", "文件太大，只扫描了前 64 MiB");
            out.addProperty("from_line", first);
            out.addProperty("lines_shown", shown);
            if ((int) red.get(1) > 0) out.addProperty("redacted_values", (int) red.get(1));
            if (shown < safe.size()) out.addProperty("note", "超过单次读取上限 " + budget + " 字，后面的请用 start_line 继续读");
            out.addProperty("content", sb.toString());
            return out.toString();
        }
    }

    // ———————————————————— 小工具 ————————————————————

    static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static int levelRank(String level) {
        return switch (level) {
            case "WARN" -> 1;
            case "ERROR", "FATAL" -> 2;
            default -> 0;
        };
    }

    private static String shortLogger(String l) {
        int i = l.lastIndexOf('.');
        return i >= 0 ? l.substring(i + 1) : l;
    }
}
