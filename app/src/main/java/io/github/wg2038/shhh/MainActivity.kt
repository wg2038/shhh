package io.github.wg2038.shhh

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ColorScheme
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
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.wg2038.shhh.ui.screens.HomeScreen
import io.github.wg2038.shhh.ui.screens.OnboardingScreen
import io.github.wg2038.shhh.ui.theme.ExpressiveMotionTokens
import io.github.wg2038.shhh.ui.theme.ExpressiveShapeTokens
import io.github.wg2038.shhh.ui.theme.ExpressiveTypographyTokens
import io.github.wg2038.shhh.ui.theme.MonetThemeResolver
import io.github.wg2038.shhh.ui.theme.ShhhTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class StartupState(
    val onboardingComplete: Boolean,
    val hasDndPermission: Boolean,
    val isIgnoringBatteryOptimization: Boolean,
    val hasAccessibilityPermission: Boolean,
    val notifPermissionAsked: Boolean,
    val autoStartEnabled: Boolean,
    val autoLockPref: Boolean,
    val showToolbarHint: Boolean,
    val colorScheme: ColorScheme,
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val context = LocalContext.current
            val initialDark = isSystemInDarkTheme()
            var startupState by remember { mutableStateOf<StartupState?>(null) }

            LaunchedEffect(Unit) {
                startupState = withContext(Dispatchers.IO) {
                    val prefs = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
                    val access = ShhhLockAccessibilityService.isAccessibilityServiceEnabled(context)
                    if (prefs.getBoolean(PrefsKeys.KEY_PENDING_AUTO_LOCK_AUTH, false)) {
                        prefs.edit {
                            if (access) {
                                putBoolean(PrefsKeys.KEY_AUTO_LOCK_SCREEN, true)
                            }
                            putBoolean(PrefsKeys.KEY_PENDING_AUTO_LOCK_AUTH, false)
                        }
                    }
                    StartupState(
                        onboardingComplete = prefs.getBoolean(PrefsKeys.KEY_ONBOARDING_COMPLETE, false),
                        hasDndPermission = checkDndPermission(context),
                        isIgnoringBatteryOptimization = checkBatteryOptimization(context),
                        hasAccessibilityPermission = access,
                        notifPermissionAsked = prefs.getBoolean(PrefsKeys.KEY_NOTIF_PERMISSION_ASKED, false),
                        autoStartEnabled = prefs.getBoolean(PrefsKeys.KEY_AUTO_START_BOOT, true),
                        autoLockPref = prefs.getBoolean(PrefsKeys.KEY_AUTO_LOCK_SCREEN, false),
                        showToolbarHint = !prefs.getBoolean(PrefsKeys.KEY_TOOLBAR_HINT_SEEN, false),
                        colorScheme = MonetThemeResolver.resolveColorScheme(context, initialDark),
                    )
                }
            }

            startupState?.let { loaded ->
                MainUiRoot(initialState = loaded)
            }
        }
    }
}

