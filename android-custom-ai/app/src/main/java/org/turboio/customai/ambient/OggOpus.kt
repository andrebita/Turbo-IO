package org.turboio.customai.ambient

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import java.io.ByteArrayOutputStream

/** Minimal Ogg Opus muxer (RFC 7845): OpusHead, OpusTags, audio pages with 48 kHz granule positions. */
class OggOpusWriter(private val channels: Int = 1, private val inputRate: Int = 16000, private val preSkip: Int = 312, private val serial: Int = 0x7392) {
    private val out = ByteArrayOutputStream()
    private var sequence = 0
    private val pending = ArrayList<ByteArray>()
    private var granule = preSkip.toLong()

    init {
        val head = ByteArray(19)
        "OpusHead".toByteArray().copyInto(head)
        head[8] = 1; head[9] = channels.toByte()
        head[10] = (preSkip and 0xff).toByte(); head[11] = (preSkip ushr 8).toByte()
        for (i in 0 until 4) head[12 + i] = (inputRate ushr (8 * i)).toByte()
        page(listOf(head), 0, bos = true, eos = false)
        val vendor = "TurboIO".toByteArray()
        val tags = ByteArrayOutputStream().apply {
            write("OpusTags".toByteArray()); le32(vendor.size); write(vendor); le32(0)
        }.toByteArray()
        page(listOf(tags), 0, bos = false, eos = false)
    }

    private fun ByteArrayOutputStream.le32(v: Int) { for (i in 0 until 4) write((v ushr (8 * i)) and 0xff) }

    /** [samples48k]: duration of the packet in 48 kHz samples. */
    fun packet(data: ByteArray, samples48k: Int) {
        pending.add(data); granule += samples48k
        if (pending.size >= 50) flushPage(eos = false)
    }

    fun finish(): ByteArray { flushPage(eos = true); return out.toByteArray() }

    private fun flushPage(eos: Boolean) {
        if (pending.isEmpty() && !eos) return
        page(ArrayList(pending), granule, bos = false, eos = eos); pending.clear()
    }

    private fun page(packets: List<ByteArray>, granulePos: Long, bos: Boolean, eos: Boolean) {
        val lacing = ArrayList<Int>()
        for (p in packets) { var n = p.size; while (n >= 255) { lacing.add(255); n -= 255 }; lacing.add(n) }
        require(lacing.size <= 255) { "page-too-large" }
        val header = ByteArray(27 + lacing.size)
        "OggS".toByteArray().copyInto(header)
        header[4] = 0
        header[5] = ((if (bos) 2 else 0) or (if (eos) 4 else 0)).toByte()
        for (i in 0 until 8) header[6 + i] = (granulePos ushr (8 * i)).toByte()
        for (i in 0 until 4) header[14 + i] = (serial ushr (8 * i)).toByte()
        for (i in 0 until 4) header[18 + i] = (sequence ushr (8 * i)).toByte()
        header[26] = lacing.size.toByte()
        lacing.forEachIndexed { i, v -> header[27 + i] = v.toByte() }
        val body = ByteArrayOutputStream().apply { packets.forEach { write(it) } }.toByteArray()
        val crc = OggCrc.compute(header + body)
        for (i in 0 until 4) header[22 + i] = (crc ushr (8 * i)).toByte()
        out.write(header); out.write(body); sequence++
    }
}

object OggCrc {
    private val TABLE = IntArray(256) { i ->
        var r = i shl 24
        repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04c11db7 else r shl 1 }
        r
    }
    fun compute(data: ByteArray): Int {
        var crc = 0
        for (b in data) crc = (crc shl 8) xor TABLE[((crc ushr 24) xor (b.toInt() and 0xff)) and 0xff]
        return crc
    }
}

/** Encodes 16 kHz mono PCM as Ogg Opus (VOIP, [bitrate] bit/s, 20 ms frames). */
object OpusFile {
    fun encode(pcm: ShortArray, bitrate: Int = 24000): ByteArray {
        val encoder = OpusEncoder(16000, 1, OpusApplication.OPUS_APPLICATION_VOIP)
        encoder.setBitrate(bitrate)
        val writer = OggOpusWriter()
        val frame = ShortArray(320); val buffer = ByteArray(1275)
        var i = 0
        while (i < pcm.size) {
            frame.fill(0)
            val n = minOf(320, pcm.size - i)
            System.arraycopy(pcm, i, frame, 0, n)
            val bytes = encoder.encode(frame, 0, 320, buffer, 0, buffer.size)
            writer.packet(buffer.copyOf(bytes), 960)
            i += 320
        }
        return writer.finish()
    }
}

/** Builds the audio of one chunk: decoded pieces (with decoder warm-up), downmixed, joined by silence. */
object ChunkAudio {
    fun pcm(frames: List<ByteArray>, channels: Int, plan: ChunkPlan, separatorMs: Long): ShortArray {
        val out = ArrayList<ShortArray>()
        val separator = ShortArray((separatorMs * 16).toInt())
        val buffer = ShortArray(5760 * 2)
        for ((index, piece) in plan.pieces.withIndex()) {
            if (index > 0) out.add(separator)
            val decoder = OpusDecoder(16000, channels)
            val warm = maxOf(0, piece.frameStart - 5)
            for (f in warm until minOf(piece.frameEnd, frames.size)) {
                val samples = try {
                    val n = decoder.decode(frames[f], 0, frames[f].size, buffer, 0, buffer.size / channels, false)
                    downmix(buffer.copyOf(n * channels), channels)
                } catch (_: Exception) { ShortArray(plan.frameMs * 16) }   // corrupt frame → silence, keeps timing
                if (f >= piece.frameStart) out.add(samples)
            }
        }
        val total = out.sumOf { it.size }
        val pcm = ShortArray(total); var at = 0
        for (s in out) { s.copyInto(pcm, at); at += s.size }
        return pcm
    }
}
