package org.myrec.app

import android.graphics.Bitmap
import android.graphics.Matrix
import java.io.File

object SourceMirror {
    fun enabled(path: String): Boolean =
        try {
            File(path + ".mirror").readText().trim() == "horizontal"
        } catch (_: Exception) {
            false
        }

    fun flip(bitmap: Bitmap): Bitmap {
        val output =
            Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                Matrix().apply { setScale(-1f, 1f) },
                false,
            )
        if (output !== bitmap) bitmap.recycle()
        return output
    }

    fun frame(path: String, bitmap: Bitmap?): Bitmap? =
        bitmap?.let { if (enabled(path)) flip(it) else it }
}
