package org.turboio.customai.journal

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.turboio.customai.insights.DailyInsightWorker
import org.turboio.customai.insights.DayInsight
import org.turboio.customai.insights.InsightEngine
import org.turboio.customai.insights.InsightPrompts
import org.turboio.customai.insights.JournalIntent
import org.turboio.customai.insights.JournalIntents
import org.turboio.customai.insights.Llm
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import java.time.LocalDateTime
import java.time.ZoneId

/** Real SQLite (xerial JDBC, includes FTS4) behind the same [SqlDb] interface the app uses on Android. */
class JdbcSqlDb : SqlDb {
    private val c: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")
    private fun bind(sql: String, args: Array<out Any?>, keys: Boolean = false) =
        (if (keys) c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS) else c.prepareStatement(sql)).apply {
            args.forEachIndexed { i, v -> setObject(i + 1, v) }
        }
    override fun exec(sql: String, vararg args: Any?) { bind(sql, args).use { it.execute() } }
    override fun insert(sql: String, vararg args: Any?): Long = bind(sql, args, true).use {
        it.executeUpdate(); it.generatedKeys.use { k -> k.next(); k.getLong(1) }
    }
    override fun query(sql: String, vararg args: Any?): List<Map<String, Any?>> = bind(sql, args).use { st ->
        st.executeQuery().use { rs ->
            val out = ArrayList<Map<String, Any?>>()
            while (rs.next()) out.add((1..rs.metaData.columnCount).associate { rs.metaData.getColumnLabel(it) to rs.getObject(it) })
            out
        }
    }
    private var depth = 0
    override fun <T> transaction(block: () -> T): T {
        if (depth++ == 0) c.autoCommit = false
        try { val r = block(); if (depth == 1) c.commit(); return r }
        catch (e: Throwable) { if (depth == 1) c.rollback(); throw e }
        finally { if (--depth == 0) c.autoCommit = true }
    }
}

/** Scripted model: records calls and answers by prompt kind. */
class FakeLlm(var mapReply: (String) -> String, var reduceReply: (String) -> String = { it }) : Llm {
    val calls = ArrayList<Pair<String, String>>()
    override suspend fun complete(system: String, user: String, maxTokens: Int): String {
        calls.add(system to user)
        return when (system) {
            InsightPrompts.MAP -> mapReply(user)
            InsightPrompts.REDUCE -> reduceReply(user)
            else -> "Resposta da busca: " + user.lines().count { it.contains("·") } + " registro(s)"
        }
    }
}

class JournalTest {
    private val zone = ZoneId.systemDefault()
    private val store = JournalStore(JdbcSqlDb())
    private fun at(day: String, hh: Int, mm: Int) = LocalDateTime.parse("${day}T%02d:%02d:00".format(hh, mm)).atZone(zone).toInstant().toEpochMilli()
    private fun interaction(day: String, hh: Int, q: String, a: String, meta: String = "{}") =
        store.add(Entry(day = day, tsStart = at(day, hh, 0), tsEnd = at(day, hh, 1), kind = EntryKind.INTERACTION,
            source = EntrySource.VOICE_GLASSES, text = q, answer = a, provider = "openai", model = "m", metaJson = meta))

    @Test fun storesListsAndSearchesWithoutAccents() {
        interaction("2026-10-16", 9, "Qual o horário da reunião de orçamento?", "Às 14h na sala Ipê.")
        interaction("2026-10-16", 15, "Me lembre de ligar para o João", "Ok: ligar para o João.")
        interaction("2026-10-15", 10, "Receita de pão de queijo", "Polvilho, queijo e ovos.")
        assertEquals(2, store.day("2026-10-16").size)
        assertEquals(listOf("2026-10-16" to 2, "2026-10-15" to 1), store.days())
        assertEquals(1, store.search("orcamento").size)                  // accent-insensitive
        assertEquals(1, store.search("Reunião ORÇAMENTO").size)            // all terms, case-insensitive
        assertEquals(1, store.search("joa").size)                          // prefix match
        assertEquals(0, store.search("orçamento", day = "2026-10-15").size)
        assertEquals(1, store.search("pao queijo").size)
        assertTrue(store.search("x").isEmpty())                            // too short
    }

