package org.myrec.app

import kotlin.math.*

data class CameraPoint(val x: Double, val y: Double)
data class CameraRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width get()=right-left
    val height get()=bottom-top
    val center get()=CameraPoint((left+right)/2,(top+bottom)/2)
    fun contains(x: Double,y: Double)=x>=left && x<=right && y>=top && y<=bottom
}

data class CameraFraming(var x: Double=.5, var y: Double=.5, var zoom: Double=1.0) {
    fun constrain() {
        x=if (x.isFinite()) x.coerceIn(0.0,1.0) else .5
        y=if (y.isFinite()) y.coerceIn(0.0,1.0) else .5
        zoom=if (zoom.isFinite()) zoom.coerceIn(1.0,4.0) else 1.0
    }
    companion object {
        fun oriented(p: CameraPoint, degrees: Int, mirrored: Boolean=false): CameraPoint {
            val r=when ((degrees/90%4+4)%4) {
                1 -> CameraPoint(1-p.y,p.x)
                2 -> CameraPoint(1-p.x,1-p.y)
                3 -> CameraPoint(p.y,1-p.x)
                else -> p
            }
            return if (mirrored) CameraPoint(1-r.x,r.y) else r
        }
    }
}

/** Geometry is in top-left canvas coordinates, shared by rendering and controls. */
class CameraLayout {
    var vertical=.5
    var horizontal=.5
    var diagonalFlipped=false
    var insetX=.77
    var insetY=.28
    var insetSize=.32
    var insetAspect=1.0
    val framing=arrayOf(CameraFraming(),CameraFraming())

    fun copy()=CameraLayout().also {
        it.vertical=vertical; it.horizontal=horizontal; it.diagonalFlipped=diagonalFlipped
        it.insetX=insetX; it.insetY=insetY; it.insetSize=insetSize; it.insetAspect=insetAspect
        for (i in framing.indices) it.framing[i]=framing[i].copy()
    }

    fun constrain() {
        vertical=if (vertical.isFinite()) vertical.coerceIn(.15,.85) else .5
        horizontal=if (horizontal.isFinite()) horizontal.coerceIn(.15,.85) else .5
        insetX=if (insetX.isFinite()) insetX.coerceIn(0.0,1.0) else .77
        insetY=if (insetY.isFinite()) insetY.coerceIn(0.0,1.0) else .28
        if (!insetAspect.isFinite() || insetAspect<=0) insetAspect=1.0
        if (!insetSize.isFinite() || insetSize<=0) insetSize=.32
        val w=max(.18,insetSize*sqrt(insetAspect)); val h=max(.18,insetSize/sqrt(insetAspect))
        if (w.isFinite() && h.isFinite()) { insetSize=sqrt(w)*sqrt(h); insetAspect=w/h }
        else { insetSize=.32; insetAspect=1.0 }
        framing.forEach { it.constrain() }
    }

    fun reset() {
        vertical=.5; horizontal=.5; diagonalFlipped=false
        insetX=.77; insetY=.28; insetSize=.32; insetAspect=1.0
        framing.indices.forEach { framing[it]=CameraFraming() }
    }
    fun regions(mode: Int,width: Double,height: Double): List<CameraRect> {
        constrain()
        val full=CameraRect(0.0,0.0,width,height)
        if (width<=0 || height<=0 || !width.isFinite() || !height.isFinite()) return List(2) { CameraRect(0.0,0.0,0.0,0.0) }
        return when (mode) {
            1 -> listOf(CameraRect(0.0,0.0,width*vertical,height),CameraRect(width*vertical,0.0,width,height))
            2 -> listOf(CameraRect(0.0,0.0,width,height*horizontal),CameraRect(0.0,height*horizontal,width,height))
            0,5 -> {
                val unit=min(width,height)
                val aspect=if (mode==5) 1.0 else insetAspect
                val size=if (mode==5) min(1.0,insetSize) else insetSize
                val w=size*sqrt(aspect)*unit; val h=size/sqrt(aspect)*unit
                val fit=min(1.0,min(width/w,height/h))
                val x=(insetX*width-w*fit/2).coerceIn(0.0,max(0.0,width-w*fit))
                val y=(insetY*height-h*fit/2).coerceIn(0.0,max(0.0,height-h*fit))
                listOf(full,CameraRect(x,y,x+w*fit,y+h*fit))
            }
            else -> listOf(full,full)
        }
    }
    fun slot(mode: Int,x: Double,y: Double,width: Double,height: Double): Int {
        val regions=regions(mode,width,height)
        if (mode==3) return if (if (diagonalFlipped) y/height-x/width>0 else x/width+y/height>1) 1 else 0
        if (mode==5) {
            val r=regions[1]
            return if (hypot(x-r.center.x,y-r.center.y)<=r.width/2) 1 else 0
        }
        return if (regions[1].contains(x,y)) 1 else 0
    }
    fun resize(mode: Int,scale: Double,axis: Int,width: Double,height: Double) {
        if (!scale.isFinite() || scale<=0 || width<=0 || height<=0) return
        val r=regions(mode,width,height)[1]; val unit=min(width,height)
        var w=r.width/unit; var h=r.height/unit
        if (mode==5) { insetSize=(w*scale).coerceIn(.18,1.0) }
        else {
            if (axis==1) w=(w*scale).coerceIn(.18,width/unit)
            else if (axis==2) h=(h*scale).coerceIn(.18,height/unit)
            else { val factor=min(min(width/unit/w,height/unit/h),max(.18/min(w,h),scale)); w*=factor; h*=factor }
            insetSize=sqrt(w*h); insetAspect=w/h
        }
        insetX=r.center.x/width; insetY=r.center.y/height
    }
}

