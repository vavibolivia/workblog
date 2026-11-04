# CRUD·동기화 스모크(공수 외). smoke.py 가 exec 한다(B, A, call, check, upload). 만든 것은 끝에서 모두 지우고, 대문은 원래대로 돌린다.
import uuid as _u3
_x = _u3.uuid4().hex[:6]
_made_posts, _made_cats, _made_users = [], [], []


def _purge_post(_p):
    call("DELETE", f"/api/posts/{_p}", tok=A); call("DELETE", f"/api/posts/{_p}?purge=true", tok=A)


# 1) 사용자 영구 삭제: 댓글이 남아 있으면 500 대신 400 + 이유, 글을 지우면 삭제됨
s, _cu = call("POST", "/api/users", {"username": "crd" + _x, "password": "crdpass1234", "displayName": "CRUD" + _x, "role": "USER"}, A)
_made_users.append(_cu["id"])
s, r = call("POST", "/api/auth/login", {"username": "crd" + _x, "password": "crdpass1234"}); _CT = r["token"]
s, _cp = call("POST", "/api/posts", {"title": "CRUD 댓글 대상 " + _x, "content": "<p>x</p>", "contentFormat": "HTML", "tags": [], "status": "PUBLISHED"}, A)
_made_posts.append(_cp["id"])
call("POST", f"/api/posts/{_cp['id']}/comments", {"content": "댓글"}, _CT)
s, r = call("DELETE", f"/api/users/{_cu['id']}?purge=true", tok=A)
check("CRUD purge user with comment -> 400 with reason (not 500)", s == 400 and "댓글" in (r or {}).get("message", ""), (s, r))
_purge_post(_cp["id"]); _made_posts.remove(_cp["id"])
s, r = call("DELETE", f"/api/users/{_cu['id']}?purge=true", tok=A)
check("CRUD purge user after their trace is gone 204", s == 204, (s, r))
if s == 204: _made_users.remove(_cu["id"])

# 2) 태그: 한 글 안 대소문자 중복은 하나로, 관리자 이름 바꾸기·합치기·지우기가 모든 글에 반영
_t1, _t2 = "SmkTag" + _x, "smktag" + _x
s, _tp = call("POST", "/api/posts", {"title": "CRUD 태그 " + _x, "content": "<p>t</p>", "contentFormat": "HTML", "tags": [_t1, _t2, "keep" + _x], "status": "PUBLISHED"}, A)
_made_posts.append(_tp["id"])
check("CRUD tags dedupe ignoring case", s == 201 and _tp["tags"] == [_t1, "keep" + _x], _tp.get("tags"))
s, tl = call("GET", "/api/admin/tags", tok=A)
check("CRUD admin tag list", s == 200 and any(t["name"] == _t1 and t["posts"] == 1 for t in tl), s)
s, r = call("PUT", "/api/admin/tags/rename", {"from": _t2, "to": "keep" + _x}, A)  # 대소문자 무시로 _t1 을 잡아 keep 과 합침
s2, p = call("GET", f"/api/posts/{_tp['id']}", tok=A)
check("CRUD tag merge into existing", s == 200 and r["posts"] == 1 and p["tags"] == ["keep" + _x], (s, r, p.get("tags")))
s, r = call("PUT", "/api/admin/tags/rename", {"from": "keep" + _x, "to": ""}, A)
s2, p = call("GET", f"/api/posts/{_tp['id']}", tok=A)
check("CRUD tag delete everywhere", s == 200 and p["tags"] == [], p.get("tags"))

# 3) 부서 이름 바꾸기: 사용자 부서 + 카테고리 열람 제한이 함께
_d1, _d2 = "부서A" + _x, "부서B" + _x
s, _du = call("POST", "/api/users", {"username": "dep" + _x, "password": "deppass1234", "displayName": "부서" + _x, "department": _d1, "role": "USER"}, A)
_made_users.append(_du["id"])
s, r = call("POST", "/api/auth/login", {"username": "dep" + _x, "password": "deppass1234"}); _DT = r["token"]   # 일반 사용자(이 단계용)
s, r = call("GET", "/api/admin/tags", tok=_DT); check("CRUD tag/dept admin needs admin 403", s == 403, s)
s, _dc = call("POST", "/api/categories", {"name": "CRUD제한" + _x, "parentId": None}, A); _made_cats.append(_dc["id"])
call("PUT", f"/api/categories/{_dc['id']}", {"name": "CRUD제한" + _x, "restrictedDepartments": [_d1, "다른부서"]}, A)
s, r = call("PUT", "/api/admin/departments/rename", {"from": _d1.lower(), "to": _d2}, A)
s2, ul = call("GET", "/api/users", tok=A)
s3, tree = call("GET", "/api/categories", tok=A)
_node = next((n for n in tree if n["id"] == _dc["id"]), {})
check("CRUD dept rename updates user and category restriction", s == 200 and r["users"] == 1 and r["categories"] == 1
      and next(u for u in ul if u["id"] == _du["id"])["department"] == _d2 and _node.get("restrictedDepartments") == [_d2, "다른부서"], (s, r, _node))

