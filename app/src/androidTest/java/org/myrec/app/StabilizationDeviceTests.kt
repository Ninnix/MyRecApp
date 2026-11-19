package org.myrec.app

import android.app.Instrumentation
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.RectF
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.media.MediaCodec
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File
import java.nio.ByteBuffer
import org.json.JSONObject
import kotlin.math.*

object StabilizationDeviceTests {
    fun run(i: Instrumentation, a: MainActivity, onMain: (() -> Unit) -> Unit, test: (String, () -> Unit) -> Unit) {
        val source = File(a.store.media,"stabilization-shake.mp4")
        i.context.assets.open(source.name).use { input -> source.outputStream().use { input.copyTo(it) } }
        val originalHash = SourceAudio.hash(source)
        val clip = ProjectStore.inspect(source).copy(sourceOut = 2.0)
        val editor = Editor(Project(name = "Stabilization",width = 640,height = 360,fps = 60,clips = mutableListOf(clip)))
        editor.applyEffect(Effect.create("Stabilize").apply { values["level"] = "Strong" })
        val project = editor.project.duplicate(); val stabilized = project.clips.single(); val effect = stabilized.effects.single()
        val bounds = RectF(0f,0f,640f,360f)
        val results = JSONObject()
        fun plan(c: Clip = stabilized, fx: Effect = effect): StabilizationPlan {
            StabilizationRenderer.matrix(c,fx,c.sourceIn,bounds) {}
            return StabilizationRenderer.status(c,fx) ?: error("Missing computed stabilization plan")
        }
        fun find(view: View, label: String): View? {
            if (view.contentDescription == label || view is TextView && view.text.toString() == label) return view
            if (view is ViewGroup) for (j in 0 until view.childCount) find(view.getChildAt(j),label)?.let { return it }
            return null
        }
        fun click(label: String) = onMain { check((find(a.root,label) ?: error("Missing $label")).performClick()) }
        test("camera motion rejects a moving patch on an otherwise flat picture") {
            val a = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
            val b = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
            val random = kotlin.random.Random(42)
            val patch = IntArray(108 * 80) { random.nextInt(30, 225).let { android.graphics.Color.rgb(it, it, it) } }
            try {
                a.eraseColor(android.graphics.Color.GRAY)
                b.eraseColor(android.graphics.Color.GRAY)
                a.setPixels(patch, 0, 108, 214, 112, 108, 80)
                b.setPixels(patch, 0, 108, 226, 116, 108, 80)
                val tracking = Native.similarity(a, b)
                check(tracking[4] >= 6 && abs(tracking[2] - 12) < 1 && abs(tracking[3] - 4) < 1) { tracking.toList() }
                check(Native.similarity(a, b, cameraMotion = true)[4] == 0f)
            } finally { a.recycle(); b.recycle() }
        }
        test("stabilization analyzes every 60 fps source frame and reuses checked motion after reopening") {
            StabilizationRenderer.clear()
            val start = SystemClock.elapsedRealtime()
            val measured = plan()
            check(measured.samples.size == 120 && measured.trackingFraction > .9) { "${measured.samples.size} frames, ${measured.trackingFraction} tracked" }
            check(measured.samples.indices.all { abs(measured.samples[it].time-it/60.0) < .000002 })
            check(measured.zoom in 1.0..1.180001)
            val cache = StabilizationRenderer.directory!!.listFiles()!!.single { it.extension == "path" }
            val bytes = cache.readBytes(); check(cache.setLastModified(1500000000000))
            StabilizationRenderer.clear()
            check(plan() == measured && cache.lastModified() == 1500000000000 && cache.readBytes().contentEquals(bytes))
            results.put("analysis_ms",SystemClock.elapsedRealtime()-start).put("samples",measured.samples.size)
                .put("tracked_fraction",measured.trackingFraction).put("zoom",measured.zoom)
        }
        test("stabilization visibly reduces fixture shake and retains exact held frames on seeks") {
            fun motion(before: Bitmap, after: Bitmap): Double {
                val m = Native.similarity(before,after); check(m[4] >= 6) { "Cannot measure rendered shake" }
                val x = m[0]*before.width/2-m[1]*before.height/2+m[2]-before.width/2
                val y = m[1]*before.width/2+m[0]*before.height/2+m[3]-before.height/2
                return x*x+y*y.toDouble()
            }
            val plain = project.duplicate().apply { clips.single().effects.clear() }
            Frames().use { frames ->
                val rawReference = frames.render(plain,.4,640,360)
                val stableReference = frames.render(project,.4,640,360)
                var raw = 0.0; var stable = 0.0
                try {
                    for (frame in listOf(30,37,44,51,58,65,72,79,86,93)) {
                        val time = frame/60.0
                        val rawFrame = frames.render(plain,time,640,360); val stableFrame = frames.render(project,time,640,360)
                        try { raw += motion(rawReference,rawFrame); stable += motion(stableReference,stableFrame) }
                        finally { rawFrame.recycle(); stableFrame.recycle() }
                    }
                    check(stable < raw*.5) { "Rendered motion $stable >= half original $raw" }
                    val exact = frames.render(project,47/60.0,320,180)
                    for (time in listOf(47/60.0+.004,1.8,47/60.0,.2,47/60.0+.009)) {
                        val image = frames.render(project,time,320,180)
                        if (abs(time-47/60.0) < .01) check(image.sameAs(exact))
                        image.recycle()
                    }
                    exact.recycle()
                } finally { rawReference.recycle(); stableReference.recycle() }
                results.put("raw_motion_squared",raw).put("stabilized_motion_squared",stable)
            }
        }
        test("stabilization uses VFR presentation times including a source frame preceding a trim") {
            val file = File(a.store.media,"stabilization-vfr.mp4")
            i.context.assets.open("time-vfr.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
            val c = Clip(path = file.path,sourceIn = .05,sourceOut = .6)
            val fx = Effect.create("Stabilize"); StabilizationSettings.bind(fx,c,null)
            check(abs(StabilizationRenderer.frameTime(c,fx,.05) {}-.04) < .000002)
            check(abs(StabilizationRenderer.frameTime(c,fx,.155) {}-.11) < .000002)
            val p = plan(c,fx)
            val expected = doubleArrayOf(0.0,.06,.11,.25,.33,.45)
            check(p.samples.size == expected.size && expected.indices.all { abs(p.samples[it].time-expected[it]) < .000002 })
        }
        test("stabilization split trim retime freeze and copied projects reuse the same reference path") {
            val before = plan()
            val work = Editor(project.duplicate()); work.cursor = 1.0; work.split()
            for (c in work.project.clips) {
                val fx = c.effects.single(); check(plan(c,fx) == before)
                for (time in listOf(.5,.995,1.0,1.5)) {
                    val x = FloatArray(9); val y = FloatArray(9)
                    StabilizationRenderer.matrix(c,fx,time,bounds) {}.getValues(x)
                    StabilizationRenderer.matrix(stabilized,effect,time,bounds) {}.getValues(y)
                    check(x.contentEquals(y))
                }
            }
            val changed = stabilized.duplicate().apply { sourceIn = .5; sourceOut = 1.5; rate = .5 }
            check(plan(changed,changed.effects.single()) == before)
            val held = changed.copy(frozen = FrozenFrame(.75,.5))
            Frames().use { frames ->
                val p = project.copy(clips = mutableListOf(held))
                val first = frames.render(p,.01,320,180); val last = frames.render(p,.45,320,180)
                check(first.sameAs(last)); first.recycle(); last.recycle()
            }
            a.store.save(work,false)
            val reopened = a.store.editor(work.project)
            val copy = a.store.duplicate(reopened.project)
            check(a.store.open(copy.id).project.clips.all { StabilizationSettings.range(it.effects.single(),it) == (0.0 to 2.0) })
            val zip = File(a.cacheDir,"stabilization-${uid()}.zip")
            try {
                ProjectArchive.export(a.store,reopened.project,zip)
                val restored = a.store.open(ProjectArchive.restore(a.store,zip).project.id)
                check(restored.project.clips.map { it.effects } == reopened.project.clips.map { it.effects })
                val encoded = a.store.encode(restored.project)
                check(encoded.getJSONArray("clips").getJSONObject(0).getJSONArray("effects").getJSONObject(0).getInt("algorithm") == 5)
            } finally { zip.delete() }
        }
        test("stabilization rejects corrupted caches and invalidates same-size same-mtime sources") {
            val files = StabilizationRenderer.directory!!.listFiles()!!.filter { it.extension == "path" }
            val cache = files.first { StabilizationCache.read(it,it.nameWithoutExtension)?.samples?.size == 120 }
            val valid = cache.readBytes(); val broken = valid.copyOf().apply { this[size/2] = (this[size/2].toInt() xor 1).toByte() }
            cache.writeBytes(broken); StabilizationRenderer.clear(); plan()
            check(cache.readBytes().contentEquals(valid))
            val copy = File(a.store.media,"same-stamp.mp4"); source.copyTo(copy,true)
            val c = stabilized.copy(path = copy.path); val original = plan(c)
            val count = StabilizationRenderer.directory!!.listFiles()!!.count { it.extension == "path" }
            val stamp = copy.lastModified()
            val bytes = copy.readBytes()
            val where = bytes.indices.firstOrNull { n -> n+4 < bytes.size && bytes[n] == 0x75.toByte() && bytes[n+1] == 0x64.toByte() && bytes[n+2] == 0x74.toByte() && bytes[n+3] == 0x61.toByte() }
            check(where != null) { "No harmless MP4 user-data box" }
            // Change its type to an ignored box while leaving all coded samples intact.
            bytes[where] = 0x66; bytes[where+1] = 0x72; bytes[where+2] = 0x65; bytes[where+3] = 0x65
            copy.writeBytes(bytes); check(copy.setLastModified(stamp))
            check(plan(c) == original)
            check(StabilizationRenderer.directory!!.listFiles()!!.count { it.extension == "path" } == count+1)
        }
        test("stabilization exported video preserves duration audio source bytes and upright rotation") {
            val output = File(a.cacheDir,"stabilization-export.mp4")
            Export(a.audioCache).run(project,output,320,180) {}
            check(abs((Mp4Audio.presentedDuration(output) ?: 0.0)-2) < .002)
            val decoded = ProjectStore.inspect(output); check(decoded.video && decoded.audio && abs(decoded.duration-2) < .002)
            VideoDecoder(output.path).use { decoder ->
                (decoder.frame(0.0,320,180) ?: error("Missing first export frame")).recycle()
                (decoder.frame(1.98,320,180) ?: error("Missing final export frame")).recycle()
            }
            val portrait = File(a.store.media,"portrait-stabilization.mp4")
            val extractor = MediaExtractor(); val muxer = MediaMuxer(portrait.path,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                extractor.setDataSource(source.path); val tracks = IntArray(extractor.trackCount)
                for (t in tracks.indices) { tracks[t] = muxer.addTrack(extractor.getTrackFormat(t)); extractor.selectTrack(t) }
                muxer.setOrientationHint(90); muxer.start()
                val data = ByteBuffer.allocateDirect(1048576); val info = MediaCodec.BufferInfo()
                while (true) {
                    data.clear(); val n = extractor.readSampleData(data,0); if (n < 0) break
                    info.set(0,n,extractor.sampleTime,if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(tracks[extractor.sampleTrackIndex],data,info); extractor.advance()
                }
                muxer.stop()
            } finally { extractor.release(); muxer.release() }
            val c = stabilized.copy(path = portrait.path)
            val p = plan(c)
            check(p.width == 360 && p.height == 640 && p.samples.size == 120 && p.trackingFraction > .9)
            Frames().use { frames ->
                val image = frames.render(project.copy(width = 360,height = 640,clips = mutableListOf(c)),.5,180,320)
                check(image.width == 180 && image.height == 320); image.recycle()
            }
            check(SourceAudio.hash(source) == originalHash)
        }
        test("stabilization Off is pixel-exact and legacy recipes retain their original processing") {
            val off = project.duplicate().apply { clips.single().effects.single().values["level"] = "Off" }
            val plain = project.duplicate().apply { clips.single().effects.clear() }
            Frames().use { frames ->
                val left = frames.render(off,.507,320,180); val right = frames.render(plain,.507,320,180)
                check(left.sameAs(right)); left.recycle(); right.recycle()
            }
            val legacy = Effect(kind = "Stabilize",values = mutableMapOf("strength" to ".6"))
            val saved = project.duplicate().apply { clips.single().effects.apply { clear(); add(legacy) } }
            check(a.store.decode(a.store.encode(saved)).clips.single().effects.single() == legacy)
            val future = a.store.encode(project)
            future.getJSONArray("clips").getJSONObject(0).getJSONArray("effects").getJSONObject(0).put("algorithm",6)
            check(runCatching { a.store.decode(future) }.exceptionOrNull() is NewerProject)
        }
        test("stabilization cancellation preserves checked progress without publishing a partial plan") {
            val c = stabilized.duplicate().apply { sourceIn = .13; sourceOut = 1.87 }
            c.effects.single().values.apply { put("referenceStart",".13"); put("referenceEnd","1.87") }
            val folder = StabilizationRenderer.directory!!
            val before = folder.listFiles().orEmpty().filter { it.extension!="checkpoint" }.associate { it.name to SourceAudio.hash(it) }
            var polls = 0
            val error = runCatching {
                Frames(cancelled = { ++polls > 6 }).use { it.render(project.copy(clips = mutableListOf(c)),0.0,320,180) }
            }.exceptionOrNull()
            check(error is java.io.InterruptedIOException && polls > 6) { "Cancellation did not interrupt analysis: $error" }
            check(folder.listFiles().orEmpty().filter { it.extension!="checkpoint" }.associate { it.name to SourceAudio.hash(it) } == before)
        }
        test("stabilization comparison holds the same source frame without preparing motion or saving a bypass") {
            val c = stabilized.duplicate().apply { sourceIn = .13; sourceOut = 1.87 }
            c.effects.single().values.apply { put("referenceStart",".13"); put("referenceEnd","1.87") }
            val scope = project.copy(clips = mutableListOf(c))
            val plain = scope.duplicate().apply { clips.single().effects.clear() }
            val folder = StabilizationRenderer.directory!!
            val before = folder.listFiles().orEmpty().associate { it.name to SourceAudio.hash(it) }
            Frames().use { frames ->
                val original = frames.render(scope,.377,320,180,originalStabilization = true)
                val expected = frames.render(plain,.37,320,180)
                check(original.sameAs(expected)); original.recycle(); expected.recycle()
            }
            check(folder.listFiles().orEmpty().associate { it.name to SourceAudio.hash(it) } == before)
            check(a.store.decode(a.store.encode(scope)) == scope)
        }
        test("Cancel preserves the edit with cached or pending stabilization work") {
            val before = project.duplicate().apply { clips.single().effects.clear() }
            val folder = StabilizationRenderer.directory!!
            val saved = folder.listFiles().orEmpty().filter { it.extension!="checkpoint" }.associate { it.name to SourceAudio.hash(it) }
            onMain {
                a.editor.load(before.duplicate()); a.editor.range = .13..1.87; a.editor.cursor = .5
                a.panels.effect("Stabilize")
            }
            val start = SystemClock.uptimeMillis()+500
            var preparing = false
            while (!preparing && SystemClock.uptimeMillis() < start) {
                onMain { preparing = a.editorView.preparing != null }; if (!preparing) Thread.sleep(20)
            }
            click("Cancel")
            val end = SystemClock.uptimeMillis()+3000
            while (preparing && SystemClock.uptimeMillis() < end) {
                onMain { preparing = a.editorView.preparing != null }; if (preparing) Thread.sleep(20)
            }
            check(!preparing && a.editor.project == before && !a.editor.canUndo)
            val after=folder.listFiles().orEmpty().filter { it.extension!="checkpoint" }.associate { it.name to SourceAudio.hash(it) }
            check(saved.all { (name,hash) -> after[name]==hash })
            for (name in after.keys-saved.keys) check(StabilizationCache.read(File(folder,name),name.substringBeforeLast('.'))!=null)
        }
        for ((name,orientation) in listOf("portrait" to ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,"landscape" to ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
            test("$name stabilization inspector compares cancels applies and undoes levels") {
                onMain { a.requestedOrientation = orientation; a.editor.load(project.duplicate().apply { clips.single().effects.clear() }) }
                Thread.sleep(600)
                val before = a.editor.project.duplicate()
                onMain { a.panels.effect("Stabilize") }
                click("Light")
                val deadline = SystemClock.uptimeMillis()+30000
                var ready = false
                while (!ready && SystemClock.uptimeMillis() < deadline) {
                    onMain {
                        val readout = (find(a.root,"Stabilization crop") as? TextView)?.text?.toString().orEmpty()
                        ready = readout == "Full frame kept" || readout.startsWith("Zoomed in ")
                    }
                    if (!ready) Thread.sleep(100)
                }
                check(ready) { "Stabilization crop readout did not become ready" }
                val screenshot = i.uiAutomation.takeScreenshot()
                File(a.getExternalFilesDir(null),"stabilization-$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG,100,it) }
                screenshot.recycle()
                click("Original"); click("Stabilized"); click("Cancel")
                check(a.editor.project == before && !a.editor.canUndo)
                onMain { a.panels.effect("Stabilize") }; click("Strong"); click("Apply")
                val applied = a.editor.project.clips.single().effects.single()
                check(StabilizationSettings.modern(applied) && StabilizationSettings.level(applied) == StabilizationLevel.Strong)
                check(a.store.current().clips.single().effects.single() == applied)
                onMain { a.editor.undo() }; check(a.editor.project == before)
                onMain { a.editor.redo(); a.panels.effect("Stabilize",a.editor.project.clips.single().effects.single()) }
                click("Remove stabilization"); check(a.editor.project.clips.single().effects.isEmpty())
                onMain { a.editor.load(before.duplicate()); a.editor.applyEffect(Effect(kind = "Stabilize",values = mutableMapOf("strength" to ".6"))) }
                val legacy = a.editor.project.duplicate()
                onMain { a.panels.effect("Stabilize",a.editor.project.clips.single().effects.single()) }
                check(find(a.root,"Use stabilization levels") != null); click("Cancel"); check(a.editor.project == legacy)
            }
        }
        results.put("source_sha256",originalHash)
        File(a.getExternalFilesDir(null),"stabilization-results.json").writeText(results.toString(2))
    }
}
