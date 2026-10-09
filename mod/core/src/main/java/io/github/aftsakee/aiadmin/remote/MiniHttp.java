package io.github.aftsakee.aiadmin.remote;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 极简 HTTP/1.1 服务端（只为远程管理接口）：不用 JDK 的 com.sun.net.httpserver——它在 NeoForge 的模块层里不一定可见；
 * 也不用 Netty，避免碰 MC 自己的网络线程。
 * <p>边界：每个连接只处理一个请求（Connection: close）；请求行 + 头最多 16 KiB、100 个头；正文必须带 Content-Length 且 ≤ 1 MiB
 * （不支持 chunked）；请求头必须在 {@link #HEAD_MS} 内收完、整个请求在 {@link #TOTAL_MS} 内收完（防止慢速逐字节发送占住线程，
 * 单次读超时 15 秒挡不住这种）；这两个时限只管"读请求"，不管处理函数执行多久（云端值守的长轮询会挂几分钟）。写回应没有超时
 * （Java 阻塞 socket 不支持写超时；回应都很小，对方不读时由系统发送缓冲吸收）。处理线程 16 个、排队 64 个，满了直接回 503。
 * 任何异常都只影响当前连接。
 * <p>可选的 early 回调在读完请求头、读请求体之前调用（body 为空数组）：返回非 null 就直接用它回应，不再读请求体——
 * 用来在等慢速请求体之前就拒绝被封禁的来源、过大的请求、同时在读的未认证请求太多的来源。
 * 可选的 done 回调在 early 放行的请求处理完（成功或失败）后调用，用来释放 early 里占的名额。
 */
public final class MiniHttp implements Closeable {
    public static final int MAX_BODY = 1 << 20;
    private static final int MAX_HEAD = 16 << 10;
    static final long HEAD_MS = 10_000L;
    static final long TOTAL_MS = 30_000L;

    public record Request(String method, String path, Map<String, String> headers, byte[] body, InetAddress remote) {
        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    public record Response(int status, String contentType, byte[] body, Map<String, String> headers) {
        public static Response json(int status, String json) {
            return new Response(status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8), Map.of());
        }

        public static Response empty(int status, Map<String, String> headers) {
            return new Response(status, null, new byte[0], headers);
        }
    }

    @FunctionalInterface
    public interface Handler {
        Response handle(Request r);
    }

    private final ServerSocket ss;
    private final ThreadPoolExecutor pool;
    private final Handler handler;
    private final Handler early;
    /** early 回调里可以用 {@link #limitBodyTime} 给这一个请求设更短的请求体时限（同一线程，读完立即清掉）。 */
    private static final ThreadLocal<Long> BODY_MS = new ThreadLocal<>();
    private final java.util.function.Consumer<Request> done;
    private volatile boolean running = true;

    public MiniHttp(InetAddress bind, int port, Handler handler) throws IOException {
        this(bind, port, handler, null);
    }

    public MiniHttp(InetAddress bind, int port, Handler handler, Handler early) throws IOException {
        this(bind, port, handler, early, null);
    }

    public MiniHttp(InetAddress bind, int port, Handler handler, Handler early, java.util.function.Consumer<Request> done) throws IOException {
        this.handler = handler;
        this.early = early;
        this.done = done;
        ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(bind, port), 50);
        AtomicInteger n = new AtomicInteger();
        pool = new ThreadPoolExecutor(16, 16, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64), r -> {
            Thread t = new Thread(r, "aiadmin-remote-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        Thread acceptor = new Thread(this::acceptLoop, "aiadmin-remote-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /** 只能在 early 回调里调用：这个请求的请求体必须在 ms 毫秒内传完（不超过整个请求的时限）。 */
    public static void limitBodyTime(long ms) {
        BODY_MS.set(ms);
    }

    public int port() {
        return ss.getLocalPort();
    }

    private void acceptLoop() {
        while (running) {
            Socket s;
            try {
                s = ss.accept();
            } catch (IOException e) {
                if (!running) return;
                continue;
            }
            try {
                pool.execute(() -> serve(s));
            } catch (RejectedExecutionException e) {
                try (s) {
                    write(s.getOutputStream(), Response.json(503, "{\"error\":\"busy\"}"));
                } catch (IOException ignored) {
                    // 客户端已断开
                }
            }
        }
    }

    private void serve(Socket s) {
        try (s) {
            s.setSoTimeout(15_000);
            long start = System.currentTimeMillis();
            Deadline dl = new Deadline(s.getInputStream(), s, start + HEAD_MS);
            InputStream in = new BufferedInputStream(dl);
            Response resp;
            boolean consumed = false;
            BODY_MS.remove();
            try {
                Request head = readHead(in, s.getInetAddress());
                resp = early == null ? null : early.handle(head);
                Long bodyMs = BODY_MS.get();
                BODY_MS.remove();
                if (resp == null) {
                    try {
                        dl.until = bodyMs == null ? start + TOTAL_MS : Math.min(start + TOTAL_MS, System.currentTimeMillis() + bodyMs);
                        Request full = withBody(head, in);
                        consumed = true;
                        resp = handler.handle(full);
                    } finally {
                        if (done != null) done.accept(head);
                    }
                }
            } catch (HttpError e) {
                resp = Response.json(e.status, "{\"error\":\"" + e.getMessage() + "\"}");
            } catch (RuntimeException e) {
                resp = Response.json(500, "{\"error\":\"internal\"}");
            }
            write(s.getOutputStream(), resp);
            if (!consumed) {
                dl.until = Math.min(dl.until, System.currentTimeMillis() + 2000); // 否则 Deadline.arm 会把 drain 的读超时放宽到请求头的截止时间
                drain(s, in);
            }
        } catch (IOException ignored) {
            // 超时、断开：只影响这一个连接
        }
    }

    /**
     * 没读完请求体就回应了（提前拒绝、请求头出错）：先关掉写方向，再在最多 2 秒、256 KiB 内读掉客户端还在发的数据再关连接。
     * 直接关会让系统回 RST，客户端常常连已经发出的回应都收不到。上限保证慢速客户端占不住线程。
     */
    private static void drain(Socket s, InputStream in) {
        try {
            s.shutdownOutput();
            long until = System.currentTimeMillis() + 2000;
            byte[] buf = new byte[8192];
            int total = 0;
            while (total < (256 << 10)) {
                long left = until - System.currentTimeMillis();
                if (left <= 0) break;
                s.setSoTimeout((int) left);
                int n = in.read(buf);
                if (n < 0) break;
                total += n;
            }
        } catch (IOException | RuntimeException ignored) {
            // 对方已断开或超时：直接关
        }
    }

    static final class HttpError extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int status;

        HttpError(int status, String msg) {
            super(msg);
            this.status = status;
        }
    }

    /**
     * 给底层输入流加一个总截止时间：每次读之前把单次超时设成"离截止还剩多久"（最多 15 秒），到点就抛 408。
     * 放在 BufferedInputStream 下面，所以只在真正读网络时检查，不是每个字节都检查。
     */
    static final class Deadline extends java.io.FilterInputStream {
        final Socket socket;
        volatile long until;

        Deadline(InputStream in, Socket socket, long until) {
            super(in);
            this.socket = socket;
            this.until = until;
        }

        private void arm() throws IOException {
            long left = until - System.currentTimeMillis();
            if (left <= 0) throw new HttpError(408, "request timeout");
            socket.setSoTimeout((int) Math.min(left, 15_000L));
        }

        @Override
        public int read() throws IOException {
            arm();
            try {
                return super.read();
            } catch (java.net.SocketTimeoutException e) {
                throw expired(e);
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            arm();
            try {
                return super.read(b, off, len);
            } catch (java.net.SocketTimeoutException e) {
                throw expired(e);
            }
        }

        /** 读超时发生在总截止时间到点时：回 408；否则是普通的读超时，按原样断开。 */
        private IOException expired(java.net.SocketTimeoutException e) {
            if (System.currentTimeMillis() >= until - 50) throw new HttpError(408, "request timeout");
            return e;
        }
    }

    static Request read(InputStream in, InetAddress remote) throws IOException {
        return withBody(readHead(in, remote), in);
    }

    /** 请求行和请求头（含 Content-Length 校验），body 先留空。 */
    static Request readHead(InputStream in, InetAddress remote) throws IOException {
        int[] budget = {MAX_HEAD};
        String line = readLine(in, budget);
        if (line == null) throw new HttpError(400, "empty");
        String[] parts = line.split(" ");
        if (parts.length != 3 || !parts[2].startsWith("HTTP/1.")) throw new HttpError(400, "bad request line");
        Map<String, String> headers = new LinkedHashMap<>();
        while (true) {
            String h = readLine(in, budget);
            if (h == null) throw new HttpError(400, "eof in headers");
            if (h.isEmpty()) break;
            int c = h.indexOf(':');
            if (c <= 0) throw new HttpError(400, "bad header");
            if (headers.size() >= 100) throw new HttpError(431, "too many headers");
            headers.put(h.substring(0, c).trim().toLowerCase(Locale.ROOT), h.substring(c + 1).trim());
        }
        if (headers.containsKey("transfer-encoding")) throw new HttpError(411, "length required");
        int len = 0;
        if (headers.containsKey("content-length")) {
            try {
                len = Integer.parseInt(headers.get("content-length"));
            } catch (NumberFormatException e) {
                throw new HttpError(400, "bad content-length");
            }
            if (len < 0) throw new HttpError(400, "bad content-length");
            if (len > MAX_BODY) throw new HttpError(413, "too large");
        }
        return new Request(parts[0].toUpperCase(Locale.ROOT), parts[1], headers, new byte[0], remote);
    }

    static Request withBody(Request head, InputStream in) throws IOException {
        String cl = head.header("content-length");
        int len = cl == null ? 0 : Integer.parseInt(cl); // readHead 已校验
        byte[] body = in.readNBytes(len);
        if (body.length != len) throw new HttpError(400, "short body");
        return new Request(head.method(), head.path(), head.headers(), body, head.remote());
    }

    private static String readLine(InputStream in, int[] budget) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (--budget[0] < 0) throw new HttpError(431, "header too large");
            if (c == '\n') break;
            if (c != '\r') b.write(c);
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString(StandardCharsets.ISO_8859_1);
    }

    private static void write(OutputStream out, Response r) throws IOException {
        StringBuilder h = new StringBuilder("HTTP/1.1 ").append(r.status()).append(' ').append(reason(r.status())).append("\r\n");
        if (r.contentType() != null) h.append("Content-Type: ").append(r.contentType()).append("\r\n");
        for (Map.Entry<String, String> e : r.headers().entrySet()) h.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        h.append("Content-Length: ").append(r.body().length).append("\r\nConnection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(r.body());
        out.flush();
    }

    private static String reason(int s) {
        return switch (s) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 202 -> "Accepted";
            case 302 -> "Found";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 408 -> "Request Timeout";
            case 409 -> "Conflict";
            case 411 -> "Length Required";
            case 413 -> "Payload Too Large";
            case 429 -> "Too Many Requests";
            case 431 -> "Request Header Fields Too Large";
            case 500 -> "Internal Server Error";
            case 503 -> "Service Unavailable";
            default -> "Error";
        };
    }

    @Override
    public void close() {
        running = false;
        try {
            ss.close();
        } catch (IOException ignored) {
            // 已关闭
        }
        pool.shutdownNow();
    }
}
