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

# zip·tar.gz(리눅스): 압축 풀고 install.sh 하나만 실행하면 끝 (사용자 지시 "install.sh 만 하도록")
README = f"""WorkBlog {VERSION}

설치: 압축을 풀고 이 폴더에서 아래 한 줄만 실행하세요.

    sudo ./install.sh

필요한 것(podman·PostgreSQL)은 install.sh 가 알아서 설치합니다.
image/ 폴더가 있는 묶음(offline)은 인터넷 없이 설치됩니다.
데이터는 들어 있지 않습니다. 이미 설치된 서버에서 실행하면 프로그램만 바뀌고 글·첨부·DB 는 그대로 둡니다.
초기 아이디: admin  (비밀번호는 설치 마지막 화면에 나옵니다)
"""

README_DOCKER = f"""WorkBlog {VERSION} (Docker)

설치: .env.example 을 .env 로 복사해 값을 고친 뒤  docker compose up -d
초기 아이디: admin  (비밀번호는 .env 의 ADMIN_PASSWORD)
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
               ("README.txt", README_DOCKER.encode("utf-8"), 0o644)],
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
