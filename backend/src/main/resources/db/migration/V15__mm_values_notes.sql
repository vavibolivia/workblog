-- 공수 표: 칸에 아무 숫자나(0~1.0 제한 해제, 0 은 '비움' 이라 저장하지 않음) + 주마다 메모(할 일)

ALTER TABLE mm_cells DROP CONSTRAINT IF EXISTS mm_cells_value_check;
ALTER TABLE mm_cells ALTER COLUMN value TYPE NUMERIC(12,2);
ALTER TABLE mm_cells ADD CONSTRAINT mm_cells_value_check CHECK (value <> 0);

-- 사람 행 × 주 메모. 값과 따로(값 없이 메모만 있어도 됨). 행을 지우면 함께 지워진다.
CREATE TABLE mm_notes (
    row_id      BIGINT        NOT NULL REFERENCES mm_rows(id) ON DELETE CASCADE,
    week        INTEGER       NOT NULL CHECK (week >= 0),
    text        VARCHAR(2000) NOT NULL,
    updated_by  VARCHAR(100),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (row_id, week)
);

-- 시기별 이벤트(마일스톤·회의·배포·휴가 등) — 공수 표와 따로 관리하는 공용 목록. 하루짜리면 end_date 없음.
-- 모든 공수 표가 자기 기간에 걸친 이벤트를 주 머리 아래 '이벤트' 줄에 보여 준다.
-- related: 관련 이름(예: AirDeck). 프로젝트 이름에 이 글자가 들어 있으면 그 프로젝트 줄에도 ◆ 표시.
CREATE TABLE mm_events (
    id          BIGSERIAL     PRIMARY KEY,
    start_date  DATE          NOT NULL,
    end_date    DATE          CHECK (end_date IS NULL OR end_date >= start_date),
    kind        VARCHAR(20)   NOT NULL DEFAULT 'ETC' CHECK (kind IN ('MILESTONE','MEETING','RELEASE','REVIEW','HOLIDAY','LEAVE','ETC')),
    title       VARCHAR(200)  NOT NULL,
    related     VARCHAR(100),
    note        VARCHAR(2000),
    created_by  VARCHAR(100),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX mm_events_dates ON mm_events (start_date, end_date);
