package com.workblog.related;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RelatedScorerTest {

    @Test
    void score_is_common_tags_times_two_plus_same_category() {
        assertThat(RelatedScorer.score(List.of("서버", "AWS", "배포"), 1L, List.of("aws", "배포", "기타"), 1L)).isEqualTo(5);
        assertThat(RelatedScorer.score(List.of("서버", "AWS"), 1L, List.of("aws"), 2L)).isEqualTo(2);
        assertThat(RelatedScorer.score(List.of("서버"), 1L, List.of("다른"), 1L)).isEqualTo(1);
        assertThat(RelatedScorer.score(List.of(), 1L, List.of(), 2L)).isZero();
    }

    @Test
    void common_tags_ignore_case_and_spaces_and_duplicates() {
        assertThat(RelatedScorer.commonTags(List.of(" Docker ", "docker", "k8s"), List.of("DOCKER", "K8S"))).containsExactly("Docker", "k8s");
        assertThat(RelatedScorer.commonTags(List.of(""), List.of(""))).isEmpty();
    }

    record Item(long id, int score, Instant time) {}

    @Test
    void top_drops_zero_scores_and_orders_by_score_then_recency() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        List<Item> items = List.of(
                new Item(1, 1, t0), new Item(2, 5, t0), new Item(3, 0, t0.plusSeconds(99)),
                new Item(4, 5, t0.plusSeconds(10)), new Item(5, 3, null), new Item(6, 2, t0), new Item(7, 2, t0), new Item(8, 1, t0.plusSeconds(1)));
        List<Item> top = RelatedScorer.top(items, Item::score, Item::time, Item::id, 5);
        assertThat(top).extracting(Item::id).containsExactly(4L, 2L, 5L, 7L, 6L);
    }
}
