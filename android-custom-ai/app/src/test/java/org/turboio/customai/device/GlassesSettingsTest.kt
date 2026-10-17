package org.turboio.customai.device

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.turboio.customai.protocol.Business
import org.turboio.customai.voice.FakePort

class MemoryOriginals(var c: GlassesConfig? = null) : OriginalSettings { override fun load() = c; override fun save(c: GlassesConfig) { this.c = c } }

@OptIn(ExperimentalCoroutinesApi::class)
class GlassesSettingsTest {
    private val port = FakePort()
    private fun settingsReply(height: Int, distance: Int, double: Int, long: Int) =
        """{"generalSettings":{"displayConfig":{"height":$height,"distance":$distance},"crownConfig":{"double":$double,"longPress":$long},"privacyConfig":{"x":1}}}"""
    private fun data(i: Int) = JSONObject(JSONObject(port.sent[i].second.jsonText()).getJSONObject("payload").getString("data"))

    /** Answers every settings read with the given values (the glasses' state). */
    private fun TestScope.glassesAnswer(height: Int, distance: Int, double: Int, long: Int) {
        runCurrent(); port.glasses(15, 4, settingsReply(height, distance, double, long)); runCurrent()
    }

    @Test fun readsThenWritesBothKeysKeepingTheOtherAndKeepsOriginals() = runTest {
        val originals = MemoryOriginals()
        val s = GlassesSettings(port, this, originals, clock = { 1000L })
        var result: String? = "?"
        s.read { result = it }; glassesAnswer(3, 2, 0, 5)
        assertNull(result); assertEquals(GlassesConfig(3, 2, 0, 5, 1000, keys = "crownConfig,displayConfig,privacyConfig"), s.state.value.config); assertEquals(3, originals.c!!.height)
        s.setDisplay(height = 1) { result = it }
        glassesAnswer(3, 2, 0, 5)                                                   // fresh read before writing
        assertEquals(listOf("15/4", "15/4", "15/5"), port.trace())
        assertEquals("display_config", JSONObject(port.sent[2].second.jsonText()).getString("cmd"))
        assertEquals(1, data(2).getInt("height")); assertEquals(2, data(2).getInt("distance"))   // distance untouched
        port.glasses(15, 6, """{"cmd":"display_config","payload":{"value":0,"data":"{\"height\":1,\"distance\":2}"}}"""); runCurrent()
        glassesAnswer(1, 2, 0, 5)                                                   // read back
        assertNull(result); assertEquals(1, s.state.value.config.height); assertEquals(3, originals.c!!.height)   // original kept
        s.restore { result = it }
        glassesAnswer(1, 2, 0, 5)
        assertEquals(3, data(port.sent.lastIndex).getInt("height"))
    }

    @Test fun doubleTapToDoPreservesLongPressAndRefusesUnknownActions() = runTest {
        val s = GlassesSettings(port, this, MemoryOriginals())
        var result: String? = "?"
        s.setDoubleTapTodo(true) { result = it }
        glassesAnswer(3, 1, 0, 7)
        assertEquals(8, data(1).getInt("double")); assertEquals(7, data(1).getInt("longPress"))
        advanceTimeBy(7000); runCurrent(); glassesAnswer(3, 1, 8, 7)
        assertEquals("Os óculos não confirmaram (6 s); valores relidos acima", result)
        // Another device whose double-tap does something else: nothing is sent.
        val port2 = FakePort()
        val other = GlassesSettings(port2, this, MemoryOriginals(GlassesConfig(3, 1, 4, 7, 1)))
        other.setDoubleTapTodo(true) { result = it }; runCurrent()
        assertTrue(result!!.contains("outra função")); assertTrue(port2.sent.isEmpty())
    }

