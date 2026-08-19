package com.rishi.cascade.actions

import com.rishi.cascade.engine.V
import com.rishi.cascade.model.ActionDef
import com.rishi.cascade.model.Cat
import com.rishi.cascade.model.ParamSpec
import com.rishi.cascade.model.ParamType
import java.util.Locale

object TextActions {

    private fun sep(name: String, custom: String): String = when (name) {
        "New line" -> "\n"
        "Space" -> " "
        "Comma" -> ","
        "Comma + space" -> ", "
        "Tab" -> "\t"
        "Nothing" -> ""
        else -> custom
    }

    private val SEPS = listOf("New line", "Space", "Comma", "Comma + space", "Tab", "Nothing", "Custom")

    fun all(): List<Action> = listOf(

        act(ActionDef(
            "text.set", "Text", Cat.TEXT, "TextFields",
            summary = "%text%",
            params = listOf(ParamSpec("text", "Text", ParamType.MULTILINE, "", hint = "plain text or {{variables}}")),
            output = "The text",
            description = "A block of text. Anything in {{braces}} is replaced with a variable.",
            keywords = "text string literal value write"
        )) { env -> env.str("text") },

        act(ActionDef(
            "text.combine", "Combine Text", Cat.TEXT, "Merge",
            summary = "Combine %input%",
            params = listOf(
                ParamSpec("input", "List or text", ParamType.MULTILINE, "{{last}}"),
                ParamSpec("sepKind", "Separator", ParamType.CHOICE, "New line", SEPS),
                ParamSpec("sep", "Custom separator", ParamType.TEXT, "", showIf = "sepKind" to listOf("Custom"))
            ),
            output = "One piece of text",
            description = "Joins a list into a single piece of text.",
            keywords = "join merge concat combine"
        )) { env ->
            V.list(env.value("input")).joinToString(sep(env.choice("sepKind", "New line"), env.str("sep"))) { V.text(it) }
        },

        act(ActionDef(
            "text.split", "Split Text", Cat.TEXT, "ContentCut",
            summary = "Split %input%",
            params = listOf(
                ParamSpec("input", "Text", ParamType.MULTILINE, "{{last}}"),
                ParamSpec("sepKind", "Split by", ParamType.CHOICE, "New line", SEPS),
                ParamSpec("sep", "Custom separator", ParamType.TEXT, "", showIf = "sepKind" to listOf("Custom")),
                ParamSpec("trim", "Trim each piece", ParamType.BOOL, "true")
            ),
            output = "A list of pieces",
            description = "Breaks text into a list.",
            keywords = "split separate divide list"
        )) { env ->
            val s = sep(env.choice("sepKind", "New line"), env.str("sep"))
            val parts = if (s.isEmpty()) env.str("input").map { it.toString() } else env.str("input").split(s)
            if (env.bool("trim", true)) parts.map { it.trim() } else parts
        },

        act(ActionDef(
            "text.replace", "Replace Text", Cat.TEXT, "FindReplace",
            summary = "Replace %find% with %replace%",
            params = listOf(
                ParamSpec("input", "Text", ParamType.MULTILINE, "{{last}}"),
                ParamSpec("find", "Find", ParamType.TEXT, ""),
                ParamSpec("replace", "Replace with", ParamType.TEXT, ""),
                ParamSpec("regex", "Find is a regular expression", ParamType.BOOL, "false"),
                ParamSpec("ignoreCase", "Ignore case", ParamType.BOOL, "false")
            ),
            output = "The changed text",
            keywords = "replace substitute swap regex"
        )) { env ->
            val input = env.str("input")
            val find = env.str("find")
            val repl = env.str("replace")
            if (find.isEmpty()) input
            else if (env.bool("regex", false)) {
                val opts = if (env.bool("ignoreCase", false)) setOf(RegexOption.IGNORE_CASE) else emptySet()
                Regex(find, opts).replace(input, repl)
            } else input.replace(find, repl, ignoreCase = env.bool("ignoreCase", false))
        },

        act(ActionDef(
            "text.match", "Match Text", Cat.TEXT, "Rule",
            summary = "Match %pattern%",
            params = listOf(
                ParamSpec("input", "Text", ParamType.MULTILINE, "{{last}}"),
                ParamSpec("pattern", "Regular expression", ParamType.TEXT, "", hint = "e.g. \\d+"),
                ParamSpec("mode", "Return", ParamType.CHOICE, "All matches",
                    listOf("All matches", "First match", "Capture groups of first match", "Does it match"))
            ),
            output = "Matches",
            description = "Finds parts of text with a regular expression.",
            keywords = "regex regular expression match find extract"
        )) { env ->
            val re = Regex(env.str("pattern"))
            val input = env.str("input")
            when (env.choice("mode", "All matches")) {
                "First match" -> re.find(input)?.value ?: ""
                "Capture groups of first match" -> re.find(input)?.groupValues?.drop(1) ?: emptyList<String>()
                "Does it match" -> re.containsMatchIn(input)
                else -> re.findAll(input).map { it.value }.toList()
            }
        },

        act(ActionDef(
            "text.case", "Change Case", Cat.TEXT, "TextFormat",
            summary = "%mode% case",
            params = listOf(
                ParamSpec("input", "Text", ParamType.MULTILINE, "{{last}}"),
                ParamSpec("mode", "Style", ParamType.CHOICE, "UPPERCASE",
                    listOf("UPPERCASE", "lowercase", "Title Case", "Sentence case", "aLtErNaTiNg"))
            ),
            output = "The changed text",
            keywords = "case upper lower title capitalize"
        )) { env ->
            val s = env.str("input")
            when (env.choice("mode", "UPPERCASE")) {
                "lowercase" -> s.lowercase()
                "Title Case" -> s.split(" ").joinToString(" ") { w ->
                    if (w.isEmpty()) w else w[0].uppercase() + w.substring(1).lowercase()
                }
                "Sentence case" -> s.lowercase().replaceFirstChar { it.uppercase() }
                "aLtErNaTiNg" -> s.mapIndexed { i, c -> if (i % 2 == 0) c.lowercaseChar() else c.uppercaseChar() }.joinToString("")
                else -> s.uppercase(Locale.getDefault())
            }
        },

        act(ActionDef(
            "text.trim", "Trim Text", Cat.TEXT, "ContentCutOutlined",
            params = listOf(
                ParamSpec("input", "Text", ParamType.MULTILINE, "{{last}}"),
                ParamSpec("mode", "Trim", ParamType.CHOICE, "Both ends", listOf("Both ends", "Start", "End", "All blank lines"))
            ),
            output = "The trimmed text",
            keywords = "trim strip whitespace clean"
        )) { env ->
            val s = env.str("input")
            when (env.choice("mode", "Both ends")) {
                "Start" -> s.trimStart()
                "End" -> s.trimEnd()
                "All blank lines" -> s.lines().filter { it.isNotBlank() }.joinToString("\n")
                else -> s.trim()
            }
        },

        act(ActionDef(
            "text.substring", "Get Part of Text", Cat.TEXT, "ShortText",
            summary = "Characters %start% to %end%",
            params = listOf(
                ParamSpec("input", "Text", ParamType.MULTILINE, "{{last}}"),
                ParamSpec("start", "From character", ParamType.NUMBER, "1", hint = "1 is the first, -1 counts from the end"),
                ParamSpec("end", "To character", ParamType.NUMBER, "", hint = "blank means to the end")
            ),
            output = "Part of the text",
            keywords = "substring slice cut part characters"
        )) { env ->
            val s = env.str("input")
            if (s.isEmpty()) return@act ""
            fun idx(v: Int, dflt: Int): Int {
                if (v == 0) return dflt
                return if (v > 0) (v - 1).coerceIn(0, s.length) else (s.length + v).coerceIn(0, s.length)
            }
            val a = idx(env.int("start", 1), 0)
            val rawEnd = env.raw("end")
            val b = if (rawEnd.isBlank()) s.length else {
                val e = env.int("end", s.length)
                if (e > 0) e.coerceIn(0, s.length) else (s.length + e + 1).coerceIn(0, s.length)
            }
            if (a >= b) "" else s.substring(a, b)
        },

        act(ActionDef(
            "text.count", "Count", Cat.TEXT, "Numbers",
            summary = "Count %mode%",
            params = listOf(
                ParamSpec("input", "Text or list", ParamType.MULTILINE, "{{last}}"),
                ParamSpec("mode", "Count", ParamType.CHOICE, "Characters",
                    listOf("Characters", "Words", "Lines", "Items in list", "Occurrences of")),
                ParamSpec("needle", "Find", ParamType.TEXT, "", showIf = "mode" to listOf("Occurrences of"))
            ),
            output = "A number",
            keywords = "count length size words characters"
        )) { env ->
            val v = env.value("input")
            when (env.choice("mode", "Characters")) {
                "Words" -> V.text(v).split(Regex("\\s+")).count { it.isNotBlank() }.toDouble()
                "Lines" -> V.text(v).lines().size.toDouble()
                "Items in list" -> V.list(v).size.toDouble()
                "Occurrences of" -> {
                    val needle = env.str("needle")
                    if (needle.isEmpty()) 0.0 else V.text(v).windowed(needle.length, 1).count { it == needle }.toDouble()
                }
                else -> V.text(v).length.toDouble()
            }
        },

        act(ActionDef(
            "text.pad", "Pad Text", Cat.TEXT, "SpaceBar",
            params = listOf(
                ParamSpec("input", "Text", ParamType.TEXT, "{{last}}"),
                ParamSpec("length", "Total length", ParamType.NUMBER, "10"),
                ParamSpec("with", "Pad with", ParamType.TEXT, "0"),
                ParamSpec("side", "Add to", ParamType.CHOICE, "Start", listOf("Start", "End"))
            ),
            output = "The padded text",
            keywords = "pad zero fill align format"
        )) { env ->
            val s = env.str("input")
            val n = env.int("length", 10)
            val c = env.str("with", "0").firstOrNull() ?: '0'
            if (env.choice("side", "Start") == "End") s.padEnd(n, c) else s.padStart(n, c)
        },

        act(ActionDef(
            "text.lines", "Get Lines", Cat.TEXT, "ViewHeadline",
            params = listOf(
                ParamSpec("input", "Text", ParamType.MULTILINE, "{{last}}"),
                ParamSpec("mode", "Take", ParamType.CHOICE, "All", listOf("All", "First N", "Last N", "Random one", "Line number")),
                ParamSpec("n", "N", ParamType.NUMBER, "1", showIf = "mode" to listOf("First N", "Last N", "Line number"))
            ),
            output = "Lines",
            keywords = "lines rows first last random"
        )) { env ->
            val lines = env.str("input").lines()
            when (env.choice("mode", "All")) {
                "First N" -> lines.take(env.int("n", 1))
                "Last N" -> lines.takeLast(env.int("n", 1))
                "Random one" -> lines.randomOrNull() ?: ""
                "Line number" -> lines.getOrElse(env.int("n", 1) - 1) { "" }
                else -> lines
            }
        },

        act(ActionDef(
            "text.reverse", "Reverse Text", Cat.TEXT, "SwapHoriz",
            params = listOf(
                ParamSpec("input", "Text", ParamType.MULTILINE, "{{last}}"),
                ParamSpec("mode", "Reverse", ParamType.CHOICE, "Characters", listOf("Characters", "Words", "Lines"))
            ),
            output = "The reversed text",
            keywords = "reverse flip backwards"
        )) { env ->
            val s = env.str("input")
            when (env.choice("mode", "Characters")) {
                "Words" -> s.split(" ").reversed().joinToString(" ")
                "Lines" -> s.lines().reversed().joinToString("\n")
                else -> s.reversed()
            }
        }
    )
}
