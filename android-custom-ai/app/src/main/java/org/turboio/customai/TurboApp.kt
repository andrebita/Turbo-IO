package org.turboio.customai

import android.app.Application
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.turboio.customai.memory.TaskMarker
import kotlinx.coroutines.suspendCancellableCoroutine
import org.turboio.customai.ai.AiAnswer
import org.turboio.customai.ai.AiClient
import org.turboio.customai.ai.AiException
import org.turboio.customai.ai.ChatMessage
import org.turboio.customai.ai.ProviderKind
import org.turboio.customai.ble.AndroidBondManager
import org.turboio.customai.ble.DeviceInfo
import org.turboio.customai.ble.GattLink
import org.turboio.customai.ble.GlassesSession
import org.turboio.customai.ble.HandlerScheduler
import org.turboio.customai.ble.Phase
import org.turboio.customai.ble.PhoneIdentity
import org.turboio.customai.ble.PrefsPeerStore
import org.turboio.customai.ble.SecureRandomness
import org.turboio.customai.ble.SessionStatus
import org.turboio.customai.diag.DiagnosticLog
import org.turboio.customai.display.DisplayText
import org.turboio.customai.display.LensDisplay
import org.turboio.customai.insights.InsightEngine
import org.turboio.customai.insights.JournalIntent
import org.turboio.customai.insights.JournalIntents
import org.turboio.customai.journal.AndroidSqlDb
import org.turboio.customai.journal.Days
import org.turboio.customai.journal.Entry
import org.turboio.customai.journal.EntryKind
import org.turboio.customai.journal.EntrySource
import org.turboio.customai.journal.JournalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.turboio.customai.service.ConnectionService
import org.turboio.customai.stt.GoogleSpeechTranscriber
import org.turboio.customai.stt.OpenAiTranscriber
import org.turboio.customai.stt.PhoneRecognizer
import org.turboio.customai.stt.SttEngine
import org.turboio.customai.stt.SttException
import org.turboio.customai.voice.SpeechInput
import org.turboio.customai.voice.VoiceAssistant
import kotlin.coroutines.resume

/** Application-wide object graph. Everything that touches the session runs on one HandlerThread. */
class TurboApp : Application() {
    lateinit var settings: SettingsRepository; private set
    lateinit var session: GlassesSession; private set
    lateinit var display: LensDisplay; private set
    lateinit var voice: VoiceAssistant; private set
    lateinit var probe: org.turboio.customai.probe.HardwareProbe; private set
    lateinit var suite: org.turboio.customai.probe.TestSuite; private set
    lateinit var glassesSettings: org.turboio.customai.device.GlassesSettings; private set
    lateinit var glassesTodo: org.turboio.customai.memory.GlassesTodoSync; private set
    lateinit var nativeNotifier: org.turboio.customai.device.NativeNotifier; private set
    lateinit var weather: org.turboio.customai.device.GlassesWeather; private set
    lateinit var teleprompter: org.turboio.customai.device.Teleprompter; private set
    lateinit var dashboard: org.turboio.customai.device.Dashboard; private set
    /** Apps opened on the glasses (15/24), for the feature test. */
    val openedOnGlasses = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Delivered mirrored notifications / navigation lines since start (feature test). */
    @Volatile var mirroredCards = 0
    @Volatile var navLines = 0
    val trafficRecorder = org.turboio.customai.probe.TrafficRecorder()
    lateinit var features: org.turboio.customai.probe.FeatureSuite; private set
    lateinit var suggestions: org.turboio.customai.device.SuggestionCards; private set
    val mirrorPolicy by lazy { org.turboio.customai.device.MirrorPolicy(packageName, { mirrorAppsInEffect() }, { settings.navOn }) }

