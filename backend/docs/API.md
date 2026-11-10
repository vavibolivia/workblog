# WorkBlog API 사용 안내

WorkBlog 의 모든 기능은 REST API 로 제공됩니다. 웹 화면도 같은 API 를 씁니다.
대화형 문서(모든 엔드포인트·스키마·직접 호출)는 **`/swagger-ui/index.html`**, OpenAPI JSON 은 `/v3/api-docs` 입니다.
명령줄·자동화 도구 연동은 [CLI 가이드](/help/cli) 를 보세요.

- 기본 주소: `{{BASE_URL}}` (테스트 서버)
- 요청·응답은 JSON(UTF-8). 파일 업로드만 `multipart/form-data`.
- 오류는 `{ "status": 400, "error": "Bad Request", "message": "설명", "timestamp": "..." }` 형식입니다. 메시지는 한국어로 원인을 설명합니다.

## 1. 인증

두 가지 토큰을 `Authorization: Bearer <토큰>` 헤더로 보냅니다.

| 종류 | 얻는 법 | 수명 | 용도 |
|---|---|---|---|
| **개인 API 토큰** `wbt_…` | 화면 **내 정보 › API 토큰** 에서 발급, 또는 `POST /api/tokens` | 발급 때 정한 만료(없음~10년) · 언제든 폐기 | 외부 프로그램·Claude Code·스크립트 (권장) |
| 로그인 JWT | `POST /api/auth/login` `{ "username", "password" }` → `token` | 12시간 | 웹 화면, 임시 스크립트 |

API 토큰은 `X-API-Key: wbt_…` 헤더로도 보낼 수 있습니다. 토큰 원문은 발급 응답에서 **한 번만** 보입니다.
안전장치: API 토큰으로는 토큰 관리(`/api/tokens`)와 비밀번호 변경을 할 수 없습니다(403). 폐기된 토큰·비활성 계정은 401.

```bash
# 토큰 발급(웹 로그인 JWT 로만 가능)
curl -s -X POST {{BASE_URL}}/api/auth/login -H 'Content-Type: application/json' -d '{"username":"me","password":"..."}'
curl -s -X POST {{BASE_URL}}/api/tokens -H "Authorization: Bearer <JWT>" -H 'Content-Type: application/json' -d '{"name":"내 노트북","expiresInDays":365}'
# → {"token":"wbt_...","info":{...}}   이후 모든 호출에 Authorization: Bearer wbt_...
```

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/auth/login` | 로그인 → `{ token, expiresIn, user }` |
| POST | `/api/auth/signup` | 회원 가입 `{ username, password, displayName, email?, department? }` → 201(즉시 승인, token 포함) / 202(승인 대기). 부서는 열람 제한의 근거라 승인 대기일 때만 신청값으로 남고(관리자가 확인), 즉시 승인이면 비워 둔다 |
| GET | `/api/auth/options` | 가입 가능 여부, 다이어그램 편집기 주소(토큰 불필요) |
| GET | `/api/auth/me` | 내 계정 |
| GET / POST / DELETE | `/api/tokens`, `/api/tokens/{id}` | 내 API 토큰 목록 · 발급 `{ name, expiresInDays? }` · 폐기 |

## 2. 글 (CRUD)

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/posts` | 목록·검색. 쿼리: `q`(제목·본문·태그·카테고리·작성자), `categoryId`, `includeSub`(기본 true = 하위 포함, false = 그 카테고리에 직접 단 글만. 화면 목록은 false), `tag`, `authorId`, `status`(DRAFT/PUBLISHED), `sort`(LATEST 최신순 기본 · POPULAR 인기순 · VIEWS 조회순 · COMMENTS 댓글 많은 순 · OLDEST 오래된순 · TITLE 제목 가나다/abc 순(한글 먼저) · TITLE_DESC 그 역순. 화면은 기준(날짜/제목)과 방향(오름/내림) 두 토글로 LATEST·OLDEST·TITLE·TITLE_DESC 만 씀), `page`(0부터), `size`(≤100) |
| GET | `/api/search?q=` | 검색(위와 같음, 유사도 정렬) |
| GET | `/api/posts/{id}?view=false` | 글 1건(본문 포함). `view=false` 면 조회수를 올리지 않음 |
| POST | `/api/posts` | 새 글 → 201 |
| PUT | `/api/posts/{id}` | 전체 수정(모든 항목을 보냄) |
| **PATCH** | `/api/posts/{id}` | **부분 수정** — 보낸 항목만 바뀜(제목만, 본문만, 카테고리만 …) |
| DELETE | `/api/posts/{id}` | 휴지통으로(복구 가능). `?purge=true` 는 관리자가 휴지통의 글을 영구 삭제 |
| POST | `/api/posts/{id}/undelete` | 삭제 취소 |
| GET | `/api/posts/deleted` | 휴지통(내 글, 관리자는 전체) |
| POST | `/api/posts/{id}/like` | 공감 토글 → `{ liked, count }` |
| GET | `/api/tags?limit=30` | 태그 집계 |

