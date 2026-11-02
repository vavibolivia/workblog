# 데이터 이전(관리자 > 데이터 이전) 서버 스모크. smoke.py 가 exec 한다(B, A, call, check 를 그대로 쓴다).
# 운영 데이터는 바꾸지 않는다: 임시 인스턴스 ID 로 만든 작은 합성 덤프만 가져오고, 만든 글·사용자·카테고리·매핑을 모두 지운다.
# 내보내기는 기준 시각으로 기록하지 않는 작은 업데이트 덤프(markBaseline=false)만 받는다.
import io as _io, hashlib as _hl, zipfile as _zf, uuid as _uuid, datetime as _dt, re, time

def _raw(m, p, data=None, tok=None, ctype="application/zip"):
    req = urllib.request.Request(B + p, method=m, data=data)
    if data is not None: req.add_header("Content-Type", ctype)
    if tok: req.add_header("Authorization", "Bearer " + tok)
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            return r.status, r.read(), r.headers
    except urllib.error.HTTPError as e:
        return e.code, e.read(), e.headers

def _json(b):
    try: return json.loads(b.decode("utf-8")) if b else None
    except Exception: return None

_sfx = str(int(time.time()))[-6:]
_now = _dt.datetime.now(_dt.timezone.utc).replace(microsecond=0)
_iso = lambda d: d.isoformat().replace("+00:00", "Z")
_png = bytes.fromhex("89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000d49444154789c636000010000050001" + "0d0a2db40000000049454e44ae426082")
_F = str(_uuid.uuid4())           # 시험용 원본 인스턴스
_SPUB = str(_uuid.uuid4())        # 원본 첨부 public id
_UNAME = "dmsmoke" + _sfx
_TITLE = "DM스모크" + _sfx

def _dump(inst, version, title, kind="FULL", extra=None):
    """합성 덤프 zip(bytes)."""
    rev = lambda v, t: {"id": 1000 + v, "postId": 100, "version": v, "title": t, "content": "<p>본문 v%d <img src=\"/api/files/%s\"></p>" % (v, _SPUB),
                        "contentFormat": "HTML", "summary": None, "category": "DM > 하위", "categoryId": 11, "tags": "dm", "status": "PUBLISHED",
                        "visibility": "PUBLIC", "changeType": "CREATE" if v == 1 else "UPDATE", "changeNote": None if v == 1 else "원본에서 수정", "editedBy": 1,
                        "createdAt": _iso(_now)}
    data = {
        "data/users.json": [
            {"id": 1, "username": _UNAME, "password": "$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5BWX4Z3JX4P2Z5vYl5xVn5G6r8XvC", "displayName": "이전 시험", "email": None,
             "department": "시험", "role": "USER", "active": True, "createdAt": _iso(_now), "updatedAt": _iso(_now)},
            {"id": 2, "username": "admin", "password": "$2a$10$ignoredignoredignoredignoredignoredignoredignoredigno", "displayName": "원본 관리자", "email": None,
             "department": None, "role": "ADMIN", "active": True, "createdAt": _iso(_now), "updatedAt": _iso(_now)}],
        "data/categories.json": [
            {"id": 10, "name": "DM" + _sfx, "parentId": None, "path": ["DM" + _sfx], "sortOrder": 99, "restrictedDepartments": "", "createdAt": _iso(_now)},
            {"id": 11, "name": "하위", "parentId": 10, "path": ["DM" + _sfx, "하위"], "sortOrder": 1, "restrictedDepartments": "", "createdAt": _iso(_now)}],
        "data/templates.json": [],
        "data/posts.json": [{"id": 100, "title": title, "content": rev(version, title)["content"], "contentFormat": "HTML", "summary": None, "categoryId": 11,
                             "tags": "dm", "status": "PUBLISHED", "visibility": "PUBLIC", "pinned": False, "authorId": 1, "version": version, "viewCount": 3,
                             "createdAt": _iso(_now), "updatedAt": _iso(_now), "publishedAt": _iso(_now), "deletedAt": None, "reviewedAt": None}],
        "data/revisions.json": [rev(v, title) for v in range(1, version + 1)],
        "data/comments.json": [{"id": 500, "postId": 100, "parentId": None, "authorId": 2, "content": "확인했습니다", "deletedAt": None,
                                "createdAt": _iso(_now), "updatedAt": _iso(_now)}],
        "data/likes.json": [{"postId": 100, "userId": 1, "createdAt": _iso(_now)}],
        "data/attachments.json": [{"id": 700, "publicId": _SPUB, "postId": 100, "uploaderId": 1, "originalName": "dm.png", "contentType": "image/png",
                                   "sizeBytes": len(_png), "kind": "IMAGE", "createdAt": _iso(_now), "sha256": _hl.sha256(_png).hexdigest(), "file": "files/" + _SPUB}],
    }
    entries = {k: json.dumps(v, ensure_ascii=False).encode("utf-8") for k, v in data.items()}
    entries["files/" + _SPUB] = _png
    if extra: entries.update(extra)
    files = [{"path": k, "sha256": _hl.sha256(v).hexdigest(), "size": len(v)} for k, v in entries.items()]
    man = {"format": "workblog-dump", "formatVersion": 1, "appVersion": "smoke", "sourceInstanceId": inst,
           "dumpName": "workblog-dump-%s-smoke-v%d.zip" % (inst[:8], version), "kind": kind, "exportedAt": _iso(_now),
           "since": None if kind == "FULL" else _iso(_now), "counts": {}, "files": files}
    buf = _io.BytesIO()
    with _zf.ZipFile(buf, "w", _zf.ZIP_DEFLATED) as z:
        z.writestr("manifest.json", json.dumps(man))
        for k, v in entries.items(): z.writestr(k, v)
    return buf.getvalue()

