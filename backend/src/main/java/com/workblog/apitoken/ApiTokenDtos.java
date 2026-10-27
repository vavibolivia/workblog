package com.workblog.apitoken;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class ApiTokenDtos {
    private ApiTokenDtos() {}

    /** expiresInDays 가 없으면 만료 없음. */
    public record CreateTokenRequest(@NotBlank @Size(max = 100) String name,
                                     @Min(1) @Max(3650) Integer expiresInDays) {}

    public record TokenInfo(Long id, String name, String prefix, Instant createdAt, Instant expiresAt,
                            Instant lastUsedAt, Instant revokedAt, boolean active) {
        static TokenInfo from(ApiToken t) {
            return new TokenInfo(t.getId(), t.getName(), t.getTokenPrefix(), t.getCreatedAt(), t.getExpiresAt(),
                    t.getLastUsedAt(), t.getRevokedAt(), t.isUsable(Instant.now()));
        }
    }

    /** token 원문은 이 응답에서 한 번만 내려간다. */
    public record CreatedToken(String token, TokenInfo info) {}
}
