package org.myrec.app

import android.view.MotionEvent
import kotlin.math.sqrt

class InspectionGesture(private val changed: (Float, Float, Float, Float, Float) -> Unit) {
    private var count = 0
    private var span = 0f
    private var x = 0f
    private var y = 0f

    fun event(e: MotionEvent): Boolean {
        if (e.actionMasked in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_UP)) {
            count = 0
            return false
        }
        if (e.pointerCount < 2) { count = 0; return false }
        var cx = 0f
        var cy = 0f
        for (i in 0 until e.pointerCount) { cx += e.getX(i); cy += e.getY(i) }
        cx /= e.pointerCount; cy /= e.pointerCount
        var radius = 0f
        for (i in 0 until e.pointerCount) {
            val dx = e.getX(i) - cx; val dy = e.getY(i) - cy
            radius += dx * dx + dy * dy
        }
        val next = sqrt(radius / e.pointerCount)
        if (e.actionMasked == MotionEvent.ACTION_MOVE && count == e.pointerCount && next > 0 && span > 0)
            changed(next / span, cx, cy, x, y)
        count = e.pointerCount; span = next; x = cx; y = cy
        return true
    }
}
