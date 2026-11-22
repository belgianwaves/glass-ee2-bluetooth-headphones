#!/usr/bin/env python3
"""
Builds `dist/glass_ee2_a2dp_sink_magisk.zip` for Google Glass Enterprise Edition 2
(`OPM1.221111.001` / Android 8.1 Oreo) without redistributing proprietary binaries in Git.

Usage:
  1. Connect Glass EE2 via USB with ADB enabled (or pass `--input /path/to/bluetooth.default.so`):
       python3 scripts/build_magisk_module.py
  2. Push & install `dist/glass_ee2_a2dp_sink_magisk.zip` in Magisk Manager -> Modules -> Install from storage.
"""

import argparse
import hashlib
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
TEMPLATE_DIR = REPO_ROOT / "magisk-template"
DEFAULT_OUT = REPO_ROOT / "dist" / "glass_ee2_a2dp_sink_magisk.zip"

STOCK_SHA256_OPM1_221111_001 = "e1258d8466403ff8235160e57498e340fac4ae0e693927a23a3b78bc67b13f7f"
PATCHED_SHA256_OPM1_221111_001 = "609f4b7f545982258da909544a84cdc874c1cfb6c689ee7fb322f9ac3e491c61"

# Exact patches for Glass EE2 OPM1.221111.001 /system/lib64/hw/bluetooth.default.so:
# 1. bta_av_api_register (va 0x40b10, file off 0x2fb10):
#      mov w8, #0x110a ; cmp w20, w8   ->   lsr w8, w20, #1 ; cmp w8, #0x885
#    Accepts both 0x110A (AudioSource) and 0x110B (AudioSink) for SDP record creation.
# 2. bta_av_api_register (va 0x40b2c, file off 0x2fb2c):
#      mov w0, #0x110a                 ->   mov w0, w20
#    Passes `profile_initialized` (0x110B for Sink) to A2DP_AddRecord().
# 3. bta_av_api_register (va 0x40b44, file off 0x2fb44):
#      mov w0, #0x110a                 ->   mov w0, w20
#    Passes `profile_initialized` (0x110B for Sink) to bta_sys_add_uuid().
# 4. bta_av_dereg_comp (va 0x3eb64, file off 0x2db64):
#      mov w0, #0x110a                 ->   mov w0, #0x110b
#    Removes 0x110B from EIR on Bluetooth teardown.
# 5. bta_dm_cfg.dev_class / BTA_DM_COD (va 0x27f74c, file off 0x26e74c):
#      5a 02 0c (Phone / Smartphone)   ->   24 04 18 (Audio|Rendering, Audio/Video, Headphones)
#    Required so iOS Settings -> Bluetooth displays Glass EE2 as pairable headphones.
PATCHES = [
    (
        "bta_av_api_register UUID gate (0x2fb10)",
        0x2FB10,
        struct.pack("<II", 0x52822148, 0x6B08029F),
        struct.pack("<II", 0x53017E88, 0x7122151F),
    ),
    (
        "A2DP_AddRecord UUID arg (0x2fb2c)",
        0x2FB2C,
        struct.pack("<I", 0x52822140),
        struct.pack("<I", 0x2A1403E0),
    ),
    (
        "bta_sys_add_uuid UUID arg (0x2fb44)",
        0x2FB44,
        struct.pack("<I", 0x52822140),
        struct.pack("<I", 0x2A1403E0),
    ),
    (
        "bta_av_dereg_comp UUID arg (0x2db64)",
        0x2DB64,
        struct.pack("<I", 0x52822140),
        struct.pack("<I", 0x52822160),
    ),
    (
        "BTA_DM_COD Class of Device -> Headphones 0x240418 (0x26e74c)",
        0x26E74C,
        b"\x5a\x02\x0c",
        b"\x24\x04\x18",
    ),
]


