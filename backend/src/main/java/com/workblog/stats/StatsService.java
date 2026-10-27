package com.workblog.stats;

import com.workblog.stats.StatsDtos.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * 글 통계(X6). 기존 JPA 리포지터리를 건드리지 않고 JdbcTemplate 네이티브 SQL 로만 집계한다.
 * 집계 대상은 항상 "삭제되지 않은 + 발행 + 전체 공개" 글({@link #VISIBLE}).
 * 날짜 기준은 서울 시간(글은 TIMESTAMPTZ 로 저장되고 서버 컨테이너는 UTC 일 수 있다).
 */
@Service
public class StatsService {
    static final String VISIBLE = visible("p");
    static final String POST_DATE = " (COALESCE(p.published_at, p.created_at) AT TIME ZONE 'Asia/Seoul')::date ";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int TOP = 10;

    private final JdbcTemplate jdbc;

    /** 집계 대상 조건. alias 는 posts 테이블 별칭. */
    static String visible(String alias) {
        return " " + alias + ".deleted_at IS NULL AND " + alias + ".status = 'PUBLISHED' AND " + alias + ".visibility = 'PUBLIC' ";
    }

    public StatsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public StatsResponse stats(int days) {
        return stats(days, null);
    }

    /**
     * viewer 가 있으면 열람 제한 카테고리(CategoryAccess) 중 viewer 가 볼 수 없는 글을 집계에서 뺀다(관리자·작성자 예외).
     * viewer 가 null 이면 제한 카테고리 글을 모두 뺀다.
     */
    @Transactional(readOnly = true)
    public StatsResponse stats(int days, com.workblog.user.User viewer) {
        String vp = restrict("p", viewer), vpp = restrict("pp", viewer);
        int d = Math.max(1, Math.min(days, 3650));
        LocalDate today = LocalDate.now(SEOUL);
        LocalDate from = today.minusDays(d - 1L);
        Date fromSql = Date.valueOf(from);
        Date toSql = Date.valueOf(today);

        return new StatsResponse(d, totals(fromSql, toSql, vp, vpp), daily(fromSql, toSql, vp),
                topPosts("p.view_count DESC, like_count DESC", vp),
                topPosts("like_count DESC, p.view_count DESC", vp),
                byCategory(vp), byDepartment(vp), topAuthors(vp));
    }

    /** 열람 제한 조건 조각(AND 로 시작). */
    static String restrict(String alias, com.workblog.user.User viewer) {
        if (viewer == null) {
            return " AND NOT EXISTS (SELECT 1 FROM categories pc JOIN categories rc ON pc.path LIKE CONCAT(rc.path, '%') WHERE pc.id = "
                    + alias + ".category_id AND rc.restricted_departments <> '') ";
        }
        return viewer.isAdmin() ? "" : " AND " + com.workblog.category.CategoryAccess.sqlAllowed(alias, viewer.getId());
    }

    private Totals totals(Date from, Date to, String vp, String vpp) {
        Map<String, Object> r = jdbc.queryForMap(
                "SELECT count(*) AS posts, " +
                "       count(*) FILTER (WHERE " + POST_DATE + " BETWEEN ? AND ?) AS in_period, " +
                "       count(DISTINCT p.author_id) AS authors, " +
                "       COALESCE(sum(p.view_count), 0) AS views, " +
                "       (SELECT count(*) FROM comments cm JOIN posts pp ON pp.id = cm.post_id WHERE cm.deleted_at IS NULL AND " + visible("pp") + vpp + ") AS comments, " +
                "       (SELECT count(*) FROM post_likes l JOIN posts pp ON pp.id = l.post_id WHERE " + visible("pp") + vpp + ") AS likes " +
                "FROM posts p WHERE " + VISIBLE + vp, from, to);
        return new Totals(n(r.get("posts")), n(r.get("in_period")), n(r.get("authors")), n(r.get("comments")), n(r.get("likes")), n(r.get("views")));
    }

    private List<DayCount> daily(Date from, Date to, String vp) {
        return jdbc.query(
                "SELECT g.d::date AS d, (SELECT count(*) FROM posts p WHERE " + VISIBLE + vp + " AND " + POST_DATE + " = g.d::date) AS c " +
                "FROM generate_series(?::date, ?::date, interval '1 day') AS g(d) ORDER BY g.d",
                (rs, i) -> new DayCount(rs.getDate("d").toLocalDate(), rs.getLong("c")), from, to);
    }

    private List<PostRow> topPosts(String orderBy, String vp) {
        return jdbc.query(
                "SELECT p.id, p.title, u.display_name, p.view_count, " +
                "       (SELECT count(*) FROM post_likes l WHERE l.post_id = p.id) AS like_count, " +
                "       (SELECT count(*) FROM comments cm WHERE cm.post_id = p.id AND cm.deleted_at IS NULL) AS comment_count " +
                "FROM posts p JOIN users u ON u.id = p.author_id WHERE " + VISIBLE + vp +
                " ORDER BY " + orderBy + ", COALESCE(p.published_at, p.created_at) DESC LIMIT " + TOP,
                (rs, i) -> new PostRow(rs.getLong("id"), rs.getString("title"), rs.getString("display_name"),
                        rs.getLong("view_count"), rs.getLong("like_count"), rs.getLong("comment_count")));
    }

    /** 1단계 카테고리 기준: 글의 카테고리 path('/1/5/9/') 첫 id 가 루트. */
    private List<NameCount> byCategory(String vp) {
        return jdbc.query(
                "SELECT r.name, count(*) AS c FROM posts p JOIN categories c ON c.id = p.category_id " +
                "JOIN categories r ON r.id = split_part(c.path, '/', 2)::bigint WHERE " + VISIBLE + vp +
                " GROUP BY r.id, r.name, r.sort_order ORDER BY c DESC, r.sort_order, r.name",
                (rs, i) -> new NameCount(rs.getString("name"), rs.getLong("c")));
    }

    private List<NameCount> byDepartment(String vp) {
        return jdbc.query(
                "SELECT COALESCE(NULLIF(trim(u.department), ''), '부서 미지정') AS name, count(*) AS c " +
                "FROM posts p JOIN users u ON u.id = p.author_id WHERE " + VISIBLE + vp +
                " GROUP BY 1 ORDER BY c DESC, name",
                (rs, i) -> new NameCount(rs.getString("name"), rs.getLong("c")));
    }

    private List<NameCount> topAuthors(String vp) {
        return jdbc.query(
                "SELECT u.display_name || CASE WHEN COALESCE(u.department, '') <> '' THEN ' · ' || u.department ELSE '' END AS name, count(*) AS c " +
                "FROM posts p JOIN users u ON u.id = p.author_id WHERE " + VISIBLE + vp +
                " GROUP BY u.id, u.display_name, u.department ORDER BY c DESC, u.display_name LIMIT " + TOP,
                (rs, i) -> new NameCount(rs.getString("name"), rs.getLong("c")));
    }

    private static long n(Object o) {
        return o instanceof Number num ? num.longValue() : 0L;
    }
}
