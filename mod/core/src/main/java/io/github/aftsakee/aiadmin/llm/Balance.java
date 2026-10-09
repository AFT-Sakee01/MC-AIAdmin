package io.github.aftsakee.aiadmin.llm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.aftsakee.aiadmin.config.ProviderConfig;

import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 查询接入点账户余额（按钱计费的服务）。
 * <ul>
 *   <li>deepseek：GET https://api.deepseek.com/user/balance → balance_infos[].total_balance（字符串，按 currency 选；没配 currency 取第一项）、is_available。</li>
 *   <li>newapi：one-api / new-api 类中转兼容的 OpenAI 旧账单接口：GET /v1/dashboard/billing/subscription 的 hard_limit_usd
 *       减去 GET /v1/dashboard/billing/usage 的 total_usage（单位：美分）。</li>
 * </ul>
 * 只读查询，失败不重试（下次定时再查）；密钥只放在请求头里，不进日志。
 */
public final class Balance {
    public record Info(String provider, String api, Double amount, String currency, Boolean available, String error, long checkedAt) {
        public String describe() {
            if (error != null) return provider + "：查询失败（" + error + "）";
            String s = provider + "：余额 " + (amount == null ? "未知" : String.format(java.util.Locale.ROOT, "%.2f", amount)) + " " + currency;
            return Boolean.FALSE.equals(available) ? s + "（服务商标记为不可用）" : s;
        }
    }

    private Balance() {}

    public static String api(ProviderConfig p) {
        if (!"auto".equals(p.balanceApi)) return p.balanceApi;
        return p.baseUrl != null && p.baseUrl.contains("api.deepseek.com") ? "deepseek" : "none";
    }

    public static Info query(String name, ProviderConfig p, HttpTransport http) {
        String api = api(p);
        long now = System.currentTimeMillis();
        String key = p.resolveKey();
        if (api.equals("none")) return new Info(name, api, null, "", null, "这个接入点没有余额接口", now);
        if (key.isEmpty()) return new Info(name, api, null, "", null, "没有配置 API key", now);
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + key);
        h.put("Accept", "application/json");
        h.putAll(p.extraHeaders);
        try {
            if (api.equals("deepseek")) {
                String base = trim(p.baseUrl);
                if (base.endsWith("/v1")) base = base.substring(0, base.length() - 3);
                JsonObject o = get(http, base + "/user/balance", h);
                Boolean avail = o.has("is_available") ? o.get("is_available").getAsBoolean() : null;
                JsonObject pick = null;
                for (JsonElement e : BaseSession.arr(o, "balance_infos")) {
                    if (!e.isJsonObject()) continue;
                    JsonObject bi = e.getAsJsonObject();
                    if (pick == null || (!p.currency.isBlank() && p.currency.equalsIgnoreCase(BaseSession.str(bi, "currency")))) pick = bi;
                }
                if (pick == null) return new Info(name, api, null, "", avail, "回复里没有 balance_infos", now);
                return new Info(name, api, Double.parseDouble(BaseSession.str(pick, "total_balance")), BaseSession.str(pick, "currency"), avail, null, now);
            }
            // newapi
            String base = BaseSession.join(trim(p.baseUrl), "/v1", "");
            JsonObject sub = get(http, base + "/dashboard/billing/subscription", h);
            if (!sub.has("hard_limit_usd")) throw new IllegalStateException("订阅接口回复里没有 hard_limit_usd");
            double limit = sub.get("hard_limit_usd").getAsDouble();
            LocalDate today = LocalDate.now();
            JsonObject usage = get(http, base + "/dashboard/billing/usage?start_date=" + today.minusDays(99) + "&end_date=" + today.plusDays(1), h);
            // 缺 total_usage 时以前按 0 算：余额被高估，停用线永远不会触发。现在当作查询失败（不改变停用状态）
            if (!usage.has("total_usage")) throw new IllegalStateException("用量接口回复里没有 total_usage");
            double used = usage.get("total_usage").getAsDouble() / 100.0;
            return new Info(name, api, limit - used, "USD", null, null, now);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new Info(name, api, null, "", null, BaseSession.snippet(msg), now);
        }
    }

    private static JsonObject get(HttpTransport http, String url, Map<String, String> h) throws Exception {
        HttpTransport.Response r = http.send("GET", url, h, null, Duration.ofSeconds(20));
        if (r.status() / 100 != 2) throw new IllegalStateException("HTTP " + r.status() + " " + BaseSession.snippet(r.body()));
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private static String trim(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
