package com.freefcc.n1

import android.content.Context
import org.json.JSONObject

/**
 * Loads DUMPL command profiles from JSON files in `app/src/main/assets/profiles/`.
 *
 * Profiles are plain JSON — no encryption, no obfuscation, no server fetch.
 * Each file defines a sequence of DUMPL frames with timing parameters.
 * The app reads them at runtime, builds wire-format frames via [DumplBuilder],
 * and sends the raw bytes to the controller.
 *
 * This is the same design as FreeFCC's `Profiles.kt` — every byte that goes
 * on the wire is visible in a text file you can open in any editor. The only
 * difference here is the addition of the Remote ID profiles and the explicit
 * `port` / `wrapper` fields for the LED frames.
 *
 * Why no server: the FCC profile is a fixed 21-frame DUMPL command sequence
 * that works across DJI's modern controller fleet (verified across 11 model
 * codes). There is nothing per-device or session-specific about FCC mode —
 * it's a fixed DUMPL command sequence. So we ship it as a JSON asset and
 * never talk to a server.
 */
object ProfileLoader {

    /**
     * A loaded command profile.
     *
     * @property sender        Sender byte (type + index)
     * @property cmdType       Command type byte (packet_type + ack_type + encrypt_type)
     * @property rounds        How many times to repeat the full frame sequence
     * @property interFrameDelay  Delay between frames within a round (ms)
     * @property interRoundDelay  Delay between rounds (ms)
     * @property readWindowMs  How long to wait for an ACK after each write
     * @property needsResponse  True if this command expects a response payload (device info)
     * @property port          TCP port on smart controllers (40009 default, 40007 for LED)
     * @property frames        List of wire-ready frames (built from the JSON definition)
     */
    data class Profile(
        val sender: Int,
        val cmdType: Int,
        val rounds: Int,
        val interFrameDelay: Long,
        val interRoundDelay: Long,
        val readWindowMs: Int,
        val needsResponse: Boolean,
        val port: Int,
        val frames: List<ByteArray>
    )

    /** Loads a static profile (FCC, CE restore, LED, device info, Remote ID) from a JSON asset. */
    fun load(context: Context, fileName: String): Profile {
        val json = readAsset(context, "profiles/$fileName")
        val obj = JSONObject(json)

        val sender = obj.getInt("sender")
        val cmdType = obj.getInt("cmd_type")
        val rounds = obj.getInt("rounds")
        val interFrame = obj.optLong("inter_frame_delay_ms", 0)
        val interRound = obj.optLong("inter_round_delay_ms", 0)
        val readWindow = obj.optInt("read_window_ms", 80)
        val needsResponse = obj.optBoolean("needs_response", false)
        val port = obj.optInt("port", 40009)
        val useWrapper = obj.optBoolean("wrapper", false)

        val framesArray = obj.getJSONArray("frames")
        val builder = DumplBuilder()
        val frames = (0 until framesArray.length()).map { i ->
            val f = framesArray.getJSONObject(i)
            val inner = builder.buildFrame(
                DumplFrame(
                    sender = sender,
                    cmdType = cmdType,
                    cmdSet = f.getInt("s"),
                    cmdId = f.getInt("i"),
                    dst = f.getInt("d"),
                    payload = hexToBytes(f.optString("p", ""))
                )
            )
            if (useWrapper) wrapFrame(inner) else inner
        }

        return Profile(
            sender = sender,
            cmdType = cmdType,
            rounds = rounds,
            interFrameDelay = interFrame,
            interRoundDelay = interRound,
            readWindowMs = readWindow,
            needsResponse = needsResponse,
            port = port,
            frames = frames
        )
    }

    /**
     * Builds the 4G activation profile at runtime. Unlike static profiles,
     * 4G frames include the aircraft serial number in each payload, so they
     * can only be generated once we know the serial.
     */
    fun load4g(context: Context, aircraftSerial: String): Profile {
        val json = readAsset(context, "profiles/4g.json")
        val obj = JSONObject(json)

        val sender = obj.getInt("sender")
        val cmdType = obj.getInt("cmd_type")
        val rounds = obj.getInt("rounds")
        val interFrame = obj.optLong("inter_frame_delay_ms", 10)
        val interRound = obj.optLong("inter_round_delay_ms", 0)
        val readWindow = obj.optInt("read_window_ms", 80)

        val frameCount = obj.getInt("frame_count")
        val cmdSet = obj.getInt("cmd_set")
        val cmdIdStart = obj.getInt("cmd_id_start")
        val dst = obj.getInt("dst")
        val prefix = hexToBytes(obj.getString("payload_prefix_hex"))

        // Build the payload: prefix + ASCII serial
        val serialBytes = aircraftSerial.toByteArray(Charsets.US_ASCII)
        val payload = ByteArray(prefix.size + serialBytes.size)
        System.arraycopy(prefix, 0, payload, 0, prefix.size)
        System.arraycopy(serialBytes, 0, payload, prefix.size, serialBytes.size)

        // Generate all 128 frames — one per cmd_id
        val builder = DumplBuilder()
        val frames = (0 until frameCount).map { i ->
            builder.buildFrame(
                DumplFrame(
                    sender = sender,
                    cmdType = cmdType,
                    cmdSet = cmdSet,
                    cmdId = cmdIdStart + i,
                    dst = dst,
                    payload = payload
                )
            )
        }

        return Profile(
            sender = sender,
            cmdType = cmdType,
            rounds = rounds,
            interFrameDelay = interFrame,
            interRoundDelay = interRound,
            readWindowMs = readWindow,
            needsResponse = false,
            port = 40009,
            frames = frames
        )
    }

    // --- Helpers ---

    private fun readAsset(context: Context, path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }

    /** Decodes a hex string (no spaces) into a ByteArray. Empty string → empty array. */
    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "").replace("\n", "")
        if (clean.isEmpty()) return ByteArray(0)
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /**
     * Wraps an inner DUMPL frame with the 8-byte outer header used by LED
     * control on smart controllers (port 40007).
     *
     * Format: [0x55][0xCC][0x30][0x75][4-byte LE length][inner frame]
     */
    private fun wrapFrame(inner: ByteArray): ByteArray {
        val out = ByteArray(8 + inner.size)
        out[0] = 0x55
        out[1] = 0xCC.toByte()
        out[2] = 0x30
        out[3] = 0x75
        val len = inner.size
        out[4] = (len and 0xFF).toByte()
        out[5] = ((len shr 8) and 0xFF).toByte()
        out[6] = ((len shr 16) and 0xFF).toByte()
        out[7] = ((len shr 24) and 0xFF).toByte()
        System.arraycopy(inner, 0, out, 8, inner.size)
        return out
    }
}