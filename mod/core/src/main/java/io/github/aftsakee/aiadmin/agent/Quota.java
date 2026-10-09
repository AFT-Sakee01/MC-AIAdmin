package io.github.aftsakee.aiadmin.agent;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.github.aftsakee.aiadmin.config.Rules;
import io.github.aftsakee.aiadmin.util.JsonFiles;

/**
 * 额度账本：按自然时段（本地时区的小时/天/周(周一起)/月）累计运行次数、token、估算花费。
 * 存在 &lt;游戏目录&gt;/aiadmin/quota.json，重启不清零；只保留当前时段的数据。
 * <p>计数时机：运行开始时 +1 次（这样排队中的任务也不会超额），结束时加 token 和花费——所以 token/花费上限可能被最后一次运行略微超出。
 * 所有方法加锁：代理线程记账，服务端主线程（/aibot quota）读。
 */
public final class Quota {
    static final String[] PERIODS = {"hour", "day", "week", "month"};
    private static final Gson GSON = new Gson();

    static final class Bucket {
        long runs;
        long tokens;
        double cost;
    }

    private static final class State {
        Map<String, Bucket> buckets = new LinkedHashMap<>();
        Set<String> alerted = new LinkedHashSet<>();
    }

    private final Path file;
    private State st = new State();

    private String loadError;

    /**
     * 账本坏了从零开始（宁可多算一点，也不让机器人起不来），但不再悄悄进行：坏文件改名隔离、loadError 记下原因，
     * AgentService 启动时写警告并提醒管理员——否则额度/花费上限等于被悄悄清零，坏文件这个证据也会被下次保存覆盖。
     */
    public Quota(Path file) {
        this.file = file;
        JsonFiles.Load l = JsonFiles.load(file);
        if (!l.ok()) {
            loadError = "额度账本 " + l.error() + "：本时段的次数/token/花费从 0 重新计";
            return;
        }
        if (l.json() == null) return;
        try {
            State s = GSON.fromJson(l.json(), State.class);
            if (s != null && s.buckets != null && s.alerted != null) st = s;
            else loadError = "额度账本 " + JsonFiles.quarantine(file, "字段不全") + "：本时段的次数/token/花费从 0 重新计";
        } catch (RuntimeException e) {
            loadError = "额度账本 " + JsonFiles.quarantine(file, e.getMessage()) + "：本时段的次数/token/花费从 0 重新计";
        }
    }

    public synchronized String loadError() {
        return loadError;
    }

    static String key(String period, LocalDateTime t) {
        return switch (period) {
            case "hour" -> "hour:" + t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH"));
            case "day" -> "day:" + t.toLocalDate();
            case "week" -> "week:" + t.get(IsoFields.WEEK_BASED_YEAR) + "-W" + t.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            case "month" -> "month:" + t.format(DateTimeFormatter.ofPattern("yyyy-MM"));
            default -> throw new IllegalArgumentException(period);
        };
    }

    static LocalDateTime resetAt(String period, LocalDateTime t) {
        return switch (period) {
            case "hour" -> t.truncatedTo(ChronoUnit.HOURS).plusHours(1);
            case "day" -> t.toLocalDate().plusDays(1).atStartOfDay();
            case "week" -> t.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atStartOfDay();
            case "month" -> t.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay();
            default -> throw new IllegalArgumentException(period);
        };
    }

    static String periodName(String period) {
        return switch (period) {
            case "hour" -> "本小时";
            case "day" -> "今天";
            case "week" -> "本周";
            default -> "本月";
        };
    }

    private Bucket bucket(String period, LocalDateTime now) {
        return st.buckets.computeIfAbsent(key(period, now), k -> new Bucket());
    }

    private static boolean counted(Rules.QuotaRules q, boolean admin) {
        return q.enabled && (!admin || q.countAdminRuns);
    }

