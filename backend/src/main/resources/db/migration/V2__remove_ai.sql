-- 2026-10-26 사용자 지시 "AI 는 하지 말고 검색만 하도록": AI 질의 기능 제거.
-- 제거 시점 서버(db_workblog)의 ai_queries 행 수 0 확인 후 삭제.
DROP TABLE IF EXISTS ai_queries;
