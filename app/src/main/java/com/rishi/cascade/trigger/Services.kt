package com.rishi.cascade.trigger

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.rishi.cascade.store.FlowStore

/** Notification triggers, and the permission that lets Now Playing read media sessions. */
class NotificationWatcher : NotificationListenerService() {

    override fun onListenerConnected() {
        connected = true
        ScreenReceiver.register(this)
    }

    override fun onListenerDisconnected() { connected = false }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (n.packageName == packageName) return
        val extras = n.notification.extras
        TriggerEngine.fire(this, "notification", mapOf(
            "package" to n.packageName,
            "title" to (extras.getCharSequence("android.title")?.toString() ?: ""),
            "text" to (extras.getCharSequence("android.text")?.toString() ?: ""),
            "app" to appLabel(this, n.packageName)
        ))
    }

    private fun appLabel(c: Context, pkg: String): String = try {
        val pm = c.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    companion object {
        @Volatile var connected = false

        fun isEnabled(c: Context): Boolean {
            val flat = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners") ?: return false
            return flat.split(":").any {
                ComponentName.unflattenFromString(it)?.packageName == c.packageName
            }
        }
    }
}

/** A quick settings tile that runs the flow you assigned to it. */
class CascadeTileService : TileService() {

    override fun onStartListening() {
        val flow = tileFlow(this)
        qsTile?.apply {
            label = flow?.name ?: "Cascade"
            state = if (flow != null) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
            updateTile()
        }
    }

    override fun onClick() {
        val flow = tileFlow(this)
        if (flow == null) {
            Toast.makeText(this, "Add the Quick Settings tile trigger to a flow first", Toast.LENGTH_LONG).show()
            return
        }
        RunnerService.start(this, flow.id, mapOf("trigger" to "tile"))
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            updateTile()
        }
    }

    private fun tileFlow(c: Context) = FlowStore.get(c).also { it.reload() }.all()
        .firstOrNull { f -> f.triggers.any { it.enabled && it.type == "tile" } }
}

/** Invisible activity behind home-screen shortcuts. Being an activity means flows started this
 *  way can still open other apps, which a background service is not allowed to do. */
class RunFlowActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val flowId = intent?.getStringExtra("flow")
        if (flowId.isNullOrEmpty()) { finish(); return }
        val flow = FlowStore.get(this).load(flowId)
        if (flow == null) {
            Toast.makeText(this, "That flow no longer exists", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        Toast.makeText(this, "Running " + flow.name, Toast.LENGTH_SHORT).show()
        RunnerService.start(this, flowId, mapOf("trigger" to "shortcut"))
        finish()
    }

    companion object {
        fun intentFor(c: Context, flowId: String): Intent =
            Intent(c, RunFlowActivity::class.java)
                .setAction("com.rishi.cascade.RUN_FLOW")
                .putExtra("flow", flowId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}
