package com.freefcc.n1

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Sends DUMPL command frames to a DJI RC-N1/RC-N2/RC-N3 controller over USB
 * **Accessory mode (AOA)**.
 *
 * The N-series controllers ("dumb" controllers without a screen) use the
 * Android Open Accessory protocol to communicate with the phone. The RC is
 * the USB **host** and the phone is the USB **accessory**. The phone calls
 * [UsbManager.openAccessory] which returns a [ParcelFileDescriptor], then
 * reads/writes raw DUMPL bytes via [FileInputStream]/[FileOutputStream].
 *
 * This is the same transport method used by the NLD FCC app: a raw byte
 * pipe over AOA, not CDC ACM serial. The RC-N1's bottom port presents as
 * an AOA accessory (manufacturer="DJI") when the RC is in host role.
 *
 * The CDC ACM serial path (used by M4TH1EU's DJI-FCC-HACK) only works on
 * older firmware. The Mini 3's firmware only accepts DUMPL commands over
 * the AOA path, which is why M4TH1EU fails on the Mini 3.
 */
interface DumplTransport {

    fun open(): Boolean
    fun write(frame: ByteArray): Boolean
    fun read(buffer: ByteArray, length: Int, timeoutMs: Int): Int
    fun close()
    val name: String
}

// ════════════════════════════════════════════════════════════════════════
// USB Accessory (AOA) transport — the NLD FCC method
// ════════════════════════════════════════════════════════════════════════

/**
 * USB Accessory mode transport for DJI RC-N1/RC-N2/RC-N3 controllers.
 *
 * The controller is the USB host; the phone is the accessory. We call
 * [UsbManager.openAccessory] to get a [ParcelFileDescriptor], then wrap
 * it in [FileInputStream] / [FileOutputStream] for raw byte I/O.
 *
 * No serial framing, no baud rate — just a raw byte pipe. The same DUMPL
 * frames (0x55 magic + header + payload + CRCs) go through as-is.
 *
 * The RC-N1's bottom USB-C port presents as an AOA accessory with
 * manufacturer="DJI". The phone connects to the bottom port, the app
 * opens the accessory, sends the DUMPL frames, then the phone moves to
 * the top port for normal flight with DJI Fly.
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
         * via USB Accessory mode. Returns null if no DJI accessory is found.
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