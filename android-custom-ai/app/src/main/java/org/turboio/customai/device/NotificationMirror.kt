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
        enum class Category(val label: String, val preselect: Boolean) {
            MESSAGES("Mensagens", true), EMAIL("E-mail", true), BANKS("Bancos e pagamentos", true),
            CALENDAR("Agenda", false), WORK("Trabalho", false), OTHER("Outros", false)
        }

        data class Known(val pkg: String, val name: String, val category: Category)

        /**
         * Common apps offered in the choice list even before any notification arrived (only the installed ones are shown).
         * Every package here must also be listed under <queries> in AndroidManifest.xml (Android 11+ package visibility).
         */
        val CATALOG = listOf(
            Known("com.whatsapp", "WhatsApp", Category.MESSAGES), Known("com.whatsapp.w4b", "WhatsApp Business", Category.MESSAGES),
            Known("org.telegram.messenger", "Telegram", Category.MESSAGES), Known("org.thoughtcrime.securesms", "Signal", Category.MESSAGES),
            Known("com.google.android.apps.messaging", "Mensagens (Google)", Category.MESSAGES),
            Known("com.samsung.android.messaging", "Mensagens (Samsung)", Category.MESSAGES),
            Known("com.facebook.orca", "Messenger", Category.MESSAGES), Known("com.instagram.android", "Instagram", Category.MESSAGES),
            Known("com.google.android.gm", "Gmail", Category.EMAIL), Known("com.microsoft.office.outlook", "Outlook", Category.EMAIL),
            Known("com.samsung.android.email.provider", "E-mail (Samsung)", Category.EMAIL),
            Known("com.nu.production", "Nubank", Category.BANKS), Known("com.itau", "Itaú", Category.BANKS),
            Known("com.bradesco", "Bradesco", Category.BANKS), Known("br.com.bb.android", "Banco do Brasil", Category.BANKS),
            Known("br.com.gabba.Caixa", "CAIXA", Category.BANKS), Known("br.gov.caixa.tem", "CAIXA Tem", Category.BANKS),
            Known("com.santander.app", "Santander", Category.BANKS), Known("br.com.intermedium", "Inter", Category.BANKS),
            Known("com.c6bank.app", "C6 Bank", Category.BANKS), Known("com.picpay", "PicPay", Category.BANKS),
            Known("com.mercadopago.wallet", "Mercado Pago", Category.BANKS), Known("br.com.uol.ps.myaccount", "PagBank", Category.BANKS),
            Known("br.com.neon", "Neon", Category.BANKS),
            Known("com.google.android.calendar", "Google Agenda", Category.CALENDAR), Known("com.samsung.android.calendar", "Agenda (Samsung)", Category.CALENDAR),
            Known("com.microsoft.teams", "Teams", Category.WORK), Known("com.Slack", "Slack", Category.WORK),
            Known("com.linkedin.android", "LinkedIn", Category.WORK),
            Known("com.ubercab", "Uber", Category.OTHER), Known("com.taxis99", "99", Category.OTHER), Known("br.com.brainweb.ifood", "iFood", Category.OTHER),
        )
        private val NAMES = CATALOG.associate { it.pkg to it.name } + (MAPS to "Maps")

        /** Rows of the choice list: installed catalog apps (by category) first, then other apps seen notifying. */
        fun choices(installed: (String) -> Boolean, seen: Set<String>, chosen: Set<String>): List<Known> {
            val known = CATALOG.filter { it.pkg in chosen || it.pkg in seen || installed(it.pkg) }
                .sortedWith(compareBy({ it.category.ordinal }, { it.name.lowercase() }))
            val catalogued = CATALOG.map { it.pkg }.toSet()
            val others = (seen + chosen).filter { it !in catalogued && it != MAPS }.sorted().map { Known(it, name(it, null), Category.OTHER) }
            return known + others
        }

        /** First-time default: messages, e-mail and banks among the offered apps. */
        fun defaults(rows: List<Known>): Set<String> = rows.filter { it.category.preselect }.map { it.pkg }.toSet()

        /** Display name when the system label is not available. */
        fun name(pkg: String, label: String?): String = label?.takeIf { it.isNotBlank() && it != pkg } ?: NAMES[pkg]
            ?: pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }
}
