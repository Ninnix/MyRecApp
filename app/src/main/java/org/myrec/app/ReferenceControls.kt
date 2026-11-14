package org.myrec.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.SeekBar
import kotlin.math.abs
import kotlin.math.roundToInt

class EffectCard(context: Context, private val unit: Float, private val remove: () -> Unit) :
    FrameLayout(context) {
    private var downX = 0f
    private var downY = 0f
    private var swiping = false

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                swiping = false
            }
            MotionEvent.ACTION_MOVE ->
                if (
                    abs(event.rawX - downX) > 10 * unit &&
                        abs(event.rawX - downX) > abs(event.rawY - downY) * 1.5f
                ) {
                    swiping = true
                    drag(event)
                    parent.requestDisallowInterceptTouchEvent(true)
                    return true
                }
        }
        return false
    }

    private fun drag(event: MotionEvent) {
        translationX = (event.rawX - downX).coerceIn(-width.toFloat(), 0f)
        alpha = (1f + translationX / width * .6f).coerceIn(.4f, 1f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!swiping) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                drag(event)
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                parent.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP && translationX < -width * .35f)
                    remove()
                else animate().translationX(0f).alpha(1f).setDuration(120).start()
                swiping = false
            }
        }
        return true
    }
}

class ReferenceSlider(context: Context, private val unit: Float) : SeekBar(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        thumb = null
        progressDrawable = null
        minimumHeight = 0
        val inset = (13f * unit).roundToInt()
        setPadding(inset, 0, inset, 0)
    }

    override fun onDraw(canvas: Canvas) {
        val middle = height / 2f
        val radius = 2f * unit
        val half = 13f * unit
        val x = half + (width - half * 2).coerceAtLeast(0f) * progress / max.coerceAtLeast(1)
        paint.color = Color.rgb(45, 45, 47)
        canvas.drawRoundRect(
            0f,
            middle - radius,
            width.toFloat(),
            middle + radius,
            radius,
            radius,
            paint,
        )
        paint.color = progressTintList?.defaultColor ?: Color.rgb(255, 55, 74)
        canvas.drawRoundRect(0f, middle - radius, x, middle + radius, radius, radius, paint)
        paint.color = Color.WHITE
        canvas.drawRoundRect(
            x - half,
            middle - 7f * unit,
            x + half,
            middle + 7f * unit,
            7f * unit,
            7f * unit,
            paint,
        )
    }
}

class ReferenceToggle(context: Context, private val unit: Float) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var changed: (Boolean) -> Unit = {}
    var checked = false
        set(value) {
            field = value
            invalidate()
        }

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize((44 * unit).toInt(), widthMeasureSpec),
            resolveSize((25 * unit).toInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val middle = height / 2f
        val radius = 10f * unit
        paint.color = if (checked) Color.rgb(255, 55, 74) else Color.rgb(65, 65, 70)
        canvas.drawRoundRect(
            0f,
            middle - radius,
            width.toFloat(),
            middle + radius,
            radius,
            radius,
            paint,
        )
        paint.color = Color.WHITE
        val x = if (checked) width - radius else radius
        canvas.drawCircle(x, middle, radius - 1.2f * unit, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean {
        checked = !checked
        changed(checked)
        super.performClick()
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Switch"
        info.isCheckable = true
        info.isChecked = checked
    }
}
