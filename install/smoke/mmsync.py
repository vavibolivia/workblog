# 공수 표 동기화·CRUD 스모크. smoke.py 가 exec 한다(B, A, call, check). 만든 표·계정·이벤트는 끝에서 지운다.
# - 프로젝트 이름 변경: 인원은 그대로 새 이름 아래, '관련' 에 옛 이름을 쓴 이벤트도 새 이름으로
# - 인원 이름 변경: 같은 표의 같은 사람(다른 프로젝트 행)도 함께
# - 계정 이름·부서 변경: 그 계정이 연결된 모든 공수 행이 따라감
# - 프로젝트·인원 순서 옮기기, 프로젝트 삭제 때 그 프로젝트 행만 지워짐
import uuid as _u2
_sx = _u2.uuid4().hex[:6]
s, _acct = call("POST", "/api/users", {"username": "mms" + _sx, "password": "mmpass1234", "displayName": "동기화" + _sx, "department": "동기부", "role": "USER"}, A)
check("MMS temp user", s == 201, (s, _acct))
s, sh = call("POST", "/api/mm/sheets", {"title": "스모크 동기화 " + _sx, "startDate": "2026-11-02", "weeks": 4}, A); MS = sh["id"]
_pa, _pb = "동기A" + _sx, "동기B" + _sx
s, sh = call("POST", f"/api/mm/sheets/{MS}/projects", {"name": _pa}, A)
s, sh = call("POST", f"/api/mm/sheets/{MS}/projects", {"name": _pb}, A)
PA, PB = sh["projects"][0]["id"], sh["projects"][1]["id"]
# 같은 외부 인원을 두 프로젝트에, 계정 인원도 두 프로젝트에
for _p in (PA, PB):
    call("POST", f"/api/mm/sheets/{MS}/rows", {"projectId": _p, "personName": "외부" + _sx, "department": "협력사"}, A)
    s, sh = call("POST", f"/api/mm/sheets/{MS}/rows", {"projectId": _p, "userId": _acct["id"], "personName": "무시될 이름"}, A)
check("MMS linked row uses account name (given name ignored)", all(r["personName"] == "동기화" + _sx and r["department"] == "동기부" for r in sh["rows"] if r["userId"]), sh["rows"])
ext = [r for r in sh["rows"] if r["userId"] is None]
s, ev = call("POST", "/api/mm/events", {"startDate": "2026-11-05", "kind": "ETC", "title": "동기 회의", "related": _pa}, A)
check("MMS event created", s in (200, 201), (s, ev))

# 1) 인원 이름 변경 → 같은 사람의 다른 프로젝트 행도
s, sh = call("PUT", f"/api/mm/sheets/{MS}/rows/{ext[0]['id']}", {"projectId": PA, "personName": "외부바뀜" + _sx, "department": "협력사2", "role": "QA"}, A)
ext2 = [r for r in sh["rows"] if r["userId"] is None]
check("MMS rename person applies to same person's rows", s == 200 and len(ext2) == 2 and all(r["personName"] == "외부바뀜" + _sx and r["department"] == "협력사2" for r in ext2), ext2)
check("MMS role stays per row", [r["role"] for r in ext2 if r["id"] == ext[0]["id"]] == ["QA"] and [r["role"] for r in ext2 if r["id"] == ext[1]["id"]] == [None], ext2)

# 2) 계정 이름·부서 변경 → 연결된 공수 행 모두
s, r = call("PUT", f"/api/users/{_acct['id']}", {"displayName": "동기새이름" + _sx, "email": None, "department": "새부서", "role": "USER"}, A)
s, sh = call("GET", f"/api/mm/sheets/{MS}", tok=A)
linked = [r for r in sh["rows"] if r["userId"] == _acct["id"]]
check("MMS account rename syncs all linked rows", len(linked) == 2 and all(r["personName"] == "동기새이름" + _sx and r["department"] == "새부서" for r in linked), linked)

# 3) 프로젝트 이름 변경 → 인원 그대로, 이벤트 '관련' 따라감
_pa2 = "동기A2" + _sx
n_before = len([r for r in sh["rows"] if r["projectId"] == PA])
s, sh = call("PUT", f"/api/mm/sheets/{MS}/projects/{PA}", {"name": _pa2}, A)
check("MMS project rename keeps rows", s == 200 and next(p["name"] for p in sh["projects"] if p["id"] == PA) == _pa2 and len([r for r in sh["rows"] if r["projectId"] == PA]) == n_before, sh.get("projects"))
s, evs = call("GET", "/api/mm/events?from=2026-11-01&to=2026-11-30", tok=A)
mine = [e for e in (evs.get("events", []) if isinstance(evs, dict) else []) if e["id"] == ev.get("id")]
check("MMS event related follows project rename", mine and mine[0]["related"] == _pa2, mine)

# 4) 순서 옮기기
s, sh = call("POST", f"/api/mm/sheets/{MS}/projects/{PB}/move?dir=-1", tok=A)
check("MMS move project up", s == 200 and sh["projects"][0]["id"] == PB, [p["id"] for p in sh.get("projects", [])])
rows_b = [r["id"] for r in sh["rows"] if r["projectId"] == PB]
s, sh = call("POST", f"/api/mm/sheets/{MS}/rows/{rows_b[1]}/move?dir=-1", tok=A)
check("MMS move row up within project", s == 200 and [r["id"] for r in sh["rows"] if r["projectId"] == PB] == [rows_b[1], rows_b[0]], sh.get("rows"))
s, sh2 = call("POST", f"/api/mm/sheets/{MS}/rows/{rows_b[1]}/move?dir=-1", tok=A)
check("MMS move first row up is no-op", s == 200 and [r["id"] for r in sh2["rows"] if r["projectId"] == PB] == [rows_b[1], rows_b[0]], s)

# 5) 프로젝트 삭제 → 그 프로젝트 행만 지워지고 같은 사람의 다른 프로젝트 행은 남음
s, sh = call("DELETE", f"/api/mm/sheets/{MS}/projects/{PB}", tok=A)
check("MMS delete project removes only its rows", s == 200 and [p["id"] for p in sh["projects"]] == [PA] and all(r["projectId"] == PA for r in sh["rows"]) and len(sh["rows"]) == 2, sh.get("rows"))

# 정리
if ev.get("id"): call("DELETE", f"/api/mm/events/{ev['id']}", tok=A)
s, _ = call("DELETE", f"/api/mm/sheets/{MS}", tok=A); check("MMS cleanup sheet", s == 204, s)
call("DELETE", f"/api/users/{_acct['id']}?purge=true", tok=A)
