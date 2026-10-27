package io.github.zenfortv.dieselheater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    // AA66 short frame: 3 on/off, 5 step, 8 mode, 9-10 setpoint, 11-12 volt*10, 13-14 case, 15-16 room, 17 error
    private fun aa66(on: Int, step: Int, volt10: Int, case: Int, room: Int, err: Int = 0): ByteArray {
        val b = IntArray(20)
        b[0] = 0xAA; b[1] = 0x66; b[3] = on; b[5] = step; b[8] = 2; b[9] = 21; b[10] = 4
        b[11] = volt10 and 255; b[12] = volt10 shr 8; b[13] = case and 255; b[14] = case shr 8
        b[15] = room and 255; b[16] = room shr 8; b[17] = err
        return ByteArray(20) { b[it].toByte() }
    }

    @Test fun normalFrameKeepsReadings() {
        val s = Protocol.parse(aa66(1, 3, 128, 150, 19))!!
        assertEquals(12.8, s.getDouble("supply_voltage"), 0.001)
        assertEquals(19.0, s.getDouble("cab_temperature"), 0.001)
        assertFalse(s.has("sensors_blank"))
    }

    @Test fun zeroVoltFrameHasNoReadings() {
        // heater off: the controller sends zeros, not a 0 °C room and a flat battery
        val s = Protocol.parse(aa66(0, 0, 0, 0, 0))!!
        assertFalse(s.has("supply_voltage"))
        assertFalse(s.has("cab_temperature"))
        assertFalse(s.has("case_temperature"))
        assertTrue(s.optBoolean("sensors_blank"))
        assertEquals(0, s.getInt("running_state"))   // the state itself is still valid
    }

    @Test fun packetChecksum() {
        val p = Protocol.packet(1234, 3, 1).map { it.toInt() and 255 }
        assertEquals(listOf(0xAA, 0x55, 12, 34, 3, 1, 0, (12 + 34 + 3 + 1) and 255), p)
    }
}
