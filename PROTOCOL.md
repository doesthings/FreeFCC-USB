# FreeFCC-N1 — Protocol Reference

Complete documentation of the DUMPL protocol as implemented in FreeFCC-N1.
The protocol is publicly documented in the
[dji-firmware-tools](https://github.com/o-gs/dji-firmware-tools) project
(GPL-3.0). This file describes how FreeFCC-N1 uses it to talk to DJI
controllers over USB and TCP.

---

## 1. The two controller worlds

DJI controllers split into two families. FreeFCC-N1 supports both.

### 1.1 USB-cabled remotes

**Controllers:** RC-N1, RC-N2 — the plastic remotes that come with the
Mini 3, Mini 3 Pro, Mini 4 Pro, Air 3, etc.

**Setup:** The app runs on the **phone/tablet**, which is cabled to the
RC over USB. The RC bridges the DUMPL commands through to the drone over
its own radio link. Some setups require the **direct USB-to-drone**
fallback: a second Android device cabled directly to the drone's USB-C
port, while DJI Fly stays connected on the main device.

**Transport:** Android's `UsbAccessory` API (the RC appears as a USB
accessory with a DUMPL bulk stream) or `UsbDevice` API (the drone appears
as a USB device with bulk endpoints). We auto-detect both in
`UsbTransport.openAttached()`.

### 1.2 Smart controllers

**Controllers:** RC2 (`rc331`), RC Pro 2 (`rc520`), Smart Controller
(`rm330`/`rm500`), RC Pro (`rm510`), RC Plus (`rm700`).

**Setup:** The app runs **ON the controller itself**. The controller
exposes a DUMPL proxy as a local TCP socket at `127.0.0.1:40009`.

**Transport:** Plain TCP, one frame per connection (open → write → read
ACK → close). Implemented in `TcpTransport`.

---

## 2. The DUMPL wire format

Every command (FCC, CE, 4G, Remote ID, LED, device info) is wrapped in
the same DUMPL envelope. The envelope is identical over USB and TCP —
only the carrier differs.

### 2.1 Frame layout

```
offset  size  field
------  ----  -----
  0      1    header magic = 0x55
  1      1    length low byte   (total frame length L)
  2      1    length high nibble (L >> 8) & 0x03  |  flags 0x04 (version=1)
  3      1    CRC-8 over bytes 0-2
  4      1    sender       (type + index)
  5      1    receiver      (type + index)
  6      1    seq counter low  (AtomicInteger starting at 4096+, & 0xFF)
  7      1    seq counter high ((seq & 0xFFFF) >> 8)
  8      1    cmd_type      (packet_type + ack_type + encrypt_type)
  9      1    cmd_set
 10      1    cmd_id
 11   L-13   payload
 11+L-13 2    CRC-16 low, CRC-16 high  (over bytes 0 .. 10+payloadLen)
```

Total frame length `L = payload.length + 13`. Hard max **1023 bytes**.

This matches the dji-firmware-tools `comm_dat2pcap.py` reference.

### 2.2 CRC-8

Polynomial `0x8C` (reflected of `0x140`), init `0x77`, 256-entry table.

```python
def _crc8_table():
    t = [0]*256
    for i in range(256):
        c = i
        for _ in range(8):
            c = (c >> 1) ^ (0x8C if c & 1 else 0)
        t[i] = c & 0xFF
    return t
```

Implemented in `DumplBuilder.crc8()`.

### 2.3 CRC-16

Polynomial `0x1021` reflected (`0x8408`), init `0x3692`, 256-entry table,
result stored little-endian.

```python
def _crc16_table():
    t = [0]*256
    for i in range(256):
        c = i
        for _ in range(8):
            c = (c >> 1) ^ (0x8408 if c & 1 else 0)
        t[i] = c & 0xFFFF
    return t
```

Implemented in `DumplBuilder.crc16()`.

---

## 3. The FCC profile (universal)

The 21-frame FCC unlock sequence. Works on every DJI drone model tested
(11 model codes verified: wm630, wa150, wa140, wm162, wm163, wm161,
wm160, wa341, wm260, wa233, wm220).

