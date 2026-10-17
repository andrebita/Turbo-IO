package org.turboio.customai.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.turboio.customai.diag.Redactor

class DisplayTextTest {
    @Test fun stripsMarkdownAndEmoji() {
        val raw = "## Resposta\n**Brasília** é a _capital_ 🇧🇷😀.\n- item um\n* item dois\n[fonte](https://x.y)\n```kotlin\ncode\n```"
        val clean = DisplayText.clean(raw)
        assertEquals("Resposta\nBrasília é a capital .\n- item um\n- item dois\nfonte\ncode", clean)
        assertFalse(clean.contains("**")); assertFalse(clean.contains("#"))
    }

    @Test fun optionalAccentRemoval() {
        assertEquals("Acao, coracao, cafe e pao", DisplayText.clean("Ação, coração, café e pão", stripAccents = true))
        assertEquals("Ação", DisplayText.clean("Ação"))
    }

    @Test fun pagesRespectLensLimit() {
        val text = "A capital do Brasil é Brasília, inaugurada em 1960. Ela foi planejada por Lúcio Costa e Oscar Niemeyer. " +
            "Fica no Planalto Central e abriga os três poderes. " + "x".repeat(130)
        val pages = DisplayText.pages(text, 90)
        assertTrue(pages.all { it.length <= 90 })
        assertEquals(text.replace(" ", ""), pages.joinToString("").replace(" ", ""))
        assertTrue(pages.size >= 4)
        assertEquals(listOf("😀".repeat(5)), DisplayText.pages("😀".repeat(5), 10))
        assertTrue(DisplayText.pages("😀".repeat(30), 11).all { it.length <= 11 && !Character.isHighSurrogate(it.last()) })
    }

    @Test fun detailIntent() {
        assertTrue(DisplayText.wantsDetail("Explique melhor a fotossíntese"))
        assertTrue(DisplayText.wantsDetail("me dê uma resposta completa"))
        assertFalse(DisplayText.wantsDetail("Que horas são?"))
    }

    @Test fun redactorMasksSecrets() {
        val text = "Authorization: Bearer sk-abcdefghijklmnop x-api-key: sk-ant-api03-ZZZZZZZZZZ key=AIzaSyA1234567890123456789012345 " +
            "mac 00:11:22:AA:BB:CC url https://h/x?key=abc123"
        val out = Redactor.redact(text)
        listOf("abcdefghijklmnop", "ZZZZZZZZZZ", "AIzaSyA1234567890123456789012345", "00:11:22:AA:BB:CC", "abc123").forEach { assertFalse(it, out.contains(it)) }
        assertEquals("••••yz", Redactor.mask("sk-abcdefxyz"))
    }
}
