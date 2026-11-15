package org.myrec.app

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Half
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import kotlin.math.abs

object NoirTests {
    fun run(i: Instrumentation, a: MainActivity, test: (String, () -> Unit) -> Unit) {
        test("Noir matches 24 independent contrast crush tint and presentation-grain fixtures") {
            val fixtures = i.context.assets.open("noir-reference.json").bufferedReader().use { JSONObject(it.readText()).getJSONArray("fixtures") }
            for (index in 0 until fixtures.length()) {
                val f = fixtures.getJSONObject(index); val width = f.getInt("width"); val height = f.getInt("height")
                val extended = f.getBoolean("linear"); val points = f.getJSONArray("points")
                val bitmap = if (extended) WorkingColor.bitmap(width,height) else Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                val bytes = if (extended) ByteBuffer.allocateDirect(bitmap.byteCount).order(ByteOrder.nativeOrder()) else null
                if (bytes != null) for (y in 0 until height) for (x in 0 until width) bytes.putShort(y*bitmap.rowBytes+x*8+6,Half.toHalf(1f))
                else bitmap.eraseColor(Color.BLACK)
                for (j in 0 until points.length()) {
                    val point = points.getJSONArray(j); val x = point.getInt(0); val y = point.getInt(1); val c = point.getJSONArray(2)
                    if (bytes != null) for (k in 0..2) bytes.putShort(y*bitmap.rowBytes+x*8+k*2,Half.toHalf(c.getDouble(k).toFloat()))
                    else bitmap.setPixel(x,y,Color.rgb(c.getInt(0),c.getInt(1),c.getInt(2)))
                }
                if (bytes != null) bitmap.copyPixelsFromBuffer(bytes)
                val parameters = f.getJSONArray("parameters")
                val fx = Effect.create("Noir").apply {
                    setNumber("contrast",parameters.getDouble(0).toFloat()); setNumber("crush",parameters.getDouble(1).toFloat())
                    parameter("grain")!!.value = EffectValue.Toggle(parameters.getInt(2) == 1)
                    parameter("shadowColor")!!.value = EffectValue.Toggle(parameters.getInt(3) == 1)
                }
                Effects.correct(bitmap,fx,f.getDouble("intensity").toFloat(),frame = f.getLong("frame"))
                if (bytes != null) { bytes.clear(); bitmap.copyPixelsToBuffer(bytes) }
                for (j in 0 until points.length()) {
                    val point = points.getJSONArray(j); val x = point.getInt(0); val y = point.getInt(1)
                    val expected = point.getJSONArray(3); val color = if (bytes == null) bitmap.getPixel(x,y) else 0
                    for (k in 0..2) {
                        val value = if (bytes != null) Half.toFloat(bytes.getShort(y*bitmap.rowBytes+x*8+k*2)).toDouble()
                            else ((color ushr (16-k*8)) and 255).toDouble()
                        check(abs(value-expected.getDouble(k)) <= if (extended) .002 else 1.0) {
                            "Noir fixture $index at $x,$y channel $k: $value != ${expected.getDouble(k)}"
                        }
                    }
                }
                bitmap.recycle()
            }
        }
        test("Noir bypass preserves SDR and extended bits and old recipes keep their original kernel") {
            for (extended in listOf(false,true)) {
                val bitmap = if (extended) WorkingColor.bitmap(17,13) else Bitmap.createBitmap(17,13,Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.rgb(33,79,201))
                if (extended) {
                    val bytes = ByteBuffer.allocateDirect(bitmap.byteCount).order(ByteOrder.nativeOrder())
                    bitmap.copyPixelsToBuffer(bytes); bytes.putShort(0,Half.toHalf(4f)); bytes.rewind(); bitmap.copyPixelsFromBuffer(bytes)
                }
                val original = bitmap.copy(bitmap.config!!,true)
                Native.noir(bitmap,0f,Noir.uniforms(Effect.create("Noir")),0)
                check(bitmap.sameAs(original)); bitmap.recycle(); original.recycle()
            }
            val legacy = Effect(kind = "Noir",values = mutableMapOf("strength" to ".7"))
            val clip = a.editor.project.clips.first().duplicate(true).apply { effects.clear(); effects.add(legacy) }
            val old = Project(clips = mutableListOf(clip)); val saved = a.store.encode(old)
            val loaded = a.store.decode(saved).clips.single().effects.single()
            check(loaded == legacy && !Noir.modern(loaded) && Effects.pixelId(loaded) == 1)
            val bitmap = Bitmap.createBitmap(17,13,Bitmap.Config.ARGB_8888); bitmap.eraseColor(Color.rgb(33,79,201))
            val expected = bitmap.copy(bitmap.config!!,true)
            Effects.correct(bitmap,loaded,.7f); Native.image(bitmap,Effects.pixelId(loaded),.7f,0f)
            Native.image(expected,1,.7f,0f)
            check(bitmap.sameAs(expected)); bitmap.recycle(); expected.recycle()
        }
        test("new Noir controls and keys survive cuts reopen and reject an unknown algorithm") {
            val clip = a.editor.project.clips.first().duplicate(true).apply { effects.clear() }
            val editor = Editor(Project(clips = mutableListOf(clip)))
            val work = VideoEffectsDraft(editor,Effects.video.toSet()); val fx = Effect.create("Noir"); work.chain.add(fx)
            work.editNumber(fx,"crush",.27f,.5,true)
            work.editValue(fx,"shadowColor",EffectValue.Toggle(true),.5,true)
            work.commit(editor); editor.cursor = .8; editor.split()
            val encoded = a.store.encode(editor.project); val reopened = a.store.decode(encoded)
            check(reopened == editor.project && reopened.clips.size == 2)
            check(reopened.clips.all { Noir.modern(it.effects.single()) && it.effects.single().parameter("crush")!!.keyframes.size == 2 })
            val effect = reopened.clips.first().effects.single()
            check(effect.sampled(clip.sourceTime(.5)).parameter("shadowColor")!!.value == EffectValue.Toggle(true))
            check(effect.sampled(clip.sourceIn).parameter("shadowColor")!!.value == EffectValue.Toggle(false))
            val json = encoded.getJSONArray("clips").getJSONObject(0).getJSONArray("effects").getJSONObject(0)
            check(json.getInt("algorithm") == 4); json.put("algorithm",5)
            check(runCatching { a.store.decode(encoded) }.exceptionOrNull() is NewerProject)
        }
        test("Noir grain follows assembled frame time through freezes cuts and random seeks") {
            val media = File(a.store.media,"noir-fixture.mp4")
            i.context.assets.open("fixture.mp4").use { input -> media.outputStream().use { input.copyTo(it) } }
            val clip = Clip(path = media.path,sourceIn = .5,sourceOut = .5+1.0/30,frozen = FrozenFrame(.5,2.0,0.0),
                effects = mutableListOf(Effect.create("Noir")))
            val project = Project(width = 640,height = 360,fps = 30,clips = mutableListOf(clip))
            Frames().use { frames ->
                val first = frames.render(project,.5,640,360)
                val later = frames.render(project,.6,640,360)
                val repeat = frames.render(project,.5,640,360)
                check(!first.sameAs(later) && first.sameAs(repeat))
                clip.effects.single().parameter("grain")!!.value = EffectValue.Toggle(false)
                val still = frames.render(project,.5,640,360); val held = frames.render(project,.6,640,360)
                check(still.sameAs(held))
                clip.effects.single().parameter("grain")!!.value = EffectValue.Toggle(true)
                val editor = Editor(project); editor.cursor = .4; editor.split()
                val split = frames.render(editor.project,.5,640,360)
                check(first.sameAs(split))
                listOf(first,later,repeat,still,held,split).forEach { it.recycle() }
            }
            media.delete()
        }
    }
}
