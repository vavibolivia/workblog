import json, urllib.request, urllib.parse, sys, os
B = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
ADMIN_PW = sys.argv[2] if len(sys.argv) > 2 else "admin1234"
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
s, r = call("GET", "/api/posts"); check("R1 unauth blocked 401", s == 401, s)
s, r = call("POST", "/api/auth/login", {"username": "admin", "password": "wrong"}); check("R1 bad password 401", s == 401, s)
s, r = call("POST", "/api/auth/login", {"username": "admin", "password": ADMIN_PW}); check("R1 admin login", s == 200, r); A = r["token"]
uname = "smoke" + str(abs(hash(B)) % 100000)
s, r = call("POST", "/api/users", {"username": uname, "password": "pass1234", "displayName": "홍길동", "department": "IT전략실", "role": "USER"}, A)
check("R1 admin creates user", s in (201, 409), r)
s, r = call("POST", "/api/auth/login", {"username": uname, "password": "pass1234"}); check("R1 new user login", s == 200, r); U = r["token"]; uid = r["user"]["id"]
s, r = call("POST", "/api/users", {"username": "x" + uname, "password": "pass1234", "displayName": "x"}, U); check("R1 non-admin cannot add user 403", s == 403, s)
# ---- R7 category tree (max depth 5) ----
import time
suffix = str(int(time.time()))[-5:]
ids = []; parent = None
for lvl in range(1, 6):
    s, r = call("POST", "/api/categories", {"name": f"T{suffix}-L{lvl}", "parentId": parent}, A)
    check(f"R7 create level {lvl}", s == 201 and r["depth"] == lvl, (s, r)); ids.append(r["id"]); parent = r["id"]
s, r = call("POST", "/api/categories", {"name": "L6", "parentId": ids[4]}, A); check("R7 level 6 rejected 400", s == 400, (s, r))
s, r = call("POST", "/api/categories", {"name": f"T{suffix}-L1", "parentId": None}, A); check("R7 duplicate sibling 409", s == 409, s)
s, r = call("POST", "/api/categories", {"name": "x", "parentId": None}, U); check("R7 non-admin cannot create 403", s == 403, s)
s, r = call("POST", "/api/categories", {"name": f"B{suffix}", "parentId": None}, A); branch = r["id"]
s, r = call("PUT", f"/api/categories/{ids[1]}", {"name": f"T{suffix}-L2", "parentId": branch}, A)
check("R7 move subtree (height 4) under depth-1 node ok", s == 200, (s, r))
s, r = call("PUT", f"/api/categories/{ids[1]}", {"name": f"T{suffix}-L2", "parentId": ids[0]}, A); check("R7 move back", s == 200, (s, r))
s, r = call("POST", "/api/categories", {"name": "c", "parentId": branch}, A); deep_parent = r["id"]
s, r = call("PUT", f"/api/categories/{ids[1]}", {"name": f"T{suffix}-L2", "parentId": deep_parent}, A)
check("R7 move that would exceed depth 5 rejected 400", s == 400, (s, r))
s, r = call("PUT", f"/api/categories/{ids[0]}", {"name": "self", "parentId": ids[3]}, A); check("R7 move under own descendant 400", s == 400, (s, r))
leaf = ids[4]; root_cat = ids[0]
s, r = call("GET", "/api/categories", tok=U)
def depth_of(nodes, cid, d=1):
    for n in nodes:
        if n["id"] == cid: return d
        f = depth_of(n["children"], cid, d + 1)
        if f: return f
