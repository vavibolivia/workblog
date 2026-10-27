#!/usr/bin/env bash
# WorkBlog 컨테이너 (재)기동 — 서버의 /project/workblog 에서 실행
# 설정 정본: $WORKBLOG_ENV (기본 ~/project/across/env/workblog.env)   jar: /project/workblog/app.jar
set -uo pipefail
B="/project/workblog"
IMAGE="${IMAGE:-docker.io/library/amazoncorretto:21-alpine}"
podman rm -f workblog >/dev/null 2>&1
podman run -d --name workblog --network=host -w /app -e LANG=C.UTF-8 -e TZ=Asia/Seoul \
  --env-file "${WORKBLOG_ENV:-$HOME/project/across/env/workblog.env}" \
  -v "$B:/app:z" \
  "$IMAGE" java -Xms256m -Xmx768m -jar /app/app.jar >/dev/null || { echo "기동 실패"; exit 1; }
for i in $(seq 1 90); do
  (echo > /dev/tcp/127.0.0.1/8070) 2>/dev/null && { echo ":8070 열림 (${i}초)"; exit 0; }
  sleep 1
done
echo ":8070 이 열리지 않았다 — podman logs workblog 확인"; exit 1
