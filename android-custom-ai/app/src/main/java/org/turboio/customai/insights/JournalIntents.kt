package org.turboio.customai.insights

import org.turboio.customai.journal.searchNormalize

/** Spoken/typed questions about the journal, detected deterministically before calling the model. */
sealed class JournalIntent {
    data class Summary(val dayOffset: Int) : JournalIntent()
    data class Tasks(val dayOffset: Int) : JournalIntent()
    data class Search(val term: String, val dayOffset: Int?) : JournalIntent()
}

object JournalIntents {
    private fun offset(text: String): Int? = when {
        Regex("\\banteontem\\b").containsMatchIn(text) -> -2
        Regex("\\bontem\\b").containsMatchIn(text) -> -1
        Regex("\\bhoje\\b").containsMatchIn(text) -> 0
        else -> null
    }

    private val SUMMARY = listOf(
        Regex("\\bresum[oa] (do |de |da )?(o )?(meu )?(dia|hoje|ontem|anteontem)\\b"),
        Regex("\\b(o que|oque) (eu )?(fiz|fizemos)( hoje| ontem| anteontem)?$"),
        Regex("\\b(o que|oque) aconteceu (no meu dia|comigo)( hoje| ontem)?$"),
        Regex("\\bcomo foi (o )?(meu )?(dia|hoje|ontem)\\b"),
        Regex("\\binsights? (do|de) (meu )?(dia|hoje|ontem)\\b"),
    )
    private val TASKS = listOf(
        Regex("\\bminhas (tarefas|pendencias)\\b"),
        Regex("\\b(tarefas|pendencias) (de|do|da) (hoje|ontem|anteontem|dia)\\b"),
        Regex("\\b(o que|oque) (eu )?(tenho|ficou) (de )?pendente\\b"),
    )
    private val SEARCH = listOf(
        Regex("\\b(o que|oque) (eu )?(falei|disse|conversei|comentei|perguntei|ouvi)( la| ali)? sobre (.+)$"),
        Regex("\\b(procure|procura|busque|busca|pesquise)( no| em)?( meu)? diario( por| sobre)? (.+)$"),
        Regex("\\bquando (eu )?(falei|disse|conversei) sobre (.+)$"),
    )

    fun detect(raw: String): JournalIntent? {
        val text = searchNormalize(raw).replace(Regex("[?!.,;:]+"), " ").replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty() || text.length > 200) return null
        for (r in SEARCH) {
            val m = r.find(text) ?: continue
            var term = m.groupValues.last().trim()
            val day = offset(" $term ")
            term = term.replace(Regex("\\b(hoje|ontem|anteontem|no diario|de manha|a tarde|a noite)\\b"), " ").replace(Regex("\\s+"), " ").trim()
            if (term.length >= 2) return JournalIntent.Search(term, day ?: offset(text))
        }
        if (TASKS.any { it.containsMatchIn(text) }) return JournalIntent.Tasks(offset(text) ?: 0)
        if (SUMMARY.any { it.containsMatchIn(text) }) return JournalIntent.Summary(offset(text) ?: 0)
        return null
    }
}
