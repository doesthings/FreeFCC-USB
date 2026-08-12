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
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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

    /**
     * Command IDs we are currently expecting responses for, and how many came
     * back. Armed before each pass and read after it, so the log can say which
     * command path the controller actually answered on this hardware.
     */
    private val ackKeys = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
    private val ackHits = AtomicInteger(0)

    /** The pass that got answered last time, re-used by the repeat service. */
    @Volatile
    private var preferredSender: Int = SENDER_CAPTURE

    companion object {
        /**
         * Sender byte from the captured sequence — device 2, index 4. The
         * broadest set of field reports (Mini 3 Pro / 4 Pro / 5 Pro, Lito 1,
         * Neo 2, Mavic Mini, on RC-N1/N2/N3) come from builds using this.
         */
        const val SENDER_CAPTURE = 0x82

        /**
         * Sender byte on network 0 — what bootstrap and keepalive use, and what
         * builds that worked on Lito X1 + RC-N3 used for the profile too.
         */
        const val SENDER_NET0 = 0x02

        /** Sender byte for the single-command WLM radio switch. */
        const val SENDER_WLM = 0xA2

        /** How long Connect keeps looking for a free USB port. */
        const val CONNECT_TIMEOUT_MS = 15_000L
    }

    /** One self-contained attempt at switching the radio. */
    private data class Pass(val label: String, val sender: Int)

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
                startFccRepeat()
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
                // Reconnecting after a release: pick the repeat back up so the
                // radio keeps getting re-applied.
                if (_state.value.isFccEnabled) {
                    log("Resuming FCC repeat")
                    startFccRepeat()
                }
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
        // The accessory is exclusive, so while DJI Fly holds it this returns
        // nothing. Rather than failing immediately, keep looking for a few
        // seconds — that way the user can background or close DJI Fly after
        // tapping Connect and the app picks the port up the moment it frees.
        val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
        var announced = false
        var firstAttempt = true
        while (true) {
            // Only the first attempt may raise the USB permission dialog, or the
            // retry loop would put one on screen every 500ms.
            val askPermission = firstAttempt
            firstAttempt = false

            // Try AOA first (phone plugged into RC's USB port)
            val accessory = AccessoryTransport.open(app, askPermission)
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
            val vcom = VcomTransport.open(app, askPermission)
            if (vcom != null) {
                vcom.rxListener = { frame -> logRxFrame(frame) }
                vcom.open()
                transport = vcom
                update { copy(transportName = vcom.name, transportKind = "USB-VCOM") }
                log("Connected via VCOM (drone USB port)")
                if (!vcom.isRecognizedDji) {
                    log("Warning: device did not match DJI's vendor ID — this may not be the drone")
                }
                log("Direct-to-drone mode — FCC will persist while DJI Fly stays connected via RC")
                return true
            }

            if (System.currentTimeMillis() >= deadline) return false
            if (!announced) {
                announced = true
                log("Port busy or not ready — retrying for ${CONNECT_TIMEOUT_MS / 1000}s")
                log("If DJI Fly is holding the port, close it now")
            }
            sleepQuietly(500)
        }
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

    /**
     * Unlocks the flight controller for parameter writes. Sent once per pass —
     * sending it before every FLYCONTROLLER frame, each with its own settle
     * delay, stretched the burst far past the service-mode window.
     */
    private fun sendAssistantUnlock(t: DumplTransport, route: ByteArray, sender: Int) {
        val unlock = DumplBuilder().buildFrame(
            DumplFrame(sender, 0x40, 0x03, 0xDF, 0x03, byteArrayOf(0x01, 0x00, 0x00, 0x00))
        )
        t.write(if (t is VcomTransport) unlock else wrapRclink(unlock, route))
        sleepQuietly(60)
    }

    /**
     * Sends one self-contained pass of the profile: profile frame 1 opens
     * service mode, frame 21 closes it, and everything in between has to land
     * inside that window. At 30ms per frame a pass takes about 0.65s.
     *
     * Returns true if any frame reached the transport.
     */
    private fun sendPass(
        t: DumplTransport,
        route: ByteArray,
        profile: ProfileLoader.Profile,
        sender: Int,
        onFrameSent: (() -> Unit)? = null
    ): Boolean {
        val isVcom = t is VcomTransport
        var anyWrite = false
        sendAssistantUnlock(t, route, sender)
        for (round in 0 until profile.rounds) {
            for (def in profile.frameDefs) {
                val frame = ProfileLoader.buildFrame(def, sender, profile.cmdType)
                if (t.write(if (isVcom) frame else wrapRclink(frame, route))) anyWrite = true
                onFrameSent?.invoke()
                sleepQuietly(profile.interFrameDelay)
            }
            sleepQuietly(profile.interRoundDelay)
        }
        return anyWrite
    }

    /**
     * Applies FCC by sweeping every command path that has been confirmed
     * working in the field, because none of them works everywhere:
     *
     * - profile @ 0x82 — the sender byte from the capture. Reported working on
     *   Mini 3 Pro / 4 Pro / 5 Pro, Lito 1, Neo 2 and Mavic Mini across
     *   RC-N1/N2/N3, once the burst is tight enough.
     * - profile @ 0x02 — network 0, the sender bootstrap and keepalive use.
     *   Reported working on Lito X1 + RC-N3.
     * - WLM 0x51/0x04 — the single-command radio switch.
     *
     * Each pass is independent, so a pass that the hardware ignores costs
     * nothing but its own runtime. Responses are counted per pass and logged,
     * which is what tells us (and anyone filing a report) which path this
     * particular drone / RC / phone / DJI Fly combination actually answers on.
     */
    private fun applyFccInternal(): Boolean {
        val t = transport ?: return false
        val isVcom = t is VcomTransport
        val route = if (t is AccessoryTransport) t.currentRoute() else byteArrayOf(0x49, 0x57)

        val profile = try {
            ProfileLoader.load(app, "fcc.json")
        } catch (e: Exception) {
            log("Failed to load FCC profile: ${e.message}")
            return false
        }

        val passes = listOf(
            Pass("profile@%02X".format(SENDER_CAPTURE), SENDER_CAPTURE),
            Pass("profile@%02X".format(SENDER_NET0), SENDER_NET0)
        )

        val totalSends = passes.size * profile.rounds * profile.frameDefs.size + 1
        var sent = 0
        var anyWrite = false
        var bestSender = SENDER_CAPTURE
        var bestAcks = 0

        log("Sweeping ${passes.size} paths x ${profile.rounds} rounds @ ${profile.interFrameDelay}ms/frame")

        for (pass in passes) {
            armAckWatch(profile.frameDefs.map { (it.cmdSet shl 8) or it.cmdId })
            val wrote = sendPass(t, route, profile, pass.sender) {
                sent++
                _state.update { it.copy(busyProgress = sent.toFloat() / totalSends) }
            }
            if (wrote) anyWrite = true
            val acks = readAckWatch(profile.readWindowMs)
            log("${pass.label}: $acks response${if (acks == 1) "" else "s"}")
            if (acks > bestAcks) {
                bestAcks = acks
                bestSender = pass.sender
            }
        }

        // WLM radio switch last, so it can never delay service-mode entry for
        // the profile passes the way it did when it ran first with a 1.3s wait.
        armAckWatch(listOf((0x51 shl 8) or 0x04))
        val wlm = DumplBuilder().buildFrame(
            DumplFrame(SENDER_WLM, 0x40, 0x51, 0x04, 0xEE, ByteArray(0))
        )
        if (t.write(if (isVcom) wlm else wrapRclink(wlm, route))) anyWrite = true
        sent++
        _state.update { it.copy(busyProgress = sent.toFloat() / totalSends) }
        val wlmAcks = readAckWatch(profile.readWindowMs)
        log("WLM 0x51/04: $wlmAcks response${if (wlmAcks == 1) "" else "s"}")

        preferredSender = bestSender
        if (bestAcks > 0) {
            log("Controller answered on %02X — repeat will use that path".format(bestSender))
        } else if (wlmAcks == 0) {
            log("No responses on any path — the RC may not be relaying to the drone")
        }
        return anyWrite
    }

    /** Arms the response counter for the given cmdSet/cmdId keys. */
    private fun armAckWatch(keys: List<Int>) {
        ackKeys.clear()
        ackKeys.addAll(keys)
        ackHits.set(0)
    }

    /** Waits out the read window and returns how many responses came back. */
    private fun readAckWatch(windowMs: Int): Int {
        sleepQuietly(windowMs.toLong().coerceAtLeast(50L))
        val hits = ackHits.get()
        ackKeys.clear()
        return hits
    }

    private fun sleepQuietly(ms: Long) {
        if (ms <= 0) return
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            // Keep the flag set so a cancelled repeat pass unwinds promptly
            // instead of running to the end of the sequence.
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Re-sends the winning pass on an interval so the radio goes back to FCC
     * after something resets it. Two resets get reported: DJI Fly reconnecting,
     * and the drone dropping back to CE the moment it sets its home point on
     * GPS lock. Re-applying is the known remedy for both, so this does it
     * automatically for as long as the app holds the USB.
     */
    private fun startFccRepeat() {
        stopFccRepeat()
        fccRepeatThread = Thread({
            val t = transport ?: return@Thread
            val profile = try {
                ProfileLoader.load(app, "fcc.json")
            } catch (_: Exception) { return@Thread }

            while (_state.value.isFccEnabled && transport != null) {
                try { Thread.sleep(profile.repeatIntervalMs) } catch (_: InterruptedException) { break }
                if (!_state.value.isFccEnabled || transport == null) break

                val route = if (t is AccessoryTransport) t.currentRoute() else byteArrayOf(0x49, 0x57)
                sendPass(t, route, profile, preferredSender)

                // Same shape as apply: WLM last, so whichever path this
                // hardware responds to gets re-sent every cycle.
                val wlm = DumplBuilder().buildFrame(
                    DumplFrame(SENDER_WLM, 0x40, 0x51, 0x04, 0xEE, ByteArray(0))
                )
                t.write(if (t is VcomTransport) wlm else wrapRclink(wlm, route))
            }
        }, "FCC-Repeat").apply { isDaemon = true; start() }
    }

    /**
     * Closes the USB transport without touching the FCC state.
     *
     * The AOA accessory is exclusive: while this app holds it, DJI Fly cannot
     * connect to the controller. Testers have been unplugging the cable and
     * force-closing the app to get around that. This does the same handoff
     * without the unplug — the radio keeps whatever region was last applied.
     */
    fun releaseUsb() {
        stopFccRepeat()
        val t = transport
        transport = null
        try { t?.close() } catch (_: Exception) {}
        update {
            copy(
                status = "released",
                isConnected = false,
                isBusy = false,
                transportKind = "",
                transportName = "",
                message = "USB released. Open DJI Fly now and check the Transmission tab."
            )
        }
        log("USB released — DJI Fly can connect now")
        log("Repeat stopped. If the radio flips back to CE, reconnect and re-apply.")
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
            val isVcom = t is VcomTransport
            val profile = ProfileLoader.load(app, "ce_restore.json")
            val route = if (t is AccessoryTransport) t.currentRoute() else byteArrayOf(0x49, 0x57)
            var anySuccess = false
            // Restore on both sender bytes for the same reason apply sweeps both.
            for (sender in listOf(profile.sender, SENDER_NET0)) {
                for (def in profile.frameDefs) {
                    val frame = ProfileLoader.buildFrame(def, sender, profile.cmdType)
                    if (t.write(if (isVcom) frame else wrapRclink(frame, route))) anySuccess = true
                    sleepQuietly(profile.interFrameDelay)
                }
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

        // The controller streams telemetry continuously, so logging every frame
        // buries the useful lines. Only responses to commands we just sent get
        // counted and logged.
        if (!isResponse) return
        if (!ackKeys.contains((cmdSet shl 8) or cmdId)) return
        ackHits.incrementAndGet()
        log("RSP %02X→%02X seq=$seq set=%02X id=%02X${if (payloadLen > 0) " [$payloadHex]" else ""}".format(sender, dst, cmdSet, cmdId))
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