package io.github.aftsakee.aiadmin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.aftsakee.aiadmin.agent.ActionTools;
import io.github.aftsakee.aiadmin.agent.Activity;
import io.github.aftsakee.aiadmin.agent.CommandNotes;
import io.github.aftsakee.aiadmin.agent.RunContext;
import io.github.aftsakee.aiadmin.agent.SocialTools;
import io.github.aftsakee.aiadmin.agent.Tools;
import io.github.aftsakee.aiadmin.agent.Trigger;
import io.github.aftsakee.aiadmin.config.BotConfig;
import io.github.aftsakee.aiadmin.config.Rules;
import io.github.aftsakee.aiadmin.log.JsonlLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** 指令资料：原版资料随包、模组指令用过后记笔记，"已验证"以真实执行记录为准。 */
class CommandNotesTest {
    @TempDir
    Path tmp;

    @Test
    void vanillaReferenceIsBundled() {
        assertEquals("give", CommandNotes.root("/minecraft:give @p diamond"));
        assertEquals("pig", CommandNotes.root("pig Steve 3"));
        JsonObject v = CommandNotes.vanilla();
        assertTrue(v.get("units").getAsString().contains("20 tick"));
        assertTrue(CommandNotes.vanilla("effect").get("units").getAsString().contains("秒"));
        assertTrue(CommandNotes.vanilla("time").get("undo").getAsString().contains("time query"));
        assertTrue(CommandNotes.vanilla("clear").get("risk").getAsString().contains("高"));
        assertNull(CommandNotes.vanilla("pig"), "模组指令没有现成资料");
        assertTrue(v.getAsJsonObject("commands").size() >= 40);
    }

    @Test
    void verifiedOnlyAfterRealSuccessfulRun() {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        Path f = tmp.resolve("command-notes.json");
        CommandNotes n = new CommandNotes(f, now::get);
        assertTrue(n.add("pig", "单位是分钟", true, "AI").contains("未验证"), "没执行过：说了 verified 也不算");
        n.recordRun("pig Steve 1", false);
        assertTrue(n.add("pig", "失败的不算", true, "AI").contains("未验证"));
        n.recordRun("/pig Steve 1", true);
        String r = n.add("pig", "/pig <玩家> <分钟>，0 恢复", true, "AI");
        assertTrue(r.contains("已验证") && r.contains("pig Steve 1"), r);
        assertTrue(n.json("pig").get(2).getAsJsonObject().get("verified").getAsBoolean());
        now.addAndGet(31 * 60_000L);
        assertTrue(n.add("pig", "过期了", true, "AI").contains("未验证"), "30 分钟前的执行不算");
        for (int i = 0; i < 10; i++) n.add("pig", "凑数 " + i, false, "AI");
        assertEquals(10, n.json("pig").size());
        assertTrue(n.json("pig").toString().contains("0 恢复"), "满了先删未验证的，已验证的留着");
        assertTrue(n.remove("pig", 1).contains("已删除"));
        assertEquals(9, new CommandNotes(f, now::get).json("pig").size(), "重启后还在");
        assertTrue(n.add("坏指令", "x", false, "AI").contains("不对"));
        assertTrue(n.has("pig"));
    }

    @Test
    void noteToolUsesActionToolsRunLog() throws Exception {
        BotConfig cfg = new BotConfig();
        Rules rules = new Rules();
        CommandNotes notes = new CommandNotes(tmp.resolve("n.json"), System::currentTimeMillis);
        Tools tools = new Tools();
        ActionTools.register(tools, new TestRig.FakeActions(), () -> cfg, () -> rules, new JsonlLog(tmp.resolve("logs"), null), new Activity(), () -> null, () -> notes);
        SocialTools.register(tools, () -> null, () -> cfg, () -> notes);
        RunContext ctx = new RunContext("t", Trigger.admin("服主", "试一下 pig", null, null), cfg, rules, "deepseek");
        String note = tools.get("note_command").handler().call(JsonParser.parseString("{\"command\":\"pig\",\"text\":\"单位分钟\",\"verified\":true}").getAsJsonObject(), ctx);
        assertTrue(note.contains("未验证"), note);
        tools.get(ActionTools.RUN_COMMAND).handler().call(JsonParser.parseString("{\"command\":\"pig Steve 1\"}").getAsJsonObject(), ctx);
        tools.get(ActionTools.RUN_COMMAND).handler().call(JsonParser.parseString("{\"command\":\"stop\"}").getAsJsonObject(), ctx); // 被黑名单拒绝：不记
        note = tools.get("note_command").handler().call(JsonParser.parseString("{\"command\":\"pig\",\"text\":\"单位分钟，0 恢复\",\"verified\":true}").getAsJsonObject(), ctx);
        assertTrue(note.contains("已验证") && note.contains("本地 AI"), note);
        assertNull(notes.lastSuccess("stop"));
        cfg.remote.allowActions = false;
        RunContext remote = new RunContext("r", Trigger.remote("x"), cfg, rules, "remote");
        assertTrue(tools.get("note_command").handler().call(JsonParser.parseString("{\"command\":\"pig\",\"text\":\"x\"}").getAsJsonObject(), remote)
                .contains("allow_actions"));
        assertEquals(List.of(), List.of());
    }
}
