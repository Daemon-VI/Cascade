package com.rishi.cascade.actions

import com.rishi.cascade.engine.Expr
import com.rishi.cascade.engine.FlowError
import com.rishi.cascade.engine.V
import com.rishi.cascade.model.ActionDef
import com.rishi.cascade.model.Cat
import com.rishi.cascade.model.ParamSpec
import com.rishi.cascade.model.ParamType
import java.text.DecimalFormat
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToLong

object MathActions {

    /** unit -> factor into the category base unit */
    private val UNITS: Map<String, Pair<String, Double>> = linkedMapOf(
        "mm" to ("length" to 0.001), "cm" to ("length" to 0.01), "m" to ("length" to 1.0),
        "km" to ("length" to 1000.0), "inch" to ("length" to 0.0254), "ft" to ("length" to 0.3048),
        "yard" to ("length" to 0.9144), "mile" to ("length" to 1609.344),
        "mg" to ("mass" to 1e-6), "g" to ("mass" to 0.001), "kg" to ("mass" to 1.0),
        "tonne" to ("mass" to 1000.0), "oz" to ("mass" to 0.0283495), "lb" to ("mass" to 0.453592),
        "ms" to ("time" to 0.001), "sec" to ("time" to 1.0), "min" to ("time" to 60.0),
        "hour" to ("time" to 3600.0), "day" to ("time" to 86400.0), "week" to ("time" to 604800.0),
        "B" to ("data" to 1.0), "KB" to ("data" to 1024.0), "MB" to ("data" to 1048576.0),
        "GB" to ("data" to 1073741824.0), "TB" to ("data" to 1.099511627776e12),
        "m/s" to ("speed" to 1.0), "km/h" to ("speed" to 0.277778), "mph" to ("speed" to 0.44704),
        "knot" to ("speed" to 0.514444)
    )

    private val TEMPS = listOf("C", "F", "K")

