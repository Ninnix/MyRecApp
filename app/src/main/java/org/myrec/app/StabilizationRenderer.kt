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
    var directory: File? = null
    private data class Source(val stamp: String, val hash: String)
    private data class Entry(val analysis: StabilizationAnalysis, val begin: Double) {
        val plans = ConcurrentHashMap<StabilizationLevel,StabilizationPlan>()
    }
    private val sources = LinkedHashMap<String,Source>(16,.75f,true)
    private val entries = LinkedHashMap<String,Entry>(8,.75f,true)
    private val analysisLock = Any()
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
    private fun identity(path: String, requestCancelled: () -> Boolean): String {
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
    private fun key(hash: String, begin: Double, end: Double) = digest("myrec-stabilization-1|$hash|$begin|$end")
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
                val measured = measure(clip.path,hash,begin,end,requestCancelled,progress)
                check(stamp == stamp(clip.path)) { "Source changed during stabilization analysis" }
                file?.let { StabilizationCache.write(it,key,measured) }
                measured
            } finally { progress(null) }
            val entry = Entry(analysis,begin)
            synchronized(entries) {
                entries[key] = entry
                while (entries.size > 8 || entries.values.sumOf { it.analysis.samples.size*44L } > 32L*1024*1024)
                    entries.remove(entries.keys.first())
            }
            return entry
        }
    }
    private fun plan(entry: Entry, effect: Effect, requestCancelled: () -> Boolean) = entry.plans.getOrPut(StabilizationSettings.level(effect)) {
        StabilizationPath.make(entry.analysis,StabilizationSettings.level(effect)) { requestCancelled() || cancelled() }
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
    private fun measure(path: String, hash: String, begin: Double, end: Double, requestCancelled: () -> Boolean, progress: (Float?) -> Unit): StabilizationAnalysis {
        val index = VideoFrameIndex.get(path,hash) { value ->
            if (requestCancelled() || cancelled()) throw InterruptedIOException("Stabilization cancelled")
            progress(value)
        }
        val first = index.last(begin); val last = max(first,index.first(end)-1)
        require(last-first < 600000) { "Too many source frames for stabilization" }
        val (width,height) = ProjectStore.shape(path)
        val ratio = min(1.0,640.0/max(width,height))
        val targetWidth = max(64,(width*ratio).roundToInt()); val targetHeight = max(64,(height*ratio).roundToInt())
        val samples = ArrayList<StabilizationSample>(last-first+1)
        var pose = StabilizationPose(); var segment = 0; var tracked = 0
        VideoDecoder(path).use { decoder ->
            var previous = decoder.frame(index.times[first],targetWidth,targetHeight) ?: error("Cannot analyze source video")
            try {
                var previousTime = index.times[first]
                samples.add(StabilizationSample(0.0,pose,segment))
                for (i in first+1..last) {
                    if (requestCancelled() || cancelled()) throw InterruptedIOException("Stabilization cancelled")
                    val time = index.times[i]
                    val current = decoder.frame(time,targetWidth,targetHeight) ?: error("A stabilization source frame is unreadable")
                    try {
                        val motion = Native.similarity(previous,current)
                        val a = motion[0].toDouble(); val b = motion[1].toDouble()
                        val cx = previous.width/2.0; val cy = previous.height/2.0
                        val step = StabilizationPose((a*cx-b*cy+motion[2]-cx)*width/previous.width,
                            (b*cx+a*cy+motion[3]-cy)*height/previous.height,atan2(b,a),ln(hypot(a,b)))
                        val next = step.applying(pose)
                        if (motion[4] >= 6 && time-previousTime < .15 && next.valid()) { pose = next; tracked++ }
                        else { segment++; pose = StabilizationPose() }
                        samples.add(StabilizationSample(max(0.0,time-begin),pose,segment))
                    } catch (error: Throwable) { current.recycle(); throw error }
                    previous.recycle(); previous = current; previousTime = time
                    if ((i-first)%8 == 0) progress((i-first).toFloat()/(last-first))
                }
            } finally { previous.recycle() }
        }
        return StabilizationAnalysis(width,height,end-begin,samples,tracked,index.times[first]).also { require(it.valid()) }
    }
}
