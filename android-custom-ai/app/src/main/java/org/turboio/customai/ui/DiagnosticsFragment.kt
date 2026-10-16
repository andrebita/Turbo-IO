package org.turboio.customai.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.turboio.customai.BuildConfig
import org.turboio.customai.TurboApp
import org.turboio.customai.ble.PrefsPeerStore
import org.turboio.customai.diag.DiagnosticLog
import org.turboio.customai.service.ConnectionService

class DiagnosticsFragment : Fragment() {
    private val app get() = TurboApp.instance
    private lateinit var info: TextView
    private lateinit var test: TextView
    private lateinit var log: TextView
    private val refresh: () -> Unit = { view?.post { renderLog() } }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        val ui = Column(requireContext())
        ui.title("Versões e conexão")
        info = ui.text(size = 13f, mono = true)

        ui.title("Testes de comunicação")
        ui.button("Teste BLE: ler estado dos óculos") { bleTest() }
        ui.button("Teste do provedor de IA (pergunta curta)", filled = false) { aiTest() }
        test = ui.text(size = 13f)

        ui.title("Log técnico (sem chaves, endereços ou conteúdo)")
        val row = ui.row()
        val c = Column(requireContext())
        c.button("Copiar log", filled = false) {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Turbo IO log", header() + "\n" + DiagnosticLog.export()))
            Toast.makeText(requireContext(), "Log copiado", Toast.LENGTH_SHORT).show()
        }
        c.button("Limpar log", filled = false) { DiagnosticLog.clear() }
        row.addView(c.root)
        log = ui.text(size = 11f, mono = true)

        ui.title("Reversão")
        ui.button("Apagar todos os dados deste app", filled = false) {
            AlertDialog.Builder(requireContext()).setTitle("Apagar dados?")
                .setMessage("Remove chaves de API, configurações e o óculos salvo deste app. O app oficial e o firmware não são afetados.")
                .setPositiveButton("Apagar") { _, _ ->
                    app.session.forget(); app.settings.wipe(); app.clearHistory(); ConnectionService.stop(requireContext())
                    DiagnosticLog.i("APP", "dados locais apagados pelo usuário")
                }.setNegativeButton("Cancelar", null).show()
        }
        return ui.scroll()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        DiagnosticLog.addListener(refresh)
        renderLog()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { app.status.collect { info.text = header() } }
        }
    }

    override fun onDestroyView() { DiagnosticLog.removeListener(refresh); super.onDestroyView() }

    private fun header(): String {
        val s = app.status.value
        val ai = app.settings.aiConfig()
        val official = try { requireContext().packageManager.getPackageInfo("com.rayneo.venus", 0).versionName } catch (_: Exception) { "não instalado" }
        return """
            App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android ${Build.VERSION.RELEASE}/API ${Build.VERSION.SDK_INT}
            Aparelho: ${Build.MANUFACTURER} ${Build.MODEL}
            App RayNeo oficial: $official
            Fase: ${s.phase} · retries ${s.retries} · motivo: ${s.lastReason.ifEmpty { "—" }}
            Protocolo: v${s.protocolVersion} · campos versão: ${s.versionShape.ifEmpty { "—" }}
            Óculos (status): ${s.glassesVersions.ifEmpty { "—" }}
            Chaves do status: ${s.statusKeys.ifEmpty { "—" }}
            MTU ${s.mtu} · TX ${s.txBytes} B / ${s.framesOut} quadros · RX ${s.rxBytes} B / ${s.framesIn} quadros
            Respostas de status: ${s.statusReplies} · bateria ${if (s.battery >= 0) "${s.battery}%" else "—"}
            Óculos salvo: ${if (PrefsPeerStore(requireContext()).load() != null) "sim" else "não"}
            IA: ${ai.provider.id}/${ai.model} · chave ${if (app.settings.hasApiKey(ai.provider)) "presente" else "ausente"}
            Voz: ${app.settings.sttConfig().engine.id} · ${app.voice.state.value.phase}
        """.trimIndent()
    }

    private fun renderLog() {
        if (!isAdded) return
        log.text = DiagnosticLog.snapshot().takeLast(300).asReversed().joinToString("\n") { it.format() }
    }

    private fun bleTest() {
        if (!app.session.isReady) { test.text = "✗ Óculos não conectado"; test.setTextColor(statusColor(false)); return }
        val before = app.status.value.statusReplies
        test.text = "Enviando consulta de estado (somente leitura)…"; test.setTextColor(statusColor(null))
        app.session.requestStatus { error ->
            view?.post {
                if (error != null) { test.text = "✗ Envio falhou: $error"; test.setTextColor(statusColor(false)); return@post }
                viewLifecycleOwner.lifecycleScope.launch {
                    repeat(30) { if (app.status.value.statusReplies > before) return@repeat; delay(100) }
                    val ok = app.status.value.statusReplies > before
                    test.text = if (ok) "✓ Óculos responderam (bateria ${app.status.value.battery}%)" else "✗ Sem resposta em 3 s"
                    test.setTextColor(statusColor(ok))
                }
            }
        }
    }

    private fun aiTest() {
        test.text = "Consultando provedor…"; test.setTextColor(statusColor(null))
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val r = app.askAi("Teste de diagnóstico: responda apenas com a palavra OK.")
                test.text = "✓ ${r.provider.label}/${r.model} respondeu em ${r.latencyMs} ms: \"${r.text.take(60)}\""
                test.setTextColor(statusColor(true))
            } catch (e: Exception) {
                test.text = "✗ " + TurboApp.friendly(e); test.setTextColor(statusColor(false))
            }
        }
    }
}