class CameraFaceTarget {
    var box: CameraRect?=null
        private set
    var seenAt=Double.NEGATIVE_INFINITY
        private set
    fun observe(faces: List<CameraRect>,time: Double) {
        val valid=faces.filter { listOf(it.left,it.top,it.right,it.bottom).all(Double::isFinite) && it.width>0 && it.height>0 }
            .map { CameraRect(max(0.0,it.left),max(0.0,it.top),min(1.0,it.right),min(1.0,it.bottom)) }.filter { it.width>0 && it.height>0 }
        val old=box
        val next=if (old!=null && time-seenAt<.8) valid.filter {
            hypot(it.center.x-old.center.x,it.center.y-old.center.y)<max(.12,min(.3,max(old.width,old.height))) && it.width/old.width>.45 && it.width/old.width<2.2
        }.minByOrNull { hypot(it.center.x-old.center.x,it.center.y-old.center.y) }
        else valid.maxByOrNull { it.width*it.height/(.25+hypot(it.center.x-.5,it.center.y-.5)) }
        if (next!=null) { box=next; seenAt=time }
    }
    fun current(time: Double)=if (time-seenAt<.4) box else null
}

object CameraFaceFraming {
    fun follow(face: CameraRect?,sourceWidth: Double,sourceHeight: Double,regionWidth: Double,regionHeight: Double,
        current: CameraFraming,elapsed: Double,target: CameraPoint=CameraPoint(.5,.5)): CameraFraming {
        if (face==null || !elapsed.isFinite() || elapsed<=0) return current
        val scale=max(regionWidth/sourceWidth,regionHeight/sourceHeight)*current.zoom
        val dt=min(.1,elapsed); val alpha=1-exp(-dt/.22)
        fun move(value: Double,center: Double,full: Double,visible: Double,target: Double): Double {
            val spare=full-visible
            if (spare<=.5) return value
            val error=center*full-spare*value-visible*target
            val dead=visible*.045
            if (abs(error)<=dead) return value
            val distance=error-sign(error)*dead
            val step=(distance*alpha).coerceIn(-visible*dt*1.5,visible*dt*1.5)
            return (value+step/spare).coerceIn(0.0,1.0)
        }
        return current.copy(x=move(current.x,face.center.x,sourceWidth*scale,regionWidth,target.x),
            y=move(current.y,face.center.y,sourceHeight*scale,regionHeight,target.y))
    }
}

/** Output-to-source map and its inverse include exactly the crop used by the GPU. */
class CameraProjection(sourceWidth: Double,sourceHeight: Double,regionWidth: Double,regionHeight: Double,
    framing: CameraFraming,level: HorizonFraming?=null,private val mirror: Boolean=false,levelCenter: Boolean=false) {
    private val aspect=sourceWidth/sourceHeight
    private val wanted=regionWidth/regionHeight
    val cropX=min(1.0,wanted/aspect)/framing.zoom
    val cropY=min(1.0,aspect/wanted)/framing.zoom
    private val c=cos(level?.radians ?: 0.0)
    private val s=sin(level?.radians ?: 0.0)
    private val scale=level?.magnification ?: 1.0
    private val extentX=(abs(c)*cropX+abs(s)*cropY/aspect)/scale
    private val extentY=(abs(s)*cropX*aspect+abs(c)*cropY)/scale
    private val centerX=cropX/2+(1-cropX)*framing.x
    private val centerY=cropY/2+(1-cropY)*framing.y
    private val cx=if (levelCenter) .5+(c*(centerX-.5)-s*(centerY-.5)/aspect)/scale else centerX.coerceIn(extentX/2,1-extentX/2)
    private val cy=if (levelCenter) .5+(s*(centerX-.5)*aspect+c*(centerY-.5))/scale else centerY.coerceIn(extentY/2,1-extentY/2)
    fun source(x: Double,y: Double): CameraPoint {
        val dx=(x-.5)*cropX*if (mirror) -1 else 1; val dy=(y-.5)*cropY
        return CameraPoint(cx+(c*dx-s*dy/aspect)/scale,cy+(s*dx*aspect+c*dy)/scale)
    }
    fun screen(point: CameraPoint): CameraPoint {
        val dx=(point.x-cx)*scale; val dy=(point.y-cy)*scale
        return CameraPoint(.5+(c*dx+s*dy/aspect)/cropX*if (mirror) -1 else 1,.5+(-s*dx*aspect+c*dy)/cropY)
    }
}

