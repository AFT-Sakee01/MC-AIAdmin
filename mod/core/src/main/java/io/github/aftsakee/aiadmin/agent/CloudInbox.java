package io.github.aftsakee.aiadmin.agent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 云端值守的收件箱："云端优先，本地兜底"。
 * <ul>
 *   <li>云端在线 = 最近 online_timeout 秒内调用过云端值守工具，或者正有 wait_events 挂着等；另外有能投递的推送订阅（MCP Events）时
 *       AgentService 也当作在线——消息会被推到云端，再等它来取。</li>
 *   <li>在线时聊天/事件/巡检进收件箱（PENDING），wait_events 取走后变 TAKEN；云端处理完调用 complete_item 变 DONE。</li>
 *   <li>放进去 handoff 秒还没被取走 → 交给本地 API（FALLBACK），保证玩家不会没人理；推送已送达的条目放宽到 webhook_handoff 秒
 *       （云端代理从被叫醒到调用 wait_events 要一会儿）。</li>
 *   <li>取走后 auto_close 分钟还没 complete → 自动关闭（AUTO_CLOSED），已执行的动作照样记进记忆。</li>
 * </ul>
 * 并发：AgentService 线程放入、远程接口线程取出/完成、计时线程做兜底，全部经过本对象的监视器；wait 用 wait/notifyAll。
 * 同时挂起的 wait_events 有上限（每个都占远程接口的一个工作线程）。
 */
public final class CloudInbox {
    public enum State { PENDING, TAKEN, DONE, FALLBACK, AUTO_CLOSED }

    public static final class Item {
        public final String id;
        public final Trigger trigger;
        public final long created;
        /** 给云端的触发说明（和本地 AI 收到的用户消息同一份生成逻辑，不含服务器概况 JSON）。 */
        public final String instructions;
        // state/taken/closed 只在 CloudInbox 的监视器内修改；volatile 让 state() 不加锁也能读到最新值
        volatile State state = State.PENDING;
        volatile long taken;
        volatile long closed;
        /** 推送（MCP Events webhook）被对方确认收到的时间；0 = 没推送过。兜底计时据此放宽到 webhook_handoff_seconds。 */
        volatile long delivered;
        /** 云端最近一次针对这条消息有动作（reply_chat、run_command_for、带 item_id 的其他工具）的时间；收回计时从这里重新算。 */
        volatile long touched;
        /** 针对这条消息执行动作时用的上下文（per-run 上限、恶作剧档位、动作记录都挂在它上面）；第一次动作时由 AgentService 建立。 */
        private RunContext ctx;
        volatile String summary;

        Item(String id, Trigger trigger, long created, String instructions) {
            this.id = id;
            this.trigger = trigger;
            this.created = created;
            this.instructions = instructions;
        }

        public State state() {
            return state;
        }

        public long delivered() {
            return delivered;
        }

        public synchronized RunContext ctx() {
            return ctx;
        }

        synchronized void ctx(RunContext c) {
            ctx = c;
        }
    }

    private final LongSupplier clock;
    private final Map<String, Item> items = new LinkedHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private long lastSeen;
    private int waiters;
    private boolean closed;

    public CloudInbox(LongSupplier clock) {
        this.clock = clock;
    }

    /** 远程工具每次调用都算一次心跳。 */
    public synchronized void heartbeat() {
        lastSeen = clock.getAsLong();
    }

    public synchronized boolean online(long onlineTimeoutMs) {
        return waiters > 0 || (lastSeen > 0 && clock.getAsLong() - lastSeen <= onlineTimeoutMs);
    }

    public synchronized long lastSeen() {
        return lastSeen;
    }

    public synchronized int waiters() {
        return waiters;
    }

    /** 放入一条；超过 maxPending 时丢掉最旧的已结束条目，仍然满就拒绝（返回 null，调用方改交本地）。 */
    public synchronized Item offer(Trigger t, String instructions, int maxPending) {
        prune(maxPending);
        long open = items.values().stream().filter(i -> i.state == State.PENDING || i.state == State.TAKEN).count();
        if (open >= maxPending) return null;
        Item it = new Item("c" + seq.incrementAndGet(), t, clock.getAsLong(), instructions);
        items.put(it.id, it);
        notifyAll();
        return it;
    }

    private void prune(int keep) {
        if (items.size() < keep * 2) return;
        Iterator<Item> i = items.values().iterator();
        while (i.hasNext() && items.size() >= keep) {
            Item x = i.next();
            if (x.state != State.PENDING && x.state != State.TAKEN) i.remove();
        }
    }

    /** 推送已送达（对方 2xx）：只对还没被取走的条目记时间；返回是否记上了。 */
    public synchronized boolean markDelivered(String id) {
        Item it = items.get(id);
        if (it == null || it.state != State.PENDING) return false;
        if (it.delivered == 0) it.delivered = clock.getAsLong();
        return true;
    }

    /**
     * 兜底：到点时如果还没被取走，标记 FALLBACK 并返回 true（调用方交给本地 API）。已取走/已完成返回 false。
     */
    public synchronized boolean claimForLocal(String id) {
        Item it = items.get(id);
        if (it == null || it.state != State.PENDING) return false;
        it.state = State.FALLBACK;
        it.closed = clock.getAsLong();
        return true;
    }

