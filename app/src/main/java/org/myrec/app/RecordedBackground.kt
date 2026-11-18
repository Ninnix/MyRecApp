package org.myrec.app

import android.graphics.Bitmap
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A candidate cannot change temporal history until the muxer accepts its timestamp. */
class RecordedBackground(val path: File,private val fps: Int,private val publish: (Bitmap,Double)->Unit,private val failure: (Throwable)->Unit) : Closeable {
    private data class Candidate(val stamp: Long,val bitmap: Bitmap,val effects: List<Effect>)
    private val writer=BackgroundJournal.Writer(path)
    private val renderer=BackgroundRenderer()
    private val worker=Executors.newSingleThreadExecutor { task ->
        Thread({
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY) }
            task.run()
        },"recorded-background")
    }
    private var candidate: Candidate?=null
    private var processing=false
    private var indexed=false
    private var lastCadence: Pair<List<Effect>,Long>?=null
    private var closed=false
    @Volatile private var problem: Throwable?=null
    @Volatile internal var offered=0;private set
    @Volatile internal var accepted=0;private set
    @Volatile internal var lastInput=0L;private set
    @Volatile internal var lastOutput=0L;private set
    @Volatile var effects: List<Effect> = emptyList()
    @Volatile var running=true
    @Synchronized fun offer(stamp: Long,read: ()->Bitmap) {
        if (closed || !running || problem!=null || processing || effects.none { it.enabled && it.kind in BackgroundEffect.kinds }) return
        candidate?.let { if (stamp/1000-it.stamp<500000) return else it.bitmap.recycle() }
        candidate=null
        val bitmap=read()
        offered++;lastInput=stamp/1000
        candidate=Candidate(stamp/1000,bitmap,effects)
    }
    @Synchronized fun rejected(stamp: Long) {
        candidate?.takeIf { it.stamp==stamp }?.let { it.bitmap.recycle();candidate=null }
    }
    @Synchronized fun accepted(stamp: Long,time: Double,frame: Int=-1) {
        lastOutput=stamp
        val sample=candidate?.takeIf { it.stamp==stamp } ?: return
        accepted++
        if (frame>=0) indexed=true
        candidate=null;processing=true
        worker.execute {
            try { render(sample,time,frame) }
            catch (error: Throwable) { sample.bitmap.recycle();problem=error;failure(error) }
            finally { synchronized(this) { processing=false } }
        }
    }
    private fun render(sample: Candidate,time: Double,frame: Int) {
        val bitmap=sample.bitmap
        val enabled=sample.effects.filter { it.enabled && it.kind!="Dolly Zoom" && (it.kind!="Crash Zoom" || it.values["triggered"]=="1") }
        val chain=enabled.filter { it.kind=="Color LUTs" }+EffectOrder.ordered(enabled).filter { it.kind!="Color LUTs" }
        for (original in chain) {
            val fx=original.sampled(time)
            if (fx.kind in BackgroundEffect.kinds) {
                val track=fx.values["matteTrack"] ?: fx.id
                val prefix=BackgroundEffect.prefix(chain,original).map { it.sampled(time) }
                val signature=renderer.signature(fx,prefix)
                val input=renderer.inputSignature(prefix)
                // Prefix/control changes start a new temporal window, even if the effect id survives.
                renderer.render(bitmap,fx,time,fx.values["origin"]?.toDoubleOrNull() ?: 0.0,"$track:$signature",capture={ raw,final ->
                    writer.append(BackgroundJournal.Sample(track,signature,raw,final,input,frame=frame))
                })
            } else {
                if (fx.kind=="Negative Strobe" && TimeEffects.negative(original.copy(phase=EffectPhase(0.0)),time,fps.toDouble())) Native.workingNegative(bitmap,fx.strength(0.0,1.0))
                Effects.correct(bitmap,fx,fx.strength(0.0,1.0),elapsed=time-(fx.values["origin"]?.toDoubleOrNull() ?: 0.0),frame=(time*fps).toLong(),decorate=false)
            }
            FrameEffects.liveGeometry(bitmap,fx,fx.strength(0.0,1.0))
            val id=Effects.pixelId(fx)
            if (id>0) Native.image(bitmap,id,fx.strength(0.0,1.0),fx.number("colors",5f),fx.number("smoothing",.5f),fx.number("dither")==1f,fx.number("palette").toInt(),fx.number("bw")==1f)
        }
        for (fx in chain.filter { it.kind=="Letterbox" || FrameEffects.modern(it) && it.kind=="Widescreen 1953" })
            FrameEffects.decorate(bitmap,fx.sampled(time),fx.strength(0.0,1.0))
        val cadence=EffectCadence(chain.filter { ExistingEffects.modern(it) }.map { it.copyEffect().apply {
            from=values["rangeStart"]?.toDoubleOrNull() ?: values["origin"]?.toDoubleOrNull() ?: 0.0;to=604800.0
        } }).hold(time)
        val held=cadence?.let { sample.effects to it.index }
        if (effects===sample.effects && (held==null || held!=lastCadence)) { lastCadence=held;publish(bitmap,time) } else bitmap.recycle()
    }
    fun finish(source: File?) {
        synchronized(this) { closed=true;candidate?.bitmap?.recycle();candidate=null }
        worker.shutdown();check(worker.awaitTermination(30,TimeUnit.SECONDS)) { "Recorded masks did not finish" }
        try {
            problem?.let { throw it }
            if (writer.count>0 && source!=null && source.isFile && source.length()>0) writer.finish(SourceAudio.identity(source) { false },
                if (indexed) movieTimes(source) else null)
        } finally { writer.close();renderer.close();if (writer.count==0) path.delete() }
    }
    override fun close() { finish(null) }
    companion object {
        fun movieTimes(source: File): DoubleArray {
            val extractor=android.media.MediaExtractor()
            try {
                extractor.setDataSource(source.path)
                val track=(0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/")==true }
                extractor.selectTrack(track)
                var values=DoubleArray(4096);var count=0
                while (extractor.sampleTime>=0) {
                    require(count<3000000)
                    if (count==values.size) values=values.copyOf(minOf(3000000,count*2))
                    values[count++]=extractor.sampleTime/1_000_000.0;extractor.advance()
                }
                return values.copyOf(count)
            } finally { extractor.release() }
        }
    }
}
