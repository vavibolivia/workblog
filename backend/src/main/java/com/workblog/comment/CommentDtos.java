package com.workblog.comment;

import com.workblog.user.User;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class CommentDtos {
    private CommentDtos() {}

    public record CommentAuthor(Long id, String displayName, String department) {}

    /** 삭제된 댓글은 content 가 null 이고 deleted 가 true. 답글이 남아 있을 때만 목록에 포함된다. */
    public record CommentResponse(Long id, Long postId, Long parentId, CommentAuthor author, String content,
                                  boolean deleted, Instant createdAt, Instant updatedAt, boolean editable) {}

    /** parentId 가 있으면 답글. 답글의 답글은 원 댓글 아래에 붙는다. */
    public record CommentRequest(@NotBlank @Size(max = 2000) String content, Long parentId) {}

    public record CommentUpdateRequest(@NotBlank @Size(max = 2000) String content) {}

    static CommentAuthor author(User u) {
        return new CommentAuthor(u.getId(), u.getDisplayName(), u.getDepartment());
    }
}
