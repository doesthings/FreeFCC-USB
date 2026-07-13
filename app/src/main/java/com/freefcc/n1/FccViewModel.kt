package com.freefcc.n1

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Immutable UI state for the entire app.
 *
 * The ViewModel updates this via `copy()` and the Compose layer observes it
 * with `collectAsStateWithLifecycle()`. Every field here represents something
 * the UI needs to render. This mirrors the standard Android ViewModel + StateFlow pattern.
 */
data class AppState(
    val status: String = "idle",
    val message: String = "",
    val transportName: String = "",
    val isConnected: Boolean = false,
    val isFccEnabled: Boolean = false,
    val is4gEnabled: Boolean = false,
    val is4gBusy: Boolean = false,
    val isBusy: Boolean = false,
    val busyProgress: Float = 0f,
    val aircraftSerial: String = "",
    val controllerModel: String = "",
    val transportKind: String = "",
    val deviceInfo: String = "",
    val isQueryingInfo: Boolean = false,
    val autoFcc: Boolean = false,
    val isLedBusy: Boolean = false,
    val ledStatus: String = "",
    val remoteIdDisabled: Boolean = false,
    val isRidBusy: Boolean = false,
    val logMessages: List<String> = emptyList()
)

/**
 * Manages all app state and business logic.
 *
 * Design notes:
 *
 * 1. **Dual transport.** Unlike the TCP-only FCC tools,
 *    this app auto-detects which transport is available. On smart controllers
 *    (RC2/RC Pro/RC Plus) the TCP proxy at 127.0.0.1:40009 is used. On
 *    phones/tablets cabled to a DJI RC-N1/RC-N2 or directly to the drone,
 *    the USB bulk endpoint is used. The same DUMPL frames go over both.
 *
 * 2. **No license, no server, no trial.** There is no license check, no
 *    integrity check, no self-update, no server contact. The FCC profile
 *    is a JSON asset. The app works offline from first launch, forever.
 *
 * 3. **Honest success reporting.** We track whether `write()` actually
 *    returned true for at least one frame, and only report success if so.
 *    This fixes the false-success bug identified in the false-success bug.
 *
 * 4. **Auto-FCC.** A toggle persists across restarts. When on, the app
 *    auto-connects and applies FCC on every launch.
 */
