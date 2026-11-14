package org.myrec.app

import android.graphics.Matrix
import android.graphics.RectF
import java.io.*
import java.security.MessageDigest
import kotlin.math.*

object Stabilizer {
    var directory: File? = null
    private const val FPS = 30.0

    private data class Track(
        val width: Int,
        val height: Int,
        val poses: Array<DoubleArray>,
        val segment: IntArray,
    ) {
        val warps = linkedMapOf<Int, Array<FloatArray>>()
    }

    private val tracks = linkedMapOf<String, Track>()

    @Synchronized
    fun matrix(
        clip: Clip,
        time: Double,
        strength: Float,
        bounds: RectF,
        progress: (Float?) -> Unit,
    ): Matrix {
        val track = track(clip.path, clip.sourceIn, clip.sourceOut, progress)
        val key = (strength.coerceIn(0f, 1f) * 100).roundToInt()
        val warps =
            track.warps.getOrPut(key) {
                if (track.warps.size >= 4) track.warps.remove(track.warps.keys.first())
                smooth(track, key / 100.0)
            }
        val position = ((time - clip.sourceIn) * FPS).coerceIn(0.0, (warps.size - 1).toDouble())
        val i = floor(position).toInt()
        val j = min(i + 1, warps.size - 1)
        val fraction = (position - i).toFloat()
        val values = FloatArray(4) { warps[i][it] + (warps[j][it] - warps[i][it]) * fraction }
        val a = values[0]
        val b = values[1]
        val sx = bounds.width() / track.width
        val sy = bounds.height() / track.height
        val bx = b * sx / sy
        val by = b * sy / sx
        val tx = bounds.left - a * bounds.left + bx * bounds.top + values[2] * sx
        val ty = bounds.top - by * bounds.left - a * bounds.top + values[3] * sy
        return Matrix().apply { setValues(floatArrayOf(a, -bx, tx, by, a, ty, 0f, 0f, 1f)) }
    }

    private fun track(path: String, start: Double, end: Double, progress: (Float?) -> Unit): Track {
        val source = File(path)
        val id =
            MessageDigest.getInstance("SHA-256")
                .digest(
                    "$path:${source.length()}:${source.lastModified()}:$start:$end:v2".toByteArray()
                )
                .joinToString("") { "%02x".format(it) }
        tracks[id]?.let {
            return it
        }
        val saved = directory?.let { File(it, "$id.motion") }
        val restored =
            try {
                saved
                    ?.takeIf { it.exists() }
                    ?.inputStream()
                    ?.buffered()
                    ?.use { input ->
                        val d = DataInputStream(input)
                        val w = d.readInt()
                        val h = d.readInt()
                        val n = d.readInt()
                        require(w in 1..1024 && h in 1..1024 && n in 1..600000)
                        val segments = IntArray(n)
                        val poses =
                            Array(n) { index ->
                                segments[index] = d.readInt()
                                DoubleArray(4) { d.readDouble() }
                            }
                        Track(w, h, poses, segments)
                    }
            } catch (_: Exception) {
                null
            }
        if (restored != null) {
            tracks[id] = restored
            return restored
        }
        val count = max(2, ceil((end - start) * FPS).toInt() + 1)
        val poses = Array(count) { DoubleArray(4) }
        val segment = IntArray(count)
        var width = 0
        var height = 0
        progress(0f)
        try {
            VideoDecoder(path).use { decoder ->
                var previous = decoder.frame(start, 320, 180) ?: error("Cannot analyze video")
                width = previous.width
                height = previous.height
                var a = 1.0
                var b = 0.0
                var tx = 0.0
                var ty = 0.0
                var angle = 0.0
                var scale = 0.0
                var group = 0
                try {
                    for (i in 1 until count) {
                        val at = min(start + i / FPS, max(start, end - 1.0 / 30))
                        val current =
                            decoder.frame(at, 320, 180) ?: previous.copy(previous.config!!, true)
                        val motion = Native.similarity(previous, current)
                        previous.recycle()
                        previous = current
                        if (motion[4] >= 6) {
                            val da = motion[0].toDouble()
                            val db = motion[1].toDouble()
                            val nextA = da * a - db * b
                            val nextB = da * b + db * a
                            val nextX = da * tx - db * ty + motion[2]
                            ty = db * tx + da * ty + motion[3]
                            tx = nextX
                            a = nextA
                            b = nextB
                            angle += atan2(db, da)
                            scale += ln(hypot(da, db))
                        } else {
                            group++
                            a = 1.0
                            b = 0.0
                            tx = 0.0
                            ty = 0.0
                            angle = 0.0
                            scale = 0.0
                        }
                        val cx = width / 2.0
                        val cy = height / 2.0
                        poses[i] =
                            doubleArrayOf(
                                a * cx - b * cy + tx - cx,
                                b * cx + a * cy + ty - cy,
                                angle,
                                scale,
                            )
                        segment[i] = group
                        if (i % 8 == 0) progress(i.toFloat() / count)
                    }
                } finally {
                    previous.recycle()
                }
            }
        } finally {
            progress(null)
        }
        val track = Track(width, height, poses, segment)
        if (tracks.size >= 8) tracks.remove(tracks.keys.first())
        tracks[id] = track
        if (saved != null) {
            saved.parentFile?.mkdirs()
            val temp = File(saved.path + ".partial")
            DataOutputStream(temp.outputStream().buffered()).use { d ->
                d.writeInt(width)
                d.writeInt(height)
                d.writeInt(count)
                for (i in poses.indices) {
                    d.writeInt(segment[i])
                    poses[i].forEach { d.writeDouble(it) }
                }
            }
            temp.renameTo(saved)
        }
        return track
    }

