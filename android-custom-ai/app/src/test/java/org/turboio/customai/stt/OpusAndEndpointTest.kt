package org.turboio.customai.stt

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.turboio.customai.protocol.Hex
import java.util.Base64
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin

/**
 * Cross-implementation check: packets encoded AND decoded by the repository's reference libopus 1.5.2
 * (tools/opus_reference.c) must decode with our pure-Java decoder to (nearly) the same PCM.
 */
class OpusAndEndpointTest {
    @Test fun concentusMatchesReferenceLibopus() {
        val fixture = JSONObject(javaClass.classLoader!!.getResource("opus-reference.json")!!.readText())
        val packets = fixture.getJSONArray("packets")
        val reference = Base64.getDecoder().decode(fixture.getString("decodedPcmBase64"))
        val decoder = GlassesOpusDecoder()
        val ours = PcmBuffer(10)
        for (i in 0 until packets.length()) ours.append(decoder.decode(Hex.decode(packets.getString(i))))
        val mine = ours.bytes()
        assertEquals(reference.size, mine.size)
        var signal = 0.0; var error = 0.0
        for (i in reference.indices step 2) {
            val r = ((reference[i + 1].toInt() shl 8) or (reference[i].toInt() and 0xff)).toShort().toDouble()
            val m = ((mine[i + 1].toInt() shl 8) or (mine[i].toInt() and 0xff)).toShort().toDouble()
            signal += r * r; error += (r - m) * (r - m)
        }
        val snr = 10 * log10(signal / maxOf(error, 1e-9))
        println("SNR Concentus vs libopus ${fixture.getString("libopusVersion")}: %.1f dB".format(snr))
        assertTrue("SNR $snr dB", snr > 30)
    }

    @Test fun rejectsMalformedPackets() {
        val d = GlassesOpusDecoder()
        assertThrows(IllegalArgumentException::class.java) { d.decode(ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { d.decode(ByteArray(5000)) }
    }

    private fun tone(ms: Int, amp: Double) = ShortArray(ms * 16) { (amp * sin(2 * PI * 300 * it / 16000.0)).toInt().toShort() }

    @Test fun endPointerDetectsSpeechThenSilence() {
        val ep = EndPointer(silenceMs = 1000, maxMs = 15000)
        ep.feed(ShortArray(16 * 300))            // quiet lead-in
        assertEquals(EndPointer.State.SPEAKING, ep.feed(tone(800, 6000.0)))
        assertEquals(EndPointer.State.SPEAKING, ep.feed(ShortArray(16 * 500)))
        assertEquals(EndPointer.State.DONE_SPEECH, ep.feed(ShortArray(16 * 600)))
    }

    @Test fun endPointerSpeechFromFirstFrameAndLimits() {
        val ep = EndPointer(silenceMs = 800, maxMs = 3000)
        assertEquals(EndPointer.State.SPEAKING, ep.feed(tone(500, 6000.0)))   // user already speaking at start
        assertEquals(EndPointer.State.DONE_MAX, ep.feed(tone(3000, 6000.0)))
        val quiet = EndPointer(noSpeechMs = 2000)
        assertEquals(EndPointer.State.DONE_NO_SPEECH, quiet.feed(ShortArray(16 * 2100)))
    }

    @Test fun wavHeader() {
        val wav = Wav.pcm16Mono(ByteArray(3200))
        assertEquals("RIFF", String(wav, 0, 4)); assertEquals("WAVE", String(wav, 8, 4)); assertEquals(3244, wav.size)
    }

    @Test fun googleResponseParsing() {
        assertEquals("qual é a capital do Brasil",
            GoogleSpeechTranscriber.parseGoogle("""{"results":[{"alternatives":[{"transcript":"qual é a capital","confidence":0.9}]},{"alternatives":[{"transcript":"do Brasil"}]}]}"""))
        assertEquals("", GoogleSpeechTranscriber.parseGoogle("{}"))
    }
}
