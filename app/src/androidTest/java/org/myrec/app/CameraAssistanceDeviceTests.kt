package org.myrec.app

import android.app.Instrumentation
import android.content.pm.ActivityInfo
import android.graphics.*
import android.hardware.camera2.*
import android.media.ImageReader
import android.media.MediaFormat
import android.os.SystemClock
import android.view.MotionEvent
import android.view.TextureView
import org.json.*
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.*

object CameraAssistanceDeviceTests {
    fun run(instrumentation: Instrumentation,a: MainActivity,main: (()->Unit)->Unit,test: (String,()->Unit)->Unit) {
        val folder=File(a.getExternalFilesDir(null),"camera-assistance-evidence").apply { mkdirs() }
        val report=JSONObject()
        val manager=a.getSystemService(CameraManager::class.java)
        val inventory=JSONArray()
        for (id in manager.cameraIdList) {
            val c=manager.getCameraCharacteristics(id)
            inventory.put(JSONObject().put("id",id).put("facing",c.get(CameraCharacteristics.LENS_FACING))
                .put("faceMode",CameraFaces.mode(c)).put("timestampSource",c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)))
        }
        report.put("cameras",inventory).put("pairs",JSONArray(DualCamera.pairs(manager)))
        main { a.showCapture() }
        val capture=(0 until a.root.childCount).map { a.root.getChildAt(it) }.filterIsInstance<Capture>().single()
        fun compositor()=Capture::class.java.getDeclaredField("composition").apply { isAccessible=true }.get(capture) as CameraComposition
        fun waitFor(message: String,seconds: Double=10.0,predicate: ()->Boolean) {
            val end=SystemClock.elapsedRealtime()+(seconds*1000).toLong()
            while (!predicate() && SystemClock.elapsedRealtime()<end) Thread.sleep(30)
            check(predicate()) {
                val last=MainActivity::class.java.getDeclaredField("lastMessage").apply { isAccessible=true }.get(a)
                "$message; activityDestroyed=${a.isDestroyed}, attached=${capture.isAttachedToWindow}, front=${capture.front}, ready=${capture.readyToRecord}, lastMessage=$last"
            }
        }
        fun tap(id: String) {
            main {
                val r=capture.controlBounds(id) ?: error("Missing $id")
                val now=SystemClock.uptimeMillis()
                for (action in intArrayOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                    val event=MotionEvent.obtain(now,now+30,action,r.centerX(),r.centerY(),0)
                    capture.dispatchTouchEvent(event); event.recycle()
                }
            }
            Thread.sleep(150)
        }
        fun click(label: String) {
            val end=SystemClock.elapsedRealtime()+4000
            while (SystemClock.elapsedRealtime()<end) {
                var node=instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label)?.firstOrNull()
                if (node!=null) {
                    while (!node!!.isClickable && node!!.parent!=null) node=node!!.parent
                    if (node!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) { Thread.sleep(200); return }
                }
                Thread.sleep(50)
            }
            error("Missing $label")
        }
        fun twoFingerPan(fromX: Float,fromY: Float,toX: Float,toY: Float,gap: Float=.06f) {
            main {
                val view=(0 until capture.childCount).map { capture.getChildAt(it) }.filterIsInstance<TextureView>().single()
                val now=SystemClock.uptimeMillis()
                val properties=Array(2) { i -> MotionEvent.PointerProperties().apply { id=i; toolType=MotionEvent.TOOL_TYPE_FINGER } }
                fun send(action: Int,count: Int,progress: Float) {
                    val coords=Array(count) { i -> MotionEvent.PointerCoords().apply {
                        x=view.left+(fromX+gap*i+(toX-fromX)*progress)*view.width
                        y=view.top+(fromY+(toY-fromY)*progress)*view.height
                        pressure=1f; size=1f
                    } }
                    val event=MotionEvent.obtain(now,now+(progress*150).toLong(),action,count,properties,coords,0,0,1f,1f,0,0,0,0)
                    capture.dispatchTouchEvent(event); event.recycle()
                }
                send(MotionEvent.ACTION_DOWN,1,0f)
                send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,0f)
                for (i in 1..6) send(MotionEvent.ACTION_MOVE,2,i/6f)
                send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,1f)
                send(MotionEvent.ACTION_UP,1,1f)
            }
        }
        val front=manager.cameraIdList.first { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING)==CameraCharacteristics.LENS_FACING_FRONT }
        val rear=manager.cameraIdList.first { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING)==CameraCharacteristics.LENS_FACING_BACK }
        try {
            test("real front camera negotiates full-view face following and records its framing") {
                main { capture.setHdr(false); capture.switchCamera(front) }
                waitFor("Front camera did not become ready") { capture.readyToRecord && capture.front }
                main { capture.setZoom(2f,true); capture.setFaceFollow(true) }
                waitFor("Camera face detection did not become active") { (capture.latestResult?.get(CaptureResult.STATISTICS_FACE_DETECT_MODE) ?: 0)>0 }
                waitFor("The full-view tracking crop did not become active") { compositor().assistanceState().firstOrNull()?.get(2)?.let { it>1.2 }==true }
                val before=a.editor.project.clips.size
                main { capture.start() }
                waitFor("Face-follow take did not start") { capture.recording && !capture.paused }
                var count=0
                repeat(50) { count=max(count,capture.latestResult?.get(CaptureResult.STATISTICS_FACES)?.count { it.score>=50 } ?: 0); Thread.sleep(40) }
                val status=compositor().followStatus
                val framing=compositor().assistanceState().first()
                report.put("liveFaces",count).put("followStatus",status).put("frontFraming",JSONArray(framing.toList()))
                if (count>0) check(status!="Looking for a face") { "Camera detected a face but the framing did not see it" }
                main {
                    val texture=(0 until capture.childCount).map { capture.getChildAt(it) }.filterIsInstance<TextureView>().single()
                    val x=texture.left+texture.width*.4f; val y=texture.top+texture.height*.55f
                    val now=SystemClock.uptimeMillis()
                    val properties=Array(2) { i -> MotionEvent.PointerProperties().apply { id=i; toolType=MotionEvent.TOOL_TYPE_FINGER } }
                    fun send(action: Int,count: Int,offset: Float) {
                        val coords=Array(count) { i -> MotionEvent.PointerCoords().apply { this.x=x+i*80+offset; this.y=y; pressure=1f; size=1f } }
                        val e=MotionEvent.obtain(now,now+30,action,count,properties,coords,0,0,1f,1f,0,0,0,0)
                        capture.dispatchTouchEvent(e); e.recycle()
                    }
                    send(MotionEvent.ACTION_DOWN,1,0f)
                    send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,0f)
                    send(MotionEvent.ACTION_MOVE,2,texture.width*.08f)
                    send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,texture.width*.08f)
                    send(MotionEvent.ACTION_UP,1,texture.width*.08f)
                }
                check(!a.captureSettings.getBoolean("followFace",true)) { "Manual pan left face following active" }
                val manual=compositor().assistanceState().first().take(3)
                Thread.sleep(500)
                check(compositor().assistanceState().first().take(3).zip(manual).all { abs(it.first-it.second)<.005 })
                main { capture.stop() }
                check(a.editor.project.clips.size==before+1)
                val clip=a.editor.project.clips.last()
                check(clip.duration>1.5)
                VideoDecoder(clip.path).use { decoder -> check(decoder.frame(.5,180,320)!=null) }
            }
            test("rear horizon assistance receives motion and uses bounded fixed overscan") {
                main { capture.setFaceFollow(false); capture.switchCamera(rear) }
                waitFor("Rear camera did not become ready") { capture.readyToRecord && !capture.front }
                val horizon=Capture::class.java.getDeclaredField("horizon").apply { isAccessible=true }.get(capture) as CameraHorizon
                check(horizon.available)
                tap("settings"); click("More settings"); click("Max angle")
                var slider: android.view.accessibility.AccessibilityNodeInfo?=null
                waitFor("Max angle slider did not open",4.0) {
                    slider=instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Max angle")?.firstOrNull { it.rangeInfo!=null }
                    slider!=null
                }
                check(slider!!.performAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                    android.os.Bundle().apply { putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,19f) }))
                waitFor("The maximum-angle preview did not use the selected crop") { compositor().assistanceState().firstOrNull()?.get(4)?.let { it>1.25 }==true }
                click("Done")
                waitFor("Closing Max angle left leveling enabled") { compositor().assistanceState().firstOrNull()?.get(4)==1.0 }
                main { capture.setHorizonDegrees(9.0); capture.setHorizonEnabled(true) }
                waitFor("Gravity samples did not arrive") { horizon.received>=10 }
                waitFor("Horizon overscan did not reach the camera") { compositor().assistanceState().firstOrNull()?.get(4)?.let { it>1.2 }==true }
                val framing=compositor().assistanceState().first()
                check(abs(framing[3])<=9*PI/180)
                report.put("motionSamples",horizon.received).put("rearFraming",JSONArray(framing.toList()))
                main { capture.setHorizonEnabled(false) }
                waitFor("Horizon did not release its overscan") { compositor().assistanceState().firstOrNull()?.get(4)==1.0 }
                check(!horizon.active)
            }
            if (HdrRecorder.supported(manager,rear) && HdrGraphics.support.reason==null) test("HDR horizon framing retains its ten-bit HLG recording format") {
                main { capture.setHdr(true); capture.setHorizonEnabled(true) }
                waitFor("HDR assistance camera did not become ready") { capture.readyToRecord }
                main { capture.start() }; Thread.sleep(1800)
                check(capture.recording && compositor().assistanceState().first()[4]>1.2)
                main { capture.stop() }
                val clip=a.editor.project.clips.last()
                val ex=android.media.MediaExtractor()
                try {
                    ex.setDataSource(clip.path)
                    val format=(0 until ex.trackCount).map(ex::getTrackFormat).first { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true }
                    check(format.getString(MediaFormat.KEY_MIME)==MediaFormat.MIMETYPE_VIDEO_HEVC)
                    check(format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)==MediaFormat.COLOR_TRANSFER_HLG)
                    check(format.getInteger(MediaFormat.KEY_COLOR_STANDARD)==MediaFormat.COLOR_STANDARD_BT2020)
                } finally { ex.release() }
                VideoDecoder(clip.path).use { check(it.frame(.5,180,320)!=null) }
                main { capture.setHorizonEnabled(false); capture.setHdr(false) }
                waitFor("SDR camera did not return") { capture.readyToRecord }
            }
            if (DualCamera.pairs(manager).isNotEmpty()) test("dual guides, diagonal, shapes and reset preserve the take across paused setup changes") {
                main { capture.setHdr(false); capture.setDualLayout(1); capture.setFaceFollow(true) }
                waitFor("Concurrent cameras did not open") { capture.readyToRecord && capture.concurrentInputSizes().size==2 }
                val pair=DualCamera.pair(manager)!!
                main { capture.selectDualCamera(0,pair[1]) }
                check(capture.front==(manager.getCameraCharacteristics(pair[1]).get(CameraCharacteristics.LENS_FACING)==CameraCharacteristics.LENS_FACING_FRONT))
                main { capture.selectDualCamera(0,pair[0]) }
                check(capture.front==(manager.getCameraCharacteristics(pair[0]).get(CameraCharacteristics.LENS_FACING)==CameraCharacteristics.LENS_FACING_FRONT))
                main { capture.start() }; Thread.sleep(700)
                val recorder=Capture::class.java.getDeclaredField("recorder").apply { isAccessible=true }.get(capture)
                main {
                    capture.setFaceFollow(false); capture.setDualLayout(3)
                    compositor().gesture(.2f,.35f,0f,0f,2f)
                    compositor().gesture(.75f,.7f,0f,0f,2f)
                }
                val beforePan=compositor().assistanceState().map { it.take(3) }
                twoFingerPan(.2f,.35f,.75f,.7f)
                val crossed=compositor().assistanceState().map { it.take(3) }
                check(crossed[0][0]<beforePan[0][0]-.2 && crossed[0][1]<beforePan[0][1]-.2)
                check(crossed[1]==beforePan[1]) { "Crossing the diagonal changed the other camera" }
                twoFingerPan(.35f,.45f,.4f,.5f,.35f)
                check(compositor().assistanceState().map { it.take(3) }==crossed) { "Touches starting in different triangles changed a camera" }
                report.put("diagonalGestureOwnership",true)
                for (orientation in intArrayOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                    main { a.requestedOrientation=orientation }; Thread.sleep(800)
                    tap("dual")
                    tap("splitVertical")
                    main {
                        val handle=capture.controlBounds("dualDivider") ?: error("No divider guide")
                        val texture=(0 until capture.childCount).map { capture.getChildAt(it) }.filterIsInstance<TextureView>().single()
                        val now=SystemClock.uptimeMillis()
                        for (i in 0..8) {
                            val x=handle.centerX()+(texture.right-handle.centerX())*i/8
                            val e=MotionEvent.obtain(now,now+i*20L,if (i==0) MotionEvent.ACTION_DOWN else if (i==8) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE,x,handle.centerY(),0)
                            capture.dispatchTouchEvent(e); e.recycle()
                        }
                    }
                    check(abs(compositor().layoutState().vertical-.85)<.001)
                    tap("splitDiagonal"); val before=compositor().layoutState().diagonalFlipped
                    tap("diagonalFlip"); check(compositor().layoutState().diagonalFlipped!=before)
                    tap("pip"); tap("roundPip")
                    val circle=compositor().insetBounds() ?: error("Missing circular inset")
                    main {
                        val texture=(0 until capture.childCount).map { capture.getChildAt(it) }.filterIsInstance<TextureView>().single()
                        check(abs(circle.width()*texture.width-circle.height()*texture.height)<2)
                        compositor().movePip(.65f-circle.centerX(),.55f-circle.centerY())
                        capture.setFaceFollow(true)
                    }
                    val inset=compositor().insetBounds()!!
                    val gap=inset.width()*.25f
                    twoFingerPan(inset.centerX()-gap/2,inset.centerY(),inset.centerX()-gap/2-.05f,inset.centerY()+.05f,gap)
                    val movedInset=compositor().insetBounds()!!
                    check(abs(movedInset.centerX()-inset.centerX()+.05f)<.01 && abs(movedInset.centerY()-inset.centerY()-.05f)<.01)
                    check(capture.faceFollowEnabled) { "Moving the floating view cancelled face following" }
                    tap("rectPip"); tap("dualDone")
                    main { check(capture.controlBounds("dualDivider")==null) }
                    instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                        File(folder,"dual-$orientation.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }; image.recycle()
                    }
                }
                tap("settings"); click("More settings"); click("Reset framing")
                check(compositor().layoutState().vertical==.5 && !compositor().layoutState().diagonalFlipped)
                check(capture.recording && capture.concurrentInputSizes().size==2)
                main { capture.pause(); capture.setDualLayout(4) }
                waitFor("Paused single camera did not reopen") { capture.readyToRecord && capture.concurrentInputSizes().isEmpty() }
                check(capture.paused)
                main { capture.pause() }; Thread.sleep(1000)
                check(Capture::class.java.getDeclaredField("recorder").apply { isAccessible=true }.get(capture)===recorder)
                main { capture.stop() }
                val clip=a.editor.project.clips.last()
                check(clip.duration>3)
                VideoDecoder(clip.path).use { check(it.frame(clip.duration-.2,180,320)!=null) }
                main { capture.setFaceFollow(false); a.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            }
            main { capture.suspendCamera() }
            test("GPU face framing holds its target and mirrored panning reaches saved pixels") {
                fixture(a,folder,report)
            }
            test("diagonal masks match reference coverage in both directions and aspect ratios") {
                diagonalFixture(report)
            }
        } finally {
            File(folder,"live-camera.json").writeText(report.toString(2)+"\n")
            main { if (capture.recording) capture.stop(); capture.suspendCamera() }
        }
    }

    private fun fixture(a: MainActivity,folder: File,report: JSONObject) {
        val width=320; val height=180
        val reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,3)
        val thread=android.os.HandlerThread("assist-fixture").apply { start() }
        reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() },android.os.Handler(thread.looper))
        val failure=AtomicReference<Throwable>()
        val gravity=3*PI/180
        val renderer=CameraComposition(reader.surface,width,height,gravity={ CameraPoint(-sin(gravity),-cos(gravity)) }) { failure.set(it) }
        val file=File(a.cacheDir,"assist-fixture-${uid()}.mp4")
        val recorder=CaptureRecorder(file,width,height,30,0,false,a.audioCache) { failure.set(it) }
        var stopped=false
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        fun time()=(recorder.clock.time(System.nanoTime()) ?: 0L)/1e9
        fun center(image: Bitmap): CameraPoint {
            var x=0.0; var y=0.0; var count=0
            for (py in 0 until image.height) for (px in 0 until image.width) {
                val p=image.getPixel(px,py)
                if (Color.green(p)>180 && Color.red(p)<80 && Color.blue(p)<80) { x+=px; y+=py; count++ }
            }
            check(count>100) { "The green framing target disappeared" }
            return CameraPoint(x/count/image.width,y/count/image.height)
        }
        fun slope(image: Bitmap): Double {
            val points=mutableListOf<Pair<Double,Double>>()
            for (x in 10 until image.width-10) {
                var sum=0.0; var n=0
                for (y in 10 until image.height-10) {
                    val p=image.getPixel(x,y)
                    if (Color.red(p)>200 && Color.green(p)>200 && Color.blue(p)>200) { sum+=y; n++ }
                }
                if (n>0) points.add(x.toDouble() to sum/n)
            }
            check(points.size>image.width/2) { "No coherent horizon in the saved picture" }
            val x=points.map { it.first }.average(); val y=points.map { it.second }.average()
            return atan2(points.sumOf { (it.first-x)*(it.second-y) },points.sumOf { (it.first-x).pow(2) })*180/PI
        }
        try {
            renderer.configure(4,false,0,width,height); renderer.mirrors(false,false)
            var input=renderer.input(width,height,0,true,id="face")
            renderer.record(recorder.surface,width,height,0,30,recorder::videoFrame); recorder.start()
            val face=CameraRect(.58,.4,.72,.6)
            fun drawFace(boxes: List<CameraRect>) {
                renderer.faces("face",boxes,System.nanoTime()/1e9)
                val canvas=input.lockCanvas(null)
                canvas.drawColor(Color.rgb(35,45,65)); paint.color=Color.GREEN
                canvas.drawRect((face.left*width).toFloat(),(face.top*height).toFloat(),(face.right*width).toFloat(),(face.bottom*height).toFloat(),paint)
                input.unlockCanvasAndPost(canvas); Thread.sleep(35)
            }
            renderer.assists(true,null,false,2.0,1.0,true)
            repeat(50) { drawFace(listOf(face)) }
            val followed=renderer.previewFrame(width,height)!!.let { image -> try { center(image) } finally { image.recycle() } }
            check(abs(followed.x-.5)<.065 && abs(followed.y-.5)<.03) { "Wrong following crop $followed" }
            val faceTime=time()-.3
            repeat(30) { drawFace(emptyList()) }
            check(renderer.followStatus=="Holding last framing")
            val held=renderer.previewFrame(width,height)!!.let { image -> try { center(image) } finally { image.recycle() } }
            check(abs(held.x-followed.x)<.005 && abs(held.y-followed.y)<.005)
            renderer.mirrors(true,false)
            repeat(3) { drawFace(emptyList()) }
            val mirrored=renderer.previewFrame(width,height)!!.let { image -> try { center(image) } finally { image.recycle() } }
            check(abs(mirrored.x-(1-held.x))<.01)
            renderer.assists(false,null,false,2.0,1.0,true)
            renderer.gesture(.5f,.5f,.1f,0f,1f)
            repeat(24) { drawFace(emptyList()) }
            val panned=renderer.previewFrame(width,height)!!.let { image -> try { center(image) } finally { image.recycle() } }
            check(abs(panned.x-mirrored.x-.1)<.015) { "Mirrored guide moved the wrong picture: $mirrored -> $panned" }
            val panTime=time()-.3
            renderer.clearInputs(); renderer.configure(4,false,0,width,height)
            input=renderer.input(width,height,0,false,id="horizon")
            renderer.assists(false,null,false)
            fun drawHorizon() {
                val canvas=input.lockCanvas(null); canvas.drawColor(Color.rgb(40,70,100))
                canvas.save(); canvas.rotate(3f,width/2f,height/2f)
                paint.color=Color.WHITE; paint.strokeWidth=4f
                canvas.drawLine(-width.toFloat(),height/2f,width*2f,height/2f,paint); canvas.restore()
                input.unlockCanvasAndPost(canvas); Thread.sleep(35)
            }
            repeat(4) { drawHorizon() }
            val raw=renderer.previewFrame(width,height)!!.let { image -> try { slope(image) } finally { image.recycle() } }
            check(abs(raw-3)<.6) { "The unassisted fixture is not a three-degree line: $raw" }
            renderer.assists(false,6.0,false)
            repeat(50) { drawHorizon() }
            val straight=renderer.previewFrame(width,height)!!.let { image ->
                try {
                    for ((x,y) in listOf(0 to 0,(width-1) to 0,0 to (height-1),(width-1) to (height-1))) check(Color.blue(image.getPixel(x,y))>80)
                    slope(image)
                } finally { image.recycle() }
            }
            check(abs(straight)<.7) { "Preview horizon is still tilted: $straight" }
            val horizonTime=time()-.3
            renderer.record(null); recorder.stop(); stopped=true
            failure.get()?.let { throw it }
            file.copyTo(File(folder,"assistance-fixture.mp4"),true)
            report.put("fixtureTimes",JSONArray(listOf(faceTime,panTime,horizonTime)))
            VideoDecoder(file.path).use { decoder ->
                val faceImage=decoder.frame(faceTime,width,height) ?: error("Missing followed movie frame")
                try { check(abs(center(faceImage).x-followed.x)<.02) } finally { faceImage.recycle() }
                val panImage=decoder.frame(panTime,width,height) ?: error("Missing manually framed movie picture")
                try { check(abs(center(panImage).x-(1-panned.x))<.02) } finally { panImage.recycle() }
                val levelImage=decoder.frame(horizonTime,width,height) ?: error("Missing leveled movie frame")
                try { check(abs(slope(levelImage))<.8) } finally { levelImage.recycle() }
            }
            report.put("fixtureFace",JSONArray(listOf(followed.x,followed.y)))
                .put("fixtureRawSlopeDegrees",raw).put("fixtureLeveledSlopeDegrees",straight)
        } finally {
            runCatching { renderer.record(null) }
            if (!stopped) runCatching { recorder.stop() }
            renderer.close(); recorder.close(); reader.close(); thread.quitSafely(); file.delete()
        }
    }

    private fun diagonalFixture(report: JSONObject) {
        val reader=ImageReader.newInstance(320,180,PixelFormat.RGBA_8888,3)
        val thread=android.os.HandlerThread("diagonal-fixture").apply { start() }
        reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() },android.os.Handler(thread.looper))
        val failure=AtomicReference<Throwable>()
        val renderer=CameraComposition(reader.surface,320,180) { failure.set(it) }
        var maximum=0; var blended=0
        try {
            val sources=List(2) { renderer.input(320,180,0,false) }
            repeat(3) {
                sources.forEachIndexed { i,surface ->
                    val canvas=surface.lockCanvas(null); canvas.drawColor(if (i==0) Color.RED else Color.BLUE)
                    surface.unlockCanvasAndPost(canvas)
                }
                Thread.sleep(40)
            }
            for ((width,height) in listOf(320 to 180,180 to 320)) for (flipped in listOf(false,true)) {
                renderer.configure(3,false,0,width,height)
                if (renderer.layoutState().diagonalFlipped!=flipped) renderer.flipDiagonal()
                val image=renderer.previewFrame(width,height) ?: error("Missing diagonal picture")
                try {
                    for (y in 0 until height) for (x in 0 until width) {
                        val value=if (flipped) (y+.5)/height-(x+.5)/width else (x+.5)/width+(y+.5)/height-1
                        val distance=value/hypot(1.0/width,1.0/height)
                        val coverage=(abs(distance)-.5).coerceIn(0.0,1.0)
                        if (coverage>0 && coverage<1) blended++
                        val pixel=image.getPixel(x,y)
                        check(Color.alpha(pixel)==255)
                        val channels=listOf(Color.red(pixel),Color.green(pixel),Color.blue(pixel))
                        for (c in 0..2) {
                            val feed=if (c==(if (distance<=0) 0 else 2)) 255.0 else 0.0
                            val expected=(.12*255*(1-coverage)+feed*coverage).roundToInt()
                            maximum=max(maximum,abs(channels[c]-expected))
                        }
                    }
                } finally { image.recycle() }
            }
            failure.get()?.let { throw it }
            check(maximum<=2 && blended>100) { "Diagonal coverage differs from reference: error=$maximum, blended=$blended" }
            report.put("diagonalMaximumChannelError",maximum).put("diagonalBlendedPixels",blended)
        } finally { renderer.close(); reader.close(); thread.quitSafely() }
    }
}
