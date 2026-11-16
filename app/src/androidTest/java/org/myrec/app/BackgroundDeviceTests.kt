package org.myrec.app

import android.app.Instrumentation
import android.content.pm.ActivityInfo
import android.graphics.*
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File
import java.nio.*
import kotlin.math.*

object BackgroundDeviceTests {
    private fun set(fx: Effect,key: String,value: EffectValue) { fx.parameter(key)!!.value=value }
    private fun choice(fx: Effect,key: String,value: String)=set(fx,key,EffectValue.Choice(value))
    private fun find(view: View,title: String): View? {
        if (view.contentDescription?.toString()==title || view is TextView && view.text.toString()==title) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i),title)?.let { return it }
        return null
    }
    fun run(i: Instrumentation,a: MainActivity,main: (()->Unit)->Unit,test: (String,()->Unit)->Unit) {
        val root=File(a.cacheDir,"background-${uid()}").apply { mkdirs() }
        val evidence=File(a.getExternalFilesDir(null),"background-evidence").apply { mkdirs() }
        fun snapshot(name: String)=main {
            val bitmap=Bitmap.createBitmap(a.root.width,a.root.height,Bitmap.Config.ARGB_8888)
            a.root.draw(Canvas(bitmap))
            File(evidence,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        }
        fun asset(name: String)=File(root,name).also { file -> i.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } } }
        val video=asset("fixture.mp4"); val personVideo=asset("person.mp4"); val person=asset("person.png")
        fun image(color: Int,w: Int=64,h: Int=48)=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        fun key()=Effect.create("Chroma Key").apply { setNumber("softness",0f); setNumber("wrap",0f); setNumber("despill",0f); set(this,"autoScreen",EffectValue.Toggle(false)) }
        fun rgb(c: Int)=listOf(Color.red(c),Color.green(c),Color.blue(c))
        test("chroma key matches the reference linear channel-difference equation for green and blue") {
            fun linear(x: Int): Double { val v=x/255.0; return if (v<=.04045) v/12.92 else ((v+.055)/1.055).pow(2.4) }
            for (blue in listOf(false,true)) for (balance in listOf(.2f,.5f,.8f)) for (green in listOf(40,100,180,255)) {
                val fx=key().apply { setNumber("balance",balance) }
                if (blue) { choice(fx,"screenType","Blue");set(fx,"screenColor",EffectValue.Color(0.0,0.0,1.0)) }
                val bitmap=image(if (blue) Color.rgb(50,30,green) else Color.rgb(50,green,30))
                try {
                    val m=Native.backgroundMatte(bitmap,null,null,BackgroundEffect.options(fx))
                    val other=linear(30); val r=linear(50)
                    val d=linear(green)-(if (balance==.5f) max(r,other) else balance*r+(1-balance)*other)
                    val expected=((1-d.coerceIn(0.0,1.0)-.05)/.85).coerceIn(0.0,1.0)
                    check(abs(m[24*64+32]-expected)<.00002) { "Key matte ${m[0]} != $expected" }
                } finally { bitmap.recycle() }
            }
        }
        test("automatic border sampling selects blue and a spatial clean plate removes uneven green") {
            val bitmap=image(Color.BLUE); val fx=key().apply { set(this,"autoScreen",EffectValue.Toggle(true)) }
            val data=Native.backgroundMatte(bitmap,null,null,BackgroundEffect.options(fx))
            check(data.take(64*48).all { it<.00001 } && data[64*48+2]>.99)
            bitmap.eraseColor(Color.rgb(20,140,25)); fx.setNumber("strength",1f)
            val plate=bitmap.copy(Bitmap.Config.ARGB_8888,true)
            try { check(Native.backgroundMatte(bitmap,null,plate,BackgroundEffect.options(fx)).take(64*48).all { it<.00001 }) }
            finally { bitmap.recycle(); plate.recycle() }
        }
        test("unmixed transparent fringes stay bounded and composite in linear light in both bitmap formats") {
            val fx=key(); val bg=image(Color.BLACK,1,1)
            for (format in listOf(Bitmap.Config.ARGB_8888,Bitmap.Config.RGBA_F16)) {
                val bitmap=if (format==Bitmap.Config.RGBA_F16) WorkingColor.bitmap(8,8) else image(Color.BLACK,8,8)
                bitmap.eraseColor(Color.WHITE)
                val alpha=FloatArray(8*8+3) { if (it<64) .001f else if (it==65) 1f else 0f }
                try {
                    Native.backgroundComposite(bitmap,bg,null,alpha,8,8,BackgroundEffect.options(fx))
                    check(Color.red(bitmap.getPixel(4,4))<=5) { "Transparent fringe became opaque: ${rgb(bitmap.getPixel(4,4))}" }
                    bitmap.eraseColor(Color.RED)
                    alpha.fill(.5f,0,64); alpha[64]=0f; alpha[65]=1f; alpha[66]=0f
                    Native.backgroundComposite(bitmap,bg,null,alpha,8,8,BackgroundEffect.options(fx))
                    check(Color.red(bitmap.getPixel(4,4)) in 186..189)
                } finally { bitmap.recycle() }
            }
            bg.recycle()
        }
        test("despill light wrap colour matching matte view and intensity match independent composite equations") {
            fun linear(v: Int): Double { val x=v/255.0;return if (x<=.04045) x/12.92 else ((x+.055)/1.055).pow(2.4) }
            fun encoded(v: Double): Int { val x=v.coerceIn(0.0,1.0);return ((if (x<=.0031308) x*12.92 else 1.055*x.pow(1/2.4)-.055)*255).roundToInt() }
            val source=rgb(Color.rgb(130,220,90)).map(::linear)
            val background=rgb(Color.rgb(60,90,190)).map(::linear)
            val screen=listOf(0.0,linear(180),0.0)
            for (kind in BackgroundEffect.kinds) for (view in listOf("Result","Matte","Original")) for (intensity in listOf(.25f,1f)) {
                val fx=Effect.create(kind).apply { setNumber("intensity",intensity);setNumber("wrap",.7f);set(this,"matchColor",EffectValue.Toggle(true));choice(this,"view",view) }
                val input=image(Color.rgb(130,220,90));val bg=image(Color.rgb(60,90,190),1,1)
                val alpha=.6;val matte=FloatArray(64*48+3) { if (it<64*48) alpha.toFloat() else screen[it-64*48].toFloat() }
                try {
                    val straight=source.indices.map { c -> ((source[c]-(1-alpha)*(if (kind=="Chroma Key") screen[c] else source[c]))/alpha).coerceIn(0.0,1.0) }.toMutableList()
                    if (kind=="Chroma Key") {
                        val before=straight[1];val limit=(straight[0]+straight[2])*.525
                        straight[1]=min(before,before+(limit-before)*.8)
                        val removed=before-straight[1]
                        for (c in 0..2) straight[c]+=removed*listOf(.3,.6,.1)[c]*.5
                    }
                    val expected=(0..2).map { c ->
                        val matched=straight[c]*(1+((background[c]/max(source[c],.01)).coerceIn(.5,2.0)-1)*.3)
                        val result=when (view) { "Matte" -> alpha;"Original" -> source[c];else -> matched*alpha+background[c]*(1-alpha)+background[c]*alpha*(1-alpha)*.7 }
                        encoded(source[c]+(result.coerceIn(0.0,1.0)-source[c])*intensity)
                    }
                    Native.backgroundComposite(input,bg,null,matte,64,48,BackgroundEffect.options(fx))
                    check(rgb(input.getPixel(32,24)).zip(expected).all { (a,b) -> abs(a-b)<=1 }) { "$kind/$view/$intensity ${rgb(input.getPixel(32,24))} != $expected" }
                } finally { input.recycle();bg.recycle() }
            }
        }
        test("broad full HD background blur retains constant colour and applies linear exposure") {
            val input=image(Color.rgb(70,100,140),1920,1080)
            val start=SystemClock.elapsedRealtime()
            Native.backgroundBlur(input,12f,1f)
            val elapsed=SystemClock.elapsedRealtime()-start
            check(elapsed<10000) { "Full HD background blur took $elapsed ms" }
            for ((x,y) in listOf(0 to 0,960 to 540,1919 to 1079)) check(rgb(input.getPixel(x,y)).zip(listOf(98,138,192)).all { (a,b) -> abs(a-b)<=1 })
            i.sendStatus(0,android.os.Bundle().apply { putString("stream","Full HD background blur: $elapsed ms\n") })
            input.recycle()
        }
        test("person inference edge refinement and background rendering are finite and repeatable") {
            val source=PhotoSource.frame(person.path,384,384)
            val mask=Person.mask(source)
            check(mask.size==192*192 && mask.all { it.isFinite() && it in 0f..1f })
            check(mask.max()>.9f && mask.min()<.1f)
            val fx=Effect.create("Auto Background").apply { choice(this,"background","Solid"); set(this,"backgroundColor",EffectValue.Color(0.0,0.0,1.0)) }
            val first=source.copy(Bitmap.Config.ARGB_8888,true); val second=source.copy(Bitmap.Config.ARGB_8888,true)
            BackgroundRenderer().use { it.render(first,fx,0.0,0.0,"fixture",emptyList()) }
            BackgroundRenderer().use { it.render(second,fx,0.0,0.0,"fixture",emptyList()) }
            check(first.sameAs(second) && !first.sameAs(source))
            first.recycle(); second.recycle(); source.recycle()
        }
        val still=File(root,"wide.png")
        image(Color.RED,120,40).also { bitmap -> still.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle() }
        test("replacement image framing preserves fit bars and fill coverage with owned media") {
            val input=image(Color.GREEN,60,60); val fx=key().apply { choice(this,"background","Image");set(this,"media",EffectValue.Media(still.path)) }
            BackgroundRenderer().use { renderer ->
                choice(fx,"fit","Fit")
                renderer.background(input,fx,0.0).also { check(it.getPixel(30,0)==Color.BLACK && Color.red(it.getPixel(30,30))>250);it.recycle() }
                choice(fx,"fit","Fill")
                renderer.background(input,fx,0.0).also { check(Color.red(it.getPixel(30,0))>250);it.recycle() }
                set(fx,"media",EffectValue.Media(null))
                renderer.render(input,fx,0.0,0.0,"empty",emptyList())
            }
            input.recycle()
        }
        test("video background loops remain deterministic and advance independently of held foregrounds") {
            val input=image(Color.GREEN,160,90)
            val fx=key().apply { choice(this,"background","Video");set(this,"media",EffectValue.Media(video.path)) }
            val duration=ProjectStore.inspect(video).sourceOut
            val period=duration-min(.2,duration/2)
            BackgroundRenderer().use { renderer ->
                val first=renderer.background(input,fx,.25)
                val again=renderer.background(input,fx,period*2+.25)
                check(first.sameAs(again));first.recycle();again.recycle()
            }
            val green=File(root,"green.png")
            input.also { green.outputStream().use { out -> it.compress(Bitmap.CompressFormat.PNG,100,out) } }.recycle()
            val clip=Clip(path=green.path,photo=true,audio=false,sourceOut=1.0,frozen=FrozenFrame(0.0,1.0),effects=mutableListOf(fx))
            Frames().use { frames ->
                val project=Project(width=160,height=90,clips=mutableListOf(clip))
                val first=frames.render(project,.1,160,90);val second=frames.render(project,.6,160,90)
                check(!first.sameAs(second)) { "The foreground hold froze its background" }
                first.recycle();second.recycle()
            }
        }
        test("video matte history produces identical frames after forward and backward seeks") {
            val fx=Effect.create("Auto Background").apply { choice(this,"background","Studio") }
            val clip=ProjectStore.inspect(personVideo).apply { audio=false;effects.add(fx) }
            val project=Project(width=160,height=90,clips=mutableListOf(clip))
            clip.effects.add(Effect(kind="Night fix").apply { setNumber("motion",1f) })
            clip.effects.add(Effect(kind="Color LUTs",values=mutableMapOf("lut" to "Warm")))
            Frames().use { frames ->
                val first=frames.render(project,.3,160,90)
                frames.render(project,.6,160,90).recycle();frames.render(project,.1,160,90).recycle()
                val repeated=frames.render(project,.3,160,90)
                try { check(first.sameAs(repeated)) { "A seek changed the source-bound matte" } }
                finally { first.recycle();repeated.recycle() }
            }
        }
        test("background video audio mixes at minus eighteen dB with muted foreground and deterministic seeks") {
            val fx=key().apply { choice(this,"background","Video");set(this,"media",EffectValue.Media(video.path));set(this,"backgroundAudio",EffectValue.Toggle(true)) }
            val clip=Clip(path=still.path,sourceOut=1.0,photo=true,audio=false,effects=mutableListOf(fx))
            val project=Project(clips=mutableListOf(clip))
            val cache=AudioCache(File(root,"audio"))
            val raw=ByteBuffer.wrap(cache.get(video.path).readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            AudioMixer(project,cache).use { mixer ->
                mixer.prepare();mixer.seek(.05); val first=mixer.read(4800)
                val begin=(.05*48000).roundToInt()*2
                val gain=10.0.pow(-18.0/20)
                check(first.sumOf { it.toDouble()*it }>0.0001)
                for (n in first.indices) check(abs(first[n]-raw[begin+n]*gain)<.00001)
                mixer.seek(.05);check(first.contentEquals(mixer.read(4800)))
            }
        }
        test("background sound follows typed toggle keys and respects frozen-audio cut") {
            val fx=key().apply {
                choice(this,"background","Video");set(this,"media",EffectValue.Media(video.path))
                parameter("backgroundAudio")!!.apply { setKeyframe(0.0,EffectValue.Toggle(false));setKeyframe(.2,EffectValue.Toggle(true)) }
            }
            val clip=Clip(path=still.path,sourceOut=1.0,photo=true,audio=false,effects=mutableListOf(fx))
            val cache=AudioCache(File(root,"keyed-audio"))
            fun samples(c: Clip)=AudioMixer(Project(clips=mutableListOf(c)),cache).use { it.prepare();it.read(24000) }
            val pcm=samples(clip)
            check(pcm.take(9600*2).all { it==0f } && pcm.drop(12000*2).any { abs(it)>.0001 })
            clip.frozen=FrozenFrame(.3,1.0)
            clip.effects.add(Effect(kind="Freeze Frame",values=mutableMapOf("audio" to "Silence")))
            check(samples(clip).all { it==0f })
        }
        test("background sound uses linear transition ramps without a correlated midpoint boost") {
            val fx=key().apply {
                values["origin"]="0.0";choice(this,"background","Video")
                set(this,"media",EffectValue.Media(video.path));set(this,"backgroundAudio",EffectValue.Toggle(true))
            }
            val left=Clip(path=still.path,photo=true,audio=false,sourceOut=1.0,effects=mutableListOf(fx))
            val right=Clip(path=still.path,photo=true,audio=false,sourceIn=.5,sourceOut=1.5,start=.5,effects=mutableListOf(fx.copyEffect()))
            val project=Project(clips=mutableListOf(left,right),transitions=mutableListOf(Transition(at=.75,duration=.5,leftId=left.id,rightId=right.id)))
            val cache=AudioCache(File(root,"transition-audio"))
            val raw=ByteBuffer.wrap(cache.get(video.path).readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            AudioMixer(project,cache).use { mixer ->
                mixer.prepare();mixer.seek(.74);val pcm=mixer.read(960)
                val first=(.74*48000).roundToInt()*2
                for (n in pcm.indices) check(abs(pcm[n]-raw[first+n]*10.0.pow(-18.0/20))<.00001)
            }
        }
        test("background assets and typed settings survive archive transfer and media remapping") {
            val store=ProjectStore(File(root,"store"))
            val source=video.copyTo(File(store.media,"source.mp4"))
            val picture=still.copyTo(File(store.media,"background.png"))
            val fx=key().apply { values["origin"]="0.25";choice(this,"background","Image");set(this,"media",EffectValue.Media(picture.path));set(this,"cleanPlate",EffectValue.Media(picture.path)) }
            val project=Project(clips=mutableListOf(ProjectStore.inspect(source).apply { effects.add(fx) }))
            store.save(project)
            val zip=File(root,"background.zip");ProjectArchive.export(store,project,zip)
            val restored=ProjectArchive.restore(store,zip)
            check(restored.missing.isEmpty())
            val effect=restored.project.clips.single().effects.single()
            check(effect.values["origin"]=="0.25")
            for (key in listOf("media","cleanPlate")) {
                val path=BackgroundEffect.media(effect,key)!!
                check(path!=picture.path && File(path).readBytes().contentEquals(picture.readBytes()))
            }
        }
        test("background editor controls apply and undo in portrait and landscape") {
            for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                main { a.requestedOrientation=orientation };Thread.sleep(600)
                main {
                    a.editor.project.clips.clear();a.editor.project.transitions.clear();a.editor.clearSelection()
                    a.editor.project.clips.add(ProjectStore.inspect(video));a.editor.cursor=0.0
                    a.editor.applyEffect(Effect.create("Chroma Key"));a.showEditor()
                }
                Thread.sleep(350)
                main { a.panels.videoEffects(a.editor.project.clips.first().effects.single()) };Thread.sleep(250)
                main {
                    check(find(a.root,"Detect green / blue screen")!=null && find(a.root,"Despill")!=null && find(a.root,"Clean plate")!=null)
                    (find(a.root,"Solid")!!.parent as View).performClick()
                }
                Thread.sleep(100)
                main { find(a.choices!!.contentView,"Studio")!!.performClick() }
                Thread.sleep(300)
                snapshot(if (orientation==ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) "chroma-portrait" else "chroma-landscape")
                main {
                    find(a.root,"Apply")!!.performClick()
                    check(BackgroundEffect.choice(a.editor.project.clips.first().effects.single(),"background")=="Studio")
                    a.editor.undo();check(BackgroundEffect.choice(a.editor.project.clips.first().effects.single(),"background")=="Solid")
                }
            }
        }
        test("live background effects retain a camera-owned recipe through recording and export") {
            main { a.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;a.showCapture() }
            lateinit var capture: Capture
            main { capture=(0 until a.root.childCount).map { a.root.getChildAt(it) }.filterIsInstance<Capture>().single();capture.setHdr(false) }
            fun waitFor(message: String,condition: ()->Boolean) {
                val deadline=SystemClock.elapsedRealtime()+15000
                while (!condition() && SystemClock.elapsedRealtime()<deadline) Thread.sleep(50)
                check(condition()) {
                    var detail=""
                    main {
                        val fields=listOf("rendering","effectGeneration","failedEffectGeneration","closed","liveEffects").joinToString { name ->
                            "$name="+Capture::class.java.getDeclaredField(name).apply { isAccessible=true }.get(capture)
                        }
                        val last=MainActivity::class.java.getDeclaredField("lastMessage").apply { isAccessible=true }.get(a)
                        detail="$fields; message=$last; attached=${capture.isAttachedToWindow} size=${capture.width}x${capture.height}"
                    }
                    "$message: $detail"
                }
            }
            val live=Capture::class.java.getDeclaredField("liveEffects").apply { isAccessible=true }
            val generation=Capture::class.java.getDeclaredField("effectGeneration").apply { isAccessible=true }
            val frame=Capture::class.java.getDeclaredField("liveFrame").apply { isAccessible=true }
            waitFor("Camera unavailable") { capture.readyToRecord }
            val sampledColor=EffectValue.Color(.1,.2,.8)
            main {
                live.set(capture,mutableListOf(key()));generation.setInt(capture,generation.getInt(capture)+1)
                capture.effects()
                (find(a.root,"Chroma Key")!!.parent as View).performClick()
                find(a.root,"Sample screen from picture")!!.performClick()
                check(a.panels.samplingBackground)
                val event=android.view.MotionEvent.obtain(0,0,android.view.MotionEvent.ACTION_DOWN,capture.width*.5f,capture.height*.3f,0)
                check(capture.dispatchTouchEvent(event));event.recycle()
                a.panels.sampleBackground(EffectValue.Color(1.0,0.0,0.0));check(a.panels.samplingBackground)
                a.panels.sampleBackground(sampledColor);check(!a.panels.samplingBackground)
                find(a.root,"Done")!!.performClick()
                @Suppress("UNCHECKED_CAST") val effects=live.get(capture) as List<Effect>
                check(effects.single().parameter("screenColor")!!.value==sampledColor)
            }
            main {
                live.set(capture,mutableListOf(Effect.create("Auto Background").apply { choice(this,"background","Window") }))
                generation.setInt(capture,generation.getInt(capture)+1)
            }
            waitFor("Background preview unavailable") { var ready=false;main { ready=frame.get(capture)!=null };ready }
            val previous=a.editor.project.clips.map { it.path }.toSet()
            main { capture.start() };waitFor("Recording did not start") { capture.recording }
            var changes=0;var previousFrame: Any?=null
            repeat(40) {
                main { val current=frame.get(capture);if (current!=null && current!==previousFrame) { changes++;previousFrame=current } }
                Thread.sleep(40)
            }
            check(changes>=2) { "The background camera preview stopped updating" }
            snapshot("live-auto-background");main { capture.stop() }
            waitFor("Recording not added") { a.editor.project.clips.any { it.path !in previous } }
            val clip=a.editor.project.clips.first { it.path !in previous }.duplicate().apply { start=0.0;sourceOut=min(sourceOut,.35);audio=false }
            check(clip.effects.any { it.kind=="Auto Background" && it.values["origin"]=="0.0" })
            main { a.showEditor() }
            val project=Project(width=160,height=90,clips=mutableListOf(clip))
            val output=File(root,"background-export.mp4")
            Export(a.audioCache).run(project,output,160,90,ExportColor.SDR) {}
            check(output.length()>1000 && ProjectStore.inspect(output).sourceOut>.25)
            Frames().use { frames -> val bitmap=frames.frame(output.path,.1,160,90)!!;check(bitmap.width==160 && bitmap.height==90);bitmap.recycle() }
        }
        root.deleteRecursively()
    }
}