### 글 본문 형식

```json
{
  "title": "제목(300자 이내)",
  "content": "<p>본문 HTML</p>",
  "contentFormat": "HTML",            // 또는 "MARKDOWN" (서버가 그대로 저장, 화면에서 렌더)
  "summary": "한 줄 요약(선택, 500자)",
  "categoryId": 12,                   // 또는 "categoryPath": "서버 정보 > AWS"  (둘 다 없으면 '일반', 일반을 지웠으면 최상위 첫 카테고리)
  "tags": ["VPN", "네트워크"],         // 최대 10개, 각 50자
  "status": "PUBLISHED",              // DRAFT = 임시 저장(작성자·관리자만 봄)
  "visibility": "PUBLIC",             // PRIVATE = 작성자·관리자만
  "pinned": false,                    // 공지(상단 고정). 관리자만 반영
  "attachmentIds": ["<업로드 응답의 id>"],
  "changeNote": "변경 메모(이력에 남음)",
  "baseVersion": 5                    // 선택(PUT·PATCH). 불러온 버전. 지금 버전과 다르면 409 (11절)
}
```

- HTML 본문은 저장 전에 정화됩니다. 남는 것: `p h1~h3 ul ol li blockquote pre code table thead tbody tr th td figure figcaption img a strong em u s mark sub sup hr span br` 와 `style`(color, background-color, font-family, font-size, font-weight, font-style, text-align, text-decoration, line-height, margin-left, padding-left, width, height 등), `data-indent`, `data-align`, `data-width`, `colspan/rowspan`. `script`·이벤트 속성·`javascript:`·`url()` 은 제거.
- 표는 에디터와 같은 꼴로 쓰면 화면이 같습니다: `<table class="rich-table"><tbody><tr><th><p>항목</p></th>…</tr><tr><td><p>값</p></td>…</tr></tbody></table>`
- 사진: 먼저 `POST /api/files` 로 올리고 응답의 `url`(`/api/files/<id>`)을 `<figure class="img-figure" data-align="center"><img src="…" alt="…"><figcaption>설명</figcaption></figure>` 로 넣고, `attachmentIds` 에 `id` 를 넣습니다.
- `categoryPath` 는 `상위 > 하위` 또는 `상위/하위`, 대소문자 무시. 없는 경로면 400(카테고리 트리는 `GET /api/categories`).

```bash
# 새 글
curl -s -X POST $WB/api/posts -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"title":"VPN 접속 오류","content":"<h2>증상</h2><p>-14 오류</p>","categoryPath":"IT > 네트워크","tags":["VPN"],"status":"PUBLISHED"}'
# 제목만 고치기
curl -s -X PATCH $WB/api/posts/31 -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"title":"VPN 접속 오류 (개정)","changeNote":"제목 수정"}'
```

## 3. 파일 가져오기 (HTML · Word · Excel · CSV · Markdown · 텍스트)

파일을 올리면 본문 HTML 로 바뀝니다. 문서 안의 사진(워드 내장 이미지, HTML 의 data: 이미지)은 첨부로 저장되고 본문에 주소가 들어갑니다. 원본 파일은 기본으로 첨부됩니다.

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/import/formats` | 지원 확장자, 최대 크기(MB) |
| POST | `/api/import/convert` | 변환만(저장 안 함) → `{ title, content, attachments, warnings }`. 결과를 `POST /api/posts` 에 쓰면 됨 |
| POST | `/api/posts/import` | 파일로 **새 글** → 201 `{ post, sourceType, warnings }` |
| PUT | `/api/posts/{id}/import` | 파일 내용으로 **기존 글 본문 교체**(제목·카테고리·태그 유지, 새 리비전) |

폼 필드(모두 선택): `title`, `categoryId` 또는 `categoryPath`, `tags`(쉼표 구분), `summary`, `status`, `visibility`, `attachOriginal`(기본 true), `changeNote`(교체 시).
제목을 주지 않으면 HTML `<title>` → 첫 제목 줄 → 파일 이름 순으로 정합니다.

| 형식 | 옮겨지는 것 | 한계 |
|---|---|---|
| `.html .htm` | 태그에 직접 준 서식(style), 표, 링크, data: 이미지 | `<style>` 시트는 무시. 상대 경로 사진은 제거(경고) |
| `.docx` | 제목 단계, 문단, 목록, 표, 굵게/기울임/밑줄/취소선, 링크, 내장 사진 | 글꼴·색·정렬 같은 꾸밈은 옮기지 않음. `.doc`(97-2003)·`.hwp` 불가 → docx 로 저장해서 |
| `.xlsx .xlsm .xls` | 시트마다 표(첫 행 머리글), 표시값(날짜·숫자 서식·수식 결과), 병합 칸, 굵게·글자색·채우기색·정렬 | 시트 20개 · 시트당 1000행 × 50열까지. 암호 걸린 파일 불가 |
| `.csv` | 표(구분자 , ; 탭 자동 인식, 따옴표 칸) | UTF-8 또는 MS949 |
| `.md` | 제목·목록·표·코드·링크(GFM) | |
| `.txt` | 빈 줄로 나뉜 문단 | |

```bash
curl -s -X POST $WB/api/posts/import -H "Authorization: Bearer $TOKEN" \
  -F 'file=@서버목록.xlsx' -F 'categoryPath=서버 정보 > 사내 서버실' -F 'tags=서버,목록'
