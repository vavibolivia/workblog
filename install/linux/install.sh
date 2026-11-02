#!/usr/bin/env bash
# WorkBlog 설치 (Linux, systemd) — 처음 설치·다시 설치·업그레이드 모두 이 하나로
#
# 인터넷 되는 Ubuntu/Debian — 명령 한 줄(Java 21·PostgreSQL 을 apt 로 깔고 GitHub Releases 에서 받아 설치):
#   curl -fsSL https://raw.githubusercontent.com/vavibolivia/workblog/main/install/linux/install.sh | sudo bash
#   ... | sudo bash -s -- --port 8080 --public-url http://host:8080      (옵션 지정)
#   ... | sudo WORKBLOG_VERSION=0.1.1 bash                              (특정 버전)
#
# 받은 묶음을 푼 폴더에서(workblog.jar 가 옆에 있으면 그것을 쓴다, 옆에 data/ 가 있으면 데이터도 넣는다):
#   sudo ./install.sh                                   # 같은 서버의 PostgreSQL 에 DB 를 만들고 설치
#   sudo ./install.sh --db-user aam --db-pass '***'     # 같은 서버 DB 를 이 계정으로(없으면 만듦)
#   sudo ./install.sh --db-url jdbc:postgresql://db:5432/workblog --db-user wb --db-pass '***'   # 다른 서버 DB
#   sudo ./install.sh --uninstall                       # 서비스·프로그램 제거 (설정·데이터·DB 는 남김)
#
# 옵션: --dir /opt/workblog  --port 8080  --admin-pass <비밀번호>  --public-url http://host:8080  --jar <파일>
#       --data <폴더>    넣을 데이터(db_*.dump + files/). 기본: 옆의 data/
#       --no-data        데이터를 넣지 않음
#       --force-data     DB 에 글이 있어도 데이터로 바꿈(바꾸기 전 DB 를 /var/backups/workblog 에 덤프, root 만 읽음)
#       --pg-pass <비밀번호>  PostgreSQL 관리자(postgres) 비밀번호가 없을 때 만들 값 (기본 postgres — 사내 요청. 5432 를 외부에 열지 말 것)
#
# 상황별로 알아서 처리한다:
#   - Java 21 이 없거나 낮은 버전만 있으면 설치(apt/dnf), 여러 버전이면 21 이상을 골라 쓴다
#   - PostgreSQL 이 없으면 설치·초기화, 꺼져 있으면 켜고, 포트를 다른 프로그램이 쓰고 있으면 빈 포트로 옮겨 켠다
#   - postgres 계정이 서버 안에서도 비밀번호를 요구하면 peer 접속을 허용(pg_hba.conf 백업 남김)
#   - DB 계정·DB 가 없으면 만들고, 있으면 비밀번호·소유자를 맞춘다. 127.0.0.1 비밀번호 접속이 막혀 있으면 허용
#   - 데이터: DB 가 비었거나 글이 0건이면(지난 설치가 중간에 멈춘 경우 등) 넣는다. 글이 있으면 그대로 둔다(--force-data 로 바꿈)
#   - WorkBlog 이 이미 설치·실행 중이면 멈추고 설정은 유지(주어진 옵션만 반영)한 채 프로그램을 바꾸고 다시 켠다
#   - 웹 포트를 다른 프로그램이 쓰고 있으면 새 설치는 다음 빈 포트로, 기존 설치는 무엇이 쓰는지 알려 주고 멈춘다
# 끝나면 접속 주소와 관리자 계정(아이디 admin)을 출력한다.
set -euo pipefail

# root 가 아니면 처음부터 sudo 로 다시 실행한다(같은 옵션 그대로). curl | bash 처럼 파일이 없으면 안내만.
if [ "$(id -u)" -ne 0 ]; then
  if [ -f "$0" ] && command -v sudo >/dev/null; then
    echo "== root 권한이 필요해 sudo 로 다시 실행합니다"
    exec sudo bash "$0" "$@"
  fi
  echo "오류: root 로 실행하세요 (sudo bash install.sh 또는 ... | sudo bash)" >&2; exit 1
fi

