#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
REPO_ROOT="$(cd "$APP_ROOT/.." && pwd)"
OUTPUT_DIR="${1:-$REPO_ROOT/dist}"
APK_SOURCE="$REPO_ROOT/android/app/build/outputs/apk/release/Click & Click.apk"
PACKAGE_NAME="FilmLightMeter-website-deploy-$(date -u +%Y%m%dT%H%M%SZ)"
WORK_DIR="$(mktemp -d)"
STAGE_DIR="$WORK_DIR/filmlightmeter-website"

cleanup() {
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

"$SCRIPT_DIR/check_environment.sh" build

if [[ ! -f "$APK_SOURCE" ]]; then
  printf '缺少签名 Release APK：%s\n' "$APK_SOURCE" >&2
  exit 1
fi

mkdir -p \
  "$OUTPUT_DIR" \
  "$STAGE_DIR/asset" \
  "$STAGE_DIR/artifacts/outputs/apk/release"

copy_entries=(
  app
  components
  lib
  public
  scripts
  package.json
  package-lock.json
  next.config.ts
  tsconfig.json
  .env.example
  README.md
)
for entry in "${copy_entries[@]}"; do
  cp -R "$APP_ROOT/$entry" "$STAGE_DIR/"
done

cp "$APP_ROOT/asset/20260816-195133.jpeg" \
  "$STAGE_DIR/asset/20260816-195133.jpeg"
cp "$APK_SOURCE" \
  "$STAGE_DIR/artifacts/outputs/apk/release/FilmLightMeter-release.apk"

chmod +x \
  "$STAGE_DIR/scripts/deploy/check_environment.sh" \
  "$STAGE_DIR/scripts/deploy/install.sh" \
  "$STAGE_DIR/scripts/deploy/create_package.sh"

(
  cd "$STAGE_DIR"
  if command -v sha256sum >/dev/null 2>&1; then
    find . -type f ! -name SHA256SUMS -print0 |
      sort -z |
      xargs -0 sha256sum > SHA256SUMS
  else
    find . -type f ! -name SHA256SUMS -print0 |
      sort -z |
      xargs -0 shasum -a 256 > SHA256SUMS
  fi
)

archive="$OUTPUT_DIR/$PACKAGE_NAME.tar.gz"
tar -C "$WORK_DIR" -czf "$archive" filmlightmeter-website

if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "$archive" > "$archive.sha256"
else
  shasum -a 256 "$archive" > "$archive.sha256"
fi

printf '%s\n' "$archive"
