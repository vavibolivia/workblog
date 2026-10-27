# Claude Code 연동 가이드

다른 PC 의 Claude Code(또는 어떤 스크립트든)가 WorkBlog 에 글을 **읽고, 쓰고, 고치고, 파일을 올리도록** 하는 방법입니다.
핵심은 세 가지입니다. ① 개인 API 토큰 ② CLI 스크립트 `workblog.py`(또는 curl) ③ 프로젝트 `CLAUDE.md` 에 넣을 안내문.

## 1. 토큰 발급 (한 번만)

1. WorkBlog 에 로그인 → 오른쪽 위 내 아바타 → **내 정보** → **API 토큰** 카드.
2. 이름(예: `내 노트북 Claude Code`)과 만료(권장 365일)를 정하고 **발급**.
3. 표시된 `wbt_…` 토큰을 복사합니다. **이 창을 닫으면 다시 볼 수 없습니다.** 잃어버리면 폐기하고 새로 발급하세요.
4. Claude Code 를 쓰는 PC 에서 환경변수로 둡니다(코드·문서에 적지 마세요).

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

토큰은 발급한 사람의 권한으로 동작합니다(글의 작성자도 그 사람). 토큰으로는 토큰 관리와 비밀번호 변경이 막혀 있습니다.

## 2. CLI 스크립트 받기

표준 라이브러리만 쓰는 파이썬 스크립트입니다(Python 3.9+, 설치할 패키지 없음).

```bash
curl -s {{BASE_URL}}/api/guide/workblog.py -o workblog.py
python workblog.py me            # 토큰이 맞으면 내 계정이 보인다
python workblog.py categories    # 카테고리 트리 (id, 이름, 글 수)
```

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

## 3. Claude Code 프로젝트에 넣을 안내문

Claude Code 가 작업하는 프로젝트의 `CLAUDE.md` 에 아래 블록을 붙여 넣으면, "이 내용을 WorkBlog 에 올려 줘", "WorkBlog 의 31번 글을 고쳐 줘" 같은 요청을 Claude Code 가 바로 처리합니다.

````markdown
## WorkBlog (사내 지식 공유 블로그)

- 주소: {{BASE_URL}} — 인증은 환경변수 `WORKBLOG_TOKEN`(개인 API 토큰, `Authorization: Bearer`). 토큰 값은 절대 출력·커밋하지 않는다.
- CLI: `python workblog.py …` (없으면 `curl -s $WORKBLOG_URL/api/guide/workblog.py -o workblog.py`). API 문서: `$WORKBLOG_URL/api/guide/api`, Swagger `$WORKBLOG_URL/swagger-ui/index.html`.
- 글을 올리기 전에 `python workblog.py categories` 로 카테고리를 확인하고, 맞는 경로를 `--category "상위 > 하위"` 로 지정한다. 없으면 '일반'에 올린다(카테고리 생성은 관리자만).
- 새 글: 본문은 HTML 로 작성해 파일로 저장한 뒤 `python workblog.py create --title "…" --file 본문.html --category "…" --tags a,b`. 마크다운으로 썼으면 `--markdown 본문.md`.
  표는 `<table class="rich-table"><tbody><tr><th><p>…</p></th></tr><tr><td><p>…</p></td></tr></tbody></table>` 꼴로, 사진은 먼저 `upload` 한 주소를 `<figure class="img-figure" data-align="center"><img src="/api/files/…" alt="…"></figure>` 로 넣는다.
- 기존 글 수정: 먼저 `get <id>` 로 현재 내용을 읽고, 바뀌는 항목만 `update <id> --note "무엇을 왜 바꿨는지"` 로 보낸다(전체 덮어쓰기 금지). 본문을 고칠 때는 받은 HTML 을 수정해 `--file` 로 보낸다.
- 문서 파일(docx/xlsx/html/md)을 그대로 올릴 때는 `import <파일>`, 기존 글을 그 파일로 바꿀 때는 `replace <id> <파일>`.
- 삭제는 `delete <id>`(휴지통, 복구 가능). 영구 삭제는 하지 않는다.
- 결과를 보고할 때는 글 주소 `{{BASE_URL}}/posts/<id>` 를 적는다.
````

## 4. 직접 API 를 쓰는 경우 (curl / 다른 언어)

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

전체 목록과 요청·응답 형식은 [API 사용 안내](/help/api) 와 Swagger UI 를 보세요.

## 5. 규칙과 주의

- 토큰은 개인 것입니다. 공유하지 말고, PC 를 바꾸면 새로 발급하고 옛 것은 폐기하세요.
- Claude Code 가 올린 글도 사람이 올린 글과 똑같이 이력(누가·언제·무엇을)이 남습니다. `changeNote` 를 꼭 적게 하세요.
- 본문의 `script`·이벤트 속성·외부 CSS 는 서버가 지웁니다. 서식은 태그의 `style` 로만 줍니다(허용 목록은 API 안내 2절).
- 큰 작업(수십 건 가져오기)은 `status: DRAFT` 로 먼저 올려 확인한 뒤 발행하는 것을 권장합니다.
- 문제가 생기면 응답의 `message` 를 읽으세요. 401 = 토큰 문제, 403 = 권한 없음, 404 = 없는 글(또는 볼 수 없는 글), 400 = 입력 오류(설명 포함).
