package io.github.aftsakee.aiadmin.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.aftsakee.aiadmin.llm.Llm.ToolSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 工具注册表 + JSON Schema 小构造器。 */
public final class Tools {
    @FunctionalInterface
    public interface Handler {
        /** 返回给模型的文本（通常是 JSON）。抛异常 = 工具出错（会作为错误结果回给模型）。 */
        String call(JsonObject args, RunContext ctx) throws Exception;
    }

    public record Tool(ToolSpec spec, Handler handler) {}

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public Tools add(ToolSpec spec, Handler h) {
        tools.put(spec.name(), new Tool(spec, h));
        return this;
    }

    public Tool get(String name) {
        return name == null ? null : tools.get(name);
    }

    public List<ToolSpec> specs() {
        return specs(java.util.Set.of());
    }

    /** 提供给模型的工具定义（去掉被规则隐藏的）。 */
    public List<ToolSpec> specs(java.util.Set<String> hidden) {
        List<ToolSpec> out = new ArrayList<>();
        for (Tool t : tools.values()) if (!hidden.contains(t.spec().name())) out.add(t.spec());
        return out;
    }

    /** schema("说明").str("command", "要执行的指令", true).build() */
    public static Schema schema(String name, String description) {
        return new Schema(name, description);
    }

    public static final class Schema {
        private final String name;
        private final String description;
        private final JsonObject props = new JsonObject();
        private final JsonArray required = new JsonArray();

        Schema(String name, String description) {
            this.name = name;
            this.description = description;
        }

        public Schema str(String key, String desc, boolean req) {
            return prop(key, "string", desc, req);
        }

        public Schema integer(String key, String desc, boolean req) {
            return prop(key, "integer", desc, req);
        }

        public Schema bool(String key, String desc, boolean req) {
            return prop(key, "boolean", desc, req);
        }

        public Schema strEnum(String key, String desc, boolean req, String... values) {
            prop(key, "string", desc, req);
            JsonArray e = new JsonArray();
            for (String v : values) e.add(v);
            props.getAsJsonObject(key).add("enum", e);
            return this;
        }

        private Schema prop(String key, String type, String desc, boolean req) {
            JsonObject p = new JsonObject();
            p.addProperty("type", type);
            p.addProperty("description", desc);
            props.add(key, p);
            if (req) required.add(key);
            return this;
        }

        public ToolSpec build() {
            JsonObject s = new JsonObject();
            s.addProperty("type", "object");
            s.add("properties", props);
            s.add("required", required);
            return new ToolSpec(name, description, s);
        }
    }

    public static String optStr(JsonObject a, String k, String def) {
        return a.has(k) && !a.get(k).isJsonNull() ? a.get(k).getAsString() : def;
    }

    public static int optInt(JsonObject a, String k, int def, int lo, int hi) {
        try {
            int v = a.has(k) && !a.get(k).isJsonNull() ? a.get(k).getAsInt() : def;
            return Math.max(lo, Math.min(hi, v));
        } catch (RuntimeException e) {
            return def;
        }
    }

    public static boolean optBool(JsonObject a, String k, boolean def) {
        try {
            return a.has(k) && !a.get(k).isJsonNull() ? a.get(k).getAsBoolean() : def;
        } catch (RuntimeException e) {
            return def;
        }
    }
}
