package org.turboio.customai.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Byte-for-byte comparison against vectors produced by EXECUTING the HarmonyOS TypeScript sources
 * (tools/harmony-vectors.mjs → src/test/resources/harmony-vectors.json). Those sources are the ones the
 * repository reports as validated on a real RayNeo iO with original firmware.
 */
class HarmonyCrossTest {
    private val v = JSONObject(javaClass.classLoader!!.getResource("harmony-vectors.json")!!.readText())
    private val sid = "0123456789abcdef0123456789abcdef"
    private val random = Hex.decode("01020304")
    private val peerRandom = Hex.decode("09080706")
    private val phone = Hex.decode("001122334455")
    private val glasses = Hex.decode("aabbccddeeff")

    @Test fun frames() {
        val frames = v.getJSONArray("frames")
        for (i in 0 until frames.length()) {
            val f = frames.getJSONObject(i); val p = f.getJSONObject("packet")
            val packet = FramePacket(p.getInt("messageNumber"), p.getInt("flags"), if (p.isNull("address")) null else p.getInt("address"),
                Hex.decode(p.getString("slices")), p.getInt("businessID"), Hex.decode(p.getString("payload")))
            assertEquals(f.getString("encoded"), Hex.encode(FrameCodec.encode(packet)))
            assertEquals(packet, FrameCodec.decode(Hex.decode(f.getString("encoded"))))
        }
    }

    @Test fun authentication() {
        assertEquals(v.getString("guestProof"), Hex.encode(AuthCalculations.guestProof(random, phone, glasses)))
        assertEquals(v.getString("peerProof"), Hex.encode(AuthCalculations.guestProof(peerRandom, phone, glasses)))
        assertEquals(v.getString("guestSessionKey"), Hex.encode(AuthCalculations.guestSessionKey(random, peerRandom, phone, glasses)))
        assertEquals(v.getString("authRequest"), Hex.encode(Tlv.authRequest(random, AuthCalculations.guestProof(random, phone, glasses))))
        val request = VersionExchange.request(VersionRequest(phone, "Turbo IO".toByteArray(), "samsung".toByteArray(), "SM-S921B".toByteArray()))
        assertEquals(v.getString("versionRequest"), Hex.encode(request))
    }

    @Test fun businessCommands() {
        assertEquals(v.getString("generalStatusQuery"), Hex.encode(Commands.generalStatusQuery()))
        val envelopes = v.getJSONArray("envelopes")
        for (i in 0 until envelopes.length()) {
            val e = envelopes.getJSONObject(i)
            assertEquals(e.getString("encoded"), Hex.encode(EnvelopeCodec.encode(e.getInt("type"), e.getString("json"), e.getLong("sequence"))))
        }
        assertEquals(v.getString("displayStart"), Commands.displayStart(sid))
        assertEquals(v.getString("displayStop"), Commands.displayStop(sid))
        val texts = v.getJSONArray("displayText")
        for (i in 0 until texts.length()) {
            val t = texts.getJSONObject(i)
            assertEquals(t.getString("json"), Commands.displayText(sid, t.getString("text")))
        }
        val transcripts = v.getJSONArray("transcript")
        for (i in 0 until transcripts.length()) {
            val t = transcripts.getJSONObject(i)
            assertEquals(t.getString("json"), Commands.transcript(t.getString("text"), true))
        }
        assertEquals(v.getString("answerChunk"), Commands.answerChunk("Resposta curta: ação!", sid, "Qual é a capital", 1700000000123))
        val chunks = v.getJSONArray("chunks")
        for (i in 0 until chunks.length()) {
            val c = chunks.getJSONObject(i)
            val expected = c.getJSONArray("out").let { a -> (0 until a.length()).map { a.getString(it) } }
            assertEquals(expected, Commands.chunks(c.getString("text"), c.getInt("limit")))
        }
    }
}
