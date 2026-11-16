package org.myrec.app

import android.app.Instrumentation
import android.graphics.*
import android.util.Half
import org.json.JSONObject
import android.view.*
import android.widget.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

object GradingParityTests {
    private fun image(w: Int,h: Int,value: (Int,Int)->FloatArray): Bitmap=WorkingColor.bitmap(w,h).apply {
        val bytes=ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
        for (y in 0 until h) for (x in 0 until rowBytes/8) {
            val c=if (x<w) value(x,y) else floatArrayOf(0f,0f,0f,0f)
            c.forEach { bytes.putShort(Half.toHalf(it)) }
        }
        bytes.flip();copyPixelsFromBuffer(bytes)
    }
    private fun pixel(image: Bitmap,x: Int,y: Int): FloatArray {
        val data=ByteBuffer.allocateDirect(image.byteCount).order(ByteOrder.nativeOrder());image.copyPixelsToBuffer(data)
        return FloatArray(4) { Half.toFloat(data.getShort(y*image.rowBytes+x*8+it*2)) }
    }
    private fun code(v: Double)=if (v>=.01) log2(v+.00964052)*.08550479+.69336945 else 47.28711236*max(0.0,v+.05641088).pow(2)
    fun run(i: Instrumentation,a: MainActivity,main: (()->Unit)->Unit,test: (String,()->Unit)->Unit) {
        test("local Log recovery matches independent guided-filter and Log-LUT source equations") {
            val fixtures=i.context.assets.open("hdr-color-reference.json").bufferedReader().use { JSONObject(it.readText()).getJSONArray("fixtures") }
            val matrix=doubleArrayOf(.6274038959,.3292830384,.0433130657,.0690972894,.9195403951,.0113623156,.0163914389,.0880133079,.8955952532)
            for (j in 0 until fixtures.length()) {
                val f=fixtures.getJSONObject(j);val w=f.getInt("width");val h=f.getInt("height");val amount=f.getDouble("amount").toFloat()
                for (domain in 0..1) {
                    val bitmap=image(w,h) { x,y ->
                        val v=.04+12*(x.toDouble()/(w-1)).pow(3);val detail=1+.11*sin(x*1.7)*cos(y*.8)
                        val rgb=if (x==w/3 && y==h/2) doubleArrayOf(18.0,12.0,5.0) else doubleArrayOf(v*detail,v*.85*detail,v*.65*detail)
                        FloatArray(4) { c -> if (c==3) 1f else code((0..2).sumOf { k -> matrix[c*3+k]*rgb[k] }).toFloat() }
                    }
                    Native.colorLut(bitmap,floatArrayOf(0f,0f,0f,1f,1f,1f),2,true,floatArrayOf(0f,0f,0f,1f,1f,1f),domain,domain.toFloat(),0f,0f,1,recovery=amount)
                    val points=f.getJSONArray("points")
                    for (p in 0 until points.length()) {
                        val point=points.getJSONArray(p);val expected=point.getJSONArray(2+domain);val actual=pixel(bitmap,point.getInt(0),point.getInt(1))
                        for (c in 0..2) check(abs(actual[c]-expected.getDouble(c))<.006) { "Recovery $w x $h amount $amount domain $domain point $p: ${actual.toList()} != $expected" }
                        check(actual[3]==1f)
                    }
                    bitmap.recycle()
                }
            }
        }
        test("recovery zero is exact and SDR footage ignores stored Log recovery strength") {
            val original=image(47,19) { x,y -> floatArrayOf(x/15f,y/20f,.3f,1f) }
            val copy=original.copy(Bitmap.Config.RGBA_F16,true)
            Effects.correct(copy,Effect(kind="Color LUTs",values=mutableMapOf("localRecovery" to "1")),0f)
            check(copy.sameAs(original));copy.recycle();original.recycle()
            val raw=image(47,19) { x,y -> floatArrayOf((x+y)/80f,.4f,.5f,1f) }
            val a0=raw.copy(Bitmap.Config.RGBA_F16,true);val b0=raw.copy(Bitmap.Config.RGBA_F16,true)
            for (bitmap in listOf(a0,b0)) Effects.correct(bitmap,Effect(kind="Color LUTs",values=mutableMapOf("source" to "Apple Log","localRecovery" to "0")),0f)
            check(a0.sameAs(b0));listOf(raw,a0,b0).forEach { it.recycle() }
        }
        test("local recovery handles single-pixel and thin images without losing constant-field colour") {
            for ((w,h) in listOf(1 to 1,1 to 37,51 to 1,2 to 2)) {
                val bitmap=image(w,h) { _,_ -> floatArrayOf(.75f,.75f,.75f,1f) }
                Effects.correct(bitmap,Effect(kind="Color LUTs",values=mutableMapOf("source" to "Apple Log","localRecovery" to "1")),0f)
                val sample=pixel(bitmap,0,0)
                check(sample.take(3).all { it.isFinite() && it in 0f..1f })
                for (y in 0 until h) for (x in 0 until w) check(pixel(bitmap,x,y).zip(sample.toList()).all { abs(it.first-it.second)<.001 })
                bitmap.recycle()
            }
        }
        test("O-Log middle grey uses its published curve and stays independent of LUT strength") {
            for (strength in listOf(0f,.5f,1f)) {
                val bitmap=image(8,8) { _,_ -> floatArrayOf(.4901589f,.4901589f,.4901589f,1f) }
                Effects.correct(bitmap,Effect(kind="Color LUTs",values=mutableMapOf("source" to "O-Log")),strength)
                check(pixel(bitmap,3,3).take(3).all { abs(it-.18f)<.002 })
                bitmap.recycle()
            }
        }
        test("new white balance preserves neutral half-float radiometry and legacy recipes stay unchanged") {
            val original=image(79,13) { x,y -> floatArrayOf(x/17f,y/9f,.12f,1f) }
            val neutral=original.copy(Bitmap.Config.RGBA_F16,true)
            Effects.correct(neutral,Effect(kind="Color LUTs",values=mutableMapOf("balanceModel" to "2")),0f)
            check(neutral.sameAs(original))
            val changed=original.copy(Bitmap.Config.RGBA_F16,true)
            Effects.correct(changed,Effect(kind="Color LUTs",values=mutableMapOf("balanceModel" to "2","warmth" to ".6","tint" to "-.3")),0f)
            check(!changed.sameAs(original) && pixel(changed,78,12).take(3).any { it>1 })
            listOf(original,neutral,changed).forEach { it.recycle() }
        }
        test("built-in creative looks extrapolate HDR while imported CUBE domains remain bounded") {
            val old=image(16,8) { x,_ -> val v=2+x/8f;floatArrayOf(v,v,v,1f) }
            val modern=old.copy(Bitmap.Config.RGBA_F16,true)
            Effects.correct(old,Effect(kind="Color LUTs",values=mutableMapOf("lut" to "Warm")),1f)
            Effects.correct(modern,Effect(kind="Color LUTs",values=mutableMapOf("lut" to "Warm","balanceModel" to "2")),1f)
            check(pixel(old,15,4).take(3).max()<=1f && pixel(modern,15,4).take(3).max()>2f)
            check(pixel(modern,15,4)[0]>pixel(modern,0,4)[0])
            old.recycle();modern.recycle()
            for (value in listOf(2f,5f,16f,32f)) {
                val bitmap=image(1,1) { _,_ -> floatArrayOf(value,value,value,1f) }
                Effects.correct(bitmap,Effect(kind="Color LUTs",values=mutableMapOf("lut" to "Warm","balanceModel" to "2")),1f)
                val code=1.055*value.toDouble().pow(1/2.4)-.055
                val expected=doubleArrayOf(1+(code-1)*(1-(31/32.0).pow(.92))*32,code,1+(code-1)*(1-(31/32.0).pow(1.1))*32)
                    .map { ((it+.055)/1.055).pow(2.4) }
                val actual=pixel(bitmap,0,0)
                for (c in 0..2) check(abs(actual[c]-expected[c])<expected[c]*.001) { "HDR LUT extrapolation $value: ${actual.toList()} != $expected" }
                bitmap.recycle()
            }
        }
        test("new recovery and white-balance recipes retain legacy animation through save and split") {
            val clip=Clip(path="color.mov",sourceOut=4.0,audio=false)
            val effect=Effect(kind="Color LUTs",to=4.0,values=mutableMapOf("source" to "O-Log","localRecovery" to ".7","balanceModel" to "2"))
            effect.motion=EffectMotion(GradeModel.pose(effect),GradeModel.pose(effect)+("localRecovery" to 1.0),duration=4.0).anchored(clip,0.0)
            clip.effects.add(effect);val editor=Editor(Project(clips=mutableListOf(clip)));editor.cursor=2.0;editor.split()
            val saved=a.store.decode(a.store.encode(editor.project));check(saved==editor.project)
            check(saved.clips.all { it.effects.single().motion!!.valid(it.effects.single()) && it.effects.single().values["balanceModel"]=="2" })
            val encoded=a.store.encode(saved);encoded.getJSONArray("clips").getJSONObject(0).getJSONArray("effects").getJSONObject(0).getJSONObject("values").put("localRecovery","NaN")
            check(runCatching { a.store.decode(encoded) }.isFailure)
        }
        val source=File(a.store.media,"grading.mp4")
        i.context.assets.open("hdr-ramp.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
        val before=Project(width=1280,height=720,clips=mutableListOf(ProjectStore.inspect(source).apply { sourceOut=.4;audio=false }))
        fun find(view: View,label: String): View? {
            if (view.contentDescription?.toString()==label || view is TextView && view.text.toString()==label) return view
            if (view is ViewGroup) for (j in 0 until view.childCount) find(view.getChildAt(j),label)?.let { return it }
            return null
        }
        fun click(label: String)=main { check(find(a.root,label)?.performClick()==true) { "No $label" } }
        test("Log recovery controls preview and cancel as a draft, and Apply is one undo") {
            main { a.panels.close();a.editor.load(before.duplicate());a.panels.effect("Color LUTs") }
            click("Source interpretation · Recorded")
            main { check(find(a.choices!!.contentView,"O-Log")?.performClick()==true) }
            click("Natural recovery");Thread.sleep(250)
            check(a.editor.project==before)
            val draft=a.playback.previewProject!!.clips.first().effects.single { it.kind=="Color LUTs" }
            check(draft.values["source"]=="O-Log" && draft.number("localRecovery")==1f)
            click("Cancel");check(a.editor.project==before && !a.editor.canUndo)
            main { a.panels.effect("Color LUTs") }
            click("Source interpretation · Recorded")
            main { check(find(a.choices!!.contentView,"O-Log")?.performClick()==true) }
            click("Natural recovery");click("Apply")
            check(a.editor.project.clips.first().effects.single().number("localRecovery")==1f)
            check(a.store.current()==a.editor.project)
            main { a.editor.undo() };check(a.editor.project==before && !a.editor.canUndo)
        }
        test("graded HLG export matches the half-float working preview and SDR view") {
            val project=before.duplicate()
            project.clips.first().effects.add(Effect(kind="Color LUTs",to=.4,values=mutableMapOf("lut" to "Warm","strength" to ".6","balanceModel" to "2","warmth" to ".2","tint" to "-.1","highlights" to "-.3")))
            val folder=File(a.getExternalFilesDir(null),"hdr-color-checks").apply { mkdirs() }
            val hdr=File(folder,"graded-hlg.mp4");val sdr=File(folder,"graded-sdr.mp4")
            Export(a.audioCache).run(project,hdr,1280,720,ExportColor.HLG) {}
            Export(a.audioCache).run(project,sdr,1280,720,ExportColor.SDR) {}
            Frames().use { frames ->
                val working=frames.render(project,.1,1280,720,keepLinear=true)
                val display=frames.render(project,.1,1280,720)
                VideoDecoder(hdr.path,linear=true,tenBit=true).use { decoder ->
                    val encoded=decoder.frame(.1,1280,720)!!
                    for (x in 32 until 1248 step 64) {
                        val expected=pixel(working,x,360);val actual=pixel(encoded,x,360)
                        for (c in 0..2) check(abs(actual[c]-expected[c])<.07) { "Graded HLG $x channel $c: ${actual[c]} != ${expected[c]}" }
                    }
                    encoded.recycle()
                }
                VideoDecoder(sdr.path).use { decoder ->
                    val encoded=decoder.frame(.1,1280,720)!!
                    for (x in 32 until 1248 step 64) {
                        val expected=display.getPixel(x,360);val actual=encoded.getPixel(x,360)
                        for (channel in listOf(Color::red,Color::green,Color::blue)) check(abs(channel(expected)-channel(actual))<=5) { "Graded SDR $x: $actual != $expected" }
                    }
                    encoded.recycle()
                }
                working.recycle();display.recycle()
            }
        }
    }
}