    @Test fun deleteRetentionAndWipe() {
        val id = interaction("2026-10-16", 9, "apagar este", "ok")
        interaction("2026-10-01", 9, "antigo", "ok")
        store.delete(id)
        assertTrue(store.search("apagar").isEmpty())
        assertEquals(1, store.applyRetention("2026-10-16", keepDays = 7))
        assertTrue(store.day("2026-10-01").isEmpty())
        assertEquals(0, store.applyRetention("2026-10-16", keepDays = 0))
        interaction("2026-10-16", 10, "outro", "ok"); store.wipe()
        assertTrue(store.days().isEmpty())
    }

    private val insightJson = """```json
        {"resumo":"Dia com reunião de orçamento e lembrete.","destaques":["Reunião às 14h"],"topicos":["orçamento"],
         "tarefas":["Ligar para o João","Ligar para o João"],"decisoes":[],"compromissos":[{"quando":"14h","o_que":"Reunião"}],
         "pessoas":["João"],"citacoes":[]}
        ```"""

    @Test fun insightSingleWindowIsCachedUntilDayChanges() = runBlocking {
        interaction("2026-10-16", 9, "Qual o horário da reunião de orçamento?", "Às 14h.")
        val llm = FakeLlm({ insightJson })
        val engine = InsightEngine(store, llm, { "openai" to "m" })
        val first = engine.day("2026-10-16")
        assertFalse(first.cached); assertEquals(1, llm.calls.size)
        assertEquals(listOf("Ligar para o João"), first.insight.tarefas)          // de-duplicated
        assertEquals("14h: Reunião", first.text.lines().first { it.startsWith("- 14h") }.removePrefix("- "))
        assertTrue(first.insight.lens().length <= 270)
        assertTrue(engine.day("2026-10-16").cached); assertEquals(1, llm.calls.size)   // cache hit
        interaction("2026-10-16", 15, "nova pergunta", "nova resposta")
        assertFalse(engine.day("2026-10-16").cached); assertEquals(2, llm.calls.size)  // new entry → recompute
        assertTrue(engine.day("2026-10-16", force = true).let { !it.cached })
    }

    @Test fun mapReduceOnlyRecomputesChangedWindows() = runBlocking {
        repeat(6) { interaction("2026-10-16", 8 + it, "pergunta longa $it " + "x".repeat(900), "resposta $it") }
        val llm = FakeLlm({ """{"resumo":"parcial","tarefas":["t"]}""" }, { """{"resumo":"dia inteiro","destaques":["d"],"tarefas":["t"]}""" })
        val engine = InsightEngine(store, llm, { "p" to "m" }, windowChars = 2500)
        val r = engine.day("2026-10-16")
        assertTrue(r.windows >= 3)
        assertEquals("dia inteiro", r.insight.resumo)
        val mapCalls = llm.calls.count { it.first == InsightPrompts.MAP }
        assertEquals(r.windows, mapCalls)
        interaction("2026-10-16", 20, "mais uma", "resposta")      // only the last window changes
        engine.day("2026-10-16")
        assertEquals(mapCalls + 1, llm.calls.count { it.first == InsightPrompts.MAP })
    }

    @Test fun invalidJsonRetriesThenFallsBack() = runBlocking {
        interaction("2026-10-16", 9, "pergunta", "resposta")
        var n = 0
        val llm = FakeLlm({ n++; if (n == 1) "Claro! Aqui está o resumo do dia." else """{"resumo":"ok na segunda"}""" })
        assertEquals("ok na segunda", InsightEngine(store, llm, { "p" to "m" }).day("2026-10-16").insight.resumo)
        assertTrue(llm.calls[1].second.contains(InsightPrompts.RETRY))
        val broken = FakeLlm({ "sem json" })
        assertTrue(InsightEngine(store, broken, { "p" to "m2" }).day("2026-10-16", force = true).insight.resumo.contains("indisponível"))
    }

    @Test fun journalQueriesAreExcludedFromInsights() = runBlocking {
        interaction("2026-10-16", 9, "resumo do meu dia", "...", meta = "{\"journal_query\":true}")
        val llm = FakeLlm({ insightJson })
        val r = InsightEngine(store, llm, { "p" to "m" }).day("2026-10-16")
        assertTrue(r.text.startsWith("Nenhum registro")); assertEquals(0, llm.calls.size)
    }

