CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL UNIQUE,
    password      VARCHAR(100) NOT NULL,
    display_name  VARCHAR(100) NOT NULL,
    email         VARCHAR(200),
    department    VARCHAR(100),
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER',
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE posts (
    id            BIGSERIAL PRIMARY KEY,
    title         VARCHAR(300) NOT NULL,
    content       TEXT         NOT NULL,
    summary       VARCHAR(500),
    category      VARCHAR(100) NOT NULL DEFAULT '일반',
    tags          VARCHAR(500) NOT NULL DEFAULT '',
    status        VARCHAR(20)  NOT NULL DEFAULT 'PUBLISHED',
    author_id     BIGINT       NOT NULL REFERENCES users(id),
    version       INTEGER      NOT NULL DEFAULT 1,
    view_count    BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,
    deleted_at    TIMESTAMPTZ
);
CREATE INDEX idx_posts_author     ON posts(author_id);
CREATE INDEX idx_posts_category   ON posts(category);
CREATE INDEX idx_posts_status     ON posts(status, deleted_at);
CREATE INDEX idx_posts_updated    ON posts(updated_at DESC);
CREATE INDEX idx_posts_title_trgm   ON posts USING gin (title gin_trgm_ops);
CREATE INDEX idx_posts_content_trgm ON posts USING gin (content gin_trgm_ops);
CREATE INDEX idx_posts_tags_trgm    ON posts USING gin (tags gin_trgm_ops);

CREATE TABLE post_revisions (
    id            BIGSERIAL PRIMARY KEY,
    post_id       BIGINT       NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    version       INTEGER      NOT NULL,
    title         VARCHAR(300) NOT NULL,
    content       TEXT         NOT NULL,
    summary       VARCHAR(500),
    category      VARCHAR(100) NOT NULL,
    tags          VARCHAR(500) NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    change_type   VARCHAR(20)  NOT NULL,
    change_note   VARCHAR(500),
    edited_by     BIGINT       NOT NULL REFERENCES users(id),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (post_id, version)
);
CREATE INDEX idx_revisions_created ON post_revisions(created_at DESC);

CREATE TABLE ai_queries (
    id                   BIGSERIAL PRIMARY KEY,
    user_id              BIGINT       NOT NULL REFERENCES users(id),
    question             TEXT         NOT NULL,
    answer               TEXT         NOT NULL,
    model                VARCHAR(100),
    referenced_post_ids  VARCHAR(500) NOT NULL DEFAULT '',
    scoped_post_id       BIGINT,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_ai_queries_user ON ai_queries(user_id, created_at DESC);
