#!/usr/bin/env bash
# WorkBlog 통합 빌드: 프론트(dist) → backend static → 단일 jar
# 사용: bash build.sh   (JAVA_HOME 이 JDK 21 이 아니면 아래 기본값 사용)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
export JAVA_HOME="${JAVA_HOME_21:-/c/Program Files/Java/jdk-21.0.12}"
export PATH="$JAVA_HOME/bin:$PATH"

echo "== frontend build"
(cd "$ROOT/frontend" && npm ci --no-audit --no-fund >/dev/null && npm run build)

echo "== copy dist → backend/src/main/resources/static"
rm -rf "$ROOT/backend/src/main/resources/static"
mkdir -p "$ROOT/backend/src/main/resources/static"
cp -r "$ROOT/frontend/dist/." "$ROOT/backend/src/main/resources/static/"

echo "== backend package"
(cd "$ROOT/backend" && ./mvnw -q -DskipTests package)
ls -la "$ROOT/backend/target/"*.jar
