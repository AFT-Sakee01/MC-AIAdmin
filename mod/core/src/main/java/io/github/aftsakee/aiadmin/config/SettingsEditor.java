package io.github.aftsakee.aiadmin.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 云端 get_settings / set_setting：按服主在 remote.settable_settings 里列的白名单读改 config.json。
 * <p>
 * 边界：
 * <ul>
 *   <li>只能改白名单里的键，而且下面这些<b>永远</b>改不了（白名单写了也不行）：接入点和密钥、远程接口本身、预设模式与恶作剧、
 *       上下文压缩（提示词文件、总结用的接入点）、名字里带 key/token/secret/password 的键，以及<b>会原样进提示词的文字</b>
 *       （bot_name、巡检额外事项、名字里带 prompt/instruction/file 的键）——防止云端被提示注入后给自己加权限、换接入点，
 *       或者把一段指令永久写进以后每次本地运行的提示词（等于绕过玩家聊天触发的所有限制）；</li>
 *   <li>只能改默认设置里已有的键，值的类型必须和原来一致（布尔 / 数字 / 字符串 / 字符串列表），防止写错键名默默不生效；</li>
 *   <li>写之前先把新内容放到同目录的临时 pending 文件用正式的读取校验跑一遍，不通过就不动原文件；
 *       原文件备份成 config.json.bak-setting（最近一次修改前），第一次被云端修改前的原样另存 config.json.bak-setting-original（之后不再覆盖）；
 *       用同目录临时文件 + 原子改名写入，保留原文件的属主和权限。</li>
 *   <li>并发：整个"读-改-校验-写"在一把类级锁里串行（远程接口是多线程的，两个 set_setting 同时进来会互相覆盖改动）。</li>
 *   <li>已知限制：写回是按 JSON 重新生成的，服主在 config.json 里手写的 // 注释会丢失（原样留在 bak-setting-original 里）。</li>
 * </ul>
 * 改动是永久的（写进文件），调用方随后触发 reload 生效。
 */
