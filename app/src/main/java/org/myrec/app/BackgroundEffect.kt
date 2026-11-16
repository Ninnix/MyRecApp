package org.myrec.app

import kotlin.math.*

object BackgroundEffect {
    val kinds=setOf("Chroma Key","Auto Background")
    data class Control(val id: String,val title: String,val kind: ParameterKind,val initial: EffectValue,val section: String)
    fun controls(kind: String): List<Control> {
        val result=mutableListOf<Control>()
        fun slider(id: String,title: String,min: Double,max: Double,value: Double,step: Double=.01,section: String="Background") {
            result.add(Control(id,title,ParameterKind.Slider(min,max,step),EffectValue.Number(value),section))
        }
        fun choice(id: String,title: String,choices: List<String>,value: String,section: String="Background") {
            result.add(Control(id,title,ParameterKind.Picker(choices),EffectValue.Choice(value),section))
        }
        fun toggle(id: String,title: String,value: Boolean,section: String="Background") {
            result.add(Control(id,title,ParameterKind.Toggle,EffectValue.Toggle(value),section))
        }
        fun color(id: String,title: String,r: Double,g: Double,b: Double,section: String="Background") {
            result.add(Control(id,title,ParameterKind.Color,EffectValue.Color(r,g,b),section))
        }
        if (kind=="Chroma Key") {
            color("screenColor","Manual screen color",0.0,1.0,0.0,"Screen")
            choice("screenType","Screen type",listOf("Auto","Green","Blue"),"Auto","Screen")
            toggle("autoScreen","Detect green / blue screen",true,"Screen")
            slider("strength","Strength",0.0,2.0,1.0,section="Screen")
            slider("balance","Balance",0.0,1.0,.5,section="Edges")
            slider("blackClip","Black clip",0.0,.5,.05,section="Edges")
            slider("whiteClip","White clip",.5,1.0,.9,section="Edges")
        }
        slider("softness","Edge softness",0.0,1.0,.3,section="Edges")
        slider("grow","Shrink / grow",-5.0,5.0,0.0,.1,"Edges")
        if (kind=="Chroma Key") slider("despill","Despill",0.0,1.0,.8,section="Edges")
        slider("wrap","Light wrap",0.0,1.0,.25,section="Edges")
        if (kind=="Chroma Key") {
            toggle("personHint","Use person hint",false,"Screen")
            result.add(Control("cleanPlate","Clean plate",ParameterKind.Media,EffectValue.Media(null),"Clean plate"))
        } else slider("portraitBlur","Portrait blur",0.0,40.0,12.0,1.0)
        choice("view","View",listOf("Result","Matte","Original"),"Result","Effect")
        choice("background","Background",listOf("Solid","Image","Video","Blur original","Studio","Window"),if (kind=="Auto Background") "Blur original" else "Solid")
        color("backgroundColor","Background color",32/255.0,37/255.0,45/255.0)
        result.add(Control("media","Background media",ParameterKind.Media,EffectValue.Media(null),"Background"))
        choice("fit","Framing",listOf("Fill","Fit"),"Fill")
        slider("backgroundX","Background X",0.0,1.0,.5)
        slider("backgroundY","Background Y",0.0,1.0,.5)
        slider("backgroundZoom","Background zoom",1.0,4.0,1.0,.05)
        slider("backgroundBlur","Background blur",0.0,20.0,0.0,1.0)
        slider("backgroundBrightness","Background brightness",-1.0,1.0,0.0)
        toggle("backgroundAudio","Background sound",false)
        toggle("matchColor","Match color",false)
        slider("matchStrength","Match strength",0.0,1.0,.3)
        return result
    }
    fun parameters(kind: String)=mutableListOf(EffectParameter("intensity",ParameterKind.Slider(0.0,1.0,.01),EffectValue.Number(1.0))).apply {
        addAll(controls(kind).map { EffectParameter(it.id,it.kind,it.initial) })
    }
    fun choice(effect: Effect,id: String)= (effect.parameter(id)?.value as? EffectValue.Choice)?.value ?: ""
    fun toggle(effect: Effect,id: String)= (effect.parameter(id)?.value as? EffectValue.Toggle)?.value==true
    fun media(effect: Effect,id: String)= (effect.parameter(id)?.value as? EffectValue.Media)?.path
    fun visible(control: Control,effect: Effect): Boolean {
        val mode=choice(effect,"background")
        return when (control.id) {
            "screenColor" -> !toggle(effect,"autoScreen") && media(effect,"cleanPlate")==null
            "autoScreen" -> media(effect,"cleanPlate")==null
            "screenType" -> toggle(effect,"autoScreen") || media(effect,"cleanPlate")!=null
            "backgroundColor" -> mode=="Solid"
            "media" -> mode=="Image" || mode=="Video"
            "fit","backgroundX","backgroundY","backgroundZoom" -> (mode=="Image" || mode=="Video") && media(effect,"media")!=null
            "backgroundAudio" -> mode=="Video" && media(effect,"media")!=null
            "backgroundBlur" -> mode!="Solid" && (mode!="Blur original" || effect.kind=="Chroma Key")
            "portraitBlur" -> mode=="Blur original"
            "matchColor" -> mode!="Blur original"
            "matchStrength" -> mode!="Blur original" && toggle(effect,"matchColor")
            else -> true
        }
    }
    fun acceptsScreen(color: EffectValue.Color): Boolean {
        fun linear(x: Double)=if (x<=.04045) x/12.92 else ((x+.055)/1.055).pow(2.4)
        val r=linear(color.red); val g=linear(color.green); val b=linear(color.blue)
        return g-max(r,b)>.02 || b-max(r,g)>.02
    }
    data class Phase(val incoming: Double,val outgoing: Double?,val blend: Double)
    fun loop(time: Double,duration: Double): Phase {
        require(time.isFinite() && duration.isFinite() && duration>.001)
        val overlap=min(.2,duration/2); val period=duration-overlap
        val t=max(0.0,time); val cycle=floor(t/period); val phase=t%period
        return Phase(phase,if (cycle>0 && phase<overlap) min(period+phase,Math.nextDown(duration)) else null,min(1.0,phase/overlap))
    }
    fun options(effect: Effect): FloatArray {
        val color=effect.parameter("screenColor")?.value as? EffectValue.Color ?: EffectValue.Color(0.0,1.0,0.0)
        return floatArrayOf(if (effect.kind=="Auto Background") 1f else 0f,
            listOf("Auto","Green","Blue").indexOf(choice(effect,"screenType")).coerceAtLeast(0).toFloat(),
            if (toggle(effect,"autoScreen")) 1f else 0f,effect.number("strength",1f),effect.number("balance",.5f),
            effect.number("blackClip",.05f),effect.number("whiteClip",.9f),effect.number("softness",.3f),effect.number("grow"),
            if (toggle(effect,"personHint")) 1f else 0f,color.red.toFloat(),color.green.toFloat(),color.blue.toFloat(),
            effect.number("intensity",1f),effect.number("despill",.8f),effect.number("wrap",.25f),
            if (toggle(effect,"matchColor")) 1f else 0f,effect.number("matchStrength",.3f),
            listOf("Result","Matte","Original").indexOf(choice(effect,"view")).coerceAtLeast(0).toFloat())
    }
}

