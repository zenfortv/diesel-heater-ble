package io.github.zenfortv.dieselheater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Safety rules of the smart thermostat, simulated minute by minute against a fake heater. */
class ThermoLogicTest {
    private val s = ThermoSettings(target = 21.0, below = 1.0, above = 1.0, minOnMin = 20.0, minOffMin = 10.0, minVolt = 11.8)

    private fun reading(room: Double?, on: Boolean = false, step: Int = if (on) 3 else 0, error: Int = 0,
                        volt: Double = 12.8, connected: Boolean = true, age: Double = 2.0) =
        HeaterReading(connected, age, on, step, error, room, volt)

    /** a logic that has already seen the heater off for a long time */
    private fun idle(now: Double = 10_000.0) = ThermoLogic().also { it.step(s, true, true, reading(30.0), now - 5000) }

    @Test fun startsOnlyBelowTheLowerMargin() {
        val l = idle()
        assertEquals(Act.NONE, l.step(s, true, true, reading(20.5), 10_000.0).act)
        assertEquals(Act.START, l.step(s, true, true, reading(19.9), 10_005.0).act)
    }

    @Test fun powerButtonStartsBelowTarget() {
        assertEquals(Act.START, idle().step(s, true, true, reading(20.5), 10_000.0, eager = true).act)
        assertEquals(Act.NONE, idle().step(s, true, true, reading(21.2), 10_000.0, eager = true).act)
    }

    @Test fun nothingWhenThermostatOffOrDisabled() {
        assertEquals(Act.NONE, idle().step(s, true, false, reading(5.0), 10_000.0).act)
        assertEquals(Act.NONE, idle().step(s, false, true, reading(5.0), 10_000.0).act)
    }

    @Test fun stopsAboveUpperMarginOnlyAfterMinimumRun() {
        val l = idle()
        var t = 10_000.0
        l.step(s, true, true, reading(19.0), t)                         // START sent
        t += 10; l.step(s, true, true, reading(19.0, on = true), t)     // burner on
        t += 300; assertEquals(Act.NONE, l.step(s, true, true, reading(22.5, on = true), t).act)   // 5 min: too early
        t += 15 * 60; assertEquals(Act.STOP, l.step(s, true, true, reading(22.5, on = true), t).act)
    }

    @Test fun restsAfterStopping() {
        val l = idle()
        var t = 10_000.0
        l.step(s, true, true, reading(22.5, on = true), t)
        t += 60; l.step(s, true, true, reading(19.0), t)                // burner went off (our stop)
        l.expectOffUntil = 0.0
        t += 120; assertEquals(Act.NONE, l.step(s, true, true, reading(18.0), t).act)
        t += 10 * 60; assertEquals(Act.START, l.step(s, true, true, reading(18.0), t).act)
    }

    @Test fun noStartOnLowBattery() {
        val d = idle().step(s, true, true, reading(15.0, volt = 11.5), 10_000.0)
        assertEquals(Act.NONE, d.act)
        assertTrue(d.status.contains("Battery"))
    }

    @Test fun noStartWhileHeaterReportsFault() {
        assertEquals(Act.NONE, idle().step(s, true, true, reading(15.0, error = 1), 10_000.0).act)
    }

    @Test fun nothingOnStaleOrMissingData() {
        assertEquals(Act.NONE, idle().step(s, true, true, reading(15.0, age = 120.0), 10_000.0).act)
        assertEquals(Act.NONE, idle().step(s, true, true, reading(15.0, connected = false), 10_000.0).act)
        assertEquals(Act.NONE, idle().step(s, true, true, reading(null), 10_000.0).act)
    }

    @Test fun twoUnlitStartsLockTheThermostatOut() {
        val l = idle()
        var t = 10_000.0
        assertEquals(Act.START, l.step(s, true, true, reading(15.0), t).act)
        // the heater never lights: stays off, then the rest time passes
        t += 8 * 60; l.step(s, true, true, reading(15.0), t)
        assertEquals(1, l.failedStarts)
        t += 11 * 60; assertEquals(Act.START, l.step(s, true, true, reading(15.0), t).act)
        t += 8 * 60; val d = l.step(s, true, true, reading(15.0), t)
        assertNotNull(d.alert)
        assertNotNull(l.lockout)
        t += 60 * 60; assertEquals(Act.NONE, l.step(s, true, true, reading(10.0), t).act)
        l.resetLockout()
        t += 60; assertEquals(Act.START, l.step(s, true, true, reading(10.0), t).act)
    }

    @Test fun aStartThatLightsClearsTheCounter() {
        val l = idle()
        var t = 10_000.0
        l.step(s, true, true, reading(15.0), t)
        t += 8 * 60; l.step(s, true, true, reading(15.0), t)
        assertEquals(1, l.failedStarts)
        t += 11 * 60; l.step(s, true, true, reading(15.0), t)
        t += 60; l.step(s, true, true, reading(15.0, on = true, step = 2), t)
        t += 120; l.step(s, true, true, reading(16.0, on = true, step = 3), t)
        assertEquals(0, l.failedStarts)
        assertNull(l.lockout)
    }

