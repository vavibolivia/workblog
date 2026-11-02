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
#   sudo ./install.sh --db-user aam --db-pass '***'   # 같은 서버 DB 를 이 계정으로(없으면 만듦)
#   sudo ./install.sh --upgrade                # 설정은 그대로 두고 프로그램만 교체
#   sudo ./install.sh --uninstall              # 서비스·프로그램 제거 (설정·데이터는 남김)
#
# 옵션: --dir /opt/workblog  --port 8080  --admin-pass <비밀번호>  --public-url http://host:8080  --jar <파일>
#       --pg-pass <비밀번호>   PostgreSQL 관리자(postgres) 비밀번호가 없을 때 만들 값 (기본 postgres — 사내 요청. 5432 를 외부에 열지 말 것)
# Java 21·PostgreSQL 이 없으면 apt/dnf 로 설치하고, PostgreSQL 은 켜고(처음이면 초기화), 계정(workblog·postgres)이 없으면 만든다.
# 이미 설치돼 있으면 설정·데이터를 그대로 두고 프로그램만 바꾼다(업그레이드).
# 끝나면 접속 주소와 초기 관리자 계정(아이디 admin)을 출력한다.
set -euo pipefail

DIR=/opt/workblog; PORT=8080; DB_URL=""; DB_USER=""; DB_PASS=""; ADMIN_PASS=""; PUBLIC_URL=""; MODE=install; PG_PASS=postgres
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
    --pg-pass) PG_PASS="$2"; shift 2 ;;
    --public-url) PUBLIC_URL="$2"; shift 2 ;;
    --jar) JAR="$2"; shift 2 ;;
    --upgrade) MODE=upgrade; shift ;;
    --uninstall) MODE=uninstall; shift ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
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
pgsu() { (cd / && runuser -u postgres -- "$@"); }
# -w: 비밀번호를 묻지 않는다(묻는 설정이면 실패로 보고 아래에서 고친다)
pg_ready() { pgsu psql -w -qtAc 'select 1' >/dev/null 2>&1; }
pg_up() { command -v pg_isready >/dev/null && pg_isready -q 2>/dev/null; }

# 서버는 떠 있는데 postgres 계정이 이 서버 안에서도 비밀번호를 요구하면(pg_hba 의 local 이 md5/scram) 설치를 못 한다.
# → pg_hba.conf 맨 위에 'local all postgres peer' 한 줄을 넣고(백업 남김) 다시 읽게 한다. (Ubuntu·Rocky 기본과 같은 규칙)
ensure_pg_peer() {
  pg_ready && return 0
  pg_up || return 0
  local hba
  for hba in /etc/postgresql/*/main/pg_hba.conf /var/lib/pgsql/data/pg_hba.conf /var/lib/pgsql/*/data/pg_hba.conf; do
    [ -f "$hba" ] || continue
    if ! grep -qE '^local\s+all\s+postgres\s+peer' "$hba"; then
      say "PostgreSQL: postgres 계정의 서버 안 접속을 비밀번호 없이(peer) 허용 — $hba (백업 $hba.bak-workblog)"
      cp "$hba" "$hba.bak-workblog"
      sed -i '1i local   all   postgres   peer   # workblog install.sh' "$hba"
    fi
  done
  systemctl reload postgresql >/dev/null 2>&1 || systemctl reload 'postgresql@*' >/dev/null 2>&1 || true
  command -v pg_ctlcluster >/dev/null && for v in $(ls /etc/postgresql 2>/dev/null); do pg_ctlcluster "$v" main reload >/dev/null 2>&1 || true; done
  for _ in $(seq 1 10); do pg_ready && return 0; sleep 1; done
  fail "postgres 계정으로 접속하지 못했습니다. pg_hba.conf 의 local 규칙을 확인하세요."
}
PG_SUPER_INFO=""

