package io.github.zenfortv.dieselheater

import kotlin.math.roundToInt

/* Decisions of the smart thermostat, without Android, so every safety rule is unit-tested
   (src/test/.../ThermoLogicTest.kt). Thermostat.kt feeds it the heater readings and sends the commands. */

data class ThermoSettings(
    val target: Double = 21.0, val below: Double = 1.0, val above: Double = 1.0,
    val minOnMin: Double = 20.0, val minOffMin: Double = 10.0, val minVolt: Double = 11.8, val offset: Double = 0.0,
)

data class HeaterReading(
    val connected: Boolean, val ageS: Double, val burnerOn: Boolean, val step: Int, val error: Int,
    val roomRaw: Double?, val volt: Double?,
)

enum class Act { NONE, START, STOP }

/** setActive: the thermostat itself was switched on/off (heater started or stopped on its panel). */
data class Decision(val act: Act, val status: String, val log: String?, val alert: String?, val setActive: Boolean?)

class ThermoLogic {
    var wasOn: Boolean? = null
    var lastOn = 0.0
    var lastOff = 0.0
    var expectOnUntil = 0.0   // we sent the command: the next on/off transition is ours
    var expectOffUntil = 0.0
    var startSentAt = 0.0
    var failedStarts = 0
    var lockout: String? = null
    private val starts = ArrayDeque<Double>()

    fun resetLockout() { lockout = null; failedStarts = 0 }

    /** what must survive an app/phone restart: rest and run times, start count, lockout */
    fun export(): Map<String, Any?> = mapOf("lastOn" to lastOn, "lastOff" to lastOff, "failedStarts" to failedStarts,
        "lockout" to lockout, "starts" to starts.toList())

    fun restore(m: Map<String, Any?>) {
        lastOn = (m["lastOn"] as? Number)?.toDouble() ?: 0.0
        lastOff = (m["lastOff"] as? Number)?.toDouble() ?: 0.0
        failedStarts = (m["failedStarts"] as? Number)?.toInt() ?: 0
        lockout = m["lockout"] as? String
        starts.clear(); (m["starts"] as? List<*>)?.forEach { (it as? Number)?.let { v -> starts.addLast(v.toDouble()) } }
    }

    fun step(s: ThermoSettings, enabled: Boolean, active: Boolean, r: HeaterReading, now: Double, eager: Boolean = false): Decision {
        // nothing is decided on old or missing data
        if (!r.connected || r.ageS > STALE_S) return Decision(Act.NONE, "Heater not connected", null, null, null)
        var on = active
        var setActive: Boolean? = null
        var log: String? = null
        var alert: String? = null

        // notice starts/stops, including the ones made on the heater's panel
        if (wasOn != null && r.burnerOn != wasOn) {
            if (r.burnerOn) {
                lastOn = now
                if (enabled && !on && now > expectOnUntil) { on = true; setActive = true; log = "started on the heater: thermostat takes over" }
            } else {
                lastOff = now
                if (enabled && on && now > expectOffUntil) {
                    if (r.error != 0) log = "heater stopped with fault E${r.error}"
                    else { on = false; setActive = false; log = "stopped on the heater: thermostat off" }
                }
            }
        } else if (wasOn == null && r.burnerOn) lastOn = now
        wasOn = r.burnerOn

        // did our last start light? (step 3 = heating)
        if (startSentAt > 0) {
            if (r.step == 3) { startSentAt = 0.0; failedStarts = 0 }
            else if (now - startSentAt > START_TIMEOUT_S && !r.burnerOn) {
                startSentAt = 0.0
                failedStarts++
                lastOff = now  // a start that didn't light also waits the rest time before the next try
                if (failedStarts >= MAX_FAILED_STARTS && lockout == null) {
                    lockout = "$failedStarts starts in a row didn't light"
                    alert = "Smart thermostat stopped: $lockout. Check the fuel and the heater, then switch the thermostat on again."
                    log = "safety: $lockout, no more automatic starts"
                }
            }
        }

        fun d(act: Act, status: String, l: String? = log) = Decision(act, status, l, alert, setActive)
        if (!enabled) return d(Act.NONE, "")
        if (!on) return d(Act.NONE, "Off")
        val room = r.roomRaw?.plus(s.offset) ?: return d(Act.NONE, "Waiting for the room temperature")
        val lo = s.target - s.below
        val hi = s.target + s.above

        if (room < SENSOR_MIN_C || room > SENSOR_MAX_C) {
            if (!r.burnerOn) return d(Act.NONE, "Room sensor reads ${fmt(room)}°: not starting")
            expectOffUntil = now + EXPECT_S
            return d(Act.STOP, "Room sensor reads ${fmt(room)}°: stopped", "safety stop: room sensor reads ${fmt(room)}°C")
        }
        if (r.burnerOn) {
            // hard ceiling: stops at once, minimum run or not
            if (room >= HARD_MAX_C) {
                expectOffUntil = now + EXPECT_S
                return d(Act.STOP, "Room ${fmt(room)}°: over the ${fmt(HARD_MAX_C)}° safety limit", "safety stop: room ${fmt(room)}°C")
            }
            if (room < hi) return d(Act.NONE, "Heating, stops at ${fmt(hi)}°")
            val wait = s.minOnMin * 60 - (now - lastOn)
            if (wait > 0) return d(Act.NONE, "Warm, stops in ${mins(wait)} min (minimum run)")
            expectOffUntil = now + EXPECT_S
            return d(Act.STOP, "Warm enough, restarts below ${fmt(lo)}°", "thermostat: room ${fmt(room)}°C")
        }

        while (starts.isNotEmpty() && now - starts.first() > 3600) starts.removeFirst()
        val rest = s.minOffMin * 60 - (now - lastOff)
        return when {
            lockout != null -> d(Act.NONE, "Stopped: $lockout")
            r.error != 0 -> d(Act.NONE, "Heater fault E${r.error}: not restarting")
            room > (if (eager) s.target - 0.05 else lo) -> d(Act.NONE, "Warm enough, starts below ${fmt(lo)}°")
            startSentAt > 0 -> d(Act.NONE, "Starting…")
            rest > 0 -> d(Act.NONE, "Resting, can start in ${mins(rest)} min")
            starts.size >= MAX_STARTS_PER_HOUR -> d(Act.NONE, "$MAX_STARTS_PER_HOUR starts in the last hour: waiting")
            s.minVolt > 0 && (r.volt ?: 0.0) < s.minVolt -> d(Act.NONE, "Battery ${r.volt?.let { fmt(it) } ?: "?"} V: too low to start")
            else -> {
                expectOnUntil = now + EXPECT_S
                startSentAt = now
                starts.addLast(now)
                d(Act.START, "Starting, target ${fmt(s.target)}°", "thermostat: room ${fmt(room)}°C")
            }
        }
    }

    companion object {
        const val STALE_S = 60.0
        const val START_TIMEOUT_S = 420.0      // self-test + glow plug + ignition take 3-5 min
        const val MAX_FAILED_STARTS = 2         // unlit fuel must not pile up in the burner
        const val MAX_STARTS_PER_HOUR = 3
        const val HARD_MAX_C = 35.0
        const val SENSOR_MIN_C = -40.0
        const val SENSOR_MAX_C = 80.0
        const val EXPECT_S = 180.0
        fun fmt(v: Double): String { val r = (v * 10).roundToInt() / 10.0; return if (r == r.toInt().toDouble()) r.toInt().toString() else r.toString() }
        private fun mins(s: Double) = (s / 60).roundToInt().coerceAtLeast(1)
    }
}
