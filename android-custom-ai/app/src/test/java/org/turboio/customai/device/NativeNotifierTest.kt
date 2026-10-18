package org.turboio.customai.device

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.turboio.customai.protocol.Business
import org.turboio.customai.voice.FakePort
import org.turboio.customai.voice.fixedRandom
import java.time.LocalDateTime
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class NativeNotifierTest {
    private val port = FakePort()
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val now = LocalDateTime.of(2026, 10, 17, 15, 4, 5, 6_000_000).atZone(zone).toInstant().toEpochMilli()

    @Test fun switchOnceThenCardAndStateAnswer() = runTest {
        val n = NativeNotifier(port, this, fixedRandom, zone) { now }
        val first = async { n.show("Lembrete", "Ligar para o dentista — 10:00") }
        runCurrent()
        assertEquals(listOf("21/18", "21/2"), port.trace())
        assertEquals("""{"notification":true}""", port.sent[0].second.jsonText())
        val card = JSONObject(port.sent[1].second.jsonText())
        assertEquals("Lembrete", card.getString("title")); assertEquals("Turbo IO", card.getString("appName"))
        assertEquals("2026-10-17T15:04:05.006", card.getString("timestamp")); assertEquals(1, card.getInt("type")); assertFalse(card.getBoolean("reply"))
        port.glasses(21, 3, """{"notificationUID":"${card.getString("notificationUID")}","state":1}"""); runCurrent()
        assertEquals(NativeNotifier.Outcome.SHOWN, first.await())
        // Second card: no switch again; another app's ANCS text (type 1) is ignored; no answer → NO_REPLY.
        val second = async { n.show("WhatsApp", "Marina: chego em 10 min", "WhatsApp") }
        runCurrent()
        assertEquals(listOf("21/18", "21/2", "21/2"), port.trace())
        port.glasses(21, 1, """{"notificationUID":"x","title":"segredo"}"""); advanceTimeBy(6000); runCurrent()
        assertEquals(NativeNotifier.Outcome.NO_REPLY, second.await())
        // Reconnection → the switch is sent again; not worn is reported as such.
        n.connectionChanged()
        val third = async { n.show("A", "b") }; runCurrent()
        assertEquals("21/18", port.trace()[3])
        port.glasses(21, 3, """{"notificationUID":"${JSONObject(port.sent[4].second.jsonText()).getString("notificationUID")}","state":2}"""); runCurrent()
        assertEquals(NativeNotifier.Outcome.NOT_WORN, third.await())
    }

    @Test fun gateRejectsMalformedCards() {
        assertFalse(Business.contentAllowed(21, 2, """{"notificationUID":"0","appId":"a","title":"t","content":"c","type":1,"reply":false}"""))
        assertFalse(Business.contentAllowed(21, 2, """{"notificationUID":"5","appId":"a","title":"","content":"c","type":1,"reply":false}"""))
        assertFalse(Business.contentAllowed(21, 18, """{"notification":true,"x":1}"""))
        assertFalse(Business.contentAllowed(21, 17, """{"notification":true}"""))
        assertTrue(Business.contentAllowed(15, 16, Business.WAKEUP_ON))
        assertFalse(Business.contentAllowed(15, 16, """{"cmd":"set_ai_voice_wakeup","payload":{"value":0,"mode":2,"data":""}}"""))
        assertFalse(Business.outboundAllowed(21, 17)); assertFalse(Business.outboundAllowed(21, 1))
    }
}
