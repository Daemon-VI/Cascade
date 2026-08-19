package com.rishi.cascade.actions

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.FileProvider
import com.rishi.cascade.engine.FileRef
import com.rishi.cascade.engine.FlowError
import com.rishi.cascade.engine.V
import com.rishi.cascade.model.ActionDef
import com.rishi.cascade.model.Cat
import com.rishi.cascade.model.ParamSpec
import com.rishi.cascade.model.ParamType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ExtraFilesNet {

    private fun walk(dir: File, out: MutableList<File>, depth: Int = 0) {
        if (depth > 8) return
        dir.listFiles()?.forEach {
            out.add(it)
            if (it.isDirectory) walk(it, out, depth + 1)
        }
    }

    fun all(): List<Action> = listOf(

        act(ActionDef(
            "file.zip", "Create Zip", Cat.FILES, "FolderZip",
            summary = "Zip %path%",
            params = listOf(
                ParamSpec("path", "File or folder", ParamType.FILEPATH, ""),
                ParamSpec("name", "Save archive as", ParamType.TEXT, "archive.zip")
            ),
            output = "The archive file",
            keywords = "zip archive compress backup folder"
        )) { env ->
            withContext(Dispatchers.IO) {
                val src = FileActions.resolve(env.app, env.str("path"))
                if (!src.exists()) throw FlowError("No such file or folder: " + src.absolutePath)
                val out = File(FileActions.baseDir(env.app), env.str("name", "archive.zip"))
                out.parentFile?.mkdirs()
                ZipOutputStream(out.outputStream().buffered()).use { zip ->
                    val files = mutableListOf<File>()
                    if (src.isDirectory) walk(src, files) else files.add(src)
                    val base = if (src.isDirectory) src else src.parentFile
                    files.filter { it.isFile }.forEach { f ->
                        val rel = base?.toURI()?.relativize(f.toURI())?.path ?: f.name
                        zip.putNextEntry(ZipEntry(rel))
                        f.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                FileRef(out.absolutePath, "application/zip")
            }
        },

        act(ActionDef(
            "file.unzip", "Extract Zip", Cat.FILES, "FolderOpen",
            summary = "Extract %path%",
            params = listOf(
                ParamSpec("path", "Archive", ParamType.FILEPATH, "{{last}}"),
                ParamSpec("target", "Into folder", ParamType.FILEPATH, "unzipped")
            ),
            output = "A list of extracted files",
            keywords = "unzip extract archive decompress"
        )) { env ->
            withContext(Dispatchers.IO) {
                val src = FileActions.resolve(env.app, env.str("path"))
                if (!src.exists()) throw FlowError("No such archive: " + src.absolutePath)
                val dir = FileActions.resolve(env.app, env.str("target", "unzipped"))
                dir.mkdirs()
                val written = mutableListOf<String>()
                ZipInputStream(src.inputStream().buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        // never let an archive write outside the target folder
                        val target = File(dir, entry.name).canonicalFile
                        if (!target.path.startsWith(dir.canonicalFile.path)) {
                            zip.closeEntry(); continue
                        }
                        if (entry.isDirectory) target.mkdirs()
                        else {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { zip.copyTo(it) }
                            written.add(target.absolutePath)
                        }
                        zip.closeEntry()
                    }
                }
                written
            }
        },

        act(ActionDef(
            "file.hash", "Hash a File", Cat.FILES, "Fingerprint",
            params = listOf(
                ParamSpec("path", "File", ParamType.FILEPATH, "{{last}}"),
                ParamSpec("algo", "Algorithm", ParamType.CHOICE, "SHA-256",
                    listOf("MD5", "SHA-1", "SHA-256", "SHA-512"))
            ),
            output = "A hex digest",
            description = "Checksum a download, or notice when a file changed.",
            keywords = "hash file checksum sha md5 verify integrity"
        )) { env ->
            withContext(Dispatchers.IO) {
                val f = FileActions.resolve(env.app, env.str("path"))
                if (!f.exists()) throw FlowError("No such file: " + f.absolutePath)
                val md = MessageDigest.getInstance(env.choice("algo", "SHA-256"))
                f.inputStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        md.update(buf, 0, n)
                    }
                }
                md.digest().joinToString("") { "%02x".format(it) }
            }
        },

        act(ActionDef(
            "file.search", "Find Files", Cat.FILES, "Search",
            summary = "Find %pattern%",
            params = listOf(
                ParamSpec("path", "Look in", ParamType.FILEPATH, "", hint = "blank means Cascade's folder"),
                ParamSpec("pattern", "Name contains", ParamType.TEXT, ""),
                ParamSpec("mode", "Return", ParamType.CHOICE, "Paths",
                    listOf("Paths", "Names", "Newest one", "Count"))
            ),
            output = "What was found",
            keywords = "find search files folder recursive newest"
        )) { env ->
            withContext(Dispatchers.IO) {
                val dir = if (env.str("path").isBlank()) FileActions.baseDir(env.app)
                else FileActions.resolve(env.app, env.str("path"))
                if (!dir.isDirectory) throw FlowError("Not a folder: " + dir.absolutePath)
                val pattern = env.str("pattern").lowercase()
                val found = mutableListOf<File>()
                walk(dir, found)
                val hits = found.filter { it.isFile && (pattern.isEmpty() || it.name.lowercase().contains(pattern)) }
                when (env.choice("mode", "Paths")) {
                    "Names" -> hits.map { it.name }
                    "Newest one" -> hits.maxByOrNull { it.lastModified() }?.absolutePath ?: ""
                    "Count" -> hits.size.toDouble()
                    else -> hits.map { it.absolutePath }
                }
            }
        },

        act(ActionDef(
            "file.share", "Share a File", Cat.FILES, "Share",
            summary = "Share %path%",
            params = listOf(
                ParamSpec("path", "File", ParamType.FILEPATH, "{{last}}"),
                ParamSpec("mime", "Type", ParamType.TEXT, "", hint = "blank guesses from the extension"),
                ParamSpec("package", "Share to (optional)", ParamType.APP, "")
            ),
            output = null,
            description = "Opens the share sheet with a real file attached, not just its path.",
            keywords = "share file send attachment sheet whatsapp"
        )) { env ->
            val f = FileActions.resolve(env.app, env.str("path"))
            if (!f.exists()) throw FlowError("No such file: " + f.absolutePath)
            val uri: Uri = try {
                FileProvider.getUriForFile(env.app, env.app.packageName + ".files", f)
            } catch (e: Exception) {
                throw FlowError("That file is outside the folders Cascade can share from")
            }
            val mime = env.str("mime").ifBlank {
                when (f.extension.lowercase()) {
                    "jpg", "jpeg" -> "image/jpeg"
                    "png" -> "image/png"
                    "txt", "log", "csv" -> "text/plain"
                    "json" -> "application/json"
                    "zip" -> "application/zip"
                    "m4a", "mp3" -> "audio/*"
                    "mp4" -> "video/mp4"
                    "pdf" -> "application/pdf"
                    else -> "*/*"
                }
            }
            val i = Intent(Intent.ACTION_SEND).setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val pkg = env.str("package").trim()
            if (pkg.isNotEmpty()) i.setPackage(pkg)
            env.app.startActivity(
                (if (pkg.isEmpty()) Intent.createChooser(i, "Share") else i)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            null
        },

        act(ActionDef(
            "media.gallery", "Save to Gallery", Cat.MEDIA, "PhotoLibrary",
            summary = "Save %path% to gallery",
            params = listOf(
                ParamSpec("path", "Image file", ParamType.FILEPATH, "{{last}}"),
                ParamSpec("album", "Album", ParamType.TEXT, "Cascade")
            ),
            output = "The gallery entry",
            description = "Puts an image into your photo gallery, where other apps can see it.",
            keywords = "gallery save photo image album pictures export"
        )) { env ->
            withContext(Dispatchers.IO) {
                val f = FileActions.resolve(env.app, env.str("path"))
                if (!f.exists()) throw FlowError("No such image: " + f.absolutePath)
                val album = env.str("album", "Cascade").ifBlank { "Cascade" }
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, f.name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= 29) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/" + album)
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                val resolver = env.app.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw FlowError("The gallery refused the image")
                resolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
                if (Build.VERSION.SDK_INT >= 29) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                uri.toString()
            }
        },

        act(ActionDef(
            "media.image", "Edit Image", Cat.MEDIA, "PhotoCamera",
            summary = "%mode% image",
            params = listOf(
                ParamSpec("path", "Image file", ParamType.FILEPATH, "{{last}}"),
                ParamSpec("mode", "Do", ParamType.CHOICE, "Resize",
                    listOf("Resize", "Rotate", "Flip horizontally", "Grayscale", "Compress")),
                ParamSpec("size", "Longest side (pixels)", ParamType.NUMBER, "1080",
                    showIf = "mode" to listOf("Resize")),
                ParamSpec("degrees", "Degrees", ParamType.CHOICE, "90",
                    listOf("90", "180", "270"), showIf = "mode" to listOf("Rotate")),
                ParamSpec("quality", "JPEG quality", ParamType.NUMBER, "70",
                    showIf = "mode" to listOf("Compress")),
                ParamSpec("name", "Save as", ParamType.TEXT, "edited.jpg")
            ),
            output = "The new image file",
            keywords = "image resize rotate flip grayscale compress photo edit"
        )) { env ->
            withContext(Dispatchers.IO) {
                val src = FileActions.resolve(env.app, env.str("path"))
                if (!src.exists()) throw FlowError("No such image: " + src.absolutePath)
                var bmp: Bitmap = CameraActions.loadUpright(src.absolutePath)
                var quality = 90
                when (env.choice("mode", "Resize")) {
                    "Resize" -> {
                        val longest = env.num("size", 1080.0).toInt().coerceIn(16, 8000)
                        val scale = longest.toFloat() / maxOf(bmp.width, bmp.height)
                        if (scale < 1f) bmp = Bitmap.createScaledBitmap(
                            bmp, (bmp.width * scale).toInt().coerceAtLeast(1),
                            (bmp.height * scale).toInt().coerceAtLeast(1), true
                        )
                    }
                    "Rotate" -> {
                        val m = Matrix().apply { postRotate(env.choice("degrees", "90").toFloatOrNull() ?: 90f) }
                        bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                    }
                    "Flip horizontally" -> {
                        val m = Matrix().apply { preScale(-1f, 1f) }
                        bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                    }
                    "Grayscale" -> {
                        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(out)
                        val paint = android.graphics.Paint()
                        paint.colorFilter = android.graphics.ColorMatrixColorFilter(
                            android.graphics.ColorMatrix().apply { setSaturation(0f) }
                        )
                        canvas.drawBitmap(bmp, 0f, 0f, paint)
                        bmp = out
                    }
                    "Compress" -> quality = env.num("quality", 70.0).toInt().coerceIn(1, 100)
                }
                val out = File(FileActions.baseDir(env.app), env.str("name", "edited.jpg"))
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
                FileRef(out.absolutePath, "image/jpeg")
            }
        },

        act(ActionDef(
            "media.ringtone", "Set Ringtone", Cat.MEDIA, "MusicNote",
            summary = "Set %kind% from %path%",
            params = listOf(
                ParamSpec("path", "Audio file", ParamType.FILEPATH, "{{last}}"),
                ParamSpec("kind", "Use as", ParamType.CHOICE, "Ringtone",
                    listOf("Ringtone", "Notification sound", "Alarm sound"))
            ),
            output = null,
            specialAccess = "write_settings",
            description = "Registers an audio file with the system and makes it the default sound.",
            keywords = "ringtone notification alarm sound set default audio"
        )) { env ->
            withContext(Dispatchers.IO) {
                if (!DeviceActions.canWriteSettings(env.app))
                    throw FlowError("This needs \"Modify system settings\". Grant it in Settings inside the app.")
                val f = FileActions.resolve(env.app, env.str("path"))
                if (!f.exists()) throw FlowError("No such audio file: " + f.absolutePath)
                val type = when (env.choice("kind", "Ringtone")) {
                    "Notification sound" -> RingtoneManager.TYPE_NOTIFICATION
                    "Alarm sound" -> RingtoneManager.TYPE_ALARM
                    else -> RingtoneManager.TYPE_RINGTONE
                }
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, f.name)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/*")
                    put(MediaStore.Audio.Media.IS_RINGTONE, type == RingtoneManager.TYPE_RINGTONE)
                    put(MediaStore.Audio.Media.IS_NOTIFICATION, type == RingtoneManager.TYPE_NOTIFICATION)
                    put(MediaStore.Audio.Media.IS_ALARM, type == RingtoneManager.TYPE_ALARM)
                    if (Build.VERSION.SDK_INT >= 29)
                        put(MediaStore.Audio.Media.RELATIVE_PATH, "Ringtones")
                }
                val resolver = env.app.contentResolver
                val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw FlowError("Could not register the audio file")
                resolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
                RingtoneManager.setActualDefaultRingtoneUri(env.app, type, uri)
                null
            }
        },

        act(ActionDef(
            "net.upload", "Upload File", Cat.NET, "Download",
            summary = "Upload %path%",
            params = listOf(
                ParamSpec("url", "URL", ParamType.TEXT, "https://"),
                ParamSpec("path", "File", ParamType.FILEPATH, "{{last}}"),
                ParamSpec("field", "Form field name", ParamType.TEXT, "file"),
                ParamSpec("headers", "Headers", ParamType.KEYVALUE, ""),
                ParamSpec("fields", "Extra form fields", ParamType.KEYVALUE, ""),
                ParamSpec("timeout", "Timeout (seconds)", ParamType.NUMBER, "60")
            ),
            output = "The response body",
            description = "Multipart POST, the shape most upload endpoints expect.",
            keywords = "upload post file multipart api send attachment"
        )) { env ->
            withContext(Dispatchers.IO) {
                val f = FileActions.resolve(env.app, env.str("path"))
                if (!f.exists()) throw FlowError("No such file: " + f.absolutePath)
                val boundary = "----CascadeBoundary" + System.currentTimeMillis()
                val c = URL(env.str("url").trim()).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = env.int("timeout", 60) * 1000
                c.readTimeout = env.int("timeout", 60) * 1000
                c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary)
                env.keyValues("headers").forEach { (k, v) -> c.setRequestProperty(k, v) }
                c.outputStream.buffered().use { out ->
                    env.keyValues("fields").forEach { (k, v) ->
                        out.write(("--" + boundary + "\r\n").toByteArray())
                        out.write(("Content-Disposition: form-data; name=\"" + k + "\"\r\n\r\n").toByteArray())
                        out.write((v + "\r\n").toByteArray())
                    }
                    out.write(("--" + boundary + "\r\n").toByteArray())
                    out.write((
                        "Content-Disposition: form-data; name=\"" + env.str("field", "file") +
                            "\"; filename=\"" + f.name + "\"\r\n" +
                            "Content-Type: application/octet-stream\r\n\r\n"
                        ).toByteArray())
                    f.inputStream().use { it.copyTo(out) }
                    out.write(("\r\n--" + boundary + "--\r\n").toByteArray())
                }
                val status = c.responseCode
                val body = (if (status in 200..399) c.inputStream else c.errorStream)
                    ?.bufferedReader()?.readText() ?: ""
                c.disconnect()
                if (status >= 400) throw FlowError("HTTP " + status + ": " + body.take(200))
                body
            }
        },

        act(ActionDef(
            "net.socket", "Send to Socket", Cat.NET, "Http",
            summary = "%protocol% to %host%:%port%",
            params = listOf(
                ParamSpec("protocol", "Protocol", ParamType.CHOICE, "TCP", listOf("TCP", "UDP")),
                ParamSpec("host", "Host", ParamType.TEXT, "192.168.1.10"),
                ParamSpec("port", "Port", ParamType.NUMBER, "8080"),
                ParamSpec("message", "Message", ParamType.MULTILINE, "{{last}}"),
                ParamSpec("reply", "Wait for a reply", ParamType.BOOL, "false",
                    showIf = "protocol" to listOf("TCP")),
                ParamSpec("timeout", "Timeout (seconds)", ParamType.NUMBER, "5")
            ),
            output = "The reply, if you waited for one",
            description = "Raw TCP or UDP, for talking to smart home gear and hobby servers on your network.",
            keywords = "socket tcp udp send raw network smart home esp arduino"
        )) { env ->
            withContext(Dispatchers.IO) {
                val host = env.str("host").trim()
                val port = env.int("port", 8080)
                val payload = env.str("message").toByteArray()
                val timeout = env.int("timeout", 5) * 1000
                if (env.choice("protocol", "TCP") == "UDP") {
                    DatagramSocket().use { s ->
                        s.send(DatagramPacket(payload, payload.size, InetAddress.getByName(host), port))
                    }
                    ""
                } else {
                    Socket().use { s ->
                        s.connect(java.net.InetSocketAddress(host, port), timeout)
                        s.soTimeout = timeout
                        s.getOutputStream().write(payload)
                        s.getOutputStream().flush()
                        if (env.bool("reply", false)) {
                            val buf = ByteArrayOutputStream()
                            val chunk = ByteArray(4096)
                            try {
                                while (true) {
                                    val n = s.getInputStream().read(chunk)
                                    if (n <= 0) break
                                    buf.write(chunk, 0, n)
                                    if (buf.size() > 1_000_000) break
                                }
                            } catch (e: Exception) { /* read timeout ends the reply */ }
                            buf.toString("UTF-8")
                        } else ""
                    }
                }
            }
        },

        act(ActionDef(
            "net.wol", "Wake a Computer", Cat.NET, "Public",
            summary = "Wake %mac%",
            params = listOf(
                ParamSpec("mac", "MAC address", ParamType.TEXT, "", hint = "AA:BB:CC:DD:EE:FF"),
                ParamSpec("broadcast", "Broadcast address", ParamType.TEXT, "255.255.255.255"),
                ParamSpec("port", "Port", ParamType.NUMBER, "9")
            ),
            output = null,
            description = "Sends a wake-on-LAN magic packet over Wi-Fi to a machine on the same network.",
            keywords = "wake on lan wol magic packet pc computer boot remote"
        )) { env ->
            withContext(Dispatchers.IO) {
                val clean = env.str("mac").trim().replace("-", ":").replace(".", ":")
                val parts = clean.split(":").filter { it.isNotEmpty() }
                if (parts.size != 6) throw FlowError("A MAC address needs six parts, e.g. AA:BB:CC:DD:EE:FF")
                val mac = parts.map {
                    it.toIntOrNull(16)?.toByte() ?: throw FlowError("\"" + it + "\" is not hexadecimal")
                }.toByteArray()
                val packet = ByteArray(6 + 16 * 6)
                for (i in 0 until 6) packet[i] = 0xFF.toByte()
                for (i in 0 until 16) System.arraycopy(mac, 0, packet, 6 + i * 6, 6)
                DatagramSocket().use { s ->
                    s.broadcast = true
                    s.send(DatagramPacket(
                        packet, packet.size,
                        InetAddress.getByName(env.str("broadcast", "255.255.255.255")),
                        env.int("port", 9)
                    ))
                }
                null
            }
        },

        act(ActionDef(
            "loc.map", "Open Map", Cat.LOCATION, "Map",
            summary = "Map %query%",
            params = listOf(
                ParamSpec("mode", "Show", ParamType.CHOICE, "A place",
                    listOf("A place", "Directions to", "Coordinates")),
                ParamSpec("query", "Place or address", ParamType.TEXT, "{{last}}",
                    showIf = "mode" to listOf("A place", "Directions to")),
                ParamSpec("coords", "Latitude,longitude", ParamType.TEXT, "{{last}}",
                    showIf = "mode" to listOf("Coordinates")),
                ParamSpec("zoom", "Zoom", ParamType.NUMBER, "15", showIf = "mode" to listOf("Coordinates"))
            ),
            output = null,
            keywords = "map maps navigate directions place location open geo"
        )) { env ->
            val uri = when (env.choice("mode", "A place")) {
                "Directions to" -> Uri.parse("google.navigation:q=" + Uri.encode(env.str("query")))
                "Coordinates" -> {
                    val c = env.str("coords").trim()
                    Uri.parse("geo:" + c + "?q=" + Uri.encode(c) + "&z=" + env.int("zoom", 15))
                }
                else -> Uri.parse("geo:0,0?q=" + Uri.encode(env.str("query")))
            }
            env.app.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            null
        },

        act(ActionDef(
            "dev.settings.get", "Read System Setting", Cat.DEVICE, "Tune",
            summary = "Read %key%",
            params = listOf(
                ParamSpec("scope", "Table", ParamType.CHOICE, "System", listOf("System", "Secure", "Global")),
                ParamSpec("key", "Setting name", ParamType.TEXT, "screen_brightness",
                    hint = "e.g. screen_off_timeout, accelerometer_rotation")
            ),
            output = "The stored value",
            description = "Reads any value from the Android settings tables. Pair with Run Shell Command " +
                    "(\"settings list system\") to discover what exists on your phone.",
            keywords = "settings read system secure global value setting"
        )) { env ->
            val cr = env.app.contentResolver
            val key = env.str("key").trim()
            val value = when (env.choice("scope", "System")) {
                "Secure" -> Settings.Secure.getString(cr, key)
                "Global" -> Settings.Global.getString(cr, key)
                else -> Settings.System.getString(cr, key)
            }
            value ?: throw FlowError("No setting called \"" + key + "\"")
        }
    )
}
