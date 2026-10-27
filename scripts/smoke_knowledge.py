# 지식 구조(템플릿·관련 글·재검토·첨부 내용 검색) 스모크 테스트. scripts/smoke.py 의 call()/check()/upload() 와
# 토큰(A=관리자, U=작성자, O=다른 사용자)을 그대로 쓴다. 만든 글·첨부·템플릿은 끝에서 스스로 영구 삭제한다.
#   smoke.py 안에서 실행:  exec(open("scripts/smoke_knowledge.py", encoding="utf-8").read())
#   단독 실행:            python scripts/smoke_knowledge.py http://localhost:8080 <admin_pw>
import io as _kio, time as _kt, zipfile as _kzip
if "call" not in globals():
    import json, sys, urllib.request, urllib.parse
    B = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
    ADMIN_PW = sys.argv[2] if len(sys.argv) > 2 else "admin1234"
    def call(m, p, body=None, tok=None):
        req = urllib.request.Request(B + urllib.parse.quote(p, safe="/?=&"), method=m, data=json.dumps(body).encode() if body is not None else None)
        req.add_header("Content-Type", "application/json")
        if tok: req.add_header("Authorization", "Bearer " + tok)
        try:
            with urllib.request.urlopen(req) as r:
                t = r.read().decode(); return r.status, (json.loads(t) if t else None)
        except urllib.error.HTTPError as e:
            t = e.read().decode(); return e.code, (json.loads(t) if t else None)
    def upload(name, data, ctype, tok):
        boundary = "----wbsmoke"
        body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{name}\"\r\nContent-Type: {ctype}\r\n\r\n").encode() + data + f"\r\n--{boundary}--\r\n".encode()
        req = urllib.request.Request(B + "/api/files", method="POST", data=body)
        req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
        req.add_header("Authorization", "Bearer " + tok)
        try:
            with urllib.request.urlopen(req) as r:
                return r.status, json.loads(r.read().decode())
        except urllib.error.HTTPError as e:
            t = e.read().decode(); return e.code, (json.loads(t) if t else None)
    ok = 0; bad = 0
    def check(name, cond, info=""):
        global ok, bad
        cond = bool(cond)
        print(("PASS " if cond else "FAIL ") + name + ("" if cond else f"  -> {info}")); ok += cond; bad += (not cond)
    s, r = call("POST", "/api/auth/login", {"username": "admin", "password": ADMIN_PW}); A = r["token"]
    _kn = "kn" + str(int(_kt.time()))[-6:]
    call("POST", "/api/users", {"username": _kn, "password": "pass1234", "displayName": "지식작성자", "role": "USER"}, A)
    call("POST", "/api/users", {"username": "o" + _kn, "password": "pass1234", "displayName": "다른사람", "role": "USER"}, A)
    s, r = call("POST", "/api/auth/login", {"username": _kn, "password": "pass1234"}); U = r["token"]
    s, r = call("POST", "/api/auth/login", {"username": "o" + _kn, "password": "pass1234"}); O = r["token"]
    _standalone = True
else:
    _standalone = False

_ks = str(int(_kt.time() * 1000))[-7:]
_k_posts = []; _k_templates = []

def _k_post(tok, title, tags, content="본문", status="PUBLISHED", visibility="PUBLIC", attachments=None):
    body = {"title": title, "content": content, "contentFormat": "HTML", "tags": tags, "status": status, "visibility": visibility}
    if attachments is not None: body["attachmentIds"] = attachments
    s, r = call("POST", "/api/posts", body, tok)
    if s == 201: _k_posts.append(r["id"])
    return s, r

