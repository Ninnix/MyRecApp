package org.myrec.app

import kotlin.math.*

class RecordingGain {
    companion object {
        fun normalized(db: Float) = if (db.isFinite()) (sign(db) * floor(abs(db.coerceIn(-40f, 12f)) * 2 + .5f) / 2) else 0f
    }
    @Volatile var db = 0f
        private set
    private var current = 1f
    private var target = 1f
    private var remaining = 0

    @Synchronized fun set(value: Float) {
        db = normalized(value)
        val next = 10.0.pow(db / 20.0).toFloat()
        if (next != target) { target = next; remaining = 960 }
    }

    @Synchronized fun process(input: FloatArray, samples: Int, channels: Int): FloatArray {
        require(channels in 1..2 && samples in 0..input.size && samples % channels == 0)
        val output = FloatArray(samples / channels * 2)
        for (frame in 0 until samples / channels) {
            if (remaining > 0) { current += (target - current) / remaining; remaining-- }
            else current = target
            for (ch in 0..1) {
                val value = input[frame * channels + min(ch, channels - 1)]
                output[frame * 2 + ch] = if (value.isFinite()) value * current else 0f
            }
        }
        return output
    }
}

class RecordingReadings {
    @Volatile var lastAudio = Long.MIN_VALUE
        private set
    @Volatile var peakDB = -160f
        private set
    @Volatile private var lastClip = Long.MIN_VALUE

    fun update(pcm: FloatArray, now: Long) {
        if (pcm.isEmpty()) return
        var peak = 0f
        for (value in pcm) if (value.isFinite()) peak = max(peak, abs(value))
        val decay = if (lastAudio == Long.MIN_VALUE) 160f else ((now - lastAudio).coerceAtLeast(0) / 1e9 * 70.3125).toFloat()
        peakDB = max((20 * log10(max(peak, 1e-8f))).coerceAtLeast(-160f), peakDB - decay)
        if (peak >= .988f) lastClip = now
        lastAudio = now
    }
    fun arriving(now: Long) = lastAudio != Long.MIN_VALUE && now - lastAudio in 0..800_000_000
    fun clipping(now: Long) = lastClip != Long.MIN_VALUE && now - lastClip in 0..350_000_000
    fun clearLevel() { peakDB = -160f; lastClip = Long.MIN_VALUE }
    fun reset() { lastAudio = Long.MIN_VALUE; clearLevel() }
}

class RecordingMicrophoneRoute {
    data class Decision(val request: String? = null, val recheck: Double? = null,
        val disconnected: String? = null, val failed: String? = null)
    companion object {
        fun priority(saved: List<String>, discovered: List<String>, actual: String?): List<String> = buildList {
            addAll(saved.distinct())
            if (isEmpty() && actual in discovered) add(actual!!)
            for (id in discovered) if (id !in this) add(id)
        }
    }
    var pinned: String? = null
        private set
    private var missingSince: Double? = null
    private var removalReported = false
    private val refused = mutableSetOf<String>()
    private var pending: String? = null
    private var pendingActual: String? = null
    private var attempts = 0
    private var nextAttempt = 0.0

