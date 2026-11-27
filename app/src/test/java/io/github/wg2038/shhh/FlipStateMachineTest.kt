package io.github.wg2038.shhh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the framework-free [FlipStateMachine]. Debounce durations are
 * shortened so the tests stay fast; thresholds and transition rules are the
 * production ones.
 */
class FlipStateMachineTest {

    private val fastConfig = FlipStateMachine.Config(
        downDebounceMs = 100L,
        downDebounceNoGyroMs = 150L,
        upDebounceMs = 30L,
        maxStillnessRetries = 3,
    )

    private fun stationary() = StillnessDetector.Result(
        isStationary = true,
        accelVariance = 1.0e-4f,
        gyroVariance = 1.0e-5f,
        accelSampleCount = 50,
        gyroSampleCount = 50,
        warmedUp = true,
    )

    private fun unstable() = StillnessDetector.Result(
        isStationary = false,
        accelVariance = 0.02f,
        gyroVariance = 0.002f,
        accelSampleCount = 50,
        gyroSampleCount = 50,
        warmedUp = true,
    )

    private fun observation(
        timestampMs: Long,
        gravityZ: Float = -9.8f,
        horizontalGravity: Float = 0.5f,
        proximity: FlipStateMachine.Proximity = FlipStateMachine.Proximity.NoOpticalSensor,
    ) = FlipStateMachine.Observation(
        timestampMs = timestampMs,
        hasOrientationSample = true,
        gravityZ = gravityZ,
        horizontalGravity = horizontalGravity,
        proximity = proximity,
    )

    @Test
    fun unstableSurfaceCoolsDownAfterThreeFailedStillnessChecks() {
        val machine = FlipStateMachine(
            hasGyroscope = true,
            hasOpticalProximity = false,
            config = fastConfig,
        )

        var decision = machine.onObservation(observation(0L))
        assertEquals(FlipStateMachine.Phase.DOWN_PENDING, decision.phase)
        assertTrue(decision.useFastSensors)
        assertEquals(100L, decision.scheduleCheckAtMs)

        // Attempt 1 and 2 restart the window and keep high-rate sampling.
        decision = machine.onTimer(100L, unstable())
        assertEquals(FlipStateMachine.Phase.DOWN_PENDING, decision.phase)
        assertEquals(1, decision.retryCount)
        assertTrue(decision.useFastSensors)
        assertTrue(decision.resetStillness)

        decision = machine.onTimer(200L, unstable())
        assertEquals(FlipStateMachine.Phase.DOWN_PENDING, decision.phase)
        assertEquals(2, decision.retryCount)
        assertTrue(decision.useFastSensors)

        // Attempt 3 trips the circuit breaker.
        decision = machine.onTimer(300L, unstable())
        assertEquals(FlipStateMachine.Phase.COOLDOWN, decision.phase)
        assertEquals(3, decision.retryCount)
        assertFalse(decision.useFastSensors)
        assertTrue(decision.releaseWakeLock)
        assertTrue(decision.cancelScheduledCheck)

        // Still face down: stay in COOLDOWN, do not sample fast or retrigger.
        decision = machine.onObservation(observation(400L))
        assertEquals(FlipStateMachine.Phase.COOLDOWN, decision.phase)
        assertNull(decision.scheduleCheckAtMs)
        assertFalse(decision.useFastSensors)

        // Lift the phone: cooldown is cleared only now.
        decision = machine.onObservation(observation(500L, gravityZ = -4.0f, horizontalGravity = 3.0f))
        assertEquals(FlipStateMachine.Phase.IDLE, decision.phase)

        // Put it back down on a stable surface: detection works again.
        decision = machine.onObservation(observation(600L))
        assertEquals(FlipStateMachine.Phase.DOWN_PENDING, decision.phase)
        assertEquals(700L, decision.scheduleCheckAtMs)

        decision = machine.onTimer(700L, stationary())
        assertEquals(FlipStateMachine.Phase.FLIPPED, decision.phase)
        assertTrue(decision.enterDnd)
    }

