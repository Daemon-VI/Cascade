package com.rishi.cascade.actions

import android.util.Base64
import com.rishi.cascade.engine.FlowError
import com.rishi.cascade.engine.V
import com.rishi.cascade.model.ActionDef
import com.rishi.cascade.model.Cat
import com.rishi.cascade.model.ParamSpec
import com.rishi.cascade.model.ParamType
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/** Text, maths, date and data actions added after the first pass. */
object ExtraTextData {

    private val EXTRACTORS = linkedMapOf(
        "Email addresses" to "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}",
        "Web links" to "https?://[^\\s\"'<>]+",
        "Numbers" to "-?\\d+(\\.\\d+)?",
        "Phone numbers" to "(\\+?\\d[\\d\\-\\s()]{7,}\\d)",
        "Hashtags" to "#[A-Za-z0-9_]+",
        "Mentions" to "@[A-Za-z0-9_.]+",
        "Dates" to "\\d{1,4}[-/]\\d{1,2}[-/]\\d{1,4}",
        "Times" to "\\d{1,2}:\\d{2}(:\\d{2})?",
        "One-time codes" to "\\b\\d{4,8}\\b",
        "Currency amounts" to "[$£€₹]\\s?\\d+(?:[.,]\\d+)*"
    )

    private fun csvSplit(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out.add(sb.toString().trim()); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString().trim())
        return out
    }

    fun all(): List<Action> = listOf(

        act(ActionDef(
            "text.extract", "Extract from Text", Cat.TEXT, "FilterAlt",
            summary = "Extract %kind%",
            params = listOf(
                ParamSpec("input", "Text", ParamType.MULTILINE, "{{last}}"),
                ParamSpec("kind", "Find", ParamType.CHOICE, "Web links", EXTRACTORS.keys.toList()),
                ParamSpec("mode", "Return", ParamType.CHOICE, "All", listOf("All", "First one"))
            ),
            output = "What was found",
            description = "Pulls links, emails, OTP codes and similar out of a block of text without writing a regex.",
            keywords = "extract find emails links urls otp code phone numbers hashtags parse"
        )) { env ->
            val re = Regex(EXTRACTORS[env.choice("kind", "Web links")] ?: "\\S+")
            val hits = re.findAll(env.str("input")).map { it.value }.toList()
            if (env.choice("mode", "All") == "First one") hits.firstOrNull() ?: "" else hits
        },

        act(ActionDef(
            "text.sortlines", "Sort Lines", Cat.TEXT, "SwapVert",
            params = listOf(
                ParamSpec("input", "Text or list", ParamType.MULTILINE, "{{last}}"),
                ParamSpec("order", "Order", ParamType.CHOICE, "A to Z",
                    listOf("A to Z", "Z to A", "Smallest first", "Largest first", "By length", "Shuffle")),
                ParamSpec("unique", "Drop duplicates", ParamType.BOOL, "false")
            ),
            output = "The sorted list",
            keywords = "sort order lines alphabetical shuffle"
        )) { env ->
            var items = V.list(env.value("input")).map { V.text(it) }
            if (env.bool("unique", false)) items = items.distinct()
            when (env.choice("order", "A to Z")) {
                "Z to A" -> items.sortedByDescending { it.lowercase() }
                "Smallest first" -> items.sortedBy { V.num(it) }
                "Largest first" -> items.sortedByDescending { V.num(it) }
                "By length" -> items.sortedBy { it.length }
                "Shuffle" -> items.shuffled()
                else -> items.sortedBy { it.lowercase() }
            }
        },

        act(ActionDef(
            "text.html", "Strip HTML", Cat.TEXT, "Code",
            params = listOf(
                ParamSpec("input", "HTML", ParamType.MULTILINE, "{{last}}"),
                ParamSpec("mode", "Do", ParamType.CHOICE, "Remove tags",
                    listOf("Remove tags", "Escape for HTML", "Unescape entities"))
            ),
            output = "Plain text",
            description = "Turns a scraped page into readable text, or makes text safe to put inside HTML.",
            keywords = "html tags strip escape unescape scrape entities"
        )) { env ->
            val s = env.str("input")
            when (env.choice("mode", "Remove tags")) {
                "Escape for HTML" -> s.replace("&", "&amp;").replace("<", "&lt;")
                    .replace(">", "&gt;").replace("\"", "&quot;")
                "Unescape entities" -> s.replace("&amp;", "&").replace("&lt;", "<")
                    .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                    .replace("&nbsp;", " ")
                else -> s.replace(Regex("(?s)<script.*?</script>"), " ")
                    .replace(Regex("(?s)<style.*?</style>"), " ")
                    .replace(Regex("<[^>]+>"), " ")
                    .replace("&nbsp;", " ").replace("&amp;", "&")
                    .replace(Regex("[ \\t]+"), " ")
                    .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
            }
        },

        act(ActionDef(
            "math.percent", "Percentage", Cat.MATH, "Percent",
            summary = "%mode%",
            params = listOf(
                ParamSpec("mode", "Work out", ParamType.CHOICE, "X% of Y",
                    listOf("X% of Y", "X as a % of Y", "Percentage change", "Add X%", "Subtract X%")),
                ParamSpec("a", "First number", ParamType.NUMBER, "{{last}}"),
                ParamSpec("b", "Second number", ParamType.NUMBER, "100")
            ),
            output = "A number",
            keywords = "percent percentage discount increase change tip"
        )) { env ->
            val a = env.num("a")
            val b = env.num("b")
            when (env.choice("mode", "X% of Y")) {
                "X as a % of Y" -> if (b == 0.0) 0.0 else a / b * 100
                "Percentage change" -> if (a == 0.0) 0.0 else (b - a) / abs(a) * 100
                "Add X%" -> b + b * a / 100
                "Subtract X%" -> b - b * a / 100
                else -> b * a / 100
            }
        },

        act(ActionDef(
            "math.base", "Convert Number Base", Cat.MATH, "Numbers",
            summary = "%from% to %to%",
            params = listOf(
                ParamSpec("input", "Value", ParamType.TEXT, "{{last}}"),
                ParamSpec("from", "From", ParamType.CHOICE, "Decimal",
                    listOf("Decimal", "Binary", "Octal", "Hex")),
                ParamSpec("to", "To", ParamType.CHOICE, "Hex",
                    listOf("Decimal", "Binary", "Octal", "Hex"))
            ),
            output = "Text in the new base",
            keywords = "binary hex decimal octal base convert radix"
        )) { env ->
            fun radix(name: String) = when (name) {
                "Binary" -> 2; "Octal" -> 8; "Hex" -> 16; else -> 10
            }
            val raw = env.str("input").trim().removePrefix("0x").removePrefix("0b")
            val n = raw.toLongOrNull(radix(env.choice("from", "Decimal")))
                ?: throw FlowError("\"" + raw + "\" is not a valid " + env.choice("from", "Decimal") + " number")
            java.lang.Long.toString(n, radix(env.choice("to", "Hex"))).uppercase(Locale.ROOT)
        },

        act(ActionDef(
            "date.duration", "Format Duration", Cat.DATE, "Timelapse",
            params = listOf(
                ParamSpec("seconds", "Seconds", ParamType.NUMBER, "{{last}}"),
                ParamSpec("style", "Style", ParamType.CHOICE, "1h 5m",
                    listOf("1h 5m", "01:05:00", "1 hour 5 minutes", "Largest unit only"))
            ),
            output = "Readable text",
            description = "Turns a number of seconds into something a human can read.",
            keywords = "duration format seconds minutes hours readable elapsed"
        )) { env ->
            val total = env.num("seconds").toLong().coerceAtLeast(0)
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            when (env.choice("style", "1h 5m")) {
                "01:05:00" -> "%02d:%02d:%02d".format(h, m, s)
                "1 hour 5 minutes" -> buildString {
                    if (h > 0) append(h).append(if (h == 1L) " hour " else " hours ")
                    if (m > 0) append(m).append(if (m == 1L) " minute " else " minutes ")
                    if (h == 0L && m == 0L) append(s).append(if (s == 1L) " second" else " seconds")
                }.trim()
                "Largest unit only" -> when {
                    h > 0 -> h.toString() + "h"
                    m > 0 -> m.toString() + "m"
                    else -> s.toString() + "s"
                }
                else -> buildString {
                    if (h > 0) append(h).append("h ")
                    if (m > 0) append(m).append("m ")
                    if (h == 0L) append(s).append("s")
                }.trim()
            }
        },

        act(ActionDef(
            "date.between", "Is Time Between", Cat.DATE, "Schedule",
            summary = "Between %from% and %to%",
            params = listOf(
                ParamSpec("from", "From (HH:mm)", ParamType.TEXT, "22:00"),
                ParamSpec("to", "To (HH:mm)", ParamType.TEXT, "07:00"),
                ParamSpec("input", "Time to check", ParamType.TEXT, "", hint = "blank means now")
            ),
            output = "true or false",
            description = "Handles windows that cross midnight, which a plain comparison does not.",
            keywords = "time window between night quiet hours schedule check"
        )) { env ->
            fun mins(t: String): Int {
                val p = t.trim().split(":")
                val h = p.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
                val m = p.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
                return h * 60 + m
            }
            val nowMs = DateActions.toMillis(env.str("input"))
            val c = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }
            val now = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
            val a = mins(env.str("from", "22:00"))
            val b = mins(env.str("to", "07:00"))
            if (a <= b) now in a..b else (now >= a || now <= b)
        },

        act(ActionDef(
            "data.csv", "Parse CSV", Cat.DATA, "GridView",
            params = listOf(
                ParamSpec("input", "CSV text", ParamType.MULTILINE, "{{last}}"),
                ParamSpec("header", "First row is a header", ParamType.BOOL, "true"),
                ParamSpec("mode", "Return", ParamType.CHOICE, "List of dictionaries",
                    listOf("List of dictionaries", "List of rows", "One column")),
                ParamSpec("column", "Column", ParamType.TEXT, "", showIf = "mode" to listOf("One column"))
            ),
            output = "The parsed rows",
            keywords = "csv parse spreadsheet table rows columns import"
        )) { env ->
            val lines = env.str("input").lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) return@act emptyList<Any?>()
            val header = env.bool("header", true)
            val cols = if (header) csvSplit(lines.first()) else emptyList()
            val rows = (if (header) lines.drop(1) else lines).map { csvSplit(it) }
            when (env.choice("mode", "List of dictionaries")) {
                "List of rows" -> rows
                "One column" -> {
                    val want = env.str("column")
                    val idx = cols.indexOfFirst { it.equals(want, true) }.takeIf { it >= 0 }
                        ?: want.toIntOrNull()?.minus(1)
                        ?: throw FlowError("No column called \"" + want + "\"")
                    rows.map { it.getOrNull(idx) ?: "" }
                }
                else -> rows.map { row ->
                    JSONObject().also { o ->
                        row.forEachIndexed { i, cell -> o.put(cols.getOrNull(i) ?: ("column" + (i + 1)), cell) }
                    }
                }
            }
        },

        act(ActionDef(
            "data.range", "Number Range", Cat.DATA, "FormatListNumbered",
            summary = "%from% to %to%",
            params = listOf(
                ParamSpec("from", "From", ParamType.NUMBER, "1"),
                ParamSpec("to", "To", ParamType.NUMBER, "10"),
                ParamSpec("step", "Step", ParamType.NUMBER, "1")
            ),
            output = "A list of numbers",
            description = "Handy in front of Repeat with Each when you want to count.",
            keywords = "range numbers sequence count list series"
        )) { env ->
            val from = env.num("from", 1.0)
            val to = env.num("to", 10.0)
            val step = env.num("step", 1.0).let { if (it == 0.0) 1.0 else abs(it) }
            val out = mutableListOf<Any?>()
            var v = from
            var guard = 0
            if (from <= to) while (v <= to && guard++ < 100_000) { out.add(v); v += step }
            else while (v >= to && guard++ < 100_000) { out.add(v); v -= step }
            out
        },

        act(ActionDef(
            "data.dict.set", "Set Dictionary Value", Cat.DATA, "EditNote",
            summary = "Set %key%",
            params = listOf(
                ParamSpec("input", "Dictionary", ParamType.MULTILINE, "{{last}}"),
                ParamSpec("key", "Key", ParamType.TEXT, ""),
                ParamSpec("value", "Value", ParamType.TEXT, ""),
                ParamSpec("remove", "Remove this key instead", ParamType.BOOL, "false")
            ),
            output = "The updated dictionary",
            keywords = "dictionary set add key value update json remove"
        )) { env ->
            val v = env.value("input")
            val o = when (v) {
                is JSONObject -> JSONObject(v.toString())
                is String -> if (v.isBlank()) JSONObject() else JSONObject(v)
                else -> JSONObject()
            }
            val key = env.str("key")
            if (key.isBlank()) throw FlowError("No key given")
            if (env.bool("remove", false)) o.remove(key)
            else {
                val raw = env.value("value")
                o.put(key, V.json(raw))
            }
            o
        },

        act(ActionDef(
            "data.dict.merge", "Merge Dictionaries", Cat.DATA, "Merge",
            params = listOf(
                ParamSpec("first", "First", ParamType.MULTILINE, "{{last}}"),
                ParamSpec("second", "Second (wins on conflict)", ParamType.MULTILINE, "")
            ),
            output = "One dictionary",
            keywords = "merge combine dictionaries json objects"
        )) { env ->
            fun obj(v: Any?): JSONObject = when (v) {
                is JSONObject -> v
                is String -> if (v.isBlank()) JSONObject() else JSONObject(v)
                else -> JSONObject()
            }
            val out = JSONObject(obj(env.value("first")).toString())
            val second = obj(env.value("second"))
            second.keys().forEach { k -> out.put(k, second.opt(k)) }
            out
        },

        act(ActionDef(
            "data.sortby", "Sort List by Field", Cat.DATA, "SwapVert",
            summary = "Sort by %key%",
            params = listOf(
                ParamSpec("input", "List of dictionaries", ParamType.MULTILINE, "{{last}}"),
                ParamSpec("key", "Field", ParamType.TEXT, ""),
                ParamSpec("order", "Order", ParamType.CHOICE, "Ascending",
                    listOf("Ascending", "Descending")),
                ParamSpec("numeric", "Compare as numbers", ParamType.BOOL, "false")
            ),
            output = "The sorted list",
            description = "For lists that came out of Parse JSON or Parse CSV.",
            keywords = "sort list field key json objects order by"
        )) { env ->
            val key = env.str("key")
            val items = V.list(env.value("input"))
            val numeric = env.bool("numeric", false)
            fun field(item: Any?): Any? = when (item) {
                is JSONObject -> item.opt(key)
                is Map<*, *> -> item[key]
                else -> item
            }
            val sorted = if (numeric) items.sortedBy { V.num(field(it)) }
            else items.sortedBy { V.text(field(it)).lowercase() }
            if (env.choice("order", "Ascending") == "Descending") sorted.reversed() else sorted
        },

        act(ActionDef(
            "data.hmac", "Sign with HMAC", Cat.DATA, "Fingerprint",
            summary = "HMAC %algo%",
            params = listOf(
                ParamSpec("input", "Message", ParamType.MULTILINE, "{{last}}"),
                ParamSpec("secret", "Secret key", ParamType.TEXT, ""),
                ParamSpec("algo", "Algorithm", ParamType.CHOICE, "HmacSHA256",
                    listOf("HmacSHA256", "HmacSHA1", "HmacSHA512", "HmacMD5")),
                ParamSpec("out", "Output as", ParamType.CHOICE, "Hex", listOf("Hex", "Base64"))
            ),
            output = "The signature",
            description = "What webhook and API signing needs.",
            keywords = "hmac sign signature secret webhook api auth"
        )) { env ->
            val mac = Mac.getInstance(env.choice("algo", "HmacSHA256"))
            mac.init(SecretKeySpec(env.str("secret").toByteArray(), env.choice("algo", "HmacSHA256")))
            val raw = mac.doFinal(env.str("input").toByteArray())
            if (env.choice("out", "Hex") == "Base64") Base64.encodeToString(raw, Base64.NO_WRAP)
            else raw.joinToString("") { "%02x".format(it) }
        },

        act(ActionDef(
            "data.jwt", "Decode JWT", Cat.DATA, "VpnKey",
            params = listOf(
                ParamSpec("input", "Token", ParamType.MULTILINE, "{{last}}"),
                ParamSpec("part", "Read", ParamType.CHOICE, "Payload", listOf("Payload", "Header", "Both"))
            ),
            output = "The decoded claims",
            description = "Reads the contents of a JSON web token. It does not verify the signature.",
            keywords = "jwt token decode claims auth bearer"
        )) { env ->
            val parts = env.str("input").trim().split(".")
            if (parts.size < 2) throw FlowError("That does not look like a JWT")
            fun decode(s: String): JSONObject = try {
                JSONObject(String(Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)))
            } catch (e: Exception) {
                throw FlowError("Could not decode that part of the token")
            }
            when (env.choice("part", "Payload")) {
                "Header" -> decode(parts[0])
                "Both" -> JSONObject().put("header", decode(parts[0])).put("payload", decode(parts[1]))
                else -> decode(parts[1])
            }
        },

        act(ActionDef(
            "time.waituntil", "Wait Until Time", Cat.FLOWCTRL, "Schedule",
            summary = "Wait until %time%",
            params = listOf(
                ParamSpec("time", "Time (HH:mm)", ParamType.TEXT, "07:30"),
                ParamSpec("maxHours", "Give up after (hours)", ParamType.NUMBER, "12")
            ),
            output = null,
            description = "Pauses until a clock time today, or tomorrow if it has already passed. " +
                    "For long waits prefer a time trigger, which survives the app being killed.",
            keywords = "wait until time clock pause sleep delay schedule"
        )) { env ->
            val target = com.rishi.cascade.trigger.Scheduler.nextTime(
                com.rishi.cascade.model.Trigger(type = "time",
                    config = mutableMapOf("value" to env.str("time", "07:30")))
            )
            val ms = (target - System.currentTimeMillis())
                .coerceIn(0L, (env.num("maxHours", 12.0) * 3_600_000).toLong())
            env.log("Waiting", V.num2str(ms / 60000.0) + " minutes", 1)
            kotlinx.coroutines.delay(ms)
            null
        }
    )
}
