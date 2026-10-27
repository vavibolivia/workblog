# 2026-10-27 추가 기능 스모크: 회원 가입(R13) · API 토큰·PATCH·카테고리 경로(R14/R15) · 파일 가져오기(R16) · 내보내기(R12) · 문서(R14) · 영구 삭제.
# smoke.py 안에서 exec 된다(call/check/upload, 토큰 A/U/O, uname, rich_pid 재사용). 단독 실행은 지원하지 않는다.
# 주의: smoke_comments.py 가 rid/cid 를 댓글 id 로 덮어쓰므로 글 id 는 rich_pid 를 쓴다.
import io, time as _t, zipfile as _z

_sfx = str(int(_t.time()))[-6:]

def _mp(method, path, name, data, ctype, tok, fields=None):
    """multipart 요청(필드 + file). upload() 는 POST /api/files 전용이라 별도."""
    boundary = "----wbsmoke2"
    body = b""
    for k, v in (fields or {}).items():
        body += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n{v}\r\n").encode("utf-8")
    body += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{name}\"\r\nContent-Type: {ctype}\r\n\r\n").encode("utf-8") + data + f"\r\n--{boundary}--\r\n".encode()
    req = urllib.request.Request(B + path, method=method, data=body)
    req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    req.add_header("Authorization", "Bearer " + tok)
    try:
        with urllib.request.urlopen(req) as r:
            t = r.read().decode(); return r.status, (json.loads(t) if t else None)
    except urllib.error.HTTPError as e:
        t = e.read().decode(); return e.code, (json.loads(t) if t else None)

def _raw(path, tok, headers=None):
    req = urllib.request.Request(B + path)
    req.add_header("Authorization", "Bearer " + tok)
    for k, v in (headers or {}).items(): req.add_header(k, v)
    try:
        with urllib.request.urlopen(req) as r: return r.status, r.read(), dict(r.headers)
    except urllib.error.HTTPError as e: return e.code, e.read(), dict(e.headers)

def _xlsx(rows):
    """최소 xlsx(인라인 문자열). POI 가 읽을 수 있는 구조."""
    def col(i):
        s = ""; i += 1
        while i: i, r = divmod(i - 1, 26); s = chr(65 + r) + s
        return s
    sd = "".join(f'<row r="{ri+1}">' + "".join(f'<c r="{col(ci)}{ri+1}" t="inlineStr"><is><t>{v}</t></is></c>' for ci, v in enumerate(row)) + "</row>" for ri, row in enumerate(rows))
    files = {
        "[Content_Types].xml": '<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>',
        "_rels/.rels": '<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>',
        "xl/workbook.xml": '<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="서버" sheetId="1" r:id="rId1"/></sheets></workbook>',
        "xl/_rels/workbook.xml.rels": '<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>',
        "xl/worksheets/sheet1.xml": f'<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>{sd}</sheetData></worksheet>',
    }
    buf = io.BytesIO()
    with _z.ZipFile(buf, "w", _z.ZIP_DEFLATED) as z:
        for n, c in files.items(): z.writestr(n, c)
    return buf.getvalue()

def _docx(paras):
    """최소 docx: (style, text) 목록. style: Heading1 | None"""
    _h1 = '<w:pPr><w:pStyle w:val="Heading1"/></w:pPr>'
    body = "".join('<w:p>' + (_h1 if st else "") + '<w:r><w:t xml:space="preserve">' + tx + '</w:t></w:r></w:p>' for st, tx in paras)
    body += '<w:tbl><w:tr><w:tc><w:p><w:r><w:t>칸1</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>칸2</w:t></w:r></w:p></w:tc></w:tr></w:tbl>'
    files = {
        "[Content_Types].xml": '<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>',
        "_rels/.rels": '<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>',
        "word/_rels/document.xml.rels": '<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>',
        "word/styles.xml": '<?xml version="1.0" encoding="UTF-8"?><w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/></w:style></w:styles>',
        "word/document.xml": f'<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>{body}</w:body></w:document>',
    }
    buf = io.BytesIO()
    with _z.ZipFile(buf, "w", _z.ZIP_DEFLATED) as z:
        for n, c in files.items(): z.writestr(n, c)
    return buf.getvalue()

