-- 2026-10-26 댓글·답글: 글마다 댓글, 댓글에 1단계 답글(parent_id). 삭제는 soft(deleted_at).

CREATE TABLE comments (
    id          BIGSERIAL    PRIMARY KEY,
    post_id     BIGINT       NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    parent_id   BIGINT       REFERENCES comments(id) ON DELETE CASCADE,   -- NULL = 댓글, 값 있음 = 답글
    author_id   BIGINT       NOT NULL REFERENCES users(id),
    content     TEXT         NOT NULL,                                     -- 평문, 최대 2000자
    deleted_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_comments_post   ON comments(post_id, created_at);
CREATE INDEX idx_comments_parent ON comments(parent_id);
