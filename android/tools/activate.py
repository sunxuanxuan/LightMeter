#!/usr/bin/env python3
"""Generate a FilmLightMeter activation code for a device ID."""

from __future__ import annotations

import argparse
import hashlib
import hmac
import re


SECRET = bytes.fromhex(
    "46696C6D4C696768744D657465722D41"
    "637469766174696F6E2D536563726574"
)
DEVICE_ID_PATTERN = re.compile(r"^[0-9A-F]{16}$")


def normalize_device_id(value: str) -> str:
    """Normalize and validate a 16-character hexadecimal device ID."""
    device_id = value.replace("-", "").replace(" ", "").upper()
    if not DEVICE_ID_PATTERN.fullmatch(device_id):
        raise ValueError("设备 ID 必须是 16 位十六进制字符（0-9、A-F）")
    return device_id


def generate_activation_code(device_id: str) -> str:
    """Generate the device-bound activation code used by the Android app."""
    normalized_device_id = normalize_device_id(device_id)
    digest = hmac.new(
        SECRET,
        normalized_device_id.encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()[:16].upper()
    return "-".join(
        digest[index : index + 4]
        for index in range(0, len(digest), 4)
    )


def main() -> int:
    parser = argparse.ArgumentParser(
        description="根据 FilmLightMeter 设备 ID 生成激活码",
    )
    parser.add_argument(
        "device_id",
        help="App 激活页面显示的 16 位设备 ID",
    )
    args = parser.parse_args()

    try:
        device_id = normalize_device_id(args.device_id)
    except ValueError as error:
        parser.error(str(error))

    activation_code = generate_activation_code(device_id)
    print(f"设备 ID：{device_id}")
    print(f"激活码：{activation_code}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
