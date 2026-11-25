package org.myrec.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlin.math.ceil
import kotlin.math.max

internal class OverlayImages : AutoCloseable {
    data class Picture(val bitmap: Bitmap, val width: Int, val height: Int)
    private data class Source(val path: String, val bytes: Long, val modified: Long)
    private data class Size(val width: Int, val height: Int)
    private data class Key(val source: Source, val size: Size, val sample: Int)

    private val shapes = LinkedHashMap<Source, Size>(16, .75f, true)
    private val pictures = LinkedHashMap<Key, Picture>(8, .75f, true)
    private var bytes = 0L

    fun get(path: String, effect: Effect, width: Int, height: Int, preview: Boolean): Picture? {
        val file = File(path)
        val source = Source(path, file.length(), file.lastModified())
        val centered = effect.values["geometry"] == "center"
        val shape = if (centered) null else shapes[source] ?: BitmapFactory.Options().run {
            inJustDecodeBounds = true
            BitmapFactory.decodeFile(path, this)
            if (outWidth <= 0 || outHeight <= 0) return null
            Size(outWidth, outHeight).also {
                shapes[source] = it
                if (shapes.size > 32) shapes.remove(shapes.keys.first())
            }
        }
        var sample = 1
        if (preview && shape != null) {
            val rect = ArchiveGeometry.image(effect, shape.width.toDouble(), shape.height.toDouble(), width.toDouble(), height.toDouble())
            val wanted = max(1.0, ceil(rect.width)).coerceAtMost(shape.width.toDouble())
            while (shape.width / (2 * sample) >= wanted) sample *= 2
        }
        val key = Key(source, if (centered) Size(width, height) else Size(0, 0), sample)
        pictures[key]?.let { return it }
        val bitmap = if (centered) PhotoSource.frame(path, width, height) else
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val picture = Picture(bitmap, shape?.width ?: bitmap.width, shape?.height ?: bitmap.height)
        pictures[key] = picture
        bytes += bitmap.allocationByteCount
        // Keep the current image even when a single full-size export asset exceeds the budget.
        while (pictures.size > 1 && (bytes > 48L * 1024 * 1024 || pictures.size > 8)) {
            val old = pictures.remove(pictures.keys.first())!!.bitmap
            bytes -= old.allocationByteCount
            old.recycle()
        }
        return picture
    }

    override fun close() {
        pictures.values.forEach { it.bitmap.recycle() }
        pictures.clear()
        shapes.clear()
        bytes = 0
    }
}
