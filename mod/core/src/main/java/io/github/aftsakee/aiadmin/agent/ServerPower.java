package io.github.aftsakee.aiadmin.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 宿主机接口（remote.cloud.power_enabled）：调用宿主机上 root 拥有的 /usr/local/sbin/aiadmin-power。
 * <p>
 * 边界：模组只转发"动作名 + 少量参数"，能做什么由那个程序决定——它的 {@code actions} 列出每个动作是只读（read）还是会改动（action），
 * 自己用 RCON 数在线人数、限频、避开定时备份，并把停/启放到独立的 systemd 单元里延迟执行（调用方就是要被停掉的这个进程）。
 * 这里只保证传过去的东西不可能被解释成别的：动作名和参数都是固定字符集的短单词、个数有限，用参数数组起进程（不经过 shell）。
 * 以后加宿主机动作只改那个程序，不用改模组。
 */
public final class ServerPower {
    public static final String PROGRAM = "/usr/local/sbin/aiadmin-power";
    static final Pattern ACTION = Pattern.compile("[a-z][a-z0-9-]{0,30}");
    static final Pattern ARG = Pattern.compile("[A-Za-z0-9_.:-]{1,40}");
    static final int MAX_ARGS = 3;
    /** 输出最多读这么多字节（日志 200 行 × 600 字符以内）。 */
    static final int MAX_OUTPUT = 131072;

    public record Result(int exit, String output) {}

    /** actions 清单里的一项：access = read（只读）或 action（会改动，要写理由）。 */
    public record Action(String name, String access, String args, String description) {
        public boolean readOnly() {
            return "read".equals(access);
        }
    }

    /** 测试里换成假的；正式环境用 {@link #sudo()}。 */
    public interface Runner {
        Result run(String action, List<String> args) throws Exception;
    }

    private ServerPower() {}

    /** 校验动作名和参数；不合格返回原因，合格返回 null。 */
    static String invalid(String action, List<String> args) {
        if (action == null || !ACTION.matcher(action).matches()) return "动作名只能是小写字母、数字和 -：" + action;
        if (args.size() > MAX_ARGS) return "参数最多 " + MAX_ARGS + " 个";
        for (String a : args) {
            if (!ARG.matcher(a).matches()) return "参数只能含字母、数字和 _ . : -，每个不超过 40 字符：" + a;
        }
        return null;
    }

    /** "a b  c" → [a, b, c]；空串 → []。 */
    static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        for (String p : (s == null ? "" : s.trim()).split("\\s+")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    /** 解析 actions 的输出（名字<TAB>read|action<TAB>参数<TAB>说明）；格式不对的行跳过。 */
    static List<Action> parseActions(String text) {
        List<Action> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length < 4 || !ACTION.matcher(f[0].trim()).matches()) continue;
            String access = f[1].trim();
            if (!access.equals("read") && !access.equals("action")) continue;
            out.add(new Action(f[0].trim(), access, f[2].trim(), f[3].trim()));
        }
        return out;
    }

    /** 向宿主机要动作清单；失败时抛出带原因的异常（工具把原因原样告诉调用方）。 */
    static List<Action> actions(Runner r) throws Exception {
        Result res = r.run("actions", List.of());
        if (res.exit() != 0) throw new IllegalStateException(res.output().isBlank() ? "宿主机接口不可用（退出码 " + res.exit() + "）" : res.output());
        List<Action> list = parseActions(res.output());
        if (list.isEmpty()) throw new IllegalStateException("宿主机接口没有返回可用动作（程序版本太旧？）");
        return list;
    }

    /** sudo -n 调用宿主机程序；30 秒没结束就杀掉（真正耗时的停/启/备份都在后台单元里，这里应当一两秒返回）。 */
    public static Runner sudo() {
        return (action, args) -> {
            String bad = invalid(action, args);
            if (bad != null) return new Result(2, bad);
            List<String> cmd = new ArrayList<>(List.of("sudo", "-n", PROGRAM, action));
            cmd.addAll(args);
            Process p;
            try {
                p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            } catch (IOException e) {
                return new Result(-1, "宿主机接口不可用（这台机器上没有 sudo 或 " + PROGRAM + "）：" + e.getMessage());
            }
            p.getOutputStream().close();
            // 输出放到专用守护线程读（不占公共线程池）：readAllBytes 要等子进程关掉输出才返回，子进程卡住时不能把调用线程也卡住
            CompletableFuture<String> out = new CompletableFuture<>();
            Thread reader = new Thread(() -> out.complete(read(p.getInputStream())), "aiadmin-power-out");
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                // 只能杀掉 sudo 本身：以 root 运行的子进程收不到我们的信号，可能还在执行——结果写成"状态未知"，不能说"没执行"
                p.destroyForcibly();
                return new Result(-1, "宿主机接口 30 秒没有返回，已中止等待；动作可能仍在执行，状态未知，请稍后用 host_info status 查看，不要立即重试");
            }
            String text;
            try {
                text = out.get(5, TimeUnit.SECONDS).strip();
            } catch (java.util.concurrent.TimeoutException e) {
                text = "（输出没读完：子进程把输出交给了还在运行的后台进程）"; // 退出码照常返回
            }
            // sudoers 没装或参数不在放行列表里时 sudo -n 直接报错，换成服主看得懂的话
            if (p.exitValue() != 0 && text.contains("sudo:")) text = "宿主机没有放行这个动作（sudoers 未安装？）：" + text;
            return new Result(p.exitValue(), text);
        };
    }

    private static String read(InputStream in) {
        try (in) {
            byte[] b = in.readNBytes(MAX_OUTPUT);
            in.transferTo(java.io.OutputStream.nullOutputStream()); // 超出的部分丢掉，但要读完，免得子进程写满管道卡住
            return new String(b, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
