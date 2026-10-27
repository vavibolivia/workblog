# 협업·보안 강화 스모크(2026-10-27): 알림 · 동시 편집 충돌(baseVersion) · 열람 감사 로그 · 카테고리 열람 제한 · 비밀번호 정책 · 로그인 잠금.
# smoke.py 안에서 exec 된다(call/check, 토큰 A=관리자 · U=작성자(부서 IT전략실) · O=다른 사용자 재사용). 단독 실행은 지원하지 않는다.
# 만든 글·카테고리·계정은 끝에서 모두 영구 삭제한다.
import time as _ct

_cs = str(int(_ct.time()))[-6:]
_c1n, _c2n, _c3n = "colA" + _cs, "colB" + _cs, "colC" + _cs
_s, _r = call("GET", "/api/auth/me", tok=U); _uname_u = _r["username"]

# ---- 비밀번호 정책 ----
_s, _r = call("GET", "/api/auth/options"); check("PW options passwordMinLength 8", _s == 200 and _r.get("passwordMinLength") == 8, _r)
_s, _r = call("POST", "/api/users", {"username": "colW" + _cs, "password": "ab12", "displayName": "약한"}, A); check("PW admin create too short 400", _s == 400 and "8자" in _r["message"], (_s, _r))
_s, _r = call("POST", "/api/users", {"username": "colW" + _cs, "password": "abcdefgh", "displayName": "약한"}, A); check("PW admin create letters only 400", _s == 400 and "영문과 숫자" in _r["message"], (_s, _r))
_s, _r = call("POST", "/api/auth/signup", {"username": "colS" + _cs, "password": "12345678", "displayName": "약한"}); check("PW signup digits only rejected", _s in (400, 403), (_s, _r))
_ids = {}
for _n, _d in ((_c1n, "인프라팀"), (_c2n, "기획팀"), (_c3n, None)):
    _s, _r = call("POST", "/api/users", {"username": _n, "password": "pass1234", "displayName": "협업" + _n[-7:], "department": _d, "role": "USER"}, A)
    check(f"PW admin create {_n} with pass1234", _s == 201, (_s, _r)); _ids[_n] = _r["id"] if _s == 201 else None
_s, _r = call("POST", "/api/auth/login", {"username": _c1n, "password": "pass1234"}); C1 = _r["token"]
_s, _r = call("POST", "/api/auth/login", {"username": _c2n, "password": "pass1234"}); C2 = _r["token"]
_s, _r = call("PUT", "/api/users/me/password", {"currentPassword": "pass1234", "newPassword": "short"}, C2); check("PW change weak 400", _s == 400, (_s, _r))
_s, _r = call("PUT", "/api/users/me/password", {"currentPassword": "pass1234", "newPassword": "pass5678"}, C2); check("PW change strong 204", _s == 204, (_s, _r))
_s, _r = call("PUT", "/api/users/me/password", {"currentPassword": "pass5678", "newPassword": "pass1234"}, C2); check("PW change back 204", _s == 204, (_s, _r))
_s, _r = call("PUT", f"/api/users/{_ids[_c2n]}", {"displayName": "협업B", "department": "기획팀", "newPassword": "onlyletters"}, A); check("PW admin reset weak 400", _s == 400, (_s, _r))

# ---- 로그인 잠금(5회 연속 실패 → 10분, 429) ----
for _i in range(4):
    _s, _r = call("POST", "/api/auth/login", {"username": _c3n, "password": "wrong-" + str(_i)})
check("LOCK 4 failures still 401", _s == 401, _s)
_s, _r = call("POST", "/api/auth/login", {"username": _c3n, "password": "wrong-5"}); check("LOCK 5th failure 429", _s == 429 and "분" in (_r or {}).get("message", ""), (_s, _r))
_s, _r = call("POST", "/api/auth/login", {"username": _c3n.upper(), "password": "pass1234"}); check("LOCK correct password while locked 429", _s == 429, (_s, _r))
_s, _r = call("POST", "/api/auth/login", {"username": _c1n, "password": "pass1234"}); check("LOCK other user unaffected", _s == 200, _s)

# ---- 알림 ----
def _unread(tok):
    s, r = call("GET", "/api/notifications/unread-count", tok=tok); return r["count"] if s == 200 else -1
def _notifs(tok):
    s, r = call("GET", "/api/notifications?size=50", tok=tok); return r["content"] if s == 200 else []
