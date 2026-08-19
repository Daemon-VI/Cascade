package com.rishi.cascade.actions

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.SmsManager
import com.rishi.cascade.engine.FlowError
import com.rishi.cascade.model.ActionDef
import com.rishi.cascade.model.Cat
import com.rishi.cascade.model.ParamSpec
import com.rishi.cascade.model.ParamType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object CommsActions {

    private fun has(c: android.content.Context, perm: String) =
        c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    fun all(): List<Action> = listOf(

        act(ActionDef(
            "comms.sms", "Send Message", Cat.COMMS, "Sms",
            summary = "Text %number%",
            params = listOf(
                ParamSpec("number", "Number", ParamType.TEXT, ""),
                ParamSpec("message", "Message", ParamType.MULTILINE, "{{last}}"),
                ParamSpec("mode", "Send", ParamType.CHOICE, "Open the messaging app",
                    listOf("Open the messaging app", "Send silently"))
            ),
            output = null,
            permissions = listOf("android.permission.SEND_SMS"),
            description = "Silent sending needs the SMS permission; otherwise the message is prefilled in your SMS app.",
            keywords = "sms text message send number"
        )) { env ->
            val number = env.str("number").trim()
            val msg = env.str("message")
            if (number.isEmpty()) throw FlowError("No number given")
            if (env.choice("mode", "Open the messaging app") == "Send silently") {
                if (!has(env.app, Manifest.permission.SEND_SMS))
                    throw FlowError("Sending silently needs the SMS permission. Grant it in Settings inside the app.")
                val sm = env.app.getSystemService(SmsManager::class.java)
                val parts = sm.divideMessage(msg)
                if (parts.size > 1) sm.sendMultipartTextMessage(number, null, parts, null, null)
                else sm.sendTextMessage(number, null, msg, null, null)
            } else {
                env.app.startActivity(
                    Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + number))
                        .putExtra("sms_body", msg)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            null
        },

        act(ActionDef(
            "comms.whatsapp", "WhatsApp Message", Cat.COMMS, "Chat",
            summary = "WhatsApp %number%",
            params = listOf(
                ParamSpec("number", "Number with country code", ParamType.TEXT, "", hint = "e.g. 919876543210"),
                ParamSpec("message", "Message", ParamType.MULTILINE, "{{last}}")
            ),
            output = null,
            description = "Opens WhatsApp with the message already typed.",
            keywords = "whatsapp message chat send wa"
        )) { env ->
            val n = env.str("number").filter { it.isDigit() }
            val text = Uri.encode(env.str("message"))
            val url = if (n.isEmpty()) "https://wa.me/?text=" + text else "https://wa.me/" + n + "?text=" + text
            env.app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            null
        },

        act(ActionDef(
            "comms.call", "Call Number", Cat.COMMS, "Call",
            summary = "Call %number%",
            params = listOf(
                ParamSpec("number", "Number", ParamType.TEXT, ""),
                ParamSpec("mode", "Action", ParamType.CHOICE, "Open the dialler",
                    listOf("Open the dialler", "Call straight away"))
            ),
            output = null,
            permissions = listOf("android.permission.CALL_PHONE"),
            keywords = "call phone dial number ring"
        )) { env ->
            val n = env.str("number").trim()
            if (n.isEmpty()) throw FlowError("No number given")
            val direct = env.choice("mode", "Open the dialler") == "Call straight away"
            if (direct && !has(env.app, Manifest.permission.CALL_PHONE))
                throw FlowError("Calling directly needs the Phone permission. Grant it in Settings inside the app.")
            val action = if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL
            env.app.startActivity(Intent(action, Uri.parse("tel:" + n)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            null
        },

        act(ActionDef(
            "comms.email", "Send Email", Cat.COMMS, "Email",
            summary = "Email %to%",
            params = listOf(
                ParamSpec("to", "To", ParamType.TEXT, ""),
                ParamSpec("subject", "Subject", ParamType.TEXT, ""),
                ParamSpec("body", "Body", ParamType.MULTILINE, "{{last}}")
            ),
            output = null,
            description = "Opens your mail app with everything filled in.",
            keywords = "email mail send gmail compose"
        )) { env ->
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + env.str("to").trim()))
                .putExtra(Intent.EXTRA_SUBJECT, env.str("subject"))
                .putExtra(Intent.EXTRA_TEXT, env.str("body"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            env.app.startActivity(i)
            null
        },

        act(ActionDef(
            "comms.contact", "Find Contact", Cat.COMMS, "Contacts",
            summary = "Find %name%",
            params = listOf(
                ParamSpec("name", "Name contains", ParamType.TEXT, "{{last}}"),
                ParamSpec("field", "Get", ParamType.CHOICE, "Phone number",
                    listOf("Phone number", "Name", "All matches", "Everything"))
            ),
            output = "Contact details",
            permissions = listOf("android.permission.READ_CONTACTS"),
            keywords = "contact phone number lookup address book find"
        )) { env ->
            withContext(Dispatchers.IO) {
                if (!has(env.app, Manifest.permission.READ_CONTACTS))
                    throw FlowError("This needs the Contacts permission. Grant it in Settings inside the app.")
                val name = env.str("name").trim()
                val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(name))
                val cur = env.app.contentResolver.query(
                    uri,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    ), null, null, null
                ) ?: throw FlowError("Could not read contacts")
                val results = mutableListOf<Pair<String, String>>()
                cur.use { c ->
                    while (c.moveToNext()) results.add(c.getString(0) to c.getString(1))
                }
                if (results.isEmpty()) throw FlowError("No contact matching \"" + name + "\"")
                when (env.choice("field", "Phone number")) {
                    "Name" -> results.first().first
                    "All matches" -> results.map { it.first + ": " + it.second }
                    "Everything" -> JSONObject().put("name", results.first().first)
                        .put("number", results.first().second).put("matches", results.size)
                    else -> results.first().second
                }
            }
        }
    )
}
