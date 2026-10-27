package io.github.zenfortv.dieselheater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SensorModelTest {
    private val idle = PhoneWarmth()
    private val busy = PhoneWarmth(screen = 1.0, charging = 1.0, wifi = 1.0, hotspot = 1.0)
    private val day = 86400.0

    @Test fun startingGuessMatchesUser() {
        val m = SensorModel()
        assertEquals(0.5, m.warmBy(idle), 1e-9)
        // user: screen + charging + hotspot + wifi -> 10-12° over the van
        assertTrue(m.warmBy(busy) in 10.0..12.5)
    }

    @Test fun learnsFromRepeatedReadings() {
        // this phone: idle reads 2° warm, screen adds 6°
        val screen = PhoneWarmth(screen = 1.0)
        val s = (0 until 10).flatMap { i ->
            listOf(CalibSample(i * 3600.0, 10.0, 12.0, idle), CalibSample(i * 3600.0 + 1, 18.0, 26.0, screen))
        }
        val m = SensorModel(s)
        assertEquals(12.0 - 2.0, m.room(12.0, idle), 0.3)
        assertEquals(26.0 - 8.0, m.room(26.0, screen), 0.5)
        assertTrue(m.meanError()!! < 0.5)
    }

    @Test fun oneReadingCantSendItWild() {
        // one odd reading: idle phone 3° under the thermometer moves it only part of the way
        val m = SensorModel(listOf(CalibSample(0.0, 20.0, 17.0, idle)))
        assertTrue(m.warmBy(idle) in -2.5..0.0)
        assertTrue(m.warmBy(busy) in 8.0..12.0)
    }

    @Test fun newerReadingsCountMore() {
        val old = (0 until 5).map { CalibSample(it.toDouble(), 10.0, 14.0, idle) }  // 4° warm, 90 days ago
        val new = (0 until 5).map { CalibSample(90 * day + it, 10.0, 11.0, idle) }  // 1° warm now
        val w = SensorModel(old + new).warmBy(idle)
        assertTrue("warm by $w", abs(w - 1.0) < abs(w - 4.0))
    }

    @Test fun warmthFollowsSlowly() {
        var w = PhoneWarmth()
        w = w.follow(screenOn = true, chargingOn = false, wifiOn = false, hotspotOn = false, loadA = 0.0, dtS = SensorModel.TAU_S)
        assertEquals(1 - kotlin.math.exp(-1.0), w.screen, 1e-9)  // one time constant: 63%
        w = w.follow(screenOn = false, chargingOn = false, wifiOn = false, hotspotOn = false, loadA = 0.0, dtS = 10 * SensorModel.TAU_S)
        assertTrue(w.screen < 0.01)
    }

    @Test fun addRemoveClear() {
        val m = SensorModel()
        m.add(CalibSample(1.0, 18.0, 28.0, busy)); m.add(CalibSample(2.0, 8.0, 9.0, idle))
        assertEquals(2, m.samples.size)
        m.remove(1.0); assertEquals(listOf(2.0), m.samples.map { it.t })
        m.clear(); assertEquals(0.5, m.warmBy(idle), 1e-9)
    }

    @Test fun solvesLinearSystem() {
        val x = SensorModel.solve(arrayOf(doubleArrayOf(2.0, 1.0), doubleArrayOf(1.0, 3.0)), doubleArrayOf(3.0, 5.0))!!
        assertEquals(0.8, x[0], 1e-9); assertEquals(1.4, x[1], 1e-9)
    }
}
