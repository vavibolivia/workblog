-- 2026-11-02 공수(Man-month) 표: 가로 = 주(월요일 시작), 세로 = 프로젝트별 인원. 칸 값 = 그 주 투입률(0~1.0).
-- 조회는 로그인한 모두, 수정은 관리자 · 표 주인 · 편집자. 메인 게시(featured)는 관리자만.

CREATE TABLE mm_sheets (
    id           BIGSERIAL     PRIMARY KEY,
    title        VARCHAR(200)  NOT NULL,
    description  VARCHAR(1000),
    start_date   DATE          NOT NULL,                       -- 첫 주 월요일
    weeks        INTEGER       NOT NULL CHECK (weeks BETWEEN 1 AND 104),
    featured     BOOLEAN       NOT NULL DEFAULT FALSE,
    owner_id     BIGINT        REFERENCES users(id) ON DELETE SET NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE mm_sheet_editors (
    sheet_id  BIGINT  NOT NULL REFERENCES mm_sheets(id) ON DELETE CASCADE,
    user_id   BIGINT  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    PRIMARY KEY (sheet_id, user_id)
);

CREATE TABLE mm_projects (
    id          BIGSERIAL     PRIMARY KEY,
    sheet_id    BIGINT        NOT NULL REFERENCES mm_sheets(id) ON DELETE CASCADE,
    name        VARCHAR(100)  NOT NULL,
    sort_order  INTEGER       NOT NULL DEFAULT 0
);
CREATE INDEX idx_mm_projects_sheet ON mm_projects (sheet_id);

-- 한 행 = 한 프로젝트에 할당된 한 사람. 계정과 연결(user_id)하거나 이름만 적는다(외부 인력).
CREATE TABLE mm_rows (
    id           BIGSERIAL     PRIMARY KEY,
    sheet_id     BIGINT        NOT NULL REFERENCES mm_sheets(id) ON DELETE CASCADE,
    project_id   BIGINT        NOT NULL REFERENCES mm_projects(id) ON DELETE CASCADE,
    user_id      BIGINT        REFERENCES users(id) ON DELETE SET NULL,
    person_name  VARCHAR(100)  NOT NULL,
    department   VARCHAR(100),
    role         VARCHAR(100),
    sort_order   INTEGER       NOT NULL DEFAULT 0
);
CREATE INDEX idx_mm_rows_sheet ON mm_rows (sheet_id);

-- 값이 없으면(0) 행을 두지 않는다. week = 0 부터(첫 주).
CREATE TABLE mm_cells (
    row_id  BIGINT        NOT NULL REFERENCES mm_rows(id) ON DELETE CASCADE,
    week    INTEGER       NOT NULL CHECK (week >= 0),
    value   NUMERIC(4,2)  NOT NULL CHECK (value > 0 AND value <= 1.00),
    PRIMARY KEY (row_id, week)
);

-- 누가 · 언제 · 무엇을 바꿨나
CREATE TABLE mm_changes (
    id          BIGSERIAL     PRIMARY KEY,
    sheet_id    BIGINT        NOT NULL REFERENCES mm_sheets(id) ON DELETE CASCADE,
    actor_id    BIGINT,
    actor_name  VARCHAR(100),
    summary     VARCHAR(500)  NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_mm_changes_sheet ON mm_changes (sheet_id, created_at DESC);