def _import(zbytes, apply=True):
    s, b, _ = _raw("POST", "/api/admin/data/import", zbytes, A)
    up = _json(b)
    if s != 200 or not apply:
        if s == 200: _raw("DELETE", "/api/admin/data/import/" + up["uploadId"], tok=A)
        return s, up
    s2, b2, _ = _raw("POST", "/api/admin/data/import/" + up["uploadId"] + "/apply", b"", A, "application/json")
    return s2, {"upload": up, "report": _json(b2)}

def _c(rep, ent, field):
    return ((rep or {}).get("counts") or {}).get(ent, {}).get(field, 0)

# ---- 권한 ----
s, r = call("GET", "/api/admin/data/info", tok=A); check("DM info admin 200 + instanceId", s == 200 and len(r.get("instanceId", "")) == 36, (s, r)); _SELF = r["instanceId"]
_base_before = r.get("lastExportAt")
_du = "dmuser" + _sfx
call("POST", "/api/users", {"username": _du, "password": "pass1234x", "displayName": "이전권한", "role": "USER"}, A)
s, r = call("POST", "/api/auth/login", {"username": _du, "password": "pass1234x"}); _DT = r["token"] if s == 200 else None
s, r = call("GET", "/api/admin/data/info", tok=_DT); check("DM non-admin 403", s == 403, s)
s, r = call("POST", "/api/tokens", {"name": "dm"}, _DT); _WBT = (r or {}).get("token")
s, r = call("GET", "/api/admin/data/info", tok=_WBT); check("DM API token 403", s == 403 and "API 토큰" in (r or {}).get("message", ""), (s, r))
s, b, h = _raw("GET", "/api/admin/data/export/download?ticket=bogus"); check("DM download without valid ticket 403", s == 403, s)

# ---- 내보내기(작은 업데이트 덤프, 기준 기록 안 함) ----
_since = urllib.parse.quote(_iso(_now - _dt.timedelta(minutes=1)))
s, b, h = _raw("GET", "/api/admin/data/export?since=" + _since + "&markBaseline=false", tok=A)
_ok = s == 200 and b[:2] == b"PK"
man = None
if _ok:
    with _zf.ZipFile(_io.BytesIO(b)) as z: man = json.loads(z.read("manifest.json"))
