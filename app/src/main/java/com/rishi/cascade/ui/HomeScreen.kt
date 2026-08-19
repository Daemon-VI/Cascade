package com.rishi.cascade.ui

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rishi.cascade.model.Flow
import com.rishi.cascade.store.FlowStore
import com.rishi.cascade.store.Templates

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpen: (String) -> Unit,
    onSettings: () -> Unit
) {
    val context = LocalContext.current
    val store = remember { FlowStore.get(context) }
    val flows by store.flows.collectAsState()
    var running by remember { mutableStateOf<Flow?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Cascade", style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (flows.isEmpty()) "Build automations by dragging actions together"
                            else plural(flows.size, "flow"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    val f = Flow(name = "New flow")
                    store.save(f)
                    onOpen(f.id)
                },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("New flow") }
            )
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (flows.isEmpty()) {
                EmptyHome(
                    onCreate = {
                        val f = Flow(name = "New flow")
                        store.save(f)
                        onOpen(f.id)
                    },
                    onExamples = { Templates.install(store) }
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(flows, key = { it.id }) { flow ->
                        FlowCard(
                            flow = flow,
                            onOpen = { onOpen(flow.id) },
                            onRun = { running = flow },
                            onDuplicate = { store.duplicate(flow.id) },
                            onPin = { pinShortcut(context, flow) },
                            onDelete = { store.delete(flow.id) }
                        )
                    }
                }
            }
        }
    }

    running?.let { f ->
        RunOverlay(f) { running = null }
    }
}

@Composable
private fun FlowCard(
    flow: Flow,
    onOpen: () -> Unit,
    onRun: () -> Unit,
    onDuplicate: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val color = flowColor(flow.color)

    Card(
        modifier = Modifier.fillMaxWidth().height(132.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(Modifier.fillMaxSize().clickable { onOpen() }.padding(12.dp)) {
            Column(Modifier.fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(color),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(iconFor(flow.icon), null, Modifier.size(21.dp), tint = Color.White)
                    }
                    Spacer(Modifier.weight(1f))
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.size(28.dp)) {
                            Text("...", style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.outline)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Run") }, onClick = { menu = false; onRun() })
                            DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; onDuplicate() })
                            DropdownMenuItem(text = { Text("Add to home screen") }, onClick = { menu = false; onPin() })
                            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    flow.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        plural(flow.steps.size, "step") +
                                (if (flow.triggers.isNotEmpty()) "  -  " + plural(flow.triggers.size, "trigger") else ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        Modifier.size(32.dp).clip(RoundedCornerShape(10.dp))
                            .background(color.copy(alpha = 0.15f))
                            .clickable { onRun() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.PlayArrow, "Run", Modifier.size(18.dp), tint = color)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHome(onCreate: () -> Unit, onExamples: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(72.dp).clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Bolt, null, Modifier.size(40.dp), tint = Color.White)
        }
        Spacer(Modifier.height(16.dp))
        Text("No flows yet", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            "A flow is a list of actions that run one after another. Drag actions in, wire them together with variables, then run it from here, a home screen icon, a quick settings tile or a trigger.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onExamples) {
                Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add 6 example flows")
            }
            TextButton(onClick = onCreate) { Text("Start from scratch") }
        }
    }
}
