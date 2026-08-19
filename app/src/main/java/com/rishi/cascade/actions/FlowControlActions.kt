package com.rishi.cascade.actions

import com.rishi.cascade.engine.Conditions
import com.rishi.cascade.engine.Engine
import com.rishi.cascade.engine.FlowError
import com.rishi.cascade.model.ActionDef
import com.rishi.cascade.model.BlockKind
import com.rishi.cascade.model.Cat
import com.rishi.cascade.model.ParamSpec
import com.rishi.cascade.model.ParamType
import com.rishi.cascade.store.FlowStore

object FlowControlActions {

    /** Operators that compare against a second value. */
    private val VALUE_OPS = Conditions.OPERATORS.filter {
        it != "is empty" && it != "is not empty" && it != "expression is true"
    }

    fun conditionParams(inputDefault: String = "{{last}}"): List<ParamSpec> = listOf(
        ParamSpec("input", "Value", ParamType.TEXT, inputDefault, hint = "Tap the chip to insert a variable"),
        ParamSpec("op", "Condition", ParamType.CHOICE, "is", Conditions.OPERATORS),
        ParamSpec("value", "Compare with", ParamType.TEXT, "", hint = "text, number or {{variable}}",
            showIf = "op" to VALUE_OPS),
        ParamSpec("expr", "Expression", ParamType.TEXT, "", hint = "e.g. {{battery}} < 20 && {{hour}} > 18",
            showIf = "op" to listOf("expression is true")),
        ParamSpec("ignoreCase", "Ignore case", ParamType.BOOL, "true",
            showIf = "op" to listOf("is", "is not", "contains", "does not contain", "begins with", "ends with"))
    )