DIR=/opt/workblog; PORT=8080; PORT_SET=0; DB_URL=""; DB_USER=""; DB_PASS=""; ADMIN_PASS=""; PUBLIC_URL=""; MODE=install
PG_PASS=postgres; DATA_DIR=""; NO_DATA=0; FORCE_DATA=0
REPO="${WORKBLOG_REPO:-vavibolivia/workblog}"
VER="${WORKBLOG_VERSION:-}"
# curl | bash 로 실행하면 $0 이 bash 라 현재 폴더를 본다
HERE="$(cd "$(dirname "$0")" && pwd)"
JAR="$HERE/workblog.jar"

while [ $# -gt 0 ]; do
  case "$1" in
    --dir) DIR="$2"; shift 2 ;;
    --port) PORT="$2"; PORT_SET=1; shift 2 ;;
    --db-url) DB_URL="$2"; shift 2 ;;
    --db-user) DB_USER="$2"; shift 2 ;;
    --db-pass) DB_PASS="$2"; shift 2 ;;
    --admin-pass) ADMIN_PASS="$2"; shift 2 ;;
    --pg-pass) PG_PASS="$2"; shift 2 ;;
    --public-url) PUBLIC_URL="$2"; shift 2 ;;
    --jar) JAR="$2"; shift 2 ;;
    --data) DATA_DIR="$2"; shift 2 ;;
    --no-data) NO_DATA=1; shift ;;
    --force-data) FORCE_DATA=1; shift ;;
    --upgrade) shift ;;   # 예전 옵션: 이제 설치가 알아서 업그레이드로 처리
    --uninstall) MODE=uninstall; shift ;;
    -h|--help) sed -n '2,32p' "$0"; exit 0 ;;
    *) echo "알 수 없는 옵션: $1"; exit 2 ;;
  esac
done
[ -n "$DATA_DIR" ] || DATA_DIR="$HERE/data"

