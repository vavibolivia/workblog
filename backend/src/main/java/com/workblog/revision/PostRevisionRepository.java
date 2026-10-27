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

    /** Company-wide activity feed. Admin sees everything; others see published+public posts plus their own. */
    @Query(value = "SELECT r FROM PostRevision r JOIN FETCH r.post p JOIN FETCH r.editedBy e JOIN FETCH p.author a " +
                   " WHERE (:isAdmin = TRUE OR (p.deletedAt IS NULL AND ((p.status = :published AND p.visibility = :publicVis) OR a.id = :viewerId))) " +
                   " ORDER BY r.createdAt DESC",
           countQuery = "SELECT count(r) FROM PostRevision r JOIN r.post p JOIN p.author a " +
                   " WHERE (:isAdmin = TRUE OR (p.deletedAt IS NULL AND ((p.status = :published AND p.visibility = :publicVis) OR a.id = :viewerId)))")
    Page<PostRevision> findActivity(@Param("viewerId") Long viewerId, @Param("isAdmin") boolean isAdmin,
                                    @Param("published") com.workblog.post.Post.Status published,
                                    @Param("publicVis") com.workblog.post.Post.Visibility publicVis, Pageable pageable);
}
