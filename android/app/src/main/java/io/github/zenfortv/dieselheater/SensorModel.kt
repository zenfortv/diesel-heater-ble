package io.github.zenfortv.dieselheater

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/** What warms the phone above the air around it, each as a "warmth" level that follows the real state
 *  slowly (the battery takes ~20 min to heat up or cool down): 0 = off for a long time, 1 = on for a long time.
 *  load = battery current in A (charging or draining: both heat it). */
data class PhoneWarmth(val screen: Double = 0.0, val charging: Double = 0.0, val wifi: Double = 0.0,
                       val hotspot: Double = 0.0, val load: Double = 0.0) {
    fun x() = doubleArrayOf(1.0, screen, charging, wifi, hotspot, load)

    /** moves each level toward the state now; dtS = seconds since the last call */
    fun follow(screenOn: Boolean, chargingOn: Boolean, wifiOn: Boolean, hotspotOn: Boolean, loadA: Double, dtS: Double): PhoneWarmth {
        val k = 1 - exp(-dtS.coerceAtLeast(0.0) / SensorModel.TAU_S)
        fun f(a: Double, b: Double) = a + (b - a) * k
        fun b(on: Boolean) = if (on) 1.0 else 0.0
        return PhoneWarmth(f(screen, b(screenOn)), f(charging, b(chargingOn)), f(wifi, b(wifiOn)), f(hotspot, b(hotspotOn)),
            f(load, loadA.coerceIn(0.0, 4.0)))
    }
}

/** A reading typed in from a real thermometer, with what the phone said and how warm it was at that moment. */
data class CalibSample(val t: Double, val real: Double, val raw: Double, val w: PhoneWarmth,
                       val outdoor: Double? = null, val heaterRoom: Double? = null)

/** Learns how much warmer than the room the phone battery reads, from the readings typed in.
 *  phone - room = base + screen*a + charging*b + wifi*c + hotspot*d + load*e
 *  Fitted by least squares pulled toward sensible starting values (so one or two readings can't send it wild),
 *  newer readings count more (half weight after 30 days). Pure: no Android, unit-tested. */
class SensorModel(samples: List<CalibSample> = emptyList()) {
    var samples: List<CalibSample> = samples
        private set
    var weights: DoubleArray = PRIOR.copyOf()
        private set

    init { fit() }

    fun add(s: CalibSample) { samples = (samples + s).takeLast(MAX_SAMPLES); fit() }
    fun remove(t: Double) { samples = samples.filter { it.t != t }; fit() }
    fun clear() { samples = emptyList(); fit() }

    /** degrees the phone reads above the room right now */
    fun warmBy(w: PhoneWarmth): Double = dot(weights, w.x()).coerceIn(-3.0, 20.0)
    fun room(raw: Double, w: PhoneWarmth) = raw - warmBy(w)

    /** average error (°) of the model on the readings typed in, null with none */
    fun meanError(): Double? = if (samples.isEmpty()) null else samples.map { abs(room(it.raw, it.w) - it.real) }.average()

    private fun fit(now: Double = samples.maxOfOrNull { it.t } ?: 0.0) {
        val n = PRIOR.size
        val a = Array(n) { i -> DoubleArray(n).also { it[i] = LAMBDA[i] } }
        val b = DoubleArray(n) { LAMBDA[it] * PRIOR[it] }
        for (s in samples) {
            val x = s.w.x(); val y = s.raw - s.real
            val wt = 0.5.pow((now - s.t) / HALF_LIFE_S)
            for (i in 0 until n) { b[i] += wt * x[i] * y; for (j in 0 until n) a[i][j] += wt * x[i] * x[j] }
        }
        weights = solve(a, b) ?: PRIOR.copyOf()
    }

    companion object {
        const val TAU_S = 20 * 60.0
        const val HALF_LIFE_S = 30 * 86400.0
        const val MAX_SAMPLES = 300
        val NAMES = listOf("base", "screen", "charging", "wifi", "hotspot", "load")
        // starting guess: on charge + hotspot + screen the phone reads ~10-12° over the van (measured by the user)
        val PRIOR = doubleArrayOf(0.5, 3.5, 4.0, 1.0, 3.0, 0.0)
        // how hard each value is held to its starting guess (higher = needs more readings to move)
        val LAMBDA = doubleArrayOf(0.5, 0.4, 0.4, 0.6, 0.4, 1.0)

        private fun dot(a: DoubleArray, b: DoubleArray) = a.indices.sumOf { a[it] * b[it] }

        /** Gaussian elimination with partial pivoting; null if singular */
        fun solve(a0: Array<DoubleArray>, b0: DoubleArray): DoubleArray? {
            val n = b0.size
            val a = Array(n) { a0[it].copyOf() }; val b = b0.copyOf()
            for (c in 0 until n) {
                val p = (c until n).maxByOrNull { abs(a[it][c]) }!!
                if (abs(a[p][c]) < 1e-12) return null
                a[c] = a[p].also { a[p] = a[c] }; b[c] = b[p].also { b[p] = b[c] }
                for (r in c + 1 until n) {
                    val f = a[r][c] / a[c][c]
                    for (k in c until n) a[r][k] -= f * a[c][k]
                    b[r] -= f * b[c]
                }
            }
            val x = DoubleArray(n)
            for (r in n - 1 downTo 0) x[r] = (b[r] - (r + 1 until n).sumOf { a[r][it] * x[it] }) / a[r][r]
            return x
        }
    }
}
