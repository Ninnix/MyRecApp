package org.myrec.app

import android.app.Instrumentation
import android.content.pm.ActivityInfo
import android.graphics.*
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlin.math.*

object KaleidoscopeTests {
    fun run(instrumentation: Instrumentation, a: MainActivity, onMain: (() -> Unit) -> Unit,
        test: (String, () -> Unit) -> Unit) {
        test("kaleidoscope matches reference wedge source rotation reflection and linear sampling") {
            fun linear(v: Double) = if (v <= .04045) v / 12.92 else ((v + .055) / 1.055).pow(2.4)
            fun encoded(v: Double) = ((if (v <= .0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - .055) * 255).roundToInt().coerceIn(0, 255)
            for ((width, height) in listOf(27 to 17, 17 to 27, 1 to 7, 7 to 1)) {
                val input = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                for (y in 0 until height) for (x in 0 until width)
                    input.setPixel(x, y, Color.rgb((x * 19 + y * 11) % 256, (x * 3 + y * 23) % 256, (x * 13 + y * 7) % 256))
                fun value(x: Int, y: Int, c: Int) = linear(((input.getPixel(x.coerceIn(0, width-1), y.coerceIn(0, height-1)) ushr (16 - c * 8)) and 255) / 255.0)
                fun sample(u: Double, v: Double, c: Int): Double {
                    val x = (u * width - .5).coerceIn(0.0, width - 1.0)
                    val y = (v * height - .5).coerceIn(0.0, height - 1.0)
                    val ix = floor(x).toInt(); val iy = floor(y).toInt()
                    val dx = x - ix; val dy = y - iy
                    return (1-dy) * ((1-dx) * value(ix,iy,c) + dx * value(ix+1,iy,c)) +
                        dy * ((1-dx) * value(ix,iy+1,c) + dx * value(ix+1,iy+1,c))
                }
                for (count in 2..9) for (strength in listOf(0f, .35f, 1f)) {
                    val fx = Effect(kind = "Kaleidoscope").apply {
                        setNumber("divisions", count.toFloat()); setNumber("centerX", .37f); setNumber("centerY", .63f)
                        setNumber("splitAngle", 31f); setNumber("sourceAngle", -47f)
                        setNumber("sourcePanX", .73f); setNumber("sourcePanY", -.51f)
                    }
                    val output = input.copy(Bitmap.Config.ARGB_8888, true)
                    Effects.correct(output, fx, strength)
                    for (y in 0 until height) for (x in 0 until width) {
                        val cx = fx.number("centerX").toDouble(); val cy = fx.number("centerY").toDouble()
                        var u = (x + .5) / width; var v = (y + .5) / height
                        val dx = (u-cx) * width; val dy = (v-cy) * height
                        val axis = (31.0-90) * PI / 180; val turn = 2*PI; val span = turn/count
                        if (dx*dx+dy*dy >= 1e-8) {
                            val relative = (atan2(dy,dx)-axis)/turn
                            val angle = (relative-floor(relative))*turn
                            val sector = min(count-1, (angle/span).toInt())
                            if (sector != 0) {
                                var local = angle-sector*span
                                if (count%2==0 && sector%2!=0) local=span-local
                                u=cx+hypot(dx,dy)*cos(axis+local)/width
                                v=cy+hypot(dx,dy)*sin(axis+local)/height
                            }
                        }
                        val rx=(u-cx)*width; val ry=(v-cy)*height; val angle=47.0*PI/180
                        u=cx+(cos(angle)*rx-sin(angle)*ry)/width-fx.number("sourcePanX")
                        v=cy+(sin(angle)*rx+cos(angle)*ry)/height-fx.number("sourcePanY")
                        fun reflect(t: Double)=1-abs(2*(t*.5-floor(t*.5))-1)
                        if (u !in 0.0..1.0 || v !in 0.0..1.0) { u=reflect(u); v=reflect(v) }
                        for (c in 0..2) {
                            val original=value(x,y,c); val expected=encoded(original+strength*(sample(u,v,c)-original))
                            val actual=(output.getPixel(x,y) ushr (16-c*8)) and 255
                            check(abs(expected-actual)<=1) { "Kaleidoscope $count $width/$height $x,$y channel $c: $actual != $expected" }
                        }
                    }
                    output.recycle()
                }
                input.recycle()
            }
        }
        test("default kaleidoscope keeps the right half and mirrors it across a vertical split") {
            val input=Bitmap.createBitmap(31,19,Bitmap.Config.ARGB_8888)
            for(y in 0 until 19)for(x in 0 until 31)input.setPixel(x,y,Color.rgb(x*7,y*11,(x+y)*4))
            val output=input.copy(Bitmap.Config.ARGB_8888,true)
            val fx=Effect(kind="Kaleidoscope")
            check(fx.number("divisions")==2f)
            Effects.correct(output,fx,1f)
            for(y in 0 until 19)for(x in 0 until 31)check(output.getPixel(x,y)==input.getPixel(max(x,30-x),y))
            output.recycle(); input.recycle()
        }
        test("kaleidoscope guide center pan and twist update independent controls") {
            val fx=Effect(kind="Kaleidoscope")
            val guide=KaleidoscopeGuide({key,value->fx.setNumber(key,value)}, {})
            val image=Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888)
            guide.draw(Canvas(image),RectF(0f,0f,400f,300f),1f,fx)
            val now=SystemClock.uptimeMillis()
            fun touch(action:Int,vararg points:Pair<Float,Float>) {
                val properties=Array(points.size){i->MotionEvent.PointerProperties().apply{id=i;toolType=MotionEvent.TOOL_TYPE_FINGER}}
                val coords=Array(points.size){i->MotionEvent.PointerCoords().apply{x=points[i].first;y=points[i].second;pressure=1f;size=1f}}
                val e=MotionEvent.obtain(now,now+10,action,points.size,properties,coords,0,0,1f,1f,0,0,0,0)
                try{check(guide.touch(e,fx.copyEffect()))}finally{e.recycle()}
            }
            touch(MotionEvent.ACTION_DOWN,200f to 150f)
            touch(MotionEvent.ACTION_MOVE,240f to 180f)
            touch(MotionEvent.ACTION_UP,240f to 180f)
            check(abs(fx.number("centerX")-.6f)<.00001 && abs(fx.number("centerY")-.6f)<.00001)
            touch(MotionEvent.ACTION_DOWN,350f to 250f)
            touch(MotionEvent.ACTION_MOVE,370f to 250f)
            touch(MotionEvent.ACTION_UP,370f to 250f)
            check(abs(fx.number("sourcePanX")-.05f)<.00001 && fx.number("centerX")==.6f)
            touch(MotionEvent.ACTION_DOWN,290f to 180f)
            touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),290f to 180f,350f to 180f)
            touch(MotionEvent.ACTION_MOVE,320f to 150f,320f to 210f)
            touch(MotionEvent.ACTION_UP,320f to 150f)
            check(abs(abs(fx.number("sourceAngle"))-90f)<.0001)
            image.recycle()
        }
        fun find(root:View,name:String):View? {
            if(root.contentDescription?.toString()==name || root is TextView && root.text.toString()==name)return root
            if(root is ViewGroup)for(i in 0 until root.childCount)find(root.getChildAt(i),name)?.let{return it}
            return null
        }
        test("kaleidoscope inspector exposes divisions fine adjustments guide actions reset and undo") {
            for(orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                onMain{
                    a.panels.close();a.requestedOrientation=orientation
                    a.editor.clearSelection();a.editor.cursor=0.0
                    a.editor.project.clips.forEach{it.effects.clear()}
                    a.editor.applyEffect(Effect(kind="Kaleidoscope"))
                }
                Thread.sleep(550)
                onMain{a.panels.videoEffects(a.editor.project.clips.first().effects.single())}
                Thread.sleep(200)
                onMain{
                    check(find(a.root,"Divisions")!=null)
                    find(a.root,"9")!!.performClick()
                    find(a.root,"› Fine adjustments")!!.performClick()
                    check(find(a.root,"Source rotation")!=null && find(a.root,"Split rotation")!=null)
                    val preview=find(a.root,"Tool preview")!!
                    check(preview.performAccessibilityAction(0x02000206,null))
                    check(preview.performAccessibilityAction(0x02000202,null))
                    find(a.root,"Apply")!!.performClick()
                    val fx=a.editor.project.clips.first().effects.single()
                    check(fx.number("divisions")==9f && abs(fx.number("sourcePanX")-.01f)<.00001)
                    check(abs(fx.number("centerX")-.51f)<.00001)
                    a.editor.undo()
                    check(a.editor.project.clips.first().effects.single().number("divisions")==2f)
                }
            }
        }
    }
}
