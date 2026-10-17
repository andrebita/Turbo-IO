package org.turboio.customai.protocol

/**
 * Business IDs and JSON command documents supported by the ORIGINAL Strix OS firmware, as used by the
 * hardware-validated HarmonyOS client (BusinessCommands.ts / VoiceSession.ets / DeviceFeatures.ets).
 *
 * Firmware update (9 / MARS_FOTA) and file transfer (18) are deliberately absent: this app has no
 * code path that can send OTA data to the glasses.
 */
object Business {
    const val HANDSHAKE = 0x10
    const val AI_ASSISTANT = 13   // wake, recorder, audio, transcript, answer, EOF, exit
    const val LAUNCHER = 15       // general status / battery
    const val SUBTITLE = 19       // temporary text display session
    const val RECORDING = 14      // glasses-menu recording (phase-3 probe)
    const val TODO = 22           // native To-do app of the glasses (schedule/todo service)

    /** Outbound allow-list: business -> envelope types we may send. Everything else is refused. */
    val OUTBOUND: Map<Int, Set<Int>> = mapOf(
        // 162 = LifeLog A2 (start), 166 = A6 (exit), 168 = A8 (realtime page flag)
        AI_ASSISTANT to setOf(2, 5, 7, 12, 32, 162, 166, 168),
        // 4 = read general settings; 5 = display_config / crown_config with evidence-backed values only;
        // 20 = life_log_guide / life_log_switch (see contentAllowed)
        LAUNCHER to setOf(1, 4, 5, 20),
        SUBTITLE to setOf(3, 5, 7),
        // enable(14), start/ack(1), stop/ack(4), acks(8, 9, 11) — no delete/format/file commands
        RECORDING to setOf(1, 4, 8, 9, 11, 14),
        // 2 = add/modify ONE to-do (eventType 1); no batch, delete, schedule or binary types
        TODO to setOf(2),
    )

    fun outboundAllowed(business: Int, type: Int): Boolean = OUTBOUND[business]?.contains(type) == true

    private val LAUNCHER_COMMANDS = setOf("life_log_guide", "life_log_switch")

    /** Second gate on the JSON body for generic command channels. */
    fun contentAllowed(business: Int, type: Int, json: String): Boolean {
        val cmd = Regex("^\\{\"cmd\":\"([a-z_]+)\"").find(json)?.groupValues?.get(1)
        if (business == LAUNCHER && type == 20) return cmd in LAUNCHER_COMMANDS
        if (business == LAUNCHER && type == 4) return cmd == "request_general_settings"
        if (business == LAUNCHER && type == 5) return settingAllowed(cmd, json)
        if (business == TODO) return runCatching { org.json.JSONObject(json) }.getOrNull()?.let { j ->
            j.optInt("eventType") == 1 && j.optLong("eventID") > 0 && j.optString("title").length in 1..60 && j.optInt("status", -1) in 0..1
        } ?: false
        return true
    }

    /** Settings writes accepted only with the value ranges the official app is known to use (RayNeoCompanion evidence). */
    private fun settingAllowed(cmd: String?, json: String): Boolean {
        val data = runCatching { org.json.JSONObject(org.json.JSONObject(json).getJSONObject("payload").getString("data")) }.getOrNull() ?: return false
        val keys = data.keys().asSequence().toSet()
        return when (cmd) {
            "display_config" -> keys == setOf("height", "distance") && data.optInt("height") in setOf(1, 3, 5) && data.optInt("distance") in setOf(1, 2)
            "crown_config" -> keys == setOf("double", "longPress") && data.optInt("double", -1) in setOf(0, 8) && data.optInt("longPress", -1) in 0..64
            else -> false
        }
    }
}

object Json {
    /** Equivalent to JavaScript JSON.stringify(string) (no '/' escaping, lowercase \\u escapes). */
    fun quote(text: String): String {
        val sb = StringBuilder(text.length + 2).append('"')
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\b' -> sb.append("\\b")
                c == '\u000c' -> sb.append("\\f")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u%04x".format(c.code))
                Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> {
                    sb.append(c).append(text[i + 1]); i++
                }
                Character.isSurrogate(c) -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
            i++
        }
        return sb.append('"').toString()
    }
}

