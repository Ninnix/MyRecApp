package org.myrec.app

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.Half
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import kotlin.math.*

object HalationTests {
    private fun image(f: JSONObject): Bitmap {
        val width = f.getInt("width"); val height = f.getInt("height")
        val linear = f.getBoolean("linear")
        val background = f.getJSONArray("background")
        val pixels = Array(width * height) { FloatArray(3) { background.getDouble(it).toFloat() } }
        val patches = f.getJSONArray("patches")
        for (i in 0 until patches.length()) {
            val p = patches.getJSONArray(i)
            for (y in p.getInt(1) until p.getInt(3)) for (x in p.getInt(0) until p.getInt(2))
                pixels[y * width + x] = FloatArray(3) { p.getDouble(it + 4).toFloat() }
        }
        val bitmap = if (linear) WorkingColor.bitmap(width, height) else Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        if (linear) {
            val bytes = ByteBuffer.allocateDirect(bitmap.byteCount).order(ByteOrder.nativeOrder())
            for (y in 0 until height) for (x in 0 until bitmap.rowBytes / 8) {
                val c = if (x < width) pixels[y * width + x] else floatArrayOf(0f, 0f, 0f)
                for (v in c) bytes.putShort(Half.toHalf(v))
                bytes.putShort(Half.toHalf(1f))
            }
            bytes.flip(); bitmap.copyPixelsFromBuffer(bytes)
        } else bitmap.setPixels(pixels.map { Color.rgb(it[0].toInt(), it[1].toInt(), it[2].toInt()) }.toIntArray(), 0, width, 0, 0, width, height)
        return bitmap
    }

