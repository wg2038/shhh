package io.github.wg2038.shhh

import kotlin.math.abs

/**
 * Normalizes sensor event timestamps (in nanoseconds) to monotonic milliseconds aligned with
 * [android.os.SystemClock.elapsedRealtime], preserving hardware inter-sample spacing even when
 * a sensor HAL uses a non-elapsed clock domain (such as CLOCK_MONOTONIC or uptime).
 */
class SensorTimestampNormalizer(
    private val maxClockSkewMs: Long = 300L,
    private val epochSkewThresholdMs: Long = 2_000L,
) {
    private var clockOffsetMs: Long? = null
    private var lastTimestampMs: Long = -1L

    fun reset() {
        clockOffsetMs = null
        lastTimestampMs = -1L
    }

    fun normalize(eventNanos: Long, nowMs: Long): Long {
        var candidate = if (eventNanos <= 0L) {
            nowMs
        } else {
            val eventMs = eventNanos / 1_000_000L
            val skew = nowMs - eventMs
            when {
                abs(skew) <= maxClockSkewMs -> {
                    clockOffsetMs = null
                    minOf(eventMs, nowMs)
                }
                abs(skew) > epochSkewThresholdMs -> {
                    val existingOffset = clockOffsetMs
                    val offset = if (existingOffset == null ||
                        abs((eventMs + existingOffset) - nowMs) > maxClockSkewMs
                    ) {
                        skew.also { clockOffsetMs = it }
                    } else {
                        existingOffset
                    }
                    minOf(eventMs + offset, nowMs)
                }
                else -> nowMs
            }
        }

        if (lastTimestampMs >= 0L && candidate < lastTimestampMs) {
            candidate = lastTimestampMs
        }
        lastTimestampMs = candidate
        return candidate
    }
}

object SensorClassifier {
    private val virtualProximityMarkers = listOf(
        "palm",
        "touch",
        "virtual",
        "ultrason",
        "elliptic",
        "pseudo",
        "fake",
        "software",
        "simulated",
        "pocket",
        "hover",
        "tp_prox",
        "algo",
        "earhover",
        "sx93",
        "sar_prox",
        "sar ",
        "_sar",
        "sip_prox",
        "rear_prox",
    )

    fun isHardwareOpticalProximity(name: String, vendor: String): Boolean {
        val info = "$name $vendor".lowercase()
        return virtualProximityMarkers.none { info.contains(it) }
    }
}
