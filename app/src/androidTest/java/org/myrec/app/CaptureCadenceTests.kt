package org.myrec.app

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

object CaptureCadenceTests {
    private fun timing(times: List<Long>): JSONObject {
        val gaps=times.zipWithNext { a,b -> (b-a)/1000.0 }
        check(gaps.isNotEmpty() && gaps.all { it>0 })
        val ordered=gaps.sorted()
        return JSONObject().put("frames",times.size).put("fps",1000/gaps.average())
            .put("missing",gaps.sumOf { (it*30/1000).roundToInt().minus(1).coerceAtLeast(0) })
            .put("p95_ms",ordered[(ordered.lastIndex*.95).roundToInt()]).put("max_ms",ordered.last())
    }

    fun run(a: MainActivity, main: (() -> Unit) -> Unit, test: (String, () -> Unit) -> Unit) {
        val manager=a.getSystemService(CameraManager::class.java)
        fun lens(facing: Int)=manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING)==facing }
        val rear=checkNotNull(lens(CameraCharacteristics.LENS_FACING_BACK))
        val front=lens(CameraCharacteristics.LENS_FACING_FRONT)
        val output=File(a.getExternalFilesDir(null),"capture-cadence.json")
        val report=JSONObject().put("device",android.os.Build.MODEL).put("nominal_fps",30)
        val sensors=mutableMapOf<String,MutableList<Long>>()
        val previewTimes=mutableListOf<Long>()
        var measuring=false
        lateinit var c: Capture
        fun waitFor(message: String, ready: () -> Boolean) {
            val end=SystemClock.elapsedRealtime()+25000
            while (SystemClock.elapsedRealtime()<end) {
                var done=false
                main { done=ready() }
                if (done) return
                Thread.sleep(80)
            }
            error("$message. ${a.lastMessage}")
        }
        main {
            a.captureSettings.edit().putInt("fps",30).apply()
            a.editor.load(Project(name="Camera cadence")); a.showCapture()
            c=(0 until a.root.childCount).map(a.root::getChildAt).filterIsInstance<Capture>().single()
            c.previewTimeObserver={ ns -> if (measuring) previewTimes.add(ns/1000) }
            c.sensorTimeObserver={ id,ns -> synchronized(sensors) { if (measuring) sensors.getOrPut(id){mutableListOf()}.add(ns) } }
        }
        try {
            waitFor("No camera preview") { c.readyToRecord }
            for (mode in listOf("SDR","Highlight-protected SDR","Stabilize","Level horizon","HDR","HDR with horizon",
                "Dual","Dual HDR","Switch camera","Front stabilize","Front HDR","Software HDR","Front Software HDR","Dual Software HDR")) {
                test("$mode camera capability and saved cadence") {
                    val software="Software HDR" in mode
                    val hdr="HDR" in mode && !software
                    val dual=mode.startsWith("Dual")
                    val id=if (mode.startsWith("Front")) front else rear
                    if (id==null || hdr && !HdrRecorder.supported(manager,id) ||
                        software && (HdrRecorder.supported(manager,id) || SoftwareHdr.exposureSteps(manager.getCameraCharacteristics(id))==0) ||
                        mode=="Switch camera" && front==null) {
                        report.put(mode,"Unavailable"); output.writeText(report.toString(2)); return@test
                    }
                    main { c.setDualLayout(4); c.setHdr(false) }
                    waitFor("Single camera did not reopen") { c.readyToRecord }
                    main {
                        a.captureSettings.edit().putBoolean("protectHighlights",mode=="Highlight-protected SDR").apply()
                        c.switchCamera(id,1f)
                    }
                    waitFor("Selected camera did not open") { c.readyToRecord && c.latestResult!=null }
                    main {
                        c.setStabilization(mode in listOf("Stabilize","Front stabilize","HDR with horizon"))
                        c.setHorizonEnabled(mode=="Level horizon" || mode=="HDR with horizon")
                        if (hdr || software) c.setHdr(true)
                        if (dual) c.setDualLayout(0)
                    }
                    waitFor("Camera setup did not settle") { c.readyToRecord }
                    if (dual && c.concurrentInputSizes().size!=2) {
                        check(c.latestResult!=null || c.readyToRecord)
                        report.put(mode,"Single camera restored. ${a.lastMessage}"); output.writeText(report.toString(2)); return@test
                    }
                    if (software) check(c.softwareHdr) { "Software HDR could not be enabled: ${a.lastMessage}" }
                    val before=a.editor.project.clips.size
                    waitFor("Recording did not start") { if (!c.recording) c.start(); c.recording && c.elapsedSeconds()>.7 }
                    val exposure=c.latestResult?.get(android.hardware.camera2.CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION)
                    if (software && !dual) check(exposure==SoftwareHdr.exposureSteps(manager.getCameraCharacteristics(id)))
                    var begin=0.0; var end=0.0
                    main { begin=c.elapsedSeconds(); previewTimes.clear(); synchronized(sensors) { sensors.clear(); measuring=true } }
                    if (mode=="Switch camera") {
                        Thread.sleep(3500); main { c.switchCamera(front!!) }; Thread.sleep(4500)
                    } else Thread.sleep(8000)
                    main {
                        end=c.elapsedSeconds(); synchronized(sensors) { measuring=false }
                        check(c.recording && !c.paused) { a.lastMessage }; c.stop()
                    }
                    waitFor("Take did not finish saving") { !c.finalizing && a.editor.project.clips.size==before+1 }
                    val clip=a.editor.project.clips.last()
                    val expectedEditor=mode=="Front stabilize" && CameraStabilization.mode(manager.getCameraCharacteristics(id))==CameraStabilization.Editor
                    if (expectedEditor) check(clip.effects.any(StabilizationSettings::modern))
                    val extractor=MediaExtractor()
                    try {
                        extractor.setDataSource(clip.path)
                        val video=(0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true }
                        val format=extractor.getTrackFormat(video)
                        val hlg=format.containsKey(MediaFormat.KEY_COLOR_TRANSFER) && format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)==MediaFormat.COLOR_TRANSFER_HLG
                        check(hlg==hdr) { "$mode produced $format" }
                        if (software) {
                            check(format.getString(MediaFormat.KEY_MIME)==MediaFormat.MIMETYPE_VIDEO_AVC)
                            VideoDecoder(clip.path).use { decoder ->
                                checkNotNull(decoder.frame(1.0,160,90)).recycle()
                            }
                        }
                        val times=mutableListOf<Long>()
                        extractor.selectTrack(video)
                        while (extractor.sampleTime>=0) {
                            if (extractor.sampleTime/1e6 in begin..end) times.add(extractor.sampleTime)
                            if (!extractor.advance()) break
                        }
                        val byLens=JSONObject()
                        synchronized(sensors) { for ((camera,ns) in sensors) byLens.put(camera,timing(ns.map { it/1000 })) }
                        check(byLens.length()==if (dual || mode=="Switch camera") 2 else 1)
                        val result=JSONObject().put("sensor",byLens).put("file",timing(times)).put("HLG",hlg)
                            .put("width",format.getInteger(MediaFormat.KEY_WIDTH)).put("height",format.getInteger(MediaFormat.KEY_HEIGHT))
                            .put("preview",if (previewTimes.size>=2) timing(previewTimes) else JSONObject().put("frames",previewTimes.size))
                            .put("editor_stabilization",expectedEditor).put("exposure_compensation_steps",exposure)
                        report.put(mode,result); output.writeText(report.toString(2))
                        if (mode!="Switch camera") {
                            val cameraLoss=byLens.keys().asSequence().sumOf { byLens.getJSONObject(it).getInt("missing") }
                            check(result.getJSONObject("file").getInt("missing")<=cameraLoss+3) { "$mode added frame gaps beyond the camera input: $result" }
                        }
                    } finally { extractor.release() }
                }
            }
        } finally { main { c.previewTimeObserver=null; c.sensorTimeObserver=null; if (c.recording) c.stop(); c.suspendCamera() } }
    }
}
