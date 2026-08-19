package com.rishi.cascade.actions

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.rishi.cascade.engine.FileRef
import com.rishi.cascade.engine.FlowError
import com.rishi.cascade.engine.V
import com.rishi.cascade.model.ActionDef
import com.rishi.cascade.model.Cat
import com.rishi.cascade.model.ParamSpec
import com.rishi.cascade.model.ParamType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object FileActions {

    /** Files with no leading slash live here: /sdcard/Android/data/<pkg>/files/Cascade */
    fun baseDir(c: Context): File =
        File(c.getExternalFilesDir(null) ?: c.filesDir, "Cascade").apply { mkdirs() }

    fun resolve(c: Context, path: String): File {
        val p = path.trim()
        if (p.isEmpty()) throw FlowError("No file path given")
        return if (p.startsWith("/")) File(p) else File(baseDir(c), p)
    }

    /** MediaStore write, so Downloads works without any storage permission on Android 10+. */
    fun saveToDownloads(c: Context, name: String, bytes: ByteArray, mime: String): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = c.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw FlowError("Could not create the file in Downloads")
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return "Downloads/" + name
        }
        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dir.mkdirs()
        val f = File(dir, name)
        f.writeBytes(bytes)
        return f.absolutePath
    }

    fun all(): List<Action> = listOf(

        act(ActionDef(
            "file.read", "Read File", Cat.FILES, "Description",
            summary = "Read %path%",
            params = listOf(
                ParamSpec("path", "Path", ParamType.FILEPATH, "", hint = "notes.txt, or an absolute /sdcard/... path"),
                ParamSpec("mode", "Read as", ParamType.CHOICE, "Text", listOf("Text", "Lines", "Size in bytes"))
            ),
            output = "The file contents",
            keywords = "read file open text load contents"
        )) { env ->
            withContext(Dispatchers.IO) {
                val f = resolve(env.app, env.str("path"))
                if (!f.exists()) throw FlowError("No such file: " + f.absolutePath)
                when (env.choice("mode", "Text")) {
                    "Lines" -> f.readLines()
                    "Size in bytes" -> f.length().toDouble()
                    else -> f.readText()
                }
            }
        },

        act(ActionDef(
            "file.write", "Write File", Cat.FILES, "Save",
            summary = "Write to %path%",
            params = listOf(
                ParamSpec("path", "Path", ParamType.FILEPATH, "", hint = "notes.txt"),
                ParamSpec("content", "Content", ParamType.MULTILINE, "{{last}}"),
                ParamSpec("mode", "If it exists", ParamType.CHOICE, "Overwrite",
                    listOf("Overwrite", "Append", "Append a line", "Fail")),
                ParamSpec("where", "Location", ParamType.CHOICE, "App storage", listOf("App storage", "Downloads"))
            ),
            output = "The file path",
            description = "Saves text to a file. Downloads uses MediaStore, so no storage permission is needed.",
            keywords = "write file save text append create export"
        )) { env ->
            withContext(Dispatchers.IO) {
                val content = V.text(env.value("content"))
                if (env.choice("where", "App storage") == "Downloads") {
                    val name = env.str("path").ifBlank { "cascade.txt" }.substringAfterLast('/')
                    FileRef(saveToDownloads(env.app, name, content.toByteArray(), "text/plain"), "text/plain")
                } else {
                    val f = resolve(env.app, env.str("path"))
                    f.parentFile?.mkdirs()
                    when (env.choice("mode", "Overwrite")) {
                        "Append" -> f.appendText(content)
                        "Append a line" -> f.appendText(content + "\n")
                        "Fail" -> {
                            if (f.exists()) throw FlowError("File already exists: " + f.name)
                            f.writeText(content)
                        }
                        else -> f.writeText(content)
                    }
                    FileRef(f.absolutePath, "text/plain")
                }
            }
        },

        act(ActionDef(
            "file.list", "List Folder", Cat.FILES, "FolderOpen",
            summary = "List %path%",
            params = listOf(
                ParamSpec("path", "Folder", ParamType.FILEPATH, "", hint = "blank means Cascade's own folder"),
                ParamSpec("mode", "Return", ParamType.CHOICE, "Names", listOf("Names", "Full paths", "Details")),
                ParamSpec("filter", "Only names containing", ParamType.TEXT, "")
            ),
            output = "A list of files",
            keywords = "list folder directory files browse ls"
        )) { env ->
            withContext(Dispatchers.IO) {
                val dir = if (env.str("path").isBlank()) baseDir(env.app) else resolve(env.app, env.str("path"))
                if (!dir.isDirectory) throw FlowError("Not a folder: " + dir.absolutePath)
                val f = env.str("filter").lowercase()
                (dir.listFiles() ?: emptyArray())
                    .filter { f.isEmpty() || it.name.lowercase().contains(f) }
                    .sortedBy { it.name.lowercase() }
                    .map {
                        when (env.choice("mode", "Names")) {
                            "Full paths" -> it.absolutePath
                            "Details" -> it.name + " (" + (if (it.isDirectory) "folder" else it.length().toString() + " bytes") + ")"
                            else -> it.name
                        }
                    }
            }
        },

        act(ActionDef(
            "file.manage", "Manage File", Cat.FILES, "DriveFileMove",
            summary = "%op% %path%",
            params = listOf(
                ParamSpec("op", "Operation", ParamType.CHOICE, "Delete",
                    listOf("Delete", "Copy", "Move", "Create folder", "Does it exist")),
                ParamSpec("path", "Path", ParamType.FILEPATH, ""),
                ParamSpec("target", "Destination", ParamType.FILEPATH, "", showIf = "op" to listOf("Copy", "Move"))
            ),
            output = "Whether it worked",
            keywords = "delete copy move rename folder mkdir file exists"
        )) { env ->
            withContext(Dispatchers.IO) {
                val f = resolve(env.app, env.str("path"))
                when (env.choice("op", "Delete")) {
                    "Copy" -> {
                        val t = resolve(env.app, env.str("target"))
                        t.parentFile?.mkdirs()
                        f.copyTo(t, overwrite = true)
                        t.absolutePath
                    }
                    "Move" -> {
                        val t = resolve(env.app, env.str("target"))
                        t.parentFile?.mkdirs()
                        f.copyTo(t, overwrite = true)
                        f.delete()
                        t.absolutePath
                    }
                    "Create folder" -> { f.mkdirs(); f.absolutePath }
                    "Does it exist" -> f.exists()
                    else -> f.deleteRecursively()
                }
            }
        },

        act(ActionDef(
            "file.log", "Append to Log File", Cat.FILES, "PostAdd",
            summary = "Log to %path%",
            params = listOf(
                ParamSpec("path", "File", ParamType.FILEPATH, "cascade-log.txt"),
                ParamSpec("text", "Line", ParamType.TEXT, "{{last}}"),
                ParamSpec("timestamp", "Add a timestamp", ParamType.BOOL, "true")
            ),
            output = "The file path",
            description = "The quickest way to record what a flow did over time.",
            keywords = "log append history record track csv"
        )) { env ->
            withContext(Dispatchers.IO) {
                val f = resolve(env.app, env.str("path", "cascade-log.txt"))
                f.parentFile?.mkdirs()
                val stamp = if (env.bool("timestamp", true))
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                        .format(java.util.Date()) + "\t" else ""
                f.appendText(stamp + V.text(env.value("text")) + "\n")
                FileRef(f.absolutePath, "text/plain")
            }
        }
    )
}
