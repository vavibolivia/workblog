package com.workblog.notification;

import com.workblog.comment.Comment;
import com.workblog.post.Post;
import com.workblog.user.User;
import jakarta.persistence.*;

import java.time.Instant;

/** 사용자 알림. 내 글에 댓글(COMMENT) · 내 댓글에 답글(REPLY) · 댓글이나 글 본문의 @아이디(MENTION). */
@Entity
@Table(name = "notifications")
public class Notification {
    public enum Type { COMMENT, REPLY, MENTION }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id")
    private User recipient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id")
    private User actor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Type type;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id")
    private Post post;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "comment_id")
    private Comment comment;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Notification() {}

    public Notification(User recipient, User actor, Type type, Post post, Comment comment) {
        this.recipient = recipient;
        this.actor = actor;
        this.type = type;
        this.post = post;
        this.comment = comment;
    }

    public void markRead() { if (readAt == null) readAt = Instant.now(); }

    public Long getId() { return id; }
    public User getRecipient() { return recipient; }
    public User getActor() { return actor; }
    public Type getType() { return type; }
    public Post getPost() { return post; }
    public Comment getComment() { return comment; }
    public Instant getReadAt() { return readAt; }
    public boolean isRead() { return readAt != null; }
    public Instant getCreatedAt() { return createdAt; }
}
