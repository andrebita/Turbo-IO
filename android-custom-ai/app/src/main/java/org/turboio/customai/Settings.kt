package org.turboio.customai

import android.content.Context
import org.turboio.customai.ai.AiConfig
import org.turboio.customai.ai.DEFAULT_SYSTEM_PROMPT
import org.turboio.customai.ai.ProviderKind
import org.turboio.customai.display.DisplaySettings
import org.turboio.customai.security.SecretStore
import org.turboio.customai.stt.SttConfig
import org.turboio.customai.stt.SttEngine
import org.turboio.customai.voice.VoiceSettings

/**
 * Non-secret preferences in SharedPreferences; API keys only through [SecretStore] (Keystore AES-GCM).
 * Model/endpoint are stored per provider so switching providers does not lose configuration.
 */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("turboio_settings_v1", Context.MODE_PRIVATE)
    val secrets = SecretStore(context)

    fun aiConfig(): AiConfig {
        val provider = ProviderKind.fromId(prefs.getString("ai.provider", ProviderKind.OPENAI.id))
        return AiConfig(
            provider = provider,
            model = prefs.getString("ai.${provider.id}.model", null) ?: provider.defaultModel,
            endpoint = prefs.getString("ai.${provider.id}.endpoint", null) ?: provider.defaultEndpoint,
            systemPrompt = prefs.getString("ai.systemPrompt", null) ?: DEFAULT_SYSTEM_PROMPT,
            maxTokens = prefs.getInt("ai.maxTokens", 800),
            timeoutSeconds = prefs.getInt("ai.timeout", 30),
            historyTurns = prefs.getInt("ai.history", 3),
        )
    }

    fun modelFor(provider: ProviderKind): String = prefs.getString("ai.${provider.id}.model", null) ?: provider.defaultModel
    fun endpointFor(provider: ProviderKind): String = prefs.getString("ai.${provider.id}.endpoint", null) ?: provider.defaultEndpoint

    fun saveAi(config: AiConfig) {
        prefs.edit()
            .putString("ai.provider", config.provider.id)
            .putString("ai.${config.provider.id}.model", config.model.trim())
            .putString("ai.${config.provider.id}.endpoint", config.endpoint.trim())
            .putString("ai.systemPrompt", config.systemPrompt)
            .putInt("ai.maxTokens", config.maxTokens)
            .putInt("ai.timeout", config.timeoutSeconds)
            .putInt("ai.history", config.historyTurns)
            .apply()
    }

    fun apiKey(provider: ProviderKind): String = secrets.get("key.ai.${provider.id}")
    fun hasApiKey(provider: ProviderKind): Boolean = secrets.has("key.ai.${provider.id}")
    fun setApiKey(provider: ProviderKind, key: String) = secrets.put("key.ai.${provider.id}", key.trim())

    fun sttConfig(): SttConfig = SttConfig(
        engine = SttEngine.fromId(prefs.getString("stt.engine", SttEngine.PHONE_ANDROID.id)),
        language = prefs.getString("stt.language", "pt-BR") ?: "pt-BR",
        openAiModel = prefs.getString("stt.openai.model", "whisper-1") ?: "whisper-1",
        openAiEndpoint = prefs.getString("stt.openai.endpoint", "https://api.openai.com/v1/audio/transcriptions")
            ?: "https://api.openai.com/v1/audio/transcriptions",
    )

    fun saveStt(config: SttConfig) {
        prefs.edit().putString("stt.engine", config.engine.id).putString("stt.language", config.language)
            .putString("stt.openai.model", config.openAiModel).putString("stt.openai.endpoint", config.openAiEndpoint).apply()
    }

    /** STT key; for the OpenAI engine an empty value falls back to the OpenAI chat key. */
    fun sttKey(engine: SttEngine): String {
        val own = secrets.get("key.stt.${engine.id}")
        if (own.isNotEmpty()) return own
        return if (engine == SttEngine.GLASSES_OPENAI) apiKey(ProviderKind.OPENAI) else ""
    }
    fun hasSttKey(engine: SttEngine): Boolean = secrets.has("key.stt.${engine.id}")
    fun setSttKey(engine: SttEngine, key: String) = secrets.put("key.stt.${engine.id}", key.trim())

    fun voiceSettings(): VoiceSettings = VoiceSettings(
        enabled = prefs.getBoolean("voice.enabled", true),
        stripAccents = prefs.getBoolean("display.stripAccents", false),
        silenceMs = prefs.getInt("voice.silenceMs", 1200),
        maxSeconds = prefs.getInt("voice.maxSeconds", 15),
        displaySeconds = prefs.getInt("voice.displaySeconds", 12),
    )

    fun displaySettings(): DisplaySettings = DisplaySettings(
        pageSeconds = prefs.getInt("display.pageSeconds", 5),
        lingerSeconds = prefs.getInt("display.lingerSeconds", 6),
        stripAccents = prefs.getBoolean("display.stripAccents", false),
    )

    fun setVoiceEnabled(value: Boolean) = prefs.edit().putBoolean("voice.enabled", value).apply()
    fun setStripAccents(value: Boolean) = prefs.edit().putBoolean("display.stripAccents", value).apply()
    fun setPageSeconds(value: Int) = prefs.edit().putInt("display.pageSeconds", value.coerceIn(2, 20)).apply()
    var keepConnected: Boolean
        get() = prefs.getBoolean("ble.keep", true)
        set(value) { prefs.edit().putBoolean("ble.keep", value).apply() }

    fun journalSettings(): JournalSettings = JournalSettings(
        recordInteractions = prefs.getBoolean("journal.record", true),
        autoDaily = prefs.getBoolean("journal.autoDaily", true),
        dailyHour = prefs.getInt("journal.hour", 21).coerceIn(0, 23),
        dailyMinute = prefs.getInt("journal.minute", 0).coerceIn(0, 59),
        lensDaily = prefs.getBoolean("journal.lens", false),
        retentionDays = prefs.getInt("journal.retention", 0).coerceIn(0, 3650),
        insightsModel = prefs.getString("journal.model", "") ?: "",
    )

    fun saveJournal(s: JournalSettings) {
        prefs.edit().putBoolean("journal.record", s.recordInteractions).putBoolean("journal.autoDaily", s.autoDaily)
            .putInt("journal.hour", s.dailyHour).putInt("journal.minute", s.dailyMinute).putBoolean("journal.lens", s.lensDaily)
            .putInt("journal.retention", s.retentionDays).putString("journal.model", s.insightsModel.trim()).apply()
    }

    /** Removes every stored key and preference (used by "Apagar dados" in diagnostics). */
    fun wipe() { secrets.clearAll(); prefs.edit().clear().apply() }
}

data class JournalSettings(
    val recordInteractions: Boolean = true,
    val autoDaily: Boolean = true,
    val dailyHour: Int = 21,
    val dailyMinute: Int = 0,
    val lensDaily: Boolean = false,
    val retentionDays: Int = 0,      // 0 = keep everything
    val insightsModel: String = "",  // empty = same model as the AI tab
)
