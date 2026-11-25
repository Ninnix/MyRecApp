package org.myrec.app

import kotlin.math.*

object CameraAssistanceTests {
    fun run(test: (String,()->Unit)->Unit) {
        fun near(a: Double,b: Double,tolerance: Double=1e-7) { check(abs(a-b)<tolerance) { "$a != $b" } }
        test("dual layout uses bounded reference dividers and canvas-aware inset shapes") {
            val l=CameraLayout()
            for ((w,h) in listOf(1920.0 to 1080.0,1080.0 to 1920.0)) {
                val r=l.regions(0,w,h)[1]
                near(r.width,min(w,h)*.32); near(r.width,r.height)
                near(r.center.x,w*.77)
                val circle=l.regions(5,w,h)[1]; near(circle.width,circle.height)
                l.insetX=1.0; l.insetY=1.0
                val edge=l.regions(0,w,h)[1]; check(edge.right<=w && edge.bottom<=h)
                l.insetX=.77; l.insetY=.28
            }
            l.vertical=1.0; l.horizontal=-1.0
            near(l.regions(1,100.0,100.0)[0].right,85.0)
            near(l.regions(2,100.0,100.0)[0].bottom,15.0)
            l.resize(0,2.0,1,1920.0,1080.0)
            near(l.regions(0,1920.0,1080.0)[1].width/l.regions(0,1920.0,1080.0)[1].height,2.0)
            near(l.regions(5,1920.0,1080.0)[1].width/l.regions(5,1920.0,1080.0)[1].height,1.0)
            l.reset(); check(l.framing.all { it==CameraFraming() }); near(l.vertical,.5)
        }
        test("diagonal selection and round inset hit testing share their rendered masks") {
            val l=CameraLayout()
            check(l.slot(3,20.0,20.0,100.0,100.0)==0)
            check(l.slot(3,80.0,80.0,100.0,100.0)==1)
            l.diagonalFlipped=true
            check(l.slot(3,80.0,20.0,100.0,100.0)==0)
            check(l.slot(3,20.0,80.0,100.0,100.0)==1)
            val r=l.regions(5,100.0,200.0)[1]
            check(l.slot(5,r.left+1,r.top+1,100.0,200.0)==0)
            check(l.slot(5,r.center.x,r.center.y,100.0,200.0)==1)
        }
        test("face selection holds identity through brief loss and clips partly visible faces") {
            val t=CameraFaceTarget()
            val near=CameraRect(.2,.2,.4,.5); val far=CameraRect(.65,.2,.95,.6)
            t.observe(listOf(near),0.0)
            t.observe(listOf(far,near.copy(left=.22,right=.42)),.1)
            check(t.current(.2)?.left==.22)
            t.observe(listOf(far),.3); check(t.current(.49)!=null && t.current(.51)==null)
            t.observe(listOf(far),1.0); check(t.current(1.1)==far)
            t.observe(listOf(CameraRect(-.1,.2,.1,.4)),2.0); near(t.current(2.0)!!.left,0.0)
            t.observe(listOf(CameraRect(Double.NaN,0.0,1.0,1.0)),3.0); check(t.current(3.0)==null)
        }
        test("face following obeys existing overscan, dead zone and bounded smooth motion") {
            val face=CameraRect(.75,.35,.95,.65)
            val full=CameraFraming()
            check(CameraFaceFraming.follow(face,1920.0,1080.0,1920.0,1080.0,full,.03)==full)
            var f=full.copy(zoom=2.0)
            val first=CameraFaceFraming.follow(face,1920.0,1080.0,1920.0,1080.0,f,.03)
            check(first.x>f.x && first.x-f.x<=.0451); near(first.y,.5)
            repeat(300) { f=CameraFaceFraming.follow(face,1920.0,1080.0,1920.0,1080.0,f,.03) }
            check(f.x in .95..1.0 && f.zoom==2.0)
            check(CameraFaceFraming.follow(null,1920.0,1080.0,1920.0,1080.0,f,.03)==f)
            check(CameraFaceFraming.follow(CameraRect(.48,.4,.52,.6),1920.0,1080.0,1920.0,1080.0,full.copy(zoom=2.0),.03).x==.5)
            var diagonal=CameraFraming(zoom=2.0)
            repeat(120) { diagonal=CameraFaceFraming.follow(CameraRect(.45,.45,.55,.55),1920.0,1080.0,1920.0,1080.0,diagonal,1.0/30,CameraPoint(.5,.25)) }
            check(.5*2-diagonal.y<.31) { "The face should lie inside its triangle" }
        }
        test("camera point rotation and preview mirroring round trip in all orientations") {
            val p=CameraPoint(.2,.7)
            for (r in listOf(0,90,180,270)) for (mirror in listOf(false,true)) {
                val q=CameraFraming.oriented(p,r,mirror)
                val back=CameraFraming.oriented(if (mirror) CameraPoint(1-q.x,q.y) else q,-r)
                near(p.x,back.x); near(p.y,back.y)
            }
        }
        test("framing guides invert the rendered mirror, crop and horizon without revealing edges") {
            for ((w,h) in listOf(1920.0 to 1080.0,1080.0 to 1920.0)) for (mirror in listOf(false,true)) for (angle in listOf(-9.0,0.0,9.0)) {
                val level=HorizonFraming(angle*PI/180,HorizonFraming.scale(9.0,w,h))
                for (frame in listOf(CameraFraming(),CameraFraming(.0,1.0,2.0),CameraFraming(1.0,.0,4.0))) {
                    val map=CameraProjection(w,h,w/2,h,frame,level,mirror)
                    for (x in listOf(0.0,.3,1.0)) for (y in listOf(0.0,.7,1.0)) {
                        val source=map.source(x,y); check(source.x in -1e-7..1.0000001 && source.y in -1e-7..1.0000001)
                        val back=map.screen(source); near(x,back.x); near(y,back.y)
                    }
                }
            }
            val angle=3*PI/180; val map=CameraProjection(1920.0,1080.0,1920.0,1080.0,CameraFraming(),HorizonFraming(angle,HorizonFraming.scale(9.0,1920.0,1080.0)))
            val a=map.source(.1,.5); val b=map.source(.9,.5)
            near(atan2((b.y-a.y)*1080,(b.x-a.x)*1920),angle)
        }
        test("horizon follows small roll smoothly and neutralizes ambiguous or absent gravity") {
            near(HorizonFraming.degrees(9.25),9.5); near(HorizonFraming.degrees(Double.NaN),9.0)
            near(HorizonFraming.scale(9.0,1920.0,1080.0),cos(PI/20)+1920.0/1080*sin(PI/20))
            near(HorizonEstimate.roll(sin(.1),-cos(.1),90.0)!!,-.1)
            check(HorizonEstimate.roll(0.0,0.0,90.0)==null)
            check(HorizonEstimate.roll(Double.NaN,-1.0,90.0)==null)
            val h=HorizonEstimate(); var angle=0.0
            repeat(100) { angle=h.correction(.08,it/30.0,9.0,false) }; near(angle,.08,1e-6)
            repeat(100) { angle=h.correction(PI/4,(100+it)/30.0,9.0,false) }; check(abs(angle)<1e-5)
            repeat(100) { angle=h.correction(null,(200+it)/30.0,9.0,false) }; check(abs(angle)<1e-5)
            repeat(100) { angle=h.correction(.4,(300+it)/30.0,.5,false); check(abs(angle)<=.5*PI/180) }
        }
        test("scene horizon needs a long coherent edge and cannot vote far from gravity") {
            val w=192; val h=128
            val slope=tan(3.0*PI/180)
            val luma=ByteArray(w*h) { i -> if (i/w>64+(i%w-w/2)*slope) 200.toByte() else 30 }
            val line=HorizonLine.estimate(luma,w,h) ?: error("No horizon")
            near(line,3*PI/180,.6*PI/180)
            check(HorizonLine.estimate(ByteArray(w*h),w,h)==null)
            val visual=HorizonEstimate()
            visual.observeScene(line,1.0); visual.observeScene(line,1.25)
            check(visual.sceneRoll(1.25)==null)
            visual.observeScene(line,1.5)
            near(visual.sceneRoll(1.6)!!,line)
            check(visual.sceneRoll(2.4)==null)
            visual.observeScene(-line,2.5)
            check(visual.sceneRoll(2.5)==null)
            val estimate=HorizonEstimate()
            repeat(60) { estimate.observe(20*PI/180,0.0,it/30.0) }
            near(estimate.correction(0.0,2.0,9.0,false),0.0)
        }
        test("horizon visual consensus refines gravity, holds briefly and fades without a jump") {
            val rad=PI/180; val estimate=HorizonEstimate()
            repeat(120) { i -> if (i%9==0) estimate.observe(4*rad,3*rad,i/30.0); estimate.correction(3*rad,i/30.0,10.0,false) }
            near(estimate.correction(3*rad,4.0,10.0,false)/rad,4.0,.1)
            estimate.observe(null,3*rad,4.01)
            check(estimate.correction(3*rad,4.02,10.0,false)>3.9*rad)
            repeat(100) { estimate.correction(-20*rad,4+(it+1)/30.0,3.0,true) }
            val limited=estimate.correction(-20*rad,7.4,3.0,true)
            check(limited>=-3*rad && limited< -2.95*rad)
            check(abs(estimate.correction(-20*rad,7.41,.5,true))<=.5*rad)
            repeat(100) { estimate.correction(null,7.4+(it+1)/30.0,3.0,false) }
            check(abs(estimate.correction(null,11.0,3.0,false))<.01*rad)
        }
    }
}
