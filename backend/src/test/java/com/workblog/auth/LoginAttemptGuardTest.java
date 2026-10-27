package com.workblog.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptGuardTest {

    /** 테스트용 움직이는 시계 */
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-27T00:00:00Z");
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void locks_after_five_consecutive_failures_for_ten_minutes() {
        MutableClock clock = new MutableClock();
        LoginAttemptGuard g = new LoginAttemptGuard(clock);
        for (int i = 1; i < LoginAttemptGuard.MAX_FAILURES; i++) assertThat(g.recordFailure("kim")).isFalse();
        assertThat(g.remainingLock("kim")).isEmpty();
        assertThat(g.recordFailure("KIM")).isTrue();
        assertThat(g.remainingLock(" kim ")).contains(LoginAttemptGuard.LOCK);

        clock.advance(Duration.ofMinutes(9));
        assertThat(g.remainingLock("kim")).contains(Duration.ofMinutes(1));
        clock.advance(Duration.ofMinutes(1));
        assertThat(g.remainingLock("kim")).isEmpty();
        // 잠금이 풀리면 횟수도 처음부터
        assertThat(g.recordFailure("kim")).isFalse();
    }

    @Test
    void success_resets_the_counter_and_users_are_independent() {
        LoginAttemptGuard g = new LoginAttemptGuard(new MutableClock());
        for (int i = 0; i < 4; i++) g.recordFailure("lee");
        g.recordSuccess("lee");
        for (int i = 0; i < 4; i++) assertThat(g.recordFailure("lee")).isFalse();
        for (int i = 0; i < 5; i++) g.recordFailure("park");
        assertThat(g.remainingLock("park")).isPresent();
        assertThat(g.remainingLock("lee")).isEmpty();
    }

    @Test
    void humanize_formats_minutes_and_seconds() {
        assertThat(LoginAttemptGuard.humanize(Duration.ofSeconds(570))).isEqualTo("9분 30초");
        assertThat(LoginAttemptGuard.humanize(Duration.ofMinutes(10))).isEqualTo("10분");
        assertThat(LoginAttemptGuard.humanize(Duration.ofMillis(200))).isEqualTo("1초");
    }

    @Test
    void lockIsPerUsernameAndIp_otherIpStillAllowed() {
        LoginAttemptGuard g = new LoginAttemptGuard(new MutableClock());
        for (int i = 0; i < LoginAttemptGuard.MAX_FAILURES; i++) {
            assertThat(g.tryAcquire("admin", "10.0.0.9")).isEmpty();
            g.release("admin", "10.0.0.9", false);
        }
        assertThat(g.tryAcquire("admin", "10.0.0.9")).isPresent();   // 공격자 IP 는 잠김
        assertThat(g.tryAcquire("admin", "10.0.0.1")).isEmpty();     // 계정 주인 IP 는 그대로 로그인 가능
    }

    @Test
    void concurrentAttemptsCannotExceedLimit() {
        LoginAttemptGuard g = new LoginAttemptGuard(new MutableClock());
        int granted = 0;
        for (int i = 0; i < 20; i++) if (g.tryAcquire("bob", "1.2.3.4").isEmpty()) granted++;  // 끝나기 전에 한꺼번에 시도
        assertThat(granted).isEqualTo(LoginAttemptGuard.MAX_FAILURES);
    }

    @Test
    void successClearsFailures() {
        LoginAttemptGuard g = new LoginAttemptGuard(new MutableClock());
        for (int i = 0; i < LoginAttemptGuard.MAX_FAILURES - 1; i++) { g.tryAcquire("amy", "ip"); g.release("amy", "ip", false); }
        g.tryAcquire("amy", "ip"); g.release("amy", "ip", true);
        for (int i = 0; i < LoginAttemptGuard.MAX_FAILURES - 1; i++) { assertThat(g.tryAcquire("amy", "ip")).isEmpty(); g.release("amy", "ip", false); }
    }

    @Test
    void userWideLimitAcrossIps() {
        LoginAttemptGuard g = new LoginAttemptGuard(new MutableClock());
        for (int i = 0; i < LoginAttemptGuard.MAX_USER_FAILURES; i++) {
            String ip = "10.0." + (i / 4) + "." + i;   // IP 를 바꿔 가며 한도 회피 시도
            assertThat(g.tryAcquire("carl", ip)).isEmpty();
            g.release("carl", ip, false);
        }
        assertThat(g.tryAcquire("carl", "9.9.9.9")).isPresent();
    }

    @Test
    void abortKeepsFailures() {
        LoginAttemptGuard g = new LoginAttemptGuard(new MutableClock());
        for (int i = 0; i < LoginAttemptGuard.MAX_FAILURES - 1; i++) { g.tryAcquire("dan", "ip"); g.release("dan", "ip", false); }
        g.tryAcquire("dan", "ip"); g.abort("dan", "ip");
        g.tryAcquire("dan", "ip"); g.release("dan", "ip", false);
        assertThat(g.tryAcquire("dan", "ip")).isPresent();
    }
}
