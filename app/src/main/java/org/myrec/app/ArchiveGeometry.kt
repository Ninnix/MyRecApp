package org.myrec.app

import kotlin.math.*

/* Native crops use an oriented source center. Placement crops the assembled
 * canvas instead; keeping the two spaces separate also keeps overlays attached. */
object ArchiveGeometry {
    fun owner(clip: Clip)=clip.effects.firstNotNullOfOrNull { if (it.kind=="Placement") it.values["owner"] else null } ?: clip.group
    fun owners(clips: List<Clip>)=clips.flatMap { c -> c.effects.filter { it.kind=="Placement" }.mapNotNull { it.values["owner"] } }.distinct()
    fun remap(clips: List<Clip>,owners: Map<String,String>) {
        for (clip in clips) {
        clip.effects.removeAll { fx -> fx.kind=="Placement" && fx.values["members"]?.split(',')?.any { it !in owners }==true }
        for (fx in clip.effects.filter { it.kind=="Placement" }) {
            fx.values["owner"]?.let { fx.values["owner"]=owners[it] ?: it }
            fx.values["members"]?.let { value -> fx.values["members"]=value.split(',').map { owners[it] ?: it }.sorted().joinToString(",") }
        }
        }
    }
    fun clean(project: Project) {
        if (project.clips.none { c -> c.effects.any { it.kind=="Placement" && it.values["members"]!=null } }) return
        val owners=project.clips.filter { it.video }.map(::owner).toSet()
        for (c in project.clips) c.effects.removeAll { fx -> fx.kind=="Placement" && fx.values["members"]?.split(',')?.any { it !in owners }==true }
    }
    fun matches(fx: Effect,members: Set<String>)=fx.values["members"]?.split(',')?.toSet()?.let { it==members } ?: true
    data class Rect(val x: Double,val y: Double,val width: Double,val height: Double)
    data class Transform(val a: Double,val b: Double,val c: Double,val d: Double,val x: Double,val y: Double) {
        fun point(px: Double,py: Double)=a*px+c*py+x to b*px+d*py+y
        fun values()=floatArrayOf(a.toFloat(),c.toFloat(),x.toFloat(),b.toFloat(),d.toFloat(),y.toFloat(),0f,0f,1f)
    }
    private fun number(fx: Effect,key: String,default: Double)=fx.values[key]?.toDoubleOrNull() ?: default
    fun cropRect(sw: Double,sh: Double,cw: Double,ch: Double,x: Double,y: Double,zoom: Double,angle: Double): Rect {
        require(listOf(sw,sh,cw,ch).all { it.isFinite() && it>0 })
        val r=Math.toRadians(angle);val c=abs(cos(r));val s=abs(sin(r))
        val scale=max((cw*c+ch*s)/sw,(cw*s+ch*c)/sh)*zoom.coerceIn(1.0,8.0)
        val w=cw/scale;val h=ch/scale;val hx=(w*c+h*s)/2;val hy=(w*s+h*c)/2
        return Rect((x*sw).coerceIn(hx, max(hx,sw-hx))-w/2,(y*sh).coerceIn(hy,max(hy,sh-hy))-h/2,w,h)
    }
    fun crop(fx: Effect,sw: Double,sh: Double,cw: Double,ch: Double,horizontal: Boolean=false,vertical: Boolean=false): Transform {
        val angle=Math.toRadians(number(fx,"rotation",0.0)+number(fx,"quarter",0.0)*90)
        val area=cropRect(sw,sh,cw,ch,number(fx,"x",.5),number(fx,"y",.5),number(fx,"zoom",1.0),Math.toDegrees(angle))
        val scale=cw/area.width;val mx=if (horizontal) -1 else 1;val my=if (vertical) -1 else 1
        val a=mx*scale*cos(angle);val b=my*scale*sin(angle);val c=-mx*scale*sin(angle);val d=my*scale*cos(angle)
        val cx=area.x+area.width/2;val cy=area.y+area.height/2
        return Transform(a,b,c,d,cw/2-a*cx-c*cy,ch/2-b*cx-d*cy)
    }
    fun legacyCrop(fx: Effect,sw: Double,sh: Double,cw: Double,ch: Double,horizontal: Boolean,vertical: Boolean): Map<String,Double> {
        val angle=number(fx,"rotation",0.0)+number(fx,"quarter",0.0)*90
        val r=Math.toRadians(angle);val c=cos(r);val s=sin(r)
        val scale=max(cw/sw,ch/sh)*number(fx,"zoom",1.0)*max(abs(c)+abs(s)*ch/cw,abs(c)+abs(s)*cw/ch)
        val mx=if (horizontal) -1 else 1;val my=if (vertical) -1 else 1
        val dx=number(fx,"x",0.0)*cw;val dy=number(fx,"y",0.0)*ch
        val cx=.5-mx*(c*dx+s*dy)/(scale*sw);val cy=.5-my*(-s*dx+c*dy)/(scale*sh)
        val rotation=angle*mx*my
        val base=max((cw*abs(c)+ch*abs(s))/sw,(cw*abs(s)+ch*abs(c))/sh)
        val zoom=scale/base
        val area=cropRect(sw,sh,cw,ch,cx,cy,zoom,rotation)
        require(cx in 0.0..1.0 && cy in 0.0..1.0 && zoom in 1.0-1e-6..8.0 &&
            abs((area.x+area.width/2)/sw-cx)<1e-6 && abs((area.y+area.height/2)/sh-cy)<1e-6) {
            "This crop exposes pixels outside the source. Use a MyRec ZIP to preserve its framing."
        }
        return mapOf("x" to cx,"y" to cy,"zoom" to max(1.0,zoom),"rotation" to rotation)
    }
    fun placement(fx: Effect,cw: Double,ch: Double): Pair<Transform,Rect> {
        val x=number(fx,"x",0.0)*cw;val y=number(fx,"y",0.0)*ch
        val w=number(fx,"w",1.0)*cw;val h=number(fx,"h",1.0)*ch;val fill=number(fx,"reframe",1.0)==1.0
        val crop=cropRect(cw,ch,if (fill) w else cw,if (fill) h else ch,number(fx,"frameX",.5),number(fx,"frameY",.5),number(fx,"contentZoom",1.0),0.0)
        val scale=if (fill) max(w/crop.width,h/crop.height) else min(w/crop.width,h/crop.height)
        val pw=if (fill) w else cw*min(w/cw,h/ch);val ph=if (fill) h else ch*min(w/cw,h/ch)
        val left=number(fx,"left",0.0);val right=number(fx,"right",1.0);val top=number(fx,"top",0.0);val bottom=number(fx,"bottom",1.0)
        return Transform(scale,0.0,0.0,scale,x+w/2-scale*(crop.x+crop.width/2)+number(fx,"panX",0.0)*w,
            y+h/2-scale*(crop.y+crop.height/2)+number(fx,"panY",0.0)*h) to
            Rect(x+w/2-pw/2+left*pw,y+h/2-ph/2+top*ph,(right-left)*pw,(bottom-top)*ph)
    }
    fun image(fx: Effect,iw: Double,ih: Double,cw: Double,ch: Double): Rect {
        val width=cw*number(fx,"size",.3);val height=width*ih/iw
        val centered=fx.values["geometry"]=="center"
        return Rect(cw*number(fx,"x",if (centered) .5 else .1)-if (centered) width/2 else 0.0,
            ch*number(fx,"y",if (centered) .5 else .1)-if (centered) height/2 else 0.0,width,height)
    }
    fun panCrop(fx: Effect,sw: Double,sh: Double,cw: Double,ch: Double,dx: Double,dy: Double,horizontal: Boolean=false,vertical: Boolean=false) {
        val matrix=crop(fx,sw,sh,cw,ch,horizontal,vertical);val det=matrix.a*matrix.d-matrix.b*matrix.c
        val area=cropRect(sw,sh,cw,ch,number(fx,"x",.5),number(fx,"y",.5),number(fx,"zoom",1.0),number(fx,"rotation",0.0)+90*number(fx,"quarter",0.0))
        val x=(area.x+area.width/2-(matrix.d*dx*cw-matrix.c*dy*ch)/det)/sw
        val y=(area.y+area.height/2-(-matrix.b*dx*cw+matrix.a*dy*ch)/det)/sh
        val next=cropRect(sw,sh,cw,ch,x,y,number(fx,"zoom",1.0),number(fx,"rotation",0.0)+90*number(fx,"quarter",0.0))
        fx.values["x"]=((next.x+next.width/2)/sw).toString();fx.values["y"]=((next.y+next.height/2)/sh).toString()
    }
    fun editMotion(fx: Effect,previous: Map<String,String>) {
        val motion=fx.motion ?: return
        val changes=motion.a.keys.mapNotNull { key ->
            val old=previous[key]?.toDoubleOrNull();val value=fx.values[key]?.toDoubleOrNull()
            if (old==null || value==null || old==value) null else key to (old to value)
        }.toMap()
        if (changes.isEmpty()) return
        fun pose(p: Map<String,Double>)=p.mapValues { (key,value) ->
            changes[key]?.let { (old,next) -> if (key in listOf("zoom","contentZoom") && old>0) value*next/old else value+next-old } ?: value
        }
        val next=motion.copy(a=pose(motion.a),b=pose(motion.b))
        if (next.valid(fx)) fx.motion=next else for (key in changes.keys) fx.values[key]=previous.getValue(key)
    }
    fun valid(fx: Effect): Boolean {
        if (fx.values["geometry"]!=mapOf("Crop" to "source","Placement" to "canvas","Image" to "center")[fx.kind]) return false
        fx.values["members"]?.let { value ->
            val members=value.split(',')
            if (fx.kind!="Placement" || members.size !in 2..100000 || members.toSet().size!=members.size || fx.values["owner"] !in members ||
                members.any { it.length>100 || it.isBlank() }) return false
        }
        val limits=when (fx.kind) {
            "Crop" -> mapOf("x" to 0.0..1.0,"y" to 0.0..1.0,"zoom" to 1.0..8.0,"rotation" to -360.0..360.0,"quarter" to -4.0..4.0)
            "Image" -> mapOf("x" to -2.0..3.0,"y" to -2.0..3.0,"size" to .00001..10.0,"opacity" to 0.0..1.0)
            "Placement" -> mapOf("x" to -4.0..4.0,"y" to -4.0..4.0,"w" to .01..3.0,"h" to .01..3.0,
                "frameX" to 0.0..1.0,"frameY" to 0.0..1.0,"contentZoom" to 1.0..8.0,"left" to 0.0..1.0,"right" to 0.0..1.0,"top" to 0.0..1.0,"bottom" to 0.0..1.0,
                "panX" to -2.0..2.0,"panY" to -2.0..2.0,"reframe" to 0.0..1.0,"order" to -100000.0..100000.0)
            else -> return false
        }
        return limits.all { (key,range) -> fx.values[key]?.let { it.toDoubleOrNull()?.let { n -> n.isFinite() && n in range }==true } ?: true } &&
            (fx.kind!="Placement" || number(fx,"right",1.0)-number(fx,"left",0.0)>=.02-1e-7 && number(fx,"bottom",1.0)-number(fx,"top",0.0)>=.02-1e-7)
    }
}
