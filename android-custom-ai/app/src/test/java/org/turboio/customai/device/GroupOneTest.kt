package org.turboio.customai.device

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.turboio.customai.display.DisplaySettings
import org.turboio.customai.display.LensDisplay
import org.turboio.customai.memory.TaskMarker
import org.turboio.customai.protocol.Business
import org.turboio.customai.protocol.Commands
import org.turboio.customai.voice.FakePort
import org.turboio.customai.voice.fixedRandom

@OptIn(ExperimentalCoroutinesApi::class)
class GroupOneTest {
    @Test fun taskMarkerIsFoundOnlyAtTheStartAndRemoved() {
        val f = TaskMarker.find("[[TAREFA: Comprar pão | QUANDO: 2026-10-18T09:00]]\nAnotado: comprar pão amanhã às 9h.")!!
        assertEquals("Comprar pão", f.task); assertEquals("2026-10-18T09:00", f.due); assertEquals("Anotado: comprar pão amanhã às 9h.", f.rest)
        assertEquals("", TaskMarker.find("  [[tarefa: ligar pro João | quando: ]] Ok!")!!.due)
        assertNull(TaskMarker.find("Brasília é a capital. [[TAREFA: x | QUANDO: ]]"))
        assertNull(TaskMarker.find("Sem marcador."))
        assertTrue(TaskMarker.instruction(0).contains("[[TAREFA:"))
    }

    @Test fun weatherWireAndIcons() {
        val json = WeatherWire.update("São Paulo", 24, WeatherWire.icon(63), 1_760_000_000)
        val p = JSONObject(json).getJSONObject("payload")
        assertEquals("1760000000", p.getString("ts"))
        assertEquals(306, JSONObject(p.getString("data")).getInt("icon"))
        assertTrue(Business.contentAllowed(15, 18, json))
        assertFalse(Business.contentAllowed(15, 18, WeatherWire.update("X", 99, 100, 1)))     // temperature out of range
        assertEquals(150, WeatherWire.icon(0, isDay = false)); assertEquals(104, WeatherWire.icon(3)); assertEquals(999, WeatherWire.icon(42))
    }

    @Test fun displayLayoutAndFallbackToTheKnownLayout() = runTest {
        val start = JSONObject(Commands.displayStart("5a".repeat(16), 3, "top", 4)).getJSONObject("config")
        assertEquals(3, start.getInt("font_size")); assertEquals("top", start.getString("position")); assertEquals(4, start.getInt("max_lines"))
        val port = FakePort()
        val display = LensDisplay(port, this, fixedRandom)
        var result: String? = "pending"
        display.show("Olá", DisplaySettings(fontSize = 3, position = "top", maxLines = 4, lingerSeconds = 1)) { result = it }
        runCurrent()
        port.glasses(19, 8, "{\"sid\":\"${"5a".repeat(16)}\",\"code\":0}"); runCurrent()     // experimental layout refused
        port.glasses(19, 8, "{\"sid\":\"${"5a".repeat(16)}\",\"code\":1}"); runCurrent()     // default layout accepted
        advanceTimeBy(3000); runCurrent()
        assertNull(result)
        val opens = port.sent.filter { it.second.type == 7 }.map { JSONObject(it.second.jsonText()).getJSONObject("config") }
        assertEquals(listOf("top", "center"), opens.map { it.getString("position") })
    }

    @Test fun wakeWordEnableAndStateRead() = runTest {
        val port = FakePort()
        val s = GlassesSettings(port, this, MemoryOriginals())
        var result: String? = "pending"
        s.enableWakeWord { result = it }; runCurrent()
        assertEquals(listOf("15/16"), port.trace()); assertEquals(Business.WAKEUP_ON, port.sent[0].second.jsonText())
        port.glasses(15, 17, """{"cmd":"set_ai_voice_wakeup","payload":{"value":0}}"""); runCurrent()
        port.glasses(15, 4, """{"generalSettings":{"aiConfig":{"voiceWakeup":true},"displayConfig":{"height":3,"distance":1}}}"""); runCurrent()
        assertNull(result); assertEquals(true, s.state.value.config.wakeup)
        assertEquals("aiConfig,displayConfig", s.state.value.config.keys)
        val off = async { GlassesConfig.parse(JSONObject("""{"voice_wakeup":0}"""), 1).wakeup }
        assertEquals(false, off.await())
        advanceTimeBy(1)
    }
}
