package org.turboio.customai.ambient

import org.json.JSONArray
import org.json.JSONObject
import org.turboio.customai.diag.DiagnosticLog
import org.turboio.customai.journal.Days
import org.turboio.customai.journal.Entry
import org.turboio.customai.journal.EntryKind
import org.turboio.customai.journal.EntrySource
import org.turboio.customai.journal.JournalStore
import org.turboio.customai.journal.SqlDb
import java.io.File

/** Durable queue of segments and chunks (same SQLite file as the journal). */
class AmbientStore(private val db: SqlDb) {
    init {
        db.exec("""CREATE TABLE IF NOT EXISTS ambient_segments(id TEXT PRIMARY KEY, dir TEXT NOT NULL, source TEXT NOT NULL,
            started INTEGER NOT NULL, ended INTEGER NOT NULL, frames INTEGER NOT NULL, speech_frames INTEGER NOT NULL,
            state TEXT NOT NULL, created INTEGER NOT NULL)""")
        db.exec("""CREATE TABLE IF NOT EXISTS ambient_chunks(id INTEGER PRIMARY KEY AUTOINCREMENT, segment_id TEXT NOT NULL, idx INTEGER NOT NULL,
            plan_json TEXT NOT NULL, audio_ms INTEGER NOT NULL, forced INTEGER NOT NULL, provider TEXT NOT NULL, state TEXT NOT NULL,
            attempts INTEGER NOT NULL DEFAULT 0, error TEXT NOT NULL DEFAULT '', last_word_wall INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL)""")
        db.exec("CREATE INDEX IF NOT EXISTS ambient_chunks_state ON ambient_chunks(state, segment_id, idx)")
    }

    data class Chunk(val id: Long, val segmentId: String, val idx: Int, val plan: ChunkPlan, val provider: String, val attempts: Int)
    data class Counts(val pending: Int, val done: Int, val failed: Int, val transcribedMs: Long, val pendingMs: Long)

    fun addSegment(dir: File, m: SegmentManifest, plans: List<ChunkPlan>, provider: String, now: Long) = db.transaction {
        db.exec("INSERT OR REPLACE INTO ambient_segments(id, dir, source, started, ended, frames, speech_frames, state, created) VALUES(?,?,?,?,?,?,?,?,?)",
            m.id, dir.absolutePath, m.source, m.startedWall, m.endedWall, m.frames, m.speechFrames, if (plans.isEmpty()) "done" else "planned", now)
        plans.forEachIndexed { i, p ->
            db.insert("INSERT INTO ambient_chunks(segment_id, idx, plan_json, audio_ms, forced, provider, state, updated) VALUES(?,?,?,?,?,?,?,?)",
                m.id, i, planJson(p), p.audioMs, if (p.forcedCut) 1 else 0, provider, "pending", now)
        }
    }

    fun nextPending(): Chunk? = db.query("""SELECT c.* FROM ambient_chunks c JOIN ambient_segments s ON s.id = c.segment_id
        WHERE c.state = 'pending' ORDER BY s.started, c.idx LIMIT 1""").firstOrNull()?.let {
        Chunk((it["id"] as Number).toLong(), it["segment_id"] as String, (it["idx"] as Number).toInt(), parsePlan(it["plan_json"] as String),
            it["provider"] as String, (it["attempts"] as Number).toInt())
    }

    fun segmentDir(id: String): File? = db.query("SELECT dir FROM ambient_segments WHERE id = ?", id).firstOrNull()?.let { File(it["dir"] as String) }

    fun previousWordEnd(segmentId: String, idx: Int): Long = db.query(
        "SELECT MAX(last_word_wall) AS t FROM ambient_chunks WHERE segment_id = ? AND idx < ? AND state = 'done'", segmentId, idx)
        .firstOrNull()?.get("t")?.let { (it as Number).toLong() } ?: 0L

    fun markDone(id: Long, lastWordWall: Long, now: Long) =
        db.exec("UPDATE ambient_chunks SET state = 'done', error = '', last_word_wall = ?, updated = ? WHERE id = ?", lastWordWall, now, id)

    fun markAttempt(id: Long, error: String, failed: Boolean, now: Long) =
        db.exec("UPDATE ambient_chunks SET attempts = attempts + 1, error = ?, state = ?, updated = ? WHERE id = ?",
            error.take(300), if (failed) "failed" else "pending", now, id)

    /** True when every chunk of the segment is finished (done or failed). */
    fun segmentFinished(segmentId: String): Boolean =
        db.query("SELECT COUNT(*) AS n FROM ambient_chunks WHERE segment_id = ? AND state = 'pending'", segmentId).first()["n"].let { (it as Number).toInt() == 0 }

    fun setSegmentState(id: String, state: String) = db.exec("UPDATE ambient_segments SET state = ? WHERE id = ?", state, id)

    /** Retries chunks that failed permanently (e.g. after fixing the API key). */
    fun requeueFailed(): Int {
        val n = (db.query("SELECT COUNT(*) AS n FROM ambient_chunks WHERE state = 'failed'").first()["n"] as Number).toInt()
        db.exec("UPDATE ambient_chunks SET state = 'pending', attempts = 0 WHERE state = 'failed'")
        return n
    }

    fun counts(): Counts {
        val rows = db.query("SELECT state, COUNT(*) AS n, SUM(audio_ms) AS ms FROM ambient_chunks GROUP BY state").associate {
            (it["state"] as String) to ((it["n"] as Number).toInt() to ((it["ms"] as Number?)?.toLong() ?: 0L))
        }
        return Counts(rows["pending"]?.first ?: 0, rows["done"]?.first ?: 0, rows["failed"]?.first ?: 0,
            rows["done"]?.second ?: 0, rows["pending"]?.second ?: 0)
    }

