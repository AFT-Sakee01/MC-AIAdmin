package io.github.aftsakee.aiadmin;

import io.github.aftsakee.aiadmin.agent.AgentService;
import io.github.aftsakee.aiadmin.agent.AgentServiceAccess;
import io.github.aftsakee.aiadmin.config.BotConfig;
import io.github.aftsakee.aiadmin.config.Rules;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import io.github.aftsakee.aiadmin.agent.Activity;

import static io.github.aftsakee.aiadmin.TestRig.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 调度层：管理员提问全流程、额度、启停限制、余额停用、长期记忆压缩。 */
class AgentServiceTest {
    @TempDir
    Path tmp;
    TestRig rig;
    BotConfig cfg;
    Rules rules;
    FakeHttp http;
    AgentService svc;
    List<String> alerts;
    List<String> executed;
    final BlockingQueue<String> replies = new ArrayBlockingQueue<>(20);

    @BeforeEach
    void setUp() throws Exception {
        rig = new TestRig(tmp);
        cfg = rig.cfg;
        rules = rig.rules;
        http = rig.http;
        alerts = rig.host.alerts;
        executed = rig.actions.executed;
        rig.host.snapshot = "{\"players\":1}";
        svc = rig.start();
    }

    @AfterEach
    void tearDown() {
        rig.close();
    }

    @Test
    void adminAskEndToEnd() throws Exception {
        http.ok("{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"a\",\"type\":\"function\",\"function\":{\"name\":\"run_command\",\"arguments\":\"{\\\"command\\\":\\\"time set day\\\"}\"}}]}}]}")
                .ok(text("已经调成白天"));
        String ack = svc.ask("Steve", "调成白天", null, replies::add);
        assertTrue(ack.startsWith("已收到"), ack);
        assertEquals("已经调成白天", replies.poll(10, TimeUnit.SECONDS));
        assertEquals(List.of("time set day"), executed);
        // 系统提示词来自接入点自己的文件，并替换了服务器名
        String sys = http.requests.get(0).json().getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
        assertTrue(sys.contains("「测试服」"));
        assertTrue(Files.exists(tmp.resolve("state/quota.json")));
        assertTrue(Files.exists(tmp.resolve("state/memory.json")));
    }

    @Test
    void quotaExhaustedBlocksAdminOnlyWhenConfigured() throws Exception {
        rules.quota.windows.get(1).maxRuns = 1; // 每天 1 次
        http.ok(text("一"));
        svc.ask("A", "1", null, replies::add);
        assertEquals("一", replies.poll(10, TimeUnit.SECONDS));
        // 管理员默认不受额度限制
        http.ok(text("二"));
        svc.ask("A", "2", null, replies::add);
        assertEquals("二", replies.poll(10, TimeUnit.SECONDS));
        rules.quota.blockAdminWhenExhausted = true;
        String ack = svc.ask("A", "3", null, replies::add);
        assertTrue(ack.startsWith("拒绝") && ack.contains("今天"), ack);
        assertTrue(alerts.stream().anyMatch(a -> a.contains("用完")), alerts.toString());
    }

    @Test
    void scheduleBlocksAutoButNotAdmin() throws Exception {
        rules.schedule.minOnlinePlayers = 5;
        assertTrue(svc.status().contains("在线人数少于 5"), svc.status());
        rules.schedule.adminBypass = false;
        String ack = svc.ask("A", "x", null, replies::add);
        assertTrue(ack.startsWith("拒绝") && ack.contains("在线人数"), ack);
        rules.schedule.adminBypass = true;
        http.ok(text("ok"));
        assertTrue(svc.ask("A", "x", null, replies::add).startsWith("已收到"));
        assertEquals("ok", replies.poll(10, TimeUnit.SECONDS));
    }

