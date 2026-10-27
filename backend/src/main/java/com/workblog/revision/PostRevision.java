package com.workblog.revision;

import com.workblog.post.Post;
import com.workblog.user.User;
import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/** Immutable snapshot of a post at a given version. */
@Entity
@Table(name = "post_revisions")
@EntityListeners(AuditingEntityListener.class)
public class PostRevision {
    public enum ChangeType { CREATE, UPDATE, RESTORE, DELETE, UNDELETE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id")
    private Post post;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_format", nullable = false, length = 20)
    private Post.ContentFormat contentFormat;

    @Column(length = 500)
    private String summary;

    /** 당시 카테고리 경로 텍스트("정비 > 엔진"). 카테고리가 지워져도 이력은 남는다. */
    @Column(nullable = false, length = 300)
    private String category;

    /** 복원용. 카테고리가 지워지면 null 이 된다(ON DELETE SET NULL). */
    @Column(name = "category_id")
    private Long categoryId;

    @Column(nullable = false, length = 500)
    private String tags;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Post.Status status;

    /** 당시 공개 범위. 복원 시 함께 되돌린다. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Post.Visibility visibility = Post.Visibility.PUBLIC;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 20)
    private ChangeType changeType;

    @Column(name = "change_note", length = 500)
    private String changeNote;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "edited_by")
    private User editedBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PostRevision() {}

    public static PostRevision snapshot(Post post, ChangeType type, String note, User editor) {
        PostRevision r = new PostRevision();
        r.post = post;
        r.version = post.getVersion();
        r.title = post.getTitle();
        r.content = post.getContent();
        r.contentFormat = post.getContentFormat();
        r.summary = post.getSummary();
        r.category = com.workblog.category.CategoryService.pathText(post.getCategory());
        r.categoryId = post.getCategory().getId();
        r.tags = post.getTags();
        r.status = post.getStatus();
        r.visibility = post.getVisibility();
        r.changeType = type;
        r.changeNote = note;
        r.editedBy = editor;
        return r;
    }

    public Long getId() { return id; }
    public Post getPost() { return post; }
    public int getVersion() { return version; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public Post.ContentFormat getContentFormat() { return contentFormat; }
    public String getSummary() { return summary; }
    public String getCategory() { return category; }
    public Long getCategoryId() { return categoryId; }
    public String getTags() { return tags; }
    public Post.Status getStatus() { return status; }
    public Post.Visibility getVisibility() { return visibility == null ? Post.Visibility.PUBLIC : visibility; }
    public ChangeType getChangeType() { return changeType; }
    public String getChangeNote() { return changeNote; }
    public User getEditedBy() { return editedBy; }
    public Instant getCreatedAt() { return createdAt; }
}
