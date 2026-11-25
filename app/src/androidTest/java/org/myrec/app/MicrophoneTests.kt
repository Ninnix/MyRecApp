package org.myrec.app

import android.app.Instrumentation
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.*
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.SeekBar
import android.widget.TextView
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.*

object MicrophoneTests {
    fun run(instrumentation: Instrumentation, a: MainActivity, main: (() -> Unit) -> Unit,
        test: (String, () -> Unit) -> Unit) {
        main { a.showCapture() }
        val capture=(0 until a.root.childCount).map { a.root.getChildAt(it) }.filterIsInstance<Capture>().single()
        fun <T> field(name: String): T {
            @Suppress("UNCHECKED_CAST")
            return Capture::class.java.getDeclaredField(name).apply { isAccessible=true }.get(capture) as T
        }
        val microphone=field<LiveMicrophone>("microphone")
        fun waitFor(message: String, seconds: Double=8.0, condition: () -> Boolean) {
            val end=SystemClock.elapsedRealtime()+(seconds*1000).toLong()
            while (!condition() && SystemClock.elapsedRealtime()<end) Thread.sleep(25)
            check(condition()) { message }
        }
        fun tap(id: String) {
            main {
                val r=capture.controlBounds(id) ?: error("Missing camera control $id")
                val now=SystemClock.uptimeMillis()
                for (action in intArrayOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                    val event=MotionEvent.obtain(now,now+30,action,r.centerX(),r.centerY(),0)
                    capture.dispatchTouchEvent(event); event.recycle()
                }
            }
            Thread.sleep(150)
        }
        fun pcm(file: File): FloatArray {
            val bytes=a.audioCache.get(file.path).readBytes()
            val buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(buffer.remaining()).also { buffer.get(it) }
        }
        val evidence=File(a.getExternalFilesDir(null),"microphone-evidence").apply { mkdirs() }
        val scratch=File(a.cacheDir,"microphone-${uid()}").apply { mkdirs() }
        try {
            test("recording gain reaches encoded AAC samples with correct stereo, priming and duration") {
                val file=File(scratch,"gain.m4a")
                val journal=CaptureSamples.audio(file)
                val clock=RecordingClock(); val failure=AtomicReference<Throwable>()
                val writer=CaptureAudio(journal,clock) { failure.set(it) }
                val origin=System.nanoTime()
                val gain=RecordingGain()
                var tail: Thread?=null
                try {
                    fun append(block: Int) {
                        if (block==100) gain.set(-12f)
                        if (block==200) gain.set(0f)
                        val source=FloatArray(960) { i ->
                            val frame=block*480+i/2
                            if (i%2==0) (.3*sin(2*PI*1000*frame/48000)).toFloat()
                            else (.15*sin(2*PI*2000*frame/48000)).toFloat()
                        }
                        writer.append(gain.process(source,source.size,2),origin+block*10_000_000L)
                    }
                    repeat(297) { block ->
                        if (block==10) clock.start(origin)
                        append(block)
                        Thread.sleep(10)
                    }
                    tail=Thread {
                        Thread.sleep(120)
                        for (block in 297..299) append(block)
                    }.apply { start() }
                    clock.stop(origin+3_000_000_000)
                    writer.finish(144000)
                    tail.join()
                    failure.get()?.let { throw it }
                    check(writer.insertedSilence==0L)
                    CaptureSamples.Reader(journal).use { reader ->
                        val muxer=MediaMuxer(file.path,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                        try {
                            val track=muxer.addTrack(reader.format)
                            muxer.start()
                            while (true) muxer.writeSampleData(track,reader.next() ?: break,reader.info)
                            muxer.stop()
                        } finally { muxer.release() }
                    }
                    Mp4Audio.trim(file,Mp4Audio.PREFIX+Mp4Audio.delay(a.audioCache.head(file.path,12288)),3.0)
                } finally { tail?.join(1000); writer.close() }
                val decoded=pcm(file)
                check(abs(decoded.size/2-144000)<=2) { "Decoded ${decoded.size/2} frames" }
                fun rms(from: Double,to: Double,ch: Int): Double {
                    var sum=0.0
                    val first=(from*48000).toInt(); val end=(to*48000).toInt()
                    for (i in first until end) sum+=decoded[i*2+ch].toDouble().pow(2)
                    return sqrt(sum/(end-first))
                }
                val base=rms(.2,.8,0); val quiet=rms(1.2,1.8,0); val reset=rms(2.2,2.8,0)
                check(rms(.02,.08,0)>base*.9) { "Samples preceding the first camera timestamp were lost" }
                check(rms(2.975,2.995,0)>base*.9) { "Buffered microphone samples were lost at Stop" }
                val db=20*log10(quiet/base)
                check(abs(db+12)<.3 && abs(20*log10(reset/base))<.1) { "Encoded gain $db dB, reset $reset / $base" }
                check(abs(rms(.2,.8,1)/base-.5)<.02) { "Stereo channels were mixed" }
                file.copyTo(File(evidence,"gain.m4a"),true)
                File(evidence,"gain.txt").writeText("gain=$db dB frames=${decoded.size/2} left=$base right=${rms(.2,.8,1)}\n")
            }
            main { a.showCapture(); capture.setHdr(false) }
            waitFor("Camera and live microphone did not become ready") { capture.readyToRecord && microphone.running && microphone.readings.arriving(System.nanoTime()) && microphone.actual!=null }
            test("live microphone gain is remembered across input-engine reopening") {
                val actual=microphone.actual ?: error("Missing actual route")
                main { capture.setRecordingGain(-10.5f) }
                val key="recordingGain."+MicrophoneInput(actual).gainKey(capture.microphoneDevices().map { MicrophoneInput(it) })
                check(a.captureSettings.getFloat(key,100f)==-10.5f)
                val session=microphone.sessionId
                main { microphone.stop(); capture.resumeCamera() }
                waitFor("Microphone did not reopen with its saved gain") {
                    microphone.sessionId!=null && microphone.sessionId!=session && microphone.actual!=null && microphone.gain.db==-10.5f && microphone.readings.arriving(System.nanoTime())
                }
            }
            test("recording volume panel supports half-decibel input, reset and held clipping in both orientations") {
                for (orientation in intArrayOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                    main { a.requestedOrientation=orientation }
                    Thread.sleep(700)
                    tap("mic")
                    val bar=field<SeekBar>("gainSlider")
                    main {
                        check(bar.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                            Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,93f) }))
                    }
                    check(microphone.gain.db==6.5f) { "Gain ${microphone.gain.db}" }
                    main { microphone.readings.update(floatArrayOf(1.1f),System.nanoTime()) }
                    Thread.sleep(100)
                    val status=field<TextView>("signalReadout")
                    main { check(status.text.toString()=="Clipping") }
                    instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                        File(evidence,if (orientation==ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) "gain-portrait.png" else "gain-landscape.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                        bitmap.recycle()
                    }
                    Thread.sleep(400)
                    main { check(status.text.toString()!="Clipping") }
                    val matches=instrumentation.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("Reset to 0")
                    check(matches.isNotEmpty())
                    var reset=matches.first()
                    while (!reset.isClickable && reset.parent!=null) reset=reset.parent
                    check(reset.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    waitFor("Gain reset did not reach the engine",1.0) { microphone.gain.db==0f }
                    instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                    Thread.sleep(100)
                }
                main { a.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                Thread.sleep(700)
            }
            test("SDR recording keeps one microphone clock across camera switches, input recovery and pause") {
                val manager=a.getSystemService(CameraManager::class.java)
                val rear=manager.cameraIdList.first { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING)==CameraCharacteristics.LENS_FACING_BACK }
                val front=manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING)==CameraCharacteristics.LENS_FACING_FRONT }
                main { capture.switchCamera(rear) }
                waitFor("Rear camera did not return") { capture.readyToRecord && microphone.readings.arriving(System.nanoTime()) }
                val before=a.editor.project.clips.size
                val session=microphone.sessionId
                main { capture.start() }
                waitFor("SDR recorder did not start") { capture.recording && !capture.paused }
                val recorder=field<CaptureRecorder>("recorder")
                Thread.sleep(800)
                if (front!=null) {
                    main { capture.switchCamera(front) }
                    waitFor("Front camera did not open") { capture.front && capture.readyToRecord }
                    check(microphone.sessionId==session && capture.recording && !capture.paused)
                    Thread.sleep(400)
                    main { capture.switchCamera(rear) }
                    waitFor("Rear camera did not reopen") { !capture.front && capture.readyToRecord }
                    check(microphone.sessionId==session && capture.recording && !capture.paused)
                }
                val input=LiveMicrophone::class.java.getDeclaredField("input").apply { isAccessible=true }.get(microphone) as AudioRecord
                input.stop()
                waitFor("Input read failure did not recover",4.0) { microphone.sessionId!=null && microphone.sessionId!=session && microphone.readings.arriving(System.nanoTime()) }
                check(capture.recording && !capture.paused) { "Input restart ended the take" }
                Thread.sleep(300)
                main { capture.pause() }
                check(capture.paused)
                val paused=capture.elapsedSeconds()
                Thread.sleep(350)
                check(abs(capture.elapsedSeconds()-paused)<.02)
                main { capture.pause() }
                check(!capture.paused)
                Thread.sleep(700)
                val active=capture.elapsedSeconds()
                main { capture.stop() }
                check(a.editor.project.clips.size==before+1) { "Recording was not saved" }
                val clip=a.editor.project.clips.last()
                check(abs(clip.duration-active)<.35) { "Movie ${clip.duration}, active $active" }
                check(pcm(File(clip.path)).size>48000)
                File(clip.path).copyTo(File(evidence,"camera-switch.mp4"),true)
                File(evidence,"camera-switch.txt").writeText("active=$active duration=${clip.duration} insertedSilence=${recorder.insertedAudioSilence} corrections=${recorder.audioClockCorrections}\n")
                check(recorder.insertedAudioSilence<48000) { "Recovery inserted more than one second of silence" }
            }
            val manager=a.getSystemService(CameraManager::class.java)
            val rear=manager.cameraIdList.first { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING)==CameraCharacteristics.LENS_FACING_BACK }
            if (HdrRecorder.supported(manager,rear) && HdrGraphics.support.reason==null) {
                test("HDR records the same independent microphone through pause and resume") {
                    val session=microphone.sessionId
                    main { capture.setHdr(true) }
                    waitFor("HDR did not become ready",12.0) { capture.readyToRecord && microphone.readings.arriving(System.nanoTime()) }
                    check(microphone.sessionId==session) { "HDR setup replaced the microphone" }
                    val before=a.editor.project.clips.size
                    main { capture.start() }
                    waitFor("HDR did not start") { capture.recording && !capture.paused }
                    Thread.sleep(800)
                    main { capture.pause() }; Thread.sleep(250); main { capture.pause() }; Thread.sleep(700)
                    check(capture.recording && !capture.paused && microphone.sessionId==session)
                    val active=capture.elapsedSeconds()
                    main { capture.stop() }
                    check(a.editor.project.clips.size==before+1)
                    val clip=a.editor.project.clips.last()
                    check(abs(clip.duration-active)<.15) { "HDR movie ${clip.duration}, active $active" }
                    val ex=MediaExtractor()
                    try {
                        ex.setDataSource(clip.path)
                        val formats=(0 until ex.trackCount).map { ex.getTrackFormat(it) }
                        val video=formats.first { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true }
                        check(video.getString(MediaFormat.KEY_MIME)==MediaFormat.MIMETYPE_VIDEO_HEVC)
                        check(video.getInteger(MediaFormat.KEY_COLOR_TRANSFER)==MediaFormat.COLOR_TRANSFER_HLG)
                        check(formats.any { it.getString(MediaFormat.KEY_MIME)==MediaFormat.MIMETYPE_AUDIO_AAC })
                    } finally { ex.release() }
                    check(pcm(File(clip.path)).size>48000)
                    File(clip.path).copyTo(File(evidence,"hdr.mp4"),true)
                    main { capture.setHdr(false) }
                }
            }
            test("missing microphone samples pause the take and permit a checked input restart") {
                waitFor("SDR camera did not return") { capture.readyToRecord && microphone.readings.arriving(System.nanoTime()) }
                val before=a.editor.project.clips.size
                main { capture.start() }
                waitFor("SDR recorder did not start") { capture.recording && !capture.paused }
                Thread.sleep(600)
                main { microphone.stop() }
                waitFor("Missing audio did not pause recording",4.0) { capture.recording && capture.paused }
                val paused=capture.elapsedSeconds()
                Thread.sleep(200)
                check(abs(capture.elapsedSeconds()-paused)<.02)
                main { capture.resumeCamera() }
                waitFor("Microphone did not restart") { microphone.running && microphone.readings.arriving(System.nanoTime()) }
                main { capture.pause() }
                Thread.sleep(600)
                check(capture.recording && !capture.paused)
                main { capture.stop() }
                check(a.editor.project.clips.size==before+1)
                check(pcm(File(a.editor.project.clips.last().path)).size>48000)
            }
            test("one-minute independent audio stays aligned with SDR video") {
                waitFor("Camera did not become ready") { capture.readyToRecord && microphone.readings.arriving(System.nanoTime()) }
                val before=a.editor.project.clips.size
                val session=microphone.sessionId
                main { capture.start() }
                waitFor("SDR recorder did not start") { capture.recording && !capture.paused }
                val recorder=field<CaptureRecorder>("recorder")
                repeat(60) {
                    Thread.sleep(1000)
                    check(capture.recording && !capture.paused && microphone.sessionId==session)
                }
                val active=capture.elapsedSeconds()
                main { capture.stop() }
                check(a.editor.project.clips.size==before+1)
                val clip=a.editor.project.clips.last()
                check(abs(clip.duration-active)<.2) { "Movie ${clip.duration}, active $active" }
                check(recorder.insertedAudioSilence<9600) { "Unexpected silence ${recorder.insertedAudioSilence}" }
                File(clip.path).copyTo(File(evidence,"minute.mp4"),true)
                File(evidence,"minute.txt").writeText("active=$active duration=${clip.duration} insertedSilence=${recorder.insertedAudioSilence} corrections=${recorder.audioClockCorrections}\n")
            }
        } finally {
            main { if (capture.recording) capture.stop(); capture.suspendCamera() }
            scratch.deleteRecursively()
        }
    }
}
