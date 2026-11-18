package org.myrec.app

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
    private val geometry: CameraLayout = CameraLayout(),
    private val gravity: (Double)->CameraPoint? = { null },
    private val planar: Boolean = hdr && HdrGraphics.support.planar,
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
    private var recordingClock: ((Long) -> Unit)? = null
    private var encoderStartedAt=Long.MAX_VALUE
    private var encoderTimestamp=Long.MIN_VALUE
    private var encoderFrameTime=0L
    private val encoderTick =
        object : Runnable {
            override fun run() {
                if (closed || encoder == EGL14.EGL_NO_SURFACE) return
                try {
                    val stamp=if (inputs.isNotEmpty() && inputs.all { it.ready }) (inputs.maxOf { it.time }*1e9).toLong() else Long.MIN_VALUE
                    if (stamp>=encoderStartedAt && stamp>encoderTimestamp) {
                        encoderFrameTime=stamp
                        draw(encoder, encoderWidth, encoderHeight, orientation)
                        encoderTimestamp=stamp
                    }
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
    private var mirrorPreview = true
    private var mirrorRecording = false
    private var follows=false
    private var selectedZoom=1.0
    private var inputZoom=1.0
    private var ownsFraming=false
    private var reserve=1.0
    private var singleFraming=CameraFraming()
    private var manualFraming=false
    private var horizonMaximum: Double?=null
    private var stabilized=false
    @Volatile var followStatus="Looking for a face"
        private set
    private val analysis=java.util.concurrent.Executors.newSingleThreadExecutor()
    private var analyzing=false

    private data class Faces(val time: Double,val boxes: List<CameraRect>,val zoom: Double)

    private class Input(
        val texture: Int,
        val image: SurfaceTexture?,
        val surface: Surface,
        val width: Int,
        val height: Int,
        val sensor: Int,
        val front: Boolean,
        var primaries: Int,
        val id: String,
        val realtime: Boolean,
        var hardwareZoom: Double,
        val pixels: P010Texture?=null,
    ) {
        val transform = FloatArray(16)
        var ready = false
        var dataSpace = 0
        var timestamp=0L
        val faces=java.util.ArrayDeque<Faces>()
        var target=CameraFaceTarget()
        var horizon=HorizonEstimate()
        var level: HorizonFraming?=null
        var time=Double.NEGATIVE_INFINITY
        var rotation=-1
        var analyzed=Double.NEGATIVE_INFINITY
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
        if (android.os.Looper.myLooper()==thread.looper) return block()
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
        if (hdr) {
            val support = HdrGraphics.evaluate(
                EGL14.eglQueryString(display,EGL14.EGL_EXTENSIONS).orEmpty(),
                GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty(),true,
                GLES20.glGetString(GLES20.GL_RENDERER).orEmpty(),allowPlanar=planar)
            check(support.reason == null) { "${support.reason} (${support.missing})" }
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
            ${if (planar) "" else "#extension GL_OES_EGL_image_external_essl3 : require\n#extension GL_EXT_YUV_target : require"}
            precision highp float;
            in vec2 v; in vec2 world; out vec4 color; uniform float side; uniform vec4 circle; uniform float diagonalFlip; uniform vec4 separator;
            ${if (planar) P010Texture.shader else "uniform __samplerExternal2DY2YEXT image;"} uniform float tone; uniform float fullRange; uniform float primaries;
            vec3 linearHLG(vec3 x){return mix(x*x/3.,(exp((x-.55991073)/.17883277)+.28466892)/12.,step(vec3(.5),x));}
            vec3 encodeHLG(vec3 x){return mix(sqrt(3.*max(x,vec3(0.))),.17883277*log(max(12.*x-.28466892,vec3(.000001)))+.55991073,step(vec3(1./12.),x));}
            vec3 srgb(vec3 x){return mix(12.92*x,1.055*pow(max(x,vec3(0.)),vec3(1./2.4))-.055,step(vec3(.0031308),x));}
            void main(){
                if(circle.z>0. && length((world-circle.xy)/circle.zw)>1.)discard;
                float distance=diagonalFlip>.5?world.y-world.x:world.x+world.y-1.;
                if(side<-.5 && distance>0.)discard;
                if(side>.5 && distance<=0.)discard;
                vec3 yuv=${if (planar) "p010(v)" else "texture(image,v).xyz"}*1023.-vec3(fullRange>.5 ? 0. : 64.,512.,512.);
                yuv/=fullRange>.5 ? vec3(1023.) : vec3(876.,896.,896.);
                vec3 c=vec3(yuv.x+1.4746*yuv.z,yuv.x-.164553*yuv.y-.571353*yuv.z,yuv.x+1.8814*yuv.y);
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
                    c=linearHLG(c);
                    c*=pow(max(dot(c,vec3(.2627,.678,.0593)),0.),.2)*(1000./203.);
                    c=vec3(1.6605*c.r-.5876*c.g-.0728*c.b,
                        -.1246*c.r+1.1329*c.g-.0083*c.b,-.0182*c.r-.1006*c.g+1.1187*c.b);
                    float y=max(1e-6,dot(c,vec3(.2126,.7152,.0722)));
                    float mapped=y<=.75?y:.75+.25*(1.-exp(-(y-.75)/.25));c*=mapped/y;
                    vec3 delta=c-mapped;float fit=1.;
                    for(int i=0;i<3;i++){if(delta[i]>0.)fit=min(fit,(1.-mapped)/delta[i]);else if(delta[i]<0.)fit=min(fit,-mapped/delta[i]);}
                    c=srgb(clamp(mapped+delta*max(0.,fit),0.,1.));
                }
                if(abs(side)>.5)c=mix(vec3(.12),c,clamp(abs(distance)/length(separator.zw)-.5,0.,1.));
                if((separator.x>0. && abs(world.x-separator.x)<separator.z) ||
                    (separator.y>0. && abs(world.y-separator.y)<separator.w))c=vec3(.12);
                color=vec4(c,1.);
            }
        """
                    .trimIndent()
            else
                """
            #extension GL_OES_EGL_image_external : require
            precision highp float; varying vec2 v; varying vec2 world; uniform samplerExternalOES image; uniform float side; uniform vec4 circle; uniform float diagonalFlip; uniform vec4 separator;
            void main(){if(circle.z>0. && length((world-circle.xy)/circle.zw)>1.)discard;
                float distance=diagonalFlip>.5?world.y-world.x:world.x+world.y-1.;
                if(side<-.5 && distance>0.)discard;
                if(side>.5 && distance<=0.)discard;
                vec4 pixel=texture2D(image,v);
                if(abs(side)>.5)pixel.rgb=mix(vec3(.12),pixel.rgb,clamp(abs(distance)/length(separator.zw)-.5,0.,1.));
                if((separator.x>0. && abs(world.x-separator.x)<separator.z) ||
                    (separator.y>0. && abs(world.y-separator.y)<separator.w))pixel.rgb=vec3(.12);
                gl_FragColor=pixel;}
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

    fun input(width: Int, height: Int, sensor: Int, front: Boolean, primaries: Int = 0,
        id: String="", realtime: Boolean=false,zoom: Double=1.0): Surface =
        call {
            check(inputs.size < 2)
            if (planar) {
                val pixels=P010Texture(width,height)
                val source=Input(0,null,pixels.reader.surface,width,height,sensor,front,primaries,id,realtime,zoom,pixels)
                inputs.add(source)
                pixels.reader.setOnImageAvailableListener({ reader ->
                    if (!closed && source in inputs) try { reader.acquireLatestImage()?.use { frame ->
                        pixels.upload(frame,source.transform);source.timestamp=frame.timestamp
                        if (android.os.Build.VERSION.SDK_INT>=33) source.dataSpace=frame.dataSpace
                        source.ready=true;updateAssistance(source);render()
                    } } catch (e: Throwable) { failure(e) }
                },handler)
                return@call source.surface
            }
            val names = IntArray(1)
            GLES20.glGenTextures(1, names, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, names[0])
            for (p in intArrayOf(GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_TEXTURE_MAG_FILTER)) GLES20
                .glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, p, GLES20.GL_LINEAR)
            for (p in intArrayOf(GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_TEXTURE_WRAP_T)) GLES20
                .glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, p, GLES20.GL_CLAMP_TO_EDGE)
            val image = SurfaceTexture(names[0]).apply { setDefaultBufferSize(width, height) }
            val source =
                Input(names[0], image, Surface(image), width, height, sensor, front, primaries,id,realtime,zoom)
            inputs.add(source)
            image.setOnFrameAvailableListener(
                {
                    if (!closed)
                        try {
                            image.updateTexImage()
                            if (android.os.Build.VERSION.SDK_INT >= 33)
                                source.dataSpace = image.dataSpace
                            source.timestamp=image.timestamp
                            image.getTransformMatrix(source.transform)
                            source.ready = true
                            updateAssistance(source)
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

    fun mirrors(preview: Boolean, recording: Boolean) = call {
        mirrorPreview = preview
        mirrorRecording = recording
        render()
    }

    fun reset() = call {
        geometry.reset(); singleFraming=CameraFraming(); manualFraming=false
        render()
    }
    fun layoutState()=call { geometry.copy() }
    fun divider(value: Float)=call {
        if (layout==1) geometry.vertical=value.toDouble().coerceIn(.15,.85)
        if (layout==2) geometry.horizontal=value.toDouble().coerceIn(.15,.85)
        render()
    }
    fun flipDiagonal()=call { geometry.diagonalFlipped=!geometry.diagonalFlipped; render() }
    fun assists(follow: Boolean,maximum: Double?,stabilization: Boolean,zoom: Double=1.0,base: Double=1.0,owns: Boolean=false)=call {
        if (follow!=follows) {
            inputs.forEach { it.target=CameraFaceTarget(); it.faces.clear() }
            followStatus="Looking for a face"
            if (follow) manualFraming=false
        }
        follows=follow; horizonMaximum=maximum; stabilized=stabilization; selectedZoom=zoom; inputZoom=base; ownsFraming=owns
    }
    fun faces(id: String,boxes: List<CameraRect>,time: Double,zoom: Double=1.0) {
        handler.post {
            val source=inputs.firstOrNull { it.id==id } ?: return@post
            source.faces.addLast(Faces(time,boxes,zoom)); while (source.faces.size>8) source.faces.removeFirst()
        }
    }
    fun assistanceState()=call { inputs.indices.map { i ->
        val f=if (inputs.size==1) singleFraming else geometry.framing[i]
        doubleArrayOf(f.x,f.y,f.zoom,inputs[i].level?.radians ?: 0.0,inputs[i].level?.magnification ?: 1.0)
    } }
    private fun updateAssistance(source: Input) {
        val index=inputs.indexOf(source)
        val time=if (source.timestamp<=0) System.nanoTime()/1e9
            else (source.timestamp-(if (source.realtime) android.os.SystemClock.elapsedRealtimeNanos()-System.nanoTime() else 0))/1e9
        val rotation=CameraOrientation.recording(source.sensor,displayRotation,source.front)
        val previous=source.time
        if (source.rotation!=rotation || time-previous !in 0.0..0.5) {
            source.target=CameraFaceTarget(); source.horizon=HorizonEstimate()
        }
        val dt=(time-if (previous.isFinite()) previous else time-1.0/30).coerceIn(0.0,.1)
        source.rotation=rotation; source.time=time
        val sw=if (rotation%180==0) source.width.toDouble() else source.height.toDouble()
        val sh=if (rotation%180==0) source.height.toDouble() else source.width.toDouble()
        updateLevel(source,index,time,rotation,sw,sh)
        while (source.faces.isNotEmpty() && source.faces.first.time<=time+.005) {
            val detection=source.faces.removeFirst()
            source.hardwareZoom=detection.zoom.coerceAtLeast(.01)
            val boxes=detection.boxes.map { r ->
                val corners=listOf(CameraPoint(r.left,r.top),CameraPoint(r.right,r.top),CameraPoint(r.left,r.bottom),CameraPoint(r.right,r.bottom)).map { CameraFraming.oriented(it,rotation) }
                val points=if (inputs.size==2 && source.level!=null) {
                    val map=CameraProjection(sw,sh,sw,sh,CameraFraming(),source.level)
                    corners.map(map::screen)
                } else corners
                CameraRect(points.minOf { it.x },points.minOf { it.y },points.maxOf { it.x },points.maxOf { it.y })
            }
            source.target.observe(boxes,detection.time)
        }
        if (inputs.size==1) {
            reserve=if (manualFraming) 1.0 else if (follows) min(1.2,reserve+.6*dt) else max(1.0,reserve-.6*dt)
            singleFraming.zoom=if (ownsFraming) max(1.0,max(selectedZoom,inputZoom*reserve)/source.hardwareZoom) else 1.0
        }
        if (follows) {
            val region=regions()[if (swapped && inputs.size==2) 1-index else index]
            val current=if (inputs.size==1) singleFraming else geometry.framing[index]
            val face=source.target.current(time)
            val slot=if (swapped && inputs.size==2) 1-index else index
            val target=CameraPoint(.5,if (inputs.size==2 && layout==3) (if (slot==0) .25 else .75) else .5)
            val next=CameraFaceFraming.follow(face,sw,sh,width*region.width().toDouble(),height*region.height().toDouble(),current,dt,target)
            if (inputs.size==1) {
                singleFraming=next
            } else geometry.framing[index]=next
            followStatus=when {
                inputs.any { it.target.current(time)!=null } -> "Following face"
                inputs.any { it.target.seenAt.isFinite() } -> "Holding last framing"
                else -> "Looking for a face"
            }
        }
    }
    private fun updateLevel(source: Input,index: Int,time: Double,rotation: Int,sw: Double,sh: Double) {
        val maximum=horizonMaximum
        if (maximum!=null && !source.front) {
            val sample=gravity(time)
            val roll=sample?.let { HorizonEstimate.roll(it.x,it.y,90.0) }
            val correction=source.horizon.correction(roll,time,maximum,stabilized)
            source.level=HorizonFraming(correction,HorizonFraming.scale(maximum,sw,sh))
            if (roll!=null && abs(roll)<13*PI/180 && !analyzing && time-source.analyzed>=.24) {
                source.analyzed=time; analyzing=true
                val ratio=min(1.0,256/max(sw,sh))
                val image=snapshot(index,max(32,(sw*ratio).toInt()),max(24,(sh*ratio).toInt()))
                if (image==null) analyzing=false
                else analysis.execute {
                    var line: Double?=null
                    try {
                        val pixels=IntArray(image.width*image.height); image.getPixels(pixels,0,image.width,0,0,image.width,image.height)
                        val luma=ByteArray(pixels.size) { i -> val p=pixels[i]; (((p shr 16 and 255)*54+(p shr 8 and 255)*183+(p and 255)*19) shr 8).toByte() }
                        line=HorizonLine.estimate(luma,image.width,image.height)
                    } finally {
                        image.recycle()
                        val found=line
                        handler.post { analyzing=false; if (!closed && source in inputs && source.rotation==rotation) source.horizon.observe(found,roll,time) }
                    }
                }
            }
        } else source.level=null
    }

    fun primaries(index: Int, value: Int) = call {
        inputs.getOrNull(index)?.primaries = value
        render()
    }

    fun snapshot(index: Int,w: Int,h: Int)=readFrame(index,w,h)
    fun previewFrame(w: Int,h: Int)=readFrame(-1,w,h)
    private fun readFrame(index: Int,w: Int,h: Int): Bitmap?=call {
        if (inputs.isEmpty() || if (index>=0) inputs.getOrNull(index)?.ready!=true else !inputs.all { it.ready }) return@call null
        val buffer =
            EGL14.eglCreatePbufferSurface(
                display,
                config,
                intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE),
                0,
            )
        check(buffer != EGL14.EGL_NO_SURFACE)
        try {
            draw(buffer, w, h, 0, index,analysis=true)
            val pixels = ByteBuffer.allocateDirect(w * h * 4)
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            CameraReadback.bitmap(pixels,w,h)
        } finally {
            EGL14.eglMakeCurrent(display, window, window, context)
            EGL14.eglDestroySurface(display, buffer)
        }
    }

    private fun projection(index: Int,rect: RectF,mirrored: Boolean,aspect: Double=width.toDouble()/height,raw: Boolean=false): CameraProjection {
        val source=inputs[index]
        val rotation=CameraOrientation.recording(source.sensor,displayRotation,source.front)
        val sw=if (rotation%180==0) source.width.toDouble() else source.height.toDouble()
        val sh=if (rotation%180==0) source.height.toDouble() else source.width.toDouble()
        val framing=if (raw) CameraFraming() else if (inputs.size==1) singleFraming else geometry.framing[index]
        return CameraProjection(sw,sh,aspect*rect.width(),rect.height().toDouble(),framing,if (raw) null else source.level,mirrored,inputs.size==2 && !raw)
    }
    fun framePoint(id: String,point: CameraPoint,recordedMirror: Boolean): CameraPoint?=call {
        val index=inputs.indexOfFirst { it.id==id };if (index<0) return@call null
        val slot=if (swapped && inputs.size==2) 1-index else index
        val rect=regions().getOrNull(slot) ?: return@call null
        val source=inputs[index]
        val rotation=CameraOrientation.recording(source.sensor,displayRotation,source.front)
        val oriented=CameraFraming.oriented(point,rotation)
        val output=projection(index,rect,source.front && recordedMirror).screen(oriented)
        CameraPoint((rect.left+output.x*rect.width()).coerceIn(0.0,1.0),(rect.top+output.y*rect.height()).coerceIn(0.0,1.0))
    }
    fun slotAt(x: Float,y: Float): Int?=call {
        if (x !in 0f..1f || y !in 0f..1f || inputs.isEmpty()) null
        else if (inputs.size==1) 0 else geometry.slot(layout,x*width.toDouble(),y*height.toDouble(),width.toDouble(),height.toDouble())
    }
    fun gesture(x: Float,y: Float,dx: Float,dy: Float,scale: Float,selectedSlot: Int?=null)=call {
        if (inputs.isEmpty()) return@call
        val slot=if (inputs.size==1) 0 else selectedSlot ?: geometry.slot(layout,x*width.toDouble(),y*height.toDouble(),width.toDouble(),height.toDouble())
        val index=if (swapped && inputs.size==2) 1-slot else slot
        val rect=regions()[slot]
        val f=if (inputs.size==1) singleFraming else geometry.framing[index]
        f.zoom=(f.zoom*scale).coerceIn(1.0,if (inputs.size==1) 120.0 else 4.0)
        val map=projection(index,rect,inputs[index].front && mirrorPreview)
        val a=map.source(.5,.5); val b=map.source(.5+dx/rect.width(),.5+dy/rect.height())
        if (map.cropX<.99999) f.x=(f.x-(b.x-a.x)/(1-map.cropX)).coerceIn(0.0,1.0)
        if (map.cropY<.99999) f.y=(f.y-(b.y-a.y)/(1-map.cropY)).coerceIn(0.0,1.0)
        if (inputs.size==1 && abs(dx)+abs(dy)>0) manualFraming=true
        render()
    }
    fun singleZoom()=call { singleFraming.zoom }
    fun singleNativeZoom()=call { singleFraming.zoom*(inputs.firstOrNull()?.hardwareZoom ?: 1.0) }
    private fun insetRect(): RectF {
        val r=geometry.regions(layout,width.toDouble(),height.toDouble())[1]
        return RectF((r.left/width).toFloat(),(r.top/height).toFloat(),(r.right/width).toFloat(),(r.bottom/height).toFloat())
    }
    private fun containsInset(x: Float,y: Float)=
        (layout==0 || layout==5) && geometry.slot(layout,x*width.toDouble(),y*height.toDouble(),width.toDouble(),height.toDouble())==1
    fun insetContains(x: Float,y: Float): Boolean=call { containsInset(x,y) }
    fun insetBounds(): RectF?=call { if (layout==0 || layout==5) insetRect() else null }
    fun resizeInset(scale: Float,axis: Int)=call { geometry.resize(layout,scale.toDouble(),axis,width.toDouble(),height.toDouble()); render() }
    fun resizePip(edges: Int,dx: Float,dy: Float)=call {
        val rect=insetRect()
        if (layout==5) {
            val delta=when { edges and 1!=0 -> -dx; edges and 4!=0 -> dx; edges and 2!=0 -> -dy*height/width; else -> dy*height/width }
            geometry.resize(layout,1.0+delta/rect.width(),0,width.toDouble(),height.toDouble())
        } else {
            val minimum=.18f*min(width,height)
            if (edges and 1!=0) rect.left=(rect.left+dx).coerceIn(0f,rect.right-minimum/width)
            if (edges and 2!=0) rect.top=(rect.top+dy).coerceIn(0f,rect.bottom-minimum/height)
            if (edges and 4!=0) rect.right=(rect.right+dx).coerceIn(rect.left+minimum/width,1f)
            if (edges and 8!=0) rect.bottom=(rect.bottom+dy).coerceIn(rect.top+minimum/height,1f)
            val w=rect.width()*width/min(width,height).toDouble(); val h=rect.height()*height/min(width,height).toDouble()
            geometry.insetSize=sqrt(w*h); geometry.insetAspect=w/h
            geometry.insetX=rect.centerX().toDouble(); geometry.insetY=rect.centerY().toDouble()
        }
        render()
    }
    fun movePip(dx: Float,dy: Float)=call {
        val rect=insetRect()
        geometry.insetX=(rect.centerX()+dx).coerceIn(rect.width()/2,1-rect.width()/2).toDouble()
        geometry.insetY=(rect.centerY()+dy).coerceIn(rect.height()/2,1-rect.height()/2).toDouble()
        render()
    }

    fun record(
        surface: Surface?,
        w: Int = 0,
        h: Int = 0,
        hint: Int = 0,
        framesPerSecond: Int = 30, clock: ((Long) -> Unit)? = null,
    ) = call {
        handler.removeCallbacks(encoderTick)
        recordingClock=clock
        if (encoder != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, encoder)
        encoder = EGL14.EGL_NO_SURFACE
        if (surface != null) {
            encoderStartedAt=System.nanoTime(); encoderTimestamp=Long.MIN_VALUE
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

    private fun regions(): List<RectF> {
        if (inputs.size==1 || layout==4) return listOf(RectF(0f,0f,1f,1f))
        return geometry.regions(layout,width.toDouble(),height.toDouble()).map {
            RectF((it.left/width).toFloat(),(it.top/height).toFloat(),(it.right/width).toFloat(),(it.bottom/height).toFloat())
        }
    }

    @Volatile var previewCovered=false
    private fun render() {
        // The effect frame covers this surface; camera inputs and encoder draws still advance.
        if (previewCovered || inputs.isEmpty() || !inputs.all { it.ready }) return
        draw(window, width, height, 0)
    }

    @Volatile var analysisFrame: ((Long,()->Bitmap)->Unit)?=null
    private val readback=CameraReadback()
    private fun draw(target: EGLSurface, w: Int, h: Int, hint: Int, snapshot: Int = -1,analysis: Boolean=false) {
        check(EGL14.eglMakeCurrent(display, target, target, context))
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"diagonalFlip"),if (geometry.diagonalFlipped) 1f else 0f)
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(program, "tone"),
            if (snapshot >= 0) -1f else if (hdr && (target !== encoder || analysis)) 1f else 0f,
        )
        val canvasAspect = if (analysis && target!==encoder && snapshot<0) width.toFloat()/height
            else if (hint % 180 == 0) w.toFloat() / h else h.toFloat() / w
        val worldAspect = if (target === encoder) width.toFloat() / height else canvasAspect
        val fitX = min(1f, worldAspect / canvasAspect)
        val fitY = min(1f, canvasAspect / worldAspect)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(program,"separator"),
            if (snapshot<0 && inputs.size==2 && layout==1) geometry.vertical.toFloat() else 0f,
            if (snapshot<0 && inputs.size==2 && layout==2) geometry.horizontal.toFloat() else 0f,
            1f/((if (hint%180==0) w else h)*fitX),1f/((if (hint%180==0) h else w)*fitY))
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
            val mirrored = source.front && if (target === encoder) mirrorRecording else mirrorPreview
            GLES20.glUniform1f(
                GLES20.glGetUniformLocation(program, "primaries"),
                source.primaries.toFloat(),
            )
            val map=projection(index,rect,mirrored,worldAspect.toDouble(),snapshot>=0)
            val values=FloatArray(16)
            for (i in 0..3) {
                val right=i%2!=0; val top=i>=2
                val x=((if (right) rect.right else rect.left)*2-1)*fitX
                val y=(1-(if (top) rect.top else rect.bottom)*2)*fitY
                values[i*4]=x*cos(radians)-y*sin(radians)
                values[i*4+1]=x*sin(radians)+y*cos(radians)
                val point=map.source(if (right) 1.0 else 0.0,if (top) 0.0 else 1.0)
                values[i*4+2]=point.x.toFloat(); values[i*4+3]=(1-point.y).toFloat()
            }
            val rotation=CameraOrientation.recording(source.sensor,displayRotation,source.front)
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
            if (source.pixels!=null) source.pixels.bind(program) else {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, source.texture)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"image"),0)
            }
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        if (analysis) return
        if (target === encoder) {
            val timestamp=encoderFrameTime
            analysisFrame?.invoke(timestamp) { readback.read(w,h,hint) { sw,sh -> draw(target,sw,sh,hint,analysis=true) } }
            recordingClock?.invoke(timestamp)
            EGLExt.eglPresentationTimeANDROID(display, target, timestamp)
        }
        check(EGL14.eglSwapBuffers(display, target)) { "Cannot submit composition (EGL 0x${EGL14.eglGetError().toString(16)})" }
    }

    internal val inputFormat get()=if (planar && android.os.Build.VERSION.SDK_INT>=33) android.graphics.ImageFormat.YCBCR_P010 else android.graphics.ImageFormat.PRIVATE

    internal fun inputDataSpaces(): List<Int> = call { inputs.map { it.dataSpace } }

    fun clearInputs() = call {
        inputs.forEach {
            it.image?.setOnFrameAvailableListener(null)
            it.surface.release()
            it.image?.release();it.pixels?.close()
            GLES20.glDeleteTextures(1, intArrayOf(it.texture), 0)
        }
        inputs.clear(); reserve=1.0; singleFraming=CameraFraming(); manualFraming=false
    }

    override fun close() {
        if (closed) return
        closed = true
        call {
            EGL14.eglMakeCurrent(display, window, window, context)
            readback.close()
            inputs.forEach {
                it.image?.setOnFrameAvailableListener(null)
                it.surface.release()
                it.image?.release();it.pixels?.close()
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
        analysis.shutdown()
        thread.quitSafely()
    }
}
