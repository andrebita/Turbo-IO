package org.turboio.customai.device

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Teleprompter scripts kept on the phone (app-private storage), so the glasses' own Teleprompter menu can list them
 * (20/1) and open one (glasses 20/2 → the file is sent). At most [MAX] scripts; the oldest unpinned one is dropped.
 */
class ScriptLibrary(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    data class Script(val did: String, val title: String, val total: Int, val timestamp: Long, val preview: String)

    private val index get() = File(dir, "index.json")

    @Synchronized fun list(): List<Script> = runCatching {
        val a = JSONArray(index.readText())
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o ->
            Script(o.getString("did"), o.getString("title"), o.getInt("total"), o.getLong("timestamp"), o.optString("preview"))
        } }.filter { UUID.matches(it.did) && File(dir, "${it.did}.txt").isFile }
    }.getOrDefault(emptyList())

    @Synchronized fun bytes(did: String): ByteArray? =
        if (!UUID.matches(did)) null else File(dir, "$did.txt").takeIf { it.isFile && it.length() in 1..Teleprompter.MAX_BYTES.toLong() }?.readBytes()

    /** Saves (or replaces) the script under [did]; the title is its first line. */
    @Synchronized fun save(did: String, text: String) {
        if (!UUID.matches(did) || text.isBlank()) return
        dir.mkdirs()
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > Teleprompter.MAX_BYTES) return
        File(dir, "$did.txt").writeBytes(bytes)
        val first = text.trim().lineSequence().first().trim()
        val item = Script(did, first.take(TITLE), bytes.size, clock() / 1000, text.trim().replace(Regex("\\s+"), " ").take(PREVIEW))
        val kept = (listOf(item) + list().filter { it.did != did }).take(MAX)
        list().filter { old -> kept.none { it.did == old.did } }.forEach { File(dir, "${it.did}.txt").delete() }
        write(kept)
    }

    @Synchronized fun clear() { dir.listFiles()?.forEach { it.delete() } }

    private fun write(items: List<Script>) {
        val a = JSONArray()
        items.forEach { a.put(JSONObject().put("did", it.did).put("title", it.title).put("total", it.total).put("timestamp", it.timestamp).put("preview", it.preview)) }
        index.writeText(a.toString())
    }

    companion object {
        const val MAX = 8
        const val TITLE = 40
        const val PREVIEW = 60
        private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

        /** 20/1 answer (official AppListUpdateRequest, one chunk): the scripts, newest first. */
        fun listMessage(items: List<Script>, timezoneSeconds: Int): String {
            val a = JSONArray()
            items.forEach {
                a.put(JSONObject().put("did", it.did).put("pin", false).put("title", it.title.ifBlank { "Roteiro" }).put("offset", 0).put("scroll", 2)
                    .put("total", it.total).put("timestamp", it.timestamp).put("preview", it.preview).put("timezone", timezoneSeconds))
            }
            return JSONObject().put("action", 2).put("type", 1).put("total", items.size)
                .put("chunkinfo", JSONObject().put("chunkid", 1).put("total_items", items.size).put("islast", true)).put("items", a).toString()
        }
    }
}
