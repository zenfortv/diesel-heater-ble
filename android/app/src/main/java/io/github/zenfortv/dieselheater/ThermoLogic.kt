package io.github.zenfortv.dieselheater

import kotlin.math.roundToInt

/* Decisions of the smart thermostat, without Android, so every safety rule is unit-tested
   (src/test/.../ThermoLogicTest.kt). Thermostat.kt feeds it the heater readings and sends the commands.
   Problems (low battery, heater fault, failed starts, sensors disagreeing...) raise an ALARM instead of a silent
   block: the user can always answer "OK, keep working" (ack) and the thermostat carries on. An OK lasts until the
   problem goes away; when it comes back, it asks again. */

data class ThermoSettings(
    val target: Double = 21.0, val below: Double = 1.0, val above: Double = 1.0,
    val minOnMin: Double = 20.0, val minOffMin: Double = 10.0, val minVolt: Double = 11.8, val offset: Double = 0.0,
)

data class HeaterReading(
    val connected: Boolean, val ageS: Double, val burnerOn: Boolean, val step: Int, val error: Int,
    val roomRaw: Double?, val volt: Double?,
    /** with the phone as the room sensor: the heater's own reading (corrected) while it runs, to cross-check */
    val crossRoom: Double? = null,
)

enum class Act { NONE, START, STOP }

/** a problem waiting for the user's OK; key = what the OK answers */
data class Alarm(val key: String, val title: String, val text: String)

/** setActive = false: the heater was stopped on its panel, so the auto thermostat switches itself off. */
data class Decision(val act: Act, val status: String, val log: String?, val alert: String?, val setActive: Boolean?,
                    val alarm: Alarm? = null)

class ThermoLogic {
    var wasOn: Boolean? = null
    var lastOn = 0.0
    var lastOff = 0.0
    var expectOnUntil = 0.0   // we sent the command: the next on/off transition is ours
    var expectOffUntil = 0.0
    var startSentAt = 0.0
    var failedStarts = 0
    var lockout: String? = null
    var lockoutCross = false  // the lockout came from the phone and heater sensors disagreeing
    val acked = mutableSetOf<String>()
    private var lowVoltSince = 0.0
    private val starts = ArrayDeque<Double>()
    private val present = mutableSetOf<String>()

    fun resetLockout() { lockout = null; lockoutCross = false; failedStarts = 0 }

    /** "OK, keep working" */
    fun ack(key: String) {
        if (key == "lockout") {
            if (lockoutCross) acked += "cross"  // the sensors may keep disagreeing: don't stop for it again until they agree
            resetLockout()
        } else acked += key
    }

    /** what must survive an app/phone restart: rest and run times, start count, lockout, OKs */
    fun export(): Map<String, Any?> = mapOf("lastOn" to lastOn, "lastOff" to lastOff, "failedStarts" to failedStarts,
        "lockout" to lockout, "lockoutCross" to lockoutCross, "starts" to starts.toList(), "acked" to acked.toList())

    fun restore(m: Map<String, Any?>) {
        lastOn = (m["lastOn"] as? Number)?.toDouble() ?: 0.0
        lastOff = (m["lastOff"] as? Number)?.toDouble() ?: 0.0
        failedStarts = (m["failedStarts"] as? Number)?.toInt() ?: 0
        lockout = m["lockout"] as? String
        lockoutCross = m["lockoutCross"] as? Boolean ?: false
        starts.clear(); (m["starts"] as? List<*>)?.forEach { (it as? Number)?.let { v -> starts.addLast(v.toDouble()) } }
        acked.clear(); (m["acked"] as? List<*>)?.forEach { (it as? String)?.let(acked::add) }
    }

