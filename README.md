<div align="center">

# FreeFCC-N1

### Open-source FCC unlock for DJI RC-N1 / RC-N2 / RC-N3 controllers

[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL--3.0-blue?style=flat-square)](LICENSE)

A free and open-source Android app that unlocks FCC mode on DJI drones when you're using an **RC-N1, RC-N2, or RC-N3 controller** — the "dumb" controllers without a screen that connect to your phone via USB cable. If you keep losing signal on your Mini 3 in CE mode, this switches the radio to FCC mode for higher power and more range. No server. No license. No tracking. Just raw DUMPL commands from a JSON profile file.

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

## Why existing tools don't work on the Mini 3

The existing free FCC hack ([M4TH1EU/DJI-FCC-HACK](https://github.com/M4TH1EU/DJI-FCC-HACK)) **does not work on the Mini 3**. The author acknowledges this in the README: *"Many people reported that this hack doesn't work with the Mini 3."*

### The technical reason

M4TH1EU's app sends only **2 hardcoded byte arrays** — a fragment of the full FCC unlock sequence. Specifically:

1. **Frame 1** is a `GENERAL` command with an empty payload that doesn't match any frame in the real FCC sequence.
2. **Frame 2** is exactly **frame 9 of 21** from the universal FCC profile — it writes the 2.4GHz OFDM power limit. But it's missing the other 20 frames.

The problem: **the Mini 3's firmware requires the full 21-frame sequence.** Specifically, it requires:

- **Service-mode entry** (frame 1) — without this, the radio silently discards all configuration writes
- **Region set + commit** (frames 2 and 20) — the actual CE→FCC switch and its commit
- **Both band power limits** (frames 9 and 10) — M4TH1EU only writes 2.4GHz, not 5.8GHz
- **Service-mode exit** (frame 21) — to finalize the change

Older drones (Mavic Air 2, Mini 2, Air 2S) have permissive firmware that accepts a bare power-limit write without the service-mode handshake. The Mini 3's newer firmware enforces the full handshake, so M4TH1EU's 2 frames are silently ignored — the app says "Patched successfully" but the radio stays in CE.

### Why this app will work

FreeFCC-N1 sends the **complete 21-frame universal FCC profile** — the same sequence that the paid NLD FCC app (€25) uses internally. The sequence:

1. Enters service mode
2. Sets the radio region to FCC
3. Writes flight-controller region parameters
4. Sets both 2.4GHz and 5.8GHz channel groups and power limits
5. Commits the region change
6. Exits service mode

Sent in 2 rounds with 150ms between each frame, giving the controller time to process each command. This is the complete handshake that the Mini 3's firmware requires.

## Features

| Feature | Description |
|---------|-------------|
| **FCC Unlock** | Switches the radio from CE to FCC mode for higher power and more channels |
| **CE Restore** | Reset to factory region (or just reboot the controller) |
| **Auto-FCC** | Toggle to automatically connect and apply FCC every time the app opens |
| **Offline** | Everything runs locally. No internet, no server, no tracking |
| **Open Profile** | The command frames are a plain JSON file you can inspect and edit |
| **No License** | No activation, no trial, no tracking, no server contact |

## Compatibility

**Designed for: USB-cabled controllers without a screen (RC-N1, RC-N2, RC-N3) + phone/tablet.**

| Controller | Transport | Status |
|------------|-----------|--------|
| **RC-N1** (cabled to phone) | USB serial (CDC ACM, 19200 baud) | Untested — should work |
| **RC-N2** (cabled to phone) | USB serial (CDC ACM, 19200 baud) | Untested — should work |
| **RC-N3** (cabled to phone) | USB serial (CDC ACM, 19200 baud) | Untested — should work |

> For **smart controllers** (RC2, RC Pro, RC Plus — the ones with a screen), use [FreeFCC](https://github.com/doesthings/FreeFCC) instead.

**The FCC profile is universal** — the same 21-frame DUMPL sequence works on every DJI aircraft:

| Drone | FCC |
|-------|-----|
| **Mini 3** | ✅ |
| Mini 3 Pro / Mini 4 Pro / Mini 5 Pro | ✅ |
| Mini 2 / Mini 2 SE / Mini 4K / Mini (Mavic Mini) | ✅ |
| Air 3 / Air 3S / Mavic Air / Air 2 / Air 2S | ✅ |
| Mavic 3 / Classic / Pro / Mavic 4 Pro | ✅ |
| Mavic Pro series / Mavic 2 Pro/Zoom | ✅ |
| Avata / Avata 2 / FPV Racer / FPV Racer 2 / Flip / Neo / Neo 2 | ✅ |
| Phantom 4 STD/ADV/PRO/PRO V2/MS / Inspire 2 / Spark | ✅ |

If you test it on a model or firmware version not listed here, please [open an issue](https://github.com/doesthings/FreeFCC-N1/issues) and let me know.

## How to Use

> **Important:** You need to repeat these steps every time you turn on the drone and/or remote. FCC mode is a RAM-only setting — it reverts to the factory region on power cycle.

1. Turn on the drone and remote and wait a few seconds for them to connect
2. Connect your phone to the **bottom USB port** of the remote
3. Open FreeFCC-N1, tap **Connect** — the app detects the USB serial connection to the RC
4. Tap **Enable FCC Mode** and wait for the green checkmark
5. **Disconnect your phone from the bottom USB port** of the remote and connect it to the **top USB port**
6. Open DJI Fly and enjoy your drone with FCC mode

> The bottom USB port is the service/diagnostic serial console — that's where the FCC patch is sent. The top USB port is for normal flight with DJI Fly. They present different USB interfaces, so the patch can only be sent from the bottom port.

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

The RC-N1/RC-N2/RC-N3 enumerates as a USB CDC ACM serial device at 19200 baud. The app opens the serial port using the [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) library and writes DUMPL frames as raw bytes — the controller forwards them to the drone over the radio link.

Each command is a small binary packet with a magic byte (`0x55`), a header with sender and receiver info, a payload, and two CRC checksums. The app builds these packets from a JSON profile file and sends them through the USB serial port.

### FCC Profile

21 frames sent in 2 rounds with 150ms between each frame. The sequence:

1. Enter service mode (AUTOTEST → SVO)
2. Set radio region to FCC
3. Write flight-controller region parameters
4. Set country codes
5. Set 2.4GHz and 5.8GHz channel groups
6. Set OFDM power limits for both bands
7. Write WIFI channel map and flags
8. Toggle flight-controller parameters
9. Set RADIO parameters
10. Commit the region change
11. Exit service mode

The same 21 frames work on every DJI aircraft model — the profile is universal.

### Profile Format

The profile is a JSON file in `app/src/main/assets/profiles/fcc.json`. Each frame looks like this:

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

You can open this file in any text editor, read every byte that gets sent, and modify it if you want.

### How the Frames Were Obtained

The command frames were identified by capturing loopback traffic on a DJI smart controller while the radio was active, then extracting the `0x55`-prefixed DUMPL packets from the capture:

```bash
tcpdump -i lo -w /sdcard/capture.pcap port 40009
```

The frames are plaintext on the local socket with no encryption. Once captured, the payloads were decoded using the publicly documented command set and device type enums from the [dji-firmware-tools](https://github.com/o-gs/dji-firmware-tools) project (GPL-3.0). This project's `DumplBuilder` class implements the same CRC-8 (polynomial 0x8C, init 0x77) and CRC-16 (polynomial 0x1021 reflected, init 0x3692) as the reference implementation to build valid frames from the decoded command definitions.

The USB serial transport uses the [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) library to open the CDC ACM serial port at 19200 baud. The library auto-detects CDC ACM devices by USB interface class, so it works with the RC-N1, RC-N2, and RC-N3 without needing to know their specific product IDs.

## Project Structure

```
app/src/main/
  assets/profiles/
    fcc.json          21 frames, FCC unlock (universal)
    ce_restore.json   1 frame, reset to factory region
  java/com/freefcc/n1/
    DumplBuilder.kt     Frame builder (CRC-8/16 tables)
    DumplTransport.kt   DumplTransport interface + UsbSerialTransport
    ProfileLoader.kt    JSON profile loader
    FccViewModel.kt     State management + business logic
    MainActivity.kt     Compose UI with animations
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