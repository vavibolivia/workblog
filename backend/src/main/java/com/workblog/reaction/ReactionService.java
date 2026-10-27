package com.workblog.reaction;

import com.workblog.common.ApiException;
import com.workblog.post.Post;
import com.workblog.post.PostRepository;
import com.workblog.post.PostService;
import com.workblog.reaction.ReactionDtos.LikeResponse;
import com.workblog.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@Transactional
public class ReactionService {
    private final PostLikeRepository likes;
    private final PostRepository posts;

    public ReactionService(PostLikeRepository likes, PostRepository posts) {
        this.likes = likes;
        this.posts = posts;
    }

    /** 공감 토글. 볼 수 없는 글(삭제·남의 임시 저장·비공개)은 404. */
    public LikeResponse toggle(Long postId, User user) {
        Post p = posts.findById(postId).orElseThrow(() -> ApiException.notFound("글"));
        if (p.isDeleted() || !PostService.canView(p, user)) throw ApiException.notFound("글");
        PostLike.Id id = new PostLike.Id(postId, user.getId());
        boolean liked;
        if (likes.existsById(id)) {
            likes.deleteById(id);
            liked = false;
        } else {
            likes.save(new PostLike(postId, user.getId()));
            liked = true;
        }
        likes.flush();
        return new LikeResponse(liked, likes.countByIdPostId(postId));
    }

    /** 글 여러 건의 공감 수와 내 공감 여부를 한 번에. */
    @Transactional(readOnly = true)
    public Likes forPosts(Collection<Long> postIds, User viewer) {
        if (postIds.isEmpty()) return new Likes(Map.of(), Set.of());
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : likes.countByPostIds(postIds)) counts.put((Long) row[0], (Long) row[1]);
        Set<Long> mine = new HashSet<>(likes.likedPostIds(viewer.getId(), postIds));
        return new Likes(counts, mine);
    }

    public record Likes(Map<Long, Long> counts, Set<Long> mine) {
        public long count(Long postId) { return counts.getOrDefault(postId, 0L); }
        public boolean liked(Long postId) { return mine.contains(postId); }
    }
}