    @Test
    fun externalDndOffDoesNotBounceBackUntilPhoneIsLifted() {
        val machine = FlipStateMachine(
            hasGyroscope = true,
            hasOpticalProximity = false,
            config = fastConfig,
        )

        // Establish a normal flipped-down state.
        machine.onObservation(observation(0L))
        var decision = machine.onTimer(100L, stationary())
        assertEquals(FlipStateMachine.Phase.FLIPPED, decision.phase)
        assertTrue(decision.enterDnd)

        // User/system turns DND off while the phone remains face down.
        decision = machine.onExternalDndDisabled()
        assertEquals(FlipStateMachine.Phase.IDLE, decision.phase)
        assertTrue(machine.suppressedUntilLifted)
        assertFalse(decision.enterDnd)

        // The phone is still face down: repeated observations must not re-arm
        // the countdown or turn DND back on.
        var t = 160L
        repeat(10) {
            decision = machine.onObservation(observation(t))
            assertEquals(FlipStateMachine.Phase.IDLE, decision.phase)
            assertFalse(decision.enterDnd)
            assertFalse(decision.useFastSensors)
            assertNull(decision.scheduleCheckAtMs)
            t += 20L
        }
        assertTrue(machine.suppressedUntilLifted)

        // Lift it: the latch clears.
        decision = machine.onObservation(observation(t, gravityZ = -3.0f, horizontalGravity = 2.0f))
        assertEquals(FlipStateMachine.Phase.IDLE, decision.phase)
        assertFalse(machine.suppressedUntilLifted)

        // Place it face down again: a fresh countdown is allowed.
        decision = machine.onObservation(observation(t + 20L))
        assertEquals(FlipStateMachine.Phase.DOWN_PENDING, decision.phase)
        decision = machine.onTimer(t + 120L, stationary())
        assertEquals(FlipStateMachine.Phase.FLIPPED, decision.phase)
        assertTrue(decision.enterDnd)
    }

    @Test
    fun noGyroscopeUsesLongerStillnessWindow() {
        val machine = FlipStateMachine(
            hasGyroscope = false,
            hasOpticalProximity = false,
            config = fastConfig,
        )

        val decision = machine.onObservation(observation(1_000L))
        assertEquals(FlipStateMachine.Phase.DOWN_PENDING, decision.phase)
        // downDebounceNoGyroMs = 150 instead of 100.
        assertEquals(1_150L, decision.scheduleCheckAtMs)

        val confirmed = machine.onTimer(1_150L, stationary())
        assertEquals(FlipStateMachine.Phase.FLIPPED, confirmed.phase)
        assertTrue(confirmed.enterDnd)
    }

    @Test
    fun unreadyProximityDoesNotBlockIdleOrForceCooldown() {
        val machine = FlipStateMachine(
            hasGyroscope = true,
            hasOpticalProximity = true,
            config = fastConfig,
        )

        // AwaitingFirstSample (proximity off in IDLE_LISTENING) must enter DOWN_PENDING immediately.
        var decision = machine.onObservation(
            observation(0L, proximity = FlipStateMachine.Proximity.AwaitingFirstSample)
        )
        assertEquals(FlipStateMachine.Phase.DOWN_PENDING, decision.phase)
        assertFalse(decision.blockedByProximity)
        assertTrue(decision.useFastSensors)
        assertEquals(100L, decision.scheduleCheckAtMs)

        // Subsequent unready observations must not cancel or restart the countdown.
        decision = machine.onObservation(
            observation(40L, proximity = FlipStateMachine.Proximity.AwaitingFirstSample)
        )
        assertEquals(FlipStateMachine.Phase.DOWN_PENDING, decision.phase)
        assertNull(decision.scheduleCheckAtMs)
        assertFalse(decision.cancelScheduledCheck)

        // If proximity never reports before the timer fires, stillness confirmation still succeeds.
        decision = machine.onTimer(100L, stationary())
        assertEquals(FlipStateMachine.Phase.FLIPPED, decision.phase)
        assertTrue(decision.enterDnd)
        assertFalse(decision.blockedByProximity)

        // FAR (mid-air) blocks entry in IDLE when known.
        machine.reset()
        decision = machine.onObservation(
            observation(200L, proximity = FlipStateMachine.Proximity.Far)
        )
        assertEquals(FlipStateMachine.Phase.IDLE, decision.phase)
        assertTrue(decision.blockedByProximity)

        // NEAR (against the desk) permits entry immediately.
        decision = machine.onObservation(
            observation(220L, proximity = FlipStateMachine.Proximity.Near)
        )
        assertEquals(FlipStateMachine.Phase.DOWN_PENDING, decision.phase)
    }

