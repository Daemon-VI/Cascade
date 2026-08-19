package com.rishi.cascade.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rishi.cascade.actions.Registry
import com.rishi.cascade.model.ActionDef
import com.rishi.cascade.model.BlockKind
import com.rishi.cascade.model.Cat

/** Colour used for an action's badge, keyed off its category. */
fun categoryColor(category: String): Color = when (category) {
    Cat.FLOWCTRL -> Color(0xFF5B3DF5)
    Cat.UI -> Color(0xFFEC4899)
    Cat.TEXT -> Color(0xFF0EA5E9)
    Cat.MATH -> Color(0xFF14B8A6)
    Cat.DATE -> Color(0xFF8B5CF6)
    Cat.DATA -> Color(0xFF6366F1)
    Cat.DEVICE -> Color(0xFFF59E0B)
    Cat.APPS -> Color(0xFF10B981)
    Cat.NET -> Color(0xFF3B82F6)
    Cat.FILES -> Color(0xFFA16207)
    Cat.MEDIA -> Color(0xFFEF4444)
    Cat.LOCATION -> Color(0xFF059669)
    Cat.COMMS -> Color(0xFFDB2777)
    Cat.SCRIPT -> Color(0xFF475569)
    else -> Color(0xFF64748B)
}

@Composable
fun ActionPalette(
    drag: DragState,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onPick: (ActionDef) -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    val haptics = LocalHapticFeedback.current

    val results = remember(query, category) {
        // block closers and Otherwise/On Error come along automatically with their opener,
        // so they are never offered on their own
        val base = (if (query.isBlank()) Registry.all() else Registry.search(query))
            .filter { it.block != BlockKind.END && it.block != BlockKind.MIDDLE }
        if (category == null) base else base.filter { it.category == category }
    }

    Surface(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 12.dp,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp)) {

            Row(
                Modifier.fillMaxWidth().height(44.dp).clickable { onExpandedChange(!expanded) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.Add, null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (expanded) "Action library" else "Add an action",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    Registry.all().size.toString() + " actions",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (expanded) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search actions", style = MaterialTheme.typography.bodyMedium) },
                    leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(18.dp)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = category == null,
                        onClick = { category = null },
                        label = { Text("All", style = MaterialTheme.typography.labelSmall) }
                    )
                    Cat.ORDER.forEach { c ->
                        FilterChip(
                            selected = category == c,
                            onClick = { category = if (category == c) null else c },
                            label = { Text(c, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Text(
                    "Tap to add at the end, or press and drag an action onto the canvas.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                LazyColumn(
                    Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(results, key = { it.id }) { def ->
                        PaletteRow(def, drag, haptics, onPick)
                    }
                    if (results.isEmpty()) {
                        items(listOf("empty")) {
                            Text(
                                "Nothing matches \"" + query + "\"",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaletteRow(
    def: ActionDef,
    drag: DragState,
    haptics: androidx.compose.ui.hapticfeedback.HapticFeedback,
    onPick: (ActionDef) -> Unit
) {
    // The palette must stay open while dragging: collapsing it here would remove this row
    // from the composition and the system would cancel the gesture mid-drag.
    val dragMod = dragSource(
        state = drag,
        payload = { DragPayload.New(def) },
        onStarted = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .then(dragMod)
            .clickable { onPick(def) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(categoryColor(def.category)),
            contentAlignment = Alignment.Center
        ) {
            Icon(iconFor(def.icon), null, Modifier.size(17.dp), tint = Color.White)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(def.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(
                if (def.description.isNotEmpty()) def.description else def.category,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            Icons.Filled.DragIndicator, null, Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.outline
        )
    }
}
