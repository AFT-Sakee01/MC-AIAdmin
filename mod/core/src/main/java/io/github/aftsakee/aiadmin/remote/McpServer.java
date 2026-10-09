package io.github.aftsakee.aiadmin.remote;

import io.github.aftsakee.aiadmin.agent.AgentService;
import io.github.aftsakee.aiadmin.agent.Tools;
import io.github.aftsakee.aiadmin.config.BotConfig;
import io.github.aftsakee.aiadmin.config.Rules;
import io.github.aftsakee.aiadmin.log.JsonlLog;
import io.github.aftsakee.aiadmin.util.Cidr;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 远程管理接口：MCP（Model Context Protocol）Streamable HTTP 端点 POST /mcp。云电脑上的 AI（例如 Claude Code：claude mcp add --transport http …）
 * 通过 Tailscale 连接后，可以使用与机器人相同的工具；配了 remote.public_base_url 时还提供 OAuth（{@link OAuthProvider}），
 * 给 ChatGPT 连接器这类不能填固定请求头的客户端；新版协议客户端还能订阅 MCP Events（{@link McpEvents}：ChatGPT / dots 的 webhook 推送）。
 * <p>本类只管生命周期和装配（start / stop / 绑定重试 / 令牌文件 / describe 与 /aibot remote 的管理方法），请求路径由三个部件完成：
 * <ol>
 *   <li>{@link RemoteGate}：HTTP 闸门——来源 CIDR → Origin → Bearer 令牌 → 认证失败封禁与未认证名额（顺序见它的类注释和 AGENTS.md），OAuth 端点路由；</li>
 *   <li>{@link McpProtocol}：JSON-RPC 双代协议分发（旧版 initialize 握手 / 新版 2026-07-28 无握手）、server/discover、批量规则；</li>
 *   <li>{@link RemoteTools}：工具目录、调用路径、只读模式（判定在 {@link ToolAccess}）和远程每分钟/每小时上限。</li>
 * </ol>
 * 依赖方向：McpServer → RemoteGate → McpProtocol（经 {@link RemoteGate.Mcp} 接口）→ RemoteTools / McpEvents；部件之间不反向引用，
 * 需要的易变状态（令牌、OAuth、监听地址、推送订阅）都经 Supplier 从本类的 volatile 字段读。
 * <p>线程：start / stop / tryBind / describe 用本对象的监视器串行（服务器线程的 /aibot reload 和绑定重试线程）；请求由 MiniHttp 的处理线程
 * 并发调用部件，部件持有的计数各自加锁（见各类注释）。三个部件在构造时建好、跟着本对象活：重启远程接口不清认证失败封禁表和调用计数。
 */
public final class McpServer {
    /** 旧版协议一次批量最多几条（定义见 {@link McpProtocol#MAX_BATCH}，这里给包外的调用方和测试用）。 */
    public static final int MAX_BATCH = McpProtocol.MAX_BATCH;

    private final Supplier<BotConfig> cfg;
    private final AgentService svc;
    private final JsonlLog log;
    private final RemoteGate gate;
    private volatile MiniHttp http;
    private volatile String token;
    private volatile String boundUrl;
    private volatile String waitingReason;
    private ScheduledExecutorService retry;
    private volatile OAuthProvider oauth;
    private volatile McpEvents events;

    public McpServer(Supplier<BotConfig> cfg, Supplier<Rules> rules, Tools tools, AgentService svc, JsonlLog log, String version) {
        this.cfg = cfg;
        this.svc = svc;
        this.log = log;
        RemoteTools remoteTools = new RemoteTools(cfg, rules, tools, svc, log);
        McpProtocol protocol = new McpProtocol(cfg, log, version, remoteTools, this::events);
        this.gate = new RemoteGate(cfg, log, this::token, this::oauth, this::url, protocol::handle);
    }

