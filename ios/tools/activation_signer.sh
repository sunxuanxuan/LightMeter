#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="$SCRIPT_DIR/../.build/tools"
BINARY="$BUILD_DIR/activation-signer"

mkdir -p "$BUILD_DIR"
if [[ ! -x "$BINARY" || "$SCRIPT_DIR/activation_signer.swift" -nt "$BINARY" ]]; then
  swiftc "$SCRIPT_DIR/activation_signer.swift" -o "$BINARY"
fi
"$BINARY" "$@"
