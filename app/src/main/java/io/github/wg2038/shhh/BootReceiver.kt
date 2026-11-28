package io.github.wg2038.shhh

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "Received broadcast intent: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val prefs = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
            val autoStartEnabled = prefs.getBoolean(PrefsKeys.KEY_AUTO_START_BOOT, true)
            val isUserEnabled = prefs.getBoolean(PrefsKeys.KEY_SERVICE_USER_ENABLED, true)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val onboardingComplete = prefs.getBoolean(PrefsKeys.KEY_ONBOARDING_COMPLETE, false)
            if (!onboardingComplete) {
                Log.i(TAG, "Onboarding not complete; skipping auto-start on boot.")
                return
            }

            if (!autoStartEnabled) {
                Log.i(TAG, "Auto-start on boot is disabled by user setting.")
                clearStaleDndAfterReboot(prefs, nm)
                return
            }

            if (!isUserEnabled) {
                Log.i(TAG, "Service was manually disabled by user; skipping auto-start on boot.")
                clearStaleDndAfterReboot(prefs, nm)
                return
            }

            if (!nm.isNotificationPolicyAccessGranted) {
                Log.w(TAG, "Cannot auto-start service: Notification Policy Access (DND) not granted.")
                return
            }

            Log.i(TAG, "Starting ShhhService automatically on boot...")
            try {
                val serviceIntent = Intent(context, ShhhService::class.java)
                context.startForegroundService(serviceIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to auto-start ShhhService on boot", e)
            }
        }
    }

    private fun clearStaleDndAfterReboot(
        prefs: android.content.SharedPreferences,
        nm: NotificationManager,
    ) {
        val dndActive = prefs.getBoolean(PrefsKeys.KEY_DND_ACTIVE, false)
        val activatedByService = prefs.getBoolean(PrefsKeys.KEY_WAS_DND_ACTIVATED_BY_SERVICE, false)
        if (!dndActive && !activatedByService) return

        if (dndActive && activatedByService && nm.isNotificationPolicyAccessGranted &&
            nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        ) {
            try {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                Log.i(TAG, "Restored DND interrupted by reboot/process death.")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restore stale DND after reboot", e)
            }
        }
        prefs.edit {
            putBoolean(PrefsKeys.KEY_DND_ACTIVE, false)
            putBoolean(PrefsKeys.KEY_WAS_DND_ACTIVATED_BY_SERVICE, false)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
