package org.turboio.customai.protocol

import java.io.ByteArrayOutputStream

/**
 * File-transfer messages (protobuf TransferMessage), ported from harmony-sdk/turbo_core FileTransfer.ts and checked
 * against the official app 1.0.7: the message goes straight into the frame payload (no envelope) on business 17
 * ("SmallCore", the one the official app's upload uses) or 18 ("LargeCore"), and FileInfoSync.busId repeats that number.
 * Used only for the teleprompter script: a plain ".txt" name or the transfer id, at most 48 000 bytes, MD5 announced,
 * and the glasses pull every chunk (type 3 → type 4).
 * Types: 1 info, 2 ack, 3 chunk request, 4 chunk data, 5 result, 6 cancel.
 */
class FileMessage(
    val type: Int,
    val uuid: String,
    val fileName: String = "",
    val md5: String = "",
    val fileSize: Long = 0,
    val busId: Long = Business.FILE.toLong(),
    val chunkId: Long = 0,
    val start: Long = 0,
    val size: Long = 0,
    val data: ByteArray = ByteArray(0),
    val state: Long = 0,
    val reason: String = "",
)

object FileTransfer {
    const val MAX_FILE = 48_000
    /** Largest range the glasses may ask for (the official receiver asks 51 200); answers are clipped to the file. */
    const val MAX_REQUEST = 1 shl 20
    const val MAX_MESSAGE = MAX_FILE + 512
    /** File businesses: 17 = SmallCore, 18 = LargeCore. */
    val BUSINESSES = setOf(Business.SMALL_FILE, Business.FILE)
    private val UUID = Regex("^[A-Za-z0-9_-]{1,64}$")
    /** A plain ".txt" name, or a teleprompter document id (lowercase UUID, no extension, as the official app names them). */
    private val NAME = Regex("^([A-Za-z0-9_-]{1,40}\\.txt|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$")

    private fun ascii(s: String, max: Int): ByteArray {
        if (s.length > max || s.any { it.code !in 0x20..0x7e }) throw ProtocolException("file-ascii")
        return s.toByteArray(Charsets.US_ASCII)
    }

    private fun ByteArrayOutputStream.varint(v: Long) { var n = v; do { val b = (n and 0x7f).toInt(); n = n ushr 7; write(if (n != 0L) b or 0x80 else b) } while (n != 0L) }
    private fun ByteArrayOutputStream.blob(field: Int, data: ByteArray) { write(field * 8 + 2); varint(data.size.toLong()); write(data) }
    private fun ByteArrayOutputStream.scalar(field: Int, v: Long) { write(field * 8); varint(v) }

    fun encode(m: FileMessage): ByteArray {
        if (!UUID.matches(m.uuid)) throw ProtocolException("file-uuid")
        val body = ByteArrayOutputStream()
        body.blob(1, ascii(m.uuid, 64))
        when (m.type) {
            1 -> {
                if ((m.fileName != m.uuid && !NAME.matches(m.fileName)) || !Regex("^[a-f0-9]{32}$").matches(m.md5) ||
                    m.busId !in BUSINESSES.map { it.toLong() } || m.fileSize !in 1..MAX_FILE.toLong())
                    throw ProtocolException("file-info")
                body.blob(2, ascii(m.fileName, 68)); body.scalar(3, m.fileSize); body.blob(4, ascii(m.md5, 32)); body.scalar(5, m.busId)
            }
            3, 4 -> {
                if (m.chunkId !in 0..0xffffffffL || m.start !in 0..MAX_FILE.toLong() || m.size !in 1..maxSize(m.type)) throw ProtocolException("file-chunk")
                body.scalar(2, m.chunkId); body.scalar(3, m.start); body.scalar(4, m.size)
                if (m.type == 4) { if (m.data.size.toLong() != m.size) throw ProtocolException("file-chunk-size"); body.blob(5, m.data) }
            }
            5 -> { if (m.state !in 0..1) throw ProtocolException("file-state"); body.scalar(2, m.state); if (m.reason.isNotEmpty()) body.blob(3, ascii(m.reason, 120)) }
            6 -> if (m.reason.isNotEmpty()) body.blob(2, ascii(m.reason, 120))
            2 -> Unit
            else -> throw ProtocolException("file-message-type")
        }
        val out = ByteArrayOutputStream()
        out.blob(m.type, body.toByteArray())
        return out.toByteArray()
    }

