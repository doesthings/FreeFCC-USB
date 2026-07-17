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
 * Every byte that goes on the wire is visible in a text file you can open
 * in any editor.
 *
 * Why no server: the FCC profile is a fixed 21-frame DUMPL command sequence
 * that works across DJI's modern controller fleet. There is nothing
 * per-device or session-specific about FCC mode — it's a fixed DUMPL command
 * sequence. So we ship it as a JSON asset and never talk to a server.
 */
object ProfileLoader {

    data class Profile(
        val sender: Int,
        val cmdType: Int,
        val rounds: Int,
        val interFrameDelay: Long,
        val interRoundDelay: Long,
        val readWindowMs: Int,
        val frames: List<ByteArray>
    )

    /** Loads a profile (FCC or CE restore) from a JSON asset. */
    fun load(context: Context, fileName: String): Profile {
        val json = readAsset(context, "profiles/$fileName")
        val obj = JSONObject(json)

        val sender = obj.getInt("sender")
        val cmdType = obj.getInt("cmd_type")
        val rounds = obj.getInt("rounds")
        val interFrame = obj.optLong("inter_frame_delay_ms", 0)
        val interRound = obj.optLong("inter_round_delay_ms", 0)
        val readWindow = obj.optInt("read_window_ms", 80)

        val framesArray = obj.getJSONArray("frames")
        val builder = DumplBuilder()
        val frames = (0 until framesArray.length()).map { i ->
            val f = framesArray.getJSONObject(i)
            builder.buildFrame(
                DumplFrame(
                    sender = sender,
                    cmdType = cmdType,
                    cmdSet = f.getInt("s"),
                    cmdId = f.getInt("i"),
                    dst = f.getInt("d"),
                    payload = hexToBytes(f.optString("p", ""))
                )
            )
        }

        return Profile(sender, cmdType, rounds, interFrame, interRound, readWindow, frames)
    }

    private fun readAsset(context: Context, path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "").replace("\n", "")
        if (clean.isEmpty()) return ByteArray(0)
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}