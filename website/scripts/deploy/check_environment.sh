#!/usr/bin/env bash
set -euo pipefail

MODE="${1:-build}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
MIN_NODE_MAJOR=22
MIN_NODE_MINOR=13
MIN_DISK_KB=$((2 * 1024 * 1024))
MIN_MEMORY_KB=$((1024 * 1024))
errors=0

ok() {
  printf '[OK] %s\n' "$1"
}

warn() {
  printf '[WARN] %s\n' "$1"
}

fail() {
  printf '[FAIL] %s\n' "$1" >&2
  errors=$((errors + 1))
}

require_command() {
  if command -v "$1" >/dev/null 2>&1; then
    ok "$1: $(command -v "$1")"
  else
    fail "缺少命令：$1"
  fi
}

printf 'FilmLightMeter 官网环境检查\n'
printf '应用目录：%s\n' "$APP_ROOT"
printf '检查模式：%s\n\n' "$MODE"

if [[ "$MODE" == "deploy" && "$(uname -s)" != "Linux" ]]; then
  fail "systemd 部署仅支持 Linux"
else
  ok "操作系统：$(uname -s) $(uname -m)"
fi

for command_name in node npm tar openssl curl; do
  require_command "$command_name"
done
if [[ "$MODE" == "deploy" ]]; then
  require_command systemctl
  require_command getent
fi

if command -v node >/dev/null 2>&1; then
  if node -e "
    const [major, minor] = process.versions.node.split('.').map(Number);
    process.exit(
      major > $MIN_NODE_MAJOR ||
      (major === $MIN_NODE_MAJOR && minor >= $MIN_NODE_MINOR) ? 0 : 1
    );
  "; then
    ok "Node.js：$(node --version)"
  else
    fail "Node.js 版本过低，需要 >= ${MIN_NODE_MAJOR}.${MIN_NODE_MINOR}"
  fi
fi

if command -v npm >/dev/null 2>&1; then
  ok "npm：$(npm --version)"
fi

for required_path in \
  package.json \
  package-lock.json \
  next.config.ts \
  app \
  components \
  lib \
  asset/20260816-161025.jpeg; do
  if [[ -e "$APP_ROOT/$required_path" ]]; then
    ok "文件：$required_path"
  else
    fail "缺少官网文件：$required_path"
  fi
done

if [[ -f "$APP_ROOT/artifacts/FilmLightMeter-release.apk" ]]; then
  ok "Android Release APK 已包含"
elif [[ "$MODE" == "deploy" ]]; then
  fail "缺少 artifacts/FilmLightMeter-release.apk"
else
  warn "当前源码目录未包含部署包内的 Android Release APK"
fi

available_disk_kb="$(df -Pk "$APP_ROOT" | awk 'NR==2 {print $4}')"
if [[ "${available_disk_kb:-0}" -ge "$MIN_DISK_KB" ]]; then
  ok "可用磁盘：$((available_disk_kb / 1024)) MiB"
else
  fail "可用磁盘不足 2 GiB"
fi

memory_kb=0
if [[ -r /proc/meminfo ]]; then
  memory_kb="$(awk '/MemTotal/ {print $2}' /proc/meminfo)"
elif command -v sysctl >/dev/null 2>&1; then
  memory_bytes="$(sysctl -n hw.memsize 2>/dev/null || printf '0')"
  memory_kb=$((memory_bytes / 1024))
fi
if [[ "$memory_kb" -ge "$MIN_MEMORY_KB" ]]; then
  ok "系统内存：$((memory_kb / 1024)) MiB"
else
  warn "系统内存低于 1 GiB，生产构建可能失败"
fi

if [[ "$errors" -gt 0 ]]; then
  printf '\n环境检查失败：%d 项\n' "$errors" >&2
  exit 1
fi

printf '\n环境检查通过。\n'
