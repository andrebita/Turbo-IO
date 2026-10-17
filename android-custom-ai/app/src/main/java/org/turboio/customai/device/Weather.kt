package org.turboio.customai.device

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.turboio.customai.TurboApp
import org.turboio.customai.ble.BusinessListener
import org.turboio.customai.ble.GlassesPort
import org.turboio.customai.diag.DiagnosticLog
import kotlinx.coroutines.suspendCancellableCoroutine
import org.turboio.customai.protocol.EnvelopeCodec
import kotlin.coroutines.resume
import org.turboio.customai.protocol.Business
import org.turboio.customai.protocol.Envelope
import org.turboio.customai.protocol.Json
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Home-screen weather of the glasses (launcher 15/18 `current_weather_update`, as sent by this repository's iOS and
 * HarmonyOS clients; partly checked on the lens). Data from Open-Meteo (free, no key, HTTPS). The glasses' icon numbers
 * follow QWeather codes in those clients; the mapping from Open-Meteo (WMO) codes below must be checked on the lens.
 */
object WeatherWire {
    fun update(location: String, temp: Int, icon: Int, epochSeconds: Long): String {
        val data = "{\"location\":${Json.quote(location.take(40))},\"temp\":$temp,\"icon\":$icon}"
        return "{\"cmd\":\"current_weather_update\",\"payload\":{\"value\":0,\"mode\":0,\"data\":${Json.quote(data)},\"ts\":\"$epochSeconds\"}}"
    }

    /** WMO weather code (Open-Meteo) → QWeather icon code (used by the glasses' home screen). */
    fun icon(wmo: Int, isDay: Boolean = true): Int = when (wmo) {
        0 -> if (isDay) 100 else 150
        1 -> if (isDay) 102 else 152
        2 -> if (isDay) 103 else 153
        3 -> 104
        45, 48 -> 501
        51, 53, 55, 56, 57 -> 309
        61 -> 305; 63 -> 306; 65 -> 307
        66, 67 -> 313
        71, 77 -> 400; 73 -> 401; 75 -> 402
        80, 81, 82 -> 300
        85, 86 -> 407
        95 -> 302
        96, 99 -> 304
        else -> 999
    }
}

data class WeatherNow(val location: String, val temp: Int, val wmo: Int, val isDay: Boolean)

object OpenMeteo {
    private fun get(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 10_000; c.instanceFollowRedirects = false
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }

    fun now(city: String): WeatherNow {
        val g = get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=pt&name=" + URLEncoder.encode(city.trim(), "UTF-8"))
        val r = g.optJSONArray("results")?.optJSONObject(0) ?: throw IllegalStateException("Cidade não encontrada")
        val w = get("https://api.open-meteo.com/v1/forecast?current=temperature_2m,weather_code,is_day&latitude=${r.getDouble("latitude")}&longitude=${r.getDouble("longitude")}")
            .getJSONObject("current")
        return WeatherNow(r.optString("name", city), Math.round(w.getDouble("temperature_2m")).toInt(), w.getInt("weather_code"), w.optInt("is_day", 1) == 1)
    }
}

/** Sends the weather and waits for the glasses' answer (15/19, value 0 = accepted). */
class GlassesWeather(private val port: GlassesPort) : BusinessListener {
    private var reply: CompletableDeferred<Int>? = null
    init { port.addBusinessListener(this) }

    override fun onBusiness(business: Int, envelope: Envelope) {
        if (business != Business.LAUNCHER || envelope.type != 19) return
        val j = runCatching { JSONObject(envelope.jsonText()) }.getOrNull() ?: return
        if (j.optString("cmd") == "current_weather_update") reply?.complete(j.optJSONObject("payload")?.optInt("value", -1) ?: -1)
    }

    suspend fun send(w: WeatherNow, now: Long = System.currentTimeMillis()): String {
        if (!port.isReady) return "Conecte os óculos"
        val icon = WeatherWire.icon(w.wmo, w.isDay)
        val waiter = CompletableDeferred<Int>().also { reply = it }
        // Sequenced request (mode 1), as the official app does for launcher 15/18, so the 15/19 answer is correlated.
        val seq = 0x40000000L + (now and 0x3fffffffL)
        val bytes = EnvelopeCodec.encode(18, WeatherWire.update(w.location, w.temp, icon, now / 1000), seq, 1)
        val error = suspendCancellableCoroutine<String?> { cont -> port.send(Business.LAUNCHER, bytes) { e -> if (cont.isActive) cont.resume(e) } }
        if (error != null) { reply = null; return "Falha ao enviar: $error" }
        val value = withTimeoutOrNull(8000) { waiter.await() }
        reply = null
        val text = "${w.location} ${w.temp}° (código ${w.wmo} → ícone $icon): " +
            when (value) { null -> "sem resposta dos óculos"; 0 -> "aceito pelos óculos"; else -> "recusado (valor $value)" }
        DiagnosticLog.i("TEMPO", text)
        return text
    }
}

/** Hourly refresh while the option is on; also run right after connecting. */
class WeatherWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as TurboApp
        val city = app.settings.weatherCity
        if (!app.settings.weatherOn || city.isBlank() || !app.session.isReady) return Result.success()
        return try {
            val w = withContext(Dispatchers.IO) { OpenMeteo.now(city) }
            app.weather.send(w); Result.success()
        } catch (e: Exception) {
            DiagnosticLog.w("TEMPO", "atualização falhou: ${e.message ?: e.javaClass.simpleName}")
            if (runAttemptCount < 2) Result.retry() else Result.success()
        }
    }

    companion object {
        private val NET = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        fun schedule(context: Context, on: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!on) { wm.cancelUniqueWork("weather-hourly"); return }
            wm.enqueueUniquePeriodicWork("weather-hourly", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WeatherWorker>(60, TimeUnit.MINUTES).setConstraints(NET).build())
        }
        fun now(context: Context) = WorkManager.getInstance(context).enqueueUniqueWork("weather-now", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<WeatherWorker>().setInitialDelay(5, TimeUnit.SECONDS).setConstraints(NET).build())
    }
}
