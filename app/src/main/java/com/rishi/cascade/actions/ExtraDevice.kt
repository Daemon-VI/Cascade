package com.rishi.cascade.actions

import android.Manifest
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.telephony.TelephonyManager
import android.view.Surface
import android.view.WindowManager
import com.rishi.cascade.engine.FlowError
import com.rishi.cascade.model.ActionDef
import com.rishi.cascade.model.Cat
import com.rishi.cascade.model.ParamSpec
import com.rishi.cascade.model.ParamType
import com.rishi.cascade.trigger.LockAdmin
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.math.sqrt

object ExtraDevice {

    private val SENSORS = linkedMapOf(
        "Light (lux)" to Sensor.TYPE_LIGHT,
        "Proximity (cm)" to Sensor.TYPE_PROXIMITY,
        "Ambient temperature (C)" to Sensor.TYPE_AMBIENT_TEMPERATURE,
        "Air pressure (hPa)" to Sensor.TYPE_PRESSURE,
        "Humidity (%)" to Sensor.TYPE_RELATIVE_HUMIDITY,
        "Acceleration (m/s2)" to Sensor.TYPE_ACCELEROMETER,
        "Step counter" to Sensor.TYPE_STEP_COUNTER
    )

    /** One reading, then unregister. Sensors that need settling still answer on the first event. */
    private suspend fun readSensor(c: Context, type: Int, timeoutMs: Long): FloatArray? {
        val sm = c.getSystemService(SensorManager::class.java) ?: return null
        val sensor = sm.getDefaultSensor(type) ?: return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        if (cont.isActive) {
                            sm.unregisterListener(this)
                            cont.resume(event.values.copyOf())
                        }
                    }
                    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
                }
                sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
                cont.invokeOnCancellation { sm.unregisterListener(listener) }
            }
        }
    }

    fun all(): List<Action> = listOf(

        act(ActionDef(
            "dev.sensor", "Read Sensor", Cat.DEVICE, "Sensors",
            summary = "Read %sensor%",
            params = listOf(
                ParamSpec("sensor", "Sensor", ParamType.CHOICE, "Light (lux)", SENSORS.keys.toList()),
                ParamSpec("timeout", "Wait up to (seconds)", ParamType.NUMBER, "5")
            ),
            output = "The reading",
            description = "Takes a single reading. Acceleration returns the overall magnitude, so a " +
                    "phone at rest reads about 9.8.",
            keywords = "sensor light lux proximity temperature pressure humidity accelerometer steps"
        )) { env ->
            val name = env.choice("sensor", "Light (lux)")
            val type = SENSORS[name] ?: Sensor.TYPE_LIGHT
            val values = readSensor(env.app, type, (env.num("timeout", 5.0) * 1000).toLong())
                ?: throw FlowError("This phone has no " + name + " sensor, or it did not report in time")
            if (type == Sensor.TYPE_ACCELEROMETER && values.size >= 3)
                sqrt((values[0] * values[0] + values[1] * values[1] + values[2] * values[2]).toDouble())
            else values[0].toDouble()
        },

        act(ActionDef(
            "dev.telephony", "Mobile Network Info", Cat.DEVICE, "SignalCellularAlt",
            params = listOf(ParamSpec("field", "Get", ParamType.CHOICE, "Carrier",
                listOf("Carrier", "SIM carrier", "Country", "Is roaming", "SIM state", "Everything"))),
            output = "Network information",
            keywords = "carrier operator sim mobile network roaming country telephony"
        )) { env ->
            val tm = env.app.getSystemService(TelephonyManager::class.java)
                ?: throw FlowError("No telephony on this device")
            val simState = when (tm.simState) {
                TelephonyManager.SIM_STATE_READY -> "Ready"
                TelephonyManager.SIM_STATE_ABSENT -> "No SIM"
                TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN required"
                TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK required"
                TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "Network locked"
                else -> "Unknown"
            }
            when (env.choice("field", "Carrier")) {
                "SIM carrier" -> tm.simOperatorName ?: ""
                "Country" -> (tm.networkCountryIso ?: "").uppercase()
                "Is roaming" -> tm.isNetworkRoaming
                "SIM state" -> simState
                "Everything" -> JSONObject()
                    .put("carrier", tm.networkOperatorName ?: "")
                    .put("simCarrier", tm.simOperatorName ?: "")
                    .put("country", (tm.networkCountryIso ?: "").uppercase())
                    .put("roaming", tm.isNetworkRoaming)
                    .put("simState", simState)
                else -> tm.networkOperatorName ?: ""
            }
        },

        act(ActionDef(
            "dev.orientation", "Screen Orientation", Cat.DEVICE, "ScreenRotation",
            params = listOf(ParamSpec("field", "Get", ParamType.CHOICE, "Orientation",
                listOf("Orientation", "Rotation degrees", "Is portrait"))),
            output = "How the screen is turned",
            keywords = "orientation rotation portrait landscape screen"
        )) { env ->
            @Suppress("DEPRECATION")
            val rotation = env.app.getSystemService(WindowManager::class.java)
                ?.defaultDisplay?.rotation ?: Surface.ROTATION_0
            val degrees = when (rotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            val portrait = degrees == 0 || degrees == 180
            when (env.choice("field", "Orientation")) {
                "Rotation degrees" -> degrees.toDouble()
                "Is portrait" -> portrait
                else -> if (portrait) "Portrait" else "Landscape"
            }
        },

        act(ActionDef(
            "dev.keepawake", "Keep Screen Awake", Cat.DEVICE, "LightMode",
            summary = "Awake for %seconds%s",
            params = listOf(ParamSpec("seconds", "Seconds", ParamType.NUMBER, "60")),
            output = null,
            description = "Holds a wake lock so the display does not sleep for a while.",
            keywords = "awake wake lock screen on keep alive display"
        )) { env ->
            val pm = env.app.getSystemService(PowerManager::class.java)
                ?: throw FlowError("Power manager unavailable")
            @Suppress("DEPRECATION")
            val lock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "cascade:keepawake"
            )
            val ms = (env.num("seconds", 60.0) * 1000).toLong().coerceIn(1000, 3_600_000)
            lock.acquire(ms)
            null
        },

        act(ActionDef(
            "dev.lock", "Lock the Screen", Cat.DEVICE, "Lock",
            output = null,
            specialAccess = "device_admin",
            description = "Turns the screen off and locks the phone. Needs Cascade enabled as a " +
                    "device admin, which you can do from Settings inside the app.",
            keywords = "lock screen off secure sleep device admin"
        )) { env ->
            val dpm = env.app.getSystemService(DevicePolicyManager::class.java)
                ?: throw FlowError("Device policy unavailable")
            if (!dpm.isAdminActive(ComponentName(env.app, LockAdmin::class.java)))
                throw FlowError("Cascade is not a device admin yet. Turn it on in Settings inside the app.")
            dpm.lockNow()
            null
        },

        act(ActionDef(
            "app.kill", "Close Background App", Cat.APPS, "Delete",
            summary = "Close %package%",
            params = listOf(ParamSpec("package", "App", ParamType.APP, "")),
            output = null,
            permissions = listOf("android.permission.KILL_BACKGROUND_PROCESSES"),
            description = "Asks Android to drop that app's background processes. The system may " +
                    "restart it straight away, and this cannot close whatever is on screen.",
            keywords = "kill close stop app background free memory"
        )) { env ->
            val pkg = env.str("package").trim()
            if (pkg.isEmpty()) throw FlowError("No app chosen")
            env.app.getSystemService(ActivityManager::class.java)?.killBackgroundProcesses(pkg)
            null
        },

        act(ActionDef(
            "app.permissions", "App Permissions", Cat.APPS, "Shield",
            summary = "Permissions of %package%",
            params = listOf(
                ParamSpec("package", "App", ParamType.APP, ""),
                ParamSpec("mode", "Return", ParamType.CHOICE, "Granted dangerous ones",
                    listOf("Granted dangerous ones", "All declared", "Count"))
            ),
            output = "A list of permissions",
            description = "What an app is actually allowed to do, not what the store page claims.",
            keywords = "permissions app audit privacy granted dangerous security"
        )) { env ->
            val pm = env.app.packageManager
            val pkg = env.str("package").trim()
            val info = try {
                pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
            } catch (e: Exception) {
                throw FlowError(pkg + " is not installed")
            }
            val declared = info.requestedPermissions?.toList().orEmpty()
            val flags = info.requestedPermissionsFlags
            val granted = declared.filterIndexed { i, _ ->
                (flags?.getOrNull(i) ?: 0) and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
            }
            val dangerous = granted.filter { perm ->
                try {
                    val pi = pm.getPermissionInfo(perm, 0)
                    (pi.protectionLevel and android.content.pm.PermissionInfo.PROTECTION_MASK_BASE) ==
                            android.content.pm.PermissionInfo.PROTECTION_DANGEROUS
                } catch (e: Exception) { false }
            }
            when (env.choice("mode", "Granted dangerous ones")) {
                "All declared" -> declared
                "Count" -> dangerous.size.toDouble()
                else -> dangerous.map { it.substringAfterLast('.') }
            }
        }
    )
}
