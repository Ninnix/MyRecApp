package org.myrec.app

import android.annotation.SuppressLint
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.util.Range
import android.util.Size
import java.io.Closeable

@SuppressLint("MissingPermission")
class DualCamera(
    private val manager: CameraManager,
    private val handler: Handler,
    private val renderer: CameraComposition,
    private val ids: List<String>,
    private val hdr: Boolean = false,
    private val primaries: (String) -> Int = { 0 },
    private val ready: () -> Unit,
    private val failure: (String) -> Unit,
) : Closeable {
    private val devices = arrayOfNulls<CameraDevice>(2)
    private val sessions = arrayOfNulls<CameraCaptureSession>(2)
    private val requests = arrayOfNulls<CaptureRequest.Builder>(2)
    @Volatile private var closed = false
    private var configured = 0
    private val focusLocks = BooleanArray(2)
    var inputSizes = emptyList<Size>()
        private set

    init {
        try {
            ids.forEachIndexed { index, id ->
                manager.openCamera(
                    id,
                    object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            if (closed) {
                                camera.close()
                                return
                            }
                            devices[index] = camera
                            // Open both devices before configuring either session, as required by
                            // Camera2.
                            if (devices.all { it != null }) configure()
                        }

                        override fun onDisconnected(camera: CameraDevice) {
                            camera.close()
                            fail("Camera disconnected")
                        }

                        override fun onError(camera: CameraDevice, error: Int) {
                            camera.close()
                            fail("Dual camera unavailable ($error)")
                        }
                    },
                    handler,
                )
            }
        } catch (e: Exception) {
            fail(e.message ?: "Cannot open both cameras")
        }
    }

    private fun fail(message: String) {
        if (closed) return
        close()
        failure(message)
    }

    private fun configure() {
        try {
            val characteristics = ids.map { manager.getCameraCharacteristics(it) }
            val supported =
                characteristics.map {
                    it.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        ?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
                        ?.toList()
                        .orEmpty()
                }
            var surfaces = emptyList<android.view.Surface>()
            var selected = emptyList<Size>()
            val candidates = listOf(Size(1920, 1080), Size(1280, 720), Size(640, 480))
            // Individual lens maxima do not establish a concurrent stream combination.
            for (size in candidates) {
                if (!supported.all { size in it }) continue
                renderer.clearInputs()
                val inputs =
                    characteristics.mapIndexed { index, chars ->
                        renderer.input(
                            size.width,
                            size.height,
                            chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
                            chars.get(CameraCharacteristics.LENS_FACING) ==
                                CameraCharacteristics.LENS_FACING_FRONT,
                            primaries(ids[index]),
                            ids[index],
                            android.os.Build.VERSION.SDK_INT<33 && chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)==CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
                        )
                    }
                val configs =
                    ids.indices.associate { index ->
                        val output = OutputConfiguration(inputs[index])
                        if (hdr && Build.VERSION.SDK_INT >= 33)
                            output.setDynamicRangeProfile(
                                android.hardware.camera2.params.DynamicRangeProfiles.HLG10
                            )
                        ids[index] to
                            SessionConfiguration(
                                    SessionConfiguration.SESSION_REGULAR,
                                    listOf(output),
                                    { handler.post(it) },
                                    object : CameraCaptureSession.StateCallback() {
                                        override fun onConfigured(session: CameraCaptureSession) {}

                                        override fun onConfigureFailed(
                                            session: CameraCaptureSession
                                        ) {}
                                    },
                                )
                                .apply {
                                    if (hdr) HdrRecorder.colorSpace(this, characteristics[index])
                                }
                    }
                if (
                    Build.VERSION.SDK_INT >= 30 &&
                        manager.isConcurrentSessionConfigurationSupported(configs)
                ) {
                    surfaces = inputs
                    selected = listOf(size, size)
                    break
                }
            }
            check(surfaces.size == 2) {
                "This camera combination does not support the selected dynamic range"
            }
            inputSizes = selected
            devices.forEachIndexed { index, device ->
                val camera = device!!
                val chars = characteristics[index]
                val surface = surfaces[index]
                requests[index] =
                    camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(surface)
                    }
                val output = OutputConfiguration(surface)
                if (hdr && Build.VERSION.SDK_INT >= 33)
                    output.setDynamicRangeProfile(
                        android.hardware.camera2.params.DynamicRangeProfiles.HLG10
                    )
                if (Build.VERSION.SDK_INT >= 33) {
                    output.setMirrorMode(OutputConfiguration.MIRROR_MODE_NONE)
                    output.setTimestampBase(OutputConfiguration.TIMESTAMP_BASE_MONOTONIC)
                }
                camera.createCaptureSession(
                    SessionConfiguration(
                            SessionConfiguration.SESSION_REGULAR,
                            listOf(output),
                            { handler.post(it) },
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    if (closed) {
                                        session.close()
                                        return
                                    }
                                    sessions[index] = session
                                    update(1f, false, false, 0)
                                    configured++
                                    if (configured == 2) ready()
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    fail("This camera combination cannot record together")
                                }
                            },
                        )
                        .apply { if (hdr) HdrRecorder.colorSpace(this, chars) }
                )
            }
        } catch (e: Exception) {
            fail(e.message ?: "Cannot configure both cameras")
        }
    }

    fun update(zoom: Float, focusLocked: Boolean, torch: Boolean, primary: Int,follow: Boolean=false,stabilize: Boolean=true) {
        if (android.os.Looper.myLooper() != handler.looper) {
            handler.post { update(zoom, focusLocked, torch, primary,follow,stabilize) }
            return
        }
        if (closed) return
        try {
            requests.forEachIndexed { index, r ->
                if (r == null || sessions[index] == null) return@forEachIndexed
                val chars = manager.getCameraCharacteristics(ids[index])
                val modes =
                    chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf(0)
                val lock =
                    focusLocked &&
                        index == primary &&
                        modes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)
                r.set(
                    CaptureRequest.CONTROL_AF_MODE,
                    if (lock) CaptureRequest.CONTROL_AF_MODE_AUTO else CameraFocus.mode(modes),
                )
                if (lock != focusLocks[index]) {
                    r.set(
                        CaptureRequest.CONTROL_AF_TRIGGER,
                        if (lock) CaptureRequest.CONTROL_AF_TRIGGER_START
                        else CaptureRequest.CONTROL_AF_TRIGGER_CANCEL,
                    )
                    sessions[index]!!.capture(r.build(), null, handler)
                    focusLocks[index] = lock
                }
                r.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                r.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE,if (follow) CameraFaces.mode(chars) else CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF)
                val videoModes=chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: intArrayOf()
                r.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,if (stabilize && CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON in videoModes) CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                if (Build.VERSION.SDK_INT >= 30)
                    chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let {
                        r.set(
                            CaptureRequest.CONTROL_ZOOM_RATIO,
                            (if (index == primary) zoom else 1f).coerceIn(it.lower, it.upper),
                        )
                    }
                r.set(
                    CaptureRequest.FLASH_MODE,
                    if (
                        torch &&
                            index == primary &&
                            chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    )
                        CaptureRequest.FLASH_MODE_TORCH
                    else CaptureRequest.FLASH_MODE_OFF,
                )
                chars
                    .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    ?.firstOrNull { it == Range(30, 30) }
                    ?.let { r.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                sessions[index]!!.setRepeatingRequest(r.build(),object: CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(session: CameraCaptureSession,request: CaptureRequest,result: TotalCaptureResult) {
                        if (!closed && follow) {
                            val size=inputSizes.getOrNull(index) ?: return
                            renderer.faces(ids[index],CameraFaces.boxes(chars,result,size.width,size.height),CameraFaces.time(chars,result),CameraFaces.zoom(chars,result))
                        }
                    }
                },handler)
                r.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            }
        } catch (e: Exception) {
            fail(e.message ?: "Cannot update dual camera")
        }
    }

    override fun close() {
        closed = true
        sessions.forEach { it?.close() }
        devices.forEach { it?.close() }
        sessions.fill(null)
        devices.fill(null)
    }

    companion object {
        fun pairs(manager: CameraManager): List<List<String>> {
            if (Build.VERSION.SDK_INT<30) return emptyList()
            val result=mutableListOf<List<String>>()
            for (set in manager.concurrentCameraIds) {
                val ids=set.sorted()
                for (i in ids.indices) for (j in i+1 until ids.size) result.add(listOf(ids[i],ids[j]))
            }
            return result.distinct().sortedBy { pair ->
                val facings=pair.map { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) }
                if (CameraCharacteristics.LENS_FACING_FRONT in facings && CameraCharacteristics.LENS_FACING_BACK in facings) 0 else 1
            }
        }
        fun pair(manager: CameraManager): List<String>? {
            val pair=pairs(manager).firstOrNull() ?: return null
            return pair.sortedBy { if (manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING)==CameraCharacteristics.LENS_FACING_FRONT) 1 else 0 }
        }
    }
}