    @Test
    fun unreadyProximityWithProductionTimeoutsDoesNotDeadlockOnTimer() {
        // Production defaults: downDebounceMs = 2_000.
        val prodMachine = FlipStateMachine(
            hasGyroscope = true,
            hasOpticalProximity = true,
        )
        var decision = prodMachine.onObservation(
            observation(0L, proximity = FlipStateMachine.Proximity.AwaitingFirstSample)
        )
        assertEquals(FlipStateMachine.Phase.DOWN_PENDING, decision.phase)

        // Even if proximity stays AwaitingFirstSample and stillness fails, retries must not
        // report blockedByProximity.
        decision = prodMachine.onTimer(2_000L, unstable())
        assertEquals(FlipStateMachine.Phase.DOWN_PENDING, decision.phase)
        assertFalse(decision.blockedByProximity)

        decision = prodMachine.onTimer(4_000L, stationary())
        assertEquals(FlipStateMachine.Phase.FLIPPED, decision.phase)
        assertTrue(decision.enterDnd)
    }

    @Test
    fun proximityFarInDownPendingResetsOnNearAndCoolsDownIfHeldInMidAir() {
        val machine = FlipStateMachine(
            hasGyroscope = true,
            hasOpticalProximity = true,
            config = fastConfig,
        )

        machine.onObservation(observation(0L, proximity = FlipStateMachine.Proximity.AwaitingFirstSample))
        var decision = machine.onObservation(observation(20L, proximity = FlipStateMachine.Proximity.Far))
        assertEquals(FlipStateMachine.Phase.DOWN_PENDING, decision.phase)
        assertTrue(decision.blockedByProximity)

        // Landing on desk (Far -> Near) restarts the stillness debounce window.
        decision = machine.onObservation(observation(50L, proximity = FlipStateMachine.Proximity.Near))
        assertEquals(FlipStateMachine.Phase.DOWN_PENDING, decision.phase)
        assertEquals(150L, decision.scheduleCheckAtMs)
        assertTrue(decision.resetStillness)

        decision = machine.onTimer(150L, stationary())
        assertEquals(FlipStateMachine.Phase.FLIPPED, decision.phase)
        assertTrue(decision.enterDnd)

        // Persistent Far in mid-air stops fast sampling via COOLDOWN after max retries.
        machine.reset()
        machine.onObservation(observation(200L, proximity = FlipStateMachine.Proximity.AwaitingFirstSample))
        machine.onObservation(observation(210L, proximity = FlipStateMachine.Proximity.Far))
        assertEquals(FlipStateMachine.Phase.DOWN_PENDING, machine.onTimer(300L, stationary()).phase)
        assertEquals(FlipStateMachine.Phase.DOWN_PENDING, machine.onTimer(400L, stationary()).phase)
        decision = machine.onTimer(500L, stationary())
        assertEquals(FlipStateMachine.Phase.COOLDOWN, decision.phase)
        assertTrue(decision.blockedByProximity)
    }