    @Test fun noReplyNoWriteAndContentGateRejectsOtherValues() = runTest {
        val s = GlassesSettings(port, this, MemoryOriginals())
        var result: String? = null
        s.setDisplay(height = 1) { result = it }; runCurrent(); advanceTimeBy(7000); runCurrent()
        assertEquals("Os óculos não responderam (6 s)", result); assertEquals(listOf("15/4"), port.trace())
        assertTrue(Business.contentAllowed(15, 5, SettingsWire.display(5, 1)))
        assertTrue(Business.contentAllowed(15, 5, SettingsWire.display(3, 3)))      // experimental third depth
        assertFalse(Business.contentAllowed(15, 5, SettingsWire.display(3, 4)))
        assertFalse(Business.contentAllowed(15, 5, SettingsWire.display(6, 1)))
        assertFalse(Business.contentAllowed(15, 5, SettingsWire.crown(4, 1)))
        assertFalse(Business.contentAllowed(15, 5, """{"cmd":"display_config","payload":{"value":0,"mode":0,"data":"{\"height\":3,\"distance\":1,\"x\":1}"}}"""))
        assertFalse(Business.contentAllowed(15, 5, """{"cmd":"factory_reset","payload":{"value":0,"mode":0,"data":"{}"}}"""))
        assertFalse(Business.contentAllowed(15, 4, """{"cmd":"request_general_status","payload":{}}"""))
        assertFalse(Business.outboundAllowed(22, 6)); assertFalse(Business.outboundAllowed(18, 1)); assertFalse(Business.outboundAllowed(9, 1))
    }

    private class MemLearned : LearnedDisplay {
        val h = HashSet<Int>(); val d = HashSet<Int>()
        override fun heights() = h; override fun distances() = d
        override fun add(height: Int?, distance: Int?) { height?.let(h::add); distance?.let(d::add) }
    }

    @Test fun experimentalDepthIsVerifiedUndoneOrLearned() = runTest {
        val learned = MemLearned()
        val s = GlassesSettings(port, this, MemoryOriginals(), clock = { 1000L }, learned = learned)
        var result: String? = "?"
        // Not applied: the glasses report another value → the previous values are written back.
        s.setDisplay(distance = 3) { result = it }
        glassesAnswer(3, 1, 0, 5)
        assertEquals(3, data(1).getInt("distance"))
        port.glasses(15, 6, """{"cmd":"display_config","payload":{"value":0}}"""); runCurrent()
        glassesAnswer(3, 2, 0, 5)                                   // read back: changed to something else
        glassesAnswer(3, 2, 0, 5)                                   // fresh read before restoring
        assertEquals(1, data(port.sent.lastIndex).getInt("distance"))
        port.glasses(15, 6, """{"cmd":"display_config","payload":{"value":0}}"""); runCurrent()
        glassesAnswer(3, 1, 0, 5)
        assertTrue(result!!, result!!.startsWith("Os óculos não aplicaram") && result!!.contains("restaurados"))
        assertFalse(3 in s.distances())
        // Applied: becomes a selectable value.
        s.setDisplay(distance = 3) { result = it }
        glassesAnswer(3, 1, 0, 5)
        port.glasses(15, 6, """{"cmd":"display_config","payload":{"value":0}}"""); runCurrent()
        glassesAnswer(3, 3, 0, 5)
        assertNull(result); assertEquals(listOf(1, 2, 3), s.distances())
        // A level read from the glasses (set in the official app) is offered too.
        s.read { result = it }; glassesAnswer(4, 0, 0, 5)
        assertEquals(listOf(1, 3, 4, 5), s.heights()); assertEquals(listOf(0, 1, 2, 3), s.distances())
    }

