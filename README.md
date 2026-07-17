<div align="center">

# FreeFCC-N1

### Open-source FCC unlock for DJI RC-N1 / RC-N2 / RC-N3 controllers

[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL--3.0-blue?style=flat-square)](LICENSE)

A free and open-source Android app that unlocks FCC mode on DJI drones when you're using an **RC-N1, RC-N2, or RC-N3 controller** — the "dumb" controllers without a screen that connect to your phone via USB cable. If you keep losing signal on your Mini 3 in CE mode, this switches the radio to FCC mode for higher power and more range. No server. No license. No tracking. Just raw DUMPL commands from JSON profile files.

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

> ## ⚠️ Untested on real hardware
>
> This app has **not been tested on a real drone yet**. The DUMPL frames are based on the publicly documented [dji-firmware-tools](https://github.com/o-gs/dji-firmware-tools) protocol and loopback captures. The USB serial path for the RC-N1/RC-N2/RC-N3 has not been verified against a real drone.
>
> If you test it, please [open an issue](https://github.com/doesthings/FreeFCC-N1/issues) and let me know the result (success or failure, your drone model, controller, and firmware version).

---

## What this fixes

If you're flying a **Mini 3** (or any DJI drone) with the stock **RC-N1** controller in a CE region, you've probably noticed the signal drops out well before the advertised range. CE mode caps the radio power at 0.5W on 2.4GHz; FCC mode allows 2W. That's a 4x power difference.

This app switches the radio from CE to FCC mode over the USB cable between your phone and the RC-N1/RC-N2/RC-N3. The same method the paid apps use, but free and open source.

> **For smart controllers** (RC2, RC Pro, RC Plus — the ones with a built-in screen) — use [FreeFCC](https://github.com/doesthings/FreeFCC) instead. That app runs directly on the controller and connects via TCP. This app (FreeFCC-N1) is specifically for the USB-cabled controllers without a screen.

## Features

| Feature | Description |
|---------|-------------|
| **FCC Unlock** | Switches the radio from CE to FCC mode for higher power and more channels |
| **4G Activation** | Enables 4G transmission on the aircraft (requires DJI Cellular Dongle 2) |
| **Remote ID Toggle** | Disable or enable Remote ID broadcast |
| **Device Info** | Queries the controller for hardware and firmware version |
| **Auto-FCC** | Toggle to automatically connect and apply FCC every time the app opens |
| **Offline** | Everything runs locally. No internet, no server, no tracking |
| **Open Profiles** | Command frames are plain JSON files you can inspect and edit |
| **No License** | No activation, no trial, no tracking, no server contact |

## Compatibility

**Designed for: USB-cabled controllers without a screen (RC-N1, RC-N2, RC-N3) + phone/tablet.**

| Controller | Transport | Status |
|------------|-----------|--------|
| **RC-N1** (cabled to phone) | USB serial (CDC ACM, 19200 baud) | Untested — should work |
| **RC-N2** (cabled to phone) | USB serial (CDC ACM, 19200 baud) | Untested — should work |
| **RC-N3** (cabled to phone) | USB serial (CDC ACM, 19200 baud) | Untested — should work |
| Direct USB-to-drone (USB-C) | USB serial | Untested — fallback path |

> For **smart controllers** (RC2, RC Pro, RC Plus — the ones with a screen), use [FreeFCC](https://github.com/doesthings/FreeFCC) instead.

**The FCC profile is universal** — the same 21-frame DUMPL sequence works on every DJI aircraft:

| Drone | Model code | FCC | 4G | Remote ID |
|-------|-----------|-----|-----|-----------|-----|
| **Mini 3** | wm163 | ✅ | N/A | ✅ |\n| Mini 3 Pro | wm162 | ✅ | N/A | ✅ |\n| Mini 4 Pro | wa140 | ✅ | N/A | ✅ |\n| Mini 4K | — | ✅ | N/A | ✅ |\n| Mini 5 Pro | wa150 | ✅ | N/A | ✅ |\n| Mini 2 / Mini 2 SE | wm161 | ✅ | N/A | ✅ |\n| Mini (Mavic Mini) | wm160 | ✅ | N/A | ✅ |\n| Air 3 / Air 3S | wa233/wa234 | ✅ | ? | ✅ |\n| Mavic Air / Air 2 / Air 2S | — | ✅ | ? | ✅ |\n| Mavic 3 / Classic / Pro | wm260+ | ✅ | ? | ✅ |\n| Mavic 4 Pro | wa341 | ✅ | ✅ | ✅ |\n| Mavic Pro series / Mavic 2 Pro/Zoom | — | ✅ | ? | ✅ |\n| Avata / Avata 2 | wm169/wa520 | ✅ | ? | ✅ |\n| FPV Racer / FPV Racer 2 | — | ✅ | N/A | ✅ |\n| Flip / Neo / Neo 2 | various | ✅ | N/A | ✅ |\n| Phantom 4 STD / ADV / PRO / PRO V2 / MS | — | ✅ | N/A | ✅ |\n| Inspire 2 | — | ✅ | N/A | ✅ |\n| Spark | — | ✅ | N/A | ✅ |\n
If you test it on a model or firmware version not listed here, please [open an issue](https://github.com/doesthings/FreeFCC-N1/issues) and let me know.

## How to Use

> **Important:** You need to repeat these steps every time you turn on the drone and/or remote. FCC mode is a RAM-only setting — it reverts to the factory region on power cycle.

### Method 1: Standard controller USB method

This is the normal method. FCC Mode is enabled through the USB connection between your phone and the RC-N1/RC-N2/RC-N3.

1. Turn on the drone and remote and wait a few seconds for them to connect
2. Connect your phone to the **bottom USB port** of the remote
3. Open FreeFCC-N1, tap **Connect** — the app detects the USB serial connection to the RC
4. Tap **Enable FCC Mode** and wait for the green checkmark
5. **Disconnect your phone from the bottom USB port** of the remote and connect it to the **top USB port**
6. Open DJI Fly and enjoy your drone with FCC mode

### Method 2: Direct USB-to-drone method

Some drones, DJI Fly versions, or firmware versions may reset back to CE Mode when DJI Fly reconnects to the aircraft. If Method 1 doesn't stick, use this method instead.

1. On your main flight device, open DJI Fly and connect to the drone
2. Wait until the GPS home point has been updated
3. **Keep DJI Fly running and connected** on your main device
4. Use a **second Android device** connected directly to the drone's USB-C port (cable straight to the drone, not to the RC)
5. On the second device, open FreeFCC-N1, tap **Connect**, then **Enable FCC Mode**
6. The drone switches to FCC mode while DJI Fly stays connected on the main device

> Your main flight device can still be Android or iOS. The second Android device is only used to enable FCC Mode directly on the drone while DJI Fly remains connected.

### Other features

- **4G**: tap **Turn 4G ON** (the drone needs to be connected so the app can read its serial number). Requires DJI Cellular Dongle 2. Mini series does not support 4G.
- **Remote ID**: tap **Disable RID** or **Enable RID** to toggle Remote ID broadcast.
- **Info**: tap the refresh button on the Info page to query the controller's hardware and firmware version.

## How Do I Know If It Worked?

Open the DJI Fly app and go to the Transmission tab. Look at the horizontal bar around -90 dBm:

- If it lines up with the **1km mark**, your drone is in **CE mode**
- If it falls **below** the 1km mark (extends further), your drone is in **FCC mode**

Check the images below for reference.

<table>
<tr>
<td align="center"><b>FCC Mode</b></td>
<td align="center"><b>CE Mode</b></td>
</tr>
<tr>
<td><img src=".github/fcc.webp" alt="FCC mode"></td>
<td><img src=".github/ce.webp" alt="CE mode"></td>
</tr>
<tr>
<td align="center" style="color:#34D399">Signal extends past 1km</td>
<td align="center" style="color:#7A85A3">Signal barely reaches 1km</td>
</tr>
</table>

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

The app sends DUMPL commands to the controller over the USB cable. DUMPL is DJI's internal command protocol, publicly documented in the [dji-firmware-tools](https://github.com/o-gs/dji-firmware-tools) project.

The RC-N1/RC-N2/RC-N3 enumerates as a USB CDC ACM serial device (DJI vendor ID 0x2CA3) at 19200 baud. The app opens the serial port and writes DUMPL frames as raw bytes — the controller forwards them to the drone over the radio link.

Each command is a small binary packet with a magic byte (`0x55`), a header with sender and receiver info, a payload, and two CRC checksums. The app builds these packets from JSON profile files and sends them through the USB serial port.

### FCC Profile

21 frames sent in 2 rounds with 150ms between each frame. The sequence enters service mode, sets the radio region to FCC, writes channel groups and power limits, commits the change, and exits service mode. The same 21 frames work on every DJI aircraft model — the profile is universal.

### 4G Profile

128 frames sent in a single round with 10ms between each. Each frame carries the aircraft's serial number in its payload. The serial is read from the controller at runtime by listening for telemetry on the DUMPL socket.

4G activation requires a DJI Cellular Dongle 2 to be physically connected to the aircraft. Without the dongle, the frames will fail to send. Mini series does not support 4G.

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

The command frames were identified by capturing loopback traffic on a DJI smart controller while the radio was active, then extracting the `0x55`-prefixed DUMPL packets from the capture:

```bash
tcpdump -i lo -w /sdcard/capture.pcap port 40009
```

The frames are plaintext on the local socket with no encryption. Once captured, the payloads were decoded using the publicly documented command set and device type enums from the [dji-firmware-tools](https://github.com/o-gs/dji-firmware-tools) project (GPL-3.0). This project's `DumplBuilder` class implements the same CRC-8 (polynomial 0x8C, init 0x77) and CRC-16 (polynomial 0x1021 reflected, init 0x3692) as the reference implementation to build valid frames from the decoded command definitions.

The USB serial transport (for RC-N1/RC-N2/RC-N3) uses the [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) library to open the CDC ACM serial port at 19200 baud — the same DUMPL frames go through the serial connection to the controller.

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
  java/com/freefcc/n1/
    DumplBuilder.kt     Frame builder (CRC-8/16 tables)
    DumplTransport.kt   DumplTransport interface + UsbSerialTransport + TcpTransport
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