_n1, _nu = _unread(C1), _unread(U)
_s, _r = call("POST", "/api/posts", {"title": f"협업 스모크 {_cs}", "content": f"본문에서 @{_c1n} 님을 부릅니다.", "contentFormat": "MARKDOWN", "tags": [], "status": "PUBLISHED"}, U)
check("NOTI create post with mention", _s == 201, (_s, _r)); _cp = _r["id"]; _cv = _r["version"]
check("NOTI post mention → recipient unread +1", _unread(C1) == _n1 + 1, (_n1, _unread(C1)))
_l = _notifs(C1); check("NOTI post mention item (MENTION, no commentId, title)", any(n["postId"] == _cp and n["type"] == "MENTION" and n["commentId"] is None and n["postTitle"] for n in _l), _l[:2])
call("PUT", f"/api/posts/{_cp}", {"title": f"협업 스모크 {_cs}", "content": f"본문에서 @{_c1n} 님을 부릅니다. 수정", "contentFormat": "MARKDOWN", "tags": [], "status": "PUBLISHED"}, U)
check("NOTI post mention not repeated on edit", sum(1 for n in _notifs(C1) if n["postId"] == _cp and n["commentId"] is None) == 1, _notifs(C1)[:3])
_s, _r = call("POST", f"/api/posts/{_cp}/comments", {"content": f"확인했습니다 @{_c1n}"}, C2); _cm = _r["id"]
check("NOTI comment → post author COMMENT", any(n["postId"] == _cp and n["type"] == "COMMENT" and n["commentId"] == _cm for n in _notifs(U)), _notifs(U)[:2])
check("NOTI comment mention → MENTION with commentId", any(n["commentId"] == _cm and n["type"] == "MENTION" for n in _notifs(C1)), _notifs(C1)[:2])
_nu2 = _unread(U)
_s, _r = call("POST", f"/api/posts/{_cp}/comments", {"content": "답글입니다", "parentId": _cm}, U); _rp = _r["id"]
check("NOTI reply → comment author REPLY", any(n["commentId"] == _rp and n["type"] == "REPLY" for n in _notifs(C2)), _notifs(C2)[:2])
check("NOTI no notification to self", _unread(U) == _nu2, (_nu2, _unread(U)))
_s, _r = call("POST", "/api/posts", {"title": "비공개 멘션", "content": f"@{_c1n} 비밀", "contentFormat": "MARKDOWN", "tags": [], "status": "PUBLISHED", "visibility": "PRIVATE"}, U); _cpp = _r["id"]
check("NOTI no mention notification for private post", not any(n["postId"] == _cpp for n in _notifs(C1)), _notifs(C1)[:2])
_mine = _notifs(C1)[0]
_s, _r = call("POST", f"/api/notifications/{_mine['id']}/read", tok=C2); check("NOTI cannot read others' notification 404", _s == 404, _s)
_s, _r = call("POST", f"/api/notifications/{_mine['id']}/read", tok=C1); check("NOTI mark one read", _s == 200 and _r["read"] is True, (_s, _r))
_s, _r = call("GET", "/api/notifications?unreadOnly=true", tok=C1); check("NOTI unreadOnly excludes read", _s == 200 and not any(n["id"] == _mine["id"] for n in _r["content"]), _r)
_s, _r = call("POST", "/api/notifications/read-all", tok=C1); check("NOTI read-all → 0", _s == 200 and _unread(C1) == 0, (_s, _unread(C1)))

# ---- 동시 편집 충돌 ----
_s, _r = call("GET", f"/api/posts/{_cp}?view=false", tok=U); _v = _r["version"]
_body = {"title": f"협업 스모크 {_cs}", "content": "A 의 수정", "contentFormat": "MARKDOWN", "tags": [], "status": "PUBLISHED", "baseVersion": _v}
_s, _r = call("PUT", f"/api/posts/{_cp}", _body, U); check("CONFLICT matching baseVersion 200", _s == 200 and _r["version"] == _v + 1, (_s, _r))
_body["content"] = "B 의 수정(오래된 버전)"
_s, _r = call("PUT", f"/api/posts/{_cp}", _body, A); check("CONFLICT stale baseVersion 409 with message", _s == 409 and f"v{_v + 1}" in _r["message"], (_s, _r))
_s, _r = call("PATCH", f"/api/posts/{_cp}", {"title": "패치", "baseVersion": _v}, U); check("CONFLICT PATCH stale baseVersion 409", _s == 409, (_s, _r))
_body.pop("baseVersion")
_s, _r = call("PUT", f"/api/posts/{_cp}", _body, A); check("CONFLICT without baseVersion overwrites (compat)", _s == 200 and _r["version"] == _v + 2, (_s, _r))

