package org.turboio.customai.device

/**
 * Which phone notifications reach the glasses, decided without Android types so it can be unit-tested.
 * Content is never stored or logged: only the package name is remembered (for the app list in the settings).
 */
class MirrorPolicy(
    private val ownPackage: String,
    private val allowed: () -> Set<String>,
    private val navOn: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val minGapMs: Long = 4000,
    private val dedupeMs: Long = 60_000,
    private val navGapMs: Long = 8000,
) {
    data class Incoming(
        val pkg: String, val appName: String, val title: String, val text: String,
        val ongoing: Boolean, val groupSummary: Boolean, val category: String? = null,
    )

    sealed class Action {
        data class Card(val appName: String, val title: String, val text: String) : Action()
        data class Nav(val text: String) : Action()
        data class Skip(val reason: String) : Action()
    }

    private val recent = LinkedHashMap<String, Long>()
    private var lastCard = 0L
    private var lastNav = ""; private var lastNavAt = 0L; private var lastStreet = ""

    fun decide(n: Incoming): Action {
        val now = clock()
        if (n.pkg == ownPackage) return Action.Skip("próprio app")
        if (n.pkg == MAPS && navOn() && n.ongoing) return navigation(n, now)
        if (n.pkg !in allowed()) return Action.Skip("app não escolhido")
        if (n.groupSummary) return Action.Skip("resumo de grupo")
        if (n.ongoing || n.category in SKIPPED) return Action.Skip("contínua/serviço")
        val title = n.title.trim().take(60); val text = n.text.trim().replace(Regex("\\s+"), " ").take(300)
        if (title.isEmpty() && text.isEmpty()) return Action.Skip("vazia")
        val key = "${n.pkg}|$title|$text"
        recent.entries.removeAll { now - it.value > dedupeMs }
        if (recent.containsKey(key)) return Action.Skip("repetida")
        if (now - lastCard < minGapMs) return Action.Skip("muitas seguidas")
        recent[key] = now; lastCard = now
        return Action.Card(n.appName, title.ifEmpty { n.appName }, text.ifEmpty { title })
    }

    /** Google Maps turn-by-turn: the ongoing navigation notification (title = distance/maneuver, text = street). */
    private fun navigation(n: Incoming, now: Long): Action {
        val title = n.title.trim(); val street = n.text.trim()
        val line = listOf(title, street).filter { it.isNotEmpty() }.joinToString(" · ").take(90)
        if (line.isEmpty() || line == lastNav) return Action.Skip("sem mudança")
        // New street/maneuver: show now; same street, only the distance changed: at most every navGapMs.
        if (street == lastStreet && now - lastNavAt < navGapMs) return Action.Skip("distância (aguardando)")
        lastNav = line; lastNavAt = now; lastStreet = street
        return Action.Nav(line)
    }

    companion object {
        const val MAPS = "com.google.android.apps.maps"
        private val SKIPPED = setOf("progress", "service", "transport", "sys", "status")
        private val NAMES = mapOf("com.whatsapp" to "WhatsApp", "com.whatsapp.w4b" to "WhatsApp Business", "org.telegram.messenger" to "Telegram",
            "com.google.android.gm" to "Gmail", "com.microsoft.office.outlook" to "Outlook", "com.slack" to "Slack",
            "com.google.android.apps.messaging" to "Mensagens", "com.samsung.android.messaging" to "Mensagens",
            "com.microsoft.teams" to "Teams", "com.instagram.android" to "Instagram", "com.google.android.calendar" to "Agenda",
            "com.samsung.android.calendar" to "Agenda", MAPS to "Maps", "com.linkedin.android" to "LinkedIn", "com.facebook.orca" to "Messenger")

        /** Display name when the system label is not available. */
        fun name(pkg: String, label: String?): String = label?.takeIf { it.isNotBlank() && it != pkg } ?: NAMES[pkg]
            ?: pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }
}
