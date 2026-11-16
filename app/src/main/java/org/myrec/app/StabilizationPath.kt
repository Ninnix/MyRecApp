package org.myrec.app

import kotlin.math.*

enum class StabilizationLevel(val sigma: Double, val maximumZoom: Double, val detail: String) {
    Off(0.0,1.0,"Keep the original camera movement."),
    Light(.12,1.045,"Soften small shakes. Keep more of the frame."),
    Standard(.28,1.10,"Smooth handheld movement with a balanced crop."),
    Strong(.5,1.18,"Steadier movement with a tighter crop.")
}

data class StabilizationPose(val x: Double = 0.0, val y: Double = 0.0,
    val angle: Double = 0.0, val logScale: Double = 0.0) {
    val scale get() = exp(logScale)
    fun valid() = x.isFinite() && y.isFinite() && angle.isFinite() && logScale.isFinite() && abs(logScale)<8
    fun applying(other: StabilizationPose): StabilizationPose {
        val a = scale*cos(angle); val b = scale*sin(angle)
        return StabilizationPose(a*other.x-b*other.y+x,b*other.x+a*other.y+y,
            angle+other.angle,logScale+other.logScale)
    }
    fun inverse(): StabilizationPose {
        val a = cos(angle)/scale; val b = sin(angle)/scale
        return StabilizationPose(-a*x-b*y,b*x-a*y,-angle,-logScale)
    }
    fun attenuated(amount: Double) = StabilizationPose(x*amount,y*amount,angle*amount,logScale*amount)
    fun requiredZoom(width: Int, height: Int): Double {
        val c = cos(angle); val s = sin(angle); val hw = width/2.0; val hh = height/2.0
        val dx = scale*hw-abs(c*x+s*y); val dy = scale*hh-abs(-s*x+c*y)
        if (dx <= 0 || dy <= 0) return Double.POSITIVE_INFINITY
        return max(1.0,max((abs(c)*hw+abs(s)*hh)/dx,(abs(s)*hw+abs(c)*hh)/dy))
    }
    fun transform(width: Int, height: Int, zoom: Double = 1.0): DoubleArray {
        val a = scale*cos(angle)*zoom; val b = scale*sin(angle)*zoom
        val cx = width/2.0; val cy = height/2.0
        return doubleArrayOf(a,b,cx-a*cx+b*cy+x*zoom,cy-b*cx-a*cy+y*zoom)
    }
}

data class StabilizationSample(val time: Double, val pose: StabilizationPose, val segment: Int)
data class StabilizationAnalysis(val width: Int, val height: Int, val duration: Double,
    val samples: List<StabilizationSample>, val tracked: Int, val firstSourceTime: Double = 0.0) {
    val trackingFraction get() = tracked.toDouble()/max(1,samples.size-1)
    fun valid() = width in 1..32768 && height in 1..32768 && duration.isFinite() && duration > 0 &&
        firstSourceTime.isFinite() && firstSourceTime in 0.0..604800.0 &&
        samples.isNotEmpty() && samples.size <= 600000 && tracked in 0 until samples.size &&
        samples.all { it.time.isFinite() && it.time >= 0 && it.time < duration+.001 && it.pose.valid() && it.segment >= 0 } &&
        (1 until samples.size).all { samples[it-1].time < samples[it].time && samples[it-1].segment <= samples[it].segment }
}

data class StabilizationPlan(val width: Int, val height: Int, val samples: List<StabilizationSample>,
    val zoom: Double, val limited: Boolean, val trackingFraction: Double) {
    fun transform(time: Double): DoubleArray {
        if (samples.isEmpty()) return doubleArrayOf(1.0,0.0,0.0,0.0)
        var low = 0; var high = samples.size
        while (low < high) {
            val middle = (low+high)/2
            if (samples[middle].time <= time+.000001) low = middle+1 else high = middle
        }
        // Correct the source image actually held at this PTS, never a blend of two paths.
        return samples[max(0,low-1)].pose.transform(width,height,zoom)
    }
}

