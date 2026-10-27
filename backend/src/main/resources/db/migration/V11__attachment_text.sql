-- 2026-10-27 첨부 파일 내용 검색: 업로드 때 꺼낸 텍스트(최대 200KB)와 추출 시각.
-- text_extracted_at 이 NULL 인 문서형 첨부는 기동 후 백그라운드에서 한 번 채운다(실패해도 시각을 남겨 다시 시도하지 않음).
ALTER TABLE attachments ADD COLUMN text_content TEXT;
ALTER TABLE attachments ADD COLUMN text_extracted_at TIMESTAMPTZ;

CREATE INDEX idx_attachments_text_trgm ON attachments USING gin (text_content gin_trgm_ops);
