package org.turboio.customai.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import org.turboio.customai.BuildConfig
import org.turboio.customai.R

/** Tools, diagnostics and the test suites. */
class MoreFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        val ui = Column(requireContext())
        val main = activity as? MainActivity
        ui.header("Mais", "Ferramentas, testes e diagnóstico")
        ui.section("Ferramentas")
        ui.nav(R.drawable.ic_chat, "Perguntar à IA", "Pergunta escrita, resposta nas lentes") { main?.open(TestFragment()) }
        ui.nav(R.drawable.ic_prompter, "Teleprompter", "Roteiro colado ou de um arquivo .txt") { main?.open(TestFragment()) }
        ui.section("Testes e diagnóstico")
        ui.nav(R.drawable.ic_flask, "Bateria de recursos", "Testa cada função nos óculos e exporta um arquivo") { main?.open(DiagnosticsFragment()) }
        ui.nav(R.drawable.ic_diag, "Diagnóstico", "Estado da conexão, log técnico, bateria de áudio") { main?.open(DiagnosticsFragment()) }
        ui.section("Sobre")
        ui.hint("Turbo IO IA ${BuildConfig.VERSION_NAME} · app independente para RayNeo iO com firmware original. " +
            "Não altera o firmware nem o app oficial.")
        return ui.scroll()
    }
}
