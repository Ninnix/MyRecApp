package org.myrec.app

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.*
import org.json.JSONArray
import org.json.JSONObject

object HdrColorTest {
    fun run(test: Instrumentation, activity: MainActivity): JSONObject {
        fun main(block: () -> Unit) {
            var error: Throwable? = null
            test.runOnMainSync {
                try {
                    block()
                } catch (e: Throwable) {
                    error = e
                }
            }
            error?.let { throw it }
        }
        val colors =
            JSONArray(
                test.context.assets.open("hdr-colors.json").bufferedReader().use { it.readText() }
            )
        val result = JSONObject()
        val manager = activity.getSystemService(android.hardware.camera2.CameraManager::class.java)
        val chars = manager.getCameraCharacteristics(manager.cameraIdList.first())
        val profiles =
            chars.get(
                android.hardware.camera2.CameraCharacteristics
                    .REQUEST_AVAILABLE_COLOR_SPACE_PROFILES
            )
        result.put(
            "camera_capabilities",
            chars
                .get(android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.toList()
                .toString(),
        )
        if (profiles != null)
            result.put(
                "camera_color_profiles",
                org.json.JSONArray(
                    profiles.getSupportedColorSpaces(android.graphics.ImageFormat.UNKNOWN).map {
                        color ->
                        JSONObject()
                            .put("space", color.name)
                            .put(
                                "formats",
                                profiles
                                    .getSupportedImageFormatsForColorSpace(color)
                                    .toList()
                                    .toString(),
                            )
                            .put(
                                "private_ranges",
                                profiles
                                    .getSupportedDynamicRangeProfiles(
                                        color,
                                        android.graphics.ImageFormat.PRIVATE,
                                    )
                                    .toList()
                                    .toString(),
                            )
                    }
                ),
            )

        for ((hdr, full, primaries) in
            listOf(
                Triple(false, false, 0),
                Triple(true, false, 0),
                Triple(true, true, 0),
                Triple(true, false, 1),
                Triple(true, false, 2),
            )) {
            val view = TextureView(activity)
            var renderer: CameraComposition? = null
            val failure = AtomicReference<Throwable>()
            try {
                main {
                    activity.root.addView(view, FrameLayout.LayoutParams(320, 180, Gravity.CENTER))
                }
                var available = false
                repeat(50) {
                    main { available = view.isAvailable }
                    if (!available) Thread.sleep(50)
                }
                check(available) { "Colour test window unavailable" }
                main { view.surfaceTexture!!.setDefaultBufferSize(320, 180) }
                renderer =
                    CameraComposition(Surface(view.surfaceTexture), 320, 180, hdr) {
                        failure.set(it)
                    }
                val input = renderer.input(320, 180, 0, false, primaries)
                val raw = test.context.assets.open("hdr-colors.yuv").use { it.readBytes() }
                val data = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                if (full) {
                    var offset = 0
                    for (channel in 0..2) {
                        val w = if (channel == 0) 320 else 160
                        val h = if (channel == 0) 180 else 90
                        for (y in 0 until h) for (x in 0 until w) {
                            val rgb = colors.getJSONArray(x * 8 / w)
                            val r = rgb.getDouble(0)
                            val g = rgb.getDouble(1)
                            val b = rgb.getDouble(2)
                            val luma = .2627 * r + .678 * g + .0593 * b
                            val value =
                                when (channel) {
                                    0 -> 1023 * luma
                                    1 -> 512 + 1023 * (b - luma) / 1.8814
                                    else -> 512 + 1023 * (r - luma) / 1.4746
                                }
                            data.putShort(
                                offset + (y * w + x) * 2,
                                value.roundToInt().coerceIn(0, 1023).toShort(),
                            )
                        }
                        offset += w * h * 2
                    }
                }
                val writer =
                    android.media.ImageWriter.Builder(input)
                        .setMaxImages(3)
                        .setWidthAndHeight(320, 180)
                        .setHardwareBufferFormat(android.hardware.HardwareBuffer.YCBCR_P010)
                        .setDataSpace(
                            android.hardware.DataSpace.STANDARD_BT2020 or
                                android.hardware.DataSpace.TRANSFER_HLG or
                                if (full) android.hardware.DataSpace.RANGE_FULL
                                else android.hardware.DataSpace.RANGE_LIMITED
                        )
                        .setUsage(
                            android.hardware.HardwareBuffer.USAGE_CPU_WRITE_OFTEN or
                                android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
                        )
                        .build()
                var recording: HdrRecorder? = null
                if (hdr && !full) {
                    val manager = activity.getSystemService(android.media.AudioManager::class.java)
                    val mic =
                        manager.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS).first {
                            it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC
                        }
                    recording =
                        HdrRecorder(
                            activity,
                            File(
                                test.targetContext.getExternalFilesDir(null),
                                if (primaries == 0) "hdr-color-roundtrip.mp4"
                                else "hdr-color-roundtrip-$primaries.mp4",
                            ),
                            320,
                            180,
                            30,
                            0,
                            mic,
                            android.media.MediaRecorder.AudioSource.CAMCORDER,
                            1,
                        ) {
                            failure.set(it)
                        }
                    renderer.record(recording.surface, 320, 180, 0)
                    recording.start()
                    check(recording.microphone(mic)) { "HDR microphone route cannot be selected" }
                }
                try {
                    repeat(3) {
                        val image = writer.dequeueInputImage()
                        image.timestamp = System.nanoTime()
                        var offset = 0
                        for ((index, plane) in image.planes.withIndex()) {
                            val w = if (index == 0) 320 else 160
                            val h = if (index == 0) 180 else 90
                            val buffer = plane.buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            for (y in 0 until h) for (x in 0 until w) buffer.putShort(
                                y * plane.rowStride + x * plane.pixelStride,
                                (data.getShort(offset + (y * w + x) * 2).toInt() shl 6).toShort(),
                            )
                            offset += w * h * 2
                        }
                        writer.queueInputImage(image)
                        Thread.sleep(100)
                    }
                    Thread.sleep(300)
                    if (recording != null) {
                        Thread.sleep(500)
                        recording.stop()
                        renderer.record(null)
                        recording.close()
                        recording = null
                    }
                    failure.get()?.let { throw it }
                    result.put(
                        if (hdr) "hdr_data_space" else "default_data_space",
                        JSONArray(renderer.inputDataSpaces()),
                    )
                    var bitmap: Bitmap? = null
                    main { bitmap = view.bitmap }
                    val image = checkNotNull(bitmap)
                    val actual = JSONArray()
                    val expected = JSONArray()
                    var maximum = 0
                    val native = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
                    if (hdr) {
                        var offset = 0
                        val planes = mutableListOf<java.nio.ByteBuffer>()
                        for (count in listOf(320 * 180, 160 * 90, 160 * 90)) {
                            val buffer =
                                java.nio.ByteBuffer.allocateDirect(count * 2)
                                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            repeat(count) {
                                buffer.putShort(
                                    (data.getShort(offset + it * 2).toInt() shl 6).toShort()
                                )
                            }
                            buffer.rewind()
                            planes.add(buffer)
                            offset += count * 2
                        }
                        Native.yuv(
                            planes[0],
                            planes[1],
                            planes[2],
                            640,
                            320,
                            320,
                            2,
                            2,
                            0,
                            0,
                            320,
                            180,
                            0,
                            6,
                            full,
                            native,
                            10,
                            7,
                            primaries,
                        )
                    }
                    try {
                        for (i in 0 until colors.length()) {
                            val pixel = image.getPixel(i * 40 + 20, 90)
                            val sample =
                                intArrayOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                            actual.put(JSONArray(sample.toList()))
                            val encoded = displayColor(colors.getJSONArray(i), primaries)
                            expected.put(JSONArray(encoded))
                            if (hdr) {
                                val pixel = native.getPixel(i * 40 + 20, 90)
                                val converted =
                                    intArrayOf(
                                        Color.red(pixel),
                                        Color.green(pixel),
                                        Color.blue(pixel),
                                    )
                                for (c in 0..2) {
                                    maximum = max(maximum, abs(sample[c] - encoded[c]))
                                    check(abs(converted[c] - encoded[c]) <= 4) {
                                        "Editor P010 colour error: ${converted.toList()} vs $encoded"
                                    }
                                }
                            }
                        }
                    } finally {
                        image.recycle()
                        native.recycle()
                    }
                    result.put(
                        if (full) "preview_full"
                        else if (hdr) "preview_$primaries" else "default_sampler",
                        actual,
                    )
                    if (hdr) {
                        result
                            .put("expected_$primaries", expected)
                            .put("maximum_channel_error_$primaries", maximum)
                        File(test.targetContext.getExternalFilesDir(null), "hdr-color-probe.json")
                            .writeText(result.toString(2))
                        check(maximum <= 4) { "HDR colour error $maximum: $result" }
                        if (!full) {
                            val file =
                                File(
                                    test.targetContext.getExternalFilesDir(null),
                                    if (primaries == 0) "hdr-color-roundtrip.mp4"
                                    else "hdr-color-roundtrip-$primaries.mp4",
                                )
                            VideoDecoder(file.path).use { decoder ->
                                val frame = checkNotNull(decoder.frame(.3, 320, 180))
                                try {
                                    var error = 0
                                    for (i in 0 until colors.length()) {
                                        val pixel = frame.getPixel(i * 40 + 20, 90)
                                        val actual =
                                            intArrayOf(
                                                Color.red(pixel),
                                                Color.green(pixel),
                                                Color.blue(pixel),
                                            )
                                        val target = displayColor(colors.getJSONArray(i), primaries)
                                        for (c in 0..2) error =
                                            max(error, abs(actual[c] - target[c]))
                                    }
                                    result.put("encoded_corrected_maximum_error_$primaries", error)
                                    check(error <= 5) {
                                        "Recorded HDR profile $primaries changed colour: $error"
                                    }
                                } finally {
                                    frame.recycle()
                                }
                            }
                        }
                    }
                } finally {
                    recording?.close()
                    writer.close()
                }
            } finally {
                renderer?.close()
                main { activity.root.removeView(view) }
            }
        }
        val encoded = File(test.targetContext.cacheDir, "hdr-colors.mp4")
        test.context.assets.open("hdr-colors.mp4").use { input ->
            encoded.outputStream().use { input.copyTo(it) }
        }
        for (primaries in 0..2) {
            File(encoded.path + ".primaries").writeText(primaries.toString())
            VideoDecoder(encoded.path).use { decoder ->
                val image = decoder.frame(.5, 320, 180) ?: error("HDR decoder frame missing")
                try {
                    var maximum = 0
                    val actual = JSONArray()
                    for (i in 0 until colors.length()) {
                        val pixel = image.getPixel(i * 40 + 20, 90)
                        val rgb =
                            intArrayOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                        actual.put(JSONArray(rgb.toList()))
                        val expected = result.getJSONArray("expected_$primaries").getJSONArray(i)
                        for (c in 0..2) maximum = max(maximum, abs(rgb[c] - expected.getInt(c)))
                    }
                    result
                        .put("editor_decoder_$primaries", actual)
                        .put("editor_maximum_channel_error_$primaries", maximum)
                    File(test.targetContext.getExternalFilesDir(null), "hdr-color-probe.json")
                        .writeText(result.toString(2))
                    check(maximum <= 4) { "HDR editor decoder colour error $maximum: $result" }
                } finally {
                    image.recycle()
                }
            }
        }
        val index =
            MediaIndex(AudioCache(File(test.targetContext.cacheDir, "hdr-thumbnail-audio"))) {}
        try {
            index.request(ProjectStore.inspect(encoded))
            var thumbnails = emptyList<Bitmap>()
            repeat(200) {
                main { thumbnails = index.thumbnails[encoded.path].orEmpty() }
                if (thumbnails.size < 12) Thread.sleep(25)
            }
            check(thumbnails.size == 12) { "HDR timeline thumbnails are missing" }
            var maximum = 0
            for (i in 0 until colors.length()) {
                val frame = thumbnails.first()
                val pixel =
                    frame.getPixel(
                        (i + .5).times(frame.width / colors.length()).toInt(),
                        frame.height / 2,
                    )
                val actual = intArrayOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                val target = displayColor(colors.getJSONArray(i), 2)
                for (c in 0..2) maximum = max(maximum, abs(actual[c] - target[c]))
            }
            result.put("timeline_maximum_channel_error", maximum)
            check(maximum <= 4) { "HDR timeline colour mismatch: $maximum" }
        } finally {
            index.close()
        }
        File(encoded.path + ".primaries").delete()
        result.put("profile_detection", profiles())
        val reference =
            File(test.targetContext.getExternalFilesDir(null), "hdr-color-reference.png")
        val input = File(test.targetContext.getExternalFilesDir(null), "hdr-color-input.png")
        if (reference.isFile && input.isFile) {
            val a = android.graphics.BitmapFactory.decodeFile(reference.path)
            val b = android.graphics.BitmapFactory.decodeFile(input.path)
            try {
                val detected = HdrColor.detect(a, b)
                result.put("camera_profile_detection", JSONArray(detected.toList()))
                check(detected[0] == 1f) { "Camera profile mismatch: ${detected.toList()}" }
            } finally {
                a.recycle()
                b.recycle()
            }
        }
        File(test.targetContext.getExternalFilesDir(null), "hdr-color-probe.json")
            .writeText(result.toString(2))
        return result
    }

