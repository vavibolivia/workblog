#!/usr/bin/env bash
# 개발 PC → 서버 설치 + 서버에서 스모크 테스트. 사용: DEPLOY_HOST=user@host bash install/deploy/deploy.sh  (먼저 bash install/build.sh)
# 원칙: 설치와 테스트는 서버에서 실행한다.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
H="${DEPLOY_HOST:?DEPLOY_HOST 를 user@host 로 지정하세요 (예: export DEPLOY_HOST=user@your-server)}"
SSH="ssh -o BatchMode=yes -i $HOME/.ssh/id_ed25519"
JAR="$ROOT/backend/target/workblog-backend-0.1.0.jar"
[ -f "$JAR" ] || { echo "jar 없음 — bash install/build.sh 먼저"; exit 1; }
TS=$(date +%Y%m%d-%H%M)
$SSH "$H" "mkdir -p /project/workblog/backup ~/.config/systemd/user && [ -f /project/workblog/app.jar ] && cp /project/workblog/app.jar /project/workblog/backup/app.jar.$TS || true; ls -1t /project/workblog/backup/app.jar.* 2>/dev/null | tail -n +4 | xargs -r rm -f"
scp -o BatchMode=yes -i "$HOME/.ssh/id_ed25519" "$JAR" "$H:/project/workblog/app.jar.new"
SCP="scp -o BatchMode=yes -i $HOME/.ssh/id_ed25519"
D="$ROOT/install/deploy"
$SCP "$D/start-workblog.sh" "$D/test-workblog.sh" "$D/rollback.sh" "$D/backup.sh" "$H:/project/workblog/"
$SCP "$D/container-workblog.service" "$H:.config/systemd/user/container-workblog.service"
# 스모크는 /project/workblog/smoke/ 에 둔다(예전 위치의 smoke*.py 는 지운다).
$SSH "$H" "rm -f /project/workblog/smoke*.py; mkdir -p /project/workblog/smoke"
$SCP "$ROOT"/install/smoke/*.py "$H:/project/workblog/smoke/"
# 백업 cron(매일 02:30)은 한 번만 등록한다. 스모크 결과가 ssh 종료 코드가 되도록 테스트를 맨 뒤에 둔다.
$SSH "$H" "cd /project/workblog && mv app.jar.new app.jar && chmod +x start-workblog.sh && ./start-workblog.sh && systemctl --user daemon-reload && systemctl --user enable container-workblog.service >/dev/null 2>&1; curl -s http://127.0.0.1:8070/api/health; echo; chmod +x rollback.sh backup.sh; crontab -l 2>/dev/null | grep -q backup.sh || (crontab -l 2>/dev/null; echo \"30 2 * * * /project/workblog/backup.sh\") | crontab -; chmod +x test-workblog.sh && ./test-workblog.sh"
