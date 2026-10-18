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
import org.turboio.customai.protocol.OutboundFile
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
        assertNotNull(FileTransfer.outboundProblem(ByteArray(60_000)))
        // Other file names (e.g. an OTA package name) cannot be announced.
        assertTrue(runCatching { FileTransfer.encode(FileMessage(1, u, fileName = "update.zip", md5 = "5d41402abc4b2a76b9719d911017c592", fileSize = 5)) }.isFailure)
        assertTrue(runCatching { FileTransfer.encode(FileMessage(1, u, fileName = "../roteiro.txt", md5 = "5d41402abc4b2a76b9719d911017c592", fileSize = 5)) }.isFailure)
        // Business 17 (official app's upload): the info must name the business it travels on; nothing else is a file business.
        val small = FileTransfer.encode(FileMessage(1, "task_1_1792276115000", fileName = "roteiro.txt", md5 = "5d41402abc4b2a76b9719d911017c592", fileSize = 5, busId = 17))
        assertNull(FileTransfer.outboundProblem(small, 17))
        assertNotNull(FileTransfer.outboundProblem(small, 18))
        assertNotNull(FileTransfer.outboundProblem(FileTransfer.encode(FileMessage(6, u)), 9))
        assertTrue(runCatching { FileTransfer.encode(FileMessage(1, u, fileName = u, md5 = "5d41402abc4b2a76b9719d911017c592", fileSize = 5, busId = 9)) }.isFailure)
    }

    /** The official app 1.0.7's receiver asks for 51 200 bytes with chunk id 0 every time; the answer is clipped to the file. */
    @Test fun largeRequestsAndRepeatedChunkIdAreServed() {
        val bytes = ByteArray(30_000) { (it % 97).toByte() }
        val f = OutboundFile("task_1_1", bytes, Teleprompter.md5(bytes), "roteiro.txt", 17)
        assertEquals(17L, FileTransfer.decode(f.info()).busId); assertEquals("roteiro.txt", FileTransfer.decode(f.info()).fileName)
        val req = FileTransfer.decode(FileTransfer.encode(FileMessage(3, "task_1_1", chunkId = 0, start = 0, size = 51_200)))
        f.receive(FileMessage(2, "task_1_1"))
        val first = f.receive(FileMessage(3, "task_1_1", chunkId = 0, start = 0, size = 20_000))!!
        assertEquals(20_000L, first.size); f.committed(first)
        val rest = f.receive(FileMessage(3, "task_1_1", chunkId = 0, start = 20_000, size = req.size))!!   // same id, other range
        assertEquals(10_000L, rest.size); assertNull(FileTransfer.outboundProblem(FileTransfer.encode(rest), 17)); f.committed(rest)
        f.receive(FileMessage(5, "task_1_1")); assertTrue(f.complete)
    }

    @Test fun checksumMatchesIosVector() = assertEquals("4f9f2cab", Teleprompter.checksum("hello".toByteArray()))

    private class Rig(val port: FakePort, val t: Teleprompter) {
        val did get() = JSONObject(port.sent.first { it.first == 20 }.second.jsonText()).getString("did")
        fun business(type: Int, extra: String = "") = port.glasses(20, type, """{"did":"$did","action":2,"code":1$extra}""")
        fun file(m: FileMessage, business: Int = 17) = port.raw(business, FileTransfer.encode(m))
        /** Transfer id of the n-th announcement. */
        fun id(n: Int = 0) = FileTransfer.decode(port.files.filterIndexed { i, _ -> FileTransfer.decode(port.files[i]).type == 1 }[n]).uuid
    }

    private fun TestScope.rig(): Rig { val port = FakePort(); return Rig(port, Teleprompter(port, this, fixedRandom)) }

    @Test fun fullFlowPrepareTransferControlsExit() = runTest {
        val r = rig(); val text = "  Bom dia a todos.\nSegunda linha 😀  "
        assertNull(r.t.prepare(text, 120)); runCurrent()
        val prep = JSONObject(r.port.sent[0].second.jsonText())
        assertEquals(1, prep.getInt("action")); assertEquals(text.toByteArray().size, prep.getInt("total"))
        // Official AppStartRequest: presentation settings, no checksum (that goes with 20/3).
        assertFalse(prep.has("checksum")); assertEquals(2, prep.getInt("scroll"))
        // The official app's defaults for the iO.
        assertEquals(3, prep.getInt("countdown")); assertEquals(1, prep.getInt("gear")); assertEquals(1, prep.getInt("depth")); assertEquals(18, prep.getInt("size"))
        assertEquals(492, prep.getInt("width")); assertEquals(4, prep.getInt("leading"))
        assertTrue(r.port.files.isEmpty())                                      // no file before the glasses accept
        r.business(2); runCurrent()
        val info = FileTransfer.decode(r.port.files[0])
        // First form: as the official app uploads (business 17, busId 17, "task_" id, plain .txt name).
        assertEquals(1, info.type); assertEquals(17, r.port.fileBusinesses[0]); assertEquals(17L, info.busId)
        assertTrue(info.uuid.startsWith("task_")); assertEquals("${r.did}.txt", info.fileName); assertEquals(Teleprompter.md5(text.toByteArray()), info.md5)
        r.file(FileMessage(2, info.uuid)); r.file(FileMessage(3, info.uuid, chunkId = 0, start = 0, size = 51_200)); runCurrent()
        val chunk = FileTransfer.decode(r.port.files[1]); assertEquals(17, r.port.fileBusinesses[1])
        assertEquals(text, String(chunk.data, Charsets.UTF_8)); assertEquals(100, r.t.state.value.progress)
        assertNotNull(r.t.control(3))                                           // not ready: file done, business not yet
        r.port.glasses(20, 2, """{"did":"${r.did}","action":2,"code":7}"""); runCurrent()
        assertEquals(Phase.TRANSFERRING, r.t.state.value.phase)                 // file result still missing
        r.file(FileMessage(5, info.uuid)); runCurrent()
        assertEquals(Phase.READY, r.t.state.value.phase)
        advanceTimeBy(20_000); runCurrent()
        assertEquals(1, r.port.files.count { FileTransfer.decode(it).type == 1 })   // answered: no other form announced
        // Controls wait for the matching answer.
        assertNull(r.t.control(3)); runCurrent()
        val start = JSONObject(r.port.sent.last().second.jsonText())
        assertEquals(Teleprompter.checksum(text.toByteArray()), start.getString("checksum")); assertEquals(prep.getInt("total"), start.getInt("total"))
        assertEquals(18, start.getInt("size"))
        r.business(4); runCurrent(); assertEquals(Phase.CONTROLLING, r.t.state.value.phase)
        r.business(3); runCurrent(); assertEquals(Phase.RUNNING, r.t.state.value.phase)
        assertNull(r.t.control(7, 200))
        val settings = JSONObject(r.port.sent.last().second.jsonText())
        assertEquals(200, settings.getInt("speed")); assertEquals(1, settings.getInt("depth")); assertEquals(492, settings.getInt("width"))   // 20/7 carries every setting
        r.business(7); runCurrent()
        assertEquals(Phase.RUNNING, r.t.state.value.phase); assertEquals(200, r.t.state.value.speed)
        assertNull(r.t.control(4)); r.business(4); runCurrent(); assertEquals(Phase.PAUSED, r.t.state.value.phase)
        assertNull(r.t.control(5)); r.business(5); runCurrent(); assertEquals(Phase.RUNNING, r.t.state.value.phase)
        // Reading position from the glasses is acknowledged.
        r.port.glasses(20, 8, """{"did":"${r.did}","action":1,"pageOffset":7}"""); runCurrent()
        assertEquals(7, r.t.state.value.offset)
        val ack = JSONObject(r.port.sent.last().second.jsonText())
        assertEquals("20/8", r.port.trace().last()); assertEquals(2, ack.getInt("action")); assertEquals(1, ack.getInt("code"))
        // Exit: file cancel on the business that answered, then 20/6; idle only after the glasses confirm.
        r.t.stop(); runCurrent()
        assertEquals(6, FileTransfer.decode(r.port.files.last()).type); assertEquals(17, r.port.fileBusinesses.last()); assertEquals("20/6", r.port.trace().last())
        assertTrue(r.t.active)
        r.business(6); runCurrent()
        assertFalse(r.t.active)
    }

    /** No answer: the other forms are tried in order; the first form that answers is used, late answers of others ignored. */
    @Test fun unansweredInfoTriesNextRoutes() = runTest {
        val r = rig(); r.t.prepare("Texto curto", 120); runCurrent(); r.business(2); runCurrent()
        assertEquals(listOf(17), r.port.fileBusinesses)
        advanceTimeBy(6_100); runCurrent()
        val second = FileTransfer.decode(r.port.files[1])
        assertEquals(17, r.port.fileBusinesses[1]); assertEquals(r.did, second.uuid); assertEquals("${r.did}.txt", second.fileName)
        advanceTimeBy(6_100); runCurrent()
        val third = FileTransfer.decode(r.port.files[2])
        assertEquals(18, r.port.fileBusinesses[2]); assertEquals(18L, third.busId); assertTrue(third.uuid.startsWith("task_"))
        advanceTimeBy(20_000); runCurrent()
        assertEquals(3, r.port.files.size)                                    // three forms, then only the 2-minute deadline
        // The glasses answer the 18 form; the 17 forms are dropped (an answer on the wrong business is ignored).
        r.file(FileMessage(2, third.uuid), 17); r.file(FileMessage(3, third.uuid, chunkId = 0, start = 0, size = 100), 17); runCurrent()
        assertEquals(3, r.port.files.size)
        r.file(FileMessage(2, third.uuid), 18); r.file(FileMessage(3, third.uuid, chunkId = 0, start = 0, size = 100), 18); runCurrent()
        assertEquals("Texto curto", String(FileTransfer.decode(r.port.files[3]).data)); assertEquals(18, r.port.fileBusinesses[3])
        r.file(FileMessage(3, second.uuid, chunkId = 0, start = 0, size = 100), 17); runCurrent()
        assertEquals(4, r.port.files.size)
        // Exit cancels only the form in use.
        r.t.stop(); runCurrent()
        assertEquals(5, r.port.files.size); assertEquals(third.uuid, FileTransfer.decode(r.port.files[4]).uuid)
    }

    /** Opened from the glasses' menu: they ask for the script list (20/1 FullSync); the answer is the official empty list. */
    @Test fun menuListRequestIsAnsweredWithEmptyList() = runTest {
        val r = rig()
        r.port.glasses(20, 1, """{"action":1,"type":1,"code":1,"chunkid":0}"""); runCurrent()
        assertEquals("20/1", r.port.trace().single())
        val list = JSONObject(r.port.sent.single().second.jsonText())
        assertEquals(2, list.getInt("action")); assertEquals(0, list.getInt("total")); assertEquals(0, list.getJSONArray("items").length())
        assertTrue(list.getJSONObject("chunkinfo").getBoolean("islast")); assertEquals(1, list.getJSONObject("chunkinfo").getInt("chunkid"))
        r.port.glasses(20, 1, """{"action":1,"type":2,"chunkId":1}"""); runCurrent()       // other list requests: not answered
        assertEquals(1, r.port.sent.size)
        assertFalse(org.turboio.customai.protocol.Business.contentAllowed(20, 1, """{"action":2,"type":1,"total":1,"items":[{"title":"x"}]}"""))
        assertNotNull(r.t.prepare("Texto", 120, Teleprompter.Look(gear = 5)))
    }

    @Test fun refusalCodesAreExplained() {
        assertTrue(Teleprompter.refusal(18).contains("dobrados")); assertTrue(Teleprompter.refusal(9).contains("outro app"))
    }

    @Test fun glassesExitWithCodeEndsSession() = runTest {
        val r = rig(); r.t.prepare("Texto", 120); runCurrent(); r.business(2); runCurrent()
        r.port.glasses(20, 6, """{"did":"${r.did}","action":2,"code":3}"""); runCurrent()
        assertFalse(r.t.active); assertTrue(r.t.state.value.note.contains("código 3"))
    }

    @Test fun refusalPrematureSuccessAndTimeoutNeverUnlockStart() = runTest {
        val refused = rig(); refused.t.prepare("Texto", 120); runCurrent()
        refused.port.glasses(20, 2, """{"did":"${refused.did}","action":2,"code":0}"""); runCurrent()
        assertEquals(Phase.ERROR, refused.t.state.value.phase); assertNotNull(refused.t.control(3))

        val early = rig(); early.t.prepare("Texto", 120); runCurrent()
        early.port.glasses(20, 2, """{"did":"${early.did}","action":2,"code":7}"""); runCurrent()
        assertEquals(Phase.ERROR, early.t.state.value.phase); assertTrue(early.port.files.isEmpty())

        val premature = rig(); premature.t.prepare("Texto", 120); runCurrent(); premature.business(2); runCurrent()
        premature.file(FileMessage(5, premature.id())); runCurrent()
        assertEquals(Phase.ERROR, premature.t.state.value.phase)

        val slow = rig(); slow.t.prepare("Texto", 120); runCurrent()
        advanceTimeBy(31_000); runCurrent()
        assertEquals(Phase.ERROR, slow.t.state.value.phase)
        slow.t.stop(); runCurrent(); assertEquals("20/6", slow.port.trace().last()); assertTrue(slow.port.files.isEmpty())
    }

    /** Strix OS 1.0.5.5: the file is confirmed (5 success) but no 20/2 code 7 follows; the script is ready after a short wait. */
    @Test fun readyWithoutCode7AfterFileSuccess() = runTest {
        val r = rig(); r.t.prepare("Texto curto", 120); runCurrent(); r.business(2); runCurrent()
        val id = r.id()
        r.file(FileMessage(2, id)); r.file(FileMessage(3, id, chunkId = 1, start = 0, size = 163)); r.file(FileMessage(5, id)); runCurrent()
        assertEquals(Phase.TRANSFERRING, r.t.state.value.phase)
        advanceTimeBy(1_600); runCurrent()
        assertEquals(Phase.READY, r.t.state.value.phase)
    }

    /** Scripts sent are kept; the glasses' menu lists them and can open one (glasses 20/2 → phone 20/2 + file → glasses 20/3). */
    @Test fun libraryListAndGlassesStartedScript() = runTest {
        val dir = java.nio.file.Files.createTempDirectory("scripts").toFile()
        val library = ScriptLibrary(dir, clock = { 1_792_281_600_000L })
        val port = FakePort(); val t = Teleprompter(port, this, fixedRandom, library = library)
        assertNull(t.prepare("Abertura do evento\nSegunda linha", 120)); runCurrent()
        val did = JSONObject(port.sent.first { it.first == 20 }.second.jsonText()).getString("did")
        t.disconnected(); port.sent.clear()
        assertEquals(1, library.list().size); assertEquals("Abertura do evento", library.list()[0].title)
        // Menu: list with the saved script, accepted by the outbound gate.
        port.glasses(20, 1, """{"action":1,"type":1,"code":1,"chunkid":0}"""); runCurrent()
        val list = JSONObject(port.sent.single().second.jsonText())
        assertEquals(1, list.getInt("total")); val item = list.getJSONArray("items").getJSONObject(0)
        assertEquals(did, item.getString("did")); assertEquals(1_792_281_600L, item.getLong("timestamp")); assertEquals(0, item.getInt("offset"))
        // Picked on the glasses.
        port.glasses(20, 2, """{"action":1,"did":"$did","total":31,"countdown":3,"scroll":1,"speed":120,"gear":1,"depth":1,"size":18,"width":492,"leading":4}"""); runCurrent()
        assertTrue(t.active); assertEquals(Phase.TRANSFERRING, t.state.value.phase)
        val answer = JSONObject(port.sent[1].second.jsonText())
        assertEquals(2, answer.getInt("action")); assertEquals(1, answer.getInt("code")); assertEquals(0, answer.getInt("highLightOffset"))
        val info = FileTransfer.decode(port.files[0]); assertEquals("$did.txt", info.fileName); assertEquals(17, port.fileBusinesses[0])
        port.raw(17, FileTransfer.encode(FileMessage(2, info.uuid))); port.raw(17, FileTransfer.encode(FileMessage(3, info.uuid, chunkId = 1, start = 0, size = 100)))
        port.raw(17, FileTransfer.encode(FileMessage(5, info.uuid))); runCurrent(); advanceTimeBy(1_600); runCurrent()
        assertEquals(Phase.READY, t.state.value.phase)
        port.glasses(20, 3, """{"action":1,"did":"$did","total":31}"""); runCurrent()
        assertEquals(Phase.RUNNING, t.state.value.phase)
        assertEquals(2, JSONObject(port.sent.last().second.jsonText()).getInt("action")); assertEquals("20/3", port.trace().last())
        // A script that is not on the phone is refused (code 4) without starting a session.
        t.disconnected(); port.sent.clear()
        port.glasses(20, 2, """{"action":1,"did":"00000000-0000-4000-8000-000000000000","total":3}"""); runCurrent()
        assertFalse(t.active); assertEquals(4, JSONObject(port.sent.single().second.jsonText()).getInt("code"))
        dir.deleteRecursively()
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
