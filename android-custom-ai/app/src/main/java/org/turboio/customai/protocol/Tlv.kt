package org.turboio.customai.protocol

class TlvField(val tag: Int, val value: ByteArray) {
    override fun equals(other: Any?): Boolean = other is TlvField && tag == other.tag && value.contentEquals(other.value)
    override fun hashCode(): Int = tag * 31 + value.contentHashCode()
}

/** Tag(1) | length(2, big endian) | value — port of encodeTlvs/decodeTlvs. */
object Tlv {
    fun encode(fields: List<TlvField>): ByteArray {
        if (fields.size > 64) throw ProtocolException("too-many-fields")
        var length = 0
        for (field in fields) {
            requireRange(field.tag, 255)
            if (field.value.size > 65535) throw ProtocolException("tlv-too-large")
            length += 3 + field.value.size
        }
        if (length > FrameLimits.MAX_PAYLOAD_BYTES) throw ProtocolException("tlvs-too-large")
        val out = ByteArray(length)
        var offset = 0
        for (field in fields) {
            out[offset++] = field.tag.toByte()
            out[offset++] = (field.value.size ushr 8).toByte()
            out[offset++] = field.value.size.toByte()
            field.value.copyInto(out, offset); offset += field.value.size
        }
        return out
    }

    fun decode(bytes: ByteArray): List<TlvField> {
        if (bytes.size > FrameLimits.MAX_PAYLOAD_BYTES) throw ProtocolException("tlvs-too-large")
        val fields = ArrayList<TlvField>()
        var offset = 0
        while (offset < bytes.size) {
            if (fields.size >= 64 || bytes.size - offset < 3) throw ProtocolException("tlv-header")
            val tag = bytes[offset].toInt() and 0xff
            val length = (bytes[offset + 1].toInt() and 0xff) * 256 + (bytes[offset + 2].toInt() and 0xff)
            offset += 3
            if (length > bytes.size - offset) throw ProtocolException("tlv-length")
            fields.add(TlvField(tag, bytes.copyOfRange(offset, offset + length)))
            offset += length
        }
        return fields
    }

    fun authRequest(random: ByteArray, proof: ByteArray): ByteArray {
        if (random.size != 4 || proof.size != 32) throw ProtocolException("auth-length")
        return encode(listOf(TlvField(0x18, encode(listOf(TlvField(0x10, random), TlvField(0x11, proof))))))
    }

    /** Parses only the explicitly selected outer tag (0x18 request / 0x19 reply). */
    fun authFields(bytes: ByteArray, expectedTag: Int): List<TlvField> {
        if (expectedTag != 0x18 && expectedTag != 0x19) throw ProtocolException("auth-tag")
        val outer = decode(bytes)
        if (outer.size != 1 || outer[0].tag != expectedTag) throw ProtocolException("auth-outer")
        val inner = decode(outer[0].value)
        if (inner.size != 2 || inner.count { it.tag == 0x10 && it.value.size == 4 } != 1 ||
            inner.count { it.tag == 0x11 && it.value.size == 32 } != 1
        ) throw ProtocolException("auth-fields")
        return inner
    }
}
