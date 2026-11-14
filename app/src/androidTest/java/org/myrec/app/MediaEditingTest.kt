package org.myrec.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.*
import android.net.Uri
import android.os.SystemClock
import android.view.*
import android.widget.TextView
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

object MediaEditingTest {
    fun run(
        instrumentation: Instrumentation,
        a: MainActivity,
        onMain: (() -> Unit) -> Unit,
        test: (String, () -> Unit) -> Unit,
    ) {
        val folder = File(a.getExternalFilesDir(null), "media-editing-checks").apply { mkdirs() }
        val source = File(a.store.media, "media-editing.mp4")
        instrumentation.context.assets.open("fixture.mp4").use { input ->
            source.outputStream().use { input.copyTo(it) }
        }
        val wave = File(a.cacheDir, "import-audio.wav")
        val samples = 48000 * 3
        val data = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
        data.putInt(16).putShort(1).putShort(1).putInt(48000).putInt(96000)
        data.putShort(2).putShort(16).put("data".toByteArray()).putInt(samples * 2)
        repeat(samples) { data.putShort((sin(it * 440 * 2 * PI / 48000) * 8000).toInt().toShort()) }
        wave.writeBytes(data.array())
        fun find(view: View, label: String): View? {
            if (view.contentDescription == label || view is TextView && view.text.toString() == label)
                return view
            if (view is ViewGroup) for (i in 0 until view.childCount)
                find(view.getChildAt(i), label)?.let { return it }
            return null
        }
        fun bounds(id: String): RectF {
            var result: RectF? = null
            onMain { result = a.editorView.controlBounds(id) }
            return result ?: error("Missing editor control $id")
        }
        fun gesture(from: PointF, to: PointF = from) {
            onMain {
                val now = SystemClock.uptimeMillis()
                for (i in 0..8) {
                    val action = if (i == 0) MotionEvent.ACTION_DOWN
                        else if (i == 8) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
                    val e = MotionEvent.obtain(now, now + i * 30L, action,
                        from.x + (to.x - from.x) * i / 8, from.y + (to.y - from.y) * i / 8, 0)
                    a.editorView.dispatchTouchEvent(e)
                    e.recycle()
                }
            }
            Thread.sleep(150)
        }
        fun tap(id: String) {
            val r = bounds(id)
            gesture(PointF(r.centerX(), r.centerY()))
        }
        fun card(id: String, audio: Boolean = false): RectF {
            var result: RectF? = null
            onMain { result = a.editorView.clipBounds(id, audio) }
            return result ?: error("Clip $id is not accessible on the timeline")
        }
        fun boundary(time: Double): Float {
            var result = 0f
            onMain { result = a.editorView.boundaryPosition(time) }
            return result
        }
        fun center(r: RectF) = PointF(r.centerX(), r.centerY())
        fun hold(r: RectF) {
            val p = center(r)
            val now = SystemClock.uptimeMillis()
            onMain {
                val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, p.x, p.y, 0)
                a.editorView.dispatchTouchEvent(event); event.recycle()
            }
            Thread.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 100)
            onMain {
                val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, p.x, p.y, 0)
                a.editorView.dispatchTouchEvent(event); event.recycle()
            }
            Thread.sleep(150)
        }
        fun point(x: Float, y: Float): PointF {
            val v = a.editorView
            val landscape = v.width > v.height
            val w = if (landscape) 640f else 294f
            val h = if (landscape) 294f else 640f
            val unit = min(v.width / w, v.height / h)
            return PointF((v.width - w * unit) / 2 + x * unit,
                (v.height - h * unit) / 2 + y * unit)
        }
        fun snapshot(name: String) {
            onMain {
                val image = Bitmap.createBitmap(a.root.width, a.root.height, Bitmap.Config.ARGB_8888)
                a.root.draw(Canvas(image))
                File(folder, "$name.png").outputStream().use {
                    image.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                image.recycle()
            }
        }
        for ((name, orientation) in listOf(
            "portrait" to ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            "landscape" to ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
        )) {
            onMain {
                a.panels.close()
                a.requestedOrientation = orientation
                a.editor.load(Project(name = "Multitrack check",
                    clips = mutableListOf(ProjectStore.inspect(source))))
                a.editor.cursor = 1.0
                a.editor.split()
                a.editor.select(a.editor.project.clips.first())
                a.editor.scale = 10.0
            }
            Thread.sleep(600)
            test("$name copy and repeat paste retain independent sections and insert time") {
                snapshot("$name-copy")
                tap("copy")
                check(a.editor.selected.isEmpty() && a.editor.clipboard.size == 1)
                snapshot("$name-paste")
                val length = a.editor.project.duration
                onMain { a.playback.seek(length) }
                Thread.sleep(200)
                tap("paste")
                tap("paste")
                check(abs(a.editor.project.duration - length - 2) < .01)
                check(a.editor.project.clips.map { it.id }.distinct().size == 4)
                onMain { a.editor.undo(); a.editor.undo() }
                check(abs(a.editor.project.duration - length) < .01)
            }
            test("$name Move creates an overlapping track above video, cancels and commits") {
                val original = a.editor.project.duplicate()
                onMain {
                    a.editor.select(a.editor.project.clips.last())
                    a.playback.seek(1.5)
                }
                Thread.sleep(250)
                tap("move")
                val landscape = name == "landscape"
                val x = if (landscape) 320f else 165f
                val y = if (landscape) 172f else 428f
                gesture(point(x, y), point(x - 25, y - 35))
                check(a.editor.targets().single().track == 1)
                check(a.editor.project.active(.75).size == 2)
                snapshot("$name-moving")
                tap("moveCancel")
                check(a.editor.project == original)
                onMain {
                    a.editor.clearSelection()
                    a.editor.select(a.editor.project.clips.last())
                    a.playback.seek(1.5)
                }
                Thread.sleep(200)
                tap("move")
                gesture(point(x, y), point(x - 25, y - 35))
                tap("moveDone")
                check(a.editor.project.active(.75).size == 2)
                onMain { a.editor.clearSelection(); a.playback.seek(.75) }
                Thread.sleep(250)
                tap("dismissPaste")
                bounds("placement")
                snapshot("$name-multitrack")
                tap("placement")
                onMain {
                    check(find(a.root, "Bring to front") != null)
                    check(find(a.root, "Send to back") != null)
                    a.panels.close()
                }
            }
            test("$name horizontal overlap keeps both cards visible and the moved picture in front") {
                val before = Project(name = "Overlap rows", clips = mutableListOf(
                    Clip(path = source.path, sourceOut = 1.0),
                    Clip(path = source.path, sourceOut = 1.0, start = 1.0)))
                val first = before.clips[0].id
                val second = before.clips[1].id
                onMain {
                    a.editor.load(before.duplicate()); a.editor.cursor = 1.0; a.editor.scale = 5.0
                    a.editor.snap = true
                }
                Thread.sleep(250)
                gesture(center(card(first)))
                tap("move")
                val from = center(card(first))
                gesture(from, PointF(from.x + boundary(1.0) - boundary(0.0), from.y))
                check(abs(a.editor.project.clips.first { it.id == first }.start - 1.0) < .00001)
                check(a.editor.project.active(1.5).last().id == first)
                tap("moveDone")
                val top = card(first)
                val bottom = card(second)
                check(top.bottom < bottom.top)
                gesture(center(bottom))
                check(a.editor.selected == setOf(second))
                gesture(center(card(first)))
                check(a.editor.selected == setOf(first))
                snapshot("$name-overlap-rows")
                val saved = a.store.currentSession()
                val restored = Editor(saved.project).apply { load(saved.project, saved) }
                check(restored.project.active(1.5).last().id == first)
                restored.undo(); check(restored.project == before)
            }
            test("$name Move exposes neighbor modes, stable rows and exact boundary placement") {
                val before = Project(name = "Neighbor moves", clips = (0..2).map {
                    Clip(path = source.path, sourceOut = 1.0, start = it.toDouble())
                }.toMutableList())
                onMain {
                    a.editor.load(before.duplicate()); a.editor.cursor = 1.0; a.editor.scale = 5.0
                }
                Thread.sleep(250)
                gesture(center(card(before.clips[1].id)))
                hold(card(before.clips[0].id))
                check(a.editor.selected == setOf(before.clips[0].id, before.clips[1].id))
                hold(card(before.clips[0].id))
                check(a.editor.selected == setOf(before.clips[1].id))
                tap("move")
                tap("moveRight")
                check(a.editor.targets().map { it.id }.toSet() == before.clips.drop(1).map { it.id }.toSet())
                val prior = card(before.clips[1].id)
                tap("moveNext")
                check(abs(a.editor.project.clips[1].start - 3.0) < .00001)
                check(abs(card(before.clips[1].id).top - prior.top) < 1)
                tap("movePrevious")
                check(abs(a.editor.project.clips[1].start - 1.0) < .00001)
                tap("moveSelected")
                tap("moveChoose")
                val r = card(before.clips[1].id)
                gesture(PointF(boundary(0.0), r.centerY()))
                check(abs(a.editor.project.clips[1].start) < .00001)
                snapshot("$name-neighbor-move")
                tap("moveCancel")
                check(a.editor.project == before && !a.editor.canUndo)
            }
            test("$name audio picker adds an independent waveform at the requested cursor") {
                val before = a.editor.project.duplicate()
                onMain { a.playback.seek(.75); a.panels.addAudio() }
                Thread.sleep(200)
                snapshot("$name-add-audio")
                val filter = IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addDataType("audio/*")
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                val monitor = instrumentation.addMonitor(filter, Instrumentation.ActivityResult(
                    Activity.RESULT_OK, Intent().setData(Uri.fromFile(wave))), true)
                try {
                    onMain { (find(a.root, "Choose audio from Files") ?: error("No audio picker")).performClick() }
                    val deadline = SystemClock.uptimeMillis() + 8000
                    while (a.editor.project.clips.size == before.clips.size && SystemClock.uptimeMillis() < deadline)
                        Thread.sleep(100)
                    check(monitor.hits == 1)
                    val added = a.editor.project.clips.last()
                    check(a.editor.project.clips.dropLast(1) == before.clips)
                    check(added.audio && !added.video && !added.linked)
                    check(abs(added.start - .75) < .01 && abs(added.duration - 3.0) < .01)
                    check(added.track > before.clips.maxOf { it.track })
                    check(File(added.path).readBytes().contentEquals(wave.readBytes()))
                    check(a.store.current().clips.last() == added)
                    check(a.audioCache.waveform(added.path).any { it > .01f })
                    onMain { a.editor.clearSelection(); a.editorView.invalidate() }
                    Thread.sleep(400)
                    snapshot("$name-audio-waveform")
                } finally {
                    instrumentation.removeMonitor(monitor)
                }
            }
        }
        test("Move and Placement keep foreground colors consistent in preview, persistence and export") {
            fun colored(name: String, color: Int): Clip {
                val image = File(folder, "$name.png")
                val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                return Clip(path = source.path, sourceOut = 1.0, audio = false, effects = mutableListOf(
                    Effect(kind = "Image", to = 1.0, values = mutableMapOf("path" to image.path,
                        "x" to "0", "y" to "0", "size" to "1"))))
            }
            val red = colored("red", Color.RED)
            val blue = colored("blue", Color.BLUE).apply { start = 1.0 }
            val edit = Editor(Project(clips = mutableListOf(red, blue)))
            edit.select(red); edit.beginMove(); edit.updateMove(1.0, 60.0, false); edit.finishMove(false)
            val store = ProjectStore(File(a.cacheDir, "overlap-export-store"))
            store.save(edit.project)
            val restored = store.current()
            fun checkColor(bitmap: Bitmap, red: Boolean) {
                val c = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                check(if (red) Color.red(c) > 200 && Color.blue(c) < 50
                    else Color.blue(c) > 200 && Color.red(c) < 50) { "Wrong foreground: $c" }
            }
            Frames().use { frames ->
                val image = frames.render(restored, 1.5, 320, 180)
                try { checkColor(image, true) } finally { image.recycle() }
            }
            val output = File(folder, "overlap-export.mp4")
            Export(a.audioCache).run(restored, output, 320, 180) {}
            VideoDecoder(output.path).use { decoder ->
                val image = decoder.frame(1.5, 320, 180) ?: error("Missing exported overlap")
                try { checkColor(image, true) } finally { image.recycle() }
            }
            edit.clearSelection(); edit.select(edit.project.clips.first { it.id == red.id })
            edit.applyPlacements(mapOf(red.id to Effect(kind = "Placement")), mapOf(red.id to 0, blue.id to 1))
            Frames().use { frames ->
                val image = frames.render(edit.project, 1.5, 320, 180)
                try { checkColor(image, false) } finally { image.recycle() }
            }
            edit.undo()
            check(edit.project.active(1.5).last().id == red.id)
        }
        onMain { a.panels.close(); a.playback.pause() }
    }
}
