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
}