    fun reset() {
        pinned = null; missingSince = null; removalReported = false
        refused.clear(); pending = null; pendingActual = null; attempts = 0; nextAttempt = 0.0
    }
    fun select(id: String) { reset(); pinned = id }
    fun retry() { pending = null; refused.clear(); attempts = 0; nextAttempt = 0.0 }
    fun disconnected(previous: List<String>) {
        if (pinned in previous) removalReported = true
        pending = null; refused.clear()
    }
    fun resolve(ranked: List<String>, actual: String?, preferred: String?, inTake: Boolean, now: Double): Decision {
        if (inTake && pinned == null) pinned = ranked.firstOrNull() ?: actual
        var target = pinned ?: ranked.firstOrNull()
        var disconnected: String? = null
        if (pinned != null && pinned !in ranked) {
            val first = ranked.firstOrNull { it !in refused || it == actual }
            if (actual == pinned || first == null) {
                missingSince = null; pending = null
                return Decision(recheck = 1.0)
            }
            val since = missingSince ?: now.also { missingSince = it }
            val remaining = since + (if (removalReported) .5 else 3.0) - now
            if (remaining > 0) return Decision(recheck = remaining)
            target = first
            if (actual == first) {
                if (inTake) disconnected = pinned
                pinned = if (inTake) first else null
                missingSince = null; removalReported = false; refused.clear()
            }
        } else { missingSince = null; removalReported = false; refused.clear() }
        if (target == null || target == actual && target == preferred) {
            pending = null
            return Decision(disconnected = disconnected)
        }
        if (pending != target || pendingActual != actual) {
            pending = target; pendingActual = actual; attempts = 0; nextAttempt = now
        }
        val delays = doubleArrayOf(.25, .5, 1.0, 1.5)
        if (now < nextAttempt) return Decision(recheck = nextAttempt - now, disconnected = disconnected)
        if (attempts < delays.size) {
            val delay = delays[attempts++]
            nextAttempt = now + delay
            return Decision(request = target, recheck = delay, disconnected = disconnected)
        }
        if (attempts == delays.size && actual != target) {
            if (missingSince != null && ranked.any { it != target && it !in refused }) {
                refused.add(target); pending = null
                return Decision(recheck = .05, disconnected = disconnected)
            }
            attempts++
            return Decision(failed = target, disconnected = disconnected)
        }
        return Decision(disconnected = disconnected)
    }
}

/** Video and microphone samples use the same host clock, including queued samples at a pause boundary. */
class RecordingClock {
    private data class Span(val start: Long, var end: Long, val offset: Long)
    data class Slice(val from: Int, val count: Int, val frame: Long, val segment: Int)
    private val spans = mutableListOf<Span>()
    private var ended = false
    val started: Boolean @Synchronized get() = spans.isNotEmpty()
    @Synchronized fun start(now: Long) { spans.clear(); spans.add(Span(now, Long.MAX_VALUE, 0)); ended = false }
    @Synchronized fun pause(now: Long) {
        spans.lastOrNull()?.takeIf { it.end == Long.MAX_VALUE }?.let { it.end = max(it.start, now) }
    }
    @Synchronized fun resume(now: Long) {
        val last = spans.lastOrNull() ?: return
        if (!ended && last.end != Long.MAX_VALUE) spans.add(Span(max(now, last.end), Long.MAX_VALUE, last.offset + last.end - last.start))
    }
    @Synchronized fun stop(now: Long) { pause(now); ended = true }
    @Synchronized fun closedTime()=spans.lastOrNull()?.takeIf { it.end!=Long.MAX_VALUE }?.let { it.offset+it.end-it.start } ?: 0L
    @Synchronized fun time(now: Long): Long? {
        val index = spans.indexOfLast { it.start <= now }
        val span = spans.getOrNull(index) ?: return null
        return if (now < span.end) span.offset + now - span.start else null
    }
    @Synchronized fun slices(start: Long, frames: Int): List<Slice> {
        val end = start + frames * 1_000_000_000L / 48000
        val result = mutableListOf<Slice>()
        var first = spans.binarySearch { it.start.compareTo(start) }
        if (first < 0) first = (-first - 2).coerceAtLeast(0)
        for (i in first until spans.size) {
            val span = spans[i]
            if (span.start >= end) break
            val from = ((max(0L, span.start - start) * 48000 + 999_999_999) / 1_000_000_000).coerceAtMost(frames.toLong()).toInt()
            val until = if (span.end >= end) frames else
                ((max(0L, span.end - start) * 48000 + 999_999_999) / 1_000_000_000).coerceAtMost(frames.toLong()).toInt()
            if (until > from) {
                val nanos = span.offset + start + from * 1_000_000_000L / 48000 - span.start
                val frame = nanos / 1_000_000_000 * 48000 + (nanos % 1_000_000_000 * 48000 + 500_000_000) / 1_000_000_000
                result.add(Slice(from, until - from, frame, i))
            }
        }
        return result
    }
}

