package org.turboio.customai.device

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.turboio.customai.voice.FakePort
import org.turboio.customai.voice.fixedRandom
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class SuggestionCardsTest {
    @Test fun nodAcceptsShakeRejectsSilenceRemoves() = runTest {
        val port = FakePort()
        val notifier = NativeNotifier(port, this, fixedRandom, ZoneId.of("UTC"))
        val cards = SuggestionCards(port, fixedRandom)
        notifier.suggestionListener = cards::onAnswer
        val first = async { cards.ask("Anotar pendência?", "Ligar para a Marina · 17/10 15:00", "Memória") }
        runCurrent()
        val add = JSONObject(port.sent[0].second.jsonText())
        assertEquals("21/33", port.trace()[0]); assertEquals(1, add.getInt("type")); assertEquals(2, add.getInt("suggestType"))
        assertEquals("Memória", add.getString("source"))
        val uid = add.getString("suggestUID")
        port.glasses(21, 34, """{"suggestUID":"other-card-123","suggestType":2,"cmd":1}"""); runCurrent()   // not ours
        port.glasses(21, 34, """{"suggestUID":"$uid","suggestType":2,"cmd":1}"""); runCurrent()
        assertEquals(SuggestionCards.Answer.YES, first.await())
        assertEquals(listOf("21/33", "21/33", "21/35"), port.trace())
        assertEquals(2, JSONObject(port.sent[1].second.jsonText()).getInt("type"))
        assertEquals(0, JSONObject(port.sent[2].second.jsonText()).getInt("code"))
        // A duplicate answer after the fact changes nothing.
        port.glasses(21, 34, """{"suggestUID":"$uid","suggestType":2,"cmd":2}"""); runCurrent()
        assertEquals(3, port.sent.size)

        val second = async { cards.ask("Anotar?", "Comprar pão") }; runCurrent()
        val uid2 = JSONObject(port.sent[3].second.jsonText()).getString("suggestUID")
        port.glasses(21, 34, """{"suggestUID":"$uid2","suggestType":2,"cmd":2}"""); runCurrent()
        assertEquals(SuggestionCards.Answer.NO, second.await())

        val third = async { cards.ask("Anotar?", "Sem resposta", timeoutMs = 1000) }; runCurrent()
        advanceTimeBy(1500); runCurrent()
        assertEquals(SuggestionCards.Answer.TIMEOUT, third.await())
        assertEquals("21/33", port.trace().last())                                                  // removed, no ack
        assertEquals(2, JSONObject(port.sent.last().second.jsonText()).getInt("type"))
    }
}
