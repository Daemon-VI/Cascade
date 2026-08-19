package com.rishi.cascade.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.rishi.cascade.store.FlowStore

/** Handles boot, power, headset, airplane mode and scheduled alarms. */
class TriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Scheduler.rescheduleAll(context)
                TriggerEngine.fire(context, "boot")
            }
            Scheduler.ACTION_ALARM -> {
                val flowId = intent.getStringExtra("flow") ?: return
                val triggerId = intent.getStringExtra("trigger") ?: return
                val store = FlowStore.get(context)
                store.reload()
                val flow = store.load(flowId)
                val trigger = flow?.triggers?.firstOrNull { it.id == triggerId }
                if (flow != null && trigger != null && trigger.enabled) {
                    RunnerService.start(context, flowId, mapOf("trigger" to trigger.type))
                    Scheduler.schedule(context, flow, trigger)   // arm the next one
                }
            }
            Intent.ACTION_POWER_CONNECTED -> TriggerEngine.fire(context, "power_connected")
            Intent.ACTION_POWER_DISCONNECTED -> TriggerEngine.fire(context, "power_disconnected")
            Intent.ACTION_BATTERY_LOW -> TriggerEngine.fire(context, "battery_low", mapOf("level" to level(context)))
            Intent.ACTION_HEADSET_PLUG -> {
                val plugged = intent.getIntExtra("state", 0) == 1
                TriggerEngine.fire(context, if (plugged) "headset_plug" else "headset_unplug")
            }
            "android.intent.action.AIRPLANE_MODE" -> {
                val on = intent.getBooleanExtra("state", false)
                TriggerEngine.fire(context, "airplane", mapOf("airplane" to on.toString()))
            }
        }
    }

    private fun level(c: Context): String {
        val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return "0"
        val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
        val s = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        return (l * 100 / s).toString()
    }
}

/** Screen and unlock events are only delivered to receivers registered in code. */
class ScreenReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> TriggerEngine.fire(context, "screen_on")
            Intent.ACTION_SCREEN_OFF -> TriggerEngine.fire(context, "screen_off")
            Intent.ACTION_USER_PRESENT -> TriggerEngine.fire(context, "unlocked")
        }
    }

    companion object {
        private var registered: ScreenReceiver? = null

        fun register(c: Context) {
            if (registered != null) return
            val r = ScreenReceiver()
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            try {
                c.applicationContext.registerReceiver(r, f)
                registered = r
            } catch (e: Exception) { }
        }
    }
}
