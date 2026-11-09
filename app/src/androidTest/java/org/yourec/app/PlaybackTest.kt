package org.yourec.app

import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.SystemClock
import java.io.File

object PlaybackTest {
    fun run(
        instrumentation: Instrumentation,
        a: MainActivity,
        currentProject: Boolean,
        onMain: (() -> Unit) -> Unit,
        test: (String, () -> Unit) -> Unit,
    ) {
        val project =
            if (currentProject) ProjectStore(File(a.filesDir, "projects")).current()
            else {
                val project = Project(width = 640, height = 360)
                repeat(5) { index ->
                    val source = File(a.store.media, "playback-$index.mp4")
                    instrumentation.context.assets.open("fixture.mp4").use { input ->
                        source.outputStream().use { input.copyTo(it) }
                    }
                    project.clips.add(ProjectStore.inspect(source).apply { start = index * 6.0 })
                }
                project.clips.last().effects.add(Effect(kind = "Kaleidoscope"))
                project
            }
        val folder = File(a.getExternalFilesDir(null), "playback-checks").apply { mkdirs() }
        val report = StringBuilder()
        fun hash(bitmap: Bitmap): Int {
            var value = 1
            for (y in 0 until bitmap.height step maxOf(1, bitmap.height / 24)) for (x in
                0 until bitmap.width step maxOf(1, bitmap.width / 24)) value =
                value * 31 + bitmap.getPixel(x, y)
            return value
        }
        test("Fifth take preview advances after seeking through preceding takes") {
            check(project.clips.size >= 5) { "Five takes are needed" }
            onMain {
                a.editor.load(project.duplicate())
                a.playback.pause()
            }
            for (clip in project.clips.take(5)) {
                onMain { a.playback.seek(clip.start + .3) }
                Thread.sleep(650)
            }
            val start = project.clips[4].start + .5
            onMain { a.playback.seek(start) }
            Thread.sleep(400)
            val hashes = mutableSetOf<Int>()
            val identities = mutableSetOf<Int>()
            var position = start
            onMain { a.editorView.action("play") }
            val deadline = SystemClock.uptimeMillis() + 3200
            while (SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(100)
                onMain {
                    a.editorView.bitmap?.let {
                        hashes.add(hash(it))
                        identities.add(System.identityHashCode(it))
                    }
                    position = a.editor.cursor
                    report.append(
                        "cursor=$position frames=${identities.size} images=${hashes.size}\n"
                    )
                }
            }
            onMain { a.playback.pause() }
            File(folder, "playback.txt").writeText(report.toString())
            check(position > start + 1.5) { "Playback clock stopped: $report" }
            check(hashes.size >= 5) { "Preview stayed still: $report" }
        }
    }
}