# 필요한 것이 없으면 깐다: Java 21, (같은 서버 DB 를 쓸 때) PostgreSQL 서버·확장. apt(Ubuntu/Debian) 또는 dnf(Rocky/RHEL).
ensure_packages() {
  local need_java=0 need_pg=0
  if ! command -v java >/dev/null || ! java -version 2>&1 | grep -E 'version "(2[1-9]|[3-9][0-9])' >/dev/null; then need_java=1; fi
  if [ -z "$DB_URL" ] && ! command -v postgres >/dev/null && ! ls /usr/lib/postgresql/*/bin/postgres >/dev/null 2>&1; then need_pg=1; fi
  [ $need_java = 0 ] && [ $need_pg = 0 ] && return 0
  if command -v apt-get >/dev/null; then
    say "필요한 패키지 설치 (apt):$( [ $need_java = 1 ] && printf ' Java 21')$( [ $need_pg = 1 ] && printf ' PostgreSQL')"
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq
    local pk="curl ca-certificates"
    [ $need_java = 1 ] && pk="$pk openjdk-21-jre-headless"
    [ $need_pg = 1 ] && pk="$pk postgresql postgresql-contrib"
    # shellcheck disable=SC2086
    apt-get install -y -qq $pk >/dev/null || fail "apt 설치 실패: $pk"
  elif command -v dnf >/dev/null; then
    say "필요한 패키지 설치 (dnf):$( [ $need_java = 1 ] && printf ' Java 21')$( [ $need_pg = 1 ] && printf ' PostgreSQL')"
    local pk=""
    [ $need_java = 1 ] && pk="$pk java-21-openjdk-headless"
    if [ $need_pg = 1 ]; then
      pk="$pk postgresql-server postgresql-contrib"
      # Rocky/RHEL 기본 스트림은 13 — 백업(14) 복원이 되도록 16(없으면 15)으로
      dnf module reset -y -q postgresql >/dev/null 2>&1 || true
      dnf module enable -y -q postgresql:16 >/dev/null 2>&1 || dnf module enable -y -q postgresql:15 >/dev/null 2>&1 || true
    fi
    # shellcheck disable=SC2086
    dnf install -y -q $pk >/dev/null || fail "dnf 설치 실패:$pk"
  else
    fail "apt 도 dnf 도 없어 자동 설치를 못 합니다. Java 21 과 PostgreSQL 을 먼저 설치하세요."
  fi
}

# PostgreSQL 서버를 켜고(처음이면 초기화), 이 서버 안(127.0.0.1)에서 비밀번호로 접속되게 한다.
ensure_postgres_running() {
  ensure_pg_peer
  pg_ready && return 0
  # Rocky/RHEL: 처음엔 데이터 폴더 초기화가 필요
  if command -v postgresql-setup >/dev/null && [ ! -f /var/lib/pgsql/data/PG_VERSION ]; then
    say "PostgreSQL 초기화"
    postgresql-setup --initdb >/dev/null
  fi
  # Rocky/RHEL 기본은 127.0.0.1 접속이 ident(비밀번호 불가) → 비밀번호(scram) 로
  local hba=/var/lib/pgsql/data/pg_hba.conf
  if [ -f "$hba" ] && grep -qE '^host\s+all\s+all\s+(127\.0\.0\.1/32|::1/128)\s+ident' "$hba"; then
    cp "$hba" "$hba.bak-workblog"
    sed -i -E 's/^(host\s+all\s+all\s+(127\.0\.0\.1\/32|::1\/128)\s+)ident/\1scram-sha-256/' "$hba"
  fi
  systemctl enable --now postgresql >/dev/null 2>&1 || true
  systemctl reload postgresql >/dev/null 2>&1 || true
  # systemd 가 서비스를 못 켜는 환경(컨테이너 등)이면 직접 켠다
  if ! pg_ready; then
    if command -v pg_ctlcluster >/dev/null; then
      local v; v=$(ls /etc/postgresql 2>/dev/null | sort -V | tail -1)
      [ -n "$v" ] && pg_ctlcluster "$v" main start >/dev/null 2>&1 || true
    elif [ -f /var/lib/pgsql/data/PG_VERSION ]; then
      pgsu pg_ctl -D /var/lib/pgsql/data -l /var/lib/pgsql/logfile start >/dev/null 2>&1 || true
    fi
  fi
  for _ in $(seq 1 30); do pg_up && break; sleep 1; done
  ensure_pg_peer
  pg_ready && return 0
  fail "PostgreSQL 이 시작되지 않았습니다 (systemctl status postgresql)."
}

# postgres 관리자 계정에 비밀번호가 없으면 만든다(기본 postgres / postgres, --pg-pass 로 지정). 이미 있으면 건드리지 않는다.
ensure_pg_superuser_password() {
  local has
  has=$(pgsu psql -w -qtAc "SELECT rolpassword IS NOT NULL FROM pg_authid WHERE rolname = 'postgres'" 2>/dev/null || true)
  if [ "$has" = t ]; then PG_SUPER_INFO="postgres (기존 비밀번호 그대로)"; return 0; fi
  # 비밀번호는 psql 변수(:'pw')로 넘겨 따옴표가 들어 있어도 안전하게
  if [ -z "$has" ]; then
    echo "CREATE ROLE postgres LOGIN SUPERUSER PASSWORD :'pw';" | pgsu psql -v ON_ERROR_STOP=1 -q -v pw="$PG_PASS" >/dev/null
  else
    echo "ALTER ROLE postgres PASSWORD :'pw';" | pgsu psql -v ON_ERROR_STOP=1 -q -v pw="$PG_PASS" >/dev/null
  fi
  PG_SUPER_INFO="postgres / ${PG_PASS}"
}

# 0) 옆에 jar 가 없으면 온라인 설치: 필요한 패키지를 apt 로 깔고 Releases 에서 묶음을 받는다
if [ ! -f "$JAR" ]; then
  command -v curl >/dev/null || fail "workblog.jar 가 없고 curl 도 없습니다. Releases 의 linux.tar.gz 를 받아 푼 폴더에서 실행하세요(README)."

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

# 1) 없는 것 설치 (Java 21 · PostgreSQL) — 업그레이드·외부 DB 면 PostgreSQL 은 건드리지 않는다
if [ "$MODE" = upgrade ] || [ -f "$CONF" ]; then DB_URL="${DB_URL:-keep}"; fi
ensure_packages
[ "$DB_URL" = keep ] && DB_URL=""
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
    ensure_postgres_running
    ensure_pg_superuser_password
    # DB 계정: --db-user/--db-pass 를 주면 그 값(없으면 만들고, 있으면 비밀번호를 맞춤), 안 주면 workblog + 난수
    DB_USER="${DB_USER:-workblog}"; DB_PASS="${DB_PASS:-$(rand 24)}"
    # 이름·비밀번호는 psql 변수로 넘긴다(특수문자 안전)
    pgsu psql -v ON_ERROR_STOP=1 -q -v u="$DB_USER" -v p="$DB_PASS" <<'SQL'
SELECT NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'u') AS need_role \gset
\if :need_role
  CREATE ROLE :"u" LOGIN PASSWORD :'p';
\else
  ALTER ROLE :"u" LOGIN PASSWORD :'p';
\endif
SQL
    pgsu psql -tAc "SELECT 1 FROM pg_database WHERE datname='workblog'" | grep 1 >/dev/null \
      || pgsu createdb -O "$DB_USER" workblog
    pgsu psql -q -v u="$DB_USER" -c 'ALTER DATABASE workblog OWNER TO :"u"' >/dev/null 2>&1       || echo 'ALTER DATABASE workblog OWNER TO :"u";' | pgsu psql -q -v ON_ERROR_STOP=1 -v u="$DB_USER" >/dev/null
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
    초기 비밀번호: ${ADMIN_INFO}${PG_SUPER_INFO:+
    DB 관리자   : ${PG_SUPER_INFO}}
    설정 파일   : ${CONF}
    로그        : journalctl -u workblog -f
  └──────────────────────────────────────────────┘
  처음 로그인한 뒤 '내 정보'에서 비밀번호를 바꾸세요.
EOF
