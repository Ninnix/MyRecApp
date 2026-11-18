package org.myrec.app

import android.graphics.SurfaceTexture
import android.opengl.*
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

class CameraRecordingSurface(
    private val output: Surface,
    private val width: Int,
    private val height: Int,
    private val onFrame: (Long) -> Unit = {},
    private val onError: (Throwable) -> Unit,
) : Closeable {
    @Volatile var analysisFrame: ((Long,()->android.graphics.Bitmap)->Unit)?=null
    var analysisRotation=0
    private val readback=CameraReadback()
    private val thread = HandlerThread("camera-encoder").apply { start() }
    private val handler = Handler(thread.looper)
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var window = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var source: Input? = null
    private var lastTimestamp = 0L
    @Volatile private var closed = false
    private var failed = false
    private val vertices =
        ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
            position(0)
        }

    private class Input(
        val texture: Int,
        val image: SurfaceTexture,
        val surface: Surface,
        val width: Int,
        val height: Int,
        var rotation: Int,
        val realtime: Boolean,
        val monotonic: Boolean,
        val unmirror: Boolean,
        val sensorOrientation: Int,
        val mirror: Boolean,
        val outputRotation: Int,
        var fitFrame: Boolean,
    ) {
        var offset: Long? = null
        var timestamp = 0L
    }

    init {
        try {
            call { initialize() }
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    private fun <T> call(block: () -> T): T {
        val ready = CountDownLatch(1)
        var result: T? = null
        var error: Throwable? = null
        check(
            handler.post {
                try {
                    result = block()
                } catch (e: Throwable) {
                    error = e
                } finally {
                    ready.countDown()
                }
            }
        ) {
            "Recording renderer is closed"
        }
        check(ready.await(5, TimeUnit.SECONDS)) { "Recording renderer timed out" }
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun initialize() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        val configs = arrayOfNulls<EGLConfig>(1)
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
                    EGL14.EGL_SURFACE_TYPE,
                    EGL14.EGL_WINDOW_BIT,
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
                IntArray(1),
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
        window =
            EGL14.eglCreateWindowSurface(display, configs[0], output, intArrayOf(EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, window, window, context))
        fun shader(type: Int, code: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, code)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { GLES20.glGetShaderInfoLog(shader) }
            return shader
        }
        val vertex =
            shader(
                GLES20.GL_VERTEX_SHADER,
                """
            attribute vec2 position; attribute vec2 uv;
            uniform mat4 transform; uniform vec2 scale; varying vec2 v;
            void main(){v=(transform*vec4(uv,0.,1.)).xy;gl_Position=vec4(position*scale,0.,1.);}
        """
                    .trimIndent(),
            )
        val fragment =
            shader(
                GLES20.GL_FRAGMENT_SHADER,
                """
            #extension GL_OES_EGL_image_external : require
            precision mediump float; varying vec2 v; uniform samplerExternalOES image;
            void main(){gl_FragColor=texture2D(image,v);}
        """
                    .trimIndent(),
            )
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] != 0) { GLES20.glGetProgramInfoLog(program) }
    }

    fun input(
        width: Int,
        height: Int,
        rotation: Int,
        realtime: Boolean,
        monotonic: Boolean = false,
        unmirror: Boolean = false,
        sensorOrientation: Int = 0,
        mirror: Boolean = false,
        outputRotation: Int = 0,
        fitFrame: Boolean = false,
    ): Surface = call {
        check(!closed && !failed)
        releaseInput()
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
        for (parameter in listOf(GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_TEXTURE_MAG_FILTER)) GLES20
            .glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, parameter, GLES20.GL_LINEAR)
        for (parameter in listOf(GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_TEXTURE_WRAP_T)) GLES20
            .glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, parameter, GLES20.GL_CLAMP_TO_EDGE)
        val image = SurfaceTexture(textures[0]).apply { setDefaultBufferSize(width, height) }
        val input =
            Input(
                textures[0],
                image,
                Surface(image),
                width,
                height,
                rotation,
                realtime,
                monotonic,
                unmirror,
                sensorOrientation,
                mirror,
                outputRotation,
                fitFrame,
            )
        source = input
        image.setOnFrameAvailableListener(
            {
                if (!closed && !failed && source === input)
                    try {
                        draw(input)
                    } catch (e: Throwable) {
                        failed = true
                        onError(e)
                    }
            },
            handler,
        )
        input.surface
    }

    fun rotate(rotation: Int, fitFrame: Boolean) = call {
        source?.let {
            it.rotation = rotation
            it.fitFrame = fitFrame
        }
    }

    private fun draw(input: Input) {
        input.image.updateTexImage()
        val stamp = input.image.timestamp
        if (stamp <= input.timestamp) return
        input.timestamp = stamp
        val sourceTransform = FloatArray(16)
        input.image.getTransformMatrix(sourceTransform)
        if (
            input.unmirror &&
                sourceTransform[0] * sourceTransform[5] - sourceTransform[1] * sourceTransform[4] >
                    0
        ) {
            Matrix.translateM(sourceTransform, 0, 1f, 0f, 0f)
            Matrix.scaleM(sourceTransform, 0, -1f, 1f, 1f)
        }
        val rotation = FloatArray(16)
        Matrix.setIdentityM(rotation, 0)
        Matrix.translateM(rotation, 0, .5f, .5f, 0f)
        // Camera textures already carry sensor rotation, unlike canvas inputs.
        Matrix.rotateM(
            rotation,
            0,
            (input.rotation - input.sensorOrientation).toFloat(),
            0f,
            0f,
            1f,
        )
        Matrix.translateM(rotation, 0, -.5f, -.5f, 0f)
        val transform = FloatArray(16)
        Matrix.multiplyMM(transform, 0, sourceTransform, 0, rotation, 0)
        if (input.mirror) {
            // Mirror the displayed frame, including its encoder rotation hint.
            val quarter = input.outputRotation % 180 != 0
            Matrix.translateM(transform, 0, .5f, .5f, 0f)
            Matrix.scaleM(transform, 0, if (quarter) 1f else -1f, if (quarter) -1f else 1f, 1f)
            Matrix.translateM(transform, 0, -.5f, -.5f, 0f)
        }
        val quarter = input.rotation % 180 != 0
        val w = if (quarter) input.height else input.width
        val h = if (quarter) input.width else input.height
        val fit =
            if (input.fitFrame) min(width.toFloat() / w, height.toFloat() / h)
            else max(width.toFloat() / w, height.toFloat() / h)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, input.texture)
        val position = GLES20.glGetAttribLocation(program, "position")
        val uv = GLES20.glGetAttribLocation(program, "uv")
        vertices.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(position)
        vertices.position(2)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glUniformMatrix4fv(
            GLES20.glGetUniformLocation(program, "transform"),
            1,
            false,
            transform,
            0,
        )
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(program, "scale"),
            w * fit / width,
            h * fit / height,
        )
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "image"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        // Encoder surfaces use MONOTONIC; sensor REALTIME includes time spent asleep.
        if (input.offset == null)
            input.offset =
                if (input.monotonic) 0L
                else if (input.realtime) System.nanoTime() - SystemClock.elapsedRealtimeNanos()
                else System.nanoTime() - stamp
        val timestamp = (stamp + input.offset!!).coerceAtLeast(lastTimestamp + 1_000)
        lastTimestamp = timestamp
        analysisFrame?.invoke(timestamp) {
            readback.read(width,height,analysisRotation) { w,h ->
                GLES20.glViewport(0,0,w,h);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
            }
        }
        onFrame(timestamp)
        EGLExt.eglPresentationTimeANDROID(display, window, timestamp)
        check(EGL14.eglSwapBuffers(display, window)) { "Cannot submit camera frame" }
    }

    private fun releaseInput() {
        source?.let {
            it.image.setOnFrameAvailableListener(null)
            it.surface.release()
            it.image.release()
            GLES20.glDeleteTextures(1, intArrayOf(it.texture), 0)
        }
        source = null
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            call {
                readback.close()
                releaseInput()
                if (program != 0) GLES20.glDeleteProgram(program)
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT,
                )
                if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                if (display != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(display)
                output.release()
            }
        } finally {
            thread.quitSafely()
        }
    }
}
