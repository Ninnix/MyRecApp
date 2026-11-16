package org.myrec.app

import android.graphics.*
import kotlin.math.*

object FrameEffects {
    val kinds=setOf("Blur","Letterbox","Widescreen 1953")
    private fun slider(id: String,title: String,min: Double,max: Double,value: Double)=
        FilmColor.Control(id,title,ParameterKind.Slider(min,max,.01),EffectValue.Number(value))
    private fun pick(id: String,title: String,values: List<String>,value: String)=
        FilmColor.Control(id,title,ParameterKind.Picker(values),EffectValue.Choice(value))
    val definitions=mapOf(
        "Blur" to FilmColor.Definition(-1,"Blur the whole picture. Use Auto Background to keep people sharp. Radius is relative to 1080p, so preview and export have the same softness.",listOf(slider("radius","Blur radius",0.0,40.0,12.0))),
        "Letterbox" to FilmColor.Definition(-1,"Bar size controls the top and bottom margins. Intensity blends the bars with the picture.",listOf(slider("margin","Bar size",0.0,.4,.12))),
        "Widescreen 1953" to FilmColor.Definition(-1,"Choose the frame ratio and Black or Blur fill bars. Flares spread bright highlights horizontally; the bars stay clean above the picture effects.",listOf(
            pick("ratio","Ratio",listOf("2.39:1","2.20:1","1.85:1","1.33:1"),"2.39:1"),
            pick("bars","Bars",listOf("Black","Blur fill"),"Black"),
            slider("flares","Flares",0.0,1.0,.4),
            pick("flareColor","Flare color",listOf("Blue","Amber","Neutral"),"Blue")))
    )
    fun modern(fx: Effect)=fx.kind in kinds && fx.parameters.isNotEmpty()
    fun parameters(kind: String)=(listOf(EffectParameter("intensity",ParameterKind.Slider(0.0,1.0,.01),EffectValue.Number(1.0)))+
        definitions.getValue(kind).controls.map { EffectParameter(it.id,it.kind,it.initial) }).toMutableList()
    fun choice(fx: Effect,key: String)=(fx.parameter(key)?.value as? EffectValue.Choice)?.value
    fun upgrade(fx: Effect) {
        val strength=fx.number("strength",1f).coerceIn(0f,1f)
        val ratio=fx.number("ratio",2.39f)
        fx.values.clear();fx.parameters.clear();fx.parameters.addAll(parameters(fx.kind));fx.setNumber("intensity",strength)
        if (fx.kind=="Widescreen 1953") {
            val ratios=listOf(2.39f,2.20f,1.85f,1.33f)
            val index=ratios.indices.minBy { abs(ratios[it]-ratio) }
            fx.parameter("ratio")!!.value=EffectValue.Choice(listOf("2.39:1","2.20:1","1.85:1","1.33:1")[index])
        }
    }
    fun correct(bitmap: Bitmap,fx: Effect,strength: Float) {
        if (fx.kind=="Blur") Native.frameBlur(bitmap,strength,fx.number("radius",12f)*min(bitmap.width,bitmap.height)/1080)
    }
    fun decorate(bitmap: Bitmap,fx: Effect,strength: Float) {
        when (fx.kind) {
            "Letterbox" -> Native.legacyVisual(bitmap,0,if (modern(fx)) strength else 1f,
                if (modern(fx)) fx.number("margin",.12f) else fx.number("strength",.12f).coerceIn(0f,.4f))
            "Widescreen 1953" -> Native.widescreenFrame(bitmap,strength,
                listOf("2.39:1","2.20:1","1.85:1","1.33:1").indexOf(choice(fx,"ratio")).coerceAtLeast(0),
                choice(fx,"bars")=="Blur fill",fx.number("flares",.4f),
                listOf("Blue","Amber","Neutral").indexOf(choice(fx,"flareColor")).coerceAtLeast(0))
        }
    }
    fun liveGeometry(bitmap: Bitmap,fx: Effect,strength: Float) {
        if (strength<=0) return
        val matrix=Matrix()
        when (fx.kind) {
            "Zoom" -> { val zoom=1+(fx.number("zoom",1f)-1)*strength;matrix.setScale(zoom,zoom,bitmap.width/2f,bitmap.height/2f) }
            "Rotation" -> matrix.setRotate(fx.number("rotation")*strength,bitmap.width/2f,bitmap.height/2f)
            "Mirror" -> matrix.setScale(if (fx.number("vertical")==1f) 1f else -1f,if (fx.number("vertical")==1f) -1f else 1f,bitmap.width/2f,bitmap.height/2f)
            else -> return
        }
        if (matrix.isIdentity) return
        val source=bitmap.copy(bitmap.config!!,false)
        try {
            bitmap.eraseColor(Color.BLACK)
            Canvas(bitmap).drawBitmap(source,matrix,Paint(Paint.FILTER_BITMAP_FLAG))
            if (fx.kind=="Mirror" && strength<1) Native.workingAverage(bitmap,source,1-strength)
        } finally { source.recycle() }
    }
    val editorOnly=setOf("Night fix","Freeze Frame","Step Printing","Freeze","Strobe","Echo")
}
