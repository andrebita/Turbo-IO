package org.turboio.customai.ui

import android.widget.FrameLayout
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import org.turboio.customai.R

/** JVM screenshots of the redesigned screens (sample data; no glasses or Android device needed). */
class SmokeShot {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, theme = "Theme.TurboIO")

    private val none = HomeActions({}, {}, {}, {})

    private fun tiles(connected: Boolean) = listOf(
        Tile(R.drawable.ic_mic, "Assistente de voz", "Ligado · Gemini", true) {},
        Tile(R.drawable.ic_record, "Gravar o dia", "Parado", false) {},
        Tile(R.drawable.ic_prompter, "Teleprompter", "Pronto", false) {},
        Tile(R.drawable.ic_bell, "Notificações", if (connected) "WhatsApp e mais 4" else "Desligado", connected) {},
        Tile(R.drawable.ic_todo, "To-do nos óculos", "Ligado", true) {},
        Tile(R.drawable.ic_lookup, "Olhar para cima", "Ligado · 20°", true) {},
        Tile(R.drawable.ic_weather, "Tempo", "Belo Horizonte 25°", false) {},
        Tile(R.drawable.ic_flask, "Bateria de testes", "12 de 20", false) {},
    )

    @Test fun homeConnected() = paparazzi.snapshot(HomeView.build(paparazzi.context, HomeModel("RayNeo iO", true, "Conectado", 78, "Strix OS 1.0.5.5",
        tiles(true), "3 pendências", listOf("Ligar para a Marina · hoje 15:00", "Enviar proposta · amanhã")), none))

    @Test fun homeDisconnected() = paparazzi.snapshot(HomeView.build(paparazzi.context, HomeModel("RayNeo iO", false, "Desconectado", null, "Óculos salvo · toque em Conectar",
        tiles(false), "Sem pendências", listOf("A memória cria pendências a partir das suas conversas.")), none))

    @Test fun bottomBar() {
        val nav = MainActivity.bottomNav(paparazzi.context).apply { selectedItemId = R.id.nav_home }
        paparazzi.snapshot(android.widget.LinearLayout(paparazzi.context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(HomeView.build(paparazzi.context, HomeModel("RayNeo iO", true, "Conectado", 78, "Strix OS 1.0.5.5", tiles(true), "3 pendências",
                listOf("Ligar para a Marina · hoje 15:00")), none), android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
            addView(nav, android.widget.LinearLayout.LayoutParams(-1, -2))
        })
    }

    @Test fun components() {
        val ui = Column(paparazzi.context)
        ui.header("Óculos", "Conexão e ajustes da tela")
        ui.section("Tela")
        val c = ui.card()
        c.title("Altura e distância")
        c.hint("O app lê os valores atuais antes de mudar e confere depois.")
        c.spinner(listOf(1, 3, 5), 0, { "Altura $it" }) {}
        c.buttons("Ligar" to {}, "Desligar" to {})
        c.button("Aplicar") {}
        ui.section("Conexão")
        ui.nav(R.drawable.ic_glasses_line, "Procurar óculos", "Modo de pareamento: segure o botão 5 s") {}
        ui.nav(R.drawable.ic_diag, "Diagnóstico", "Log, bateria de testes") {}
        val f = ui.card()
        f.field("Chave da API (vazio = manter)", password = true)
        f.switch("Manter conexão com a tela desligada", true) {}
        f.chip("Conectado", true)
        paparazzi.snapshot(FrameLayout(paparazzi.context).apply { addView(ui.scroll()) })
    }
}
