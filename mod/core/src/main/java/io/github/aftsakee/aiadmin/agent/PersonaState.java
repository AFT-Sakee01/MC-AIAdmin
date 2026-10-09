package io.github.aftsakee.aiadmin.agent;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import io.github.aftsakee.aiadmin.util.JsonFiles;

/**
 * /aibot mode、/aibot prank 切换后的值，存在状态目录的 persona.json 里，重启后仍然有效；
 * 为 null 时用 config.json 的 persona.mode / persona.prank.level。
 * 故意不回写 config.json：那里有 API 密钥和服主手写的格式，程序重写整份文件风险太大。
 * 线程：由 AgentService 在自己的锁里调用。写入用临时文件 + 原子替换，写失败只影响"重启后是否记得"。
 */
final class PersonaState {
    private static final Gson GSON = new Gson();
    private final Path file;
    String mode;
    String prankLevel;
    /** 话量（/aibot talk）：quiet / normal / chatty；null = 用 config.json 的 social.chattiness。 */
    String talk;

    /** 文件损坏就当没有切换过，回到设置文件的值；坏文件改名隔离（见 JsonFiles）。 */
    PersonaState(Path file) {
        this.file = file;
        JsonFiles.Load l = JsonFiles.load(file);
        if (!l.ok() || l.json() == null) return;
        try {
            JsonObject o = l.json().getAsJsonObject();
            mode = o.has("mode") && !o.get("mode").isJsonNull() ? o.get("mode").getAsString() : null;
            prankLevel = o.has("prank_level") && !o.get("prank_level").isJsonNull() ? o.get("prank_level").getAsString() : null;
            talk = o.has("talk") && !o.get("talk").isJsonNull() ? o.get("talk").getAsString() : null;
        } catch (RuntimeException e) {
            mode = prankLevel = talk = null;
            JsonFiles.quarantine(file, e.getMessage());
        }
    }

    /** 保存；失败返回原因（调用方提示管理员"重启后会恢复"），成功返回 null。 */
    String save() {
        JsonObject o = new JsonObject();
        o.addProperty("mode", mode);
        o.addProperty("prank_level", prankLevel);
        o.addProperty("talk", talk);
        return JsonFiles.writeQuietly(file, GSON.toJson(o));
    }
}
