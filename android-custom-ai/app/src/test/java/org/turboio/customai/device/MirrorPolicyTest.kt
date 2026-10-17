package org.turboio.customai.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.turboio.customai.device.MirrorPolicy.Action
import org.turboio.customai.device.MirrorPolicy.Incoming

class MirrorPolicyTest {
    private var now = 1_000_000L
    private var nav = false
    private val policy = MirrorPolicy("org.turboio.customai", { setOf("com.whatsapp") }, { nav }, { now })
    private fun wa(title: String, text: String) = Incoming("com.whatsapp", "WhatsApp", title, text, ongoing = false, groupSummary = false)

    @Test fun chosenAppsOnlyOnceAndNotTooFast() {
        assertEquals(Action.Card("WhatsApp", "Marina", "Chego em 10 min"), policy.decide(wa("Marina", "Chego em  10 min")))
        now += 1000
        assertEquals("muitas seguidas", (policy.decide(wa("João", "Oi")) as Action.Skip).reason)
        now += 5000
        assertEquals("repetida", (policy.decide(wa("Marina", "Chego em 10 min")) as Action.Skip).reason)
        assertTrue(policy.decide(wa("João", "Oi")) is Action.Card)
        now += 70_000
        assertTrue(policy.decide(wa("Marina", "Chego em 10 min")) is Action.Card)          // repeated after a minute: shown again
        assertEquals("app não escolhido", (policy.decide(Incoming("com.bank", "Banco", "Código", "123456", false, false)) as Action.Skip).reason)
        assertEquals("resumo de grupo", (policy.decide(wa("2 mensagens", "").copy(groupSummary = true)) as Action.Skip).reason)
        assertEquals("próprio app", (policy.decide(Incoming("org.turboio.customai", "Turbo", "x", "y", false, false)) as Action.Skip).reason)
        now += 10_000
        assertTrue(policy.decide(wa("Upload", "50%").copy(ongoing = true)) is Action.Skip)
        assertEquals("WhatsApp", MirrorPolicy.name("com.whatsapp", null)); assertEquals("Foo", MirrorPolicy.name("com.example.foo", null))
    }

    @Test fun mapsNavigationOnlyWhenOnChangesAndThrottlesDistance() {
        val maps = { t: String, s: String -> Incoming(MirrorPolicy.MAPS, "Maps", t, s, ongoing = true, groupSummary = false) }
        assertTrue(policy.decide(maps("200 m", "Rua Augusta")) is Action.Skip)                // navigation off
        nav = true
        assertEquals(Action.Nav("200 m · Rua Augusta"), policy.decide(maps("200 m", "Rua Augusta")))
        now += 2000
        assertEquals("distância (aguardando)", (policy.decide(maps("150 m", "Rua Augusta")) as Action.Skip).reason)
        now += 1000
        assertEquals(Action.Nav("50 m · Av. Paulista"), policy.decide(maps("50 m", "Av. Paulista")))   // new street: right away
        now += 9000
        assertEquals(Action.Nav("20 m · Av. Paulista"), policy.decide(maps("20 m", "Av. Paulista")))
        assertEquals("sem mudança", (policy.decide(maps("20 m", "Av. Paulista")) as Action.Skip).reason)
    }

    @Test fun choiceListOffersInstalledKnownAppsWithDefaults() {
        val installed = setOf("com.whatsapp", "com.nu.production", "com.google.android.gm", "com.google.android.calendar")
        val rows = MirrorPolicy.choices({ it in installed }, seen = setOf("com.example.chat"), chosen = emptySet())
        assertEquals(listOf("com.whatsapp", "com.google.android.gm", "com.nu.production", "com.google.android.calendar", "com.example.chat"), rows.map { it.pkg })
        // Messages, e-mail and banks are suggested; calendar and unknown apps are not.
        assertEquals(setOf("com.whatsapp", "com.google.android.gm", "com.nu.production"), MirrorPolicy.defaults(rows))
        // A saved choice stays listed even if the app can no longer be detected.
        assertTrue(MirrorPolicy.choices({ false }, emptySet(), setOf("com.itau")).any { it.pkg == "com.itau" })
    }

    @Test fun everyCatalogAppIsDeclaredForPackageVisibility() {
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        MirrorPolicy.CATALOG.forEach { assertTrue(it.pkg, manifest.contains("<package android:name=\"${it.pkg}\" />")) }
        assertEquals(MirrorPolicy.CATALOG.size, MirrorPolicy.CATALOG.map { it.pkg }.toSet().size)
    }
}
