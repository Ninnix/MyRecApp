package org.myrec.app

import android.graphics.*
import java.io.Closeable
import java.io.File
import kotlin.math.*

class BackgroundRenderer : Closeable {
    internal var frameTime: ((Double)->Unit)?=null
    private val mattes=linkedMapOf<String,BackgroundMatte>()
    private val live=mutableMapOf<String,List<BackgroundMatte>>()
    private val readers=Frames()
    private val duration=mutableMapOf<String,Double>()
    private val journals=linkedMapOf<String,BackgroundJournal.Reader>()

    fun journal(effect: Effect,source: File): BackgroundJournal.Reader? {
        val path=effect.values["matteJournal"] ?: return null
        val identity=SourceAudio.identity(source) { false }
        val file=File(path)
        val key="$path:${file.length()}:${file.lastModified()}:$identity"
        return journals.getOrPut(key) {
            val reader=BackgroundJournal.Reader(file)
            try {
                require(reader.sourceHash==null || reader.sourceHash==identity) { "Recorded masks belong to another source" }
                if (reader.sourceHash==null) reader.recoverClock(RecordedBackground.movieTimes(source))
            }
            catch (error: Throwable) { reader.close();throw error }
            while (journals.size>=8) journals.remove(journals.keys.first())?.close()
            reader
        }
    }

    fun signature(effect: Effect,prefix: List<Effect>)=BackgroundEffect.signature(effect,prefix) { SourceAudio.identity(File(it)) { false } }
    fun inputSignature(prefix: List<Effect>)=signature(dollySubject,prefix)

    private fun identity(effect: Effect,time: Double,key: String,width: Int,height: Int)=
        "$key:${effect.id}:${BackgroundEffect.options(effect).take(13)}:${BackgroundEffect.media(effect,"cleanPlate")}:${width}x${height}:$time"

    private fun matte(bitmap: Bitmap,effect: Effect,time: Double,key: String,plate: Bitmap?,personMask: FloatArray?=null,canonical: Boolean=false): BackgroundMatte {
        val options=BackgroundEffect.options(effect)
        val fingerprint=identity(effect,time,key,bitmap.width,bitmap.height)
        mattes[fingerprint]?.let { return it }
        val scale=min(1.0,512.0/max(bitmap.width,bitmap.height))
        val width=max(2,(bitmap.width*scale).roundToInt()); val height=max(2,(bitmap.height*scale).roundToInt())
        val small=Bitmap.createScaledBitmap(bitmap,width,height,true)
        try {
            val person=if (effect.kind=="Auto Background" || BackgroundEffect.toggle(effect,"personHint")) personMask ?: Person.mask(small) else null
            val data=Native.backgroundMatte(small,person,plate,options)
            val count=width*height
            val guide=Native.backgroundGuide(data.copyOfRange(count+3,data.size),width,height)
            fun values(v: FloatArray)=if (canonical) BackgroundJournal.canonical(v) else v
            return BackgroundMatte(width,height,values(data.copyOfRange(0,count+3)),values(guide),time,(width+3)/4,(height+3)/4,person?.let(::values)).also {
                if (mattes.size>=24) mattes.remove(mattes.keys.first())
                mattes[fingerprint]=it
            }
        } finally { if (small!==bitmap) small.recycle() }
    }

    fun render(bitmap: Bitmap,effect: Effect,time: Double,origin: Double,key: String,
        previous: List<Double>?=null,backgroundTime: Double=time,original: Effect=effect,
        journal: BackgroundJournal.Reader?=null,signature: String="",inputSignature: String="",capture: ((BackgroundMatte,FloatArray)->Unit)?=null,
        source: ((Double,Int,Int)->Bitmap?)?=null) {
        if (BackgroundEffect.choice(effect,"view")=="Original" || effect.number("intensity",1f)<=0) return
        val started=if (frameTime==null) 0L else System.nanoTime()
        val plate=BackgroundEffect.media(effect,"cleanPlate")?.let { PhotoSource.frame(it,bitmap.width,bitmap.height) }
        try {
            val track=effect.values["matteTrack"] ?: effect.id
            val saved=journal?.at(track,time)
            val exact=saved!=null && abs(saved.raw.time-time)<.00001
            if (exact && saved!!.signature==signature) {
                val background=background(bitmap,effect,max(0.0,backgroundTime-origin))
                try { Native.backgroundComposite(bitmap,background,plate,saved.final,saved.raw.width,saved.raw.height,BackgroundEffect.options(effect)) }
                finally { background.recycle() }
                return
            }
            val current=matte(bitmap,effect,time,key,plate,saved?.takeIf { exact && it.inputSignature==inputSignature }?.raw?.person,capture!=null)
            val history=mutableListOf(current)
            val capacity=if (effect.kind=="Chroma Key") 6 else 8
            if (previous!=null && source!=null) {
                val times=if (journal==null) previous else journal.preceding(track,time,capacity-1,origin)
                for (at in times.take(capacity-1)) {
                    val sampled=original.sampled(at)
                    val recorded=journal?.at(track,at)
                    if (recorded!=null && recorded.signature==signature) { history.add(recorded.raw);continue }
                    val cached=mattes[identity(sampled,at,key,bitmap.width,bitmap.height)]
                    if (cached!=null) history.add(cached)
                    else {
                        val decoded=source(at,bitmap.width,bitmap.height) ?: error("Cannot read background-mask source frame")
                        val frame=if (decoded.width==bitmap.width && decoded.height==bitmap.height) decoded
                            else Bitmap.createScaledBitmap(decoded,bitmap.width,bitmap.height,true).also { decoded.recycle() }
                        try { history.add(matte(frame,sampled,at,key,plate,recorded?.takeIf { it.inputSignature==inputSignature }?.raw?.person)) } finally { frame.recycle() }
                    }
                }
            } else {
                val signature="$key:$track:${BackgroundEffect.options(effect).take(13)}"
                val old=live[signature].orEmpty()
                if (old.firstOrNull()?.let { time-it.time in .000001..0.5 && it.width==current.width && it.height==current.height }==true) history.addAll(old.take(capacity-1))
                if (live.size>8) live.clear()
                live[signature]=history.toList()
            }
            val filtered=BackgroundTemporal.fast(history,effect.kind=="Chroma Key",effect.values["matteVersion"]=="2")
            val alpha=if (capture!=null) BackgroundJournal.canonical(filtered) else filtered
            capture?.invoke(current,alpha)
            val background=background(bitmap,effect,max(0.0,backgroundTime-origin))
            try { Native.backgroundComposite(bitmap,background,plate,alpha,current.width,current.height,BackgroundEffect.options(effect)) }
            finally { background.recycle() }
        } finally { plate?.recycle();if (started!=0L) frameTime?.invoke((System.nanoTime()-started)/1e6) }
    }