def _k_pdf(text):
    objs = [b"<< /Type /Catalog /Pages 2 0 R >>", b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>"]
    stream = ("BT /F1 12 Tf 72 700 Td (" + text + ") Tj ET").encode()
    objs.append(b"<< /Length " + str(len(stream)).encode() + b" >>\nstream\n" + stream + b"\nendstream")
    objs.append(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    out = b"%PDF-1.4\n"; offs = []
    for i, o in enumerate(objs, 1):
        offs.append(len(out)); out += str(i).encode() + b" 0 obj\n" + o + b"\nendobj\n"
    x = len(out)
    out += b"xref\n0 " + str(len(objs) + 1).encode() + b"\n0000000000 65535 f \n"
    for o in offs: out += ("%010d 00000 n \n" % o).encode()
    out += b"trailer\n<< /Size " + str(len(objs) + 1).encode() + b" /Root 1 0 R >>\nstartxref\n" + str(x).encode() + b"\n%%EOF\n"
    return out

def _k_docx(text):
    b = _kio.BytesIO()
    with _kzip.ZipFile(b, "w") as z:
        z.writestr("[Content_Types].xml", '<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
        z.writestr("_rels/.rels", '<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
        z.writestr("word/document.xml", '<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>' + text + '</w:t></w:r></w:p></w:body></w:document>')
    return b.getvalue()

# ---- K1 글 템플릿 ----
s, r = call("GET", "/api/templates", tok=U)
names = [t["name"] for t in r] if s == 200 else []
check("K1 templates listed for users (3 built-in)", s == 200 and all(n in names for n in ("공문형 지침", "서버 정보", "장애 보고")), (s, names))
gw = next((t for t in r if t["name"] == "공문형 지침"), None) if s == 200 else None
check("K1 built-in 공문형 has header table and 끝.", gw is not None and "문서번호" in gw["content"] and "1. 관련" in gw["content"] and "끝." in gw["content"], gw and gw["content"][:200])
s, r = call("POST", "/api/templates", {"name": "x", "content": "<p>x</p>", "tags": []}, U); check("K1 non-admin cannot create 403", s == 403, s)
s, r = call("POST", "/api/templates", {"name": f"스모크 템플릿 {_ks}", "description": "설명", "content": "<p>양식</p><script>alert(1)</script>", "tags": ["스모크", "양식"], "sortOrder": 99}, A)
check("K1 admin creates template (sanitized)", s == 201 and "<script" not in r["content"] and r["tags"] == ["스모크", "양식"] and r["category"] is None, (s, r))
tid = r["id"] if s == 201 else 0
if s == 201: _k_templates.append(tid)
s, r = call("PUT", f"/api/templates/{tid}", {"name": f"스모크 템플릿 {_ks} 수정", "content": "<p>양식2</p>", "tags": []}, A)
check("K1 admin updates template", s == 200 and r["name"].endswith("수정") and r["tags"] == [] and r["sortOrder"] == 99, (s, r))
s, r = call("PUT", f"/api/templates/{tid}", {"name": " ", "content": "<p>x</p>", "tags": []}, A); check("K1 blank name 400", s == 400, s)
s, r = call("PUT", f"/api/templates/{tid}", {"name": "y", "content": "<p>x</p>", "tags": [], "categoryId": 999999999}, A); check("K1 unknown category 400", s == 400, s)
s, r = call("DELETE", f"/api/templates/{tid}", tok=U); check("K1 non-admin cannot delete 403", s == 403, s)
s, r = call("DELETE", f"/api/templates/{tid}", tok=A); check("K1 admin deletes template 204", s == 204, s)
if s == 204: _k_templates.remove(tid)
s, r = call("GET", f"/api/templates/{tid}", tok=A); check("K1 deleted template 404", s == 404, s)

# ---- K2 관련 글 · 백링크 ----
t1, t2 = f"관련{_ks}a", f"관련{_ks}b"
s, r = _k_post(U, "관련 기준 글", [t1, t2]); base = r["id"]
s, r = _k_post(U, "태그 둘 겹침", [t2, t1, "기타"]); both = r["id"]
s, r = _k_post(U, "태그 하나 겹침", [t1]); one = r["id"]
s, r = _k_post(O, "남의 비공개", [t1, t2], visibility="PRIVATE"); priv = r["id"]
s, r = _k_post(O, "남의 임시", [t1, t2], status="DRAFT"); draft = r["id"]
s, r = _k_post(O, "링크하는 글", [], content=f'<p>참고: <a href="/posts/{base}">기준 글</a></p>'); linker = r["id"]
s, r = _k_post(O, "비공개로 링크", [], content=f'<p><a href="/posts/{base}">x</a></p>', visibility="PRIVATE"); plinker = r["id"]
s, r = _k_post(O, "다른 번호 링크", [], content=f'<p><a href="/posts/{base}0">x</a></p>'); otherlink = r["id"]
s, r = call("GET", f"/api/posts/{base}/related", tok=U)
rel = [p["id"] for p in r["related"]] if s == 200 else []
bl = [p["id"] for p in r["backlinks"]] if s == 200 else []
check("K2 related ordered by score (2 tags before 1 tag)", s == 200 and both in rel and one in rel and rel.index(both) < rel.index(one), (s, rel))
check("K2 related score = tags*2 + same category", s == 200 and next(p for p in r["related"] if p["id"] == both)["score"] == 5 and sorted(next(p for p in r["related"] if p["id"] == both)["commonTags"]) == sorted([t1, t2]), r and r.get("related"))
check("K2 related excludes others' private/draft and self", s == 200 and priv not in rel and draft not in rel and base not in rel and len(rel) <= 5, rel)
check("K2 backlinks: linking post only (not private, not /posts/{id}0)", s == 200 and linker in bl and plinker not in bl and otherlink not in bl, bl)
s, r = call("GET", f"/api/posts/{base}/related", tok=O)
check("K2 owner sees own private in related/backlinks", s == 200 and priv in [p["id"] for p in r["related"]] and plinker in [p["id"] for p in r["backlinks"]], s)
s, r = call("GET", f"/api/posts/{priv}/related", tok=U); check("K2 related of invisible post 404", s == 404, s)
s, r = call("GET", "/api/posts/999999999/related", tok=U); check("K2 related of missing post 404", s == 404, s)

# ---- K3 재검토 ----
s, r = call("GET", f"/api/posts/{base}", tok=U); v0 = r["version"] if s == 200 else -1
s, r = call("GET", f"/api/posts/{base}/review", tok=U)
check("K3 fresh post not stale", s == 200 and r["stale"] is False and r["staleDays"] > 0 and r["canConfirm"] is False and r["daysSince"] == 0 and r["lastModifiedAt"], (s, r))
s, r = call("POST", f"/api/posts/{base}/review-ok", tok=O); check("K3 other user cannot confirm 403", s == 403, s)
s, r = call("POST", f"/api/posts/{base}/review-ok", tok=U); check("K3 author confirms (reviewedAt set)", s == 200 and r["reviewedAt"] and r["stale"] is False, (s, r))
s, r = call("GET", f"/api/posts/{base}", tok=U); check("K3 confirm does not bump version", s == 200 and r["version"] == v0, (v0, r and r.get("version")))
s, r = call("GET", f"/api/posts/{base}/revisions", tok=U); check("K3 confirm adds no revision", s == 200 and len(r) == 1, (s, r and len(r)))
s, r = call("POST", f"/api/posts/{draft}/review-ok", tok=O); check("K3 draft cannot be confirmed 400", s == 400, s)
s, r = call("POST", f"/api/posts/{base}/review-ok", tok=A); check("K3 admin confirms", s == 200, s)
s, r = call("GET", f"/api/posts/{priv}/review", tok=U); check("K3 review of invisible post 404", s == 404, s)
s, r = call("GET", "/api/admin/review/stale?page=0", tok=A)
check("K3 admin stale list (page shape, no fresh smoke posts)", s == 200 and "totalElements" in r and not any(x["id"] in _k_posts for x in r["content"]), (s, r))
s, r = call("GET", "/api/admin/review/stale", tok=U); check("K3 stale list admin only 403", s == 403, s)

# ---- K4 첨부 파일 내용 검색 ----
w_csv, w_pdf, w_docx, w_priv = f"kcsv{_ks}zeta", f"kpdf{_ks}omega", f"kdoc{_ks}sigma", f"kprv{_ks}delta"
s, a_csv = upload("서버목록.csv", f"호스트,메모\nweb01,{w_csv} 관리\n".encode("utf-8"), "text/csv", U); check("K4 upload csv", s == 201, (s, a_csv))
s, a_pdf = upload("runbook.pdf", _k_pdf(f"restart {w_pdf} gateway"), "application/pdf", U); check("K4 upload pdf", s == 201, (s, a_pdf))
s, a_doc = upload("보고서.docx", _k_docx(f"원인 {w_docx} 디스크"), "application/vnd.openxmlformats-officedocument.wordprocessingml.document", U); check("K4 upload docx", s == 201, (s, a_doc))
s, a_prv = upload("비밀.txt", f"내용 {w_priv}".encode("utf-8"), "text/plain", U)
s, r = _k_post(U, "첨부만 있는 글", [], content="<p>본문에는 검색어 없음</p>", attachments=[a_csv["id"], a_pdf["id"], a_doc["id"]]); apost = r["id"]
s, r = _k_post(U, "비공개 첨부 글", [], content="<p>x</p>", visibility="PRIVATE", attachments=[a_prv["id"]]); ppost = r["id"]
for word, fname, label in ((w_csv, "서버목록.csv", "csv"), (w_pdf, "runbook.pdf", "pdf"), (w_docx, "보고서.docx", "docx")):
    s, r = call("GET", f"/api/posts?q={word}", tok=O)
    hit = next((p for p in r["content"] if p["id"] == apost), None) if s == 200 else None
    check(f"K4 search finds attachment text ({label}) with matchedAttachment", hit is not None and fname in (hit.get("matchedAttachment") or "") and word in hit["matchedAttachment"], (s, hit))
s, r = call("GET", f"/api/search?q={w_csv}", tok=U); check("K4 /api/search also matches attachments", s == 200 and any(p["id"] == apost and p.get("matchedAttachment") for p in r["content"]), s)
s, r = call("GET", f"/api/posts?q={w_priv}", tok=O); check("K4 private post's attachment not found by others", s == 200 and not any(p["id"] == ppost for p in r["content"]), r and [p["id"] for p in r["content"]])
s, r = call("GET", f"/api/posts?q={w_priv}", tok=U); check("K4 author finds own private attachment", s == 200 and any(p["id"] == ppost for p in r["content"]), s)
s, r = call("GET", "/api/posts?size=5", tok=U); check("K4 list without q has no matchedAttachment", s == 200 and all("matchedAttachment" not in p for p in r["content"]), s)

# ---- 정리: 만든 글(첨부 포함)·템플릿 영구 삭제 ----
for _p in _k_posts:
    call("DELETE", f"/api/posts/{_p}", tok=A); call("DELETE", f"/api/posts/{_p}?purge=true", tok=A)
for _t in _k_templates:
    call("DELETE", f"/api/templates/{_t}", tok=A)
s, r = call("GET", "/api/posts/deleted", tok=A); check("K9 cleanup knowledge smoke posts purged", s == 200 and not any(p["id"] in _k_posts for p in r), [p["id"] for p in r if p["id"] in _k_posts])

if _standalone:
    print(f"\nRESULT {ok} pass / {bad} fail")
