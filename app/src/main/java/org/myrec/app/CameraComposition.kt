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

// Half a 30 fps frame plus a few milliseconds for capture jitter.
private const val MAX_PAIR_SKEW_NS=20_000_000L

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
    private val thread = HandlerThread("camera-composition",android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var window = EGL14.EGL_NO_SURFACE
    private var previewWidth=width
    private var previewHeight=height
    private var frameSurface=EGL14.EGL_NO_SURFACE
    private var frameWidth=0
    private var frameHeight=0
    private var framePixels=ByteBuffer.allocateDirect(0)
    private var encoder = EGL14.EGL_NO_SURFACE
    private var encoderWidth = 0
    private var encoderHeight = 0
    private var orientation = 0
    private var fps = 30
    private var nextFrame = Long.MIN_VALUE
    private var recordingClock: ((Long) -> Unit)? = null
    private var encoderStartedAt=Long.MAX_VALUE
    private var encoderTimestamp=Long.MIN_VALUE
    private var encoderFrameTime=0L
    private var program = 0
    private var retainedProgram = 0
    private var frameCopy: CameraFrameCopy? = null
    private var closed = false
    private val inputs = mutableListOf<Input>()
    @Volatile var pairedFrames = 0L
        private set
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
    private var sceneHorizon=false
    private var stabilized=false
    @Volatile internal var softwareHdr = emptyMap<String, Float>()
    private var exposureMap: CameraExposureMap? = null
    private var exposureMapFailed = false
    @Volatile var followStatus="Looking for a face"
        private set
    private val analysis=java.util.concurrent.Executors.newSingleThreadExecutor()
    private var analyzing=false

    private data class Faces(val time: Double,val boxes: List<CameraRect>,val zoom: Double)

    private class Frame(val slot: Int) {
        val transform=FloatArray(16)
        var time=Long.MIN_VALUE
        var dataSpace=0
        var reverseX=false
        val texture=CameraFrameCopy.Texture()
    }

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
        val maximumFps: Int,
        val pixels: P010Texture?=null,
    ) {
        // Two pending frames can absorb delivery jitter while the displayed frame stays intact.
        val frames=Array(3,::Frame)
        val pending=java.util.ArrayDeque<Frame>(frames.size-1)
        var frame=frames[0]
        var warmGain=1f
        var matchedExposure=0f
        var exposureHistory: CameraExposureMap.History? = null
        var receivedTimestamp=0L
        var receivedTime=Long.MIN_VALUE
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
        if (hdr) {
            val support = HdrGraphics.evaluate(
                EGL14.eglQueryString(display,EGL14.EGL_EXTENSIONS).orEmpty(),
                GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty(),true,
                GLES20.glGetString(GLES20.GL_RENDERER).orEmpty(),allowPlanar=planar)
            check(support.reason == null) { "${support.reason} (${support.missing})" }
        }
        program=compositionProgram()
    }

    private fun compositionProgram(retained: Boolean=false): Int {
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
            ${if (planar || retained) "" else "#extension GL_OES_EGL_image_external_essl3 : require\n#extension GL_EXT_YUV_target : require"}
            precision highp float;
            in vec2 v; in vec2 world; out vec4 color; uniform float side; uniform vec4 circle; uniform float diagonalFlip; uniform vec4 separator;
            ${when {
                planar -> P010Texture.shader
                retained -> "uniform highp sampler2D image;"
                else -> "uniform __samplerExternal2DY2YEXT image;"
            }} uniform float tone; uniform float fullRange; uniform float primaries; uniform float warmGain; uniform float matchedExposure;
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
                if(tone>=0. && (tone>.5 || primaries>.5 || warmGain<.999)){
                    vec3 s=linearHLG(c);
                    if(primaries>.5 && primaries<1.5)s=vec3(.6274*s.r+.3293*s.g+.0433*s.b,
                        .0691*s.r+.9195*s.g+.0114*s.b,.0164*s.r+.0880*s.g+.8956*s.b);
                    else if(primaries>1.5)s=vec3(.753833*s.r+.198597*s.g+.047570*s.b,
                        .045744*s.r+.941777*s.g+.012479*s.b,-.001211*s.r+.017602*s.g+.983609*s.b);
                    s=clamp(s,0.,1.);
                    if(matchedExposure>0.){
                        // This camera's HLG code values track its SDR response. Match diffuse
                        // luminance while reserving the full 1000 nit range for highlights.
                        vec3 ref=mix(c/12.92,pow((c+.055)/1.055,vec3(2.4)),step(vec3(.04045),c));
                        float y=dot(ref,vec3(.2126,.7152,.0722));
                        y=y*matchedExposure/(1.+(matchedExposure-1.)*y);
                        float head=max(0.,y-.18);
                        float light=min(y,.18)+head/(1.-head*(1./.82-1./(1000./203.-.18)));
                        s*=pow(light*(203./1000.),1./1.2)/max(.000001,dot(s,vec3(.2627,.678,.0593)));
                    }
                    if(warmGain<.999){
                        vec3 rgb=vec3(dot(s,vec3(1.6605,-.5876,-.0728)),dot(s,vec3(-.1246,1.1329,-.0083)),dot(s,vec3(-.0182,-.1006,1.1187)));
                        vec3 n=rgb/max(.0001,rgb.r+rgb.g+rgb.b);
                        float warm=smoothstep(.38,.46,n.r)*(1.-smoothstep(.72,.82,n.r))*smoothstep(.0,.04,n.g-n.b)*(1.-smoothstep(.22,.30,n.g-n.b));
                        s=mix(vec3(dot(s,vec3(.2627,.678,.0593))),s,mix(1.,warmGain,warm));
                    }
                    c=tone>.5 ? s : encodeHLG(s);
                }
                if(tone>.5){
                    c*=pow(max(dot(c,vec3(.2627,.678,.0593)),0.),.2)*(1000./203.);
                    c=vec3(1.6605*c.r-.5876*c.g-.0728*c.b,
                        -.1246*c.r+1.1329*c.g-.0083*c.b,-.0182*c.r-.1006*c.g+1.1187*c.b);
                    float y=max(1e-6,dot(c,vec3(.2126,.7152,.0722)));
                    float head=max(0.,y-.18);
                    float mapped=min(1.,min(y,.18)+head/(1.+head*(1./.82-1./(1000./203.-.18))));c*=mapped/y;
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
            ${if (retained) "" else "#extension GL_OES_EGL_image_external : require"}
            precision highp float; varying vec2 v; varying vec2 world; uniform ${if (retained) "sampler2D" else "samplerExternalOES"} image; uniform float side; uniform vec4 circle; uniform float diagonalFlip; uniform vec4 separator;
            uniform float softwareHdr;
            ${SoftwareHdr.shader}
            void main(){if(circle.z>0. && length((world-circle.xy)/circle.zw)>1.)discard;
                float distance=diagonalFlip>.5?world.y-world.x:world.x+world.y-1.;
                if(side<-.5 && distance>0.)discard;
                if(side>.5 && distance<=0.)discard;
                vec4 pixel=texture2D(image,v);
                if(softwareHdr>0.)pixel.rgb=recoverExposure(pixel.rgb,softwareHdr,v);
                if(abs(side)>.5)pixel.rgb=mix(vec3(.12),pixel.rgb,clamp(abs(distance)/length(separator.zw)-.5,0.,1.));
                if((separator.x>0. && abs(world.x-separator.x)<separator.z) ||
                    (separator.y>0. && abs(world.y-separator.y)<separator.w))pixel.rgb=vec3(.12);
                gl_FragColor=pixel;}
        """
                    .trimIndent()
        return cameraProgram(vertex,fragment)
    }

    fun input(width: Int, height: Int, sensor: Int, front: Boolean, primaries: Int = 0,
        id: String="", realtime: Boolean=false,zoom: Double=1.0,maximumFps: Int=30): Surface =
        call {
            check(inputs.size < 2)
            if (planar) {
                val pixels=P010Texture(width,height)
                val source=Input(0,null,pixels.reader.surface,width,height,sensor,front,primaries,id,realtime,zoom,maximumFps,pixels)
                inputs.add(source)
                pixels.reader.setOnImageAvailableListener({ reader ->
                    if (!closed && source in inputs) try { reader.acquireLatestImage()?.use { image ->
                        val dataSpace=if (android.os.Build.VERSION.SDK_INT>=33) image.dataSpace else 0
                        receive(source,image.timestamp,dataSpace) { frame ->
                            pixels.upload(image,frame.transform,frame.slot)
                        }
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
                Input(names[0], image, Surface(image), width, height, sensor, front, primaries,id,realtime,zoom,maximumFps)
            inputs.add(source)
            image.setOnFrameAvailableListener(
                {
                    if (!closed && source in inputs)
                        try {
                            image.updateTexImage()
                            val dataSpace=if (android.os.Build.VERSION.SDK_INT>=33) image.dataSpace else 0
                            receive(source,image.timestamp,dataSpace) { frame ->
                                image.getTransformMatrix(frame.transform)
                                if (inputs.size==2) {
                                    val copy=frameCopy ?: CameraFrameCopy(hdr).also { frameCopy=it }
                                    copy.store(source.texture,frame.texture,width,height,frame.transform)
                                }
                            }
                        } catch (e: Throwable) {
                            failure(e)
                        }
                },
                handler,
            )
            source.surface
        }

    private inline fun receive(source: Input, timestamp: Long, dataSpace: Int, fill: (Frame)->Unit) {
        val paired=inputs.size==2
        if (paired && timestamp<=source.receivedTimestamp) return
        val now=System.nanoTime()
        val time=when {
            timestamp<=0 -> now
            source.realtime -> timestamp-(android.os.SystemClock.elapsedRealtimeNanos()-now)
            else -> timestamp
        }
        if (paired && time<=source.receivedTime) return
        source.receivedTimestamp=timestamp.coerceAtLeast(0L)
        source.receivedTime=time
        val frame=if (paired) {
            if (source.pending.size==source.frames.size-1) source.pending.removeFirst()
            source.frames.first { it!==source.frame && it !in source.pending }
        } else source.frame
        fill(frame)
        frame.time=time
        frame.dataSpace=dataSpace
        val m=frame.transform
        frame.reverseX=source.front && m[0]*m[5]-m[1]*m[4]>0
        if (frame.texture.name!=0) Matrix.setIdentityM(m,0)
        if (paired) {
            source.pending.addLast(frame)
            if (!pairFrames()) return
            pairedFrames++
        } else updateAssistance(source)
        if (!hdr) updateExposureMap()
        encodeFrame()
        render()
    }

    private fun updateExposureMap() {
        if (exposureMapFailed) return
        val strengths = softwareHdr
        if (strengths.isEmpty()) {
            if (exposureMap != null) inputs.forEach { it.exposureHistory?.reset() }
            return
        }
        try {
            val map = exposureMap ?: CameraExposureMap().also { exposureMap = it }
            for (input in inputs) {
                if ((strengths[input.id] ?: 0f) <= 0f) {
                    input.exposureHistory?.reset()
                    continue
                }
                val history = input.exposureHistory
                    ?: CameraExposureMap.History().also { input.exposureHistory = it }
                val retained = input.frame.texture.name
                map.update(history, if (retained != 0) retained else input.texture, retained != 0,input.frame.time)
            }
        } catch (e: Exception) {
            // Keep the single-exposure curve available if the GPU cannot draw the guide.
            exposureMapFailed = true
            android.util.Log.w("MyRec-HDR","Using the global Software HDR curve",e)
        }
    }

    private fun encodeFrame() {
        if (encoder == EGL14.EGL_NO_SURFACE || inputs.any { it.frame.time < encoderStartedAt }) return
        val stamp = inputs.maxOf { it.frame.time }
        if (stamp <= encoderTimestamp) return
        val sourceFps = inputs.maxOf { it.maximumFps }
        // Only downsample inputs negotiated above the requested recording rate.
        if (sourceFps > fps) {
            val period = 1_000_000_000L / fps
            val tolerance = 500_000_000L / sourceFps
            if (nextFrame != Long.MIN_VALUE && stamp < nextFrame - tolerance) return
            nextFrame = (if (nextFrame == Long.MIN_VALUE || stamp - nextFrame >= period) stamp else nextFrame) + period
        } else nextFrame = Long.MIN_VALUE
        encoderFrameTime = stamp
        draw(encoder, encoderWidth, encoderHeight, orientation)
        encoderTimestamp = stamp
    }

    private fun pairFrames(): Boolean {
        val left=inputs[0]
        val right=inputs[1]
        while (left.pending.isNotEmpty() && right.pending.isNotEmpty()) {
            val skew=left.pending.first.time-right.pending.first.time
            if (abs(skew)<=MAX_PAIR_SKEW_NS) {
                left.frame=left.pending.removeFirst()
                right.frame=right.pending.removeFirst()
                updateAssistance(left)
                updateAssistance(right)
                return true
            }
            // With ordered timestamps, the earlier head cannot match a later frame.
            (if (skew<0) left else right).pending.removeFirst()
        }
        return false
    }

    fun configure(mode: Int, flip: Boolean, rotation: Int, w: Int, h: Int, bufferWidth: Int=w, bufferHeight: Int=h) = call {
        previewWidth=bufferWidth; previewHeight=bufferHeight
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
    fun assists(follow: Boolean,maximum: Double?,stabilization: Boolean,zoom: Double=1.0,base: Double=1.0,owns: Boolean=false,sceneOnly: Boolean=false)=call {
        sceneHorizon=sceneOnly
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
        val time=source.frame.time/1e9
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
            val correction=source.horizon.correction(roll ?: if (sceneHorizon) source.horizon.sceneRoll(time) else null,time,maximum,stabilized)
            source.level=HorizonFraming(correction,HorizonFraming.scale(maximum,sw,sh))
            if ((sceneHorizon || roll!=null && abs(roll)<13*PI/180) && !analyzing && time-source.analyzed>=.24) {
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
                        handler.post {
                            analyzing=false
                            if (!closed && source in inputs && source.rotation==rotation) {
                                if (roll!=null) source.horizon.observe(found,roll,time)
                                else if (sceneHorizon) source.horizon.observeScene(found,time)
                            }
                        }
                    }
                }
            }
        } else source.level=null
    }

    fun primaries(index: Int, value: Int, warmGain: Float=1f,matchedExposure: Float?=null) = call {
        inputs.getOrNull(index)?.let { it.primaries=value; it.warmGain=warmGain; if (matchedExposure!=null) it.matchedExposure=matchedExposure }
        render()
    }

    fun snapshot(index: Int,w: Int,h: Int)=readFrame(index,w,h)
    fun previewFrame(w: Int,h: Int)=readFrame(-1,w,h)
    private fun readFrame(index: Int,w: Int,h: Int): Bitmap?=call {
        if (!hasFrame() || index>=inputs.size) return@call null
        if (frameSurface==EGL14.EGL_NO_SURFACE || frameWidth!=w || frameHeight!=h) {
            if (frameSurface!=EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,frameSurface)
            frameSurface=EGL14.EGL_NO_SURFACE
            val buffer=EGL14.eglCreatePbufferSurface(
                display,
                config,
                intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE),
                0,
            )
            check(buffer!=EGL14.EGL_NO_SURFACE)
            frameSurface=buffer;frameWidth=w;frameHeight=h
            framePixels=ByteBuffer.allocateDirect(w*h*4)
        }
        try {
            draw(frameSurface, w, h, 0, index,analysis=true)
            framePixels.clear()
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, framePixels)
            CameraReadback.bitmap(framePixels,w,h)
        } finally {
            EGL14.eglMakeCurrent(display, window, window, context)
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
    fun previewSourcePoint(x: Double, y: Double): CameraPoint? = call {
        val source = inputs.singleOrNull() ?: return@call null
        if (source.frame.time == Long.MIN_VALUE || x !in 0.0..1.0 || y !in 0.0..1.0) return@call null
        val rotation = CameraOrientation.recording(source.sensor, displayRotation, source.front)
        val point = projection(0, RectF(0f, 0f, 1f, 1f), source.front && mirrorPreview).source(x, y)
        CameraFraming.oriented(point, -rotation)
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
            nextFrame = Long.MIN_VALUE
        }
    }

    private fun regions(): List<RectF> {
        if (inputs.size==1 || layout==4) return listOf(RectF(0f,0f,1f,1f))
        return geometry.regions(layout,width.toDouble(),height.toDouble()).map {
            RectF((it.left/width).toFloat(),(it.top/height).toFloat(),(it.right/width).toFloat(),(it.bottom/height).toFloat())
        }
    }

    @Volatile var previewCovered=false
    private fun hasFrame()=inputs.isNotEmpty() && inputs.all { it.frame.time!=Long.MIN_VALUE }

    private fun render() {
        // The effect frame covers this surface; camera inputs and encoder draws still advance.
        if (previewCovered || !hasFrame()) return
        draw(window, previewWidth, previewHeight, 0)
    }

    @Volatile var analysisFrame: ((Long,()->Bitmap)->Unit)?=null
    private val readback=CameraReadback()
    private fun draw(target: EGLSurface, w: Int, h: Int, hint: Int, snapshot: Int = -1,analysis: Boolean=false) {
        check(EGL14.eglMakeCurrent(display, target, target, context))
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val program=if (inputs.size==2 && !planar) {
            if (retainedProgram==0) retainedProgram=compositionProgram(retained=true)
            retainedProgram
        } else program
        GLES20.glUseProgram(program)
        if (!hdr) GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"exposureMap"),1)
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
            if (hdr) {
                GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"warmGain"),source.warmGain)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"matchedExposure"),source.matchedExposure)
            } else {
                val strength = if (snapshot < 0) softwareHdr[source.id] ?: 0f else 0f
                val map = if (strength > 0f && !exposureMapFailed) source.exposureHistory?.texture ?: 0 else 0
                GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"softwareHdr"),strength)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"useExposureMap"),if (map == 0) 0f else 1f)
                if (map != 0) {
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,map)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                }
            }
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
            val frame=source.frame
            val base = frame.transform.copyOf()
            if (frame.reverseX) {
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
                        android.hardware.DataSpace.getRange(frame.dataSpace) ==
                            android.hardware.DataSpace.RANGE_FULL
                )
                    1f
                else 0f,
            )
            if (source.pixels!=null) source.pixels.bind(program,frame.slot) else {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                if (frame.texture.name!=0) GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,frame.texture.name)
                else GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, source.texture)
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

    internal fun inputDataSpaces(): List<Int> = call { inputs.map { it.frame.dataSpace } }

    fun clearInputs() = call {
        frameCopy?.close();frameCopy=null
        inputs.forEach {
            it.image?.setOnFrameAvailableListener(null)
            it.surface.release()
            it.image?.release();it.pixels?.close()
            it.exposureHistory?.close()
            it.frames.forEach { frame -> frame.texture.close() }
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
            clearInputs()
            exposureMap?.close()
            GLES20.glDeleteProgram(retainedProgram)
            GLES20.glDeleteProgram(program)
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            if (encoder != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, encoder)
            if (frameSurface!=EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,frameSurface)
            EGL14.eglDestroySurface(display, window)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            preview.release()
        }
        analysis.shutdown()
        thread.quitSafely()
    }
}
