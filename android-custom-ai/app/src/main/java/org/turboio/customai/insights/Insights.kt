package org.turboio.customai.insights

import org.json.JSONArray
import org.json.JSONObject
import org.turboio.customai.diag.DiagnosticLog
import org.turboio.customai.journal.Days
import org.turboio.customai.journal.Entry
import org.turboio.customai.journal.EntryKind
import org.turboio.customai.journal.JournalStore
import org.turboio.customai.journal.StoredInsight
import java.security.MessageDigest

/** Text-in/text-out model call used by the insight engine (implemented with the configured provider). */
fun interface Llm { suspend fun complete(system: String, user: String, maxTokens: Int): String }

data class Commitment(val quando: String, val oQue: String)

data class DayInsight(
    val resumo: String = "",
    val destaques: List<String> = emptyList(),
    val topicos: List<String> = emptyList(),
    val tarefas: List<String> = emptyList(),
    val decisoes: List<String> = emptyList(),
    val compromissos: List<Commitment> = emptyList(),
    val pessoas: List<String> = emptyList(),
    val citacoes: List<String> = emptyList(),
) {
    fun toJson(): String = JSONObject()
        .put("resumo", resumo).put("destaques", JSONArray(destaques)).put("topicos", JSONArray(topicos))
        .put("tarefas", JSONArray(tarefas)).put("decisoes", JSONArray(decisoes))
        .put("compromissos", JSONArray(compromissos.map { JSONObject().put("quando", it.quando).put("o_que", it.oQue) }))
        .put("pessoas", JSONArray(pessoas)).put("citacoes", JSONArray(citacoes)).toString()

    /** Full report for the phone. */
    fun render(): String = buildString {
        if (resumo.isNotBlank()) append(resumo.trim()).append("\n")
        fun section(title: String, items: List<String>) {
            if (items.isNotEmpty()) { append("\n").append(title).append("\n"); items.forEach { append("- ").append(it).append("\n") } }
        }
        section("Destaques", destaques)
        section("Tarefas", tarefas)
        section("Decisões", decisoes)
        section("Compromissos", compromissos.map { if (it.quando.isNotBlank()) "${it.quando}: ${it.oQue}" else it.oQue })
        section("Pessoas", pessoas)
        section("Tópicos", topicos)
        section("Citações", citacoes)
    }.trim()

    /** Short version for the lenses (≈ 3 pages). */
    fun lens(maxChars: Int = 270): String {
        val parts = ArrayList<String>()
        if (resumo.isNotBlank()) parts.add(resumo.trim())
        if (tarefas.isNotEmpty()) parts.add("Tarefas: " + tarefas.take(3).joinToString("; "))
        val text = parts.joinToString(" ")
        return if (text.length <= maxChars) text else text.take(maxChars - 1).trimEnd() + "…"
    }

    val empty: Boolean get() = resumo.isBlank() && destaques.isEmpty() && topicos.isEmpty() && tarefas.isEmpty() &&
        decisoes.isEmpty() && compromissos.isEmpty() && pessoas.isEmpty() && citacoes.isEmpty()

    companion object {
        private fun list(o: JSONObject, key: String, max: Int = 15): List<String> {
            val a = o.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).mapNotNull { a.opt(it)?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() && s != "null" } }
                .distinct().take(max)
        }

        fun fromJson(o: JSONObject) = DayInsight(
            resumo = o.optString("resumo").trim(),
            destaques = list(o, "destaques", 5), topicos = list(o, "topicos"), tarefas = list(o, "tarefas"),
            decisoes = list(o, "decisoes"), pessoas = list(o, "pessoas"), citacoes = list(o, "citacoes", 5),
            compromissos = (o.optJSONArray("compromissos") ?: JSONArray()).let { a ->
                (0 until a.length()).mapNotNull { i ->
                    when (val v = a.opt(i)) {
                        is JSONObject -> Commitment(v.optString("quando").trim(), v.optString("o_que").ifBlank { v.optString("oQue") }.trim())
                        is String -> Commitment("", v.trim())
                        else -> null
                    }?.takeIf { it.oQue.isNotEmpty() }
                }.take(15)
            },
        )

        /** Tolerant extraction: strips code fences/prose around the first JSON object. */
        fun parse(raw: String): DayInsight? {
            val start = raw.indexOf('{'); val end = raw.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return try { fromJson(JSONObject(raw.substring(start, end + 1))) } catch (_: Exception) { null }
        }
    }
}

