package org.myrec.app

import android.app.Instrumentation
import android.graphics.*
import android.hardware.camera2.*
import android.hardware.camera2.params.*
import android.media.*
import android.os.Handler
import android.os.HandlerThread
import android.view.*
import android.widget.FrameLayout
import org.json.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object HdrColorDeviceTests {
    fun run(i: Instrumentation,a: MainActivity,main: (()->Unit)->Unit,test: (String,()->Unit)->Unit) {
        val folder=File(a.getExternalFilesDir(null),"hdr-color-checks").apply { mkdirs() }
        val manager=a.getSystemService(CameraManager::class.java)
        val report=JSONObject()
        GradingParityTests.run(i,a,main,test)
        test("native camera colour and vendor Log controls are discovered without guessing a profile") {
            val cameras=JSONArray()
            manager.cameraIdList.forEach { id ->
                val chars=manager.getCameraCharacteristics(id)
                cameras.put(JSONObject().put("id",id).put("requests",JSONArray(chars.availableCaptureRequestKeys.orEmpty().filter { "log" in it.name.lowercase() }.map { it.name }))
                    .put("results",JSONArray(chars.availableCaptureResultKeys.orEmpty().filter { "log" in it.name.lowercase() }.map { it.name }))
                    .put("p010",JSONArray(chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.YCBCR_P010).orEmpty().map { it.toString() }))
                    .put("range",JSONArray(chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)?.supportedProfiles.orEmpty().toList())))
            }
            report.put("cameras",cameras);File(folder,"capabilities.json").writeText(report.toString(2))
        }
        test("advertised native Log controls are checked against solid sensor patterns") { NativeLogProbe.run(a,folder) }
        test("P010 packing preserves every ten-bit value with padded rows and separate chroma offsets") {
            val width=1024;val height=2;val stride=width*2+32
            val y=ByteBuffer.allocateDirect(stride*height).order(ByteOrder.nativeOrder())
            val chroma=ByteBuffer.allocateDirect(stride).order(ByteOrder.nativeOrder())
            repeat(height) { row -> repeat(width) { x -> y.putShort(row*stride+x*2,(x shl 6).toShort()) } }
            repeat(width/2) { x -> chroma.putShort(x*4,(x shl 6).toShort());chroma.putShort(x*4+2,((1023-x) shl 6).toShort()) }
            val outY=ByteBuffer.allocateDirect(width*height*2).order(ByteOrder.nativeOrder())
            val outUV=ByteBuffer.allocateDirect(width*height).order(ByteOrder.nativeOrder())
            check(Native.packP010(y,chroma,chroma.duplicate().apply { position(2) }.slice(),stride,stride,stride,4,4,width,height,outY,outUV))
            repeat(height) { row -> repeat(width) { x -> check((outY.getShort((row*width+x)*2).toInt() and 65535)==x shl 6) } }
            repeat(width/2) { x -> check((outUV.getShort(x*4).toInt() and 65535)==x shl 6);check((outUV.getShort(x*4+2).toInt() and 65535)==(1023-x) shl 6) }
            check(!Native.packP010(y,chroma,chroma,stride,stride,stride,4,4,width,height,ByteBuffer.allocateDirect(1),outUV))
            check(HdrGraphics.evaluate(HdrGraphics.HLG,"",true,"fixture",allowPlanar=true).let { it.reason==null && it.planar })
            check(HdrGraphics.evaluate("","",true,"fixture",allowPlanar=true).reason!=null)
        }
        test("P010 composition and encoding preserve more than eight bits and match the HLG source ramp") { P010RampTest.run(a,main,folder) }
        test("P010 colour patches preserve range, chroma and primaries in preview and recording") { HdrColorTest.run(i,a,planar=true) }
        test("camera P010 HLG records through the compositor without a YUV texture extension") {
            cameraP010(a,main,manager,folder)
        }
    }
    @android.annotation.SuppressLint("MissingPermission")
    private fun cameraP010(a: MainActivity,main: (()->Unit)->Unit,manager: CameraManager,folder: File) {
        val id=manager.cameraIdList.first { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING)==CameraCharacteristics.LENS_FACING_BACK }
        val chars=manager.getCameraCharacteristics(id)
        check(HdrRecorder.planarSupported(chars,1280,720)) { "No 720p P010 camera stream" }
        val thread=HandlerThread("p010-test").apply { start() };val handler=Handler(thread.looper)
        val ready=CountDownLatch(1);val surfaceReady=CountDownLatch(1);val failure=AtomicReference<Throwable>()
        var preview: SurfaceView?=null;var renderer: CameraComposition?=null;var camera: CameraDevice?=null;var session: CameraCaptureSession?=null
        val output=File(folder,"p010-camera.mp4")
        val recorder=CaptureRecorder(output,1280,720,30,0,true,a.audioCache) { failure.set(it) }
        val device=a.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS).first { it.type==AudioDeviceInfo.TYPE_BUILTIN_MIC }
        var microphone: TestMicrophone?=null
        try {
            main {
                preview=SurfaceView(a).apply {
                    holder.setFormat(PixelFormat.RGBA_1010102)
                    holder.addCallback(object: SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady.countDown() }
                        override fun surfaceChanged(holder: SurfaceHolder,format: Int,width: Int,height: Int) {}
                        override fun surfaceDestroyed(holder: SurfaceHolder) {}
                    })
                }
                a.root.addView(preview,FrameLayout.LayoutParams(640,360))
            }
            check(surfaceReady.await(10,TimeUnit.SECONDS)) { "No ten-bit preview surface" }
            renderer=CameraComposition(preview!!.holder.surface,640,360,hdr=true,planar=true) { failure.set(it) }
            val input=renderer.input(1280,720,chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,false,id=id)
            renderer.configure(4,false,0,640,360)
            renderer.record(recorder.surface,1280,720,0,30,recorder::videoFrame)
            microphone=TestMicrophone(recorder,device,failure)
            manager.openCamera(id,object: CameraDevice.StateCallback() {
                override fun onOpened(value: CameraDevice) {
                    camera=value
                    try {
                        val config=SessionConfiguration(SessionConfiguration.SESSION_REGULAR,listOf(OutputConfiguration(input).apply {
                            setDynamicRangeProfile(DynamicRangeProfiles.HLG10);setMirrorMode(OutputConfiguration.MIRROR_MODE_NONE);setTimestampBase(OutputConfiguration.TIMESTAMP_BASE_MONOTONIC)
                        }),{ handler.post(it) },object: CameraCaptureSession.StateCallback() {
                            override fun onConfigured(value: CameraCaptureSession) {
                                session=value
                                try {
                                    recorder.start()
                                    value.setRepeatingRequest(camera!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                        addTarget(input);set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO)
                                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,android.util.Range(30,30))
                                    }.build(),null,handler)
                                } catch (error: Throwable) { failure.set(error) }
                                ready.countDown()
                            }
                            override fun onConfigureFailed(value: CameraCaptureSession) { failure.set(IllegalStateException("P010 camera session refused"));ready.countDown() }
                        })
                        HdrRecorder.colorSpace(config,chars,ImageFormat.YCBCR_P010);value.createCaptureSession(config)
                    } catch (error: Throwable) { failure.set(error);ready.countDown() }
                }
                override fun onDisconnected(value: CameraDevice) { failure.set(IllegalStateException("Camera disconnected"));ready.countDown() }
                override fun onError(value: CameraDevice,error: Int) { failure.set(IllegalStateException("Camera error $error"));ready.countDown() }
            },handler)
            check(ready.await(12,TimeUnit.SECONDS));failure.get()?.let { throw it }
            Thread.sleep(2400);failure.get()?.let { throw it }
            session?.stopRepeating();session?.close();session=null;camera?.close();camera=null
            renderer.record(null);recorder.stop()
            val extractor=MediaExtractor()
            try {
                extractor.setDataSource(output.path)
                val format=(0 until extractor.trackCount).map(extractor::getTrackFormat).first { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true }
                check(format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)==MediaFormat.COLOR_TRANSFER_HLG && format.getInteger(MediaFormat.KEY_COLOR_STANDARD)==MediaFormat.COLOR_STANDARD_BT2020)
                File(folder,"p010-format.txt").writeText(format.toString())
            } finally { extractor.release() }
            VideoDecoder(output.path,linear=true,tenBit=true).use { decoder ->
                val frame=decoder.frame(.5,320,180) ?: error("No P010 encoded picture");check(frame.config==Bitmap.Config.RGBA_F16);frame.recycle()
            }
        } finally {
            session?.close();camera?.close();microphone?.close();recorder.close();renderer?.close()
            main { preview?.let { a.root.removeView(it) } };thread.quitSafely()
        }
    }
}