public final class SettingsEditor {
    static final List<String> DENY_PREFIXES = List.of("providers", "active_provider", "fallback_providers", "remote", "persona",
            "compression", "bot_name", "patrol.extra_instructions", "schema_version");
    /** 某一段键名是（或以 _ 结尾为）key / token / secret / password：api_key、remote_token 算，chat.keywords 不算。 */
    static final Pattern SECRET = Pattern.compile("(?i)(^|[._])([a-z0-9]*_)?(key|token|secret|password|passwd)s?($|\\.)");
    /** 最后一段键名里带 prompt / instruction / file：这类文字会进提示词或指向提示词文件。 */
    static final Pattern PROMPT_TEXT = Pattern.compile("(?i)(^|\\.)[a-z0-9_]*(prompt|instruction|file)[a-z0-9_]*$");
    static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*){0,4}");
    private static final Object LOCK = new Object();

    private SettingsEditor() {}

    /** 白名单里的键能不能改：能改返回 null，否则返回原因。 */
    static String denied(String key, List<String> allowed) {
        if (key == null || !KEY.matcher(key).matches()) return "键名格式不对（用点分隔的 snake_case，例如 patrol.interval_minutes）：" + key;
        for (String p : DENY_PREFIXES) {
            if (key.equals(p) || key.startsWith(p + ".")) return key + " 不能由云端修改（接入点、远程接口、预设模式与恶作剧、压缩、会进提示词的文字只能服主改）";
        }
        if (SECRET.matcher(key).find()) return key + " 看起来是密钥类设置，不能由云端修改";
        if (PROMPT_TEXT.matcher(key).find()) return key + " 会进提示词或指向提示词文件，不能由云端修改";
        if (!allowed.contains(key)) return key + " 不在服主允许云端修改的列表（remote.settable_settings）里";
        return null;
    }

    private static JsonElement at(JsonObject root, String key) {
        JsonElement cur = root;
        for (String part : key.split("\\.")) {
            if (cur == null || !cur.isJsonObject()) return null;
            cur = cur.getAsJsonObject().get(part);
        }
        return cur;
    }

    /** 当前生效的值（按内存里的设置，含默认值）。白名单里被硬性禁止或不存在的键不列出。 */
    public static Map<String, JsonElement> current(BotConfig c, List<String> allowed) {
        JsonObject tree = ConfigStore.GSON.toJsonTree(c).getAsJsonObject();
        Map<String, JsonElement> out = new LinkedHashMap<>();
        for (String k : allowed) {
            if (denied(k, allowed) != null) continue;
            JsonElement v = at(tree, k);
            if (v != null) out.put(k, v);
        }
        return out;
    }

    /** 把用户给的值按旧值的类型解析：布尔、数字、字符串、字符串列表（JSON 数组或逗号分隔）。类型不对抛 IllegalArgumentException。 */
    static JsonElement coerce(JsonElement like, String raw) {
        String t = raw == null ? "" : raw.trim();
        if (like.isJsonPrimitive() && like.getAsJsonPrimitive().isBoolean()) {
            String l = t.toLowerCase(Locale.ROOT);
            if (l.equals("true") || l.equals("false")) return new JsonPrimitive(Boolean.parseBoolean(l));
            throw new IllegalArgumentException("要填 true 或 false");
        }
        if (like.isJsonPrimitive() && like.getAsJsonPrimitive().isNumber()) {
            try {
                double d = Double.parseDouble(t);
                if (Double.isNaN(d) || Double.isInfinite(d)) throw new NumberFormatException();
                return d == Math.rint(d) && Math.abs(d) < 1e15 ? new JsonPrimitive((long) d) : new JsonPrimitive(d);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("要填数字");
            }
        }
        if (like.isJsonPrimitive()) {
            if (t.length() > 2000) throw new IllegalArgumentException("文字太长（最多 2000 字）");
            return new JsonPrimitive(raw == null ? "" : raw);
        }
        if (like.isJsonArray()) {
            com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
            if (t.startsWith("[")) {
                JsonElement e;
                try {
                    e = JsonParser.parseString(t);
                } catch (JsonParseException ex) {
                    throw new IllegalArgumentException("列表要写成 JSON 数组，例如 [\"a\",\"b\"]");
                }
                if (!e.isJsonArray()) throw new IllegalArgumentException("要填列表");
                for (JsonElement x : e.getAsJsonArray()) {
                    if (!x.isJsonPrimitive()) throw new IllegalArgumentException("列表里只能是文字");
                    arr.add(x.getAsString());
                }
            } else if (!t.isEmpty()) {
                for (String p : t.split("[,，]")) if (!p.isBlank()) arr.add(p.trim());
            }
            if (arr.size() > 100) throw new IllegalArgumentException("列表太长（最多 100 项）");
            return arr;
        }
        throw new IllegalArgumentException("这个设置不是简单值（对象），不能这样改");
    }

    public record Change(String key, JsonElement before, JsonElement after) {}

    /** 改 config.json 里的一个键。设置文件不合法时抛 ConfigException，原文件不变。 */
    public static Change set(Path configFile, List<String> allowed, String key, String rawValue) throws ConfigStore.ConfigException, IOException {
        String why = denied(key, allowed);
        if (why != null) throw new IllegalArgumentException(why);
        JsonElement like = at(ConfigStore.GSON.toJsonTree(new BotConfig()).getAsJsonObject(), key);
        if (like == null) throw new IllegalArgumentException("默认设置里没有 " + key + "（键名写错了？）");
        JsonElement value = coerce(like, rawValue);
        synchronized (LOCK) {
            return write(configFile, key, value);
        }
    }

    private static Change write(Path configFile, String key, JsonElement value) throws ConfigStore.ConfigException, IOException {
        String text = Files.readString(configFile, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '﻿') text = text.substring(1);
        JsonObject root;
        try {
            root = JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new ConfigStore.ConfigException("config.json 现在就有格式错误，先请服主修好：" + e.getMessage());
        }
        JsonElement before = at(ConfigStore.GSON.toJsonTree(ConfigStore.GSON.fromJson(root, BotConfig.class)).getAsJsonObject(), key);
        String[] parts = key.split("\\.");
        JsonObject o = root;
        for (int i = 0; i < parts.length - 1; i++) {
            JsonElement next = o.get(parts[i]);
            if (next == null || !next.isJsonObject()) {
                next = new JsonObject();
                o.add(parts[i], next);
            }
            o = next.getAsJsonObject();
        }
        o.add(parts[parts.length - 1], value);
        String out = ConfigStore.GSON.toJson(root) + "\n";

        Path dir = configFile.toAbsolutePath().getParent();
        // 唯一文件名：loadConfig 遇到不存在的文件会生成默认设置并"校验通过"，固定文件名被别人删掉时等于没校验
        Path pending = Files.createTempFile(dir, "config.json.", ".pending");
        try {
            Files.writeString(pending, out, StandardCharsets.UTF_8);
            ConfigStore.loadConfig(pending); // 正式读取 + 校验；不通过直接抛出，原文件不动
        } finally {
            Files.deleteIfExists(pending);
        }
        Path original = dir.resolve("config.json.bak-setting-original");
        if (!Files.exists(original)) Files.copy(configFile, original, StandardCopyOption.COPY_ATTRIBUTES); // 第一次被云端改之前的原样，含服主的注释
        Files.copy(configFile, dir.resolve("config.json.bak-setting"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        Path tmp = dir.resolve(".config.json." + Long.toHexString(System.nanoTime()) + ".tmp");
        try {
            Files.writeString(tmp, out, StandardCharsets.UTF_8);
            copyOwnership(configFile, tmp);
            Files.move(tmp, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
        return new Change(key, before, value);
    }

    /** 新文件沿用原文件的权限和属组（Linux 上 config.json 是 660 crafty:mcshared，服主也要能改）；不支持 POSIX 的系统跳过。 */
    private static void copyOwnership(Path from, Path to) {
        try {
            PosixFileAttributeView src = Files.getFileAttributeView(from, PosixFileAttributeView.class);
            PosixFileAttributeView dst = Files.getFileAttributeView(to, PosixFileAttributeView.class);
            if (src == null || dst == null) return;
            var a = src.readAttributes();
            dst.setPermissions(a.permissions());
            try {
                dst.setGroup(a.group());
            } catch (IOException ignored) {
                // 进程不属于原属组时改不了组，保留默认组
            }
        } catch (IOException | UnsupportedOperationException ignored) {
            // 拿不到属性就用默认权限
        }
    }
}
