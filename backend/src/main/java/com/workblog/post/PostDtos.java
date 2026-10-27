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

    /**
     * thumbnailUrl: 본문의 첫 이미지(없으면 null). 앨범형 목록용.
     * matchedAttachment: 검색어가 첨부 파일 내용에 있을 때만 "파일명 — …일치 부분…"(없으면 응답에서 빠짐).
     */
    public record PostSummary(Long id, String title, String summary, String snippet, String thumbnailUrl, CategoryRef category, List<String> tags,
                              Post.Status status, Post.Visibility visibility, boolean pinned, AuthorSummary author, int version, long viewCount,
                              long likeCount, boolean likedByMe, long commentCount,
                              Instant createdAt, Instant updatedAt, Instant publishedAt,
                              @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String matchedAttachment) {
        public PostSummary(Long id, String title, String summary, String snippet, String thumbnailUrl, CategoryRef category, List<String> tags,
                           Post.Status status, Post.Visibility visibility, boolean pinned, AuthorSummary author, int version, long viewCount,
                           long likeCount, boolean likedByMe, long commentCount,
                           Instant createdAt, Instant updatedAt, Instant publishedAt) {
            this(id, title, summary, snippet, thumbnailUrl, category, tags, status, visibility, pinned, author, version, viewCount,
                    likeCount, likedByMe, commentCount, createdAt, updatedAt, publishedAt, null);
        }

        public PostSummary withMatchedAttachment(String m) {
            return new PostSummary(id, title, summary, snippet, thumbnailUrl, category, tags, status, visibility, pinned, author, version, viewCount,
                    likeCount, likedByMe, commentCount, createdAt, updatedAt, publishedAt, m);
        }
    }

    public record PostDetail(Long id, String title, String content, Post.ContentFormat contentFormat, String summary, CategoryRef category, List<String> tags,
                             Post.Status status, Post.Visibility visibility, boolean pinned, AuthorSummary author, int version, long viewCount,
                             long likeCount, boolean likedByMe,
                             Instant createdAt, Instant updatedAt, Instant publishedAt, Instant deletedAt,
                             boolean editable, List<AttachmentResponse> attachments) {
        /** 조회수만 바꾼 사본(조회 시 SQL 로 올린 값을 응답에 반영) */
        public PostDetail withViewCount(long v) {
            return new PostDetail(id, title, content, contentFormat, summary, category, tags, status, visibility, pinned, author, version, v,
                    likeCount, likedByMe, createdAt, updatedAt, publishedAt, deletedAt, editable, attachments);
        }
    }

    /**
     * 글 작성·전체 수정. categoryId(또는 categoryPath "상위 > 하위")가 없으면 기본 카테고리('일반', 없으면 최상위 첫 카테고리).
     * contentFormat 기본 HTML(MARKDOWN 도 받는다). visibility 기본 PUBLIC. pinned 는 관리자만 반영(그 외는 무시).
     * baseVersion(선택, 수정 때만): 편집을 시작할 때 불러온 글 버전. 지금 버전과 다르면 409(동시 편집 충돌). 안 보내면 검사하지 않는다.
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
                              @Size(max = 600) String categoryPath,
                              Integer baseVersion) {
        public PostRequest(String title, String content, Post.ContentFormat contentFormat, List<String> attachmentIds, String summary,
                           Long categoryId, List<String> tags, Post.Status status, Post.Visibility visibility, Boolean pinned, String changeNote) {
            this(title, content, contentFormat, attachmentIds, summary, categoryId, tags, status, visibility, pinned, changeNote, null, null);
        }
        public PostRequest(String title, String content, Post.ContentFormat contentFormat, List<String> attachmentIds, String summary,
                           Long categoryId, List<String> tags, Post.Status status, Post.Visibility visibility, Boolean pinned, String changeNote,
                           String categoryPath) {
            this(title, content, contentFormat, attachmentIds, summary, categoryId, tags, status, visibility, pinned, changeNote, categoryPath, null);
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
                                   @Size(max = 500) String changeNote,
                                   Integer baseVersion) {
        public PostPatchRequest(String title, String content, Post.ContentFormat contentFormat, List<String> attachmentIds, String summary,
                                Long categoryId, String categoryPath, List<String> tags, Post.Status status, Post.Visibility visibility,
                                Boolean pinned, String changeNote) {
            this(title, content, contentFormat, attachmentIds, summary, categoryId, categoryPath, tags, status, visibility, pinned, changeNote, null);
        }
    }

    public record TagCount(String name, long count) {}

    static AuthorSummary author(com.workblog.user.User u) {
        return new AuthorSummary(u.getId(), u.getUsername(), u.getDisplayName(), u.getDepartment());
    }
}
