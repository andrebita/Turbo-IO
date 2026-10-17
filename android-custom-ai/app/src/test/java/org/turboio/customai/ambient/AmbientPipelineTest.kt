package org.turboio.customai.ambient

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusEncoder
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.turboio.customai.journal.EntryKind
import org.turboio.customai.journal.JdbcSqlDb
import org.turboio.customai.journal.JournalStore
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

/** Synthetic sources: "speech" = loud harmonic tone, silence = zeros; 20 ms Opus frames like the real ones. */
object Synth {
    fun frames(pattern: List<Pair<Boolean, Int>>, channels: Int = 1, loud: Double = 6000.0, quietSecond: Boolean = false): List<ByteArray> {
        val enc = OpusEncoder(16000, channels, OpusApplication.OPUS_APPLICATION_VOIP).apply { setBitrate(if (channels == 2) 96000 else 24000) }
        val out = ArrayList<ByteArray>(); var t = 0
        for ((speech, ms) in pattern) repeat(ms / 20) {
            val pcm = ShortArray(320 * channels) { i ->
                val n = t + i / channels
                val v = if (speech) loud * (sin(2 * PI * 180 * n / 16000.0) + 0.5 * sin(2 * PI * 360 * n / 16000.0)) / 1.5 else 0.0
                if (channels == 2 && quietSecond && i % 2 == 1) (v * 0.02).toInt().toShort() else v.toInt().toShort()
            }
            t += 320
            val buf = ByteArray(1275); out.add(buf.copyOf(enc.encode(pcm, 0, 320, buf, 0, buf.size)))
        }
        return out
    }
}

class TestSource(override val id: String = "phone_mic", override val providesWearer: Boolean = false, override val headMounted: Boolean = false) : AudioSource {
    override fun start(sink: FrameSink) {}
    override fun stop() {}
}

class ScriptedTranscriber(val reply: (ChunkPlan?, Long) -> ChunkTranscript) : AmbientTranscriber {
    var calls = 0; var lastOgg = ByteArray(0); var fail: TranscriptionException? = null; var currentPlan: ChunkPlan? = null
    override suspend fun transcribe(oggOpus: ByteArray, audioMs: Long): ChunkTranscript {
        calls++; lastOgg = oggOpus; fail?.let { throw it }
        return reply(currentPlan, audioMs)
    }
}

class AmbientPipelineTest {
    @get:Rule val tmp = TemporaryFolder()

    // ------------------------------------------------------------------ chunk planning
    private fun flags(vararg runs: Pair<Boolean, Int>): BooleanArray {
        val list = ArrayList<Boolean>(); runs.forEach { (s, ms) -> repeat(ms / 20) { list.add(s) } }; return list.toBooleanArray()
    }

    @Test fun plannerMergesPadsAndDropsNoise() {
        val f = flags(false to 2000, true to 3000, false to 1000, true to 2000, false to 5000, true to 400, false to 3000)
        val spans = ChunkPlanner.spans(f, 20, ChunkPolicy.DEEPGRAM)
        assertEquals(1, spans.size)                                   // 1 s gap merged; 400 ms blip dropped
        assertEquals((2000 - 300) / 20, spans[0].start); assertEquals((2000 + 6000 + 300) / 20, spans[0].end)
        val plans = ChunkPlanner.plan(f, 20, ChunkPolicy.DEEPGRAM)
        assertEquals(1, plans.size); assertEquals(6600L, plans[0].audioMs)   // silence before/after never billed
    }

    @Test fun plannerPacksSpansWithSeparatorsUpToLimit() {
        val runs = ArrayList<Pair<Boolean, Int>>(); repeat(10) { runs.add(true to 20_000); runs.add(false to 10_000) }
        val f = flags(*runs.toTypedArray())
        val whisper = ChunkPlanner.plan(f, 20, ChunkPolicy.WHISPER)
        assertTrue(whisper.all { it.audioMs <= 60_000 }); assertEquals(5, whisper.size)          // two 20.6 s spans per 60 s chunk
        val deepgram = ChunkPlanner.plan(f, 20, ChunkPolicy.DEEPGRAM)
        assertEquals(1, deepgram.size)
        // speech + 300 ms pads (none before t=0) + 400 ms separators; the 10 s silences are never billed
        assertEquals(10 * 20_600L - 300 + 9 * 400L, deepgram[0].audioMs)
        val p = deepgram[0].pieces
        assertEquals(p[0].frameStart, ChunkPlanner.frameAt(deepgram[0], 0))
        assertEquals(p[1].frameStart + 50, ChunkPlanner.frameAt(deepgram[0], p[1].chunkStartMs + 1000))
    }

