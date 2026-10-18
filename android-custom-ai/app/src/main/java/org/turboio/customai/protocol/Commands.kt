package org.turboio.customai.protocol

/**
 * Business IDs and JSON command documents supported by the ORIGINAL Strix OS firmware, as used by the
 * hardware-validated HarmonyOS client (BusinessCommands.ts / VoiceSession.ets / DeviceFeatures.ets).
 *
 * Firmware update (9 / MARS_FOTA) is deliberately absent: this app has no code path that can send OTA data to the
 * glasses. The file businesses (17, 18) are not in [OUTBOUND]: they have their own gate (FileTransfer.outboundProblem)
 * and open only during a teleprompter session, for one small ".txt" script.
 */
object Business {
    const val HANDSHAKE = 0x10
    const val AI_ASSISTANT = 13   // wake, recorder, audio, transcript, answer, EOF, exit
    const val LAUNCHER = 15       // general status / battery
    const val SUBTITLE = 19       // temporary text display session
    const val RECORDING = 14      // glasses-menu recording (phase-3 probe)
    const val TODO = 22           // native To-do app of the glasses (schedule/todo service)
    const val NOTIFICATION = 21   // notification cards, master switch, nod/shake suggestion cards
    const val TELEPROMPTER = 20   // teleprompter session control (the script itself goes over FILE)
    const val FILE = 18           // file transfer "LargeCore" (protobuf, not envelopes): teleprompter script only
    const val SMALL_FILE = 17     // file transfer "SmallCore", the one the official app's upload uses; same messages

    /** Outbound allow-list: business -> envelope types we may send. Everything else is refused. */
    val OUTBOUND: Map<Int, Set<Int>> = mapOf(
        // 162 = LifeLog A2 (start), 166 = A6 (exit), 168 = A8 (realtime page flag)
        AI_ASSISTANT to setOf(2, 5, 7, 12, 32, 162, 166, 168),
        // 4 = read general settings; 5 = display/crown/wakeup config and sync_time with the official ranges only;
        // 20 = life_log_guide / life_log_switch (see contentAllowed)
        // 16 = set_ai_voice_wakeup (on/off); 18 = weather / dashboard (see contentAllowed)
        LAUNCHER to setOf(1, 4, 5, 16, 18, 20),
        SUBTITLE to setOf(3, 5, 7),
        // enable(14), start/ack(1), stop/ack(4), acks(8, 9, 11) — no delete/format/file commands
        RECORDING to setOf(1, 4, 8, 9, 11, 14),
        // 2 = add/modify ONE to-do (eventType 1); no batch, delete, schedule or binary types
        TODO to setOf(2),
        // 2 = card, 18 = master switch, 33 = suggestion card add/delete, 35 = suggestion answer ack
        NOTIFICATION to setOf(2, 18, 33, 35),
        // 1 script list (empty answer only), 2 prepare, 3 start, 4 pause, 5 resume, 6 exit, 7 settings, 8 progress ack
        TELEPROMPTER to setOf(1, 2, 3, 4, 5, 6, 7, 8),
    )

    fun outboundAllowed(business: Int, type: Int): Boolean = OUTBOUND[business]?.contains(type) == true

    private val LAUNCHER_COMMANDS = setOf("life_log_guide", "life_log_switch")

    /** Second gate on the JSON body for generic command channels. */
    fun contentAllowed(business: Int, type: Int, json: String): Boolean {
        val cmd = Regex("^\\{\"cmd\":\"([a-z_]+)\"").find(json)?.groupValues?.get(1)
        if (business == LAUNCHER && type == 20) return cmd in LAUNCHER_COMMANDS
        if (business == LAUNCHER && type == 4) return cmd == "request_general_settings"
        if (business == LAUNCHER && type == 5) return settingAllowed(cmd, json)
        if (business == LAUNCHER && type == 16) return json == WAKEUP_ON || json == WAKEUP_OFF
        if (business == LAUNCHER && type == 18) return launcher18Allowed(cmd, json)
        if (business == NOTIFICATION) return notificationAllowed(type, json)
        if (business == TELEPROMPTER && type == 1) return json == TELEPROMPTER_LIST_EMPTY
        if (business == TELEPROMPTER) return runCatching { org.json.JSONObject(json) }.getOrNull()?.let { j ->
            UUID.matches(j.optString("did")) && j.optInt("action") in 1..2
        } ?: false
        if (business == TODO) return runCatching { org.json.JSONObject(json) }.getOrNull()?.let { j ->
            j.optInt("eventType") == 1 && j.optLong("eventID") > 0 && j.optString("title").length in 1..60 && j.optInt("status", -1) in 0..1
        } ?: false
        return true
    }

