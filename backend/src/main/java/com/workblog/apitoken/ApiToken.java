package com.workblog.apitoken;

import com.workblog.user.User;
import jakarta.persistence.*;

import java.time.Instant;

/** 개인 API 토큰. 원문은 저장하지 않고 SHA-256 해시만 둔다. */
@Entity
@Table(name = "api_tokens")
public class ApiToken {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "token_prefix", nullable = false, length = 16)
    private String tokenPrefix;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected ApiToken() {}

    public ApiToken(User user, String name, String tokenHash, String tokenPrefix, Instant expiresAt) {
        this.user = user;
        this.name = name;
        this.tokenHash = tokenHash;
        this.tokenPrefix = tokenPrefix;
        this.expiresAt = expiresAt;
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public String getName() { return name; }
    public String getTokenPrefix() { return tokenPrefix; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public Instant getRevokedAt() { return revokedAt; }

    public boolean isUsable(Instant now) {
        return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now)) && user.isActive();
    }

    public void revoke() { if (revokedAt == null) revokedAt = Instant.now(); }
    public void touch(Instant now) { lastUsedAt = now; }
}
