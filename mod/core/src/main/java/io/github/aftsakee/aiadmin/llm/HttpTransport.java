package io.github.aftsakee.aiadmin.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * HTTP 发送抽象（测试里可以替换）。默认实现用 JDK HttpClient，强制 HTTP/1.1：不少本地反代/中转对 h2c 升级处理不好。
 * <p>安全约束：不跟随重定向。JDK 跨主机重定向时会去掉 Authorization，但会照转 x-api-key、x-goog-api-key 和自定义请求头——
 * 中转站（或被劫持的中转站）回一个 307 就能把 Claude / Antigravity 的密钥送到别的主机。收到 3xx 直接按错误处理。
 */
public interface HttpTransport {
    /** retryAfterSeconds：响应头 Retry-After 的秒数（没有或不是秒数时为 null）。 */
    record Response(int status, String body, Long retryAfterSeconds) {
        public Response(int status, String body) {
            this(status, body, null);
        }
    }

    Response send(String method, String url, Map<String, String> headers, String body, Duration timeout)
            throws IOException, InterruptedException;

    static HttpTransport jdk() {
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return (method, url, headers, body, timeout) -> {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(timeout);
            headers.forEach(b::header);
            if (body == null) b.method(method, HttpRequest.BodyPublishers.noBody());
            else b.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Long ra = null;
            try {
                ra = r.headers().firstValue("retry-after").map(v -> Long.parseLong(v.trim())).orElse(null);
            } catch (NumberFormatException ignored) {
                // HTTP 日期格式的 Retry-After 不处理，按默认退避
            }
            return new Response(r.statusCode(), r.body(), ra);
        };
    }
}
