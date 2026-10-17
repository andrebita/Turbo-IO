package org.turboio.customai.ui

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.turboio.customai.BuildConfig
import org.turboio.customai.TurboApp
import org.turboio.customai.ble.GlassesScanner
import org.turboio.customai.ble.Phase
import org.turboio.customai.ble.PrefsPeerStore
import org.turboio.customai.ble.SessionStatus
import org.turboio.customai.ble.bluetoothAdapter

class ConnectionFragment : Fragment() {
    private val app get() = TurboApp.instance
    private lateinit var phase: TextView
    private lateinit var note: TextView
    private lateinit var details: TextView
    private lateinit var bluetooth: TextView
    private lateinit var candidates: LinearLayout
    private lateinit var scanStatus: TextView
    private var scanner: GlassesScanner? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        val ui = Column(requireContext())
        val status = ui.card()
        status.title("Status dos óculos")
        phase = status.text(size = 20f)
        note = status.text()
        details = status.text(size = 13f)
        bluetooth = status.text(size = 13f)

        ui.title("Conectar")
        ui.hint("Antes da primeira conexão: (1) no app RayNeo oficial, desvincule os óculos; (2) em Configurações > Bluetooth do Android, " +
            "esqueça o dispositivo RayNeo; (3) mantenha o botão dos óculos pressionado ~5 s até o LED azul piscar. " +
            "O firmware não é alterado em nenhuma etapa.")
        ui.button("Procurar óculos (15 s)") { scan() }
        scanStatus = ui.hint("")
        candidates = ui.row().apply { orientation = LinearLayout.VERTICAL }
        ui.button("Reconectar óculos salvo", filled = false) {
            if (!permitted()) return@button
            if (!app.connectSaved()) toast("Nenhum óculos salvo; procure e selecione primeiro")
        }
        ui.button("Desconectar", filled = false) { app.disconnect() }
        ui.button("Esquecer óculos (voltar ao app oficial)", filled = false) {
            AlertDialog.Builder(requireContext()).setTitle("Esquecer óculos?")
                .setMessage("A identidade salva será apagada. Para voltar ao app RayNeo oficial: remova o pareamento em Configurações > Bluetooth, " +
                    "coloque os óculos em modo de pareamento e vincule-os novamente no app oficial.")
                .setPositiveButton("Esquecer") { _, _ -> app.session.forget(); org.turboio.customai.service.ConnectionService.stop(requireContext()) }
                .setNegativeButton("Cancelar", null).show()
        }
        ui.switch("Manter conexão com a tela desligada (serviço em primeiro plano)", app.settings.keepConnected) { app.settings.keepConnected = it }

