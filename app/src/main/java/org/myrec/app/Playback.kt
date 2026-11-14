package org.myrec.app

import android.graphics.Bitmap
import android.media.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

class Playback(
    private val editor: Editor,
    private val cache: AudioCache,
    private val onFrame: (Bitmap) -> Unit,
    private val onTick: () -> Unit,
    private val onError: (String) -> Unit,
    private val onAnalysis: (Float?) -> Unit = {},
) {
    private val ui = Handler(Looper.getMainLooper())
    private val video = Executors.newSingleThreadExecutor()
    private val audio = Executors.newSingleThreadExecutor()
    private val frames = Frames { progress -> ui.post { onAnalysis(progress) } }
    private val rendering = AtomicBoolean(false)
    private var pendingRender: Pair<Project, Double>? = null
    private var closed = false
    @Volatile
    var playing = false
        private set

    @Volatile private var generation = 0
    private var baseTime = 0.0
    private var baseClock = 0L
    private var until = Double.MAX_VALUE
    private var track: AudioTrack? = null
    @Volatile private var activeMixer: AudioMixer? = null
    var previewProject: Project? = null
        set(value) {
            val before = field ?: editor.project
            val after = value ?: editor.project
            val restart =
                playing &&
                    (Studio.has(before) || Studio.has(after)) &&
                    (Studio.signature(before) != Studio.signature(after) ||
                        before.clips.map { it.id } != after.clips.map { it.id })
            field = value
            if (restart) {
                val end = until
                pause()
                play(end)
            } else
                activeMixer?.update(
                    value ?: editor.project.duplicate(),
                    editor.matchLoudness && value != null,
                )
        }

    var frameWidth = 768
    var frameHeight = 432
    private val tick =
        object : Runnable {
            override fun run() {
                if (!playing) return
                editor.cursor =
                    baseTime +
                        (SystemClock.elapsedRealtime() - baseClock) / 1000.0 * editor.playbackRate
                if (editor.cursor >= min(until, editor.project.duration)) {
                    editor.cursor = min(until, editor.project.duration)
                    pause()
                    onTick()
                    return
                }
                refresh()
                onTick()
                ui.postDelayed(this, 33)
            }
        }

    fun refresh(project: Project = previewProject ?: editor.project) {
        enqueue(project.duplicate(), editor.cursor)
    }

    private fun enqueue(snapshot: Project, position: Double) {
        if (closed) return
        if (!rendering.compareAndSet(false, true)) {
            pendingRender = snapshot to position
            return
        }
        val ratio = snapshot.width.toDouble() / snapshot.height
        val w = if (ratio >= 1) 768 else (768 * ratio).toInt().coerceAtLeast(2)
        val h = if (ratio >= 1) (768 / ratio).toInt().coerceAtLeast(2) else 768
        video.execute {
            try {
                val bitmap = frames.render(snapshot, position, w, h)
                ui.post { onFrame(bitmap) }
            } catch (e: Exception) {
                ui.post { onError(e.message ?: "Cannot render video") }
            } finally {
                ui.post {
                    rendering.set(false)
                    val pending = pendingRender
                    pendingRender = null
                    if (pending != null && !closed) {
                        enqueue(pending.first, pending.second)
                    }
                }
            }
        }
    }

    fun toggle() {
        if (playing) pause() else play()
    }

    fun play(end: Double = editor.project.duration) {
        pause()
        if (editor.project.duration <= 0) return
        if (editor.cursor >= editor.project.duration - 0.01) editor.cursor = 0.0
        val ticket = ++generation
        val snapshot = (previewProject ?: editor.project).duplicate()
        val start = editor.cursor
        val speed = editor.playbackRate
        until = end
        baseTime = start
        baseClock = SystemClock.elapsedRealtime()
        playing = true
        onTick()
        audio.execute {
            if (ticket != generation || !playing) return@execute
            var output: AudioTrack? = null
            try {
                AudioMixer(snapshot, cache, editor.matchLoudness && previewProject != null).use {
                    mixer ->
                    mixer.prepare({ ticket != generation || !playing }) { progress ->
                        ui.post { if (ticket == generation) onAnalysis(progress) }
                    }
                    ui.post { if (ticket == generation) onAnalysis(null) }
                    mixer.seek(start)
                    activeMixer = mixer
                    if (ticket != generation || !playing) return@execute
                    val size =
                        max(
                            AudioTrack.getMinBufferSize(
                                48000,
                                AudioFormat.CHANNEL_OUT_STEREO,
                                AudioFormat.ENCODING_PCM_FLOAT,
                            ),
                            32768,
                        )
                    output =
                        AudioTrack.Builder()
                            .setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                                    .build()
                            )
                            .setAudioFormat(
                                AudioFormat.Builder()
                                    .setSampleRate(48000)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                                    .build()
                            )
                            .setBufferSizeInBytes(size)
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .build()
                    track = output
                    output!!.playbackParams = PlaybackParams().setSpeed(speed)
                    var position = start
                    baseTime = start
                    baseClock = SystemClock.elapsedRealtime()
                    output!!.play()
                    ui.post(tick)
                    while (
                        playing && ticket == generation && position < min(until, snapshot.duration)
                    ) {
                        val pcm = mixer.read(1024)
                        if (!playing || ticket != generation) break
                        output!!.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                        position += 1024 / 48000.0
                    }
                }
            } catch (e: Exception) {
                ui.post {
                    if (ticket == generation) {
                        pause()
                        onTick()
                        onError(e.message ?: "Cannot play audio")
                    }
                }
            } finally {
                ui.post { if (ticket == generation) onAnalysis(null) }
                activeMixer = null
                try {
                    output?.stop()
                } catch (_: Exception) {}
                output?.release()
                if (track === output) track = null
            }
        }
    }

    fun audition() {
        val point = editor.cursor
        editor.cursor = max(0.0, point - 2)
        play(min(editor.project.duration, point + 2))
    }

    fun pause() {
        playing = false
        onAnalysis(null)
        generation++
        ui.removeCallbacks(tick)
        try {
            track?.pause()
            track?.flush()
        } catch (_: Exception) {}
    }

    fun seek(t: Double) {
        val wasPlaying = playing
        pause()
        editor.cursor = t.coerceIn(0.0, editor.project.duration)
        refresh()
        onTick()
        if (wasPlaying) play()
    }

    fun close() {
        closed = true
        pendingRender = null
        pause()
        video.execute { frames.close() }
        video.shutdown()
        audio.shutdown()
    }
}
