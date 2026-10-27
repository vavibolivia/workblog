#!/usr/bin/env python3
"""
릴리스 묶음 만들기 (Linux · Windows · Docker). 먼저 `bash build.sh` 로 jar 를 만든다.

    python scripts/package.py 0.1.0        → dist/workblog-0.1.0-linux.tar.gz, -windows.zip, -docker.tar.gz, SHA256SUMS.txt
"""
import hashlib, io, os, sys, tarfile, zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VERSION = sys.argv[1] if len(sys.argv) > 1 else "0.0.0"
JAR = os.path.join(ROOT, "backend", "target", "workblog-backend-0.1.0.jar")
DIST = os.path.join(ROOT, "dist")
NAME = f"workblog-{VERSION}"

if not os.path.isfile(JAR):
    sys.exit(f"jar 가 없습니다: {JAR}  (먼저 bash build.sh)")
os.makedirs(DIST, exist_ok=True)

README = f"""WorkBlog {VERSION}

설치 방법은 https://github.com/vavibolivia/workblog 의 README 를 보세요.
  Linux  : sudo ./install.sh
  Windows: 관리자 PowerShell 에서 .\\install.ps1
  Docker : .env.example → .env 로 복사·수정 후 docker compose up -d
초기 아이디: admin  (비밀번호는 설치 마지막 화면 / 설정 파일의 ADMIN_PASSWORD)
"""


def read(p):
    with open(os.path.join(ROOT, p), "rb") as f:
        return f.read()


def lf(b):
    return b.replace(b"\r\n", b"\n")


bundles = {
    "linux": [("workblog.jar", read(os.path.relpath(JAR, ROOT)), 0o644), ("install.sh", lf(read("install/linux/install.sh")), 0o755),
              ("README.txt", README.encode("utf-8"), 0o644)],
    # Windows PowerShell 5.1 은 BOM 없는 UTF-8 을 ANSI 로 읽어 한글이 깨진다 → BOM 을 붙이고 CRLF 로
    "windows": [("workblog.jar", read(os.path.relpath(JAR, ROOT)), 0o644),
                ("install.ps1", b"\xef\xbb\xbf" + lf(read("install/windows/install.ps1")).lstrip(b"\xef\xbb\xbf").replace(b"\n", b"\r\n"), 0o644),
                ("README.txt", b"\xef\xbb\xbf" + README.replace("\n", "\r\n").encode("utf-8"), 0o644)],
    "docker": [("workblog.jar", read(os.path.relpath(JAR, ROOT)), 0o644), ("Dockerfile", lf(read("install/docker/Dockerfile")), 0o644),
               ("docker-compose.yml", lf(read("install/docker/docker-compose.yml")), 0o644), (".env.example", lf(read("install/docker/.env.example")), 0o644),
               ("README.txt", README.encode("utf-8"), 0o644)],
}

out = []
for kind, files in bundles.items():
    if kind == "windows":
        path = os.path.join(DIST, f"{NAME}-windows.zip")
        with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
            for n, data, _ in files:
                z.writestr(f"{NAME}/{n}", data)
    else:
        path = os.path.join(DIST, f"{NAME}-{kind}.tar.gz")
        with tarfile.open(path, "w:gz") as t:
            for n, data, mode in files:
                info = tarfile.TarInfo(f"{NAME}/{n}")
                info.size, info.mode = len(data), mode
                t.addfile(info, io.BytesIO(data))
    out.append(path)

with open(os.path.join(DIST, "SHA256SUMS.txt"), "w", encoding="utf-8", newline="\n") as f:
    for p in out:
        f.write(f"{hashlib.sha256(open(p, 'rb').read()).hexdigest()}  {os.path.basename(p)}\n")
for p in out:
    print(os.path.relpath(p, ROOT), os.path.getsize(p))
