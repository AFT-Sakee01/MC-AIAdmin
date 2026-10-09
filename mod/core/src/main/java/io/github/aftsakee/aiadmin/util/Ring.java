package io.github.aftsakee.aiadmin.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** 定长环形缓冲（线程安全）：服务端主线程、日志线程写，代理线程读。 */
public final class Ring<T> {
    private final ArrayDeque<T> q;
    private final int cap;

    public Ring(int cap) {
        this.cap = cap;
        this.q = new ArrayDeque<>(cap);
    }

    public synchronized void add(T t) {
        if (q.size() >= cap) q.pollFirst();
        q.addLast(t);
    }

    /** 最近 limit 条满足条件的项，按时间正序。 */
    public synchronized List<T> recent(int limit, Predicate<T> filter) {
        List<T> out = new ArrayList<>();
        var it = q.descendingIterator();
        while (it.hasNext() && out.size() < limit) {
            T t = it.next();
            if (filter == null || filter.test(t)) out.add(t);
        }
        java.util.Collections.reverse(out);
        return out;
    }

    public synchronized int size() {
        return q.size();
    }
}
