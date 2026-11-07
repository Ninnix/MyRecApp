package org.yourec.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.os.Bundle
import java.io.File
import kotlin.math.*

class DeviceTests : Instrumentation() {
    private val report = StringBuilder()
    private var suite = ""
    private val testSessionId = uid()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        suite = arguments?.getString("suite") ?: ""
        start()
    }

    private fun test(name: String, block: () -> Unit) {
        block()
        report.append("PASS ").append(name).append('\n')
        sendStatus(0, Bundle().apply { putString("stream", "PASS $name\n") })
    }

    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync {
            try {
                block()
            } catch (e: Throwable) {
                failure = e
            }
        }
        failure?.let { throw it }
    }

    override fun onStart() {
        if (suite == "speech") {
            speech()
            return
        }
        if (suite == "exports") {
            exports()
            return
        }
        if (suite == "parity") {
            parity()
            return
        }
        if (suite == "studio") {
            studio()
            return
        }
        var activity: MainActivity? = null
        var previous: Project? = null
        try {
            val file = File(targetContext.filesDir, "device-fixture.mp4")
            context.assets.open("fixture.mp4").use { src ->
                file.outputStream().use { src.copyTo(it) }
            }
            val clip = ProjectStore.inspect(file)
            val cache = AudioCache(File(targetContext.cacheDir, "test-audio"))
            val project =
                Project(
                    name = "Device test",
                    clips = mutableListOf(clip),
                    width = 640,
                    height = 360,
                )
            activity =
                startActivitySync(
                    Intent(targetContext, MainActivity::class.java)
                        .putExtra("testSession", true)
                        .putExtra("testSessionId", testSessionId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                    as MainActivity
            previous = activity.editor.project.duplicate()
            onMain {
                activity.editor.load(project.duplicate())
                activity.playback.pause()
            }
            test("RNNoise and Qwen C runtimes load") {
                check(Native.rnnoiseAvailable())
                check(Native.asrAvailable())
            }
            test("project round trip preserves independent effect parameters") {
                val store = ProjectStore(File(targetContext.cacheDir, "store-test"))
                val e = Editor(project.duplicate())
                e.applyEffect(
                    Effect(kind = "Text", to = 2.0, values = mutableMapOf("text" to "Hello"))
                )
                e.cursor = 3.0
                e.split()
                store.save(e.project)
                val restored = store.current()
                check(restored.clips.size == 2)
                check(restored.clips[0].effects[0].values["text"] == "Hello")
            }
            test("native Mono filter and dissolve produce expected pixels") {
                val a =
                    Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(Color.RED)
                    }
                Native.image(a, 1, 1f, 0f)
                val pixel = a.getPixel(10, 10)
                check(abs(Color.red(pixel) - Color.green(pixel)) <= 1)
                val b =
                    Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(Color.WHITE)
                    }
                val out = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                Native.blend(a, b, out, 0, 0.5f, 1f)
                check(Color.red(out.getPixel(10, 10)) in 160..170)
                Native.blend(a, b, out, 1, .5f, 1f, 0f, false)
                check(Color.red(out.getPixel(4, 16)) > 240 && Color.red(out.getPixel(28, 16)) < 100)
                Native.blend(a, b, out, 1, .5f, 1f, 90f, false)
                check(Color.red(out.getPixel(16, 4)) > 240 && Color.red(out.getPixel(16, 28)) < 100)
                a.recycle()
                b.recycle()
                out.recycle()
            }
            test("quantization honors palette size, monochrome and zero intensity") {
                val input =
                    IntArray(64 * 64) { Color.rgb(it % 256, (it / 16) % 256, (it * 17) % 256) }
                fun quantize(palette: Int, monochrome: Boolean, amount: Float = 1f): IntArray {
                    val image =
                        Bitmap.createBitmap(input, 64, 64, Bitmap.Config.ARGB_8888)
                            .copy(Bitmap.Config.ARGB_8888, true)
                    try {
                        Native.image(image, 5, amount, 5f, .5f, true, palette, monochrome)
                        return IntArray(input.size).also {
                            image.getPixels(it, 0, 64, 0, 0, 64, 64)
                        }
                    } finally {
                        image.recycle()
                    }
                }
                check(quantize(0, false).toSet().size in 2..5)
                check(quantize(1, false).toSet().size in 2..5)
                check(!quantize(1, false).contentEquals(quantize(2, false)))
                check(
                    quantize(0, true).all {
                        Color.red(it) == Color.green(it) && Color.green(it) == Color.blue(it)
                    }
                )
                check(quantize(1, false, 0f).contentEquals(input))
            }
            test("color isolation selects hue and preserves zero intensity") {
                val source =
                    Bitmap.createBitmap(
                        intArrayOf(Color.RED, Color.BLUE, Color.GREEN),
                        3,
                        1,
                        Bitmap.Config.ARGB_8888,
                    )
                val image = source.copy(Bitmap.Config.ARGB_8888, true)
                try {
                    Native.isolate(image, 0f, 0f, 30f, .15f, null)
                    check(image.sameAs(source))
                    Effects.correct(image, Effect(kind = "Color Isolation"), 1f)
                    check(image.getPixel(0, 0) == Color.RED)
                    for (x in 1..2) {
                        val p = image.getPixel(x, 0)
                        check(Color.red(p) == Color.green(p) && Color.green(p) == Color.blue(p))
                    }
                    image.setPixel(1, 0, Color.BLUE)
                    Native.isolate(image, 1f, 240f, 15f, .15f, null)
                    check(image.getPixel(1, 0) == Color.BLUE)
                } finally {
                    source.recycle()
                    image.recycle()
                }
            }
            test("person segmentation keeps the subject in color against a monochrome background") {
                val source =
                    context.assets.open("person.png").use { BitmapFactory.decodeStream(it) }
                val image = source.copy(Bitmap.Config.ARGB_8888, true)
                try {
                    val started = System.nanoTime()
                    val mask = Person.mask(source)
                    val seconds = (System.nanoTime() - started) / 1e9
                    check(mask.size == 192 * 192 && mask.all { it.isFinite() && it in 0f..1f })
                    fun probability(x: Int, y: Int) =
                        mask[y * 192 / source.height * 192 + x * 192 / source.width]
                    check(probability(220, 70) > .9f) { "Subject ${probability(220, 70)}" }
                    check(probability(90, 140) < .1f) { "Background ${probability(90, 140)}" }
                    Effects.correct(
                        image,
                        Effect(kind = "Color Isolation", values = mutableMapOf("mode" to "Person")),
                        1f,
                        mask,
                    )
                    val background = image.getPixel(90, 140)
                    check(
                        Color.red(background) == Color.green(background) &&
                            Color.green(background) == Color.blue(background)
                    )
                    check(image.getPixel(220, 70) == source.getPixel(220, 70))
                    check(!image.sameAs(source))
                    check(seconds < 5) { "Person inference took $seconds seconds" }
                } finally {
                    source.recycle()
                    image.recycle()
                }
            }
            test("audio decoding yields six seconds at 48k stereo") {
                val pcm = cache.get(file.absolutePath)
                val seconds = pcm.length() / 8 / 48000.0
                check(abs(seconds - 6) < 0.1) { "Decoded duration $seconds" }
                val wave = cache.waveform(file.absolutePath)
                check(wave.max() > 0.05f)
            }
            test("color correction is neutral by default and adjusts exposure and saturation") {
                val image =
                    Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(Color.rgb(50, 80, 120))
                    }
                try {
                    Native.colorCorrect(image, 1f, 0f, 1f, 1f, 0f, 0f)
                    check(image.getPixel(10, 10) == Color.rgb(50, 80, 120))
                    Native.colorCorrect(image, 1f, 1f, 1f, 0f, 0f, 0f)
                    val pixel = image.getPixel(10, 10)
                    check(
                        Color.red(pixel) == Color.green(pixel) &&
                            Color.green(pixel) == Color.blue(pixel)
                    )
                    check(Color.red(pixel) > 80)
                } finally {
                    image.recycle()
                }
            }
            test(
                "night cleanup reduces dark noise and temporal rejection protects moving subjects"
            ) {
                val random = java.util.Random(42)
                val pixels =
                    IntArray(64 * 64) {
                        val value = 48 + random.nextInt(25)
                        Color.rgb(value, value, value)
                    }
                val original =
                    Bitmap.createBitmap(pixels, 64, 64, Bitmap.Config.ARGB_8888)
                        .copy(Bitmap.Config.ARGB_8888, true)
                val clean = original.copy(Bitmap.Config.ARGB_8888, true)
                val previous = original.copy(Bitmap.Config.ARGB_8888, true)
                try {
                    Native.night(original, 1f, 0f)
                    Native.night(clean, 1f, 1f)
                    fun variance(image: Bitmap): Double {
                        val values =
                            (4 until 60).flatMap { y ->
                                (4 until 60).map { x -> Color.red(image.getPixel(x, y)).toDouble() }
                            }
                        val mean = values.average()
                        return values.sumOf { (it - mean) * (it - mean) } / values.size
                    }
                    check(variance(clean) < variance(original) * .5)
                    clean.eraseColor(Color.rgb(60, 60, 60))
                    previous.eraseColor(Color.rgb(65, 65, 65))
                    clean.setPixel(30, 30, Color.WHITE)
                    Native.temporal(clean, previous, floatArrayOf(1f, 0f, 0f, 0f, 32f), 1f)
                    check(clean.getPixel(30, 30) == Color.WHITE)
                    check(Color.red(clean.getPixel(20, 20)) in 61..64)
                } finally {
                    original.recycle()
                    clean.recycle()
                    previous.recycle()
                }
            }
            fun level(p: Project): Double {
                AudioMixer(p, cache).use { m ->
                    m.prepare()
                    m.seek(1.0)
                    val pcm = m.read(4096)
                    return sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size)
                }
            }
            test("gain and mute change real mixed samples") {
                val original = level(project)
                val p = project.duplicate()
                p.clips[0].gainDb = -6f
                val reduced = level(p)
                check(reduced / original in 0.48..0.52)
                p.clips[0].gainDb = -60f
                check(level(p) < original * 0.002)
            }
            test("undercranking speeds up decoded audio by the same factor as video") {
                fun crossings(p: Project): Int =
                    AudioMixer(p, cache).use { mixer ->
                        mixer.prepare()
                        mixer.seek(1.0)
                        val audio = mixer.read(24000)
                        (2 until audio.size step 2).count { audio[it - 2] <= 0 && audio[it] > 0 }
                    }
                val normal = crossings(project)
                val faster =
                    project.duplicate().apply {
                        clips.single().effects.add(Effect(kind = "Undercranking"))
                    }
                val ratio = crossings(faster).toDouble() / normal
                check(normal > 50 && ratio in 1.23..1.27) { "Audio rate ratio $ratio" }
                check(abs(faster.duration - 4.8) < .01)
            }
            test("limiter bounds peaks and bypass preserves samples") {
                val pcm = FloatArray(9600) { if (it % 2 == 0) 1.5f else -.5f }
                val state = Native.audioCreate(48000)
                try {
                    val bypass = pcm.copyOf()
                    Native.audioProcess(state, bypass, 4800, 1f, 0f, 0f, 1800f, 0f, 0f, 0f)
                    check(bypass.contentEquals(pcm))
                    Native.audioProcess(state, pcm, 4800, 1f, 0f, 0f, 1800f, 0f, 0f, 0f, 1f)
                    check(pcm.all { abs(it) <= .951f })
                    check(abs(pcm.last() / pcm[pcm.lastIndex - 1] + 1f / 3) < .001f)
                } finally {
                    Native.audioDestroy(state)
                }
            }
            test("sequential hardware decoder advances and seeks") {
                VideoDecoder(file.absolutePath).use { decoder ->
                    val first = decoder.frame(0.5, 320, 180) ?: error("No decoded frame")
                    val second = decoder.frame(3.0, 320, 180) ?: error("No decoded frame")
                    val backward = decoder.frame(0.5, 320, 180) ?: error("No frame after seek")
                    fun pixels(b: Bitmap) =
                        IntArray(b.width * b.height).also {
                            b.getPixels(it, 0, b.width, 0, 0, b.width, b.height)
                        }
                    val f = pixels(first)
                    val b = pixels(backward)
                    check(!f.contentEquals(pixels(second)))
                    check(f.indices.count { f[it] == b[it] } > f.size * 0.9)
                    first.recycle()
                    second.recycle()
                    backward.recycle()
                }
            }
            test("robust native motion recovers rotation and translation") {
                val base =
                    android.graphics.BitmapFactory.decodeStream(
                        context.assets.open("stabilizer.png")
                    )
                val moved = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
                val matrix =
                    Matrix().apply {
                        setRotate(1.3f, 160f, 90f)
                        postTranslate(3.2f, -2.4f)
                    }
                Canvas(moved).drawBitmap(base, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
                val actual = Native.similarity(base, moved)
                val expected = FloatArray(9)
                matrix.getValues(expected)
                check(actual[4] >= 15) { "Only ${actual[4]} inliers" }
                check(
                    abs(actual[0] - expected[0]) < 0.005f && abs(actual[1] - expected[3]) < 0.005f
                )
                check(hypot(actual[2] - expected[2], actual[3] - expected[5]) < 0.5f) {
                    actual.contentToString()
                }
                val begin = System.nanoTime()
                repeat(30) { Native.similarity(base, moved) }
                sendStatus(
                    0,
                    Bundle().apply {
                        putString(
                            "stream",
                            "Motion analysis: ${(System.nanoTime()-begin)/30/1e6} ms/pair\n",
                        )
                    },
                )
                base.recycle()
                moved.recycle()
            }
            test("stabilization prepares a trajectory and renders valid frames") {
                val source = File(targetContext.filesDir, "shaky-test.mp4")
                context.assets.open("shaky.mp4").use { input ->
                    source.outputStream().use { input.copyTo(it) }
                }
                val stabilized =
                    Project(
                        clips = mutableListOf(ProjectStore.inspect(source)),
                        width = 320,
                        height = 180,
                    )
                stabilized.clips[0]
                    .effects
                    .add(Effect(kind = "Stabilize", values = mutableMapOf("strength" to "1")))
                Frames().use { frames ->
                    val image = frames.render(stabilized, 2.0, 320, 180)
                    check(image.width == 320 && image.height == 180)
                    val center = image.getPixel(160, 90)
                    check(Color.alpha(center) == 255)
                    image.recycle()
                }
            }
            test("upper video tracks remain visible throughout an overlapping transition") {
                val e = Editor(project.duplicate())
                e.cursor = 3.0
                e.split()
                e.putTransition(Transition(at = 3.0, duration = .5))
                val top =
                    clip.duplicate(true).apply {
                        track = 2
                        effects.add(
                            Effect(
                                kind = "Placement",
                                values =
                                    mutableMapOf("x" to ".5", "y" to ".5", "w" to ".5", "h" to ".5"),
                            )
                        )
                        effects.add(Effect(kind = "Noir"))
                    }
                e.project.clips.add(top)
                Frames().use { frames ->
                    val actual = frames.render(e.project, 2.8, 320, 180)
                    val expected = frames.render(Project(clips = mutableListOf(top)), 2.8, 320, 180)
                    try {
                        for (y in 100 until 175 step 10) for (x in 170 until 315 step 10) check(
                            actual.getPixel(x, y) == expected.getPixel(x, y)
                        )
                    } finally {
                        actual.recycle()
                        expected.recycle()
                    }
                }
            }
            test("simultaneous transitions compose independently on separate tracks") {
                val e = Editor(project.duplicate())
                e.cursor = 3.0
                e.split()
                e.putTransition(Transition(at = 3.0, duration = .5))
                val base = e.project.duplicate()
                val top =
                    e.project.clips.map {
                        it.duplicate(true).apply {
                            track = 1
                            effects.add(
                                Effect(
                                    kind = "Placement",
                                    values =
                                        mutableMapOf(
                                            "x" to ".5",
                                            "y" to ".5",
                                            "w" to ".5",
                                            "h" to ".5",
                                        ),
                                )
                            )
                            effects.add(Effect(kind = "Noir"))
                        }
                    }
                val transition =
                    e.project.transitions
                        .single()
                        .copy(
                            id = uid(),
                            kind = "Wipe",
                            angle = 90f,
                            leftId = top.first().id,
                            rightId = top.last().id,
                        )
                e.project.clips.addAll(top)
                e.project.transitions.add(transition)
                val upper =
                    Project(clips = top.toMutableList(), transitions = mutableListOf(transition))
                Frames().use { frames ->
                    for (time in listOf(2.6, 2.9)) {
                        val actual = frames.render(e.project, time, 320, 180)
                        val lower = frames.render(base, time, 320, 180)
                        val higher = frames.render(upper, time, 320, 180)
                        try {
                            for (y in 10 until 80 step 10) for (x in 10 until 150 step 10) check(
                                actual.getPixel(x, y) == lower.getPixel(x, y)
                            )
                            for (y in 100 until 175 step 10) for (x in 170 until 315 step 10) check(
                                actual.getPixel(x, y) == higher.getPixel(x, y)
                            )
                        } finally {
                            actual.recycle()
                            lower.recycle()
                            higher.recycle()
                        }
                    }
                }
            }
            test("splitting and inserting inside a blend preserves rendered frames and audio") {
                val edit = Editor(project.duplicate())
                edit.cursor = 3.0
                edit.split()
                edit.putTransition(Transition(at = 3.0, duration = .8))
                val before = edit.project.duplicate()
                val times = listOf(2.3, 2.5, 2.7, 2.9)
                Frames().use { frames ->
                    val images = times.associateWith { frames.render(before, it, 320, 180) }
                    try {
                        edit.cursor = 2.6
                        edit.split()
                        for (time in times) {
                            val actual = frames.render(edit.project, time, 320, 180)
                            check(actual.sameAs(images.getValue(time))) {
                                "Split changed frame at $time"
                            }
                            actual.recycle()
                            fun audio(p: Project) =
                                AudioMixer(p, cache).use {
                                    it.prepare()
                                    it.seek(time)
                                    it.read(1024)
                                }
                            val a = audio(before)
                            val b = audio(edit.project)
                            check(a.indices.maxOf { abs(a[it] - b[it]) } < .00001f)
                        }
                        edit.cursor = 2.6
                        edit.insert(clip.duplicate(true).apply { sourceOut = .6 })
                        val store = ProjectStore(File(targetContext.cacheDir, "blend-fragments"))
                        store.save(edit.project)
                        val restored = store.current()
                        check(restored == edit.project)
                        for (time in times) {
                            val actual =
                                frames.render(
                                    restored,
                                    if (time < 2.6) time else time + .6,
                                    320,
                                    180,
                                )
                            check(actual.sameAs(images.getValue(time))) {
                                "Insertion changed frame at $time"
                            }
                            actual.recycle()
                        }
                    } finally {
                        images.values.forEach { it.recycle() }
                    }
                }
            }
            test("range effect and multitrack composition render") {
                val e = Editor(project.duplicate())
                e.range = 1.0..3.0
                e.applyEffect(Effect(kind = "Color LUTs", values = mutableMapOf("lut" to "Mono")))
                Frames().use { frames ->
                    val color = frames.render(e.project, 0.5, 320, 180)
                    val mono = frames.render(e.project, 2.0, 320, 180)
                    val pixel = mono.getPixel(30, 30)
                    check(abs(Color.red(pixel) - Color.green(pixel)) < 3)
                    check(color.getPixel(30, 30) != pixel)
                    color.recycle()
                    mono.recycle()
                }
            }
            val edited = project.duplicate()
            edited.clips[0]
                .effects
                .add(
                    Effect(
                        kind = "Text",
                        from = 0.5,
                        to = 5.5,
                        values = mutableMapOf("text" to "YouRec test", "x" to "0.1", "y" to "0.2"),
                    )
                )
            val exported = File(targetContext.getExternalFilesDir(null), "test-export.mp4")
            test("H.264 AAC export can be decoded with the expected duration") {
                val began = android.os.SystemClock.elapsedRealtime()
                Export(cache).run(edited, exported, 640, 360) {}
                sendStatus(
                    0,
                    Bundle().apply {
                        putString(
                            "stream",
                            "Export time: ${(android.os.SystemClock.elapsedRealtime()-began)/1000.0} s\n",
                        )
                    },
                )
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(exported.absolutePath)
                    check(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes")
                    check(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes")
                    check(
                        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) == "640"
                    )
                    val duration =
                        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!
                            .toDouble() / 1000
                    check(abs(duration - 6) < 0.15) { "Exported duration $duration" }
                    val frame = r.getFrameAtTime(2_000_000, MediaMetadataRetriever.OPTION_CLOSEST)
                    check(frame != null)
                    frame.recycle()
                    check(
                        level(Project(clips = mutableListOf(ProjectStore.inspect(exported)))) > 0.01
                    )
                } finally {
                    r.release()
                }
            }
            onMain {
                activity.panels.close()
                activity.showEditor()
                activity.editor.project = edited
                activity.editor.cursor = 2.0
                activity.editor.onChange()
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            Thread.sleep(1500)
            test("portrait editor screenshot") { screenshot("editor-portrait", activity) }
            onMain { activity.panels.tools() }
            Thread.sleep(400)
            screenshot("tools-portrait", activity)
            onMain {
                activity.panels.close()
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            Thread.sleep(1000)
            test("landscape editor screenshot") { screenshot("editor-landscape", activity) }
            onMain { activity.panels.tools() }
            Thread.sleep(400)
            screenshot("tools-landscape", activity)
            onMain {
                activity.panels.close()
                activity.showCapture()
            }
            Thread.sleep(1800)
            val camera =
                (0 until activity.root.childCount)
                    .map { activity.root.getChildAt(it) }
                    .filterIsInstance<Capture>()
                    .single()
            screenshot("camera-idle-landscape", activity)
            onMain {
                val preview = camera.getChildAt(0)
                check(preview.left > camera.width * .1f && preview.right < camera.width * .9f) {
                    "Camera preview ${preview.left}..${preview.right} in ${camera.width}"
                }
            }
            test("Camera2 switches lenses and pauses or resumes through camera suspension") {
                val before = activity.editor.project.clips.size
                onMain { camera.start() }
                Thread.sleep(1600)
                check(camera.recording)
                onMain { camera.pause() }
                check(camera.paused)
                val pausedAt = android.os.SystemClock.elapsedRealtime()
                Thread.sleep(1000)
                onMain { camera.pause() }
                check(!camera.paused)
                val pauseLength = android.os.SystemClock.elapsedRealtime() - pausedAt
                Thread.sleep(1400)
                val manager =
                    activity.getSystemService(android.hardware.camera2.CameraManager::class.java)
                val cameraIds = manager.cameraIdList
                val front =
                    cameraIds.first {
                        manager
                            .getCameraCharacteristics(it)
                            .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                            android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
                    }
                val rear =
                    cameraIds.first {
                        manager
                            .getCameraCharacteristics(it)
                            .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                            android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
                    }
                onMain { camera.switchCamera(front) }
                Thread.sleep(1300)
                check(camera.recording && !camera.paused) {
                    "Front camera switch interrupted the recording"
                }
                onMain { camera.switchCamera(rear) }
                Thread.sleep(1300)
                check(camera.recording && !camera.paused) {
                    "Rear camera switch interrupted the recording"
                }
                onMain { camera.suspendCamera() }
                check(camera.recording && camera.paused)
                Thread.sleep(400)
                onMain { camera.resumeCamera() }
                Thread.sleep(900)
                onMain { camera.pause() }
                check(camera.recording && !camera.paused)
                Thread.sleep(700)
                screenshot("camera-landscape", activity)
                var activeTime = 0.0
                onMain {
                    activeTime = camera.elapsedSeconds()
                    camera.stop()
                }
                Thread.sleep(1000)
                check(activity.editor.project.clips.size == before + 1)
                val recorded = activity.editor.project.clips.last()
                check(abs(recorded.duration - activeTime) < 0.3) {
                    "Recording duration ${recorded.duration}, active timer $activeTime, pause $pauseLength"
                }
                check(File(recorded.path).length() > 1000)
            }
            fun tapCameraControl(id: String) {
                onMain {
                    val bounds = camera.controlBounds(id) ?: error("Missing camera control $id")
                    val now = android.os.SystemClock.uptimeMillis()
                    for (action in
                        listOf(
                            android.view.MotionEvent.ACTION_DOWN,
                            android.view.MotionEvent.ACTION_UP,
                        )) {
                        val event =
                            android.view.MotionEvent.obtain(
                                now,
                                now + 30,
                                action,
                                bounds.centerX(),
                                bounds.centerY(),
                                0,
                            )
                        camera.dispatchTouchEvent(event)
                        event.recycle()
                    }
                }
                Thread.sleep(200)
            }
            test("portrait camera places scissors left of Record and AF alone controls focus") {
                onMain { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                Thread.sleep(1000)
                onMain {
                    val edit = camera.controlBounds("edit")!!
                    val record = camera.controlBounds("record")!!
                    val projects = camera.controlBounds("projects")!!
                    check(
                        edit.centerX() < record.centerX() && record.centerX() < projects.centerX()
                    )
                    check(abs(edit.centerY() - record.centerY()) < 2)
                    check(abs(projects.centerY() - record.centerY()) < 2)
                    check(camera.controlBounds("lock") == null)
                }
                tapCameraControl("focus")
                check(camera.focusIsLocked)
                val focusDeadline = android.os.SystemClock.elapsedRealtime() + 3000
                val lockedStates =
                    listOf(
                        android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
                        android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED,
                    )
                while (
                    camera.focusState !in lockedStates &&
                        android.os.SystemClock.elapsedRealtime() < focusDeadline
                ) Thread.sleep(50)
                check(camera.focusState in lockedStates) { "Camera AF state ${camera.focusState}" }
                screenshot("camera-focus-portrait", activity)
                tapCameraControl("focus")
                check(!camera.focusIsLocked)
                tapCameraControl("projects")
                check(activity.panels.isOpen && !camera.focusIsLocked)
                onMain {
                    activity.panels.close()
                    activity.showCapture()
                }
                Thread.sleep(1000)
                tapCameraControl("edit")
                check(camera.visibility == android.view.View.GONE)
                onMain { activity.showCapture() }
                Thread.sleep(1000)
            }
            test("microphone choices have unique physical route names") {
                val inputs = camera.microphoneDevices().map { MicrophoneInput(it) }
                val labels = MicrophoneInput.labels(inputs)
                check(inputs.isNotEmpty() && inputs.all { it.usable })
                check(labels.distinct().size == labels.size)
                check(inputs.map { it.key }.distinct().size == inputs.size)
                sendStatus(
                    0,
                    Bundle().apply {
                        putString("stream", "Microphones: ${labels.joinToString()}\n")
                    },
                )
                tapCameraControl("mic")
                screenshot("microphones-portrait", activity, true)
                for (label in labels) check(
                    uiAutomation.rootInActiveWindow
                        ?.findAccessibilityNodeInfosByText(label)
                        ?.isNotEmpty() == true
                ) {
                    "Missing microphone label $label"
                }
                onMain { activity.onBackPressed() }
            }
            test("portrait camera records correctly oriented playable video") {
                onMain { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                Thread.sleep(1000)
                screenshot("camera-idle-portrait", activity)
                val before = activity.editor.project.clips.size
                onMain { camera.start() }
                Thread.sleep(1600)
                check(camera.recording)
                screenshot("camera-portrait", activity)
                onMain { camera.stop() }
                Thread.sleep(600)
                check(activity.editor.project.clips.size == before + 1)
                val recorded = activity.editor.project.clips.last()
                val reader = MediaMetadataRetriever()
                try {
                    reader.setDataSource(recorded.path)
                    val rotation =
                        reader
                            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)!!
                            .toInt()
                    check(rotation % 180 == 90) { "Portrait rotation $rotation" }
                    val frame =
                        reader.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST)
                            ?: error("No portrait frame")
                    check(frame.height > frame.width) {
                        "Portrait decoded ${frame.width}x${frame.height}"
                    }
                    frame.recycle()
                } finally {
                    reader.release()
                }
            }
            onMain {
                camera.suspendCamera()
                activity.showEditor()
            }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", report.toString()) })
        } catch (e: Throwable) {
            finish(
                Activity.RESULT_CANCELED,
                Bundle().apply {
                    putString("stream", report.toString() + "FAIL " + e.stackTraceToString())
                },
            )
        } finally {
            if (activity != null && previous != null)
                onMain {
                    activity.editor.load(previous)
                    activity.editor.cursor = 0.0
                    activity.editor.clearSelection()
                    activity.editor.onChange()
                }
        }
    }

    private fun parity() {
        var activity: MainActivity? = null
        var original: Project? = null
        try {
            val source = File(targetContext.filesDir, "parity-fixture.mp4")
            context.assets.open("fixture.mp4").use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            val a =
                startActivitySync(
                    Intent(targetContext, MainActivity::class.java)
                        .putExtra("testSession", true)
                        .putExtra("testSessionId", testSessionId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                    as MainActivity
            activity = a
            original = a.editor.project.duplicate()
            waitForIdleSync()
            val readyBy = android.os.SystemClock.elapsedRealtime() + 5000
            var ready = false
            while (!ready && android.os.SystemClock.elapsedRealtime() < readyBy) {
                onMain { ready = a.hasWindowFocus() && a.root.width > 0 && a.root.height > 0 }
                if (!ready) Thread.sleep(100)
            }
            check(ready) { "The editor window is not ready" }
            val project =
                Project(
                    name = "30 Oct · 15:35",
                    width = 640,
                    height = 360,
                    clips = mutableListOf(ProjectStore.inspect(source)),
                )
            test("new image overlay retains its original timing after preview scrubbing") {
                val image = File(targetContext.filesDir, "overlay-fixture.png")
                context.assets.open("stabilizer.png").use { input ->
                    image.outputStream().use { input.copyTo(it) }
                }
                onMain {
                    a.editor.load(project.duplicate())
                    a.editor.cursor = 1.0
                    a.panels.effect(
                        "Image",
                        Effect(
                            kind = "Image",
                            to = 2.0,
                            values = mutableMapOf("path" to image.absolutePath),
                        ),
                    )
                }
                Thread.sleep(200)
                val preview =
                    a.playback.previewProject!!.clips.single().effects.single { it.kind == "Image" }
                check(abs(preview.from - 1) < .001 && abs(preview.to - 3) < .001)
                onMain { a.playback.seek(2.5) }
                Thread.sleep(150)
                onMain {
                    findView(a.root) {
                            it is android.widget.TextView && it.text.toString() == "Apply"
                        }!!
                        .performClick()
                }
                val applied = a.editor.project.clips.single().effects.single { it.kind == "Image" }
                check(applied.values["path"] == image.absolutePath)
                check(abs(applied.from - 1) < .001 && abs(applied.to - 3) < .001)
            }
            test("effect slider touch positions match their displayed scale") {
                onMain {
                    a.editor.load(project.duplicate())
                    a.editor.cursor = 2.0
                    a.panels.videoEffects(Effect(kind = "Quantization"))
                }
                Thread.sleep(250)
                for (position in listOf(.25f, .75f)) {
                    onMain {
                        val bar =
                            findView(a.root) { it.contentDescription == "Intensity" }
                                as android.widget.SeekBar
                        val unit =
                            if (a.root.width > a.root.height)
                                min(a.root.width / 640f, a.root.height / 294f)
                            else min(a.root.width / 294f, a.root.height / 640f)
                        val half = 13f * unit
                        val x = half + (bar.width - half * 2) * position
                        val now = android.os.SystemClock.uptimeMillis()
                        for (action in
                            listOf(
                                android.view.MotionEvent.ACTION_DOWN,
                                android.view.MotionEvent.ACTION_UP,
                            )) {
                            val event =
                                android.view.MotionEvent.obtain(
                                    now,
                                    now + 30,
                                    action,
                                    x,
                                    bar.height / 2f,
                                    0,
                                )
                            bar.dispatchTouchEvent(event)
                            event.recycle()
                        }
                    }
                    Thread.sleep(150)
                    val strength =
                        a.playback.previewProject!!
                            .clips
                            .single()
                            .effects
                            .single { it.kind == "Quantization" }
                            .number("strength")
                    check(abs(strength - position) < .005f) {
                        "Slider $position produced $strength"
                    }
                }
                onMain { a.panels.close() }
                check(a.editor.project == project)
            }
            test("volume removal resets the selected section as one undoable edit") {
                onMain {
                    a.editor.load(project.duplicate())
                    a.editor.cursor = 2.0
                    a.editor.setGain(-6f)
                    a.panels.volume()
                }
                Thread.sleep(250)
                onMain {
                    findView(a.root) { it.contentDescription == "Increase gain by 0.5 dB" }!!
                        .performClick()
                    check(a.editor.project.clips.single().gainDb == -6f)
                }
                Thread.sleep(150)
                onMain {
                    check(a.playback.previewProject!!.clips.single().gainDb == -5.5f)
                    findView(a.root) {
                            it is android.widget.TextView && it.text.toString() == "Remove"
                        }!!
                        .performClick()
                    check(!a.panels.isOpen)
                    check(a.editor.project.clips.single().gainDb == 0f)
                    a.editor.undo()
                    check(a.editor.project.clips.single().gainDb == -6f)
                    a.editor.load(project.duplicate())
                }
            }
            test("placement layer edits cancel and each clip frames independently") {
                val overlap = project.duplicate()
                overlap.clips.add(overlap.clips.single().duplicate(true).apply { track = 1 })
                onMain {
                    a.editor.load(overlap.duplicate())
                    a.editor.cursor = 2.0
                    a.panels.effect("Placement")
                }
                Thread.sleep(250)
                onMain {
                    findView(a.root) {
                            it is android.widget.TextView && it.text.toString() == "Bring to front"
                        }!!
                        .performClick()
                }
                Thread.sleep(150)
                check(a.editor.project == overlap)
                onMain { a.panels.close() }
                check(a.editor.project == overlap)
                onMain { a.panels.effect("Placement") }
                Thread.sleep(250)
                onMain {
                    val preview = findView(a.root) { it.javaClass.simpleName == "Preview" }!!
                    check(preview.width > 0 && preview.height > 0) {
                        "Preview size ${preview.width}x${preview.height}"
                    }
                    val now = android.os.SystemClock.uptimeMillis()
                    for ((action, dx) in
                        listOf(
                            android.view.MotionEvent.ACTION_DOWN to 0f,
                            android.view.MotionEvent.ACTION_MOVE to .1f,
                            android.view.MotionEvent.ACTION_UP to .1f,
                        )) {
                        val event =
                            android.view.MotionEvent.obtain(
                                now,
                                now + 30,
                                action,
                                preview.width * (.5f + dx),
                                preview.height * .5f,
                                0,
                            )
                        preview.dispatchTouchEvent(event)
                        event.recycle()
                    }
                    findView(a.root) {
                            it is android.widget.TextView && it.text.toString() == "Apply"
                        }!!
                        .performClick()
                }
                val positions =
                    a.editor.project.clips.map {
                        it.effects.single { it.kind == "Placement" }.number("x")
                    }
                check(abs(positions[0]) < .001 && positions[1] > .07f) {
                    "Layer positions $positions"
                }
                onMain { a.editor.undo() }
                check(a.editor.project == overlap)
            }
            test("transition draft and Apply use the same shortened timeline") {
                onMain {
                    a.editor.load(project.duplicate())
                    a.editor.cursor = 3.0
                    a.editor.split()
                    a.panels.transition()
                }
                Thread.sleep(250)
                check(abs(a.playback.previewProject!!.duration - 5.5) < .001)
                onMain {
                    findView(a.root) {
                            it is android.widget.TextView && it.text.toString() == "Apply"
                        }!!
                        .performClick()
                }
                check(abs(a.editor.project.duration - 5.5) < .001)
                check(a.editor.project.transitions.single().leftId != null)
                onMain { a.editor.undo() }
                check(
                    abs(a.editor.project.duration - 6) < .001 &&
                        a.editor.project.transitions.isEmpty()
                )
            }
            test("text draft survives both rotations and commits at its original insertion time") {
                onMain {
                    a.editor.load(project.duplicate())
                    a.editor.cursor = 1.0
                    a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
                Thread.sleep(600)
                onMain {
                    a.panels.effect("Text")
                    (findView(a.root) { it is android.widget.EditText } as android.widget.EditText)
                        .setText("Rotation draft")
                    a.playback.seek(2.0)
                    a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
                Thread.sleep(800)
                onMain {
                    check(a.panels.isOpen)
                    check(
                        (findView(a.root) { it is android.widget.EditText }
                                as android.widget.EditText)
                            .text
                            .toString() == "Rotation draft"
                    )
                    a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
                Thread.sleep(800)
                onMain {
                    check(a.panels.isOpen)
                    findView(a.root) {
                            it is android.widget.TextView && it.text.toString() == "Apply"
                        }!!
                        .performClick()
                    val text = a.editor.project.clips.single().effects.single { it.kind == "Text" }
                    check(text.values["text"] == "Rotation draft")
                    check(abs(text.from - 1.0) < .001) { "Text moved to ${text.from}" }
                    a.editor.undo()
                    check(a.editor.project == project)
                }
            }
            test("crop shape previews without committing and Apply undoes as one edit") {
                fun square() {
                    onMain {
                        a.panels.effect("Crop")
                        findView(a.root) {
                                it is android.widget.TextView &&
                                    it.text.toString().startsWith("Video shape")
                            }!!
                            .let { (it.parent as android.view.View).performClick() }
                    }
                    Thread.sleep(150)
                    var item: android.view.accessibility.AccessibilityNodeInfo? = null
                    val deadline = android.os.SystemClock.elapsedRealtime() + 3000
                    while (item == null && android.os.SystemClock.elapsedRealtime() < deadline) {
                        item =
                            uiAutomation.rootInActiveWindow
                                ?.findAccessibilityNodeInfosByText("1:1")
                                ?.firstOrNull()
                        if (item == null) Thread.sleep(100)
                    }
                    check(item != null) { "Missing square video shape" }
                    check(
                        item.performAction(
                            android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK
                        )
                    )
                    Thread.sleep(250)
                }
                onMain {
                    a.editor.load(project.duplicate())
                    a.editor.cursor = 2.0
                }
                square()
                check(a.playback.previewProject!!.let { it.width == it.height })
                check(a.editor.project == project)
                onMain { a.panels.close() }
                check(a.editor.project == project)
                square()
                onMain {
                    findView(a.root) {
                            it is android.widget.TextView && it.text.toString() == "Apply"
                        }!!
                        .performClick()
                    check(a.editor.project.width == a.editor.project.height)
                    check(a.editor.project.clips.single().effects.single().kind == "Crop")
                    a.editor.undo()
                    check(a.editor.project == project)
                }
            }
            test("effect card bypass and swipe removal remain drafts until Apply") {
                val current =
                    project.duplicate().apply {
                        clips
                            .single()
                            .effects
                            .addAll(
                                listOf(Effect(kind = "Quantization"), Effect(kind = "Film grain"))
                            )
                    }
                onMain {
                    a.editor.load(current.duplicate())
                    a.editor.cursor = 2.0
                    a.panels.videoEffects()
                    findView(a.root) { it.contentDescription == "Quantization enabled" }!!
                        .performClick()
                }
                Thread.sleep(200)
                check(!a.playback.previewProject!!.clips.single().effects.first().enabled)
                check(a.editor.project == current)
                onMain {
                    val card = findView(a.root) { it is EffectCard }!!
                    val now = android.os.SystemClock.uptimeMillis()
                    for ((action, x) in
                        listOf(
                            android.view.MotionEvent.ACTION_DOWN to .85f,
                            android.view.MotionEvent.ACTION_MOVE to .15f,
                            android.view.MotionEvent.ACTION_UP to .15f,
                        )) {
                        val event =
                            android.view.MotionEvent.obtain(
                                now,
                                now + 20,
                                action,
                                card.width * x,
                                card.height * .5f,
                                0,
                            )
                        card.dispatchTouchEvent(event)
                        event.recycle()
                    }
                }
                Thread.sleep(200)
                check(
                    a.playback.previewProject!!.clips.single().effects.map { it.kind } ==
                        listOf("Film grain")
                )
                onMain {
                    findView(a.root) {
                            it is android.widget.TextView && it.text.toString() == "Apply"
                        }!!
                        .performClick()
                    check(a.editor.project.clips.single().effects.single().kind == "Film grain")
                    a.editor.undo()
                    check(a.editor.project == current)
                }
            }
            for ((orientation, label) in
                listOf(
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to "landscape",
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to "portrait",
                )) {
                onMain {
                    a.panels.close()
                    a.showEditor()
                    a.requestedOrientation = orientation
                    a.editor.project = project.duplicate()
                    a.editor.cursor = 2.0
                    a.editor.clearSelection()
                    a.editor.onChange()
                }
                Thread.sleep(1000)
                test("$label centered timeline at start") {
                    onMain { a.playback.seek(0.0) }
                    Thread.sleep(250)
                    screenshot("$label-editor-start", a)
                }
                onMain { a.playback.seek(2.0) }
                Thread.sleep(250)
                val panels =
                    listOf<Pair<String, () -> Unit>>(
                        "projects" to { a.panels.projects() },
                        "tools" to { a.panels.tools() },
                        "text" to
                            {
                                a.panels.effect(
                                    "Text",
                                    Effect(
                                        kind = "Text",
                                        to = 3.0,
                                        values = mutableMapOf("text" to "Blabla"),
                                    ),
                                )
                            },
                        "crop" to { a.panels.effect("Crop") },
                        "placement" to { a.panels.effect("Placement") },
                        "luts" to { a.panels.effect("Color LUTs") },
                        "stabilize" to { a.panels.effect("Stabilize") },
                        "video-effects" to { a.panels.videoEffects() },
                        "color-category" to
                            {
                                a.panels.videoEffects()
                                findView(a.root) {
                                        it is android.widget.TextView &&
                                            it.text.toString() == "Color & style"
                                    }!!
                                    .let { (it.parent.parent as android.view.View).performClick() }
                            },
                        "applied-effects" to { a.panels.videoEffects() },
                        "quantization" to { a.panels.videoEffects(Effect(kind = "Quantization")) },
                        "quantization-palette" to
                            {
                                a.panels.videoEffects(Effect(kind = "Quantization"))
                                findView(a.root) {
                                        it is android.widget.TextView &&
                                            it.text.toString().startsWith("From scene")
                                    }!!
                                    .let { palette ->
                                        palette.addOnLayoutChangeListener(
                                            object : android.view.View.OnLayoutChangeListener {
                                                override fun onLayoutChange(
                                                    view: android.view.View,
                                                    left: Int,
                                                    top: Int,
                                                    right: Int,
                                                    bottom: Int,
                                                    oldLeft: Int,
                                                    oldTop: Int,
                                                    oldRight: Int,
                                                    oldBottom: Int,
                                                ) {
                                                    if (right <= left) return
                                                    palette.removeOnLayoutChangeListener(this)
                                                    val landscape = a.root.width > a.root.height
                                                    val unit =
                                                        if (landscape) a.root.height / 294f
                                                        else a.root.width / 294f
                                                    val scroll =
                                                        palette.parent.parent
                                                            as android.widget.ScrollView
                                                    scroll.scrollTo(
                                                        0,
                                                        palette.top -
                                                            ((if (landscape) 99f else 130f) * unit)
                                                                .toInt(),
                                                    )
                                                    palette.postOnAnimation {
                                                        palette.performClick()
                                                    }
                                                }
                                            }
                                        )
                                    }
                            },
                        "night-fix" to { a.panels.videoEffects(Effect(kind = "Night fix")) },
                        "color-correction" to
                            {
                                a.panels.videoEffects(Effect(kind = "Color Correction"))
                            },
                        "color-isolation" to
                            {
                                a.panels.videoEffects(Effect(kind = "Color Isolation"))
                            },
                        "person-isolation" to
                            {
                                a.panels.videoEffects(
                                    Effect(
                                        kind = "Color Isolation",
                                        values = mutableMapOf("mode" to "Person"),
                                    )
                                )
                            },
                        "audio" to { a.panels.audio() },
                        "transition" to
                            {
                                a.panels.transition(Transition(at = 3.0, kind = "Wipe"))
                            },
                        "volume" to { a.panels.volume() },
                        "speed" to { a.panels.speed() },
                        "pauses" to { a.panels.pauses() },
                        "export" to { a.panels.export() },
                    )
                for ((name, open) in panels) test(
                    "$label $name opens and cancels without editing"
                ) {
                    if (name == "transition")
                        onMain {
                            a.editor.cursor = 3.0
                            a.editor.split()
                        }
                    if (name == "audio")
                        onMain {
                            a.editor.project.clips
                                .first()
                                .effects
                                .addAll(
                                    listOf(
                                        Effect(kind = "AI voice isolation"),
                                        Effect(
                                            kind = "Voice EQ",
                                            values = mutableMapOf("gain" to "9.4"),
                                        ),
                                    )
                                )
                        }
                    if (name == "applied-effects")
                        onMain {
                            a.editor.replaceChain(
                                listOf("Expressionism", "Silent Tint", "16mm", "Undercranking")
                                    .map { Effect(kind = it) },
                                Effects.video,
                            )
                        }
                    val before = a.editor.project.duplicate()
                    onMain { open() }
                    Thread.sleep(300)
                    screenshot("$label-$name", a, name == "quantization-palette")
                    onMain {
                        if (name == "quantization-palette") a.onBackPressed()
                        a.panels.close()
                    }
                    check(a.editor.project == before) { "$name changed the project before Apply" }
                    if (name in listOf("audio", "transition", "applied-effects"))
                        onMain {
                            a.editor.load(project.duplicate())
                            a.editor.cursor = 2.0
                        }
                }
                test("$label video lanes and audio lanes remain separate") {
                    onMain {
                        val clip =
                            a.editor.project.clips.single().duplicate(true).apply {
                                track = 1
                                start = 1.0
                                sourceOut = 4.0
                            }
                        a.editor.project.clips.add(clip)
                        a.editor.onChange()
                    }
                    Thread.sleep(250)
                    screenshot("$label-multitrack", a)
                }
            }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", report.toString()) })
        } catch (e: Throwable) {
            finish(
                Activity.RESULT_CANCELED,
                Bundle().apply {
                    putString("stream", report.toString() + "FAIL " + e.stackTraceToString())
                },
            )
        } finally {
            val a = activity
            val p = original
            if (a != null && p != null)
                onMain {
                    a.panels.close()
                    a.editor.load(p)
                    a.editor.cursor = 0.0
                    a.editor.clearSelection()
                    a.editor.onChange()
                    a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                }
        }
    }

    private fun exports() {
        var activity: MainActivity? = null
        try {
            activity =
                startActivitySync(
                    Intent(targetContext, MainActivity::class.java)
                        .putExtra("testSession", true)
                        .putExtra("testSessionId", testSessionId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                    as MainActivity
            val source = File(targetContext.cacheDir, "export-fixture.mp4")
            context.assets.open("fixture.mp4").use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            val clip =
                ProjectStore.inspect(source).apply {
                    sourceOut = 2.0
                    effects.add(Effect(kind = "Text", values = mutableMapOf("text" to "YouRec")))
                }
            val project = Project(clips = mutableListOf(clip))
            val cache = AudioCache(File(targetContext.cacheDir, "test-audio"))
            test("person isolation preview and export honor the effect range") {
                val source = File(targetContext.cacheDir, "person-source.mp4")
                context.assets.open("person.mp4").use { input ->
                    source.outputStream().use { input.copyTo(it) }
                }
                val person =
                    Project(
                        clips =
                            mutableListOf(
                                ProjectStore.inspect(source).apply {
                                    effects.add(
                                        Effect(
                                            kind = "Color Isolation",
                                            from = .2,
                                            to = .6,
                                            values = mutableMapOf("mode" to "Person"),
                                        )
                                    )
                                }
                            )
                    )
                val target = File(targetContext.cacheDir, "person-export.mp4")
                fun chroma(bitmap: Bitmap, x: Int, y: Int): Int {
                    val p = bitmap.getPixel(x, y)
                    return maxOf(Color.red(p), Color.green(p), Color.blue(p)) -
                        minOf(Color.red(p), Color.green(p), Color.blue(p))
                }
                try {
                    Frames().use { frames ->
                        for ((time, isolated) in listOf(.1 to false, .4 to true, .7 to false)) {
                            val bitmap = frames.render(person, time, 352, 200)
                            try {
                                check(chroma(bitmap, 20, 110) < 2 == isolated) {
                                    "Preview background at $time: ${chroma(bitmap, 20, 110)}"
                                }
                                check(chroma(bitmap, 220, 70) > 20)
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                    Export(cache).run(person, target, 352, 200) {}
                    VideoDecoder(target.absolutePath).use { decoder ->
                        for ((time, isolated) in listOf(.1 to false, .4 to true, .7 to false)) {
                            val bitmap =
                                decoder.frame(time, 352, 200)
                                    ?: error("Missing person export frame")
                            try {
                                check(chroma(bitmap, 20, 110) < 5 == isolated) {
                                    "Export background at $time: ${chroma(bitmap, 20, 110)}"
                                }
                                check(chroma(bitmap, 220, 70) > 15)
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                } finally {
                    target.delete()
                }
            }
            test("overlapping wipe persists and exported frames match the preview") {
                val edit = Editor(Project(clips = mutableListOf(clip.duplicate())))
                edit.cursor = 1.0
                edit.split()
                edit.project.clips.first().effects.add(Effect(kind = "Noir"))
                edit.putTransition(
                    Transition(at = 1.0, duration = .5, kind = "Wipe", angle = 90f, soft = false)
                )
                val store = ProjectStore(File(targetContext.cacheDir, "transition-export-store"))
                store.save(edit.project)
                val restored = store.current()
                check(restored == edit.project)
                val target = File(targetContext.cacheDir, "transition-export.mp4")
                try {
                    Export(cache).run(restored, target, 640, 360) {}
                    val reader = MediaMetadataRetriever()
                    try {
                        reader.setDataSource(target.absolutePath)
                        val duration =
                            reader
                                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!
                                .toDouble() / 1000
                        check(abs(duration - 1.5) < .12) { "Transition export duration $duration" }
                    } finally {
                        reader.release()
                    }
                    Frames().use { frames ->
                        VideoDecoder(target.absolutePath).use { decoder ->
                            for (time in listOf(.2, .6, .8, 1.2)) {
                                val expected = frames.render(restored, time, 320, 180)
                                val actual =
                                    decoder.frame(time, 320, 180) ?: error("Missing exported frame")
                                try {
                                    var error = 0.0
                                    for (y in 5 until 175 step 3) for (x in 5 until 315 step 3) {
                                        val a = expected.getPixel(x, y)
                                        val b = actual.getPixel(x, y)
                                        error +=
                                            abs(Color.red(a) - Color.red(b)) +
                                                abs(Color.green(a) - Color.green(b)) +
                                                abs(Color.blue(a) - Color.blue(b))
                                    }
                                    val mean = error / (57 * 104 * 3)
                                    check(mean < 16) { "Frame error $mean at $time" }
                                } finally {
                                    expected.recycle()
                                    actual.recycle()
                                }
                            }
                        }
                    }
                } finally {
                    target.delete()
                }
            }
            for ((width, height) in listOf(1280 to 720, 1920 to 1080, 3840 to 2160)) {
                test("${width}x$height H.264 AAC export decodes") {
                    val target = File(targetContext.cacheDir, "resolution-test.mp4")
                    val began = android.os.SystemClock.elapsedRealtime()
                    Export(cache).run(project, target, width, height) {}
                    val reader = MediaMetadataRetriever()
                    try {
                        reader.setDataSource(target.absolutePath)
                        check(
                            reader.extractMetadata(
                                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                            ) == "$width"
                        )
                        check(
                            reader.extractMetadata(
                                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                            ) == "$height"
                        )
                        check(
                            reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) ==
                                "yes"
                        )
                        val duration =
                            reader
                                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!
                                .toDouble() / 1000
                        check(abs(duration - 2) < 0.15) { "Exported duration $duration" }
                        val image =
                            reader.getScaledFrameAtTime(
                                1_000_000,
                                MediaMetadataRetriever.OPTION_CLOSEST,
                                320,
                                180,
                            ) ?: error("Cannot decode exported frame")
                        check(Color.green(image.getPixel(80, 90)) > 100)
                        image.recycle()
                        sendStatus(
                            0,
                            Bundle().apply {
                                putString(
                                    "stream",
                                    "${width}x$height: ${(android.os.SystemClock.elapsedRealtime()-began)/1000.0} s for 2 s of video\n",
                                )
                            },
                        )
                    } finally {
                        reader.release()
                        target.delete()
                    }
                }
            }
            test("long multitrack export retains edited duration and final audio video frames") {
                val full = ProjectStore.inspect(source)
                val edit = Editor()
                repeat(5) { edit.append(full.duplicate(true)) }
                for (index in listOf(1, 3)) {
                    edit.putTransition(
                        Transition(
                            at = edit.project.clips[index].start,
                            kind = if (index == 1) "Wipe" else "Defocus",
                            duration = .5,
                        )
                    )
                }
                edit.select(edit.project.clips[2])
                edit.replaceChain(listOf(Effect(kind = "Undercranking")), Effects.video)
                edit.project.clips
                    .first()
                    .effects
                    .add(
                        Effect(
                            kind = "Quantization",
                            values = mutableMapOf("palette" to "1", "smoothing" to "0"),
                        )
                    )
                edit.project.clips.forEach {
                    it.effects.add(
                        Effect(kind = "Text", values = mutableMapOf("text" to "YouRec long export"))
                    )
                }
                edit.project.clips.add(
                    full.duplicate(true).apply {
                        video = false
                        track = 1
                        start = 5.0
                        gainDb = -6f
                    }
                )
                val target = File(targetContext.cacheDir, "long-export.mp4")
                val began = android.os.SystemClock.elapsedRealtime()
                try {
                    Export(cache).run(edit.project, target, 1280, 720) {}
                    val exported = ProjectStore.inspect(target)
                    check(abs(exported.duration - edit.project.duration) < .12) {
                        "Long export ${exported.duration} vs ${edit.project.duration}"
                    }
                    check(exported.video && exported.audio)
                    VideoDecoder(target.absolutePath).use { decoder ->
                        val frame =
                            decoder.frame(edit.project.duration - .1, 320, 180)
                                ?: error("No final video frame")
                        frame.recycle()
                    }
                    val pcm = cache.get(target.absolutePath)
                    check(abs(pcm.length() / 8.0 / 48000 - edit.project.duration) < .12)
                    sendStatus(
                        0,
                        Bundle().apply {
                            putString(
                                "stream",
                                "Long export: ${edit.project.duration} s video in ${(android.os.SystemClock.elapsedRealtime()-began)/1000.0} s\n",
                            )
                        },
                    )
                } finally {
                    target.delete()
                }
            }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", report.toString()) })
        } catch (e: Throwable) {
            finish(
                Activity.RESULT_CANCELED,
                Bundle().apply {
                    putString("stream", report.toString() + "FAIL " + e.stackTraceToString())
                },
            )
        } finally {
            activity?.let { onMain { it.finish() } }
        }
    }

    private fun studio() {
        var activity: MainActivity? = null
        try {
            activity =
                startActivitySync(
                    Intent(targetContext, MainActivity::class.java)
                        .putExtra("testSession", true)
                        .putExtra("testSessionId", testSessionId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                    as MainActivity
            val source = File(targetContext.cacheDir, "studio-speech.wav")
            context.assets.open("speech.wav").use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            val cache = AudioCache(File(targetContext.cacheDir, "studio-test-audio"))
            val dry = Project(clips = mutableListOf(ProjectStore.inspect(source)))
            val wet =
                dry.duplicate().apply {
                    clips.single().effects.add(Effect(kind = "Studio clarity"))
                }
            fun samples(project: Project, matchListening: Boolean = false): FloatArray =
                AudioMixer(project, cache, matchListening).use {
                    it.prepare()
                    it.read((project.duration * 48000).roundToInt())
                }
            test("Studio clarity C API and verified model load") {
                check(Native.studioAvailable())
                check(Studio.ready)
            }
            val original = samples(dry)
            var enhanced = FloatArray(0)
            test("neural studio restoration changes speech and preserves sample timing") {
                val began = android.os.SystemClock.elapsedRealtime()
                enhanced = samples(wet)
                check(enhanced.size == original.size && enhanced.all { it.isFinite() })
                val error =
                    enhanced.indices.sumOf {
                        val delta = enhanced[it] - original[it]
                        delta.toDouble() * delta
                    } / enhanced.size
                check(error > 1e-9) { "Studio restoration did not change the speech" }
                sendStatus(
                    0,
                    Bundle().apply {
                        putString(
                            "stream",
                            "Studio audio: ${wet.duration} s in ${(android.os.SystemClock.elapsedRealtime()-began)/1000.0} s\n",
                        )
                    },
                )
            }
            test("equivalent draft effect ids reuse prepared studio audio") {
                val first = Studio.prepare(wet, wet.clips.single(), cache, { false }, {})!!
                val changed =
                    wet.duplicate().apply {
                        val fx = clips.single().effects.single().copyEffect(true)
                        clips.single().effects.clear()
                        clips.single().effects.add(fx)
                    }
                val second = Studio.prepare(changed, changed.clips.single(), cache, { false }, {})!!
                check(first.file == second.file)
            }
            test("studio restoration respects its range and preserves mute") {
                val range =
                    wet.duplicate().apply {
                        clips.single().effects.single().apply {
                            from = .8
                            to = 1.6
                        }
                    }
                val output = samples(range)
                check(output.copyOfRange(0, 24000).contentEquals(original.copyOfRange(0, 24000)))
                val muted = wet.duplicate().apply { clips.single().gainDb = -60f }
                check(samples(muted).all { it == 0f })
            }
            test("EQ before or after neural restoration retains the requested filter order") {
                val eq =
                    Effect(
                        kind = "Voice EQ",
                        values = mutableMapOf("frequency" to "6000", "gain" to "12"),
                    )
                val before =
                    wet.duplicate().apply { clips.single().effects.add(0, eq.copyEffect()) }
                val after = wet.duplicate().apply { clips.single().effects.add(eq.copyEffect()) }
                val a = samples(before)
                val b = samples(after)
                check(
                    a.indices.sumOf {
                        val delta = a[it] - b[it]
                        delta.toDouble() * delta
                    } / a.size > 1e-8
                )
            }
            test("studio preparation cancels before inference") {
                val changed =
                    wet.duplicate().apply {
                        clips.single().effects.single().values["strength"] = ".731"
                    }
                try {
                    Studio.prepare(changed, changed.clips.single(), cache, { true }, {})
                    error("Preparation ignored cancellation")
                } catch (_: java.io.InterruptedIOException) {}
            }
            test("listening level matching includes filters before neural restoration") {
                val boosted =
                    wet.duplicate().apply {
                        clips
                            .single()
                            .effects
                            .add(
                                0,
                                Effect(
                                    kind = "Voice EQ",
                                    values = mutableMapOf("frequency" to "1800", "gain" to "9"),
                                ),
                            )
                    }
                val output = samples(boosted, true)
                val ratio =
                    sqrt(
                        output.sumOf { it.toDouble() * it } / original.sumOf { it.toDouble() * it }
                    )
                check(ratio in .98..1.02) { "Listening RMS ratio $ratio" }
            }
            test("studio preview resumes after live parameter changes and applying a split range") {
                onMain {
                    activity.editor.load(dry.duplicate())
                    activity.editor.range = .5..2.5
                    activity.editor.cursor = .5
                    activity.panels.audio(null, listOf(Effect(kind = "Studio clarity")))
                }
                Thread.sleep(200)
                onMain {
                    findView(activity.root) { it.contentDescription == "Play preview" }!!
                        .performClick()
                }
                val deadline = android.os.SystemClock.elapsedRealtime() + 5000
                while (
                    activity.editor.cursor <= .6 &&
                        android.os.SystemClock.elapsedRealtime() < deadline
                ) Thread.sleep(100)
                check(activity.playback.playing && activity.editor.cursor > .6)
                onMain {
                    val slider =
                        findView(activity.root) {
                            it is android.widget.SeekBar && it.contentDescription == "Strength"
                        }!!
                    val now = android.os.SystemClock.uptimeMillis()
                    for (action in
                        listOf(
                            android.view.MotionEvent.ACTION_DOWN,
                            android.view.MotionEvent.ACTION_MOVE,
                            android.view.MotionEvent.ACTION_UP,
                        )) {
                        val event =
                            android.view.MotionEvent.obtain(
                                now,
                                now + 20,
                                action,
                                slider.width * .6f,
                                slider.height * .5f,
                                0,
                            )
                        slider.dispatchTouchEvent(event)
                        event.recycle()
                    }
                }
                Thread.sleep(100)
                check(
                    activity.playback.previewProject!!
                        .clips
                        .flatMap { it.effects }
                        .single { it.kind == "Studio clarity" }
                        .number("strength", 1f) < .8f
                )
                onMain {
                    findView(activity.root) {
                            it is android.widget.TextView && it.text.toString() == "Apply"
                        }!!
                        .performClick()
                }
                Thread.sleep(500)
                check(activity.editor.project.clips.size == 3)
                check(activity.playback.playing)
                onMain {
                    activity.playback.pause()
                    activity.panels.close()
                }
            }
            test("neural studio export keeps audio duration and playable tracks") {
                val target = File(targetContext.cacheDir, "studio-export.mp4")
                try {
                    Export(cache).run(wet, target, 640, 360) {}
                    val clip = ProjectStore.inspect(target)
                    check(clip.video && clip.audio && abs(clip.duration - wet.duration) < .12)
                    val encoded = samples(Project(clips = mutableListOf(clip)))
                    check(encoded.any { abs(it) > .001f })
                } finally {
                    target.delete()
                }
            }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", report.toString()) })
        } catch (error: Throwable) {
            finish(
                Activity.RESULT_CANCELED,
                Bundle().apply {
                    putString("stream", report.toString() + "FAIL " + error.stackTraceToString())
                },
            )
        } finally {
            activity?.let { onMain { it.finish() } }
        }
    }

    private fun speech() {
        try {
            val file = File(targetContext.filesDir, "speech-test.wav")
            context.assets.open("speech.wav").use { src ->
                file.outputStream().use { src.copyTo(it) }
            }
            val cache = AudioCache(File(targetContext.cacheDir, "speech-test"))
            val directory = File(targetContext.getExternalFilesDir(null), "models/qwen3-asr-0.6b")
            val captions = Captions(cache, directory)
            test("RNNoise processes actual audio samples") {
                val state = Native.audioCreate(48000)
                try {
                    var changed = false
                    val random = java.util.Random(42)
                    repeat(100) {
                        val pcm = FloatArray(960) { (random.nextFloat() - 0.5f) * 0.1f }
                        val before = pcm.copyOf()
                        Native.audioProcess(state, pcm, 480, 1f, 0f, 0f, 1800f, 0f, 0f, 1f)
                        check(pcm.all { it.isFinite() })
                        if (!before.contentEquals(pcm)) changed = true
                    }
                    check(changed)
                } finally {
                    Native.audioDestroy(state)
                }
            }
            test("Qwen captions transcribe speech offline") {
                val began = android.os.SystemClock.elapsedRealtime()
                val project = Project(clips = mutableListOf(ProjectStore.inspect(file)))
                val result = captions.generate(project, "English") {}
                val transcript = result.joinToString(" ") { it.text }
                sendStatus(
                    0,
                    Bundle().apply {
                        putString(
                            "stream",
                            "Transcript: $transcript\nTime: ${(android.os.SystemClock.elapsedRealtime()-began)/1000.0} s\n",
                        )
                    },
                )
                val lower = transcript.lowercase()
                check("test" in lower && "speech" in lower) { "Unexpected transcript: $transcript" }
            }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", report.toString()) })
        } catch (e: Throwable) {
            finish(
                Activity.RESULT_CANCELED,
                Bundle().apply {
                    putString("stream", report.toString() + "FAIL " + e.stackTraceToString())
                },
            )
        }
    }

    private fun screenshot(name: String, activity: Activity, ownedPopup: Boolean = false) {
        var focused = false
        val deadline = android.os.SystemClock.elapsedRealtime() + 5000
        while (!focused && android.os.SystemClock.elapsedRealtime() < deadline) {
            onMain { focused = activity.hasWindowFocus() }
            if (!focused && ownedPopup)
                focused =
                    uiAutomation.rootInActiveWindow?.packageName?.toString() ==
                        targetContext.packageName
            if (!focused) Thread.sleep(100)
        }
        check(focused) { "The app is covered by another window" }
        val bitmap = uiAutomation.takeScreenshot() ?: error("Cannot capture screenshot")
        val directory =
            File(
                    targetContext.getExternalFilesDir(null),
                    if (suite == "parity") "parity-screens" else "test-screens",
                )
                .apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun findView(
        view: android.view.View,
        matches: (android.view.View) -> Boolean,
    ): android.view.View? {
        if (matches(view)) return view
        if (view is android.view.ViewGroup)
            for (i in 0 until view.childCount) {
                findView(view.getChildAt(i), matches)?.let {
                    return it
                }
            }
        return null
    }
}
