package org.myrec.app

import android.app.Instrumentation
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File
import java.io.InterruptedIOException
import org.json.JSONObject

object MovieExportTests {
    private fun find(view: View, title: String): View? {
        if (view.contentDescription?.toString() == title || view is TextView && view.text.toString() == title) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index),title)?.let { return it }
        return null
    }
    fun run(i: Instrumentation, a: MainActivity, onMain: (() -> Unit) -> Unit,
            test: (String, () -> Unit) -> Unit) {
        val root = File(a.cacheDir, "movie-export-check-${uid()}").apply { mkdirs() }
        val source = File(root, "source.mp4")
        i.context.assets.open("fixture.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
        val project = Project(name = "Export receipt check", width = 160, height = 90,
            clips = mutableListOf(Clip(path = source.path, sourceOut = .4)))
        val directory = File(root, "completed")
        val exports = MovieExports(directory)
        val good = File(root, "known-good.mp4")
        lateinit var completed: MovieExports.Movie
        var renders = 0
        fun awaitChoice(title: String) {
            val deadline = android.os.SystemClock.uptimeMillis() + 30000
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                var ready = false
                onMain { ready = a.choices?.contentView?.let { find(it,title) } != null }
                if (ready) return
                Thread.sleep(100)
            }
            error("No $title export action")
        }
        test("export completion chime retains the reference duration and two tone spectrum") {
            val pcm = ExportSound.samples()
            check(pcm.size == 14400 && pcm.first() == 0.toShort() && pcm.last() == 0.toShort())
            val rms = kotlin.math.sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size)
            check(rms in 1000.0..1600.0)
            fun amplitude(hz: Double): Double {
                var real = 0.0; var imag = 0.0
                for (index in pcm.indices) {
                    val angle = 2 * Math.PI * hz * index / 48000
                    real += pcm[index] * kotlin.math.cos(angle); imag += pcm[index] * kotlin.math.sin(angle)
                }
                return kotlin.math.hypot(real,imag)
            }
            check(amplitude(1320.0) / amplitude(880.0) in .38.. .42)
            check(amplitude(660.0) < amplitude(880.0) * .02)
        }
        fun copy(target: File, progress: (Float) -> Unit) {
            renders++; good.copyTo(target); progress(1f)
        }
        test("completed movie receipts reuse verified picture and sound across reopening without encoding") {
            completed = exports.movie(a.store, project, 160, 90, ExportColor.SDR) { target, progress ->
                renders++; Export(a.audioCache).run(project, target, 160, 90, ExportColor.SDR, progress)
            }
            check(!completed.reused && renders == 1)
            completed.file.copyTo(good)
            val reopened = MovieExports(directory).movie(a.store, project, 160, 90, ExportColor.SDR) { _, _ -> error("An unchanged movie was encoded again") }
            check(reopened.reused && reopened.file == completed.file && reopened.file.readBytes().contentEquals(good.readBytes()))
        }
        test("export identity includes source bytes sidecars settings and edits but ignores dictionary order and project title") {
            fun key(p: Project = project, width: Int = 160, color: ExportColor = ExportColor.SDR) = exports.ticket(a.store, p, width, 90, color).key
            val first = key()
            check(key(project.copy(name = "A different title")) == first)
            check(key(width = 320) != first && key(color = ExportColor.HLG) != first)
            val changed = project.duplicate().apply { clips[0].gainDb = -3f }
            check(key(changed) != first)
            changed.clips[0].effects.add(Effect(kind = "Color LUTs", values = linkedMapOf("lut" to "Warm", "exposure" to "0")))
            val ordered = key(changed)
            changed.clips[0].effects[0].values.apply { clear(); put("exposure", "0"); put("lut", "Warm") }
            check(key(changed) == ordered)
            val sidecar = File(source.path + ".primaries")
            sidecar.writeText("1\n"); check(key() != first)
            sidecar.writeText("2\n"); check(key() != first)
            sidecar.delete(); check(key() == first)
            val original = source.readBytes(); val stamp = source.lastModified()
            try {
                source.writeBytes(original.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
                source.setLastModified(stamp)
                check(key() != first)
            } finally { source.writeBytes(original); source.setLastModified(stamp) }
            check(key() == first)
        }
        test("tampered or missing completed output is rendered again despite unchanged size and timestamp") {
            val original = completed.file.readBytes(); val stamp = completed.file.lastModified()
            completed.file.writeBytes(original.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
            completed.file.setLastModified(stamp)
            val repaired = exports.movie(a.store, project, 160, 90, ExportColor.SDR, render = ::copy)
            check(!repaired.reused && renders == 2 && repaired.file.readBytes().contentEquals(good.readBytes()))
            repaired.file.delete()
            completed = exports.movie(a.store, project, 160, 90, ExportColor.SDR, render = ::copy)
            check(!completed.reused && renders == 3)
        }
        test("malformed future or absent receipts never authorize reuse") {
            val receipt = completed.file.resolveSibling(completed.file.nameWithoutExtension + ".json")
            val original = receipt.readText()
            for (data in listOf("broken", JSONObject(original).put("version",2).toString(), "{}")) {
                receipt.writeText(data)
                check(!exports.movie(a.store, project, 160, 90, ExportColor.SDR, render = ::copy).reused)
            }
            receipt.delete()
            check(!exports.movie(a.store, project, 160, 90, ExportColor.SDR, render = ::copy).reused)
        }
        test("cancel failed rendering wrong duration and changing sources preserve the last completed movie") {
            val changed = project.duplicate().apply { clips[0].gainDb = -6f }
            val original = completed.file.readBytes()
            check(runCatching { exports.movie(a.store, changed,160,90,ExportColor.SDR,cancelled = { true },render = ::copy) }.exceptionOrNull() is InterruptedIOException)
            check(runCatching { exports.movie(a.store,changed,160,90,ExportColor.SDR) { target,_ ->
                target.writeBytes(byteArrayOf(1,2,3)); throw InterruptedIOException("Interrupted render")
            } }.exceptionOrNull() is InterruptedIOException)
            check(runCatching { exports.movie(a.store,changed,160,90,ExportColor.SDR) { target,_ -> source.copyTo(target) } }.isFailure)
            val sourceBytes = source.readBytes()
            try {
                check(runCatching { exports.movie(a.store,changed,160,90,ExportColor.SDR) { target,_ ->
                    good.copyTo(target); source.appendBytes(byteArrayOf(1))
                } }.isFailure)
            } finally { source.writeBytes(sourceBytes) }
            check(completed.file.readBytes().contentEquals(original))
            check(directory.walkTopDown().none { it.isFile && it.name.contains(".partial.") })
        }
        test("only a verified replacement retires the previous project's completed movie") {
            val changed = project.duplicate().apply { clips[0].gainDb = -1f }
            val replacement = exports.movie(a.store,changed,160,90,ExportColor.SDR,render = ::copy)
            check(!replacement.reused && replacement.file.isFile && !completed.file.exists())
            check(replacement.file.parentFile!!.listFiles()!!.count { it.extension == "mp4" } == 1)
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) test("gallery export publishes a playable movie and repeated export reuses the completed render") {
            val collection = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            fun published(): Set<android.net.Uri> {
                val ids = mutableSetOf<android.net.Uri>()
                a.contentResolver.query(collection, arrayOf(android.provider.MediaStore.Video.Media._ID),
                    "${android.provider.MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?", arrayOf(a.packageName), null)?.use { cursor ->
                    while (cursor.moveToNext()) ids.add(android.content.ContentUris.withAppendedId(collection,cursor.getLong(0)))
                }
                return ids
            }
            val before = published()
            try {
                onMain { a.editor.load(project); a.export(160,90) }
                awaitChoice("Play video")
                val first = published() - before
                check(first.size == 1)
                val cached = a.movieExports.movie(a.store,project,160,90,ExportColor.SDR) { _,_ -> error("Gallery export did not retain its completed render") }
                check(cached.reused)
                val stamp = cached.file.lastModified()
                onMain { a.choices?.dismiss(); a.export(160,90) }
                awaitChoice("Play video")
                check((published() - before).size == 2 && cached.file.lastModified() == stamp)
                for (uri in published() - before) {
                    val movie = File(root,"gallery-${uri.lastPathSegment}.mp4")
                    a.contentResolver.openInputStream(uri)!!.use { input -> movie.outputStream().use { input.copyTo(it) } }
                    check(movie.readBytes().contentEquals(cached.file.readBytes()))
                    VideoDecoder(movie.path).use { decoder -> (decoder.frame(.2,96,54) ?: error("Gallery movie is not playable")).recycle() }
                }
            } finally {
                onMain { a.choices?.dismiss() }
                for (uri in published() - before) a.contentResolver.delete(uri,null,null)
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) test("failed gallery saving retries the same completed movie after the active edit changes") {
            val collection = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            fun published(): Set<android.net.Uri> {
                val ids = mutableSetOf<android.net.Uri>()
                a.contentResolver.query(collection,arrayOf(android.provider.MediaStore.Video.Media._ID),
                    "${android.provider.MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",arrayOf(a.packageName),null)?.use { cursor ->
                    while (cursor.moveToNext()) ids.add(android.content.ContentUris.withAppendedId(collection,cursor.getLong(0)))
                }
                return ids
            }
            val before = published(); val writer = a.movieWriter
            val failed = project.copy(id = uid(),name = "Save retry check")
            var attempts = 0
            try {
                onMain {
                    a.movieWriter = { file,cancelled ->
                        attempts++
                        if (attempts == 1) throw java.io.IOException("Simulated destination failure")
                        writer(file,cancelled)
                    }
                    a.editor.load(failed); a.export(160,90)
                }
                awaitChoice("Retry saving")
                val cached = a.movieExports.movie(a.store,failed,160,90,ExportColor.SDR) { _,_ -> error("A completed movie was lost after destination failure") }
                check(cached.reused && published() == before)
                val stamp = cached.file.lastModified()
                onMain {
                    check(find(a.choices!!.contentView,"Save to file") != null && find(a.choices!!.contentView,"Keep for later") != null)
                    a.editor.load(project.duplicate().copy(id = uid()).apply { clips[0].sourceOut = .2 })
                    check(find(a.choices!!.contentView,"Retry saving")!!.performClick())
                }
                awaitChoice("Play video")
                val saved = published() - before
                check(attempts == 2 && saved.size == 1 && cached.file.lastModified() == stamp)
                val bytes = a.contentResolver.openInputStream(saved.single())!!.use { it.readBytes() }
                check(bytes.contentEquals(cached.file.readBytes()))
            } finally {
                onMain { a.movieWriter = writer; a.choices?.dismiss() }
                for (uri in published() - before) a.contentResolver.delete(uri,null,null)
            }
        }
        test("project card video export opens that project's movie settings") {
            a.store.save(project,false)
            check(a.store.open(project.id).project.id == project.id)
            onMain {
                a.openProject(project)
                check(a.editor.project.id == project.id) { "Direct project opening failed" }
                a.editor.load(project.copy(id = uid(),name = "Another project"))
                a.panels.projects()
                fun list(view: android.view.View): android.widget.ListView? {
                    if (view is android.widget.ListView) return view
                    if (view is android.view.ViewGroup) for (j in 0 until view.childCount) list(view.getChildAt(j))?.let { return it }
                    return null
                }
                val projects=list(a.root) ?: error("Missing project list")
                val position=(0 until projects.adapter.count).first { (projects.adapter.getItem(it) as? ProjectCard)?.project?.id==project.id }
                projects.setSelection(position)
            }
            val deadline=android.os.SystemClock.uptimeMillis()+3000;var ready=false
            while (!ready && android.os.SystemClock.uptimeMillis()<deadline) {
                onMain { ready=a.root.findViewWithTag<View>("project.options:${project.id}")!=null }
                if (!ready) Thread.sleep(20)
            }
            check(ready)
            onMain {
                val options = a.root.findViewWithTag<View>("project.options:${project.id}") ?: error("No options for the saved project")
                check(options.performClick())
                check(find(a.choices!!.contentView,"Export video")!!.performClick())
                check(a.editor.project.id == project.id) { "Project-card export opened ${a.editor.project.id}, expected ${project.id}; ${a.store.problems}" }
                check(find(a.root,"Video quality") != null) { "Project-card export did not open movie settings" }
                a.panels.close()
            }
        }
    }
}