    /** on = the Auto thermostat switch; eager = it was just switched on (starts below the target, not below the margin) */
    fun step(s: ThermoSettings, on: Boolean, r: HeaterReading, now: Double, eager: Boolean = false): Decision {
        // nothing is decided on old or missing data
        if (!r.connected || r.ageS > STALE_S) return Decision(Act.NONE, "Heater not connected", null, null, null)
        var auto = on
        var setActive: Boolean? = null
        var log: String? = null
        var alert: String? = null
        var alarm: Alarm? = null
        present.clear()
        // a problem is present now; true = not OK'd yet (raise the alarm / hold)
        fun held(key: String): Boolean { present += key; return key !in acked }

        // notice starts/stops, including the ones made on the heater's panel
        if (wasOn != null && r.burnerOn != wasOn) {
            if (r.burnerOn) lastOn = now
            else {
                lastOff = now
                if (auto && now > expectOffUntil) {
                    if (r.error != 0) log = "heater stopped with fault E${r.error.toString().padStart(2, '0')}"
                    else { auto = false; setActive = false; log = "stopped on the heater: auto thermostat switched off" }
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
                    lockout = "$failedStarts starts in a row didn't light"; lockoutCross = false
                    log = "safety: $lockout, waiting for OK"
                }
            }
        }

        // alarms that apply with the Auto thermostat off too (manual use): heater fault, battery low while running
        val faultAlarm = if (r.error != 0 && held("fault")) faultAlarm(r.error) else null
        val volt = r.volt
        if (r.burnerOn && s.minVolt > 0 && volt != null && volt < s.minVolt) {
            if (lowVoltSince == 0.0) lowVoltSince = now
        } else lowVoltSince = 0.0
        // over a minute, so a single odd reading (this heater sometimes reports 10.2 V on a 12 V battery) doesn't ring
        val runVoltAlarm = if (lowVoltSince > 0 && now - lowVoltSince >= LOW_VOLT_S && held("volt_run"))
            Alarm("volt_run", "Battery low: ${fmt(volt!!)} V", "The heater reports ${fmt(volt)} V while running (alarm under ${fmt(s.minVolt)} V). " +
                "Check the battery. If it's really fine (the heater's reading can be off), tap OK and it keeps working.") else null
        if (r.burnerOn && lowVoltSince > 0) present += "volt_run"  // keep the OK while it stays low

        fun d(act: Act, status: String, l: String? = log, a: Alarm? = alarm) =
            Decision(act, status, l, alert, setActive, a ?: faultAlarm ?: runVoltAlarm)
        fun done(dc: Decision): Decision {
            // an OK lasts while its problem lasts ("cross" is cleared only when the two sensors agree again)
            acked.retainAll(present + "cross")
            return dc
        }
        if (!auto) return done(d(Act.NONE, ""))
        // never act on a missing reading (some heaters send none while off): no start on a guessed temperature
        val room = r.roomRaw?.plus(s.offset) ?: return d(Act.NONE,
            if (r.burnerOn) "Waiting for the room temperature" else "No room temperature from the heater while it's off: can't decide when to start")
        val lo = s.target - s.below
        val hi = s.target + s.above
        val lockAlarm = lockout?.let {
            Alarm("lockout", "Auto thermostat stopped", "Stopped: $it. " + (if (lockoutCross) "Is the phone in the van? " else "Check the fuel and the heater. ") +
                "Tap OK to keep working.")
        }

        if ((room < SENSOR_MIN_C || room > SENSOR_MAX_C) && held("sensor")) {
            alarm = Alarm("sensor", "Room sensor reads ${fmt(room)}°", "That can't be right. Check the sensor; tap OK to keep working with it anyway.")
            if (!r.burnerOn) return done(d(Act.NONE, "Room sensor reads ${fmt(room)}°: not starting"))
            expectOffUntil = now + EXPECT_S
            return done(d(Act.STOP, "Room sensor reads ${fmt(room)}°: stopped", "safety stop: room sensor reads ${fmt(room)}°C"))
        }
        if (r.burnerOn) {
            // phone as the sensor but far from what the heater reads (in a pocket, by a window, out of the van):
            // stop and wait for OK, the phone can't be trusted
            if (r.crossRoom != null) {
                val apart = kotlin.math.abs(room - r.crossRoom) > MAX_DISAGREE_C
                if (!apart) acked -= "cross"
                else if ("cross" !in acked && lockout == null) {
                    lockout = "phone reads ${fmt(room)}°, heater ${fmt(r.crossRoom)}°"; lockoutCross = true
                    expectOffUntil = now + EXPECT_S
                    return done(d(Act.STOP, "Stopped: $lockout", "safety stop: $lockout", lockAlarm ?: Alarm("lockout", "Auto thermostat stopped",
                        "Stopped: $lockout. Is the phone in the van? Tap OK to keep working.")))
                }
            }
            // waiting for OK but still burning (the stop didn't get through): keep stopping
            if (lockout != null) { expectOffUntil = now + EXPECT_S; return done(d(Act.STOP, "Stopped: $lockout", null, lockAlarm)) }
            // hard ceiling: stops at once, minimum run or not
            val hot = maxOf(room, r.crossRoom ?: room)
            if (hot >= HARD_MAX_C && held("ceiling")) {
                expectOffUntil = now + EXPECT_S
                alarm = Alarm("ceiling", "Too hot: ${fmt(hot)}°", "Over the ${fmt(HARD_MAX_C)}° safety limit: stopped. Tap OK to let it keep heating anyway.")
                return done(d(Act.STOP, "${fmt(hot)}°: over the ${fmt(HARD_MAX_C)}° safety limit", "safety stop: ${fmt(hot)}°C"))
            }
            if (room < hi) return done(d(Act.NONE, "Heating · stops at ${fmt(hi)}° · ${fmt(hi - room)}° to go"))
            val wait = s.minOnMin * 60 - (now - lastOn)
            if (wait > 0) return done(d(Act.NONE, "Warm · stops in ${mins(wait)} min (minimum run)"))
            expectOffUntil = now + EXPECT_S
            return done(d(Act.STOP, "Stopping · restarts below ${fmt(lo)}°", "auto thermostat: room ${fmt(room)}°C"))
        }

        while (starts.isNotEmpty() && now - starts.first() > 3600) starts.removeFirst()
        val rest = s.minOffMin * 60 - (now - lastOff)
        val cold = room <= (if (eager) s.target - 0.05 else lo)
        return done(when {
            lockout != null -> d(Act.NONE, "Stopped: $lockout · waiting for OK", a = lockAlarm)
            !cold -> d(Act.NONE, "Off · starts below ${fmt(lo)}° · ${fmt(room - lo)}° to go")
            // from here the room is cold: problems that would stop a start ask for OK first
            r.error != 0 && "fault" !in acked -> d(Act.NONE, "Heater fault E${r.error.toString().padStart(2, '0')}: waiting for OK")
            startSentAt > 0 -> d(Act.NONE, "Starting…")
            rest > 0 -> d(Act.NONE, "Resting · can start in ${mins(rest)} min")
            starts.size >= MAX_STARTS_PER_HOUR && held("starts") -> d(Act.NONE, "$MAX_STARTS_PER_HOUR starts in the last hour: waiting for OK",
                a = Alarm("starts", "$MAX_STARTS_PER_HOUR starts in one hour", "The heater was started $MAX_STARTS_PER_HOUR times in the last hour. " +
                    "Tap OK to let it start again now, or it waits."))
            s.minVolt > 0 && volt != null && volt < s.minVolt && held("volt_low") -> d(Act.NONE, "Battery ${fmt(volt)} V: waiting for OK",
                a = Alarm("volt_low", "Battery low: ${fmt(volt)} V", "Under the ${fmt(s.minVolt)} V alarm level, so it hasn't started. " +
                    "If the battery is fine (the heater's reading can be off), tap OK and it starts."))
            else -> {
                expectOnUntil = now + EXPECT_S
                startSentAt = now
                starts.addLast(now)
                d(Act.START, "Starting · target ${fmt(s.target)}°" + if (volt == null) " · battery voltage unknown" else "",
                    "auto thermostat: room ${fmt(room)}°C")
            }
        })
    }

