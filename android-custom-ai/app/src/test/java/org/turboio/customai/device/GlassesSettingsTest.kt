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
        assertFalse(Business.contentAllowed(15, 5, SettingsWire.display(2, 1)))
        assertFalse(Business.contentAllowed(15, 5, SettingsWire.display(3, 3)))
        assertFalse(Business.contentAllowed(15, 5, SettingsWire.crown(4, 1)))
        assertFalse(Business.contentAllowed(15, 5, """{"cmd":"display_config","payload":{"value":0,"mode":0,"data":"{\"height\":3,\"distance\":1,\"x\":1}"}}"""))
        assertFalse(Business.contentAllowed(15, 5, """{"cmd":"factory_reset","payload":{"value":0,"mode":0,"data":"{}"}}"""))
        assertFalse(Business.contentAllowed(15, 4, """{"cmd":"request_general_status","payload":{}}"""))
        assertFalse(Business.outboundAllowed(22, 6)); assertFalse(Business.outboundAllowed(18, 1)); assertFalse(Business.outboundAllowed(9, 1))
    }
}
