package org.myrec.app

import android.app.Instrumentation
import android.content.pm.ActivityInfo
import android.graphics.*
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.view.TextureView
import java.io.File

object CameraPreviewTest {
    fun run(
        instrumentation: Instrumentation,
        a: MainActivity,
        onMain: (() -> Unit) -> Unit,
        test: (String, () -> Unit) -> Unit,
    ) {
        val folder = File(a.getExternalFilesDir(null), "camera-preview-checks").apply { mkdirs() }
        val manager = a.getSystemService(CameraManager::class.java)
        val front =
            manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_FRONT
            } ?: return
        fun save(bitmap: Bitmap, name: String) {
            File(folder, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        onMain { a.showCapture() }
        Thread.sleep(1000)
        val capture =
            (0 until a.root.childCount)
                .map { a.root.getChildAt(it) }
                .filterIsInstance<Capture>()
                .single()
        try {
            for ((orientation, name) in
                listOf(
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to "landscape",
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to "portrait",
                )) test("$name front-camera preview matches recording without a mirror flip") {
                onMain {
                    capture.suspendCamera()
                    a.requestedOrientation = orientation
                    a.editor.load(Project(name = "Camera preview check"))
                }
                Thread.sleep(600)
                onMain { capture.resumeCamera() }
                Thread.sleep(800)
                onMain { capture.switchCamera(front) }
                Thread.sleep(1100)
                onMain { capture.start() }
                Thread.sleep(1400)
                check(capture.recording) { "Camera did not start" }
                var preview: Bitmap? = null
                val bounds = Rect()
                onMain {
                    val texture =
                        (0 until capture.childCount)
                            .map { capture.getChildAt(it) }
                            .filterIsInstance<TextureView>()
                            .single()
                    preview =
                        capture.cameraFrame(
                            if (name == "portrait") 180 else 320,
                            if (name == "portrait") 320 else 180,
                        )
                    val matrix = FloatArray(9)
                    texture.getTransform(Matrix()).getValues(matrix)
                    val surface = FloatArray(16)
                    texture.surfaceTexture!!.getTransformMatrix(surface)
                    val location = IntArray(2)
                    texture.getLocationOnScreen(location)
                    bounds.set(
                        location[0],
                        location[1],
                        location[0] + texture.width,
                        location[1] + texture.height,
                    )
                    File(folder, "$name-transform.txt")
                        .writeText(
                            "rotation=${a.windowManager.defaultDisplay.rotation}, texture=${texture.width}x${texture.height}\n" +
                                "matrix=${matrix.toList()}\nsurface=${surface.toList()}\n"
                        )
                }
                instrumentation.uiAutomation.takeScreenshot()?.let { screen ->
                    val image =
                        Bitmap.createBitmap(
                            screen,
                            bounds.left,
                            bounds.top,
                            bounds.width(),
                            bounds.height(),
                        )
                    save(image, "$name-screen")
                    image.recycle()
                    screen.recycle()
                }
                Thread.sleep(600)
                onMain { capture.stop() }
                val reference = preview ?: error("Missing preview")
                val clip = a.editor.project.clips.last()
                VideoDecoder(clip.path).use { decoder ->
                    val recorded =
                        decoder.frame(1.0, reference.width, reference.height)
                            ?: error("Missing recorded frame")
                    val flipped =
                        Bitmap.createBitmap(
                            recorded,
                            0,
                            0,
                            recorded.width,
                            recorded.height,
                            Matrix().apply { setScale(-1f, 1f) },
                            true,
                        )
                    try {
                        save(reference, "$name-preview")
                        save(recorded, "$name-recorded")
                        val direct = cameraCorrelations(reference, recorded).first()
                        val mirrored = cameraCorrelations(reference, flipped).first()
                        File(folder, "$name-scores.txt")
                            .writeText("direct=$direct mirrored=$mirrored\n")
                        check(direct > .65 && direct > mirrored + .1) {
                            "Preview/recording mismatch: direct=$direct, mirrored=$mirrored"
                        }
                        val effect = Effect(kind = "Kaleidoscope")
                        val live = reference.copy(Bitmap.Config.ARGB_8888, true)
                        Effects.correct(live, effect, 1f)
                        val project = a.editor.project.duplicate()
                        project.clips.last().effects.add(effect)
                        Frames().use { frames ->
                            val edited =
                                frames.render(project, 1.0, reference.width, reference.height)
                            try {
                                save(live, "$name-kaleidoscope-preview")
                                save(edited, "$name-kaleidoscope-editor")
                                val score = cameraCorrelations(live, edited).first()
                                check(score > .65) {
                                    "Kaleidoscope preview/editor mismatch: $score"
                                }
                            } finally {
                                edited.recycle()
                            }
                        }
                        live.recycle()
                    } finally {
                        recorded.recycle()
                        flipped.recycle()
                        reference.recycle()
                    }
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
