package org.turboio.customai.voice

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusEncoder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.turboio.customai.ble.BusinessListener
import org.turboio.customai.ble.GlassesPort
import org.turboio.customai.ble.Randomness
import org.turboio.customai.display.DisplaySettings
import org.turboio.customai.display.DisplayState
import org.turboio.customai.display.LensDisplay
import org.turboio.customai.protocol.Business
import org.turboio.customai.protocol.Envelope
import org.turboio.customai.protocol.EnvelopeCodec
import kotlin.math.PI
import kotlin.math.sin

class FakePort : GlassesPort {
    override var isReady = true
    val sent = ArrayList<Pair<Int, Envelope>>()
    private val listeners = ArrayList<BusinessListener>()
    override fun send(business: Int, envelope: ByteArray, done: (String?) -> Unit) {
        val e = EnvelopeCodec.decode(envelope)
        require(Business.outboundAllowed(business, e.type)) { "não permitido $business/${e.type}" }
        require(Business.contentAllowed(business, e.type, e.jsonText())) { "conteúdo recusado $business/${e.type}: ${e.jsonText()}" }
        sent.add(business to e); done(null)
    }
    override fun addBusinessListener(listener: BusinessListener) { listeners.add(listener) }
    fun glasses(business: Int, type: Int, json: String = "{}", binary: ByteArray = ByteArray(0)) {
        listeners.forEach { it.onBusiness(business, Envelope(type, 0, json.toByteArray(), binary)) }
    }
    fun trace(): List<String> = sent.map { (b, e) -> "$b/${e.type}" }
    /** File-transfer messages (business 18), checked by the same gate as the real session. */
    val files = ArrayList<ByteArray>()
    var fileError: String? = null
    override fun sendFile(payload: ByteArray, done: (String?) -> Unit) {
        require(org.turboio.customai.protocol.FileTransfer.outboundProblem(payload) == null) { "arquivo recusado" }
        fileError?.let { done(it); return }
        files.add(payload.copyOf()); done(null)
    }
    private val raws = ArrayList<(Int, ByteArray) -> Unit>()
    override fun addRawListener(listener: (business: Int, payload: ByteArray) -> Unit) { raws.add(listener) }
    /** A packet as it arrives from the glasses (envelope or not); envelopes also reach the business listeners. */
    fun raw(business: Int, payload: ByteArray) {
        raws.forEach { it(business, payload) }
        runCatching { EnvelopeCodec.decode(payload) }.getOrNull()?.let { e -> listeners.forEach { it.onBusiness(business, e) } }
    }
}

val fixedRandom = object : Randomness { override fun bytes(count: Int) = ByteArray(count) { 0x5a } }

