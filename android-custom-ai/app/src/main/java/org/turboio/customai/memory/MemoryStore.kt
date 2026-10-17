package org.turboio.customai.memory

import org.json.JSONArray
import org.turboio.customai.journal.SqlDb
import org.turboio.customai.journal.searchNormalize

enum class ItemKind(val id: String, val label: String) {
    TASK("tarefa", "Tarefa"), COMMITMENT("compromisso", "Compromisso"), DECISION("decisao", "Decisão"), FOLLOW_UP("acompanhamento", "Acompanhar");
    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id?.trim()?.lowercase() } ?: TASK }
}

enum class ItemStatus(val id: String) { OPEN("aberta"), DONE("feita"), DISMISSED("descartada");
    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: OPEN } }

/** What to do with a conversation category (approved: privacy filter). */
enum class CategoryPolicy(val id: String, val label: String) {
    INCLUDE("incluir", "Incluir"), IGNORE("ignorar", "Ignorar nos insights"), DELETE("apagar", "Não guardar");
    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: INCLUDE }
}

object Categories {
    val ALL = listOf("trabalho", "familia", "pessoal", "saude", "financas", "compras", "lazer", "ruido", "outro")
    val LABELS = mapOf("trabalho" to "Trabalho", "familia" to "Família", "pessoal" to "Pessoal", "saude" to "Saúde",
        "financas" to "Finanças", "compras" to "Compras", "lazer" to "Lazer", "ruido" to "Ruído / sem conteúdo", "outro" to "Outros")
    fun of(raw: String?): String = searchNormalize(raw ?: "").trim().let { c -> ALL.firstOrNull { it == c } ?: "outro" }
}

data class Block(
    val id: String,              // "<day>#<first entry id>"
    val day: String,
    val tsStart: Long,
    val tsEnd: Long,
    val entryIds: List<Long>,
    val hash: String,
    val category: String = "outro",
    val relevance: Double = 0.5,
    val summary: String = "",
    val processedAt: Long = 0,
)

data class Person(
    val id: Long = 0,
    val name: String,
    val aliases: List<String> = emptyList(),
    val relation: String = "",
    val facts: List<String> = emptyList(),
    val firstSeen: Long = 0,
    val lastSeen: Long = 0,
    val conversations: Int = 0,
)

data class Item(
    val id: Long = 0,
    val kind: ItemKind,
    val text: String,
    val status: ItemStatus = ItemStatus.OPEN,
    val owner: String = "eu",            // eu | outro
    val personId: Long? = null,
    val dueAt: Long? = null,
    val dueText: String = "",
    val blockId: String = "",
    val sourceTs: Long = 0,
    val quote: String = "",
    val confidence: Double = 0.0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val doneAt: Long? = null,
    val doneEvidence: String = "",
    val remindedAt: Long? = null,
    val snoozeUntil: Long? = null,
)

/** Normalized token set used for duplicate detection. */
fun tokens(text: String): Set<String> =
    searchNormalize(text).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 && it !in STOP }.toSet()

private val STOP = setOf("para", "com", "que", "uma", "dos", "das", "por", "ate", "sobre", "the", "nos", "nas", "pra", "pro")

fun jaccard(a: Set<String>, b: Set<String>): Double = if (a.isEmpty() || b.isEmpty()) 0.0 else a.intersect(b).size.toDouble() / a.union(b).size

