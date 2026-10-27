-- 2026-10-27 지식 구조: ① 글 템플릿 ② 오래된 글 재검토(내용 확인 시각)

CREATE TABLE post_templates (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(100) NOT NULL,
    description  VARCHAR(300),
    content      TEXT         NOT NULL,                              -- 본문 HTML(정화된 것)
    category_id  BIGINT       REFERENCES categories(id) ON DELETE SET NULL,   -- 기본 카테고리(선택)
    tags         VARCHAR(500) NOT NULL DEFAULT '',                   -- 기본 태그(쉼표 구분)
    sort_order   INTEGER      NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_post_templates_order ON post_templates(sort_order, id);

-- 재검토: '내용 확인 완료' 시각. 버전·updated_at 은 건드리지 않는다. 기준일 = max(마지막 수정, reviewed_at)
ALTER TABLE posts ADD COLUMN reviewed_at TIMESTAMPTZ;

-- 기본 제공 템플릿 3개(일반 문구만)
INSERT INTO post_templates (name, description, content, tags, sort_order) VALUES
('공문형 지침',
 '머리 표 · 1. 관련 ~ 끝. 의 공문 골격(글 작성 지침 2절). 번호는 1. → 가. → 1) → 가) → (1)',
 '<p style="text-align: center"><strong>기관명</strong></p>'
 || '<h1 style="text-align: center">문서 제목</h1>'
 || '<table class="rich-table"><tbody>'
 || '<tr><th><p>문서번호</p></th><td><p>조직-주제-연도-일련번호</p></td><th><p>시행일자</p></th><td><p>YYYY.MM.DD</p></td></tr>'
 || '<tr><th><p>수신</p></th><td><p>수신 대상</p></td><th><p>참조</p></th><td><p>참조 대상</p></td></tr>'
 || '<tr><th><p>발신</p></th><td><p>발신 부서</p></td><th><p>제목</p></th><td><p>문서 제목</p></td></tr>'
 || '</tbody></table>'
 || '<p>1. 관련</p>'
 || '<p data-indent="1" style="margin-left:32px">가. 근거 문서 · 화면 기준일</p>'
 || '<p>2. 목적</p>'
 || '<p data-indent="1" style="margin-left:32px">가. 목적 1</p>'
 || '<p data-indent="1" style="margin-left:32px">나. 목적 2</p>'
 || '<p>3. 적용 범위</p>'
 || '<p data-indent="1" style="margin-left:32px">가. 적용 대상 한 줄</p>'
 || '<p>4. 개요</p>'
 || '<p data-indent="1" style="margin-left:32px">가. 항목</p>'
 || '<p data-indent="2" style="margin-left:64px">1) 세부 항목</p>'
 || '<p data-indent="3" style="margin-left:96px">가) 세부 내용</p>'
 || '<p>5. 절차</p>'
 || '<p data-indent="1" style="margin-left:32px">가. 작업 — 수행자, [위치]</p>'
 || '<p>6. 협조 요청</p>'
 || '<p data-indent="1" style="margin-left:32px">가. 이용자가 지켜야 할 사항 바람.</p>'
 || '<p>붙임 용어 정의 1부.  끝.</p>'
 || '<p><strong>붙임</strong> 용어 정의</p>'
 || '<table class="rich-table"><tbody><tr><th><p>용어</p></th><th><p>정의</p></th></tr><tr><td><p></p></td><td><p></p></td></tr></tbody></table>',
 '지침', 1),
('서버 정보',
 '① 기본 정보 ② 서비스/컨테이너 ③ 접속 계정 ④ 비고 — 표 머리글만 채워 둔 서버 기록 양식',
 '<h2>① 기본 정보</h2>'
 || '<table class="rich-table"><tbody><tr><th><p>호스트명</p></th><th><p>주소</p></th><th><p>운영체제</p></th><th><p>용도</p></th><th><p>담당</p></th></tr>'
 || '<tr><td><p></p></td><td><p></p></td><td><p></p></td><td><p></p></td><td><p></p></td></tr></tbody></table>'
 || '<h2>② 서비스 / 컨테이너</h2>'
 || '<table class="rich-table"><tbody><tr><th><p>이름</p></th><th><p>종류</p></th><th><p>포트</p></th><th><p>설치 경로</p></th><th><p>기동 방법</p></th></tr>'
 || '<tr><td><p></p></td><td><p></p></td><td><p></p></td><td><p></p></td><td><p></p></td></tr></tbody></table>'
 || '<h2>③ 접속 계정</h2>'
 || '<p>비밀번호는 본문에 적지 않고 보관 위치만 적는다.</p>'
 || '<table class="rich-table"><tbody><tr><th><p>구분</p></th><th><p>계정</p></th><th><p>권한</p></th><th><p>비밀번호 보관 위치</p></th></tr>'
 || '<tr><td><p></p></td><td><p></p></td><td><p></p></td><td><p></p></td></tr></tbody></table>'
 || '<h2>④ 비고</h2>'
 || '<p></p>',
 '서버', 2),
('장애 보고',
 '발생 일시 · 영향 · 원인 · 조치 · 재발 방지',
 '<table class="rich-table"><tbody>'
 || '<tr><th><p>발생 일시</p></th><td><p>YYYY.MM.DD HH:MM ~ HH:MM</p></td></tr>'
 || '<tr><th><p>대상 시스템</p></th><td><p></p></td></tr>'
 || '<tr><th><p>보고자</p></th><td><p></p></td></tr>'
 || '</tbody></table>'
 || '<h2>1. 영향</h2><p>영향 범위 · 사용자 · 시간</p>'
 || '<h2>2. 경과</h2>'
 || '<table class="rich-table"><tbody><tr><th><p>시각</p></th><th><p>내용</p></th></tr><tr><td><p></p></td><td><p></p></td></tr></tbody></table>'
 || '<h2>3. 원인</h2><p></p>'
 || '<h2>4. 조치</h2><p></p>'
 || '<h2>5. 재발 방지</h2>'
 || '<table class="rich-table"><tbody><tr><th><p>대책</p></th><th><p>담당</p></th><th><p>기한</p></th></tr><tr><td><p></p></td><td><p></p></td><td><p></p></td></tr></tbody></table>',
 '장애,보고', 3);
