package io.github.aftsakee.aiadmin.agent;

import io.github.aftsakee.aiadmin.config.BotConfig;
import io.github.aftsakee.aiadmin.log.JsonlLog;
import io.github.aftsakee.aiadmin.policy.PrankPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 预设模式、恶作剧档位、话量（从 AgentService 拆出，1.0.0.34）：/aibot mode | prank | talk 和云端 bot_control 切换后记在 persona.json，
 * 重启后仍有效；reset 回到设置文件的值。
 * <ul>
 *   <li>锁：本对象自己的锁，保护 PersonaState 的三个字段和它的保存。持锁时不调用别的带锁对象（免疫名单要读玩家偏好，
 *       所以 {@link #immune} 和 {@link #prompt} 不拿这把锁），AgentService / 运行线程 / 远程线程持着服务锁调用这里
 *       （锁顺序 服务 → 预设）不会死锁。</li>
 *   <li>保存是一次小文件原子写，在本对象锁内做：只在管理员切换时发生，聊天和事件路径从不等这把锁。</li>
 * </ul>
 */
final class PersonaSettings {
    static final List<String> TALK_LEVELS = List.of("quiet", "normal", "chatty");

    private final Supplier<BotConfig> cfg;
    private final Personas personas;
    private final PersonaState state;
    private final PlayerPrefs prefs;
    private final JsonlLog log;

    /** 一次运行（或云端开工）用的模式和档位：两项一起取，不会取到切换到一半的组合。 */
    record Snapshot(String mode, PrankPolicy.Level level) {}

    PersonaSettings(Supplier<BotConfig> cfg, Personas personas, PersonaState state, PlayerPrefs prefs, JsonlLog log) {
        this.cfg = cfg;
        this.personas = personas;
        this.state = state;
        this.prefs = prefs;
        this.log = log;
    }

    /** 当前预设模式：切换过就用切换后的（文件已被删掉时退回设置文件的值）。 */
    synchronized String mode() {
        String m = state.mode;
        return m != null && personas.exists(m) ? m : cfg.get().persona.mode;
    }

    synchronized String prankLevel() {
        String l = state.prankLevel;
        return l != null ? l : cfg.get().persona.prank.level;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(mode(), PrankPolicy.Level.parse(prankLevel()));
    }

    synchronized List<String> names() {
        return personas.available();
    }

    synchronized String info() {
        StringBuilder sb = new StringBuilder("预设模式：").append(Personas.label(mode())).append("（").append(mode()).append("）");
        if (Personas.PRANK.equals(mode())) sb.append("，恶作剧档位：").append(PrankPolicy.Level.parse(prankLevel()).label);
        sb.append("\n可用：");
        List<String> names = new ArrayList<>();
        for (String m : personas.available()) names.add(m + "（" + Personas.label(m) + "）");
        sb.append(String.join("、", names));
        sb.append("\n提示词文件：config/aiadmin/prompts/modes/<模式>.md，放新文件即可增加模式；/aibot mode reset 回到设置文件的 ").append(cfg.get().persona.mode);
        return sb.toString();
    }

    /** 切换预设模式（记住，重启后仍有效）；reset = 回到设置文件的值。 */
    synchronized String setMode(String mode) {
        String m = mode.toLowerCase(Locale.ROOT);
        if (m.equals("reset")) {
            state.mode = null;
        } else {
            if (!personas.exists(m)) return "没有这个模式：" + mode + "（可用：" + String.join(" / ", personas.available()) + "）";
            state.mode = m;
        }
        String err = state.save();
        log.info("agent", "persona.mode", "预设模式改为 " + mode(), null, JsonlLog.data("mode", mode()));
        return "预设模式已改为 " + Personas.label(mode()) + (err == null ? "" : "（保存失败：" + err + "，重启后会恢复）")
                + (Personas.PRANK.equals(mode()) ? "；恶作剧档位：" + PrankPolicy.Level.parse(prankLevel()).label : "");
    }

    /** 切换恶作剧档位（记住）；reset = 回到设置文件的值。 */
    synchronized String setPrankLevel(String level) {
        String l = level.toLowerCase(Locale.ROOT);
        if (l.equals("reset")) state.prankLevel = null;
        else if (PrankPolicy.Level.valid(l)) state.prankLevel = l;
        else return "档位只能是 chat_only（只动嘴）/ harmless（无害整蛊）/ rough（较狠）/ reset";
        String err = state.save();
        log.info("agent", "persona.prank_level", "恶作剧档位改为 " + prankLevel(), null, JsonlLog.data("level", prankLevel()));
        return "恶作剧档位已改为 " + PrankPolicy.Level.parse(prankLevel()).label
                + (Personas.PRANK.equals(mode()) ? "" : "（当前不是恶作剧模式，切到 /aibot mode prank 后生效）")
                + (err == null ? "" : "（保存失败：" + err + "，重启后会恢复）");
    }

    /** 话量：切换过就用切换后的，否则用 config.json 的 social.chattiness。 */
    synchronized String chattiness() {
        String t = state.talk;
        return t != null && TALK_LEVELS.contains(t) ? t : cfg.get().social.chattiness;
    }

    /** 切换话量（记住，重启后仍有效）；reset = 回到设置文件的值。 */
    synchronized String setChattiness(String level) {
        String l = level == null ? "" : level.toLowerCase(Locale.ROOT);
        if (l.equals("reset")) state.talk = null;
        else if (TALK_LEVELS.contains(l)) state.talk = l;
        else return "话量只能是 quiet（安静）/ normal（正常）/ chatty（话痨）/ reset";
        String err = state.save();
        log.info("agent", "persona.talk", "话量改为 " + chattiness(), null, JsonlLog.data("talk", chattiness()));
        return "话量已改为 " + talkLabel(chattiness()) + (err == null ? "" : "（保存失败：" + err + "，重启后会恢复）");
    }

    static String talkLabel(String t) {
        return switch (t) {
            case "quiet" -> "安静";
            case "chatty" -> "话痨";
            default -> "正常";
        };
    }

    /** 恶作剧免疫名单：和执行前检查（ActionTools）同一份。不拿本对象的锁（PlayerPrefs 有自己的锁）。 */
    Set<String> immune(BotConfig c) {
        return PlayerPrefs.prankImmune(c, prefs);
    }

    /** 预设模式的提示词段（本地运行和云端开工说明共用）。读模式文件在调用线程上做，不持本对象的锁。 */
    String prompt(BotConfig c, Snapshot s) {
        return personas.prompt(s.mode(), c.botName, s.level(), immune(c), c.persona.prank.maxSummonsPerRun);
    }
}
