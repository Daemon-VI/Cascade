package com.rishi.cascade.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.rishi.cascade.actions.AppActions
import com.rishi.cascade.model.ParamSpec
import com.rishi.cascade.model.ParamType
import com.rishi.cascade.store.FlowStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class VarSuggestion(val name: String, val hint: String)

@Composable
fun ParamField(
    spec: ParamSpec,
    value: String,
    variables: List<VarSuggestion>,
    onChange: (String) -> Unit
) {
    when (spec.type) {
        ParamType.BOOL -> Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(spec.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = value.equals("true", true),
                onCheckedChange = { onChange(it.toString()) }
            )
        }

        ParamType.CHOICE -> ChoiceField(spec, value, onChange)

        ParamType.APP -> AppField(spec, value, onChange)

        ParamType.FLOW -> FlowField(spec, value, onChange)

        else -> TextParamField(spec, value, variables, onChange)
    }
}

@Composable
private fun ChoiceField(spec: ParamSpec, value: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val shown = value.ifBlank { spec.default.ifBlank { spec.options.firstOrNull() ?: "" } }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(spec.label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            Row(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                    .clickable { open = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(shown, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(20.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                spec.options.forEach { opt ->
                    DropdownMenuItem(
                        text = { Text(opt) },
                        onClick = { onChange(opt); open = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun TextParamField(
    spec: ParamSpec,
    value: String,
    variables: List<VarSuggestion>,
    onChange: (String) -> Unit
) {
    var field by remember(spec.key) { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    var menu by remember { mutableStateOf(false) }
    if (field.text != value && !field.text.startsWith(value) && value.isEmpty()) {
        field = TextFieldValue(value, TextRange(0))
    }
    val multiline = spec.type == ParamType.MULTILINE || spec.type == ParamType.KEYVALUE
    val numeric = spec.type == ParamType.NUMBER

    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(spec.label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (spec.type != ParamType.VARNAME) {
                Box {
                    AssistChip(
                        onClick = { menu = true },
                        label = { Text("Variable", style = MaterialTheme.typography.labelSmall) },
                        leadingIcon = { Icon(Icons.Filled.DataObject, null, Modifier.size(14.dp)) },
                        colors = AssistChipDefaults.assistChipColors(
                            labelColor = MaterialTheme.colorScheme.primary,
                            leadingIconContentColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.height(28.dp)
                    )
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (variables.isEmpty()) {
                            DropdownMenuItem(text = { Text("No variables yet") }, onClick = { menu = false })
                        }
                        variables.forEach { v ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text("{{" + v.name + "}}", style = MaterialTheme.typography.bodyMedium)
                                        if (v.hint.isNotEmpty())
                                            Text(v.hint, style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                onClick = {
                                    val token = "{{" + v.name + "}}"
                                    val at = field.selection.start.coerceIn(0, field.text.length)
                                    val text = field.text.substring(0, at) + token + field.text.substring(at)
                                    field = TextFieldValue(text, TextRange(at + token.length))
                                    onChange(text)
                                    menu = false
                                }
                            )
                        }
                    }
                }
            }
        }
        OutlinedTextField(
            value = field,
            onValueChange = { field = it; onChange(it.text) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = if (spec.hint.isNotEmpty()) {
                { Text(spec.hint, style = MaterialTheme.typography.bodySmall) }
            } else null,
            singleLine = !multiline,
            minLines = if (multiline) 2 else 1,
            maxLines = if (multiline) 8 else 1,
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text
            ),
            shape = RoundedCornerShape(10.dp)
        )
    }
}

@Composable
private fun AppField(spec: ParamSpec, value: String, onChange: (String) -> Unit) {
    val context = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<AppActions.AppEntry>>(emptyList()) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(picking) {
        if (picking && apps.isEmpty()) {
            apps = withContext(Dispatchers.IO) { AppActions.installedApps(context) }
        }
    }

    val label = remember(value, apps) {
        apps.firstOrNull { it.pkg == value }?.label ?: value.ifBlank { "Choose an app" }
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(spec.label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                .clickable { picking = true }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Filled.Apps, null, Modifier.size(18.dp))
        }
    }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("Choose an app") },
            text = {
                Column {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search") },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    val filtered = apps.filter {
                        query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true)
                    }
                    if (apps.isEmpty()) Text("Loading apps...")
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        items(filtered) { app ->
                            Column(
                                Modifier.fillMaxWidth()
                                    .clickable { onChange(app.pkg); picking = false }
                                    .padding(vertical = 8.dp)
                            ) {
                                Text(app.label, style = MaterialTheme.typography.bodyMedium)
                                Text(app.pkg, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun FlowField(spec: ParamSpec, value: String, onChange: (String) -> Unit) {
    val context = LocalContext.current
    val store = remember { FlowStore.get(context) }
    val flows = remember { store.all() }
    var open by remember { mutableStateOf(false) }
    val label = flows.firstOrNull { it.id == value }?.name ?: "Choose a flow"

    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(spec.label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            Row(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                    .clickable { open = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(20.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                flows.forEach { f ->
                    DropdownMenuItem(text = { Text(f.name) }, onClick = { onChange(f.id); open = false })
                }
            }
        }
    }
}
