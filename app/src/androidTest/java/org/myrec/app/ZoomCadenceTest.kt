package org.myrec.app

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureResult
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

object ZoomCadenceTest {
    fun run(a: MainActivity, allowDrops: Boolean, main: (() -> Unit) -> Unit) {
        lateinit var capture: Capture
        val manager = a.getSystemService(CameraManager::class.java)
        val rear = CameraLenses.available(manager).first { lens -> lens.zoom == 1f &&
            manager.getCameraCharacteristics(lens.id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
        val characteristics = manager.getCameraCharacteristics(rear.id)
        val limit = if (Build.VERSION.SDK_INT >= 30) characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
        val sensor = JSONArray()
        val zooms = JSONArray()
        val cameraTimes = mutableListOf<Long>()
        fun sample(ms: Long) {
            val until = SystemClock.uptimeMillis() + ms
            while (SystemClock.uptimeMillis() < until) {
                val result = capture.latestResult
                val time = result?.get(CaptureResult.SENSOR_TIMESTAMP)
                if (time != null && time > (cameraTimes.lastOrNull() ?: 0L)) {
                    cameraTimes.add(time)
                    sensor.put(JSONObject().put("ns", time).put("zoom", CameraFaces.zoom(characteristics, result))
                        .put("physical", if (Build.VERSION.SDK_INT >= 28) result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) else null))
                }
                Thread.sleep(4)
            }
        }
        fun waitFor(ready: () -> Boolean) {
            val until = SystemClock.uptimeMillis() + 12000
            while (SystemClock.uptimeMillis() < until) {
                var done = false
                main { done = ready() }
                if (done) return
                Thread.sleep(90)
            }
            error("Camera or microphone was not ready. ${a.lastMessage}")
        }
        main {
            a.captureSettings.edit().putInt("fps", 30).putFloat("zoomSeconds", 1f).apply()
            a.editor.load(Project(name = "Zoom cadence fixture"))
            a.showCapture()
            capture = (0 until a.root.childCount).map { a.root.getChildAt(it) }.filterIsInstance<Capture>().single()
        }
        try {
            waitFor { capture.readyToRecord && capture.latestResult != null }
            main { capture.switchCamera(rear.id, 1f) }
            waitFor { capture.readyToRecord && capture.latestResult != null }
            waitFor { if (!capture.recording) capture.start(); capture.recording }
            sample(2900)
            for (preset in listOf(.7f, 1.75f, 3.5f, 1f, 3.5f, 1f)) {
                val desired = if (limit == null) preset.coerceAtLeast(1f) else preset.coerceIn(limit.lower, limit.upper)
                main {
                    zooms.put(JSONObject().put("at_seconds", capture.elapsedSeconds()).put("target", desired))
                    capture.setZoom(desired)
                }
                sample(1750)
            }
            sample(1650)
            main { check(capture.recording && !capture.paused) { a.lastMessage }; capture.stop() }
            val clip = a.editor.project.clips.single()
            val extractor = MediaExtractor()
            val pictureTimes = mutableListOf<Long>()
            try {
                extractor.setDataSource(clip.path)
                extractor.selectTrack((0 until extractor.trackCount).first { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                })
                while (extractor.sampleTime >= 0L) {
                    pictureTimes.add(extractor.sampleTime * 1000L)
                    if (!extractor.advance()) break
                }
            } finally { extractor.release() }
            fun lost(times: List<Long>) = times.zipWithNext().sumOf { (before, after) ->
                ((after - before) / (1_000_000_000.0 / 30)).roundToInt().minus(1).coerceAtLeast(0)
            }
            val missingCamera = lost(cameraTimes)
            val missingVideo = lost(pictureTimes)
            val report = JSONObject().put("camera", rear.id).put("duration", clip.duration).put("zoom_commands", zooms)
                .put("sensor", sensor).put("video_pts_ns", JSONArray(pictureTimes))
                .put("missing_camera_intervals", missingCamera).put("missing_video_intervals", missingVideo)
            File(a.getExternalFilesDir(null), "zoom-cadence.json").writeText(report.toString(2))
            check(cameraTimes.size > 260 && pictureTimes.size > 260)
            check((0 until sensor.length()).any { sensor.getJSONObject(it).getDouble("zoom") > 1.3 })
            if (!allowDrops) check(missingVideo <= missingCamera + 3) {
                "Camera had $missingCamera missing frame intervals, saved video had $missingVideo"
            }
        } finally { main { if (capture.recording) capture.stop(); capture.suspendCamera() } }
    }
}