    /** Until the user saves a choice, the suggested apps (messages, e-mail, banks that are installed) are used. */
    fun mirrorAppsInEffect(): Set<String> {
        if (settings.mirrorAppsChosen) return settings.mirrorApps
        val M = org.turboio.customai.device.MirrorPolicy
        return M.defaults(M.choices({ pkg -> runCatching { packageManager.getApplicationInfo(pkg, 0); true }.getOrDefault(false) }, emptySet(), emptySet()))
    }
    val probeFiles by lazy { org.turboio.customai.probe.ProbeFiles(this) }
    lateinit var scope: CoroutineScope; private set
    lateinit var random: org.turboio.customai.ble.Randomness; private set
    val ai = AiClient()
    private val history = ArrayList<ChatMessage>()
    private val _status = MutableStateFlow(SessionStatus())
    val status: StateFlow<SessionStatus> = _status

    override fun onCreate() {
        super.onCreate()
        instance = this
        DiagnosticLog.sink = { level, tag, message ->
            when (level) {
                DiagnosticLog.Level.ERROR -> Log.e("TurboIO/$tag", message)
                DiagnosticLog.Level.WARN -> Log.w("TurboIO/$tag", message)
                else -> Log.i("TurboIO/$tag", message)
            }
        }
        settings = SettingsRepository(this)
        val scheduler = HandlerScheduler("turboio-glasses")
        scope = CoroutineScope(SupervisorJob() + scheduler.handler.asCoroutineDispatcher())
        random = SecureRandomness()
        val identity = PhoneIdentity(this)
        session = GlassesSession(
            scheduler = scheduler,
            linkFactory = { GattLink(this, scheduler) },
            bond = AndroidBondManager(this),
            random = random,
            identity = { identity.get() },
            device = DeviceInfo("Turbo IO", Build.MANUFACTURER ?: "Android", Build.MODEL ?: "Android"),
            store = PrefsPeerStore(this),
        )
        display = LensDisplay(session, scope, random)
        probe = org.turboio.customai.probe.HardwareProbe(session, scope, org.turboio.customai.probe.PrefsPendingFlag(this), random, probeFiles)
        suite = org.turboio.customai.probe.TestSuite(probe, org.turboio.customai.probe.PrefsSuiteStore(this))
        features = org.turboio.customai.probe.FeatureSuite(org.turboio.customai.probe.FeatureSteps.all(this), scope,
            org.turboio.customai.probe.PrefsSuiteStore(this, "features"), trafficRecorder, probeFiles::saveFeature)
        glassesSettings = org.turboio.customai.device.GlassesSettings(session, scope, settings.originalGlassesSettings, learned = settings.learnedDisplay)
        nativeNotifier = org.turboio.customai.device.NativeNotifier(session, scope, random)
        weather = org.turboio.customai.device.GlassesWeather(session)
        dashboard = org.turboio.customai.device.Dashboard(session, random)
        suggestions = org.turboio.customai.device.SuggestionCards(session, random)
        nativeNotifier.suggestionListener = suggestions::onAnswer
        teleprompter = org.turboio.customai.device.Teleprompter(session, scope, random, busy = {
            when {
                voice.busy -> "O assistente de voz está em uso"
                display.active -> "Há texto do app nas lentes; aguarde terminar"
                probe.busy -> "Um teste de hardware está em andamento"
                else -> null
            }
        })
        session.addRawListener { b, p -> trafficRecorder.inbound(b, p) }
        session.outboundTap = { b, p -> trafficRecorder.outbound(b, p) }
        // Business 18 (file transfer) is open only while a teleprompter session is active.
        session.fileLease = { teleprompter.active }
        // What the user opens on the glasses (15/24 glass_preview): opening the native To-do refreshes it right away.
        session.addBusinessListener { business, envelope ->
            if (business != org.turboio.customai.protocol.Business.LAUNCHER || envelope.type != 24) return@addBusinessListener
            val data = runCatching { org.json.JSONObject(org.json.JSONObject(envelope.jsonText()).getJSONObject("payload").getString("data")) }.getOrNull()
                ?: return@addBusinessListener
            val opened = data.optJSONObject("app")?.takeIf { it.optInt("action") != 2 }?.optString("name").orEmpty()
            if (opened.isNotEmpty()) DiagnosticLog.i("OCULOS", "aberto nos óculos: $opened")
            if (opened.isNotEmpty()) openedOnGlasses.tryEmit(opened)
            if (opened == "com.rayneo.liteos.todo") memoryChanged()
        }
        glassesTodo = org.turboio.customai.memory.GlassesTodoSync(session, scope, { memoryStore }, random,
            { settings.memorySettings().let { it.enabled && it.glassesTodo } })
        // The glasses LifeLog channel is shared: probes wait while the journal records from the glasses, and a
        // probe's OFF sequence clears the "start from the glasses menu" flag, so it is re-armed afterwards.
        probe.blocker = { if (ambient.usesGlasses) "A gravação do diário pelos óculos está ativa" else null }
        probe.addFinishedListener { if (settings.ambientSettings().glassesMenu) ambient.armGlassesMenu() }
        session.addBusinessListener { business, envelope ->
            if (business == org.turboio.customai.protocol.Business.LAUNCHER || business == org.turboio.customai.protocol.Business.AI_ASSISTANT) ambient.onGlasses(business, envelope)
        }
        voice = VoiceAssistant(session, scope, random, ::speechInput, { q -> voiceAnswer(q) }, settings::voiceSettings, { !display.active && !probe.busy && !teleprompter.active })
        var wasReady = false
        session.onStatus = { s ->
            _status.value = s
            val ready = s.phase == Phase.READY
            if (ready != wasReady) {
                wasReady = ready; voice.connectionChanged(ready)
                if (ready) {
                    nativeNotifier.connectionChanged()
                    glassesSettings.syncTime()   // glasses clock, like the official app (15/5 sync_time)
                    if (settings.weatherOn) runCatching { org.turboio.customai.device.WeatherWorker.now(this) }
                    probe.connectionReady()   // re-sends LifeLog OFF if a previous one was not confirmed
                    if (settings.ambientSettings().glassesMenu) ambient.armGlassesMenu()
                    memoryChanged()
                } else { ambient.connectionLost(); teleprompter.disconnected() }
            }
        }
        runCatching { org.turboio.customai.insights.DailyInsightWorker.schedule(this) }
        runCatching { org.turboio.customai.memory.MemorySchedule.all(this) }
        runCatching { org.turboio.customai.device.WeatherWorker.schedule(this, settings.weatherOn) }
        DiagnosticLog.i("APP", "Turbo IO Custom AI ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    }

    fun connectSaved(): Boolean {
        val ok = session.reconnectSaved()
        if (ok && settings.keepConnected) ConnectionService.start(this)
        return ok
    }

    fun connect(address: String, name: String) {
        session.connect(address, name)
        if (settings.keepConnected) ConnectionService.start(this)
    }

    fun disconnect() { session.disconnect(); ConnectionService.stop(this) }

    /** Journal (SQLite, opened lazily off the main thread) and the insight engine on top of it. */
    val sqlDb: AndroidSqlDb by lazy { AndroidSqlDb(this) }
    val journal: JournalStore by lazy { JournalStore(sqlDb) }
    /** Ambient recording pipeline (option 3). */
    val ambient: org.turboio.customai.ambient.AmbientRecorder by lazy { org.turboio.customai.ambient.AmbientRecorder(this) }
    val insights: InsightEngine by lazy {
        InsightEngine(journal, { system, user, maxTokens -> completeForInsights(system, user, maxTokens) }, {
            val c = settings.aiConfig(); c.provider.id to settings.journalSettings().insightsModel.ifBlank { c.model }
        }, exclude = { day -> excludedFromInsights(day) })
    }

    /** Persistent memory (docs/MEMORIA_E_ASSISTENTE.md): conversations, people, open items, proactive delivery. */
    val memoryStore: org.turboio.customai.memory.MemoryStore by lazy { org.turboio.customai.memory.MemoryStore(sqlDb) }
    val memory: org.turboio.customai.memory.MemoryEngine by lazy {
        org.turboio.customai.memory.MemoryEngine(journal, memoryStore, { s, u, m -> completeForInsights(s, u, m) }, settings::memorySettings)
    }
    val memoryQueries: org.turboio.customai.memory.MemoryQueries by lazy {
        org.turboio.customai.memory.MemoryQueries(journal, memoryStore, { s, u, m -> completeForInsights(s, u, m) })
    }

    /** Ignored categories and conversations without content stay out of the daily insight. */
    private fun excludedFromInsights(day: String): Set<Long> {
        val s = settings.memorySettings()
        if (!s.enabled) return emptySet()
        return memoryStore.blocks(day).filter { s.policy(it.category) != org.turboio.customai.memory.CategoryPolicy.INCLUDE || it.relevance < 0.2 }
            .flatMap { it.entryIds }.toSet()
    }

    /** This week's review (cached per week unless [force]). */
    suspend fun weeklyReview(force: Boolean = false): org.turboio.customai.memory.MemoryQueries.Weekly? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val week = memoryQueries.weekStart(now).toString()
        if (!force) journal.latestInsight(week, "weekly")?.let { return@withContext org.turboio.customai.memory.MemoryQueries.Weekly(org.json.JSONObject(it.json), it.text) }
        memory.process(listOf(Days.shift(Days.of(now), -1), Days.of(now)), now)
        val w = memoryQueries.weekly(now) { d -> journal.latestInsight(d) } ?: return@withContext null
        val c = settings.aiConfig()
        journal.saveInsight(org.turboio.customai.journal.StoredInsight(week, "weekly", "w-" + now, w.json.toString(), w.text, c.provider.id, c.model, now))
        w
    }

    /** One-shot completion without chat history (insights, journal search). Optional cheaper insights model. */
    private suspend fun completeForInsights(system: String, user: String, maxTokens: Int): String {
        val base = settings.aiConfig()
        val model = settings.journalSettings().insightsModel.ifBlank { base.model }
        val config = base.copy(model = model, systemPrompt = system, historyTurns = 0,
            maxTokens = maxOf(maxTokens, 400), timeoutSeconds = maxOf(base.timeoutSeconds, 60))
        return ai.ask(config, settings.apiKey(base.provider), emptyList(), user.take(60_000)).text
    }

    /**
     * Shared by the test screen and voice turns. Keeps a short history for follow-up questions and records
     * the exchange in the journal (option 2).
     */
    suspend fun askAi(question: String, source: EntrySource = EntrySource.TEXT, record: Boolean = true): AiAnswer {
        val config = settings.aiConfig()
        val key = settings.apiKey(config.provider)
        val snapshot = synchronized(history) { history.toList() }
        val started = System.currentTimeMillis()
        val tasks = record && settings.memorySettings().enabled
        val base = if (tasks) config.copy(systemPrompt = config.systemPrompt + TaskMarker.instruction(started)) else config
        val effective = if (DisplayText.wantsDetail(question)) base.copy(maxTokens = maxOf(base.maxTokens, 1500)) else base
        val raw = ai.ask(effective, key, snapshot, question)
        // The assistant itself flags requests to remember something; the marker becomes an item and is never shown.
        val marker = if (tasks) TaskMarker.find(raw.text) else null
        val result = if (marker == null) raw else raw.copy(text = marker.rest.ifBlank { "Anotado." })
        if (marker != null) withContext(Dispatchers.IO) {
            runCatching { memory.itemFromMarker(marker.task, marker.due, question, started) }.getOrNull()?.let {
                DiagnosticLog.i("MEMORIA", "pendência criada pela conversa com a IA")
                org.turboio.customai.memory.Reminders.sync(this@TurboApp); memoryChanged()
            }
        }
        synchronized(history) {
            history.add(ChatMessage("user", question.take(4000)))
            history.add(ChatMessage("assistant", result.text.take(8000)))
            while (history.size > 20) history.removeAt(0)
        }
        if (record) recordInteraction(question, result.text, source, started, result.provider.id, result.model, result.latencyMs)
        return result
    }

    private suspend fun recordInteraction(question: String, answer: String, source: EntrySource, started: Long,
                                          provider: String, model: String, latency: Long, meta: String = "{}") {
        if (!settings.journalSettings().recordInteractions) return
        withContext(Dispatchers.IO) {
            runCatching {
                journal.add(Entry(day = Days.of(started), tsStart = started, tsEnd = System.currentTimeMillis(),
                    kind = EntryKind.INTERACTION, source = source, text = question, answer = answer,
                    provider = provider, model = model, latencyMs = latency, metaJson = meta))
            }.onFailure { DiagnosticLog.w("DIARIO", "falha ao registrar interação: ${it.javaClass.simpleName}") }
        }
        if (!meta.contains("journal_query")) runCatching { org.turboio.customai.memory.MemoryWorker.kick(this) }
    }

    /** Voice entry point: journal questions are answered from the journal; everything else goes to the model. */
    suspend fun voiceAnswer(question: String): String {
        val source = if (settings.sttConfig().engine.usesGlassesMic) EntrySource.VOICE_GLASSES else EntrySource.VOICE_PHONE
        return journalAnswer(question, source) ?: askAi(question, source).text
    }

    /** Returns null when [question] is not about the journal. Journal queries are recorded but excluded from insights. */
    suspend fun journalAnswer(question: String, source: EntrySource): String? {
        val intent = JournalIntents.detect(question) ?: return null
        if (!settings.memorySettings().enabled && (intent is JournalIntent.Remind || intent is JournalIntent.Complete || intent is JournalIntent.Pending)) return null
        val today = Days.of(System.currentTimeMillis())
        val started = System.currentTimeMillis()
        DiagnosticLog.i("DIARIO", "pergunta sobre o diário: ${intent.javaClass.simpleName}")
        val memoryOn = settings.memorySettings().enabled
        val answer = withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            when (intent) {
                is JournalIntent.Summary -> insights.day(Days.shift(today, intent.dayOffset.toLong())).insight.lens(400)
                is JournalIntent.Tasks -> if (memoryOn) listPending(now, null) else insights.day(Days.shift(today, intent.dayOffset.toLong())).insight.tarefas
                    .let { if (it.isEmpty()) "Nenhuma tarefa registrada." else "Tarefas: " + it.take(5).joinToString("; ") }
                is JournalIntent.Pending -> listPending(now, intent.person)
                is JournalIntent.Remind -> memory.quickItem(intent.text, now).let { item ->
                    org.turboio.customai.memory.Reminders.sync(this@TurboApp)
                    "Anotado: " + memoryQueries.line(item, now)
                }
                is JournalIntent.Complete -> when (val m = memoryQueries.match(intent.ref, lastListed)) {
                    is org.turboio.customai.memory.MemoryQueries.Match.One -> {
                        memoryStore.setStatus(m.item.id, org.turboio.customai.memory.ItemStatus.DONE, now, question.take(200))
                        "Feito: " + m.item.text
                    }
                    is org.turboio.customai.memory.MemoryQueries.Match.Many -> {
                        lastListed = m.items.map { it.id }
                        "Qual delas? " + m.items.mapIndexed { i, it -> "${i + 1}) ${it.text}" }.joinToString("; ") + ". Diga: marca a 1 como feita."
                    }
                    org.turboio.customai.memory.MemoryQueries.Match.None -> if (intent.explicit) "Não achei essa pendência. Diga “minhas pendências” para ver a lista." else null
                }
                is JournalIntent.Person -> memoryQueries.personAnswer(intent.name, now)
                is JournalIntent.Weekly -> weeklyReview()?.text?.let { DisplayText.clean(it).take(500) } ?: "Ainda não há conteúdo suficiente nesta semana."
                is JournalIntent.Search -> if (memoryOn) memoryQueries.search(question, now)
                    else insights.ask(question, intent.term, intent.dayOffset?.let { Days.shift(today, it.toLong()) })
            }
        }
        if (answer == null) return null   // "já fiz X" that matches no item: an ordinary question for the model
        if (intent is JournalIntent.Remind || intent is JournalIntent.Complete) memoryChanged()
        val c = settings.aiConfig()
        recordInteraction(question, answer, source, started, c.provider.id, c.model, System.currentTimeMillis() - started, "{\"journal_query\":true}")
        return answer
    }

