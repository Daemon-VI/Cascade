package com.rishi.cascade.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.rishi.cascade.actions.Registry
import com.rishi.cascade.engine.Blocks
import com.rishi.cascade.model.ActionDef
import com.rishi.cascade.model.BlockKind
import com.rishi.cascade.model.Flow
import com.rishi.cascade.model.Step
import com.rishi.cascade.store.FlowStore
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Which extra steps come along when a block-opening action is added. */
private fun stepsFor(def: ActionDef): List<Step> {
    fun s(id: String) = Step(actionId = id, params = Registry.defaults(id))
    return when (def.id) {
        "flow.if" -> listOf(s("flow.if"), s("flow.else"), s("flow.endif"))
        "flow.try" -> listOf(s("flow.try"), s("flow.catch"), s("flow.endtry"))
        "flow.repeat" -> listOf(s("flow.repeat"), s("flow.endrepeat"))
        "flow.foreach" -> listOf(s("flow.foreach"), s("flow.endforeach"))
        "flow.while" -> listOf(s("flow.while"), s("flow.endwhile"))
        else -> listOf(s(def.id))
    }
}

private fun summaryFor(step: Step): String {
    val def = Registry.def(step.actionId) ?: return "Unknown action"
    val raw = def.summary
    val text = if (raw.isNotBlank()) {
        Regex("%(\\w+)%").replace(raw) { m ->
            val key = m.groupValues[1]
            (step.params[key] ?: def.spec(key)?.default ?: "").ifBlank { "..." }
        }
    } else {
        def.params.firstNotNullOfOrNull { p -> step.params[p.key]?.takeIf { it.isNotBlank() } }
            ?: def.description
    }
    val flat = text.replace("\n", " ").trim()
    return if (flat.length > 90) flat.take(90) + "..." else flat
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(flowId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { FlowStore.get(context) }
    val flow = remember(flowId) { store.load(flowId) ?: Flow().also { store.save(it) } }
    val steps = remember(flowId) { mutableStateListOf<Step>().also { it.addAll(flow.steps) } }

    var expandedId by remember { mutableStateOf<String?>(null) }
    var paletteOpen by remember { mutableStateOf(steps.isEmpty()) }
    var showRun by remember { mutableStateOf(false) }
    var showTriggers by remember { mutableStateOf(false) }
    var showAppearance by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf(flow.name) }
    var renaming by remember { mutableStateOf(false) }

    val drag = remember { DragState() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScopeCompat()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current

    // Step params are plain maps rather than snapshot state, so edits are announced by hand.
    var revision by remember { mutableStateOf(0) }

    fun persist() {
        flow.steps.clear()
        flow.steps.addAll(steps)
        flow.name = title
        store.save(flow)
        revision++
    }

    fun insert(def: ActionDef, at: Int) {
        val block = stepsFor(def)
        val index = at.coerceIn(0, steps.size)
        steps.addAll(index, block)
        persist()
        expandedId = block.first().id
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch { listState.animateScrollToItem(index.coerceAtMost(steps.size - 1)) }
    }

    fun removeAt(index: Int) {
        if (index !in steps.indices) return
        val def = Registry.def(steps[index].actionId)
        if (def?.block == BlockKind.BEGIN) {
            // drop the block markers but keep whatever was inside
            val pairs = Blocks.pair(steps)
            val p = pairs[index]
            val victims = listOfNotNull(p?.end?.takeIf { it in steps.indices }, p?.middle?.takeIf { it >= 0 }, index)
                .sortedDescending()
            victims.forEach { steps.removeAt(it) }
        } else if (def?.block == BlockKind.END || def?.block == BlockKind.MIDDLE) {
            steps.removeAt(index)
        } else {
            steps.removeAt(index)
        }
        persist()
    }

    fun moveBlock(from: Int, count: Int, to: Int) {
        if (from !in steps.indices) return
        if (to in from..(from + count)) return
        val slice = ArrayList(steps.subList(from, (from + count).coerceAtMost(steps.size)))
        repeat(slice.size) { steps.removeAt(from) }
        val target = (if (to > from) to - slice.size else to).coerceIn(0, steps.size)
        steps.addAll(target, slice)
        persist()
    }

    drag.onDrop = { payload, target ->
        when (payload) {
            is DragPayload.New -> {
                insert(payload.def, target)
                paletteOpen = false   // safe here: the gesture has already finished
            }
            is DragPayload.Move -> moveBlock(payload.from, payload.count, target)
        }
    }
    drag.stepCount = steps.size

    // Nudge the list when a drag reaches its top or bottom edge.
    LaunchedEffect(drag.dragging) {
        while (drag.dragging) {
            val y = drag.pointer.y
            val edge = with(density) { 90.dp.toPx() }
            val speed = with(density) { 10.dp.toPx() }
            if (y < drag.listTop + edge) listState.scrollBy(-speed)
            else if (y > drag.listBottom - edge) listState.scrollBy(speed)
            awaitFrame()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Column(Modifier.clickable { renaming = true }) {
                            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(
                                plural(steps.size, "step") +
                                        (if (flow.triggers.isNotEmpty()) "  -  " + plural(flow.triggers.size, "trigger") else ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { persist(); onBack() }) {
                            Icon(Icons.Filled.ArrowBack, "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { persist(); showRun = true }, enabled = steps.isNotEmpty()) {
                            Icon(Icons.Filled.PlayArrow, "Run", tint = MaterialTheme.colorScheme.primary)
                        }
                        Box {
                            IconButton(onClick = { showMenu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(text = { Text("Triggers") },
                                    onClick = { showMenu = false; showTriggers = true })
                                DropdownMenuItem(text = { Text("Icon and colour") },
                                    onClick = { showMenu = false; showAppearance = true })
                                DropdownMenuItem(text = { Text("Rename") },
                                    onClick = { showMenu = false; renaming = true })
                                DropdownMenuItem(text = { Text("Delete flow") },
                                    onClick = { showMenu = false; confirmDelete = true })
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            }
        ) { pad ->
            Box(
                Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .onGloballyPositioned {
                        val b = it.boundsInRoot()
                        drag.listTop = b.top
                        drag.listBottom = b.bottom
                    }
            ) {
                if (steps.isEmpty()) {
                    EmptyEditorHint(Modifier.align(Alignment.Center))
                }
                val indents = remember(steps.toList()) { Blocks.indents(steps) }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 220.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(steps, key = { _, s -> s.id }) { index, step ->
                        Column(
                            Modifier.onGloballyPositioned { drag.rows[index] = it.boundsInRoot() }
                        ) {
                            InsertionLine(visible = drag.showLineAt(index))
                            StepRow(
                                step = step,
                                index = index,
                                indent = indents.getOrElse(index) { 0 },
                                revision = revision,
                                expanded = expandedId == step.id,
                                drag = drag,
                                variables = variablesBefore(steps, index),
                                onToggle = { expandedId = if (expandedId == step.id) null else step.id },
                                onChange = { persist() },
                                onDelete = { removeAt(index) },
                                onDuplicate = {
                                    steps.add(index + 1, step.copyNew()); persist()
                                },
                                blockSize = { blockSize(steps, index) }
                            )
                        }
                    }
                    item {
                        InsertionLine(visible = drag.showLineAt(steps.size))
                    }
                }
            }
        }

        // The palette lives in the same coordinate space, so actions can be dragged out of it.
        ActionPalette(
            drag = drag,
            expanded = paletteOpen,
            onExpandedChange = { paletteOpen = it },
            onPick = { def -> insert(def, steps.size) },
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        DragGhost(drag)
    }

    if (renaming) {
        var draft by remember { mutableStateOf(title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Flow name") },
            text = {
                OutlinedTextField(value = draft, onValueChange = { draft = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    title = draft.ifBlank { "Untitled flow" }
                    persist(); renaming = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete " + title + "?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    store.delete(flow.id); confirmDelete = false; onBack()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }

    if (showAppearance) {
        AppearanceDialog(flow) { showAppearance = false; store.save(flow) }
    }

    if (showTriggers) {
        TriggerDialog(flow) { showTriggers = false; store.save(flow) }
    }

    if (showRun) {
        RunOverlay(flow) { showRun = false }
    }
}

/** How many steps move together when this one is dragged (a whole block for block openers). */
private fun blockSize(steps: List<Step>, index: Int): Int {
    val def = Registry.def(steps[index].actionId) ?: return 1
    if (def.block != BlockKind.BEGIN) return 1
    val p = Blocks.pair(steps)[index] ?: return 1
    val end = if (p.end in steps.indices) p.end else steps.size - 1
    return (end - index + 1).coerceAtLeast(1)
}

/** Variables a step at this index can refer to. */
private fun variablesBefore(steps: List<Step>, index: Int): List<VarSuggestion> {
    val out = mutableListOf(
        VarSuggestion("last", "Result of the step above"),
        VarSuggestion("input", "Whatever started this flow")
    )
    steps.take(index).forEach { s ->
        val def = Registry.def(s.actionId)
        s.outputName?.takeIf { it.isNotBlank() }?.let {
            out.add(VarSuggestion(it, "From " + (def?.title ?: s.actionId)))
        }
        when (s.actionId) {
            "flow.setvar" -> s.params["name"]?.takeIf { it.isNotBlank() }
                ?.let { out.add(VarSuggestion(it, "Variable you set")) }
            "flow.repeat" -> out.add(VarSuggestion(s.params["indexVar"] ?: "index", "Repeat counter"))
            "flow.foreach" -> {
                out.add(VarSuggestion(s.params["itemVar"] ?: "item", "Current item"))
                out.add(VarSuggestion(s.params["indexVar"] ?: "index", "Current position"))
            }
            "flow.try" -> out.add(VarSuggestion("error", "Message from the failed step"))
            "ui.menu" -> out.add(VarSuggestion("chosenIndex", "Which menu row was picked"))
        }
    }
    return out.distinctBy { it.name }
}

@Composable
private fun InsertionLine(visible: Boolean) {
    AnimatedVisibility(visible) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

@Composable
private fun StepRow(
    step: Step,
    index: Int,
    indent: Int,
    revision: Int,
    expanded: Boolean,
    drag: DragState,
    variables: List<VarSuggestion>,
    onToggle: () -> Unit,
    onChange: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    blockSize: () -> Int
) {
    val def = Registry.def(step.actionId)
    val haptics = LocalHapticFeedback.current
    val beingDragged = (drag.payload as? DragPayload.Move)?.from == index

    val handleMod = dragSource(
        state = drag,
        payload = {
            DragPayload.Move(index, blockSize(), def?.title ?: step.actionId, def?.icon ?: "Bolt")
        },
        onStarted = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    )

    Row(Modifier.fillMaxWidth()) {
        if (indent > 0) {
            Box(
                Modifier
                    .width((indent * 14).dp)
                    .height(if (expanded) 56.dp else 52.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Box(
                    Modifier
                        .width(2.dp)
                        .height(if (expanded) 56.dp else 52.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                )
                Spacer(Modifier.width(6.dp))
            }
        }
        Card(
            modifier = Modifier
                .weight(1f)
                .alpha(if (beingDragged) 0.35f else if (step.enabled) 1f else 0.55f),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = if (expanded) 4.dp else 1.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(handleMod)
                    .clickable { onToggle() }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(categoryColor(def?.category ?: "")),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(iconFor(def?.icon ?: "Bolt"), null, Modifier.size(18.dp), tint = Color.White)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        def?.title ?: step.actionId,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    val sub = remember(revision, step.id, step.actionId) { summaryFor(step) }
                    if (sub.isNotBlank()) {
                        Text(
                            sub,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (!step.enabled) {
                    Icon(Icons.Filled.VisibilityOff, "Disabled", Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.width(6.dp))
                }
                Icon(
                    Icons.Filled.DragIndicator, null, Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null,
                    Modifier.size(20.dp), tint = MaterialTheme.colorScheme.outline
                )
            }

            if (expanded && def != null) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                    // when there are no params the collapsed row already shows the description
                    if (def.description.isNotEmpty() && def.params.isNotEmpty()) {
                        Text(
                            def.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                    def.params.forEach { spec ->
                        val visible = spec.showIf?.let { (key, allowed) ->
                            val current = step.params[key] ?: def.spec(key)?.default ?: ""
                            current in allowed
                        } ?: true
                        if (visible) {
                            ParamField(
                                spec = spec,
                                value = step.params[spec.key] ?: spec.default,
                                variables = variables
                            ) { newValue ->
                                step.params[spec.key] = newValue
                                onChange()
                            }
                        }
                    }
                    if (def.output != null) {
                        var name by remember(step.id) { mutableStateOf(step.outputName ?: "") }
                        OutlinedTextField(
                            value = name,
                            onValueChange = {
                                name = it
                                step.outputName = it.trim().takeIf { t -> t.isNotEmpty() }
                                onChange()
                            },
                            label = { Text("Save result as", style = MaterialTheme.typography.labelSmall) },
                            placeholder = { Text(def.output, style = MaterialTheme.typography.bodySmall) },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = {
                            step.enabled = !step.enabled
                            onChange()
                        }) { Text(if (step.enabled) "Disable" else "Enable") }
                        IconButton(onClick = onDuplicate) {
                            Icon(Icons.Filled.ContentCopy, "Duplicate", Modifier.size(18.dp))
                        }
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Filled.Delete, "Delete", Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

/** The card that follows your finger. */
@Composable
private fun DragGhost(drag: DragState) {
    val payload = drag.payload ?: return
    val density = LocalDensity.current
    val title = when (payload) {
        is DragPayload.New -> payload.def.title
        is DragPayload.Move -> payload.title
    }
    val icon = when (payload) {
        is DragPayload.New -> payload.def.icon
        is DragPayload.Move -> payload.icon
    }
    val color = when (payload) {
        is DragPayload.New -> categoryColor(payload.def.category)
        is DragPayload.Move -> MaterialTheme.colorScheme.primary
    }
    val extra = (payload as? DragPayload.Move)?.count?.takeIf { it > 1 }

    Box(
        Modifier
            .offset {
                IntOffset(
                    (drag.pointer.x - drag.grab.x).roundToInt(),
                    (drag.pointer.y - drag.grab.y).roundToInt()
                )
            }
            .width(with(density) { drag.ghostWidth.toDp().coerceAtLeast(180.dp) })
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 14.dp,
            border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        ) {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(color),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(iconFor(icon), null, Modifier.size(17.dp), tint = Color.White)
                }
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (extra != null) {
                    Spacer(Modifier.width(6.dp))
                    Text("+" + (extra - 1), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun EmptyEditorHint(modifier: Modifier = Modifier) {
    Column(
        modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Filled.Bolt, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(10.dp))
        Text("This flow is empty", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Open the action library below, then tap an action to add it or drag it up here to drop it exactly where you want.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun rememberCoroutineScopeCompat() = androidx.compose.runtime.rememberCoroutineScope()
