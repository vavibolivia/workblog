-- 2026-10-26 R8: 네이버 에디터 수준 리치 텍스트(HTML 본문) + 첨부파일(이미지·엑셀 등)
-- 기존 글은 MARKDOWN 으로 표시해 그대로 읽히고, 수정하면 HTML 로 저장된다.

ALTER TABLE posts ADD COLUMN content_format VARCHAR(20) NOT NULL DEFAULT 'MARKDOWN';
ALTER TABLE post_revisions ADD COLUMN content_format VARCHAR(20) NOT NULL DEFAULT 'MARKDOWN';

CREATE TABLE attachments (
    id             BIGSERIAL PRIMARY KEY,
    public_id      VARCHAR(36)  NOT NULL UNIQUE,          -- URL 에 쓰는 무작위 id
    post_id        BIGINT       REFERENCES posts(id) ON DELETE SET NULL,
    uploader_id    BIGINT       NOT NULL REFERENCES users(id),
    original_name  VARCHAR(255) NOT NULL,
    stored_name    VARCHAR(255) NOT NULL,                 -- 디스크 파일명 (public_id + 확장자)
    content_type   VARCHAR(150) NOT NULL,
    size_bytes     BIGINT       NOT NULL,
    kind           VARCHAR(20)  NOT NULL,                 -- IMAGE / EXCEL / FILE
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_attachments_post ON attachments(post_id);
CREATE INDEX idx_attachments_uploader ON attachments(uploader_id, created_at DESC);
