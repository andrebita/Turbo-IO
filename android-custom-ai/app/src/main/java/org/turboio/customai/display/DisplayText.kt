package org.turboio.customai.display

import java.text.Normalizer

/** Converts model output into plain text that fits the small lens display. */
object DisplayText {
    private val DETAIL = Regex("\\b(detalh\\w*|explique melhor|explica melhor|resposta completa|mais detalhes|em detalhes)\\b", RegexOption.IGNORE_CASE)

    fun wantsDetail(question: String): Boolean = DETAIL.containsMatchIn(question)

    fun clean(raw: String, stripAccents: Boolean = false): String {
        var text = raw.replace("\r\n", "\n")
        text = text.replace(Regex("```[a-zA-Z0-9]*\\n?"), "")                       // code fences
        text = text.replace(Regex("!\\[([^\\]]*)]\\([^)]*\\)"), "$1")               // images
        text = text.replace(Regex("\\[([^\\]]+)]\\((https?://)?[^)]*\\)"), "$1")    // links -> label
        text = text.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")                  // headings
        text = text.replace(Regex("(?m)^\\s*>\\s?"), "")                           // quotes
        text = text.replace(Regex("(?m)^\\s*[-*+•]\\s+"), "- ")                    // bullets
        text = text.replace(Regex("(?m)^\\s*\\|?\\s*:?-{3,}.*$"), "")              // table rules
        text = text.replace("|", " ")
        text = text.replace(Regex("(\\*\\*|__)(.+?)\\1"), "$2")                    // bold
        text = text.replace(Regex("(?<![\\w*])[*_](\\S(?:.*?\\S)?)[*_](?![\\w*])"), "$1") // italics
        text = text.replace(Regex("`([^`]*)`"), "$1")
        text = text.replace(Regex("~~(.+?)~~"), "$1")
        text = removeEmoji(text)
        text = text.replace(Regex("[ \\t\\u00a0]+"), " ")
        text = text.replace(Regex(" *\\n *"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()
        text = text.replace(Regex("[\\u0000-\\u0008\\u000b\\u000c\\u000e-\\u001f\\u007f]"), "")
        if (stripAccents) text = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return text
    }

    private fun removeEmoji(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val emoji = cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0xFE00..0xFE0F || cp == 0x200D || cp in 0x1F1E6..0x1F1FF
            if (!emoji) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    /**
     * Splits text into lens pages of at most [maxChars] UTF-16 units, preferring sentence and word
     * boundaries and never splitting surrogate pairs.
     */
    fun pages(text: String, maxChars: Int = 90): List<String> {
        require(maxChars in 10..96)
        val words = text.replace('\n', ' ').split(' ').filter { it.isNotEmpty() }
        val pages = ArrayList<String>()
        val current = StringBuilder()
        fun flush() { if (current.isNotEmpty()) { pages.add(current.toString().trim()); current.setLength(0) } }
        for (word in words) {
            var w = word
            while (w.length > maxChars) {               // very long token: hard split on code points
                flush()
                var cut = maxChars
                if (Character.isHighSurrogate(w[cut - 1])) cut--
                pages.add(w.substring(0, cut)); w = w.substring(cut)
            }
            val needed = if (current.isEmpty()) w.length else current.length + 1 + w.length
            if (needed > maxChars) flush()
            if (current.isNotEmpty()) current.append(' ')
            current.append(w)
            if (w.endsWith('.') || w.endsWith('!') || w.endsWith('?')) {
                if (current.length > maxChars * 0.6) flush()
            }
        }
        flush()
        return pages.filter { it.isNotBlank() }
    }
}
