package io.github.wg2038.shhh

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.wg2038.shhh.ui.screens.HomeScreen
import io.github.wg2038.shhh.ui.screens.OnboardingScreen
import io.github.wg2038.shhh.ui.theme.ExpressiveMotionTokens
import io.github.wg2038.shhh.ui.theme.ExpressiveShapeTokens
import io.github.wg2038.shhh.ui.theme.ExpressiveTypographyTokens
import io.github.wg2038.shhh.ui.theme.ShhhTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val context = LocalContext.current
            val lifecycleOwner = LocalLifecycleOwner.current
            val coroutineScope = rememberCoroutineScope()
            val prefs = remember {
                context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
            }

            var resumeTrigger by remember { mutableIntStateOf(0) }
            var permissionSyncSeq by remember { mutableIntStateOf(0) }

            ShhhTheme(updateKey = resumeTrigger) {
                var onboardingComplete by remember {
                    mutableStateOf(prefs.getBoolean(PrefsKeys.KEY_ONBOARDING_COMPLETE, false))
                }

                var hasDndPermission by remember { mutableStateOf(checkDndPermission(context)) }
                var isIgnoringBatteryOptimization by remember { mutableStateOf(checkBatteryOptimization(context)) }
                var hasAccessibilityPermission by remember { mutableStateOf(ShhhLockAccessibilityService.isAccessibilityServiceEnabled(context)) }

                val refreshPermissions: suspend () -> Unit = remember(context) {
                    {
                        val dnd = withContext(Dispatchers.IO) { checkDndPermission(context) }
                        val batt = withContext(Dispatchers.IO) { checkBatteryOptimization(context) }
                        val access = withContext(Dispatchers.IO) { ShhhLockAccessibilityService.isAccessibilityServiceEnabled(context) }
                        hasDndPermission = dnd
                        isIgnoringBatteryOptimization = batt
                        hasAccessibilityPermission = access

                        if (prefs.getBoolean(PrefsKeys.KEY_PENDING_AUTO_LOCK_AUTH, false)) {
                            prefs.edit {
                                if (access) {
                                    putBoolean(PrefsKeys.KEY_AUTO_LOCK_SCREEN, true)
                                }
                                putBoolean(PrefsKeys.KEY_PENDING_AUTO_LOCK_AUTH, false)
                            }
                        }
                        permissionSyncSeq++
                    }
                }

                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            resumeTrigger++
                            coroutineScope.launch {
                                refreshPermissions()
                                if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                                    return@launch
                                }
                                val dndGranted = hasDndPermission
                                val isUserEnabled = prefs.getBoolean(PrefsKeys.KEY_SERVICE_USER_ENABLED, true)
                                if (onboardingComplete && isUserEnabled && dndGranted && !ShhhService.isRunning.value) {
                                    startShhhService(context)
                                } else if (!dndGranted && ShhhService.isRunning.value) {
                                    stopShhhService(context)
                                }
                            }
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                AnimatedContent(
                    targetState = onboardingComplete,
                    label = "onboarding_transition",
                    transitionSpec = {
                        fadeIn(ExpressiveMotionTokens.FadeIn) togetherWith fadeOut(ExpressiveMotionTokens.FadeOut)
                    }
                ) { isDone ->
                    if (!isDone) {
                        OnboardingScreen(
                            hasDndPermission = hasDndPermission,
                            isIgnoringBatteryOptimization = isIgnoringBatteryOptimization,
                            onGrantDnd = { openDndPermissionSettings(context) },
                            onRequestBatteryOptimization = { requestIgnoreBatteryOptimization(context) },
                            onComplete = {
                                prefs.edit {
                                    putBoolean(PrefsKeys.KEY_ONBOARDING_COMPLETE, true)
                                    putBoolean(PrefsKeys.KEY_SERVICE_USER_ENABLED, true)
                                }
                                onboardingComplete = true

                                if (checkDndPermission(context) && !ShhhService.isRunning.value) {
                                    startShhhService(context)
                                }
                            }
                        )
                    } else {
                        MainAppContent(
                            hasDndPermission = hasDndPermission,
                            hasAccessibilityPermission = hasAccessibilityPermission,
                            permissionSyncSeq = permissionSyncSeq,
                            onOpenDndSettings = { openDndPermissionSettings(context) },
                            onOpenAccessibilitySettings = { openAccessibilitySettings(context) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MainAppContent(
    hasDndPermission: Boolean,
    hasAccessibilityPermission: Boolean,
    permissionSyncSeq: Int,
    onOpenDndSettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE) }

    val isServiceRunning by ShhhService.isRunning.collectAsState()
    val isFlippedDown by ShhhService.isFlippedDown.collectAsState()
    val isDndActive by ShhhService.isDndActive.collectAsState()

    var autoStartEnabled by remember { mutableStateOf(prefs.getBoolean(PrefsKeys.KEY_AUTO_START_BOOT, true)) }
    var autoLockPref by remember { mutableStateOf(prefs.getBoolean(PrefsKeys.KEY_AUTO_LOCK_SCREEN, false)) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var showToolbarHint by remember {
        mutableStateOf(!prefs.getBoolean(PrefsKeys.KEY_TOOLBAR_HINT_SEEN, false))
    }

    LaunchedEffect(hasAccessibilityPermission, permissionSyncSeq) {
        autoStartEnabled = prefs.getBoolean(PrefsKeys.KEY_AUTO_START_BOOT, true)
        autoLockPref = prefs.getBoolean(PrefsKeys.KEY_AUTO_LOCK_SCREEN, false)
    }

    val effectiveAutoLock = autoLockPref && hasAccessibilityPermission

    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            shape = ExpressiveShapeTokens.ExtraLarge,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text(
                    text = stringResource(R.string.perm_dnd_title),
                    style = ExpressiveTypographyTokens.ItemTitle
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.perm_dnd_required),
                    style = ExpressiveTypographyTokens.ItemSubtitle
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionDialog = false
                    onOpenDndSettings()
                }) {
                    Text(
                        text = stringResource(R.string.grant_btn),
                        style = ExpressiveTypographyTokens.ButtonText
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) {
                    Text(
                        text = stringResource(R.string.btn_cancel),
                        style = ExpressiveTypographyTokens.ButtonText
                    )
                }
            }
        )
    }

    HomeScreen(
        isServiceRunning = isServiceRunning,
        isFlippedDown = isFlippedDown,
        isDndActive = isDndActive,
        hasDndPermission = hasDndPermission,
        autoStartEnabled = autoStartEnabled,
        autoLockEnabled = effectiveAutoLock,
        showToolbarHint = showToolbarHint,
        onToggleService = {
            val isEffectiveRunning = isServiceRunning && hasDndPermission
            if (!isEffectiveRunning) {
                if (!hasDndPermission) {
                    showPermissionDialog = true
                } else {
                    prefs.edit { putBoolean(PrefsKeys.KEY_SERVICE_USER_ENABLED, true) }
                    startShhhService(context)
                }
            } else {
                prefs.edit { putBoolean(PrefsKeys.KEY_SERVICE_USER_ENABLED, false) }
                stopShhhService(context)
            }
        },
        onToggleAutoStart = { enabled ->
            autoStartEnabled = enabled
            prefs.edit { putBoolean(PrefsKeys.KEY_AUTO_START_BOOT, enabled) }
        },
        onToggleAutoLock = { wantEnabled ->
            if (wantEnabled) {
                if (hasAccessibilityPermission) {
                    autoLockPref = true
                    prefs.edit {
                        putBoolean(PrefsKeys.KEY_AUTO_LOCK_SCREEN, true)
                        putBoolean(PrefsKeys.KEY_PENDING_AUTO_LOCK_AUTH, false)
                    }
                } else {
                    prefs.edit { putBoolean(PrefsKeys.KEY_PENDING_AUTO_LOCK_AUTH, true) }
                    onOpenAccessibilitySettings()
                }
            } else {
                autoLockPref = false
                prefs.edit {
                    putBoolean(PrefsKeys.KEY_AUTO_LOCK_SCREEN, false)
                    putBoolean(PrefsKeys.KEY_PENDING_AUTO_LOCK_AUTH, false)
                }
            }
        },
        onDismissToolbarHint = {
            showToolbarHint = false
            prefs.edit { putBoolean(PrefsKeys.KEY_TOOLBAR_HINT_SEEN, true) }
        }
    )
}

private fun startShhhService(context: Context) {
    try {
        context.startForegroundService(Intent(context, ShhhService::class.java))
    } catch (e: Exception) {
        Log.w("MainActivity", "Failed to start ShhhService", e)
    }
}

private fun stopShhhService(context: Context) {
    context.stopService(Intent(context, ShhhService::class.java))
}

private fun checkDndPermission(context: Context): Boolean {
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    return nm.isNotificationPolicyAccessGranted
}

private fun openDndPermissionSettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    } catch (_: Exception) {
        openAppDetailsSettings(context)
    }
}

private fun checkBatteryOptimization(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun requestIgnoreBatteryOptimization(context: Context) {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:${context.packageName}".toUri()
            }
            context.startActivity(intent)
            return
        } catch (_: Exception) {
        }
    }

    if (!openAppDetailsSettings(context)) {
        try {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Exception) {
        }
    }
}

private fun openAccessibilitySettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    } catch (_: Exception) {
        openAppDetailsSettings(context)
    }
}

private fun openAppDetailsSettings(context: Context): Boolean {
    return try {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = "package:${context.packageName}".toUri()
        }
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }
}
