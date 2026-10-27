package com.workblog.related;

import com.workblog.post.Post;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * 관련 글·백링크 후보. 여기서는 삭제·임시 저장만 걸러 후보를 줄이고, 열람 가능 여부(비공개·카테고리 열람 제한 등)는
 * 서비스에서 PostService.canView 로 한 건씩 판정한다(규칙이 한곳에만 있도록).
 */
public interface RelatedRepository extends Repository<Post, Long> {

    /** 같은 카테고리이거나 태그가 하나라도 겹치는 발행 글(최근 순 limit 개). tags 는 소문자로 정규화해 넘긴다. */
    @Query(value = "SELECT p.* FROM posts p WHERE p.id <> :id AND p.deleted_at IS NULL AND p.status = 'PUBLISHED' " +
            " AND (p.category_id = :categoryId OR EXISTS (SELECT 1 FROM unnest(string_to_array(p.tags, ',')) t(tag) WHERE lower(trim(t.tag)) IN (:tags))) " +
            " ORDER BY COALESCE(p.published_at, p.created_at) DESC, p.id DESC LIMIT :limit", nativeQuery = true)
    List<Post> candidates(@Param("id") long id, @Param("categoryId") long categoryId, @Param("tags") Collection<String> tags, @Param("limit") int limit);

    /** 본문에 /posts/{id} 링크(뒤에 숫자가 이어지지 않는 것)가 있는 발행 글. 최근 수정 순. */
    @Query(value = "SELECT p.* FROM posts p WHERE p.id <> :id AND p.deleted_at IS NULL AND p.status = 'PUBLISHED' " +
            " AND p.content ~ CONCAT('/posts/', CAST(:id AS text), '([^0-9]|$)') " +
            " ORDER BY p.updated_at DESC, p.id DESC LIMIT :limit", nativeQuery = true)
    List<Post> linking(@Param("id") long id, @Param("limit") int limit);
}
