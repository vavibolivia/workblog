package com.workblog.post;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PostRepository extends JpaRepository<Post, Long> {

    /**
     * 카테고리 열람 제한(CategoryAccess): 글의 카테고리나 그 조상에 restricted_departments 가 있으면
     * 그 부서 사용자 · 작성자 · 관리자(users.role, :isAdmin 필터 여부와 무관)만. 부서 비교는 LOWER(TRIM()).
     */
    String RESTRICT_OK = " (p.author_id = :viewerId OR NOT EXISTS (SELECT 1 FROM categories pc JOIN categories rc ON pc.path LIKE CONCAT(rc.path, '%') " +
            " WHERE pc.id = p.category_id AND rc.restricted_departments <> '' " +
            " AND NOT EXISTS (SELECT 1 FROM users vu WHERE vu.id = :viewerId AND (vu.role = 'ADMIN' OR (COALESCE(TRIM(vu.department), '') <> '' " +
            " AND POSITION(CONCAT(',', LOWER(TRIM(vu.department)), ',') IN CONCAT(',', LOWER(rc.restricted_departments), ',')) > 0))))) ";

    /** 발행 + 전체 공개 글은 모두에게, 임시 저장·비공개 글은 작성자(와 명시 필터를 쓴 관리자)에게만. 열람 제한 카테고리는 RESTRICT_OK. */
    String VISIBLE = " p.deleted_at IS NULL AND ((p.status = 'PUBLISHED' AND p.visibility = 'PUBLIC') OR p.author_id = :viewerId OR :isAdmin = TRUE) AND " + RESTRICT_OK;

    /** 카테고리 필터는 하위 포함: c.path 가 선택한 카테고리의 path 로 시작하면 포함. */
    String FILTER = VISIBLE +
            " AND (:q = '' OR p.title ILIKE CONCAT('%', :q, '%') OR p.content ILIKE CONCAT('%', :q, '%') OR p.tags ILIKE CONCAT('%', :q, '%') " +
            "      OR u.display_name ILIKE CONCAT('%', :q, '%') OR c.name ILIKE CONCAT('%', :q, '%') " +
            // 첨부 파일 내용(엑셀·워드·PDF 등에서 꺼낸 텍스트, attachments.text_content)
            "      OR EXISTS (SELECT 1 FROM attachments att WHERE att.post_id = p.id AND att.text_content ILIKE CONCAT('%', :q, '%'))) " +
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

    /** 태그 모음은 사용자 구분 없이 같으므로 열람 제한 카테고리(하위 포함)의 글은 뺀다. */
    @Query(value = "SELECT p.tags FROM posts p WHERE p.deleted_at IS NULL AND p.status = 'PUBLISHED' AND p.visibility = 'PUBLIC' AND p.tags <> '' " +
            " AND NOT EXISTS (SELECT 1 FROM categories pc JOIN categories rc ON pc.path LIKE CONCAT(rc.path, '%') WHERE pc.id = p.category_id AND rc.restricted_departments <> '')", nativeQuery = true)
    List<String> allPublishedTags();

    @Query(value = "SELECT p.* FROM posts p WHERE p.deleted_at IS NOT NULL ORDER BY p.deleted_at DESC", nativeQuery = true)
    List<Post> findDeleted();

    long countByAuthorId(Long authorId);

    /** 조회수만 올린다(엔티티 저장을 거치지 않아 updated_at·@LastModifiedDate 가 바뀌지 않는다). */
    @Modifying
    @Query(value = "UPDATE posts SET view_count = view_count + 1 WHERE id = :id", nativeQuery = true)
    int incrementViewCount(@Param("id") Long id);
}