curl -s -X PUT $WB/api/posts/31/import -H "Authorization: Bearer $TOKEN" -F 'file=@절차.docx' -F 'changeNote=워드 문서로 교체'
```

## 4. 내보내기

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/posts/{id}/export?format=html` | 단독 HTML 파일(화면과 같은 모양, 사진은 절대 주소) |
| GET | `/api/posts/{id}/export?format=md` | Markdown |
| GET | `/api/posts/{id}/export?format=docx` | Word(.docx) — 서식·표(병합)·사진·목록·인용·코드 포함 |

`attachments=false` 면 첨부 목록을 붙이지 않습니다. PDF 는 화면의 **내보내기 › PDF(인쇄)** 를 쓰세요(브라우저 인쇄).

## 5. 첨부 파일

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/files` (multipart `file`) | 업로드 → `{ id, name, url, kind(IMAGE/EXCEL/FILE), size }`. 최대 25MB. 실행 파일·스크립트·SVG·HTML 은 거부 |
| GET | `/api/files/{id}` | 내려받기/표시(토큰 불필요, 추측 불가한 id). `?download=true` 면 저장 |

업로드한 파일은 글 저장 때 `attachmentIds` 에 넣어야 글에 연결됩니다(본인이 올린 것만).

## 6. 카테고리 (최대 5단계)

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/categories` | 트리 `[ { id, name, parentId, depth, sortOrder, postCount, totalCount, children:[…] } ]` |
| POST | `/api/categories` | 로그인 사용자 모두(최상위·하위). 관리자가 아니면 열람 제한 카테고리 아래는 그 부서 구성원만, 최상위 '일반'은 불가(403). `{ name, parentId }` (6단계가 되면 400, 형제 중복 409) |
| PUT | `/api/categories/{id}` | 관리자. `{ name, parentId? | moveToRoot?, sortOrder?, restrictedDepartments? }` (자기 자손 아래로 이동 불가. `restrictedDepartments` 는 11절) |
| POST | `/api/categories/{id}/move?direction=-1|1` | 관리자. 형제 안에서 순서 이동 |
| DELETE | `/api/categories/{id}` | 관리자. 하위나 글이 있으면 400 |

