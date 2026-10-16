package org.turboio.customai.protocol

/**
 * RayNeo transport frame (AA 55 | len16 | msg16 | flags+slices | [addr] | slices | business | payload | crc16).
 *
 * Kotlin port of harmony-sdk/turbo_core/src/main/ets/ProtocolCodec.ts, which was validated on real
 * RayNeo iO hardware with the original firmware. Behaviour (limits, error cases) is kept identical
 * so the same golden vectors apply. No vendor code is used.
 */
object FrameLimits {
    const val MAX_FRAME_BYTES = 65541
    const val MAX_PAYLOAD_BYTES = 65524
}

class ProtocolException(message: String) : Exception(message)

class FramePacket(
    val messageNumber: Int,
    val flags: Int,
    val address: Int?,
    val slices: ByteArray,
    val businessId: Int,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is FramePacket &&
        messageNumber == other.messageNumber && flags == other.flags && address == other.address &&
        slices.contentEquals(other.slices) && businessId == other.businessId && payload.contentEquals(other.payload)

    override fun hashCode(): Int = (messageNumber * 31 + businessId) * 31 + payload.contentHashCode()
    override fun toString(): String = "FramePacket(msg=$messageNumber, flags=$flags, business=$businessId, bytes=${payload.size})"
}

internal fun requireRange(value: Int, max: Int) {
    if (value < 0 || value > max) throw ProtocolException("invalid-integer")
}

object Crc16Xmodem {
    fun compute(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): Int {
        var crc = 0
        for (i in from until to) {
            crc = crc xor ((bytes[i].toInt() and 0xff) shl 8)
            repeat(8) {
                crc = ((crc shl 1) xor (if (crc and 0x8000 != 0) 0x1021 else 0)) and 0xffff
            }
        }
        return crc
    }
}

object FrameCodec {
    private fun declaredSize(bytes: ByteArray, offset: Int = 0): Int {
        if (bytes.size - offset < 4) throw ProtocolException("truncated-header")
        if ((bytes[offset].toInt() and 0xff) != 0xaa || (bytes[offset + 1].toInt() and 0xff) != 0x55) {
            throw ProtocolException("invalid-head")
        }
        val size = (bytes[offset + 2].toInt() and 0xff) * 256 + (bytes[offset + 3].toInt() and 0xff) + 6
        if (size < 10 || size > FrameLimits.MAX_FRAME_BYTES) throw ProtocolException("invalid-length")
        return size
    }

    internal fun declaredSizeOf(header: ByteArray): Int = declaredSize(header)

    fun encode(packet: FramePacket): ByteArray {
        requireRange(packet.messageNumber, 65535); requireRange(packet.flags, 31); requireRange(packet.businessId, 255)
        packet.address?.let { requireRange(it, 255) }
        if (((packet.flags and 2) != 0) != (packet.address != null)) throw ProtocolException("address-flag-mismatch")
        if (packet.slices.size > 7) throw ProtocolException("slice-metadata-too-long")
        if (packet.payload.size > FrameLimits.MAX_PAYLOAD_BYTES) throw ProtocolException("payload-too-long")
        val size = 10 + (if (packet.address == null) 0 else 1) + packet.slices.size + packet.payload.size
        if (size > FrameLimits.MAX_FRAME_BYTES) throw ProtocolException("frame-too-long")
        val out = ByteArray(size)
        val length = size - 6
        out[0] = 0xaa.toByte(); out[1] = 0x55; out[2] = (length ushr 8).toByte(); out[3] = length.toByte()
        out[4] = (packet.messageNumber ushr 8).toByte(); out[5] = packet.messageNumber.toByte()
        out[6] = (packet.flags or (packet.slices.size shl 5)).toByte()
        var offset = 7
        packet.address?.let { out[offset++] = it.toByte() }
        packet.slices.copyInto(out, offset); offset += packet.slices.size
        out[offset++] = packet.businessId.toByte()
        packet.payload.copyInto(out, offset)
        val crc = Crc16Xmodem.compute(out, 4, size - 2)
        out[size - 2] = (crc ushr 8).toByte(); out[size - 1] = crc.toByte()
        return out
    }

    fun decode(bytes: ByteArray): FramePacket {
        val size = declaredSize(bytes)
        if (bytes.size != size) throw ProtocolException("frame-size-mismatch")
        val flags = bytes[6].toInt() and 31
        val sliceCount = (bytes[6].toInt() and 0xff) ushr 5
        val addressCount = if (flags and 2 != 0) 1 else 0
        val businessOffset = 7 + addressCount + sliceCount
        if (businessOffset >= size - 2) throw ProtocolException("truncated-frame")
        if (size - businessOffset - 3 > FrameLimits.MAX_PAYLOAD_BYTES) throw ProtocolException("payload-too-long")
        val expected = (bytes[size - 2].toInt() and 0xff) * 256 + (bytes[size - 1].toInt() and 0xff)
        if (Crc16Xmodem.compute(bytes, 4, size - 2) != expected) throw ProtocolException("crc-mismatch")
        return FramePacket(
            messageNumber = (bytes[4].toInt() and 0xff) * 256 + (bytes[5].toInt() and 0xff),
            flags = flags,
            address = if (addressCount == 1) bytes[7].toInt() and 0xff else null,
            slices = bytes.copyOfRange(7 + addressCount, businessOffset),
            businessId = bytes[businessOffset].toInt() and 0xff,
            payload = bytes.copyOfRange(businessOffset + 1, size - 2),
        )
    }
}

/**
 * Strict bounded reassembly of notifications into frames. Never resynchronises by skipping bytes:
 * a corrupt stream fails until [reset], exactly like the HarmonyOS reference.
 */
class FrameStream {
    private val buffer = ByteArray(FrameLimits.MAX_FRAME_BYTES)
    private var used = 0
    private var expected = 4
    private var failed = false

    fun reset() { buffer.fill(0); used = 0; expected = 4; failed = false }
    fun pendingBytes(): Int = used

    fun finish() {
        if (failed) throw ProtocolException("stream-failed")
        if (used != 0) {
            buffer.fill(0); used = 0; expected = 4; failed = true
            throw ProtocolException("truncated-stream")
        }
    }

    fun feed(chunk: ByteArray): List<FramePacket> {
        if (failed) throw ProtocolException("stream-failed")
        try {
            if (chunk.size > 262144) throw ProtocolException("feed-too-large")
            val packets = ArrayList<FramePacket>()
            var offset = 0
            while (offset < chunk.size) {
                val count = minOf(expected - used, chunk.size - offset)
                chunk.copyInto(buffer, used, offset, offset + count)
                offset += count; used += count
                if (used == 4 && expected == 4) expected = FrameCodec.declaredSizeOf(buffer.copyOfRange(0, 4))
                if (used == expected) {
                    if (packets.size >= 256) throw ProtocolException("too-many-frames")
                    packets.add(FrameCodec.decode(buffer.copyOfRange(0, used)))
                    buffer.fill(0, 0, used); used = 0; expected = 4
                }
            }
            return packets
        } catch (error: Exception) {
            buffer.fill(0); used = 0; expected = 4; failed = true
            throw if (error is ProtocolException) error else ProtocolException("stream-error")
        }
    }
}
