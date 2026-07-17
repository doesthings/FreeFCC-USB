package com.freefcc.n1

import android.content.Context
import android.hardware.usb.UsbManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Sends DUMPL command frames to the DJI controller.
 *
 * The RC-N1 / RC-N2 controllers connect to your phone via USB and enumerate
 * as a CDC ACM serial device (vendor 0x2C93, product 0x1020). We open the
 * serial port at 19200 baud and write the DUMPL frames as raw bytes — the
 * controller forwards them to the drone over the radio link.
 *
 * This is the same transport method used by M4TH1EU's DJI-FCC-HACK and the
 * paid NLD FCC app: a simple USB serial connection, not Android's USB
 * accessory mode and not the TCP loopback used by smart-controller tools.
 */
interface DumplTransport {

    /** Opens the transport. Returns true on success, false on any I/O failure. */
    fun open(): Boolean

    /**
     * Writes one frame and returns true if the bytes were accepted by the
     * carrier. For USB serial this is `serialPort.write(frame)` returning
     * the full length; for TCP this is `write` + `flush` not throwing.
     */
    fun write(frame: ByteArray): Boolean

    /**
     * Reads up to [length] bytes into [buffer]. Returns the number of bytes
     * read, 0 on timeout, -1 on error/EOF. Never blocks longer than [timeoutMs].
     * Used for ACKs and the aircraft-serial probe.
     */
    fun read(buffer: ByteArray, length: Int, timeoutMs: Int): Int

    /** Releases the carrier. Safe to call multiple times. */
    fun close()

    /** Human-readable name for logging/UI. */
    val name: String
}

// ════════════════════════════════════════════════════════════════════════
// USB serial transport — for RC-N1 / RC-N2 (phone cabled via USB)
// ════════════════════════════════════════════════════════════════════════

/**
 * USB CDC ACM serial transport for DJI RC-N1 / RC-N2 controllers.
 *
 * The RC-N1 enumerates as a CDC ACM serial device at 19200 baud.
 * We open the serial port and write DUMPL frames as raw bytes.
 *
 * The RC-N1 has two USB ports:
 *  - **Bottom port** (Type-C): used for the FCC patch — the phone connects
 *    here to send the DUMPL commands, then moves to the top port.
 *  - **Top port**: used for normal flight with DJI Fly.
 *
 * This transport writes to whichever port the phone is currently cabled to.
 */
