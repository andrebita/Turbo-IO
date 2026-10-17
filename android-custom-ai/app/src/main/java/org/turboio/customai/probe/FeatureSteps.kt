package org.turboio.customai.probe

import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.turboio.customai.TurboApp
import org.turboio.customai.device.Teleprompter
import org.turboio.customai.diag.DiagnosticLog
import org.turboio.customai.display.DisplaySettings
import kotlin.coroutines.resume

/**
 * The feature test steps. Each one does the real action through the same code the app uses, and reports what the
 * phone saw; the user then says whether it really appeared on the lenses. Nothing here changes the firmware; settings
 * changed by a step (dashboard type, test card, test To-do item) are put back by the same step.
 */
object FeatureSteps {
    private fun ready(app: TurboApp): String? = if (app.session.isReady) null else "Óculos não conectados: conecte na aba Conexão e repita"

    private suspend fun show(app: TurboApp, text: String, settings: DisplaySettings): String =
        suspendCancellableCoroutine { cont -> app.display.show(text, settings) { e -> if (cont.isActive) cont.resume(e ?: "exibido") } }

    /** Log lines (already redacted) written since [since] whose text contains any of [words]. */
    private fun logSince(since: Long, vararg words: String): List<String> =
        DiagnosticLog.snapshot().filter { it.time >= since && words.any { w -> it.message.contains(w, ignoreCase = true) } }.map { it.message }

