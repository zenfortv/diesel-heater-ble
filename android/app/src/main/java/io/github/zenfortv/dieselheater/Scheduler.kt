package io.github.zenfortv.dieselheater

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Weekly / one-time / countdown timers + safety cap. Same data and API as LocalHub in web/index.html
 *  and scheduler.py, but running in the background service so it fires with the screen off. */
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
        if (!data.has("weekly")) data.put("weekly", JSONArray())
        if (!data.has("once")) data.put("once", JSONArray())
        if (!data.has("off_timer")) data.put("off_timer", JSONObject.NULL)
        if (!data.has("max_run_hours")) data.put("max_run_hours", 0)
    }

    private fun save() {
        while (history.length() > 30) history.remove(0)
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSONObject().put("data", data).put("history", history).toString())
        tmp.renameTo(file)
    }

    private fun list(kind: String): JSONArray = data.getJSONArray(kind)
    private fun find(kind: String, id: String): JSONObject? {
        val a = list(kind)
        for (i in 0 until a.length()) if (a.getJSONObject(i).optString("id") == id) return a.getJSONObject(i)
        return null
    }

    @Synchronized fun add(kind: String, body: JSONObject): JSONObject {
        val it = JSONObject(body.toString())
        it.put("id", "%08x".format(kotlin.random.Random.nextInt()))
        if (!it.has("enabled")) it.put("enabled", true)
        list(kind).put(it); save()
        return it
    }

    @Synchronized fun update(kind: String, id: String, patch: JSONObject): JSONObject? {
        val it = find(kind, id) ?: return null
        for (k in patch.keys()) it.put(k, patch.get(k))
        it.put("id", id); save()
        return it
    }

    @Synchronized fun delete(kind: String, id: String) {
        val a = list(kind)
        for (i in a.length() - 1 downTo 0) if (a.getJSONObject(i).optString("id") == id) a.remove(i)
        save()
    }

    @Synchronized fun setOffTimer(minutes: Double?) {
        data.put("off_timer", if (minutes != null && minutes > 0) nowS() + minutes * 60 else JSONObject.NULL); save()
    }

    @Synchronized fun setMaxRun(hours: Double) { data.put("max_run_hours", maxOf(0.0, hours)); save() }

    @Synchronized fun snapshot(): JSONObject {
        if (drain()) save()
        val o = JSONObject(data.toString())
        o.put("next", nextEvents(6))
        val h = JSONArray()
        for (i in maxOf(0, history.length() - 15) until history.length()) h.put(history.get(i))
        return o.put("history", h)
    }

    private val zone get() = ZoneId.systemDefault()
    private fun epoch(t: LocalDateTime) = t.atZone(zone).toEpochSecond().toDouble()

    private fun events(a: Double, b: Double): List<Pair<Double, JSONObject>> {
        val out = mutableListOf<Pair<Double, JSONObject>>()
        var day = Instant.ofEpochMilli((a * 1000).toLong()).atZone(zone).toLocalDate().minusDays(1)
        val endDay = Instant.ofEpochMilli((b * 1000).toLong()).atZone(zone).toLocalDate()
        val weekly = list("weekly")
        while (!day.isAfter(endDay)) {
            val wd = day.dayOfWeek.value - 1  // Mon = 0
            for (i in 0 until weekly.length()) {
                val r = weekly.getJSONObject(i)
                if (!r.optBoolean("enabled", true)) continue
                val days = r.optJSONArray("days") ?: continue
                if ((0 until days.length()).none { days.getInt(it) == wd }) continue
                val on = day.atTime(LocalTime.parse(r.getString("on")))
                var off = day.atTime(LocalTime.parse(r.getString("off")))
                if (!off.isAfter(on)) off = off.plusDays(1)
                val src = "weekly ${r.getString("on")}-${r.getString("off")}"
                out += epoch(on) to JSONObject().put("action", "on").put("mode", r.opt("mode") ?: JSONObject.NULL)
                    .put("value", r.opt("value") ?: JSONObject.NULL).put("src", src)
                out += epoch(off) to JSONObject().put("action", "off").put("src", src)
            }
            day = day.plusDays(1)
        }
        val once = list("once")
        for (i in 0 until once.length()) {
            val o = once.getJSONObject(i)
            if (!o.optBoolean("enabled", true)) continue
            val at = try { LocalDateTime.parse(o.getString("at")) } catch (_: Exception) { continue }
            out += epoch(at) to JSONObject().put("action", o.optString("action", "on")).put("mode", o.opt("mode") ?: JSONObject.NULL)
                .put("value", o.opt("value") ?: JSONObject.NULL).put("src", "once ${o.getString("at")}").put("once_id", o.getString("id"))
        }
        if (!data.isNull("off_timer")) out += data.getDouble("off_timer") to JSONObject().put("action", "off").put("src", "countdown")
        return out.filter { it.first > a && it.first <= b }.sortedBy { it.first }
    }

    private fun nextEvents(n: Int): JSONArray {
        val t = nowS()
        val a = JSONArray()
        for ((at, e) in events(t, t + 8 * 86400).take(n)) {
            a.put(JSONObject().put("at", iso(LocalDateTime.ofInstant(Instant.ofEpochSecond(at.toLong()), zone)))
                .put("action", e.getString("action")).put("mode", e.opt("mode") ?: JSONObject.NULL)
                .put("value", e.opt("value") ?: JSONObject.NULL).put("src", e.getString("src")))
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

    @Synchronized fun tick() {
        val t = nowS()
        var changed = drain()
        for ((at, e) in events(last, t)) {
            if (e.has("once_id")) find("once", e.getString("once_id"))?.put("enabled", false)
            if (e.getString("src") == "countdown") data.put("off_timer", JSONObject.NULL)
            changed = true
            // the app was not running (phone restarted, app killed): don't fire old timers late,
            // only a missed countdown off still runs
            if (t - at > STALE_SEC && e.getString("src") != "countdown") { record(e, "missed (app was not running)"); continue }
            // Auto thermostat on: it alone switches the heater, timers are locked
            if (thermostat?.enabled == true) { record(e, "skipped (auto thermostat on)"); continue }
            e.put("deadline", t + 600)
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
                event?.invoke("Heater ${if (e.getString("action") == "on") "started" else "stopped"} by ${e.getString("src").let { if (it == "countdown") "the countdown" else if (it.startsWith("safety")) "the $it" else "timer $it" }}")
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
