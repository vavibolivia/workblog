package com.workblog.apitoken;

import com.workblog.apitoken.ApiTokenDtos.*;
import com.workblog.common.ApiException;
import com.workblog.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 개인 API 토큰 발급·검증. 형식 {@code wbt_} + 영숫자 40자. 다른 Claude Code·스크립트가
 * {@code Authorization: Bearer wbt_...} 또는 {@code X-API-Key: wbt_...} 헤더로 사용한다.
 */
@Service
@Transactional
public class ApiTokenService {
    public static final String PREFIX = "wbt_";
    static final int MAX_ACTIVE_PER_USER = 20;
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    private final ApiTokenRepository repo;
    private final SecureRandom random = new SecureRandom();

    public ApiTokenService(ApiTokenRepository repo) {
        this.repo = repo;
    }

    public CreatedToken create(User owner, CreateTokenRequest req) {
        if (repo.countByUserIdAndRevokedAtIsNull(owner.getId()) >= MAX_ACTIVE_PER_USER) {
            throw ApiException.badRequest("사용 중인 토큰이 " + MAX_ACTIVE_PER_USER + "개입니다. 쓰지 않는 토큰을 폐기한 뒤 다시 발급하세요.");
        }
        StringBuilder sb = new StringBuilder(PREFIX);
        for (int i = 0; i < 40; i++) sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        String raw = sb.toString();
        Instant expires = req.expiresInDays() == null ? null : Instant.now().plus(Duration.ofDays(req.expiresInDays()));
        ApiToken t = repo.save(new ApiToken(owner, req.name().trim(), sha256(raw), raw.substring(0, 12), expires));
        return new CreatedToken(raw, TokenInfo.from(t));
    }

    @Transactional(readOnly = true)
    public List<TokenInfo> list(User owner) {
        return repo.findByUserIdOrderByCreatedAtDesc(owner.getId()).stream().map(TokenInfo::from).toList();
    }

    /** 본인 토큰 또는 관리자만 폐기. */
    public void revoke(Long id, User actor) {
        ApiToken t = repo.findById(id).orElseThrow(() -> ApiException.notFound("토큰"));
        if (!actor.isAdmin() && !t.getUser().getId().equals(actor.getId())) throw ApiException.notFound("토큰");
        t.revoke();
    }

    /** 원문 토큰 → 사용자 아이디. 폐기·만료·비활성 계정이면 빈 값. 마지막 사용 시각은 1분 단위로만 갱신한다. */
    public Optional<String> authenticate(String raw) {
        if (raw == null || !raw.startsWith(PREFIX) || raw.length() > 100) return Optional.empty();
        Instant now = Instant.now();
        return repo.findByTokenHash(sha256(raw)).filter(t -> t.isUsable(now)).map(t -> {
            if (t.getLastUsedAt() == null || t.getLastUsedAt().isBefore(now.minus(TOUCH_INTERVAL))) t.touch(now);
            return t.getUser().getUsername();
        });
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
