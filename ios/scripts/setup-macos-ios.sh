#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IOS_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
XCODE_APP="${XCODE_APP:-}"
XCODE_XIP="${XCODE_XIP:-}"
XCODE_INSTALL_DIR="${XCODE_INSTALL_DIR:-$HOME/Applications}"
INSTALL_XCODE=1
RUN_BUILD=1
CHECK_ONLY=0
XCODE_APP_STORE_ID=497799835

log() {
  printf '\n==> %s\n' "$1"
}

warn() {
  printf '\n[warn] %s\n' "$1" >&2
}

fail() {
  printf '\n[error] %s\n' "$1" >&2
  exit 1
}

usage() {
  cat <<'EOF'
用法：./scripts/setup-macos-ios.sh [选项]

选项：
  --check-only          只检查环境，不执行安装或系统配置
  --skip-xcode-install  缺少 Xcode 时不通过 Mac App Store 安装
  --xcode-xip PATH      使用已下载的 Xcode .xip 安装包，而不是 Mac App Store
  --skip-build          安装后不执行 Simulator 构建验证
  -h, --help            显示帮助

环境变量：
  XCODE_APP             指定 Xcode.app 路径，例如 /Applications/Xcode-beta.app
  XCODE_XIP             指定 Xcode .xip 路径，例如 ~/Downloads/Xcode_16.4.xip
  XCODE_INSTALL_DIR     .xip 解包目录，默认 ~/Applications
  HOMEBREW_NO_AUTO_UPDATE=1
                        跳过 Homebrew 自动更新

默认流程：
  1. 安装或检查 Homebrew。
  2. 安装 XcodeGen。
  3. 若提供 XCODE_XIP/--xcode-xip，从 .xip 解包 Xcode。
  4. 若缺少完整 Xcode 且未提供 .xip，通过 Mac App Store 安装 Xcode。
  5. 选择 Xcode Developer Directory，接受许可证并运行首次初始化。
  6. 检查或下载 iOS SDK/Simulator Runtime。
  7. 生成 Xcode 工程并执行无签名 Simulator 构建。

注意：Mac App Store 必须已登录 Apple ID。真机签名证书和 Apple Developer
Team 不能由脚本代替用户创建。
EOF
}

parse_arguments() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --check-only)
        CHECK_ONLY=1
        INSTALL_XCODE=0
        RUN_BUILD=0
        ;;
      --skip-xcode-install)
        INSTALL_XCODE=0
        ;;
      --xcode-xip)
        shift
        [[ $# -gt 0 ]] || fail "--xcode-xip 需要一个 .xip 路径"
        XCODE_XIP="$1"
        ;;
      --skip-build)
        RUN_BUILD=0
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      *)
        fail "未知选项：$1"
        ;;
    esac
    shift
  done
}

configure_homebrew_path() {
  if [[ -x /opt/homebrew/bin/brew ]]; then
    eval "$(/opt/homebrew/bin/brew shellenv)"
  elif [[ -x /usr/local/bin/brew ]]; then
    eval "$(/usr/local/bin/brew shellenv)"
  fi
}

install_homebrew() {
  if command -v brew >/dev/null 2>&1; then
    return
  fi
  if [[ "$CHECK_ONLY" -eq 1 ]]; then
    fail "未安装 Homebrew：https://brew.sh"
  fi

  log "安装 Homebrew"
  NONINTERACTIVE=1 /bin/bash -c \
    "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
  configure_homebrew_path
  command -v brew >/dev/null 2>&1 || fail "Homebrew 安装完成，但当前 Shell 无法找到 brew"
}

install_formula() {
  local formula="$1"
  if brew list --formula "$formula" >/dev/null 2>&1; then
    printf '已安装：%s\n' "$formula"
  elif [[ "$CHECK_ONLY" -eq 1 ]]; then
    fail "缺少 Homebrew 工具：$formula"
  else
    brew install "$formula"
  fi
}

