package com.workblog.stats;

import java.time.LocalDate;
import java.util.List;

/** 글 통계(X6) 응답. 삭제·임시 저장·비공개 글은 모두 제외한 '발행·전체 공개' 글 기준. */
public final class StatsDtos {
    private StatsDtos() {}

    /** 전체 합계 */
    public record Totals(long posts, long publishedInPeriod, long authors, long comments, long likes, long views) {}

    /** 일별 새 글 수(기간 안의 모든 날짜가 들어간다. 글이 없는 날은 0) */
    public record DayCount(LocalDate date, long count) {}

    /** 글 한 줄(TOP 10 표) */
    public record PostRow(long id, String title, String author, long viewCount, long likeCount, long commentCount) {}

    /** 이름·글 수 한 줄(카테고리·부서·작성자 표) */
    public record NameCount(String name, long count) {}

    public record StatsResponse(int days,
                                Totals totals,
                                List<DayCount> daily,
                                List<PostRow> mostViewed,
                                List<PostRow> mostLiked,
                                List<NameCount> byCategory,
                                List<NameCount> byDepartment,
                                List<NameCount> topAuthors) {}
}