object Commands {
    private val SID = Regex("^[a-f0-9]{32}$")
    private val CONTROL = Regex("[\\u0000-\\u0008\\u000b\\u000c\\u000e-\\u001f]")

    private fun sid(value: String): String {
        if (!SID.matches(value)) throw ProtocolException("invalid-session")
        return Json.quote(value)
    }

    private fun text(value: String, max: Int): String {
        if (value.isBlank() || value.length > max || CONTROL.containsMatchIn(value)) throw ProtocolException("invalid-text")
        return Json.quote(value)
    }

    /** LAUNCHER(15) read-only status query; exact bytes of the validated iOS/Harmony request. */
    fun generalStatusQuery(): ByteArray {
        val json = "{\"cmd\":\"request_general_status\",\"payload\":{\"data\":\"\",\"mode\":0,\"value\":0}}".toByteArray(Charsets.US_ASCII)
        val out = ByteArray(json.size + 8)
        byteArrayOf(8, 1, 16, 1, 26, json.size.toByte()).copyInto(out)
        json.copyInto(out, 6)
        out[json.size + 6] = 34; out[json.size + 7] = 0
        return out
    }

    // --- SUBTITLE(19): temporary display without microphone ---
    fun displayStart(sid: String): String =
        "{\"sid\":${sid(sid)},\"force\":false,\"scope\":\"temporary\",\"config\":{\"font_size\":2,\"content_width\":100,\"max_lines\":5,\"position\":\"center\",\"is_display\":true,\"straight_view\":\"original\"}}"

    fun displayText(sid: String, value: String): String =
        "{\"sid\":${sid(sid)},\"mode\":3,\"status\":0,\"content\":{\"source_transcript\":${text(value, 96)}}}"

    fun displayStop(sid: String): String = "{\"sid\":${sid(sid)},\"reason_code\":10,\"text\":\"\"}"

    // --- AI_ASSISTANT(13): voice turn ---
    fun recorder(start: Boolean): String = if (start) "{\"rc\":1}" else "{\"rc\":2}"
    const val EXIT = "{\"rc\":1}"
    const val EOF = "{}"

    fun transcript(value: String, final: Boolean): String {
        if (value.isEmpty() || value.length > 100) throw ProtocolException("transcript-size")
        return "{\"text\":${Json.quote(value)},\"final\":$final}"
    }

    fun answerChunk(value: String, sid: String, query: String, timestamp: Long): String {
        if (!SID.matches(sid) || value.isEmpty() || value.length > 24 || query.length > 16 || timestamp < 0) {
            throw ProtocolException("answer-input")
        }
        return "{\"sub\":\"workflow\",\"vendor\":\"turboio\",\"uuid\":\"$sid\",\"sid\":\"$sid\",\"round\":-1,\"timestamp\":$timestamp," +
            "\"query\":${Json.quote(query)},\"domain\":\"chat\",\"intent\":\"chat\",\"payload\":{},\"offline\":false," +
            "\"answer\":{\"text\":${Json.quote(value)},\"isFinal\":false}}"
    }

    /** Splits by UTF-16 units without breaking surrogate pairs (port of voiceChunks). */
    fun chunks(value: String, limit: Int): List<String> {
        if (limit < 2 || limit > 100) throw ProtocolException("chunk-limit")
        val out = ArrayList<String>()
        val chunk = StringBuilder()
        var i = 0
        while (i < value.length) {
            val cp = value.codePointAt(i)
            val len = Character.charCount(cp)
            if (chunk.length + len > limit) { out.add(chunk.toString()); chunk.setLength(0) }
            chunk.appendCodePoint(cp)
            i += len
        }
        if (chunk.isNotEmpty()) out.add(chunk.toString())
        return out
    }
}