    /** 按当前设置启动（已在运行就先停）。绑定地址暂时找不到（Tailscale 还没起来）时每分钟重试。 */
    public synchronized void start(Path configDir) {
        BotConfig.Remote now = cfg.get().remote;
        // 远程接口这一节设置和令牌都没变、而且正在监听：不重启。/aibot reload 和云端 set_setting 都会走到这里，
        // 重启会切断正在进行的请求（包括发起这次改设置的那个请求、挂着的 wait_events）。
        if (http != null && now.enabled && configDir.equals(startedDir) && fingerprint(now).equals(startedFingerprint)) {
            try {
                if (resolveToken(now, configDir).equals(token)) {
                    log.info("remote", "remote.unchanged", "远程接口设置没变，继续运行", null, null);
                    return;
                }
            } catch (IOException ignored) {
                // 读令牌失败就按正常流程重启，由下面报告错误
            }
        }
        stop();
        BotConfig.Remote r = cfg.get().remote;
        if (!r.enabled) return;
        try {
            token = resolveToken(r, configDir);
        } catch (IOException e) {
            waitingReason = "无法生成/读取令牌文件：" + e.getMessage();
            log.error("remote", "remote.token_failed", waitingReason, null, JsonlLog.data());
            return;
        }
        // 重读设置时沿用同一个实例：内存里的 access token、进行中的授权不丢
        Path state = configDir.resolve("oauth-state.json");
        if (oauth == null || !oauth.stateFile().equals(state)) oauth = new OAuthProvider(cfg, log, state);
        Path ev = configDir.resolve("mcp-events.json");
        if (events == null || !events.stateFile().equals(ev)) {
            if (events != null) events.close();
            // -Daiadmin.devWebhookInsecure=true 只给端到端测试用：允许 http 和本机回调地址
            events = new McpEvents(cfg, log, ev, this::principalValid, this::principalLabel, Boolean.getBoolean("aiadmin.devWebhookInsecure"));
        }
        McpEvents e = events;
        e.onDelivered(svc::cloudDelivered);
        svc.cloudPush(new AgentService.CloudPush() {
            @Override
            public boolean active() {
                return e.active();
            }

            @Override
            public void publish(io.github.aftsakee.aiadmin.agent.CloudInbox.Item it, int onlinePlayers) {
                e.publish(it, onlinePlayers);
            }

            @Override
            public String status() {
                return e.statusShort();
            }
        });
        startedDir = configDir;
        startedFingerprint = fingerprint(r);
        retry = Executors.newSingleThreadScheduledExecutor(t -> {
            Thread th = new Thread(t, "aiadmin-remote-bind");
            th.setDaemon(true);
            return th;
        });
        retry.scheduleWithFixedDelay(this::tryBind, 0, 60, TimeUnit.SECONDS);
    }

    private Path startedDir;
    private String startedFingerprint;

    private static String fingerprint(BotConfig.Remote r) {
        return io.github.aftsakee.aiadmin.config.ConfigStore.GSON.toJson(r);
    }

    private synchronized void tryBind() {
        if (http != null) return;
        BotConfig.Remote r = cfg.get().remote;
        InetAddress addr;
        try {
            addr = r.bind.equalsIgnoreCase("tailscale") ? Cidr.tailscaleAddress() : InetAddress.getByName(r.bind);
        } catch (IOException e) {
            addr = null;
        }
        if (addr == null) {
            if (waitingReason == null) log.warn("remote", "remote.waiting", "没找到 Tailscale 地址，远程管理接口每分钟重试一次", null, null);
            waitingReason = "等待 Tailscale 地址（本机还没有 100.64.0.0/10 段的地址）";
            return;
        }
        try {
            http = new MiniHttp(addr, r.port, gate::handle, gate::early, gate::done);
            String host = addr.getHostAddress().contains(":") ? "[" + addr.getHostAddress() + "]" : addr.getHostAddress();
            boundUrl = "http://" + host + ":" + http.port() + "/mcp";
            waitingReason = null;
            log.info("remote", "remote.started", "远程管理接口已监听 " + boundUrl, null, JsonlLog.data("url", boundUrl));
            if (retry != null) retry.shutdown();
        } catch (IOException e) {
            waitingReason = "端口 " + r.port + " 监听失败：" + e.getMessage();
            log.error("remote", "remote.bind_failed", waitingReason, null, JsonlLog.data());
        }
    }

