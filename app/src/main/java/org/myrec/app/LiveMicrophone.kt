package org.myrec.app

import android.annotation.SuppressLint
import android.media.*
import android.os.Build
import android.os.Handler
import java.io.Closeable
import java.util.concurrent.Executors
import kotlin.math.*

/** Camera sessions never own this input. Every published buffer is an owned 48 kHz stereo copy. */
@SuppressLint("MissingPermission")
class LiveMicrophone(
    private val ui: Handler,
    private val changed: () -> Unit,
    private val failure: (Throwable) -> Unit,
    private val gainForDevice: (AudioDeviceInfo) -> Float,
    private val sample: (FloatArray, Long) -> Unit,
) : Closeable {
    val gain = RecordingGain()
    val readings = RecordingReadings()
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var input: AudioRecord? = null
    @Volatile private var generation = 0
    @Volatile private var closed = false
    @Volatile var actual: AudioDeviceInfo? = null
        private set
    @Volatile var requested: AudioDeviceInfo? = null
        private set
    private var source = MediaRecorder.AudioSource.CAMCORDER
    val running get() = input?.recordingState == AudioRecord.RECORDSTATE_RECORDING
    val sessionId get() = input?.audioSessionId
    val configuration get() = if (Build.VERSION.SDK_INT >= 29) input?.activeRecordingConfiguration else null

    fun start(device: AudioDeviceInfo?, source: Int) {
        if (closed) return
        if (running && this.source == source) {
            if (requested?.id != device?.id || actual?.id != device?.id) {
                check(input?.setPreferredDevice(device) == true) { "The selected microphone could not be requested" }
                requested = device
            }
            return
        }
        stop()
        this.source = source; requested = device
        val ticket = ++generation
        worker.execute {
            var record: AudioRecord? = null
            var routing: AudioRouting.OnRoutingChangedListener? = null
            var problem: Throwable? = null
            try {
                var negotiation: Throwable? = null
                for (channels in intArrayOf(if (source == MediaRecorder.AudioSource.VOICE_COMMUNICATION) 1 else 2, 1).distinct()) {
                    try {
                        val mask = if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
                        val minimum = AudioRecord.getMinBufferSize(48000, mask, AudioFormat.ENCODING_PCM_FLOAT)
                        check(minimum > 0) { "The microphone cannot provide 48 kHz PCM" }
                        val next = AudioRecord.Builder().setAudioSource(source)
                            .setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setChannelMask(mask)
                                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                            .setBufferSizeInBytes(max(minimum * 2, 4800 * channels * 4)).build()
                        try {
                            check(next.state == AudioRecord.STATE_INITIALIZED)
                            check(next.setPreferredDevice(device)) { "The selected microphone could not be requested" }
                            next.startRecording()
                            check(next.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                        } catch (e: Throwable) { next.release(); throw e }
                        record = next
                        break
                    } catch (e: Throwable) { negotiation = e }
                }
                val current = record ?: throw negotiation ?: IllegalStateException("Microphone is unavailable")
                if (ticket != generation || closed) return@execute
                input = current
                routing = AudioRouting.OnRoutingChangedListener { route ->
                    if (input === current) {
                        actual = route.routedDevice
                        actual?.let { gain.set(gainForDevice(it)) }
                        changed()
                    }
                }
                current.addOnRoutingChangedListener(routing, ui)
                val channels = current.channelCount
                val buffer = FloatArray(480 * channels)
                val stamp = AudioTimestamp()
                var readFrames = 0L
                var anchorTime = 0L
                var anchorFrame = 0L
                var lastStart = Long.MIN_VALUE
                var hardwareTime = false
                while (ticket == generation && !closed) {
                    val n = current.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    check(n > 0) { if (n == AudioRecord.ERROR_DEAD_OBJECT) "Microphone connection was lost" else "Microphone audio stopped ($n)" }
                    check(n % channels == 0)
                    val frames = n / channels
                    val before = readFrames
                    readFrames += frames
                    val hadHardwareTime = hardwareTime
                    if (current.getTimestamp(stamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                        hardwareTime = true
                        anchorTime = stamp.nanoTime
                        anchorFrame = stamp.framePosition
                    } else if (anchorTime == 0L) {
                        anchorTime = System.nanoTime() - frames * 1_000_000_000L / 48000
                        anchorFrame = before
                    }
                    val time = anchorTime + (before - anchorFrame) * 1_000_000_000L / 48000
                    if (hadHardwareTime && lastStart != Long.MIN_VALUE && time + 2_000_000 < lastStart) error("Microphone clock moved backwards")
                    lastStart = time
                    val route = current.routedDevice
                    if (route?.id != actual?.id) {
                        actual = route
                        route?.let { gain.set(gainForDevice(it)) }
                        ui.post { if (input === current) changed() }
                    }
                    if (Build.VERSION.SDK_INT >= 29 && current.activeRecordingConfiguration?.isClientSilenced == true) {
                        readings.reset()
                        continue
                    }
                    val pcm = gain.process(buffer, n, channels)
                    readings.update(pcm, System.nanoTime())
                    sample(pcm, time)
                }
            } catch (e: Throwable) {
                problem = e
            } finally {
                if (input === record) { input = null; actual = null }
                routing?.let { listener -> runCatching { record?.removeOnRoutingChangedListener(listener) } }
                runCatching { record?.stop() }
                record?.release()
                problem?.let { error ->
                    if (ticket == generation && !closed) ui.post { if (ticket == generation && !closed) failure(error) }
                }
            }
        }
    }

    fun stop() {
        generation++
        val old = input
        input = null; actual = null
        readings.reset()
        runCatching { old?.stop() }
    }

    override fun close() { closed = true; stop(); worker.shutdown() }
}