detect_xcode_app() {
  local candidate
  for candidate in \
    "$XCODE_APP" \
    "/Applications/Xcode.app" \
    "/Applications/Xcode-beta.app"; do
    if [[ -n "$candidate" && -x "$candidate/Contents/Developer/usr/bin/xcodebuild" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done

  candidate="$(
    find /Applications "$HOME/Applications" \
      -maxdepth 1 \
      -type d \
      -name 'Xcode*.app' \
      -print 2>/dev/null |
      sort |
      head -n 1
  )"
  if [[ -n "$candidate" && -x "$candidate/Contents/Developer/usr/bin/xcodebuild" ]]; then
    printf '%s\n' "$candidate"
    return 0
  fi
  return 1
}

install_xcode_from_xip() {
  [[ -n "$XCODE_XIP" ]] || return 1
  [[ "$CHECK_ONLY" -eq 0 ]] || fail "check-only 模式不会解包 Xcode .xip"
  [[ -f "$XCODE_XIP" ]] || fail "找不到 Xcode .xip：$XCODE_XIP"

  log "从 .xip 解包 Xcode：$XCODE_XIP"
  mkdir -p "$XCODE_INSTALL_DIR"

  local before
  before="$(mktemp)"
  find "$XCODE_INSTALL_DIR" -maxdepth 1 -type d -name 'Xcode*.app' -print |
    sort > "$before"

  (
    cd "$XCODE_INSTALL_DIR"
    xip -x "$XCODE_XIP"
  )

  local extracted
  extracted="$(
    comm -13 "$before" <(
      find "$XCODE_INSTALL_DIR" -maxdepth 1 -type d -name 'Xcode*.app' -print |
        sort
    ) | head -n 1
  )"
  rm -f "$before"

  if [[ -z "$extracted" ]]; then
    extracted="$(
      find "$XCODE_INSTALL_DIR" -maxdepth 1 -type d -name 'Xcode*.app' -print |
        sort |
        tail -n 1
    )"
  fi
  [[ -n "$extracted" && -x "$extracted/Contents/Developer/usr/bin/xcodebuild" ]] ||
    fail ".xip 解包后没有找到可用 Xcode.app"

  XCODE_APP="$extracted"
  printf 'Xcode installed at: %s\n' "$XCODE_APP"
}

install_xcode_from_app_store() {
  if [[ -n "$XCODE_XIP" ]]; then
    install_xcode_from_xip
    return
  fi
  if [[ "$INSTALL_XCODE" -ne 1 ]]; then
    fail "缺少完整 Xcode。请设置 XCODE_APP，或使用 --xcode-xip 指向已下载的兼容 Xcode .xip"
  fi

  log "准备通过 Mac App Store 安装 Xcode"
  warn "Mac App Store 只提供最新版 Xcode；如果它要求更高 macOS，请改用 --xcode-xip 安装旧版兼容 Xcode。"
  local available_gb
  available_gb="$(df -g /Applications | awk 'NR == 2 {print $4}')"
  if [[ "$available_gb" =~ ^[0-9]+$ ]] && [[ "$available_gb" -lt 40 ]]; then
    fail "安装 Xcode 建议至少保留 40 GB 可用空间，当前约 ${available_gb} GB"
  fi
  install_formula mas
  if ! mas install "$XCODE_APP_STORE_ID"; then
    fail "Xcode 安装失败。请先打开 Mac App Store 登录 Apple ID，再重新运行脚本"
  fi
}

select_and_initialize_xcode() {
  local app="$1"
  local developer_dir="$app/Contents/Developer"

  if [[ "$CHECK_ONLY" -eq 1 ]]; then
    if [[ "$(xcode-select -p 2>/dev/null || true)" != "$developer_dir" ]]; then
      fail "当前未选择完整 Xcode。需要执行：sudo xcode-select -s \"$developer_dir\""
    fi
    return
  fi

  log "选择并初始化 Xcode：$app"
  sudo xcode-select --switch "$developer_dir"
  sudo xcodebuild -license accept
  sudo xcodebuild -runFirstLaunch
}

verify_xcode_version() {
  local major
  major="$(xcodebuild -version | awk 'NR == 1 {split($2, parts, \".\"); print parts[1]}')"
  if [[ ! "$major" =~ ^[0-9]+$ ]] || [[ "$major" -lt 16 ]]; then
    fail "需要 Xcode 16 或更新版本，当前为：$(xcodebuild -version | head -n 1)"
  fi
}

