# WorkBlog — 사내 지식 공유 블로그

제작자: 정석호 · 공개 저장소에는 내부 기록(요구사항·작업 일지·데이터)을 포함하지 않는다.

전사 구성원이 업무 지식을 블로그 형식으로 쓰고, 한곳에서 검색하고, AI에게 묻는 시스템.
API 사용 안내는 `docs/API.md`, 외부 프로그램(Claude Code 등) 연동은 `docs/CLAUDE_CODE_GUIDE.md`.

## 구성
| 폴더 | 내용 |
|---|---|
| `backend/` | Spring Boot 3.5.6 · Java 21 · JPA · Flyway(V1~V7) · Spring Security(JWT + 개인 API 토큰) · jsoup · Apache POI · mammoth · commonmark/flexmark · springdoc |
| `frontend/` | React 19 · TypeScript · Vite 8 · react-router 7 · TipTap 3 리치 에디터 · draw.io embed · react-markdown |
| `deploy/` | 테스트 서버(<서버>) 배포 스크립트와 systemd 유닛 |
| `scripts/smoke.py` (+ `smoke_comments.py`, `smoke_v2.py`) | 서버 API 스모크 테스트(약 170개 항목, 끝에 테스트 데이터 스스로 정리) |
| `scripts/workblog.py` | CLI — 다른 Claude Code·스크립트가 글을 읽고 쓰고 고친다(표준 라이브러리만). 서버에서 `/api/guide/workblog.py` 로도 받음 |
| `docs/API.md`, `docs/CLAUDE_CODE_GUIDE.md` | API 사용 안내 · Claude Code 연동 가이드. jar 에 포함되어 화면 `/help` 와 `/api/guide/*` 에서 제공 |

## 기능
- **사용자**: 관리자가 계정 생성·수정·비활성화. 역할 ADMIN / USER. 본인 정보·비밀번호 변경.
- **글**: 마크다운 작성·수정·삭제(소프트), 카테고리·태그·요약, 임시 저장, 삭제 취소.
- **History**: 모든 변경을 버전으로 저장. 버전별 내용, 현재 버전과의 줄 단위 비교, 복원. 전사 활동 타임라인. AI 질의 이력.
- **검색**: 제목·본문·태그(pg_trgm), 카테고리·태그·작성자 필터, 결과 하이라이트.
- **에디터**: 네이버 에디터와 같은 서식(글꼴·크기·색·강조·정렬·들여쓰기·목록·인용·표·사진 편집), 엑셀 → 표, 문서 파일(HTML·Word·Excel·CSV·Markdown·텍스트) 가져오기, draw.io 다이어그램 그리기·다시 편집.
- **댓글·공감·공지·공개 범위·앨범형 목록**, 카테고리 5단계 트리(이름 클릭으로 접고 펴기), 모든 외부 링크 새 창.
- **내보내기**: PDF(인쇄)·HTML·Markdown·Word(.docx).
- **회원 가입**: 아이디 직접 지정, 즉시 승인(설정으로 승인제 전환 가능).
- **API / Claude Code 연동**: 개인 API 토큰(내 정보), REST CRUD(PATCH 부분 수정, 카테고리 경로 지정), 파일 가져오기 API, Swagger UI(`/swagger-ui/index.html`), 가이드 문서(`/help`), CLI `scripts/workblog.py`.
- **권한**: 임시 저장·비공개 글은 작성자와 관리자만. 수정·삭제는 작성자와 관리자. 영구 삭제(휴지통)·공지·카테고리·사용자 관리는 관리자.

## 로컬 실행
```bash
# DB: PostgreSQL 에 workblog/workblog 계정과 workblog DB, pg_trgm 확장
bash build.sh                                   # 프론트 빌드 → jar 에 포함
java -jar backend/target/workblog-backend-0.1.0.jar   # http://localhost:8080
# 개발 모드: backend 실행 후 cd frontend && npm run dev  (http://localhost:5173, /api 프록시)
```
첫 기동 시 관리자 `admin` 생성. 비밀번호는 `ADMIN_PASSWORD` 환경변수(로컬 기본값 `admin1234`).

## 환경변수
| 변수 | 기본값 | 설명 |
|---|---|---|
| `SERVER_PORT` | 8080 | |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | localhost workblog | PostgreSQL |
| `JWT_SECRET` | 개발용 | 32바이트 이상, 운영에서 반드시 교체 |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD` | admin / admin1234 | 사용자가 없을 때만 생성 |
| `SIGNUP_ENABLED` / `SIGNUP_APPROVAL` | true / false | 회원 가입 허용 / 관리자 승인제 |
| `DRAWIO_URL` | https://embed.diagrams.net | draw.io 편집기 주소. 폐쇄망이면 자체 호스팅 `http://<서버>:8070/drawio` (아래) |
| `EXTRA_STATIC_DIR` | ./static-extra | jar 밖 정적 파일 폴더. 서버 `/project/workblog/static-extra` 에 `drawio/`(draw.war 를 푼 것)를 두면 `/drawio/` 로 서비스된다 |
| `STORAGE_DIR` / `MAX_FILE_MB` | ./data/files / 25 | 첨부 저장 폴더, 파일 최대 크기 |
| `CORS_ALLOWED_ORIGINS` | localhost:5173,3000 | |

## 서버 설치 (예: 사내 리눅스 서버, podman)
- 주소: `http://<서버>:8070`
- 설치+테스트: `bash build.sh && DEPLOY_HOST=user@host bash deploy/deploy.sh` (서버에서 기동 후 스모크 테스트까지 실행, 이전 jar 3개 `/project/workblog/backup` 보관)
- 설정 정본: `~/project/across/env/workblog.env` (권한 600). 변경 후 `systemctl --user restart container-workblog`
- 실행: podman 컨테이너 `workblog`(amazoncorretto:21-alpine, host network), `/project/workblog` 마운트
- DB: PostgreSQL 14 `db_workblog` / 계정 `workblog`
- 로그: `podman logs workblog`
- draw.io 자체 호스팅: GitHub `jgraph/drawio` 릴리스의 `draw.war` 를 `/project/workblog/static-extra/drawio/` 에 풀고 `DRAWIO_URL=http://<서버>:8070/drawio` 로 지정한다(공개 embed.diagrams.net 을 못 쓰는 폐쇄망용).

## 검증
설치와 테스트는 서버에서 실행한다. `deploy/deploy.sh`가 설치 직후 서버에서 스모크 테스트를 돌린다.
```bash
ssh user@host /project/workblog/test-workblog.sh   # 서버에서 다시 테스트 (결과: /project/workblog/logs/smoke-*.log)
```
