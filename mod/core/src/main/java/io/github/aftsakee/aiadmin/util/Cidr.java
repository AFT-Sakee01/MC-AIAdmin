package io.github.aftsakee.aiadmin.util;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;

/** IP 地址段（IPv4/IPv6 CIDR）匹配，以及找本机的 Tailscale 地址。 */
public final class Cidr {
    private final byte[] net;
    private final int bits;

    private Cidr(byte[] net, int bits) {
        this.net = net;
        this.bits = bits;
    }

    /** "100.64.0.0/10"、"fd7a:115c:a1e0::/48"、"127.0.0.1"（= /32）。只接受字面 IP，不做 DNS 解析。 */
    public static Cidr parse(String s) {
        String t = s.trim();
        int slash = t.indexOf('/');
        String ip = slash < 0 ? t : t.substring(0, slash);
        if (!ip.matches("[0-9a-fA-F:.]+")) throw new IllegalArgumentException("不是 IP：" + s);
        byte[] a;
        try {
            a = InetAddress.getByName(ip).getAddress(); // 字面 IP 不会触发 DNS
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("不是 IP：" + s);
        }
        int bits;
        try {
            bits = slash < 0 ? a.length * 8 : Integer.parseInt(t.substring(slash + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("前缀长度不对：" + s);
        }
        if (bits < 0 || bits > a.length * 8) throw new IllegalArgumentException("前缀长度不对：" + s);
        return new Cidr(a, bits);
    }

    public boolean contains(InetAddress addr) {
        byte[] b = addr.getAddress();
        if (b.length != net.length) return false;
        int full = bits / 8, rest = bits % 8;
        for (int i = 0; i < full; i++) if (b[i] != net[i]) return false;
        if (rest == 0) return true;
        int mask = (0xFF << (8 - rest)) & 0xFF;
        return (b[full] & mask) == (net[full] & mask);
    }

    public static boolean anyContains(List<String> cidrs, InetAddress addr) {
        for (String c : cidrs) {
            try {
                if (parse(c).contains(addr)) return true;
            } catch (IllegalArgumentException ignored) {
                // 设置校验时已经拦过
            }
        }
        return false;
    }

    /**
     * 本机 Tailscale 地址：优先名为 tailscale* 的网卡（Linux 上是 tailscale0，Windows 显示名含 Tailscale），
     * 否则任何处于 100.64.0.0/10（Tailscale 用的 CGNAT 段）的 IPv4。找不到返回 null。
     */
    public static InetAddress tailscaleAddress() {
        Cidr cgnat = parse("100.64.0.0/10");
        InetAddress fallback = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp()) continue;
                boolean named = ni.getName().toLowerCase().startsWith("tailscale")
                        || (ni.getDisplayName() != null && ni.getDisplayName().toLowerCase().contains("tailscale"));
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !cgnat.contains(a)) continue;
                    if (named) return a;
                    if (fallback == null) fallback = a;
                }
            }
        } catch (SocketException e) {
            return null;
        }
        return fallback;
    }
}
