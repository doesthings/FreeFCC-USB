package com.freefcc.n1

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Loads DUMPL command profiles from JSON files in `app/src/main/assets/profiles/`.
 */
object ProfileLoader {

    /**
     * Global sequence counter shared across all frame builders — bootstrap,
     * keepalive, FCC, and CE restore all draw from this so no two frames
     * in the same session ever carry the same sequence number.
     */
    val globalSeq = AtomicInteger(149)

    data class FrameDef(
        val cmdSet: Int,
        val cmdId: Int,
        val dst: Int,
        val payload: ByteArray
    )

    data class Profile(
        val sender: Int,
        val cmdType: Int,
        val rounds: Int,
        val interFrameDelay: Long,
        val interRoundDelay: Long,
        val readWindowMs: Int,
        val frameDefs: List<FrameDef>
    )

    fun load(context: Context, fileName: String, senderOverride: Int? = null): Profile {
        val json = readAsset(context, "profiles/$fileName")
        val obj = JSONObject(json)

        val profileSender = obj.getInt("sender")
        val sender = senderOverride ?: profileSender
        val cmdType = obj.getInt("cmd_type")
        val rounds = obj.getInt("rounds")
        val interFrame = obj.optLong("inter_frame_delay_ms", 0)
        val interRound = obj.optLong("inter_round_delay_ms", 0)
        val readWindow = obj.optInt("read_window_ms", 80)

        val framesArray = obj.getJSONArray("frames")
        val defs = (0 until framesArray.length()).map { i ->
            val f = framesArray.getJSONObject(i)
            FrameDef(
                cmdSet = f.getInt("s"),
                cmdId = f.getInt("i"),
                dst = f.getInt("d"),
                payload = hexToBytes(f.optString("p", ""))
            )
        }

        return Profile(sender, cmdType, rounds, interFrame, interRound, readWindow, defs)
    }

    /** Builds a wire-ready DUML frame from a definition, using the global sequence counter. */
    fun buildFrame(def: FrameDef, sender: Int, cmdType: Int): ByteArray {
        return DumplBuilder.buildFrameWithSeq(
            DumplFrame(sender, cmdType, def.cmdSet, def.cmdId, def.dst, def.payload),
            globalSeq.getAndIncrement() and 0xFFFF
        )
    }

    private fun readAsset(context: Context, path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "").replace("\n", "")
        if (clean.isEmpty()) return ByteArray(0)
        require(clean.length % 2 == 0) { "Odd-length hex string" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
