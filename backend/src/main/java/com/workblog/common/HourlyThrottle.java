package com.workblog.common;

import java.util.ArrayDeque;
import java.util.Map;

/** 키마다 최근 한 시간의 횟수를 센다(메모리, 재시작하면 비워짐). */
public final class HourlyThrottle {
    private static final long WINDOW_MS = 3_600_000L;
    private static final int MAX_KEYS = 10_000;
    private final Map<String, ArrayDeque<Long>> hits = new java.util.LinkedHashMap<>(); // 넣은 순서 유지 → 맨 앞이 가장 오래된 키

    /** 접속 주소를 한도 키로: IPv6 은 앞 /64(한 가입자가 받는 대역)로 묶고, IPv4 는 그대로. */
    public static String clientKey(String remoteAddr) {
        if (remoteAddr == null) return "?";
        if (!remoteAddr.contains(":")) return remoteAddr;
        try {
            byte[] b = java.net.InetAddress.getByName(remoteAddr).getAddress(); // 숫자 주소라 이름 조회는 하지 않는다
            if (b.length != 16) return remoteAddr;
            StringBuilder sb = new StringBuilder("v6:");
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", b[i]));
            return sb.toString();
        } catch (Exception e) {
            return remoteAddr;
        }
    }

    /** 한 시간 안에 max 번까지 true. 넘으면 false(횟수는 늘지 않음). */
    public synchronized boolean allow(String key, int max) {
        long now = System.currentTimeMillis();
        hits.values().removeIf(q -> {
            while (!q.isEmpty() && now - q.peekFirst() > WINDOW_MS) q.pollFirst();
            return q.isEmpty();
        });
        ArrayDeque<Long> q = hits.get(key);
        if (q == null) {
            // 가득 차면 새 키를 막지 않고 가장 오래된 키를 버린다(키를 많이 만들어 남의 요청을 막지 못하게)
            if (hits.size() >= MAX_KEYS) {
                var it = hits.keySet().iterator();
                it.next();
                it.remove();
            }
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
