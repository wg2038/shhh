package io.github.wg2038.shhh

import kotlin.math.abs
import kotlin.math.max

/**
 * Sliding-window stillness detector using de-meaned 3-axis accelerometer and gyroscope variance.
 * Discards the initial warm-up window to skip landing impacts and cold-start transients.
 */
class StillnessDetector(
    private val hasGyroscope: Boolean,
    private val config: Config = Config(),
    private val hasAccelerometer: Boolean = true,
) {

    data class Config(
        val windowSize: Int = 50,
        val warmupMs: Long = 300L,
        val minSamples: Int = 15,
        val minWindowSpanMs: Long = 200L,
        val maxSampleAgeMs: Long = 600L,
        val maxSensorSkewMs: Long = 500L,
        val maxAccelVariance: Float = 0.0050f,
        val maxAccelVarianceWithoutGyro: Float = 0.0035f,
        val maxGyroVariance: Float = 0.00060f,
    )

    data class Result(
        val isStationary: Boolean,
        val accelVariance: Float,
        val gyroVariance: Float,
        val accelSampleCount: Int,
        val gyroSampleCount: Int,
        val warmedUp: Boolean,
    )

    private class AxisBuffer(private val capacity: Int) {
        private val xs = FloatArray(capacity)
        private val ys = FloatArray(capacity)
        private val zs = FloatArray(capacity)
        private val timestamps = LongArray(capacity)
        private var index = 0

        var count = 0
            private set
        var lastSampleMs = -1L
            private set

        fun reset() {
            index = 0
            count = 0
            lastSampleMs = -1L
        }

        fun add(x: Float, y: Float, z: Float, timestampMs: Long) {
            xs[index] = x
            ys[index] = y
            zs[index] = z
            timestamps[index] = timestampMs
            index = (index + 1) % capacity
            if (count < capacity) count++
            lastSampleMs = timestampMs
        }

        fun spanMs(): Long {
            if (count < 2 || lastSampleMs < 0L) return 0L
            val oldestSlot = if (count == capacity) index else 0
            return (lastSampleMs - timestamps[oldestSlot]).coerceAtLeast(0L)
        }

        fun computeVariance(minSamples: Int): Float {
            if (count < minSamples) return Float.MAX_VALUE
            var sumX = 0.0
            var sumY = 0.0
            var sumZ = 0.0
            for (i in 0 until count) {
                sumX += xs[i]
                sumY += ys[i]
                sumZ += zs[i]
            }
            val meanX = sumX / count
            val meanY = sumY / count
            val meanZ = sumZ / count

            var variance = 0.0
            for (i in 0 until count) {
                val dx = xs[i] - meanX
                val dy = ys[i] - meanY
                val dz = zs[i] - meanZ
                variance += dx * dx + dy * dy + dz * dz
            }
            return (variance / count).toFloat()
        }
    }

    private val accelBuffer = AxisBuffer(config.windowSize)
    private val gyroBuffer = AxisBuffer(config.windowSize)

    private var warmupStartMs = -1L

    fun reset(startTimestampMs: Long) {
        accelBuffer.reset()
        gyroBuffer.reset()
        warmupStartMs = startTimestampMs
    }

    fun reset() = reset(-1L)

    fun isWarmedUp(timestampMs: Long): Boolean {
        return warmupStartMs >= 0L && timestampMs >= warmupStartMs + config.warmupMs
    }

    fun addAccelerometer(x: Float, y: Float, z: Float, timestampMs: Long) {
        if (!hasAccelerometer) return
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return
        if (!acceptTimestamp(timestampMs, accelBuffer.lastSampleMs)) return
        accelBuffer.add(x, y, z, timestampMs)
    }

    fun addGyroscope(x: Float, y: Float, z: Float, timestampMs: Long) {
        if (!hasGyroscope) return
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return
        if (!acceptTimestamp(timestampMs, gyroBuffer.lastSampleMs)) return
        gyroBuffer.add(x, y, z, timestampMs)
    }

    private fun acceptTimestamp(timestampMs: Long, lastSampleMs: Long): Boolean {
        if (timestampMs < 0L) return false
        if (warmupStartMs < 0L) warmupStartMs = timestampMs
        if (timestampMs - warmupStartMs < config.warmupMs) return false
        if (lastSampleMs >= 0L && timestampMs < lastSampleMs) return false
        return true
    }

    fun result(nowMs: Long = -1L): Result {
        val latestSampleMs = max(accelBuffer.lastSampleMs, gyroBuffer.lastSampleMs)
        val evaluationTimeMs = if (nowMs >= 0L) nowMs else latestSampleMs
        val warmedUp = warmupStartMs >= 0L &&
                latestSampleMs >= 0L &&
                latestSampleMs - warmupStartMs >= config.warmupMs

        val accelVariance = if (hasAccelerometer) {
            accelBuffer.computeVariance(config.minSamples)
        } else {
            0f
        }
        val gyroVariance = if (hasGyroscope) {
            gyroBuffer.computeVariance(config.minSamples)
        } else {
            0f
        }

        val accelThreshold = if (hasGyroscope) {
            config.maxAccelVariance
        } else {
            config.maxAccelVarianceWithoutGyro
        }

        val accelFresh = !hasAccelerometer || (
            accelBuffer.lastSampleMs >= 0L &&
                evaluationTimeMs - accelBuffer.lastSampleMs <= config.maxSampleAgeMs &&
                accelBuffer.spanMs() >= config.minWindowSpanMs
            )
        val gyroFresh = !hasGyroscope || (
            gyroBuffer.lastSampleMs >= 0L &&
                evaluationTimeMs - gyroBuffer.lastSampleMs <= config.maxSampleAgeMs &&
                gyroBuffer.spanMs() >= config.minWindowSpanMs
            )
        val sensorsAligned = !hasAccelerometer || !hasGyroscope ||
            abs(accelBuffer.lastSampleMs - gyroBuffer.lastSampleMs) <= config.maxSensorSkewMs

        val accelStill = !hasAccelerometer || (
            accelBuffer.count >= config.minSamples &&
                accelFresh &&
                accelVariance <= accelThreshold
            )
        val gyroStill = !hasGyroscope || (
            gyroBuffer.count >= config.minSamples &&
                gyroFresh &&
                gyroVariance <= config.maxGyroVariance
            )

        return Result(
            isStationary = warmedUp && sensorsAligned && accelStill && gyroStill,
            accelVariance = accelVariance,
            gyroVariance = gyroVariance,
            accelSampleCount = accelBuffer.count,
            gyroSampleCount = gyroBuffer.count,
            warmedUp = warmedUp,
        )
    }
}
