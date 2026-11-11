#!/usr/bin/env bash
# WorkBlog 업데이트 — 받는 서버(install.sh 로 설치한 곳)에서 root 로 돈다. install.sh 가 설치한다.
#   workblog-sync.timer : 5분마다 깨어나 화면에서 정한 주기(관리 › 업데이트)가 지났으면 확인
#   workblog-sync.path  : 화면의 [지금 업데이트] 가 남긴 요청 파일($DIR/data/sync/request)을 보면 바로 실행
#
# 두 영역을 따로 받는다:
#   1) 프로그램: 공개 저장소(PROGRAM_REPO)의 최신 릴리스가 설치된 버전($DIR/VERSION)보다 새로우면
#      linux.tar.gz 를 받아 SHA-256 확인 후 그 안의 install.sh --no-data 로 업그레이드(설정·데이터 유지, 앱 재시작)
#   2) 데이터: 비공개 저장소(DATA_REPO)의 최신 릴리스에 붙은 덤프(workblog-dump-*.zip)가 지난번과 다르면 받아
#      병합 폴더($DIR/data/import-on-start)에 넣는다 → 앱이 1분 안에 병합(없는 글은 추가, 원본에서 바뀐 글·사용자는 갱신, 지우지 않음)
#
# 설정: $DIR/config/sync.env (root 만 읽음 — 저장소 이름·토큰)   화면 설정: $DIR/data/sync/settings.json (주기·켜고 끄기)
# 상태: $DIR/data/sync/status.json (화면이 읽음)   수동 실행: sudo systemctl start workblog-sync   기록: journalctl -u workblog-sync
set -euo pipefail

DIR="${WORKBLOG_DIR:-/opt/workblog}"
ENV_FILE="$DIR/config/sync.env"
STATE="$DIR/sync"
UI="$DIR/data/sync"
MERGE_DIR="$DIR/data/import-on-start"
PROGRAM_REPO=vavibolivia/workblog; DATA_REPO=""; DATA_TOKEN=""
# shellcheck disable=SC1090
[ -f "$ENV_FILE" ] && . "$ENV_FILE"

API=https://api.github.com
# $DIR/data 아래는 앱 계정(workblog)이 쓸 수 있다 → root 는 그 안의 파일을 직접 열거나 chown 하지 않고,
# 읽기·쓰기·받기는 모두 workblog 계정으로 한다(심볼릭 링크로 root 권한 쓰기를 유도하지 못하게). 폴더 자체가 링크면 멈춘다.
asapp() { runuser -u workblog -- "$@"; }
for d in "$DIR/data" "$UI" "$MERGE_DIR"; do [ ! -L "$d" ] || { echo "심볼릭 링크라 멈춥니다: $d"; exit 1; }; done
mkdir -p "$STATE"; chmod 700 "$STATE"
[ -d "$UI" ] || asapp mkdir -m 750 "$UI"
exec 9>"$STATE/.lock"
flock -n 9 || { echo "다른 업데이트가 진행 중 — 건너뜀"; exit 0; }
command -v python3 >/dev/null || { echo "python3 가 필요합니다"; exit 1; }

TRIGGER=timer
if [ -e "$UI/request" ] || [ -L "$UI/request" ]; then TRIGGER=button; rm -f "$UI/request"; fi
[ "${1:-}" = "--now" ] && TRIGGER=manual

