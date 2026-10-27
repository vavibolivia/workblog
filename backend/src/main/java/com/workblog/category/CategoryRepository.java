package com.workblog.category;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    @Query("SELECT c FROM Category c ORDER BY c.depth, c.sortOrder, c.name")
    List<Category> findAllOrdered();

    @Query("SELECT c FROM Category c WHERE c.path LIKE CONCAT(:path, '%') ORDER BY c.depth")
    List<Category> findSubtree(@Param("path") String path);

    @Query("SELECT c FROM Category c WHERE c.parent IS NULL AND lower(c.name) = lower(:name)")
    Optional<Category> findRootByName(@Param("name") String name);

    @Query("SELECT count(c) > 0 FROM Category c WHERE ((:parentId IS NULL AND c.parent IS NULL) OR c.parent.id = :parentId) " +
           "AND lower(c.name) = lower(:name) AND (:excludeId IS NULL OR c.id <> :excludeId)")
    boolean existsSibling(@Param("parentId") Long parentId, @Param("name") String name, @Param("excludeId") Long excludeId);

    @Query("SELECT COALESCE(MAX(c.sortOrder), 0) FROM Category c WHERE (:parentId IS NULL AND c.parent IS NULL) OR c.parent.id = :parentId")
    int maxSortOrder(@Param("parentId") Long parentId);

    @Query("SELECT count(c) > 0 FROM Category c WHERE c.parent.id = :parentId")
    boolean existsByParentId(@Param("parentId") Long parentId);

    /** 카테고리별 발행 글 수(직접 연결된 것만). [categoryId, count] */
    @Query(value = "SELECT p.category_id, count(*) FROM posts p WHERE p.deleted_at IS NULL AND p.status = 'PUBLISHED' GROUP BY p.category_id", nativeQuery = true)
    List<Object[]> publishedCounts();

    @Query(value = "SELECT count(*) FROM posts p WHERE p.category_id = :id AND p.deleted_at IS NULL", nativeQuery = true)
    long countLivePosts(@Param("id") Long id);

    @Query(value = "SELECT count(*) FROM posts p WHERE p.category_id = :id", nativeQuery = true)
    long countAllPosts(@Param("id") Long id);
}
