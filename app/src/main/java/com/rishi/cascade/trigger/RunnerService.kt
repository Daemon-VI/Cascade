package com.rishi.cascade.trigger

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.rishi.cascade.actions.UiActions
import com.rishi.cascade.engine.Engine
import com.rishi.cascade.engine.FlowIO
import com.rishi.cascade.engine.FlowStopped
import com.rishi.cascade.engine.LogLine
import com.rishi.cascade.engine.V
import com.rishi.cascade.store.FlowStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Runs flows away from the UI, for triggers, tiles and home-screen shortcuts. */
class RunnerService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var running = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val flowId = intent?.getStringExtra(EXTRA_FLOW) ?: return START_NOT_STICKY
        val extras = HashMap<String, String>()
        intent.getStringArrayExtra(EXTRA_KEYS)?.forEachIndexed { i, k ->
            extras[k] = intent.getStringArrayExtra(EXTRA_VALUES)?.getOrNull(i) ?: ""
        }
        startForegroundSafely(flowId)
        running++
        scope.launch { runFlow(flowId, extras) }
        return START_NOT_STICKY
    }

    private fun startForegroundSafely(flowId: String) {
        UiActions.ensureChannel(this)
        val name = FlowStore.get(this).load(flowId)?.name ?: "Flow"
        val n = Notification.Builder(this, UiActions.CHANNEL_ID)
            .setContentTitle("Running " + name)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, serviceTypes())
            else startForeground(NOTIF_ID, n)
        } catch (e: Exception) {
            // Some OEM builds refuse a foreground start from the background; the work still runs.
            try { startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) }
            catch (e2: Exception) { }
        }
    }

    /** Camera and microphone are only claimed when their permission is actually held - declaring
     *  a type without its permission makes startForeground throw. */
    private fun serviceTypes(): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        return types
    }

    private suspend fun runFlow(flowId: String, extras: Map<String, String>) {
        val store = FlowStore.get(this)
        val flow = store.load(flowId)
        if (flow == null) { finishOne(); return }
        val io = HeadlessIO(this, flow.name)
        val engine = Engine(this, flow, io)
        extras.forEach { (k, v) -> engine.ctx.set(k, v) }
        try {
            engine.run()
            store.markRun(flowId)
            History.record(this, flow.name, true, io.lines(), null)
        } catch (e: FlowStopped) {
            History.record(this, flow.name, true, io.lines(), null)
        } catch (e: Exception) {
            History.record(this, flow.name, false, io.lines(), e.message)
            io.notifyError(e.message ?: "Flow failed")
        } finally {
            finishOne()
        }
    }

    private fun finishOne() {
        running--
        if (running <= 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    companion object {
        const val EXTRA_FLOW = "flow"
        const val EXTRA_KEYS = "keys"
        const val EXTRA_VALUES = "values"
        const val NOTIF_ID = 4711

        fun start(context: Context, flowId: String, extras: Map<String, String> = emptyMap()) {
            val i = Intent(context, RunnerService::class.java)
                .putExtra(EXTRA_FLOW, flowId)
                .putExtra(EXTRA_KEYS, extras.keys.toTypedArray())
                .putExtra(EXTRA_VALUES, extras.values.toTypedArray())
            try {
                context.startForegroundService(i)
            } catch (e: Exception) {
                try { context.startService(i) } catch (e2: Exception) { }
            }
        }
    }
}

/** FlowIO for background runs: nothing can be asked, so anything interactive becomes a notification. */
class HeadlessIO(private val context: Context, private val flowName: String) : FlowIO {

    private val lines = mutableListOf<LogLine>()

    fun lines(): List<LogLine> = lines

    override suspend fun ask(prompt: String, default: String, multiline: Boolean, numeric: Boolean): String? {
        if (default.isNotEmpty()) return default
        throw com.rishi.cascade.engine.FlowError(
            "\"" + prompt + "\" needs an answer, but this flow ran in the background. Give the step a default answer."
        )
    }

    override suspend fun choose(prompt: String, options: List<String>): Int? = 0

    override suspend fun alert(title: String, message: String, cancellable: Boolean): Boolean {
        notify(title, message)
        return true
    }

    override suspend fun showResult(title: String, value: Any?) {
        notify(title, V.text(value).take(400))
    }

    override fun log(line: LogLine) { lines.add(line) }

    override fun progress(stepIndex: Int, title: String) {}

    fun notifyError(message: String) = notify(flowName + " failed", message)

    private fun notify(title: String, text: String) {
        UiActions.ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        val n = Notification.Builder(context, UiActions.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()
        nm?.notify(System.currentTimeMillis().toInt(), n)
    }
}

/** A rolling record of background runs, so you can see what fired while you were not looking. */
object History {
    private const val MAX_BYTES = 200_000

    fun file(c: Context) = File(c.filesDir, "history.log")

    fun record(c: Context, flowName: String, ok: Boolean, lines: List<LogLine>, error: String?) {
        try {
            val f = file(c)
            if (f.length() > MAX_BYTES) f.writeText(f.readText().takeLast(MAX_BYTES / 2))
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val status = if (ok) "ok" else "FAILED"
            val detail = error?.let { " - " + it } ?: ""
            f.appendText(stamp + "\t" + status + "\t" + flowName + "\t" + lines.size + " steps" + detail + "\n")
        } catch (e: Exception) { }
    }

    fun read(c: Context): List<String> = try {
        file(c).takeIf { it.exists() }?.readLines()?.reversed()?.take(200) ?: emptyList()
    } catch (e: Exception) { emptyList() }

    fun clear(c: Context) { try { file(c).delete() } catch (e: Exception) { } }
}
