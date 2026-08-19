package com.rishi.cascade.actions

import android.util.Base64
import com.rishi.cascade.engine.Conditions
import com.rishi.cascade.engine.Env
import com.rishi.cascade.engine.FlowError
import com.rishi.cascade.engine.Interpolator
import com.rishi.cascade.engine.RunContext
import com.rishi.cascade.engine.V
import com.rishi.cascade.model.ActionDef
import com.rishi.cascade.model.Cat
import com.rishi.cascade.model.ParamSpec
import com.rishi.cascade.model.ParamType
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID

object DataActions {

    fun parseJson(text: String): Any {
        val t = text.trim()
        return try {
            when {
                t.startsWith("{") -> JSONObject(t)
                t.startsWith("[") -> JSONArray(t)
                else -> throw FlowError("That text is not JSON")
            }
        } catch (e: Exception) {
            throw FlowError("Could not read JSON: " + (e.message ?: ""))
        }
    }

    /** Reuses the {{variable}} path syntax for digging into a parsed value. */
    private fun dig(root: Any?, path: String): Any? {
        if (path.isBlank()) return root
        val ctx = RunContext("", "")
        ctx.set("__root", root)
        return Interpolator.resolve("__root." + path.trim().removePrefix("$").removePrefix("."), ctx)
    }

    private fun jsonify(v: Any?): Any? = V.json(v)

