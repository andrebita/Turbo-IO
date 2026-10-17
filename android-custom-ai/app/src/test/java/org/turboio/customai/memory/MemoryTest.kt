package org.turboio.customai.memory

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.turboio.customai.insights.Llm
import org.turboio.customai.journal.Entry
import org.turboio.customai.journal.EntryKind
import org.turboio.customai.journal.EntrySource
import org.turboio.customai.journal.JdbcSqlDb
import org.turboio.customai.journal.JournalStore
import java.time.LocalDateTime
import java.time.ZoneId

class ScriptedLlm(val reply: (system: String, user: String) -> String) : Llm {
    val calls = ArrayList<Pair<String, String>>()
    override suspend fun complete(system: String, user: String, maxTokens: Int): String { calls.add(system to user); return reply(system, user) }
}

class MemoryTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val db = JdbcSqlDb()
    private val journal = JournalStore(db)
    private val store = MemoryStore(db)
    private fun at(h: Int, m: Int, day: Int = 17) = LocalDateTime.of(2026, 10, day, h, m).atZone(zone).toInstant().toEpochMilli()

    private fun ambient(h: Int, m: Int, label: String, text: String, chunk: Int = 0, day: Int = 17): Long {
        val wearer = label == "Você"
        val meta = JSONObject().put("speaker", if (wearer) "wearer" else "other").put("label", label).put("segment", "seg1").put("chunk", chunk)
        return journal.add(Entry(day = "2026-10-%02d".format(day), tsStart = at(h, m, day), tsEnd = at(h, m, day) + 5000, kind = EntryKind.AMBIENT,
            source = EntrySource.LIFELOG, text = "$label: $text", metaJson = meta.toString()))
    }

    private fun engine(llm: Llm, settings: MemorySettings = MemorySettings()) = MemoryEngine(journal, store, llm, { settings }, zone)

    private val firstConversation = """{"categoria":"trabalho","relevancia":0.9,"resumo":"Marina pediu o relatório até sexta.",
        "falantes":[{"rotulo":"Pessoa A·1","nome":"Marina","evidencia":"Você: Oi, Marina","confianca":0.95}],
        "pessoas":[{"nome":"Marina","relacao":"colega de trabalho","fatos":["cuida do orçamento"]}],
        "itens_novos":[{"tipo":"compromisso","texto":"Enviar o relatório para a Marina","responsavel":"eu","pessoa":"Marina",
          "quando":"2026-10-23T15:00","quando_texto":"sexta às 15h","horario":"10:01","citacao":"te mando sexta às 15h","confianca":0.9,"mesmo_que":null},
          {"tipo":"tarefa","texto":"Talvez ver isso","confianca":0.3}]}"""

    @Test fun conversationBecomesPersonNamedSpeakerAndDatedCommitment() = runBlocking {
        ambient(10, 0, "Você", "Oi, Marina, tudo bem?")
        ambient(10, 1, "Pessoa A", "Tudo. Me manda o relatório até sexta?")
        ambient(10, 1, "Você", "Pode deixar, te mando sexta às 15h.")
        val llm = ScriptedLlm { _, user ->
            assertTrue(user.contains("Pessoa A·1: Tudo. Me manda o relatório"))       // anonymous label tagged by chunk
            assertTrue(user.contains("Agora: sábado, 17/10/2026 11:00"))
            firstConversation
        }
        val run = engine(llm).process(listOf("2026-10-17"), at(11, 0))
        assertEquals(1, run.blocks); assertEquals(listOf("Marina"), run.named)
        val item = run.newItems.single()                                            // low-confidence one dropped
        assertEquals("Enviar o relatório para a Marina", item.text)
        assertEquals(at(15, 0, 23), item.dueAt); assertEquals(ItemKind.COMMITMENT, item.kind)
        val marina = store.findPerson("marina")!!
        assertEquals(marina.id, item.personId); assertEquals("colega de trabalho", marina.relation); assertEquals(listOf("cuida do orçamento"), marina.facts)
        assertEquals(1, marina.conversations)
        val texts = journal.day("2026-10-17").map { it.text }
        assertTrue(texts.contains("Marina: Tudo. Me manda o relatório até sexta?"))
        assertEquals("contexto", JSONObject(journal.day("2026-10-17")[1].metaJson).getString("person_method"))
        assertEquals("Marina", journal.search("marina relatorio").single().text.substringBefore(":"))  // index follows the rename
        // Already analysed and unchanged: no new model call.
        engine(llm).process(listOf("2026-10-17"), at(11, 30))
        assertEquals(1, llm.calls.size)
    }

    @Test fun duplicatesMergeCompletionsCloseAndDismissedItemsStayGone() = runBlocking {
        ambient(10, 0, "Pessoa A", "Me manda o relatório?"); ambient(10, 1, "Você", "Te mando sexta.")
        engine(ScriptedLlm { _, _ -> firstConversation }).process(listOf("2026-10-17"), at(11, 0))
        val id = store.openItems().single().id
        ambient(14, 0, "Você", "Marina, lembra de enviar o relatório para a Marina? Ah, e comprar café.")
        val second = """{"categoria":"trabalho","relevancia":0.6,"resumo":"Lembrete.","itens_novos":[
            {"tipo":"tarefa","texto":"Enviar relatório para a Marina","confianca":0.9,"mesmo_que":null,"quando":"2026-10-22T10:00"},
            {"tipo":"tarefa","texto":"Comprar café","confianca":0.8}]}"""
        val run2 = engine(ScriptedLlm { _, u -> assertTrue(u.contains("#$id Enviar o relatório para a Marina (com Marina) — sexta às 15h")); second })
            .process(listOf("2026-10-17"), at(15, 0))
        assertEquals(listOf("Comprar café"), run2.newItems.map { it.text })        // near-duplicate merged, not added
        assertEquals(at(10, 0, 22), store.item(id)!!.dueAt)                         // …and its new deadline kept
        val coffee = run2.newItems.single().id
        store.setStatus(coffee, ItemStatus.DISMISSED, at(15, 1))
        ambient(17, 0, "Você", "Já mandei o relatório. Preciso comprar café.")
        val third = """{"categoria":"trabalho","relevancia":0.5,"resumo":"Relatório enviado.",
            "itens_concluidos":[{"id":$id,"evidencia":"Já mandei o relatório"},{"id":999,"evidencia":"x"}],
            "itens_novos":[{"tipo":"tarefa","texto":"Comprar café","confianca":0.9}]}"""
        val run3 = engine(ScriptedLlm { _, _ -> third }).process(listOf("2026-10-17"), at(18, 0))
        assertEquals(listOf(id), run3.closedItems.map { it.id })
        assertEquals(ItemStatus.DONE, store.item(id)!!.status); assertEquals("Já mandei o relatório", store.item(id)!!.doneEvidence)
        assertTrue(run3.newItems.isEmpty())                                         // dismissed by the user: not recreated
    }

    @Test fun namesNeedEvidenceAndUserCorrectionsWin() = runBlocking {
        val e1 = ambient(10, 0, "Pessoa A", "Bom dia.")
        ambient(10, 1, "Você", "Bom dia.")
        val weak = """{"categoria":"outro","relevancia":0.2,"resumo":"Cumprimento.","falantes":[
            {"rotulo":"Pessoa A·1","nome":"Carlos","evidencia":"","confianca":0.9},
            {"rotulo":"Pessoa A·1","nome":"Pedro","evidencia":"acho que é o Pedro","confianca":0.4}]}"""
        engine(ScriptedLlm { _, _ -> weak }).process(listOf("2026-10-17"), at(11, 0))
        assertTrue(store.people().isEmpty())
        assertTrue(journal.day("2026-10-17")[0].text.startsWith("Pessoa A:"))
        val eng = engine(ScriptedLlm { _, _ -> "{}" })
        val ana = eng.assignSpeaker(journal.byIds(listOf(e1)).single(), "Ana")!!
        assertEquals("Ana: Bom dia.", journal.byIds(listOf(e1)).single().text)
        // A later re-analysis that "finds" another name cannot override the user's choice.
        ambient(10, 2, "Pessoa A", "Tchau, Bia!")
        val strong = """{"categoria":"outro","relevancia":0.2,"resumo":"x","falantes":[{"rotulo":"Pessoa A·1","nome":"Bia","evidencia":"Tchau, Bia","confianca":0.9}]}"""
        engine(ScriptedLlm { _, u -> assertTrue(u.contains("Ana: Bom dia.") && u.contains("Ana: Tchau, Bia!")); strong }).process(listOf("2026-10-17"), at(12, 0))
        assertEquals("Ana: Bom dia.", journal.byIds(listOf(e1)).single().text)
        assertEquals(1, store.findPerson("ana")!!.conversations); assertEquals(ana.id, store.findPerson("Ana")!!.id)
    }

    @Test fun privacyPoliciesAndOpenConversations() = runBlocking {
        ambient(9, 0, "Você", "Filho, vamos tomar café.")
        ambient(10, 58, "Você", "Isso ainda está acontecendo.")                 // ended 2 min before "now": still open
        val settings = MemorySettings(policies = mapOf("familia" to CategoryPolicy.DELETE, "ruido" to CategoryPolicy.IGNORE))
        val llm = ScriptedLlm { _, _ -> """{"categoria":"familia","relevancia":0.4,"resumo":"Café da manhã.","itens_novos":[{"texto":"Comprar pão","confianca":0.9}]}""" }
        val run = engine(llm, settings).process(listOf("2026-10-17"), at(11, 0))
        assertEquals(1, run.blocks); assertTrue(run.newItems.isEmpty()); assertEquals(1, llm.calls.size)
        assertEquals(listOf("Você: Isso ainda está acontecendo."), journal.day("2026-10-17").map { it.text })   // family talk not kept
        assertTrue(store.blocks("2026-10-17").single().summary.contains("removida"))
    }

    @Test fun queriesPendingMorningSearchAndWeekly() = runBlocking {
        ambient(10, 0, "Pessoa A", "Me manda o relatório?"); ambient(10, 1, "Você", "Oi, Marina. Te mando sexta.")
        engine(ScriptedLlm { _, _ -> firstConversation }).process(listOf("2026-10-17"), at(11, 0))
        val llm = ScriptedLlm { system, user ->
            when (system) {
                QueryPrompts.PLAN -> """{"termos":["relatorio","documento"],"de":"2026-10-12","ate":"2026-10-18","pessoa":"Marina"}"""
                QueryPrompts.ANSWER -> { assertTrue(user.contains("[17/10 10:00] Marina: Me manda o relatório?")); "Em 17/10 10:00 a Marina pediu o relatório." }
                QueryPrompts.WEEKLY -> """{"resumo":"Semana de trabalho.","avancos":["relatório combinado"],"sugestoes":["Enviar o relatório"]}"""
                else -> "ok"
            }
        }
        val q = MemoryQueries(journal, store, llm, zone)
        assertEquals("1 pendência(s): Enviar o relatório para a Marina — sex 23/10 15:00", q.pendingAnswer(at(11, 0)))
        assertEquals("Nada pendente com Bruno.".let { "Não conheço ninguém chamado Bruno no diário." }, q.pendingAnswer(at(11, 0), "Bruno"))
        assertTrue(q.pendingAnswer(at(11, 0), "marina").contains("relatório"))
        assertTrue(q.morningBrief(at(8, 0, 23))!!.contains("Hoje: Enviar o relatório para a Marina — hoje 15:00"))
        assertTrue(q.morningBrief(at(8, 0, 24))!!.contains("Atrasadas:"))
        assertEquals("Em 17/10 10:00 a Marina pediu o relatório.", q.search("o que a Marina pediu semana passada?", at(11, 0)))
        assertTrue(llm.calls.first().second.contains("Hoje: 2026-10-17"))
        val card = q.personCard(store.findPerson("Marina")!!, at(11, 0))
        assertTrue(card.contains("Pendências:") && card.contains("Marina pediu o relatório até sexta."))
        val week = q.weekly(at(19, 0, 18)) { null }!!
        assertTrue(week.text.startsWith("Semana de trabalho.") && week.text.contains("Sugestões\n- Enviar o relatório"))
        assertNull(q.weekly(at(19, 0, 25)) { null })                                   // empty week: nothing to review
        assertNotNull(store.findPerson("MARINA")); assertFalse(store.openItems().isEmpty())
    }
}
