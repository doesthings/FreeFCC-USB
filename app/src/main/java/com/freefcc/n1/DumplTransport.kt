package com.freefcc.n1

import android.content.Context
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException

/**
 * Sends DUMPL command frames to a DJI RC-N1/RC-N2/RC-N3 controller over USB.
 *
 * The N-series controllers ("dumb" controllers without a screen) connect to
 * your phone via USB and enumerate as CDC ACM serial devices at 19200 baud.
 * We open the serial port and write DUMPL frames as raw bytes — the controller
 * forwards them to the drone over the radio link.
 *
 * This is the same transport method used by M4TH1EU's DJI-FCC-HACK and the
 * paid NLD FCC app: a USB CDC ACM serial connection, not Android's USB
 * accessory mode and not the TCP loopback used by smart-controller tools.
 *
 * The library auto-detects CDC ACM devices by USB interface class (not
 * VID/PID), so this works with the RC-N1, RC-N2, and RC-N3 without needing
 * to know their specific product IDs.
 */
interface DumplTransport {

    /** Opens the transport. Returns true on success, false on any I/O failure. */
    fun open(): Boolean

    /**
     * Writes one frame and returns true if the bytes were accepted by the
     * carrier.
     */
    fun write(frame: ByteArray): Boolean

    /**
     * Reads up to [length] bytes into [buffer]. Returns the number of bytes
     * read, 0 on timeout, -1 on error/EOF. Never blocks longer than [timeoutMs].
     */
    fun read(buffer: ByteArray, length: Int, timeoutMs: Int): Int

    /** Releases the carrier. Safe to call multiple times. */
    fun close()

    /** Human-readable name for logging/UI. */
    val name: String
}

// ════════════════════════════════════════════════════════════════════════
// USB serial transport — for RC-N1 / RC-N2 / RC-N3 (phone cabled via USB)
// ════════════════════════════════════════════════════════════════════════

/**
 * USB CDC ACM serial transport for DJI RC-N1 / RC-N2 / RC-N3 controllers.
 *
 * The controller enumerates as a CDC ACM serial device at 19200 baud.
 * We open the serial port and write DUMPL frames as raw bytes.
 *
 * The RC-N1 has two USB ports:
 *  - **Bottom port** (Type-C): the service/diagnostic serial console —
 *    this is where the FCC patch is sent. The phone connects here, the
 *    app writes the DUMPL frames, then the phone moves to the top port.
 *  - **Top port**: used for normal flight with DJI Fly (MTP/accessory mode).
 *
 * This transport writes to whichever port the phone is currently cabled to.
 * The library auto-detects CDC ACM devices by interface class, so it works
 * with any N-series controller regardless of product ID.
 */
class UsbSerialTransport private constructor(
    private val serialPort: UsbSerialPort,
    override val name: String
) : DumplTransport {

    private var isOpen = false

    override fun open(): Boolean {
        return try {
            if (!isOpen) {
                serialPort.setParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE)
                isOpen = true
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun write(frame: ByteArray): Boolean {
        return try {
            serialPort.write(frame, 1000)
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun read(buffer: ByteArray, length: Int, timeoutMs: Int): Int {
        return try {
            serialPort.read(buffer, timeoutMs)
        } catch (_: Exception) {
            -1
        }
    }

    override fun close() {
        try {
            if (isOpen) {
                serialPort.close()
                isOpen = false
            }
        } catch (_: Exception) {}
    }

    companion object {

        /**
         * Attempts to find and open a connected DJI RC-N1/RC-N2/RC-N3 controller.
         * Returns null if no CDC ACM device is found or permission is missing.
         *
         * Uses the library's default prober, which auto-detects CDC ACM devices
         * by USB interface class (class 0x02, subclass 0x02). This works with
         * any N-series controller — no need to know the specific product ID.
         */
        fun open(context: Context): UsbSerialTransport? {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

            // The default prober auto-detects CDC ACM devices by interface class.
            // Since v3.5.0, the library detects CDC/ACM by USB interface types
            // instead of fixed VID+PID, so custom probers are not required.
            val defaultProber = UsbSerialProber.getDefaultProber()

            for (device in usbManager.deviceList.values) {
                if (!usbManager.hasPermission(device)) continue

                val driver = defaultProber.probeDevice(device) ?: continue
                if (driver !is CdcAcmSerialDriver) continue

                val port = driver.ports.firstOrNull() ?: continue
                return UsbSerialTransport(
                    port,
                    "USB Serial (VID=0x${device.vendorId.toString(16)}, PID=0x${device.productId.toString(16)})"
                )
            }

            return null
        }
    }
}