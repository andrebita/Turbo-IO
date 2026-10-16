package org.turboio.customai.protocol

/**
 * Business envelope: a minimal protobuf message {1:1, 2:type, 3:json bytes, 4:binary, 5:sequence}.
 * Port of BusinessCommands.businessEnvelope and LauncherStatus.readLauncherEnvelope.
 */
class Envelope(val type: Int, val sequence: Long, val json: ByteArray, val binary: ByteArray) {
    fun jsonText(): String = String(json, Charsets.UTF_8)
}

object EnvelopeCodec {
    fun encode(type: Int, json: ByteArray, sequence: Long = 0): ByteArray {
        if (type < 1 || type > 65535 || sequence < 0 || sequence > 2147483647L) throw ProtocolException("invalid-value")
        if (json.size < 2 || json.size > 7500) throw ProtocolException("business-size")
        val out = java.io.ByteArrayOutputStream(json.size + 16)
        fun varint(v: Long) {
            var value = v
            do {
                val b = (value % 128).toInt(); value /= 128
                out.write(b or (if (value != 0L) 128 else 0))
            } while (value != 0L)
        }
        out.write(8); out.write(1); out.write(16); varint(type.toLong()); out.write(26); varint(json.size.toLong())
        out.write(json)
        if (sequence != 0L) { out.write(40); varint(sequence) }
        return out.toByteArray()
    }

    fun encode(type: Int, json: String, sequence: Long = 0): ByteArray = encode(type, json.toByteArray(Charsets.UTF_8), sequence)

    fun decode(bytes: ByteArray): Envelope {
        if (bytes.size > 16384) throw ProtocolException("launcher-size")
        var pos = 0
        var type = -1; var sequence = 0L
        var json: ByteArray? = null; var binary: ByteArray? = null
        fun varint(): Long {
            var n = 0L; var mul = 1L
            for (i in 0 until 5) {
                if (pos >= bytes.size) throw ProtocolException("launcher-truncated")
                val b = bytes[pos++].toInt() and 0xff
                n += (b and 127) * mul; mul *= 128
                if (b and 128 == 0) return n
            }
            throw ProtocolException("launcher-varint")
        }
        while (pos < bytes.size) {
            val key = varint()
            val field = (key / 8).toInt(); val wire = (key % 8).toInt()
            if (field == 0) throw ProtocolException("launcher-field")
            when (wire) {
                0 -> {
                    val n = varint()
                    if (field == 2) { if (type != -1) throw ProtocolException("launcher-duplicate"); type = n.toInt() }
                    if (field == 5) sequence = n
                }
                2 -> {
                    val size = varint()
                    if (size > bytes.size - pos) throw ProtocolException("launcher-truncated")
                    val slice = bytes.copyOfRange(pos, pos + size.toInt())
                    if (field == 3) { if (json != null) throw ProtocolException("launcher-duplicate"); json = slice }
                    if (field == 4) { if (binary != null) throw ProtocolException("launcher-duplicate"); binary = slice }
                    pos += size.toInt()
                }
                1, 5 -> { pos += if (wire == 1) 8 else 4; if (pos > bytes.size) throw ProtocolException("launcher-truncated") }
                else -> throw ProtocolException("launcher-wire")
            }
        }
        return Envelope(type, sequence, json ?: ByteArray(0), binary ?: ByteArray(0))
    }
}