# ---- 열람 감사 로그 ----
call("GET", f"/api/posts/{_cp}", tok=C1); call("GET", f"/api/posts/{_cp}?view=false", tok=C1)
_s, _r = call("GET", f"/api/posts/{_cp}/viewers", tok=U)
_row = [v for v in (_r or {}).get("viewers", []) if v["user"]["username"] == _c1n]
check("AUDIT author sees viewer (10-min dedupe → 1)", _s == 200 and len(_row) == 1 and _row[0]["count"] == 1, (_s, _r))
check("AUDIT viewers exclude the author", _s == 200 and not any(v["user"]["username"] == _uname_u for v in _r["viewers"]), _r)
_s, _r = call("GET", f"/api/posts/{_cp}/viewers", tok=C2); check("AUDIT non-author viewers 403", _s == 403, _s)
_s, _r = call("GET", f"/api/posts/{_cp}/viewers", tok=A); check("AUDIT admin viewers 200", _s == 200, _s)
_s, _r = call("POST", "/api/tokens", {"name": "collab smoke"}, C2); _wbt = _r["token"] if _s in (200, 201) else None
_s, _r = call("GET", f"/api/posts/{_cp}", tok=_wbt); check("AUDIT read with API token", _s == 200, _s)
_s, _r = call("GET", f"/api/admin/audit/views?postId={_cp}&size=50", tok=A)
check("AUDIT admin log has WEB and API_TOKEN entries", _s == 200 and any(e["via"] == "WEB" and e["user"]["username"] == _c1n for e in _r["content"])
      and any(e["via"] == "API_TOKEN" and e["user"]["username"] == _c2n for e in _r["content"]), (_s, _r))
_s, _r = call("GET", f"/api/admin/audit/views?userId={_ids[_c1n]}&from=2000-01-01&to=2999-12-31", tok=A); check("AUDIT admin filter by user/date", _s == 200 and all(e["user"]["id"] == _ids[_c1n] for e in _r["content"]) and _r["totalElements"] >= 1, (_s, _r))
_s, _r = call("GET", "/api/admin/audit/views?from=2026-10-27&to=2026-10-01", tok=A); check("AUDIT from after to 400", _s == 400, _s)
_s, _r = call("GET", "/api/admin/audit/views", tok=C2); check("AUDIT non-admin 403", _s == 403, _s)
_s, _r = call("GET", "/api/admin/audit/views", tok=_wbt); check("AUDIT API token blocked 403", _s == 403, _s)

# ---- 카테고리 열람 제한 ----
_s, _r = call("POST", "/api/categories", {"name": f"R{_cs}", "parentId": None}, A); _rc = _r["id"]
_s, _r = call("POST", "/api/categories", {"name": "하위", "parentId": _rc}, A); _rcc = _r["id"]
_s, _r = call("PUT", f"/api/categories/{_rc}", {"name": f"R{_cs}", "restrictedDepartments": [" 인프라팀 ", "인프라팀", ""]}, A)
check("RESTRICT set departments (normalized)", _s == 200 and _r["restrictedDepartments"] == ["인프라팀"], (_s, _r))
_s, _r = call("PUT", f"/api/categories/{_rc}", {"name": f"R{_cs}", "restrictedDepartments": ["x"]}, U); check("RESTRICT non-admin cannot set 403", _s == 403, _s)
call("PUT", f"/api/categories/{_rc}", {"name": f"R{_cs}"}, A)  # restrictedDepartments 생략 = 유지
_s, _r = call("GET", "/api/categories", tok=U); _node = [n for n in _r if n["id"] == _rc]
check("RESTRICT omitted field keeps restriction", _node and _node[0]["restrictedDepartments"] == ["인프라팀"], _node)
_word = "rstword" + _cs
_s, _r = call("POST", "/api/posts", {"title": f"서버 접속 정보 {_cs}", "content": f"{_word} 민감 내용 @{_c2n} @{_c1n}", "contentFormat": "MARKDOWN", "tags": ["rst" + _cs], "status": "PUBLISHED", "categoryId": _rcc}, U)
check("RESTRICT author posts into child category", _s == 201, (_s, _r)); _rp_id = _r["id"]
for _tok, _who, _exp in ((C1, "member", 200), (C2, "other dept", 404), (O, "no dept", 404), (A, "admin", 200), (U, "author", 200)):
    _s, _r = call("GET", f"/api/posts/{_rp_id}", tok=_tok); check(f"RESTRICT detail {_who} {_exp}", _s == _exp, _s)
