-- 2026-10-26 네이버 블로그형 기능: 공감(좋아요) · 공지(상단 고정) · 공개 범위
-- 기존 글은 모두 전체 공개(PUBLIC), 고정 아님.

ALTER TABLE posts ADD COLUMN pinned BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE posts ADD COLUMN visibility VARCHAR(20) NOT NULL DEFAULT 'PUBLIC';   -- PUBLIC / PRIVATE
ALTER TABLE post_revisions ADD COLUMN visibility VARCHAR(20) NOT NULL DEFAULT 'PUBLIC';

CREATE INDEX idx_posts_pinned ON posts(pinned) WHERE pinned;

-- 공감: 사용자 한 명이 글 하나에 한 번. 다시 누르면 취소(행 삭제).
CREATE TABLE post_likes (
    post_id     BIGINT      NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    user_id     BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (post_id, user_id)
);
CREATE INDEX idx_post_likes_user ON post_likes(user_id);