    @Test
    void consecutiveFailuresPauseAndResume() throws Exception {
        rules.schedule.pauseAfterConsecutiveFailures = 2;
        rules.schedule.adminBypass = false;
        http.then(401, "bad").then(401, "bad");
        svc.ask("A", "1", null, replies::add);
        assertTrue(replies.poll(10, TimeUnit.SECONDS).startsWith("失败"));
        svc.ask("A", "2", null, replies::add);
        assertTrue(replies.poll(10, TimeUnit.SECONDS).startsWith("失败"));
        assertTrue(alerts.stream().anyMatch(a -> a.contains("连续失败 2 次")), alerts.toString());
        assertTrue(svc.ask("A", "3", null, replies::add).contains("暂停"));
        assertTrue(svc.resume());
        http.ok(text("恢复了"));
        svc.ask("A", "4", null, replies::add);
        assertEquals("恢复了", replies.poll(10, TimeUnit.SECONDS));
    }

    @Test
    void balanceBelowStopLineDisablesProvider() throws Exception {
        http.ok("{\"is_available\":true,\"balance_infos\":[{\"currency\":\"CNY\",\"total_balance\":\"0.50\",\"granted_balance\":\"0\",\"topped_up_balance\":\"0.50\"}]}");
        BlockingQueue<String> rep = new ArrayBlockingQueue<>(2);
        svc.quotaReport(rep::add);
        String report = rep.poll(10, TimeUnit.SECONDS);
        assertNotNull(report);
        assertTrue(report.contains("0.50 CNY") && report.contains("已停用"), report);
        assertEquals("https://api.deepseek.com/user/balance", http.requests.get(0).url());
        assertTrue(alerts.stream().anyMatch(a -> a.contains("停用")), alerts.toString());
        svc.ask("A", "x", null, replies::add);
        assertTrue(replies.poll(10, TimeUnit.SECONDS).contains("余额不足"));
        // 充值后刷新自动恢复
        http.ok("{\"is_available\":true,\"balance_infos\":[{\"currency\":\"CNY\",\"total_balance\":\"50.00\"}]}");
        svc.quotaReport(rep::add);
        assertTrue(rep.poll(10, TimeUnit.SECONDS).contains("50.00 CNY"));
        assertTrue(alerts.stream().anyMatch(a -> a.contains("重新启用")), alerts.toString());
    }

    /** 审查发现：设置文件写坏时管理员提问仍会用默认规则运行。 */
    @Test
    void lockedServiceRefusesEverything() throws Exception {
        svc.setLockReason("rules.json 格式错误");
        assertTrue(svc.ask("A", "x", null, replies::add).contains("已锁定"));
        assertTrue(svc.status().contains("已锁定"));
        assertTrue(http.requests.isEmpty());
        svc.setLockReason(null);
        http.ok(text("解锁了"));
        svc.ask("A", "x", null, replies::add);
        assertEquals("解锁了", replies.poll(10, TimeUnit.SECONDS));
    }

    /** 审查发现：base_url 写错等 RuntimeException 绕过了失败处理（不计连续失败、不回复）。 */
    @Test
    void runtimeErrorsCountAsFailures() throws Exception {
        // 假传输层没有更多回复时抛 IllegalStateException——和真实环境里 URI.create 抛的 IllegalArgumentException 一样是 RuntimeException
        rules.schedule.pauseAfterConsecutiveFailures = 1;
        rules.schedule.adminBypass = false;
        svc.ask("A", "x", null, replies::add);
        String r = replies.poll(10, TimeUnit.SECONDS);
        assertTrue(r != null && r.startsWith("失败") && r.contains("IllegalStateException"), r);
        assertTrue(alerts.stream().anyMatch(a -> a.contains("连续失败 1 次")), alerts.toString());
    }

