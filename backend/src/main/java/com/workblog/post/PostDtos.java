package com.workblog.post;

import com.workblog.attachment.AttachmentDtos.AttachmentResponse;
import com.workblog.category.CategoryDtos.CategoryRef;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class PostDtos {
    private PostDtos() {}

    public record AuthorSummary(Long id, String username, String displayName, String department) {}

    /** thumbnailUrl: 본문의 첫 이미지(없으면 null). 앨범형 목록용. */
    public record PostSummary(Long id, String title, String summary, String snippet, String thumbnailUrl, CategoryRef category, List<String> tags,
                              Post.Status status, Post.Visibility visibility, boolean pinned, AuthorSummary author, int version, long viewCount,
                              long likeCount, boolean likedByMe, long commentCount,
                              Instant createdAt, Instant updatedAt, Instant publishedAt) {}

    public record PostDetail(Long id, String title, String content, Post.ContentFormat contentFormat, String summary, CategoryRef category, List<String> tags,
                             Post.Status status, Post.Visibility visibility, boolean pinned, AuthorSummary author, int version, long viewCount,
                             long likeCount, boolean likedByMe,
                             Instant createdAt, Instant updatedAt, Instant publishedAt, Instant deletedAt,
                             boolean editable, List<AttachmentResponse> attachments) {}

    /**
     * 글 작성·전체 수정. categoryId(또는 categoryPath "상위 > 하위")가 없으면 기본 카테고리 '일반'.
     * contentFormat 기본 HTML(MARKDOWN 도 받는다). visibility 기본 PUBLIC. pinned 는 관리자만 반영(그 외는 무시).
     */
    public record PostRequest(@NotBlank @Size(max = 300) String title,
                              @NotBlank String content,
                              Post.ContentFormat contentFormat,
                              List<String> attachmentIds,
                              @Size(max = 500) String summary,
                              Long categoryId,
                              List<@Size(max = 50) String> tags,
                              Post.Status status,
                              Post.Visibility visibility,
                              Boolean pinned,
                              @Size(max = 500) String changeNote,
                              @Size(max = 600) String categoryPath) {
        public PostRequest(String title, String content, Post.ContentFormat contentFormat, List<String> attachmentIds, String summary,
                           Long categoryId, List<String> tags, Post.Status status, Post.Visibility visibility, Boolean pinned, String changeNote) {
            this(title, content, contentFormat, attachmentIds, summary, categoryId, tags, status, visibility, pinned, changeNote, null);
        }
    }

    /** 부분 수정(PATCH): 보낸 항목만 바뀐다. 외부 프로그램(Claude Code 등)이 제목이나 본문만 고칠 때. summary 를 ""로 보내면 지운다. */
    public record PostPatchRequest(@Size(max = 300) String title,
                                   String content,
                                   Post.ContentFormat contentFormat,
                                   List<String> attachmentIds,
                                   @Size(max = 500) String summary,
                                   Long categoryId,
                                   @Size(max = 600) String categoryPath,
                                   List<@Size(max = 50) String> tags,
                                   Post.Status status,
                                   Post.Visibility visibility,
                                   Boolean pinned,
                                   @Size(max = 500) String changeNote) {}

    public record TagCount(String name, long count) {}

    static AuthorSummary author(com.workblog.user.User u) {
        return new AuthorSummary(u.getId(), u.getUsername(), u.getDisplayName(), u.getDepartment());
    }
}