    @Test fun atMostThreeStartsPerHour() {
        val quick = s.copy(minOnMin = 1.0, minOffMin = 1.0)
        val l = ThermoLogic().also { it.step(quick, true, true, reading(30.0), 0.0) }
        var t = 10_000.0
        var startsSeen = 0
        repeat(6) {
            if (l.step(quick, true, true, reading(15.0), t).act == Act.START) startsSeen++
            t += 30; l.step(quick, true, true, reading(15.0, on = true), t)    // lights
            t += 90; l.step(quick, true, true, reading(23.0, on = true), t)    // warm: stop
            t += 30; l.step(quick, true, true, reading(23.0), t); l.expectOffUntil = 0.0
            t += 70
        }
        assertEquals(ThermoLogic.MAX_STARTS_PER_HOUR, startsSeen)
    }

    @Test fun hardTemperatureCeilingStopsAtOnce() {
        val l = idle()
        var t = 10_000.0
        l.step(s, true, true, reading(19.0), t)
        t += 10; l.step(s, true, true, reading(19.0, on = true), t)
        t += 60; assertEquals(Act.STOP, l.step(s.copy(above = 5.0, target = 30.0), true, true, reading(35.5, on = true), t).act)
    }

    @Test fun crazySensorStopsAndNeverStarts() {
        val l = idle()
        assertEquals(Act.NONE, l.step(s, true, true, reading(-60.0), 10_000.0).act)
        l.step(s, true, true, reading(19.0, on = true), 10_010.0)
        assertEquals(Act.STOP, l.step(s, true, true, reading(120.0, on = true), 10_020.0).act)
    }

    @Test fun stoppedOnThePanelTurnsTheThermostatOff() {
        val l = idle()
        l.step(s, true, true, reading(19.0, on = true), 10_000.0)
        val d = l.step(s, true, true, reading(19.0), 10_010.0)
        assertEquals(false, d.setActive)
        assertEquals(Act.NONE, d.act)
    }

    @Test fun faultStopKeepsItFromRestarting() {
        val l = idle()
        l.step(s, true, true, reading(18.0, on = true), 10_000.0)
        val d = l.step(s, true, true, reading(18.0, error = 2), 10_010.0)
        assertNull(d.setActive)
        assertEquals(Act.NONE, l.step(s, true, true, reading(18.0, error = 2), 20_000.0).act)
    }

    @Test fun lockoutAndRestSurviveARestart() {
        val l = idle()
        var t = 10_000.0
        l.step(s, true, true, reading(15.0), t)
        t += 8 * 60; l.step(s, true, true, reading(15.0), t)
        t += 11 * 60; l.step(s, true, true, reading(15.0), t)
        t += 8 * 60; l.step(s, true, true, reading(15.0), t)
        assertNotNull(l.lockout)
        val after = ThermoLogic().also { it.restore(l.export()) }          // app restarted
        assertEquals(Act.NONE, after.step(s, true, true, reading(10.0), t + 60).act)

        val stopped = idle()
        stopped.step(s, true, true, reading(22.5, on = true), 20_000.0)
        stopped.expectOffUntil = 30_000.0
        stopped.step(s, true, true, reading(19.0), 20_010.0)               // stopped just now
        val again = ThermoLogic().also { it.restore(stopped.export()) }
        assertEquals(Act.NONE, again.step(s, true, true, reading(15.0), 20_100.0).act)   // still resting
    }

    @Test fun ourOwnStopIsNotMistakenForAPanelStop() {
        val l = idle()
        var t = 10_000.0
        l.step(s, true, true, reading(19.0), t)
        t += 10; l.step(s, true, true, reading(19.0, on = true), t)
        t += 25 * 60; assertEquals(Act.STOP, l.step(s, true, true, reading(22.5, on = true), t).act)
        t += 20; val d = l.step(s, true, true, reading(22.4), t)
        assertNull(d.setActive)                                            // thermostat stays on
        t += 30 * 60; assertEquals(Act.START, l.step(s, true, true, reading(19.5), t).act)
    }

    @Test fun ourOwnStartIsNotMistakenForAPanelStart() {
        val l = idle()
        l.step(s, true, true, reading(19.0), 10_000.0)
        assertNull(l.step(s, true, true, reading(19.0, on = true), 10_010.0).setActive)
    }

    @Test fun panelStartWhileThermostatOffTakesOver() {
        val l = idle()
        l.step(s, true, false, reading(19.0), 10_000.0)
        assertEquals(true, l.step(s, true, false, reading(19.0, on = true), 10_010.0).setActive)
    }

    @Test fun sensorCorrectionIsApplied() {
        val l = idle()
        // reads 21.5 but the user measured 2° less: 19.5 is below 20 -> start
        assertEquals(Act.START, l.step(s.copy(offset = -2.0), true, true, reading(21.5), 10_000.0).act)
    }
}
