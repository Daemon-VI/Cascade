package com.rishi.cascade.actions

import com.rishi.cascade.engine.Env
import com.rishi.cascade.model.ActionDef
import com.rishi.cascade.model.Cat

abstract class Action(val def: ActionDef) {
    abstract suspend fun run(env: Env): Any?
}

/** Convenience wrapper so action modules stay declarative. */
class SimpleAction(def: ActionDef, private val body: suspend (Env) -> Any?) : Action(def) {
    override suspend fun run(env: Env): Any? = body(env)
}

fun act(def: ActionDef, body: suspend (Env) -> Any?): Action = SimpleAction(def, body)

/** Control-flow steps are interpreted by the Engine; these exist for the editor only. */
class ControlAction(def: ActionDef) : Action(def) {
    override suspend fun run(env: Env): Any? = null
}

object Registry {
    private val byId = LinkedHashMap<String, Action>()

    init {
        registerAll(FlowControlActions.all())
        registerAll(UiActions.all())
        registerAll(TextActions.all())
        registerAll(MathActions.all())
        registerAll(DateActions.all())
        registerAll(DataActions.all())
        registerAll(DeviceActions.all())
        registerAll(AppActions.all())
        registerAll(NetActions.all())
        registerAll(FileActions.all())
        registerAll(MediaActions.all())
        registerAll(CameraActions.all())
        registerAll(LocationActions.all())
        registerAll(CommsActions.all())
        registerAll(ScriptActions.all())
    }

    private fun registerAll(list: List<Action>) = list.forEach { byId[it.def.id] = it }

    fun get(id: String): Action? = byId[id]
    fun def(id: String): ActionDef? = byId[id]?.def
    fun all(): List<ActionDef> = byId.values.map { it.def }

    fun byCategory(): Map<String, List<ActionDef>> {
        val grouped = all().groupBy { it.category }
        val out = LinkedHashMap<String, List<ActionDef>>()
        Cat.ORDER.forEach { c -> grouped[c]?.let { out[c] = it } }
        grouped.keys.filter { it !in Cat.ORDER }.forEach { out[it] = grouped.getValue(it) }
        return out
    }

    fun search(query: String): List<ActionDef> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return all()
        return all().filter {
            it.title.lowercase().contains(q) ||
                it.category.lowercase().contains(q) ||
                it.keywords.lowercase().contains(q) ||
                it.description.lowercase().contains(q)
        }.sortedBy { if (it.title.lowercase().startsWith(q)) 0 else 1 }
    }

    /** Fills a step's params with their declared defaults. */
    fun defaults(id: String): MutableMap<String, String> {
        val d = def(id) ?: return mutableMapOf()
        val m = mutableMapOf<String, String>()
        d.params.forEach { p -> if (p.default.isNotEmpty()) m[p.key] = p.default }
        return m
    }
}
