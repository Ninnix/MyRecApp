package org.myrec.app

import android.app.Instrumentation
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import java.io.File

object ShowcaseScreens {
    fun run(i: Instrumentation, a: MainActivity, onMain: (() -> Unit) -> Unit,
            test: (String, () -> Unit) -> Unit) {
        val directory = File(a.getExternalFilesDir(null), "showcase").apply { mkdirs() }
        val input = File(a.getExternalFilesDir(null), "test-inputs/colosseum.mp4")
        check(input.isFile) { "Install the documented Colosseum showcase clip first" }
        val source = File(a.store.media, "colosseum.mp4"); input.copyTo(source, overwrite = true)
        fun find(view: View, name: String): View? {
            if (view.contentDescription?.toString() == name || view is TextView && view.text.toString() == name) return view
            if (view is ViewGroup) for (n in 0 until view.childCount) find(view.getChildAt(n), name)?.let { return it }
            return null
        }
        fun snapshot(name: String) {
            i.waitForIdleSync(); Thread.sleep(500)
            val image = i.uiAutomation.takeScreenshot() ?: error("No $name screenshot")
            File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
        lateinit var title: Effect
        onMain {
            val clip = ProjectStore.inspect(source).apply { sourceOut = 11.27 }
            a.editor.load(Project(name = "The Colosseum", width = 1920, height = 1080, clips = mutableListOf(clip)))
            title = Effect(kind = "Text", to = 3.5, values = TitleStyle.defaults.toMutableMap().apply {
                putAll(TitleStyle.looks.getValue("Clean")); put("text", "The Colosseum")
                put("x", ".42"); put("y", ".76"); put("size", ".085")
            })
            a.editor.applyEffect(title)
            for (cut in listOf(4.5, 8.5)) { a.editor.selected.clear(); a.editor.cursor = cut; a.editor.split() }
            val sounds = a.editor.project.clips.toList().map { video ->
                val link = uid(); video.audio = false; video.link = link; video.linked = true
                video.duplicate(true, keepGroup = true, keepOccurrence = true).apply { this.video = false; audio = true; effects.clear(); this.link = link }
            }
            a.editor.project.clips.addAll(sounds)
            a.editor.selected.clear(); a.editor.range = null; a.editor.cursor = 0.0
            a.editor.wholeProject = true
            a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            a.showEditor(); a.playback.refresh()
        }
        Thread.sleep(2500)
        test("Colosseum landscape timeline captures three linked video and audio clips") {
            check(a.editor.project.clips.count { it.video } == 3 && a.editor.project.clips.count { it.audio } == 3)
            snapshot("timeline")
        }
        onMain { a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        Thread.sleep(800)
        test("Colosseum text inspector captures a real editable three and a half second title") {
            onMain {
                a.editor.selected.clear(); a.editor.range = null; a.editor.cursor = 0.0
                a.panels.editExisting(title.id)
                (find(a.root, "Title text") as EditText).clearFocus()
            }
            Thread.sleep(1600)
            snapshot("text-overlay")
            onMain { (find(a.root, "Cancel") ?: error("No Text Cancel")).performClick() }
        }
        test("Colosseum color LUT inspector captures real previews for the full linked selection") {
            onMain {
                a.editor.selected.addAll(a.editor.project.clips.map { it.id }); a.editor.cursor = 0.0
                a.panels.effect("Color LUTs")
            }
            Thread.sleep(3000)
            snapshot("color-luts")
            onMain { (find(a.root, "Cancel") ?: error("No LUT Cancel")).performClick() }
        }
        onMain { a.panels.close(); a.playback.pause() }
        File(directory, "project.json").writeText(a.store.encode(a.editor.project).toString())
    }
}