class UsbSerialTransport private constructor(
    private val serialPort: UsbSerialPort,
    override val name: String
) : DumplTransport {

    private var isOpen = false

    override fun open(): Boolean {
        return try {
            if (!isOpen) {
                serialPort.open(
                    serialPort.driver.device.let { device ->
                        // The connection is managed by the driver; we just need
                        // to set the serial parameters.
                        null  // The driver handles the USB connection internally
                    }
                )
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
            val read = serialPort.read(buffer, timeoutMs)
            read
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

        /** DJI USB vendor ID (0x2CA3 = 11427 decimal). All DJI controllers use this VID. */
        const val DJI_VENDOR_ID = 0x2CA3

        /** DJI RC-N1 USB product ID (0x1020 = 4128 decimal). */
        const val DJI_RC_N1_PRODUCT_ID = 0x1020

        /**
         * Attempts to find and open a connected DJI RC-N1/RC-N2/RC-N3 controller.
         * Returns null if no matching device is found or permission is missing.
         *
         * The caller must have USB permission — request it via
         * [UsbManager.requestPermission] before calling this method.
         *
         * We match by DJI vendor ID (0x2CA3) so this works with any N-series
         * "dumb" controller (RC-N1, RC-N2, RC-N3) regardless of product ID.
         */
        fun open(context: Context): UsbSerialTransport? {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

            // Build a probe table matching any DJI CDC ACM device by vendor ID.
            // The RC-N1 has PID 0x1020, the RC-N2 and RC-N3 have different PIDs
            // but the same vendor ID 0x2CA3 and the same CDC ACM serial interface.
            val probeTable = ProbeTable().apply {
                addProduct(DJI_VENDOR_ID, DJI_RC_N1_PRODUCT_ID, CdcAcmSerialDriver::class.java)
            }

            val prober = UsbSerialProber(probeTable)

            // Find the first matching DJI device by vendor ID
            for (device in usbManager.deviceList.values) {
                // Match any DJI device (by vendor ID 0x2CA3), not just the RC-N1 product ID.
                // This covers RC-N1, RC-N2, RC-N3, and any future N-series controller.
                if (device.vendorId == DJI_VENDOR_ID) {
                    if (!usbManager.hasPermission(device)) continue

                    // Try the exact probe table match first (RC-N1 PID 0x1020)
                    val driver = prober.probeDevice(device)
                        ?: UsbSerialProber.getDefaultProber().probeDevice(device)
                        ?: continue

                    val port = driver.ports.firstOrNull() ?: continue
                    return UsbSerialTransport(
                        port,
                        "USB Serial ${device.deviceName} (VID=0x${device.vendorId.toString(16)}, PID=0x${device.productId.toString(16)})"
                    )
                }
            }

            // Fallback: try the default prober for any CDC ACM device
            // (covers cases where the VID is different or not yet known)
            val defaultProber = UsbSerialProber.getDefaultProber()
            for (device in usbManager.deviceList.values) {
                if (!usbManager.hasPermission(device)) continue
                val driver = defaultProber.probeDevice(device) ?: continue
                if (driver !is CdcAcmSerialDriver) continue
                val port = driver.ports.firstOrNull() ?: continue
                return UsbSerialTransport(
                    port,
                    "USB Serial ${device.deviceName} (VID=0x${device.vendorId.toString(16)}, PID=0x${device.productId.toString(16)})"
                )
            }

            return null
        }
    }
}

// ════════════════════════════════════════════════════════════════════════
// TCP transport — fallback for smart controllers (RC2, RC Pro, RC Plus)
// ════════════════════════════════════════════════════════════════════════

/**
 * Loopback TCP transport for smart controllers. The DJI smart controllers
 * run a DUMPL proxy on `127.0.0.1:40009` that accepts one frame per
 * connection. This is kept as a fallback — for RC-N1/RC-N2/RC-N3 use the USB
 * serial transport instead.
 */
class TcpTransport(
    private val host: String = "127.0.0.1",
    private val port: Int = 40009
) : DumplTransport {

    override val name: String = "TCP $host:$port"

    private var socket: Socket? = null

    override fun open(): Boolean {
        return try {
            val s = Socket()
            s.tcpNoDelay = true
            s.soTimeout = 1000
            s.connect(InetSocketAddress(host, port), 2000)
            socket = s
            true
        } catch (_: IOException) {
            false
        }
    }

    override fun write(frame: ByteArray): Boolean {
        val s = socket ?: return false
        return try {
            s.getOutputStream().apply { write(frame); flush() }
            true
        } catch (_: IOException) {
            false
        }
    }

    override fun read(buffer: ByteArray, length: Int, timeoutMs: Int): Int {
        val s = socket ?: return -1
        return try {
            s.soTimeout = maxOf(timeoutMs, 1)
            s.getInputStream().read(buffer, 0, minOf(length, buffer.size))
        } catch (_: IOException) {
            0
        }
    }

    override fun close() {
        try { socket?.close() } catch (_: IOException) {}
        socket = null
    }
}

/**
 * Sends a frame via Unix domain socket for 4G activation on smart controllers.
 * On USB serial setups, 4G frames go through the same serial port.
 */
fun sendUnix4gFrame(frame: ByteArray, socketName: String = "/duss/mb/0x205"): Boolean {
    var socket: LocalSocket? = null
    return try {
        socket = LocalSocket()
        socket.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
        socket.soTimeout = 500
        socket.getOutputStream().apply { write(frame); flush() }
        true
    } catch (_: IOException) {
        false
    } finally {
        try { socket?.close() } catch (_: IOException) {}
    }
}