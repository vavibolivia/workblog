-- 2026-11-02 대문(홈 맨 위): 관리자가 고른 항목을 순서대로 탭으로 띄운다. 항목 = 글(POST) 또는 공수 표(MM_SHEET).
-- 공수 표의 featured 표시는 이 목록으로 옮기고 지운다.
CREATE TABLE front_items (
    id          BIGSERIAL    PRIMARY KEY,
    kind        VARCHAR(20)  NOT NULL CHECK (kind IN ('POST', 'MM_SHEET')),
    ref_id      BIGINT       NOT NULL,
    sort_order  INTEGER      NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (kind, ref_id)
);

INSERT INTO front_items (kind, ref_id, sort_order)
SELECT 'MM_SHEET', id, ROW_NUMBER() OVER (ORDER BY updated_at DESC) FROM mm_sheets WHERE featured;

ALTER TABLE mm_sheets DROP COLUMN featured;
