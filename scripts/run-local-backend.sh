#!/usr/bin/env bash
# 本地启动后端：从 .env.local 注入模型凭据，再起 jar。
#
# 为什么需要这个脚本：
#   1. 凭据只放本地文件，不进 application.yml、不进仓库；
#   2. 环境里注入了 SERVER__PORT=51719，会顶掉 application.yml 的 8080，
#      命令行 --server.port 优先级更高，用它压回去。
#
# 用法：scripts/run-local-backend.sh [端口]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/ontos/ontos-server/target/ontos-server-0.1.0-SNAPSHOT.jar"
ENV_FILE="$ROOT/.env.local"
PORT="${1:-8080}"

if [ ! -f "$JAR" ]; then
  echo "找不到 jar：$JAR" >&2
  echo "先构建：cd ontos && mvn -q package -DskipTests" >&2
  exit 1
fi

if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
  # 清掉空值，避免把「未填写」当成空字符串的 key 传给 provider
  for name in ONTOS_AGENT_MODEL ONTOS_AGENT_BASE_URL ONTOS_AGENT_API_KEY \
              OPENAI_API_KEY OPENAI_BASE_URL; do
    if [ -n "${!name:-}" ]; then
      case "$name" in
        *API_KEY*) echo "  已注入 $name（值不打印）" ;;
        *) echo "  已注入 $name=${!name}" ;;
      esac
    else
      unset "$name" 2>/dev/null || true
    fi
  done
else
  echo "未找到 .env.local，按无凭据启动（确定性链路可用，AG-UI 调用会失败）"
  echo "  参考 .env.example 创建"
fi

exec java -jar "$JAR" --server.port="$PORT"
