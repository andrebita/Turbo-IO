package org.turboio.customai.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.turboio.customai.R
import org.turboio.customai.TurboApp
import org.turboio.customai.ble.Phase
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Home: glasses status, shortcut tiles for every feature, today's pending items and the "ask the AI" bar. */
class HomeFragment : Fragment() {
    private val app get() = TurboApp.instance
    private lateinit var frame: FrameLayout
    private var today: Pair<String, List<String>> = "Hoje" to listOf("Carregando…")

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View =
        FrameLayout(requireContext()).also { frame = it }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { today = withContext(Dispatchers.IO) { loadToday() }; render() }
                // Only what the screen shows: the session status also changes on every received frame.
                combine(
                    app.status.map { Triple(it.phase, it.battery, it.deviceName + it.glassesVersions) }.distinctUntilChanged(),
                    app.ambient.status.map { it.recording }.distinctUntilChanged(),
                    app.teleprompter.state.map { it.phase }.distinctUntilChanged(),
                    app.glassesSettings.state.map { it.config }.distinctUntilChanged(),
                ) { _, _, _, _ -> }.collect { render() }
            }
        }
    }

    private fun main() = activity as? MainActivity

    private fun render() {
        if (!isAdded) return
        val s = app.status.value
        val connected = when (s.phase) { Phase.READY -> true; Phase.IDLE, Phase.FAILED -> false; else -> null }
        val firmware = s.glassesVersions.split("·").firstOrNull { it.contains("1.") }?.substringAfter("=")?.trim()?.let { "Strix OS $it" }
            ?: if (connected == true) "Firmware original" else if (app.session.hasSaved()) "Óculos salvo · toque em Conectar" else "Nenhum óculos salvo"
        val model = HomeModel(
            name = s.deviceName.ifBlank { "RayNeo iO" }.take(24),
            connected = connected, connection = ConnectionFragment.phaseLabel(s.phase),
            battery = s.battery.takeIf { it in 0..100 && connected == true },
            firmware = firmware, tiles = tiles(connected == true),
            todayTitle = today.first, todayLines = today.second,
        )
        val scrollY = (frame.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.scrollY ?: 0
        frame.removeAllViews()
        frame.addView(HomeView.build(requireContext(), model, HomeActions(
            connect = { if (!app.connectSaved()) main()?.go(R.id.nav_glasses) },
            glasses = { main()?.go(R.id.nav_glasses) },
            today = { main()?.go(R.id.nav_memory) },
            ask = { main()?.open(TestFragment()) },
        )))
        (frame.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.post { (frame.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.scrollTo(0, scrollY) }
    }

    private fun tiles(connected: Boolean): List<Tile> {
        val st = app.settings
        val voiceOn = st.voiceSettings().enabled
        val engine = st.sttConfig().engine.label.substringAfter("→ ", "").substringBefore(" (").ifEmpty { "microfone do telefone" }
        val rec = app.ambient.status.value.recording
        val tp = app.teleprompter.state.value
        val mem = st.memorySettings()
        val wake = app.glassesSettings.state.value.config
        val features = app.features.state.value
        return listOf(
            Tile(R.drawable.ic_mic, "Assistente de voz", if (voiceOn) "Ligado · $engine" else "Desligado", voiceOn) {
                st.setVoiceEnabled(!voiceOn); render()
            },
            Tile(R.drawable.ic_record, "Gravar o dia", if (rec) "Gravando" else "Parado", rec) { main()?.go(R.id.nav_memory) },
            Tile(R.drawable.ic_prompter, "Teleprompter", if (app.teleprompter.active) tp.phase.label.replaceFirstChar { it.uppercase() } else "Enviar roteiro",
                app.teleprompter.active) { main()?.open(TestFragment()) },
            Tile(R.drawable.ic_bell, "Notificações", if (st.mirrorOn) "Ligado" + if (st.navOn) " · Maps" else "" else "Desligado", st.mirrorOn) { main()?.go(R.id.nav_glasses) },
            Tile(R.drawable.ic_todo, "To-do nos óculos", if (mem.enabled && mem.glassesTodo) "Ligado" else "Desligado", mem.enabled && mem.glassesTodo) {
                st.saveMemory(mem.copy(glassesTodo = !mem.glassesTodo)); app.memoryChanged(); render()
            },
            Tile(R.drawable.ic_lookup, "Olhar para cima",
                when (wake.wakeHeadup) { 1 -> "Ligado · ${wake.wakeDegree}°"; 0 -> "Desligado"; else -> if (connected) "Ler ajustes" else "—" },
                wake.wakeHeadup == 1) { main()?.go(R.id.nav_glasses) },
            Tile(R.drawable.ic_weather, "Tempo", if (st.weatherOn) st.weatherCity.ifBlank { "Ligado" } else "Desligado", st.weatherOn) { main()?.go(R.id.nav_glasses) },
            Tile(R.drawable.ic_flask, "Bateria de testes", if (features.active) "${features.results.size} de ${app.features.steps.size}" else "Testar tudo",
                features.active) { main()?.open(DiagnosticsFragment()) },
        )
    }

    private fun loadToday(): Pair<String, List<String>> = runCatching {
        if (!app.settings.memorySettings().enabled) return "Memória desligada" to listOf("Ligue em Memória → Pendências → Configurações.")
        val open = app.memoryStore.openItems(50)
        if (open.isEmpty()) return "Sem pendências" to listOf("A memória cria pendências a partir das suas conversas.")
        val fmt = DateTimeFormatter.ofPattern("dd/MM HH:mm")
        val lines = open.take(3).map { i ->
            i.text.take(60) + (i.dueAt?.let { " · " + Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(fmt) } ?: "")
        }
        (if (open.size == 1) "1 pendência" else "${open.size} pendências") to lines
    }.getOrElse { "Hoje" to listOf("Não foi possível ler a memória") }
}
