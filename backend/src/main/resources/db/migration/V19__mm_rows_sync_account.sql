-- 공수 표 인원 행의 이름·소속을 연결된 계정과 맞춘다(계정 이름이 바뀌어도 행에 옛 이름이 남아 있던 문제).
-- 이후로는 계정 수정 때 UserService 가, 행 수정 때 ManMonthService 가 같이 맞춘다.
UPDATE mm_rows r
   SET person_name = u.display_name,
       department  = COALESCE(NULLIF(TRIM(u.department), ''), r.department)
  FROM users u
 WHERE r.user_id = u.id
   AND (r.person_name IS DISTINCT FROM u.display_name
        OR (NULLIF(TRIM(u.department), '') IS NOT NULL AND r.department IS DISTINCT FROM u.department));
