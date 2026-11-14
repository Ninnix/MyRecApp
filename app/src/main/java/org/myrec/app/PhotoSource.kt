package org.myrec.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import kotlin.math.max

object PhotoSource {
    fun shape(path: String): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        val turn = orientation(path) in 5..8
        return if (turn) options.outHeight to options.outWidth else options.outWidth to options.outHeight
    }

    private fun orientation(path: String): Int = try {
        ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }

    fun frame(path: String, width: Int, height: Int): Bitmap {
        val shape = requireNotNull(shape(path)) { "This image cannot be decoded" }
        val options = BitmapFactory.Options()
        while (shape.first / (options.inSampleSize.coerceAtLeast(1) * 2) >= width &&
            shape.second / (options.inSampleSize.coerceAtLeast(1) * 2) >= height)
            options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
        val original = requireNotNull(BitmapFactory.decodeFile(path, options)) { "This image cannot be decoded" }
        val matrix = Matrix()
        when (orientation(path)) {
            2 -> matrix.setScale(-1f, 1f)
            3 -> matrix.setRotate(180f)
            4 -> matrix.setScale(1f, -1f)
            5 -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            6 -> matrix.setRotate(90f)
            7 -> { matrix.setRotate(90f); matrix.postScale(1f, -1f) }
            8 -> matrix.setRotate(-90f)
        }
        val oriented = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
        if (oriented !== original) original.recycle()
        val scale = max(oriented.width.toFloat() / max(1, width), oriented.height.toFloat() / max(1, height)).coerceAtLeast(1f)
        val result = Bitmap.createScaledBitmap(oriented, max(1, (oriented.width / scale).toInt()),
            max(1, (oriented.height / scale).toInt()), true)
        if (result !== oriented) oriented.recycle()
        return result
    }
}
