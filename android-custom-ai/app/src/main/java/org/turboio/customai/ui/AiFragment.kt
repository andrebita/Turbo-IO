package org.turboio.customai.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.textfield.TextInputEditText
import org.turboio.customai.TurboApp
import org.turboio.customai.ai.AiConfig
import org.turboio.customai.ai.DEFAULT_SYSTEM_PROMPT
import org.turboio.customai.ai.ProviderCodec
import org.turboio.customai.ai.ProviderKind
import org.turboio.customai.diag.DiagnosticLog
import org.turboio.customai.diag.Redactor
import org.turboio.customai.stt.SttEngine

class AiFragment : Fragment() {
    private val settings get() = TurboApp.instance.settings
    private var provider = ProviderKind.OPENAI
    private var engine = SttEngine.PHONE_ANDROID
    private lateinit var model: TextInputEditText
    private lateinit var endpoint: TextInputEditText
    private lateinit var key: TextInputEditText
    private lateinit var keyState: TextView
    private lateinit var models: TextView
    private lateinit var prompt: TextInputEditText
    private lateinit var tokens: TextInputEditText
    private lateinit var timeout: TextInputEditText
    private lateinit var history: TextInputEditText
    private var webSearch = true
    private lateinit var sttKey: TextInputEditText
    private lateinit var sttKeyState: TextView
    private lateinit var sttModel: TextInputEditText
    private lateinit var deepgramKey: TextInputEditText
    private lateinit var ambientModel: TextInputEditText
    private lateinit var deepgramState: TextView
    private var ambientProvider = org.turboio.customai.ambient.AmbientProvider.DEEPGRAM

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        val config = settings.aiConfig()
        provider = config.provider
        val ui = Column(requireContext())
        ui.header("IA", "Provedor, voz e texto nas lentes")

        ui.section("Provedor de IA")
        val g1 = ui.card()
        g1.spinner(ProviderKind.entries.toList(), ProviderKind.entries.indexOf(provider), { it.label }) { selected ->
            if (selected != provider) { provider = selected; loadProvider() }
        }
        model = g1.field("Modelo")
        models = g1.hint("")
        endpoint = g1.field("Endpoint (HTTPS)")
        key = g1.field("Chave de API (deixe vazio para manter a salva)", password = true)
        keyState = g1.hint("")
        g1.hint("A chave é cifrada com o Android Keystore (AES-GCM), nunca é exibida, registrada em log, incluída em backup ou no APK.")

        ui.section("Comportamento")
        val g2 = ui.card()
        prompt = g2.field("Instruções de sistema", config.systemPrompt, multiline = true)
        g2.button("Restaurar instruções padrão (pt-BR, respostas curtas)", filled = false) { prompt.setText(DEFAULT_SYSTEM_PROMPT) }
        tokens = g2.field("Limite de tokens da resposta", config.maxTokens.toString(), number = true)
        timeout = g2.field("Tempo máximo de resposta (segundos)", config.timeoutSeconds.toString(), number = true)
        history = g2.field("Rodadas de contexto mantidas (0–10)", config.historyTurns.toString(), number = true)
        webSearch = config.webSearch
        g2.switch("Buscar na internet quando precisar (tempo, notícias, preços)", webSearch) { webSearch = it }
        g2.hint("Gemini usa a Pesquisa Google; Claude usa a busca na web da Anthropic (ative-a no console da Anthropic). A busca só acontece " +
            "quando a pergunta pede, e o provedor pode cobrar por consulta. OpenAI: use um modelo com busca (ex.: gpt-4o-search-preview). " +
            "Data, hora e a cidade do tempo (aba Óculos) vão sempre junto da pergunta.")

