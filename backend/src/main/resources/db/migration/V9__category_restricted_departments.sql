-- 2026-10-27 카테고리 열람 제한: 부서명 목록(쉼표 구분, 앞뒤 공백 없음). 빈 문자열 = 제한 없음.
-- 제한 카테고리와 그 하위 카테고리의 글은 해당 부서 사용자 · 작성자 · 관리자만 본다(서버 정보 같은 민감 글용).

ALTER TABLE categories ADD COLUMN restricted_departments VARCHAR(1000) NOT NULL DEFAULT '';
