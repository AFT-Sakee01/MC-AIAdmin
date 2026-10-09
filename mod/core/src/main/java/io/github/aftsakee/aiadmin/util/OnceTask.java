package io.github.aftsakee.aiadmin.util;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 投递到另一个线程（服务端主线程）执行、调用方可能等不及要取消的任务。
 * <p>为什么不用 Future.cancel：CompletableFuture.supplyAsync 的任务已经在执行时，cancel(false) 照样返回 true，
 * 调用方会以为"取消了、没执行"，而指令其实执行了——AI 据此重试就执行两次。这里用一个状态机：
 * 执行线程把"未开始 → 执行中"抢到手才执行；调用方把"未开始 → 已取消"抢到手才算真的取消。两者只有一个能成功。
 */
public final class OnceTask<T> implements Supplier<T> {
    private static final int PENDING = 0;
    private static final int RUNNING = 1;
    private static final int CANCELLED = 2;
    private final AtomicInteger state = new AtomicInteger(PENDING);
    private final Supplier<T> body;

    public OnceTask(Supplier<T> body) {
        this.body = body;
    }

    @Override
    public T get() {
        if (!state.compareAndSet(PENDING, RUNNING)) throw new CancellationException("已取消");
        return body.get();
    }

    /** 取消还没开始的任务：成功返回 true（保证不会再执行）；已经开始或结束返回 false。 */
    public boolean cancel() {
        return state.compareAndSet(PENDING, CANCELLED);
    }

    public boolean started() {
        return state.get() == RUNNING;
    }
}