# ---- R13 회원 가입 ----
s, r = call("GET", "/api/auth/options"); check("R13 options public", s == 200 and "signupEnabled" in r and r.get("drawioUrl"), (s, r))
_su = "join" + _sfx
s, r = call("POST", "/api/auth/signup", {"username": _su, "password": "pass1234", "displayName": "가입자", "department": "운항통제"})
check("R13 signup 201 with token (instant approval)", s == 201 and r["approved"] is True and r["token"] and r["user"]["role"] == "USER", (s, r)); S = r["token"]; sid_ = r["user"]["id"]
s, r = call("POST", "/api/auth/signup", {"username": _su.upper(), "password": "pass1234", "displayName": "중복"}); check("R13 duplicate username (case-insensitive) 409", s == 409, s)
s, r = call("POST", "/api/auth/signup", {"username": "bad name!", "password": "pass1234", "displayName": "x"}); check("R13 invalid username 400", s == 400, s)
s, r = call("GET", "/api/auth/me", tok=S); check("R13 signed-up user can use token", s == 200 and r["username"] == _su, (s, r))

# ---- API 토큰 (R14) ----
s, r = call("POST", "/api/tokens", {"name": "스모크 토큰", "expiresInDays": 30}, U); check("TOKEN create 201 returns wbt_ once", s == 201 and r["token"].startswith("wbt_") and r["info"]["active"] is True, (s, r)); WBT = r["token"]; tid = r["info"]["id"]
s, r = call("GET", "/api/auth/me", tok=WBT); check("TOKEN works as Bearer", s == 200 and r["username"] == uname, (s, r))
req = urllib.request.Request(B + "/api/auth/me"); req.add_header("X-API-Key", WBT)
try:
    with urllib.request.urlopen(req) as rr: check("TOKEN works as X-API-Key", rr.status == 200, rr.status)
except Exception as e: check("TOKEN works as X-API-Key", False, e)
s, r = call("GET", "/api/tokens", tok=WBT); check("TOKEN cannot manage tokens with a token 403", s == 403, s)
s, r = call("PUT", "/api/users/me/password", {"currentPassword": "pass1234", "newPassword": "pass9999"}, WBT); check("TOKEN cannot change password 403", s == 403, s)
s, r = call("GET", "/api/tokens", tok=U); check("TOKEN list shows token with prefix only", s == 200 and any(t["id"] == tid and "token" not in t for t in r), r)
s, r = call("DELETE", f"/api/tokens/{tid}", tok=O); check("TOKEN others cannot revoke 404", s == 404, s)
s, r = call("DELETE", f"/api/tokens/{tid}", tok=U); check("TOKEN revoke 204", s == 204, s)
s, r = call("GET", "/api/auth/me", tok=WBT); check("TOKEN revoked token rejected 401", s == 401, s)
s, r = call("GET", "/api/auth/me", tok="wbt_doesnotexist0000000000000000000000000000"); check("TOKEN unknown token 401", s == 401, s)

# ---- PATCH · 카테고리 경로 (R15) ----
s, r = call("POST", "/api/tokens", {"name": "cli"}, U); WBT2 = r["token"]
s, r = call("GET", "/api/categories", tok=U); _tree = r
_cat = next(n for n in _tree if n["name"] == f"T{suffix}-L1")
_path = f"T{suffix}-L1 > T{suffix}-L2"
s, r = call("PATCH", f"/api/posts/{rich_pid}", {"title": "제목만 수정"}, WBT2); check("PATCH title only keeps content (v+1)", s == 200 and r["title"] == "제목만 수정" and "강조" in r["content"], (s, r.get("title") if isinstance(r, dict) else r))
_v = r["version"]
s, r = call("PATCH", f"/api/posts/{rich_pid}", {"categoryPath": _path, "changeNote": "경로로 이동"}, WBT2); check("PATCH categoryPath moves post", s == 200 and r["category"]["path"][-1] == f"T{suffix}-L2" and r["version"] == _v + 1, (s, r.get("category") if isinstance(r, dict) else r))
s, r = call("PATCH", f"/api/posts/{rich_pid}", {"categoryPath": "없는 > 경로"}, WBT2); check("PATCH unknown categoryPath 400", s == 400, (s, r))
s, r = call("PATCH", f"/api/posts/{rich_pid}", {"content": "  "}, WBT2); check("PATCH blank content 400", s == 400, s)
s, r = call("PATCH", f"/api/posts/{rich_pid}", {"title": "남의 글"}, O); check("PATCH other user 403", s == 403, s)
s, r = call("POST", "/api/posts", {"title": "경로로 만든 글", "content": "<p>본문</p>", "categoryPath": _path.lower(), "tags": [], "status": "PUBLISHED"}, WBT2)
check("POST categoryPath (case-insensitive) 201", s == 201 and r["category"]["path"][-1] == f"T{suffix}-L2", (s, r)); cpid = r["id"]

