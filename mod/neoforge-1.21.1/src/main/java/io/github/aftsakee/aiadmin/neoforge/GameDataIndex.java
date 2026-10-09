package io.github.aftsakee.aiadmin.neoforge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.github.aftsakee.aiadmin.gamedata.GameData;
import io.github.aftsakee.aiadmin.gamedata.GameData.Ingredient;
import io.github.aftsakee.aiadmin.gamedata.GameData.ItemInfo;
import io.github.aftsakee.aiadmin.gamedata.GameData.LootTable;
import io.github.aftsakee.aiadmin.gamedata.GameData.Recipe;
import io.github.aftsakee.aiadmin.gamedata.GameData.Stack;
import io.github.aftsakee.aiadmin.gamedata.LootJson;
import io.github.aftsakee.aiadmin.gamedata.RecipeJson;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.fml.ModList;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 从运行中的服务端构建 {@link GameData} 快照，并在数据包重载（/reload）后自动重建。
 * <p>线程：{@link #get} 只能在服务端主线程调用（读 RecipeManager、注册表、战利品表）；拿到的快照不可变，之后可以交给任何线程。
 * <p>重建时机：MinecraftServer#getServerResources() 每次 /reload 都会换成新对象，用它的身份判断是否需要重建；
 * 首次构建在开服完成时做（ServerStartedEvent），2000 多条配方 + 1000 多张战利品表大约几百毫秒，只在开服和 /reload 后各一次。
 * <p>中文名：服务端没有原版的 zh_cn（原版语言文件只在客户端资源里），只能读各模组 jar 自带的 assets/&lt;ns&gt;/lang/zh_cn.json，开服时读一次缓存。
 * <p>代码实现的配方：酿造台（{@link GameDataBrewing} 只读枚举 PotionBrewing）和原版药箭特殊配方（按药水展开，见 RecipeJson#expandSpecial）
 * 也转成普通配方放进快照，同样只在构建时（主线程）读一次。
 */
final class GameDataIndex {
    private static volatile GameData data;
    private static Object builtFor;
    private static Map<String, String> zh;
    /** 上次整体构建失败的资源对象、时间和原因：1 分钟内不再重试（构建要几百毫秒到几秒，都在主线程上）。 */
    private static Object failedFor;
    private static long failedAt;
    private static String failure;
    static final long RETRY_MS = 60_000L;

    private GameDataIndex() {}

    /**
     * 主线程：取当前快照，资源重载过就先重建。
     * <p>失败处理：整次构建失败（模组编码器抛 Error 之类）时记下失败，1 分钟内直接用旧快照（/reload 之前的）或报错，
     * 不再每次工具调用、每个目标检查周期都在主线程重跑一遍构建。
     */
    static GameData get(MinecraftServer server) {
        Object key = server.getServerResources();
        if (data != null && builtFor == key) return data;
        long now = System.currentTimeMillis();
        if (failedFor == key && now - failedAt < RETRY_MS) {
            if (data != null) return data;
            throw new IllegalStateException("游戏资料构建失败（" + failure + "），1 分钟后再试");
        }
        try {
            data = build(server);
            builtFor = key;
            failedFor = null;
            return data;
        } catch (RuntimeException | LinkageError e) {
            failedFor = key;
            failedAt = now;
            failure = e.getClass().getSimpleName() + "：" + e.getMessage();
            AiAdminMod.LOG.error("[AI管理] 游戏资料构建失败，{} 秒内用旧快照或报错：{}", RETRY_MS / 1000, failure);
            if (data != null) return data;
            throw new IllegalStateException("游戏资料构建失败（" + failure + "），1 分钟后再试");
        }
    }

    /** 任意线程：上一次构建好的快照（可能是 /reload 之前的；还没构建过返回 null）。 */
    static GameData cached() {
        return data;
    }

    static void clear() {
        data = null;
        builtFor = null;
        failedFor = null;
    }

    private static GameData build(MinecraftServer server) {
        long t0 = System.currentTimeMillis();
        List<String> warnings = new ArrayList<>();
        RegistryOps<JsonElement> ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        if (zh == null) zh = loadZh(warnings);

        Map<String, ItemInfo> items = new LinkedHashMap<>();
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (id.getPath().equals("air")) continue;
            String en = item.getDescription().getString();
            items.put(id.toString(), new ItemInfo(id.toString(), en, zh.get(item.getDescriptionId())));
        }

        Map<String, List<String>> tags = new LinkedHashMap<>();
        try (Stream<com.mojang.datafixers.util.Pair<TagKey<Item>, HolderSet.Named<Item>>> s = BuiltInRegistries.ITEM.getTags()) {
            s.forEach(p -> {
                List<String> members = new ArrayList<>();
                for (Holder<Item> h : p.getSecond()) members.add(BuiltInRegistries.ITEM.getKey(h.value()).toString());
                tags.put(p.getFirst().location().toString(), members);
            });
        }

        // 酿造台配方（代码，不在 RecipeManager 里）：只读枚举；失败只少了酿造配方，不影响其它资料
        List<Recipe> brewingRecipes = List.of();
        List<String> tippedPotions;
        try {
            GameDataBrewing.Result br = GameDataBrewing.enumerate(server, ops);
            brewingRecipes = br.recipes();
            tippedPotions = br.lingeringPotions();
            if (br.failed() > 0) warnings.add("酿造配方枚举时 " + br.failed() + " 个组合出错（模组的自定义酿造逻辑），已跳过");
        } catch (RuntimeException | LinkageError ex) {
            warnings.add("酿造配方枚举失败（" + ex.getClass().getSimpleName() + "），没有酿造配方");
            tippedPotions = allPotions(); // 药箭仍按注册的全部药水展开（可能含酿不出来的）
        }

        List<Recipe> recipes = new ArrayList<>();
        int fallback = 0, failed = 0, partial = 0, expanded = 0;
        for (RecipeHolder<?> h : server.getRecipeManager().getRecipes()) {
            ResourceLocation serKey = BuiltInRegistries.RECIPE_SERIALIZER.getKey(h.value().getSerializer());
            if (serKey != null && serKey.getNamespace().equals("justenoughbreeding")) continue; // 繁殖/驯服说明（JEI 展示用），不是配方
            try {
                Recipe r = recipe(h, ops, tags, server);
                if (r.inputs().isEmpty() && !r.outputs().isEmpty()) fallback++;
                else if (r.droppedSlots() > 0 && !r.outputs().isEmpty()) partial++;
                recipes.add(r);
                // 代码实现、能按药水展开的特殊配方（原版药箭）：每种药水一条普通配方，原配方保留
                List<Recipe> more = RecipeJson.expandSpecial(r.id(), r.type(), r.category(), tippedPotions);
                recipes.addAll(more);
                expanded += more.size();
            } catch (RuntimeException | LinkageError ex) { // 模组编码器可能抛 Error（类缺失等）：只跳过这一条
                failed++;
            }
        }
        recipes.addAll(brewingRecipes);
        if (failed > 0) warnings.add(failed + " 条配方无法解析，已跳过");
        if (partial > 0) warnings.add(partial + " 条配方有原料槽解析不出（模组自定义的原料写法），已标为原料不全、不参与合成规划");

        // 两遍：先把所有表编码成 JSON，再逐张解析。原因：引用子表的条目（钓鱼 → 鱼/垃圾/宝藏）要把子表按引用条目的概率展开进父表，
        // 解析一张表时得能查到别的表；JSON 映射只在这次构建里用，构建完就丢
        Map<String, JsonObject> lootJson = new LinkedHashMap<>();
        Map<String, String> lootErr = new LinkedHashMap<>();
        for (ResourceLocation id : server.reloadableRegistries().getKeys(Registries.LOOT_TABLE)) {
            String sid = id.toString();
            try {
                net.minecraft.world.level.storage.loot.LootTable t = server.reloadableRegistries().getLootTable(ResourceKey.create(Registries.LOOT_TABLE, id));
                JsonElement j = net.minecraft.world.level.storage.loot.LootTable.DIRECT_CODEC.encodeStart(ops, t).getOrThrow();
                lootJson.put(sid, j.getAsJsonObject());
            } catch (RuntimeException | LinkageError ex) {
                lootErr.put(sid, "无法读取：" + ex.getClass().getSimpleName());
            }
        }
        Map<String, LootTable> loot = new LinkedHashMap<>();
        int lootFailed = lootErr.size();
        for (ResourceLocation id : server.reloadableRegistries().getKeys(Registries.LOOT_TABLE)) {
            String sid = id.toString();
            JsonObject j = lootJson.get(sid);
            if (j == null) {
                loot.put(sid, new LootTable(sid, LootJson.kind(sid), List.of(), List.of(), lootErr.getOrDefault(sid, "无法读取")));
                continue;
            }
            try {
                // 标签此时已建好：expand 标签条目要成员数；lootJson::get 让引用的子表能展开（读不到的子表记进表的 notes）
                loot.put(sid, LootJson.parse(sid, j, tg -> tags.getOrDefault(tg, List.of()), lootJson::get));
            } catch (RuntimeException ex) {
                lootFailed++;
                loot.put(sid, new LootTable(sid, LootJson.kind(sid), List.of(), List.of(), "无法解析：" + ex.getClass().getSimpleName()));
            }
        }
        if (lootFailed > 0) warnings.add(lootFailed + " 张战利品表无法编码（多为模组自定义条件/函数），只记了表名");

        GameData d = new GameData(items, recipes, tags, loot, warnings, System.currentTimeMillis());
        AiAdminMod.LOG.info("[AI管理] 游戏资料已建立：物品 {}，配方 {}（其中 {} 条只读到产物，酿造台 {} 条，药箭按药水展开 {} 条），标签 {}，战利品表 {}，中文名 {} 条，用时 {} ms{}",
                items.size(), recipes.size(), fallback, brewingRecipes.size(), expanded, tags.size(), loot.size(), zh.size(), System.currentTimeMillis() - t0,
                warnings.isEmpty() ? "" : "；" + String.join("；", warnings));
        return d;
    }

    /**
     * 一条配方：先用配方自己的编码器转成 JSON 解析（原版和模组机器通用），
     * 解析不出产物/原料时用 Recipe#getResultItem / getIngredients 兜底。
     */
    private static Recipe recipe(RecipeHolder<?> h, RegistryOps<JsonElement> ops, Map<String, List<String>> tags, MinecraftServer server) {
        net.minecraft.world.item.crafting.Recipe<?> r = h.value();
        String id = h.id().toString();
        ResourceLocation ser = BuiltInRegistries.RECIPE_SERIALIZER.getKey(r.getSerializer());
        ResourceLocation cat = BuiltInRegistries.RECIPE_TYPE.getKey(r.getType());
        String type = ser == null ? "?" : ser.toString();
        String category = cat == null ? type : cat.toString();
        List<Ingredient> inputs = List.of();
        List<Stack> outputs = List.of();
        List<String> shape = null;
        Map<String, String> key = null;
        String extra = null;
        int dropped = 0;
        try {
            JsonElement j = net.minecraft.world.item.crafting.Recipe.CODEC.encodeStart(ops, r).getOrThrow();
            if (j.isJsonObject()) {
                RecipeJson.Parsed p = RecipeJson.parse(j.getAsJsonObject(), t -> tags.getOrDefault(t, List.of()));
                inputs = p.inputs();
                outputs = p.outputs();
                shape = p.shape();
                key = p.key();
                extra = p.extra();
                dropped = p.droppedSlots();
            }
        } catch (RuntimeException ignored) {
            // 有的模组配方不能编码（或编码器有副作用限制），走下面的兜底
        }
        if (outputs.isEmpty()) {
            try {
                ItemStack res = r.getResultItem(server.registryAccess());
                if (!res.isEmpty()) outputs = List.of(new Stack(BuiltInRegistries.ITEM.getKey(res.getItem()).toString(), res.getCount(), 1, false,
                        GameDataBrewing.potionOf(res, ops)));
            } catch (RuntimeException ignored) {
                // 个别配方的 getResultItem 依赖输入，取不到就当特殊配方
            }
        }
        if (inputs.isEmpty()) {
            List<Ingredient> l = new ArrayList<>();
            for (net.minecraft.world.item.crafting.Ingredient ing : r.getIngredients()) {
                if (ing.isEmpty()) continue;
                String tag = null;
                if (!ing.isCustom()) {
                    for (net.minecraft.world.item.crafting.Ingredient.Value v : ing.getValues()) {
                        if (v instanceof net.minecraft.world.item.crafting.Ingredient.TagValue tv) tag = tv.tag().location().toString();
                    }
                }
                List<String> opts = new ArrayList<>();
                for (ItemStack s : ing.getItems()) {
                    String sid = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
                    if (!opts.contains(sid)) opts.add(sid);
                }
                l.add(new Ingredient(List.copyOf(opts), tag, 1, false));
            }
            inputs = mergeSame(l);
            // 兜底拿到的是配方自己报告的完整原料表（Recipe#getIngredients），JSON 里丢掉的槽不再作数；兜底也是空的就仍是"原料未知"
            if (!inputs.isEmpty()) dropped = 0;
        }
        return new Recipe(id, type, category, RecipeJson.station(category, type), inputs, outputs, shape, key, extra, dropped);
    }

    /** 注册的全部药水 ID（酿造配方枚举失败时药箭的兜底展开列表）。 */
    private static List<String> allPotions() {
        List<String> l = new ArrayList<>();
        for (ResourceLocation k : BuiltInRegistries.POTION.keySet()) l.add(k.toString());
        return l;
    }

    private static List<Ingredient> mergeSame(List<Ingredient> in) {
        Map<String, Ingredient> m = new LinkedHashMap<>();
        for (Ingredient i : in) {
            String k = i.tag() + "|" + i.options();
            Ingredient prev = m.get(k);
            m.put(k, prev == null ? i : new Ingredient(i.options(), i.tag(), prev.count() + 1, false));
        }
        return new ArrayList<>(m.values());
    }

    /** 读所有模组 jar 里的 assets/*&#47;lang/zh_cn.json，合并成 翻译键 → 中文。 */
    private static Map<String, String> loadZh(List<String> warnings) {
        Map<String, String> out = new HashMap<>();
        int files = 0;
        for (var info : ModList.get().getModFiles()) {
            try {
                Path assets = info.getFile().findResource("assets");
                if (assets == null || !Files.isDirectory(assets)) continue;
                try (Stream<Path> ns = Files.list(assets)) {
                    for (Path dir : ns.toList()) {
                        Path f = dir.resolve("lang").resolve("zh_cn.json");
                        if (!Files.isRegularFile(f)) continue;
                        try (Reader rd = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                            JsonElement e = JsonParser.parseReader(rd);
                            if (!e.isJsonObject()) continue;
                            JsonObject o = e.getAsJsonObject();
                            for (Map.Entry<String, JsonElement> kv : o.entrySet()) {
                                String k = kv.getKey();
                                if ((k.startsWith("item.") || k.startsWith("block.")) && kv.getValue().isJsonPrimitive()) out.putIfAbsent(k, kv.getValue().getAsString());
                            }
                            files++;
                        } catch (IOException | RuntimeException ex) {
                            warnings.add("读不了 " + f.getFileName() + "（" + dir.getFileName() + "）");
                        }
                    }
                }
            } catch (IOException | RuntimeException ex) {
                // 个别模组文件系统不支持列目录，跳过
            }
        }
        AiAdminMod.LOG.debug("[AI管理] 读到 {} 个模组中文语言文件", files);
        return out;
    }
}
