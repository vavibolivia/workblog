-- 2026-10-27 개인 API 토큰: 다른 Claude Code·스크립트가 사용자 대신 글을 쓰고 고친다.
-- 토큰 원문은 저장하지 않는다(SHA-256 해시만). 발급 때 한 번만 보여 준다.

CREATE TABLE api_tokens (
    id            BIGSERIAL    PRIMARY KEY,
    user_id       BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name          VARCHAR(100) NOT NULL,
    token_hash    VARCHAR(64)  NOT NULL UNIQUE,
    token_prefix  VARCHAR(16)  NOT NULL,          -- 화면 표시용 앞부분 (wbt_xxxxxxxx)
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ,                     -- NULL = 만료 없음
    last_used_at  TIMESTAMPTZ,
    revoked_at    TIMESTAMPTZ
);
CREATE INDEX idx_api_tokens_user ON api_tokens(user_id);
