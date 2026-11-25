package org.myrec.app

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

class MediaIndex(private val cache: AudioCache, private val changed: () -> Unit) {
    private val worker = Executors.newFixedThreadPool(2)
    private val ui = Handler(Looper.getMainLooper())
    val thumbnails = mutableMapOf<String, List<Bitmap>>()
    val waves = mutableMapOf<String, FloatArray>()
    val durations = mutableMapOf<String, Double>()
    private val requested = mutableSetOf<String>()
    private data class WaveTile(val path: String,val tile: Int)
    private val waveTiles=LinkedHashMap<WaveTile,FloatArray>(64,.75f,true)
    private val waveRequests=mutableSetOf<WaveTile>()
    private val detail=Executors.newSingleThreadExecutor()
    private val filmstrip=Executors.newSingleThreadScheduledExecutor()
    private data class FilmFrame(val path: String,val time: Long,val recipe: List<Effect>,val canvas: Pair<Int,Int>,
                                 val scope: String?,val composition: Long)
    private class FilmWork {
        @Volatile var cancelled=false
        var future: Future<*>?=null
        fun cancel() { cancelled=true;future?.cancel(false) }
    }
    private val filmFrames=LinkedHashMap<FilmFrame,Bitmap>(128,.75f,true)
    private val filmRequests=mutableMapOf<FilmFrame,FilmWork>()
    private val visibleFilm=mutableSetOf<FilmFrame>()
    private var filmRegions=emptyList<ClosedFloatingPointRange<Double>>()
    private var filmProject: Project?=null
    private var filmRenderer: Frames?=null
    private var filmIdle: Future<*>?=null
    private var filmWork: FilmWork?=null
    @Volatile private var editGeneration=1L
    @Volatile private var closed=false

    fun beginFilmstrip() { visibleFilm.clear() }

    fun endFilmstrip() {
        val removed=filmRequests.entries.removeAll { (key, work) ->
            (key !in visibleFilm).also { if (it) work.cancel() }
        }
        if (removed && !closed) changed()
    }

    fun editChanged(project: Project) {
        editGeneration++
        filmProject=null
        filmRequests.entries.removeAll { (key, work) ->
            (key.composition!=0L).also { if (it) work.cancel() }
        }
        val regions=project.transitions.map { it.begin..it.end } + project.clips.flatMap { clip ->
            clip.effects.filter { it.enabled && (it.kind=="Placement" || it.subtitle!=null) }
                .map { max(clip.start,clip.start+it.from)..min(clip.end,clip.start+it.to) }
        }
        val merged=mutableListOf<ClosedFloatingPointRange<Double>>()
        for (range in regions.sortedBy { it.start }) {
            if (range.isEmpty()) continue
            val last=merged.lastOrNull()
            if (last!=null && range.start<=last.endInclusive) merged[merged.lastIndex]=last.start..max(last.endInclusive,range.endInclusive)
            else merged.add(range)
        }
        filmRegions=merged
    }

    fun editedFrame(project: Project,clip: Clip,position: Double, refine: Boolean = false): Bitmap? {
        val at=position.coerceIn(clip.start,max(clip.start,clip.end-.000001))
        val effects=clip.effects.filter { it.active(at-clip.start) && it.kind !in Effects.audio }
        val region=filmRegions.binarySearchBy(at) { it.start }.let { if (it>=0) it else -it-2 }
        val composite=region>=0 && at in filmRegions[region]
        if (!composite && effects.isEmpty() && (!refine || clip.photo)) return null
        val sourceTime=clip.sourceTime(at)
        val exact=composite || clip.frozen!=null || effects.any { it.kind !in stillFilmEffects || it.motion!=null ||
            it.phase!=null || it.number("animate")!=0f || it.parameters.any { p -> p.keyframes.isNotEmpty() } }
        val recipe=if (exact) emptyList() else effects.map { it.copyEffect().copy(id="",from=0.0,to=Double.MAX_VALUE) }
        val key=FilmFrame(clip.path,((if (exact) at else sourceTime)*240000).roundToLong(), recipe,
            project.width to project.height,if (exact && !composite) clip.id else null,if (exact) editGeneration else 0)
        visibleFilm.add(key)
        filmFrames[key]?.let { return it }
        if (closed || filmRequests.size>=6 || key in filmRequests) return null
        val work=FilmWork()
        filmRequests[key]=work
        val snapshot=if (composite) filmProject ?: project.duplicate().also { filmProject=it } else project.copy(
            clips=mutableListOf(clip.duplicate()),transitions=mutableListOf())
        work.future=filmstrip.submit {
            filmIdle?.cancel(false)
            filmWork=work
            val frame=runCatching {
                if (closed || work.cancelled) return@runCatching null
                val edge=max(snapshot.width,snapshot.height)
                val w=max(2,144*snapshot.width/edge)
                val h=max(2,144*snapshot.height/edge)
                val renderer=filmRenderer ?: Frames(cancelled={ closed || filmWork?.cancelled==true }).also { filmRenderer=it }
                renderer.render(snapshot,at,w,h,preview=true)
            }.getOrNull()
            filmWork=null
            if (!closed) runCatching { filmIdle=filmstrip.schedule(::closeFilmRenderer,500,TimeUnit.MILLISECONDS) }
            ui.post {
                if (filmRequests[key] === work) filmRequests.remove(key)
                if (frame!=null) {
                    if (closed || work.cancelled) frame.recycle()
                    else {
                        filmFrames[key]=frame
                        if (filmFrames.size>128) filmFrames.remove(filmFrames.keys.first())?.recycle()
                    }
                }
                if (!closed && frame!=null && !work.cancelled) changed()
            }
        }
        return null
    }