    /** 20/1 answer to the glasses' script-list request: no documents (the official app's reply when it has none). */
    const val TELEPROMPTER_LIST_EMPTY = """{"action":2,"type":1,"total":0,"chunkinfo":{"chunkid":1,"total_items":0,"islast":true},"items":[]}"""
    const val WAKEUP_ON = """{"cmd":"set_ai_voice_wakeup","payload":{"value":0,"mode":1,"data":""}}"""
    /** Off: the same command with mode 0 (official app 1.0.7). */
    const val WAKEUP_OFF = """{"cmd":"set_ai_voice_wakeup","payload":{"value":0,"mode":0,"data":""}}"""
    private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val OWN_WIDGET = Regex("^turbo_ui_[a-z0-9_]{1,40}$")

    private fun launcher18Allowed(cmd: String?, json: String): Boolean {
        val j = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return false
        val payload = j.optJSONObject("payload") ?: return false
        return when (cmd) {
            "current_weather_update" -> runCatching { org.json.JSONObject(payload.getString("data")) }.getOrNull()?.let { d ->
                d.keys().asSequence().toSet() == setOf("location", "temp", "icon") && d.optString("location").length in 1..40 &&
                    d.optInt("temp", 999) in -80..60 && d.optInt("icon", -1) in 0..999
            } ?: false
            "dashboard_config" -> payload.optInt("version") == 1 && payload.optInt("value", -1) == 0 && !payload.has("data")
            "widget_install" -> payload.optJSONObject("data")?.let { d -> d.optString("type") == "a2ui" && OWN_WIDGET.matches(d.optString("id")) } ?: false
            "widget_uninstall" -> payload.optJSONObject("data")?.let { d -> OWN_WIDGET.matches(d.optString("id")) } ?: false
            // A full snapshot (value 0) built from a fresh read; the dashboard controller verifies it by reading back.
            "dashboard_update" -> payload.optInt("value", -1) == 0 &&
                runCatching { org.json.JSONObject(payload.getString("data")).let { it.has("widgets_v2") || it.optJSONArray("widgets") != null } }.getOrDefault(false)
            else -> false
        }
    }

    private fun notificationAllowed(type: Int, json: String): Boolean {
        val j = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return false
        return when (type) {
            2 -> j.optString("notificationUID").toLongOrNull()?.let { it in 1..2147483647L } == true && j.optString("appId").isNotEmpty() &&
                j.optString("title").length in 1..80 && j.optString("content").length in 1..500 && j.optInt("type") == 1 && !j.optBoolean("reply")
            18 -> j.keys().asSequence().toSet() == setOf("notification") && j.get("notification") is Boolean
            33 -> j.optInt("type") in 1..2 && j.optInt("suggestType") in 1..2 && Regex("^[A-Za-z0-9-]{8,64}$").matches(j.optString("suggestUID")) &&
                j.optString("title").length <= 40 && j.optString("source").length <= 40 && j.optString("content").length <= 120
            35 -> j.optInt("code", -1) in 0..3 && j.optInt("suggestType") in 1..2 && Regex("^[A-Za-z0-9-]{8,64}$").matches(j.optString("suggestUID"))
            else -> false
        }
    }