    public synchronized void stop() {
        if (retry != null) retry.shutdownNow();
        retry = null;
        svc.cloudPush(null);
        if (events != null) events.close();
        if (http != null) {
            http.close();
            log.info("remote", "remote.stopped", "远程管理接口已停止", null, null);
        }
        http = null;
        boundUrl = null;
        waitingReason = null;
    }

    public synchronized String describe() {
        if (!cfg.get().remote.enabled) return "远程管理：关闭（config.json 的 remote.enabled）";
        if (boundUrl != null) {
            OAuthProvider o = oauth;
            String oa = o != null && o.enabled() ? "；OAuth 已启用，对外地址 " + cfg.get().remote.publicBaseUrl + "/mcp"
                    : "；OAuth 未启用（需要 remote.public_base_url）";
            McpEvents e = events;
            String ev = e == null || e.statusShort().isEmpty() ? "" : "；" + e.statusShort();
            return "远程管理：已监听 " + boundUrl + "（令牌见 config/aiadmin/remote-token.txt 或 remote.token）" + oa + ev;
        }
        return "远程管理：未就绪（" + (waitingReason == null ? "启动中" : waitingReason) + "）";
    }

    public String url() {
        return boundUrl;
    }

    /** 固定令牌（RemoteGate 校验 Bearer 和批准口令用；不写日志）。 */
    String token() {
        return token;
    }

    OAuthProvider oauth() {
        return oauth;
    }

    /** /aibot remote approve &lt;确认号&gt;：批准批准页上显示的那个授权请求；null = OAuth 没启用。 */
    public String approve(String confirm) {
        OAuthProvider o = oauth;
        if (o == null || !o.enabled()) return null;
        return o.approve(confirm);
    }

    /** /aibot remote approve（不带参数）：列出待批请求；null = OAuth 没启用。 */
    public String pendingRequests() {
        OAuthProvider o = oauth;
        if (o == null || !o.enabled()) return null;
        return o.describePending();
    }

    public String oauthClients() {
        OAuthProvider o = oauth;
        return o == null ? "OAuth 未初始化（远程管理没有开启）" : o.describeClients();
    }

    public String revoke(String clientId) {
        OAuthProvider o = oauth;
        if (o == null) return "OAuth 未初始化（远程管理没有开启）";
        McpEvents e = events;
        if (e != null) e.removeByPrincipal(clientId); // 吊销的客户端的推送订阅一起删
        return o.revoke(clientId);
    }

    public String revokeAll() {
        OAuthProvider o = oauth;
        if (o == null) return "OAuth 未初始化（远程管理没有开启）";
        McpEvents e = events;
        if (e != null) e.removeOAuth();
        return o.revokeAll();
    }

    /** /aibot remote events：推送订阅列表；null = 远程管理没开。 */
    public String eventSubscriptions() {
        McpEvents e = events;
        return e == null ? null : e.describe();
    }

    McpEvents events() {
        return events;
    }

    /** 推送订阅的归属还有效：固定令牌永远有效，OAuth 客户端要还没被吊销。 */
    private boolean principalValid(String principal) {
        if (principal.equals("token")) return true;
        OAuthProvider o = oauth;
        return o != null && o.knownClient(principal);
    }

    private String principalLabel(String principal) {
        if (principal.equals("token")) return "固定令牌";
        OAuthProvider o = oauth;
        return o == null ? principal : o.clientLabel(principal);
    }

    static String resolveToken(BotConfig.Remote r, Path configDir) throws IOException {
        if (!r.token.isBlank()) return r.token.trim();
        Path f = configDir.resolve("remote-token.txt");
        if (Files.exists(f)) {
            String t = Files.readString(f, StandardCharsets.UTF_8).trim();
            if (t.length() >= 16) {
                try {
                    // 已有的令牌文件（例如手工建的）权限可能过宽：收紧到 660；不是属主改不了就算了
                    Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-rw----"));
                } catch (UnsupportedOperationException | IOException ignored) {
                    // Windows 没有 POSIX 权限
                }
                return t;
            }
        }
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        String t = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        OAuthProvider.writePrivate(f, t + "\n"); // 建立时就是 660，没有先建后改权限的窗口
        return t;
    }
}
