package org.turboio.customai.device

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.turboio.customai.protocol.Business
import org.turboio.customai.protocol.EnvelopeCodec
import org.turboio.customai.voice.FakePort
import org.turboio.customai.voice.fixedRandom
import java.time.LocalDateTime
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardTest {
    private val port = FakePort()
    private var answered = 0
    /** The glasses' dashboard as the official add-on tests model it (simple_mode as a number). */
    private var config = JSONObject("""{"widgets":[3,1],"simple_mode":0,"widgets_v2":[{"id":"todo","type":"native"},{"id":"weather","type":"native"}]}""")
    var refuse = false
    var silent = false

    private fun serve() {
        while (answered < port.sent.size) {
            val (b, e) = port.sent[answered++]
            if (b != Business.LAUNCHER || e.type != 18 || silent) continue
            val j = JSONObject(e.jsonText()); val p = j.getJSONObject("payload")
            val reply = when (j.getString("cmd")) {
                "dashboard_config" -> JSONObject().put("cmd", "dashboard_config").put("payload", JSONObject().put("value", 0).put("data", config.toString()))
                "widget_install" -> {
                    if (!refuse) {
                        val d = p.getJSONObject("data"); val rows = config.getJSONArray("widgets_v2"); val kept = JSONArray()
                        for (i in 0 until rows.length()) if (rows.getJSONObject(i).getString("id") != d.getString("id")) kept.put(rows.getJSONObject(i))
                        kept.put(JSONObject().put("id", d.getString("id")).put("type", "a2ui").put("name", d.getString("name")).put("extras", d.getString("extras")))
                        config.put("widgets_v2", kept)
                    }
                    JSONObject().put("code", if (refuse) 1 else 0)
                }
                "widget_uninstall" -> {
                    val rows = config.getJSONArray("widgets_v2"); val kept = JSONArray()
                    for (i in 0 until rows.length()) if (rows.getJSONObject(i).getString("id") != DashboardWire.CARD_ID) kept.put(rows.getJSONObject(i))
                    config.put("widgets_v2", kept); JSONObject().put("code", 0)
                }
                "dashboard_update" -> { config = JSONObject(p.getString("data")); JSONObject().put("cmd", "dashboard_update").put("payload", JSONObject().put("value", 0)) }
                else -> continue
            }
            port.raw(Business.LAUNCHER, EnvelopeCodec.encode(19, reply.toString(), e.sequence, 2))
        }
    }

    private fun TestScope.drive() = repeat(12) { runCurrent(); serve() }

    @Test fun requestsCarrySequenceAndMode() = runTest {
        val d = Dashboard(port, fixedRandom)
        val read = async { d.read() }; drive()
        val (snap, _) = read.await()
        assertEquals(listOf("todo", "weather"), snap!!.widgets.map { it.id }); assertEquals(false, snap.simple)
        val bytes = EnvelopeCodec.encode(18, DashboardWire.QUERY, port.sent[0].second.sequence, 1)
        assertTrue(port.sent[0].second.sequence >= 0x40000000L)
        assertEquals(0x30, bytes[bytes.size - 2].toInt()); assertEquals(1, bytes.last().toInt())   // field 6 = 1
    }

    @Test fun cardInstallVerifiedThenRemoved() = runTest {
        val d = Dashboard(port, fixedRandom)
        val show = async { d.showCard("3 pend · 15:00 Ligar") }; drive()
        assertTrue(show.await(), show.await().startsWith("Cartão no painel"))
        assertEquals(listOf("15/18", "15/18", "15/18"), port.trace())
        assertEquals(listOf("todo", "weather", DashboardWire.CARD_ID), DashboardWire.widgets(config)!!.map { it.id })
        val extras = JSONObject(DashboardWire.widgets(config)!!.let { config.getJSONArray("widgets_v2").getJSONObject(2).getString("extras") })
        assertEquals("3 pend · 15:00 Ligar", extras.getJSONObject("uiContent").getJSONObject("updateComponents").getJSONArray("components").getJSONObject(0).getString("text"))
        val again = async { d.showCard("2 pendências") }; drive()                                   // own card replaced
        assertTrue(again.await().startsWith("Cartão no painel"))
        val remove = async { d.removeCard() }; drive()
        assertTrue(remove.await(), remove.await().startsWith("Cartão removido"))
        assertEquals(listOf("todo", "weather"), DashboardWire.widgets(config)!!.map { it.id })
        assertTrue(runCatching { DashboardWire.install("x".repeat(21)) }.isFailure)
    }

    @Test fun refusalAndForeignCardStopWithoutRetry() = runTest {
        val d = Dashboard(port, fixedRandom)
        refuse = true
        val r = async { d.showCard("Oi") }; drive()
        assertTrue(r.await().contains("recusaram")); assertEquals(2, port.sent.size)
        refuse = false
        config.getJSONArray("widgets_v2").put(JSONObject().put("id", DashboardWire.CARD_ID).put("type", "native"))
        val f = async { d.showCard("Oi") }; drive()
        assertTrue(f.await().contains("não é deste app")); assertEquals(3, port.sent.size)
    }

    @Test fun simpleModeFlippedKeepingTypeAndVerified() = runTest {
        val d = Dashboard(port, fixedRandom)
        var remembered: Boolean? = null
        val on = async { d.setSimpleMode(true) { remembered = it } }; drive()
        assertTrue(on.await(), on.await().startsWith("Painel agora no tipo simples"))
        assertEquals(1, config.get("simple_mode"))                                                 // number stays a number
        assertEquals(false, remembered)
        assertEquals(listOf(3, 1), (0 until 2).map { config.getJSONArray("widgets").getInt(it) })
        val same = async { d.setSimpleMode(true) }; drive()
        assertTrue(same.await().startsWith("O painel já"))
        // Boolean form and a config without simple_mode.
        config = JSONObject("""{"simple_mode":true,"widgets_v2":[{"id":"todo","type":"native"}]}""")
        val off = async { d.setSimpleMode(false) }; drive()
        assertTrue(off.await().startsWith("Painel agora no tipo completo")); assertEquals(false, config.get("simple_mode"))
        config = JSONObject("""{"widgets_v2":[{"id":"todo","type":"native"}]}""")
        val unknown = async { d.setSimpleMode(true) }; drive()
        assertTrue(unknown.await().contains("não informam"))
    }

    @Test fun noReplyTimesOut() = runTest {
        silent = true
        val d = Dashboard(port, fixedRandom, timeoutMs = 1000)
        val r = async { d.read() }; drive(); advanceTimeBy(1500); runCurrent()
        assertNull(r.await().first)
    }

    @Test fun preservesIgnoresKeyOrderOnly() {
        val a = JSONObject("""{"simple_mode":0,"widgets_v2":[{"id":"a","type":"native","x":{"p":1,"q":2}}]}""")
        val b = JSONObject("""{"widgets_v2":[{"x":{"q":2,"p":1},"type":"native","id":"a"}],"simple_mode":0}""")
        assertTrue(DashboardWire.preserves(a, b))
        assertFalse(DashboardWire.preserves(a, JSONObject("""{"simple_mode":1,"widgets_v2":[{"id":"a","type":"native","x":{"p":1,"q":2}}]}""")))
        assertTrue(DashboardWire.preserves(a, JSONObject("""{"simple_mode":1,"widgets_v2":[{"id":"a","type":"native","x":{"p":1,"q":2}}]}"""), ignoring = setOf("simple_mode")))
    }

    @Test fun summaryFitsTwentyCharacters() {
        val zone = ZoneId.of("America/Sao_Paulo")
        val now = LocalDateTime.of(2026, 10, 17, 9, 0).atZone(zone).toInstant().toEpochMilli()
        val at15 = LocalDateTime.of(2026, 10, 17, 15, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals("Sem pendências", DashboardWire.summary(0, null, "", now, zone))
        assertEquals("1 pendência", DashboardWire.summary(1, null, "", now, zone))
        assertEquals("3 pend · 15:00 Ligar", DashboardWire.summary(3, at15, "Ligar", now, zone))
        val long = DashboardWire.summary(12, at15, "Ligar para a Marina sobre o contrato", now, zone)
        assertTrue(long, long.length <= 20 && long.startsWith("12 pend · 15:00 ") && long.endsWith("…"))
        assertEquals("4 pendências", DashboardWire.summary(4, at15 + 86_400_000L, "Amanhã", now, zone))
        DashboardWire.install(long)                                                                 // always installable
    }
}
