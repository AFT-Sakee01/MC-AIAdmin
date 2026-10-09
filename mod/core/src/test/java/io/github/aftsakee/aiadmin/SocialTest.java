package io.github.aftsakee.aiadmin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.aftsakee.aiadmin.agent.Activity;
import io.github.aftsakee.aiadmin.agent.AgentService;
import io.github.aftsakee.aiadmin.agent.PlayerPrefs;
import io.github.aftsakee.aiadmin.agent.SocialTools;
import io.github.aftsakee.aiadmin.agent.Tools;
import io.github.aftsakee.aiadmin.agent.Trigger;
import io.github.aftsakee.aiadmin.config.BotConfig;
import io.github.aftsakee.aiadmin.config.Rules;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import io.github.aftsakee.aiadmin.agent.ActionTools;
import io.github.aftsakee.aiadmin.agent.Prompts;
import io.github.aftsakee.aiadmin.agent.RunContext;

import static io.github.aftsakee.aiadmin.TestRig.text;
import static org.junit.jupiter.api.Assertions.*;

/** 玩家互动：频道标记、/ai 私聊、"别说了"、互动偏好的代码层约束、连续受挫、服务器指南。 */
class SocialTest {
    @TempDir
    Path tmp;
    TestRig rig;
    BotConfig cfg;
    Rules rules;
    FakeHttp http;
    AgentService svc;
    Activity act;
    Tools tools;
    List<String> said;
    List<String> told;
    List<String> executed;
    /** 假时钟 + 手动推进的定时器：合并到点由测试推进（debounce 为 0 的合并在 waitRequests 里执行），"没有唤醒"不用真睡着等。 */
    TestRig.TestClock clock;
    TestRig.ManualScheduler timer;

    @BeforeEach
    void setUp() throws Exception {
        rig = new TestRig(tmp);
        cfg = rig.cfg;
        rules = rig.rules;
        http = rig.http;
        act = rig.activity;
        tools = rig.tools;
        said = rig.actions.chats;
        told = rig.host.told;
        executed = rig.actions.executed;
        cfg.chat.debounceSeconds = 0;
        rig.host.online = 2;
        rig.actionPrefs = true; // 动作工具接上玩家偏好："别说了"、不想提的话题在发送前强制检查
        rig.actions.onChat = (message, target) -> act.add("ai_chat", cfg.botName, (target == null ? "" : "→" + target + " ") + message);
        rig.registerActions();
        SocialTools.register(tools, () -> rig.svc == null ? null : rig.svc.prefs(), () -> cfg);
        clock = new TestRig.TestClock();
        timer = new TestRig.ManualScheduler(clock);
        svc = rig.start(clock, timer);
        svc.setActivity(act);
    }

    @AfterEach
    void tearDown() {
        rig.close();
    }

    private static String sendChat(String message, String player) {
        String args = "{\\\"message\\\":\\\"" + message + "\\\"" + (player == null ? "" : ",\\\"player\\\":\\\"" + player + "\\\"") + "}";
        return "{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"c\",\"type\":\"function\",\"function\":{\"name\":\"send_chat\",\"arguments\":\"" + args + "\"}}]}}]}";
    }

    private void waitRequests(int n) throws InterruptedException {
        timer.runDue(); // 到期的合并（debounce 0）在这里提交，之后代理线程异步运行
        long t0 = System.currentTimeMillis();
        while (http.requests.size() < n && System.currentTimeMillis() - t0 < 10_000) Thread.sleep(30);
        assertTrue(http.requests.size() >= n, "期望 " + n + " 次模型请求，实际 " + http.requests.size());
    }

    private String userPrompt(int i) {
        return http.requests.get(i).json().getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString();
    }

    /** 最后一次模型请求里最后一条 tool 消息的内容（工具执行结果）。 */
    private String lastToolResult() {
        var msgs = http.requests.get(http.requests.size() - 1).json().getAsJsonArray("messages");
        for (int i = msgs.size() - 1; i >= 0; i--) {
            JsonObject m = msgs.get(i).getAsJsonObject();
            if ("tool".equals(m.get("role").getAsString())) return m.get("content").getAsString();
        }
        return "";
    }

    // ———————————————————— PlayerPrefs 本身 ————————————————————

