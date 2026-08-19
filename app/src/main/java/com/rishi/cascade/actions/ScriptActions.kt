package com.rishi.cascade.actions

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import com.rishi.cascade.engine.FlowError
import com.rishi.cascade.engine.V
import com.rishi.cascade.model.ActionDef
import com.rishi.cascade.model.Cat
import com.rishi.cascade.model.ParamSpec
import com.rishi.cascade.model.ParamType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedReader
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

object ScriptActions {

    /** Runs JS in a headless WebView. Variables arrive as `vars`, the last result as `input`. */
    private suspend fun runJs(app: android.content.Context, code: String, varsJson: String, timeoutMs: Long): Any? {
        val wrapped = """
            (function() {
              var vars = $varsJson;
              var input = vars["last"];
              try {
                var __out = (function(){ $code })();
                return JSON.stringify({ ok: true, value: __out === undefined ? null : __out });
              } catch (e) {
                return JSON.stringify({ ok: false, error: String(e && e.message ? e.message : e) });
              }
            })();
        """.trimIndent()

        val raw = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<String?> { cont ->
                Handler(Looper.getMainLooper()).post {
                    try {
                        val wv = WebView(app)
                        wv.settings.javaScriptEnabled = true
                        wv.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                view?.evaluateJavascript(wrapped) { value ->
                                    if (cont.isActive) cont.resume(value)
                                    view.destroy()
                                }
                            }
                        }
                        wv.loadDataWithBaseURL(null, "<html><body></body></html>", "text/html", "utf-8", null)
                        cont.invokeOnCancellation { wv.destroy() }
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        } ?: throw FlowError("JavaScript timed out")

        // evaluateJavascript hands back a JSON-encoded string containing our JSON payload.
        val inner = try {
            val t = JSONTokener(raw).nextValue()
            if (t is String) t else raw
        } catch (e: Exception) { raw }

        val obj = try { JSONObject(inner) } catch (e: Exception) {
            throw FlowError("JavaScript returned something unreadable")
        }
        if (!obj.optBoolean("ok")) throw FlowError("JavaScript error: " + obj.optString("error"))
        val value = obj.opt("value")
        return when (value) {
            null, JSONObject.NULL -> null
            is Int -> value.toDouble()
            is Long -> value.toDouble()
            is JSONArray -> (0 until value.length()).map { value.opt(it) }
            else -> value
        }
    }

    fun all(): List<Action> = listOf(

        act(ActionDef(
            "script.js", "Run JavaScript", Cat.SCRIPT, "Javascript",
            summary = "Run script",
            params = listOf(
                ParamSpec("code", "Code", ParamType.MULTILINE,
                    "// `input` is the previous result, `vars` holds every variable\nreturn input;",
                    hint = "return a value to pass it on"),
                ParamSpec("timeout", "Timeout (seconds)", ParamType.NUMBER, "10")
            ),
            output = "Whatever the script returns",
            description = "Real JavaScript for the things no action covers. Every flow variable is available in `vars`.",
            keywords = "javascript js script code custom eval function programming"
        )) { env ->
            val vars = JSONObject()
            env.ctx.vars.forEach { (k, v) -> vars.put(k, V.json(v)) }
            vars.put("last", V.json(env.ctx.last))
            runJs(env.app, env.raw("code"), vars.toString(), (env.num("timeout", 10.0) * 1000).toLong())
        },

        act(ActionDef(
            "script.shell", "Run Shell Command", Cat.SCRIPT, "Terminal",
            summary = "$ %command%",
            params = listOf(
                ParamSpec("command", "Command", ParamType.MULTILINE, "getprop ro.product.model"),
                ParamSpec("returns", "Return", ParamType.CHOICE, "Output", listOf("Output", "Exit code", "Everything")),
                ParamSpec("timeout", "Timeout (seconds)", ParamType.NUMBER, "10")
            ),
            output = "Command output",
            description = "Runs a command as the app user (no root). Good for getprop, ls, ping, settings get and similar.",
            keywords = "shell command terminal exec sh run script bash"
        )) { env ->
            withContext(Dispatchers.IO) {
                val cmd = env.str("command")
                val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
                val finished = p.waitFor(env.num("timeout", 10.0).toLong().coerceAtLeast(1), TimeUnit.SECONDS)
                if (!finished) { p.destroy(); throw FlowError("Command timed out") }
                val out = p.inputStream.bufferedReader().use(BufferedReader::readText).trim()
                when (env.choice("returns", "Output")) {
                    "Exit code" -> p.exitValue().toDouble()
                    "Everything" -> JSONObject().put("output", out).put("exitCode", p.exitValue())
                    else -> out
                }
            }
        }
    )
}
