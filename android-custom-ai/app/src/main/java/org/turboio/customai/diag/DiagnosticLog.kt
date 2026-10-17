package org.turboio.customai.diag

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory, bounded technical log. Every message passes through [Redactor] before storage, and the
 * log is never written to logcat with payload contents. API keys, bearer tokens, Bluetooth addresses
 * and protocol identifiers must not appear here.
 */
object DiagnosticLog {
    enum class Level { DEBUG, INFO, WARN, ERROR }
    data class Entry(val time: Long, val level: Level, val tag: String, val message: String) {
        fun format(): String = "${FORMAT.get()!!.format(Date(time))} ${level.name.first()} [$tag] $message"
    }

    private val FORMAT = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    private const val CAPACITY = 2000
    private val entries = ArrayDeque<Entry>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Optional sink (e.g. android.util.Log) — receives already-redacted text only. */
    @Volatile var sink: ((Level, String, String) -> Unit)? = null

    fun d(tag: String, message: String) = add(Level.DEBUG, tag, message)
    fun i(tag: String, message: String) = add(Level.INFO, tag, message)
    fun w(tag: String, message: String) = add(Level.WARN, tag, message)
    fun e(tag: String, message: String) = add(Level.ERROR, tag, message)

    fun add(level: Level, tag: String, message: String) {
        val safe = Redactor.redact(message).take(600)
        synchronized(entries) {
            entries.addLast(Entry(System.currentTimeMillis(), level, tag, safe))
            while (entries.size > CAPACITY) entries.removeFirst()
        }
        sink?.invoke(level, tag, safe)
        listeners.forEach { runCatching { it() } }
    }

    fun snapshot(): List<Entry> = synchronized(entries) { entries.toList() }
    fun clear() { synchronized(entries) { entries.clear() }; listeners.forEach { runCatching { it() } } }
    fun export(): String = snapshot().joinToString("\n") { it.format() }
    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }
}

object Redactor {
    private val rules: List<Pair<Regex, String>> = listOf(
        Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{6,}") to "$1***",
        Regex("(?i)(x-api-key|x-goog-api-key|api[_-]?key|authorization|token|secret)([\"'\\s:=]+)[^\\s\"',;&]{4,}") to "$1$2***",
        Regex("sk-(ant-)?[A-Za-z0-9_-]{8,}") to "sk-***",
        Regex("AIza[0-9A-Za-z_-]{20,}") to "AIza***",
        Regex("(?i)([?&]key=)[^&\\s]+") to "$1***",
        Regex("\\b([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\\b") to "XX:XX:XX:XX:XX:XX",
    )

    fun redact(text: String): String = rules.fold(text) { acc, (regex, replacement) -> regex.replace(acc, replacement) }

    /** Masks a secret for display: shows only that a value exists and its last 2 characters. */
    fun mask(secret: String): String = when {
        secret.isEmpty() -> "(vazio)"
        secret.length <= 6 -> "••••"
        else -> "••••" + secret.takeLast(2)
    }
}
