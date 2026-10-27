package io.github.zenfortv.dieselheater

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.math.roundToInt

/** Keeps the Bluetooth link to the heater: connect, reconnect forever, poll status, send commands.
 *  All GATT work runs on one thread (Android allows a single GATT operation at a time). */
@SuppressLint("MissingPermission")
class HeaterLink(private val ctx: Context, private val onState: () -> Unit) {
    private val prefs = ctx.getSharedPreferences("heater", Context.MODE_PRIVATE)
    var address: String
        get() = prefs.getString("address", "") ?: ""
        private set(v) = prefs.edit().putString("address", v).apply()
    var name: String
        get() = prefs.getString("name", "") ?: ""
        private set(v) = prefs.edit().putString("name", v).apply()
    var pin: Int
        get() = prefs.getInt("pin", 1234)
        set(v) = prefs.edit().putInt("pin", v.coerceIn(0, 9999)).apply()
    @Volatile var paused = prefs.getBoolean("paused", false)
        private set

    private val io = Executors.newSingleThreadExecutor()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var ch: BluetoothGattCharacteristic? = null
    @Volatile private var frame = JSONObject()
    @Volatile private var updated = 0.0
    @Volatile var connected = false
        private set
    @Volatile private var connecting = false
    @Volatile private var linkUp = false
    @Volatile private var lastError: String? = null
    private val clock = JSONObject(prefs.getString("clock", "{}") ?: "{}")
    private var lastClock = 0.0
    private var retries = 0
    private var retry: ScheduledFuture<*>? = null
    @Volatile private var connLatch: CountDownLatch? = null
    @Volatile private var discLatch: CountDownLatch? = null
    @Volatile private var descLatch: CountDownLatch? = null
    @Volatile private var writeLatch: CountDownLatch? = null
    @Volatile private var replyLatch: CountDownLatch? = null

    val running get() = frame.optInt("running_state", 0) == 1
    val updatedAt get() = updated
    fun frame(): JSONObject = frame

    fun state(): JSONObject {
        val o = JSONObject(frame.toString())
        o.put("connected", connected && !paused).put("connecting", connecting).put("paused", paused)
            .put("address", name.ifEmpty { address }).put("pin", pin).put("clock_sync", clock)
        lastError?.let { o.put("last_error", it) }
        if (updated > 0) o.put("updated", updated)
        return o
    }

    fun start() {
        timer.scheduleWithFixedDelay({ poll() }, 3000, 2500, MILLISECONDS)
        if (!paused) connectAsync()
    }

    fun stop() {
        timer.shutdownNow()
        io.execute { closeGatt() }
        io.shutdown()
    }

    fun setDevice(addr: String, devName: String) {
        address = addr; name = devName
        setPaused(false, reconnect = false)
        io.execute { connected = false; ch = null; closeGatt(); frame = JSONObject(); publish() }
        retries = 0
        connectAsync()
    }

    fun setPaused(p: Boolean, reconnect: Boolean = true) {
        paused = p; prefs.edit().putBoolean("paused", p).apply()
        if (p) {
            retry?.cancel(false)
            io.execute { connected = false; ch = null; closeGatt(); publish() }
        } else if (reconnect) { retries = 0; connectAsync() }
        publish()
    }

    private fun publish() = onState()

    private fun hasPermission() = Build.VERSION.SDK_INT < 31 ||
        ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun connectAsync() = io.execute { doConnect() }

