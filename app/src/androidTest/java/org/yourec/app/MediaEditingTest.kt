package org.yourec.app

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
        onMain { a.panels.close(); a.playback.pause() }
    }
}