    @Test fun headGesturesReadToggleAndGate() = runTest {
        val s = GlassesSettings(port, this, MemoryOriginals())
        var result: String? = "?"
        val reply = { on: Int -> """{"generalSettings":{"displayConfig":{"height":1,"distance":2},"headGestures":{"enabled":$on,"mode":1,"angle":20}}}""" }
        s.setHeadGestures(false) { result = it }
        runCurrent(); port.glasses(15, 4, reply(1)); runCurrent()
        val sent = JSONObject(port.sent.last().second.jsonText())
        assertEquals("head_gestures", sent.getString("cmd"))
        assertEquals(0, sent.getJSONObject("payload").getInt("value")); assertEquals(1, sent.getJSONObject("payload").getInt("mode"))  // mode kept
        port.glasses(15, 6, """{"cmd":"head_gestures","payload":{"value":0}}"""); runCurrent()
        port.glasses(15, 4, reply(0)); runCurrent()
        assertNull(result); assertEquals(false, s.state.value.config.headEnabled)
        assertTrue(s.state.value.details.contains("headGestures="))
        assertTrue(Business.contentAllowed(15, 5, SettingsWire.head(true, 0)))
        assertFalse(Business.contentAllowed(15, 5, """{"cmd":"head_gestures","payload":{"value":2,"mode":0,"data":""}}"""))
        assertFalse(Business.contentAllowed(15, 5, """{"cmd":"head_gestures","payload":{"value":1,"mode":0,"data":"{}"}}"""))
    }

    /** The groups Strix OS 1.0.5.5 reported in the feature test (2026-10-17). */
    private fun strix(headup: Int, degree: Int) = """{"generalSettings":{"displayConfig":{"height":1,"distance":2},""" +
        """"crownConfig":{"direction":0,"double":8,"longPress":1},"headGestures":{"enabled":1,"mode":0},""" +
        """"wakeupConfig":{"headupSwitch":$headup,"headupDegree":$degree,"crownSwitch":0}}}"""

    @Test fun lookUpAngleWrittenVerifiedAndCrownDirectionKept() = runTest {
        val originals = MemoryOriginals()
        val s = GlassesSettings(port, this, originals)
        var result: String? = "?"
        s.setWake(degree = 35) { result = it }
        runCurrent(); port.glasses(15, 4, strix(1, 20)); runCurrent()
        val sent = JSONObject(port.sent.last().second.jsonText())
        assertEquals("wakeup_config", sent.getString("cmd"))
        assertEquals(JSONObject("""{"headupSwitch":1,"headupDegree":35,"crownSwitch":0}""").toString(), data(port.sent.lastIndex).toString())
        port.glasses(15, 6, """{"cmd":"wakeup_config","payload":{"value":0}}"""); runCurrent()
        port.glasses(15, 4, strix(1, 35)); runCurrent()
        assertNull(result); assertEquals(35, s.state.value.config.wakeDegree)
        assertEquals(20, originals.c!!.wakeDegree)                                  // restore point
        assertEquals(null, s.state.value.config.wakeup)                            // screen wake is not the voice wake word
        // Not applied → reported.
        s.setWake(headup = false) { result = it }
        runCurrent(); port.glasses(15, 4, strix(1, 35)); runCurrent()
        port.glasses(15, 6, """{"cmd":"wakeup_config","payload":{"value":0}}"""); runCurrent()
        port.glasses(15, 4, strix(1, 35)); runCurrent()
        assertTrue(result!!, result!!.startsWith("Os óculos não aplicaram"))
        // Double-tap keeps the direction field read from the glasses.
        s.setDoubleTapTodo(false) { result = it }
        runCurrent(); port.glasses(15, 4, strix(1, 35)); runCurrent()
        assertEquals(JSONObject("""{"direction":0,"double":0,"longPress":1}""").toString(), data(port.sent.lastIndex).toString())
        // Gate.
        assertTrue(Business.contentAllowed(15, 5, SettingsWire.wake(1, 40, 0)))
        assertFalse(Business.contentAllowed(15, 5, SettingsWire.wake(1, 90, 0)))
        assertFalse(Business.contentAllowed(15, 5, """{"cmd":"wakeup_config","payload":{"value":0,"mode":0,"data":"{\\"headupSwitch\\":1}"}}"""))
    }
}