## 7. 이력 · 댓글 · 사용자

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/posts/{id}/revisions` · `/revisions/{v}` | 글의 버전 목록 · 버전 내용 |
| POST | `/api/posts/{id}/revisions/{v}/restore` | 그 버전으로 복원(새 버전 생성) |
| GET | `/api/history?page=&size=` | 전사 활동 타임라인 |
| GET / POST | `/api/posts/{id}/comments` | 댓글 목록 · 작성 `{ content, parentId? }` (답글 1단계) |
| PUT / DELETE | `/api/posts/{id}/comments/{cid}` | 수정(본인·관리자) · 삭제 |
| GET | `/api/users` | 구성원 목록 |
| POST / PUT / DELETE | `/api/users`, `/api/users/{id}` | 관리자: 생성 · 수정(역할·활성·비밀번호) · 비활성화(`?purge=true` 면 글 없는 계정 영구 삭제) |
| PUT | `/api/users/me`, `/api/users/me/password` | 내 정보 · 비밀번호 변경(JWT 만). 부서는 관리자만 바꾼다(일반 사용자가 보낸 department 는 무시) |

## 8. 문서 · 상태

| 경로 | 설명 |
|---|---|
| `/api/guide` · `/api/guide/{api|cli|writing}` · `/api/guide/workblog.py` | 이 문서 · CLI 가이드 · 글 작성 지침 · CLI 스크립트(토큰 불필요) |
| `/swagger-ui/index.html` · `/v3/api-docs` | 대화형 문서 · OpenAPI JSON |
| `/api/health` | `{ status: "UP" }` |

## 9. 권한 요약

- 발행 + 전체 공개 글: 로그인한 모두 조회. 임시 저장·비공개·삭제된 글: 작성자와 관리자만(남에게는 404).
- 열람 제한 카테고리(하위 포함)의 글: 지정 부서 구성원 · 작성자 · 관리자만(남에게는 404, 11절).
- 수정·삭제·복원: 작성자와 관리자. 카테고리 만들기(최상위·하위): 로그인 사용자 모두. 공지 고정·카테고리 이름 변경·이동·삭제·열람 제한·사용자 관리·영구 삭제: 관리자.
- 모든 변경은 리비전으로 남고 전사 이력에 보입니다. 변경 메모(`changeNote`)를 적어 두면 이력이 읽기 쉽습니다.

## 10. 템플릿 · 관련 글 · 재검토 · 첨부 내용 검색

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/templates` · `/api/templates/{id}` | 글 템플릿 목록(순서 → id) · 하나. 로그인한 모두 |
| POST / PUT / DELETE | `/api/templates`, `/api/templates/{id}` | 관리자. `{ name, description?, content(HTML), categoryId?, tags[], sortOrder? }`. 본문은 글과 같은 HTML 정화 |
| GET | `/api/posts/{id}/related` | `{ related[], backlinks[] }`. related: 점수(공통 태그 수×2 + 같은 카테고리 1) 높은 순 5개(0점 제외). backlinks: 본문에 `/posts/{id}` 링크가 있는 글 |
| GET | `/api/posts/{id}/review` | 재검토 상태 `{ staleDays, lastModifiedAt, reviewedAt, lastCheckedAt, daysSince, stale, canConfirm }` |
| POST | `/api/posts/{id}/review-ok` | 내용 확인 완료(작성자·관리자, 발행 글만). 버전·리비전·수정 시각은 그대로, `reviewedAt` 만 지금으로 |
| GET | `/api/admin/review/stale?page=&size=` | 관리자. 재검토 대상(기준일이 오래된 순) |

- 기본 제공 템플릿: `공문형 지침`(머리 표 · 1. 관련 ~ 끝., 글 작성 지침 2절) · `서버 정보`(① 기본 정보 ② 서비스/컨테이너 ③ 접속 계정 ④ 비고) · `장애 보고`(발생 일시 · 영향 · 원인 · 조치 · 재발 방지).
- 관련 글·백링크는 삭제·임시 저장 글을 빼고, 나머지는 글 열람 규칙(비공개·카테고리 열람 제한)대로 볼 수 있는 글만 돌려준다.
- 재검토 기준일 = max(마지막 수정, 내용 확인). 마지막 수정은 마지막 리비전 시각이다(`updatedAt` 은 조회수 증가에도 바뀌므로 쓰지 않는다). 기준일이 `REVIEW_STALE_DAYS`(기본 180)일 이상 지난 발행 글이 `stale`. 0 이면 기능을 끈다.
- **첨부 내용 검색**: xlsx/xlsm/xls · csv · docx · txt · md · html · pdf 첨부는 올릴 때 텍스트(최대 200KB)를 꺼내 둔다. 기존 첨부는 서버 기동 뒤 백그라운드에서 한 번 채운다. `GET /api/posts?q=` · `/api/search?q=` 가 첨부 내용도 찾고, 걸린 글에는 `matchedAttachment: "파일명 — …일치 부분…"` 이 붙는다(검색어가 첨부에 없으면 이 필드는 없다).

## 11. 알림 · 동시 편집 충돌 · 열람 감사 로그 · 열람 제한 · 비밀번호 정책

