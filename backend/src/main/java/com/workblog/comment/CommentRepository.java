package com.workblog.comment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    /** 글의 댓글 전체(삭제 포함), 작성 순. 작성자·부모를 함께 읽어 N+1 을 피한다. */
    @Query("SELECT c FROM Comment c JOIN FETCH c.author LEFT JOIN FETCH c.parent WHERE c.post.id = :postId ORDER BY c.createdAt ASC, c.id ASC")
    List<Comment> findAllByPost(@Param("postId") Long postId);

    @Query("SELECT count(c) FROM Comment c WHERE c.post.id = :postId AND c.deletedAt IS NULL")
    long countVisible(@Param("postId") Long postId);

    /** 목록용: 글 id 별 (삭제되지 않은) 댓글 수 [postId, count] */
    @Query("SELECT c.post.id, count(c) FROM Comment c WHERE c.post.id IN :postIds AND c.deletedAt IS NULL GROUP BY c.post.id")
    List<Object[]> countVisibleByPostIds(@Param("postIds") java.util.Collection<Long> postIds);
}
