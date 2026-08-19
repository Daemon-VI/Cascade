package com.rishi.cascade.engine

import android.content.Context
import com.rishi.cascade.model.Step
import org.json.JSONArray
import org.json.JSONObject

/** A file produced by an action (photo, download, recording...). */
data class FileRef(val path: String, val mime: String = "application/octet-stream") {
    override fun toString(): String = path
}

/** Loose dynamic value helpers. Runtime values are Any?: String, Double, Boolean,
 *  List<Any?>, JSONObject, FileRef or null. */
object V {
    fun text(v: Any?): String = when (v) {
        null -> ""
        is String -> v
        is Double -> num2str(v)
        is Float -> num2str(v.toDouble())
        is Int -> v.toString()
        is Long -> v.toString()
        is Boolean -> if (v) "true" else "false"
        is List<*> -> v.joinToString("\n") { text(it) }
        is JSONArray -> (0 until v.length()).joinToString("\n") { text(v.get(it)) }
        is JSONObject -> v.toString(2)
        is FileRef -> v.path
        else -> v.toString()
    }

    fun num2str(d: Double): String {
        if (d.isNaN() || d.isInfinite()) return d.toString()
        return if (d == d.toLong().toDouble() && kotlin.math.abs(d) < 1e15) d.toLong().toString()
        else d.toString()
    }

    fun num(v: Any?): Double = when (v) {
        null -> 0.0
        is Double -> v
        is Number -> v.toDouble()
        is Boolean -> if (v) 1.0 else 0.0
        is String -> v.trim().let { s ->
            s.toDoubleOrNull() ?: Regex("-?\\d+(\\.\\d+)?").find(s)?.value?.toDoubleOrNull() ?: 0.0
        }
        is List<*> -> v.size.toDouble()
        else -> 0.0
    }

    fun bool(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> v.isNotEmpty() && !v.equals("false", true) && v != "0"
        is List<*> -> v.isNotEmpty()
        else -> true
    }

    fun list(v: Any?): List<Any?> = when (v) {
        null -> emptyList()
        is List<*> -> v
        is JSONArray -> (0 until v.length()).map { v.get(it) }
        is String -> if (v.isEmpty()) emptyList() else v.split("\n")
        else -> listOf(v)
    }

    /** Best-effort JSON-ish conversion so lists/objects survive serialization to a variable. */
    fun json(v: Any?): Any? = when (v) {
        is List<*> -> JSONArray().also { a -> v.forEach { a.put(json(it)) } }
        is Map<*, *> -> JSONObject().also { o -> v.forEach { (k, x) -> o.put(k.toString(), json(x)) } }
        is Double -> if (v == v.toLong().toDouble()) v.toLong() else v
        is FileRef -> v.path
        null -> JSONObject.NULL
        else -> v
    }

    fun typeName(v: Any?): String = when (v) {
        null -> "nothing"
        is String -> "text"
        is Number -> "number"
        is Boolean -> "true/false"
        is List<*> -> "list (${v.size})"
        is JSONObject -> "dictionary"
        is JSONArray -> "list (${v.length()})"
        is FileRef -> "file"
        else -> v.javaClass.simpleName
    }
}

class FlowStopped(val silent: Boolean = false, message: String = "Flow stopped") : Exception(message)
class FlowError(message: String) : Exception(message)

data class LogLine(val time: Long, val stepIndex: Int, val title: String, val detail: String, val level: Int)

/** Everything the UI can be asked to do by a running flow. */
interface FlowIO {
    suspend fun ask(prompt: String, default: String, multiline: Boolean, numeric: Boolean): String?
    suspend fun choose(prompt: String, options: List<String>): Int?
    suspend fun alert(title: String, message: String, cancellable: Boolean): Boolean
    suspend fun showResult(title: String, value: Any?)
    fun log(line: LogLine)
    fun progress(stepIndex: Int, title: String)
}

/** Live state of one flow run. */
class RunContext(
    val flowId: String,
    val flowName: String,
    val vars: MutableMap<String, Any?> = LinkedHashMap()
) {
    var last: Any? = null
    @Volatile var cancelled = false
    val logs = mutableListOf<LogLine>()
    var depth = 0

    fun set(name: String, value: Any?) { vars[name] = value }
    fun get(name: String): Any? = when (name.lowercase()) {
        "last", "result", "input" -> last
        else -> vars[name]
    }
}

/** Per-step view over params, with {{variable}} interpolation. */
class Env(
    val app: Context,
    val ctx: RunContext,
    val step: Step,
    val io: FlowIO,
    val engine: Engine
) {
    fun raw(key: String): String = step.params[key] ?: ""

    fun str(key: String, def: String = ""): String {
        val r = step.params[key] ?: return def
        if (r.isEmpty()) return def
        return Interpolator.interpolate(r, ctx)
    }

    /** Returns the underlying object when the param is exactly one {{var}} reference,
     *  so lists and dictionaries keep their type instead of collapsing to text. */
    fun value(key: String): Any? {
        val r = step.params[key] ?: return null
        val m = Interpolator.SOLE.matchEntire(r.trim()) ?: return if (r.isEmpty()) null else str(key)
        return Interpolator.resolve(m.groupValues[1], ctx)
    }

    fun num(key: String, def: Double = 0.0): Double {
        val r = step.params[key] ?: return def
        if (r.isBlank()) return def
        return V.num(value(key))
    }

    fun int(key: String, def: Int = 0): Int = num(key, def.toDouble()).toInt()

    fun bool(key: String, def: Boolean = false): Boolean {
        val r = step.params[key] ?: return def
        if (r.isBlank()) return def
        return V.bool(value(key))
    }

    fun choice(key: String, def: String = ""): String = str(key, def)

    fun list(key: String): List<Any?> = V.list(value(key))

    fun keyValues(key: String): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        str(key).lines().forEach { line ->
            val t = line.trim()
            if (t.isEmpty()) return@forEach
            val i = t.indexOf(':')
            if (i > 0) out[t.substring(0, i).trim()] = t.substring(i + 1).trim()
        }
        return out
    }

    fun log(title: String, detail: String = "", level: Int = 0) {
        val l = LogLine(System.currentTimeMillis(), -1, title, detail, level)
        ctx.logs.add(l); io.log(l)
    }
}
