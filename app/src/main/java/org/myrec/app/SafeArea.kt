package org.myrec.app

import android.graphics.Rect
import android.view.View

internal object SafeArea {
    fun insets(view: View): Rect {
        val cutout = view.rootWindowInsets?.displayCutout ?: return Rect()
        val at = IntArray(2)
        view.getLocationInWindow(at)
        val window = view.rootView
        return Rect(
            (cutout.safeInsetLeft-at[0]).coerceAtLeast(0),
            (cutout.safeInsetTop-at[1]).coerceAtLeast(0),
            (at[0]+view.width-window.width+cutout.safeInsetRight).coerceAtLeast(0),
            (at[1]+view.height-window.height+cutout.safeInsetBottom).coerceAtLeast(0))
    }
}
