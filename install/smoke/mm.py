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
# 아무 숫자(1 넘는 값·소수 둘째 자리) — 넣었다가 바로 비움
s, r = call("PUT", f"/api/mm/sheets/{MM}/cells", {"changes": [{"rowId": R2, "week": 5, "value": 40.256}]}, A); check("MM any number accepted (40.26)", s == 200 and r["rows"][1]["cells"].get("5") == 40.26, (s, r and r["rows"][1]["cells"]))
s, r = call("PUT", f"/api/mm/sheets/{MM}/cells", {"changes": [{"rowId": R2, "week": 5, "value": None}]}, A); check("MM clear number", s == 200 and "5" not in r["rows"][1]["cells"], s)
s, r = call("PUT", f"/api/mm/sheets/{MM}/cells", {"changes": [{"rowId": R2, "week": 5, "value": 1e12}]}, A); check("MM too large rejected 400", s == 400, s)
# 할 일 메모: 여러 칸 한 번에, 빈 글은 지움
s, r = call("PUT", f"/api/mm/sheets/{MM}/notes", {"changes": [{"rowId": R1, "week": 3, "text": "통합 시험"}, {"rowId": R2, "week": 3, "text": "통합 시험"}]}, A)
check("MM notes set on 2 cells", s == 200 and r["rows"][0]["notes"].get("3") == "통합 시험" and r["rows"][1]["notes"].get("3") == "통합 시험", (s, r and [x.get("notes") for x in r["rows"]]))
s, r = call("PUT", f"/api/mm/sheets/{MM}/notes", {"changes": [{"rowId": R2, "week": 3, "text": "  "}]}, A); check("MM note blank clears", s == 200 and "3" not in r["rows"][1]["notes"], s)
# 이벤트(공용): 표가 하나도 없는 사람은 못 고침
s, r = call("PUT", f"/api/mm/sheets/{MM}/cells", {"changes": [{"rowId": R1, "week": 8, "value": 0.5}]}, A); check("MM week out of range 400", s == 400, (s, r))
# 권한: 보기는 모두, 수정은 편집자
s, r = call("GET", f"/api/mm/sheets/{MM}", tok=MO); check("MM other user can view, not edit", s == 200 and r["canEdit"] is False, (s, r and r.get("canEdit")))
s, r = call("PUT", f"/api/mm/sheets/{MM}/cells", {"changes": [{"rowId": R1, "week": 3, "value": 0.5}]}, MO); check("MM non-editor edit 403", s == 403, s)
s, r = call("PUT", f"/api/mm/sheets/{MM}/editors", {"userIds": [me_o["id"]]}, MO); check("MM non-owner set editors 403", s == 403, s)
# 이벤트 넣기는 로그인한 누구나(고치기·지우기는 만든 사람과 관리자). 만든 것은 바로 지운다 — 공용 목록에 남지 않게
s, r = call("POST", "/api/mm/events", {"startDate": "2026-11-18", "kind": "RELEASE", "title": "스모크 릴리스"}, MO); check("EV any user create 201", s == 201, s)
if s == 201:
    s, _ = call("DELETE", f"/api/mm/events/{r['id']}", tok=MO); check("EV creator deletes own 204", s == 204, s)
