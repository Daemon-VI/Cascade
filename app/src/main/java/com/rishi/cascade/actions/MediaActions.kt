package com.rishi.cascade.actions

import android.content.ComponentName
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.RingtoneManager
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import com.rishi.cascade.engine.FileRef
import com.rishi.cascade.engine.FlowError
import com.rishi.cascade.model.ActionDef
import com.rishi.cascade.model.Cat
import com.rishi.cascade.model.ParamSpec
import com.rishi.cascade.model.ParamType
import com.rishi.cascade.trigger.NotificationWatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

object MediaActions {

    private suspend fun speak(app: android.content.Context, text: String, rate: Float, pitch: Float, wait: Boolean) {
        if (text.isBlank()) return
        suspendCancellableCoroutine<Unit> { cont ->
            var tts: TextToSpeech? = null
            tts = TextToSpeech(app) { status ->
                if (status != TextToSpeech.SUCCESS) {
                    if (cont.isActive) cont.resume(Unit)
                    return@TextToSpeech
                }
                val engine = tts ?: return@TextToSpeech
                engine.language = Locale.getDefault()
                engine.setSpeechRate(rate)
                engine.setPitch(pitch)
                if (wait) {
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) {
                            engine.shutdown()
                            if (cont.isActive) cont.resume(Unit)
                        }
                        @Deprecated("legacy callback")
                        override fun onError(id: String?) {
                            engine.shutdown()
                            if (cont.isActive) cont.resume(Unit)
                        }
                    })
                    engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cascade")
                } else {
                    engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cascade")
                    if (cont.isActive) cont.resume(Unit)
                }
            }
            cont.invokeOnCancellation { tts?.shutdown() }
        }
    }

    fun all(): List<Action> = listOf(

        act(ActionDef(
            "media.speak", "Speak Text", Cat.MEDIA, "RecordVoiceOver",
            summary = "Say %text%",
            params = listOf(
                ParamSpec("text", "Text", ParamType.MULTILINE, "{{last}}"),
                ParamSpec("rate", "Speed", ParamType.NUMBER, "1.0"),
                ParamSpec("pitch", "Pitch", ParamType.NUMBER, "1.0"),
                ParamSpec("wait", "Wait until finished", ParamType.BOOL, "true")
            ),
            output = null,
            keywords = "speak say tts voice talk read aloud"
        )) { env ->
            speak(env.app, env.str("text"),
                env.num("rate", 1.0).toFloat().coerceIn(0.1f, 3f),
                env.num("pitch", 1.0).toFloat().coerceIn(0.1f, 3f),
                env.bool("wait", true))
            null
        },

        act(ActionDef(
            "media.control", "Control Playback", Cat.MEDIA, "PlayArrow",
            summary = "%command%",
            params = listOf(ParamSpec("command", "Command", ParamType.CHOICE, "Play/Pause",
                listOf("Play/Pause", "Play", "Pause", "Next track", "Previous track", "Stop", "Fast forward", "Rewind"))),
            output = null,
            description = "Sends a media key to whichever app is playing.",
            keywords = "music play pause next previous skip track spotify playback media"
        )) { env ->
            val code = when (env.choice("command", "Play/Pause")) {
                "Play" -> KeyEvent.KEYCODE_MEDIA_PLAY
                "Pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                "Next track" -> KeyEvent.KEYCODE_MEDIA_NEXT
                "Previous track" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                "Stop" -> KeyEvent.KEYCODE_MEDIA_STOP
                "Fast forward" -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                "Rewind" -> KeyEvent.KEYCODE_MEDIA_REWIND
                else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            }
            val am = env.app.getSystemService(AudioManager::class.java)
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            null
        },

        act(ActionDef(
            "media.nowplaying", "Now Playing", Cat.MEDIA, "MusicNote",
            params = listOf(ParamSpec("field", "Get", ParamType.CHOICE, "Title",
                listOf("Title", "Artist", "Album", "App", "Everything"))),
            output = "What is playing",
            specialAccess = "notification_listener",
            description = "Reads the current media session. Needs notification access.",
            keywords = "now playing song track music artist spotify current"
        )) { env ->
            val msm = env.app.getSystemService(MediaSessionManager::class.java)
            val comp = ComponentName(env.app, NotificationWatcher::class.java)
            val sessions = try { msm.getActiveSessions(comp) } catch (e: SecurityException) {
                throw FlowError("This needs notification access. Turn it on in Settings inside the app.")
            }
            val s = sessions.firstOrNull { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING }
                ?: sessions.firstOrNull() ?: throw FlowError("Nothing is playing")
            val md = s.metadata
            val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
            val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
            val album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
            when (env.choice("field", "Title")) {
                "Artist" -> artist
                "Album" -> album
                "App" -> s.packageName
                "Everything" -> JSONObject().put("title", title).put("artist", artist)
                    .put("album", album).put("app", s.packageName)
                else -> title
            }
        },

        act(ActionDef(
            "media.sound", "Play Sound", Cat.MEDIA, "VolumeUp",
            summary = "Play %sound%",
            params = listOf(
                ParamSpec("sound", "Sound", ParamType.CHOICE, "Notification",
                    listOf("Notification", "Ringtone", "Alarm", "File")),
                ParamSpec("path", "File", ParamType.FILEPATH, "", showIf = "sound" to listOf("File")),
                ParamSpec("wait", "Wait until finished", ParamType.BOOL, "false")
            ),
            output = null,
            keywords = "sound play audio beep ringtone alarm notification chime"
        )) { env ->
            val uri: Uri = when (env.choice("sound", "Notification")) {
                "Ringtone" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                "Alarm" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                "File" -> Uri.fromFile(FileActions.resolve(env.app, env.str("path")))
                else -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            }
            val mp = MediaPlayer()
            mp.setDataSource(env.app, uri)
            mp.prepare()
            mp.start()
            if (env.bool("wait", false)) {
                val ms = mp.duration.coerceIn(0, 60_000).toLong()
                delay(ms)
                mp.release()
            } else {
                mp.setOnCompletionListener { it.release() }
            }
            null
        },

        act(ActionDef(
            "media.record", "Record Audio", Cat.MEDIA, "Mic",
            summary = "Record %seconds%s",
            params = listOf(
                ParamSpec("seconds", "Length (seconds)", ParamType.NUMBER, "5"),
                ParamSpec("name", "Save as", ParamType.TEXT, "recording.m4a")
            ),
            output = "The recording file",
            permissions = listOf("android.permission.RECORD_AUDIO"),
            keywords = "record audio microphone voice memo sound"
        )) { env ->
            val out = File(FileActions.baseDir(env.app), env.str("name", "recording.m4a"))
            out.parentFile?.mkdirs()
            val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(env.app)
                      else @Suppress("DEPRECATION") MediaRecorder()
            try {
                rec.setAudioSource(MediaRecorder.AudioSource.MIC)
                rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                rec.setOutputFile(out.absolutePath)
                rec.prepare()
                rec.start()
                delay((env.num("seconds", 5.0) * 1000).toLong().coerceIn(500, 600_000))
                rec.stop()
            } catch (e: Exception) {
                throw FlowError("Recording failed: " + (e.message ?: "microphone unavailable"))
            } finally {
                try { rec.release() } catch (e: Exception) { }
            }
            FileRef(out.absolutePath, "audio/mp4")
        }
    )
}
