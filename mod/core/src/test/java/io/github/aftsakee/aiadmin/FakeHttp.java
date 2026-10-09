package io.github.aftsakee.aiadmin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.aftsakee.aiadmin.llm.HttpTransport;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/** 脚本化的假 HTTP：按顺序返回预设回复，记录每个请求。 */
final class FakeHttp implements HttpTransport {
    record Req(String method, String url, Map<String, String> headers, String body) {
        JsonObject json() {
            return JsonParser.parseString(body).getAsJsonObject();
        }
    }

    /** 线程约束：代理线程在 send 里写、测试线程在轮询里读，所以用同步列表；脚本的增删也都在同一把锁里。 */
    final List<Req> requests = java.util.Collections.synchronizedList(new ArrayList<>());
    private final Deque<Response> script = new ArrayDeque<>();

    synchronized FakeHttp then(int status, String body) {
        script.add(new Response(status, body));
        return this;
    }

    FakeHttp ok(String body) {
        return then(200, body);
    }

    /** 等到至少 n 个请求（或超时），返回当前数量。 */
    int awaitRequests(int n, long timeoutMs) throws InterruptedException {
        long until = System.currentTimeMillis() + timeoutMs;
        while (requests.size() < n && System.currentTimeMillis() < until) Thread.sleep(20);
        return requests.size();
    }

    @Override
    public synchronized Response send(String method, String url, Map<String, String> headers, String body, Duration timeout) {
        requests.add(new Req(method, url, Map.copyOf(headers), body));
        Response r = script.pollFirst();
        if (r == null) throw new IllegalStateException("脚本里没有更多回复了：" + method + " " + url);
        return r;
    }
}
