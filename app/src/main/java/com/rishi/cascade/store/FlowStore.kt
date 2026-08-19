package com.rishi.cascade.store

import android.content.Context
import com.rishi.cascade.model.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

/** Flows live as one JSON file each in filesDir/flows. Small enough to keep entirely in memory. */
class FlowStore private constructor(private val app: Context) {

    private val dir = File(app.filesDir, "flows").apply { mkdirs() }
    private val _flows = MutableStateFlow<List<Flow>>(emptyList())
    val flows: StateFlow<List<Flow>> = _flows

    init { reload() }

    fun reload() {
        val list = dir.listFiles { f -> f.name.endsWith(".json") }
            ?.mapNotNull { f ->
                try { Flow.fromJson(JSONObject(f.readText())) } catch (e: Exception) { null }
            }
            ?.sortedBy { it.createdAt }
            ?: emptyList()
        _flows.value = list
    }

    fun all(): List<Flow> = _flows.value

    fun load(id: String): Flow? {
        _flows.value.firstOrNull { it.id == id }?.let { return it }
        val f = File(dir, "$id.json")
        if (!f.exists()) return null
        return try { Flow.fromJson(JSONObject(f.readText())) } catch (e: Exception) { null }
    }

    fun save(flow: Flow) {
        File(dir, "${flow.id}.json").writeText(flow.toJson().toString())
        val cur = _flows.value.toMutableList()
        val i = cur.indexOfFirst { it.id == flow.id }
        if (i >= 0) cur[i] = flow else cur.add(flow)
        _flows.value = cur.sortedBy { it.createdAt }
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
        _flows.value = _flows.value.filter { it.id != id }
    }

    fun duplicate(id: String): Flow? {
        val src = load(id) ?: return null
        val copy = Flow(
            name = src.name + " copy",
            icon = src.icon,
            color = src.color,
            steps = src.steps.map { it.copyNew() }.toMutableList()
        )
        save(copy)
        return copy
    }

    fun exportJson(id: String): String? = load(id)?.toJson()?.toString(2)

    fun importJson(text: String): Flow? = try {
        val o = JSONObject(text)
        val f = Flow.fromJson(o)
        // fresh identity so importing twice does not overwrite
        val fresh = Flow(
            name = f.name, icon = f.icon, color = f.color,
            steps = f.steps.map { it.copyNew() }.toMutableList(),
            triggers = f.triggers.toMutableList()
        )
        save(fresh)
        fresh
    } catch (e: Exception) { null }

    fun markRun(id: String) {
        val f = load(id) ?: return
        f.runCount++
        f.lastRun = System.currentTimeMillis()
        save(f)
    }

    companion object {
        @Volatile private var instance: FlowStore? = null
        fun get(context: Context): FlowStore = instance ?: synchronized(this) {
            instance ?: FlowStore(context.applicationContext).also { instance = it }
        }
    }
}
