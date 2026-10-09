package io.github.aftsakee.aiadmin;

import io.github.aftsakee.aiadmin.config.Rules;
import io.github.aftsakee.aiadmin.policy.CommandPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandPolicyTest {
    private final CommandPolicy p = new CommandPolicy(new Rules().permissions);

    @Test
    void deniesRootsInAnySpelling() {
        for (String c : List.of("stop", "/stop", "  //STOP  ", "minecraft:stop", "op Steve", "deop Steve", "ban-ip 1.2.3.4",
                "save-off", "reload", "aibot ask hi", "publish")) {
            assertFalse(p.check(c).allowed(), c);
        }
    }

    /** 不依赖黑名单：服主把 aibot 从黑名单里拿掉、甚至用白名单放行 aibot，代理也不能执行 aibot remote（批准/吊销 OAuth）。 */
    @Test
    void aibotRemoteIsAlwaysDenied() {
        Rules.Permissions open = new Rules().permissions;
        open.denyRoots = List.of();
        open.denyPatterns = List.of();
        CommandPolicy q = new CommandPolicy(open);
        for (String c : List.of("aibot remote approve ABC234", "/aibot REMOTE revoke-all", "execute as @a run aibot remote approve X",
                "return run aibot remote clients")) {
            assertFalse(q.check(c).allowed(), c);
        }
        assertTrue(q.check("aibot status").allowed());
        open.allowOnlyRoots = List.of("aibot");
        assertFalse(new CommandPolicy(open).check("aibot remote approve X").allowed());
    }

    @Test
    void escapedJsonAndQuotedSnbtCannotDodgePatterns() {
        assertFalse(p.check("tellraw @a {\"text\":\"领奖\",\"click\\u0045vent\":{\"action\":\"run_command\",\"value\":\"/say hi\"}}").allowed());
        assertFalse(p.check("tellraw @a {\"text\":\"x\",\"click\\\\u0065vent\":{\"action\":\"run_command\",\"value\":\"/say hi\"}}").allowed());
        assertFalse(p.check("data merge block 1 2 3 {\"Command\":\"say hi\"}").allowed());
        assertFalse(p.check("give @p written_book[written_book_content={pages:['\"/aibot  remote approve ABCDEF\"']}]").allowed(), "文本里的 aibot remote");
        assertTrue(p.check("tellraw @a {\"text\":\"欢迎\"}").allowed());
    }

    @Test
    void deniesNestedExecuteRun() {
        assertFalse(p.check("execute as @a run stop").allowed());
        assertFalse(p.check("execute in minecraft:the_nether run execute as @p run op Steve").allowed());
        assertFalse(p.check("execute if entity @a run minecraft:deop Alex").allowed());
        // 玩家名恰好叫 run 也不能绕过
        assertFalse(p.check("execute as run run stop").allowed());
        assertFalse(p.check("return run stop").allowed());
    }

    @Test
    void deniesPatterns() {
        assertFalse(p.check("whitelist off").allowed());
        assertFalse(p.check("kill @e").allowed());
        assertFalse(p.check("kill   @a").allowed());
        assertFalse(p.check("execute as @p run kill @e").allowed());
        assertFalse(p.check("gamerule logAdminCommands false").allowed());
        assertFalse(p.check("neoforge generate 0 0 0 100").allowed());
    }

    /** 审查发现：点击事件、命令方块能间接执行被禁止的指令。 */
    @Test
    void deniesIndirectExecution() {
        assertFalse(p.check("tellraw @a {\"text\":\"点我\",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/op X\"}}").allowed());
        assertFalse(p.check("give @p written_book[written_book_content={pages:['{\"click_event\":1}']}]").allowed());
        assertFalse(p.check("setblock 0 64 0 minecraft:command_block{Command:\"op X\"}").allowed());
        assertFalse(p.check("summon minecraft:command_block_minecart").allowed());
        assertFalse(p.check("data merge block 0 64 0 {Command:\"stop\"}").allowed());
        assertFalse(p.check("kill @a").allowed());
        assertFalse(p.check("execute as @p run kill @a").allowed());
        assertFalse(p.check("kill @e").allowed());
        assertTrue(p.check("kill @a[name=Griefer]").allowed());
        assertTrue(p.check("kill @e[type=minecraft:zombie,distance=..50]").allowed());
        assertTrue(p.check("tellraw @a {\"text\":\"服务器 5 分钟后重启\"}").allowed());
    }

    @Test
    void allowsNormalAdminWork() {
        for (String c : List.of("time set day", "weather clear", "say hi", "kill @e[type=minecraft:item]", "tp Steve 0 64 0",
                "kick Griefer 刷屏", "execute as @a at @s run particle heart ~ ~2 ~", "whitelist add Alex", "neoforge tps",
                "data get entity Steve Pos", "gamerule doDaylightCycle true", "ban Griefer 炸家")) {
            assertTrue(p.check(c).allowed(), c);
        }
    }

    @Test
    void emptyIsDenied() {
        assertFalse(p.check("   ").allowed());
        assertFalse(p.check(null).allowed());
    }

    @Test
    void allowOnlyMode() {
        Rules.Permissions c = new Rules().permissions;
        c.allowOnlyRoots = List.of("say", "time", "execute");
        CommandPolicy w = new CommandPolicy(c);
        assertTrue(w.check("say hi").allowed());
        assertTrue(w.check("execute as @a run say hi").allowed());
        assertFalse(w.check("execute as @a run tp @s 0 0 0").allowed());
        assertFalse(w.check("weather clear").allowed());
    }

    @Test
    void normalize() {
        assertEquals("time set day", CommandPolicy.normalize("  /time   set\nday "));
    }
}
