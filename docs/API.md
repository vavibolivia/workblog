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
| POST | `/api/auth/signup` | 회원 가입 `{ username, password, displayName, email?, department? }` → 201(즉시 승인, token 포함) / 202(승인 대기) |
| GET | `/api/auth/options` | 가입 가능 여부, 다이어그램 편집기 주소(토큰 불필요) |
| GET | `/api/auth/me` | 내 계정 |
| GET / POST / DELETE | `/api/tokens`, `/api/tokens/{id}` | 내 API 토큰 목록 · 발급 `{ name, expiresInDays? }` · 폐기 |

## 2. 글 (CRUD)

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/posts` | 목록·검색. 쿼리: `q`(제목·본문·태그·카테고리·작성자), `categoryId`(하위 포함), `tag`, `authorId`, `status`(DRAFT/PUBLISHED), `sort`(LATEST 최신순 기본 · POPULAR 인기순 · VIEWS 조회순 · COMMENTS 댓글 많은 순 · OLDEST 오래된순), `page`(0부터), `size`(≤100) |
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
| POST | `/api/categories` | 관리자. `{ name, parentId }` (6단계가 되면 400, 형제 중복 409) |
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
| PUT | `/api/users/me`, `/api/users/me/password` | 내 정보 · 비밀번호 변경(JWT 만) |

## 8. 문서 · 상태

| 경로 | 설명 |
|---|---|
| `/api/guide` · `/api/guide/{api|cli|writing}` · `/api/guide/workblog.py` | 이 문서 · CLI 가이드 · 글 작성 지침 · CLI 스크립트(토큰 불필요) |
| `/swagger-ui/index.html` · `/v3/api-docs` | 대화형 문서 · OpenAPI JSON |
| `/api/health` | `{ status: "UP" }` |

## 9. 권한 요약

- 발행 + 전체 공개 글: 로그인한 모두 조회. 임시 저장·비공개·삭제된 글: 작성자와 관리자만(남에게는 404).
- 열람 제한 카테고리(하위 포함)의 글: 지정 부서 구성원 · 작성자 · 관리자만(남에게는 404, 11절).
- 수정·삭제·복원: 작성자와 관리자. 공지 고정·카테고리 관리·사용자 관리·영구 삭제: 관리자.
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

- 새 비밀번호(가입 · 내 비밀번호 변경 · 관리자 생성/재설정)는 **8자 이상 + 영문과 숫자 모두** → 어기면 400 과 이유. 최소 길이는 설정 `workblog.security.password-min-length`(기본 8, env `PASSWORD_MIN_LENGTH`)이고 `GET /api/auth/options` 의 `passwordMinLength` 로 알 수 있다. 이미 있는 계정의 로그인에는 적용하지 않는다.
- 같은 아이디로 로그인에 **5회 연속 실패하면 10분 잠금**. 잠긴 동안은 맞는 비밀번호도 **429** `"… 9분 30초 뒤에 다시 시도하세요."`. 성공하면 실패 횟수는 0. 메모리에만 두므로 서버를 다시 시작하면 풀린다.
