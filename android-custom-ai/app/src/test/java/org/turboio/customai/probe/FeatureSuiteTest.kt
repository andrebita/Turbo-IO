package org.turboio.customai.probe

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.turboio.customai.protocol.EnvelopeCodec
import org.turboio.customai.protocol.FileMessage
import org.turboio.customai.protocol.FileTransfer
import org.turboio.customai.stt.DeepgramSpeechTranscriber

@OptIn(ExperimentalCoroutinesApi::class)
class FeatureSuiteTest {
    private class MemStore : SuiteStore { var v: String? = null; override fun load() = v; override fun save(json: String) { v = json } }

    @Test fun stepRunsCapturesTrafficAndWaitsForVerdict() = runTest {
        val store = MemStore(); val rec = TrafficRecorder({ testScheduler.currentTime })
        val saved = ArrayList<Pair<FeatureResult, List<String>>>()
        val steps = listOf(
            FeatureStep("F1", "Um", "faz", "viu?") { run ->
                rec.outbound(19, EnvelopeCodec.encode(7, """{"sid":"x"}""")); run.countdown(2, "olhe"); "exibido"
            },
            FeatureStep("F2", "Dois", "faz", "viu?") { _ -> error("quebrou") },
            FeatureStep("F3", "Três", "faz", "viu?", timeoutSeconds = 5) { _ -> delay(60_000); "nunca" },
        )
        val suite = FeatureSuite(steps, this, store, rec, { _, r, t, _ -> saved.add(r to t); "pasta-${r.id}" }) { testScheduler.currentTime }
        suite.begin()
        assertEquals(null, suite.start()); runCurrent()
        assertEquals(FeaturePhase.RUNNING, suite.state.value.phase)
        assertEquals("olhe · 2 s", suite.state.value.cue)
        advanceTimeBy(2500); runCurrent()
        assertEquals(FeaturePhase.VERDICT, suite.state.value.phase); assertEquals("exibido", suite.state.value.outcome)
        rec.outbound(19, EnvelopeCodec.encode(5, "{}"))                              // after the step: not captured
        suite.verdict(true, " apareceu ")
        assertEquals(1, saved.size); assertEquals("funcionou", saved[0].first.verdict); assertEquals("apareceu", saved[0].first.note)
        assertEquals(1, saved[0].second.size); assertTrue(saved[0].second[0].contains("→ 19/7"))
        // A failing step reports the error; repeat runs it again; skip moves on without saving.
        suite.start(); runCurrent()
        assertEquals("Erro: quebrou", suite.state.value.outcome)
        suite.repeatStep(); assertEquals(FeaturePhase.IDLE, suite.state.value.phase); assertEquals(1, suite.state.value.index)
        suite.skip(); assertEquals(2, suite.state.value.index); assertEquals(1, saved.size)
        // Timeout.
        suite.start(); advanceTimeBy(6000); runCurrent()
        assertTrue(suite.state.value.outcome.startsWith("Tempo esgotado"))
        suite.verdict(false)
        assertEquals(listOf("funcionou", "pulado", "não funcionou"), suite.state.value.results.map { it.verdict })
        assertTrue(suite.summary().contains("[F3] Três: NÃO FUNCIONOU"))
        // Progress survives a restart.
        val again = FeatureSuite(steps, this, store, rec, { _, _, _, _ -> "" })
        assertEquals(3, again.state.value.index); assertEquals(3, again.state.value.results.size); assertTrue(again.state.value.active)
    }

    @Test fun stopAndEndNeverLeaveAStaleVerdict() = runTest {
        val rec = TrafficRecorder({ testScheduler.currentTime })
        val steps = listOf(FeatureStep("F1", "Um", "faz", "viu?") { _ -> delay(60_000); "fim" })
        val suite = FeatureSuite(steps, this, MemStore(), rec, { _, _, _, _ -> "" }) { testScheduler.currentTime }
        suite.begin(); suite.start(); runCurrent()
        suite.stop(); runCurrent()
        assertEquals("Etapa interrompida pelo usuário", suite.state.value.outcome)
        suite.repeatStep(); suite.start(); runCurrent()
        suite.end(); runCurrent()
        assertFalse(suite.state.value.active); assertEquals(FeaturePhase.IDLE, suite.state.value.phase); assertFalse(rec.recording)
    }

    @Test fun trafficIsRedacted() {
        val other = TrafficRecorder.describe(21, EnvelopeCodec.encode(1, """{"title":"Banco","content":"código 123456"}"""))
        assertTrue(other.contains("conteúdo omitido")); assertFalse(other.contains("123456"))
        val card = TrafficRecorder.describe(21, EnvelopeCodec.encode(2, """{"notificationUID":"5","title":"Marina","content":"segredo"}"""))
        assertFalse(card.contains("Marina")); assertFalse(card.contains("segredo")); assertTrue(card.contains("«7 caracteres»"))
        val uuid = "0a0b0c0d-0e0f-4011-8213-141516171819"
        val chunk = TrafficRecorder.describe(18, FileTransfer.encode(FileMessage(4, uuid, start = 0, size = 5, data = "texto".toByteArray())))
        assertEquals("18 arquivo tipo=4 pedaço=0 início=0 tamanho=5", chunk)
        assertTrue(TrafficRecorder.describe(13, EnvelopeCodec.encode(5, "{}".toByteArray())).startsWith("13/5"))
        assertEquals("15 bruto 3 B", TrafficRecorder.describe(15, byteArrayOf(1, 2, 3)))
        // Audio frame streams collapse.
        var t = 0L
        val rec = TrafficRecorder({ t })
        rec.start()
        val audio = byteArrayOf(8, 1, 16, 99, 26, 2, 123, 125, 34, 2, 1, 2)   // 13/99 envelope: json "{}" + 2 binary bytes
        repeat(3) { rec.inbound(13, audio); t += 60 }
        val lines = rec.stop()
        assertEquals(1, lines.size); assertTrue(lines[0], lines[0].endsWith("(+2 iguais)"))
    }

    @Test fun deepgramVoiceRequestAndParse() {
        val wav = DeepgramSpeechTranscriber.wav(ByteArray(3200))
        assertEquals(3244, wav.size); assertEquals("RIFF", String(wav, 0, 4)); assertEquals("WAVE", String(wav, 8, 4))
        assertEquals(16000, (wav[24].toInt() and 0xff) or ((wav[25].toInt() and 0xff) shl 8))
        assertEquals("Qual é a capital da França?", DeepgramSpeechTranscriber.parseDeepgram(
            """{"results":{"channels":[{"alternatives":[{"transcript":" Qual é a capital da França? ","confidence":0.98}]}]}}"""))
        assertEquals("", DeepgramSpeechTranscriber.parseDeepgram("{}"))
    }

    @Test fun geminiTranscriptParse() {
        assertEquals("Qual é a capital da França?", org.turboio.customai.stt.GeminiSpeechTranscriber.parseGemini(
            """{"candidates":[{"content":{"parts":[{"text":"pensando…","thought":true},{"text":" Qual é a capital da França? "}]}}]}"""))
        assertEquals("", org.turboio.customai.stt.GeminiSpeechTranscriber.parseGemini("{}"))
    }
}
