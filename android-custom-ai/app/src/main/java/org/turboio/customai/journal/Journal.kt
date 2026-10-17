package org.turboio.customai.journal

import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Minimal SQL surface so the same SQL (incl. FTS4) runs on Android SQLite and on JDBC SQLite in unit tests. */
interface SqlDb {
    fun exec(sql: String, vararg args: Any?)
    fun insert(sql: String, vararg args: Any?): Long
    fun query(sql: String, vararg args: Any?): List<Map<String, Any?>>
    fun <T> transaction(block: () -> T): T
}

enum class EntryKind(val id: String) { INTERACTION("interaction"), AMBIENT("ambient"), NOTE("note");
    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: NOTE } }

enum class EntrySource(val id: String, val label: String) {
    VOICE_GLASSES("voice_glasses", "voz (óculos)"),
    VOICE_PHONE("voice_phone", "voz (telefone)"),
    TEXT("text_test", "texto"),
    LIFELOG("lifelog", "ambiente (óculos)"),
    RECORDING14("recording14", "gravação (óculos)"),
    PHONE_MIC("phone_mic", "ambiente (telefone)");
    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: TEXT }
}

data class Entry(
    val id: Long = 0,
    val day: String,                 // yyyy-MM-dd in the device time zone at capture time
    val tsStart: Long,               // epoch ms
    val tsEnd: Long,
    val kind: EntryKind,
    val source: EntrySource,
    val text: String,                // question / transcript
    val answer: String = "",         // AI answer for interactions
    val provider: String = "",
    val model: String = "",
    val latencyMs: Long = 0,
    val metaJson: String = "{}",
)

data class StoredInsight(val day: String, val kind: String, val inputHash: String, val json: String, val text: String,
                         val provider: String, val model: String, val createdAt: Long)

