package org.myrec.app

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
    private var currentProject = false
    private val testSessionId = uid()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        suite = arguments?.getString("suite") ?: ""
        currentProject = arguments?.getString("currentProject") == "true"
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
        if (suite == "hdr-scene") {
            hdrScene()
            return
        }
        if (suite == "hdr-color") {
            var activity: MainActivity? = null
            try {
                val a =
                    startActivitySync(
                        Intent(targetContext, MainActivity::class.java)
                            .putExtra("testSession", true)
                            .putExtra("testSessionId", testSessionId)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                        as MainActivity
                activity = a
                test("HLG YUV sampling preserves colour and preview matches BT2020 tone mapping") {
                    HdrColorTest.run(this, a)
                }
                onMain { a.finish() }
                finish(
                    Activity.RESULT_OK,
                    Bundle().apply { putString("stream", report.toString()) },
                )
            } catch (e: Throwable) {
                activity?.let { onMain { it.finish() } }
                finish(
                    Activity.RESULT_CANCELED,
                    Bundle().apply {
                        putString("stream", report.toString() + "FAIL " + e.stackTraceToString())
                    },
                )
            }
            return
        }
        if (
            suite == "references" ||
                suite == "camera-preview" ||
                suite == "playback" ||
                suite == "capture-controls" ||
                suite == "media-editing" ||
                suite == "live-rotation"
        ) {
            references()
            return
        }
        if (suite == "new-ui") {
            newUi()
            return
        }
        if (suite == "composition") {
            composition()
            return
        }
        if (suite == "camera-gaps") {
            cameraGaps()
            return
        }
        if (suite == "gaps") {
            gaps()
            return
        }
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
        if (suite == "orientation") {
            orientation()
            return
        }
        if (suite == "timeline") {
            timeline()
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
                        values = mutableMapOf("text" to "MyRec test", "x" to "0.1", "y" to "0.2"),
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
            test("portrait camera places scissors right of Record and AF alone controls focus") {
                onMain { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                Thread.sleep(1000)
                onMain {
                    val edit = camera.controlBounds("edit")!!
                    val record = camera.controlBounds("record")!!
                    val projects = camera.controlBounds("projects")!!
                    check(
                        projects.centerX() < record.centerX() && record.centerX() < edit.centerX()
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

    private fun timeline() {
        var activity: MainActivity? = null
        try {
            val a =
                startActivitySync(
                    Intent(targetContext, MainActivity::class.java)
                        .putExtra("testSession", true)
                        .putExtra("testSessionId", testSessionId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                    as MainActivity
            activity = a
            for ((label, width, height) in
                listOf(Triple("portrait", 294, 640), Triple("landscape", 640, 294))) {
                test("$label split thumbnails scroll together without overwriting adjacent clips") {
                    onMain {
                        for ((separateSources, cursor) in
                            listOf(false to 7.0, false to 11.0, true to 7.0, true to 11.0)) {
                            val path = File(targetContext.cacheDir, "timeline-pattern.mp4").path
                            val editor =
                                Editor(
                                    Project(
                                        clips = mutableListOf(Clip(path = path, sourceOut = 20.0))
                                    )
                                )
                            editor.cursor = 6.0
                            editor.split()
                            editor.cursor = 13.0
                            editor.split()
                            if (separateSources)
                                for (i in editor.project.clips.indices) {
                                    val clip = editor.project.clips[i]
                                    editor.project.clips[i] = clip.copy(path = "$path-$i")
                                }
                            editor.clearSelection()
                            editor.cursor = cursor
                            editor.scale = 10.0
                            editor.snap = false
                            val original = editor.project.duplicate()
                            val index =
                                MediaIndex(
                                    AudioCache(File(targetContext.cacheDir, "timeline-audio"))
                                ) {}
                            val images = mutableListOf<Bitmap>()
                            for ((i, clip) in
                                editor.project.clips.distinctBy { it.path }.withIndex()) {
                                val image = Bitmap.createBitmap(80, 45, Bitmap.Config.ARGB_8888)
                                for (y in 0 until 45) for (x in 0 until 80) image.setPixel(
                                    x,
                                    y,
                                    Color.rgb(
                                        (x * 19 + i * 31) % 256,
                                        (x * 7 + i * 71) % 256,
                                        (x * 13 + i * 113) % 256,
                                    ),
                                )
                                images.add(image)
                                index.thumbnails[clip.path] = listOf(image)
                                index.durations[clip.path] = 20.0
                                index.waves[clip.path] = FloatArray(200) { .2f + (it % 7) / 10f }
                            }
                            val view =
                                EditorView(
                                    a,
                                    editor,
                                    index,
                                    {},
                                    { editor.cursor = it.coerceIn(0.0, 20.0) },
                                )
                            view.layout(0, 0, width, height)
                            fun draw(): Bitmap =
                                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                                    view.draw(Canvas(it))
                                }
                            val before = draw()
                            val left = if (label == "portrait") 36 else 46
                            val right = if (label == "portrait") 294 else 594
                            val y = if (label == "portrait") 430 else 174
                            val x = (left + right) / 2f + 40
                            val now = android.os.SystemClock.uptimeMillis()
                            var eventTime = now
                            fun touch(action: Int, delta: Int) {
                                eventTime += 16
                                val event =
                                    android.view.MotionEvent.obtain(
                                        now,
                                        eventTime,
                                        action,
                                        x - delta,
                                        y.toFloat(),
                                        0,
                                    )
                                view.dispatchTouchEvent(event)
                                event.recycle()
                            }
                            try {
                                touch(android.view.MotionEvent.ACTION_DOWN, 0)
                                for (delta in listOf(8, 16, 24, 32, 24, 8, 0, -16)) {
                                    touch(android.view.MotionEvent.ACTION_MOVE, delta)
                                    val after = draw()
                                    try {
                                        var different = 0
                                        var checked = 0
                                        for (px in
                                            max(left + 12, left - delta + 12) until
                                                min(right - 12, right - delta - 12)) {
                                            val center = (left + right) / 2
                                            if (
                                                abs(px - center) < 3 || abs(px + delta - center) < 3
                                            )
                                                continue
                                            val expected = before.getPixel(px + delta, y)
                                            val actual = after.getPixel(px, y)
                                            if (
                                                abs(Color.red(expected) - Color.red(actual)) > 25 ||
                                                    abs(
                                                        Color.green(expected) - Color.green(actual)
                                                    ) > 25 ||
                                                    abs(Color.blue(expected) - Color.blue(actual)) >
                                                        25
                                            )
                                                different++
                                            checked++
                                        }
                                        check(different <= 4) {
                                            "$label $separateSources at $cursor: $different/$checked thumbnail pixels failed to translate by $delta"
                                        }
                                        check(editor.project == original) {
                                            "Scrolling changed clip data"
                                        }
                                    } finally {
                                        after.recycle()
                                    }
                                }
                                touch(android.view.MotionEvent.ACTION_UP, -16)
                                check(editor.project == original)
                                check(
                                    abs(editor.cursor - (cursor - 16.0 / (right - left) * 10)) <
                                        .001
                                )
                            } finally {
                                before.recycle()
                                index.close()
                                images.forEach { it.recycle() }
                            }
                        }
                    }
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

    private fun orientation() {
        var activity: MainActivity? = null
        try {
            val a =
                startActivitySync(
                    Intent(targetContext, MainActivity::class.java)
                        .putExtra("testSession", true)
                        .putExtra("testSessionId", testSessionId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                    as MainActivity
            activity = a
            test(
                "recording surface normalizes 0, 90, 180 and 270 degree camera changes in one file"
            ) {
                val target = File(targetContext.cacheDir, "camera-orientation.mp4")
                val recorder =
                    if (android.os.Build.VERSION.SDK_INT >= 31)
                        android.media.MediaRecorder(targetContext)
                    else android.media.MediaRecorder()
                var bridge: CameraRecordingSurface? = null
                var started = false
                val renderError = java.util.concurrent.atomic.AtomicReference<Throwable>()
                val centers = mutableListOf<Double>()
                try {
                    recorder.setVideoSource(android.media.MediaRecorder.VideoSource.SURFACE)
                    recorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                    recorder.setOutputFile(target.absolutePath)
                    recorder.setVideoEncoder(android.media.MediaRecorder.VideoEncoder.H264)
                    recorder.setVideoSize(320, 180)
                    recorder.setVideoFrameRate(30)
                    recorder.setVideoEncodingBitRate(2_000_000)
                    recorder.prepare()
                    bridge =
                        CameraRecordingSurface(recorder.surface, 320, 180) { renderError.set(it) }
                    recorder.start()
                    started = true
                    var first = 0L
                    val paint = Paint()
                    for ((rotation, sourceWidth, sourceHeight) in
                        listOf(
                            Triple(0, 320, 180),
                            Triple(180, 320, 180),
                            Triple(90, 180, 320),
                            Triple(270, 180, 320),
                            Triple(0, 400, 300),
                            Triple(0, 320, 180),
                        )) {
                        val quarter = rotation % 180 != 0
                        val surface = bridge.input(sourceWidth, sourceHeight, rotation, false)
                        if (first == 0L) first = android.os.SystemClock.elapsedRealtimeNanos()
                        centers.add(
                            (android.os.SystemClock.elapsedRealtimeNanos() - first) / 1e9 + .25
                        )
                        repeat(12) {
                            val canvas = surface.lockCanvas(null)
                            canvas.drawColor(Color.BLACK)
                            canvas.save()
                            canvas.translate(canvas.width / 2f, canvas.height / 2f)
                            canvas.rotate(-rotation.toFloat())
                            val w = (if (quarter) canvas.height else canvas.width) / 2f
                            val h = (if (quarter) canvas.width else canvas.height) / 2f
                            for ((rect, color) in
                                listOf(
                                    RectF(-w, -h, 0f, 0f) to Color.RED,
                                    RectF(0f, -h, w, 0f) to Color.GREEN,
                                    RectF(-w, 0f, 0f, h) to Color.BLUE,
                                    RectF(0f, 0f, w, h) to Color.YELLOW,
                                )) {
                                paint.color = color
                                canvas.drawRect(rect, paint)
                            }
                            canvas.restore()
                            surface.unlockCanvasAndPost(canvas)
                            Thread.sleep(50)
                        }
                    }
                    bridge.close()
                    bridge = null
                    recorder.stop()
                    started = false
                    renderError.get()?.let { throw it }
                    VideoDecoder(target.absolutePath).use { decoder ->
                        for (time in centers) {
                            val frame =
                                decoder.frame(time, 320, 180) ?: error("Missing orientation frame")
                            try {
                                for ((point, expected) in
                                    listOf(
                                        (20 to 20) to Color.RED,
                                        (300 to 20) to Color.GREEN,
                                        (20 to 160) to Color.BLUE,
                                        (300 to 160) to Color.YELLOW,
                                    )) {
                                    val actual = frame.getPixel(point.first, point.second)
                                    check(
                                        abs(Color.red(actual) - Color.red(expected)) < 35 &&
                                            abs(Color.green(actual) - Color.green(expected)) < 35 &&
                                            abs(Color.blue(actual) - Color.blue(expected)) < 35
                                    ) {
                                        "Orientation at $time, $point: ${actual.toUInt().toString(16)} expected ${expected.toUInt().toString(16)}"
                                    }
                                }
                            } finally {
                                frame.recycle()
                            }
                        }
                    }
                    val extractor = android.media.MediaExtractor()
                    try {
                        extractor.setDataSource(target.absolutePath)
                        extractor.selectTrack(0)
                        val times = mutableListOf<Long>()
                        while (extractor.sampleTime >= 0) {
                            times.add(extractor.sampleTime)
                            extractor.advance()
                        }
                        check(times.size >= 30 && times.distinct().size == times.size) {
                            "Camera presentation times $times"
                        }
                        check(times.max() - times.min() > 2_000_000)
                    } finally {
                        extractor.release()
                    }
                } finally {
                    bridge?.close()
                    if (started) runCatching { recorder.stop() }
                    recorder.release()
                    target.delete()
                }
            }
            onMain { a.showCapture() }
            Thread.sleep(1200)
            val capture =
                (0 until a.root.childCount)
                    .map { a.root.getChildAt(it) }
                    .filterIsInstance<Capture>()
                    .single()
            val manager = a.getSystemService(android.hardware.camera2.CameraManager::class.java)
            val rear =
                manager.cameraIdList.first {
                    manager
                        .getCameraCharacteristics(it)
                        .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == 1
                }
            val front =
                manager.cameraIdList.first {
                    manager
                        .getCameraCharacteristics(it)
                        .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == 0
                }
            for ((name, orientation) in
                listOf(
                    "portrait" to ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                    "landscape" to ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                )) {
                for ((label, first, next) in
                    listOf(Triple("rear", rear, front), Triple("front", front, rear))) {
                    test(
                        "$name $label start switches cameras both ways without changing recording orientation"
                    ) {
                        onMain {
                            capture.suspendCamera()
                            a.requestedOrientation = orientation
                        }
                        Thread.sleep(5000)
                        val references =
                            listOf(first, next).associateWith { id ->
                                val chars = manager.getCameraCharacteristics(id)
                                val rotation =
                                    CameraOrientation.recording(
                                        chars.get(
                                            android.hardware.camera2.CameraCharacteristics
                                                .SENSOR_ORIENTATION
                                        )!!,
                                        a.windowManager.defaultDisplay.rotation * 90,
                                        id == front,
                                    )
                                val frame = cameraReference(manager, id, rotation)
                                if (id == front) SourceMirror.flip(frame) else frame
                            }
                        onMain { capture.resumeCamera() }
                        Thread.sleep(700)
                        onMain { capture.switchCamera(first) }
                        Thread.sleep(800)
                        val before = a.editor.project.clips.size
                        val sensor =
                            manager
                                .getCameraCharacteristics(first)
                                .get(
                                    android.hardware.camera2.CameraCharacteristics
                                        .SENSOR_ORIENTATION
                                )!!
                        val expected =
                            CameraOrientation.recording(
                                sensor,
                                a.windowManager.defaultDisplay.rotation * 90,
                                first == front,
                            )
                        onMain { capture.start() }
                        Thread.sleep(1000)
                        check(capture.recording && !capture.paused)
                        onMain { capture.switchCamera(next) }
                        Thread.sleep(1200)
                        check(capture.recording && !capture.paused)
                        onMain { capture.switchCamera(first) }
                        Thread.sleep(1000)
                        check(capture.recording && !capture.paused)
                        onMain { capture.stop() }
                        Thread.sleep(300)
                        check(a.editor.project.clips.size == before + 1)
                        val clip = a.editor.project.clips.last()
                        val reader = MediaMetadataRetriever()
                        try {
                            reader.setDataSource(clip.path)
                            val actual =
                                reader
                                    .extractMetadata(
                                        MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
                                    )
                                    ?.toInt() ?: 0
                            check(actual == expected) { "Rotation $actual, expected $expected" }
                            for ((time, id) in
                                listOf(
                                    500_000L to first,
                                    1_600_000L to next,
                                    2_700_000L to first,
                                )) {
                                val frame =
                                    reader.getFrameAtTime(
                                        time,
                                        MediaMetadataRetriever.OPTION_CLOSEST,
                                    ) ?: error("Missing camera frame")
                                check((frame.height > frame.width) == (name == "portrait"))
                                val scores = cameraCorrelations(references.getValue(id), frame)
                                report.append("Camera pixels $name $label at $time: $scores\n")
                                val captures =
                                    File(
                                            targetContext.getExternalFilesDir(null),
                                            "orientation-frames",
                                        )
                                        .apply { mkdirs() }
                                File(captures, "$name-$label-$time.png").outputStream().use {
                                    frame.compress(Bitmap.CompressFormat.PNG, 100, it)
                                }
                                File(captures, "$name-$label-$time-reference.png")
                                    .outputStream()
                                    .use {
                                        references
                                            .getValue(id)
                                            .compress(Bitmap.CompressFormat.PNG, 100, it)
                                    }
                                check(scores[0] > .4 && scores[0] > scores.drop(1).max() + .15) {
                                    "Camera pixels do not match the upright reference: $name $label at $time, correlations for 0/90/180/270: $scores"
                                }
                                frame.recycle()
                            }
                        } finally {
                            reader.release()
                            references.values.forEach { it.recycle() }
                        }
                        val extractor = android.media.MediaExtractor()
                        try {
                            extractor.setDataSource(clip.path)
                            val ranges = mutableMapOf<String, Pair<Long, Long>>()
                            for (index in 0 until extractor.trackCount) {
                                val mime =
                                    extractor
                                        .getTrackFormat(index)
                                        .getString(android.media.MediaFormat.KEY_MIME)!!
                                extractor.selectTrack(index)
                                var low = Long.MAX_VALUE
                                var high = 0L
                                while (extractor.sampleTime >= 0) {
                                    low = min(low, extractor.sampleTime)
                                    high = max(high, extractor.sampleTime)
                                    extractor.advance()
                                }
                                ranges[mime.substringBefore('/')] = low to high
                                extractor.unselectTrack(index)
                                extractor.seekTo(
                                    0,
                                    android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC,
                                )
                            }
                            val video = ranges.getValue("video")
                            val audio = ranges.getValue("audio")
                            check(
                                abs(video.first - audio.first) < 250_000 &&
                                    abs(video.second - audio.second) < 250_000
                            ) {
                                "Camera audio/video drift: $ranges"
                            }
                        } finally {
                            extractor.release()
                        }
                    }
                }
            }
            onMain {
                capture.suspendCamera()
                a.showEditor()
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
                        "settings" to { a.panels.tools() },
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
                    if (name == "settings") {
                        onMain {
                            val scroll =
                                findView(a.root) { it is android.widget.ScrollView }!!
                                    as android.widget.ScrollView
                            scroll.fullScroll(android.view.View.FOCUS_DOWN)
                            for (title in listOf("Snap to cuts", "Match listening levels")) {
                                val card = findView(a.root) { it.contentDescription == title }!!
                                val text =
                                    findView(card) {
                                        it is android.widget.TextView &&
                                            it.text.toString().replace('\n', ' ') == title
                                    }!!
                                        as android.widget.TextView
                                check(
                                    text.layout.lineCount <= 2 && text.layout.height <= text.height
                                ) {
                                    "$title is clipped"
                                }
                                for (line in 0 until text.layout.lineCount) check(
                                    text.layout.getLineRight(line) <= text.width + 1
                                )
                                val original =
                                    if (title == "Snap to cuts") a.editor.snap
                                    else a.editor.matchLoudness
                                card.performClick()
                                check(
                                    (if (title == "Snap to cuts") a.editor.snap
                                    else a.editor.matchLoudness) != original
                                )
                                card.performClick()
                                check(
                                    (if (title == "Snap to cuts") a.editor.snap
                                    else a.editor.matchLoudness) == original
                                )
                            }
                        }
                        Thread.sleep(150)
                    }
                    if (name == "quantization")
                        onMain {
                            val animate = findView(a.root) { it.contentDescription == "Animate" }!!
                            check(findView(animate) { it is Glyph } != null)
                            val label =
                                findView(animate) { it is android.widget.TextView }!!
                                    as android.widget.TextView
                            check(
                                label.layout.lineCount == 1 &&
                                    label.layout.getLineRight(0) <= label.width + 1
                            )
                        }
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
                    effects.add(Effect(kind = "Text", values = mutableMapOf("text" to "MyRec")))
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
                        Effect(kind = "Text", values = mutableMapOf("text" to "MyRec long export"))
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

    private fun hdrScene() {
        var activity: MainActivity? = null
        try {
            val a =
                startActivitySync(
                    Intent(targetContext, MainActivity::class.java)
                        .putExtra("testSession", true)
                        .putExtra("testSessionId", testSessionId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                    as MainActivity
            activity = a
            onMain { a.showCapture() }
            Thread.sleep(1500)
            val capture =
                (0 until a.root.childCount)
                    .map { a.root.getChildAt(it) }
                    .filterIsInstance<Capture>()
                    .single()
            val folder =
                File(targetContext.getExternalFilesDir(null), "hdr-scene").apply { mkdirs() }
            for (hdr in listOf(false, true)) test(
                "${if(hdr) "HDR" else "SDR"} rear camera scene is recorded and rendered"
            ) {
                onMain { capture.setHdr(hdr) }
                Thread.sleep(2000)
                val name = if (hdr) "hdr" else "sdr"
                val preview =
                    Bitmap.createBitmap(a.root.width, a.root.height, Bitmap.Config.ARGB_8888)
                onMain { capture.drawCamera(Canvas(preview)) }
                File(folder, "$name-preview.png").outputStream().use {
                    preview.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                preview.recycle()
                capture.latestResult?.let { result ->
                    val info =
                        org.json
                            .JSONObject()
                            .put(
                                "gains",
                                result
                                    .get(
                                        android.hardware.camera2.CaptureResult
                                            .COLOR_CORRECTION_GAINS
                                    )
                                    .toString(),
                            )
                            .put(
                                "transform",
                                result
                                    .get(
                                        android.hardware.camera2.CaptureResult
                                            .COLOR_CORRECTION_TRANSFORM
                                    )
                                    .toString(),
                            )
                            .put(
                                "awb",
                                result.get(android.hardware.camera2.CaptureResult.CONTROL_AWB_STATE),
                            )
                            .put(
                                "physical",
                                result.get(
                                    android.hardware.camera2.CaptureResult
                                        .LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID
                                ),
                            )
                    File(folder, "$name-camera-color.json").writeText(info.toString(2))
                }
                File(folder, "$name-data-space.json")
                    .writeText(org.json.JSONArray(capture.inputDataSpaces()).toString())
                val before = a.editor.project.clips.size
                onMain { capture.start() }
                Thread.sleep(2200)
                check(capture.recording && !capture.paused)
                onMain { capture.stop() }
                check(a.editor.project.clips.size == before + 1)
                val clip = a.editor.project.clips.last()
                File(clip.path).copyTo(File(folder, "$name.mp4"), true)
                VideoDecoder(clip.path).use { decoder ->
                    val image =
                        decoder.frame(.8, 360, 640) ?: error("Cannot render camera recording")
                    File(folder, "$name-editor.png").outputStream().use {
                        image.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    image.recycle()
                }
            }
            onMain { capture.suspendCamera() }
            Thread.sleep(400)
            test("direct HLG camera reference bypasses the compositor") {
                DirectHdrTest.run(a, folder)
            }
            onMain {
                capture.suspendCamera()
                a.finish()
            }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", report.toString()) })
        } catch (e: Throwable) {
            activity?.let { onMain { it.finish() } }
            finish(
                Activity.RESULT_CANCELED,
                Bundle().apply {
                    putString("stream", report.toString() + "FAIL " + e.stackTraceToString())
                },
            )
        }
    }

    private fun references() {
        var activity: MainActivity? = null
        try {
            val a =
                startActivitySync(
                    Intent(targetContext, MainActivity::class.java)
                        .putExtra("testSession", true)
                        .putExtra("testSessionId", testSessionId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                    as MainActivity
            activity = a
            if (suite == "capture-controls") CaptureControlsTest.run(this, a, ::onMain, ::test)
            else if (suite == "media-editing") MediaEditingTest.run(this, a, ::onMain, ::test)
            else if (suite == "live-rotation") RecordingRotationTest.run(this, a, ::onMain, ::test)
            else if (suite == "playback")
                PlaybackTest.run(this, a, currentProject, ::onMain, ::test)
            else if (suite == "camera-preview") CameraPreviewTest.run(this, a, ::onMain, ::test)
            else ReferenceTests.run(this, a, ::onMain, ::test)
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", report.toString()) })
        } catch (e: Throwable) {
            finish(
                Activity.RESULT_CANCELED,
                Bundle().apply {
                    putString("stream", report.toString() + "FAIL " + e.stackTraceToString())
                },
            )
        } finally {
            activity?.let { a -> onMain { a.finish() } }
        }
    }

    private fun newUi() {
        var activity: MainActivity? = null
        try {
            val a =
                startActivitySync(
                    Intent(targetContext, MainActivity::class.java)
                        .putExtra("testSession", true)
                        .putExtra("testSessionId", testSessionId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                    as MainActivity
            activity = a
            val source = File(targetContext.cacheDir, "new-ui.mp4")
            context.assets.open("fixture.mp4").use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            onMain {
                a.editor.load(Project(clips = mutableListOf(ProjectStore.inspect(source))))
                a.playback.pause()
            }
            fun click(label: String) {
                onMain {
                    var view =
                        findView(a.root) {
                            it.contentDescription == label ||
                                (it is android.widget.TextView && it.text.toString() == label)
                        } ?: error("Missing $label")
                    while (
                        view !is ReferenceToggle &&
                            !view!!.hasOnClickListeners() &&
                            view.parent is android.view.View
                    ) view = view.parent as android.view.View
                    check(view.performClick()) { "Cannot click $label" }
                }
                Thread.sleep(140)
            }
            for (orientation in
                listOf(
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                )) {
                val name =
                    if (orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) "portrait"
                    else "landscape"
                onMain {
                    a.panels.close()
                    a.requestedOrientation = orientation
                }
                Thread.sleep(900)
                test("$name live effects sheet expands, toggles and clears immediately") {
                    var chain = listOf(Effect(kind = "Kaleidoscope"))
                    onMain { a.panels.captureEffects(chain) { chain = it } }
                    Thread.sleep(180)
                    screenshot("new-live-$name", a)
                    val panel = a.root.getChildAt(a.root.childCount - 1)
                    val height = panel.height
                    click("Expand effects")
                    check(panel.height > height)
                    screenshot("new-live-expanded-$name", a)
                    click("Kaleidoscope enabled")
                    Thread.sleep(150)
                    check(chain.single().enabled == false)
                    click("Clear all effects")
                    Thread.sleep(150)
                    check(chain.isEmpty())
                    click("Done")
                    check(!a.panels.isOpen)
                }
                test("$name Widescreen controls retain independent parameters") {
                    onMain { a.panels.videoEffects(Effect(kind = "Widescreen 1953")) }
                    Thread.sleep(180)
                    check(
                        findView(a.root) {
                            it is android.widget.TextView && it.text.toString() == "Flares"
                        } != null
                    )
                    screenshot("new-widescreen-$name", a)
                    onMain { a.panels.close() }
                }
                test("$name crop exposes 4:5 and separate quarter turn") {
                    onMain { a.panels.effect("Crop") }
                    Thread.sleep(180)
                    onMain {
                        val label =
                            findView(a.root) {
                                it is android.widget.TextView &&
                                    it.text.toString().startsWith("Original")
                            }!!
                        (label.parent as android.view.View).performClick()
                    }
                    Thread.sleep(180)
                    val choice =
                        uiAutomation.rootInActiveWindow
                            .findAccessibilityNodeInfosByText("4:5")
                            .firstOrNull() ?: error("Missing 4:5 crop choice")
                    check(
                        choice.performAction(
                            android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK
                        )
                    )
                    Thread.sleep(200)
                    val preview = a.playback.previewProject ?: error("Crop preview missing")
                    check(abs(preview.width.toFloat() / preview.height - .8f) < .01f)
                    if (name == "portrait")
                        check(
                            findView(a.root) {
                                it is android.widget.TextView && it.text.toString() == "Inspect"
                            } != null
                        )
                    click("Rotate 90 degrees")
                    screenshot("new-crop-$name", a)
                    click("Apply")
                    check(
                        a.editor.project.clips
                            .single()
                            .effects
                            .last { it.kind == "Crop" }
                            .number("quarter") == 1f
                    )
                    onMain { a.panels.effect("Crop") }
                    Thread.sleep(100)
                    onMain {
                        val label =
                            findView(a.root) {
                                it is android.widget.TextView &&
                                    it.text.toString().startsWith("Original")
                            }!!
                        (label.parent as android.view.View).performClick()
                    }
                    Thread.sleep(150)
                    val original =
                        uiAutomation.rootInActiveWindow
                            .findAccessibilityNodeInfosByText("Original")
                            .first()
                    check(
                        original.performAction(
                            android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK
                        )
                    )
                    Thread.sleep(150)
                    click("Apply")
                    check(a.editor.project.width == 640 && a.editor.project.height == 360)
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

    private fun composition() {
        try {
            for (hint in listOf(0, 90, 180, 270)) test(
                "composition pixels match every layout, flip and selfie mirror at $hint degrees"
            ) {
                val reader =
                    android.media.ImageReader.newInstance(
                        if (hint % 180 == 0) 320 else 180,
                        if (hint % 180 == 0) 180 else 320,
                        android.graphics.PixelFormat.RGBA_8888,
                        3,
                    )
                val thread = android.os.HandlerThread("composition-test").apply { start() }
                reader.setOnImageAvailableListener(
                    { it.acquireLatestImage()?.close() },
                    android.os.Handler(thread.looper),
                )
                val file = File(targetContext.cacheDir, "composition-$hint.mp4")
                val recorder =
                    if (android.os.Build.VERSION.SDK_INT >= 31)
                        android.media.MediaRecorder(targetContext)
                    else android.media.MediaRecorder()
                var renderer: CameraComposition? = null
                var started = false
                val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
                val centers = mutableListOf<Triple<Double, Int, Boolean>>()
                try {
                    recorder.setVideoSource(android.media.MediaRecorder.VideoSource.SURFACE)
                    recorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                    recorder.setOutputFile(file.path)
                    recorder.setVideoEncoder(android.media.MediaRecorder.VideoEncoder.H264)
                    recorder.setVideoSize(320, 180)
                    recorder.setVideoFrameRate(30)
                    recorder.setVideoEncodingBitRate(1000000)
                    recorder.setOrientationHint(hint)
                    recorder.prepare()
                    renderer =
                        CameraComposition(reader.surface, reader.width, reader.height) {
                            failure.set(it)
                        }
                    val inputs =
                        listOf(
                            renderer.input(320, 180, 0, false),
                            renderer.input(320, 180, 0, true),
                        )
                    renderer.record(recorder.surface, 320, 180, hint)
                    recorder.start()
                    started = true
                    val begin = android.os.SystemClock.elapsedRealtimeNanos()
                    for (flip in listOf(false, true)) for (mode in 0..5) {
                        renderer.configure(mode, flip, 0, reader.width, reader.height)
                        centers.add(
                            Triple(
                                (android.os.SystemClock.elapsedRealtimeNanos() - begin) / 1e9 + .25,
                                mode,
                                flip,
                            )
                        )
                        repeat(14) {
                            inputs.forEachIndexed { index, surface ->
                                val canvas = surface.lockCanvas(null)
                                canvas.drawColor(if (index == 0) Color.RED else Color.BLUE)
                                if (index == 1)
                                    canvas.drawRect(
                                        0f,
                                        0f,
                                        16f,
                                        180f,
                                        Paint().apply { color = Color.WHITE },
                                    )
                                surface.unlockCanvasAndPost(canvas)
                            }
                            Thread.sleep(36)
                        }
                    }
                    val selfie = renderer.snapshot(1, 320, 180) ?: error("No front input snapshot")
                    try {
                        check(Color.red(selfie.getPixel(310, 90)) > 230)
                        check(Color.green(selfie.getPixel(310, 90)) > 230)
                        check(Color.red(selfie.getPixel(10, 90)) < 40)
                        check(Color.blue(selfie.getPixel(10, 90)) > 200)
                    } finally {
                        selfie.recycle()
                    }
                    renderer.record(null)
                    recorder.stop()
                    started = false
                    failure.get()?.let { throw it }
                    VideoDecoder(file.path).use { decoder ->
                        for ((time, mode, flip) in centers) {
                            val image =
                                decoder.frame(time, 640, 640) ?: error("Missing composed frame")
                            try {
                                val points =
                                    when (mode) {
                                        0 -> listOf(Triple(.2f, .7f, 0), Triple(.8f, .2f, 1))
                                        1 -> listOf(Triple(.2f, .5f, 0), Triple(.8f, .5f, 1))
                                        2 -> listOf(Triple(.5f, .2f, 0), Triple(.5f, .8f, 1))
                                        3 -> listOf(Triple(.15f, .15f, 0), Triple(.85f, .85f, 1))
                                        5 -> listOf(Triple(.21f, .64f, 1), Triple(.045f, .35f, 0),
                                            Triple(.37f, .93f, 0), Triple(.8f, .2f, 0))
                                        else -> listOf(Triple(.2f, .5f, 0), Triple(.8f, .5f, 0))
                                    }
                                for ((x, y, index) in points) {
                                    val pixel =
                                        image.getPixel(
                                            (x * image.width).toInt(),
                                            (y * image.height).toInt(),
                                        )
                                    val red = (index == 0) != flip
                                    check(
                                        if (red) Color.red(pixel) > 200 && Color.blue(pixel) < 40
                                        else Color.blue(pixel) > 200 && Color.red(pixel) < 40
                                    ) {
                                        "Composition $hint mode=$mode flip=$flip at $x,$y = ${pixel.toUInt().toString(16)}"
                                    }
                                }
                            } finally {
                                image.recycle()
                            }
                        }
                    }
                } finally {
                    if (started) runCatching { recorder.stop() }
                    renderer?.close()
                    recorder.release()
                    reader.close()
                    thread.quitSafely()
                    file.delete()
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
        }
    }

    private fun cameraGaps() {
        var activity: MainActivity? = null
        try {
            val a =
                startActivitySync(
                    Intent(targetContext, MainActivity::class.java)
                        .putExtra("testSession", true)
                        .putExtra("testSessionId", testSessionId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                    as MainActivity
            activity = a
            onMain { a.showCapture() }
            Thread.sleep(1800)
            val capture =
                (0 until a.root.childCount)
                    .map { a.root.getChildAt(it) }
                    .filterIsInstance<Capture>()
                    .single()
            val manager = a.getSystemService(android.hardware.camera2.CameraManager::class.java)
            val images =
                File(targetContext.getExternalFilesDir(null), "gap-screens").apply { mkdirs() }
            fun snapshot(name: String) {
                val image = uiAutomation.takeScreenshot() ?: error("Cannot capture $name")
                File(images, "$name.png").outputStream().use {
                    image.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                image.recycle()
            }
            fun fileTest(hdr: Boolean) {
                val before = a.editor.project.clips.size
                onMain { capture.start() }
                Thread.sleep(2200)
                check(capture.recording && !capture.paused) { "Camera did not start" }
                if (capture.concurrentInputSizes().size == 2) {
                    onMain { capture.setDualLayout(2) }
                    Thread.sleep(1000)
                    onMain { capture.setDualLayout(0) }
                    Thread.sleep(800)
                    onMain { capture.setDualLayout(3) }
                    Thread.sleep(500)
                    onMain { capture.setDualLayout(4) }
                    Thread.sleep(500)
                    onMain { capture.setDualLayout(0) }
                }
                onMain { capture.pause() }
                Thread.sleep(400)
                onMain { capture.pause() }
                Thread.sleep(1600)
                snapshot(if (hdr) "hdr-recording" else "dual-recording")
                onMain { capture.stop() }
                check(a.editor.project.clips.size == before + 1) { "Recording was not saved" }
                val clip = a.editor.project.clips.last()
                if (hdr)
                    File(images, "hdr-camera-data-space.json")
                        .writeText(org.json.JSONArray(capture.inputDataSpaces()).toString())
                val ex = android.media.MediaExtractor()
                try {
                    ex.setDataSource(clip.path)
                    val ranges = mutableMapOf<String, Pair<Long, Long>>()
                    for (index in 0 until ex.trackCount) {
                        val f = ex.getTrackFormat(index)
                        val mime = f.getString(android.media.MediaFormat.KEY_MIME)!!
                        if (mime.startsWith("video/")) {
                            if (hdr) {
                                check(mime == "video/hevc")
                                check(
                                    f.getInteger(android.media.MediaFormat.KEY_PROFILE) ==
                                        android.media.MediaCodecInfo.CodecProfileLevel
                                            .HEVCProfileMain10
                                )
                                check(
                                    f.getInteger(android.media.MediaFormat.KEY_COLOR_TRANSFER) ==
                                        android.media.MediaFormat.COLOR_TRANSFER_HLG
                                ) {
                                    "Missing HLG transfer: $f"
                                }
                                check(
                                    f.getInteger(android.media.MediaFormat.KEY_COLOR_STANDARD) ==
                                        android.media.MediaFormat.COLOR_STANDARD_BT2020
                                )
                            }
                            File(images, if (hdr) "hdr-format.txt" else "dual-format.txt")
                                .writeText(f.toString())
                        }
                        ex.selectTrack(index)
                        var first = -1L
                        var last = -1L
                        var count = 0
                        while (ex.sampleTime >= 0) {
                            if (first < 0) first = ex.sampleTime
                            if (mime.startsWith("audio/"))
                                check(ex.sampleTime >= last) { "Audio timestamps moved backwards" }
                            last = max(last, ex.sampleTime)
                            count++
                            ex.advance()
                        }
                        check(count > 20) { "Empty $mime" }
                        ranges[mime.substringBefore('/')] = first to last
                        ex.unselectTrack(index)
                        ex.seekTo(0, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    }
                    check(ranges.keys.containsAll(listOf("video", "audio")))
                    check(
                        abs(ranges.getValue("video").second - ranges.getValue("audio").second) <
                            300000
                    ) {
                        "AV timing: $ranges"
                    }
                } finally {
                    ex.release()
                }
                VideoDecoder(clip.path).use { decoder ->
                    val bitmap =
                        decoder.frame(.7, 320, 640) ?: error("Recorded frame cannot be decoded")
                    File(images, if (hdr) "hdr-frame.png" else "dual-frame.png")
                        .outputStream()
                        .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                if (hdr) {
                    val project =
                        Project(
                            width = 180,
                            height = 320,
                            clips =
                                mutableListOf(clip.duplicate().apply { sourceOut = sourceIn + 1.0 }),
                        )
                    val output = File(images, "hdr-editor-export.mp4")
                    Export(AudioCache(File(targetContext.cacheDir, "hdr-export-audio"))).run(
                        project,
                        output,
                        180,
                        320,
                    ) {}
                    VideoDecoder(output.path).use { decoder ->
                        val bitmap = decoder.frame(.5, 180, 320) ?: error("HDR edit did not export")
                        bitmap.recycle()
                    }
                }
                File(clip.path).copyTo(File(images, if (hdr) "hdr.mp4" else "dual.mp4"), true)
            }
            if (DualCamera.pair(manager) != null)
                test("concurrent cameras record layout changes and pause with synchronized audio") {
                    onMain { capture.setDualLayout(1) }
                    Thread.sleep(2200)
                    snapshot("dual-preview")
                    check(capture.concurrentInputSizes().size == 2)
                    File(images, "concurrent-input-sizes.txt")
                        .writeText(capture.concurrentInputSizes().toString())
                    fileTest(false)
                    fun tap(id: String) {
                        onMain {
                            val bounds = capture.controlBounds(id) ?: error("Missing $id")
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
                                capture.dispatchTouchEvent(event)
                                event.recycle()
                            }
                        }
                        Thread.sleep(160)
                    }
                    onMain { capture.setDualLayout(3) }
                    tap("dual")
                    snapshot("dual-diagonal-toolbar")
                    tap("dualReset")
                    check(capture.concurrentInputSizes().isEmpty()) {
                        "Circular arrow did not return to normal capture"
                    }
                    snapshot("dual-normal-toolbar")
                    tap("dualDone")
                    onMain { capture.setDualLayout(4) }
                    Thread.sleep(1600)
                }
            val id =
                manager.cameraIdList.first {
                    manager
                        .getCameraCharacteristics(it)
                        .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == 1
                }
            if (HdrRecorder.supported(manager, id))
                test("HLG recording has HEVC BT2020 metadata and decodes for the editor") {
                    onMain { capture.setHdr(true) }
                    Thread.sleep(2000)
                    snapshot("hdr-preview")
                    fileTest(true)
                    onMain { capture.setHdr(false) }
                }
            if (DualCamera.pair(manager) != null)
                test("HDR concurrent profiles record together or retain a working single camera") {
                    onMain { capture.setDualLayout(1) }
                    Thread.sleep(1700)
                    onMain { capture.setHdr(true) }
                    Thread.sleep(2000)
                    File(images, "hdr-concurrent-input-sizes.txt")
                        .writeText(capture.concurrentInputSizes().toString())
                    fileTest(true)
                    onMain {
                        capture.setHdr(false)
                        capture.setDualLayout(4)
                    }
                }
            test("live capture effect changes save their time ranges in the recorded clip") {
                fun click(label: String) {
                    onMain {
                        var view =
                            findView(a.root) {
                                it.contentDescription == label ||
                                    it is android.widget.TextView && it.text.toString() == label
                            } ?: error("Missing live effect control $label")
                        while (
                            view !is ReferenceToggle &&
                                !view.hasOnClickListeners() &&
                                view.parent is android.view.View
                        ) view = view.parent as android.view.View
                        check(view.performClick())
                    }
                    Thread.sleep(180)
                }
                Thread.sleep(1200)
                onMain { capture.effects() }
                click("+ Add")
                click("Camera & frame")
                click("Kaleidoscope")
                click("Done")
                val before = a.editor.project.clips.size
                onMain { capture.start() }
                Thread.sleep(1600)
                check(capture.recording && !capture.paused)
                onMain { capture.effects() }
                Thread.sleep(150)
                click("Kaleidoscope enabled")
                click("Clear all effects")
                click("Done")
                Thread.sleep(1000)
                onMain { capture.stop() }
                check(a.editor.project.clips.size == before + 1)
                val clip = a.editor.project.clips.last()
                val effect = clip.effects.single { it.kind == "Kaleidoscope" }
                check(effect.from == 0.0 && effect.to > .8 && effect.to < clip.duration - .5) {
                    "Live range ${effect.from}..${effect.to} in ${clip.duration}"
                }
            }
            onMain {
                capture.suspendCamera()
                a.showEditor()
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

    private fun gaps() {
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
            test("camera concurrency and HDR capabilities are enumerated") {
                val manager =
                    targetContext.getSystemService(
                        android.hardware.camera2.CameraManager::class.java
                    )
                val result = org.json.JSONObject()
                result.put(
                    "concurrent",
                    if (android.os.Build.VERSION.SDK_INT >= 30)
                        org.json.JSONArray(manager.concurrentCameraIds.map { it.toList() })
                    else org.json.JSONArray(),
                )
                result.put(
                    "cameras",
                    org.json.JSONArray(
                        manager.cameraIdList.map { id ->
                            val c = manager.getCameraCharacteristics(id)
                            org.json
                                .JSONObject()
                                .put("id", id)
                                .put(
                                    "facing",
                                    c.get(
                                        android.hardware.camera2.CameraCharacteristics.LENS_FACING
                                    ),
                                )
                                .put(
                                    "sensor",
                                    c.get(
                                        android.hardware.camera2.CameraCharacteristics
                                            .SENSOR_ORIENTATION
                                    ),
                                )
                                .put(
                                    "hdr",
                                    if (android.os.Build.VERSION.SDK_INT >= 33)
                                        c.get(
                                                android.hardware.camera2.CameraCharacteristics
                                                    .REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES
                                            )
                                            ?.supportedProfiles
                                            ?.toList()
                                            .toString()
                                    else "[]",
                                )
                        }
                    ),
                )
                File(targetContext.getExternalFilesDir(null), "gap-capabilities.json")
                    .writeText(result.toString(2))
            }
            for ((name, kind) in listOf("Noise reduction" to 0, "Studio restoration" to 1)) {
                test("$name loads and processes through the Android C API") {
                    check(AudioModels.ready(name))
                    val input =
                        FloatArray(48000) { i ->
                            (.1 * sin(i * 2 * PI * 220 / 48000) +
                                    .02 * sin(i * 2 * PI * 4100 / 48000))
                                .toFloat()
                        }
                    val start = System.nanoTime()
                    val handle =
                        Native.neuralLoad(AudioModels.directory(name).absolutePath, kind, 2)
                    check(handle != 0L)
                    try {
                        val output = Native.neuralProcess(handle, input)
                        check(output.size == input.size && output.all { it.isFinite() })
                        check(output.any { abs(it) > .00001f })
                        check(!output.contentEquals(input))
                        val elapsed = (System.nanoTime() - start) / 1e9
                        File(targetContext.getExternalFilesDir(null), "gap-audio-$kind.json")
                            .writeText("{\"seconds\":$elapsed,\"samples\":${output.size}}")
                    } finally {
                        Native.neuralFree(handle)
                    }
                }
            }
            val speech = File(targetContext.cacheDir, "gap-speech.wav")
            context.assets.open("speech.wav").use { input ->
                speech.outputStream().use { input.copyTo(it) }
            }
            val sourceClip = ProjectStore.inspect(speech).apply { sourceOut = min(sourceOut, 1.0) }
            val dry = Project(clips = mutableListOf(sourceClip))
            val cache = AudioCache(File(targetContext.cacheDir, "gap-audio"))
            fun samples(project: Project) =
                AudioMixer(project, cache).use {
                    it.prepare()
                    it.read((project.duration * 48000).roundToInt())
                }
            val original = samples(dry)
            for (name in AudioModels.effects) test(
                "$name mixer preserves effect ranges, mute and prepared cache"
            ) {
                val wet =
                    dry.duplicate().apply {
                        clips.single().effects.add(Effect(kind = name, from = .25, to = .75))
                    }
                val output = samples(wet)
                check(output.size == original.size && output.all { it.isFinite() })
                var error = 0.0
                for (i in output.indices) {
                    val time = i / 2.0 / 48000
                    val delta = abs(output[i] - original[i])
                    if (time < .2 || time > .8)
                        check(delta < .000001f) { "Effect escaped its range at $time" }
                    else error += delta
                }
                check(error > .001)
                check(samples(wet).contentEquals(output))
                wet.clips.single().audio = false
                check(samples(wet).all { abs(it) < .000001f })
            }
            test("three neural audio stages retain their order and share one engine safely") {
                val project =
                    dry.duplicate().apply {
                        clips.single().effects.addAll(AudioModels.effects.map { Effect(kind = it) })
                    }
                val output = samples(project)
                check(output.size == original.size && output.all { it.isFinite() })
                check(output.any { abs(it) > .00001f })
            }
            test("Kaleidoscope and Widescreen preserve zero strength and change pixels") {
                val source = Bitmap.createBitmap(128, 96, Bitmap.Config.ARGB_8888)
                for (y in 0 until 96) for (x in 0 until 128) source.setPixel(
                    x,
                    y,
                    Color.rgb(x * 2, y * 2, if (x > 64) 255 else 0),
                )
                for (kind in listOf("Kaleidoscope", "Widescreen 1953")) {
                    val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
                    try {
                        Effects.correct(bitmap, Effect(kind = kind), 0f)
                        check(bitmap.sameAs(source))
                        Effects.correct(bitmap, Effect(kind = kind), 1f)
                        check(!bitmap.sameAs(source))
                        if (kind == "Widescreen 1953") check(bitmap.getPixel(64, 1) == Color.BLACK)
                    } finally {
                        bitmap.recycle()
                    }
                }
                source.recycle()
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
            activity?.let { a -> onMain { a.finish() } }
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
