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
                "starts" to (0 until (starts?.length() ?: 0)).map { starts!!.getDouble(it) }))
        } catch (_: Exception) {}
    }

    @Volatile var enabled = cfg.optBoolean("enabled")
        private set
    private fun d(k: String) = cfg.optDouble(k, DEFAULTS.optDouble(k))
    private fun save() = prefs.edit().putString("thermo", cfg.toString()).apply()
    private fun settings() = ThermoSettings(d("target"), d("below"), d("above"), d("min_on_min"), d("min_off_min"), d("min_volt"), d("offset"))

    private var savedMem = ""
    private fun saveMem() {
        val m = logic.export()
        val j = JSONObject().put("lastOn", m["lastOn"]).put("lastOff", m["lastOff"]).put("failedStarts", m["failedStarts"])
            .put("lockout", m["lockout"] ?: JSONObject.NULL).put("starts", JSONArray(m["starts"] as List<*>)).toString()
        if (j != savedMem) { savedMem = j; prefs.edit().putString("thermo_mem", j).apply() }
    }

    @Synchronized fun state(): JSONObject {
        val o = JSONObject(cfg.toString()).put("status", status)
        val f = heater.frame()
        if (f.has("cab_temperature")) o.put("room", (f.getDouble("cab_temperature") + d("offset")).let { Math.round(it * 10) / 10.0 })
        logic.lockout?.let { o.put("lockout", it) }
        return o
    }

    /** margins, minimum times, battery limit, sensor correction (not the switch) */
    @Synchronized fun configure(b: JSONObject) {
        for (k in KEYS) if (b.has(k)) cfg.put(k, b.get(k))
        // floors that protect the heater, whatever the page sends
        cfg.put("below", d("below").coerceIn(0.5, 5.0)).put("above", d("above").coerceIn(0.5, 5.0))
            .put("min_on_min", d("min_on_min").coerceIn(10.0, 120.0)).put("min_off_min", d("min_off_min").coerceIn(5.0, 120.0))
            .put("offset", d("offset").coerceIn(-5.0, 5.0)).put("target", d("target").coerceIn(8.0, MAX_TARGET))
            .put("min_volt", d("min_volt").coerceIn(0.0, 13.0))
        save()
        tick()
    }

    @Synchronized fun setTarget(t: Double) { cfg.put("target", t.coerceIn(8.0, MAX_TARGET)); save() }

    /** The Auto thermostat switch. On: hold the target (starts now if the room is below it).
     *  Off: the heater is stopped, back to manual control. */
    @Synchronized fun setEnabled(on: Boolean): Boolean {
        enabled = on
        cfg.put("enabled", on); save()
        if (on) {
            logic.resetLockout()  // switching it on by hand is the reset after a lockout
            // the heater's own auto start/stop would stop the burner behind our back
            if (heater.frame().optBoolean("auto_start_stop")) {
                val ok = heater.setAutoStartStop(false)
                log("off", "heater's own auto start/stop switched off" + if (ok) "" else " (no answer)")
            }
            log("on", "auto thermostat on, target ${ThermoLogic.fmt(d("target"))}°C")
            tick(eager = true)
            return true
        }
        status = ""
        log("off", "auto thermostat off")
        if (!heater.running) return true
        logic.expectOffUntil = nowS() + ThermoLogic.EXPECT_S
        val ok = heater.power(false)
        if (ok) event("Auto thermostat off · heater stopped")
        else alert("Couldn't switch the heater off (no answer). Check it.")
        return ok
    }

    @Synchronized fun tick(eager: Boolean = false) {
        val f = heater.frame()
        val r = HeaterReading(
            connected = heater.connected, ageS = nowS() - heater.updatedAt,
            burnerOn = f.optInt("running_state", 0) == 1, step = f.optInt("running_step", 0), error = f.optInt("error_code", 0),
            roomRaw = if (f.has("cab_temperature")) f.getDouble("cab_temperature") else null,
            volt = if (f.has("supply_voltage")) f.getDouble("supply_voltage") else null,
        )
        val dcs = logic.step(settings(), enabled, r, nowS(), eager)
        dcs.setActive?.let { enabled = it; cfg.put("enabled", it); save() }
        status = dcs.status
        dcs.alert?.let(alert)
        val room = r.roomRaw?.let { ThermoLogic.fmt(it + d("offset")) } ?: "?"
        when (dcs.act) {
            Act.START -> {
                val ok = heater.power(true) && heater.setTemperature(d("target"))
                log("on", dcs.log + if (ok) "" else " (no answer)")
                if (ok) event("Heater started by the auto thermostat · room $room° · target ${ThermoLogic.fmt(d("target"))}°")
            }
            Act.STOP -> {
                val ok = heater.power(false)
                log("off", dcs.log + if (ok) "" else " (no answer, retrying)")
                if (ok) event("Heater stopped by the auto thermostat · room $room°")
                else alert("Couldn't switch the heater off (no answer). Check it.")
            }
            Act.NONE -> dcs.log?.let {
                log(if (r.burnerOn) "on" else "off", it)
                if (dcs.setActive == false) event("Heater stopped on its panel · Auto thermostat switched off")
            }
        }
        saveMem()
    }

    companion object {
        const val MAX_TARGET = 30.0
        private val KEYS = listOf("target", "below", "above", "min_on_min", "min_off_min", "min_volt", "offset")
        val DEFAULTS: JSONObject = JSONObject().put("enabled", false).put("target", 21.0)
            .put("below", 1.0).put("above", 1.0).put("min_on_min", 20.0).put("min_off_min", 10.0)
            .put("min_volt", 11.8).put("offset", 0.0)
    }
}
