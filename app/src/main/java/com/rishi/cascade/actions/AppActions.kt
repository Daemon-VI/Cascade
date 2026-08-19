package com.rishi.cascade.actions

import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import com.rishi.cascade.engine.FlowError
import com.rishi.cascade.engine.V
import com.rishi.cascade.model.ActionDef
import com.rishi.cascade.model.Cat
import com.rishi.cascade.model.ParamSpec
import com.rishi.cascade.model.ParamType
import org.json.JSONObject

object AppActions {

    data class AppEntry(val label: String, val pkg: String)

    fun installedApps(c: Context, onlyLaunchable: Boolean = true): List<AppEntry> {
        val pm = c.packageManager
        return if (onlyLaunchable) {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
        } else {
            pm.getInstalledApplications(0)
                .map { AppEntry(pm.getApplicationLabel(it).toString(), it.packageName) }
                .sortedBy { it.label.lowercase() }
        }
    }

    private fun launch(c: Context, intent: Intent) {
        c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun all(): List<Action> = listOf(

        act(ActionDef(
            "app.open", "Open App", Cat.APPS, "Apps",
            summary = "Open %package%",
            params = listOf(ParamSpec("package", "App", ParamType.APP, "")),
            output = null,
            keywords = "open app launch start run"
        )) { env ->
            val pkg = env.str("package").trim()
            if (pkg.isEmpty()) throw FlowError("No app chosen")
            val i = env.app.packageManager.getLaunchIntentForPackage(pkg)
                ?: throw FlowError("Cannot open " + pkg)
            launch(env.app, i)
            null
        },

        act(ActionDef(
            "app.url", "Open URL", Cat.APPS, "OpenInBrowser",
            summary = "Open %url%",
            params = listOf(
                ParamSpec("url", "URL", ParamType.TEXT, "https://", hint = "https://, geo:, tel:, mailto:, any scheme"),
                ParamSpec("package", "Open with (optional)", ParamType.APP, "")
            ),
            output = null,
            description = "Opens a link, or any deep link such as spotify: or whatsapp://.",
            keywords = "url link browser open web deeplink scheme"
        )) { env ->
            val url = env.str("url").trim()
            if (url.isEmpty()) throw FlowError("No URL given")
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            env.str("package").trim().takeIf { it.isNotEmpty() }?.let { i.setPackage(it) }
            launch(env.app, i)
            null
        },

        act(ActionDef(
            "app.intent", "Send Intent", Cat.APPS, "Bolt",
            summary = "%action%",
            params = listOf(
                ParamSpec("kind", "Send as", ParamType.CHOICE, "Activity", listOf("Activity", "Broadcast", "Service")),
                ParamSpec("action", "Action", ParamType.TEXT, "android.intent.action.VIEW",
                    hint = "e.g. android.intent.action.SEND"),
                ParamSpec("package", "Package (optional)", ParamType.APP, ""),
                ParamSpec("class", "Class (optional)", ParamType.TEXT, "", hint = "fully qualified activity name"),
                ParamSpec("data", "Data URI (optional)", ParamType.TEXT, ""),
                ParamSpec("mime", "MIME type (optional)", ParamType.TEXT, "", hint = "e.g. text/plain"),
                ParamSpec("category", "Category (optional)", ParamType.TEXT, ""),
                ParamSpec("extras", "Extras", ParamType.KEYVALUE, "",
                    hint = "one \"key: value\" per line; prefix value with int: bool: long: float: to type it")
            ),
            output = null,
            description = "Fires any Android intent. This is the escape hatch that lets Cascade drive apps that have no dedicated action.",
            keywords = "intent broadcast activity service advanced custom deeplink tasker"
        )) { env ->
            val i = Intent()
            env.str("action").trim().takeIf { it.isNotEmpty() }?.let { i.action = it }
            val pkg = env.str("package").trim()
            val cls = env.str("class").trim()
            when {
                pkg.isNotEmpty() && cls.isNotEmpty() -> i.component = ComponentName(pkg, cls)
                pkg.isNotEmpty() -> i.setPackage(pkg)
            }
            val data = env.str("data").trim()
            val mime = env.str("mime").trim()
            when {
                data.isNotEmpty() && mime.isNotEmpty() -> i.setDataAndType(Uri.parse(data), mime)
                data.isNotEmpty() -> i.data = Uri.parse(data)
                mime.isNotEmpty() -> i.type = mime
            }
            env.str("category").trim().takeIf { it.isNotEmpty() }?.let { i.addCategory(it) }
            env.keyValues("extras").forEach { (k, raw) ->
                when {
                    raw.startsWith("int:") -> i.putExtra(k, raw.removePrefix("int:").trim().toIntOrNull() ?: 0)
                    raw.startsWith("long:") -> i.putExtra(k, raw.removePrefix("long:").trim().toLongOrNull() ?: 0L)
                    raw.startsWith("float:") -> i.putExtra(k, raw.removePrefix("float:").trim().toFloatOrNull() ?: 0f)
                    raw.startsWith("bool:") -> i.putExtra(k, raw.removePrefix("bool:").trim().toBoolean())
                    else -> i.putExtra(k, raw)
                }
            }
            when (env.choice("kind", "Activity")) {
                "Broadcast" -> env.app.sendBroadcast(i)
                "Service" -> env.app.startService(i)
                else -> launch(env.app, i)
            }
            null
        },

        act(ActionDef(
            "app.share", "Share", Cat.APPS, "Share",
            summary = "Share %text%",
            params = listOf(
                ParamSpec("text", "Text or file path", ParamType.MULTILINE, "{{last}}"),
                ParamSpec("subject", "Subject", ParamType.TEXT, ""),
                ParamSpec("package", "Share to (optional)", ParamType.APP, "")
            ),
            output = null,
            keywords = "share send to app sheet"
        )) { env ->
            val i = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, env.str("text"))
            env.str("subject").takeIf { it.isNotBlank() }?.let { i.putExtra(Intent.EXTRA_SUBJECT, it) }
            val pkg = env.str("package").trim()
            if (pkg.isNotEmpty()) { i.setPackage(pkg); launch(env.app, i) }
            else launch(env.app, Intent.createChooser(i, "Share"))
            null
        },

        act(ActionDef(
            "app.list", "Get Installed Apps", Cat.APPS, "GridView",
            params = listOf(
                ParamSpec("mode", "Return", ParamType.CHOICE, "Names", listOf("Names", "Package names", "Name and package")),
                ParamSpec("filter", "Only apps containing", ParamType.TEXT, ""),
                ParamSpec("system", "Include system apps", ParamType.BOOL, "false")
            ),
            output = "A list of apps",
            keywords = "apps installed list packages inventory"
        )) { env ->
            val f = env.str("filter").lowercase()
            installedApps(env.app, !env.bool("system", false))
                .filter { f.isEmpty() || it.label.lowercase().contains(f) || it.pkg.contains(f) }
                .map {
                    when (env.choice("mode", "Names")) {
                        "Package names" -> it.pkg
                        "Name and package" -> it.label + " (" + it.pkg + ")"
                        else -> it.label
                    }
                }
        },

        act(ActionDef(
            "app.info", "App Info", Cat.APPS, "Info",
            summary = "Info for %package%",
            params = listOf(
                ParamSpec("package", "App", ParamType.APP, ""),
                ParamSpec("field", "Get", ParamType.CHOICE, "Version",
                    listOf("Version", "Version code", "Name", "Is installed", "Install date", "Size (MB)", "Everything"))
            ),
            output = "App information",
            keywords = "app info version installed package size"
        )) { env ->
            val pm = env.app.packageManager
            val pkg = env.str("package").trim()
            val info = try { pm.getPackageInfo(pkg, 0) } catch (e: PackageManager.NameNotFoundException) { null }
            if (env.choice("field", "Version") == "Is installed") return@act info != null
            if (info == null) throw FlowError(pkg + " is not installed")
            val label = pm.getApplicationLabel(info.applicationInfo!!).toString()
            val sizeMb = try {
                java.io.File(info.applicationInfo!!.sourceDir).length() / 1048576.0
            } catch (e: Exception) { 0.0 }
            when (env.choice("field", "Version")) {
                "Version code" -> info.longVersionCode.toDouble()
                "Name" -> label
                "Install date" -> info.firstInstallTime.toDouble()
                "Size (MB)" -> Math.round(sizeMb * 10) / 10.0
                "Everything" -> JSONObject().put("name", label).put("package", pkg)
                    .put("version", info.versionName).put("versionCode", info.longVersionCode)
                    .put("installed", info.firstInstallTime).put("updated", info.lastUpdateTime)
                    .put("sizeMb", Math.round(sizeMb * 10) / 10.0)
                    .put("system", (info.applicationInfo!!.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
                else -> info.versionName ?: ""
            }
        },

        act(ActionDef(
            "app.current", "Current App", Cat.APPS, "CenterFocusStrong",
            params = listOf(ParamSpec("mode", "Return", ParamType.CHOICE, "Name", listOf("Name", "Package name"))),
            output = "The app in the foreground",
            specialAccess = "usage_stats",
            description = "Needs usage access. Note that while a flow is running from the Cascade window, Cascade itself is in front.",
            keywords = "foreground current app running usage"
        )) { env ->
            val usm = env.app.getSystemService(UsageStatsManager::class.java)
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 3_600_000, now)
            if (stats.isNullOrEmpty()) throw FlowError("Usage access is not granted. Turn it on in Settings inside the app.")
            val top = stats.maxByOrNull { it.lastTimeUsed } ?: throw FlowError("Could not read the foreground app")
            if (env.choice("mode", "Name") == "Package name") top.packageName
            else try {
                val pm = env.app.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(top.packageName, 0)).toString()
            } catch (e: Exception) { top.packageName }
        },

        act(ActionDef(
            "app.settings", "Open App Settings", Cat.APPS, "Tune",
            summary = "Settings for %package%",
            params = listOf(ParamSpec("package", "App", ParamType.APP, "")),
            output = null,
            keywords = "app settings info permissions manage"
        )) { env ->
            val pkg = env.str("package").trim().ifEmpty { env.app.packageName }
            launch(env.app, Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + pkg)))
            null
        },

        act(ActionDef(
            "app.uninstall", "Uninstall App", Cat.APPS, "Delete",
            summary = "Uninstall %package%",
            params = listOf(ParamSpec("package", "App", ParamType.APP, "")),
            output = null,
            description = "Opens the system uninstall prompt. You still confirm it by hand.",
            keywords = "uninstall remove delete app"
        )) { env ->
            val pkg = env.str("package").trim()
            if (pkg.isEmpty()) throw FlowError("No app chosen")
            launch(env.app, Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)))
            null
        }
    )
}
