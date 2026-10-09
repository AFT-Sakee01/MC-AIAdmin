package io.github.aftsakee.aiadmin;

import com.google.gson.JsonParser;
import io.github.aftsakee.aiadmin.agent.ActionTools;
import io.github.aftsakee.aiadmin.agent.Activity;
import io.github.aftsakee.aiadmin.agent.CloudInbox;
import io.github.aftsakee.aiadmin.agent.OwnerTools;
import io.github.aftsakee.aiadmin.agent.PlayerPrefs;
import io.github.aftsakee.aiadmin.agent.RunContext;
import io.github.aftsakee.aiadmin.agent.Tools;
import io.github.aftsakee.aiadmin.agent.Trigger;
import io.github.aftsakee.aiadmin.config.BotConfig;
import io.github.aftsakee.aiadmin.config.ConfigStore;
import io.github.aftsakee.aiadmin.config.Rules;
import io.github.aftsakee.aiadmin.config.SettingsEditor;
import io.github.aftsakee.aiadmin.log.JsonlLog;
import io.github.aftsakee.aiadmin.policy.CommandPolicy;
import io.github.aftsakee.aiadmin.util.CommandPrivacy;
import io.github.aftsakee.aiadmin.util.Text;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.0.0.28 代码审查修复：指令黑名单硬规则与复杂度、玩家指令隐私、IP 脱敏、"别说了"覆盖面、设置编辑器边界、收件箱原子性、宏参数。 */
class BoundaryHardeningTest {
    @TempDir
    Path tmp;

    /** 黑名单清空后也生效的代码层规则（服主改 rules.json 不影响）。 */
    private static CommandPolicy open() {
        Rules.Permissions p = new Rules().permissions;
        p.denyRoots = List.of();
        p.denyPatterns = List.of();
        return new CommandPolicy(p);
    }

    @Test
    void killSelectorsMustExcludePlayersOrNameOne() {
        CommandPolicy p = open();
        for (String c : List.of("kill @e", "kill @e[]", "kill @e[distance=0..]", "kill @e[type=!item]", "kill @e[type=player]",
                "kill @e[type=minecraft:player]", "kill @e[type=#minecraft:skeletons]", "kill @a", "kill @a[]", "kill @a[distance=..5]",
                "kill @r", "kill @a[name=!Steve]", "execute as @a run kill @e[ distance = 0.. ]", "minecraft:kill @e[]")) {
            assertFalse(p.check(c).allowed(), c);
        }
        for (String c : List.of("kill @e[type=minecraft:item]", "kill @e[type=item,distance=..64]", "kill @e[type=!player]",
                "kill @e[ type = zombie ]", "kill @a[name=Steve]", "kill Steve", "kill @s", "kill @p", "execute as Steve run kill @s")) {
            assertTrue(p.check(c).allowed(), c);
        }
    }

    @Test
    void commandBlockDataEditsAreDenied() {
        CommandPolicy p = open();
        for (String c : List.of("data modify block 0 64 0 Command set value \"gamerule sendCommandFeedback false\"",
                "data modify block 0 64 0 auto set value 1b", "data merge block 1 2 3 {auto:1b}", "data merge block 1 2 3 {\"Command\":\"op x\"}",
                "data modify entity @e[type=command_block_minecart,limit=1] Command set value \"x\"", "execute run data merge block 1 2 3 {auto:1b}",
                "data merge block 1 2 3 {\"\\u0043ommand\":\"op x\"}")) {
            assertFalse(p.check(c).allowed(), c);
        }
        for (String c : List.of("data modify block 0 64 0 Items append value {id:\"minecraft:stone\",count:1}", "data get block 0 64 0 Command",
                "data merge entity @e[type=villager,limit=1] {NoAI:1b}")) {
            assertTrue(p.check(c).allowed(), c);
        }
    }

