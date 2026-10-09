package io.github.aftsakee.aiadmin;

import com.google.gson.JsonObject;
import io.github.aftsakee.aiadmin.agent.ActionTools;
import io.github.aftsakee.aiadmin.agent.Activity;
import io.github.aftsakee.aiadmin.agent.Personas;
import io.github.aftsakee.aiadmin.agent.RunContext;
import io.github.aftsakee.aiadmin.agent.Tools;
import io.github.aftsakee.aiadmin.agent.Trigger;
import io.github.aftsakee.aiadmin.config.BotConfig;
import io.github.aftsakee.aiadmin.config.Rules;
import io.github.aftsakee.aiadmin.log.JsonlLog;
import io.github.aftsakee.aiadmin.policy.PrankPolicy;
import io.github.aftsakee.aiadmin.policy.PrankPolicy.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonaTest {
    private static boolean ok(String cmd, Level l) {
        return PrankPolicy.check(cmd, l, Set.of(), () -> 0, 5).allowed();
    }

    @Test
    void chatOnlyAllowsNoCommands() {
        assertFalse(ok("playsound minecraft:entity.creeper.primed hostile Steve", Level.CHAT_ONLY));
        assertFalse(ok("say hi", Level.CHAT_ONLY));
    }

    @Test
    void harmlessWhitelistAndLimits() {
        assertTrue(ok("playsound minecraft:entity.creeper.primed hostile Steve", Level.HARMLESS));
        assertTrue(ok("/title Steve title {\"text\":\"Boo\"}", Level.HARMLESS));
        assertTrue(ok("tellraw @a {\"text\":\"[Server] Steve 获得了成就 [被整了]\"}", Level.HARMLESS));
        assertTrue(ok("effect give Steve minecraft:levitation 3", Level.HARMLESS));
        assertFalse(ok("effect give Steve levitation 4", Level.HARMLESS));          // 漂浮太久会摔伤
        assertFalse(ok("effect give Steve blindness 5", Level.HARMLESS));           // 较狠档才有
        assertFalse(ok("effect give Steve speed", Level.HARMLESS));                 // 必须写秒数（默认 30 秒）
        assertFalse(ok("effect give Steve speed infinite", Level.HARMLESS));
        assertFalse(ok("effect give Steve speed 5 3", Level.HARMLESS));             // 等级太高
        assertFalse(ok("effect clear Steve", Level.HARMLESS));                      // 可能清掉防火
        assertTrue(ok("summon minecraft:chicken ~ ~1 ~", Level.HARMLESS));
        assertFalse(ok("summon creeper ~ ~ ~", Level.HARMLESS));
        assertFalse(ok("summon chicken ~ ~ ~ {Passengers:[{id:tnt}]}", Level.HARMLESS));
        assertFalse(PrankPolicy.check("summon chicken", Level.HARMLESS, Set.of(), () -> 5, 5).allowed()); // 召唤数用完
        assertTrue(ok("give Steve minecraft:poisonous_potato 3", Level.HARMLESS));
        assertFalse(ok("give Steve diamond 1", Level.HARMLESS));
        assertFalse(ok("give Steve stick[custom_name='\"x\"'] 1", Level.HARMLESS));
        assertFalse(ok("give Steve stick 64", Level.HARMLESS));
        assertTrue(ok("particle minecraft:heart ~ ~2 ~ 0.5 0.5 0.5 0 30", Level.HARMLESS));
        assertFalse(ok("particle minecraft:heart ~ ~2 ~ 0.5 0.5 0.5 0 5000", Level.HARMLESS));
        assertFalse(ok("tp Steve ~1 ~ ~", Level.HARMLESS));
        assertFalse(ok("damage Steve 1", Level.HARMLESS));
        for (String bad : new String[]{"kill Steve", "clear Steve", "setblock ~ ~ ~ air", "execute as Steve run playsound a b @s",
                "ban Steve", "kick Steve", "effect give @e[type=cow] speed 5", "effect give @s speed 5", "give Steve; op Steve stick 1"}) {
            assertFalse(ok(bad, Level.ROUGH), bad);
        }
    }

    @Test
    void roughAddsShortTeleportDamageAndStrongerEffects() {
        assertTrue(ok("effect give Steve blindness 10 1", Level.ROUGH));
        assertTrue(ok("effect give Steve levitation 6", Level.ROUGH));
        assertFalse(ok("effect give Steve levitation 7", Level.ROUGH));
        assertFalse(ok("effect give Steve instant_damage 1", Level.ROUGH));
        assertTrue(ok("tp Steve ~5 ~ ~-8", Level.ROUGH));
        assertTrue(ok("tp Steve ~ ~ ~ ~180 ~", Level.ROUGH));
        assertFalse(ok("tp Steve ~9 ~ ~", Level.ROUGH));
        assertFalse(ok("tp Steve ~ ~5 ~", Level.ROUGH));                            // 往上传会摔
        assertFalse(ok("tp Steve ~ ~-1 ~", Level.ROUGH));                           // 往下会卡进地里
        assertFalse(ok("tp Steve 100 64 100", Level.ROUGH));
        assertFalse(ok("tp Steve Alex", Level.ROUGH));
        assertTrue(ok("damage Steve 3", Level.ROUGH));
        assertFalse(ok("damage Steve 4", Level.ROUGH));
        assertFalse(ok("damage Steve 2 minecraft:lava", Level.ROUGH));
    }

    @Test
    void immunePlayersAndSelectors() {
        Set<String> immune = Set.of("admin");
        assertFalse(PrankPolicy.check("title Admin title {\"text\":\"x\"}", Level.HARMLESS, immune, () -> 0, 5).allowed());
        assertFalse(PrankPolicy.check("title @a title {\"text\":\"x\"}", Level.HARMLESS, immune, () -> 0, 5).allowed());
        assertTrue(PrankPolicy.check("title Steve title {\"text\":\"x\"}", Level.HARMLESS, immune, () -> 0, 5).allowed());
        assertTrue(ok("title @a title {\"text\":\"x\"}", Level.HARMLESS));
        assertTrue(PrankPolicy.describe(Level.HARMLESS, immune, 5).contains("admin"));
        assertTrue(PrankPolicy.describe(Level.ROUGH, Set.of(), 5).contains("damage"));
        assertFalse(PrankPolicy.describe(Level.HARMLESS, Set.of(), 5).contains("damage"));
        assertEquals(Level.HARMLESS, Level.parse("whatever"));
        assertEquals(Level.ROUGH, Level.parse(" Rough "));
    }

    @Test
    void modeFilesGeneratedCustomModesAndPrompt(@TempDir Path dir) throws Exception {
        Personas p = new Personas(dir);
        assertEquals(3, p.ensure().size());
        assertEquals(0, p.ensure().size()); // 已存在不覆盖
        Path roast = p.file("roast");
        Files.writeString(roast, "<!-- c -->\n只用文言文吐槽。", StandardCharsets.UTF_8);
        assertTrue(p.prompt("roast", "AI管家", Level.HARMLESS, Set.of(), 5).endsWith("只用文言文吐槽。"));
        assertEquals("", p.prompt("normal", "AI管家", Level.HARMLESS, Set.of(), 5));
        Files.writeString(p.file("pirate"), "用海盗腔说话。", StandardCharsets.UTF_8);
        assertTrue(p.available().contains("pirate"));
        assertTrue(p.exists("pirate"));
        assertFalse(p.exists("../config"));
        assertFalse(Personas.validName("../x"));
        String prank = p.prompt("prank", "AI管家", Level.ROUGH, Set.of(), 5);
        assertTrue(prank.contains("较狠") && prank.contains("damage") && !prank.contains("{prank_rules}"));
        Files.writeString(p.file("prank"), "随便整。", StandardCharsets.UTF_8); // 删了占位符也会补上档位规则
        assertTrue(p.prompt("prank", "AI管家", Level.CHAT_ONLY, Set.of(), 5).contains("只动嘴"));
        assertTrue(p.prompt("helper", "AI管家", Level.HARMLESS, Set.of(), 5).startsWith("当前预设模式：帮助模式"));
    }

    @Test
    void actionToolsEnforcePrankPolicyOnlyForPlainChatInPrankMode(@TempDir Path dir) throws Exception {
        BotConfig cfg = new BotConfig();
        Rules rules = new Rules();
        cfg.persona.prank.maxSummonsPerRun = 1;
        TestRig.FakeActions actions = new TestRig.FakeActions();
        actions.outcome = c -> new ActionTools.ServerActions.CommandOutcome(true, 1, "");
        List<String> executed = actions.executed;
        Tools tools = new Tools();
        ActionTools.register(tools, actions, () -> cfg, () -> rules, new JsonlLog(dir.resolve("logs"), null), new Activity());
        Tools.Handler run = tools.get(ActionTools.RUN_COMMAND).handler();
        java.util.function.BiFunction<RunContext, String, Boolean> call = (ctx, cmd) -> {
            JsonObject a = new JsonObject();
            a.addProperty("command", cmd);
            try {
                return com.google.gson.JsonParser.parseString(run.call(a, ctx)).getAsJsonObject().get("allowed").getAsBoolean();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        RunContext chat = new RunContext("c1", Trigger.chat(List.of("Steve：嗨"), ""), cfg, rules, "p");
        chat.persona(Personas.PRANK, Level.HARMLESS);
        assertFalse(call.apply(chat, "time set night"));                 // 不在恶作剧白名单
        assertTrue(call.apply(chat, "summon chicken ~ ~ ~"));
        assertFalse(call.apply(chat, "summon chicken ~ ~ ~"));           // 召唤上限 1
        assertTrue(call.apply(chat, "playsound minecraft:entity.creeper.primed hostile Steve"));
        RunContext authorized = new RunContext("c2", Trigger.chat(List.of("Admin【已授权】：改成晚上"), ""), cfg, rules, "p");
        authorized.persona(Personas.PRANK, Level.CHAT_ONLY);
        assertTrue(call.apply(authorized, "time set night"));            // 已授权玩家的要求照常
        RunContext patrol = new RunContext("c3", Trigger.patrol(false), cfg, rules, "p");
        patrol.persona(Personas.PRANK, Level.CHAT_ONLY);
        assertTrue(call.apply(patrol, "time set day"));                  // 巡检不受恶作剧档位影响
        RunContext roastChat = new RunContext("c4", Trigger.chat(List.of("Steve：嗨"), ""), cfg, rules, "p");
        roastChat.persona("roast", Level.CHAT_ONLY);
        assertTrue(call.apply(roastChat, "time set day"));               // 只有 prank 模式才检查
        assertEquals(List.of("summon chicken ~ ~ ~", "playsound minecraft:entity.creeper.primed hostile Steve", "time set night", "time set day",
                "time set day"), executed);
    }
}
