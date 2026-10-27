package com.workblog.ops;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorLogBufferTest {
    private static final Instant T0 = Instant.parse("2026-10-27T10:00:00Z");

    @BeforeEach
    void reset() { ErrorLogBuffer.clear(); }

    private static ErrorLogBuffer.Entry at(Instant t, String msg) {
        return new ErrorLogBuffer.Entry(t, "WARN", "X", msg, null);
    }

    @Test
    void keeps_only_the_latest_200_and_returns_newest_first() {
        for (int i = 0; i < 250; i++) ErrorLogBuffer.add(at(T0.plusSeconds(i), "m" + i));
        var recent = ErrorLogBuffer.recent(20);
        assertThat(recent).hasSize(20);
        assertThat(recent.get(0).message()).isEqualTo("m249");
        assertThat(recent.get(19).message()).isEqualTo("m230");
        assertThat(ErrorLogBuffer.recent(1000)).hasSize(ErrorLogBuffer.CAPACITY);
        assertThat(ErrorLogBuffer.recent(1000).get(199).message()).isEqualTo("m50");
    }

    @Test
    void count_since_counts_entries_at_or_after_the_instant() {
        ErrorLogBuffer.add(at(T0.minusSeconds(3600), "old"));
        ErrorLogBuffer.add(at(T0.minusSeconds(10), "recent1"));
        ErrorLogBuffer.add(at(T0, "recent2"));
        assertThat(ErrorLogBuffer.countSince(T0.minusSeconds(60))).isEqualTo(2);
        assertThat(ErrorLogBuffer.countSince(T0)).isEqualTo(1);
        assertThat(ErrorLogBuffer.countSince(T0.minusSeconds(7200))).isEqualTo(3);
    }

    @Test
    void logger_name_is_shortened_to_simple_class_name() {
        assertThat(ErrorLogBuffer.shortLogger("com.workblog.post.PostService")).isEqualTo("PostService");
        assertThat(ErrorLogBuffer.shortLogger("root")).isEqualTo("root");
        assertThat(ErrorLogBuffer.shortLogger(null)).isEmpty();
    }
}
