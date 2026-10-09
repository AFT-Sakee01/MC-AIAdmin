package io.github.aftsakee.aiadmin.neoforge;

import io.github.aftsakee.aiadmin.util.Ring;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 服务端日志旁路：把 INFO 及以上的日志存进内存环形缓冲（get_server_log 工具用），ERROR/FATAL 放进待处理队列。
 * <p>约束：append() 可能在任何线程、甚至在持有其他锁时被调用，所以这里只做无阻塞的内存操作，
 * 不直接调用 AgentService（会拿它的锁，有死锁风险）；错误队列由服务端 tick 取出再交给调度。本模组自己的日志不触发错误事件。
 */
final class LogTap extends AbstractAppender {
    record Line(long time, String level, String logger, String thread, String message) {}

    static final String OWN_PREFIX = "io.github.aftsakee.aiadmin";
    final Ring<Line> ring = new Ring<>(1500);
    final ConcurrentLinkedQueue<Line> errors = new ConcurrentLinkedQueue<>();

    LogTap() {
        super("AiAdminTap", null, null, true, Property.EMPTY_ARRAY);
    }

    /**
     * 机器人自己执行的指令引发的 ERROR（模组指令报错、/reload 数据包报错）不当成服务器错误事件交给 AI——
     * 否则 AI 会去排查自己刚造成的错误，只靠告警冷却限流。指令都在服务端主线程上执行，记下执行区间，
     * 主线程在这些区间里打出的错误算"自己的"。按事件时间判断（日志可能异步写出），只留最近 8 个区间。
     */
    private static final long[][] BOT_CMDS = new long[8][2];
    private static int botIdx;
    private static volatile long botRunningSince;

    static void botCommandStart() {
        botRunningSince = System.currentTimeMillis();
    }

    static synchronized void botCommandEnd() {
        BOT_CMDS[botIdx][0] = botRunningSince;
        BOT_CMDS[botIdx][1] = System.currentTimeMillis();
        botIdx = (botIdx + 1) % BOT_CMDS.length;
        botRunningSince = 0;
    }

    static synchronized boolean duringBotCommand(long t) {
        long since = botRunningSince;
        if (since > 0 && t >= since) return true;
        for (long[] r : BOT_CMDS) if (r[1] > 0 && t >= r[0] && t <= r[1]) return true;
        return false;
    }

    @Override
    public void append(LogEvent e) {
        try {
            if (!e.getLevel().isMoreSpecificThan(Level.INFO)) return;
            String msg = e.getMessage() == null ? "" : e.getMessage().getFormattedMessage();
            if (e.getThrown() != null) msg += " | " + e.getThrown().getClass().getName() + ": " + e.getThrown().getMessage();
            if (msg.length() > 2000) msg = msg.substring(0, 2000) + "…";
            String logger = e.getLoggerName() == null ? "" : e.getLoggerName();
            Line l = new Line(e.getTimeMillis(), e.getLevel().name(), logger, e.getThreadName(), msg);
            ring.add(l);
            boolean own = logger.startsWith(OWN_PREFIX) || (e.getThreadName() != null && e.getThreadName().startsWith("aiadmin-"))
                    || ("Server thread".equals(e.getThreadName()) && duringBotCommand(e.getTimeMillis()));
            if (!own && e.getLevel().isMoreSpecificThan(Level.ERROR) && errors.size() < 100) errors.add(l);
        } catch (RuntimeException ignored) {
            // 日志旁路绝不能影响日志系统本身
        }
    }

    void install() {
        start();
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration cfg = ctx.getConfiguration();
        cfg.getRootLogger().addAppender(this, Level.INFO, null);
        ctx.updateLoggers();
    }

    void uninstall() {
        try {
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            ctx.getConfiguration().getRootLogger().removeAppender(getName());
            ctx.updateLoggers();
        } catch (RuntimeException ignored) {
            // 关服时日志系统可能已经在关闭
        }
        stop();
    }
}
