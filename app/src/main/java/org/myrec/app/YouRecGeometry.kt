package org.myrec.app

import org.json.*
import kotlin.math.*

object YouRecGeometry {
    private fun angle(value: Double)=value-360*floor((value+180)/360)
    private fun n(fx: Effect,key: String,default: Double)=fx.values[key]?.toDoubleOrNull() ?: default
    private fun values(o: JSONObject,keys: List<String>)=keys.associateWith { o.getDouble(it).toString() }.toMutableMap()
    private fun motion(m: EffectMotion,convert: (Map<String,Double>)->Map<String,Double>)=m.copy(a=convert(m.a),b=convert(m.b))
    fun crop(fx: Effect,clip: Clip,canvas: Pair<Int,Int>,shape: (String)->Pair<Int,Int>): JSONObject {
        val mirror=clip.effects.lastOrNull { it.kind=="Mirror" && it.enabled }
        val pose=if (fx.values["geometry"]=="source") mapOf("x" to n(fx,"x",.5),"y" to n(fx,"y",.5),"zoom" to n(fx,"zoom",1.0),
            "rotation" to n(fx,"rotation",0.0)+90*n(fx,"quarter",0.0)) else {
            require(fx.motion==null) { "Legacy crop animation needs a MyRec ZIP" }
            val (w,h)=shape(clip.path)
            ArchiveGeometry.legacyCrop(fx,w.toDouble(),h.toDouble(),canvas.first.toDouble(),canvas.second.toDouble(),
                mirror?.number("horizontal",if (mirror.number("vertical")==1f) 0f else 1f)==1f,mirror?.number("vertical")==1f)
        }
        fun normalized(p: Map<String,Double>)=p+("rotation" to angle(p.getValue("rotation")))
        return JSONObject(normalized(pose)).put("id",YouRecDocument.uuid(fx.id)).apply { fx.motion?.let { m ->
            val quarter=n(fx,"quarter",0.0)*90
            put("motion",YouRecDocument.motion(motion(m) { normalized(it+("rotation" to it.getValue("rotation")+quarter)) }))
        } }
    }
    fun crop(o: JSONObject,duration: Double)=Effect(id=o.getString("id"),kind="Crop",to=duration,
        values=values(o,listOf("x","y","zoom")).apply { put("rotation",o.optDouble("rotation",0.0).toString());put("geometry","source") },motion=o.optJSONObject("motion")?.let(YouRecDocument::motion)).also {
        require(ArchiveGeometry.valid(it) && it.motion?.valid(it)!=false) { "Invalid native crop" }
    }
    fun image(fx: Effect,clip: Clip,canvas: Pair<Int,Int>,shape: (String)->Pair<Int,Int>): JSONObject {
        val cw=canvas.first.toDouble();val ch=canvas.second.toDouble();val edge=min(cw,ch)
        val (w,h)=shape(fx.values.getValue("path"));val centered=fx.values["geometry"]=="center"
        fun pose(p: Map<String,Double>): Map<String,Double> {
            val size=p["size"] ?: .3;val width=size*cw;val height=width*h/w
            val result=mapOf("x" to ((p["x"] ?: if (centered) .5 else .1)+if (centered) 0.0 else width/cw/2),
                "y" to (1-(p["y"] ?: if (centered) .5 else .1)-if (centered) 0.0 else height/ch/2),
                "width" to width/edge,"opacity" to (p["opacity"] ?: 1.0))
            require(result.getValue("x") in 0.0..1.0 && result.getValue("y") in 0.0..1.0 && result.getValue("width") in .03..3.0 && result.getValue("opacity") in 0.0..1.0) {
                "This image is outside the compatible geometry limits. Use a MyRec ZIP."
            }
            return result
        }
        return JSONObject(pose(fx.values.mapNotNull { (key,value) -> value.toDoubleOrNull()?.let { key to it } }.toMap()))
            .put("id",YouRecDocument.uuid(fx.id)).put("filename",fx.values.getValue("path"))
            .put("start",clip.sourceTime(clip.start+fx.from)).put("end",if (clip.frozen!=null) clip.sourceOut else clip.sourceTime(clip.start+min(fx.to,clip.duration)))
            .apply { fx.motion?.let { put("motion",YouRecDocument.motion(motion(it,::pose))) } }
    }
    fun image(o: JSONObject,clip: Clip,canvas: Pair<Int,Int>,paths: Map<String,String>): Effect {
        val scale=min(canvas.first,canvas.second).toDouble()/canvas.first
        fun pose(p: Map<String,Double>)=mapOf("x" to p.getValue("x"),"y" to 1-p.getValue("y"),"size" to p.getValue("width")*scale,"opacity" to p.getValue("opacity"))
        val p=pose(listOf("x","y","width","opacity").associateWith { o.getDouble(it) })
        return Effect(id=o.getString("id"),kind="Image",from=if (clip.frozen!=null) 0.0 else clip.timelineOffset(max(clip.sourceIn,o.getDouble("start"))),
            to=if (clip.frozen!=null) clip.duration else clip.timelineOffset(min(clip.sourceOut,o.getDouble("end"))),
            values=p.mapValues { it.value.toString() }.toMutableMap().apply { put("path",paths.getValue(o.getString("filename")));put("geometry","center") },
            motion=o.optJSONObject("motion")?.let { motion(YouRecDocument.motion(it),::pose) }).also { require(ArchiveGeometry.valid(it) && it.motion?.valid(it)!=false) }
    }
    fun placement(o: JSONObject,id: String,from: Double,to: Double): Effect {
        val framing=o.getJSONObject("framing");val edge=o.optJSONObject("edgeCrop") ?: JSONObject()
        fun pose(p: Map<String,Double>)=mapOf("x" to p.getValue("x")-p.getValue("width")/2,"y" to p.getValue("y")-p.getValue("height")/2,
            "w" to p.getValue("width"),"h" to p.getValue("height"),"frameX" to p.getValue("fx"),"frameY" to p.getValue("fy"),"contentZoom" to p.getValue("zoom"),
            "left" to p.getValue("left"),"right" to 1-p.getValue("right"),"top" to p.getValue("top"),"bottom" to 1-p.getValue("bottom"))
        val p=pose(mapOf("x" to o.getDouble("x"),"y" to o.getDouble("y"),"width" to o.getDouble("width"),"height" to o.getDouble("height"),
            "fx" to framing.getDouble("x"),"fy" to framing.getDouble("y"),"zoom" to framing.getDouble("zoom"),
            "left" to edge.optDouble("left",0.0),"right" to edge.optDouble("right",0.0),"top" to edge.optDouble("top",0.0),"bottom" to edge.optDouble("bottom",0.0)))
        return Effect(id=id,kind="Placement",from=from,to=to,values=p.mapValues { it.value.toString() }.toMutableMap().apply {
            put("geometry","canvas");put("order",o.getInt("order").toString());put("reframe",if (o.getBoolean("fill")) "1" else "0")
        },motion=o.optJSONObject("motion")?.let { motion(YouRecDocument.motion(it),::pose) }).also { require(ArchiveGeometry.valid(it) && it.motion?.valid(it)!=false) }
    }
    fun placement(fx: Effect,canvas: Pair<Int,Int>,order: Int): JSONObject {
        require(fx.values["geometry"]=="canvas" || fx.number("panX")==0f && fx.number("panY")==0f && fx.number("contentZoom",1f)==1f && fx.number("reframe")==0f) {
            "This legacy placement needs a MyRec ZIP to preserve its framing"
        }
        val fill=fx.number("reframe",if (fx.values["geometry"]=="canvas") 1f else 0f)==1f
        fun pose(p: Map<String,Double>): Map<String,Double> {
            val result=mapOf("x" to p.getValue("x")+p.getValue("w")/2,"y" to p.getValue("y")+p.getValue("h")/2,
            "width" to p.getValue("w"),"height" to p.getValue("h"),"fx" to p.getValue("frameX"),"fy" to p.getValue("frameY"),"zoom" to p.getValue("contentZoom"),
            "left" to p.getValue("left"),"right" to 1-p.getValue("right"),"top" to p.getValue("top"),"bottom" to 1-p.getValue("bottom"))
            require(result.getValue("x") in -2.0..3.0 && result.getValue("y") in -2.0..3.0 && result.getValue("width") in .01..3.0 && result.getValue("height") in .01..3.0 &&
                result.getValue("fx") in 0.0..1.0 && result.getValue("fy") in 0.0..1.0 && result.getValue("zoom") in 1.0..8.0 &&
                listOf("left","right","top","bottom").all { result.getValue(it)>=0 } && result.getValue("left")+result.getValue("right")<=.9800001 && result.getValue("top")+result.getValue("bottom")<=.9800001) {
                "This placement is outside the compatible geometry limits. Use a MyRec ZIP."
            }
            return result
        }
        val data=mapOf("x" to n(fx,"x",0.0),"y" to n(fx,"y",0.0),"w" to n(fx,"w",1.0),"h" to n(fx,"h",1.0),
            "frameX" to n(fx,"frameX",.5),"frameY" to n(fx,"frameY",.5),"contentZoom" to n(fx,"contentZoom",1.0),
            "left" to n(fx,"left",0.0),"right" to n(fx,"right",1.0),"top" to n(fx,"top",0.0),"bottom" to n(fx,"bottom",1.0))
        val p=pose(data).toMutableMap()
        if (n(fx,"panX",0.0)!=0.0 || n(fx,"panY",0.0)!=0.0) {
            require(fx.motion==null) { "Animated placement with a separate pan needs a MyRec ZIP" }
            val cw=canvas.first.toDouble();val ch=canvas.second.toDouble();val w=p.getValue("width")*cw;val h=p.getValue("height")*ch
            val crop=ArchiveGeometry.cropRect(cw,ch,if (fill) w else cw,if (fill) h else ch,p.getValue("fx"),p.getValue("fy"),p.getValue("zoom"),0.0)
            val scale=if (fill) max(w/crop.width,h/crop.height) else min(w/crop.width,h/crop.height)
            p["fx"]=(crop.x+crop.width/2-n(fx,"panX",0.0)*w/scale)/cw
            p["fy"]=(crop.y+crop.height/2-n(fx,"panY",0.0)*h/scale)/ch
            val checked=ArchiveGeometry.cropRect(cw,ch,if (fill) w else cw,if (fill) h else ch,p.getValue("fx"),p.getValue("fy"),p.getValue("zoom"),0.0)
            require(abs((checked.x+checked.width/2)/cw-p.getValue("fx"))<1e-6 && abs((checked.y+checked.height/2)/ch-p.getValue("fy"))<1e-6) { "This placement pans beyond its source. Use a MyRec ZIP." }
        }
        return JSONObject().put("x",p.getValue("x")).put("y",p.getValue("y")).put("width",p.getValue("width")).put("height",p.getValue("height"))
            .put("framing",JSONObject().put("x",p.getValue("fx")).put("y",p.getValue("fy")).put("zoom",p.getValue("zoom")))
            .put("edgeCrop",JSONObject().put("left",p.getValue("left")).put("right",p.getValue("right")).put("top",p.getValue("top")).put("bottom",p.getValue("bottom")))
            .put("fill",fill).put("order",order).apply { fx.motion?.let { put("motion",YouRecDocument.motion(motion(it,::pose))) } }
    }
}
