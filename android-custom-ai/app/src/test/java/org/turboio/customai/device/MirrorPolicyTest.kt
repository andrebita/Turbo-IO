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
}
