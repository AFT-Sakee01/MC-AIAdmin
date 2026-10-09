package io.github.aftsakee.aiadmin.agent;

import io.github.aftsakee.aiadmin.config.BotConfig;
import io.github.aftsakee.aiadmin.config.ProviderConfig;
import io.github.aftsakee.aiadmin.config.Rules;
import io.github.aftsakee.aiadmin.llm.Balance;
import io.github.aftsakee.aiadmin.llm.HttpTransport;
import io.github.aftsakee.aiadmin.log.JsonlLog;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 接入点余额监控（从 AgentService 拆出，1.0.0.33）：定期 / 按需查余额，低于提醒线发提醒，低于停用线停用该接入点（有备用就用备用），
 * 充值后下次查询自动恢复。
 * <ul>
 *   <li>线程：查询是阻塞的网络请求（每个接入点最长 20 秒），放在专用的 aiadmin-balance 线程上——AgentService 的定时线程只有一个，
 *       还要负责聊天合并、事件合并、云端兜底和收回，被余额接口卡住时玩家要多等 20 秒×接入点数。上一次还没查完就不再排新的定期查询。</li>
 *   <li>锁：本对象自己的锁，只保护内存状态；持锁时从不回调 AgentService（要查哪些接入点、发提醒都在锁外做），
 *       所以 AgentService 持着自己的锁调用这里（锁顺序 服务 → 余额）不会死锁。</li>
 * </ul>
 */
final class BalanceMonitor {
    private final Supplier<BotConfig> cfg;
    private final Supplier<Rules> rules;
    private final HttpTransport http;
    private final JsonlLog log;
    /** 定期查询的接入点：当前 + 备用（由 AgentService 提供，在锁外调用）。 */
    private final Supplier<List<String>> watched;
    /** 发提醒（日志 + 在线 OP），在锁外调用。 */
    private final Consumer<List<String>> alerts;
    private final Map<String, Balance.Info> balances = new LinkedHashMap<>();
    private final Set<String> warned = new HashSet<>();
    private final Set<String> stopped = new HashSet<>();
    private long lastCheck;
    private ExecutorService exec;
    private final AtomicBoolean running = new AtomicBoolean();

    BalanceMonitor(Supplier<BotConfig> cfg, Supplier<Rules> rules, HttpTransport http, JsonlLog log,
                   Supplier<List<String>> watched, Consumer<List<String>> alerts) {
        this.cfg = cfg;
        this.rules = rules;
        this.http = http;
        this.log = log;
        this.watched = watched;
        this.alerts = alerts;
    }

    synchronized void start() {
        exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "aiadmin-balance");
            t.setDaemon(true);
            return t;
        });
    }

    synchronized void stop() {
        if (exec != null) exec.shutdownNow();
    }

    /** 到了定期查询的时候（rules.json quota.balance_check_minutes）。 */
    synchronized boolean due(Rules rl, long now) {
        return rl.quota.balanceCheckMinutes > 0 && now - lastCheck >= rl.quota.balanceCheckMinutes * 60_000L;
    }

    /** 这个接入点因余额不足被停用了。 */
    synchronized boolean stopped(String provider) {
        return stopped.contains(provider);
    }

    /** 在余额线程上查当前 + 备用接入点；上一次还没查完就跳过。 */
    void refreshAsync() {
        ExecutorService ex;
        synchronized (this) {
            ex = exec;
        }
        if (ex == null || ex.isShutdown() || !running.compareAndSet(false, true)) return;
        try {
            ex.execute(() -> {
                try {
                    refresh(false);
                } finally {
                    running.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            running.set(false);
        }
    }

    /**
     * 在调用线程上查询并处理阈值（网络请求在锁外）。all = 查所有配置了余额接口的接入点（/aibot quota 用）；否则只查当前 + 备用。
     */
    List<Balance.Info> refresh(boolean all) {
        BotConfig c = cfg.get();
        Rules rl = rules.get();
        List<String> names = new ArrayList<>(all ? c.providers.keySet() : watched.get()); // 锁外：watched 会拿 AgentService 的锁
        synchronized (this) {
            lastCheck = System.currentTimeMillis();
        }
        List<Balance.Info> out = new ArrayList<>();
        List<String> msgs = new ArrayList<>();
        for (String n : new LinkedHashSet<>(names)) {
            ProviderConfig p = c.providers.get(n);
            if (p == null || Balance.api(p).equals("none")) continue;
            Balance.Info info = Balance.query(n, p, http);
            out.add(info);
            log.info("agent", "balance.check", info.describe(), null, JsonlLog.data("provider", n, "amount", info.amount(), "currency", info.currency(), "error", info.error()));
            synchronized (this) {
                balances.put(n, info);
                if (info.error() != null) continue; // 查询失败不改变停用状态
                double amt = info.amount() == null ? Double.MAX_VALUE : info.amount();
                Rules.BalanceRule br = rl.quota.providerBalance.getOrDefault(n, new Rules.BalanceRule());
                boolean stop = (br.stopBelow > 0 && amt < br.stopBelow) || Boolean.FALSE.equals(info.available());
                if (stop && stopped.add(n)) {
                    msgs.add(n + " 余额 " + Quota.fmt(round2(amt)) + " " + info.currency() + "，低于停用线 " + Quota.fmt(br.stopBelow)
                            + "，已停用这个接入点" + (c.fallbackProviders.isEmpty() ? "（没有备用接入点，机器人暂停）" : "，改用备用接入点"));
                } else if (!stop && stopped.remove(n)) {
                    msgs.add(n + " 余额恢复到 " + Quota.fmt(round2(amt)) + " " + info.currency() + "，已重新启用");
                }
                boolean warn = br.warnBelow > 0 && amt < br.warnBelow;
                if (warn && !stop && warned.add(n)) {
                    msgs.add(n + " 余额只剩 " + Quota.fmt(round2(amt)) + " " + info.currency() + "（提醒线 " + Quota.fmt(br.warnBelow) + "），请及时充值");
                } else if (!warn) {
                    warned.remove(n); // 充值后重新布防
                }
            }
        }
        if (!msgs.isEmpty()) alerts.accept(msgs);
        return out;
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    /** /aibot quota：刷新余额后回复完整额度报告（在余额线程里做网络请求，不阻塞服务端主线程）。 */
    void report(Quota quota, Consumer<String> reply) {
        ExecutorService ex;
        synchronized (this) {
            ex = exec;
        }
        Runnable job = () -> {
            List<Balance.Info> infos = refresh(true);
            StringBuilder sb = new StringBuilder(quota.report(rules.get().quota, LocalDateTime.now()));
            for (Balance.Info i : infos) {
                sb.append('\n').append(i.describe());
                if (stopped(i.provider())) sb.append("【已停用】");
            }
            if (infos.isEmpty()) sb.append("\n（没有配置余额接口的接入点）");
            reply.accept(sb.toString());
        };
        if (ex == null || ex.isShutdown()) job.run();
        else {
            try {
                ex.execute(job);
            } catch (RejectedExecutionException e) {
                job.run();
            }
        }
    }
}