    /**
     * A short alert on the glasses. Native notification card when enabled; if the glasses do not answer it, the same
     * text goes to the lens display. Not worn / do-not-disturb / busy are respected (nothing else is shown).
     */
    fun glassesAlert(title: String, text: String) {
        if (!session.isReady) return
        scope.launch {
            if (settings.nativeAlerts) {
                val outcome = nativeNotifier.show(title, text)
                if (outcome != org.turboio.customai.device.NativeNotifier.Outcome.FAILED) return@launch
            }
            if (!display.active) display.show(if (title == "Turbo IO") text else "$title: $text", settings.displaySettings())
        }
    }

    /** From the notification listener (any thread): chosen apps → native card; Maps navigation → lens text. */
    fun mirrorNotification(n: org.turboio.customai.device.MirrorPolicy.Incoming) {
        if (!session.isReady) return
        scope.launch {
            if (n.pkg == org.turboio.customai.device.MirrorPolicy.MAPS && !settings.navOn && !settings.mirrorOn) return@launch
            if (n.pkg != org.turboio.customai.device.MirrorPolicy.MAPS && !settings.mirrorOn) return@launch
            when (val a = synchronized(mirrorPolicy) { mirrorPolicy.decide(n) }) {
                is org.turboio.customai.device.MirrorPolicy.Action.Card -> {
                    val outcome = nativeNotifier.show(a.title, a.text, a.appName)
                    if (outcome.delivered) mirroredCards++
                    if (outcome == org.turboio.customai.device.NativeNotifier.Outcome.FAILED && !display.active)
                        display.show("${a.appName} · ${a.title}: ${a.text}".take(270), settings.displaySettings())
                }
                is org.turboio.customai.device.MirrorPolicy.Action.Nav -> {
                    navLines++
                    display.show(a.text, settings.displaySettings().copy(lingerSeconds = 5, pageSeconds = 5))
                }
                is org.turboio.customai.device.MirrorPolicy.Action.Skip -> Unit
            }
        }
    }

