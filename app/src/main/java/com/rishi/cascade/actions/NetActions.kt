package com.rishi.cascade.actions

import com.rishi.cascade.engine.FileRef
import com.rishi.cascade.engine.FlowError
import com.rishi.cascade.engine.V
import com.rishi.cascade.model.ActionDef
import com.rishi.cascade.model.Cat
import com.rishi.cascade.model.ParamSpec
import com.rishi.cascade.model.ParamType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder

object NetActions {

    private fun openConn(urlText: String, timeoutSec: Int): HttpURLConnection {
        val url = try { URL(urlText) } catch (e: Exception) { throw FlowError("Bad URL: " + urlText) }
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = timeoutSec * 1000
        c.readTimeout = timeoutSec * 1000
        c.instanceFollowRedirects = true
        return c
    }

    fun all(): List<Action> = listOf(

        act(ActionDef(
            "net.http", "HTTP Request", Cat.NET, "Http",
            summary = "%method% %url%",
            params = listOf(
                ParamSpec("method", "Method", ParamType.CHOICE, "GET",
                    listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")),
                ParamSpec("url", "URL", ParamType.TEXT, "https://"),
                ParamSpec("bodyKind", "Body", ParamType.CHOICE, "None",
                    listOf("None", "JSON", "Form", "Raw text"), showIf = "method" to listOf("POST", "PUT", "PATCH", "DELETE")),
                ParamSpec("body", "Body content", ParamType.MULTILINE, "",
                    showIf = "bodyKind" to listOf("JSON", "Raw text"), hint = "JSON text, or {{a dictionary}}"),
                ParamSpec("form", "Form fields", ParamType.KEYVALUE, "",
                    showIf = "bodyKind" to listOf("Form"), hint = "one \"key: value\" per line"),
                ParamSpec("headers", "Headers", ParamType.KEYVALUE, "", hint = "one \"Header: value\" per line"),
                ParamSpec("query", "Query parameters", ParamType.KEYVALUE, ""),
                ParamSpec("returns", "Return", ParamType.CHOICE, "Body (parsed if JSON)",
                    listOf("Body (parsed if JSON)", "Body as text", "Status code", "Headers", "Everything")),
                ParamSpec("timeout", "Timeout (seconds)", ParamType.NUMBER, "30")
            ),
            output = "The response",
            description = "Calls any web API. JSON responses are parsed so you can use {{result.field}} straight away.",
            keywords = "http request api rest get post json web url webhook curl"
        )) { env ->
            withContext(Dispatchers.IO) {
                var urlText = env.str("url").trim()
                val query = env.keyValues("query")
                if (query.isNotEmpty()) {
                    val qs = query.entries.joinToString("&") {
                        URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
                    }
                    urlText += if (urlText.contains("?")) "&" + qs else "?" + qs
                }
                val method = env.choice("method", "GET")
                val c = openConn(urlText, env.int("timeout", 30))
                c.requestMethod = method
                env.keyValues("headers").forEach { (k, v) -> c.setRequestProperty(k, v) }

                val bodyKind = env.choice("bodyKind", "None")
                if (method != "GET" && method != "HEAD" && bodyKind != "None") {
                    val payload = when (bodyKind) {
                        "JSON" -> {
                            if (c.getRequestProperty("Content-Type") == null)
                                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                            val v = env.value("body")
                            when (v) {
                                is JSONObject, is JSONArray -> v.toString()
                                else -> V.text(v)
                            }
                        }
                        "Form" -> {
                            if (c.getRequestProperty("Content-Type") == null)
                                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                            env.keyValues("form").entries.joinToString("&") {
                                URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
                            }
                        }
                        else -> env.str("body")
                    }
                    c.doOutput = true
                    c.outputStream.use { it.write(payload.toByteArray()) }
                }

                val status = try { c.responseCode } catch (e: Exception) {
                    throw FlowError("Request failed: " + (e.message ?: "no response"))
                }
                val text = try {
                    (if (status in 200..399) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
                } catch (e: Exception) { "" }
                val headers = JSONObject().also { h ->
                    c.headerFields.forEach { (k, v) -> if (k != null) h.put(k, v.joinToString(", ")) }
                }
                c.disconnect()

                val parsed: Any = try {
                    val t = text.trim()
                    when {
                        t.startsWith("{") -> JSONObject(t)
                        t.startsWith("[") -> JSONArray(t)
                        else -> text
                    }
                } catch (e: Exception) { text }

                when (env.choice("returns", "Body (parsed if JSON)")) {
                    "Body as text" -> text
                    "Status code" -> status.toDouble()
                    "Headers" -> headers
                    "Everything" -> JSONObject().put("status", status).put("body", parsed).put("headers", headers)
                    else -> {
                        if (status >= 400) throw FlowError("HTTP " + status + ": " + text.take(200))
                        parsed
                    }
                }
            }
        },

        act(ActionDef(
            "net.download", "Download File", Cat.NET, "Download",
            summary = "Download %url%",
            params = listOf(
                ParamSpec("url", "URL", ParamType.TEXT, "https://"),
                ParamSpec("name", "Save as", ParamType.TEXT, "", hint = "blank uses the name from the URL"),
                ParamSpec("where", "Location", ParamType.CHOICE, "App storage", listOf("App storage", "Downloads")),
                ParamSpec("timeout", "Timeout (seconds)", ParamType.NUMBER, "60")
            ),
            output = "The saved file path",
            keywords = "download file save fetch url get"
        )) { env ->
            withContext(Dispatchers.IO) {
                val urlText = env.str("url").trim()
                val c = openConn(urlText, env.int("timeout", 60))
                val name = env.str("name").ifBlank {
                    urlText.substringAfterLast('/').substringBefore('?').ifBlank { "download.bin" }
                }
                val bytes = c.inputStream.use { it.readBytes() }
                val mime = c.contentType ?: "application/octet-stream"
                c.disconnect()
                val path = if (env.choice("where", "App storage") == "Downloads")
                    FileActions.saveToDownloads(env.app, name, bytes, mime)
                else {
                    val f = File(FileActions.baseDir(env.app), name)
                    f.parentFile?.mkdirs()
                    f.writeBytes(bytes)
                    f.absolutePath
                }
                FileRef(path, mime)
            }
        },

        act(ActionDef(
            "net.ping", "Ping Host", Cat.NET, "NetworkPing",
            summary = "Ping %host%",
            params = listOf(
                ParamSpec("host", "Host", ParamType.TEXT, "8.8.8.8"),
                ParamSpec("timeout", "Timeout (seconds)", ParamType.NUMBER, "5"),
                ParamSpec("returns", "Return", ParamType.CHOICE, "Reachable", listOf("Reachable", "Round trip (ms)", "IP address"))
            ),
            output = "Ping result",
            keywords = "ping reachable host network latency dns"
        )) { env ->
            withContext(Dispatchers.IO) {
                val host = env.str("host", "8.8.8.8").trim()
                val t0 = System.currentTimeMillis()
                val addr = try { InetAddress.getByName(host) } catch (e: Exception) { throw FlowError("Cannot resolve " + host) }
                val ok = try { addr.isReachable(env.int("timeout", 5) * 1000) } catch (e: Exception) { false }
                when (env.choice("returns", "Reachable")) {
                    "Round trip (ms)" -> (System.currentTimeMillis() - t0).toDouble()
                    "IP address" -> addr.hostAddress ?: ""
                    else -> ok
                }
            }
        },

        act(ActionDef(
            "net.publicip", "Get Public IP", Cat.NET, "Public",
            params = listOf(ParamSpec("detail", "Return", ParamType.CHOICE, "IP address", listOf("IP address", "Location details"))),
            output = "Your public IP",
            keywords = "ip public external address location isp"
        )) { env ->
            withContext(Dispatchers.IO) {
                if (env.choice("detail", "IP address") == "Location details") {
                    val t = URL("https://ipapi.co/json/").readText()
                    JSONObject(t)
                } else URL("https://api.ipify.org").readText().trim()
            }
        }
    )
}
