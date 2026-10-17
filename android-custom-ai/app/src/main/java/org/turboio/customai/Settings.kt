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
        fontSize = prefs.getInt("display.fontSize", 2).coerceIn(1, 3),
        position = prefs.getString("display.position", "center")?.takeIf { it in setOf("center", "top", "bottom") } ?: "center",
        maxLines = prefs.getInt("display.maxLines", 5).coerceIn(1, 5),
    )

    fun setDisplayLayout(fontSize: Int, position: String, maxLines: Int) =
        prefs.edit().putInt("display.fontSize", fontSize).putString("display.position", position).putInt("display.maxLines", maxLines).apply()

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

    fun ambientSettings(): AmbientSettings = AmbientSettings(
        provider = org.turboio.customai.ambient.AmbientProvider.of(prefs.getString("ambient.provider", "deepgram")),
        openAiModel = prefs.getString("ambient.openai.model", "gpt-4o-mini-transcribe") ?: "gpt-4o-mini-transcribe",
        deleteRaw = prefs.getBoolean("ambient.deleteRaw", true),
        wifiOnly = prefs.getBoolean("ambient.wifiOnly", false),
        consent = prefs.getBoolean("ambient.consent", false),
        glassesMenu = prefs.getBoolean("ambient.glassesMenu", false),
    )

    fun saveAmbient(s: AmbientSettings) {
        prefs.edit().putString("ambient.provider", s.provider.id).putString("ambient.openai.model", s.openAiModel.trim())
            .putBoolean("ambient.deleteRaw", s.deleteRaw).putBoolean("ambient.wifiOnly", s.wifiOnly).putBoolean("ambient.consent", s.consent)
            .putBoolean("ambient.glassesMenu", s.glassesMenu).apply()
    }

    fun memorySettings(): org.turboio.customai.memory.MemorySettings {
        val policies = runCatching {
            val o = org.json.JSONObject(prefs.getString("memory.policies", "{\"ruido\":\"ignorar\"}") ?: "{}")
            o.keys().asSequence().associateWith { org.turboio.customai.memory.CategoryPolicy.of(o.getString(it)) }
        }.getOrDefault(mapOf("ruido" to org.turboio.customai.memory.CategoryPolicy.IGNORE))
        return org.turboio.customai.memory.MemorySettings(
            enabled = prefs.getBoolean("memory.enabled", true),
            notifyNew = prefs.getBoolean("memory.notifyNew", true),
            lensNew = prefs.getBoolean("memory.lensNew", false),
            reminders = prefs.getBoolean("memory.reminders", true),
            reminderLeadMin = prefs.getInt("memory.lead", 15).coerceIn(0, 240),
            morning = prefs.getBoolean("memory.morning", true),
            morningHour = prefs.getInt("memory.morningHour", 8).coerceIn(0, 23),
            morningMinute = prefs.getInt("memory.morningMinute", 0).coerceIn(0, 59),
            weekly = prefs.getBoolean("memory.weekly", true),
            weeklyDow = prefs.getInt("memory.weeklyDow", 7).coerceIn(1, 7),
            weeklyHour = prefs.getInt("memory.weeklyHour", 19).coerceIn(0, 23),
            policies = policies,
            glassesTodo = prefs.getBoolean("memory.glassesTodo", false),
            nodConfirm = prefs.getBoolean("memory.nodConfirm", false),
        )
    }

    fun saveMemory(s: org.turboio.customai.memory.MemorySettings) {
        val policies = org.json.JSONObject().apply { s.policies.forEach { (k, v) -> put(k, v.id) } }
        prefs.edit().putBoolean("memory.enabled", s.enabled).putBoolean("memory.notifyNew", s.notifyNew).putBoolean("memory.lensNew", s.lensNew)
            .putBoolean("memory.reminders", s.reminders).putInt("memory.lead", s.reminderLeadMin).putBoolean("memory.morning", s.morning)
            .putInt("memory.morningHour", s.morningHour).putInt("memory.morningMinute", s.morningMinute).putBoolean("memory.weekly", s.weekly)
            .putInt("memory.weeklyDow", s.weeklyDow).putInt("memory.weeklyHour", s.weeklyHour).putString("memory.policies", policies.toString())
            .putBoolean("memory.glassesTodo", s.glassesTodo).putBoolean("memory.nodConfirm", s.nodConfirm).apply()
    }

    /** First display/crown values read from the glasses (restore point). */
    val originalGlassesSettings = object : org.turboio.customai.device.OriginalSettings {
        override fun load(): org.turboio.customai.device.GlassesConfig? {
            if (!prefs.contains("glasses.orig.at")) return null
            fun i(k: String) = if (prefs.contains(k)) prefs.getInt(k, 0) else null
            return org.turboio.customai.device.GlassesConfig(i("glasses.orig.height"), i("glasses.orig.distance"), i("glasses.orig.double"), i("glasses.orig.long"), prefs.getLong("glasses.orig.at", 0))
        }
        override fun save(c: org.turboio.customai.device.GlassesConfig) {
            val e = prefs.edit().putLong("glasses.orig.at", c.readAt)
            fun put(k: String, v: Int?) { if (v != null) e.putInt(k, v) else e.remove(k) }
            put("glasses.orig.height", c.height); put("glasses.orig.distance", c.distance); put("glasses.orig.double", c.crownDouble); put("glasses.orig.long", c.crownLongPress)
            e.apply()
        }
    }

    /** Phone notifications of the chosen apps → glasses; Google Maps navigation → lens text. */
    var mirrorOn: Boolean
        get() = prefs.getBoolean("mirror.on", false)
        set(v) { prefs.edit().putBoolean("mirror.on", v).apply() }
    var navOn: Boolean
        get() = prefs.getBoolean("mirror.nav", false)
        set(v) { prefs.edit().putBoolean("mirror.nav", v).apply() }
    var mirrorApps: Set<String>
        get() = prefs.getStringSet("mirror.apps", emptySet())?.toSet() ?: emptySet()
        set(v) { prefs.edit().putStringSet("mirror.apps", v.toSet()).apply() }
    /** Package names (only) of apps that posted notifications recently, for the choice list. */
    val notifyingApps: Set<String> get() = prefs.getStringSet("mirror.seen", emptySet())?.toSet() ?: emptySet()
    fun rememberNotifyingApp(pkg: String) {
        val seen = notifyingApps
        if (pkg in seen || seen.size >= 60) return
        prefs.edit().putStringSet("mirror.seen", seen + pkg).apply()
    }

    var weatherOn: Boolean
        get() = prefs.getBoolean("weather.on", false)
        set(v) { prefs.edit().putBoolean("weather.on", v).apply() }
    var weatherCity: String
        get() = prefs.getString("weather.city", "") ?: ""
        set(v) { prefs.edit().putString("weather.city", v.trim().take(60)).apply() }

    /** Short pending-items card on the glasses' dashboard, refreshed when items change. */
    var dashboardCard: Boolean
        get() = prefs.getBoolean("dashboard.card", false)
        set(v) { prefs.edit().putBoolean("dashboard.card", v).apply() }
    /** Dashboard type (simple_mode) read before the app first changed it: restore point. */
    var dashboardSimpleOriginal: Boolean?
        get() = if (prefs.contains("dashboard.simpleOriginal")) prefs.getBoolean("dashboard.simpleOriginal", false) else null
        set(v) { prefs.edit().apply { if (v == null) remove("dashboard.simpleOriginal") else putBoolean("dashboard.simpleOriginal", v) }.apply() }

    /** Alerts (reminders, new items, morning brief) as the glasses' native notification card. */
    var nativeAlerts: Boolean
        get() = prefs.getBoolean("alerts.native", true)
        set(v) { prefs.edit().putBoolean("alerts.native", v).apply() }

    fun deepgramKey(): String = secrets.get("key.ambient.deepgram")
    fun hasDeepgramKey(): Boolean = secrets.has("key.ambient.deepgram")
    fun setDeepgramKey(key: String) = secrets.put("key.ambient.deepgram", key.trim())
    /** OpenAI ambient transcription reuses the OpenAI STT key, then the OpenAI chat key. */
    fun ambientOpenAiKey(): String = sttKey(SttEngine.GLASSES_OPENAI)

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

data class AmbientSettings(
    val provider: org.turboio.customai.ambient.AmbientProvider = org.turboio.customai.ambient.AmbientProvider.DEEPGRAM,
    val openAiModel: String = "gpt-4o-mini-transcribe",
    val deleteRaw: Boolean = true,      // approved: delete raw audio after transcription
    val wifiOnly: Boolean = false,      // approved: mobile data allowed
    val consent: Boolean = false,
    val glassesMenu: Boolean = false,   // the glasses menu starts/stops journal recording (LifeLog guide flag on)
)
