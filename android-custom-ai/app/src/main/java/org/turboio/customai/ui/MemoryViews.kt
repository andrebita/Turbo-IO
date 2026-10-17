package org.turboio.customai.ui

import android.content.Context
import android.graphics.Typeface
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.turboio.customai.TurboApp
import org.turboio.customai.journal.Days
import org.turboio.customai.journal.Entry
import org.turboio.customai.memory.Categories
import org.turboio.customai.memory.CategoryPolicy
import org.turboio.customai.memory.Item
import org.turboio.customai.memory.ItemStatus
import org.turboio.customai.memory.MemorySchedule
import org.turboio.customai.memory.Person
import org.turboio.customai.memory.Reminders

/** The memory sections of the Diário tab: Pendências, Pessoas, Semana (docs/MEMORIA_E_ASSISTENTE.md). */
class MemoryViews(private val context: Context, private val scope: () -> LifecycleCoroutineScope) {
    private val app get() = TurboApp.instance
    private val pending = Column(context)
    private val people = Column(context)
    private val week = Column(context)
    val pendingView: ScrollView = pending.scroll()
    val peopleView: ScrollView = people.scroll()
    val weekView: ScrollView = week.scroll()

    private fun toast(t: String) = Toast.makeText(context, t, Toast.LENGTH_SHORT).show()
    private fun io(block: suspend () -> Unit): kotlinx.coroutines.Job = scope().launch { try { block() } catch (e: Exception) { toast("✗ " + TurboApp.friendly(e)) } }

    // ------------------------------------------------------------------ Pendências
    fun loadPending(): kotlinx.coroutines.Job = io {
        val now = System.currentTimeMillis()
        val (open, done) = withContext(Dispatchers.IO) { app.memoryStore.openItems() to app.memoryStore.items(ItemStatus.DONE, 10) }
        val lines = withContext(Dispatchers.IO) { (open + done).associate { it.id to app.memoryQueries.line(it, now) } }
        val names = withContext(Dispatchers.IO) { app.memoryStore.people().associate { it.id to it.name } }
        pending.clear()
        pending.title("Pendências")
        pending.hint("Criadas automaticamente a partir das suas conversas e perguntas. Cada uma mostra de onde veio. " +
            "Toque para marcar como feita, descartar ou editar.")
        pending.button("Analisar conversas agora") { analyseNow() }
        pending.buttons("Mostrar nas lentes" to { io { app.showPendingOnLens()?.let { toast(it) } } }, "Adicionar" to { add() })
        if (open.isEmpty()) pending.text("Nenhuma pendência aberta.", size = 14f)
        val overdue = open.filter { (it.dueAt ?: Long.MAX_VALUE) < now }
        if (overdue.isNotEmpty()) pending.text("⚠ ${overdue.size} atrasada(s)", size = 13f).setTextColor(statusColor(false))
        for (i in open) itemCard(i, lines.getValue(i.id), names[i.personId])
        if (done.isNotEmpty()) {
            pending.title("Concluídas recentemente")
            for (i in done) pending.text("✓ " + lines.getValue(i.id) + (if (i.doneEvidence.isNotBlank()) "\n   “${i.doneEvidence}”" else ""), size = 13f).apply {
                alpha = 0.7f; setOnClickListener { setStatus(i, ItemStatus.OPEN) }
            }
            pending.hint("Toque em uma concluída para reabri-la.")
        }
        settings()
    }

    private fun itemCard(i: Item, line: String, person: String?) {
        val c = pending.card()
        val head = c.text("${i.kind.label}${if (i.owner == "outro") " (do outro)" else ""}", size = 12f)
        head.alpha = 0.7f
        c.text(line, size = 15f).setTypeface(null, Typeface.BOLD)
        val origin = buildString {
            if (i.quote.isNotBlank()) append("“").append(i.quote).append("”")
            if (i.sourceTs > 0) append(if (isEmpty()) "" else " — ").append(Days.label(Days.of(i.sourceTs))).append(" ").append(Days.time(i.sourceTs))
            if (person != null && !line.contains(person)) append(" · ").append(person)
        }
        if (origin.isNotBlank()) c.hint(origin)
        c.buttons("Feita" to { setStatus(i, ItemStatus.DONE) }, "Descartar" to { setStatus(i, ItemStatus.DISMISSED) }, "Editar" to { edit(i) })
    }

    private fun setStatus(i: Item, s: ItemStatus): kotlinx.coroutines.Job = io {
        withContext(Dispatchers.IO) { app.memoryStore.setStatus(i.id, s, System.currentTimeMillis(), if (s == ItemStatus.DONE) "marcada no app" else "") }
        if (s == ItemStatus.DISMISSED) toast("Descartada — não será sugerida de novo")
        loadPending()
    }

    private fun edit(i: Item) {
        val field = EditText(context).apply { setText(i.text) }
        AlertDialog.Builder(context).setTitle("Editar pendência").setView(field)
            .setPositiveButton("Salvar") { _, _ -> io {
                withContext(Dispatchers.IO) { app.memoryStore.updateItem(i.copy(text = field.text.toString().trim().ifEmpty { i.text }, updatedAt = System.currentTimeMillis())) }
                loadPending()
            } }.setNegativeButton("Cancelar", null).show()
    }

