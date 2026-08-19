package com.rishi.cascade.model

enum class ParamType {
    TEXT,        // single-line, supports {{vars}}
    MULTILINE,   // big text box, supports {{vars}}
    NUMBER,      // numeric, supports {{vars}}
    BOOL,        // switch
    CHOICE,      // dropdown from options
    APP,         // app picker
    FLOW,        // pick another flow
    DURATION,    // seconds
    KEYVALUE,    // "key: value" per line
    FILEPATH,    // path in app storage / shared storage
    VARNAME      // name of a variable to write
}

data class ParamSpec(
    val key: String,
    val label: String,
    val type: ParamType = ParamType.TEXT,
    val default: String = "",
    val options: List<String> = emptyList(),
    val hint: String = "",
    /** only show this param when another param equals one of these values */
    val showIf: Pair<String, List<String>>? = null
)

enum class BlockKind { NONE, BEGIN, MIDDLE, END }

data class ActionDef(
    val id: String,
    val title: String,
    val category: String,
    val icon: String = "Bolt",
    /** short template for the collapsed card, e.g. "Wait %seconds%s" */
    val summary: String = "",
    val params: List<ParamSpec> = emptyList(),
    val block: BlockKind = BlockKind.NONE,
    /** result description shown in the variable picker; null = produces nothing */
    val output: String? = "Result",
    val description: String = "",
    /** runtime permissions this action needs */
    val permissions: List<String> = emptyList(),
    /** special access (write settings, notification listener, ...) */
    val specialAccess: String? = null,
    val keywords: String = ""
) {
    fun spec(key: String): ParamSpec? = params.firstOrNull { it.key == key }
}

object Cat {
    const val FLOWCTRL = "Control Flow"
    const val TEXT = "Text"
    const val MATH = "Math"
    const val DATE = "Date & Time"
    const val DEVICE = "Device"
    const val APPS = "Apps & Intents"
    const val NET = "Network"
    const val FILES = "Files"
    const val MEDIA = "Media"
    const val UI = "Interaction"
    const val LOCATION = "Location"
    const val DATA = "Data & Encoding"
    const val COMMS = "Communication"
    const val SCRIPT = "Scripting"

    val ORDER = listOf(
        FLOWCTRL, UI, TEXT, MATH, DATE, DATA, DEVICE, APPS,
        NET, FILES, MEDIA, LOCATION, COMMS, SCRIPT
    )
}
