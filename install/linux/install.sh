#!/usr/bin/env bash
# WorkBlog 설치 (Linux, systemd)
#
# 인터넷 되는 Ubuntu/Debian — 명령 한 줄(Java 21·PostgreSQL 을 apt 로 깔고 GitHub Releases 에서 받아 설치):
#   curl -fsSL https://raw.githubusercontent.com/vavibolivia/workblog/main/install/linux/install.sh | sudo bash
#   ... | sudo bash -s -- --port 8080 --public-url http://host:8080      (옵션 지정)
#   ... | sudo WORKBLOG_VERSION=0.1.1 bash                              (특정 버전)
#
# 인터넷 안 되는 서버 — 받은 묶음(linux.zip/tar.gz)을 풀고 그 폴더에서(workblog.jar 가 옆에 있으면 그것을 쓴다):
#   sudo ./install.sh                          # 같은 서버의 PostgreSQL 에 DB 를 만들고 설치
#   sudo ./install.sh --db-url jdbc:postgresql://db:5432/workblog --db-user wb --db-pass '***'
#   sudo ./install.sh --upgrade                # 설정은 그대로 두고 프로그램만 교체
#   sudo ./install.sh --uninstall              # 서비스·프로그램 제거 (설정·데이터는 남김)
#
# 옵션: --dir /opt/workblog  --port 8080  --admin-pass <비밀번호>  --public-url http://host:8080  --jar <파일>
# 이미 설치돼 있으면 설정·데이터를 그대로 두고 프로그램만 바꾼다(업그레이드).
# 끝나면 접속 주소와 초기 관리자 계정(아이디 admin)을 출력한다.
set -euo pipefail

DIR=/opt/workblog; PORT=8080; DB_URL=""; DB_USER=""; DB_PASS=""; ADMIN_PASS=""; PUBLIC_URL=""; MODE=install
REPO="${WORKBLOG_REPO:-vavibolivia/workblog}"
VER="${WORKBLOG_VERSION:-}"
# curl | bash 로 실행하면 $0 이 bash 라 현재 폴더를 본다
HERE="$(cd "$(dirname "$0")" && pwd)"
JAR="$HERE/workblog.jar"

while [ $# -gt 0 ]; do
  case "$1" in
    --dir) DIR="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --db-url) DB_URL="$2"; shift 2 ;;
    --db-user) DB_USER="$2"; shift 2 ;;
    --db-pass) DB_PASS="$2"; shift 2 ;;
    --admin-pass) ADMIN_PASS="$2"; shift 2 ;;
    --public-url) PUBLIC_URL="$2"; shift 2 ;;
    --jar) JAR="$2"; shift 2 ;;
    --upgrade) MODE=upgrade; shift ;;
    --uninstall) MODE=uninstall; shift ;;
    -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
    *) echo "알 수 없는 옵션: $1"; exit 2 ;;
  esac
done

