package com.rishi.cascade.actions

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import com.rishi.cascade.engine.FlowError
import com.rishi.cascade.engine.V
import com.rishi.cascade.model.ActionDef
import com.rishi.cascade.model.Cat
import com.rishi.cascade.model.ParamSpec
import com.rishi.cascade.model.ParamType
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

object DeviceActions {

    private val STREAMS = listOf("Media", "Ringer", "Notification", "Alarm", "Call", "System")

    private fun stream(name: String) = when (name) {
        "Ringer" -> AudioManager.STREAM_RING
        "Notification" -> AudioManager.STREAM_NOTIFICATION
        "Alarm" -> AudioManager.STREAM_ALARM
        "Call" -> AudioManager.STREAM_VOICE_CALL
        "System" -> AudioManager.STREAM_SYSTEM
        else -> AudioManager.STREAM_MUSIC
    }

    fun canWriteSettings(c: Context): Boolean = Settings.System.canWrite(c)

    private fun requireWriteSettings(c: Context) {
        if (!canWriteSettings(c))
            throw FlowError("Cascade needs \"Modify system settings\" for this. Grant it in Settings inside the app.")
    }

    private val SETTINGS_SCREENS = linkedMapOf(
        "Wi-Fi" to Settings.ACTION_WIFI_SETTINGS,
        "Bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
        "Mobile data" to Settings.ACTION_DATA_ROAMING_SETTINGS,
        "Airplane mode" to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
        "Location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
        "Display" to Settings.ACTION_DISPLAY_SETTINGS,
        "Sound" to Settings.ACTION_SOUND_SETTINGS,
        "Battery saver" to Settings.ACTION_BATTERY_SAVER_SETTINGS,
        "Apps" to Settings.ACTION_APPLICATION_SETTINGS,
        "Storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
        "Developer options" to Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
        "Accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
        "Date & time" to Settings.ACTION_DATE_SETTINGS,
        "Notifications" to Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
        "Main settings" to Settings.ACTION_SETTINGS
    )

