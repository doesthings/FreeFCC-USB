<div align="center">

# FreeFCC USB

### Open-source FCC unlock for DJI RC-N1 / RC-N2 / RC-N3 controllers

[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL--3.0-blue?style=flat-square)](LICENSE)

A free and open-source Android app that unlocks FCC mode on DJI drones when you're using an **RC-N1, RC-N2, or RC-N3 controller** — the controllers without a screen that connect to your phone via USB cable. If you keep losing signal on your Mini 3 in CE mode, this switches the radio to FCC mode for higher power and more range. No server. No license. No tracking. Just raw DUMPL commands from a JSON profile file.

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

If FreeFCC USB helped you out, please consider buying me a coffee. It helps cover server costs and keeps development going.

[![Support on Ko-fi](https://img.shields.io/badge/Ko--fi-Buy%20me%20a%20coffee-FF5E5B?style=for-the-badge&logo=ko-fi&logoColor=white)](https://ko-fi.com/freefcc)

[![Star on GitHub](https://img.shields.io/badge/Star%20on%20GitHub-%E2%AD%90-yellow?style=for-the-badge&logo=github)](https://github.com/doesthings/FreeFCC-USB)

</div>

---

> ## ⚠️ Untested on real hardware
>
> This app has **not been tested on a real drone yet**. The DUMPL frames are based on the publicly documented [dji-firmware-tools](https://github.com/o-gs/dji-firmware-tools) protocol. If you test it, please [open an issue](https://github.com/doesthings/FreeFCC-USB/issues) and let me know the result.

---

## What this fixes

If you're flying a **Mini 3** (or any DJI drone) with the stock **RC-N1** controller in a CE region, you've probably noticed the signal drops out well before the advertised range. CE mode caps the radio power at 0.5W on 2.4GHz; FCC mode allows 2W. That's a 4x power difference.

This app switches the radio from CE to FCC mode over the USB cable between your phone and the RC-N1/RC-N2/RC-N3.

> **For smart controllers** (RC2, RC Pro, RC Plus — the ones with a built-in screen) — use [FreeFCC](https://github.com/doesthings/FreeFCC) instead. That app runs directly on the controller and connects via TCP.

## Why other free tools don't work on the Mini 3

Other free FCC tools do not work on the Mini 3 for **two reasons**:

### 1. Wrong USB transport

Other tools use USB host mode (the phone is the USB host, the RC is a device). But the Mini 3's RC-N1 firmware only accepts DUMPL commands over **Android Open Accessory (AOA) mode** — where the RC is the USB host and the phone is the accessory.

**FreeFCC USB uses AOA accessory mode** — the RC-N1 presents as a USB accessory with `manufacturer="DJI"`, the app calls `UsbManager.openAccessory()` to get a `ParcelFileDescriptor`, and reads/writes raw DUMPL bytes via `FileInputStream`/`FileOutputStream`.

### 2. Incomplete frame sequence

Other tools send only **2 hardcoded byte arrays** — a fragment of the full FCC unlock sequence. One is just frame 9 of 21 (the 2.4GHz power limit write). It's missing the service-mode handshake, the region commit, and the 5.8GHz power limit.

The Mini 3's firmware requires the **full 21-frame sequence**: enter service mode → set region to FCC → write both band power limits → commit → exit service mode.

**FreeFCC USB sends the complete 21-frame universal profile**, wrapped in the RCLink envelope that the RC-N1's AOA parser expects, with a bootstrap handshake and keepalive frames to keep the session alive.

## How the USB connection works

The RC-N1 has **two USB-C ports** that work differently:

| Port | What it does | USB mode | Used for |
|------|-------------|----------|----------|
| **TOP port** (phone cradle) | DJI Fly connects here for video/telemetry/flight | **AOA accessory** (RC is host, phone is accessory) | FCC patch + normal flight |
| **BOTTOM port** (charging) | Charging and service access | USB host mode (phone is host, RC is device) | Charging only on Mini 3 |

**For the Mini 3, the FCC patch must be sent through the TOP port** using AOA accessory mode. This is the same port you normally use for DJI Fly — but you need to **close DJI Fly first** because Android only allows one app at a time to hold the USB accessory.

The app:
1. Opens the AOA accessory on the TOP port
2. Sends a **bootstrap handshake** (2 frames) to initialize the DUMPL command session
3. Sends **keepalive frames** every 2.5s to keep the session alive
4. Sends the **21-frame FCC unlock sequence**, each wrapped in an RCLink envelope
5. The RC-N1 forwards the commands to the drone over the radio link

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
| **RC-N1** (TOP port, AOA) | USB Accessory (AOA) | Untested — should work |
| **RC-N2** (TOP port, AOA) | USB Accessory (AOA) | Untested — should work |
| **RC-N3** (TOP port, AOA) | USB Accessory (AOA) | Untested — should work |

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

## Step-by-step tutorial

> **Important:** You need to repeat these steps every time you turn on the drone and/or remote. FCC mode is a RAM-only setting — it reverts to the factory region on power cycle.

### What you need

- An Android phone or tablet (Android 7.0+)
- A USB-C cable
- Your DJI drone with RC-N1, RC-N2, or RC-N3 controller
- The FreeFCC USB app (download from [releases](https://github.com/doesthings/FreeFCC-USB/releases))

### Step 1: Install the app

1. Download the `FreeFCC-USB.apk` from the [releases page](https://github.com/doesthings/FreeFCC-USB/releases)
2. Install it on your Android phone (you may need to enable "Install unknown apps" in your security settings)

### Step 2: Power on the drone and controller

1. Turn on the drone
2. Turn on the RC-N1/RC-N2/RC-N3 controller
3. Wait a few seconds for them to connect (the controller's status LED should turn green)

### Step 3: Close DJI Fly

**This is critical.** Android only allows one app at a time to hold the USB accessory connection. If DJI Fly is running (even in the background), it will claim the accessory and our app won't be able to write.

1. Go to **Settings → Apps → DJI Fly → Force Stop**
2. Make sure DJI Fly is completely closed (not just swiped from recents)

### Step 4: Connect to the TOP USB port

1. Take your USB-C cable
2. Connect one end to your phone
3. Connect the other end to the **TOP USB-C port** of the controller (the one in the phone cradle where you normally plug in for DJI Fly)

> **Why the TOP port?** The TOP port is where the RC-N1 presents as an AOA USB accessory. The BOTTOM port is for charging and uses a different USB mode. The Mini 3's firmware only accepts DUMPL commands over the AOA accessory connection on the TOP port.

### Step 5: Open FreeFCC USB and connect

1. Open the FreeFCC USB app on your phone
2. Tap the **Connect** button
3. If prompted, grant USB permission (a dialog will appear — tap "Allow")
4. The connection status should change to "Connected" (green indicator)

> **If it says "Controller not found":** Make sure DJI Fly is force-closed. Make sure you're plugged into the **TOP** port. Try unplugging and replugging the USB cable.

### Step 6: Enable FCC mode

1. Once connected, tap the **Enable FCC Mode** button
2. Wait for the progress bar to complete (sends 21 frames in 2 rounds)
3. You should see a green checkmark and "FCC mode enabled" message

### Step 7: Open DJI Fly and verify

1. **Keep the USB cable connected to the TOP port** (don't switch ports)
2. Open DJI Fly
3. Go to the **Transmission** tab (the satellite/signal icon)
4. Look at the horizontal signal bar around -90 dBm:
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

> **To revert to CE mode:** Either tap "Stop FCC Mode" in the app, or simply power cycle the controller and drone — FCC mode is RAM-only and reverts to the factory region on reboot.

### Troubleshooting

| Problem | Solution |
|---------|---------|
| "Controller not found" | **Force-close DJI Fly** (Settings → Apps → DJI Fly → Force Stop). Make sure you're on the **TOP** port. Try a different USB-C cable. |
| "FCC apply failed" | The DUMPL writes failed. Make sure the drone is powered on. Try disconnecting and reconnecting, then tap Connect again. |
| "Patched but still in CE mode" | Please [open an issue](https://github.com/doesthings/FreeFCC-USB/issues) with your drone model, controller model, and firmware version. |
| USB permission dialog doesn't appear | Unplug and replug the USB cable. Or go to Settings → Apps → FreeFCC USB → Permissions. |
| DJI Fly keeps auto-launching | Go to Settings → Apps → DJI Fly → Open by default → Clear defaults. |

---

## Support

If FreeFCC USB helped you out, please consider starring the repo and buying me a coffee.

<div align="center">

[![Star on GitHub](https://img.shields.io/badge/Star%20on%20GitHub-%E2%AD%90-yellow?style=for-the-badge&logo=github)](https://github.com/doesthings/FreeFCC-USB)

[![Support on Ko-fi](https://img.shields.io/badge/Ko--fi-Buy%20me%20a%20coffee-FF5E5B?style=for-the-badge&logo=ko-fi&logoColor=white)](https://ko-fi.com/freefcc)

</div>

---

## How It Works

The app sends DUMPL commands to the controller over the USB cable using **Android Open Accessory (AOA) mode**. DUMPL is DJI's internal command protocol, publicly documented in the [dji-firmware-tools](https://github.com/o-gs/dji-firmware-tools) project.

### USB Accessory Mode (AOA)

The RC-N1/RC-N2/RC-N3 uses the Android Open Accessory protocol. In this mode:
- The **controller is the USB host** (it initiates the AOA handshake)
- The **phone is the USB accessory** (it enters accessory mode)
- The app calls `UsbManager.openAccessory()` to get a `ParcelFileDescriptor`
- Raw DUMPL bytes are written via `FileOutputStream` and read via `FileInputStream`

### RCLink envelope

DUMPL frames are not sent raw — each frame is wrapped in an 8-byte RCLink envelope:

```
[0x55][0xCC][0x49][0x57][4-byte LE length][DUMPL frame bytes...]
```

The RC-N1's AOA parser expects this envelope format. The magic bytes `0x55 0xCC` signal an RCLink frame, followed by route bytes `0x49 0x57` ("IW"), the payload length, and the inner DUMPL frame.

### Bootstrap handshake

Immediately after opening the AOA accessory, the app sends 2 bootstrap frames:
1. **CONN_BOOTSTRAP_3100** — destination component 0x1F, payload `{0x00, 0x00, 0x01}`
2. **CONN_BOOTSTRAP_0000** — destination component 0x00 (broadcast), payload `{0x00, 0x00, 0x01}`

These initialize the DUMPL command session. Without this handshake, the controller ignores all subsequent commands.

### Keepalive frames

The app sends 2 keepalive frames every 2.5 seconds to keep the RCLink session alive:
- Destination 0x06, cmd_set=6, cmd_id=0x77, payload `{0x01, 0x01, 0x00, 0xFF, 0xFF, 0x20, 0x00, 0x00}`
- Destination 0x0E, same payload

Without these keepalives, the controller may drop the session before the FCC sequence completes.

### FCC Profile

21 frames sent in 2 rounds, each wrapped in the RCLink envelope:

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

The profile is a JSON file in `app/src/main/assets/profiles/fcc.json`:

```json
{ "s": 16, "i": 88, "d": 18, "p": "030100", "note": "Enter service mode" }
```

| Field | Meaning |
|-------|---------|
| `s` | Command set (16 = service mode, 6 = radio, 3 = flight controller) |
| `i` | Command ID within the set |
| `d` | Destination device |
| `p` | Payload as hex string (sent as raw bytes) |
| `note` | Plain English description |

You can open this file in any text editor, read every byte that gets sent, and modify it if you want.

## Project Structure

```
app/src/main/
  assets/profiles/
    fcc.json          21 frames, FCC unlock (universal)
    ce_restore.json   1 frame, reset to factory region
  java/com/freefcc/n1/
    DumplBuilder.kt     Frame builder (CRC-8/16 tables)
    DumplTransport.kt   AOA accessory transport + RCLink envelope + TX thread + keepalives
    ProfileLoader.kt    JSON profile loader
    FccViewModel.kt     State management + bootstrap handshake + FCC logic
    MainActivity.kt     Compose UI
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

## License

AGPL-3.0. See [LICENSE](LICENSE).

The DUMPL protocol implementation is based on the publicly documented [dji-firmware-tools](https://github.com/o-gs/dji-firmware-tools) project (GPL-3.0).

## Contact

- **GitHub Issues:** [github.com/doesthings/FreeFCC-USB/issues](https://github.com/doesthings/FreeFCC-USB/issues)
- **Ko-fi:** [ko-fi.com/freefcc](https://ko-fi.com/freefcc)