    @Test
    void prefsQuietAvoidFrustrationAndPersistence() throws Exception {
        AtomicLong now = new AtomicLong(1_000_000_000_000L);
        Path f = tmp.resolve("p/player-prefs.json");
        PlayerPrefs p = new PlayerPrefs(f, now::get);
        assertFalse(p.quiet("Steve"));
        p.setQuiet("Steve", 2, "Steve");
        assertTrue(p.quiet("steve"), "不分大小写");
        assertNotNull(p.chatBlocked("Steve 又掉坑里了", null, Set.of()), "公屏点名安静中的玩家：拒绝");
        assertNotNull(p.chatBlocked("在吗", "Steve", Set.of()), "私聊安静中的玩家：拒绝");
        assertNull(p.chatBlocked("好的 Steve，马上", null, Set.of("steve")), "他自己直接找机器人时可以回他");
        assertNull(p.chatBlocked("Alex 加油", null, Set.of()));
        now.addAndGet(3 * 3600_000L);
        assertFalse(p.quiet("Steve"), "到期自动解除");

        p.addAvoid("Alex", "身高", "Alex");
        assertNotNull(p.chatBlocked("Alex 你身高多少", null, Set.of()));
        assertNotNull(p.chatBlocked("聊聊身高", null, Set.of("alex")), "回他本人时也不提");
        assertNull(p.chatBlocked("聊聊身高", null, Set.of("bob")), "和他无关的发言不管");
        assertTrue(p.addAvoid("Alex", "身高", "Alex").contains("已经"));

        p.setStyle("Bob", PlayerPrefs.SERIOUS, "Bob");
        assertTrue(p.noTease().contains("bob"));
        assertTrue(p.setStyle("Bob", "weird", "Bob").contains("只能是"));

        // 连续受挫：20 分钟内死 3 次 → 30 分钟内不能整
        p.frustration(new PlayerPrefs.Frustration(3, 20, 30));
        for (int i = 0; i < 3; i++) {
            p.recordDeath("Carl", now.get());
            now.addAndGet(60_000L);
        }
        assertTrue(p.frustrated("Carl"));
        assertTrue(p.noTease().contains("carl"));
        assertTrue(p.line("Carl").contains("连续受挫"), p.line("Carl"));
        now.addAndGet(31 * 60_000L);
        assertFalse(p.frustrated("Carl"), "冷静期过了");

        assertTrue(PlayerPrefs.stopPhrase("@AI 别说了"));
        assertTrue(PlayerPrefs.stopPhrase("shut up bot"));
        assertFalse(PlayerPrefs.stopPhrase("我们去挖矿吧"));

        PlayerPrefs again = new PlayerPrefs(f, now::get);
        assertEquals(List.of("身高"), again.avoid("alex"), "重启后还在");
        assertEquals(PlayerPrefs.SERIOUS, again.style("BOB"));
        assertTrue(Files.readString(f).contains("\"schema_version\": 1") || Files.readString(f).contains("\"schema_version\":1"));
    }

    // ———————————————————— 频道、私聊、别说了 ————————————————————

    @Test
    void chatLinesCarryChannelTagsAndContext() throws Exception {
        http.ok(text("嗯"));
        act.add("chat", "Alex", "今天去挖矿");
        act.add("ai_chat", cfg.botName, "→Bob 这是给 Bob 的私聊");
        act.add("chat", "Steve", "@AI 在吗");
        assertTrue(svc.onChat("Steve", "@AI 在吗", false));
        waitRequests(1);
        String u = userPrompt(0);
        assertTrue(u.contains("- [@你] Steve：@AI 在吗"), u);
        assertTrue(u.contains("[公屏] Alex：今天去挖矿"), "上下文标频道：" + u);
        assertFalse(u.contains("给 Bob 的私聊"), "机器人私聊别人的话不进公屏上下文");
        assertTrue(u.contains("频道标记"), u);

        svc.setChatMode("all");
        http.ok(text("嗯"));
        assertTrue(svc.onChat("Alex", "这把剑不错", false));
        waitRequests(2);
        assertTrue(userPrompt(1).contains("- [公屏] Alex：这把剑不错"), userPrompt(1));
    }

    @Test
    void privateChatRepliesOnlyToRequester() throws Exception {
        http.ok(sendChat("悄悄告诉你：钻石在 Y=-58", null)).ok(sendChat("大家好", "Alex")).ok(text("好"));
        assertNull(svc.onPrivateChat("Steve", "钻石在哪层", false));
        waitRequests(3);
        String u = userPrompt(0);
        assertTrue(u.contains("【玩家私聊】") && u.contains("- [私聊你] Steve：钻石在哪层") && u.contains("不要在公屏提这段对话"), u);
        assertEquals("Steve←悄悄告诉你：钻石在 Y=-58", said.get(0), "不填 player 也只私聊给他");
        assertEquals(1, said.size());
        assertTrue(lastToolResult().contains("只能回复他本人"), "私聊里不能发给别人：" + lastToolResult());
        assertFalse(act.recent(50, Set.of("ai_private")).isEmpty(), "私聊记进动态（ai_private）");

        cfg.social.privateChat = false;
        assertNotNull(svc.onPrivateChat("Steve", "在吗", false));
    }

