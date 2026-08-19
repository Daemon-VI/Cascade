package com.rishi.cascade.store

import com.rishi.cascade.actions.Registry
import com.rishi.cascade.model.Flow
import com.rishi.cascade.model.Step

/** Ready-made flows, so the app is useful the moment it opens. */
object Templates {

    private fun step(action: String, vararg params: Pair<String, String>, out: String? = null): Step {
        val s = Step(actionId = action, params = Registry.defaults(action))
        params.forEach { (k, v) -> s.params[k] = v }
        s.outputName = out
        return s
    }

    fun all(): List<Flow> = listOf(morningBrief(), batterySaver(), focusMode(), whereAmI(), quickNote(), isItUp())

    private fun morningBrief() = Flow(
        name = "Morning brief", icon = "WbSunny", color = 3,
        steps = mutableListOf(
            step("date.now", "format" to "EEEE, d MMMM yyyy", out = "today"),
            step("net.http", "method" to "GET", "url" to "https://wttr.in/?format=3",
                "returns" to "Body as text", out = "weather"),
            step("dev.battery", "field" to "Level", out = "battery"),
            step(
                "text.set",
                "text" to "Good morning. Today is {{today}}. {{weather}}. Your battery is at {{battery}} percent.",
                out = "brief"
            ),
            step("media.speak", "text" to "{{brief}}", "wait" to "true"),
            step("ui.result", "title" to "Morning brief", "value" to "{{brief}}")
        )
    )

    private fun batterySaver() = Flow(
        name = "Battery rescue", icon = "Bolt", color = 4,
        steps = mutableListOf(
            step("dev.battery", "field" to "Level", out = "level"),
            step("flow.if", "input" to "{{level}}", "op" to "less than", "value" to "25"),
            step("dev.brightness", "auto" to "Turn off", "level" to "30"),
            step("dev.ringer", "mode" to "Vibrate"),
            step("ui.notify", "title" to "Battery rescue",
                "text" to "Battery is {{level}}%. Brightness dimmed and ringer set to vibrate."),
            step("flow.else"),
            step("ui.toast", "text" to "Battery is fine at {{level}}%"),
            step("flow.endif")
        )
    )

    private fun focusMode() = Flow(
        name = "Focus mode", icon = "School", color = 0,
        steps = mutableListOf(
            step("ui.menu", "prompt" to "How long?", "options" to "25 minutes\n45 minutes\n60 minutes",
                out = "choice"),
            step("math.number", "input" to "{{choice}}", "mode" to "First number", out = "minutes"),
            step("dev.dnd", "mode" to "On"),
            step("ui.notify", "title" to "Focus started", "text" to "Do Not Disturb on for {{minutes}} minutes",
                "tag" to "focus"),
            step("math.calc", "expr" to "{{minutes}} * 60", out = "seconds"),
            step("flow.wait", "seconds" to "{{seconds}}"),
            step("dev.dnd", "mode" to "Off"),
            step("media.speak", "text" to "Focus session finished.", "wait" to "true"),
            step("ui.notify", "title" to "Focus finished", "text" to "{{minutes}} minutes done.", "tag" to "focus")
        )
    )

    private fun whereAmI() = Flow(
        name = "Where am I", icon = "Map", color = 2,
        steps = mutableListOf(
            step("loc.current", "field" to "Everything", "timeout" to "20", out = "place"),
            step("text.set", "text" to "{{place.address}}\n\n{{place.latitude}}, {{place.longitude}}\nAccurate to {{place.accuracy}} m",
                out = "summary"),
            step("dev.clip.set", "text" to "{{summary}}"),
            step("ui.result", "title" to "You are here", "value" to "{{summary}}")
        )
    )

    private fun quickNote() = Flow(
        name = "Quick note", icon = "PostAdd", color = 6,
        steps = mutableListOf(
            step("ui.ask", "prompt" to "What do you want to note down?", "kind" to "Long text", out = "note"),
            step("file.log", "path" to "notes.txt", "text" to "{{note}}", "timestamp" to "true", out = "file"),
            step("ui.toast", "text" to "Saved to notes.txt")
        )
    )

    private fun isItUp() = Flow(
        name = "Is my site up", icon = "Wifi", color = 1,
        steps = mutableListOf(
            step("ui.ask", "prompt" to "Which site?", "default" to "https://github.com", out = "url"),
            step("flow.try"),
            step("net.http", "method" to "GET", "url" to "{{url}}", "returns" to "Status code",
                "timeout" to "15", out = "status"),
            step("ui.alert", "title" to "It is up", "message" to "{{url}} answered with HTTP {{status}}"),
            step("flow.catch"),
            step("ui.alert", "title" to "No answer", "message" to "{{url}} did not respond.\n\n{{error}}"),
            step("flow.endtry")
        )
    )

    fun install(store: FlowStore) {
        all().forEach { store.save(it) }
    }
}
