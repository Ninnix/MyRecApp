package org.myrec.app

import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.Bundle
import java.io.File
import org.json.JSONObject

object BackgroundPerformanceTests {
    fun run(i: Instrumentation,a: MainActivity,main: (()->Unit)->Unit,test: (String,()->Unit)->Unit) {
        check(a.captureSettings!==a.getSharedPreferences("capture",android.content.Context.MODE_PRIVATE)) { "Performance tests need an isolated session" }
        val normal=a.getSharedPreferences("capture",android.content.Context.MODE_PRIVATE)
        val settings=normal.all.toMap()
        test("covered camera preview keeps reading fresh input frames") {
            val thread=android.os.HandlerThread("background-input-test").apply { start() }
            val reader=android.media.ImageReader.newInstance(96,64,android.graphics.PixelFormat.RGBA_8888,3)
            reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() },android.os.Handler(thread.looper))
            val failure=java.util.concurrent.atomic.AtomicReference<Throwable>()
            val renderer=CameraComposition(reader.surface,96,64) { failure.set(it) }
            try {
                val input=renderer.input(96,64,0,false);renderer.configure(4,false,0,96,64);renderer.previewCovered=true
                for (color in listOf(android.graphics.Color.RED,android.graphics.Color.GREEN,android.graphics.Color.BLUE)) {
                    input.lockCanvas(null).let { canvas -> canvas.drawColor(color);input.unlockCanvasAndPost(canvas) }
                    val deadline=android.os.SystemClock.elapsedRealtime()+2000;var matched=false
                    while (!matched && android.os.SystemClock.elapsedRealtime()<deadline) {
                        failure.get()?.let { throw it }
                        renderer.previewFrame(96,64)?.let { bitmap ->
                            try { matched=bitmap.getPixel(48,32)==color } finally { bitmap.recycle() }
                        }
                        if (!matched) Thread.sleep(10)
                    }
                    check(matched) { "Covered preview returned an old camera frame" }
                }
            } finally { renderer.close();reader.close();thread.quitSafely() }
        }
        test("direct camera readback retains exact RGBA pixels and every quarter-turn") {
            val bytes=java.nio.ByteBuffer.allocateDirect(4*3*2)
            val pixels=IntArray(6)
            for (p in pixels.indices) {
                val r=p*29;val g=p*17;val b=p*43
                bytes.put(r.toByte()).put(g.toByte()).put(b.toByte()).put(255.toByte())
                pixels[(1-p/3)*3+p%3]=android.graphics.Color.rgb(r,g,b)
            }
            val reference=Bitmap.createBitmap(pixels,3,2,Bitmap.Config.ARGB_8888)
            try { for (angle in listOf(0,90,180,270)) {
                val expected=Bitmap.createBitmap(reference,0,0,3,2,android.graphics.Matrix().apply { postRotate(angle.toFloat()) },false)
                val actual=CameraReadback.bitmap(bytes,3,2,angle)
                try { check(expected.sameAs(actual)) }
                finally { if (expected!==reference) expected.recycle();actual.recycle() }
            } } finally { reference.recycle() }
        }
        test("native background filtering matches the scalar algorithm across motion cuts and mixed sizes") {
            val random=kotlin.random.Random(123)
            for (key in listOf(false,true)) for (motion in listOf(false,true)) for (cut in listOf(false,true)) {
                val history=(0 until (if (key) 6 else 8)).map { index ->
                    val w=if (index%2==0) 63 else 52;val h=if (index%2==0) 39 else 31
                    val guide=FloatArray(w*h) { if (cut && index>2) .9f else .3f+random.nextFloat()*.05f }
                    val scalar=BackgroundTemporal.guide(guide,w,h);val native=Native.backgroundGuide(guide,w,h)
                    check(scalar.indices.all { kotlin.math.abs(scalar[it]-native[it])<.000001f })
                    BackgroundMatte(w,h,FloatArray(w*h+3) { random.nextFloat() },scalar,index/30.0,(w+3)/4,(h+3)/4)
                }
                val scalar=BackgroundTemporal.apply(history,key,motion);val native=BackgroundTemporal.fast(history,key,motion)
                check(scalar.indices.all { kotlin.math.abs(scalar[it]-native[it])<.00008f }) { "Temporal equations changed" }
            }
        }
        test("Auto Background stage timings on a warm portrait and full temporal window") {
            val source=File(a.cacheDir,"background-performance.png")
            i.context.assets.open("person.png").use { input -> source.outputStream().use { input.copyTo(it) } }
            val report=JSONObject()
            try {
                val input=PhotoSource.frame(source.path,512,288)
                val fx=Effect.create("Auto Background")
                val options=BackgroundEffect.options(fx);val times=linkedMapOf<String,MutableList<Double>>()
                fun <T> measure(name: String,block: ()->T): T {
                    val start=System.nanoTime();val result=block()
                    times.getOrPut(name) { mutableListOf() }.add((System.nanoTime()-start)/1e6);return result
                }
                val history=mutableListOf<BackgroundMatte>()
                BackgroundRenderer().use { renderer ->
                    repeat(14) { frame ->
                        val mask=measure("person") { Person.mask(input) }
                        val data=measure("spatial") { Native.backgroundMatte(input,mask,null,options) }
                        val n=input.width*input.height
                        val guide=measure("guide") { Native.backgroundGuide(data.copyOfRange(n+3,data.size),input.width,input.height) }
                        val raw=BackgroundMatte(input.width,input.height,data.copyOfRange(0,n+3),guide,frame/30.0,(input.width+3)/4,(input.height+3)/4)
                        history.add(0,raw);if (history.size>8) history.removeLast()
                        val alpha=measure("temporal") { BackgroundTemporal.fast(history,false,true) }
                        val output=input.copy(Bitmap.Config.ARGB_8888,true)
                        val bg=measure("background") { renderer.background(input,fx,frame/30.0) }
                        try { measure("composite") { Native.backgroundComposite(output,bg,null,alpha,input.width,input.height,options) } }
                        finally { output.recycle();bg.recycle() }
                    }
                }
                input.recycle()
                for ((name,samples) in times) {
                    val warm=samples.takeLast(6).sorted()
                    report.put(name,JSONObject().put("median_ms",warm[warm.size/2]).put("max_ms",warm.last()))
                }
                val json=report.toString(2)
                File(a.getExternalFilesDir(null),"background-performance.json").writeText(json)
                i.sendStatus(0,Bundle().apply { putString("stream",json+"\n") })
            } finally { source.delete() }
        }
        test("live Auto Background measures preview and accepted recording frame rates without a backlog") {
            lateinit var capture: Capture
            main { a.showCapture();capture=(0 until a.root.childCount).map { a.root.getChildAt(it) }.filterIsInstance<Capture>().single();capture.setHdr(false) }
            fun waitFor(message: String,condition: ()->Boolean) {
                val end=android.os.SystemClock.elapsedRealtime()+15000
                while (!condition() && android.os.SystemClock.elapsedRealtime()<end) Thread.sleep(50)
                check(condition()) { message }
            }
            try {
                waitFor("Camera not ready") { capture.readyToRecord }
                main {
                    Capture::class.java.getDeclaredField("liveEffects").apply { isAccessible=true }.set(capture,mutableListOf(Effect.create("Auto Background")))
                    Capture::class.java.getDeclaredField("effectGeneration").apply { isAccessible=true }.let { it.setInt(capture,it.getInt(capture)+1) }
                }
                val field=Capture::class.java.getDeclaredField("liveFrame").apply { isAccessible=true }
                val renderTimes=java.util.Collections.synchronizedList(mutableListOf<Double>())
                val readTimes=java.util.Collections.synchronizedList(mutableListOf<Double>())
                main {
                    val renderer=Capture::class.java.getDeclaredField("backgroundEffects").apply { isAccessible=true }.get(capture) as BackgroundRenderer
                    renderer.frameTime={ renderTimes.add(it) };capture.cameraReadTime={ readTimes.add(it) }
                }
                Thread.sleep(2000)
                fun fps(): Double {
                    var previous: Any?=null;var count=0
                    val start=android.os.SystemClock.elapsedRealtime()
                    while (android.os.SystemClock.elapsedRealtime()-start<3000) {
                        main { field.get(capture)?.let { if (it!==previous) { previous=it;count++ } } };Thread.sleep(8)
                    }
                    check(count>2) { "The background preview stopped" }
                    return (count-1)*1000.0/(android.os.SystemClock.elapsedRealtime()-start)
                }
                val preview=fps()
                val previewRender=renderTimes.toList().sorted().let { it[it.size/2] }
                val read=readTimes.toList().sorted().let { it[it.size/2] }
                waitFor("Recording not ready") { main { if (!capture.recording) capture.start() };capture.recording }
                renderTimes.clear()
                main {
                    val recorder=Capture::class.java.getDeclaredField("recordedBackground").apply { isAccessible=true }.get(capture)
                    val renderer=RecordedBackground::class.java.getDeclaredField("renderer").apply { isAccessible=true }.get(recorder) as BackgroundRenderer
                    renderer.frameTime={ renderTimes.add(it) }
                }
                Thread.sleep(1000);val recording=fps()
                main { capture.stop() }
                val file=File(a.getExternalFilesDir(null),"background-performance.json")
                val report=JSONObject(file.readText()).put("preview_fps",preview).put("recording_fps",recording)
                    .put("preview_render_ms",previewRender).put("camera_read_ms",read)
                    .put("recording_render_ms",renderTimes.toList().sorted().let { it[it.size/2] })
                file.writeText(report.toString(2));i.sendStatus(0,Bundle().apply { putString("stream","Live preview: $preview FPS; recording preview: $recording FPS\n") })
                i.sendStatus(0,Bundle().apply { putString("stream",report.toString(2)+"\n") })
            } finally { main { if (capture.recording) capture.stop();a.showEditor() } }
        }
        check(normal.all==settings) { "Performance tests changed normal recording settings" }
    }
}