    private fun closeFilmRenderer() {
        runCatching { filmRenderer?.close() }
        filmRenderer=null
    }

    fun waveDetail(path: String,source: Double): Pair<Float,Float>? {
        val millisecond=(source*1000).toLong().coerceAtLeast(0)
        val key=WaveTile(path,(millisecond/4000).toInt())
        waveTiles[key]?.let {
            val index=(millisecond%4000).toInt()*2
            return it[index] to it[index+1]
        }
        if (closed || waveRequests.size>=8 || !waveRequests.add(key)) return null
        detail.execute {
            val values=FloatArray(8000)
            runCatching {
                RandomAccessFile(cache.get(path),"r").use { input ->
                    val start=key.tile*4000L*48L*8L
                    val size=min(4000L*384,(input.length()-start).coerceAtLeast(0L)).toInt()
                    input.seek(start)
                    val buffer=ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
                    input.readFully(buffer.array())
                    var i=0
                    while (buffer.remaining()>=4) {
                        var lower=0f;var upper=0f
                        repeat(min(96,buffer.remaining()/4)) {
                            val sample=buffer.float
                            lower=min(lower,sample);upper=max(upper,sample)
                        }
                        values[i*2]=lower;values[i*2+1]=upper
                        i++
                    }
                }
            }
            ui.post {
                waveRequests.remove(key)
                if (!closed) {
                    waveTiles[key]=values
                    if (waveTiles.size>64) waveTiles.remove(waveTiles.keys.first())
                    changed()
                }
            }
        }
        return null
    }

    fun request(clip: Clip) {
        if (!requested.add(clip.path)) return
        worker.execute {
            if (clip.photo) {
                val image = try { PhotoSource.frame(clip.path, 192, 108) } catch (_: Exception) { null }
                ui.post {
                    if (image != null) thumbnails[clip.path] = listOf(image)
                    durations[clip.path] = 3.0; waves[clip.path] = FloatArray(0); changed()
                }
                return@execute
            }
            val reader = MediaMetadataRetriever()
            var duration = clip.sourceOut
            var hasAudio = clip.audio
            try {
                reader.setDataSource(clip.path)
                duration = Mp4Audio.presentedDuration(File(clip.path)) ?:
                    (reader
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toDoubleOrNull() ?: 0.0) / 1000
                hasAudio = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                val sourceDuration = duration
                ui.post { durations[clip.path] = sourceDuration }
                if (clip.video) {
                    val hdr = WorkingColor.hdr(clip.path)
                    val decoder =
                        if (hdr || File(clip.path + ".primaries").isFile)
                            try {
                                VideoDecoder(clip.path)
                            } catch (_: Exception) {
                                null
                            }
                        else null
                    val images = mutableListOf<Bitmap>()
                    try {
                        for (i in 0 until 12) {
                            val image =
                                decoder?.frame(duration * i / 12, 144, 81)
                                    ?: SourceMirror.frame(
                                        clip.path,
                                        reader.getScaledFrameAtTime(
                                            (duration * i / 12 * 1_000_000).toLong(),
                                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                            144,
                                            81,
                                        ),
                                    )
                            if (image != null) images.add(image)
                            if (i == 0 || i == 11) {
                                val ready = images.toList()
                                ui.post {
                                    thumbnails[clip.path] = ready
                                    changed()
                                }
                            }
                        }
                    } finally {
                        decoder?.close()
                    }
                }
            } catch (_: Exception) {
                ui.post { changed() }
            } finally {
                reader.release()
            }
            val waveform =
                try {
                    // Original-source 10 ms peaks also guide quiet-point selection after AV unlinking.
                    if (hasAudio) cache.waveform(clip.path, max(1, ceil(duration * 100).toInt())) else FloatArray(0)
                } catch (_: Exception) {
                    FloatArray(0)
                }
            ui.post {
                waves[clip.path] = waveform
                changed()
            }
        }
    }

    fun close() {
        closed=true
        filmRequests.values.forEach(FilmWork::cancel)
        filmRequests.clear()
        filmstrip.execute(::closeFilmRenderer)
        filmstrip.shutdown()
        filmFrames.values.forEach(Bitmap::recycle)
        filmFrames.clear()
        detail.shutdownNow()
        worker.shutdown()
    }

    private companion object {
        val stillFilmEffects=setOf("Color LUTs","Crop","Mirror","Rotation","Zoom","Image")
    }
}
