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
        previewRotation(File(a.getExternalFilesDir(null),"rotation-preview"),test)
        cameraModes(a,main,test)
    }

    private fun previewRotation(evidence: File,test: (String,()->Unit)->Unit) {
        evidence.mkdirs()
        for (hint in listOf(0,90)) test("recorded background preview keeps its geometry through rotation (movie turn $hint)") {
            val thread=android.os.HandlerThread("mask-rotation-test").apply { start() }
            fun reader()=android.media.ImageReader.newInstance(320,180,android.graphics.PixelFormat.RGBA_8888,3).apply {
                setOnImageAvailableListener({ it.acquireLatestImage()?.close() },android.os.Handler(thread.looper))
            }
            val window=reader();val movie=reader()
            val failure=java.util.concurrent.atomic.AtomicReference<Throwable>()
            val frames=java.util.concurrent.ArrayBlockingQueue<Bitmap>(1)
            val renderer=CameraComposition(window.surface,320,180) { failure.set(it) }
            try {
                val input=renderer.input(640,360,90,false)
                renderer.analysisFrame={ _,read -> val bitmap=read();if (!frames.offer(bitmap)) bitmap.recycle() }
                renderer.record(movie.surface,320,180,hint)
                val phases=if (hint==0) listOf(false,true,false,true) else listOf(true,false,true,false)
                for (portrait in phases) {
                    val w=if (portrait) 180 else 320;val h=if (portrait) 320 else 180
                    renderer.configure(4,false,if (portrait) 0 else 90,w,h)
                    while (true) { val old=frames.poll() ?: break;old.recycle() }
                    val canvas=input.lockCanvas(null)
                    val paint=android.graphics.Paint()
                    paint.shader=android.graphics.LinearGradient(0f,0f,640f,360f,Color.rgb(20,40,80),Color.rgb(230,180,140),android.graphics.Shader.TileMode.CLAMP)
                    canvas.drawRect(0f,0f,640f,360f,paint)
                    paint.shader=null;paint.color=Color.GREEN;canvas.drawCircle(320f,180f,70f,paint)
                    input.unlockCanvasAndPost(canvas)
                    val recorded=frames.poll(4,java.util.concurrent.TimeUnit.SECONDS) ?: throw failure.get() ?: error("No rotation analysis frame")
                    val expected=renderer.previewFrame(w,h) ?: error("No reference preview")
                    val actual=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                    try {
                        val crop=CameraReadback.previewCrop(recorded.width,recorded.height,w,h)
                        android.graphics.Canvas(actual).drawBitmap(recorded,crop,android.graphics.Rect(0,0,w,h),android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                        for ((name,bitmap) in listOf("actual" to actual,"expected" to expected,"recorded" to recorded))
                            File(evidence,"$hint-$portrait-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                        fun bounds(bitmap: Bitmap): android.graphics.Rect {
                            val box=android.graphics.Rect(w,h,0,0)
                            for (y in 0 until h) for (x in 0 until w) {
                                val color=bitmap.getPixel(x,y)
                                if (Color.green(color)>230 && Color.red(color)<20 && Color.blue(color)<20) {
                                    box.left=min(box.left,x);box.top=min(box.top,y);box.right=max(box.right,x+1);box.bottom=max(box.bottom,y+1)
                                }
                            }
                            check(!box.isEmpty);return box
                        }
                        val actualBox=bounds(actual);val expectedBox=bounds(expected)
                        check(listOf(actualBox.left-expectedBox.left,actualBox.top-expectedBox.top,actualBox.right-expectedBox.right,actualBox.bottom-expectedBox.bottom).all { abs(it)<=3 }) {
                            "Rotation changed the subject bounds: $actualBox instead of $expectedBox"
                        }
                        for (v in listOf(.2f,.4f,.6f,.8f)) for (u in listOf(.2f,.4f,.6f,.8f)) {
                            val x=(u*w).toInt();val y=(v*h).toInt();val a=actual.getPixel(x,y);val b=expected.getPixel(x,y)
                            if (Color.green(a)>max(Color.red(a),Color.blue(a))+20 || Color.green(b)>max(Color.red(b),Color.blue(b))+20) continue
                            check(listOf(Color.red(a)-Color.red(b),Color.green(a)-Color.green(b),Color.blue(a)-Color.blue(b)).all { abs(it)<=12 }) {
                                "Rotating the recording changed preview proportions (portrait=$portrait, turn=$hint, x=$x, y=$y, actual=${a.toUInt().toString(16)}, expected=${b.toUInt().toString(16)})"
                            }
                        }
                    } finally { recorded.recycle();expected.recycle();actual.recycle() }
                }
            } finally {
                renderer.record(null);renderer.close();window.close();movie.close();thread.quitSafely()
                while (true) { val bitmap=frames.poll() ?: break;bitmap.recycle() }
            }
        }
    }

    private fun cameraModes(a: MainActivity,main: (()->Unit)->Unit,test: (String,()->Unit)->Unit) {
        val manager=a.getSystemService(android.hardware.camera2.CameraManager::class.java)
        fun facing(front: Boolean)=manager.cameraIdList.first { manager.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)==
            if (front) android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT else android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK }
        val rear=facing(false)
        val modes=mutableListOf(Triple("front mirror",true,false))
        modes.add(Triple("rotation",false,false))
        modes.add(Triple("camera rebuild",false,false))
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
                if (label=="camera rebuild") {
                    val masks=Capture::class.java.getDeclaredField("recordedBackground").apply { isAccessible=true }.get(capture) as RecordedBackground
                    val before=masks.accepted
                    main { capture.pause();capture.suspendCamera();capture.resumeCamera() }
                    waitFor("Rebuilt camera unavailable") { capture.readyToRecord }
                    main { if (capture.paused) capture.pause() }
                    waitFor("Rebuilt camera lost mask analysis") { masks.accepted>=before+2 }
                    Thread.sleep(300)
                }
                if (label=="rotation") {
                    main { a.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                    waitFor("Recording did not rotate") { a.root.width<a.root.height }
                    Thread.sleep(1500)
                    check(capture.recording) { "Rotation stopped the recording" }
                }
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
                val short=Project(width=160,height=90,clips=mutableListOf(clip.duplicate().apply {
                    start=0.0;if (label=="rotation") sourceIn=max(sourceIn,sourceOut-.5) else sourceOut=min(sourceOut,.5);audio=false
                }))
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
