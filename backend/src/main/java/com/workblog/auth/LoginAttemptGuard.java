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
 * 로그인 보호: 같은 아이디로 {@link #MAX_FAILURES}회 연속 실패하면 {@link #LOCK}간 잠근다.
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
        Instant lastFailure;
        Instant lockedUntil;
    }

    public LoginAttemptGuard() { this(Clock.systemUTC()); }

    LoginAttemptGuard(Clock clock) { this.clock = clock; }

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

    /** "9분 30초" */
    public static String humanize(Duration d) {
        long sec = Math.max(1, (d.toMillis() + 999) / 1000);
        long m = sec / 60, s = sec % 60;
        return m > 0 ? (m + "분" + (s > 0 ? " " + s + "초" : "")) : s + "초";
    }
}
