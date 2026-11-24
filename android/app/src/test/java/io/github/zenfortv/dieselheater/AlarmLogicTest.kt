package io.github.zenfortv.dieselheater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class AlarmLogicTest {
    private val mon = LocalDate.of(2026, 11, 23)  // a Monday

    @Test fun weeklyAlarmFiresOnItsDaysOnly() {
        val a = HeatAlarm("a", LocalTime.of(7, 0), 45, days = setOf(0, 1, 2, 3, 4))  // weekdays
        val s = AlarmLogic.starts(a, mon.atStartOfDay(), mon.plusDays(7).atStartOfDay())
        assertEquals(5, s.size)
        assertEquals(mon.atTime(7, 0), s.first())
        assertEquals(mon.plusDays(4).atTime(7, 0), s.last())  // Friday
    }

    @Test fun windowIsExclusiveAtTheStartInclusiveAtTheEnd() {
        val a = HeatAlarm("a", LocalTime.of(7, 0), 30, days = (0..6).toSet())
        assertTrue(AlarmLogic.starts(a, mon.atTime(7, 0), mon.atTime(7, 1)).isEmpty())
        assertEquals(1, AlarmLogic.starts(a, mon.atTime(6, 59), mon.atTime(7, 0)).size)
    }

    @Test fun oneTimeAlarmFiresOnlyOnItsDate() {
        val a = HeatAlarm("a", LocalTime.of(6, 30), 60, date = mon.plusDays(1))
        val s = AlarmLogic.starts(a, mon.atStartOfDay(), mon.plusDays(5).atStartOfDay())
        assertEquals(listOf(mon.plusDays(1).atTime(6, 30)), s)
    }

    @Test fun disabledAlarmNeverFires() {
        val a = HeatAlarm("a", LocalTime.of(7, 0), 30, days = (0..6).toSet(), enabled = false)
        assertTrue(AlarmLogic.starts(a, mon.atStartOfDay(), mon.plusDays(7).atStartOfDay()).isEmpty())
    }

    @Test fun oneTimeDateIsTodayIfAheadElseTomorrow() {
        assertEquals(mon, AlarmLogic.nextDate(LocalTime.of(7, 0), mon.atTime(6, 0)))
        assertEquals(mon.plusDays(1), AlarmLogic.nextDate(LocalTime.of(7, 0), mon.atTime(7, 0)))
        assertEquals(mon.plusDays(1), AlarmLogic.nextDate(LocalTime.of(7, 0), LocalDateTime.of(mon, LocalTime.of(22, 0))))
    }

    @Test fun snoozeExtendsThePlannedSwitchOff() {
        assertEquals(1600.0, AlarmLogic.extend(1000.0, 900.0, 10.0), 0.0)   // 5 min left + 10 = off 10 min after the old time
        assertEquals(2600.0, AlarmLogic.extend(1000.0, 2000.0, 10.0), 0.0)  // already past: from now
        assertEquals(2600.0, AlarmLogic.extend(null, 2000.0, 10.0), 0.0)
    }

    @Test fun warningOnlyInTheLastFiveMinutes() {
        assertFalse(AlarmLogic.warnDue(null, 0.0))
        assertFalse(AlarmLogic.warnDue(1000.0, 1000.0 - 301))
        assertTrue(AlarmLogic.warnDue(1000.0, 1000.0 - 300))
        assertTrue(AlarmLogic.warnDue(1000.0, 999.0))
        assertFalse(AlarmLogic.warnDue(1000.0, 1000.0))
    }

    @Test fun oldWeeklySlotBecomesRunMinutes() {
        assertEquals(90, AlarmLogic.slotMinutes(LocalTime.of(7, 0), LocalTime.of(8, 30)))
        assertEquals(120, AlarmLogic.slotMinutes(LocalTime.of(23, 0), LocalTime.of(1, 0)))
        assertEquals(1440, AlarmLogic.slotMinutes(LocalTime.of(7, 0), LocalTime.of(7, 0)))
    }
}
