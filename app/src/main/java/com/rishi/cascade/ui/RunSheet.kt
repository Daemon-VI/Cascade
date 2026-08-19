package com.rishi.cascade.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rishi.cascade.engine.Engine
import com.rishi.cascade.engine.FlowIO
import com.rishi.cascade.engine.FlowStopped
import com.rishi.cascade.engine.LogLine
import com.rishi.cascade.engine.V
import com.rishi.cascade.model.Flow
import com.rishi.cascade.store.FlowStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Anything the flow needs a human for, held until the dialog answers it. */
sealed interface Pending {
    data class Ask(
        val prompt: String, val default: String, val multiline: Boolean, val numeric: Boolean,
        val reply: CompletableDeferred<String?>
    ) : Pending

    data class Choose(
        val prompt: String, val options: List<String>, val reply: CompletableDeferred<Int?>
    ) : Pending

    data class Alert(
        val title: String, val message: String, val cancellable: Boolean,
        val reply: CompletableDeferred<Boolean>
    ) : Pending

    data class Result(
        val title: String, val value: String, val reply: CompletableDeferred<Unit>
    ) : Pending
}

class UiFlowIO : FlowIO {
    val logs = mutableStateListOf<LogLine>()
    var pending by mutableStateOf<Pending?>(null)
    var currentStep by mutableStateOf("")

    override suspend fun ask(prompt: String, default: String, multiline: Boolean, numeric: Boolean): String? {
        val d = CompletableDeferred<String?>()
        pending = Pending.Ask(prompt, default, multiline, numeric, d)
        val out = d.await()
        pending = null
        return out
    }

    override suspend fun choose(prompt: String, options: List<String>): Int? {
        val d = CompletableDeferred<Int?>()
        pending = Pending.Choose(prompt, options, d)
        val out = d.await()
        pending = null
        return out
    }

    override suspend fun alert(title: String, message: String, cancellable: Boolean): Boolean {
        val d = CompletableDeferred<Boolean>()
        pending = Pending.Alert(title, message, cancellable, d)
        val out = d.await()
        pending = null
        return out
    }

    override suspend fun showResult(title: String, value: Any?) {
        val d = CompletableDeferred<Unit>()
        pending = Pending.Result(title, V.text(value), d)
        d.await()
        pending = null
    }

    override fun log(line: LogLine) { logs.add(line) }

    override fun progress(stepIndex: Int, title: String) { currentStep = title }
}

/** Unwraps the cause chain so the dialog shows something you can act on. */
private fun describe(t: Throwable): String {
    val parts = mutableListOf<String>()
    var cur: Throwable? = t
    var depth = 0
    while (cur != null && depth < 4) {
        parts.add((cur.message ?: cur.javaClass.simpleName) + " (" + cur.javaClass.simpleName + ")")
        cur = cur.cause
        depth++
    }
    return parts.joinToString(" | caused by ")
}