object InsightPrompts {
    const val VERSION = "v1"
    private const val SCHEMA = """{"resumo": "3 a 5 frases", "destaques": ["até 3 itens"], "topicos": [], "tarefas": ["ação a fazer, com responsável se houver"], "decisoes": [], "compromissos": [{"quando": "data/hora mencionada ou vazio", "o_que": ""}], "pessoas": ["nomes citados"], "citacoes": ["até 3 frases marcantes, literais"]}"""

    val MAP = """Você analisa registros do diário pessoal de uma pessoa (perguntas que ela fez a um assistente de IA, respostas e transcrições de conversas do ambiente).
Extraia somente o que estiver nos registros; não invente nada. Use português do Brasil.
Responda APENAS com um objeto JSON válido, sem Markdown, exatamente neste formato:
$SCHEMA
Use listas vazias quando não houver itens. "Você" nos registros é a dona/o dono do diário."""

    val REDUCE = """Você recebe análises parciais (JSON) de um mesmo dia do diário de uma pessoa, em ordem cronológica.
Una tudo em um único relatório do dia: elimine duplicatas, mantenha os itens mais relevantes, escreva um "resumo" novo de 3 a 5 frases cobrindo o dia todo e escolha até 3 "destaques".
Não invente nada. Português do Brasil. Responda APENAS com um objeto JSON válido neste formato:
$SCHEMA"""

    const val RETRY = "Sua resposta anterior não era um JSON válido. Responda novamente APENAS com o objeto JSON pedido, sem nenhum texto antes ou depois."

    const val SEARCH = """Você responde perguntas sobre o diário pessoal do usuário usando SOMENTE os registros fornecidos.
Responda em português do Brasil, de forma curta (até 3 frases), citando o horário (HH:MM) quando útil.
Se os registros não responderem à pergunta, diga isso em uma frase."""
}

/**
 * Map-reduce daily insight with caching:
 *  - entries are grouped into windows (~[windowChars]) without splitting an entry;
 *  - each window → partial JSON (cached by window hash), all partials → final JSON (cached by day hash);
 *  - a growing day only re-runs the windows that changed.
 */
