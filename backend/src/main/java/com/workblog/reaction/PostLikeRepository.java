package com.workblog.reaction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface PostLikeRepository extends JpaRepository<PostLike, PostLike.Id> {

    long countByIdPostId(Long postId);

    /** [postId, count] 쌍. 목록 한 페이지의 공감 수를 한 번에 가져온다. */
    @Query("SELECT l.id.postId, count(l) FROM PostLike l WHERE l.id.postId IN :postIds GROUP BY l.id.postId")
    List<Object[]> countByPostIds(@Param("postIds") Collection<Long> postIds);

    @Query("SELECT l.id.postId FROM PostLike l WHERE l.id.userId = :userId AND l.id.postId IN :postIds")
    List<Long> likedPostIds(@Param("userId") Long userId, @Param("postIds") Collection<Long> postIds);
}