### 알림

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/notifications?unreadOnly=&page=&size=` | 내 알림(최신순, size ≤ 50). `{ id, type, actor, postId, postTitle, commentId, excerpt, read, available, createdAt }` |
| GET | `/api/notifications/unread-count` | `{ count }` (화면은 60초마다 갱신) |
| POST | `/api/notifications/{id}/read` | 읽음 처리(내 알림만, 아니면 404) |
| POST | `/api/notifications/read-all` | 모두 읽음 → `{ count: 0 }` |
| DELETE | `/api/notifications/{id}` | 내 알림 하나 지우기(아니면 404) → `{ count }` 남은 안 읽은 수 |
| DELETE | `/api/notifications` | 읽은 알림 모두 지우기(안 읽은 것은 남김) → `{ count }` |

- 언제 생기나: 내 글에 댓글(`COMMENT`) · 내 댓글에 답글(`REPLY`) · 댓글이나 글 본문의 `@아이디`(`MENTION`, 대소문자 무시, 이메일 `a@b.com` 은 제외).
- 한 댓글로 같은 사람에게는 하나만(REPLY > MENTION > COMMENT). 글 본문 멘션은 글마다 한 사람에게 한 번(수정할 때마다 다시 보내지 않음, 임시 저장 중이던 멘션은 발행할 때 감).
- 자기 자신 · 비활성 계정 · 그 글을 볼 수 없는 사람(비공개·임시 저장·열람 제한)에게는 만들지 않는다. 나중에 글을 볼 수 없게 되면 목록에서 `available=false`, `postTitle`·`excerpt` 는 `null`.
- 댓글 수정으로 새로 넣은 `@아이디` 에는 알림을 보내지 않는다.

### 동시 편집 충돌 방지 (`baseVersion`)

- `PUT`·`PATCH /api/posts/{id}` 에 선택 필드 `baseVersion`(편집을 시작할 때 불러온 `version`). 지금 버전과 다르면 **409** `"다른 사람이 v6 으로 고쳤습니다(홍길동, 10.27 10:32). 불러온 버전은 v5 입니다. …"`.
- 보내지 않으면 검사하지 않고 덮어쓴다(기존 CLI·API 그대로). 웹 에디터는 항상 보내고, 409 면 "최신 내용 보기(새 탭) / 내 내용으로 덮어쓰기(baseVersion 없이 다시 보냄) / 취소" 를 고르게 한다.

```bash
# 안전하게 고치기: 읽은 버전을 함께 보낸다
V=$(curl -s "$WB/api/posts/31?view=false" -H "Authorization: Bearer $TOKEN" | python -c 'import json,sys;print(json.load(sys.stdin)["version"])')
curl -s -X PATCH $WB/api/posts/31 -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "{\"title\":\"새 제목\",\"baseVersion\":$V}"
```

### 열람 감사 로그

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/posts/{id}/viewers` | 작성자·관리자만(그 외 403). `{ postId, totalViewers, viewers:[{ user, count, lastViewedAt }] }` 최근 열람 순 50명, 작성자 제외 |
| GET | `/api/admin/audit/views?postId=&userId=&from=&to=&page=&size=` | 관리자(API 토큰 불가). 최신순 `{ id, postId, postTitle, postDeleted, user, viewedAt, via }`. `from`·`to` 는 `yyyy-MM-dd`(서울 날짜, 둘 다 포함) |

- `GET /api/posts/{id}` 가 성공할 때마다(`view=false` 포함, 수정 화면이 글을 불러올 때도) `(글, 사용자, 시각, via)` 를 남긴다. `via`: `WEB`(로그인 JWT) · `API_TOKEN`(개인 API 토큰).
- 같은 사람·같은 글은 10분 안에 다시 열어도 한 번만 남는다. 볼 권한이 없어 404 가 난 조회는 남지 않는다. 글·사용자를 영구 삭제하면 기록도 지워진다.
- 화면: 글 상세의 **열람자** 버튼(작성자·관리자), 관리자 메뉴 **감사**(`/admin/audit`, 글 번호·사용자·기간 필터).

### 카테고리 열람 제한 (부서 그룹 권한) — 서버 정보 같은 민감 글용

- 카테고리에 `restrictedDepartments`(부서 이름 목록)를 걸면, 그 카테고리와 **모든 하위 카테고리**의 글은 **그 부서 구성원 · 작성자 · 관리자**만 본다. 비어 있으면 제한 없음.
- 설정: 관리자 `PUT /api/categories/{id}` `{ "name": "서버 정보", "restrictedDepartments": ["인프라팀", "운영팀"] }`. `[]` 이면 해제, 필드를 빼면 그대로 둔다. 화면은 **카테고리 관리 › 편집 › 열람 부서 제한**(쉼표 구분).
- 부서 비교는 사용자 정보의 부서와 앞뒤 공백·대소문자를 무시하고 같아야 한다. 부서가 비어 있는 사용자는 제한 카테고리를 볼 수 없다. 경로 위에 제한이 여럿이면 모두 통과해야 한다.
- 적용 범위: 글 상세 · 목록 · 검색 · 댓글 · 공감 · 변경 이력(글별·전사) · 관련 글 · 내보내기 · 알림 · 통계(볼 수 있는 글만 집계). 태그 모음(`/api/tags`)은 제한 카테고리의 글을 아예 빼고 센다. 사이드바 글 수(`/api/categories` 의 `postCount`)는 숫자만 보인다.
- 한계: 첨부 파일 주소(`/api/files/<id>`)는 지금처럼 주소를 아는 사람이면 받을 수 있다(추측 불가능한 무작위 id). 접속 정보 원문은 본문에 두고 첨부로 올리지 않기를 권한다.

### 비밀번호 정책 · 로그인 보호

