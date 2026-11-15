package org.myrec.app

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.media.*
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

@SuppressLint("MissingPermission")
class VoiceDraft(private val activity: MainActivity,
                 private val save: (File) -> Unit, private val dismissed: () -> Unit) {
    val file = File(activity.cacheDir, "voice-${uid()}.wav")
    private val ui = Handler(Looper.getMainLooper())
    private val manager = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var input: AudioDeviceInfo? = null
    private var activeInput: Int? = null
    private var recorder: AudioRecord? = null
    private var player: MediaPlayer? = null
    private var thread: Thread? = null
    internal var discardDialog: AlertDialog? = null
        private set
    @Volatile private var running = false
    @Volatile private var frames = 0L
    @Volatile private var peak = 0.0
    private var closed = false
    var changed: () -> Unit = {}
    val hasAudio get() = frames > 0
    val recording get() = running
    val playing get() = player?.isPlaying == true
    val clock get() = if (playing) player!!.currentPosition / 1000.0 else frames / 48000.0
    val level get() = if (running && peak > 0) ((20 * log10(peak) + 55) / 55).coerceIn(0.0, 1.0) else 0.0
    internal fun microphoneDevices() = MicrophoneInput.devices(manager)
    val inputName get() = input?.let { selected ->
        val devices = microphoneDevices()
        MicrophoneInput.labels(devices.map { MicrophoneInput(it) }).getOrNull(devices.indexOfFirst { it.id == selected.id })
    } ?: "Microphone"
    private val refresh = object : Runnable {
        override fun run() {
            if (closed) return
            changed()
            ui.postDelayed(this, 100)
        }
    }

    private val routes = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            val active = activeInput
            if (running && removed.any { it.id == active }) {
                pause(); activity.message("Microphone disconnected. Choose an input and tap Record more.")
            }
        }
    }

    fun show() {
        val devices = microphoneDevices()
        val selected = activity.captureSettings.getInt("mic", -1)
        input = devices.firstOrNull { it.id == selected }
            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            ?: devices.firstOrNull()
        manager.registerAudioDeviceCallback(routes, ui)
        activity.panels.voiceover(this)
        refresh.run()
    }

    fun chooseInput() {
        val devices = microphoneDevices()
        activity.choose("Microphone", MicrophoneInput.labels(devices.map { MicrophoneInput(it) }),
            devices.indexOfFirst { it.id == input?.id }) { index ->
            input = devices[index]
            changed()
        }
    }

    fun record() {
        try { if (running) pause() else start() }
        catch (e: Exception) { pause(); activity.message(e.message ?: "Cannot record voiceover") }
        changed()
    }

    fun play() {
        try {
            if (playing) stopPlayer()
            else {
                pause()
                player = MediaPlayer().apply {
                    setDataSource(file.path); prepare()
                    setOnCompletionListener { if (player === it) { stopPlayer(); changed() } }; start()
                }
            }
        } catch (e: Exception) { stopPlayer(); activity.message(e.message ?: "Cannot play this recording") }
        changed()
    }

    fun commit() {
        try {
            pause()
            check(hasAudio) { "Record some audio first" }
            save(file)
            activity.panels.close()
        } catch (e: Exception) { activity.message(e.message ?: "Cannot save voiceover") }
    }

    fun discard(leave: Boolean) {
        pause()
        changed()
        if (!hasAudio) { if (leave) activity.panels.close(); return }
        discardDialog = AlertDialog.Builder(activity).setTitle("Discard this recording?")
            .setMessage("This discards all the takes in this voice recording.")
            .setNegativeButton("Keep take", null)
            .setPositiveButton("Discard") { _, _ ->
                frames = 0; header(); changed()
                if (leave) activity.panels.close()
            }.create().also { dialog ->
                dialog.setOnDismissListener { discardDialog = null }
                dialog.show()
            }
    }

    private fun start() {
        check(!closed && recorder == null)
        stopPlayer()
        val size = max(8192, AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))
        val audio = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(size).build()
        try {
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "Cannot open the microphone" }
            input?.let { check(audio.setPreferredDevice(it)) { "The selected microphone is unavailable" } }
            header()
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Cannot start the microphone" }
            recorder = audio
            running = true
            activeInput = audio.routedDevice?.id
            audio.addOnRoutingChangedListener(AudioRouting.OnRoutingChangedListener { route ->
                if (recorder === audio && running) {
                    val actual = route.routedDevice?.id
                    if (activeInput != null && actual != activeInput) {
                        pause(); activity.message("Microphone changed. Tap Record more to continue.")
                    } else activeInput = actual
                }
            }, ui)
            thread = Thread {
                try {
                    RandomAccessFile(file, "rw").use { output ->
                        output.seek(44 + frames * 2)
                        val samples = ShortArray(2048)
                        while (running) {
                            val count = audio.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                            if (count <= 0 && !running) break
                            check(count > 0) { "The microphone stopped. Tap Record more to continue." }
                            check(frames + count <= (0xffffffffL - 36) / 2) { "This recording has reached the WAV size limit" }
                            val bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
                            var loudest = 0
                            for (i in 0 until count) { bytes.putShort(samples[i]); loudest = max(loudest, abs(samples[i].toInt())) }
                            output.write(bytes.array()); frames += count; peak = loudest / 32768.0
                        }
                    }
                } catch (e: Exception) {
                    ui.post { pause(); activity.message(e.message ?: "Microphone interrupted") }
                }
            }.apply { name = "MyRec voiceover"; start() }
        } catch (e: Exception) { audio.release(); throw e }
    }

    private fun stopPlayer() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }

    fun pause() {
        stopPlayer()
        val audio = recorder ?: return
        running = false
        runCatching { audio.stop() }
        thread?.join(2000)
        check(thread?.isAlive != true) { "The microphone is still stopping" }
        thread = null
        audio.release(); recorder = null; activeInput = null
        header()
    }

    private fun header() {
        val bytes = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt((36 + frames * 2).toInt()).put("WAVEfmt ".toByteArray())
        bytes.putInt(16).putShort(1).putShort(1).putInt(48000).putInt(96000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt((frames * 2).toInt())
        RandomAccessFile(file, "rw").use { it.setLength(44 + frames * 2); it.seek(0); it.write(bytes.array()); it.fd.sync() }
    }

    fun close() {
        if (closed) return
        pause()
        closed = true
        ui.removeCallbacks(refresh)
        manager.unregisterAudioDeviceCallback(routes)
        file.delete()
        discardDialog?.dismiss()
        changed = {}
        dismissed()
    }
}
