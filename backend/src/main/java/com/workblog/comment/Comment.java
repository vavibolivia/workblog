package com.workblog.comment;

import com.workblog.post.Post;
import com.workblog.user.User;
import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/** 글의 댓글. parent 가 있으면 답글(1단계만). 삭제는 deleted_at 으로 표시한다. */
@Entity
@Table(name = "comments")
@EntityListeners(AuditingEntityListener.class)
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id")
    private Post post;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Comment parent;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id")
    private User author;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Comment() {}

    public Comment(Post post, Comment parent, User author, String content) {
        this.post = post;
        this.parent = parent;
        this.author = author;
        this.content = content;
    }

    public void edit(String content) { this.content = content; }

    public void softDelete() { this.deletedAt = Instant.now(); }

    public Long getId() { return id; }
    public Post getPost() { return post; }
    public Comment getParent() { return parent; }
    public Long getParentId() { return parent == null ? null : parent.getId(); }
    public User getAuthor() { return author; }
    public String getContent() { return content; }
    public Instant getDeletedAt() { return deletedAt; }
    public boolean isDeleted() { return deletedAt != null; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
