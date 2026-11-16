package org.myrec.app

import android.Manifest
import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
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
    private val prefs = activity.captureSettings
    private val horizon=CameraHorizon(activity,cameraHandler)
    internal var faceFollowEnabled=prefs.getBoolean("followFace",false)
        private set
    internal var horizonEnabled=prefs.getBoolean("levelHorizon",false)
        private set
    internal var horizonDegrees=HorizonFraming.degrees(prefs.getFloat("levelHorizonDegrees",9f).toDouble())
        private set
    private var previewHorizonAngle=false
    private var horizonPopup: PopupWindow?=null
    private var assistInputZoom: Float?=null
    private var assistBaseZoom=1f
    private var assistZoomAnimator: ValueAnimator?=null
    private var manualCameraPan=false
    private var dualIds: List<String>?=null
    private val geometry=CameraLayout().apply {
        vertical=prefs.getFloat("layout.vertical",.5f).toDouble(); horizontal=prefs.getFloat("layout.horizontal",.5f).toDouble()
        diagonalFlipped=prefs.getBoolean("layout.diagonalFlipped",false)
        insetX=prefs.getFloat("layout.insetX",.77f).toDouble(); insetY=prefs.getFloat("layout.insetY",.28f).toDouble()
        insetSize=prefs.getFloat("layout.insetSize",.32f).toDouble(); insetAspect=prefs.getFloat("layout.insetAspect",1f).toDouble()
        for (i in 0..1) framing[i]=CameraFraming(prefs.getFloat("layout.$i.x",.5f).toDouble(),prefs.getFloat("layout.$i.y",.5f).toDouble(),prefs.getFloat("layout.$i.zoom",1f).toDouble())
        constrain()
    }
    internal val mirrorPreview get() = prefs.getBoolean("mirrorFrontPreview", true)
    internal val mirrorRecording get() = prefs.getBoolean("mirrorFrontRecording", false)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var request: CaptureRequest.Builder? = null
    @Volatile private var recorder: CaptureRecorder? = null
    private var hdr = false
    private val hdrColor = HdrColor(activity)
    private var colorGeneration = 0
    private var matchingColor = false
    private var recordingSurface: CameraRecordingSurface? = null
    private var composition: CameraComposition? = null
    private var dualCamera: DualCamera? = null
    private var dualLayout = 4
    private var lastDualLayout = prefs.getInt("dualMode",0).coerceIn(0,5).let { if (it==4) 0 else it }
    private var dualMenu = false
    private var settingsPopup: PopupWindow? = null
    private var lightPopup: PopupWindow? = null
    private var dualSwapped = false
    private var dualReady = false
    private var recordingOrientation = 0
    private var recordingDisplayRotation = 0
    private var recordingFile: File? = null
    private var receipt: CaptureReceipt? = null
    private val receiptWorker = Executors.newSingleThreadExecutor()
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
    private val recordingMarks = mutableListOf<Double>()
    private var markedUntil = 0L
    private var since = 0L
    private var amplitude = 0f
    private var micId = prefs.getInt("mic", -1)
    private val microphoneRoute = RecordingMicrophoneRoute()
    private var microphoneRecheck: Runnable? = null
    private var microphoneRecoveryAt = 0L
    private var recordingRequestedAt = 0L
    private var gainPopup: PopupWindow? = null
    private var gainReadout: TextView? = null
    private var signalReadout: TextView? = null
    private var signalMeter: ProgressBar? = null
    private var routeReadout: TextView? = null
    private var gainSlider: SeekBar? = null
    private val microphone = LiveMicrophone(ui,
        changed = { microphoneChanged() },
        failure = { microphoneFailed(it) },
        gainForDevice = { recordingGain(it) },
        sample = { pcm,time -> recorder?.appendAudio(pcm,time) })
    private var communicationRoute = -1
    private var previousAudioMode = AudioManager.MODE_NORMAL
    internal var latestResult: TotalCaptureResult? = null
        private set

    private var focusLocked = false
    private var manualFocus = false
    private var focusDistance = 0f
    private var maximumFocusDistance = 0f
    private var focusPeaking = prefs.getBoolean("focusPeaking", false)
    @Volatile private var focusMode = CaptureRequest.CONTROL_AF_MODE_OFF
    @Volatile
    internal var focusState: Int? = null
        private set

    private var focusTrigger = CaptureRequest.CONTROL_AF_TRIGGER_IDLE
    private var torch = false
    private var screenLight = false
    private var lightWidth = prefs.getFloat("lightWidth", .085f).coerceIn(.015f, .35f)
    private var lightTone = prefs.getString("lightTone", "White") ?: "White"
    private var lightCustom = prefs.getInt("lightCustom", Color.rgb(255, 179, 201))
    private var lightColorPicker: FrontLightColorPicker? = null
    private var customLightSwatch: View? = null
    private var lightOpacity = prefs.getFloat("lightOpacity", 1f).coerceIn(0f, 1f)
    private var previousBrightness: Float? = null
    private var zoom = 1f
    private var requestedZoom: Float? = null
    private var zoomRange = Range(1f, 8f)
    private var zoomSeconds = prefs.getFloat("zoomSeconds", 1f)
    private var zoomAnimator: ValueAnimator? = null
    private var zoomMenu = false
    private var liveEffects = mutableListOf<Effect>()
    private val effectHistory = mutableListOf<Effect>()
    private var effectSince = 0.0
    private var strobePreviewSince = 0L
    private var strobePreviewUntil = 0L
    private var liveFrame: Bitmap? = null
    private var rendering = false
    private var effectGeneration = 0
    private val imageWorker = Executors.newSingleThreadExecutor()
    private var lastZoomTap = 0L
    private var lastZoom = 0f
    private val audioDevices =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                resolveMicrophone()
                overlay.invalidate()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                microphoneRoute.disconnected(removedDevices.map { MicrophoneInput(it).key })
                if (removedDevices.any { it.id == micId } && recording) microphoneRecoveryAt = System.nanoTime()
                resolveMicrophone()
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
                        (microphoneRecoveryAt==0L || System.nanoTime()-microphoneRecoveryAt>3_000_000_000) &&
                        configs.any {
                            it.clientAudioSessionId == microphone.sessionId && it.isClientSilenced
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
                val now = System.nanoTime()
                val arriving = microphone.readings.arriving(now)
                amplitude = if (arriving) ((microphone.readings.peakDB + 60) / 60).coerceIn(0f,1f) else 0f
                updateRecordingAudioControls(now)
                if (arriving && microphone.running && microphone.readings.lastAudio>=microphoneRecoveryAt) microphoneRecoveryAt=0
                if (recording && !paused && !pendingStart && !arriving && now-recordingRequestedAt>1_000_000_000) {
                    val last=microphone.readings.lastAudio.takeIf { it!=Long.MIN_VALUE } ?: recordingRequestedAt
                    val expired=if (microphoneRecoveryAt>0) now-microphoneRecoveryAt>3_000_000_000 else now-last>1_000_000_000
                    if (expired) {
                        pause()
                        activity.message("No microphone audio is arriving. Recording paused; check the microphone before resuming.")
                    }
                }
                if ((liveEffects.any { it.enabled } || focusPeaking && dualLayout == 4) && texture.isAvailable && !rendering)
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        lightColorPicker?.resize()
        if (settingsPopup?.isShowing == true) {
            settingsPopup?.dismiss()
            post { if (!closed) settings() }
        }
        if (lightPopup?.isShowing == true) {
            lightPopup?.dismiss()
            post { if (!closed && screenLight) lightSettings() }
        }
        if (horizonPopup?.isShowing==true) { horizonPopup?.dismiss(); post { if (!closed) horizonSettings() } }
    }

    fun resumeCamera() {
        closed = false
        updateLight()
        microphoneRoute.retry()
        startMeter()
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
        maximumFocusDistance = (chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f).coerceAtLeast(0f)
        focusLocked = prefs.getBoolean("camera.$cameraId.locked", false) && CameraFocus.canLock(focusMode)
        focusDistance = prefs.getFloat("camera.$cameraId.focus", 0f).let { if (it.isFinite()) it.coerceIn(0f, maximumFocusDistance) else 0f }
        manualFocus = focusLocked && maximumFocusDistance > 0 && prefs.contains("camera.$cameraId.focus")
        latestResult = null
        focusState = null
        front =
            chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        zoomRange =
            if (Build.VERSION.SDK_INT >= 30)
                chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: Range(1f, 8f)
            else Range(1f, chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f)
        zoom = (requestedZoom ?: prefs.getFloat("camera.$cameraId.zoom", 1f)).let { if (it.isFinite()) it.coerceIn(zoomRange.lower, zoomRange.upper) else 1f }
        requestedZoom = null
        assistZoomAnimator?.let { assistZoomAnimator=null; it.cancel() }
        manualCameraPan=false
        assistBaseZoom=trackingBase()
        assistInputZoom=if (faceFollowEnabled) assistBaseZoom else null
        if (dualLayout != 4) { zoom = 1f; focusLocked = false; manualFocus = false }
        updateLight()
    }

    private fun rememberLens() {
        if (cameraId.isEmpty() || device == null || dualLayout != 4) return
        val distance = if (manualFocus) focusDistance else latestResult?.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: focusDistance
        prefs.edit().putFloat("camera.$cameraId.zoom", zoom)
            .putBoolean("camera.$cameraId.locked", focusLocked)
            .putFloat("camera.$cameraId.focus", distance.coerceIn(0f, maximumFocusDistance)).apply()
    }

    private fun trackingBase(): Float =
        lenses.filter { it.id==cameraId && it.zoom<=zoom+.001f }.maxOfOrNull { it.zoom } ?: max(1f,zoomRange.lower)

    internal val canFollowFace get() = activeCameraIds().any { CameraFaces.mode(manager.getCameraCharacteristics(it))!=CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF }
    internal val horizonUsesRear get() = activeCameraIds().any { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING)==CameraCharacteristics.LENS_FACING_BACK }
    private fun activeCameraIds()=if (dualLayout!=4) selectedPair().orEmpty() else listOf(cameraId).filter { it.isNotEmpty() }

    private fun updateAssistance() {
        val level=(horizonEnabled || previewHorizonAngle) && horizonUsesRear && !closed
        horizon.configure(level)
        composition?.assists(faceFollowEnabled,if (level) horizonDegrees else null,prefs.getBoolean("stabilization",true),zoom.toDouble(),
            assistBaseZoom.toDouble(),assistInputZoom!=null)
    }

    internal fun setFaceFollow(enabled: Boolean,manualPan: Boolean=false) {
        if (enabled && !canFollowFace) { activity.message("Face following is unavailable on these cameras"); return }
        assistZoomAnimator?.let { assistZoomAnimator=null; it.cancel() }
        if (manualPan && dualLayout==4) {
            zoom=(composition?.singleNativeZoom()?.toFloat() ?: zoom).coerceIn(zoomRange.lower,zoomRange.upper)
            manualCameraPan=true
        } else if (enabled) manualCameraPan=false
        faceFollowEnabled=enabled; prefs.edit().putBoolean("followFace",enabled).apply()
        if (dualLayout==4) {
            if (enabled) { assistBaseZoom=trackingBase(); assistInputZoom=assistBaseZoom }
            else if (!manualPan && assistInputZoom!=null) {
                val animation=ValueAnimator.ofFloat(assistInputZoom!!,zoom).apply { duration=650 }
                assistZoomAnimator=animation
                animation.addUpdateListener { assistInputZoom=it.animatedValue as Float; updateRequest() }
                animation.addListener(object: AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animator: Animator) {
                        if (assistZoomAnimator===animation) {
                            assistZoomAnimator=null; manualCameraPan=false
                            val lens=cameraId
                            fun settle(attempt: Int) {
                                if (closed || cameraId!=lens || faceFollowEnabled || manualCameraPan || assistZoomAnimator!=null) return
                                val actual=latestResult?.let { CameraFaces.zoom(manager.getCameraCharacteristics(lens),it) }
                                if (attempt<15 && actual!=null && abs(actual-zoom)>.005*zoom) { ui.postDelayed({ settle(attempt+1) },33); return }
                                assistInputZoom=null; updateRequest()
                            }
                            settle(0)
                        }
                    }
                })
                animation.start()
            }
        }
        updateRequest(); updateAssistance(); overlay.invalidate()
    }
    internal fun setHorizonEnabled(enabled: Boolean) {
        if (recording && !paused) return
        if (enabled && (!horizon.available || !horizonUsesRear)) return
        horizonEnabled=enabled; prefs.edit().putBoolean("levelHorizon",enabled).apply()
        updateAssistance(); overlay.invalidate()
    }
    internal fun setHorizonDegrees(value: Double) {
        if (recording && !paused) return
        horizonDegrees=HorizonFraming.degrees(value)
        prefs.edit().putFloat("levelHorizonDegrees",horizonDegrees.toFloat()).apply()
        updateAssistance(); overlay.invalidate()
    }
    private fun selectedPair(): List<String>? {
        val pairs=DualCamera.pairs(manager)
        val desired=dualIds ?: prefs.getString("dualPair",null)?.split('|')
        return desired?.takeIf { ids -> ids.size==2 && pairs.any { it.toSet()==ids.toSet() } }?.also { dualIds=it }
            ?: DualCamera.pair(manager)?.also { dualIds=it }
    }
    private fun rememberLayout() {
        val g=composition?.layoutState() ?: geometry.copy()
        val edit=prefs.edit().putInt("dualMode",lastDualLayout)
            .putFloat("layout.vertical",g.vertical.toFloat()).putFloat("layout.horizontal",g.horizontal.toFloat())
            .putBoolean("layout.diagonalFlipped",g.diagonalFlipped)
            .putFloat("layout.insetX",g.insetX.toFloat()).putFloat("layout.insetY",g.insetY.toFloat())
            .putFloat("layout.insetSize",g.insetSize.toFloat()).putFloat("layout.insetAspect",g.insetAspect.toFloat())
        for (i in 0..1) edit.putFloat("layout.$i.x",g.framing[i].x.toFloat()).putFloat("layout.$i.y",g.framing[i].y.toFloat()).putFloat("layout.$i.zoom",g.framing[i].zoom.toFloat())
        edit.apply()
    }

    internal val focusAdjustable get() = maximumFocusDistance > 0 && CameraFocus.canLock(focusMode)
    internal val focusPosition get() = if (maximumFocusDistance > 0)
        1f - (if (manualFocus) focusDistance else latestResult?.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f) / maximumFocusDistance else 1f
    internal val currentZoom get() = zoom
    internal val manualFocusActive get() = manualFocus

    internal fun setFocusPosition(position: Float, persist: Boolean = true) {
        if (!focusAdjustable || !position.isFinite()) return
        focusLocked = true
        manualFocus = true
        focusDistance = (1f - position.coerceIn(0f, 1f)) * maximumFocusDistance
        focusTrigger = CaptureRequest.CONTROL_AF_TRIGGER_IDLE
        updateRequest()
        if (persist) rememberLens()
        overlay.invalidate()
    }

    internal fun toggleFocusPeaking() {
        focusPeaking = !focusPeaking
        prefs.edit().putBoolean("focusPeaking", focusPeaking).apply()
        effectGeneration++
        liveFrame?.recycle(); liveFrame = null
        overlay.invalidate()
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
                        if (closed || dualLayout!=4 || pendingSwitch != null) {
                            camera.close()
                            val next=pendingSwitch
                            pendingSwitch=null
                            if (!closed) ui.post { if (next!=null && dualLayout==4) open(next) else openDefault() }
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
                ui,
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
            if (composition==null) composition=newComposition()
            composition!!.clearInputs()
            val chars=manager.getCameraCharacteristics(cameraId)
            val surfaces=mutableListOf(composition!!.input(captureSize.width,captureSize.height,
                chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,front,hdrColor.profile(cameraId),cameraId,
                Build.VERSION.SDK_INT<33 && chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)==CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
                (assistInputZoom ?: zoom).toDouble()))
            updateAssistance()
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
                        mirror = front && mirrorRecording,
                        outputRotation = recordingOrientation,
                        fitFrame = recordingTurned(),
                    )
                )
            }
            request =
                camera.createCaptureRequest(
                    CameraDevice.TEMPLATE_RECORD
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
                                since = SystemClock.elapsedRealtime()
                            } catch (e: Exception) {
                                ui.post {
                                    recording = false
                                    closeRecordingSurface()
                                    recorder?.close()
                                    recorder = null
                                    recordingFile?.takeIf { it.length() == 0L }?.delete()
                                    receipt?.let { runCatching { store.abandonCapture(it) } }
                                    receipt = null
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
                            if (device!==camera) { s.close(); return@post }
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
                            if (index > 0 || composition!=null) {
                                setMirrorMode(OutputConfiguration.MIRROR_MODE_NONE)
                                setTimestampBase(OutputConfiguration.TIMESTAMP_BASE_MONOTONIC)
                            }
                        }
                    }
                camera.createCaptureSession(
                    SessionConfiguration(
                            SessionConfiguration.SESSION_REGULAR,
                            outputs,
                            { command -> ui.post(command) },
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
            } else camera.createCaptureSession(surfaces, callback, ui)
        } catch (e: Exception) {
            ui.post {
                if (device!==camera) return@post
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
        updateAssistance()
        dualCamera?.let {
            it.update(zoom, focusLocked, torch && !front, selectedPair()?.indexOf(cameraId)?.coerceAtLeast(0) ?: 0,faceFollowEnabled,prefs.getBoolean("stabilization",true))
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
            r.set(CaptureRequest.CONTROL_AF_MODE, if (manualFocus) CaptureRequest.CONTROL_AF_MODE_OFF else focusMode)
            if (manualFocus) r.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
            r.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            r.set(CaptureRequest.CONTROL_AE_LOCK, false)
            val characteristics=manager.getCameraCharacteristics(cameraId)
            r.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE,if (faceFollowEnabled) CameraFaces.mode(characteristics) else CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF)
            val inputZoom=assistInputZoom ?: zoom
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
            if (Build.VERSION.SDK_INT >= 30) r.set(CaptureRequest.CONTROL_ZOOM_RATIO, inputZoom)
            else
                manager
                    .getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    ?.let { a ->
                        val w = (a.width() / inputZoom).toInt()
                        val h = (a.height() / inputZoom).toInt()
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
                        if (session !== this@Capture.session) return
                        latestResult = result
                        focusState = result.get(CaptureResult.CONTROL_AF_STATE)
                        composition?.faces(cameraId,if (faceFollowEnabled) CameraFaces.boxes(characteristics,result,captureSize.width,captureSize.height) else emptyList(),CameraFaces.time(characteristics,result),CameraFaces.zoom(characteristics,result))
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
            it.mirrors(mirrorPreview, mirrorRecording)
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
        if (front && !mirrorPreview) matrix.postScale(-1f, 1f, w / 2, h / 2)
        texture.setTransform(matrix)
    }

    internal fun setMirrorPreview(enabled: Boolean) {
        prefs.edit().putBoolean("mirrorFrontPreview", enabled).apply()
        effectGeneration++
        liveFrame?.recycle(); liveFrame = null
        transform()
    }

    internal fun setMirrorRecording(enabled: Boolean) {
        if (recording) {
            activity.message("Stop recording to change saved selfie mirroring")
            return
        }
        prefs.edit().putBoolean("mirrorFrontRecording", enabled).apply()
        effectGeneration++
        liveFrame?.recycle(); liveFrame = null
        transform()
    }

    internal fun setZoom(value: Float, instant: Boolean = false) {
        val to = value.coerceIn(zoomRange.lower, zoomRange.upper)
        zoomAnimator?.cancel()
        if (instant || zoomSeconds == 0f) {
            zoom = to
            rememberLens()
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
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { rememberLens() }
                })
                start()
            }
    }

    private fun choosePriority() { resolveMicrophone() }

    private fun recordingGain(device: AudioDeviceInfo): Float {
        val input = MicrophoneInput(device)
        return RecordingGain.normalized(prefs.getFloat("recordingGain." + input.gainKey(microphoneDevices().map { MicrophoneInput(it) }),0f))
    }

    internal fun setRecordingGain(value: Float) {
        val device = microphone.actual ?: return
        val input = MicrophoneInput(device)
        val gain = RecordingGain.normalized(value)
        prefs.edit().putFloat("recordingGain." + input.gainKey(microphoneDevices().map { MicrophoneInput(it) }),gain).apply()
        microphone.gain.set(gain)
        microphone.readings.clearLevel()
        updateRecordingAudioControls(System.nanoTime())
    }

    private fun microphoneChanged() {
        microphone.actual?.let { device ->
            micId = device.id
            prefs.edit().putInt("mic",micId).apply()
        }
        resolveMicrophone()
        updateRecordingAudioControls(System.nanoTime())
        overlay.invalidate()
    }

    private fun microphoneFailed(error: Throwable) {
        if (closed) return
        if (recording && microphoneRecoveryAt==0L) microphoneRecoveryAt=System.nanoTime()
        resolveMicrophone()
        if (!recording) activity.message(error.message ?: "Microphone is unavailable")
    }

    private fun resolveMicrophone() {
        if (closed || activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) return
        microphoneRecheck?.let { ui.removeCallbacks(it) }
        microphoneRecheck = null
        val devices = microphoneDevices()
        val inputs = devices.map { MicrophoneInput(it) }
        val actual = microphone.actual?.let { MicrophoneInput(it).key }
        val saved = prefs.getString("priorities","").orEmpty().split(',').filter { it.isNotEmpty() }
        val canonical = saved.map { key ->
            inputs.firstOrNull { it.key==key }?.key
                ?: inputs.firstOrNull { it.legacyKey==key && it.key==actual }?.key
                ?: inputs.firstOrNull { it.legacyKey==key }?.key ?: key
        }
        val priorities = RecordingMicrophoneRoute.priority(canonical,inputs.map { it.key },actual)
        val edit = prefs.edit()
        if (priorities!=saved) edit.putString("priorities",priorities.joinToString(","))
        for (input in inputs) edit.putString("microphoneName."+input.key,input.label)
        edit.apply()
        val ranked = priorities.mapNotNull { key -> inputs.firstOrNull { it.key==key || it.legacyKey==key }?.key }.distinct()
        val decision = microphoneRoute.resolve(ranked,actual,microphone.requested?.let { MicrophoneInput(it).key },recording,System.nanoTime()/1e9)
        if (decision.request!=null) {
            devices.firstOrNull { MicrophoneInput(it).key==decision.request }?.let { device ->
                if (recording && microphoneRecoveryAt==0L) microphoneRecoveryAt=System.nanoTime()
                if (routeMicrophone(device)) runCatching {
                    microphone.start(device,if (bluetooth(device)) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.CAMCORDER)
                }
            }
        }
        if (decision.disconnected!=null) {
            val old = prefs.getString("microphoneName."+decision.disconnected,"Microphone")
            activity.message("$old disconnected. Using ${micName()}.")
        }
        if (decision.failed!=null) {
            val desired = prefs.getString("microphoneName."+decision.failed,"the selected microphone")
            activity.message("Could not switch to $desired. Using ${micName()}.")
        }
        decision.recheck?.let { delay ->
            val next = Runnable { resolveMicrophone() }
            microphoneRecheck=next
            ui.postDelayed(next,(delay*1000).roundToLong().coerceAtLeast(50))
        }
    }

    internal fun microphoneDevices() = MicrophoneInput.devices(audioManager)

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

    private fun updateLight(enabled: Boolean = torch && !closed && (front || dualLayout != 4)) {
        screenLight = enabled
        val window = activity.window
        val attributes = window.attributes
        if (screenLight && lightOpacity > 0) {
            if (previousBrightness == null) previousBrightness = attributes.screenBrightness
            attributes.screenBrightness = 1f
        } else {
            attributes.screenBrightness = previousBrightness ?: return
            previousBrightness = null
        }
        window.attributes = attributes
        overlay.invalidate()
    }

    private fun lightColor(tone: String = lightTone) = when (tone) {
        "Warm" -> Color.rgb(255, 209, 158)
        "Cool" -> Color.rgb(184, 222, 255)
        "Custom" -> lightCustom or Color.BLACK
        else -> Color.WHITE
    }

    internal fun setFrontLight(width: Float? = null, tone: String? = null, opacity: Float? = null, color: Int? = null) {
        width?.takeIf { it.isFinite() }?.let { lightWidth = it.coerceIn(.015f, .35f) }
        tone?.takeIf { it in listOf("White", "Warm", "Cool", "Custom") }?.let { lightTone = it }
        opacity?.takeIf { it.isFinite() }?.let { lightOpacity = it.coerceIn(0f, 1f) }
        color?.let { lightCustom = it or Color.BLACK; lightTone = "Custom" }
        prefs.edit().putFloat("lightWidth", lightWidth).putString("lightTone", lightTone)
            .putFloat("lightOpacity", lightOpacity).putInt("lightCustom", lightCustom).apply()
        updateLight()
    }

    private fun lightSettings() {
        lightPopup?.dismiss()
        val landscape = width > height
        val u = InspectorUi(activity, min(width / (if (landscape) 640f else 294f), height / (if (landscape) 294f else 640f)))
        val cyan = Color.rgb(77, 211, 239)
        val body = u.column().apply {
            background = u.shape(Color.rgb(29, 29, 31), 14f)
            setPadding(u.px(12f), u.px(6f), u.px(12f), u.px(10f))
        }
        val popup = PopupWindow(body, u.px(210f), -2, true).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true; elevation = u.px(12f).toFloat()
        }
        lightPopup = popup
        fun render() {
            body.removeAllViews()
            val header = u.row()
            header.addView(u.text("Front light", 12f, bold = true), LinearLayout.LayoutParams(0, u.px(30f), 1f))
            header.addView(u.text("Done", 10f, cyan).apply {
                contentDescription = "Close front light controls"; gravity = Gravity.CENTER
                setOnClickListener { popup.dismiss() }
            }, LinearLayout.LayoutParams(u.px(36f), u.px(30f)))
            u.add(body, header)
            u.add(body, u.text("Border width", 10f, bold = true), 25f)
            val widths = linkedMapOf("Thin" to .045f, "Medium" to .085f, "Wide" to .14f)
            u.segments(body, widths.keys.toList(), widths.entries.firstOrNull { abs(it.value - lightWidth) < .001 }?.key ?: "") { name ->
                setFrontLight(width = widths.getValue(name)); render()
            }
            u.gap(body, 7f); u.add(body, u.text("Color", 10f, bold = true), 23f)
            val colors = u.row()
            for (tone in listOf("White", "Warm", "Cool", "Custom")) {
                val button = u.column().apply {
                    gravity = Gravity.CENTER; contentDescription = tone; isSelected = tone == lightTone; isFocusable = true
                    val swatch = object : View(activity) {
                        private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
                        override fun onDraw(canvas: Canvas) {
                            val r = min(width, height) / 2f - u.px(2f)
                            val x = width / 2f; val y = height / 2f
                            pen.shader = null; pen.style = Paint.Style.FILL; pen.color = lightColor(tone)
                            canvas.drawCircle(x, y, r, pen)
                            if (tone == "Custom") {
                                pen.style = Paint.Style.STROKE; pen.strokeWidth = u.px(3f).toFloat()
                                pen.shader = SweepGradient(x, y, intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED), null)
                                canvas.drawCircle(x, y, r, pen); pen.shader = null
                            }
                            if (tone == lightTone) {
                                pen.style = Paint.Style.STROKE; pen.strokeWidth = u.px(2f).toFloat()
                                pen.color = if (tone == "Custom") Color.BLACK else Color.rgb(20, 20, 20)
                                val mark = Path().apply { moveTo(x - r * .42f, y); lineTo(x - r * .1f, y + r * .3f); lineTo(x + r * .48f, y - r * .34f) }
                                canvas.drawPath(mark, pen)
                            }
                        }
                    }
                    if (tone=="Custom") customLightSwatch=swatch
                    addView(swatch, LinearLayout.LayoutParams(u.px(24f), u.px(24f)))
                    addView(u.text(tone, 8f).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, u.px(18f)))
                    setOnClickListener {
                        if (tone == "Custom") {
                            setFrontLight(tone = tone); render()
                            lightColorPicker?.dismiss()
                            lightColorPicker=FrontLightColorPicker(activity,lightCustom,u.scale) { color -> setFrontLight(color = color); customLightSwatch?.invalidate() }.apply {
                                setOnDismissListener { lightColorPicker=null }
                                show()
                            }
                        }
                        else { setFrontLight(tone = tone); render() }
                    }
                }
                colors.addView(button, LinearLayout.LayoutParams(0, u.px(46f), 1f))
            }
            u.add(body, colors)
            val level = u.row()
            level.addView(u.text("Opacity", 10f), LinearLayout.LayoutParams(0, u.px(25f), 1f))
            val percent = u.text("${(lightOpacity * 100).roundToInt()}%", 10f, Color.LTGRAY)
            level.addView(percent); u.add(body, level)
            val bar = ReferenceSlider(activity, u.scale).apply {
                max = 20; progress = (lightOpacity * 20).roundToInt(); contentDescription = "Front light opacity"
                progressTintList = android.content.res.ColorStateList.valueOf(cyan)
                thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(view: SeekBar, value: Int, user: Boolean) {
                        if (user) { setFrontLight(opacity = value / 20f); percent.text = "${value * 5}%" }
                    }
                    override fun onStartTrackingTouch(view: SeekBar) {}
                    override fun onStopTrackingTouch(view: SeekBar) {}
                })
            }
            u.add(body, bar, 26f); u.divider(body)
            u.add(body, u.action("Turn off front light", "Light", Color.WHITE) { popup.dismiss(); setTorch(false) }, 34f)
        }
        render()
        popup.setOnDismissListener { if (lightPopup === popup) lightPopup = null }
        popup.showAtLocation(this, Gravity.TOP or if (landscape) Gravity.LEFT else Gravity.RIGHT, u.px(6f), u.px(if (landscape) 12f else 42f))
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
        val pair = selectedPair() ?: return
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
        microphone.actual?.let { MicrophoneInput(it).label } ?: "Microphone unavailable"

    private fun startMeter() { if (!closed) resolveMicrophone() }

    private fun stopMeter() {
        microphoneRecheck?.let { ui.removeCallbacks(it) }
        microphoneRecheck=null
        microphone.stop()
        releaseMicrophoneRoute()
    }

    internal val readyToRecord get() = !matchingColor && (device != null && session!=null || dualReady)

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
        val selected = selectedMicrophone()
        if (selected == null || !routeMicrophone(selected)) {
            activity.message("The selected microphone is unavailable. Choose another microphone.")
            return
        }
        if (
            bluetooth(selected) &&
                Build.VERSION.SDK_INT >= 31 &&
                audioManager.communicationDevice?.type != selected.type
        ) {
            activity.message("Waiting for the selected microphone")
            return
        }
        if (!activity.saveProject()) return
        if (!microphone.running || !microphone.readings.arriving(System.nanoTime())) {
            startMeter()
            activity.message("Waiting for the selected microphone")
            return
        }
        microphone.actual?.let { microphoneRoute.select(MicrophoneInput(it).key) }
        if (composition==null) { session?.close(); session=null }
        val file=File(store.media,"recording-${System.currentTimeMillis()}.mp4")
        var next: CaptureRecorder?=null
        try {
            receipt=store.beginCapture(activity.editor.project,file,liveEffects,activity.recordingInsertion())
            recordingDisplayRotation=activity.windowManager.defaultDisplay.rotation
            recordingOrientation=cameraOrientation()
            val r=CaptureRecorder(file,videoSize.width,videoSize.height,prefs.getInt("fps",30),recordingOrientation,hdr,activity.audioCache) { error ->
                ui.post {
                    if (recording) stop()
                    activity.message(error.message ?: "Recording interrupted. Completed footage has been kept.")
                }
            }
            next=r; recorder=r
            if (composition!=null) composition!!.record(r.surface,videoSize.width,videoSize.height,recordingOrientation,prefs.getInt("fps",30),r::videoFrame)
            else recordingSurface=CameraRecordingSurface(r.surface,videoSize.width,videoSize.height,
                r::videoFrame) { error -> ui.post { if (recording) stop(); activity.message(error.message ?: "Camera frame processing failed") } }
            recordingFile=file
            recording=true; paused=false; duration=0
            recordingMarks.clear(); effectHistory.clear(); effectSince=0.0
            since=SystemClock.elapsedRealtime()
            recordingRequestedAt=System.nanoTime(); microphoneRecoveryAt=0
            if (composition!=null) { r.start(); since=SystemClock.elapsedRealtime() }
            else { pendingStart=true; createSession() }
        } catch (e: Exception) {
            recorder=null; next?.close(); closeRecordingSurface()
            if (file.length()==0L) file.delete()
            receipt?.let { runCatching { store.abandonCapture(it) } }; receipt=null
            recording=false; pendingStart=false
            createSession(); startMeter()
            activity.message(e.message ?: "Cannot start recording")
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
                        selectedPair()?.all { HdrRecorder.supported(manager, it) } !=
                            true))
        ) {
            activity.message("HDR recording is unavailable for this camera combination")
            return
        }
        if (enabled) HdrGraphics.support.reason?.let {
            activity.message(it)
            return
        }
        val ids = if (dualCamera != null) selectedPair().orEmpty() else listOf(cameraId)
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
                                        "MyRec-HDR",
                                        "Camera ${ids[index]} colour match: ${result.toList()}",
                                    )
                                    result[0].toInt()
                                }
                            } catch (e: Exception) {
                                android.util.Log.w(
                                    "MyRec-HDR",
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
        if (recording && !paused) recorder?.elapsedSeconds ?: (duration+SystemClock.elapsedRealtime()-since)/1000.0 else duration/1000.0

    internal fun addMark() {
        if (!recording || pendingStart) return
        val at = elapsedSeconds()
        if (recordingMarks.none { abs(it - at) < .1 }) recordingMarks.add(at)
        markedUntil = SystemClock.elapsedRealtime() + 800
        checkpointReceipt()
        overlay.invalidate()
    }

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
                if (!microphone.running) { microphoneRoute.retry(); startMeter(); activity.message("Waiting for the selected microphone"); return }
                recordingRequestedAt=System.nanoTime(); microphoneRecoveryAt=recordingRequestedAt
                recorder?.resume()
                since = SystemClock.elapsedRealtime()
                paused = false
            } else {
                recorder?.pause()
                duration=(recorder?.pausedSeconds?.times(1000))?.roundToLong() ?: (duration+SystemClock.elapsedRealtime()-since)
                paused = true
            }
        } catch (e: Exception) {
            activity.message(e.message ?: "Cannot pause recording")
        }
        checkpointReceipt()
        overlay.invalidate()
    }

    fun stop() {
        if (!recording) return
        session?.close()
        session = null
        pendingStart = false
        val r = recorder
        finishEffectRange(elapsedSeconds())
        recording = false
        paused = false
        // Detach the camera before closing the encoder surface.
        runCatching { composition?.record(null) }
        var stopFailure: Exception?=null
        try {
            r?.stop()
        } catch (e: Exception) {
            stopFailure=e
            android.util.Log.w("MyRec","Capture stopped before merging",e)
            // Probe any completed media below even when the encoder reports failure.
        } finally {
            recorder = null
            closeRecordingSurface()
            r?.close()
        }
        val file = recordingFile
        recordingFile = null
        val completed = receipt
        receipt = null
        if (file != null && file.length() > 0)
            try {
                val inspected = ProjectStore.inspect(file)
                val clip = if (completed == null) inspected else inspected.copy(id = completed.id, group = completed.id, occurrence = completed.id)
                clip.marks.addAll(recordingMarks.filter { it < clip.sourceOut })
                effectHistory.forEach { clip.effects.add(it.copyEffect(true)) }
                if (completed != null) store.finishCapture(completed, clip)
                if (completed == null || completed.project == activity.editor.project.id) onClip(clip)
                else {
                    val target = store.editor(Project(id = completed.project))
                    if (completed.insertion == null) target.appendRecording(clip)
                    else target.insertBatch(listOf(clip), completed.insertion, completed.layer)
                    store.save(target, false)
                    activity.message("Take saved in ${target.project.name}")
                }
                if (completed != null) store.acknowledgeCapture(completed)
                val project = completed?.project ?: activity.editor.project.id
                receiptWorker.execute {
                    try { store.registerMedia(project, clip.path) }
                    catch (e: Exception) {
                        store.problems["checksum:$project"] = "Source verification could not finish: ${e.message}"
                    }
                }
            } catch (e: Exception) {
                activity.message("Recording kept for recovery: ${e.message}")
            }
        else {
            file?.delete()
            completed?.let { runCatching { store.abandonCapture(it) } }
            activity.message(stopFailure?.message ?: "Recording was too short to save")
        }
        activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        if (dualLayout == 4 && dualCamera != null) {
            closeCamera()
            openDefault()
        } else createSession()
        microphoneRoute.reset()
        startMeter()
        overlay.invalidate()
    }

    internal fun switchCamera(id: String, preset: Float? = null) {
        if (dualCamera != null || dualLayout != 4) {
            val pair = selectedPair() ?: return
            if (id !in pair) {
                activity.message("Choose a main camera while using Split view")
                return
            }
            cameraId = id
            lensState(manager.getCameraCharacteristics(id))
            dualSwapped = id==pair[1]
            transform()
            updateRequest()
            overlay.invalidate()
            return
        }
        zoomAnimator?.cancel()
        rememberLens()
        requestedZoom = preset
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
        val result=CameraComposition(
            Surface(texture.surfaceTexture),
            texture.width,
            texture.height,
            hdr,geometry,horizon::gravity,
        ) {
            ui.post {
                if (recording) stop()
                activity.message(it.message ?: "Camera composition failed")
            }
        }
        if (recording) recorder?.let { result.record(it.surface,videoSize.width,videoSize.height,recordingOrientation,prefs.getInt("fps",30),it::videoFrame) }
        return result
    }

    private fun openDual() {
        if (closed || dualCamera != null || !texture.isAvailable) return
        val pair = selectedPair()
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
        if (!recording) videoSize =
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
        require(mode in 0..5)
        if (mode != 4) lastDualLayout = mode
        if (mode != 4 && selectedPair() == null) {
            activity.message("This phone does not support simultaneous front and rear cameras")
            return
        }
        if (recording && dualCamera != null && mode!=4) {
            dualLayout = mode
            transform()
        } else if ((mode == 4) != (dualLayout == 4)) {
            if (mode != 4) dualSwapped = cameraId==selectedPair()?.getOrNull(1)
            if (recording && !paused) {
                activity.message("Pause recording to change the camera setup")
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
        updateAssistance(); rememberLayout()
        overlay.invalidate()
    }

    private fun cameraName(id: String)=lenses.firstOrNull { it.id==id && it.zoom==1f }?.name
        ?: (if (manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)==CameraCharacteristics.LENS_FACING_FRONT) "Front camera " else "Rear camera ")+id

    internal fun selectDualCamera(slot: Int,id: String) {
        if (recording && !paused) return
        val current=selectedPair() ?: return
        val other=current[if (dualSwapped) slot else 1-slot]
        val candidates=DualCamera.pairs(manager).filter { id in it }
        val next=candidates.firstOrNull { other in it && other!=id } ?: candidates.firstOrNull { it.toSet()==current.toSet() } ?: candidates.firstOrNull() ?: return
        if (hdr && next.any { !HdrRecorder.supported(manager,it) }) { activity.message("This camera combination does not support HDR"); return }
        dualSwapped=if (slot==0) next[1]==id else next[0]==id
        if (next.toSet()!=current.toSet()) {
            closeCamera(); dualIds=next; cameraId=next[if (dualSwapped) 1 else 0]
            prefs.edit().putString("dualPair",next.joinToString("|")).apply()
            openDefault()
        } else switchCamera(next[if (dualSwapped) 1 else 0])
        overlay.invalidate()
    }

    private fun chooseDualCamera(slot: Int,done: ()->Unit) {
        val current=selectedPair() ?: return
        val first=current[if (dualSwapped) 1 else 0]
        val ids=DualCamera.pairs(manager).filter { slot==0 || first in it }.flatten().distinct().filter { slot==0 || it!=first }
        val selected=current[if (dualSwapped) 1-slot else slot]
        activity.choose(if (slot==0) "First camera" else "Second camera",ids.map(::cameraName),ids.indexOf(selected)) { i -> selectDualCamera(slot,ids[i]); done() }
    }

    private fun horizonSettings() {
        horizonPopup?.dismiss()
        previewHorizonAngle=!recording || paused; updateAssistance()
        val landscape=width>height
        val u=InspectorUi(activity,min(width/(if (landscape) 640f else 294f),height/(if (landscape) 294f else 640f)))
        val body=u.column().apply { setPadding(u.px(16f),0,u.px(16f),u.px(12f)); background=u.shape(Color.rgb(29,29,31),16f) }
        val row=u.row(); row.addView(u.text("Max angle",12f,bold=true),LinearLayout.LayoutParams(0,u.px(44f),1f))
        val value=u.text("",12f,Color.CYAN,true); row.addView(value)
        row.addView(u.text("Done",11f,Color.CYAN).apply { gravity=Gravity.CENTER; contentDescription="Max angle done"; setOnClickListener { horizonPopup?.dismiss() } },LinearLayout.LayoutParams(u.px(48f),u.px(44f)))
        u.add(body,row)
        val status=u.text("",9f,Color.LTGRAY)
        fun refresh() { value.text="%.1f°".format(horizonDegrees); status.text="%.2f× framing".format(HorizonFraming.scale(horizonDegrees,videoSize.width.toDouble(),videoSize.height.toDouble()))+if (horizonEnabled) "" else " · Preview only, Level horizon is off" }
        u.add(body,ReferenceSlider(activity,u.scale).apply {
            max=19; progress=((horizonDegrees-.5)*2).roundToInt(); contentDescription="Max angle"
            progressTintList=android.content.res.ColorStateList.valueOf(Color.CYAN)
            setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(view: SeekBar,value: Int,user: Boolean) { if (user) { setHorizonDegrees(.5+value*.5); refresh() } }
                override fun onStartTrackingTouch(view: SeekBar) {}
                override fun onStopTrackingTouch(view: SeekBar) {}
            })
        },34f)
        u.add(body,status,28f); refresh()
        horizonPopup=PopupWindow(body,min(width-u.px(16f),u.px(360f)),-2,true).apply {
            setBackgroundDrawable(u.shape(Color.rgb(29,29,31),16f)); elevation=u.px(12f).toFloat()
            setOnDismissListener { horizonPopup=null; previewHorizonAngle=false; updateAssistance() }
            showAtLocation(this@Capture,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,0,u.px(12f))
        }
    }

    private fun cameras() {
        lenses = CameraLenses.available(manager)
        activity.choose(
            "Camera",
            lenses.map { it.name },
            CameraLenses.selected(lenses, cameraId, zoom),
        ) { i ->
            val lens = lenses[i]
            if (lens.id == cameraId) setZoom(lens.zoom, true)
            else switchCamera(lens.id, lens.zoom.takeIf { it != 1f })
        }
    }

    private fun recordingAudioControls() {
        gainPopup?.dismiss()
        val landscape=width>height
        val scale=min(width/(if (landscape) 640f else 294f),height/(if (landscape) 294f else 640f))
        val u=InspectorUi(activity,scale)
        val body=u.column().apply { setPadding(u.px(18f),u.px(14f),u.px(18f),u.px(14f)); background=u.shape(Color.rgb(22,22,24),14f) }
        val header=u.row()
        header.addView(u.text("Recording volume",14f,bold=true),LinearLayout.LayoutParams(0,u.px(44f),1f))
        val about=u.text("ⓘ",17f,Panels.RED).apply {
            gravity=Gravity.CENTER; contentDescription="About recording volume"; isFocusable=true
        }
        header.addView(about,LinearLayout.LayoutParams(u.px(38f),u.px(44f)))
        u.add(body,header)
        val values=u.row()
        gainReadout=u.text("",15f,bold=true).also { values.addView(it,LinearLayout.LayoutParams(0,u.px(40f),1f)) }
        values.addView(u.action("Reset to 0") { setRecordingGain(0f) },LinearLayout.LayoutParams(u.px(90f),u.px(38f)))
        u.add(body,values)
        gainSlider=ReferenceSlider(activity,scale).apply {
            max=104; progress=((microphone.gain.db+40)*2).roundToInt()
            contentDescription="Recording gain"; minimumHeight=u.px(44f)
            progressTintList=android.content.res.ColorStateList.valueOf(Color.CYAN)
            setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(view: SeekBar, value: Int, user: Boolean) { if (user) setRecordingGain(value*.5f-40) }
                override fun onStartTrackingTouch(view: SeekBar) {}
                override fun onStopTrackingTouch(view: SeekBar) {}
            })
        }.also { u.add(body,it,44f) }
        val signal=u.row()
        signalMeter=ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal).apply {
            max=600; progressBackgroundTintList=android.content.res.ColorStateList.valueOf(Color.DKGRAY)
            contentDescription="Microphone level"
        }.also { signal.addView(it,LinearLayout.LayoutParams(0,u.px(6f),1f).apply { rightMargin=u.px(10f) }) }
        signalReadout=u.text("No signal",10f).apply { gravity=Gravity.RIGHT or Gravity.CENTER_VERTICAL }
            .also { signal.addView(it,LinearLayout.LayoutParams(u.px(75f),u.px(30f))) }
        u.add(body,signal)
        routeReadout=u.text(micName()+"  ›",11f,Panels.RED).apply {
            contentDescription="Choose recording microphone"; isFocusable=true; setOnClickListener { microphones() }
        }.also { u.add(body,it,44f) }
        val explanation=u.text("Changes the volume of new recordings, including during a take. Remembered for each microphone. Red means the signal may distort; lowering gain cannot repair distortion already present in the microphone.",10f,Color.LTGRAY).apply { visibility=View.GONE }
        about.setOnClickListener {
            explanation.visibility=if (explanation.visibility==View.GONE) View.VISIBLE else View.GONE
            gainPopup?.let { popup ->
                popup.contentView.measure(MeasureSpec.makeMeasureSpec(popup.width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(height-u.px(48f),MeasureSpec.AT_MOST))
                popup.update(popup.width,popup.contentView.measuredHeight)
            }
        }
        u.add(body,explanation)
        val scroll=ScrollView(activity).apply { addView(body) }
        val popupWidth=min(width-u.px(24f),u.px(310f))
        scroll.measure(MeasureSpec.makeMeasureSpec(popupWidth,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(height-u.px(48f),MeasureSpec.AT_MOST))
        gainPopup=PopupWindow(scroll,popupWidth,scroll.measuredHeight,true).apply {
            setBackgroundDrawable(u.shape(Color.rgb(22,22,24),14f)); elevation=u.px(12f).toFloat()
            setOnDismissListener { gainPopup=null; gainReadout=null; signalReadout=null; signalMeter=null; routeReadout=null; gainSlider=null }
            showAtLocation(this@Capture,Gravity.CENTER,0,0)
        }
        updateRecordingAudioControls(System.nanoTime())
    }

    private fun updateRecordingAudioControls(now: Long) {
        val clipping=microphone.readings.clipping(now)
        val gain=microphone.gain.db
        gainReadout?.text="%+.1f dB".format(gain)
        gainReadout?.setTextColor(if (clipping) Panels.RED else Color.WHITE)
        gainSlider?.let {
            it.isEnabled=microphone.actual!=null
            if (!it.isPressed) it.progress=((gain+40)*2).roundToInt()
            it.progressTintList=android.content.res.ColorStateList.valueOf(if (clipping) Panels.RED else Color.CYAN)
        }
        signalMeter?.let {
            it.progress=if (microphone.readings.arriving(now)) ((microphone.readings.peakDB+60)*10).roundToInt().coerceIn(0,600) else 0
            it.progressTintList=android.content.res.ColorStateList.valueOf(if (clipping) Panels.RED else Color.rgb(60,200,120))
        }
        signalReadout?.text=if (clipping) "Clipping" else if (microphone.readings.arriving(now)) "%.0f dBFS".format(microphone.readings.peakDB) else "No signal"
        signalReadout?.setTextColor(if (clipping) Panels.RED else Color.LTGRAY)
        routeReadout?.text=micName()+"  ›"
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
            if (recording) microphoneRecoveryAt=System.nanoTime()
            if (!routeMicrophone(devices[i])) {
                previous?.let { routeMicrophone(it) }
                activity.message("The selected microphone is unavailable")
                return@choose
            }
            microphoneRoute.select(MicrophoneInput(devices[i]).key)
            resolveMicrophone()
            overlay.invalidate()
        }
    }

    private fun priority(devices: List<AudioDeviceInfo>) {
        val inputs=devices.map { MicrophoneInput(it) }
        val saved=prefs.getString("priorities","").orEmpty().split(',').filter { it.isNotBlank() }
        val keys=RecordingMicrophoneRoute.priority(saved,inputs.map { it.key },microphone.actual?.let { MicrophoneInput(it).key }).toMutableList()
        val labels=MicrophoneInput.labels(inputs)
        activity.choose("Tap a microphone to give it highest priority",keys.mapIndexed { index,key ->
            val at=inputs.indexOfFirst { it.key==key || it.legacyKey==key }
            val name=if (at>=0) labels[at] else prefs.getString("microphoneName."+key,"Disconnected microphone")+" · Disconnected"
            "${index+1}. $name"
        }) { index ->
            val key=keys.removeAt(index); keys.add(0,key)
            prefs.edit().putString("priorities",keys.joinToString(",")).apply()
            if (!recording) microphoneRoute.reset()
            choosePriority()
        }
    }

    private fun settings() {
        dualMenu = false
        zoomMenu = false
        overlay.invalidate()
        settingsPopup?.dismiss()
        val landscape = width > height
        val scale = min(width / (if (landscape) 640f else 294f), height / (if (landscape) 294f else 640f))
        val u = InspectorUi(activity, scale)
        val cyan = Color.rgb(77, 211, 239)
        val body = u.column().apply {
            background = u.shape(Color.rgb(29, 29, 31), 16f, Color.rgb(65, 65, 68))
            setPadding(u.px(14f), u.px(8f), u.px(14f), u.px(8f))
        }
        val popup = PopupWindow(body, u.px(if (landscape) 300f else 282f), -2, true).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = u.px(12f).toFloat()
        }
        settingsPopup = popup
        fun render() {
            body.removeAllViews()
            val header = u.row()
            header.addView(u.text("Camera settings", 12f, bold = true), LinearLayout.LayoutParams(0, u.px(34f), 1f))
            header.addView(u.text("Done", 11f, cyan).apply {
                gravity = Gravity.CENTER; contentDescription = "Camera settings done"
                setOnClickListener { popup.dismiss() }
            }, LinearLayout.LayoutParams(u.px(44f), u.px(34f)))
            u.add(body, header)
            val content = u.column()
            val assistsColumn = if (landscape) u.column() else content
            val camerasColumn = if (landscape) u.column() else content
            if (landscape) {
                val columns = u.row().apply { gravity = Gravity.TOP }
                columns.addView(assistsColumn, LinearLayout.LayoutParams(0, -2, 1.2f).apply { rightMargin = u.px(10f) })
                columns.addView(camerasColumn, LinearLayout.LayoutParams(0, -2, 1f))
                u.add(content, columns)
            }
            val ready = !opening && (device != null || dualReady)
            val canChange = ready && !recording
            val canAssist=ready && (!recording || paused)
            val controls=listOf(
                Triple("Stabilize","Stabilize",prefs.getBoolean("stabilization",true)),
                Triple("Level horizon","Level horizon",horizonEnabled && horizonUsesRear),
                Triple("Follow face","Follow face",faceFollowEnabled),
                Triple(if (front || dualLayout!=4) "Front light" else "Torch","Light",torch))
            u.grid(assistsColumn,controls.size,2,58f) { i ->
                val (title,icon,active)=controls[i]
                u.column().apply {
                    gravity=Gravity.CENTER; background=u.shape(if (active) Color.rgb(22,68,79) else Color.rgb(43,43,46))
                    contentDescription=title; isSelected=active; isFocusable=true
                    isEnabled=when (i) { 0 -> canAssist; 1 -> canAssist && horizonUsesRear && horizon.available; 2 -> ready && canFollowFace; else -> true }
                    alpha=if (isEnabled) 1f else .4f
                    setOnClickListener {
                        when (i) {
                            0 -> { prefs.edit().putBoolean("stabilization",!active).apply(); updateRequest() }
                            1 -> setHorizonEnabled(!horizonEnabled)
                            2 -> setFaceFollow(!faceFollowEnabled)
                            else -> setTorch(!torch)
                        }
                        render()
                    }
                    setOnLongClickListener {
                        activity.message(when (i) {
                            0 -> "Camera stabilization reduces hand shake. Change it before a take or while paused."
                            1 -> "Keeps small rear-camera tilts level using a fixed crop. Adjust Max angle in More settings."
                            2 -> (composition?.followStatus ?: "Looking for a face")+". Gently centers a face using the room available in the picture. If it leaves, framing holds. Panning manually turns following off."
                            else -> "The screen border lights the front view. Its bulb opens color, width and opacity controls."
                        }); true
                    }
                    addView(Glyph(activity,icon,if (active) cyan else Color.WHITE),LinearLayout.LayoutParams(u.px(21f),u.px(26f)))
                    addView(u.text(title,9f).apply { gravity=Gravity.CENTER })
                }
            }
            u.gap(assistsColumn,6f)
            val pair=selectedPair()
            val cameraHdr = HdrRecorder.supported(manager, cameraId) &&
                (dualLayout == 4 || pair?.all { HdrRecorder.supported(manager, it) } == true)
            val graphicsReason = if (cameraHdr) HdrGraphics.support.reason else null
            val supportsHdr = cameraHdr && graphicsReason == null
            u.toggle(camerasColumn, "Use two cameras", dualLayout != 4) { enabled ->
                setDualLayout(if (enabled) lastDualLayout else 4)
                render()
            }
            camerasColumn.getChildAt(camerasColumn.childCount - 1).apply {
                isEnabled = canAssist && pair != null; alpha = if (isEnabled) 1f else .4f
                (this as Switch).thumbTintList = android.content.res.ColorStateList.valueOf(cyan)
            }
            if (dualLayout!=4 && pair!=null) {
                for (slot in 0..1) {
                    val source=pair[if (dualSwapped) 1-slot else slot]
                    u.add(camerasColumn,u.disclosure((if (slot==0) "First" else "Second")+" · "+cameraName(source),false) { chooseDualCamera(slot) { render() } }.apply {
                        contentDescription=if (slot==0) "First camera" else "Second camera"
                        isEnabled=canAssist; alpha=if (isEnabled) 1f else .4f
                        (getChildAt(0) as TextView).setTextColor(Color.WHITE)
                    },42f)
                }
                u.add(camerasColumn,u.text("1080p · 30 fps",9f,Color.LTGRAY),22f)
            } else if (pair==null) u.add(camerasColumn,u.text("Two-camera capture is unavailable on this device.",9f,Color.LTGRAY),if (landscape) 54f else 28f)
            u.gap(assistsColumn, 5f)
            u.add(assistsColumn, u.text("Recording color", 10f, bold = true), 26f)
            val colors = u.row().apply { background = u.shape(Color.rgb(43, 43, 46)); setPadding(u.px(3f), u.px(3f), u.px(3f), u.px(3f)) }
            for (mode in listOf("SDR", "HDR")) {
                val selected = hdr == (mode == "HDR")
                val supported = mode == "SDR" || supportsHdr
                colors.addView(u.text(mode, 11f, if (selected) Color.BLACK else Color.WHITE, true).apply {
                    gravity = Gravity.CENTER; contentDescription = mode; isSelected = selected
                    isEnabled = canChange && supported
                    alpha = if (isEnabled || selected) 1f else .4f
                    if (selected) background = u.shape(cyan, 6f)
                    setOnClickListener { if (!selected) { setHdr(mode == "HDR"); render() } }
                }, LinearLayout.LayoutParams(0, u.px(34f), 1f))
            }
            u.add(assistsColumn, colors)
            u.add(assistsColumn, u.text(when {
                recording -> "Stop recording to change color or the camera setup."
                !ready -> "Recording color is available when the camera is ready."
                !cameraHdr -> "This camera setup supports SDR only."
                graphicsReason != null -> graphicsReason
                hdr -> "HDR keeps more highlight and color detail."
                else -> "SDR is ready to share on any display."
            }, 9f, Color.LTGRAY), 32f)
            u.add(body, ScrollView(activity).apply { addView(content); isVerticalScrollBarEnabled = false }, if (landscape) 169f else 224f)
            u.divider(body)
            u.add(body, u.disclosure("More settings", false) { popup.dismiss(); moreSettings() }.apply {
                (getChildAt(0) as TextView).setTextColor(Color.WHITE)
            }, 34f)
        }
        render()
        popup.showAtLocation(this, if (landscape) Gravity.RIGHT or Gravity.CENTER_VERTICAL else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, if (landscape) u.px(6f) else 0, u.px(6f))
        val refresh = object : Runnable {
            var state = ""
            override fun run() {
                if (!popup.isShowing) return
                val next = "$opening:$dualReady:${device != null}:$hdr:$dualLayout:$front:$recording:$paused:$torch:$faceFollowEnabled:$horizonEnabled"
                if (state != next) { state = next; render() }
                ui.postDelayed(this, 250)
            }
        }
        popup.setOnDismissListener {
            ui.removeCallbacks(refresh)
            if (settingsPopup === popup) settingsPopup = null
        }
        ui.post(refresh)
    }

    private fun moreSettings() {
        activity.choose(
            "More settings",
            listOf(
                "Resolution · ${prefs.getString("quality","1080p")}",
                "Frame rate · ${prefs.getInt("fps",30)} fps",
                "Microphone priority",
                "Mirror selfie preview · ${if (mirrorPreview) "On" else "Off"}",
                "Save mirrored selfies · ${if (mirrorRecording) "On" else "Off"}",
                "Max angle · %.1f°".format(horizonDegrees),
                "Reset framing",
            ),
            disabled = (if (recording) setOf(0,1,4) else emptySet()) + (if (recording && !paused || !horizon.available || !horizonUsesRear) setOf(5) else emptySet()),
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
                2 -> priority(microphoneDevices())
                3 -> { setMirrorPreview(!mirrorPreview); moreSettings() }
                4 -> { setMirrorRecording(!mirrorRecording); moreSettings() }
                5 -> horizonSettings()
                6 -> { composition?.reset(); rememberLayout(); overlay.invalidate() }
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

    fun tryStrobe(seconds: Float) {
        strobePreviewSince = SystemClock.elapsedRealtime()
        strobePreviewUntil = strobePreviewSince + (seconds.coerceIn(.1f, 10f) * 1000).toLong()
    }

    private fun updateLiveFrame() {
        val frame = cameraFrame(640, if (width > height) 360 else 1138) ?: return
        val flip = front && dualLayout == 4 && mirrorPreview != mirrorRecording
        var raw = if (flip) SourceMirror.flip(frame) else frame
        rendering = true
        val generation = effectGeneration
        val now = SystemClock.elapsedRealtime()
        val clock = if (recording) elapsedSeconds() else (now - strobePreviewSince) / 1000.0
        val effects = EffectOrder.ordered(liveEffects).filter {
            it.enabled && (it.kind != "Negative Strobe" || recording || now < strobePreviewUntil)
        }.map { it.copyEffect() }
        imageWorker.execute {
            val person =
                if (
                    effects.any {
                        it.kind == "Color Isolation" &&
                            it.values["mode"] == "Person" &&
                            it.strength(0.0, 1.0) > 0f
                    }
                )
                    Person.mask(raw)
                else null
            for (original in effects) {
                val fx = original.sampled(clock)
                if (fx.kind == "Negative Strobe" && TimeEffects.negative(original.copy(phase = EffectPhase(0.0)), clock, prefs.getInt("fps", 30).toDouble()))
                    Native.workingNegative(raw, fx.strength(0.0, 1.0))
                Effects.correct(raw, fx, fx.strength(0.0, 1.0), person,elapsed = clock,
                    frame = floor(clock.coerceAtLeast(0.0) * prefs.getInt("fps",30) + .000001).toLong())
                var kind = fx.kind
                val id = Effects.pixelId(fx)
                if (id > 0)
                    Native.image(
                        raw,
                        id,
                        fx.strength(0.0, 1.0),
                        fx.number("colors", 5f),
                        fx.number("smoothing", 0.5f),
                        fx.number("dither") == 1f,
                        fx.number("palette").toInt(),
                        fx.number("bw") == 1f,
                    )
            }
            if (flip) raw = SourceMirror.flip(raw)
            if (focusPeaking && dualLayout == 4) Native.focusPeaking(raw)
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

    private fun checkpointReceipt() {
        val take = receipt ?: return
        val marks = recordingMarks.toList()
        val effects = effectHistory.map { it.copyEffect() } + liveEffects.filter { it.enabled }.map {
            it.copyEffect().apply {
                from = effectSince; to = Double.MAX_VALUE
                if (kind == "Negative Strobe") phase = EffectPhase(0.0)
            }
        }
        receiptWorker.execute {
            try { store.checkpoint(take, marks, effects) }
            catch (e: Exception) { ui.post { activity.message("Recording metadata could not be saved: ${e.message}") } }
        }
    }

    private fun finishEffectRange(end: Double) {
        if (end <= effectSince) return
        for (fx in liveEffects.filter { it.enabled }) effectHistory.add(
            fx.copyEffect(true).apply {
                from = effectSince
                to = end
                if (kind == "Negative Strobe") phase = EffectPhase(0.0)
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
            checkpointReceipt()
            if (it.none { fx -> fx.enabled }) {
                liveFrame?.recycle()
                liveFrame = null
            }
            overlay.invalidate()
        }
    }

    private fun closeCamera() {
        rememberLayout()
        assistZoomAnimator?.let { assistZoomAnimator=null; it.cancel() }
        zoomAnimator?.cancel()
        rememberLens()
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
        session?.close()
        session = null
        device?.close()
        device = null
        previewSurface?.release()
        previewSurface = null
    }

    fun suspendCamera() {
        lightColorPicker?.dismiss()
        horizonPopup?.dismiss()
        settingsPopup?.dismiss()
        lightPopup?.dismiss()
        gainPopup?.dismiss()
        if (recording && !paused) pause()
        closed = true
        horizon.close()
        stopMeter()
        closeCamera()
        ui.removeCallbacks(ticker)
    }

    fun destroy() {
        lightColorPicker?.dismiss()
        horizonPopup?.dismiss()
        settingsPopup?.dismiss()
        lightPopup?.dismiss()
        gainPopup?.dismiss()
        if (recording) stop()
        closed = true
        horizon.close()
        stopMeter()
        closeCamera()
        audioManager.unregisterAudioDeviceCallback(audioDevices)
        audioManager.unregisterAudioRecordingCallback(recordingCallback)
        displays.unregisterDisplayListener(displayListener)
        ui.removeCallbacks(ticker)
        handlerThread.quitSafely()
        microphone.close()
        receiptWorker.shutdown()
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
            if (id !in listOf("flip", "camera", "dual", "zoom", "focus"))
                c.drawRoundRect(r, 15f, 15f, paint)
            val kind =
                when (id) {
                    "dual" -> "Dual camera"
                    "pip" -> "Picture in picture"
                    "roundPip" -> if (title.isEmpty()) "Round picture in picture" else null
                    "splitVertical" -> "Split vertical"
                    "splitHorizontal" -> "Split horizontal"
                    "splitDiagonal" -> "Split diagonal"
                    "dualReset" -> "Reset"
                    "effects" -> "Sparkles"
                    "settings" -> "Edit"
                    "light" -> "Light"
                    "flip" -> "Camera flip"
                    "pipSwap" -> "Camera flip"
                    "projects" -> "Stack"
                    "pause" -> if (paused) "Play" else "Pause"
                    "edit" -> "Scissors"
                    "mark" -> "Bookmark"
                    else -> null
                }
            if (id == "mic") {
                val portrait = h > w
                val glyph =
                    glyphs.getOrPut("Microphone" to color) {
                        Glyph(activity, "Microphone", color).apply { layout(0, 0, 12, 12) }
                    }
                c.save()
                c.translate(r.left + 8, if (portrait) r.top + 2 else r.centerY() - 6)
                glyph.draw(c)
                c.restore()
                for (i in 0..7) {
                    paint.color =
                        if (amplitude > i / 8f) if (microphone.readings.clipping(System.nanoTime())) Color.rgb(255, 75, 60) else Color.WHITE
                        else Color.argb(150, 100, 100, 100)
                    val x = r.left + 24 + i * 3.2f
                    val center = if (portrait) r.top + 10 else r.centerY()
                    c.drawRoundRect(
                        x,
                        center - 2 - i * .65f,
                        x + 1.8f,
                        center + 2 + i * .65f,
                        1f,
                        1f,
                        paint,
                    )
                }
                if (portrait) text(c, title, r.centerX(), r.bottom - 4, 7f, color, Paint.Align.CENTER)
                else {
                    val left = r.left + 56
                    val labelPaint = android.text.TextPaint(paint).apply {
                        textSize = 7f; typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                    }
                    val label = android.text.TextUtils.ellipsize(title, labelPaint,
                        (r.right - left - 8).coerceAtLeast(0f), android.text.TextUtils.TruncateAt.END)
                    text(c, label.toString(), left, r.centerY() + 2.5f, 7f, color)
                }
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
            } else if (id == "dual") {
                val mode = when (dualLayout) {
                    0 -> "Picture in picture"
                    1 -> "Split vertical"
                    2 -> "Split horizontal"
                    3 -> "Split diagonal"
                    5 -> "Round picture in picture"
                    else -> "Dual camera"
                }
                val glyph = glyphs.getOrPut(mode to color) {
                    Glyph(activity, mode, color).apply { layout(0, 0, 14, 14) }
                }
                c.save(); c.translate(r.left + 5, r.centerY() - 7); glyph.draw(c); c.restore()
                text(c, "Layout", r.left + 25, r.centerY() + 3, 9f, color)
            } else if (kind == null)
                text(c, title, r.centerX(), r.centerY() + 3, 9f, color, Paint.Align.CENTER)
            else {
                val glyph =
                    glyphs.getOrPut(kind to color) {
                        Glyph(activity, kind, color).apply { layout(0, 0, 18, 18) }
                    }
                c.save()
                c.translate(r.centerX() - 9, r.centerY() - if (id == "mark") 14 else 9)
                glyph.draw(c)
                c.restore()
                if (id == "mark") text(c,
                    if (SystemClock.elapsedRealtime() < markedUntil) "Marked" else "Mark",
                    r.centerX(), r.bottom - 2, 7f, color, Paint.Align.CENTER)
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
                lightPath.rewind()
                lightPath.fillType = Path.FillType.EVEN_ODD
                lightPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
                val corner = 24 * resources.displayMetrics.density
                val radius = max(min(corner, 10 * resources.displayMetrics.density), corner - border)
                lightPath.addRoundRect(
                    border,
                    border,
                    width - border,
                    height - border,
                    radius,
                    radius,
                    Path.Direction.CW,
                )
                val color = lightColor()
                paint.color = Color.argb((lightOpacity * 255).roundToInt(), Color.red(color), Color.green(color), Color.blue(color))
                c.drawPath(lightPath, paint)
            }
            c.save()
            c.scale(s, s)
            hits.clear()
            val portrait = h > w
            if (screenLight) {
                val x = if (portrait) w - 25f else 25f
                pill(c, RectF(x - 22, h - 47f, x + 22, h - 3f), "", "light")
            }
            val top = if (portrait) max(36f, texture.top / s - 10f) else 14f
            pill(
                c,
                RectF(
                    if (portrait) 12f else 64f,
                    top,
                    if (portrait) 74f else 208f,
                    top + if (portrait) 34 else 22,
                ),
                if (portrait) micName().take(15) else micName(),
                "mic",
            )
            val elapsed =
                (duration +
                    if (recording && !paused) SystemClock.elapsedRealtime() - since else 0) / 1000.0
            val timer = activity.editor.project.duration + if (recording) elapsed else 0.0
            pill(
                c,
                RectF(
                    if (portrait) w / 2 - 32 else 252f,
                    top,
                    if (portrait) w / 2 + 32 else 328f,
                    top + if (portrait) 34 else 22,
                ),
                "%02d:%02d".format(java.util.Locale.US, (timer / 60).toInt(), (timer % 60).toInt()),
                "time",
                if (recording) Color.rgb(255, 55, 74) else Color.WHITE,
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
                if (dualLayout != 4) Color.CYAN else Color.WHITE,
            )
            val bottom = if (portrait) texture.bottom / s - 94f else 242f
            val twoCameras = dualCamera != null || dualLayout != 4
            if (dualMenu && twoCameras) {
                val left=if (portrait) 35f else (w-224)/2
                val y=top+if (portrait) 46 else 32
                paint.color=Color.argb(210,35,35,37)
                c.drawRoundRect(left,y,left+224,y+40,12f,12f,paint)
                listOf("pip","splitVertical","splitHorizontal","splitDiagonal","dualDone").forEachIndexed { i,id ->
                    val active=when (id) { "pip" -> dualLayout==0 || dualLayout==5; "splitVertical" -> dualLayout==1; "splitHorizontal" -> dualLayout==2; "splitDiagonal" -> dualLayout==3; else -> false }
                    pill(c,RectF(left+2+i*44,y+2,left+44+i*44,y+38),if (id=="dualDone") "Done" else "",id,if (active) Color.CYAN else Color.WHITE)
                }
                if (dualLayout==0 || dualLayout==5) {
                    pill(c,RectF(w/2-94,y+45,w/2-2,y+80),"Rectangle","rectPip",if (dualLayout==0) Color.CYAN else Color.WHITE)
                    pill(c,RectF(w/2+2,y+45,w/2+94,y+80),"Circle","roundPip",if (dualLayout==5) Color.CYAN else Color.WHITE)
                }
                text(c,when (dualLayout) { 0 -> "Drag to move · Pinch across to reshape"; 5 -> "Drag to move · Pinch to resize"; 3 -> "Flip diagonal · Two fingers to pan or zoom"; else -> "Drag divider to resize · Two fingers to pan or zoom" },w/2,y+if (dualLayout==0 || dualLayout==5) 96 else 56,8f,Color.WHITE,Paint.Align.CENTER)
                if (dualLayout==0 || dualLayout==5) insetBounds()?.let { bounds ->
                    val r=RectF(bounds.left/s,bounds.top/s,bounds.right/s,bounds.bottom/s)
                    paint.color=Color.WHITE; paint.style=Paint.Style.STROKE; paint.strokeWidth=1f
                    if (dualLayout==5) c.drawOval(r,paint) else c.drawRect(r,paint)
                    if (dualLayout==0) {
                        paint.strokeWidth=2.5f
                        for (x in listOf(r.left,r.right)) for (y in listOf(r.top,r.bottom)) {
                            c.drawLine(x,y,x+if (x==r.left) 7f else -7f,y,paint)
                            c.drawLine(x,y,x,y+if (y==r.top) 7f else -7f,paint)
                        }
                    }
                    paint.style=Paint.Style.FILL
                }
                if (dualLayout in 1..3) {
                    val state=composition?.layoutState() ?: geometry
                    val x=texture.left/s+texture.width/s*(if (dualLayout==1) state.vertical.toFloat() else .5f)
                    val y=texture.top/s+texture.height/s*(if (dualLayout==2) state.horizontal.toFloat() else .5f)
                    pill(c,RectF(x-16,y-16,x+16,y+16),if (dualLayout==3) "↻" else if (dualLayout==1) "↔" else "↕",if (dualLayout==3) "diagonalFlip" else "dualDivider")
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
                if (recording) "Mark" else "✂",
                if (recording) "mark" else "edit",
            )
            if (portrait) {
                paint.color = Color.argb(105, 60, 59, 55)
                c.drawRoundRect(if (twoCameras) 78f else 30f, bottom, if (twoCameras) 216f else 264f, bottom + 33, 18f, 18f, paint)
                if (!twoCameras) {
                paint.color = Color.argb(60, 255, 255, 255)
                paint.strokeWidth = .5f
                c.drawLine(164f, bottom + 10, 164f, bottom + 23, paint)
                c.drawLine(217f, bottom + 10, 217f, bottom + 23, paint)
                }
            } else {
                paint.color = Color.argb(95, 25, 25, 27)
                c.drawRoundRect(if (twoCameras) 205f else 164f, 236f, if (twoCameras) 343f else 394f, 272f, 18f, 18f, paint)
            }
            pill(
                c,
                RectF(
                    if (twoCameras) if (portrait) 86f else 213f else if (portrait) 34f else 171f,
                    bottom,
                    if (twoCameras) if (portrait) 120f else 247f else if (portrait) 65f else 203f,
                    bottom + if (portrait) 33 else 25,
                ),
                "▣",
                "flip",
            )
            pill(
                c,
                RectF(
                    if (twoCameras) if (portrait) 126f else 253f else if (portrait) 73f else 208f,
                    bottom,
                    if (twoCameras) if (portrait) 207f else 334f else if (portrait) 153f else 286f,
                    bottom + if (portrait) 33 else 25,
                ),
                lenses
                    .getOrNull(CameraLenses.selected(lenses, cameraId, zoom))
                    ?.name
                    ?.removePrefix("Rear ")
                    ?.replaceFirstChar { it.uppercase() } ?: "Main",
                if (twoCameras) "dual" else "camera",
                if (dualMenu) Color.CYAN else Color.WHITE,
            )
            if (!twoCameras) {
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
                    if (!CameraFocus.canLock(focusMode)) "Fixed" else if (focusLocked) if (focusAdjustable) "MF" else "Locked" else "AF",
                    "focus",
                    if (focusPeaking) Color.RED else if (focusLocked) Color.YELLOW else Color.WHITE,
                )
            }
            if (!recording && !dualMenu)
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
                text(c, if (manualFocus) "Focus ${(focusPosition * 100).roundToInt()}%" else "AF LOCK", w / 2, top + 38, 8f, Color.YELLOW, Paint.Align.CENTER)
            c.restore()
        }

        private val scale =
            ScaleGestureDetector(
                activity,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    private var insetPinch=false
                    private var insetAxis=0
                    override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                        if (dualCamera!=null && pictureSlot==null) return false
                        insetPinch=dualMenu && (dualLayout==0 || dualLayout==5) && pictureSlot==1
                        insetAxis=if (d.currentSpanX>=d.currentSpanY*2.4f) 1 else if (d.currentSpanY>=d.currentSpanX*2.4f) 2 else 0
                        return true
                    }
                    override fun onScale(d: ScaleGestureDetector): Boolean {
                        moved = true
                        if (dualCamera != null) {
                            if ((dualLayout==0 || dualLayout==5) && !dualMenu) return true
                            if (insetPinch) { composition?.resizeInset(d.scaleFactor,insetAxis); return true }
                            composition?.gesture(
                                (d.focusX - texture.left) / texture.width,
                                (d.focusY - texture.top) / texture.height,
                                0f,
                                0f,
                                d.scaleFactor,
                                pictureSlot,
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
        private var focusTouch = false
        private var focusStart = 1f
        private var draggingDivider=false
        private var pictureSlot: Int?=null
        private var panX=0f
        private var panY=0f
        private var panStarted=false
        private val slop = ViewConfiguration.get(activity).scaledTouchSlop

        private fun edgeDistance(x: Float, y: Float) = min(min(x, width - x), min(y, height - y))

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                touchX = e.x
                touchY = e.y
                downX = e.x
                downY = e.y
                moved = false
                pictureSlot=null; panX=0f; panY=0f; panStarted=false
                draggingDivider=bounds("dualDivider")?.contains(e.x,e.y)==true
                focusTouch = bounds("focus")?.contains(e.x, e.y) == true
                focusStart = focusPosition
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
                        composition != null &&
                        (if (dualLayout==4) faceFollowEnabled || manualCameraPan else dualLayout!=0 && dualLayout!=5 || dualMenu) &&
                        e.x >= texture.left &&
                        e.x < texture.right &&
                        e.y >= texture.top &&
                        e.y < texture.bottom &&
                        free
                if (composing) pictureSlot=composition?.slotAt((e.x-texture.left)/texture.width,(e.y-texture.top)/texture.height)
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
                if (e.pointerCount!=2 || dualCamera!=null && (pictureSlot==null || composition?.slotAt((e.getX(1)-texture.left)/texture.width,(e.getY(1)-texture.top)/texture.height)!=pictureSlot)) {
                    composing=false; pictureSlot=null
                }
                if (e.pointerCount==2) { touchX=(e.getX(0)+e.getX(1))/2; touchY=(e.getY(0)+e.getY(1))/2 }
                panX=0f; panY=0f; panStarted=false
            }
            if (e.actionMasked == MotionEvent.ACTION_CANCEL) {
                draggingDivider=false
                pictureSlot=null
                if (resizingLight) prefs.edit().putFloat("lightWidth", lightWidth).apply()
                resizingLight = false
                composing = false
                focusTouch = false
                return true
            }
            if (e.actionMasked == MotionEvent.ACTION_MOVE && hypot(e.x - downX, e.y - downY) > slop)
                moved = true
            if (draggingDivider) {
                if (e.actionMasked==MotionEvent.ACTION_MOVE) {
                    composition?.divider(if (dualLayout==1) (e.x-texture.left)/texture.width else (e.y-texture.top)/texture.height)
                    invalidate()
                }
                if (e.actionMasked==MotionEvent.ACTION_UP) { draggingDivider=false; rememberLayout() }
                return true
            }
            if (focusTouch) {
                if (e.actionMasked == MotionEvent.ACTION_MOVE && moved && focusLocked && focusAdjustable && abs(e.y - downY) > abs(e.x - downX)) {
                    val dy = (e.y - downY) / resources.displayMetrics.density
                    val distance = abs(dy)
                    val amount = min(distance, 40f) / 320f + max(0f, distance - 40f) / 160f
                    setFocusPosition(focusStart + if (dy < 0) amount else -amount, false)
                    return true
                }
                if (e.actionMasked == MotionEvent.ACTION_UP) {
                    focusTouch = false
                    if (moved) { rememberLens(); return true }
                    if (e.eventTime - e.downTime >= 450) { toggleFocusPeaking(); return true }
                }
            }
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
                val x=if (e.pointerCount==2) (e.getX(0)+e.getX(1))/2 else e.x
                val y=if (e.pointerCount==2) (e.getY(0)+e.getY(1))/2 else e.y
                var dx = (x - touchX) / texture.width
                var dy = (y - touchY) / texture.height
                if (e.pointerCount==2 || abs(dx) + abs(dy) > .003f) {
                    if (e.pointerCount >= 2) {
                        if (dualMenu && (dualLayout==0 || dualLayout==5) && pictureSlot==1) composition?.movePip(dx,dy)
                        else {
                            panX+=x-touchX; panY+=y-touchY
                            if (!panStarted && hypot(panX,panY)>=8*resources.displayMetrics.density) panStarted=true
                            if (panStarted && abs(panX)+abs(panY)>0) {
                                dx=panX/texture.width; dy=panY/texture.height
                                panX=0f; panY=0f
                                if (faceFollowEnabled) setFaceFollow(false,true)
                                composition?.gesture((x-texture.left)/texture.width,(y-texture.top)/texture.height,dx,dy,1f,pictureSlot)
                            }
                        }
                    }
                    else if (resizingInset != 0) composition?.resizePip(resizingInset, dx, dy)
                    else if (draggingInset) composition?.movePip(dx, dy)
                    moved = true
                    touchX = x
                    touchY = y
                    invalidate()
                }
            }
            scale.onTouchEvent(e)
            if (e.actionMasked==MotionEvent.ACTION_UP && moved && composition!=null) rememberLayout()
            if (e.actionMasked != MotionEvent.ACTION_UP || scale.isInProgress || moved) return true
            val x = e.x / s
            val y = e.y / s
            val id = hits.lastOrNull { it.first.contains(x, y) }?.second
            if (id==null) return true
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
                        "mark" -> addMark()
                        "edit" -> {
                            if (recording) stop()
                            suspendCamera()
                            onEdit()
                        }
                        "mic" -> recordingAudioControls()
                        "camera" -> cameras()
                        "flip" -> {
                            if (dualCamera!=null) { swapCameras(); return true }
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
                            manualFocus = false
                            rememberLens()
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
                        "light" -> lightSettings()
                        "dual" -> {
                            dualMenu = !dualMenu
                            zoomMenu = false
                        }
                        "pip" -> setDualLayout(if (dualLayout==5) 5 else 0)
                        "rectPip" -> setDualLayout(0)
                        "roundPip" -> setDualLayout(5)
                        "splitVertical" -> setDualLayout(1)
                        "splitHorizontal" -> setDualLayout(2)
                        "splitDiagonal" -> setDualLayout(3)
                        "dualReset" -> { composition?.reset(); rememberLayout(); invalidate() }
                        "diagonalFlip" -> { composition?.flipDiagonal(); rememberLayout(); invalidate() }
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
