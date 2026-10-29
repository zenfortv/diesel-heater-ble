package io.github.zenfortv.dieselheater

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.abs

/** The phone as room sensor: its battery temperature, corrected by SensorModel for what is warming it
 *  (screen, charging, wifi, hotspot, battery current), learned from real-thermometer readings typed in.
 *  Also keeps a 48 h log (every 5 min) and the outdoor temperature (ItaliaMeteo ICON-2I via Open-Meteo). */
class PhoneSensor(ctx: Context) {
    private val app = ctx.applicationContext
    private val prefs = app.getSharedPreferences("heater", Context.MODE_PRIVATE)
    private val samplesFile = File(app.filesDir, "calib.json")
    private val logFile = File(app.filesDir, "phone_log.json")
    private val model = SensorModel(loadSamples())
    private val log = ArrayDeque<JSONObject>()
    private var lastLogAt = 0.0
    private var warmth: PhoneWarmth? = null
    private var warmthAt = 0.0
    @Volatile private var now = Now()

    data class Now(val raw: Double? = null, val charging: Boolean = false, val screen: Boolean = false,
                   val wifi: Boolean = false, val hotspot: Boolean? = null, val loadA: Double? = null)

    init {
        try { JSONArray(logFile.readText()).let { a -> for (i in 0 until a.length()) log.add(a.getJSONObject(i)) } } catch (_: Exception) {}
    }

    /* ---------- reading the phone ---------- */
    private fun read(): Now {
        val i = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val t = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val charging = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val screen = try { app.getSystemService(PowerManager::class.java).isInteractive } catch (_: Exception) { false }
        val wifi = try {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } catch (_: Exception) { false }
        return Now(if (t == Int.MIN_VALUE || t == 0) null else t / 10.0, charging, screen, wifi, hotspot(), current())
    }

