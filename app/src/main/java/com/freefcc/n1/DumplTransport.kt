package com.freefcc.n1

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.ParcelFileDescriptor
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Sends DUMPL command frames to a DJI RC-N1/RC-N2/RC-N3 controller over USB.
 *
 * The RC-N1 exposes **two** USB transports on **two** physical ports:
 *
 *  - **BOTTOM port (the service port)** — presents as a **CDC ACM serial**
 *    device with `VID=0x2CA3 / PID=0x1020`. This is the same interface
 *    M4TH1EU's DJI-FCC-HACK sends its patch over. DUMPL frames flow as raw
 *    serial bytes. Handled by [UsbSerialTransport] using usb-serial-for-android.
 *
 *  - **TOP port (the phone port)** — presents as an **AOA USB accessory**
 *    with `manufacturer="DJI"`. This is the pipe DJI Fly uses for flight.
 *    Handled by [AccessoryTransport] using the framework [UsbManager].
 *
 * Both transports carry identical DUMPL frames (0x55 magic + header +
 * payload + CRCs); the only difference is the wire format. The viewmodel
 * tries the BOTTOM port (CDC ACM) first — that's where DUMPL commands
 * reliably work — then falls back to the TOP port (AOA).
 */
interface DumplTransport {

    fun open(): Boolean
    fun write(frame: ByteArray): Boolean
    fun read(buffer: ByteArray, length: Int, timeoutMs: Int): Int
    fun close()
    val name: String
}

// ════════════════════════════════════════════════════════════════════════
// USB Serial (CDC ACM) transport — the BOTTOM port / M4TH1EU method
// ════════════════════════════════════════════════════════════════════════

/**
 * CDC ACM serial transport for the DJI RC-N1/RC-N2/RC-N3 **bottom** port.
 *
 * The bottom (service) USB-C port presents as a CDC ACM serial device with
 * `VID=0x2CA3 / PID=0x1020`. We use the usb-serial-for-android library's
 * [CdcAcmSerialDriver] to open the port, set 115200 8N1 (the DUMPL baud
 * rate — same one M4TH1EU's DJI-FCC-HACK uses), and read/write raw DUMPL
 * bytes. No AOA handshake, no accessory negotiation — just a serial pipe.
 *
 * This is the transport the user should try **first**: it's the same path
 * M4TH1EU uses and is where DUMPL commands are most reliably accepted.
 */
class UsbSerialTransport private constructor(
    private val port: UsbSerialPort,
    private val connection: UsbDeviceConnection,
    override val name: String
) : DumplTransport {

    override fun open(): Boolean = true  // already opened in factory

    override fun write(frame: ByteArray): Boolean {
        return try {
            port.write(frame, WRITE_TIMEOUT_MS)
            true
        } catch (_: IOException) {
            false
        }
    }

    override fun read(buffer: ByteArray, length: Int, timeoutMs: Int): Int {
        return try {
            // usb-serial-for-android's read() blocks up to timeoutMs, then
            // returns however many bytes actually arrived (0 if none).
            port.read(buffer, timeoutMs)
        } catch (_: IOException) {
            -1
        }
    }

    override fun close() {
        try { port.close() } catch (_: IOException) {}
        try { connection.close() } catch (_: IOException) {}
    }

    companion object {

        private const val ACTION_USB_PERMISSION = "com.freefcc.n1.USB_PERMISSION"
        private const val WRITE_TIMEOUT_MS = 500

        // RC-N1/RC-N2/RC-N3 bottom (service) port enumerates as CDC ACM:
        private const val DJI_VID = 0x2CA3   // 11427
        private const val DJI_PID = 0x1020   // 4128

        // DUMPL runs at 115200 8N1 — matches M4TH1EU's DJI-FCC-HACK.
        private const val BAUD_RATE = 115200

        /**
         * Attempts to find and open the DJI RC-N1/RC-N2/RC-N3 bottom port
         * as a CDC ACM serial device. Returns null if no matching device is
         * attached or permission is missing (in which case it's requested
         * and the caller should retry after the permission broadcast).
         */
        fun open(context: Context): UsbSerialTransport? {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

            // Find the DJI CDC ACM device by VID/PID. The default prober maps
            // generic CDC ACM devices to CdcAcmSerialDriver automatically, but
            // we want to target the DJI VID/PID specifically (not just any
            // CDC ACM device the user happens to have plugged in).
            val device: UsbDevice = usbManager.deviceList.values.firstOrNull {
                it.vendorId == DJI_VID && it.productId == DJI_PID
            } ?: return null

            // Check / request permission.
            if (!usbManager.hasPermission(device)) {
                val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    PendingIntent.FLAG_MUTABLE else 0
                val pi = PendingIntent.getBroadcast(
                    context, 0,
                    Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                    flag
                )
                usbManager.requestPermission(device, pi)
                return null
            }

            val connection = usbManager.openDevice(device) ?: return null

            // Use the library's CdcAcmSerialDriver directly with the matched
            // device — it knows how to claim the CDC ACM interface and endpoints.
            val driver: UsbSerialDriver = CdcAcmSerialDriver(device)
            val port: UsbSerialPort = driver.ports.firstOrNull() ?: run {
                connection.close()
                return null
            }

            try {
                port.open(connection)
                port.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            } catch (_: IOException) {
                try { port.close() } catch (_: IOException) {}
                try { connection.close() } catch (_: IOException) {}
                return null
            }

            return UsbSerialTransport(
                port,
                connection,
                "USB Serial (CDC ACM): ${device.deviceName}"
            )
        }
    }
}

