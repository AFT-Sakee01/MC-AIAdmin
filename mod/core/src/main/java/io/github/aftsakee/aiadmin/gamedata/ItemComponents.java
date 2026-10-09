package io.github.aftsakee.aiadmin.gamedata;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.aftsakee.aiadmin.util.Json;

import java.util.ArrayList;
import java.util.List;

/**
 * 物品数据组件（data components）里决定"这是哪一种"的部分，转成简短说明。目前只解析药水内容（minecraft:potion_contents）：
 * 药水、喷溅/滞留药水、药箭同一个物品 ID 下有几十种，不看组件只能说成"药箭"。
 * <p>输入是编码后的 JSON（ItemStack.CODEC 的 components、PotionContents.CODEC、set_potion 函数的 id）。
 * 说明的格式（也是 {@link GameData#variant} 变体键里的药水部分，同一内容总得到同一字符串）：
 * <ul>
 *   <li>只有药水："minecraft:long_poison"；</li>
 *   <li>只有自定义效果："自定义效果 minecraft:speed 2级 30秒、minecraft:jump_boost 10秒"；</li>
 *   <li>两者都有："minecraft:poison + 自定义效果 …"。</li>
 * </ul>
 * 边界：只有自定义颜色（custom_color）、没有药水也没有效果时返回 null（颜色不影响是哪种药水）；
 * 组件补丁里的移除写法（"!minecraft:potion_contents"）不算药水。不认识的结构返回 null，不抛异常。
 */
public final class ItemComponents {
    static final String POTION_CONTENTS = "minecraft:potion_contents";

    private ItemComponents() {}

    /** components 对象（{"minecraft:potion_contents": …, …}）→ 药水说明；没有药水内容返回 null。 */
    public static String potion(JsonElement components) {
        if (components == null || !components.isJsonObject()) return null;
        JsonObject c = components.getAsJsonObject();
        JsonElement pc = c.get(POTION_CONTENTS);
        if (pc == null) pc = c.get("potion_contents"); // 数据包里可以省略 minecraft: 命名空间
        return potionContents(pc);
    }

    /** potion_contents 组件的值：字符串（只有药水 ID 的简写）或 {potion, custom_color, custom_effects}。 */
    public static String potionContents(JsonElement pc) {
        if (pc == null || pc.isJsonNull()) return null;
        String only = Json.str(pc);
        if (only != null) return only.isBlank() ? null : GameData.norm(only);
        if (!pc.isJsonObject()) return null;
        JsonObject o = pc.getAsJsonObject();
        String potion = Json.str(o.get("potion"));
        List<String> effects = new ArrayList<>();
        for (JsonElement e : Json.arr(o, "custom_effects")) {
            if (e.isJsonObject()) {
                String s = effect(e.getAsJsonObject());
                if (s != null) effects.add(s);
            }
        }
        String base = potion == null || potion.isBlank() ? null : GameData.norm(potion);
        if (effects.isEmpty()) return base;
        String custom = "自定义效果 " + String.join("、", effects);
        return base == null ? custom : base + " + " + custom;
    }

    /** 一个状态效果（MobEffectInstance.CODEC）："minecraft:speed 2级 30秒"。等级 1 不写；瞬间效果（时长 0）不写时长；-1 = 无限。 */
    static String effect(JsonObject e) {
        String id = Json.str(e.get("id"));
        if (id == null || id.isBlank()) return null;
        StringBuilder sb = new StringBuilder(GameData.norm(id));
        int amp = Json.intOf(e, "amplifier", 0);
        if (amp > 0) sb.append(' ').append(amp + 1).append("级");
        int ticks = Json.intOf(e, "duration", 0);
        if (ticks < 0) sb.append(" 无限");
        else if (ticks > 0) sb.append(' ').append(duration(ticks));
        return sb.toString();
    }

    /** 刻 → "30秒"、"1分30秒"、"3分"（不足 1 秒按 1 秒）。 */
    static String duration(int ticks) {
        int s = Math.max(1, Math.round(ticks / 20f));
        if (s < 60) return s + "秒";
        int m = s / 60, r = s % 60;
        return m + "分" + (r == 0 ? "" : r + "秒");
    }
}