    companion object {
        const val STALE_S = 60.0
        const val START_TIMEOUT_S = 420.0      // self-test + glow plug + ignition take 3-5 min
        const val MAX_FAILED_STARTS = 2         // unlit fuel must not pile up in the burner
        const val MAX_STARTS_PER_HOUR = 3
        const val HARD_MAX_C = 35.0
        const val MAX_DISAGREE_C = 8.0
        const val SENSOR_MIN_C = -40.0
        const val SENSOR_MAX_C = 80.0
        const val EXPECT_S = 180.0
        const val LOW_VOLT_S = 60.0
        fun fmt(v: Double): String { val r = (v * 10).roundToInt() / 10.0; return if (r == r.toInt().toDouble()) r.toInt().toString() else r.toString() }
        private fun mins(s: Double) = (s / 60).roundToInt().coerceAtLeast(1)

        /** fault codes as on the user's heater panel (E01...E08), same table as ERR in web/index.html */
        val FAULTS = mapOf(
            1 to ("Abnormal voltage" to "Battery voltage too low or too high: charge the battery, check the cables and connections."),
            2 to ("Ignition plug fault" to "The glow (ignition) plug isn't working: check its plug and wiring, or replace it."),
            4 to ("Oil pump fault" to "The fuel pump isn't working: check its plug and wiring."),
            5 to ("High temperature protection" to "Overheated: make sure the hot-air outlet and the intake are not blocked, let it cool down."),
            6 to ("Fan fault" to "The blower is blocked or its motor is failing."),
            8 to ("Lack of fuel" to "Fill the tank. Then press and hold the DOWN arrow button on the heater panel to pump fuel automatically " +
                "(primes the line); press the POWER button to stop pumping."),
        )
        fun faultAlarm(code: Int): Alarm {
            val (name, tip) = FAULTS[code] ?: ("Heater fault" to "Check the heater display and its manual.")
            return Alarm("fault", "E${code.toString().padStart(2, '0')} · $name", "$tip Tap OK to keep working (the auto thermostat will try again).")
        }
    }
}
