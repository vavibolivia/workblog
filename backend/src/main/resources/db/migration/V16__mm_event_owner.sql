-- 이벤트 고치기·지우기는 만든 사람과 관리자만: 만든 사람 계정을 남긴다(이전 이벤트는 관리자만 고칠 수 있음)
ALTER TABLE mm_events ADD COLUMN created_by_id BIGINT REFERENCES users(id) ON DELETE SET NULL;
