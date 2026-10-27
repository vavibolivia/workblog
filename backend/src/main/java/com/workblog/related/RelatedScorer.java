package com.workblog.related;

import java.util.*;

/** 관련 글 점수: 공통 태그 수 × 2 + 같은 카테고리 1. 태그는 대소문자·앞뒤 공백을 무시하고 비교한다. */
public final class RelatedScorer {
    private RelatedScorer() {}

    public static final int TAG_WEIGHT = 2;
    public static final int CATEGORY_WEIGHT = 1;

    public static List<String> commonTags(Collection<String> a, Collection<String> b) {
        Set<String> bn = new HashSet<>();
        for (String t : b) bn.add(norm(t));
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String t : a) {
            String n = norm(t);
            if (!n.isEmpty() && bn.contains(n) && seen.add(n)) out.add(t.trim());
        }
        return out;
    }

    public static int score(Collection<String> tagsA, Long categoryA, Collection<String> tagsB, Long categoryB) {
        int s = commonTags(tagsA, tagsB).size() * TAG_WEIGHT;
        if (categoryA != null && categoryA.equals(categoryB)) s += CATEGORY_WEIGHT;
        return s;
    }

    /** 점수 > 0 인 것만, 점수 내림차순 → 최근 순(time 내림차순) → id 내림차순으로 limit 개. */
    public static <T> List<T> top(List<T> items, java.util.function.ToIntFunction<T> score,
                                  java.util.function.Function<T, java.time.Instant> time,
                                  java.util.function.ToLongFunction<T> id, int limit) {
        return items.stream().filter(x -> score.applyAsInt(x) > 0)
                .sorted(Comparator.comparingInt(score).reversed()
                        .thenComparing(time, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Comparator.comparingLong(id).reversed()))
                .limit(limit).toList();
    }

    static String norm(String t) {
        return t == null ? "" : t.trim().toLowerCase(Locale.ROOT);
    }
}
