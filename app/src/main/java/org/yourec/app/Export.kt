package org.yourec.app

import android.graphics.Bitmap
import android.media.*
import android.opengl.*
import android.view.Surface
import java.io.*
import java.nio.*
import kotlin.math.*

class Export(private val cache: AudioCache) {
    @Volatile var cancelled = false

    private fun checkCancelled() {
        if (cancelled) throw InterruptedIOException("Export cancelled")
    }

    fun run(project: Project, target: File, width: Int, height: Int, progress: (Float) -> Unit) {
        require(project.duration > 0) { "The project is empty" }
        val video = File(target.parentFile, target.name + ".video.mp4")
        val audio = File(target.parentFile, target.name + ".audio.m4a")
        try {
            encodeVideo(project, video, width, height) { progress(it * 0.8f) }
            checkCancelled()
            encodeAudio(project, audio) { progress(0.8f + it * 0.15f) }
            checkCancelled()
            mux(video, audio, target)
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
        target: File,
        width: Int,
        height: Int,
        progress: (Float) -> Unit,
    ) {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var surface: Surface? = null
        var egl: EncoderSurface? = null
        val muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
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
            val format =
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            format.setInteger(MediaFormat.KEY_BIT_RATE, max(2_000_000, width * height * 6))
            format.setInteger(MediaFormat.KEY_FRAME_RATE, project.fps)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            egl = EncoderSurface(surface)
            codec.start()
            Frames().use { frames ->
                val count = ceil(project.duration * project.fps).toInt()
                for (i in 0 until count) {
                    checkCancelled()
                    drain(false)
                    val bitmap = frames.render(project, i.toDouble() / project.fps, width, height)
                    egl.draw(bitmap, i * 1_000_000_000L / project.fps)
                    bitmap.recycle()
                    progress((i + 1f) / count)
                }
            }
            codec.signalEndOfInputStream()
            drain(true)
        } finally {
            try {
                codec.stop()
            } catch (_: Exception) {}
            codec.release()
            egl?.close()
            surface?.release()
            try {
                if (started) muxer.stop()
            } finally {
                muxer.release()
            }
        }
    }

    private fun encodeAudio(project: Project, target: File, progress: (Float) -> Unit) {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
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
                mixer.prepare({ cancelled })
                mixer.seek(0.0)
                val count = (project.duration * 48000).toLong()
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
                                val pcm = mixer.read(n)
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

    private fun mux(video: File, audio: File, target: File) {
        val extractors = listOf(MediaExtractor(), MediaExtractor())
        val output = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            extractors[0].setDataSource(video.absolutePath)
            extractors[1].setDataSource(audio.absolutePath)
            val tracks = extractors.map { ex ->
                ex.selectTrack(0)
                output.addTrack(ex.getTrackFormat(0))
            }
            output.start()
            started = true
            val buffer = ByteBuffer.allocateDirect(16 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            for ((i, ex) in extractors.withIndex()) while (true) {
                checkCancelled()
                buffer.clear()
                val size = ex.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(
                    0,
                    size,
                    ex.sampleTime,
                    if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                        MediaCodec.BUFFER_FLAG_KEY_FRAME
                    else 0,
                )
                output.writeSampleData(tracks[i], buffer, info)
                ex.advance()
            }
        } finally {
            extractors.forEach { it.release() }
            try {
                if (started) output.stop()
            } finally {
                output.release()
            }
        }
    }
}

private class EncoderSurface(surface: Surface) : Closeable {
    private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context: EGLContext
    private val eglSurface: EGLSurface
    private val program: Int
    private val texture: Int
    private val vertices =
        ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f))
            position(0)
        }

    init {
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(
            EGL14.eglChooseConfig(
                display,
                intArrayOf(
                    EGL14.EGL_RED_SIZE,
                    8,
                    EGL14.EGL_GREEN_SIZE,
                    8,
                    EGL14.EGL_BLUE_SIZE,
                    8,
                    EGL14.EGL_RENDERABLE_TYPE,
                    EGL14.EGL_OPENGL_ES2_BIT,
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
        context =
            EGL14.eglCreateContext(
                display,
                configs[0],
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0,
            )
        eglSurface =
            EGL14.eglCreateWindowSurface(
                display,
                configs[0],
                surface,
                intArrayOf(EGL14.EGL_NONE),
                0,
            )
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
                "attribute vec2 position; attribute vec2 uv; varying vec2 v; void main(){v=uv;gl_Position=vec4(position,0.,1.);}",
            )
        val fs =
            shader(
                GLES20.GL_FRAGMENT_SHADER,
                "precision mediump float; varying vec2 v; uniform sampler2D image; void main(){gl_FragColor=texture2D(image,v);}",
            )
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
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
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
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
        GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        GLES20.glDeleteProgram(program)
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
