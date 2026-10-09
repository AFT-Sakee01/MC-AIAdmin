package io.github.aftsakee.aiadmin.neoforge;

import io.github.aftsakee.aiadmin.gamedata.GameData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;

/**
 * 游戏对象 → 给 AI、日志看的文字。事件层（TrackerHooks、StoryHooks、ServerHooks）和观察工具（McTools、TrackerTools）共用这一套。
 * <p>原因：以前各类各写一份，物品/实体显示名有两套格式（TrackerHooks"英文名 [id · 模组名]"、StoryHooks"英文名 / 中文名 [完整 id]"
 * 和"英文名「自定义名」"），维度有的丢模组命名空间。统一后：
 * <ul>
 *   <li>玩家名 {@link #name}：GameProfile 名（登录名，不是显示名/队伍前缀）。</li>
 *   <li>维度：{@link #dimId} 是完整 id（minecraft:overworld），存进追踪、战斗记录、建筑账本、工程这些数据里，core 按它比较和过滤；
 *       {@link #dim} 是显示用：原版只写路径（overworld），模组维度保留命名空间（不同模组的同名维度才分得清），和 core 的 PlayerTracker.shortId 一致。</li>
 *   <li>方块坐标 {@link #xyz}："x y z"，整数方块坐标（BlockPos / blockPosition()，向下取整，负坐标不会差 1）；{@link #where} = 显示维度 + 坐标。</li>
 *   <li>计数键 {@link #key}：原版只用 id 的 path，模组的用"命名空间:path（英文名）"。</li>
 *   <li>显示名 {@link #label}："英文名 [path]"，模组的 id 带命名空间并加模组名："Mechanical Press / 动力压机 [create:mechanical_press · Create]"；
 *       游戏资料（GameDataIndex，模组 jar 自带的 zh_cn）里有中文名时英文名后加" / 中文名"（原版物品服务端没有中文名）；
 *       物品/生物有自定义名时前面加「自定义名」；玩家写成"玩家 名字"。</li>
 * </ul>
 * 边界：格式被端到端测试断言——scripts/e2e_tracker.py 查"Lever [lever]""Pig [pig]""Chest [chest]""Diamond Ore [diamond_ore]"
 * 和计数键"diamond×5""dirt×7"，scripts/e2e_social.py 查里程碑里有"beacon""diamond"；改格式要同步改脚本。
 * <p>线程：读实体、方块状态、物品的方法只在服务端主线程调用；全部是内存操作（游戏资料快照不可变，模组列表开服后不变）。
 */
final class McFmt {
    private McFmt() {}

    // ———————————————————— 玩家、维度、坐标 ————————————————————

    static String name(Player p) {
        return p.getGameProfile().getName();
    }

    /** 完整维度 id：minecraft:overworld、twilightforest:twilight_forest。存数据、和 core 比较用。 */
    static String dimId(Level level) {
        return level.dimension().location().toString();
    }

    static String dimId(Entity e) {
        return dimId(e.level());
    }

    /** 显示用维度名：原版只写路径（overworld），模组维度保留命名空间（twilightforest:twilight_forest）。 */
    static String dim(ResourceKey<Level> key) {
        ResourceLocation d = key.location();
        return d.getNamespace().equals("minecraft") ? d.getPath() : d.toString();
    }

    /** "x y z"（方块坐标）。 */
    static String xyz(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    /** "overworld x y z"：显示维度 + 方块坐标。 */
    static String where(Level level, BlockPos pos) {
        return dim(level.dimension()) + " " + xyz(pos);
    }

    /** 实体所在的维度和方块坐标。 */
    static String where(Entity e) {
        return where(e.level(), e.blockPosition());
    }

    // ———————————————————— 注册 id ————————————————————

    static String id(BlockState s) {
        return BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
    }

    static String id(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
    }

    static String id(Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
    }

    /** 短 id：原版只写 path，模组的保留命名空间（ns:path），不同模组的同名物品/实体不会混在一起。 */
    static String shortId(ResourceLocation id) {
        return id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
    }

    // ———————————————————— 计数键 ————————————————————

    /** 计数用的键：原版只用 id 的 path；模组的用"命名空间:path（英文名）"，一眼看出是哪个模组的什么东西。 */
    static String key(ResourceLocation id, String name) {
        return id.getNamespace().equals("minecraft") ? id.getPath() : id + "（" + name + "）";
    }

    static String key(BlockState s) {
        return key(BuiltInRegistries.BLOCK.getKey(s.getBlock()), s.getBlock().getName().getString());
    }

    static String key(Entity e) {
        return key(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()), e.getType().getDescription().getString());
    }

    /** 物品计数键（用物品本身的英文名，不用自定义名——改过名的同一种物品计在一起）。 */
    static String itemKey(ItemStack s) {
        return key(BuiltInRegistries.ITEM.getKey(s.getItem()), s.getItem().getDescription().getString());
    }

    /** "计数键×数量"。 */
    static String stack(ItemStack s) {
        return itemKey(s) + "×" + s.getCount();
    }

    // ———————————————————— 显示名 ————————————————————

    /** "英文名[ / 中文名] [id]"，模组的再加模组名：Mechanical Press [create:mechanical_press · Create]。zh 为 null 时不加中文名。 */
    private static String label(ResourceLocation id, String name, String zh) {
        String shown = zh == null ? name : name + " / " + zh;
        String ns = id.getNamespace();
        if (ns.equals("minecraft")) return shown + " [" + id.getPath() + "]";
        String mod = ModList.get().getModContainerById(ns).map(c -> c.getModInfo().getDisplayName()).orElse(ns);
        return shown + " [" + id + " · " + mod + "]";
    }

    /**
     * 游戏资料里这个 id 的中文名；没建好快照、没有这个物品、没有中文名或中文名和英文名一样时返回 null。
     * 方块按同名物品查（绝大多数方块物品和方块同 id，中文名也来自同一个翻译键）；实体没有中文名来源。
     */
    private static String zh(ResourceLocation id) {
        GameData d = GameDataIndex.cached();
        GameData.ItemInfo i = d == null ? null : d.items.get(id.toString());
        if (i == null || i.zh() == null || i.zh().isBlank() || i.zh().equals(i.en())) return null;
        return i.zh();
    }

    static String label(BlockState s) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(s.getBlock());
        return label(id, s.getBlock().getName().getString(), zh(id));
    }

    static String label(ItemStack s) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(s.getItem());
        String base = label(id, s.getItem().getDescription().getString(), zh(id));
        return s.has(DataComponents.CUSTOM_NAME) ? "「" + s.getHoverName().getString() + "」" + base : base;
    }

    static String label(Entity e) {
        if (e instanceof ServerPlayer sp) return "玩家 " + name(sp);
        String base = label(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()), e.getType().getDescription().getString(), null);
        return e.hasCustomName() ? "「" + e.getName().getString() + "」" + base : base;
    }
}
