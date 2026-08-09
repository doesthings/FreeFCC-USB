# FreeFCC USB — Agent Instructions

## Project

Open-source FCC unlock app for DJI RC-N1 / RC-N2 / RC-N3 USB-cabled controllers. Sends DUML command frames over USB AOA (Android Open Accessory) to switch the drone's radio region from CE to FCC.

## Architecture

- **DumplBuilder.kt** — Builds DUML wire-format frames (0x55 magic, CRC-8/CRC-16, 13-byte header + payload)
- **DumplTransport.kt** — USB AOA transport with RX drain thread, TX queue, RCLink keepalive, and dynamic route tracking
- **ProfileLoader.kt** — Loads FCC/CE command profiles from JSON assets, builds frames with global sequence counter
- **FccViewModel.kt** — Business logic: connect, bootstrap, apply FCC, restore CE
- **MainActivity.kt** — Compose UI

## Key Protocol Details

- **Sender byte**: `0x02` (device 2, network 0) for USB AOA. The JSON profiles ship with `0x82` (network 4, for smart controllers) but the code overrides to `0x02` via `senderOverride`.
- **RCLink envelope**: `[0x55, 0xCC, route0, route1, LE32_length, DUML_frame]`. Route bytes default to `{0x49, 0x57}` ("IW") and are updated dynamically from received frames.
- **Sequence numbers**: Single global `AtomicInteger` in `ProfileLoader.globalSeq`, shared across bootstrap, keepalive, FCC, and CE restore. Starts at 149.
- **Bootstrap**: Two frames — cmdSet=0, cmdId=0, dst=0x1F then dst=0x00, payload={0,0,1}
- **Keepalive**: cmdSet=6, cmdId=0x77, dst=0x06 and dst=0x0E, every 2.5s
- **FCC profile**: 21 frames, 2 rounds, 150ms inter-frame, 400ms inter-round

## Build

```bash
SIGN=/home/ofirb/freefcc-signing
PW=$(grep '^storePassword=' "$SIGN/keystore.properties" | cut -d= -f2-)
SIGNING_STORE_FILE="$SIGN/freefcc-release.jks" \
SIGNING_STORE_PASSWORD="$PW" \
SIGNING_KEY_ALIAS=freefcc \
SIGNING_KEY_PASSWORD="$PW" \
./gradlew assembleRelease --no-daemon
```

APK output: `app/build/outputs/apk/release/app-release.apk`

## Rules

- All git commits must use author `doesthings <doesthings@users.noreply.github.com>` — set via local git config, never use global config
- Never mention competitor app names, reverse engineering, or decompilation in code, comments, commit messages, or release notes
- Use `gh auth switch --user doesthings` before any GitHub operations
- Signing key is at `/home/ofirb/freefcc-signing/` — never commit it or log the password