def find_adb() -> str:
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    candidates = []
    if sdk:
        candidates.append(os.path.join(sdk, "platform-tools", "adb"))
    candidates.append(os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"))
    candidates.append(os.path.expanduser("~/Android/Sdk/platform-tools/adb"))
    found = shutil.which("adb")
    if found:
        candidates.append(found)
    for c in candidates:
        if c and os.path.isfile(c) and os.access(c, os.X_OK):
            return c
    raise RuntimeError("Could not find `adb`. Set ANDROID_HOME or pass --input /path/to/bluetooth.default.so")


def pull_bluetooth_so(adb_bin: str, serial: str | None, dest: Path) -> None:
    cmd = [adb_bin]
    if serial:
        cmd += ["-s", serial]
    cmd += ["pull", "/system/lib64/hw/bluetooth.default.so", str(dest)]
    print("Pulling /system/lib64/hw/bluetooth.default.so from Glass EE2...")
    subprocess.run(cmd, check=True)


def patch_bluetooth_so(raw: bytes) -> bytes:
    in_sha = hashlib.sha256(raw).hexdigest()
    print(f"Input bluetooth.default.so: {len(raw)} bytes (sha256={in_sha})")
    if in_sha == PATCHED_SHA256_OPM1_221111_001:
        print("Input binary is already patched for OPM1.221111.001 — keeping as-is.")
        return raw

    buf = bytearray(raw)
    for desc, offset, expected, replacement in PATCHES:
        actual = bytes(buf[offset : offset + len(expected)])
        if actual == replacement:
            print(f"  [already applied] {desc}")
            continue
        if actual != expected:
            raise RuntimeError(
                f"Byte mismatch at {desc}: expected {expected.hex()}, found {actual.hex()}. "
                f"Ensure your Glass EE2 is running firmware OPM1.221111.001."
            )
        buf[offset : offset + len(expected)] = replacement
        print(f"  [patched] {desc}")

    out_bytes = bytes(buf)
    out_sha = hashlib.sha256(out_bytes).hexdigest()
    print(f"Patched bluetooth.default.so sha256={out_sha}")
    if in_sha == STOCK_SHA256_OPM1_221111_001 and out_sha != PATCHED_SHA256_OPM1_221111_001:
        raise RuntimeError("Unexpected output SHA-256 after patching!")
    return out_bytes


def build_zip(patched_so: bytes, out_zip: Path) -> None:
    out_zip.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        stage = Path(tmp) / "module"
        shutil.copytree(TEMPLATE_DIR, stage)
        so_dest = stage / "system" / "lib64" / "hw" / "bluetooth.default.so"
        so_dest.parent.mkdir(parents=True, exist_ok=True)
        so_dest.write_bytes(patched_so)

        with zipfile.ZipFile(out_zip, "w", compression=zipfile.ZIP_DEFLATED) as zf:
            for root, dirs, files in os.walk(stage):
                rel_root = Path(root).relative_to(stage)
                for f in sorted(files):
                    full_path = Path(root) / f
                    arcname = str(rel_root / f) if str(rel_root) != "." else f
                    zf.write(full_path, arcname)

    zip_sha = hashlib.sha256(out_zip.read_bytes()).hexdigest()
    print(f"\nBuilt Magisk module: {out_zip} ({out_zip.stat().st_size} bytes, sha256={zip_sha})")


def main() -> None:
    parser = argparse.ArgumentParser(description="Build Glass EE2 A2DP Sink Headphone Magisk module.")
    parser.add_argument("--input", type=Path, help="Path to stock EE2 /system/lib64/hw/bluetooth.default.so")
    parser.add_argument("--serial", "-s", help="Optional ADB device serial")
    parser.add_argument("--output", "-o", type=Path, default=DEFAULT_OUT, help="Output Magisk zip path")
    args = parser.parse_args()

    if args.input:
        raw = args.input.read_bytes()
    else:
        adb_bin = find_adb()
        with tempfile.TemporaryDirectory() as tmp:
            pulled = Path(tmp) / "bluetooth.default.so"
            pull_bluetooth_so(adb_bin, args.serial, pulled)
            raw = pulled.read_bytes()

    patched = patch_bluetooth_so(raw)
    build_zip(patched, args.output)


if __name__ == "__main__":
    main()