- 새 비밀번호(가입 · 내 비밀번호 변경 · 관리자 생성/재설정)는 **8자 이상**(글자 종류 제한 없음) → 짧으면 400 과 이유. 최소 길이는 설정 `workblog.security.password-min-length`(기본 8, env `PASSWORD_MIN_LENGTH`)이고 `GET /api/auth/options` 의 `passwordMinLength` 로 알 수 있다. 이미 있는 계정의 로그인에는 적용하지 않는다.
- 같은 아이디로 로그인에 **5회 연속 실패하면 10분 잠금**. 잠긴 동안은 맞는 비밀번호도 **429** `"… 9분 30초 뒤에 다시 시도하세요."`. 성공하면 실패 횟수는 0. 메모리에만 두므로 서버를 다시 시작하면 풀린다.

## 12. 데이터 이전 (관리자 — 다른 망으로 옮기기)

**관리자 + 웹 로그인(JWT)만.** API 토큰으로 부르면 403. 화면: 관리자 › 데이터 이전(`/admin/data`). 모든 내보내기·미리 보기·적용은 기록(누가·언제·어떤 덤프·결과 보고서)이 남는다.

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/admin/data/info` | 이 서버 인스턴스 ID · 직전 내보내기(기준) 시각 · 올리기 한도 · 최근 기록 30건 |
| GET | `/api/admin/data/export?full=true` | 전체 덤프(zip 스트림). `full=false`(기본)면 `since`(ISO 시각, 없으면 직전 기준 시각) 이후 변경분. `markBaseline=false` 면 다음 기준으로 기록 안 함 |
| POST | `/api/admin/data/export/ticket` | 본문 `{full, since, markBaseline}` → `{ticket}`. 브라우저가 `GET /api/admin/data/export/download?ticket=…` 로 바로 내려받는다(2분·1회용) |
| POST | `/api/admin/data/import` | 본문 = zip 그대로(`Content-Type: application/zip`, 최대 `IMPORT_MAX_MB`, 기본 2048MB). 검사 후 **미리 보기**(아무것도 바꾸지 않음) → `{uploadId, manifest, preview}` |
| POST | `/api/admin/data/import/{uploadId}/apply` | 미리 본 덤프를 적용 → 보고서. 한 번에 하나만(진행 중이면 409). 올린 덤프는 2시간 보관 |
| DELETE | `/api/admin/data/import/{uploadId}` | 올린 덤프 취소 |
| GET | `/api/admin/data/transfers/{id}` | 기록 1건과 보고서 |
| DELETE | `/api/admin/data/sources/{instanceId}` | 시험용 원본의 매핑·가져오기 기록 삭제(만들어진 데이터는 그대로) |

CLI: `workblog.py dump [--full] [--since …] [-o 파일]` · `workblog.py load <zip> [--apply]` (관리자 아이디/비밀번호, [CLI 가이드](/help/cli) 6절). 글 하나 내보내기 `export <id>` 와는 다른 명령이다.

**덤프 형식** `workblog-dump-<인스턴스 앞 8자>-<yyyyMMdd-HHmm>-full|update.zip`

```
manifest.json         {format:"workblog-dump", formatVersion:1, appVersion, sourceInstanceId, dumpName, kind:FULL|UPDATE,
                       exportedAt, since, counts:{users:…, posts:…}, files:[{path, sha256, size}, …]}