    private class Field(val key: Int, val wire: Int, val value: Long, val data: ByteArray)

    private fun fields(bytes: ByteArray): List<Field> {
        if (bytes.size > MAX_MESSAGE) throw ProtocolException("file-message-size")
        var offset = 0
        fun read(): Long {
            var n = 0L
            for (i in 0 until 8) {
                if (offset >= bytes.size) throw ProtocolException("file-truncated")
                val b = bytes[offset++].toInt() and 0xff
                n = n or ((b and 0x7f).toLong() shl (7 * i))
                if (b and 0x80 == 0) return n
            }
            throw ProtocolException("file-varint")
        }
        val out = ArrayList<Field>()
        while (offset < bytes.size) {
            if (out.size >= 16) throw ProtocolException("file-field-count")
            val tag = read(); val key = (tag ushr 3).toInt(); val wire = (tag and 7).toInt()
            if (key == 0 || out.any { it.key == key }) throw ProtocolException("file-duplicate-field")
            out.add(when (wire) {
                0 -> Field(key, 0, read(), ByteArray(0))
                2 -> { val n = read().toInt(); if (n < 0 || n > bytes.size - offset) throw ProtocolException("file-truncated"); Field(key, 2, 0, bytes.copyOfRange(offset, offset + n)).also { offset += n } }
                else -> throw ProtocolException("file-wire")
            })
        }
        return out
    }

    fun decode(bytes: ByteArray): FileMessage {
        val outer = fields(bytes)
        if (outer.size != 1 || outer[0].wire != 2 || outer[0].key !in 1..6) throw ProtocolException("file-oneof")
        val type = outer[0].key; val list = fields(outer[0].data)
        fun text(key: Int, max: Int): String {
            val f = list.firstOrNull { it.key == key } ?: return ""
            if (f.wire != 2 || f.data.size > max || f.data.any { (it.toInt() and 0xff) !in 0x20..0x7e }) throw ProtocolException("file-field")
            return String(f.data, Charsets.US_ASCII)
        }
        fun num(key: Int): Long { val f = list.firstOrNull { it.key == key } ?: return 0; if (f.wire != 0) throw ProtocolException("file-field"); return f.value }
        val uuid = text(1, 64)
        if (!UUID.matches(uuid)) throw ProtocolException("file-uuid")
        return when (type) {
            1 -> FileMessage(1, uuid, fileName = text(2, 68), fileSize = num(3), md5 = text(4, 32), busId = num(5))
            3, 4 -> {
                val data = list.firstOrNull { it.key == 5 }?.data ?: ByteArray(0)
                val m = FileMessage(type, uuid, chunkId = num(2), start = num(3), size = num(4), data = data)
                if (m.chunkId !in 0..0xffffffffL || m.start !in 0..MAX_FILE.toLong() || m.size !in 1..maxSize(type) || (type == 4 && data.size.toLong() != m.size))
                    throw ProtocolException("file-chunk")
                m
            }
            5 -> FileMessage(5, uuid, state = num(2).also { if (it !in 0..1) throw ProtocolException("file-state") }, reason = text(3, 120))
            6 -> FileMessage(6, uuid, reason = text(2, 120))
            else -> FileMessage(type, uuid)
        }
    }

    private fun maxSize(type: Int): Long = if (type == 3) MAX_REQUEST.toLong() else MAX_FILE.toLong()

