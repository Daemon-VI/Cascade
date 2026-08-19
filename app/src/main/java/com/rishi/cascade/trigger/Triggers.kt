package com.rishi.cascade.trigger

import android.content.Context
import com.rishi.cascade.model.Trigger
import com.rishi.cascade.store.FlowStore

/** Everything a flow can react to. */
data class TriggerType(
    val id: String,
    val title: String,
    val icon: String,
    val description: String,
    val configLabel: String = "",
    val configHint: String = "",
    val needs: String = ""      // "notification_listener", "exact_alarm", ""
)

object TriggerTypes {

    val ALL = listOf(
        TriggerType("tile", "Quick Settings tile", "Dashboard",
            "Run from the tile in your notification shade."),
        TriggerType("shortcut", "Home screen icon", "AddToHomeScreen",
            "Add an icon that runs this flow straight from the home screen."),
        TriggerType("time", "At a time of day", "Schedule",
            "Runs every day at the time you pick.", "Time (HH:mm)", "07:30", "exact_alarm"),
        TriggerType("interval", "Every so often", "Update",
            "Runs on a repeating timer while the phone is awake.", "Minutes between runs", "30", "exact_alarm"),
        TriggerType("boot", "When the phone starts", "RestartAlt",
            "Runs once after a reboot."),
        TriggerType("power_connected", "Charger plugged in", "BatteryChargingFull",
            "Runs the moment power is connected."),
        TriggerType("power_disconnected", "Charger unplugged", "PowerOff",
            "Runs when power is disconnected."),
        TriggerType("battery_low", "Battery gets low", "Battery2Bar",
            "Runs when the battery drops below a level you choose.", "Percentage", "20"),
        TriggerType("headset_plug", "Headphones connected", "Headphones",
            "Runs when wired headphones go in."),
        TriggerType("headset_unplug", "Headphones removed", "HeadsetOff",
            "Runs when wired headphones come out."),
        TriggerType("screen_on", "Screen turns on", "LightMode",
            "Runs each time the display wakes."),
        TriggerType("screen_off", "Screen turns off", "DarkMode",
            "Runs each time the display sleeps."),
        TriggerType("unlocked", "Phone unlocked", "LockOpen",
            "Runs after you unlock the phone."),
        TriggerType("wifi_connected", "Joins a Wi-Fi network", "Wifi",
            "Runs when Wi-Fi connects. Leave the name blank for any network.", "Network name", "Home Wi-Fi"),
        TriggerType("wifi_disconnected", "Leaves Wi-Fi", "WifiOff",
            "Runs when Wi-Fi drops."),
        TriggerType("notification", "A notification arrives", "Notifications",
            "Runs when a notification appears. The text lands in {{title}}, {{text}} and {{app}}.",
            "Only from this app (package or blank)", "com.whatsapp", "notification_listener"),
        TriggerType("airplane", "Airplane mode changes", "AirplanemodeActive",
            "Runs when airplane mode is switched on or off.")
    )

    fun get(id: String): TriggerType? = ALL.firstOrNull { it.id == id }
}

object TriggerEngine {

    /** Runs every enabled flow whose trigger of this type matches, in the background. */
    fun fire(context: Context, type: String, extras: Map<String, String> = emptyMap()) {
        val store = FlowStore.get(context)
        store.reload()
        store.all().forEach { flow ->
            flow.triggers.filter { it.enabled && it.type == type && matches(it, type, extras) }
                .forEach { _ ->
                    RunnerService.start(context, flow.id, extras)
                }
        }
    }

    private fun matches(t: Trigger, type: String, extras: Map<String, String>): Boolean {
        val filter = t.config["value"]?.trim().orEmpty()
        return when (type) {
            "wifi_connected" -> filter.isEmpty() ||
                    extras["ssid"]?.equals(filter, ignoreCase = true) == true
            "notification" -> {
                val pkgOk = filter.isEmpty() || extras["package"]?.contains(filter, true) == true
                val textFilter = t.config["contains"]?.trim().orEmpty()
                val textOk = textFilter.isEmpty() ||
                        (extras["title"].orEmpty() + " " + extras["text"].orEmpty()).contains(textFilter, true)
                pkgOk && textOk
            }
            "battery_low" -> {
                val threshold = filter.toDoubleOrNull() ?: 20.0
                (extras["level"]?.toDoubleOrNull() ?: 0.0) <= threshold
            }
            else -> true
        }
    }
}
