# 댓글·답글 스모크 테스트. scripts/smoke.py 의 call()/check() 와 토큰(A=관리자, U=작성자, O=다른 사용자), pid(발행 글), did(U 의 임시 저장 글)를 그대로 쓴다.
#   smoke.py 안에서 실행:  exec(open("scripts/smoke_comments.py", encoding="utf-8").read())
#   단독 실행:            python scripts/smoke_comments.py http://localhost:8080 <admin_pw>
if "call" not in globals():
    import json, sys, time, urllib.request, urllib.parse
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
    ok = 0; bad = 0
    def check(name, cond, info=""):
        global ok, bad
        cond = bool(cond)
        print(("PASS " if cond else "FAIL ") + name + ("" if cond else f"  -> {info}")); ok += cond; bad += (not cond)
    s, r = call("POST", "/api/auth/login", {"username": "admin", "password": ADMIN_PW}); A = r["token"]
    uname = "cmt" + str(int(time.time()))[-6:]
    call("POST", "/api/users", {"username": uname, "password": "pass1234", "displayName": "댓글작성자", "department": "IT전략실", "role": "USER"}, A)
    call("POST", "/api/users", {"username": "o" + uname, "password": "pass1234", "displayName": "다른사람", "role": "USER"}, A)
    s, r = call("POST", "/api/auth/login", {"username": uname, "password": "pass1234"}); U = r["token"]
    s, r = call("POST", "/api/auth/login", {"username": "o" + uname, "password": "pass1234"}); O = r["token"]
    s, r = call("POST", "/api/posts", {"title": "댓글 테스트 글", "content": "본문", "tags": [], "status": "PUBLISHED", "contentFormat": "MARKDOWN"}, U); pid = r["id"]
    s, r = call("POST", "/api/posts", {"title": "댓글 테스트 초안", "content": "비공개", "tags": [], "status": "DRAFT", "contentFormat": "MARKDOWN"}, U); did = r["id"]
    _standalone = True
else:
    _standalone = False

# ---- 댓글·답글 ----
s, r = call("POST", f"/api/posts/{pid}/comments", {"content": "좋은 글입니다.\n둘째 줄"}, O)
check("C1 create comment 201", s == 201 and r["parentId"] is None and r["content"] == "좋은 글입니다.\n둘째 줄" and r["editable"] is True, (s, r)); cid = r["id"]
s, r = call("POST", f"/api/posts/{pid}/comments", {"content": "감사합니다.", "parentId": cid}, U)
check("C2 reply attaches to comment", s == 201 and r["parentId"] == cid, (s, r)); rid = r["id"]
s, r = call("POST", f"/api/posts/{pid}/comments", {"content": "답글의 답글", "parentId": rid}, O)
check("C3 reply-to-reply flattens to root comment", s == 201 and r["parentId"] == cid, (s, r)); rid2 = r["id"]
s, r = call("POST", f"/api/posts/{pid}/comments", {"content": ""}, O); check("C4 blank content 400", s == 400, s)
s, r = call("POST", f"/api/posts/{pid}/comments", {"content": "x" * 2001}, O); check("C4 over 2000 chars 400", s == 400, s)
s, r = call("GET", f"/api/posts/{pid}/comments", tok=A)
check("C5 list flat in order with author", s == 200 and [c["id"] for c in r if c["id"] in (cid, rid, rid2)] == [cid, rid, rid2] and r[0]["author"]["displayName"], (s, r))
s, r = call("PUT", f"/api/posts/{pid}/comments/{cid}", {"content": "수정됨"}, U); check("C6 other user cannot edit 403", s == 403, s)
s, r = call("PUT", f"/api/posts/{pid}/comments/{cid}", {"content": "수정됨"}, O); check("C6 author edits own comment", s == 200 and r["content"] == "수정됨", (s, r))
s, r = call("PUT", f"/api/posts/{pid}/comments/{rid}", {"content": "관리자 수정"}, A); check("C6 admin edits any comment", s == 200 and r["content"] == "관리자 수정", (s, r))
s, r = call("DELETE", f"/api/posts/{pid}/comments/{cid}", tok=O); check("C7 soft delete 204", s == 204, s)
s, r = call("GET", f"/api/posts/{pid}/comments", tok=O)
root = next((c for c in r if c["id"] == cid), None)
check("C7 deleted comment with replies stays as placeholder", s == 200 and root is not None and root["deleted"] is True and root["content"] is None and root["editable"] is False, root)
s, r = call("DELETE", f"/api/posts/{pid}/comments/{rid2}", tok=O)
s, r = call("GET", f"/api/posts/{pid}/comments", tok=O); check("C7 deleted reply disappears from list", s == 200 and not any(c["id"] == rid2 for c in r), [c["id"] for c in r])
s, r = call("PUT", f"/api/posts/{pid}/comments/{cid}", {"content": "되살리기"}, O); check("C7 deleted comment cannot be edited 404", s == 404, s)
s, r = call("POST", f"/api/posts/{did}/comments", {"content": "남의 초안에 댓글"}, O); check("C8 others' draft not commentable 404", s == 404, s)
s, r = call("GET", f"/api/posts/{did}/comments", tok=O); check("C8 others' draft comments hidden 404", s == 404, s)
s, r = call("POST", f"/api/posts/{did}/comments", {"content": "내 초안 메모"}, U); check("C8 author comments on own draft", s == 201, (s, r))
s, r = call("GET", f"/api/posts/{pid}/comments"); check("C8 unauth blocked 401", s == 401, s)

if _standalone:
    print(f"\nRESULT {ok} pass / {bad} fail")