check("DM export update dump zip with manifest", _ok and man["sourceInstanceId"] == _SELF and man["kind"] == "UPDATE" and "-update.zip" in (h.get("Content-Disposition") or ""), (s, man))
check("DM export manifest sha list covers data files", man is not None and any(f["path"] == "data/posts.json" for f in man["files"]), man and [f["path"] for f in man["files"]])
s, r = call("GET", "/api/admin/data/info", tok=A); check("DM markBaseline=false keeps last baseline", s == 200 and r.get("lastExportAt") == _base_before, (r.get("lastExportAt"), _base_before))
s, r = call("POST", "/api/admin/data/export/ticket", {"full": False, "since": _iso(_now), "markBaseline": False}, A)
_tk = (r or {}).get("ticket")
s, b, h = _raw("GET", "/api/admin/data/export/download?ticket=" + str(_tk)); check("DM ticket download 200 zip", s == 200 and b[:2] == b"PK", s)
s, b, h = _raw("GET", "/api/admin/data/export/download?ticket=" + str(_tk)); check("DM ticket is one-time 403", s == 403, s)

# ---- 거부: 자기 덤프 · 경로 탈출 · zip 아님 ----
s, r = _import(_dump(_SELF, 1, _TITLE), apply=False); check("DM own dump rejected 400", s == 400 and "자기 덤프" in (r or {}).get("message", ""), (s, r))
_slip = _io.BytesIO()
with _zf.ZipFile(_slip, "w") as z: z.writestr("manifest.json", "{}"); z.writestr("../../evil.sh", "x")
s, r = _import(_slip.getvalue(), apply=False); check("DM zip slip rejected 400", s == 400 and "경로" in (r or {}).get("message", ""), (s, r))
s, r = _import(b"not a zip", apply=False); check("DM non-zip rejected 400", s == 400, s)

# ---- 미리 보기는 아무것도 바꾸지 않는다 ----
s, r = _import(_dump(_F, 1, _TITLE), apply=False)
p = (r or {}).get("preview") or {}
check("DM preview counts (post 1 · user 1 new · admin kept · category 2)", s == 200 and _c(p, "posts", "created") == 1 and _c(p, "users", "created") == 1
      and _c(p, "users", "skipped") == 1 and _c(p, "categories", "created") == 2 and _c(p, "attachments", "created") == 1, (s, p.get("counts")))
s, r = call("GET", "/api/search?q=" + _TITLE, tok=A); check("DM preview created nothing", s == 200 and not r["content"], r)

# ---- 적용 ----
s, r = _import(_dump(_F, 1, _TITLE)); rep = (r or {}).get("report") or {}
check("DM apply OK", s == 200 and rep.get("status") == "OK" and _c(rep, "posts", "created") == 1 and _c(rep, "comments", "created") == 1 and _c(rep, "likes", "created") == 1, (s, rep.get("counts"), rep.get("error")))
s, r = call("GET", "/api/search?q=" + _TITLE, tok=A); _pid = r["content"][0]["id"] if s == 200 and r["content"] else None
check("DM imported post found", _pid is not None, r)
s, r = call("GET", f"/api/posts/{_pid}?view=false", tok=A)
_m = re.search(r"/api/files/([0-9a-f-]{36})", (r or {}).get("content", "")) if s == 200 else None
check("DM attachment url rewritten to new id", _m is not None and _m.group(1) != _SPUB and _SPUB not in r["content"], (r or {}).get("content"))
check("DM imported author/category/version", s == 200 and r["author"]["username"] == _UNAME and r["category"]["path"] == ["DM" + _sfx, "하위"] and r["version"] == 1, r and (r.get("author"), r.get("category"), r.get("version")))
s, b, h = _raw("GET", "/api/files/" + (_m.group(1) if _m else "x")); check("DM imported file served", s == 200 and b == _png, s)
_aid = call("GET", "/api/auth/me", tok=A)[1]["id"]
s, r = call("GET", f"/api/posts/{_pid}/comments", tok=A); check("DM comment imported (author mapped to admin)", s == 200 and len(r) == 1 and r[0]["author"]["id"] == _aid, r)

