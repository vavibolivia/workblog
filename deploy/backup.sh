#!/usr/bin/env bash
# WorkBlog 백업 — 서버 cron 매일 02:30 (deploy.sh 가 등록: 30 2 * * * /project/workblog/backup.sh)
#   DB:   pg_dump -Fc → /project/workblog/backup/db/db_workblog-<yyyymmdd>.dump
#   첨부: tar.gz    → /project/workblog/backup/files/files-<yyyymmdd>.tar.gz
#   보관: 14일 지난 백업 삭제.  로그: /project/workblog/logs/backup.log
# DB 접속 정보는 $WORKBLOG_ENV (기본 ~/project/across/env/workblog.env) 의 DB_URL / DB_USERNAME / DB_PASSWORD 에서 읽는다.
# 비밀번호는 PGPASSWORD 환경변수로만 넘기고 로그·명령줄에 남기지 않는다.
# 복구: pg_restore --clean --if-exists -d <db> <dump>  /  tar -xzf files-<날짜>.tar.gz -C /project/workblog/data
set -uo pipefail
B="/project/workblog"
ENV_FILE="${WORKBLOG_ENV:-$HOME/project/across/env/workblog.env}"
KEEP_DAYS="${BACKUP_KEEP_DAYS:-14}"
DAY=$(date +%Y%m%d)
LOG="$B/logs/backup.log"
# 덤프·압축본에는 글 본문(서버 계정 정보 포함)이 그대로 들어 있다 → 소유자만 읽도록
umask 077
mkdir -p "$B/backup/db" "$B/backup/files" "$B/logs"
chmod 700 "$B/backup" "$B/backup/db" "$B/backup/files"
chmod 600 "$B"/backup/db/* "$B"/backup/files/* 2>/dev/null || true

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG"; }

# env 파일에서 KEY=VALUE 한 줄 읽기(따옴표 제거). source 하지 않는다(값에 특수문자가 있어도 안전하게).
env_get() {
  local v
  v=$(grep -E "^[[:space:]]*(export[[:space:]]+)?$1=" "$ENV_FILE" 2>/dev/null | tail -n 1 | sed -E "s/^[[:space:]]*(export[[:space:]]+)?$1=//; s/[[:space:]]+$//")
  v="${v%\"}"; v="${v#\"}"; v="${v%\'}"; v="${v#\'}"
  printf '%s' "$v"
}

STATUS=0
log "백업 시작 (env: $ENV_FILE, 보관 ${KEEP_DAYS}일)"

# ---------- DB ----------
if [ ! -r "$ENV_FILE" ]; then
  log "실패: env 파일을 읽을 수 없음"; STATUS=1
elif ! command -v pg_dump >/dev/null 2>&1; then
  log "실패: pg_dump 가 없음 (postgresql-client 설치 필요)"; STATUS=1
else
  DB_URL=$(env_get DB_URL)
  DB_USER=$(env_get DB_USERNAME)
  DB_PASS=$(env_get DB_PASSWORD)
  # jdbc:postgresql://host[:port]/dbname[?params]
  REST="${DB_URL#jdbc:postgresql://}"
  HOSTPORT="${REST%%/*}"
  DBNAME="${REST#*/}"; DBNAME="${DBNAME%%\?*}"
  DBHOST="${HOSTPORT%%:*}"
  DBPORT=5432; [[ "$HOSTPORT" == *:* ]] && DBPORT="${HOSTPORT##*:}"
  if [ -z "$DB_URL" ] || [ "$REST" = "$DB_URL" ] || [ -z "$DBNAME" ] || [ -z "$DB_USER" ]; then
    log "실패: DB_URL/DB_USERNAME 을 해석할 수 없음"; STATUS=1
  else
    OUT="$B/backup/db/db_workblog-$DAY.dump"
    if PGPASSWORD="$DB_PASS" pg_dump -Fc -h "$DBHOST" -p "$DBPORT" -U "$DB_USER" -d "$DBNAME" -f "$OUT.part" 2>>"$LOG"; then
      mv "$OUT.part" "$OUT"
      log "DB 백업 완료: $OUT ($(du -h "$OUT" | cut -f1))"
    else
      rm -f "$OUT.part"; log "실패: pg_dump ($DBHOST:$DBPORT/$DBNAME)"; STATUS=1
    fi
  fi
fi

# ---------- 첨부 ----------
# STORAGE_DIR 은 컨테이너 안 경로(/app = $B). 상대 경로면 $B 기준, /app/ 로 시작하면 $B 로 바꾼다.
SD=""; [ -r "$ENV_FILE" ] && SD=$(env_get STORAGE_DIR)
SD="${SD:-./data/files}"
case "$SD" in
  /app/*) FILES_DIR="$B/${SD#/app/}" ;;
  /*)     FILES_DIR="$SD" ;;
  *)      FILES_DIR="$B/${SD#./}" ;;
esac
if [ -d "$FILES_DIR" ]; then
  OUT="$B/backup/files/files-$DAY.tar.gz"
  if tar -czf "$OUT.part" -C "$(dirname "$FILES_DIR")" "$(basename "$FILES_DIR")" 2>>"$LOG"; then
    mv "$OUT.part" "$OUT"
    log "첨부 백업 완료: $OUT ($(du -h "$OUT" | cut -f1))"
  else
    rm -f "$OUT.part"; log "실패: 첨부 tar ($FILES_DIR)"; STATUS=1
  fi
else
  log "첨부 폴더 없음, 건너뜀: $FILES_DIR"
fi

# ---------- 보관 기간 정리 ----------
find "$B/backup/db" -maxdepth 1 -name 'db_workblog-*.dump' -mtime +"$KEEP_DAYS" -print -delete >> "$LOG" 2>&1
find "$B/backup/files" -maxdepth 1 -name 'files-*.tar.gz' -mtime +"$KEEP_DAYS" -print -delete >> "$LOG" 2>&1

log "백업 끝 (status=$STATUS)"
exit "$STATUS"
