package io.github.zenfortv.dieselheater

import android.content.Context
import org.json.JSONObject
import kotlin.math.roundToInt

/** Smart thermostat: the heater's own thermostat mode only turns the power down near the target and never
 *  stops, so here the burner is switched fully on below the target and off above it. The room sensor keeps
 *  reporting with the burner off. Limits protect the heater: hysteresis, minimum run / rest time
 *  (each start runs the glow plug and short runs coke the burner), low battery, no restart on a fault. */
class Thermostat(ctx: Context, private val heater: HeaterLink, private val log: (action: String, result: String) -> Unit) {
    private val prefs = ctx.getSharedPreferences("heater", Context.MODE_PRIVATE)
    private val cfg = JSONObject(DEFAULTS.toString())
    private var wasOn: Boolean? = null
    private var lastOn = 0.0
    private var lastOff = 0.0
    private var expectOnUntil = 0.0   // we sent the command: the next on/off transition is ours
    private var expectOffUntil = 0.0
    @Volatile private var status = ""

    init {
        try { val saved = JSONObject(prefs.getString("thermo", "{}") ?: "{}"); for (k in saved.keys()) cfg.put(k, saved.get(k)) } catch (_: Exception) {}
    }

    val enabled get() = cfg.optBoolean("enabled")
    private val active get() = cfg.optBoolean("active")
    private fun d(k: String) = cfg.optDouble(k, DEFAULTS.optDouble(k))
    private fun save() = prefs.edit().putString("thermo", cfg.toString()).apply()
    private fun room(): Double? = heater.frame().let { if (it.has("cab_temperature")) it.getDouble("cab_temperature") + d("offset") else null }
    private fun r1(v: Double) = (v * 10).roundToInt() / 10.0
    private fun fmt(v: Double) = if (v == v.toInt().toDouble()) v.toInt().toString() else v.toString()

    @Synchronized fun state(): JSONObject {
        val o = JSONObject(cfg.toString()).put("status", status)
        room()?.let { o.put("room", r1(it)) }
        return o
    }

    @Synchronized fun configure(b: JSONObject) {
        val wasEnabled = enabled
        for (k in KEYS) if (b.has(k)) cfg.put(k, b.get(k))
        cfg.put("below", d("below").coerceIn(0.2, 5.0)).put("above", d("above").coerceIn(0.2, 5.0))
            .put("min_on_min", d("min_on_min").coerceIn(5.0, 120.0)).put("min_off_min", d("min_off_min").coerceIn(3.0, 120.0))
            .put("offset", d("offset").coerceIn(-5.0, 5.0)).put("target", d("target").coerceIn(8.0, 36.0))
        // switched on while the heater runs: carry on holding the target; switched off: leave the heater as it is
        if (enabled != wasEnabled) cfg.put("active", enabled && heater.running)
        save()
        tick()
    }

    @Synchronized fun setTarget(t: Double) { cfg.put("target", t.coerceIn(8.0, 36.0)); save() }

    /** Power button / timers: on = hold the target (starts now if the room is below it), off = stop for real. */
    @Synchronized fun setActive(on: Boolean, src: String): Boolean {
        cfg.put("active", on); save()
        if (!on) {
            status = "Off"
            if (!heater.running) return true
            expectOffUntil = nowS() + 180
            return heater.power(false)
        }
        log("on", "$src: thermostat ${fmt(d("target"))}°C")
        tick(eager = true)
        return true
    }

    @Synchronized fun tick(eager: Boolean = false) {
        val f = heater.frame()
        val t = nowS()
        if (!heater.connected) { status = "Heater not connected"; return }
        val on = f.optInt("running_state", 0) == 1
        val err = f.optInt("error_code", 0)
        // notice starts/stops, including the ones made on the heater's panel
        if (wasOn != null && on != wasOn) {
            if (on) {
                lastOn = t
                if (enabled && !active && t > expectOnUntil) { cfg.put("active", true); save(); log("on", "started on the heater: thermostat takes over") }
            } else {
                lastOff = t
                if (enabled && active && t > expectOffUntil) {
                    if (err != 0) log("off", "heater stopped with fault E$err")
                    else { cfg.put("active", false); save(); log("off", "stopped on the heater: thermostat off") }
                }
            }
        } else if (wasOn == null && on) lastOn = t
        wasOn = on
        if (!enabled) { status = ""; return }
        if (!active) { status = "Off"; return }
        val room = room() ?: run { status = "Waiting for the room temperature"; return }
        val target = d("target")
        val lo = target - d("below"); val hi = target + d("above")
        if (on) {
            if (room < hi) { status = "Heating, stops at ${fmt(r1(hi))}°"; return }
            val wait = d("min_on_min") * 60 - (t - lastOn)
            if (wait > 0) { status = "Warm, stops in ${(wait / 60).roundToInt().coerceAtLeast(1)} min (minimum run)"; return }
            expectOffUntil = t + 180
            val ok = heater.power(false)
            log("off", "thermostat: room ${fmt(r1(room))}°C" + if (ok) "" else " (no answer, retrying)")
            status = "Warm enough, restarts below ${fmt(r1(lo))}°"
            return
        }
        val volt = f.optDouble("supply_voltage", 99.0)
        val minVolt = d("min_volt")
        val rest = d("min_off_min") * 60 - (t - lastOff)
        when {
            err != 0 -> status = "Heater fault E$err: not restarting"
            room > (if (eager) target - 0.05 else lo) -> status = "Warm enough, starts below ${fmt(r1(lo))}°"
            rest > 0 -> status = "Resting, can start in ${(rest / 60).roundToInt().coerceAtLeast(1)} min"
            minVolt > 0 && volt < minVolt -> status = "Battery ${fmt(volt)} V: too low to start"
            else -> {
                expectOnUntil = t + 180
                val ok = heater.power(true) && heater.setTemperature(target)
                log("on", "thermostat: room ${fmt(r1(room))}°C" + if (ok) "" else " (no answer, retrying)")
                if (ok) lastOff = t  // don't hammer the heater if the start doesn't show up
                status = "Starting, target ${fmt(target)}°"
            }
        }
    }

    companion object {
        private val KEYS = listOf("enabled", "target", "below", "above", "min_on_min", "min_off_min", "min_volt", "offset")
        val DEFAULTS: JSONObject = JSONObject().put("enabled", false).put("active", false).put("target", 21.0)
            .put("below", 1.0).put("above", 1.0).put("min_on_min", 20.0).put("min_off_min", 10.0)
            .put("min_volt", 11.8).put("offset", 0.0)
    }
}
