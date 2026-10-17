package org.turboio.customai.memory

import org.json.JSONArray
import org.json.JSONObject
import org.turboio.customai.insights.Llm
import org.turboio.customai.journal.Days
import org.turboio.customai.journal.Entry
import org.turboio.customai.journal.JournalStore
import org.turboio.customai.journal.StoredInsight
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

object QueryPrompts {
    const val PLAN = """Você transforma uma pergunta sobre o diário pessoal em parâmetros de busca. Responda APENAS com JSON:
{"termos": ["5 a 10 palavras ou expressões curtas: as da pergunta e sinônimos/variações prováveis em português"],
 "de": "AAAA-MM-DD ou vazio", "ate": "AAAA-MM-DD ou vazio", "pessoa": "nome citado ou vazio"}
Calcule o período a partir da data de hoje informada ("semana passada", "ontem", "em setembro"…). Sem período, deixe vazio."""

    const val ANSWER = """Você responde perguntas sobre o diário pessoal do usuário usando SOMENTE os registros fornecidos.
Português do Brasil, curto (até 3 frases), citando data e hora (dd/MM HH:MM) de onde veio cada informação.
Se os registros não responderem, diga isso em uma frase."""

    const val PERSON = """Com base SOMENTE nos dados fornecidos sobre uma pessoa (conversas resumidas, pendências e fatos), responda em
português do Brasil, em até 3 frases curtas, o que o usuário precisa saber sobre ela agora: o que está pendente e o que
foi conversado por último (com data)."""

    const val WEEKLY = """Você faz a revisão semanal do diário pessoal de alguém, usando SOMENTE os dados fornecidos. Português do Brasil.
Responda APENAS com JSON: {"resumo": "3 a 5 frases", "avancos": [""], "travado": [""], "recorrente": [""],
"pessoas": ["nome — por quê"], "sugestoes": ["até 3 próximos passos concretos"]}"""
}

