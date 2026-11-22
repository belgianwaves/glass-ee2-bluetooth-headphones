# Google Glass Enterprise Edition 2 — Bluetooth Headphones (`A2DP Audio Sink` + `AVRCP`)

Turn a **Google Glass Enterprise Edition 2** (`OPM1.221111.001` / Android 8.1 Oreo) into standard **Bluetooth stereo headphones (`A2DP Audio Sink` + `AVRCP Controller`)** compatible with **iOS (iPhone / iPad), Android, macOS, Windows, and Linux**.

Out of the box, Google Glass EE2 only supports **A2DP Source** (sending audio *from* Glass to external earbuds) and refuses incoming Bluetooth audio streams *from* a phone or computer to its built-in temple speaker.

This repository unlocks the dormant Android Auto / Fluoride A2DP Sink stack already compiled inside EE2's firmware — systemlessly via Magisk, without modifying `/system` or `/vendor` on flash, and without redistributing any proprietary Qualcomm/Google binaries in Git.

---

## Why Stock Glass EE2 Cannot Act as Headphones (The 4 Hidden Gates)

Enabling `A2DP Sink` on Glass EE2 (`OPM1.221111.001`) requires clearing four separate gates across Java, SELinux properties, ARM64 Fluoride native code, and Android AudioFocus:

1. **AOSP RRO Resource Gate (`Bluetooth.apk`):**
   `profile_supported_a2dp_sink` and `profile_supported_avrcp_controller` default to `false`, and `<service android:name=".a2dpsink.A2dpSinkService" android:enabled="false"/>` is disabled in the manifest.
   - **Fix:** Static RRO overlay (`GlassA2dpSinkOverlay.apk`, priority `99`) + `pm enable` on `A2dpSinkService`, `A2dpMediaBrowserService`, and `AvrcpControllerService` (and disabling `A2dpService` Source + `HeadsetService`).

2. **Qualcomm's `Config.addAudioProfiles()` Property Gate (`Bluetooth.vdex`):**
   In addition to AOSP's XML booleans, Qualcomm's `com.android.bluetooth.btservice.Config` checks `SystemProperties.getBoolean("persist.service.bt.a2dp.sink", false)` and `persist.service.bt.avrcp.controller`.
   - **Fix:** `service.sh` sets `persist.service.bt.a2dp.sink=true` and `persist.service.bt.avrcp.controller=true` via `init`'s property service.

3. **Fluoride Native SDP Record & iOS Class of Device Gate (`/system/lib64/hw/bluetooth.default.so`):**
   Even when `A2dpSinkService` starts in Java, Google compiled EE2's `bluetooth.default.so` with `BTA_AV_SINK_INCLUDED=FALSE`:
   - The SBC decoder (`btif_a2dp_sink_worker_thread`), `AVDT_TSEP_SNK` endpoint, `AudioTrack` output, and `/vendor/etc/a2dp_audio_policy_configuration.xml` routes are **all present**, *except* `bta_av_api_register` skips calling `A2DP_AddRecord(0x110B)` (`UUID_SERVCLASS_AUDIO_SINK`)! Without `0x110B` in SDP/EIR, phones never discover an audio sink.
   - Stock `BTA_DM_COD` advertises `0x5A020C` (`Major = 0x02 Phone / Smartphone`), which **iOS (`Settings → Bluetooth`) hides** from headphone pairing.
   - **Fix:** `scripts/build_magisk_module.py` pulls `/system/lib64/hw/bluetooth.default.so` from your own EE2, patches 4 ARM64 instructions in `bta_av_api_register` / `bta_av_dereg_comp` + the 3-byte `BTA_DM_COD` (`0x240418` = `Audio | Rendering`, `Audio/Video`, `Headphones`), and bind-mounts it systemlessly via Magisk.

4. **Android 8.1 Car Head-Unit Mute Quirk (`A2dpSinkStreamHandler`):**
   In Android 8.1 (`OPM1`), AOSP designed `A2dpSinkService` for Android Auto dashboards and keeps the native Fluoride `AudioTrack` muted (`STATE_FOCUS_LOST`) when a phone starts streaming until a local `MediaBrowser` client connected to `A2dpMediaBrowserService` dispatches `play()` (`SNK_PLAY` -> `informAudioFocusStateNative(1)` + gain `1.0f`).
   - **Fix:** Tiny 16 KB zero-dependency app (`dist/GlassHeadphoneBridge.apk`, source in `bridge-app/`) that runs on boot and automatically grants sink audio focus whenever streaming starts (automatically yields if `GlassMate` is installed, which has the same bridge built in).

---

## Prerequisites

