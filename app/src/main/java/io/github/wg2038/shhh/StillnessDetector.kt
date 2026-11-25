package io.github.wg2038.shhh

import kotlin.math.max

/**
 * Sliding-window stillness detector using de-meaned 3-axis accelerometer and gyroscope variance.
 * Discards the initial warm-up window to skip landing impacts and cold-start transients.
 */
class StillnessDetector(
    private val hasGyroscope: Boolean,
    private val config: Config = Config(),
) {

    data class Config(
        val windowSize: Int = 50,
        val warmupMs: Long = 300L,
        val minSamples: Int = 15,
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

    private val accelX = FloatArray(config.windowSize)
    private val accelY = FloatArray(config.windowSize)
    private val accelZ = FloatArray(config.windowSize)
    private var accelIndex = 0
    private var accelCount = 0

    private val gyroX = FloatArray(config.windowSize)
    private val gyroY = FloatArray(config.windowSize)
    private val gyroZ = FloatArray(config.windowSize)
    private var gyroIndex = 0
    private var gyroCount = 0

    private var warmupStartMs = -1L
    private var lastAccelSampleMs = -1L
    private var lastGyroSampleMs = -1L

    fun reset(startTimestampMs: Long) {
        accelIndex = 0
        accelCount = 0
        gyroIndex = 0
        gyroCount = 0
        warmupStartMs = startTimestampMs
        lastAccelSampleMs = -1L
        lastGyroSampleMs = -1L
    }

    fun reset() = reset(-1L)

    fun isWarmedUp(timestampMs: Long): Boolean {
        return warmupStartMs >= 0L && timestampMs >= warmupStartMs + config.warmupMs
    }

    fun addAccelerometer(x: Float, y: Float, z: Float, timestampMs: Long) {
        if (!acceptAccelTimestamp(timestampMs)) return
        accelX[accelIndex] = x
        accelY[accelIndex] = y
        accelZ[accelIndex] = z
        accelIndex = (accelIndex + 1) % config.windowSize
        if (accelCount < config.windowSize) accelCount++
    }

    fun addGyroscope(x: Float, y: Float, z: Float, timestampMs: Long) {
        if (!hasGyroscope) return
        if (!acceptGyroTimestamp(timestampMs)) return
        gyroX[gyroIndex] = x
        gyroY[gyroIndex] = y
        gyroZ[gyroIndex] = z
        gyroIndex = (gyroIndex + 1) % config.windowSize
        if (gyroCount < config.windowSize) gyroCount++
    }

    private fun acceptAccelTimestamp(timestampMs: Long): Boolean {
        if (timestampMs < 0L) return false
        if (warmupStartMs < 0L) warmupStartMs = timestampMs
        if (timestampMs < warmupStartMs) return false
        if (timestampMs - warmupStartMs < config.warmupMs) return false
        if (lastAccelSampleMs >= 0L && timestampMs < lastAccelSampleMs) return false
        lastAccelSampleMs = max(lastAccelSampleMs, timestampMs)
        return true
    }

    private fun acceptGyroTimestamp(timestampMs: Long): Boolean {
        if (timestampMs < 0L) return false
        if (warmupStartMs < 0L) warmupStartMs = timestampMs
        if (timestampMs < warmupStartMs) return false
        if (timestampMs - warmupStartMs < config.warmupMs) return false
        if (lastGyroSampleMs >= 0L && timestampMs < lastGyroSampleMs) return false
        lastGyroSampleMs = max(lastGyroSampleMs, timestampMs)
        return true
    }

    fun result(): Result {
        val latestSampleMs = max(lastAccelSampleMs, lastGyroSampleMs)
        val warmedUp = warmupStartMs >= 0L &&
                latestSampleMs >= 0L &&
                latestSampleMs - warmupStartMs >= config.warmupMs

        val accelVariance = computeVariance(accelX, accelY, accelZ, accelCount)
        val gyroVariance = if (hasGyroscope) {
            computeVariance(gyroX, gyroY, gyroZ, gyroCount)
        } else {
            0f
        }

        val accelThreshold = if (hasGyroscope) {
            config.maxAccelVariance
        } else {
            config.maxAccelVarianceWithoutGyro
        }
        val accelStill = accelCount >= config.minSamples && accelVariance <= accelThreshold
        val gyroStill = !hasGyroscope ||
                (gyroCount >= config.minSamples && gyroVariance <= config.maxGyroVariance)

        return Result(
            isStationary = warmedUp && accelStill && gyroStill,
            accelVariance = accelVariance,
            gyroVariance = gyroVariance,
            accelSampleCount = accelCount,
            gyroSampleCount = gyroCount,
            warmedUp = warmedUp,
        )
    }

    private fun computeVariance(xs: FloatArray, ys: FloatArray, zs: FloatArray, count: Int): Float {
        if (count < config.minSamples) return Float.MAX_VALUE
        var sumX = 0f
        var sumY = 0f
        var sumZ = 0f
        for (i in 0 until count) {
            sumX += xs[i]
            sumY += ys[i]
            sumZ += zs[i]
        }
        val meanX = sumX / count
        val meanY = sumY / count
        val meanZ = sumZ / count

        var variance = 0f
        for (i in 0 until count) {
            val dx = xs[i] - meanX
            val dy = ys[i] - meanY
            val dz = zs[i] - meanZ
            variance += dx * dx + dy * dy + dz * dz
        }
        return variance / count
    }
}
