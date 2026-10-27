package com.workblog.post;

import com.workblog.category.Category;
import com.workblog.user.User;
import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Entity
@Table(name = "posts")
@EntityListeners(AuditingEntityListener.class)
public class Post {
    /** 목록 정렬 */
    public enum Sort { LATEST, POPULAR, VIEWS, COMMENTS, OLDEST }

    public enum Status { DRAFT, PUBLISHED }
    public enum ContentFormat { MARKDOWN, HTML }
    /** 공개 범위. PRIVATE(비공개) 는 작성자와 관리자만 본다(임시 저장과 같은 취급). */
    public enum Visibility { PUBLIC, PRIVATE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_format", nullable = false, length = 20)
    private ContentFormat contentFormat = ContentFormat.HTML;

    @Column(length = 500)
    private String summary;

    /** 카테고리 트리의 한 노드(어느 단계든 가능). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id")
    private Category category;

    /** comma separated, normalized lower-case-trimmed */
    @Column(nullable = false, length = 500)
    private String tags = "";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PUBLISHED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Visibility visibility = Visibility.PUBLIC;

    /** 공지(상단 고정). 관리자만 바꿀 수 있다. */
    @Column(nullable = false)
    private boolean pinned = false;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id")
    private User author;

    @Column(nullable = false)
    private int version = 1;

    @Column(name = "view_count", nullable = false)
    private long viewCount = 0;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Post() {}

    public Post(String title, String content, ContentFormat format, String summary, Category category, List<String> tags,
                Status status, Visibility visibility, User author) {
        this.title = title;
        this.content = content;
        this.contentFormat = format == null ? ContentFormat.HTML : format;
        this.summary = summary;
        this.category = category;
        this.tags = joinTags(tags);
        this.status = status == null ? Status.PUBLISHED : status;
        this.visibility = visibility == null ? Visibility.PUBLIC : visibility;
        this.author = author;
        if (this.status == Status.PUBLISHED) this.publishedAt = Instant.now();
    }

    public static String joinTags(List<String> tags) {
        if (tags == null) return "";
        return tags.stream().filter(t -> t != null && !t.isBlank())
                .map(t -> t.trim().replace(",", " ").replaceAll("\\s+", " "))
                .distinct().collect(Collectors.joining(","));
    }

    public static List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) return List.of();
        return Arrays.stream(tags.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public void applyEdit(String title, String content, ContentFormat format, String summary, Category category, List<String> tags,
                          Status status, Visibility visibility) {
        this.title = title;
        this.content = content;
        if (format != null) this.contentFormat = format;
        this.summary = summary;
        this.category = category;
        this.tags = joinTags(tags);
        if (status != null) {
            if (status == Status.PUBLISHED && this.publishedAt == null) this.publishedAt = Instant.now();
            this.status = status;
        }
        if (visibility != null) this.visibility = visibility;
        this.version++;
    }

    /** 관리자 전용. 버전은 올리지 않는다(글 내용 변경이 아님). */
    public void setPinned(boolean pinned) { this.pinned = pinned; }

    public void softDelete() {
        this.deletedAt = Instant.now();
        this.version++;
    }

    public void restoreFromDelete() {
        this.deletedAt = null;
        this.version++;
    }

    public void incrementViews() { this.viewCount++; }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public ContentFormat getContentFormat() { return contentFormat; }
    public String getSummary() { return summary; }
    public Category getCategory() { return category; }
    public String getTags() { return tags; }
    public List<String> getTagList() { return splitTags(tags); }
    public Status getStatus() { return status; }
    public Visibility getVisibility() { return visibility; }
    public boolean isPinned() { return pinned; }
    public User getAuthor() { return author; }
    public int getVersion() { return version; }
    public long getViewCount() { return viewCount; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public boolean isDeleted() { return deletedAt != null; }
    /** 발행 + 전체 공개: 모든 사용자가 볼 수 있는 글. */
    public boolean isOpen() { return status == Status.PUBLISHED && visibility == Visibility.PUBLIC; }
}
