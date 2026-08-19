package com.rishi.cascade.ui

import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.rishi.cascade.actions.Registry
import com.rishi.cascade.store.FlowStore
import com.rishi.cascade.store.Templates
import com.rishi.cascade.trigger.History
import com.rishi.cascade.trigger.LockAdmin
import com.rishi.cascade.trigger.AppWatcherService
import com.rishi.cascade.trigger.NotificationWatcher
import com.rishi.cascade.trigger.Scheduler

private data class Access(
    val title: String,
    val why: String,
    val granted: Boolean,
    val open: () -> Unit
)

private fun hasPerm(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

private fun usageAccess(c: Context): Boolean = try {
    val ops = c.getSystemService(AppOpsManager::class.java)
    val mode = ops.unsafeCheckOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), c.packageName
    )
    mode == AppOpsManager.MODE_ALLOWED
} catch (e: Exception) { false }

private fun ignoringBattery(c: Context): Boolean = try {
    c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)
} catch (e: Exception) { false }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { FlowStore.get(context) }
    var refresh by remember { mutableStateOf(0) }
    var showHistory by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh++ }

    fun openSettings(action: String, withPackage: Boolean = false) {
        try {
            val i = Intent(action)
            if (withPackage) i.data = Uri.parse("package:" + context.packageName)
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    val runtimePerms = listOf(
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.RECORD_AUDIO,
        android.Manifest.permission.CAMERA,
        android.Manifest.permission.READ_CONTACTS,
        android.Manifest.permission.SEND_SMS,
        android.Manifest.permission.CALL_PHONE
    ) + (if (Build.VERSION.SDK_INT >= 33) listOf(android.Manifest.permission.POST_NOTIFICATIONS) else emptyList())

    val items = remember(refresh) {
        listOf(
            Access("Notifications",
                "Lets flows post notifications and show background results.",
                Build.VERSION.SDK_INT < 33 || hasPerm(context, android.Manifest.permission.POST_NOTIFICATIONS)
            ) { permLauncher.launch(runtimePerms.toTypedArray()) },
            Access("Location",
                "Current Location, address lookup and the Wi-Fi network name.",
                hasPerm(context, android.Manifest.permission.ACCESS_FINE_LOCATION)
            ) { permLauncher.launch(runtimePerms.toTypedArray()) },
            Access("Microphone",
                "Record Audio.",
                hasPerm(context, android.Manifest.permission.RECORD_AUDIO)
            ) { permLauncher.launch(runtimePerms.toTypedArray()) },
            Access("Camera",
                "Take Photo. Android only allows this while Cascade is on screen or running as a foreground service.",
                hasPerm(context, android.Manifest.permission.CAMERA)
            ) { permLauncher.launch(runtimePerms.toTypedArray()) },
            Access("Contacts, SMS and phone",
                "Find Contact, sending a text silently and dialling directly.",
                hasPerm(context, android.Manifest.permission.READ_CONTACTS)
            ) { permLauncher.launch(runtimePerms.toTypedArray()) },
            Access("Modify system settings",
                "Brightness, screen timeout and auto-rotate.",
                Settings.System.canWrite(context)
            ) { openSettings(Settings.ACTION_MANAGE_WRITE_SETTINGS, withPackage = true) },
            Access("Do Not Disturb access",
                "Silent ringer mode and the Do Not Disturb action.",
                context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
            ) { openSettings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) },
            Access("Notification access",
                "The \"a notification arrives\" trigger and Now Playing.",
                NotificationWatcher.isEnabled(context)
            ) { openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },
            Access("Accessibility",
                "The \"when an app opens\" trigger. On ColorOS look under Installed services. " +
                        "Cascade only receives which app came to the front - it cannot read screen content.",
                // the switch being listed as on is not enough: ColorOS can leave it unbound,
                // so trust only an actually connected service
                AppWatcherService.connected
            ) { openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
            Access("Usage access",
                "The Current App action, and the \"when an app opens\" trigger when accessibility " +
                        "is unavailable - which is the case on most Realme and Xiaomi builds.",
                usageAccess(context)
            ) { openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS) },
            Access("Device admin",
                "Only the Lock the Screen action. Cascade asks for force-lock and nothing else, " +
                        "and you can revoke it on the same screen.",
                LockAdmin.isActive(context)
            ) {
                if (LockAdmin.isActive(context)) LockAdmin.disable(context)
                else context.startActivity(LockAdmin.enableIntent(context))
            },
            Access("Exact alarms",
                "Time triggers fire on the minute instead of whenever the system feels like it.",
                Scheduler.canBeExact(context)
            ) { openSettings("android.settings.REQUEST_SCHEDULE_EXACT_ALARM") },
            Access("Ignore battery optimisation",
                "Realme and other OEM builds otherwise freeze background triggers.",
                ignoringBattery(context)
            ) { openSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    "Permissions",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
                Text(
                    "Grant only what your flows actually use. Tap a row to open the right screen, then come back.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(items) { a ->
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable { a.open(); refresh++ }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(26.dp).clip(CircleShape)
                            .background(
                                if (a.granted) Color(0xFF10B981).copy(alpha = 0.18f)
                                else MaterialTheme.colorScheme.errorContainer
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (a.granted) Icons.Filled.Check else Icons.Filled.Close,
                            null,
                            Modifier.size(16.dp),
                            tint = if (a.granted) Color(0xFF10B981) else MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.title, style = MaterialTheme.typography.bodyMedium)
                        Text(a.why, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            item {
                Spacer(Modifier.height(6.dp))
                Text("Flows", style = MaterialTheme.typography.titleMedium)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { Templates.install(store) }) { Text("Add example flows") }
                    TextButton(onClick = { showHistory = !showHistory }) {
                        Text(if (showHistory) "Hide history" else "Background run history")
                    }
                }
            }
            if (showHistory) {
                val lines = History.read(context)
                if (lines.isEmpty()) {
                    item {
                        Text("Nothing has run in the background yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(lines) { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    )
                }
                item {
                    TextButton(onClick = { History.clear(context); showHistory = false }) {
                        Text("Clear history")
                    }
                }
            }

            item {
                Spacer(Modifier.height(10.dp))
                Text("About", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Cascade 1.0 - " + Registry.all().size + " actions, " +
                            com.rishi.cascade.trigger.TriggerTypes.ALL.size + " triggers.\n" +
                            "Flows are stored as plain JSON in this app's private folder. Nothing leaves the phone " +
                            "unless one of your own steps sends it somewhere.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
