package org.yourec.app

import android.app.Instrumentation
import android.content.pm.ActivityInfo
import android.graphics.*
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File
import kotlin.math.abs

object ReferenceTests {
    fun run(
        instrumentation: Instrumentation,
        a: MainActivity,
        onMain: (() -> Unit) -> Unit,
        test: (String, () -> Unit) -> Unit,
    ) {
        val folder = File(a.getExternalFilesDir(null), "reference-checks").apply { mkdirs() }
        fun save(bitmap: Bitmap, name: String) {
            File(folder, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        fun find(view: View, label: String): View? {
            if (
                view.contentDescription == label ||
                    view is TextView && view.text.toString() == label
            )
                return view
            if (view is ViewGroup)
                for (i in 0 until view.childCount) find(view.getChildAt(i), label)?.let {
                    return it
                }
            return null
        }
        fun snapshot(name: String) {
            Thread.sleep(250)
            onMain {
                val image =
                    Bitmap.createBitmap(a.root.width, a.root.height, Bitmap.Config.ARGB_8888)
                a.root.draw(Canvas(image))
                save(image, name)
                image.recycle()
            }
        }
        test(
            "Recently deleted persists complete edits and restores without changing shared media"
        ) {
            val source = File(a.store.media, "shared.mp4")
            instrumentation.context.assets.open("fixture.mp4").use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            val original = source.readBytes()
            val project =
                Project(
                    name = "A project with a long name for two lines",
                    clips = mutableListOf(ProjectStore.inspect(source)),
                )
            project.clips.single().effects.add(Effect(kind = "RGB Offset"))
            project.clips.single().effects.add(Effect(kind = "Kaleidoscope"))
            val encoded = a.store.encode(project).toString()
            a.store.save(project, false)
            a.store.delete(project)
            check(a.store.list().none { it.id == project.id })
            val restoredStore = ProjectStore(source.parentFile!!.parentFile!!)
            val deleted = restoredStore.recentlyDeleted().single { it.id == project.id }
            check(restoredStore.encode(deleted).toString() == encoded)
            restoredStore.restore(deleted)
            check(
                restoredStore
                    .encode(restoredStore.list().single { it.id == project.id })
                    .toString() == encoded
            )
            check(restoredStore.recentlyDeleted().none { it.id == project.id })
            val copy = project.duplicate().copy(id = uid(), name = "Deleted project")
            restoredStore.save(copy, false)
            restoredStore.delete(copy)
            restoredStore.erase(copy)
            check(restoredStore.list().none { it.id == copy.id })
            check(restoredStore.recentlyDeleted().none { it.id == copy.id })
            check(source.readBytes().contentEquals(original))
            val trash = Project(name = "Recover this project")
            a.store.save(trash, false)
            a.store.delete(trash)
            onMain {
                a.editor.load(project)
                a.playback.pause()
            }
        }
        test("Legacy selfie orientation reaches decoding, timeline thumbnails and effects") {
            val source = File(a.store.media, "shared.mp4")
            val original = source.readBytes()
            val before = VideoDecoder(source.path).use { it.frame(.7, 240, 135)!! }
            val marker = File(source.path + ".mirror")
            val clip = ProjectStore.inspect(source)
            val index = MediaIndex(a.audioCache) {}
            try {
                marker.writeText("horizontal\n")
                val expected = SourceMirror.flip(before.copy(Bitmap.Config.ARGB_8888, true))
                val decoded = VideoDecoder(source.path).use { it.frame(.7, 240, 135)!! }
                check(decoded.sameAs(expected)) { "Decoded selfie was not mirrored exactly once" }
                decoded.recycle()
                clip.effects.add(Effect(kind = "Kaleidoscope"))
                Effects.correct(expected, clip.effects.single(), 1f)
                Frames().use { frames ->
                    val rendered = frames.render(Project(clips = mutableListOf(clip)), .7, 240, 135)
                    check(rendered.sameAs(expected)) { "Mirror must precede creative effects" }
                    rendered.recycle()
                }
                expected.recycle()
                onMain { index.request(clip) }
                var thumbnail: Bitmap? = null
                val deadline = android.os.SystemClock.uptimeMillis() + 5000
                while (thumbnail == null && android.os.SystemClock.uptimeMillis() < deadline) {
                    Thread.sleep(50)
                    onMain {
                        thumbnail =
                            index.thumbnails[source.path]
                                ?.takeIf { it.size == 12 }
                                ?.first()
                                ?.copy(Bitmap.Config.ARGB_8888, true)
                    }
                }
                val actual = thumbnail ?: error("Timeline thumbnails did not load")
                val reader = android.media.MediaMetadataRetriever()
                try {
                    reader.setDataSource(source.path)
                    val raw =
                        reader.getScaledFrameAtTime(
                            0,
                            android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            144,
                            81,
                        )!!
                    val mirrored = SourceMirror.flip(raw)
                    check(actual.sameAs(mirrored)) { "Timeline selfie orientation differs" }
                    mirrored.recycle()
                } finally {
                    reader.release()
                    actual.recycle()
                }
                check(source.readBytes().contentEquals(original))
            } finally {
                marker.delete()
                before.recycle()
                index.close()
            }
        }
        test("Kaleidoscope keeps subject detail, original colors and the requested mirror count") {
            val source = Bitmap.createBitmap(240, 400, Bitmap.Config.ARGB_8888)
            for (y in 0 until source.height) for (x in 0 until source.width) source.setPixel(
                x,
                y,
                Color.rgb(x, y * 255 / 400, (x + y) % 256),
            )
            for (count in listOf(2, 4, 6, 8, 24)) {
                val output = source.copy(Bitmap.Config.ARGB_8888, true)
                val effect =
                    Effect(
                        kind = "Kaleidoscope",
                        values =
                            mutableMapOf("segments" to "$count", "centerY" to ".5", "zoom" to "1"),
                    )
                Effects.correct(output, effect, 0f)
                check(output.sameAs(source))
                Effects.correct(output, effect, 1f)
                check(!output.sameAs(source))
                if (count == 2) {
                    for (y in 10 until 190 step 20) for (x in 10 until 230 step 20) {
                        val expected = output.getPixel(x, y)
                        for (actual in
                            listOf(
                                output.getPixel(x, y),
                                output.getPixel(x, 399 - y),
                            )) for (shift in listOf(0, 8, 16)) check(
                            abs((expected shr shift and 255) - (actual shr shift and 255)) <= 1
                        )
                    }
                }
                save(output, "kaleidoscope-$count")
                output.recycle()
            }
            source.recycle()
        }
        test("Default Kaleidoscope joins four sections at the image center") {
            val source = Bitmap.createBitmap(113, 193, Bitmap.Config.ARGB_8888)
            for (y in 0 until source.height) for (x in 0 until source.width) source.setPixel(
                x,
                y,
                Color.rgb(x * 2, y, (x * 3 + y) % 256),
            )
            val output = source.copy(Bitmap.Config.ARGB_8888, true)
            val four = source.copy(Bitmap.Config.ARGB_8888, true)
            try {
                Effects.correct(output, Effect(kind = "Kaleidoscope"), 1f)
                Effects.correct(
                    four,
                    Effect(kind = "Kaleidoscope", values = mutableMapOf("segments" to "4")),
                    1f,
                )
                check(output.sameAs(four)) { "Default still uses two sections" }
                for (dx in 3 until 56 step 7) for (dy in 3 until 96 step 11) {
                    val expected = output.getPixel(56 - dx, 96 - dy)
                    for ((x, y) in
                        listOf(
                            56 + dx to 96 - dy,
                            56 - dx to 96 + dy,
                            56 + dx to 96 + dy,
                        )) for (shift in listOf(0, 8, 16)) check(
                        abs(
                            (expected shr shift and 255) - (output.getPixel(x, y) shr shift and 255)
                        ) <= 1
                    ) {
                        "Four sections do not join at the image center"
                    }
                }
            } finally {
                source.recycle()
                output.recycle()
                four.recycle()
            }
        }
        test("Kaleidoscope Center moves the mirror junction without panning its source") {
            val source = Bitmap.createBitmap(113, 193, Bitmap.Config.ARGB_8888)
            for (y in 0 until source.height) for (x in 0 until source.width) source.setPixel(
                x,
                y,
                Color.rgb(x * 2, y, (x * 3 + y) % 256),
            )
            fun close(a: Int, b: Int) =
                listOf(0, 8, 16).all { abs((a shr it and 255) - (b shr it and 255)) <= 1 }
            fun render(count: Int, rotation: Int, x: Float, y: Float): Bitmap =
                source.copy(Bitmap.Config.ARGB_8888, true).apply {
                    Effects.correct(
                        this,
                        Effect(
                            kind = "Kaleidoscope",
                            values =
                                mutableMapOf(
                                    "segments" to "$count",
                                    "rotation" to "$rotation",
                                    "centerX" to "$x",
                                    "centerY" to "$y",
                                ),
                        ),
                        1f,
                    )
                }
            for (count in listOf(2, 4, 6)) for (rotation in listOf(0, 37, 90)) {
                val centered = render(count, rotation, .5f, .5f)
                val moved = render(count, rotation, .25f, .625f)
                try {
                    for (y in 8 until 160 step 7) for (x in 30 until 110 step 7) {
                        val expected = centered.getPixel(x, y)
                        val actual = moved.getPixel(x - 28, y + 24)
                        check(close(expected, actual)) {
                            "Junction did not move with Center: $count sections, $rotation degrees"
                        }
                    }
                    if (count == 4 && rotation == 0) {
                        for (dx in listOf(3, 9, 21)) for (dy in listOf(3, 13, 35)) {
                            val expected = moved.getPixel(28 - dx, 120 - dy)
                            check(close(moved.getPixel(28 + dx, 120 - dy), expected))
                            check(close(moved.getPixel(28 - dx, 120 + dy), expected))
                            check(close(moved.getPixel(28 + dx, 120 + dy), expected))
                        }
                        save(moved, "kaleidoscope-moved-center")
                    }
                    if (rotation == 0) {
                        val default = source.copy(Bitmap.Config.ARGB_8888, true)
                        try {
                            Effects.correct(
                                default,
                                Effect(
                                    kind = "Kaleidoscope",
                                    values = mutableMapOf("segments" to "$count"),
                                ),
                                1f,
                            )
                            check(default.sameAs(centered)) { "Default junction is off-center" }
                        } finally {
                            default.recycle()
                        }
                    }
                } finally {
                    centered.recycle()
                    moved.recycle()
                }
            }
            source.recycle()
        }
        test("RGB Offset shifts channel edges and preserves flat colors and alpha") {
            val source = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)
            source.eraseColor(Color.rgb(220, 190, 150))
            val canvas = Canvas(source)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(60, 45, 35) }
            canvas.drawOval(35f, 40f, 165f, 250f, paint)
            paint.color = Color.WHITE
            canvas.drawCircle(75f, 110f, 10f, paint)
            canvas.drawCircle(125f, 110f, 10f, paint)
            canvas.drawRect(75f, 185f, 125f, 195f, paint)
            val output = source.copy(Bitmap.Config.ARGB_8888, true)
            val effect =
                Effect(
                    kind = "RGB Offset",
                    values = mutableMapOf("distance" to ".05", "angle" to "0"),
                )
            Effects.correct(output, effect, 0f)
            check(output.sameAs(source))
            Effects.correct(output, effect, 1f)
            check(output.getPixel(5, 5) == source.getPixel(5, 5))
            for (y in 10 until 290 step 10) for (x in 10 until 190 step 10) {
                val actual = output.getPixel(x, y)
                check(Color.alpha(actual) == 255)
                check(Color.red(actual) == Color.red(source.getPixel(x - 10, y)))
                check(Color.green(actual) == Color.green(source.getPixel(x + 10, y)))
                check(Color.blue(actual) == Color.blue(source.getPixel(x - 10, y)))
            }
            for (count in listOf(2, 4)) {
                val combined = source.copy(Bitmap.Config.ARGB_8888, true)
                Effects.correct(combined, Effect(kind = "RGB Offset"), 1f)
                Effects.correct(
                    combined,
                    Effect(kind = "Kaleidoscope", values = mutableMapOf("segments" to "$count")),
                    1f,
                )
                save(combined, "combined-$count")
                combined.recycle()
            }
            source.recycle()
            output.recycle()
        }
        test("Project privacy, import and recovery controls work in both orientations") {
            for ((orientation, name) in
                listOf(
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to "portrait",
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to "landscape",
                )) {
                onMain { a.requestedOrientation = orientation }
                Thread.sleep(400)
                onMain { a.panels.projects() }
                snapshot("projects-$name")
                onMain {
                    check(find(a.root, "⇩ Import") != null)
                    check(find(a.root, "Privacy policy")!!.performClick())
                }
                Thread.sleep(100)
                onMain {
                    // The policy uses a dialog, outside the root view hierarchy.
                    check(
                        a.assets
                            .open("privacy.txt")
                            .bufferedReader()
                            .use { it.readText() }
                            .contains("locally")
                    )
                }
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                onMain { check(find(a.root, "Recently deleted (1)")!!.performClick()) }
                snapshot("deleted-$name")
                onMain { check(find(a.root, "Restore") != null) }
            }
            onMain {
                a.panels.projects(true)
                check(find(a.root, "Restore")!!.performClick())
                check(a.store.recentlyDeleted().isEmpty())
                a.panels.close(false)
                a.showEditor()
            }
            snapshot("effects-landscape")
            onMain { a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            Thread.sleep(400)
            snapshot("effects-portrait")
        }
    }
}
