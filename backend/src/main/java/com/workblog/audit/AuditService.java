package com.workblog.audit;

import com.workblog.audit.AuditDtos.*;
import com.workblog.common.ApiException;
import com.workblog.common.PageResponse;
import com.workblog.post.Post;
import com.workblog.post.PostRepository;
import com.workblog.post.PostService;
import com.workblog.user.User;
import com.workblog.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 열람 감사 로그. 글 상세 조회(GET /api/posts/{id}, view 값과 무관)가 성공할 때마다 기록하되
 * 같은 사용자·글은 {@link #DEDUP_WINDOW} 안에 다시 기록하지 않는다.
 * 기록 실패는 글 조회를 막지 않는다(경고 로그만).
 */
@Service
@Transactional
public class AuditService {
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    public static final Duration DEDUP_WINDOW = Duration.ofMinutes(10);
    static final int VIEWERS_LIMIT = 50;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final PostViewRepository views;
    private final PostRepository posts;
    private final UserRepository users;

    public AuditService(PostViewRepository views, PostRepository posts, UserRepository users) {
        this.views = views;
        this.posts = posts;
        this.users = users;
    }

    public void recordView(Long postId, User viewer, PostView.Via via) {
        try {
            Instant now = Instant.now();
            if (views.existsByPostIdAndUserIdAndViewedAtAfter(postId, viewer.getId(), now.minus(DEDUP_WINDOW))) return;
            views.save(new PostView(posts.getReferenceById(postId), users.getReferenceById(viewer.getId()), now, via));
        } catch (RuntimeException e) {
            log.warn("열람 기록 실패 post={} user={}: {}", postId, viewer.getId(), e.toString());
        }
    }

    /** 작성자·관리자만: 이 글의 최근 열람자 50명(작성자 제외). */
    @Transactional(readOnly = true)
    public ViewersResponse viewers(Long postId, User viewer) {
        Post p = posts.findById(postId).orElseThrow(() -> ApiException.notFound("글"));
        if (!PostService.canView(p, viewer)) throw ApiException.notFound("글");
        if (!PostService.canEdit(p, viewer)) throw ApiException.forbidden("열람자 목록은 작성자와 관리자만 볼 수 있습니다.");
        Long authorId = p.getAuthor().getId();
        List<ViewerRow> rows = views.viewers(postId, authorId, PageRequest.of(0, VIEWERS_LIMIT)).stream()
                .map(r -> new ViewerRow(new UserRef((Long) r[0], (String) r[1], (String) r[2], (String) r[3]),
                        ((Number) r[4]).longValue(), (Instant) r[5]))
                .toList();
        return new ViewersResponse(postId, views.countViewers(postId, authorId), rows);
    }

    /** 관리자 감사 로그 검색. from/to 는 서울 날짜(둘 다 포함), 비우면 제한 없음. */
    @Transactional(readOnly = true)
    public PageResponse<ViewLogEntry> search(Long postId, Long userId, LocalDate from, LocalDate to, int page, int size, User actor) {
        if (!actor.isAdmin()) throw ApiException.forbidden("감사 로그는 관리자만 볼 수 있습니다.");
        if (from != null && to != null && from.isAfter(to)) throw ApiException.badRequest("시작일이 종료일보다 늦습니다.");
        Instant f = from == null ? Instant.EPOCH : from.atStartOfDay(SEOUL).toInstant();
        Instant t = to == null ? Instant.parse("9999-01-01T00:00:00Z") : to.plusDays(1).atStartOfDay(SEOUL).toInstant();
        var result = views.search(postId == null ? 0 : postId, userId == null ? 0 : userId, f, t,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200)));
        return PageResponse.of(result, v -> {
            User u = v.getUser();
            return new ViewLogEntry(v.getId(), v.getPost().getId(), v.getPost().getTitle(), v.getPost().isDeleted(),
                    new UserRef(u.getId(), u.getUsername(), u.getDisplayName(), u.getDepartment()), v.getViewedAt(), v.getVia());
        });
    }
}