class InsightEngine(
    private val store: JournalStore,
    private val llm: Llm,
    private val modelTag: () -> Pair<String, String>,   // provider, model (part of cache key)
    private val windowChars: Int = 20_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Result(val insight: DayInsight, val text: String, val cached: Boolean, val windows: Int)

    fun format(e: Entry): String {
        val time = Days.time(e.tsStart)
        return when (e.kind) {
            EntryKind.INTERACTION -> "[$time · ${e.source.label}] Você: ${e.text.trim().take(4000)}" +
                if (e.answer.isNotBlank()) "\n[$time] IA: ${e.answer.trim().take(4000)}" else ""
            else -> "[$time · ${e.source.label}] ${speakerPrefix(e)}${e.text.trim().take(4000)}"
        }
    }

    private fun speakerPrefix(e: Entry): String = try {
        when (org.json.JSONObject(e.metaJson).optString("speaker")) { "wearer" -> "Você: "; "other" -> "Outra pessoa: "; else -> "Ambiente: " }
    } catch (_: Exception) { "Ambiente: " }

    fun windows(entries: List<Entry>): List<String> {
        val out = ArrayList<String>(); val current = StringBuilder()
        for (e in entries) {
            val line = format(e)
            if (current.isNotEmpty() && current.length + line.length + 1 > windowChars) { out.add(current.toString()); current.setLength(0) }
            current.append(line).append('\n')
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out
    }

    private fun hash(vararg parts: String): String {
        val d = MessageDigest.getInstance("SHA-256")
        parts.forEach { d.update(it.toByteArray()); d.update(0) }
        return d.digest().joinToString("") { "%02x".format(it) }.take(32)
    }

    private suspend fun structured(system: String, user: String): DayInsight? {
        val first = llm.complete(system, user, 1500)
        DayInsight.parse(first)?.let { return it }
        DiagnosticLog.w("INSIGHT", "resposta não-JSON; nova tentativa")
        return DayInsight.parse(llm.complete(system, user + "\n\n" + InsightPrompts.RETRY, 1500))
    }

    suspend fun day(day: String, force: Boolean = false): Result {
        val entries = store.day(day).filter { !it.metaJson.contains("\"journal_query\":true") }
        if (entries.isEmpty()) return Result(DayInsight(resumo = "Nenhum registro em ${Days.label(day)}."), "Nenhum registro em ${Days.label(day)}.", true, 0)
        val (provider, model) = modelTag()
        val windows = windows(entries)
        val dayHash = hash(InsightPrompts.VERSION, provider, model, *windows.toTypedArray())
        if (!force) store.insight(day, "daily", dayHash)?.let { cached ->
            DayInsight.parse(cached.json)?.let { return Result(it, cached.text, true, windows.size) }
        }
        val header = "Dia ${Days.label(day)} (horários locais).\n"
        val partials = windows.mapIndexed { i, w ->
            val wHash = hash(InsightPrompts.VERSION, provider, model, w)
            val cached = if (force) null else store.insight(day, "partial", wHash)?.let { DayInsight.parse(it.json) }
            cached ?: run {
                DiagnosticLog.i("INSIGHT", "analisando bloco ${i + 1}/${windows.size} (${w.length} caracteres)")
                val partial = structured(InsightPrompts.MAP, header + "Registros (bloco ${i + 1} de ${windows.size}):\n" + w)
                    ?: DayInsight(resumo = "Bloco ${i + 1}: análise indisponível (resposta inválida do modelo).")
                store.saveInsight(StoredInsight(day, "partial", wHash, partial.toJson(), partial.render(), provider, model, now()))
                partial
            }
        }
        val final = if (partials.size == 1) partials[0] else {
            DiagnosticLog.i("INSIGHT", "consolidando ${partials.size} blocos")
            val joined = partials.mapIndexed { i, p -> "Bloco ${i + 1}: ${p.toJson()}" }.joinToString("\n")
            structured(InsightPrompts.REDUCE, header + joined) ?: merge(partials)
        }
        val text = final.render()
        store.saveInsight(StoredInsight(day, "daily", dayHash, final.toJson(), text, provider, model, now()))
        return Result(final, text, false, windows.size)
    }

    /** Deterministic fallback when the reduce step fails. */
    fun merge(parts: List<DayInsight>) = DayInsight(
        resumo = parts.map { it.resumo }.filter { it.isNotBlank() }.joinToString(" "),
        destaques = parts.flatMap { it.destaques }.distinct().take(3),
        topicos = parts.flatMap { it.topicos }.distinct().take(15),
        tarefas = parts.flatMap { it.tarefas }.distinct().take(15),
        decisoes = parts.flatMap { it.decisoes }.distinct().take(15),
        compromissos = parts.flatMap { it.compromissos }.distinctBy { it.quando + "|" + it.oQue }.take(15),
        pessoas = parts.flatMap { it.pessoas }.distinct().take(15),
        citacoes = parts.flatMap { it.citacoes }.distinct().take(5),
    )

    /** "O que eu falei sobre X?" — full-text retrieval + grounded short answer. */
    suspend fun ask(question: String, term: String, day: String?): String {
        val hits = store.search(term, day, 20).filter { !it.metaJson.contains("\"journal_query\":true") }.sortedBy { it.tsStart }
        val where = if (day != null) "em ${Days.label(day)}" else "no diário"
        if (hits.isEmpty()) return "Não encontrei registros sobre \"$term\" $where."
        val context = hits.joinToString("\n") { "${Days.label(it.day)} ${format(it)}" }
        return llm.complete(InsightPrompts.SEARCH, "Registros:\n$context\n\nPergunta: $question", 400).trim()
    }
}
