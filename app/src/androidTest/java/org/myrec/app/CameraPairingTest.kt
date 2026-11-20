package org.myrec.app

import android.graphics.Color
import android.graphics.PixelFormat
import android.media.ImageReader
import android.opengl.EGL14.*
import android.opengl.EGLExt.eglPresentationTimeANDROID
import android.opengl.GLES20.*
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

internal object CameraPairingTest {
    fun encoderCadence() {
        val thread = HandlerThread("cadence-test-output").apply { start() }
        val handler = Handler(thread.looper)
        val failure = AtomicReference<Throwable>()
        fun reader() = ImageReader.newInstance(96, 64, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, handler)
        }
        try {
            reader().use { preview -> reader().use { recording ->
                CameraComposition(preview.surface, 96, 64) { failure.set(it) }.use { renderer ->
                    val input = renderer.input(96, 64, 0, false, maximumFps = 60)
                    val encoded = CopyOnWriteArrayList<Long>()
                    renderer.record(recording.surface, 96, 64, clock = { encoded.add(it) })
                    val start = System.nanoTime()
                    TimedFrames(listOf(input)).use { producer ->
                        repeat(8) { index ->
                            producer.send(0, start + index * 1_000_000_000L / 60, Color.RED)
                            Thread.sleep(45)
                        }
                    }
                    failure.get()?.let { throw it }
                    check(encoded.toList() == (0..6 step 2).map { start + it * 1_000_000_000L / 60 }) { "$encoded" }
                    renderer.record(null)
                }
            } }
        } finally { thread.quitSafely() }
    }

    fun run() {
        val width=96
        val height=64
        val thread=HandlerThread("pairing-test-output").apply { start() }
        val handler=Handler(thread.looper)
        fun reader()=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,3).apply {
            setOnImageAvailableListener({ it.acquireLatestImage()?.close() },handler)
        }
        val failure=AtomicReference<Throwable>()
        try {
            reader().use { preview -> reader().use { recording ->
                CameraComposition(preview.surface,width,height) { failure.set(it) }.use { renderer ->
                    val surfaces=List(2) { renderer.input(width,height,0,false) }
                    TimedFrames(surfaces).use { producer ->
                        val encoded=CopyOnWriteArrayList<Long>()
                        fun start()=renderer.record(recording.surface,width,height,clock={ encoded.add(it) })
                        start()
                        val base=System.nanoTime()
                        fun send(input: Int, ms: Long, color: Int) {
                            producer.send(input,base+ms*1_000_000L,color)
                            Thread.sleep(70)
                            failure.get()?.let { throw it }
                        }
                        fun awaitRecording(time: Long) {
                            val deadline=System.nanoTime()+2_000_000_000L
                            while ((encoded.lastOrNull() ?: Long.MIN_VALUE)<time && System.nanoTime()<deadline) {
                                failure.get()?.let { throw it }
                                Thread.sleep(20)
                            }
                        }
                        fun expect(ms: Long, left: Int, right: Int) {
                            awaitRecording(base+ms*1_000_000L)
                            val image=checkNotNull(renderer.previewFrame(width,height)) { "Missing paired preview" }
                            try {
                                check(image.getPixel(width/4,height/2)==left && image.getPixel(width*3/4,height/2)==right) {
                                    "Unexpected paired pixels at ${ms}ms: ${image.getPixel(width/4,height/2)}, ${image.getPixel(width*3/4,height/2)}"
                                }
                            } finally { image.recycle() }
                            check(encoded.lastOrNull()==base+ms*1_000_000L) { "Unexpected recording timestamps: ${encoded.map { (it-base)/1_000_000L }}" }
                        }

                        send(0,0,Color.RED)
                        check(renderer.previewFrame(width,height)==null && encoded.isEmpty())
                        send(1,20,Color.BLUE)
                        expect(20,Color.RED,Color.BLUE)

                        send(0,40,Color.GREEN)
                        send(1,65,Color.YELLOW)
                        expect(20,Color.RED,Color.BLUE)
                        renderer.configure(1,true,0,width,height)
                        expect(20,Color.BLUE,Color.RED)
                        renderer.configure(1,false,0,width,height)
                        send(0,68,Color.MAGENTA)
                        expect(68,Color.MAGENTA,Color.YELLOW)

                        send(0,100,Color.CYAN)
                        send(0,135,Color.GREEN)
                        send(0,170,Color.WHITE)
                        send(1,104,Color.BLUE)
                        expect(68,Color.MAGENTA,Color.YELLOW)
                        send(1,137,Color.RED)
                        expect(137,Color.GREEN,Color.RED)
                        check(encoded.toList()==listOf(20L,68L,137L).map { base+it*1_000_000L })

                        renderer.record(null)
                        encoded.clear()
                        start()
                        Thread.sleep(70)
                        check(encoded.isEmpty()) { "Recording reused a pre-resume pair" }
                        val resumed=System.nanoTime()
                        producer.send(0,resumed,Color.WHITE)
                        Thread.sleep(70)
                        check(encoded.isEmpty()) { "Recording resumed before both cameras supplied new frames" }
                        producer.send(1,resumed+5_000_000L,Color.BLUE)
                        awaitRecording(resumed+5_000_000L)
                        check(encoded.toList()==listOf(resumed+5_000_000L)) { "Recording did not resume with a fresh pair: $encoded" }
                        renderer.record(null)
                        failure.get()?.let { throw it }
                    }
                    renderer.clearInputs()
                    check(renderer.previewFrame(width,height)==null)
                }
            } }
        } finally { thread.quitSafely() }
    }

    private class TimedFrames(surfaces: List<Surface>): AutoCloseable {
        private val display=eglGetDisplay(EGL_DEFAULT_DISPLAY)
        private val context: android.opengl.EGLContext
        private val windows: List<android.opengl.EGLSurface>
        init {
            check(eglInitialize(display,null,0,null,0))
            val configs=arrayOfNulls<android.opengl.EGLConfig>(1)
            check(eglChooseConfig(display,intArrayOf(EGL_RENDERABLE_TYPE,EGL_OPENGL_ES2_BIT,
                EGL_SURFACE_TYPE,EGL_WINDOW_BIT,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_NONE),
                0,configs,0,1,IntArray(1),0))
            val config=checkNotNull(configs[0])
            context=eglCreateContext(display,config,EGL_NO_CONTEXT,intArrayOf(EGL_CONTEXT_CLIENT_VERSION,2,EGL_NONE),0)
            windows=surfaces.map { eglCreateWindowSurface(display,config,it,intArrayOf(EGL_NONE),0) }
        }
        fun send(index: Int, time: Long, color: Int) {
            val window=windows[index]
            check(eglMakeCurrent(display,window,window,context))
            glClearColor(Color.red(color)/255f,Color.green(color)/255f,Color.blue(color)/255f,1f)
            glClear(GL_COLOR_BUFFER_BIT)
            check(eglPresentationTimeANDROID(display,window,time) && eglSwapBuffers(display,window))
        }
        override fun close() {
            eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT)
            windows.forEach { eglDestroySurface(display,it) }
            eglDestroyContext(display,context)
            eglTerminate(display)
        }
    }
}