- **Google Glass Enterprise Edition 2** running final factory firmware **`OPM1.221111.001`** ([official Google Glass EE2 system images](https://developers.google.com/glass-enterprise/downloads/system-images)).
- **Rooted with Magisk v26.4 (`LEGACYSAR=true`):**
  > **Important Magisk Note for EE2 (Android 8.1 System-as-Root):**
  > Magisk v27+ dropped legacy Android 8.1 `skip_initramfs -> want_initramfs` kernel hexpatching. Use **Magisk v26.4** to patch `OPM1.221111.001`'s `boot.img` with `LEGACYSAR=true KEEPVERITY=false KEEPFORCEENCRYPT=true`, and disable vbmeta verification before flashing:
  > ```bash
  > adb reboot bootloader
  > fastboot flashing unlock   # (if not already unlocked; wipes userdata once)
  > fastboot --disable-verity --disable-verification flash vbmeta vbmeta.img
  > fastboot flash boot_a magisk26_sar.img
  > fastboot flash boot_b magisk26_sar.img
  > fastboot reboot
  > ```
- `python3` and `adb` on your computer.

---

## Quick Start

### 1. Build the Magisk Module from Your Connected EE2

Connect your Glass EE2 over USB with USB Debugging enabled and run:

```bash
python3 scripts/build_magisk_module.py
```

This pulls `/system/lib64/hw/bluetooth.default.so` directly from your Glass EE2, verifies its `OPM1.221111.001` SHA-256 (`e1258d8466403ff8235160e57498e340fac4ae0e693927a23a3b78bc67b13f7f`), applies the 5 byte patches, and outputs:

```
dist/glass_ee2_a2dp_sink_magisk.zip
```

### 2. Install the Module & Audio-Focus Bridge

```bash
# Install the 16 KB audio-focus bridge APK (skip if you already run GlassMate)
adb install -r dist/GlassHeadphoneBridge.apk

# Push the Magisk module to Glass Downloads
adb push dist/glass_ee2_a2dp_sink_magisk.zip /sdcard/Download/
```

On Glass EE2:
1. Open **Magisk** → **Modules** → **Install from storage** → select `glass_ee2_a2dp_sink_magisk.zip`.
2. Tap **Reboot**.

### 3. Pair Your Phone or Computer

1. Open **Bluetooth Headphones** in the Glass app launcher (or run `adb shell am start -a android.settings.BLUETOOTH_SETTINGS`) so Glass displays **`Visible as "Glass Enterprise Edition 2" to other devices`**.
2. On your **iPhone / Android / Mac / PC**, open **Settings → Bluetooth** and select **`Glass Enterprise Edition 2`**.
3. Confirm **`Pair`** on your phone and **tap the Glass touchpad** when `Tap to pair` appears.
4. Play any media, call/notification audio, or DRM video on your phone — audio routes natively to the Glass EE2 temple speaker!

---

## Binary Patch Reference (`OPM1.221111.001` `bluetooth.default.so`)

For complete auditability, here are the exact 5 patches applied by `scripts/build_magisk_module.py` to `/system/lib64/hw/bluetooth.default.so` (`3,058,296` bytes):

| File Offset | Virtual Addr | Function / Symbol | Original Bytes & Instruction | Patched Bytes & Instruction | Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `0x02FB10` | `0x040B10` | `bta_av_api_register` | `48 21 82 52 9F 02 08 6B`<br>`mov w8, #0x110a; cmp w20, w8` | `88 7E 01 53 1F 15 22 71`<br>`lsr w8, w20, #1; cmp w8, #0x885` | Enter SDP registration block for both `0x110A` (Source) and `0x110B` (Sink) |
| `0x02FB2C` | `0x040B2C` | `bta_av_api_register` | `40 21 82 52`<br>`mov w0, #0x110a` | `E0 03 14 2A`<br>`mov w0, w20` | Pass `0x110B` (`UUID_SERVCLASS_AUDIO_SINK`) to `A2DP_AddRecord()` |
| `0x02FB44` | `0x040B44` | `bta_av_api_register` | `40 21 82 52`<br>`mov w0, #0x110a` | `E0 03 14 2A`<br>`mov w0, w20` | Pass `0x110B` to `bta_sys_add_uuid()` (EIR + Adapter UUIDs) |
| `0x02DB64` | `0x03EB64` | `bta_av_dereg_comp` | `40 21 82 52`<br>`mov w0, #0x110a` | `60 21 82 52`<br>`mov w0, #0x110b` | Cleanly remove `0x110B` UUID on Bluetooth disable |
| `0x26E74C` | `0x27F74C` | `bta_dm_cfg.dev_class` | `5A 02 0C`<br>`Phone / Smartphone` | `24 04 18`<br>`Audio\|Rendering, Audio/Video, Headphones` | Advertise standard Bluetooth Headphones CoD (`0x240418`) for iOS discovery |

- **Stock `bluetooth.default.so` SHA-256:** `e1258d8466403ff8235160e57498e340fac4ae0e693927a23a3b78bc67b13f7f`
- **Patched `bluetooth.default.so` SHA-256:** `609f4b7f545982258da909544a84cdc874c1cfb6c689ee7fb322f9ac3e491c61`

---

## Rebuilding the APKs from Source (Optional)

If you modify `bridge-app/` or `overlay-src/` and have the Android SDK installed (`ANDROID_HOME`):

```bash
./scripts/build_bridge_apk.sh
```

## Uninstalling / Reverting to Stock A2DP Source

Disable or remove **Glass EE2 Bluetooth Headphone (A2DP Sink + AVRCP)** in Magisk Manager, run:

```bash
adb shell su -c '
  setprop persist.service.bt.a2dp.sink false
  setprop persist.service.bt.avrcp.controller false
  pm enable com.android.bluetooth/.a2dp.A2dpService
  pm enable com.android.bluetooth/.hfp.HeadsetService
  pm disable com.android.bluetooth/.a2dpsink.A2dpSinkService
  pm disable com.android.bluetooth/.a2dpsink.mbs.A2dpMediaBrowserService
  pm disable com.android.bluetooth/.avrcpcontroller.AvrcpControllerService
'
adb uninstall com.bw.glass.headphonebridge
adb reboot
```

---

## License

MIT License — see [LICENSE](LICENSE). (`magisk-template/META-INF/com/google/android/update-binary` is Magisk's standard `module_installer.sh`, Apache-2.0.)