    @Test fun plannerCutsLongSpeechAtTheLongestPauseOrForcesWithOverlap() {
        // 90 s of talk with short pauses; the 1.2 s pause at 45 s is the longest after the 30 s search point.
        val f = flags(true to 35_000, false to 600, true to 10_000, false to 1200, true to 43_000)
        val spans = ChunkPlanner.spans(f, 20, ChunkPolicy.WHISPER)
        assertEquals(2, spans.size); assertFalse(spans.any { it.forced })
        assertTrue(spans[0].end * 20 in 45_600..47_000)
        // Continuous speech: forced cut with 1 s overlap.
        val forced = ChunkPlanner.spans(flags(true to 150_000), 20, ChunkPolicy.WHISPER)
        assertTrue(forced.drop(1).all { it.forced })
        assertEquals((forced[0].end - forced[1].start) * 20, 1000)
        assertTrue(ChunkPlanner.plan(flags(true to 150_000), 20, ChunkPolicy.WHISPER).all { it.audioMs <= 60_000 })
    }

    // ------------------------------------------------------------------ Ogg Opus
    @Test fun oggOpusIsValidForAnIndependentDecoder() {
        val pcm = ShortArray(16000 * 3) { (5000 * sin(2 * PI * 220 * it / 16000.0)).toInt().toShort() }
        val ogg = OpusFile.encode(pcm)
        assertEquals("OggS", String(ogg, 0, 4)); assertTrue(ogg.size in 6_000..16_000)      // ~24 kbit/s
        val file = tmp.newFile("t.ogg").apply { writeBytes(ogg) }
        assumeTrue(File("/usr/bin/ffprobe").exists())
        val probe = ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "stream=codec_name,channels:format=duration", "-of", "json", file.path)
            .redirectErrorStream(true).start().let { it.waitFor(30, TimeUnit.SECONDS); it.inputStream.bufferedReader().readText() }
        val json = JSONObject(probe)
        assertEquals("opus", json.getJSONArray("streams").getJSONObject(0).getString("codec_name"))
        assertEquals(1, json.getJSONArray("streams").getJSONObject(0).getInt("channels"))
        assertEquals(3.0, json.getJSONObject("format").getString("duration").toDouble(), 0.05)
        val decode = ProcessBuilder("ffmpeg", "-v", "error", "-err_detect", "crccheck+explode", "-i", file.path, "-f", "null", "-")
            .redirectErrorStream(true).start()
        decode.waitFor(30, TimeUnit.SECONDS)
        assertEquals("ffmpeg (CRC/estrutura): " + decode.inputStream.bufferedReader().readText(), 0, decode.exitValue())
    }

    // ------------------------------------------------------------------ ingest + segments
    private fun ingestPattern(root: File, frames: List<ByteArray>, source: AudioSource, channels: Int = 1, targetMs: Long = 10_000,
                              wearer: (Int) -> Boolean = { false }): List<Pair<File, SegmentManifest>> {
        val sealed = ArrayList<Pair<File, SegmentManifest>>()
        val ingest = AmbientIngest(root, source, { d, m -> sealed.add(d to m) }, targetMs = targetMs, hardMaxMs = targetMs * 3 / 2)
        var wall = 1_760_000_000_000L
        frames.chunked(50).forEachIndexed { b, batch ->
            wall += batch.size * 20L
            ingest.frames(OpusBatch(batch, channels, 20, wall, wearerVoice = BooleanArray(batch.size) { wearer(b * 50 + it) }))
        }
        ingest.seal("stop")
        return sealed
    }

    @Test fun ingestCutsSegmentsAtPausesAndPreservesFramesAndTime() {
        val pattern = listOf(true to 8000, false to 300, true to 4000, false to 1000, true to 6000, false to 1000)
        val frames = Synth.frames(pattern)
        val sealed = ingestPattern(tmp.newFolder(), frames, TestSource())
        assertEquals(2, sealed.size)
        val first = SegmentReader(sealed[0].first)
        assertTrue(first.manifest.sealed); assertEquals("pause", first.manifest.sealReason)
        val firstMs = first.manifest.frames * 20L
        assertTrue("cut after the 10 s target inside the 1 s pause, not in the 300 ms one: $firstMs", firstMs in 12_700..13_400)
        assertEquals(frames.size, sealed.sumOf { it.second.frames })
        assertTrue(first.frames().zip(frames).all { (a, b) -> a.contentEquals(b) })       // original bytes kept
        val (f, levels) = first.flags()
        assertTrue(f.take(300).count { it.toInt() and FrameFlags.SPEECH != 0 } > 280); assertTrue(levels[100] > 2000)
        assertEquals(1_760_000_000_000L, first.manifest.wallAt(0))
        assertEquals(sealed[1].second.startedWall, first.manifest.wallAt(first.manifest.frames))
    }

    @Test fun silenceOnlyProducesNoChunks() {
        val sealed = ingestPattern(tmp.newFolder(), Synth.frames(listOf(false to 6000)), TestSource())
        val (f, _) = SegmentReader(sealed[0].first).flags()
        assertTrue(ChunkPlanner.plan(BooleanArray(f.size) { f[it].toInt() and FrameFlags.SPEECH != 0 }, 20, ChunkPolicy.DEEPGRAM).isEmpty())
    }

    // ------------------------------------------------------------------ speakers
    private fun manifest(providesWearer: Boolean, headMounted: Boolean) =
        SegmentManifest("s", "lifelog", 2, 20, providesWearer, headMounted, 1_000_000L)

    @Test fun wearerFromVpuThenProximityElseUnknown() {
        val plan = ChunkPlan(listOf(Piece(0, 0, 500)), 20, false)
        val words = (0 until 10).map { Word(it * 1000L, it * 1000L + 400, "w$it", if (it % 2 == 0) 0 else 1) }
        val vpu = ByteArray(500) { f -> if ((f * 20 / 1000) % 2 == 1) FrameFlags.WEARER.toByte() else 0 }   // speaker 1 on VPU
        val levels = IntArray(500) { f -> if ((f * 20 / 1000) % 2 == 0) 4000 else 1500 }               // speaker 0 louder
        val byVpu = Speakers.attribute(words, plan, vpu, levels, manifest(providesWearer = true, headMounted = true))
        assertEquals(1, byVpu.wearer); assertEquals("vpu", byVpu.method)
        val byEnergy = Speakers.attribute(words, plan, ByteArray(500), levels, manifest(providesWearer = false, headMounted = true))
        assertEquals(0, byEnergy.wearer); assertEquals("proximidade", byEnergy.method)
        assertNull(Speakers.attribute(words, plan, ByteArray(500), IntArray(500) { 2000 }, manifest(false, true)).wearer)   // ambiguous
        assertNull(Speakers.attribute(words, plan, ByteArray(500), levels, manifest(false, headMounted = false)).wearer)    // phone mic
        // VPU on for everyone (ambiguous) → falls back to proximity instead of giving up.
        val allVpu = ByteArray(500) { FrameFlags.WEARER.toByte() }
        val fallback = Speakers.attribute(words, plan, allVpu, levels, manifest(providesWearer = true, headMounted = true))
        assertEquals(0, fallback.wearer); assertEquals("vpu-ambiguo→proximidade", fallback.method)
        val u = Speakers.utterances(words, byVpu, plan, manifest(true, true))
        assertEquals(10, u.size)
        assertEquals("Pessoa A" to "other", u[0].label to u[0].speaker); assertEquals("Você" to "wearer", u[1].label to u[1].speaker)
        assertEquals(1_000_000L, u[0].startWall)
    }

    @Test fun utterancesGroupWordsAndHallucinationsAreDropped() {
        val plan = ChunkPlan(listOf(Piece(0, 0, 1000)), 20, false)
        val words = listOf(Word(0, 300, "Vamos", 0), Word(320, 600, "fechar", 0), Word(620, 900, "amanhã.", 0), Word(3000, 3300, "Ok", 0))
        val u = Speakers.utterances(words, Speakers.Attribution(null, "x", 0.0), plan, manifest(false, false))
        assertEquals(listOf("Vamos fechar amanhã.", "Ok"), u.map { it.text })        // split on 2.1 s pause
        assertTrue(Speakers.hallucination("Legendas pela comunidade Amara.org"))
        assertTrue(Speakers.hallucination("Obrigado por assistir!"))
        assertFalse(Speakers.hallucination("Obrigado pela reunião de hoje"))
    }

    // ------------------------------------------------------------------ providers
    @Test fun deepgramRequestAndResponse() = runBlocking {
        var url = ""; var headers = emptyMap<String, String>()
        val http = RawHttp { u, h, _, _ -> url = u; headers = h; 200 to javaClass.classLoader!!.getResource("deepgram-response.json")!!.readText() }
        val t = DeepgramTranscriber("dg-secret-key", http = http).transcribe(byteArrayOf(1, 2, 3), 5000)
        assertTrue(url.startsWith("https://api.deepgram.com/v1/listen?model=nova-3&language=pt-BR"))
        assertTrue(url.contains("diarize=true") && url.contains("smart_format=true"))
        assertEquals("Token dg-secret-key", headers["Authorization"]); assertEquals("audio/ogg", headers["Content-Type"])
        assertEquals(listOf("Bom", "dia,", "tudo", "bem?", "Tudo", "ótimo."), t.words.map { it.text })
        assertEquals(listOf(0, 0, 0, 0, 1, 1), t.words.map { it.speaker })
        assertEquals(1840L, t.words[4].startMs)
        val auth = runCatching { DeepgramTranscriber("x", http = { _, _, _, _ -> 401 to """{"err_code":"INVALID_AUTH","err_msg":"Invalid credentials."}""" }).transcribe(ByteArray(1), 1) }.exceptionOrNull()
        assertTrue(auth is TranscriptionException && auth.permanent && auth.message!!.contains("chave inválida"))
        val busy = runCatching { DeepgramTranscriber("x", http = { _, _, _, _ -> 429 to "{}" }).transcribe(ByteArray(1), 1) }.exceptionOrNull()
        assertTrue(busy is TranscriptionException && !busy.permanent)
    }

    @Test fun openAiAmbientParsing() {
        val verbose = OpenAiAmbientTranscriber.parse("""{"text":"a b","segments":[{"start":0.0,"end":1.5,"text":" a"},{"start":2.0,"end":3.0,"text":" b"}]}""", "whisper-1", 3000)
        assertEquals(listOf(Word(0, 1500, "a", null), Word(2000, 3000, "b", null)), verbose.words)
        val plain = OpenAiAmbientTranscriber.parse("""{"text":"tudo certo"}""", "gpt-4o-mini-transcribe", 4000)
        assertEquals(listOf(Word(0, 4000, "tudo certo", null)), plain.words)
    }

    // ------------------------------------------------------------------ end-to-end
    @Test fun endToEndFromAudioToJournalWithSpeakersAndRawDeletion() = runBlocking {
        val root = tmp.newFolder()
        val db = JdbcSqlDb(); val journal = JournalStore(db); val store = AmbientStore(db)
        // 3 s pauses: with the 600 ms VAD hangover + 300 ms pads the gap stays > 1.5 s → two pieces in one request.
        val pattern = listOf(false to 3000, true to 4000, false to 3000, true to 3000, false to 3000)
        // Glasses-like source: stereo, VPU marks the second speech run as the wearer.
        val frames = Synth.frames(pattern, channels = 2, quietSecond = true)
        val transcriber = ScriptedTranscriber { _, audioMs ->
            // pieces ≈ [2.76 s–7.9 s] + 0.4 s separator + [9.76 s–13.9 s] → ≈ 9.7 s billed out of 16 s recorded
            assertTrue("audioMs=$audioMs", audioMs in 9000..10_500)
            ChunkTranscript(listOf(Word(100, 600, "Você", 0), Word(700, 1200, "vem", 0), Word(1300, 1800, "amanhã?", 0),
                Word(5700, 6200, "Sim,", 1), Word(6300, 6800, "às", 1), Word(6900, 7400, "nove.", 1)), "", "deepgram", "nova-3")
        }
        val processor = AmbientProcessor(store, journal, { AmbientProvider.DEEPGRAM }, { transcriber }, { true })
        val sealed = ingestPattern(root, frames, TestSource("lifelog", providesWearer = true, headMounted = true), channels = 2, targetMs = 60_000,
            wearer = { f -> f * 20 in 10_000..13_000 })
        assertEquals(1, sealed.size)
        processor.onSealed(sealed[0].first, sealed[0].second)
        assertEquals(1, store.counts().pending)
        val outcome = processor.processPending()
        assertEquals(AmbientProcessor.Outcome.Done(1), outcome)
        assertEquals("OggS", String(transcriber.lastOgg, 0, 4))
        val entries = journal.day(org.turboio.customai.journal.Days.of(1_760_000_000_000L))
        assertEquals(2, entries.size); assertTrue(entries.all { it.kind == EntryKind.AMBIENT })
        assertEquals("Pessoa A: Você vem amanhã?", entries[0].text)
        assertEquals("Você: Sim, às nove.", entries[1].text)
        assertEquals("wearer", JSONObject(entries[1].metaJson).getString("speaker"))
        // Chunk time 5.7 s lies in the second piece → mapped back through the removed silence to ≈ 9.9 s of the recording.
        assertTrue("start=${entries[1].tsStart - 1_760_000_000_000L}", entries[1].tsStart - 1_760_000_000_000L in 9_700..10_200)
        assertTrue(entries[0].tsStart - 1_760_000_000_000L in 2_700..3_200)
        assertFalse(sealed[0].first.exists())                              // raw audio deleted after transcription
        assertEquals(1, journal.search("nove").size)
    }

    @Test fun permanentErrorPausesQueueAndRetryResumes() = runBlocking {
        val db = JdbcSqlDb(); val store = AmbientStore(db); val journal = JournalStore(db)
        val transcriber = ScriptedTranscriber { _, _ -> ChunkTranscript(listOf(Word(0, 500, "oi", null)), "oi", "deepgram", "nova-3") }
        transcriber.fail = TranscriptionException("Deepgram: chave inválida (HTTP 401)", permanent = true)
        val processor = AmbientProcessor(store, journal, { AmbientProvider.DEEPGRAM }, { transcriber }, { true })
        val sealed = ingestPattern(tmp.newFolder(), Synth.frames(listOf(true to 3000, false to 1000)), TestSource())
        processor.onSealed(sealed[0].first, sealed[0].second)
        assertTrue(processor.processPending() is AmbientProcessor.Outcome.Stopped)
        assertEquals(1, store.counts().failed); assertTrue(sealed[0].first.exists())       // audio kept while not transcribed
        transcriber.fail = TranscriptionException("rede", permanent = false)
        store.requeueFailed()
        assertTrue(processor.processPending() is AmbientProcessor.Outcome.Retry)
        assertEquals(1, store.counts().pending)
        transcriber.fail = null
        assertEquals(AmbientProcessor.Outcome.Done(1), processor.processPending())
        assertFalse(sealed[0].first.exists())
    }

    @Test fun liveDeepgramEndpointRejectsInvalidKey() = runBlocking {
        assumeTrue(System.getenv("RUN_LIVE_PROVIDER_TESTS") == "1")
        val ogg = OpusFile.encode(ShortArray(16000) { (3000 * sin(2 * PI * 200 * it / 16000.0)).toInt().toShort() })
        val key = System.getenv("DEEPGRAM_API_KEY").orEmpty()
        val result = runCatching { DeepgramTranscriber(key.ifEmpty { "invalid-key-for-turboio-test" }).transcribe(ogg, 1000) }
        if (key.isEmpty()) {
            val e = result.exceptionOrNull()
            println("Deepgram (chave inválida) → ${e?.message}")
            assertTrue(e is TranscriptionException && e.permanent && e.message!!.contains("HTTP 401"))
        } else println("Deepgram → ${result.getOrThrow().plainText}")
    }
}