    @Test
    fun motionDisturbanceExtendsDebounceWithoutIncrementingRetryCount() {
        val machine = FlipStateMachine(
            hasGyroscope = true,
            hasOpticalProximity = false,
            config = fastConfig,
        )

        // Outside DOWN_PENDING, motion disturbance is ignored.
        assertNull(machine.onMotionDisturbance(0L))

        machine.onObservation(observation(0L))
        assertEquals(FlipStateMachine.Phase.DOWN_PENDING, machine.phase)

        // Exceed maxStillnessRetries (3) disturbances: must NOT trip COOLDOWN.
        var t = 30L
        repeat(6) {
            val decision = machine.onMotionDisturbance(t)!!
            assertEquals(FlipStateMachine.Phase.DOWN_PENDING, decision.phase)
            assertEquals(0, decision.retryCount)
            assertEquals(t + 100L, decision.scheduleCheckAtMs)
            assertTrue(decision.resetStillness)
            assertTrue(decision.useFastSensors)
            t += 20L
        }

        val lastDisturbanceMs = t - 20L
        val confirmed = machine.onTimer(lastDisturbanceMs + 100L, stationary())
        assertEquals(FlipStateMachine.Phase.FLIPPED, confirmed.phase)
        assertTrue(confirmed.enterDnd)
        assertNull(machine.onMotionDisturbance(lastDisturbanceMs + 120L))
    }

    @Test
    fun exitingFlippedViaProximityFarWhileFaceDownLatchesUntilTiltExit() {
        val machine = FlipStateMachine(
            hasGyroscope = true,
            hasOpticalProximity = true,
            config = fastConfig,
        )

        machine.onObservation(observation(0L, proximity = FlipStateMachine.Proximity.Near))
        var decision = machine.onTimer(100L, stationary())
        assertEquals(FlipStateMachine.Phase.FLIPPED, decision.phase)

        // Optical proximity reports Far while phone remains face down (e.g. lifted straight up).
        decision = machine.onObservation(observation(120L, proximity = FlipStateMachine.Proximity.Far))
        assertEquals(FlipStateMachine.Phase.FLIPPED, decision.phase)
        assertEquals(FlipStateMachine.Target.UP, decision.pendingTarget)
        assertEquals(150L, decision.scheduleCheckAtMs)

        decision = machine.onTimer(150L, null)
        assertEquals(FlipStateMachine.Phase.IDLE, decision.phase)
        assertTrue(decision.exitDnd)
        assertTrue(machine.suppressedUntilLifted)

        // Repeated face-down observations (including AwaitingFirstSample when IDLE_LISTENING
        // turns proximity off, Near, or Far) must NOT flap back into DOWN_PENDING.
        var t = 160L
        for (prox in listOf(
            FlipStateMachine.Proximity.AwaitingFirstSample,
            FlipStateMachine.Proximity.Near,
            FlipStateMachine.Proximity.Far,
            FlipStateMachine.Proximity.AwaitingFirstSample,
        )) {
            decision = machine.onObservation(observation(t, proximity = prox))
            assertEquals(FlipStateMachine.Phase.IDLE, decision.phase)
            assertFalse(decision.useFastSensors)
            assertNull(decision.scheduleCheckAtMs)
            t += 20L
        }

        // Slight tilt inside the hysteresis band (cosTilt ~ 0.90, not yet tiltExit) keeps latch.
        decision = machine.onObservation(
            observation(t, gravityZ = -9.0f, horizontalGravity = 4.35f, proximity = FlipStateMachine.Proximity.AwaitingFirstSample)
        )
        assertEquals(FlipStateMachine.Phase.IDLE, decision.phase)
        assertTrue(machine.suppressedUntilLifted)

        decision = machine.onObservation(
            observation(t + 20L, proximity = FlipStateMachine.Proximity.AwaitingFirstSample)
        )
        assertEquals(FlipStateMachine.Phase.IDLE, decision.phase)

        // Genuine tiltExit clears the anti-flapping latch.
        decision = machine.onObservation(
            observation(t + 40L, gravityZ = -4.0f, horizontalGravity = 3.0f)
        )
        assertEquals(FlipStateMachine.Phase.IDLE, decision.phase)
        assertFalse(machine.suppressedUntilLifted)

        // Re-placing phone face down works normally again.
        decision = machine.onObservation(
            observation(t + 60L, proximity = FlipStateMachine.Proximity.AwaitingFirstSample)
        )
        assertEquals(FlipStateMachine.Phase.DOWN_PENDING, decision.phase)
        decision = machine.onTimer(t + 160L, stationary())
        assertEquals(FlipStateMachine.Phase.FLIPPED, decision.phase)
        assertTrue(decision.enterDnd)
    }

