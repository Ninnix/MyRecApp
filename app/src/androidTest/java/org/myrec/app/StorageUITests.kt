package org.myrec.app

import android.app.Instrumentation
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File

object StorageUITests {
    fun run(instrumentation: Instrumentation, a: MainActivity,
        onMain: (() -> Unit) -> Unit, test: (String, () -> Unit) -> Unit) {
        val source = File(a.store.media, "storage-ui.mp4")
        instrumentation.context.assets.open("fixture.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
        val p = Project(name = "Storage workflow", clips = mutableListOf(ProjectStore.inspect(source)))
        onMain { a.editor.load(p); a.playback.pause() }
        fun find(view: View, label: String): View? {
            if (view.contentDescription?.toString() == label || view is TextView && view.text.toString() == label) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), label)?.let { return it }
            return null
        }
        test("project switching restores its own persistent undo and redo branch") {
            onMain {
                a.editor.setGain(-6f); a.editor.setGain(-9f); a.editor.undo()
                a.newProject(); a.openProject(p)
                check(a.editor.project.id == p.id && a.editor.canUndo && a.editor.canRedo)
                check(a.editor.project.clips.single().gainDb == -6f)
                a.editor.redo()
                check(a.editor.project.clips.single().gainDb == -9f)
            }
        }
        test("original recording actions remain reachable in portrait and landscape") {
            val directory = File(a.getExternalFilesDir(null), "storage-checks").apply { mkdirs() }
            for ((name, orientation) in listOf("portrait" to ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                "landscape" to ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                onMain { a.requestedOrientation = orientation }
                Thread.sleep(700)
                onMain {
                    a.panels.originals(a.editor.project)
                    check(find(a.root, "Review original") != null)
                    check(find(a.root, "Save original to Files") != null)
                    check(find(a.root, "Insert at playhead") != null)
                    val image = Bitmap.createBitmap(a.root.width, a.root.height, Bitmap.Config.ARGB_8888)
                    a.root.draw(Canvas(image))
                    File(directory, "originals-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    image.recycle()
                    a.panels.close()
                }
            }
        }
        test("save failure exposes Retry and saves the latest edit after storage recovers") {
            val pointer = File(a.store.root, "current")
            val saved = File(a.store.root, ".current-before-fault")
            lateinit var notice: TextView
            onMain { notice = TextView(a) }
            try {
                onMain {
                    notice.text = "Storage test · expected save failure"
                    notice.setTextColor(android.graphics.Color.YELLOW)
                    notice.setBackgroundColor(android.graphics.Color.BLACK)
                    a.root.addView(notice, android.widget.FrameLayout.LayoutParams(-2, -2,
                        android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL).apply {
                        topMargin = (72 * a.resources.displayMetrics.density).toInt()
                    })
                    check(pointer.renameTo(saved) && pointer.mkdir())
                    a.editor.setGain(-11f)
                    val retry = find(a.root, "Changes not saved · Retry") ?: error("Save retry is missing")
                    check(retry.visibility == View.VISIBLE)
                }
                Thread.sleep(700)
                onMain {
                    val retry = find(a.root, "Changes not saved · Retry") ?: error("Save retry is missing")
                    check(pointer.delete())
                    retry.performClick()
                    check(retry.visibility == View.GONE)
                    check(a.store.open(p.id).project.clips.single().gainDb == -11f)
                }
                Thread.sleep(3000)
            } finally {
                onMain {
                    if (pointer.isDirectory) pointer.delete()
                    if (!pointer.exists()) saved.renameTo(pointer)
                    saved.delete()
                    a.root.removeView(notice)
                }
            }
        }
        test("unavailable take controls expose repair and remove takes in one undoable edit") {
            val file = File(a.store.media, "unavailable.mp4")
            file.writeBytes(source.readBytes())
            val clip = ProjectStore.inspect(file)
            val missingProject = Project(name = "Missing take", clips = mutableListOf(clip))
            onMain { a.editor.load(missingProject); a.editor.setGain(-4f) }
            a.store.registerMedia(missingProject.id, clip.path)
            val found = File(a.store.root, "found-original.mp4")
            check(file.renameTo(found))
            onMain {
                a.panels.originals(missingProject)
                check(find(a.root, "Relink original") != null)
                val remove = find(a.root, "Remove unavailable takes") ?: error("Missing removal action")
                remove.performClick()
                check(a.editor.project.clips.isEmpty())
                check(a.editor.session().originals.single().path == clip.path)
                a.editor.undo()
                check(a.editor.project.clips.single().path == clip.path)
                check(a.editor.project.clips.single().gainDb == -4f)
                a.openProject(p)
            }
            check(found.isFile)
        }
        test("a real paused recording is journaled and survives undo of earlier edits") {
            onMain { a.showCapture() }
            Thread.sleep(2500)
            lateinit var capture: Capture
            onMain {
                capture = (0 until a.root.childCount).map { a.root.getChildAt(it) }.filterIsInstance<Capture>().single()
                capture.start()
            }
            Thread.sleep(1800)
            onMain { check(capture.recording); capture.addMark(); capture.pause() }
            Thread.sleep(250)
            onMain { capture.pause() }
            Thread.sleep(1000)
            onMain { capture.stop(); a.showEditor() }
            val savedState = a.store.open(p.id)
            val take = savedState.captures.single()
            check(take.marks.isNotEmpty() && take.sourceOut > 1.0 && File(take.path).isFile)
            onMain { a.editor.undo(); check(a.editor.project.clips.any { it.id == take.id }) }
            check(a.store.open(p.id).project.clips.any { it.id == take.id })
            check(File(a.store.root, "pending").listFiles()?.none { it.extension == "json" } != false)
        }
    }
}
