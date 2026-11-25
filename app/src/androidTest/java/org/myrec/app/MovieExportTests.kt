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
        test("software verification decodes distinct AVC pictures across a seek") {
            VideoDecoder(source.path, softwareOnly = true).use { decoder ->
                val first = checkNotNull(decoder.frame(0.0, 96, 54))
                try {
                    val later = checkNotNull(decoder.frame(5.0, 96, 54))
                    try { check(!first.sameAs(later)) } finally { later.recycle() }
                } finally { first.recycle() }
            }
        }
        test("a reopened export resumes verified HEVC sections at the selected frame rate") {
            val edited = Project(name = "Resumable export", width = 320, height = 180,
                clips = MutableList(9) { index -> Clip(path = source.path, sourceOut = .4, start = index * .4) },
                movieSettings = MovieSettings(ExportResolution.Project, 24, ExportColor.SDR_HEVC))
            check(a.store.decode(a.store.encode(edited)).movieSettings == edited.movieSettings)
            val settings = edited.movieSettings
            val ticket = exports.ticket(a.store, edited, 320, 180, settings.color, fps = 24)
            val section = File(directory, "${edited.id}/${ticket.key}.resume/frames-0-72.mp4")
            val job = Export(a.audioCache)
            fun run(owner: MovieExports, export: Export, report: (Float) -> Unit) = owner.resumable(
                a.store, edited, 320, 180, settings.color, 24, { export.paused }, report,
            ) { file, checkpoint, progress -> export.render(edited, file, 320, 180, settings.color, 24, checkpoint, progress) }
            val interrupted = runCatching { run(exports, job) { if (section.isFile) job.paused = true } }
            check(interrupted.exceptionOrNull() is java.io.InterruptedIOException && section.length() > 0) {
                "Missing paused HEVC section: ${interrupted.exceptionOrNull()}"
            }
            val saved = section.lastModified()
            check(MovieExports(directory).hasCheckpoint(a.store, edited, 320, 180, settings.color, 24))
            var reusedSection = false
            val completed = run(MovieExports(directory), Export(a.audioCache)) { fraction ->
                if (fraction > .5f && section.lastModified() == saved) reusedSection = true
            }
            check(reusedSection && !section.exists())
            val extractor = android.media.MediaExtractor()
            try {
                extractor.setDataSource(completed.file.path)
                val index = (0 until extractor.trackCount).first {
                    extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME) == "video/hevc"
                }
                extractor.selectTrack(index)
                val times = mutableListOf<Long>()
                do { if (extractor.sampleTime >= 0) times.add(extractor.sampleTime) } while (extractor.advance())
                check(times.size == 87 && times.zipWithNext().all { (left, right) -> right - left in 41663L..41670L }) {
                    "HEVC segment clock is discontinuous: ${times.size} frames, $times"
                }
            } finally { extractor.release() }
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
        test("scoped clipping warning follows the edited samples and survives audio checkpoint reuse") {
            val wave=File(root,"loud.wav")
            val frames=48000
            val bytes=java.nio.ByteBuffer.allocate(44+frames*2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bytes.put("RIFF".toByteArray()).putInt(36+frames*2).put("WAVEfmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(1).putInt(48000).putInt(96000).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(frames*2)
            repeat(frames) { index ->
                bytes.putShort((.7*32767*kotlin.math.sin(2*Math.PI*index/48)).toInt().toShort())
            }
            wave.writeBytes(bytes.array())
            val sound=Clip(path=wave.path,sourceOut=1.0,video=false,linked=false)
            val edit=Project(width=160,height=90,clips=mutableListOf(
                Clip(path=source.path,sourceOut=1.0,audio=false),sound))
            val dry=Export(a.audioCache)
            dry.run(edit,File(root,"straight.mp4"),160,90) {}
            check(dry.editedAudioPeak==null)
            sound.effects.add(AudioFilter.effect("Output gain").copy(from=.35,to=.65).apply { values["gain"]="6" })
            val ticket=exports.ticket(a.store,edit,160,90,ExportColor.SDR)
            val audioPart=File(directory,"${edit.id}/${ticket.key}.resume/audio.m4a")
            fun render(job: Export,progress: (Float)->Unit) = exports.resumable(a.store,edit,160,90,ExportColor.SDR,30,
                cancelled={ job.paused },progress=progress,editedAudioPeak={ job.editedAudioPeak }) { target,checkpoint,report ->
                job.render(edit,target,160,90,ExportColor.SDR,30,checkpoint,report)
            }
            val first=Export(a.audioCache)
            check(runCatching { render(first) { if (audioPart.isFile) first.paused=true } }.isFailure)
            check(audioPart.isFile)
            val resumed=render(Export(a.audioCache)) {}
            check(resumed.editedAudioPeak?.let { it in 1.38..1.42 }==true)
            check(render(Export(a.audioCache)) {}.let { it.reused && it.editedAudioPeak==resumed.editedAudioPeak })
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
                check(find(a.root,"Resolution, Project size") != null) { "Project-card export did not open movie settings" }
                a.panels.close()
            }
        }
        test("export settings keep readable labels and values at large text size") {
            onMain {
                val resources = a.resources
                val original = android.content.res.Configuration(resources.configuration)
                @Suppress("DEPRECATION")
                fun font(configuration: android.content.res.Configuration) {
                    resources.updateConfiguration(configuration, resources.displayMetrics)
                }
                try {
                    a.editor.load(project.duplicate().apply {
                        movieSettings = MovieSettings(ExportResolution.FullHD, 50, ExportColor.SDR_HEVC)
                    })
                    font(android.content.res.Configuration(original).apply { fontScale = 2f })
                    a.panels.export()
                    for (label in listOf("Resolution, 1080p", "Frame rate, 50 fps", "Codec and color, SDR · HEVC")) {
                        val control = checkNotNull(find(a.root, label)) as android.widget.TextView
                        control.measure(View.MeasureSpec.makeMeasureSpec((220 * resources.displayMetrics.density).toInt(), View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                        check(control.isClickable && control.lineCount >= 2 && control.measuredHeight >= control.layout.height) { label }
                    }
                } finally {
                    a.panels.close()
                    font(original)
                }
            }
        }
    }
}
