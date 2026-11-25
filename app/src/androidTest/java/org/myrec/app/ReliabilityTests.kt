package org.myrec.app

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioDeviceInfo
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.system.ErrnoException
import android.system.OsConstants
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

object ReliabilityTests {
    private const val SAVED = "Last saved edit"
    private const val EXPORT = "interrupted-export"

    fun run(i: Instrumentation, a: MainActivity, phase: String, main: (() -> Unit) -> Unit,
            test: (String, () -> Unit) -> Unit) {
        when (phase) {
            "exercise" -> {
                test("34-minute project retains 256 clips and undo/redo and renders at its endpoints") { longProject(a) }
                test("ENOSPC during project publication preserves the last acknowledged edit for retry") { failedSave(a) }
                test("low storage and critical thermal pressure preserve the completed movie and verified export sections") {
                    exportPressure(i, a)
                }
                capturePressures(i, a, main, test)
            }
            "save-arm" -> saveArm(i, a, main)
            "save-check" -> test("restart restores the committed edit and attaches the sealed recording exactly once") { saveCheck(a) }
            "capture-arm" -> onCapture(a,main) { capture ->
                waitFor("Camera did not start") {
                    main { if (capture.readyToRecord && !capture.recording) capture.start() }
                    capture.elapsedSeconds()>4
                }
                val journals=a.store.media.listFiles().orEmpty().filter { it.name.endsWith(".samples") }
                check(journals.size==2 && journals.all { it.length()>4096 })
                check(a.store.needsRecordingRemux())
                armed(i,"capture-arm")
            }
            "capture-check" -> test("an active take survives process death with decodable picture and sound and attaches once") {
                waitFor("Interrupted capture remux timed out") { a.startupComplete }
                val saved=a.store.currentSession()
                val take=saved.originals.single()
                check(take.audio && take.video && take.duration>3.0)
                check(saved.project.clips.single().id==take.id)
                check(a.store.recoverCaptures()==0)
                VideoDecoder(take.path).use { decoder -> checkNotNull(decoder.frame(take.duration-.25,96,54)).recycle() }
                val pcm=a.audioCache.get(take.path)
                check(pcm.length()>3*48000*8)
            }
            "bluetooth" -> test("a Bluetooth microphone disconnects during recording and reconnects without taking over the fallback route") {
                bluetooth(i,a,main)
            }
            "export-arm", "publish-arm" -> exportArm(i, a, phase)
            "export-check" -> test("two export kills preserve the old movie and retry reuses every verified section") { exportCheck(a) }
            else -> error("Unknown reliability test phase")
        }
    }

