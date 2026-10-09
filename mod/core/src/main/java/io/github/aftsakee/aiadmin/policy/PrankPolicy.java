package io.github.aftsakee.aiadmin.policy;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;

/**
 * 恶作剧模式下"回复普通玩家聊天"时的指令白名单（在 {@link CommandPolicy} 黑名单之后再查一遍）。
 * <p>为什么要单独一层：恶作剧模式让模型主动对玩家做事，提示词约束不可靠，所以档位必须在执行前强制检查。
 * 三档：
 * <ul>
 *   <li>{@link Level#CHAT_ONLY}：只动嘴，任何指令都不执行；</li>
 *   <li>{@link Level#HARMLESS}：音效、粒子、标题/假消息、几秒的无害效果、刷几只无害小动物、给搞笑物品；</li>
 *   <li>{@link Level#ROUGH}：再加 1.5 颗心以内的伤害、8 格内的相对传送/转身、更长更强的负面效果（中毒不会致死）。</li>
 * </ul>
 * 任何档位都不允许：杀死、清背包、改方块、封禁/踢人、execute 嵌套、带 NBT/组件的召唤和物品、@e/@s 选择器。
 * 边界：传送只校验偏移量，不检查落点是否在方块里（玩家可以自己走出来）；漂浮只允许 0 级、限制在几秒内，避免摔伤（无害档 3 秒约升 3 格，不掉血）。
 */
public final class PrankPolicy {
    public enum Level {
        CHAT_ONLY("chat_only", "只动嘴"), HARMLESS("harmless", "无害整蛊"), ROUGH("rough", "较狠");

        public final String id;
        public final String label;

        Level(String id, String label) {
            this.id = id;
            this.label = label;
        }

        /** 不认识的值按无害档处理。 */
        public static Level parse(String s) {
            for (Level l : values()) if (l.id.equalsIgnoreCase(s == null ? "" : s.trim())) return l;
            return HARMLESS;
        }

        public static boolean valid(String s) {
            for (Level l : values()) if (l.id.equalsIgnoreCase(s == null ? "" : s.trim())) return true;
            return false;
        }
    }

    /** 效果 → 允许的最长秒数（无害档）。 */
    static final Map<String, Integer> HARMLESS_EFFECTS = Map.ofEntries(
            Map.entry("glowing", 10), Map.entry("night_vision", 10), Map.entry("speed", 10), Map.entry("slowness", 10),
            Map.entry("jump_boost", 10), Map.entry("nausea", 10), Map.entry("slow_falling", 10), Map.entry("invisibility", 10),
            Map.entry("luck", 10), Map.entry("unluck", 10), Map.entry("haste", 10), Map.entry("dolphins_grace", 10),
            Map.entry("water_breathing", 10), Map.entry("levitation", 3));
    /** 较狠档额外允许的效果和秒数（覆盖无害档的同名项）。 */
    static final Map<String, Integer> ROUGH_EFFECTS = Map.ofEntries(
            Map.entry("blindness", 15), Map.entry("darkness", 15), Map.entry("hunger", 20), Map.entry("mining_fatigue", 20),
            Map.entry("weakness", 20), Map.entry("poison", 8), Map.entry("levitation", 6));
    static final Set<String> MOBS = Set.of("chicken", "bat", "rabbit", "parrot", "frog", "pig", "sheep");
    static final Set<String> JOKE_ITEMS = Set.of("poisonous_potato", "rotten_flesh", "dead_bush", "stick", "cookie", "cake", "poppy", "dirt",
            "egg", "snowball", "slime_ball", "bone", "feather", "string", "spider_eye", "kelp", "sweet_berries", "pufferfish");
    static final int MAX_GIVE = 16;
    static final int MAX_PARTICLES = 500;
    static final int MAX_TP = 8;
    static final double MAX_DAMAGE = 3.0;

    private PrankPolicy() {}

    /**
     * @param immune  免疫名单（小写玩家名）；非空时不允许用 @a/@p/@r，必须逐个点名
     * @param summons 本次运行已经召唤过几只（调用方计数），超过 maxSummons 拒绝
     */
    public static CommandPolicy.Verdict check(String raw, Level level, Set<String> immune, IntSupplier summons, int maxSummons) {
        if (level == Level.CHAT_ONLY) return CommandPolicy.Verdict.deny("恶作剧模式（只动嘴）：回复玩家聊天时不能执行指令，只能用聊天开玩笑");
        String cmd = CommandPolicy.normalize(raw);
        if (cmd.isEmpty()) return CommandPolicy.Verdict.deny("空指令");
        String[] t = cmd.split(" ");
        String root = strip(t[0]);
        boolean rough = level == Level.ROUGH;
        try {
            return switch (root) {
                case "playsound" -> t.length >= 4 ? target(t[3], immune) : deny("playsound 要写明 <音效> <来源> <玩家>");
                case "particle" -> particle(t);
                case "title", "tellraw" -> t.length >= 3 ? target(t[1], immune) : deny(root + " 缺少参数");
                case "effect" -> effect(t, rough, immune);
                case "summon" -> summon(t, summons, maxSummons);
                case "give" -> give(t, immune);
                case "tp", "teleport" -> rough ? tp(t, immune) : deny("无害档不允许传送玩家");
                case "damage" -> rough ? damage(t, immune) : deny("无害档不允许伤害玩家");
                default -> deny("恶作剧模式（" + level.label + "）下回复玩家聊天时不允许指令：" + root);
            };
        } catch (NumberFormatException e) {
            return deny("参数不是数字：" + e.getMessage());
        }
    }

