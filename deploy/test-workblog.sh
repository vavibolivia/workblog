#!/usr/bin/env bash
# 서버에서 실행하는 WorkBlog 스모크 테스트.
# 관리자 비밀번호는 서버의 ~/project/across/env/workblog.env 에서 읽고 밖으로 내보내지 않는다.
set -euo pipefail
cd "/project/workblog"
ADM=$(grep '^ADMIN_PASSWORD=' "${WORKBLOG_ENV:-$HOME/project/across/env/workblog.env}" | cut -d= -f2-)
mkdir -p logs
LOG="logs/smoke-$(date +%Y%m%d-%H%M%S).log"
PYTHONIOENCODING=utf-8 python3 -I smoke.py http://127.0.0.1:8070 "$ADM" | tee "$LOG" | grep -E "FAIL|RESULT"
ls -1t logs/smoke-*.log | tail -n +11 | xargs -r rm -f
grep -q " 0 fail" "$LOG"