@Composable
fun RunOverlay(flow: Flow, onClose: () -> Unit) {
    val context = LocalContext.current
    val io = remember { UiFlowIO() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var engine by remember { mutableStateOf<Engine?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var status by remember { mutableStateOf("running") }
    var finalValue by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf("") }

    LaunchedEffect(flow.id) {
        val e = Engine(context, flow, io)
        engine = e
        job = scope.launch(Dispatchers.Default) {
            try {
                val out = e.run()
                finalValue = V.text(out)
                status = "done"
                FlowStore.get(context).markRun(flow.id)
            } catch (stop: FlowStopped) {
                status = "stopped"
                errorText = if (stop.silent) "" else (stop.message ?: "")
            } catch (t: Throwable) {
                status = "failed"
                android.util.Log.e("Cascade", "flow failed", t)
                errorText = describe(t)
            }
        }
    }

    LaunchedEffect(io.logs.size) {
        if (io.logs.isNotEmpty()) listState.animateScrollToItem(io.logs.size - 1)
    }

    Dialog(
        onDismissRequest = { if (status != "running") onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Surface(
            Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.86f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (status) {
                        "running" -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        "failed" -> Icon(Icons.Filled.Error, null, tint = MaterialTheme.colorScheme.error)
                        else -> Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF10B981))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(flow.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            when (status) {
                                "running" -> io.currentStep.ifBlank { "Starting..." }
                                "done" -> "Finished - " + io.logs.size + " steps"
                                "stopped" -> "Stopped" + (if (errorText.isNotEmpty()) ": " + errorText else "")
                                else -> "Failed"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    itemsIndexed(io.logs) { _, line ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Box(
                                Modifier.size(6.dp)
                                    .background(
                                        when (line.level) {
                                            2 -> MaterialTheme.colorScheme.error
                                            1 -> MaterialTheme.colorScheme.primary
                                            else -> MaterialTheme.colorScheme.outline
                                        },
                                        RoundedCornerShape(3.dp)
                                    )
                                    .align(Alignment.CenterVertically)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    line.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium
                                )
                                if (line.detail.isNotBlank()) {
                                    Text(
                                        line.detail,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    if (status != "running" && finalValue.isNotBlank()) {
                        itemsIndexed(listOf(finalValue)) { _, v ->
                            Column(
                                Modifier.fillMaxWidth().padding(top = 10.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primaryContainer,
                                        RoundedCornerShape(10.dp)
                                    )
                                    .padding(10.dp)
                            ) {
                                Text("Result", style = MaterialTheme.typography.labelSmall)
                                Text(v.take(2000), style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                    if (status == "failed") {
                        itemsIndexed(listOf(errorText)) { _, v ->
                            Column(
                                Modifier.fillMaxWidth().padding(top = 10.dp)
                                    .background(
                                        MaterialTheme.colorScheme.errorContainer,
                                        RoundedCornerShape(10.dp)
                                    )
                                    .padding(10.dp)
                            ) {
                                Text("What went wrong", style = MaterialTheme.typography.labelSmall)
                                Text(v, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (status == "running") {
                        TextButton(onClick = {
                            engine?.cancel()
                            job?.cancel()
                            status = "stopped"
                        }) { Text("Stop") }
                    } else {
                        TextButton(onClick = {
                            io.logs.clear()
                            finalValue = ""; errorText = ""; status = "running"
                            val e = Engine(context, flow, io)
                            engine = e
                            job = scope.launch(Dispatchers.Default) {
                                try {
                                    finalValue = V.text(e.run()); status = "done"
                                } catch (stop: FlowStopped) {
                                    status = "stopped"; errorText = if (stop.silent) "" else (stop.message ?: "")
                                } catch (t: Throwable) {
                                    status = "failed"
                                    android.util.Log.e("Cascade", "flow failed", t)
                                    errorText = describe(t)
                                }
                            }
                        }) {
                            Icon(Icons.Filled.PlayArrow, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Run again")
                        }
                    }
                    TextButton(onClick = {
                        engine?.cancel(); job?.cancel(); onClose()
                    }) { Text(if (status == "running") "Hide" else "Close") }
                }
            }
        }
    }

    when (val p = io.pending) {
        is Pending.Ask -> AskDialog(p)
        is Pending.Choose -> ChooseDialog(p)
        is Pending.Alert -> AlertDialogStep(p)
        is Pending.Result -> ResultDialog(p)
        null -> {}
    }
}

@Composable
private fun AskDialog(p: Pending.Ask) {
    var text by remember { mutableStateOf(p.default) }
    Dialog(onDismissRequest = { p.reply.complete(null) }) {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(18.dp)) {
                Text(p.prompt, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = !p.multiline,
                    minLines = if (p.multiline) 3 else 1,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { p.reply.complete(null) }) { Text("Cancel") }
                    TextButton(onClick = { p.reply.complete(text) }) { Text("Continue") }
                }
            }
        }
    }
}

@Composable
private fun ChooseDialog(p: Pending.Choose) {
    Dialog(onDismissRequest = { p.reply.complete(null) }) {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(18.dp)) {
                Text(p.prompt, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    itemsIndexed(p.options) { i, opt ->
                        Text(
                            opt,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { p.reply.complete(i) }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { p.reply.complete(null) }) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun AlertDialogStep(p: Pending.Alert) {
    Dialog(onDismissRequest = { if (p.cancellable) p.reply.complete(false) }) {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(18.dp)) {
                Text(p.title, style = MaterialTheme.typography.titleMedium)
                if (p.message.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(p.message, style = MaterialTheme.typography.bodyMedium)
                }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
                    if (p.cancellable) TextButton(onClick = { p.reply.complete(false) }) { Text("Cancel") }
                    TextButton(onClick = { p.reply.complete(true) }) { Text("OK") }
                }
            }
        }
    }
}

@Composable
private fun ResultDialog(p: Pending.Result) {
    Dialog(onDismissRequest = { p.reply.complete(Unit) }) {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(18.dp)) {
                Text(p.title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(listOf(p.value)) { _, v ->
                        Text(v, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { p.reply.complete(Unit) }) { Text("Continue") }
                }
            }
        }
    }
}
