package org.turboio.customai.probe

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Where the guided suite keeps its progress, so it survives the app being killed (step G5 requires that). */
interface SuiteStore { fun load(): String?; fun save(json: String) }

class SuiteStep(
    val id: String,
    val title: String,
    val instructions: String,
    /** Script shown while audio is arriving: (seconds since audio start, what to do). */
    val cues: List<Pair<Int, String>> = emptyList(),
    val start: (HardwareProbe) -> String?,
) {
    fun cue(audioSeconds: Int): String? = if (audioSeconds < 0) null else cues.lastOrNull { it.first <= audioSeconds }?.second
}

data class StepResult(val id: String, val done: Boolean, val verdict: String, val savedAt: String)

data class SuiteState(
    val active: Boolean = false,
    val index: Int = 0,
    val running: Boolean = false,
    val startedAt: Long = 0,
    val results: List<StepResult> = emptyList(),
) {
    val finished: Boolean get() = index >= TestSuite.STEPS.size
    val current: SuiteStep? get() = TestSuite.STEPS.getOrNull(index)
}

/**
 * Runs every hardware probe in a fixed order with instructions between them; the user only taps "start" for each
 * step and exports one package at the end. Each step is an ordinary bounded probe (same OFF guarantees).
 */
class TestSuite(private val probe: HardwareProbe, private val store: SuiteStore, private val clock: () -> Long = System::currentTimeMillis) {
    companion object {
        val STEPS = listOf(
            SuiteStep("G1-G3", "LifeLog 40 s com roteiro",
                "Tenha outra pessoa por perto (ou um vídeo com voz). Siga as instruções que aparecem durante o teste.",
                listOf(0 to "VOCÊ fala: conte devagar de 1 a 10", 10 to "SILÊNCIO total", 20 to "OUTRA PESSOA fala (você calado)",
                    30 to "VOCÊ e a outra pessoa alternam frases")) { it.startLifeLog(40) },
            SuiteStep("G4b", "LifeLog em tempo real 60 s",
                "Fale o tempo todo, com pausas curtas. Observe se o contador de áudio sobe aos poucos ou de 20 em 20 s.",
                listOf(0 to "Fale normalmente, com pausas curtas")) { it.startLifeLog(60, realtimePage = true) },
            SuiteStep("G7", "Ligar o LifeLog pelos óculos",
                "Toque em Iniciar e, em até 90 s, ligue o LifeLog pelo menu dos óculos. Anote o que aparecer nas lentes.",
                listOf(0 to "Ligou! Fale algumas frases")) { it.startLifeLogFromGlasses(90, 30) },
            SuiteStep("G14", "Gravação pelo menu dos óculos",
                "Toque em Iniciar e, em até 60 s, comece uma gravação pelo menu dos óculos. Fale e pare a gravação pelos óculos.",
                listOf(0 to "Gravando: fale e depois pare pelos óculos")) { it.startRecording14(120) },
            SuiteStep("G4", "LifeLog 5 min",
                "Óculos no rosto o tempo todo. Converse ou leia em voz alta, com pausas. Pode deixar o telefone de lado.",
                listOf(0 to "Converse normalmente, óculos no rosto")) { it.startLifeLog(300) },
            SuiteStep("G5", "Desligamento após fechar o app à força",
                "Toque em Iniciar. Quando aparecer “áudio: 10 s”, feche o app à força (Configurações → Apps → Turbo IO → Forçar parada). " +
                    "Reabra o app e conecte os óculos: o app deve desligar o LifeLog sozinho e concluir esta etapa.",
                listOf(0 to "Aguarde…", 10 to "AGORA: force a parada do app e reabra")) { it.startLifeLog(600) },
        )
    }

    private val _state = MutableStateFlow(restore())
    val state: StateFlow<SuiteState> = _state

    init { probe.addFinishedListener { onProbeFinished(it) } }

    fun begin() = update(SuiteState(active = true, startedAt = clock()))

    fun startStep(): String? {
        val s = _state.value
        if (!s.active || s.running) return "Bateria não está aguardando uma etapa"
        val step = s.current ?: return "Bateria concluída"
        step.start(probe)?.let { return it }
        update(s.copy(running = true))
        return null
    }

    fun skip() {
        val s = _state.value
        val step = s.current ?: return
        if (!s.active || s.running) return
        update(s.copy(index = s.index + 1, results = s.results + StepResult(step.id, false, "pulado", "")))
    }

    fun end() = update(_state.value.copy(active = false, running = false))

    private fun onProbeFinished(p: ProbeState) {
        val s = _state.value
        if (!s.active || !s.running) return
        val step = s.current ?: return
        update(s.copy(index = s.index + 1, running = false, results = s.results + StepResult(step.id, true, p.verdict, p.savedAt)))
    }

    private fun update(s: SuiteState) {
        _state.value = s
        runCatching { store.save(encode(s)) }
    }

    private fun encode(s: SuiteState) = JSONObject().put("active", s.active).put("index", s.index).put("running", s.running)
        .put("startedAt", s.startedAt).put("results", JSONArray().apply {
            s.results.forEach { put(JSONObject().put("id", it.id).put("done", it.done).put("verdict", it.verdict).put("savedAt", it.savedAt)) }
        }).toString()

    private fun restore(): SuiteState = runCatching {
        val j = JSONObject(store.load() ?: return SuiteState())
        val r = j.getJSONArray("results")
        val index = j.getInt("index")
        // After a restart only G5 is still "running" (its OFF re-send on reconnect completes it); others are re-started.
        val running = j.getBoolean("running") && STEPS.getOrNull(index)?.id == "G5"
        SuiteState(j.getBoolean("active"), index, running, j.getLong("startedAt"),
            (0 until r.length()).map { r.getJSONObject(it).let { o -> StepResult(o.getString("id"), o.getBoolean("done"), o.getString("verdict"), o.getString("savedAt")) } })
    }.getOrDefault(SuiteState())
}
