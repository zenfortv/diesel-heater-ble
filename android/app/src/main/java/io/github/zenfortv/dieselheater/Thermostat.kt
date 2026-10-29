package io.github.zenfortv.dieselheater

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Auto thermostat: the heater's own thermostat mode only turns the power down near the target and never
 *  stops, so here the burner is switched fully on below the target and off above it. The room sensor keeps
 *  reporting with the burner off. One switch: while it is on, nothing else switches the heater (power button,
 *  timers, countdown, safety auto-off and the heater's own auto start/stop are locked).
 *  The rules live in ThermoLogic (unit-tested); this class keeps the settings, reads the heater and sends commands. */
class Thermostat(
    ctx: Context, private val heater: HeaterLink,
    private val log: (action: String, result: String) -> Unit,
    private val alert: (String) -> Unit,
    private val event: (String) -> Unit,
    /** a problem waiting for "OK, keep working" (null: none any more) */
    private val alarm: (Alarm?) -> Unit = {},
) {
    private val prefs = ctx.getSharedPreferences("heater", Context.MODE_PRIVATE)
    private val cfg = JSONObject(DEFAULTS.toString())
    private val logic = ThermoLogic()
    @Volatile private var status = ""

    init {
        try {
            val saved = JSONObject(prefs.getString("thermo", "{}") ?: "{}")
            for (k in saved.keys()) cfg.put(k, saved.get(k))
            // v1.1-1.3 had "enabled" (feature) + "active" (holding): now one switch
            if (saved.has("active")) { cfg.put("enabled", saved.optBoolean("enabled") && saved.optBoolean("active")); cfg.remove("active"); save() }
        } catch (_: Exception) {}
        try {
            val m = JSONObject(prefs.getString("thermo_mem", "{}") ?: "{}")
            val starts = m.optJSONArray("starts")
            logic.restore(mapOf("lastOn" to m.optDouble("lastOn", 0.0), "lastOff" to m.optDouble("lastOff", 0.0),
                "failedStarts" to m.optInt("failedStarts", 0), "lockout" to (if (m.isNull("lockout")) null else m.optString("lockout")),
                "starts" to (0 until (starts?.length() ?: 0)).map { starts!!.getDouble(it) },
                "lockoutCross" to m.optBoolean("lockoutCross"),
                "acked" to (m.optJSONArray("acked")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList<String>())))
        } catch (_: Exception) {}
    }

    @Volatile var enabled = cfg.optBoolean("enabled")
        private set
    private fun d(k: String) = cfg.optDouble(k, DEFAULTS.optDouble(k))
    private fun save() = prefs.edit().putString("thermo", cfg.toString()).apply()
    private val phoneSource get() = cfg.optString("source") == "phone"
    // the logic sees one room temperature: the phone's is already corrected (learned warmth + extra correction)
    private fun settings() = ThermoSettings(targetNow(), d("below"), d("above"), d("min_on_min"), d("min_off_min"), d("min_volt"),
        if (phoneSource) 0.0 else d("offset"),
        plan = if (style == "classic") null else HeatPlan(d("warm_below"), d("boost_level").toInt(), d("boost_until"),
            d("approach_level").toInt(), d("hold_level").toInt(), d("cold_restart")),
        autoTune = style == "auto", outdoor = phone.outdoorTemp())
    /** auto: the algorithm sets the numbers · custom: the user's numbers · classic: on/off with the heater's thermostat */
    val classic get() = style == "classic"
    private val style get() = cfg.optString("style", "auto").let { if (it in STYLES) it else "auto" }

    /* sleep and wake-up times: the target follows them (Sleep / Day) when "sleep_mode" is on */
    private val sleepMode get() = cfg.optBoolean("sleep_mode")
    fun sleeping(): Boolean {
        val n = nowMin()
        val a = toMin(cfg.optString("sleep_at", "22:30")); val b = toMin(cfg.optString("wake_at", "07:00"))
        return if (a <= b) n in a until b else n >= a || n < b
    }
    fun targetNow(): Double = if (sleepMode) d(if (sleeping()) "night_target" else "day_target") else d("target")
    private var wasSleeping: Boolean? = null

    /* the phone as room sensor: its battery temperature, corrected for what warms it (PhoneSensor + SensorModel) */
    val phone = PhoneSensor(ctx).also { p ->
        p.trim = { d("phone_offset") }
        p.place = { d("lat") to d("lon") }
        // v1.7.x: phone_offset was the whole correction (often -10); now the model does that, it is only an extra trim
        if (!prefs.getBoolean("model_v1", false)) { cfg.put("phone_offset", 0.0); save(); prefs.edit().putBoolean("model_v1", true).apply() }
        // v2.0: levels instead of on/off, so a shorter minimum run (user's choice: 10 min instead of 20)
        if (!prefs.getBoolean("plan_v2", false)) {
            if (d("min_on_min") == 20.0) { cfg.put("min_on_min", 10.0); save() }
            prefs.edit().putBoolean("plan_v2", true).apply()
        }
    }
    // the heater sends no voltage while off: the last reading while it ran (under load, so on the low side)
    // stands in for up to 16 h (a night), so the battery limit still works between cycles
    private var lastVolt = prefs.getFloat("last_volt", 0f).toDouble()
    private var lastVoltAt = prefs.getLong("last_volt_at", 0L).toDouble()
    private fun volt(f: JSONObject): Pair<Double?, Boolean> {
        if (f.has("supply_voltage")) {
            val v = f.getDouble("supply_voltage")
            if (nowS() - lastVoltAt > 300 || kotlin.math.abs(v - lastVolt) >= 0.1) {
                lastVolt = v; lastVoltAt = nowS()
                prefs.edit().putFloat("last_volt", v.toFloat()).putLong("last_volt_at", nowS().toLong()).apply()
            }
            return v to false
        }
        return (if (lastVoltAt > 0 && nowS() - lastVoltAt < 16 * 3600) lastVolt else null) to true
    }
    private fun heaterRoom(): Double? = heater.frame().let { if (it.has("cab_temperature")) it.getDouble("cab_temperature") + d("offset") else null }
    private fun r1(v: Double) = Math.round(v * 10) / 10.0

    private var savedMem = ""
    private fun saveMem() {
        val m = logic.export()
        val j = JSONObject().put("lastOn", m["lastOn"]).put("lastOff", m["lastOff"]).put("failedStarts", m["failedStarts"])
            .put("lockout", m["lockout"] ?: JSONObject.NULL).put("starts", JSONArray(m["starts"] as List<*>))
            .put("lockoutCross", m["lockoutCross"]).put("acked", JSONArray(m["acked"] as List<*>)).toString()
        if (j != savedMem) { savedMem = j; prefs.edit().putString("thermo_mem", j).apply() }
    }

    @Synchronized fun state(): JSONObject {
        val o = JSONObject(cfg.toString()).put("status", status)
        val pr = phone.room()
        pr?.let { o.put("phone_temp", r1(it)) }
        phone.raw?.let { o.put("phone_raw", r1(it)) }
        o.put("phone_charging", phone.charging).put("phone", phone.stateJson())
        heaterRoom()?.let { o.put("heater_room", r1(it)) }
        // "room" = the reading the thermostat uses
        (if (phoneSource) pr else heaterRoom())?.let { o.put("room", r1(it)) }
        logic.lockout?.let { o.put("lockout", it) }
        o.put("target_now", targetNow()).put("sleeping", sleepMode && sleeping()).put("style", style)
        logic.phase?.let { o.put("phase", it.name.lowercase()) }
        o.put("fuel_today", r1(fuelToday * 100) / 100).put("fuel_total", r1(fuelTotal * 10) / 10)
        // what the algorithm chose right now (shown under the Auto settings)
        if (style == "auto") settings().let { ThermoLogic.tuned(it, logic.coldSession) }.let { t ->
            o.put("tuned", JSONObject().put("boost_until", t.plan!!.boostUntil).put("approach_level", t.plan.approachLevel)
                .put("below", t.below).put("min_off_min", t.minOffMin).put("outdoor", t.outdoor ?: JSONObject.NULL)
                .put("cold_session", logic.coldSession))
        }
        current?.let { o.put("alarm", JSONObject().put("key", it.key).put("title", it.title).put("text", it.text)) }
        return o
    }

    /** margins, minimum times, battery limit, sensor correction (not the switch) */
    @Synchronized fun configure(b: JSONObject) {
        for (k in KEYS) if (b.has(k)) cfg.put(k, b.get(k))
        // floors that protect the heater, whatever the page sends
        cfg.put("below", d("below").coerceIn(0.5, 5.0)).put("above", d("above").coerceIn(0.5, 5.0))
            .put("min_on_min", d("min_on_min").coerceIn(10.0, 120.0)).put("min_off_min", d("min_off_min").coerceIn(5.0, 120.0))
            .put("offset", d("offset").coerceIn(-5.0, 5.0)).put("target", d("target").coerceIn(8.0, MAX_TARGET))
            .put("min_volt", d("min_volt").coerceIn(0.0, 13.0)).put("phone_offset", d("phone_offset").coerceIn(-5.0, 5.0))
            .put("lat", d("lat").coerceIn(-90.0, 90.0)).put("lon", d("lon").coerceIn(-180.0, 180.0))
            .put("source", if (cfg.optString("source") == "phone") "phone" else "heater")
            .put("style", style).put("warm_below", d("warm_below").coerceIn(-10.0, 18.0))
            .put("boost_level", d("boost_level").coerceIn(1.0, 10.0).toInt()).put("boost_until", d("boost_until").coerceIn(8.0, 25.0))
            .put("approach_level", d("approach_level").coerceIn(1.0, 10.0).toInt()).put("hold_level", d("hold_level").coerceIn(1.0, 10.0).toInt())
            .put("cold_restart", d("cold_restart").coerceIn(5.0, 22.0))
            .put("day_target", d("day_target").coerceIn(8.0, MAX_TARGET)).put("night_target", d("night_target").coerceIn(8.0, MAX_TARGET))
            .put("pump_hz_min", d("pump_hz_min").coerceIn(0.5, 3.0)).put("pump_hz_max", d("pump_hz_max").coerceIn(3.0, 8.0))
        for (k in listOf("sleep_at", "wake_at")) if (!Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(cfg.optString(k))) cfg.put(k, DEFAULTS.getString(k))
        save()
        if (b.has("lat") || b.has("lon")) phone.refreshWeather()
        tick()
    }

    /** "OK, keep working": the thermostat carries on despite the problem (asks again if it comes back) */
    @Synchronized fun ack(key: String) {
        val a = current
        logic.ack(key)
        log("alarm", "OK'd by the user: " + (a?.takeIf { it.key == key }?.title ?: key))
        if (current?.key == key) { current = null; alarm(null) }
        saveMem()
        tick()
    }

    @Volatile private var current: Alarm? = null

    /** the +/- on Home: with sleep/wake times on, it changes the target of the part of the day we're in */
    @Synchronized fun setTarget(t: Double) {
        cfg.put(if (sleepMode) (if (sleeping()) "night_target" else "day_target") else "target", t.coerceIn(8.0, MAX_TARGET)); save()
    }

    /** The Auto thermostat switch. On: hold the target (starts now if the room is below it).
     *  Off: back to manual control; a running heater keeps running. */
    @Synchronized fun setEnabled(on: Boolean): Boolean {
        enabled = on
        cfg.put("enabled", on); save()
        logic.resetSession()
        if (on) {
            logic.resetLockout()  // switching it on by hand is the reset after a lockout
            // the heater's own auto start/stop would stop the burner behind our back
            if (heater.frame().optBoolean("auto_start_stop")) {
                val ok = heater.setAutoStartStop(false)
                log("off", "heater's own auto start/stop switched off" + if (ok) "" else " (no answer)")
            }
            log("on", "auto thermostat on, target ${ThermoLogic.fmt(targetNow())}°C")
            tick(eager = true)
            return true
        }
        status = ""
        // the heater keeps doing what it does: from here it's manual (power button, level/thermostat on the heater)
        log("off", "auto thermostat off" + if (heater.running) " · heater keeps running, manual control" else "")
        if (heater.running) event("Auto thermostat off · heater keeps running (manual)")
        return true
    }

    // fuel estimate: each level is a pump frequency between pump_hz_min (level 1) and pump_hz_max (level 10),
    // each pump stroke ~0.022 ml (the usual pump of these heaters). Only an estimate.
    private var fuelToday = prefs.getFloat("fuel_today", 0f).toDouble()
    private var fuelTotal = prefs.getFloat("fuel_total", 0f).toDouble()
    private var fuelDay = prefs.getString("fuel_day", "") ?: ""
    private var lastTickAt = 0.0
    private fun countFuel(f: JSONObject) {
        val now = nowS(); val dt = if (lastTickAt > 0) now - lastTickAt else 0.0
        lastTickAt = now
        val day = iso(java.time.LocalDateTime.now()).substring(0, 10)
        if (day != fuelDay) { fuelDay = day; fuelToday = 0.0 }
        if (f.optInt("running_state", 0) == 1 && f.optInt("running_step", 0) == 3 && dt in 0.0..60.0) {
            val lv = f.optInt("set_level", 5).coerceIn(1, 10)
            val hz = d("pump_hz_min") + (lv - 1) * (d("pump_hz_max") - d("pump_hz_min")) / 9
            val l = hz * dt * STROKE_ML / 1000
            fuelToday += l; fuelTotal += l
        }
        if (now - fuelSavedAt > 300) {
            fuelSavedAt = now
            prefs.edit().putFloat("fuel_today", fuelToday.toFloat()).putFloat("fuel_total", fuelTotal.toFloat()).putString("fuel_day", fuelDay).apply()
        }
    }
    private var fuelSavedAt = 0.0
    private var levelSentAt = 0.0

    @Synchronized fun tick(eager: Boolean = false) {
        val f = heater.frame()
        countFuel(f)
        val burnerOn = f.optInt("running_state", 0) == 1
        if (sleepMode) {
            val sl = sleeping()
            if (wasSleeping != null && sl != wasSleeping && enabled)
                event(if (sl) "Sleep time · target ${ThermoLogic.fmt(d("night_target"))}°" else "Good morning · target ${ThermoLogic.fmt(d("day_target"))}°")
            wasSleeping = sl
        } else wasSleeping = null
        phone.update(heaterRoom(), burnerOn)
        val pt = phone.room()
        val r = HeaterReading(
            connected = heater.connected, ageS = nowS() - heater.updatedAt,
            burnerOn = burnerOn, step = f.optInt("running_step", 0), error = f.optInt("error_code", 0),
            roomRaw = if (phoneSource) pt else if (f.has("cab_temperature")) f.getDouble("cab_temperature") else null,
            volt = volt(f).first,
            crossRoom = if (phoneSource && burnerOn) heaterRoom() else null,
        )
        val dcs = logic.step(settings(), enabled, r, nowS(), eager)
        dcs.setActive?.let { enabled = it; cfg.put("enabled", it); save() }
        status = dcs.status
        dcs.alert?.let(alert)
        if (dcs.alarm != current) {
            val fresh = dcs.alarm != null && (dcs.alarm.key != current?.key || dcs.alarm.title != current?.title)
            current = dcs.alarm
            if (fresh) log("alarm", dcs.alarm!!.title)
            alarm(dcs.alarm)
        }
        val room = r.roomRaw?.let { ThermoLogic.fmt(it + settings().offset) + if (phoneSource) "° (phone)" else "°" } ?: "?"
        when (dcs.act) {
            Act.START -> {
                // a plan runs the heater in level mode at the plan's level; classic uses the heater's own thermostat mode
                val ok = heater.power(true) && (dcs.level?.let { heater.setLevel(it.toDouble()) } ?: heater.setTemperature(targetNow()))
                if (dcs.level != null) levelSentAt = nowS()
                log("on", dcs.log + if (ok) "" else " (no answer)")
                if (ok) event("Heater started by the auto thermostat · room $room · target ${ThermoLogic.fmt(targetNow())}°" +
                    (dcs.level?.let { " · level $it" } ?: ""))
            }
            Act.STOP -> {
                val ok = heater.power(false)
                dcs.log?.let { log("off", it + if (ok) "" else " (no answer, retrying)") }
                if (ok && dcs.log != null) event("Heater stopped by the auto thermostat · room $room")
                else alert("Couldn't switch the heater off (no answer). Check it.")
            }
            Act.NONE -> {
                dcs.log?.let {
                    log(if (r.burnerOn) "on" else "off", it)
                    if (dcs.setActive == false) event("Heater stopped on its panel · Auto thermostat switched off")
                }
                // the plan changed the level: send it once the burner is heating (not during ignition), at most every 20 s
                val lv = dcs.level
                if (lv != null && enabled && r.burnerOn && r.step == 3 && nowS() - levelSentAt > 20 &&
                    (f.optInt("running_mode", -1) != 1 || f.optInt("set_level", -1) != lv)) {
                    levelSentAt = nowS()
                    val was = f.optInt("set_level", -1)
                    val ok = heater.setLevel(lv.toDouble())
                    log("level", "level ${if (was > 0) "$was → " else ""}$lv · room $room" + if (ok) "" else " (no answer)")
                }
            }
        }
        saveMem()
    }

    companion object {
        const val MAX_TARGET = 30.0
        private val KEYS = listOf("target", "below", "above", "min_on_min", "min_off_min", "min_volt", "offset", "source", "phone_offset", "lat", "lon",
            "style", "warm_below", "boost_level", "boost_until", "approach_level", "hold_level", "cold_restart",
            "sleep_mode", "sleep_at", "wake_at", "day_target", "night_target", "pump_hz_min", "pump_hz_max")
        val STYLES = listOf("auto", "custom", "classic")
        const val STROKE_ML = 0.022
        val DEFAULTS: JSONObject = JSONObject().put("enabled", false).put("target", 21.0)
            .put("below", 1.0).put("above", 1.0).put("min_on_min", 10.0).put("min_off_min", 10.0)
            .put("min_volt", 11.8).put("offset", 0.0).put("source", "heater").put("phone_offset", 0.0)
            .put("lat", PhoneSensor.VENARIA.first).put("lon", PhoneSensor.VENARIA.second)
            // heating plan (HeatPlan) and sleep / wake-up targets: WHO winter minimum 18°, sleep 18-20°, a van's cold walls ask ~1° more
            .put("style", "auto").put("warm_below", 9.0).put("boost_level", 10).put("boost_until", 17.0)
            .put("approach_level", 2).put("hold_level", 1).put("cold_restart", 16.0)
            .put("sleep_mode", false).put("sleep_at", "22:30").put("wake_at", "07:00").put("day_target", 21.0).put("night_target", 19.0)
            .put("pump_hz_min", 1.6).put("pump_hz_max", 5.5)
    }
}
