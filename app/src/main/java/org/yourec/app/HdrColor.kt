package org.yourec.app

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import kotlin.math.*

class HdrColor(context: Context) {
    private val prefs = context.getSharedPreferences("hdr-color", Context.MODE_PRIVATE)

    private fun key(camera: String) = "${Build.FINGERPRINT}:$camera"

    fun profile(camera: String) = prefs.getInt(key(camera), 0)

    fun remember(camera: String, profile: Int) {
        require(profile in 0..2)
        prefs.edit().putInt(key(camera), profile).apply()
    }

    companion object {
        // Camera HALs can label HLG RGB with primaries that differ from the pixels.
        // Compare registered, exposure-independent chroma against the same camera's SDR output.
        fun detect(sdr: Bitmap, hlg: Bitmap): FloatArray {
            require(sdr.width == hlg.width && sdr.height == hlg.height)
            val w = sdr.width
            val h = sdr.height
            val a = IntArray(w * h)
            val b = IntArray(w * h)
            sdr.getPixels(a, 0, w, 0, 0, w, h)
            hlg.getPixels(b, 0, w, 0, 0, w, h)
            val ga = registration(a, w, h)
            val gb = registration(b, w, h)
            val motion =
                try {
                    Native.similarity(ga, gb)
                } finally {
                    ga.recycle()
                    gb.recycle()
                }
            val result = floatArrayOf(-1f, motion[4], 0f, 0f, 0f, 0f)
            if (motion[4] < 12) return result
            val errors = Array(3) { mutableListOf<Float>() }
            fun channel(pixel: Int, shift: Int) = ((pixel ushr shift) and 255) / 255.0
            fun linear(v: Double) = if (v <= .04045) v / 12.92 else ((v + .055) / 1.055).pow(2.4)
            fun hlg(v: Double) =
                if (v <= .5) v * v / 3 else (exp((v - .55991073) / .17883277) + .28466892) / 12
            for (y in 5 until h - 5 step 2) for (x in 5 until w - 5 step 2) {
                val sx = motion[0] * x - motion[1] * y + motion[2]
                val sy = motion[1] * x + motion[0] * y + motion[3]
                val ix = floor(sx).toInt()
                val iy = floor(sy).toInt()
                if (ix !in 1 until w - 2 || iy !in 1 until h - 2) continue
                val pixel = a[y * w + x]
                val target = DoubleArray(3) { channel(pixel, 16 - 8 * it) }
                if (target.max() > .95 || target.min() < .025) continue
                for (c in 0..2) target[c] = linear(target[c])
                val ts = target.sum()
                if (ts < .01) continue
                for (c in 0..2) target[c] /= ts
                if (target.max() - target.min() < .08) continue
                val source =
                    DoubleArray(3) { c ->
                        val shift = 16 - 8 * c
                        val fx = (sx - ix).toDouble()
                        val fy = (sy - iy).toDouble()
                        val top =
                            channel(b[iy * w + ix], shift) * (1 - fx) +
                                channel(b[iy * w + ix + 1], shift) * fx
                        val bottom =
                            channel(b[(iy + 1) * w + ix], shift) * (1 - fx) +
                                channel(b[(iy + 1) * w + ix + 1], shift) * fx
                        hlg(top * (1 - fy) + bottom * fy)
                    }
                if (source.sum() < .006) continue
                val r = source[0]
                val g = source[1]
                val blue = source[2]
                val candidates =
                    arrayOf(
                        doubleArrayOf(
                            1.6605 * r - .5876 * g - .0728 * blue,
                            -.1246 * r + 1.1329 * g - .0083 * blue,
                            -.0182 * r - .1006 * g + 1.1187 * blue,
                        ),
                        source,
                        doubleArrayOf(
                            1.224745 * r - .224904 * g,
                            -.042058 * r + 1.042081 * g,
                            -.019642 * r - .078655 * g + 1.098537 * blue,
                        ),
                    )
                for (c in 0..2) {
                    val sum = candidates[c].sum().coerceAtLeast(1e-8)
                    var error = 0.0
                    for (k in 0..2) error += (candidates[c][k] / sum - target[k]).pow(2)
                    errors[c].add(sqrt(error).toFloat())
                }
            }
            result[5] = errors[0].size.toFloat()
            if (errors[0].size < 400) return result
            for (c in 0..2) {
                errors[c].sort()
                // A trimmed mean tolerates moving subjects and clipped colour boundaries.
                result[c + 2] = errors[c].subList(0, errors[c].size * 4 / 5).average().toFloat()
            }
            val best = (0..2).minBy { result[it + 2] }
            val next = (0..2).filter { it != best }.minOf { result[it + 2] }
            if (
                result[best + 2] < .055f &&
                    next - result[best + 2] > .008f &&
                    (best == 0 || result[best + 2] < result[2] * .65f)
            )
                result[0] = best.toFloat()
            return result
        }

        private fun registration(pixels: IntArray, w: Int, h: Int): Bitmap {
            val gray =
                DoubleArray(w * h) { i ->
                    val p = pixels[i]
                    .2126 * ((p ushr 16) and 255) +
                        .7152 * ((p ushr 8) and 255) +
                        .0722 * (p and 255)
                }
            val sum = DoubleArray((w + 1) * (h + 1))
            val square = DoubleArray(sum.size)
            for (y in 0 until h) {
                var s = 0.0
                var q = 0.0
                for (x in 0 until w) {
                    val value = gray[y * w + x]
                    s += value
                    q += value * value
                    val i = (y + 1) * (w + 1) + x + 1
                    sum[i] = sum[i - w - 1] + s
                    square[i] = square[i - w - 1] + q
                }
            }
            fun area(v: DoubleArray, x0: Int, y0: Int, x1: Int, y1: Int) =
                v[y1 * (w + 1) + x1] - v[y0 * (w + 1) + x1] - v[y1 * (w + 1) + x0] +
                    v[y0 * (w + 1) + x0]
            val out = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val x0 = max(0, x - 10)
                val x1 = min(w, x + 11)
                val y0 = max(0, y - 10)
                val y1 = min(h, y + 11)
                val count = (x1 - x0) * (y1 - y0)
                val mean = area(sum, x0, y0, x1, y1) / count
                val sd = sqrt(max(.65, area(square, x0, y0, x1, y1) / count - mean * mean))
                val value = (128 + 32 * (gray[y * w + x] - mean) / sd).roundToInt().coerceIn(0, 255)
                out[y * w + x] = -0x1000000 or (value shl 16) or (value shl 8) or value
            }
            return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        }
    }
}