say()  { printf '\033[1;34m==\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m오류:\033[0m %s\n' "$*" >&2; exit 1; }
# 난수 문자열. 'tr </dev/urandom | head' 는 pipefail 에서 SIGPIPE(141)로 실패하므로 유한한 입력을 쓴다
rand() { local s=""; while [ ${#s} -lt "$1" ]; do s+=$(head -c 96 /dev/urandom | tr -dc 'A-Za-z0-9'); done; printf '%s' "${s:0:$1}"; }

[ "$(id -u)" -eq 0 ] || fail "root 로 실행하세요 (sudo ./install.sh)"
command -v systemctl >/dev/null || fail "systemd 가 필요합니다. 수동 실행은 README 의 '직접 실행' 절을 보세요."
CONF="$DIR/config/workblog.properties"
UNIT=/etc/systemd/system/workblog.service

if [ "$MODE" = uninstall ]; then
  say "서비스 중지·제거"
  systemctl disable --now workblog 2>/dev/null || true
  rm -f "$UNIT"; systemctl daemon-reload
  rm -f "$DIR/workblog.jar"
  say "제거 완료. 설정($CONF)과 데이터($DIR/data), DB 는 남겼습니다. 필요 없으면 직접 지우세요."
  exit 0
fi

# postgres 계정으로 실행. 지금 폴더(홈 등)에 postgres 가 못 들어가 'could not change directory … Permission denied'
# 경고가 나지 않도록 / 에서 실행한다(파일은 절대 경로나 리다이렉트로만 넘긴다).
pgsu() { (cd / && sudo -u postgres "$@"); }

# 0) 옆에 jar 가 없으면 온라인 설치: 필요한 패키지를 apt 로 깔고 Releases 에서 묶음을 받는다
if [ ! -f "$JAR" ]; then
  command -v apt-get >/dev/null || fail "workblog.jar 가 없고 apt 도 없습니다. Releases 의 linux.tar.gz 를 받아 푼 폴더에서 실행하세요(README)."
  say "필요한 패키지 설치 (Java 21 · PostgreSQL)"
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq
  apt-get install -y -qq curl ca-certificates tar openjdk-21-jre-headless postgresql >/dev/null
  systemctl enable --now postgresql >/dev/null 2>&1 || true
  for _ in $(seq 1 30); do pgsu psql -qtAc 'select 1' >/dev/null 2>&1 && break; sleep 1; done
  pgsu psql -qtAc 'select 1' >/dev/null 2>&1 || fail "PostgreSQL 이 시작되지 않았습니다 (systemctl status postgresql)."

  if [ -z "$VER" ]; then
    say "최신 버전 확인"
    VER=$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" | sed -n 's/.*"tag_name": *"v\{0,1\}\([^"]*\)".*/\1/p' | head -1)
    [ -n "$VER" ] || fail "최신 릴리스를 찾지 못했습니다. WORKBLOG_VERSION=<버전> 으로 지정하세요."
  fi
  VER="${VER#v}"
  NAME="workblog-$VER-linux.tar.gz"
  BASE="${WORKBLOG_BASE_URL:-https://github.com/$REPO/releases/download/v$VER}"   # 시험용으로 다른 위치(file:// 등) 지정 가능
  TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
  say "내려받기: $NAME"
  curl -fSL --progress-bar -o "$TMP/$NAME" "$BASE/$NAME"
  curl -fsSL -o "$TMP/SHA256SUMS.txt" "$BASE/SHA256SUMS.txt"
  say "무결성 확인 (SHA-256)"
  (cd "$TMP" && grep " $NAME\$" SHA256SUMS.txt | sha256sum -c --quiet -) || fail "체크섬이 맞지 않습니다. 다시 받아 보세요."
  tar -xzf "$TMP/$NAME" -C "$TMP"
  JAR="$TMP/workblog-$VER/workblog.jar"
  [ -f "$JAR" ] || fail "묶음에 workblog.jar 가 없습니다: $NAME"
fi

# 1) Java 21
say "Java 21 확인"
if ! command -v java >/dev/null || ! java -version 2>&1 | grep -E 'version "(2[1-9]|[3-9][0-9])' >/dev/null; then
  fail "Java 21 이상이 필요합니다.
  Ubuntu/Debian: sudo apt install -y openjdk-21-jre-headless
  RHEL/Rocky:    sudo dnf install -y java-21-openjdk-headless"
fi

id workblog >/dev/null 2>&1 || useradd --system --home "$DIR" --shell /usr/sbin/nologin workblog
mkdir -p "$DIR/config" "$DIR/data/files" "$DIR/logs" "$DIR/static-extra"

if [ "$MODE" = upgrade ] || [ -f "$CONF" ]; then
  [ -f "$CONF" ] || fail "업그레이드할 설정이 없습니다: $CONF"
  say "기존 설정 유지: $CONF"
  ADMIN_INFO="(기존 비밀번호 그대로)"
else
  # 2) DB
  if [ -z "$DB_URL" ]; then
    say "같은 서버의 PostgreSQL 에 DB 생성"
    command -v psql >/dev/null || fail "psql 이 없습니다. PostgreSQL 을 설치하거나 --db-url/--db-user/--db-pass 로 외부 DB 를 지정하세요.
  Ubuntu/Debian: sudo apt install -y postgresql postgresql-contrib"
    DB_USER=workblog; DB_PASS="$(rand 24)"
    pgsu psql -v ON_ERROR_STOP=1 -q <<SQL
DO \$\$ BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '${DB_USER}') THEN CREATE ROLE ${DB_USER} LOGIN PASSWORD '${DB_PASS}';
  ELSE ALTER ROLE ${DB_USER} PASSWORD '${DB_PASS}'; END IF;
END \$\$;
SQL
    pgsu psql -tAc "SELECT 1 FROM pg_database WHERE datname='workblog'" | grep 1 >/dev/null \
      || pgsu createdb -O "$DB_USER" workblog
    pgsu psql -q -d workblog -c "CREATE EXTENSION IF NOT EXISTS pg_trgm;"
    DB_URL="jdbc:postgresql://localhost:5432/workblog"
  else
    [ -n "$DB_USER" ] && [ -n "$DB_PASS" ] || fail "--db-url 을 쓰면 --db-user, --db-pass 도 필요합니다. (DB 에 pg_trgm 확장이 있어야 합니다)"
  fi

  # 3) 설정 파일
  [ -n "$ADMIN_PASS" ] || ADMIN_PASS="$(rand 14)"
  [ -n "$PUBLIC_URL" ] || PUBLIC_URL="http://$(hostname -I 2>/dev/null | awk '{print $1}'):$PORT"
  say "설정 파일 작성: $CONF"
  umask 077
  cat > "$CONF" <<EOF
# WorkBlog 설정 (install.sh 작성). 바꾼 뒤: sudo systemctl restart workblog
SERVER_PORT=$PORT
DB_URL=$DB_URL
DB_USERNAME=$DB_USER
DB_PASSWORD=$DB_PASS
JWT_SECRET=$(rand 48)
# 초기 관리자: 사용자가 하나도 없을 때 한 번만 만들어진다.
ADMIN_USERNAME=admin
ADMIN_PASSWORD=$ADMIN_PASS
STORAGE_DIR=$DIR/data/files
EXTRA_STATIC_DIR=$DIR/static-extra
PUBLIC_BASE_URL=$PUBLIC_URL
SIGNUP_ENABLED=true
SIGNUP_APPROVAL=false
# 비워 두면 static-extra/drawio 가 있을 때 자체 호스팅(/drawio), 없으면 공개 embed.diagrams.net (README 참고)
DRAWIO_URL=
EOF
  umask 022
  ADMIN_INFO="$ADMIN_PASS"
fi

# 4) 프로그램
say "프로그램 설치: $DIR/workblog.jar"
[ -f "$DIR/workblog.jar" ] && cp "$DIR/workblog.jar" "$DIR/workblog.jar.bak"
install -m 0644 "$JAR" "$DIR/workblog.jar"
chown -R workblog:workblog "$DIR"
chmod 600 "$CONF"

# 5) 서비스
say "systemd 서비스 등록"
cat > "$UNIT" <<EOF
[Unit]
Description=WorkBlog
After=network-online.target postgresql.service
Wants=network-online.target

[Service]
User=workblog
WorkingDirectory=$DIR
Environment=WORKBLOG_CONFIG=$CONF
ExecStart=$(command -v java) -Xms256m -Xmx768m -jar $DIR/workblog.jar
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable workblog >/dev/null 2>&1
systemctl restart workblog

PORT_NOW="$(grep -E '^SERVER_PORT=' "$CONF" | cut -d= -f2)"
say "기동 확인 (최대 90초)"
for i in $(seq 1 90); do
  if curl -fs "http://127.0.0.1:${PORT_NOW}/api/health" >/dev/null 2>&1; then OK=1; break; fi
  sleep 1
done
[ "${OK:-}" = 1 ] || fail "기동하지 않았습니다. 로그: journalctl -u workblog -n 80"

URL="$(grep -E '^PUBLIC_BASE_URL=' "$CONF" | cut -d= -f2)"
cat <<EOF

  ┌──────────────────────────────────────────────┐
    WorkBlog 설치 완료
    주소        : ${URL}
    초기 아이디 : admin
    초기 비밀번호: ${ADMIN_INFO}
    설정 파일   : ${CONF}
    로그        : journalctl -u workblog -f
  └──────────────────────────────────────────────┘
  처음 로그인한 뒤 '내 정보'에서 비밀번호를 바꾸세요.
EOF