| # | cmdSet | cmdId | dst | payload (hex)             | Purpose |
|---|--------|-------|-----|----------------------------|--------|
| 1 | 16     | 88    | 18  | `030100`                   | Enter service mode (AUTOTEST → SVO) |
| 2 | 6      | 114   | 6   | `00000000000100`           | Set region param to FCC (01) |
| 3 | 3      | 249   | 3   | `8a237103f401`             | FLIGHT_CONTROLLER param write |
| 4 | 0      | 0     | 31  | `000001`                   | GENERAL activate change |
| 5 | 0      | 50    | 111 | `3131000000`               | Set country code '11' |
| 6 | 3      | 175   | 3   | `032400000000000000`       | FLIGHT_CONTROLLER param write |
| 7 | 7      | 48    | 9   | `41550000415500000100`     | Set 2.4G channels (AU) |
| 8 | 7      | 48    | 9   | `41550000415500000100`     | Set 5.8G channels (AU, repeat) |
| 9 | 9      | 39    | 9   | `00024800ffff0200000000`   | OFDM 2.4G power limit |
| 10| 9      | 39    | 9   | `00026300ffff0300000000`   | OFDM 5.8G power limit |
| 11| 7      | 24    | 7   | `ff415500`                 | WIFI channel map |
| 12| 7      | 25    | 9   | `c0`                       | WIFI channel flag |
| 13| 3      | 249   | 146 | `d04aeffb01`               | FLIGHT_CONTROLLER param ON |
| 14| 3      | 249   | 146 | `d04aeffb00`               | FLIGHT_CONTROLLER param OFF |
| 15| 0      | 229   | 111 | `323201`                   | Set country code '22' |
| 16| 3      | 249   | 3   | `236b820101`               | FLIGHT_CONTROLLER param write |
| 17| 3      | 249   | 3   | `8773e68a01`               | FLIGHT_CONTROLLER param write |
| 18| 6      | 140   | 9   | `000300`                   | RADIO set param 03 |
| 19| 6      | 140   | 9   | `000100`                   | RADIO set param 01 |
| 20| 6      | 114   | 6   | `000000000001ff`           | Commit region change |
| 21| 16     | 88    | 18  | `030100`                   | Exit service mode (AUTOTEST) |

Apply settings: `sender=130`, `cmd_type=32`, `rounds=2`,
`inter_frame_delay_ms=150`, `inter_round_delay_ms=400`,
`read_window_ms=80`.

### Why the profile is universal

DJI's radio firmware interprets these commands the same way on every
aircraft model — the cmd_set/cmd_id/dst triples are not model-specific.
The profile was captured via loopback on a smart controller and verified
across all tested model codes. There is no per-device customization needed.

---

## 4. The CE-restore command

A single DUMPL frame. Not license-gated — works unconditionally on any
connected controller.

```
sender=130, cmd_type=6, cmd_set=6, cmd_id=114, dst=32
payload = 00 00 00 00 00 01 00
```

This is the safe undo for FCC mode. Rebooting the controller also reverts
to the flashed region (FCC state is RAM-only).

---

## 5. 4G activation

128 frames, each carrying the aircraft serial in its payload.

- `sender = 2` (CAMERA)
- `cmd_type = 0` (Request, NO_ACK_NEEDED)
- `cmd_set = 81` (0x51 — 4G command set)
- `cmd_id = 0..127` (one per frame)
- `dst = 238` (0xEE — OFDM_GROUND idx 7)
- `payload = 000000 + ASCII(aircraft_serial)`

On smart controllers these frames go via Unix domain socket at
`/duss/mb/0x205` (abstract namespace). On USB setups they go through the
bulk endpoint with the same dst=238.

**Requires DJI Cellular Dongle 2** physically connected to the aircraft.
Without it the socket/endpoint won't exist and the frames will fail.

**Mini series does not support 4G** — 4G is enterprise-only (Mavic 4
Pro / Matrice series).

---

## 6. Remote ID toggle

A single DUMPL frame writing to FLIGHT_CONTROLLER param `0x025D` (605).

```
sender=130, cmd_type=32, cmd_set=3, cmd_id=249, dst=3
payload = 5D 02 <value>     where value = 00 (disable) or 01 (enable)
```

The param-index `0x025D` may differ on some firmware versions. If the
toggle doesn't work, capture the actual param with Frida on a DJI Fly
session that toggles Remote ID, and update
`remote_id_off.json` / `remote_id_on.json`.

---

## 7. LED control

LED commands use a wrapped DUMPL frame format on port 40007 (different
from the standard 40009). The wrapper is 8 bytes prepended to the inner
DUMPL frame:

```
[0x55][0xCC][0x30][0x75][4-byte LE length][inner DUMPL frame]
```

Implemented in `ProfileLoader.wrapFrame()`. Sent twice with a 500ms delay
for reliability.

---

## 8. Device info query

GENERAL VersionInquiry — `cmd_set=0`, `cmd_id=1`, empty payload. The
response payload layout (from dji-firmware-tools
DJIPayload_General_VersionInquiryRe):

