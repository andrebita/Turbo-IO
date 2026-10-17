package org.turboio.customai.ambient

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream

/**
 * On-disk segment (≈5 min, always cut at a pause):
 *   frames.bin  — [u16 big-endian length][Opus frame] … (append-only, original bytes)
 *   flags.bin   — per frame: [flags][rms u16 big-endian]
 *   segment.json — manifest, replaced atomically (write .next + rename); a torn write never marks a segment sealed.
 */
class SegmentManifest(
    val id: String,
    val source: String,
    val channels: Int,
    val frameMs: Int,
    val providesWearer: Boolean,
    val headMounted: Boolean,
    val startedWall: Long,
    var frames: Int = 0,
    var speechFrames: Int = 0,
    var corruptFrames: Int = 0,
    var sealed: Boolean = false,
    var endedWall: Long = 0,
    var sealReason: String = "",
    val anchors: MutableList<LongArray> = ArrayList(),   // [frameIndex, wallMs] per batch (time base)
) {
    fun wallAt(frame: Int): Long {
        var best = longArrayOf(0, startedWall)
        for (a in anchors) if (a[0] <= frame) best = a else break
        return best[1] + (frame - best[0]) * frameMs
    }

    fun toJson(): String = JSONObject().put("version", 1).put("id", id).put("source", source).put("channels", channels)
        .put("frameMs", frameMs).put("providesWearer", providesWearer).put("headMounted", headMounted)
        .put("startedWall", startedWall).put("frames", frames).put("speechFrames", speechFrames).put("corruptFrames", corruptFrames)
        .put("sealed", sealed).put("endedWall", endedWall).put("sealReason", sealReason)
        .put("anchors", JSONArray(anchors.map { JSONArray(listOf(it[0], it[1])) })).toString()

    companion object {
        fun parse(text: String): SegmentManifest {
            val o = JSONObject(text)
            require(o.getInt("version") == 1)
            val m = SegmentManifest(o.getString("id"), o.getString("source"), o.getInt("channels"), o.getInt("frameMs"),
                o.optBoolean("providesWearer"), o.optBoolean("headMounted"), o.getLong("startedWall"),
                o.getInt("frames"), o.getInt("speechFrames"), o.optInt("corruptFrames"), o.getBoolean("sealed"),
                o.optLong("endedWall"), o.optString("sealReason"))
            val a = o.optJSONArray("anchors") ?: JSONArray()
            for (i in 0 until a.length()) a.getJSONArray(i).let { m.anchors.add(longArrayOf(it.getLong(0), it.getLong(1))) }
            return m
        }
    }
}

class SegmentWriter(val dir: File, val manifest: SegmentManifest) {
    private val frames = BufferedOutputStream(FileOutputStream(File(dir, "frames.bin"), true))
    private val flags = BufferedOutputStream(FileOutputStream(File(dir, "flags.bin"), true))
    private var lastPersist = 0L

    init { persist(force = true) }

    fun append(frame: ByteArray, flag: Int, frameRms: Int) {
        require(frame.size in 1..4096)
        frames.write(frame.size ushr 8); frames.write(frame.size and 0xff); frames.write(frame)
        val r = frameRms.coerceIn(0, 65535)
        flags.write(flag); flags.write(r ushr 8); flags.write(r and 0xff)
        manifest.frames++
        if (flag and FrameFlags.SPEECH != 0) manifest.speechFrames++
        if (flag and FrameFlags.CORRUPT != 0) manifest.corruptFrames++
    }

    fun anchor(wall: Long) {
        if (manifest.anchors.size < 4000) manifest.anchors.add(longArrayOf(manifest.frames.toLong(), wall))
    }

    /** Flushes data first, then the manifest (so the manifest never claims frames that are not on disk). */
    fun persist(force: Boolean = false, now: Long = System.currentTimeMillis()) {
        if (!force && now - lastPersist < 5000) return
        lastPersist = now
        frames.flush(); flags.flush()
        val next = File(dir, "segment.json.next")
        FileOutputStream(next).use { it.write(manifest.toJson().toByteArray()); it.fd.sync() }
        if (!next.renameTo(File(dir, "segment.json"))) throw java.io.IOException("manifest-rename")
    }

    fun seal(reason: String, endedWall: Long) {
        manifest.sealed = true; manifest.endedWall = endedWall; manifest.sealReason = reason
        persist(force = true)
        frames.close(); flags.close()
    }
}

/** Read side of a segment (used by the chunk builder). */
class SegmentReader(val dir: File) {
    val manifest: SegmentManifest = SegmentManifest.parse(File(dir, "segment.json").readText())

    /** Flags limited to the frames the manifest vouches for. */
    fun flags(): Pair<ByteArray, IntArray> {
        val raw = File(dir, "flags.bin").readBytes()
        val n = minOf(manifest.frames, raw.size / 3)
        return ByteArray(n) { raw[it * 3] } to IntArray(n) { ((raw[it * 3 + 1].toInt() and 0xff) shl 8) or (raw[it * 3 + 2].toInt() and 0xff) }
    }

    fun frames(): List<ByteArray> {
        val out = ArrayList<ByteArray>(manifest.frames)
        DataInputStream(File(dir, "frames.bin").inputStream().buffered()).use { input ->
            while (out.size < manifest.frames) {
                val len = try { input.readUnsignedShort() } catch (_: java.io.EOFException) { break }
                val b = ByteArray(len); input.readFully(b); out.add(b)
            }
        }
        return out
    }
}
