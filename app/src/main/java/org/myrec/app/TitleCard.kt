package org.myrec.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

internal object TitleCard {
    val label = mapOf("align" to "Left","tracking" to ".09","color" to "#000000",
        "shadow" to "false","plate" to "One box","backing" to "#ffffff","padding" to ".32","radius" to ".27")
    val headline = mapOf("face" to "archivoblack","weight" to "400","align" to "Left",
        "tracking" to "-.045","shadow" to "false")
    val dimensions = mapOf("borderWidth" to .017f,"padding" to .42f,"radius" to .29f,"spacing" to .08f)
    const val fill = "#8b08090b"
    const val border = "#48ffffff"
    val looks=linkedMapOf(
        "Graphite" to emptyMap(),
        "Editorial" to mapOf("label.face" to "ibmplexmono","label.weight" to "500","label.color" to "#962c26",
            "label.plate" to "None","headline.face" to "sourceserif4","headline.weight" to "600","headline.color" to "#221d1a",
            "labelScale" to ".42","card.fill" to "#f6f7f3ea","card.border" to "#51463c","card.borderWidth" to ".022",
            "card.padding" to ".46","card.radius" to ".04","card.spacing" to ".12"),
        "Broadcast" to mapOf("label.face" to "barlowcondensed","label.color" to "#111111","label.backing" to "#ffd60a",
            "label.padding" to ".20","label.radius" to ".06","headline.face" to "barlowcondensed","headline.weight" to "700",
            "labelScale" to ".44","card.fill" to "#ec0b0f14","card.borderWidth" to "0","card.padding" to ".36","card.radius" to ".08","card.spacing" to ".12"),
        "Notebook" to mapOf("label.face" to "caveat","label.color" to "#ffe066","label.plate" to "None",
            "headline.face" to "fraunces","headline.weight" to "600","headline.italic" to "true","headline.tracking" to "-.01",
            "labelScale" to ".62","card.fill" to "#e814213d","card.borderWidth" to "0","card.padding" to ".44","card.radius" to ".30","card.spacing" to ".06"),
        "Terminal" to mapOf("label.face" to "ibmplexmono","label.weight" to "500","label.color" to "#61e3a5","label.plate" to "None",
            "headline.face" to "ibmplexmono","headline.weight" to "700","headline.color" to "#e6edf3","headline.tracking" to "-.025",
            "labelScale" to ".41","headlineScale" to ".94","card.fill" to "#f00d1517","card.border" to "#61e3a5",
            "card.borderWidth" to ".025","card.padding" to ".40","card.radius" to ".12","card.spacing" to ".12"),
        "Poster" to mapOf("label.face" to "barlowcondensed","label.padding" to ".18","label.radius" to "0",
            "headline.plate" to "One box","headline.backing" to "#b7251f","headline.padding" to ".15","headline.radius" to "0",
            "labelScale" to ".44","card.fill" to "#00000000","card.borderWidth" to "0","card.padding" to ".12","card.radius" to "0","card.spacing" to ".03"),
    )

    fun appearance(key: String) = key.startsWith("label.") || key.startsWith("headline.") ||
        key in listOf("labelScale","headlineScale","card.fill","card.border") ||
        key.startsWith("card.") && key.substringAfter('.') in dimensions

    fun applyLook(fx: Effect,look: Map<String,String>) {
        fx.values.keys.removeAll(::appearance)
        fx.values.putAll(look)
    }

    fun enabled(fx: Effect) = fx.values["grouped"]=="true"

    fun valid(fx: Effect): Boolean {
        fun bounded(key: String,min: Double,max: Double) =
            fx.values[key]?.let { it.toDoubleOrNull()?.let { value -> value.isFinite() && value in min..max }==true } ?: true
        return bounded("labelScale",.2,1.0) && bounded("headlineScale",.5,1.8) &&
            dimensions.keys.all { bounded("card.$it",0.0,if (it=="borderWidth") .1 else 1.0) } &&
            listOf("label","headline").all { part ->
                bounded("card.$part.x",-1.0,1.0) && bounded("card.$part.y",-1.0,1.0) &&
                    listOf("padding","radius","shadowBlur","borderWidth").all { bounded("$part.$it",0.0,1.0) } &&
                    listOf("tracking","lineSpacing","shadowOffset").all { bounded("$part.$it",-1.0,1.0) }
            }
    }

    fun style(fx: Effect,part: String): Map<String,String> =
        TitleStyle.defaults+(if (part=="label") label else headline)+
            fx.values.filterKeys { it.startsWith("$part.") }.mapKeys { it.key.substringAfter('.') }

    fun raster(context: Context,fx: Effect,size: Float,width: Int,height: Int): Bitmap {
        val pad=ceil(size*fx.number("card.padding",.42f))
        fun line(part: String,words: String?,scale: Float): Pair<Bitmap,String>? {
            if (words.isNullOrBlank()) return null
            val style=style(fx,part)
            return TitleStyle.raster(context,Effect(kind="Text",values=(style+("text" to words)).toMutableMap()),
                size*scale,(width-2*pad).toInt().coerceAtLeast(1)) to style.getValue("align")
        }
        val label=line("label",fx.values["label"],fx.number("labelScale",.36f))
        val headline=line("headline",fx.values["text"],fx.number("headlineScale",1f))
        try {
            val gap=if (label!=null && headline!=null) ceil(size*fx.number("card.spacing",.08f)) else 0f
            val content=max(label?.first?.width ?: 0,headline?.first?.width ?: 0)
            val w=(2*pad+content).coerceAtLeast(1f)
            val h=(2*pad+(label?.first?.height ?: 0)+(headline?.first?.height ?: 0)+gap).coerceAtLeast(1f)
            val scale=min(1f,min(width/w,height/h))
            val image=Bitmap.createBitmap(ceil(w*scale).toInt().coerceAtLeast(1),ceil(h*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
            val canvas=Canvas(image)
            canvas.scale(scale,scale)
            val radius=min(size*fx.number("card.radius",.29f),min(w,h)/2)
            val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            paint.color=TitleStyle.color(fx,"card.fill",fill)
            canvas.drawRoundRect(0f,0f,w,h,radius,radius,paint)
            val stroke=size*fx.number("card.borderWidth",.017f)
            if (stroke>0) {
                val inset=min(stroke,min(w,h))/2
                val inner=max(0f,radius-inset)
                paint.color=TitleStyle.color(fx,"card.border",border)
                paint.strokeWidth=inset*2
                paint.style=Paint.Style.STROKE
                canvas.drawRoundRect(inset,inset,w-inset,h-inset,inner,inner,paint)
                paint.style=Paint.Style.FILL
            }
            paint.alpha=255
            fun place(part: String,line: Pair<Bitmap,String>?,y: Float) {
                val (bitmap,align)=line ?: return
                val slack=content-bitmap.width
                val x=pad+when (align) { "Left" -> 0f; "Right" -> slack.toFloat(); else -> slack/2f }
                fun offset(key: String,start: Float,room: Float): Float {
                    val delta=fx.number("card.$part.$key").coerceIn(-1f,1f)
                    return start+delta*(if (delta<0) start else max(0f,room-start))
                }
                canvas.drawBitmap(bitmap,offset("x",x,w-bitmap.width),offset("y",y,h-bitmap.height),paint)
            }
            place("label",label,pad)
            place("headline",headline,pad+(label?.first?.height ?: 0)+gap)
            return image
        } finally { label?.first?.recycle();headline?.first?.recycle() }
    }
}