    fun lastError(): String = db.query("SELECT error FROM ambient_chunks WHERE error != '' ORDER BY updated DESC LIMIT 1")
        .firstOrNull()?.get("error") as String? ?: ""

    fun wipe() = db.transaction { db.exec("DELETE FROM ambient_chunks"); db.exec("DELETE FROM ambient_segments") }

    companion object {
        fun planJson(p: ChunkPlan): String = JSONObject().put("frameMs", p.frameMs).put("forced", p.forcedCut)
            .put("pieces", JSONArray(p.pieces.map { JSONArray(listOf(it.chunkStartMs, it.frameStart, it.frameEnd)) })).toString()
        fun parsePlan(s: String): ChunkPlan {
            val o = JSONObject(s); val a = o.getJSONArray("pieces")
            return ChunkPlan((0 until a.length()).map { a.getJSONArray(it).let { r -> Piece(r.getLong(0), r.getInt(1), r.getInt(2)) } },
                o.getInt("frameMs"), o.optBoolean("forced"))
        }
    }
}

/**
 * Segment sealed → chunk plan → (worker) transcription → speaker attribution → journal entries → raw audio deleted.
 * Every step is persisted; a crash resumes from the last committed chunk.
 */
class AmbientProcessor(
    private val store: AmbientStore,
    private val journal: JournalStore,
    private val provider: () -> AmbientProvider,
    private val transcriber: (String) -> AmbientTranscriber,
    private val deleteRaw: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    sealed class Outcome {
        data class Done(val chunks: Int) : Outcome()
        data class Retry(val message: String) : Outcome()
        data class Stopped(val message: String) : Outcome()
    }

    fun onSealed(dir: File, m: SegmentManifest) {
        val p = provider()
        val (flags, _) = SegmentReader(dir).flags()
        val plans = ChunkPlanner.plan(BooleanArray(flags.size) { flags[it].toInt() and FrameFlags.SPEECH != 0 }, m.frameMs, p.policy)
        store.addSegment(dir, m, plans, p.id, now())
        DiagnosticLog.i("AMBIENTE", "segmento planejado: ${plans.size} fatia(s), ${plans.sumOf { it.audioMs } / 1000} s de fala a transcrever")
        if (plans.isEmpty() && deleteRaw()) dir.deleteRecursively()
    }

    suspend fun processPending(maxChunks: Int = 200): Outcome {
        var done = 0
        while (done < maxChunks) {
            val chunk = store.nextPending() ?: return Outcome.Done(done)
            val dir = store.segmentDir(chunk.segmentId)
            if (dir == null || !File(dir, "segment.json").exists()) {
                store.markAttempt(chunk.id, "áudio do segmento não encontrado", failed = true, now()); continue
            }
            try {
                processChunk(chunk, dir)
                done++
            } catch (e: TranscriptionException) {
                store.markAttempt(chunk.id, e.message ?: "erro", failed = e.permanent || chunk.attempts >= 8, now())
                DiagnosticLog.w("AMBIENTE", "transcrição falhou: ${e.message}")
                return if (e.permanent) Outcome.Stopped(e.message ?: "erro") else Outcome.Retry(e.message ?: "erro")
            }
            if (store.segmentFinished(chunk.segmentId)) {
                store.setSegmentState(chunk.segmentId, "done")
                if (deleteRaw()) { dir.deleteRecursively(); store.setSegmentState(chunk.segmentId, "deleted") }
            }
        }
        return Outcome.Done(done)
    }

    private suspend fun processChunk(chunk: AmbientStore.Chunk, dir: File) {
        val reader = SegmentReader(dir)
        val m = reader.manifest
        val (flags, levels) = reader.flags()
        val pcm = ChunkAudio.pcm(reader.frames(), m.channels, chunk.plan, AmbientProvider.of(chunk.provider).policy.separatorMs)
        val ogg = OpusFile.encode(pcm)
        val result = transcriber(chunk.provider).transcribe(ogg, chunk.plan.audioMs)
        // Forced cuts overlap the previous chunk by ~1 s: drop words already stored (word-level, by wall time).
        val previousEnd = store.previousWordEnd(chunk.segmentId, chunk.idx)
        val words = if (previousEnd == 0L) result.words
            else result.words.filter { m.wallAt(ChunkPlanner.frameAt(chunk.plan, it.startMs)) >= previousEnd - 50 }
        val attribution = Speakers.attribute(words, chunk.plan, flags, levels, m)
        val utterances = Speakers.utterances(words, attribution, chunk.plan, m).filter { !Speakers.hallucination(it.text) }
        val source = when (m.source) { "lifelog" -> EntrySource.LIFELOG; "recording14" -> EntrySource.RECORDING14; else -> EntrySource.PHONE_MIC }
        for (u in utterances) {
            val meta = JSONObject().put("speaker", u.speaker).put("label", u.label).put("confidence", u.confidence)
                .put("method", attribution.method).put("segment", m.id).put("chunk", chunk.idx).put("provider", result.provider)
            journal.add(Entry(day = Days.of(u.startWall), tsStart = u.startWall, tsEnd = u.endWall, kind = EntryKind.AMBIENT,
                source = source, text = if (u.label.isNotEmpty()) "${u.label}: ${u.text}" else u.text,
                provider = result.provider, model = result.model, metaJson = meta.toString()))
        }
        store.markDone(chunk.id, utterances.maxOfOrNull { it.endWall } ?: previousEnd, now())
        DiagnosticLog.i("AMBIENTE", "fatia ${chunk.idx} transcrita: ${utterances.size} fala(s), falantes=${attribution.method}")
    }
}
