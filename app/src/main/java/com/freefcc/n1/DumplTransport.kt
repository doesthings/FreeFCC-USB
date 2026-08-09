package com.freefcc.n1

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
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
 * A dedicated TX thread drains a LinkedBlockingQueue with 3ms
 * inter-frame delay.
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
    private var rxThread: Thread? = null
    private var keepaliveThread: Thread? = null

    @Volatile
    private var lastRoute: ByteArray = byteArrayOf(0x49, 0x57)

    /** Optional listener for received DUML frames — used for diagnostics. */
    var rxListener: ((ByteArray) -> Unit)? = null

    override fun open(): Boolean {
        if (running.compareAndSet(false, true)) {
            // Start RX drain thread — continuously reads from the USB input
            // stream to prevent the pipe from filling up and stalling writes.
            // Also extracts route bytes from received RCLink envelopes so we
            // can echo them back (the controller may change route mid-session).
            rxThread = Thread({
                val buf = ByteArray(4096)
                while (running.get()) {
                    try {
                        val n = inputStream.read(buf)
                        if (n < 0) {
                            running.set(false)
                            break
                        }
                        if (n >= 4 && buf[0] == 0x55.toByte() && buf[1] == 0xCC.toByte()) {
                            lastRoute = byteArrayOf(buf[2], buf[3])
                        }
                        // Log received DUML frames for diagnostics
                        if (n >= 13) {
                            val rxLog = rxListener
                            if (rxLog != null) {
                                val frame = buf.copyOfRange(0, n)
                                rxLog(frame)
                            }
                        }
                    } catch (_: IOException) {
                        running.set(false)
                        break
                    }
                }
            }, "AOA-RX").apply { isDaemon = true; start() }

            // Start TX thread
            txThread = Thread({
                while (running.get()) {
                    try {
                        val frame = queue.take()
                        if (frame.isEmpty()) continue
                        try {
                            outputStream.write(frame, 0, frame.size)
                            outputStream.flush()
                        } catch (_: IOException) {
                            running.set(false)
                            break
                        }
                        try { Thread.sleep(3) } catch (_: InterruptedException) { break }
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }, "AOA-TX").apply { isDaemon = true; start() }

            // Start RCLink keepalive thread — uses the global sequence counter
            // so keepalive, bootstrap, and FCC frames never collide on seq numbers
            keepaliveThread = Thread({
                val builder = DumplBuilder()
                try { Thread.sleep(2500) } catch (_: InterruptedException) { return@Thread }
                while (running.get()) {
                    val keepalivePayload = byteArrayOf(0x01, 0x01, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0x20, 0x00, 0x00)
                    val route = lastRoute
                    val ka1 = builder.buildFrame(DumplFrame(0x02, 0x40, 0x06, 0x77, 0x06, keepalivePayload))
                    try { queue.put(wrapRclink(ka1, route)) } catch (_: InterruptedException) { break }

                    val ka2 = builder.buildFrame(DumplFrame(0x02, 0x40, 0x06, 0x77, 0x0E, keepalivePayload))
                    try { queue.put(wrapRclink(ka2, route)) } catch (_: InterruptedException) { break }

                    try { Thread.sleep(2500) } catch (_: InterruptedException) { break }
                }
            }, "AOA-Keepalive").apply { isDaemon = true; start() }
        }
        return true
    }

    /** Returns the last route bytes seen from the controller. */
    fun currentRoute(): ByteArray = lastRoute.copyOf()

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
        try { rxThread?.interrupt() } catch (_: Exception) {}
        try { txThread?.interrupt() } catch (_: Exception) {}
        try { keepaliveThread?.interrupt() } catch (_: Exception) {}
        try { inputStream.close() } catch (_: IOException) {}
        try { outputStream.close() } catch (_: IOException) {}
        try { pfd.close() } catch (_: IOException) {}
    }

    companion object {

        private const val ACTION_USB_PERMISSION = "com.freefcc.n1.USB_PERMISSION"

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

/**
 * USB VCOM (CDC-ACM bulk) transport for direct-to-drone connection.
 *
 * Used when DJI Fly resets FCC mode on startup — connect a second phone
 * directly to the drone's USB-C port and send FCC commands there while
 * DJI Fly stays connected via the RC. Raw DUML frames go directly to the
 * drone without RCLink wrapping.
 *
 * DJI drone USB: vendor 0x2CA3 (11427), products in AOA range 0x2D00-0x2D05.
 * Also detects any device with CDC-ACM or vendor-specific bulk endpoints.
 */
class VcomTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val epOut: UsbEndpoint,
    private val epIn: UsbEndpoint,
    override val name: String
) : DumplTransport {

    private val running = AtomicBoolean(false)
    private var rxThread: Thread? = null

    override fun open(): Boolean {
        if (running.compareAndSet(false, true)) {
            rxThread = Thread({
                val buf = ByteArray(512)
                while (running.get()) {
                    val n = connection.bulkTransfer(epIn, buf, buf.size, 1000)
                    if (n < 0 && !running.get()) break
                }
            }, "VCOM-RX").apply { isDaemon = true; start() }
        }
        return true
    }

    override fun write(frame: ByteArray): Boolean {
        if (!running.get()) return false
        val n = connection.bulkTransfer(epOut, frame, frame.size, 1000)
        return n == frame.size
    }

    override fun read(buffer: ByteArray, length: Int, timeoutMs: Int): Int {
        return connection.bulkTransfer(epIn, buffer, length, timeoutMs)
    }

    override fun close() {
        running.set(false)
        try { rxThread?.interrupt() } catch (_: Exception) {}
        try { connection.releaseInterface(usbInterface) } catch (_: Exception) {}
        try { connection.close() } catch (_: Exception) {}
    }

    companion object {
        private const val DJI_VENDOR_ID = 11427
        private const val ACTION_USB_PERMISSION = "com.freefcc.n1.USB_PERMISSION_VCOM"

        fun open(context: Context): VcomTransport? {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

            val device = findDjiDevice(usbManager) ?: return null

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

            val endpoints = findBulkEndpoints(device) ?: return null
            val conn = usbManager.openDevice(device) ?: return null

            if (!conn.claimInterface(endpoints.iface, true)) {
                conn.close()
                return null
            }

            // CDC-ACM line coding: 115200 baud, 8N1
            val cdcIface = findCdcControlInterface(device)
            if (cdcIface != null) {
                conn.claimInterface(cdcIface, true)
                val lineCoding = byteArrayOf(
                    0x00, 0xC2.toByte(), 0x01, 0x00, // 115200 baud LE
                    0x00, // 0 stop bits
                    0x00, // no parity
                    0x08  // 8 data bits
                )
                conn.controlTransfer(0x21, 0x20, 0, cdcIface.id, lineCoding, lineCoding.size, 1000)
                conn.controlTransfer(0x21, 0x22, 3, cdcIface.id, null, 0, 1000)
            }

            return VcomTransport(
                conn, endpoints.iface, endpoints.epOut, endpoints.epIn,
                "USB VCOM: ${device.vendorId}/${device.productId}"
            )
        }

        private fun findDjiDevice(usbManager: UsbManager): UsbDevice? {
            for (device in usbManager.deviceList.values) {
                if (device.vendorId == DJI_VENDOR_ID) return device
                if (findBulkEndpoints(device) != null) return device
            }
            return null
        }

        private data class Endpoints(val iface: UsbInterface, val epOut: UsbEndpoint, val epIn: UsbEndpoint)

        private fun findBulkEndpoints(device: UsbDevice): Endpoints? {
            var bestScore = 0
            var best: Endpoints? = null

            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                var epOut: UsbEndpoint? = null
                var epIn: UsbEndpoint? = null
                var score = 0

                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (ep.direction == UsbConstants.USB_DIR_OUT) epOut = ep
                    else epIn = ep
                }

                if (epOut == null || epIn == null) continue

                score += when (iface.interfaceClass) {
                    0xFF -> 120   // vendor-specific
                    0x0A -> 110   // CDC data
                    else -> 10
                }
                if (epOut.maxPacketSize >= 512 && epIn.maxPacketSize >= 512) score += 50
                if (iface.endpointCount == 2) score += 10

                if (score > bestScore) {
                    bestScore = score
                    best = Endpoints(iface, epOut, epIn)
                }
            }
            return best
        }

        private fun findCdcControlInterface(device: UsbDevice): UsbInterface? {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass == 2 && iface.interfaceSubclass == 2) return iface
            }
            return null
        }
    }
}