    private val dollySubject=Effect.create("Auto Background")
    fun subject(bitmap: Bitmap,time: Double,key: String,previous: List<Double> = emptyList(),source: ((Double,Int,Int)->Bitmap?)?=null): BackgroundMatte {
        val current=matte(bitmap,dollySubject,time,key,null)
        val history=mutableListOf(current)
        if (source!=null) for (at in previous.take(7)) {
            val input=source(at,bitmap.width,bitmap.height) ?: continue
            try { history.add(matte(input,dollySubject,at,key,null)) } finally { input.recycle() }
        }
        return current.copy(alpha=BackgroundTemporal.fast(history,false))
    }

    internal fun background(bitmap: Bitmap,effect: Effect,time: Double): Bitmap {
        val selected=BackgroundEffect.choice(effect,"background")
        val mode=if (selected in listOf("Image","Video") && BackgroundEffect.media(effect,"media")==null) "Solid" else selected
        val width=if (mode=="Solid") 1 else bitmap.width; val height=if (mode=="Solid") 1 else bitmap.height
        val result=if (bitmap.config==Bitmap.Config.RGBA_F16) WorkingColor.bitmap(width,height) else Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        try {
        val canvas=Canvas(result); canvas.drawColor(Color.BLACK)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        when (mode) {
            "Blur original" -> canvas.drawBitmap(bitmap,0f,0f,paint)
            "Image","Video" -> {
                val path=BackgroundEffect.media(effect,"media")
                check(path!=null && File(path).isFile) { "Choose background media before rendering" }
                val image=if (mode=="Image") PhotoSource.frame(path,width*2,height*2) else {
                    val length=duration.getOrPut(path) { ProjectStore.inspect(File(path)).sourceOut }
                    val phase=BackgroundEffect.loop(time,length)
                    val incoming=readers.frame(path,phase.incoming,width*2,height*2,"$path:background-head") ?: error("Cannot read background video")
                    phase.outgoing?.let { at ->
                        val outgoing=readers.frame(path,at,width*2,height*2,"$path:background-tail") ?: error("Cannot read background loop tail")
                        try { Native.workingAverage(outgoing,incoming,phase.blend.toFloat()); incoming.recycle(); outgoing }
                        catch (error: Throwable) { outgoing.recycle(); incoming.recycle(); throw error }
                    } ?: incoming
                }
                try {
                    val scale=(if (BackgroundEffect.choice(effect,"fit")=="Fit") min(width.toFloat()/image.width,height.toFloat()/image.height)
                        else max(width.toFloat()/image.width,height.toFloat()/image.height))*effect.number("backgroundZoom",1f)
                    val w=image.width*scale; val h=image.height*scale
                    val x=(width-w)*effect.number("backgroundX",.5f); val y=(height-h)*effect.number("backgroundY",.5f)
                    canvas.drawBitmap(image,null,RectF(x,y,x+w,y+h),paint)
                } finally { image.recycle() }
            }
            "Studio","Window" -> {
                paint.shader=RadialGradient(width*.3f,height*.3f,width*.8f,intArrayOf(Color.rgb(166,148,122),Color.rgb(20,26,36)),null,Shader.TileMode.CLAMP)
                canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint); paint.shader=null
                if (mode=="Window") {
                    paint.color=Color.rgb(158,186,212)
                    for (x in listOf(.64f,.80f)) canvas.drawRect(width*x,height*.17f,width*(x+.1f),height*.82f,paint)
                }
            }
            else -> {
                val c=effect.parameter("backgroundColor")?.value as? EffectValue.Color ?: EffectValue.Color(32/255.0,37/255.0,45/255.0)
                canvas.drawColor(Color.rgb((c.red*255).roundToInt(),(c.green*255).roundToInt(),(c.blue*255).roundToInt()))
            }
        }
        val extra=effect.number("backgroundBlur")
        val portrait=if (mode=="Blur original") if (effect.kind=="Auto Background") effect.number("portraitBlur",12f) else 20f else 0f
        val blur=sqrt(extra*extra+portrait*portrait)
        Native.backgroundBlur(result,blur*min(width,height)/1080,effect.number("backgroundBrightness"))
        return result
        } catch (error: Throwable) { result.recycle();throw error }
    }
    override fun close() { readers.close(); mattes.clear(); live.clear(); duration.clear();journals.values.forEach { it.close() };journals.clear() }
}