object Days {
    private val FORMAT = DateTimeFormatter.ISO_LOCAL_DATE
    fun of(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().format(FORMAT)
    fun shift(day: String, delta: Long): String = LocalDate.parse(day, FORMAT).plusDays(delta).format(FORMAT)
    fun label(day: String): String = LocalDate.parse(day, FORMAT).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
    fun time(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
}

/** Lowercase, accent-free form used for the FTS column and queries (device-independent search). */
fun searchNormalize(text: String): String =
    Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

/**
 * The journal: interactions with the AI (option 2) and ambient transcripts (option 3) in one timeline.
 * All methods are blocking; call them off the main thread.
 */
class JournalStore(private val db: SqlDb) {
    init { migrate() }

    private fun migrate() {
        db.exec("""CREATE TABLE IF NOT EXISTS entries(
            id INTEGER PRIMARY KEY AUTOINCREMENT, day TEXT NOT NULL, ts_start INTEGER NOT NULL, ts_end INTEGER NOT NULL,
            kind TEXT NOT NULL, source TEXT NOT NULL, text TEXT NOT NULL, answer TEXT NOT NULL DEFAULT '',
            provider TEXT NOT NULL DEFAULT '', model TEXT NOT NULL DEFAULT '', latency_ms INTEGER NOT NULL DEFAULT 0,
            meta_json TEXT NOT NULL DEFAULT '{}')""")
        db.exec("CREATE INDEX IF NOT EXISTS entries_day ON entries(day, ts_start)")
        // Contentless-style FTS4 table keyed by docid = entries.id; holds normalized text only.
        db.exec("CREATE VIRTUAL TABLE IF NOT EXISTS entries_fts USING fts4(body)")
        db.exec("""CREATE TABLE IF NOT EXISTS insights(
            day TEXT NOT NULL, kind TEXT NOT NULL, input_hash TEXT NOT NULL, json TEXT NOT NULL, text TEXT NOT NULL,
            provider TEXT NOT NULL, model TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(day, kind, input_hash))""")
    }

    fun add(entry: Entry): Long = db.transaction {
        require(entry.text.isNotBlank() || entry.answer.isNotBlank()) { "entrada vazia" }
        val id = db.insert(
            "INSERT INTO entries(day, ts_start, ts_end, kind, source, text, answer, provider, model, latency_ms, meta_json) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            entry.day, entry.tsStart, entry.tsEnd, entry.kind.id, entry.source.id, entry.text.take(100_000), entry.answer.take(100_000),
            entry.provider, entry.model, entry.latencyMs, entry.metaJson,
        )
        db.exec("INSERT INTO entries_fts(docid, body) VALUES(?, ?)", id, searchNormalize(entry.text + "\n" + entry.answer))
        id
    }

    private fun row(r: Map<String, Any?>) = Entry(
        id = (r["id"] as Number).toLong(), day = r["day"] as String,
        tsStart = (r["ts_start"] as Number).toLong(), tsEnd = (r["ts_end"] as Number).toLong(),
        kind = EntryKind.of(r["kind"] as String?), source = EntrySource.of(r["source"] as String?),
        text = r["text"] as String, answer = r["answer"] as String? ?: "",
        provider = r["provider"] as String? ?: "", model = r["model"] as String? ?: "",
        latencyMs = (r["latency_ms"] as Number?)?.toLong() ?: 0, metaJson = r["meta_json"] as String? ?: "{}",
    )

    fun day(day: String): List<Entry> =
        db.query("SELECT * FROM entries WHERE day = ? ORDER BY ts_start, id", day).map(::row)

    /** Days that have entries, newest first. */
    fun days(limit: Int = 366): List<Pair<String, Int>> =
        db.query("SELECT day, COUNT(*) AS n FROM entries GROUP BY day ORDER BY day DESC LIMIT ?", limit)
            .map { it["day"] as String to (it["n"] as Number).toInt() }

    /** Full-text search (accent/case-insensitive, all terms must match). Optional day filter. */
    fun search(terms: String, day: String? = null, limit: Int = 50): List<Entry> {
        val words = searchNormalize(terms).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }.take(8)
        if (words.isEmpty()) return emptyList()
        val match = words.joinToString(" ") { "$it*" }
        val sql = "SELECT e.* FROM entries e JOIN entries_fts f ON f.docid = e.id WHERE f.body MATCH ?" +
            (if (day != null) " AND e.day = ?" else "") + " ORDER BY e.ts_start DESC LIMIT ?"
        val args: Array<Any?> = if (day != null) arrayOf(match, day, limit) else arrayOf(match, limit)
        return db.query(sql, *args).map(::row)
    }

    fun delete(id: Long) = db.transaction {
        db.exec("DELETE FROM entries WHERE id = ?", id); db.exec("DELETE FROM entries_fts WHERE docid = ?", id)
    }

    fun deleteDay(day: String) = db.transaction {
        db.exec("DELETE FROM entries_fts WHERE docid IN (SELECT id FROM entries WHERE day = ?)", day)
        db.exec("DELETE FROM entries WHERE day = ?", day)
        db.exec("DELETE FROM insights WHERE day = ?", day)
    }

    /** Retention: removes days strictly older than [keepDays] before [today]. 0 = keep everything. */
    fun applyRetention(today: String, keepDays: Int): Int {
        if (keepDays <= 0) return 0
        val cutoff = Days.shift(today, -keepDays.toLong())
        val old = db.query("SELECT DISTINCT day FROM entries WHERE day < ?", cutoff).map { it["day"] as String }
        old.forEach { deleteDay(it) }
        return old.size
    }

    fun saveInsight(insight: StoredInsight) = db.exec(
        "INSERT OR REPLACE INTO insights(day, kind, input_hash, json, text, provider, model, created_at) VALUES(?,?,?,?,?,?,?,?)",
        insight.day, insight.kind, insight.inputHash, insight.json, insight.text, insight.provider, insight.model, insight.createdAt,
    )

    fun insight(day: String, kind: String, inputHash: String): StoredInsight? =
        db.query("SELECT * FROM insights WHERE day = ? AND kind = ? AND input_hash = ?", day, kind, inputHash).firstOrNull()?.let(::insightRow)

    fun latestInsight(day: String, kind: String = "daily"): StoredInsight? =
        db.query("SELECT * FROM insights WHERE day = ? AND kind = ? ORDER BY created_at DESC LIMIT 1", day, kind).firstOrNull()?.let(::insightRow)

    private fun insightRow(r: Map<String, Any?>) = StoredInsight(r["day"] as String, r["kind"] as String, r["input_hash"] as String,
        r["json"] as String, r["text"] as String, r["provider"] as String, r["model"] as String, (r["created_at"] as Number).toLong())

    fun wipe() = db.transaction { db.exec("DELETE FROM entries"); db.exec("DELETE FROM entries_fts"); db.exec("DELETE FROM insights") }
}

object JournalExport {
    fun markdown(day: String, entries: List<Entry>, insight: StoredInsight?): String = buildString {
        append("# Diário Turbo IO — ").append(Days.label(day)).append("\n\n")
        if (insight != null) append("## Insights\n\n").append(insight.text.trim()).append("\n\n")
        append("## Linha do tempo\n\n")
        for (e in entries) {
            append("### ").append(Days.time(e.tsStart)).append(" · ").append(e.source.label).append("\n\n")
            if (e.kind == EntryKind.INTERACTION) {
                append("**Você:** ").append(e.text.trim()).append("\n\n")
                if (e.answer.isNotBlank()) append("**IA:** ").append(e.answer.trim()).append("\n\n")
            } else append(e.text.trim()).append("\n\n")
        }
    }
}