    fun all(): List<Action> = listOf(

        act(ActionDef(
            "data.json.parse", "Parse JSON", Cat.DATA, "DataObject",
            params = listOf(ParamSpec("input", "JSON text", ParamType.MULTILINE, "{{last}}")),
            output = "A dictionary or list",
            description = "Turns JSON text into something you can dig into with {{var.key}}.",
            keywords = "json parse decode dictionary object api"
        )) { env -> parseJson(env.str("input")) },

        act(ActionDef(
            "data.json.get", "Get Value from JSON", Cat.DATA, "ManageSearch",
            summary = "Get %path%",
            params = listOf(
                ParamSpec("input", "JSON or dictionary", ParamType.MULTILINE, "{{last}}"),
                ParamSpec("path", "Key path", ParamType.TEXT, "", hint = "e.g. items[0].name")
            ),
            output = "The value at that path",
            keywords = "json get key path value extract dictionary"
        )) { env ->
            val v = env.value("input")
            val root = if (v is String) parseJson(v) else v
            dig(root, env.str("path"))
        },

        act(ActionDef(
            "data.json.build", "Make Dictionary", Cat.DATA, "Inventory",
            params = listOf(ParamSpec("pairs", "Keys and values", ParamType.KEYVALUE, "",
                hint = "one \"key: value\" per line")),
            output = "A dictionary",
            description = "Builds a dictionary you can send as a JSON body.",
            keywords = "dictionary json build make object map"
        )) { env ->
            JSONObject().also { o -> env.keyValues("pairs").forEach { (k, v) ->
                o.put(k, v.toDoubleOrNull() ?: (if (v == "true" || v == "false") v.toBoolean() else v))
            } }
        },

        act(ActionDef(
            "data.json.stringify", "Make JSON Text", Cat.DATA, "Code",
            params = listOf(
                ParamSpec("input", "Value", ParamType.MULTILINE, "{{last}}"),
                ParamSpec("pretty", "Pretty print", ParamType.BOOL, "true")
            ),
            output = "JSON text",
            keywords = "json stringify serialize text encode"
        )) { env ->
            val v = jsonify(env.value("input"))
            when {
                env.bool("pretty", true) && v is JSONObject -> v.toString(2)
                env.bool("pretty", true) && v is JSONArray -> v.toString(2)
                else -> v.toString()
            }
        },

        act(ActionDef(
            "data.list.make", "Make List", Cat.DATA, "PlaylistAdd",
            params = listOf(ParamSpec("items", "Items (one per line)", ParamType.MULTILINE, "")),
            output = "A list",
            keywords = "list make array create items"
        )) { env -> env.str("items").lines().filter { it.isNotBlank() } },

        act(ActionDef(
            "data.list.item", "Get Item from List", Cat.DATA, "FilterCenterFocus",
            summary = "%mode% of %input%",
            params = listOf(
                ParamSpec("input", "List", ParamType.MULTILINE, "{{last}}"),
                ParamSpec("mode", "Get", ParamType.CHOICE, "Item at index",
                    listOf("Item at index", "First item", "Last item", "Random item", "First N", "Last N")),
                ParamSpec("index", "Index", ParamType.NUMBER, "1",
                    showIf = "mode" to listOf("Item at index", "First N", "Last N"), hint = "1 is the first")
            ),
            output = "An item or a shorter list",
            keywords = "list item index first last random get element"
        )) { env ->
            val l = V.list(env.value("input"))
            if (l.isEmpty()) return@act null
            when (env.choice("mode", "Item at index")) {
                "First item" -> l.first()
                "Last item" -> l.last()
                "Random item" -> l.random()
                "First N" -> l.take(env.int("index", 1))
                "Last N" -> l.takeLast(env.int("index", 1))
                else -> {
                    val i = env.int("index", 1)
                    if (i < 0) l.getOrNull(l.size + i) else l.getOrNull(i - 1)
                }
            }
        },

        act(ActionDef(
            "data.list.edit", "Edit List", Cat.DATA, "EditNote",
            summary = "%mode%",
            params = listOf(
                ParamSpec("input", "List", ParamType.MULTILINE, "{{last}}"),
                ParamSpec("mode", "Operation", ParamType.CHOICE, "Add item",
                    listOf("Add item", "Add to front", "Remove item at index", "Remove duplicates",
                        "Reverse", "Shuffle", "Sort", "Remove empty")),
                ParamSpec("value", "Item", ParamType.TEXT, "", showIf = "mode" to listOf("Add item", "Add to front")),
                ParamSpec("index", "Index", ParamType.NUMBER, "1", showIf = "mode" to listOf("Remove item at index")),
                ParamSpec("order", "Order", ParamType.CHOICE, "A to Z",
                    listOf("A to Z", "Z to A", "Smallest first", "Largest first"), showIf = "mode" to listOf("Sort"))
            ),
            output = "The new list",
            keywords = "list add remove sort shuffle reverse unique duplicate"
        )) { env ->
            val l = V.list(env.value("input")).toMutableList()
            when (env.choice("mode", "Add item")) {
                "Add item" -> l.add(env.value("value"))
                "Add to front" -> l.add(0, env.value("value"))
                "Remove item at index" -> {
                    val i = env.int("index", 1) - 1
                    if (i in l.indices) l.removeAt(i)
                }
                "Remove duplicates" -> return@act l.distinctBy { V.text(it) }
                "Reverse" -> return@act l.reversed()
                "Shuffle" -> return@act l.shuffled()
                "Remove empty" -> return@act l.filter { V.text(it).isNotBlank() }
                "Sort" -> return@act when (env.choice("order", "A to Z")) {
                    "Z to A" -> l.sortedByDescending { V.text(it).lowercase() }
                    "Smallest first" -> l.sortedBy { V.num(it) }
                    "Largest first" -> l.sortedByDescending { V.num(it) }
                    else -> l.sortedBy { V.text(it).lowercase() }
                }
            }
            l
        },

        act(ActionDef(
            "data.list.filter", "Filter List", Cat.DATA, "FilterAlt",
            summary = "Keep items %op% %value%",
            params = listOf(
                ParamSpec("input", "List", ParamType.MULTILINE, "{{last}}"),
                ParamSpec("op", "Keep items that", ParamType.CHOICE, "contains", Conditions.OPERATORS),
                ParamSpec("value", "Value", ParamType.TEXT, ""),
                ParamSpec("invert", "Keep the others instead", ParamType.BOOL, "false")
            ),
            output = "The filtered list",
            keywords = "filter list keep where matching search"
        )) { env ->
            val items = V.list(env.value("input"))
            val invert = env.bool("invert", false)
            items.filter { item ->
                val probe = Env(env.app, env.ctx, env.step.copyNew().also {
                    it.params["input"] = "{{__item}}"
                    it.params["op"] = env.raw("op")
                    it.params["value"] = env.raw("value")
                }, env.io, env.engine)
                env.ctx.set("__item", item)
                val keep = Conditions.test(probe)
                if (invert) !keep else keep
            }.also { env.ctx.vars.remove("__item") }
        },

        act(ActionDef(
            "data.base64", "Base64", Cat.DATA, "Lock",
            summary = "%mode% base64",
            params = listOf(
                ParamSpec("input", "Text", ParamType.MULTILINE, "{{last}}"),
                ParamSpec("mode", "Direction", ParamType.CHOICE, "Encode", listOf("Encode", "Decode"))
            ),
            output = "Text",
            keywords = "base64 encode decode"
        )) { env ->
            val s = env.str("input")
            if (env.choice("mode", "Encode") == "Decode")
                String(Base64.decode(s.trim(), Base64.DEFAULT))
            else Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP)
        },

