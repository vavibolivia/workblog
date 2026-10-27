package com.workblog.review;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewPolicyTest {
    private static final Instant NOW = Instant.parse("2026-10-27T03:00:00Z");

    @Test
    void last_checked_is_the_later_of_modified_and_reviewed() {
        Instant m = NOW.minus(Duration.ofDays(300)), r = NOW.minus(Duration.ofDays(10));
        assertThat(ReviewPolicy.lastChecked(m, r)).isEqualTo(r);
        assertThat(ReviewPolicy.lastChecked(r, m)).isEqualTo(r);
        assertThat(ReviewPolicy.lastChecked(m, null)).isEqualTo(m);
        assertThat(ReviewPolicy.lastChecked(null, r)).isEqualTo(r);
        assertThat(ReviewPolicy.lastChecked(null, null)).isNull();
    }

    @Test
    void stale_from_exactly_stale_days() {
        assertThat(ReviewPolicy.isStale(NOW.minus(Duration.ofDays(180)), NOW, 180)).isTrue();
        assertThat(ReviewPolicy.isStale(NOW.minus(Duration.ofDays(180)).plusSeconds(1), NOW, 180)).isFalse();
        assertThat(ReviewPolicy.isStale(NOW.minus(Duration.ofDays(400)), NOW, 180)).isTrue();
        assertThat(ReviewPolicy.daysSince(NOW.minus(Duration.ofDays(200)).minusSeconds(5), NOW)).isEqualTo(200);
    }

    @Test
    void review_resets_the_clock() {
        Instant modified = NOW.minus(Duration.ofDays(365));
        Instant reviewed = NOW.minus(Duration.ofDays(3));
        assertThat(ReviewPolicy.isStale(ReviewPolicy.lastChecked(modified, null), NOW, 180)).isTrue();
        assertThat(ReviewPolicy.isStale(ReviewPolicy.lastChecked(modified, reviewed), NOW, 180)).isFalse();
    }

    @Test
    void zero_days_disables_and_future_is_zero() {
        assertThat(ReviewPolicy.isStale(NOW.minus(Duration.ofDays(999)), NOW, 0)).isFalse();
        assertThat(ReviewPolicy.isStale(null, NOW, 180)).isFalse();
        assertThat(ReviewPolicy.daysSince(NOW.plusSeconds(60), NOW)).isZero();
        assertThat(ReviewPolicy.cutoff(NOW, 180)).isEqualTo(NOW.minus(Duration.ofDays(180)));
    }
}
