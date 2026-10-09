package io.github.aftsakee.aiadmin.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.aftsakee.aiadmin.util.Ring;
import io.github.aftsakee.aiadmin.util.Text;

import java.util.List;
import java.util.Set;

/**
 * 最近的服务器动态（聊天、进出、死亡、成就、玩家指令、机器人自己的动作），给 get_recent_activity 工具用。
 * 只在内存里保留最近 600 条，重启清空。
 */
public final class Activity {
    public record Entry(long time, String kind, String who, String text) {}

    private final Ring<Entry> ring = new Ring<>(600);

    public void add(String kind, String who, String text) {
        ring.add(new Entry(System.currentTimeMillis(), kind, who, text));
    }

    public List<Entry> recent(int limit, Set<String> kinds) {
        return ring.recent(limit, e -> kinds == null || kinds.isEmpty() || kinds.contains(e.kind()));
    }

    public JsonArray toJson(int limit, Set<String> kinds) {
        JsonArray a = new JsonArray();
        for (Entry e : recent(limit, kinds)) {
            JsonObject o = new JsonObject();
            o.addProperty("time", Text.time(e.time()));
            o.addProperty("kind", e.kind());
            o.addProperty("who", e.who());
            o.addProperty("text", e.text());
            a.add(o);
        }
        return a;
    }
}
