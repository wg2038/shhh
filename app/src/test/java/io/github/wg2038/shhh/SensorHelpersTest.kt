package io.github.wg2038.shhh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorHelpersTest {

    @Test
    fun normalElapsedTimestampsPreserveHardwareTiming() {
        val normalizer = SensorTimestampNormalizer(maxClockSkewMs = 300L, epochSkewThresholdMs = 2_000L)

        assertEquals(990L, normalizer.normalize(eventNanos = 990_000_000L, nowMs = 1_000L))
        assertEquals(1_010L, normalizer.normalize(eventNanos = 1_010_000_000L, nowMs = 1_020L))
    }

    @Test
    fun differentClockEpochPreservesInterSampleIntervals() {
        val normalizer = SensorTimestampNormalizer(maxClockSkewMs = 300L, epochSkewThresholdMs = 2_000L)

        // Simulate sensor reporting uptimeNanos (~100s) while elapsedRealtime is ~500s.
        val t1 = normalizer.normalize(eventNanos = 100_000_000_000L, nowMs = 500_000L)
        val t2 = normalizer.normalize(eventNanos = 100_020_000_000L, nowMs = 500_022L)
        val t3 = normalizer.normalize(eventNanos = 100_040_000_000L, nowMs = 500_045L)

        assertEquals(500_000L, t1)
        assertEquals(500_020L, t2)
        assertEquals(500_040L, t3)
    }

    @Test
    fun delayedSampleDoesNotCauseSubsequentSamplesToBeRejectedForBackwardTimestamps() {
        val normalizer = SensorTimestampNormalizer(maxClockSkewMs = 300L, epochSkewThresholdMs = 2_000L)

        assertEquals(1_000L, normalizer.normalize(eventNanos = 1_000_000_000L, nowMs = 1_005L))
        // Sample delayed by 350ms -> falls back to nowMs (1350).
        val stalled = normalizer.normalize(eventNanos = 1_000_000_000L, nowMs = 1_350L)
        assertEquals(1_350L, stalled)

        // Subsequent sample with hardware timestamp slightly before 1350 clamps monotonically instead of going backwards.
        val recovered = normalizer.normalize(eventNanos = 1_340_000_000L, nowMs = 1_360L)
        assertTrue(recovered >= stalled)
    }

    @Test
    fun nonPositiveTimestampFallsBackToNowAndResetClearsState() {
        val normalizer = SensorTimestampNormalizer()
        assertEquals(2_000L, normalizer.normalize(eventNanos = 0L, nowMs = 2_000L))
        assertEquals(2_050L, normalizer.normalize(eventNanos = -5L, nowMs = 2_050L))

        normalizer.reset()
        assertEquals(500L, normalizer.normalize(eventNanos = 500_000_000L, nowMs = 510L))
    }

    @Test
    fun hardwareVsVirtualProximityClassificationWorksAsExpected() {
        assertTrue(SensorClassifier.isHardwareOpticalProximity("TCS3701 Proximity", "ams"))
        assertTrue(SensorClassifier.isHardwareOpticalProximity("STK3x1x ALS/PS", "Sensortek"))

        assertFalse(SensorClassifier.isHardwareOpticalProximity("Elliptic Ultrasound Proximity", "Elliptic Labs"))
        assertFalse(SensorClassifier.isHardwareOpticalProximity("Palm Touch Proximity", "Vendor"))
        assertFalse(SensorClassifier.isHardwareOpticalProximity("Virtual Pocket Sensor", "OEM"))
        assertFalse(SensorClassifier.isHardwareOpticalProximity("sx932x sar_prox", "Semtech"))
        assertFalse(SensorClassifier.isHardwareOpticalProximity("tp_prox", "Goodix"))
    }
}
