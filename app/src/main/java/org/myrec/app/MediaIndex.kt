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
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

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
    private val filmstrip=Executors.newSingleThreadExecutor()
    private data class FilmFrame(val path: String,val time: Long,val recipe: List<Effect>,val canvas: Pair<Int,Int>,
                                 val composition: Long)
    private val filmFrames=LinkedHashMap<FilmFrame,Bitmap>(128,.75f,true)
    private val filmRequests=mutableSetOf<FilmFrame>()
    @Volatile private var editGeneration=1L
    private var compositeFilmstrip=false
    private var closed=false

    fun editChanged(project: Project) {
        editGeneration++
        compositeFilmstrip=project.transitions.isNotEmpty() || project.clips.any { clip ->
            clip.effects.any { it.enabled && (it.kind=="Placement" || it.subtitle!=null) }
        }
    }

    fun editedFrame(project: Project,clip: Clip,position: Double): Bitmap? {
        val at=position.coerceIn(clip.start,max(clip.start,clip.end-.000001))
        val effects=clip.effects.filter { it.active(at-clip.start) && it.kind !in Effects.audio }
        if (!compositeFilmstrip && effects.isEmpty()) return null
        val theEdit=editGeneration
        val sourceTime=clip.sourceTime(at)
        val exact=compositeFilmstrip || clip.frozen!=null || effects.any { it.motion!=null || it.phase!=null || it.parameters.any { p -> p.keyframes.isNotEmpty() } }
        val key=FilmFrame(clip.path,if (exact) (at*240000).toLong() else (sourceTime*4).toLong(),
            if (exact) emptyList() else effects.map { it.copyEffect().apply { from=0.0;to=Double.MAX_VALUE } },
            project.width to project.height,if (exact) theEdit else 0)
        filmFrames[key]?.let { return it }
        if (closed || filmRequests.size>=6 || !filmRequests.add(key)) return null
        val snapshot=if (compositeFilmstrip) project.duplicate() else project.copy(
            clips=mutableListOf(clip.duplicate()),transitions=mutableListOf())
        filmstrip.execute {
            val frame=runCatching {
                val edge=max(snapshot.width,snapshot.height)
                val w=max(2,144*snapshot.width/edge)
                val h=max(2,144*snapshot.height/edge)
                Frames(cancelled={ editGeneration!=theEdit }).use { it.render(snapshot,at,w,h) }
            }.getOrNull()
            ui.post {
                filmRequests.remove(key)
                if (frame!=null) {
                    if (closed || editGeneration!=theEdit) frame.recycle()
                    else {
                        filmFrames[key]=frame
                        if (filmFrames.size>128) filmFrames.remove(filmFrames.keys.first())?.recycle()
                    }
                }
                if (!closed && (frame!=null || editGeneration!=theEdit)) changed()
            }
        }
        return null
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
        editGeneration++
        filmstrip.shutdownNow()
        filmFrames.values.forEach(Bitmap::recycle)
        filmFrames.clear()
        detail.shutdownNow()
        worker.shutdown()
    }
}
