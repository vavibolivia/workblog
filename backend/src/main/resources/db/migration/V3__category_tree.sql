-- 2026-10-26 사용자 지시 "생성 및 연결 가능한 depth 는 최대 5개": 카테고리를 최대 5단계 트리로.
-- path 는 '/1/5/9/' 형태의 조상 경로(자기 포함). 하위 포함 검색은 path LIKE '/1/%'.

CREATE TABLE categories (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(50)  NOT NULL,
    parent_id   BIGINT       REFERENCES categories(id),
    depth       SMALLINT     NOT NULL CHECK (depth BETWEEN 1 AND 5),
    path        VARCHAR(200) NOT NULL DEFAULT '',
    sort_order  INTEGER      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_categories_sibling_name ON categories (COALESCE(parent_id, 0), lower(name));
CREATE INDEX idx_categories_parent ON categories (parent_id);
CREATE INDEX idx_categories_path ON categories (path varchar_pattern_ops);

-- 기존 글의 카테고리 이름을 1단계 카테고리로 이관 ('일반'은 항상 존재)
INSERT INTO categories (name, depth, sort_order)
SELECT name, 1, row_number() OVER (ORDER BY CASE WHEN name = '일반' THEN 0 ELSE 1 END, name)
FROM (SELECT DISTINCT category AS name FROM posts UNION SELECT '일반') s;
UPDATE categories SET path = '/' || id || '/';

ALTER TABLE posts ADD COLUMN category_id BIGINT REFERENCES categories(id);
UPDATE posts p SET category_id = c.id FROM categories c WHERE c.parent_id IS NULL AND c.name = p.category;
ALTER TABLE posts ALTER COLUMN category_id SET NOT NULL;
CREATE INDEX idx_posts_category_id ON posts (category_id);
DROP INDEX IF EXISTS idx_posts_category;
ALTER TABLE posts DROP COLUMN category;

-- 리비전: 당시 경로 텍스트(category)는 그대로 두고, 복원용으로 category_id 를 함께 저장
ALTER TABLE post_revisions ALTER COLUMN category TYPE VARCHAR(300);
ALTER TABLE post_revisions ADD COLUMN category_id BIGINT REFERENCES categories(id) ON DELETE SET NULL;
UPDATE post_revisions r SET category_id = c.id FROM categories c WHERE c.parent_id IS NULL AND c.name = r.category;
