package io.github.aftsakee.aiadmin;

import com.google.gson.JsonObject;
import io.github.aftsakee.aiadmin.agent.ActionTools;
import io.github.aftsakee.aiadmin.agent.Activity;
import io.github.aftsakee.aiadmin.agent.RunContext;
import io.github.aftsakee.aiadmin.agent.Tools;
import io.github.aftsakee.aiadmin.agent.Trigger;
import io.github.aftsakee.aiadmin.config.BotConfig;
import io.github.aftsakee.aiadmin.config.Rules;
import io.github.aftsakee.aiadmin.log.JsonlLog;
import io.github.aftsakee.aiadmin.util.FileGuard;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ActionTools 声明的每一项上限和开关（直接调处理器，不经过"隐藏工具"那条路），以及 FileGuard 的真实路径复查。 */
class ActionLimitsTest {
    @TempDir
    Path tmp;
    BotConfig cfg;
    Rules rules;
    Tools tools;
    final TestRig.FakeActions actions = new TestRig.FakeActions();
    final List<String> executed = actions.executed;
    final List<String> chats = actions.chats;

    @BeforeEach
    void setUp() {
        cfg = new BotConfig();
        rules = new Rules();
        tools = new Tools();
        ActionTools.register(tools, actions, () -> cfg, () -> rules, new JsonlLog(tmp.resolve("logs"), null), new Activity());
    }

    private String chat(RunContext ctx, String message, String player) throws Exception {
        JsonObject a = new JsonObject();
        a.addProperty("message", message);
        if (player != null) a.addProperty("player", player);
        return tools.get(ActionTools.SEND_CHAT).handler().call(a, ctx);
    }

    private String cmd(RunContext ctx, String command) throws Exception {
        JsonObject a = new JsonObject();
        a.addProperty("command", command);
        return tools.get(ActionTools.RUN_COMMAND).handler().call(a, ctx);
    }

    private RunContext patrol() {
        return new RunContext("t", Trigger.patrol(true), cfg, rules, "test");
    }

    @Test
    void chatCountLengthAndControlCharacters() throws Exception {
        rules.quota.perRun.maxChat = 2;
        rules.quota.perRun.maxChatLength = 10;
        RunContext ctx = patrol();
        assertEquals("已发送", chat(ctx, "一二三四五六七八九十十一十二", null));
        assertEquals("一二三四五六七八九十…", chats.get(0), "超长截断");
        assertEquals("已发送", chat(ctx, "a§cb\u0007c", null));
        assertEquals("ab c", chats.get(1), "去掉颜色代码，控制字符换成空格");
        assertTrue(chat(ctx, "第三条", null).contains("上限"), "每次运行的条数上限");
        assertEquals(2, chats.size());
    }

    @Test
    void permissionSwitchesAreCheckedInsideTheHandlers() throws Exception {
        rules.permissions.allowPrivateChat = false;
        assertThrows(IllegalStateException.class, () -> chat(patrol(), "hi", "Steve"), "不许私聊");
        assertEquals("已发送", chat(patrol(), "hi", null), "公告照常");
        rules.permissions.allowChat = false;
        assertThrows(IllegalStateException.class, () -> chat(patrol(), "hi", null));
        rules.permissions.allowCommands = false;
        assertThrows(IllegalStateException.class, () -> cmd(patrol(), "time set day"));
        assertTrue(executed.isEmpty());
        rules.permissions.allowCommands = true;
        rules.quota.perRun.maxCommands = 1;
        RunContext ctx = patrol();
        assertTrue(cmd(ctx, "time set day").contains("\"allowed\":true"));
        assertTrue(cmd(ctx, "weather clear").contains("已用完"), "每次运行的指令上限");
        assertEquals(List.of("time set day"), executed);
    }

    /** 链接/联接点指向禁区或服务器目录外：toRealPath 复查要拦下（Windows 上建符号链接要权限，建不了就跳过）。 */
    @Test
    void fileGuardRechecksRealPathsThroughLinks() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("server"));
        Path secret = Files.createDirectories(root.resolve("config/aiadmin"));
        Files.writeString(secret.resolve("config.json"), "{\"api_key\":\"x\"}");
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("x.txt"), "x");
        try {
            Files.createSymbolicLink(root.resolve("innocent"), secret);
            Files.createSymbolicLink(root.resolve("escape"), outside);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            Assumptions.assumeTrue(false, "这台机器不能建符号链接：" + e.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> FileGuard.checked(root, "innocent/config.json", List.of()), "换个名字指向 config/aiadmin");
        assertThrows(IllegalArgumentException.class, () -> FileGuard.checked(root, "escape/x.txt", List.of()), "链接指向服务器目录外");
        assertFalse(FileGuard.denied("logs/latest.log", List.of()));
    }
}
