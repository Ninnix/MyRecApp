package org.myrec.app

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Half
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

object SixteenMmTests {
    fun run(i: Instrumentation, a: MainActivity, test: (String, () -> Unit) -> Unit) {
        test("16mm matches 24 independent blur weave grain and rounded-frame fixtures") {
            val data = i.context.assets.open("sixteen-mm-reference.json").bufferedReader().use { JSONObject(it.readText()) }
            val fixtures = data.getJSONArray("fixtures"); val colors = data.getJSONArray("colors")
            for (index in 0 until fixtures.length()) {
                val f = fixtures.getJSONObject(index); val width = f.getInt("width"); val height = f.getInt("height")
                val extended = f.getBoolean("linear")
                val bitmap = if (extended) WorkingColor.bitmap(width,height) else Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                val bytes = if (extended) ByteBuffer.allocateDirect(bitmap.byteCount).order(ByteOrder.nativeOrder()) else null
                val sdr = if (extended) null else IntArray(width*height)
                for (y in 0 until height) for (x in 0 until width) {
                    val color = colors.getJSONArray((x/7+y/5)%colors.length())
                    if (bytes != null) {
                        for (k in 0..2) bytes.putShort(y*bitmap.rowBytes+x*8+k*2,Half.toHalf(color.getInt(k)/255f*(if ((x/11+y/13)%3 == 0) 4 else 1)))
                        bytes.putShort(y*bitmap.rowBytes+x*8+6,Half.toHalf(1f))
                    } else sdr!![y*width+x] = Color.rgb(color.getInt(0),color.getInt(1),color.getInt(2))
                }
                if (bytes != null) bitmap.copyPixelsFromBuffer(bytes) else bitmap.setPixels(sdr!!,0,width,0,0,width,height)
                val fx = Effect.create("16mm"); val parameters = f.getJSONArray("parameters")
                for ((j,control) in SixteenMm.definition.controls.withIndex()) fx.parameter(control.id)!!.value = when (val kind = control.kind) {
                    is ParameterKind.Slider -> EffectValue.Number(parameters.getDouble(j))
                    is ParameterKind.Picker -> EffectValue.Choice(kind.values[parameters.getInt(j)])
                    ParameterKind.Toggle -> EffectValue.Toggle(parameters.getInt(j) == 1)
                    else -> error("Invalid 16mm fixture control")
                }
                Effects.correct(bitmap,fx,f.getDouble("intensity").toFloat(),frame = f.getLong("frame"))
                if (bytes != null) { bytes.clear(); bitmap.copyPixelsToBuffer(bytes) }
                val points = f.getJSONArray("output")
                for (j in 0 until points.length()) {
                    val point = points.getJSONArray(j); val x = point.getInt(0); val y = point.getInt(1); val expected = point.getJSONArray(2)
                    val pixel = if (bytes == null) bitmap.getPixel(x,y) else 0
                    for (k in 0..2) {
                        val value = if (bytes != null) Half.toFloat(bytes.getShort(y*bitmap.rowBytes+x*8+k*2)).toDouble()
                            else ((pixel ushr (16-k*8)) and 255).toDouble()
                        check(abs(value-expected.getDouble(k)) <= if (extended) .003 else 1.0) {
                            "16mm fixture $index at $x,$y channel $k: $value != ${expected.getDouble(k)}"
                        }
                    }
                }
                bitmap.recycle()
            }
        }
        test("16mm zero bypass preserves headroom and original grain-only projects retain their kernel") {
            val bitmap = WorkingColor.bitmap(17,13)
            val bytes = ByteBuffer.allocateDirect(bitmap.byteCount).order(ByteOrder.nativeOrder())
            for (y in 0 until 13) for (x in 0 until 17) for ((k,value) in listOf(4f,1.5f,.1234f,1f).withIndex())
                bytes.putShort(y*bitmap.rowBytes+x*8+k*2,Half.toHalf(value))
            bitmap.copyPixelsFromBuffer(bytes)
            val original = bitmap.copy(bitmap.config!!,true)
            Native.sixteenMm(bitmap,0f,FilmColor.uniforms(Effect.create("16mm")),42)
            check(bitmap.sameAs(original)); bitmap.recycle(); original.recycle()
            val old = Effect(kind = "16mm",values = mutableMapOf("strength" to ".6"))
            val clip = a.editor.project.clips.first().duplicate(true).apply { effects.clear(); effects.add(old) }
            val loaded = a.store.decode(a.store.encode(Project(clips = mutableListOf(clip)))).clips.single().effects.single()
            check(loaded == old && Effects.pixelId(loaded) == 13 && !SixteenMm.modern(loaded))
        }
        test("16mm and Noir typed controls survive copy history and portable archives") {
            val media = File(a.store.media,"film-texture-fixture.mp4")
            i.context.assets.open("fixture.mp4").use { input -> media.outputStream().use { input.copyTo(it) } }
            val project = Project(clips = mutableListOf(Clip(path = media.path,sourceOut = 2.0,
                effects = mutableListOf(Effect.create("Noir"),Effect.create("16mm")))))
            a.store.save(project,false)
            val editor = a.store.editor(project)
            val work = VideoEffectsDraft(editor,Effects.video.toSet()); val fx = work.chain.single { it.kind == "16mm" }
            work.editNumber(fx,"weave",.8f,.5,true)
            work.editValue(fx,"color",EffectValue.Choice("B&W"),.5,true)
            work.commit(editor); a.store.save(editor.session(),false)
            val archive = File(a.cacheDir,"film-texture-${uid()}.zip")
            try {
                val copy = a.store.duplicate(editor.project); val copied = a.store.open(copy.id)
                check(copied.project.clips.single().effects.map { it.parameters } == editor.project.clips.single().effects.map { it.parameters })
                ProjectArchive.export(a.store,editor.project,archive)
                val restored = a.store.open(ProjectArchive.restore(a.store,archive).project.id)
                check(restored.undo.isNotEmpty())
                val effects = restored.project.clips.single().effects
                check(effects.all { it.parameters.isNotEmpty() && Effects.pixelId(it) == 0 })
                check(effects.single { it.kind == "16mm" }.sampled(.5).parameter("color")!!.value == EffectValue.Choice("B&W"))
                check(effects.map { it.parameters } == editor.project.clips.single().effects.map { it.parameters })
            } finally { archive.delete() }
        }
        test("16mm presentation grain and weave are deterministic across frozen footage cuts and seeks") {
            val media = File(a.store.media,"film-texture-fixture.mp4")
            val clip = Clip(path = media.path,sourceIn = .5,sourceOut = .5+1.0/30,
                frozen = FrozenFrame(.5,2.0),effects = mutableListOf(Effect.create("16mm")))
            val editor = Editor(Project(width = 640,height = 360,clips = mutableListOf(clip)))
            Frames().use { frames ->
                val first = frames.render(editor.project,.5,640,360)
                val later = frames.render(editor.project,.6,640,360)
                val repeated = frames.render(editor.project,.5,640,360)
                check(!first.sameAs(later) && first.sameAs(repeated))
                editor.cursor = .4; editor.split()
                val split = frames.render(editor.project,.5,640,360)
                check(first.sameAs(split)); listOf(first,later,repeated,split).forEach { it.recycle() }
            }
        }
        test("film texture working sizes stay finite and repeatable with timing evidence") {
            val report = JSONArray()
            for (kind in listOf("Noir","16mm")) for ((width,height) in listOf(640 to 360,1920 to 1080,3840 to 2160)) {
                val bitmap = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                val fx = Effect.create(kind); val times = JSONArray(); val samples = mutableListOf<List<Int>>()
                repeat(3) {
                    bitmap.eraseColor(Color.rgb(79,117,161))
                    val start = SystemClock.elapsedRealtimeNanos()
                    Effects.correct(bitmap,fx,1f,frame = 1234)
                    times.put((SystemClock.elapsedRealtimeNanos()-start)/1e6)
                    samples.add(listOf(bitmap.getPixel(0,0),bitmap.getPixel(width/2,height/2),bitmap.getPixel(width-1,height-1)))
                }
                check(samples.distinct().size == 1); bitmap.recycle()
                report.put(JSONObject().put("kind",kind).put("width",width).put("height",height).put("milliseconds",times))
            }
            File(a.getExternalFilesDir(null),"film-texture-bench.json").writeText(JSONObject().put("device",android.os.Build.MODEL).put("frames",report).toString(2))
        }
    }
}
