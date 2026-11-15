package org.myrec.app

import android.app.Instrumentation
import android.content.Context
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
        val prefs = a.captureSettings
        val hadPreview = prefs.contains("mirrorFrontPreview")
        val hadRecording = prefs.contains("mirrorFrontRecording")
        val oldPreview = prefs.getBoolean("mirrorFrontPreview", true)
        val oldRecording = prefs.getBoolean("mirrorFrontRecording", false)
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
            for ((previewMirrored, saveMirrored) in listOf(true to false, true to true, false to false, false to true))
            for ((orientation, name) in
                listOf(
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to "landscape",
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to "portrait",
                )) test("$name selfie preview=$previewMirrored recording=$saveMirrored retain independent framing and effects") {
                onMain {
                    capture.suspendCamera()
                    capture.setMirrorPreview(previewMirrored)
                    capture.setMirrorRecording(saveMirrored)
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
                onMain { capture.setMirrorRecording(!saveMirrored) }
                check(capture.mirrorRecording == saveMirrored) { "Saved mirroring changed during the take" }
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
                val luma = IntArray(reference.width * reference.height)
                reference.getPixels(luma, 0, reference.width, 0, 0, reference.width, reference.height)
                val average = luma.map { (Color.red(it) + Color.green(it) + Color.blue(it)) / 3.0 }.average()
                check(average > 12) { "Camera scene is too dark for an optical mirror comparison (mean=$average); this is not a mirror result" }
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
                        val different = previewMirrored != saveMirrored
                        val direct = cameraCorrelations(reference, if (different) flipped else recorded).first()
                        val mirrored = cameraCorrelations(reference, if (different) recorded else flipped).first()
                        File(folder, "$name-scores.txt")
                            .writeText("direct=$direct mirrored=$mirrored\n")
                        check(direct > .65 && direct > mirrored + .1) {
                            "Preview/recording mismatch: direct=$direct, mirrored=$mirrored"
                        }
                        val effect = Effect(kind = "Kaleidoscope")
                        var live = reference.copy(Bitmap.Config.ARGB_8888, true)
                        if (different) live = SourceMirror.flip(live)
                        Effects.correct(live, effect, 1f)
                        if (different) live = SourceMirror.flip(live)
                        val project = a.editor.project.duplicate()
                        project.clips.last().effects.add(effect)
                        Frames().use { frames ->
                            var edited =
                                frames.render(project, 1.0, reference.width, reference.height)
                            if (different) edited = SourceMirror.flip(edited)
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
                prefs.edit().apply {
                    if (hadPreview) putBoolean("mirrorFrontPreview", oldPreview) else remove("mirrorFrontPreview")
                    if (hadRecording) putBoolean("mirrorFrontRecording", oldRecording) else remove("mirrorFrontRecording")
                }.apply()
                a.showEditor()
            }
        }
    }
}
