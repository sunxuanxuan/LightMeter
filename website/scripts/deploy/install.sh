#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SERVICE_NAME="filmlightmeter-website"
SERVICE_USER="filmlightmeter"
INSTALL_ROOT="/opt/$SERVICE_NAME"
STATE_DIR="/var/lib/$SERVICE_NAME"
ENV_TARGET="/etc/$SERVICE_NAME.env"
PORT="3000"
ENV_SOURCE=""

read_env_value() {
  local file_path="$1"
  local key="$2"

  if [[ ! -f "$file_path" ]]; then
    return
  fi

  awk -v expected_key="$key" '
    /^[[:space:]]*#/ { next }
    {
      separator = index($0, "=")
      if (separator < 2) next
      key = substr($0, 1, separator - 1)
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", key)
      if (key != expected_key) next

      value = substr($0, separator + 1)
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
      first = substr(value, 1, 1)
      last = substr(value, length(value), 1)
      if (length(value) >= 2 &&
          ((first == "\"" && last == "\"") ||
           (first == "\047" && last == "\047"))) {
        value = substr(value, 2, length(value) - 2)
      }
      print value
      exit
    }
  ' "$file_path"
}

generated_or_existing_value() {
  local key="$1"
  local value

  value="$(read_env_value "$ENV_SOURCE" "$key")"
  if [[ -n "$value" ]]; then
    printf '%s' "$value"
    return
  fi

  value="$(read_env_value "$ENV_TARGET" "$key")"
  if [[ -n "$value" ]]; then
    printf '%s' "$value"
    return
  fi

  case "$key" in
    DATA_ENCRYPTION_KEY_BASE64)
      openssl rand -base64 32 | tr -d '\n'
      ;;
    LOOKUP_HMAC_PEPPER|PAYMENT_MONITOR_SECRET)
      openssl rand -base64 48 | tr -d '\n'
      ;;
    PAYMENT_MONITOR_ID)
      printf 'monitor-%s' "$(openssl rand -hex 12)"
      ;;
    *)
      printf '不支持自动生成变量：%s\n' "$key" >&2
      return 1
      ;;
  esac
}

usage() {
  cat <<'EOF'
用法：
  sudo ./scripts/deploy/install.sh --env-file /root/filmlightmeter.env [选项]

选项：
  --env-file PATH   必填，生产环境变量文件
  --port PORT       本地监听端口，默认 3000
  --install-root    发布目录，默认 /opt/filmlightmeter-website
  --state-dir       SQLite 持久化目录，默认 /var/lib/filmlightmeter-website
EOF
}

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --env-file)
      ENV_SOURCE="${2:-}"
      shift 2
      ;;
    --port)
      PORT="${2:-}"
      shift 2
      ;;
    --install-root)
      INSTALL_ROOT="${2:-}"
      shift 2
      ;;
    --state-dir)
      STATE_DIR="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      printf '未知参数：%s\n' "$1" >&2
      usage
      exit 2
      ;;
  esac
done

if [[ "$EUID" -ne 0 ]]; then
  printf '安装脚本必须使用 root 运行。\n' >&2
  exit 1
fi
if [[ -z "$ENV_SOURCE" || ! -f "$ENV_SOURCE" ]]; then
  printf '必须通过 --env-file 提供生产环境变量文件。\n' >&2
  exit 1
fi
if [[ ! "$PORT" =~ ^[0-9]+$ ]] || ((PORT < 1024 || PORT > 65535)); then
  printf '端口必须是 1024..65535 的整数。\n' >&2
  exit 1
fi

"$SCRIPT_DIR/check_environment.sh" deploy

if ! getent passwd "$SERVICE_USER" >/dev/null; then
  useradd \
    --system \
    --home-dir "$STATE_DIR" \
    --shell /usr/sbin/nologin \
    "$SERVICE_USER"
fi

release_id="$(date -u +%Y%m%dT%H%M%SZ)"
release_dir="$INSTALL_ROOT/releases/$release_id"
current_link="$INSTALL_ROOT/current"
previous_release="$(readlink -f "$current_link" 2>/dev/null || true)"

install -d -m 0755 "$INSTALL_ROOT/releases" "$release_dir"
install -d -o "$SERVICE_USER" -g "$SERVICE_USER" -m 0700 "$STATE_DIR"