/** Persistent memory next to the journal (same database). Blocking; call off the main thread. */
class MemoryStore(private val db: SqlDb) {
    init {
        db.exec("""CREATE TABLE IF NOT EXISTS blocks(
            id TEXT PRIMARY KEY, day TEXT NOT NULL, ts_start INTEGER NOT NULL, ts_end INTEGER NOT NULL, entry_ids TEXT NOT NULL,
            hash TEXT NOT NULL, category TEXT NOT NULL DEFAULT 'outro', relevance REAL NOT NULL DEFAULT 0.5,
            summary TEXT NOT NULL DEFAULT '', processed_at INTEGER NOT NULL DEFAULT 0)""")
        db.exec("CREATE INDEX IF NOT EXISTS blocks_day ON blocks(day, ts_start)")
        db.exec("""CREATE TABLE IF NOT EXISTS people(
            id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, norm TEXT NOT NULL, aliases TEXT NOT NULL DEFAULT '[]',
            relation TEXT NOT NULL DEFAULT '', facts TEXT NOT NULL DEFAULT '[]', first_seen INTEGER NOT NULL DEFAULT 0,
            last_seen INTEGER NOT NULL DEFAULT 0, conversations INTEGER NOT NULL DEFAULT 0)""")
        db.exec("CREATE UNIQUE INDEX IF NOT EXISTS people_norm ON people(norm)")
        db.exec("""CREATE TABLE IF NOT EXISTS person_blocks(
            person_id INTEGER NOT NULL, block_id TEXT NOT NULL, role TEXT NOT NULL, PRIMARY KEY(person_id, block_id, role))""")
        db.exec("""CREATE TABLE IF NOT EXISTS items(
            id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, text TEXT NOT NULL, norm TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'aberta', owner TEXT NOT NULL DEFAULT 'eu', person_id INTEGER, due_at INTEGER,
            due_text TEXT NOT NULL DEFAULT '', block_id TEXT NOT NULL DEFAULT '', source_ts INTEGER NOT NULL DEFAULT 0,
            quote TEXT NOT NULL DEFAULT '', confidence REAL NOT NULL DEFAULT 0, created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL, done_at INTEGER, done_evidence TEXT NOT NULL DEFAULT '', reminded_at INTEGER)""")
        db.exec("CREATE INDEX IF NOT EXISTS items_status ON items(status, due_at)")
        runCatching { db.exec("ALTER TABLE items ADD COLUMN snooze_until INTEGER") }   // added after the first 0.4.0 build
        // Native To-do of the glasses (business 22): id used on the wire and what was last sent/reported.
        runCatching { db.exec("ALTER TABLE items ADD COLUMN wire_id INTEGER") }
        runCatching { db.exec("ALTER TABLE items ADD COLUMN wire_status INTEGER") }
        runCatching { db.exec("ALTER TABLE items ADD COLUMN wire_title TEXT") }
    }

    // ------------------------------------------------------------------ blocks
    fun block(id: String): Block? = db.query("SELECT * FROM blocks WHERE id = ?", id).firstOrNull()?.let(::blockRow)

    fun blocks(fromDay: String, toDay: String = fromDay): List<Block> =
        db.query("SELECT * FROM blocks WHERE day >= ? AND day <= ? ORDER BY ts_start", fromDay, toDay).map(::blockRow)

    fun saveBlock(b: Block) = db.exec(
        "INSERT OR REPLACE INTO blocks(id, day, ts_start, ts_end, entry_ids, hash, category, relevance, summary, processed_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
        b.id, b.day, b.tsStart, b.tsEnd, JSONArray(b.entryIds).toString(), b.hash, b.category, b.relevance, b.summary, b.processedAt)

    private fun blockRow(r: Map<String, Any?>): Block {
        val ids = JSONArray(r["entry_ids"] as String)
        return Block(r["id"] as String, r["day"] as String, num(r["ts_start"]), num(r["ts_end"]), (0 until ids.length()).map { ids.getLong(it) },
            r["hash"] as String, r["category"] as String, (r["relevance"] as Number).toDouble(), r["summary"] as String, num(r["processed_at"]))
    }

    // ------------------------------------------------------------------ people
    fun people(): List<Person> = db.query("SELECT * FROM people ORDER BY last_seen DESC, name").map(::personRow)
    fun person(id: Long): Person? = db.query("SELECT * FROM people WHERE id = ?", id).firstOrNull()?.let(::personRow)

    /** Finds a person by name or alias (accent/case-insensitive). */
    fun findPerson(name: String): Person? {
        val n = normName(name)
        if (n.isEmpty()) return null
        db.query("SELECT * FROM people WHERE norm = ?", n).firstOrNull()?.let { return personRow(it) }
        return people().firstOrNull { p -> p.aliases.any { normName(it) == n } }
            ?: people().firstOrNull { p -> n.split(" ").size == 1 && normName(p.name).split(" ").firstOrNull() == n }
    }

    /** Creates or updates; relation/facts are merged, never erased. */
    fun upsertPerson(name: String, relation: String = "", facts: List<String> = emptyList(), seenAt: Long): Person {
        val existing = findPerson(name)
        if (existing == null) {
            val id = db.insert("INSERT INTO people(name, norm, relation, facts, first_seen, last_seen) VALUES(?,?,?,?,?,?)",
                name.trim(), normName(name), relation.trim(), JSONArray(facts.distinct().take(20)).toString(), seenAt, seenAt)
            return person(id)!!
        }
        val mergedFacts = (existing.facts + facts).map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { searchNormalize(it) }.takeLast(20)
        db.exec("UPDATE people SET relation = ?, facts = ?, last_seen = MAX(last_seen, ?) WHERE id = ?",
            existing.relation.ifBlank { relation.trim() }, JSONArray(mergedFacts).toString(), seenAt, existing.id)
        return person(existing.id)!!
    }

    fun linkPerson(personId: Long, blockId: String, role: String) {
        val before = db.query("SELECT 1 FROM person_blocks WHERE person_id = ? AND block_id = ?", personId, blockId).isNotEmpty()
        db.exec("INSERT OR IGNORE INTO person_blocks(person_id, block_id, role) VALUES(?,?,?)", personId, blockId, role)
        if (!before) db.exec("UPDATE people SET conversations = conversations + 1 WHERE id = ?", personId)
    }