    /** 以前每层对后面每个 run 递归，16 层 + 2 KB 文本要好几秒；现在线性。 */
    @Test
    void nestedExecuteIsCheckedLinearlyAndCapped() {
        CommandPolicy p = new CommandPolicy(new Rules().permissions);
        String deep = "execute run ".repeat(16) + "say " + "x".repeat(2000);
        long t0 = System.nanoTime();
        assertTrue(p.check(deep).allowed());
        assertTrue(System.nanoTime() - t0 < 2_000_000_000L, "16 层嵌套检查应在毫秒级完成");
        assertFalse(p.check("execute run ".repeat(17) + "say hi").allowed(), "超过 16 层");
        assertFalse(p.check("execute run ".repeat(10) + "stop").allowed(), "深层的禁用指令照样拦");
        assertFalse(p.check("say " + "x".repeat(CommandPolicy.MAX_LENGTH)).allowed(), "超长指令");
        assertEquals(List.of("execute as @a run execute at @s run say hi", "execute at @s run say hi", "say hi"),
                CommandPolicy.layers("execute as @a run execute at @s run say hi"));
        assertEquals(List.of("say run stop"), CommandPolicy.layers("say run stop"), "非 execute 的 run 只是文本");
        assertTrue(p.check("say run stop").allowed());
    }

    @Test
    void playerCommandPrivacy() {
        assertEquals(new CommandPrivacy.Entry("whisper", "私聊了 Bob（内容不记录）"), CommandPrivacy.describe("/msg Bob 我的秘密"));
        CommandPrivacy.Entry ex = CommandPrivacy.describe("execute as @s run minecraft:tell Bob 秘密");
        assertEquals("whisper", ex.kind());
        assertFalse(ex.text().contains("秘密"), ex.text());
        for (String c : List.of("/login hunter2", "/l hunter2", "register hunter2 hunter2", "/changepassword old new",
                "execute as @s run login hunter2")) {
            CommandPrivacy.Entry e = CommandPrivacy.describe(c);
            assertFalse(e.text().contains("hunter2") || e.text().contains("new"), c + " → " + e.text());
        }
        assertNull(CommandPrivacy.describe("/ai 你好"));
        assertEquals("/tp Steve 0 64 0", CommandPrivacy.describe("/tp Steve 0 64 0").text());
        assertEquals("/execute as @a run tp @s 0 0 0", CommandPrivacy.describe("execute as @a run tp @s 0 0 0").text());
        assertEquals("whisper", CommandPrivacy.describe("tm 集合").kind());
    }

    @Test
    void redactsPlayerIpsButNotVersions() {
        String log = "Steve[/203.0.113.5:54321] logged in with entity id 1\nAlex (/[2001:db8::1]:5555) lost connection\n"
                + "{\"ip\": \"198.51.100.7\", \"created\": \"x\"}\nLoading NeoForge 21.1.235 for 1.21.1";
        String r = Text.redactIps(log);
        assertFalse(r.contains("203.0.113.5") || r.contains("2001:db8") || r.contains("198.51.100.7"), r);
        assertTrue(r.contains("Steve[/[IP已隐藏]] logged in") && r.contains("21.1.235") && r.contains("1.21.1"), r);
    }

    private Tools actionTools(BotConfig cfg, Rules rules, PlayerPrefs prefs, TestRig.FakeActions actions) {
        Tools tools = new Tools();
        ActionTools.register(tools, actions, () -> cfg, () -> rules, new JsonlLog(tmp.resolve("logs"), null), new Activity(), () -> prefs);
        return tools;
    }

    /** "别说了"以前只在 send_chat 里检查，用 run_command tell/tellraw 就绕过；远程（云端）身份以前也被豁免。 */
    @Test
    void quietPlayersAreProtectedOnEverySpeechPath() throws Exception {
        BotConfig cfg = new BotConfig();
        Rules rules = new Rules();
        AtomicLong now = new AtomicLong(1_000_000_000L);
        PlayerPrefs prefs = new PlayerPrefs(tmp.resolve("prefs.json"), now::get);
        prefs.setQuiet("Tom", 2, "Tom");
        TestRig.FakeActions actions = new TestRig.FakeActions();
        List<String> executed = actions.executed;
        Tools tools = actionTools(cfg, rules, prefs, actions);
        RunContext chat = new RunContext("c", Trigger.chat(List.of("[@你] Steve：@AI 你好"), ""), cfg, rules, "test");
        for (String c : List.of("tell Tom 在吗", "msg tom hi", "execute as @a run tellraw @a[name=Tom] {\"text\":\"hi\"}",
                "title Tom title {\"text\":\"看这里\"}", "say Tom 快来")) {
            com.google.gson.JsonObject args = new com.google.gson.JsonObject();
            args.addProperty("command", c);
            String r = tools.get(ActionTools.RUN_COMMAND).handler().call(args, chat);
            assertTrue(r.contains("\"allowed\":false") && r.contains("别说了"), c + " → " + r);
        }
        assertTrue(executed.isEmpty());
        String ok = tools.get(ActionTools.RUN_COMMAND).handler().call(JsonParser.parseString("{\"command\":\"say see you tomorrow\"}").getAsJsonObject(), chat);
        assertTrue(ok.contains("\"allowed\":true"), "整词匹配：tomorrow 不算提到 Tom " + ok);
        RunContext remote = new RunContext("r", Trigger.remote("cloud"), cfg, rules, "remote");
        assertThrows(IllegalStateException.class, () -> tools.get(ActionTools.SEND_CHAT).handler()
                .call(JsonParser.parseString("{\"message\":\"Tom 你好\"}").getAsJsonObject(), remote), "远程（云端）也不能点名安静中的玩家");
        RunContext admin = new RunContext("a", Trigger.admin("Owner", "x", null, null), cfg, rules, "test");
        assertEquals("已发送", tools.get(ActionTools.SEND_CHAT).handler().call(JsonParser.parseString("{\"message\":\"Tom 你好\"}").getAsJsonObject(), admin),
                "管理员提问不受限");
    }

