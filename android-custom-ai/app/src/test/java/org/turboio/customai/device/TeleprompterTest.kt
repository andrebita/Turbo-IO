package org.turboio.customai.device

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.turboio.customai.protocol.FileMessage
import org.turboio.customai.protocol.FileTransfer
import org.turboio.customai.protocol.Hex
import org.turboio.customai.voice.FakePort
import org.turboio.customai.voice.fixedRandom
import org.turboio.customai.device.Teleprompter.Phase

@OptIn(ExperimentalCoroutinesApi::class)
class TeleprompterTest {
    private val u = "0a0b0c0d-0e0f-4011-8213-141516171819"

    /** Vectors produced by harmony-sdk/turbo_core FileTransfer.ts (the reference this codec ports). */
    @Test fun codecMatchesReferenceBytes() {
        val info = FileTransfer.encode(FileMessage(1, u, fileName = u, md5 = "5d41402abc4b2a76b9719d911017c592", fileSize = 5))
        assertEquals("0a720a24" + Hex.encode(u.toByteArray()) + "1224" + Hex.encode(u.toByteArray()) + "1805222035643431343032616263346232613736623937313964393131303137633539322812", Hex.encode(info))
        val chunk = FileTransfer.encode(FileMessage(4, u, chunkId = 0, start = 0, size = 5, data = "hello".toByteArray()))
        assertEquals("22330a24" + Hex.encode(u.toByteArray()) + "1000180020052a0568656c6c6f", Hex.encode(chunk))
        assertEquals("32310a24" + Hex.encode(u.toByteArray()) + "1209757365722d73746f70", Hex.encode(FileTransfer.encode(FileMessage(6, u, reason = "user-stop"))))
        val req = FileTransfer.decode(Hex.decode("1a2e0a24" + Hex.encode(u.toByteArray()) + "100018002080a001"))
        assertEquals(3, req.type); assertEquals(20480L, req.size); assertEquals(0L, req.start)
        val ok = FileTransfer.decode(Hex.decode("2a280a24" + Hex.encode(u.toByteArray()) + "1000"))
        assertEquals(5, ok.type); assertEquals(0L, ok.state)
        // Round trip of the data chunk.
        val back = FileTransfer.decode(chunk); assertEquals("hello", String(back.data)); assertEquals(u, back.uuid)
    }

    @Test fun outboundGateOnlyInfoChunkCancel() {
        assertNull(FileTransfer.outboundProblem(FileTransfer.encode(FileMessage(1, u, fileName = u, md5 = "5d41402abc4b2a76b9719d911017c592", fileSize = 5))))
        assertNull(FileTransfer.outboundProblem(FileTransfer.encode(FileMessage(6, u))))
        assertNotNull(FileTransfer.outboundProblem(FileTransfer.encode(FileMessage(3, u, size = 10))))       // request: glasses only
        assertNotNull(FileTransfer.outboundProblem(FileTransfer.encode(FileMessage(5, u))))                  // result: glasses only
        assertNotNull(FileTransfer.outboundProblem(byteArrayOf(1, 2, 3)))
        assertNotNull(FileTransfer.outboundProblem(ByteArray(30_000)))
        // Other file names (e.g. an OTA package name) cannot be announced.
        assertTrue(runCatching { FileTransfer.encode(FileMessage(1, u, fileName = "update.zip", md5 = "5d41402abc4b2a76b9719d911017c592", fileSize = 5)) }.isFailure)
    }

    @Test fun checksumMatchesIosVector() = assertEquals("4f9f2cab", Teleprompter.checksum("hello".toByteArray()))

    private class Rig(val port: FakePort, val t: Teleprompter) {
        val did get() = JSONObject(port.sent.first { it.first == 20 }.second.jsonText()).getString("did")
        fun business(type: Int, extra: String = "") = port.glasses(20, type, """{"did":"$did","action":2,"code":1$extra}""")
        fun file(m: FileMessage) = port.raw(18, FileTransfer.encode(m))
    }

    private fun TestScope.rig(): Rig { val port = FakePort(); return Rig(port, Teleprompter(port, this, fixedRandom)) }

