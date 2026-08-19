package com.rishi.cascade.actions

import android.Manifest
import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ExifInterface
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import com.rishi.cascade.engine.FileRef
import com.rishi.cascade.engine.FlowError
import com.rishi.cascade.model.ActionDef
import com.rishi.cascade.model.Cat
import com.rishi.cascade.model.ParamSpec
import com.rishi.cascade.model.ParamType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Headless stills with Camera2 — no preview on screen. Opens the chosen lens, lets 3A settle
 * against a throwaway surface, grabs one JPEG and tears everything down.
 * Adapted from the intruder-capture path proven on this device in AegisToolkit.
 */
object SilentCamera {

    private const val WARMUP_MS = 700L
    private const val HARD_TIMEOUT_MS = 8000L

    @SuppressLint("MissingPermission") // the caller checks CAMERA first
    fun capture(ctx: Context, outFile: File, front: Boolean, onComplete: (Boolean) -> Unit) {
        val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val thread = HandlerThread("cascade-cam").apply { start() }
        val handler = Handler(thread.looper)
        val finished = AtomicBoolean(false)

        var camera: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var reader: ImageReader? = null
        var dummySurface: Surface? = null
        var dummyTexture: SurfaceTexture? = null

        fun done(success: Boolean) {
            if (!finished.compareAndSet(false, true)) return
            try { session?.close() } catch (e: Exception) {}
            try { camera?.close() } catch (e: Exception) {}
            try { reader?.close() } catch (e: Exception) {}
            try { dummySurface?.release() } catch (e: Exception) {}
            try { dummyTexture?.release() } catch (e: Exception) {}
            try { thread.quitSafely() } catch (e: Exception) {}
            onComplete(success)
        }

        handler.postDelayed({ done(outFile.exists() && outFile.length() > 0) }, HARD_TIMEOUT_MS)

        try {
            val cameraId = lensId(manager, front) ?: run { done(false); return }
            val chars = manager.getCameraCharacteristics(cameraId)
            val size = pickSize(chars)
            val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

            reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)
            reader!!.setOnImageAvailableListener({ r ->
                val image = try { r.acquireLatestImage() } catch (e: Exception) { null }
                var ok = false
                if (image != null) {
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { it.write(bytes) }
                        ok = outFile.length() > 0
                    } catch (e: Exception) {
                        ok = false
                    } finally {
                        try { image.close() } catch (e: Exception) {}
                    }
                }
                done(ok)
            }, handler)

            dummyTexture = SurfaceTexture(false).apply { setDefaultBufferSize(size.width, size.height) }
            dummySurface = Surface(dummyTexture)

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    try {
                        val targets = listOf(dummySurface!!, reader!!.surface)
                        @Suppress("DEPRECATION")
                        device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(cs: CameraCaptureSession) {
                                session = cs
                                try {
                                    val preview = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                        addTarget(dummySurface!!)
                                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                    }
                                    cs.setRepeatingRequest(preview.build(), null, handler)
                                    handler.postDelayed({
                                        fireStill(device, cs, reader!!, sensorOrientation, handler)
                                    }, WARMUP_MS)
                                } catch (e: Exception) {
                                    done(false)
                                }
                            }

                            override fun onConfigureFailed(cs: CameraCaptureSession) = done(false)
                        }, handler)
                    } catch (e: Exception) {
                        done(false)
                    }
                }

                override fun onDisconnected(device: CameraDevice) = done(false)
                override fun onError(device: CameraDevice, error: Int) = done(false)
            }, handler)
        } catch (e: Exception) {
            done(false)
        }
    }

    private fun fireStill(
        device: CameraDevice,
        cs: CameraCaptureSession,
        reader: ImageReader,
        sensorOrientation: Int,
        handler: Handler
    ) {
        try {
            cs.stopRepeating()
            val still = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
            }
            cs.capture(still.build(), null, handler)
        } catch (e: Exception) {
            // the hard timeout finishes us
        }
    }

    private fun lensId(manager: CameraManager, front: Boolean): String? {
        val want = if (front) CameraCharacteristics.LENS_FACING_FRONT
        else CameraCharacteristics.LENS_FACING_BACK
        return manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want
        } ?: manager.cameraIdList.firstOrNull()
    }

    /** Big enough to look good as a wallpaper, small enough to grab quickly. */
    private fun pickSize(chars: CameraCharacteristics): Size {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
        if (sizes.isEmpty()) return Size(1280, 960)
        return sizes.filter { it.width.toLong() * it.height <= 8_500_000L }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.minByOrNull { it.width.toLong() * it.height }
            ?: Size(1280, 960)
    }
}

