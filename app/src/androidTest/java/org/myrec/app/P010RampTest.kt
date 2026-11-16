package org.myrec.app

import android.graphics.*
import android.hardware.DataSpace
import android.media.*
import android.view.*
import android.widget.FrameLayout
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.*

object P010RampTest {
    fun run(a: MainActivity,main: (()->Unit)->Unit,folder: File) {
        val w=1280;val h=720;val ready=CountDownLatch(1);val failure=AtomicReference<Throwable>()
        var preview: SurfaceView?=null;var renderer: CameraComposition?=null;var writer: ImageWriter?=null;var microphone: TestMicrophone?=null
        val output=File(folder,"p010-ramp.mp4")
        val recorder=CaptureRecorder(output,w,h,30,0,true,a.audioCache) { failure.set(it) }
        try {
            main {
                preview=SurfaceView(a).apply {
                    holder.setFormat(PixelFormat.RGBA_1010102)
                    holder.addCallback(object: SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) { ready.countDown() }
                        override fun surfaceChanged(holder: SurfaceHolder,format: Int,width: Int,height: Int) {}
                        override fun surfaceDestroyed(holder: SurfaceHolder) {}
                    })
                }
                a.root.addView(preview,FrameLayout.LayoutParams(640,360))
            }
            check(ready.await(10,TimeUnit.SECONDS)) { "No ramp preview surface: attached=${preview?.isAttachedToWindow}, shown=${preview?.isShown}, window=${preview?.windowVisibility}" }
            renderer=CameraComposition(preview!!.holder.surface,640,360,hdr=true,planar=true) { failure.set(it) }
            val surface=renderer.input(w,h,0,false);renderer.configure(4,false,0,640,360)
            renderer.record(recorder.surface,w,h,0,30,recorder::videoFrame)
            val device=a.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS).first { it.type==AudioDeviceInfo.TYPE_BUILTIN_MIC }
            microphone=TestMicrophone(recorder,device,failure)
            writer=ImageWriter.newInstance(surface,3,ImageFormat.YCBCR_P010)
            recorder.start()
            repeat(45) {
                failure.get()?.let { throw it }
                val frame=writer.dequeueInputImage();val planes=frame.planes
                for ((index,plane) in planes.withIndex()) {
                    val data=plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                    val height=if (index==0) h else h/2;val width=if (index==0) w else w/2
                    for (y in 0 until height) for (x in 0 until width) {
                        val code=if (index==0) 64+x*876/(w-1) else 512
                        data.putShort(y*plane.rowStride+x*plane.pixelStride,(code shl 6).toShort())
                        if (planes.size==2 && index==1) data.putShort(y*plane.rowStride+x*plane.pixelStride+2,(512 shl 6).toShort())
                    }
                }
                frame.dataSpace=DataSpace.STANDARD_BT2020 or DataSpace.TRANSFER_HLG or DataSpace.RANGE_LIMITED
                frame.timestamp=System.nanoTime()
                writer.queueInputImage(frame);Thread.sleep(32)
            }
            failure.get()?.let { throw it }
            val raw=renderer.snapshot(0,w,h) ?: error("No P010 source snapshot")
            for (x in listOf(0,64,128,256,512,768,1023)) {
                val expected=(64+x*876/(w-1)-64)/876.0*255
                check(abs(Color.red(raw.getPixel(x,h/2))-expected)<2.5) { "Raw P010 range $x: ${Color.red(raw.getPixel(x,h/2))} != $expected" }
            }
            raw.recycle();renderer.clearInputs();Thread.sleep(100);failure.get()?.let { throw it }
            renderer.record(null);recorder.stop()
            VideoDecoder(output.path,linear=true,tenBit=true).use { decoder ->
                val frame=decoder.frame(.6,w,h) ?: error("No ten-bit ramp")
                val bytes=ByteBuffer.allocateDirect(frame.byteCount).order(ByteOrder.nativeOrder());frame.copyPixelsToBuffer(bytes)
                val values=(0 until w).map { x -> android.util.Half.toFloat(bytes.getShort(h/2*frame.rowBytes+x*8)) }
                check(values.distinct().size>300) { "Only ${values.distinct().size} encoded levels survived" }
                check(values.last()>4.5f && values.first()<.002f) { "HLG headroom or black was lost" }
                for (x in listOf(64,128,256,512,768,1000)) {
                    val code=(x*876/(w-1))/876.0
                    val scene=if (code<=.5) code*code/3 else (exp((code-.55991073)/.17883277)+.28466892)/12
                    val expected=scene.pow(1.2)*1000/203
                    check(abs(values[x]-expected)<.05) { "Ten-bit ramp $x: ${values[x]} != $expected" }
                }
                File(folder,"p010-ramp-precision.json").writeText(org.json.JSONObject().put("levels",values.distinct().size).put("white",values.last()).toString())
                frame.recycle()
            }
        } finally {
            writer?.close();microphone?.close();recorder.close();renderer?.close()
            main { preview?.let { a.root.removeView(it) } }
        }
    }
}
