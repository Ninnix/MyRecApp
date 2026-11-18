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
        test("camera waits for its preview surface when color mode is set before layout") {
            main {
                val capture=Capture(a,a.store,{}, {})
                try {
                    capture.setHdr(false)
                    check(!Capture::class.java.getDeclaredField("opening").apply { isAccessible=true }.getBoolean(capture))
                    check(Capture::class.java.getDeclaredField("device").apply { isAccessible=true }.get(capture)==null)
                } finally { capture.destroy() }
            }
        }
        test("covered camera preview keeps reading fresh input frames") {
            val thread=android.os.HandlerThread("background-input-test").apply { start() }
            val reader=android.media.ImageReader.newInstance(96,64,android.graphics.PixelFormat.RGBA_8888,3)
            reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() },android.os.Handler(thread.looper))
            val failure=java.util.concurrent.atomic.AtomicReference<Throwable>()
            val renderer=CameraComposition(reader.surface,96,64) { failure.set(it) }
            try {
                val input=renderer.input(96,64,0,false);renderer.configure(4,false,0,96,64);renderer.previewCovered=true
                for ((index,color) in listOf(android.graphics.Color.RED,android.graphics.Color.GREEN,android.graphics.Color.BLUE).withIndex()) {
                    val w=if (index==1) 64 else 96;val h=if (index==1) 96 else 64
                    input.lockCanvas(null).let { canvas -> canvas.drawColor(color);input.unlockCanvasAndPost(canvas) }
                    val deadline=android.os.SystemClock.elapsedRealtime()+2000;var matched=false
                    while (!matched && android.os.SystemClock.elapsedRealtime()<deadline) {
                        failure.get()?.let { throw it }
                        renderer.previewFrame(w,h)?.let { bitmap ->
                            try { matched=bitmap.getPixel(w/2,h/2)==color } finally { bitmap.recycle() }
                        }
                        if (!matched) Thread.sleep(10)
                    }
                    check(matched) { "Covered preview returned an old camera frame" }
                }
            } finally { renderer.close();reader.close();thread.quitSafely() }
        }
        test("prefetched masks and backgrounds retain exact moving-frame pixels and media clocks") {
            val source=File(a.cacheDir,"background-prefetch.png")
            i.context.assets.open("person.png").use { input -> source.outputStream().use { input.copyTo(it) } }
            val movie=File(a.cacheDir,"background-prefetch.mp4")
            i.context.assets.open("person.mp4").use { input -> movie.outputStream().use { input.copyTo(it) } }
            val photo=PhotoSource.frame(source.path,352,199)
            try {
                BackgroundRenderer().use { normal -> BackgroundRenderer().use { prefetched -> BackgroundRenderer().use { backgrounds ->
                for (mode in listOf("Blur original","Solid","Studio","Window","Image","Video")) {
                    val fx=Effect.create("Auto Background").apply {
                        parameter("background")!!.value=EffectValue.Choice(mode)
                        parameter("media")!!.value=EffectValue.Media(if (mode=="Video") movie.path else source.path)
                    }
                    repeat(4) { index ->
                        val original=Bitmap.createBitmap(photo.width,photo.height,Bitmap.Config.ARGB_8888)
                        android.graphics.Canvas(original).apply { drawColor(android.graphics.Color.BLACK);drawBitmap(photo,index*3f,0f,null) }
                        val mask=Person.mask(original)
                        val expected=original.copy(Bitmap.Config.ARGB_8888,true)
                        val background=backgrounds.background(original,fx,index/30.0)
                        try {
                            normal.render(expected,fx,index/30.0,0.0,"prefetch")
                            prefetched.render(original,fx,index/30.0,0.0,"prefetch",personMask=mask,backgroundFrame=background)
                            check(expected.sameAs(original)) { "Prefetch changed $mode frame $index" }
                        } finally { expected.recycle();original.recycle();background.recycle() }
                    }
                }
                } } }
            } finally { photo.recycle();source.delete();movie.delete() }
        }
        test("GPU person masks match the original FP32 model or fall back cleanly") {
            val source=File(a.cacheDir,"person-gpu-test.png")
            i.context.assets.open("person.png").use { input -> source.outputStream().use { input.copyTo(it) } }
            val bitmap=PhotoSource.frame(source.path,352,199)
            PersonGpu(a).use { gpu ->
                try {
                    val expected=Person.mask(bitmap)
                    var actual=gpu.mask(bitmap)
                    val deadline=System.nanoTime()+4_000_000_000L
                    while (actual==null && System.nanoTime()<deadline) { Thread.sleep(30);actual=gpu.mask(bitmap) }
                    i.sendStatus(0,Bundle().apply { putString("stream","GPU person backend available: ${actual!=null}\n") })
                    val mask=actual
                    if (mask!=null) check(expected.indices.all { kotlin.math.abs(expected[it]-mask[it])<.0001f }) { "GPU changed the person model" }
                    else check(!gpu.active)
                } finally { bitmap.recycle();source.delete() }
            }
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
            for (key in listOf(false,true)) for (motion in listOf(false,true)) for (cut in listOf(false,true)) for (mixed in listOf(false,true)) {
                val history=(0 until (if (key) 6 else 8)).map { index ->
                    val w=if (!mixed) 64 else if (index%2==0) 63 else 52;val h=if (!mixed) 40 else if (index%2==0) 39 else 31
                    val guide=FloatArray(w*h) { if (cut && index>2) .9f else .3f+random.nextFloat()*.05f }
                    val scalar=BackgroundTemporal.guide(guide,w,h);val native=Native.backgroundGuide(guide,w,h)
                    check(scalar.indices.all { kotlin.math.abs(scalar[it]-native[it])<.000001f })
                    BackgroundMatte(w,h,FloatArray(w*h+3) { random.nextFloat() },scalar,index/30.0,(w+3)/4,(h+3)/4)
                }
                val scalar=BackgroundTemporal.apply(history,key,motion);val native=BackgroundTemporal.fast(history,key,motion)
                check(scalar.indices.all { kotlin.math.abs(scalar[it]-native[it])<.00008f }) { "Temporal equations changed" }
            }
        }
        test("compact matte guides retain alpha and linear-light area averages") {
            val random=kotlin.random.Random(987)
            for (w in listOf(63,64)) for (hdr in listOf(false,true)) {
                val h=39
                val bitmap=if (hdr) WorkingColor.bitmap(w,h) else Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                val canvas=android.graphics.Canvas(bitmap);val paint=android.graphics.Paint()
                for (y in 0 until h) for (x in 0 until w) {
                    paint.color=android.graphics.Color.rgb(random.nextInt(256),random.nextInt(256),random.nextInt(256))
                    canvas.drawRect(x.toFloat(),y.toFloat(),x+1f,y+1f,paint)
                }
                try {
                    val mask=FloatArray(192*192) { random.nextFloat() }
                    val options=BackgroundEffect.options(Effect.create("Auto Background"))
                    val full=Native.backgroundMatte(bitmap,mask,null,options)
                    val compact=Native.backgroundMatte(bitmap,mask,null,options,true)
                    val n=w*h;val guide=Native.backgroundGuide(full.copyOfRange(n+3,full.size),w,h)
                    check((0 until n+3).all { full[it]==compact[it] })
                    check(guide.indices.all { kotlin.math.abs(guide[it]-compact[n+3+it])<.000001f })
                } finally { bitmap.recycle() }
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
        for (landscape in listOf(false,true)) test("live Auto Background measures drawn preview and recording frames in ${if (landscape) "landscape" else "portrait"}") {
            lateinit var capture: Capture
            main {
                a.requestedOrientation=if (landscape) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                a.showCapture();capture=(0 until a.root.childCount).map { a.root.getChildAt(it) }.filterIsInstance<Capture>().single();capture.setHdr(false)
            }
            fun waitFor(message: String,condition: ()->Boolean) {
                val end=android.os.SystemClock.elapsedRealtime()+15000
                while (!condition() && android.os.SystemClock.elapsedRealtime()<end) Thread.sleep(50)
                check(condition()) { message }
            }
            try {
                waitFor("Camera not ready") { capture.readyToRecord }
                waitFor("Camera orientation did not settle") { (a.root.width>a.root.height)==landscape }
                main {
                    Capture::class.java.getDeclaredField("liveEffects").apply { isAccessible=true }.set(capture,mutableListOf(Effect.create("Auto Background")))
                    Capture::class.java.getDeclaredField("effectGeneration").apply { isAccessible=true }.let { it.setInt(capture,it.getInt(capture)+1) }
                }
                val renderTimes=java.util.Collections.synchronizedList(mutableListOf<Double>())
                val readTimes=java.util.Collections.synchronizedList(mutableListOf<Double>())
                val segmentation=java.util.Collections.synchronizedList(mutableListOf<Double>())
                val displayed=java.util.concurrent.ConcurrentLinkedQueue<Long>()
                var previous: Bitmap?=null
                val stages=java.util.concurrent.ConcurrentHashMap<String,MutableList<Double>>()
                main {
                    val renderer=Capture::class.java.getDeclaredField("backgroundEffects").apply { isAccessible=true }.get(capture) as BackgroundRenderer
                    renderer.frameTime={ renderTimes.add(it) };capture.cameraReadTime={ readTimes.add(it) }
                    renderer.stageTime={ name,time -> stages.getOrPut(name) { java.util.Collections.synchronizedList(mutableListOf()) }.add(time) }
                    capture.segmentationTime={ segmentation.add(it) }
                    capture.segmentationCpuTime={ time -> stages.getOrPut("segmentation.cpu") { java.util.Collections.synchronizedList(mutableListOf()) }.add(time) }
                    capture.backgroundPrepareTime={ time -> stages.getOrPut("background.prepare") { java.util.Collections.synchronizedList(mutableListOf()) }.add(time) }
                    capture.previewDraw={ if (it!==previous) { previous=it;displayed.add(System.nanoTime()) } }
                }
                Thread.sleep(4000)
                fun fps(): Double {
                    val start=System.nanoTime();Thread.sleep(10000);val end=System.nanoTime()
                    val count=displayed.count { it in start..end }
                    check(count>2) { "The background preview stopped" }
                    return count*1e9/(end-start)
                }
                val preview=fps()
                val backend=if (capture.gpuBackground) "GPU FP32" else "CPU FP32"
                val previewRender=renderTimes.toList().sorted().let { it[it.size/2] }
                val read=readTimes.toList().sorted().let { it[it.size/2] }
                val profile=JSONObject()
                for ((name,samples) in stages) profile.put(name,samples.toList().sorted().let { it[it.size/2] })
                if (segmentation.isNotEmpty()) profile.put("segmentation",segmentation.toList().sorted().let { it[it.size/2] })
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
                    .put("preview_backend",backend)
                    .put("preview_render_ms",previewRender).put("camera_read_ms",read)
                    .put("preview_stages_ms",profile)
                    .put("recording_render_ms",renderTimes.toList().sorted().let { it[it.size/2] })
                val orientations=report.optJSONObject("orientations") ?: JSONObject()
                orientations.put(if (landscape) "landscape" else "portrait",JSONObject().put("preview_fps",preview).put("recording_fps",recording).put("backend",backend))
                report.put("orientations",orientations)
                file.writeText(report.toString(2));i.sendStatus(0,Bundle().apply { putString("stream","Live preview: $preview FPS; recording preview: $recording FPS\n") })
                i.sendStatus(0,Bundle().apply { putString("stream",report.toString(2)+"\n") })
            } finally { main { capture.previewDraw=null;capture.segmentationTime=null;capture.segmentationCpuTime=null;capture.backgroundPrepareTime=null;if (capture.recording) capture.stop();a.showEditor() } }
        }
        test("GPU process loss preserves the app and CPU segmentation") {
            val source=File(a.cacheDir,"person-gpu-fallback.png")
            i.context.assets.open("person.png").use { input -> source.outputStream().use { input.copyTo(it) } }
            val bitmap=PhotoSource.frame(source.path,352,199)
            PersonGpu(a).use { gpu ->
                try {
                    var mask=gpu.mask(bitmap);val deadline=System.nanoTime()+4_000_000_000L
                    while (mask==null && System.nanoTime()<deadline) { Thread.sleep(30);mask=gpu.mask(bitmap) }
                    if (mask!=null) {
                        val parent=android.os.Process.myPid();val child=gpu.processId
                        check(child>0 && child!=parent)
                        android.os.Process.killProcess(child)
                        check(gpu.mask(bitmap)==null && !gpu.active)
                        check(android.os.Process.myPid()==parent && !a.isFinishing)
                    }
                    check(Person.mask(bitmap).all { it.isFinite() && it in 0f..1f })
                } finally { bitmap.recycle();source.delete() }
            }
        }
        check(normal.all==settings) { "Performance tests changed normal recording settings" }
    }
}