        ui.section("Reconhecimento de voz (pt-BR)")
        val g3 = ui.card()
        val stt = settings.sttConfig(); engine = stt.engine
        g3.spinner(SttEngine.entries.toList(), SttEngine.entries.indexOf(engine), { it.label }) { engine = it; loadStt() }
        sttModel = g3.field("Modelo de transcrição OpenAI", stt.openAiModel)
        sttKey = g3.field("Chave da API de transcrição (vazio = manter)", password = true)
        sttKeyState = g3.hint("")
        g3.hint("Microfone dos óculos: o áudio Opus é recebido pelo canal oficial do firmware original e enviado ao serviço escolhido. " +
            "Android nativo: usa o microfone do telefone; nenhuma chave é necessária.")
        val voice = settings.voiceSettings()
        g3.switch("Assistente de voz ativo (ao acionar nos óculos)", voice.enabled) { settings.setVoiceEnabled(it) }
        g3.switch("Continuar a conversa depois da resposta (os óculos voltam a ouvir)", voice.followUp) { settings.setVoiceFollowUp(it) }
        g3.switch("Remover acentos no display (use se aparecerem quadrados)", voice.stripAccents) { settings.setStripAccents(it) }
        val formats = listOf("legacy" to "Formato antigo, repetido (padrão)", "official" to "Formato do app oficial", "bilingual" to "Oficial com linha de tradução",
            "caption" to "Legenda do app oficial 1.0.7 (título)", "classic" to "Legenda do app oficial 1.0.7 (clássica)",
            "session_classic" to "Sessão de legendas oficial (clássica)", "session_title" to "Sessão de legendas oficial (título)")
        g3.text("Formato do texto nas lentes (a etapa F1 da bateria mostra os três)", size = 13f)
        g3.spinner(formats, formats.indexOfFirst { it.first == settings.displaySettings().textFormat }.coerceAtLeast(0), { it.second }) { settings.setTextFormat(it.first) }
        g3.text("Texto nas lentes (experimental; se os óculos recusarem, volta ao padrão)", size = 13f)
        val d = settings.displaySettings()
        var fontSize = d.fontSize; var position = d.position; var lines = d.maxLines
        val sizes = listOf(1 to "Pequeno", 2 to "Médio (padrão)", 3 to "Grande")
        g3.spinner(sizes, sizes.indexOfFirst { it.first == fontSize }, { "Tamanho: ${it.second}" }) { fontSize = it.first; settings.setDisplayLayout(fontSize, position, lines) }
        val positions = listOf("center" to "Centro (padrão)", "top" to "Topo", "bottom" to "Base")
        g3.spinner(positions, positions.indexOfFirst { it.first == position }, { "Posição: ${it.second}" }) { position = it.first; settings.setDisplayLayout(fontSize, position, lines) }
        g3.spinner(listOf(3, 4, 5), listOf(3, 4, 5).indexOf(lines).coerceAtLeast(0), { "Linhas: $it${if (it == 5) " (padrão)" else ""}" }) { lines = it; settings.setDisplayLayout(fontSize, position, lines) }

        ui.section("Transcrição do ambiente (gravação contínua)")
        val g4 = ui.card()
        val amb = settings.ambientSettings(); ambientProvider = amb.provider
        g4.spinner(org.turboio.customai.ambient.AmbientProvider.entries.toList(), org.turboio.customai.ambient.AmbientProvider.entries.indexOf(ambientProvider), { it.label }) { ambientProvider = it; loadAmbient() }
        deepgramKey = g4.field("Chave da Deepgram (vazio = manter)", password = true)
        deepgramState = g4.hint("")
        ambientModel = g4.field("Modelo OpenAI para o ambiente", amb.openAiModel)
        g4.hint("Deepgram Nova-3 pt-BR separa os falantes (até ~5 min de fala por envio). OpenAI usa a chave de transcrição/IA da OpenAI e não separa falantes.")