/** Synthetic glasses microphone: Opus 16 kHz mono 20 ms packets (tone = "speech", then silence). */
fun opusPackets(speechMs: Int, silenceMs: Int): List<ByteArray> {
    val encoder = OpusEncoder(16000, 1, OpusApplication.OPUS_APPLICATION_VOIP)
    val out = ArrayList<ByteArray>(); var t = 0
    repeat((speechMs + silenceMs) / 20) { frame ->
        val pcm = ShortArray(320) { i ->
            val loud = frame * 20 < speechMs
            if (loud) (8000 * sin(2 * PI * 440 * (t + i) / 16000.0)).toInt().toShort() else 0
        }
        t += 320
        val buffer = ByteArray(1275)
        val n = encoder.encode(pcm, 0, 320, buffer, 0, buffer.size)
        out.add(buffer.copyOf(n))
    }
    return out
}

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceAndLensTest {
    private fun TestScope.assistant(port: FakePort, glassesMic: Boolean, transcript: String = "Qual é a capital do Brasil?",
                                    reply: String = "A capital do Brasil é Brasília, inaugurada em 1960."): VoiceAssistant {
        val input = object : SpeechInput {
            override val usesGlassesMic = glassesMic
            override suspend fun transcribe(pcm: ByteArray): String { assertTrue(pcm.size > 16000); return transcript }
            override suspend fun listenOnPhone(onPartial: (String) -> Unit): String { onPartial("Qual é"); return transcript }
        }
        return VoiceAssistant(port, this, fixedRandom, { input }, { reply }, { VoiceSettings(displaySeconds = 10) })
    }

    @Test fun glassesMicTurnFollowsOriginalFirmwareSequence() = runTest {
        val port = FakePort()
        val voice = assistant(port, glassesMic = true)
        port.glasses(13, 1); runCurrent()
        assertEquals(listOf("13/2"), port.trace())
        assertEquals("{\"rc\":1}", port.sent[0].second.jsonText())
        for (packet in opusPackets(speechMs = 1500, silenceMs = 1600)) { port.glasses(13, 3, binary = packet); runCurrent() }
        advanceTimeBy(100); runCurrent()
        val trace = port.trace()
        // rc:1, rc:2 (capture end), final transcript, rc:2, answer chunks…, EOF
        assertEquals(listOf("13/2", "13/2", "13/5", "13/2"), trace.take(4))
        val chunks = port.sent.filter { it.first == 13 && it.second.type == 32 }
        assertTrue(chunks.isNotEmpty())
        val joined = chunks.joinToString("") { JSONObject(it.second.jsonText()).getJSONObject("answer").getString("text") }
        assertEquals("A capital do Brasil é Brasília, inaugurada em 1960.", joined)
        assertTrue(chunks.all { JSONObject(it.second.jsonText()).getJSONObject("answer").getString("text").length <= 24 })
        assertEquals("13/12", trace.last())
        assertTrue(JSONObject(port.sent.first { it.second.type == 5 }.second.jsonText()).getBoolean("final"))
        assertEquals("exibindo", voice.state.value.phase)
        advanceTimeBy(11_000); runCurrent()
        assertEquals(listOf("13/2", "13/7"), port.trace().takeLast(2))
        assertEquals(1, voice.state.value.rounds)
    }

    @Test fun phoneMicTurnMirrorsPartialTranscript() = runTest {
        val port = FakePort()
        assistant(port, glassesMic = false)
        port.glasses(13, 1); runCurrent(); advanceTimeBy(100); runCurrent()
        val trace = port.trace()
        assertEquals("13/5", trace.first())                 // transcript; the glasses recorder is never started
        assertTrue(trace.none { it == "13/2" && port.sent[trace.indexOf(it)].second.jsonText() == "{\"rc\":1}" })
        assertTrue(trace.contains("13/32")); assertEquals("13/12", trace.last())
        // A partial that arrives after the final transcript is dropped (never overwrites the answer).
        assertEquals(1, port.sent.count { it.second.type == 5 })
    }

    @Test fun noAudioFromGlassesEndsTurnCleanly() = runTest {
        val port = FakePort()
        val voice = assistant(port, glassesMic = true)
        port.glasses(13, 1); runCurrent()
        advanceTimeBy(6500); runCurrent()
        assertTrue(voice.state.value.lastError.contains("Nenhum áudio"))
        assertEquals(listOf("13/2", "13/2", "13/7"), port.trace())
    }

    @Test fun silenceOnlyIsNotSentToTheModel() = runTest {
        val port = FakePort()
        val voice = assistant(port, glassesMic = true)
        port.glasses(13, 1); runCurrent()
        for (packet in opusPackets(speechMs = 0, silenceMs = 7400)) { port.glasses(13, 3, binary = packet); runCurrent() }
        advanceTimeBy(100); runCurrent()
        assertTrue(voice.state.value.lastError.contains("Não ouvi"))
        assertTrue(port.trace().none { it == "13/32" })
    }

    @Test fun closeCommandEndsWithoutQueryingModel() = runTest {
        val port = FakePort()
        var asked = false
        val input = object : SpeechInput {
            override val usesGlassesMic = false
            override suspend fun listenOnPhone(onPartial: (String) -> Unit) = "Pode fechar"
        }
        VoiceAssistant(port, this, fixedRandom, { input }, { asked = true; "x" }, { VoiceSettings() })
        port.glasses(13, 1); runCurrent(); advanceTimeBy(100); runCurrent()
        assertTrue(!asked); assertEquals("13/7", port.trace().last())
    }

    @Test fun glassesExitCancelsRound() = runTest {
        val port = FakePort()
        val voice = assistant(port, glassesMic = true)
        port.glasses(13, 1); runCurrent()
        port.glasses(13, 8); runCurrent()
        assertEquals("aguardando", voice.state.value.phase)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(listOf("13/2"), port.trace())     // nothing else sent after the user closed it
    }

    @Test fun lensDisplayOpensPagesAndCloses() = runTest {
        val port = FakePort()
        val display = LensDisplay(port, this, fixedRandom)
        val sid = "5a".repeat(16)
        var result: String? = "pending"
        display.show("**Brasília** é a capital do Brasil. " + "Ela foi inaugurada em 1960 e planejada por Lúcio Costa e Oscar Niemeyer no Planalto Central.",
            DisplaySettings(pageSeconds = 3, lingerSeconds = 4)) { result = it }
        runCurrent()
        assertEquals(listOf("19/7"), port.trace())
        port.glasses(19, 8, "{\"sid\":\"$sid\",\"code\":1}"); runCurrent()
        assertTrue(display.state.value is DisplayState.Opening)            // short pause while the glasses open Subtitles
        advanceTimeBy(500); runCurrent()
        assertTrue(display.state.value is DisplayState.Showing)
        advanceTimeBy(20_000); runCurrent()
        assertEquals(null, result)
        val first = JSONObject(port.sent.first { it.second.type == 5 }.second.jsonText())
        assertEquals(0, first.getInt("mode")); assertEquals(1, first.getInt("status"))   // official-app template (1.0.5.5)
        val texts = port.sent.filter { it.second.type == 5 }.map { JSONObject(it.second.jsonText()).getJSONObject("content").getString("source_transcript") }
        assertTrue(texts.size >= 2); assertTrue(texts.all { it.length <= 90 }); assertTrue(texts[0].startsWith("Brasília é"))
        assertEquals("19/3", port.trace().last())
        assertTrue(display.state.value is DisplayState.Done)
    }

    @Test fun lensDisplayReportsMissingAck() = runTest {
        val port = FakePort()
        val display = LensDisplay(port, this, fixedRandom)
        var result: String? = null
        display.show("Olá", DisplaySettings()) { result = it }
        runCurrent(); advanceTimeBy(10_500); runCurrent()
        assertTrue(result!!.contains("não confirmaram"))
        assertEquals(listOf("19/7", "19/3"), port.trace())
    }
}