# 화면 설정(앱이 쓴 파일 — 정해진 값만 받는다)
read -r INTERVAL DO_PROGRAM DO_DATA < <(asapp cat "$UI/settings.json" 2>/dev/null | head -c 4096 | python3 -c '
import json, sys
try: s = json.load(sys.stdin)
except Exception: s = {}
m = s.get("intervalMinutes", 0)
m = m if m in (0, 15, 60, 360, 1440) else 0
print(m, 1 if s.get("program", True) is not False else 0, 1 if s.get("data", True) is not False else 0)
')

LAST_CHECK=$(cat "$STATE/last-check" 2>/dev/null || echo 0)
NOW=$(date +%s)
if [ "$TRIGGER" = timer ]; then
  [ "$INTERVAL" -gt 0 ] || exit 0
  [ $((NOW - LAST_CHECK)) -ge $((INTERVAL * 60 - 30)) ] || exit 0
fi
echo "$NOW" > "$STATE/last-check"

LOG="$STATE/last-run.log"; : > "$LOG"
log() { printf '%s %s\n' "$(date '+%F %T')" "$*" | tee -a "$LOG"; }
P_RESULT=""; D_RESULT=""; P_LATEST=""; D_TAG=""
status() {  # $1 = running|done
  P_CUR=$(cat "$DIR/VERSION" 2>/dev/null || true)
  python3 - "$STATE/status.json" "$1" "$TRIGGER" "$P_CUR" "$P_LATEST" "$P_RESULT" "$DATA_REPO" "$D_TAG" "$D_RESULT" "$LOG" "$INTERVAL" <<'PY'
import json, sys, datetime
a = sys.argv
log = open(a[10], encoding="utf-8", errors="replace").read().splitlines()[-40:]
json.dump({"state": a[2], "trigger": a[3], "at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
           "intervalMinutes": int(a[11]),
           "program": {"current": a[4], "latest": a[5], "result": a[6]},
           "data": {"repo": a[7], "tag": a[8], "result": a[9]}, "log": log},
          open(a[1], "w", encoding="utf-8"), ensure_ascii=False)
PY
  # 앱이 읽을 사본은 workblog 계정이 쓴다(root 가 앱 폴더에 직접 쓰지 않음)
  asapp sh -c 'cat > "$1.tmp" && mv -f "$1.tmp" "$1"' _ "$UI/status.json" < "$STATE/status.json"
}
log "업데이트 시작 ($TRIGGER)"
status running

jget() { python3 -c '
import json,sys
r=json.load(sys.stdin); what=sys.argv[1]
if what=="tag": print(r.get("tag_name",""))
else:
    for a in r.get("assets",[]):
        if a["name"].startswith(sys.argv[2]) and a["name"].endswith(".zip"): print(a["id"], a["name"]); break
' "$@"; }

TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

# ---------- 1) 프로그램 ----------
if [ "$DO_PROGRAM" = 1 ]; then
  if P_LATEST=$(curl -fsS -m 30 "$API/repos/$PROGRAM_REPO/releases/latest" | jget tag) && [ -n "$P_LATEST" ]; then
    P_LATEST="${P_LATEST#v}"
    current=$(cat "$DIR/VERSION" 2>/dev/null || echo 0)
    if [ "$P_LATEST" != "$current" ] && [ "$(printf '%s\n%s\n' "$current" "$P_LATEST" | sort -V | tail -1)" = "$P_LATEST" ]; then
      log "프로그램: $current → $P_LATEST 업그레이드"
      status running
      name="workblog-$P_LATEST-linux.tar.gz"; base="https://github.com/$PROGRAM_REPO/releases/download/v$P_LATEST"
      if curl -fsSL -m 600 -o "$TMP/$name" "$base/$name" && curl -fsSL -m 60 -o "$TMP/SHA256SUMS.txt" "$base/SHA256SUMS.txt" \
         && (cd "$TMP" && grep " $name\$" SHA256SUMS.txt | sha256sum -c --quiet -); then
        tar -xzf "$TMP/$name" -C "$TMP"
        if bash "$TMP/workblog-$P_LATEST/install.sh" --dir "$DIR" --no-data >"$STATE/program-upgrade.log" 2>&1; then
          P_RESULT="OK $current → $P_LATEST"; log "프로그램: $P_LATEST 설치 완료"
        else
          P_RESULT="FAILED 설치 실패"; log "프로그램: 업그레이드 실패"; tail -15 "$STATE/program-upgrade.log" | tee -a "$LOG"
        fi
      else
        P_RESULT="FAILED 받기·체크섬 실패"; log "프로그램: 받지 못했거나 체크섬 불일치 — 업그레이드 안 함"
      fi
    else
      P_RESULT="OK 최신($current)"; log "프로그램: 최신($current)"
    fi
  else
    P_RESULT="FAILED 릴리스 확인 실패"; log "프로그램: 최신 릴리스를 확인하지 못함($PROGRAM_REPO — 인터넷 연결 확인)"
  fi
else
  P_RESULT="꺼짐"
fi

# ---------- 2) 데이터 ----------
if [ "$DO_DATA" != 1 ]; then
  D_RESULT="꺼짐"
elif [ -z "$DATA_REPO" ] || [ -z "$DATA_TOKEN" ]; then
  D_RESULT="설정 없음(sync.env 의 DATA_REPO·DATA_TOKEN)"; log "데이터: $D_RESULT"
else
  H=(-H "Authorization: Bearer $DATA_TOKEN" -H "X-GitHub-Api-Version: 2022-11-28")
  if curl -fsS -m 30 "${H[@]}" "$API/repos/$DATA_REPO/releases/latest" -o "$TMP/data.json"; then
    D_TAG=$(jget tag <"$TMP/data.json")
    aid=""; aname=""
    read -r aid aname < <(jget asset workblog-dump- <"$TMP/data.json") || true
    last=$(cat "$STATE/data.last" 2>/dev/null || true)
    if [ -z "$aid" ]; then
      D_RESULT="최신 릴리스($D_TAG)에 덤프가 없음"; log "데이터: $D_RESULT"
    elif [ "$D_TAG" = "$last" ]; then
      D_RESULT="OK 최신($D_TAG)"; log "데이터: 최신($D_TAG)"
    else
      log "데이터: $D_TAG 받기 ($aname)"
      status running
      case "$aname" in *[!A-Za-z0-9._-]*|.*) D_RESULT="FAILED 덤프 이름이 이상함: $aname"; log "데이터: $D_RESULT"; aid="" ;; esac
    fi
    if [ -n "$aid" ] && [ "$D_TAG" != "$last" ]; then
      [ -d "$MERGE_DIR" ] || asapp mkdir -m 750 "$MERGE_DIR"
      rm -f "$MERGE_DIR/$aname.result" "$MERGE_DIR/$aname.done" "$MERGE_DIR/$aname.failed"
      part="$TMP/$aname"   # root 전용 임시 폴더에 받고, 앱 폴더에는 workblog 계정이 옮겨 놓는다
      if curl -fsSL -m 3600 "${H[@]}" -H "Accept: application/octet-stream" -o "$part" "$API/repos/$DATA_REPO/releases/assets/$aid"; then
        chgrp workblog "$part"; chmod 640 "$part"; chmod 711 "$TMP"
        # 받는 중(.part)에는 앱이 집지 않는다. 이름이 *.zip 이 되는 순간 앱이 병합한다
        asapp sh -c 'umask 077; cp "$1" "$2/.$3.part" && mv -f "$2/.$3.part" "$2/$3"' _ "$part" "$MERGE_DIR" "$aname"
        rm -f "$part"
        log "데이터: 병합 기다리는 중"
        status running
        r=""
        for _ in $(seq 1 1800); do
          [ -f "$MERGE_DIR/$aname.result" ] && { r=$(asapp head -c 2000 "$MERGE_DIR/$aname.result" | head -1); break; }
          sleep 1
        done
        D_RESULT="${r:-FAILED 30분 안에 병합 결과 없음(podman logs workblog 확인)}"
        log "데이터: 병합 결과 — $D_RESULT"
        case "$r" in OK*|SKIP*) echo "$D_TAG" >"$STATE/data.last" ;; esac   # 실패하면 다음 실행 때 다시 받는다
        rm -f "$MERGE_DIR/$aname.done"
      else
        rm -f "$part"; D_RESULT="FAILED 덤프를 받지 못함"; log "데이터: $D_RESULT"
      fi
    fi
  else
    D_RESULT="FAILED 릴리스를 읽지 못함 — 토큰·저장소 이름 확인"; log "데이터: $D_RESULT ($DATA_REPO)"
  fi
fi

log "업데이트 끝"
status done
