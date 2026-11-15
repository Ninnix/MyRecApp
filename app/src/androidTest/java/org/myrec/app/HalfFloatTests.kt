package org.myrec.app

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder

object HalfFloatTests {
    fun run(test: (String, () -> Unit) -> Unit) {
        fun image(value: (Int) -> Int): Bitmap {
            val bitmap = WorkingColor.bitmap(256,256)
            val bytes = ByteBuffer.allocateDirect(bitmap.byteCount).order(ByteOrder.nativeOrder())
            for (y in 0 until 256) for (x in 0 until 256) {
                for (k in 0..2) bytes.putShort(y*bitmap.rowBytes+x*8+k*2,value(y*256+x).toShort())
                bytes.putShort(y*bitmap.rowBytes+x*8+6,0x3c00.toShort())
            }
            bitmap.copyPixelsFromBuffer(bytes); return bitmap
        }
        fun bytes(image: Bitmap) = ByteBuffer.allocateDirect(image.byteCount).order(ByteOrder.nativeOrder()).also { image.copyPixelsToBuffer(it) }
        test("native half conversion preserves every finite half value including subnormals and signed HDR") {
            val a = image { it }; val b = image { it }
            try {
                Native.workingAverage(a,b,.5f); val output = bytes(a)
                for (y in 0 until 256) for (x in 0 until 256) {
                    val h = y*256+x
                    val expected = if (h and 0x7c00 == 0x7c00 || h and 0x7fff == 0) 0 else h
                    for (k in 0..2) check(output.getShort(y*a.rowBytes+x*8+k*2).toInt() and 65535 == expected) { "Half round trip ${h.toString(16)}" }
                }
            } finally { a.recycle(); b.recycle() }
        }
        test("native half averaging rounds all adjacent finite half midpoints to nearest even") {
            val a = image { it }; val b = image { if (it and 0x7fff < 0x7bff) it+1 else it }
            try {
                Native.workingAverage(a,b,.5f); val output = bytes(a)
                for (h in 0..65535) {
                    if (h and 0x7fff >= 0x7bff) continue
                    val expected = if (h and 1 == 0) h else h+1
                    val y = h/256; val x = h%256
                    for (k in 0..2) check(output.getShort(y*a.rowBytes+x*8+k*2).toInt() and 65535 == expected) { "Half midpoint ${h.toString(16)}" }
                }
            } finally { a.recycle(); b.recycle() }
        }
    }
}
