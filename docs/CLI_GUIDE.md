# CLI 가이드 (workblog.py · API 토큰)

명령줄에서 WorkBlog 글을 **읽고, 쓰고, 고치고, 파일을 올리는** 방법입니다. 사람이 터미널에서 써도 되고, 스크립트·배치·Claude Code 같은 자동화 도구가 써도 됩니다.

1. 개인 API 토큰 발급
2. CLI 스크립트 `workblog.py` 받기
3. 명령 사용
4. (선택) curl 등으로 API 직접 호출
5. (선택) Claude Code 등 자동화 도구에 연결

## 1. 토큰 발급 (한 번만)

1. WorkBlog 로그인 → 오른쪽 위 아바타 → **내 정보** → **API 토큰** 카드
2. 이름(예: `내 노트북`)과 만료(권장 365일)를 정하고 **발급**
3. 표시된 `wbt_…` 토큰 복사 — **창을 닫으면 다시 볼 수 없음**(잃어버리면 폐기 후 재발급)
4. CLI 를 쓸 PC 의 환경변수에 저장(코드·문서에 적지 않음)

```powershell
# Windows (PowerShell, 사용자 영구 설정)
[Environment]::SetEnvironmentVariable("WORKBLOG_URL", "{{BASE_URL}}", "User")
[Environment]::SetEnvironmentVariable("WORKBLOG_TOKEN", "wbt_여기에_토큰", "User")
```
```bash
# macOS / Linux (~/.bashrc 또는 ~/.zshrc)
export WORKBLOG_URL={{BASE_URL}}
export WORKBLOG_TOKEN=wbt_여기에_토큰
```

- 토큰은 발급한 사람 권한으로 동작(글 작성자도 그 사람)
- 토큰으로는 토큰 관리·비밀번호 변경·사용자 관리 불가

## 2. CLI 받기

표준 라이브러리만 쓰는 파이썬 스크립트(Python 3.9+, 설치할 패키지 없음).

```bash
curl -s {{BASE_URL}}/api/guide/workblog.py -o workblog.py
python workblog.py me            # 토큰이 맞으면 내 계정 표시
python workblog.py categories    # 카테고리 트리 (id, 이름, 글 수)
```

## 3. 명령

| 명령 | 하는 일 |
|---|---|
| `list [--q 검색어] [--category id] [--tag 태그] [--status DRAFT]` | 글 목록·검색 |
| `get <id> [--text]` | 글 1건(JSON, `--text` 면 평문 본문) |
| `create --title "제목" (--file 본문.html \| --markdown 본문.md \| --content "<p>…</p>") [--category "상위 > 하위"] [--tags a,b] [--summary …] [--status DRAFT] [--visibility PRIVATE]` | 새 글 |
| `update <id> [--title …] [--file …] [--category …] [--tags …] [--note "변경 메모"]` | **부분 수정** — 준 항목만 바뀜 |
| `import <파일> [--title …] [--category …] [--tags …]` | HTML·docx·xlsx·csv·md·txt 파일로 새 글(원본 첨부) |
| `replace <id> <파일> [--note …]` | 파일 내용으로 기존 글 본문 교체 |
| `upload <파일>` | 첨부 업로드 → `/api/files/...` 주소(본문 `<img src>` 에 사용) |
| `export <id> --format md\|html\|docx [--out 파일]` | 내보내기 |
| `delete <id>` · `undelete <id>` · `revisions <id>` · `restore <id> <버전>` | 삭제·복구·이력 |
| `comment <id> "내용"` · `comments <id>` | 댓글 |

- 본문에 넣은 사진(`/api/files/…`)은 `create`·`update` 때 자동으로 첨부로 연결
- 표: `<table class="rich-table"><tbody><tr><th><p>…</p></th></tr><tr><td><p>…</p></td></tr></tbody></table>`
- 사진: `upload` 한 주소를 `<figure class="img-figure" data-align="center"><img src="/api/files/…" alt="…"></figure>` 로
- 문서 형식·도식 기준은 [글 작성 지침](/help/writing)

## 4. API 직접 호출 (curl / 다른 언어)

```bash
WB={{BASE_URL}}; H="Authorization: Bearer $WORKBLOG_TOKEN"
curl -s "$WB/api/posts?q=VPN&size=5" -H "$H"                                   # 검색
curl -s "$WB/api/posts/31?view=false" -H "$H"                                  # 읽기
curl -s -X POST "$WB/api/posts" -H "$H" -H 'Content-Type: application/json' \
  -d '{"title":"제목","content":"<p>본문</p>","categoryPath":"서버 정보 > AWS","tags":["aws"],"status":"PUBLISHED"}'
curl -s -X PATCH "$WB/api/posts/31" -H "$H" -H 'Content-Type: application/json' -d '{"content":"<p>새 본문</p>","changeNote":"절차 갱신"}'
curl -s -X POST "$WB/api/posts/import" -H "$H" -F 'file=@문서.docx' -F 'categoryPath=운영 절차'
curl -s "$WB/api/posts/31/export?format=md" -H "$H" -o 31.md
```

전체 목록과 요청·응답 형식은 [API 사용 안내](/help/api) 와 Swagger UI(`/swagger-ui/index.html`).

## 5. 자동화 도구에 연결 (Claude Code 등)

Claude Code 같은 코딩 도구가 작업하는 프로젝트의 지침 파일(`CLAUDE.md` 등)에 아래 블록을 넣으면, "이 내용을 WorkBlog 에 올려 줘", "WorkBlog 31번 글을 고쳐 줘" 같은 요청을 도구가 CLI 로 처리합니다.

````markdown
## WorkBlog (사내 지식 공유 블로그)

- 주소: {{BASE_URL}} — 인증은 환경변수 `WORKBLOG_TOKEN`(개인 API 토큰). 토큰 값은 출력·커밋 금지.
- CLI: `python workblog.py …` (없으면 `curl -s $WORKBLOG_URL/api/guide/workblog.py -o workblog.py`). 사용법 `$WORKBLOG_URL/api/guide/cli`, 문서 형식 `$WORKBLOG_URL/api/guide/writing`.
- 올리기 전 `python workblog.py categories` 로 카테고리 확인 → `--category "상위 > 하위"` 지정(카테고리 생성은 관리자만).
- 새 글: 본문 HTML 파일 → `python workblog.py create --title "…" --file 본문.html --category "…" --tags a,b` (마크다운은 `--markdown`).
- 수정: `get <id>` 로 현재 내용 확인 → 바뀌는 항목만 `update <id> --note "무엇을 왜"` (전체 덮어쓰기 금지).
- 파일 그대로: 새 글 `import <파일>`, 본문 교체 `replace <id> <파일>`.
- 삭제는 `delete <id>`(휴지통, 복구 가능). 영구 삭제 금지.
- 결과 보고에 글 주소 `{{BASE_URL}}/posts/<id>` 표기.
````

## 6. 규칙과 주의

1. 토큰은 개인용 — 공유 금지, PC 를 바꾸면 재발급 후 옛 토큰 폐기
2. CLI·도구로 올린 글도 이력(누가·언제·무엇을)이 남음 — `--note`(changeNote) 필수
3. 본문의 `script`·이벤트 속성·외부 CSS 는 서버가 제거 — 서식은 태그 `style` 로만
4. 대량 작업은 `--status DRAFT` 로 먼저 올려 확인 후 발행
5. 오류는 응답 `message` 확인 — 401 토큰 · 403 권한 · 404 없는(볼 수 없는) 글 · 400 입력 오류 · 409 다른 사람이 먼저 고침