@Composable
private fun MainUiRoot(initialState: StartupState) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val prefs = remember {
        context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
    }

    var resumeTrigger by remember { mutableIntStateOf(0) }
    var permissionSyncSeq by remember { mutableIntStateOf(0) }

    ShhhTheme(
        updateKey = resumeTrigger,
        initialColorScheme = initialState.colorScheme,
    ) {
        var onboardingComplete by remember { mutableStateOf(initialState.onboardingComplete) }
        var hasDndPermission by remember { mutableStateOf(initialState.hasDndPermission) }
        var isIgnoringBatteryOptimization by remember { mutableStateOf(initialState.isIgnoringBatteryOptimization) }
        var hasAccessibilityPermission by remember { mutableStateOf(initialState.hasAccessibilityPermission) }

        val refreshPermissionsAndSyncService: suspend () -> Unit = remember(context) {
            {
                val (dnd, batt, access, isUserEnabled) = withContext(Dispatchers.IO) {
                    val accessNow = ShhhLockAccessibilityService.isAccessibilityServiceEnabled(context)
                    if (prefs.getBoolean(PrefsKeys.KEY_PENDING_AUTO_LOCK_AUTH, false)) {
                        prefs.edit {
                            if (accessNow) {
                                putBoolean(PrefsKeys.KEY_AUTO_LOCK_SCREEN, true)
                            }
                            putBoolean(PrefsKeys.KEY_PENDING_AUTO_LOCK_AUTH, false)
                        }
                    }
                    Quadruple(
                        checkDndPermission(context),
                        checkBatteryOptimization(context),
                        accessNow,
                        prefs.getBoolean(PrefsKeys.KEY_SERVICE_USER_ENABLED, true),
                    )
                }
                hasDndPermission = dnd
                isIgnoringBatteryOptimization = batt
                hasAccessibilityPermission = access
                permissionSyncSeq++

                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    if (onboardingComplete && isUserEnabled && dnd && !ShhhService.isRunning.value) {
                        startShhhService(context)
                    } else if (!dnd && ShhhService.isRunning.value) {
                        stopShhhService(context)
                    }
                }
            }
        }

        LaunchedEffect(Unit) {
            refreshPermissionsAndSyncService()
        }

        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    resumeTrigger++
                    coroutineScope.launch {
                        refreshPermissionsAndSyncService()
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

                        coroutineScope.launch {
                            val dnd = withContext(Dispatchers.IO) { checkDndPermission(context) }
                            hasDndPermission = dnd
                            if (dnd && !ShhhService.isRunning.value) {
                                startShhhService(context)
                            }
                        }
                    }
                )
            } else {
                MainAppContent(
                    hasDndPermission = hasDndPermission,
                    hasAccessibilityPermission = hasAccessibilityPermission,
                    permissionSyncSeq = permissionSyncSeq,
                    initialAutoStartEnabled = initialState.autoStartEnabled,
                    initialAutoLockPref = initialState.autoLockPref,
                    initialShowToolbarHint = initialState.showToolbarHint,
                    initialNotifPermissionAsked = initialState.notifPermissionAsked,
                    onOpenDndSettings = { openDndPermissionSettings(context) },
                    onOpenAccessibilitySettings = { openAccessibilitySettings(context) }
                )
            }
        }
    }
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
)

@Composable
private fun MainAppContent(
    hasDndPermission: Boolean,
    hasAccessibilityPermission: Boolean,
    permissionSyncSeq: Int,
    initialAutoStartEnabled: Boolean,
    initialAutoLockPref: Boolean,
    initialShowToolbarHint: Boolean,
    initialNotifPermissionAsked: Boolean,
    onOpenDndSettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE) }

    val isServiceRunning by ShhhService.isRunning.collectAsState()
    val isFlippedDown by ShhhService.isFlippedDown.collectAsState()
    val isDndActive by ShhhService.isDndActive.collectAsState()

    var autoStartEnabled by remember { mutableStateOf(initialAutoStartEnabled) }
    var autoLockPref by remember { mutableStateOf(initialAutoLockPref) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var showAccessibilityDialog by remember { mutableStateOf(false) }
    var showToolbarHint by remember { mutableStateOf(initialShowToolbarHint) }
    var notifPermissionAsked by remember { mutableStateOf(initialNotifPermissionAsked) }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        notifPermissionAsked = true
        prefs.edit { putBoolean(PrefsKeys.KEY_NOTIF_PERMISSION_ASKED, true) }
    }

    LaunchedEffect(notifPermissionAsked) {
        if (!notifPermissionAsked) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            notifPermissionAsked = true
            prefs.edit { putBoolean(PrefsKeys.KEY_NOTIF_PERMISSION_ASKED, true) }
            if (!granted) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LaunchedEffect(hasAccessibilityPermission, permissionSyncSeq) {
        val (autoStart, autoLock) = withContext(Dispatchers.IO) {
            prefs.getBoolean(PrefsKeys.KEY_AUTO_START_BOOT, true) to
                prefs.getBoolean(PrefsKeys.KEY_AUTO_LOCK_SCREEN, false)
        }
        autoStartEnabled = autoStart
        autoLockPref = autoLock
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

    if (showAccessibilityDialog) {
        AlertDialog(
            onDismissRequest = { showAccessibilityDialog = false },
            shape = ExpressiveShapeTokens.ExtraLarge,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text(
                    text = stringResource(R.string.perm_accessibility_title),
                    style = ExpressiveTypographyTokens.ItemTitle
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.perm_accessibility_message),
                    style = ExpressiveTypographyTokens.ItemSubtitle
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showAccessibilityDialog = false
                    prefs.edit { putBoolean(PrefsKeys.KEY_PENDING_AUTO_LOCK_AUTH, true) }
                    onOpenAccessibilitySettings()
                }) {
                    Text(
                        text = stringResource(R.string.btn_open_settings),
                        style = ExpressiveTypographyTokens.ButtonText
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showAccessibilityDialog = false }) {
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
                    showAccessibilityDialog = true
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

// Persistent foreground sensor monitoring requires unrestricted battery access; distributed directly via GitHub Releases.
@SuppressLint("BatteryLife")
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
