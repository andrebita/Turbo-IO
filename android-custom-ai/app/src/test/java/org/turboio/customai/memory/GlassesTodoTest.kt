package org.turboio.customai.memory

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.turboio.customai.protocol.Business
import org.turboio.customai.journal.JdbcSqlDb
import org.turboio.customai.voice.FakePort
import org.turboio.customai.voice.fixedRandom
import java.time.LocalDateTime
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class GlassesTodoTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val store = MemoryStore(JdbcSqlDb())
    private val port = FakePort()
    private var now = at(11, 0)
    private fun at(h: Int, m: Int, day: Int = 17) = LocalDateTime.of(2026, 10, day, h, m).atZone(zone).toInstant().toEpochMilli()
    private fun body(i: Int) = JSONObject(port.sent[i].second.jsonText())
    private fun add(text: String, due: Long? = null) = store.addItem(Item(kind = ItemKind.TASK, text = text, dueAt = due, createdAt = at(10, 0), updatedAt = at(10, 0)))

    @Test fun mirrorsOpenItemsOnceAndOnlyChanges() = runTest {
        val sync = GlassesTodoSync(port, this, { store }, fixedRandom, { true }, zone, clock = { now })
        val dentist = add("Ligar para o dentista", at(10, 0, 19))
        val bread = add("Comprar pão")
        sync.sync(); advanceUntilIdle()
        assertEquals(listOf("22/2", "22/2"), port.trace())
        val first = body(0)
        assertEquals(1, first.getInt("eventType")); assertEquals(0, first.getInt("status"))
        assertEquals("Ligar para o dentista · 19/10 10:00", first.getString("title")); assertFalse(first.getBoolean("isImportant"))
        assertEquals(at(10, 0) / 1000, first.getLong("createTime")); assertTrue(first.getLong("eventID") in 1_000_000_000_000L..8_999_999_999_999L)
        val wireId = first.getLong("eventID")
        now = at(9, 0, 19); sync.sync(); advanceUntilIdle()
        assertEquals(2, port.sent.size)                                              // next days: same title, nothing resent
        store.setStatus(bread, ItemStatus.DISMISSED, now)
        sync.sync(); advanceUntilIdle()
        assertEquals(1, body(2).getInt("status")); assertEquals("Comprar pão", body(2).getString("title"))   // no delete on the wire: sent as done
        sync.sync(); advanceUntilIdle(); assertEquals(3, port.sent.size)
        // Completed on the glasses → done here, and never reopened by a resend.
        port.glasses(22, 4, """{"eventID":$wireId,"status":1,"isImportant":false,"lastModifiedTime":123}"""); advanceUntilIdle()
        assertEquals(ItemStatus.DONE, store.item(dentist)!!.status); assertEquals("concluída nos óculos", store.item(dentist)!!.doneEvidence)
        sync.sync(); advanceUntilIdle(); assertEquals(3, port.sent.size)
        port.glasses(22, 10, """{"todoTotal":1,"eventList":[{"eventType":1,"eventID":$wireId,"status":0}]}"""); advanceUntilIdle()
        assertEquals(ItemStatus.OPEN, store.item(dentist)!!.status)                  // unchecked on the glasses
        port.glasses(22, 4, """{"eventID":42,"status":1}"""); advanceUntilIdle()     // unknown id ignored
        assertTrue(Business.contentAllowed(22, 2, port.sent[0].second.jsonText()))
    }

    @Test fun disabledOrDisconnectedSendsNothingAndTitlesFit() = runTest {
        var on = false
        val sync = GlassesTodoSync(port, this, { store }, fixedRandom, { on }, zone, clock = { now })
        add("x".repeat(100), at(15, 0))
        sync.sync(); advanceUntilIdle(); assertTrue(port.sent.isEmpty())
        on = true; port.isReady = false; sync.sync(); advanceUntilIdle(); assertTrue(port.sent.isEmpty())
        port.isReady = true; sync.sync(); advanceUntilIdle()
        val title = body(0).getString("title")
        assertEquals(60, title.length); assertTrue(title.endsWith("… · 17/10 15:00")); assertTrue(body(0).getBoolean("isImportant"))
    }
}