    fun all(): List<Action> = listOf(
        ControlAction(ActionDef(
            "flow.if", "If", Cat.FLOWCTRL, "CallSplit",
            summary = "If %input% %op% %value%",
            params = conditionParams(),
            block = BlockKind.BEGIN, output = null,
            description = "Runs the steps below only when the condition holds.",
            keywords = "condition branch when compare"
        )),
        ControlAction(ActionDef(
            "flow.else", "Otherwise", Cat.FLOWCTRL, "AltRoute",
            block = BlockKind.MIDDLE, output = null,
            description = "The branch taken when the If above was false.",
            keywords = "else"
        )),
        ControlAction(ActionDef(
            "flow.endif", "End If", Cat.FLOWCTRL, "SubdirectoryArrowLeft",
            block = BlockKind.END, output = null, keywords = "endif"
        )),

        ControlAction(ActionDef(
            "flow.repeat", "Repeat", Cat.FLOWCTRL, "Repeat",
            summary = "Repeat %times% times",
            params = listOf(
                ParamSpec("times", "Times", ParamType.NUMBER, "3"),
                ParamSpec("indexVar", "Index variable", ParamType.VARNAME, "index")
            ),
            block = BlockKind.BEGIN, output = null,
            description = "Runs the steps below a fixed number of times.",
            keywords = "loop for times"
        )),
        ControlAction(ActionDef(
            "flow.endrepeat", "End Repeat", Cat.FLOWCTRL, "SubdirectoryArrowLeft",
            block = BlockKind.END, output = null
        )),

        ControlAction(ActionDef(
            "flow.foreach", "Repeat with Each", Cat.FLOWCTRL, "FormatListNumbered",
            summary = "For each item in %list%",
            params = listOf(
                ParamSpec("list", "List", ParamType.TEXT, "{{last}}", hint = "a list variable or lines of text"),
                ParamSpec("itemVar", "Item variable", ParamType.VARNAME, "item"),
                ParamSpec("indexVar", "Index variable", ParamType.VARNAME, "index")
            ),
            block = BlockKind.BEGIN, output = null,
            description = "Runs the steps below once per item in a list.",
            keywords = "loop foreach each iterate list"
        )),
        ControlAction(ActionDef(
            "flow.endforeach", "End Repeat with Each", Cat.FLOWCTRL, "SubdirectoryArrowLeft",
            block = BlockKind.END, output = null
        )),

        ControlAction(ActionDef(
            "flow.while", "While", Cat.FLOWCTRL, "Loop",
            summary = "While %input% %op% %value%",
            params = conditionParams(),
            block = BlockKind.BEGIN, output = null,
            description = "Repeats the steps below for as long as the condition holds (max 10000 loops).",
            keywords = "loop until while"
        )),
        ControlAction(ActionDef(
            "flow.endwhile", "End While", Cat.FLOWCTRL, "SubdirectoryArrowLeft",
            block = BlockKind.END, output = null
        )),

        ControlAction(ActionDef(
            "flow.try", "Try", Cat.FLOWCTRL, "Shield",
            block = BlockKind.BEGIN, output = null,
            description = "Runs the steps below; if any of them fail, jump to On Error instead of stopping.",
            keywords = "error catch handle fail"
        )),
        ControlAction(ActionDef(
            "flow.catch", "On Error", Cat.FLOWCTRL, "ErrorOutline",
            block = BlockKind.MIDDLE, output = null,
            description = "Runs when a step inside Try failed. The message is in {{error}}."
        )),
        ControlAction(ActionDef(
            "flow.endtry", "End Try", Cat.FLOWCTRL, "SubdirectoryArrowLeft",
            block = BlockKind.END, output = null
        )),

        ControlAction(ActionDef(
            "flow.break", "Break Loop", Cat.FLOWCTRL, "Stop", output = null,
            description = "Leaves the innermost loop.", keywords = "exit loop break"
        )),
        ControlAction(ActionDef(
            "flow.continue", "Next Loop Iteration", Cat.FLOWCTRL, "FastForward", output = null,
            description = "Skips the rest of this pass through the loop.", keywords = "continue skip"
        )),
        ControlAction(ActionDef(
            "flow.wait", "Wait", Cat.FLOWCTRL, "HourglassBottom",
            summary = "Wait %seconds%s",
            params = listOf(ParamSpec("seconds", "Seconds", ParamType.NUMBER, "1")),
            output = null, keywords = "delay sleep pause"
        )),
        ControlAction(ActionDef(
            "flow.stop", "Stop Flow", Cat.FLOWCTRL, "DoNotDisturbOn",
            params = listOf(ParamSpec("reason", "Message", ParamType.TEXT, "", hint = "optional")),
            output = null, keywords = "end quit abort"
        )),
        ControlAction(ActionDef(
            "flow.exitif", "Stop If", Cat.FLOWCTRL, "ExitToApp",
            summary = "Stop if %input% %op% %value%",
            params = conditionParams(), output = null,
            keywords = "guard stop condition"
        )),
        ControlAction(ActionDef(
            "flow.comment", "Comment", Cat.FLOWCTRL, "Notes",
            summary = "%text%",
            params = listOf(ParamSpec("text", "Note", ParamType.MULTILINE, "")),
            output = null, keywords = "note remark"
        )),

        act(ActionDef(
            "flow.setvar", "Set Variable", Cat.FLOWCTRL, "DataObject",
            summary = "%name% = %value%",
            params = listOf(
                ParamSpec("name", "Variable name", ParamType.VARNAME, "myVar"),
                ParamSpec("value", "Value", ParamType.TEXT, "{{last}}")
            ),
            output = "The value",
            description = "Stores a value you can use later as {{name}}.",
            keywords = "variable store assign let"
        )) { env ->
            val name = env.raw("name").trim().ifBlank { "myVar" }
            val v = env.value("value")
            env.ctx.set(name, v)
            v
        },

        act(ActionDef(
            "flow.runflow", "Run Flow", Cat.FLOWCTRL, "PlayCircle",
            summary = "Run %flow%",
            params = listOf(
                ParamSpec("flow", "Flow", ParamType.FLOW, ""),
                ParamSpec("input", "Input", ParamType.TEXT, "", hint = "becomes {{input}} in that flow")
            ),
            output = "Whatever the other flow ends with",
            description = "Runs another flow as a sub-routine and returns its result.",
            keywords = "subflow call nested function"
        )) { env ->
            if (env.engine.depth >= Engine.MAX_DEPTH) throw FlowError("Flows nested too deep")
            val target = FlowStore.get(env.app).load(env.raw("flow"))
                ?: throw FlowError("That flow no longer exists")
            val sub = Engine(env.app, target, env.io, env.engine.depth + 1)
            val out = sub.run(env.value("input"))
            sub.ctx.vars.forEach { (k, v) -> if (k.startsWith("out.")) env.ctx.set(k, v) }
            out
        }
    )
}
