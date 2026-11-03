-- 이벤트 색(사용자가 고름). 종류(kind)는 화면에서 쓰지 않음. NULL 이면 기본 색.
ALTER TABLE mm_events ADD COLUMN color VARCHAR(7);
