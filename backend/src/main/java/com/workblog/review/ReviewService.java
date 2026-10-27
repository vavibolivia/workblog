package com.workblog.review;

import com.workblog.common.ApiException;
import com.workblog.common.PageResponse;
import com.workblog.post.Post;
import com.workblog.post.PostRepository;
import com.workblog.post.PostService;
import com.workblog.user.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 오래된 글 재검토. '내용 확인 완료'는 posts.reviewed_at 만 바꾼다(버전·리비전·updated_at 그대로).
 * 엔티티에 매핑하지 않고 JDBC 로 읽고 쓴다(Post 저장 시 auditing 이 updated_at 을 바꾸지 않도록).
 */
@Service
@Transactional
public class ReviewService {
    /** 마지막 리비전 시각(없으면 updated_at). */
    private static final String LAST_MODIFIED = "COALESCE((SELECT max(r.created_at) FROM post_revisions r WHERE r.post_id = p.id), p.updated_at)";

    public record ReviewStatus(Long postId, int staleDays, Instant lastModifiedAt, Instant reviewedAt, Instant lastCheckedAt,
                               long daysSince, boolean stale, boolean canConfirm) {}

    public record StaleRow(Long id, String title, String authorName, String categoryName, long viewCount,
                           Instant lastModifiedAt, Instant reviewedAt, Instant lastCheckedAt, long daysSince) {}

    private final PostRepository posts;
    private final JdbcTemplate jdbc;
    private final ReviewProperties props;

    public ReviewService(PostRepository posts, JdbcTemplate jdbc, ReviewProperties props) {
        this.posts = posts;
        this.jdbc = jdbc;
        this.props = props;
    }

    @Transactional(readOnly = true)
    public ReviewStatus status(Long id, User viewer) {
        Post p = loadVisible(id, viewer);
        return statusOf(p, viewer, Instant.now());
    }

    /** 작성자·관리자: 오래된 발행 글의 내용이 아직 맞다고 확인. */
    public ReviewStatus confirm(Long id, User actor) {
        Post p = loadVisible(id, actor);
        if (!PostService.canEdit(p, actor)) throw ApiException.forbidden("작성자와 관리자만 내용 확인을 할 수 있습니다.");
        if (p.isDeleted() || p.getStatus() != Post.Status.PUBLISHED) throw ApiException.badRequest("발행한 글만 내용 확인을 할 수 있습니다.");
        jdbc.update("UPDATE posts SET reviewed_at = now() WHERE id = ?", id);
        return statusOf(p, actor, Instant.now());
    }

    @Transactional(readOnly = true)
    public PageResponse<StaleRow> stale(int page, int size) {
        int pg = Math.max(page, 0), sz = Math.min(Math.max(size, 1), 100);
        Instant now = Instant.now();
        Timestamp cutoff = Timestamp.from(ReviewPolicy.cutoff(now, props.staleDays()));
        String base = "SELECT p.id, p.title, p.view_count, u.display_name, c.name AS category_name, p.reviewed_at, " + LAST_MODIFIED + " AS last_modified " +
                "FROM posts p JOIN users u ON u.id = p.author_id JOIN categories c ON c.id = p.category_id " +
                "WHERE p.deleted_at IS NULL AND p.status = 'PUBLISHED'";
        String checked = "GREATEST(s.last_modified, COALESCE(s.reviewed_at, s.last_modified))";
        if (props.staleDays() <= 0) return new PageResponse<>(List.of(), pg, sz, 0, 0);
        Long total = jdbc.queryForObject("SELECT count(*) FROM (" + base + ") s WHERE " + checked + " <= ?", Long.class, cutoff);
        List<StaleRow> rows = jdbc.query("SELECT s.*, " + checked + " AS last_checked FROM (" + base + ") s WHERE " + checked + " <= ? " +
                        "ORDER BY last_checked ASC, s.id ASC LIMIT ? OFFSET ?",
                (rs, i) -> {
                    Instant lastChecked = instant(rs.getTimestamp("last_checked"));
                    return new StaleRow(rs.getLong("id"), rs.getString("title"), rs.getString("display_name"), rs.getString("category_name"),
                            rs.getLong("view_count"), instant(rs.getTimestamp("last_modified")), instant(rs.getTimestamp("reviewed_at")),
                            lastChecked, ReviewPolicy.daysSince(lastChecked, now));
                }, cutoff, sz, (long) pg * sz);
        long t = total == null ? 0 : total;
        return new PageResponse<>(rows, pg, sz, t, (int) ((t + sz - 1) / sz));
    }

    private Post loadVisible(Long id, User viewer) {
        Post p = posts.findById(id).orElseThrow(() -> ApiException.notFound("글"));
        if (!PostService.canView(p, viewer)) throw ApiException.notFound("글");
        return p;
    }

    private ReviewStatus statusOf(Post p, User viewer, Instant now) {
        Map<String, Object> row = jdbc.queryForMap("SELECT p.reviewed_at, " + LAST_MODIFIED + " AS last_modified FROM posts p WHERE p.id = ?", p.getId());
        Instant reviewed = instantOf(row.get("reviewed_at"));
        Instant modified = instantOf(row.get("last_modified"));
        Instant checked = ReviewPolicy.lastChecked(modified, reviewed);
        boolean eligible = !p.isDeleted() && p.getStatus() == Post.Status.PUBLISHED;
        boolean stale = eligible && ReviewPolicy.isStale(checked, now, props.staleDays());
        return new ReviewStatus(p.getId(), props.staleDays(), modified, reviewed, checked, ReviewPolicy.daysSince(checked, now),
                stale, stale && PostService.canEdit(p, viewer));
    }

    private static Instant instantOf(Object o) {
        if (o instanceof Timestamp t) return t.toInstant();
        if (o instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        if (o instanceof Instant i) return i;
        return null;
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
