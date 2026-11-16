package org.myrec.app

import kotlin.math.*

object HdrColorModelTests {
    fun run(test: (String,()->Unit)->Unit) {
        test("temperature and tint have an exact neutral matrix and bounded finite adaptation") {
            check(WhiteBalance.matrix(0.0,0.0).contentEquals(floatArrayOf(1f,0f,0f,0f,1f,0f,0f,0f,1f)))
            for (w in -10..10) for (t in -10..10) check(WhiteBalance.matrix(w/10.0,t/10.0).all { it.isFinite() && abs(it)<4 })
            check(runCatching { WhiteBalance.matrix(Double.NaN,0.0) }.isFailure)
            check(runCatching { WhiteBalance.matrix(0.0,1.01) }.isFailure)
        }
        test("the 4000 K Planckian white agrees with CIE chromaticity and tint moves off the locus") {
            val point=WhiteBalance.white(4000.0,0.0);val sum=point.sum()
            check(abs(point[0]/sum-.3805)<.0002 && abs(point[1]/sum-.3767)<.0002)
            val a=WhiteBalance.white(6500.0,-100.0);val b=WhiteBalance.white(6500.0,100.0)
            check(a[2]>b[2] && a.all { it.isFinite() } && b.all { it.isFinite() })
        }
        test("Bradford adaptation mixes channels and remains linear above reference white") {
            val matrix=WhiteBalance.matrix(.6,-.4)
            check(abs(matrix[1])>.001f && abs(matrix[2])>.001f)
            fun apply(v: FloatArray)=FloatArray(3) { row -> (0..2).sumOf { column -> (matrix[row*3+column]*v[column]).toDouble() }.toFloat() }
            val a=apply(floatArrayOf(.2f,.3f,.4f));val b=apply(floatArrayOf(2f,3f,4f))
            check((0..2).all { abs(b[it]-a[it]*10)<.00001 && b[it]>1 })
        }
        test("local recovery animation preserves old grading poses and fits the new bounded endpoint") {
            val effect=Effect(kind="Color LUTs",values=mutableMapOf("source" to "Apple Log","localRecovery" to "0"))
            val a=GradeModel.pose(effect);val b=a+("localRecovery" to 1.0)
            val clip=Clip(path="log.mov",sourceIn=10.0,sourceOut=14.0,audio=false)
            effect.motion=EffectMotion(a,b,duration=4.0).anchored(clip,0.0)
            check(effect.motion!!.valid(effect) && effect.sampled(12.0).number("localRecovery") in .5f.. .51f)
            val old=a-"localRecovery";check(EffectMotion(old,duration=4.0).valid(effect))
            check(!EffectMotion(old,b,duration=4.0).valid(effect))
            check(!EffectMotion(a,b+("localRecovery" to 1.01),duration=4.0).valid(effect))
        }
    }
}
