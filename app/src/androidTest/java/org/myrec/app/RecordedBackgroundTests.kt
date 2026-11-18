package org.myrec.app

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Color
import java.io.File
import kotlin.math.*

object RecordedBackgroundTests {
    fun run(i: Instrumentation,a: MainActivity,main: (()->Unit)->Unit,test: (String,()->Unit)->Unit) {
        val root=File(a.cacheDir,"recorded-background-${uid()}").apply { mkdirs() }
        try {
            val source=File(root,"source.mp4").also { f -> i.context.assets.open("person.mp4").use { input -> f.outputStream().use { input.copyTo(it) } } }
            val fx=Effect.create("Auto Background").apply {
                parameter("background")!!.value=EffectValue.Choice("Solid")
                parameter("backgroundColor")!!.value=EffectValue.Color(0.0,0.0,1.0)
                setNumber("wrap",0f);values["origin"]="0.0";values["matteTrack"]="subject"
            }
            val file=File(root,"source.masks");fx.values["matteJournal"]=file.path
            val alpha=FloatArray(64*48+3)
            val signature=BackgroundEffect.signature(fx,emptyList())
            BackgroundJournal.Writer(file).use { writer ->
                for (time in listOf(0.0,.2,.4,.6)) writer.append(BackgroundJournal.Sample("subject",signature,
                    BackgroundMatte(64,48,alpha,FloatArray(16*12),time,16,12),alpha))
                writer.finish(SourceAudio.identity(source) { false })
            }
            val clip=ProjectStore.inspect(source).apply { audio=false;effects.add(fx) }
            val project=Project(width=160,height=90,clips=mutableListOf(clip))
            fun blue(bitmap: Bitmap) {
                try { check(Color.blue(bitmap.getPixel(80,45))>245 && Color.red(bitmap.getPixel(80,45))<3) { "Recorded matte was replaced by fresh segmentation" } }
                finally { bitmap.recycle() }
            }
            test("saved accepted masks drive playback backward seeks freezes and exports") {
                Frames().use { frames -> for (t in listOf(.2,.4,0.0,.2)) blue(frames.render(project,t,160,90)) }
                Frames().use { frames ->
                    val current=frames.render(project,.28,160,90)
                    try {
                        val pixels=IntArray(160*90);current.getPixels(pixels,0,160,0,0,160,90)
                        check(pixels.count { Color.red(it)>40 }>100) { "An unsampled frame kept an old silhouette" }
                    } finally { current.recycle() }
                }
                val hold=project.duplicate();hold.clips[0].frozen=FrozenFrame(.4,1.0)
                Frames().use { blue(it.render(hold,.8,160,90)) }
                val output=File(root,"export.mp4")
                val short=project.duplicate().apply { clips[0].sourceOut=.6 }
                Export(a.audioCache).run(short,output,160,90,ExportColor.SDR) {}
                Frames().use { blue(it.frame(output.path,.4,160,90)!!) }
            }
            test("recorded masks remain source assets through splits undo and archive relocation") {
                val store=ProjectStore(File(root,"store"))
                val owned=source.copyTo(File(store.media,"source.mp4"));val masks=file.copyTo(File(store.media,"source.masks"))
                val editor=Editor(project.duplicate())
                editor.project.clips[0]=editor.project.clips[0].copy(path=owned.path)
                editor.project.clips[0].effects[0].values["matteJournal"]=masks.path
                editor.cursor=.3;editor.split();store.save(editor)
                val zip=File(root,"archive.zip");ProjectArchive.export(store,editor.project,zip)
                val restored=ProjectArchive.restore(store,zip)
                check(restored.missing.isEmpty())
                check(restored.project.clips.all { it.effects[0].values["matteTrack"]=="subject" })
                val path=restored.project.clips[0].effects[0].values["matteJournal"]!!
                check(path!=masks.path && File(path).readBytes().contentEquals(file.readBytes()))
                Frames().use { blue(it.render(restored.project,.4,160,90)) }
                editor.undo();Frames().use { blue(it.render(editor.project,.4,160,90)) }
            }
            test("a changed source or damaged mask fails explicitly instead of silently changing the look") {
                val other=File(root,"changed.mp4").apply { writeBytes(source.readBytes()+byteArrayOf(0)) }
                BackgroundRenderer().use { renderer -> check(runCatching { renderer.journal(fx,other) }.isFailure) }
                val damaged=File(root,"damaged.masks").apply { writeBytes(file.readBytes().also { it[80]=(it[80].toInt() xor 1).toByte() }) }
                BackgroundJournal.Reader(damaged).use { check(runCatching { it.at("subject",.1) }.isFailure) }
            }
            test("rejected and paused camera candidates cannot enter saved matte history") {
                val journal=File(root,"acceptance.masks")
                val gate=RecordedBackground(journal,30,{ bitmap,_ -> bitmap.recycle() },{ throw it })
                val effect=fx.copyEffect().apply { values["matteTrack"]="subject" }
                gate.effects=listOf(effect)
                fun bitmap()=Bitmap.createBitmap(64,48,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
                gate.offer(1_000_000_000,::bitmap);gate.rejected(1_000_000)
                gate.offer(2_000_000_000,::bitmap);gate.accepted(2_000_000,.1)
                gate.running=false;gate.offer(3_000_000_000) { error("Paused recorder read a frame") }
                gate.finish(source)
                BackgroundJournal.Reader(journal).use { check(it.times("subject")==listOf(.1)) }
            }
            test("background audio retains continuous pitch-preserved clocks across constant and animated speed cuts") {
                val video=File(root,"audio-video.mp4").also { f -> i.context.assets.open("fixture.mp4").use { input -> f.outputStream().use { input.copyTo(it) } } }
                val cache=AudioCache(File(root,"audio"))
                for (animated in listOf(false,true)) {
                    val backdrop=Effect.create("Auto Background").apply {
                        values["origin"]="0.35";values["backgroundStart"]="0.35";values["backgroundEnd"]="5.0"
                        parameter("background")!!.value=EffectValue.Choice("Video")
                        parameter("backgroundAudio")!!.value=EffectValue.Toggle(true);parameter("media")!!.value=EffectValue.Media(video.path)
                    }
                    val clip=ProjectStore.inspect(video).apply { sourceIn=.35;sourceOut=5.0;audio=false;rate=if (animated) 1.0 else 1.5;effects.add(backdrop) }
                    if (animated) clip.effects.add(Effect.create("Undercranking").apply {
                        values["origin"]="0.0";values["clockEnd"]="6.0"
                        parameter("speed")!!.setKeyframe(0.0,EffectValue.Number(1.0));parameter("speed")!!.setKeyframe(6.0,EffectValue.Number(1.5))
                    })
                    val project=Project(clips=mutableListOf(clip));val at=.71
                    val expected=AudioMixer(project,cache).use { it.prepare();it.seek(at);it.read(4096) }
                    check(expected.any { abs(it)>.0001f })
                    val edit=Editor(project.duplicate());edit.cursor=.73;edit.split()
                    val actual=AudioMixer(edit.project,cache).use { it.prepare();it.seek(at);it.read(4096) }
                    check(actual.indices.maxOf { abs(actual[it]-expected[it]) }<.0001f) { "A background speed cut changed the waveform (animated=$animated)" }
                    val loop=cache.root.listFiles()!!.first { it.name.endsWith(".loop.f32") }
                    val hash=SourceAudio.identity(loop) { false }
                    java.io.RandomAccessFile(loop,"rw").use { it.seek(loop.length()/2);it.writeInt(0x12345678) }
                    AudioMixer(project,cache).use { it.prepare() }
                    check(SourceAudio.identity(loop) { false }==hash) { "A damaged loop PCM was reused" }
                }
            }
        } finally { root.deleteRecursively() }
        BackgroundDeviceTests.run(i,a,main) { name,body -> if (name.startsWith("live background")) test(name,body) }
        cameraModes(a,main,test)
    }

    private fun cameraModes(a: MainActivity,main: (()->Unit)->Unit,test: (String,()->Unit)->Unit) {
        val manager=a.getSystemService(android.hardware.camera2.CameraManager::class.java)
        fun facing(front: Boolean)=manager.cameraIdList.first { manager.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)==
            if (front) android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT else android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK }
        val rear=facing(false)
        val modes=mutableListOf(Triple("front mirror",true,false))
        if (DualCamera.pairs(manager).isNotEmpty()) modes.add(Triple("dual layout",false,false))
        if (HdrRecorder.supported(manager,rear) && HdrGraphics.support.reason==null) modes.add(Triple("HDR landscape",false,true))
        for ((label,front,hdr) in modes) test("recorded mask geometry and final movie clock survive $label") {
            lateinit var capture: Capture
            main {
                a.requestedOrientation=if (front) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                a.showCapture();capture=(0 until a.root.childCount).map { a.root.getChildAt(it) }.filterIsInstance<Capture>().single()
                capture.setHdr(false);capture.setDualLayout(4);capture.switchCamera(facing(front))
                capture.setMirrorPreview(false);capture.setMirrorRecording(front)
            }
            fun waitFor(message: String,condition: ()->Boolean) {
                val until=android.os.SystemClock.elapsedRealtime()+20000
                while (!condition() && android.os.SystemClock.elapsedRealtime()<until) Thread.sleep(50)
                check(condition()) { message }
            }
            try {
                waitFor("Camera mode unavailable: $label") { capture.readyToRecord }
                main {
                    if (label=="dual layout") capture.setDualLayout(0)
                    if (hdr) capture.setHdr(true)
                }
                waitFor("Camera mode did not settle: $label") { capture.readyToRecord }
                val fx=Effect.create("Auto Background").apply { parameter("background")!!.value=EffectValue.Choice("Studio") }
                main {
                    Capture::class.java.getDeclaredField("liveEffects").apply { isAccessible=true }.set(capture,mutableListOf(fx))
                    Capture::class.java.getDeclaredField("effectGeneration").apply { isAccessible=true }.let { it.setInt(capture,it.getInt(capture)+1) }
                }
                val paths=a.editor.project.clips.map { it.path }.toSet()
                waitFor("Recording did not start: $label") { main { if (!capture.recording) capture.start() };capture.recording }
                Thread.sleep(1800)
                main { capture.stop() }
                val clip=a.editor.project.clips.first { it.path !in paths }
                val effect=clip.effects.first { it.kind=="Auto Background" }
                BackgroundJournal.Reader(File(effect.values.getValue("matteJournal"))).use { reader ->
                    val track=effect.values.getValue("matteTrack");val times=reader.times(track);check(times.size>=2)
                    val movie=RecordedBackground.movieTimes(File(clip.path))
                    check(times.all { movie.binarySearch(it)>=0 })
                    val last=reader.at(track,times.last())!!
                    Frames().use { frames ->
                        val raw=frames.frame(clip.path,times.last(),512,512)!!
                        try { check(abs(raw.width.toDouble()/raw.height-last.raw.width.toDouble()/last.raw.height)<.02) { "Mask orientation differs from the recorded picture" } }
                        finally { raw.recycle() }
                    }
                }
                if (hdr) {
                    check(WorkingColor.hdr(clip.path)) { "HDR capture silently changed to SDR" }
                }
                val short=Project(width=160,height=90,clips=mutableListOf(clip.duplicate().apply { start=0.0;sourceOut=min(sourceOut,.5);audio=false }))
                val output=File(a.cacheDir,"mask-$label-${uid()}.mp4")
                try { Export(a.audioCache).run(short,output,160,90,ExportColor.SDR) {};check(output.length()>1000) }
                finally { output.delete() }
            } finally {
                main {
                    if (capture.recording) capture.stop()
                    capture.setHdr(false);capture.setDualLayout(4);capture.setMirrorRecording(false);a.showEditor()
                }
            }
        }
    }
}