        g4.button("Salvar configuração") { save() }
        loadProvider(); loadStt()
        return ui.scroll()
    }

    private fun loadProvider() {
        model.setText(settings.modelFor(provider))
        endpoint.setText(settings.endpointFor(provider).ifEmpty { provider.defaultEndpoint })
        endpoint.isEnabled = provider.endpointEditable
        models.text = "Sugestões: " + provider.suggestedModels.joinToString(", ") + if (provider.endpointEditable) "\nEx.: https://api.deepseek.com/chat/completions" else ""
        key.setText("")
        keyState.text = if (settings.hasApiKey(provider)) "Chave salva: ${Redactor.mask(settings.apiKey(provider))}" else "Nenhuma chave salva para ${provider.label}"
    }

    /** Only the fields of the chosen services are shown. */
    private fun loadAmbient() {
        if (!::ambientModel.isInitialized) return
        val deepgram = ambientProvider == org.turboio.customai.ambient.AmbientProvider.DEEPGRAM || engine == SttEngine.GLASSES_DEEPGRAM
        deepgramKey.showField(deepgram)
        deepgramState.visibility = if (deepgram) View.VISIBLE else View.GONE
        deepgramState.text = if (settings.hasDeepgramKey()) "Chave Deepgram salva: ${Redactor.mask(settings.deepgramKey())}" else "Nenhuma chave Deepgram salva (console.deepgram.com → API Keys)"
        ambientModel.showField(ambientProvider == org.turboio.customai.ambient.AmbientProvider.OPENAI)
    }

    private fun loadStt() {
        sttModel.showField(engine == SttEngine.GLASSES_OPENAI)
        sttKey.showField(engine == SttEngine.GLASSES_OPENAI || engine == SttEngine.GLASSES_GOOGLE)
        sttKey.setText("")
        loadAmbient()
        sttKeyState.text = when {
            engine == SttEngine.PHONE_ANDROID -> "Sem chave necessária"
            engine == SttEngine.GLASSES_GEMINI -> if (settings.hasApiKey(ProviderKind.GEMINI)) "Usa a chave do Gemini salva no provedor de IA (modelo ${settings.modelFor(ProviderKind.GEMINI)})" else "Salve a chave do Gemini no provedor de IA (acima)"
            engine == SttEngine.GLASSES_DEEPGRAM -> if (settings.hasDeepgramKey()) "Usa a chave Deepgram da transcrição do ambiente (abaixo)" else "Informe a chave Deepgram abaixo, em “Transcrição do ambiente”"
            settings.hasSttKey(engine) -> "Chave salva: ${Redactor.mask(settings.sttKey(engine))}"
            engine == SttEngine.GLASSES_OPENAI && settings.hasApiKey(ProviderKind.OPENAI) -> "Usará a chave OpenAI do provedor de IA"
            else -> "Nenhuma chave salva"
        }
    }

    private fun save() {
        val config = AiConfig(
            provider = provider,
            model = model.text.toString().trim(),
            endpoint = if (provider.endpointEditable) endpoint.text.toString().trim() else provider.defaultEndpoint,
            systemPrompt = prompt.text.toString(),
            maxTokens = tokens.text.toString().toIntOrNull() ?: 800,
            timeoutSeconds = timeout.text.toString().toIntOrNull() ?: 30,
            historyTurns = (history.text.toString().toIntOrNull() ?: 3).coerceIn(0, 10),
            webSearch = webSearch,
        )
        val newKey = key.text.toString().trim()
        val effectiveKey = newKey.ifEmpty { settings.apiKey(provider) }
        val error = ProviderCodec.validate(config, effectiveKey.ifEmpty { "placeholder-for-validation" })
        if (error != null) { Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show(); return }
        settings.saveAi(config)
        if (newKey.isNotEmpty()) settings.setApiKey(provider, newKey)
        settings.saveStt(settings.sttConfig().copy(engine = engine, openAiModel = sttModel.text.toString().trim().ifEmpty { "whisper-1" }))
        settings.saveAmbient(settings.ambientSettings().copy(provider = ambientProvider,
            openAiModel = ambientModel.text.toString().trim().ifEmpty { "gpt-4o-mini-transcribe" }))
        deepgramKey.text.toString().trim().takeIf { it.isNotEmpty() }?.let { settings.setDeepgramKey(it); deepgramKey.setText("") }
        val newStt = sttKey.text.toString().trim()
        if (newStt.isNotEmpty() && (engine == SttEngine.GLASSES_OPENAI || engine == SttEngine.GLASSES_GOOGLE)) settings.setSttKey(engine, newStt)
        DiagnosticLog.i("CFG", "configuração salva: provedor=${provider.id} modelo=${config.model} stt=${engine.id} chave=${if (effectiveKey.isEmpty()) "ausente" else "presente"}")
        TurboApp.instance.clearHistory()
        loadProvider(); loadStt()
        Toast.makeText(requireContext(), if (effectiveKey.isEmpty()) "Salvo. Falta informar a chave de API." else "Configuração salva", Toast.LENGTH_SHORT).show()
    }
}
