package io.github.aftsakee.aiadmin.neoforge;

import com.google.gson.JsonElement;
import io.github.aftsakee.aiadmin.gamedata.GameData.Ingredient;
import io.github.aftsakee.aiadmin.gamedata.GameData.Recipe;
import io.github.aftsakee.aiadmin.gamedata.ItemComponents;
import io.github.aftsakee.aiadmin.gamedata.RecipeJson;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.alchemy.PotionContents;
import net.neoforged.neoforge.common.brewing.BrewingRecipe;
import net.neoforged.neoforge.common.brewing.IBrewingRecipe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 酿造台配方的只读枚举（PotionBrewing 是代码不是数据配方，Recipe.CODEC 读不到）。
 * <p>线程：只在 {@link GameDataIndex} 构建快照时、服务端主线程上调用（读注册表和 PotionBrewing）；不改任何游戏状态，不做 IO。
 * <p>方法：原版的混合表（potionMixes / containerMixes）是私有字段，不用反射，改用公开 API 试出来——
 * 候选材料 = 所有物品里 isIngredient 为真的（默认组件）；瓶子 = 药水 / 喷溅药水 / 滞留药水 × 每种注册的药水；
 * 对每个（瓶子, 材料）先 hasMix 再 mix 取结果，调用方式和酿造台（BrewingStandBlockEntity）一致：mix(材料, 瓶子)。
 * 这样原版混合、容器转换（加火药 / 龙息）和模组经 RegisterBrewingRecipesEvent 加的配方（瓶子是药水类物品时）都能覆盖。
 * NeoForge 的 BrewingRecipe（简单配方，输入可以不是药水瓶）另外直接读 input / ingredient / output。
 * <p>边界：模组新增的"瓶子"容器（Builder.addContainer）在私有列表里，枚举不到以它们为输入的原版混合；自定义逻辑的 IBrewingRecipe
 * 只能靠上面的试探发现（输入是三种药水瓶时）；要求特殊组件的材料按默认组件试，试不出来的会漏掉。
 * <p>开销：物品数 × 混合表长度次 Ingredient.test（几千物品 × 几十条，几十万次）+ 3 × 药水数 × 候选材料数次 hasMix，
 * 几十毫秒级；只在开服和 /reload 后建快照时做一次。
 */
final class GameDataBrewing {
    private GameDataBrewing() {}

    /**
     * recipes = 酿造配方（同一转换能用多种材料时合成一条，材料多选一）；lingeringPotions = 能酿出滞留药水的药水（药箭按这些展开）；
     * failed = 试探时抛异常的组合数（模组的自定义酿造配方对没见过的输入抛异常时只跳过那一个组合）。
     */
    record Result(List<Recipe> recipes, List<String> lingeringPotions, int failed) {}

    /** 一种转换（输入瓶子 → 产物）和能用的材料。 */
    private static final class Mix {
        final String inItem, inPotion, outItem, outPotion;
        final Set<String> materials = new LinkedHashSet<>();

        Mix(String inItem, String inPotion, String outItem, String outPotion) {
            this.inItem = inItem;
            this.inPotion = inPotion;
            this.outItem = outItem;
            this.outPotion = outPotion;
        }
    }

    static Result enumerate(MinecraftServer server, RegistryOps<JsonElement> ops) {
        PotionBrewing brewing = server.potionBrewing();
        List<ItemStack> materials = new ArrayList<>();
        int failed = 0;
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) continue;
            ItemStack s = new ItemStack(item);
            try {
                if (brewing.isIngredient(s)) materials.add(s);
            } catch (RuntimeException ex) {
                failed++;
            }
        }
        Map<String, Mix> mixes = new LinkedHashMap<>();
        List<Holder.Reference<Potion>> potions = BuiltInRegistries.POTION.holders().toList();
        for (Item bottle : List.of(Items.POTION, Items.SPLASH_POTION, Items.LINGERING_POTION)) {
            for (Holder<Potion> p : potions) {
                ItemStack in = PotionContents.createItemStack(bottle, p);
                for (ItemStack m : materials) {
                    try {
                        if (!brewing.hasMix(in, m)) continue;
                        ItemStack out = brewing.mix(m.copy(), in.copy());
                        if (out.isEmpty() || ItemStack.isSameItemSameComponents(out, in)) continue;
                        add(mixes, in, m, out, ops);
                    } catch (RuntimeException ex) {
                        failed++; // 模组的自定义酿造逻辑不认识这个组合：跳过它，不让整个枚举失败
                    }
                }
            }
        }
        for (IBrewingRecipe r : brewing.getRecipes()) {
            if (!(r instanceof BrewingRecipe br)) continue; // 自定义逻辑的配方只能靠上面的试探
            try {
                ItemStack out = br.getOutput();
                if (out.isEmpty()) continue;
                for (ItemStack in : br.getInput().getItems()) {
                    for (ItemStack m : br.getIngredient().getItems()) add(mixes, in, m, out, ops);
                }
            } catch (RuntimeException ex) {
                failed++; // 自定义原料类型展开物品时出错：只跳过这一条
            }
        }
        List<Recipe> recipes = new ArrayList<>();
        Set<String> lingering = new LinkedHashSet<>();
        Set<String> ids = new LinkedHashSet<>();
        for (Mix x : mixes.values()) {
            Recipe r = RecipeJson.brewing(x.inItem, x.inPotion, new Ingredient(List.copyOf(x.materials), null, 1, false), x.outItem, x.outPotion);
            // 拼出来的 ID 撞了（只有自定义效果的药水都写 custom，或不同命名空间路径相同）：加序号区分，配方本身不丢
            String id = r.id();
            for (int n = 2; !ids.add(id); n++) id = r.id() + "#" + n;
            if (!id.equals(r.id())) r = new Recipe(id, r.type(), r.category(), r.station(), r.inputs(), r.outputs(), r.shape(), r.key(), r.extra(), r.droppedSlots());
            recipes.add(r);
            if ("minecraft:lingering_potion".equals(x.outItem) && x.outPotion != null && !x.outPotion.contains(" ")) lingering.add(x.outPotion);
        }
        return new Result(recipes, List.copyOf(lingering), failed);
    }

    private static void add(Map<String, Mix> mixes, ItemStack in, ItemStack material, ItemStack out, RegistryOps<JsonElement> ops) {
        if (in.isEmpty() || material.isEmpty()) return;
        String inItem = id(in), outItem = id(out), inPotion = potionOf(in, ops), outPotion = potionOf(out, ops);
        Mix x = mixes.computeIfAbsent(inItem + "|" + inPotion + "|" + outItem + "|" + outPotion, k -> new Mix(inItem, inPotion, outItem, outPotion));
        x.materials.add(id(material));
    }

    private static String id(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
    }

    /** 物品堆的药水内容 → core 的药水说明（没有返回 null）。编码失败（模组效果的编码器异常）按没有药水处理。 */
    static String potionOf(ItemStack s, RegistryOps<JsonElement> ops) {
        PotionContents pc = s.get(DataComponents.POTION_CONTENTS);
        if (pc == null) return null;
        try {
            return ItemComponents.potionContents(PotionContents.CODEC.encodeStart(ops, pc).getOrThrow());
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
