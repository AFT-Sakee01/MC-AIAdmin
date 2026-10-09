package io.github.aftsakee.aiadmin.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 从 Gson 树里按类型取字段的小工具（游戏资料解析、目标表共用）。
 * <p>约定：类型不对一律当"没有这个字段"返回默认值/空容器，不抛异常；需要把类型错误报给服主的调用方（如 GoalTable）
 * 先用 {@link #isBool}/{@link #isNumber}/{@link #isString} 判断再取。返回的空 JsonArray/JsonObject 是新建的，改了不影响原树。
 */
public final class Json {
    private Json() {}

    /** o[k] 是数组就返回它，否则返回新的空数组。 */
    public static JsonArray arr(JsonObject o, String k) {
        JsonElement v = o == null ? null : o.get(k);
        return v != null && v.isJsonArray() ? v.getAsJsonArray() : new JsonArray();
    }

    /** o[k] 是对象就返回它，否则返回新的空对象。 */
    public static JsonObject obj(JsonObject o, String k) {
        JsonElement v = o == null ? null : o.get(k);
        return v != null && v.isJsonObject() ? v.getAsJsonObject() : new JsonObject();
    }

    /** 字符串值；不是字符串（数字、布尔、对象、null）返回 null。 */
    public static String str(JsonElement e) {
        return isString(e) ? e.getAsString() : null;
    }

    /** o[k] 的字符串值；没有或不是字符串返回 null。 */
    public static String str(JsonObject o, String k) {
        return o == null ? null : str(o.get(k));
    }

    /**
     * o[k] 的整数值；没有或不是数字返回 def。小数向零截断；超出 int 范围时饱和到 int 上下限
     * （原因：Gson 的 getAsInt 对 1e20 这类值会返回截掉高位的随机数，饱和至少方向正确）。
     */
    public static int intOf(JsonObject o, String k, int def) {
        JsonElement v = o == null ? null : o.get(k);
        if (!isNumber(v)) return def;
        try {
            double d = v.getAsDouble();
            if (d >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
            if (d <= Integer.MIN_VALUE) return Integer.MIN_VALUE;
            return (int) d;
        } catch (RuntimeException ex) {
            return def;
        }
    }

    /** 数字值；不是数字返回 def。 */
    public static double dbl(JsonElement v, double def) {
        if (!isNumber(v)) return def;
        try {
            return v.getAsDouble();
        } catch (RuntimeException ex) {
            return def;
        }
    }

    /** o[k] 的布尔值；没有或不是布尔（包括字符串 "false"）返回 def。 */
    public static boolean bool(JsonObject o, String k, boolean def) {
        JsonElement v = o == null ? null : o.get(k);
        return isBool(v) ? v.getAsBoolean() : def;
    }

    public static boolean isString(JsonElement v) {
        return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString();
    }

    public static boolean isNumber(JsonElement v) {
        return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber();
    }

    public static boolean isBool(JsonElement v) {
        return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean();
    }

    /** "minecraft:item" → "item"；没有命名空间原样返回；null 返回 null。 */
    public static String path(String id) {
        if (id == null) return null;
        int i = id.indexOf(':');
        return i < 0 ? id : id.substring(i + 1);
    }
}
