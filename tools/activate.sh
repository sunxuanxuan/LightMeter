#!/bin/bash
# ============================================================
# FilmLightMeter Activation Code Generator
# ============================================================
# Usage: ./activate.sh <device_id>
#
# The device_id is a 16-char hex string shown in the app's
# activation screen. This script generates the matching
# activation code using the same HMAC-SHA256 secret.
# ============================================================

set -euo pipefail

if [ $# -lt 1 ]; then
    echo "Usage: $0 <device_id>"
    echo ""
    echo "  device_id : 16-char hex fingerprint shown in the app"
    echo ""
    echo "Example:"
    echo "  $0 A1B2C3D4E5F6A7B8"
    exit 1
fi

DEVICE_ID="$1"

# Validate input: 16 hex chars
if [[ ! "$DEVICE_ID" =~ ^[0-9A-Fa-f]{16}$ ]]; then
    echo "Error: device_id must be exactly 16 hex characters (0-9, A-F)"
    exit 1
fi

# Same secret as ActivationManager.kt (hex-encoded)
# "FilmLightMeter-Activation-Secret"
SECRET_HEX="46696C6D4C696768744D657465722D41637469766174696F6E2D536563726574"

# Compute HMAC-SHA256, take first 8 bytes (16 hex chars), uppercase
CODE=$(echo -n "$DEVICE_ID" | openssl dgst -sha256 -mac HMAC -macopt "hexkey:$SECRET_HEX" -binary | xxd -p -l 8 | tr 'a-f' 'A-F')

# Format as XXXX-XXXX-XXXX-XXXX
FORMATTED=$(echo "$CODE" | sed 's/.\{4\}/&-/g' | sed 's/-$//')

echo ""
echo "  Device ID     : $DEVICE_ID"
echo "  Activation Code: $FORMATTED"
echo ""
echo "  Send this code back to the user."
echo ""