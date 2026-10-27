package com.workblog.audit;

import com.workblog.post.Post;
import com.workblog.user.User;
import jakarta.persistence.*;

import java.time.Instant;

/** 글 상세 열람 기록(감사 로그). via = WEB(웹 로그인) · API_TOKEN(개인 API 토큰). */
@Entity
@Table(name = "post_views")
public class PostView {
    public enum Via { WEB, API_TOKEN }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id")
    private Post post;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "viewed_at", nullable = false)
    private Instant viewedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Via via;

    protected PostView() {}

    public PostView(Post post, User user, Instant viewedAt, Via via) {
        this.post = post;
        this.user = user;
        this.viewedAt = viewedAt;
        this.via = via;
    }

    public Long getId() { return id; }
    public Post getPost() { return post; }
    public User getUser() { return user; }
    public Instant getViewedAt() { return viewedAt; }
    public Via getVia() { return via; }
}