/** Raw source observations, never a matte recursively smoothed by a previous seek. */
data class BackgroundMatte(val width: Int,val height: Int,val alpha: FloatArray,val guide: FloatArray,val time: Double,
    val guideWidth: Int=width,val guideHeight: Int=height)

object BackgroundTemporal {
    fun guide(values: FloatArray,width: Int,height: Int): FloatArray {
        val w=(width+3)/4; val h=(height+3)/4
        return FloatArray(w*h) { i ->
            var sum=0.0; var count=0
            for (y in i/w*4 until min(height,i/w*4+4)) for (x in i%w*4 until min(width,i%w*4+4)) {
                val v=values[y*width+x].toDouble()
                sum+=if (v<=.04045) v/12.92 else ((v+.055)/1.055).pow(2.4);count++
            }
            val v=sum/count
            (if (v<=.0031308) v*12.92 else 1.055*v.pow(1/2.4)-.055).toFloat()
        }
    }
    private fun sample(values: FloatArray,width: Int,height: Int,u: Float,v: Float): Float {
        val x=(u*width-.5f).coerceIn(0f,width-1f);val y=(v*height-.5f).coerceIn(0f,height-1f)
        val a=x.toInt();val b=y.toInt();val n=min(width-1,a+1);val m=min(height-1,b+1)
        val dx=x-a;val dy=y-b
        return (values[b*width+a]*(1-dx)+values[b*width+n]*dx)*(1-dy)+(values[m*width+a]*(1-dx)+values[m*width+n]*dx)*dy
    }
    private fun guide(frame: BackgroundMatte,u: Float,v: Float)=sample(frame.guide,frame.guideWidth,frame.guideHeight,u,v)
    fun cut(a: FloatArray,b: FloatArray): Boolean {
        require(a.size==b.size && a.isNotEmpty())
        val ha=IntArray(16); val hb=IntArray(16); var difference=0.0
        for (i in a.indices) {
            difference+=abs(a[i]-b[i]); ha[(a[i]*16).toInt().coerceIn(0,15)]++; hb[(b[i]*16).toInt().coerceIn(0,15)]++
        }
        val d=difference/a.size; val h=ha.indices.sumOf { abs(ha[it]-hb[it]) }.toDouble()/(2*a.size)
        return d>.20 || d>.08 && h>.30
    }
    fun apply(frames: List<BackgroundMatte>,key: Boolean): FloatArray {
        require(frames.isNotEmpty())
        val first=frames.first(); val count=min(if (key) 6 else 8,frames.size)
        val result=first.alpha.copyOf()
        val sampled=frames.take(count).map { frame -> FloatArray(32*18) { guide(frame,(it%32+.5f)/32,(it/32+.5f)/18) } }
        val cuts=BooleanArray(count-1) { cut(sampled[it],sampled[it+1]) }
        for (i in 0 until first.width*first.height) {
            val u=(i%first.width+.5f)/first.width;val v=(i/first.width+.5f)/first.height
            var remaining=1f; var sum=0f
            for (f in 0 until count-1) {
                var prior=if (cuts[f]) 0f else if (key) .25f else 1f/3
                if (key) {
                    val motion=abs(guide(frames[f],u,v)-guide(frames[f+1],u,v))
                    val t=((motion-.01f)/.01f).coerceIn(0f,1f)
                    prior*=1-t*t*(3-2*t)
                }
                val frame=frames[f]
                val alpha=if (frame.width==first.width && frame.height==first.height) frame.alpha[i] else sample(frame.alpha,frame.width,frame.height,u,v)
                sum+=remaining*(1-prior)*alpha; remaining*=prior
            }
            val oldest=frames[count-1]
            val alpha=if (oldest.width==first.width && oldest.height==first.height) oldest.alpha[i] else sample(oldest.alpha,oldest.width,oldest.height,u,v)
            result[i]=(sum+remaining*alpha).coerceIn(0f,1f)
        }
        return result
    }
}