        act(ActionDef(
            "data.hash", "Hash Text", Cat.DATA, "Fingerprint",
            summary = "%algo% of %input%",
            params = listOf(
                ParamSpec("input", "Text", ParamType.MULTILINE, "{{last}}"),
                ParamSpec("algo", "Algorithm", ParamType.CHOICE, "SHA-256", listOf("MD5", "SHA-1", "SHA-256", "SHA-512"))
            ),
            output = "A hex digest",
            keywords = "hash md5 sha checksum digest"
        )) { env ->
            MessageDigest.getInstance(env.choice("algo", "SHA-256"))
                .digest(env.str("input").toByteArray())
                .joinToString("") { "%02x".format(it) }
        },

        act(ActionDef(
            "data.url", "URL Encode", Cat.DATA, "Link",
            params = listOf(
                ParamSpec("input", "Text", ParamType.MULTILINE, "{{last}}"),
                ParamSpec("mode", "Direction", ParamType.CHOICE, "Encode", listOf("Encode", "Decode"))
            ),
            output = "Text",
            keywords = "url encode decode percent escape query"
        )) { env ->
            val s = env.str("input")
            if (env.choice("mode", "Encode") == "Decode") URLDecoder.decode(s, "UTF-8")
            else URLEncoder.encode(s, "UTF-8")
        },

        act(ActionDef(
            "data.uuid", "Generate UUID", Cat.DATA, "Tag",
            params = listOf(ParamSpec("upper", "Uppercase", ParamType.BOOL, "false")),
            output = "A unique id",
            keywords = "uuid guid unique id random"
        )) { env ->
            val u = UUID.randomUUID().toString()
            if (env.bool("upper", false)) u.uppercase() else u
        },

        act(ActionDef(
            "data.type", "Get Type", Cat.DATA, "Category",
            params = listOf(ParamSpec("input", "Value", ParamType.TEXT, "{{last}}")),
            output = "The type name",
            description = "Tells you whether a value is text, a number, a list or a dictionary. Handy for debugging.",
            keywords = "type debug kind inspect"
        )) { env -> V.typeName(env.value("input")) },

        act(ActionDef(
            "data.keys", "Get Dictionary Keys", Cat.DATA, "VpnKey",
            params = listOf(
                ParamSpec("input", "Dictionary", ParamType.MULTILINE, "{{last}}"),
                ParamSpec("mode", "Get", ParamType.CHOICE, "Keys", listOf("Keys", "Values"))
            ),
            output = "A list",
            keywords = "keys values dictionary json fields"
        )) { env ->
            val v = env.value("input")
            val o = if (v is String) parseJson(v) else v
            if (o !is JSONObject) throw FlowError("That is not a dictionary")
            val keys = o.keys().asSequence().toList()
            if (env.choice("mode", "Keys") == "Values") keys.map { o.opt(it) } else keys
        }
    )
}
