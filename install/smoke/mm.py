# 공수(Man-month) 표 스모크. smoke.py 가 exec 한다(B, A·U·O 토큰, call, check 를 그대로 쓴다). 만든 표는 끝에서 지운다.
import uuid as _uuid


def _mm_raw(method, path, data=None, tok=None, ctype=None):
    req = urllib.request.Request(B + path, method=method, data=data)
    if ctype: req.add_header("Content-Type", ctype)
    if tok: req.add_header("Authorization", "Bearer " + tok)
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


# 앞 단계에서 U·O 계정을 정리하므로 이 스크립트용 임시 계정 2개를 만들고 끝에서 지운다
_mm_sfx = _uuid.uuid4().hex[:6]
_mm_users = []
for _n in ("mma" + _mm_sfx, "mmb" + _mm_sfx):
    s, r = call("POST", "/api/users", {"username": _n, "password": "mmpass1234", "displayName": "공수" + _n[-6:], "department": "공수시험", "role": "USER"}, A)
    _mm_users.append(r["id"])
s, r = call("POST", "/api/auth/login", {"username": "mma" + _mm_sfx, "password": "mmpass1234"}); MU = r["token"]
s, r = call("POST", "/api/auth/login", {"username": "mmb" + _mm_sfx, "password": "mmpass1234"}); MO = r["token"]
s, me_u = call("GET", "/api/auth/me", tok=MU)
s, me_o = call("GET", "/api/auth/me", tok=MO)
# 수요일로 만들면 그 주 월요일로 맞춤
s, sh = call("POST", "/api/mm/sheets", {"title": "스모크 공수 표", "startDate": "2026-11-04", "weeks": 8}, A)
check("MM create 201 + start snapped to Monday", s == 201 and sh["startDate"] == "2026-11-02" and sh["weeks"] == 8 and sh["canManage"], (s, sh))
MM = sh["id"]
s, sh = call("POST", f"/api/mm/sheets/{MM}/projects", {"name": "프로젝트 가"}, A); P1 = sh["projects"][0]["id"]
s, sh = call("POST", f"/api/mm/sheets/{MM}/rows", {"projectId": P1, "userId": me_u["id"], "role": "개발"}, A)
check("MM add row by account fills name", s == 200 and sh["rows"][0]["personName"] == me_u["displayName"] and sh["rows"][0]["userId"] == me_u["id"], (s, sh.get("rows")))
s, sh = call("POST", f"/api/mm/sheets/{MM}/rows", {"projectId": P1, "personName": "외부 인력"}, A)
R1, R2 = sh["rows"][0]["id"], sh["rows"][1]["id"]
s, sh = call("PUT", f"/api/mm/sheets/{MM}/cells", {"changes": [{"rowId": R1, "week": 0, "value": 1.0}, {"rowId": R1, "week": 1, "value": 0.5}, {"rowId": R2, "week": 1, "value": 0.25}]}, A)
check("MM set cells", s == 200 and sh["rows"][0]["cells"].get("1") == 0.5 and sh["rows"][1]["cells"].get("1") == 0.25, (s, sh.get("rows")))
s, r = call("PUT", f"/api/mm/sheets/{MM}/cells", {"changes": [{"rowId": R1, "week": 2, "value": 1.5}]}, A); check("MM value > 1.0 rejected 400", s == 400, (s, r))
s, r = call("PUT", f"/api/mm/sheets/{MM}/cells", {"changes": [{"rowId": R1, "week": 8, "value": 0.5}]}, A); check("MM week out of range 400", s == 400, (s, r))
# 권한: 보기는 모두, 수정은 편집자
s, r = call("GET", f"/api/mm/sheets/{MM}", tok=MO); check("MM other user can view, not edit", s == 200 and r["canEdit"] is False, (s, r and r.get("canEdit")))
s, r = call("PUT", f"/api/mm/sheets/{MM}/cells", {"changes": [{"rowId": R1, "week": 3, "value": 0.5}]}, MO); check("MM non-editor edit 403", s == 403, s)
s, r = call("PUT", f"/api/mm/sheets/{MM}/editors", {"userIds": [me_o["id"]]}, MO); check("MM non-owner set editors 403", s == 403, s)
s, r = call("PUT", f"/api/mm/sheets/{MM}/editors", {"userIds": [me_o["id"]]}, A); check("MM owner sets editors", s == 200 and [e["id"] for e in r["editors"]] == [me_o["id"]], (s, r and r.get("editors")))
s, r = call("PUT", f"/api/mm/sheets/{MM}/cells", {"changes": [{"rowId": R1, "week": 3, "value": 0.5}]}, MO); check("MM editor can edit", s == 200, (s, r))
s, r = call("PATCH", f"/api/mm/sheets/{MM}", {"weeks": 10}, MO); check("MM editor cannot change settings 403", s == 403, s)
s, r = call("PATCH", f"/api/mm/sheets/{MM}", {"featured": True}, A); check("MM admin features sheet", s == 200 and r["featured"] is True, (s, r and r.get("featured")))
s, r = call("GET", "/api/mm/sheets?featured=true", tok=MO); check("MM featured list visible to all", s == 200 and any(x["id"] == MM for x in r), (s, r))
# 시작을 1주 늦추면 값은 날짜 기준으로 한 칸 당겨짐(첫 주 값은 기간 밖 → 지워짐)
s, r = call("PATCH", f"/api/mm/sheets/{MM}", {"startDate": "2026-11-09"}, A)
row1 = next(x for x in r["rows"] if x["id"] == R1) if s == 200 else {}
check("MM start shift keeps values by date", s == 200 and r["startDate"] == "2026-11-09" and row1.get("cells") == {"0": 0.5, "2": 0.5}, (s, row1))
s, r = call("GET", f"/api/mm/sheets/{MM}", tok=A); check("MM change log recorded", s == 200 and len(r["changes"]) >= 5, (s, r and len(r["changes"])))
# 엑셀: 내려받기 → 같은 파일 올리기(값 그대로) → 프로젝트·인원 늘지 않음
s, xbytes = _mm_raw("GET", f"/api/mm/sheets/{MM}/excel", tok=A); check("MM excel export 200 xlsx", s == 200 and xbytes[:2] == b"PK", s)
s, tbytes = _mm_raw("GET", f"/api/mm/sheets/{MM}/excel?template=true", tok=MO); check("MM excel template for viewer 200", s == 200 and tbytes[:2] == b"PK", s)
bd = "----mm" + _uuid.uuid4().hex
body = (f"--{bd}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"mm.xlsx\"\r\nContent-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n").encode() + xbytes + f"\r\n--{bd}--\r\n".encode()
s, rb = _mm_raw("POST", f"/api/mm/sheets/{MM}/excel", body, A, f"multipart/form-data; boundary={bd}")
imp = json.loads(rb.decode()) if s == 200 else {}
check("MM excel re-import is stable", s == 200 and imp.get("projectsCreated") == 0 and imp.get("rowsCreated") == 0, (s, rb[:200]))
s, after = call("GET", f"/api/mm/sheets/{MM}", tok=A)
check("MM values unchanged after round trip", s == 200 and {x["id"]: x["cells"] for x in after["rows"]} == {x["id"]: x["cells"] for x in r["rows"]}, (after and after.get("rows"), r.get("rows")))
# 정리
s, r = call("DELETE", f"/api/mm/sheets/{MM}", tok=MO); check("MM editor cannot delete 403", s == 403, s)
s, r = call("DELETE", f"/api/mm/sheets/{MM}", tok=A); check("MM delete 204", s == 204, s)
s, r = call("GET", f"/api/mm/sheets/{MM}", tok=A); check("MM gone 404", s == 404, s)
for _u in _mm_users:
    call("DELETE", f"/api/users/{_u}?purge=true", tok=A)
s, r = call("GET", "/api/users", tok=A); check("MM cleanup temp users", s == 200 and not any(u["id"] in _mm_users for u in r), s)
