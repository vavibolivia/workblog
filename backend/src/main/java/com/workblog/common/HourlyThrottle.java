package com.workblog.common;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/** 키마다 최근 한 시간의 횟수를 센다(메모리, 재시작하면 비워짐). */
public final class HourlyThrottle {
    private static final long WINDOW_MS = 3_600_000L;
    private static final int MAX_KEYS = 10_000;
    private final Map<String, ArrayDeque<Long>> hits = new HashMap<>();

    /** 한 시간 안에 max 번까지 true. 넘으면 false(횟수는 늘지 않음). */
    public synchronized boolean allow(String key, int max) {
        long now = System.currentTimeMillis();
        hits.values().removeIf(q -> {
            while (!q.isEmpty() && now - q.peekFirst() > WINDOW_MS) q.pollFirst();
            return q.isEmpty();
        });
        ArrayDeque<Long> q = hits.get(key);
        if (q == null) {
            if (hits.size() >= MAX_KEYS) return false;
            hits.put(key, q = new ArrayDeque<>());
        }
        if (q.size() >= max) return false;
        q.addLast(now);
        return true;
    }

    /** 방금 allow 로 잡은 한 번을 되돌린다(뒤따르는 검사에서 막혔을 때). */
    public synchronized void undo(String key) {
        ArrayDeque<Long> q = hits.get(key);
        if (q != null && !q.isEmpty()) q.pollLast();
    }
}
