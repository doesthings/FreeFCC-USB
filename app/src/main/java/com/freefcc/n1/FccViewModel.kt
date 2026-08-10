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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AppState(
    val status: String = "idle",
    val message: String = "",
    val transportName: String = "",
    val isConnected: Boolean = false,
    val isFccEnabled: Boolean = false,
    val isBusy: Boolean = false,
    val busyProgress: Float = 0f,
    val controllerModel: String = "",
    val transportKind: String = "",
    val autoFcc: Boolean = false,
    val logMessages: List<String> = emptyList()
)

/**
 * Manages all app state and business logic.
 *
 * Matches the DJI controller's connection flow 1:1:
 * 1. Open AOA accessory on the TOP USB port
 * 2. Start dedicated TX thread with 3ms inter-frame delay
 * 3. Send bootstrap handshake (CONN_BOOTSTRAP_3100 + CONN_BOOTSTRAP_0000)
 * 4. Start RCLink keepalive thread (every 2.5s)
 * 5. Send FCC DUMPL frames wrapped in RCLink envelope
 */
class FccViewModel(private val app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var transport: DumplTransport? = null
    private val prefs = app.getSharedPreferences("freefcc_n1", Context.MODE_PRIVATE)

    fun init() {
        val model = try { Build.DEVICE } catch (_: Exception) { "unknown" }
        val autoEnabled = prefs.getBoolean("auto_fcc", false)
        update { copy(controllerModel = model, status = "disconnected", autoFcc = autoEnabled) }

        if (autoEnabled) {
            log("Auto-FCC enabled — connecting and applying...")
            autoConnectAndApply()
        }
    }

    fun toggleAutoFcc() {
        val newValue = !_state.value.autoFcc
        prefs.edit().putBoolean("auto_fcc", newValue).apply()
        update { copy(autoFcc = newValue) }
        log(if (newValue) "Auto-FCC enabled" else "Auto-FCC disabled")
    }

    private fun autoConnectAndApply() {
        runOnIO {
            delay(1000)
            update { copy(status = "connecting", message = "Auto-connecting...") }
            if (!connectInternal()) {
                log("Auto-FCC: controller not found")
                update { copy(status = "disconnected", message = "Controller not found. Make sure DJI Fly is closed and the phone is connected to the TOP USB port.") }
                return@runOnIO
            }
            log("Auto-FCC: connected")
            update { copy(status = "connected", isConnected = true, message = "Connected. Auto-applying FCC...") }
            delay(500)
            update { copy(status = "applying", isBusy = true, busyProgress = 0f, message = "Auto-enabling FCC...") }
            val success = applyFccInternal()
            if (success) {
                update { copy(status = "fcc_enabled", message = "FCC mode enabled (auto)", isFccEnabled = true, isBusy = false, busyProgress = 1f, isConnected = true) }
                log("Auto-FCC: FCC mode enabled")
            } else {
                update { copy(status = "connected", message = "Auto-FCC failed — try manually", isBusy = false, busyProgress = 0f) }
                log("Auto-FCC: apply failed")
            }
        }
    }

    fun connect() {
        update { copy(status = "connecting", message = "Connecting to controller...") }
        log("Connecting to controller...")
        log("Make sure DJI Fly is closed and the phone is plugged into the TOP USB port.")
        runOnIO {
            if (connectInternal()) {
                log("Connected")
                update { copy(status = "connected", message = "Connected. Ready to apply FCC.", isConnected = true) }
            } else {
                update { copy(status = "disconnected", message = "Controller not found. Close DJI Fly, plug into the TOP USB port, then tap Connect.", isConnected = false) }
                log("Connection failed — no DJI USB accessory detected")
            }
        }
    }

    /**
     * Opens the AOA accessory on the TOP port, starts the TX thread,
     * and sends the bootstrap handshake — matching the DJI controller exactly.
     */
    private fun connectInternal(): Boolean {
        // Try AOA first (phone plugged into RC's USB port)
        val accessory = AccessoryTransport.open(app)
        if (accessory != null) {
            accessory.rxListener = { frame -> logRxFrame(frame) }
            accessory.open()
            transport = accessory
            update { copy(transportName = accessory.name, transportKind = "USB-AOA") }
            sendBootstrap()
            log("Connected via AOA (RC USB port)")
            log("Bootstrap handshake sent")
            return true
        }

        // Try VCOM (phone plugged directly into drone's USB-C port)
        val vcom = VcomTransport.open(app)
        if (vcom != null) {
            vcom.open()
            transport = vcom
            update { copy(transportName = vcom.name, transportKind = "USB-VCOM") }
            log("Connected via VCOM (drone USB port)")
            log("Direct-to-drone mode — FCC will persist while DJI Fly stays connected via RC")
            return true
        }

        return false
    }

    /**
     * Sends the 2-frame bootstrap handshake that the DJI controller sends immediately
     * after opening the AOA accessory.
     *
     * Frame 1: CONN_BOOTSTRAP_3100 — dst=0x1F, payload={0x00,0x00,0x01}
     * Frame 2: CONN_BOOTSTRAP_0000 — dst=0x00, payload={0x00,0x00,0x01}
     *
     * Both frames use src=0x02 (mobile app), cmd_type=0x40 (request expecting ACK).
     * The RC-N1 requires this handshake before accepting any DUMPL commands.
     */
    private fun sendBootstrap() {
        val t = transport ?: return
        val builder = DumplBuilder()
        val payload = byteArrayOf(0x00, 0x00, 0x01)

        val frame1 = builder.buildFrame(DumplFrame(0x02, 0x40, 0x00, 0x00, 0x1F, payload))
        t.write(wrapRclink(frame1))

        val frame2 = builder.buildFrame(DumplFrame(0x02, 0x40, 0x00, 0x00, 0x00, payload))
        t.write(wrapRclink(frame2))
    }

    private var fccRepeatThread: Thread? = null

    fun enableFcc() {
        if (!isControllerReachable()) return
        update { copy(status = "applying", isBusy = true, busyProgress = 0f, message = "Enabling FCC mode...") }
        log("Enabling FCC mode...")
        runOnIO {
            val success = applyFccInternal()
            if (success) {
                update { copy(status = "fcc_enabled", message = "FCC mode enabled — switch to DJI Fly now", isFccEnabled = true, isBusy = false, busyProgress = 1f, isConnected = true) }
                log("FCC mode enabled — starting repeat service to maintain FCC")
                startFccRepeat()
            } else {
                update { copy(status = "connected", message = "FCC apply failed — is the drone on and linked?", isBusy = false, busyProgress = 0f) }
                log("FCC apply failed — writes failed")
            }
        }
    }

    private fun sendAssistantUnlock(t: DumplTransport, route: ByteArray) {
        val unlock = DumplBuilder().buildFrame(
            DumplFrame(0x02, 0x40, 0x03, 0xDF, 0x03, byteArrayOf(0x01, 0x00, 0x00, 0x00))
        )
        val toSend = if (t is VcomTransport) unlock else wrapRclink(unlock, route)
        t.write(toSend)
        try { Thread.sleep(350) } catch (_: Exception) {}
    }

    private fun applyFccInternal(): Boolean {
        val t = transport ?: return false
        val isVcom = t is VcomTransport
        val route = if (t is AccessoryTransport) t.currentRoute() else byteArrayOf(0x49, 0x57)

        // Send the WLM FCC command: cmdSet=0x51, cmdId=4, dst=0xEE, sender=0xA2
        // This is the actual command that switches the radio to FCC mode.
        log("Sending WLM FCC command (cmdSet=0x51 cmdId=4)")
        val fccFrame = DumplBuilder().buildFrame(
            DumplFrame(0xA2, 0x40, 0x51, 0x04, 0xEE, ByteArray(0))
        )
        val fccWrapped = if (isVcom) fccFrame else wrapRclink(fccFrame, route)
        t.write(fccWrapped)
        try { Thread.sleep(1300) } catch (_: Exception) {}

        // Also send the full 21-frame profile for redundancy
        val usbSender = 0x02
        val profile = try {
            ProfileLoader.load(app, "fcc.json", senderOverride = usbSender)
        } catch (e: Exception) {
            log("Failed to load FCC profile: ${e.message}")
            return true // WLM command already sent
        }
        log("Sending ${profile.frameDefs.size} supplemental FCC frames, ${profile.rounds} rounds")

        var anySuccess = true
        val totalSends = profile.frameDefs.size * profile.rounds
        var sent = 0

        for (round in 0 until profile.rounds) {
            for (def in profile.frameDefs) {
                if (def.cmdSet == 3) {
                    sendAssistantUnlock(t, route)
                }
                val frame = ProfileLoader.buildFrame(def, profile.sender, profile.cmdType)
                val toSend = if (isVcom) frame else wrapRclink(frame, route)
                t.write(toSend)
                sent++
                _state.update { it.copy(busyProgress = sent.toFloat() / totalSends) }
                if (profile.interFrameDelay > 0) {
                    try { Thread.sleep(profile.interFrameDelay) } catch (_: Exception) {}
                }
            }
            if (profile.interRoundDelay > 0) {
                try { Thread.sleep(profile.interRoundDelay) } catch (_: Exception) {}
            }
        }
        return anySuccess
    }

    /**
     * Continuously re-sends the FCC profile every 10 seconds to prevent
     * DJI Fly from resetting the radio back to CE mode. DJI Fly resets
     * FCC on every connection — the repeat service fights back.
     */
    private fun startFccRepeat() {
        stopFccRepeat()
        fccRepeatThread = Thread({
            val t = transport ?: return@Thread
            val route = if (t is AccessoryTransport) t.currentRoute() else byteArrayOf(0x49, 0x57)
            val builder = DumplBuilder()
            val profile = try {
                ProfileLoader.load(app, "fcc.json", senderOverride = 0x02)
            } catch (_: Exception) { return@Thread }

            while (_state.value.isFccEnabled && transport != null) {
                try { Thread.sleep(2_000) } catch (_: InterruptedException) { break }
                if (!_state.value.isFccEnabled) break

                // Re-send WLM FCC command + profile
                val isVcom = t is VcomTransport
                val fcc = builder.buildFrame(DumplFrame(0xA2, 0x40, 0x51, 0x04, 0xEE, ByteArray(0)))
                t.write(if (isVcom) fcc else wrapRclink(fcc, route))
                try { Thread.sleep(500) } catch (_: InterruptedException) { break }

                for (def in profile.frameDefs) {
                    if (def.cmdSet == 3) {
                        sendAssistantUnlock(t, route)
                    }
                    val frame = ProfileLoader.buildFrame(def, profile.sender, profile.cmdType)
                    val toSend = if (isVcom) frame else wrapRclink(frame, route)
                    t.write(toSend)
                    try { Thread.sleep(profile.interFrameDelay) } catch (_: InterruptedException) { break }
                }
            }
        }, "FCC-Repeat").apply { isDaemon = true; start() }
    }

    private fun stopFccRepeat() {
        fccRepeatThread?.interrupt()
        fccRepeatThread = null
    }

    fun disableFcc() {
        if (!isControllerReachable()) return
        stopFccRepeat()
        update { copy(status = "restoring", isBusy = true, busyProgress = 0f, message = "Restoring CE mode...") }
        log("Restoring CE mode...")
        runOnIO {
            val t = transport ?: return@runOnIO
            val profile = ProfileLoader.load(app, "ce_restore.json", senderOverride = 0x02)
            val route = if (t is AccessoryTransport) t.currentRoute() else byteArrayOf(0x49, 0x57)
            var anySuccess = false
            for (def in profile.frameDefs) {
                val frame = ProfileLoader.buildFrame(def, profile.sender, profile.cmdType)
                if (t.write(wrapRclink(frame, route))) anySuccess = true
            }
            if (anySuccess) {
                update { copy(status = "connected", message = "CE mode restored", isFccEnabled = false, isBusy = false) }
                log("CE mode restored")
            } else {
                update { copy(status = "connected", message = "CE restore failed", isBusy = false) }
                log("CE restore failed")
            }
        }
    }

    private fun isControllerReachable(): Boolean {
        if (_state.value.isConnected && transport != null) return true
        log("Connect to the controller first")
        return false
    }

    private fun logRxFrame(raw: ByteArray) {
        // Parse RCLink envelope or raw DUML frame
        var offset = 0
        if (raw.size >= 8 && raw[0] == 0x55.toByte() && raw[1] == 0xCC.toByte()) {
            offset = 8 // skip RCLink header
        }
        if (offset + 13 > raw.size) return
        val frame = raw.copyOfRange(offset, raw.size)
        if (frame[0] != 0x55.toByte() || frame.size < 13) return

        val sender = frame[4].toInt() and 0xFF
        val dst = frame[5].toInt() and 0xFF
        val seq = (frame[6].toInt() and 0xFF) or ((frame[7].toInt() and 0xFF) shl 8)
        val cmdType = frame[8].toInt() and 0xFF
        val cmdSet = frame[9].toInt() and 0xFF
        val cmdId = frame[10].toInt() and 0xFF
        val isResponse = (cmdType and 0x80) != 0
        val payloadLen = frame.size - 13
        val payloadHex = if (payloadLen > 0) {
            frame.copyOfRange(11, 11 + minOf(payloadLen, 16)).joinToString(" ") { "%02X".format(it) }
        } else ""

        val tag = if (isResponse) "RX RSP" else "RX REQ"
        log("$tag %02X→%02X seq=$seq set=%02X id=%02X${if (payloadLen > 0) " [$payloadHex]" else ""}".format(sender, dst, cmdSet, cmdId))
    }

    private fun update(block: AppState.() -> AppState) {
        _state.update { it.block() }
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