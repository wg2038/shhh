package io.github.wg2038.shhh

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/**
 * Foreground service that bridges Android sensors, wake locks, and Do Not Disturb
 * policy APIs to [FlipStateMachine] and [StillnessDetector].
 */
class ShhhService : LifecycleService(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var notifManager: NotificationManager
    private lateinit var powerManager: PowerManager
    private lateinit var audioManager: AudioManager
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var prefs: SharedPreferences

    private lateinit var sensorThread: HandlerThread
    private lateinit var sensorHandler: Handler

    private var gravitySensor: Sensor? = null
    private var accelerometerSensor: Sensor? = null
    private var gyroscopeSensor: Sensor? = null
    private var proximitySensor: Sensor? = null
    private var activeOrientationSensor: Sensor? = null
    private var hasOpticalProximity: Boolean = false

    private lateinit var stillnessDetector: StillnessDetector
    private lateinit var flipStateMachine: FlipStateMachine

    private var currentXValue: Float = 0f
    private var currentYValue: Float = 0f
    private var currentZValue: Float = 0f

    private var isProximityNear: Boolean = false
    private var hasOrientationSample: Boolean = false
    private var hasProximitySample: Boolean = false

    @Volatile
    private var wasDndActivatedByService: Boolean = false

    private enum class SensorMode {
        STOPPED,
        IDLE_LISTENING,
        FAST_SAMPLING,
        FLIPPED_LOW_POWER,
        EXIT_VALIDATION,
    }

    private var sensorMode = SensorMode.STOPPED
    private var exitValidationActive = false
    private var pendingAutoLock = false

    private val sensorLock = Any()

    @Volatile
    private var shuttingDown = false

    private val returnToLowPowerRunnable: Runnable = Runnable {
        if (!this::flipStateMachine.isInitialized || shuttingDown) return@Runnable
        if (flipStateMachine.phase == FlipStateMachine.Phase.FLIPPED) {
            if (flipStateMachine.pendingTarget == FlipStateMachine.Target.NONE) {
                exitValidationActive = false
                applySensorMode(SensorMode.FLIPPED_LOW_POWER)
                if (!pendingAutoLock) releaseWakeLock()
            } else {
                sensorHandler.postDelayed(returnToLowPowerRunnable, 500L)
            }
        } else {
            exitValidationActive = false
            if (!pendingAutoLock) releaseWakeLock()
        }
    }

    private val autoLockRunnable = Runnable {
        pendingAutoLock = false
        try {
            if (!shuttingDown &&
                this::flipStateMachine.isInitialized &&
                flipStateMachine.phase == FlipStateMachine.Phase.FLIPPED &&
                flipStateMachine.pendingTarget == FlipStateMachine.Target.NONE &&
                _isFlippedDown.value &&
                powerManager.isInteractive
            ) {
                ShhhLockAccessibilityService.performLock()
            }
        } finally {
            if (!shuttingDown &&
                this::flipStateMachine.isInitialized &&
                flipStateMachine.phase == FlipStateMachine.Phase.FLIPPED &&
                flipStateMachine.pendingTarget == FlipStateMachine.Target.NONE &&
                !exitValidationActive
            ) {
                releaseWakeLock()
            }
        }
    }

    private val debounceRunnable = Runnable {
        if (!this::flipStateMachine.isInitialized || shuttingDown) return@Runnable
        val now = SystemClock.elapsedRealtime()
        val stillness = if (flipStateMachine.phase == FlipStateMachine.Phase.DOWN_PENDING) {
            stillnessDetector.result()
        } else {
            null
        }
        applyDecision(flipStateMachine.onTimer(now, stillness))
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock(timeoutMs: Long = 3_000L) {
        try {
            wakeLock?.acquire(timeoutMs.coerceAtLeast(1L))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire wake lock", e)
            releaseWakeLock()
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release wake lock", e)
        }
    }

    private fun isInActiveCall(): Boolean {
        return try {
            val mode = audioManager.mode
            mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
        } catch (_: Exception) {
            false
        }
    }

    private val dndReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (shuttingDown) return
            if (intent?.action != NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED) {
                return
            }

            if (!notifManager.isNotificationPolicyAccessGranted) {
                sensorHandler.post {
                    if (shuttingDown || !this@ShhhService::flipStateMachine.isInitialized) {
                        return@post
                    }
                    _isDndActive.value = false
                    _isFlippedDown.value = false
                    wasDndActivatedByService = false
                    exitValidationActive = false
                    pendingAutoLock = false
                    sensorHandler.removeCallbacks(debounceRunnable)
                    sensorHandler.removeCallbacks(returnToLowPowerRunnable)
                    sensorHandler.removeCallbacks(autoLockRunnable)
                    persistState(active = false)
                    updateNotification(active = false)
                    flipStateMachine.reset(startFlipped = false)
                    applySensorMode(SensorMode.STOPPED, force = true)
                    releaseWakeLock()
                    stopSelf()
                }
                return
            }

            val currentFilter = notifManager.currentInterruptionFilter
            if (currentFilter == NotificationManager.INTERRUPTION_FILTER_ALL && _isDndActive.value) {
                sensorHandler.post {
                    if (shuttingDown ||
                        !this@ShhhService::flipStateMachine.isInitialized ||
                        !_isDndActive.value ||
                        notifManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
                    ) {
                        return@post
                    }
                    _isDndActive.value = false
                    _isFlippedDown.value = false
                    wasDndActivatedByService = false
                    exitValidationActive = false
                    pendingAutoLock = false
                    persistState(active = false)
                    updateNotification(active = false)
                    applyDecision(flipStateMachine.onExternalDndDisabled(SystemClock.elapsedRealtime()))
                }
            }
        }
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (shuttingDown || intent?.action != Intent.ACTION_SCREEN_ON) return
            sensorHandler.post {
                if (shuttingDown || !this@ShhhService::flipStateMachine.isInitialized) return@post
                if (flipStateMachine.phase == FlipStateMachine.Phase.FLIPPED &&
                    sensorMode != SensorMode.EXIT_VALIDATION
                ) {
                    onLiftSignal()
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        createNotificationChannel()
        updateNotification(active = _isDndActive.value)
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "shhh:DebounceWakeLock").apply {
            setReferenceCounted(false)
        }
        vibrator = (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)

        sensorThread = HandlerThread("shhh-sensors").apply { start() }
        sensorHandler = Handler(sensorThread.looper)

        val dndFilter = IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        registerReceiver(dndReceiver, dndFilter, Context.RECEIVER_NOT_EXPORTED)

        val screenFilter = IntentFilter(Intent.ACTION_SCREEN_ON)
        registerReceiver(screenStateReceiver, screenFilter, Context.RECEIVER_NOT_EXPORTED)

        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY, false)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY, true)
        accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, false)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
        gyroscopeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE, false)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE, true)
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY, true)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY, false)
        activeOrientationSensor = gravitySensor ?: accelerometerSensor

        hasOpticalProximity = isHardwareOpticalProximity(proximitySensor)

        stillnessDetector = StillnessDetector(hasGyroscope = gyroscopeSensor != null)
        flipStateMachine = FlipStateMachine(
            hasGyroscope = gyroscopeSensor != null,
            hasOpticalProximity = hasOpticalProximity,
        )

        if (prefs.getBoolean(PrefsKeys.KEY_DND_ACTIVE, false)) {
            val systemDndStillOn = notifManager.isNotificationPolicyAccessGranted &&
                    notifManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
            if (systemDndStillOn) {
                wasDndActivatedByService = prefs.getBoolean(PrefsKeys.KEY_WAS_DND_ACTIVATED_BY_SERVICE, false)
                _isDndActive.value = true
                _isFlippedDown.value = true
            } else {
                persistState(active = false)
            }
        }

        createNotificationChannel()
    }

    private fun isHardwareOpticalProximity(sensor: Sensor?): Boolean {
        sensor ?: return false
        val info = "${sensor.name} ${sensor.vendor}".lowercase()
        val virtualMarkers = listOf(
            "palm", "touch", "virtual", "ultrason", "elliptic",
            "pseudo", "fake", "software", "simulated", "pocket", "hover", "tp_prox", "algo",
        )
        return virtualMarkers.none { info.contains(it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        startForeground(NOTIFICATION_ID, buildNotification(active = _isDndActive.value), fgsType)

        if (!_isRunning.value) {
            _isRunning.value = true
            sensorHandler.removeCallbacksAndMessages(null)
            sensorHandler.post { startDetection() }
        }
        return START_STICKY
    }

    private fun startDetection() {
        hasOrientationSample = false
        hasProximitySample = false
        isProximityNear = false
        exitValidationActive = false
        pendingAutoLock = false
        currentXValue = 0f
        currentYValue = 0f
        currentZValue = 0f
        stillnessDetector.reset()
        flipStateMachine.reset(startFlipped = _isFlippedDown.value)
        releaseWakeLock()
        val initialMode = if (flipStateMachine.phase == FlipStateMachine.Phase.FLIPPED) {
            SensorMode.FLIPPED_LOW_POWER
        } else {
            SensorMode.IDLE_LISTENING
        }
        applySensorMode(initialMode, force = true)
    }

    override fun onDestroy() {
        shuttingDown = true
        try {
            unregisterReceiver(dndReceiver)
        } catch (_: Exception) {}
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (_: Exception) {}

        if (this::sensorManager.isInitialized) {
            synchronized(sensorLock) {
                sensorManager.unregisterListener(this)
            }
        }
        if (this::sensorHandler.isInitialized) {
            sensorHandler.removeCallbacksAndMessages(null)
        }
        if (this::sensorThread.isInitialized) {
            sensorThread.quitSafely()
            try {
                sensorThread.join(1_000L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        restoreDndIfNeeded()
        sensorMode = SensorMode.STOPPED
        exitValidationActive = false
        pendingAutoLock = false
        releaseWakeLock()
        wakeLock = null
        hasOrientationSample = false
        hasProximitySample = false
        _isRunning.value = false
        _isFlippedDown.value = false
        _isDndActive.value = false
        super.onDestroy()
    }

    private fun applySensorMode(mode: SensorMode, force: Boolean = false) {
        synchronized(sensorLock) {
            if (shuttingDown) return
            if (!force && sensorMode == mode) return
            sensorManager.unregisterListener(this)
            sensorMode = mode

            when (mode) {
                SensorMode.STOPPED -> Unit

                SensorMode.IDLE_LISTENING -> {
                    // Keep proximity off while idle to avoid under-display IR blinking and wake-ups.
                    hasProximitySample = false
                    isProximityNear = false
                    registerOrientation(SensorManager.SENSOR_DELAY_UI, IDLE_BATCH_LATENCY_US)
                }

                SensorMode.FAST_SAMPLING -> {
                    hasProximitySample = false
                    isProximityNear = false
                    registerOrientation(SensorManager.SENSOR_DELAY_GAME, 0)
                    if (accelerometerSensor != null &&
                        accelerometerSensor?.type != activeOrientationSensor?.type
                    ) {
                        sensorManager.registerListener(
                            this,
                            accelerometerSensor,
                            SensorManager.SENSOR_DELAY_GAME,
                            0,
                            sensorHandler,
                        )
                    }
                    gyroscopeSensor?.let { gyro ->
                        sensorManager.registerListener(
                            this,
                            gyro,
                            SensorManager.SENSOR_DELAY_GAME,
                            0,
                            sensorHandler,
                        )
                    }
                    if (hasOpticalProximity) {
                        registerProximity()
                    }
                }

                SensorMode.FLIPPED_LOW_POWER -> {
                    if (hasOpticalProximity) {
                        registerProximity()
                    }
                    registerOrientation(SensorManager.SENSOR_DELAY_UI, IDLE_BATCH_LATENCY_US)
                }

                SensorMode.EXIT_VALIDATION -> {
                    registerOrientation(SensorManager.SENSOR_DELAY_UI, 0)
                    if (hasOpticalProximity) {
                        registerProximity()
                    }
                }
            }
        }
    }

    private fun registerOrientation(samplingPeriodUs: Int, maxReportLatencyUs: Int) {
        val sensor = activeOrientationSensor ?: return
        val registered = sensorManager.registerListener(
            this,
            sensor,
            samplingPeriodUs,
            maxReportLatencyUs,
            sensorHandler,
        )
        if (!registered && maxReportLatencyUs > 0) {
            sensorManager.registerListener(this, sensor, samplingPeriodUs, sensorHandler)
        }
    }

    private fun registerProximity() {
        proximitySensor?.let { prox ->
            val registered = sensorManager.registerListener(
                this,
                prox,
                SensorManager.SENSOR_DELAY_NORMAL,
                0,
                sensorHandler,
            )
            if (!registered) {
                sensorManager.registerListener(this, prox, SensorManager.SENSOR_DELAY_NORMAL, sensorHandler)
            }
        }
    }

    private fun onLiftSignal() {
        if (!this::flipStateMachine.isInitialized || shuttingDown) return
        if (flipStateMachine.phase != FlipStateMachine.Phase.FLIPPED) return
        exitValidationActive = true
        acquireWakeLock(EXIT_VALIDATION_TIMEOUT_MS + 500L)
        sensorHandler.removeCallbacks(returnToLowPowerRunnable)
        if (sensorMode != SensorMode.EXIT_VALIDATION) {
            applySensorMode(SensorMode.EXIT_VALIDATION)
        }
        sensorHandler.postDelayed(returnToLowPowerRunnable, EXIT_VALIDATION_TIMEOUT_MS)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        if (shuttingDown) return
        val now = SystemClock.elapsedRealtime()

        when (event.sensor.type) {
            Sensor.TYPE_PROXIMITY -> {
                val distance = event.values[0]
                val maxRange = proximitySensor?.maximumRange?.takeIf { it > 0f } ?: 5.0f
                hasProximitySample = true
                isProximityNear = if (maxRange <= 4.0f) distance < maxRange else distance <= 4.0f

                if (hasOpticalProximity && !isProximityNear &&
                    flipStateMachine.phase == FlipStateMachine.Phase.FLIPPED
                ) {
                    onLiftSignal()
                }
                updateDetection(now)
            }

            Sensor.TYPE_GYROSCOPE -> {
                val gx = event.values[0]
                val gy = event.values[1]
                val gz = event.values[2]

                if (flipStateMachine.phase == FlipStateMachine.Phase.DOWN_PENDING &&
                    stillnessDetector.isWarmedUp(now)
                ) {
                    val magnitude = sqrt(gx * gx + gy * gy + gz * gz)
                    if (magnitude > MAX_GYRO_DISTURBANCE) {
                        flipStateMachine.onMotionDisturbance(now)?.let { applyDecision(it) }
                    }
                }
                stillnessDetector.addGyroscope(gx, gy, gz, now)
            }

            Sensor.TYPE_ACCELEROMETER -> {
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]
                stillnessDetector.addAccelerometer(ax, ay, az, now)
                if (activeOrientationSensor?.type == Sensor.TYPE_ACCELEROMETER) {
                    updateOrientation(ax, ay, az, now)
                }
            }

            Sensor.TYPE_GRAVITY -> {
                if (activeOrientationSensor?.type == Sensor.TYPE_GRAVITY) {
                    updateOrientation(event.values[0], event.values[1], event.values[2], now)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun updateOrientation(x: Float, y: Float, z: Float, now: Long) {
        hasOrientationSample = true
        currentXValue = x
        currentYValue = y
        currentZValue = z
        updateDetection(now)
    }

    private fun updateDetection(now: Long) {
        if (flipStateMachine.phase == FlipStateMachine.Phase.IDLE && isInActiveCall()) {
            return
        }
        applyDecision(flipStateMachine.onObservation(buildObservation(now)))
    }

    private fun buildObservation(now: Long): FlipStateMachine.Observation {
        val hMag = sqrt(currentXValue * currentXValue + currentYValue * currentYValue)
        val proximity = when {
            !hasOpticalProximity -> FlipStateMachine.Proximity.NoOpticalSensor
            !hasProximitySample -> FlipStateMachine.Proximity.AwaitingFirstSample
            isProximityNear -> FlipStateMachine.Proximity.Near
            else -> FlipStateMachine.Proximity.Far
        }
        return FlipStateMachine.Observation(
            timestampMs = now,
            hasOrientationSample = hasOrientationSample,
            gravityZ = currentZValue,
            horizontalGravity = hMag,
            proximity = proximity,
        )
    }

    private fun applyDecision(decision: FlipStateMachine.Decision) {
        if (shuttingDown) return
        val now = SystemClock.elapsedRealtime()

        if (decision.cancelScheduledCheck) {
            sensorHandler.removeCallbacks(debounceRunnable)
        }
        if (decision.resetStillness) {
            stillnessDetector.reset(now)
        }

        if (decision.acquireWakeLockMs != null) {
            acquireWakeLock(decision.acquireWakeLockMs)
        }

        if (decision.phase != FlipStateMachine.Phase.FLIPPED) {
            exitValidationActive = false
            pendingAutoLock = false
            sensorHandler.removeCallbacks(returnToLowPowerRunnable)
            sensorHandler.removeCallbacks(autoLockRunnable)
        }

        if (decision.scheduleCheckAtMs != null) {
            sensorHandler.removeCallbacks(debounceRunnable)
            val delay = (decision.scheduleCheckAtMs - now).coerceAtLeast(0L)
            sensorHandler.postDelayed(debounceRunnable, delay)
        }

        val targetMode = when {
            decision.useFastSensors -> SensorMode.FAST_SAMPLING
            decision.phase == FlipStateMachine.Phase.FLIPPED &&
                (decision.pendingTarget == FlipStateMachine.Target.UP || exitValidationActive) -> {
                SensorMode.EXIT_VALIDATION
            }
            decision.phase == FlipStateMachine.Phase.FLIPPED -> SensorMode.FLIPPED_LOW_POWER
            else -> SensorMode.IDLE_LISTENING
        }
        applySensorMode(targetMode)

        var deferWakeLockRelease = false

        if (decision.enterDnd) {
            _isFlippedDown.value = true
            when (enableDoNotDisturb()) {
                EnableDndResult.FAILED -> {
                    sensorHandler.removeCallbacks(autoLockRunnable)
                    pendingAutoLock = false
                    wasDndActivatedByService = false
                    persistState(active = false)
                    _isFlippedDown.value = false
                    _isDndActive.value = false
                    flipStateMachine.reset(startFlipped = false)
                    applySensorMode(SensorMode.IDLE_LISTENING)
                }
                EnableDndResult.STOPPING -> return
                EnableDndResult.ENABLED_WITH_PENDING_LOCK -> deferWakeLockRelease = true
                EnableDndResult.ENABLED -> Unit
            }
        }
        if (decision.exitDnd) {
            _isFlippedDown.value = false
            disableDoNotDisturb()
        }

        if (decision.releaseWakeLock && !deferWakeLockRelease && !exitValidationActive && !pendingAutoLock) {
            releaseWakeLock()
        }
    }

    private enum class EnableDndResult {
        ENABLED,
        ENABLED_WITH_PENDING_LOCK,
        FAILED,
        STOPPING,
    }

    private fun enableDoNotDisturb(): EnableDndResult {
        if (!notifManager.isNotificationPolicyAccessGranted) {
            _isFlippedDown.value = false
            _isDndActive.value = false
            wasDndActivatedByService = false
            exitValidationActive = false
            pendingAutoLock = false
            sensorHandler.removeCallbacks(debounceRunnable)
            sensorHandler.removeCallbacks(returnToLowPowerRunnable)
            sensorHandler.removeCallbacks(autoLockRunnable)
            persistState(active = false)
            updateNotification(active = false)
            flipStateMachine.reset(startFlipped = false)
            applySensorMode(SensorMode.STOPPED, force = true)
            releaseWakeLock()
            stopSelf()
            return EnableDndResult.STOPPING
        }
        try {
            // Only turn DND on if it was not already active so manual/scheduled DND is preserved.
            val currentFilter = notifManager.currentInterruptionFilter
            if (currentFilter == NotificationManager.INTERRUPTION_FILTER_ALL) {
                notifManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                wasDndActivatedByService = true
            } else {
                wasDndActivatedByService = false
            }

            persistState(active = true)
            _isDndActive.value = true
            updateNotification(active = true)
            triggerFlipDownHaptic()

            val shouldAutoLock = try {
                val autoLockPref = prefs.getBoolean(PrefsKeys.KEY_AUTO_LOCK_SCREEN, false)
                val accessibilityEnabled = ShhhLockAccessibilityService.isAccessibilityServiceEnabled(this)
                autoLockPref && accessibilityEnabled && powerManager.isInteractive
            } catch (e: Exception) {
                Log.w(TAG, "Failed to evaluate auto-lock state", e)
                false
            }

            if (shouldAutoLock) {
                acquireWakeLock(500L)
                sensorHandler.removeCallbacks(autoLockRunnable)
                pendingAutoLock = true
                sensorHandler.postDelayed(autoLockRunnable, 150L)
                return EnableDndResult.ENABLED_WITH_PENDING_LOCK
            }
            return EnableDndResult.ENABLED
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling DND", e)
            return EnableDndResult.FAILED
        }
    }

    private fun disableDoNotDisturb() {
        try {
            if (_isDndActive.value) {
                if (notifManager.isNotificationPolicyAccessGranted && wasDndActivatedByService) {
                    if (notifManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
                        notifManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                    }
                }

                wasDndActivatedByService = false
                persistState(active = false)
                _isDndActive.value = false
                updateNotification(active = false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error disabling DND", e)
        }
        triggerFlipUpHaptic()
    }

    private fun restoreDndIfNeeded() {
        if (_isDndActive.value) {
            try {
                if (notifManager.isNotificationPolicyAccessGranted &&
                    wasDndActivatedByService &&
                    notifManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
                ) {
                    notifManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                }
                wasDndActivatedByService = false
                persistState(active = false)
                _isDndActive.value = false
            } catch (e: Exception) {
                Log.e(TAG, "Error restoring DND state", e)
            }
        }
    }

    private fun persistState(active: Boolean) {
        prefs.edit()
            .putBoolean(PrefsKeys.KEY_DND_ACTIVE, active)
            .putBoolean(PrefsKeys.KEY_WAS_DND_ACTIVATED_BY_SERVICE, wasDndActivatedByService)
            .apply()
    }

    private fun triggerFlipDownHaptic() {
        try {
            val vib = vibrator ?: return
            if (!vib.hasVibrator()) return
            val vibAttrs = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK)

            if (vib.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_THUD)) {
                val composition = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1.0f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1.0f, 65)
                    .compose()
                vib.vibrate(composition, vibAttrs)
            } else {
                val timings = longArrayOf(0, 28, 65, 40)
                if (vib.hasAmplitudeControl()) {
                    val amplitudes = intArrayOf(0, 255, 0, 255)
                    vib.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1), vibAttrs)
                } else {
                    vib.vibrate(VibrationEffect.createWaveform(timings, -1), vibAttrs)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to trigger flip-down haptic", e)
        }
    }

    private fun triggerFlipUpHaptic() {
        try {
            val vib = vibrator ?: return
            if (!vib.hasVibrator()) return
            val vibAttrs = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK)

            if (vib.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)) {
                val composition = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f)
                    .compose()
                vib.vibrate(composition, vibAttrs)
            } else {
                val timings = longArrayOf(0, 22)
                if (vib.hasAmplitudeControl()) {
                    val amplitudes = intArrayOf(0, 200)
                    vib.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1), vibAttrs)
                } else {
                    vib.vibrate(VibrationEffect.createWaveform(timings, -1), vibAttrs)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to trigger flip-up haptic", e)
        }
    }

    private fun createNotificationChannel() {
        val name = getString(R.string.channel_name)
        val desc = getString(R.string.channel_desc)
        val channel = NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_MIN).apply {
            description = desc
            setShowBadge(false)
        }
        notifManager.createNotificationChannel(channel)
    }

    private fun updateNotification(active: Boolean) {
        try {
            if (notifManager.areNotificationsEnabled()) {
                notifManager.notify(NOTIFICATION_ID, buildNotification(active))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update notification", e)
        }
    }

    private fun buildNotification(active: Boolean): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_shhh)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (active) {
            builder
                .setContentTitle(getString(R.string.notif_active_title))
                .setContentText(getString(R.string.notif_active_desc))
        } else {
            builder
                .setContentTitle(getString(R.string.notif_idle_title))
                .setContentText(getString(R.string.notif_idle_desc))
        }

        return builder.build()
    }

    companion object {
        const val CHANNEL_ID = "shhh_service_channel"
        const val NOTIFICATION_ID = 1001
        private const val TAG = "ShhhService"

        private const val MAX_GYRO_DISTURBANCE = 0.50f
        private const val IDLE_BATCH_LATENCY_US = 500_000
        private const val EXIT_VALIDATION_TIMEOUT_MS = 1_000L

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _isFlippedDown = MutableStateFlow(false)
        val isFlippedDown: StateFlow<Boolean> = _isFlippedDown.asStateFlow()

        private val _isDndActive = MutableStateFlow(false)
        val isDndActive: StateFlow<Boolean> = _isDndActive.asStateFlow()
    }
}
