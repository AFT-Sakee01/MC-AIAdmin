package io.github.aftsakee.aiadmin.remote;

import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * 远程接口的认证失败防护（为经 Tailscale Funnel 暴露到公网准备）：
 * <ul>
 *   <li>真实来源：TCP 来源是回环地址（Funnel / tailscale serve 的本地反向代理）时，取 X-Forwarded-For 的<b>最右</b>一个值——
 *       反向代理是在客户端自带的值后面追加，只有最右边那个是代理写的、可信；其他来源一律用 TCP 来源，忽略这个头。</li>
 *   <li>限流：同一来源在 window 内认证失败达到 limit 次后封禁 block。封禁只拦失败的请求：持有效令牌的请求永远放行——
 *       共享出口（claude.ai、ChatGPT、Anthropic/OpenAI API 的服务器）上的别人怎么输错都封不掉服主。封禁期间的失败照样计数，
 *       每再失败 {@link #HARD_LIMIT} 次封禁就延长一次。代价是封禁期间仍能试令牌：固定令牌 256 位、OAuth 令牌随机 32 字节，试不出来；
 *       手填的短令牌有风险（ConfigStore 要求至少 16 位，建议留空自动生成）。成功不清零失败计数。</li>
 *   <li>日志合并：同一来源的认证失败在一个窗口内只记第一条，封禁时再记一条汇总；被封期间的请求不逐条记录。</li>
 * </ul>
 * 只保存在内存里（重启清零）；表项超过 10000 时清理过期项，防止被大量不同来源撑爆内存。
 */
final class AuthGuard {
    private static final int MAX_ENTRIES = 10_000;

    private final Map<String, ArrayDeque<Long>> fails = new HashMap<>();
    private final Map<String, Long> blockedUntil = new HashMap<>();
    private final Map<String, Long> lastLogged = new HashMap<>();
    private final Map<String, Integer> suppressed = new HashMap<>();
    private final Map<String, Long> deniedIpLogged = new HashMap<>();
    private final Map<String, Integer> failsWhileBlocked = new HashMap<>();
    static final int HARD_LIMIT = 30;

    /** 认证失败后该怎么记日志：log = 要不要写这一条；blocked = 这一次刚触发封禁；count = 窗口内第几次。 */
    record Failure(boolean log, boolean blocked, int count, int suppressedBefore) {}

    static String realClient(InetAddress peer, String forwardedFor) {
        String p = peer.getHostAddress();
        if (!peer.isLoopbackAddress() || forwardedFor == null || forwardedFor.isBlank()) return p;
        String[] parts = forwardedFor.split(",");
        String last = parts[parts.length - 1].trim();
        if (last.startsWith("[") && last.contains("]")) last = last.substring(1, last.indexOf(']')); // [v6]:port
        else if (last.chars().filter(ch -> ch == ':').count() == 1) last = last.substring(0, last.indexOf(':')); // v4:port
        return last.matches("[0-9a-fA-F:.]{2,45}") ? prefix64(last) : p;
    }

    /** IPv6 按 /64 计（一个家庭/机房一般分到一整段 /64，轮换地址不能绕过封禁和名额）；IPv4 原样。只解析字面量，不查 DNS。 */
    static String prefix64(String ip) {
        if (!ip.contains(":")) return ip;
        try {
            byte[] b = InetAddress.getByName(ip).getAddress();
            if (b.length != 16) return ip;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i += 2) sb.append(Integer.toHexString(((b[i] & 0xff) << 8) | (b[i + 1] & 0xff))).append(':');
            return sb.append(":/64").toString();
        } catch (java.io.IOException | RuntimeException e) {
            return ip;
        }
    }

    synchronized long blockedSeconds(String client, long now) {
        Long until = blockedUntil.get(client);
        if (until == null) return 0;
        if (until <= now) {
            blockedUntil.remove(client);
            return 0;
        }
        return (until - now + 999) / 1000;
    }

    /**
     * blockable = false：分不清真实来源（本机来源且没有 X-Forwarded-For，所有经代理的请求看起来都是 127.0.0.1）。
     * 这时只合并日志、不封禁——否则公网扫描器连错几次就会把正常的云端客户端一起封掉。
     */
    synchronized Failure recordFailure(String client, long now, int limit, long windowMs, long blockMs, boolean blockable) {
        cleanup(now, windowMs);
        Long until = blockedUntil.get(client);
        if (blockable && until != null && until > now) {
            // 封禁期间的失败：计数；每够 HARD_LIMIT 次延长封禁（只记这一条日志）
            int n = failsWhileBlocked.merge(client, 1, Integer::sum);
            if (n >= HARD_LIMIT) {
                failsWhileBlocked.remove(client);
                blockedUntil.put(client, now + blockMs);
                return new Failure(true, true, n, 0);
            }
            return new Failure(false, false, n, 0);
        }
        failsWhileBlocked.remove(client);
        ArrayDeque<Long> d = fails.computeIfAbsent(client, k -> new ArrayDeque<>());
        while (!d.isEmpty() && now - d.peekFirst() > windowMs) d.pollFirst();
        d.addLast(now);
        int n = d.size();
        if (blockable && limit > 0 && n >= limit) {
            blockedUntil.put(client, now + blockMs);
            d.clear();
            int sup = suppressed.getOrDefault(client, 0);
            suppressed.remove(client);
            lastLogged.remove(client);
            return new Failure(true, true, n, sup);
        }
        Long last = lastLogged.get(client);
        if (last == null || now - last > windowMs) {
            lastLogged.put(client, now);
            return new Failure(true, false, n, 0);
        }
        suppressed.merge(client, 1, Integer::sum);
        return new Failure(false, false, n, 0);
    }

    /** 成功只重置日志合并状态，不清失败计数（理由见类注释）。 */
    synchronized void recordSuccess(String client) {
        lastLogged.remove(client);
        suppressed.remove(client);
    }



    /** 来源网段不允许的请求：同一 IP 一个窗口内只记一次日志。 */
    synchronized boolean shouldLogDeniedIp(String ip, long now, long windowMs) {
        Long last = deniedIpLogged.get(ip);
        if (last != null && now - last <= windowMs) return false;
        if (deniedIpLogged.size() > MAX_ENTRIES) deniedIpLogged.clear();
        deniedIpLogged.put(ip, now);
        return true;
    }

    private void cleanup(long now, long windowMs) {
        if (fails.size() + blockedUntil.size() + lastLogged.size() < MAX_ENTRIES) return;
        fails.values().removeIf(d -> d.isEmpty() || now - d.peekLast() > windowMs);
        fails.keySet().removeIf(k -> !fails.containsKey(k));
        blockedUntil.values().removeIf(u -> u <= now);
        failsWhileBlocked.keySet().retainAll(blockedUntil.keySet());
        lastLogged.values().removeIf(t -> now - t > windowMs);
        suppressed.keySet().retainAll(lastLogged.keySet());
        if (fails.size() > MAX_ENTRIES) fails.clear(); // 仍然太多（大量不同来源）：直接清空，宁可放松计数也不能耗尽内存
    }
}
