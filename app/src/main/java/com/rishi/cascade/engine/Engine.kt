package com.rishi.cascade.engine

import android.content.Context
import com.rishi.cascade.actions.Registry
import com.rishi.cascade.model.BlockKind
import com.rishi.cascade.model.Flow
import com.rishi.cascade.model.Step
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Pairs a block-opening step with its optional middle (else / catch) and its end. */
data class BlockPair(val begin: Int, var middle: Int = -1, var end: Int = -1)

object Blocks {
    /** Matches BEGIN/MIDDLE/END steps into pairs, tolerating unbalanced flows. */
    fun pair(steps: List<Step>): Map<Int, BlockPair> {
        val out = HashMap<Int, BlockPair>()
        val stack = ArrayDeque<BlockPair>()
        steps.forEachIndexed { i, s ->
            when (Registry.def(s.actionId)?.block ?: BlockKind.NONE) {
                BlockKind.BEGIN -> {
                    val p = BlockPair(i)
                    stack.addLast(p)
                    out[i] = p
                }
                BlockKind.MIDDLE -> {
                    stack.lastOrNull()?.let { it.middle = i; out[i] = it }
                }
                BlockKind.END -> {
                    val p = stack.removeLastOrNull()
                    if (p != null) { p.end = i; out[i] = p }
                }
                BlockKind.NONE -> {}
            }
        }
        // Unbalanced BEGINs run to the end of the flow.
        stack.forEach { if (it.end < 0) it.end = steps.size }
        return out
    }

    /** Indentation level for each step, for the editor. */
    fun indents(steps: List<Step>): IntArray {
        val out = IntArray(steps.size)
        var d = 0
        steps.forEachIndexed { i, s ->
            when (Registry.def(s.actionId)?.block ?: BlockKind.NONE) {
                BlockKind.BEGIN -> { out[i] = d; d++ }
                BlockKind.MIDDLE -> { out[i] = (d - 1).coerceAtLeast(0) }
                BlockKind.END -> { d = (d - 1).coerceAtLeast(0); out[i] = d }
                BlockKind.NONE -> out[i] = d
            }
        }
        return out
    }
}

private class LoopFrame(
    val begin: Int,
    val end: Int,
    var iteration: Int = 0,
    val total: Int = -1,
    val items: List<Any?>? = null,
    val indexVar: String = "index",
    val itemVar: String = "item"
)

private class TryFrame(val catchIdx: Int, val endIdx: Int)

