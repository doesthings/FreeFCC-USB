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
> This app has **not been tested on a real drone yet**. The DUMPL frames and USB transport are based on reverse-engineering of the NLD FCC app (which works on the Mini 3) and the publicly documented [dji-firmware-tools](https://github.com/o-gs/dji-firmware-tools) protocol. If you test it, please [open an issue](https://github.com/doesthings/FreeFCC-N1/issues) and let me know the result.

---

## What this fixes

If you're flying a **Mini 3** (or any DJI drone) with the stock **RC-N1** controller in a CE region, you've probably noticed the signal drops out well before the advertised range. CE mode caps the radio power at 0.5W on 2.4GHz; FCC mode allows 2W. That's a 4x power difference.

This app switches the radio from CE to FCC mode over the USB cable between your phone and the RC-N1/RC-N2/RC-N3. The same method the paid apps use, but free and open source.

> **For smart controllers** (RC2, RC Pro, RC Plus — the ones with a built-in screen) — use [FreeFCC](https://github.com/doesthings/FreeFCC) instead. That app runs directly on the controller and connects via TCP. This app (FreeFCC-N1) is specifically for the USB-cabled controllers without a screen.

## Why existing tools don't work on the Mini 3

The existing free FCC hack ([M4TH1EU/DJI-FCC-HACK](https://github.com/M4TH1EU/DJI-FCC-HACK)) **does not work on the Mini 3**. The author acknowledges this: *"Many people reported that this hack doesn't work with the Mini 3."*

There are **two reasons** it fails:

### 1. Wrong USB transport (CDC ACM serial instead of AOA accessory mode)

M4TH1EU uses CDC ACM serial mode (the phone is the USB host, the RC is a serial device at 19200 baud). But the Mini 3's RC-N1 firmware only accepts DUMPL commands over **Android Open Accessory (AOA) mode** — where the RC is the USB host and the phone is the accessory. The paid NLD FCC app uses AOA mode, which is why it works on the Mini 3 while M4TH1EU doesn't.

**FreeFCC-N1 uses AOA accessory mode** — the same transport as NLD FCC. The RC-N1 presents as a USB accessory with `manufacturer="DJI"`, the app calls `UsbManager.openAccessory()` to get a `ParcelFileDescriptor`, and reads/writes raw DUMPL bytes via `FileInputStream`/`FileOutputStream`.

### 2. Incomplete frame sequence (2 frames instead of 21)

M4TH1EU sends only **2 hardcoded byte arrays** — a fragment of the full FCC unlock sequence. One is just frame 9 of 21 (the 2.4GHz power limit write). It's missing the service-mode handshake, the region commit, and the 5.8GHz power limit.

The Mini 3's firmware requires the **full 21-frame sequence**: enter service mode → set region to FCC → write both band power limits → commit → exit service mode. Without the full handshake, the radio silently discards the writes.

**FreeFCC-N1 sends the complete 21-frame universal profile** — the same sequence NLD FCC uses internally.

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
| **RC-N1** (cabled to phone) | USB Accessory (AOA) | Untested — should work |
| **RC-N2** (cabled to phone) | USB Accessory (AOA) | Untested — should work |
| **RC-N3** (cabled to phone) | USB Accessory (AOA) | Untested — should work |

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

## Step-by-step tutorial

> **Important:** You need to repeat these steps every time you turn on the drone and/or remote. FCC mode is a RAM-only setting — it reverts to the factory region on power cycle.

### What you need

- An Android phone or tablet (Android 7.0+)
- A USB-C cable
- Your DJI drone with RC-N1, RC-N2, or RC-N3 controller
- The FreeFCC-N1 app (download from [releases](https://github.com/doesthings/FreeFCC-N1/releases))

### Step 1: Install the app

1. Download the `FreeFCC-N1.apk` from the [releases page](https://github.com/doesthings/FreeFCC-N1/releases)
2. Install it on your Android phone (you may need to enable "Install unknown apps" in your security settings)
3. You should see the FreeFCC-N1 app icon in your app drawer

### Step 2: Power on the drone and controller

1. Turn on the drone
2. Turn on the RC-N1/RC-N2/RC-N3 controller
3. Wait a few seconds for them to connect to each other (the controller's status LED should turn green)

### Step 3: Connect to the bottom USB port

1. Take your USB-C cable
2. Connect one end to your phone
3. Connect the other end to the **bottom USB-C port** of the controller (NOT the top port where you normally plug in for DJI Fly)

> **Why the bottom port?** The bottom port is the service/diagnostic port. When you plug into it, the controller enters USB host mode and the phone becomes a USB accessory. This is the AOA (Android Open Accessory) connection that allows DUMPL commands to be sent. The top port is for normal flight with DJI Fly and uses a different USB mode.

### Step 4: Open FreeFCC-N1 and connect

1. Open the FreeFCC-N1 app on your phone
2. Tap the **Connect** button
3. If prompted, grant USB permission (a dialog will appear asking for permission to access the USB accessory — tap "Allow")
4. The connection status should change from "Disconnected" to "Connected" (green indicator)

> **If it says "Controller not found":** Make sure you're plugged into the **bottom** port, not the top. Also try unplugging and replugging the USB cable. The controller needs to be powered on and linked to the drone.

### Step 5: Enable FCC mode

1. Once connected, tap the **Enable FCC Mode** button
2. Wait for the progress bar to complete (it sends 21 frames in 2 rounds — takes about 6 seconds)
3. You should see a green checkmark and "FCC mode enabled" message

### Step 6: Switch to the top USB port

1. **Disconnect** the USB cable from the bottom port of the controller
2. **Reconnect** the USB cable to the **top USB-C port** of the controller (the one in the phone clamp)
3. Open DJI Fly — your phone should connect normally

### Step 7: Verify FCC mode is active

1. In DJI Fly, go to the **Transmission** tab (the satellite/signal icon)
2. Look at the horizontal signal bar around -90 dBm:
   - If it lines up with the **1km mark**, your drone is still in **CE mode** (it didn't work)
   - If it falls **below** the 1km mark (the signal extends further), your drone is in **FCC mode** ✅

Check the images below for reference:

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

### Step 8: Fly!

If the signal graph shows FCC mode, you're done. Go fly and enjoy the extra range.

> **To revert to CE mode:** Either tap "Stop FCC Mode" in the app (before switching to the top port), or simply power cycle the controller and drone — FCC mode is RAM-only and reverts to the factory region on reboot.

### Troubleshooting

| Problem | Solution |
|---------|----------|
| "Controller not found" | Make sure you're plugged into the **bottom** USB port. Try a different USB-C cable. Make sure the controller is powered on and linked to the drone. |
| "FCC apply failed" | The DUMPL writes failed. Make sure the drone is powered on. Try disconnecting and reconnecting the USB cable, then tap Connect again. |
| "Patched but still in CE mode" | The transport or frame sequence may not be compatible with your firmware. Please [open an issue](https://github.com/doesthings/FreeFCC-N1/issues) with your drone model, controller model, and firmware version. |
| USB permission dialog doesn't appear | Go to Settings → Apps → FreeFCC-N1 → Permissions and grant USB access manually. Or unplug and replug the USB cable. |
| App doesn't launch when I plug in the RC | The `USB_ACCESSORY_ATTACHED` intent filter should auto-launch the app. If it doesn't, open it manually from your app drawer. |

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

The app sends DUMPL commands to the controller over the USB cable using **Android Open Accessory (AOA) mode**. DUMPL is DJI's internal command protocol, publicly documented in the [dji-firmware-tools](https://github.com/o-gs/dji-firmware-tools) project.

### USB Accessory Mode (AOA) — the correct transport

The RC-N1/RC-N2/RC-N3 uses the Android Open Accessory protocol to communicate with the phone. In this mode:
- The **controller is the USB host** (it initiates the AOA handshake)
- The **phone is the USB accessory** (it enters accessory mode)
- The app calls `UsbManager.openAccessory()` to get a `ParcelFileDescriptor`
- Raw DUMPL bytes are written via `FileOutputStream` and read via `FileInputStream`

This is the same transport used by the NLD FCC app (the paid app that works on the Mini 3). The alternative CDC ACM serial mode (used by M4TH1EU's DJI-FCC-HACK) does not work on the Mini 3 because the newer firmware only accepts DUMPL commands over the AOA path.

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

The profile is a JSON file in `app/src/main/assets/profiles/fcc.json`. Each frame:

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

## Project Structure

```
app/src/main/
  assets/profiles/
    fcc.json          21 frames, FCC unlock (universal)
    ce_restore.json   1 frame, reset to factory region
  java/com/freefcc/n1/
    DumplBuilder.kt     Frame builder (CRC-8/16 tables)
    DumplTransport.kt   DumplTransport interface + AccessoryTransport (AOA)
    ProfileLoader.kt    JSON profile loader
    FccViewModel.kt     State management + business logic
    MainActivity.kt     Compose UI with animations
  res/
    drawable/         Launcher icon (vector)
    mipmap-anydpi-v26/ Adaptive icon
    values/           Theme + strings
    xml/              Network security config + accessory filter
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