s, r = call("PUT", f"/api/mm/sheets/{MM}/editors", {"userIds": [me_o["id"]]}, A); check("MM owner sets editors", s == 200 and [e["id"] for e in r["editors"]] == [me_o["id"]], (s, r and r.get("editors")))
s, r = call("PUT", f"/api/mm/sheets/{MM}/cells", {"changes": [{"rowId": R1, "week": 3, "value": 0.5}]}, MO); check("MM editor can edit", s == 200, (s, r))
s, r = call("PATCH", f"/api/mm/sheets/{MM}", {"weeks": 10}, MO); check("MM editor cannot change settings 403", s == 403, s)
s, EV = call("POST", "/api/mm/events", {"startDate": "2026-11-18", "endDate": "2026-11-19", "kind": "RELEASE", "title": "스모크 릴리스", "related": "프로젝트 가"}, MO)
check("EV sheet editor creates event 201", s == 201 and EV["kind"] == "RELEASE" and EV["endDate"] == "2026-11-19", (s, EV))
s, r = call("GET", f"/api/mm/sheets/{MM}", tok=A); check("EV shown in sheet period", s == 200 and any(e["id"] == EV["id"] for e in r["events"]), s)
s, r = call("GET", "/api/mm/events?from=2027-06-01&to=2027-06-30", tok=MO); check("EV range filter excludes", s == 200 and all(e["id"] != EV["id"] for e in r["events"]) and r["canEdit"] is True, s)
s, r = call("PUT", f"/api/mm/events/{EV['id']}", {"startDate": "2026-11-20", "endDate": "2026-11-18", "title": "x"}, A); check("EV end before start 400", s == 400, s)
s, r = call("PUT", f"/api/mm/events/{EV['id']}", {"startDate": "2026-11-20", "kind": "MILESTONE", "title": "스모크 마일스톤"}, A); check("EV update", s == 200 and r["kind"] == "MILESTONE" and r["endDate"] is None, (s, r))
# 대문(글만): 지금 목록을 기억해 두고, 이 표를 담은 임시 글을 맨 뒤에 올렸다가 원래대로 되돌린다(실제 대문을 건드리지 않음)
s, _front0 = call("GET", "/api/front", tok=A); check("FRONT list 200", s == 200 and isinstance(_front0, list), s)
_front_keep = [{"kind": i["kind"], "refId": i["refId"]} for i in _front0]
s, _fp = call("POST", "/api/posts", {"title": "smoke 공수 글 " + _mm_sfx, "content": f'<p><a href="/mm/{MM}">공수 표</a></p>', "contentFormat": "HTML", "tags": [], "status": "PUBLISHED"}, A)
FP = _fp["id"] if s in (200, 201) else None
check("FRONT temp post with sheet link", FP is not None, s)
s, r = call("PUT", "/api/front", {"items": _front_keep + [{"kind": "POST", "refId": FP}]}, MO); check("FRONT non-admin replace 403", s == 403, s)
s, r = call("PUT", "/api/front", {"items": _front_keep + [{"kind": "MM_SHEET", "refId": MM}]}, A); check("FRONT sheet itself rejected (posts only) 400", s == 400, s)
s, r = call("PUT", "/api/front", {"items": _front_keep + [{"kind": "POST", "refId": FP}]}, A); check("FRONT admin adds post last", s == 200 and r[-1]["kind"] == "POST" and r[-1]["refId"] == FP, (s, r))
s, r = call("GET", "/api/front", tok=MO); check("FRONT visible to all", s == 200 and any(i["refId"] == FP and i["kind"] == "POST" for i in r), (s, r))
s, r = call("PUT", "/api/front", {"items": _front_keep + [{"kind": "POST", "refId": 999999999}]}, A); check("FRONT unknown item 400", s == 400, s)
s, r = call("PUT", "/api/front", {"items": _front_keep}, A); check("FRONT restored", s == 200 and [{"kind": i["kind"], "refId": i["refId"]} for i in r] == _front_keep, (s, r))
# 글 내보내기에 표 요약(월별 MM), 표 화면의 '이 표를 담은 글'(글 검색으로 찾음)
if FP:
    import io as _io0, zipfile as _zf0
    s, dx = _mm_raw("GET", f"/api/posts/{FP}/export?format=docx", tok=MO)
    _docxml = _zf0.ZipFile(_io0.BytesIO(dx)).read("word/document.xml").decode("utf-8") if s == 200 else ""
    check("EXPORT docx includes MM summary table", s == 200 and "합계 MM" in _docxml and "프로젝트 가" in _docxml, s)
    s, hx = _mm_raw("GET", f"/api/posts/{FP}/export?format=html", tok=MO); check("EXPORT html includes MM table", s == 200 and b"data-mm-export" in hx, s)
    s, r = call("GET", f'/api/posts?size=10&q=/mm/{MM}"', tok=MO); check("MM backlink search finds post", s == 200 and any(x["id"] == FP for x in r["content"]), (s, r))