    /** 审查发现：跨午夜时段 + 星期限制，午夜后那段被错误拦截。 */
    @Test
    void overnightWindowBelongsToStartDay() {
        Rules.Schedule s = new Rules.Schedule();
        s.activeHours = List.of("20:00-02:00");
        s.activeWeekdays = List.of(5); // 只有周五晚上
        java.time.LocalDateTime fri2130 = java.time.LocalDateTime.of(2026, 10, 9, 21, 30);  // 周五
        java.time.LocalDateTime sat0100 = java.time.LocalDateTime.of(2026, 10, 10, 1, 0);   // 周六凌晨，属于周五晚上
        java.time.LocalDateTime sat2130 = java.time.LocalDateTime.of(2026, 10, 10, 21, 30); // 周六晚上
        java.time.LocalDateTime fri0100 = java.time.LocalDateTime.of(2026, 10, 9, 1, 0);    // 周五凌晨，属于周四晚上
        java.time.LocalDateTime fri1200 = java.time.LocalDateTime.of(2026, 10, 9, 12, 0);
        assertEquals(null, AgentServiceAccess.timeBlock(s, fri2130));
        assertEquals(null, AgentServiceAccess.timeBlock(s, sat0100));
        assertNotNull(AgentServiceAccess.timeBlock(s, sat2130));
        assertNotNull(AgentServiceAccess.timeBlock(s, fri0100));
        assertTrue(AgentServiceAccess.timeBlock(s, fri1200).contains("不在运行时段"));
        s.activeWeekdays = List.of(1); // 周一：周日晚上跨到周一凌晨的部分属于周日
        assertNotNull(AgentServiceAccess.timeBlock(s, java.time.LocalDateTime.of(2026, 10, 12, 1, 0)));  // 周一 01:00 = 周日晚上
        assertEquals(null, AgentServiceAccess.timeBlock(s, java.time.LocalDateTime.of(2026, 10, 13, 1, 0))); // 周二 01:00 = 周一晚上
    }

    /** 玩家聊天：mention 模式只理会叫它的消息，回复用 send_chat；冷却内的消息并入同一批；all / off 模式。 */
    @Test
    void chatModes() throws Exception {
        cfg.chat.debounceSeconds = 1;
        io.github.aftsakee.aiadmin.agent.Activity act = new io.github.aftsakee.aiadmin.agent.Activity();
        svc.setActivity(act);
        act.add("chat", "Alex", "今天挖到钻石了");
        assertTrue(!svc.onChat("Alex", "今天挖到钻石了", false)); // 默认 mention：没叫它
        http.ok("{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"c\",\"type\":\"function\",\"function\":{\"name\":\"send_chat\","
                        + "\"arguments\":\"{\\\"message\\\":\\\"你好 Steve！\\\"}\"}}]}}]}")
                .ok(text("回复了"));
        assertTrue(svc.onChat("Steve", "AI管家 现在几点", false));
        assertTrue(!svc.onChat("Steve", "@AI 还有服务器 TPS", false)); // 冷却内：不另起一次，但并进这一批
        long t0 = System.currentTimeMillis();
        while (http.requests.size() < 2 && System.currentTimeMillis() - t0 < 10_000) Thread.sleep(50);
        String user = http.requests.get(0).json().getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString();
        assertTrue(user.contains("【玩家聊天】") && user.contains("Steve：AI管家 现在几点") && user.contains("Steve：@AI 还有服务器 TPS"), user);
        assertTrue(user.contains("Alex：今天挖到钻石了"), "带上最近聊天上下文：" + user);
        assertTrue(user.contains("不要执行"), user);

        assertEquals("聊天模式已改为 all（reload 或重启后恢复设置文件的 mention）", svc.setChatMode("all"));
        assertTrue(svc.onChat("Bob", "随便说说", false));
        svc.setChatMode("off");
        assertTrue(!svc.onChat("Carl", "AI管家 在吗", false));
        assertTrue(svc.status().contains("聊天：不回应"));
        svc.resetOverrides();
        assertEquals("mention", svc.chatMode());
        cfg.chat.keywords = List.of("小管");
        assertTrue(svc.onChat("Dave", "小管你好", false));
        assertTrue(!svc.onChat("Erin", "AI管家你好", false)); // 自定义关键词后不再用默认词
    }

    private static BotConfig.Condition cond(String type, int cooldown) {
        BotConfig.Condition c = new BotConfig.Condition();
        c.type = type;
        c.cooldownSeconds = cooldown;
        return c;
    }

    /** 默认关键词：@aibot（任意大小写）、@AI、bot_name 都能叫到它；status 里列出 @aibot。 */
    @Test
    void defaultKeywordsIncludeAibot() {
        cfg.chat.debounceSeconds = 60;
        cfg.chat.keywordCooldownSeconds = 0;
        assertTrue(svc.onChat("A", "@aibot 你好", false));
        assertTrue(svc.onChat("B", "@AIBOT 在吗", false));
        assertTrue(svc.onChat("C", "@AI 在吗", false));
        assertTrue(svc.onChat("D", "AI管家 在吗", false));
        assertTrue(!svc.onChat("E", "aibot 在吗", false)); // 不带 @ 也不是 bot_name：不算叫它
        assertTrue(svc.status().contains("@aibot"), svc.status());
    }