    /** Settings writes accepted only with the keys and ranges of the official app 1.0.7's setters. */
    private fun settingAllowed(cmd: String?, json: String): Boolean {
        if (cmd == "head_gestures") return runCatching {
            val j = org.json.JSONObject(json); val p = j.getJSONObject("payload")
            j.keys().asSequence().toSet() == setOf("cmd", "payload") && p.keys().asSequence().toSet() == setOf("value", "mode", "data") &&
                p.getInt("value") in 0..1 && p.getInt("mode") in 0..1 && p.getString("data").isEmpty()
        }.getOrDefault(false)
        val payload = runCatching { org.json.JSONObject(json).getJSONObject("payload") }.getOrNull() ?: return false
        val data = runCatching { org.json.JSONObject(payload.getString("data")) }.getOrNull() ?: return false
        val keys = data.keys().asSequence().toSet()
        // Ranges of the official app 1.0.7's setters (glasses_commands.dart).
        val crownActions = setOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        return when (cmd) {
            "display_config" -> keys == setOf("height", "distance") && data.optInt("height", -1) in 1..6 && data.optInt("distance", -1) in 1..3 &&
                payload.optInt("value", -1) == 0
            "wakeup_config" -> keys == setOf("headup_switch", "headup_degree", "crown_switch") && data.optInt("headup_switch", -1) in 0..1 &&
                data.optInt("crown_switch", -1) in 0..3 && data.optInt("headup_degree", -1) in 0..90 && payload.optInt("value", -1) == 0
            // value = rotation direction, sent back as read.
            "crown_config" -> keys == setOf("double", "longPress") && payload.optInt("value", -1) in 0..8 &&
                data.optInt("double", -1) in crownActions && data.optInt("longPress", -1) in crownActions - 6
            "sync_time" -> keys == setOf("utc", "offset", "timezone") && data.optLong("utc", -1) in 1_600_000_000L..4_000_000_000L &&
                data.optInt("offset", 99_999) in -14 * 3600..14 * 3600 && Regex("^[A-Za-z0-9_+/-]{1,40}$").matches(data.optString("timezone")) &&
                payload.optInt("value", -1) == 0
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
    /** Text session config. Defaults are the values seen working on stock firmware; others are experimental. */
    fun displayStart(sid: String, fontSize: Int = 2, position: String = "center", maxLines: Int = 5): String {
        if (fontSize !in 1..3 || position !in setOf("center", "top", "bottom") || maxLines !in 1..5) throw ProtocolException("invalid-value")
        return "{\"sid\":${sid(sid)},\"force\":false,\"scope\":\"temporary\",\"config\":{\"font_size\":$fontSize,\"content_width\":100,\"max_lines\":$maxLines,\"position\":\"$position\",\"is_display\":true,\"straight_view\":\"original\"}}"
    }

    /** Text formats for 19/5: "legacy" (HarmonyOS client, older firmware), "official" (template of the official app's
     *  own subtitle preview on 1.0.5.5/1.0.6: final text, mode 0), "bilingual" (official with the translation line). */
    val TEXT_FORMATS = listOf("legacy", "official", "bilingual", "caption", "classic")

    /**
     * "caption" / "classic": the official app 1.0.7's caption message (mode 3 = title layout, 1 = classic), status 0
     * while the sentence is still forming and 1 when it is [final], with every content field (label 1 = no translation).
     */
    fun displayText(sid: String, value: String, format: String = "legacy", final: Boolean = true): String {
        val t = text(value, 96)
        return when (format) {
            "official" -> "{\"sid\":${sid(sid)},\"mode\":0,\"status\":1,\"content\":{\"source_transcript\":$t}}"
            "bilingual" -> "{\"sid\":${sid(sid)},\"mode\":0,\"status\":1,\"content\":{\"source_transcript\":$t,\"target_translation\":$t}}"
            "caption", "classic" -> "{\"sid\":${sid(sid)},\"mode\":${if (format == "caption") 3 else 1},\"status\":${if (final) 1 else 0}," +
                "\"content\":{\"source_transcript\":$t,\"target_translation\":\"\",\"label\":1,\"keyword_info\":\"\"}}"
            else -> "{\"sid\":${sid(sid)},\"mode\":3,\"status\":0,\"content\":{\"source_transcript\":$t}}"
        }
    }

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
