package org.turboio.customai.insights

import org.turboio.customai.journal.searchNormalize

/** Spoken/typed questions about the journal, detected deterministically before calling the model. */
sealed class JournalIntent {
    data class Summary(val dayOffset: Int) : JournalIntent()
    data class Tasks(val dayOffset: Int) : JournalIntent()
    /** Open items from the persistent memory, optionally only those with one person. */
    data class Pending(val person: String?) : JournalIntent()
    /** "O que tenho / conversei com a Marina?" → person card. */
    data class Person(val name: String) : JournalIntent()
    object Weekly : JournalIntent() { override fun toString() = "Weekly" }
    /** "Me lembra de…", "anota…": a new item right away. */
    data class Remind(val text: String) : JournalIntent()
    /** Marks an item done by its number in the last list or by its words; [explicit] = "marca … como feita". */
    data class Complete(val ref: String, val explicit: Boolean) : JournalIntent()
    data class Search(val term: String, val dayOffset: Int?) : JournalIntent()
}

object JournalIntents {
    private val NOT_NAMES = setOf("isso", "aquilo", "ele", "ela", "eles", "elas", "voce", "mim", "tudo", "nada", "hoje", "ontem")
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

    private val ARTICLE = Regex("^(o|a|os|as|do|da|dos|das|com|meu|minha|seu|sua|dona|seu|dr|dra|doutor|doutora)\\s+")
    private fun name(raw: String): String? {
        var n = raw.trim()
        repeat(3) { n = n.replace(ARTICLE, "") }
        n = n.replace(Regex("\\b(hoje|ontem|anteontem|essa semana|esta semana|semana passada)\\b"), " ").replace(Regex("\\s+"), " ").trim()
        return n.takeIf { it.length in 2..40 && it.split(" ").size <= 3 }
    }

    private val PENDING_WITH = listOf(
        Regex("\\b(o que|oque) (eu )?(tenho|ficou|esta) (de )?pendente com (.+)$"),
        Regex("\\b(pendencias|tarefas|compromissos) com (.+)$"),
        Regex("\\b(o que|oque) (eu )?(combinei|fiquei de fazer|prometi) (com|para|pra|pro) (.+)$"),
    )
    private val PERSON = listOf(
        Regex("\\b(o que|oque) (eu )?(conversei|falei|combinei|tenho|sei) com (?<n>.+)$"),
        Regex("\\b(ultima|ultimas) (conversa|conversas) com (?<n>.+)$"),
    )
    private val WEEKLY = listOf(
        Regex("\\b(resumo|revisao|balanco) (da|de|desta|dessa)( minha)? semana\\b"),
        Regex("\\brevisao semanal\\b"),
        Regex("\\bcomo foi (a |minha |esta |essa )?semana\\b"),
    )
    private val PENDING = listOf(
        Regex("\\b(quais|quais sao) (as )?minhas pendencias\\b"),
        Regex("\\b(o que|oque) (eu )?(tenho|ficou) (de )?pendente$"),
        Regex("\\bminhas pendencias\\b"),
        Regex("\\b(o que|oque) (eu )?tenho (pra|para) (fazer|hoje)\\b"),
    )

    private val REMIND = listOf(
        Regex("\\b(me lembra|me lembre|lembre me|lembre-me|me lembrar|lembra me|lembra-me) (de|que) (?<t>.+)$"),
        Regex("^(por favor )?anota( ai| pra mim| para mim)?( que)? (?<t>.+)$"),
        Regex("\\b(adiciona|adicione|cria|crie|coloca|coloque)( uma)? (tarefa|pendencia|lembrete)( de| para| pra|:)? (?<t>.+)$"),
        Regex("\\bnao (me )?deix[ae] esquecer (de |que )?(?<t>.+)$"),
    )
    private const val NUM = "\\d+|um|uma|dois|duas|tres|quatro|cinco|seis|sete|oito|nove|dez"
    private val COMPLETE_EXPLICIT = listOf(
        Regex("\\b(marca|marque|marcar)( a| o)?( pendencia| tarefa| item)?( numero)? (?<t>$NUM)( como)? (feita|feito|concluida|concluido|pronta|pronto)\\b"),
        Regex("\\b(marca|marque|marcar)( como)? (feita|feito|concluida|concluido|pronta|pronto)( a| o)?( pendencia| tarefa)? (?<t>.+)$"),
        Regex("\\b(marca|marque|marcar) (?<t>.+) como (feita|feito|concluida|concluido|pronta|pronto)$"),
        Regex("\\b(conclui|concluir|finaliza|finalizar)( a| o)? (pendencia|tarefa) (?<t>.+)$"),
    )
    private val COMPLETE_SOFT = listOf(
        Regex("^ja (fiz|terminei|mandei|enviei|liguei|paguei|comprei|resolvi|respondi|entreguei|marquei|agendei) (?<t>.+)$"),
    )

    fun detect(raw: String): JournalIntent? {
        val text = searchNormalize(raw).replace(Regex("[?!.,;:]+"), " ").replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty() || text.length > 200) return null
        for (r in COMPLETE_EXPLICIT) r.find(text)?.let { m -> m.groups["t"]?.value?.trim()?.takeIf { it.isNotEmpty() }?.let { return JournalIntent.Complete(it, true) } }
        for (r in REMIND) r.find(text)?.let { m ->
            // Keep the user's own wording (accents, names) for the item text.
            val t = m.groups["t"]!!.value.trim()
            val original = raw.trim().trimEnd('.', '!', '?')
            val start = original.length - t.length
            return JournalIntent.Remind(if (start >= 0 && searchNormalize(original.substring(start)).equals(t, true)) original.substring(start) else t)
        }
        for (r in COMPLETE_SOFT) r.find(text)?.let { m -> return JournalIntent.Complete(m.value, false) }
        if (WEEKLY.any { it.containsMatchIn(text) }) return JournalIntent.Weekly
        for (r in PENDING_WITH) r.find(text)?.let { m -> name(m.groupValues.last())?.let { return JournalIntent.Pending(it) } }
        if (PENDING.any { it.containsMatchIn(text) }) return JournalIntent.Pending(null)
        for (r in SEARCH) {
            val m = r.find(text) ?: continue
            var term = m.groupValues.last().trim()
            val day = offset(" $term ")
            term = term.replace(Regex("\\b(hoje|ontem|anteontem|no diario|de manha|a tarde|a noite)\\b"), " ").replace(Regex("\\s+"), " ").trim()
            if (term.length >= 2) return JournalIntent.Search(term, day ?: offset(text))
        }
        for (r in PERSON) r.find(text)?.let { m -> name(m.groups["n"]?.value ?: "")?.takeIf { it !in NOT_NAMES }?.let { return JournalIntent.Person(it) } }
        if (TASKS.any { it.containsMatchIn(text) }) return JournalIntent.Tasks(offset(text) ?: 0)
        if (SUMMARY.any { it.containsMatchIn(text) }) return JournalIntent.Summary(offset(text) ?: 0)
        return null
    }
}