    fun all(): List<Action> = listOf(

        act(ActionDef(
            "dev.volume.set", "Set Volume", Cat.DEVICE, "VolumeUp",
            summary = "%stream% volume to %level%%",
            params = listOf(
                ParamSpec("stream", "Stream", ParamType.CHOICE, "Media", STREAMS),
                ParamSpec("level", "Level (0-100)", ParamType.NUMBER, "50"),
                ParamSpec("showUi", "Show the volume slider", ParamType.BOOL, "false")
            ),
            output = null,
            keywords = "volume sound loud mute audio media ringer"
        )) { env ->
            val am = env.app.getSystemService(AudioManager::class.java)
            val s = stream(env.choice("stream", "Media"))
            val max = am.getStreamMaxVolume(s)
            val target = (env.num("level", 50.0) / 100.0 * max).toInt().coerceIn(0, max)
            try {
                am.setStreamVolume(s, target, if (env.bool("showUi", false)) AudioManager.FLAG_SHOW_UI else 0)
            } catch (e: SecurityException) {
                throw FlowError("Changing this volume needs Do Not Disturb access")
            }
            null
        },

        act(ActionDef(
            "dev.volume.get", "Get Volume", Cat.DEVICE, "VolumeDown",
            params = listOf(ParamSpec("stream", "Stream", ParamType.CHOICE, "Media", STREAMS)),
            output = "Volume as a percentage",
            keywords = "volume get level read"
        )) { env ->
            val am = env.app.getSystemService(AudioManager::class.java)
            val s = stream(env.choice("stream", "Media"))
            val max = am.getStreamMaxVolume(s).coerceAtLeast(1)
            (am.getStreamVolume(s) * 100.0 / max)
        },

        act(ActionDef(
            "dev.ringer", "Set Ringer Mode", Cat.DEVICE, "NotificationsPaused",
            summary = "Ringer to %mode%",
            params = listOf(ParamSpec("mode", "Mode", ParamType.CHOICE, "Vibrate", listOf("Normal", "Vibrate", "Silent"))),
            output = null,
            specialAccess = "dnd",
            keywords = "ringer silent vibrate mute sound profile"
        )) { env ->
            val am = env.app.getSystemService(AudioManager::class.java)
            val nm = env.app.getSystemService(NotificationManager::class.java)
            val mode = env.choice("mode", "Vibrate")
            if (mode == "Silent" && !nm.isNotificationPolicyAccessGranted)
                throw FlowError("Silent mode needs Do Not Disturb access. Grant it in Settings inside the app.")
            am.ringerMode = when (mode) {
                "Normal" -> AudioManager.RINGER_MODE_NORMAL
                "Silent" -> AudioManager.RINGER_MODE_SILENT
                else -> AudioManager.RINGER_MODE_VIBRATE
            }
            null
        },

        act(ActionDef(
            "dev.dnd", "Set Do Not Disturb", Cat.DEVICE, "DoNotDisturbOn",
            summary = "DND %mode%",
            params = listOf(ParamSpec("mode", "Filter", ParamType.CHOICE, "On",
                listOf("On", "Off", "Priority only", "Alarms only"))),
            output = null,
            specialAccess = "dnd",
            keywords = "dnd do not disturb silence focus"
        )) { env ->
            val nm = env.app.getSystemService(NotificationManager::class.java)
            if (!nm.isNotificationPolicyAccessGranted)
                throw FlowError("This needs Do Not Disturb access. Grant it in Settings inside the app.")
            nm.setInterruptionFilter(when (env.choice("mode", "On")) {
                "Off" -> NotificationManager.INTERRUPTION_FILTER_ALL
                "Priority only" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
                "Alarms only" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
                else -> NotificationManager.INTERRUPTION_FILTER_NONE
            })
            null
        },

        act(ActionDef(
            "dev.brightness", "Set Brightness", Cat.DEVICE, "BrightnessHigh",
            summary = "Brightness %level%%",
            params = listOf(
                ParamSpec("auto", "Automatic brightness", ParamType.CHOICE, "Leave alone",
                    listOf("Leave alone", "Turn on", "Turn off")),
                ParamSpec("level", "Level (0-100)", ParamType.NUMBER, "50")
            ),
            output = null,
            specialAccess = "write_settings",
            keywords = "brightness screen display dim bright auto"
        )) { env ->
            requireWriteSettings(env.app)
            val cr = env.app.contentResolver
            when (env.choice("auto", "Leave alone")) {
                "Turn on" -> Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 1)
                "Turn off" -> Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
            }
            if (env.raw("level").isNotBlank()) {
                val v = (env.num("level", 50.0) / 100.0 * 255).toInt().coerceIn(1, 255)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, v)
            }
            null
        },

        act(ActionDef(
            "dev.rotation", "Set Auto-Rotate", Cat.DEVICE, "ScreenRotation",
            summary = "Auto-rotate %mode%",
            params = listOf(ParamSpec("mode", "Auto-rotate", ParamType.CHOICE, "Off", listOf("On", "Off", "Toggle"))),
            output = null,
            specialAccess = "write_settings",
            keywords = "rotation rotate orientation lock screen"
        )) { env ->
            requireWriteSettings(env.app)
            val cr = env.app.contentResolver
            val cur = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0)
            val target = when (env.choice("mode", "Off")) {
                "On" -> 1
                "Toggle" -> if (cur == 1) 0 else 1
                else -> 0
            }
            Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, target)
            null
        },

        act(ActionDef(
            "dev.timeout", "Set Screen Timeout", Cat.DEVICE, "Timer",
            summary = "Timeout %seconds%s",
            params = listOf(ParamSpec("seconds", "Seconds", ParamType.NUMBER, "30")),
            output = null,
            specialAccess = "write_settings",
            keywords = "timeout screen off sleep display"
        )) { env ->
            requireWriteSettings(env.app)
            Settings.System.putInt(env.app.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT,
                (env.num("seconds", 30.0) * 1000).toInt().coerceAtLeast(5000))
            null
        },

        act(ActionDef(
            "dev.torch", "Flashlight", Cat.DEVICE, "FlashlightOn",
            summary = "Flashlight %mode%",
            params = listOf(ParamSpec("mode", "State", ParamType.CHOICE, "Toggle", listOf("On", "Off", "Toggle"))),
            output = null,
            keywords = "flashlight torch light led flash"
        )) { env ->
            val cm = env.app.getSystemService(CameraManager::class.java)
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: throw FlowError("This device has no flash")
            val want = when (env.choice("mode", "Toggle")) {
                "On" -> true
                "Off" -> false
                else -> !torchOn
            }
            cm.setTorchMode(id, want)
            torchOn = want
            null
        },

        act(ActionDef(
            "dev.vibrate", "Vibrate", Cat.DEVICE, "Vibration",
            summary = "Vibrate %pattern%",
            params = listOf(
                ParamSpec("pattern", "Pattern", ParamType.CHOICE, "Short",
                    listOf("Short", "Long", "Double", "Triple", "Heartbeat", "Custom")),
                ParamSpec("custom", "Custom (ms, comma separated)", ParamType.TEXT, "0,200,100,200",
                    showIf = "pattern" to listOf("Custom"))
            ),
            output = null,
            keywords = "vibrate haptic buzz feedback"
        )) { env ->
            val vib = if (Build.VERSION.SDK_INT >= 31)
                env.app.getSystemService(VibratorManager::class.java).defaultVibrator
            else @Suppress("DEPRECATION") env.app.getSystemService(Vibrator::class.java)
            val pattern = when (env.choice("pattern", "Short")) {
                "Long" -> longArrayOf(0, 600)
                "Double" -> longArrayOf(0, 120, 120, 120)
                "Triple" -> longArrayOf(0, 100, 100, 100, 100, 100)
                "Heartbeat" -> longArrayOf(0, 90, 120, 220, 400, 90, 120, 220)
                "Custom" -> env.str("custom").split(",").mapNotNull { it.trim().toLongOrNull() }.toLongArray()
                else -> longArrayOf(0, 150)
            }
            if (pattern.isEmpty()) return@act null
            vib.vibrate(VibrationEffect.createWaveform(pattern, -1))
            null
        },

        act(ActionDef(
            "dev.clip.get", "Get Clipboard", Cat.DEVICE, "ContentPaste",
            output = "The clipboard text",
            description = "Android only lets an app read the clipboard while it is on screen.",
            keywords = "clipboard paste copy read"
        )) { env ->
            suspendCancellableCoroutine<String> { cont ->
                Handler(Looper.getMainLooper()).post {
                    val cb = env.app.getSystemService(ClipboardManager::class.java)
                    val text = cb?.primaryClip?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)?.coerceToText(env.app)?.toString() ?: ""
                    cont.resume(text)
                }
            }
        },

        act(ActionDef(
            "dev.clip.set", "Set Clipboard", Cat.DEVICE, "ContentCopy",
            summary = "Copy %text%",
            params = listOf(ParamSpec("text", "Text", ParamType.MULTILINE, "{{last}}")),
            output = null,
            keywords = "clipboard copy set paste"
        )) { env ->
            val t = env.str("text")
            Handler(Looper.getMainLooper()).post {
                env.app.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("Cascade", t))
            }
            null
        },

        act(ActionDef(
            "dev.battery", "Battery Status", Cat.DEVICE, "BatteryFull",
            params = listOf(ParamSpec("field", "Get", ParamType.CHOICE, "Level",
                listOf("Level", "Is charging", "Temperature", "Power source", "Health", "Everything"))),
            output = "Battery information",
            keywords = "battery charge level charging power temperature"
        )) { env ->
            val i = env.app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: throw FlowError("Battery status unavailable")
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val pct = level * 100.0 / scale
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
            val plugged = when (i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
                BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
                else -> "Unplugged"
            }
            val health = when (i.getIntExtra(BatteryManager.EXTRA_HEALTH, 1)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheating"
                BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
                BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
                else -> "Unknown"
            }
            when (env.choice("field", "Level")) {
                "Is charging" -> charging
                "Temperature" -> temp
                "Power source" -> plugged
                "Health" -> health
                "Everything" -> JSONObject().put("level", pct).put("charging", charging)
                    .put("temperature", temp).put("source", plugged).put("health", health)
                else -> pct
            }
        },

        act(ActionDef(
            "dev.info", "Device Info", Cat.DEVICE, "PhoneAndroid",
            params = listOf(ParamSpec("field", "Get", ParamType.CHOICE, "Model",
                listOf("Model", "Manufacturer", "Android version", "SDK level", "Device name",
                    "Free storage (GB)", "Total storage (GB)", "Free memory (MB)", "Uptime (hours)", "Everything"))),
            output = "Device information",
            keywords = "device info model android version storage memory ram uptime"
        )) { env ->
            val stat = StatFs(Environment.getDataDirectory().path)
            val freeGb = stat.availableBytes / 1073741824.0
            val totalGb = stat.totalBytes / 1073741824.0
            val mi = ActivityManager.MemoryInfo()
            env.app.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
            val uptimeH = SystemClock.elapsedRealtime() / 3_600_000.0
            when (env.choice("field", "Model")) {
                "Manufacturer" -> Build.MANUFACTURER
                "Android version" -> Build.VERSION.RELEASE
                "SDK level" -> Build.VERSION.SDK_INT.toDouble()
                "Device name" -> Settings.Global.getString(env.app.contentResolver, "device_name") ?: Build.MODEL
                "Free storage (GB)" -> freeGb
                "Total storage (GB)" -> totalGb
                "Free memory (MB)" -> mi.availMem / 1048576.0
                "Uptime (hours)" -> uptimeH
                "Everything" -> JSONObject()
                    .put("model", Build.MODEL).put("manufacturer", Build.MANUFACTURER)
                    .put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
                    .put("freeStorageGb", Math.round(freeGb * 10) / 10.0)
                    .put("totalStorageGb", Math.round(totalGb * 10) / 10.0)
                    .put("freeMemoryMb", mi.availMem / 1048576)
                    .put("uptimeHours", Math.round(uptimeH * 10) / 10.0)
                else -> Build.MODEL
            }
        },

        act(ActionDef(
            "dev.network", "Network Status", Cat.DEVICE, "Wifi",
            params = listOf(ParamSpec("field", "Get", ParamType.CHOICE, "Connection type",
                listOf("Connection type", "Is connected", "Wi-Fi name", "Wi-Fi signal (%)",
                    "Local IP address", "Is metered", "Everything"))),
            output = "Network information",
            permissions = listOf("android.permission.ACCESS_FINE_LOCATION"),
            description = "Wi-Fi name requires location permission on Android 10 and newer.",
            keywords = "wifi network connection ssid ip mobile data signal"
        )) { env ->
            val cm = env.app.getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            val type = when {
                caps == null -> "None"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "Other"
            }
            val connected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
            @Suppress("DEPRECATION")
            val wifi = env.app.applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            val info = try { wifi?.connectionInfo } catch (e: Exception) { null }
            val ssid = info?.ssid?.trim('"')?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" } ?: ""
            @Suppress("DEPRECATION")
            val rssiPct = info?.let { WifiManager.calculateSignalLevel(it.rssi, 100) } ?: 0
            @Suppress("DEPRECATION")
            val ip = info?.ipAddress?.let {
                if (it == 0) "" else "%d.%d.%d.%d".format(it and 0xff, it shr 8 and 0xff, it shr 16 and 0xff, it shr 24 and 0xff)
            } ?: ""
            when (env.choice("field", "Connection type")) {
                "Is connected" -> connected
                "Wi-Fi name" -> ssid
                "Wi-Fi signal (%)" -> rssiPct.toDouble()
                "Local IP address" -> ip
                "Is metered" -> metered
                "Everything" -> JSONObject().put("type", type).put("connected", connected)
                    .put("ssid", ssid).put("signal", rssiPct).put("ip", ip).put("metered", metered)
                else -> type
            }
        },

        act(ActionDef(
            "dev.settings", "Open Settings Page", Cat.DEVICE, "Settings",
            summary = "Open %page% settings",
            params = listOf(ParamSpec("page", "Page", ParamType.CHOICE, "Wi-Fi", SETTINGS_SCREENS.keys.toList())),
            output = null,
            description = "Jumps straight to a system settings screen. Android blocks apps from toggling Wi-Fi and Bluetooth directly, so this is the reliable route.",
            keywords = "settings open system wifi bluetooth airplane display"
        )) { env ->
            val action = SETTINGS_SCREENS[env.choice("page", "Wi-Fi")] ?: Settings.ACTION_SETTINGS
            env.app.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            null
        },

        act(ActionDef(
            "dev.wifi.panel", "Show Wi-Fi Panel", Cat.DEVICE, "WifiTethering",
            output = null,
            description = "Slides up the system Wi-Fi picker without leaving what you were doing.",
            keywords = "wifi panel quick connect network"
        )) { env ->
            val action = if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
            env.app.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            null
        }
    )

    @Volatile private var torchOn = false
}