    @Test
    fun exitingFlippedViaTiltExitDoesNotSuppressReentry() {
        val machine = FlipStateMachine(
            hasGyroscope = true,
            hasOpticalProximity = true,
            config = fastConfig,
        )

        machine.onObservation(observation(0L, proximity = FlipStateMachine.Proximity.Near))
        machine.onTimer(100L, stationary())

        // Normal lift with tilt + Far proximity.
        machine.onObservation(
            observation(120L, gravityZ = -4.0f, horizontalGravity = 3.0f, proximity = FlipStateMachine.Proximity.Far)
        )
        val exitDecision = machine.onTimer(150L, null)
        assertEquals(FlipStateMachine.Phase.IDLE, exitDecision.phase)
        assertTrue(exitDecision.exitDnd)
        assertFalse(machine.suppressedUntilLifted)

        // Putting it straight back face down works without needing another tiltExit.
        val reenter = machine.onObservation(
            observation(200L, proximity = FlipStateMachine.Proximity.AwaitingFirstSample)
        )
        assertEquals(FlipStateMachine.Phase.DOWN_PENDING, reenter.phase)
    }

    @Test
    fun proximityNearDuringUpDebounceCancelsRestoreAndDoesNotLatch() {
        val machine = FlipStateMachine(
            hasGyroscope = true,
            hasOpticalProximity = true,
            config = fastConfig,
        )

        machine.onObservation(observation(0L, proximity = FlipStateMachine.Proximity.Near))
        machine.onTimer(100L, stationary())

        var decision = machine.onObservation(observation(120L, proximity = FlipStateMachine.Proximity.Far))
        assertEquals(FlipStateMachine.Target.UP, decision.pendingTarget)

        decision = machine.onObservation(observation(135L, proximity = FlipStateMachine.Proximity.Near))
        assertEquals(FlipStateMachine.Phase.FLIPPED, decision.phase)
        assertEquals(FlipStateMachine.Target.NONE, decision.pendingTarget)
        assertTrue(decision.cancelScheduledCheck)
        assertFalse(machine.suppressedUntilLifted)
    }

    @Test
    fun nudgeDuringUpDebounceCancelsRestore() {
        val machine = FlipStateMachine(
            hasGyroscope = true,
            hasOpticalProximity = false,
            config = fastConfig,
        )

        machine.onObservation(observation(0L))
        machine.onTimer(100L, stationary())

        // Lift slightly -> UP countdown.
        var decision = machine.onObservation(observation(120L, gravityZ = -7.0f, horizontalGravity = 1.0f))
        assertEquals(FlipStateMachine.Phase.FLIPPED, decision.phase)
        assertEquals(FlipStateMachine.Target.UP, decision.pendingTarget)
        assertEquals(150L, decision.scheduleCheckAtMs)

        // Nudge back flat before the debounce expires -> cancel restore.
        decision = machine.onObservation(observation(140L))
        assertEquals(FlipStateMachine.Phase.FLIPPED, decision.phase)
        assertEquals(FlipStateMachine.Target.NONE, decision.pendingTarget)
        assertTrue(decision.cancelScheduledCheck)

        decision = machine.onTimer(150L, null)
        assertEquals(FlipStateMachine.Phase.FLIPPED, decision.phase)
        assertFalse(decision.exitDnd)
    }

    @Test
    fun motionDisturbanceAfterPriorFailedStillnessCheckDoesNotIncrementRetryCount() {
        val machine = FlipStateMachine(
            hasGyroscope = true,
            hasOpticalProximity = false,
            config = fastConfig,
        )

        machine.onObservation(observation(0L))
        // One failed stillness timer increments retryCount to 1.
        var decision = machine.onTimer(100L, unstable())
        assertEquals(FlipStateMachine.Phase.DOWN_PENDING, decision.phase)
        assertEquals(1, decision.retryCount)

        // Multiple motion disturbances must extend deadline without incrementing retryCount to COOLDOWN.
        var t = 130L
        repeat(5) {
            decision = machine.onMotionDisturbance(t)!!
            assertEquals(FlipStateMachine.Phase.DOWN_PENDING, decision.phase)
            assertEquals(1, decision.retryCount)
            assertEquals(t + 100L, decision.scheduleCheckAtMs)
            t += 20L
        }

        val lastDisturbance = t - 20L
        decision = machine.onTimer(lastDisturbance + 100L, stationary())
        assertEquals(FlipStateMachine.Phase.FLIPPED, decision.phase)
        assertTrue(decision.enterDnd)
    }