    @Test fun fullFlowPrepareTransferControlsExit() = runTest {
        val r = rig(); val text = "  Bom dia a todos.\nSegunda linha 😀  "
        assertNull(r.t.prepare(text, 120)); runCurrent()
        val prep = JSONObject(r.port.sent[0].second.jsonText())
        assertEquals(1, prep.getInt("action")); assertEquals(text.toByteArray().size, prep.getInt("total"))
        assertEquals(Teleprompter.checksum(text.toByteArray()), prep.getString("checksum")); assertEquals(2, prep.getInt("scroll"))
        assertTrue(r.port.files.isEmpty())                                      // nothing on 18 before the glasses accept
        r.business(2); runCurrent()
        val info = FileTransfer.decode(r.port.files[0])
        assertEquals(1, info.type); assertEquals(r.did, info.fileName); assertEquals(Teleprompter.md5(text.toByteArray()), info.md5)
        r.file(FileMessage(2, r.did)); r.file(FileMessage(3, r.did, chunkId = 0, start = 0, size = 20480)); runCurrent()
        val chunk = FileTransfer.decode(r.port.files[1])
        assertEquals(text, String(chunk.data, Charsets.UTF_8)); assertEquals(100, r.t.state.value.progress)
        assertNotNull(r.t.control(3))                                           // not ready: file done, business not yet
        r.port.glasses(20, 2, """{"did":"${r.did}","action":2,"code":7}"""); runCurrent()
        assertEquals(Phase.TRANSFERRING, r.t.state.value.phase)                 // file result still missing
        r.file(FileMessage(5, r.did)); runCurrent()
        assertEquals(Phase.READY, r.t.state.value.phase)
        // Controls wait for the matching answer.
        assertNull(r.t.control(3)); runCurrent()
        val start = JSONObject(r.port.sent.last().second.jsonText())
        assertEquals(prep.getString("checksum"), start.getString("checksum")); assertEquals(prep.getInt("total"), start.getInt("total"))
        r.business(4); runCurrent(); assertEquals(Phase.CONTROLLING, r.t.state.value.phase)
        r.business(3); runCurrent(); assertEquals(Phase.RUNNING, r.t.state.value.phase)
        assertNull(r.t.control(7, 200)); r.business(7); runCurrent()
        assertEquals(Phase.RUNNING, r.t.state.value.phase); assertEquals(200, r.t.state.value.speed)
        assertNull(r.t.control(4)); r.business(4); runCurrent(); assertEquals(Phase.PAUSED, r.t.state.value.phase)
        assertNull(r.t.control(5)); r.business(5); runCurrent(); assertEquals(Phase.RUNNING, r.t.state.value.phase)
        // Reading position from the glasses is acknowledged.
        r.port.glasses(20, 8, """{"did":"${r.did}","action":1,"pageOffset":7}"""); runCurrent()
        assertEquals(7, r.t.state.value.offset)
        val ack = JSONObject(r.port.sent.last().second.jsonText())
        assertEquals("20/8", r.port.trace().last()); assertEquals(2, ack.getInt("action")); assertEquals(1, ack.getInt("code"))
        // Exit: file cancel on 18, then 20/6; idle only after the glasses confirm.
        r.t.stop(); runCurrent()
        assertEquals(6, FileTransfer.decode(r.port.files.last()).type); assertEquals("20/6", r.port.trace().last())
        assertTrue(r.t.active)
        r.business(6); runCurrent()
        assertFalse(r.t.active)
    }

    @Test fun refusalPrematureSuccessAndTimeoutNeverUnlockStart() = runTest {
        val refused = rig(); refused.t.prepare("Texto", 120); runCurrent()
        refused.port.glasses(20, 2, """{"did":"${refused.did}","action":2,"code":0}"""); runCurrent()
        assertEquals(Phase.ERROR, refused.t.state.value.phase); assertNotNull(refused.t.control(3))

        val early = rig(); early.t.prepare("Texto", 120); runCurrent()
        early.port.glasses(20, 2, """{"did":"${early.did}","action":2,"code":7}"""); runCurrent()
        assertEquals(Phase.ERROR, early.t.state.value.phase); assertTrue(early.port.files.isEmpty())

        val premature = rig(); premature.t.prepare("Texto", 120); runCurrent(); premature.business(2); runCurrent()
        premature.file(FileMessage(5, premature.did)); runCurrent()
        assertEquals(Phase.ERROR, premature.t.state.value.phase)

        val slow = rig(); slow.t.prepare("Texto", 120); runCurrent()
        advanceTimeBy(31_000); runCurrent()
        assertEquals(Phase.ERROR, slow.t.state.value.phase)
        slow.t.stop(); runCurrent(); assertEquals("20/6", slow.port.trace().last()); assertTrue(slow.port.files.isEmpty())
    }

    @Test fun limitsBusyAndDisconnect() = runTest {
        val port = FakePort(); var busy: String? = "O assistente de voz está em uso"
        val t = Teleprompter(port, this, fixedRandom, busy = { busy })
        assertEquals(busy, t.prepare("Texto", 120))
        busy = null
        assertNotNull(t.prepare("Texto", 300))                         // speed out of range
        assertNotNull(t.prepare("x".repeat(12_001), 120))              // too many characters
        assertNotNull(t.prepare("   ", 120))                           // empty
        assertTrue(port.sent.isEmpty())
        port.isReady = false
        assertNotNull(t.prepare("Texto", 120))
        port.isReady = true
        assertNull(t.prepare("Texto", 120)); runCurrent()
        assertTrue(t.active)
        // Messages for another session id are ignored.
        port.glasses(20, 2, """{"did":"00000000-0000-4000-8000-000000000000","action":2,"code":1}"""); runCurrent()
        assertTrue(port.files.isEmpty())
        t.disconnected(); assertFalse(t.active)
    }
}
