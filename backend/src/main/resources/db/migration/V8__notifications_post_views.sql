-- 2026-10-27 협업: 알림(내 글에 댓글 · 내 댓글에 답글 · @아이디 멘션)과 글 열람 감사 로그.
-- 글·댓글·사용자가 영구 삭제되면 관련 행도 함께 지워진다(CASCADE).

CREATE TABLE notifications (
    id            BIGSERIAL    PRIMARY KEY,
    recipient_id  BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    actor_id      BIGINT       REFERENCES users(id) ON DELETE CASCADE,
    type          VARCHAR(20)  NOT NULL,                                   -- COMMENT · REPLY · MENTION
    post_id       BIGINT       NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    comment_id    BIGINT       REFERENCES comments(id) ON DELETE CASCADE,  -- 글 본문 멘션이면 NULL
    read_at       TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_notifications_recipient ON notifications(recipient_id, created_at DESC);
CREATE INDEX idx_notifications_unread    ON notifications(recipient_id) WHERE read_at IS NULL;
CREATE INDEX idx_notifications_post      ON notifications(post_id);

-- 글 상세 조회 기록. 같은 사용자·글은 10분 안에 다시 기록하지 않는다(애플리케이션에서 판단).
CREATE TABLE post_views (
    id         BIGSERIAL    PRIMARY KEY,
    post_id    BIGINT       NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    user_id    BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    viewed_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    via        VARCHAR(20)  NOT NULL                                       -- WEB · API_TOKEN
);
CREATE INDEX idx_post_views_post ON post_views(post_id, viewed_at DESC);
CREATE INDEX idx_post_views_user ON post_views(user_id, post_id, viewed_at DESC);
CREATE INDEX idx_post_views_time ON post_views(viewed_at DESC);
