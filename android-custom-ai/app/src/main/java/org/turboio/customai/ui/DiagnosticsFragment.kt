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
    private lateinit var probeStatus: TextView
    private lateinit var suiteText: TextView
    private lateinit var cueText: TextView
    private lateinit var btnBegin: View
    private lateinit var btnStep: View
    private lateinit var btnStopStep: View
    private lateinit var btnSkip: View
    private lateinit var btnExportAll: View
    private lateinit var btnEnd: View
    private val refresh: () -> Unit = { view?.post { renderLog() } }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        val ui = Column(requireContext())
        ui.title("Versões e conexão")
        info = ui.text(size = 13f, mono = true)

        ui.title("Testes de comunicação")
        ui.button("Teste BLE: ler estado dos óculos") { bleTest() }
        ui.button("Teste do provedor de IA (pergunta curta)", filled = false) { aiTest() }
        test = ui.text(size = 13f)

        ui.title("Bateria de testes nos óculos (recomendado)")
        ui.hint("Todos os testes de hardware em sequência (cerca de 10 min), com instruções entre eles, e um único arquivo " +
            "para enviar no final. Nada é gravado no firmware; o LifeLog é desligado ao fim de cada etapa.")
        val suiteCard = ui.card()
        suiteText = suiteCard.text(size = 14f)
        cueText = suiteCard.text(size = 20f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }
        btnBegin = suiteCard.button("Começar bateria") { confirmProbe("bateria completa (≈ 10 min)") { app.suite.begin(); null } }
        btnStep = suiteCard.button("Iniciar esta etapa") { app.suite.startStep()?.let { Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show() } }
        btnStopStep = suiteCard.button("Parar esta etapa", filled = false) { app.probe.stop() }
        btnSkip = suiteCard.button("Pular etapa", filled = false) { app.suite.skip() }
        btnExportAll = suiteCard.button("Exportar todos os testes (1 arquivo)") { exportAll() }
        btnEnd = suiteCard.button("Encerrar bateria", filled = false) { app.suite.end() }

        ui.title("Testes de hardware avulsos (fase 3)")
        ui.hint("Testes curtos e limitados nos canais de áudio do firmware original. Nada é gravado no firmware. " +
            "Fale frases de teste e avise quem estiver perto. Ao final, exporte o pacote e envie para análise.")
        ui.button("G1–G3 · LifeLog 30 s") { confirmProbe("LifeLog por 30 s") { app.probe.startLifeLog(30) } }
        ui.button("G4 · LifeLog 5 min (fale com pausas)", filled = false) { confirmProbe("LifeLog por 5 min") { app.probe.startLifeLog(300) } }
        ui.button("G4b · LifeLog em tempo real 60 s", filled = false) { confirmProbe("LifeLog em tempo real por 60 s") { app.probe.startLifeLog(60, realtimePage = true) } }
        ui.button("Parar teste", filled = false) { app.probe.stop() }
        ui.button("Segurança: desligar LifeLog nos óculos", filled = false) { app.probe.forceLifeLogOff() }
        probeStatus = ui.text(size = 12.5f, mono = true)
        ui.button("Exportar último teste (com áudio)", filled = false) { exportProbe(true) }
        ui.button("Exportar último teste (sem áudio)", filled = false) { exportProbe(false) }
        ui.button("Apagar testes salvos no telefone", filled = false) {
            AlertDialog.Builder(requireContext()).setTitle("Apagar testes salvos?")
                .setMessage("Remove os áudios e relatórios dos testes de hardware guardados neste app.")
                .setPositiveButton("Apagar") { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { app.probeFiles.deleteAll() }
                    Toast.makeText(requireContext(), "Testes apagados", Toast.LENGTH_SHORT).show()
                }.setNegativeButton("Cancelar", null).show()
        }

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
                .setMessage("Remove chaves de API, configurações, o diário, a memória (pessoas e pendências) e o óculos salvo deste app. O app oficial e o firmware não são afetados.")
                .setPositiveButton("Apagar") { _, _ ->
                    if (app.settings.ambientSettings().glassesMenu) {   // tell the glasses the phone-side LifeLog setup is gone
                        app.settings.saveAmbient(app.settings.ambientSettings().copy(glassesMenu = false)); app.ambient.armGlassesMenu()
                    }
                    app.session.forget(); app.settings.wipe(); app.clearHistory()
                    viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { app.journal.wipe() }; runCatching { app.memoryStore.wipe() } }
                    app.ambient.stop(); app.ambient.wipe()
                    ConnectionService.stop(requireContext())
                    DiagnosticLog.i("APP", "dados locais apagados pelo usuário")
                }.setNegativeButton("Cancelar", null).show()
        }
        return ui.scroll()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        DiagnosticLog.addListener(refresh)
        renderLog()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { app.status.collect { info.text = header() } }
                launch { kotlinx.coroutines.flow.combine(app.suite.state, app.probe.state) { a, b -> a to b }.collect { (s, p) -> renderSuite(s, p) } }
                launch {
                    app.probe.state.collect { p ->
                        probeStatus.text = buildString {
                            append("Teste: ${p.kind.ifEmpty { "—" }} · ${p.phase}")
                            if (p.secondsLeft > 0) append(" · ${p.secondsLeft} s")
                            append("\n${p.note}\nPacotes: ${p.records} · áudio ${p.audioBytes / 1024} KB")
                            if (p.pendingOff) append("\n⚠ LifeLog pode estar LIGADO nos óculos — desligamento não confirmado")
                            else if (p.offConfirmed) append("\n✓ LifeLog desligado (confirmado pelos óculos)")
                            if (p.verdict.isNotEmpty()) append("\nResultado: ${p.verdict}")
                            if (p.savedAt.isNotEmpty()) append("\nSalvo: ${p.savedAt}")
                            if (p.events.isNotEmpty()) append("\n— eventos —\n").append(p.events.takeLast(12).joinToString("\n"))
                        }
                        probeStatus.setTextColor(statusColor(if (p.pendingOff) false else if (p.phase == "concluído") true else null))
                    }
                }
            }
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

    private fun confirmProbe(what: String, start: () -> String?) {
        AlertDialog.Builder(requireContext()).setTitle("Iniciar teste: $what?")
            .setMessage("O teste capta áudio dos óculos e salva no telefone para análise. Use frases de teste e avise quem estiver por perto. " +
                "Não altera o firmware; o LifeLog é desligado ao final (e de novo na próxima conexão, se necessário).")
            .setPositiveButton("Iniciar") { _, _ -> start()?.let { Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show() } }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun exportProbe(audio: Boolean) {
        val dir = app.probeFiles.latest() ?: run { Toast.makeText(requireContext(), "Nenhum teste salvo ainda", Toast.LENGTH_SHORT).show(); return }
        viewLifecycleOwner.lifecycleScope.launch {
            val intent = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.probeFiles.share(dir, audio, header() + "\n\n" + DiagnosticLog.export()) }
            startActivity(intent)
        }
    }

    private fun renderSuite(s: org.turboio.customai.probe.SuiteState, p: org.turboio.customai.probe.ProbeState) {
        val steps = org.turboio.customai.probe.TestSuite.STEPS
        val step = s.current
        suiteText.text = buildString {
            if (!s.active && s.results.isEmpty()) { append("Toque em “Começar bateria”. Deixe os óculos conectados e carregados."); return@buildString }
            if (s.active && step != null) {
                append("Etapa ${s.index + 1}/${steps.size} · ${step.id} · ${step.title}\n${step.instructions}")
                if (s.running) {
                    append("\n\n${p.phase}")
                    if (p.secondsLeft > 0) append(" · ${p.secondsLeft} s")
                    if (p.audioSeconds >= 0) append(" · áudio: ${p.audioSeconds} s")
                    if (p.trigger.isNotEmpty()) append("\n${p.trigger}")
                    if (p.pendingOff && p.phase == "inativo") append("\n⚠ LifeLog pode estar ligado")
                }
            } else if (s.active) append("✓ Bateria concluída. Toque em “Exportar todos os testes” e me envie o arquivo.")
            else append("Bateria encerrada.")
            if (s.results.isNotEmpty()) {
                append("\n\nResultados:")
                s.results.forEach { append("\n${if (it.done) "✓" else "↷"} ${it.id}: ${it.verdict.take(90)}") }
            }
        }
        cueText.text = if (s.running) step?.cue(p.audioSeconds) ?: "" else ""
        cueText.visibility = if (cueText.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        fun show(v: View, on: Boolean) { v.visibility = if (on) View.VISIBLE else View.GONE }
        show(btnBegin, !s.active)
        show(btnStep, s.active && !s.running && step != null)
        show(btnSkip, s.active && !s.running && step != null)
        show(btnStopStep, s.active && s.running && p.phase != "inativo")
        show(btnExportAll, s.results.any { it.done })
        show(btnEnd, s.active)
    }

    private fun exportAll() {
        val s = app.suite.state.value
        val dirs = app.probeFiles.all(since = s.startedAt)
        if (dirs.isEmpty()) { Toast.makeText(requireContext(), "Nenhum teste salvo desde o início da bateria", Toast.LENGTH_SHORT).show(); return }
        val summary = header() + "\n\nBateria iniciada em ${java.util.Date(s.startedAt)}\n" +
            s.results.joinToString("\n") { "${if (it.done) "✓" else "↷"} ${it.id}: ${it.verdict} ${it.savedAt}" }
        viewLifecycleOwner.lifecycleScope.launch {
            val intent = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.probeFiles.shareAll(dirs, summary, DiagnosticLog.export()) }
            startActivity(intent)
        }
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
                val r = app.askAi("Teste de diagnóstico: responda apenas com a palavra OK.", record = false)
                test.text = "✓ ${r.provider.label}/${r.model} respondeu em ${r.latencyMs} ms: \"${r.text.take(60)}\""
                test.setTextColor(statusColor(true))
            } catch (e: Exception) {
                test.text = "✗ " + TurboApp.friendly(e); test.setTextColor(statusColor(false))
            }
        }
    }
}