    fun run(i: Instrumentation, a: MainActivity, test: (String, () -> Unit) -> Unit) {
        test("Halation matches 18 source-equation highlight blur and flash fixtures in SDR and half float") {
            val data = i.context.assets.open("halation-reference.json").bufferedReader().use { JSONObject(it.readText()) }
            val fixtures = data.getJSONArray("fixtures")
            for (n in 0 until fixtures.length()) {
                val f = fixtures.getJSONObject(n); val bitmap = image(f)
                val p = f.getJSONArray("parameters")
                val effect = Effect(kind = "Halation")
                for ((index, key) in listOf("glow", "red", "flash").withIndex()) effect.setNumber(key, p.getDouble(index).toFloat())
                effect.parameter("flashColor")!!.value = EffectValue.Choice(listOf("Neutral", "Warm", "Cool")[p.getInt(3)])
                Effects.correct(bitmap, effect, f.getDouble("intensity").toFloat())
                val bytes = if (f.getBoolean("linear")) ByteBuffer.allocateDirect(bitmap.byteCount).order(ByteOrder.nativeOrder()).also { bitmap.copyPixelsToBuffer(it) } else null
                val points = f.getJSONArray("output")
                for (index in 0 until points.length()) {
                    val point = points.getJSONArray(index); val x = point.getInt(0); val y = point.getInt(1)
                    val color = if (bytes == null) bitmap.getPixel(x, y) else 0
                    for (channel in 0..2) {
                        val value = if (bytes != null) Half.toFloat(bytes.getShort(y * bitmap.rowBytes + x * 8 + channel * 2)).toDouble()
                            else ((color ushr (16 - channel * 8)) and 255).toDouble()
                        val expected = point.getDouble(channel + 2)
                        check(abs(value - expected) <= if (bytes == null) 1.0 else .002) {
                            "${f.getString("name")} $x,$y channel $channel: $value != $expected"
                        }
                    }
                }
                bitmap.recycle()
            }
        }
        test("Halation bypass is bit exact including HDR headroom and does not alter older film recipes") {
            for (linear in listOf(false, true)) {
                val bitmap = image(JSONObject().put("width", 9).put("height", 7).put("linear", linear)
                    .put("background", org.json.JSONArray(if (linear) listOf(4.0, 1.5, .1234) else listOf(33, 79, 201)))
                    .put("patches", org.json.JSONArray()))
                val original = bitmap.copy(bitmap.config!!, true)
                Native.halation(bitmap, 0f, floatArrayOf(1f, 1f, 1f, 0f))
                check(bitmap.sameAs(original))
                bitmap.recycle(); original.recycle()
            }
            val effect = Effect(kind = "16mm")
            check(effect.parameters.isEmpty())
            check(Effect(kind = "Noir").parameters.isEmpty())
        }
        test("Halation source keys and algorithm identity survive split persistence and reject newer processing") {
            val clip = a.editor.project.clips.first().duplicate(true).apply { effects.clear() }
            val editor = Editor(Project(clips = mutableListOf(clip)))
            val draft = VideoEffectsDraft(editor, Effects.video.toSet())
            val effect = Effect(kind = "Halation")
            draft.chain.add(effect)
            draft.editNumber(effect, "glow", .9f, .4, true)
            draft.editValue(effect, "flashColor", EffectValue.Choice("Cool"), .4, true)
            draft.commit(editor)
            editor.cursor = .8; editor.split()
            val encoded = a.store.encode(editor.project)
            val decoded = a.store.decode(encoded)
            check(decoded == editor.project)
            check(decoded.clips.all { it.effects.single().parameter("glow")!!.keyframes.size == 2 })
            val loaded = decoded.clips.first().effects.single()
            check(loaded.sampled(clip.sourceTime(.4)).number("glow") > .89f)
            check(loaded.sampled(clip.sourceIn).parameter("flashColor")!!.value == EffectValue.Choice("Warm"))
            check(loaded.sampled(clip.sourceTime(.4)).parameter("flashColor")!!.value == EffectValue.Choice("Cool"))
            val json = encoded
            val fx = json.getJSONArray("clips").getJSONObject(0).getJSONArray("effects").getJSONObject(0)
            check(fx.getInt("algorithm") == 4)
            fx.put("algorithm", 5)
            check(runCatching { a.store.decode(json) }.exceptionOrNull() is NewerProject)
        }
        test("Halation handles preview HD and 4K working sizes with bounded finite output") {
            val report = org.json.JSONArray()
            for ((width, height, linear) in listOf(Triple(640,360,false), Triple(1920,1080,false), Triple(3840,2160,false), Triple(1920,1080,true))) {
                val bitmap = if (linear) WorkingColor.bitmap(width, height) else Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap); val paint = Paint()
                val times = mutableListOf<Double>()
                val samples = mutableListOf<List<Int>>()
                repeat(3) {
                    canvas.drawColor(Color.rgb(25, 37, 48)); paint.color = Color.WHITE
                    canvas.drawRect(width * .3f, height * .3f, width * .35f, height * .4f, paint)
                    canvas.drawRect(width * .65f, height * .5f, width * .68f, height * .53f, paint)
                    val start = SystemClock.elapsedRealtimeNanos()
                    Effects.correct(bitmap, Effect(kind = "Halation"), 1f)
                    times.add((SystemClock.elapsedRealtimeNanos() - start) / 1e6)
                    samples.add(listOf(bitmap.getPixel(0,0), bitmap.getPixel(width/3,height/3), bitmap.getPixel(width/2,height/2)))
                }
                check(samples.distinct().size == 1)
                if (linear) {
                    val bytes = ByteBuffer.allocateDirect(bitmap.byteCount).order(ByteOrder.nativeOrder())
                    bitmap.copyPixelsToBuffer(bytes)
                    for ((x,y) in listOf(0 to 0,width/3 to height/3,width/2 to height/2)) for (channel in 0..2)
                        check(Half.toFloat(bytes.getShort(y*bitmap.rowBytes+x*8+channel*2)) in 0f..1f)
                }
                report.put(JSONObject().put("width",width).put("height",height).put("linear",linear).put("milliseconds",org.json.JSONArray(times)))
                bitmap.recycle()
            }
            File(a.getExternalFilesDir(null), "halation-bench.json").writeText(JSONObject()
                .put("device",android.os.Build.MODEL).put("api",android.os.Build.VERSION.SDK_INT)
                .put("frames",report).toString(2))
        }
    }
}