ensure_ios_sdk() {
  if xcrun --sdk iphoneos --show-sdk-path >/dev/null 2>&1 &&
    xcrun --sdk iphonesimulator --show-sdk-path >/dev/null 2>&1; then
    return
  fi
  if [[ "$CHECK_ONLY" -eq 1 ]]; then
    fail "缺少 iOS SDK 或 Simulator SDK"
  fi

  log "下载 iOS 平台与 Simulator Runtime"
  xcodebuild -downloadPlatform iOS
  xcrun --sdk iphoneos --show-sdk-path >/dev/null 2>&1 ||
    fail "iOS Device SDK 安装失败"
  xcrun --sdk iphonesimulator --show-sdk-path >/dev/null 2>&1 ||
    fail "iOS Simulator SDK 安装失败"
}

generate_project() {
  if [[ "$CHECK_ONLY" -eq 1 ]]; then
    [[ -f "$IOS_ROOT/project.yml" ]] || fail "缺少 ios/project.yml"
    [[ -f "$IOS_ROOT/FilmLightMeter.xcodeproj/project.pbxproj" ]] ||
      fail "缺少已生成的 Xcode 工程"
    return
  fi

  log "生成 Xcode 工程"
  (
    cd "$IOS_ROOT"
    xcodegen generate --spec project.yml
  )
}

run_build_verification() {
  [[ "$RUN_BUILD" -eq 1 ]] || return

  log "执行 iOS Simulator 无签名构建"
  (
    cd "$IOS_ROOT"
    xcodebuild \
      -project FilmLightMeter.xcodeproj \
      -scheme FilmLightMeter \
      -configuration Debug \
      -sdk iphonesimulator \
      -destination 'generic/platform=iOS Simulator' \
      -derivedDataPath DerivedData \
      CODE_SIGNING_ALLOWED=NO \
      build
  )
}

print_signing_status() {
  local identity_count
  identity_count="$(
    security find-identity -v -p codesigning 2>/dev/null |
      grep -c '"Apple Development:' || true
  )"

  if [[ "$identity_count" -eq 0 ]]; then
    warn "未检测到 Apple Development 签名证书。Simulator 构建可用，但真机安装前需要在 Xcode 登录 Apple ID 并选择 Team。"
  else
    printf '检测到 Apple Development 签名证书：%s 个\n' "$identity_count"
  fi
}

print_summary() {
  local xcode_version
  local swift_version
  local sdk_version
  xcode_version="$(xcodebuild -version | tr '\n' ' ')"
  swift_version="$(xcrun swift --version | head -n 1)"
  sdk_version="$(xcrun --sdk iphoneos --show-sdk-version)"

  log "环境检查完成"
  printf 'Xcode:   %s\n' "$xcode_version"
  printf 'Swift:   %s\n' "$swift_version"
  printf 'iOS SDK: %s\n' "$sdk_version"
  printf 'XcodeGen: %s\n' "$(xcodegen --version)"
  printf '\n工程：%s/FilmLightMeter.xcodeproj\n' "$IOS_ROOT"
  printf 'Simulator 构建产物：%s/DerivedData/Build/Products/Debug-iphonesimulator/Click & Click.app\n' "$IOS_ROOT"
  printf '\n真机运行：用 Xcode 打开工程，在 Signing & Capabilities 选择 Team 后运行。\n'
  printf '可分发 IPA：需要 Apple Developer Program、分发证书和 Provisioning Profile。\n'
}

main() {
  parse_arguments "$@"
  [[ "$(uname -s)" == "Darwin" ]] || fail "该脚本只能在 macOS 上运行"

  configure_homebrew_path
  install_homebrew

  log "安装 iOS 工程工具"
  if [[ -z "${HOMEBREW_NO_AUTO_UPDATE:-}" && "$CHECK_ONLY" -ne 1 ]]; then
    brew update
  fi
  install_formula xcodegen

  local xcode_app
  if ! xcode_app="$(detect_xcode_app)"; then
    install_xcode_from_app_store
    xcode_app="$(detect_xcode_app)" ||
      fail "安装命令结束后仍未找到 Xcode.app"
  fi

  select_and_initialize_xcode "$xcode_app"
  verify_xcode_version
  ensure_ios_sdk
  generate_project
  run_build_verification
  print_signing_status
  print_summary
}

main "$@"
