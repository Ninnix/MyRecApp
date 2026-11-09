package org.yourec.app

import android.app.Instrumentation
import android.content.pm.ActivityInfo
import android.graphics.*
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

object RecordingRotationTest {
    private data class Sample(val time: Double, val image: Bitmap, val name: String)

    fun run(
        instrumentation: Instrumentation,
        a: MainActivity,
        onMain: (() -> Unit) -> Unit,
        test: (String, () -> Unit) -> Unit,
    ) {
        val folder = File(a.getExternalFilesDir(null), "live-rotation-checks").apply { mkdirs() }
        val manager = a.getSystemService(CameraManager::class.java)
        val front =
            manager.cameraIdList.first {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_FRONT
            }
        fun save(bitmap: Bitmap, name: String) {
            File(folder, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        onMain {
            check(a.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_FULL_USER)
            a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            a.showCapture()
        }
        Thread.sleep(1200)
        val capture =
            (0 until a.root.childCount)
                .map { a.root.getChildAt(it) }
                .filterIsInstance<Capture>()
                .single()
        onMain { capture.switchCamera(front) }
        Thread.sleep(1100)
        val modes = mutableListOf("SDR")
        if (HdrRecorder.supported(manager, front)) modes.add("HDR")
        if (DualCamera.pair(manager) != null) modes.addAll(listOf("Split", "Diagonal"))
        try {
            for (mode in modes) test("$mode capture and UI follow display rotation in one take") {
                onMain {
                    a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    a.editor.load(Project(name = "Live rotation check"))
                    if (mode == "HDR") capture.setHdr(true)
                    if (mode == "Split" || mode == "Diagonal") {
                        capture.setHdr(false)
                        capture.setDualLayout(if (mode == "Diagonal") 3 else 1)
                    }
                }
                Thread.sleep(1800)
                val deadline = android.os.SystemClock.uptimeMillis() + 7000
                while (!capture.recording && android.os.SystemClock.uptimeMillis() < deadline) {
                    onMain { capture.start() }
                    Thread.sleep(300)
                }
                check(capture.recording) { "$mode recording did not start" }
                check(a.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
                    "Recording replaced the user's orientation policy"
                }
                val samples = mutableListOf<Sample>()
                try {
                    for ((name, orientation) in
                        listOf(
                            "portrait" to ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                            "landscape" to ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                            "reverse-portrait" to ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
                            "reverse-landscape" to
                                ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
                            "portrait-return" to ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                        )) {
                        onMain { a.requestedOrientation = orientation }
                        Thread.sleep(1100)
                        onMain {
                            check(capture.recording && !capture.paused) {
                                "Capture interrupted at $name"
                            }
                            val landscape =
                                orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE ||
                                    orientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                            check((capture.width > capture.height) == landscape) {
                                "UI did not rotate at $name"
                            }
                            val before = android.os.SystemClock.elapsedRealtime() / 1000.0
                            val image =
                                capture.cameraFrame(
                                    if (landscape) 320 else 180,
                                    if (landscape) 180 else 320,
                                ) ?: error("Missing $name preview")
                            val after = android.os.SystemClock.elapsedRealtime() / 1000.0
                            samples.add(Sample((before + after) / 2, image, name))
                            save(image, "$mode-$name-preview")
                        }
                        Thread.sleep(600)
                    }
                    Thread.sleep(400)
                    var stopAt = 0.0
                    onMain {
                        stopAt = android.os.SystemClock.elapsedRealtime() / 1000.0
                        capture.stop()
                    }
                    check(a.editor.project.clips.size == 1) { "Rotation split or lost the take" }
                    val clip = a.editor.project.clips.single()
                    val scores =
                        StringBuilder(
                            "duration=${clip.duration} stop=$stopAt samples=${samples.map { it.time }}\n"
                        )
                    onMain { a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                    Thread.sleep(400)
                    onMain {
                        val image =
                            Bitmap.createBitmap(
                                a.root.width,
                                a.root.height,
                                Bitmap.Config.ARGB_8888,
                            )
                        a.root.draw(Canvas(image))
                        save(image, "$mode-landscape-controls")
                        image.recycle()
                    }
                    VideoDecoder(clip.path).use { decoder ->
                        for (sample in samples) {
                            val frame =
                                decoder.frame(
                                    (clip.duration - stopAt + sample.time).coerceAtLeast(0.0),
                                    360,
                                    640,
                                ) ?: error("Missing recorded frame")
                            val fit =
                                min(
                                    frame.width.toFloat() / sample.image.width,
                                    frame.height.toFloat() / sample.image.height,
                                )
                            val w =
                                (sample.image.width * fit).roundToInt().coerceAtMost(frame.width)
                            val h =
                                (sample.image.height * fit).roundToInt().coerceAtMost(frame.height)
                            val content =
                                Bitmap.createBitmap(
                                    frame,
                                    (frame.width - w) / 2,
                                    (frame.height - h) / 2,
                                    w,
                                    h,
                                )
                            try {
                                save(frame, "$mode-${sample.name}-recorded")
                                val correlations = cameraCorrelations(sample.image, content)
                                scores.append("${sample.name}: $correlations\n")
                                File(folder, "$mode-scores.txt").writeText(scores.toString())
                                check(correlations.first() > .65) {
                                    "$mode ${sample.name} preview/recording differ: $correlations"
                                }
                            } finally {
                                if (content !== frame) content.recycle()
                                frame.recycle()
                            }
                        }
                    }
                } finally {
                    onMain { if (capture.recording) capture.stop() }
                    samples.forEach { it.image.recycle() }
                }
            }
        } finally {
            onMain {
                capture.suspendCamera()
                a.showEditor()
            }
        }
    }
}
