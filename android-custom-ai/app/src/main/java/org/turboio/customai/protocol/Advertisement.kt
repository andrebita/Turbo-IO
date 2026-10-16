package org.turboio.customai.protocol

/** Advertisement filter (port of ProbePolicy.ts). A match is only a candidate, never an identity. */
object Advertisement {
    class Info(val hasRayNeoService: Boolean, val malformed: Boolean, val localName: String)

    fun inspect(bytes: ByteArray): Info {
        var service = false; var malformed = false; var name = ""
        var offset = 0
        while (offset < bytes.size) {
            val length = bytes[offset].toInt() and 0xff
            if (length == 0) break
            if (offset + 1 + length > bytes.size) { malformed = true; break }
            val type = bytes[offset + 1].toInt() and 0xff
            if (type == 0x09 || (type == 0x08 && name.isEmpty())) {
                val decoded = decodeName(bytes.copyOfRange(offset + 2, offset + 1 + length))
                if (decoded.isNotEmpty()) name = decoded
            }
            if (type == 0x02 || type == 0x03) {
                if ((length - 1) % 2 != 0) malformed = true
                var i = offset + 2
                while (i + 1 < offset + 1 + length) {
                    if ((bytes[i].toInt() and 0xff) == 0x1d && (bytes[i + 1].toInt() and 0xff) == 0xb8) service = true
                    i += 2
                }
            }
            if (type == 0x16 && length >= 3 && (bytes[offset + 2].toInt() and 0xff) == 0x1d && (bytes[offset + 3].toInt() and 0xff) == 0xb8) {
                service = true
            }
            offset += 1 + length
        }
        return Info(service, malformed, name)
    }

    fun candidateName(name: String): Boolean = Regex("rayneo|雷鸟", RegexOption.IGNORE_CASE).containsMatchIn(name)

    fun displayName(name: String): String =
        name.replace(Regex("[\\u0000-\\u001f\\u007f-\\u009f\\u200b-\\u200f\\u202a-\\u202e\\u2060-\\u206f]"), "").trim().take(128)

    private fun decodeName(bytes: ByteArray): String {
        if (bytes.isEmpty() || bytes.size > 248) return ""
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return try { displayName(decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()) } catch (_: Exception) { "" }
    }
}
