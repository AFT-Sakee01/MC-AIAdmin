package io.github.aftsakee.aiadmin.agent;

import io.github.aftsakee.aiadmin.policy.PrankPolicy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 预设模式（机器人的说话风格）：全服同一时间只有一个，管理员用 /aibot mode 切换。
 * <ul>
 *   <li>每个模式一份提示词文件 config/aiadmin/prompts/modes/&lt;模式&gt;.md，缺失时按内置默认生成、已存在绝不覆盖；
 *       服主可以改内置模式的文字，也可以放新的 .md 文件来增加自己的模式（文件名就是模式名）。</li>
 *   <li>normal = 不加任何风格提示（不需要文件）。</li>
 *   <li>模式提示词接在系统提示词后面，只影响说话方式；恶作剧模式另外附上当前档位允许的手段（{@code {prank_rules}}），
 *       真正的限制由 {@link PrankPolicy} 在执行指令前强制检查。</li>
 * </ul>
 */
public final class Personas {
    public static final String NORMAL = "normal";
    public static final String PRANK = "prank";
    /** 模式名只能是小写字母、数字、下划线、短横线：直接拼成文件名，必须防路径穿越。 */
    private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("[a-z0-9_-]{1,32}");

    /** 内置模式 → 显示名。 */
    public static final Map<String, String> BUILTIN_LABELS = new LinkedHashMap<>();
    static final Map<String, String> BUILTIN_TEXT = new LinkedHashMap<>();

    static {
        BUILTIN_LABELS.put(NORMAL, "普通");
        BUILTIN_LABELS.put("helper", "帮助模式");
        BUILTIN_LABELS.put("roast", "吐槽模式");
        BUILTIN_LABELS.put(PRANK, "恶作剧模式");
        BUILTIN_TEXT.put("helper", """
                <!-- 帮助模式的提示词，/aibot mode helper 时接在系统提示词后面。可以随意修改；删除后按默认内容重新生成。{bot_name} = 机器人名字。 -->
                你现在是热心的新手向导。
                - 玩家问怎么玩、怎么合成、某个机制、模组物品的用法时，耐心、具体地回答：给步骤、材料和数量，必要时分两三条消息说完；
                - 不确定的东西先用查询工具（服务器上的模组列表、物品、日志等）确认，不要编造合成表；
                - 对新手多一点鼓励，提醒常见的坑（夜里别乱跑、记得睡觉设重生点、别在岩浆上挖）；
                - 语气亲切，不说教，不嘲笑任何问题。
                """);
        BUILTIN_TEXT.put("roast", """
                <!-- 吐槽模式的提示词，/aibot mode roast 时接在系统提示词后面。可以随意修改；删除后按默认内容重新生成。{bot_name} = 机器人名字。 -->
                你现在是毒舌但心软的吐槽役。
                - 回复时先用一句俏皮的吐槽调侃对方的操作、提问、死法或运气，再给出真正有用的回答（被问正事时答案不能少）；
                - 吐槽对事不对人：可以笑操作和游戏里的事，不涉及外貌、性别、民族、地域、家庭和现实生活，不说脏话，不揭别人隐私；
                - 对方明显不高兴、或者在求助紧急问题（掉线、丢东西、被偷）时，收起吐槽认真帮忙；
                - 简短有梗，每条一两句。
                """);
        BUILTIN_TEXT.put(PRANK, """
                <!-- 恶作剧模式的提示词，/aibot mode prank 时接在系统提示词后面。可以随意修改；删除后按默认内容重新生成。
                     {prank_rules} 会替换成当前档位（/aibot prank chat_only|harmless|rough）允许的手段，请保留这一行。 -->
                你现在是爱恶作剧的捣蛋鬼，但不坏。
                - 玩家找你说话时，可以先正常回应，再顺手整蛊一下说话的人或旁边的玩家；不是每次都要整，大约三次里整一次，别让人烦；
                - 整蛊要好笑、无害、很快结束，整完用一句话让对方知道是玩笑（例如"嘿嘿，刚才的苦力怕声是我"）；
                - 玩家认真求助、心情不好或者说"别整我"时，停止整他，好好帮忙；
                - 不能用恶作剧当借口做管理操作，也不能透露别人的坐标、背包。

                {prank_rules}
                """);
    }

    private final Path dir;

    /** configDir = config/aiadmin/。 */
    public Personas(Path configDir) {
        this.dir = configDir.resolve("prompts").resolve("modes");
    }

    public static boolean validName(String mode) {
        return mode != null && NAME.matcher(mode).matches();
    }

    public Path file(String mode) {
        return dir.resolve(mode + ".md");
    }

    /** 补齐内置模式缺失的文件，返回新生成的文件。 */
    public List<Path> ensure() throws IOException {
        List<Path> created = new ArrayList<>();
        for (Map.Entry<String, String> e : BUILTIN_TEXT.entrySet()) {
            Path f = file(e.getKey());
            if (Files.exists(f)) continue;
            Files.createDirectories(dir);
            Files.writeString(f, e.getValue(), StandardCharsets.UTF_8);
            created.add(f);
        }
        return created;
    }

    /** 可用的模式：内置的 + 目录里服主自己加的 .md（按名字排序）。 */
    public List<String> available() {
        List<String> out = new ArrayList<>(BUILTIN_LABELS.keySet());
        List<String> extra = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.md")) {
                for (Path f : ds) {
                    String n = f.getFileName().toString();
                    n = n.substring(0, n.length() - 3).toLowerCase(Locale.ROOT);
                    if (validName(n) && !out.contains(n)) extra.add(n);
                }
            } catch (IOException ignored) {
                // 列不出目录时只给内置模式
            }
        }
        extra.sort(null);
        out.addAll(extra);
        return out;
    }

    public boolean exists(String mode) {
        return validName(mode) && (BUILTIN_LABELS.containsKey(mode) || Files.isRegularFile(file(mode)));
    }

    public static String label(String mode) {
        return BUILTIN_LABELS.getOrDefault(mode, mode);
    }

    /**
     * 当前模式要接到系统提示词后面的文字；normal、模式不存在或文件内容为空时返回空串。
     * 内置模式的文件读不了时用内置默认内容。
     */
    public String prompt(String mode, String botName, PrankPolicy.Level level, Set<String> immune, int maxSummons) {
        if (mode == null || mode.equals(NORMAL) || !validName(mode)) return "";
        String text;
        try {
            text = Files.readString(file(mode), StandardCharsets.UTF_8);
            if (!text.isEmpty() && text.charAt(0) == '﻿') text = text.substring(1);
        } catch (IOException e) {
            text = BUILTIN_TEXT.getOrDefault(mode, "");
        }
        text = Prompts.strip(text);
        if (text.isBlank()) return "";
        String rules = PrankPolicy.describe(level, immune, maxSummons);
        if (mode.equals(PRANK) && !text.contains("{prank_rules}")) text += "\n\n{prank_rules}"; // 服主删了占位符也要让模型知道边界
        text = text.replace("{prank_rules}", mode.equals(PRANK) ? rules : "").replace("{bot_name}", botName == null ? "" : botName).trim();
        return "当前预设模式：" + label(mode) + "（只改变你在聊天里说话的语气和方式，上面的管理原则、隐私和安全要求照旧）：\n" + text;
    }
}