if FP: call("DELETE", f"/api/posts/{FP}", tok=A); s, _ = call("DELETE", f"/api/posts/{FP}?purge=true", tok=A); check("FRONT temp post purged", s == 204, s)
# 시작을 1주 늦추면 값은 날짜 기준으로 한 칸 당겨짐(첫 주 값은 기간 밖 → 지워짐)
s, r = call("PATCH", f"/api/mm/sheets/{MM}", {"startDate": "2026-11-09"}, A)
row1 = next(x for x in r["rows"] if x["id"] == R1) if s == 200 else {}
check("MM start shift keeps values by date", s == 200 and r["startDate"] == "2026-11-09" and row1.get("cells") == {"0": 0.5, "2": 0.5}, (s, row1))
check("MM start shift moves notes too", row1.get("notes") == {"2": "통합 시험"}, row1.get("notes"))
s, r = call("GET", f"/api/mm/sheets/{MM}", tok=A); check("MM change log recorded", s == 200 and len(r["changes"]) >= 5, (s, r and len(r["changes"])))
# 엑셀: 내려받기 → 같은 파일 올리기(값 그대로) → 프로젝트·인원 늘지 않음
s, xbytes = _mm_raw("GET", f"/api/mm/sheets/{MM}/excel", tok=A); check("MM excel export 200 xlsx", s == 200 and xbytes[:2] == b"PK", s)
s, tbytes = _mm_raw("GET", f"/api/mm/sheets/{MM}/excel?template=true", tok=MO); check("MM excel template for viewer 200", s == 200 and tbytes[:2] == b"PK", s)
bd = "----mm" + _uuid.uuid4().hex
body = (f"--{bd}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"mm.xlsx\"\r\nContent-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n").encode() + xbytes + f"\r\n--{bd}--\r\n".encode()
s, rb = _mm_raw("POST", f"/api/mm/sheets/{MM}/excel", body, A, f"multipart/form-data; boundary={bd}")
imp = json.loads(rb.decode()) if s == 200 else {}
check("MM excel re-import is stable", s == 200 and imp.get("applied") is True and imp.get("projectsCreated") == 0 and imp.get("rowsCreated") == 0 and imp.get("cellsSet") == 0 and imp.get("cellsCleared") == 0, (s, rb[:200]))
s, after = call("GET", f"/api/mm/sheets/{MM}", tok=A)
check("MM values unchanged after round trip", s == 200 and {x["id"]: x["cells"] for x in after["rows"]} == {x["id"]: x["cells"] for x in r["rows"]}, (after and after.get("rows"), r.get("rows")))
# 빈 양식 올리기: 미리보기(dryRun)는 아무것도 바꾸지 않고, 기본(keepBlank)은 빈 칸을 지우지 않는다
tbody = (f"--{bd}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"t.xlsx\"\r\nContent-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n").encode() + tbytes + f"\r\n--{bd}--\r\n".encode()
s, rb = _mm_raw("POST", f"/api/mm/sheets/{MM}/excel?dryRun=true&keepBlank=false", tbody, A, f"multipart/form-data; boundary={bd}")
imp = json.loads(rb.decode()) if s == 200 else {}
check("MM excel dry run reports clears", s == 200 and imp.get("applied") is False and imp.get("cellsCleared", 0) > 0, (s, rb[:200]))
s, after2 = call("GET", f"/api/mm/sheets/{MM}", tok=A)
check("MM excel dry run changes nothing", s == 200 and {x["id"]: x["cells"] for x in after2["rows"]} == {x["id"]: x["cells"] for x in r["rows"]} and len(after2["changes"]) == len(after["changes"]), s)
s, rb = _mm_raw("POST", f"/api/mm/sheets/{MM}/excel", tbody, A, f"multipart/form-data; boundary={bd}")
imp = json.loads(rb.decode()) if s == 200 else {}
s2, after3 = call("GET", f"/api/mm/sheets/{MM}", tok=A)
check("MM excel blank cells kept by default", s == 200 and imp.get("cellsCleared") == 0 and {x["id"]: x["cells"] for x in after3["rows"]} == {x["id"]: x["cells"] for x in r["rows"]}, (s, rb[:200]))
call("PUT", f"/api/mm/sheets/{MM}/editors", {"userIds": []}, A)   # 편집자에서 빼고 확인한 뒤 되돌림
s, rb = _mm_raw("POST", f"/api/mm/sheets/{MM}/excel?dryRun=true", tbody, MO, f"multipart/form-data; boundary={bd}")
check("MM excel dry run needs editor", s == 403, s)
call("PUT", f"/api/mm/sheets/{MM}/editors", {"userIds": [me_o["id"]]}, A)
# 엑셀 내려받기에 메모·이벤트 시트, 소계 줄(올리기에서 건너뜀 — 위 재가져오기 안정성으로 확인)
import io as _io, zipfile as _zf
_wbxml = _zf.ZipFile(_io.BytesIO(xbytes)).read("xl/workbook.xml").decode("utf-8")
check("MM excel has memo/event sheets", 'name="메모"' in _wbxml and 'name="이벤트"' in _wbxml, _wbxml[:300])
# 표 복사: 보기만 되는 사람도 복사 가능(새 표의 주인). 값까지 옮기면 날짜 기준(1주 뒤 시작 → 한 칸 당겨짐)
s, cp = call("POST", f"/api/mm/sheets/{MM}/copy", {"title": "스모크 복사", "startDate": "2026-11-16", "weeks": 4, "withValues": True}, MU)
crow = next((x for x in cp.get("rows", []) if x["personName"] == me_u["displayName"]), {}) if s == 201 else {}
check("MM copy by viewer 201 + owner", s == 201 and cp["canManage"] and len(cp["rows"]) == 2 and len(cp["projects"]) == 1, (s, cp))
check("MM copy moves values/notes by date", crow.get("cells") == {"1": 0.5} and crow.get("notes") == {"1": "통합 시험"} and crow.get("role") == "개발", crow)
check("MM copy keeps editors", s == 201 and [e["id"] for e in cp["editors"]] == [me_o["id"]], cp.get("editors"))
s2, cp2 = call("POST", f"/api/mm/sheets/{MM}/copy", {"title": "스모크 복사2", "startDate": "2027-01-04", "weeks": 4, "withValues": False}, MU)
check("MM copy without values", s2 == 201 and all(not x["cells"] and not x["notes"] for x in cp2["rows"]), (s2, cp2))
s, orig = call("GET", f"/api/mm/sheets/{MM}", tok=A); check("MM copy leaves source unchanged", s == 200 and orig["title"] != "스모크 복사" and len(orig["rows"]) == 2, s)
for _c in (cp, cp2):
    if isinstance(_c, dict) and _c.get("id"): s, _ = call("DELETE", f"/api/mm/sheets/{_c['id']}", tok=MU); check("MM copy deleted by its owner", s == 204, s)
# 화면 주소를 바로 열거나 새로 고침해도 앱이 떠야 함(SPA 경로)
for _path in ("/mm", f"/mm/{MM}"):
    s, b = _mm_raw("GET", _path); check(f"MM page {_path} serves app", s == 200 and b"<div id=\"root\"" in b, (s, b[:80]))
# 정리
s, r = call("DELETE", f"/api/mm/sheets/{MM}", tok=MO); check("MM editor cannot delete 403", s == 403, s)
s, r = call("DELETE", f"/api/mm/events/{EV['id']}", tok=A); check("EV delete 204", s == 204, s)
s, r = call("DELETE", f"/api/mm/sheets/{MM}", tok=A); check("MM delete 204", s == 204, s)
s, r = call("GET", f"/api/mm/sheets/{MM}", tok=A); check("MM gone 404", s == 404, s)
for _u in _mm_users:
    call("DELETE", f"/api/users/{_u}?purge=true", tok=A)
s, r = call("GET", "/api/users", tok=A); check("MM cleanup temp users", s == 200 and not any(u["id"] in _mm_users for u in r), s)
