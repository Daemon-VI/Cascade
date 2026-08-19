package com.rishi.cascade.engine

import org.json.JSONArray
import org.json.JSONObject

/** Handles {{variable}} references inside text params.
 *  Supports {{name}}, {{name.key}}, {{name[0]}}, {{name.key[2].other}} and {{last}}. */
object Interpolator {
    // Android's ICU regex engine rejects unescaped closing braces, so every brace is escaped.
    private val TOKEN = Regex("\\{\\{\\s*([^{}]+?)\\s*\\}\\}")
    val SOLE = Regex("\\{\\{\\s*([^{}]+?)\\s*\\}\\}")

    fun interpolate(input: String, ctx: RunContext): String {
        if (!input.contains("{{")) return input
        return TOKEN.replace(input) { m -> V.text(resolve(m.groupValues[1], ctx)) }
    }

    fun references(input: String): List<String> =
        TOKEN.findAll(input).map { it.groupValues[1].trim() }.toList()

    fun resolve(path: String, ctx: RunContext): Any? {
        val parts = splitPath(path.trim())
        if (parts.isEmpty()) return null
        var cur: Any? = ctx.get(parts[0].removeSurrounding("\""))
        for (i in 1 until parts.size) {
            cur = index(cur, parts[i]) ?: return null
        }
        return cur
    }

    /** "a.b[0].c" becomes ["a","b","0","c"] */
    private fun splitPath(path: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < path.length) {
            when (path[i]) {
                '.' -> if (sb.isNotEmpty()) { out.add(sb.toString()); sb.clear() }
                '[' -> {
                    if (sb.isNotEmpty()) { out.add(sb.toString()); sb.clear() }
                    val close = path.indexOf(']', i)
                    if (close < 0) i = path.length
                    else {
                        out.add(path.substring(i + 1, close).trim().removeSurrounding("\""))
                        i = close
                    }
                }
                ']' -> {}
                else -> sb.append(path[i])
            }
            i++
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    private fun index(cur: Any?, key: String): Any? {
        val idx = key.toIntOrNull()
        return when (cur) {
            null -> null
            is List<*> -> when {
                idx != null -> cur.getOrNull(if (idx < 0) cur.size + idx else idx)
                key == "count" || key == "length" -> cur.size.toDouble()
                else -> null
            }
            is JSONArray -> when {
                idx != null -> {
                    val n = if (idx < 0) cur.length() + idx else idx
                    if (n >= 0 && n < cur.length()) cur.opt(n) else null
                }
                key == "count" || key == "length" -> cur.length().toDouble()
                else -> null
            }
            is JSONObject -> if (cur.has(key)) cur.opt(key).takeIf { it != JSONObject.NULL } else null
            is Map<*, *> -> cur[key]
            is String -> when {
                key == "count" || key == "length" -> cur.length.toDouble()
                idx != null -> cur.getOrNull(idx)?.toString()
                else -> tryJson(cur)?.let { index(it, key) }
            }
            else -> null
        }
    }

    private fun tryJson(s: String): Any? = try {
        val t = s.trim()
        when {
            t.startsWith("{") -> JSONObject(t)
            t.startsWith("[") -> JSONArray(t)
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}
