package org.myrec.app

import android.graphics.Matrix
import android.graphics.RectF
import android.system.Os
import java.io.File
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

object StabilizationRenderer {
    internal const val VERSION = "myrec-stabilization-2"
    var directory: File? = null
    private data class Source(val stamp: String, val hash: String)
    private data class Entry(val analysis: StabilizationAnalysis, val begin: Double) {
        val plans = ConcurrentHashMap<StabilizationLevel,StabilizationPlan>()
    }
    private val sources = LinkedHashMap<String,Source>(16,.75f,true)
    private val entries = LinkedHashMap<String,Entry>(8,.75f,true)
    private val analysisLock = Any()
    private fun weight(entry: Entry)=entry.analysis.samples.size*104L+
        entry.plans.values.filter { it.samples!==entry.analysis.samples }.sumOf { it.samples.size*104L }
    private fun trim(keep: Entry) = synchronized(entries) {
        val budget=min(128L*1024*1024,Runtime.getRuntime().maxMemory()/4)
        val iterator=entries.entries.iterator()
        while ((entries.size>8 || entries.values.sumOf(::weight)>budget) && iterator.hasNext())
            if (iterator.next().value!==keep) iterator.remove()
    }
    internal fun clear() { synchronized(entries) { entries.clear() }; synchronized(sources) { sources.clear() } }
    private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }
    private fun stamp(path: String): String {
        fun stat(file: File): String = if (!file.exists()) "missing" else Os.stat(file.path).let {
            "${it.st_dev}:${it.st_ino}:${it.st_size}:${it.st_mtim.tv_sec}:${it.st_mtim.tv_nsec}:${it.st_ctim.tv_sec}:${it.st_ctim.tv_nsec}"
        }
        return stat(File(path))+"|"+stat(File(path+".mirror"))+"|"+stat(File(path+".primaries"))
    }
    private fun cancelled() = Thread.currentThread().isInterrupted
    internal fun identity(path: String, requestCancelled: () -> Boolean): String {
        val stamp = stamp(path)
        synchronized(sources) { sources[path]?.takeIf { it.stamp == stamp }?.let { return it.hash } }
        val stop = { requestCancelled() || cancelled() }
        val parts = mutableListOf(SourceAudio.hash(File(path),stop))
        for (suffix in listOf(".mirror",".primaries")) {
            val side = File(path+suffix)
            require(!side.isFile || side.length() <= 65536) { "Invalid source sidecar" }
            parts.add(suffix+":"+if (side.isFile) SourceAudio.hash(side,stop) else "none")
        }
        val hash = digest(parts.joinToString("|"))
        check(stamp == stamp(path)) { "Source changed during stabilization verification" }
        synchronized(sources) {
            sources[path] = Source(stamp,hash)
            while (sources.size > 16) sources.remove(sources.keys.first())
        }
        return hash
    }
    private fun key(hash: String, begin: Double, end: Double) = digest("$VERSION|$hash|$begin|$end")
    private fun entry(clip: Clip, effect: Effect, requestCancelled: () -> Boolean, progress: (Float?) -> Unit): Entry {
        if (requestCancelled() || cancelled()) throw InterruptedIOException("Stabilization cancelled")
        val (begin,end) = StabilizationSettings.range(effect,clip)
        val hash = identity(clip.path,requestCancelled); val key = key(hash,begin,end)
        synchronized(entries) { entries[key]?.let { return it } }
        synchronized(analysisLock) {
            synchronized(entries) { entries[key]?.let { return it } }
            val file = directory?.let { File(it,"$key.path") }
            val analysis = file?.let { StabilizationCache.read(it,key) } ?: try {
                progress(0f)
                val stamp = stamp(clip.path)
                val checkpoint=file?.resolveSibling("$key.checkpoint")
                val measured = measure(clip.path,hash,begin,end,requestCancelled,progress,checkpoint)
                check(stamp == stamp(clip.path)) { "Source changed during stabilization analysis" }
                file?.let { StabilizationCache.write(it,key,measured) }
                checkpoint?.delete()
                StabilizationCache.retire(directory,key)
                measured
            } finally { progress(null) }
            val entry = Entry(analysis,begin)
            synchronized(entries) {
                entries[key] = entry
                trim(entry)
            }
            return entry
        }
    }
    private fun plan(entry: Entry, effect: Effect, requestCancelled: () -> Boolean): StabilizationPlan {
        val level=StabilizationSettings.level(effect)
        val result=synchronized(entry) {
            entry.plans[level] ?: StabilizationPath.make(entry.analysis,level) { requestCancelled() || cancelled() }.also {
                entry.plans.clear();entry.plans[level]=it
            }
        }
        trim(entry);return result
    }
    fun status(clip: Clip, effect: Effect): StabilizationPlan? {
        val (begin,end) = StabilizationSettings.range(effect,clip)
        val source = synchronized(sources) { sources[clip.path] } ?: return null
        if (source.stamp != stamp(clip.path)) return null
        return synchronized(entries) { entries[key(source.hash,begin,end)] }?.plans?.get(StabilizationSettings.level(effect))
    }
    fun frameTime(clip: Clip, effect: Effect, time: Double, requestCancelled: () -> Boolean = { false }, progress: (Float?) -> Unit): Double {
        val entry = entry(clip,effect,requestCancelled,progress)
        val samples = entry.analysis.samples
        var a = 0; var b = samples.size
        while (a < b) { val m = (a+b)/2; if (samples[m].time <= time-entry.begin+.000001) a = m+1 else b = m }
        return if (a <= 1) entry.analysis.firstSourceTime else entry.begin+samples[a-1].time
    }
    fun matrix(clip: Clip, effect: Effect, time: Double, bounds: RectF, requestCancelled: () -> Boolean = { false }, progress: (Float?) -> Unit): Matrix {
        val entry = entry(clip,effect,requestCancelled,progress); val plan = plan(entry,effect,requestCancelled)
        val values = plan.transform(time-entry.begin)
        val a = values[0]; val b = values[1]
        val sx = bounds.width()/plan.width; val sy = bounds.height()/plan.height
        val bx = b*sx/sy; val by = b*sy/sx
        return Matrix().apply { setValues(floatArrayOf(a.toFloat(),-bx.toFloat(),
            (bounds.left-a*bounds.left+bx*bounds.top+values[2]*sx).toFloat(),
            by.toFloat(),a.toFloat(),(bounds.top-by*bounds.left-a*bounds.top+values[3]*sy).toFloat(),0f,0f,1f)) }
    }
    fun originalTime(path: String, time: Double, requestCancelled: () -> Boolean, progress: (Float?) -> Unit): Double {
        val hash = identity(path,requestCancelled)
        val index = VideoFrameIndex.get(path,hash) { value ->
            if (requestCancelled() || cancelled()) throw InterruptedIOException("Stabilization cancelled")
            progress(value)
        }
        return index.times[index.last(time)]
    }
    private fun measure(path: String, hash: String, begin: Double, end: Double, requestCancelled: () -> Boolean, progress: (Float?) -> Unit,checkpoint: File?): StabilizationAnalysis {
        val index = VideoFrameIndex.get(path,hash) { value ->
            if (requestCancelled() || cancelled()) throw InterruptedIOException("Stabilization cancelled")
            progress(value)
        }
        val first = index.last(begin); val last = max(first,index.first(end)-1)
        require(last-first < 600000) { "Too many source frames for stabilization" }
        require((last-first+1)*320L<Runtime.getRuntime().maxMemory()/2) { "This range exceeds the stabilization memory limit. Choose a shorter range." }
        val runtime=Runtime.getRuntime();val available=runtime.maxMemory()-(runtime.totalMemory()-runtime.freeMemory())
        require((last-first+1)*320L+16L*1024*1024<=available) { "Not enough memory to analyze this stabilization range. Choose a shorter range." }
        val (width,height) = ProjectStore.shape(path)
        val ratio = min(1.0,640.0/max(width,height))
        val targetWidth = max(64,(width*ratio).roundToInt()); val targetHeight = max(64,(height*ratio).roundToInt())
        val journal=checkpoint?.let { StabilizationJournal(it,key(hash,begin,end),width,height,end-begin,index.times[first]) }
        val samples = journal?.samples ?: ArrayList<StabilizationSample>(last-first+1)
        require(samples.size<=last-first+1) { "Invalid stabilization checkpoint" }
        var pose = samples.lastOrNull()?.pose ?: StabilizationPose(); var segment = samples.lastOrNull()?.segment ?: 0
        var tracked = max(0,samples.size-1-segment)
        fun add(sample: StabilizationSample) { if (journal!=null) journal.add(sample) else samples.add(sample) }
        try {
        if (samples.isEmpty()) add(StabilizationSample(0.0,pose,segment))
        progress(samples.size.toFloat()/(last-first+1))
        VideoDecoder(path).use { decoder ->
            var previous = decoder.frame(index.times[first+samples.size-1],targetWidth,targetHeight) ?: error("Cannot analyze source video")
            try {
                var previousTime = index.times[first+samples.size-1]
                for (i in first+samples.size..last) {
                    if (requestCancelled() || cancelled()) throw InterruptedIOException("Stabilization cancelled")
                    val time = index.times[i]
                    val current = decoder.frame(time,targetWidth,targetHeight) ?: error("A stabilization source frame is unreadable")
                    try {
                        val motion = Native.similarity(previous,current,cameraMotion = true)
                        val a = motion[0].toDouble(); val b = motion[1].toDouble()
                        val cx = previous.width/2.0; val cy = previous.height/2.0
                        val step = StabilizationPose((a*cx-b*cy+motion[2]-cx)*width/previous.width,
                            (b*cx+a*cy+motion[3]-cy)*height/previous.height,atan2(b,a),ln(hypot(a,b)))
                        val next = step.applying(pose)
                        if (motion[4] >= 12 && time-previousTime < .15 && next.valid()) { pose = next; tracked++ }
                        else { segment++; pose = StabilizationPose() }
                        add(StabilizationSample(max(0.0,time-begin),pose,segment))
                    } catch (error: Throwable) { current.recycle(); throw error }
                    previous.recycle(); previous = current; previousTime = time
                    if ((i-first)%8 == 0) progress((i-first).toFloat()/(last-first))
                }
            } finally { previous.recycle() }
        }
        return StabilizationAnalysis(width,height,end-begin,samples,tracked,index.times[first]).also { require(it.valid()) }
        } finally { journal?.close() }
    }
}
