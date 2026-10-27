package com.workblog.related;

import com.workblog.category.CategoryDtos.CategoryRef;
import com.workblog.category.CategoryService;
import com.workblog.common.ApiException;
import com.workblog.post.Post;
import com.workblog.post.PostRepository;
import com.workblog.post.PostService;
import com.workblog.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** 관련 글(태그·카테고리) · 이 글을 언급한 글(백링크). 볼 수 없는 글은 PostService.canView 로 뺀다. */
@Service
@Transactional(readOnly = true)
public class RelatedService {
    static final int RELATED_LIMIT = 5;
    static final int CANDIDATE_LIMIT = 200;
    static final int BACKLINK_LIMIT = 30;

    public record RelatedPost(Long id, String title, String summary, CategoryRef category, List<String> tags, String authorName,
                              Instant publishedAt, Instant updatedAt, int score, List<String> commonTags) {}

    public record RelatedResponse(List<RelatedPost> related, List<RelatedPost> backlinks) {}

    private final PostRepository posts;
    private final RelatedRepository repo;
    private final CategoryService categories;

    public RelatedService(PostRepository posts, RelatedRepository repo, CategoryService categories) {
        this.posts = posts;
        this.repo = repo;
        this.categories = categories;
    }

    public RelatedResponse forPost(Long id, User viewer) {
        Post p = posts.findById(id).orElseThrow(() -> ApiException.notFound("글"));
        if (!PostService.canView(p, viewer)) throw ApiException.notFound("글");
        List<String> tags = p.getTagList();
        // IN () 은 문법 오류라 태그가 없으면 어떤 태그와도 겹치지 않는 값 하나를 넣는다
        List<String> normTags = tags.isEmpty() ? List.of("\u0001") : tags.stream().map(t -> t.trim().toLowerCase(Locale.ROOT)).distinct().toList();
        Long catId = p.getCategory().getId();

        List<Post> candidates = repo.candidates(id, catId, normTags, CANDIDATE_LIMIT).stream()
                .filter(c -> PostService.canView(c, viewer)).toList();
        List<Post> top = RelatedScorer.top(candidates,
                c -> RelatedScorer.score(tags, catId, c.getTagList(), c.getCategory().getId()),
                c -> c.getPublishedAt() != null ? c.getPublishedAt() : c.getCreatedAt(),
                Post::getId, RELATED_LIMIT);
        List<RelatedPost> related = top.stream().map(c -> toDto(c,
                RelatedScorer.score(tags, catId, c.getTagList(), c.getCategory().getId()),
                RelatedScorer.commonTags(c.getTagList(), tags))).toList();

        List<RelatedPost> backlinks = repo.linking(id, BACKLINK_LIMIT).stream()
                .filter(c -> PostService.canView(c, viewer))
                .map(c -> toDto(c, 0, List.of())).toList();
        return new RelatedResponse(related, backlinks);
    }

    private RelatedPost toDto(Post c, int score, List<String> common) {
        return new RelatedPost(c.getId(), c.getTitle(), c.getSummary(), categories.ref(c.getCategory()), c.getTagList(),
                c.getAuthor().getDisplayName(), c.getPublishedAt(), c.getUpdatedAt(), score, common);
    }
}