data class HorizonFraming(val radians: Double,val magnification: Double) {
    companion object {
        fun degrees(value: Double)=if (value.isFinite()) (floor(value*2+.5)/2).coerceIn(.5,10.0) else 9.0
        fun scale(maxDegrees: Double,width: Double,height: Double): Double {
            if (width<=0 || height<=0) return 1.0
            val a=degrees(maxDegrees)*PI/180
            return cos(a)+max(width/height,height/width)*sin(a)
        }
    }
}

class HorizonEstimate {
    private var angle=0.0
    private var lastTime: Double?=null
    private var lastGravityTime=Double.NEGATIVE_INFINITY
    private var lastGravity=0.0
    private var visualBias=0.0
    private var lastVisualTime=Double.NEGATIVE_INFINITY
    private var lastAgreement=Double.NEGATIVE_INFINITY
    private var agreedBias=0.0
    private var candidate=0.0
    private var agreements=0
    companion object {
        fun roll(x: Double,y: Double,rotation: Double,mirrored: Boolean=false): Double? {
            if (!x.isFinite() || !y.isFinite() || hypot(x,y)<=.24) return null
            val raw=atan2(-x,-y)+(rotation-90)*PI/180
            val residual=atan2(sin(4*raw),cos(4*raw))/4
            return if (mirrored) -residual else residual
        }
    }
    fun observe(line: Double?,gravity: Double,time: Double) {
        if (line==null || abs(line-gravity)>3*PI/180) { agreements=0; return }
        val difference=line-gravity
        if (time-lastVisualTime>.9 || abs(difference-candidate)>PI/180) { agreements=0; candidate=difference }
        agreements++; candidate+=.4*(difference-candidate); lastVisualTime=time
        if (agreements>=3) { agreedBias=(candidate*180/PI).coerceIn(-1.5,1.5)*PI/180; lastAgreement=time }
    }
    fun correction(gravity: Double?,time: Double,maxDegrees: Double,stabilized: Boolean): Double {
        val dt=(time-(lastTime ?: (time-1.0/30))).coerceIn(0.0,.1); lastTime=time
        if (gravity!=null) { lastGravity=gravity; lastGravityTime=time }
        val recent=time-lastAgreement<.9
        visualBias+=((if (recent) agreedBias else 0.0)-visualBias)*(1-exp(-dt/if (recent) .35 else .9))
        val raw=if (time-lastGravityTime<.25) lastGravity+visualBias else 0.0
        val limit=HorizonFraming.degrees(maxDegrees)*PI/180; val knee=limit*.85
        val magnitude=abs(raw)
        val bounded=if (magnitude<=knee) magnitude else knee+(limit-knee)*tanh((magnitude-knee)/(limit-knee))
        val ambiguity=((abs(lastGravity)*180/PI-35)/10).coerceIn(0.0,1.0)
        val target=sign(raw)*bounded*(1-ambiguity*ambiguity*(3-2*ambiguity))
        val tau=if (gravity==null) .35 else if (stabilized) .22 else .075
        angle=(angle+(target-angle)*(1-exp(-dt/tau))).coerceIn(-limit,limit)
        return angle
    }
}

object HorizonLine {
    fun estimate(luma: ByteArray,width: Int,height: Int): Double? {
        if (width<32 || height<24 || luma.size!=width*height) return null
        val xs=ArrayList<Int>(); val ys=ArrayList<Int>(); val strengths=ArrayList<Int>()
        for (x in 2 until width-2 step 2) for (y in 2 until height-2) {
            val i=y*width+x
            val v=(luma[i+width].toInt() and 255)-(luma[i-width].toInt() and 255)
            if (abs(v)>=14 && abs((luma[i+1].toInt() and 255)-(luma[i-1].toInt() and 255))<abs(v)) {
                xs.add(x); ys.add(y); strengths.add(v.coerceIn(-64,64))
            }
        }
        if (xs.size<width/3) return null
        val pad=width/4+2; val rows=height+2*pad
        val scores=DoubleArray(49)
        for (halfDegrees in -24..24) {
            val slope=tan(halfDegrees*PI/360); val votes=IntArray(rows*8)
            for (i in xs.indices) {
                val row=pad+ys[i]-(sign((xs[i]-width/2)*slope)*floor(abs((xs[i]-width/2)*slope)+.5)).toInt()
                votes[row*8+xs[i]*8/width]+=strengths[i]
            }
            for (row in pad/2 until rows-pad/2) {
                var sum=0
                for (band in 0..7) sum+=votes[row*8+band].coerceIn(-width*2,width*2)
                scores[halfDegrees+24]=max(scores[halfDegrees+24],abs(sum).toDouble())
            }
        }
        val peak=scores.max()
        if (peak<=width*11) return null
        val top=scores.indices.filter { scores[it]>=peak*.97 }
        if (top.last()-top.first()>6) return null
        return ((top.first()+top.last())/2.0-24)*PI/360
    }
}