```
bytes 0-1    unknown
bytes 2-17   hardware version (16-char ASCII)
bytes 18-21  bootloader version (uint32 LE)
bytes 22-25  firmware version (uint32 LE)
```

Parsed in `FccViewModel.formatVersionResponse()`.

---

## 9. The dual transport — how it's universal

The key innovation in this app is the `DumplTransport` interface with two
implementations:

```
DumplTransport (interface)
├── open(): Boolean
├── write(frame: ByteArray): Boolean
├── read(buffer, length, timeoutMs): Int
└── close()

TcpTransport          UsbTransport (and AccessoryFdTransport)
─────────────         ──────────────────────────────────────
127.0.0.1:40009       USB bulk endpoint OR accessory FD
one frame per conn    connection stays open
proxy ACKs            controller ACKs
smart controllers     phone + RC-N1/RC-N2 OR direct-to-drone
```

`FccViewModel.connectInternal()` tries USB first (covers the Mini 3
non-pro case), then falls back to TCP (covers smart controllers). The
same `sendProfile()` loop works over either transport — the only
difference is that TCP opens a fresh socket per frame (the proxy
expects that) while USB keeps the bulk connection open.

---

## 10. How the frames were obtained

The DUMPL proxy on DJI smart controllers listens on `127.0.0.1:40009`
and accepts plain unencrypted TCP connections. The command frames were
identified by capturing loopback traffic on the controller while the
radio was active, then extracting the `0x55`-prefixed DUMPL packets from
the capture:

```bash
tcpdump -i lo -w /sdcard/capture.pcap port 40009
```

The frames are plaintext on the local socket with no encryption. Once
captured, the payloads were decoded using the publicly documented command
set and device type enums from the dji-firmware-tools project (GPL-3.0).

The USB transport path (for RC-N1/RC-N2) uses Android's standard
`UsbAccessory` / `UsbDevice` APIs — no protocol differences, the same
DUMPL frames go through the USB bulk endpoint.

---

## 11. Quick reference: all constants

| Constant | Value | Source |
|----------|-------|--------|
| DUMPL magic | `0x55` | dji-firmware-tools |
| CRC-8 poly | `0x8C` reflected, init `0x77` | dji-firmware-tools |
| CRC-16 poly | `0x8408` reflected, init `0x3692` | dji-firmware-tools |
| TCP host | `127.0.0.1` | Smart-controller loopback proxy |
| TCP port | `40009` | Smart-controller DUMPL proxy |
| LED port | `40007` | LED control proxy |
| 4G Unix socket | `/duss/mb/0x205` | 4G module bus |
| FCC sender | `130` (0x82 MOBILE_APP idx 4) | DUMPL spec |
| 4G sender | `2` (CAMERA idx 0) | DUMPL spec |
| 4G dst | `238` (0xEE OFDM_GROUND idx 7) | DUMPL spec |
| CE-restore cmd | `set=6 id=114 dst=32 payload=00000000000100` | DUMPL spec |
| Remote ID param | `0x025D` (605) | FLIGHT_CONTROLLER write-param |
| Max frame size | 1023 bytes | DUMPL spec |
| Sequence start | 4096 | DUMPL spec |
| FCC apply timing | 2 rounds, 150ms inter-frame, 400ms inter-round | Captured timing |

---

## 12. Caveats and known limitations

- **Untested on real hardware.** The DUMPL frames are based on the public
  dji-firmware-tools protocol and loopback captures. The USB path for
  RC-N1/RC-N2 has not been verified against a real Mini 3 non-pro yet.
- **Firmware can close the DUMPL proxy.** DJI may ship firmware updates
  that require auth on the `40009` socket or remove the proxy entirely.
  No app patch helps if the socket is unreachable.
- **The FCC profile is the only "secret".** Everything else (transport,
  envelope, CRCs, CE-restore) is in the public dji-firmware-tools docs.
- **CE-restore is universal.** It's the same 7-byte payload for every
  supported controller. Safe to use as the undo on any model.
- **Radio state is RAM-only.** A controller reboot always reverts to
  the flashed region regardless of what the app did. The app persists
  the *intent* (auto-FCC flag) across reboots, not the radio state.
- **Remote ID param index may vary.** `0x025D` (605) is the index on
  the firmware we tested. If the toggle doesn't work on your firmware,
  capture the actual param with Frida and update the JSON.

---

## Legal

This is an educational project. The DUMPL protocol is publicly documented
in the dji-firmware-tools project (GPL-3.0). The FCC profile bytes were
captured via loopback on a DJI smart controller.