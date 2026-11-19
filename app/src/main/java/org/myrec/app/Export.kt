package org.myrec.app

import android.graphics.Bitmap
import android.media.*
import android.opengl.*
import android.view.Surface
import java.io.*
import java.nio.*
import kotlin.math.*

class Export(private val cache: AudioCache) {
    @Volatile var cancelled = false
    @Volatile var paused = false
    internal var mediaStorage = MediaStorage()
    internal var thermalStatus: () -> Int = { MediaThermalGate.currentStatus() }
    private val thermal = MediaThermalGate("Export") { thermalStatus() }
    private val interrupted get() = cancelled || paused

    private fun checkCancelled() {
        if (interrupted) throw InterruptedIOException(if (paused && !cancelled) "Export paused" else "Export cancelled")
        thermal.cooperate()
    }

    fun run(project: Project, target: File, width: Int, height: Int, color: ExportColor = ExportColor.SDR, progress: (Float) -> Unit) {
        render(project, target, width, height, color, project.fps, null, progress)
    }

    internal fun render(project: Project, target: File, width: Int, height: Int, color: ExportColor,
                        fps: Int, checkpoint: MovieCheckpoint?, progress: (Float) -> Unit) {
        require(project.duration > 0) { "The project is empty" }
        val folder = target.parentFile!!
        val video = File(folder, target.name + ".video.mp4")
        val audio = File(folder, target.name + ".audio.m4a")
        fun requireSpace(bytes: Long = 0) = mediaStorage.requireSpace(folder, "Free up storage to finish exporting. Completed work is kept.", bytes)
        try {
            checkCancelled()
            requireSpace()
            val count = ceil(project.duration * fps - 1e-7).toInt().coerceAtLeast(1)
            val videos = Frames(cancelled = { interrupted }).use { frames ->
                if (checkpoint == null) {
                    encodeVideo(project, frames, video, width, height, color, fps, 0 until count, false) {
                        if (it % fps == 0) requireSpace()
                        progress(it * .8f / count)
                    }
                    listOf(video to 0L)
                } else (0 until count step fps * 3).map { start ->
                    checkCancelled()
                    val end = min(count, start + fps * 3)
                    val file = checkpoint.segment(start, end, ::checkCancelled) { output ->
                        requireSpace()
                        encodeVideo(project, frames, output, width, height, color, fps, start until end, true) {
                            progress(it * .8f / count)
                        }
                    }
                    progress(end * .8f / count)
                    file to start * 1_000_000L / fps
                }
            }
            checkCancelled()
            val encodeSound: (File) -> Unit = { file ->
                requireSpace()
                encodeAudio(project, file) { progress(.8f + it * .15f) }
            }
            val sound = if (checkpoint == null) audio.also(encodeSound) else checkpoint.audio(::checkCancelled, encodeSound)
            val priming = Mp4Audio.delay(cache.head(sound.path, 12288))
            checkCancelled()
            requireSpace(sound.length() + videos.sumOf { it.first.length() })
            mux(videos, sound, target)
            Mp4Audio.trim(target, Mp4Audio.PREFIX + priming, project.duration)
            progress(1f)
        } catch (e: Exception) {
            target.delete()
            throw e
        } finally {
            video.delete()
            audio.delete()
        }
    }

