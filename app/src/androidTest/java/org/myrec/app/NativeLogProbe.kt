package org.myrec.app

import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import org.json.*
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object NativeLogProbe {
    @android.annotation.SuppressLint("MissingPermission")
    fun run(a: MainActivity,folder: File) {
        val manager=a.getSystemService(CameraManager::class.java)
        val id=manager.cameraIdList.first { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING)==CameraCharacteristics.LENS_FACING_BACK }
        val chars=manager.getCameraCharacteristics(id)
        if (chars.availableCaptureRequestKeys.orEmpty().none { it.name=="com.oplus.log.video.mode" }) return
        if (chars.get(CameraCharacteristics.SENSOR_AVAILABLE_TEST_PATTERN_MODES)?.contains(CaptureRequest.SENSOR_TEST_PATTERN_MODE_SOLID_COLOR)!=true) return
        val thread=HandlerThread("native-log-probe").apply { start() };val handler=Handler(thread.looper)
        val opened=CountDownLatch(1);val problem=AtomicReference<Throwable>();var camera: CameraDevice?=null
        val records=JSONArray()
        try {
            manager.openCamera(id,object: CameraDevice.StateCallback() {
                override fun onOpened(value: CameraDevice) { camera=value;opened.countDown() }
                override fun onDisconnected(value: CameraDevice) { problem.set(IllegalStateException("Camera disconnected"));opened.countDown() }
                override fun onError(value: CameraDevice,error: Int) { problem.set(IllegalStateException("Camera error $error"));opened.countDown() }
            },handler)
            check(opened.await(10,TimeUnit.SECONDS));problem.get()?.let { throw it }
            for (profile in listOf(DynamicRangeProfiles.STANDARD,DynamicRangeProfiles.HLG10)) for (mode in listOf(-1,0,1,2)) {
                val record=JSONObject().put("range",profile).put("vendorMode",mode);records.put(record)
                val reader=ImageReader.newInstance(1280,720,ImageFormat.YCBCR_P010,3)
                val sample=AtomicReference<JSONObject>();var session: CameraCaptureSession?=null
                reader.setOnImageAvailableListener({ input -> input.acquireLatestImage()?.use { frame ->
                    val plane=frame.planes[0];val buffer=plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                    var sum=0.0;var square=0.0;var count=0
                    for (y in frame.height/2-8 until frame.height/2+8) for (x in frame.width/2-8 until frame.width/2+8) {
                        val value=(buffer.getShort(y*plane.rowStride+x*plane.pixelStride).toInt() and 65535) ushr 6
                        sum+=value;square+=value.toDouble()*value;count++
                    }
                    sample.set(JSONObject().put("code",sum/count).put("sd",kotlin.math.sqrt(kotlin.math.max(0.0,square/count-(sum/count)*(sum/count)))).put("dataSpace",frame.dataSpace))
                } },handler)
                try {
                    val ready=CountDownLatch(1)
                    val builder=camera!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(reader.surface);set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_OFF)
                        set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_OFF);set(CaptureRequest.CONTROL_AWB_MODE,CaptureRequest.CONTROL_AWB_MODE_OFF)
                        set(CaptureRequest.SENSOR_SENSITIVITY,100);set(CaptureRequest.SENSOR_EXPOSURE_TIME,10_000_000L);set(CaptureRequest.SENSOR_FRAME_DURATION,33_333_333L)
                        set(CaptureRequest.COLOR_CORRECTION_MODE,CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                        set(CaptureRequest.COLOR_CORRECTION_GAINS,android.hardware.camera2.params.RggbChannelVector(1f,1f,1f,1f))
                        set(CaptureRequest.SENSOR_TEST_PATTERN_MODE,CaptureRequest.SENSOR_TEST_PATTERN_MODE_SOLID_COLOR)
                        if (mode>=0) {
                            set(CaptureRequest.Key("com.oplus.log.video.mode",Int::class.javaObjectType),mode)
                            if (chars.availableCaptureRequestKeys.orEmpty().any { it.name=="com.oplus.camera.mode" }) set(CaptureRequest.Key("com.oplus.camera.mode",ByteArray::class.java),"pro_video_log_mode".toByteArray())
                        }
                    }
                    val config=SessionConfiguration(SessionConfiguration.SESSION_REGULAR,listOf(OutputConfiguration(reader.surface).apply { setDynamicRangeProfile(profile) }),{ handler.post(it) },object: CameraCaptureSession.StateCallback() {
                        override fun onConfigured(value: CameraCaptureSession) { session=value;ready.countDown() }
                        override fun onConfigureFailed(value: CameraCaptureSession) { ready.countDown() }
                    }).apply { sessionParameters=builder.build();if (profile==DynamicRangeProfiles.HLG10) HdrRecorder.colorSpace(this,chars,ImageFormat.YCBCR_P010) }
                    camera!!.createCaptureSession(config);check(ready.await(12,TimeUnit.SECONDS) && session!=null) { "The camera refused this native profile" }
                    val curve=JSONArray();record.put("curve",curve)
                    for (level in listOf(0.0,.002,.005,.01,.02,.05,.1,.18,.4,.8,1.0)) {
                        val pattern=(level*4294967295L).toLong().toInt();builder.set(CaptureRequest.SENSOR_TEST_PATTERN_DATA,IntArray(4) { pattern })
                        val settled=CountDownLatch(5);val actual=AtomicReference<TotalCaptureResult>()
                        session!!.setRepeatingRequest(builder.build(),object: CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(session: CameraCaptureSession,request: CaptureRequest,result: TotalCaptureResult) { actual.set(result);settled.countDown() }
                        },handler)
                        check(settled.await(5,TimeUnit.SECONDS));Thread.sleep(80)
                        val data=sample.get() ?: error("No ten-bit sample")
                        curve.put(JSONObject(data.toString()).put("level",level).put("pattern",actual.get()?.get(CaptureResult.SENSOR_TEST_PATTERN_MODE)))
                    }
                } catch (error: Exception) { record.put("error",error.message ?: error.javaClass.simpleName) }
                finally {
                    session?.close();reader.setOnImageAvailableListener(null,null)
                    val closed=CountDownLatch(1)
                    handler.post { reader.close();closed.countDown() }
                    check(closed.await(5,TimeUnit.SECONDS))
                }
                File(folder,"native-log-probe.json").writeText(records.toString(2))
            }
        } finally { camera?.close();thread.quitSafely() }
    }
}