    /** 给模型看的规则说明（和上面的检查保持一致）。 */
    public static String describe(Level level, Set<String> immune, int maxSummons) {
        StringBuilder sb = new StringBuilder();
        switch (level) {
            case CHAT_ONLY -> sb.append("当前恶作剧档位：只动嘴。只能用 send_chat 开玩笑（夸张的假消息、谐音梗、假装要做坏事），不能执行任何指令。");
            case HARMLESS, ROUGH -> {
                sb.append("当前恶作剧档位：").append(level.label).append("。回复玩家时可以用 run_command 整蛊，只允许下面这些（其他指令会被拒绝）：\n");
                sb.append("- playsound <音效> <来源> <玩家>：怪声、苦力怕嘶嘶声等；\n");
                sb.append("- particle：粒子（数量不超过 ").append(MAX_PARTICLES).append("）；\n");
                sb.append("- title <玩家> title|subtitle|actionbar ...、tellraw <玩家> ...：屏幕大字、假消息；\n");
                sb.append("- effect give <玩家> <效果> <秒数> [等级]（必须写秒数，等级不超过 ").append(level == Level.ROUGH ? 4 : 3).append("）：");
                sb.append(effects(level)).append("；\n");
                sb.append("- summon <生物> [坐标]：").append(String.join("、", MOBS)).append("，不能带 NBT，每次最多 ").append(maxSummons).append(" 只；\n");
                sb.append("- give <玩家> <物品> [数量≤").append(MAX_GIVE).append("]：").append(String.join("、", JOKE_ITEMS)).append("（不能带组件）；\n");
                if (level == Level.ROUGH) {
                    sb.append("- tp <玩家> ~x ~y ~z [转向]：只能用相对坐标，水平各不超过 ").append(MAX_TP).append(" 格、y 在 0~1 之间；可以只转视角（~ ~ ~ ~180 ~）；\n");
                    sb.append("- damage <玩家> <伤害>：不超过 ").append(MAX_DAMAGE).append("（1.5 颗心），对方血量低于 10 时不要用。\n");
                }
                sb.append("选择器只能用玩家名或 @a/@p/@r，不能用 @e/@s 和 execute。");
                sb.append("正在战斗、血量低、在岩浆或高处边缘的玩家不要整；整完用一句话让对方知道是玩笑。");
            }
        }
        if (!immune.isEmpty()) sb.append("\n免疫名单（不能整他们，也因此不能用 @a/@p/@r）：").append(String.join("、", immune)).append('。');
        return sb.toString();
    }

    private static String effects(Level level) {
        Map<String, Integer> m = new java.util.TreeMap<>(HARMLESS_EFFECTS);
        if (level == Level.ROUGH) m.putAll(ROUGH_EFFECTS);
        List<String> out = new java.util.ArrayList<>();
        m.forEach((k, v) -> out.add(k + "≤" + v + "秒"));
        return String.join("、", out);
    }

    private static CommandPolicy.Verdict particle(String[] t) {
        for (int i = 1; i < t.length; i++) {
            if (t[i].matches("\\d+") && Long.parseLong(t[i]) > MAX_PARTICLES) return deny("粒子数量太多（最多 " + MAX_PARTICLES + "）");
            if (t[i].startsWith("@")) return deny("particle 不要指定观看者");
        }
        return CommandPolicy.Verdict.ok();
    }

