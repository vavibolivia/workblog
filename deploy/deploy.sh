#!/usr/bin/env bash
# 개발 PC → 서버 설치 + 서버에서 스모크 테스트. 사용: DEPLOY_HOST=user@host bash deploy/deploy.sh  (먼저 bash build.sh)
# 원칙: 설치와 테스트는 서버에서 실행한다.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
H="${DEPLOY_HOST:?DEPLOY_HOST 를 user@host 로 지정하세요 (예: export DEPLOY_HOST=user@your-server)}"
SSH="ssh -o BatchMode=yes -i $HOME/.ssh/id_ed25519"
JAR="$ROOT/backend/target/workblog-backend-0.1.0.jar"
[ -f "$JAR" ] || { echo "jar 없음 — bash build.sh 먼저"; exit 1; }
TS=$(date +%Y%m%d-%H%M)
$SSH "$H" "mkdir -p /project/workblog/backup ~/.config/systemd/user && [ -f /project/workblog/app.jar ] && cp /project/workblog/app.jar /project/workblog/backup/app.jar.$TS || true; ls -1t /project/workblog/backup/app.jar.* 2>/dev/null | tail -n +4 | xargs -r rm -f"
scp -o BatchMode=yes -i "$HOME/.ssh/id_ed25519" "$JAR" "$H:/project/workblog/app.jar.new"
scp -o BatchMode=yes -i "$HOME/.ssh/id_ed25519" "$ROOT/deploy/start-workblog.sh" "$H:/project/workblog/start-workblog.sh"
scp -o BatchMode=yes -i "$HOME/.ssh/id_ed25519" "$ROOT/scripts/smoke.py" "$H:/project/workblog/smoke.py"
scp -o BatchMode=yes -i "$HOME/.ssh/id_ed25519" "$ROOT/scripts/smoke_comments.py" "$H:/project/workblog/smoke_comments.py"
scp -o BatchMode=yes -i "$HOME/.ssh/id_ed25519" "$ROOT/scripts/smoke_v2.py" "$H:/project/workblog/smoke_v2.py"
scp -o BatchMode=yes -i "$HOME/.ssh/id_ed25519" "$ROOT/scripts/smoke_knowledge.py" "$H:/project/workblog/smoke_knowledge.py"
scp -o BatchMode=yes -i "$HOME/.ssh/id_ed25519" "$ROOT/scripts/smoke_collab.py" "$H:/project/workblog/smoke_collab.py"
scp -o BatchMode=yes -i "$HOME/.ssh/id_ed25519" "$ROOT/deploy/test-workblog.sh" "$H:/project/workblog/test-workblog.sh"
scp -o BatchMode=yes -i "$HOME/.ssh/id_ed25519" "$ROOT/deploy/container-workblog.service" "$H:.config/systemd/user/container-workblog.service"
scp -o BatchMode=yes -i "$HOME/.ssh/id_ed25519" "$ROOT/deploy/rollback.sh" "$H:/project/workblog/rollback.sh"
scp -o BatchMode=yes -i "$HOME/.ssh/id_ed25519" "$ROOT/deploy/backup.sh" "$H:/project/workblog/backup.sh"
# 백업 cron(매일 02:30)은 한 번만 등록한다. 스모크 결과가 ssh 종료 코드가 되도록 테스트를 맨 뒤에 둔다.
$SSH "$H" "cd /project/workblog && mv app.jar.new app.jar && chmod +x start-workblog.sh && ./start-workblog.sh && systemctl --user daemon-reload && systemctl --user enable container-workblog.service >/dev/null 2>&1; curl -s http://127.0.0.1:8070/api/health; echo; chmod +x rollback.sh backup.sh; crontab -l 2>/dev/null | grep -q backup.sh || (crontab -l 2>/dev/null; echo \"30 2 * * * /project/workblog/backup.sh\") | crontab -; chmod +x test-workblog.sh && ./test-workblog.sh"
