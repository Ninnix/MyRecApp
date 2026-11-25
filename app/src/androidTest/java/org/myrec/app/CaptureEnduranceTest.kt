package org.myrec.app

import android.app.KeyguardManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

object CaptureEnduranceTest {
    fun run(a: MainActivity, onMain: (() -> Unit) -> Unit, test: (String, () -> Unit) -> Unit) {
        check(a.store.root.canonicalPath.startsWith(File(a.cacheDir,"test-projects").canonicalPath+File.separator))
        check(a.store.media.usableSpace >= 2L*1024*1024*1024) { "This recording check needs 2 GiB of working space" }
        onMain { a.editor.load(Project(name = "SDR endurance fixture")); a.showCapture() }
        val capture = (0 until a.root.childCount).map { a.root.getChildAt(it) }.filterIsInstance<Capture>().single()
        val power = a.getSystemService(PowerManager::class.java)
        val keyguard = a.getSystemService(KeyguardManager::class.java)
        val report = JSONObject().put("device",android.os.Build.MODEL).put("api",android.os.Build.VERSION.SDK_INT)
        val samples = JSONArray(); report.put("samples",samples)
        val output = File(a.getExternalFilesDir(null),"capture-endurance.json")
        fun publish() {
            val partial = File(output.parentFile,output.name+".partial"); partial.writeText(report.toString(2)); check(partial.renameTo(output))
        }
        try {
            val readyBy = SystemClock.uptimeMillis()+15000
            onMain { capture.setHdr(false) }
            while (SystemClock.uptimeMillis() < readyBy) {
                var recording = false
                onMain {
                    if (capture.readyToRecord && !capture.recording) capture.start()
                    recording = capture.recording
                }
                if (recording) break
                Thread.sleep(100)
            }
            onMain { check(capture.recording) { "Capture could not start. ${a.lastMessage}" } }
            Thread.sleep(1000)
            val started = SystemClock.elapsedRealtime(); var pausedFor = 0L
            test("eleven-minute SDR capture stays awake and responsive across pause and resume") {
                repeat(22) { index ->
                    Thread.sleep(30000)
                    var seconds = 0.0
                    var locked = false
                    onMain {
                        check(capture.recording && !capture.paused) { "Recording stopped or paused at sample $index. ${a.lastMessage}" }
                        locked = keyguard.isKeyguardLocked
                        check(power.isInteractive && (!locked || a.hasWindowFocus())) { "Recording UI was obscured at sample $index" }
                        seconds = capture.elapsedSeconds()
                    }
                    val sample = JSONObject().put("wall_ms",SystemClock.elapsedRealtime()-started).put("recording_seconds",seconds)
                        .put("pss_kib",Debug.getPss()).put("awake",true).put("locked",locked)
                        .put("thermal_status",MediaThermalGate.currentStatus())
                    samples.put(sample); publish()
                    if (index == 9) {
                        var held = 0.0
                        onMain { capture.addMark(); capture.pause(); check(capture.paused); held = capture.elapsedSeconds() }
                        val before = SystemClock.elapsedRealtime(); Thread.sleep(3000)
                        onMain {
                            check(capture.paused && abs(capture.elapsedSeconds()-held) < .05)
                            capture.pause(); check(!capture.paused)
                        }
                        pausedFor += SystemClock.elapsedRealtime()-before
                    }
                }
                val wall = (SystemClock.elapsedRealtime()-started-pausedFor)/1000.0
                var measured = 0.0; onMain { measured = capture.elapsedSeconds(); capture.stop() }
                check(abs(measured-wall-1.0) < .6) { "Recording clock $measured differs from active wall time $wall" }
                val warm = (3 until samples.length()).map { samples.getJSONObject(it).getInt("pss_kib") }
                check(warm.last()-warm.min() < 128*1024) { "Unbounded memory growth during the take" }
                report.put("active_wall_seconds",wall).put("recording_clock_seconds",measured)
                publish()
            }
            val saveBy=SystemClock.elapsedRealtime()+180000
            while (true) {
                var saving=false
                onMain { saving=capture.finalizing }
                if (!saving) break
                check(SystemClock.elapsedRealtime()<saveBy) { "Saving the long recording timed out" }
                Thread.sleep(150)
            }
            val clip = a.editor.project.clips.single(); val file = File(clip.path)
            test("long SDR take has continuous presentation timestamps bounded AV drift and decodable endpoints") {
                check(file.isFile && clip.duration > 650 && clip.marks.size == 1)
                val tracks = JSONArray(); val ends = mutableMapOf<String,Long>()
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.path)
                    for (index in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(index); val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                        if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                        extractor.selectTrack(index); extractor.seekTo(0,MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        val times = mutableListOf<Long>()
                        while (extractor.sampleTime >= 0) {
                            times.add(extractor.sampleTime)
                            if (!extractor.advance()) break
                        }
                        extractor.unselectTrack(index)
                        val ordered = times.sorted()
                        check(ordered.size == ordered.distinct().size) { "$mime repeats presentation timestamps" }
                        val maximumGap = ordered.zipWithNext().maxOf { (a,b) -> b-a }
                        check(times.size > if (mime.startsWith("video/")) 15000 else 20000)
                        check(maximumGap < 500000) { "$mime has a long pause/resume gap" }
                        ends[if (mime.startsWith("video/")) "video" else "audio"] = ordered.last()
                        tracks.put(JSONObject().put("mime",mime).put("first_pts_us",ordered.first()).put("last_pts_us",ordered.last())
                            .put("samples",times.size).put("maximum_gap_us",maximumGap)
                            .put("decode_order_pts_monotonic",times.zipWithNext().all { (a,b) -> b>a }).apply {
                                if (mime.startsWith("video/")) {
                                    put("width", format.getInteger(MediaFormat.KEY_WIDTH)); put("height", format.getInteger(MediaFormat.KEY_HEIGHT))
                                }
                            })
                    }
                } finally { extractor.release() }
                // API 29 exposes AAC priming and trailing padding in packet timestamps.
                // The MP4 track headers describe the edited presentation on both Android versions.
                val presented=Mp4Audio.trackDurations(file)
                val drift=(abs(presented.getValue("vide")-presented.getValue("soun"))*1e6).toLong()
                val packetDifference=abs(ends.getValue("video")-ends.getValue("audio"))
                report.put("raw_packet_end_difference_us",packetDifference).put("presented_seconds",JSONObject(presented))
                check(drift<200000 && packetDifference<500000) { "AV end drift is $drift us, raw packets differ by $packetDifference us" }
                VideoDecoder(file.path).use { decoder ->
                    for (time in listOf(.3,clip.sourceOut-.2)) (decoder.frame(time,160,90) ?: error("A long-take endpoint is unreadable")).recycle()
                }
                report.put("tracks",tracks).put("duration_seconds",clip.duration).put("bytes",file.length()).put("av_drift_us",drift)
                report.put("sha256",a.store.registerMedia(a.editor.project.id,file.path))
                report.put("complete",true); publish()
                check(file.delete()) { "Cannot remove the owned endurance footage" }
            }
        } catch (failure: Throwable) {
            report.put("failure",failure.toString()); publish(); throw failure
        } finally {
            onMain { if (capture.recording) capture.stop(); capture.suspendCamera() }
        }
    }
}