    // no public API for the hotspot: the hidden WifiManager call where allowed, else look for the access-point interface
    private fun hotspot(): Boolean? {
        try {
            val wm = app.getSystemService(WifiManager::class.java)
            return wm.javaClass.getMethod("isWifiApEnabled").invoke(wm) as Boolean
        } catch (_: Exception) {}
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.any { n ->
                n.isUp && Regex("^(ap\\d|swlan\\d|softap\\d|wigig\\d)").containsMatchIn(n.name) && n.inetAddresses.toList().isNotEmpty()
            }
        } catch (_: Exception) { null }
    }

    // battery current in A: phones report µA (most) or mA (some)
    private fun current(): Double? = try {
        val v = app.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (v == Int.MIN_VALUE || v == 0) null else abs(v.toDouble()).let { if (it > 20_000) it / 1e6 else it / 1e3 }.coerceAtMost(4.0)
    } catch (_: Exception) { null }

    /** every thermostat tick (5 s): read the phone, follow the warmth levels, log every 5 min */
    @Synchronized fun update(heaterRoom: Double?, burnerOn: Boolean) {
        val n = read(); now = n
        val t = nowS()
        warmth = (warmth ?: PhoneWarmth().follow(n.screen, n.charging, n.wifi, n.hotspot == true, n.loadA ?: 0.0, 1e9))
            .follow(n.screen, n.charging, n.wifi, n.hotspot == true, n.loadA ?: 0.0, if (warmthAt > 0) t - warmthAt else 0.0)
        warmthAt = t
        if (t - lastLogAt >= 300 && n.raw != null) {
            lastLogAt = t
            log.add(JSONObject().put("t", t.toLong()).put("raw", r1(n.raw)).put("room", r1(model.room(n.raw, warmth!!) + trim()))
                .put("heater", heaterRoom?.let(::r1) ?: JSONObject.NULL).put("out", outdoor?.optDouble("temp")?.let(::r1) ?: JSONObject.NULL)
                .put("on", burnerOn).put("scr", n.screen).put("chg", n.charging).put("hs", n.hotspot == true).put("wifi", n.wifi))
            while (log.size > 576) log.removeFirst()
            try { logFile.writeText(JSONArray(log).toString()) } catch (_: Exception) {}
        }
    }

    var trim: () -> Double = { 0.0 }  // the manual "extra correction" from the settings
    val raw get() = now.raw
    val charging get() = now.charging
    /** room temperature from the phone (null with no battery reading yet) */
    @Synchronized fun room(): Double? { val r = now.raw ?: return null; val w = warmth ?: return null; return model.room(r, w) + trim() }

    /* ---------- readings from a real thermometer ---------- */
    @Synchronized fun addReading(real: Double, heaterRoom: Double?): JSONObject {
        val r = now.raw ?: return JSONObject().put("ok", false).put("error", "No phone reading yet")
        val w = warmth ?: return JSONObject().put("ok", false).put("error", "No phone reading yet")
        model.add(CalibSample(nowS(), real, r, w, outdoor?.optDouble("temp"), heaterRoom))
        saveSamples()
        return calib().put("ok", true)
    }
    @Synchronized fun removeReading(t: Double) { model.remove(t); saveSamples() }
    @Synchronized fun clearReadings() { model.clear(); saveSamples() }

    @Synchronized fun calib(): JSONObject {
        val arr = JSONArray()
        for (s in model.samples.asReversed()) arr.put(JSONObject().put("t", s.t).put("real", s.real).put("raw", s.raw)
            .put("est", r1(model.room(s.raw, s.w))).put("w", wJson(s.w))
            .put("out", s.outdoor ?: JSONObject.NULL).put("heater", s.heaterRoom ?: JSONObject.NULL))
        val wts = JSONObject(); SensorModel.NAMES.forEachIndexed { i, k -> wts.put(k, r1(model.weights[i])) }
        return JSONObject().put("samples", arr).put("weights", wts).put("mean_error", model.meanError()?.let(::r1) ?: JSONObject.NULL)
    }
    fun logJson() = synchronized(this) { JSONArray(log) }

    /** for the page: what the phone says, what is warming it, what the model makes of it */
    @Synchronized fun stateJson(): JSONObject {
        val n = now; val o = JSONObject()
        n.raw?.let { o.put("raw", r1(it)) }
        room()?.let { o.put("room", r1(it)) }
        warmth?.let { o.put("warm_by", r1(model.warmBy(it))).put("warmth", wJson(it)) }
        o.put("screen", n.screen).put("charging", n.charging).put("wifi", n.wifi)
            .put("hotspot", n.hotspot ?: JSONObject.NULL).put("load_a", n.loadA?.let { Math.round(it * 100) / 100.0 } ?: JSONObject.NULL)
            .put("readings", model.samples.size).put("mean_error", model.meanError()?.let(::r1) ?: JSONObject.NULL)
        outdoor?.let { o.put("outdoor", it) }
        return o
    }

    private fun wJson(w: PhoneWarmth) = JSONObject().put("screen", r2(w.screen)).put("charging", r2(w.charging))
        .put("wifi", r2(w.wifi)).put("hotspot", r2(w.hotspot)).put("load", r2(w.load))
    private fun wFrom(j: JSONObject) = PhoneWarmth(j.optDouble("screen", 0.0), j.optDouble("charging", 0.0),
        j.optDouble("wifi", 0.0), j.optDouble("hotspot", 0.0), j.optDouble("load", 0.0))
    private fun loadSamples(): List<CalibSample> = try {
        val a = JSONArray(samplesFile.readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map { j ->
            CalibSample(j.getDouble("t"), j.getDouble("real"), j.getDouble("raw"), wFrom(j.getJSONObject("w")),
                if (j.isNull("out")) null else j.optDouble("out"), if (j.isNull("heater")) null else j.optDouble("heater"))
        }
    } catch (_: Exception) { emptyList() }
    private fun saveSamples() = try {
        val a = JSONArray()
        for (s in model.samples) a.put(JSONObject().put("t", s.t).put("real", s.real).put("raw", s.raw).put("w", wJson(s.w))
            .put("out", s.outdoor ?: JSONObject.NULL).put("heater", s.heaterRoom ?: JSONObject.NULL))
        samplesFile.writeText(a.toString())
    } catch (_: Exception) {}

    /* ---------- outdoor temperature ---------- */
    /** the outdoor temperature if it's recent (3 h), for the heating algorithm */
    fun outdoorTemp(): Double? = outdoor?.takeIf { it.has("temp") && nowS() - it.optDouble("at", 0.0) < 3 * 3600 }?.getDouble("temp")

    @Volatile var outdoor: JSONObject? = try { JSONObject(prefs.getString("outdoor", "") ?: "") } catch (_: Exception) { null }
        private set
    private val net = Executors.newSingleThreadScheduledExecutor()
    var place: () -> Pair<Double, Double> = { VENARIA }

    fun startWeather() { net.scheduleWithFixedDelay({ fetchWeather() }, 2, 20 * 60, java.util.concurrent.TimeUnit.SECONDS) }
    fun refreshWeather() { net.execute { fetchWeather() } }
    fun stop() { net.shutdownNow() }

    private fun fetchWeather() {
        val (lat, lon) = place()
        // ItaliaMeteo's ICON-2I (2.2 km, the Italian national model) first, Open-Meteo's best match if it has no data here
        for ((model, label) in listOf("&models=italia_meteo_arpae_icon_2i" to "ItaliaMeteo ICON-2I", "" to "Open-Meteo")) {
            try {
                val c = URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code,is_day&timezone=auto$model").openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 10_000
                val cur = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getJSONObject("current")
                if (cur.isNull("temperature_2m")) continue
                val code = cur.optInt("weather_code", -1)
                val o = JSONObject().put("temp", cur.getDouble("temperature_2m")).put("code", code).put("text", weatherText(code)).put("at", nowS())
                    .put("day", cur.optInt("is_day", 1) == 1).put("time", cur.optString("time")).put("at", nowS().toLong())
                    .put("src", label).put("lat", lat).put("lon", lon)
                outdoor = o; prefs.edit().putString("outdoor", o.toString()).apply()
                return
            } catch (_: Exception) {}
        }
    }

    companion object {
        val VENARIA = 45.1377 to 7.6306
        private fun nowS() = System.currentTimeMillis() / 1000.0
        private fun r1(v: Double) = Math.round(v * 10) / 10.0
        private fun r2(v: Double) = Math.round(v * 100) / 100.0
        fun weatherText(c: Int) = when (c) {
            0 -> "clear"; 1, 2 -> "partly cloudy"; 3 -> "overcast"; 45, 48 -> "fog"
            in 51..57 -> "drizzle"; in 61..67 -> "rain"; in 71..77 -> "snow"; in 80..82 -> "showers"
            85, 86 -> "snow showers"; in 95..99 -> "thunderstorm"; else -> ""
        }
    }
}
