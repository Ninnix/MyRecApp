package org.yourec.app

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.display.DisplayManager
import android.media.*
import android.os.*
import android.util.Range
import android.util.Size
import android.view.*
import android.widget.*
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.*

@SuppressLint("MissingPermission")
class Capture(
    private val activity: MainActivity,
    private val store: ProjectStore,
    private val onClip: (Clip) -> Unit,
    private val onEdit: () -> Unit,
) : FrameLayout(activity) {
    private val texture = TextureView(activity)
    private val overlay = CaptureOverlay()
    private val manager = activity.getSystemService(CameraManager::class.java)
    private val displays = activity.getSystemService(DisplayManager::class.java)
    private var displayRotation = activity.windowManager.defaultDisplay.rotation
    private val displayListener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) {}

            override fun onDisplayRemoved(id: Int) {}

            override fun onDisplayChanged(id: Int) {
                val display = activity.windowManager.defaultDisplay
                if (id == display.displayId && display.rotation != displayRotation) {
                    displayRotation = display.rotation
                    if (!closed) transform()
                }
            }
        }
    private val audioManager = activity.getSystemService(AudioManager::class.java)
    private val handlerThread = HandlerThread("camera").apply { start() }
    private val cameraHandler = Handler(handlerThread.looper)
    private val ui = Handler(Looper.getMainLooper())
    private val prefs = activity.getSharedPreferences("capture", Context.MODE_PRIVATE)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var request: CaptureRequest.Builder? = null
    private var recorder: MediaRecorder? = null
    private var hdrRecorder: HdrRecorder? = null
    private var hdr = false
    private val hdrColor = HdrColor(activity)
    private var colorGeneration = 0
    private var matchingColor = false
    private var recordingSurface: CameraRecordingSurface? = null
    private var composition: CameraComposition? = null
    private var dualCamera: DualCamera? = null
    private var dualLayout = 4
    private var lastDualLayout = 0
    private var dualMenu = false
    private var dualSwapped = false
    private var dualReady = false
    private var recordingOrientation = 0
    private var recordingDisplayRotation = 0
    private var recordingFile: File? = null
    private var pendingStart = false
    private var previewSurface: Surface? = null
    private var cameraId = ""
    private var lenses = emptyList<CameraLens>()
    private var opening = false
    private var pendingSwitch: String? = null
    private var closed = false
    private var videoSize = Size(1920, 1080)
    private var captureSize = videoSize
    internal var front = false
        private set

    var recording = false
        private set

    var paused = false
        private set

    private var duration = 0L
    private var since = 0L
    private var amplitude = 0f
    private var meter: AudioRecord? = null
    private var meterGeneration = 0
    private val meterWorker = Executors.newSingleThreadExecutor()
    private var micId = prefs.getInt("mic", -1)
    private var communicationRoute = -1
    private var previousAudioMode = AudioManager.MODE_NORMAL
    internal var latestResult: TotalCaptureResult? = null
        private set

    private var focusLocked = false
    @Volatile private var focusMode = CaptureRequest.CONTROL_AF_MODE_OFF
    @Volatile
    internal var focusState: Int? = null
        private set

    private var focusTrigger = CaptureRequest.CONTROL_AF_TRIGGER_IDLE
    private var torch = false
    private var screenLight = false
    private var lightWidth = prefs.getFloat("lightWidth", .06f).coerceIn(.015f, .35f)
    private var previousBrightness: Float? = null
    private var zoom = 1f
    private var zoomRange = Range(1f, 8f)
    private var zoomSeconds = prefs.getFloat("zoomSeconds", 1f)
    private var zoomAnimator: ValueAnimator? = null
    private var zoomMenu = false
    private var liveEffects = mutableListOf<Effect>()
    private val effectHistory = mutableListOf<Effect>()
    private var effectSince = 0.0
    private var liveFrame: Bitmap? = null
    private var rendering = false
    private var effectGeneration = 0
    private val imageWorker = Executors.newSingleThreadExecutor()
    private var lastZoomTap = 0L
    private var lastZoom = 0f
    private val audioDevices =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                if (!recording) choosePriority()
                overlay.invalidate()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                if (removedDevices.any { it.id == micId }) {
                    if (recording && !paused) {
                        pause()
                        activity.message("No microphone available, recording paused.")
                    }
                    micId = -1
                    choosePriority()
                }
                overlay.invalidate()
            }
        }
    private val recordingCallback =
        object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(
                configs: MutableList<AudioRecordingConfiguration>
            ) {
                if (
                    Build.VERSION.SDK_INT >= 29 &&
                        recording &&
                        !paused &&
                        configs.any {
                            it.clientAudioSessionId ==
                                (recorder?.activeRecordingConfiguration
                                        ?: hdrRecorder?.configuration)
                                    ?.clientAudioSessionId && it.isClientSilenced
                        }
                ) {
                    pause()
                    activity.message("No microphone available, recording paused.")
                }
            }
        }
    private val ticker =
        object : Runnable {
            override fun run() {
                if (closed) return
                if (recording && !paused)
                    amplitude =
                        try {
                            (recorder?.maxAmplitude ?: hdrRecorder?.amplitude ?: 0) / 32767f
                        } catch (_: Exception) {
                            0f
                        }
                if (liveEffects.any { it.enabled } && texture.isAvailable && !rendering)
                    updateLiveFrame()
                overlay.invalidate()
                ui.postDelayed(this, 66)
            }
        }

    init {
        setBackgroundColor(Color.BLACK)
        addView(texture, LayoutParams(-1, -1, Gravity.CENTER))
        addView(overlay, LayoutParams(-1, -1))
        texture.surfaceTextureListener =
            object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) {
                    openDefault()
                }

                override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {
                    transform()
                }

                override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean {
                    closeCamera()
                    return true
                }

                override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
            }
        audioManager.registerAudioDeviceCallback(audioDevices, ui)
        audioManager.registerAudioRecordingCallback(recordingCallback, ui)
        displays.registerDisplayListener(displayListener, ui)
        choosePriority()
        ui.post(ticker)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = measuredWidth
        val h = measuredHeight
        val unit = min(w / 640f, h / 294f)
        val previewWidth = if (w > h) (498 * unit).roundToInt() else w
        val previewHeight = if (w > h) (280 * unit).roundToInt() else min(h, w * 16 / 9)
        texture.measure(
            MeasureSpec.makeMeasureSpec(previewWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(previewHeight, MeasureSpec.EXACTLY),
        )
    }

    fun resumeCamera() {
        closed = false
        updateLight()
        selectedMicrophone()?.let { routeMicrophone(it) }
        if (texture.isAvailable) openDefault()
        ui.removeCallbacks(ticker)
        ui.post(ticker)
    }

    private fun openDefault() {
        if (closed || device != null || opening || dualCamera != null) return
        if (dualLayout != 4) {
            openDual()
            return
        }
        if (
            activity.checkSelfPermission(Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED
        )
            return
        if (lenses.isEmpty()) lenses = CameraLenses.available(manager)
        val ids = manager.cameraIdList
        if (cameraId.isEmpty())
            cameraId =
                ids.firstOrNull {
                    manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_BACK
                } ?: ids.firstOrNull() ?: return
        open(cameraId)
    }

    private fun lensState(chars: CameraCharacteristics) {
        focusMode =
            CameraFocus.mode(
                chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                    ?: intArrayOf(CaptureRequest.CONTROL_AF_MODE_OFF)
            )
        if (!CameraFocus.canLock(focusMode)) focusLocked = false
        front =
            chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        zoomRange =
            if (Build.VERSION.SDK_INT >= 30)
                chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: Range(1f, 8f)
            else Range(1f, chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f)
        zoom = zoom.coerceIn(zoomRange.lower, zoomRange.upper)
        updateLight()
    }

    private fun open(id: String) {
        if (closed || opening) return
        if (recording && hdr && !HdrRecorder.supported(manager, id)) {
            ui.post { activity.message("This camera cannot record HDR") }
            return
        }
        opening = true
        cameraId = id
        try {
            val chars = manager.getCameraCharacteristics(id)
            lensState(chars)
            if (hdr && !HdrRecorder.supported(manager, id)) hdr = false
            val sizes =
                chars
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(MediaRecorder::class.java)
                    ?.toList() ?: listOf(Size(1920, 1080))
            val desired =
                if (prefs.getString("quality", "1080p") == "4K") Size(3840, 2160)
                else Size(1920, 1080)
            if (!recording)
                videoSize =
                    sizes.firstOrNull { it == desired }
                        ?: sizes.filter { it.width <= 1920 }.maxByOrNull { it.width * it.height }
                        ?: sizes.first()
            captureSize =
                sizes.firstOrNull { it == videoSize }
                    ?: sizes
                        .filter { it.width <= videoSize.width }
                        .maxByOrNull { it.width * it.height }
                    ?: sizes.first()
            manager.openCamera(
                id,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        opening = false
                        if (closed || pendingSwitch != null) {
                            camera.close()
                            pendingSwitch?.let {
                                pendingSwitch = null
                                open(it)
                            }
                            return
                        }
                        device = camera
                        createSession()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        opening = false
                        camera.close()
                        device = null
                        if (recording && !paused) {
                            ui.post {
                                pause()
                                activity.message("Camera disconnected. Recording paused.")
                            }
                        }
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        opening = false
                        camera.close()
                        device = null
                        ui.post { activity.message("Camera unavailable ($error)") }
                    }
                },
                cameraHandler,
            )
        } catch (e: Exception) {
            opening = false
            activity.message(e.message ?: "Cannot open camera")
        }
    }

    private fun createSession() {
        val camera = device ?: return
        val st = texture.surfaceTexture ?: return
        try {
            session?.close()
            previewSurface?.release()
            val surfaces =
                if (hdr) {
                    if (composition == null) composition = newComposition()
                    composition!!.clearInputs()
                    mutableListOf(
                        composition!!.input(
                            captureSize.width,
                            captureSize.height,
                            manager
                                .getCameraCharacteristics(cameraId)
                                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
                            front,
                            hdrColor.profile(cameraId),
                        )
                    )
                } else {
                    st.setDefaultBufferSize(captureSize.width, captureSize.height)
                    previewSurface = Surface(st)
                    mutableListOf(previewSurface!!)
                }
            recordingSurface?.let {
                val chars = manager.getCameraCharacteristics(cameraId)
                surfaces.add(
                    it.input(
                        captureSize.width,
                        captureSize.height,
                        CameraOrientation.correction(recordingOrientation, cameraOrientation()),
                        chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
                        monotonic = Build.VERSION.SDK_INT >= 33,
                        unmirror = front,
                        sensorOrientation =
                            chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
                        mirror = front,
                        outputRotation = recordingOrientation,
                        fitFrame = recordingTurned(),
                    )
                )
            }
            request =
                camera.createCaptureRequest(
                    if (recorder != null || hdr) CameraDevice.TEMPLATE_RECORD
                    else CameraDevice.TEMPLATE_PREVIEW
                )
            surfaces.forEach { request!!.addTarget(it) }
            val callback =
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (device !== camera) {
                            s.close()
                            return
                        }
                        session = s
                        focusTrigger =
                            if (focusLocked) CaptureRequest.CONTROL_AF_TRIGGER_START
                            else CaptureRequest.CONTROL_AF_TRIGGER_IDLE
                        updateRequest()
                        if (pendingStart) {
                            pendingStart = false
                            try {
                                recorder?.start()
                                hdrRecorder?.start()
                                since = SystemClock.elapsedRealtime()
                            } catch (e: Exception) {
                                ui.post {
                                    recording = false
                                    closeRecordingSurface()
                                    recorder?.release()
                                    recorder = null
                                    recordingFile?.delete()
                                    createSession()
                                    activity.message(e.message ?: "Cannot start recording")
                                }
                            }
                        }
                        ui.post {
                            transform()
                            if (!recording) startMeter()
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        ui.post {
                            if (hdr && !recording) {
                                closeCamera()
                                hdr = false
                                openDefault()
                            }
                            activity.message(
                                "This camera does not support the selected recording format"
                            )
                        }
                    }
                }
            if (Build.VERSION.SDK_INT >= 33) {
                val outputs =
                    surfaces.mapIndexed { index, surface ->
                        OutputConfiguration(surface).apply {
                            if (hdr)
                                setDynamicRangeProfile(
                                    android.hardware.camera2.params.DynamicRangeProfiles.HLG10
                                )
                            if (index > 0 || hdr) {
                                setMirrorMode(OutputConfiguration.MIRROR_MODE_NONE)
                                setTimestampBase(OutputConfiguration.TIMESTAMP_BASE_MONOTONIC)
                            }
                        }
                    }
                camera.createCaptureSession(
                    SessionConfiguration(
                            SessionConfiguration.SESSION_REGULAR,
                            outputs,
                            { command -> cameraHandler.post(command) },
                            callback,
                        )
                        .apply {
                            if (hdr)
                                HdrRecorder.colorSpace(
                                    this,
                                    manager.getCameraCharacteristics(cameraId),
                                )
                        }
                )
            } else camera.createCaptureSession(surfaces, callback, cameraHandler)
        } catch (e: Exception) {
            ui.post {
                if (hdr && !recording) {
                    closeCamera()
                    hdr = false
                    openDefault()
                }
                activity.message(e.message ?: "Cannot start camera preview")
            }
        }
    }

    private fun updateRequest() {
        dualCamera?.let {
            it.update(zoom, focusLocked, torch && !front, if (front) 1 else 0)
            return
        }
        if (Looper.myLooper() != cameraHandler.looper) {
            cameraHandler.post { updateRequest() }
            return
        }
        val r = request ?: return
        try {
            r.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            val fps = prefs.getInt("fps", 30)
            manager
                .getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.filter { it.contains(fps) }
                ?.minByOrNull { it.upper - it.lower }
                ?.let { r.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            r.set(CaptureRequest.CONTROL_AF_MODE, focusMode)
            r.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            r.set(CaptureRequest.CONTROL_AE_LOCK, false)
            r.set(
                CaptureRequest.FLASH_MODE,
                if (
                    torch &&
                        !front &&
                        manager
                            .getCameraCharacteristics(cameraId)
                            .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                )
                    CaptureRequest.FLASH_MODE_TORCH
                else CaptureRequest.FLASH_MODE_OFF,
            )
            r.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                if (prefs.getBoolean("stabilization", true))
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
            )
            if (Build.VERSION.SDK_INT >= 30) r.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
            else
                manager
                    .getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    ?.let { a ->
                        val w = (a.width() / zoom).toInt()
                        val h = (a.height() / zoom).toInt()
                        r.set(
                            CaptureRequest.SCALER_CROP_REGION,
                            Rect(
                                a.centerX() - w / 2,
                                a.centerY() - h / 2,
                                a.centerX() + w / 2,
                                a.centerY() + h / 2,
                            ),
                        )
                    }
            if (focusTrigger != CaptureRequest.CONTROL_AF_TRIGGER_IDLE) {
                r.set(CaptureRequest.CONTROL_AF_TRIGGER, focusTrigger)
                session?.capture(r.build(), null, cameraHandler)
                r.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                focusTrigger = CaptureRequest.CONTROL_AF_TRIGGER_IDLE
            }
            session?.setRepeatingRequest(
                r.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        latestResult = result
                        focusState = result.get(CaptureResult.CONTROL_AF_STATE)
                    }
                },
                cameraHandler,
            )
        } catch (e: Exception) {
            ui.post { activity.message(e.message ?: "Cannot update camera") }
        }
    }

    private fun transform() {
        composition?.let {
            texture.setTransform(Matrix())
            it.configure(
                dualLayout,
                dualSwapped,
                activity.windowManager.defaultDisplay.rotation * 90,
                texture.width,
                texture.height,
            )
            return
        }
        val w = texture.width.toFloat()
        val h = texture.height.toFloat()
        if (w == 0f || h == 0f) return
        val rotation = activity.windowManager.defaultDisplay.rotation
        val chars =
            try {
                manager.getCameraCharacteristics(cameraId)
            } catch (_: Exception) {
                return
            }
        val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        recordingSurface?.rotate(
            CameraOrientation.correction(recordingOrientation, cameraOrientation()),
            recordingTurned(),
        )
        val rotated = (sensor - rotation * 90 + 360) % 180 != 0
        val bufferW = if (rotated) captureSize.height.toFloat() else captureSize.width.toFloat()
        val bufferH = if (rotated) captureSize.width.toFloat() else captureSize.height.toFloat()
        val matrix = Matrix()
        val view = RectF(0f, 0f, w, h)
        val buffer = RectF(0f, 0f, bufferH, bufferW)
        buffer.offset(view.centerX() - buffer.centerX(), view.centerY() - buffer.centerY())
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            matrix.setRectToRect(view, buffer, Matrix.ScaleToFit.FILL)
            val scale = max(h / captureSize.height, w / captureSize.width)
            matrix.postScale(scale, scale, w / 2, h / 2)
            matrix.postRotate(90f * (rotation - 2), w / 2, h / 2)
        } else {
            val aspect = bufferW / bufferH
            val desired = w / h
            if (aspect > desired) matrix.setScale(aspect / desired, 1f, w / 2, h / 2)
            else matrix.setScale(1f, desired / aspect, w / 2, h / 2)
            if (rotation == Surface.ROTATION_180) matrix.postRotate(180f, w / 2, h / 2)
        }
        texture.setTransform(matrix)
    }

    private fun setZoom(value: Float, instant: Boolean = false) {
        val to = value.coerceIn(zoomRange.lower, zoomRange.upper)
        zoomAnimator?.cancel()
        if (instant || zoomSeconds == 0f) {
            zoom = to
            updateRequest()
            return
        }
        zoomAnimator =
            ValueAnimator.ofFloat(zoom, to).apply {
                duration = (zoomSeconds * 1000).toLong()
                addUpdateListener {
                    zoom = it.animatedValue as Float
                    updateRequest()
                    overlay.invalidate()
                }
                start()
            }
    }

    private fun choosePriority() {
        if (recording) return
        val devices = microphoneDevices()
        val priorities = prefs.getString("priorities", "")!!.split(',')
        val chosen =
            priorities.firstNotNullOfOrNull { key ->
                devices.firstOrNull {
                    val input = MicrophoneInput(it)
                    input.key == key || input.legacyKey == key
                }
            }
        if (chosen != null) micId = chosen.id
        else if (devices.none { it.id == micId })
            micId = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }?.id ?: -1
        prefs.edit().putInt("mic", micId).apply()
        if (device != null) startMeter()
    }

    internal fun microphoneDevices() =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).filter {
            MicrophoneInput(it).usable
        }

    private fun micKey(device: AudioDeviceInfo) = MicrophoneInput(device).key

    internal fun concurrentInputSizes() = dualCamera?.inputSizes.orEmpty()

    internal fun inputDataSpaces() = composition?.inputDataSpaces().orEmpty()

    internal fun controlBounds(id: String) = overlay.bounds(id)

    internal fun setTorch(enabled: Boolean) {
        torch = enabled
        updateLight()
        updateRequest()
        overlay.invalidate()
    }

    private fun updateLight(enabled: Boolean = torch && !closed && (front || dualLayout < 4)) {
        screenLight = enabled
        val window = activity.window
        val attributes = window.attributes
        if (screenLight) {
            if (previousBrightness == null) previousBrightness = attributes.screenBrightness
            attributes.screenBrightness = 1f
        } else {
            attributes.screenBrightness = previousBrightness ?: return
            previousBrightness = null
        }
        window.attributes = attributes
        overlay.invalidate()
    }

    internal fun insetBounds(): RectF? =
        composition?.insetBounds()?.let {
            RectF(
                texture.left + it.left * texture.width,
                texture.top + it.top * texture.height,
                texture.left + it.right * texture.width,
                texture.top + it.bottom * texture.height,
            )
        }

    private fun swapCameras() {
        val pair = DualCamera.pair(manager) ?: return
        switchCamera(pair[if (dualSwapped) 0 else 1])
    }

    internal val focusIsLocked
        get() = focusLocked

    private fun selectedMicrophone() = microphoneDevices().firstOrNull { it.id == micId }

    private fun bluetooth(device: AudioDeviceInfo) =
        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            Build.VERSION.SDK_INT >= 31 && device.type == AudioDeviceInfo.TYPE_BLE_HEADSET

    private fun releaseMicrophoneRoute() {
        if (communicationRoute < 0) return
        try {
            if (Build.VERSION.SDK_INT >= 31) audioManager.clearCommunicationDevice()
            else {
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
            }
            if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION)
                audioManager.mode = previousAudioMode
        } catch (_: Exception) {}
        communicationRoute = -1
    }

    private fun routeMicrophone(input: AudioDeviceInfo): Boolean {
        if (!bluetooth(input)) {
            releaseMicrophoneRoute()
            return true
        }
        if (communicationRoute == input.id) return true
        releaseMicrophoneRoute()
        previousAudioMode = audioManager.mode
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val devices =
                    audioManager.availableCommunicationDevices.filter { it.type == input.type }
                val output =
                    devices.firstOrNull { it.address == input.address && it.address.isNotEmpty() }
                        ?: devices.firstOrNull { it.productName == input.productName }
                        ?: devices.singleOrNull()
                        ?: return false
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                if (!audioManager.setCommunicationDevice(output)) {
                    audioManager.mode = previousAudioMode
                    return false
                }
            } else {
                if (!audioManager.isBluetoothScoAvailableOffCall) return false
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
            }
            communicationRoute = input.id
            return true
        } catch (_: Exception) {
            if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION)
                audioManager.mode = previousAudioMode
            return false
        }
    }

    private fun micName(): String =
        selectedMicrophone()?.let { MicrophoneInput(it).label } ?: "Default microphone"

    private fun startMeter() {
        stopMeter()
        if (
            recording ||
                activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED
        )
            return
        val selected = selectedMicrophone()
        if (selected != null && !routeMicrophone(selected)) return
        val ticket = ++meterGeneration
        meterWorker.execute {
            var input: AudioRecord? = null
            try {
                val size =
                    max(
                        4096,
                        AudioRecord.getMinBufferSize(
                            48000,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                        ),
                    )
                input =
                    AudioRecord.Builder()
                        .setAudioSource(
                            if (selected != null && bluetooth(selected))
                                MediaRecorder.AudioSource.VOICE_COMMUNICATION
                            else MediaRecorder.AudioSource.MIC
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setSampleRate(48000)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .build()
                        )
                        .setBufferSizeInBytes(size)
                        .build()
                if (ticket != meterGeneration) {
                    input.release()
                    return@execute
                }
                meter = input
                audioManager
                    .getDevices(AudioManager.GET_DEVICES_INPUTS)
                    .firstOrNull { it.id == micId }
                    ?.let { check(input.setPreferredDevice(it)) { "Microphone is unavailable" } }
                input.startRecording()
                val buffer = ShortArray(1024)
                while (ticket == meterGeneration && !recording && !closed) {
                    val n = input.read(buffer, 0, buffer.size)
                    if (n <= 0) break
                    var peak = 0
                    for (i in 0 until n) peak = max(peak, abs(buffer[i].toInt()))
                    amplitude = peak / 32768f
                }
            } catch (_: Exception) {} finally {
                try {
                    input?.stop()
                } catch (_: Exception) {}
                input?.release()
                if (meter === input) meter = null
            }
        }
    }

    private fun stopMeter() {
        meterGeneration++
        try {
            meter?.stop()
        } catch (_: Exception) {}
    }

    fun start() {
        if (recording) return
        if (matchingColor) {
            activity.message("Preparing HDR")
            return
        }
        if (device == null && !dualReady) {
            activity.message("Waiting for camera")
            return
        }
        val microphone = selectedMicrophone()
        if (microphone == null || !routeMicrophone(microphone)) {
            activity.message("The selected microphone is unavailable. Choose another microphone.")
            return
        }
        if (
            bluetooth(microphone) &&
                Build.VERSION.SDK_INT >= 31 &&
                audioManager.communicationDevice?.type != microphone.type
        ) {
            activity.message("Waiting for the selected microphone")
            return
        }
        stopMeter()
        if (hdr) {
            startHdr(microphone)
            return
        }
        session?.close()
        session = null
        val file = File(store.media, "recording-${System.currentTimeMillis()}.mp4")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(activity) else MediaRecorder()
        try {
            r.setAudioSource(
                if (bluetooth(microphone)) MediaRecorder.AudioSource.VOICE_COMMUNICATION
                else MediaRecorder.AudioSource.CAMCORDER
            )
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setOutputFile(file.absolutePath)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setVideoSize(videoSize.width, videoSize.height)
            r.setVideoFrameRate(prefs.getInt("fps", 30))
            r.setVideoEncodingBitRate(videoSize.width * videoSize.height * 8)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(48000)
            r.setAudioChannels(if (bluetooth(microphone)) 1 else 2)
            r.setAudioEncodingBitRate(192000)
            recordingDisplayRotation = activity.windowManager.defaultDisplay.rotation
            recordingOrientation = cameraOrientation()
            r.setOrientationHint(recordingOrientation)
            check(r.setPreferredDevice(microphone)) { "The selected microphone is unavailable" }
            r.setOnErrorListener { _, _, _ ->
                ui.post {
                    if (recording) {
                        stop()
                        activity.message("Recording interrupted. Completed footage has been kept.")
                    }
                }
            }
            r.prepare()
            if (composition != null) {
                composition!!.record(
                    r.surface,
                    videoSize.width,
                    videoSize.height,
                    recordingOrientation,
                )
            } else
                recordingSurface =
                    CameraRecordingSurface(r.surface, videoSize.width, videoSize.height) { error ->
                        ui.post {
                            if (recording) {
                                stop()
                                activity.message(error.message ?: "Camera frame processing failed")
                            }
                        }
                    }
            recorder = r
            recordingFile = file
            recording = true
            effectHistory.clear()
            effectSince = 0.0
            paused = false
            duration = 0
            since = SystemClock.elapsedRealtime()
            if (composition != null) {
                r.start()
                since = SystemClock.elapsedRealtime()
            } else {
                pendingStart = true
                createSession()
            }
        } catch (e: Exception) {
            closeRecordingSurface()
            r.release()
            file.delete()
            recorder = null
            recording = false
            createSession()
            activity.message(e.message ?: "Cannot start recording")
        }
        overlay.invalidate()
    }

    private fun startHdr(microphone: AudioDeviceInfo) {
        val file = File(store.media, "recording-${System.currentTimeMillis()}.mp4")
        try {
            val renderer = composition ?: error("Waiting for HDR camera")
            recordingDisplayRotation = activity.windowManager.defaultDisplay.rotation
            recordingOrientation = cameraOrientation()
            val r =
                HdrRecorder(
                    activity,
                    file,
                    videoSize.width,
                    videoSize.height,
                    prefs.getInt("fps", 30),
                    recordingOrientation,
                    microphone,
                    if (bluetooth(microphone)) MediaRecorder.AudioSource.VOICE_COMMUNICATION
                    else MediaRecorder.AudioSource.CAMCORDER,
                    if (bluetooth(microphone)) 1 else 2,
                ) { error ->
                    ui.post {
                        if (recording) stop()
                        activity.message(error.message ?: "HDR recording failed")
                    }
                }
            hdrRecorder = r
            renderer.record(
                r.surface,
                videoSize.width,
                videoSize.height,
                recordingOrientation,
                prefs.getInt("fps", 30),
            )
            r.start()
            recordingFile = file
            recording = true
            paused = false
            duration = 0
            effectHistory.clear()
            effectSince = 0.0
            since = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            composition?.record(null)
            hdrRecorder?.close()
            hdrRecorder = null
            file.delete()
            recording = false
            startMeter()
            activity.message(e.message ?: "Cannot start HDR recording")
        }
        overlay.invalidate()
    }

    internal fun setHdr(enabled: Boolean) {
        if (recording) {
            activity.message("Stop recording to change HDR")
            return
        }
        if (
            enabled &&
                (!HdrRecorder.supported(manager, cameraId) ||
                    (dualLayout != 4 &&
                        DualCamera.pair(manager)?.all { HdrRecorder.supported(manager, it) } !=
                            true))
        ) {
            activity.message("HDR recording is unavailable for this camera combination")
            return
        }
        val ids = if (dualCamera != null) DualCamera.pair(manager).orEmpty() else listOf(cameraId)
        val references =
            if (enabled && !hdr) {
                val w = 256
                val h = (w * texture.height / texture.width.coerceAtLeast(1)).coerceAtLeast(64)
                ids.indices.map { index ->
                    try {
                        if (composition != null) composition?.snapshot(index, w, h)
                        else cameraFrame(w, h)
                    } catch (_: Exception) {
                        null
                    }
                }
            } else emptyList()
        closeCamera()
        hdr = enabled
        try {
            openDefault()
        } catch (e: Exception) {
            hdr = false
            closeCamera()
            openDefault()
            activity.message(e.message ?: "HDR is unavailable on this graphics driver")
        }
        if (hdr && references.any { it != null }) {
            matchingColor = true
            matchHdrColor(ids, references, colorGeneration)
        } else references.forEach { it?.recycle() }
        overlay.invalidate()
    }

    private fun matchHdrColor(
        ids: List<String>,
        references: List<Bitmap?>,
        generation: Int,
        attempt: Int = 0,
    ) {
        ui.postDelayed(
            {
                if (generation != colorGeneration || !hdr || closed) {
                    references.forEach { it?.recycle() }
                    return@postDelayed
                }
                val renderer = composition
                val samples =
                    references.mapIndexed { index, reference ->
                        try {
                            reference?.let { renderer?.snapshot(index, it.width, it.height) }
                        } catch (_: Exception) {
                            null
                        }
                    }
                if (
                    renderer == null ||
                        samples.indices.any { references[it] != null && samples[it] == null }
                ) {
                    samples.forEach { it?.recycle() }
                    if (attempt < 20) matchHdrColor(ids, references, generation, attempt + 1)
                    else {
                        references.forEach { it?.recycle() }
                        matchingColor = false
                    }
                    return@postDelayed
                }
                imageWorker.execute {
                    val profiles =
                        references.indices.map { index ->
                            val reference = references[index]
                            val sample = samples[index]
                            try {
                                if (reference == null || sample == null) -1
                                else {
                                    val result = HdrColor.detect(reference, sample)
                                    android.util.Log.i(
                                        "YouRec-HDR",
                                        "Camera ${ids[index]} colour match: ${result.toList()}",
                                    )
                                    result[0].toInt()
                                }
                            } catch (e: Exception) {
                                android.util.Log.w(
                                    "YouRec-HDR",
                                    "Cannot compare camera colour profiles",
                                    e,
                                )
                                -1
                            } finally {
                                reference?.recycle()
                                sample?.recycle()
                            }
                        }
                    ui.post {
                        if (generation == colorGeneration && composition === renderer) {
                            profiles.forEachIndexed { index, profile ->
                                if (profile >= 0) {
                                    hdrColor.remember(ids[index], profile)
                                    renderer.primaries(index, profile)
                                }
                            }
                            matchingColor = false
                        }
                    }
                }
            },
            if (attempt == 0) 1500L else 200L,
        )
    }

    fun elapsedSeconds(): Double =
        (duration + if (recording && !paused) SystemClock.elapsedRealtime() - since else 0) / 1000.0

    private fun recordingTurned() =
        (activity.windowManager.defaultDisplay.rotation - recordingDisplayRotation) % 2 != 0

    private fun cameraOrientation(): Int {
        val sensor =
            manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION)
                ?: 90
        return CameraOrientation.recording(
            sensor,
            activity.windowManager.defaultDisplay.rotation * 90,
            front,
        )
    }

    private fun closeRecordingSurface() {
        composition?.record(null)
        val surface = recordingSurface
        recordingSurface = null
        // A stalled graphics driver must not prevent recorder cleanup.
        try {
            surface?.close()
        } catch (_: Exception) {}
    }

    fun pause() {
        if (!recording) return
        try {
            if (paused) {
                val input = selectedMicrophone()
                if (input == null || !routeMicrophone(input)) {
                    activity.message(
                        "The selected microphone is unavailable. Recording remains paused."
                    )
                    return
                }
                recorder?.resume()
                hdrRecorder?.resume()
                since = SystemClock.elapsedRealtime()
                paused = false
            } else {
                recorder?.pause()
                hdrRecorder?.pause()
                duration += SystemClock.elapsedRealtime() - since
                paused = true
            }
        } catch (e: Exception) {
            activity.message(e.message ?: "Cannot pause recording")
        }
        overlay.invalidate()
    }

    fun stop() {
        if (!recording) return
        session?.close()
        session = null
        pendingStart = false
        val r = recorder
        val hr = hdrRecorder
        recorder = null
        hdrRecorder = null
        finishEffectRange(elapsedSeconds())
        recording = false
        paused = false
        var success = true
        // Stop feeding the encoder before MediaRecorder disconnects its surface.
        runCatching { composition?.record(null) }
        try {
            r?.stop()
            hr?.stop()
        } catch (_: Exception) {
            success = false
        } finally {
            closeRecordingSurface()
            r?.release()
            hr?.close()
        }
        val file = recordingFile
        recordingFile = null
        if (success && file != null)
            try {
                val clip = ProjectStore.inspect(file)
                effectHistory.forEach { clip.effects.add(it.copyEffect(true)) }
                onClip(clip)
            } catch (e: Exception) {
                activity.message(e.message ?: "Cannot open recorded video")
            }
        else {
            file?.delete()
            activity.message("Recording was too short to save")
        }
        activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        if (dualLayout == 4 && dualCamera != null) {
            closeCamera()
            openDefault()
        } else createSession()
        startMeter()
        overlay.invalidate()
    }

    internal fun switchCamera(id: String) {
        if (dualCamera != null || dualLayout != 4) {
            val pair = DualCamera.pair(manager) ?: return
            if (id !in pair) {
                activity.message("Choose a main camera while using Split view")
                return
            }
            cameraId = id
            lensState(manager.getCameraCharacteristics(id))
            dualSwapped = front
            transform()
            updateRequest()
            overlay.invalidate()
            return
        }
        session?.close()
        session = null
        device?.close()
        device = null
        if (opening) {
            pendingSwitch = id
            return
        }
        open(id)
    }

    private fun newComposition(): CameraComposition {
        texture.surfaceTexture?.setDefaultBufferSize(texture.width, texture.height)
        return CameraComposition(
            Surface(texture.surfaceTexture),
            texture.width,
            texture.height,
            hdr,
        ) {
            ui.post {
                if (recording) stop()
                activity.message(it.message ?: "Camera composition failed")
            }
        }
    }

    private fun openDual() {
        if (closed || dualCamera != null || !texture.isAvailable) return
        val pair = DualCamera.pair(manager)
        if (pair == null) {
            dualLayout = 4
            activity.message("This phone does not support simultaneous front and rear cameras")
            openDefault()
            return
        }
        if (cameraId !in pair) cameraId = pair[0]
        lensState(manager.getCameraCharacteristics(cameraId))
        val sizes =
            manager
                .getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(MediaRecorder::class.java)
                ?.toList()
                .orEmpty()
        val desired =
            if (prefs.getString("quality", "1080p") == "4K") Size(3840, 2160) else Size(1920, 1080)
        videoSize =
            sizes.firstOrNull { it == desired }
                ?: sizes.filter { it.width <= 1920 }.maxByOrNull { it.width * it.height }
                ?: Size(1280, 720)
        composition = newComposition()
        transform()
        dualCamera =
            DualCamera(
                manager,
                cameraHandler,
                composition!!,
                pair,
                hdr,
                { hdrColor.profile(it) },
                {
                    ui.post {
                        dualReady = true
                        updateRequest()
                        startMeter()
                        overlay.invalidate()
                    }
                },
                { message ->
                    ui.post {
                        if (recording) stop()
                        closeCamera()
                        dualLayout = 4
                        activity.message(message)
                        openDefault()
                    }
                },
            )
    }

    internal fun setDualLayout(mode: Int) {
        if (mode == dualLayout) return
        if (mode < 4) lastDualLayout = mode
        if (mode != 4 && DualCamera.pair(manager) == null) {
            activity.message("This phone does not support simultaneous front and rear cameras")
            return
        }
        if (recording && dualCamera != null) {
            dualLayout = mode
            transform()
        } else if ((mode == 4) != (dualLayout == 4)) {
            if (mode != 4) dualSwapped = front
            if (recording) {
                activity.message("Stop recording to turn Split view on")
                return
            }
            closeCamera()
            dualLayout = mode
            openDefault()
        } else {
            dualLayout = mode
            transform()
        }
        updateLight()
        overlay.invalidate()
    }

    private fun cameras() {
        lenses = CameraLenses.available(manager)
        activity.choose(
            "Camera",
            lenses.map { it.name },
            CameraLenses.selected(lenses, cameraId, zoom),
        ) { i ->
            zoom = lenses[i].zoom
            switchCamera(lenses[i].id)
        }
    }

    private fun microphones() {
        val devices = microphoneDevices()
        activity.choose(
            "Microphone",
            MicrophoneInput.labels(devices.map { MicrophoneInput(it) }) + "Microphone priority…",
            devices.indexOfFirst { it.id == micId },
        ) { i ->
            if (i == devices.size) {
                priority(devices)
                return@choose
            }
            val previous = selectedMicrophone()
            if (!routeMicrophone(devices[i])) {
                previous?.let { routeMicrophone(it) }
                activity.message("The selected microphone is unavailable")
                return@choose
            }
            if (recording) {
                if (
                    !(recorder?.setPreferredDevice(devices[i])
                        ?: hdrRecorder?.microphone(devices[i])
                        ?: false)
                ) {
                    previous?.let { routeMicrophone(it) }
                    activity.message("This microphone cannot be selected during recording")
                    return@choose
                }
            }
            micId = devices[i].id
            prefs.edit().putInt("mic", micId).apply()
            if (!recording) startMeter()
            overlay.invalidate()
        }
    }

    private fun priority(devices: List<AudioDeviceInfo>) {
        val keys =
            prefs
                .getString("priorities", "")!!
                .split(',')
                .filter { it.isNotBlank() }
                .toMutableList()
        val labels = MicrophoneInput.labels(devices.map { MicrophoneInput(it) })
        activity.choose(
            "Tap a microphone to give it highest priority",
            devices.map { d ->
                val input = MicrophoneInput(d)
                val pos = keys.indexOfFirst { it == input.key || it == input.legacyKey }
                "${if(pos>=0)pos+1 else "–"}. ${labels[devices.indexOf(d)]}"
            },
        ) { i ->
            val key = micKey(devices[i])
            keys.remove(key)
            keys.remove(MicrophoneInput(devices[i]).legacyKey)
            keys.add(0, key)
            prefs.edit().putString("priorities", keys.joinToString(",")).apply()
            choosePriority()
        }
    }

    private fun settings() {
        activity.choose(
            "Recording settings",
            listOf(
                "Resolution · ${prefs.getString("quality","1080p")}",
                "Frame rate · ${prefs.getInt("fps",30)} fps",
                "Stabilization · ${if(prefs.getBoolean("stabilization",true))"On"else"Off"}",
                "Microphone priority",
                "Torch · ${if(torch) "On" else "Off"}",
            ),
        ) { i ->
            when (i) {
                0 ->
                    if (recording) activity.message("Stop recording to change resolution")
                    else
                        activity.choose("Resolution", listOf("1080p", "4K")) {
                            prefs
                                .edit()
                                .putString("quality", if (it == 0) "1080p" else "4K")
                                .apply()
                            closeCamera()
                            openDefault()
                        }
                1 ->
                    if (recording) activity.message("Stop recording to change frame rate")
                    else
                        activity.choose("Frame rate", listOf("24 fps", "30 fps", "60 fps")) {
                            prefs.edit().putInt("fps", listOf(24, 30, 60)[it]).apply()
                        }
                2 -> {
                    prefs
                        .edit()
                        .putBoolean("stabilization", !prefs.getBoolean("stabilization", true))
                        .apply()
                    updateRequest()
                }
                3 -> priority(microphoneDevices())
                4 -> setTorch(!torch)
            }
        }
    }

    internal fun cameraFrame(width: Int, height: Int): Bitmap? {
        val raw = texture.getBitmap(width, height) ?: return null
        val transform = texture.getTransform(Matrix())
        if (transform.isIdentity) return raw
        // Bitmap copies omit the view transform used to display the camera.
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.scale(width.toFloat() / texture.width, height.toFloat() / texture.height)
        canvas.concat(transform)
        canvas.scale(texture.width.toFloat() / width, texture.height.toFloat() / height)
        canvas.drawBitmap(raw, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        raw.recycle()
        return output
    }

    private fun updateLiveFrame() {
        val raw = cameraFrame(640, if (width > height) 360 else 1138) ?: return
        rendering = true
        val generation = effectGeneration
        val effects = liveEffects.filter { it.enabled }.map { it.copyEffect() }
        imageWorker.execute {
            val person =
                if (
                    effects.any {
                        it.kind == "Color Isolation" &&
                            it.values["mode"] == "Person" &&
                            it.number("strength", 1f) > 0f
                    }
                )
                    Person.mask(raw)
                else null
            for (fx in effects) {
                Effects.correct(raw, fx, fx.number("strength", 1f), person)
                var kind = fx.kind
                if (kind == "Color LUTs") kind = fx.values["lut"] ?: "Natural"
                val id = Effects.pixelId(kind)
                if (id > 0)
                    Native.image(
                        raw,
                        id,
                        fx.number("strength", 1f),
                        fx.number("colors", 5f),
                        fx.number("smoothing", 0.5f),
                        fx.number("dither") == 1f,
                        fx.number("palette").toInt(),
                        fx.number("bw") == 1f,
                    )
            }
            ui.post {
                rendering = false
                if (generation != effectGeneration || closed) raw.recycle()
                else {
                    liveFrame?.recycle()
                    liveFrame = raw
                    overlay.invalidate()
                }
            }
        }
    }

    internal fun drawCamera(canvas: Canvas) {
        cameraFrame(texture.width, texture.height)?.let {
            canvas.drawBitmap(
                it,
                null,
                RectF(
                    texture.left.toFloat(),
                    texture.top.toFloat(),
                    texture.right.toFloat(),
                    texture.bottom.toFloat(),
                ),
                Paint(),
            )
            it.recycle()
        }
    }

    private fun finishEffectRange(end: Double) {
        if (end <= effectSince) return
        for (fx in liveEffects.filter { it.enabled }) effectHistory.add(
            fx.copyEffect(true).apply {
                from = effectSince
                to = end
            }
        )
        effectSince = end
    }

    fun effects() {
        activity.captureEffects(liveEffects) {
            val next = it.map { fx -> fx.copyEffect() }.toMutableList()
            if (recording && next != liveEffects) finishEffectRange(elapsedSeconds())
            if (next != liveEffects) effectGeneration++
            liveEffects = next
            if (it.none { fx -> fx.enabled }) {
                liveFrame?.recycle()
                liveFrame = null
            }
            overlay.invalidate()
        }
    }

    private fun closeCamera() {
        updateLight(false)
        colorGeneration++
        matchingColor = false
        effectGeneration++
        liveFrame?.recycle()
        liveFrame = null
        dualReady = false
        dualCamera?.close()
        dualCamera = null
        composition?.close()
        composition = null
        releaseMicrophoneRoute()
        session?.close()
        session = null
        device?.close()
        device = null
        previewSurface?.release()
        previewSurface = null
        stopMeter()
    }

    fun suspendCamera() {
        if (recording && !paused) pause()
        closed = true
        closeCamera()
        ui.removeCallbacks(ticker)
    }

    fun destroy() {
        if (recording) stop()
        closed = true
        closeCamera()
        audioManager.unregisterAudioDeviceCallback(audioDevices)
        audioManager.unregisterAudioRecordingCallback(recordingCallback)
        displays.unregisterDisplayListener(displayListener)
        ui.removeCallbacks(ticker)
        handlerThread.quitSafely()
        meterWorker.shutdown()
        imageWorker.shutdown()
        liveFrame?.recycle()
    }

    private inner class CaptureOverlay : View(activity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val lightPath = Path()
        private val hits = mutableListOf<Pair<RectF, String>>()
        private var s = 1f
        private var w = 640f
        private var h = 294f
        private val glyphs = mutableMapOf<Pair<String, Int>, Glyph>()

        fun bounds(id: String) =
            hits
                .firstOrNull { it.second == id }
                ?.first
                ?.let { RectF(it.left * s, it.top * s, it.right * s, it.bottom * s) }

        private fun text(
            c: Canvas,
            t: String,
            x: Float,
            y: Float,
            size: Float = 10f,
            color: Int = Color.WHITE,
            align: Paint.Align = Paint.Align.LEFT,
        ) {
            paint.color = color
            paint.textSize = size
            paint.textAlign = align
            paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            paint.setShadowLayer(2f, 0f, 1f, Color.BLACK)
            c.drawText(t, x, y, paint)
            paint.clearShadowLayer()
        }

        private fun pill(c: Canvas, r: RectF, title: String, id: String, color: Int = Color.WHITE) {
            paint.color =
                if (id == "time" && recording) Color.rgb(210, 44, 60)
                else Color.argb(90, 25, 25, 27)
            if (id !in listOf("flip", "camera", "zoom", "focus"))
                c.drawRoundRect(r, 15f, 15f, paint)
            val kind =
                when (id) {
                    "dual" -> "Dual camera"
                    "pip" -> "Picture in picture"
                    "splitVertical" -> "Split vertical"
                    "splitHorizontal" -> "Split horizontal"
                    "splitDiagonal" -> "Split diagonal"
                    "dualReset" -> "Reset"
                    "effects" -> "Sparkles"
                    "settings" -> "Edit"
                    "flip" -> "Camera flip"
                    "pipSwap" -> "Camera flip"
                    "projects" -> "Stack"
                    "pause" -> if (paused) "Play" else "Pause"
                    "edit" -> "Scissors"
                    else -> null
                }
            if (id == "mic") {
                val glyph =
                    glyphs.getOrPut("Microphone" to color) {
                        Glyph(activity, "Microphone", color).apply { layout(0, 0, 12, 12) }
                    }
                c.save()
                c.translate(r.left + 8, r.top + 2)
                glyph.draw(c)
                c.restore()
                for (i in 0..7) {
                    paint.color =
                        if (amplitude > i / 8f) if (i > 5) Color.rgb(255, 75, 60) else Color.WHITE
                        else Color.argb(150, 100, 100, 100)
                    val x = r.left + 24 + i * 3.2f
                    c.drawRoundRect(
                        x,
                        r.top + 8 - i * .65f,
                        x + 1.8f,
                        r.top + 12 + i * .65f,
                        1f,
                        1f,
                        paint,
                    )
                }
                text(c, title, r.centerX(), r.bottom - 4, 7f, color, Paint.Align.CENTER)
            } else if (id == "time") {
                paint.color = Color.WHITE
                paint.typeface = Typeface.create("monospace", Typeface.BOLD)
                paint.textSize = 11f
                paint.textAlign = Paint.Align.CENTER
                c.drawText(title, r.centerX(), r.centerY() + 4, paint)
            } else if (id == "camera") {
                paint.textSize = 9f
                paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                val labelWidth = paint.measureText(title)
                text(c, title, r.centerX() - 5, r.centerY() + 3, 9f, color, Paint.Align.CENTER)
                val x = r.centerX() + labelWidth / 2 + 2
                paint.strokeWidth = 1.1f
                paint.strokeCap = Paint.Cap.ROUND
                c.drawLine(x - 2.2f, r.centerY() - .8f, x, r.centerY() + 1.4f, paint)
                c.drawLine(x, r.centerY() + 1.4f, x + 2.2f, r.centerY() - .8f, paint)
            } else if (kind == null)
                text(c, title, r.centerX(), r.centerY() + 3, 9f, color, Paint.Align.CENTER)
            else {
                val glyph =
                    glyphs.getOrPut(kind to color) {
                        Glyph(activity, kind, color).apply { layout(0, 0, 18, 18) }
                    }
                c.save()
                c.translate(r.centerX() - 9, r.centerY() - 9)
                glyph.draw(c)
                c.restore()
            }
            hits.add(r to id)
        }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            s = if (width > height) height / 294f else width / 294f
            w = width / s
            h = height / s
            liveFrame?.let {
                c.drawBitmap(
                    it,
                    null,
                    RectF(
                        texture.left.toFloat(),
                        texture.top.toFloat(),
                        texture.right.toFloat(),
                        texture.bottom.toFloat(),
                    ),
                    paint,
                )
            }
            if (screenLight) {
                val border = min(width, height) * lightWidth
                paint.color = Color.WHITE
                c.drawRect(0f, 0f, width.toFloat(), texture.top.toFloat(), paint)
                c.drawRect(0f, texture.bottom.toFloat(), width.toFloat(), height.toFloat(), paint)
                c.drawRect(
                    0f,
                    texture.top.toFloat(),
                    texture.left.toFloat(),
                    texture.bottom.toFloat(),
                    paint,
                )
                c.drawRect(
                    texture.right.toFloat(),
                    texture.top.toFloat(),
                    width.toFloat(),
                    texture.bottom.toFloat(),
                    paint,
                )
                lightPath.rewind()
                lightPath.fillType = Path.FillType.EVEN_ODD
                lightPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
                val radius = 24 * resources.displayMetrics.density
                lightPath.addRoundRect(
                    max(border, texture.left.toFloat()),
                    max(border, texture.top.toFloat()),
                    min(width - border, texture.right.toFloat()),
                    min(height - border, texture.bottom.toFloat()),
                    radius,
                    radius,
                    Path.Direction.CW,
                )
                paint.color = Color.argb(200, 255, 255, 255)
                c.drawPath(lightPath, paint)
            }
            c.save()
            c.scale(s, s)
            hits.clear()
            val portrait = h > w
            val top = if (portrait) max(36f, texture.top / s - 10f) else 14f
            pill(
                c,
                RectF(
                    if (portrait) 12f else 64f,
                    top,
                    if (portrait) 74f else 178f,
                    top + if (portrait) 34 else 22,
                ),
                micName().take(if (portrait) 15 else 18),
                "mic",
            )
            val elapsed =
                (duration +
                    if (recording && !paused) SystemClock.elapsedRealtime() - since else 0) / 1000.0
            val timer = if (recording) elapsed else activity.editor.project.duration
            pill(
                c,
                RectF(
                    if (portrait) 80f else 252f,
                    top,
                    if (portrait) 130f else 328f,
                    top + if (portrait) 34 else 22,
                ),
                "%02d:%02d".format(java.util.Locale.US, (timer / 60).toInt(), (timer % 60).toInt()),
                "time",
                if (recording) Color.rgb(255, 55, 74) else Color.WHITE,
            )
            pill(
                c,
                RectF(
                    if (portrait) 137f else 366f,
                    top,
                    if (portrait) 169f else 400f,
                    top + if (portrait) 34 else 22,
                ),
                if (hdr) "HDR" else "SDR",
                "hdr",
                if (hdr) Color.YELLOW else Color.WHITE,
            )
            pill(
                c,
                RectF(
                    if (portrait) 175f else 404f,
                    top,
                    if (portrait) 207f else 437f,
                    top + if (portrait) 34 else 22,
                ),
                "",
                "dual",
                if (dualMenu || dualLayout != 4) Color.CYAN else Color.WHITE,
            )
            pill(
                c,
                RectF(
                    if (portrait) 213f else 441f,
                    top,
                    if (portrait) 245f else 474f,
                    top + if (portrait) 34 else 22,
                ),
                "✧",
                "effects",
                if (liveEffects.any { it.enabled }) Color.YELLOW else Color.WHITE,
            )
            pill(
                c,
                RectF(
                    if (portrait) 251f else 478f,
                    top,
                    if (portrait) 283f else 511f,
                    top + if (portrait) 34 else 22,
                ),
                "☷",
                "settings",
            )
            if (dualMenu) {
                val left = if (portrait) 26f else 175f
                val y = top + 34
                paint.color = Color.argb(180, 53, 53, 49)
                c.drawRoundRect(left, y, left + 242, y + 40, 12f, 12f, paint)
                listOf(
                        "pip",
                        "splitVertical",
                        "splitHorizontal",
                        "splitDiagonal",
                        "dualReset",
                        "dualDone",
                    )
                    .forEachIndexed { i, id ->
                        val active = i < 4 && i == dualLayout
                        pill(
                            c,
                            RectF(left + 3 + i * 39, y + 3, left + 39 + i * 39, y + 37),
                            if (id == "dualDone") "Done" else "",
                            id,
                            if (active) Color.CYAN else Color.WHITE,
                        )
                    }
                text(
                    c,
                    if (dualLayout == 0) "Swap views · Drag inset or edges to resize"
                    else "Tap divider to flip · Two fingers to pan or zoom",
                    w / 2,
                    y + 54,
                    8f,
                    Color.WHITE,
                    Paint.Align.CENTER,
                )
            }
            if (dualCamera != null && dualLayout == 0) {
                insetBounds()?.let { bounds ->
                    val r =
                        RectF(bounds.left / s, bounds.top / s, bounds.right / s, bounds.bottom / s)
                    paint.color = Color.WHITE
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 1f
                    c.drawRect(r, paint)
                    paint.strokeWidth = 2.5f
                    for (x in listOf(r.left, r.right)) for (y in listOf(r.top, r.bottom)) {
                        c.drawLine(x, y, x + if (x == r.left) 7f else -7f, y, paint)
                        c.drawLine(x, y, x, y + if (y == r.top) 7f else -7f, paint)
                    }
                    paint.style = Paint.Style.FILL
                    pill(
                        c,
                        RectF(
                            r.centerX() - 13,
                            r.centerY() - 13,
                            r.centerX() + 13,
                            r.centerY() + 13,
                        ),
                        "",
                        "pipSwap",
                    )
                }
            }
            val cx = if (portrait) w / 2 else 549f
            val cy = if (portrait) texture.bottom / s - 12f else 139f
            paint.color = Color.WHITE
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f
            c.drawCircle(cx, cy, 28f, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(255, 47, 64)
            if (recording) c.drawRoundRect(cx - 13, cy - 13, cx + 13, cy + 13, 4f, 4f, paint)
            else c.drawCircle(cx, cy, 24f, paint)
            hits.add(RectF(cx - 34, cy - 34, cx + 34, cy + 34) to "record")
            pill(
                c,
                RectF(
                    if (portrait) cx - 100 else cx - 14,
                    if (portrait) cy - 16 else cy + 52,
                    if (portrait) cx - 64 else cx + 14,
                    if (portrait) cy + 16 else cy + 88,
                ),
                if (recording) if (paused) "▶" else "Ⅱ" else "",
                if (recording) "pause" else "projects",
            )
            pill(
                c,
                RectF(
                    if (portrait) cx + 64 else cx - 18,
                    if (portrait) cy - 16 else cy - 83,
                    if (portrait) cx + 100 else cx + 18,
                    if (portrait) cy + 16 else cy - 53,
                ),
                "✂",
                "edit",
            )
            val bottom = if (portrait) texture.bottom / s - 94f else 242f
            if (portrait) {
                paint.color = Color.argb(105, 60, 59, 55)
                c.drawRoundRect(30f, bottom, 264f, bottom + 33, 18f, 18f, paint)
                paint.color = Color.argb(60, 255, 255, 255)
                paint.strokeWidth = .5f
                c.drawLine(164f, bottom + 10, 164f, bottom + 23, paint)
                c.drawLine(217f, bottom + 10, 217f, bottom + 23, paint)
            } else {
                paint.color = Color.argb(95, 25, 25, 27)
                c.drawRoundRect(164f, 236f, 394f, 272f, 18f, 18f, paint)
            }
            pill(
                c,
                RectF(
                    if (portrait) 34f else 171f,
                    bottom,
                    if (portrait) 65f else 203f,
                    bottom + if (portrait) 33 else 25,
                ),
                "▣",
                "flip",
            )
            pill(
                c,
                RectF(
                    if (portrait) 73f else 208f,
                    bottom,
                    if (portrait) 153f else 286f,
                    bottom + if (portrait) 33 else 25,
                ),
                lenses
                    .getOrNull(CameraLenses.selected(lenses, cameraId, zoom))
                    ?.name
                    ?.removePrefix("Rear ")
                    ?.replaceFirstChar { it.uppercase() } ?: "Main",
                "camera",
            )
            pill(
                c,
                RectF(
                    if (portrait) 167f else 301f,
                    bottom,
                    if (portrait) 212f else 346f,
                    bottom + if (portrait) 33 else 25,
                ),
                "%.1f×".format(java.util.Locale.US, zoom),
                "zoom",
                if (zoomMenu) Color.YELLOW else Color.WHITE,
            )
            pill(
                c,
                RectF(
                    if (portrait) 223f else 358f,
                    bottom,
                    if (portrait) 257f else 391f,
                    bottom + if (portrait) 33 else 25,
                ),
                if (!CameraFocus.canLock(focusMode)) "Fixed" else if (focusLocked) "AF L" else "AF",
                "focus",
                if (focusLocked) Color.YELLOW else Color.WHITE,
            )
            if (!recording)
                pill(
                    c,
                    RectF(
                        if (portrait) w / 2 - 64 else 216f,
                        bottom - (if (zoomMenu) 78 else 36),
                        if (portrait) w / 2 + 64 else 344f,
                        bottom - (if (zoomMenu) 55 else 13),
                    ),
                    "+ Start new project",
                    "new",
                )
            if (zoomMenu) {
                val y = bottom - 44
                val left = if (portrait) 30f else 164f
                val right = if (portrait) 264f else 394f
                paint.color = Color.argb(120, 60, 59, 55)
                c.drawRoundRect(left, y, right, y + 36, 18f, 18f, paint)
                val timerGlyph =
                    glyphs.getOrPut("Timer" to Color.WHITE) {
                        Glyph(activity, "Timer", Color.WHITE).apply { layout(0, 0, 11, 11) }
                    }
                c.save()
                c.translate(left + 19, y + 5)
                timerGlyph.draw(c)
                c.restore()
                text(
                    c,
                    if (zoomSeconds == 0f) "Instant" else "${zoomSeconds.toInt()} s",
                    left + 24,
                    y + 27,
                    7f,
                    Color.WHITE,
                    Paint.Align.CENTER,
                )
                val arrows =
                    glyphs.getOrPut("Chevrons" to Color.WHITE) {
                        Glyph(activity, "Chevrons", Color.WHITE).apply { layout(0, 0, 8, 12) }
                    }
                c.save()
                c.translate(left + 44, y + 12)
                arrows.draw(c)
                c.restore()
                hits.add(RectF(left, y, left + 54, y + 36) to "zoomTime")
                val presets =
                    listOf(if (zoomRange.lower < 1f) zoomRange.lower else 1f, 1f, 2f, 3f, 5f, 6f)
                        .distinct()
                        .filter { it >= zoomRange.lower && it <= zoomRange.upper }
                val cell = (right - left - 54) / presets.size.coerceAtLeast(1)
                for ((i, z) in presets.withIndex()) {
                    val x = left + 54 + i * cell
                    text(
                        c,
                        "${if(z%1 == 0f) z.toInt().toString() else z.toString()}×",
                        x + cell / 2,
                        y + 21,
                        9f,
                        if (abs(zoom - z) < .05f) Color.YELLOW else Color.WHITE,
                        Paint.Align.CENTER,
                    )
                    hits.add(RectF(x, y, x + cell, y + 36) to "z:$z")
                }
            }
            if (focusLocked)
                text(c, "AF LOCK", w / 2, top + 38, 8f, Color.YELLOW, Paint.Align.CENTER)
            c.restore()
        }

        private val scale =
            ScaleGestureDetector(
                activity,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(d: ScaleGestureDetector): Boolean {
                        moved = true
                        if (dualCamera != null) {
                            composition?.gesture(
                                (d.focusX - texture.left) / texture.width,
                                (d.focusY - texture.top) / texture.height,
                                0f,
                                0f,
                                d.scaleFactor,
                            )
                        } else setZoom(zoom * d.scaleFactor, true)
                        return true
                    }
                },
            )

        private var touchX = 0f
        private var touchY = 0f
        private var moved = false
        private var composing = false
        private var draggingInset = false
        private var resizingInset = 0
        private var resizingLight = false
        private var downX = 0f
        private var downY = 0f
        private val slop = ViewConfiguration.get(activity).scaledTouchSlop

        private fun edgeDistance(x: Float, y: Float) = min(min(x, width - x), min(y, height - y))

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                touchX = e.x
                touchY = e.y
                downX = e.x
                downY = e.y
                moved = false
                val free = hits.none { it.first.contains(e.x / s, e.y / s) }
                val margin = 14 * resources.displayMetrics.density
                val inset = insetBounds()?.apply { inset(-margin, -margin) }
                resizingLight =
                    screenLight &&
                        free &&
                        inset?.contains(e.x, e.y) != true &&
                        (edgeDistance(e.x, e.y) <= min(width, height) * lightWidth + margin ||
                            e.x <= texture.left + margin ||
                            e.x >= texture.right - margin ||
                            e.y <= texture.top + margin ||
                            e.y >= texture.bottom - margin)
                composing =
                    !resizingLight &&
                        dualCamera != null &&
                        e.x >= texture.left &&
                        e.x < texture.right &&
                        e.y >= texture.top &&
                        e.y < texture.bottom &&
                        free
                resizingInset = 0
                if (composing)
                    insetBounds()?.let { r ->
                        if (
                            e.x >= r.left - margin &&
                                e.x <= r.right + margin &&
                                e.y >= r.top - margin &&
                                e.y <= r.bottom + margin
                        ) {
                            if (abs(e.x - r.left) < margin) resizingInset = resizingInset or 1
                            else if (abs(e.x - r.right) < margin) resizingInset = resizingInset or 4
                            if (abs(e.y - r.top) < margin) resizingInset = resizingInset or 2
                            else if (abs(e.y - r.bottom) < margin)
                                resizingInset = resizingInset or 8
                        }
                    }
                draggingInset =
                    composing &&
                        composition?.insetContains(
                            (e.x - texture.left) / texture.width,
                            (e.y - texture.top) / texture.height,
                        ) == true
            }
            if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                resizingLight = false
                resizingInset = 0
                draggingInset = false
                moved = true
            }
            if (e.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (resizingLight) prefs.edit().putFloat("lightWidth", lightWidth).apply()
                resizingLight = false
                composing = false
                return true
            }
            if (e.actionMasked == MotionEvent.ACTION_MOVE && hypot(e.x - downX, e.y - downY) > slop)
                moved = true
            if (resizingLight) {
                if (e.actionMasked == MotionEvent.ACTION_MOVE && moved) {
                    lightWidth = (edgeDistance(e.x, e.y) / min(width, height)).coerceIn(.015f, .35f)
                    invalidate()
                }
                if (e.actionMasked == MotionEvent.ACTION_UP) {
                    prefs.edit().putFloat("lightWidth", lightWidth).apply()
                    resizingLight = false
                }
                return true
            }
            if (e.actionMasked == MotionEvent.ACTION_MOVE && composing) {
                val dx = (e.x - touchX) / texture.width
                val dy = (e.y - touchY) / texture.height
                if (abs(dx) + abs(dy) > .003f) {
                    if (e.pointerCount >= 2)
                        composition?.gesture(
                            (e.x - texture.left) / texture.width,
                            (e.y - texture.top) / texture.height,
                            dx,
                            dy,
                            1f,
                        )
                    else if (resizingInset != 0) composition?.resizePip(resizingInset, dx, dy)
                    else if (draggingInset) composition?.movePip(dx, dy)
                    moved = true
                    touchX = e.x
                    touchY = e.y
                    invalidate()
                }
            }
            scale.onTouchEvent(e)
            if (e.actionMasked != MotionEvent.ACTION_UP || scale.isInProgress || moved) return true
            val x = e.x / s
            val y = e.y / s
            val id = hits.lastOrNull { it.first.contains(x, y) }?.second
            if (id == null) {
                if (composition != null && !moved) {
                    val px = (e.x - texture.left) / texture.width
                    val py = (e.y - texture.top) / texture.height
                    if (
                        (dualLayout == 0 && draggingInset) ||
                            (dualLayout == 1 && abs(px - .5f) < .07f) ||
                            (dualLayout == 2 && abs(py - .5f) < .07f) ||
                            (dualLayout == 3 && abs(px + py - 1f) < .07f)
                    ) {
                        swapCameras()
                    }
                }
                return true
            }
            when {
                id.startsWith("z:") -> {
                    val z = id.substringAfter(':').toFloat()
                    val instant = e.eventTime - lastZoomTap < 300 && lastZoom == z
                    setZoom(z, instant)
                    lastZoomTap = e.eventTime
                    lastZoom = z
                }
                else ->
                    when (id) {
                        "record" -> if (recording) stop() else start()
                        "pause" -> pause()
                        "edit" -> {
                            if (recording) stop()
                            suspendCamera()
                            onEdit()
                        }
                        "mic" -> microphones()
                        "camera" -> cameras()
                        "flip" -> {
                            val facing =
                                if (front) CameraCharacteristics.LENS_FACING_BACK
                                else CameraCharacteristics.LENS_FACING_FRONT
                            manager.cameraIdList
                                .firstOrNull {
                                    manager
                                        .getCameraCharacteristics(it)
                                        .get(CameraCharacteristics.LENS_FACING) == facing
                                }
                                ?.let { switchCamera(it) }
                        }
                        "zoom" -> zoomMenu = !zoomMenu
                        "zoomTime" ->
                            activity.choose(
                                "Zoom duration",
                                listOf("Instant", "1 second", "2 seconds", "3 seconds", "5 seconds"),
                            ) {
                                zoomSeconds = listOf(0f, 1f, 2f, 3f, 5f)[it]
                                prefs.edit().putFloat("zoomSeconds", zoomSeconds).apply()
                            }
                        "focus" -> {
                            if (!CameraFocus.canLock(focusMode)) {
                                activity.message("This camera has fixed focus")
                                return true
                            }
                            focusLocked = !focusLocked
                            cameraHandler.post {
                                focusTrigger =
                                    if (focusLocked) CaptureRequest.CONTROL_AF_TRIGGER_START
                                    else CaptureRequest.CONTROL_AF_TRIGGER_CANCEL
                                updateRequest()
                            }
                        }
                        "projects" -> {
                            suspendCamera()
                            onEdit()
                            activity.panels.projects()
                        }
                        "pipSwap" -> swapCameras()
                        "settings" -> settings()
                        "hdr" -> setHdr(!hdr)
                        "dual" -> {
                            dualMenu = !dualMenu
                            if (dualMenu && dualLayout == 4) setDualLayout(lastDualLayout)
                        }
                        "pip" -> setDualLayout(0)
                        "splitVertical" -> setDualLayout(1)
                        "splitHorizontal" -> setDualLayout(2)
                        "splitDiagonal" -> setDualLayout(3)
                        "dualReset" -> {
                            composition?.reset()
                            dualSwapped = front
                            setDualLayout(4)
                        }
                        "dualDone" -> dualMenu = false
                        "effects" -> effects()
                        "new" -> activity.newProject()
                    }
            }
            invalidate()
            performClick()
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }
}
