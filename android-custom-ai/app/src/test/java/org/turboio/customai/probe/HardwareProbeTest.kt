package org.turboio.customai.probe

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusEncoder
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.math.PI
import kotlin.math.sin

class MemoryFlag(var value: Boolean = false) : PendingFlag { override fun get() = value; override fun set(value: Boolean) { this.value = value } }

class MemorySink : ProbeSink {
    var analysis: ProbeAnalysis.Result? = null; var records = 0
    override fun save(kind: String, records: List<ProbeRecord>, analysis: ProbeAnalysis.Result, events: List<String>): String {
        this.analysis = analysis; this.records = records.size; return "probe-test-$kind"
    }
}

/** 240-byte CBR stereo 20 ms Opus frames, like the glasses' recording/LifeLog profile; speech then silence. */
fun glassesFrames(speechFrames: Int, silentFrames: Int): List<ByteArray> {
    val enc = OpusEncoder(16000, 2, OpusApplication.OPUS_APPLICATION_VOIP).apply { setBitrate(96000); setUseVBR(false) }
    var t = 0
    return (0 until speechFrames + silentFrames).map { f ->
        val pcm = ShortArray(640) { i -> if (f < speechFrames) (6000 * sin(2 * PI * 200 * (t + i / 2) / 16000.0)).toInt().toShort() else 0 }
        t += 320
        val b = ByteArray(1275); b.copyOf(enc.encode(pcm, 0, 320, b, 0, b.size))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HardwareProbeTest {
    private val port = FakePort()
    private fun cmd(i: Int) = JSONObject(port.sent[i].second.jsonText())

    @Test fun lifeLogProbeFullCycleCapturesAnalyzesAndSwitchesOff() = runTest {
        val flag = MemoryFlag(); val sink = MemorySink()
        val probe = HardwareProbe(port, this, flag, fixedRandom, sink, clock = { testScheduler.currentTime })
        assertEquals(null, probe.startLifeLog(5))
        assertTrue(flag.value)                                           // pending OFF persisted before sending
        runCurrent()
        assertEquals(listOf("15/20", "15/20"), port.trace())
        assertEquals("life_log_guide", cmd(0).getString("cmd")); assertEquals(1, cmd(1).getJSONObject("payload").getInt("value"))
        assertEquals("{\"delay\":2}", cmd(1).getJSONObject("payload").getString("data"))
        port.glasses(15, 21, """{"cmd":"life_log_switch_result","payload":{"value":0}}"""); runCurrent()
        port.glasses(13, 161); runCurrent()
        assertEquals(listOf("13/162", "13/168"), port.trace().drop(2))
        val taskId = cmd(2).getString("taskId"); assertEquals(30, cmd(2).getInt("idleTimeoutSec"))
        // 3 batches of 31 frames: speech in the first 1.5 batches, silence after; vadMask as a 31-bit integer.
        val frames = glassesFrames(46, 47)
        assertTrue(frames.all { it.size == 240 })
        frames.chunked(31).forEachIndexed { b, batch ->
            var mask = 0L; batch.indices.forEach { i -> if (b * 31 + i < 46) mask = mask or (1L shl i) }
            port.glasses(13, 163, """{"taskId":"$taskId","frameCount":${batch.size},"mode":0,"vadMask":$mask,"vpuMask":$mask}""",
                batch.fold(ByteArray(0)) { a, f -> a + f }); runCurrent()
        }
        advanceTimeBy(6000); runCurrent()
        // stop sequence: A6, switch OFF, guide OFF
        assertEquals(listOf("13/166", "15/20", "15/20"), port.trace().drop(4).take(3))
        assertEquals(0, cmd(5).getJSONObject("payload").getInt("value")); assertEquals("life_log_guide", cmd(6).getString("cmd"))
        port.glasses(15, 21, """{"cmd":"life_log_switch","payload":{"value":0}}"""); runCurrent()
        assertFalse(flag.value); assertTrue(probe.state.value.offConfirmed)
        advanceTimeBy(2000); runCurrent()
        val report = sink.analysis!!.report
        assertEquals("concluído", probe.state.value.phase)
        assertEquals(93, report.getInt("audioFrames"))
        assertEquals(93, report.getJSONObject("frameSizes").getInt("240"))
        assertEquals(2, report.getJSONObject("decode").getInt("channels"))
        assertEquals(0, report.getJSONObject("decode").getInt("failed"))
        assertTrue(report.getJSONObject("masks").getInt("vadAgreementPct") >= 85)
        assertTrue(report.getJSONObject("masks").getDouble("vpuMeanRms") > 10 * report.getJSONObject("masks").getDouble("nonVpuMeanRms"))
        assertTrue(report.getString("verdict").contains("240 B decodificam"))
        assertTrue(report.getJSONObject("jsonKeys").getJSONObject("13/163").has("vpuMask"))
        assertEquals(sink.analysis!!.pcm.size, 93 * 320 * 2 * 2)
    }

    @Test fun refusalStopsAndStillSendsOff() = runTest {
        val flag = MemoryFlag()
        val probe = HardwareProbe(port, this, flag, fixedRandom, MemorySink(), clock = { testScheduler.currentTime })
        probe.startLifeLog(30); runCurrent()
        port.glasses(15, 21, """{"cmd":"life_log_switch_result","payload":{"value":2}}"""); runCurrent()
        assertTrue(probe.state.value.events.any { it.contains("abra as hastes") })
        assertEquals(listOf("13/166", "15/20", "15/20"), port.trace().drop(2).take(3))
        advanceTimeBy(11_000); runCurrent()
        assertTrue(flag.value)                        // no OFF confirmation → stays pending
        assertTrue(probe.state.value.pendingOff)
    }

    @Test fun pendingOffIsResentFirstOnReconnect() = runTest {
        val flag = MemoryFlag(true)
        val probe = HardwareProbe(port, this, flag, fixedRandom, MemorySink(), clock = { testScheduler.currentTime })
        probe.connectionReady(); runCurrent()
        assertEquals(listOf("13/166", "15/20", "15/20"), port.trace())
        port.glasses(15, 21, """{"cmd":"life_log_switch_result","payload":{"value":0}}"""); runCurrent()
        advanceTimeBy(2000); runCurrent()
        assertFalse(flag.value); assertTrue(probe.state.value.note.contains("confirmado"))
        assertFalse(probe.busy)
    }

    @Test fun recordingProbeAcksAndRebuildsOutOfOrderAudio() = runTest {
        val sink = MemorySink()
        val probe = HardwareProbe(port, this, MemoryFlag(), fixedRandom, sink, clock = { testScheduler.currentTime })
        probe.startRecording14(60); runCurrent()
        assertEquals(listOf("14/14"), port.trace())
        port.glasses(14, 1, """{"uuid":"rec-1","action":1}"""); runCurrent()
        assertEquals(2, cmd(1).getInt("action")); assertEquals(1, cmd(1).getInt("mode"))
        val audio = glassesFrames(50, 0).fold(ByteArray(0)) { a, f -> a + f }
        val half = 25 * 240
        port.glasses(14, 3, """{"uuid":"rec-1","offset":$half}""", audio.copyOfRange(half, audio.size)); runCurrent()
        port.glasses(14, 3, """{"uuid":"rec-1","offset":0}""", audio.copyOfRange(0, half)); runCurrent()
        port.glasses(14, 4, """{"uuid":"rec-1","action":1,"code":2}"""); runCurrent()
        assertEquals("14/4", port.trace().last())
        port.glasses(14, 6, """{"uuid":"rec-1","completed":true}"""); runCurrent()
        advanceTimeBy(2000); runCurrent()
        val report = sink.analysis!!.report
        assertTrue(report.getJSONObject("coverage").getBoolean("contiguous"))
        assertEquals(12_000, report.getJSONObject("coverage").getInt("bytes"))
        assertEquals(0, report.getJSONObject("decode").getInt("failed"))
        assertEquals("concluído", probe.state.value.phase)
    }

    @Test fun launcherContentGateOnlyAllowsLifeLogCommands() {
        assertTrue(Business.contentAllowed(15, 20, LifeLogWire.launcher("life_log_switch", true)))
        assertFalse(Business.contentAllowed(15, 20, """{"cmd":"factory_reset","payload":{}}"""))
        assertFalse(Business.contentAllowed(15, 20, """{"cmd":"brightness_change","payload":{"value":3}}"""))
        assertFalse(Business.outboundAllowed(14, 2)); assertFalse(Business.outboundAllowed(13, 163))
    }

    @Test fun tocAndMaskHelpers() {
        val t = ProbeAnalysis.toc(glassesFrames(1, 0)[0][0])
        assertTrue(t.stereo)
        val j = JSONObject("""{"a":5,"b":[1,0,true],"c":"010"}""")
        assertEquals(listOf(true, false, true), (0..2).map { ProbeAnalysis.maskBit(j, "a", it) })
        assertEquals(listOf(true, false, true), (0..2).map { ProbeAnalysis.maskBit(j, "b", it) })
        assertEquals(listOf(false, true, false), (0..2).map { ProbeAnalysis.maskBit(j, "c", it) })
    }
}