// ════════════════════════════════════════════════════════════════════════
// USB Accessory (AOA) transport — the TOP port / NLD FCC method
// ════════════════════════════════════════════════════════════════════════

/**
 * USB Accessory mode transport for DJI RC-N1/RC-N2/RC-N3 controllers
 * (**top** port).
 *
 * The controller is the USB host; the phone is the accessory. We call
 * [UsbManager.openAccessory] to get a [ParcelFileDescriptor], then wrap
 * it in [FileInputStream] / [FileOutputStream] for raw byte I/O.
 *
 * No serial framing, no baud rate — just a raw byte pipe. The same DUMPL
 * frames (0x55 magic + header + payload + CRCs) go through as-is.
 *
 * The RC-N1's top USB-C port presents as an AOA accessory with
 * manufacturer="DJI". This is the pipe DJI Fly uses for normal flight.
 * DUMPL writes over this port work on some firmware and fail on others,
 * so it's the **fallback** after the CDC ACM bottom port.
 */
class AccessoryTransport private constructor(
    private val inputStream: FileInputStream,
    private val outputStream: FileOutputStream,
    private val pfd: ParcelFileDescriptor,
    override val name: String
) : DumplTransport {

    override fun open(): Boolean = true  // already opened in factory

    override fun write(frame: ByteArray): Boolean {
        return try {
            outputStream.write(frame)
            outputStream.flush()
            true
        } catch (_: IOException) {
            false
        }
    }

    override fun read(buffer: ByteArray, length: Int, timeoutMs: Int): Int {
        return try {
            // Accessory-mode reads block until data arrives or the FD closes.
            // We approximate the timeout by reading available() bytes only.
            val avail = inputStream.available()
            if (avail <= 0) return 0
            inputStream.read(buffer, 0, minOf(length, avail))
        } catch (_: IOException) {
            -1
        }
    }

    override fun close() {
        try { inputStream.close() } catch (_: IOException) {}
        try { outputStream.close() } catch (_: IOException) {}
        try { pfd.close() } catch (_: IOException) {}
    }

    companion object {

        private const val ACTION_USB_PERMISSION = "com.freefcc.n1.USB_PERMISSION"

        /**
         * Attempts to find and open a connected DJI RC-N1/RC-N2/RC-N3 controller
         * via USB Accessory mode (top port). Returns null if no DJI accessory
         * is found.
         *
         * The caller must have USB permission — if not, this will request it
         * via a PendingIntent and return null (the caller should retry after
         * the permission broadcast is received).
         */
        fun open(context: Context): AccessoryTransport? {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

            // Get the list of connected accessories (Android supports at most 1)
            val accessories = usbManager.accessoryList
            if (accessories.isNullOrEmpty()) return null

            val accessory = accessories[0]

            // DJI's runtime check: manufacturer must be "DJI"
            if (accessory.manufacturer != "DJI") return null

            // Check permission
            if (!usbManager.hasPermission(accessory)) {
                // Request permission — the caller should retry after the broadcast
                val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    PendingIntent.FLAG_MUTABLE else 0
                val pi = PendingIntent.getBroadcast(
                    context, 0,
                    Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                    flag
                )
                usbManager.requestPermission(accessory, pi)
                return null
            }

            // Open the accessory — returns a ParcelFileDescriptor
            val pfd = usbManager.openAccessory(accessory) ?: return null

            val inputStream = FileInputStream(pfd.fileDescriptor)
            val outputStream = FileOutputStream(pfd.fileDescriptor)

            return AccessoryTransport(
                inputStream,
                outputStream,
                pfd,
                "USB Accessory: ${accessory.manufacturer}/${accessory.model}"
            )
        }
    }
}