    private fun smooth(track: Track, strength: Double): Array<FloatArray> {
        val sigma = 1 + strength * 18
        val radius = ceil(sigma * 2.5).toInt()
        val cx = track.width / 2.0
        val cy = track.height / 2.0
        val warps = Array(track.poses.size) { floatArrayOf(1f, 0f, 0f, 0f) }
        var crop = 1.0
        for (i in track.poses.indices) {
            val sum = DoubleArray(4)
            var weight = 0.0
            for (j in max(0, i - radius)..min(track.poses.size - 1, i + radius)) {
                if (track.segment[j] != track.segment[i]) continue
                val w = exp(-(j - i).toDouble().pow(2) / (2 * sigma * sigma))
                for (k in 0..3) sum[k] += track.poses[j][k] * w
                weight += w
            }
            val pose = track.poses[i]
            for (k in 0..3) sum[k] = pose[k] + (sum[k] / weight - pose[k]) * strength
            val angle = sum[2] - pose[2]
            val scale = exp(sum[3] - pose[3])
            val a = scale * cos(angle)
            val b = scale * sin(angle)
            val tx = cx + sum[0] - a * (cx + pose[0]) + b * (cy + pose[1])
            val ty = cy + sum[1] - b * (cx + pose[0]) - a * (cy + pose[1])
            var warp = floatArrayOf(a.toFloat(), b.toFloat(), tx.toFloat(), ty.toFloat())
            // Bound crop and attenuate extreme corrections instead of exposing black borders.
            if (!covered(warp, 1.3, track.width, track.height)) {
                var lo = 0.0
                var hi = 1.0
                repeat(12) {
                    val f = (lo + hi) / 2
                    val partial =
                        floatArrayOf(
                            (1 + (a - 1) * f).toFloat(),
                            (b * f).toFloat(),
                            (tx * f).toFloat(),
                            (ty * f).toFloat(),
                        )
                    if (covered(partial, 1.3, track.width, track.height)) lo = f else hi = f
                }
                warp =
                    floatArrayOf(
                        (1 + (a - 1) * lo).toFloat(),
                        (b * lo).toFloat(),
                        (tx * lo).toFloat(),
                        (ty * lo).toFloat(),
                    )
            }
            var lo = 1.0
            var hi = 1.3
            repeat(12) {
                val z = (lo + hi) / 2
                if (covered(warp, z, track.width, track.height)) hi = z else lo = z
            }
            crop = max(crop, hi)
            warps[i] = warp
        }
        for (w in warps) {
            w[0] = (w[0] * crop).toFloat()
            w[1] = (w[1] * crop).toFloat()
            w[2] = (w[2] * crop + (1 - crop) * cx).toFloat()
            w[3] = (w[3] * crop + (1 - crop) * cy).toFloat()
        }
        return warps
    }

    private fun covered(w: FloatArray, crop: Double, width: Int, height: Int): Boolean {
        val a = w[0].toDouble()
        val b = w[1].toDouble()
        val det = a * a + b * b
        for (x in intArrayOf(0, width)) for (y in intArrayOf(0, height)) {
            val px = (x - width / 2.0) / crop + width / 2.0 - w[2]
            val py = (y - height / 2.0) / crop + height / 2.0 - w[3]
            val sx = (a * px + b * py) / det
            val sy = (-b * px + a * py) / det
            if (sx < 0 || sy < 0 || sx > width || sy > height) return false
        }
        return true
    }
}
