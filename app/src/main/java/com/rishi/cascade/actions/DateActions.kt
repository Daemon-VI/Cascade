package com.rishi.cascade.actions

import com.rishi.cascade.engine.FlowError
import com.rishi.cascade.model.ActionDef
import com.rishi.cascade.model.Cat
import com.rishi.cascade.model.ParamSpec
import com.rishi.cascade.model.ParamType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object DateActions {

    private val PRESETS = listOf(
        "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd", "dd/MM/yyyy", "HH:mm", "hh:mm a",
        "EEEE, d MMMM yyyy", "d MMM yyyy", "Unix seconds", "Unix milliseconds", "Custom"
    )

    /** Accepts millis, seconds, ISO text or common formats. */
    fun toMillis(raw: String): Long {
        val t = raw.trim()
        if (t.isEmpty()) return System.currentTimeMillis()
        t.toLongOrNull()?.let { return if (it < 100_000_000_000L) it * 1000 else it }
        val fmts = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd",
            "dd/MM/yyyy HH:mm", "dd/MM/yyyy", "MM/dd/yyyy", "HH:mm:ss", "HH:mm"
        )
        for (f in fmts) {
            try {
                val d = SimpleDateFormat(f, Locale.getDefault()).parse(t)
                if (d != null) {
                    if (f.startsWith("HH")) {
                        val now = Calendar.getInstance()
                        val c = Calendar.getInstance().apply { time = d }
                        now.set(Calendar.HOUR_OF_DAY, c.get(Calendar.HOUR_OF_DAY))
                        now.set(Calendar.MINUTE, c.get(Calendar.MINUTE))
                        now.set(Calendar.SECOND, c.get(Calendar.SECOND))
                        return now.timeInMillis
                    }
                    return d.time
                }
            } catch (e: Exception) { /* try the next pattern */ }
        }
        throw FlowError("Could not read \"" + t + "\" as a date")
    }

    fun format(millis: Long, preset: String, custom: String): String = when (preset) {
        "Unix seconds" -> (millis / 1000).toString()
        "Unix milliseconds" -> millis.toString()
        "Custom" -> SimpleDateFormat(custom.ifBlank { "yyyy-MM-dd HH:mm" }, Locale.getDefault()).format(Date(millis))
        else -> SimpleDateFormat(preset, Locale.getDefault()).format(Date(millis))
    }

    private fun unitField(u: String): Int = when (u) {
        "Seconds" -> Calendar.SECOND
        "Minutes" -> Calendar.MINUTE
        "Hours" -> Calendar.HOUR_OF_DAY
        "Days" -> Calendar.DAY_OF_MONTH
        "Weeks" -> Calendar.WEEK_OF_YEAR
        "Months" -> Calendar.MONTH
        "Years" -> Calendar.YEAR
        else -> Calendar.DAY_OF_MONTH
    }

    private val UNITS = listOf("Seconds", "Minutes", "Hours", "Days", "Weeks", "Months", "Years")

    fun all(): List<Action> = listOf(

        act(ActionDef(
            "date.now", "Current Date", Cat.DATE, "Today",
            summary = "Now as %format%",
            params = listOf(
                ParamSpec("format", "Format", ParamType.CHOICE, "yyyy-MM-dd HH:mm:ss", PRESETS),
                ParamSpec("custom", "Custom pattern", ParamType.TEXT, "", showIf = "format" to listOf("Custom"),
                    hint = "e.g. EEE d MMM, HH:mm")
            ),
            output = "The current date as text",
            keywords = "date time now today clock timestamp"
        )) { env ->
            format(System.currentTimeMillis(), env.choice("format", "yyyy-MM-dd HH:mm:ss"), env.str("custom"))
        },

        act(ActionDef(
            "date.format", "Format Date", Cat.DATE, "EditCalendar",
            summary = "Format %input%",
            params = listOf(
                ParamSpec("input", "Date", ParamType.TEXT, "{{last}}", hint = "text, unix seconds or millis"),
                ParamSpec("format", "Format", ParamType.CHOICE, "yyyy-MM-dd", PRESETS),
                ParamSpec("custom", "Custom pattern", ParamType.TEXT, "", showIf = "format" to listOf("Custom"))
            ),
            output = "The formatted date",
            keywords = "date format convert display"
        )) { env ->
            format(toMillis(env.str("input")), env.choice("format", "yyyy-MM-dd"), env.str("custom"))
        },

        act(ActionDef(
            "date.adjust", "Adjust Date", Cat.DATE, "MoreTime",
            summary = "%op% %amount% %unit%",
            params = listOf(
                ParamSpec("input", "Date", ParamType.TEXT, "", hint = "blank means now"),
                ParamSpec("op", "Operation", ParamType.CHOICE, "Add", listOf("Add", "Subtract")),
                ParamSpec("amount", "Amount", ParamType.NUMBER, "1"),
                ParamSpec("unit", "Unit", ParamType.CHOICE, "Days", UNITS),
                ParamSpec("format", "Format", ParamType.CHOICE, "yyyy-MM-dd HH:mm", PRESETS),
                ParamSpec("custom", "Custom pattern", ParamType.TEXT, "", showIf = "format" to listOf("Custom"))
            ),
            output = "The new date as text",
            keywords = "date add subtract tomorrow yesterday offset"
        )) { env ->
            val c = Calendar.getInstance().apply { timeInMillis = toMillis(env.str("input")) }
            val amt = env.int("amount", 1) * (if (env.choice("op", "Add") == "Subtract") -1 else 1)
            c.add(unitField(env.choice("unit", "Days")), amt)
            format(c.timeInMillis, env.choice("format", "yyyy-MM-dd HH:mm"), env.str("custom"))
        },

        act(ActionDef(
            "date.diff", "Time Between Dates", Cat.DATE, "Timelapse",
            summary = "%from% to %to% in %unit%",
            params = listOf(
                ParamSpec("from", "From", ParamType.TEXT, "", hint = "blank means now"),
                ParamSpec("to", "To", ParamType.TEXT, "{{last}}"),
                ParamSpec("unit", "In", ParamType.CHOICE, "Days", UNITS),
                ParamSpec("abs", "Always positive", ParamType.BOOL, "false")
            ),
            output = "A number",
            keywords = "date difference between duration countdown age"
        )) { env ->
            val a = toMillis(env.str("from"))
            val b = toMillis(env.str("to"))
            val ms = (b - a).toDouble()
            val div = when (env.choice("unit", "Days")) {
                "Seconds" -> 1000.0
                "Minutes" -> 60_000.0
                "Hours" -> 3_600_000.0
                "Days" -> 86_400_000.0
                "Weeks" -> 604_800_000.0
                "Months" -> 2_629_800_000.0
                else -> 31_557_600_000.0
            }
            val v = ms / div
            if (env.bool("abs", false)) kotlin.math.abs(v) else v
        },

        act(ActionDef(
            "date.part", "Get Date Part", Cat.DATE, "CalendarViewMonth",
            summary = "%part% of %input%",
            params = listOf(
                ParamSpec("input", "Date", ParamType.TEXT, "", hint = "blank means now"),
                ParamSpec("part", "Part", ParamType.CHOICE, "Hour",
                    listOf("Year", "Month", "Month name", "Day", "Hour", "Minute", "Second",
                        "Weekday", "Weekday name", "Day of year", "Week of year", "Is weekend"))
            ),
            output = "A number or name",
            description = "Pulls one field out of a date. Great for If conditions on the hour or weekday.",
            keywords = "hour minute weekday year month day part component"
        )) { env ->
            val c = Calendar.getInstance().apply { timeInMillis = toMillis(env.str("input")) }
            when (env.choice("part", "Hour")) {
                "Year" -> c.get(Calendar.YEAR).toDouble()
                "Month" -> (c.get(Calendar.MONTH) + 1).toDouble()
                "Month name" -> SimpleDateFormat("MMMM", Locale.getDefault()).format(c.time)
                "Day" -> c.get(Calendar.DAY_OF_MONTH).toDouble()
                "Minute" -> c.get(Calendar.MINUTE).toDouble()
                "Second" -> c.get(Calendar.SECOND).toDouble()
                "Weekday" -> c.get(Calendar.DAY_OF_WEEK).toDouble()
                "Weekday name" -> SimpleDateFormat("EEEE", Locale.getDefault()).format(c.time)
                "Day of year" -> c.get(Calendar.DAY_OF_YEAR).toDouble()
                "Week of year" -> c.get(Calendar.WEEK_OF_YEAR).toDouble()
                "Is weekend" -> c.get(Calendar.DAY_OF_WEEK).let { it == Calendar.SATURDAY || it == Calendar.SUNDAY }
                else -> c.get(Calendar.HOUR_OF_DAY).toDouble()
            }
        }
    )
}
