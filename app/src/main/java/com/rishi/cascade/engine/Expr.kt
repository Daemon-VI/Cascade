package com.rishi.cascade.engine

import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Small recursive-descent evaluator: arithmetic, comparisons, logic and common functions.
 *  Powers the Calculate action and expression conditions. */
object Expr {
    fun eval(src: String): Double = Parser(src).parseAll()

    fun evalOrNull(src: String): Double? = try {
        eval(src)
    } catch (e: Exception) {
        null
    }

    private class Parser(val s: String) {
        var i = 0

        fun parseAll(): Double {
            val v = or()
            skip()
            if (i < s.length) throw FlowError("Unexpected text in expression: " + s.substring(i))
            return v
        }

        fun skip() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun eat(tok: String): Boolean {
            skip()
            if (s.startsWith(tok, i)) {
                i += tok.length
                return true
            }
            return false
        }

        fun b(x: Boolean) = if (x) 1.0 else 0.0

        fun or(): Double {
            var v = and()
            while (true) {
                v = if (eat("||") || eat("or ")) {
                    val r = and(); b(v != 0.0 || r != 0.0)
                } else return v
            }
        }

        fun and(): Double {
            var v = cmp()
            while (true) {
                v = if (eat("&&") || eat("and ")) {
                    val r = cmp(); b(v != 0.0 && r != 0.0)
                } else return v
            }
        }

        fun cmp(): Double {
            var v = add()
            while (true) {
                v = when {
                    eat("<=") -> b(v <= add())
                    eat(">=") -> b(v >= add())
                    eat("==") -> b(abs(v - add()) < 1e-12)
                    eat("!=") -> b(abs(v - add()) >= 1e-12)
                    eat("<") -> b(v < add())
                    eat(">") -> b(v > add())
                    else -> return v
                }
            }
        }

        fun add(): Double {
            var v = mul()
            while (true) {
                v = when {
                    eat("+") -> v + mul()
                    eat("-") -> v - mul()
                    else -> return v
                }
            }
        }

        fun mul(): Double {
            var v = unary()
            while (true) {
                v = when {
                    eat("*") -> v * unary()
                    eat("/") -> {
                        val d = unary()
                        if (d == 0.0) throw FlowError("Division by zero")
                        v / d
                    }
                    eat("%") -> {
                        val d = unary()
                        if (d == 0.0) throw FlowError("Division by zero")
                        v.mod(d)
                    }
                    else -> return v
                }
            }
        }

        fun unary(): Double {
            skip()
            if (eat("-")) return -unary()
            if (eat("+")) return unary()
            if (eat("!")) return b(unary() == 0.0)
            return power()
        }

        fun power(): Double {
            val base = atom()
            skip()
            if (eat("**") || eat("^")) return base.pow(unary())
            return base
        }

        fun atom(): Double {
            skip()
            if (i >= s.length) throw FlowError("Expression ended early")
            if (eat("(")) {
                val v = or()
                if (!eat(")")) throw FlowError("Missing closing bracket")
                return v
            }
            val c = s[i]
            if (c.isDigit() || c == '.') {
                val start = i
                while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' ||
                            ((s[i] == '-' || s[i] == '+') && i > start && (s[i - 1] == 'e' || s[i - 1] == 'E')))
                ) i++
                return s.substring(start, i).toDoubleOrNull() ?: throw FlowError("Bad number")
            }
            if (c.isLetter() || c == '_') {
                val start = i
                while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                val name = s.substring(start, i).lowercase()
                skip()
                if (i < s.length && s[i] == '(') {
                    i++
                    val args = mutableListOf<Double>()
                    skip()
                    if (!eat(")")) {
                        do {
                            args.add(or())
                        } while (eat(","))
                        if (!eat(")")) throw FlowError("Missing closing bracket after " + name)
                    }
                    return callFn(name, args)
                }
                return constant(name)
            }
            throw FlowError("Unexpected character in expression: " + c)
        }

        fun constant(name: String): Double = when (name) {
            "pi" -> PI
            "e" -> E
            "true" -> 1.0
            "false" -> 0.0
            else -> throw FlowError("Unknown name: " + name)
        }

        fun callFn(name: String, a: List<Double>): Double {
            val first = a.getOrNull(0)
            fun one(): Double = first ?: throw FlowError(name + " needs an argument")
            return when (name) {
                "abs" -> abs(one())
                "round" -> if (a.size > 1) {
                    val f = 10.0.pow(a[1]); (a[0] * f).roundToLong() / f
                } else one().roundToLong().toDouble()
                "floor" -> floor(one())
                "ceil" -> ceil(one())
                "sqrt" -> sqrt(one())
                "cbrt" -> cbrt(one())
                "ln" -> ln(one())
                "log" -> if (a.size > 1) log(a[0], a[1]) else log10(one())
                "exp" -> exp(one())
                "sin" -> sin(one())
                "cos" -> cos(one())
                "tan" -> tan(one())
                "asin" -> asin(one())
                "acos" -> acos(one())
                "atan" -> atan(one())
                "atan2" -> atan2(a[0], a[1])
                "pow" -> a[0].pow(a[1])
                "min" -> a.minOrNull() ?: 0.0
                "max" -> a.maxOrNull() ?: 0.0
                "sum" -> a.sum()
                "avg", "mean" -> if (a.isEmpty()) 0.0 else a.average()
                "sign" -> sign(one())
                "trunc", "int" -> one().toLong().toDouble()
                "rand", "random" -> when (a.size) {
                    0 -> Math.random()
                    1 -> Math.random() * a[0]
                    else -> a[0] + Math.random() * (a[1] - a[0])
                }
                "randint" -> {
                    val lo = a.getOrElse(0) { 0.0 }
                    val hi = a.getOrElse(1) { 100.0 }
                    (lo + Math.random() * (hi - lo + 1)).toLong().coerceAtMost(hi.toLong()).toDouble()
                }
                "if" -> if (a[0] != 0.0) a[1] else a.getOrElse(2) { 0.0 }
                else -> throw FlowError("Unknown function: " + name)
            }
        }
    }
}