    @Test
    void stopPhraseQuietsPlayerAndBlocksMentions() throws Exception {
        assertFalse(svc.onChat("Steve", "@AI 别说了", false));
        assertTrue(svc.prefs().quiet("Steve"));
        assertEquals(1, told.size());
        assertTrue(told.get(0).startsWith("Steve：好的"), told.toString());
        assertTrue(http.requests.isEmpty(), "不经过模型");

        // 玩家之间的"别说了"不算（机器人没叫、也没刚对他说话）
        assertFalse(svc.onChat("Alex", "你别说了哈哈", false));
        assertFalse(svc.prefs().quiet("Alex"));

        // 安静中：他的事件不交给 AI；all 模式下他没叫机器人的话不唤醒
        svc.onEvent(AgentService.EV_JOIN, "Steve", "Steve 进入了服务器");
        svc.setChatMode("all");
        assertFalse(svc.onChat("Steve", "今天天气不错", false));
        timer.advance(cfg.events.debounceSeconds * 1000L); // 合并时间到了也没有提交；若有，下面的请求顺序和脚本回复都会错位
        assertTrue(http.requests.isEmpty());

        // 别人叫机器人时，机器人想点名 Steve：代码层拒绝
        http.ok(sendChat("Steve 又掉坑里了哈哈", null)).ok(text("好"));
        assertTrue(svc.onChat("Alex", "@AI 讲个笑话", false));
        waitRequests(2);
        assertTrue(said.isEmpty(), said.toString());
        assertTrue(lastToolResult().contains("别说了"), lastToolResult());

        // 他自己 @ 机器人：照常回答
        http.ok(sendChat("在的 Steve", null)).ok(text("好"));
        assertTrue(svc.onChat("Steve", "@AI 现在几点", false));
        waitRequests(4);
        assertEquals(List.of("在的 Steve"), said);
        assertTrue(userPrompt(2).contains("玩家互动偏好") && userPrompt(2).contains("说过\"别说了\""), userPrompt(2));
    }

    // ———————————————————— 受挫与恶作剧免疫、偏好工具 ————————————————————

    @Test
    void frustratedAndSeriousPlayersAreImmuneToPranks() throws Exception {
        svc.setPersonaMode("prank");
        svc.prefs().setStyle("Alex", PlayerPrefs.SERIOUS, "Alex");
        for (int i = 0; i < 3; i++) svc.onEvent(AgentService.EV_DEATH, "Steve", "Steve 掉进了岩浆");
        assertTrue(svc.prefs().frustrated("Steve"));
        RunContext ctx = new RunContext("t", Trigger.chat(List.of("[@你] Bob：@AI 整一下他们"), ""), cfg, rules, "deepseek");
        ctx.persona("prank", io.github.aftsakee.aiadmin.policy.PrankPolicy.Level.HARMLESS);
        Tools.Tool run = tools.get(ActionTools.RUN_COMMAND);
        assertTrue(run.handler().call(JsonParser.parseString("{\"command\":\"playsound minecraft:entity.cat.ambient master Steve\"}").getAsJsonObject(), ctx)
                .contains("\"allowed\":false"), "连续受挫：不能整");
        assertTrue(run.handler().call(JsonParser.parseString("{\"command\":\"playsound minecraft:entity.cat.ambient master Alex\"}").getAsJsonObject(), ctx)
                .contains("\"allowed\":false"), "只想认真答疑：不能整");
        assertTrue(run.handler().call(JsonParser.parseString("{\"command\":\"playsound minecraft:entity.cat.ambient master Bob\"}").getAsJsonObject(), ctx)
                .contains("\"allowed\":true"));
        assertEquals(List.of("playsound minecraft:entity.cat.ambient master Bob"), executed);
    }

