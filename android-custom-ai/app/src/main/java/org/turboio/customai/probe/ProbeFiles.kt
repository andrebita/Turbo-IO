package org.turboio.customai.probe

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import org.turboio.customai.BuildConfig
import org.turboio.customai.diag.DiagnosticLog
import java.io.DataOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PrefsSuiteStore(context: Context) : SuiteStore {
    private val prefs = context.getSharedPreferences("turboio_probe_v1", Context.MODE_PRIVATE)
    override fun load(): String? = prefs.getString("suite", null)
    override fun save(json: String) { prefs.edit().putString("suite", json).commit() }
}

class PrefsPendingFlag(context: Context) : PendingFlag {
    private val prefs = context.getSharedPreferences("turboio_probe_v1", Context.MODE_PRIVATE)
    override fun get() = prefs.getBoolean("lifelog_off_pending", false)
    override fun set(value: Boolean) { prefs.edit().putBoolean("lifelog_off_pending", value).commit() }
}

/**
 * probe-<time>-<kind>/
 *   report.json   — analysis (no audio)            events.txt — command/receive log (no audio)
 *   envelopes.bin — raw captures: [u32 atMs][u8 business][u16 type][u32 jsonLen][json][u32 binLen][bin]
 *   decoded.wav   — best-effort 16 kHz decode of the glasses audio, to listen to
 *   log-tecnico.txt — redacted technical log at save time (survives the app being killed before export)
 */
class ProbeFiles(private val context: Context) : ProbeSink {
    val root = File(context.filesDir, "probes").apply { mkdirs() }

    override fun save(kind: String, records: List<ProbeRecord>, analysis: ProbeAnalysis.Result, events: List<String>): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(root, "probe-$stamp-$kind").apply { mkdirs() }
        analysis.report.put("app", BuildConfig.VERSION_NAME).put("android", android.os.Build.VERSION.RELEASE)
            .put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        File(dir, "report.json").writeText(analysis.report.toString(2))
        File(dir, "events.txt").writeText(events.joinToString("\n"))
        DataOutputStream(File(dir, "envelopes.bin").outputStream().buffered()).use { out ->
            for (r in records) {
                val json = r.json.toByteArray()
                out.writeInt(r.atMs.toInt()); out.writeByte(r.business); out.writeShort(r.type)
                out.writeInt(json.size); out.write(json); out.writeInt(r.binary.size); out.write(r.binary)
            }
        }
        File(dir, "log-tecnico.txt").writeText(DiagnosticLog.export())
        if (analysis.pcm.isNotEmpty()) File(dir, "decoded.wav").writeBytes(ProbeAnalysis.wav(analysis.pcm, analysis.channels))
        DiagnosticLog.i("SONDA", "resultado salvo em ${dir.name}")
        return dir.name
    }

    fun latest(): File? = all().lastOrNull()

    /** Saved probes, oldest first; [since] (epoch ms) keeps only those saved after a suite started. */
    fun all(since: Long = 0): List<File> = root.listFiles()?.filter { it.isDirectory && it.lastModified() >= since - 1000 }?.sortedBy { it.name } ?: emptyList()

    fun deleteAll() { root.listFiles()?.forEach { it.deleteRecursively() } }

    /** One ZIP with every probe folder (each with its own log) plus a summary and the current log. */
    fun shareAll(dirs: List<File>, summary: String, log: String): Intent {
        val exports = File(context.cacheDir, "exports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val zip = File(exports, "turboio-testes-$stamp.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            for (dir in dirs) dir.listFiles()?.forEach { f ->
                z.putNextEntry(ZipEntry("${dir.name}/${f.name}")); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
            z.putNextEntry(ZipEntry("resumo.txt")); z.write(summary.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("log-tecnico.txt")); z.write(log.toByteArray()); z.closeEntry()
        }
        DiagnosticLog.i("SONDA", "pacote com ${dirs.size} testes: ${zip.length() / 1024} KB")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", zip)
        return Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Turbo IO · ${zip.name}").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Enviar todos os testes")
    }

    fun report(dir: File): JSONObject? = runCatching { JSONObject(File(dir, "report.json").readText()) }.getOrNull()

    /** Zips the probe (optionally without audio) plus the redacted technical log, and opens the share sheet. */
    fun share(dir: File, includeAudio: Boolean, log: String): Intent {
        val exports = File(context.cacheDir, "exports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val zip = File(exports, dir.name + if (includeAudio) ".zip" else "-sem-audio.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            dir.listFiles()?.filter { includeAudio || it.name !in setOf("envelopes.bin", "decoded.wav") }?.forEach { f ->
                z.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
            z.putNextEntry(ZipEntry("log-tecnico.txt")); z.write(log.toByteArray()); z.closeEntry()
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", zip)
        return Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Turbo IO · ${dir.name}").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Enviar pacote de diagnóstico")
    }
}