    fun all(app: TurboApp): List<FeatureStep> = listOf(
        FeatureStep("F1", "Texto nas lentes (3 formatos)",
            "O app mostra o texto de teste três vezes: A (formato antigo, repetido a cada 2 s — o novo padrão), B (formato antigo, enviado uma vez) " +
                "e C (formato do app oficial). Anote quais apareceram nas lentes e se ficaram na tela.",
            "Quais letras apareceram (A, B, C)? Os acentos (ã, ç, é) apareceram certos? Escreva na observação.", timeoutSeconds = 120) { run ->
            ready(app)?.let { return@FeatureStep it }
            val base = app.settings.displaySettings().copy(fontSize = 2, position = "center", maxLines = 5, lingerSeconds = 6)
            val variants = listOf(Triple("A", "legacy", true), Triple("B", "legacy", false), Triple("C", "official", true))
            val results = variants.map { (letter, format, resend) ->
                run.cue("Formato $letter: olhe as lentes")
                "$letter ($format${if (resend) ", repetido" else ", uma vez"}): " +
                    show(app, "$letter: olá! Ação, coração, café e pão.", base.copy(textFormat = format, resend = resend))
            }
            results.joinToString("\n") + "\nFormato em uso no app: ${app.settings.displaySettings().textFormat} (troque na aba IA)"
        },
        FeatureStep("F2", "Tamanho e posição do texto",
            "O app mostra o mesmo texto com letra GRANDE no TOPO e depois com letra PEQUENA na BASE.",
            "O texto mudou de tamanho e de posição entre as duas exibições?") { run ->
            ready(app)?.let { return@FeatureStep it }
            val t0 = System.currentTimeMillis()
            val base = app.settings.displaySettings().copy(lingerSeconds = 5)
            run.cue("Olhe as lentes: letra grande no topo")
            val big = show(app, "Letra grande no topo", base.copy(fontSize = 3, position = "top", maxLines = 3))
            run.cue("Agora: letra pequena na base")
            val small = show(app, "Letra pequena na base", base.copy(fontSize = 1, position = "bottom", maxLines = 5))
            val refused = logSince(t0, "recusado")
            "Grande/topo: $big\nPequena/base: $small\n" +
                if (refused.isEmpty()) "Os óculos aceitaram os dois layouts" else "Recusados (voltou ao padrão):\n" + refused.joinToString("\n")
        },
        FeatureStep("F3", "Pergunta à IA nas lentes",
            "O app pergunta ao seu provedor de IA “Qual é a capital do Brasil?” e mostra a resposta.",
            "A resposta apareceu nas lentes, em português?") { _ ->
            ready(app)?.let { return@FeatureStep it }
            val r = try { app.askAi("Qual é a capital do Brasil? Responda em uma frase.", record = false) }
                catch (e: Exception) { return@FeatureStep "IA falhou: ${TurboApp.friendly(e)}" }
            "IA: ${r.provider.label}/${r.model} em ${r.latencyMs} ms: “${r.text.take(120)}”\nExibição: " + show(app, r.text, app.settings.displaySettings())
        },
        FeatureStep("F4", "Assistente de voz (gesto nos óculos)",
            "Depois de Iniciar, acione o assistente nos óculos do jeito que você usa no dia a dia (toque longo no botão, gesto ou “Hey RayNeo”) " +
                "e pergunte “Qual é a capital da França?”. Até 90 s. Escreva na observação qual gesto você usou. Use a voz pela Deepgram (aba IA).",
            "Os óculos ouviram a pergunta e a resposta da sua IA apareceu nas lentes?", timeoutSeconds = 120) { run ->
            ready(app)?.let { return@FeatureStep it }
            voiceRound(app, run, "Acione o assistente nos óculos e pergunte")
        },
        FeatureStep("F5", "“Hey RayNeo”",
            "Só se você já ativou “Hey RayNeo” (aba Conexão). Diga “Hey RayNeo” e pergunte “Quanto é dois mais dois?”. Até 90 s. Se não ativou, pule.",
            "Os óculos acordaram com “Hey RayNeo” e a resposta da sua IA apareceu?", timeoutSeconds = 120) { run ->
            ready(app)?.let { return@FeatureStep it }
            voiceRound(app, run, "Diga “Hey RayNeo” e pergunte")
        },
        FeatureStep("F6", "Alerta como notificação nativa",
            "O app envia um cartão de notificação nativo dos óculos (como os lembretes).",
            "Apareceu o cartão de notificação “Teste Turbo IO” nos óculos?") { _ ->
            ready(app) ?: "Cartão: " + app.nativeNotifier.show("Teste Turbo IO", "Alerta nativo de teste do Turbo IO").label
        },
        FeatureStep("F7", "Notificações do celular nos óculos",
            "O app confere o acesso às notificações, envia um cartão simulado “WhatsApp” e depois espera 60 s por uma notificação real " +
                "de um app escolhido (peça para alguém te mandar uma mensagem, ou mande do computador).",
            "O cartão simulado apareceu? E a mensagem real (se chegou)?", timeoutSeconds = 120) { run ->
            ready(app)?.let { return@FeatureStep it }
            val access = app.packageName in NotificationManagerCompat.getEnabledListenerPackages(app)
            val apps = app.mirrorAppsInEffect()
            val simulated = app.nativeNotifier.show("Marina (teste)", "Mensagem simulada do teste do Turbo IO", "WhatsApp").label
            val before = app.mirroredCards
            if (access && app.settings.mirrorOn && apps.isNotEmpty()) run.countdown(60, "Aguardando uma notificação real")
            val real = app.mirroredCards - before
            "Acesso às notificações: ${if (access) "liberado" else "NÃO liberado"} · envio ligado: ${app.settings.mirrorOn} · apps em uso: ${apps.size}${if (app.settings.mirrorAppsChosen) "" else " (sugeridos)"}\n" +
                "Cartão simulado: $simulated\nNotificações reais entregues: $real" +
                if (!access || !app.settings.mirrorOn || apps.isEmpty()) " (não esperou: falta liberar o acesso, ligar o envio ou escolher apps)" else ""
        },
        FeatureStep("F8", "Navegação do Google Maps",
            "O app mostra uma linha de navegação simulada. Depois espera 60 s: se puder, inicie uma rota a pé no Google Maps.",
            "A linha “↱ 200 m · Rua de Teste” apareceu? Com o Maps, as manobras apareceram?", timeoutSeconds = 120) { run ->
            ready(app)?.let { return@FeatureStep it }
            val simulated = show(app, "↱ 200 m · Rua de Teste", app.settings.displaySettings().copy(lingerSeconds = 5, pageSeconds = 5))
            val before = app.navLines
            if (app.settings.navOn) run.countdown(60, "Inicie uma rota no Google Maps (opcional)")
            "Simulada: $simulated\nNavegação ligada: ${app.settings.navOn} · linhas reais do Maps: ${app.navLines - before}"
        },
        FeatureStep("F9", "Tempo na tela inicial",
            "O app busca o tempo da sua cidade (ou São Paulo, se não houver) e envia para a tela inicial dos óculos.",
            "O tempo (temperatura e ícone) apareceu certo na tela inicial dos óculos?") { _ ->
            ready(app)?.let { return@FeatureStep it }
            val city = app.settings.weatherCity.ifBlank { "São Paulo" }
            val w = try { withContext(Dispatchers.IO) { org.turboio.customai.device.OpenMeteo.now(city) } }
                catch (e: Exception) { return@FeatureStep "Open-Meteo falhou: ${e.message ?: e.javaClass.simpleName}" }
            app.weather.send(w)
        },
        FeatureStep("F10", "To-do dos óculos",
            "O app envia o item “Teste Turbo IO · pode marcar” ao To-do dos óculos. Abra o To-do nos óculos e marque o item como feito (até 90 s). " +
                "Ao final o app marca o item como feito, para não ficar sobrando.",
            "O item apareceu no To-do dos óculos? Marcar lá foi percebido pelo app?", timeoutSeconds = 150) { run ->
            ready(app)?.let { return@FeatureStep it }
            val id = app.glassesTodo.testWireId()
            app.glassesTodo.sendTest(id, "Teste Turbo IO · pode marcar", 0)?.let { return@FeatureStep "Envio falhou: $it" }
            var marked = -1
            app.glassesTodo.wireListener = { w, st -> if (w == id) marked = st }
            try {
                for (left in 90 downTo 1) { if (marked == 1) break; run.cue("Abra o To-do nos óculos e marque o item · $left s"); delay(1000) }
            } finally { app.glassesTodo.wireListener = null }
            val cleanup = if (marked == 1) "já marcado nos óculos" else app.glassesTodo.sendTest(id, "Teste Turbo IO · pode marcar", 1) ?: "marcado como feito pelo app"
            "Item enviado. Marcação nos óculos: ${if (marked == 1) "recebida ✓" else "não recebida"}. Limpeza: $cleanup"
        },
        FeatureStep("F11", "Reação ao abrir app nos óculos",
            "Abra o To-do pelo menu dos óculos em até 60 s. O app deve perceber e sincronizar as pendências.",
            "Você abriu o To-do? (o resultado do app diz se ele percebeu)", timeoutSeconds = 90) { run ->
            ready(app)?.let { return@FeatureStep it }
            run.cue("Abra o To-do nos óculos")
            val seen = ArrayList<String>()
            val opened = withTimeoutOrNull(60_000) { app.openedOnGlasses.first { seen.add(it); it == "com.rayneo.liteos.todo" } }
            if (opened != null) "Percebido: To-do aberto nos óculos ✓" else "Não percebido em 60 s. Apps vistos: ${seen.joinToString().ifEmpty { "nenhum" }}"
        },
        FeatureStep("F12", "Teleprompter pelo celular",
            "O app envia um roteiro curto ao teleprompter dos óculos e, quando estiver pronto, inicia, pausa, continua, muda a velocidade e sai sozinho.",
            "O roteiro apareceu e rolou nas lentes? Pausar/continuar/velocidade funcionaram? Saiu no fim?", timeoutSeconds = 150) { run ->
            ready(app)?.let { return@FeatureStep it }
            teleprompter(app, run)
        },
        FeatureStep("F13", "Teleprompter pelo menu dos óculos (observação)",
            "Depois de Iniciar, abra o Teleprompter pelo menu dos óculos e tente usar por uns 40 s. O app só registra o que os óculos pedem " +
                "(para entendermos o erro que aparece). Anote a mensagem de erro, se houver.",
            "Qual mensagem apareceu nos óculos? (escreva na observação)", timeoutSeconds = 90) { run ->
            ready(app)?.let { return@FeatureStep it }
            run.countdown(45, "Abra o Teleprompter no menu dos óculos")
            "Observação concluída: o tráfego dos óculos está no arquivo trafego.txt desta etapa"
        },
        FeatureStep("F14", "Painel: leitura",
            "O app lê a configuração do painel dos óculos (nada é alterado).",
            "A lista de cartões abaixo confere com o seu painel?") { _ ->
            ready(app)?.let { return@FeatureStep it }
            val (snap, why) = app.dashboard.read()
            snap?.describe() ?: why
        },
        FeatureStep("F15", "Painel: cartão de pendências",
            "O app instala o cartão de pendências no painel, espera 25 s para você olhar e remove o cartão.",
            "O cartão apareceu no painel e depois sumiu? Os outros cartões continuaram iguais?", timeoutSeconds = 150) { run ->
            ready(app)?.let { return@FeatureStep it }
            val shown = app.refreshDashboardCard(force = true)
            run.countdown(25, "Olhe o painel dos óculos: deve ter o cartão do Turbo IO")
            val removed = if (app.settings.dashboardCard) "mantido (a atualização automática está ligada)" else app.dashboard.removeCard()
            "Instalação: $shown\nRemoção: $removed"
        },
        FeatureStep("F16", "Painel: tipo simples/completo",
            "O app troca o tipo do painel, espera 25 s para você olhar e volta ao tipo original.",
            "O painel mudou de aparência e depois voltou ao normal?", timeoutSeconds = 150) { run ->
            ready(app)?.let { return@FeatureStep it }
            val (snap, why) = app.dashboard.read()
            val current = snap?.simple ?: return@FeatureStep "Leitura: ${snap?.let { "os óculos não informam o tipo (simple_mode)" } ?: why}"
            val changed = app.dashboard.setSimpleMode(!current) { if (app.settings.dashboardSimpleOriginal == null) app.settings.dashboardSimpleOriginal = it }
            try {
                run.countdown(25, "Olhe o painel: deve estar no tipo ${if (current) "completo" else "simples"}")
            } finally {
                // Runs even when the step is stopped: the dashboard always goes back to the type it had.
                val back = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { app.dashboard.setSimpleMode(current) }
                if (back.startsWith("Painel agora") || back.startsWith("O painel já")) app.settings.dashboardSimpleOriginal = null
                DiagnosticLog.i("PAINEL", "F16 volta: $back")
            }
            "Tipo lido: ${if (current) "simples" else "completo"}\nTroca: $changed\nVolta ao tipo original: ver log (PAINEL)"
        },
        FeatureStep("F17", "Aceno e balanço de cabeça",
            "Aparecem dois cartões nos óculos. No primeiro, ACENE (sim). No segundo, BALANCE a cabeça (não). Até 40 s cada.",
            "Os cartões apareceram e os gestos foram reconhecidos como no resultado?", timeoutSeconds = 120) { run ->
            ready(app)?.let { return@FeatureStep it }
            run.cue("ACENE com a cabeça (sim)")
            val yes = app.suggestions.ask("Teste: acene para SIM", "Acene com a cabeça", "Teste", 40_000)
            run.cue("Agora BALANCE a cabeça (não)")
            val no = app.suggestions.ask("Teste: balance para NÃO", "Balance a cabeça", "Teste", 40_000)
            "Primeiro cartão: ${yes.label} (esperado: aceno)\nSegundo cartão: ${no.label} (esperado: balanço)"
        },
        FeatureStep("F18", "Ajustes da tela (leitura)",
            "O app lê os ajustes de altura, distância, duplo toque e palavra de ativação (nada é alterado).",
            "Os valores abaixo conferem com o app oficial / o que você vê?") { _ ->
            ready(app)?.let { return@FeatureStep it }
            val error = suspendCancellableCoroutine<String?> { cont -> app.glassesSettings.read { e -> if (cont.isActive) cont.resume(e) } }
            val c = app.glassesSettings.state.value.config
            (error?.let { "Leitura: $it\n" } ?: "") +
                "Altura ${c.height ?: "?"} · distância ${c.distance ?: "?"} · duplo toque ${c.crownDouble ?: "?"} · toque longo ${c.crownLongPress ?: "?"} · palavra de ativação ${c.wakeup ?: "?"}" +
                "\nValores lidos: ${app.glassesSettings.state.value.details}"
        },
        FeatureStep("F20", "Olhar para cima: ângulo",
            "O app muda o ângulo de “olhar para cima acende a tela” para 35°, dá 40 s para você testar (deixe a tela apagar e olhe para cima) " +
                "e volta ao ângulo e ao estado que estavam.",
            "Com 35° foi preciso levantar mais a cabeça do que antes? A tela acendeu? (escreva na observação)", timeoutSeconds = 120) { run ->
            ready(app)?.let { return@FeatureStep it }
            suspend fun wake(headup: Boolean? = null, degree: Int? = null) =
                suspendCancellableCoroutine<String?> { cont -> app.glassesSettings.setWake(headup, degree) { e -> if (cont.isActive) cont.resume(e) } }
            val readError = suspendCancellableCoroutine<String?> { cont -> app.glassesSettings.read { e -> if (cont.isActive) cont.resume(e) } }
            val before = app.glassesSettings.state.value.config
            if (!before.hasWake) return@FeatureStep "Os óculos não informaram wakeupConfig${readError?.let { " ($it)" } ?: ""}\nValores lidos: ${app.glassesSettings.state.value.details}"
            val test = if (before.wakeDegree == 35) 25 else 35
            try {
                val on = if (before.wakeHeadup == 1) "já estava ligado" else wake(headup = true) ?: "ligado pelo app"
                val change = wake(degree = test) ?: "ângulo $test° aplicado (relido)"
                run.countdown(40, "Ângulo $test°: deixe a tela apagar e olhe para cima")
                return@FeatureStep "Antes: ${if (before.wakeHeadup == 1) "ligado" else "desligado"}, ${before.wakeDegree}°\nLigar: $on\nMudar: $change"
            } finally {
                // Runs even when the step is stopped: the angle and switch go back to what they were.
                val back = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    wake(headup = before.wakeHeadup == 1, degree = before.wakeDegree) ?: "restaurado (${before.wakeDegree}°)"
                }
                DiagnosticLog.i("AJUSTES", "F20 volta: $back")
            }
        },
        FeatureStep("F19", "Diário: gravar pelos óculos",
            "O app grava 40 s pelos microfones dos óculos para o diário. Converse ou leia algo em voz alta. A transcrição chega ao Diário em alguns minutos. " +
                "Antes, aceite o aviso de consentimento em Diário → Gravação ambiente (uma vez só).",
            "No Diário, a conversa apareceu transcrita (confira daqui a uns minutos)?", timeoutSeconds = 120) { run ->
            ready(app)?.let { return@FeatureStep it }
            app.ambient.startGlasses()?.let { return@FeatureStep "Não iniciou: $it" }
            try { run.countdown(40, "Gravando pelos óculos: converse") } finally { app.ambient.stop("fim do teste") }
            val s = app.ambient.status.value
            "Áudio capturado: ${s.capturedMs / 1000} s · trechos: ${s.segments}\n${s.note}${if (s.error.isNotEmpty()) "\nErro: ${s.error}" else ""}"
        },
    )

    private suspend fun voiceRound(app: TurboApp, run: StepRun, what: String): String {
        val before = app.voice.state.value.rounds
        val done = withTimeoutOrNull(90_000) {
            launch { for (left in 90 downTo 1) { run.cue("$what · $left s"); delay(1000) } }.let { ticker ->
                try { app.voice.state.first { it.rounds > before } } finally { ticker.cancel() }
            }
        }
        val v = app.voice.state.value
        return (if (done == null) "Nenhuma rodada de voz concluída em 90 s\n" else "Rodada concluída ✓\n") +
            "Estado: ${v.phase} · ${v.note}\nTranscrição: ${v.transcript.ifEmpty { "—" }}\nResposta: ${v.answer.take(160).ifEmpty { "—" }}" +
            "\nÁudio dos óculos: ${v.audioPackets} pacotes / ${v.audioMs} ms" + if (v.lastError.isNotEmpty()) "\nÚltimo erro: ${v.lastError}" else ""
    }

    private suspend fun teleprompter(app: TurboApp, run: StepRun): String {
        val t = app.teleprompter
        val history = ArrayList<String>()
        val watcher = app.scope.launch { t.state.collect { s -> val line = "${s.phase.label}: ${s.note}"; if (history.lastOrNull() != line) history.add(line) } }
        try {
            if (t.active) t.stop()
            val script = "Teste do teleprompter Turbo IO. Esta é a primeira linha do roteiro. A segunda linha continua o texto. " +
                "Agora o texto deve rolar devagar pelas lentes. Fim do teste."
            t.prepare(script, 120)?.let { return "Preparo: $it" }
            run.cue("Enviando o roteiro aos óculos")
            val ready = withTimeoutOrNull(60_000) { t.state.first { it.phase == Teleprompter.Phase.READY || it.phase == Teleprompter.Phase.ERROR || it.phase == Teleprompter.Phase.IDLE } }
            if (ready?.phase != Teleprompter.Phase.READY) return "Roteiro não ficou pronto: ${ready?.note ?: "sem resposta em 60 s"}\n\nSequência:\n" + history.joinToString("\n")
            suspend fun step(type: Int, label: String, seconds: Int, speed: Int = t.state.value.speed) {
                t.control(type, speed)?.let { history.add("✗ $label: $it"); return }
                withTimeoutOrNull(10_000) { t.state.first { it.phase != Teleprompter.Phase.CONTROLLING } }
                run.countdown(seconds, label)
            }
            step(3, "Rolando", 8)
            step(4, "Pausado", 4)
            step(5, "Continuando", 4)
            step(7, "Velocidade maior", 4, 200)
            t.stop()
            withTimeoutOrNull(12_000) { t.state.first { it.phase == Teleprompter.Phase.IDLE } }
            return "Estado final: ${t.state.value.phase.label}\n\nSequência:\n" + history.joinToString("\n")
        } finally {
            watcher.cancel()
            if (t.active) t.stop()
        }
    }
}