    private fun displayColor(rgb: JSONArray, primaries: Int): List<Int> {
        fun linear(v: Double) =
            if (v <= .5) v * v / 3 else (exp((v - .55991073) / .17883277) + .28466892) / 12
        val r = linear(rgb.getDouble(0))
        val g = linear(rgb.getDouble(1))
        val b = linear(rgb.getDouble(2))
        val target =
            when (primaries) {
                1 -> doubleArrayOf(r, g, b)
                2 ->
                    doubleArrayOf(
                        1.224745 * r - .224904 * g,
                        -.042058 * r + 1.042081 * g,
                        -.019642 * r - .078655 * g + 1.098537 * b,
                    )
                else ->
                    doubleArrayOf(
                        1.6605 * r - .5876 * g - .0728 * b,
                        -.1246 * r + 1.1329 * g - .0083 * b,
                        -.0182 * r - .1006 * g + 1.1187 * b,
                    )
            }
        val luminance = when (primaries) {
            1 -> .2126 * r + .7152 * g + .0722 * b
            2 -> .2289746 * r + .6917385 * g + .0792869 * b
            else -> .2627 * r + .678 * g + .0593 * b
        }
        val gain = max(0.0, luminance).pow(.2) * 1000 / 203
        for (i in target.indices) target[i] *= gain
        val luma = max(.000001, .2126 * target[0] + .7152 * target[1] + .0722 * target[2])
        val mapped = if (luma <= .75) luma else .75 + .25 * (1 - exp(-(luma - .75) / .25))
        val delta = target.map { it * mapped / luma - mapped }
        var fit = 1.0
        for (d in delta) if (d > 0) fit = min(fit, (1 - mapped) / d) else if (d < 0) fit = min(fit, -mapped / d)
        return delta.map { difference ->
            val v = (mapped + difference * max(0.0, fit)).coerceIn(0.0, 1.0)
            ((if (v <= .0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - .055) * 255)
                .roundToInt()
                .coerceIn(0, 255)
        }
    }

    private fun profiles(): JSONArray {
        val result = JSONArray()
        val w = 256
        val h = 384
        for (profile in 0..2) {
            val a = IntArray(w * h)
            val b = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val k = (x / 16) * 1973 + (y / 16) * 9277
                val rgb =
                    doubleArrayOf(
                        .1 + ((k * 13) and 255) / 370.0,
                        .1 + ((k * 29 + 79) and 255) / 370.0,
                        .1 + ((k * 47 + 129) and 255) / 370.0,
                    )
                fun linear(v: Double) =
                    if (v <= .04045) v / 12.92 else ((v + .055) / 1.055).pow(2.4)
                val r = linear(rgb[0]) / 3
                val g = linear(rgb[1]) / 3
                val blue = linear(rgb[2]) / 3
                val source =
                    when (profile) {
                        1 -> doubleArrayOf(r, g, blue)
                        2 ->
                            doubleArrayOf(
                                .822593 * r + .177534 * g,
                                .033200 * r + .966783 * g,
                                .017085 * r + .072396 * g + .910302 * blue,
                            )
                        else ->
                            doubleArrayOf(
                                .6274 * r + .3293 * g + .0433 * blue,
                                .0691 * r + .9195 * g + .0114 * blue,
                                .0164 * r + .0880 * g + .8956 * blue,
                            )
                    }
                val encoded =
                    source.map { v ->
                        ((if (v <= 1.0 / 12) sqrt(3 * v)
                            else .17883277 * ln(12 * v - .28466892) + .55991073) * 255)
                            .roundToInt()
                            .coerceIn(0, 255)
                    }
                a[y * w + x] =
                    -0x1000000 or
                        ((rgb[0] * 255).roundToInt() shl 16) or
                        ((rgb[1] * 255).roundToInt() shl 8) or
                        (rgb[2] * 255).roundToInt()
                b[y * w + x] = -0x1000000 or (encoded[0] shl 16) or (encoded[1] shl 8) or encoded[2]
            }
            val reference = Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888)
            val input = Bitmap.createBitmap(b, w, h, Bitmap.Config.ARGB_8888)
            try {
                val detected = HdrColor.detect(reference, input)
                result.put(JSONArray(detected.toList()))
                check(detected[0] == profile.toFloat()) { "Profile $profile: ${detected.toList()}" }
                val flat = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                try {
                    flat.eraseColor(Color.GRAY)
                    check(HdrColor.detect(reference, flat)[0] == -1f) {
                        "Flat scene must not change the colour profile"
                    }
                } finally {
                    flat.recycle()
                }
            } finally {
                reference.recycle()
                input.recycle()
            }
        }
        return result
    }
}
