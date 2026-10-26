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
        val n = note("Starting…")
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTE_ID, n)

        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DieselHeater:link")
        wake.setReferenceCounted(false)
        wake.acquire()

        heater = HeaterLink(this) { onState() }
        sched = Scheduler(File(filesDir, "schedules.json"), heater)
        web = WebServer(this, ::api) { stateMsg() }
        try { web.start(PORT) } catch (_: Exception) {}
        heater.start()
        ticker.scheduleWithFixedDelay({ try { sched.tick() } catch (_: Exception) {} }, 5, 5, SECONDS)
        ticker.scheduleWithFixedDelay({ updateNote(force = true) }, 30, 30, SECONDS)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_QUIT) {
            getSharedPreferences("heater", MODE_PRIVATE).edit().putBoolean("quit", true).apply()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        ticker.shutdownNow()
        web.stop()
        heater.stop()
        if (wake.isHeld) wake.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stateMsg() = JSONObject().put("type", "state").put("state", heater.state()).toString()

    private fun onState() {
        web.broadcast(stateMsg())
        updateNote(force = false)
    }

    private fun api(method: String, p: String, b: JSONObject): Any? {
        val h = heater
        fun ok(v: Boolean) = JSONObject().put("ok", v)
        return when (p) {
            "state" -> JSONObject().put("heater", h.state()).put("paused", h.paused).put("simulated", false).put("schedule", sched.snapshot())
            "power" -> ok(h.power(b.optBoolean("on")))
            "level" -> ok(h.setLevel(b.optDouble("level")))
            "temperature" -> ok(h.setTemperature(b.optDouble("temperature")))
            "auto_start_stop" -> ok(h.setAutoStartStop(b.optBoolean("on")))
            "builtin_timer" -> ok(h.setBuiltinTimer(b.getString("start"), b.optInt("duration", 60), b.optBoolean("enabled")))
            "sync_clock" -> ok(h.syncClock(true))
            "set_clock" -> ok(h.setClock(b.getString("time")))
            "pause" -> { h.setPaused(b.optBoolean("paused")); ok(true) }
            "pin" -> { h.pin = b.optInt("pin", 1234); onState(); ok(true) }
            "off_timer" -> { sched.setOffTimer(if (b.isNull("minutes")) null else b.optDouble("minutes")); sched.snapshot() }
            "max_run" -> { sched.setMaxRun(b.optDouble("hours", 0.0)); sched.snapshot() }
            "schedule" -> sched.snapshot()
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

    /* ongoing notification: what the heater does + the next timer */
    private fun summary(): String {
        val s = heater.state()
        if (s.optBoolean("paused")) return "Bluetooth released"
        if (!s.optBoolean("connected")) return if (s.optBoolean("connecting")) "Connecting…" else s.optString("last_error").ifEmpty { "Searching for heater…" }
        val step = STEP[s.optInt("running_step", 0)] ?: if (heater.running) "On" else "Off"
        val parts = mutableListOf(step)
        if (s.has("cab_temperature")) parts += "room ${"%.0f".format(s.optDouble("cab_temperature"))}°C"
        if (s.optInt("error_code", 0) != 0) parts += "fault E${s.optInt("error_code")}"
        return parts.joinToString(" · ")
    }

    private fun nextTxt(): String {
        val n = sched.nextEvent() ?: return "No timers scheduled"
        val at = LocalDateTime.parse(n.getString("at"))
        val day = if (at.toLocalDate() == LocalDateTime.now().toLocalDate()) "today" else at.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
        return "Next: ${if (n.getString("action") == "on") "On" else "Off"} $day ${"%02d:%02d".format(at.hour, at.minute)}"
    }

    private fun updateNote(force: Boolean) {
        val text = summary() + "\n" + nextTxt()
        val t = System.currentTimeMillis()
        if (text == lastNote || (!force && t - lastNoteAt < 3000)) return
        lastNote = text; lastNoteAt = t
        getSystemService(NotificationManager::class.java).notify(NOTE_ID, note(text))
    }

    private fun note(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val quit = PendingIntent.getService(this, 1, Intent(this, HeaterService::class.java).setAction(ACTION_QUIT), PendingIntent.FLAG_IMMUTABLE)
        val lines = text.split("\n")
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_flame)
            .setContentTitle(lines[0])
            .setContentText(lines.getOrNull(1) ?: "")
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(null, "Quit", quit).build())
            .build()
    }

    companion object {
        const val PORT = 8770
        const val CHANNEL = "link"
        const val NOTE_ID = 1
        const val ACTION_QUIT = "quit"
        val STEP = mapOf(0 to "Off", 1 to "Self-test", 2 to "Igniting", 3 to "Heating", 4 to "Cooling down", 6 to "Ventilating")
        @Volatile var instance: HeaterService? = null

        fun start(ctx: Context) {
            ctx.getSharedPreferences("heater", MODE_PRIVATE).edit().putBoolean("quit", false).apply()
            ctx.startForegroundService(Intent(ctx, HeaterService::class.java))
        }
    }
}