check("R7 tree shows leaf at depth 5", s == 200 and depth_of(r, leaf) == 5, depth_of(r, leaf))
s, r = call("POST", "/api/posts", {"title": "VPN 접속 오류 해결 가이드", "content": "# 증상\nFortiClient 연결 시 -14 오류.\n\n# 조치\n1. 인증서 재설치\n2. MTU 1400 설정", "categoryId": leaf, "tags": ["VPN", "네트워크"], "status": "PUBLISHED", "contentFormat": "MARKDOWN"}, U)
check("R2 create post", s == 201 and r["version"] == 1, r); pid = r["id"]
check("R7 post linked to depth-5 category with full path", r["category"]["id"] == leaf and len(r["category"]["path"]) == 5, r["category"])
s, r = call("PUT", f"/api/posts/{pid}", {"title": "VPN 접속 오류 해결 가이드 (개정)", "content": "# 증상\nFortiClient -14 오류.\n\n# 조치\n1. 인증서 재설치\n2. MTU 1400\n3. 재부팅", "categoryId": root_cat, "tags": ["VPN"], "status": "PUBLISHED", "changeNote": "3단계 추가", "contentFormat": "MARKDOWN"}, U)
check("R2 update post -> v2", s == 200 and r["version"] == 2, r)
s, r = call("PUT", f"/api/posts/{pid}", {"title": "hack", "content": "x", "tags": [], "status": "PUBLISHED"}, None); check("R2 unauth edit blocked", s == 401, s)
s, r = call("POST", "/api/users", {"username": "o" + uname, "password": "pass1234", "displayName": "다른사람"}, A)
s2, r2 = call("POST", "/api/auth/login", {"username": "o" + uname, "password": "pass1234"}); O = r2["token"]
s, r = call("PUT", f"/api/posts/{pid}", {"title": "hack", "content": "x", "tags": [], "status": "PUBLISHED"}, O); check("R2 other user cannot edit 403", s == 403, s)
s, r = call("POST", "/api/posts", {"title": "작성 중 초안", "content": "비공개", "tags": [], "status": "DRAFT"}, U); did = r["id"]
s, r = call("GET", f"/api/posts/{did}", tok=O); check("R2 draft hidden from others 404", s == 404, s)
s, r = call("GET", f"/api/posts/{pid}/revisions", tok=U); check("R3 revisions listed (2)", s == 200 and [x["version"] for x in r] == [2, 1], r)
s, r = call("POST", f"/api/posts/{pid}/revisions/1/restore", tok=U); check("R3 restore v1 -> v3 with v1 title", s == 200 and r["version"] == 3 and r["title"] == "VPN 접속 오류 해결 가이드", r)
check("R7 restore brings back v1 category (leaf)", r["category"]["id"] == leaf, r["category"])
s, r = call("GET", f"/api/posts/{pid}/revisions/3", tok=U); check("R3 v3 is RESTORE", s == 200 and r["changeType"] == "RESTORE", r)
s, r = call("GET", "/api/search?q=MTU", tok=O); check("R4 search body text", s == 200 and any(p["id"] == pid for p in r["content"]), r)
s, r = call("GET", "/api/search?q=홍길동", tok=O); check("R4 search by author name", s == 200 and any(p["id"] == pid for p in r["content"]), r)
s, r = call("GET", "/api/posts?tag=VPN", tok=O); check("R4 tag filter", s == 200 and any(p["id"] == pid for p in r["content"]), r)
s, r = call("GET", "/api/search?q=작성 중", tok=O); check("R4 search excludes others' drafts", s == 200 and not any(p["id"] == did for p in r["content"]), r)
s, r = call("GET", "/api/posts?size=100", tok=A); check("R2 admin main feed excludes others' drafts", s == 200 and not any(p["id"] == did for p in r["content"]), r)
s, r = call("GET", f"/api/posts?authorId={uid}&status=DRAFT", tok=A); check("R2 admin sees draft via explicit filter", s == 200 and any(p["id"] == did for p in r["content"]), r)
s, r = call("GET", f"/api/posts?categoryId={root_cat}", tok=O); check("R7 filter by root includes post in depth-5 child", s == 200 and any(p["id"] == pid for p in r["content"]), r)
s, r = call("GET", f"/api/posts?categoryId={branch}", tok=O); check("R7 filter by other branch excludes it", s == 200 and not any(p["id"] == pid for p in r["content"]), r)
s, r = call("DELETE", f"/api/categories/{leaf}", tok=A); check("R7 delete category with posts 400", s == 400, (s, r))
s, r = call("DELETE", f"/api/categories/{root_cat}", tok=A); check("R7 delete category with children 400", s == 400, (s, r))
s, r = call("DELETE", f"/api/categories/{deep_parent}", tok=A); check("R7 delete empty category 204", s == 204, s)
s, r = call("GET", "/api/categories", tok=O); check("R5 categories tree available", s == 200 and isinstance(r, list) and len(r) > 0, r)
s, r = call("DELETE", f"/api/posts/{pid}", tok=U); check("R2 delete 204", s == 204, s)
s, r = call("GET", f"/api/search?q=MTU", tok=O); check("R2 deleted hidden from search", s == 200 and not any(p["id"] == pid for p in r["content"]), r)
s, r = call("POST", f"/api/posts/{pid}/undelete", tok=U); check("R3 undelete -> v5", s == 200 and r["version"] == 5, r)
s, r = call("GET", "/api/history?size=50", tok=O); types = [a["changeType"] for a in r["content"] if a["postId"] == pid]
check("R3 company history has all events", s == 200 and set(types) >= {"CREATE", "UPDATE", "RESTORE", "DELETE", "UNDELETE"}, types)
check("R3 history hides others' drafts", not any(a["postId"] == did for a in r["content"]), "draft leaked")
# ---- R8 rich text + attachments ----
png = bytes.fromhex("89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000d49444154789c636000010000050001" + "0d0a2db40000000049454e44ae426082")
s, r = upload("그림.png", png, "image/png", U); check("R8 upload image 201", s == 201 and r["kind"] == "IMAGE" and r["url"].startswith("/api/files/"), (s, r)); img = r
s, r = upload("표.xlsx", b"PK\x03\x04fake", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", U); check("R8 upload excel kind EXCEL", s == 201 and r["kind"] == "EXCEL", (s, r)); xls = r
s, r = upload("evil.exe", b"MZ", "application/octet-stream", U); check("R8 exe upload rejected 400", s == 400, s)
req = urllib.request.Request(B + img["url"]);
try:
    with urllib.request.urlopen(req) as rr: check("R8 image served without token", rr.status == 200 and rr.headers.get("Content-Type","").startswith("image/png"), rr.status)
except Exception as e: check("R8 image served without token", False, e)
html = ('<h2 style="text-align:center">서식 <span style="color:#d7263d;font-size:19px;font-family:\'Nanum Gothic\', sans-serif">강조</span></h2>'
        '<p data-indent="2" style="margin-left:64px">내어쓰기 문단</p><figure class="img-figure" data-align="right" data-width="50%"><img src="' + img["url"] + '" alt="그림" style="width:50%"><figcaption>캡션</figcaption></figure>'
        '<table class="rich-table"><tbody><tr><th><p>항목</p></th><th><p>값</p></th></tr><tr><td><p>A</p></td><td><p>1</p></td></tr></tbody></table>'
        '<script>alert(1)</script><p onclick="x()">이벤트</p><img src="javascript:alert(1)"><p style="background:url(x)">배경</p>')
s, r = call("POST", "/api/posts", {"title": "리치 텍스트 글", "content": html, "contentFormat": "HTML", "attachmentIds": [img["id"], xls["id"]], "tags": ["서식"], "status": "PUBLISHED"}, U)
check("R8 create HTML post", s == 201 and r["contentFormat"] == "HTML", (s, r)); rid = r["id"]; saved = r["content"]; rich_pid = rid
check("R8 script/onclick/javascript: stripped", "<script" not in saved and "onclick" not in saved and "javascript:" not in saved and "url(" not in saved, saved)
check("R8 font/color/size/align/indent kept", all(k in saved for k in ["color:#d7263d", "font-size:19px", "Nanum Gothic", "text-align:center", "data-indent=\"2\"", "margin-left:64px"]), saved)
check("R8 figure/table kept", "<figure" in saved and "data-align=\"right\"" in saved and "<figcaption>" in saved and "<table" in saved and "<th>" in saved, saved)
check("R8 attachments linked to post", {a["id"] for a in r["attachments"]} == {img["id"], xls["id"]}, r["attachments"])
s, r = call("GET", "/api/search?q=내어쓰기", tok=O); check("R8 search over HTML text with plain snippet", s == 200 and any(p["id"] == rid and "<" not in p["snippet"] for p in r["content"]), r)
s, r = call("GET", f"/api/posts/{rid}/revisions/1", tok=U); check("R8 revision keeps contentFormat", s == 200 and r["contentFormat"] == "HTML", r)
s, r = call("GET", f"/api/posts/{pid}", tok=U); check("R8 old markdown post still MARKDOWN", s == 200 and r["contentFormat"] == "MARKDOWN", r.get("contentFormat"))
s, r = call("POST", "/api/ai/ask", {"question": "x"}, U); check("AI removed (no endpoint)", s in (404, 405), s)
# ---- 공감 / 공지 / 공개 범위 / 앨범형 썸네일 ----
s, r = call("GET", f"/api/posts/{rid}?view=false", tok=U)
check("LIKE new post starts with 0 likes and public", r["likeCount"] == 0 and r["likedByMe"] is False and r["visibility"] == "PUBLIC" and r["pinned"] is False, r)
s, r = call("POST", f"/api/posts/{rid}/like", tok=U); check("LIKE toggle on -> liked, count 1", s == 200 and r["liked"] is True and r["count"] == 1, (s, r))
s, r = call("POST", f"/api/posts/{rid}/like", tok=O); check("LIKE second user -> count 2", s == 200 and r["liked"] is True and r["count"] == 2, (s, r))
s, r = call("POST", f"/api/posts/{rid}/like", tok=U); check("LIKE toggle off -> count 1", s == 200 and r["liked"] is False and r["count"] == 1, (s, r))
s, r = call("GET", f"/api/posts/{rid}?view=false", tok=O); check("LIKE detail shows likeCount/likedByMe", s == 200 and r["likeCount"] == 1 and r["likedByMe"] is True, (s, r))
s, r = call("GET", "/api/posts?tag=서식", tok=O); card = next((p for p in r["content"] if p["id"] == rid), None)
check("ALBUM thumbnailUrl = first image of HTML post", s == 200 and card is not None and card["thumbnailUrl"] == img["url"], card and card.get("thumbnailUrl"))
check("LIKE list shows likeCount", card is not None and card["likeCount"] == 1 and card["likedByMe"] is True, card)
s, r = call("POST", "/api/posts", {"title": "마크다운 사진 글", "content": "본문" + chr(10) + chr(10) + "![그림](" + img["url"] + " \"설명\")" + chr(10), "contentFormat": "MARKDOWN", "tags": ["썸네일"], "status": "PUBLISHED"}, U); mdid = r["id"]
s, r = call("GET", "/api/posts?tag=썸네일", tok=O); card = next((p for p in r["content"] if p["id"] == mdid), None)
check("ALBUM thumbnailUrl from markdown ![](url)", s == 200 and card is not None and card["thumbnailUrl"] == img["url"], card and card.get("thumbnailUrl"))
s, r = call("POST", "/api/posts", {"title": "일반 사용자 공지 시도", "content": "x", "tags": [], "status": "PUBLISHED", "pinned": True}, U)
check("PIN non-admin pinned ignored", s == 201 and r["pinned"] is False, (s, r)); npid = r["id"]
s, r = call("PUT", f"/api/posts/{npid}", {"title": "일반 사용자 공지 시도", "content": "x", "tags": [], "status": "PUBLISHED", "pinned": True}, U)
check("PIN non-admin cannot pin on update", s == 200 and r["pinned"] is False, (s, r))
s, r = call("POST", "/api/posts", {"title": "[공지] 스모크 공지", "content": "<p>상단 고정 확인</p>", "contentFormat": "HTML", "tags": [], "status": "PUBLISHED", "pinned": True}, A)
check("PIN admin creates pinned post", s == 201 and r["pinned"] is True, (s, r)); ppid = r["id"]
s, r = call("GET", "/api/posts", tok=O); check("PIN pinned post first in main feed", s == 200 and len(r["content"]) > 0 and r["content"][0]["id"] == ppid and r["content"][0]["pinned"] is True, [p["id"] for p in r["content"][:3]])
s, r = call("GET", "/api/search?q=상단 고정", tok=O); check("PIN search still finds pinned post", s == 200 and any(p["id"] == ppid for p in r["content"]), r)
s, r = call("POST", "/api/posts", {"title": "비공개 메모", "content": "나만 보는 내용", "tags": ["비밀"], "status": "PUBLISHED", "visibility": "PRIVATE"}, U)
check("VIS create PRIVATE post", s == 201 and r["visibility"] == "PRIVATE", (s, r)); prid = r["id"]
s, r = call("GET", f"/api/posts/{prid}", tok=O); check("VIS private hidden from others 404", s == 404, s)
s, r = call("GET", "/api/posts?tag=비밀", tok=O); check("VIS private excluded from others' list", s == 200 and not any(p["id"] == prid for p in r["content"]), r)
s, r = call("GET", "/api/search?q=나만 보는", tok=O); check("VIS private excluded from search", s == 200 and not any(p["id"] == prid for p in r["content"]), r)
s, r = call("POST", f"/api/posts/{prid}/like", tok=O); check("VIS others cannot like private 404", s == 404, s)
s, r = call("GET", "/api/history?size=50", tok=O); check("VIS history hides others' private posts", s == 200 and not any(a["postId"] == prid for a in r["content"]), "private leaked")
s, r = call("GET", f"/api/posts/{prid}", tok=U); check("VIS author sees own private", s == 200 and r["visibility"] == "PRIVATE", s)
s, r = call("GET", f"/api/posts/{prid}", tok=A); check("VIS admin sees private", s == 200, s)
s, r = call("GET", "/api/posts?tag=비밀", tok=U); check("VIS author's list includes private", s == 200 and any(p["id"] == prid for p in r["content"]), r)
s, r = call("PUT", f"/api/posts/{prid}", {"title": "비공개 메모", "content": "이제 공개", "tags": ["비밀"], "status": "PUBLISHED", "visibility": "PUBLIC"}, U)
check("VIS switch to PUBLIC", s == 200 and r["visibility"] == "PUBLIC", (s, r))
s, r = call("GET", f"/api/posts/{prid}", tok=O); check("VIS now visible to others", s == 200, s)
s, r = call("POST", f"/api/posts/{prid}/revisions/1/restore", tok=U); check("VIS restore brings back PRIVATE", s == 200 and r["visibility"] == "PRIVATE", (s, r))
call("DELETE", f"/api/posts/{ppid}", tok=A)  # 공지 테스트 글은 피드에 남지 않게 정리
s, r = call("PUT", f"/api/users/{uid}", {"displayName": "홍길동", "active": False}, A); check("R1 admin deactivates user", s == 200 and r["active"] is False, r)
s, r = call("POST", "/api/auth/login", {"username": uname, "password": "pass1234"}); check("R1 deactivated user cannot login", s == 401, s)
s, r = call("GET", "/api/posts", tok=U); check("R1 deactivated user's token rejected", s == 401, s)
call("PUT", f"/api/users/{uid}", {"displayName": "홍길동", "active": True}, A)
# ---- 댓글·답글 (scripts/smoke_comments.py, 같은 폴더) ----
exec(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "smoke_comments.py"), encoding="utf-8").read())
# ---- 2026-10-27 기능(가입·토큰·PATCH·가져오기·내보내기·문서·영구삭제) + 정리 (scripts/smoke_v2.py) ----
exec(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "smoke_v2.py"), encoding="utf-8").read())
print(f"\nRESULT {ok} pass / {bad} fail")