say()  { printf '\033[1;34m==\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m주의:\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m오류:\033[0m %s\n' "$*" >&2; exit 1; }
# 난수 문자열. 'tr </dev/urandom | head' 는 pipefail 에서 SIGPIPE(141)로 실패하므로 유한한 입력을 쓴다
rand() { local s=""; while [ ${#s} -lt "$1" ]; do s+=$(head -c 96 /dev/urandom | tr -dc 'A-Za-z0-9'); done; printf '%s' "${s:0:$1}"; }
# 그 포트를 누가 듣고 있는지(ss 가 없으면 접속해 본다)
port_busy() {
  if command -v ss >/dev/null; then ss -ltnH "( sport = :$1 )" 2>/dev/null | grep -q .
  else (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; fi
}
port_owner() { command -v ss >/dev/null && ss -ltnpH "( sport = :$1 )" 2>/dev/null | sed -n 's/.*users:(("\([^"]*\)".*/\1/p' | head -1; }
free_port_from() { local p="$1"; while port_busy "$p"; do p=$((p + 1)); [ "$p" -lt $(( $1 + 50 )) ] || fail "$1 근처에 빈 포트가 없습니다."; done; echo "$p"; }

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

# ---------- 설정 파일 읽기·고치기 (값에 ! & / \ 가 있어도 그대로) ----------
conf_get() { [ -f "$CONF" ] && grep -E "^$1=" "$CONF" | tail -1 | cut -d= -f2- || true; }
conf_set() {
  no_link "$CONF"
  local tmp; tmp=$(mktemp)
  K="$1" V="$2" awk 'BEGIN{k=ENVIRON["K"]; v=ENVIRON["V"]; d=0} index($0, k "=") == 1 {print k "=" v; d=1; next} {print} END{if(!d) print k "=" v}' "$CONF" > "$tmp"
  cat "$tmp" > "$CONF"; rm -f "$tmp"
}
# root 가 쓰는 곳이 서비스 계정이 바꿔 둔 심볼릭 링크면(다른 파일을 덮어쓰게 됨) 멈춘다
no_link() { local x; for x in "$@"; do [ ! -L "$x" ] || fail "심볼릭 링크라 쓰지 않습니다: $x (직접 확인 후 지우세요)"; done; }

# ---------- PostgreSQL ----------
PG_KIND=""; PG_VER=""; PG_CLUSTER=""; PG_DATA=""; PG_CONF_FILE=""; PG_HBA=""; PG_PORT=5432; PG_SUPER_INFO=""
# postgres 계정으로 실행. 지금 폴더(홈 등)에 postgres 가 못 들어가 'could not change directory … Permission denied'
# 경고가 나지 않도록 / 에서 실행한다(파일은 리다이렉트로만 넘긴다). 포트·클러스터를 고정해 둔다.
pgsu() { (cd / && runuser -u postgres -- env PGPORT="$PG_PORT" ${PG_VER:+PGCLUSTER="$PG_VER/$PG_CLUSTER"} "$@"); }
# -w: 비밀번호를 묻지 않는다(묻는 설정이면 실패로 보고 고친다)
pg_ready() { pgsu psql -w -qtAc 'select 1' >/dev/null 2>&1; }
pg_up() { pgsu pg_isready -q >/dev/null 2>&1; }
# psql 변수 지정 줄(\set 이름 '값'). 비밀번호를 명령줄(ps 에 보임)이 아니라 표준 입력으로 넘기기 위함.
# psql 작은따옴표 안에서는 ' → '' , 백슬래시 → 백슬래시 두 개로 적는다.
psql_set() {
  local q="'" v="$2"
  v="${v//\\/\\\\}"
  v="${v//$q/$q$q}"
  printf '%sset %s %s%s%s\n' '\' "$1" "$q" "$v" "$q"
}

# 설치된 PostgreSQL 찾기: Ubuntu/Debian 은 클러스터(켜진 것 우선, 높은 버전), Rocky/RHEL 은 데이터 폴더
pg_detect() {
  if command -v pg_lsclusters >/dev/null; then
    local line
    line=$(pg_lsclusters -h 2>/dev/null | awk '$4 == "online"' | sort -V | tail -1)
    [ -n "$line" ] || line=$(pg_lsclusters -h 2>/dev/null | sort -V | tail -1)
    if [ -n "$line" ]; then
      read -r PG_VER PG_CLUSTER PG_PORT _ <<<"$line"
      PG_KIND=deb
      PG_CONF_FILE="/etc/postgresql/$PG_VER/$PG_CLUSTER/postgresql.conf"
      PG_HBA="/etc/postgresql/$PG_VER/$PG_CLUSTER/pg_hba.conf"
      return 0
    fi
  fi
  if command -v postgresql-setup >/dev/null || [ -d /var/lib/pgsql ]; then
    PG_KIND=rpm
    PG_DATA=$(ls -d /var/lib/pgsql/data /var/lib/pgsql/*/data 2>/dev/null | head -1 || true)
    [ -n "$PG_DATA" ] || PG_DATA=/var/lib/pgsql/data
    PG_CONF_FILE="$PG_DATA/postgresql.conf"; PG_HBA="$PG_DATA/pg_hba.conf"
    local p; p=$(grep -E '^\s*port\s*=' "$PG_CONF_FILE" 2>/dev/null | tail -1 | sed -E 's/^[^=]*=\s*([0-9]+).*/\1/' || true)
    [ -n "$p" ] && PG_PORT="$p"
    return 0
  fi
  return 1
}

pg_start() {
  if [ "$PG_KIND" = deb ]; then
    systemctl enable postgresql >/dev/null 2>&1 || true
    systemctl start "postgresql@$PG_VER-$PG_CLUSTER" >/dev/null 2>&1 || pg_ctlcluster "$PG_VER" "$PG_CLUSTER" start >/dev/null 2>&1 || true
  else
    systemctl enable --now postgresql >/dev/null 2>&1 \
      || pgsu pg_ctl -D "$PG_DATA" -l "$PG_DATA/../workblog-pg.log" -w start >/dev/null 2>&1 || true
  fi
}
pg_reload() {
  if [ "$PG_KIND" = deb ]; then
    systemctl reload "postgresql@$PG_VER-$PG_CLUSTER" >/dev/null 2>&1 || pg_ctlcluster "$PG_VER" "$PG_CLUSTER" reload >/dev/null 2>&1 || true
  else
    systemctl reload postgresql >/dev/null 2>&1 || pgsu pg_ctl -D "$PG_DATA" reload >/dev/null 2>&1 || true
  fi
}
pg_wait() { for _ in $(seq 1 "$1"); do pg_up && return 0; sleep 1; done; return 1; }

# 켜기. 처음이면 초기화. 포트를 다른 프로그램(다른 PostgreSQL·docker 등)이 쓰고 있어 못 켜면 빈 포트로 옮겨 켠다.
ensure_postgres_running() {
  pg_detect || fail "PostgreSQL 을 찾지 못했습니다."
  if [ "$PG_KIND" = rpm ] && [ ! -f "$PG_DATA/PG_VERSION" ]; then
    say "PostgreSQL 초기화"
    postgresql-setup --initdb >/dev/null 2>&1 || pgsu initdb -D "$PG_DATA" >/dev/null
  fi
  pg_up && return 0
  say "PostgreSQL 켜기 (포트 $PG_PORT)"
  pg_start
  pg_wait 15 && return 0
  if port_busy "$PG_PORT"; then
    local np; np=$(free_port_from $((PG_PORT + 1)))
    warn "포트 $PG_PORT 를 다른 프로그램($(port_owner "$PG_PORT" || true))이 쓰고 있어 이 서버의 PostgreSQL 을 포트 $np 로 옮겨 켭니다."
    if [ "$PG_KIND" = deb ] && command -v pg_conftool >/dev/null; then
      pg_conftool "$PG_VER" "$PG_CLUSTER" set port "$np"
    elif grep -qE '^\s*port\s*=' "$PG_CONF_FILE"; then
      sed -i -E "s/^(\s*port\s*=\s*)[0-9]+/\1$np/" "$PG_CONF_FILE"
    else
      echo "port = $np   # workblog install.sh" >> "$PG_CONF_FILE"
    fi
    PG_PORT="$np"
    pg_start
    pg_wait 20 && return 0
  fi
  fail "PostgreSQL 이 켜지지 않았습니다. 확인: systemctl status postgresql / journalctl -u postgresql* -n 50"
}

# postgres 계정이 서버 안에서도 비밀번호를 요구하면(pg_hba 의 local 이 md5/scram) 설치를 못 한다
# → pg_hba.conf 맨 위에 'local all postgres peer' 를 넣는다(백업 남김). Ubuntu·Rocky 기본과 같은 규칙.
ensure_pg_peer() {
  pg_ready && return 0
  [ -f "$PG_HBA" ] || fail "pg_hba.conf 를 찾지 못했습니다: $PG_HBA"
  if ! grep -qE '^local\s+all\s+postgres\s+peer' "$PG_HBA"; then
    say "PostgreSQL: postgres 계정의 서버 안 접속을 비밀번호 없이(peer) 허용 — $PG_HBA (백업 $PG_HBA.bak-workblog)"
    [ -f "$PG_HBA.bak-workblog" ] || cp "$PG_HBA" "$PG_HBA.bak-workblog"
    sed -i '1i local   all   postgres   peer   # workblog install.sh' "$PG_HBA"
  fi
  pg_reload
  for _ in $(seq 1 10); do pg_ready && return 0; sleep 1; done
  fail "postgres 계정으로 접속하지 못했습니다. $PG_HBA 의 local 규칙을 확인하세요."
}

# postgres 관리자 계정에 비밀번호가 없으면 만든다(기본 postgres / postgres, --pg-pass 로 지정). 이미 있으면 건드리지 않는다.
ensure_pg_superuser_password() {
  local has
  has=$(pgsu psql -w -qtAc "SELECT rolpassword IS NOT NULL FROM pg_authid WHERE rolname = 'postgres'" 2>/dev/null || true)
  if [ "$has" = t ]; then PG_SUPER_INFO="postgres (기존 비밀번호 그대로)"; return 0; fi
  if [ -z "$has" ]; then
    { psql_set pw "$PG_PASS"; echo "CREATE ROLE postgres LOGIN SUPERUSER PASSWORD :'pw';"; } | pgsu psql -v ON_ERROR_STOP=1 -q >/dev/null
  else
    { psql_set pw "$PG_PASS"; echo "ALTER ROLE postgres PASSWORD :'pw';"; } | pgsu psql -v ON_ERROR_STOP=1 -q >/dev/null
  fi
  PG_SUPER_INFO="postgres / ${PG_PASS}"
}

# DB 계정·DB: 없으면 만들고, 있으면 비밀번호·소유자를 맞춘다
ensure_db() {
  { psql_set u "$DB_USER"; psql_set p "$DB_PASS"; cat <<'SQL'
SELECT NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'u') AS need_role \gset
\if :need_role
  CREATE ROLE :"u" LOGIN PASSWORD :'p';
\else
  ALTER ROLE :"u" LOGIN PASSWORD :'p';
\endif
SQL
  } | pgsu psql -v ON_ERROR_STOP=1 -q
  if ! pgsu psql -tAc "SELECT 1 FROM pg_database WHERE datname = '$DB_NAME'" | grep -q 1; then
    say "DB 만들기: $DB_NAME (소유 $DB_USER)"
    pgsu createdb -O "$DB_USER" "$DB_NAME"
  fi
  { psql_set u "$DB_USER"; echo "ALTER DATABASE \"$DB_NAME\" OWNER TO :\"u\";"; } | pgsu psql -q -v ON_ERROR_STOP=1 >/dev/null
  { psql_set u "$DB_USER"; echo "ALTER SCHEMA public OWNER TO :\"u\";"; } | pgsu psql -q -d "$DB_NAME" >/dev/null 2>&1 || true
  pgsu psql -q -d "$DB_NAME" -c "CREATE EXTENSION IF NOT EXISTS pg_trgm;" >/dev/null
  # 앱이 쓰는 것과 같은 방식(127.0.0.1, 비밀번호)으로 접속되는지. 막혀 있으면 이 계정만 허용 줄을 넣는다.
  if ! PGPASSWORD="$DB_PASS" psql -w -h 127.0.0.1 -p "$PG_PORT" -U "$DB_USER" -d "$DB_NAME" -qtAc 'select 1' >/dev/null 2>&1; then
    say "PostgreSQL: $DB_USER 의 127.0.0.1 비밀번호 접속 허용 — $PG_HBA"
    [ -f "$PG_HBA.bak-workblog" ] || cp "$PG_HBA" "$PG_HBA.bak-workblog"
    sed -i "1i host   $DB_NAME   $DB_USER   127.0.0.1/32   scram-sha-256   # workblog install.sh\nhost   $DB_NAME   $DB_USER   ::1/128        scram-sha-256   # workblog install.sh" "$PG_HBA"
    pg_reload; sleep 1
    PGPASSWORD="$DB_PASS" psql -w -h 127.0.0.1 -p "$PG_PORT" -U "$DB_USER" -d "$DB_NAME" -qtAc 'select 1' >/dev/null 2>&1 \
      || fail "$DB_USER 로 DB 에 접속하지 못했습니다. $PG_HBA 와 'SHOW password_encryption' 을 확인하세요."
  fi
}

# 데이터 넣기: 옆에 data/(db_*.dump) 가 있을 때. DB 가 비었거나 글 0건이면 넣고, 글이 있으면 --force-data 일 때만.
DATA_INFO=""
restore_data() {
  [ "$NO_DATA" = 1 ] && return 0
  local dump; dump=$(ls -1 "$DATA_DIR"/db_*.dump 2>/dev/null | sort | tail -1 || true)
  [ -n "$dump" ] || return 0
  local tables posts
  tables=$(pgsu psql -w -d "$DB_NAME" -tAc "SELECT count(*) FROM pg_tables WHERE schemaname = 'public'")
  posts=$(pgsu psql -w -d "$DB_NAME" -tAc "SELECT count(*) FROM posts" 2>/dev/null || echo 0)
  if [ "$tables" != 0 ] && [ "$posts" != 0 ] && [ "$FORCE_DATA" != 1 ]; then
    say "데이터: DB 에 이미 글 ${posts}건이 있어 그대로 둠 (바꾸려면 --force-data)"
    DATA_INFO="기존 DB 그대로 (글 ${posts}건)"
    return 0
  fi
  # 버전: 덤프보다 낮은 PostgreSQL 에는 넣을 수 없다
  local dv sv
  dv=$(pg_restore -l "$dump" 2>/dev/null | sed -n 's/.*Dumped from database version: \([0-9]*\).*/\1/p' | head -1)
  sv=$(pgsu psql -w -tAc "SELECT current_setting('server_version_num')::int / 10000")
  [ -z "$dv" ] || [ "$sv" -ge "$dv" ] || fail "데이터는 PostgreSQL $dv 에서 만든 것입니다. 이 서버는 $sv — $dv 이상이 필요합니다."
  if [ "$tables" != 0 ]; then
    install -d -m 700 -o root -g root /var/backups/workblog
    local bk="/var/backups/workblog/before-data-$(date +%Y%m%d-%H%M%S).dump"
    say "데이터: 지금 DB 를 먼저 덤프 → $bk"
    ( umask 077; pgsu pg_dump -Fc -d "$DB_NAME" > "$bk" )
    say "데이터: 지금 DB 비우기 (글 ${posts}건)"
    { psql_set u "$DB_USER"; echo 'DROP SCHEMA public CASCADE; CREATE SCHEMA public AUTHORIZATION :"u";'; } | pgsu psql -q -v ON_ERROR_STOP=1 -d "$DB_NAME" >/dev/null
    pgsu psql -q -d "$DB_NAME" -c "CREATE EXTENSION IF NOT EXISTS pg_trgm;" >/dev/null
  fi
  say "데이터 넣기: $(basename "$dump") → DB $DB_NAME (소유 $DB_USER)"
  # 파일은 리다이렉트로 넘긴다(postgres 계정이 압축 푼 폴더를 못 읽어도 됨). 확장 주석 등 권한 경고는 결과로 확인.
  pgsu pg_restore --no-owner --no-acl --role="$DB_USER" -d "$DB_NAME" < "$dump" 2>/tmp/workblog-restore.log || true
  posts=$(pgsu psql -w -d "$DB_NAME" -tAc "SELECT count(*) FROM posts" 2>/dev/null || true)
  [ -n "$posts" ] || fail "데이터를 넣지 못했습니다. 로그: /tmp/workblog-restore.log"
  local files=0
  if [ -d "$DATA_DIR/files" ]; then
    tar -C "$DATA_DIR/files" -cf - . | runuser -u workblog -- tar -C "$DIR/data/files" -xf - --no-same-owner --no-same-permissions
    files=$(find "$DATA_DIR/files" -type f | wc -l)
  fi
  say "데이터: 글 ${posts}건 · 첨부·사진 ${files}개"
  DATA_INFO="넣음 — 글 ${posts}건 · 첨부 ${files}개 (공수 표·대문 포함)"
  RESTORED=1
}

# ---------- 폴더: $DIR 과 config/ 는 root 소유, 앱이 쓰는 data·logs·static-extra 만 workblog ----------
setup_layout() {
  id workblog >/dev/null 2>&1 || useradd --system --home "$DIR" --shell /usr/sbin/nologin workblog
  no_link "$DIR" "$DIR/config" "$DIR/data" "$DIR/logs" "$DIR/static-extra"
  install -d -m 755 -o root -g root "$DIR"
  install -d -m 750 -o root -g workblog "$DIR/config"
  local d; for d in data logs static-extra; do install -d -m 750 -o workblog -g workblog "$DIR/$d"; done
  runuser -u workblog -- mkdir -p "$DIR/data/files"
}

# ---------- Java ----------
JAVA_BIN=""
find_java21() {
  local j
  for j in $(ls -d /usr/lib/jvm/*/bin/java 2>/dev/null) $(command -v java 2>/dev/null || true); do
    [ -x "$j" ] && "$j" -version 2>&1 | grep -qE 'version "(2[1-9]|[3-9][0-9])' && { JAVA_BIN="$j"; return 0; }
  done
  return 1
}

# 필요한 것이 없으면 깐다: Java 21, (같은 서버 DB 를 쓸 때) PostgreSQL 서버·확장. apt(Ubuntu/Debian) 또는 dnf(Rocky/RHEL).
ensure_packages() {
  local need_java=0 need_pg=0
  find_java21 || need_java=1
  if [ "$LOCAL_DB" = 1 ] && ! command -v postgres >/dev/null && ! ls /usr/lib/postgresql/*/bin/postgres >/dev/null 2>&1; then need_pg=1; fi
  [ $need_java = 0 ] && [ $need_pg = 0 ] && return 0
  if command -v apt-get >/dev/null; then
    say "필요한 패키지 설치 (apt):$( [ $need_java = 1 ] && printf ' Java 21')$( [ $need_pg = 1 ] && printf ' PostgreSQL')"
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq || warn "apt-get update 실패 — 받아 둔 목록으로 계속합니다"
    local pk="curl ca-certificates"
    [ $need_java = 1 ] && pk="$pk openjdk-21-jre-headless"
    [ $need_pg = 1 ] && pk="$pk postgresql postgresql-contrib"
    # shellcheck disable=SC2086
    apt-get install -y -qq $pk >/dev/null || fail "apt 설치 실패: $pk (인터넷·패키지 저장소를 확인하세요)"
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
    dnf install -y -q $pk >/dev/null || fail "dnf 설치 실패:$pk (인터넷·패키지 저장소를 확인하세요)"
  else
    fail "apt 도 dnf 도 없어 자동 설치를 못 합니다. Java 21 과 PostgreSQL 을 먼저 설치하세요."
  fi
  find_java21 || fail "Java 21 을 설치했지만 찾지 못했습니다 (ls /usr/lib/jvm)."
}

# ========== 0) 옆에 jar 가 없으면 Releases 에서 받는다 ==========
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

# ========== 1) 지금 상태 ==========
no_link "$DIR" "$DIR/config" "$CONF"
EXISTING=0; [ -f "$CONF" ] && EXISTING=1
if [ "$EXISTING" = 1 ]; then
  say "기존 설치 발견: $CONF — 설정은 유지하고 주어진 옵션만 반영"
  [ -n "$DB_URL" ]  || DB_URL="$(conf_get DB_URL)"
  [ -n "$DB_USER" ] || DB_USER="$(conf_get DB_USERNAME)"
  [ -n "$DB_PASS" ] || DB_PASS="$(conf_get DB_PASSWORD)"
  [ "$PORT_SET" = 1 ] || PORT="$(conf_get SERVER_PORT)"; PORT="${PORT:-8080}"
fi
if systemctl is-active --quiet workblog 2>/dev/null; then
  say "실행 중인 WorkBlog 멈춤 (끝나면 다시 켬)"
  systemctl stop workblog
fi

# 같은 서버 DB 인가(비었거나 localhost/127.0.0.1) — DB 이름은 주소에서
LOCAL_DB=0; DB_NAME=workblog
NAME_RE='^[A-Za-z_][A-Za-z0-9_]{0,62}$'
if [ -z "$DB_URL" ]; then LOCAL_DB=1
elif [[ "$DB_URL" =~ ^jdbc:postgresql://(localhost|127\.0\.0\.1)(:[0-9]+)?/([A-Za-z0-9_]+) ]]; then LOCAL_DB=1; DB_NAME="${BASH_REMATCH[3]}"
fi

# ========== 2) 없는 것 설치 (Java 21 · PostgreSQL) ==========
ensure_packages
say "Java: $JAVA_BIN ($("$JAVA_BIN" -version 2>&1 | head -1))"

setup_layout

# ========== 3) DB ==========
RESTORED=0
if [ "$LOCAL_DB" = 1 ]; then
  ensure_postgres_running
  ensure_pg_peer
  ensure_pg_superuser_password
  DB_USER="${DB_USER:-workblog}"; DB_PASS="${DB_PASS:-$(rand 24)}"
  [[ "$DB_USER" =~ $NAME_RE ]] || fail "DB 계정 이름은 영문·숫자·_ 만 됩니다: $DB_USER"
  [[ "$DB_NAME" =~ $NAME_RE ]] || fail "DB 이름은 영문·숫자·_ 만 됩니다: $DB_NAME"
  say "DB: 이 서버 PostgreSQL $(pgsu psql -w -tAc 'SHOW server_version' | cut -d' ' -f1) (포트 $PG_PORT) · 계정 $DB_USER · DB $DB_NAME"
  ensure_db
  restore_data
  DB_URL="jdbc:postgresql://localhost:$PG_PORT/$DB_NAME"
else
  [ -n "$DB_USER" ] && [ -n "$DB_PASS" ] || fail "--db-url 을 쓰면 --db-user, --db-pass 도 필요합니다. (DB 에 pg_trgm 확장이 있어야 합니다)"
  say "DB: 다른 서버 $DB_URL (계정 $DB_USER) — DB 는 만들지 않음"
fi

# ========== 4) 웹 포트 ==========
if port_busy "$PORT"; then
  if [ "$EXISTING" = 1 ] && [ "$PORT_SET" = 0 ]; then
    fail "웹 포트 $PORT 를 다른 프로그램($(port_owner "$PORT" || echo '?'))이 쓰고 있습니다. 그 프로그램을 멈추거나 --port 로 다른 포트를 주세요."
  fi
  NP=$(free_port_from $((PORT + 1)))
  warn "웹 포트 $PORT 를 다른 프로그램($(port_owner "$PORT" || echo '?'))이 쓰고 있어 $NP 를 씁니다."
  PORT="$NP"
fi

# ========== 5) 설정 파일 ==========
[ -n "$PUBLIC_URL" ] || PUBLIC_URL="$(conf_get PUBLIC_BASE_URL)"
if [ -z "$PUBLIC_URL" ] || { [ "$EXISTING" = 1 ] && [[ "$PUBLIC_URL" =~ :[0-9]+$ ]] && [ "${PUBLIC_URL##*:}" != "$PORT" ] && [[ "$PUBLIC_URL" =~ ^http://[0-9.]+:[0-9]+$ ]]; }; then
  PUBLIC_URL="http://$(hostname -I 2>/dev/null | awk '{print $1}'):$PORT"
fi
umask 077
if [ "$EXISTING" = 1 ]; then
  cp "$CONF" "$CONF.bak-$(date +%Y%m%d-%H%M%S)"
  conf_set SERVER_PORT "$PORT"
  conf_set DB_URL "$DB_URL"
  conf_set DB_USERNAME "$DB_USER"
  conf_set DB_PASSWORD "$DB_PASS"
  conf_set PUBLIC_BASE_URL "$PUBLIC_URL"
  [ -n "$ADMIN_PASS" ] && conf_set ADMIN_PASSWORD "$ADMIN_PASS"
  ADMIN_INFO="(기존 비밀번호 그대로)"
else
  [ -n "$ADMIN_PASS" ] || ADMIN_PASS="$(rand 14)"
  say "설정 파일 작성: $CONF"
  no_link "$DIR/config" "$CONF"
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
  ADMIN_INFO="$ADMIN_PASS"
fi
umask 022
[ "$RESTORED" = 1 ] && ADMIN_INFO="원래 서버의 admin 비밀번호 그대로 (데이터에 들어 있음)"

# ========== 6) 프로그램 ==========
say "프로그램 설치: $DIR/workblog.jar"
[ -f "$DIR/workblog.jar" ] && cp "$DIR/workblog.jar" "$DIR/workblog.jar.bak"
install -m 0644 "$JAR" "$DIR/workblog.jar"
no_link "$DIR" "$DIR/config" "$CONF" "$DIR/data" "$DIR/logs" "$DIR/static-extra"
chown root:root "$DIR" "$DIR/workblog.jar"; chmod 755 "$DIR"
[ -f "$DIR/workblog.jar.bak" ] && chown root:root "$DIR/workblog.jar.bak"
# 앱이 쓰는 곳만 workblog (chown -R 은 안쪽 링크를 따라가지 않는다)
chown -R workblog:workblog "$DIR/data" "$DIR/logs" "$DIR/static-extra"
# 설정(비밀번호 들어 있음)은 root 소유 — 서비스 계정은 읽기만. 일반 파일만(링크는 건너뜀)
chown root:workblog "$DIR/config"; chmod 750 "$DIR/config"
find "$DIR/config" -maxdepth 1 -type f -exec chown root:workblog {} + -exec chmod 640 {} +

# ========== 7) 서비스 ==========
say "systemd 서비스 등록·시작"
PG_UNIT="postgresql.service"; [ "$PG_KIND" = deb ] && PG_UNIT="postgresql@$PG_VER-$PG_CLUSTER.service"
cat > "$UNIT" <<EOF
[Unit]
Description=WorkBlog
After=network-online.target $PG_UNIT
Wants=network-online.target

[Service]
User=workblog
WorkingDirectory=$DIR
Environment=WORKBLOG_CONFIG=$CONF
ExecStart=$JAVA_BIN -Xms256m -Xmx768m -jar $DIR/workblog.jar
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable workblog >/dev/null 2>&1
systemctl restart workblog

say "기동 확인 (최대 120초)"
OK=0
for _ in $(seq 1 120); do
  if curl -fs "http://127.0.0.1:${PORT}/api/health" >/dev/null 2>&1; then OK=1; break; fi
  systemctl is-failed --quiet workblog && break
  sleep 1
done
if [ "$OK" != 1 ]; then
  journalctl -u workblog -n 40 --no-pager 2>/dev/null | tail -40 || true
  fail "기동하지 않았습니다(위 로그). 전체: journalctl -u workblog -n 200"
fi

cat <<EOF

  ┌──────────────────────────────────────────────┐
    WorkBlog 설치 완료
    주소        : ${PUBLIC_URL}
    아이디      : admin
    비밀번호    : ${ADMIN_INFO}${DATA_INFO:+
    데이터      : ${DATA_INFO}}
    DB          : ${DB_URL} (계정 ${DB_USER})${PG_SUPER_INFO:+
    DB 관리자   : ${PG_SUPER_INFO}}
    설정 파일   : ${CONF}
    로그        : journalctl -u workblog -f
  └──────────────────────────────────────────────┘
  처음 로그인한 뒤 '내 정보'에서 비밀번호를 바꾸세요.
EOF
