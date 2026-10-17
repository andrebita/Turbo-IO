package org.turboio.customai.memory

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Lets the configured assistant (Gemini, OpenAI, Claude…) turn free phrasing (“preciso comprar pão amanhã”) into an item:
 * the system prompt asks for a one-line marker at the start of the answer; the app removes it and saves the item.
 * Provider-agnostic (plain text, no tool-calling API).
 */
object TaskMarker {
    data class Found(val task: String, val due: String, val rest: String)

    private val REGEX = Regex("^\\s*\\[\\[\\s*TAREFA\\s*:\\s*(.+?)\\s*\\|\\s*QUANDO\\s*:\\s*([^\\]]*?)\\s*]]\\s*", RegexOption.IGNORE_CASE)

    fun instruction(now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val stamp = Instant.ofEpochMilli(now).atZone(zone).format(DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy HH:mm", Locale.forLanguageTag("pt-BR")))
        return "\n\nAgora: $stamp. Se o usuário pedir para lembrar, anotar, agendar ou não esquecer algo, ou disser algo que precisa " +
            "fazer depois (ex.: “preciso comprar pão amanhã”), comece a resposta com UMA linha exatamente assim: " +
            "[[TAREFA: verbo no infinitivo + objeto | QUANDO: AAAA-MM-DDTHH:MM ou vazio]] e depois confirme em uma frase curta. " +
            "Use a data e hora atuais acima para calcular o prazo. Em qualquer outro caso, nunca use essa marcação."
    }

    fun find(answer: String): Found? {
        val m = REGEX.find(answer) ?: return null
        return Found(m.groupValues[1].trim(), m.groupValues[2].trim(), answer.substring(m.range.last + 1).trim())
    }
}
