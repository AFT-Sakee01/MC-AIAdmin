package io.github.aftsakee.aiadmin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.aftsakee.aiadmin.agent.AgentService;
import io.github.aftsakee.aiadmin.agent.ServerPower;
import io.github.aftsakee.aiadmin.agent.Tools;
import io.github.aftsakee.aiadmin.config.BotConfig;
import io.github.aftsakee.aiadmin.config.Rules;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static io.github.aftsakee.aiadmin.TestRig.text;
import static org.junit.jupiter.api.Assertions.*;

/** 云端值守：云端优先、本地兜底的路由，以及云端针对消息的动作沿用本地同一套检查。 */
class CloudServiceTest {
    @TempDir
    Path tmp;
    TestRig rig;
    BotConfig cfg;
    Rules rules;
    FakeHttp http;
    AgentService svc;
    List<String> executed;
    List<String> said;
    /** 假时钟 + 手动推进的定时器：合并、兜底、收回的时序完全由测试推进，不用真睡，负载高时也不会误判。 */
    TestRig.TestClock clock;
    TestRig.ManualScheduler timer;

    @BeforeEach
    void setUp() throws Exception {
        rig = new TestRig(tmp);
        cfg = rig.cfg;
        rules = rig.rules;
        http = rig.http;
        executed = rig.actions.executed;
        said = rig.actions.chats;
        cfg.chat.debounceSeconds = 0;
        cfg.remote.enabled = true;
        cfg.remote.cloud.enabled = true;
        cfg.remote.cloud.handoffSeconds = 1;
        clock = new TestRig.TestClock();
        timer = new TestRig.ManualScheduler(clock);
        svc = rig.start(clock, timer);
    }

    @AfterEach
    void tearDown() {
        rig.close();
    }

    static final String ACTIONS = "status\tread\t\t状态\ncheck\tread\t\t检查\nlogs\tread\t<mc|crafty> [行数]\t日志\n"
            + "backup\taction\t\t备份\nrestart\taction\t\t重启\nstop\taction\t\t停服\nbad line\n";

    @Test
    void hostActionsComeFromHostAndAreSplitByAccess() throws Exception {
        List<String> ran = Collections.synchronizedList(new ArrayList<>());
        svc.setServerPower((action, args) -> {
            if (action.equals("actions")) return new ServerPower.Result(0, ACTIONS);
            ran.add(action + (args.isEmpty() ? "" : " " + String.join(" ", args)));
            return new ServerPower.Result(0, "ok " + action);
        });
        assertTrue(call("host_info", "{}").contains("没有开启"), "默认关");
        assertTrue(call("server_power", "{\"action\":\"backup\",\"reason\":\"x\"}").contains("没有开启"));
        cfg.remote.cloud.powerEnabled = true;
        assertTrue(call("host_info", "{}").contains("backup\taction"), "不带 action = 动作清单");
        assertTrue(call("host_info", "{\"action\":\"logs\",\"args\":\"mc 50\"}").contains("ok logs"));
        assertTrue(call("host_info", "{\"action\":\"restart\"}").contains("server_power"), "会改动的动作不能走只读入口");
        assertTrue(call("server_power", "{\"action\":\"status\",\"reason\":\"x\"}").contains("host_info"), "只读动作请用 host_info");
        assertTrue(call("host_info", "{\"action\":\"rm\"}").contains("没有这个动作"), "宿主机清单里没有的动作不转发");
        assertTrue(call("host_info", "{\"action\":\"logs\",\"args\":\"mc;reboot\"}").contains("只能含"), "参数字符集");
        assertTrue(call("host_info", "{\"action\":\"logs\",\"args\":\"a b c d\"}").contains("最多"), "参数个数");
        assertTrue(call("server_power", "{\"action\":\"backup\"}").contains("reason"), "会改动的要写理由");
        assertTrue(call("server_power", "{\"action\":\"restart\",\"reason\":\"卡顿\"}").contains("1 名玩家在线"), "有人在线不重启");
        assertTrue(call("server_power", "{\"action\":\"backup\",\"reason\":\"大改前留档\"}").contains("ok backup"), "备份有人在线也可以");
        rig.host.online = 0;
        assertTrue(call("server_power", "{\"action\":\"restart\",\"reason\":\"TPS 一直很低\"}").contains("ok restart"));
        assertEquals(List.of("logs mc 50", "backup", "restart"), ran, "被挡下的没有调宿主机");
        svc.setServerPower((action, args) -> action.equals("actions") ? new ServerPower.Result(0, ACTIONS)
                : new ServerPower.Result(3, "拒绝：60 分钟内已经启停 3 次"));
        String denied = call("server_power", "{\"action\":\"stop\",\"reason\":\"服主要求\"}");
        assertTrue(denied.contains("60 分钟") && denied.contains("没有执行"), denied);
        svc.setServerPower((action, args) -> new ServerPower.Result(1, "sudo: a password is required"));
        assertTrue(call("host_info", "{\"action\":\"status\"}").contains("password"), "宿主机接口没装好时原样报告");
        String mem = call("memory_read", "{\"entries\":10}");
        assertTrue(mem.contains("大改前留档") && mem.contains("TPS 一直很低") && mem.contains("服主要求"), "备份/重启/停服都进行动记录：" + mem);
    }

