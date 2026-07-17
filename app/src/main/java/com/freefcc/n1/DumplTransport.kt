package com.freefcc.n1

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sends DUMPL command frames to a DJI RC-N1/RC-N2/RC-N3 controller over USB
 * Accessory mode (AOA) on the TOP USB port — matching the DJI controller's expected protocol.
 *
 * The connection flow replicates the DJI controller exactly:
 * 1. UsbManager.openAccessory() → ParcelFileDescriptor
 * 2. Start dedicated TX thread (LinkedBlockingQueue, 3ms inter-frame delay)
 * 3. Send bootstrap handshake (2 frames: CONN_BOOTSTRAP_3100 + CONN_BOOTSTRAP_0000)
 * 4. Send RCLink keepalive frames every 2.5s (keep the session alive)
 * 5. Send DUMPL frames wrapped in RCLink envelope: [55 CC 49 57 len_le32 DUMPL...]
 *
 * The RCLink envelope is critical — bare DUMPL frames (just 0x55...) are rejected
 * by the RC-N1's AOA parser. The envelope adds an 8-byte header with magic 0x55 0xCC
 * and route bytes 0x49 0x57 ("IW") before the DUMPL frame payload.
 */
interface DumplTransport {
    fun open(): Boolean
    fun write(frame: ByteArray): Boolean
    fun read(buffer: ByteArray, length: Int, timeoutMs: Int): Int
    fun close()
    val name: String
}

/**
 * RCLink envelope wrapper — wraps a bare DUMPL frame in the 8-byte header
 * that the RC-N1's AOA parser expects.
 *
 * Envelope format (matching the DJI controller's a0/v0.t() output):
 *   [0] 0x55  magic byte 1
 *   [1] 0xCC  magic byte 2 (RCLink header, not DUMPL 0x55-only)
 *   [2] 0x49  route byte 1 ('I' — default from r3/d.a)
 *   [3] 0x57  route byte 2 ('W' — default from r3/d.a)
 *   [4-7] payload length (uint32 LE) — length of the inner DUMPL frame
 *   [8..]  DUMPL frame bytes (starts with 0x55)
 */
fun wrapRclink(dumplFrame: ByteArray, route: ByteArray = byteArrayOf(0x49, 0x57)): ByteArray {
    val out = ByteArray(8 + dumplFrame.size)
    out[0] = 0x55
    out[1] = 0xCC.toByte()
    out[2] = route[0]
    out[3] = route[1]
    val len = dumplFrame.size
    out[4] = (len and 0xFF).toByte()
    out[5] = ((len shr 8) and 0xFF).toByte()
    out[6] = ((len shr 16) and 0xFF).toByte()
    out[7] = ((len shr 24) and 0xFF).toByte()
    System.arraycopy(dumplFrame, 0, out, 8, dumplFrame.size)
    return out
}

/**
 * USB Accessory mode transport for DJI RC-N1/RC-N2/RC-N3.
 *
 * Uses the TOP USB port where the RC presents as an AOA accessory
 * with manufacturer="DJI". The app calls openAccessory(), gets a
 * ParcelFileDescriptor, and wraps DUMPL frames in the RCLink envelope
 * before writing to the FileOutputStream.
 *
 * A dedicated TX thread (matching 's "-AOA-TX") drains a
 * LinkedBlockingQueue with 3ms inter-frame delay.
 *
 * The user MUST close DJI Fly before connecting — the AOA accessory
 * is exclusive (only one app can hold it at a time).
 */
class AccessoryTransport private constructor(
    private val inputStream: FileInputStream,
    private val outputStream: FileOutputStream,
    private val pfd: ParcelFileDescriptor,
    override val name: String
) : DumplTransport {

    private val queue = LinkedBlockingQueue<ByteArray>()
    private val running = AtomicBoolean(false)
    private var txThread: Thread? = null
    private var keepaliveThread: Thread? = null

    override fun open(): Boolean {
        if (running.compareAndSet(false, true)) {
            // Start TX thread (matches 's "-AOA-TX")
            txThread = Thread({
                while (running.get()) {
                    try {
                        val frame = queue.take() // blocks until a frame is available
                        if (frame.isEmpty()) continue
                        try {
                            outputStream.write(frame, 0, frame.size)
                            outputStream.flush()
                        } catch (_: IOException) {
                            running.set(false)
                            break
                        }
                        // 3ms inter-frame delay (matches )
                        try { Thread.sleep(3) } catch (_: InterruptedException) { break }
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }, "AOA-TX").apply { isDaemon = true; start() }

            // Start RCLink keepalive thread (matches 's keepalive coroutine)
            keepaliveThread = Thread({
                // Wait 2.5s before first keepalive (matches 's initialDelayMs)
                try { Thread.sleep(2500) } catch (_: InterruptedException) { return@Thread }
                while (running.get()) {
                    // Keepalive 1: cmd_set=6, cmd_id=0x77, dst=0x06, payload={01,01,00,FF,FF,20,00,00}
                    val keepalivePayload = byteArrayOf(0x01, 0x01, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0x20, 0x00, 0x00)
                    val ka1 = DumplBuilder().buildFrame(DumplFrame(0x02, 0x40, 0x06, 0x77, 0x06, keepalivePayload))
                    try { queue.put(wrapRclink(ka1)) } catch (_: InterruptedException) { break }

                    // Keepalive 2: same payload, dst=0x0E (1400)
                    val ka2 = DumplBuilder().buildFrame(DumplFrame(0x02, 0x40, 0x06, 0x77, 0x0E, keepalivePayload))
                    try { queue.put(wrapRclink(ka2)) } catch (_: InterruptedException) { break }

                    try { Thread.sleep(2500) } catch (_: InterruptedException) { break }
                }
            }, "AOA-Keepalive").apply { isDaemon = true; start() }
        }
        return true
    }

    /**
     * Enqueues a frame for writing. The frame should already be wrapped
     * in the RCLink envelope. The TX thread will write it with 3ms delay.
     */
    override fun write(frame: ByteArray): Boolean {
        if (!running.get()) return false
        return try {
            queue.put(frame.copyOf())
            true
        } catch (_: InterruptedException) {
            false
        }
    }

    override fun read(buffer: ByteArray, length: Int, timeoutMs: Int): Int {
        return try {
            val avail = inputStream.available()
            if (avail <= 0) return 0
            inputStream.read(buffer, 0, minOf(length, avail))
        } catch (_: IOException) {
            -1
        }
    }

    override fun close() {
        running.set(false)
        try { txThread?.interrupt() } catch (_: Exception) {}
        try { keepaliveThread?.interrupt() } catch (_: Exception) {}
        try { inputStream.close() } catch (_: IOException) {}
        try { outputStream.close() } catch (_: IOException) {}
        try { pfd.close() } catch (_: IOException) {}
    }

    companion object {

        private const val ACTION_USB_PERMISSION = "com.freefcc.n1.USB_PERMISSION"

        /**
         * Attempts to find and open a connected DJI RC-N1/RC-N2/RC-N3 controller
         * via USB Accessory mode on the TOP port. Returns null if no DJI
         * accessory is found.
         *
         * The user must close DJI Fly first — the AOA accessory is exclusive.
         */
        fun open(context: Context): AccessoryTransport? {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

            val accessories = usbManager.accessoryList
            if (accessories.isNullOrEmpty()) return null

            val accessory = accessories[0]

            if (accessory.manufacturer != "DJI") return null

            if (!usbManager.hasPermission(accessory)) {
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
