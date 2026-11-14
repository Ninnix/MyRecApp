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
        val snapSource = File(a.store.media, "snap-video.mp4")
        source.copyTo(snapSource, overwrite = true)
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
            test("$name Link confirms only the shared interval and persists independent outside fragments") {
                val video = Clip(path = source.path, sourceOut = 2.0, rate = .5, audio = false, linked = false)
                val sound = Clip(path = wave.path, sourceOut = 3.0, start = 2.0, video = false, linked = false)
                onMain {
                    a.editor.load(Project(clips = mutableListOf(video, sound)))
                    a.editor.cursor = 2.0; a.editor.scale = 10.0
                }
                Thread.sleep(200)
                gesture(center(card(video.id))); hold(card(sound.id, true))
                check(a.editor.selected == setOf(video.id, sound.id))
                val before = a.editor.project.duplicate()
                fun dialog(label: String) {
                    Thread.sleep(150)
                    onMain {
                        val dialog = a.linkDialog ?: error("No link dialog")
                        val button = if (label == "Cancel") android.app.AlertDialog.BUTTON_NEGATIVE
                            else android.app.AlertDialog.BUTTON_POSITIVE
                        check(dialog.getButton(button).performClick())
                    }
                    Thread.sleep(200)
                }
                tap("link"); dialog("Cancel")
                check(a.editor.project == before && !a.editor.canUndo)
                tap("link"); dialog("Link intersection")
                check(a.editor.targets().size == 2)
                check(a.editor.targets().all { abs(it.start - 2.0) < .00001 && abs(it.end - 4.0) < .00001 })
                check(a.editor.project.clips.filter { it.id !in a.editor.selected }.all { it.link == null })
                val saved = a.store.currentSession()
                val reopened = Editor(saved.project).apply { load(saved.project, saved) }
                reopened.select(reopened.project.clips.first { it.link != null })
                check(reopened.targets().size == 2)
                onMain { a.editor.undo() }
                check(a.editor.project == before)
            }
            test("$name Cut and Paste preserve independent music and Close gap only removes empty time") {
                val music = Clip(path = wave.path, sourceOut = 3.0, video = false, linked = false)
                onMain {
                    a.editor.load(Project(clips = (0..2).map {
                        Clip(path = source.path, sourceOut = 1.0, start = it.toDouble())
                    }.toMutableList().apply { add(music) }))
                    a.editor.cursor = 1.5; a.editor.scale = 5.0
                    a.editor.select(a.editor.project.clips[1])
                }
                Thread.sleep(200); tap("cut")
                check(a.editor.project.clips.single { it.id == music.id } == music)
                check(a.editor.project.clips.count { it.video } == 2)
                tap("paste")
                check(a.editor.project.clips.count { it.video } == 3)
                check(a.editor.project.clips.single { it.id == music.id } == music)
                onMain {
                    a.editorView.showPaste = false
                    a.editor.load(Project(clips = mutableListOf(
                        Clip(path = source.path, sourceOut = 1.0, start = 2.0, audio = false, linked = false))))
                    a.editor.cursor = 1.0
                }
                Thread.sleep(200); tap("closeGap")
                check(abs(a.editor.project.clips.single().start) < .00001)
                onMain { a.editor.undo() }
                check(abs(a.editor.project.clips.single().start - 2.0) < .00001)
            }
            test("$name Restore sync uses the original occurrence without moving the picture") {
                onMain {
                    a.editor.load(Project(clips = mutableListOf(ProjectStore.inspect(source))))
                    a.editor.cursor = .5; a.editor.scale = 10.0
                    a.editor.select(a.editor.project.clips.single())
                }
                Thread.sleep(200); tap("link")
                val sound = a.editor.project.clips.single { it.audio && !it.video }
                onMain { a.editor.clearSelection(); a.editor.select(sound) }
                Thread.sleep(150); tap("move"); tap("moveNext"); tap("moveDone")
                onMain { a.editor.select(a.editor.project.clips.first { it.id == sound.id }) }
                Thread.sleep(150)
                check(abs(a.editor.syncOffset()!!) > .01)
                val picture = a.editor.project.clips.first { it.video }.duplicate()
                tap("restoreSync")
                check(abs(a.editor.syncOffset()!!) < .00001)
                check(a.editor.project.clips.first { it.video } == picture)
            }
            test("$name Snap to cuts applies only after scrub release and uses the final finger position") {
                onMain {
                    a.editor.load(Project(clips = mutableListOf(
                        Clip(path = snapSource.path, sourceOut = 2.0, audio = false),
                        Clip(path = snapSource.path, sourceOut = 2.0, start = 2.0, audio = false))))
                    a.editor.cursor = 2.0; a.editor.scale = 4.0; a.editor.snap = true
                }
                Thread.sleep(250)
                for (whole in listOf(false, true)) for (enabled in listOf(true, false)) {
                    onMain { a.editor.wholeProject = whole; a.editor.cursor = 2.0; a.editorView.invalidate() }
                    Thread.sleep(150)
                    if (a.editor.snap != enabled) tap("snap")
                    val r = card(a.editor.project.clips.first().id)
                    val from = center(r)
                    val mid = if (whole) boundary(2.0) - (boundary(1.0) - boundary(0.0)) *
                        (6 / a.editorView.pointsPerSecond).toFloat() else
                        from.x + (boundary(1.0) - boundary(0.0)) * (6 / a.editorView.pointsPerSecond).toFloat()
                    val end = if (whole) boundary(2.0) - (boundary(1.0) - boundary(0.0)) *
                        (7 / a.editorView.pointsPerSecond).toFloat() else
                        from.x + (boundary(1.0) - boundary(0.0)) * (7 / a.editorView.pointsPerSecond).toFloat()
                    val expectedMid = 2.0 - 6 / a.editorView.pointsPerSecond
                    val expectedEnd = if (enabled) 2.0 else 2.0 - 7 / a.editorView.pointsPerSecond
                    onMain {
                        val now = SystemClock.uptimeMillis()
                        fun send(action: Int, x: Float, delay: Long) {
                            val event = MotionEvent.obtain(now, now + delay, action, x, from.y, 0)
                            a.editorView.dispatchTouchEvent(event); event.recycle()
                        }
                        send(MotionEvent.ACTION_DOWN, from.x, 0)
                        send(MotionEvent.ACTION_MOVE, mid, 30)
                        check(abs(a.editor.cursor - expectedMid) < .00001) { "Snapped before release: ${a.editor.cursor}" }
                        send(MotionEvent.ACTION_UP, end, 60)
                        check(abs(a.editor.cursor - expectedEnd) < .00001) { "Release: ${a.editor.cursor} vs $expectedEnd" }
                    }
                }
                onMain { a.editor.wholeProject = false; a.editor.snap = true }
            }
            test("$name ruler snaps to bookmarks and cuts while effects and clip selection do not attract the playhead") {
                onMain {
                    a.editor.cursor = 2.0; a.editor.scale = 4.0
                    a.editor.project.clips.first().effects.add(Effect(kind = "Noir", from = .7, to = .8))
                    a.editor.project.clips.first().marks.add(1.5)
                    a.editorView.invalidate()
                }
                Thread.sleep(150)
                val r = card(a.editor.project.clips.first().id)
                gesture(center(r))
                check(abs(a.editor.cursor - 2.0) < .00001)
                val y = point(0f, if (name == "landscape") 137f else 393f).y
                gesture(PointF(boundary(.75), y))
                check(abs(a.editor.cursor - .75) < .00001)
                gesture(PointF(boundary(1.975), y))
                check(abs(a.editor.cursor - 2.0) < .00001)
                tap("snap")
                gesture(PointF(boundary(1.975), y))
                check(abs(a.editor.cursor - 1.975) < .00001)
                gesture(PointF(boundary(1.52), y))
                check(abs(a.editor.cursor - 1.5) < .00001)
                tap("snap")
            }
            test("$name Mark aligns cuts, rejects subframe ranges, moves the end and keeps frame rounding with Off") {
                onMain { a.editor.clearSelection(); a.editor.cursor = 1.975; a.editorView.invalidate() }
                Thread.sleep(150)
                tap("mark")
                check(a.editor.mark == 2.0)
                onMain { a.editor.cursor = 2.01; a.editorView.invalidate() }
                Thread.sleep(100); tap("mark")
                check(a.editor.mark == 2.0 && a.editor.range == null)
                onMain { a.editor.cursor = 3.0; a.editorView.invalidate() }
                Thread.sleep(100); tap("mark")
                check(a.editor.range == 2.0..3.0)
                onMain { a.editor.cursor = 3.5; a.editorView.invalidate() }
                Thread.sleep(100); tap("mark")
                check(a.editor.range == 2.0..3.5 && a.editor.mark == null)
                onMain { a.editor.clearSelection(); a.editor.cursor = 1.975; a.editorView.invalidate() }
                Thread.sleep(100); tap("snap"); tap("mark")
                check(abs(a.editor.mark!! - 59.0 / 30) < .00001)
                check(!a.editor.canUndo)
                onMain { a.editor.clearSelection(); a.editor.snap = true }
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
                    check(added.track > (before.clips.filter { it.audio }.maxOfOrNull { it.track } ?: -1))
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
        test("mixed import reviews numbered order, reads photo orientation and commits one undo") {
            val photo = File(folder, "batch-photo.jpg")
            val bitmap = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint()
            for ((rect, color) in listOf(Rect(0, 0, 120, 80) to Color.RED,
                Rect(120, 0, 240, 80) to Color.GREEN, Rect(0, 80, 120, 160) to Color.BLUE,
                Rect(120, 80, 240, 160) to Color.YELLOW)) {
                paint.color = color; canvas.drawRect(rect, paint)
            }
            photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }; bitmap.recycle()
            android.media.ExifInterface(photo.path).apply {
                setAttribute(android.media.ExifInterface.TAG_ORIENTATION, "6"); saveAttributes()
            }
            val before = Project(name = "Batch import")
            onMain { a.editor.load(before.duplicate()) }
            val data = android.content.ClipData.newRawUri("Video", Uri.fromFile(source)).apply {
                addItem(android.content.ClipData.Item(Uri.fromFile(photo)))
            }
            val filter = IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply {
                addDataType("*/*"); addCategory(Intent.CATEGORY_OPENABLE)
            }
            val monitor = instrumentation.addMonitor(filter, Instrumentation.ActivityResult(
                Activity.RESULT_OK, Intent().apply { clipData = data }), true)
            try {
                onMain { a.importVideo() }
                Thread.sleep(250)
                onMain {
                    val dialog = a.importDialog ?: error("No import-order review")
                    check(a.editor.project == before)
                    (find(dialog.window!!.decorView, "Move item 2 up") ?: error("No reorder control")).performClick()
                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
                }
                val deadline = SystemClock.uptimeMillis() + 10000
                while (a.editor.project.clips.size != 2 && SystemClock.uptimeMillis() < deadline) Thread.sleep(100)
                check(monitor.hits == 1 && a.editor.project.clips.size == 2)
                val edit = a.editor.project.duplicate()
                check(edit.clips.first().photo && !edit.clips.first().audio)
                check(abs(edit.clips.first().duration - 3.0) < .00001)
                check(abs(edit.clips.last().start - 3.0) < .00001)
                check(edit.width == 160 && edit.height == 240)
                val restored = a.store.current()
                check(restored == edit)
                Frames().use { frames ->
                    val image = frames.render(restored, 1.0, 160, 240)
                    val color = image.getPixel(40, 60); image.recycle()
                    check(Color.blue(color) > 200 && Color.red(color) < 50) { "Photo orientation $color" }
                }
                val exported = File(folder, "photo-batch.mp4")
                Export(a.audioCache).run(restored, exported, 160, 240) {}
                VideoDecoder(exported.path).use { decoder ->
                    val image = decoder.frame(1.0, 160, 240) ?: error("No exported photo")
                    val color = image.getPixel(40, 60); image.recycle()
                    check(Color.blue(color) > 180 && Color.red(color) < 70)
                }
                onMain { a.editor.undo() }
                check(a.editor.project == before && !a.editor.canUndo)
                check(edit.clips.all { File(it.path).isFile })
                check(a.editor.session().originals.size == 2)
                onMain { a.editor.redo() }
                check(a.editor.project == edit)
            } finally { instrumentation.removeMonitor(monitor) }
        }
        test("Voiceover records PCM passes without pause gaps, saves an independent layer and undoes once") {
            val before = Project(name = "Voiceover", clips = mutableListOf(ProjectStore.inspect(source)))
            onMain { a.editor.load(before.duplicate()); a.editor.cursor = .5; a.voiceover() }
            fun voice(label: String) {
                onMain {
                    val view = a.root.also { check(a.voiceDraft != null) { "No voiceover draft" } }
                    check((find(view, label) ?: error("No voiceover control $label")).performClick())
                }
            }
            Thread.sleep(150)
            voice("Choose microphone")
            onMain {
                val work = a.voiceDraft!!
                val devices = work.microphoneDevices()
                val labels = MicrophoneInput.labels(devices.map { MicrophoneInput(it) })
                val menu = a.choices?.contentView ?: error("No microphone menu")
                val shown = mutableListOf<String>()
                fun collect(view: View) {
                    if (view is TextView) view.contentDescription?.toString()?.let { shown.add(it) }
                    if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i))
                }
                collect(menu)
                check(shown == labels && shown.distinct().size == shown.size) { "Microphone menu $shown, expected $labels" }
                check(devices.all { MicrophoneInput(it).usable })
                val selected = devices.indexOfFirst { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC }.coerceAtLeast(0)
                (find(menu, labels[selected]) ?: error("No physical microphone")).performClick()
                check(work.inputName == labels[selected])
            }
            for ((orientation, name) in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to "portrait",
                                               ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to "landscape")) {
                onMain { a.requestedOrientation = orientation }
                Thread.sleep(500)
                val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: error("No voiceover screenshot")
                File(folder, "voiceover-$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                screenshot.recycle()
                check(a.voiceDraft != null)
            }
            voice("Record"); Thread.sleep(650); voice("Stop")
            val first = a.voiceDraft!!.file.length()
            Thread.sleep(250)
            check(a.voiceDraft!!.file.length() == first)
            voice("Record more"); Thread.sleep(650); voice("Stop"); Thread.sleep(150)
            val draft = a.voiceDraft!!.file
            val duration = PcmWave.duration(draft) ?: error("No sample-exact PCM duration")
            check(duration > .7 && duration < 2.0)
            voice("Play"); Thread.sleep(150); voice("Stop playback")
            voice("Done")
            Thread.sleep(200)
            check(a.voiceDraft == null)
            val added = a.editor.project.clips.single { it.path != source.path }
            check(!added.video && added.audio && !added.linked && added.start == .5)
            check(abs(added.duration - duration) < .000001)
            check(!draft.exists())
            val decoded = a.audioCache.get(added.path)
            check(decoded.length() / 8 == ((duration * 48000).roundToLong()))
            val edit = a.editor.project.duplicate()
            check(a.store.current() == edit)
            onMain { a.editor.undo() }
            check(a.editor.project == before && !a.editor.canUndo && File(added.path).isFile)
            onMain { a.editor.redo() }
            check(a.editor.project == edit)
            check(File(a.store.root, "pending").listFiles()?.none { it.extension == "json" } != false)
        }
        test("cancelling a recorded voice draft preserves the project and removes only its unpublished file") {
            val before = a.editor.project.duplicate()
            onMain { a.voiceover() }
            Thread.sleep(150)
            onMain {
                val view = a.root
                (find(view, "Record") ?: error("No Record control")).performClick()
            }
            Thread.sleep(350)
            val file = a.voiceDraft!!.file
            onMain { (find(a.root, "Cancel") ?: error("No Cancel control")).performClick() }
            onMain {
                val dialog = a.voiceDraft!!.discardDialog ?: error("No discard confirmation")
                dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
            }
            onMain {
                check(a.voiceDraft != null && file.exists() && a.editor.project == before)
                (find(a.root, "Start over") ?: error("No Start over control")).performClick()
                a.voiceDraft!!.discardDialog!!.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            }
            onMain {
                check(a.voiceDraft != null && PcmWave.duration(file) == null)
                (find(a.root, "Record") ?: error("No Record control")).performClick()
            }
            Thread.sleep(300)
            onMain {
                (find(a.root, "Cancel") ?: error("No Cancel control")).performClick()
                a.voiceDraft!!.discardDialog!!.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            }
            onMain {}
            check(a.voiceDraft == null && !file.exists() && a.editor.project == before)
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
        test("Snap to cuts and Tools share a preference retained by a fresh editor activity") {
            val id = "snap-" + uid()
            fun launch(): MainActivity = instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, MainActivity::class.java)
                    .putExtra("testSession", true).putExtra("testSessionId", id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) as MainActivity
            var current: MainActivity? = null
            try {
                val first = launch(); current = first
                Thread.sleep(250)
                onMain {
                    check(first.editor.snap)
                    val r = first.editorView.controlBounds("snap") ?: error("No Snap to cuts button")
                    val now = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(now, now + 30, action, r.centerX(), r.centerY(), 0)
                        first.editorView.dispatchTouchEvent(event); event.recycle()
                    }
                    check(!first.editor.snap)
                    first.finish()
                }
                val second = launch(); current = second
                Thread.sleep(250)
                onMain {
                    check(!second.editor.snap)
                    second.panels.tools()
                    (find(second.root, "Snap to cuts") ?: error("No Snap setting")).performClick()
                    check(second.editor.snap)
                    second.panels.close(); second.finish()
                }
                val third = launch(); current = third
                onMain { check(third.editor.snap) }
            } finally {
                current?.let { onMain { it.finish() } }
                instrumentation.targetContext.deleteSharedPreferences("editor-test-$id")
            }
        }
        onMain { a.panels.close(); a.playback.pause() }
    }
}
