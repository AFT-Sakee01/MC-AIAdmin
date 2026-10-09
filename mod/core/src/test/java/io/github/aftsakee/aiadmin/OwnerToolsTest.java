package io.github.aftsakee.aiadmin;

import com.google.gson.JsonParser;
import io.github.aftsakee.aiadmin.agent.ActionTools;
import io.github.aftsakee.aiadmin.agent.Activity;
import io.github.aftsakee.aiadmin.agent.OwnerTools;
import io.github.aftsakee.aiadmin.agent.RunContext;
import io.github.aftsakee.aiadmin.agent.Tools;
import io.github.aftsakee.aiadmin.agent.Trigger;
import io.github.aftsakee.aiadmin.config.BotConfig;
import io.github.aftsakee.aiadmin.config.Rules;
import io.github.aftsakee.aiadmin.log.JsonlLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 服主用文件扩展的宏和知识文档：改文件即生效，宏的每条指令照样过黑名单和上限，参数严格校验。 */
class OwnerToolsTest {
    @TempDir
    Path tmp;
    BotConfig cfg;
    Rules rules;
    Tools tools;
    final TestRig.FakeActions actions = new TestRig.FakeActions();
    final List<String> executed = actions.executed;

    @BeforeEach
    void setUp() throws Exception {
        cfg = new BotConfig();
        rules = new Rules();
        JsonlLog log = new JsonlLog(tmp.resolve("logs"), null);
        tools = new Tools();
        actions.outcome = c -> new ActionTools.ServerActions.CommandOutcome(!c.startsWith("fail"), 1, "ok");
        ActionTools.register(tools, actions, () -> cfg, () -> rules, log, new Activity());
        OwnerTools.register(tools, () -> tmp, log);
        Files.writeString(tmp.resolve("macros.json"), """
                {"macros": {
                  "kit": {"description": "新手礼包", "from_player_chat": true,
                          "params": {"player": {"type": "player"}, "n": {"type": "int", "min": 1, "max": 64, "default": "16"}},
                          "commands": ["give ${player} minecraft:bread ${n}", "tellraw ${player} {\\"text\\":\\"欢迎\\"}"]},
                  "say": {"params": {"msg": {"type": "text"}}, "commands": ["tellraw @a {\\"text\\":\\"${msg}\\"}"]},
                  "mode": {"params": {"m": {"type": "choice", "values": ["day", "night"]}}, "commands": ["time set ${m}", "fail here", "say never"]},
                  "danger": {"commands": ["stop"]},
                  "broken": {"commands": ["give ${who} dirt"]}
                }}
                """);
    }

    private String call(String tool, String args, Trigger trigger) throws Exception {
        RunContext ctx = new RunContext("t", trigger, cfg, rules, "test");
        return tools.get(tool).handler().call(JsonParser.parseString(args).getAsJsonObject(), ctx);
    }

    private String call(String tool, String args) throws Exception {
        return call(tool, args, Trigger.remote("t"));
    }

