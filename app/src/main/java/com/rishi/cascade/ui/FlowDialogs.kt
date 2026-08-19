package com.rishi.cascade.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon as AndroidIcon
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.rishi.cascade.R
import com.rishi.cascade.model.Flow
import com.rishi.cascade.model.Trigger
import com.rishi.cascade.trigger.NotificationWatcher
import com.rishi.cascade.trigger.RunFlowActivity
import com.rishi.cascade.trigger.Scheduler
import com.rishi.cascade.trigger.TriggerTypes

@Composable
fun TriggerDialog(flow: Flow, onClose: () -> Unit) {
    val context = LocalContext.current
    var adding by remember { mutableStateOf(false) }
    var version by remember { mutableStateOf(0) }

    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(18.dp)) {
                Text("Triggers", style = MaterialTheme.typography.titleLarge)
                Text(
                    "What makes " + flow.name + " run on its own.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))

                // reading `version` here re-runs this block whenever a trigger is edited
                androidx.compose.runtime.key(version) {
                    if (flow.triggers.isEmpty() && !adding) {
                        Text(
                            "No triggers yet. This flow runs when you tap it, and nothing else.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        items(flow.triggers.toList(), key = { it.id }) { t ->
                            TriggerRow(context, flow, t, onChanged = { version++ }) {
                                flow.triggers.remove(t)
                                Scheduler.cancel(context, flow.id, t.id)
                                version++
                            }
                        }
                        if (adding) {
                            items(TriggerTypes.ALL, key = { it.id }) { type ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable {
                                            val t = Trigger(type = type.id)
                                            type.configHint.takeIf { it.isNotEmpty() }
                                                ?.let { t.config["value"] = it }
                                            flow.triggers.add(t)
                                            adding = false
                                            version++
                                            Scheduler.rescheduleAll(context)
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(iconFor(type.icon), null, Modifier.size(22.dp),
                                        tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text(type.title, style = MaterialTheme.typography.bodyMedium)
                                        Text(type.description, style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }

                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { adding = !adding }) {
                        Icon(Icons.Filled.Add, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (adding) "Cancel" else "Add trigger")
                    }
                    TextButton(onClick = { Scheduler.rescheduleAll(context); onClose() }) { Text("Done") }
                }
            }
        }
    }
}

@Composable
private fun TriggerRow(
    context: Context,
    flow: Flow,
    t: Trigger,
    onChanged: () -> Unit,
    onDelete: () -> Unit
) {
    val type = TriggerTypes.get(t.type)
    var value by remember(t.id) { mutableStateOf(t.config["value"] ?: "") }
    var contains by remember(t.id) { mutableStateOf(t.config["contains"] ?: "") }

    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(iconFor(type?.icon ?: "Bolt"), null, Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(type?.title ?: t.type, style = MaterialTheme.typography.bodyMedium)
                Text(type?.description ?: "", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = t.enabled, onCheckedChange = {
                t.enabled = it
                if (!it) Scheduler.cancel(context, flow.id, t.id) else Scheduler.schedule(context, flow, t)
                onChanged()
            })
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, "Remove", Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error)
            }
        }

        if (type != null && type.configLabel.isNotEmpty()) {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it; t.config["value"] = it; onChanged() },
                label = { Text(type.configLabel, style = MaterialTheme.typography.labelSmall) },
                placeholder = { Text(type.configHint, style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
        }
        if (t.type == "notification") {
            OutlinedTextField(
                value = contains,
                onValueChange = { contains = it; t.config["contains"] = it; onChanged() },
                label = { Text("Only when the text contains", style = MaterialTheme.typography.labelSmall) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
        }

        when {
            t.type == "shortcut" -> TextButton(onClick = { pinShortcut(context, flow) }) {
                Icon(Icons.Filled.OpenInNew, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add icon to home screen")
            }
            t.type == "tile" -> Text(
                "Pull down quick settings, edit the tiles and drag Cascade in.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            type?.needs == "notification_listener" && !NotificationWatcher.isEnabled(context) ->
                TextButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }) { Text("Grant notification access") }
            type?.needs == "exact_alarm" && !Scheduler.canBeExact(context) ->
                TextButton(onClick = {
                    try {
                        context.startActivity(
                            Intent("android.settings.REQUEST_SCHEDULE_EXACT_ALARM")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (e: Exception) {
                        Toast.makeText(context, "Not available on this phone", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Allow exact alarms (more reliable timing)") }
        }
    }
}

fun pinShortcut(context: Context, flow: Flow) {
    val sm = context.getSystemService(ShortcutManager::class.java)
    if (sm == null || !sm.isRequestPinShortcutSupported) {
        Toast.makeText(context, "Your launcher does not support pinned shortcuts", Toast.LENGTH_LONG).show()
        return
    }
    val info = ShortcutInfo.Builder(context, "flow-" + flow.id)
        .setShortLabel(flow.name.take(20))
        .setLongLabel(flow.name)
        .setIcon(AndroidIcon.createWithResource(context, R.mipmap.ic_launcher))
        .setIntent(RunFlowActivity.intentFor(context, flow.id))
        .build()
    try {
        sm.requestPinShortcut(info, null)
    } catch (e: Exception) {
        Toast.makeText(context, "Could not add the shortcut", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun AppearanceDialog(flow: Flow, onClose: () -> Unit) {
    var icon by remember { mutableStateOf(flow.icon) }
    var color by remember { mutableStateOf(flow.color) }

    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(18.dp)) {
                Text("Icon and colour", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FlowColors.forEachIndexed { i, c ->
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).background(c)
                                .border(
                                    width = if (color == i) 3.dp else 0.dp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    shape = CircleShape
                                )
                                .clickable { color = i; flow.color = i }
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    modifier = Modifier.heightIn(max = 220.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(FlowIcons) { name ->
                        Box(
                            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (icon == name) flowColor(color)
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable { icon = name; flow.icon = name },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                iconFor(name), null, Modifier.size(22.dp),
                                tint = if (icon == name) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClose) { Text("Done") }
                }
            }
        }
    }
}
