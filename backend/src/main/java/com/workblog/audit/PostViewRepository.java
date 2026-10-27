package com.workblog.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface PostViewRepository extends JpaRepository<PostView, Long> {

    /** 10분 안 중복 기록 생략 판단 */
    boolean existsByPostIdAndUserIdAndViewedAtAfter(Long postId, Long userId, Instant after);

    /** 관리자 감사 로그. postId/userId 0 = 전체. 기간은 [from, to). */
    @Query(value = "SELECT v FROM PostView v JOIN FETCH v.post p JOIN FETCH v.user u " +
                   " WHERE (:postId = 0 OR p.id = :postId) AND (:userId = 0 OR u.id = :userId) AND v.viewedAt >= :from AND v.viewedAt < :to " +
                   " ORDER BY v.viewedAt DESC, v.id DESC",
           countQuery = "SELECT count(v) FROM PostView v WHERE (:postId = 0 OR v.post.id = :postId) AND (:userId = 0 OR v.user.id = :userId) " +
                   " AND v.viewedAt >= :from AND v.viewedAt < :to")
    Page<PostView> search(@Param("postId") long postId, @Param("userId") long userId,
                          @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);

    /** 글의 열람자: [userId, username, displayName, department, count, lastViewedAt] 최근 열람 순. 글 작성자는 뺀다. */
    @Query("SELECT u.id, u.username, u.displayName, u.department, count(v), max(v.viewedAt) FROM PostView v JOIN v.user u " +
           " WHERE v.post.id = :postId AND u.id <> :authorId GROUP BY u.id, u.username, u.displayName, u.department ORDER BY max(v.viewedAt) DESC")
    List<Object[]> viewers(@Param("postId") Long postId, @Param("authorId") Long authorId, Pageable pageable);

    /** 작성자 제외 열람자 수(서로 다른 사람) */
    @Query("SELECT count(DISTINCT v.user.id) FROM PostView v WHERE v.post.id = :postId AND v.user.id <> :authorId")
    long countViewers(@Param("postId") Long postId, @Param("authorId") Long authorId);
}