class FccViewModel(private val app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var transport: DumplTransport? = null
    private val prefs = app.getSharedPreferences("freefcc_n1", Context.MODE_PRIVATE)

    /** Called once from MainActivity.onCreate(). */
    fun init() {
        val model = try { Build.DEVICE } catch (_: Exception) { "unknown" }
        val autoEnabled = prefs.getBoolean("auto_fcc", false)
        update { copy(controllerModel = model, status = "disconnected", autoFcc = autoEnabled) }

        if (autoEnabled) {
            log("Auto-FCC enabled — connecting and applying...")
            autoConnectAndApply()
        }
    }

    // --- Auto-FCC ---

    fun toggleAutoFcc() {
        val newValue = !_state.value.autoFcc
        prefs.edit().putBoolean("auto_fcc", newValue).apply()
        update { copy(autoFcc = newValue) }
        log(if (newValue) "Auto-FCC enabled — will auto-connect on next launch" else "Auto-FCC disabled")
    }

    private fun autoConnectAndApply() {
        runOnIO {
            delay(1000)
            update { copy(status = "connecting", message = "Auto-connecting...") }
            if (!connectInternal()) {
                log("Auto-FCC: controller not found — is the drone powered on / cable connected?")
                update { copy(status = "disconnected", message = "Controller not found. Auto-FCC will retry when you tap Connect.") }
                return@runOnIO
            }
            log("Auto-FCC: ${state.value.transportKind} connected")
            val serial = probeSerialInternal()
            update {
                copy(
                    status = "connected",
                    isConnected = true,
                    aircraftSerial = serial,
                    message = "Connected. Auto-applying FCC..."
                )
            }
            if (serial.isNotEmpty()) log("Aircraft serial: $serial")

            delay(500)
            update { copy(status = "applying", isBusy = true, busyProgress = 0f, message = "Auto-enabling FCC...") }
            log("Auto-FCC: enabling FCC mode...")

            val success = applyFccInternal()
            if (success) {
                update {
                    copy(
                        status = "fcc_enabled",
                        message = "FCC mode enabled (auto)",
                        isFccEnabled = true,
                        isBusy = false,
                        busyProgress = 1f,
                        isConnected = true
                    )
                }
                log("Auto-FCC: FCC mode enabled")
            } else {
                update {
                    copy(
                        status = "connected",
                        message = "Auto-FCC failed — try manually",
                        isBusy = false,
                        busyProgress = 0f
                    )
                }
                log("Auto-FCC: apply failed — try manually")
            }
        }
    }

    // --- Connection ---

    /**
     * Connects to the controller. Tries USB first (covers phone+RC-N1/RC-N2
     * and direct-to-drone USB), then falls back to the TCP proxy (smart
     * controllers). This is the universal path the user asked for.
     */
    fun connect() {
        update { copy(status = "connecting", message = "Connecting to controller...") }
        log("Connecting to controller...")

        runOnIO {
            if (connectInternal()) {
                log("${state.value.transportKind} connected")
                val serial = probeSerialInternal()
                update {
                    copy(
                        status = "connected",
                        message = if (serial.isNotEmpty()) "Connected — $serial" else "Connected. Ready to apply FCC.",
                        isConnected = true,
                        aircraftSerial = serial
                    )
                }
                if (serial.isNotEmpty()) log("Aircraft serial: $serial")
            } else {
                update {
                    copy(
                        status = "disconnected",
                        message = "Controller not found. Plug in the RC via USB, or power on a smart controller.",
                        isConnected = false
                    )
                }
                log("Connection failed — no USB accessory and no TCP proxy at 127.0.0.1:40009")
            }
        }
    }

    /**
     * Tries USB first, then TCP. Returns true and sets [transport] on success.
     */
    private fun connectInternal(): Boolean {
        // 1) USB accessory/device — phone cabled to RC-N1/RC-N2 or directly to drone
        val usb = UsbTransport.openAttached(app)
        if (usb != null) {
            transport = usb
            update {
                copy(
                    transportName = usb.name,
                    transportKind = "USB"
                )
            }
            return true
        }

        // 2) TCP proxy — smart controller running the app on-device
        val tcp = TcpTransport()
        if (tcp.open()) {
            transport = tcp
            update {
                copy(
                    transportName = tcp.name,
                    transportKind = "TCP"
                )
            }
            return true
        }
        tcp.close()
        return false
    }

    // --- FCC ---

    /** Sends the 21-frame universal FCC unlock profile (2 rounds, 150ms between frames). */
    fun enableFcc() {
        if (!isControllerReachable()) return
        update { copy(status = "applying", isBusy = true, busyProgress = 0f, message = "Enabling FCC mode...") }
        log("Enabling FCC mode...")

        runOnIO {
            val success = applyFccInternal()
            if (success) {
                update {
                    copy(
                        status = "fcc_enabled",
                        message = "FCC mode enabled",
                        isFccEnabled = true,
                        isBusy = false,
                        busyProgress = 1f,
                        isConnected = true
                    )
                }
                log("FCC mode enabled")
            } else {
                update {
                    copy(
                        status = "connected",
                        message = "FCC apply failed — RC link unreachable. Make sure the drone is on and linked.",
                        isBusy = false,
                        busyProgress = 0f
                    )
                }
                log("FCC apply failed — writes failed")
            }
        }
    }

    private fun applyFccInternal(): Boolean {
        val t = transport ?: return false
        val profile = ProfileLoader.load(app, "fcc.json")
        log("Loaded FCC profile: ${profile.frames.size} frames, ${profile.rounds} rounds")

        return sendProfile(t, profile)
    }

    /** Sends the CE restore command: a single frame that resets to factory region. */
    fun disableFcc() {
        if (!isControllerReachable()) return
        update { copy(status = "restoring", isBusy = true, busyProgress = 0f, message = "Restoring CE mode...") }
        log("Restoring CE mode...")

        runOnIO {
            val t = transport ?: return@runOnIO
            val profile = ProfileLoader.load(app, "ce_restore.json")
            val success = sendProfile(t, profile)
            if (success) {
                update { copy(status = "connected", message = "CE mode restored", isFccEnabled = false, isBusy = false) }
                log("CE mode restored")
            } else {
                update { copy(status = "connected", message = "CE restore failed — RC link unreachable", isBusy = false) }
                log("CE restore failed")
            }
        }
    }

    // --- 4G ---

    /** Sends the 128-frame 4G activation profile with the aircraft serial embedded in each payload. */
    fun enable4g() {
        if (!isControllerReachable()) return
        update { copy(is4gBusy = true, busyProgress = 0f, message = "Turning 4G on...") }
        log("Enabling 4G...")

        runOnIO {
            val serial = getOrProbeSerial()
            if (serial.isEmpty()) {
                update { copy(is4gBusy = false, message = "4G needs the aircraft connected. Power on the drone and try again.") }
                log("4G failed — no aircraft serial")
                return@runOnIO
            }

            val profile = ProfileLoader.load4g(app, serial)
            log("Loaded 4G profile: ${profile.frames.size} frames (serial: $serial)")

            val t = transport
            val success = if (t != null) {
                // On USB we send 4G frames through the same bulk endpoint (dst=OFDM_GROUND 238)
                sendProfile(t, profile)
            } else {
                // On smart controllers, 4G frames go via Unix domain socket
                var anySuccess = false
                for ((i, frame) in profile.frames.withIndex()) {
                    if (sendUnix4gFrame(frame)) anySuccess = true
                    update { copy(busyProgress = (i + 1).toFloat() / profile.frames.size) }
                    if (profile.interFrameDelay > 0) Thread.sleep(profile.interFrameDelay)
                }
                anySuccess
            }

            if (success) {
                update { copy(is4gEnabled = true, is4gBusy = false, busyProgress = 0f, message = "4G enabled") }
                log("4G enabled — ${profile.frames.size} frames sent")
            } else {
                update { copy(is4gBusy = false, message = "4G apply failed — is the 4G dongle connected?") }
                log("4G apply failed — socket/bulk endpoint unreachable")
            }
        }
    }

    fun disable4g() {
        if (!isControllerReachable()) return
        update { copy(is4gBusy = true, message = "Turning 4G off...") }
        log("Disabling 4G...")

        runOnIO {
            // Re-apply FCC (keeps FCC on) or restore CE
            if (_state.value.isFccEnabled) {
                applyFccInternal()
                log("FCC re-applied (4G off)")
            } else {
                val t = transport
                if (t != null) {
                    val ce = ProfileLoader.load(app, "ce_restore.json")
                    sendProfile(t, ce)
                }
                log("CE restored (4G off)")
            }
            update {
                copy(
                    is4gEnabled = false,
                    is4gBusy = false,
                    message = if (isFccEnabled) "4G disabled — FCC still active" else "4G disabled — CE restored"
                )
            }
            log("4G disabled")
        }
    }

    // --- Remote ID ---

    /** Disables Remote ID packet transmission. */
    fun disableRemoteId() {
        if (!isControllerReachable()) return
        update { copy(isRidBusy = true, message = "Disabling Remote ID...") }
        log("Disabling Remote ID...")

        runOnIO {
            val t = transport ?: return@runOnIO
            val profile = ProfileLoader.load(app, "remote_id_off.json")
            val success = sendProfile(t, profile)
            if (success) {
                update { copy(isRidBusy = false, remoteIdDisabled = true, message = "Remote ID disabled") }
                log("Remote ID disabled")
            } else {
                update { copy(isRidBusy = false, message = "Remote ID disable failed — RC link unreachable") }
                log("Remote ID disable failed")
            }
        }
    }

    /** Re-enables Remote ID packet transmission. */
    fun enableRemoteId() {
        if (!isControllerReachable()) return
        update { copy(isRidBusy = true, message = "Enabling Remote ID...") }
        log("Enabling Remote ID...")

        runOnIO {
            val t = transport ?: return@runOnIO
            val profile = ProfileLoader.load(app, "remote_id_on.json")
            val success = sendProfile(t, profile)
            if (success) {
                update { copy(isRidBusy = false, remoteIdDisabled = false, message = "Remote ID enabled") }
                log("Remote ID enabled")
            } else {
                update { copy(isRidBusy = false, message = "Remote ID enable failed — RC link unreachable") }
                log("Remote ID enable failed")
            }
        }
    }

    // --- LED ---

    fun setLed(on: Boolean) {
        if (!isControllerReachable()) return
        update { copy(isLedBusy = true, ledStatus = if (on) "Turning LEDs on..." else "Turning LEDs off...") }
        log(if (on) "Turning LEDs on..." else "Turning LEDs off...")

        runOnIO {
            val t = transport ?: return@runOnIO
            val fileName = if (on) "led_on.json" else "led_off.json"
            val profile = ProfileLoader.load(app, fileName)
            log("Loaded LED profile: ${profile.frames.size} frames")

            var anySuccess = false
            // Send twice with a delay for reliability (matches FreeFCC behaviour)
            for (attempt in 0 until 2) {
                if (attempt > 0) delay(500)
                if (sendProfile(t, profile)) anySuccess = true
            }

            if (anySuccess) {
                update { copy(isLedBusy = false, ledStatus = if (on) "ON" else "OFF") }
                log(if (on) "LEDs turned on" else "LEDs turned off")
            } else {
                update { copy(isLedBusy = false, ledStatus = "Failed — is DJI Fly running?") }
                log("LED command failed")
            }
        }
    }

    // --- Device Info ---

    fun queryDeviceInfo() {
        if (!isControllerReachable()) return
        update { copy(isQueryingInfo = true) }
        log("Querying device info...")

        runOnIO {
            val t = transport ?: return@runOnIO
            val profile = ProfileLoader.load(app, "device_info.json")
            val frame = profile.frames.first()

            // Send the query frame and read the full DUMPL response
            val response = sendAndReceive(t, frame, profile.readWindowMs)

            if (response == null || response.isEmpty()) {
                update { copy(isQueryingInfo = false, deviceInfo = "No response from controller") }
                log("Device info: no response")
                return@runOnIO
            }

            val info = formatVersionResponse(response)
            update { copy(isQueryingInfo = false, deviceInfo = info) }
            log("Device info received: ${response.size} bytes")
        }
    }

    fun probeSerial() {
        log("Probing for aircraft serial...")
        runOnIO {
            val serial = probeSerialInternal()
            if (serial.isNotEmpty()) {
                update { copy(aircraftSerial = serial) }
                log("Aircraft serial: $serial")
            } else {
                log("No serial detected — is the aircraft powered on?")
            }
        }
    }

    // --- Internal helpers ---

    private fun isControllerReachable(): Boolean {
        if (_state.value.isConnected && transport != null) return true
        log("Connect to the controller first")
        return false
    }

    /**
     * Sends a profile's frames over [transport]. Opens a fresh connection per
     * frame on TCP (the proxy expects one frame per connection). On USB the
     * connection stays open — we just bulk-write each frame.
     *
     * Returns true if at least one frame was written successfully (honest
     * success reporting — reports success only if writes actually succeeded).
     */
    private fun sendProfile(t: DumplTransport, profile: ProfileLoader.Profile): Boolean {
        var anySuccess = false
        val totalSends = profile.frames.size * profile.rounds
        var sent = 0

        for (round in 0 until profile.rounds) {
            for (frame in profile.frames) {
                // On TCP, open a fresh connection per frame (proxy requirement).
                // On USB, the connection is already open from connectInternal().
                val needsPerFrameOpen = t is TcpTransport
                if (needsPerFrameOpen && !t.open()) {
                    sent++
                    update { copy(busyProgress = sent.toFloat() / totalSends) }
                    if (profile.interFrameDelay > 0) Thread.sleep(profile.interFrameDelay)
                    continue
                }

                val wrote = t.write(frame)
                if (wrote) {
                    anySuccess = true
                    // Read and discard the ACK so the controller has time to process
                    try {
                        val ack = ByteArray(2048)
                        t.read(ack, ack.size, profile.readWindowMs)
                    } catch (_: Exception) {}
                }

                if (needsPerFrameOpen) t.close()

                sent++
                update { copy(busyProgress = sent.toFloat() / totalSends) }
                if (profile.interFrameDelay > 0) Thread.sleep(profile.interFrameDelay)
            }
            if (profile.interRoundDelay > 0 && round < profile.rounds - 1) {
                Thread.sleep(profile.interRoundDelay)
            }
        }
        return anySuccess
    }

    /**
     * Sends a single frame and reads the full DUMPL response. Used for
     * request/response commands like VersionInquiry. Returns the response
     * payload (without the 2 CRC bytes).
     */
    private fun sendAndReceive(
        t: DumplTransport,
        frame: ByteArray,
        readWindowMs: Int
    ): ByteArray? {
        val needsOpen = t is TcpTransport
        if (needsOpen && !t.open()) return null

        try {
            if (!t.write(frame)) return null

            // Read the 11-byte DUMPL header
            val header = ByteArray(11)
            val n = t.read(header, header.size, readWindowMs)
            if (n < 11) return null
            if (header[0] != 0x55.toByte()) return null

            // Extract total length from bytes 1-2 (11-bit LE)
            val totalLength = (header[1].toInt() and 0xFF) or ((header[2].toInt() and 0x03) shl 8)
            if (totalLength <= 13) return ByteArray(0)
            if (totalLength > 1023) return null

            // Read the rest (payload + 2 CRC bytes)
            val remaining = ByteArray(totalLength - 11)
            val m = t.read(remaining, remaining.size, readWindowMs)
            if (m < remaining.size) return null

            // Extract payload (skip 2 CRC bytes at the end)
            val payloadLength = remaining.size - 2
            if (payloadLength <= 0) return ByteArray(0)

            val payload = ByteArray(payloadLength)
            System.arraycopy(remaining, 0, payload, 0, payloadLength)
            return payload
        } finally {
            if (needsOpen) t.close()
        }
    }

    private fun probeSerialInternal(): String {
        val t = transport ?: return ""
        val needsOpen = t is TcpTransport
        if (needsOpen && !t.open()) return ""

        try {
            val buffer = StringBuilder()
            val buf = ByteArray(4096)
            val deadline = System.currentTimeMillis() + 1500

            while (System.currentTimeMillis() < deadline) {
                val n = t.read(buf, buf.size, 200)
                if (n > 0) {
                    buffer.append(String(buf, 0, n))
                    // Try the full aircraft serial pattern first (1581XXXXXXXXXXX)
                    Regex("1581[0-9A-Za-z]{12,18}").find(buffer.toString())?.let { return it.value }
                    // Fallback: model code pattern (W[AM]xxx)
                    Regex("W[AM][0-9]{3}").find(buffer.toString())?.let { return it.value }
                } else if (n < 0) break
            }
        } finally {
            if (needsOpen) t.close()
        }
        return ""
    }

    private fun getOrProbeSerial(): String {
        var serial = _state.value.aircraftSerial
        if (serial.isEmpty()) {
            log("Probing for aircraft serial...")
            serial = probeSerialInternal()
            if (serial.isNotEmpty()) {
                update { copy(aircraftSerial = serial) }
                log("Aircraft serial: $serial")
            }
        }
        return serial
    }

    /**
     * Parses a DUMPL VersionInquiry response payload into a human-readable string.
     *
     * Response layout (from dji-firmware-tools DJIPayload_General_VersionInquiryRe):
     *   bytes 0-1    unknown
     *   bytes 2-17   hardware version (16-char ASCII string)
     *   bytes 18-21  bootloader version (uint32 LE)
     *   bytes 22-25  firmware version (uint32 LE)
     */
    private fun formatVersionResponse(payload: ByteArray): String {
        val lines = mutableListOf<String>()

        if (payload.size >= 18) {
            val hwVersion = String(payload, 2, 16, Charsets.US_ASCII).trimEnd('\u0000')
            lines.add("Hardware: $hwVersion")
        }
        if (payload.size >= 22) {
            val ldrVersion = readUInt32LE(payload, 18)
            lines.add("Bootloader: ${formatVersion(ldrVersion)}")
        }
        if (payload.size >= 26) {
            val appVersion = readUInt32LE(payload, 22)
            lines.add("Firmware: ${formatVersion(appVersion)}")
        }
        lines.add("")
        lines.add("Raw payload (${payload.size} bytes):")
        lines.add(payload.joinToString(" ") { "%02x".format(it) })

        return lines.joinToString("\n")
    }

    private fun readUInt32LE(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xFF)) or
                ((data[offset + 1].toLong() and 0xFF) shl 8) or
                ((data[offset + 2].toLong() and 0xFF) shl 16) or
                ((data[offset + 3].toLong() and 0xFF) shl 24)

    private fun formatVersion(version: Long): String {
        val major = (version shr 24) and 0xFF
        val minor = (version shr 16) and 0xFF
        val patch = (version shr 8) and 0xFF
        val build = version and 0xFF
        return "$major.$minor.$patch.$build"
    }

    // --- State plumbing ---

    private fun update(block: AppState.() -> AppState) {
        _state.value = _state.value.block()
    }

    private fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val entry = "[$time] $message"
        update { copy(logMessages = (listOf(entry) + logMessages).take(50)) }
    }

    private fun runOnIO(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { block() }
    }
}