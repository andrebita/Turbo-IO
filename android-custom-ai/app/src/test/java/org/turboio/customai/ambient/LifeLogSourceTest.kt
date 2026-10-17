package org.turboio.customai.ambient

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.turboio.customai.probe.MemoryFlag
import org.turboio.customai.probe.lifeLogFrames
import org.turboio.customai.voice.FakePort
import org.turboio.customai.voice.fixedRandom

@OptIn(ExperimentalCoroutinesApi::class)
class LifeLogSourceTest {
    @get:Rule val tmp = TemporaryFolder()
    private val port = FakePort()
    private var now = 1_000_000L

    private class Collect : FrameSink {
        val batches = ArrayList<OpusBatch>(); val gaps = ArrayList<String>()
        override fun frames(batch: OpusBatch) { batches.add(batch) }
        override fun gap(reason: String) { gaps.add(reason) }
    }

    private fun TestScope.source(flag: MemoryFlag, fromGlasses: Boolean = false, keepGuide: Boolean = false, ended: (String) -> Unit = {}) =
        LifeLogSource(port, this, flag, fixedRandom, realtime = true, startedOnGlasses = fromGlasses, keepGuide = { keepGuide },
            onEnded = ended, clock = { now }).also { port.addBusinessListener(it) }

    private fun cmd(i: Int) = JSONObject(port.sent[i].second.jsonText())

    /** Realtime delivery as measured on hardware: one 60 ms frame per envelope, mode 0, masks in bit 0. */
    private fun TestScope.realtime(frames: List<ByteArray>, wearer: (Int) -> Boolean) {
        frames.forEachIndexed { i, f ->
            now += 60
            val bit = if (wearer(i)) 1 else 0
            port.glasses(13, 163, """{"taskId":"t","mode":0,"frameCount":1,"vpuMask":$bit,"vadMask":1}""", f); runCurrent()
        }
    }

    @Test fun appStartedRealtimeCaptureBatchesFramesWithWearerFlagsAndSwitchesOff() = runTest {
        val flag = MemoryFlag(); val sink = Collect()
        val src = source(flag)
        src.start(sink); runCurrent()
        assertTrue(flag.value)
        assertEquals(listOf("15/20", "15/20"), port.trace())
        assertEquals("life_log_switch", cmd(1).getString("cmd")); assertEquals(1, cmd(1).getJSONObject("payload").getInt("value"))
        port.glasses(15, 21, """{"cmd":"life_log_switch_result","payload":{"value":0}}"""); runCurrent()
        port.glasses(13, 161); runCurrent()
        assertEquals(listOf("13/162", "13/168"), port.trace().drop(2))
        assertTrue(cmd(3).getBoolean("inRealtimePage"))
        val start = now
        realtime(lifeLogFrames(40)) { it < 20 }
        // 17 frames × 60 ms ≥ 1 s per batch; the rest is flushed on stop.
        assertEquals(listOf(17, 17), sink.batches.map { it.frames.size })
        assertTrue(sink.batches.all { it.frameMs == 60 && it.channels == 1 && !it.cached })
        assertEquals(start + 17 * 60, sink.batches[0].receivedAtWall)
        assertEquals(sink.batches[0].receivedAtWall + 17 * 60, sink.batches[1].receivedAtWall)   // contiguous capture time
        assertTrue(sink.batches[0].wearerVoice!!.all { it }); assertEquals(3, sink.batches[1].wearerVoice!!.count { it })
        src.stop(); runCurrent()
        assertEquals(6, sink.batches.single { it !== sink.batches[0] && it !== sink.batches[1] }.frames.size)
        assertEquals(listOf("13/166", "15/20", "15/20"), port.trace().drop(4))
        assertEquals("life_log_guide", cmd(6).getString("cmd"))
        assertTrue(flag.value); assertTrue(src.stopping)
        port.glasses(15, 21, """{"cmd":"life_log_switch_result","payload":{"value":0}}"""); runCurrent()
        assertFalse(flag.value); assertFalse(src.stopping)
    }

    @Test fun startedFromTheGlassesMenuAcceptsTaskKeepsGuideAndEndsWhenGlassesSwitchOff() = runTest {
        val flag = MemoryFlag(); val sink = Collect(); var endedWith = ""
        val src = source(flag, fromGlasses = true, keepGuide = true) { endedWith = it }
        src.start(sink); runCurrent()
        assertEquals(listOf("13/162", "13/168"), port.trace())                // no switch: the glasses already started
        realtime(lifeLogFrames(5)) { true }
        port.glasses(15, 21, """{"cmd":"life_log_switch","payload":{"value":0,"mode":0,"data":""}}"""); runCurrent()
        assertEquals("LifeLog desligado nos óculos", endedWith)
        assertFalse(src.running); assertFalse(flag.value)
        assertEquals(5, sink.batches.single().frames.size)
        assertEquals(listOf("13/166", "15/20"), port.trace().drop(2))          // guide flag kept on
        assertEquals("life_log_switch", cmd(3).getString("cmd"))
    }

    @Test fun cachedBurstsAreTimedFromTheTaskStartAndRealtimeHolesCloseTheSegment() = runTest {
        val sink = Collect()
        val src = source(MemoryFlag())
        src.start(sink); runCurrent()
        val a1 = now
        port.glasses(13, 161); runCurrent()
        now += 20_000                                                          // a 20 s burst arrives late
        val frames = lifeLogFrames(62)
        frames.chunked(31).forEach { b ->
            port.glasses(13, 163, """{"taskId":"t","mode":1,"frameCount":31,"vpuMask":0,"vadMask":0}""", b.fold(ByteArray(0)) { x, y -> x + y }); runCurrent()
        }
        assertEquals(listOf(31, 31), sink.batches.map { it.frames.size }); assertTrue(sink.batches.all { it.cached })
        assertEquals(a1 + 31 * 60, sink.batches[0].receivedAtWall)             // capture time, not arrival time
        assertEquals(a1 + 62 * 60, sink.batches[1].receivedAtWall)
        realtime(lifeLogFrames(3)) { false }
        now += 5_000
        realtime(lifeLogFrames(1)) { false }
        assertEquals(listOf("lifelog-pausa"), sink.gaps)
        src.stop(); runCurrent()
    }

    @Test fun glassesFramesFlowIntoSegmentsWithWearerFlags() = runTest {
        val sealed = ArrayList<SegmentManifest>()
        val src = source(MemoryFlag())
        val ingest = AmbientIngest(tmp.root, src, { _, m -> sealed.add(m) }, now = { now })
        src.start(ingest); runCurrent()
        port.glasses(13, 161); runCurrent()
        realtime(lifeLogFrames(34)) { it >= 17 }
        src.stop(); runCurrent()
        ingest.seal("stop")
        val m = sealed.single()
        assertEquals(60, m.frameMs); assertEquals(34, m.frames); assertEquals("glasses_lifelog", m.source)
        assertTrue(m.providesWearer && m.headMounted)
        val (flags, _) = SegmentReader(java.io.File(tmp.root, m.id)).flags()
        assertArrayEquals(BooleanArray(34) { it >= 17 }, BooleanArray(34) { flags[it].toInt() and FrameFlags.WEARER != 0 })
    }
}
