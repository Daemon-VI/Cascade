package com.rishi.cascade.trigger

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.rishi.cascade.store.FlowStore

/**
 * The fallback backend for the "when an app opens" trigger.
 *
 * [AppWatcherService] (accessibility) is the better path — instant and free — but some OEM builds,
 * ColorOS among them, refuse to let a sideloaded app hold an accessibility service at all. Usage
 * access is not blocked in the same way, so this service polls the usage event stream instead.
 * It costs a persistent notification and a poll every second and a half, and only runs while the
 * screen is on and some flow actually wants it.
 */
class AppWatchService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastQuery = 0L
    private var currentPackage: String? = null
    private val lastFired = HashMap<String, Long>()
    private var screenOn = true

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> { screenOn = true; schedule() }
                Intent.ACTION_SCREEN_OFF -> screenOn = false
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (screenOn) poll()
            schedule()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lastQuery = System.currentTimeMillis()
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        handler.removeCallbacks(tick)
        schedule()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        try { unregisterReceiver(screenReceiver) } catch (e: Exception) {}
        running = false
        super.onDestroy()
    }

    private fun schedule() {
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, POLL_MS)
    }

    private fun poll() {
        val usm = getSystemService(UsageStatsManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val from = (lastQuery - 500L).coerceAtLeast(now - 60_000L)
        lastQuery = now

        val events = try { usm.queryEvents(from, now) } catch (e: Exception) { return }
        val event = UsageEvents.Event()
        var latest: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            ) {
                latest = event.packageName
            }
        }

        val pkg = latest ?: return
        if (pkg == packageName || pkg == currentPackage) return
        currentPackage = pkg

        // The accessibility backend, when it is alive, already reports this.
        if (AppWatcherService.connected) return

        val previous = lastFired[pkg] ?: 0L
        if (now - previous < AppWatcherService.COOLDOWN_MS) return
        lastFired[pkg] = now

        TriggerEngine.fire(this, "app_opened", mapOf("package" to pkg, "app" to label(pkg)))
    }

    private fun label(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm?.getNotificationChannel(CHANNEL) == null) {
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL, "App watcher", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Watching for app triggers")
            .setContentText("Cascade runs a flow when a chosen app opens")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34)
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIF_ID, n)
            running = true
        } catch (e: Exception) {
            running = false
        }
    }

    companion object {
        private const val POLL_MS = 1500L
        private const val CHANNEL = "cascade_watch"
        private const val NOTIF_ID = 4712

        @Volatile var running = false

        fun usageAccess(c: Context): Boolean = try {
            val ops = c.getSystemService(AppOpsManager::class.java)
            ops.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), c.packageName
            ) == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) { false }

        fun wanted(c: Context): Boolean {
            val store = FlowStore.get(c)
            return store.all().any { f -> f.triggers.any { it.enabled && it.type == "app_opened" } }
        }

        /** Starts the poller only when a flow needs it, accessibility is not doing the job,
         *  and usage access has actually been granted. Stops it otherwise. */
        fun sync(c: Context) {
            val need = wanted(c) && !AppWatcherService.connected && usageAccess(c)
            val i = Intent(c, AppWatchService::class.java)
            try {
                if (need) c.startForegroundService(i) else c.stopService(i)
            } catch (e: Exception) { }
        }
    }
}