# ---- 멱등: 같은 덤프 다시 ----
s, r = _import(_dump(_F, 1, _TITLE)); rep = (r or {}).get("report") or {}
check("DM same dump again creates nothing", s == 200 and rep.get("status") == "OK" and all(v["created"] == 0 and v["updated"] == 0 for v in rep["counts"].values()), rep.get("counts"))

# ---- 업데이트 덤프: 새 버전 + change note ----
s, r = _import(_dump(_F, 2, _TITLE + " 개정", kind="UPDATE")); rep = (r or {}).get("report") or {}
check("DM update dump updates post", s == 200 and _c(rep, "posts", "updated") == 1, (s, rep.get("counts")))
s, r = call("GET", f"/api/posts/{_pid}/revisions", tok=A)
check("DM update kept as v2 with import note", s == 200 and r[0]["version"] == 2 and (r[0]["changeNote"] or "").startswith("데이터 가져오기: "), r and r[0])

# ---- 충돌: 대상에서 고친 글은 덮어쓰지 않는다 ----
s, r = call("PATCH", f"/api/posts/{_pid}", {"title": _TITLE + " 대상수정"}, A)
s, r = _import(_dump(_F, 3, _TITLE + " 원본v3", kind="UPDATE")); rep = (r or {}).get("report") or {}
check("DM edited-on-target reported as conflict", s == 200 and _c(rep, "posts", "conflict") == 1 and any(i["action"] == "CONFLICT" for i in rep.get("items", [])), (s, rep.get("counts")))
s, r = call("GET", f"/api/posts/{_pid}?view=false", tok=A); check("DM conflict keeps target content", s == 200 and r["title"] == _TITLE + " 대상수정", r and r.get("title"))
s, r = call("GET", "/api/admin/data/info", tok=A)
check("DM history records preview/apply", s == 200 and any(t["action"] == "APPLY" and t["sourceInstance"] == _F for t in r["history"]), r and [t["action"] for t in r["history"][:5]])

# ---- 정리 ----
call("DELETE", f"/api/posts/{_pid}", tok=A); s, r = call("DELETE", f"/api/posts/{_pid}?purge=true", tok=A); check("DM cleanup post purged", s == 204, s)
s, r = call("GET", "/api/users", tok=A)
for _u in [u for u in (r or []) if u["username"] in (_UNAME, _du)]:
    call("DELETE", f"/api/users/{_u['id']}?purge=true", tok=A)
s, r = call("GET", "/api/users", tok=A); check("DM cleanup users purged", s == 200 and not any(u["username"] in (_UNAME, _du) for u in r), [u["username"] for u in r if u["username"] in (_UNAME, _du)])
s, r = call("GET", "/api/categories", tok=A)
_dmcat = next((n for n in (r or []) if n["name"] == "DM" + _sfx), None)
if _dmcat:
    for _ch in _dmcat["children"]: call("DELETE", f"/api/categories/{_ch['id']}", tok=A)
    call("DELETE", f"/api/categories/{_dmcat['id']}", tok=A)
s, r = call("GET", "/api/categories", tok=A); check("DM cleanup categories deleted", s == 200 and not any(n["name"] == "DM" + _sfx for n in r), s)
s, r = call("DELETE", f"/api/admin/data/sources/{_F}", tok=A); check("DM cleanup mapping deleted", s == 200 and r["deleted"] >= 5, (s, r))
s, r = call("GET", "/api/admin/data/info", tok=A); check("DM cleanup import records deleted", s == 200 and not any(t["sourceInstance"] == _F for t in r["history"]), s)