    @Test
    fun exitingFlippedViaProximityFarAfterBriefTiltBackToFaceDownLatchesUntilTiltExit() {
        val machine = FlipStateMachine(
            hasGyroscope = true,
            hasOpticalProximity = true,
            config = fastConfig,
        )

        machine.onObservation(observation(0L, proximity = FlipStateMachine.Proximity.Near))
        machine.onTimer(100L, stationary())

        // Lift starts via Far proximity while flat.
        var decision = machine.onObservation(observation(120L, proximity = FlipStateMachine.Proximity.Far))
        assertEquals(FlipStateMachine.Target.UP, decision.pendingTarget)

        // Brief >30 deg tilt, then laid back flat while still Far before upDebounce expires.
        decision = machine.onObservation(
            observation(130L, gravityZ = -4.0f, horizontalGravity = 3.0f, proximity = FlipStateMachine.Proximity.Far)
        )
        assertEquals(FlipStateMachine.Target.UP, decision.pendingTarget)
        decision = machine.onObservation(
            observation(140L, gravityZ = -9.8f, horizontalGravity = 0.5f, proximity = FlipStateMachine.Proximity.Far)
        )
        assertEquals(FlipStateMachine.Target.UP, decision.pendingTarget)

        // Exit at 150ms occurs while face-down & Far -> must latch until next tiltExit.
        decision = machine.onTimer(150L, null)
        assertEquals(FlipStateMachine.Phase.IDLE, decision.phase)
        assertTrue(decision.exitDnd)
        assertTrue(machine.suppressedUntilLifted)

        decision = machine.onObservation(
            observation(170L, proximity = FlipStateMachine.Proximity.AwaitingFirstSample)
        )
        assertEquals(FlipStateMachine.Phase.IDLE, decision.phase)
        assertFalse(decision.useFastSensors)
    }

    @Test
    fun resetStartFlippedAllowsImmediateTiltOrProximityExit() {
        val machine = FlipStateMachine(
            hasGyroscope = true,
            hasOpticalProximity = true,
            config = fastConfig,
        )

        machine.reset(startFlipped = true)
        assertEquals(FlipStateMachine.Phase.FLIPPED, machine.phase)

        // Missing orientation sample does not trigger premature state transitions.
        val noSample = machine.onObservation(
            FlipStateMachine.Observation(
                timestampMs = 0L,
                hasOrientationSample = false,
                gravityZ = 0f,
                horizontalGravity = 0f,
                proximity = FlipStateMachine.Proximity.Far,
            )
        )
        assertEquals(FlipStateMachine.Phase.FLIPPED, noSample.phase)
        assertEquals(FlipStateMachine.Target.NONE, noSample.pendingTarget)

        // First orientation sample after restart shows phone was already tilted face up.
        val tilt = machine.onObservation(
            observation(20L, gravityZ = 9.8f, horizontalGravity = 0.2f, proximity = FlipStateMachine.Proximity.Far)
        )
        assertEquals(FlipStateMachine.Phase.FLIPPED, tilt.phase)
        assertEquals(FlipStateMachine.Target.UP, tilt.pendingTarget)
        assertEquals(50L, tilt.scheduleCheckAtMs)

        val exit = machine.onTimer(50L, null)
        assertEquals(FlipStateMachine.Phase.IDLE, exit.phase)
        assertTrue(exit.exitDnd)
        assertFalse(machine.suppressedUntilLifted)
    }

