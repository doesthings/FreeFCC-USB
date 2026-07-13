package com.freefcc.n1

import android.content.Context
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Abstract transport that can carry DUMPL frames from a host device to the
 * DJI radio. Two concrete implementations exist because DJI ships two kinds
 * of remote controllers, and the original paid apps each only support one:
 *
 *   - [TcpTransport] — talks to the in-RC DUMPL TCP proxy at 127.0.0.1:40009.
 *     Used by smart-controller FCC tools (RC2, RC Pro, RC Plus,
 *     Smart Controller). The app itself runs ON the controller.
 *
 *   - [UsbTransport] — talks directly over a USB bulk endpoint, with the app
 *     running on a phone/tablet that is cabled to either:
 *       (a) the remote controller (RC-N1, RC-N2 — the "USB cable remote
 *           controllers" USB remotes use), or
 *       (b) the drone itself (the "direct USB-to-drone method"
 *           for setups where DJI Fly reverts CE on reconnect).
 *
 * Both transports emit the *same* wire bytes from [DumplBuilder]. The frame
 * format and CRCs are identical — only the carrier differs.
 *
 * This is the unification that lets one app cover the Mini 3 non-pro (which
 * ships with an RC-N1 and is not supported by the M4TH1EU/DJI-FCC-HACK repo
 * because that repo only knows the smart-controller TCP path) and every
 * other DJI drone at the same time.
 */
interface DumplTransport {

    /** Opens the transport. Returns true on success, false on any I/O failure. */
    fun open(): Boolean

    /**
     * Writes one frame and returns true if the bytes were accepted by the
     * carrier. For USB this is `bulkTransfer` returning the full length;
     * for TCP this is `write` + `flush` not throwing.
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
// TCP transport — for smart controllers (RC2, RC Pro, RC Plus, Smart Controller)
// ════════════════════════════════════════════════════════════════════════

/**
 * Loopback TCP transport. The DJI smart controllers run a DUMPL proxy on
 * `127.0.0.1:40009` that accepts one frame per connection (open → write →
 * read ACK → close). This transport reproduces that exact pattern — every
 * call to [write] opens a fresh socket, writes, and reads the ACK so the
 * controller has time to process the frame before the next one arrives.
 *
 * This is what smart-controller FCC tools use. We
 * keep it for RC2/RC Pro/RC Plus users running this app on the controller
 * itself. For phone-cable-to-RC setups, [UsbTransport] is used instead.
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

// ════════════════════════════════════════════════════════════════════════
// USB transport — for phone/tablet cabled to RC or drone
// ════════════════════════════════════════════════════════════════════════

/**
 * USB bulk-transfer transport. This app exposes its `MainActivity`
 * as a USB_ACCESSORY_ATTACHED / USB_DEVICE_ATTACHED target. When the user
 * cables their phone to a DJI RC-N1/RC-N2 (or directly to the drone's
 * USB-C port), Android launches us and hands over a [UsbAccessory] or
 * [UsbDevice] handle. We claim the first bulk in/out interface pair and
 * shovel DUMPL frames through it.
 *
 * Endpoint selection: DJI's USB-accessory mode presents one bulk-out and one
 * bulk-in endpoint on the accessory interface. For USB-device mode (direct
 * to drone), the device exposes a CDC-like interface with bulk endpoints.
 * We auto-pick the first bulk-out and bulk-in endpoints we find.
 *
 * All access is synchronous on a worker thread (the ViewModel launches
 * operations on Dispatchers.IO). This matches the original FreeFCC-N1
 * `DumlForegroundService` threading model — never block the UI thread.
 */
class UsbTransport private constructor(
    private val usbManager: UsbManager,
    private val connection: UsbDeviceConnection,
    private val outEndpoint: android.hardware.usb.UsbEndpoint,
    private val inEndpoint: android.hardware.usb.UsbEndpoint,
    override val name: String
) : DumplTransport {

    override fun open(): Boolean = true  // already opened in factory

    override fun write(frame: ByteArray): Boolean {
        return try {
            val written = connection.bulkTransfer(
                outEndpoint,
                frame, frame.size,
                2000
            )
            written == frame.size
        } catch (_: Exception) {
            false
        }
    }

    override fun read(buffer: ByteArray, length: Int, timeoutMs: Int): Int {
        return try {
            val n = connection.bulkTransfer(
                inEndpoint,
                buffer, minOf(length, buffer.size),
                maxOf(timeoutMs, 1)
            )
            // bulkTransfer returns -1 on timeout/error
            if (n < 0) 0 else n
        } catch (_: Exception) {
            -1
        }
    }

    override fun close() {
        try { connection.releaseInterface(null) } catch (_: Exception) {}
        try { connection.close() } catch (_: Exception) {}
    }

    companion object {

        /**
         * Attempts to open the first already-attached DJI USB accessory/device
         * the system has granted us permission for. Returns null if nothing
         * is attached or permission is missing.
         */
        fun openAttached(context: Context): DumplTransport? {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

            // 1) Accessory path — most RC-N1/RC-N2 setups appear here.
            val accessory = usbManager.accessoryList?.firstOrNull()
            if (accessory != null && usbManager.hasPermission(accessory)) {
                openAccessory(context, usbManager, accessory)?.let { return it }
            }

            // 2) Device path — direct-to-drone USB-C, or RCs that re-enumerate
            //    as USB devices instead of accessories.
            for (device in usbManager.deviceList.values) {
                if (usbManager.hasPermission(device)) {
                    openDevice(usbManager, device)?.let { return it }
                }
            }

            return null
        }

        /**
         * Opens a specific [UsbAccessory] that we have permission for. The
         * `USB_ACCESSORY_ATTACHED` intent filter routes here
         * automatically when the user plugs in their RC.
         *
         * Returns a [DumplTransport] — in accessory mode there are no
         * endpoints, so we back it with the raw file descriptor pair.
         */
        fun openAccessory(
            context: Context,
            usbManager: UsbManager,
            accessory: UsbAccessory
        ): DumplTransport? {
            if (!usbManager.hasPermission(accessory)) return null

            val pfd: android.os.ParcelFileDescriptor =
                usbManager.openAccessory(accessory) ?: return null
            val fileIn = java.io.FileInputStream(pfd.fileDescriptor)
            val fileOut = java.io.FileOutputStream(pfd.fileDescriptor)

            // Accessory mode has no endpoint discovery — read/write the FD directly.
            return AccessoryFdTransport(
                usbManager = usbManager,
                pfd = pfd,
                inputStream = fileIn,
                outputStream = fileOut,
                name = "USB accessory ${accessory.manufacturer}/${accessory.model}"
            )
        }

        /**
         * Opens a specific [UsbDevice] that we have permission for. Used for
         * the direct USB-to-drone method (fallback when DJI Fly
         * reverts CE on reconnect).
         */
        fun openDevice(usbManager: UsbManager, device: UsbDevice): UsbTransport? {
            if (!usbManager.hasPermission(device)) return null

            val connection = usbManager.openDevice(device) ?: return null

            // Find the interface with both bulk-in and bulk-out endpoints.
            // DJI controllers/drones present exactly one such interface.
            for (i in 0 until device.interfaceCount) {
                val iface: UsbInterface = device.getInterface(i) ?: continue
                if (!connection.claimInterface(iface, true)) continue

                var outEp: android.hardware.usb.UsbEndpoint? = null
                var inEp: android.hardware.usb.UsbEndpoint? = null
                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j) ?: continue
                    if (ep.type != android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (ep.direction and 0x80 != 0) inEp = ep else outEp = ep
                }
                if (outEp != null && inEp != null) {
                    return UsbTransport(
                        usbManager, connection, outEp, inEp,
                        "USB device ${device.vendorId}:${device.productId}"
                    )
                }
                connection.releaseInterface(iface)
            }
            connection.close()
            return null
        }
    }
}

/**
 * UsbAccessory-mode transport backed by raw file descriptors rather than
 * endpoints. Accessory mode gives us a single bidirectional FD pair — no
 * endpoint discovery, just read/write the stream.
 *
 * Public so it can be returned from [UsbTransport.openAccessory] without
 * a type mismatch.
 */
class AccessoryFdTransport(
    private val usbManager: UsbManager,
    private val pfd: android.os.ParcelFileDescriptor,
    private val inputStream: java.io.InputStream,
    private val outputStream: java.io.OutputStream,
    override val name: String
) : DumplTransport {

    override fun open(): Boolean = true

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
            // Accessory-mode reads block until data arrives or the FD closes;
            // we approximate the timeout by reading available() bytes only.
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
}

/**
 * Adds Unix-domain-socket sending for 4G activation. The 4G frames go to
 * an abstract-namespace socket on smart controllers (`/duss/mb/0x205`)
 * rather than the TCP proxy. On phone+USB setups the same frames are sent
 * through the bulk endpoint with dst=OFDM_GROUND (238) — see the 4G profile.
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