    /** custom 模式：热闹度（2 分钟内超过 5 句）触发一次后进入它自己的冷却。 */
    @Test
    void customModeBurst() {
        cfg.chat.mode = "custom";
        cfg.chat.debounceSeconds = 60; // 只测唤醒判断，不让它真的跑
        BotConfig.Condition b = cond("burst", 300);
        b.moreThan = 5;
        b.windowSeconds = 120;
        cfg.chat.conditions = List.of(b);
        for (int i = 1; i <= 5; i++) assertTrue(!svc.onChat("P" + i, "随便聊聊 " + i, false), "第 " + i + " 句还不够");
        assertTrue(svc.onChat("P6", "第六句", false)); // 超过 5 句
        assertTrue(!svc.onChat("P7", "第七句", false)); // 这个条件在冷却中
        assertTrue(svc.onChat("P8", "AI管家 你在吗", false)); // 关键词照样有效（有自己的冷却）
    }

    /** custom 模式的其他条件，以及每个条件各自的冷却。 */
    @Test
    void customModeConditionsAndCooldowns() {
        cfg.chat.mode = "custom";
        cfg.chat.debounceSeconds = 60;
        BotConfig.Condition players = cond("players", 0);
        players.players = List.of("Boss");
        BotConfig.Condition question = cond("question", 60);
        BotConfig.Condition regex = cond("regex", 30);
        regex.pattern = "钻石|下界合金";
        cfg.chat.conditions = List.of(players, question, regex);
        assertTrue(svc.onChat("boss", "我上线了", false));      // 关注的玩家（不分大小写），冷却 0
        assertTrue(svc.onChat("Boss", "再说一句", false));
        assertTrue(svc.onChat("Carl", "有人在吗", false));       // 问句
        assertTrue(!svc.onChat("Carl", "真的没人吗？", false));  // Carl 的问句冷却 60 秒
        assertTrue(svc.onChat("Dan", "现在几点？", false));      // 问句冷却按玩家各自计
        assertTrue(svc.onChat("Eve", "我挖到钻石了", false));    // 正则
        assertTrue(!svc.onChat("Eve", "又挖到钻石了", false));   // 正则冷却 30 秒
        assertTrue(!svc.onChat("Fay", "今天天气不错", false));   // 都不满足
        // 关键词冷却单独可调
        cfg.chat.keywordCooldownSeconds = 0;
        assertTrue(svc.onChat("Gus", "@AI 1", false));
        assertTrue(svc.onChat("Gus", "@AI 2", false));
        cfg.chat.keywordCooldownSeconds = 600;
        assertTrue(svc.onChat("Hal", "@AI 1", false));
        assertTrue(!svc.onChat("Hal", "@AI 2", false));
    }

    /** rules.json 的 chat_safety_off：被授权的人消息带【已授权】，提示词对他们不加安全限制；其他人照旧。 */
    @Test
    void chatSafetyOffForOps() throws Exception {
        cfg.chat.debounceSeconds = 1;
        rules.permissions.chatSafetyOff.ops = true;
        http.ok(text("好")).ok(text("好"));
        assertTrue(svc.onChat("Boss", "@AI 给我 64 个钻石", true));
        assertTrue(svc.onChat("Alex", "@AI 也给我钻石", false));
        long t0 = System.currentTimeMillis();
        while (http.requests.isEmpty() && System.currentTimeMillis() - t0 < 10_000) Thread.sleep(50);
        String user = http.requests.get(0).json().getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString();
        assertTrue(user.contains("Boss【已授权】：@AI 给我 64 个钻石") && user.contains("Alex：@AI 也给我钻石"), user);
        assertTrue(user.contains("可以照做") && user.contains("没有【已授权】标记的玩家是普通玩家"), user);

        // 所有人都授权：不再出现普通玩家的限制
        assertTrue(rules.permissions.chatSafetyOff.appliesTo("x", false) == false);
        rules.permissions.chatSafetyOff.everyone = true;
        assertTrue(rules.permissions.chatSafetyOff.appliesTo("x", false));
        rules.permissions.chatSafetyOff.everyone = false;
        rules.permissions.chatSafetyOff.ops = false;
        rules.permissions.chatSafetyOff.players = List.of("Steve");
        assertTrue(rules.permissions.chatSafetyOff.appliesTo("steve", false) && !rules.permissions.chatSafetyOff.appliesTo("Boss", true));
        rules.permissions.chatSafetyOff.players = List.of();
        rules.permissions.chatSafetyOff.normalPlayers = true;
        assertTrue(rules.permissions.chatSafetyOff.appliesTo("Alex", false) && !rules.permissions.chatSafetyOff.appliesTo("Boss", true));
    }