    fun all(): List<Action> = listOf(

        act(ActionDef(
            "math.calc", "Calculate", Cat.MATH, "Calculate",
            summary = "%expr%",
            params = listOf(ParamSpec("expr", "Expression", ParamType.TEXT, "{{last}} + 1",
                hint = "+ - * / % ^, brackets, min max round sqrt abs random...")),
            output = "A number",
            description = "Evaluates a maths expression. Variables can be used anywhere in it.",
            keywords = "math calculate expression arithmetic compute sum"
        )) { env -> Expr.eval(env.str("expr")) },

        act(ActionDef(
            "math.round", "Round Number", Cat.MATH, "Adjust",
            summary = "%mode% %input%",
            params = listOf(
                ParamSpec("input", "Number", ParamType.NUMBER, "{{last}}"),
                ParamSpec("mode", "Mode", ParamType.CHOICE, "Nearest", listOf("Nearest", "Up", "Down", "Cut off")),
                ParamSpec("places", "Decimal places", ParamType.NUMBER, "0")
            ),
            output = "A number",
            keywords = "round ceil floor decimal precision"
        )) { env ->
            val n = env.num("input")
            val f = 10.0.pow(env.int("places", 0))
            when (env.choice("mode", "Nearest")) {
                "Up" -> ceil(n * f) / f
                "Down" -> floor(n * f) / f
                "Cut off" -> (n * f).toLong() / f
                else -> (n * f).roundToLong() / f
            }
        },

        act(ActionDef(
            "math.random", "Random Number", Cat.MATH, "Casino",
            summary = "Random %min%-%max%",
            params = listOf(
                ParamSpec("min", "Minimum", ParamType.NUMBER, "1"),
                ParamSpec("max", "Maximum", ParamType.NUMBER, "100"),
                ParamSpec("whole", "Whole numbers only", ParamType.BOOL, "true")
            ),
            output = "A number",
            keywords = "random dice number chance"
        )) { env ->
            val lo = env.num("min", 1.0)
            val hi = env.num("max", 100.0)
            val v = lo + Math.random() * (hi - lo + if (env.bool("whole", true)) 1.0 else 0.0)
            if (env.bool("whole", true)) floor(v).coerceAtMost(hi) else v
        },

        act(ActionDef(
            "math.stats", "List Statistics", Cat.MATH, "QueryStats",
            summary = "%mode% of %input%",
            params = listOf(
                ParamSpec("input", "Numbers", ParamType.MULTILINE, "{{last}}", hint = "a list, or one number per line"),
                ParamSpec("mode", "Calculate", ParamType.CHOICE, "Sum",
                    listOf("Sum", "Average", "Minimum", "Maximum", "Median", "Range", "Count"))
            ),
            output = "A number",
            keywords = "sum average min max median statistics total"
        )) { env ->
            val nums = V.list(env.value("input")).map { V.num(it) }
            if (nums.isEmpty()) return@act 0.0
            when (env.choice("mode", "Sum")) {
                "Average" -> nums.average()
                "Minimum" -> nums.min()
                "Maximum" -> nums.max()
                "Median" -> nums.sorted().let {
                    if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2
                }
                "Range" -> nums.max() - nums.min()
                "Count" -> nums.size.toDouble()
                else -> nums.sum()
            }
        },

        act(ActionDef(
            "math.format", "Format Number", Cat.MATH, "Pin",
            params = listOf(
                ParamSpec("input", "Number", ParamType.NUMBER, "{{last}}"),
                ParamSpec("decimals", "Decimal places", ParamType.NUMBER, "2"),
                ParamSpec("grouping", "Thousands separator", ParamType.BOOL, "true"),
                ParamSpec("prefix", "Prefix", ParamType.TEXT, "", hint = "e.g. a currency symbol"),
                ParamSpec("suffix", "Suffix", ParamType.TEXT, "")
            ),
            output = "Formatted text",
            keywords = "format currency decimal percent display"
        )) { env ->
            val d = env.int("decimals", 2)
            val pattern = StringBuilder(if (env.bool("grouping", true)) "#,##0" else "0")
            if (d > 0) { pattern.append('.'); repeat(d) { pattern.append('0') } }
            env.str("prefix") + DecimalFormat(pattern.toString()).format(env.num("input")) + env.str("suffix")
        },

        act(ActionDef(
            "math.convert", "Convert Units", Cat.MATH, "SwapHorizontalCircle",
            summary = "%input% %from% to %to%",
            params = listOf(
                ParamSpec("input", "Amount", ParamType.NUMBER, "{{last}}"),
                ParamSpec("from", "From", ParamType.CHOICE, "km", UNITS.keys.toList() + TEMPS),
                ParamSpec("to", "To", ParamType.CHOICE, "mile", UNITS.keys.toList() + TEMPS)
            ),
            output = "A number",
            description = "Converts length, mass, time, data, speed and temperature.",
            keywords = "convert unit metric imperial temperature celsius fahrenheit"
        )) { env ->
            val n = env.num("input")
            val from = env.choice("from", "km")
            val to = env.choice("to", "mile")
            if (from in TEMPS || to in TEMPS) {
                if (from !in TEMPS || to !in TEMPS) throw FlowError("Temperatures can only convert to temperatures")
                val k = when (from) { "C" -> n + 273.15; "F" -> (n - 32) * 5 / 9 + 273.15; else -> n }
                when (to) { "C" -> k - 273.15; "F" -> (k - 273.15) * 9 / 5 + 32; else -> k }
            } else {
                val a = UNITS[from] ?: throw FlowError("Unknown unit " + from)
                val b = UNITS[to] ?: throw FlowError("Unknown unit " + to)
                if (a.first != b.first) throw FlowError("Cannot convert " + a.first + " to " + b.first)
                n * a.second / b.second
            }
        },

        act(ActionDef(
            "math.number", "Get Number from Text", Cat.MATH, "Pin",
            params = listOf(
                ParamSpec("input", "Text", ParamType.TEXT, "{{last}}"),
                ParamSpec("mode", "Take", ParamType.CHOICE, "First number", listOf("First number", "All numbers", "Sum of all"))
            ),
            output = "A number",
            keywords = "parse number extract digits"
        )) { env ->
            val nums = Regex("-?\\d+(\\.\\d+)?").findAll(env.str("input")).map { it.value.toDouble() }.toList()
            when (env.choice("mode", "First number")) {
                "All numbers" -> nums
                "Sum of all" -> nums.sum()
                else -> nums.firstOrNull() ?: 0.0
            }
        }
    )
}
