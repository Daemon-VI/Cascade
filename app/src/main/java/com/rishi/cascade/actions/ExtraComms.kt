package com.rishi.cascade.actions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import com.rishi.cascade.engine.FlowError
import com.rishi.cascade.model.ActionDef
import com.rishi.cascade.model.Cat
import com.rishi.cascade.model.ParamSpec
import com.rishi.cascade.model.ParamType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ExtraComms {

    private fun has(c: Context, perm: String) =
        c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    fun all(): List<Action> = listOf(

        act(ActionDef(
            "comms.sms.read", "Read Messages", Cat.COMMS, "Sms",
            summary = "Read %mode%",
            params = listOf(
                ParamSpec("mode", "Get", ParamType.CHOICE, "Latest message",
                    listOf("Latest message", "Latest code", "Recent messages", "From sender")),
                ParamSpec("sender", "Sender contains", ParamType.TEXT, "",
                    showIf = "mode" to listOf("From sender")),
                ParamSpec("count", "How many", ParamType.NUMBER, "5",
                    showIf = "mode" to listOf("Recent messages", "From sender")),
                ParamSpec("field", "Return", ParamType.CHOICE, "Body",
                    listOf("Body", "Sender", "Everything"))
            ),
            output = "The message",
            permissions = listOf("android.permission.READ_SMS"),
            description = "Reads your SMS inbox. \"Latest code\" pulls the one-time code out of the " +
                    "newest message, which is the usual reason to want this.",
            keywords = "sms read inbox message otp code verification text latest"
        )) { env ->
            withContext(Dispatchers.IO) {
                if (!has(env.app, Manifest.permission.READ_SMS))
                    throw FlowError("This needs the SMS permission. Grant it in Settings inside the app.")
                val limit = env.int("count", 5).coerceIn(1, 100)
                val cursor = env.app.contentResolver.query(
                    Uri.parse("content://sms/inbox"),
                    arrayOf("address", "body", "date"), null, null, "date DESC"
                ) ?: throw FlowError("Could not read messages")

                data class Msg(val from: String, val body: String, val at: Long)
                val messages = mutableListOf<Msg>()
                cursor.use { c ->
                    val senderFilter = env.str("sender").lowercase()
                    while (c.moveToNext() && messages.size < limit * 4) {
                        val from = c.getString(0) ?: ""
                        if (senderFilter.isNotEmpty() && !from.lowercase().contains(senderFilter)) continue
                        messages.add(Msg(from, c.getString(1) ?: "", c.getLong(2)))
                        if (messages.size >= limit && env.choice("mode", "Latest message") != "Latest message") break
                        if (env.choice("mode", "Latest message") == "Latest message") break
                    }
                }
                if (messages.isEmpty()) throw FlowError("No matching messages")

                fun render(m: Msg): Any = when (env.choice("field", "Body")) {
                    "Sender" -> m.from
                    "Everything" -> JSONObject().put("sender", m.from).put("body", m.body)
                        .put("time", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(m.at)))
                    else -> m.body
                }

                when (env.choice("mode", "Latest message")) {
                    "Latest code" -> Regex("\\b\\d{4,8}\\b").find(messages.first().body)?.value
                        ?: throw FlowError("No code in the latest message")
                    "Latest message" -> render(messages.first())
                    else -> messages.take(limit).map { render(it) }
                }
            }
        },

        act(ActionDef(
            "comms.calls", "Recent Calls", Cat.COMMS, "Call",
            params = listOf(
                ParamSpec("kind", "Only", ParamType.CHOICE, "Any",
                    listOf("Any", "Missed", "Incoming", "Outgoing")),
                ParamSpec("count", "How many", ParamType.NUMBER, "5"),
                ParamSpec("field", "Return", ParamType.CHOICE, "Summary",
                    listOf("Summary", "Numbers", "Everything"))
            ),
            output = "Recent calls",
            permissions = listOf("android.permission.READ_CALL_LOG"),
            keywords = "calls log missed incoming outgoing recent history phone"
        )) { env ->
            withContext(Dispatchers.IO) {
                if (!has(env.app, Manifest.permission.READ_CALL_LOG))
                    throw FlowError("This needs the Call log permission. Grant it in Settings inside the app.")
                val type = when (env.choice("kind", "Any")) {
                    "Missed" -> CallLog.Calls.MISSED_TYPE
                    "Incoming" -> CallLog.Calls.INCOMING_TYPE
                    "Outgoing" -> CallLog.Calls.OUTGOING_TYPE
                    else -> -1
                }
                val where = if (type >= 0) CallLog.Calls.TYPE + "=" + type else null
                val cursor = env.app.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
                        CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE),
                    where, null, CallLog.Calls.DATE + " DESC"
                ) ?: throw FlowError("Could not read the call log")

                val limit = env.int("count", 5).coerceIn(1, 100)
                val out = mutableListOf<Any>()
                cursor.use { c ->
                    while (c.moveToNext() && out.size < limit) {
                        val number = c.getString(0) ?: ""
                        val name = c.getString(1) ?: ""
                        val at = c.getLong(2)
                        val seconds = c.getLong(3)
                        val kind = when (c.getInt(4)) {
                            CallLog.Calls.MISSED_TYPE -> "Missed"
                            CallLog.Calls.INCOMING_TYPE -> "Incoming"
                            CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                            else -> "Other"
                        }
                        val stamp = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(at))
                        out.add(
                            when (env.choice("field", "Summary")) {
                                "Numbers" -> number
                                "Everything" -> JSONObject().put("number", number).put("name", name)
                                    .put("type", kind).put("seconds", seconds).put("time", stamp)
                                else -> kind + " " + (name.ifBlank { number }) + " at " + stamp
                            }
                        )
                    }
                }
                if (out.isEmpty()) throw FlowError("No calls matched")
                out
            }
        }
    )
}
