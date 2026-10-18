package org.turboio.customai.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.turboio.customai.TurboApp
import org.turboio.customai.display.DisplayState
import org.turboio.customai.display.DisplayText

class TestFragment : Fragment() {
    private val app get() = TurboApp.instance
    private lateinit var question: TextInputEditText
    private lateinit var answer: TextView
    private lateinit var aiStatus: TextView
    private lateinit var lensStatus: TextView
    private lateinit var voiceStatus: TextView
    private lateinit var ask: MaterialButton
    private var lastAnswer = ""
    private lateinit var script: TextInputEditText
    private lateinit var prompterStatus: TextView
    private var prompterSpeed = 120
    private val openScript = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { loadScript(it) } }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        val ui = Column(requireContext())
        ui.header("Ferramentas", "Pergunte à IA e use o teleprompter")
        ui.section("Pergunta à IA")
        val g1 = ui.card()
        question = g1.field("Digite uma pergunta em português", multiline = true)
        ask = g1.button("Executar consulta") { runQuery() }
        aiStatus = g1.text(size = 13f)
        ui.section("Resposta")
        val g2 = ui.card()
        answer = g2.text("—")

        ui.section("Óculos")
        val g3 = ui.card()
        g3.button("Transmitir resposta aos óculos") {
            if (lastAnswer.isEmpty()) { lensStatus.text = "Execute uma consulta primeiro"; lensStatus.setTextColor(statusColor(false)); return@button }
            app.display.show(lastAnswer, app.settings.displaySettings())
        }
        g3.button("Enviar texto de teste (acentuação pt-BR)", filled = false) {
            app.display.show("Teste Turbo IO: olá! Ação, coração, café, você e pão. Se aparecerem quadrados, ative \"Remover acentos\".", app.settings.displaySettings())
        }
        g3.button("Parar exibição", filled = false) { app.display.stop() }
        lensStatus = g3.text(size = 13f)
        g3.button("Limpar contexto da conversa", filled = false) { app.clearHistory(); aiStatus.text = "Contexto limpo" }

        ui.section("Assistente de voz")
        val g4 = ui.card()
        g4.hint("Acione o assistente nos óculos (gesto/botão de IA do firmware original) e fale. O app usa o motor escolhido na aba IA.")
        voiceStatus = g4.text(size = 13f)

        ui.section("Teleprompter dos óculos")
        val g5 = ui.card()
        g5.hint("Usa o teleprompter que já vem nos óculos. O roteiro vai como arquivo temporário (até 12 000 caracteres) e " +
            "fica só no celular depois que você sai. Nada é instalado nos óculos. Ainda não funcionou nos seus óculos; a rev. 13 segue o app oficial 1.0.7.")
        script = g5.field("Roteiro (cole o texto)", multiline = true)
        g5.button("Abrir arquivo .txt", filled = false) { openScript.launch(arrayOf("text/plain")) }
        val speeds = (80..260 step 20).toList()
        g5.text("Velocidade (palavras por minuto)", size = 13f)
        g5.spinner(speeds, speeds.indexOf(prompterSpeed), { "$it" }) { prompterSpeed = it }
        var look = org.turboio.customai.device.Teleprompter.Look()
        val countdowns = listOf(0, 3, 5)
        g5.spinner(countdowns, countdowns.indexOf(look.countdown), { if (it == 0) "Começar sem contagem" else "Contagem de $it s" }) { look = look.copy(countdown = it) }
        val sizes = listOf(18 to "pequeno", 20 to "médio", 24 to "grande")
        g5.spinner(sizes, 0, { "Texto ${it.second}" }) { look = look.copy(size = it.first) }
        val levels = listOf(1 to "perto", 2 to "média", 3 to "longe")
        g5.spinner(levels, 0, { "Profundidade ${it.second}" }) { look = look.copy(depth = it.first) }
        val gears = listOf(1 to "baixo", 2 to "médio", 3 to "alto")
        g5.spinner(gears, 0, { "Nível (gear) ${it.second}" }) { look = look.copy(gear = it.first) }
        val spacings = listOf(0 to "sem espaço extra", 4 to "normal", 8 to "largo")
        g5.spinner(spacings, 1, { "Entrelinha ${it.second}" }) { look = look.copy(leading = it.first) }
        g5.button("Enviar roteiro aos óculos") { prompter { app.teleprompter.prepare(script.text.toString(), prompterSpeed, look) } }
        g5.buttons("Iniciar" to { prompter { app.teleprompter.control(3) } },
            "Pausar" to { prompter { app.teleprompter.control(4) } },
            "Continuar" to { prompter { app.teleprompter.control(5) } })
        g5.buttons("Aplicar velocidade" to { prompter { app.teleprompter.control(7, prompterSpeed) } },
            "Sair" to { prompter { app.teleprompter.stop(); null } })
        g5.hint("Os roteiros enviados (até ${org.turboio.customai.device.ScriptLibrary.MAX}) ficam salvos neste celular e aparecem no menu " +
            "Teleprompter dos óculos; escolha um lá para abrir.")
        g5.button("Apagar roteiros salvos", filled = false) {
            app.scripts.clear(); android.widget.Toast.makeText(requireContext(), "Roteiros apagados", android.widget.Toast.LENGTH_SHORT).show()
        }
        prompterStatus = g5.text(size = 13f)
        return ui.scroll()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { app.display.state.collect { renderLens(it) } }
                launch {
                    app.teleprompter.state.collect { t ->
                        prompterStatus.text = "Estado: ${t.phase.label} · ${t.note}" +
                            (if (t.progress in 1..99) "\nEnvio: ${t.progress}%" else "") + "\nVelocidade: ${t.speed}"
                        prompterStatus.setTextColor(statusColor(when (t.phase) {
                            org.turboio.customai.device.Teleprompter.Phase.ERROR -> false
                            org.turboio.customai.device.Teleprompter.Phase.READY, org.turboio.customai.device.Teleprompter.Phase.RUNNING -> true
                            else -> null
                        }))
                    }
                }
                launch {
                    app.voice.state.collect { v ->
                        voiceStatus.text = "Estado: ${v.phase} · ${v.note}" +
                            (if (v.transcript.isNotEmpty()) "\nVocê: ${v.transcript}" else "") +
                            (if (v.answer.isNotEmpty()) "\nIA: ${v.answer.take(300)}" else "") +
                            (if (v.audioPackets > 0) "\nÁudio dos óculos: ${v.audioPackets} pacotes / ${v.audioMs} ms" else "") +
                            "\nRodadas concluídas: ${v.rounds}"
                        voiceStatus.setTextColor(statusColor(if (v.lastError.isNotEmpty() && v.note == v.lastError) false else null))
                    }
                }
            }
        }
    }

    private fun renderLens(s: DisplayState) {
        val (text, ok) = when (s) {
            DisplayState.Idle -> "Exibição: inativa" to null
            is DisplayState.Opening -> "Abrindo exibição (${s.pages} página(s))…" to null
            is DisplayState.Showing -> "✓ Página ${s.page}/${s.pages} enviada: \"${s.text}\"" to true
            is DisplayState.Done -> "✓ Exibição concluída (${s.pages} página(s)). Confirme visualmente nas lentes." to true
            is DisplayState.Failed -> "✗ ${s.reason}" to false
        }
        lensStatus.text = text; lensStatus.setTextColor(statusColor(ok))
    }

    private fun prompter(action: suspend () -> String?) {
        viewLifecycleOwner.lifecycleScope.launch {
            action()?.let { prompterStatus.text = "✗ $it"; prompterStatus.setTextColor(statusColor(false)) }
        }
    }

    private fun loadScript(uri: android.net.Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    requireContext().contentResolver.openInputStream(uri)?.use { input ->
                        val limit = org.turboio.customai.protocol.FileTransfer.MAX_FILE
                        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
                        while (out.size() <= limit) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                        if (out.size() > limit) null else out.toString("UTF-8")
                    }
                }.getOrNull()
            }
            if (text == null) { prompterStatus.text = "✗ Arquivo não lido ou maior que 48 KB"; prompterStatus.setTextColor(statusColor(false)) }
            else script.setText(text)
        }
    }

    private fun runQuery() {
        val q = question.text.toString().trim()
        if (q.isEmpty()) { aiStatus.text = "Digite uma pergunta"; return }
        ask.isEnabled = false
        aiStatus.text = "Consultando ${app.settings.aiConfig().provider.label}…"; aiStatus.setTextColor(statusColor(null))
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Questions about the journal ("resumo do meu dia", "o que falei sobre X") are answered from it.
                app.journalAnswer(q, org.turboio.customai.journal.EntrySource.TEXT)?.let { fromJournal ->
                    lastAnswer = fromJournal
                    answer.text = DisplayText.clean(fromJournal, app.settings.displaySettings().stripAccents)
                    aiStatus.text = "✓ Respondido a partir do diário"; aiStatus.setTextColor(statusColor(true))
                    return@launch
                }
                val result = app.askAi(q)
                lastAnswer = result.text
                answer.text = DisplayText.clean(result.text, app.settings.displaySettings().stripAccents)
                aiStatus.text = "✓ ${result.provider.label} · ${result.model} · ${result.latencyMs} ms · ${result.text.length} caracteres" +
                    if (result.truncated) " · resposta truncada pelo limite de tokens" else ""
                aiStatus.setTextColor(statusColor(true))
            } catch (e: Exception) {
                aiStatus.text = "✗ " + TurboApp.friendly(e); aiStatus.setTextColor(statusColor(false))
            } finally { ask.isEnabled = true }
        }
    }
}
