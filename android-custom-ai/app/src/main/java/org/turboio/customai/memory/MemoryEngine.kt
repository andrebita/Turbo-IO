package org.turboio.customai.memory

import org.json.JSONObject
import org.turboio.customai.diag.DiagnosticLog
import org.turboio.customai.insights.Llm
import org.turboio.customai.journal.Days
import org.turboio.customai.journal.Entry
import org.turboio.customai.journal.EntryKind
import org.turboio.customai.journal.JournalStore
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class MemorySettings(
    val enabled: Boolean = true,
    val notifyNew: Boolean = true,
    val lensNew: Boolean = false,
    val reminders: Boolean = true,
    val reminderLeadMin: Int = 15,
    val morning: Boolean = true,
    val morningHour: Int = 8,
    val morningMinute: Int = 0,
    val weekly: Boolean = true,
    val weeklyDow: Int = 7,          // ISO: 1 = Monday … 7 = Sunday
    val weeklyHour: Int = 19,
    val policies: Map<String, CategoryPolicy> = mapOf("ruido" to CategoryPolicy.IGNORE),
    val glassesTodo: Boolean = false,   // mirror open items into the glasses' native To-do (opt-in)
) {
    fun policy(category: String): CategoryPolicy = policies[category] ?: CategoryPolicy.INCLUDE
}

/** Result of analysing pending conversations. */
data class MemoryRun(val blocks: Int, val newItems: List<Item>, val closedItems: List<Item>, val named: List<String>, val failed: Int)

object MemoryPrompts {
    const val VERSION = "m1"

    val EXTRACT = """Você é a memória pessoal de quem usa óculos inteligentes. Recebe UMA conversa do dia (transcrição automática,
pode ter erros) e devolve só o que for útil depois. "Você" é a dona/o dono da memória; "Pessoa A·2" etc. são outras pessoas
sem nome conhecido (o número separa trechos diferentes). Português do Brasil.

Regras:
- Não invente. Use só o que está na conversa.
- Nome de um falante: só com evidência explícita (vocativo como "oi, Marina", apresentação, "tô aqui com o João").
  Sem evidência, não inclua o falante. Copie a evidência literal.
- Itens novos: tarefas e compromissos de Você, incluindo os implícitos ("vou…", "pode deixar", "te mando", "amanhã eu vejo"),
  pedidos que Você aceitou, combinados com data, decisões tomadas e coisas a acompanhar (o outro ficou de fazer algo).
  Não crie itens a partir de TV, vídeo, música, leitura em voz alta ou conversa sem compromisso.
- "quando": data/hora absolutas no formato AAAA-MM-DDTHH:MM, calculadas a partir da data e hora atuais informadas;
  vazio se não houver prazo. Mantenha também o texto original em "quando_texto".
- Se um item novo for a mesma coisa que uma pendência aberta listada, informe o id dela em "mesmo_que".
- Se a conversa mostrar que uma pendência aberta foi feita, liste-a em "itens_concluidos" com a evidência.
- "relevancia": 0 = sem conteúdo/ruído/conversa banal, 1 = importante. "categoria": trabalho, familia, pessoal, saude,
  financas, compras, lazer, ruido ou outro.

Responda APENAS com um objeto JSON válido, sem Markdown, neste formato:
{"categoria": "", "relevancia": 0.0, "resumo": "1 a 2 frases",
 "falantes": [{"rotulo": "Pessoa A·1", "nome": "", "evidencia": "", "confianca": 0.0}],
 "pessoas": [{"nome": "", "relacao": "", "fatos": [""]}],
 "itens_novos": [{"tipo": "tarefa|compromisso|decisao|acompanhamento", "texto": "verbo no infinitivo + objeto",
   "responsavel": "eu|outro", "pessoa": "", "quando": "", "quando_texto": "", "horario": "HH:MM", "citacao": "", "confianca": 0.0, "mesmo_que": null}],
 "itens_concluidos": [{"id": 0, "evidencia": ""}]}"""

    const val RETRY = "Sua resposta anterior não era um JSON válido. Responda novamente APENAS com o objeto JSON pedido."

