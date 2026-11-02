-- 계정에 직급·직책(예: 과장, PM): 인원을 고를 때 누가 누군지 알아보게. 본인이 프로필에서, 관리자가 사용자 관리에서 고친다
ALTER TABLE users ADD COLUMN position VARCHAR(100);