    private fun encodeVideo(
        project: Project,
        frames: Frames,
        target: File,
        width: Int,
        height: Int,
        color: ExportColor,
        fps: Int,
        range: IntRange,
        segmented: Boolean,
        progress: (Int) -> Unit,
    ) {
        val hdr = color == ExportColor.HLG
        val format = MediaFormat.createVideoFormat(color.mime, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_COLOR_STANDARD, if (hdr) MediaFormat.COLOR_STANDARD_BT2020 else MediaFormat.COLOR_STANDARD_BT709)
        format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, if (hdr) MediaFormat.COLOR_TRANSFER_HLG else MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        if (hdr) format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
        format.setInteger(MediaFormat.KEY_BIT_RATE, max(2_000_000, width * height * 6))
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        if (segmented && android.os.Build.VERSION.SDK_INT >= 29) format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        val input = videoInput(format, hdr, width, height, fps)
        val codec = input.first; val surface = input.second; val egl = input.third
        val muxer = try { MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) }
        catch (error: Exception) {
            try { codec.release() } finally { try { egl.close() } finally { surface.release() } }
            throw error
        }
        var started = false
        var track = -1
        val info = MediaCodec.BufferInfo()
        fun drain(eos: Boolean) {
            var idle = 0
            while (true) {
                checkCancelled()
                val index = codec.dequeueOutputBuffer(info, if (eos) 10000 else 0)
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!eos) return
                    check(++idle < 3000) { "The video encoder timed out" }
                    continue
                }
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    check(!started)
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    started = true
                } else if (index >= 0) {
                    val output = codec.getOutputBuffer(index)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0) {
                        check(started)
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        muxer.writeSampleData(track, output, info)
                    }
                    val done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                    if (done) return
                }
            }
        }
        try {
            for (i in range) {
                checkCancelled()
                drain(false)
                val bitmap = frames.render(project, i.toDouble() / fps, width, height, keepLinear = hdr)
                try { egl.draw(bitmap, (i - range.first) * 1_000_000_000L / fps) }
                finally { bitmap.recycle() }
                progress(i + 1)
            }
            codec.signalEndOfInputStream()
            drain(true)
        } finally {
            try {
                codec.stop()
            } catch (_: Exception) {}
            codec.release()
            egl.close()
            surface.release()
            try {
                if (started) muxer.stop()
            } finally {
                muxer.release()
            }
        }
    }

    private fun videoInput(format: MediaFormat, hdr: Boolean, width: Int, height: Int, fps: Int): Triple<MediaCodec, Surface, EncoderSurface> {
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { codec ->
            if (!codec.isEncoder || mime !in codec.supportedTypes) false else try {
                val caps = codec.getCapabilitiesForType(mime)
                caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) &&
                    (!hdr || caps.profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 }) &&
                    caps.videoCapabilities?.areSizeAndRateSupported(width, height, fps.toDouble()) == true
            } catch (_: Exception) { false }
        }
        var failure: Exception? = null
        for (candidate in candidates) {
            var codec: MediaCodec? = null; var surface: Surface? = null; var egl: EncoderSurface? = null
            try {
                codec = MediaCodec.createByCodecName(candidate.name)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                surface = codec.createInputSurface(); egl = EncoderSurface(surface, hdr)
                codec.start()
                return Triple(codec, surface, egl)
            } catch (error: Exception) {
                failure = error
                try { egl?.close() } finally { surface?.release(); codec?.release() }
            }
        }
        error(if (hdr) "This device cannot start a HEVC Main10 HLG encoder for this size and frame rate: ${failure?.message.orEmpty()}"
            else "This device cannot encode this size and frame rate: ${failure?.message.orEmpty()}")
    }

    private fun encodeAudio(project: Project, target: File, progress: (Float) -> Unit) {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = try { MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) }
        catch (error: Exception) { codec.release(); throw error }
        var started = false
        var track = -1
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 2)
            format.setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC,
            )
            format.setInteger(MediaFormat.KEY_BIT_RATE, 192000)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            AudioMixer(project, cache).use { mixer ->
                mixer.prepare({ interrupted })
                mixer.seek(0.0)
                val content = (project.duration * 48000).roundToLong()
                val count = content + Mp4Audio.PREFIX + Mp4Audio.TAIL
                var position = 0L
                var inputDone = false
                var outputDone = false
                val info = MediaCodec.BufferInfo()
                while (!outputDone) {
                    checkCancelled()
                    if (!inputDone) {
                        val index = codec.dequeueInputBuffer(10000)
                        if (index >= 0) {
                            val buffer =
                                codec.getInputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                            buffer.clear()
                            val n =
                                min(
                                    min(1024, buffer.capacity() / 4),
                                    max(0L, count - position).toInt(),
                                )
                            if (n == 0) {
                                codec.queueInputBuffer(
                                    index,
                                    0,
                                    0,
                                    position * 1_000_000 / 48000,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                val pcm = FloatArray(n * 2)
                                for (i in 0 until n) if (position + i < Mp4Audio.PREFIX)
                                    for (ch in 0..1) pcm[i * 2 + ch] = Mp4Audio.prefix[(position + i).toInt()]
                                val from = max(0L, Mp4Audio.PREFIX - position).toInt().coerceAtMost(n)
                                val until = min(n.toLong(), Mp4Audio.PREFIX + content - position).toInt().coerceAtLeast(from)
                                if (until > from) mixer.read(until - from).copyInto(pcm, from * 2)
                                for (v in pcm) buffer.putShort(
                                    (v.coerceIn(-1f, 1f) * 32767).toInt().toShort()
                                )
                                codec.queueInputBuffer(
                                    index,
                                    0,
                                    n * 4,
                                    position * 1_000_000 / 48000,
                                    0,
                                )
                                position += n
                                progress(position.toFloat() / max(1, count))
                            }
                        }
                    }
                    when (val index = codec.dequeueOutputBuffer(info, 10000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            started = true
                        }
                        else ->
                            if (index >= 0) {
                                val buffer = codec.getOutputBuffer(index)!!
                                if (
                                    info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                                        info.size > 0
                                ) {
                                    check(started)
                                    buffer.position(info.offset)
                                    buffer.limit(info.offset + info.size)
                                    muxer.writeSampleData(track, buffer, info)
                                }
                                outputDone =
                                    info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                codec.releaseOutputBuffer(index, false)
                            }
                    }
                }
            }
        } finally {
            try {
                codec.stop()
            } catch (_: Exception) {}
            codec.release()
            try {
                if (started) muxer.stop()
            } finally {
                muxer.release()
            }
        }
    }

    private fun mux(videos: List<Pair<File, Long>>, audio: File, target: File) {
        val output = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        fun <T> read(file: File, block: (MediaExtractor) -> T): T {
            val ex = MediaExtractor()
            try { ex.setDataSource(file.path); ex.selectTrack(0); return block(ex) }
            finally { ex.release() }
        }
        try {
            val pictureFormat = read(videos.first().first) { it.getTrackFormat(0) }
            val picture = output.addTrack(pictureFormat)
            val sound = read(audio) { output.addTrack(it.getTrackFormat(0)) }
            output.start()
            started = true
            val buffer = ByteBuffer.allocateDirect(16 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            fun append(ex: MediaExtractor, track: Int, offset: Long) {
                while (true) {
                    checkCancelled()
                    buffer.clear()
                    val size = ex.readSampleData(buffer, 0)
                    if (size < 0) break
                    val flag = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    info.set(0, size, offset + ex.sampleTime, flag)
                    output.writeSampleData(track, buffer, info)
                    ex.advance()
                }
            }
            for ((file, offset) in videos) read(file) { ex ->
                if (offset > 0) {
                    val format = ex.getTrackFormat(0)
                    check((0..2).all { format.getByteBuffer("csd-$it") == pictureFormat.getByteBuffer("csd-$it") }) {
                        "The encoder changed configuration between export sections"
                    }
                }
                append(ex, picture, offset)
            }
            read(audio) { append(it, sound, 0L) }
        } finally {
            try {
                if (started) output.stop()
            } finally {
                output.release()
            }
        }
    }
}

