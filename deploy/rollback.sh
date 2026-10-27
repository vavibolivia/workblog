#!/usr/bin/env bash
# WorkBlog 롤백 — 서버의 /project/workblog 에서 실행. 사용: ssh user@host /project/workblog/rollback.sh [N]
#   N 없음: 가장 최근 백업(backup/app.jar.<ts>)으로. N: 최신순 N번째 백업으로.
# 현재 jar 는 /project/workblog/app.jar.rolledback-<ts> 로 남긴다(backup/ 밖에 두어 다음 롤백·정리 대상에 섞이지 않게).
set -uo pipefail
B="/project/workblog"
N="${1:-1}"
[[ "$N" =~ ^[0-9]+$ && "$N" -ge 1 ]] || { echo "사용: rollback.sh [N]  (N = 최신순 백업 번호, 기본 1)"; exit 2; }
cd "$B" || exit 1

mapfile -t CANDIDATES < <(ls -1t "$B"/backup/app.jar.* 2>/dev/null)
if [ "${#CANDIDATES[@]}" -eq 0 ]; then echo "백업 jar 가 없습니다: $B/backup/app.jar.*"; exit 1; fi
TARGET="${CANDIDATES[$((N-1))]:-}"
if [ -z "$TARGET" ]; then
  echo "백업은 ${#CANDIDATES[@]}개뿐입니다. 선택 가능:"; printf '  %s\n' "${CANDIDATES[@]}"; exit 1
fi

TS=$(date +%Y%m%d-%H%M%S)
echo "되돌릴 대상: $TARGET"
if [ -f app.jar ]; then
  mv app.jar "app.jar.rolledback-$TS" || { echo "현재 jar 보관 실패"; exit 1; }
  echo "현재 jar 보관: $B/app.jar.rolledback-$TS"
fi
cp "$TARGET" app.jar || { echo "복사 실패 — 현재 jar 를 되돌립니다"; [ -f "app.jar.rolledback-$TS" ] && mv "app.jar.rolledback-$TS" app.jar; exit 1; }

echo "재기동…"
if ! ./start-workblog.sh; then
  echo "재기동 실패 — podman logs workblog 를 확인하세요. 되돌리기 전 jar: $B/app.jar.rolledback-$TS"
  exit 1
fi
for i in $(seq 1 30); do
  if curl -sf http://127.0.0.1:8070/api/health | grep -q '"UP"'; then
    echo "롤백 완료: $(basename "$TARGET") → app.jar (/api/health UP, ${i}회 시도)"
    exit 0
  fi
  sleep 2
done
echo "롤백 후 /api/health 가 UP 이 아닙니다 — podman logs workblog 확인"
exit 1
