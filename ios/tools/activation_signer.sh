#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BINARY="$(mktemp -t film-light-meter-activation-signer)"
trap 'rm -f "$BINARY"' EXIT

swiftc "$SCRIPT_DIR/activation_signer.swift" -o "$BINARY"
"$BINARY" "$@"
