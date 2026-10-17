package org.turboio.customai.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.turboio.customai.TurboApp
import org.turboio.customai.insights.DailyInsightWorker
import org.turboio.customai.journal.Days
import org.turboio.customai.journal.Entry
import org.turboio.customai.journal.EntryKind
import org.turboio.customai.journal.JournalExport

class JournalFragment : Fragment() {
    private val app get() = TurboApp.instance
    private var day = Days.of(System.currentTimeMillis())
    private lateinit var dayLabel: TextView
    private lateinit var insightText: TextView
    private lateinit var status: TextView
    private lateinit var timeline: LinearLayout
    private lateinit var search: TextInputEditText
    private lateinit var generate: MaterialButton
    private var lastLens = ""
    private lateinit var ambientStatus: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        val ui = Column(requireContext())
        val nav = ui.row()
        val c = Column(requireContext())
        c.button("◀ Dia anterior", filled = false) { day = Days.shift(day, -1); load() }
        c.button("Hoje", filled = false) { day = Days.of(System.currentTimeMillis()); load() }
        c.button("Próximo dia ▶", filled = false) { day = Days.shift(day, 1); load() }
        nav.addView(c.root)
        dayLabel = ui.title("")

        val amb = ui.card()
        amb.title("Gravação ambiente (experimental)")
        ambientStatus = amb.text("", size = 13f)
        amb.hint("Pelos óculos: usa os microfones dos óculos (LifeLog do firmware original) e separa a sua voz da dos outros. " +
            "Pelo telefone: usa o microfone do celular.")
        amb.button("Gravar pelos óculos") { startAmbient(glasses = true) }
        amb.button("Gravar pelo microfone do telefone", filled = false) { startAmbient(glasses = false) }
        amb.button("Parar gravação", filled = false) { app.ambient.stop() }
        amb.button("Transcrever pendentes agora", filled = false) { org.turboio.customai.ambient.AmbientRecorder.enqueue(app); app.ambient.refresh() }
        amb.button("Tentar novamente as que falharam", filled = false) { app.ambient.retryFailed() }
        amb.button("Bateria: permitir em segundo plano (Samsung: \"Sem restrições\")", filled = false) {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
        val a = app.settings.ambientSettings()
        amb.switch("Apagar o áudio após transcrever", a.deleteRaw) { app.settings.saveAmbient(app.settings.ambientSettings().copy(deleteRaw = it)) }
        amb.switch("Transcrever só no Wi-Fi", a.wifiOnly) { app.settings.saveAmbient(app.settings.ambientSettings().copy(wifiOnly = it)) }
        amb.switch("Ligar e desligar pelo menu dos óculos", a.glassesMenu) { on ->
            if (on && !app.settings.ambientSettings().consent) toast("Aceite o aviso de consentimento (toque em Gravar pelos óculos) para o menu dos óculos funcionar")
            app.settings.saveAmbient(app.settings.ambientSettings().copy(glassesMenu = on)); app.ambient.armGlassesMenu()
        }
        amb.hint("Com esta opção, abrir o LifeLog no menu dos óculos começa a gravar o diário (com o app aberto ou a conexão mantida em segundo plano), " +
            "e fechá-lo nos óculos para a gravação. Desligue antes de desinstalar o app.")

        val card = ui.card()
        card.title("Insights do dia")
        insightText = card.text("—")
        generate = card.button("Gerar / atualizar insights") { generate(force = false) }
        card.button("Enviar resumo às lentes", filled = false) {
            if (lastLens.isEmpty()) toast("Gere os insights primeiro") else app.display.show(lastLens, app.settings.displaySettings())
        }
        card.button("Refazer do zero (ignora cache)", filled = false) { generate(force = true) }
        status = ui.hint("")

        ui.title("Buscar no diário")
        search = ui.field("Palavras (ex.: reunião orçamento)")
        ui.button("Buscar em todos os dias", filled = false) { runSearch() }

        ui.title("Linha do tempo")
        timeline = ui.row().apply { orientation = LinearLayout.VERTICAL }

        ui.title("Ações")
        ui.button("Exportar o dia (Markdown)", filled = false) { export() }
        ui.button("Apagar este dia", filled = false) { confirmDeleteDay() }

        ui.title("Configurações do diário")
        val s = app.settings.journalSettings()
        ui.switch("Registrar perguntas e respostas no diário", s.recordInteractions) { app.settings.saveJournal(app.settings.journalSettings().copy(recordInteractions = it)) }
        ui.switch("Resumo automático diário", s.autoDaily) { app.settings.saveJournal(app.settings.journalSettings().copy(autoDaily = it)); DailyInsightWorker.schedule(requireContext()) }
        val time = ui.field("Horário do resumo (HH:MM)", "%02d:%02d".format(s.dailyHour, s.dailyMinute))
        ui.switch("Mostrar o resumo automático nas lentes (se conectado)", s.lensDaily) { app.settings.saveJournal(app.settings.journalSettings().copy(lensDaily = it)) }
        val retention = ui.field("Manter dias (0 = sempre)", s.retentionDays.toString(), number = true)
        val model = ui.field("Modelo para insights (vazio = o mesmo da aba IA)", s.insightsModel)
        ui.button("Salvar configurações do diário", filled = false) {
            val m = Regex("^(\\d{1,2}):(\\d{2})$").find(time.text.toString().trim())
            val h = m?.groupValues?.get(1)?.toIntOrNull(); val mi = m?.groupValues?.get(2)?.toIntOrNull()
            if (h == null || mi == null || h > 23 || mi > 59) { toast("Horário inválido (use HH:MM)"); return@button }
            app.settings.saveJournal(app.settings.journalSettings().copy(dailyHour = h, dailyMinute = mi,
                retentionDays = retention.text.toString().toIntOrNull()?.coerceIn(0, 3650) ?: 0, insightsModel = model.text.toString().trim()))
            DailyInsightWorker.schedule(requireContext()); toast("Configurações salvas")
        }
        ui.hint("Tudo fica no telefone. Para gerar insights, o texto do dia é enviado ao provedor de IA configurado.")
        return ui.scroll()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        load()
        app.ambient.refresh()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                app.ambient.status.collect { s ->
                    val c = s.counts
                    ambientStatus.text = buildString {
                        append(if (s.recording) "● GRAVANDO · " else "Parado · ").append(s.note)
                        if (s.recording) append("\nCapturado: ${s.capturedMs / 60000} min ${(s.capturedMs / 1000) % 60} s")
                        append("\nFila: ${c.pending} fatia(s) (${c.pendingMs / 1000} s de fala) · transcritas ${c.done} (${c.transcribedMs / 60000} min) · falhas ${c.failed}")
                        if (s.error.isNotEmpty()) append("\nÚltimo erro: ${s.error}")
                    }
                    ambientStatus.setTextColor(statusColor(if (s.error.isNotEmpty() && c.failed > 0) false else if (s.recording) true else null))
                }
            }
        }
    }

    private fun startAmbient(glasses: Boolean) {
        if (!app.settings.ambientSettings().consent) {
            AlertDialog.Builder(requireContext()).setTitle("Antes de gravar o ambiente")
                .setMessage("A gravação capta a sua voz e a de outras pessoas por perto. Avise quem estiver com você e respeite a LGPD e as regras do local. " +
                    "O áudio fica no telefone e só os trechos com fala são enviados ao serviço de transcrição configurado (Deepgram/OpenAI). " +
                    "O áudio é apagado após a transcrição (configurável). Você pode pausar a qualquer momento.")
                .setPositiveButton("Entendi e aceito") { _, _ ->
                    app.settings.saveAmbient(app.settings.ambientSettings().copy(consent = true)); startAmbient(glasses)
                }.setNegativeButton("Cancelar", null).show()
            return
        }
        if (!glasses) (activity as? MainActivity)?.requestPermissionsIfNeeded()
        val provider = app.settings.ambientSettings().provider
        if (provider == org.turboio.customai.ambient.AmbientProvider.DEEPGRAM && !app.settings.hasDeepgramKey()) toast("Aviso: informe a chave da Deepgram na aba IA; o áudio ficará na fila até lá")
        (if (glasses) app.ambient.startGlasses() else app.ambient.startPhoneMic())?.let { toast(it) }
    }

    private fun load() {
        dayLabel.text = "Diário · ${Days.label(day)}"
        viewLifecycleOwner.lifecycleScope.launch {
            val (entries, insight) = withContext(Dispatchers.IO) { app.journal.day(day) to app.journal.latestInsight(day) }
            insightText.text = insight?.text ?: if (entries.isEmpty()) "Nenhum registro neste dia." else "Ainda não gerado. Toque em \"Gerar\"."
            lastLens = insight?.let { org.turboio.customai.insights.DayInsight.parse(it.json)?.lens() } ?: ""
            status.text = "${entries.size} registro(s)" + (insight?.let { " · insights de ${Days.time(it.createdAt)} (${it.model})" } ?: "")
            render(entries, showDay = false)
        }
    }

    private fun render(entries: List<Entry>, showDay: Boolean) {
        timeline.removeAllViews()
        val c = Column(requireContext())
        if (entries.isEmpty()) c.hint("Sem registros.")
        for (e in entries) {
            val head = (if (showDay) Days.label(e.day) + " " else "") + Days.time(e.tsStart) + " · " + e.source.label
            val body = if (e.kind == EntryKind.INTERACTION) "Você: ${e.text}\nIA: ${e.answer}" else e.text
            val tv = c.text("$head\n${body.take(1200)}", size = 13.5f)
            tv.setPadding(0, c.dp(6), 0, c.dp(6))
            tv.setOnLongClickListener { confirmDelete(e); true }
        }
        c.hint("Toque longo em um registro para apagá-lo.")
        timeline.addView(c.root)
    }

    private fun generate(force: Boolean) {
        generate.isEnabled = false
        status.text = "Gerando insights…"
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { app.insights.day(day, force) }
                insightText.text = r.text; lastLens = r.insight.lens()
                status.text = if (r.cached) "Sem novidades desde a última geração (cache)." else "✓ Insights gerados (${r.windows} bloco(s))."
            } catch (e: Exception) {
                status.text = "✗ " + TurboApp.friendly(e)
            } finally { generate.isEnabled = true }
        }
    }

    private fun runSearch() {
        val q = search.text.toString().trim()
        if (q.length < 2) { toast("Digite ao menos 2 letras"); return }
        viewLifecycleOwner.lifecycleScope.launch {
            val hits = withContext(Dispatchers.IO) { app.journal.search(q) }
            status.text = "${hits.size} resultado(s) para \"$q\""
            render(hits, showDay = true)
        }
    }

    private fun export() {
        viewLifecycleOwner.lifecycleScope.launch {
            val md = withContext(Dispatchers.IO) { JournalExport.markdown(day, app.journal.day(day), app.journal.latestInsight(day)) }
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/markdown")
                .putExtra(Intent.EXTRA_SUBJECT, "Diário ${Days.label(day)}").putExtra(Intent.EXTRA_TEXT, md), "Exportar diário"))
        }
    }

    private fun confirmDelete(e: Entry) {
        AlertDialog.Builder(requireContext()).setTitle("Apagar registro?").setMessage(e.text.take(200))
            .setPositiveButton("Apagar") { _, _ -> viewLifecycleOwner.lifecycleScope.launch { withContext(Dispatchers.IO) { app.journal.delete(e.id) }; load() } }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun confirmDeleteDay() {
        AlertDialog.Builder(requireContext()).setTitle("Apagar ${Days.label(day)}?").setMessage("Remove todos os registros e insights deste dia.")
            .setPositiveButton("Apagar") { _, _ -> viewLifecycleOwner.lifecycleScope.launch { withContext(Dispatchers.IO) { app.journal.deleteDay(day) }; load() } }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun toast(t: String) = Toast.makeText(requireContext(), t, Toast.LENGTH_SHORT).show()
}