    private fun waitFor(message: String, ready: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) {
            if (ready()) return
            Thread.sleep(50)
        }
        error(message)
    }

    private fun longProject(a: MainActivity) {
        val paths = listOf(Color.RED, Color.BLUE).mapIndexed { index, color ->
            File(a.store.media, "long-$index.png").also { target ->
                val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                try { target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                finally { bitmap.recycle() }
            }
        }
        val project = Project(name = "Long timeline", width = 160, height = 90, clips = MutableList(256) { index ->
            Clip(path = paths[index % 2].absolutePath, sourceOut = 8.0, start = 8.0 * index,
                audio = false, linked = false, photo = true)
        })
        val history = List(24) { index -> EditRevision(project.duplicate().apply { clips.first().marks.add((index + 1) / 4.0) }) }
        val start = SystemClock.elapsedRealtime()
        a.store.save(EditSession(project, undo = history), false)
        val reopened = ProjectStore(a.store.root)
        val editor = reopened.editor(project)
        check(editor.project.duration == 2048.0 && editor.project.clips.size == 256)
        check(editor.session().undo.size == 24 && editor.session().undo.all { it.deferred })
        editor.undo(); check(editor.project.clips.first().marks == listOf(6.0))
        reopened.save(editor, false)
        val restored = ProjectStore(a.store.root).editor(project)
        restored.redo(); check(restored.project.clips.first().marks.isEmpty())
        Frames().use { frames ->
            for (index in listOf(0, 127, 128, 255)) {
                val frame = frames.render(restored.project, index * 8.0 + 7.95, 160, 90)
                try { check(frame.getPixel(80, 45) == if (index % 2 == 0) Color.RED else Color.BLUE) }
                finally { frame.recycle() }
            }
        }
        File(a.getExternalFilesDir(null), "reliability-long-project.json").writeText(org.json.JSONObject()
            .put("clips", project.clips.size).put("seconds", project.duration).put("undo_states", history.size)
            .put("save_reopen_seek_ms", SystemClock.elapsedRealtime() - start).toString(2))
    }

    private fun failedSave(a: MainActivity) {
        val project = Project(name = SAVED)
        a.store.save(project, false)
        val next = project.copy(name = "Retry this edit")
        a.store.beforePublish = { file -> if (file.name == "${project.id}.json") throw ErrnoException("rename", OsConstants.ENOSPC) }
        try { check(runCatching { a.store.save(next, false) }.exceptionOrNull() is ErrnoException) }
        finally { a.store.beforePublish = null }
        check(ProjectStore(a.store.root).open(project.id).project.name == SAVED)
        a.store.save(next, false)
        check(ProjectStore(a.store.root).open(project.id).project.name == next.name)
    }

    private fun source(i: Instrumentation, a: MainActivity, name: String): File = File(a.store.media, name).also { file ->
        i.context.assets.open("fixture.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
    }

    private fun saveArm(i: Instrumentation, a: MainActivity, main: (() -> Unit) -> Unit) {
        val project = Project(id = "interrupted-save", name = SAVED)
        val earlier = ProjectStore.inspect(source(i, a, "earlier.mp4"))
        a.store.save(EditSession(project.copy(clips = mutableListOf(earlier)), originals = listOf(earlier)))
        a.store.registerMedia(project.id, earlier.path)
        val recording = File(a.store.media, "recording-interrupted.mp4")
        val receipt = a.store.beginCapture(project, recording, emptyList())
        val pending = File(a.store.root, "pending/${receipt.id}.json")
        pending.writeText(org.json.JSONObject(pending.readText())
            .put("recipe", org.json.JSONArray().put(org.json.JSONObject())).toString())
        source(i, a, recording.name + ".sealed.mp4")
        val draft = a.store.open(project.id).project.apply { name = "Edit interrupted before publication" }
        a.store.beforePublish = { file -> if (file.name == "${project.id}.json") armed(i, "save-arm") }
        main { a.editor.load(draft); a.saveProject() }
        error("Save was allowed to finish")
    }

    private fun saveCheck(a: MainActivity) {
        val saved = a.store.currentSession()
        check(saved.project.id == "interrupted-save" && saved.project.name == SAVED)
        check(saved.project.clips.size == 2 && saved.originals.size == 2)
        val prior = saved.originals.single { File(it.path).name == "earlier.mp4" }
        val recovered = saved.originals.single { File(it.path).name == "recording-interrupted.mp4" }
        check(recovered.video && recovered.audio && recovered.duration > 5)
        check(SourceAudio.hash(File(prior.path)) == a.store.trustedHash(saved.project.id, prior.path))
        check(SourceAudio.hash(File(prior.path)) == SourceAudio.hash(File(recovered.path)))
        check(a.store.root.listFiles().orEmpty().none { it.name.startsWith(".saving-") })
        check(File(a.store.root, "damaged/${saved.project.id}").listFiles().orEmpty().size == 1)
        check(a.store.recoverCaptures() == 0)
    }

    private fun exportFolder(a: MainActivity, project: Project) =
        File(a.cacheDir, "test-exports/${a.store.root.name}/${project.id}")

    private fun oldMovie(a: MainActivity, project: Project): File =
        a.movieExports.movie(a.store, project, 160, 90, ExportColor.SDR) { file, progress ->
            Export(a.audioCache).run(project, file, 160, 90, progress = progress)
        }.file

    private fun render(a: MainActivity, project: Project, export: Export, progress: (Float) -> Unit = {}): MovieExports.Movie =
        a.movieExports.resumable(a.store, project, 160, 90, ExportColor.SDR, 24,
            cancelled = { export.cancelled || export.paused }, progress = progress) { file, checkpoint, report ->
            export.render(project, file, 160, 90, ExportColor.SDR, 24, checkpoint, report)
        }

    private fun projectForExport(i: Instrumentation, a: MainActivity, id: String): Project {
        val file = source(i, a, "$id.mp4")
        return Project(id = id, clips = MutableList(3) { index -> Clip(path = file.path, sourceOut = 3.0, start = 3.0 * index) })
    }

    private fun previous(a: MainActivity, project: Project): File = oldMovie(a, project.copy(clips = mutableListOf(
        project.clips.first().copy(sourceOut = .5))))

    private fun section(a: MainActivity, project: Project): File {
        val ticket = a.movieExports.ticket(a.store, project, 160, 90, ExportColor.SDR, fps = 24)
        return File(exportFolder(a, project), "${ticket.key}.resume/frames-0-72.mp4")
    }

    private fun exportPressure(i: Instrumentation, a: MainActivity) {
        val project = projectForExport(i, a, "export-pressure")
        val old = previous(a, project)
        val oldHash = SourceAudio.hash(old)
        val first = section(a, project)
        val disk = Export(a.audioCache)
        var capacity = Long.MAX_VALUE
        disk.mediaStorage = MediaStorage { capacity }
        val failure = runCatching { render(a, project, disk) { if (first.isFile) capacity = MediaStorage.RESERVE - 1 } }
        check(failure.exceptionOrNull()?.message?.contains("storage") == true && first.isFile)
        check(SourceAudio.hash(old) == oldHash)
        val hash = SourceAudio.hash(first)
        val hot = Export(a.audioCache).apply { thermalStatus = { PowerManager.THERMAL_STATUS_CRITICAL } }
        val heat = runCatching { render(a, project, hot) }
        check(heat.exceptionOrNull()?.message?.contains("too warm") == true)
        check(SourceAudio.hash(first) == hash && SourceAudio.hash(old) == oldHash)
        val saved = render(a, project, Export(a.audioCache)).file
        check(abs(ProjectStore.inspect(saved).duration - 9.0) < .1)
    }

    private fun exportArm(i: Instrumentation, a: MainActivity, phase: String) {
        val project = if (phase == "export-arm") projectForExport(i, a, EXPORT) else a.store.open(EXPORT).project
        val folder = exportFolder(a, project)
        if (phase == "export-arm") {
            val old = previous(a, project)
            File(a.store.root, "export-baseline").writeText("${old.name}\n${SourceAudio.hash(old)}")
            a.store.save(project)
        }
        val first = section(a, project)
        if (phase == "publish-arm") {
            check(first.isFile && first.parentFile!!.listFiles().orEmpty().any { it.name.startsWith(".frames-72-144-") })
            check(a.movieExports.hasCheckpoint(a.store, project, 160, 90, ExportColor.SDR, 24))
        }
        render(a, project, Export(a.audioCache)) { progress ->
            val nextSection = phase == "export-arm" && first.isFile &&
                first.parentFile!!.listFiles().orEmpty().any { it.name.startsWith(".frames-72-144-") }
            if (nextSection || phase == "publish-arm" && progress >= .95f) {
                if (phase == "publish-arm") {
                    check(folder.listFiles().orEmpty().any { it.name.endsWith(".partial.mp4") })
                    val verified = first.parentFile!!.listFiles().orEmpty().filter { it.extension in setOf("mp4", "m4a") }
                    check(verified.size == 4)
                    File(a.store.root, "export-checkpoints").writeText(verified.joinToString("\n") { "${it.name} ${it.lastModified()}" })
                }
                armed(i, phase)
            }
        }
        error("Export was allowed to finish")
    }

    private fun exportCheck(a: MainActivity) {
        val project = a.store.open(EXPORT).project
        val folder = exportFolder(a, project)
        val (name, hash) = File(a.store.root, "export-baseline").readLines()
        check(SourceAudio.hash(File(folder, name)) == hash)
        val parts = section(a, project).parentFile!!
        val stamps = File(a.store.root, "export-checkpoints").readLines().associate { row ->
            row.substringBefore(' ') to row.substringAfter(' ').toLong()
        }
        check(stamps.size == 4)
        val saved = render(a, project, Export(a.audioCache)) { progress ->
            if (progress >= .9f && progress < 1f) check(stamps.all { (part, stamp) -> File(parts, part).lastModified() == stamp })
        }
        check(abs(ProjectStore.inspect(saved.file).duration - 9.0) < .1)
        check(folder.listFiles().orEmpty().none { it.name.contains(".partial.") || it.name.endsWith(".resume") })
        check(render(a, project, Export(a.audioCache)).reused)
    }

    private fun armed(i: Instrumentation, phase: String): Nothing {
        i.sendStatus(0, Bundle().apply { putString("stream", "READY $phase\n") })
        CountDownLatch(1).await(60, TimeUnit.SECONDS)
        error("Controller did not terminate the process")
    }

    private fun capturePressures(i: Instrumentation, a: MainActivity, main: (() -> Unit) -> Unit,
                                 test: (String, () -> Unit) -> Unit) {
        onCapture(a, main) { capture ->
            fun state(ready: () -> Boolean): Boolean { var found = false; main { found = ready() }; return found }
            fun start() {
                waitFor("Camera and microphone did not start") { state {
                    if (capture.readyToRecord && !capture.recording) capture.start()
                    capture.recording
                } }
                waitFor("No encoded picture arrived") { state { capture.elapsedSeconds() > 1.0 } }
            }
            fun take(index: Int) {
                waitFor("Recording did not finalize") { state { !capture.recording } }
                val saved = a.store.open(a.editor.project.id).originals[index]
                check(saved.duration > .7 && saved.audio && saved.video)
                VideoDecoder(saved.path).use { decoder -> checkNotNull(decoder.frame(.25, 96, 54)).recycle() }
            }
            test("camera interruption pauses and finishes the take after the activity returns") {
                start()
                main { check(a.moveTaskToBack(true)) }
                waitFor("Leaving the activity did not pause recording") { state { capture.paused } }
                val intent = Intent(a, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                i.targetContext.startActivity(intent)
                waitFor("Camera did not reopen after interruption") { state { a.hasWindowFocus() && capture.readyToRecord } }
                main { capture.stop() }
                take(0)
            }
            test("storage reserve prevents a new take and safely finalizes an active take") {
                var bytes = MediaStorage.RESERVE - 1
                main {
                    capture.mediaStorage = MediaStorage { bytes }
                    capture.start(); check(!capture.recording)
                    bytes = Long.MAX_VALUE
                }
                start()
                main { bytes = MediaStorage.RESERVE + 1 }
                take(1)
                main { capture.mediaStorage = MediaStorage() }
            }
            test("critical thermal pressure finalizes the recording with picture and sound") {
                var heat = PowerManager.THERMAL_STATUS_NONE
                main { capture.thermalStatus = { heat } }
                start()
                main { heat = PowerManager.THERMAL_STATUS_CRITICAL }
                take(2)
            }
        }
    }

    private fun onCapture(a: MainActivity, main: (() -> Unit) -> Unit, block: (Capture) -> Unit) {
        lateinit var capture: Capture
        main {
            a.editor.load(Project(name = "Recording acceptance")); a.showCapture()
            capture = (0 until a.root.childCount).map { a.root.getChildAt(it) }.filterIsInstance<Capture>().single()
            capture.setHdr(false)
        }
        try { block(capture) }
        finally { main { if (capture.recording) capture.stop(); capture.suspendCamera() } }
    }

    private fun bluetooth(i: Instrumentation,a: MainActivity,main: (() -> Unit) -> Unit) = onCapture(a,main) { capture ->
        fun external(device: AudioDeviceInfo?) = device?.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO,AudioDeviceInfo.TYPE_BLE_HEADSET)
        fun waitForRoute(message: String, seconds: Int = 15, condition: ()->Boolean) {
            val deadline=SystemClock.uptimeMillis()+seconds*1000L
            var ready=false
            do {
                main { ready=condition() }
                if (ready) return
                Thread.sleep(80)
            } while (SystemClock.uptimeMillis()<deadline)
            error("$message · ${a.lastMessage}")
        }
        lateinit var headset: AudioDeviceInfo
        main {
            headset=capture.microphoneDevices().firstOrNull(::external) ?: error("No Bluetooth microphone is connected")
            check(capture.selectMicrophone(headset)) { "Android refused the Bluetooth microphone" }
        }
        waitForRoute("The headset never supplied input samples") { external(capture.microphoneInput) && capture.microphoneReceiving }
        waitForRoute("The Bluetooth take did not start") {
            if (capture.readyToRecord && !capture.recording) capture.start()
            capture.recording && capture.elapsedSeconds()>2.0 && external(capture.microphoneInput)
        }
        i.sendStatus(0,Bundle().apply { putString("stream","READY bluetooth-disconnect\n") })
        waitForRoute("The headset is still connected",60) { capture.microphoneDevices().none(::external) }
        waitForRoute("The built-in microphone did not take over") {
            capture.microphoneInput!=null && !external(capture.microphoneInput) && capture.microphoneReceiving
        }
        main { if (capture.paused) capture.pause() }
        waitForRoute("The take could not continue with the built-in microphone") { capture.recording && !capture.paused }
        i.sendStatus(0,Bundle().apply { putString("stream","READY bluetooth-reconnect\n") })
        waitForRoute("The headset did not reconnect",60) { capture.microphoneDevices().any(::external) }
        Thread.sleep(1500)
        main {
            check(capture.recording && capture.microphoneReceiving && !external(capture.microphoneInput)) {
                "The reconnected headset replaced the fallback during a take"
            }
            capture.stop()
        }
        val take=a.store.open(a.editor.project.id).originals.single()
        check(take.video && take.audio && take.duration>3)
        VideoDecoder(take.path).use { decoder -> checkNotNull(decoder.frame(take.duration-.25,96,54)).recycle() }
    }
}