    private static CommandPolicy.Verdict effect(String[] t, boolean rough, Set<String> immune) {
        if (t.length < 2 || !t[1].equalsIgnoreCase("give")) return deny("只允许 effect give（effect clear 可能清掉玩家的防火等保命效果）");
        if (t.length < 5) return deny("effect give 必须写明 <玩家> <效果> <秒数>");
        CommandPolicy.Verdict v = target(t[2], immune);
        if (!v.allowed()) return v;
        String eff = strip(t[3]);
        Integer max = rough && ROUGH_EFFECTS.containsKey(eff) ? ROUGH_EFFECTS.get(eff) : HARMLESS_EFFECTS.get(eff);
        if (max == null) return deny("这个档位不允许效果：" + eff);
        if (t[4].equalsIgnoreCase("infinite")) return deny("不允许无限时长");
        if (Integer.parseInt(t[4]) > max) return deny(eff + " 最多 " + max + " 秒");
        // 漂浮按等级加速上升（每级约 0.9 格/秒）：和其他效果共用"2/3 级"上限时，无害档 3 秒能升约 8 格、落下掉约 5 点血，
        // 较狠档 6 秒升约 21 格足以摔死——和"无害档不掉血、任何档都不杀死"矛盾，所以漂浮只允许 0 级（无害 3 秒约 2.7 格、较狠 6 秒约 5.4 格）
        if (eff.equals("levitation") && t.length >= 6 && Integer.parseInt(t[5]) > 0) return deny("漂浮只能 0 级（等级越高升得越快，落下会摔伤）");
        if (t.length >= 6 && Integer.parseInt(t[5]) > (rough ? 3 : 2)) return deny("效果等级太高");
        return CommandPolicy.Verdict.ok();
    }

    private static CommandPolicy.Verdict summon(String[] t, IntSupplier summons, int maxSummons) {
        if (t.length < 2) return deny("summon 缺少生物");
        if (!MOBS.contains(strip(t[1]))) return deny("只能召唤：" + String.join("、", MOBS));
        if (t.length > 5) return deny("召唤不能带 NBT");
        if (summons.getAsInt() >= maxSummons) return deny("本次已经召唤了 " + maxSummons + " 只，够了");
        return CommandPolicy.Verdict.ok();
    }

    private static CommandPolicy.Verdict give(String[] t, Set<String> immune) {
        if (t.length < 3) return deny("give 缺少参数");
        CommandPolicy.Verdict v = target(t[1], immune);
        if (!v.allowed()) return v;
        if (t[2].contains("[") || t[2].contains("{")) return deny("物品不能带组件/NBT");
        if (!JOKE_ITEMS.contains(strip(t[2]))) return deny("只能给搞笑物品：" + String.join("、", JOKE_ITEMS));
        if (t.length >= 4 && Integer.parseInt(t[3]) > MAX_GIVE) return deny("最多给 " + MAX_GIVE + " 个");
        return t.length > 4 ? deny("give 参数过多") : CommandPolicy.Verdict.ok();
    }

    private static CommandPolicy.Verdict tp(String[] t, Set<String> immune) {
        if (t.length < 5) return deny("tp 必须写成 tp <玩家> ~x ~y ~z [转向]");
        CommandPolicy.Verdict v = target(t[1], immune);
        if (!v.allowed()) return v;
        for (int i = 2; i <= 4; i++) {
            if (!t[i].startsWith("~")) return deny("只能用相对坐标（~），不能传到别的地方");
            double d = t[i].length() == 1 ? 0 : Double.parseDouble(t[i].substring(1));
            if (i == 3 ? (d < 0 || d > 1) : Math.abs(d) > MAX_TP) return deny("传送距离超出限制（水平 ≤" + MAX_TP + "，y 在 0~1）");
        }
        for (int i = 5; i < t.length; i++) if (!t[i].startsWith("~") && !t[i].matches("-?[\\d.]+")) return deny("转向参数不对");
        return t.length > 7 ? deny("tp 参数过多") : CommandPolicy.Verdict.ok();
    }

    private static CommandPolicy.Verdict damage(String[] t, Set<String> immune) {
        if (t.length != 3) return deny("只能写成 damage <玩家> <伤害>");
        CommandPolicy.Verdict v = target(t[1], immune);
        if (!v.allowed()) return v;
        double d = Double.parseDouble(t[2]);
        return d > MAX_DAMAGE || d < 0 ? deny("伤害最多 " + MAX_DAMAGE) : CommandPolicy.Verdict.ok();
    }

    /** 目标只能是玩家名或 @a/@p/@r；有免疫名单时必须点名且不在名单里。 */
    static CommandPolicy.Verdict target(String tok, Set<String> immune) {
        String s = tok.toLowerCase(Locale.ROOT);
        if (s.startsWith("@")) {
            if (s.startsWith("@e") || s.startsWith("@s") || s.startsWith("@n")) return deny("不能用 @e/@s/@n，只能整玩家");
            if (!immune.isEmpty()) return deny("有免疫名单时不能用 @a/@p/@r，请逐个写玩家名");
            return CommandPolicy.Verdict.ok();
        }
        if (!s.matches("[a-z0-9_]{1,16}")) return deny("目标不是玩家名：" + tok);
        if (immune.contains(s)) return deny(tok + " 在免疫名单里，不能整");
        return CommandPolicy.Verdict.ok();
    }

    private static String strip(String s) {
        String l = s.toLowerCase(Locale.ROOT);
        return l.startsWith("minecraft:") ? l.substring("minecraft:".length()) : l;
    }

    private static CommandPolicy.Verdict deny(String r) {
        return CommandPolicy.Verdict.deny(r);
    }
}
