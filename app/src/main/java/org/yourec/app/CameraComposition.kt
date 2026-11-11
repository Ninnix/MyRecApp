package org.yourec.app

import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.opengl.*
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.*

class CameraComposition(
    private val preview: Surface,
    private var width: Int,
    private var height: Int,
    private val hdr: Boolean = false,
    private val failure: (Throwable) -> Unit,
) : Closeable {
    private val thread = HandlerThread("camera-composition").apply { start() }
    private val handler = Handler(thread.looper)
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var window = EGL14.EGL_NO_SURFACE
    private var encoder = EGL14.EGL_NO_SURFACE
    private var encoderWidth = 0
    private var encoderHeight = 0
    private var orientation = 0
    private var fps = 30
    private var nextFrame = 0L
    private val encoderTick =
        object : Runnable {
            override fun run() {
                if (closed || encoder == EGL14.EGL_NO_SURFACE) return
                try {
                    if (inputs.isNotEmpty() && inputs.all { it.ready })
                        draw(encoder, encoderWidth, encoderHeight, orientation)
                } catch (e: Throwable) {
                    failure(e)
                    return
                }
                nextFrame += 1_000_000_000L / fps
                val now = System.nanoTime()
                if (nextFrame < now) nextFrame = now
                handler.postDelayed(this, ((nextFrame - now) / 1_000_000).coerceAtLeast(1))
            }
        }
    private var program = 0
    private var closed = false
    private val inputs = mutableListOf<Input>()
    private val vertices =
        ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var layout = 1
    private var swapped = false
    private var displayRotation = 0
    private var zoom = floatArrayOf(1f, 1f)
    private var panX = floatArrayOf(0f, 0f)
    private var panY = floatArrayOf(0f, 0f)
    private var pip = RectF(.63f, .06f, .95f, .38f)
    private var roundPip = RectF(.04f, .34f, .38f, .94f)

    private class Input(
        val texture: Int,
        val image: SurfaceTexture,
        val surface: Surface,
        val width: Int,
        val height: Int,
        val sensor: Int,
        val front: Boolean,
        var primaries: Int,
    ) {
        val transform = FloatArray(16)
        var ready = false
        var dataSpace = 0
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
        val done = CountDownLatch(1)
        var value: T? = null
        var error: Throwable? = null
        check(
            handler.post {
                try {
                    value = block()
                } catch (e: Throwable) {
                    error = e
                } finally {
                    done.countDown()
                }
            }
        )
        check(done.await(5, TimeUnit.SECONDS)) { "Camera compositor timed out" }
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun initialize() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        if (hdr)
            check(
                EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS)
                    ?.contains("EGL_EXT_gl_colorspace_bt2020_hlg") == true
            ) {
                "This graphics driver cannot record HLG video"
            }
        val configs = arrayOfNulls<EGLConfig>(1)
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
                    EGL14.EGL_SURFACE_TYPE,
                    EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RENDERABLE_TYPE,
                    if (hdr) 0x40 else EGL14.EGL_OPENGL_ES2_BIT,
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
        config = checkNotNull(configs[0]) { "No compatible camera graphics format" }
        context =
            EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, if (hdr) 3 else 2, EGL14.EGL_NONE),
                0,
            )
        window =
            EGL14.eglCreateWindowSurface(display, config, preview, intArrayOf(EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, window, window, context))
        fun shader(type: Int, source: String): Int {
            val id = GLES20.glCreateShader(type)
            GLES20.glShaderSource(id, source)
            GLES20.glCompileShader(id)
            val status = IntArray(1)
            GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { GLES20.glGetShaderInfoLog(id) }
            return id
        }
        if (hdr)
            check(GLES20.glGetString(GLES20.GL_EXTENSIONS)?.contains("GL_EXT_YUV_target") == true) {
                "This graphics driver cannot preserve HDR camera colours"
            }
        val vertex =
            if (hdr)
                """
            #version 300 es
            in vec2 position; in vec2 uv; uniform mat4 transform; uniform vec2 frameTurn; uniform vec2 frameFit;
            out vec2 v; out vec2 world; void main(){gl_Position=vec4(position,0.,1.);
            vec2 p=vec2(frameTurn.x*position.x+frameTurn.y*position.y,-frameTurn.y*position.x+frameTurn.x*position.y);
            world=vec2(.5+.5*p.x/frameFit.x,.5-.5*p.y/frameFit.y);v=(transform*vec4(uv,0.,1.)).xy;}
        """
                    .trimIndent()
            else
                """
            attribute vec2 position; attribute vec2 uv; uniform mat4 transform; uniform vec2 frameTurn; uniform vec2 frameFit;
            varying vec2 v; varying vec2 world; void main(){gl_Position=vec4(position,0.,1.);
            vec2 p=vec2(frameTurn.x*position.x+frameTurn.y*position.y,-frameTurn.y*position.x+frameTurn.x*position.y);
            world=vec2(.5+.5*p.x/frameFit.x,.5-.5*p.y/frameFit.y);v=(transform*vec4(uv,0.,1.)).xy;}
        """
                    .trimIndent()
        val fragment =
            if (hdr)
                """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            #extension GL_EXT_YUV_target : require
            precision highp float;
            in vec2 v; in vec2 world; out vec4 color; uniform float side; uniform vec4 circle;
            uniform __samplerExternal2DY2YEXT image; uniform float tone; uniform float fullRange; uniform float primaries;
            vec3 linearHLG(vec3 x){return mix(x*x/3.,(exp((x-.55991073)/.17883277)+.28466892)/12.,step(vec3(.5),x));}
            vec3 encodeHLG(vec3 x){return mix(sqrt(3.*max(x,vec3(0.))),.17883277*log(max(12.*x-.28466892,vec3(.000001)))+.55991073,step(vec3(1./12.),x));}
            vec3 srgb(vec3 x){return mix(12.92*x,1.055*pow(max(x,vec3(0.)),vec3(1./2.4))-.055,step(vec3(.0031308),x));}
            void main(){
                if(circle.z>0. && length((world-circle.xy)/circle.zw)>1.)discard;
                if(side<-.5 && world.x+world.y>1.)discard;
                if(side>.5 && world.x+world.y<=1.)discard;
                vec3 yuv=texture(image,v).xyz-vec3(fullRange>.5 ? 0. : .0625,.5,.5);
                vec3 c=fullRange>.5 ? vec3(yuv.x+1.4746*yuv.z,
                    yuv.x-.16455*yuv.y-.57135*yuv.z,yuv.x+1.8814*yuv.y) :
                    vec3(1.1689*yuv.x+1.6853*yuv.z,1.1689*yuv.x-.1881*yuv.y-.6530*yuv.z,
                    1.1689*yuv.x+2.1502*yuv.y);
                c=clamp(c,0.,1.);
                if(tone>=0. && primaries>.5){
                    vec3 s=linearHLG(c);
                    if(primaries<1.5)s=vec3(.6274*s.r+.3293*s.g+.0433*s.b,
                        .0691*s.r+.9195*s.g+.0114*s.b,.0164*s.r+.0880*s.g+.8956*s.b);
                    else s=vec3(.753833*s.r+.198597*s.g+.047570*s.b,
                        .045744*s.r+.941777*s.g+.012479*s.b,-.001211*s.r+.017602*s.g+.983609*s.b);
                    c=encodeHLG(clamp(s,0.,1.));
                }
                if(tone>.5){
                    c=linearHLG(c)*3.;
                    c=vec3(1.6605*c.r-.5876*c.g-.0728*c.b,
                        -.1246*c.r+1.1329*c.g-.0083*c.b,-.0182*c.r-.1006*c.g+1.1187*c.b);
                    float luminance=max(dot(c,vec3(.2126,.7152,.0722)),0.);
                    c=srgb(max(c,vec3(0.))/(1.+luminance));
                }
                color=vec4(c,1.);
            }
        """
                    .trimIndent()
            else
                """
            #extension GL_OES_EGL_image_external : require
            precision highp float; varying vec2 v; varying vec2 world; uniform samplerExternalOES image; uniform float side; uniform vec4 circle;
            void main(){if(circle.z>0. && length((world-circle.xy)/circle.zw)>1.)discard;
                if(side<-.5 && world.x+world.y>1.)discard;
                if(side>.5 && world.x+world.y<=1.)discard;gl_FragColor=texture2D(image,v);}
        """
                    .trimIndent()
        val v = shader(GLES20.GL_VERTEX_SHADER, vertex)
        val f = shader(GLES20.GL_FRAGMENT_SHADER, fragment)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, v)
        GLES20.glAttachShader(program, f)
        GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] != 0) { GLES20.glGetProgramInfoLog(program) }
    }

    fun input(width: Int, height: Int, sensor: Int, front: Boolean, primaries: Int = 0): Surface =
        call {
            check(inputs.size < 2)
            val names = IntArray(1)
            GLES20.glGenTextures(1, names, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, names[0])
            for (p in intArrayOf(GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_TEXTURE_MAG_FILTER)) GLES20
                .glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, p, GLES20.GL_LINEAR)
            for (p in intArrayOf(GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_TEXTURE_WRAP_T)) GLES20
                .glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, p, GLES20.GL_CLAMP_TO_EDGE)
            val image = SurfaceTexture(names[0]).apply { setDefaultBufferSize(width, height) }
            val source =
                Input(names[0], image, Surface(image), width, height, sensor, front, primaries)
            inputs.add(source)
            image.setOnFrameAvailableListener(
                {
                    if (!closed)
                        try {
                            image.updateTexImage()
                            if (android.os.Build.VERSION.SDK_INT >= 33)
                                source.dataSpace = image.dataSpace
                            image.getTransformMatrix(source.transform)
                            source.ready = true
                            render()
                        } catch (e: Throwable) {
                            failure(e)
                        }
                },
                handler,
            )
            source.surface
        }

    fun configure(mode: Int, flip: Boolean, rotation: Int, w: Int, h: Int) = call {
        layout = mode
        swapped = flip
        displayRotation = rotation
        width = w
        height = h
        render()
    }

    fun reset() = call {
        zoom.fill(1f)
        panX.fill(0f)
        panY.fill(0f)
        pip = RectF(.63f, .06f, .95f, .38f)
        roundPip = RectF(.04f, .34f, .38f, .94f)
        render()
    }

    fun primaries(index: Int, value: Int) = call {
        inputs.getOrNull(index)?.primaries = value
        render()
    }

    fun snapshot(index: Int, w: Int, h: Int): Bitmap? = call {
        if (inputs.getOrNull(index)?.ready != true) return@call null
        val buffer =
            EGL14.eglCreatePbufferSurface(
                display,
                config,
                intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE),
                0,
            )
        check(buffer != EGL14.EGL_NO_SURFACE)
        try {
            draw(buffer, w, h, 0, index)
            val pixels = ByteBuffer.allocateDirect(w * h * 4)
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            val colors = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val p = ((h - y - 1) * w + x) * 4
                colors[y * w + x] =
                    -0x1000000 or
                        ((pixels.get(p).toInt() and 255) shl 16) or
                        ((pixels.get(p + 1).toInt() and 255) shl 8) or
                        (pixels.get(p + 2).toInt() and 255)
            }
            Bitmap.createBitmap(colors, w, h, Bitmap.Config.ARGB_8888)
        } finally {
            EGL14.eglMakeCurrent(display, window, window, context)
            EGL14.eglDestroySurface(display, buffer)
        }
    }

    fun gesture(x: Float, y: Float, dx: Float, dy: Float, scale: Float) = call {
        val area =
            if (layout == 3 && inputs.size == 2) {
                if (x + y > 1) 1 else 0
            } else if (layout == 5 && inputs.size == 2) { if (containsInset(x, y)) 1 else 0 }
            else regions().indexOfLast { it.contains(x, y) }.coerceAtLeast(0)
        val index = if (swapped) 1 - area else area
        zoom[index] = (zoom[index] * scale).coerceIn(1f, 8f)
        val rect = regions()[area]
        val source = inputs.getOrNull(index) ?: return@call
        val rotation = CameraOrientation.recording(source.sensor, displayRotation, source.front)
        val aspect =
            if (rotation % 180 == 0) source.width.toFloat() / source.height
            else source.height.toFloat() / source.width
        val wanted = width.toFloat() / height * rect.width() / rect.height()
        panX[index] =
            (panX[index] - dx * min(1f, wanted / aspect) / zoom[index] / rect.width()).coerceIn(
                -.5f,
                .5f,
            )
        panY[index] =
            (panY[index] + dy * min(1f, aspect / wanted) / zoom[index] / rect.height()).coerceIn(
                -.5f,
                .5f,
            )
        render()
    }

    private fun insetRect(): RectF {
        if (layout != 5) return RectF(pip)
        val aspect = width.toFloat() / height
        val diameter = roundPip.width().coerceIn(.12f, min(1f, 1f / aspect))
        val halfWidth = diameter / 2
        val halfHeight = halfWidth * aspect
        val x = roundPip.centerX().coerceIn(halfWidth, 1f - halfWidth)
        val y = roundPip.centerY().coerceIn(halfHeight, 1f - halfHeight)
        return RectF(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight)
    }

    private fun containsInset(x: Float, y: Float): Boolean {
        val rect = insetRect()
        return when (layout) {
            0 -> rect.contains(x, y)
            5 -> ((x - rect.centerX()) / (rect.width() / 2)).pow(2) +
                ((y - rect.centerY()) / (rect.height() / 2)).pow(2) <= 1f
            else -> false
        }
    }

    fun insetContains(x: Float, y: Float): Boolean = call { containsInset(x, y) }

    fun insetBounds(): RectF? = call { if (layout == 0 || layout == 5) insetRect() else null }

    fun resizePip(edges: Int, dx: Float, dy: Float) = call {
        if (layout == 5) {
            val rect = insetRect()
            val aspect = width.toFloat() / height
            val delta = when {
                edges and 1 != 0 -> -dx
                edges and 4 != 0 -> dx
                edges and 2 != 0 -> -dy / aspect
                else -> dy / aspect
            }
            val diameter = (rect.width() + delta).coerceIn(.12f, min(1f, 1f / aspect))
            roundPip = RectF(rect.centerX() - diameter / 2, rect.top,
                rect.centerX() + diameter / 2, rect.bottom)
            render()
            return@call
        }
        if (edges and 1 != 0) pip.left = (pip.left + dx).coerceIn(0f, pip.right - .12f)
        if (edges and 2 != 0) pip.top = (pip.top + dy).coerceIn(0f, pip.bottom - .12f)
        if (edges and 4 != 0) pip.right = (pip.right + dx).coerceIn(pip.left + .12f, 1f)
        if (edges and 8 != 0) pip.bottom = (pip.bottom + dy).coerceIn(pip.top + .12f, 1f)
        render()
    }

    fun movePip(dx: Float, dy: Float) = call {
        if (layout == 5) {
            val rect = insetRect()
            rect.offsetTo((rect.left + dx).coerceIn(0f, 1f - rect.width()),
                (rect.top + dy).coerceIn(0f, 1f - rect.height()))
            roundPip = rect
            render()
            return@call
        }
        val x = (pip.left + dx).coerceIn(0f, 1f - pip.width())
        val y = (pip.top + dy).coerceIn(0f, 1f - pip.height())
        pip.offsetTo(x, y)
        render()
    }

    fun record(
        surface: Surface?,
        w: Int = 0,
        h: Int = 0,
        hint: Int = 0,
        framesPerSecond: Int = 30,
    ) = call {
        handler.removeCallbacks(encoderTick)
        if (encoder != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, encoder)
        encoder = EGL14.EGL_NO_SURFACE
        if (surface != null) {
            encoder =
                EGL14.eglCreateWindowSurface(
                    display,
                    config,
                    surface,
                    if (hdr) intArrayOf(0x309d, 0x3540, EGL14.EGL_NONE)
                    else intArrayOf(EGL14.EGL_NONE),
                    0,
                )
            check(encoder != EGL14.EGL_NO_SURFACE) { "Cannot create recording surface" }
            encoderWidth = w
            encoderHeight = h
            orientation = hint
            fps = framesPerSecond.coerceIn(1, 60)
            nextFrame = System.nanoTime()
            handler.post(encoderTick)
        }
    }

    private fun regions(): List<RectF> =
        when {
            inputs.size == 1 -> listOf(RectF(0f, 0f, 1f, 1f))
            else ->
                when (layout) {
                    4 -> listOf(RectF(0f, 0f, 1f, 1f))
                    3 -> listOf(RectF(0f, 0f, 1f, 1f), RectF(0f, 0f, 1f, 1f))
                    0, 5 -> listOf(RectF(0f, 0f, 1f, 1f), insetRect())
                    1 -> listOf(RectF(0f, 0f, .5f, 1f), RectF(.5f, 0f, 1f, 1f))
                    else -> listOf(RectF(0f, 0f, 1f, .5f), RectF(0f, .5f, 1f, 1f))
                }
        }

    private fun render() {
        if (inputs.isEmpty() || !inputs.all { it.ready }) return
        draw(window, width, height, 0)
    }

    private fun draw(target: EGLSurface, w: Int, h: Int, hint: Int, snapshot: Int = -1) {
        check(EGL14.eglMakeCurrent(display, target, target, context))
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(program, "tone"),
            if (snapshot >= 0) -1f else if (hdr && target !== encoder) 1f else 0f,
        )
        val canvasAspect = if (hint % 180 == 0) w.toFloat() / h else h.toFloat() / w
        val worldAspect = if (target === encoder) width.toFloat() / height else canvasAspect
        val fitX = min(1f, worldAspect / canvasAspect)
        val fitY = min(1f, canvasAspect / worldAspect)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "frameFit"), fitX, fitY)
        val radians = hint * PI.toFloat() / 180
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(program, "frameTurn"),
            cos(radians),
            sin(radians),
        )
        (if (snapshot >= 0) listOf(RectF(0f, 0f, 1f, 1f)) else regions()).forEachIndexed {
            region,
            rect ->
            val index =
                if (snapshot >= 0) snapshot
                else if (swapped && inputs.size == 2) 1 - region else region
            GLES20.glUniform1f(
                GLES20.glGetUniformLocation(program, "side"),
                if (snapshot < 0 && layout == 3 && inputs.size == 2) if (region == 0) -1f else 1f
                else 0f,
            )
            val circle = snapshot < 0 && layout == 5 && region == 1
            GLES20.glUniform4f(GLES20.glGetUniformLocation(program, "circle"),
                rect.centerX(), rect.centerY(),
                if (circle) rect.width() / 2 else 0f, if (circle) rect.height() / 2 else 0f)
            val source = inputs[index]
            GLES20.glUniform1f(
                GLES20.glGetUniformLocation(program, "primaries"),
                source.primaries.toFloat(),
            )
            val rotation = CameraOrientation.recording(source.sensor, displayRotation, source.front)
            val aspect =
                if (rotation % 180 == 0) source.width.toFloat() / source.height
                else source.height.toFloat() / source.width
            val wanted = worldAspect * rect.width() / rect.height()
            val cropX = min(1f, wanted / aspect) / zoom[index]
            val cropY = min(1f, aspect / wanted) / zoom[index]
            val cx = (.5f + panX[index]).coerceIn(cropX / 2, 1 - cropX / 2)
            val cy = (.5f + panY[index]).coerceIn(cropY / 2, 1 - cropY / 2)
            val values = FloatArray(16)
            for (i in 0..3) {
                val right = i % 2 != 0
                val top = i >= 2
                val x = ((if (right) rect.right else rect.left) * 2 - 1) * fitX
                val y = (1 - (if (top) rect.top else rect.bottom) * 2) * fitY
                values[i * 4] = x * cos(radians) - y * sin(radians)
                values[i * 4 + 1] = x * sin(radians) + y * cos(radians)
                values[i * 4 + 2] = cx + (if (right != source.front) .5f else -.5f) * cropX
                values[i * 4 + 3] = cy + (if (top) .5f else -.5f) * cropY
            }
            vertices.clear()
            vertices.put(values)
            vertices.position(0)
            val position = GLES20.glGetAttribLocation(program, "position")
            val uv = GLES20.glGetAttribLocation(program, "uv")
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertices)
            GLES20.glEnableVertexAttribArray(position)
            vertices.position(2)
            GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, vertices)
            GLES20.glEnableVertexAttribArray(uv)
            val base = source.transform.copyOf()
            if (source.front && base[0] * base[5] - base[1] * base[4] > 0) {
                Matrix.translateM(base, 0, 1f, 0f, 0f)
                Matrix.scaleM(base, 0, -1f, 1f, 1f)
            }
            val turn = FloatArray(16)
            Matrix.setIdentityM(turn, 0)
            Matrix.translateM(turn, 0, .5f, .5f, 0f)
            Matrix.rotateM(turn, 0, (rotation - source.sensor).toFloat(), 0f, 0f, 1f)
            Matrix.translateM(turn, 0, -.5f, -.5f, 0f)
            val transform = FloatArray(16)
            Matrix.multiplyMM(transform, 0, base, 0, turn, 0)
            GLES20.glUniformMatrix4fv(
                GLES20.glGetUniformLocation(program, "transform"),
                1,
                false,
                transform,
                0,
            )
            GLES20.glUniform1f(
                GLES20.glGetUniformLocation(program, "fullRange"),
                if (
                    android.os.Build.VERSION.SDK_INT >= 33 &&
                        android.hardware.DataSpace.getRange(source.dataSpace) ==
                            android.hardware.DataSpace.RANGE_FULL
                )
                    1f
                else 0f,
            )
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, source.texture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "image"), 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        if (target === encoder)
            EGLExt.eglPresentationTimeANDROID(display, target, System.nanoTime())
        check(EGL14.eglSwapBuffers(display, target)) { "Cannot submit composition" }
    }

    internal fun inputDataSpaces(): List<Int> = call { inputs.map { it.dataSpace } }

    fun clearInputs() = call {
        inputs.forEach {
            it.image.setOnFrameAvailableListener(null)
            it.surface.release()
            it.image.release()
            GLES20.glDeleteTextures(1, intArrayOf(it.texture), 0)
        }
        inputs.clear()
    }

    override fun close() {
        if (closed) return
        closed = true
        call {
            EGL14.eglMakeCurrent(display, window, window, context)
            inputs.forEach {
                it.image.setOnFrameAvailableListener(null)
                it.surface.release()
                it.image.release()
                GLES20.glDeleteTextures(1, intArrayOf(it.texture), 0)
            }
            inputs.clear()
            GLES20.glDeleteProgram(program)
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            if (encoder != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, encoder)
            EGL14.eglDestroySurface(display, window)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            preview.release()
        }
        thread.quitSafely()
    }
}