    /** 只读模式的兜底：经远程转调到执行指令/说话的处理器一律拒绝（宏就是这样转调的）。 */
    @Test
    void readOnlyRemoteCannotReachActionHandlersIndirectly() throws Exception {
        BotConfig cfg = new BotConfig();
        cfg.remote.allowActions = false;
        Rules rules = new Rules();
        TestRig.FakeActions actions = new TestRig.FakeActions();
        List<String> executed = actions.executed;
        Tools tools = actionTools(cfg, rules, null, actions);
        OwnerTools.register(tools, () -> tmp, new JsonlLog(tmp.resolve("logs"), null));
        Files.writeString(tmp.resolve("macros.json"), "{\"macros\":{\"kick\":{\"commands\":[\"kick Steve\"]}}}");
        RunContext remote = new RunContext("r", Trigger.remote("t"), cfg, rules, "remote");
        assertThrows(IllegalStateException.class, () -> tools.get(OwnerTools.RUN_MACRO).handler()
                .call(JsonParser.parseString("{\"name\":\"kick\"}").getAsJsonObject(), remote));
        assertThrows(IllegalStateException.class, () -> tools.get(ActionTools.SEND_CHAT).handler()
                .call(JsonParser.parseString("{\"message\":\"hi\"}").getAsJsonObject(), remote));
        assertTrue(executed.isEmpty());
        cfg.remote.allowActions = true;
        assertTrue(tools.get(OwnerTools.RUN_MACRO).handler().call(JsonParser.parseString("{\"name\":\"kick\"}").getAsJsonObject(), remote)
                .contains("\"completed\":true"));
    }

    /** 宏的 text 参数：单引号 SNBT（custom_name='…'）里跳不出模板。 */
    @Test
    void macroTextCannotBreakSingleQuotedSnbt() throws Exception {
        BotConfig cfg = new BotConfig();
        Rules rules = new Rules();
        TestRig.FakeActions actions = new TestRig.FakeActions();
        List<String> executed = actions.executed;
        Tools tools = actionTools(cfg, rules, null, actions);
        OwnerTools.register(tools, () -> tmp, new JsonlLog(tmp.resolve("logs"), null));
        Files.writeString(tmp.resolve("macros.json"),
                "{\"macros\":{\"named\":{\"params\":{\"n\":{\"type\":\"text\"}},\"commands\":[\"give Steve stone[custom_name='${n}']\"]}}}");
        RunContext admin = new RunContext("a", Trigger.admin("Owner", "x", null, null), cfg, rules, "test");
        tools.get(OwnerTools.RUN_MACRO).handler().call(JsonParser.parseString(
                "{\"name\":\"named\",\"params\":{\"n\":\"x',enchantments={levels:{sharpness:255}},a='\"}}").getAsJsonObject(), admin);
        assertEquals(1, executed.size());
        assertFalse(executed.get(0).contains("',enchantments"), executed.get(0));
        assertTrue(executed.get(0).contains("x’,enchantments"), "单引号换成全角，整段仍在字符串里：" + executed.get(0));
    }

