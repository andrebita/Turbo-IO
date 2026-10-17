package org.turboio.customai.probe

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** What a running step can do besides its own work: tell the user what to do now, and wait with a countdown. */
interface StepRun {
    fun cue(text: String)
    suspend fun countdown(seconds: Int, text: String)
}

class FeatureStep(
    val id: String,
    val title: String,
    /** Shown before starting: what the app will do and what the user should do. */
    val instructions: String,
    /** Asked after the step: what to confirm on the lenses. */
    val question: String,
    val timeoutSeconds: Int = 180,
    val run: suspend (StepRun) -> String,
)

data class FeatureResult(val id: String, val title: String, val verdict: String, val outcome: String, val note: String, val folder: String)

enum class FeaturePhase { IDLE, RUNNING, VERDICT }

data class FeatureState(
    val active: Boolean = false,
    val index: Int = 0,
    val phase: FeaturePhase = FeaturePhase.IDLE,
    val cue: String = "",
    val outcome: String = "",
    val startedAt: Long = 0,
    val results: List<FeatureResult> = emptyList(),
)

/** Saves one finished step (result, traffic, log lines since it started); returns the folder name. */
fun interface FeatureSink { fun save(step: FeatureStep, result: FeatureResult, traffic: List<String>, startedAt: Long): String }

/**
 * Guided test of every app feature on the glasses: for each step the app does the action itself (capturing the glasses
 * traffic), shows the automatic result, and asks the user whether it really worked on the lenses. Only the user's answer
 * counts as approval. Progress survives the app being closed; a step interrupted that way is simply offered again.
 */
class FeatureSuite(
    val steps: List<FeatureStep>,
    private val scope: CoroutineScope,
    private val store: SuiteStore,
    private val recorder: TrafficRecorder,
    private val sink: FeatureSink,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(restore())
    val state: StateFlow<FeatureState> = _state
    private var job: Job? = null
    private var traffic: List<String> = emptyList()
    private var stepStartedAt = 0L
    /** Bumped by begin/end so a cancelled step cannot report into a newer state. */
    @Volatile private var runId = 0

    val current: FeatureStep? get() = steps.getOrNull(_state.value.index)

    fun begin() { runId++; job?.cancel(); recorder.stop(); update(FeatureState(active = true, startedAt = clock())) }

    /** Runs the current step; the result then waits for the user's verdict. */
    fun start(): String? {
        val s = _state.value
        if (!s.active || s.phase != FeaturePhase.IDLE) return "A bateria não está aguardando uma etapa"
        val step = current ?: return "Bateria concluída"
        stepStartedAt = clock()
        recorder.start()
        update(s.copy(phase = FeaturePhase.RUNNING, cue = "", outcome = ""))
        val run = object : StepRun {
            override fun cue(text: String) { _state.value = _state.value.copy(cue = text) }
            override suspend fun countdown(seconds: Int, text: String) {
                for (left in seconds downTo 1) { cue("$text · $left s"); delay(1000) }
                cue("")
            }
        }
        val id = ++runId
        job = scope.launch {
            val outcome = try {
                withTimeoutOrNull(step.timeoutSeconds * 1000L) { step.run(run) } ?: "Tempo esgotado (${step.timeoutSeconds} s)"
            } catch (e: CancellationException) {
                "Etapa interrompida pelo usuário"
            } catch (e: Exception) {
                "Erro: ${e.message ?: e.javaClass.simpleName}"
            }
            if (id == runId) finishRun(outcome)
        }
        return null
    }

    private fun finishRun(outcome: String) {
        traffic = recorder.stop()
        update(_state.value.copy(phase = FeaturePhase.VERDICT, cue = "", outcome = outcome))
    }

    /** Stops the running step (its result is kept as "interrupted" and still needs a verdict). */
    fun stop() { job?.cancel() }

    /** [worked] true = worked, false = did not work, null = could not tell. */
    fun verdict(worked: Boolean?, note: String = "") {
        val s = _state.value
        val step = current ?: return
        if (s.phase != FeaturePhase.VERDICT) return
        val label = when (worked) { true -> "funcionou"; false -> "não funcionou"; null -> "não deu para avaliar" }
        record(step, FeatureResult(step.id, step.title, label, s.outcome, note.trim().take(500), ""), save = true)
    }

    /** Runs the same step again (the previous attempt is discarded). */
    fun repeatStep() {
        val s = _state.value
        if (s.phase == FeaturePhase.VERDICT) update(s.copy(phase = FeaturePhase.IDLE, outcome = ""))
    }

    fun skip() {
        val s = _state.value
        val step = current ?: return
        if (!s.active || s.phase == FeaturePhase.RUNNING) return
        record(step, FeatureResult(step.id, step.title, "pulado", s.outcome, "", ""), save = false)
    }

    fun end() { runId++; job?.cancel(); recorder.stop(); update(_state.value.copy(active = false, phase = FeaturePhase.IDLE, cue = "")) }

    private fun record(step: FeatureStep, result: FeatureResult, save: Boolean) {
        val folder = if (save) runCatching { sink.save(step, result, traffic, stepStartedAt) }.getOrDefault("") else ""
        traffic = emptyList()
        val s = _state.value
        update(s.copy(index = s.index + 1, phase = FeaturePhase.IDLE, outcome = "", cue = "",
            results = s.results.filter { it.id != step.id } + result.copy(folder = folder)))
    }

    fun summary(): String = buildString {
        val s = _state.value
        append("Bateria de recursos iniciada em ${java.util.Date(s.startedAt)}\n")
        s.results.forEach { r ->
            append("\n[${r.id}] ${r.title}: ${r.verdict.uppercase()}\n  resultado do app: ${r.outcome.replace("\n", "\n    ")}\n")
            if (r.note.isNotEmpty()) append("  observação: ${r.note}\n")
        }
        val missing = steps.filter { st -> s.results.none { it.id == st.id } }
        if (missing.isNotEmpty()) append("\nNão executadas: ${missing.joinToString { it.id }}\n")
    }

    private fun update(s: FeatureState) { _state.value = s; runCatching { store.save(encode(s)) } }

    private fun encode(s: FeatureState) = JSONObject().put("active", s.active).put("index", s.index).put("startedAt", s.startedAt)
        .put("results", JSONArray().apply {
            s.results.forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("verdict", it.verdict).put("outcome", it.outcome)
                .put("note", it.note).put("folder", it.folder)) }
        }).toString()

    private fun restore(): FeatureState = runCatching {
        val j = JSONObject(store.load() ?: return FeatureState())
        val r = j.getJSONArray("results")
        FeatureState(j.getBoolean("active"), j.getInt("index"), FeaturePhase.IDLE, "", "", j.getLong("startedAt"),
            (0 until r.length()).map { r.getJSONObject(it).let { o ->
                FeatureResult(o.getString("id"), o.optString("title"), o.getString("verdict"), o.optString("outcome"), o.optString("note"), o.optString("folder"))
            } })
    }.getOrDefault(FeatureState())
}
