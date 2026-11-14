package org.myrec.app

import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.*

class RGBOffsetPads(context: Context, private val scale: Float,
    private val read: (String) -> Float, private val change: (String, Float, Float) -> Unit,
) : LinearLayout(context) {
    private val pads = mutableListOf<Pad>()

    init {
        orientation = HORIZONTAL
        for ((name, color) in listOf("Red" to Color.RED, "Green" to Color.GREEN, "Blue" to Color.BLUE)) {
            val column = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val pad = Pad(name, color)
            pads.add(pad)
            column.addView(pad, LayoutParams(-1, -2))
            column.addView(TextView(context).apply {
                text = name
                setTextColor(Color.WHITE)
                textSize = 8 * scale / resources.displayMetrics.scaledDensity
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                setPadding(0, (5 * scale).roundToInt(), 0, 0)
            }, LayoutParams(-1, -2))
            addView(column, LayoutParams(0, -2, 1f).apply {
                leftMargin = (6 * scale).roundToInt()
                rightMargin = (6 * scale).roundToInt()
            })
        }
    }

    fun refresh() { pads.forEach { it.invalidate() } }

    fun enable(value: Boolean) {
        pads.forEach { it.isEnabled = value }
        alpha = if (value) 1f else .45f
    }

    private inner class Pad(val name: String, val color: Int) : View(context) {
        private val key = name.lowercase(java.util.Locale.ROOT)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onDoubleTap(e: MotionEvent): Boolean { place(0f, 0f); return true }
        })

        init {
            contentDescription = "$name offset"
            isFocusable = true
            isClickable = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val size = min(MeasureSpec.getSize(widthMeasureSpec), (108 * scale).roundToInt())
            setMeasuredDimension(size, size)
        }

        private fun reach() = max(1f, min(width, height) / 2f - 10 * scale)

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val radius = min(cx, cy) - scale
            paint.style = Paint.Style.FILL
            paint.color = color
            paint.alpha = 31
            canvas.drawCircle(cx, cy, radius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = scale
            paint.alpha = 204
            canvas.drawCircle(cx, cy, radius, paint)
            paint.alpha = 56
            canvas.drawCircle(cx, cy, radius - reach() * .48f, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.alpha = 102
            canvas.drawCircle(cx, cy, 1.5f * scale, paint)
            val x = cx + read(key + "X") * reach()
            val y = cy + read(key + "Y") * reach()
            paint.alpha = 255
            paint.strokeWidth = 2 * scale
            paint.strokeCap = Paint.Cap.ROUND
            paint.setShadowLayer(scale, 0f, scale, Color.BLACK)
            canvas.drawLine(x - 5 * scale, y, x + 5 * scale, y, paint)
            canvas.drawLine(x, y - 5 * scale, x, y + 5 * scale, paint)
            paint.clearShadowLayer()
        }

        private fun place(x: Float, y: Float) {
            val radius = max(1f, hypot(x, y))
            change(key, (x / radius).coerceIn(-1f, 1f), (y / radius).coerceIn(-1f, 1f))
            invalidate()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!isEnabled) return false
            detector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> parent.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_MOVE -> place((event.x - width / 2f) / reach(), (event.y - height / 2f) / reach())
                MotionEvent.ACTION_UP -> { parent.requestDisallowInterceptTouchEvent(false); performClick() }
                MotionEvent.ACTION_CANCEL -> parent.requestDisallowInterceptTouchEvent(false)
            }
            return true
        }

        override fun performClick(): Boolean { super.performClick(); return true }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            val reading = "X ${(read(key + "X") * 100).roundToInt()}% · Y ${(read(key + "Y") * 100).roundToInt()}%"
            if (android.os.Build.VERSION.SDK_INT >= 30) info.stateDescription = reading
            else info.contentDescription = "$name offset · $reading"
            for ((i, label) in listOf("Move left", "Move right", "Move up", "Move down", "Center").withIndex())
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(0x02000001 + i, label))
        }

        override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
            if (!isEnabled) return false
            val x = read(key + "X")
            val y = read(key + "Y")
            when (action) {
                0x02000001 -> place(x - .03f, y)
                0x02000002 -> place(x + .03f, y)
                0x02000003 -> place(x, y - .03f)
                0x02000004 -> place(x, y + .03f)
                0x02000005 -> place(0f, 0f)
                else -> return super.performAccessibilityAction(action, arguments)
            }
            return true
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            val action = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> 0x02000001
                KeyEvent.KEYCODE_DPAD_RIGHT -> 0x02000002
                KeyEvent.KEYCODE_DPAD_UP -> 0x02000003
                KeyEvent.KEYCODE_DPAD_DOWN -> 0x02000004
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> 0x02000005
                else -> return super.onKeyDown(keyCode, event)
            }
            return performAccessibilityAction(action, null)
        }
    }
}
