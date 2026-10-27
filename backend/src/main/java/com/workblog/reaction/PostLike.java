package com.workblog.reaction;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/** 공감 한 건: (글, 사용자) 쌍이 기본키. 다시 누르면 행을 지운다. */
@Entity
@Table(name = "post_likes")
@EntityListeners(AuditingEntityListener.class)
public class PostLike {

    @Embeddable
    public static class Id implements Serializable {
        @Column(name = "post_id", nullable = false)
        private Long postId;

        @Column(name = "user_id", nullable = false)
        private Long userId;

        protected Id() {}

        public Id(Long postId, Long userId) {
            this.postId = postId;
            this.userId = userId;
        }

        public Long getPostId() { return postId; }
        public Long getUserId() { return userId; }

        @Override public boolean equals(Object o) {
            return o instanceof Id other && Objects.equals(postId, other.postId) && Objects.equals(userId, other.userId);
        }
        @Override public int hashCode() { return Objects.hash(postId, userId); }
    }

    @EmbeddedId
    private Id id;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PostLike() {}

    public PostLike(Long postId, Long userId) {
        this.id = new Id(postId, userId);
    }

    public Id getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }
}