    /** Pending items changed somewhere (screen, voice, notification, analysis): mirror to the glasses' To-do. */
    fun memoryChanged() {
        runCatching { glassesTodo.sync() }
        if (settings.dashboardCard && session.isReady) scope.launch { refreshDashboardCard() }
    }

    @Volatile private var lastCard = ""
    @Volatile private var lastCardAt = 0L

    suspend fun dashboardCardText(): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val open = memoryStore.openItems()
        val next = open.firstOrNull { it.dueAt != null && it.dueAt >= now - 3600_000L }
        org.turboio.customai.device.DashboardWire.summary(open.size, next?.dueAt, next?.text.orEmpty(), now)
    }

    /** Re-installs the dashboard card only when its text changed, at most every 2 minutes unless [force]. */
    suspend fun refreshDashboardCard(force: Boolean = false): String {
        val text = dashboardCardText()
        val now = System.currentTimeMillis()
        if (!force && (text == lastCard || now - lastCardAt < 120_000)) return "Cartão já atualizado"
        lastCardAt = now
        val outcome = dashboard.showCard(text)
        if (outcome.startsWith("Cartão no painel")) lastCard = text
        if (outcome == org.turboio.customai.device.Dashboard.NOT_SUPPORTED && settings.dashboardCard) {
            settings.dashboardCard = false
            DiagnosticLog.i("PAINEL", "cartão automático desligado: não suportado por estes óculos")
        }
        return "\"$text\": $outcome"
    }

    /** Ids of the last numbered list read out (for "marca a 2 como feita"). */
    @Volatile var lastListed: List<Long> = emptyList()

    private fun listPending(now: Long, person: String?): String =
        memoryQueries.pendingList(now, person).also { lastListed = it.ids }.text

    /** Pendências nas lentes, on demand (Diário → Pendências, or by voice). */
    suspend fun showPendingOnLens(): String? {
        if (!session.isReady) return "Conecte os óculos"
        val text = withContext(Dispatchers.IO) { listPending(System.currentTimeMillis(), null) }
        display.show(text, settings.displaySettings())
        return null
    }

    fun clearHistory() = synchronized(history) { history.clear() }

    private fun speechInput(): SpeechInput {
        val config = settings.sttConfig()
        return when (config.engine) {
            SttEngine.PHONE_ANDROID -> object : SpeechInput {
                private val recognizer = PhoneRecognizer(this@TurboApp)
                override val usesGlassesMic = false
                override suspend fun listenOnPhone(onPartial: (String) -> Unit): String = suspendCancellableCoroutine { cont ->
                    recognizer.start(config.language, onPartial) { text, error ->
                        scope.launch { if (cont.isActive) { if (error != null) cont.resumeWith(Result.failure(SttException(error))) else cont.resume(text ?: "") } }
                    }
                    cont.invokeOnCancellation { recognizer.cancel() }
                }
                override fun cancel() = recognizer.cancel()
            }
            SttEngine.GLASSES_OPENAI, SttEngine.GLASSES_GOOGLE, SttEngine.GLASSES_DEEPGRAM, SttEngine.GLASSES_GEMINI -> object : SpeechInput {
                override val usesGlassesMic = true
                override suspend fun transcribe(pcm: ByteArray): String {
                    val key = settings.sttKey(config.engine)
                    val gemini = { org.turboio.customai.stt.GeminiSpeechTranscriber(config, settings.apiKey(ProviderKind.GEMINI),
                        settings.modelFor(ProviderKind.GEMINI)) }
                    val transcriber = when (config.engine) {
                        SttEngine.GLASSES_OPENAI -> OpenAiTranscriber(config, key)
                        SttEngine.GLASSES_DEEPGRAM -> org.turboio.customai.stt.DeepgramSpeechTranscriber(config, key)
                        SttEngine.GLASSES_GEMINI -> gemini()
                        else -> GoogleSpeechTranscriber(config, key)
                    }
                    return try { transcriber.transcribe(pcm) } catch (e: SttException) {
                        // A Gemini/AI Studio key in the Google Cloud field is rejected ("API keys are not supported"):
                        // fall back to Gemini (the same kind of key) or Deepgram, whichever is configured.
                        if (config.engine != SttEngine.GLASSES_GOOGLE || e.message?.contains("não serve para esta API") != true) throw e
                        when {
                            settings.hasApiKey(ProviderKind.GEMINI) -> { DiagnosticLog.w("STT", "chave recusada pelo Google Cloud; usando o Gemini"); gemini().transcribe(pcm) }
                            settings.hasDeepgramKey() -> { DiagnosticLog.w("STT", "chave recusada pelo Google Cloud; usando a Deepgram"); org.turboio.customai.stt.DeepgramSpeechTranscriber(config, settings.deepgramKey()).transcribe(pcm) }
                            else -> throw e
                        }
                    }
                }
            }
        }
    }

    companion object {
        lateinit var instance: TurboApp; private set
        fun friendly(e: Throwable): String = when (e) {
            is AiException, is SttException -> e.message ?: "Erro"
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}