_s, _r = call("GET", f"/api/search?q={_word}", tok=C2); check("RESTRICT search hides from other dept", _s == 200 and not any(p["id"] == _rp_id for p in _r["content"]), _r)
_s, _r = call("GET", f"/api/search?q={_word}", tok=C1); check("RESTRICT search shows to member", _s == 200 and any(p["id"] == _rp_id for p in _r["content"]), _r)
_s, _r = call("GET", f"/api/posts?categoryId={_rc}", tok=C2); check("RESTRICT category list hides from other dept", _s == 200 and not any(p["id"] == _rp_id for p in _r["content"]), _r)
_s, _r = call("GET", f"/api/posts?categoryId={_rc}", tok=A); check("RESTRICT category list shows to admin", _s == 200 and any(p["id"] == _rp_id for p in _r["content"]), _r)
_s, _r = call("GET", "/api/history?size=50", tok=C2); check("RESTRICT history hides from other dept", _s == 200 and not any(a["postId"] == _rp_id for a in _r["content"]), "leaked")
_s, _r = call("GET", "/api/history?size=50", tok=C1); check("RESTRICT history shows to member", _s == 200 and any(a["postId"] == _rp_id for a in _r["content"]), "missing")
_s, _r = call("GET", f"/api/posts/{_rp_id}/comments", tok=C2); check("RESTRICT comments 404 for other dept", _s == 404, _s)
_s, _r = call("GET", f"/api/posts/{_rp_id}/revisions", tok=C2); check("RESTRICT revisions 404 for other dept", _s == 404, _s)
_s, _r = call("GET", "/api/tags?limit=200", tok=C1); check("RESTRICT tag cloud excludes restricted posts", _s == 200 and not any(t["name"] == "rst" + _cs for t in _r), _r)
_s, _r = call("GET", "/api/stats?days=30", tok=C2); check("RESTRICT stats top lists exclude for other dept", _s == 200 and not any(p["id"] == _rp_id for p in _r["mostViewed"] + _r["mostLiked"]), "leaked")
check("RESTRICT no mention notification to non-member", not any(n["postId"] == _rp_id for n in _notifs(C2)), _notifs(C2)[:2])
check("RESTRICT mention notification to member", any(n["postId"] == _rp_id for n in _notifs(C1)), _notifs(C1)[:2])
_s, _r = call("PUT", f"/api/categories/{_rc}", {"name": f"R{_cs}", "restrictedDepartments": []}, A); check("RESTRICT clear with empty list", _s == 200 and _r["restrictedDepartments"] == [], (_s, _r))
_s, _r = call("GET", f"/api/posts/{_rp_id}", tok=C2); check("RESTRICT cleared → other dept can read", _s == 200, _s)

# ---- 정리: 이 스크립트가 만든 글·카테고리·계정 영구 삭제 ----
for _p in (_cp, _cpp, _rp_id):
    call("DELETE", f"/api/posts/{_p}", tok=A); call("DELETE", f"/api/posts/{_p}?purge=true", tok=A)
for _c in (_rcc, _rc):
    call("DELETE", f"/api/categories/{_c}", tok=A)
for _n in (_c1n, _c2n, _c3n):
    if _ids.get(_n): call("DELETE", f"/api/users/{_ids[_n]}?purge=true", tok=A)
_s, _r = call("GET", "/api/users", tok=A); check("CLEANUP collab users purged", _s == 200 and not any(u["username"] in (_c1n, _c2n, _c3n) for u in _r), [u["username"] for u in _r if u["username"].startswith("col")])
_s, _r = call("GET", "/api/categories", tok=A); check("CLEANUP collab categories removed", _s == 200 and not any(n["name"] == f"R{_cs}" for n in _r), "left")
_s, _r = call("GET", "/api/posts/deleted", tok=A); check("CLEANUP collab posts purged", _s == 200 and not any(p["id"] in (_cp, _cpp, _rp_id) for p in _r), "left")