    @Test
    fun earlyTimerBeforeDeadlineReschedulesWithoutAdvancingRetryOrExiting() {
        val machine = FlipStateMachine(
            hasGyroscope = true,
            hasOpticalProximity = false,
            config = fastConfig,
        )

        var decision = machine.onObservation(observation(0L))
        assertEquals(100L, decision.scheduleCheckAtMs)

        // Early timer firing during DOWN_PENDING must reschedule deadline without incrementing retryCount.
        decision = machine.onTimer(95L, unstable())
        assertEquals(FlipStateMachine.Phase.DOWN_PENDING, decision.phase)
        assertEquals(0, decision.retryCount)
        assertEquals(100L, decision.scheduleCheckAtMs)
        assertFalse(decision.resetStillness)

        decision = machine.onTimer(100L, stationary())
        assertEquals(FlipStateMachine.Phase.FLIPPED, decision.phase)
        assertTrue(decision.enterDnd)

        // Start UP debounce at 120ms -> deadline 150ms.
        decision = machine.onObservation(observation(120L, gravityZ = 9.8f, horizontalGravity = 0.2f))
        assertEquals(FlipStateMachine.Target.UP, decision.pendingTarget)
        assertEquals(150L, decision.scheduleCheckAtMs)

        // Early timer firing during FLIPPED UP debounce must reschedule without exiting DND.
        decision = machine.onTimer(145L, null)
        assertEquals(FlipStateMachine.Phase.FLIPPED, decision.phase)
        assertEquals(FlipStateMachine.Target.UP, decision.pendingTarget)
        assertEquals(150L, decision.scheduleCheckAtMs)
        assertFalse(decision.exitDnd)

        decision = machine.onTimer(150L, null)
        assertEquals(FlipStateMachine.Phase.IDLE, decision.phase)
        assertTrue(decision.exitDnd)
    }

    @Test
    fun externalDndDisabledDuringDownPendingCancelsAndSuppressesUntilLifted() {
        val machine = FlipStateMachine(
            hasGyroscope = true,
            hasOpticalProximity = false,
            config = fastConfig,
        )

        var decision = machine.onObservation(observation(0L))
        assertEquals(FlipStateMachine.Phase.DOWN_PENDING, decision.phase)
        assertTrue(decision.useFastSensors)

        decision = machine.onExternalDndDisabled()
        assertEquals(FlipStateMachine.Phase.IDLE, decision.phase)
        assertEquals(FlipStateMachine.Target.NONE, decision.pendingTarget)
        assertFalse(decision.useFastSensors)
        assertTrue(decision.releaseWakeLock)
        assertTrue(decision.cancelScheduledCheck)
        assertTrue(machine.suppressedUntilLifted)

        // Remains suppressed while still face down.
        decision = machine.onObservation(observation(60L))
        assertEquals(FlipStateMachine.Phase.IDLE, decision.phase)
        assertNull(decision.scheduleCheckAtMs)

        // Tilting clears suppression so subsequent face-down works normally.
        machine.onObservation(observation(80L, gravityZ = 9.8f, horizontalGravity = 0.2f))
        assertFalse(machine.suppressedUntilLifted)
        decision = machine.onObservation(observation(100L))
        assertEquals(FlipStateMachine.Phase.DOWN_PENDING, decision.phase)
    }

    @Test
    fun continuousMotionDisturbancesExceedingMaxDownPendingBudgetCoolDown() {
        val machine = FlipStateMachine(
            hasGyroscope = true,
            hasOpticalProximity = false,
            config = fastConfig.copy(maxDownPendingMs = 300L),
        )

        machine.onObservation(observation(0L))
        assertEquals(FlipStateMachine.Phase.DOWN_PENDING, machine.phase)

        // Disturbances inside the budget keep extending DOWN_PENDING.
        assertEquals(FlipStateMachine.Phase.DOWN_PENDING, machine.onMotionDisturbance(100L)!!.phase)
        assertEquals(FlipStateMachine.Phase.DOWN_PENDING, machine.onMotionDisturbance(200L)!!.phase)

        // Reaching the max budget trips COOLDOWN instead of draining battery indefinitely.
        val decision = machine.onMotionDisturbance(300L)!!
        assertEquals(FlipStateMachine.Phase.COOLDOWN, decision.phase)
        assertFalse(decision.useFastSensors)
        assertTrue(decision.releaseWakeLock)
        assertTrue(decision.cancelScheduledCheck)
    }

