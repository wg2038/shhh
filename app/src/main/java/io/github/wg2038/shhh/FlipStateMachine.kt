package io.github.wg2038.shhh

import kotlin.math.sqrt

/**
 * Pure state machine governing face-down confirmation, stillness retries,
 * on-demand optical proximity checks, external DND suppression, and lift exit.
 */
class FlipStateMachine(
    private val hasGyroscope: Boolean,
    private val hasOpticalProximity: Boolean,
    private val config: Config = Config(),
) {

    data class Config(
        val downDebounceMs: Long = 2_000L,
        val downDebounceNoGyroMs: Long = 2_500L,
        val upDebounceMs: Long = 300L,
        val maxStillnessRetries: Int = 3,
        val maxDownPendingMs: Long? = null,
        val minGravityMagnitude: Float = 8.0f,
        val maxGravityMagnitude: Float = 12.0f,
        // cos(15.2 deg) for face-down entry, cos(30.0 deg) for lift exit hysteresis.
        val enterCosTiltThreshold: Float = 0.965f,
        val exitCosTiltThreshold: Float = 0.866f,
    )

    enum class Phase {
        IDLE,
        DOWN_PENDING,
        FLIPPED,
        COOLDOWN,
    }

    enum class Target { NONE, UP }

    sealed interface Proximity {
        data object NoOpticalSensor : Proximity
        data object AwaitingFirstSample : Proximity
        data object Near : Proximity
        data object Far : Proximity
    }

    data class Observation(
        val timestampMs: Long,
        val hasOrientationSample: Boolean,
        val gravityZ: Float,
        val horizontalGravity: Float,
        val proximity: Proximity,
    )

    data class Decision(
        val phase: Phase,
        val pendingTarget: Target,
        val useFastSensors: Boolean,
        val enterDnd: Boolean = false,
        val exitDnd: Boolean = false,
        val acquireWakeLockMs: Long? = null,
        val releaseWakeLock: Boolean = false,
        val scheduleCheckAtMs: Long? = null,
        val cancelScheduledCheck: Boolean = false,
        val resetStillness: Boolean = false,
        val retryCount: Int = 0,
        val blockedByProximity: Boolean = false,
    )

    var phase: Phase = Phase.IDLE
        private set

    var pendingTarget: Target = Target.NONE
        private set

    var stillnessRetryCount: Int = 0
        private set

    var suppressedUntilLifted: Boolean = false
        private set

    private var hasGyroscopeState: Boolean = hasGyroscope

    private val downDebounceMs: Long
        get() = if (hasGyroscopeState) config.downDebounceMs else config.downDebounceNoGyroMs

    private val maxDownPendingMs: Long
        get() = config.maxDownPendingMs ?: (downDebounceMs * (config.maxStillnessRetries + 2).coerceAtLeast(1))

    private var deadlineMs = 0L
    private var downPendingStartMs = 0L
    private var lastProximity: Proximity = defaultProximity()
    private var lastTiltExit: Boolean = false

    fun reset(startFlipped: Boolean = false) {
        phase = if (startFlipped) Phase.FLIPPED else Phase.IDLE
        pendingTarget = Target.NONE
        stillnessRetryCount = 0
        suppressedUntilLifted = false
        lastProximity = defaultProximity()
        lastTiltExit = false
        deadlineMs = 0L
        downPendingStartMs = 0L
    }

    fun onObservation(observation: Observation): Decision {
        if (!observation.hasOrientationSample ||
            !observation.gravityZ.isFinite() ||
            !observation.horizontalGravity.isFinite()
        ) {
            return decision()
        }

        val totalG = sqrt(
            observation.gravityZ * observation.gravityZ +
                observation.horizontalGravity * observation.horizontalGravity
        )
        val validRestingGravity =
            totalG in config.minGravityMagnitude..config.maxGravityMagnitude
        val cosTilt = if (totalG > 0f) -observation.gravityZ / totalG else 0f

        val faceDownPose = validRestingGravity && cosTilt >= config.enterCosTiltThreshold
        val tiltExit = totalG < config.minGravityMagnitude || cosTilt < config.exitCosTiltThreshold
        val proximityLifted = hasOpticalProximity && observation.proximity is Proximity.Far
        val exitCondition = tiltExit || proximityLifted

        return when (phase) {
            Phase.IDLE -> {
                if (tiltExit) {
                    resetToIdle(clearSuppression = true)
                    return decision()
                }
                if (suppressedUntilLifted || !faceDownPose) return decision()

                if (!isProximityPermitting(observation)) {
                    return decision(blockedByProximity = true)
                }

                phase = Phase.DOWN_PENDING
                pendingTarget = Target.NONE
                stillnessRetryCount = 0
                lastProximity = observation.proximity
                lastTiltExit = false
                downPendingStartMs = observation.timestampMs
                deadlineMs = observation.timestampMs + downDebounceMs
                return decision(
                    acquireWakeLockMs = downDebounceMs + WAKE_LOCK_MARGIN_MS,
                    scheduleCheckAtMs = deadlineMs,
                    resetStillness = true,
                )
            }

            Phase.DOWN_PENDING -> {
                val prevProximity = lastProximity
                lastProximity = observation.proximity

                if (!faceDownPose) {
                    resetToIdle(clearSuppression = false)
                    return decision(
                        releaseWakeLock = true,
                        cancelScheduledCheck = true,
                        resetStillness = true,
                    )
                }

                // Restart the stillness window when the phone transitions from mid-air onto a surface.
                if (observation.proximity is Proximity.Near && prevProximity is Proximity.Far) {
                    deadlineMs = observation.timestampMs + downDebounceMs
                    return decision(
                        acquireWakeLockMs = downDebounceMs + WAKE_LOCK_MARGIN_MS,
                        scheduleCheckAtMs = deadlineMs,
                        resetStillness = true,
                    )
                }

                if (hasOpticalProximity && observation.proximity is Proximity.Far) {
                    return decision(blockedByProximity = true)
                }

                return decision()
            }

            Phase.FLIPPED -> {
                lastProximity = observation.proximity
                lastTiltExit = tiltExit
                if (exitCondition) {
                    if (pendingTarget != Target.UP) {
                        pendingTarget = Target.UP
                        deadlineMs = observation.timestampMs + config.upDebounceMs
                        return decision(
                            acquireWakeLockMs = config.upDebounceMs + WAKE_LOCK_MARGIN_MS,
                            scheduleCheckAtMs = deadlineMs,
                        )
                    }
                } else if (faceDownPose && pendingTarget == Target.UP) {
                    pendingTarget = Target.NONE
                    return decision(
                        releaseWakeLock = true,
                        cancelScheduledCheck = true,
                    )
                }
                return decision()
            }

            Phase.COOLDOWN -> {
                if (tiltExit) {
                    resetToIdle(clearSuppression = true)
                }
                return decision()
            }
        }
    }

    fun onTimer(timestampMs: Long, stillness: StillnessDetector.Result?): Decision {
        when (phase) {
            Phase.DOWN_PENDING -> {
                if (timestampMs < deadlineMs) {
                    return decision(scheduleCheckAtMs = deadlineMs)
                }

                val proximityPermitted = isProximityPermittingAt()
                if (proximityPermitted && stillness?.isStationary == true) {
                    phase = Phase.FLIPPED
                    pendingTarget = Target.NONE
                    lastTiltExit = false
                    return decision(
                        enterDnd = true,
                        releaseWakeLock = true,
                        cancelScheduledCheck = true,
                    )
                }

                stillnessRetryCount++
                val timedOut = timestampMs - downPendingStartMs >= maxDownPendingMs
                if (stillnessRetryCount >= config.maxStillnessRetries || timedOut) {
                    return enterCooldown(blockedByProximity = !proximityPermitted)
                }

                deadlineMs = timestampMs + downDebounceMs
                return decision(
                    acquireWakeLockMs = downDebounceMs + WAKE_LOCK_MARGIN_MS,
                    scheduleCheckAtMs = deadlineMs,
                    resetStillness = true,
                    blockedByProximity = !proximityPermitted,
                )
            }

            Phase.FLIPPED -> {
                if (pendingTarget == Target.UP) {
                    if (timestampMs < deadlineMs) {
                        return decision(scheduleCheckAtMs = deadlineMs)
                    }
                    val shouldSuppress = hasOpticalProximity && lastProximity is Proximity.Far && !lastTiltExit
                    resetToIdle(clearSuppression = false)
                    if (shouldSuppress) {
                        suppressedUntilLifted = true
                    }
                    return decision(
                        exitDnd = true,
                        releaseWakeLock = true,
                        cancelScheduledCheck = true,
                    )
                }
                return decision()
            }

            Phase.IDLE, Phase.COOLDOWN -> return decision()
        }
    }

    fun onMotionDisturbance(timestampMs: Long): Decision? {
        if (phase != Phase.DOWN_PENDING) return null
        if (timestampMs - downPendingStartMs >= maxDownPendingMs) {
            return enterCooldown(blockedByProximity = !isProximityPermittingAt())
        }
        deadlineMs = timestampMs + downDebounceMs
        return decision(
            acquireWakeLockMs = downDebounceMs + WAKE_LOCK_MARGIN_MS,
            scheduleCheckAtMs = deadlineMs,
            resetStillness = true,
        )
    }

    fun onCallActive(): Decision? {
        if (phase != Phase.DOWN_PENDING) return null
        resetToIdle(clearSuppression = false)
        return decision(
            releaseWakeLock = true,
            cancelScheduledCheck = true,
            resetStillness = true,
        )
    }

    fun onEnterDndFailed(): Decision {
        return enterCooldown(blockedByProximity = false)
    }

    fun onExternalDndDisabled(): Decision {
        resetToIdle(clearSuppression = false)
        suppressedUntilLifted = true
        return decision(
            releaseWakeLock = true,
            cancelScheduledCheck = true,
        )
    }

    /**
     * Switches to no-gyroscope debounce timing if gyroscope registration fails at runtime.
     * Returns the extended deadline when in [Phase.DOWN_PENDING], or null otherwise.
     */
    fun disableGyroscope(timestampMs: Long): Long? {
        if (!hasGyroscopeState) return null
        hasGyroscopeState = false
        if (phase != Phase.DOWN_PENDING) return null
        val extendedDeadline = maxOf(deadlineMs, timestampMs + downDebounceMs)
        deadlineMs = extendedDeadline
        return extendedDeadline
    }

    private fun enterCooldown(blockedByProximity: Boolean): Decision {
        phase = Phase.COOLDOWN
        pendingTarget = Target.NONE
        return decision(
            releaseWakeLock = true,
            cancelScheduledCheck = true,
            resetStillness = true,
            blockedByProximity = blockedByProximity,
        )
    }

    private fun resetToIdle(clearSuppression: Boolean) {
        phase = Phase.IDLE
        pendingTarget = Target.NONE
        stillnessRetryCount = 0
        lastProximity = defaultProximity()
        lastTiltExit = false
        deadlineMs = 0L
        downPendingStartMs = 0L
        if (clearSuppression) {
            suppressedUntilLifted = false
        }
    }

    private fun defaultProximity(): Proximity =
        if (hasOpticalProximity) Proximity.AwaitingFirstSample else Proximity.NoOpticalSensor

    private fun isProximityPermitting(observation: Observation): Boolean {
        if (!hasOpticalProximity) return true
        return observation.proximity !is Proximity.Far
    }

    private fun isProximityPermittingAt(): Boolean {
        if (!hasOpticalProximity) return true
        return lastProximity !is Proximity.Far
    }

    private fun decision(
        enterDnd: Boolean = false,
        exitDnd: Boolean = false,
        acquireWakeLockMs: Long? = null,
        releaseWakeLock: Boolean = false,
        scheduleCheckAtMs: Long? = null,
        cancelScheduledCheck: Boolean = false,
        resetStillness: Boolean = false,
        blockedByProximity: Boolean = false,
    ): Decision = Decision(
        phase = phase,
        pendingTarget = pendingTarget,
        useFastSensors = phase == Phase.DOWN_PENDING,
        enterDnd = enterDnd,
        exitDnd = exitDnd,
        acquireWakeLockMs = acquireWakeLockMs,
        releaseWakeLock = releaseWakeLock,
        scheduleCheckAtMs = scheduleCheckAtMs,
        cancelScheduledCheck = cancelScheduledCheck,
        resetStillness = resetStillness,
        retryCount = stillnessRetryCount,
        blockedByProximity = blockedByProximity,
    )

    companion object {
        const val WAKE_LOCK_MARGIN_MS = 1_000L
    }
}
