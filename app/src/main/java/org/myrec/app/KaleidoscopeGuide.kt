package org.myrec.app

import android.graphics.*
import android.view.MotionEvent
import kotlin.math.*

class KaleidoscopeGuide(private val changed: (String, Float) -> Unit, private val invalidate: () -> Unit) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private var factor = 1f
    private val splitHandle = android.graphics.PointF()
    private var splitStart: Effect? = null
    private var splitTarget = 0
    private var splitX = 0f
    private var splitY = 0f
    private var splitAngle = 0.0
    private var splitMap = Kaleidoscope.Mapping(1.0, 0.0, 0.0, 1.0)

    fun draw(canvas: Canvas, rect: RectF, unit: Float, fx: Effect) {
        bounds.set(rect); factor = unit
        val cx = bounds.left + fx.number("centerX", .5f) * bounds.width()
        val cy = bounds.top + fx.number("centerY", .5f) * bounds.height()
        val angle = (fx.number("splitAngle") - 90) * Math.PI / 180
        val divisions = fx.number("divisions", 2f).toInt()
        val reach = hypot(bounds.width(), bounds.height())
        val length = min(82 * factor, max(40 * factor, min(bounds.width(), bounds.height()) * .3f))
        val safe = RectF(bounds).apply { inset(min(22 * factor, width() / 4), min(22 * factor, height() / 4)) }
        val handles = (0 until divisions).map { i ->
            val a = angle + i * 2 * Math.PI / divisions
            android.graphics.PointF(cx + cos(a).toFloat() * length, cy + sin(a).toFloat() * length)
        }
        splitHandle.set(handles.firstOrNull { safe.contains(it.x, it.y) } ?: handles.map {
            android.graphics.PointF(it.x.coerceIn(safe.left, safe.right), it.y.coerceIn(safe.top, safe.bottom))
        }.maxBy { hypot(it.x - cx, it.y - cy) })
        canvas.save(); canvas.clipRect(bounds)
        paint.style = Paint.Style.STROKE
        for (i in 0 until divisions) {
            val a = angle + i * 2 * Math.PI / divisions
            val x = cx + cos(a).toFloat() * reach; val y = cy + sin(a).toFloat() * reach
            paint.strokeWidth = 2.5f * factor; paint.color = Color.argb(140, 0, 0, 0)
            canvas.drawLine(cx, cy, x, y, paint)
            paint.strokeWidth = factor; paint.color = Color.argb(190, 255, 255, 255)
            paint.pathEffect = DashPathEffect(floatArrayOf(5 * factor, 4 * factor), 0f)
            canvas.drawLine(cx, cy, x, y, paint)
            paint.pathEffect = null
        }
        paint.style = Paint.Style.FILL; paint.color = Color.argb(210, 20, 20, 20)
        canvas.drawCircle(cx, cy, 9 * factor, paint)
        canvas.drawCircle(splitHandle.x, splitHandle.y, 8 * factor, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = factor; paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, 9 * factor, paint)
        canvas.drawCircle(splitHandle.x, splitHandle.y, 8 * factor, paint)
        paint.style = Paint.Style.FILL
        canvas.restore()
    }

    fun touch(e: MotionEvent, display: Effect): Boolean {
        fun pointX(x: Float) = (x - bounds.left) / max(1f, bounds.width())
        fun pointY(y: Float) = (y - bounds.top) / max(1f, bounds.height())
        fun mapping(fx: Effect, x: Float, y: Float) = Kaleidoscope.dragMapping(pointX(x).toDouble(), pointY(y).toDouble(),
            bounds.width().toDouble(), bounds.height().toDouble(), fx)
        fun edit(key: String, value: Float) { changed(key, value) }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!bounds.contains(e.x, e.y)) return true
                splitStart = display.copyEffect()
                splitX = e.x; splitY = e.y
                val fx = splitStart!!
                val cx = bounds.left + fx.number("centerX", .5f) * bounds.width()
                val cy = bounds.top + fx.number("centerY", .5f) * bounds.height()
                val center = hypot(e.x - cx, e.y - cy); val handle = hypot(e.x - splitHandle.x, e.y - splitHandle.y)
                splitTarget = if (min(center, handle) > 22 * factor) 0 else if (center <= handle) 1 else 2
                splitMap = mapping(fx, e.x, e.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                splitStart = display.copyEffect()
                splitTarget = 3
                splitAngle = atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())
                val fx = splitStart!!.copyEffect().apply { setNumber("sourceAngle", 0f) }
                splitMap = mapping(fx, (e.getX(0) + e.getX(1)) / 2, (e.getY(0) + e.getY(1)) / 2)
            }
            MotionEvent.ACTION_POINTER_UP -> splitStart = null
            MotionEvent.ACTION_MOVE -> {
                val fx = splitStart ?: return true
                when (splitTarget) {
                    0 -> if (e.pointerCount == 1) {
                        val dx = (e.x - splitX).toDouble(); val dy = (e.y - splitY).toDouble()
                        edit("sourcePanX", (fx.number("sourcePanX") + splitMap.x(dx, dy) / bounds.width()).toFloat().coerceIn(-1f, 1f))
                        edit("sourcePanY", (fx.number("sourcePanY") + splitMap.y(dx, dy) / bounds.height()).toFloat().coerceIn(-1f, 1f))
                    }
                    1 -> if (e.pointerCount == 1) {
                        edit("centerX", (fx.number("centerX", .5f) + (e.x - splitX) / bounds.width()).coerceIn(0f, 1f))
                        edit("centerY", (fx.number("centerY", .5f) + (e.y - splitY) / bounds.height()).coerceIn(0f, 1f))
                    }
                    2 -> if (e.pointerCount == 1) {
                        val cx = bounds.left + fx.number("centerX", .5f) * bounds.width()
                        val cy = bounds.top + fx.number("centerY", .5f) * bounds.height()
                        if (hypot(e.x - cx, e.y - cy) > 8 * factor) {
                            val delta = atan2((e.y - cy).toDouble(), (e.x - cx).toDouble()) - atan2((splitY - cy).toDouble(), (splitX - cx).toDouble())
                            edit("splitAngle", Kaleidoscope.wrapped(fx.number("splitAngle") + delta * 180 / Math.PI).toFloat())
                        }
                    }
                    3 -> if (e.pointerCount == 2) {
                        val angle = atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())
                        val direction = if (splitMap.determinant < 0) -1 else 1
                        var value = Kaleidoscope.wrapped(fx.number("sourceAngle") + direction * (angle - splitAngle) * 180 / Math.PI)
                        if (abs(value) <= 2) value = 0.0
                        edit("sourceAngle", value.toFloat())
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                splitStart = null
            }
        }
        return true
    }

}
