package io.github.aftsakee.aiadmin.agent;

import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 调度层的定时器（1.0.0.34 起可注入 AgentService）：生产环境是一个守护线程的 ScheduledExecutorService；测试里换成按假时钟
 * 手动推进的实现，兜底/合并的计时不用真睡，也不会因为机器负载高而误判。
 * <ul>
 *   <li>约束：任务在同一个线程上串行执行——聊天合并、事件合并、云端兜底、收回都依赖"不会同时跑两个定时任务"。</li>
 *   <li>边界：关闭后再排任务静默丢弃。关服时 flush / handoff 可能和 stop 赛跑，这时丢掉就是正确结果
 *       （服务已停，交给本地也跑不了）。</li>
 * </ul>
 */
public interface Scheduler {
    /** delayMs 毫秒后执行一次。 */
    void schedule(Runnable task, long delayMs);

    /** 固定间隔重复执行（上一次结束后再等 periodMs）。 */
    void repeat(Runnable task, long initialDelayMs, long periodMs);

    boolean isShutdown();

    /** 停止并中断正在执行的任务。 */
    void shutdownNow();

    /** 生产用：一个名为 threadName 的守护线程。 */
    static Scheduler threaded(String threadName) {
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, threadName);
            t.setDaemon(true);
            return t;
        });
        return new Scheduler() {
            @Override
            public void schedule(Runnable task, long delayMs) {
                try {
                    ex.schedule(task, delayMs, TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException ignored) {
                    // 已关闭：见类注释
                }
            }

            @Override
            public void repeat(Runnable task, long initialDelayMs, long periodMs) {
                try {
                    ex.scheduleWithFixedDelay(task, initialDelayMs, periodMs, TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException ignored) {
                    // 已关闭：见类注释
                }
            }

            @Override
            public boolean isShutdown() {
                return ex.isShutdown();
            }

            @Override
            public void shutdownNow() {
                ex.shutdownNow();
            }
        };
    }
}
