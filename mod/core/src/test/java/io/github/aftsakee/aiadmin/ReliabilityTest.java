package io.github.aftsakee.aiadmin;

import io.github.aftsakee.aiadmin.agent.Memory;
import io.github.aftsakee.aiadmin.agent.Milestones;
import io.github.aftsakee.aiadmin.agent.PlayerPrefs;
import io.github.aftsakee.aiadmin.agent.ProjectStore;
import io.github.aftsakee.aiadmin.agent.Quota;
import io.github.aftsakee.aiadmin.config.ProviderConfig;
import io.github.aftsakee.aiadmin.llm.HttpTransport;
import io.github.aftsakee.aiadmin.llm.Llm;
import io.github.aftsakee.aiadmin.llm.Providers;
import io.github.aftsakee.aiadmin.tpa.TpaManager;
import io.github.aftsakee.aiadmin.util.JsonFiles;
import io.github.aftsakee.aiadmin.util.OnceTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.0.0.30 可靠性修复：状态文件读坏不覆盖、工程快照、里程碑延迟落盘、记忆压缩按身份删除、模型请求不跟随跳转/超时不重试、主线程任务取消语义。 */
class ReliabilityTest {
    @TempDir
    Path tmp;
    final List<String> reported = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void unhook() {
        JsonFiles.setErrorSink(null);
    }

    private long corruptCopies(String name) throws Exception {
        try (var s = Files.list(tmp)) {
            return s.filter(p -> p.getFileName().toString().startsWith(name + ".corrupt-")).count();
        }
    }

    @Test
    void brokenStateFilesAreQuarantinedNotOverwritten() throws Exception {
        JsonFiles.setErrorSink((e, m) -> reported.add(e + " " + m));
        Path prefs = tmp.resolve("player-prefs.json");
        Files.writeString(prefs, "{\"players\":[{\"name\":\"Steve\",\"style\":\"serious\"}");
        PlayerPrefs p = new PlayerPrefs(prefs, System::currentTimeMillis);
        assertNotNull(p.loadError());
        assertFalse(Files.exists(prefs), "坏文件被改名隔离");
        assertEquals(1, corruptCopies("player-prefs.json"));
        p.setStyle("Alex", PlayerPrefs.SERIOUS, "Alex");
        assertTrue(Files.exists(prefs), "之后照常保存新数据");
        assertTrue(reported.stream().anyMatch(r -> r.startsWith("state.corrupt")), reported.toString());

        Path quota = tmp.resolve("quota.json");
        Files.writeString(quota, "{not json");
        Quota q = new Quota(quota);
        assertNotNull(q.loadError(), "额度账本坏了要报出来（AgentService 会提醒管理员）");
        assertEquals(1, corruptCopies("quota.json"));

        Path tpa = tmp.resolve("tpa-prefs.json");
        Files.writeString(tpa, "[1,2,3]");
        TpaManager t = new TpaManager(tpa);
        t.setAutoAccept(UUID.randomUUID(), true);
        assertEquals(1, corruptCopies("tpa-prefs.json"), "旧文件留证，不会被只有一条的新内容覆盖掉证据");

        Path mem = tmp.resolve("memory.json");
        Files.writeString(mem, "{\"entries\":");
        assertNotNull(new Memory(mem).loadError());
        assertEquals(1, corruptCopies("memory.json"));
    }

    @Test
    void partiallyBrokenRecordsAreSkippedAndKept() throws Exception {
        Path prefs = tmp.resolve("player-prefs.json");
        Files.writeString(prefs, "{\"players\":[{\"name\":\"Steve\",\"style\":\"serious\"},{\"name\":\"Bad\",\"avoid\":\"不是列表\"},{\"name\":\"Alex\"}]}");
        PlayerPrefs p = new PlayerPrefs(prefs, System::currentTimeMillis);
        assertEquals(PlayerPrefs.SERIOUS, p.style("Steve"));
        assertNotNull(p.loadError());
        assertTrue(Files.exists(prefs), "整体能读：原文件不动");
        assertEquals(1, corruptCopies("player-prefs.json"), "坏记录复制一份留证");
    }

    @Test
    void projectStoreHandsOutSnapshotsAndRepairsSeq() throws Exception {
        Path f = tmp.resolve("projects.json");
        Files.writeString(f, "{\"projects\":[{\"id\":\"p5\",\"title\":\"城堡\",\"owner\":\"Steve\",\"status\":\"active\",\"radius\":48}]}");
        AtomicLong now = new AtomicLong(1_000_000_000L);
        ProjectStore s = new ProjectStore(f, now::get);
        ProjectStore.Project copy = s.get("p5");
        copy.members.add("Mallory");
        copy.radius = 1;
        assertFalse(s.involves("p5", "Mallory"), "改快照不影响存储");
        assertEquals(48, s.get("p5").radius);
        assertTrue(s.setRadius("p5", "Alex", 100).contains("只有负责人"));
        assertTrue(s.setRadius("p5", "Steve", 100).contains("100"));
        assertEquals(100, s.get("p5").radius);
        String r = s.create("Alex", "农场", "farm", null, 0, 0, 0, 48, "Alex");
        assertTrue(r.contains("p6"), "seq 缺失时从已有编号续：" + r);
        assertEquals("城堡", s.get("p5").title, "没有覆盖已有工程");
    }

