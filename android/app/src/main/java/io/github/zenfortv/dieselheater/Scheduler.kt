package io.github.zenfortv.dieselheater

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Heating alarms (on at a time, off N minutes later), the switch-off countdown with its 5-minute warning
 *  and snooze, and the safety cap. Same data and API as LocalHub in web/index.html and scheduler.py,
 *  running in the background service so it fires with the screen off.
 *
 *  data: alarms [{id, at "07:00", dur minutes (0 = until stopped), days [0..6] (empty = once), date, mode, value, enabled}],
 *        off_timer epoch | null, off_src label of who set it, max_run_hours */
class Scheduler(private val file: File, private val heater: HeaterLink) {
    private var data = JSONObject()
    private var history = JSONArray()
    private val pend = mutableListOf<JSONObject>()
    private var last = nowS()
    private var onSince: Double? = null
    var thermostat: Thermostat? = null
    var alert: ((String) -> Unit)? = null
    var event: ((String) -> Unit)? = null
    // thermostat messages arrive from its own thread: queued, folded into history under this lock
    private val inbox = java.util.concurrent.ConcurrentLinkedQueue<JSONObject>()

    fun log(action: String, src: String, result: String) {
        inbox += JSONObject().put("t", iso(LocalDateTime.now(), true)).put("action", action).put("src", src).put("result", result)
    }

    private fun drain(): Boolean {
        var any = false
        while (true) { history.put(inbox.poll() ?: break); any = true }
        return any
    }

    init {
        try {
            if (file.exists()) {
                val j = JSONObject(file.readText())
                data = j.optJSONObject("data") ?: JSONObject()
                history = j.optJSONArray("history") ?: JSONArray()
            }
        } catch (_: Exception) {}
        if (!data.has("alarms")) data.put("alarms", JSONArray())
        if (!data.has("off_timer")) data.put("off_timer", JSONObject.NULL)
        if (!data.has("max_run_hours")) data.put("max_run_hours", 0)
        if (migrate()) save()
    }

    /** v2.1: the weekly calendar and one-time timers became alarms */
    private fun migrate(): Boolean {
        if (!data.has("weekly") && !data.has("once")) return false
        val alarms = list()
        data.optJSONArray("weekly")?.let { w ->
            for (i in 0 until w.length()) try {
                val r = w.getJSONObject(i)
                val on = LocalTime.parse(r.getString("on"))
                alarms.put(JSONObject().put("id", newId()).put("at", r.getString("on"))
                    .put("dur", AlarmLogic.slotMinutes(on, LocalTime.parse(r.getString("off"))))
                    .put("days", r.optJSONArray("days") ?: JSONArray()).put("mode", r.opt("mode") ?: "keep")
                    .put("value", r.opt("value") ?: JSONObject.NULL).put("enabled", r.optBoolean("enabled", true)))
            } catch (_: Exception) {}
        }
        data.optJSONArray("once")?.let { o ->
            for (i in 0 until o.length()) try {
                val r = o.getJSONObject(i)
                val at = LocalDateTime.parse(r.getString("at"))
                if (r.optString("action", "on") != "on" || !r.optBoolean("enabled", true) || !at.isAfter(LocalDateTime.now())) continue
                alarms.put(JSONObject().put("id", newId()).put("at", "%02d:%02d".format(at.hour, at.minute)).put("dur", 0)
                    .put("days", JSONArray()).put("date", at.toLocalDate().toString()).put("mode", r.opt("mode") ?: "keep")
                    .put("value", r.opt("value") ?: JSONObject.NULL).put("enabled", true))
            } catch (_: Exception) {}
        }
        data.remove("weekly"); data.remove("once")
        return true
    }

