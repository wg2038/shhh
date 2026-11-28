package io.github.wg2038.shhh

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.edit

class ShhhLockAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PrefsKeys.KEY_PENDING_AUTO_LOCK_AUTH, false)) {
            prefs.edit {
                putBoolean(PrefsKeys.KEY_AUTO_LOCK_SCREEN, true)
                putBoolean(PrefsKeys.KEY_PENDING_AUTO_LOCK_AUTH, false)
            }
        }
        Log.i(TAG, "ShhhLockAccessibilityService connected successfully.")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) {
            instance = null
        }
        Log.i(TAG, "ShhhLockAccessibilityService unbound.")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        Log.i(TAG, "ShhhLockAccessibilityService destroyed.")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ShhhLockAccessibility"
        @Volatile
        var instance: ShhhLockAccessibilityService? = null
            private set

        fun performLock(): Boolean {
            val service = instance ?: return false
            val success = service.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            Log.i(TAG, "performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) result: $success")
            return success
        }

        fun isAccessibilityServiceEnabled(context: Context): Boolean {
            val masterEnabled = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0
            ) == 1
            if (!masterEnabled) return false

            val expectedComponent = ComponentName(context, ShhhLockAccessibilityService::class.java)
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServices)
            while (colonSplitter.hasNext()) {
                val componentStr = colonSplitter.next()
                val component = ComponentName.unflattenFromString(componentStr)
                if (component != null && component == expectedComponent) {
                    return true
                }
            }
            return false
        }
    }
}
