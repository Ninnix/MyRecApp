package org.myrec.app

import kotlin.math.*

object WhiteBalance {
    // Robertson isotherms in CIE 1960 UCS cover the reference's temperature range.
    private val locus=arrayOf(
        doubleArrayOf(100.0,.19032,.29326,-.47888),doubleArrayOf(125.0,.19462,.30141,-.58204),
        doubleArrayOf(150.0,.19962,.30921,-.70471),doubleArrayOf(175.0,.20525,.31647,-.84901),
        doubleArrayOf(200.0,.21142,.32312,-1.0182),doubleArrayOf(225.0,.21807,.32909,-1.2168),
        doubleArrayOf(250.0,.22511,.33439,-1.4512))
    private val bradford=doubleArrayOf(.8951,.2664,-.1614,-.7502,1.7135,.0367,.0389,-.0685,1.0296)
    private val rgbXYZ=doubleArrayOf(.4123907993,.3575843394,.1804807884,.2126390059,.7151686788,.0721923154,.0193308187,.1191947798,.9505321522)
    private fun multiply(a: DoubleArray,b: DoubleArray)=DoubleArray(9) { i -> (0..2).sumOf { k -> a[i/3*3+k]*b[k*3+i%3] } }
    private fun vector(m: DoubleArray,v: DoubleArray)=DoubleArray(3) { i -> (0..2).sumOf { k -> m[i*3+k]*v[k] } }
    private fun inverse(a: DoubleArray): DoubleArray {
        val b=doubleArrayOf(a[4]*a[8]-a[5]*a[7],a[2]*a[7]-a[1]*a[8],a[1]*a[5]-a[2]*a[4],
            a[5]*a[6]-a[3]*a[8],a[0]*a[8]-a[2]*a[6],a[2]*a[3]-a[0]*a[5],
            a[3]*a[7]-a[4]*a[6],a[1]*a[6]-a[0]*a[7],a[0]*a[4]-a[1]*a[3])
        val determinant=a[0]*b[0]+a[1]*b[3]+a[2]*b[6]
        return b.map { it/determinant }.toDoubleArray()
    }
    fun white(temperature: Double,tint: Double): DoubleArray {
        require(temperature in 4000.0..9000.0 && tint in -100.0..100.0)
        val mired=1e6/temperature
        val index=(0 until locus.lastIndex).firstOrNull { mired<=locus[it+1][0] } ?: locus.lastIndex-1
        val a=locus[index];val b=locus[index+1];val f=(mired-a[0])/(b[0]-a[0])
        val u=a[1]+(b[1]-a[1])*f;val v=a[2]+(b[2]-a[2])*f
        val du=(1-f)/hypot(1.0,a[3])+f/hypot(1.0,b[3])
        val dv=(1-f)*a[3]/hypot(1.0,a[3])+f*b[3]/hypot(1.0,b[3])
        val length=hypot(du,dv);val offset=-tint/3000
        val x=u+du*offset/length;val y=v+dv*offset/length
        return doubleArrayOf(1.5*x/y,1.0,(4-x-10*y)/(2*y))
    }
    // Adapt source 6500 K to the reference's target temperature/tint in linear RGB.
    fun matrix(warmth: Double,tint: Double): FloatArray {
        require(warmth.isFinite() && warmth in -1.0..1.0 && tint.isFinite() && tint in -1.0..1.0)
        if (warmth==0.0 && tint==0.0) return floatArrayOf(1f,0f,0f,0f,1f,0f,0f,0f,1f)
        val source=vector(bradford,white(6500.0,0.0));val target=vector(bradford,white(6500+warmth*2500,tint*100))
        val scale=DoubleArray(9) { i -> if (i/3==i%3) target[i/3]/source[i/3] else 0.0 }
        return multiply(inverse(rgbXYZ),multiply(inverse(bradford),multiply(scale,multiply(bradford,rgbXYZ)))).map { it.toFloat() }.toFloatArray()
    }
}