    @Test
    void stopTalkingNeverShortensALongerQuiet() {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        PlayerPrefs p = new PlayerPrefs(tmp.resolve("p.json"), now::get);
        p.setQuiet("Tom", 24, "Tom");
        p.extendQuiet("Tom", 2, "别说了");
        now.addAndGet(5 * 3600_000L);
        assertNotNull(p.chatBlocked("Tom 你好", null, java.util.Set.of()), "5 小时后仍在 24 小时的安静里");
        p.setQuiet("Tom", 1, "Tom");
        now.addAndGet(2 * 3600_000L);
        assertNull(p.chatBlocked("Tom 你好", null, java.util.Set.of()), "本人明确设短可以缩短");
    }

    /** 设置编辑器：会进提示词的文字、预设模式、压缩永远改不了；并发修改不丢；原始备份只留第一次。 */
    @Test
    void settingsEditorBoundariesAndConcurrency() throws Exception {
        Path file = tmp.resolve("config.json");
        Files.writeString(file, ConfigStore.GSON.toJson(new BotConfig()));
        List<String> allowed = new ArrayList<>(new BotConfig().remote.settableSettings);
        assertFalse(allowed.contains("patrol.extra_instructions"), "默认白名单不再包含巡检额外事项");
        for (String k : List.of("patrol.extra_instructions", "bot_name", "persona.mode", "compression.summary_provider",
                "compression.memory.prompt_file", "providers.deepseek.api_key", "remote.enabled")) {
            allowed.add(k);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> SettingsEditor.set(file, allowed, k, "x"), k);
            assertTrue(e.getMessage().contains("不能由云端修改"), k + "：" + e.getMessage());
        }
        SettingsEditor.set(file, allowed, "patrol.interval_minutes", "10");
        String original = Files.readString(tmp.resolve("config.json.bak-setting-original"));
        assertTrue(original.contains("\"interval_minutes\": 15"));
        Thread a = new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                try {
                    SettingsEditor.set(file, allowed, "patrol.interval_minutes", String.valueOf(20 + i));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        Thread b = new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                try {
                    SettingsEditor.set(file, allowed, "events.cooldown_seconds", String.valueOf(200 + i));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        a.start();
        b.start();
        a.join();
        b.join();
        String now = Files.readString(file);
        assertTrue(now.contains("\"interval_minutes\": 29") && now.contains("\"cooldown_seconds\": 209"), "两边的改动都在：" + now);
        assertEquals(original, Files.readString(tmp.resolve("config.json.bak-setting-original")), "原始备份不被后来的修改覆盖");
        try (var s = Files.list(tmp)) {
            assertTrue(s.noneMatch(x -> x.getFileName().toString().endsWith(".pending") || x.getFileName().toString().endsWith(".tmp")), "临时文件都清掉了");
        }
    }

    /** 收件箱："检查 TAKEN + 建上下文"原子化；收回后云端不能再为它执行动作；带 item_id 的查询会推迟收回。 */
    @Test
    void cloudInboxBeginActionIsAtomicWithReclaim() throws Exception {
        AtomicLong clock = new AtomicLong(1_000_000L);
        CloudInbox box = new CloudInbox(clock::get);
        BotConfig cfg = new BotConfig();
        Rules rules = new Rules();
        CloudInbox.Item a = box.offer(Trigger.chat(List.of("[@你] Steve：hi"), ""), "x", 10);
        CloudInbox.Item b = box.offer(Trigger.chat(List.of("[@你] Alex：hi"), ""), "y", 10);
        assertEquals(2, box.take(0, 5, 3).size());
        clock.addAndGet(100_000);
        assertTrue(box.touch(b.id));
        clock.addAndGet(30_000);
        assertEquals(List.of(a), box.reclaimIdle(120_000), "a 一直没动静被收回；b 30 秒前刚被碰过");
        assertNull(box.beginAction(a.id, it -> new RunContext("x", it.trigger, cfg, rules, "cloud")), "收回交本地后不能再为它执行");
        RunContext ctx = box.beginAction(b.id, it -> new RunContext("y", it.trigger, cfg, rules, "cloud"));
        assertNotNull(ctx);
        assertTrue(ctx == box.beginAction(b.id, it -> null), "同一条消息复用同一个上下文");
        clock.addAndGet(1_000_000);
        assertTrue(box.reclaimIdle(120_000).isEmpty(), "已有动作的不再收回（交给自动关闭）");
    }
}
