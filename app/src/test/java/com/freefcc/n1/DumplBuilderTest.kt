package com.freefcc.n1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Wire-format tests for the DUML frame builder and the shipped FCC profile.
 *
 * The production CRCs are table-driven. These tests recompute them bitwise
 * straight from the polynomials, so a corrupted or mistyped table entry shows
 * up as a mismatch rather than as frames the controller silently drops.
 */
class DumplBuilderTest {

    // ── Independent, from-spec CRC implementations ──────────────────────────

    /** CRC-8, polynomial 0x8C (reflected 0x31), init 0x77. */
    private fun crc8Bitwise(data: ByteArray, from: Int, to: Int): Int {
        var crc = 0x77
        for (i in from until to) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x01 != 0) (crc shr 1) xor 0x8C else crc shr 1
            }
        }
        return crc and 0xFF
    }

    /** CRC-16, polynomial 0x8408 (reflected 0x1021), init 0x3692. */
    private fun crc16Bitwise(data: ByteArray, from: Int, to: Int): Int {
        var crc = 0x3692
        for (i in from until to) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x0001 != 0) (crc shr 1) xor 0x8408 else crc shr 1
            }
        }
        return crc and 0xFFFF
    }

    // ── Frame structure ────────────────────────────────────────────────────

    private fun buildAndCheck(
        sender: Int, cmdType: Int, cmdSet: Int, cmdId: Int, dst: Int,
        payload: ByteArray, seq: Int
    ): ByteArray {
        val f = DumplBuilder.buildFrameWithSeq(
            DumplFrame(sender, cmdType, cmdSet, cmdId, dst, payload), seq
        )

        val total = payload.size + 13
        assertEquals("frame length", total, f.size)
        assertEquals("magic", 0x55, f[0].toInt() and 0xFF)

        // Length in bits 0-9, version 1 in the high bits.
        val encodedLen = (f[1].toInt() and 0xFF) or ((f[2].toInt() and 0x03) shl 8)
        assertEquals("encoded length", total, encodedLen)
        assertEquals("version bits", 0x04, f[2].toInt() and 0xFC)

        // Header CRC-8 over bytes 0..2, recomputed from the polynomial.
        assertEquals("crc8", crc8Bitwise(f, 0, 3), f[3].toInt() and 0xFF)

        assertEquals("sender", sender and 0xFF, f[4].toInt() and 0xFF)
        assertEquals("dst", dst and 0xFF, f[5].toInt() and 0xFF)
        assertEquals("seq lo", seq and 0xFF, f[6].toInt() and 0xFF)
        assertEquals("seq hi", (seq shr 8) and 0xFF, f[7].toInt() and 0xFF)
        assertEquals("cmdType", cmdType and 0xFF, f[8].toInt() and 0xFF)
        assertEquals("cmdSet", cmdSet and 0xFF, f[9].toInt() and 0xFF)
        assertEquals("cmdId", cmdId and 0xFF, f[10].toInt() and 0xFF)

        for (i in payload.indices) {
            assertEquals("payload[$i]", payload[i], f[11 + i])
        }

        // Body CRC-16 over everything but the trailing two bytes.
        val expected = crc16Bitwise(f, 0, 11 + payload.size)
        val stored = (f[total - 2].toInt() and 0xFF) or ((f[total - 1].toInt() and 0xFF) shl 8)
        assertEquals("crc16", expected, stored)

        return f
    }

    @Test
    fun `empty payload frame is well formed`() {
        buildAndCheck(0xA2, 0x40, 0x51, 0x04, 0xEE, ByteArray(0), 149)
    }

    @Test
    fun `bootstrap frame is well formed`() {
        buildAndCheck(0x02, 0x40, 0x00, 0x00, 0x1F, byteArrayOf(0, 0, 1), 4096)
    }

    @Test
    fun `crc tables match the polynomials across the byte range`() {
        // Every payload length and byte value exercised through the real builder.
        for (len in 0..64) {
            val payload = ByteArray(len) { ((it * 7 + len * 13) and 0xFF).toByte() }
            buildAndCheck(0x82, 0x20, 0x03, 0xF9, 0x03, payload, (len * 37) and 0xFFFF)
        }
    }

    @Test
    fun `sequence number wraps within two bytes`() {
        buildAndCheck(0x02, 0x40, 0x06, 0x77, 0x06, ByteArray(4), 0xFFFF)
        buildAndCheck(0x02, 0x40, 0x06, 0x77, 0x06, ByteArray(4), 0)
    }

    // ── RCLink envelope ────────────────────────────────────────────────────

    @Test
    fun `rclink envelope carries header route and little endian length`() {
        val inner = DumplBuilder.buildFrameWithSeq(
            DumplFrame(0x82, 0x20, 0x06, 0x72, 0x06, ByteArray(7)), 200
        )
        val wrapped = wrapRclink(inner, byteArrayOf(0x49, 0x57))

        assertEquals(8 + inner.size, wrapped.size)
        assertEquals(0x55, wrapped[0].toInt() and 0xFF)
        assertEquals(0xCC, wrapped[1].toInt() and 0xFF)
        assertEquals(0x49, wrapped[2].toInt() and 0xFF)
        assertEquals(0x57, wrapped[3].toInt() and 0xFF)

        val len = (wrapped[4].toInt() and 0xFF) or
            ((wrapped[5].toInt() and 0xFF) shl 8) or
            ((wrapped[6].toInt() and 0xFF) shl 16) or
            ((wrapped[7].toInt() and 0xFF) shl 24)
        assertEquals(inner.size, len)

        for (i in inner.indices) assertEquals(inner[i], wrapped[8 + i])
    }

    @Test
    fun `rclink envelope honours a route the controller changed`() {
        val inner = DumplBuilder.buildFrameWithSeq(DumplFrame(0x02, 0x40, 0, 0, 0x1F, ByteArray(3)), 1)
        val wrapped = wrapRclink(inner, byteArrayOf(0x11, 0x22))
        assertEquals(0x11, wrapped[2].toInt() and 0xFF)
        assertEquals(0x22, wrapped[3].toInt() and 0xFF)
    }

    // ── The shipped FCC profile ────────────────────────────────────────────

    private fun assetText(name: String): String {
        val candidates = listOf(
            File("src/main/assets/profiles/$name"),
            File("app/src/main/assets/profiles/$name")
        )
        val f = candidates.firstOrNull { it.exists() }
            ?: throw AssertionError("asset not found: $name (cwd=${File(".").absolutePath})")
        return f.readText()
    }

    private fun intField(json: String, key: String): Int {
        val m = Regex("\"$key\"\\s*:\\s*(\\d+)").find(json)
            ?: throw AssertionError("missing field: $key")
        return m.groupValues[1].toInt()
    }

    private data class ProfileFrame(val s: Int, val i: Int, val d: Int, val p: String)

    private fun profileFrames(json: String): List<ProfileFrame> =
        Regex("\\{\\s*\"s\"\\s*:\\s*(\\d+)\\s*,\\s*\"i\"\\s*:\\s*(\\d+)\\s*,\\s*\"d\"\\s*:\\s*(\\d+)\\s*,\\s*\"p\"\\s*:\\s*\"([0-9a-fA-F]*)\"")
            .findAll(json)
            .map {
                ProfileFrame(
                    it.groupValues[1].toInt(),
                    it.groupValues[2].toInt(),
                    it.groupValues[3].toInt(),
                    it.groupValues[4]
                )
            }
            .toList()

    @Test
    fun `fcc profile keeps the burst inside the service mode window`() {
        val json = assetText("fcc.json")
        val frames = profileFrames(json)
        assertEquals("frame count", 21, frames.size)

        val interFrame = intField(json, "inter_frame_delay_ms")
        val interRound = intField(json, "inter_round_delay_ms")
        val rounds = intField(json, "rounds")

        // A round is one pass through the sequence, opened by frame 1 and closed
        // by frame 21. Anything much over a second and the radio stays on CE.
        val roundMs = frames.size * interFrame
        assertTrue("round takes ${roundMs}ms, must stay under 1500ms", roundMs < 1500)

        // Two sender variants are swept per apply, so bound the whole thing too.
        val sweepMs = 2 * rounds * (roundMs + interRound)
        assertTrue("sweep takes ${sweepMs}ms, must stay under 8000ms", sweepMs < 8000)
    }

    @Test
    fun `fcc profile opens and closes service mode`() {
        val frames = profileFrames(assetText("fcc.json"))
        val first = frames.first()
        val last = frames.last()
        // AUTOTEST cmdSet 16 / cmdId 88 brackets the sequence.
        assertEquals("first frame cmdSet", 16, first.s)
        assertEquals("first frame cmdId", 88, first.i)
        assertEquals("last frame cmdSet", 16, last.s)
        assertEquals("last frame cmdId", 88, last.i)
    }

    @Test
    fun `every fcc profile frame builds to a valid frame on both sender bytes`() {
        val frames = profileFrames(assetText("fcc.json"))
        val cmdType = intField(assetText("fcc.json"), "cmd_type")

        for (sender in listOf(FccViewModel.SENDER_CAPTURE, FccViewModel.SENDER_NET0)) {
            var seq = 149
            for (fr in frames) {
                val payload = ProfileLoader.hexToBytes(fr.p)
                val built = buildAndCheck(sender, cmdType, fr.s, fr.i, fr.d, payload, seq)
                assertTrue("frame within DUML max", built.size <= 1023)
                seq = (seq + 1) and 0xFFFF
            }
        }
    }

    @Test
    fun `ce restore profile frames are valid`() {
        val json = assetText("ce_restore.json")
        val frames = profileFrames(json)
        val cmdType = intField(json, "cmd_type")
        assertTrue("ce_restore has frames", frames.isNotEmpty())
        var seq = 500
        for (fr in frames) {
            buildAndCheck(0x82, cmdType, fr.s, fr.i, fr.d, ProfileLoader.hexToBytes(fr.p), seq)
            seq = (seq + 1) and 0xFFFF
        }
    }

    @Test
    fun `hex parsing round trips and rejects odd input`() {
        assertEquals(0, ProfileLoader.hexToBytes("").size)
        val b = ProfileLoader.hexToBytes("8a237103f401")
        assertEquals(6, b.size)
        assertEquals(0x8a.toByte(), b[0])
        assertEquals(0x01.toByte(), b[5])
        var threw = false
        try { ProfileLoader.hexToBytes("abc") } catch (_: IllegalArgumentException) { threw = true }
        assertTrue("odd-length hex must be rejected", threw)
    }

    // ── Sequence numbers ───────────────────────────────────────────────────

    @Test
    fun `a full sweep never reuses a sequence number`() {
        val frames = profileFrames(assetText("fcc.json"))
        val seen = HashSet<Int>()
        // Two sender passes x two rounds x 21 frames, plus unlock and WLM.
        val total = 2 * 2 * frames.size + 3
        repeat(total) {
            val seq = ProfileLoader.globalSeq.getAndIncrement() and 0xFFFF
            assertTrue("sequence $seq reused", seen.add(seq))
        }
        assertEquals(total, seen.size)
    }

    @Test
    fun `distinct sender bytes produce distinct frames`() {
        val a = DumplBuilder.buildFrameWithSeq(
            DumplFrame(FccViewModel.SENDER_CAPTURE, 0x20, 6, 114, 6, ByteArray(7)), 300
        )
        val b = DumplBuilder.buildFrameWithSeq(
            DumplFrame(FccViewModel.SENDER_NET0, 0x20, 6, 114, 6, ByteArray(7)), 300
        )
        assertNotEquals(a.toList(), b.toList())
        assertEquals(FccViewModel.SENDER_CAPTURE, a[4].toInt() and 0xFF)
        assertEquals(FccViewModel.SENDER_NET0, b[4].toInt() and 0xFF)
    }
}
