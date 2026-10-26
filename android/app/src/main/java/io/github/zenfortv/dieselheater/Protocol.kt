package io.github.zenfortv.dieselheater

import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.roundToInt

/* Heater protocol (AirHeaterBLE family), same as parseFrame()/packet() in web/index.html.
   command: AA 55 <pin/100> <pin%100> <cmd> <arg lo> <arg hi> <sum of bytes 2-6>
   cmd (decimal): 1 status, 2 mode (1 level / 2 thermostat), 3 power, 4 level/temp,
   10 clock, 11 timer start, 12 timer minutes, 13 timer on/off, 18 auto start/stop
   replies: AA55 18/20 bytes, AA66 20 bytes, or 48 bytes XOR-"password" encrypted. */
object Protocol {
    private fun uuid16(v: Int): UUID = UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", v))
    val SERVICES = listOf(uuid16(0xffe0) to uuid16(0xffe1), uuid16(0xfff0) to uuid16(0xfff1))
    val CCCD: UUID = uuid16(0x2902)
    private val KEY = intArrayOf(112, 97, 115, 115, 119, 111, 114, 100)

    fun packet(pin: Int, cmd: Int, arg: Int): ByteArray {
        val p = intArrayOf(0xAA, 0x55, pin / 100, pin % 100, cmd, arg and 255, (arg shr 8) and 255, 0)
        p[7] = (p[2] + p[3] + p[4] + p[5] + p[6]) and 255
        return ByteArray(8) { p[it].toByte() }
    }

    private fun s16(v: Int) = if (v > 32767) v - 65536 else v
    private fun clamp(v: Int, a: Int, b: Int) = v.coerceIn(a, b)

    fun parse(raw: ByteArray): JSONObject? {
        val b = IntArray(raw.size) { raw[it].toInt() and 0xff }
        if (b.size < 17) return null
        val h = b[0] shl 8 or b[1]
        val s = JSONObject()
        if (h == 0xAA55 && (b.size == 18 || b.size == 20)) {
            s.put("protocol", "AA55").put("running_state", b[3]).put("error_code", b[4])
                .put("running_step", b[5]).put("running_mode", b[8])
            when (b[8]) {
                1 -> s.put("set_level", b[9])
                2 -> s.put("set_temp", b[9]).put("set_level", b[10] + 1)
                0 -> s.put("set_level", b[10] + 1)
            }
            s.put("supply_voltage", (256 * b[12] + b[11]) / 10.0)
            s.put("case_temperature", s16(256 * b[14] + b[13]))
            s.put("cab_temperature", s16(256 * b[16] + b[15]))
            return s
        }
        if (h == 0xAA66 && b.size == 20) {
            s.put("protocol", "AA66").put("running_state", b[3]).put("error_code", b[17])
                .put("running_step", b[5]).put("running_mode", b[8])
            when (b[8]) {
                1 -> s.put("set_level", clamp(b[9], 1, 10))
                2 -> s.put("set_temp", clamp(b[9], 8, 36)).put("set_level", clamp(b[10] + 1, 1, 10))
                0 -> s.put("set_level", clamp(b[10] + 1, 1, 10))
            }
            s.put("supply_voltage", (b[11] or (b[12] shl 8)) / 10.0)
            val c = b[13] or (b[14] shl 8)
            s.put("case_temperature", if (c > 350) c / 10.0 else c.toDouble())
            s.put("cab_temperature", s16(256 * b[16] + b[15]))
            return s
        }
        if (b.size == 48) {
            val d = IntArray(48) { b[it] xor KEY[it % 8] }
            val ih = d[0] shl 8 or d[1]
            if (ih != 0xAA55 && ih != 0xAA66) return null
            val e66 = ih == 0xAA66
            s.put("protocol", if (e66) "AA66 encrypted" else "AA55 encrypted").put("running_state", d[3])
                .put("error_code", if (e66) d[35] else d[4]).put("running_step", d[5]).put("running_mode", d[8])
                .put("set_level", clamp(d[10], 1, 10))
            s.put("set_temp", clamp(if (e66 && d[27] == 1) ((d[9] - 32) * 5 / 9.0).roundToInt() else d[9], 8, 36))
            s.put("supply_voltage", (256 * d[11] + d[12]) / 10.0)
            s.put("case_temperature", s16(256 * d[13] + d[14]))
            s.put("cab_temperature", s16(256 * d[32] + d[33]) / 10.0)
            s.put("clock_min", d[19] shl 8 or d[20])
            s.put("timer_start_min", d[21] shl 8 or d[22])
            s.put("timer_duration_min", d[23] shl 8 or d[24])
            s.put("timer_enabled", d[25] == 1)
            s.put("auto_start_stop", d[31] == 1)
            return s
        }
        return null
    }
}

/* small helpers shared by the service, link and scheduler */
fun nowS() = System.currentTimeMillis() / 1000.0
fun nowMin(): Int = LocalDateTime.now().let { it.hour * 60 + it.minute }
fun toMin(hhmm: String): Int = hhmm.split(":").let { it[0].toInt() * 60 + it[1].toInt() }
private val ISO_MIN = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
private val ISO_SEC = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
fun iso(t: LocalDateTime, sec: Boolean = false): String = t.format(if (sec) ISO_SEC else ISO_MIN)