    @Test fun groundedSearchAnswer() = runBlocking {
        interaction("2026-10-16", 9, "Qual o horário da reunião de orçamento?", "Às 14h.")
        val llm = FakeLlm({ insightJson })
        val engine = InsightEngine(store, llm, { "p" to "m" })
        assertTrue(engine.ask("o que falei sobre orçamento", "orcamento", "2026-10-16").startsWith("Resposta da busca"))
        assertTrue(engine.ask("o que falei sobre viagem", "viagem", "2026-10-16").startsWith("Não encontrei"))
    }

    @Test fun insightParsingIsTolerant() {
        val parsed = DayInsight.parse("texto antes {\"resumo\":\"r\",\"compromissos\":[\"amanhã cedo\",{\"quando\":\"9h\",\"o_que\":\"médico\"}],\"pessoas\":null} depois")!!
        assertEquals("r", parsed.resumo); assertEquals(2, parsed.compromissos.size); assertTrue(parsed.pessoas.isEmpty())
        assertNull(DayInsight.parse("nada"))
        val round = DayInsight.parse(parsed.toJson())!!
        assertEquals(parsed, round)
        assertEquals("r", JSONObject(parsed.toJson()).getString("resumo"))
    }

    @Test fun intents() {
        assertEquals(JournalIntent.Summary(0), JournalIntents.detect("Qual o resumo do meu dia?"))
        assertEquals(JournalIntent.Summary(-1), JournalIntents.detect("como foi meu dia ontem"))
        assertEquals(JournalIntent.Summary(0), JournalIntents.detect("O que eu fiz hoje?"))
        assertEquals(JournalIntent.Tasks(0), JournalIntents.detect("Quais são minhas tarefas?"))
        assertEquals(JournalIntent.Tasks(-1), JournalIntents.detect("tarefas de ontem"))
        assertEquals(JournalIntent.Search("orcamento", -1), JournalIntents.detect("O que eu falei sobre orçamento ontem?"))
        assertEquals(JournalIntent.Search("viagem para salvador", null), JournalIntents.detect("procure no meu diário por viagem para Salvador"))
        assertEquals(JournalIntent.Pending(null), JournalIntents.detect("O que eu tenho pendente?"))
        assertEquals(JournalIntent.Pending(null), JournalIntents.detect("quais são minhas pendências"))
        assertEquals(JournalIntent.Pending("marina"), JournalIntents.detect("O que tenho pendente com a Marina?"))
        assertEquals(JournalIntent.Pending("joao"), JournalIntents.detect("o que eu combinei com o João"))
        assertEquals(JournalIntent.Person("marina"), JournalIntents.detect("O que eu conversei com a Marina?"))
        assertEquals(JournalIntent.Person("dr paulo".let { "paulo" }), JournalIntents.detect("última conversa com o doutor Paulo"))
        assertEquals(JournalIntent.Weekly, JournalIntents.detect("Qual o resumo da semana?"))
        assertEquals(JournalIntent.Weekly, JournalIntents.detect("como foi minha semana"))
        // Ordinary questions must go to the model, not the journal.
        listOf("Qual é a capital do Brasil?", "Me dê insights sobre investimentos", "Quais tarefas priorizar em um projeto?",
            "Faça um resumo da história do Brasil", "O que eu fiz de errado neste código?", "Como organizar tarefas?",
            "O que fazer com sobras de arroz?", "Quem é o presidente do Brasil?")
            .forEach { assertNull(it, JournalIntents.detect(it)) }
    }

    @Test fun exportMarkdown() {
        interaction("2026-10-16", 9, "pergunta", "resposta")
        val md = JournalExport.markdown("2026-10-16", store.day("2026-10-16"), null)
        assertTrue(md.startsWith("# Diário Turbo IO — 16/10/2026")); assertTrue(md.contains("**Você:** pergunta")); assertTrue(md.contains("**IA:** resposta"))
    }

    @Test fun dailyScheduleDelay() {
        val now = LocalDateTime.parse("2026-10-16T20:30:00")
        assertEquals(30, DailyInsightWorker.delayUntil(now, 21, 0).toMinutes())
        assertEquals(23 * 60 + 30, DailyInsightWorker.delayUntil(now, 20, 0).toMinutes())
        assertEquals(24 * 60, DailyInsightWorker.delayUntil(now, 20, 30).toMinutes())
    }
}
