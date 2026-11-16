package org.myrec.app

import android.app.Instrumentation
import android.content.pm.ActivityInfo
import android.graphics.*
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File
import kotlin.math.*

object EffectAuditTests {
    private fun find(view: View,name: String): View? {
        if (view.contentDescription?.toString()==name || view is TextView && view.text.toString()==name) return view
        if (view is ViewGroup) for (n in 0 until view.childCount) find(view.getChildAt(n),name)?.let { return it }
        return null
    }
    fun run(i: Instrumentation,a: MainActivity,main: (()->Unit)->Unit,test: (String,()->Unit)->Unit) {
        val root=File(a.cacheDir,"effect-audit-${uid()}").apply { mkdirs() }
        fun pattern(w: Int=128,h: Int=80)=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until h) for (x in 0 until w) setPixel(x,y,Color.rgb((x*37+y*19)%256,(x*3+y*41)%256,(x*13+y*7)%256))
        }
        fun pixels(b: Bitmap)=IntArray(b.width*b.height).also { b.getPixels(it,0,b.width,0,0,b.width,b.height) }
        fun difference(a: Bitmap,b: Bitmap)=pixels(a).zip(pixels(b)).count { (a,b) -> a!=b }
        fun linear(v: Int): Double { val x=v/255.0;return if (x<=.04045) x/12.92 else ((x+.055)/1.055).pow(2.4) }
        fun encoded(v: Double)=((if (v<=.0031308) v*12.92 else 1.055*v.pow(1/2.4)-.055)*255).roundToInt().coerceIn(0,255)
        test("Gaussian blur has visible adjustable strength exact neutral bypass and linear intensity") {
            val source=pattern();val full=source.copy(Bitmap.Config.ARGB_8888,true);val half=source.copy(Bitmap.Config.ARGB_8888,true)
            Native.frameBlur(full,1f,2f);Native.frameBlur(half,.5f,2f)
            check(difference(source,full)>source.width*source.height*.95)
            for (y in 0 until source.height step 5) for (x in 0 until source.width step 5) for (shift in listOf(16,8,0)) {
                val expected=encoded((linear(source.getPixel(x,y) ushr shift and 255)+linear(full.getPixel(x,y) ushr shift and 255))*.5)
                check(abs((half.getPixel(x,y) ushr shift and 255)-expected)<=1)
            }
            val same=source.copy(Bitmap.Config.ARGB_8888,true)
            Native.frameBlur(same,0f,10f);Native.frameBlur(same,1f,0f);check(same.sameAs(source))
            same.recycle();source.recycle();full.recycle();half.recycle()
        }
        test("blur and frame kernels handle single pixel axes in both working formats") {
            for ((w,h) in listOf(1 to 1,1 to 7,7 to 1)) for (format in listOf(Bitmap.Config.ARGB_8888,Bitmap.Config.RGBA_F16)) {
                val image=if (format==Bitmap.Config.RGBA_F16) WorkingColor.bitmap(w,h) else Bitmap.createBitmap(w,h,format)
                image.eraseColor(Color.rgb(70,100,140))
                Native.frameBlur(image,1f,4f)
                check(abs(Color.red(image.getPixel(0,0))-70)<=1)
                Native.widescreenFrame(image,1f,0,true,.4f,0)
                Native.legacyVisual(image,0,1f,.2f)
                image.recycle()
            }
        }
        test("widescreen matches reference ratios pillarboxing black and dimmed blurred bars") {
            val ratios=listOf(2.39,2.20,1.85,1.33)
            for ((w,h) in listOf(160 to 90,90 to 160)) for ((index,ratio) in ratios.withIndex()) for (blur in listOf(false,true)) {
                val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(128,128,128)) }
                Native.widescreenFrame(bitmap,1f,index,blur,0f,0)
                for (y in 0 until h) for (x in 0 until w) {
                    val aspect=w.toDouble()/h
                    val bar=if (aspect<ratio) abs((y+.5)/h-.5)>.5*aspect/ratio else abs((x+.5)/w-.5)>.5*ratio/aspect
                    val expected=if (!bar) 128 else if (blur) encoded(linear(128)*.6) else 0
                    check(abs(Color.red(bitmap.getPixel(x,y))-expected)<=1) { "Bars $w/$h $ratio $blur $x/$y" }
                }
                bitmap.recycle()
            }
        }
        test("horizontal flares spread isolated highlights while keeping the final bars clean") {
            val bitmap=Bitmap.createBitmap(192,108,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
            for (y in 50..57) for (x in 94..97) bitmap.setPixel(x,y,Color.WHITE)
            Native.widescreenFrame(bitmap,1f,0,false,1f,0)
            check(Color.blue(bitmap.getPixel(80,54))>8)
            check(Color.blue(bitmap.getPixel(80,54))>Color.red(bitmap.getPixel(80,54)))
            check(bitmap.getPixel(80,30)==Color.BLACK && bitmap.getPixel(95,0)==Color.BLACK)
            bitmap.recycle()
        }
        test("Auto Background retains confident subject detail while whole-picture Blur softens it") {
            val file=File(root,"person.png")
            i.context.assets.open("person.png").use { input -> file.outputStream().use { input.copyTo(it) } }
            val source=PhotoSource.frame(file.path,512,512)
            val mask=Person.mask(source)
            val whole=source.copy(Bitmap.Config.ARGB_8888,true)
            Native.backgroundBlur(whole,12f*min(source.width,source.height)/1080,0f)
            val portrait=source.copy(Bitmap.Config.ARGB_8888,true)
            BackgroundRenderer().use { it.render(portrait,Effect.create("Auto Background"),0.0,0.0,"sharp-person",emptyList()) }
            fun delta(a: Int,b: Int)=listOf(16,8,0).maxOf { abs((a ushr it and 255)-(b ushr it and 255)) }
            var checked=0
            for (y in 0 until source.height step 3) for (x in 0 until source.width step 3) {
                val mx=(x*192/source.width).coerceIn(3,188);val my=(y*192/source.height).coerceIn(3,188)
                if ((-2..2).any { dy -> (-2..2).any { dx -> mask[(my+dy)*192+mx+dx]<.995f } }) continue
                val original=source.getPixel(x,y);val softened=delta(original,whole.getPixel(x,y))
                if (softened<6) continue
                check(delta(original,portrait.getPixel(x,y))<softened*.35+3) { "Subject detail was blurred with the background" }
                checked++
            }
            check(checked>=8) { "Insufficient confident subject detail in the fixture: $checked" }
            source.recycle();whole.recycle();portrait.recycle()
        }
        test("every available spatial effect changes pixels when its non-neutral controls are set") {
            val kinds=(Effects.categories.filterKeys { it!="Presets" }.values.flatten()+FilmColor.definitions.keys+"Color LUTs")
                .distinct().filter { it !in TimeEffects.kinds && it !in setOf("Strobe","Freeze","Echo","Undercranking") }
            for (kind in kinds) {
                val fx=Effect.create(kind)
                when (kind) {
                    "RGB Offset" -> fx.setNumber("redX",.4f)
                    "Color Correction" -> fx.setNumber("exposure",.8f)
                    "Zoom" -> fx.setNumber("zoom",1.8f)
                    "Rotation" -> fx.setNumber("rotation",25f)
                    "Exposure","Warmth" -> fx.setNumber("value",.7f)
                    "Color LUTs" -> fx.values["lut"]="Warm"
                }
                val source=pattern();val result=source.copy(Bitmap.Config.ARGB_8888,true)
                Effects.correct(result,fx,1f,elapsed=1.2,frame=20)
                FrameEffects.liveGeometry(result,fx,1f)
                val id=Effects.pixelId(fx)
                if (id>0) Native.image(result,id,1f,fx.number("colors",5f),fx.number("smoothing",.5f),false,0,false)
                check(difference(source,result)>32) { "$kind produced no visible change" }
                source.recycle();result.recycle()
            }
        }
        val photo=File(root,"pattern.png")
        pattern().also { bitmap -> photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle() }
        test("frame bars remain horizontal after rotation and annotations stay above the bars") {
            val wide=Effect.create("Widescreen 1953").apply { setNumber("flares",0f) }
            val clip=Clip(path=photo.path,photo=true,audio=false,sourceOut=1.0,effects=mutableListOf(Effect(kind="Rotation",values=mutableMapOf("rotation" to "28")),wide))
            val back=File(root,"red.png")
            Bitmap.createBitmap(160,90,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }.also { b -> back.outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle() }
            clip.track=1
            val project=Project(width=160,height=90,clips=mutableListOf(Clip(path=back.path,photo=true,audio=false,sourceOut=1.0),clip))
            Frames().use { frames ->
                val frame=frames.render(project,.2,160,90)
                check((0 until 160).all { frame.getPixel(it,0)==Color.BLACK })
                check((0 until 160).all { frame.getPixel(it,89)==Color.BLACK });frame.recycle()
                clip.effects.add(Effect(kind="Text",values=mutableMapOf("text" to "FRAME","x" to ".2","y" to ".12","size" to ".1")))
                val titled=frames.render(project,.2,160,90)
                check((0 until 11).sumOf { y -> (25 until 110).count { x -> Color.green(titled.getPixel(x,y))>200 } }>8) { "Frame bars covered the title" }
                titled.recycle()
            }
        }
        test("source-time effects change moving footage and zero intensity restores the original frame") {
            val video=File(root,"moving.mp4")
            i.context.assets.open("fixture.mp4").use { input -> video.outputStream().use { input.copyTo(it) } }
            val clip=ProjectStore.inspect(video).apply { audio=false }
            val project=Project(width=160,height=90,clips=mutableListOf(clip))
            Frames().use { frames ->
                val base=frames.render(project,.61,160,90)
                for (kind in listOf("Freeze","Strobe","Echo","Step Printing","Undercranking")) {
                    val fx=Effect.create(kind);clip.effects.clear();clip.effects.add(fx)
                    val result=frames.render(project,.61,160,90)
                    check(difference(base,result)>8) { "$kind did not change moving footage" };result.recycle()
                    if (kind in setOf("Freeze","Strobe","Echo")) {
                        fx.setNumber("strength",0f)
                        val bypass=frames.render(project,.61,160,90);check(base.sameAs(bypass));bypass.recycle()
                    }
                }
                base.recycle()
            }
        }
        test("legacy geometry zero intensity is a true bypass in editor rendering") {
            val clip=Clip(path=photo.path,photo=true,audio=false,sourceOut=1.0)
            val project=Project(width=128,height=80,clips=mutableListOf(clip))
            Frames().use { frames ->
                val base=frames.render(project,.2,128,80)
                for (kind in listOf("Zoom","Mirror","Rotation")) {
                    clip.effects.clear();clip.effects.add(Effect(kind=kind,values=mutableMapOf("strength" to "0","zoom" to "2","rotation" to "35")))
                    val same=frames.render(project,.2,128,80)
                    check(base.sameAs(same)) { "$kind ignored zero intensity" };same.recycle()
                }
                base.recycle()
            }
        }
        test("frame schemas preserve legacy values typed animation and explicit upgrades through save") {
            val legacy=Effect(kind="Widescreen 1953",values=mutableMapOf("bars" to "White","ratio" to "2.35"))
            val modern=Effect.create("Blur").apply { parameter("radius")!!.setKeyframe(.2,EffectValue.Number(20.0)) }
            val store=ProjectStore(File(root,"store"));val source=photo.copyTo(File(store.media,"pattern.png"))
            val project=Project(clips=mutableListOf(Clip(path=source.path,photo=true,audio=false,sourceOut=1.0,effects=mutableListOf(legacy,modern))))
            store.save(project)
            val restored=store.open(project.id).project.clips.single().effects
            check(restored[0].parameters.isEmpty() && restored[0].values["bars"]=="White")
            check(FrameEffects.modern(restored[1]) && restored[1].parameter("radius")!!.keyframes.size==1)
        }
        test("blur letterbox and reference framing expose working controls in both orientations") {
            for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                main { a.requestedOrientation=orientation };Thread.sleep(400)
                for ((kind,title) in listOf("Blur" to "Blur radius","Letterbox" to "Bar size","Widescreen 1953" to "Flares")) {
                    main {
                        a.editor.project.clips.clear();a.editor.project.transitions.clear();a.editor.clearSelection();a.editor.cursor=0.0
                        a.editor.project.clips.add(Clip(path=photo.path,photo=true,audio=false,sourceOut=1.0));a.editor.applyEffect(Effect.create(kind))
                        a.showEditor();a.panels.videoEffects(a.editor.project.clips.first().effects.single())
                        check(find(a.root,title)!=null) { "Missing $kind control $title" }
                        find(a.root,"Apply")!!.performClick()
                    }
                    Thread.sleep(100)
                }
            }
        }
        test("actual recording previews letterbox and blur and retains both through decoded export") {
            main { a.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;a.showCapture() }
            lateinit var capture: Capture
            main { capture=(0 until a.root.childCount).map { a.root.getChildAt(it) }.filterIsInstance<Capture>().single();capture.setHdr(false) }
            fun waitFor(message: String,predicate: ()->Boolean) {
                val until=android.os.SystemClock.elapsedRealtime()+20000
                while (!predicate() && android.os.SystemClock.elapsedRealtime()<until) Thread.sleep(50)
                check(predicate()) { message }
            }
            val live=Capture::class.java.getDeclaredField("liveEffects").apply { isAccessible=true }
            val generation=Capture::class.java.getDeclaredField("effectGeneration").apply { isAccessible=true }
            val frame=Capture::class.java.getDeclaredField("liveFrame").apply { isAccessible=true }
            waitFor("Camera unavailable") { capture.readyToRecord }
            val box=Effect.create("Letterbox").apply { setNumber("margin",.2f) }
            main { live.set(capture,mutableListOf(box));generation.setInt(capture,generation.getInt(capture)+1) }
            waitFor("Live letterbox is missing") {
                var ready=false
                main { (frame.get(capture) as? Bitmap)?.let { ready=Color.red(it.getPixel(it.width/2,2))<3 } };ready
            }
            var previous: Any?=null
            val blur=Effect.create("Blur").apply { setNumber("radius",40f) }
            main { previous=frame.get(capture);live.set(capture,mutableListOf(blur,box));generation.setInt(capture,generation.getInt(capture)+1) }
            waitFor("Live blur is missing") { var ready=false;main { ready=frame.get(capture)?.let { it!==previous } ?: false };ready }
            val before=a.editor.project.clips.map { it.path }.toSet()
            main { capture.start() };waitFor("Recording did not start") { capture.recording }
            Thread.sleep(1400);main { capture.stop() }
            waitFor("Recording not added") { a.editor.project.clips.any { it.path !in before } }
            val clip=a.editor.project.clips.first { it.path !in before }.duplicate().apply { start=0.0;sourceOut=min(sourceOut,.4);audio=false }
            check(clip.effects.any { it.kind=="Blur" && FrameEffects.modern(it) && it.number("radius")==40f })
            check(clip.effects.any { it.kind=="Letterbox" && FrameEffects.modern(it) && abs(it.number("margin")-.2f)<.00001 })
            main { a.showEditor() }
            val output=File(root,"frame-effects.mp4")
            Export(a.audioCache).run(Project(width=160,height=90,clips=mutableListOf(clip)),output,160,90,ExportColor.SDR) {}
            Frames().use { frames ->
                val result=frames.frame(output.path,.1,160,90)!!
                check(Color.red(result.getPixel(80,3))<8 && Color.red(result.getPixel(80,86))<8);result.recycle()
            }
        }
        root.deleteRecursively()
    }
}