# ---- 파일 가져오기 (R16) ----
s, r = call("GET", "/api/import/formats", tok=U); check("IMPORT formats lists docx/xlsx/html", s == 200 and {"docx", "xlsx", "html", "csv", "md"} <= set(r["extensions"]), r)
_html = ('<html><head><title>HTML 문서 제목</title><style>p{color:red}</style></head><body><h1>HTML 문서 제목</h1><p style="color:#d7263d">빨간 <b>굵게</b></p>'
         '<img src="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNgYAAAAAMAASsJTYQAAAAASUVORK5CYII="><script>alert(1)</script><a href="http://example.com">링크</a></body></html>').encode("utf-8")
s, r = _mp("POST", "/api/import/convert", "doc.html", _html, "text/html", U, {"attachOriginal": "false"})
check("IMPORT convert html: title from <title>, style kept, script gone", s == 200 and r["title"] == "HTML 문서 제목" and "color:#d7263d" in r["content"] and "<script" not in r["content"] and r["sourceType"] == "HTML", (s, r))
check("IMPORT convert html: data: image became attachment + target=_blank link", s == 200 and len(r["attachments"]) == 1 and r["attachments"][0]["url"] in r["content"] and 'target="_blank"' in r["content"], r.get("content") if s == 200 else r)
check("IMPORT convert html: <h1> equal to title removed from body", s == 200 and "<h1>" not in r["content"], r.get("content") if s == 200 else r)
s, r = _mp("POST", "/api/posts/import", "서버목록.xlsx", _xlsx([["서버", "IP", "용도"], ["20번", "10.0.0.20", "gitlab"], ["103번", "10.0.0.103", "시연"]]), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", U, {"categoryPath": _path, "tags": "엑셀, 가져오기", "summary": "엑셀로 만든 글"})
check("IMPORT xlsx -> post with table + original attached", s == 201 and r["sourceType"] == "EXCEL" and "<table" in r["post"]["content"] and "<th>" in r["post"]["content"] and "10.0.0.103" in r["post"]["content"] and any(a["name"] == "서버목록.xlsx" for a in r["post"]["attachments"]), (s, r))
check("IMPORT xlsx: title from filename, category path, tags", s == 201 and r["post"]["title"] == "서버목록" and r["post"]["category"]["path"][-1] == f"T{suffix}-L2" and r["post"]["tags"] == ["엑셀", "가져오기"], r["post"] if s == 201 else r); xpid = r["post"]["id"] if s == 201 else None
s, r = _mp("POST", "/api/posts/import", "절차.docx", _docx([("Heading1", "배포 절차"), (None, "첫 문단입니다."), (None, "둘째 문단")]), "application/vnd.openxmlformats-officedocument.wordprocessingml.document", U, {"title": "워드로 만든 글"})
check("IMPORT docx -> post (paragraphs + table)", s == 201 and r["sourceType"] == "WORD" and "첫 문단입니다." in r["post"]["content"] and "<table" in r["post"]["content"] and r["post"]["title"] == "워드로 만든 글", (s, r)); dpid = r["post"]["id"] if s == 201 else None
s, r = _mp("POST", "/api/posts/import", "데이터.csv", "이름,값\n에이,1\n\"비,씨\",2\n".encode("utf-8"), "text/csv", U, {})
check("IMPORT csv -> table with quoted comma", s == 201 and "비,씨" in r["post"]["content"] and "<th>" in r["post"]["content"], (s, r)); csvpid = r["post"]["id"] if s == 201 else None
s, r = _mp("POST", "/api/posts/import", "메모.md", "# 마크다운 제목\n\n- 항목 **굵게**\n\n| a | b |\n|---|---|\n| 1 | 2 |\n".encode("utf-8"), "text/markdown", U, {"status": "DRAFT"})
check("IMPORT md -> title from # heading, list+table, DRAFT", s == 201 and r["post"]["title"] == "마크다운 제목" and "<li>" in r["post"]["content"] and "<table" in r["post"]["content"] and r["post"]["status"] == "DRAFT", (s, r)); mdpid2 = r["post"]["id"] if s == 201 else None
s, r = _mp("POST", "/api/posts/import", "virus.exe", b"MZ", "application/octet-stream", U, {}); check("IMPORT unsupported ext 400", s == 400, s)
s, r = _mp("POST", "/api/posts/import", "old.doc", b"\xd0\xcf\x11\xe0", "application/msword", U, {}); check("IMPORT .doc explains docx 400", s == 400 and "docx" in r["message"], (s, r))
s, r = _mp("POST", "/api/posts/import", "broken.xlsx", b"PK\x03\x04junk", "application/octet-stream", U, {}); check("IMPORT corrupt xlsx 400", s == 400, s)
s, r = _mp("PUT", f"/api/posts/{cpid}/import", "새본문.html", "<p>교체된 본문</p>".encode("utf-8"), "text/html", WBT2, {"attachOriginal": "false"})
check("IMPORT replace body keeps title/category, new revision", s == 200 and "교체된 본문" in r["post"]["content"] and r["post"]["title"] == "경로로 만든 글" and r["post"]["version"] == 2, (s, r))
s, r = _mp("PUT", f"/api/posts/{cpid}/import", "x.html", b"<p>x</p>", "text/html", O, {}); check("IMPORT replace other user 403", s == 403, s)

# ---- 내보내기 (R12) ----
s, b, h = _raw(f"/api/posts/{rich_pid}/export?format=html", U)
check("EXPORT html: standalone file with title + content + attachment list", s == 200 and b.startswith(b"<!DOCTYPE html>") and "제목만 수정".encode() in b and "강조".encode() in b and "첨부파일".encode() in b and "attachment" in h.get("Content-Disposition", ""), (s, h.get("Content-Disposition")))
s, b, h = _raw(f"/api/posts/{rich_pid}/export?format=md&attachments=false", U)
check("EXPORT md: heading + no attachment section", s == 200 and b.startswith("# 제목만 수정".encode()) and "## 첨부파일".encode() not in b and "text/markdown" in h.get("Content-Type", ""), (s, b[:120]))
s, b, h = _raw(f"/api/posts/{rich_pid}/export?format=docx", U)
check("EXPORT docx: valid zip with document.xml containing text", s == 200 and b[:2] == b"PK" and "wordprocessingml" in h.get("Content-Type", "") and "강조".encode("utf-8") in _z.ZipFile(io.BytesIO(b)).read("word/document.xml"), (s, h.get("Content-Type")))
s, b, h = _raw(f"/api/posts/{rich_pid}/export?format=pdf", U); check("EXPORT unknown format 400", s == 400, s)
s, b, h = _raw(f"/api/posts/{did}/export?format=html", O); check("EXPORT others' draft 404", s == 404, s)
s, b, h = _raw(f"/api/posts/{xpid}/export?format=docx", U); check("EXPORT docx of imported table post", s == 200 and b[:2] == b"PK", s)

# ---- 정렬 (R18) ----
s, r = call("GET", "/api/posts?sort=POPULAR&size=50", tok=U); _lk = [p["likeCount"] for p in r["content"] if not p["pinned"]]
check("SORT popular: like counts non-increasing", s == 200 and _lk == sorted(_lk, reverse=True), _lk[:10])
s, r = call("GET", "/api/posts?sort=VIEWS&size=50", tok=U); _vc = [p["viewCount"] for p in r["content"] if not p["pinned"]]
check("SORT views: view counts non-increasing", s == 200 and _vc == sorted(_vc, reverse=True), _vc[:10])
s, r = call("GET", "/api/posts?sort=OLDEST&size=50", tok=U); _dt = [p["publishedAt"] or p["createdAt"] for p in r["content"] if not p["pinned"]]
check("SORT oldest: dates non-decreasing", s == 200 and _dt == sorted(_dt), _dt[:5])
s, r = call("GET", "/api/posts?sort=LATEST&size=50", tok=U); _dt = [p["publishedAt"] or p["createdAt"] for p in r["content"] if not p["pinned"]]
check("SORT latest: dates non-increasing", s == 200 and _dt == sorted(_dt, reverse=True), _dt[:5])
s, r = call("GET", "/api/posts?sort=COMMENTS&size=50", tok=U); _cc = [p["commentCount"] for p in r["content"] if not p["pinned"]]
check("SORT comments: comment counts non-increasing", s == 200 and _cc == sorted(_cc, reverse=True), _cc[:10])
s, r = call("GET", "/api/posts?sort=BOGUS", tok=U); check("SORT unknown value 400", s == 400, s)

# ---- 문서 (R14) ----
s, r = call("GET", "/api/guide"); check("GUIDE list public", s == 200 and {d["id"] for d in r} >= {"api", "claude-code"}, (s, r))
s, b, h = _raw("/api/guide/claude-code", U); check("GUIDE claude-code markdown mentions token + CLI", s == 200 and b"wbt_" in b and b"workblog.py" in b, (s, b[:80]))
s, b, h = _raw("/api/guide/api", U); check("GUIDE api markdown lists PATCH/import/export", s == 200 and b"PATCH" in b and b"/api/posts/import" in b and b"/export" in b, (s, b[:80]))
s, b, h = _raw("/api/guide/workblog.py", U); check("GUIDE CLI script served", s == 200 and b.startswith(b"#!/usr/bin/env python3"), (s, b[:40]))
req = urllib.request.Request(B + "/v3/api-docs")
try:
    with urllib.request.urlopen(req) as rr: _oa = json.loads(rr.read().decode()); check("OPENAPI /v3/api-docs has paths", "/api/posts/{id}/export" in _oa["paths"] and "/api/posts/import" in _oa["paths"], list(_oa["paths"])[:5])
except Exception as e: check("OPENAPI /v3/api-docs has paths", False, e)

# ---- 영구 삭제 ----
s, r = call("DELETE", f"/api/posts/{cpid}?purge=true", tok=U); check("PURGE non-admin 403", s == 403, s)
s, r = call("DELETE", f"/api/posts/{cpid}?purge=true", tok=A); check("PURGE not-yet-deleted post 400", s == 400, s)
s, r = call("DELETE", f"/api/posts/{cpid}", tok=U); s, r = call("DELETE", f"/api/posts/{cpid}?purge=true", tok=A); check("PURGE deleted post 204", s == 204, s)
s, r = call("GET", f"/api/posts/{cpid}", tok=A); check("PURGE post gone 404", s == 404, s)
s, r = call("DELETE", f"/api/users/{uid}?purge=true", tok=A); check("PURGE user with posts 400", s == 400, (s, r))

# ---- 정리: 이 스모크가 만든 글·카테고리·계정을 모두 지운다(테스트 데이터가 서버에 남지 않게) ----
_posts = [p for p in [pid, did, rich_pid, mdid, npid, ppid, prid, xpid, dpid, csvpid, mdpid2] if p]
try: _posts += [cm_pid, cm_did]
except NameError: pass
for _p in _posts:
    call("DELETE", f"/api/posts/{_p}", tok=A); call("DELETE", f"/api/posts/{_p}?purge=true", tok=A)
s, r = call("GET", "/api/posts/deleted", tok=A); check("CLEANUP smoke posts purged", s == 200 and not any(p["id"] in _posts for p in r), [p["id"] for p in r if p["id"] in _posts])
for _c in [ids[4], ids[3], ids[2], ids[1], ids[0], branch]:
    call("DELETE", f"/api/categories/{_c}", tok=A)
s, r = call("GET", "/api/categories", tok=A); check("CLEANUP smoke categories removed", s == 200 and not any(n["name"].startswith((f"T{suffix}", f"B{suffix}")) for n in r), [n["name"] for n in r])
_users = [uid, sid_]
s, r = call("GET", "/api/users", tok=A)
_users += [u["id"] for u in r if u["username"] in ("o" + uname,) or u["username"].startswith("cmt") or u["username"].startswith("ocmt")]
for _u in _users:
    call("DELETE", f"/api/users/{_u}?purge=true", tok=A)
s, r = call("GET", "/api/users", tok=A); check("CLEANUP smoke users purged", s == 200 and not any(u["id"] in _users for u in r), [u["username"] for u in r if u["id"] in _users])
