package org.myrec.app

import android.app.Instrumentation
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.os.SystemClock
import android.view.MotionEvent
import android.view.TextureView
import java.io.File
import kotlin.math.*

object FocusControlsTest {
    fun run(i: Instrumentation, a: MainActivity, onMain: (() -> Unit) -> Unit,
            test: (String, () -> Unit) -> Unit) {
        test("focus peaking matches reference display gradients and rejects blurred ramps") {
            val w = 79; val h = 31
            val colors = IntArray(w * h) { index ->
                val x = index % w; val y = index / w
                val n = if (y < 10) if (x < 30) 25 else 230
                    else if (y < 20) (x * 3).coerceAtMost(255) else (20 + x * 17 + y * 13) % 256
                Color.rgb(n, n, n)
            }
            val image = Bitmap.createBitmap(colors, w, h, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
            fun smooth(low: Double, high: Double, value: Double): Double {
                val t = ((value - low) / (high - low)).coerceIn(0.0, 1.0)
                return t * t * (3 - 2 * t)
            }
            fun light(x: Int, y: Int) = Color.red(colors[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]) / 255.0
            fun edge(x: Int, y: Int): Double {
                val l = light(x - 1, y); val r = light(x + 1, y)
                val top = light(x, y - 1); val bottom = light(x, y + 1); val center = light(x, y)
                return smooth(.065, .16, hypot(r - l, bottom - top)) *
                    smooth(.017, .06, max(abs(l + r - 2 * center), abs(top + bottom - 2 * center)))
            }
            Native.focusPeaking(image)
            for (y in 0 until h) for (x in 0 until w) {
                val amount = max(edge(x, y), .9 * max(edge(min(w - 1, x + 1), y), edge(x, min(h - 1, y + 1))))
                val original = Color.red(colors[y * w + x]); val pixel = image.getPixel(x, y)
                for ((actual, red) in listOf(Color.red(pixel) to 255.0, Color.green(pixel) to .045 * 255, Color.blue(pixel) to .025 * 255))
                    check(abs(actual - (original + (red - original) * amount)) <= 1.1)
            }
            check(image.getPixel(20, 15) == colors[15 * w + 20])
            check(Color.red(image.getPixel(30, 4)) > 245 && Color.green(image.getPixel(30, 4)) < 20)
            image.recycle()
        }
        val prefs = a.captureSettings
        fun owned(key: String) = key.startsWith("camera.") || key == "focusPeaking"
        val original = prefs.all.filterKeys(::owned)
        val manager = a.getSystemService(CameraManager::class.java)
        val cameras = CameraLenses.available(manager).map { it.id }.distinct()
        val back = cameras.first { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
        val lens = manager.getCameraCharacteristics(back)
        val continuous = CameraFocus.mode(lens.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf())
        val targetable = (lens.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0 && CameraFocus.canLock(continuous)
        val other = cameras.firstOrNull { it != back }
        lateinit var capture: Capture
        onMain {
            a.showCapture()
            capture = (0 until a.root.childCount).map { a.root.getChildAt(it) }.filterIsInstance<Capture>().single()
            capture.switchCamera(back)
        }
        fun waitFor(message: String = "Camera controls did not reach the expected state", predicate: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 12000
            while (SystemClock.uptimeMillis() < deadline) {
                var ready = false; onMain { ready = predicate() }
                if (ready) return
                Thread.sleep(100)
            }
            error("$message. ${a.lastMessage}")
        }
        fun switch(id: String) {
            onMain { capture.switchCamera(id) }
            waitFor { capture.readyToRecord && capture.latestResult != null }
        }
        fun tap(x: Float, y: Float) {
            val now = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(now, now + 30, action, x, y, 0)
                try { capture.dispatchTouchEvent(event) } finally { event.recycle() }
            }
        }
        fun previewTap(x: Double, y: Double) {
            val preview = capture.getChildAt(0) as TextureView
            tap(preview.left + (preview.width * x).toFloat(), preview.top + (preview.height * y).toFloat())
        }
        fun region(): Rect? {
            val result = capture.latestResult ?: return null
            if (result.request.get(CaptureRequest.CONTROL_AF_REGIONS)?.any { it.meteringWeight > 0 } != true) return null
            return result.get(CaptureResult.CONTROL_AF_REGIONS)?.firstOrNull { it.meteringWeight > 0 }?.rect
        }
        try {
            waitFor("Camera did not open when the preview became available") { capture.readyToRecord && capture.latestResult != null }
            if (targetable) test("preview taps move the hardware focus region in portrait and landscape") {
                val originalOrientation = a.requestedOrientation
                try {
                    for (landscape in listOf(false, true)) {
                        onMain {
                            a.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                                else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        }
                        waitFor { (capture.width > capture.height) == landscape }
                        switch(back)
                        waitFor { capture.cameraFrame(32, 18)?.let { it.recycle(); true } ?: false }
                        var rotation = 0
                        onMain {
                            val sensor = lens.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                            rotation = CameraOrientation.recording(sensor, a.windowManager.defaultDisplay.rotation * 90, false)
                            previewTap(.28, .34)
                        }
                        waitFor { region() != null }
                        val first = checkNotNull(region())
                        onMain {
                            val p = capture.getChildAt(0)
                            val scale = min(capture.width, capture.height) / 294f
                            val image = Bitmap.createBitmap(capture.width, capture.height, Bitmap.Config.ARGB_8888)
                            try {
                                capture.getChildAt(1).draw(Canvas(image))
                                val x = (p.left + p.width * .28).roundToInt()
                                val y = (p.top + p.height * .34 - 13 * scale).roundToInt()
                                check((y - 4..y + 4).any { Color.red(image.getPixel(x,it)) == 255 &&
                                    Color.green(image.getPixel(x,it)) == 205 }) { "Missing focus box near the tap" }
                            } finally { image.recycle() }
                            previewTap(.72, .66)
                        }
                        waitFor { region()?.let { it != first } == true }
                        val second = checkNotNull(region())
                        val from = CameraFraming.oriented(CameraPoint(.28,.34), -rotation)
                        val to = CameraFraming.oriented(CameraPoint(.72,.66), -rotation)
                        check((second.centerX() - first.centerX()) * (to.x - from.x) > 0)
                        check((second.centerY() - first.centerY()) * (to.y - from.y) > 0)
                        waitFor { capture.latestResult?.request?.get(CaptureRequest.CONTROL_AF_MODE) == continuous }
                        check(!capture.focusIsLocked)
                    }
                } finally { onMain { a.requestedOrientation = originalOrientation } }
            }
            test("each camera restores its own zoom and manual lens state across switches and reopen") {
                onMain {
                    capture.setZoom(1.4f, true)
                    if (capture.focusAdjustable) capture.setFocusPosition(.3f)
                }
                Thread.sleep(400)
                if (other != null) {
                    switch(other)
                    onMain { capture.setZoom(1.1f, true) }
                    switch(back)
                }
                check(abs(capture.currentZoom - 1.4f) < .01)
                if (capture.focusAdjustable) {
                    check(capture.manualFocusActive && abs(capture.focusPosition - .3f) < .01)
                    val maximum = manager.getCameraCharacteristics(back).get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)!!
                    waitFor { abs((capture.latestResult?.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: -1f) - maximum * .7f) < .05 }
                }
                onMain { capture.suspendCamera(); capture.resumeCamera() }
                waitFor { capture.readyToRecord && capture.latestResult != null }
                check(abs(capture.currentZoom - 1.4f) < .01)
            }
            test("manual focus gestures reach lens metadata and holding AF toggles only preview peaking") {
                onMain {
                    if (capture.focusAdjustable) capture.setFocusPosition(.5f)
                    val r = capture.controlBounds("focus") ?: error("No focus control")
                    val now = SystemClock.uptimeMillis()
                    val dy = 40 * a.resources.displayMetrics.density
                    for ((action, y) in listOf(MotionEvent.ACTION_DOWN to r.centerY(), MotionEvent.ACTION_MOVE to r.centerY() - dy, MotionEvent.ACTION_UP to r.centerY() - dy)) {
                        val event = MotionEvent.obtain(now, now + 30, action, r.centerX(), y, 0)
                        capture.dispatchTouchEvent(event); event.recycle()
                    }
                    if (capture.focusAdjustable) check(abs(capture.focusPosition - .625f) < .01)
                    if (capture.focusAdjustable) {
                        previewTap(.4, .35)
                        check(capture.manualFocusActive && abs(capture.focusPosition - .625f) < .01)
                    }
                    val wasLocked = capture.focusIsLocked
                    val before = prefs.getBoolean("focusPeaking", false)
                    for ((action, elapsed) in listOf(MotionEvent.ACTION_DOWN to 0L, MotionEvent.ACTION_UP to 500L)) {
                        val event = MotionEvent.obtain(now + 100, now + 100 + elapsed, action, r.centerX(), r.centerY(), 0)
                        capture.dispatchTouchEvent(event); event.recycle()
                    }
                    check(prefs.getBoolean("focusPeaking", false) != before && capture.focusIsLocked == wasLocked)
                }
            }
            test("focus aid remains a preview overlay and recording retains a decodable source") {
                waitFor("Camera and microphone did not become ready after reopening") {
                    capture.readyToRecord && capture.microphoneInput != null && capture.microphoneReceiving
                }
                onMain {
                    a.editor.load(Project(name = "Focus controls check"))
                    if (targetable && capture.focusIsLocked) capture.controlBounds("focus")?.let { tap(it.centerX(), it.centerY()) }
                    capture.start()
                }
                waitFor("Focus-aid recording did not start") {
                    capture.recording && !capture.paused && capture.elapsedSeconds() >= 1.3
                }
                if (targetable) {
                    onMain { previewTap(.62, .42) }
                    waitFor { region() != null }
                    check(capture.recording)
                }
                onMain { capture.stop() }
                val clip = a.editor.project.clips.single()
                check(clip.effects.none { it.kind.contains("Peaking", true) } && File(clip.path).length() > 1000)
                VideoDecoder(clip.path).use { decoder -> (decoder.frame(.4, 160, 90) ?: error("No recorded frame")).recycle() }
            }
        } finally {
            onMain {
                if (capture.recording) capture.stop()
                capture.suspendCamera()
                prefs.edit().apply {
                    for (key in prefs.all.keys.filter(::owned)) remove(key)
                    for ((key, value) in original) when (value) {
                        is Float -> putFloat(key, value)
                        is Boolean -> putBoolean(key, value)
                    }
                }.apply()
            }
        }
    }
}
