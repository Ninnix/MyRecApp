package org.myrec.app

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.*
import android.media.ImageReader
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.*

// ImageReader receives sensor pixels without the SurfaceTexture producer transform.
internal fun cameraReference(manager: CameraManager, id: String, rotation: Int): Bitmap {
    val thread = HandlerThread("camera-reference").apply { start() }
    val handler = Handler(thread.looper)
    val result = AtomicReference<Bitmap>()
    val error = AtomicReference<Throwable>()
    val ready = CountDownLatch(1)
    val chars = manager.getCameraCharacteristics(id)
    val sizes =
        chars
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(ImageFormat.YUV_420_888)
            .filter { it.width <= 1280 }
    val size = sizes.minBy { abs(it.width.toDouble() / it.height - 16.0 / 9) + 1.0 / it.width }
    val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
    var camera: CameraDevice? = null
    var session: CameraCaptureSession? = null
    var frames = 0
    reader.setOnImageAvailableListener(
        { source ->
            source.acquireLatestImage()?.use { image ->
                if (++frames < 15 || result.get() != null) return@use
                try {
                    val quarter = rotation % 180 != 0
                    val bitmap =
                        Bitmap.createBitmap(
                            if (quarter) 180 else 320,
                            if (quarter) 320 else 180,
                            Bitmap.Config.ARGB_8888,
                        )
                    val p = image.planes
                    val crop = image.cropRect
                    Native.yuv(
                        p[0].buffer.slice(),
                        p[1].buffer.slice(),
                        p[2].buffer.slice(),
                        p[0].rowStride,
                        p[1].rowStride,
                        p[2].rowStride,
                        p[1].pixelStride,
                        p[2].pixelStride,
                        crop.left,
                        crop.top,
                        crop.width(),
                        crop.height(),
                        rotation,
                        MediaFormat.COLOR_STANDARD_BT709,
                        false,
                        bitmap,
                    )
                    result.set(bitmap)
                } catch (e: Throwable) {
                    error.set(e)
                }
                ready.countDown()
            }
        },
        handler,
    )
    try {
        manager.openCamera(
            id,
            object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    device.createCaptureSession(
                        listOf(reader.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(configured: CameraCaptureSession) {
                                session = configured
                                val request =
                                    device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                                request.addTarget(reader.surface)
                                request.set(
                                    CaptureRequest.CONTROL_MODE,
                                    CaptureRequest.CONTROL_MODE_AUTO,
                                )
                                request.set(
                                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON,
                                )
                                request.set(
                                    CaptureRequest.CONTROL_AF_MODE,
                                    CameraFocus.mode(
                                        chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                                            ?: intArrayOf(0)
                                    ),
                                )
                                configured.setRepeatingRequest(request.build(), null, handler)
                            }

                            override fun onConfigureFailed(failed: CameraCaptureSession) {
                                error.set(
                                    IllegalStateException("Raw camera reference session failed")
                                )
                                ready.countDown()
                            }
                        },
                        handler,
                    )
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    error.set(IllegalStateException("Reference camera disconnected"))
                    ready.countDown()
                }

                override fun onError(device: CameraDevice, code: Int) {
                    device.close()
                    error.set(IllegalStateException("Reference camera error $code"))
                    ready.countDown()
                }
            },
            handler,
        )
        check(ready.await(10, TimeUnit.SECONDS)) { "No raw camera reference frame" }
        error.get()?.let { throw it }
        return result.get() ?: error("Missing raw camera reference")
    } finally {
        val closed = CountDownLatch(1)
        handler.post {
            session?.close()
            camera?.close()
            reader.close()
            closed.countDown()
        }
        check(closed.await(5, TimeUnit.SECONDS))
        thread.quitSafely()
    }
}

internal fun cameraCorrelations(reference: Bitmap, recorded: Bitmap): List<Double> {
    fun values(bitmap: Bitmap): DoubleArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, 48, 48, true)
        val values = DoubleArray(48 * 48)
        for (y in 0 until 48) for (x in 0 until 48) {
            val p = scaled.getPixel(x, y)
            values[y * 48 + x] =
                (p shr 16 and 255) * .2126 + (p shr 8 and 255) * .7152 + (p and 255) * .0722
        }
        if (scaled !== bitmap) scaled.recycle()
        return values
    }
    val expected = values(reference)
    return listOf(0, 90, 180, 270).map { rotation ->
        val image =
            Bitmap.createBitmap(
                recorded,
                0,
                0,
                recorded.width,
                recorded.height,
                Matrix().apply { postRotate(rotation.toFloat()) },
                true,
            )
        val actual = values(image)
        if (image !== recorded) image.recycle()
        var best = -1.0
        // ImageReader and stabilized video can have different crops and optical centers.
        for (step in 16..32) for (dy in -6..6 step 2) for (dx in -6..6 step 2) {
            val scale = step / 20.0
            val xs = IntArray(32) { ((it + 8 - 24) / scale + 24 + dx).roundToInt() }
            val ys = IntArray(32) { ((it + 8 - 24) / scale + 24 + dy).roundToInt() }
            if (xs.first() < 0 || xs.last() >= 48 || ys.first() < 0 || ys.last() >= 48) continue
            var a = 0.0
            var b = 0.0
            var aa = 0.0
            var bb = 0.0
            var ab = 0.0
            for (y in 0 until 32) for (x in 0 until 32) {
                val u = expected[ys[y] * 48 + xs[x]]
                val v = actual[(y + 8) * 48 + x + 8]
                a += u
                b += v
                aa += u * u
                bb += v * v
                ab += u * v
            }
            val score =
                (ab - a * b / 1024) /
                    sqrt(max(0.0, aa - a * a / 1024) * max(0.0, bb - b * b / 1024))
                        .coerceAtLeast(1.0)
            best = max(best, score)
        }
        best
    }
}
