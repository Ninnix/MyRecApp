package org.yourec.app

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
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
    private val audioManager = activity.getSystemService(AudioManager::class.java)
    private val handlerThread = HandlerThread("camera").apply { start() }
    private val cameraHandler = Handler(handlerThread.looper)
    private val ui = Handler(Looper.getMainLooper())
    private val prefs = activity.getSharedPreferences("capture", Context.MODE_PRIVATE)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var request: CaptureRequest.Builder? = null
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var pendingStart = false
    private var previewSurface: Surface? = null
    private var cameraId = ""
    private var opening = false
    private var pendingSwitch: String? = null
    private var closed = false
    private var videoSize = Size(1920, 1080)
    private var front = false
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
    private var focusLocked = false
    @Volatile private var focusMode = CaptureRequest.CONTROL_AF_MODE_OFF
    @Volatile
    internal var focusState: Int? = null
        private set

    private var focusTrigger = CaptureRequest.CONTROL_AF_TRIGGER_IDLE
    private var torch = false
    private var zoom = 1f
    private var zoomRange = Range(1f, 8f)
    private var zoomSeconds = prefs.getFloat("zoomSeconds", 1f)
    private var zoomAnimator: ValueAnimator? = null
    private var zoomMenu = false
    private var liveEffects = mutableListOf<Effect>()
    private var liveFrame: Bitmap? = null
    private var rendering = false
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
                                recorder?.activeRecordingConfiguration?.clientAudioSessionId &&
                                it.isClientSilenced
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
                            (recorder?.maxAmplitude ?: 0) / 32767f
                        } catch (_: Exception) {
                            0f
                        }
                if (liveEffects.isNotEmpty() && texture.isAvailable && !rendering) updateLiveFrame()
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
        selectedMicrophone()?.let { routeMicrophone(it) }
        if (texture.isAvailable) openDefault()
        ui.removeCallbacks(ticker)
        ui.post(ticker)
    }

    private fun openDefault() {
        if (closed || device != null || opening) return
        if (
            activity.checkSelfPermission(Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED
        )
            return
        val ids = manager.cameraIdList
        if (cameraId.isEmpty())
            cameraId =
                ids.firstOrNull {
                    manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_BACK
                } ?: ids.firstOrNull() ?: return
        open(cameraId)
    }

    private fun open(id: String) {
        if (closed || opening) return
        opening = true
        cameraId = id
        try {
            val chars = manager.getCameraCharacteristics(id)
            focusMode =
                CameraFocus.mode(
                    chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                        ?: intArrayOf(CaptureRequest.CONTROL_AF_MODE_OFF)
                )
            if (!CameraFocus.canLock(focusMode)) focusLocked = false
            front =
                chars.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_FRONT
            zoomRange =
                if (Build.VERSION.SDK_INT >= 30)
                    chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: Range(1f, 8f)
                else
                    Range(
                        1f,
                        chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f,
                    )
            zoom = zoom.coerceIn(zoomRange.lower, zoomRange.upper)
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
            st.setDefaultBufferSize(videoSize.width, videoSize.height)
            previewSurface = Surface(st)
            val surfaces = mutableListOf(previewSurface!!)
            recorder?.let { surfaces.add(it.surface) }
            request =
                camera.createCaptureRequest(
                    if (recorder != null) CameraDevice.TEMPLATE_RECORD
                    else CameraDevice.TEMPLATE_PREVIEW
                )
            surfaces.forEach { request!!.addTarget(it) }
            camera.createCaptureSession(
                surfaces,
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
                                since = SystemClock.elapsedRealtime()
                            } catch (e: Exception) {
                                ui.post {
                                    recording = false
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
                            activity.message(
                                "This camera does not support the selected recording format"
                            )
                        }
                    }
                },
                cameraHandler,
            )
        } catch (e: Exception) {
            ui.post { activity.message(e.message ?: "Cannot start camera preview") }
        }
    }

    private fun updateRequest() {
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
                if (torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF,
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
        val rotated = (sensor - rotation * 90 + 360) % 180 != 0
        val bufferW = if (rotated) videoSize.height.toFloat() else videoSize.width.toFloat()
        val bufferH = if (rotated) videoSize.width.toFloat() else videoSize.height.toFloat()
        val matrix = Matrix()
        val view = RectF(0f, 0f, w, h)
        val buffer = RectF(0f, 0f, bufferH, bufferW)
        buffer.offset(view.centerX() - buffer.centerX(), view.centerY() - buffer.centerY())
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            matrix.setRectToRect(view, buffer, Matrix.ScaleToFit.FILL)
            val scale = max(h / videoSize.height, w / videoSize.width)
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

    internal fun controlBounds(id: String) = overlay.bounds(id)

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
        if (device == null) {
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
            val sensor =
                manager
                    .getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val rotation = activity.windowManager.defaultDisplay.rotation * 90
            r.setOrientationHint(
                if (front) (sensor + rotation) % 360 else (sensor - rotation + 360) % 360
            )
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
            recorder = r
            recordingFile = file
            recording = true
            paused = false
            duration = 0
            since = SystemClock.elapsedRealtime()
            pendingStart = true
            createSession()
            activity.requestedOrientation =
                if (width > height)
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } catch (e: Exception) {
            r.release()
            file.delete()
            recorder = null
            recording = false
            createSession()
            activity.message(e.message ?: "Cannot start recording")
        }
        overlay.invalidate()
    }

    fun elapsedSeconds(): Double =
        (duration + if (recording && !paused) SystemClock.elapsedRealtime() - since else 0) / 1000.0

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
                since = SystemClock.elapsedRealtime()
                paused = false
            } else {
                recorder?.pause()
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
        recorder = null
        recording = false
        paused = false
        var success = true
        try {
            r?.stop()
        } catch (_: Exception) {
            success = false
        } finally {
            r?.release()
        }
        val file = recordingFile
        recordingFile = null
        if (success && file != null)
            try {
                val clip = ProjectStore.inspect(file)
                liveEffects.forEach { clip.effects.add(it.copyEffect(true)) }
                onClip(clip)
            } catch (e: Exception) {
                activity.message(e.message ?: "Cannot open recorded video")
            }
        else {
            file?.delete()
            activity.message("Recording was too short to save")
        }
        activity.requestedOrientation =
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        createSession()
        startMeter()
        overlay.invalidate()
    }

    internal fun switchCamera(id: String) {
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

    private fun cameras() {
        val options = mutableListOf<Pair<String, () -> Unit>>()
        val cameraIds = manager.cameraIdList.toList()
        val primary =
            listOf(CameraCharacteristics.LENS_FACING_BACK, CameraCharacteristics.LENS_FACING_FRONT)
                .mapNotNull { facing ->
                    cameraIds.firstOrNull {
                        manager
                            .getCameraCharacteristics(it)
                            .get(CameraCharacteristics.LENS_FACING) == facing
                    }
                }
        for (id in primary) {
            val chars = manager.getCameraCharacteristics(id)
            val isFront =
                chars.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_FRONT
            options.add(
                (if (isFront) "Front main" else "Rear main") to
                    {
                        zoom = 1f
                        switchCamera(id)
                    }
            )
            val range =
                if (Build.VERSION.SDK_INT >= 30)
                    chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                else null
            if (range != null && range.lower < 1f)
                options.add(
                    (if (isFront) "Front ultrawide" else "Rear ultrawide") to
                        {
                            zoom = range.lower
                            switchCamera(id)
                        }
                )
            if (!isFront && range != null && range.upper >= 3f)
                options.add(
                    (if (isFront) "Front tele" else "Rear tele") to
                        {
                            zoom = 3f
                            switchCamera(id)
                        }
                )
        }
        activity.choose("Camera", options.map { it.first }) { options[it].second() }
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
                if (!recorder!!.setPreferredDevice(devices[i])) {
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
                4 -> {
                    torch = !torch
                    updateRequest()
                }
            }
        }
    }

    private fun updateLiveFrame() {
        val raw = texture.getBitmap(640, if (width > height) 360 else 1138) ?: return
        rendering = true
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
                liveFrame?.recycle()
                liveFrame = raw
                rendering = false
                overlay.invalidate()
            }
        }
    }

    fun effects() {
        activity.captureEffects(liveEffects) {
            liveEffects = it.toMutableList()
            if (it.isEmpty()) {
                liveFrame?.recycle()
                liveFrame = null
            }
            overlay.invalidate()
        }
    }

    private fun closeCamera() {
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
        ui.removeCallbacks(ticker)
        handlerThread.quitSafely()
        meterWorker.shutdown()
        imageWorker.shutdown()
        liveFrame?.recycle()
    }

    private inner class CaptureOverlay : View(activity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
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
            paint.color = Color.argb(90, 25, 25, 27)
            if (h > w || id !in listOf("flip", "camera", "zoom", "focus"))
                c.drawRoundRect(r, 15f, 15f, paint)
            val kind =
                when (id) {
                    "effects" -> "Sparkles"
                    "settings" -> "Edit"
                    "flip" -> "Camera flip"
                    "projects" -> "Stack"
                    "pause" -> if (paused) "Play" else "Pause"
                    "edit" -> "Scissors"
                    else -> null
                }
            if (id == "camera") {
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
            c.save()
            c.scale(s, s)
            hits.clear()
            val portrait = h > w
            val top = if (portrait) 36f else 14f
            pill(
                c,
                RectF(if (portrait) 12f else 64f, top, if (portrait) 108f else 178f, top + 22),
                micName().take(if (portrait) 15 else 18),
                "mic",
            )
            for (i in 0..7) {
                paint.color =
                    if (amplitude > i / 8f) if (i > 5) Color.rgb(255, 75, 60) else Color.WHITE
                    else Color.argb(100, 150, 150, 150)
                c.drawRect(
                    (if (portrait) 99f else 154f) + i * 2,
                    top + 17 - i,
                    (if (portrait) 100f else 155f) + i * 2,
                    top + 18,
                    paint,
                )
            }
            val elapsed =
                (duration +
                    if (recording && !paused) SystemClock.elapsedRealtime() - since else 0) / 1000.0
            pill(
                c,
                RectF(if (portrait) 116f else 252f, top, if (portrait) 178f else 328f, top + 22),
                if (recording) timeLabel(elapsed) else timeLabel(activity.editor.project.duration),
                "time",
                if (recording) Color.rgb(255, 55, 74) else Color.WHITE,
            )
            pill(
                c,
                RectF(if (portrait) 181f else 396f, top, if (portrait) 213f else 430f, top + 22),
                "SDR",
                "hdr",
            )
            pill(
                c,
                RectF(if (portrait) 216f else 434f, top, if (portrait) 244f else 469f, top + 22),
                "✧",
                "effects",
                if (liveEffects.any { it.enabled }) Color.YELLOW else Color.WHITE,
            )
            pill(
                c,
                RectF(if (portrait) 248f else 472f, top, if (portrait) 279f else 506f, top + 22),
                "☷",
                "settings",
            )
            val cx = if (portrait) w / 2 else 549f
            val cy = if (portrait) h - 88 else 139f
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
                    if (portrait) cx + 64 else cx - 14,
                    if (portrait) cy - 16 else cy - 86,
                    if (portrait) cx + 100 else cx + 14,
                    if (portrait) cy + 16 else cy - 50,
                ),
                if (recording) if (paused) "▶" else "Ⅱ" else "",
                if (recording) "pause" else "projects",
            )
            pill(
                c,
                RectF(
                    if (portrait) cx - 100 else cx - 18,
                    if (portrait) cy - 16 else cy + 55,
                    if (portrait) cx - 64 else cx + 18,
                    if (portrait) cy + 16 else cy + 85,
                ),
                "✂",
                "edit",
            )
            val bottom = if (portrait) h - 155 else 242f
            if (!portrait) {
                paint.color = Color.argb(95, 25, 25, 27)
                c.drawRoundRect(164f, 236f, 394f, 272f, 18f, 18f, paint)
            }
            pill(
                c,
                RectF(
                    if (portrait) 24f else 171f,
                    bottom,
                    if (portrait) 55f else 203f,
                    bottom + 25,
                ),
                "▣",
                "flip",
            )
            pill(
                c,
                RectF(
                    if (portrait) 62f else 208f,
                    bottom,
                    if (portrait) 154f else 286f,
                    bottom + 25,
                ),
                if (front) "Front main"
                else if (zoom < 1) "Ultrawide" else if (zoom >= 3) "Tele" else "Main",
                "camera",
            )
            pill(
                c,
                RectF(
                    if (portrait) 161f else 301f,
                    bottom,
                    if (portrait) 203f else 346f,
                    bottom + 25,
                ),
                "%.1f×".format(java.util.Locale.US, zoom),
                "zoom",
                if (zoomMenu) Color.YELLOW else Color.WHITE,
            )
            pill(
                c,
                RectF(
                    if (portrait) 211f else 358f,
                    bottom,
                    if (portrait) 244f else 391f,
                    bottom + 25,
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
                        bottom - (if (zoomMenu) 68 else 36),
                        if (portrait) w / 2 + 64 else 344f,
                        bottom - (if (zoomMenu) 45 else 13),
                    ),
                    "+ Start new project",
                    "new",
                )
            if (zoomMenu) {
                val y = bottom - 30
                val left = if (portrait) 62f else 164f
                pill(
                    c,
                    RectF(left, y - 3, left + 35, y + 21),
                    "${zoomSeconds.toInt()} s",
                    "zoomTime",
                    Color.YELLOW,
                )
                var x = left + 39
                for (z in
                    listOf(if (zoomRange.lower < 1f) zoomRange.lower else 1f, 1f, 2f, 3f, 5f)
                        .distinct()
                        .filter { it >= zoomRange.lower && it <= zoomRange.upper }) {
                    pill(
                        c,
                        RectF(x, y - 3, x + 30, y + 21),
                        "${if(z%1==0f)z.toInt().toString()else z.toString()}×",
                        "z:$z",
                    )
                    x += 34
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
                        setZoom(zoom * d.scaleFactor, true)
                        return true
                    }
                },
            )

        override fun onTouchEvent(e: MotionEvent): Boolean {
            scale.onTouchEvent(e)
            if (e.actionMasked != MotionEvent.ACTION_UP || scale.isInProgress) return true
            val x = e.x / s
            val y = e.y / s
            val id = hits.lastOrNull { it.first.contains(x, y) }?.second ?: return true
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
                        "torch" -> {
                            torch = !torch
                            updateRequest()
                        }
                        "settings" -> settings()
                        "hdr" -> activity.message("Recording in SDR · H.264")
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