    /**
     * 取走待处理的条目；没有时最多等 waitMs 毫秒（有新条目进来立即返回）。
     * 返回 null = 挂起的人数已满（拒绝本次）。等待被中断时返回已有的（可能为空）。
     */
    public List<Item> take(long waitMs, int max, int maxWaiters) throws InterruptedException {
        synchronized (this) {
            heartbeat();
            if (pending(max).isEmpty() && waitMs > 0) {
                if (waiters >= maxWaiters) return null;
                waiters++;
                try {
                    // 等待时长用真实单调时钟（clock 只用于业务时间戳，测试里可以是假时钟）
                    long until = System.nanoTime() + waitMs * 1_000_000L;
                    long left = waitMs;
                    while (!closed && pending(max).isEmpty() && left > 0) {
                        wait(left);
                        left = (until - System.nanoTime()) / 1_000_000L;
                    }
                } finally {
                    waiters--;
                    heartbeat();
                }
            }
            List<Item> out = pending(max);
            long now = clock.getAsLong();
            for (Item it : out) {
                it.state = State.TAKEN;
                it.taken = now;
            }
            return out;
        }
    }

    private List<Item> pending(int max) {
        List<Item> r = new ArrayList<>();
        for (Item it : items.values()) {
            if (it.state == State.PENDING) r.add(it);
            if (r.size() >= max) break;
        }
        return r;
    }

    public synchronized Item get(String id) {
        return items.get(id);
    }

    /** 云端处理完。只有 TAKEN 的能完成（PENDING 的必须先取走，FALLBACK 的已归本地）。 */
    public synchronized Item complete(String id, String summary) {
        Item it = items.get(id);
        if (it == null || it.state != State.TAKEN) return null;
        it.state = State.DONE;
        it.closed = clock.getAsLong();
        it.summary = summary;
        return it;
    }

    /** 取走后超过 autoCloseMs 没完成的，自动关闭并返回（调用方把已执行的动作记进记忆）。 */
    public synchronized List<Item> expire(long autoCloseMs) {
        List<Item> r = new ArrayList<>();
        long now = clock.getAsLong();
        for (Item it : items.values()) {
            if (it.state == State.TAKEN && now - it.taken > autoCloseMs) {
                it.state = State.AUTO_CLOSED;
                it.closed = now;
                r.add(it);
            }
        }
        return r;
    }

    /** 状态统计：PENDING/TAKEN/DONE/FALLBACK/AUTO_CLOSED 各多少。 */
    public synchronized Map<State, Integer> counts() {
        Map<State, Integer> m = new LinkedHashMap<>();
        for (State s : State.values()) m.put(s, 0);
        for (Item it : items.values()) m.merge(it.state, 1, Integer::sum);
        return m;
    }

    /**
     * 为这条消息开始一个动作，返回它的动作上下文；不是 TAKEN（已完成、已自动关闭、已收回交本地）返回 null。
     * <p>并发约束：状态检查和"建立上下文"必须在本对象的同一把锁里完成——{@link #reclaimIdle} 以"没有上下文"判定空闲并收回交本地，
     * 如果先不加锁看到 TAKEN、再去建上下文，两步之间被收回的话云端和本地会各回一次。make 在锁内调用，
     * 所以它不能去拿 AgentService 的锁（AgentService 持自己的锁时会调用本对象，反过来就是死锁）——需要的值请先在锁外取好。
     */
    public synchronized RunContext beginAction(String id, java.util.function.Function<Item, RunContext> make) {
        Item it = items.get(id);
        if (it == null || it.state != State.TAKEN) return null;
        it.touched = clock.getAsLong();
        RunContext c = it.ctx();
        if (c == null) {
            c = make.apply(it);
            it.ctx(c);
        }
        return c;
    }

    /** 云端带着 item_id 做了任何事（包括查询）：刷新活动时间，避免思考较久时被当成断线收回。返回这条是否仍是 TAKEN。 */
    public synchronized boolean touch(String id) {
        Item it = items.get(id);
        if (it == null || it.state != State.TAKEN) return false;
        it.touched = clock.getAsLong();
        return true;
    }

    /**
     * 取走后 idleMs 内一个动作都没有（没建过动作上下文、最近也没碰过、没 complete）的条目：标成 FALLBACK 并返回，由调用方交给本地。
     * 客户端超时断开时，服务端这边挂着的 wait_events 还会把新消息标成 TAKEN、回应却送不出去——不收回的话玩家要等到自动关闭。
     * 和 {@link #beginAction} 在同一把锁里：收回后云端再想为它执行动作会被拒绝，不会两边都回。
     */
    public synchronized List<Item> reclaimIdle(long idleMs) {
        List<Item> r = new ArrayList<>();
        long now = clock.getAsLong();
        for (Item it : items.values()) {
            if (it.state == State.TAKEN && it.ctx() == null && now - Math.max(it.taken, it.touched) > idleMs) {
                it.state = State.FALLBACK;
                it.closed = now;
                r.add(it);
            }
        }
        return r;
    }

    /** 关服：让挂起的 wait 立即返回（之后也不再等待）。 */
    public synchronized void wakeAll() {
        closed = true;
        notifyAll();
    }
}
