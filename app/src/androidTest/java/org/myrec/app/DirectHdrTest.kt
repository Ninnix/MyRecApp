package org.myrec.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.hardware.camera2.*
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object DirectHdrTest {
    @SuppressLint("MissingPermission")
    fun run(activity: MainActivity, folder: File) {
        val manager = activity.getSystemService(CameraManager::class.java)
        val id =
            manager.cameraIdList.first {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            }
        val chars = manager.getCameraCharacteristics(id)
        val microphone =
            activity
                .getSystemService(AudioManager::class.java)
                .getDevices(AudioManager.GET_DEVICES_INPUTS)
                .first { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        val thread = HandlerThread("direct-hdr-test").apply { start() }
        val handler = Handler(thread.looper)
        val ready = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        val target = File(folder, "direct-hdr.mp4")
        val rotation =
            CameraOrientation.recording(
                chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
                activity.windowManager.defaultDisplay.rotation * 90,
                false,
            )
        val recorder =
            HdrRecorder(
                activity,
                target,
                1920,
                1080,
                30,
                rotation,
                microphone,
                MediaRecorder.AudioSource.CAMCORDER,
                1,
            ) {
                failure.set(it)
            }
        try {
            manager.openCamera(
                id,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        device = camera
                        try {
                            val output =
                                OutputConfiguration(recorder.surface).apply {
                                    setDynamicRangeProfile(DynamicRangeProfiles.HLG10)
                                    setMirrorMode(OutputConfiguration.MIRROR_MODE_NONE)
                                    setTimestampBase(OutputConfiguration.TIMESTAMP_BASE_MONOTONIC)
                                }
                            val config =
                                SessionConfiguration(
                                    SessionConfiguration.SESSION_REGULAR,
                                    listOf(output),
                                    { handler.post(it) },
                                    object : CameraCaptureSession.StateCallback() {
                                        override fun onConfigured(value: CameraCaptureSession) {
                                            session = value
                                            try {
                                                recorder.start()
                                                val request =
                                                    camera
                                                        .createCaptureRequest(
                                                            CameraDevice.TEMPLATE_RECORD
                                                        )
                                                        .apply {
                                                            addTarget(recorder.surface)
                                                            set(
                                                                CaptureRequest.CONTROL_MODE,
                                                                CaptureRequest.CONTROL_MODE_AUTO,
                                                            )
                                                            set(
                                                                CaptureRequest.CONTROL_AWB_MODE,
                                                                CaptureRequest.CONTROL_AWB_MODE_AUTO,
                                                            )
                                                            set(
                                                                CaptureRequest
                                                                    .CONTROL_AE_TARGET_FPS_RANGE,
                                                                Range(30, 30),
                                                            )
                                                        }
                                                value.setRepeatingRequest(
                                                    request.build(),
                                                    null,
                                                    handler,
                                                )
                                            } catch (e: Throwable) {
                                                failure.set(e)
                                            }
                                            ready.countDown()
                                        }

                                        override fun onConfigureFailed(
                                            value: CameraCaptureSession
                                        ) {
                                            failure.set(
                                                IllegalStateException("Direct HLG unavailable")
                                            )
                                            ready.countDown()
                                        }
                                    },
                                )
                            HdrRecorder.colorSpace(config, chars)
                            camera.createCaptureSession(config)
                        } catch (e: Throwable) {
                            failure.set(e)
                            ready.countDown()
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        failure.set(IllegalStateException("Camera disconnected"))
                        ready.countDown()
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        failure.set(IllegalStateException("Camera error $error"))
                        ready.countDown()
                    }
                },
                handler,
            )
            check(ready.await(10, TimeUnit.SECONDS))
            failure.get()?.let { throw it }
            Thread.sleep(2800)
            session?.stopRepeating()
            session?.close()
            session = null
            device?.close()
            device = null
            recorder.stop()
            VideoDecoder(target.path).use { decoder ->
                val frame = decoder.frame(1.5, 360, 640) ?: error("Direct HDR frame missing")
                File(folder, "direct-hdr-editor.png").outputStream().use {
                    frame.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                frame.recycle()
            }
        } finally {
            session?.close()
            device?.close()
            recorder.close()
            thread.quitSafely()
        }
    }
}
