package org.myrec.app

import android.app.Instrumentation
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File
import kotlin.math.abs

object CoverTests {
    private fun find(view: View, title: String): View? {
        if (view.contentDescription?.toString() == title || view is TextView && view.text.toString() == title) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index),title)?.let { return it }
        return null
    }
    private fun tiles(view: View): List<View> {
        if (view.tag?.toString()?.startsWith("cover.frame.") == true) return listOf(view)
        return if (view is ViewGroup) (0 until view.childCount).flatMap { tiles(view.getChildAt(it)) } else emptyList()
    }
    fun run(i: Instrumentation, a: MainActivity, onMain: (() -> Unit) -> Unit, test: (String, () -> Unit) -> Unit) {
        val media = File(a.store.media,"cover-fixture.mp4")
        i.context.assets.open("fixture.mp4").use { input -> media.outputStream().use { input.copyTo(it) } }
        val title = Effect(kind = "Text",to = 2.0,values = TitleStyle.defaults.toMutableMap().apply {
            put("text","Cover check"); put("x",".5"); put("y",".5"); put("size",".16")
        })
        val project = Project(name = "Cover check",width = 640,height = 360,clips = mutableListOf(
            Clip(path = media.path,sourceOut = 2.0,effects = mutableListOf(Effect(kind = "Color LUTs",values = mutableMapOf("lut" to "Warm")),title)),
            Clip(path = media.path,sourceOut = 1.0,start = 4.0,video = false)))
        fun awaitReady() {
            val deadline = SystemClock.uptimeMillis() + 20000
            while (SystemClock.uptimeMillis() < deadline) {
                var ready = false
                onMain { ready = find(a.root,"Share JPEG")?.isEnabled == true }
                if (ready) return
                Thread.sleep(100)
            }
            error("Full-resolution cover did not become ready")
        }
        test("cover picker refreshes chronological choices and scopes video without modifying edits") {
            onMain {
                a.editor.load(project.duplicate()); a.panels.cover()
                val before = tiles(a.root).map { it.tag }
                check(before.size == 9 && find(a.root,"Whole project") != null)
                check(find(a.root,"Update")!!.performClick())
                val after = tiles(a.root).map { it.tag }
                check(after.size == 9 && after.indices.all { before[it] != after[it] })
                check(a.editor.project == project && !a.editor.canUndo)
                a.editor.range = .5..1.0; a.panels.cover()
                check(find(a.root,"Selected range") != null && tiles(a.root).size == 9)
                a.editor.clearSelection(); a.editor.selected.add(a.editor.project.clips[1].id); a.panels.cover()
                check(find(a.root,"Selected clips") != null && find(a.root,"No video in this range") != null && tiles(a.root).isEmpty())
                a.panels.close()
            }
        }
        val directory = SharedImages.directory(a)
        val originalFiles = directory.listFiles().orEmpty().map { it.name }.toSet()
        try {
            test("cover JPEG keeps full assembled resolution and the captured edit through rotation") {
                onMain { a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT; a.editor.load(project.duplicate()) }
                Thread.sleep(650)
                val expected = a.editor.project.duplicate()
                val source = CoverSource(expected)
                var pick = 0
                onMain {
                    a.panels.cover()
                    val tile = tiles(a.root).first()
                    pick = tile.tag.toString().substringAfterLast('.').toInt()
                    check(tile.performClick())
                    a.editor.project.clips[0].effects.first { it.kind == "Text" }.values["text"] = "Changed while loading"
                }
                awaitReady()
                val files = directory.listFiles().orEmpty().filter { it.extension == "jpg" && it.name !in originalFiles }
                check(files.size == 1)
                val file = files.single()
                val jpeg = BitmapFactory.decodeFile(file.path) ?: error("No cover JPEG")
                check(jpeg.width == 640 && jpeg.height == 360)
                Frames().use { frames ->
                    val original = frames.render(expected,source.pool.time(pick),640,360)
                    val changed = frames.render(a.editor.project,source.pool.time(pick),640,360)
                    var total = 0L; var originalError = 0L; var changedError = 0L; var mask = 0
                    for (y in 0 until 360) for (x in 0 until 640) {
                        val old = original.getPixel(x,y); val current = changed.getPixel(x,y); val actual = jpeg.getPixel(x,y)
                        val delta = listOf(16,8,0).sumOf { shift -> abs(((old ushr shift) and 255) - ((current ushr shift) and 255)) }
                        for (shift in listOf(16,8,0)) {
                            val a0 = (actual ushr shift) and 255; val b = (old ushr shift) and 255; val c = (current ushr shift) and 255
                            total += abs(a0-b)
                            if (delta > 100) { originalError += abs(a0-b); changedError += abs(a0-c) }
                        }
                        if (delta > 100) mask++
                    }
                    check(total / (640.0*360*3) < 8 && mask > 100 && originalError < changedError / 3) { "Cover did not keep its assembled edit snapshot" }
                    original.recycle(); changed.recycle()
                }
                jpeg.recycle()
                onMain { a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                Thread.sleep(650); awaitReady()
                onMain {
                    check(find(a.root,"Save JPEG")!!.isEnabled && find(a.root,"Cover image, 640 × 360 pixels") != null)
                    check(find(a.root,"Back to cover choices")!!.performClick())
                    check(tiles(a.root).size == 9)
                    a.panels.close()
                }
                check(directory.listFiles().orEmpty().count { it.extension == "jpg" && it.name !in originalFiles } == 1)
            }
            test("JPEG provider reports display metadata and grants read access without exposing other files") {
                val file = directory.listFiles()!!.single { it.extension == "jpg" && it.name !in originalFiles }
                val uri = SharedImages.uri(a,file)
                check(a.contentResolver.getType(uri) == "image/jpeg")
                a.contentResolver.query(uri,null,null,null,null)!!.use { cursor ->
                    check(cursor.moveToFirst())
                    check(cursor.getString(cursor.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)) == file.name.substringAfter('_'))
                    check(cursor.getLong(cursor.getColumnIndexOrThrow(android.provider.OpenableColumns.SIZE)) == file.length())
                }
                check(a.contentResolver.openInputStream(uri)!!.use { it.readBytes() }.contentEquals(file.readBytes()))
                check(runCatching { a.contentResolver.openFileDescriptor(uri,"rw")?.close() }.isFailure)
                val outside = File(a.cacheDir,"outside-cover.jpg").apply { writeText("private fixture") }
                try {
                    val bad = uri.buildUpon().path("").appendPath("../${outside.name}").build()
                    check(runCatching { a.contentResolver.openInputStream(bad)?.close() }.isFailure)
                } finally { outside.delete() }
            }
            test("closing cover generation cannot publish late views or alter the timeline") {
                onMain {
                    a.editor.load(project.duplicate()); a.panels.cover()
                    check(tiles(a.root).first().performClick()); a.panels.close()
                }
                Thread.sleep(500)
                onMain { check(find(a.root,"Share JPEG") == null && a.editor.project == project && !a.editor.canUndo) }
            }
        } finally {
            onMain { a.panels.close() }
            for (file in directory.listFiles().orEmpty()) if (file.name !in originalFiles) file.delete()
        }
    }
}
