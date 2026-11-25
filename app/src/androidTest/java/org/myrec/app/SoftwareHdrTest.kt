package org.myrec.app

import android.graphics.Color
import android.graphics.Paint
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

internal object SoftwareHdrTest {
    fun run(a: MainActivity, main: (() -> Unit) -> Unit) {
        val view=TextureView(a)
        val width=320
        val height=192
        val levels=listOf(0,4,32,77,128,204,230,255)
        val paint=Paint()
        main { a.root.addView(view,FrameLayout.LayoutParams(width,height)) }
        try {
            var display: Surface?=null
            repeat(60) {
                if (display==null) {
                    main { view.surfaceTexture?.let { display=Surface(it) } }
                    if (display==null) Thread.sleep(50)
                }
            }
            val failure=AtomicReference<Throwable>()
            CameraComposition(checkNotNull(display),width,height,failure=failure::set).use { renderer ->
                val source=renderer.input(width,height,0,false,id="chart")
                var marker=0
                fun post(chart: Boolean, flipped: Boolean = false) {
                    marker=5-marker
                    val canvas=source.lockCanvas(null)
                    try {
                        fun rect(left: Int, top: Int, right: Int, bottom: Int, luma: Int) {
                            paint.color=Color.rgb(luma,luma,luma)
                            canvas.drawRect(left.toFloat(),top.toFloat(),right.toFloat(),bottom.toFloat(),paint)
                        }
                        if (chart) {
                            for ((i,luma) in levels.withIndex()) rect(i*40,0,(i+1)*40,height,luma)
                        } else {
                            rect(0,0,width/2,height,if (flipped) 174 else 42)
                            rect(width/2,0,width,height,if (flipped) 42 else 174)
                            rect(0,height/2-3,width,height/2+3,77)
                        }
                        rect(0,0,8,height,marker)
                    } finally { source.unlockCanvasAndPost(canvas) }
                }
                fun sample(xs: List<Int>): List<Int> {
                    repeat(60) {
                        failure.get()?.let { throw it }
                        val frame=renderer.previewFrame(width,height)
                        if (frame!=null) try {
                            if (abs(Color.red(frame.getPixel(4,height/2))-marker)<=1)
                                return xs.map { Color.red(frame.getPixel(it,height/2)) }
                        } finally { frame.recycle() }
                        Thread.sleep(15)
                    }
                    error("Camera compositor did not receive exposure chart marker $marker")
                }
                val patches=levels.indices.map { it*40+20 }
                post(true)
                val sdr=sample(patches)
                renderer.softwareHdr=mapOf("chart" to .7f)
                post(true)
                val hdr=sample(patches)
                check(sdr.zip(levels).all { (a,b) -> abs(a-b)<=2 }) { "SDR chart changed: $sdr" }
                check(hdr.zipWithNext().all { (a,b) -> b>a }) { "Software HDR lost highlight gradations: $hdr" }
                check(hdr[0]==sdr[0] && hdr[1]==sdr[1] && hdr.last()==sdr.last()) { "Black, noise floor or white moved: $hdr" }
                check(hdr[3]>=sdr[3]+20 && hdr[4]>=sdr[4]+17) { "Software HDR did not recover midtones: $hdr" }

                val sides = listOf(width/4,width*3/4)
                renderer.softwareHdr=emptyMap()
                post(false)
                check(sample(sides)==listOf(77,77))
                renderer.softwareHdr=mapOf("chart" to .7f)
                val global = sample(sides)
                check(global[0]==global[1]) { "Global tone mapping changed equal midtones differently: $global" }
                post(false)
                val initial = sample(sides)
                check(initial[0] >= initial[1]+8) { "Equal midtones do not adapt to their surroundings: $initial" }
                Thread.sleep(105)
                post(false,true)
                val afterChange = sample(sides)
                repeat(8) { Thread.sleep(105); post(false,true); sample(sides) }
                val settled = sample(sides)
                check(settled[1] >= settled[0]+8) { "The exposure map did not adapt after the scene changed: $settled" }
                check(afterChange[0] in (settled[0]+2)..initial[0] && afterChange[1] in initial[1]..(settled[1]-2)) {
                    "Tone mapping jumped after the lighting changed: $initial → $afterChange → $settled"
                }

                val file=File.createTempFile("adaptive-hdr-",".mp4",a.cacheDir)
                try {
                    val mic=a.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS)
                        .first { it.type==AudioDeviceInfo.TYPE_BUILTIN_MIC }
                    CaptureRecorder(file,width,height,30,0,false,a.audioCache,failure::set).use { recorder ->
                        TestMicrophone(recorder,mic,failure).use {
                            renderer.record(recorder.surface,width,height,clock=recorder::videoFrame)
                            recorder.start()
                            repeat(44) { post(false,true); Thread.sleep(34); failure.get()?.let { throw it } }
                            renderer.record(null)
                            recorder.stop()
                        }
                    }
                    val recorded=VideoDecoder(file.path).use { decoder ->
                        val decoded=checkNotNull(decoder.frame(.6,width,height))
                        try {
                            sides.map { Color.red(decoded.getPixel(it,height/2)) }
                        } finally { decoded.recycle() }
                    }
                    check(recorded.zip(settled).all { (a,b) -> abs(a-b)<=6 }) {
                        "Preview and recorded tone mapping differ: $settled vs $recorded"
                    }
                    val result=JSONObject(mapOf("input_levels" to levels,"tone_mapped_levels" to hdr,
                        "old_global" to global,"local" to initial,"first_change" to afterChange,
                        "settled" to settled,"recorded" to recorded))
                    File(a.getExternalFilesDir(null),"software-hdr-chart.json").writeText(result.toString(2))
                } finally { file.delete() }
            }
        } finally { main { a.root.removeView(view) } }
    }
}