    /** 额度用完时返回原因（给人看），否则 null。 */
    public synchronized String blocked(Rules.QuotaRules q, boolean admin, LocalDateTime now) {
        if (!counted(q, admin) || (admin && !q.blockAdminWhenExhausted)) return null;
        for (Rules.Window w : q.windows) {
            Bucket b = bucket(w.period, now);
            String dim = w.maxRuns > 0 && b.runs >= w.maxRuns ? "次数" : w.maxTokens > 0 && b.tokens >= w.maxTokens ? "token"
                    : w.maxCost > 0 && b.cost >= w.maxCost ? "花费" : null;
            if (dim != null) {
                return periodName(w.period) + "的" + dim + "额度已用完，" + resetAt(w.period, now).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")) + " 恢复";
            }
        }
        return null;
    }

    public synchronized void recordStart(Rules.QuotaRules q, boolean admin, LocalDateTime now) {
        if (!counted(q, admin)) return;
        for (String p : PERIODS) bucket(p, now).runs++;
        save(now);
    }

    public synchronized void recordUsage(Rules.QuotaRules q, boolean admin, long tokens, double cost, LocalDateTime now) {
        if (!counted(q, admin)) return;
        for (String p : PERIODS) {
            Bucket b = bucket(p, now);
            b.tokens += tokens;
            b.cost += cost;
        }
        save(now);
    }

    /** 新跨过"剩余不足 warn%"或"用完"阈值的项，每个时段每项只报一次。 */
    public synchronized List<String> newAlerts(Rules.QuotaRules q, LocalDateTime now) {
        List<String> out = new ArrayList<>();
        if (!q.enabled) return out;
        for (Rules.Window w : q.windows) {
            Bucket b = bucket(w.period, now);
            check(out, w.period, "次数", b.runs, w.maxRuns, q.warnAtPercent, now, "");
            check(out, w.period, "token", b.tokens, w.maxTokens, q.warnAtPercent, now, "");
            check(out, w.period, "花费", b.cost, w.maxCost, q.warnAtPercent, now, "");
        }
        if (!out.isEmpty()) save(now);
        return out;
    }

    private void check(List<String> out, String period, String dim, double used, double max, int warnPct, LocalDateTime now, String unit) {
        if (max <= 0) return;
        String k = key(period, now) + "|" + dim;
        if (used >= max) {
            if (st.alerted.add(k + "|out")) {
                out.add(periodName(period) + "的" + dim + "额度已用完（" + fmt(used) + "/" + fmt(max) + unit + "），自动运行暂停到 "
                        + resetAt(period, now).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")));
            }
        } else if ((max - used) * 100 <= warnPct * max && st.alerted.add(k + "|warn")) {
            out.add(periodName(period) + "的" + dim + "额度只剩 " + fmt(max - used) + "/" + fmt(max) + unit);
        }
    }

    public synchronized String report(Rules.QuotaRules q, LocalDateTime now) {
        if (!q.enabled) return "额度：未启用";
        if (q.windows.isEmpty()) return "额度：没有配置任何时段上限";
        StringBuilder sb = new StringBuilder();
        for (Rules.Window w : q.windows) {
            Bucket b = bucket(w.period, now);
            sb.append(periodName(w.period)).append("：");
            List<String> parts = new ArrayList<>();
            parts.add(part("次数", b.runs, w.maxRuns));
            parts.add(part("token", b.tokens, w.maxTokens));
            parts.add(part("花费", b.cost, w.maxCost));
            sb.append(String.join("，", parts)).append("（").append(resetAt(w.period, now).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")))
                    .append(" 重置）\n");
        }
        if (!q.countAdminRuns) sb.append("管理员提问不计入额度\n");
        return sb.toString().trim();
    }

    private static String part(String dim, double used, double max) {
        if (max <= 0) return dim + "已用 " + fmt(used) + "（不限）";
        return dim + "剩 " + fmt(Math.max(0, max - used)) + "/" + fmt(max);
    }

    static String fmt(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
        return String.format(Locale.ROOT, "%.4f", v).replaceAll("0+$", "");
    }

    private void save(LocalDateTime now) {
        // 只保留当前时段的桶和提醒标记
        Set<String> live = new LinkedHashSet<>();
        for (String p : PERIODS) live.add(key(p, now));
        st.buckets.keySet().removeIf(k -> !live.contains(k));
        st.alerted.removeIf(a -> !live.contains(a.substring(0, a.indexOf('|'))));
        JsonFiles.writeQuietly(file, GSON.toJson(st)); // 写不了只留在内存，下次再写（失败经 JsonFiles 上报日志）
    }
}