        ui.title("Versões")
        ui.text(versionText(), size = 13f)
        glassesCard(ui)
        return ui.scroll()
    }

    private lateinit var settingsText: TextView
    private var height = 0
    private var distance = 0

    /** Settings the official app changes, limited to evidence-backed values (docs/AJUSTES_DOS_OCULOS.md). */
    private fun glassesCard(ui: Column) {
        val c = ui.card()
        c.title("Ajustes da tela dos óculos (experimental)")
        c.hint("Os mesmos ajustes do app oficial, só com os valores já observados nele. O app lê os valores atuais antes de mudar " +
            "e guarda os originais para restaurar. Confira o efeito nas lentes.")
        settingsText = c.text("", size = 13f)
        fun done(msg: String?) = view?.post { android.widget.Toast.makeText(requireContext(), msg ?: "Feito — confira nas lentes", android.widget.Toast.LENGTH_SHORT).show() }
        c.button("Ler ajustes dos óculos", filled = false) { app.glassesSettings.read { done(it ?: "Ajustes lidos") } }
        c.text("Altura do texto", size = 13f)
        c.spinner(org.turboio.customai.device.SettingsWire.HEIGHTS, 0, { "Altura $it" }) { height = it }
        c.text("Distância", size = 13f)
        c.spinner(org.turboio.customai.device.SettingsWire.DISTANCES, 0, { "Distância $it" }) { distance = it }
        c.button("Aplicar altura e distância") { app.glassesSettings.setDisplay(height, distance) { done(it) } }
        c.buttons("Duplo toque abre To-do" to { app.glassesSettings.setDoubleTapTodo(true) { done(it) } },
            "Desligar" to { app.glassesSettings.setDoubleTapTodo(false) { done(it) } })
        c.button("Restaurar ajustes originais", filled = false) {
            AlertDialog.Builder(requireContext()).setTitle("Restaurar?").setMessage("Volta altura, distância e duplo toque aos primeiros valores lidos dos óculos.")
                .setPositiveButton("Restaurar") { _, _ -> app.glassesSettings.restore { done(it ?: "Restaurado") } }.setNegativeButton("Cancelar", null).show()
        }
        c.hint("O tipo de painel (dashboard) não pode ser alterado: o comando não é conhecido. Para trocar, use o app oficial.")
    }

    private fun renderSettings(s: org.turboio.customai.device.SettingsState) {
        if (!::settingsText.isInitialized) return
        val c = s.config
        settingsText.text = buildString {
            append(if (c.readAt > 0) "Atual: altura ${c.height ?: "?"} · distância ${c.distance ?: "?"} · duplo toque ${when (c.crownDouble) { 8 -> "abre To-do"; 0 -> "sem ação"; null -> "?"; else -> "função ${c.crownDouble}" }}"
                else "Ainda não lido")
            s.original?.let { o -> append("\nOriginais: altura ${o.height ?: "?"} · distância ${o.distance ?: "?"} · duplo toque ${o.crownDouble ?: "?"}") }
            append("\n").append(s.note)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { app.glassesSettings.state.collect { renderSettings(it) } }
                app.status.collect { render(it) }
            }
        }
    }

    override fun onStop() { scanner?.stop(); super.onStop() }

    private fun render(s: SessionStatus) {
        val ok = when (s.phase) { Phase.READY -> true; Phase.FAILED -> false; else -> null }
        phase.text = phaseLabel(s.phase); phase.setTextColor(statusColor(ok))
        note.text = s.note
        details.text = buildString {
            append("Bateria: ${if (s.battery >= 0) "${s.battery}%" else "—"} · MTU: ${s.mtu}")
            if (s.protocolVersion >= 0) append(" · Protocolo: v${s.protocolVersion}")
            if (s.glassesVersions.isNotEmpty()) append("\nÓculos: ${s.glassesVersions}")
            if (s.readySince > 0) append("\nConectado há ${(android.os.SystemClock.elapsedRealtime() - s.readySince) / 1000} s")
            if (s.lastReason.isNotEmpty()) append("\nÚltimo evento: ${s.lastReason}")
            val saved = PrefsPeerStore(requireContext()).load()
            append("\nÓculos salvo: ${saved?.name ?: "nenhum"}")
        }
        val adapter = bluetoothAdapter(requireContext())
        bluetooth.text = "Bluetooth do telefone: " + when {
            adapter == null -> "indisponível"
            !adapter.isEnabled -> "DESLIGADO"
            else -> "ligado"
        } + if (!(activity as MainActivity).bluetoothPermitted()) " · permissões pendentes" else ""
    }

    private fun permitted(): Boolean {
        val activity = activity as MainActivity
        if (!activity.bluetoothPermitted()) { activity.requestPermissionsIfNeeded(); toast("Conceda as permissões de Bluetooth"); return false }
        return true
    }

    private fun scan() {
        if (!permitted()) return
        candidates.removeAllViews()
        val s = scanner ?: GlassesScanner(requireContext()).also { scanner = it }
        val error = s.start { list, running ->
            if (!isAdded) return@start
            scanStatus.text = if (running) "Procurando… ${list.size} candidato(s)" else "Busca concluída · ${list.size} candidato(s) (ainda não autenticados)"
            candidates.removeAllViews()
            val column = Column(requireContext())
            list.sortedByDescending { it.rssi }.forEach { c ->
                column.button("${c.name} · sinal ${c.rssi} dBm${if (c.serviceMatched) " · serviço RayNeo" else ""}", filled = false) {
                    s.stop(); app.connect(c.address, c.name)
                }
            }
            candidates.addView(column.root)
        }
        if (error != null) scanStatus.text = error
    }

    private fun versionText(): String {
        val official = try {
            val info = requireContext().packageManager.getPackageInfo("com.rayneo.venus", 0)
            "instalado · versão ${info.versionName}"
        } catch (_: Exception) { "não encontrado (com.rayneo.venus)" }
        return "Turbo IO IA: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
            "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "App RayNeo oficial: $official\n" +
            "Firmware dos óculos: nunca é modificado por este app (sem OTA)."
    }

    private fun toast(text: String) = android.widget.Toast.makeText(requireContext(), text, android.widget.Toast.LENGTH_LONG).show()

    companion object {
        fun phaseLabel(p: Phase) = when (p) {
            Phase.IDLE -> "Desconectado"
            Phase.CONNECTING -> "Conectando…"
            Phase.AUTHENTICATING -> "Autenticando…"
            Phase.PAIRING -> "Pareando…"
            Phase.INITIALIZING -> "Inicializando…"
            Phase.READY -> "Conectado"
            Phase.RETRYING -> "Reconectando…"
            Phase.FAILED -> "Falha"
        }
    }
}
