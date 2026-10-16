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
import kotlinx.coroutines.launch
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        val ui = Column(requireContext())
        ui.title("Pergunta à IA")
        question = ui.field("Digite uma pergunta em português", multiline = true)
        ask = ui.button("Executar consulta") { runQuery() }
        aiStatus = ui.text(size = 13f)
        ui.title("Resposta")
        answer = ui.text("—")

        ui.title("Óculos")
        ui.button("Transmitir resposta aos óculos") {
            if (lastAnswer.isEmpty()) { lensStatus.text = "Execute uma consulta primeiro"; lensStatus.setTextColor(statusColor(false)); return@button }
            app.display.show(lastAnswer, app.settings.displaySettings())
        }
        ui.button("Enviar texto de teste (acentuação pt-BR)", filled = false) {
            app.display.show("Teste Turbo IO: olá! Ação, coração, café, você e pão. Se aparecerem quadrados, ative \"Remover acentos\".", app.settings.displaySettings())
        }
        ui.button("Parar exibição", filled = false) { app.display.stop() }
        lensStatus = ui.text(size = 13f)
        ui.button("Limpar contexto da conversa", filled = false) { app.clearHistory(); aiStatus.text = "Contexto limpo" }

        ui.title("Assistente de voz")
        ui.hint("Acione o assistente nos óculos (gesto/botão de IA do firmware original) e fale. O app usa o motor escolhido na aba IA.")
        voiceStatus = ui.text(size = 13f)
        return ui.scroll()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { app.display.state.collect { renderLens(it) } }
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

    private fun runQuery() {
        val q = question.text.toString().trim()
        if (q.isEmpty()) { aiStatus.text = "Digite uma pergunta"; return }
        ask.isEnabled = false
        aiStatus.text = "Consultando ${app.settings.aiConfig().provider.label}…"; aiStatus.setTextColor(statusColor(null))
        viewLifecycleOwner.lifecycleScope.launch {
            try {
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
