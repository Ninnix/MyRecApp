package org.myrec.app

import java.io.*
import java.nio.*
import kotlin.math.*

class BackgroundAudio(private val cache: AudioCache) : Closeable {
    private data class Source(val file: RandomAccessFile,val duration: Double,val frames: Long,var block: Long=-1,var data: FloatArray=FloatArray(0))
    private val sources=mutableMapOf<Pair<String,Double>,Source>()
    private val chunks=linkedMapOf<Triple<String,Double,Long>,FloatArray>()

    fun prepare(project: Project,cancelled: ()->Boolean,progress: (Float)->Unit) {
        for (clip in project.clips.filter { it.video }) {
            val effects=clip.effects.filter { it.enabled && it.kind in BackgroundEffect.kinds }
            for (fx in effects) {
                val mode=fx.parameter("background") ?: continue
                if ((listOf(mode.value)+mode.keyframes.map { it.value }).none { it==EffectValue.Choice("Video") }) continue
                val audio=fx.parameter("backgroundAudio") ?: continue
                if ((listOf(audio.value)+audio.keyframes.map { it.value }).none { it==EffectValue.Toggle(true) }) continue
                val media=fx.parameter("media") ?: continue
                val paths=(listOf(media.value)+media.keyframes.map { it.value }).mapNotNull { (it as? EffectValue.Media)?.path }.distinct()
                for (path in paths) {
                    val info=ProjectStore.inspect(File(path))
                    if (!info.audio || info.sourceOut<=.001) continue
                    for (rate in clip.playbackPieces().map { it.effectiveRate }.distinct()) {
                        if (path to rate in sources) continue
                        val pcm=cache.stretched(path,rate,cancelled,progress)
                        sources[path to rate]=Source(RandomAccessFile(pcm,"r"),info.sourceOut,pcm.length()/8)
                    }
                }
            }
        }
    }

    private fun sample(path: String,rate: Double,source: Source,time: Double,channel: Int): Float {
        val position=max(0.0,time)*48000/rate
        val index=floor(position).toLong()
        fun value(frame: Long): Float {
            if (frame<0 || frame>=source.frames) return 0f
            val block=frame/4096
            if (source.block==block) return source.data[(frame%4096).toInt()*2+channel]
            val key=Triple(path,rate,block)
            val data=chunks.getOrPut(key) {
                if (chunks.size>=32) chunks.remove(chunks.keys.first())
                val bytes=ByteArray(4096*8)
                source.file.seek(block*bytes.size)
                var read=0
                while (read<bytes.size) {
                    val n=source.file.read(bytes,read,bytes.size-read)
                    if (n<0) break
                    read+=n
                }
                FloatArray(8192).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
            }
            source.block=block;source.data=data
            return data[(frame%4096).toInt()*2+channel]
        }
        val a=value(index); val b=value(index+1)
        return a+(b-a)*(position-index).toFloat()
    }

    fun read(clip: Clip,time: Double,frames: Int,original: Clip=clip): FloatArray {
        val pcm=FloatArray(frames*2)
        if (!clip.video) return pcm
        val effects=clip.effects.filter { it.enabled && it.kind in BackgroundEffect.kinds }
        if (effects.isEmpty()) return pcm
        val rate=clip.effectiveRate;val duration=clip.duration;val end=clip.start+duration
        val freeze=clip.frozen
        val freezeEffect=clip.effects.firstOrNull { it.enabled && it.kind=="Freeze Frame" }
        val freezeMode=(freezeEffect?.parameter("audio")?.at(clip.sourceIn) as? EffectValue.Choice)?.value ?: freezeEffect?.values?.get("audio")
        if (freeze!=null && freezeMode in listOf("Cut","Silence")) return pcm
        for (fx in effects) {
            val origin=fx.values["origin"]?.toDoubleOrNull() ?: clip.sourceTime(clip.start+fx.from)
            val range=original.effects.firstOrNull { it.id==fx.id } ?: fx
            val begin=original.start+range.from;val finish=min(original.end,original.start+range.to)
            val controls=listOf("media","background","backgroundAudio").mapNotNull { fx.parameter(it) }
            val changes=if (freeze!=null) emptyList() else controls.flatMap { p -> p.keyframes.map { it.time } }.distinct().sorted().filter { at ->
                controls.map { it.at(max(0.0,at-.000001)) }!=controls.map { it.at(at) }
            }
            for (i in 0 until frames) {
                val at=time+i/48000.0; val local=at-clip.start
                if (at<clip.start || at>=end || !fx.active(local)) continue
                val setting=freeze?.sourceTime ?: (clip.sourceIn+local*rate)
                if ((fx.parameter("backgroundAudio")?.at(setting) as? EffectValue.Toggle)?.value!=true ||
                    (fx.parameter("background")?.at(setting) as? EffectValue.Choice)?.value!="Video") continue
                val path=(fx.parameter("media")?.at(setting) as? EffectValue.Media)?.path ?: continue
                val source=sources[path to rate] ?: continue
                val clock=if (freeze==null) setting else freeze.sourceTime+freeze.offset+local
                val phase=BackgroundEffect.loop(clock-origin,source.duration)
                var gain=10.0.pow(-18.0/20)*min(1.0,max(0.0,at-begin)/.008)*
                    min(1.0,max(0.0,finish-at)/.008)
                if (changes.isNotEmpty()) {
                    val next=changes.binarySearch(setting).let { if (it>=0) it+1 else -it-1 }
                    if (next>0) gain*=min(1.0,max(0.0,setting-changes[next-1])/rate/.008)
                    if (next<changes.size) gain*=min(1.0,max(0.0,changes[next]-setting)/rate/.008)
                }
                if (freeze!=null && freezeMode=="Fade out over hold") {
                    val hold=freezeEffect?.number("hold",freeze.duration.toFloat())?.toDouble() ?: freeze.duration
                    gain*=max(0.0,1-(freeze.offset+local)/max(.001,hold))
                }
                for (ch in 0..1) {
                    val incoming=sample(path,rate,source,phase.incoming,ch)
                    val outgoing=phase.outgoing?.let { sample(path,rate,source,it,ch) }
                    pcm[i*2+ch]+=((if (outgoing==null) incoming.toDouble() else incoming*phase.blend+outgoing*(1-phase.blend))*gain).toFloat()
                }
            }
        }
        return pcm
    }

    override fun close() { sources.values.forEach { it.file.close() }; sources.clear(); chunks.clear() }
}