object StabilizationPath {
    fun make(analysis: StabilizationAnalysis, level: StabilizationLevel,
        cancelled: () -> Boolean = { false }): StabilizationPlan {
        require(analysis.valid()) { "Invalid stabilization analysis" }
        fun checkCancelled() { if (cancelled()) throw java.io.InterruptedIOException("Stabilization cancelled") }
        if (level == StabilizationLevel.Off) return StabilizationPlan(analysis.width,analysis.height,emptyList(),1.0,false,analysis.trackingFraction)
        val runtime=Runtime.getRuntime();val available=runtime.maxMemory()-(runtime.totalMemory()-runtime.freeMemory())
        require(analysis.samples.size*200L+16L*1024*1024<=available) { "Not enough memory to plan this stabilization range. Choose a shorter range." }
        val source = analysis.samples
        val corrections = ArrayList<StabilizationSample>(source.size)
        val inverses = source.map { it.pose.inverse() }
        var left = 0; var right = 0; var segmentStart = 0; var segmentEnd = 0
        fun smoothstep(value: Double): Double { val x = value.coerceIn(0.0,1.0); return x*x*(3-2*x) }
        for (i in source.indices) {
            if (i%128 == 0) checkCancelled()
            val current = source[i]; val radius = level.sigma*3
            if (i == 0 || source[i-1].segment != current.segment) {
                segmentStart = i; segmentEnd = i+1
                while (segmentEnd < source.size && source[segmentEnd].segment == current.segment) segmentEnd++
            }
            while (left < i && (source[left].time < current.time-radius || source[left].segment != current.segment)) left++
            right = max(i+1,right)
            while (right < source.size && source[right].segment == current.segment && source[right].time <= current.time+radius) right++
            var sw = 0.0; var st = 0.0; var stt = 0.0
            val sums = DoubleArray(3); val timeSums = DoubleArray(3)
            for (j in left until right) if (source[j].segment == current.segment) {
                val dt = source[j].time-current.time; val w = exp(-.5*(dt/level.sigma).pow(2))
                sw += w; st += w*dt; stt += w*dt*dt
                val p = inverses[j]
                sums[0] += w*p.x; timeSums[0] += w*dt*p.x
                sums[1] += w*p.y; timeSums[1] += w*dt*p.y
                sums[2] += w*p.angle; timeSums[2] += w*dt*p.angle
            }
            val determinant = sw*stt-st*st
            val values = DoubleArray(3) { if (determinant > 1e-10) (sums[it]*stt-timeSums[it]*st)/determinant else sums[it]/max(sw,1e-10) }
            // Keep deliberate zoom; smooth translation/rotation in world coordinates.
            val smooth = StabilizationPose(values[0],values[1],values[2],inverses[i].logScale).inverse()
            var confidence = 1.0
            if (segmentStart > 0) confidence = min(confidence,smoothstep((current.time-source[segmentStart].time)/.3))
            if (segmentEnd < source.size) confidence = min(confidence,smoothstep((source[segmentEnd-1].time-current.time)/.3))
            corrections.add(StabilizationSample(current.time,smooth.applying(inverses[i]).attenuated(confidence),current.segment))
        }
        val margin = 1+3.0/min(analysis.width,analysis.height)
        val allowed = DoubleArray(corrections.size) { i ->
            val pose = corrections[i].pose
            if (pose.requiredZoom(analysis.width,analysis.height)*margin <= level.maximumZoom) 1.0
            else {
                var low = 0.0; var high = 1.0
                repeat(24) {
                    val middle = (low+high)/2
                    if (pose.attenuated(middle).requiredZoom(analysis.width,analysis.height)*margin <= level.maximumZoom) low = middle else high = middle
                }
                low
            }
        }
        var limited = false; var zoom = 1.0
        left = 0; right = 0
        for (i in corrections.indices) {
            checkCancelled()
            val sample = corrections[i]; val time = sample.time
            while (left < i && corrections[left].time < time-.7) left++
            right = max(i+1,right)
            while (right < corrections.size && corrections[right].time <= time+.7) right++
            var strength = allowed[i]
            for (j in left until right) if (allowed[j] < 1 && corrections[j].segment == sample.segment)
                strength = min(strength,allowed[j]+(1-allowed[j])*smoothstep(abs(time-corrections[j].time)/.7))
            limited = limited || strength < .97
            val pose = sample.pose.attenuated(strength)
            corrections[i] = sample.copy(pose = pose)
            zoom = max(zoom,pose.requiredZoom(analysis.width,analysis.height))
        }
        if (zoom > 1.00001) zoom = min(level.maximumZoom,zoom*margin)
        return StabilizationPlan(analysis.width,analysis.height,corrections,zoom,limited,analysis.trackingFraction)
    }
}

object StabilizationSettings {
    fun modern(effect: Effect) = effect.kind == "Stabilize" && effect.values["stabilization"] == "1"
    fun level(effect: Effect) = StabilizationLevel.entries.firstOrNull { it.name == effect.values["level"] }
        ?: error("Invalid stabilization level")
    fun initialize(effect: Effect) { effect.values["stabilization"] = "1"; effect.values["level"] = "Standard" }
    fun bind(effect: Effect, clip: Clip, previous: Effect?) {
        val start = previous?.values?.get("referenceStart") ?: clip.sourceIn.toString()
        val end = previous?.values?.get("referenceEnd") ?: clip.sourceOut.toString()
        effect.values["referenceStart"] = start; effect.values["referenceEnd"] = end
    }
    fun range(effect: Effect, clip: Clip): Pair<Double,Double> {
        val start = effect.values["referenceStart"]?.toDoubleOrNull() ?: clip.sourceIn
        val end = effect.values["referenceEnd"]?.toDoubleOrNull() ?: clip.sourceOut
        require(start.isFinite() && end.isFinite() && start >= 0 && end > start && end <= 604800)
        return min(start,clip.sourceIn) to max(end,clip.sourceOut)
    }
    fun valid(effect: Effect, clip: Clip) = runCatching {
        require(effect.values["stabilization"] == "1"); level(effect); range(effect,clip); true
    }.getOrDefault(false)
}
