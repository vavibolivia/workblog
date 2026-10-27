package com.workblog.post;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PostRepository extends JpaRepository<Post, Long> {

    /** 발행 + 전체 공개 글은 모두에게, 임시 저장·비공개 글은 작성자(와 명시 필터를 쓴 관리자)에게만. */
    String VISIBLE = " p.deleted_at IS NULL AND ((p.status = 'PUBLISHED' AND p.visibility = 'PUBLIC') OR p.author_id = :viewerId OR :isAdmin = TRUE) ";

    /** 카테고리 필터는 하위 포함: c.path 가 선택한 카테고리의 path 로 시작하면 포함. */
    String FILTER = VISIBLE +
            " AND (:q = '' OR p.title ILIKE CONCAT('%', :q, '%') OR p.content ILIKE CONCAT('%', :q, '%') OR p.tags ILIKE CONCAT('%', :q, '%') " +
            "      OR u.display_name ILIKE CONCAT('%', :q, '%') OR c.name ILIKE CONCAT('%', :q, '%')) " +
            " AND (:catPath = '' OR c.path LIKE CONCAT(:catPath, '%')) " +
            " AND (:tag = '' OR CONCAT(',', p.tags, ',') ILIKE CONCAT('%,', :tag, ',%')) " +
            " AND (:authorId = 0 OR p.author_id = :authorId) " +
            " AND (:status = '' OR p.status = :status) ";

    String FROM = " FROM posts p JOIN users u ON u.id = p.author_id JOIN categories c ON c.id = p.category_id WHERE ";

    /**
     * 정렬(sort): LATEST 최신순(기본) · POPULAR 인기순(공감 수 → 조회수 → 최신) · VIEWS 조회순 · COMMENTS 댓글 많은 순 · OLDEST 오래된순.
     * 검색어가 없을 때는 공지(고정·발행) 글이 항상 맨 위, 검색어가 있고 최신순이면 유사도 순을 먼저 본다.
     */
    @Query(value = "SELECT p.* " + FROM + FILTER +
            " ORDER BY CASE WHEN :q = '' AND p.pinned AND p.status = 'PUBLISHED' THEN 1 ELSE 0 END DESC, " +
            " CASE WHEN :q = '' OR :sort <> 'LATEST' THEN 0 ELSE GREATEST(similarity(p.title, :q), similarity(p.tags, :q)) END DESC, " +
            " CASE WHEN :sort = 'POPULAR' THEN (SELECT count(*) FROM post_likes l WHERE l.post_id = p.id) ELSE 0 END DESC, " +
            " CASE WHEN :sort IN ('POPULAR', 'VIEWS') THEN p.view_count ELSE 0 END DESC, " +
            " CASE WHEN :sort = 'COMMENTS' THEN (SELECT count(*) FROM comments cm WHERE cm.post_id = p.id AND cm.deleted_at IS NULL) ELSE 0 END DESC, " +
            " CASE WHEN :sort = 'OLDEST' THEN COALESCE(p.published_at, p.created_at) END ASC, " +
            " COALESCE(p.published_at, p.created_at) DESC",
           countQuery = "SELECT count(*) " + FROM + FILTER,
           nativeQuery = true)
    Page<Post> search(@Param("q") String q,
                      @Param("catPath") String catPath,
                      @Param("tag") String tag,
                      @Param("authorId") long authorId,
                      @Param("status") String status,
                      @Param("viewerId") long viewerId,
                      @Param("isAdmin") boolean isAdmin,
                      @Param("sort") String sort,
                      Pageable pageable);

    @Query(value = "SELECT p.tags FROM posts p WHERE p.deleted_at IS NULL AND p.status = 'PUBLISHED' AND p.visibility = 'PUBLIC' AND p.tags <> ''", nativeQuery = true)
    List<String> allPublishedTags();

    @Query(value = "SELECT p.* FROM posts p WHERE p.deleted_at IS NOT NULL ORDER BY p.deleted_at DESC", nativeQuery = true)
    List<Post> findDeleted();

    long countByAuthorId(Long authorId);
}