    private fun add() {
        val field = EditText(context).apply { hint = "Ex.: ligar para o João sexta às 10h" }
        AlertDialog.Builder(context).setTitle("Nova pendência").setView(field).setPositiveButton("Adicionar") { _, _ ->
            val text = field.text.toString().trim()
            if (text.length >= 3) io {
                val item = withContext(Dispatchers.IO) { app.memory.quickItem(text, System.currentTimeMillis()).also { Reminders.sync(app) } }
                toast("Anotado: ${item.text}"); loadPending()
            }
        }.setNegativeButton("Cancelar", null).show()
    }

    private fun analyseNow(): kotlinx.coroutines.Job = io {
        toast("Analisando conversas…")
        val now = System.currentTimeMillis(); val today = Days.of(now)
        val run = withContext(Dispatchers.IO) { app.memory.process(listOf(Days.shift(today, -1), today), now) }
        withContext(Dispatchers.IO) { Reminders.sync(app) }
        toast("${run.blocks} conversa(s) analisada(s) · ${run.newItems.size} nova(s) pendência(s)" + if (run.failed > 0) " · ${run.failed} falha(s)" else "")
        loadPending()
    }

    private fun settings() {
        val s = app.settings.memorySettings()
        fun save(change: (org.turboio.customai.memory.MemorySettings) -> org.turboio.customai.memory.MemorySettings) {
            app.settings.saveMemory(change(app.settings.memorySettings())); MemorySchedule.all(context)
        }
        pending.title("Configurações da memória")
        pending.switch("Analisar conversas e criar pendências automaticamente", s.enabled) { v -> save { it.copy(enabled = v) } }
        pending.switch("Avisar quando surgir uma pendência nova", s.notifyNew) { v -> save { it.copy(notifyNew = v) } }
        pending.switch("Mostrar pendências novas nas lentes", s.lensNew) { v -> save { it.copy(lensNew = v) } }
        pending.switch("Lembrete antes do prazo (notificação e lentes)", s.reminders) { v -> save { it.copy(reminders = v) } }
        pending.switch("Resumo da manhã", s.morning) { v -> save { it.copy(morning = v) } }
        val morning = pending.field("Horário do resumo da manhã (HH:MM)", "%02d:%02d".format(s.morningHour, s.morningMinute))
        pending.switch("Revisão semanal (domingo)", s.weekly) { v -> save { it.copy(weekly = v) } }
        pending.button("Salvar horário", filled = false) {
            val m = Regex("^(\\d{1,2}):(\\d{2})$").find(morning.text.toString().trim())
            val h = m?.groupValues?.get(1)?.toIntOrNull(); val mi = m?.groupValues?.get(2)?.toIntOrNull()
            if (h == null || mi == null || h > 23 || mi > 59) toast("Use HH:MM") else { save { it.copy(morningHour = h, morningMinute = mi) }; toast("Salvo") }
        }
        pending.title("Privacidade por tipo de conversa")
        pending.hint("“Ignorar” deixa a conversa no diário, mas fora de pendências e resumos. “Não guardar” apaga as falas " +
            "dessa conversa do diário depois de classificá-la.")
        for (cat in Categories.ALL) {
            pending.text(Categories.LABELS.getValue(cat), size = 13f)
            val options = CategoryPolicy.entries
            pending.spinner(options, options.indexOf(s.policy(cat)), { it.label }) { p ->
                if (app.settings.memorySettings().policy(cat) != p) save { it.copy(policies = it.policies + (cat to p)) }
            }
        }
    }

    // ------------------------------------------------------------------ Pessoas
    fun loadPeople(): kotlinx.coroutines.Job = io {
        val list = withContext(Dispatchers.IO) { app.memoryStore.people().map { it to app.memoryStore.itemsOf(it.id).size } }
        people.clear()
        people.title("Pessoas")
        people.hint("Nomes descobertos pelo contexto das conversas (só com evidência clara) ou indicados por você. " +
            "Para dizer quem é uma “Pessoa A”, toque longo na fala dela na linha do tempo do dia.")
        if (list.isEmpty()) people.text("Ninguém identificado ainda.", size = 14f)
        for ((p, open) in list) {
            val c = people.card()
            c.text(p.name, size = 16f).setTypeface(null, Typeface.BOLD)
            c.hint(listOfNotNull(p.relation.ifBlank { null }, "${p.conversations} conversa(s)",
                if (p.lastSeen > 0) "última: ${Days.label(Days.of(p.lastSeen))}" else null, if (open > 0) "$open pendência(s)" else null).joinToString(" · "))
            c.root.setOnClickListener { showPerson(p) }
            c.buttons("Ver" to { showPerson(p) }, "Renomear" to { rename(p) }, "Juntar" to { merge(p) })
        }
    }