# 4) 카테고리 글 수는 보는 사람 기준(비공개 글은 남에게 안 셈), 휴지통 글이 있으면 삭제 안내
s, _pp = call("POST", "/api/posts", {"title": "CRUD 비공개 " + _x, "content": "<p>p</p>", "contentFormat": "HTML", "categoryId": _dc["id"], "tags": [], "status": "PUBLISHED", "visibility": "PRIVATE"}, A)
_made_posts.append(_pp["id"])
s, ta = call("GET", "/api/categories", tok=A); s, tu = call("GET", "/api/categories", tok=_DT)
_ca = next((n for n in ta if n["id"] == _dc["id"]), {}); _cuu = next((n for n in tu if n["id"] == _dc["id"]), {})
check("CRUD category count per viewer (private hidden from others)", _ca.get("totalCount") == 1 and _cuu.get("totalCount") == 0, (_ca.get("totalCount"), _cuu.get("totalCount")))
call("DELETE", f"/api/posts/{_pp['id']}", tok=A)
s, r = call("DELETE", f"/api/categories/{_dc['id']}", tok=A)
check("CRUD category delete blocked by trash says so", s == 400 and "휴지통" in (r or {}).get("message", ""), (s, r))

# 5) 대문: 옛 MM_SHEET 항목은 저장 때 조용히 빠짐, 휴지통 글은 대문 고르기 저장에도 남았다가 복구하면 돌아옴
s, _front0 = call("GET", "/api/front", tok=A)
_orig = [{"kind": i["kind"], "refId": i["refId"]} for i in _front0]
s, _fp = call("POST", "/api/posts", {"title": "CRUD 대문 " + _x, "content": "<p>f</p>", "contentFormat": "HTML", "tags": [], "status": "PUBLISHED"}, A)
_made_posts.append(_fp["id"])
try:
    s, r = call("PUT", "/api/front", {"items": _orig + [{"kind": "POST", "refId": _fp["id"]}, {"kind": "MM_SHEET", "refId": 999999}]}, A)
    check("CRUD front save ignores legacy MM_SHEET", s == 200 and any(i["refId"] == _fp["id"] for i in r) and not any(i["kind"] == "MM_SHEET" for i in r), (s, r))
    call("DELETE", f"/api/posts/{_fp['id']}", tok=A)
    s, cur = call("GET", "/api/front", tok=A)
    s, r = call("PUT", "/api/front", {"items": [{"kind": i["kind"], "refId": i["refId"]} for i in cur]}, A)   # 화면이 보이는 목록만 다시 보냄
    call("POST", f"/api/posts/{_fp['id']}/undelete", tok=A)
    s, r = call("GET", "/api/front", tok=A)
    check("CRUD trashed front post comes back after restore", any(i["refId"] == _fp["id"] for i in r), r)
finally:
    call("PUT", "/api/front", {"items": _orig}, A)
s, r = call("GET", "/api/front", tok=A); check("CRUD front restored to original", [(i["kind"], i["refId"]) for i in r] == [(i["kind"], i["refId"]) for i in _orig], r)

# 6) 같은 사진을 쓰는 다른 글이 있으면, 한 글을 영구 삭제해도 사진은 남음
_png = bytes.fromhex("89504E470D0A1A0A0000000D4948445200000001000000010806000000" + "1F15C4890000000D49444154789C6360000000000500010D0A2DB40000000049454E44AE426082")
s, _img = upload("공유" + _x + ".png", _png, "image/png", A)
_html = '<p><img src="' + _img["url"] + '"></p>'
s, _pa = call("POST", "/api/posts", {"title": "CRUD 사진 A " + _x, "content": _html, "contentFormat": "HTML", "attachmentIds": [_img["id"]], "tags": [], "status": "PUBLISHED"}, A)
s, _pb = call("POST", "/api/posts", {"title": "CRUD 사진 B " + _x, "content": _html, "contentFormat": "HTML", "tags": [], "status": "PUBLISHED"}, A)
_made_posts += [_pb["id"]]
_purge_post(_pa["id"])
try:
    with urllib.request.urlopen(urllib.request.Request(B + _img["url"], headers={"Authorization": "Bearer " + A})) as _r:
        _st = _r.status
except urllib.error.HTTPError as _e:
    _st = _e.code
check("CRUD shared image survives purge of one post", _st == 200, _st)

# 정리
for _p in _made_posts: _purge_post(_p)
for _c in _made_cats: call("DELETE", f"/api/categories/{_c}", tok=A)
for _uid in _made_users: call("DELETE", f"/api/users/{_uid}?purge=true", tok=A)
s, r = call("GET", "/api/admin/tags", tok=A); check("CRUD cleanup tags", not any(_x in t["name"] for t in r), [t for t in r if _x in t["name"]])