    /** rules.json quota.chat_limits：按群体/个人分别设置上限，逐级继承。 */
    @Test
    void perGroupChatLimits() {
        cfg.chat.debounceSeconds = 60;
        cfg.chat.keywordCooldownSeconds = 0;
        cfg.chat.maxPerPlayerPerHour = 30;
        Rules.ChatLimits cl = rules.quota.chatLimits;
        cl.defaults.maxPerHour = 2;                 // 所有人每小时 2 次
        cl.ops.maxPerHour = 0;                      // OP 不限
        Rules.ChatLimit steve = new Rules.ChatLimit();
        steve.maxPerDay = 1;                        // Steve 每天 1 次（小时上限继承 defaults 的 2）
        Rules.ChatLimit spam = new Rules.ChatLimit();
        spam.ignore = true;
        cl.players = new java.util.LinkedHashMap<>(java.util.Map.of("Steve", steve, "Spammer", spam));

        assertTrue(svc.onChat("Alex", "@AI 1", false));
        assertTrue(svc.onChat("Alex", "@AI 2", false));
        assertTrue(!svc.onChat("Alex", "@AI 3", false));         // 普通玩家：defaults 每小时 2 次
        for (int i = 0; i < 5; i++) assertTrue(svc.onChat("Boss", "@AI " + i, true)); // OP 不限
        assertTrue(svc.onChat("steve", "@AI 1", false));
        assertTrue(!svc.onChat("Steve", "@AI 2", false));        // 个人：每天 1 次
        assertTrue(!svc.onChat("Spammer", "@AI 你好", false));   // 忽略
        cl.normalPlayers.keywordCooldownSeconds = 3600;   // 普通玩家关键词冷却 1 小时
        cl.defaults.maxPerHour = null;
        assertTrue(svc.onChat("Carl", "@AI 1", false));
        assertTrue(!svc.onChat("Carl", "@AI 2", false));
        assertTrue(svc.onChat("Boss", "@AI again", true)); // OP 不受普通玩家的冷却影响
    }

    @Test
    void chatHourlyLimitPerPlayer() {
        cfg.chat.keywordCooldownSeconds = 0; // 关键词触发用自己的冷却
        cfg.chat.maxPerPlayerPerHour = 2;
        cfg.chat.debounceSeconds = 30; // 不让它真的跑
        assertTrue(svc.onChat("Steve", "@AI 1", false));
        assertTrue(svc.onChat("Steve", "@AI 2", false));
        assertTrue(!svc.onChat("Steve", "@AI 3", false));
        assertTrue(svc.onChat("Alex", "@AI 1", false));
    }

    @Test
    void memoryIsCompressedIntoDigest() throws Exception {
        cfg.compression.memory.compressWhenOver = 3;
        cfg.compression.memory.keepRecent = 1;
        for (int i = 0; i < 4; i++) {
            http.ok(text("第" + i + "次"));
            svc.ask("A", "q" + i, null, replies::add);
            assertNotNull(replies.poll(10, TimeUnit.SECONDS));
        }
        String mem = Files.readString(tmp.resolve("state/memory.json"));
        assertTrue(mem.contains("\"digest\"") && mem.contains("第0次"), mem);
        // 下一次运行的提示词里带着长期记忆摘要
        http.ok(text("ok"));
        svc.ask("A", "q", null, replies::add);
        replies.poll(10, TimeUnit.SECONDS);
        String user = http.requests.get(http.requests.size() - 1).json().getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString();
        assertTrue(user.contains("长期记忆摘要") && user.contains("第0次"), user);
    }
}
