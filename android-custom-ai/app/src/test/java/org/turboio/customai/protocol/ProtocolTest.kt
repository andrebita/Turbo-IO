package org.turboio.customai.protocol

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private fun u(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
private fun packet(msg: Int = 0x1234, flags: Int = 0, address: Int? = null, slices: ByteArray = ByteArray(0), business: Int = 0x2a, payload: ByteArray = u(1, 2, 254)) =
    FramePacket(msg, flags, address, slices, business, payload)

/** Ported from harmony-sdk/tests/protocol-codec.test.mjs + auth-calculations.test.mjs (same golden vectors). */
class ProtocolTest {
    private val golden = u(170, 85, 0, 7, 18, 52, 0, 42, 1, 2, 254, 126, 211)

    @Test fun crcAndGoldenFrame() {
        assertEquals(0x31c3, Crc16Xmodem.compute("123456789".toByteArray()))
        assertEquals(0, Crc16Xmodem.compute(ByteArray(0)))
        assertArrayEquals(golden, FrameCodec.encode(packet()))
        assertEquals(packet(), FrameCodec.decode(golden))
    }

    @Test fun flagsSlicesRoundTripAndAddressGolden() {
        for (flags in 0 until 32) for (n in 0 until 8) {
            val p = packet(flags = flags, address = if (flags and 2 != 0) 255 else null, slices = ByteArray(n) { 99 })
            assertEquals(p, FrameCodec.decode(FrameCodec.encode(p)))
        }
        assertArrayEquals(u(170, 85, 0, 11, 190, 239, 98, 33, 160, 176, 192, 225, 0, 255, 85, 40, 22),
            FrameCodec.encode(packet(0xbeef, 2, 33, u(160, 176, 192), 225, u(0, 255, 85))))
    }

    @Test fun everyTruncationTrailingAndBitFlipRejected() {
        for (n in golden.indices) assertThrows(ProtocolException::class.java) { FrameCodec.decode(golden.copyOf(n)) }
        assertThrows(ProtocolException::class.java) { FrameCodec.decode(golden + u(0)) }
        for (pos in golden.indices) for (bit in 0 until 8) {
            val altered = golden.copyOf(); altered[pos] = (altered[pos].toInt() xor (1 shl bit)).toByte()
            assertThrows(ProtocolException::class.java) { FrameCodec.decode(altered) }
        }
    }

    @Test fun limitsRejectInsteadOfTruncating() {
        listOf(packet(flags = 32), packet(flags = -1), packet(msg = 65536), packet(business = 256), packet(address = 1),
            packet(flags = 2), packet(flags = 2, address = 256), packet(slices = ByteArray(8)), packet(payload = ByteArray(65525))
        ).forEach { p -> assertThrows(ProtocolException::class.java) { FrameCodec.encode(p) } }
        assertEquals(65524, FrameCodec.decode(FrameCodec.encode(packet(payload = ByteArray(65524)))).payload.size)
    }

    @Test fun streamEverySplitAndByteAtATime() {
        for (split in 0..golden.size) {
            val stream = FrameStream()
            val got = stream.feed(golden.copyOfRange(0, split)) + stream.feed(golden.copyOfRange(split, golden.size))
            assertEquals(listOf(packet()), got); assertEquals(0, stream.pendingBytes())
        }
        val stream = FrameStream()
        val got = golden.flatMap { stream.feed(byteArrayOf(it)) }
        assertEquals(listOf(packet()), got)
    }

    @Test fun streamConcatenationAndStickyFailure() {
        val stream = FrameStream()
        assertEquals(2, stream.feed(golden + golden + golden.copyOf(5)).size)
        assertEquals(5, stream.pendingBytes())
        assertEquals(listOf(packet()), stream.feed(golden.copyOfRange(5, golden.size)))
        val corrupt = golden.copyOf(); corrupt[8] = (corrupt[8].toInt() xor 1).toByte()
        assertThrows(ProtocolException::class.java) { stream.feed(golden + corrupt) }
        assertEquals(0, stream.pendingBytes())
        assertThrows(ProtocolException::class.java) { stream.feed(golden) }
        stream.reset(); assertEquals(listOf(packet()), stream.feed(golden))
    }

    @Test fun truncatedStreamEnd() {
        for (n in 1 until golden.size) {
            val stream = FrameStream(); stream.feed(golden.copyOf(n))
            assertThrows(ProtocolException::class.java) { stream.finish() }
        }
        FrameStream().finish()
    }

    @Test fun tlvAndAuthShapes() {
        val fields = listOf(TlvField(1, u(2, 3)), TlvField(1, ByteArray(0)))
        assertEquals(fields, Tlv.decode(Tlv.encode(fields)))
        listOf(u(1), u(1, 0), u(1, 0, 2, 9)).forEach { assertThrows(ProtocolException::class.java) { Tlv.decode(it) } }
        val payload = Tlv.authRequest(u(1, 2, 3, 4), ByteArray(32) { 5 })
        assertEquals(45, payload.size)
        assertEquals(55, FrameCodec.encode(packet(business = 16, payload = payload)).size)
        assertEquals(listOf(16 to 4, 17 to 32), Tlv.authFields(payload, 0x18).map { it.tag to it.value.size })
        assertThrows(ProtocolException::class.java) { Tlv.authFields(payload, 0x19) }
        val duplicate = Tlv.encode(listOf(TlvField(24, Tlv.encode(listOf(TlvField(16, ByteArray(4)), TlvField(16, ByteArray(4)))))))
        assertThrows(ProtocolException::class.java) { Tlv.authFields(duplicate, 0x18) }
    }

    @Test fun guestProofGoldenVector() {
        val proof = AuthCalculations.guestProof(u(1, 2, 3, 4), Hex.decode("001122334455"), Hex.decode("aabbccddeeff"))
        assertEquals("481bb79704a8523c1e86ae93cfb2bd7204b76984d2d2c3684799d70b58839c84", Hex.encode(proof))
        assertTrue(AuthCalculations.proofMatches(proof, proof.copyOf()))
        for (i in 0 until 32) { val other = proof.copyOf(); other[i] = (other[i].toInt() xor 1).toByte(); assertFalse(AuthCalculations.proofMatches(proof, other)) }
        assertThrows(ProtocolException::class.java) { AuthCalculations.guestProof(ByteArray(3), ByteArray(6), ByteArray(6)) }
    }

    @Test fun versionReplyValidation() {
        fun reply(vararg fields: TlvField) = Tlv.encode(listOf(TlvField(0x11, Tlv.encode(fields.toList()))))
        val ok = VersionExchange.reply(reply(TlvField(0x10, u(2)), TlvField(0x11, ByteArray(6) { 7 }), TlvField(0x13, u(1))))
        assertEquals(2, ok.protocolVersion); assertEquals("0x10:1,0x11:6,0x13:1", ok.shape())
        assertThrows(ProtocolException::class.java) { VersionExchange.reply(reply(TlvField(0x10, u(2)))) }
        assertThrows(ProtocolException::class.java) { VersionExchange.reply(reply(TlvField(0x10, u(2)), TlvField(0x11, ByteArray(6)), TlvField(0x10, u(2)))) }
        assertThrows(ProtocolException::class.java) { VersionExchange.reply(reply(TlvField(0x10, u(2)), TlvField(0x11, ByteArray(6)), TlvField(0x19, ByteArray(64)))) }
    }

    @Test fun envelopeRoundTripAndStatusQuery() {
        val e = EnvelopeCodec.decode(EnvelopeCodec.encode(18, "{\"a\":1}", 1001))
        assertEquals(18, e.type); assertEquals(1001L, e.sequence); assertEquals("{\"a\":1}", e.jsonText())
        val status = EnvelopeCodec.decode(Commands.generalStatusQuery())
        assertEquals(1, status.type); assertEquals("request_general_status", JSONObject(status.jsonText()).getString("cmd"))
        assertThrows(ProtocolException::class.java) { EnvelopeCodec.encode(0, "{}") }
        assertThrows(ProtocolException::class.java) { EnvelopeCodec.encode(1, "{") }
        assertThrows(ProtocolException::class.java) { EnvelopeCodec.decode(u(16, 1, 16, 2)) } // duplicate type
    }

    @Test fun commandsValidateInput() {
        val sid = "0".repeat(32)
        assertEquals("temporary", JSONObject(Commands.displayStart(sid)).getString("scope"))
        assertThrows(ProtocolException::class.java) { Commands.displayStart("wrong") }
        assertThrows(ProtocolException::class.java) { Commands.displayText(sid, "x".repeat(97)) }
        assertThrows(ProtocolException::class.java) { Commands.displayText(sid, "   ") }
        assertThrows(ProtocolException::class.java) { Commands.answerChunk("x".repeat(25), sid, "q", 1) }
        val text = "Navegação\n\"aspas\" \\ fim"
        assertEquals(text, JSONObject(Commands.displayText(sid, text)).getJSONObject("content").getString("source_transcript"))
    }

    @Test fun outboundAllowListExcludesFirmwareAndFileTransfer() {
        assertTrue(Business.outboundAllowed(13, 32)); assertTrue(Business.outboundAllowed(19, 5)); assertTrue(Business.outboundAllowed(15, 1))
        assertFalse(Business.outboundAllowed(9, 1))    // MARS_FOTA (firmware update)
        assertFalse(Business.outboundAllowed(18, 1))   // file transfer
        assertFalse(Business.outboundAllowed(15, 18))  // launcher writes other than status query
        assertFalse(Business.outboundAllowed(0x10, 1))
        assertNull(Business.OUTBOUND[9]); assertNull(Business.OUTBOUND[18])
    }

    @Test fun advertisementFilter() {
        val adv = u(3, 3, 0x1d, 0xb8) + byteArrayOf(7, 9) + "RayNeo".toByteArray()
        val info = Advertisement.inspect(adv)
        assertTrue(info.hasRayNeoService); assertEquals("RayNeo", info.localName); assertFalse(info.malformed)
        assertTrue(Advertisement.inspect(u(5, 3, 1)).malformed)
        assertTrue(Advertisement.candidateName("雷鸟 iO")); assertFalse(Advertisement.candidateName("Galaxy Buds"))
        assertEquals("RayNeo", Advertisement.displayName("Ray‮Neo\u0000"))
    }
}
