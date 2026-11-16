package org.myrec.app

import android.app.Instrumentation
import android.graphics.*
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.util.Half
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

object ExistingEffectsDeviceTests {
    private fun find(view: View,title: String): View? {
        if (view is TextView && view.text.toString()==title || view.contentDescription?.toString()==title) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i),title)?.let { return it }
        return null
    }
    fun run(i: Instrumentation,a: MainActivity,main: (()->Unit)->Unit,test: (String,()->Unit)->Unit) {
        val root=File(a.cacheDir,"existing-effects-${uid()}").apply { mkdirs() }
        fun pattern(w: Int=128,h: Int=80)=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until h) for (x in 0 until w) setPixel(x,y,Color.rgb((x*37+y*19)%256,(x*3+y*41)%256,(x*13+y*7)%256))
        }
        val source=File(root,"vfr.mp4")
        i.context.assets.open("time-vfr.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
        try {
            test("film looks match eighteen independent source-equation fixtures in SDR and half float") {
                val data=i.context.assets.open("time-film-reference.json").bufferedReader().use { JSONObject(it.readText()) }
                val fixtures=data.getJSONArray("fixtures");val colors=data.getJSONArray("colors")
                for (index in 0 until fixtures.length()) {
                    val f=fixtures.getJSONObject(index);val w=f.getInt("width");val h=f.getInt("height");val extended=f.getBoolean("linear")
                    val bitmap=if (extended) WorkingColor.bitmap(w,h) else Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                    val bytes=if (extended) ByteBuffer.allocateDirect(bitmap.byteCount).order(ByteOrder.nativeOrder()) else null
                    val sdr=if (extended) null else IntArray(w*h)
                    for (y in 0 until h) for (x in 0 until w) {
                        val color=colors.getJSONArray((x/7+y/5)%colors.length())
                        if (bytes!=null) {
                            for (k in 0..2) bytes.putShort(y*bitmap.rowBytes+x*8+k*2,Half.toHalf(color.getInt(k)/255f*(if ((x/11+y/13)%3==0) 4 else 1)))
                            bytes.putShort(y*bitmap.rowBytes+x*8+6,Half.toHalf(1f))
                        } else sdr!![y*w+x]=Color.rgb(color.getInt(0),color.getInt(1),color.getInt(2))
                    }
                    if (bytes!=null) bitmap.copyPixelsFromBuffer(bytes) else bitmap.setPixels(sdr!!,0,w,0,0,w,h)
                    val p=f.getJSONArray("parameters")
                    Native.timeFilm(bitmap,f.getInt("kind"),f.getDouble("intensity").toFloat(),FloatArray(p.length()) { p.getDouble(it).toFloat() },f.getLong("frame"),f.getDouble("time"))
                    if (bytes!=null) { bytes.clear();bitmap.copyPixelsToBuffer(bytes) }
                    val points=f.getJSONArray("output")
                    for (j in 0 until points.length()) {
                        val point=points.getJSONArray(j);val x=point.getInt(0);val y=point.getInt(1);val expected=point.getJSONArray(2)
                        for (k in 0..2) {
                            val value=if (bytes!=null) Half.toFloat(bytes.getShort(y*bitmap.rowBytes+x*8+k*2)).toDouble() else (bitmap.getPixel(x,y) ushr (16-k*8) and 255).toDouble()
                            check(abs(value-expected.getDouble(k))<=if (extended) .003 else 1.0) { "Film fixture $index $x,$y channel $k: $value != ${expected.getDouble(k)}" }
                        }
                    }
                    bitmap.recycle()
                }
            }
            test("Night restore has exact neutral bypass fixed exposure and independent chroma cleanup") {
                val original=pattern();val image=original.copy(Bitmap.Config.ARGB_8888,true)
                Native.nightRestore(image,original,1f,0f,0f,.5f);check(image.sameAs(original))
                Native.nightRestore(image,original,0f,8f,3f,0f);check(image.sameAs(original))
                Native.nightRestore(image,original,1f,2.5f,1f,.5f);check(!image.sameAs(original))
                val uniform=Bitmap.createBitmap(32,24,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(40,40,40)) }
                val input=uniform.copy(Bitmap.Config.ARGB_8888,false)
                Native.nightRestore(uniform,input,1f,2f,0f,.5f)
                val linear=((40/255.0+.055)/1.055).pow(2.4);val out=linear*4/(1+3*linear)
                val expected=((1.055*out.pow(1/2.4)-.055)*255).roundToInt()
                check(abs(Color.red(uniform.getPixel(16,12))-expected)<=1)
                original.recycle();image.recycle();uniform.recycle();input.recycle()
            }
            test("Night motion fusion averages stationary noise and rejects a scene cut") {
                fun noisy(seed: Int)=Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888).apply {
                    val random=java.util.Random(seed.toLong())
                    for (y in 0 until height) for (x in 0 until width) {
                        val base=75+(x/12%2)*35+(y/12%2)*15
                        val value=(base+random.nextInt(11)-5).coerceIn(0,255);setPixel(x,y,Color.rgb(value,value,value))
                    }
                }
                val original=noisy(1);val fused=original.copy(Bitmap.Config.ARGB_8888,true);val mass=FloatArray(96*64) { 1f }
                for (n in 2..5) { val neighbor=noisy(n);Native.nightFuse(original,fused,neighbor,mass,n-1,true);neighbor.recycle() }
                fun error(image: Bitmap): Double {
                    var sum=0.0;for (y in 4 until 60) for (x in 4 until 92) {
                        val base=75+(x/12%2)*35+(y/12%2)*15;sum+=(Color.red(image.getPixel(x,y))-base).toDouble().pow(2)
                    };return sum
                }
                check(error(fused)<error(original)*.85) { "Fusion did not improve noise: ${error(fused)} / ${error(original)}" }
                val before=fused.copy(Bitmap.Config.ARGB_8888,false);val scene=pattern(96,64).apply { eraseColor(Color.WHITE) }
                Native.nightFuse(original,fused,scene,mass,1,true);check(before.sameAs(fused))
                listOf(original,fused,before,scene).forEach { it.recycle() }
            }
            test("Super 8 holds grain weave and the complete look through a fractional cadence and seek") {
                val clip=ProjectStore.inspect(source).apply { audio=false;sourceOut=.74;effects.add(Effect.create("Super 8").apply {
                    to=.74;values["origin"]="0.0";values["clockEnd"]="0.74";setNumber("fps",18f)
                }) }
                val project=Project(width=96,height=64,clips=mutableListOf(clip))
                Frames().use { frames ->
                    val first=frames.render(project,.06,96,64,true);val held=frames.render(project,.10,96,64,true)
                    check(first.sameAs(held)) { "Held film look changed between exposures" }
                    frames.render(project,.4,96,64,true).recycle()
                    val seek=frames.render(project,.06,96,64,true);check(first.sameAs(seek))
                    val next=frames.render(project,.12,96,64,true);check(!first.sameAs(next))
                    listOf(first,held,seek,next).forEach { it.recycle() }
                }
                val saved=a.store.decode(a.store.encode(project));check(saved.clips.single().effects==clip.effects)
                val movie=File(root,"super8.mp4");Export(a.audioCache).run(saved,movie,96,64) {}
                check(abs((Mp4Audio.presentedDuration(movie) ?: 0.0)-.74)<.04)
            }
            test("Freeze Print styles the composed frame after push and rotation with animated screen-space grain") {
                val image=pattern(96,64);val file=File(root,"freeze.png")
                file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle()
                val clean=Clip(path=file.path,sourceOut=1.0,photo=true,audio=false,frozen=FrozenFrame(0.0,2.0),effects=mutableListOf(
                    Effect(kind="Rotation",to=2.0,values=mutableMapOf("rotation" to "17")),
                    Effect(kind="Freeze Frame",to=2.0,values=mutableMapOf("style" to "Clean","pushIn" to ".1","hold" to "2.0"))))
                val printed=clean.duplicate().apply { effects.last().values["style"]="Print" }
                Frames().use { frames ->
                    val original=frames.render(Project(clips=mutableListOf(clean)),.9,96,64,true)
                    Native.timeFilm(original,2,1f,floatArrayOf(1f),27,0.0)
                    val actual=frames.render(Project(clips=mutableListOf(printed)),.9,96,64,true)
                    check(original.sameAs(actual)) { "Print grain was transformed with the frozen source" }
                    val later=frames.render(Project(clips=mutableListOf(printed)),.94,96,64,true)
                    check(!actual.sameAs(later));listOf(original,actual,later).forEach { it.recycle() }
                }
            }
            test("Crash Zoom locks its target has optional return and radial motion blur") {
                val original=pattern();val zoomed=original.copy(Bitmap.Config.ARGB_8888,true)
                Native.crashZoom(zoomed,1f,0f,.5f,.5f);check(zoomed.sameAs(original))
                Native.crashZoom(zoomed,2.5f,0f,.3f,.6f);check(!zoomed.sameAs(original))
                val blurred=original.copy(Bitmap.Config.ARGB_8888,true);Native.crashZoom(blurred,2.5f,.12f,.3f,.6f)
                check(!blurred.sameAs(zoomed))
                val fx=Effect.create("Crash Zoom");val end=original.copy(Bitmap.Config.ARGB_8888,true)
                fx.parameter("return")!!.value=EffectValue.Toggle(true);Effects.correct(end,fx,1f,elapsed=10.0)
                check(end.sameAs(original));listOf(original,zoomed,blurred,end).forEach { it.recycle() }
            }
            test("continuous audio ramp preserves both stereo pitches and has stable split and seek samples") {
                val count=48000*4;val wav=ByteBuffer.allocate(44+count*4).order(ByteOrder.LITTLE_ENDIAN)
                wav.put("RIFF".toByteArray()).putInt(36+count*4).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(2)
                    .putInt(48000).putInt(192000).putShort(4).putShort(16).put("data".toByteArray()).putInt(count*4)
                repeat(count) { n -> repeat(2) { c -> wav.putShort((.2*sin(2*PI*(if (c==0) 440 else 733)*n/48000)*32767).roundToInt().toShort()) } }
                val file=File(root,"ramp.wav").apply { writeBytes(wav.array()) }
                val clip=ProjectStore.inspect(file).apply { effects.add(Effect.create("Undercranking").apply {
                    to=4.0;values["origin"]="0.0";values["clockEnd"]="4.0"
                    parameter("speed")!!.setKeyframe(0.0,EffectValue.Number(1.0));parameter("speed")!!.setKeyframe(4.0,EffectValue.Number(1.5))
                }) }
                val project=Project(clips=mutableListOf(clip))
                fun pitch(pcm: FloatArray,c: Int): Double {
                    var crosses=0;for (n in 1 until pcm.size/2) if (pcm[(n-1)*2+c]<=0 && pcm[n*2+c]>0) crosses++
                    return crosses*48000.0/(pcm.size/2-1)
                }
                var expected=FloatArray(0)
                AudioMixer(project,a.audioCache).use { mixer ->
                    mixer.prepare()
                    for (time in listOf(.5,1.3,2.3)) {
                        mixer.seek(time);val pcm=mixer.read(9600)
                        check(abs(pitch(pcm,0)-440)<12 && abs(pitch(pcm,1)-733)<12)
                    }
                    mixer.seek(1.1);expected=mixer.read(2048)
                }
                val editor=Editor(project.duplicate());editor.cursor=.9;editor.split()
                AudioMixer(editor.project,a.audioCache).use { mixer ->
                    mixer.prepare();mixer.seek(1.1);val actual=mixer.read(2048)
                    check(actual.indices.all { abs(actual[it]-expected[it])<.00001 }) { "A split changed continuous ramp audio" }
                }
                val fx=clip.effects.single();fx.parameter("mute")!!.value=EffectValue.Toggle(true)
                AudioMixer(project,a.audioCache).use { mixer -> mixer.prepare();mixer.seek(.5);check(mixer.read(1024).all { it==0f }) }
            }
            test("background audio uses one source-owned ramp through loop seams and ignores source mute") {
                val tones=File(root,"ramp.wav");check(tones.isFile)
                val background=File(root,"background.mp4")
                val backgroundProject=Project(width=96,height=64,clips=mutableListOf(ProjectStore.inspect(source).apply { audio=false },ProjectStore.inspect(tones).apply { sourceOut=1.0 }))
                Export(a.audioCache).run(backgroundProject,background,96,64) {}
                val under=Effect.create("Undercranking").apply {
                    to=3.0;values["origin"]="0.0";values["clockEnd"]="3.0";parameter("mute")!!.value=EffectValue.Toggle(true)
                    parameter("speed")!!.setKeyframe(0.0,EffectValue.Number(1.0));parameter("speed")!!.setKeyframe(3.0,EffectValue.Number(1.5))
                }
                val backdrop=Effect.create("Auto Background").apply {
                    to=3.0;values["origin"]="0.0";parameter("background")!!.value=EffectValue.Choice("Video")
                    parameter("backgroundAudio")!!.value=EffectValue.Toggle(true);parameter("media")!!.value=EffectValue.Media(background.path)
                }
                val clip=Clip(path=source.path,sourceOut=3.0,audio=false,effects=mutableListOf(under,backdrop))
                val project=Project(clips=mutableListOf(clip));var expected=FloatArray(0)
                AudioMixer(project,a.audioCache).use { mixer ->
                    mixer.prepare();mixer.seek(1.3);expected=mixer.read(4096)
                    check(expected.any { abs(it)>.005f } && expected.all { it.isFinite() })
                }
                val editor=Editor(project.duplicate());editor.cursor=.6;editor.split()
                AudioMixer(editor.project,a.audioCache).use { mixer ->
                    mixer.prepare();mixer.seek(1.3);val actual=mixer.read(4096)
                    check(actual.indices.all { abs(actual[it]-expected[it])<.00001 }) { "A split changed background ramp phase" }
                }
            }
            test("transition controls persist and fade through selected linear color with clean endpoints") {
                val settings=TransitionControls(vertical=true,divider=.3,reverse=true,audio="Second clip",color=0xffff0000,
                    first=TransitionFraming(.3,.7,1.4),audioOffset=.1,audioBlend=.4,flashCount=5,flashAcceleration=1.5)
                val transition=Transition(at=.4,kind="Fade through color",duration=.5,controls=settings)
                val project=Project(clips=mutableListOf(ProjectStore.inspect(source)),transitions=mutableListOf(transition))
                val saved=a.store.decode(a.store.encode(project));check(saved.transitions.single().controls==settings)
                val first=pattern();val second=pattern().apply { eraseColor(Color.BLUE) };val output=first.copy(Bitmap.Config.ARGB_8888,true)
                val p=floatArrayOf(0f,.5f,0f,.5f,.5f,1f,.5f,.5f,1f,1f,0f,0f,0f,0f)
                Native.transition(first,second,output,4,.5f,.75f,0f,true,p)
                check(output.getPixel(64,40)==Color.RED)
                for (kind in 0..8) {
                    Native.transition(first,second,output,kind,0f,.75f,0f,true,p);check(output.sameAs(first))
                    Native.transition(first,second,output,kind,1f,.75f,0f,true,p);check(output.sameAs(second))
                }
                listOf(first,second,output).forEach { it.recycle() }
            }
            test("Night cache reuses source restoration independently of adjustment controls") {
                val clip=ProjectStore.inspect(source).apply { audio=false;sourceOut=.74;effects.add(Effect.create("Night fix").apply {
                    to=.74;parameter("mode")!!.value=EffectValue.Choice("Medium")
                }) }
                val project=Project(clips=mutableListOf(clip));val old=NightRestore.directory
                NightRestore.directory=File(root,"night-cache")
                try {
                    Frames().use { frames ->
                        val first=frames.render(project,.11,96,64,true)
                        val files=NightRestore.directory!!.walkTopDown().filter { it.extension=="f16" }.toList();check(files.size==1)
                        val partial=File(NightRestore.directory,"interrupted.f16.tmp").apply { writeText("unfinished") }
                        val orphan=File(NightRestore.directory,"interrupted.f16.sum").apply { writeText("unfinished") }
                        NightRestore.directory=NightRestore.directory
                        check(!partial.exists() && !orphan.exists() && files.single().isFile)
                        clip.effects.single().setNumber("brightness",3f)
                        val changed=frames.render(project,.11,96,64,true);check(!first.sameAs(changed))
                        check(NightRestore.directory!!.walkTopDown().count { it.extension=="f16" }==1)
                        clip.effects.single().setNumber("brightness",2.5f)
                        val repeated=frames.render(project,.11,96,64,true);check(first.sameAs(repeated))
                        listOf(first,changed,repeated).forEach { it.recycle() }
                    }
                } finally { NightRestore.directory=old }
            }
            test("Night cancellation releases working images and resumes without publishing a partial restoration") {
                val clip=ProjectStore.inspect(source).apply { audio=false;effects.add(Effect.create("Night fix").apply {
                    to=.74;parameter("mode")!!.value=EffectValue.Choice("Precise")
                }) }
                val project=Project(clips=mutableListOf(clip));val old=NightRestore.directory
                NightRestore.directory=File(root,"cancel-night");var polls=0;var cancelled=false
                try {
                    try { Frames(cancelled={ ++polls>80 }).use { it.render(project,.11,128,80,true).recycle() } }
                    catch (_: java.io.InterruptedIOException) { cancelled=true }
                    check(cancelled && polls<100) { "Restoration did not cancel within the active frame" }
                    check(NightRestore.directory!!.walkTopDown().none { it.extension=="f16" })
                    Frames().use { it.render(project,.11,128,80,true).recycle() }
                    check(NightRestore.directory!!.walkTopDown().count { it.extension=="f16" }==1)
                } finally { NightRestore.directory=old }
            }
            test("patch subject tracking follows translation and rejects textureless targets") {
                val previous=Bitmap.createBitmap(160,100,Bitmap.Config.ARGB_8888).apply {
                    val random=java.util.Random(11)
                    for (y in 0 until height) for (x in 0 until width) setPixel(x,y,Color.rgb(random.nextInt(256),random.nextInt(256),random.nextInt(256)))
                }
                val current=Bitmap.createBitmap(160,100,Bitmap.Config.ARGB_8888)
                Canvas(current).drawBitmap(previous,7f,-4f,Paint())
                val tracked=Native.trackSubject(previous,current,floatArrayOf(.5f,.5f,.3f,.35f))!!
                check(tracked[4]>.8f && abs(tracked[0]-(.5f+7f/160))<.018f && abs(tracked[1]-(.5f-4f/100))<.018f) { tracked.contentToString() }
                previous.eraseColor(Color.GRAY);current.eraseColor(Color.GRAY)
                check(Native.trackSubject(previous,current,floatArrayOf(.5f,.5f,.3f,.35f))!![4]<.6f)
                previous.recycle();current.recycle()
            }
            test("modern still and solid holds export the fitted picture audio union and reopen sliced clocks") {
                for (kind in listOf("Dissolve","Fade through color","Still")) {
                    val editor=Editor(Project(width=96,height=64,clips=mutableListOf(ProjectStore.inspect(source).apply { sourceOut=.7;audio=false },
                        ProjectStore.inspect(source).apply { start=.7;sourceOut=.7;audio=false })))
                    editor.putTransition(Transition(at=.7,kind=kind,duration=.2,hold=if (kind=="Still") 0.0 else .1,controls=TransitionControls()))
                    val project=a.store.decode(a.store.encode(editor.project));val tr=project.transitions.single()
                    val slice=tr.slice(tr.begin+.02,tr.end-.02)!!
                    val sliced=a.store.decode(a.store.encode(project.copy(transitions=mutableListOf(slice)))).transitions.single()
                    check(abs(sliced.pictureOrigin-tr.pictureOrigin)<1e-6)
                    val movie=File(root,"transition-$kind.mp4")
                    Export(a.audioCache).run(project,movie,96,64) {}
                    check(abs((Mp4Audio.presentedDuration(movie) ?: 0.0)-project.duration)<.04)
                    VideoDecoder(movie.path).use { decoder -> decoder.frame(project.duration-.04,96,64)!!.recycle() }
                }
            }
            test("effect and freeze inspectors expose reference controls with Apply Cancel draft isolation") {
                main {
                    a.editor.project=Project(clips=mutableListOf(ProjectStore.inspect(source)))
                    a.editor.cursor=.1
                    for ((kind,title) in listOf("Night fix" to "Shadow brightness","Undercranking" to "Gate weave","Super 8" to "Light leaks","Crash Zoom" to "Push duration")) {
                        val fx=Effect.create(kind).apply { to=.74 };a.editor.project.clips.single().effects.clear();a.editor.project.clips.single().effects.add(fx)
                        a.panels.videoEffects(fx);find(a.root,kind)?.performClick()
                        check(find(a.root,title)!=null) { "Missing $kind $title" };a.panels.close()
                    }
                    a.editor.project.clips.single().effects.clear();a.panels.effect("Freeze Frame")
                    check(find(a.root,"Print")!=null && find(a.root,"Fade out")!=null);a.panels.close()
                }
            }
            test("live Crash Zoom triggers by button and volume key and records only the applied camera recipe") {
                main { a.showCapture() }
                lateinit var capture: Capture
                main { capture=(0 until a.root.childCount).map { a.root.getChildAt(it) }.filterIsInstance<Capture>().single();capture.setHdr(false);capture.setDualLayout(4) }
                fun waitFor(message: String,predicate: ()->Boolean) {
                    val until=android.os.SystemClock.elapsedRealtime()+20000
                    while (!predicate() && android.os.SystemClock.elapsedRealtime()<until) Thread.sleep(30)
                    check(predicate()) { message }
                }
                waitFor("Camera is unavailable") { capture.readyToRecord }
                val live=Capture::class.java.getDeclaredField("liveEffects").apply { isAccessible=true }
                val generation=Capture::class.java.getDeclaredField("effectGeneration").apply { isAccessible=true }
                val frame=Capture::class.java.getDeclaredField("liveFrame").apply { isAccessible=true }
                val crash=Effect.create("Crash Zoom");val dolly=Effect.create("Dolly Zoom")
                main {
                    live.set(capture,mutableListOf(crash,dolly));generation.setInt(capture,generation.getInt(capture)+1)
                    check(capture.triggerCameraEffect());check(capture.toggleDolly());check(capture.toggleDolly())
                }
                waitFor("Crash Zoom did not render a live frame") { var ready=false;main { ready=frame.get(capture)!=null };ready }
                val old=a.editor.project.clips.map { it.path }.toSet()
                main { capture.start() };waitFor("Recording did not start") { capture.recording }
                Thread.sleep(250)
                main { check(a.onKeyDown(android.view.KeyEvent.KEYCODE_VOLUME_UP,android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_VOLUME_UP))) }
                Thread.sleep(400);main { check(capture.triggerCameraEffect(true)) }
                Thread.sleep(300);main { capture.stop() }
                waitFor("The new recording was not saved") { a.editor.project.clips.any { it.path !in old } }
                val clip=a.editor.project.clips.first { it.path !in old }.duplicate().apply { start=0.0;audio=false }
                check(clip.effects.any { it.kind=="Crash Zoom" } && clip.effects.none { it.kind=="Dolly Zoom" })
                val reopened=a.store.decode(a.store.encode(Project(width=96,height=64,clips=mutableListOf(clip))))
                check(reopened.clips.single().effects.any { it.kind=="Crash Zoom" && it.values["release"]!=null })
                main { a.showEditor() }
                Export(a.audioCache).run(reopened,File(root,"live-crash.mp4"),96,64) {}
            }
        } finally { root.deleteRecursively() }
    }
}
