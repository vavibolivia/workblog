package com.workblog.auth;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 로그인 보호: 같은 (아이디, 접속 IP) 로 {@link #MAX_FAILURES}회 연속 실패하면 그 조합만 {@link #LOCK}간 잠근다.
 * IP 를 함께 묶어, 다른 PC 에서 남의 아이디로 일부러 틀려 계정 주인을 잠그는 공격을 막는다.
 * 시도는 인증 전에 {@link #tryAcquire} 로 원자적으로 예약한다(동시 요청으로 실패 한도를 넘기는 것 방지).
 * 메모리에만 두므로 서버를 다시 시작하면 풀린다. 성공하면 실패 횟수를 지운다. 아이디는 대소문자를 무시한다.
 */
@Component
public class LoginAttemptGuard {
    public static final int MAX_FAILURES = 5;
    public static final Duration LOCK = Duration.ofMinutes(10);
    /** 이 수를 넘으면 잠기지 않은 오래된 기록부터 정리(메모리 보호) */
    static final int MAX_ENTRIES = 10_000;

    private final Clock clock;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    private static final class State {
        int failures;
        int inFlight;
        Instant lastFailure;
        Instant lockedUntil;
    }

    public LoginAttemptGuard() { this(Clock.systemUTC()); }

    LoginAttemptGuard(Clock clock) { this.clock = clock; }

    /**
     * 인증 직전 호출. 잠겼거나(실패 + 진행 중 시도)가 한도에 닿았으면 남은 시간을 돌려주고 시도를 막는다.
     * 비어 있으면 시도가 예약된 것 — 반드시 {@link #release} 로 끝낸다.
     */
    public Optional<Duration> tryAcquire(String username, String ip) {
        if (states.size() > MAX_ENTRIES) prune();
        State s = states.computeIfAbsent(key(username, ip), k -> new State());
        synchronized (s) {
            Instant now = clock.instant();
            if (s.lockedUntil != null && now.isBefore(s.lockedUntil)) return Optional.of(Duration.between(now, s.lockedUntil));
            if (s.lockedUntil != null) { s.lockedUntil = null; s.failures = 0; }
            if (s.failures + s.inFlight >= MAX_FAILURES) return Optional.of(Duration.ofSeconds(5)); // 진행 중 시도가 끝날 때까지 잠깐 대기
            s.inFlight++;
            return Optional.empty();
        }
    }

    /** 예약한 시도를 끝낸다. 실패로 이번에 잠기면 true. */
    public boolean release(String username, String ip, boolean success) {
        String k = key(username, ip);
        State s = states.get(k);
        if (s == null) return false;
        synchronized (s) {
            s.inFlight = Math.max(0, s.inFlight - 1);
            if (success) {
                if (s.inFlight == 0) states.remove(k, s); else s.failures = 0;
                return false;
            }
            Instant now = clock.instant();
            s.failures++;
            s.lastFailure = now;
            if (s.failures >= MAX_FAILURES && s.lockedUntil == null) { s.lockedUntil = now.plus(LOCK); return true; }
            return s.lockedUntil != null;
        }
    }

    /** 잠겨 있으면 남은 시간. */
    public Optional<Duration> remainingLock(String username) {
        State s = states.get(key(username));
        if (s == null) return Optional.empty();
        synchronized (s) {
            Instant now = clock.instant();
            if (s.lockedUntil != null && now.isBefore(s.lockedUntil)) return Optional.of(Duration.between(now, s.lockedUntil));
            if (s.lockedUntil != null) { s.lockedUntil = null; s.failures = 0; }
            return Optional.empty();
        }
    }

    /** 실패 한 번 기록. 이번 실패로 잠기면 true. */
    public boolean recordFailure(String username) {
        if (states.size() > MAX_ENTRIES) prune();
        State s = states.computeIfAbsent(key(username), k -> new State());
        synchronized (s) {
            Instant now = clock.instant();
            if (s.lockedUntil != null && !now.isBefore(s.lockedUntil)) { s.lockedUntil = null; s.failures = 0; }
            s.failures++;
            s.lastFailure = now;
            if (s.failures >= MAX_FAILURES && s.lockedUntil == null) {
                s.lockedUntil = now.plus(LOCK);
                return true;
            }
            return s.lockedUntil != null;
        }
    }

    public void recordSuccess(String username) {
        states.remove(key(username));
    }

    private void prune() {
        Instant cutoff = clock.instant().minus(LOCK);
        states.entrySet().removeIf(e -> {
            State s = e.getValue();
            synchronized (s) {
                boolean locked = s.lockedUntil != null && clock.instant().isBefore(s.lockedUntil);
                return !locked && (s.lastFailure == null || s.lastFailure.isBefore(cutoff));
            }
        });
    }

    static String key(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    static String key(String username, String ip) {
        return key(username) + "|" + (ip == null ? "" : ip);
    }

    /** "9분 30초" */
    public static String humanize(Duration d) {
        long sec = Math.max(1, (d.toMillis() + 999) / 1000);
        long m = sec / 60, s = sec % 60;
        return m > 0 ? (m + "분" + (s > 0 ? " " + s + "초" : "")) : s + "초";
    }
}
