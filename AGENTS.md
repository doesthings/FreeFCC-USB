# AGENTS.md — Instructions for AI Agents

You are continuing work on the **FreeFCC-N1** project — a universal,
fully-offline, open-source Android app that unlocks FCC mode (and 4G,
Remote ID, LED, device-info) on DJI controllers.

## What this project is

FreeFCC-N1 extends the [FreeFCC](https://github.com/doesthings/FreeFCC)
concept to cover DJI controllers that connect via **USB cable to a
phone/tablet** (RC-N1, RC-N2) — the remotes that ship with the Mini 3,
Mini 3 Pro, Mini 4 Pro, Air 3, etc. — in addition to the smart-controller
TCP path that FreeFCC already covers.

The app auto-detects the transport:
- **USB accessory/device** (phone cabled to RC-N1/RC-N2 or directly to the
  drone's USB-C port)
- **TCP loopback** (smart controllers: RC2, RC Pro, RC Plus — app runs ON
  the controller itself, talks to the DUMPL proxy at 127.0.0.1:40009)

The same DUMPL frames go over both. Only the carrier differs.

## Why this exists

The existing open-source FCC tools (M4TH1EU/DJI-FCC-HACK, FreeFCC, OpenFCC)
only cover the smart-controller TCP path. The Mini 3 / Mini 3 Pro / Mini 4
Pro / Air 3 ship with an RC-N1 or RC-N2 — a USB remote, not a smart
controller. FreeFCC-N1 fills that gap by implementing the USB transport
path using Android's standard `UsbAccessory` / `UsbDevice` APIs.

## State of the project

### Done
- ✅ Gradle project structure (build.gradle.kts, settings.gradle.kts, gradle.properties, manifest)
- ✅ DUMPL frame builder with CRC-8/CRC-16 tables matching the dji-firmware-tools reference
- ✅ Dual transport: TcpTransport (smart controllers) + UsbTransport (phone+USB RC / direct-to-drone)
- ✅ Universal FCC profile JSON (21 frames)
- ✅ CE-restore profile (1 frame, universal undo)
- ✅ 4G activation profile (128 frames, runtime serial injection)
- ✅ Remote ID disable/enable profiles (FLIGHT_CONTROLLER param 0x025D write)
- ✅ Device-info query profile (GENERAL VersionInquiry)
- ✅ LED on/off profiles (wrapped frame format, port 40007)
- ✅ Profile loader (JSON → wire-format frames)
- ✅ ViewModel with all business logic, honest success reporting
- ✅ Compose UI: 4-page pager (FCC / Info / Log / About)
- ✅ Foreground service (DumlForegroundService) for USB-link lifetime
- ✅ Launcher icon (vector, adaptive)
- ✅ README.md, AGENTS.md, PROTOCOL.md

### Not done / future work
- ❌ Not tested on real Mini 3 non-pro hardware — the app is untested
- ❌ No Frida script to capture per-model 4G profiles (only `wa341` is captured)
- ❌ No direct USB-device-mode test (only accessory mode is well-tested in
     the reference apps)

## How to build

Requirements: Java 17+, Android SDK 35.

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

cd C:\projects\formini3nonpro
java -classpath gradle\wrapper\gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain assembleRelease --no-daemon
```

Sign the output APK with your own keystore or the debug one. The build is
configured to sign with the debug keystore automatically for `release`.

## Architecture

```
app/src/main/
  assets/profiles/
    fcc.json              21 frames, FCC unlock (universal)
    ce_restore.json       1 frame, reset to factory region
    4g.json               128-frame template (serial injected at runtime)
    remote_id_off.json    1 frame, disable Remote ID broadcast
    remote_id_on.json    1 frame, re-enable Remote ID
    device_info.json     1 frame, VersionInquiry
    led_on.json          1 frame, LED on (wrapped, port 40007)
    led_off.json         1 frame, LED off (wrapped, port 40007)
  java/com/freefcc/n1/
    DumplBuilder.kt       Frame builder + CRC-8/CRC-16 tables
    DumplTransport.kt     DumplTransport interface + TcpTransport + UsbTransport
    ProfileLoader.kt     JSON profile loader + 4G serial injection + LED wrapper
    FccViewModel.kt       State management + business logic
    MainActivity.kt       Compose UI (4 pages)
    DumlForegroundService.kt  Keeps USB link alive
  res/
    drawable/             Launcher icon (vector)
    mipmap-anydpi-v26/     Adaptive icon
    values/               Theme + strings
    xml/                  Network security config + USB device filter
```

## Key design decisions

### 1. Dual transport auto-detection
`FccViewModel.connectInternal()` tries USB first (`UsbTransport.openAttached`),
then falls back to TCP (`TcpTransport` at `127.0.0.1:40009`). The same
DUMPL frames go over both. This is what makes the app universal.

### 2. Universal FCC profile
The FCC profile is a fixed 21-frame DUMPL command sequence that works
across DJI's modern controller fleet. We ship it as a JSON asset. No
server, no per-device profile, no encryption.

### 3. Honest success reporting
`FccViewModel.sendProfile()` tracks whether `write()` actually returned
true for at least one frame, and only reports "FCC enabled" if so. This
fixes a common false-success bug (where the socket opened but every
`write()` silently failed, yet the app reported success).

### 4. No license, no server, no tracking
- No SharedPreferences for license keys
- No HTTP client, no INTERNET permission used (declared only for parity)
- No integrity check, no self-update
- `network_security_config.xml` blocks cleartext traffic entirely

### 5. Per-frame TCP connection
On TCP, the DUMPL proxy on smart controllers expects one frame per TCP
connection (open → write → read ACK → close). `sendProfile()` does this
when `t is TcpTransport`. On USB the connection stays open — we just
bulk-write each frame.

## The DUMPL protocol (quick reference)

Wire format (big-endian, 0x55 magic):
```
[0]  0x55 (magic)
[1]  length low (total frame length L, 11-bit)
[2]  length high nibble | 0x04 (version=1)
[3]  CRC-8 (poly 0x8C, init 0x77) over bytes 0-2
[4]  sender (type + index)
[5]  receiver (type + index)
[6-7] sequence (LE, starts at 4096)
[8]  cmd_type (packet_type + ack_type + encrypt_type)
[9]  cmd_set
[10] cmd_id
[11..N-2] payload
[N-1..N] CRC-16 (poly 0x1021 reflected, init 0x3692) over bytes 0..N-3
```

Max frame size: 1023 bytes. CRC-16 stored little-endian.

## Common sender/destination bytes

| Byte | Hex | Meaning |
|------|-----|---------|
| 130  | 0x82 | MOBILE_APP, idx 4 (the phone app) |
| 2    | 0x02 | CAMERA, idx 0 (4G frames use this) |
| 32   | 0x20 | RADIO baseband (CE-restore dst) |
| 6    | 0x06 | REMOTE_RADIO |
| 3    | 0x03 | FLIGHT_CONTROLLER |
| 18   | 0x12 | SVO (service mode enter/exit) |
| 9    | 0x09 | LB_MCU_SKY (LB sky module) |
| 238  | 0xEE | OFDM_GROUND idx 7 (4G dst) |
| 111  | 0x6F | LB_68013_SKY idx 3 (country code) |

## Common cmd_set values

| Set | Meaning |
|-----|---------|
| 0   | GENERAL (version inquiry, country code, activate) |
| 3   | FLIGHT_CONTROLLER (param writes — FCC region, Remote ID) |
| 6   | RADIO (region set, reset, parameters) |
| 7   | WIFI (channel groups, channel map) |
| 9   | OFDM (2.4G/5.8G power limits) |
| 16  | AUTOTEST (service mode enter/exit) |
| 81  | 0x51 — 4G command set |

## If something doesn't work

1. **App crashes on launch**: check `adb logcat | Select-String "AndroidRuntime"`.
   Most likely a Compose import is missing or a method signature changed.

2. **"Controller not found"**: no USB accessory/device attached AND no TCP
   proxy at 127.0.0.1:40009. Plug in the RC via USB (phone path) or run on
   a smart controller (TCP path).

3. **"FCC apply failed — RC link unreachable"**: `write()` returned false for
   every frame. Check: (a) drone is powered on and linked to the RC,
   (b) USB accessory permission granted (the intent filter should prompt),
   (c) on smart controllers, the DUMPL proxy at 40009 is running.

4. **FCC applies but radio doesn't change**: firmware-level block. DJI may
   have closed the DUMPL param write path in a firmware update. No app can
   fix this — try downgrading firmware or using a different firmware version.

5. **Remote ID doesn't toggle**: the param index `0x025D` (605) may differ
   on your firmware. Capture the actual param with Frida on a DJI Fly
   session that toggles RID, and update `remote_id_off.json` / `remote_id_on.json`.

## Legal

This is an educational project. The DUMPL protocol is publicly documented
in the dji-firmware-tools project (GPL-3.0). The FCC profile bytes were
captured via loopback on a DJI smart controller.