class Engine(
    val app: Context,
    val flow: Flow,
    val io: FlowIO,
    val depth: Int = 0
) {
    val ctx = RunContext(flow.id, flow.name)
    private val steps = flow.steps
    private val pairs = Blocks.pair(steps)
    private val loops = HashMap<Int, LoopFrame>()
    private val tries = ArrayDeque<TryFrame>()
    private var executed = 0

    companion object {
        const val MAX_STEPS = 200_000
        const val MAX_DEPTH = 8
    }

    fun cancel() { ctx.cancelled = true }

    suspend fun run(input: Any? = null): Any? {
        ctx.last = input
        input?.let { ctx.set("input", it) }
        var pc = 0
        while (pc < steps.size) {
            coroutineContext.ensureActive()
            if (ctx.cancelled) throw FlowStopped(true, "Cancelled")
            if (++executed > MAX_STEPS) throw FlowError("Runaway flow: over $MAX_STEPS steps executed")
            val step = steps[pc]
            if (!step.enabled) { pc++; continue }
            val def = Registry.def(step.actionId)
            if (def == null) {
                logStep(pc, "Unknown action", step.actionId, 2)
                pc++
                continue
            }
            io.progress(pc, def.title)
            pc = try {
                execute(pc, step)
            } catch (e: FlowStopped) {
                throw e
            } catch (e: Exception) {
                val frame = tries.lastOrNull()
                if (frame != null) {
                    tries.removeLast()
                    ctx.set("error", e.message ?: e.javaClass.simpleName)
                    logStep(pc, "Caught error", e.message ?: "", 2)
                    frame.catchIdx + 1
                } else {
                    logStep(pc, "Error in " + def.title, e.message ?: e.javaClass.simpleName, 2)
                    throw FlowError((def.title) + ": " + (e.message ?: e.javaClass.simpleName))
                }
            }
        }
        return ctx.last
    }

    private fun logStep(index: Int, title: String, detail: String, level: Int) {
        val l = LogLine(System.currentTimeMillis(), index, title, detail, level)
        ctx.logs.add(l)
        io.log(l)
    }

    /** Runs one step and returns the next program counter. */
    private suspend fun execute(pc: Int, step: Step): Int {
        val env = Env(app, ctx, step, io, this)
        when (step.actionId) {
            "flow.comment" -> return pc + 1

            "flow.if" -> {
                val p = pairs[pc] ?: return pc + 1
                val ok = Conditions.test(env)
                logStep(pc, "If", if (ok) "true" else "false", 0)
                return if (ok) pc + 1 else (if (p.middle >= 0) p.middle + 1 else p.end + 1)
            }
            "flow.else" -> {
                val p = pairs[pc] ?: return pc + 1
                return p.end + 1
            }
            "flow.endif" -> return pc + 1

            "flow.repeat" -> {
                val p = pairs[pc] ?: return pc + 1
                val frame = loops.getOrPut(pc) {
                    LoopFrame(pc, p.end, 0, env.int("times", 1).coerceAtLeast(0),
                        indexVar = env.raw("indexVar").ifBlank { "index" })
                }
                if (frame.iteration >= frame.total) { loops.remove(pc); return p.end + 1 }
                ctx.set(frame.indexVar, (frame.iteration + 1).toDouble())
                frame.iteration++
                return pc + 1
            }
            "flow.foreach" -> {
                val p = pairs[pc] ?: return pc + 1
                val frame = loops.getOrPut(pc) {
                    LoopFrame(pc, p.end, 0, -1, env.list("list"),
                        indexVar = env.raw("indexVar").ifBlank { "index" },
                        itemVar = env.raw("itemVar").ifBlank { "item" })
                }
                val items = frame.items ?: emptyList()
                if (frame.iteration >= items.size) { loops.remove(pc); return p.end + 1 }
                ctx.set(frame.indexVar, (frame.iteration + 1).toDouble())
                ctx.set(frame.itemVar, items[frame.iteration])
                ctx.last = items[frame.iteration]
                frame.iteration++
                return pc + 1
            }
            "flow.while" -> {
                val p = pairs[pc] ?: return pc + 1
                val frame = loops.getOrPut(pc) { LoopFrame(pc, p.end) }
                if (frame.iteration > 10_000) { loops.remove(pc); throw FlowError("While loop exceeded 10000 iterations") }
                frame.iteration++
                return if (Conditions.test(env)) pc + 1 else { loops.remove(pc); p.end + 1 }
            }
            "flow.endrepeat", "flow.endforeach", "flow.endwhile" -> {
                val p = pairs[pc] ?: return pc + 1
                return p.begin
            }

            "flow.try" -> {
                val p = pairs[pc] ?: return pc + 1
                tries.addLast(TryFrame(if (p.middle >= 0) p.middle else p.end, p.end))
                return pc + 1
            }
            "flow.catch" -> {
                val p = pairs[pc] ?: return pc + 1
                return p.end + 1   // reached only when the try body finished cleanly
            }
            "flow.endtry" -> {
                tries.removeLastOrNull()
                return pc + 1
            }

            "flow.break" -> {
                val target = loops.values.maxByOrNull { it.begin }
                return if (target == null) pc + 1 else { loops.remove(target.begin); target.end + 1 }
            }
            "flow.continue" -> {
                val target = loops.values.maxByOrNull { it.begin }
                return target?.end ?: (pc + 1)
            }

            "flow.stop" -> {
                logStep(pc, "Stop", env.str("reason"), 1)
                throw FlowStopped(env.raw("reason").isBlank(), env.str("reason", "Flow stopped"))
            }
            "flow.exitif" -> {
                if (Conditions.test(env)) throw FlowStopped(true, "Exited")
                return pc + 1
            }
            "flow.wait" -> {
                val ms = (env.num("seconds", 1.0) * 1000).toLong().coerceIn(0, 3_600_000)
                logStep(pc, "Wait", V.num2str(ms / 1000.0) + "s", 0)
                delay(ms)
                return pc + 1
            }
        }

        val action = Registry.get(step.actionId) ?: throw FlowError("Action not available: " + step.actionId)
        val result = action.run(env)
        ctx.last = result
        step.outputName?.takeIf { it.isNotBlank() }?.let { ctx.set(it, result) }
        val def = Registry.def(step.actionId)
        logStep(pc, def?.title ?: step.actionId, shortPreview(result), 0)
        return pc + 1
    }

    private fun shortPreview(v: Any?): String {
        if (v == null) return ""
        val t = V.text(v).replace("\n", " ")
        return if (t.length > 160) t.take(160) + "..." else t
    }
}

/** Shared condition evaluation for If / While / Exit-if. */
object Conditions {
    val OPERATORS = listOf(
        "is", "is not", "contains", "does not contain", "begins with", "ends with",
        "matches regex", "is empty", "is not empty", "greater than", "greater or equal",
        "less than", "less or equal", "between", "is in list", "expression is true"
    )

    fun test(env: Env): Boolean {
        val op = env.choice("op", "is")
        if (op == "expression is true") {
            val src = env.str("expr")
            return (Expr.evalOrNull(src) ?: 0.0) != 0.0
        }
        val left = env.value("input")
        val lt = V.text(left)
        val rt = env.str("value")
        return when (op) {
            "is" -> lt.equals(rt, ignoreCase = env.bool("ignoreCase", true)) ||
                    (V.num(left) == V.num(rt) && lt.isNotEmpty() && rt.isNotEmpty() &&
                     lt.toDoubleOrNull() != null && rt.toDoubleOrNull() != null)
            "is not" -> !lt.equals(rt, ignoreCase = env.bool("ignoreCase", true))
            "contains" -> lt.contains(rt, ignoreCase = env.bool("ignoreCase", true))
            "does not contain" -> !lt.contains(rt, ignoreCase = env.bool("ignoreCase", true))
            "begins with" -> lt.startsWith(rt, ignoreCase = env.bool("ignoreCase", true))
            "ends with" -> lt.endsWith(rt, ignoreCase = env.bool("ignoreCase", true))
            "matches regex" -> try { Regex(rt).containsMatchIn(lt) } catch (e: Exception) { false }
            "is empty" -> lt.isBlank()
            "is not empty" -> lt.isNotBlank()
            "greater than" -> V.num(left) > V.num(rt)
            "greater or equal" -> V.num(left) >= V.num(rt)
            "less than" -> V.num(left) < V.num(rt)
            "less or equal" -> V.num(left) <= V.num(rt)
            "between" -> {
                val parts = rt.split(",", "-").mapNotNull { it.trim().toDoubleOrNull() }
                if (parts.size < 2) false else V.num(left) >= parts[0] && V.num(left) <= parts[1]
            }
            "is in list" -> V.list(env.value("value")).any { V.text(it).trim() == lt.trim() } ||
                    rt.split(",").any { it.trim().equals(lt.trim(), true) }
            else -> false
        }
    }
}
