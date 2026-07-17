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
 * Matches the NLD FCC app's connection flow 1:1:
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
     * and sends the bootstrap handshake — matching NLD FCC exactly.
     */
    private fun connectInternal(): Boolean {
        val accessory = AccessoryTransport.open(app)
        if (accessory != null) {
            accessory.open() // starts TX thread + keepalive thread
            transport = accessory
            update { copy(transportName = accessory.name, transportKind = "USB-AOA") }

            // Send bootstrap handshake (matching NLD FCC's CONN_BOOTSTRAP_3100 + CONN_BOOTSTRAP_0000)
            sendBootstrap()
            log("Bootstrap handshake sent")
            return true
        }
        return false
    }

    /**
     * Sends the 2-frame bootstrap handshake that NLD FCC sends immediately
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

        // Frame 1: CONN_BOOTSTRAP_3100 — dst component 0x1F (RC-N1 FCC/link service)
        val frame1 = builder.buildFrame(DumplFrame(
            sender = 0x02,    // mobile app (0200)
            cmdType = 0x40,   // request expecting ACK
            cmdSet = 0x00,
            cmdId = 0x00,
            dst = 0x1F,       // component 31 (3100)
            payload = payload
        ))
        t.write(wrapRclink(frame1))

        // Frame 2: CONN_BOOTSTRAP_0000 — dst component 0x00 (broadcast)
        val frame2 = builder.buildFrame(DumplFrame(
            sender = 0x02,
            cmdType = 0x40,
            cmdSet = 0x00,
            cmdId = 0x00,
            dst = 0x00,       // broadcast (0000)
            payload = payload
        ))
        t.write(wrapRclink(frame2))
    }

    fun enableFcc() {
        if (!isControllerReachable()) return
        update { copy(status = "applying", isBusy = true, busyProgress = 0f, message = "Enabling FCC mode...") }
        log("Enabling FCC mode...")
        runOnIO {
            val success = applyFccInternal()
            if (success) {
                update { copy(status = "fcc_enabled", message = "FCC mode enabled", isFccEnabled = true, isBusy = false, busyProgress = 1f, isConnected = true) }
                log("FCC mode enabled")
            } else {
                update { copy(status = "connected", message = "FCC apply failed — is the drone on and linked?", isBusy = false, busyProgress = 0f) }
                log("FCC apply failed — writes failed")
            }
        }
    }

    /**
     * Sends the 21-frame FCC profile, each wrapped in the RCLink envelope.
     * The TX thread handles the 3ms inter-frame delay and keepalives run
     * in the background to keep the session alive.
     */
    private fun applyFccInternal(): Boolean {
        val t = transport ?: return false
        val profile = ProfileLoader.load(app, "fcc.json")
        log("Loaded FCC profile: ${profile.frames.size} frames, ${profile.rounds} rounds")

        var anySuccess = false
        val totalSends = profile.frames.size * profile.rounds
        var sent = 0

        for (round in 0 until profile.rounds) {
            for (frame in profile.frames) {
                // Wrap each DUMPL frame in the RCLink envelope before sending
                val wrapped = wrapRclink(frame)
                if (t.write(wrapped)) {
                    anySuccess = true
                }
                sent++
                update { copy(busyProgress = sent.toFloat() / totalSends) }
                // The TX thread handles the 3ms inter-frame delay internally.
                // We add a small sleep here for the progress bar to update smoothly.
                try { Thread.sleep(5) } catch (_: Exception) {}
            }
            if (profile.interRoundDelay > 0) {
                try { Thread.sleep(profile.interRoundDelay) } catch (_: Exception) {}
            }
        }
        return anySuccess
    }

    fun disableFcc() {
        if (!isControllerReachable()) return
        update { copy(status = "restoring", isBusy = true, busyProgress = 0f, message = "Restoring CE mode...") }
        log("Restoring CE mode...")
        runOnIO {
            val t = transport ?: return@runOnIO
            val profile = ProfileLoader.load(app, "ce_restore.json")
            var anySuccess = false
            for (frame in profile.frames) {
                if (t.write(wrapRclink(frame))) anySuccess = true
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