    /** Removes the model's links of a block being re-analysed; links made by the user ([USER_ROLE]) stay. */
    fun unlinkBlock(blockId: String) {
        val people = db.query("SELECT DISTINCT person_id FROM person_blocks WHERE block_id = ? AND role != ?", blockId, USER_ROLE).map { num(it["person_id"]) }
        db.exec("DELETE FROM person_blocks WHERE block_id = ? AND role != ?", blockId, USER_ROLE)
        for (p in people) if (db.query("SELECT 1 FROM person_blocks WHERE person_id = ? AND block_id = ?", p, blockId).isEmpty())
            db.exec("UPDATE people SET conversations = MAX(0, conversations - 1) WHERE id = ?", p)
    }

    fun blocksOf(personId: Long, limit: Int = 20): List<Block> = db.query(
        "SELECT b.* FROM blocks b JOIN person_blocks p ON p.block_id = b.id WHERE p.person_id = ? GROUP BY b.id ORDER BY b.ts_start DESC LIMIT ?",
        personId, limit).map(::blockRow)

    fun renamePerson(id: Long, name: String) {
        val p = person(id) ?: return
        val aliases = (p.aliases + p.name).filter { normName(it) != normName(name) }.distinct()
        db.exec("UPDATE people SET name = ?, norm = ?, aliases = ? WHERE id = ?", name.trim(), normName(name), JSONArray(aliases).toString(), id)
    }

    /** Merges [from] into [into]: conversations, items and aliases move; [from] is deleted. */
    fun mergePeople(from: Long, into: Long) = db.transaction {
        val a = person(from) ?: return@transaction
        val b = person(into) ?: return@transaction
        db.exec("INSERT OR IGNORE INTO person_blocks(person_id, block_id, role) SELECT ?, block_id, role FROM person_blocks WHERE person_id = ?", into, from)
        db.exec("DELETE FROM person_blocks WHERE person_id = ?", from)
        db.exec("UPDATE items SET person_id = ? WHERE person_id = ?", into, from)
        val aliases = (b.aliases + a.aliases + a.name).filter { normName(it) != normName(b.name) }.distinct()
        val facts = (b.facts + a.facts).distinctBy { searchNormalize(it) }.takeLast(20)
        val n = db.query("SELECT COUNT(DISTINCT block_id) AS n FROM person_blocks WHERE person_id = ?", into).first()["n"]
        db.exec("UPDATE people SET aliases = ?, facts = ?, relation = ?, first_seen = MIN(first_seen, ?), last_seen = MAX(last_seen, ?), conversations = ? WHERE id = ?",
            JSONArray(aliases).toString(), JSONArray(facts).toString(), b.relation.ifBlank { a.relation }, a.firstSeen, a.lastSeen, num(n), into)
        db.exec("DELETE FROM people WHERE id = ?", from)
    }

    fun deletePerson(id: Long) = db.transaction {
        db.exec("DELETE FROM person_blocks WHERE person_id = ?", id); db.exec("UPDATE items SET person_id = NULL WHERE person_id = ?", id)
        db.exec("DELETE FROM people WHERE id = ?", id)
    }

    private fun personRow(r: Map<String, Any?>) = Person(num(r["id"]), r["name"] as String, strings(r["aliases"]), r["relation"] as String,
        strings(r["facts"]), num(r["first_seen"]), num(r["last_seen"]), num(r["conversations"]).toInt())

    // ------------------------------------------------------------------ items
    fun item(id: Long): Item? = db.query("SELECT * FROM items WHERE id = ?", id).firstOrNull()?.let(::itemRow)

    fun openItems(limit: Int = 200): List<Item> = db.query(
        "SELECT * FROM items WHERE status = 'aberta' ORDER BY CASE WHEN due_at IS NULL THEN 1 ELSE 0 END, due_at, created_at DESC LIMIT ?", limit).map(::itemRow)

    fun items(status: ItemStatus, limit: Int = 200): List<Item> =
        db.query("SELECT * FROM items WHERE status = ? ORDER BY updated_at DESC LIMIT ?", status.id, limit).map(::itemRow)

    fun itemsOf(personId: Long, onlyOpen: Boolean = true): List<Item> =
        db.query("SELECT * FROM items WHERE person_id = ?" + (if (onlyOpen) " AND status = 'aberta'" else "") + " ORDER BY created_at DESC", personId).map(::itemRow)

    fun itemsOfBlock(blockId: String): List<Item> = db.query("SELECT * FROM items WHERE block_id = ?", blockId).map(::itemRow)