    private fun showPerson(p: Person): kotlinx.coroutines.Job = io {
        val card = withContext(Dispatchers.IO) { app.memoryQueries.personCard(p, System.currentTimeMillis()) }
        AlertDialog.Builder(context).setTitle(p.name).setMessage(card)
            .setPositiveButton("Fechar", null)
            .setNeutralButton("Apagar pessoa") { _, _ -> AlertDialog.Builder(context).setTitle("Apagar ${p.name}?")
                .setMessage("Remove a ficha. As falas no diário continuam; as pendências ficam sem pessoa.")
                .setPositiveButton("Apagar") { _, _ -> io { withContext(Dispatchers.IO) { app.memoryStore.deletePerson(p.id) }; loadPeople() } }
                .setNegativeButton("Cancelar", null).show() }
            .show()
    }

    private fun rename(p: Person) {
        val field = EditText(context).apply { setText(p.name) }
        AlertDialog.Builder(context).setTitle("Renomear").setView(field).setPositiveButton("Salvar") { _, _ ->
            val name = field.text.toString().trim()
            if (name.isNotEmpty()) io { withContext(Dispatchers.IO) { app.memoryStore.renamePerson(p.id, name) }; loadPeople() }
        }.setNegativeButton("Cancelar", null).show()
    }

    private fun merge(p: Person): kotlinx.coroutines.Job = io {
        val others = withContext(Dispatchers.IO) { app.memoryStore.people().filter { it.id != p.id } }
        if (others.isEmpty()) { toast("Não há outra pessoa para juntar"); return@io }
        AlertDialog.Builder(context).setTitle("${p.name} é a mesma pessoa que…").setItems(others.map { it.name }.toTypedArray()) { _, which ->
            io { withContext(Dispatchers.IO) { app.memoryStore.mergePeople(p.id, others[which].id) }; toast("Juntado em ${others[which].name}"); loadPeople() }
        }.setNegativeButton("Cancelar", null).show()
    }

    /** Timeline long-press on an ambient utterance: who is speaking? */
    fun assignSpeaker(entry: Entry, after: () -> Unit) = io {
        val known = withContext(Dispatchers.IO) { app.memoryStore.people() }
        val names = known.map { it.name } + "Outro nome…"
        AlertDialog.Builder(context).setTitle("Quem está falando?").setItems(names.toTypedArray()) { _, which ->
            if (which < known.size) apply(entry, known[which].name, after)
            else {
                val field = EditText(context).apply { hint = "Nome" }
                AlertDialog.Builder(context).setTitle("Nome de quem fala").setView(field)
                    .setPositiveButton("Salvar") { _, _ -> field.text.toString().trim().takeIf { it.isNotEmpty() }?.let { apply(entry, it, after) } }
                    .setNegativeButton("Cancelar", null).show()
            }
        }.setNegativeButton("Cancelar", null).show()
    }

    private fun apply(entry: Entry, name: String, after: () -> Unit) = io {
        val p = withContext(Dispatchers.IO) { app.memory.assignSpeaker(entry, name) }
        toast(if (p != null) "Falas deste trecho atribuídas a ${p.name}" else "Esta fala não tem rótulo de falante")
        after()
    }

    // ------------------------------------------------------------------ Semana
    fun loadWeek(): kotlinx.coroutines.Job = io {
        val now = System.currentTimeMillis()
        val weekStart = app.memoryQueries.weekStart(now).toString()
        val stored = withContext(Dispatchers.IO) { app.journal.latestInsight(weekStart, "weekly") }
        week.clear()
        week.title("Semana de ${Days.label(weekStart)}")
        val text = week.text(stored?.text ?: "Revisão ainda não gerada nesta semana.", size = 14f)
        week.button("Gerar / atualizar revisão da semana") {
            text.text = "Gerando…"
            io { val w = app.weeklyReview(force = true); text.text = w?.text ?: "Ainda não há conteúdo suficiente nesta semana." }
        }
        week.title("Resumo da manhã")
        val brief = week.text("", size = 14f)
        week.buttons("Ver agora" to { io { brief.text = withContext(Dispatchers.IO) { app.memoryQueries.morningBrief(System.currentTimeMillis()) } ?: "Nada pendente." } },
            "Nas lentes" to { io {
                val t = withContext(Dispatchers.IO) { app.memoryQueries.morningBrief(System.currentTimeMillis()) } ?: "Nada pendente."
                if (app.session.isReady) app.display.show(t, app.settings.displaySettings()) else toast("Conecte os óculos")
            } })
        week.title("Perguntar ao diário")
        week.hint("Por sentido e período, ex.: “o que falei de custos semana passada?”, “o que combinei com a Marina?”")
        val q = week.field("Pergunta")
        val answer = week.text("", size = 14f)
        week.button("Perguntar") {
            val question = q.text.toString().trim()
            if (question.length < 4) { toast("Escreva a pergunta"); return@button }
            answer.text = "Procurando…"
            io {
                val a = app.journalAnswer(question, org.turboio.customai.journal.EntrySource.TEXT)
                    ?: withContext(Dispatchers.IO) { app.memoryQueries.search(question, System.currentTimeMillis()) }
                answer.text = a
            }
        }
    }
}