/** Small clock corrections crossfade at a quiet window, rather than modulating a whole buffer. */
class RecordingAudioClock {
    var nextFrame = 0L
        private set
    var insertedSilence = 0L
        private set
    var corrections = 0L
        private set
    var maximumOffset = 0L
        private set
    private var lastSegment = -1
    private var expected = Long.MIN_VALUE

    fun process(pcm: FloatArray, timestamp: Long, clock: RecordingClock, endFrame: Long = Long.MAX_VALUE,
        output: (FloatArray, Int, Int) -> Unit) {
        for (slice in clock.slices(timestamp, pcm.size / 2)) {
            val time = timestamp + slice.from * 1_000_000_000L / 48000
            val contiguous = lastSegment == slice.segment && expected != Long.MIN_VALUE && abs(time - expected) < 2_000_000
            var samples = pcm
            var from = slice.from
            var count = slice.count
            if (contiguous && slice.frame >= 0) {
                val offset = slice.frame - nextFrame
                maximumOffset = max(maximumOffset, abs(offset))
                if (abs(offset) > 72 && count > 2) {
                    val amount = min(abs(offset) - 48, max(1, count / 1000).toLong()).toInt() * if (offset > 0) 1 else -1
                    samples = adjust(pcm, from, count, amount); from = 0; count += amount; corrections += amount
                }
            }
            val target = if (contiguous && slice.frame >= 0) nextFrame else slice.frame
            require(target - nextFrame < 480000) { "Microphone timestamp jumped by more than ten seconds" }
            pad(min(target, endFrame), output)
            val skip = min(count.toLong(), max(0, nextFrame - target)).toInt()
            val keep = min((count - skip).toLong(), max(0, endFrame - nextFrame)).toInt()
            if (keep > 0) { output(samples, from + skip, keep); nextFrame += keep }
            expected = time + slice.count * 1_000_000_000L / 48000
            lastSegment = slice.segment
        }
    }
    fun pad(endFrame: Long, output: (FloatArray, Int, Int) -> Unit) {
        val zeros = FloatArray(2048)
        while (nextFrame < endFrame) {
            val count = min(1024L, endFrame - nextFrame).toInt()
            output(zeros, 0, count); nextFrame += count; insertedSilence += count
        }
    }
    private fun adjust(pcm: FloatArray, from: Int, count: Int, amount: Int): FloatArray {
        val radius = min(16, (count - abs(amount) - 2) / 4)
        var energy = 0.0
        fun squared(i: Int): Double { val a = pcm[(from + i) * 2].toDouble(); val b = pcm[(from + i) * 2 + 1].toDouble(); return a*a+b*b }
        for (i in 0 until radius * 2) energy += squared(i)
        var best = Double.POSITIVE_INFINITY; var center = radius
        for (candidate in radius until count - radius - abs(amount)) {
            if (energy < best) { best = energy; center = candidate }
            energy += squared(candidate + radius) - squared(candidate - radius)
        }
        val result = FloatArray((count + amount) * 2)
        val begin = center - radius; val end = min(count + amount, center + radius + max(0, amount))
        pcm.copyInto(result, 0, from * 2, (from + begin) * 2)
        for (i in begin until end) for (ch in 0..1) {
            val weight = (i - begin).toFloat() / max(1, end - begin - 1)
            val a = pcm[(from + min(count - 1, i)) * 2 + ch]
            val b = pcm[(from + (i - amount).coerceIn(0, count - 1)) * 2 + ch]
            result[i * 2 + ch] = a + weight * (b - a)
        }
        if (end < count + amount) pcm.copyInto(result, end * 2, (from + end - amount) * 2, (from + count) * 2)
        return result
    }
}