data/users.json       사용자(아이디·이름·부서·역할·상태·bcrypt 해시)     data/categories.json  카테고리(경로·순서·열람 제한 부서)
data/templates.json   템플릿                                             data/posts.json       글(본문·요약·태그·상태·공개 범위·공지·작성자·시각·버전, 휴지통은 deletedAt)
data/revisions.json   글 이력                                            data/comments.json    댓글
data/likes.json       공감                                               data/attachments.json 첨부 메타(+ sha256)
data/apitokens.json   API 토큰(SHA-256 해시)                             data/notifications.json 알림
data/postviews.json   열람 감사 로그                                     files/<첨부 id>       첨부 원본
```

- 업데이트 덤프: `since` 10분 전부터(늦게 커밋된 변경까지) 이력·검토·생성이 있는 글과 그 이력·첨부, 바뀐 댓글·공감·알림·열람 기록. 사용자·카테고리·템플릿·API 토큰은 언제나 전부. 공지 고정만 바꾼 글은 다른 변경이 있을 때 함께 넘어간다.
- 검사: 허용한 항목 이름만(경로 탈출 거부) · 항목 수 · 풀었을 때 크기(실제로 읽은 바이트, 업로드 한도의 4배까지) · 압축률 · 모든 항목의 SHA-256. 자기 서버가 만든 덤프는 거부.

**병합 규칙** (매핑 표 = 원본 인스턴스 · 종류 · 원본 id → 대상 id · 마지막 반영 버전)

| 대상 | 규칙 |
|---|---|
| 사용자 | 아이디(대소문자 무시)가 있으면 받는 서버 값 유지, 없으면 원본 bcrypt 해시 그대로 생성(같은 비밀번호로 로그인) |
| 카테고리 | 전체 경로(이름)로 맞추고 없으면 만든다. 원본에 열람 부서 제한이 있는데 받는 서버의 같은 카테고리가 더 넓게 열려 있으면 **충돌** — 그 카테고리·하위·글은 가져오지 않는다(받는 서버의 제한을 맞춘 뒤 다시 가져오기) |
| 글 | 원본 버전이 지난번 반영보다 새로울 때만 갱신 → 새 버전, 이력 change note `데이터 가져오기: <덤프 이름>`. 받는 서버에서 그 사이 고친 글은 **충돌**로 보고하고 덮어쓰지 않는다. 받는 서버에서 영구 삭제한 글은 다시 만들지 않는다 |
| 첨부 | 매핑 또는 같은 글의 같은 SHA-256 파일이 있으면 다시 만들지 않는다. 본문 `/api/files/<원본 id>` 를 새 id 로 바꾼다 |
| 댓글 · 템플릿 | 원본이 더 새로우면 갱신. 받는 서버에서 지난 가져오기 뒤에 고쳤으면 **충돌**로 보고하고 덮어쓰지 않는다. 받는 서버에 같은 이름으로 원래 있던 템플릿은 계속 그대로 둔다 |
| API 토큰 | 가져오기로 **만든 계정**의 토큰만 가져온다(받는 서버에 원래 있던 계정, 예: admin 에는 붙이지 않는다 — 원본 토큰으로 이 계정에 들어오는 것을 막기 위해). 원본에서 폐기한 토큰은 받는 서버에서도 폐기 |
| 이력 · 공감 · 알림 · 열람 기록 | 매핑이나 자연 키로 중복 없이 추가 |

- 가져오기는 받는 서버의 데이터를 지우지 않는다(병합). 방향은 한쪽(원본 → 받는 서버)이다: 받은 서버의 덤프를 원래 서버에 다시 넣으면 원래 글이 새 글로 한 번 더 만들어진다.

- 본문 HTML 은 저장할 때와 같은 정화(HtmlSanitizer)를 거친다.
- 단계(사용자 → 카테고리 → 템플릿 → 글·첨부 → 이력 → 댓글 → 공감 → 알림 → 열람 기록 → API 토큰)별로 50~500건씩 커밋한다. 실패하면 보고서에 멈춘 단계·마지막 커밋 위치가 남고, 같은 덤프를 다시 넣으면 이미 넣은 것은 건너뛰고 이어서 반영한다.

## 13. 공수 표 (Man-month)

가로 = 주(월요일 시작), 세로 = 프로젝트별 인원, 칸 = 그 주 투입률(0~1.0). 화면: 상단 **공수**(`/mm`), 표를 담은 글은 [글로 게시] 로 만든다(본문에 `/mm/{id}` 링크만 있는 줄이 살아 있는 표로 보임). 대문에는 글만 올린다(14절).

| 권한 | 할 수 있는 것 |
|---|---|
| 로그인한 모두 | 보기 · 엑셀 내려받기 · 새 표 만들기(만든 사람 = 주인) |
| 편집자 (표마다 지정) | 칸 · 프로젝트 · 인원 수정, 엑셀 올리기 |
| 주인 · 관리자 | 위 전부 + 표 설정(제목·시작 주·주 수) · 편집자 지정 · 삭제 |
| 관리자 | 모든 표 편집·설정 |

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/mm/sheets` | 표 목록 |
| POST | `/api/mm/sheets` | `{title, startDate, weeks(1~104), description?}` — startDate 는 그 주 월요일로 맞춤 |
| GET · PATCH · DELETE | `/api/mm/sheets/{id}` | 상세(프로젝트·행·칸·편집자·변경 이력 30건) · 설정(시작 주를 바꾸면 값은 날짜 기준으로 유지, 기간 밖은 지움) · 삭제 |
| PUT | `/api/mm/sheets/{id}/editors` | `{userIds:[…]}` 편집자 목록 전체 교체 |
| POST · PUT · DELETE | `/api/mm/sheets/{id}/projects[/{projectId}]` | 프로젝트 `{name, sortOrder?}`. 이름을 바꾸면 '관련' 에 옛 이름을 쓴 이벤트도 새 이름으로 — 관리자는 모든 이벤트, 그 밖에는 자기가 만든 이벤트만(이벤트 수정 권한과 같음, 나머지는 변경 이력에 안내). 다른 표에 옛 이름 프로젝트가 있으면 그대로. 지우면 그 프로젝트 행·값·메모도 지워짐 |
| POST | `/api/mm/sheets/{id}/projects/{projectId}/move?dir=-1|1` | 프로젝트 순서 한 칸 위·아래 |
| POST | `/api/mm/sheets/{id}/rows/{rowId}/move?dir=-1|1` | 인원 순서 한 칸 위·아래(같은 프로젝트 안) |
| POST · PUT · DELETE | `/api/mm/sheets/{id}/rows[/{rowId}]` | 인원 `{projectId, userId?, personName?, department?, role?}` — 계정을 주면 이름은 계정 이름, 소속은 계정 부서(없을 때만 department). 고칠 때 이름·소속·계정은 이 표의 같은 사람(같은 계정, 없으면 같은 이름) 행 모두에 적용, 프로젝트·역할은 그 행만. 계정 이름·부서를 바꾸면 연결된 행이 함께 바뀜 |
| PUT | `/api/mm/sheets/{id}/cells` | `{changes:[{rowId, week, value}]}` — value 가 null·0 이면 비움, 0~1.0 (한 번에 최대 5000칸) |
| GET | `/api/mm/sheets/{id}/excel?template=` | 엑셀(화면과 같은 모양: 왼쪽 프로젝트·이름·소속·역할, 위 월/주). `template=true` 면 값 없는 양식 |
| POST | `/api/mm/sheets/{id}/excel?dryRun=&keepBlank=` | multipart `file` — 주 머리 날짜로 주를, (프로젝트, 이름) 으로 행을 찾아 반영(없으면 만듦). `dryRun=true` 면 미리보기(바뀌는 칸·지워지는 칸 수만, 아무것도 안 바뀜). `keepBlank=true`(기본) 면 빈 칸은 기존 값 유지. 값 50·50% 는 0.5. 파일에 없는 행은 그대로 |

