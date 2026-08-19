package com.rishi.cascade.trigger

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.rishi.cascade.model.Flow
import com.rishi.cascade.model.Trigger
import com.rishi.cascade.store.FlowStore
import java.util.Calendar

object Scheduler {

    const val ACTION_ALARM = "com.rishi.cascade.ALARM"

    private fun pending(c: Context, flowId: String, triggerId: String): PendingIntent {
        val i = Intent(c, TriggerReceiver::class.java)
            .setAction(ACTION_ALARM)
            .setData(Uri.parse("cascade://alarm/" + flowId + "/" + triggerId))
            .putExtra("flow", flowId)
            .putExtra("trigger", triggerId)
        return PendingIntent.getBroadcast(
            c, triggerId.hashCode(), i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun canBeExact(c: Context): Boolean {
        val am = c.getSystemService(AlarmManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= 31) am.canScheduleExactAlarms() else true
    }

    /** Works out when a trigger should next fire. */
    fun nextTime(t: Trigger): Long {
        val now = System.currentTimeMillis()
        return when (t.type) {
            "time" -> {
                val parts = (t.config["value"] ?: "07:30").split(":")
                val h = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 7
                val m = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 30
                val c = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, h.coerceIn(0, 23))
                    set(Calendar.MINUTE, m.coerceIn(0, 59))
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
                c.timeInMillis
            }
            "interval" -> {
                val mins = (t.config["value"]?.trim()?.toLongOrNull() ?: 30L).coerceAtLeast(1L)
                now + mins * 60_000L
            }
            else -> 0L
        }
    }

    fun schedule(c: Context, flow: Flow, t: Trigger) {
        if (!t.enabled) return
        val at = nextTime(t)
        if (at <= 0L) return
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(c, flow.id, t.id)
        try {
            if (canBeExact(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(c: Context, flowId: String, triggerId: String) {
        c.getSystemService(AlarmManager::class.java)?.cancel(pending(c, flowId, triggerId))
    }

    /** Re-arms every time-based trigger. Called at boot, after edits and after each firing. */
    fun rescheduleAll(c: Context) {
        val store = FlowStore.get(c)
        store.reload()
        store.all().forEach { flow ->
            flow.triggers.forEach { t ->
                if (t.type == "time" || t.type == "interval") {
                    if (t.enabled) schedule(c, flow, t) else cancel(c, flow.id, t.id)
                }
            }
        }
    }
}