private class EncoderSurface(surface: Surface, private val hdr: Boolean) : Closeable {
    private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texture = 0
    private var pixels: ByteBuffer? = null
    private val vertices =
        ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f))
            position(0)
        }

    init {
        try { initialize(surface) } catch (error: Throwable) { close(); throw error }
    }

    private fun initialize(surface: Surface) {
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        if (hdr) check(EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS).orEmpty().split(' ')
            .contains("EGL_EXT_gl_colorspace_bt2020_hlg")) { "This device cannot create an HLG editing surface" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(
            EGL14.eglChooseConfig(
                display,
                intArrayOf(
                    EGL14.EGL_RED_SIZE,
                    if (hdr) 10 else 8,
                    EGL14.EGL_GREEN_SIZE,
                    if (hdr) 10 else 8,
                    EGL14.EGL_BLUE_SIZE,
                    if (hdr) 10 else 8,
                    EGL14.EGL_ALPHA_SIZE,
                    if (hdr) 2 else 0,
                    EGL14.EGL_RENDERABLE_TYPE,
                    if (hdr) 0x0040 else EGL14.EGL_OPENGL_ES2_BIT,
                    0x3142,
                    1,
                    EGL14.EGL_NONE,
                ),
                0,
                configs,
                0,
                1,
                count,
                0,
            )
        )
        check(count[0] > 0) { "This device has no compatible recording surface" }
        context =
            EGL14.eglCreateContext(
                display,
                configs[0],
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, if (hdr) 3 else 2, EGL14.EGL_NONE),
                0,
            )
        eglSurface =
            EGL14.eglCreateWindowSurface(
                display,
                configs[0],
                surface,
                if (hdr) intArrayOf(0x309D, 0x3540, EGL14.EGL_NONE) else intArrayOf(EGL14.EGL_NONE),
                0,
            )
        if (hdr) check(Native.hlgSurface(surface)) { "Cannot tag the HLG input surface" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context))
        fun shader(type: Int, source: String): Int {
            val id = GLES20.glCreateShader(type)
            GLES20.glShaderSource(id, source)
            GLES20.glCompileShader(id)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { GLES20.glGetShaderInfoLog(id) }
            return id
        }
        val vs =
            shader(
                GLES20.GL_VERTEX_SHADER,
                if (hdr) "#version 300 es\nin vec2 position; in vec2 uv; out vec2 v; void main(){v=uv;gl_Position=vec4(position,0.,1.);}"
                else "attribute vec2 position; attribute vec2 uv; varying vec2 v; void main(){v=uv;gl_Position=vec4(position,0.,1.);}",
            )
        val fs =
            shader(
                GLES20.GL_FRAGMENT_SHADER,
                if (hdr) """#version 300 es
                    precision highp float;
                    in vec2 v; uniform sampler2D image; out vec4 result;
                    float hlg(float x) { return x <= 1.0/12.0 ? sqrt(3.0*x) : 0.17883277*log(12.0*x-0.28466892)+0.55991073; }
                    void main() {
                        vec3 c=texture(image,v).rgb;
                        vec3 bt=max(vec3(0.0),vec3(dot(c,vec3(.627403896,.329283038,.043313066)),
                            dot(c,vec3(.069097289,.919540395,.011362316)),dot(c,vec3(.016391439,.088013308,.895595253))));
                        vec3 display=bt*(203.0/1000.0);
                        float y=dot(display,vec3(.2627,.678,.0593));
                        vec3 scene=y>0.0?display*pow(y,(1.0-1.2)/1.2):vec3(0.0);
                        result=vec4(hlg(clamp(scene.r,0.0,1.0)),hlg(clamp(scene.g,0.0,1.0)),hlg(clamp(scene.b,0.0,1.0)),1.0);
                    }
                """.trimIndent() else "precision mediump float; varying vec2 v; uniform sampler2D image; void main(){gl_FragColor=texture2D(image,v);}",
            )
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val linked = IntArray(1); GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] != 0) { GLES20.glGetProgramInfoLog(program) }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        texture = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )
    }

    fun draw(bitmap: Bitmap, timestamp: Long) {
        GLES20.glViewport(0, 0, bitmap.width, bitmap.height)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        if (hdr) {
            check(bitmap.config == Bitmap.Config.RGBA_F16) { "HDR export requires a linear half-float frame" }
            val data = pixels?.takeIf { it.capacity() == bitmap.byteCount }
                ?: ByteBuffer.allocateDirect(bitmap.byteCount).order(ByteOrder.nativeOrder()).also { pixels = it }
            data.clear(); bitmap.copyPixelsToBuffer(data); data.flip()
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, bitmap.rowBytes / 8)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, bitmap.width, bitmap.height, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, data)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0)
        } else GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "Cannot upload export frame" }
        val position = GLES20.glGetAttribLocation(program, "position")
        val uv = GLES20.glGetAttribLocation(program, "uv")
        vertices.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(position)
        vertices.position(2)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "image"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, timestamp)
        check(EGL14.eglSwapBuffers(display, eglSurface)) { "Cannot submit video frame" }
    }

    override fun close() {
        if (context != EGL14.EGL_NO_CONTEXT) {
            GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            GLES20.glDeleteProgram(program)
        }
        EGL14.eglMakeCurrent(
            display,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_CONTEXT,
        )
        EGL14.eglDestroySurface(display, eglSurface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
    }
}
