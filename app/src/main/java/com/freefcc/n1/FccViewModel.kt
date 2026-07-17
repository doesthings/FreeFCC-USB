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
 */
data class AppState(
    val status: String = "idle",
    val message: String = "",
    val transportName: String = "",
    val isConnected: Boolean = false,
    val isFccEnabled: Boolean = false,
    val isBusy: Boolean = false,
    val busyProgress: Float = 0f,
    val aircraftSerial: String = "",
    val controllerModel: String = "",
    val transportKind: String = "",
    val autoFcc: Boolean = false,
    val logMessages: List<String> = emptyList()
)

/**
 * Manages all app state and business logic.
 *
 * 1. **USB serial for RC-N1/RC-N2/RC-N3.** This app connects to the controller
 *    over a CDC ACM serial link at 19200 baud through the USB cable between
 *    your phone and the RC. The controller enumerates as a CDC ACM device
 *    and the library auto-detects it by interface class.
 *
 * 2. **No license, no server, no trial.** The FCC profile is a JSON asset.
 *    The app works offline from first launch, forever.
 *
 * 3. **Honest success reporting.** We track whether `write()` actually
 *    returned true for at least one frame, and only report success if so.
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
            log("Auto-FCC: connected")
            update {
                copy(
                    status = "connected",
                    isConnected = true,
                    message = "Connected. Auto-applying FCC..."
                )
            }

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
     * Connects to the controller. Tries USB serial first (covers phone+RC-N1/N2/N3),
     * then falls back to TCP (smart controllers).
     */
    fun connect() {
        update { copy(status = "connecting", message = "Connecting to controller...") }
        log("Connecting to controller...")

        runOnIO {
            if (connectInternal()) {
                log("Connected")
                update {
                    copy(
                        status = "connected",
                        message = "Connected. Ready to apply FCC.",
                        isConnected = true
                    )
                }
            } else {
                update {
                    copy(
                        status = "disconnected",
                        message = "Controller not found. Connect your phone to the bottom USB port of the RC-N1/RC-N2/RC-N3.",
                        isConnected = false
                    )
                }
                log("Connection failed — no DJI RC-N1/RC-N2/RC-N3 USB serial device detected")
            }
        }
    }

    private fun connectInternal(): Boolean {
        // 1) USB serial — phone cabled to RC-N1/RC-N2/RC-N3 (the primary path)
        val usb = UsbSerialTransport.open(app)
        if (usb != null) {
            if (usb.open()) {
                transport = usb
                update {
                    copy(
                        transportName = usb.name,
                        transportKind = "USB"
                    )
                }
                return true
            }
            usb.close()
        }
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

    // --- Internal helpers ---

    private fun isControllerReachable(): Boolean {
        if (_state.value.isConnected && transport != null) return true
        log("Connect to the controller first")
        return false
    }

    /**
     * Sends a profile's frames over the transport. The USB serial port stays
     * open for the whole sequence — each frame is written, then we wait for
     * the inter-frame delay so the controller has time to process it.
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
                val wrote = t.write(frame)
                if (wrote) {
                    anySuccess = true
                    // Read and discard any ACK so the buffer doesn't stall
                    try {
                        val ack = ByteArray(2048)
                        t.read(ack, ack.size, profile.readWindowMs)
                    } catch (_: Exception) {}
                }

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