    fun itemsBetween(from: Long, to: Long): List<Item> =
        db.query("SELECT * FROM items WHERE (created_at >= ? AND created_at < ?) OR (done_at >= ? AND done_at < ?) ORDER BY created_at", from, to, from, to).map(::itemRow)

    fun addItem(i: Item): Long = db.insert(
        "INSERT INTO items(kind, text, norm, status, owner, person_id, due_at, due_text, block_id, source_ts, quote, confidence, created_at, updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        i.kind.id, i.text.trim(), searchNormalize(i.text), i.status.id, i.owner, i.personId, i.dueAt, i.dueText, i.blockId, i.sourceTs,
        i.quote, i.confidence, i.createdAt, i.updatedAt)

    fun updateItem(i: Item) = db.exec(
        "UPDATE items SET kind = ?, text = ?, norm = ?, status = ?, owner = ?, person_id = ?, due_at = ?, due_text = ?, quote = ?, confidence = ?, updated_at = ?, done_at = ?, done_evidence = ?, reminded_at = ?, snooze_until = ? WHERE id = ?",
        i.kind.id, i.text.trim(), searchNormalize(i.text), i.status.id, i.owner, i.personId, i.dueAt, i.dueText, i.quote, i.confidence,
        i.updatedAt, i.doneAt, i.doneEvidence, i.remindedAt, i.snoozeUntil, i.id)

    fun setStatus(id: Long, status: ItemStatus, at: Long, evidence: String = "") = db.exec(
        "UPDATE items SET status = ?, updated_at = ?, done_at = ?, done_evidence = ? WHERE id = ?",
        status.id, at, if (status == ItemStatus.OPEN) null else at, evidence, id)

    /** Items generated by a block that is being re-analysed (only those still open and never touched by the user). */
    fun dropOpenItemsOfBlock(blockId: String) = db.exec("DELETE FROM items WHERE block_id = ? AND status = 'aberta' AND updated_at = created_at", blockId)

    fun wasDismissed(text: String): Boolean {
        val t = tokens(text)
        return db.query("SELECT text FROM items WHERE status = 'descartada' ORDER BY updated_at DESC LIMIT 200").any { jaccard(tokens(it["text"] as String), t) >= 0.8 }
    }

    private fun itemRow(r: Map<String, Any?>) = Item(num(r["id"]), ItemKind.of(r["kind"] as String), r["text"] as String,
        ItemStatus.of(r["status"] as String), r["owner"] as String, (r["person_id"] as Number?)?.toLong(), (r["due_at"] as Number?)?.toLong(),
        r["due_text"] as String, r["block_id"] as String, num(r["source_ts"]), r["quote"] as String, (r["confidence"] as Number).toDouble(),
        num(r["created_at"]), num(r["updated_at"]), (r["done_at"] as Number?)?.toLong(), r["done_evidence"] as String,
        (r["reminded_at"] as Number?)?.toLong(), (r["snooze_until"] as Number?)?.toLong())

    // ------------------------------------------------------------------ glasses To-do
    data class Wire(val item: Item, val wireId: Long?, val status: Int?, val title: String?)

    /** Open items to show on the glasses, plus closed ones the glasses still believe open. */
    fun wireCandidates(maxOpen: Int): List<Wire> {
        fun rows(sql: String, vararg args: Any?) = db.query(sql, *args).map { Wire(itemRow(it), (it["wire_id"] as Number?)?.toLong(), (it["wire_status"] as Number?)?.toInt(), it["wire_title"] as String?) }
        val open = rows("SELECT * FROM items WHERE status = 'aberta' AND kind != 'decisao' ORDER BY CASE WHEN due_at IS NULL THEN 1 ELSE 0 END, due_at, created_at DESC LIMIT ?", maxOpen)
        val closed = rows("SELECT * FROM items WHERE status != 'aberta' AND wire_id IS NOT NULL AND wire_status = 0")
        return open + closed
    }

    fun itemByWire(wireId: Long): Item? = db.query("SELECT * FROM items WHERE wire_id = ?", wireId).firstOrNull()?.let(::itemRow)

    fun setWire(itemId: Long, wireId: Long, status: Int, title: String?) =
        db.exec("UPDATE items SET wire_id = ?, wire_status = ?, wire_title = COALESCE(?, wire_title) WHERE id = ?", wireId, status, title, itemId)

    fun wipe() = db.transaction {
        db.exec("DELETE FROM blocks"); db.exec("DELETE FROM people"); db.exec("DELETE FROM person_blocks"); db.exec("DELETE FROM items")
    }

    companion object {
        const val USER_ROLE = "falante_usuario"
        fun normName(name: String): String = searchNormalize(name).replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()
        private fun num(v: Any?): Long = (v as Number?)?.toLong() ?: 0
        private fun strings(v: Any?): List<String> = runCatching { JSONArray(v as String).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
    }
}