copy_entries=(
  app
  components
  lib
  public
  scripts
  asset
  artifacts
  package.json
  package-lock.json
  next.config.ts
  tsconfig.json
  .env.example
  README.md
)
for entry in "${copy_entries[@]}"; do
  if [[ -e "$SOURCE_ROOT/$entry" ]]; then
    cp -R "$SOURCE_ROOT/$entry" "$release_dir/"
  fi
done

runtime_env="$(mktemp)"
trap 'rm -f "$runtime_env"' EXIT
awk '
  !/^(NODE_ENV|SITE_DATA_DIR|ANDROID_APK_PATH|PORT|HOSTNAME|DATA_ENCRYPTION_KEY_BASE64|LOOKUP_HMAC_PEPPER|PAYMENT_MONITOR_ID|PAYMENT_MONITOR_SECRET)=/
' "$ENV_SOURCE" > "$runtime_env"

for generated_key in \
  DATA_ENCRYPTION_KEY_BASE64 \
  LOOKUP_HMAC_PEPPER \
  PAYMENT_MONITOR_ID \
  PAYMENT_MONITOR_SECRET; do
  generated_value="$(generated_or_existing_value "$generated_key")"
  printf '%s=%s\n' "$generated_key" "$generated_value" >> "$runtime_env"
done

cat >> "$runtime_env" <<EOF
NODE_ENV=production
SITE_DATA_DIR=$STATE_DIR
ANDROID_APK_PATH=./artifacts/outputs/apk/release/FilmLightMeter-release.apk
EOF
install -o root -g root -m 0600 "$runtime_env" "$ENV_TARGET"

(
  cd "$release_dir"
  node scripts/deploy/validate_config.mjs "$ENV_TARGET" "$release_dir"
  npm ci --no-audit --no-fund
  npm run build
  npm prune --omit=dev --no-audit --no-fund
)

chown -R root:root "$release_dir"
chmod -R a+rX "$release_dir"
ln -sfn "$release_dir" "$current_link.new"
mv -Tf "$current_link.new" "$current_link"

npm_bin="$(command -v npm)"
cat > "/etc/systemd/system/$SERVICE_NAME.service" <<EOF
[Unit]
Description=FilmLightMeter Website
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$SERVICE_USER
Group=$SERVICE_USER
WorkingDirectory=$current_link
EnvironmentFile=$ENV_TARGET
Environment=NODE_ENV=production
ExecStart=$npm_bin start -- --hostname 127.0.0.1 --port $PORT
Restart=on-failure
RestartSec=5
TimeoutStopSec=30
KillSignal=SIGTERM
UMask=0077
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=full
ReadWritePaths=$STATE_DIR

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable "$SERVICE_NAME" >/dev/null
if ! systemctl restart "$SERVICE_NAME"; then
  if [[ -n "$previous_release" && -d "$previous_release" ]]; then
    ln -sfn "$previous_release" "$current_link"
    systemctl restart "$SERVICE_NAME" || true
  fi
  printf '服务启动失败，已尝试回滚。\n' >&2
  exit 1
fi

healthy=0
for _ in $(seq 1 30); do
  if curl --fail --silent --show-error \
    "http://127.0.0.1:$PORT/activate" >/dev/null; then
    healthy=1
    break
  fi
  sleep 1
done
if [[ "$healthy" -ne 1 ]]; then
  journalctl -u "$SERVICE_NAME" -n 80 --no-pager >&2 || true
  if [[ -n "$previous_release" && -d "$previous_release" ]]; then
    ln -sfn "$previous_release" "$current_link"
    systemctl restart "$SERVICE_NAME" || true
  fi
  printf '健康检查失败，已尝试回滚。\n' >&2
  exit 1
fi

mapfile -t old_releases < <(
  find "$INSTALL_ROOT/releases" -mindepth 1 -maxdepth 1 -type d \
    -printf '%T@ %p\n' | sort -rn | awk 'NR > 3 {$1=""; sub(/^ /, ""); print}'
)
for old_release in "${old_releases[@]}"; do
  rm -rf -- "$old_release"
done

printf '\n安装完成。\n'
printf '服务：%s\n' "$SERVICE_NAME"
printf '本地地址：http://127.0.0.1:%s\n' "$PORT"
printf '状态：systemctl status %s\n' "$SERVICE_NAME"
printf '日志：journalctl -u %s -f\n' "$SERVICE_NAME"
printf '下一步：配置 Nginx/HTTPS 反向代理到 127.0.0.1:%s。\n' "$PORT"
