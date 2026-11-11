"""WorkBlog 데이터 올리기 — 원본 WorkBlog 의 전체 덤프를 비공개 데이터 저장소의 GitHub 릴리스로 올린다.

받는 서버의 workblog-sync 가 그 저장소의 최신 릴리스를 받아 병합한다(관리 › 업데이트).
원본 서버와 GitHub 양쪽에 닿는 PC 에서 돈다(원본 서버가 인터넷이 안 되는 경우). 주기 실행은 작업 스케줄러·cron 으로.

  python install/publish/publish-data.py                 # 설정 파일 기본 위치: ~/.workblog-publish.env
  python install/publish/publish-data.py --env <파일> [--force]

설정 파일(KEY=VALUE):
  WB_URL=http://원본서버:포트      WB_USER=admin      WB_PASSWORD=...
  DATA_REPO=<owner>/<name>        KEEP=3   (남길 릴리스 수)
필요: gh CLI 로그인(gh auth login) — 그 계정이 DATA_REPO 에 쓸 수 있어야 한다.

바뀐 것이 없으면(덤프의 내용 지문이 최신 릴리스와 같으면) 올리지 않는다. --force 면 그래도 올린다.
"""
import argparse, datetime, hashlib, json, os, subprocess, sys, tempfile, urllib.request, zipfile


def load_env(path):
    env = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip()
    return env


def gh(*args, capture=True):
    r = subprocess.run(["gh", *args], capture_output=capture, text=True, encoding="utf-8")
    if r.returncode != 0:
        raise SystemExit(f"gh {' '.join(args[:3])} 실패: {(r.stderr or '').strip()}")
    return r.stdout


def dump(url, user, password, out):
    req = urllib.request.Request(url + "/api/auth/login", data=json.dumps({"username": user, "password": password}).encode(),
                                 headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=60) as r:
        token = json.loads(r.read())["token"]
    # markBaseline=false: 관리 화면의 '업데이트 덤프' 기준 시각을 건드리지 않는다
    req = urllib.request.Request(url + "/api/admin/data/export?full=true&markBaseline=false", headers={"Authorization": "Bearer " + token})
    with urllib.request.urlopen(req, timeout=None) as r, open(out, "wb") as f:
        name = r.headers.get("X-Dump-Name") or "workblog-dump.zip"
        while True:
            b = r.read(1 << 20)
            if not b:
                break
            f.write(b)
    return name


def fingerprint(path):
    """manifest.json(내보낸 시각이 들어 있음)을 뺀 항목들의 이름·CRC·크기 지문."""
    h = hashlib.sha256()
    with zipfile.ZipFile(path) as z:
        for i in sorted(z.infolist(), key=lambda x: x.filename):
            if i.filename != "manifest.json":
                h.update(f"{i.filename}\0{i.CRC}\0{i.file_size}\n".encode())
        manifest = json.loads(z.read("manifest.json"))
    return h.hexdigest(), manifest


def main():
    ap = argparse.ArgumentParser(description="WorkBlog 데이터 → 비공개 저장소 릴리스")
    ap.add_argument("--env", default=os.path.expanduser("~/.workblog-publish.env"))
    ap.add_argument("--force", action="store_true")
    a = ap.parse_args()
    env = load_env(a.env)
    for k in ("WB_URL", "WB_USER", "WB_PASSWORD", "DATA_REPO"):
        if not env.get(k):
            raise SystemExit(f"설정 {k} 가 없습니다: {a.env}")
    repo, keep = env["DATA_REPO"], int(env.get("KEEP", "3"))
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")

    with tempfile.TemporaryDirectory() as tmp:
        raw = os.path.join(tmp, "dump.zip")
        name = dump(env["WB_URL"].rstrip("/"), env["WB_USER"], env["WB_PASSWORD"], raw)
        fp, m = fingerprint(raw)
        counts = m.get("counts", {})
        print(f"덤프: {name} ({os.path.getsize(raw):,} bytes) 지문 {fp[:12]} · " + " · ".join(f"{k} {v}" for k, v in counts.items()))

        rels = json.loads(gh("release", "list", "-R", repo, "--json", "tagName,createdAt", "-L", "100") or "[]")
        rels.sort(key=lambda r: r["createdAt"], reverse=True)
        if rels and not a.force:
            body = json.loads(gh("release", "view", rels[0]["tagName"], "-R", repo, "--json", "body"))["body"] or ""
            if f"fingerprint: {fp}" in body:
                print(f"변경 없음 — 최신 릴리스 {rels[0]['tagName']} 와 같음. 올리지 않음")
                return

        tag = f"data-{stamp}"
        asset = os.path.join(tmp, name if name.startswith("workblog-dump-") else f"workblog-dump-{stamp}.zip")
        os.replace(raw, asset)
        notes = (f"WorkBlog 데이터 (전체 덤프, 원본 {m.get('sourceInstanceId', '')}, 앱 {m.get('appVersion', '')}, 내보낸 시각 {m.get('exportedAt', '')})\n\n"
                 + "\n".join(f"- {k}: {v}" for k, v in counts.items()) + f"\n\nfingerprint: {fp}\n")
        gh("release", "create", tag, asset, "-R", repo, "--title", f"데이터 {stamp}", "--notes", notes, "--latest")
        print(f"올림: {repo} {tag}")

        for r in rels[max(0, keep - 1):]:   # 새 것 포함 KEEP 개만 남김
            gh("release", "delete", r["tagName"], "-R", repo, "--cleanup-tag", "-y")
            print(f"지움: {r['tagName']}")


if __name__ == "__main__":
    try:
        main()
    except urllib.error.HTTPError as e:
        sys.exit(f"원본 서버 HTTP {e.code}: {e.read().decode('utf-8', 'replace')[:300]}")
