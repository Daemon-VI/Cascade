package com.rishi.cascade.trigger

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Notices which app came to the foreground, for the "when an app opens" trigger.
 *
 * Accessibility is the only way to learn this promptly — the alternative, polling
 * UsageStatsManager from a service, is both laggy and something ColorOS kills. The service is
 * configured to receive nothing but window-state changes and cannot read screen content.
 */
class AppWatcherService : AccessibilityService() {

    private var lastPackage: String? = null
    private val lastFired = HashMap<String, Long>()

    override fun onServiceConnected() {
        connected = true
        ScreenReceiver.register(this)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        connected = false
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return
        // Launchers and system UI churn constantly; they are not "opening an app".
        if (pkg == "com.android.systemui") return

        // Only the transition into an app counts, not every window it opens once inside.
        if (pkg == lastPackage) return
        lastPackage = pkg

        val now = System.currentTimeMillis()
        val previous = lastFired[pkg] ?: 0L
        if (now - previous < COOLDOWN_MS) return
        lastFired[pkg] = now

        TriggerEngine.fire(this, "app_opened", mapOf("package" to pkg, "app" to label(this, pkg)))
    }

    private fun label(c: Context, pkg: String): String = try {
        val pm = c.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    companion object {
        /** Stops a flow firing again the moment you flick back into the same app. */
        const val COOLDOWN_MS = 20_000L

        @Volatile var connected = false

        fun isEnabled(c: Context): Boolean {
            val flat = Settings.Secure.getString(
                c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return flat.split(":").any {
                ComponentName.unflattenFromString(it)?.packageName == c.packageName
            }
        }
    }
}