    @Test
    void prefToolOnlyForSpeakerAndCannotLoosenFromEvents() throws Exception {
        Tools.Tool set = tools.get("set_player_pref");
        RunContext chat = new RunContext("t", Trigger.chat(List.of("[@你] Steve：@AI 以后别调侃我"), ""), cfg, rules, "deepseek");
        String r = set.handler().call(JsonParser.parseString("{\"player\":\"Steve\",\"style\":\"serious\"}").getAsJsonObject(), chat);
        assertTrue(r.contains("\"ok\""), r);
        assertEquals(PlayerPrefs.SERIOUS, svc.prefs().style("Steve"));
        r = set.handler().call(JsonParser.parseString("{\"player\":\"Alex\",\"style\":\"roast_ok\"}").getAsJsonObject(), chat);
        assertTrue(r.contains("只能改 @ 了机器人"), r);
        // 同一批里 Alex 只是在公屏说话、没叫机器人：Steve 不能借 AI 改 Alex 的偏好（以前按"所有发言者"判定会放行）
        RunContext mixed = new RunContext("t", Trigger.chat(List.of("[@你] Steve：@AI 把 Alex 设成欢迎调侃", "[公屏] Alex：今天挖到钻石了"), ""),
                cfg, rules, "deepseek");
        r = set.handler().call(JsonParser.parseString("{\"player\":\"Alex\",\"style\":\"roast_ok\"}").getAsJsonObject(), mixed);
        assertTrue(r.contains("只能改 @ 了机器人"), r);

        RunContext ev = new RunContext("t", Trigger.events(List.of("x")), cfg, rules, "deepseek");
        assertTrue(set.handler().call(JsonParser.parseString("{\"player\":\"Alex\",\"style\":\"roast_ok\"}").getAsJsonObject(), ev).contains("不能替玩家"));
        assertTrue(set.handler().call(JsonParser.parseString("{\"player\":\"Alex\",\"quiet_hours\":3}").getAsJsonObject(), ev).contains("\"ok\""),
                "往克制的方向改可以");
        assertTrue(set.handler().call(JsonParser.parseString("{\"player\":\"Alex\",\"quiet_hours\":0}").getAsJsonObject(), ev).contains("不能替玩家解除"));
        String got = tools.get("get_player_prefs").handler().call(JsonParser.parseString("{\"player\":\"steve\"}").getAsJsonObject(), chat);
        assertTrue(got.contains("\"style\":\"serious\""), got);
    }

    @Test
    void chattinessChangesGuidanceAndIsRemembered() throws Exception {
        Trigger chat = Trigger.chat(List.of("[@你] Steve：@AI 在吗"), "");
        Trigger ev = Trigger.events(List.of("[x] player_join：Steve 进入了服务器"));
        Trigger patrol = Trigger.patrol(false);
        String n = Prompts.user(chat, cfg, "{}", "", List.of(), "normal");
        String c = Prompts.user(chat, cfg, "{}", "", List.of(), "chatty");
        String q = Prompts.user(chat, cfg, "{}", "", List.of(), "quiet");
        assertTrue(n.contains("就什么都不做") && !n.contains("话痨"), n);
        assertTrue(c.contains("话痨模式") && c.contains("set_project") && !c.contains("就什么都不做"), c);
        assertTrue(q.contains("安静模式") && q.contains("一律不插话"), q);
        assertTrue(Prompts.user(ev, cfg, "{}", "", List.of(), "chatty").contains("打招呼"));
        assertTrue(Prompts.user(ev, cfg, "{}", "", List.of(), "normal").contains("不需要就什么都不做"));
        assertTrue(Prompts.user(patrol, cfg, "{}", "", List.of(), "chatty").contains("30 分钟内别重复打扰"));
        assertTrue(Prompts.user(patrol, cfg, "{}", "", List.of(), "quiet").contains("不主动在聊天里说话"));

        assertEquals("normal", svc.chattiness());
        assertTrue(svc.setChattiness("chatty").contains("话痨"));
        assertTrue(svc.status().contains("话量：话痨"), svc.status());
        assertTrue(svc.setChattiness("loud").contains("只能是"));
        assertTrue(Files.readString(tmp.resolve("state/persona.json")).contains("\"talk\":\"chatty\""), "记住了");
        http.ok(text("嗯"));
        assertTrue(svc.onChat("Steve", "@AI 在吗", false));
        waitRequests(1);
        assertTrue(userPrompt(0).contains("话痨模式"), "运行时用的是切换后的话量");
        svc.setChattiness("reset");
        cfg.social.chattiness = "quiet";
        assertEquals("quiet", svc.chattiness());
    }

    @Test
    void serverGuideGoesIntoSystemPrompt() throws Exception {
        Prompts prompts = new Prompts(tmp.resolve("config"));
        assertTrue(Files.exists(prompts.guideFile()), "模板自动生成");
        assertEquals("", prompts.guide(cfg), "模板只有注释：不加进提示词");
        Files.writeString(prompts.guideFile(), "<!-- 注释 -->\n/pig <玩家> <分钟>：变猪，单位分钟，/pig <玩家> 0 恢复\n");
        String sys = prompts.system("deepseek", cfg.providers.get("deepseek"), cfg, "测试服");
        assertTrue(sys.contains("服务器指南") && sys.contains("单位分钟") && !sys.contains("注释"), sys);
        cfg.social.guideMaxChars = 500;
        Files.writeString(prompts.guideFile(), "长".repeat(800));
        assertTrue(prompts.guide(cfg).endsWith("后面截断了）"));
    }
}