    const val QUICK = """Transforme o pedido da pessoa em UMA pendência. Português do Brasil. Responda APENAS com JSON:
{"texto": "verbo no infinitivo + objeto, curto", "tipo": "tarefa|compromisso", "quando": "AAAA-MM-DDTHH:MM ou vazio",
 "quando_texto": "como a pessoa disse o prazo, ou vazio", "pessoa": "nome de outra pessoa envolvida, ou vazio"}
Calcule "quando" a partir da data e hora atuais informadas; sem hora dita, use 09:00; sem prazo, deixe vazio."""
}

/**
 * Turns the journal into persistent memory, one conversation at a time (docs/MEMORIA_E_ASSISTENTE.md):
 * conversations (blocks) → one model call each → validated people, speaker names, open items and completions.
 */
class MemoryEngine(
    private val journal: JournalStore,
    val store: MemoryStore,
    private val llm: Llm,
    private val settings: () -> MemorySettings,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val gapMs: Long = 4 * 60_000,
    private val maxChars: Int = 15_000,
) {
    data class Draft(val id: String, val day: String, val entries: List<Entry>) {
        val tsStart get() = entries.first().tsStart
        val tsEnd get() = entries.maxOf { it.tsEnd }
    }

    /** Groups a day's entries into conversations (gap < [gapMs], long ones split at ~[maxChars]); journal questions are left out. */
    fun drafts(entries: List<Entry>): List<Draft> {
        val usable = entries.filter { !it.metaJson.contains("\"journal_query\":true") }.sortedBy { it.tsStart }
        val out = ArrayList<MutableList<Entry>>()
        var chars = 0
        for (e in usable) {
            val last = out.lastOrNull()
            val size = e.text.length + minOf(e.answer.length, 300)
            if (last == null || last.first().day != e.day || e.tsStart - last.maxOf { it.tsEnd } >= gapMs || chars + size > maxChars) {
                out.add(arrayListOf(e)); chars = size
            } else { last.add(e); chars += size }
        }
        return out.map { Draft("${it.first().day}#${it.first().id}", it.first().day, it) }
    }

    /** Utterance text without the speaker prefix ("Pessoa A: " or the name it was renamed to). */
    private fun body(e: Entry): String {
        val meta = runCatching { JSONObject(e.metaJson) }.getOrDefault(JSONObject())
        for (prefix in listOf(meta.optString("shown"), meta.optString("label"))) {
            if (prefix.isNotEmpty() && e.text.startsWith("$prefix:")) return e.text.substring(prefix.length + 1).trim()
        }
        return e.text.trim()
    }

    /** Content fingerprint: speaker renames do not count as new content (no re-analysis loop). */
    private fun hash(d: Draft): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(MemoryPrompts.VERSION.toByteArray())
        d.entries.forEach { md.update("${it.id}|${body(it)}|${it.answer}\n".toByteArray()) }
        return md.digest().take(12).joinToString("") { "%02x".format(it) }
    }

    /** Analyses every closed, not yet analysed conversation of [days]. */
    suspend fun process(days: List<String>, now: Long, force: Boolean = false): MemoryRun {
        var blocks = 0; var failed = 0
        val newItems = ArrayList<Item>(); val closed = ArrayList<Item>(); val named = ArrayList<String>()
        val closedEarly = HashSet<String>()   // a part followed by another part of the same day is complete
        val today = Days.of(now, zone)
        for (day in days) for (d in drafts(journal.day(day)).let { list -> list.map { it to (it === list.last()) } }.map { (d, last) ->
            d.also { if (!last) closedEarly.add(d.id) } }) {
            val isClosed = d.day < today || d.id in closedEarly || now - d.tsEnd >= gapMs
            if (!isClosed) continue
            val h = hash(d)
            val previous = store.block(d.id)
            if (!force && previous != null && previous.hash == h) continue
            try {
                val r = analyse(d, h, previous != null, now)
                blocks++; newItems += r.newItems; closed += r.closedItems; named += r.named
            } catch (e: Exception) {
                failed++
                DiagnosticLog.w("MEMORIA", "conversa ${Days.time(d.tsStart, zone)} não analisada: ${e.javaClass.simpleName}")
            }
        }
        if (blocks > 0) DiagnosticLog.i("MEMORIA", "$blocks conversa(s) analisada(s): ${newItems.size} pendência(s) nova(s), ${closed.size} concluída(s), nomes: ${named.distinct().joinToString().ifEmpty { "—" }}")
        return MemoryRun(blocks, newItems, closed, named.distinct(), failed)
    }

    // ------------------------------------------------------------------ one conversation
    private class Lines(val text: String, val labels: Map<String, Pair<String, List<Long>>>)   // "Pessoa A·2" → (label, entry ids)

    private fun transcript(d: Draft): Lines {
        val chunkTags = LinkedHashMap<String, Int>()
        val labels = HashMap<String, Pair<String, MutableList<Long>>>()
        val sb = StringBuilder()
        // A label is per transcription chunk: once one utterance of it has a name, the others are the same person.
        fun key(m: JSONObject) = "${m.optString("segment")}#${m.optInt("chunk")}#${m.optString("label")}"
        val known = HashMap<String, String>()
        for (e in d.entries) runCatching { JSONObject(e.metaJson) }.getOrNull()?.let { m ->
            val p = m.optString("person")
            if (p.isNotEmpty() && (m.optString("person_method") == "usuario" || !known.containsKey(key(m)))) known[key(m)] = p
        }
        for (e in d.entries) {
            val t = Days.time(e.tsStart, zone)
            if (e.kind == EntryKind.INTERACTION) {
                sb.append("[$t] Você perguntou à IA: ").append(e.text.trim().take(500)).append('\n')
                if (e.answer.isNotBlank()) sb.append("[$t] IA respondeu: ").append(e.answer.trim().take(300)).append('\n')
                continue
            }
            val meta = runCatching { JSONObject(e.metaJson) }.getOrDefault(JSONObject())
            val label = meta.optString("label")
            val body = body(e)
            val who = when {
                meta.optString("person").isNotEmpty() -> meta.optString("person")
                label.isNotEmpty() && known.containsKey(key(meta)) -> known.getValue(key(meta))
                meta.optString("speaker") == "wearer" -> "Você"
                label.isNotEmpty() -> {
                    val tag = chunkTags.getOrPut("${meta.optString("segment")}#${meta.optInt("chunk")}") { chunkTags.size + 1 }
                    "$label·$tag".also { labels.getOrPut(it) { label to ArrayList() }.second.add(e.id) }
                }
                else -> "Falante"
            }
            sb.append("[$t] $who: ").append(body.take(2000)).append('\n')
        }
        return Lines(sb.toString(), labels)
    }

    private class Analysis(val newItems: List<Item>, val closedItems: List<Item>, val named: List<String>)

    private suspend fun analyse(d: Draft, hash: String, reanalysis: Boolean, now: Long): Analysis {
        val lines = transcript(d)
        if (reanalysis) { store.unlinkBlock(d.id); store.dropOpenItemsOfBlock(d.id) }
        val people = store.people().take(30)
        val open = store.openItems(40)
        val nowLocal = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        val context = buildString {
            append("Agora: ").append(nowLocal.format(DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy HH:mm", Locale.forLanguageTag("pt-BR")))).append('\n')
            append("Conversa de ").append(Days.label(d.day)).append(", ").append(Days.time(d.tsStart, zone)).append("–").append(Days.time(d.tsEnd, zone)).append('\n')
            if (people.isNotEmpty()) append("Pessoas conhecidas: ").append(people.joinToString("; ") { p ->
                p.name + (if (p.aliases.isNotEmpty()) " (também: ${p.aliases.take(3).joinToString()})" else "") + (if (p.relation.isNotBlank()) " — ${p.relation}" else "")
            }).append('\n')
            if (open.isNotEmpty()) {
                append("Pendências abertas:\n")
                open.forEach { i -> append("#").append(i.id).append(" ").append(i.text)
                    i.personId?.let { pid -> people.firstOrNull { it.id == pid }?.let { append(" (com ").append(it.name).append(")") } }
                    if (i.dueText.isNotBlank()) append(" — ").append(i.dueText)
                    append('\n') }
            }
            append("\nTranscrição:\n").append(lines.text)
        }
        val json = complete(context) ?: throw IllegalStateException("json")
        val category = Categories.of(json.optString("categoria"))
        val relevance = json.optDouble("relevancia", 0.5).let { if (it.isNaN()) 0.5 else it.coerceIn(0.0, 1.0) }
        val summary = json.optString("resumo").trim().take(400)
        val policy = settings().policy(category)
        if (policy == CategoryPolicy.DELETE) {
            d.entries.filter { it.kind == EntryKind.AMBIENT }.forEach { journal.delete(it.id) }
            store.saveBlock(Block(d.id, d.day, d.tsStart, d.tsEnd, d.entries.filter { it.kind != EntryKind.AMBIENT }.map { it.id }, hash, category, 0.0,
                "Conversa de ${Categories.LABELS[category] ?: category} removida (configuração de privacidade).", now))
            return Analysis(emptyList(), emptyList(), emptyList())
        }
        store.saveBlock(Block(d.id, d.day, d.tsStart, d.tsEnd, d.entries.map { it.id }, hash, category, relevance, summary, now))
        if (policy == CategoryPolicy.IGNORE) return Analysis(emptyList(), emptyList(), emptyList())

        // Speakers named from context (evidence + confidence), never over a user correction.
        val named = ArrayList<String>()
        json.optJSONArray("falantes")?.let { arr ->
            for (i in 0 until arr.length()) {
                val f = arr.optJSONObject(i) ?: continue
                val name = f.optString("nome").trim(); val evidence = f.optString("evidencia").trim()
                val target = lines.labels[f.optString("rotulo").trim()] ?: continue
                if (name.isEmpty() || name.equals("null", true) || evidence.isEmpty() || f.optDouble("confianca", 0.0) < 0.7) continue
                val person = store.upsertPerson(name, seenAt = d.tsEnd)
                store.linkPerson(person.id, d.id, "falante")
                nameEntries(target.second, target.first, person, "contexto")
                named.add(person.name)
            }
        }
        json.optJSONArray("pessoas")?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val name = p.optString("nome").trim()
                if (name.isEmpty() || name.equals("null", true) || name.equals("você", true)) continue
                val facts = p.optJSONArray("fatos")?.let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() } } ?: emptyList()
                val person = store.upsertPerson(name, p.optString("relacao"), facts, d.tsEnd)
                store.linkPerson(person.id, d.id, "mencionado")
            }
        }

        val closed = ArrayList<Item>()
        json.optJSONArray("itens_concluidos")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val item = open.firstOrNull { it.id == c.optLong("id", -1) } ?: continue
                store.setStatus(item.id, ItemStatus.DONE, now, c.optString("evidencia").trim().take(300))
                closed.add(item)
            }
        }

        val created = ArrayList<Item>()
        json.optJSONArray("itens_novos")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val text = o.optString("texto").trim().take(300)
                if (text.length < 3 || o.optDouble("confianca", 0.0) < 0.5) continue
                val personName = o.optString("pessoa").trim()
                val person = if (personName.isEmpty() || personName.equals("null", true) || personName.equals("você", true)) null
                    else store.upsertPerson(personName, seenAt = d.tsEnd).also { store.linkPerson(it.id, d.id, "mencionado") }
                val due = parseDue(o.optString("quando"), now)
                val sourceTs = parseHour(d.day, o.optString("horario")) ?: d.tsStart
                val draft = Item(kind = ItemKind.of(o.optString("tipo")), text = text, owner = if (o.optString("responsavel").trim().lowercase() == "outro") "outro" else "eu",
                    personId = person?.id, dueAt = due, dueText = o.optString("quando_texto").trim().take(80), blockId = d.id, sourceTs = sourceTs,
                    quote = o.optString("citacao").trim().take(300), confidence = o.optDouble("confianca", 0.0), createdAt = now, updatedAt = now)
                val sameId = if (o.isNull("mesmo_que")) -1L else o.optLong("mesmo_que", -1)
                val same = store.openItems(400).firstOrNull { it.id == sameId }
                    ?: store.openItems(400).firstOrNull { jaccard(tokens(it.text), tokens(text)) >= 0.8 }
                if (same != null) {
                    store.updateItem(same.copy(dueAt = draft.dueAt ?: same.dueAt, dueText = draft.dueText.ifBlank { same.dueText },
                        personId = same.personId ?: draft.personId, quote = draft.quote.ifBlank { same.quote }, updatedAt = now,
                        remindedAt = if (draft.dueAt != null && draft.dueAt != same.dueAt) null else same.remindedAt))
                    continue
                }
                if (store.wasDismissed(text)) continue
                created.add(draft.copy(id = store.addItem(draft)))
            }
        }
        return Analysis(created, closed, named)
    }

    /** "Me lembra de…" by voice/text: one model call to normalize text and deadline; still saved if the call fails. */
    suspend fun quickItem(request: String, now: Long): Item {
        val nowLocal = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        val ctx = "Agora: " + nowLocal.format(DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy HH:mm", Locale.forLanguageTag("pt-BR"))) + "\nPedido: " + request.trim()
        val j = runCatching { parse(llm.complete(MemoryPrompts.QUICK, ctx, 300)) }.getOrNull()
        val text = j?.optString("texto")?.trim()?.takeIf { it.length >= 3 } ?: request.trim().replaceFirstChar { it.uppercase() }
        val personName = j?.optString("pessoa")?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", true) }
        val person = personName?.let { store.upsertPerson(it, seenAt = now) }
        val due = j?.let { parseDue(it.optString("quando"), now) }
        val draft = Item(kind = ItemKind.of(j?.optString("tipo")), text = text.take(300), personId = person?.id, dueAt = due,
            dueText = j?.optString("quando_texto")?.trim()?.take(80) ?: "", blockId = "voz", sourceTs = now, quote = request.trim().take(300),
            confidence = 1.0, createdAt = now, updatedAt = now)
        store.openItems(400).firstOrNull { jaccard(tokens(it.text), tokens(text)) >= 0.8 }?.let { same ->
            val merged = same.copy(dueAt = due ?: same.dueAt, dueText = draft.dueText.ifBlank { same.dueText }, updatedAt = now,
                remindedAt = if (due != null && due != same.dueAt) null else same.remindedAt)
            store.updateItem(merged); return merged
        }
        return draft.copy(id = store.addItem(draft))
    }

    /** Renames anonymous diarization labels to a person in the journal; user corrections win. */
    fun nameEntries(ids: List<Long>, label: String, person: Person, method: String) {
        for (e in journal.byIds(ids)) {
            val meta = runCatching { JSONObject(e.metaJson) }.getOrDefault(JSONObject())
            if (method != "usuario" && meta.optString("person_method") == "usuario") continue
            meta.put("person", person.name).put("person_id", person.id).put("person_method", method)
            if (meta.optString("label").isEmpty()) meta.put("label", label)
            val text = body(e)
            meta.put("shown", person.name)
            journal.relabel(e.id, "${person.name}: $text", meta.toString())
        }
    }

    /** User correction from the journal screen: every entry of that label in that transcription chunk becomes [name]. */
    fun assignSpeaker(entry: Entry, name: String): Person? {
        val meta = runCatching { JSONObject(entry.metaJson) }.getOrNull() ?: return null
        val label = meta.optString("label").ifEmpty { return null }
        val segment = meta.optString("segment"); val chunk = meta.optInt("chunk", -1)
        val siblings = journal.day(entry.day).filter { e ->
            val m = runCatching { JSONObject(e.metaJson) }.getOrNull()
            m != null && m.optString("label") == label && m.optString("segment") == segment && m.optInt("chunk", -1) == chunk
        }
        val person = store.upsertPerson(name, seenAt = entry.tsEnd)
        nameEntries(siblings.map { it.id }, label, person, "usuario")
        drafts(journal.day(entry.day)).firstOrNull { d -> d.entries.any { it.id == entry.id } }?.let { store.linkPerson(person.id, it.id, MemoryStore.USER_ROLE) }
        return person
    }

    private suspend fun complete(user: String): JSONObject? {
        parse(llm.complete(MemoryPrompts.EXTRACT, user, 2000))?.let { return it }
        return parse(llm.complete(MemoryPrompts.EXTRACT, user + "\n\n" + MemoryPrompts.RETRY, 2000))
    }

    private fun parseDue(raw: String, now: Long): Long? {
        val s = raw.trim()
        if (s.isEmpty() || s.equals("null", true)) return null
        val at = runCatching { LocalDateTime.parse(s.take(16)) }.getOrNull()
            ?: runCatching { LocalDate.parse(s.take(10)).atTime(9, 0) }.getOrNull() ?: return null
        val ms = at.atZone(zone).toInstant().toEpochMilli()
        return if (ms < now - 24 * 3600_000L || ms > now + 400L * 24 * 3600_000) null else ms
    }

    private fun parseHour(day: String, hhmm: String): Long? = runCatching {
        LocalDate.parse(day).atTime(LocalTime.parse(hhmm.trim().take(5))).atZone(zone).toInstant().toEpochMilli()
    }.getOrNull()

    companion object {
        fun parse(raw: String): JSONObject? {
            val start = raw.indexOf('{'); val end = raw.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrNull()
        }
    }
}
