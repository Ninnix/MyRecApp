package org.myrec.app

import android.app.Instrumentation
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.*
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.*
import android.view.MotionEvent
import android.view.TextureView
import java.io.File
import kotlin.math.*

object CaptureControlsTest {
    fun run(
        instrumentation: Instrumentation,
        a: MainActivity,
        onMain: (() -> Unit) -> Unit,
        test: (String, () -> Unit) -> Unit,
    ) {
        val folder = File(a.getExternalFilesDir(null), "capture-controls-checks").apply { mkdirs() }
        val manager = a.getSystemService(CameraManager::class.java)
        val pair = DualCamera.pair(manager)
        val front =
            manager.cameraIdList.first {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_FRONT
            }
        val back =
            manager.cameraIdList.first {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            }
        val prefs = a.captureSettings
        val lightKeys = setOf("lightWidth", "lightTone", "lightOpacity", "lightCustom")
        val oldLight = prefs.all.filterKeys { it in lightKeys }
        onMain { a.showCapture() }
        Thread.sleep(1000)
        val capture =
            (0 until a.root.childCount)
                .map { a.root.getChildAt(it) }
                .filterIsInstance<Capture>()
                .single()
        fun control(title: String, enabled: Boolean = true, clickable: Boolean = true, range: Boolean = false): android.view.accessibility.AccessibilityNodeInfo {
            val deadline = SystemClock.uptimeMillis() + 4000
            val visible = StringBuilder()
            while (SystemClock.uptimeMillis() < deadline) {
                visible.clear()
                val nodes = java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
                instrumentation.uiAutomation.rootInActiveWindow?.let { nodes.add(it) }
                while (nodes.isNotEmpty()) {
                    val node = nodes.removeFirst()
                    visible.append("${node.text} / ${node.contentDescription} (click=${node.isClickable}, enabled=${node.isEnabled})\n")
                    if ((node.contentDescription?.toString() == title || node.text?.toString()?.equals(title, ignoreCase = true) == true) && (!clickable || node.isClickable) && node.isEnabled == enabled && (!range || node.rangeInfo!=null)) return node
                    for (i in 0 until node.childCount) node.getChild(i)?.let { nodes.add(it) }
                }
                Thread.sleep(100)
            }
            instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                File(folder, "missing-control.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
            error("Missing $title control (enabled=$enabled)\n$visible")
        }
        fun click(title: String) {
            repeat(3) {
                if (control(title).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
                    Thread.sleep(200); return
                }
                Thread.sleep(100)
            }
            error("Could not click $title")
        }
        fun tap(id: String) {
            if (id == "torch") {
                onMain { check(capture.controlBounds("torch") == null) }
                tap("settings")
                click(if (capture.front || capture.concurrentInputSizes().isNotEmpty()) "Front light" else "Torch")
                click("Camera settings done")
                return
            }
            var bounds: RectF? = null
            val deadline = SystemClock.uptimeMillis() + 3000
            while (bounds == null && SystemClock.uptimeMillis() < deadline) {
                onMain { bounds = capture.controlBounds(id) }
                if (bounds == null) Thread.sleep(100)
            }
            onMain {
                val r = bounds ?: error("Missing $id control")
                val now = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val e = MotionEvent.obtain(now, now + 30, action, r.centerX(), r.centerY(), 0)
                    capture.dispatchTouchEvent(e)
                    e.recycle()
                }
            }
            Thread.sleep(150)
        }
        fun drag(x: Float, y: Float, endX: Float, endY: Float) {
            val now = SystemClock.uptimeMillis()
            onMain {
                for (i in 0..12) {
                    val action =
                        if (i == 0) MotionEvent.ACTION_DOWN
                        else if (i == 12) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
                    val e =
                        MotionEvent.obtain(
                            now,
                            now + i * 20L,
                            action,
                            x + (endX - x) * i / 12,
                            y + (endY - y) * i / 12,
                            0,
                        )
                    capture.dispatchTouchEvent(e)
                    e.recycle()
                }
            }
            Thread.sleep(150)
        }
        fun render(name: String, overlayOnly: Boolean = false): Bitmap {
            var image: Bitmap? = null
            onMain {
                val b = Bitmap.createBitmap(capture.width, capture.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(b)
                if (!overlayOnly) {
                    canvas.drawColor(Color.BLACK)
                    capture.drawCamera(canvas)
                }
                capture.getChildAt(1).draw(canvas)
                image = b
                File(folder, "$name.png").outputStream().use {
                    b.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            return image!!
        }
        fun bounds(): RectF {
            var r: RectF? = null
            onMain { r = capture.insetBounds() }
            return r ?: error("Missing inset")
        }
        try {
            for ((name, orientation) in
                listOf(
                    "portrait" to ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                    "landscape" to ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                )) {
                onMain {
                    capture.setTorch(false)
                    capture.setFrontLight(tone = "White", opacity = 1f)
                    capture.setDualLayout(4)
                    a.requestedOrientation = orientation
                }
                Thread.sleep(700)
                onMain { capture.switchCamera(front) }
                Thread.sleep(1100)
                test("$name simplified recording toolbar exposes color and cameras in settings") {
                    onMain {
                        val mic = capture.controlBounds("mic") ?: error("No microphone")
                        val time = capture.controlBounds("time") ?: error("No timer")
                        val effects = capture.controlBounds("effects") ?: error("No effects")
                        val settings = capture.controlBounds("settings") ?: error("No settings")
                        check(mic.right < time.left && time.right < effects.left && effects.right < settings.left)
                        if (name == "portrait") check(abs(time.centerX() - capture.width / 2f) < 2)
                        check(capture.controlBounds("hdr") == null && capture.controlBounds("dual") == null)
                    }
                    render("$name-toolbar").recycle()
                    tap("settings")
                    control("SDR"); control("More settings")
                    check(control("Use two cameras", pair != null).isChecked.not())
                    instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                        File(folder, "$name-camera-settings.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        image.recycle()
                    }
                    click("Camera settings done")
                }
                test("$name recording marks remain opposite pause and survive saving") {
                    onMain { a.editor.load(Project(name = "Recording marks check")) }
                    tap("record")
                    Thread.sleep(900)
                    check(capture.recording)
                    tap("settings")
                    control("SDR", false); control("HDR", false); control("Use two cameras", false)
                    click("Camera settings done")
                    val mark = capture.controlBounds("mark") ?: error("No Mark button")
                    val pause = capture.controlBounds("pause") ?: error("No Pause button")
                    if (name == "portrait") check(mark.centerX() > pause.centerX())
                    else check(mark.centerY() < pause.centerY())
                    tap("mark")
                    tap("pause")
                    val at = capture.elapsedSeconds()
                    Thread.sleep(500)
                    check(abs(capture.elapsedSeconds() - at) < .05)
                    tap("mark")
                    tap("pause")
                    Thread.sleep(500)
                    tap("record")
                    val clip = a.editor.project.clips.single()
                    check(clip.marks.size == 2)
                    check(clip.marks.all { it > .3 && it < clip.duration })
                    val restored = a.store.decode(a.store.encode(a.editor.project))
                    check(restored.markers() == a.editor.project.markers())
                }
                onMain { a.requestedOrientation = orientation }
                Thread.sleep(700)
                check((capture.width > capture.height) == (name == "landscape"))
                test("$name front screen torch resizes by touch and restores brightness") {
                    onMain {
                        a.window.attributes = a.window.attributes.apply { screenBrightness = .42f }
                    }
                    tap("torch")
                    check(a.window.attributes.screenBrightness == 1f)
                    val short = min(capture.width, capture.height).toFloat()
                    val y = capture.height / 2f
                    val texture = capture.getChildAt(0) as TextureView
                    val probeX = max(short * .15f, texture.left + short * .03f).toInt()
                    drag(2f, y, short * .34f, y)
                    render("$name-light-wide", true).also {
                        val pixel = it.getPixel(probeX, y.toInt())
                        check(Color.alpha(pixel) >= 250 && Color.red(pixel) > 250)
                        val corner = (short * prefs.getFloat("lightWidth", .06f) + 3).toInt()
                        check(Color.alpha(it.getPixel(corner, corner)) >= 250) {
                            "Inner light corner is square"
                        }
                        if (texture.top > 3) check(it.getPixel(it.width / 2, 1) == Color.WHITE)
                        if (texture.bottom < capture.height - 3)
                            check(it.getPixel(it.width / 2, it.height - 2) == Color.WHITE)
                        it.recycle()
                    }
                    drag(short * .3f, y, short * .025f, y)
                    render("$name-light-narrow", true).also {
                        check(Color.alpha(it.getPixel(1, y.toInt())) >= 190)
                        check(Color.alpha(it.getPixel(probeX, y.toInt())) == 0)
                        val border = short * prefs.getFloat("lightWidth", .06f)
                        val right = (capture.width - border).toInt() - 3
                        val bottom = (capture.height - border).toInt() - 3
                        check(Color.alpha(it.getPixel(right, bottom)) >= 250) {
                            "Light corner at the window boundary is square"
                        }
                        if (texture.top > border * 3)
                            check(Color.alpha(it.getPixel(capture.width / 3, ((border + texture.top) / 2).toInt())) == 0) {
                                "The front light filled the letterbox instead of keeping its window border"
                            }
                        it.recycle()
                    }
                    render("$name-light-preview").recycle()
                    check(
                        capture.latestResult?.request?.get(CaptureRequest.FLASH_MODE) ==
                            CaptureRequest.FLASH_MODE_OFF
                    ) {
                        "Front torch requested a hardware flash"
                    }
                    onMain { capture.switchCamera(back) }
                    Thread.sleep(900)
                    check(a.window.attributes.screenBrightness == .42f)
                    if (
                        manager
                            .getCameraCharacteristics(back)
                            .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    ) {
                        check(
                            capture.latestResult?.request?.get(CaptureRequest.FLASH_MODE) ==
                                CaptureRequest.FLASH_MODE_TORCH
                        ) {
                            "Rear hardware torch did not turn on"
                        }
                    }
                    onMain { capture.switchCamera(front) }
                    Thread.sleep(900)
                    check(a.window.attributes.screenBrightness == 1f)
                    onMain { capture.suspendCamera() }
                    check(a.window.attributes.screenBrightness == .42f)
                    onMain { capture.resumeCamera() }
                    Thread.sleep(900)
                    check(a.window.attributes.screenBrightness == 1f)
                    tap("torch")
                    check(a.window.attributes.screenBrightness == .42f)
                }
                test("$name front light exposes width color and opacity without baking the border into video") {
                    onMain { capture.setFrontLight(width = .085f, tone = "White", opacity = 1f) }
                    tap("torch"); tap("light")
                    click("Warm"); click("Thin")
                    check(prefs.getString("lightTone", "") == "Warm" && abs(prefs.getFloat("lightWidth", 0f) - .045f) < .001)
                    fun opacity(value: Int) {
                        val args = Bundle().apply { putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value.toFloat()) }
                        check(control("Front light opacity", clickable = false).performAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, args))
                        Thread.sleep(150)
                    }
                    opacity(10)
                    render("$name-light-warm-half", true).also { image ->
                        val p = image.getPixel(1, image.height / 2)
                        check(Color.alpha(p) in 126..129 && Color.red(p) >= 250 && abs(Color.green(p) - 209) <= 2 && abs(Color.blue(p) - 158) <= 2)
                        image.recycle()
                    }
                    opacity(0)
                    check(a.window.attributes.screenBrightness == .42f)
                    render("$name-light-zero", true).also { image -> check(Color.alpha(image.getPixel(1, image.height / 2)) == 0); image.recycle() }
                    opacity(20)
                    check(a.window.attributes.screenBrightness == 1f)
                    click("Custom")
                    click("Color #FF0000")
                    check(prefs.getInt("lightCustom",0)==Color.RED)
                    fun screenshot(suffix: String) {
                        Thread.sleep(150)
                        instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                            File(folder,"$name-light-color-$suffix.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
                            image.recycle()
                        }
                    }
                    screenshot("grid")
                    click("Spectrum")
                    val spectrum=android.graphics.Rect().also { control("Color spectrum",clickable=false).getBoundsInScreen(it) }
                    val now=SystemClock.uptimeMillis()
                    for (action in intArrayOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                        val event=MotionEvent.obtain(now,now+30,action,spectrum.exactCenterX(),spectrum.exactCenterY(),0)
                        instrumentation.sendPointerSync(event); event.recycle()
                    }
                    val cyan=prefs.getInt("lightCustom",0)
                    check(Color.red(cyan)<10 && Color.green(cyan)>245 && Color.blue(cyan)>245)
                    screenshot("spectrum")
                    click("Sliders")
                    for ((channel,progress) in listOf("Red" to 125f,"Green" to 500f,"Blue" to 875f)) {
                        val args=Bundle().apply { putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,progress) }
                        check(control(channel,clickable=false,range=true).performAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,args))
                        Thread.sleep(100)
                    }
                    check(prefs.getInt("lightCustom",0)==Color.rgb(32,128,223))
                    screenshot("sliders")
                    click("Close front light color")
                    check(prefs.getInt("lightCustom",0)==Color.rgb(32,128,223))
                    click("Close front light controls")
                    onMain { a.editor.load(Project(name = "Front light source check")); capture.start() }
                    Thread.sleep(1100); check(capture.recording)
                    val frameWidth = if (name == "portrait") 180 else 320
                    val frameHeight = if (name == "portrait") 320 else 180
                    onMain { capture.stop() }
                    val clip = a.editor.project.clips.single()
                    VideoDecoder(clip.path).use { decoder ->
                        val frame = decoder.frame(.5, frameWidth, frameHeight) ?: error("No saved light frame")
                        val lamp = prefs.getInt("lightCustom", Color.rgb(255, 179, 201))
                        // The opaque lamp covers these screen edges. Even a dark or noisy
                        // camera scene must retain source pixels at the encoded edges.
                        val error = (0 until 16).sumOf { index ->
                            val y = (index + 1) * frame.height / 17
                            listOf(frame.getPixel(1, y), frame.getPixel(frame.width - 2, y)).sumOf { pixel ->
                                abs(Color.red(pixel) - Color.red(lamp)) + abs(Color.green(pixel) - Color.green(lamp)) + abs(Color.blue(pixel) - Color.blue(lamp))
                            }
                        } / (32.0 * 3)
                        check(error > 30) { "The saved picture contains the opaque front-light border (error=$error)" }
                        frame.recycle()
                    }
                    tap("torch")
                    check(a.window.attributes.screenBrightness == .42f)
                }
                if (pair == null) continue
                test("$name picture-in-picture swaps, moves and resizes during one recording") {
                    onMain {
                        a.requestedOrientation = orientation
                        a.editor.load(Project(name = "Inset controls check"))
                        capture.switchCamera(pair[0])
                    }
                    Thread.sleep(900)
                    tap("settings")
                    click("Use two cameras")
                    click("Camera settings done")
                    Thread.sleep(1400)
                    tap("dual")
                    tap("pip")
                    tap("dualDone")
                    Thread.sleep(1400)
                    check(capture.concurrentInputSizes().size == 2)
                    val texture =
                        (0 until capture.childCount)
                            .map { capture.getChildAt(it) }
                            .filterIsInstance<TextureView>()
                            .single()
                    val initial = bounds()
                    tap("record")
                    Thread.sleep(600)
                    check(capture.recording)
                    tap("torch")
                    check(a.window.attributes.screenBrightness == 1f)
                    drag(
                        initial.left,
                        initial.centerY(),
                        initial.left - texture.width * .1f,
                        initial.centerY(),
                    )
                    val wider = bounds()
                    check(wider.width() > initial.width() + texture.width * .07f)
                    check(abs(wider.height() - initial.height()) < 2)
                    drag(
                        wider.centerX(),
                        wider.bottom,
                        wider.centerX(),
                        wider.bottom + texture.height * .12f,
                    )
                    val taller = bounds()
                    check(taller.height() > wider.height() + texture.height * .09f)
                    check(abs(taller.width() - wider.width()) < 2)
                    drag(
                        taller.left,
                        taller.bottom,
                        taller.left - texture.width * .05f,
                        taller.bottom + texture.height * .05f,
                    )
                    val larger = bounds()
                    check(larger.width() > taller.width() && larger.height() > taller.height())
                    val x = larger.left + larger.width() * .23f
                    val y = larger.top + larger.height() * .7f
                    drag(x, y, x - texture.width * .15f, y + texture.height * .05f)
                    val moved = bounds()
                    check(moved.left < larger.left - texture.width * .1f)
                    check(abs(moved.width() - larger.width()) < 2)
                    check(!capture.front)
                    tap("pipSwap")
                    check(capture.front)
                    tap("flip")
                    check(!capture.front)
                    onMain { capture.setDualLayout(5) }
                    Thread.sleep(250)
                    val circle = bounds()
                    check(abs(circle.width() - circle.height()) < 2) { "Round inset is oval" }
                    check((capture.width > capture.height) == (name == "landscape"))
                    render("$name-round-inset").recycle()
                    val circleX = circle.left + circle.width() * .25f
                    val circleY = circle.centerY()
                    drag(circleX, circleY, circleX + texture.width * .1f,
                        circleY - texture.height * .05f)
                    check(bounds().left > circle.left + texture.width * .07f)
                    val movedCircle = bounds()
                    drag(movedCircle.right, movedCircle.centerY(),
                        movedCircle.right + texture.width * .05f, movedCircle.centerY())
                    check(bounds().width() > movedCircle.width() + texture.width * .03f)
                    check(abs(bounds().width() - bounds().height()) < 2)
                    onMain { capture.setDualLayout(0) }
                    check(capture.recording && !capture.paused)
                    check(a.window.attributes.screenBrightness == 1f)
                    tap("torch")
                    check(a.window.attributes.screenBrightness == .42f)
                    render("$name-inset-resized").recycle()
                    tap("record")
                    check(a.editor.project.clips.size == 1)
                    val clip = a.editor.project.clips.single()
                    VideoDecoder(clip.path).use {
                        it.frame(clip.duration / 2, 320, 320)?.recycle()
                            ?: error("No saved inset video")
                    }
                    onMain { capture.setDualLayout(4) }
                    Thread.sleep(800)
                }
            }
            test("resized inset bounds and camera swaps reach encoded pixels") {
                encodedInset(a, folder)
            }
        } finally {
            onMain {
                if (capture.recording) capture.stop()
                capture.setTorch(false)
                capture.suspendCamera()
                a.showEditor()
                prefs.edit().apply {
                    for (key in lightKeys) remove(key)
                    for ((key, value) in oldLight) when (value) {
                        is Float -> putFloat(key, value)
                        is Int -> putInt(key, value)
                        is String -> putString(key, value)
                    }
                }.apply()
            }
        }
    }

    private fun encodedInset(a: MainActivity, folder: File) {
        val reader = ImageReader.newInstance(320, 180, PixelFormat.RGBA_8888, 3)
        val thread = HandlerThread("inset-test").apply { start() }
        reader.setOnImageAvailableListener(
            { it.acquireLatestImage()?.close() },
            Handler(thread.looper),
        )
        val file = File(folder, "resized-inset.mp4")
        val recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(a) else MediaRecorder()
        var renderer: CameraComposition? = null
        var started = false
        try {
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setOutputFile(file.path)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            recorder.setVideoSize(320, 180)
            recorder.setVideoFrameRate(30)
            recorder.setVideoEncodingBitRate(1000000)
            recorder.prepare()
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
            val composition = CameraComposition(reader.surface, 320, 180) { failure.set(it) }
            renderer = composition
            val inputs =
                listOf(composition.input(320, 180, 0, false), composition.input(320, 180, 0, true))
            composition.configure(0, false, 0, 320, 180)
            composition.resizePip(1 or 8, -.23f, .32f)
            composition.movePip(-.1f, .1f)
            val r = composition.insetBounds()!!
            check(abs(r.left - .3f) < .001 && abs(r.top - .16f) < .001)
            check(abs(r.width() - .55f) < .001 && abs(r.height() - .64f) < .001)
            composition.record(recorder.surface, 320, 180)
            recorder.start()
            started = true
            for (flip in listOf(false, true)) {
                composition.configure(0, flip, 0, 320, 180)
                repeat(30) {
                    inputs.forEachIndexed { index, surface ->
                        val canvas = surface.lockCanvas(null)
                        canvas.drawColor(if (index == 0) Color.RED else Color.BLUE)
                        surface.unlockCanvasAndPost(canvas)
                    }
                    Thread.sleep(35)
                }
            }
            composition.record(null)
            recorder.stop()
            started = false
            failure.get()?.let { throw it }
            VideoDecoder(file.path).use { decoder ->
                for ((time, flip) in listOf(.5 to false, 1.6 to true)) {
                    val frame = decoder.frame(time, 320, 180) ?: error("No inset frame")
                    try {
                        for ((x, y) in listOf(.1f to .5f, .35f to .2f, .8f to .75f, .9f to .85f)) {
                            val inset = r.contains(x, y)
                            val red = inset == flip
                            val pixel =
                                frame.getPixel(
                                    (x * frame.width).toInt(),
                                    (y * frame.height).toInt(),
                                )
                            check(
                                if (red) Color.red(pixel) > 200 && Color.blue(pixel) < 40
                                else Color.blue(pixel) > 200 && Color.red(pixel) < 40
                            ) {
                                "Wrong inset pixel at $x,$y flip=$flip"
                            }
                        }
                    } finally {
                        frame.recycle()
                    }
                }
            }
        } finally {
            renderer?.record(null)
            if (started) runCatching { recorder.stop() }
            renderer?.close()
            recorder.release()
            reader.close()
            thread.quitSafely()
        }
    }
}
