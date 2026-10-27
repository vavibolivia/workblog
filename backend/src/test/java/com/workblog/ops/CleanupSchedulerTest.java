package com.workblog.ops;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CleanupSchedulerTest {
    private static final Instant NOW = Instant.parse("2026-10-27T03:30:00Z");

    @Test
    void trash_cutoff_is_now_minus_retention_days() {
        assertThat(CleanupScheduler.trashCutoff(NOW, 30)).isEqualTo(Instant.parse("2026-09-27T03:30:00Z"));
        assertThat(CleanupScheduler.trashCutoff(NOW, 1)).isEqualTo(Instant.parse("2026-10-26T03:30:00Z"));
    }

    @Test
    void post_deleted_exactly_30_days_ago_is_not_yet_expired_but_older_is() {
        Instant cutoff = CleanupScheduler.trashCutoff(NOW, 30);
        Instant deleted30d = Instant.parse("2026-09-27T03:30:00Z");
        Instant deleted31d = Instant.parse("2026-09-26T03:29:59Z");
        // 스케줄러의 SQL 은 deleted_at < cutoff 이므로 정확히 30일인 글은 남고 그 이전 글은 지워진다.
        assertThat(deleted30d.isBefore(cutoff)).isFalse();
        assertThat(deleted31d.isBefore(cutoff)).isTrue();
    }

    @Test
    void orphan_cutoff_is_now_minus_hours() {
        assertThat(CleanupScheduler.orphanCutoff(NOW, 24)).isEqualTo(Instant.parse("2026-10-26T03:30:00Z"));
        assertThat(CleanupScheduler.orphanCutoff(NOW, 1)).isEqualTo(Instant.parse("2026-10-27T02:30:00Z"));
    }

    @Test
    void negative_settings_are_treated_as_zero() {
        assertThat(CleanupScheduler.trashCutoff(NOW, -5)).isEqualTo(NOW);
        assertThat(CleanupScheduler.orphanCutoff(NOW, -5)).isEqualTo(NOW);
    }
}
