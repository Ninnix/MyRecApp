package org.myrec.app

import android.media.*
import java.io.*
import java.nio.*
import java.security.MessageDigest
import kotlin.math.*

class AudioCache(internal val root: File) {
    init {
        root.mkdirs()
    }

    @Synchronized
    fun get(path: String, cancelled: () -> Boolean = { false }): File {
        if (cancelled()) throw InterruptedIOException("Audio preparation cancelled")
        val source = File(path)
        val hash = SourceAudio.identity(source, cancelled) + "-pcm-v8"
        val file = File(root, "$hash.f32")
        if (PcmCache.valid(file,cancelled)) return file
        val temp = File(root, "$hash.partial")
        try {
            decode(path, temp, cancelled = cancelled)
            check(temp.renameTo(file))
            PcmCache.seal(file,cancelled)
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
        return file
    }

    @Synchronized
    fun stretched(path: String, speed: Double, cancelled: () -> Boolean, progress: (Float) -> Unit): File {
        require(speed.isFinite() && speed in .1..128.0)
        return stretchPcm(get(path, cancelled), speed, cancelled, progress)
    }

    @Synchronized
    fun stretchPcm(source: File, speed: Double, cancelled: () -> Boolean, progress: (Float) -> Unit): File {
        require(speed.isFinite() && speed in .1..128.0)
        if (speed == 1.0 || source.length() == 0L) return source
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("wsola-2:${SourceAudio.identity(source,cancelled)}:${speed.toBits()}".toByteArray())
        val key = digest.digest().joinToString("") { "%02x".format(it) }
        val result = File(root, "$key.stretch.f32")
        val frames = (source.length() / 8 / speed).roundToLong()
        if (result.length() == frames * 8 && PcmCache.valid(result,cancelled)) return result
        val partial = File(root, "$key-${uid()}.partial")
        try {
            val written = Native.audioStretch(source.path, partial.path, speed) { done, total ->
                progress(if (total > 0) done.toFloat() / total else 1f)
                !cancelled()
            }
            if (cancelled()) throw InterruptedIOException("Audio preparation cancelled")
            check(written == frames && partial.length() == frames * 8) { "Cannot prepare pitch-preserved audio" }
            check(partial.renameTo(result)) { "Cannot save prepared audio" }
            PcmCache.seal(result,cancelled)
            return result
        } finally { partial.delete() }
    }

    @Synchronized fun rampPcm(source: File,plan: SpeedRamp,cancelled: ()->Boolean,progress: (Float)->Unit): File {
        val identity=SourceAudio.identity(source,cancelled)+":wsola-ramp-1:"+plan.native.joinToString(",") { it.toBits().toString() }
        val key=MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
        val result=File(root,"$key.ramp.f32")
        val frames=(plan.outputTime(source.length()/8/48000.0)*48000).roundToLong()
        if (result.length()==frames*8 && PcmCache.valid(result,cancelled)) return result
        val partial=File(root,"$key-${uid()}.partial")
        try {
            val written=Native.audioRamp(source.path,partial.path,plan.native) { done,total ->
                progress(if (total>0) done.toFloat()/total else 1f);!cancelled()
            }
            if (cancelled()) throw InterruptedIOException("Audio preparation cancelled")
            check(written==frames && partial.length()==frames*8 && partial.renameTo(result)) { "Cannot prepare continuous speed ramp" }
            PcmCache.seal(result,cancelled)
            return result
        } finally { partial.delete() }
    }

    fun head(path: String, frames: Int): FloatArray {
        val temporary = File(root, "probe-${uid()}.partial")
        try {
            decode(path, temporary, frames.toLong(), rawClock = true)
            val buffer = ByteBuffer.wrap(temporary.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(buffer.remaining()).also { buffer.get(it) }
        } finally { temporary.delete() }
    }

    private fun decode(path: String, target: File, limit: Long = Long.MAX_VALUE,
                       cancelled: () -> Boolean = { false }, rawClock: Boolean = false) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val presented = if (rawClock) Long.MAX_VALUE else Mp4Audio.presentedDuration(File(path))?.let { (it * 48000).roundToLong() } ?: Long.MAX_VALUE
        val outputLimit = min(limit, presented)
        try {
            extractor.setDataSource(path)
            val track =
                (0 until extractor.trackCount).firstOrNull {
                    extractor
                        .getTrackFormat(it)
                        .getString(MediaFormat.KEY_MIME)
                        ?.startsWith("audio/") == true
                }
            if (track == null) {
                target.writeBytes(byteArrayOf())
                return
            }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            // Older extractors express the edit as decoder delay; newer ones
            // shift packet timestamps. Apply only one of these trimming paths.
            // The encoder timing probe deliberately keeps the raw coded prefix.
            if (rawClock || extractor.sampleTime < 0) {
                format.setInteger(MediaFormat.KEY_ENCODER_DELAY, 0)
                format.setInteger(MediaFormat.KEY_ENCODER_PADDING, 0)
            }
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()
            var totalSource = 0L
            var totalOutput = 0L
            var previousLeft = 0f
            var previousRight = 0f
            val output = ByteBuffer.allocate(256 * 1024).order(ByteOrder.LITTLE_ENDIAN)
            FileOutputStream(target).buffered().use { stream ->
                while (!outputDone) {
                    if (cancelled()) throw InterruptedIOException("Audio preparation cancelled")
                    if (!inputDone) {
                        val index = codec.dequeueInputBuffer(10000)
                        if (index >= 0) {
                            val buffer = codec.getInputBuffer(index)!!
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(
                                    index,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    when (val index = codec.dequeueOutputBuffer(info, 10000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            encoding =
                                if (f.containsKey(MediaFormat.KEY_PCM_ENCODING))
                                    f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                else AudioFormat.ENCODING_PCM_16BIT
                        }
                        else ->
                            if (index >= 0) {
                                val buffer =
                                    codec.getOutputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val bytes = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                                val frames = info.size / (bytes * channels)
                                val samples = FloatArray(frames * 2)
                                for (i in 0 until frames) {
                                    var l = 0f
                                    var r = 0f
                                    for (ch in 0 until channels) {
                                        val v =
                                            if (bytes == 4) buffer.float else buffer.short / 32768f
                                        if (ch == 0) l = v
                                        if (ch == 1) r = v
                                    }
                                    samples[i * 2] = l
                                    samples[i * 2 + 1] = if (channels == 1) l else r
                                }
                                if (!rawClock && totalSource == 0L && info.presentationTimeUs < 0)
                                    totalSource = (info.presentationTimeUs * rate / 1_000_000.0).roundToLong()
                                val end = totalSource + frames
                                while (frames > 0) {
                                    val pos = totalOutput * rate / 48000.0
                                    if (pos >= end - 1 || totalOutput >= outputLimit) break
                                    val i = floor(pos).toLong() - totalSource
                                    if (i < -1) {
                                        totalOutput++
                                        continue
                                    }
                                    val frac = (pos - floor(pos)).toFloat()
                                    fun sample(ch: Int): Float {
                                        val a =
                                            if (i < 0)
                                                (if (ch == 0) previousLeft else previousRight)
                                            else samples[i.toInt() * 2 + ch]
                                        val b = samples[(i + 1).toInt() * 2 + ch]
                                        return a + (b - a) * frac
                                    }
                                    if (output.remaining() < 8) {
                                        stream.write(output.array(), 0, output.position())
                                        output.clear()
                                    }
                                    output.putFloat(sample(0))
                                    output.putFloat(sample(1))
                                    totalOutput++
                                }
                                if (frames > 0) {
                                    previousLeft = samples[samples.size - 2]
                                    previousRight = samples.last()
                                }
                                totalSource = end
                                outputDone =
                                    info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || totalOutput >= outputLimit
                                codec.releaseOutputBuffer(index, false)
                            }
                    }
                }
                val targetFrames = min(outputLimit, (totalSource * 48000.0 / rate).roundToLong())
                while (totalOutput < targetFrames) {
                    if (output.remaining() < 8) { stream.write(output.array(), 0, output.position()); output.clear() }
                    output.putFloat(previousLeft); output.putFloat(previousRight); totalOutput++
                }
                if (output.position() > 0) stream.write(output.array(), 0, output.position())
            }
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {}
            codec?.release()
            extractor.release()
        }
    }

    fun waveform(path: String, bins: Int = 1000): FloatArray {
        val file = get(path)
        val count = file.length() / 8
        if (count == 0L) return FloatArray(bins)
        val peaks = FloatArray(bins)
        FileInputStream(file).channel.use { channel ->
            val bytes = ByteBuffer.allocate(65536).order(ByteOrder.LITTLE_ENDIAN)
            var position = 0L
            while (channel.read(bytes) > 0) {
                bytes.flip()
                while (bytes.remaining() >= 8) {
                    val peak = max(abs(bytes.float), abs(bytes.float))
                    val bin = (position * bins / count).toInt().coerceIn(0, bins - 1)
                    peaks[bin] = max(peaks[bin], peak)
                    position++
                }
                bytes.clear()
            }
        }
        return peaks
    }
}

class AudioMixer(
    @Volatile private var project: Project,
    private val cache: AudioCache,
    @Volatile private var matchListening: Boolean = false,
    private val finishing: Boolean = true,
) : Closeable {
    private val files = mutableMapOf<String, RandomAccessFile>()
    private val states = mutableMapOf<String, Long>()
    private val stretched = mutableMapOf<Pair<String, Double>, RandomAccessFile>()
    private val studio = mutableMapOf<Pair<String, Double>, RandomAccessFile>()
    private data class RampAudio(val clock: SpeedRamp,val dry: RandomAccessFile,val wet: RandomAccessFile?)
    private val ramps=mutableMapOf<String,RampAudio>()
    private var framePosition = 0L
    private val backgrounds=BackgroundAudio(cache)

    fun seek(time: Double) {
        framePosition = (time * 48000).toLong()
        states.values.forEach { Native.audioDestroy(it) }
        states.clear()
    }

    fun prepare(cancelled: () -> Boolean = { false }, progress: (Float) -> Unit = {}) {
        backgrounds.prepare(project,cancelled,progress)
        project.clips
            .filter { it.audio }
            .map { it.path }
            .distinct()
            .forEach { files[it] = RandomAccessFile(cache.get(it, cancelled), "r") }
        for (clip in project.clips.filter { it.audio }) {
            Studio.prepared(clip,cache,cancelled,progress) { wet ->
                val ramp=SpeedRamp.forClip(clip)
                if (ramp!=null) {
                    ramps[clip.id]=RampAudio(ramp,RandomAccessFile(cache.rampPcm(cache.get(clip.path,cancelled),ramp,cancelled,progress),"r"),
                        wet?.let { RandomAccessFile(cache.rampPcm(it,ramp,cancelled,progress),"r") })
                } else for (speed in clip.playbackPieces().map { it.effectiveRate }.distinct()) {
                    if (cancelled()) throw InterruptedIOException("Audio preparation cancelled")
                    if (speed != 1.0 && clip.path to speed !in stretched) {
                        val dry = cache.stretched(clip.path, speed, cancelled, progress)
                        stretched[clip.path to speed] = RandomAccessFile(dry, "r")
                    }
                    if (wet != null) studio[clip.id to speed] = RandomAccessFile(cache.stretchPcm(wet, speed, cancelled, progress), "r")
                }
            }
        }
    }

    fun update(project: Project, matchListening: Boolean) {
        this.project = project
        this.matchListening = matchListening
    }

    private fun readSource(
        file: RandomAccessFile,
        startSource: Double,
        frames: Int,
    ): FloatArray {
        val firstSource = floor(startSource).toLong()
        val sourceFrames = frames + 2
        val bytes = ByteArray(sourceFrames * 8)
        file.seek(firstSource * 8)
        var count = 0
        while (count < bytes.size) {
            val n = file.read(bytes, count, bytes.size - count)
            if (n <= 0) break
            count += n
        }
        val source = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val pcm = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val pos = startSource - firstSource + i
            val a = floor(pos).toInt()
            val frac = (pos - a).toFloat()
            for (ch in 0..1) {
                val v = source[a * 2 + ch]
                val next = source[(a + 1) * 2 + ch]
                pcm[i * 2 + ch] = v + (next - v) * frac
            }
        }
        return pcm
    }

    private fun fade(project: Project, clip: Clip, pcm: FloatArray, time: Double, first: Int, linear: Boolean=false) {
        for (transition in project.transitions) {
            val left = project.clips.firstOrNull { it.id == transition.leftId }
            val right = project.clips.firstOrNull { it.id == transition.rightId }
            fun sameSource(other: Clip?) =
                other != null &&
                    other.path == clip.path &&
                    abs(other.start - clip.start) < .001 &&
                    abs(other.sourceIn - clip.sourceIn) < .001
            val outgoing = left?.group == clip.group || sameSource(left)
            val incoming = right?.group == clip.group || sameSource(right)
            if (!outgoing && !incoming) continue
            val clock=transition.controls?.let { TransitionClock(transition) }
            val sound=clock?.let { if (outgoing) it.audioOut else it.audioIn }
            val origin=if (clock!=null) transition.pictureOrigin else 0.0
            val soundBegin=transition.begin
            val soundEnd=transition.end
            for (i in 0 until pcm.size / 2) {
                val at = time + (first + i) / 48000.0
                if (at < soundBegin || at > soundEnd) continue
                val fade =
                    if (sound!=null) {
                        val length=sound.endInclusive-sound.start
                        val position=if (length<=0) (if (at-origin>=sound.start) 1.0 else 0.0) else ((at-origin-sound.start)/length).coerceIn(0.0,1.0)
                        (if (outgoing) 1-position else position).toFloat()
                    }
                    else {
                        val position = transition.progress(at).toDouble()
                        if (linear) (if (outgoing) 1-position else position).toFloat()
                        else if (outgoing) cos(position * Math.PI / 2).toFloat()
                        else sin(position * Math.PI / 2).toFloat()
                    }
                pcm[i * 2] *= fade
                pcm[i * 2 + 1] *= fade
            }
        }
    }

    fun read(frames: Int): FloatArray {
        val project = this.project
        val mixed = FloatArray(frames * 2)
        val time = framePosition / 48000.0
        val end = (framePosition + frames) / 48000.0
        for (original in project.clips) for (clip in original.playbackPieces()) {
            if (!clip.audio || clip.start >= end || clip.end <= time) continue
            val ramp=ramps[clip.id]
            val prepared = ramp?.wet ?: studio[clip.id to clip.effectiveRate]
            val drySource = ramp?.dry ?: if (clip.effectiveRate == 1.0) files[clip.path]
                else stretched[clip.path to clip.effectiveRate] ?: error("Pitch-preserved audio has not been prepared")
            val file = prepared ?: drySource ?: continue
            val first = max(0, ceil((clip.start - time) * 48000).toInt())
            val last = min(frames, ceil((clip.end - time) * 48000).toInt())
            if (last <= first) continue
            val sourceTime=if (clip.frozen==null) clip.sourceTime(time+first/48000.0)
                else clip.frozen!!.sourceTime+clip.frozen!!.offset+time+first/48000.0-clip.start
            val startSource=if (ramp!=null) ramp.clock.outputTime(sourceTime)*48000 else sourceTime*48000/clip.effectiveRate
            val pcm = readSource(file, max(0.0, startSource), last - first)
            val gain =
                if (clip.gainDb <= -60f) 0f else 10f.pow(clip.gainDb / 20)
            val under=original.effects.filter { it.enabled && it.kind=="Undercranking" && ExistingEffects.modern(it) }
            val freeze=original.effects.firstOrNull { it.kind=="Freeze Frame" }
            fun mediaGain(at: Double): Float {
                val source=clip.sourceIn+(at-clip.start)*clip.rate
                val mute=under.any { fx -> fx.active(at-original.start) && (fx.parameter("mute")?.at(source) as? EffectValue.Toggle)?.value==true }
                val fade=if (freeze?.values?.get("audio")=="Fade out") {
                    val held=original.frozen!!;1-((held.offset+at-original.start)/freeze.number("hold",(held.offset+held.duration).toFloat())).toFloat().coerceIn(0f,1f)
                } else 1f
                return if (mute) 0f else gain*fade
            }
            for (i in 0 until pcm.size/2) {
                val scale=mediaGain(time+(first+i)/48000.0);pcm[i*2]*=scale;pcm[i*2+1]*=scale
            }
            fade(project, clip, pcm, time, first)
            val originalPower =
                if (!matchListening) 0.0
                else if (prepared == null) pcm.sumOf { it.toDouble() * it }
                else {
                    val raw = drySource ?: error("Missing source audio")
                    val dry =
                        readSource(
                            raw,
                            startSource,
                            last - first,
                        )
                    for (i in 0 until dry.size/2) {
                        val scale=mediaGain(time+(first+i)/48000.0);dry[i*2]*=scale;dry[i*2+1]*=scale
                    }
                    fade(project, clip, dry, time, first)
                    dry.sumOf { it.toDouble() * it }
                }
            for (fx in
                (if (prepared != null) emptyList() else clip.effects).filter {
                    it.active((time - clip.start).coerceAtLeast(0.0))
                }) {
                if (fx.kind !in Effects.audio) continue
                val strength = fx.strength(time - clip.start, clip.duration)
                if (strength <= 0f) continue
                val state = states.getOrPut(clip.id + ":" + fx.id) { Native.audioCreate(48000) }
                AudioFilter.process(state, pcm, fx, strength)
            }
            if (matchListening && originalPower > 0.00001) {
                val processedPower = pcm.sumOf { it.toDouble() * it }
                if (processedPower > 0.00001) {
                    val compensation =
                        sqrt(originalPower / processedPower).toFloat().coerceIn(0.25f, 4f)
                    for (i in pcm.indices) pcm[i] *= compensation
                }
            }
            if (finishing) audioFinish(project, clip, pcm, time + first / 48000.0 - clip.start)
            for (i in first until last) for (ch in 0..1) mixed[i * 2 + ch] +=
                pcm[(i - first) * 2 + ch]
        }
        for (original in project.clips.filter { it.video && it.start<end && it.end>time }) for (clip in original.playbackPieces()) {
            val pcm=backgrounds.read(clip,time,frames,original)
            fade(project,clip,pcm,time,0,true)
            for (i in mixed.indices) mixed[i]+=pcm[i]
        }
        for (i in mixed.indices) mixed[i] = mixed[i].coerceIn(-1f, 1f)
        framePosition += frames
        return mixed
    }

    override fun close() {
        backgrounds.close()
        studio.values.forEach { it.close() }
        studio.clear()
        states.values.forEach { Native.audioDestroy(it) }
        states.clear()
        ramps.values.forEach { it.dry.close();it.wet?.close() };ramps.clear()
        stretched.values.forEach { it.close() }
        stretched.clear()
        files.values.forEach { it.close() }
        files.clear()
    }
}
