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
import org.junit.Assert.assertFalse
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
    /** File-transfer messages (business 17 or 18, in [fileBusinesses]), checked by the same gate as the real session. */
    val files = ArrayList<ByteArray>()
    val fileBusinesses = ArrayList<Int>()
    var fileError: String? = null
    override fun sendFile(payload: ByteArray, business: Int, done: (String?) -> Unit) {
        require(org.turboio.customai.protocol.FileTransfer.outboundProblem(payload, business) == null) { "arquivo recusado" }
        fileError?.let { done(it); return }
        files.add(payload.copyOf()); fileBusinesses.add(business); done(null)
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
        advanceTimeBy(1300); runCurrent()
        assertTrue(display.state.value is DisplayState.Showing)
        advanceTimeBy(20_000); runCurrent()
        assertEquals(null, result)
        val first = JSONObject(port.sent.first { it.second.type == 5 }.second.jsonText())
        assertEquals(3, first.getInt("mode")); assertEquals(0, first.getInt("status"))   // the format that drew text on 1.0.5.5
        val texts = port.sent.filter { it.second.type == 5 }.map { JSONObject(it.second.jsonText()).getJSONObject("content").getString("source_transcript") }
        assertTrue(texts.size >= 2); assertTrue(texts.all { it.length <= 90 }); assertTrue(texts[0].startsWith("Brasília é"))
        // Each page is re-sent every 2 s while shown (3 s page → 2 sends, 4 s linger → 2 sends).
        assertTrue(texts.size >= 4); assertEquals(texts[0], texts[1])
        assertEquals("19/3", port.trace().last())
        assertTrue(display.state.value is DisplayState.Done)
    }

    /** Official app 1.0.7 caption message: status 0 while forming, then 1 (final), with every content field. */
    @Test fun lensDisplayCaptionFormatGoesFromIntermediateToFinal() = runTest {
        val port = FakePort()
        val display = LensDisplay(port, this, fixedRandom)
        val sid = "5a".repeat(16)
        display.show("Olá, tudo bem?", DisplaySettings(lingerSeconds = 4, textFormat = "caption"))
        runCurrent(); port.glasses(19, 8, "{\"sid\":\"$sid\",\"code\":1}"); advanceTimeBy(10_000); runCurrent()
        val sends = port.sent.filter { it.second.type == 5 }.map { JSONObject(it.second.jsonText()) }
        assertEquals(listOf(0, 1), sends.map { it.getInt("status") })
        assertTrue(sends.all { it.getInt("mode") == 3 && it.getString("sid") == sid })
        val content = sends[1].getJSONObject("content")
        assertEquals("Olá, tudo bem?", content.getString("source_transcript")); assertEquals("", content.getString("target_translation"))
        assertEquals(1, content.getInt("label")); assertEquals("", content.getString("keyword_info"))
        assertEquals(1, JSONObject(org.turboio.customai.protocol.Commands.displayText(sid, "x", "classic")).getInt("mode"))
    }

    /** Official phone-started session: 19/7 → 19/8, 19/1 → glasses 19/2 (code 1, their sid) → 19/5 with that sid, mode 1. */
    @Test fun lensDisplaySessionFormatStartsCaptionSession() = runTest {
        val port = FakePort()
        val display = LensDisplay(port, this, fixedRandom)
        val sid = "5a".repeat(16); val theirs = "0123456789abcdef".repeat(2)
        display.show("Olá", DisplaySettings(lingerSeconds = 2, textFormat = "session_classic"))
        runCurrent()
        assertEquals(listOf("19/1"), port.trace())                   // no 19/7 preview: that one shows the Subtitles intro
        val start = JSONObject(port.sent[0].second.jsonText())
        assertEquals(sid, start.getString("sid"))
        assertEquals("phone", start.getString("audio_source")); assertEquals("classic", start.getJSONObject("settings").getString("mode"))
        port.glasses(19, 2, "{\"sid\":\"$theirs\",\"code\":1,\"final_settings\":{}}"); runCurrent()
        advanceTimeBy(5_000); runCurrent()
        val text = port.sent.filter { it.second.type == 5 }.map { JSONObject(it.second.jsonText()) }
        assertTrue(text.isNotEmpty()); assertTrue(text.all { it.getString("sid") == theirs && it.getInt("mode") == 1 })
        val stop = JSONObject(port.sent.last().second.jsonText())
        assertEquals(theirs, stop.getString("sid")); assertEquals(2, stop.getInt("reason_code"))   // official normal end of a session
        assertFalse(org.turboio.customai.protocol.Business.contentAllowed(19, 1, "{\"sid\":\"$sid\",\"trigger\":1}"))
    }

    /** Just after the reason-2 close the glasses may still be busy (code 4): one retry, then the preview path (19/7, reason 10). */
    @Test fun lensDisplayRetriesBusyStartThenFallsBack() = runTest {
        val port = FakePort()
        val display = LensDisplay(port, this, fixedRandom)
        val sid = "5a".repeat(16)
        display.show("Um", DisplaySettings(lingerSeconds = 2, textFormat = "session_classic")); runCurrent()
        port.glasses(19, 2, "{\"sid\":\"$sid\",\"code\":4,\"text\":\"not_idle_state\"}"); runCurrent()
        advanceTimeBy(1_100); runCurrent()
        assertEquals(listOf("19/1", "19/1"), port.trace())
        port.glasses(19, 2, "{\"sid\":\"$sid\",\"code\":1}"); advanceTimeBy(5_000); runCurrent()
        assertEquals(1, JSONObject(port.sent.first { it.second.type == 5 }.second.jsonText()).getInt("mode"))
        port.sent.clear()
        display.show("Dois", DisplaySettings(lingerSeconds = 2, textFormat = "session_classic")); runCurrent()
        port.glasses(19, 2, "{\"sid\":\"$sid\",\"code\":4}"); advanceTimeBy(1_100); runCurrent()
        port.glasses(19, 2, "{\"sid\":\"$sid\",\"code\":4}"); runCurrent()
        assertEquals("19/7", port.trace().last())
        port.glasses(19, 8, "{\"sid\":\"$sid\",\"code\":1}"); advanceTimeBy(5_000); runCurrent()
        assertEquals(3, JSONObject(port.sent.first { it.second.type == 5 }.second.jsonText()).getInt("mode"))
        assertEquals(10, JSONObject(port.sent.last().second.jsonText()).getInt("reason_code"))
    }

    /** A non-standard size/position is saved with the persistent 19/7 (global, no preview) before the session. */
    @Test fun lensDisplaySavesLayoutWithoutPreview() = runTest {
        val port = FakePort()
        val display = LensDisplay(port, this, fixedRandom)
        val sid = "5a".repeat(16)
        display.show("Grande", DisplaySettings(lingerSeconds = 2, fontSize = 3, position = "top", maxLines = 3, textFormat = "session_classic")); runCurrent()
        val cfg = JSONObject(port.sent[0].second.jsonText())
        assertEquals("global", cfg.getString("scope")); assertFalse(cfg.getJSONObject("config").getBoolean("is_display")); assertEquals(3, cfg.getJSONObject("config").getInt("font_size"))
        port.glasses(19, 8, "{\"sid\":\"$sid\",\"code\":1}"); runCurrent()
        assertEquals(listOf("19/7", "19/1"), port.trace())
    }

    @Test fun voiceSpeechStatusGate() {
        assertTrue(org.turboio.customai.protocol.Business.contentAllowed(13, 9, org.turboio.customai.protocol.Commands.SPEECH_THINKING))
        assertFalse(org.turboio.customai.protocol.Business.contentAllowed(13, 9, "{\"rc\":4}"))
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
