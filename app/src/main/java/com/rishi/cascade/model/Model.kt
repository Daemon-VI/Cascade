package com.rishi.cascade.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A single configured action inside a flow. Params are always stored as strings that may
 *  contain {{variable}} templates; the ActionDef says how to interpret them. */
data class Step(
    val id: String = UUID.randomUUID().toString().take(8),
    val actionId: String,
    val params: MutableMap<String, String> = mutableMapOf(),
    var outputName: String? = null,
    var enabled: Boolean = true
) {
    fun copyNew() = Step(
        id = UUID.randomUUID().toString().take(8),
        actionId = actionId,
        params = LinkedHashMap(params),
        outputName = outputName,
        enabled = enabled
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("action", actionId)
        put("params", JSONObject(params as Map<*, *>))
        outputName?.let { put("out", it) }
        put("enabled", enabled)
    }

    companion object {
        fun fromJson(o: JSONObject): Step {
            val p = mutableMapOf<String, String>()
            val po = o.optJSONObject("params")
            po?.keys()?.forEach { k -> p[k] = po.optString(k, "") }
            return Step(
                id = o.optString("id", UUID.randomUUID().toString().take(8)),
                actionId = o.optString("action"),
                params = p,
                outputName = if (o.has("out")) o.optString("out") else null,
                enabled = o.optBoolean("enabled", true)
            )
        }
    }
}

/** How a flow gets started without the user tapping it. */
data class Trigger(
    val id: String = UUID.randomUUID().toString().take(8),
    val type: String,
    val config: MutableMap<String, String> = mutableMapOf(),
    var enabled: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type)
        put("config", JSONObject(config as Map<*, *>))
        put("enabled", enabled)
    }

    companion object {
        fun fromJson(o: JSONObject): Trigger {
            val c = mutableMapOf<String, String>()
            val co = o.optJSONObject("config")
            co?.keys()?.forEach { k -> c[k] = co.optString(k, "") }
            return Trigger(
                id = o.optString("id", UUID.randomUUID().toString().take(8)),
                type = o.optString("type"),
                config = c,
                enabled = o.optBoolean("enabled", true)
            )
        }
    }
}

/** A whole automation: an ordered, flat step list (blocks are marked by BEGIN/END steps). */
data class Flow(
    val id: String = UUID.randomUUID().toString().take(10),
    var name: String = "New Flow",
    var icon: String = "Bolt",
    var color: Int = 0,
    val steps: MutableList<Step> = mutableListOf(),
    val triggers: MutableList<Trigger> = mutableListOf(),
    var showInWidget: Boolean = true,
    var runCount: Int = 0,
    var lastRun: Long = 0L,
    var createdAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("icon", icon)
        put("color", color)
        put("steps", JSONArray().also { a -> steps.forEach { a.put(it.toJson()) } })
        put("triggers", JSONArray().also { a -> triggers.forEach { a.put(it.toJson()) } })
        put("widget", showInWidget)
        put("runCount", runCount)
        put("lastRun", lastRun)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject): Flow {
            val steps = mutableListOf<Step>()
            val sa = o.optJSONArray("steps")
            if (sa != null) for (i in 0 until sa.length()) steps.add(Step.fromJson(sa.getJSONObject(i)))
            val trs = mutableListOf<Trigger>()
            val ta = o.optJSONArray("triggers")
            if (ta != null) for (i in 0 until ta.length()) trs.add(Trigger.fromJson(ta.getJSONObject(i)))
            return Flow(
                id = o.optString("id", UUID.randomUUID().toString().take(10)),
                name = o.optString("name", "Flow"),
                icon = o.optString("icon", "Bolt"),
                color = o.optInt("color", 0),
                steps = steps,
                triggers = trs,
                showInWidget = o.optBoolean("widget", true),
                runCount = o.optInt("runCount", 0),
                lastRun = o.optLong("lastRun", 0L),
                createdAt = o.optLong("createdAt", System.currentTimeMillis())
            )
        }
    }
}
