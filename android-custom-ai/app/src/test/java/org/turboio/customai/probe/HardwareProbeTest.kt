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

/** Hardware LifeLog profile seen on a real RayNeo iO: mono 16 kHz, 60 ms CBR frames of 240 B (32 kbps). */
fun lifeLogFrames(count: Int): List<ByteArray> {
    val enc = OpusEncoder(16000, 1, OpusApplication.OPUS_APPLICATION_VOIP).apply { setBitrate(32000); setUseVBR(false) }
    var t = 0
    return (0 until count).map {
        val pcm = ShortArray(960) { i -> (5000 * sin(2 * PI * 220 * (t + i) / 16000.0)).toInt().toShort() }
        t += 960
        val b = ByteArray(1275); b.copyOf(enc.encode(pcm, 0, 960, b, 0, b.size))
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

    @Test fun hardwareProfileSixtyMsFramesInCachedBursts() = runTest {
        val sink = MemorySink()
        val probe = HardwareProbe(port, this, MemoryFlag(), fixedRandom, sink, clock = { testScheduler.currentTime })
        probe.startLifeLog(70, realtimePage = true); runCurrent()
        port.glasses(15, 21, """{"cmd":"life_log_switch_result","payload":{"value":0}}"""); runCurrent()
        port.glasses(13, 161); runCurrent()
        assertTrue(cmd(3).getBoolean("inRealtimePage"))
        val frames = lifeLogFrames(3 * 11 * 31)
        assertTrue(frames.all { it.size == 240 })
        // As logged on hardware: every 20 s, 11 envelopes of 31 frames ~200 ms apart; launcher state pushes in between.
        frames.chunked(31).chunked(11).forEach { burst ->
            advanceTimeBy(18_000); runCurrent()
            port.glasses(15, 24, """{"cmd":"state","bluetoothAddress":"AA:BB:CC:DD:EE:FF"}"""); runCurrent()
            burst.forEach { batch ->
                port.glasses(13, 163, """{"taskId":"t","mode":1,"frameCount":31,"vpuMask":0,"vadMask":0}""", batch.fold(ByteArray(0)) { a, f -> a + f })
                advanceTimeBy(200); runCurrent()
            }
        }
        advanceTimeBy(20_000); runCurrent()
        val report = sink.analysis!!.report
        val c = report.getJSONObject("continuity")
        assertEquals(60, report.getJSONObject("frameDurationsMs").keys().next().toInt())
        assertEquals(1, report.getJSONObject("decode").getInt("channels"))
        assertEquals(3, c.getInt("bursts")); assertEquals(60, c.getInt("typicalFrameMs"))
        assertEquals(3 * 11 * 31 * 60L, c.getLong("audioDurationMs"))
        assertTrue(c.getString("delivery").startsWith("lotes a cada ~20 s"))
        assertTrue(report.getString("verdict").contains("60 ms"))
        assertEquals(3 * 11 * 31 * 960 * 2, sink.analysis!!.pcm.size)
        // Launcher context kept for diagnosis, with the Bluetooth address redacted.
        assertTrue(report.getJSONObject("jsonKeys").has("15/24"))
        assertFalse(report.getJSONObject("jsonKeys").getJSONObject("15/24").getString("bluetoothAddress").contains("AA:BB"))
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
        assertFalse(flag.value); assertTrue(probe.state.value.verdict.contains("confirmado"))
        assertFalse(probe.busy)
    }

    @Test fun pendingOffResendIsSavedAsEvidenceAndNotConfusedByAnOldConfirmation() = runTest {
        val flag = MemoryFlag(false); val sink = MemorySink()
        val probe = HardwareProbe(port, this, flag, fixedRandom, sink, clock = { testScheduler.currentTime })
        probe.startLifeLog(1); runCurrent(); advanceTimeBy(1500); runCurrent()
        port.glasses(15, 21, """{"cmd":"life_log_switch_result","payload":{"value":0}}"""); runCurrent()
        advanceTimeBy(2000); runCurrent()
        assertTrue(probe.state.value.offConfirmed)
        // A later unconfirmed OFF must wait for its own confirmation, then be saved (G5 evidence).
        flag.value = true; port.sent.clear()
        probe.connectionReady(); runCurrent()
        assertTrue(probe.busy)
        advanceTimeBy(11_000); runCurrent()
        assertFalse(probe.busy); assertTrue(flag.value)
        assertEquals("probe-test-lifelog-off", probe.state.value.savedAt)
        assertTrue(probe.state.value.verdict.contains("sem confirmação"))
        assertEquals(2, probe.state.value.runs)
    }

    @Test fun glassesInitiatedLifeLogAnswersGuideLaunchWithSwitchOn() = runTest {
        val flag = MemoryFlag(); val sink = MemorySink()
        val probe = HardwareProbe(port, this, flag, fixedRandom, sink, clock = { testScheduler.currentTime })
        assertEquals(null, probe.startLifeLogFromGlasses(90, 5)); runCurrent()
        assertEquals(listOf("15/20"), port.trace())                       // only the guide flag: the user starts it
        assertEquals("life_log_guide", cmd(0).getString("cmd")); assertTrue(flag.value)
        advanceTimeBy(10_000); runCurrent()
        assertEquals("aguardando os óculos", probe.state.value.phase)
        // The glasses ask the phone for the LifeLog settings → the app switches LifeLog on itself.
        port.glasses(15, 21, """{"cmd":"life_log_guide_launch","payload":{"value":0,"mode":0,"data":""}}"""); runCurrent()
        assertEquals("life_log_switch", cmd(1).getString("cmd")); assertEquals(1, cmd(1).getJSONObject("payload").getInt("value"))
        port.glasses(15, 21, """{"cmd":"life_log_switch_result","payload":{"value":0}}"""); runCurrent()
        port.glasses(13, 161); runCurrent()
        assertEquals(listOf("13/162", "13/168"), port.trace().drop(2))
        lifeLogFrames(31).let { f -> port.glasses(13, 163, """{"taskId":"t","mode":1,"frameCount":31}""", f.fold(ByteArray(0)) { a, b -> a + b }) }
        advanceTimeBy(3000); runCurrent()
        assertEquals(3, probe.state.value.audioSeconds)
        advanceTimeBy(3000); runCurrent()
        assertEquals(listOf("13/166", "15/20", "15/20"), port.trace().drop(4).take(3))
        port.glasses(15, 21, """{"cmd":"life_log_switch_result","payload":{"value":0}}"""); runCurrent()
        advanceTimeBy(2000); runCurrent()
        val report = sink.analysis!!.report
        assertTrue(report.getString("trigger").contains("guide_launch")); assertTrue(report.getBoolean("appSwitchedOn"))
        assertEquals(31, report.getInt("audioFrames")); assertFalse(flag.value)
    }

    @Test fun scriptedTestCountsFromTheFirstAudioAndGivesUpWithoutIt() = runTest {
        val sink = MemorySink()
        val probe = HardwareProbe(port, this, MemoryFlag(), fixedRandom, sink, clock = { testScheduler.currentTime })
        probe.startLifeLog(10, realtimePage = true, countFromAudio = true); runCurrent()
        advanceTimeBy(20_000); runCurrent()
        assertEquals("aguardando o áudio dos óculos", probe.state.value.phase)
        port.glasses(13, 161); runCurrent()                                   // A1 after 20 s
        advanceTimeBy(9_000); runCurrent()
        assertEquals("capturando", probe.state.value.phase)                    // 10 s counted from the audio, not the switch
        advanceTimeBy(13_000); runCurrent()
        assertEquals("tempo do teste concluído", sink.analysis!!.report.getString("reason"))
        port.sent.clear()
        probe.startLifeLog(10, countFromAudio = true); runCurrent()
        advanceTimeBy(52_000); runCurrent()
        assertTrue(sink.analysis!!.report.getString("reason").contains("não iniciaram"))
    }

    @Test fun glassesInitiatedLifeLogTimesOutAndStillSwitchesOff() = runTest {
        val sink = MemorySink()
        val probe = HardwareProbe(port, this, MemoryFlag(), fixedRandom, sink, clock = { testScheduler.currentTime })
        probe.startLifeLogFromGlasses(5, 5); runCurrent()
        advanceTimeBy(6000); runCurrent()
        assertEquals(listOf("15/20", "13/166", "15/20", "15/20"), port.trace())
        advanceTimeBy(11_000); runCurrent()
        assertEquals("nenhum", sink.analysis!!.report.getString("trigger"))
        assertFalse(sink.analysis!!.report.getBoolean("appSwitchedOn"))
    }

    @Test fun glassesStartingOnTheirOwnAreAcceptedWithoutSwitch() = runTest {
        val sink = MemorySink()
        val probe = HardwareProbe(port, this, MemoryFlag(), fixedRandom, sink, clock = { testScheduler.currentTime })
        probe.startLifeLogFromGlasses(30, 2); runCurrent()
        port.glasses(15, 21, """{"cmd":"life_log_switch","payload":{"value":1}}"""); runCurrent()
        port.glasses(13, 161); runCurrent()
        assertEquals(listOf("15/20", "13/162", "13/168"), port.trace())
        assertTrue(probe.state.value.trigger.contains("switch = 1"))
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
