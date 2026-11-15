package org.myrec.app

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlin.math.*

class CubeLut private constructor(
    val size: Int, val oneDimensional: Boolean, val samples: FloatArray,
    val minimum: FloatArray, val maximum: FloatArray, val title: String?,
) {
    companion object {
        const val MAX_BYTES = 32 * 1024 * 1024
        val looks = listOf("Natural", "Warm", "Cool", "Cinema", "Mono", "Portrait", "Daylight",
            "Golden hour", "Muted film", "Rich contrast", "Pastel", "Night", "Silver", "Ink")
        fun title(name: String) = if (name == "Cool") "Cool shadows" else name
        private val builtins = mutableMapOf<String, CubeLut>()

        fun parse(bytes: ByteArray): CubeLut {
            require(bytes.size <= MAX_BYTES) { "Choose a UTF-8 .cube file smaller than 32 MB" }
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
            var size = 0; var oneD = false; var title: String? = null; var count = 0
            var low = floatArrayOf(0f, 0f, 0f); var high = floatArrayOf(1f, 1f, 1f)
            val seen = mutableSetOf<String>()
            var samples = FloatArray(0)
            fun triple(parts: List<String>): FloatArray {
                require(parts.size == 4) { "Invalid LUT input domain" }
                return parts.drop(1).map {
                    it.toFloatOrNull()?.takeIf { v -> v.isFinite() && abs(v) <= 65536 }
                        ?: error("Invalid LUT color value")
                }.toFloatArray()
            }
            val whitespace = Regex("\\s+")
            for (raw in text.lineSequence()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val parts = line.split(whitespace)
                when (parts[0]) {
                    "TITLE" -> {
                        require(seen.add("TITLE")) { "Duplicate LUT title" }
                        title = line.drop(5).trim(' ', '"').take(120)
                    }
                    "LUT_3D_SIZE", "LUT_1D_SIZE" -> {
                        require(size == 0 && count == 0 && parts.size == 2) { "Combined shaper LUTs are unsupported" }
                        oneD = parts[0] == "LUT_1D_SIZE"
                        size = parts[1].toIntOrNull() ?: 0
                        require(size in 2..(if (oneD) 16384 else 65)) { "Invalid LUT size" }
                        samples = FloatArray((if (oneD) size else size * size * size) * 3)
                    }
                    "DOMAIN_MIN", "DOMAIN_MAX" -> {
                        require(count == 0 && seen.add(parts[0])) { "Duplicate or misplaced LUT domain" }
                        val value = triple(parts)
                        if (parts[0] == "DOMAIN_MIN") low = value else high = value
                    }
                    else -> {
                        require(size > 0 && parts.size == 3 && count < samples.size) { "Unsupported LUT header or entry count" }
                        for (part in parts) samples[count++] = part.toFloatOrNull()
                            ?.takeIf { it.isFinite() && abs(it) <= 65536 } ?: error("Invalid LUT color value")
                    }
                }
            }
            require(size > 0 && count == samples.size && (0..2).all { high[it] > low[it] }) {
                "Incomplete LUT or invalid input domain"
            }
            return CubeLut(size, oneD, samples, low, high, title)
        }

        @Synchronized fun builtin(name: String): CubeLut = builtins.getOrPut(name) {
            require(name in looks)
            val size = 33
            val values = FloatArray(size * size * size * 3)
            var at = 0
            for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
                var c = doubleArrayOf(r / 32.0, g / 32.0, b / 32.0)
                val y = c[0] * .2126 + c[1] * .7152 + c[2] * .0722
                fun map(f: (Double) -> Double) { c = c.map(f).toDoubleArray() }
                when (name) {
                    "Warm" -> { c[0] = c[0].pow(.92); c[2] = c[2].pow(1.10) }
                    "Cool" -> { c[0] = c[0].pow(1.08); c[2] = c[2].pow(.92) }
                    "Mono" -> c.fill(y)
                    "Cinema" -> { map { (.5 + (it - .5) * 1.12).coerceIn(0.0, 1.0) }
                        c[0] += .035 * y; c[1] += .012 * (1 - y); c[2] += .035 * (1 - y)
                        map { y + (it - y) * .85 } }
                    "Portrait" -> { map { y + (it - y) * .92 }; c[0] += .018 * y * (1 - y)
                        c[2] -= .025 * y * (1 - y); map { it * .95 + .025 } }
                    "Daylight" -> { map { .5 + (it - .5) * 1.04 }; map { y + (it - y) * 1.08 } }
                    "Golden hour" -> { c[0] += .12 * y * (1 - y); c[1] += .025 * y
                        c[2] *= .86; map { max(0.0, it).pow(.95) } }
                    "Muted film" -> { map { .055 + .89 * (y + (it - y) * .72) }; c[2] += .018 * (1 - y) }
                    "Rich contrast" -> map { val x = it.coerceIn(0.0, 1.0); x * x * (3 - 2 * x) * .55 + x * .45 }
                    "Pastel" -> map { .075 + .88 * max(0.0, y + (it - y) * .65).pow(.91) }
                    "Night" -> { map { max(0.0, it * .94 - .012) }; c[2] += .045 * (1 - y); c[1] += .012 * (1 - y) }
                    "Silver" -> { val v = max(0.0, c[0] * .3 + c[1] * .6 + c[2] * .1).pow(.92)
                        c = doubleArrayOf(v * .98, v, min(1.0, v * 1.015)) }
                    "Ink" -> { val v = ((y - .08) / .84).coerceIn(0.0, 1.0); c.fill(v * v * (3 - 2 * v)) }
                }
                for (v in c) values[at++] = v.coerceIn(0.0, 1.0).toFloat()
            }
            CubeLut(size, false, values, FloatArray(3), FloatArray(3) { 1f }, name)
        }
    }

    fun sample(rgb: FloatArray): FloatArray {
        val p = FloatArray(3) { ((rgb[it] - minimum[it]) / (maximum[it] - minimum[it])).coerceIn(0f, 1f) * (size - 1) }
        if (oneDimensional) return FloatArray(3) { ch ->
            val i = min(size - 2, p[ch].toInt()); val f = p[ch] - i
            samples[i * 3 + ch] * (1 - f) + samples[(i + 1) * 3 + ch] * f
        }
        val base = IntArray(3) { min(size - 2, p[it].toInt()) }
        val result = FloatArray(3)
        for (b in 0..1) for (g in 0..1) for (r in 0..1) {
            val bits = intArrayOf(r, g, b)
            var weight = 1f
            for (ch in 0..2) weight *= if (bits[ch] == 1) p[ch] - base[ch] else 1 - (p[ch] - base[ch])
            val index = ((base[2] + b) * size * size + (base[1] + g) * size + base[0] + r) * 3
            for (ch in 0..2) result[ch] += weight * samples[index + ch]
        }
        return result
    }
}

object LutAssets {
    private val cache = linkedMapOf<String, CubeLut>()
    @Synchronized fun load(path: String): CubeLut = cache[path] ?: run {
        val file = File(path)
        require(file.length() <= CubeLut.MAX_BYTES) { "The LUT is too large" }
        val cube = CubeLut.parse(file.readBytes())
        while (cache.values.sumOf { it.samples.size * 4 } + cube.samples.size * 4 > CubeLut.MAX_BYTES && cache.isNotEmpty())
            cache.remove(cache.keys.first())
        cache[path] = cube
        cube
    }

    fun ingest(bytes: ByteArray, folder: File): Pair<File, CubeLut> {
        val cube = CubeLut.parse(bytes)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        folder.mkdirs()
        val file = File(folder, "effect-lut-$hash.cube")
        if (file.exists()) require(file.isFile && SourceAudio.hash(file) == hash) { "The saved LUT payload changed" }
        else {
            val partial = File(folder, "lut-${uid()}.partial")
            try { partial.writeBytes(bytes); check(partial.renameTo(file)) { "Cannot save LUT" } }
            finally { partial.delete() }
        }
        return file to cube
    }
}