    @Test
    void serverPowerRunnerValidatesBeforeSpawning() throws Exception {
        // 正式的 sudo 调用只接受固定字符集；这里不真的起进程（Windows 也有 sudo，会弹提权）
        assertEquals(2, ServerPower.sudo().run("check; rm -rf /", List.of()).exit());
        assertEquals(2, ServerPower.sudo().run("logs", List.of("$(reboot)")).exit());
        assertEquals(2, ServerPower.sudo().run("logs", List.of("a", "b", "c", "d")).exit());
    }

    @Test
    void settingsAreReadAndWrittenOnlyWithinAllowlist() throws Exception {
        Path file = tmp.resolve("config/config.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, io.github.aftsakee.aiadmin.config.ConfigStore.GSON.toJson(cfg));
        java.util.concurrent.atomic.AtomicInteger reloads = new java.util.concurrent.atomic.AtomicInteger();
        svc.setReloader(() -> {
            reloads.incrementAndGet();
            return null;
        });
        String got = call("get_settings", "{}");
        assertTrue(got.contains("\"patrol.interval_minutes\":15") && !got.contains("api_key") && !got.contains("remote."), got);
        assertTrue(call("set_setting", "{\"key\":\"patrol.interval_minutes\",\"value\":\"10\"}").contains("reason"));
        String ok = call("set_setting", "{\"key\":\"patrol.interval_minutes\",\"value\":\"10\",\"reason\":\"服主要求巡检勤一点\"}");
        assertTrue(ok.contains("15 → 10"), ok);
        assertTrue(Files.readString(file).contains("\"interval_minutes\": 10"));
        assertTrue(Files.readString(tmp.resolve("config/config.json.bak-setting")).contains("\"interval_minutes\": 15"), "改前备份");
        String kw = call("set_setting", "{\"key\":\"events.chat_keywords\",\"value\":\"钻石, 下界\",\"reason\":\"x\"}");
        assertTrue(kw.contains("[\"钻石\",\"下界\"]"), kw);
        cfg.remote.settableSettings.add("remote.enabled");
        cfg.remote.settableSettings.add("providers.deepseek.api_key");
        cfg.remote.settableSettings.add("persona.prank.level");
        for (String k : List.of("remote.enabled", "providers.deepseek.api_key", "persona.prank.level", "limits.queue_max", "patrol.nope")) {
            String r = call("set_setting", "{\"key\":\"" + k + "\",\"value\":\"1\",\"reason\":\"x\"}");
            assertTrue(r.startsWith("没有修改"), k + "：" + r);
        }
        assertTrue(call("set_setting", "{\"key\":\"patrol.enabled\",\"value\":\"maybe\",\"reason\":\"x\"}").contains("true 或 false"), "类型要对");
        assertTrue(call("set_setting", "{\"key\":\"chat.mode\",\"value\":\"everything\",\"reason\":\"x\"}").startsWith("没有修改"),
                "通不过正式校验的值不写");
        assertFalse(Files.readString(file).contains("everything"));
        assertTrue(Files.readString(file).contains("\"enabled\": true"), "remote.enabled 没被改动");
        long t0 = System.currentTimeMillis();
        while (reloads.get() < 2 && System.currentTimeMillis() - t0 < 5000) Thread.sleep(50);
        assertEquals(2, reloads.get(), "两次成功修改各重读一次");
        assertTrue(call("memory_read", "{}").contains("服主要求巡检勤一点"));
    }

    private String call(String tool, String argsJson) throws Exception {
        Tools.Tool t = svc.cloudTools().get(tool);
        assertNotNull(t, tool);
        return t.handler().call(JsonParser.parseString(argsJson).getAsJsonObject(), null);
    }

    private JsonObject waitItems(int seconds) throws Exception {
        return JsonParser.parseString(call("wait_events", "{\"timeout_seconds\":" + seconds + "}")).getAsJsonObject();
    }

    @Test
    void onlineCloudGetsChatAndActsWithSameChecks() throws Exception {
        svc.cloudHeartbeat();
        assertTrue(svc.onChat("Steve", "@AI 帮我把天调成白天", false));
        timer.runDue(); // 合并（debounce 0）→ 进收件箱
        JsonObject w = waitItems(5);
        var items = w.getAsJsonArray("items");
        assertEquals(1, items.size(), w.toString());
        JsonObject it = items.get(0).getAsJsonObject();
        assertEquals("chat", it.get("kind").getAsString());
        String ins = it.get("instructions").getAsString();
        assertTrue(ins.contains("Steve：@AI 帮我把天调成白天") && ins.contains("普通玩家"), ins);
        String id = it.get("item_id").getAsString();

        assertEquals("已发送", call("reply_chat", "{\"item_id\":\"" + id + "\",\"message\":\"好的\"}"));
        assertTrue(call("run_command_for", "{\"item_id\":\"" + id + "\",\"command\":\"time set day\"}").contains("\"allowed\":true"));
        assertTrue(call("run_command_for", "{\"item_id\":\"" + id + "\",\"command\":\"stop\"}").contains("\"allowed\":false"), "黑名单照样生效");
        assertEquals("已记录", call("complete_item", "{\"item_id\":\"" + id + "\",\"summary\":\"回复了 Steve 并调成白天\"}"));
        assertTrue(call("reply_chat", "{\"item_id\":\"" + id + "\",\"message\":\"x\"}").contains("不能再为它执行动作"), "完成后不能再动作");
        assertEquals(List.of("time set day"), executed);
        assertEquals(List.of("好的"), said);
        timer.advance(1500); // 超过 handoff：已被云端取走，不会再交给本地
        assertTrue(svc.status().contains("转本地 0"), svc.status());
        assertTrue(http.requests.isEmpty(), "本地 API 没被调用");
        String mem = call("memory_read", "{}");
        assertTrue(mem.contains("云端·玩家聊天") && mem.contains("回复了 Steve") && mem.contains("time set day"), mem);
        assertTrue(svc.status().contains("云端值守：在线"), svc.status());
    }

    @Test
    void notTakenInTimeFallsBackToLocal() throws Exception {
        http.ok(text("本地来回答"));
        svc.cloudHeartbeat();
        svc.onEvent(AgentService.EV_DEATH, "Alex", "掉进岩浆");
        timer.advance(cfg.events.debounceSeconds * 1000L); // 事件合并 → 进收件箱
        timer.advance(999);
        assertTrue(svc.status().contains("转本地 0"), "handoff 秒数没到不交本地：" + svc.status());
        timer.advance(1);
        assertEquals(1, http.awaitRequests(1, 8000), "云端没取走 → 本地接手");
        assertTrue(waitItems(0).getAsJsonArray("items").isEmpty(), "转本地的不会再给云端");
        assertTrue(svc.status().contains("转本地 1"), svc.status());
    }

    @Test
    void offlineCloudOrNoActionsMeansLocal() throws Exception {
        http.ok(text("一")).ok(text("二"));
        svc.onChat("Steve", "@AI 在吗", false); // 云端从没来过
        timer.runDue();
        assertEquals(1, http.awaitRequests(1, 8000));

        cfg.remote.allowActions = false; // 云端不能回复时不交给云端
        svc.cloudHeartbeat();
        svc.onChat("Alex", "@AI 你好", false);
        timer.runDue();
        assertEquals(2, http.awaitRequests(2, 8000));
    }

    @Test
    void prankLevelAppliesToCloudChatReplies() throws Exception {
        svc.setPersonaMode("prank");
        svc.setPrankLevel("chat_only");
        svc.cloudHeartbeat();
        svc.onChat("Steve", "@AI 整我一下", false);
        timer.runDue();
        String id = waitItems(5).getAsJsonArray("items").get(0).getAsJsonObject().get("item_id").getAsString();
        String r = call("run_command_for", "{\"item_id\":\"" + id + "\",\"command\":\"playsound minecraft:entity.cat.ambient master Steve\"}");
        assertTrue(r.contains("\"allowed\":false"), "只动嘴档：云端也不能执行指令 " + r);
        assertTrue(executed.isEmpty());
        assertTrue(call("get_bot_prompt", "{}").contains("测试服"));
        assertTrue(call("bot_control", "{\"action\":\"prank_level\",\"value\":\"harmless\"}").contains("无害"));
        assertTrue(call("bot_control", "{\"action\":\"prank_level\",\"value\":\"rough\"}").contains("不能"), "设置文件是 harmless：不能调到 rough");
        cfg.persona.mode = "normal";
        svc.setPersonaMode("normal");
        assertTrue(call("bot_control", "{\"action\":\"persona_mode\",\"value\":\"prank\"}").contains("不能"), "云端不能切进恶作剧模式");
        // 大小写绕过（生效时会转小写）：同样拦下；不认识的档位也不放行
        assertTrue(call("bot_control", "{\"action\":\"persona_mode\",\"value\":\"Prank\"}").contains("不能"), "Prank");
        assertTrue(call("bot_control", "{\"action\":\"prank_level\",\"value\":\"ROUGH\"}").contains("不能"), "ROUGH");
        assertTrue(call("bot_control", "{\"action\":\"prank_level\",\"value\":\"extreme\"}").contains("不能"), "未知档位");
        assertEquals("normal", svc.personaMode());
    }

    /** 推送通道（MCP Events 订阅）：没有心跳也算在线；推送送达的消息等到 webhook_handoff 才交本地，没送达的按 handoff。 */
    @Test
    void pushSubscriptionCountsAsOnlineAndDeliveredItemsWaitLonger() throws Exception {
        cfg.remote.cloud.webhookHandoffSeconds = 3;
        List<String> pushed = Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.atomic.AtomicBoolean ack = new java.util.concurrent.atomic.AtomicBoolean(true);
        svc.cloudPush(new AgentService.CloudPush() {
            public boolean active() {
                return true;
            }

            public void publish(io.github.aftsakee.aiadmin.agent.CloudInbox.Item it, int onlinePlayers) {
                pushed.add(it.id + "/" + it.trigger.kind() + "/" + onlinePlayers);
                if (ack.get()) svc.cloudDelivered(it.id); // 对方 2xx
            }

            public String status() {
                return "推送订阅 1 个";
            }
        });
        http.ok(text("本地")).ok(text("本地2"));
        assertTrue(svc.status().contains("在线（推送）") && svc.status().contains("推送订阅 1 个"), svc.status());
        assertTrue(svc.onChat("Steve", "@AI 在吗", false));
        timer.runDue(); // 合并 → 进收件箱 → 推送
        assertEquals(1, pushed.size());
        assertTrue(pushed.get(0).endsWith("/CHAT/1"), pushed.get(0));
        timer.advance(1800); // handoff 1 秒已过（推送已送达：改为等到放入后 3 秒）
        assertTrue(svc.status().contains("转本地 0"), svc.status());
        assertTrue(http.requests.isEmpty(), "推送已送达：handoff 秒数过了仍等云端来取");
        assertEquals(1, waitItems(0).getAsJsonArray("items").size(), "消息还在收件箱");

        ack.set(false); // 推送没被确认：按 handoff 秒交本地
        assertTrue(svc.onChat("Alex", "@AI 你好", false));
        timer.runDue();
        timer.advance(999);
        assertTrue(svc.status().contains("转本地 0"), "handoff（1 秒）还没到：" + svc.status());
        timer.advance(1); // 正好 1 秒：按 handoff 而不是 webhook_handoff（3 秒）
        assertTrue(svc.status().contains("转本地 1"), svc.status());
        assertEquals(1, http.awaitRequests(1, 8000), "没送达的消息 handoff 后交本地");

        svc.cloudPush(null);
        assertFalse(svc.status().contains("（推送）"), svc.status()); // 刚才的 wait_events 算心跳，仍可能"在线"，但不再是推送
    }

    @Test
    void waitWithoutCloudEnabledExplains() throws Exception {
        cfg.remote.cloud.enabled = false;
        assertTrue(call("wait_events", "{\"timeout_seconds\":1}").contains("没有开启"));
        assertTrue(Files.exists(tmp.resolve("config")));
    }
}
