#!/usr/bin/env bash
# WorkBlog 설치 (Linux, systemd)
#
#   sudo ./install.sh                          # 같은 서버의 PostgreSQL 에 DB 를 만들고 설치
#   sudo ./install.sh --db-url jdbc:postgresql://db:5432/workblog --db-user wb --db-pass '***'
#   sudo ./install.sh --upgrade                # 설정은 그대로 두고 프로그램만 교체
#   sudo ./install.sh --uninstall              # 서비스·프로그램 제거 (설정·데이터는 남김)
#
# 옵션: --dir /opt/workblog  --port 8080  --admin-pass <비밀번호>  --public-url http://host:8080
#
# 끝나면 접속 주소와 초기 관리자 계정(아이디 admin)을 출력한다.
set -euo pipefail

DIR=/opt/workblog; PORT=8080; DB_URL=""; DB_USER=""; DB_PASS=""; ADMIN_PASS=""; PUBLIC_URL=""; MODE=install
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
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "알 수 없는 옵션: $1"; exit 2 ;;
  esac
done

say()  { printf '\033[1;34m==\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m오류:\033[0m %s\n' "$*" >&2; exit 1; }
rand() { tr -dc 'A-Za-z0-9' </dev/urandom | head -c "$1"; }

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

[ -f "$JAR" ] || fail "workblog.jar 를 찾을 수 없습니다: $JAR"

# 1) Java 21
say "Java 21 확인"
if ! command -v java >/dev/null || ! java -version 2>&1 | grep -qE 'version "(2[1-9]|[3-9][0-9])'; then
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
    sudo -u postgres psql -v ON_ERROR_STOP=1 -q <<SQL
DO \$\$ BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '${DB_USER}') THEN CREATE ROLE ${DB_USER} LOGIN PASSWORD '${DB_PASS}';
  ELSE ALTER ROLE ${DB_USER} PASSWORD '${DB_PASS}'; END IF;
END \$\$;
SQL
    sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='workblog'" | grep -q 1 \
      || sudo -u postgres createdb -O "$DB_USER" workblog
    sudo -u postgres psql -q -d workblog -c "CREATE EXTENSION IF NOT EXISTS pg_trgm;"
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
# 폐쇄망이면 draw.io 를 직접 올리고 주소를 바꾼다(README 참고)
DRAWIO_URL=https://embed.diagrams.net
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