object CameraActions {

    private fun hasCamera(c: Context) =
        c.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** Loads a JPEG the right way up, since camera files carry their rotation in EXIF. */
    fun loadUpright(path: String): Bitmap {
        val bmp = BitmapFactory.decodeFile(path) ?: throw FlowError("Could not read the image: $path")
        val rotation = try {
            when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) { 0f }
        if (rotation == 0f) return bmp
        val m = Matrix().apply { postRotate(rotation) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    fun all(): List<Action> = listOf(

        act(ActionDef(
            "camera.photo", "Take Photo", Cat.MEDIA, "PhotoCamera",
            summary = "%lens% camera photo",
            params = listOf(
                ParamSpec("lens", "Camera", ParamType.CHOICE, "Front", listOf("Front", "Back")),
                ParamSpec("name", "Save as", ParamType.TEXT, "photo.jpg"),
                ParamSpec("unique", "Keep every shot (timestamp the name)", ParamType.BOOL, "false")
            ),
            output = "The photo file",
            permissions = listOf("android.permission.CAMERA"),
            description = "Takes a still with no preview and no shutter UI. Android blocks the camera " +
                    "for apps in the background, so a flow triggered while another app is in front may " +
                    "be refused by the system.",
            keywords = "camera photo selfie picture capture shot front back"
        )) { env ->
            if (!hasCamera(env.app))
                throw FlowError("This needs the Camera permission. Grant it in Settings inside the app.")
            val base = env.str("name", "photo.jpg").ifBlank { "photo.jpg" }
            val name = if (env.bool("unique", false)) {
                val dot = base.lastIndexOf('.')
                val stamp = System.currentTimeMillis().toString()
                if (dot > 0) base.substring(0, dot) + "-" + stamp + base.substring(dot)
                else base + "-" + stamp
            } else base
            val out = File(FileActions.baseDir(env.app), name)
            val front = env.choice("lens", "Front") == "Front"

            val ok = withTimeoutOrNull(15_000L) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    SilentCamera.capture(env.app, out, front) { success ->
                        if (cont.isActive) cont.resume(success)
                    }
                }
            } ?: false

            if (!ok || !out.exists() || out.length() == 0L)
                throw FlowError(
                    "The camera did not produce a photo. If this flow ran in the background, " +
                            "Android may have refused camera access - try running it from a home screen icon."
                )
            FileRef(out.absolutePath, "image/jpeg")
        },

        act(ActionDef(
            "dev.wallpaper", "Set Wallpaper", Cat.DEVICE, "Wallpaper",
            summary = "Wallpaper from %file% to %target%",
            params = listOf(
                ParamSpec("file", "Image file", ParamType.FILEPATH, "{{last}}",
                    hint = "a photo file, usually the result of Take Photo or Download File"),
                ParamSpec("target", "Apply to", ParamType.CHOICE, "Home screen",
                    listOf("Home screen", "Lock screen", "Both"))
            ),
            output = null,
            description = "Sets an image file as your wallpaper.",
            keywords = "wallpaper background home lock screen set image"
        )) { env ->
            withContext(Dispatchers.IO) {
                val raw = env.str("file").trim()
                if (raw.isEmpty()) throw FlowError("No image given")
                val file = FileActions.resolve(env.app, raw)
                if (!file.exists()) throw FlowError("No such image: " + file.absolutePath)

                val bitmap = loadUpright(file.absolutePath)
                val wm = WallpaperManager.getInstance(env.app)
                val which = when (env.choice("target", "Home screen")) {
                    "Lock screen" -> WallpaperManager.FLAG_LOCK
                    "Both" -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                    else -> WallpaperManager.FLAG_SYSTEM
                }
                try {
                    if (Build.VERSION.SDK_INT >= 24) {
                        wm.setBitmap(bitmap, null, true, which)
                    } else {
                        @Suppress("DEPRECATION")
                        wm.setBitmap(bitmap)
                    }
                } catch (e: Exception) {
                    throw FlowError("The system refused the wallpaper change: " + (e.message ?: ""))
                }
                null
            }
        }
    )
}