- 합계 MM = 달마다 (그 달 주 투입률 합 ÷ 그 달 월요일 수) 의 합 → 한 달 내내 1.0 = 1 MM.
- 사람별 부하 = 같은 사람(계정, 없으면 이름)의 모든 프로젝트 합. 1.0 초과는 화면에 빨갛게.
- 같은 칸을 여러 명이 동시에 고치면 나중 저장이 남음(화면은 20초마다 다른 사람 변경을 반영). 모든 변경은 변경 이력에 남음.
- 데이터 이전(12절) 덤프에는 아직 공수 표가 들어가지 않음 — 다른 서버로는 엑셀 내려받기 → 올리기로 옮김.

## 14. 대문 (홈 가운데 '전체 글' 위)

관리자가 고른 **글**을 탭으로 보여 준다(공수 표·발표 자료도 그것을 담은 글로). 글 화면의 [☆ 대문에 올리기] 또는 홈의 [대문 고르기] 로 넣고·빼고·순서를 바꾼다.

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/front` | 대문 항목(순서대로). 보는 사람이 볼 수 없는 글·삭제된 글은 빠짐 |
| PUT | `/api/front` | 관리자. `{items:[{kind:"POST", refId}]}` 로 통째로 바꿈(최대 30). 글이 아닌 항목(MM_SHEET)은 무시. 휴지통에 간 대문 글은 목록에 안 보여도 남아 있다가 복구하면 돌아옴, 영구 삭제하면 빠짐 |

## 15. 태그 · 부서 관리 (관리자)

태그와 부서는 따로 표가 없고 글자로 여러 곳에 있다(태그: 글·템플릿, 부서: 사용자·카테고리 열람 제한·공수 표 소속). 아래 API 는 그 모든 곳을 한 트랜잭션에서 바꾼다. 비교는 대소문자·앞뒤 공백 무시. 화면은 관리 > 카테고리 아래 [태그], 관리 > 사용자 아래 [부서].

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/admin/tags` | `[{name, posts, templates}]` 태그마다 쓰는 글(휴지통 포함)·템플릿 수 |
| PUT | `/api/admin/tags/rename` | `{from, to}` — 모든 글·템플릿에서 from 을 to 로(이미 있으면 합침). `to` 가 비면 지움 → `{posts, templates}` |
| GET | `/api/admin/departments` | `[{name, users, categories}]` |
| PUT | `/api/admin/departments/rename` | `{from, to}` — 사용자 부서 + 카테고리 열람 제한 + 공수 표 소속을 함께(이미 있으면 합침) → `{users, categories}` |

- 글 저장 때 대소문자만 다른 태그는 하나로 합친다(먼저 나온 글자 그대로).
- 사용자 영구 삭제는 그 계정의 글·댓글·글 수정 기록·글에 붙은 첨부가 남아 있으면 400(이유를 알려 줌) — 대신 비활성화.
- 카테고리 트리의 글 수는 보는 사람이 실제로 볼 수 있는 글만 센다. 휴지통 글이 남은 카테고리는 지울 수 없다(400 에 휴지통 안내).
- 글을 영구 삭제해도 다른 글·이력·템플릿이 같은 사진을 쓰면 그 파일은 남는다.
