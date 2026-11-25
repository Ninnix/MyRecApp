package org.myrec.app

import android.graphics.Bitmap
import android.media.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import java.util.concurrent.Executors
import kotlin.math.*

class Playback(
    private val editor: Editor,
    private val cache: AudioCache,
    private val onFrame: (Bitmap) -> Unit,
    private val onTick: () -> Unit,
    private val onError: (String) -> Unit,
    private val onAnalysis: (Float?) -> Unit = {},
    private val outputSurface: () -> Surface? = { null },
    private val onSurfaceFrame: (Surface, Long) -> Unit = { _, _ -> },
    private val hideSurface: () -> Unit = {},
) {
    private val ui = Handler(Looper.getMainLooper())
    private val video = Executors.newSingleThreadExecutor()
    private val audio = Executors.newSingleThreadExecutor()
    @Volatile private var preparationEpoch = 0
    private var activePreparationEpoch = 0
    private val frames = Frames(cancelled = { closed || activePreparationEpoch != preparationEpoch }) { progress -> ui.post { onAnalysis(progress) } }
    private var rendering = false
    private var pendingRender: Pair<Project, Double>? = null
    private var scrubbing = false
    private var resumeAfterScrub: Double? = null
    private data class DirectKey(val surface: Surface, val path: String, val clip: String, val width: Int, val height: Int)
    private var directDecoder: VideoDecoder? = null
    private var directKey: DirectKey? = null
    private var failedDirect: DirectKey? = null
    private var directRequested = false
    @Volatile private var closed = false
    @Volatile
    var playing = false
        private set

    var originalStabilization = false
        set(value) {
            if (field == value) return
            field = value; cancelPreparation()
        }

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
                    (before.duration != after.duration || (Studio.has(before) || Studio.has(after)) &&
                    (Studio.signature(before) != Studio.signature(after) ||
                        before.clips.map { it.id } != after.clips.map { it.id }))
            field = value
            if (value != null) hideSurface()
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

    private var playbackEdge = 768
    private var slowFrames = 0
    private var inspectionZoom = 1f
    private val refreshInspection = Runnable { if (!playing && !closed) refresh() }

    fun inspect(zoom: Float) {
        if (inspectionZoom == zoom) return
        inspectionZoom = zoom
        if (zoom > 1f) hideSurface()
        ui.removeCallbacks(refreshInspection)
        ui.postDelayed(refreshInspection, 85)
    }
    private val tick =
        object : Runnable {
            override fun run() {
                if (!playing) return
                editor.cursor =
                    baseTime +
                        (SystemClock.elapsedRealtime() - baseClock) / 1000.0 * editor.playbackRate
                val end = min(until, (previewProject ?: editor.project).duration)
                if (editor.cursor >= end) {
                    editor.cursor = end
                    pause()
                    refresh()
                    return
                }
                refresh()
                onTick()
                ui.postDelayed(this, 33)
            }
        }

    fun refresh(project: Project = previewProject ?: editor.project) {
        enqueue(project, editor.cursor)
    }

    fun cancelPreparation() { preparationEpoch++ }

    private fun enqueue(project: Project, position: Double) {
        if (closed) return
        if (rendering) {
            pendingRender = project to position
            return
        }
        val snapshot = project.duplicate()
        rendering = true
        val ratio = snapshot.width.toDouble() / snapshot.height
        val edge = if (playing && inspectionZoom<=1f) playbackEdge else if (inspectionZoom <= 1f) 768 else
            (768 * inspectionZoom).roundToInt().coerceAtMost(min(4096, max(snapshot.width, snapshot.height)))
        val w = if (ratio >= 1) edge else (edge * ratio).toInt().coerceAtLeast(2)
        val h = if (ratio >= 1) (edge / ratio).toInt().coerceAtLeast(2) else edge
        val preparation = preparationEpoch
        val original = originalStabilization
        val live=playing && inspectionZoom<=1f
        val ticket=generation
        val clip=if (live && previewProject==null && project===editor.project &&
            snapshot.transitions.none { position>=it.begin && position<=it.end } && snapshot.subtitleCues(position).isEmpty())
            snapshot.active(position).singleOrNull()?.takeIf { !it.photo && it.frozen==null && it.rate==1.0 && it.pauses==null &&
                it.effects.none { fx -> fx.enabled && fx.kind !in Effects.audio } }
            else null
        val direct=clip?.let { source -> outputSurface()?.let { DirectKey(it,source.path,source.id,snapshot.width,snapshot.height) } }
        directRequested=direct!=null
        video.execute {
            try {
                activePreparationEpoch = preparation
                val start=SystemClock.elapsedRealtimeNanos()
                if (direct!=null && direct!=failedDirect && ticket==generation) {
                    val frame=try {
                        if (directKey!=direct) {
                            closeDirect()
                            directDecoder=VideoDecoder(direct.path,surface=direct.surface,surfaceAspect=ratio)
                            directKey=direct
                        }
                        directDecoder?.draw(clip.sourceTime(position)) { closed || ticket!=generation || preparation!=preparationEpoch }
                    } catch (_: Exception) {
                        failedDirect=direct
                        null
                    }
                    if (frame!=null) {
                        ui.post {
                            if (!closed && playing && ticket==generation && preparation==preparationEpoch && inspectionZoom<=1f)
                                onSurfaceFrame(direct.surface,frame)
                        }
                        return@execute
                    }
                    if (ticket!=generation || preparation!=preparationEpoch || closed) return@execute
                    failedDirect=direct
                }
                closeDirect()
                val bitmap = frames.render(snapshot, position, w, h,originalStabilization = original,preview=true)
                val elapsed=(SystemClock.elapsedRealtimeNanos()-start)/1e6
                ui.post {
                    if (!closed && preparation==preparationEpoch) {
                        hideSurface()
                        onFrame(bitmap)
                    } else bitmap.recycle()
                    if (live && playing && ticket==generation) {
                        slowFrames=if (elapsed>33) slowFrames+1 else 0
                        if (slowFrames>=3) {
                            playbackEdge=(edge*sqrt(28/elapsed)).roundToInt().coerceIn(384,edge)
                            slowFrames=0
                        }
                    }
                }
            } catch (e: Exception) {
                if (!closed && preparation == preparationEpoch) ui.post { onError(e.message ?: "Cannot render video") }
            } finally {
                ui.post {
                    rendering = false
                    val pending = pendingRender
                    pendingRender = null
                    if (pending != null && !closed) {
                        enqueue(pending.first, pending.second)
                    }
                }
            }
        }
    }

    private fun closeDirect() {
        runCatching { directDecoder?.close() }
        directDecoder=null
        directKey=null
    }

    fun toggle() {
        if (playing) pause() else play()
    }

    fun play(end: Double = editor.project.duration) {
        pause()
        val snapshot = (previewProject ?: editor.project).duplicate()
        if (snapshot.duration <= 0) return
        val limit = min(end, snapshot.duration)
        if (editor.cursor >= limit - 0.01) editor.cursor = 0.0
        val ticket = ++generation
        playbackEdge=768; slowFrames=0
        val start = editor.cursor
        val speed = editor.playbackRate
        until = limit
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
                    output.playbackParams = PlaybackParams().setSpeed(speed)
                    var position = start
                    baseTime = start
                    baseClock = SystemClock.elapsedRealtime()
                    output.play()
                    ui.post { if (ticket == generation && playing) tick.run() }
                    while (
                        playing && ticket == generation && position < min(until, snapshot.duration)
                    ) {
                        val pcm = mixer.read(1024)
                        if (!playing || ticket != generation) break
                        output.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
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
        scrubbing = false
        resumeAfterScrub = null
        playing = false
        onAnalysis(null)
        generation++
        ui.removeCallbacks(tick)
        try {
            track?.pause()
            track?.flush()
        } catch (_: Exception) {}
        if (directRequested && !closed) {
            directRequested=false
            refresh()
        }
        onTick()
    }

    fun seek(t: Double) {
        val resume = if (playing) until else resumeAfterScrub
        pause()
        cancelPreparation()
        editor.cursor = t.coerceIn(0.0, editor.project.duration)
        refresh()
        onTick()
        if (resume != null && editor.cursor < resume) play(resume)
    }

    fun scrub(t: Double) {
        if (!scrubbing) {
            val resume = until.takeIf { playing }
            pause()
            cancelPreparation()
            scrubbing = true
            resumeAfterScrub = resume
        }
        editor.cursor = t.coerceIn(0.0, editor.project.duration)
        refresh()
        onTick()
    }

    fun close() {
        closed = true
        ui.removeCallbacks(refreshInspection)
        pendingRender = null
        pause()
        hideSurface()
        video.execute { closeDirect();frames.close() }
        video.shutdown()
        audio.shutdown()
    }
}
