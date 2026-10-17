package org.turboio.customai.protocol

/** Guest version request (account/public key absent). Port of AndroidVersionExchange.ts. */
class VersionRequest(
    val phoneIdentifier: ByteArray,
    val phoneName: ByteArray,
    val manufacturer: ByteArray,
    val model: ByteArray,
    val pairValue: Int? = null,
)

class VersionReply(val protocolVersion: Int, val peerIdentifier: ByteArray, val fields: List<TlvField>) {
    // May contain names/serials. Never log values; only tags and lengths.
    fun field(tag: Int): ByteArray? = fields.firstOrNull { it.tag == tag }?.value?.copyOf()
    fun shape(): String = fields.joinToString(",") { "0x%x:%d".format(it.tag, it.value.size) }
}

object VersionExchange {
    private fun bounded(bytes: ByteArray, max: Int) { if (bytes.size > max) throw ProtocolException("version-field-limit") }

    fun request(input: VersionRequest): ByteArray {
        if (input.phoneIdentifier.size != 6) throw ProtocolException("version-phone-identifier")
        bounded(input.phoneName, 248); bounded(input.manufacturer, 128); bounded(input.model, 128)
        if (input.pairValue != null && input.pairValue != 1 && input.pairValue != 2) throw ProtocolException("version-pair-policy")
        val fields = mutableListOf(
            TlvField(0x10, byteArrayOf(1)), TlvField(0x11, input.phoneIdentifier),
            TlvField(0x12, input.phoneName), TlvField(0x13, byteArrayOf(2)),
            TlvField(0x16, input.manufacturer), TlvField(0x17, input.model),
        )
        input.pairValue?.let { fields.add(TlvField(0x1b, byteArrayOf(it.toByte()))) }
        return Tlv.encode(listOf(TlvField(0x11, Tlv.encode(fields))))
    }

    fun reply(payload: ByteArray): VersionReply {
        if (payload.size > 4096) throw ProtocolException("version-payload-limit")
        val outer = Tlv.decode(payload)
        if (outer.size != 1 || outer[0].tag != 0x11) throw ProtocolException("version-outer")
        val fields = Tlv.decode(outer[0].value)
        if (fields.size > 32) throw ProtocolException("version-field-count")
        val seen = HashSet<Int>()
        for (field in fields) {
            bounded(field.value, 1024)
            if (!seen.add(field.tag)) throw ProtocolException("version-duplicate-field")
        }
        val version = fields.firstOrNull { it.tag == 0x10 }?.value
        val identifier = fields.firstOrNull { it.tag == 0x11 }?.value
        if (version == null || version.size != 1 || identifier == null || identifier.size != 6) {
            throw ProtocolException("version-required-fields")
        }
        val kind = fields.firstOrNull { it.tag == 0x13 }?.value
        if (kind != null && kind.size != 1) throw ProtocolException("version-device-type")
        val key = fields.firstOrNull { it.tag == 0x19 }?.value
        if (key != null && (key.size != 65 || key[0].toInt() != 4)) throw ProtocolException("version-peer-key-shape")
        return VersionReply(version[0].toInt() and 0xff, identifier.copyOf(), fields)
    }
}
