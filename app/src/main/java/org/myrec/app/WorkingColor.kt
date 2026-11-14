package org.myrec.app

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

object WorkingColor {
    private val formats = linkedMapOf<String, Boolean>()
    fun bitmap(width: Int, height: Int) = Bitmap.createBitmap(width, height, Bitmap.Config.RGBA_F16,
        true, ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB))

    @Synchronized fun hdr(path: String): Boolean {
        val file = File(path)
        val key = "$path:${file.length()}:${file.lastModified()}"
        return formats.getOrPut(key) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(path)
                (0 until extractor.trackCount).any {
                    val format = extractor.getTrackFormat(it)
                    format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true &&
                        format.containsKey(MediaFormat.KEY_COLOR_TRANSFER) && format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) in
                        listOf(MediaFormat.COLOR_TRANSFER_HLG, MediaFormat.COLOR_TRANSFER_ST2084)
                }
            } catch (_: Exception) { false }
            finally { extractor.release(); if (formats.size > 64) formats.clear() }
        }
    }

    fun display(input: Bitmap, toneMap: Boolean = true): Bitmap {
        val output = Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)
        Native.workingDisplay(input, output, toneMap)
        return output
    }
}
