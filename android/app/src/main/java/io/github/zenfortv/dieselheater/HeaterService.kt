package io.github.zenfortv.dieselheater

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS

/** Foreground service: stays alive with the screen off, holds the heater link, runs the timers
 *  and serves the page + API on 127.0.0.1:8770. */
class HeaterService : Service() {
    lateinit var heater: HeaterLink
        private set
    private lateinit var sched: Scheduler
    private lateinit var thermo: Thermostat
    private lateinit var web: WebServer
    private lateinit var wake: PowerManager.WakeLock
    private val ticker = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var lastNote = ""
    @Volatile private var lastNoteAt = 0L

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Heater connection", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Keeps the heater connected so timers work with the screen off"
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(ALERTS, "Safety alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "The heater couldn't be switched off, failed starts, connection lost while running"
        })
        nm.createNotificationChannel(NotificationChannel(EVENTS, "Heater started / stopped", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Every start and stop made by the auto thermostat, a timer or the countdown"
        })
        val n = note("Starting…")
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTE_ID, n)

        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DieselHeater:link")
        wake.setReferenceCounted(false)
        wake.acquire()

        heater = HeaterLink(this) { onState() }
        sched = Scheduler(File(filesDir, "schedules.json"), heater)
        thermo = Thermostat(this, heater, { action, result -> sched.log(action, "thermostat", result) }, ::alert, ::event, ::alarmNote)
        sched.thermostat = thermo
        thermo.phone.startWeather()
        sched.alert = ::alert
        sched.event = ::event
        web = WebServer(this, ::api) { stateMsg() }
        try { web.start(PORT) } catch (_: Exception) {}
        heater.start()
        ticker.scheduleWithFixedDelay({ try { sched.tick() } catch (_: Exception) {} }, 5, 5, SECONDS)
        ticker.scheduleWithFixedDelay({ try { thermo.tick() } catch (_: Exception) {} }, 8, 5, SECONDS)
        ticker.scheduleWithFixedDelay({ releaseCheck(); updateNote(force = true); watchdog() }, 30, 30, SECONDS)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_QUIT) {
            getSharedPreferences("heater", MODE_PRIVATE).edit().putBoolean("quit", true).apply()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RELEASE) release(RELEASE_S)
        if (intent?.action == ACTION_RECONNECT) { releaseUntil = 0.0; heater.setPaused(false) }
        if (intent?.action == ACTION_ACK) intent.getStringExtra("key")?.let { k -> ticker.execute { try { thermo.ack(k) } catch (_: Exception) {} } }
        return START_STICKY
    }

    // the heater takes one Bluetooth connection: free it for the AirHeaterBLE app for a while, then come back
    @Volatile private var releaseUntil = 0.0
    private fun release(seconds: Double) {
        releaseUntil = nowS() + seconds
        heater.setPaused(true)
        updateNote(force = true)
    }
    private fun releaseCheck() {
        if (releaseUntil > 0 && nowS() > releaseUntil) {
            releaseUntil = 0.0
            heater.setPaused(false)
            event("Reconnecting to the heater (Bluetooth release ended)")
        }
    }

    override fun onDestroy() {
        instance = null
        ticker.shutdownNow()
        if (::thermo.isInitialized) thermo.phone.stop()
        bus.shutdownNow()
        web.stop()
        heater.stop()
        if (wake.isHeld) wake.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // the heater keeps running on its own if the link drops: tell the user instead of failing silently
    private var lostSince = 0.0
    private fun watchdog() {
        if (heater.connected || heater.paused || !heater.running) { lostSince = 0.0; return }
        if (lostSince == 0.0) { lostSince = nowS(); return }
        if (nowS() - lostSince > 120 && lostSince > 0) {
            alert("Lost the heater while it was running. It keeps running on its own: check it.")
            lostSince = -1.0  // once per loss
        }
    }

    private val lastAlert = HashMap<String, Long>()
    private fun alert(msg: String) {
        val t = System.currentTimeMillis()
        if (t - (lastAlert[msg] ?: 0L) < 10 * 60_000) return  // same alert at most every 10 min
        lastAlert[msg] = t
        sched.log("alert", "safety", msg)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        getSystemService(NotificationManager::class.java).notify(100 + (msg.hashCode() and 0xff),
            Notification.Builder(this, ALERTS).setSmallIcon(R.drawable.ic_flame).setContentTitle("Diesel heater")
                .setContentText(msg).setStyle(Notification.BigTextStyle().bigText(msg))
                .setContentIntent(open).setAutoCancel(true).build())
    }

    /* alarm: one notification with "OK, keep working", replaced when the alarm changes, removed when it's gone */
    private var alarmShown: Alarm? = null
    private fun alarmNote(a: Alarm?) {
        val nm = getSystemService(NotificationManager::class.java)
        if (a == null) { nm.cancel(ALARM_ID); alarmShown = null; onState(); return }
        val same = alarmShown?.key == a.key && alarmShown?.title == a.title
        alarmShown = a
        onState()
        if (same) return
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val ok = PendingIntent.getService(this, 3, Intent(this, HeaterService::class.java).setAction(ACTION_ACK).putExtra("key", a.key),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        sched.log("alert", "alarm", a.title)
        nm.notify(ALARM_ID, Notification.Builder(this, ALERTS).setSmallIcon(R.drawable.ic_flame).setContentTitle(a.title)
            .setContentText(a.text).setStyle(Notification.BigTextStyle().bigText(a.text))
            .setContentIntent(open).setOnlyAlertOnce(false)
            .addAction(Notification.Action.Builder(null, "OK, keep working", ok).build()).build())
    }

    private var eventId = 0
    private fun event(msg: String) {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        getSystemService(NotificationManager::class.java).notify(200 + (eventId++ % 3),  // keep the last 3
            Notification.Builder(this, EVENTS).setSmallIcon(R.drawable.ic_flame).setContentTitle("Diesel heater")
                .setContentText(msg).setStyle(Notification.BigTextStyle().bigText(msg))
                .setContentIntent(open).setAutoCancel(true).build())
    }

    private val appVersion by lazy { try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (_: Exception) { "" } }
    private fun fullState(): JSONObject = heater.state().put("smart", if (::thermo.isInitialized) thermo.state() else Thermostat.DEFAULTS)
        .put("app_version", appVersion)
    private fun stateMsg() = JSONObject().put("type", "state").put("state", fullState()).toString()

    // called from the Bluetooth threads: never block them (the thermostat/scheduler may hold their lock
    // while waiting for exactly that thread to deliver the heater's reply)
    private val bus = Executors.newSingleThreadExecutor()
    private fun onState() = try { bus.execute { publishNow() } } catch (_: Exception) {}
    private fun publishNow() {
        web.broadcast(stateMsg())
        updateNote(force = false)
    }

    private fun api(method: String, p: String, b: JSONObject): Any? {
        val h = heater
        fun ok(v: Boolean) = JSONObject().put("ok", v)
        return when (p) {
            "state" -> JSONObject().put("heater", fullState()).put("paused", h.paused).put("simulated", false).put("schedule", sched.snapshot())
            "auto" -> {
                if (b.optBoolean("on")) sched.setOffTimer(null)  // nothing else switches the heater while it is on
                val r = thermo.setEnabled(b.optBoolean("on")); onState(); ok(r)
            }
            // locked while the Auto thermostat is on (the page greys them out too)
            "power", "level", "auto_start_stop", "builtin_timer", "off_timer", "max_run" ->
                if (thermo.enabled) JSONObject().put("ok", false).put("locked", true).put("error", "Locked while Auto thermostat is on")
                else when (p) {
                    "power" -> ok(h.power(b.optBoolean("on")))
                    "level" -> ok(h.setLevel(b.optDouble("level")))
                    "auto_start_stop" -> ok(h.setAutoStartStop(b.optBoolean("on")))
                    "builtin_timer" -> ok(h.setBuiltinTimer(b.getString("start"), b.optInt("duration", 60), b.optBoolean("enabled")))
                    "off_timer" -> { sched.setOffTimer(if (b.isNull("minutes")) null else b.optDouble("minutes")); sched.snapshot() }
                    else -> { sched.setMaxRun(b.optDouble("hours", 0.0)); sched.snapshot() }
                }
            "temperature" -> {
                if (thermo.enabled) thermo.setTarget(b.optDouble("temperature"))
                // auto thermostat with the burner off: the target is stored, the heater gets it at the next start
                ok(if (thermo.enabled && !h.running) true else h.setTemperature(b.optDouble("temperature")))
            }
            "smart" -> { thermo.configure(b); onState(); thermo.state() }
            "ack" -> { thermo.ack(b.getString("key")); onState(); ok(true) }
            "sync_clock" -> ok(h.syncClock(true))
            "set_clock" -> ok(h.setClock(b.getString("time")))
            "pause" -> { releaseUntil = 0.0; h.setPaused(b.optBoolean("paused")); ok(true) }  // from the page: until switched back
            "pin" -> { h.pin = b.optInt("pin", 1234); onState(); ok(true) }
            "schedule" -> sched.snapshot()
            "raw" -> JSONObject().put("frames", org.json.JSONArray(h.rawLog()))
            // readings from a real thermometer: POST {real} adds one, {delete: t} / {clear: true} remove
            "calib" -> {
                val ph = thermo.phone
                when {
                    method != "POST" -> ph.calib()
                    b.has("real") -> ph.addReading(b.getDouble("real"), h.frame().optDouble("cab_temperature").takeUnless { it.isNaN() })
                        .also { onState() }
                    b.has("delete") -> { ph.removeReading(b.getDouble("delete")); onState(); ph.calib() }
                    b.optBoolean("clear") -> { ph.clearReadings(); onState(); ph.calib() }
                    else -> ph.calib()
                }
            }
            "phone_log" -> JSONObject().put("log", thermo.phone.logJson())
            else -> {
                val m = Regex("^schedule/(weekly|once)(?:/(\\w+))?$").find(p) ?: return null
                val kind = m.groupValues[1]; val id = m.groupValues[2]
                when (method) {
                    "POST" -> sched.add(kind, b)
                    "PATCH" -> sched.update(kind, id, b)
                    "DELETE" -> { sched.delete(kind, id); ok(true) }
                    else -> null
                }
            }
        }
    }

    /* ongoing notification: title = what the heater does, text = what happens next and when */
    private fun summary(): Pair<String, String> {
        val s = fullState()
        if (s.optBoolean("paused")) {
            val left = ((releaseUntil - nowS()) / 60).toInt() + 1
            val auto = if (thermo.enabled) " · Auto thermostat paused" else ""
            return "Bluetooth released: AirHeaterBLE can connect" to
                (if (releaseUntil > 0) "Reconnects in $left min$auto" else "Reconnect from the app$auto")
        }
        if (s.optBoolean("bt_off")) return "Bluetooth is OFF" to "Switch Bluetooth on: the app reconnects to the heater by itself"
        if (!s.optBoolean("connected")) return (if (s.optBoolean("connecting")) "Connecting…" else "Searching for heater…") to s.optString("last_error")
        val sm = s.optJSONObject("smart")
        val room = sm?.takeIf { it.has("room") }?.let { " · room ${ThermoLogic.fmt(it.getDouble("room"))}°" } ?: ""
        if (sm != null && sm.optBoolean("enabled"))
            return "Auto thermostat ${ThermoLogic.fmt(sm.optDouble("target"))}°$room" to sm.optString("status")
        val step = STEP[s.optInt("running_step", 0)] ?: if (heater.running) "On" else "Off"
        val fault = if (s.optInt("error_code", 0) != 0) " · fault E${s.optInt("error_code")}" else ""
        return "$step$room$fault" to nextTxt()
    }

    private fun nextTxt(): String {
        val n = sched.nextEvent() ?: return "No timers scheduled"
        val at = LocalDateTime.parse(n.getString("at"))
        val mins = java.time.Duration.between(LocalDateTime.now(), at).toMinutes().coerceAtLeast(0)
        val left = if (mins >= 60) "${mins / 60} h ${mins % 60} min" else "$mins min"
        val day = if (at.toLocalDate() == LocalDateTime.now().toLocalDate()) "" else at.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() } + " "
        return "Next timer: ${if (n.getString("action") == "on") "On" else "Off"} in $left ($day${"%02d:%02d".format(at.hour, at.minute)})"
    }

    private fun updateNote(force: Boolean) {
        val (title, body) = summary()
        val text = title + "\n" + body
        val t = System.currentTimeMillis()
        if (text == lastNote || (!force && t - lastNoteAt < 3000)) return
        lastNote = text; lastNoteAt = t
        getSystemService(NotificationManager::class.java).notify(NOTE_ID, note(text))
    }

    private fun note(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val quit = PendingIntent.getService(this, 1, Intent(this, HeaterService::class.java).setAction(ACTION_QUIT), PendingIntent.FLAG_IMMUTABLE)
        val paused = ::heater.isInitialized && heater.paused
        val bt = PendingIntent.getService(this, 2, Intent(this, HeaterService::class.java)
            .setAction(if (paused) ACTION_RECONNECT else ACTION_RELEASE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val lines = text.split("\n")
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_flame)
            .setContentTitle(lines[0])
            .setContentText(lines.getOrNull(1) ?: "")
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(null, if (paused) "Reconnect now" else "Release 10 min", bt).build())
            .addAction(Notification.Action.Builder(null, "Quit", quit).build())
            .build()
    }

    companion object {
        const val PORT = 8770
        const val CHANNEL = "link"
        const val ALERTS = "alerts"
        const val EVENTS = "events"
        const val NOTE_ID = 1
        const val ACTION_QUIT = "quit"
        const val ACTION_RELEASE = "release"
        const val ACTION_RECONNECT = "reconnect"
        const val ACTION_ACK = "ack"
        const val ALARM_ID = 50
        const val RELEASE_S = 600.0
        val STEP = mapOf(0 to "Off", 1 to "Self-test", 2 to "Igniting", 3 to "Heating", 4 to "Cooling down", 6 to "Ventilating")
        @Volatile var instance: HeaterService? = null

        fun start(ctx: Context) {
            ctx.getSharedPreferences("heater", MODE_PRIVATE).edit().putBoolean("quit", false).apply()
            ctx.startForegroundService(Intent(ctx, HeaterService::class.java))
        }
    }
}
