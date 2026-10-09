package io.github.aftsakee.aiadmin.agent;

import io.github.aftsakee.aiadmin.config.BotConfig;
import io.github.aftsakee.aiadmin.config.Rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 一次运行的上下文：关联 id、计数器（指令/聊天上限）、已执行动作清单（写进行动记录）。 */
public final class RunContext {
    public final String cid;
    public final Trigger trigger;
    public final BotConfig cfg;
    public final Rules rules;
    public final String provider;
    private int commands;
    private int chats;
    private final List<String> actions = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean cancelled;

    public RunContext(String cid, Trigger trigger, BotConfig cfg, Rules rules, String provider) {
        this.cid = cid;
        this.trigger = trigger;
        this.cfg = cfg;
        this.rules = rules;
        this.provider = provider;
    }

    public synchronized boolean tryCommand() {
        if (commands >= rules.quota.perRun.maxCommands) return false;
        commands++;
        return true;
    }

    /** 本次运行已经执行（放行）的指令数：远程接口据此把宏等"一次调用多条指令"的工具计入每小时上限。 */
    public synchronized int commandsUsed() {
        return commands;
    }

    public synchronized boolean tryChat() {
        if (chats >= rules.quota.perRun.maxChat) return false;
        chats++;
        return true;
    }

    private long extraTokens;
    private double extraCost;
    /** 本次运行开始时的预设模式和恶作剧档位（AgentService 设置；远程管理接口的调用保持默认 normal）。 */
    private volatile String persona = Personas.NORMAL;
    private volatile io.github.aftsakee.aiadmin.policy.PrankPolicy.Level prankLevel = io.github.aftsakee.aiadmin.policy.PrankPolicy.Level.HARMLESS;
    private int summons;

    public void persona(String mode, io.github.aftsakee.aiadmin.policy.PrankPolicy.Level level) {
        this.persona = mode;
        this.prankLevel = level;
    }

    public String persona() {
        return persona;
    }

    public io.github.aftsakee.aiadmin.policy.PrankPolicy.Level prankLevel() {
        return prankLevel;
    }

    /** 恶作剧召唤计数。 */
    public synchronized int summons() {
        return summons;
    }

    public synchronized void addSummon() {
        summons++;
    }

    /** 主对话之外的模型调用（压缩摘要）的用量。 */
    public synchronized void addExtra(long tokens, double cost) {
        extraTokens += tokens;
        extraCost += cost;
    }

    public synchronized long extraTokens() {
        return extraTokens;
    }

    public synchronized double extraCost() {
        return extraCost;
    }

    public void action(String a) {
        actions.add(a);
    }

    public List<String> actions() {
        synchronized (actions) {
            return new ArrayList<>(actions);
        }
    }

    public boolean acted() {
        return !actions.isEmpty();
    }

    public void cancel() {
        cancelled = true;
    }

    public boolean cancelled() {
        return cancelled;
    }
}