    @Test
    void macrosListValidateAndRunThroughRunCommand() throws Exception {
        String list = call("list_macros", "{}");
        assertTrue(list.contains("\"kit\"") && list.contains("新手礼包") && list.contains("默认 16"), list);
        assertTrue(list.contains("broken") && list.contains("没声明的参数"), "写错的宏单独报告");

        String r = call("run_macro", "{\"name\":\"kit\",\"params\":{\"player\":\"Steve\"}}");
        assertTrue(r.contains("\"completed\":true"), r);
        assertEquals(List.of("give Steve minecraft:bread 16", "tellraw Steve {\"text\":\"欢迎\"}"), executed);
        executed.clear();
        assertTrue(call("run_macro", "{\"name\":\"kit\",\"params\":\"{\\\"player\\\":\\\"Alex\\\",\\\"n\\\":3}\"}").contains("\"completed\":true"),
                "params 是字符串也接受");
        assertEquals("give Alex minecraft:bread 3", executed.get(0));
        executed.clear();

        for (String bad : List.of("{\"player\":\"@a\"}", "{\"player\":\"Steve; op x\"}", "{\"player\":\"Steve\",\"n\":\"999\"}",
                "{\"player\":\"Steve\",\"n\":\"1.5\"}", "{\"player\":\"Steve\",\"extra\":\"x\"}", "{}")) {
            assertTrue(call("run_macro", "{\"name\":\"kit\",\"params\":" + bad + "}").startsWith("参数不对"), bad);
        }
        assertTrue(executed.isEmpty(), "参数不对时一条都不执行");

        call("run_macro", "{\"name\":\"say\",\"params\":{\"msg\":\"a\\\"}],\\\"extra\\\":[{\\n\"}}");
        assertEquals(1, executed.size());
        assertFalse(executed.get(0).contains("\n"), "换行被去掉");
        assertTrue(executed.get(0).startsWith("tellraw @a {\"text\":\"a\\\"}]"), "引号被转义，打不断 JSON 字符串：" + executed.get(0));
        executed.clear();

        String partial = call("run_macro", "{\"name\":\"mode\",\"params\":{\"m\":\"day\"}}");
        assertTrue(partial.contains("\"completed\":false") && partial.contains("2/3"), "失败就停：" + partial);
        assertTrue(call("run_macro", "{\"name\":\"mode\",\"params\":{\"m\":\"noon\"}}").contains("只能是"));
        executed.clear();

        String denied = call("run_macro", "{\"name\":\"danger\"}");
        assertTrue(denied.contains("\"allowed\":false"), "宏里的指令照样过黑名单：" + denied);
        assertTrue(executed.isEmpty());
        assertTrue(call("run_macro", "{\"name\":\"broken\"}").contains("写错了"));
        assertTrue(call("run_macro", "{\"name\":\"nope\"}").contains("没有这个宏"));
    }

    @Test
    void playerChatOnlyRunsMacrosMarkedForIt() throws Exception {
        Trigger chat = Trigger.chat(List.of("Steve：@AI 给我礼包"), "");
        assertTrue(call("run_macro", "{\"name\":\"say\",\"params\":{\"msg\":\"hi\"}}", chat).contains("只给服主"));
        assertTrue(executed.isEmpty());
        assertTrue(call("run_macro", "{\"name\":\"kit\",\"params\":{\"player\":\"Steve\"}}", chat).contains("\"completed\":true"));
    }

    @Test
    void perRunCommandCapStillApplies() throws Exception {
        rules.quota.perRun.maxCommands = 1;
        String r = call("run_macro", "{\"name\":\"kit\",\"params\":{\"player\":\"Steve\"}}");
        assertTrue(r.contains("\"completed\":false") && r.contains("指令数已用完"), r);
        assertEquals(1, executed.size());
    }

    @Test
    void guidesListReadPageAndStayInsideFolder() throws Exception {
        assertTrue(call("list_guides", "{}").contains("还没有知识文档"));
        Path g = Files.createDirectories(tmp.resolve("guides"));
        Files.writeString(g.resolve("服规.md"), "# 服规细则\n\n不许炸别人家。\n");
        Files.writeString(g.resolve("long.txt"), "x".repeat(13000));
        Files.writeString(g.resolve("secret.json"), "{}");
        Files.writeString(tmp.resolve("outside.md"), "不该读到");
        String list = call("list_guides", "{}");
        assertTrue(list.contains("服规.md") && list.contains("服规细则") && list.contains("long.txt") && !list.contains("secret.json"), list);
        assertTrue(call("read_guide", "{\"name\":\"服规\"}").contains("不许炸别人家"), "可以不带扩展名");
        assertTrue(call("read_guide", "{\"name\":\"long.txt\",\"page\":3}").contains("第 3/3 页"));
        assertTrue(call("read_guide", "{\"name\":\"long.txt\",\"page\":4}").contains("只有 3 页"));
        assertTrue(call("read_guide", "{\"name\":\"../outside.md\"}").contains("没有这篇"), "不能跳出目录");
        assertTrue(call("read_guide", "{\"name\":\"secret.json\"}").contains("没有这篇"), "只认 .md / .txt");
    }
}