    /**
     * Outgoing gate: only info (1), chunk data (4) and cancel (6), well formed and bounded, on a file business; the info's
     * busId must name the business it travels on. Null = allowed.
     */
    fun outboundProblem(payload: ByteArray, business: Int = Business.FILE): String? {
        if (business !in BUSINESSES) return "Canal de arquivo inválido ($business)"
        if (payload.size > MAX_MESSAGE + 10) return "Mensagem de arquivo grande demais"
        val m = runCatching { decode(payload) }.getOrElse { return "Mensagem de arquivo inválida" }
        if (m.type !in setOf(1, 4, 6)) return "Tipo de mensagem de arquivo não permitido (${m.type})"
        if (m.type == 1 && m.busId != business.toLong()) return "Arquivo anunciado para outro canal (${m.busId})"
        return runCatching { encode(m); null }.getOrElse { "Mensagem de arquivo fora dos limites" }
    }
}

/** One outgoing file served on the glasses' requests (port of OutboundFile), announced as [fileName] on [business]. */
class OutboundFile(val uuid: String, bytes: ByteArray, private val md5: String, val fileName: String = uuid, val business: Int = Business.FILE) {
    private val bytes = bytes.copyOf()
    var acknowledged = false; private set
    var complete = false; private set
    var cancelled = false; private set
    var sentBytes = 0; private set
    private val ranges = ArrayList<IntRange>()
    private var requests = 0
    val size get() = bytes.size

    init { info() }

    fun info(): ByteArray =
        FileTransfer.encode(FileMessage(1, uuid, fileName = fileName, md5 = md5, fileSize = bytes.size.toLong(), busId = business.toLong()))

    /** The same bytes under another transfer id, name or business (the next addressing attempt). */
    fun copy(uuid: String, fileName: String, business: Int) = OutboundFile(uuid, bytes, md5, fileName, business)

    /** Why the glasses refused the file, when they said so. */
    var failure = ""; private set

    /** Handles a glasses message; returns the chunk to send for a request, else null. Throws on protocol errors. */
    fun receive(m: FileMessage): FileMessage? {
        if (m.uuid != uuid || cancelled || complete) return null
        when (m.type) {
            2 -> { acknowledged = true; return null }
            6 -> { cancelled = true; throw ProtocolException("file-peer-cancelled") }
            5 -> {
                if (m.state != 0L) { failure = m.reason; throw ProtocolException("file-peer-failed" + if (m.reason.isNotEmpty()) ": ${m.reason}" else "") }
                if (sentBytes != bytes.size) throw ProtocolException("file-premature-success")
                complete = true; return null
            }
            3 -> {
                // A request implies the info was accepted (the official receiver acks first, but nothing depends on it).
                acknowledged = true
                // The official receiver uses chunk id 0 for every request, so ids are echoed, not checked for reuse.
                if (++requests > 256 || m.start >= bytes.size) throw ProtocolException("file-request-limit")
                val start = m.start.toInt(); val size = minOf(m.size.toInt(), bytes.size - start)
                return FileMessage(4, uuid, chunkId = m.chunkId, start = start.toLong(), size = size.toLong(), data = bytes.copyOfRange(start, start + size))
            }
            else -> return null
        }
    }

    /** Call after the chunk was written to the radio. */
    fun committed(m: FileMessage) {
        if (cancelled || m.uuid != uuid || m.type != 4 || m.start + m.size > bytes.size) throw ProtocolException("file-commit")
        ranges.add(m.start.toInt() until (m.start + m.size).toInt())
        ranges.sortBy { it.first }
        val merged = ArrayList<IntRange>()
        for (r in ranges) { val last = merged.lastOrNull(); if (last != null && r.first <= last.last + 1) merged[merged.lastIndex] = last.first..maxOf(last.last, r.last) else merged.add(r) }
        ranges.clear(); ranges.addAll(merged)
        sentBytes = merged.sumOf { it.last - it.first + 1 }
    }

    fun close() { cancelled = true; bytes.fill(0); ranges.clear() }
}
