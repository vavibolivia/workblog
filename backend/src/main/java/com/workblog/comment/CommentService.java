package com.workblog.comment;

import com.workblog.comment.CommentDtos.*;
import com.workblog.common.ApiException;
import com.workblog.post.Post;
import com.workblog.post.PostRepository;
import com.workblog.post.PostService;
import com.workblog.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@Transactional
public class CommentService {
    private final CommentRepository comments;
    private final PostRepository posts;

    public CommentService(CommentRepository comments, PostRepository posts) {
        this.comments = comments;
        this.posts = posts;
    }

    // ---------- queries ----------

    /** 평면 목록(작성 순). 삭제된 댓글은 답글이 남아 있을 때만 자리 표시용으로 포함한다. */
    @Transactional(readOnly = true)
    public List<CommentResponse> list(Long postId, User viewer) {
        loadViewable(postId, viewer);
        List<Comment> all = comments.findAllByPost(postId);
        Set<Long> parentsWithLiveReplies = new HashSet<>();
        for (Comment c : all) {
            if (c.getParent() != null && !c.isDeleted()) parentsWithLiveReplies.add(c.getParent().getId());
        }
        return all.stream()
                .filter(c -> !c.isDeleted() || parentsWithLiveReplies.contains(c.getId()))
                .map(c -> toResponse(c, viewer))
                .toList();
    }

    @Transactional(readOnly = true)
    public long count(Long postId) {
        return comments.countVisible(postId);
    }

    // ---------- commands ----------

    public CommentResponse create(Long postId, CommentRequest req, User author) {
        Post post = loadViewable(postId, author);
        if (post.isDeleted()) throw ApiException.badRequest("삭제된 글에는 댓글을 달 수 없습니다.");
        Comment parent = null;
        if (req.parentId() != null) {
            parent = comments.findById(req.parentId()).orElseThrow(() -> ApiException.notFound("댓글"));
            if (!parent.getPost().getId().equals(postId)) throw ApiException.notFound("댓글");
            // 답글은 1단계만: 답글에 단 답글은 원 댓글 아래에 붙인다.
            if (parent.getParent() != null) parent = parent.getParent();
        }
        Comment c = comments.save(new Comment(post, parent, author, req.content().trim()));
        return toResponse(c, author);
    }

    public CommentResponse update(Long postId, Long id, CommentUpdateRequest req, User editor) {
        Comment c = loadEditable(postId, id, editor);
        c.edit(req.content().trim());
        return toResponse(c, editor);
    }

    public void delete(Long postId, Long id, User editor) {
        Comment c = loadEditable(postId, id, editor);
        c.softDelete();
    }

    // ---------- helpers ----------

    /** 글 열람 규칙은 PostService.canView 그대로: 공개 발행 글은 모두, 임시 저장·비공개·삭제 글은 작성자와 관리자만. */
    private Post loadViewable(Long postId, User viewer) {
        Post p = posts.findById(postId).orElseThrow(() -> ApiException.notFound("글"));
        if (!PostService.canView(p, viewer)) throw ApiException.notFound("글");
        return p;
    }

    private Comment loadEditable(Long postId, Long id, User editor) {
        Post post = loadViewable(postId, editor);
        if (post.isDeleted()) throw ApiException.badRequest("삭제된 글의 댓글은 바꿀 수 없습니다.");
        Comment c = comments.findById(id).orElseThrow(() -> ApiException.notFound("댓글"));
        if (!c.getPost().getId().equals(postId) || c.isDeleted()) throw ApiException.notFound("댓글");
        if (!canEdit(c, editor)) throw ApiException.forbidden("이 댓글을 수정할 권한이 없습니다.");
        return c;
    }

    static boolean canEdit(Comment c, User viewer) {
        return viewer.isAdmin() || c.getAuthor().getId().equals(viewer.getId());
    }

    private static CommentResponse toResponse(Comment c, User viewer) {
        boolean deleted = c.isDeleted();
        return new CommentResponse(c.getId(), c.getPost().getId(), c.getParentId(), CommentDtos.author(c.getAuthor()),
                deleted ? null : c.getContent(), deleted, c.getCreatedAt(), c.getUpdatedAt(),
                !deleted && canEdit(c, viewer));
    }
}
