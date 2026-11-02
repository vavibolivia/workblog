-- 2026-10-27 데이터 이전(다른 망으로 옮기기): 인스턴스 ID · 가져오기 매핑 · 내보내기/가져오기 기록
-- 인스턴스 ID 는 앱이 처음 시작할 때 만든다(com.workblog.datamove.InstanceIdService). 덤프의 원본 표시와 자기 덤프 거부에 쓴다.

CREATE TABLE app_instance (
    id           SMALLINT     PRIMARY KEY CHECK (id = 1),
    instance_id  VARCHAR(36)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 원본 인스턴스의 (종류, 원본 키) → 이 서버의 대상 id. 다시 가져오면 같은 대상을 갱신한다(중복 생성 금지).
-- 글: source_version/target_version 으로 '원본이 더 새로움'과 '대상에서 그 사이 고침(충돌)'을 판단한다.
CREATE TABLE data_import_map (
    source_instance    VARCHAR(36)  NOT NULL,
    entity             VARCHAR(20)  NOT NULL,     -- USER · CATEGORY · TEMPLATE · POST · REVISION · COMMENT · ATTACHMENT
    source_key         VARCHAR(64)  NOT NULL,     -- 원본 id (첨부는 public_id)
    target_id          BIGINT       NOT NULL,
    target_key         VARCHAR(64),               -- 첨부: 대상 public_id
    source_version     INTEGER,
    target_version     INTEGER,
    source_updated_at  TIMESTAMPTZ,
    target_updated_at  TIMESTAMPTZ,
    applied_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (source_instance, entity, source_key)
);

-- 내보내기·가져오기 기록(누가·언제·어떤 덤프·결과 보고서). baseline=TRUE 인 마지막 내보내기 시각이 다음 업데이트 덤프의 기본 기준 시각.
CREATE TABLE data_transfers (
    id               BIGSERIAL    PRIMARY KEY,
    direction        VARCHAR(10)  NOT NULL,       -- EXPORT · IMPORT
    dump_kind        VARCHAR(10)  NOT NULL,       -- FULL · UPDATE
    action           VARCHAR(10)  NOT NULL,       -- EXPORT · PREVIEW · APPLY
    dump_name        VARCHAR(200) NOT NULL,
    source_instance  VARCHAR(36)  NOT NULL,
    since_at         TIMESTAMPTZ,
    exported_at      TIMESTAMPTZ,
    baseline         BOOLEAN      NOT NULL DEFAULT FALSE,
    status           VARCHAR(20)  NOT NULL,       -- RUNNING · OK · FAILED
    actor_id         BIGINT       REFERENCES users(id) ON DELETE SET NULL,
    actor_name       VARCHAR(100) NOT NULL,
    summary          VARCHAR(500),                -- 한 줄 요약(목록용)
    report           TEXT,                        -- JSON(건수·목록·오류)
    started_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at      TIMESTAMPTZ
);
CREATE INDEX idx_data_transfers_started ON data_transfers(started_at DESC);

-- 가져온 첨부의 내용 해시(같은 글에 같은 파일이 이미 있으면 다시 만들지 않는다)
ALTER TABLE attachments ADD COLUMN content_sha256 VARCHAR(64);
CREATE INDEX idx_attachments_sha ON attachments(content_sha256) WHERE content_sha256 IS NOT NULL;