    @Test
    fun enterDndFailureMovesToCooldownUntilPhoneIsLifted() {
        val machine = FlipStateMachine(
            hasGyroscope = true,
            hasOpticalProximity = false,
            config = fastConfig,
        )

        machine.onObservation(observation(0L))
        val enter = machine.onTimer(100L, stationary())
        assertTrue(enter.enterDnd)

        val failed = machine.onEnterDndFailed()
        assertEquals(FlipStateMachine.Phase.COOLDOWN, failed.phase)
        assertFalse(failed.useFastSensors)
        assertTrue(failed.releaseWakeLock)

        // Remaining face down must not immediately re-arm a retry loop.
        val stillDown = machine.onObservation(observation(150L))
        assertEquals(FlipStateMachine.Phase.COOLDOWN, stillDown.phase)
        assertFalse(stillDown.useFastSensors)
        assertNull(stillDown.scheduleCheckAtMs)

        // Lifting the phone resets COOLDOWN back to IDLE.
        val lifted = machine.onObservation(observation(200L, gravityZ = 9.8f, horizontalGravity = 0.1f))
        assertEquals(FlipStateMachine.Phase.IDLE, lifted.phase)
    }

    @Test
    fun callBecomingActiveDuringDownPendingCancelsCountdown() {
        val machine = FlipStateMachine(
            hasGyroscope = true,
            hasOpticalProximity = false,
            config = fastConfig,
        )

        machine.onObservation(observation(0L))
        assertEquals(FlipStateMachine.Phase.DOWN_PENDING, machine.phase)

        val decision = machine.onCallActive()!!
        assertEquals(FlipStateMachine.Phase.IDLE, decision.phase)
        assertFalse(decision.useFastSensors)
        assertTrue(decision.releaseWakeLock)
        assertTrue(decision.cancelScheduledCheck)
        assertTrue(decision.resetStillness)
    }

    @Test
    fun nonFiniteOrientationValuesAreIgnoredSafely() {
        val machine = FlipStateMachine(
            hasGyroscope = true,
            hasOpticalProximity = false,
            config = fastConfig,
        )

        machine.onObservation(observation(0L))
        machine.onTimer(100L, stationary())
        assertEquals(FlipStateMachine.Phase.FLIPPED, machine.phase)

        val nanDecision = machine.onObservation(observation(120L, gravityZ = Float.NaN))
        assertEquals(FlipStateMachine.Phase.FLIPPED, nanDecision.phase)
        assertEquals(FlipStateMachine.Target.NONE, nanDecision.pendingTarget)

        val infDecision = machine.onObservation(observation(140L, horizontalGravity = Float.POSITIVE_INFINITY))
        assertEquals(FlipStateMachine.Phase.FLIPPED, infDecision.phase)
        assertEquals(FlipStateMachine.Target.NONE, infDecision.pendingTarget)
    }

    @Test
    fun disablingGyroscopeMidPendingExtendsDebounceToNoGyroWindow() {
        val machine = FlipStateMachine(
            hasGyroscope = true,
            hasOpticalProximity = false,
            config = fastConfig,
        )

        val initial = machine.onObservation(observation(100L))
        assertEquals(FlipStateMachine.Phase.DOWN_PENDING, initial.phase)
        assertEquals(200L, initial.scheduleCheckAtMs)

        val extendedDeadline = machine.disableGyroscope(120L)
        assertEquals(270L, extendedDeadline)

        // Old 200ms deadline should now reschedule instead of failing stillness early.
        val earlyCheck = machine.onTimer(200L, stationary())
        assertEquals(FlipStateMachine.Phase.DOWN_PENDING, earlyCheck.phase)
        assertEquals(270L, earlyCheck.scheduleCheckAtMs)

        val confirmed = machine.onTimer(270L, stationary())
        assertEquals(FlipStateMachine.Phase.FLIPPED, confirmed.phase)
        assertTrue(confirmed.enterDnd)
        assertNull(machine.disableGyroscope(300L))
    }
}
