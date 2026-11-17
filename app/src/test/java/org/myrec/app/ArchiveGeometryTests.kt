package org.myrec.app

import kotlin.math.*

object ArchiveGeometryTests {
    fun run(test: (String,()->Unit)->Unit) {
        fun near(a: Double,b: Double) { check(abs(a-b)<.00001) { "$a != $b" } }
        test("source crops clamp their rotated footprint and map the selected center to the canvas") {
            for (angle in listOf(-135.0,-37.0,0.0,42.0,90.0,175.0)) for (zoom in listOf(1.0,2.0,7.0)) {
                val area=ArchiveGeometry.cropRect(900.0,1600.0,1920.0,1080.0,0.0,1.0,zoom,angle)
                val fx=Effect(kind="Crop",values=mutableMapOf("geometry" to "source","x" to "0","y" to "1","zoom" to "$zoom","rotation" to "$angle"))
                val matrix=ArchiveGeometry.crop(fx,900.0,1600.0,1920.0,1080.0,true,false)
                val center=matrix.point(area.x+area.width/2,area.y+area.height/2);near(center.first,960.0);near(center.second,540.0)
                val r=Math.toRadians(angle);val hx=(area.width*abs(cos(r))+area.height*abs(sin(r)))/2
                val hy=(area.width*abs(sin(r))+area.height*abs(cos(r)))/2
                check(area.x+area.width/2-hx>=-1e-7 && area.x+area.width/2+hx<=900.000001)
                check(area.y+area.height/2-hy>=-1e-7 && area.y+area.height/2+hy<=1600.000001)
            }
        }
        test("legacy framing converts without shifting pixels through all mirror axes and rotations") {
            for (horizontal in listOf(false,true)) for (vertical in listOf(false,true)) for (angle in listOf(-32.0,0.0,40.0,90.0)) {
                val fx=Effect(kind="Crop",values=mutableMapOf("x" to ".02","y" to "-.03","zoom" to "2.0","rotation" to "$angle"))
                val pose=ArchiveGeometry.legacyCrop(fx,1280.0,720.0,900.0,900.0,horizontal,vertical)
                val native=fx.copy(values=pose.mapValues { it.value.toString() }.toMutableMap())
                val matrix=ArchiveGeometry.crop(native,1280.0,720.0,900.0,900.0,horizontal,vertical)
                val r=Math.toRadians(angle);val c=cos(r);val s=sin(r)
                val scale=900.0/720*2*(abs(c)+abs(s))
                for (point in listOf(100.0 to 150.0,640.0 to 360.0,1100.0 to 620.0)) {
                    val x=(point.first-640)*if (horizontal) -1 else 1;val y=(point.second-360)*if (vertical) -1 else 1
                    val actual=matrix.point(point.first,point.second)
                    near(actual.first,450+18+scale*(c*x-s*y));near(actual.second,450-27+scale*(s*x+c*y))
                }
            }
            check(runCatching { ArchiveGeometry.legacyCrop(Effect(kind="Crop",values=mutableMapOf("x" to "1")),100.0,100.0,100.0,100.0,false,false) }.isFailure)
        }
        test("native placement edge crops clip without rescaling or moving the completed picture") {
            val fx=Effect(kind="Placement",values=mutableMapOf("geometry" to "canvas","x" to ".2","y" to ".1","w" to ".4","h" to ".7","reframe" to "0"))
            val (before,box)=ArchiveGeometry.placement(fx,1000.0,500.0)
            near(box.width,400.0);near(box.height,200.0);near(box.y,125.0)
            fx.values["left"]=".1";fx.values["right"]=".8";fx.values["top"]=".2"
            val (after,clipped)=ArchiveGeometry.placement(fx,1000.0,500.0)
            check(before==after);near(clipped.x,240.0);near(clipped.width,280.0);near(clipped.y,165.0)
            val center=after.point(500.0,250.0);near(center.first,400.0);near(center.second,225.0)
        }
        test("image centers keep aspect and source motion remains valid through save and sampling") {
            val fx=Effect(kind="Image",values=mutableMapOf("geometry" to "center","x" to ".2","y" to ".7","size" to ".3","opacity" to ".4","path" to "logo.png"))
            val box=ArchiveGeometry.image(fx,400.0,200.0,1000.0,500.0)
            near(box.x,50.0);near(box.y,275.0);near(box.width,300.0);near(box.height,150.0)
            val a=mapOf("x" to .2,"y" to .7,"size" to .3,"opacity" to .4)
            fx.motion=EffectMotion(a,a+mapOf("x" to .8,"size" to .5),duration=2.0,clock=listOf(EffectTimePoint(10.0,0.0),EffectTimePoint(12.0,2.0)))
            check(fx.motion!!.valid(fx));check(fx.sampled(11.0).number("x") in .49f.. .52f)
            check(!ArchiveGeometry.valid(fx.copy(values=fx.values.toMutableMap().apply { put("opacity","NaN") })))
        }
        test("source crop dragging accounts for rotation zoom and output mirrors") {
            val fx=Effect(kind="Crop",values=mutableMapOf("geometry" to "source","x" to ".5","y" to ".5","zoom" to "3","rotation" to "30"))
            val before=ArchiveGeometry.crop(fx,1280.0,720.0,640.0,360.0,true,false).point(640.0,360.0)
            ArchiveGeometry.panCrop(fx,1280.0,720.0,640.0,360.0,.03,-.02,true,false)
            val after=ArchiveGeometry.crop(fx,1280.0,720.0,640.0,360.0,true,false).point(640.0,360.0)
            near(after.first-before.first,19.2);near(after.second-before.second,-7.2)
            val a=mapOf("x" to .2,"y" to .5,"zoom" to 2.0,"rotation" to 10.0)
            fx.values.putAll(a.mapValues { it.value.toString() });fx.motion=EffectMotion(a,a+("zoom" to 3.0),duration=2.0)
            val previous=fx.values.toMap();fx.values["x"]=".3";fx.values["zoom"]="3"
            ArchiveGeometry.editMotion(fx,previous)
            near(fx.motion!!.a.getValue("x"),.3);near(fx.motion!!.b.getValue("zoom"),4.5)
        }
        test("placement ownership survives cuts and copied layouts get independent owners through undo") {
            val a=Clip(path="a.mp4",sourceOut=2.0,audio=false);val b=Clip(path="b.mp4",sourceOut=2.0,audio=false,track=1)
            val members=listOf(a.id,b.id).sorted().joinToString(",")
            for (c in listOf(a,b)) c.effects.add(Effect(kind="Placement",values=mutableMapOf("geometry" to "canvas","members" to members,"owner" to c.id)))
            val edit=Editor(Project(clips=mutableListOf(a,b)));edit.cursor=1.0;edit.split()
            val owners=edit.project.active(1.5).map(ArchiveGeometry::owner).toSet()
            check(owners==setOf(a.id,b.id) && edit.project.active(1.5).all { ArchiveGeometry.matches(it.effects.single(),owners) })
            edit.range=1.0..2.0;edit.copySelection();edit.range=null;edit.cursor=2.0;edit.paste()
            val copied=edit.project.active(2.5);val next=copied.map(ArchiveGeometry::owner).toSet()
            check(next.size==2 && next.intersect(owners).isEmpty() && copied.all { ArchiveGeometry.matches(it.effects.single(),next) })
            edit.undo();near(edit.project.duration,2.0);edit.redo()
            check(edit.project.active(2.5).map(ArchiveGeometry::owner).toSet()==next)
        }
    }
}
