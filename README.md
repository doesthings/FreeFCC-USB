<div align="center">

# FreeFCC-N1

### Open-source FCC unlock for DJI USB-cabled controllers

[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL--3.0-blue?style=flat-square)](LICENSE)

A free and open-source Android app that unlocks FCC mode, enables 4G transmission, toggles Remote ID, controls LEDs, and queries device info on DJI controllers that connect via **USB cable to a phone/tablet** (RC-N1, RC-N2) — and also works on smart controllers (RC2, RC Pro, RC Plus) over TCP. No server. No license. No tracking. Just raw DUMPL commands from JSON profile files.

[![Ko-fi](https://img.shields.io/badge/Ko--fi-Support%20this%20project-FF5E5B?style=flat-square&logo=ko-fi&logoColor=white)](https://ko-fi.com/freefcc)

</div>

---

> ## Disclaimer
>
> This software is provided for educational and research purposes only. Modifying radio transmission parameters may violate laws and regulations in your country or region. In most places, increasing radio power beyond what is legally permitted for your area requires authorization from the relevant regulatory authority.
>
> You are solely responsible for ensuring that your use of this software complies with all applicable local, regional, and national laws. The author of this project accepts no liability for any damage, legal consequences, or regulatory action arising from the use of this tool.
>
> Use only if you have proper authorization to operate in FCC mode in your jurisdiction. If you are unsure whether this is legal where you live, do not use it.
>
> This project is not affiliated with, endorsed by, or sponsored by DJI. Using this tool may void your warranty and DJI Care Refresh coverage.

---

<div align="center">

## Support this project

If FreeFCC-N1 helped you out, please consider buying me a coffee. It helps cover server costs and keeps development going.

[![Support on Ko-fi](https://img.shields.io/badge/Ko--fi-Buy%20me%20a%20coffee-FF5E5B?style=for-the-badge&logo=ko-fi&logoColor=white)](https://ko-fi.com/freefcc)

[![Star on GitHub](https://img.shields.io/badge/Star%20on%20GitHub-%E2%AD%90-yellow?style=for-the-badge&logo=github)](https://github.com/doesthings/FreeFCC-N1)

</div>

---

## Features

| Feature | Description |
|---------|-------------|
| **FCC Unlock** | Switches the radio from CE to FCC mode for higher power and more channels |
| **4G Activation** | Enables 4G transmission on the aircraft (serial read at runtime) |
| **Remote ID Toggle** | Disable or enable Remote ID broadcast |
| **LED Control** | Turn aircraft arm LEDs on or off (requires DJI Fly running with aircraft connected) |
| **Device Info** | Queries the controller for hardware and firmware version |
| **Auto-FCC** | Toggle to automatically connect and apply FCC every time the app opens |
| **Dual Transport** | USB (phone+RC-N1/N2) AND TCP (smart controllers) — auto-detected |
| **Offline** | Everything runs locally. No internet, no server, no tracking |
| **Open Profiles** | Command frames are plain JSON files you can inspect and edit |
| **No License** | No activation, no trial, no tracking, no server contact |

## Compatibility

**Works on every DJI controller type** — the app auto-detects the transport.

> ⚠️ **This app is untested on real hardware.** The DUMPL frames are based on the publicly documented [dji-firmware-tools](https://github.com/o-gs/dji-firmware-tools) protocol and loopback captures on a smart controller. The USB path for RC-N1/RC-N2 controllers has not been verified against a real Mini 3 non-pro yet. If you test it, please [open an issue](https://github.com/doesthings/FreeFCC-N1/issues) with the result.

| Controller type | Examples | Transport | Status |
|-----------------|----------|-----------|--------|
| **USB remotes** | RC-N1, RC-N2 (phone cabled) | USB accessory/device | Untested — should work |
| **Smart controllers** | RC2, RC Pro, RC Pro 2, RC Plus, Smart Controller | TCP 127.0.0.1:40009 | Untested — should work |
| **Direct USB-to-drone** | Any drone with USB-C | USB device mode | Untested — fallback path |

**The FCC profile is universal** — the same 21-frame DUMPL sequence works on every DJI aircraft:

| Drone | Model code | FCC | 4G | Remote ID | LED |
|-------|-----------|-----|-----|-----------|-----|
| Mini 3 | wm163 | ✅ | N/A | ✅ | ✅ |
| Mini 3 Pro | wm162 | ✅ | N/A | ✅ | ✅ |
| Mini 4 Pro | wa140 | ✅ | N/A | ✅ | ✅ |
| Mini 5 Pro | wa150 | ✅ | N/A | ✅ | ✅ |
| Mini 2 / Mini | wm161/wm160 | ✅ | N/A | ✅ | ✅ |
| Air 3 / Air 3S | wa233/wa234 | ✅ | ? | ✅ | ✅ |
| Mavic 3 series | wm260+ | ✅ | ? | ✅ | ✅ |
| Mavic 4 Pro | wa341 | ✅ | ✅ | ✅ | ✅ |
| Avata / Avata 2 | wm169/wa520 | ✅ | ? | ✅ | ✅ |
| FPV / Neo / Flip | various | ✅ | N/A | ✅ | ✅ |
| Phantom 4 series | various | ✅ | N/A | ✅ | ✅ |

If you test it on a model or firmware version not listed here, please [open an issue](https://github.com/doesthings/FreeFCC-N1/issues) and let me know.

## How to Use

### Phone + USB RC (covers Mini 3 / Mini 3 Pro / Mini 4 Pro / Air 3 / etc.)

1. Power on the drone and link it to the RC
2. **Open DJI Fly** on your phone, connect to the drone, wait for the GPS home point to update
3. **Close DJI Fly** (it may reset CE on reconnect — closing it first prevents that)
4. **Open FreeFCC-N1**, tap **Connect** — the app detects the USB accessory and connects
5. Tap **Enable FCC Mode** and wait for the green checkmark
6. **Reopen DJI Fly** from the launcher — the radio stays in FCC mode

### Direct USB-to-drone (for setups that revert CE on reconnect)

1. On your main flight device, open DJI Fly and connect to the drone
2. Wait for the GPS home point to update
3. Use a **second Android device** connected directly to the drone via USB-C
4. On the second device, open FreeFCC-N1, tap **Connect**, then **Enable FCC Mode**
5. The drone switches to FCC while DJI Fly stays connected on the main device

### Smart controller (RC2 / RC Pro / RC Plus)

1. Install this app on the smart controller itself
2. Power on the drone and link it to the controller
3. Open FreeFCC-N1, tap **Connect** (connects via TCP at `127.0.0.1:40009`)
4. Tap **Enable FCC Mode** and wait for the green checkmark

### Other features

- **4G**: tap **Turn 4G ON** (the drone needs to be connected so the app can read its serial number). Requires DJI Cellular Dongle 2.
- **Remote ID**: tap **Disable RID** or **Enable RID** to toggle Remote ID broadcast.
- **LED**: tap **LED ON** / **LED OFF** (requires DJI Fly running with aircraft connected).
- **Info**: tap the refresh button on the Info page to query the controller's hardware and firmware version.

## How Do I Know If It Worked?

Open the DJI Fly app and go to the Transmission tab. Look at the horizontal bar around -90 dBm:

- If it lines up with the **1km mark**, your drone is in **CE mode**
- If it falls **below** the 1km mark (extends further), your drone is in **FCC mode**

> If the signal graph hasn't changed, power cycle the controller and try again. Make sure the drone is powered on and linked before enabling FCC.

---

## Support

If FreeFCC-N1 helped you out, please consider starring the repo and buying me a coffee. It helps cover server costs and keeps development going.

<div align="center">

[![Star on GitHub](https://img.shields.io/badge/Star%20on%20GitHub-%E2%AD%90-yellow?style=for-the-badge&logo=github)](https://github.com/doesthings/FreeFCC-N1)

[![Support on Ko-fi](https://img.shields.io/badge/Ko--fi-Buy%20me%20a%20coffee-FF5E5B?style=for-the-badge&logo=ko-fi&logoColor=white)](https://ko-fi.com/freefcc)

</div>

Every contribution helps cover server costs and keeps development going. Thank you.

---

## How It Works

The app sends DUMPL commands to the controller. DUMPL is DJI's internal command protocol, publicly documented in the [dji-firmware-tools](https://github.com/o-gs/dji-firmware-tools) project.

Each command is a small binary packet with a magic byte (`0x55`), a header with sender and receiver info, a payload, and two CRC checksums. The app builds these packets from JSON profile files and sends them over either USB (phone+RC) or TCP (smart controller).

### Dual transport — the key innovation

```
┌─────────────────────────────────────────────────────────────┐
│                       FreeFCC-N1 app                         │
│                                                             │
│  JSON profiles → DumplBuilder → wire-format DUMPL frames    │
│                                                             │
│  Auto-detect:  ┌─ USB accessory? → UsbTransport (bulk)      │
│                ├─ USB device?    → UsbTransport (bulk)      │
│                └─ TCP 40009?     → TcpTransport (loopback)  │
└─────────────────────────────────────────────────────────────┘
                          │
            ┌─────────────┴─────────────┐
            ▼                           ▼
   Phone cabled to RC-N1/RC-N2     Smart controller (RC2/RC Pro/RC Plus)
   (USB accessory mode)           (TCP proxy at 127.0.0.1:40009)
```

The same 21-frame FCC sequence goes over both transports. The frames are byte-identical. Only the carrier differs.

### FCC Profile

21 frames sent in 2 rounds with 150ms between each frame. The sequence enters service mode, sets the radio region to FCC, writes channel groups and power limits, commits the change, and exits service mode. The same 21 frames work on every DJI aircraft model — the profile is universal.

### 4G Profile

128 frames sent in a single round with 10ms between each. Each frame carries the aircraft's serial number in its payload. The serial is read from the controller at runtime by listening for telemetry on the DUMPL socket. On smart controllers these frames go via Unix domain socket at `/duss/mb/0x205`; on USB setups they go through the bulk endpoint with `dst=238`.

4G activation requires a DJI Cellular Dongle 2 to be physically connected to the aircraft. Without the dongle, the frames will fail to send.

### Remote ID

A single DUMPL frame writing to FLIGHT_CONTROLLER param `0x025D` (605). Value `0x00` disables, `0x01` enables. The param-index may differ on some firmware versions — if the toggle doesn't work, capture the actual param with Frida and update `remote_id_off.json` / `remote_id_on.json`.

### Profile Format

Profiles are JSON files in `app/src/main/assets/profiles/`. Each frame looks like this:

```json
{ "s": 16, "i": 88, "d": 18, "p": "030100", "note": "Enter service mode" }
```

| Field | Meaning |
|-------|---------|
| `s` | Command set (16 = service mode, 6 = radio, 3 = flight controller) |
| `i` | Command ID within the set |
| `d` | Destination device |
| `p` | Payload as hex string (sent as raw bytes, no transformation) |
| `note` | Plain English description of what the frame does |

You can open these files in any text editor, read every byte that gets sent, and modify them if you want.

### How the Frames Were Obtained

The DUMPL proxy on DJI smart controllers listens on `127.0.0.1:40009` and accepts plain unencrypted TCP connections. The command frames were identified by capturing loopback traffic on the controller while the radio was active, then extracting the `0x55`-prefixed DUMPL packets from the capture:

```bash
tcpdump -i lo -w /sdcard/capture.pcap port 40009
```

The frames are plaintext on the local socket with no encryption. Once captured, the payloads were decoded using the publicly documented command set and device type enums from the [dji-firmware-tools](https://github.com/o-gs/dji-firmware-tools) project (GPL-3.0). This project's `DumplBuilder` class implements the same CRC-8 (polynomial 0x8C, init 0x77) and CRC-16 (polynomial 0x1021 reflected, init 0x3692) as the reference implementation to build valid frames from the decoded command definitions.

The USB transport path (for RC-N1/RC-N2) uses Android's standard `UsbAccessory` / `UsbDevice` APIs — no protocol differences, the same DUMPL frames go through the USB bulk endpoint.

## Project Structure

```
app/src/main/
  assets/profiles/
    fcc.json          21 frames, FCC unlock (universal)
    ce_restore.json   1 frame, reset to factory region
    4g.json           128 frames, 4G activation
    remote_id_off.json  1 frame, disable Remote ID
    remote_id_on.json   1 frame, re-enable Remote ID
    device_info.json 1 frame, version inquiry
    led_on.json       1 frame, LED on (wrapped, port 40007)
    led_off.json      1 frame, LED off (wrapped, port 40007)
  java/com/freefcc/n1/
    DumplBuilder.kt     Frame builder (CRC-8/16 tables)
    DumplTransport.kt   DumplTransport interface + TcpTransport + UsbTransport
    ProfileLoader.kt    JSON profile loader + 4G serial injection + LED wrapper
    FccViewModel.kt     State management + business logic
    MainActivity.kt     Compose UI with animations
    DumlForegroundService.kt  Keeps USB link alive
  res/
    drawable/         Launcher icon (vector)
    mipmap-anydpi-v26/ Adaptive icon
    values/           Theme + strings
    xml/              Network security config + USB device filter
```

## Building

Requirements: Java 17+, Android SDK 35.

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

cd C:\projects\formini3nonpro
java -classpath gradle\wrapper\gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain assembleRelease --no-daemon
```

Sign the output APK with your own keystore or the debug one. The build is configured to sign with the debug keystore automatically for `release`.

## License

AGPL-3.0. See [LICENSE](LICENSE).

The DUMPL protocol implementation is based on the publicly documented [dji-firmware-tools](https://github.com/o-gs/dji-firmware-tools) project (GPL-3.0).

## Contact

Questions, issues, or feedback? Reach out:

- **GitHub Issues:** [github.com/doesthings/FreeFCC-N1/issues](https://github.com/doesthings/FreeFCC-N1/issues)
- **Ko-fi:** [ko-fi.com/freefcc](https://ko-fi.com/freefcc)