/** Read side of the memory: search, person cards, pending list, morning brief, weekly review. */
class MemoryQueries(
    private val journal: JournalStore,
    private val store: MemoryStore,
    private val llm: Llm,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val dm = DateTimeFormatter.ofPattern("dd/MM")
    private val WEEKDAYS = listOf("seg", "ter", "qua", "qui", "sex", "sáb", "dom")
    private fun stamp(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).let { it.format(dm) + " " + Days.time(ms, zone) }

    fun dueLabel(i: Item, now: Long): String {
        val due = i.dueAt ?: return i.dueText
        val d = Instant.ofEpochMilli(due).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val day = when (d.toLocalDate()) {
            today -> "hoje"; today.plusDays(1) -> "amanhã"; today.minusDays(1) -> "ontem"
            else -> WEEKDAYS[d.dayOfWeek.value - 1] + " " + d.format(dm)
        }
        return "$day ${Days.time(due, zone)}"
    }

    fun line(i: Item, now: Long): String {
        val who = i.personId?.let { store.person(it)?.name }
        return buildString {
            append(i.text)
            if (who != null && !i.text.contains(who, true)) append(" (").append(who).append(")")
            dueLabel(i, now).takeIf { it.isNotBlank() }?.let { append(" — ").append(it) }
        }
    }

    // ------------------------------------------------------------------ pending
    fun pendingAnswer(now: Long, personName: String? = null, max: Int = 5): String {
        val person = personName?.let { store.findPerson(it) }
        if (personName != null && person == null) return "Não conheço ninguém chamado $personName no diário."
        val items = (if (person != null) store.itemsOf(person.id) else store.openItems()).filter { it.kind != ItemKind.DECISION }
        if (items.isEmpty()) return if (person != null) "Nada pendente com ${person.name}." else "Nenhuma pendência aberta."
        val overdue = items.count { (it.dueAt ?: Long.MAX_VALUE) < now }
        val head = "${items.size} pendência(s)" + (if (overdue > 0) ", $overdue atrasada(s)" else "") + ": "
        return head + items.take(max).joinToString("; ") { line(it, now) }
    }

    // ------------------------------------------------------------------ morning brief
    fun morningBrief(now: Long): String? {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val endOfDay = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val open = store.openItems().filter { it.kind != ItemKind.DECISION }
        if (open.isEmpty()) return null
        val overdue = open.filter { (it.dueAt ?: Long.MAX_VALUE) < now }
        val todays = open.filter { it.dueAt != null && it.dueAt >= now && it.dueAt < endOfDay }
        val others = open.filter { it !in overdue && it !in todays }.take(3)
        return buildString {
            append("Bom dia! ")
            if (todays.isNotEmpty()) append("Hoje: ").append(todays.joinToString("; ") { line(it, now) }).append(". ")
            if (overdue.isNotEmpty()) append("Atrasadas: ").append(overdue.take(3).joinToString("; ") { line(it, now) }).append(". ")
            if (others.isNotEmpty()) append("Também pendente: ").append(others.joinToString("; ") { line(it, now) }).append(".")
        }.trim()
    }

    // ------------------------------------------------------------------ person
    fun personCard(p: Person, now: Long): String = buildString {
        append(p.name)
        if (p.relation.isNotBlank()) append(" — ").append(p.relation)
        append("\n").append(p.conversations).append(" conversa(s)")
        if (p.lastSeen > 0) append(" · última em ").append(stamp(p.lastSeen))
        if (p.aliases.isNotEmpty()) append("\nTambém chamada de: ").append(p.aliases.joinToString())
        if (p.facts.isNotEmpty()) { append("\n\nFatos:"); p.facts.takeLast(8).forEach { append("\n- ").append(it) } }
        val items = store.itemsOf(p.id)
        if (items.isNotEmpty()) { append("\n\nPendências:"); items.take(8).forEach { append("\n- ").append(line(it, now)) } }
        val blocks = store.blocksOf(p.id, 6).filter { it.summary.isNotBlank() }
        if (blocks.isNotEmpty()) { append("\n\nÚltimas conversas:"); blocks.forEach { append("\n- ").append(stamp(it.tsStart)).append(": ").append(it.summary) } }
    }

    suspend fun personAnswer(name: String, now: Long): String {
        val p = store.findPerson(name) ?: return "Não conheço ninguém chamado $name no diário."
        return llm.complete(QueryPrompts.PERSON, personCard(p, now), 400).trim()
    }

    // ------------------------------------------------------------------ search
    data class Plan(val terms: List<String>, val from: String?, val to: String?, val person: String?)

    suspend fun plan(question: String, now: Long): Plan {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val raw = llm.complete(QueryPrompts.PLAN, "Hoje: ${today} (${today.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.forLanguageTag("pt-BR"))})\nPergunta: $question", 300)
        val j = MemoryEngine.parse(raw)
        fun day(key: String) = j?.optString(key)?.trim()?.takeIf { runCatching { LocalDate.parse(it) }.isSuccess }
        val terms = j?.optJSONArray("termos")?.let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.length >= 2 } }.orEmpty()
        return Plan(terms.ifEmpty { listOf(question) }.take(12), day("de"), day("ate"), j?.optString("pessoa")?.trim()?.takeIf { it.isNotEmpty() && it != "null" })
    }

    suspend fun search(question: String, now: Long): String {
        val plan = plan(question, now)
        val person = plan.person?.let { store.findPerson(it) }
        var hits: List<Entry> = journal.searchAny(plan.terms + listOfNotNull(person?.name), plan.from, plan.to, 60)
        if (person != null) {
            val ids = store.blocksOf(person.id, 200).flatMap { it.entryIds }.toSet()
            val withPerson = hits.filter { it.id in ids || it.text.contains(person.name, true) }
            hits = withPerson.ifEmpty { hits }
        }
        val blocks = (if (plan.from != null || plan.to != null) store.blocks(plan.from ?: "0000-00-00", plan.to ?: "9999-99-99") else emptyList())
            .filter { b -> b.summary.isNotBlank() && plan.terms.any { t -> tokens(b.summary).intersect(tokens(t)).isNotEmpty() } }.take(10)
        if (hits.isEmpty() && blocks.isEmpty()) return "Não encontrei nada no diário sobre isso" + (if (plan.from != null) " nesse período." else ".")
        val context = buildString {
            blocks.forEach { append("[").append(stamp(it.tsStart)).append("] resumo da conversa: ").append(it.summary).append('\n') }
            hits.take(40).sortedBy { it.tsStart }.forEach { e ->
                append("[").append(stamp(e.tsStart)).append("] ").append(e.text.take(600))
                if (e.answer.isNotBlank()) append(" → IA: ").append(e.answer.take(300))
                append('\n')
            }
        }
        return llm.complete(QueryPrompts.ANSWER, "Registros:\n$context\nPergunta: $question", 400).trim()
    }

    // ------------------------------------------------------------------ weekly review
    fun weekStart(now: Long): LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    data class Weekly(val json: JSONObject, val text: String)

    suspend fun weekly(now: Long, dailies: (String) -> StoredInsight?): Weekly? {
        val start = weekStart(now)
        val days = (0..6).map { start.plusDays(it.toLong()).toString() }
        val from = start.atStartOfDay(zone).toInstant().toEpochMilli(); val to = start.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        val blocks = store.blocks(days.first(), days.last()).filter { it.relevance >= 0.25 && it.summary.isNotBlank() }
        val items = store.itemsBetween(from, to)
        if (blocks.isEmpty() && items.isEmpty()) return null
        val context = buildString {
            append("Semana de ").append(Days.label(days.first())).append(" a ").append(Days.label(days.last())).append('\n')
            days.forEach { d -> dailies(d)?.let { append("\nResumo de ").append(Days.label(d)).append(": ").append(it.text.take(800)).append('\n') } }
            append("\nConversas relevantes:\n"); blocks.take(60).forEach { append("- ").append(stamp(it.tsStart)).append(" [").append(it.category).append("] ").append(it.summary).append('\n') }
            append("\nPendências criadas/concluídas:\n"); items.forEach { append("- [").append(it.status.id).append("] ").append(line(it, now)).append('\n') }
            val people = store.people().filter { it.lastSeen >= from }.sortedByDescending { it.conversations }.take(10)
            if (people.isNotEmpty()) append("\nPessoas: ").append(people.joinToString { "${it.name} (${it.conversations})" }).append('\n')
        }
        val j = MemoryEngine.parse(llm.complete(QueryPrompts.WEEKLY, context, 1200)) ?: return null
        return Weekly(j, renderWeekly(j))
    }

    fun renderWeekly(j: JSONObject): String = buildString {
        append(j.optString("resumo").trim()).append('\n')
        fun section(title: String, key: String) {
            val a = j.optJSONArray(key) ?: JSONArray()
            val list = (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() }
            if (list.isNotEmpty()) { append('\n').append(title).append('\n'); list.forEach { append("- ").append(it).append('\n') } }
        }
        section("Avanços", "avancos"); section("Travado", "travado"); section("Recorrente", "recorrente")
        section("Pessoas", "pessoas"); section("Sugestões", "sugestoes")
    }.trim()
}
