package com.workblog.review;

import java.time.Duration;
import java.time.Instant;

/**
 * 재검토 기준일 계산. 기준일 = max(마지막 수정, 내용 확인 시각).
 * '마지막 수정'은 글의 마지막 리비전 시각을 쓴다(posts.updated_at 은 조회수 증가 같은 저장에도 바뀌어 기준으로 쓸 수 없다).
 */
public final class ReviewPolicy {
    private ReviewPolicy() {}

    public static Instant lastChecked(Instant lastModified, Instant reviewedAt) {
        if (lastModified == null) return reviewedAt;
        if (reviewedAt == null) return lastModified;
        return reviewedAt.isAfter(lastModified) ? reviewedAt : lastModified;
    }

    /** 기준일부터 지난 날 수(내림). 기준일이 없으면 0. */
    public static long daysSince(Instant lastChecked, Instant now) {
        if (lastChecked == null || now.isBefore(lastChecked)) return 0;
        return Duration.between(lastChecked, now).toDays();
    }

    /** 기준일이 staleDays 일보다 오래되었으면 true(정확히 staleDays 일째부터). staleDays <= 0 이면 기능 끔. */
    public static boolean isStale(Instant lastChecked, Instant now, int staleDays) {
        if (staleDays <= 0 || lastChecked == null) return false;
        return daysSince(lastChecked, now) >= staleDays;
    }

    public static Instant cutoff(Instant now, int staleDays) {
        return now.minus(Duration.ofDays(staleDays));
    }
}
