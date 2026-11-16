package org.myrec.app

import android.app.Dialog
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.*
import kotlin.math.*

/** The lamp is opaque; its separate opacity control belongs to the recorder. */
class FrontLightColorPicker(a: MainActivity, initial: Int, scale: Float,
    title: String="Front light color", private val change: (Int) -> Unit) : Dialog(a) {
    private val u=InspectorUi(a,min(scale,a.root.height/520f))
    private val body=u.column().apply {
        background=u.shape(Color.rgb(28,28,30),18f)
        setPadding(u.px(16f),u.px(8f),u.px(16f),u.px(16f))
    }
    private val tabs=u.column()
    private val content=u.column()
    private val swatch=View(a)
    private val hex=EditText(a)
    private var color=initial or Color.BLACK
    private var tab="Grid"
    private var updating=false
    private val cells=mutableListOf<Pair<Int,View>>()
    private val sliders=mutableListOf<SeekBar>()
    private var spectrum: View?=null

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val handle=View(a).apply { background=u.shape(Color.rgb(90,90,94),2f) }
        body.addView(handle,LinearLayout.LayoutParams(u.px(28f),u.px(3f)).apply { gravity=Gravity.CENTER; bottomMargin=u.px(6f) })
        val header=u.row()
        header.addView(u.text(title,14f,bold=true),LinearLayout.LayoutParams(0,u.px(44f),1f))
        header.addView(u.text("×",22f,Color.LTGRAY).apply {
            gravity=Gravity.CENTER; background=u.shape(Color.rgb(58,58,60),20f)
            contentDescription="Close ${title.lowercase()}"; isFocusable=true; setOnClickListener { dismiss() }
        },LinearLayout.LayoutParams(u.px(34f),u.px(34f)))
        u.add(body,header); u.gap(body,6f); u.add(body,tabs); u.gap(body,12f); u.add(body,content)
        u.divider(body)
        val values=u.row()
        swatch.background=u.shape(color,10f,Color.GRAY)
        swatch.contentDescription="Selected ${title.lowercase()}"
        values.addView(swatch,LinearLayout.LayoutParams(u.px(42f),u.px(42f)).apply { rightMargin=u.px(12f) })
        values.addView(u.text("Hex color",11f),LinearLayout.LayoutParams(0,u.px(44f),1f))
        hex.apply {
            setTextColor(Color.WHITE); setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,u.px(12f).toFloat())
            background=u.shape(Color.rgb(44,44,46),8f); setPadding(u.px(10f),0,u.px(10f),0)
            setSingleLine(); setSelectAllOnFocus(true); contentDescription="Hex color"
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            imeOptions=EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _,action,_ -> if (action==EditorInfo.IME_ACTION_DONE) { applyHex(); clearFocus(); true } else false }
            onFocusChangeListener=View.OnFocusChangeListener { _,focused -> if (!focused) applyHex() }
        }
        values.addView(hex,LinearLayout.LayoutParams(u.px(100f),u.px(40f)))
        u.add(body,values)
        setContentView(ScrollView(a).apply { isFillViewport=false; addView(body) })
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes=attributes.apply { dimAmount=.35f }
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        render(); update()
    }

    override fun onStart() {
        super.onStart()
        resize()
    }

    fun resize() {
        val bounds=ownerActivity?.window?.decorView ?: u.a.window.decorView
        val portrait=bounds.height>=bounds.width
        val width=min(bounds.width-u.px(12f),u.px(350f))
        val height=min(bounds.height-u.px(20f),u.px(510f))
        body.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.AT_MOST))
        window?.apply {
            setGravity(if (portrait) Gravity.BOTTOM else Gravity.CENTER)
            setLayout(width,min(height,body.measuredHeight))
        }
    }

    private fun applyHex() {
        val text=hex.text.toString().trim().removePrefix("#")
        val value=if (text.length==6 && text.all { it.digitToIntOrNull(16)!=null }) text.toIntOrNull(16) else null
        if (value==null) hex.error="Use #RRGGBB"
        else { hex.error=null; select(value or Color.BLACK) }
    }

    private fun select(value: Int) { color=value or Color.BLACK; update(); change(color) }

    private fun update() {
        updating=true
        swatch.background=u.shape(color,10f,Color.GRAY)
        swatch.contentDescription="Selected front light color #%06X".format(color and 0xffffff)
        hex.setText("#%06X".format(color and 0xffffff))
        for ((value,cell) in cells) {
            cell.isSelected=value==color
            cell.background=u.shape(value,0f,if (value==color) Color.WHITE else Color.TRANSPARENT)
        }
        for ((i,bar) in sliders.withIndex()) bar.progress=(intArrayOf(Color.red(color),Color.green(color),Color.blue(color))[i]*1000f/255).roundToInt()
        spectrum?.invalidate()
        updating=false
    }

    private fun render() {
        tabs.removeAllViews(); content.removeAllViews(); cells.clear(); sliders.clear(); spectrum=null
        u.segments(tabs,listOf("Grid","Spectrum","Sliders"),tab) { tab=it; render(); update() }
        when (tab) {
            "Grid" -> repeat(10) { y ->
                val line=u.row()
                repeat(12) { x ->
                    val value=if (y==0) Color.rgb(255-x*255/11,255-x*255/11,255-x*255/11)
                    else Color.HSVToColor(floatArrayOf(x*30f,if (y<=5) 1f else 1f-(y-5)*.18f,if (y<=5) .25f+y*.15f else 1f))
                    val cell=View(u.a).apply {
                        contentDescription="Color #%06X".format(value and 0xffffff); isFocusable=true
                        setOnClickListener { select(value) }
                    }
                    cells.add(value to cell)
                    line.addView(cell,LinearLayout.LayoutParams(0,u.px(23f),1f))
                }
                u.add(content,line)
            }
            "Spectrum" -> {
                spectrum=object: View(u.a) {
                    val paint=Paint(Paint.ANTI_ALIAS_FLAG)
                    val hsv=FloatArray(3)
                    init { contentDescription="Color spectrum" }
                    override fun onDraw(c: Canvas) {
                        paint.shader=LinearGradient(0f,0f,width.toFloat(),0f,intArrayOf(Color.RED,Color.YELLOW,Color.GREEN,Color.CYAN,Color.BLUE,Color.MAGENTA,Color.RED),null,Shader.TileMode.CLAMP)
                        c.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint)
                        paint.shader=LinearGradient(0f,0f,0f,height.toFloat(),intArrayOf(Color.WHITE,Color.TRANSPARENT,Color.BLACK),floatArrayOf(0f,.5f,1f),Shader.TileMode.CLAMP)
                        c.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint)
                        Color.colorToHSV(color,hsv)
                        paint.shader=null; paint.style=Paint.Style.STROKE; paint.strokeWidth=u.px(2f).toFloat(); paint.color=Color.WHITE
                        val y=if (hsv[2]<1f) 1f-hsv[2]/2 else hsv[1]/2
                        c.drawCircle((hsv[0]/360*width).coerceIn(u.px(5f).toFloat(),width-u.px(5f).toFloat()),(y*height).coerceIn(u.px(5f).toFloat(),height-u.px(5f).toFloat()),u.px(5f).toFloat(),paint)
                        paint.style=Paint.Style.FILL
                    }
                    override fun onTouchEvent(e: MotionEvent): Boolean {
                        if (e.actionMasked==MotionEvent.ACTION_DOWN || e.actionMasked==MotionEvent.ACTION_MOVE || e.actionMasked==MotionEvent.ACTION_UP) {
                            parent.requestDisallowInterceptTouchEvent(true)
                            val y=(e.y/height).coerceIn(0f,1f)
                            select(Color.HSVToColor(floatArrayOf((e.x/width).coerceIn(0f,.99999f)*360,min(1f,y*2),min(1f,(1-y)*2))))
                            if (e.actionMasked==MotionEvent.ACTION_UP) performClick()
                            return true
                        }
                        return e.actionMasked==MotionEvent.ACTION_CANCEL
                    }
                    override fun performClick(): Boolean { super.performClick(); return true }
                }.also { u.add(content,it,230f) }
            }
            else -> {
                for ((i,name) in listOf("Red","Green","Blue").withIndex()) {
                    val bar=u.slider(content,name,intArrayOf(Color.red(color),Color.green(color),Color.blue(color))[i].toFloat(),0f,255f,format={ "${it.roundToInt()}" }) { value ->
                        if (!updating) {
                            val rgb=intArrayOf(Color.red(color),Color.green(color),Color.blue(color)); rgb[i]=value.roundToInt()
                            select(Color.rgb(rgb[0],rgb[1],rgb[2]))
                        }
                    }
                    sliders.add(bar)
                }
                u.gap(content,59f)
            }
        }
    }
}
