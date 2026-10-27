package com.workblog.revision;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PostRevisionRepository extends JpaRepository<PostRevision, Long> {

    @EntityGraph(attributePaths = {"editedBy"})
    List<PostRevision> findByPostIdOrderByVersionDesc(Long postId);

    @EntityGraph(attributePaths = {"editedBy", "post"})
    Optional<PostRevision> findByPostIdAndVersion(Long postId, int version);

    /**
     * 열람 제한 카테고리(CategoryAccess): 글 카테고리 경로 위에 제한 부서가 있고 :dept 가 그 목록에 없으면 뺀다(작성자·관리자 예외).
     * :dept 는 LOWER(TRIM(부서)), 부서가 없으면 ''.
     */
    String RESTRICT_OK = " NOT EXISTS (SELECT rc.id FROM Category rc WHERE p.category.path LIKE CONCAT(rc.path, '%') AND rc.restrictedDepartments <> '' " +
                         " AND (:dept = '' OR LOCATE(CONCAT(',', :dept, ','), CONCAT(',', LOWER(rc.restrictedDepartments), ',')) = 0)) ";

    /** Company-wide activity feed. Admin sees everything; others see published+public posts plus their own (restricted categories: members only). */
    @Query(value = "SELECT r FROM PostRevision r JOIN FETCH r.post p JOIN FETCH r.editedBy e JOIN FETCH p.author a " +
                   " WHERE (:isAdmin = TRUE OR (p.deletedAt IS NULL AND (a.id = :viewerId OR (p.status = :published AND p.visibility = :publicVis AND " + RESTRICT_OK + ")))) " +
                   " ORDER BY r.createdAt DESC",
           countQuery = "SELECT count(r) FROM PostRevision r JOIN r.post p JOIN p.author a " +
                   " WHERE (:isAdmin = TRUE OR (p.deletedAt IS NULL AND (a.id = :viewerId OR (p.status = :published AND p.visibility = :publicVis AND " + RESTRICT_OK + "))))")
    Page<PostRevision> findActivity(@Param("viewerId") Long viewerId, @Param("isAdmin") boolean isAdmin,
                                    @Param("published") com.workblog.post.Post.Status published,
                                    @Param("publicVis") com.workblog.post.Post.Visibility publicVis,
                                    @Param("dept") String dept, Pageable pageable);
}
