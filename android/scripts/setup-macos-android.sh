#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
SHELL_RC="${SHELL_RC:-$HOME/.zshrc}"

log() {
  printf '\n==> %s\n' "$1"
}

warn() {
  printf '\n[warn] %s\n' "$1" >&2
}

ensure_command() {
  local command_name="$1"
  local install_hint="$2"

  if ! command -v "$command_name" >/dev/null 2>&1; then
    printf '[error] Missing command: %s\n%s\n' "$command_name" "$install_hint" >&2
    exit 1
  fi
}

append_if_missing() {
  local line="$1"
  local file="$2"

  touch "$file"
  if ! grep -Fqx "$line" "$file"; then
    printf '%s\n' "$line" >> "$file"
  fi
}

install_homebrew_package() {
  local package_name="$1"

  if brew list "$package_name" >/dev/null 2>&1; then
    printf 'Already installed: %s\n' "$package_name"
  else
    brew install "$package_name"
  fi
}

detect_java_home() {
  if /usr/libexec/java_home -v 17 >/dev/null 2>&1; then
    /usr/libexec/java_home -v 17
    return
  fi

  if [[ -d /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ]]; then
    printf '/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home\n'
    return
  fi

  if [[ -d /usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ]]; then
    printf '/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home\n'
    return
  fi

  return 1
}

configure_shell_env() {
  local java_home="$1"

  log "Writing Android and Java environment variables to $SHELL_RC"
  append_if_missing '' "$SHELL_RC"
  append_if_missing '# LightMeter Android development environment' "$SHELL_RC"
  append_if_missing "export JAVA_HOME=\"$java_home\"" "$SHELL_RC"
  append_if_missing 'export ANDROID_HOME="$HOME/Library/Android/sdk"' "$SHELL_RC"
  append_if_missing 'export ANDROID_SDK_ROOT="$ANDROID_HOME"' "$SHELL_RC"
  append_if_missing 'export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"' "$SHELL_RC"
}

main() {
  log "Checking host system"
  if [[ "$(uname -s)" != "Darwin" ]]; then
    printf '[error] This setup script is intended for macOS.\n' >&2
    exit 1
  fi

  ensure_command brew "Install Homebrew first: https://brew.sh"

  log "Installing JDK 17 and Android SDK command line tools"
  brew update
  install_homebrew_package openjdk@17
  install_homebrew_package android-commandlinetools

  local java_home
  if ! java_home="$(detect_java_home)"; then
    printf '[error] JDK 17 was installed but JAVA_HOME could not be detected.\n' >&2
    exit 1
  fi

  export JAVA_HOME="$java_home"
  export ANDROID_HOME="$ANDROID_SDK_ROOT"
  export ANDROID_SDK_ROOT="$ANDROID_SDK_ROOT"
  export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

  log "Preparing Android SDK directory"
  mkdir -p "$ANDROID_SDK_ROOT"

  ensure_command sdkmanager "sdkmanager not found. Ensure android-commandlinetools is installed and PATH includes cmdline-tools/latest/bin."

  log "Installing Android SDK components"
  yes | sdkmanager --sdk_root="$ANDROID_SDK_ROOT" --licenses >/dev/null || true
  sdkmanager --sdk_root="$ANDROID_SDK_ROOT" \
    "platform-tools" \
    "platforms;android-35" \
    "build-tools;35.0.0"

  configure_shell_env "$java_home"

  log "Verifying local toolchain"
  java -version
  sdkmanager --list_installed | grep -E 'platform-tools|platforms;android-35|build-tools;35.0.0' || true

  log "Checking Gradle wrapper"
  cd "$PROJECT_ROOT"
  ./gradlew --version

  log "Setup complete"
  printf 'Open a new shell or run:\n'
  printf '  source "%s"\n\n' "$SHELL_RC"
  printf 'Then build the project:\n'
  printf '  cd "%s"\n' "$PROJECT_ROOT"
  printf '  ./gradlew assembleDebug\n'
}

main "$@"
