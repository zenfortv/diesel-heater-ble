package io.github.zenfortv.dieselheater

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** A heating "alarm clock": switches the heater on at [at] and off again [durMin] minutes later
 *  (0 = stays on until stopped). [days] Mon = 0 … Sun = 6; empty = only once, on [date]. */
data class HeatAlarm(
    val id: String,
    val at: LocalTime,
    val durMin: Int,
    val days: Set<Int> = emptySet(),
    val date: LocalDate? = null,
    val enabled: Boolean = true,
)

/** Pure timing rules (JUnit-tested), shared in spirit with LocalHub in web/index.html and scheduler.py. */
object AlarmLogic {
    /** the warning before an automatic switch-off (notification with +10 min / +30 min / +1 h / keep on) */
    const val WARN_S = 300.0

    /** start times of [a] inside (from, to] */
    fun starts(a: HeatAlarm, from: LocalDateTime, to: LocalDateTime): List<LocalDateTime> {
        if (!a.enabled) return emptyList()
        val out = mutableListOf<LocalDateTime>()
        var d = from.toLocalDate()
        while (!d.isAfter(to.toLocalDate())) {
            val fires = if (a.days.isEmpty()) a.date == d else (d.dayOfWeek.value - 1) in a.days
            if (fires) {
                val t = d.atTime(a.at)
                if (t.isAfter(from) && !t.isAfter(to)) out += t
            }
            d = d.plusDays(1)
        }
        return out
    }

    /** day a one-time alarm fires: today if its time is still ahead, otherwise tomorrow */
    fun nextDate(at: LocalTime, now: LocalDateTime): LocalDate =
        if (now.toLocalTime().isBefore(at)) now.toLocalDate() else now.toLocalDate().plusDays(1)

    /** "keep it warm for N more minutes": counted from the planned switch-off (or from now if that already passed) */
    fun extend(offAt: Double?, now: Double, minutes: Double): Double = maxOf(offAt ?: now, now) + minutes * 60

    /** inside the warning window before the switch-off */
    fun warnDue(offAt: Double?, now: Double): Boolean = offAt != null && offAt > now && offAt - now <= WARN_S

    /** old weekly slot on/off -> run minutes (off earlier than on = past midnight) */
    fun slotMinutes(on: LocalTime, off: LocalTime): Int {
        var m = (off.toSecondOfDay() - on.toSecondOfDay()) / 60
        if (m <= 0) m += 1440
        return m
    }
}
