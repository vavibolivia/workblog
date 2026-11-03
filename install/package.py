#!/usr/bin/env python3
"""
릴리스 묶음 만들기 (Linux · Docker). 먼저 `bash install/build.sh` 로 jar 를 만든다.

    python install/package.py 0.1.1   → install/dist/workblog-0.1.1-linux.tar.gz, -linux.zip(같은 내용), -docker.tar.gz, SHA256SUMS.txt
    python install/package.py 0.1.1 --image corretto21.tar
                                      → 위에 더해 -linux-offline.zip (image/ 에 컨테이너 이미지 tar 동봉 — 인터넷 없는 서버용)
       이미지 tar 만들기: podman pull docker.io/library/amazoncorretto:21-alpine && podman save -o corretto21.tar docker.io/library/amazoncorretto:21-alpine
"""
import glob, hashlib, io, os, sys, tarfile, zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
args = sys.argv[1:]
IMAGE_TAR = ""
if "--image" in args:
    i = args.index("--image"); IMAGE_TAR = args[i + 1]; del args[i:i + 2]
    if not os.path.isfile(IMAGE_TAR):
        sys.exit(f"이미지 tar 가 없습니다: {IMAGE_TAR}")
VERSION = args[0] if args else "0.0.0"
JAR = next(iter(sorted(glob.glob(os.path.join(ROOT, "backend", "target", "workblog-backend-*.jar")))), "")
DIST = os.path.join(ROOT, "install", "dist")
NAME = f"workblog-{VERSION}"

if not os.path.isfile(JAR):
    sys.exit(f"jar 가 없습니다: {JAR}  (먼저 bash install/build.sh)")
os.makedirs(DIST, exist_ok=True)

README = f"""WorkBlog {VERSION}

설치 방법은 https://github.com/vavibolivia/workblog 의 README 를 보세요.
  Linux  : sudo ./install.sh        (podman 컨테이너로 실행. podman·PostgreSQL 이 없으면 apt/dnf 로 설치,
                                     image/ 폴더가 있으면 인터넷 없이 그 이미지를 씀)
           인터넷 되는 Ubuntu 는 한 줄 설치: curl -fsSL https://raw.githubusercontent.com/vavibolivia/workblog/main/install/linux/install.sh | sudo bash
  Docker : .env.example → .env 로 복사·수정 후 docker compose up -d
초기 아이디: admin  (비밀번호는 설치 마지막 화면 / 설정 파일의 ADMIN_PASSWORD)
"""


def read(p):
    with open(os.path.join(ROOT, p), "rb") as f:
        return f.read()


def lf(b):
    return b.replace(b"\r\n", b"\n")


bundles = {
    "linux": [("workblog.jar", read(os.path.relpath(JAR, ROOT)), 0o644), ("install.sh", lf(read("install/linux/install.sh")), 0o755), ("README.txt", README.encode("utf-8"), 0o644)],
    "docker": [("workblog.jar", read(os.path.relpath(JAR, ROOT)), 0o644), ("Dockerfile", lf(read("install/docker/Dockerfile")), 0o644),
               ("docker-compose.yml", lf(read("install/docker/docker-compose.yml")), 0o644), (".env.example", lf(read("install/docker/.env.example")), 0o644),
               ("README.txt", README.encode("utf-8"), 0o644)],
}

out = []
for kind, files in bundles.items():
    path = os.path.join(DIST, f"{NAME}-{kind}.tar.gz")
    with tarfile.open(path, "w:gz") as t:
        for n, data, mode in files:
            info = tarfile.TarInfo(f"{NAME}/{n}")
            info.size, info.mode = len(data), mode
            t.addfile(info, io.BytesIO(data))
    out.append(path)
    if kind == "linux":  # 다른 서버로 옮기기 쉬운 zip (unzip 해도 install.sh 실행 권한 유지)
        zpath = os.path.join(DIST, f"{NAME}-linux.zip")
        with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
            for n, data, mode in files:
                zi = zipfile.ZipInfo(f"{NAME}/{n}", date_time=(2026, 1, 1, 0, 0, 0))
                zi.external_attr = (0o100000 | mode) << 16
                zi.compress_type = zipfile.ZIP_DEFLATED
                z.writestr(zi, data)
        out.append(zpath)
        if IMAGE_TAR:  # 인터넷 없는 서버용: 이미지 tar 동봉(이미 압축된 층이라 저장만)
            opath = os.path.join(DIST, f"{NAME}-linux-offline.zip")
            with zipfile.ZipFile(opath, "w", zipfile.ZIP_DEFLATED, allowZip64=True) as z:
                for n, data, mode in files:
                    zi = zipfile.ZipInfo(f"{NAME}/{n}", date_time=(2026, 1, 1, 0, 0, 0))
                    zi.external_attr = (0o100000 | mode) << 16
                    zi.compress_type = zipfile.ZIP_DEFLATED
                    z.writestr(zi, data)
                z.write(IMAGE_TAR, f"{NAME}/image/{os.path.basename(IMAGE_TAR)}", compress_type=zipfile.ZIP_STORED)
            out.append(opath)

with open(os.path.join(DIST, "SHA256SUMS.txt"), "w", encoding="utf-8", newline="\n") as f:
    for p in out:
        f.write(f"{hashlib.sha256(open(p, 'rb').read()).hexdigest()}  {os.path.basename(p)}\n")
for p in out:
    print(os.path.relpath(p, ROOT), os.path.getsize(p))