    private fun doConnect() {
        if (paused || connected) return
        val addr = address
        if (addr.isEmpty()) { lastError = "No heater chosen yet"; publish(); return }
        if (!hasPermission()) { lastError = "Bluetooth permission missing"; publish(); return }
        val ad = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        if (ad == null || !ad.isEnabled) { lastError = "Bluetooth is off"; publish(); scheduleReconnect(); return }
        connecting = true; publish()
        try {
            closeGatt()
            linkUp = false
            val cl = CountDownLatch(1); connLatch = cl
            val g = ad.getRemoteDevice(addr).connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
                ?: throw Exception("Bluetooth refused the connection")
            gatt = g
            if (!cl.await(20, SECONDS) || !linkUp) throw Exception("Heater not answering (out of range, off, or another app is connected)")
            val dl = CountDownLatch(1); discLatch = dl
            if (!g.discoverServices() || !dl.await(15, SECONDS) || !linkUp) throw Exception("Service discovery timed out")
            var found: BluetoothGattCharacteristic? = null
            for ((su, cu) in Protocol.SERVICES) {
                found = g.getService(su)?.getCharacteristic(cu) ?: continue
                break
            }
            if (found == null) throw Exception("This device has no heater service (FFE0/FFF0)")
            for (c in found.service.characteristics) {
                val notify = c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                val indicate = c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                if (!notify && !indicate) continue
                g.setCharacteristicNotification(c, true)
                val d = c.getDescriptor(Protocol.CCCD) ?: continue
                val wl = CountDownLatch(1); descLatch = wl
                writeDescriptor(g, d, if (notify) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                wl.await(3, SECONDS)
            }
            ch = found; connected = true; lastError = null; retries = 0
            connecting = false; publish()
            doCmd(1, 0)
            lastClock = 0.0
        } catch (e: Exception) {
            lastError = e.message; connected = false; ch = null
            closeGatt()
            scheduleReconnect()
        } finally {
            connecting = false; publish()
        }
    }

    // 2, 4, 8, 16, 30, 30 ... seconds: keeps trying while the heater is out of range
    private fun scheduleReconnect() {
        if (paused || timer.isShutdown) return
        retry?.cancel(false)
        val delay = minOf(30, 2 shl minOf(retries, 4)).toLong()
        retries++
        retry = timer.schedule({ connectAsync() }, delay, SECONDS)
    }

    private fun closeGatt() {
        val g = gatt ?: return
        gatt = null
        try { g.disconnect() } catch (_: Exception) {}
        try { g.close() } catch (_: Exception) {}
    }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) { linkUp = true; connLatch?.countDown(); return }
            if (newState != BluetoothProfile.STATE_DISCONNECTED) return
            linkUp = false
            val was = connected
            connected = false; ch = null
            listOf(connLatch, discLatch, descLatch, writeLatch, replyLatch).forEach { it?.countDown() }
            if (was) {
                lastError = "Connection lost, reconnecting"
                io.execute { if (gatt === g) closeGatt() }
                publish()
                retries = 0
                scheduleReconnect()
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { discLatch?.countDown() }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { descLatch?.countDown() }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { writeLatch?.countDown() }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) = onData(value)
        @Deprecated("Android < 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) c.value?.let { onData(it) }
        }
    }

    private fun onData(v: ByteArray) {
        val p = Protocol.parse(v) ?: return
        frame = p; updated = nowS()
        replyLatch?.countDown()
        publish()
    }

    @Suppress("DEPRECATION")
    private fun writeChar(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray, type: Int): Boolean =
        if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, v, type) == BluetoothStatusCodes.SUCCESS
        else { c.writeType = type; c.value = v; g.writeCharacteristic(c) }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, v: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, v) == BluetoothStatusCodes.SUCCESS
        else { d.value = v; g.writeDescriptor(d) }

    // one command at a time; each waits for the heater's status reply
    private fun doCmd(c: Int, a: Int): Boolean {
        val g = gatt; val k = ch
        if (g == null || k == null || !connected) return false
        return try {
            val rl = CountDownLatch(1); replyLatch = rl
            val wl = CountDownLatch(1); writeLatch = wl
            val withResponse = k.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
            val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            if (!writeChar(g, k, Protocol.packet(pin, c, a), type)) { lastError = "Write refused"; return false }
            if (withResponse) wl.await(2, SECONDS)
            val ok = rl.await(4, SECONDS) && connected
            replyLatch = null
            Thread.sleep(250)
            ok
        } catch (e: Exception) { lastError = e.message; false }
    }

    fun cmd(c: Int, a: Int = 0): Boolean =
        try { io.submit(Callable { doCmd(c, a) }).get(40, SECONDS) } catch (_: Exception) { false }

    private fun poll() {
        if (!connected || paused) return
        cmd(1, 0)
        if (nowS() - lastClock > 3600 && frame.has("clock_min")) { lastClock = nowS(); syncClock(false) }
    }

    /* high-level commands, same as the Device class in web/index.html */
    fun power(on: Boolean) = cmd(3, if (on) 1 else 0)
    private fun ensureMode(m: Int) = frame.optInt("running_mode", -1) == m || cmd(2, m)
    fun setTemperature(t: Double) = ensureMode(2) && cmd(4, t.roundToInt().coerceIn(8, 36))
    fun setLevel(l: Double) = ensureMode(1) && cmd(4, l.roundToInt().coerceIn(1, 10))
    fun setAutoStartStop(on: Boolean) = cmd(18, if (on) 1 else 0)
    fun setClock(hhmm: String) = cmd(10, toMin(hhmm))
    fun setBuiltinTimer(start: String, dur: Int, en: Boolean) =
        syncClock(true) && cmd(11, toMin(start)) && cmd(12, dur.coerceIn(1, 1440)) && cmd(13, if (en) 1 else 0)

    // the heater clock drifts: compare with real time, re-set it (cmd 10), estimate drift per day
    fun syncClock(force: Boolean): Boolean {
        val real = nowMin()
        var drift: Int? = null
        if (frame.has("clock_min")) {
            drift = ((frame.getInt("clock_min") - real + 720) % 1440 + 1440) % 1440 - 720
            if (!force && drift == 0) return true
        } else if (!force) return true  // heater doesn't report its clock: only on request
        if (!cmd(10, real)) return false
        synchronized(clock) {
            if (drift != null && clock.has("last_sync")) {
                val days = (nowS() - clock.getDouble("last_sync")) / 86400
                if (days > 0.5) clock.put("drift_min_per_day", (drift / days * 10).roundToInt() / 10.0)
            }
            clock.put("last_drift_min", drift ?: JSONObject.NULL).put("last_sync", nowS())
            prefs.edit().putString("clock", clock.toString()).apply()
        }
        publish()
        return true
    }
}