    @Test
    void milestonesWriteLazily() throws Exception {
        Path f = tmp.resolve("milestones.json");
        Milestones m = new Milestones(f);
        assertTrue(m.first("Steve", "beacon"));
        assertFalse(Files.exists(f), "主线程上不写盘");
        m.saveIfDirty();
        assertTrue(Files.readString(f).contains("beacon"));
        assertFalse(new Milestones(f).first("Steve", "beacon"), "落盘后重启仍记得");
    }

    /** 压缩期间又追加、头部还被 HARD_MAX 裁过：只删真正被压缩的那些，不误删新记录。 */
    @Test
    void memoryCompressionRemovesExactlyTheCompressedEntries() {
        Memory m = new Memory(tmp.resolve("memory.json"));
        for (int i = 0; i < 49; i++) m.add(new Memory.Entry("t", "旧" + i, "local", "s", List.of(), null));
        List<Memory.Entry> old = m.toCompress(40, 10);
        assertEquals(39, old.size());
        for (int i = 0; i < 5; i++) m.add(new Memory.Entry("t", "新" + i, "cloud", "s", List.of(), null)); // 超过 50 从头部裁掉 4 条
        m.applyCompression(old, "摘要");
        List<Memory.Entry> left = m.recent(100);
        assertEquals(15, left.size(), left.toString());
        assertEquals(5, left.stream().filter(e -> e.trigger().startsWith("新")).count(), "新记录一条不少");
        assertEquals("摘要", m.digest());
    }

    @Test
    void onceTaskCancelIsTruthful() {
        AtomicInteger runs = new AtomicInteger();
        OnceTask<Integer> a = new OnceTask<>(runs::incrementAndGet);
        assertTrue(a.cancel(), "还没开始：能取消");
        assertThrows(CancellationException.class, a::get);
        assertEquals(0, runs.get(), "取消后不会再执行");
        OnceTask<Integer> b = new OnceTask<>(() -> {
            runs.incrementAndGet();
            return 1;
        });
        assertEquals(1, b.get());
        assertFalse(b.cancel(), "已经执行过：取消必须如实返回 false");
    }

    private static ProviderConfig claude() {
        ProviderConfig p = new ProviderConfig();
        p.type = ProviderConfig.ANTHROPIC;
        p.baseUrl = "https://relay.example/";
        p.apiKey = "sk-test";
        p.model = "m";
        p.maxTokens = 100;
        p.timeoutSeconds = 5;
        return p;
    }

    /** 不跟随跳转（密钥不会被带去别处）；超时不重试（单步最长就是 timeout_seconds）；Retry-After 太长不干等。 */
    @Test
    void modelRequestsDoNotFollowRedirectsOrRetryTimeouts() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpTransport redirect = (m, u, h, b, t) -> {
            calls.incrementAndGet();
            return new HttpTransport.Response(307, "");
        };
        Llm.Session s = Providers.open(claude(), List.of(), redirect);
        Llm.LlmException e = assertThrows(Llm.LlmException.class, () -> s.start("sys", "hi"));
        assertTrue(e.getMessage().contains("跳转"), e.getMessage());
        assertEquals(1, calls.get());

        calls.set(0);
        HttpTransport slow = (m, u, h, b, t) -> {
            calls.incrementAndGet();
            throw new java.net.http.HttpTimeoutException("request timed out");
        };
        Llm.Session s2 = Providers.open(claude(), List.of(), slow);
        assertTrue(assertThrows(Llm.LlmException.class, () -> s2.start("sys", "hi")).getMessage().contains("超时"));
        assertEquals(1, calls.get(), "超时不重试");

        calls.set(0);
        HttpTransport limited = (m, u, h, b, t) -> {
            calls.incrementAndGet();
            return new HttpTransport.Response(429, "{\"error\":\"rate\"}", 120L);
        };
        Llm.Session s3 = Providers.open(claude(), List.of(), limited);
        long t0 = System.currentTimeMillis();
        assertThrows(Llm.LlmException.class, () -> s3.start("sys", "hi"));
        assertEquals(1, calls.get(), "要等 120 秒：不干等、不重试");
        assertTrue(System.currentTimeMillis() - t0 < 1500);
    }

    @Test
    void writeQuietlyReportsFailures() throws Exception {
        JsonFiles.setErrorSink((e, m) -> reported.add(e));
        Path dirAsFile = tmp.resolve("blocked");
        Files.writeString(dirAsFile, "x");
        assertNotNull(JsonFiles.writeQuietly(dirAsFile.resolve("state.json"), "{}"));
        assertEquals(List.of("state.save_failed"), reported);
        assertNull(JsonFiles.writeQuietly(tmp.resolve("ok.json"), "{}"));
    }
}