    private fun save() {
        while (history.length() > 30) history.remove(0)
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSONObject().put("data", data).put("history", history).toString())
        tmp.renameTo(file)
    }

    private fun newId() = "%08x".format(kotlin.random.Random.nextInt())
    private fun list(): JSONArray = data.getJSONArray("alarms")
    private fun find(id: String): JSONObject? {
        val a = list()
        for (i in 0 until a.length()) if (a.getJSONObject(i).optString("id") == id) return a.getJSONObject(i)
        return null
    }

    /** one-time alarm switched on / edited: it fires at the next time it comes round */
    private fun fixDate(it: JSONObject) {
        val once = (it.optJSONArray("days")?.length() ?: 0) == 0
        if (once && it.optBoolean("enabled", true)) {
            val at = try { LocalTime.parse(it.getString("at")) } catch (_: Exception) { return }
            it.put("date", AlarmLogic.nextDate(at, LocalDateTime.now()).toString())
        } else if (!once) it.remove("date")
    }

    @Synchronized fun add(body: JSONObject): JSONObject {
        val it = JSONObject(body.toString())
        it.put("id", newId())
        if (!it.has("enabled")) it.put("enabled", true)
        if (!it.has("dur")) it.put("dur", 60)
        fixDate(it)
        list().put(it); save()
        return it
    }

    @Synchronized fun update(id: String, patch: JSONObject): JSONObject? {
        val it = find(id) ?: return null
        for (k in patch.keys()) it.put(k, patch.get(k))
        it.put("id", id)
        fixDate(it); save()
        return it
    }

    @Synchronized fun delete(id: String) {
        val a = list()
        for (i in a.length() - 1 downTo 0) if (a.getJSONObject(i).optString("id") == id) a.remove(i)
        save()
    }

    @Synchronized fun setOffTimer(minutes: Double?, src: String = "countdown") {
        if (minutes != null && minutes > 0) data.put("off_timer", nowS() + minutes * 60).put("off_src", src)
        else data.put("off_timer", JSONObject.NULL).remove("off_src")
        save()
    }

    private fun offAt(): Double? = if (data.isNull("off_timer")) null else data.optDouble("off_timer")

    /** from the 5-minute warning: keep it warm N more minutes, or (null) keep it on until stopped */
    @Synchronized fun snooze(minutes: Double?): JSONObject {
        val t = nowS()
        val src = data.optString("off_src", "countdown")
        if (minutes == null || minutes <= 0) {
            data.put("off_timer", JSONObject.NULL).remove("off_src")
            history.put(JSONObject().put("t", iso(LocalDateTime.now(), true)).put("action", "keep on").put("src", src).put("result", "until stopped"))
        } else {
            data.put("off_timer", AlarmLogic.extend(offAt(), t, minutes)).put("off_src", src)
            history.put(JSONObject().put("t", iso(LocalDateTime.now(), true)).put("action", "snooze").put("src", src).put("result", "+${minutes.toInt()} min"))
        }
        save()
        return snapshot()
    }

    /** the planned switch-off while it is inside the warning window, else null */
    @Synchronized fun warnAt(): Double? = offAt()?.takeIf { AlarmLogic.warnDue(it, nowS()) && heater.running }

    @Synchronized fun setMaxRun(hours: Double) { data.put("max_run_hours", maxOf(0.0, hours)); save() }

    @Synchronized fun snapshot(): JSONObject {
        if (drain()) save()
        val o = JSONObject(data.toString())
        o.put("next", nextEvents(6)).put("warn_s", AlarmLogic.WARN_S)
        val h = JSONArray()
        for (i in maxOf(0, history.length() - 15) until history.length()) h.put(history.get(i))
        return o.put("history", h)
    }

    private val zone get() = ZoneId.systemDefault()
    private fun epoch(t: LocalDateTime) = t.atZone(zone).toEpochSecond().toDouble()
    private fun local(s: Double) = LocalDateTime.ofInstant(Instant.ofEpochMilli((s * 1000).toLong()), zone)

    private fun alarm(j: JSONObject): HeatAlarm? = try {
        val d = j.optJSONArray("days") ?: JSONArray()
        HeatAlarm(j.getString("id"), LocalTime.parse(j.getString("at")), j.optInt("dur", 0),
            (0 until d.length()).map { d.getInt(it) }.toSet(),
            j.optString("date").takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) }, j.optBoolean("enabled", true))
    } catch (_: Exception) { null }

    private fun events(a: Double, b: Double): List<Pair<Double, JSONObject>> {
        val out = mutableListOf<Pair<Double, JSONObject>>()
        val from = local(a); val to = local(b)
        val alarms = list()
        for (i in 0 until alarms.length()) {
            val j = alarms.getJSONObject(i)
            val al = alarm(j) ?: continue
            for (t in AlarmLogic.starts(al, from, to))
                out += epoch(t) to JSONObject().put("action", "on").put("mode", j.opt("mode") ?: JSONObject.NULL)
                    .put("value", j.opt("value") ?: JSONObject.NULL).put("src", "timer ${j.getString("at")}")
                    .put("alarm_id", al.id).put("dur", al.durMin).put("once", al.days.isEmpty())
        }
        offAt()?.let { out += it to JSONObject().put("action", "off").put("src", data.optString("off_src", "countdown")).put("off_timer", true) }
        return out.filter { it.first > a && it.first <= b }.sortedBy { it.first }
    }

    private fun nextEvents(n: Int): JSONArray {
        val t = nowS()
        val a = JSONArray()
        for ((at, e) in events(t, t + 8 * 86400).take(n)) {
            a.put(JSONObject().put("at", iso(local(at))).put("action", e.getString("action")).put("mode", e.opt("mode") ?: JSONObject.NULL)
                .put("value", e.opt("value") ?: JSONObject.NULL).put("src", e.getString("src")).put("dur", e.opt("dur") ?: JSONObject.NULL))
        }
        return a
    }

    fun nextEvent(): JSONObject? = synchronized(this) { nextEvents(1).optJSONObject(0) }

    private fun exec(e: JSONObject): Boolean {
        if (e.getString("action") == "off") return heater.power(false)
        var ok = heater.power(true)
        val v = e.optDouble("value", 0.0)
        if (ok && e.optString("mode") == "temp" && v > 0) ok = heater.setTemperature(v)
        else if (ok && e.optString("mode") == "level" && v > 0) ok = heater.setLevel(v)
        return ok
    }

    private fun record(e: JSONObject, result: String) {
        history.put(JSONObject().put("t", iso(LocalDateTime.now(), true)).put("action", e.getString("action"))
            .put("src", e.getString("src")).put("result", result))
    }

    private fun hhmm(s: Double) = local(s).let { "%02d:%02d".format(it.hour, it.minute) }

    @Synchronized fun tick() {
        val t = nowS()
        var changed = drain()
        for ((at, e) in events(last, t)) {
            if (e.optBoolean("once")) find(e.getString("alarm_id"))?.put("enabled", false)
            if (e.optBoolean("off_timer")) data.put("off_timer", JSONObject.NULL).remove("off_src")
            changed = true
            // the app was not running (phone restarted, app killed): don't fire old alarms late,
            // only a missed switch-off still runs
            if (t - at > STALE_SEC && !e.optBoolean("off_timer")) { record(e, "missed (app was not running)"); continue }
            // Auto thermostat on: it alone switches the heater, timers are locked
            if (thermostat?.enabled == true) { record(e, "skipped (auto thermostat on)"); continue }
            e.put("deadline", t + 600).put("at", at)
            pend += e
        }
        last = t
        val cap = data.optDouble("max_run_hours", 0.0)
        onSince = if (heater.running && thermostat?.enabled != true) (onSince ?: t) else null
        if (cap > 0 && onSince != null && t - onSince!! > cap * 3600) {
            pend += JSONObject().put("action", "off").put("src", "safety cap ${cap}h").put("deadline", t + 600)
            onSince = t
        }
        if (pend.size > 1) {
            pend.dropLast(1).forEach { record(it, "superseded") }
            val keep = pend.last(); pend.clear(); pend += keep
            changed = true
        }
        for (e in pend.toList()) {
            if (thermostat?.enabled == true) { record(e, "skipped (auto thermostat on)"); pend.remove(e); changed = true; continue }
            if (exec(e)) {
                record(e, "ok"); pend.remove(e); changed = true
                val src = e.getString("src")
                if (e.getString("action") == "on") {
                    // the alarm's run time becomes the switch-off countdown (snooze / keep on work on it)
                    val dur = e.optInt("dur", 0)
                    if (dur > 0) data.put("off_timer", e.getDouble("at") + dur * 60).put("off_src", src)
                    else data.put("off_timer", JSONObject.NULL).remove("off_src")
                    event?.invoke("Heater started by $src · " + if (dur > 0) "switches off at ${hhmm(e.getDouble("at") + dur * 60)}" else "stays on until you stop it")
                } else event?.invoke("Heater stopped by " + when {
                    src == "countdown" -> "the countdown"
                    src.startsWith("safety") -> "the $src"
                    else -> src
                })
            }
            else if (t > e.getDouble("deadline")) {
                record(e, "FAILED (heater unreachable)"); pend.remove(e); changed = true
                if (e.getString("action") == "off") alert?.invoke("Timer couldn't switch the heater off (${e.getString("src")}). Check it.")
            }
        }
        if (changed) save()
    }

    companion object { const val STALE_SEC = 300 }
}
