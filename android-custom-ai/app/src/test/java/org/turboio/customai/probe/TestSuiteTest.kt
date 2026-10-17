package org.turboio.customai.probe

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.turboio.customai.voice.FakePort
import org.turboio.customai.voice.fixedRandom

class MemorySuiteStore(var json: String? = null) : SuiteStore { override fun load() = json; override fun save(json: String) { this.json = json } }

@OptIn(ExperimentalCoroutinesApi::class)
class TestSuiteTest {
    private val port = FakePort()

    @Test fun runsStepsInOrderRecordsResultsAndSurvivesRestartForG5() = runTest {
        val store = MemorySuiteStore(); val flag = MemoryFlag()
        val probe = HardwareProbe(port, this, flag, fixedRandom, MemorySink(), clock = { testScheduler.currentTime })
        val suite = TestSuite(probe, store, clock = { 1000L })
        assertNotNull(suite.startStep())                          // not begun
        suite.begin()
        assertEquals("G1-G3", suite.state.value.current!!.id)
        assertNull(suite.startStep()); runCurrent()
        assertTrue(suite.state.value.running); assertNotNull(suite.startStep())   // one step at a time
        port.glasses(15, 21, """{"cmd":"life_log_switch_result","payload":{"value":0}}"""); runCurrent()
        port.glasses(13, 161); runCurrent()
        advanceTimeBy(15_000); runCurrent()
        assertEquals("SILÊNCIO total", suite.state.value.current!!.cue(probe.state.value.audioSeconds))
        advanceTimeBy(30_000); runCurrent()
        port.glasses(15, 21, """{"cmd":"life_log_switch_result","payload":{"value":0}}"""); runCurrent()
        advanceTimeBy(2000); runCurrent()
        assertEquals(1, suite.state.value.index); assertFalse(suite.state.value.running)
        assertEquals("G1-G3", suite.state.value.results.single().id); assertTrue(suite.state.value.results.single().done)
        repeat(4) { suite.skip() }
        assertEquals("G5", suite.state.value.current!!.id)
        assertNull(suite.startStep()); runCurrent()
        assertTrue(flag.value)

        // App force-stopped: a new process restores the suite and completes G5 when the OFF re-send finishes.
        val port2 = FakePort()
        val probe2 = HardwareProbe(port2, this, flag, fixedRandom, MemorySink(), clock = { testScheduler.currentTime })
        val suite2 = TestSuite(probe2, store)
        assertTrue(suite2.state.value.running); assertEquals(5, suite2.state.value.index)
        probe2.connectionReady(); runCurrent()
        port2.glasses(15, 21, """{"cmd":"life_log_switch_result","payload":{"value":0}}"""); runCurrent()
        advanceTimeBy(2000); runCurrent()
        val s = suite2.state.value
        assertTrue(s.finished); assertFalse(s.running)
        assertEquals(listOf("G1-G3", "G7", "G8", "G14", "G4", "G5"), s.results.map { it.id })
        assertTrue(s.results.last().verdict.contains("confirmado")); assertEquals(1000L, s.startedAt)
        probe.stop(); advanceTimeBy(20_000); runCurrent()
    }

    @Test fun restartDuringAnOrdinaryStepWaitsForTheUserToRestartIt() {
        val store = MemorySuiteStore("""{"active":true,"index":1,"running":true,"startedAt":5,"results":[]}""")   // G7
        val probe = HardwareProbe(FakePort(), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), MemoryFlag(), fixedRandom, MemorySink())
        val s = TestSuite(probe, store).state.value
        assertTrue(s.active); assertFalse(s.running); assertEquals("G7", s.current!!.id)
    }
}
