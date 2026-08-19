package com.rishi.cascade.actions

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.rishi.cascade.MainActivity
import com.rishi.cascade.engine.FlowStopped
import com.rishi.cascade.engine.V
import com.rishi.cascade.model.ActionDef
import com.rishi.cascade.model.Cat
import com.rishi.cascade.model.ParamSpec
import com.rishi.cascade.model.ParamType

object UiActions {

    const val CHANNEL_ID = "cascade_flow"

    fun ensureChannel(app: Context) {
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Flow notifications", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    fun all(): List<Action> = listOf(

        act(ActionDef(
            "ui.ask", "Ask for Input", Cat.UI, "QuestionAnswer",
            summary = "Ask \"%prompt%\"",
            params = listOf(
                ParamSpec("prompt", "Question", ParamType.TEXT, "What is it?"),
                ParamSpec("default", "Default answer", ParamType.TEXT, ""),
                ParamSpec("kind", "Input type", ParamType.CHOICE, "Text", listOf("Text", "Number", "Long text"))
            ),
            output = "What the user typed",
            description = "Pauses the flow and asks you to type something.",
            keywords = "input prompt question dialog"
        )) { env ->
            val kind = env.choice("kind", "Text")
            env.io.ask(env.str("prompt", "Input"), env.str("default"), kind == "Long text", kind == "Number")
                ?: throw FlowStopped(true, "Input cancelled")
        },

        act(ActionDef(
            "ui.menu", "Choose from Menu", Cat.UI, "Menu",
            summary = "Choose from %options%",
            params = listOf(
                ParamSpec("prompt", "Title", ParamType.TEXT, "Pick one"),
                ParamSpec("options", "Options (one per line)", ParamType.MULTILINE, "First\nSecond")
            ),
            output = "The chosen option (index in {{chosenIndex}})",
            description = "Shows a menu and continues with whatever you pick.",
            keywords = "menu choose select list pick option"
        )) { env ->
            val opts = env.str("options").lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (opts.isEmpty()) throw com.rishi.cascade.engine.FlowError("No options to choose from")
            val i = env.io.choose(env.str("prompt", "Choose"), opts) ?: throw FlowStopped(true, "Menu cancelled")
            env.ctx.set("chosenIndex", (i + 1).toDouble())
            opts[i]
        },

        act(ActionDef(
            "ui.alert", "Show Alert", Cat.UI, "Announcement",
            summary = "Alert \"%message%\"",
            params = listOf(
                ParamSpec("title", "Title", ParamType.TEXT, "Cascade"),
                ParamSpec("message", "Message", ParamType.MULTILINE, ""),
                ParamSpec("cancel", "Allow cancel (stops the flow)", ParamType.BOOL, "false")
            ),
            output = null,
            description = "Shows a dialog and waits for you to tap OK.",
            keywords = "alert dialog popup confirm"
        )) { env ->
            val ok = env.io.alert(env.str("title", "Cascade"), env.str("message"), env.bool("cancel", false))
            if (!ok) throw FlowStopped(true, "Cancelled at alert")
            null
        },

        act(ActionDef(
            "ui.result", "Show Result", Cat.UI, "Visibility",
            summary = "Show %value%",
            params = listOf(
                ParamSpec("title", "Title", ParamType.TEXT, "Result"),
                ParamSpec("value", "Value", ParamType.MULTILINE, "{{last}}")
            ),
            output = "The same value, passed through",
            description = "Displays a value without changing it.",
            keywords = "show display output preview quicklook"
        )) { env ->
            val v = env.value("value")
            env.io.showResult(env.str("title", "Result"), v)
            v
        },

        act(ActionDef(
            "ui.toast", "Show Toast", Cat.UI, "Chat",
            summary = "Toast %text%",
            params = listOf(
                ParamSpec("text", "Text", ParamType.TEXT, "{{last}}"),
                ParamSpec("long", "Long duration", ParamType.BOOL, "false")
            ),
            output = null,
            description = "Flashes a short message over whatever is on screen.",
            keywords = "toast message flash popup"
        )) { env ->
            val t = env.str("text")
            val dur = if (env.bool("long", false)) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            Handler(Looper.getMainLooper()).post { Toast.makeText(env.app, t, dur).show() }
            null
        },

        act(ActionDef(
            "ui.notify", "Post Notification", Cat.UI, "NotificationsActive",
            summary = "Notify \"%title%\"",
            params = listOf(
                ParamSpec("title", "Title", ParamType.TEXT, "Cascade"),
                ParamSpec("text", "Message", ParamType.MULTILINE, "{{last}}"),
                ParamSpec("tag", "Notification id", ParamType.TEXT, "", hint = "same id replaces the old one"),
                ParamSpec("ongoing", "Ongoing (cannot swipe away)", ParamType.BOOL, "false")
            ),
            output = null,
            permissions = listOf("android.permission.POST_NOTIFICATIONS"),
            description = "Posts a real Android notification.",
            keywords = "notification notify alert status bar"
        )) { env ->
            ensureChannel(env.app)
            val nm = env.app.getSystemService(NotificationManager::class.java)
            val pi = PendingIntent.getActivity(
                env.app, 0, Intent(env.app, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val b = Notification.Builder(env.app, CHANNEL_ID)
                .setContentTitle(env.str("title", "Cascade"))
                .setContentText(env.str("text"))
                .setStyle(Notification.BigTextStyle().bigText(env.str("text")))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi)
                .setAutoCancel(!env.bool("ongoing", false))
                .setOngoing(env.bool("ongoing", false))
            val id = env.str("tag").ifBlank { System.currentTimeMillis().toString() }
            nm?.notify(id, id.hashCode(), b.build())
            null
        },

        act(ActionDef(
            "ui.cancelnotify", "Clear Notifications", Cat.UI, "NotificationsOff",
            params = listOf(ParamSpec("tag", "Notification id", ParamType.TEXT, "", hint = "blank clears all of Cascade's")),
            output = null,
            description = "Removes notifications this app posted.",
            keywords = "clear dismiss notification"
        )) { env ->
            val nm = env.app.getSystemService(NotificationManager::class.java)
            val tag = env.str("tag")
            if (tag.isBlank()) nm?.cancelAll() else nm?.cancel(tag, tag.hashCode())
            null
        },

        act(ActionDef(
            "ui.log", "Add to Log", Cat.UI, "Article",
            summary = "Log %text%",
            params = listOf(ParamSpec("text", "Text", ParamType.TEXT, "{{last}}")),
            output = null,
            description = "Writes a line into this run's log, useful while debugging a flow.",
            keywords = "log debug print trace"
        )) { env ->
            env.log("Log", V.text(env.value("text")), 1)
            null
        }
    )
}
