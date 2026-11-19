package org.myrec.app

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Half
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

object HdrPipelineTests {
    private fun image(width: Int, height: Int, values: (Int, Int) -> FloatArray): Bitmap {
        val bitmap = WorkingColor.bitmap(width, height)
        val bytes = ByteBuffer.allocateDirect(bitmap.byteCount).order(ByteOrder.nativeOrder())
        for (y in 0 until height) for (x in 0 until bitmap.rowBytes / 8) {
            val p = if (x < width) values(x, y) else floatArrayOf(0f, 0f, 0f, 0f)
            for (c in p) bytes.putShort(Half.toHalf(c))
        }
        bytes.flip(); bitmap.copyPixelsFromBuffer(bytes); return bitmap
    }
    private fun pixel(bitmap: Bitmap, x: Int, y: Int): FloatArray {
        val bytes = ByteBuffer.allocateDirect(bitmap.byteCount).order(ByteOrder.nativeOrder())
        bitmap.copyPixelsToBuffer(bytes)
        return FloatArray(4) { Half.toFloat(bytes.getShort(y * bitmap.rowBytes + x * 8 + it * 2)) }
    }
    fun run(instrumentation: Instrumentation, a: MainActivity, onMain: (() -> Unit) -> Unit,
        test: (String, () -> Unit) -> Unit, requireFull: Boolean = false) {
        test("half float grading preserves extended identity and technical exposure without 8 bit quantization") {
            val bitmap = image(1024, 1) { x, _ -> val v = .2f + x / 1024f * 3; floatArrayOf(v, v, v, 1f) }
            val original = bitmap.copy(Bitmap.Config.RGBA_F16, true)
            Effects.correct(bitmap, Effect(kind = "Color Correction"), 1f)
            check(bitmap.sameAs(original))
            val fx = Effect(kind = "Color LUTs", values = mutableMapOf("exposure" to "-1"))
            check(fx.number("exposure") == -1f)
            Effects.correct(bitmap, fx, 1f)
            var last = -1f; var distinct = 0
            for (x in 0 until 1024) {
                val v = pixel(bitmap, x, 0)[0]
                check(abs(v - pixel(original, x, 0)[0] * .5f) < .002) { "$x: $v != ${pixel(original, x, 0)[0]} / 2; space=${bitmap.colorSpace}" }
                if (v != last) distinct++
                check(v >= last); last = v
            }
            check(distinct > 900 && last > 1)
            bitmap.recycle(); original.recycle()
        }
        test("linear half float canvas and dissolve retain highlights and alpha") {
            val first = image(8, 8) { _, _ -> floatArrayOf(4f, 2f, 1f, 1f) }
            val second = image(8, 8) { _, _ -> floatArrayOf(2f, 1f, .5f, 1f) }
            val output = WorkingColor.bitmap(8, 8)
            Canvas(output).drawBitmap(first, 0f, 0f, null)
            check(abs(pixel(output, 3, 3)[0] - 4) < .002) { "Canvas clipped extended white" }
            Native.blend(first, second, output, 0, .5f, 1f)
            check(abs(pixel(output, 3, 3)[0] - 3) < .002 && pixel(output, 3, 3)[3] == 1f)
            listOf(first, second, output).forEach { it.recycle() }
        }
        test("HDR geometry retains source radiometry while bounded effects retain half float precision") {
            val bitmap = image(65, 33) { x, y -> floatArrayOf(1 + x / 32f, .2f + y / 32f, .3f, 1f) }
            Native.kaleidoscope(bitmap, 1f, 2, .5f, .5f, 0f, 0f, 0f, 0f)
            check(pixel(bitmap, 60, 16)[0] > 2)
            Native.workingToneMap(bitmap)
            val mapped = pixel(bitmap, 60, 16)
            check(mapped.take(3).all { it in 0f..1f })
            Effects.correct(bitmap, Effect(kind = "Three-Strip"), .4f)
            check(bitmap.config == Bitmap.Config.RGBA_F16 && pixel(bitmap, 40, 16).all { it.isFinite() })
            val display = WorkingColor.display(bitmap)
            check(display.config == Bitmap.Config.ARGB_8888 && Color.red(display.getPixel(40, 16)) > 0)
            bitmap.recycle(); display.recycle()
        }
        test("P010 HLG decoding preserves 10 bit code steps and BT2100 headroom") {
            val width = 1024; val height = 2
            val y = ByteBuffer.allocateDirect(width * height * 2).order(ByteOrder.nativeOrder())
            val uv = ByteBuffer.allocateDirect(width * 2).order(ByteOrder.nativeOrder())
            repeat(height) { for (x in 0 until width) y.putShort((x shl 6).toShort()) }
            repeat(width / 2) { uv.putShort((512 shl 6).toShort()); uv.putShort((512 shl 6).toShort()) }
            y.flip(); uv.flip()
            val u = uv.duplicate(); val v = uv.duplicate().apply { position(2) }.slice()
            val output = WorkingColor.bitmap(width, height)
            Native.yuv(y, u, v, width * 2, width * 2, width * 2, 4, 4, 0, 0, width, height, 0,
                MediaFormat.COLOR_STANDARD_BT2020, true, output, 10, MediaFormat.COLOR_TRANSFER_HLG, 0)
            var prior = -1f; var distinct = 0
            for (x in 0 until width) {
                val code = x / 1023.0
                val scene = if (code <= .5) code * code / 3 else (exp((code - .55991073) / .17883277) + .28466892) / 12
                val expected = scene.pow(1.2) * 1000 / 203
                val value = pixel(output, x, 0)[0]
                check(abs(value - expected) < .006) { "$x: $value != $expected" }
                check(value >= prior)
                if (value != prior) distinct++
                prior = value
            }
            check(prior > 4.9f && distinct > 950)
            val display = WorkingColor.display(output)
            val direct = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Native.yuv(y, u, v, width * 2, width * 2, width * 2, 4, 4, 0, 0, width, height, 0,
                MediaFormat.COLOR_STANDARD_BT2020, true, direct, 10, MediaFormat.COLOR_TRANSFER_HLG, 0)
            for (x in 0 until width) check(abs(Color.red(display.getPixel(x, 0)) - Color.red(direct.getPixel(x, 0))) <= 1)
            display.recycle(); direct.recycle(); output.recycle()
        }
        test("an inactive HDR clip cannot tone map ordinary SDR layers elsewhere in the project") {
            val source = File(a.cacheDir, "sdr-source-${uid()}.mp4")
            val hdr = File(a.cacheDir, "inactive-hdr-${uid()}.mp4")
            try {
                for ((file, asset) in listOf(source to "fixture.mp4", hdr to "hdr-colors.mp4"))
                    instrumentation.context.assets.open(asset).use { input -> file.outputStream().use { input.copyTo(it) } }
                val clip = ProjectStore.inspect(source).apply { sourceOut = 1.0 }
                val project = Project(width = 160, height = 90, clips = mutableListOf(clip))
                Frames().use { frames ->
                    val expected = frames.render(project, .1, 160, 90)
                    project.clips.add(ProjectStore.inspect(hdr).apply { start = 2.0 })
                    val actual = frames.render(project, .1, 160, 90)
                    try {
                        for (x in 10 until 150 step 20) {
                            val a = expected.getPixel(x, 20); val b = actual.getPixel(x, 20)
                            check(abs(Color.red(a) - Color.red(b)) <= 2 && abs(Color.green(a) - Color.green(b)) <= 2 &&
                                abs(Color.blue(a) - Color.blue(b)) <= 2) { "Inactive HDR changed SDR pixels: $a / $b" }
                        }
                    } finally { expected.recycle(); actual.recycle() }
                }
            } finally { source.delete(); hdr.delete() }
        }
        test("actual Main10 HLG source preserves the decoder precision contract or rejects 8 bit output") {
            val source = File(a.cacheDir, "hdr-source-${uid()}.mp4")
            try {
                instrumentation.context.assets.open("hdr-colors.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
                check(WorkingColor.hdr(source.path))
                val failure = runCatching {
                    VideoDecoder(source.path, linear = true, tenBit = true).use { decoder ->
                        val image = decoder.frame(0.0, 320, 180) ?: error("No HDR picture")
                        try { check(image.config == Bitmap.Config.RGBA_F16 && pixel(image, 300, 90).all { it.isFinite() }) }
                        finally { image.recycle() }
                    }
                }.exceptionOrNull()
                if (failure != null) check(failure.message.orEmpty().contains("10-bit HDR samples")) { failure.toString() }
                instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream",
                    "HDR source decoder: ${failure?.message ?: "native 10-bit samples"}\n") })
            } finally { source.delete() }
        }
        test("HDR export either produces tagged Main10 HLG or rejects unsupported hardware without SDR substitution") {
            for (codec in android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.filter {
                it.isEncoder && it.supportedTypes.contains(MediaFormat.MIMETYPE_VIDEO_HEVC)
            }) {
                val caps = codec.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream",
                    "HEVC encoder: ${codec.name}; profiles=${caps.profileLevels.map { it.profile }}; sizes=${caps.videoCapabilities?.supportedWidths}/${caps.videoCapabilities?.supportedHeights}; surface=${caps.colorFormats.contains(android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)}\n") })
            }
            val project = Project(width = 1280, height = 720, clips = mutableListOf(
                Clip(path = File(a.cacheDir, "hdr-silence").path, sourceOut = .2, video = false, audio = false)))
            val target = File(a.cacheDir, "hdr-pipeline-${uid()}.mp4")
            try {
                val failure = runCatching { Export(a.audioCache).run(project, target, 1280, 720, ExportColor.HLG) {} }.exceptionOrNull()
                if (failure != null) {
                    check(failure.message.orEmpty().contains("Main10") || failure.message.orEmpty().contains("HLG") ||
                        failure.message.orEmpty().contains("recording surface")) { failure.toString() }
                    check(!target.exists())
                    instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "HDR capability: ${failure.message}\n") })
                } else {
                    instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "HDR capability: encoded tagged Main10 HLG\n") })
                    val extractor = MediaExtractor()
                    try {
                        extractor.setDataSource(target.path)
                        val format = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                            .single { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                        check(format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_HEVC)
                        check(format.getInteger(MediaFormat.KEY_COLOR_STANDARD) == MediaFormat.COLOR_STANDARD_BT2020)
                        check(format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) == MediaFormat.COLOR_TRANSFER_HLG)
                        check(format.getInteger(MediaFormat.KEY_PROFILE) == android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                    } finally { extractor.release() }
                }
            } finally { target.delete() }
        }
        if (requireFull) test("mixed SDR alpha and HLG retain per-layer light through SDR and HDR exports") {
            val width = 640
            val height = 360
            val folder = File(a.getExternalFilesDir(null), "test-output").apply { mkdirs() }
            val source = File(a.cacheDir, "mixed-hlg-${uid()}.mp4")
            val photo = File(a.cacheDir, "mixed-sdr-${uid()}.png")
            try {
                instrumentation.context.assets.open("hdr-ramp.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
                val color = Color.argb(128, 110, 177, 60)
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { image ->
                    image.eraseColor(color)
                    try { photo.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                    finally { image.recycle() }
                }
                val plate = ProjectStore.inspect(source).apply { audio = false }
                val project = Project(width = width, height = height, clips = mutableListOf(plate))
                val placed = Effect(kind = "Placement", values = mutableMapOf(
                    "geometry" to "canvas", "x" to ".5", "y" to "0", "w" to ".5", "h" to ".5", "reframe" to "0"))
                val layer = Clip(path = photo.path, sourceOut = .1, start = 1.0 / 30, track = 1, photo = true, audio = false,
                    effects = mutableListOf(placed))
                val pose = TransformMotion.pose(placed)
                placed.motion = EffectMotion(pose, pose + ("x" to .55), duration = layer.duration).anchored(layer, 0.0)
                val mixed = project.copy(clips = mutableListOf(plate, layer))
                val at = 2.0 / 30
                val x = 512
                val y = 90
                fun linear(v: Int): Double { val s = v / 255.0; return if (s <= .04045) s / 12.92 else ((s + .055) / 1.055).pow(2.4) }
                val foreground = listOf(Color.red(color), Color.green(color), Color.blue(color)).map(::linear)
                val alpha = Color.alpha(color) / 255.0
                val (expectedLinear, expectedPreview) = Frames().use { frames ->
                    val bare = frames.render(project, at, width, height, keepLinear = true)
                    val pixels = frames.render(mixed, at, width, height, keepLinear = true)
                    val display = frames.render(mixed, at, width, height)
                    try {
                        val backing = pixel(bare, x, y)
                        check(backing[0] > 1f)
                        val linear = pixel(pixels, x, y)
                        for (i in 0..2) check(abs(linear[i] - (backing[i] * (1 - alpha) + foreground[i] * alpha)) < .018) {
                            "SDR foreground channel $i changed the HLG/SDR blend: ${linear.toList()} over ${backing.toList()}"
                        }
                        check(pixel(pixels, x, 270).contentEquals(pixel(bare, x, 270)))
                        linear to display.getPixel(x, y)
                    } finally { bare.recycle(); pixels.recycle(); display.recycle() }
                }
                var rgbaError = 0f
                var displayError = 0
                for (format in listOf(ExportColor.SDR, ExportColor.HLG)) {
                    val target = File(folder, "mixed-${format.name.lowercase()}.mp4")
                    val hdr = format == ExportColor.HLG
                    Export(a.audioCache).run(mixed, target, width, height, format) {}
                    VideoDecoder(target.path, linear = hdr, tenBit = hdr).use { decoder ->
                        val frame = checkNotNull(decoder.frame(at, width, height))
                        try {
                            if (hdr) {
                                rgbaError = pixel(frame, x, y).take(3).zip(expectedLinear.take(3)).maxOf { (actual, expected) -> abs(actual - expected) }
                                check(rgbaError < .075f) { "HDR round-trip linear error $rgbaError" }
                            } else {
                                val sample = frame.getPixel(x, y)
                                displayError = listOf(Color.red(sample) - Color.red(expectedPreview), Color.green(sample) - Color.green(expectedPreview),
                                    Color.blue(sample) - Color.blue(expectedPreview)).maxOf { abs(it) }
                                check(displayError <= 9) { "SDR round-trip error $displayError" }
                            }
                        } finally { frame.recycle() }
                    }
                }
                File(folder, "mixed-color.json").writeText(org.json.JSONObject().put("hdr_max_linear_error", rgbaError)
                    .put("sdr_max_code_error", displayError).put("highlight_under_alpha", expectedLinear[0]).toString(2))
            } finally { source.delete(); photo.delete() }
        }
        if (requireFull) for ((width, height) in listOf(1280 to 720, 1920 to 1080, 3840 to 2160))
        test("real ${width}x${height} Main10 HLG export retains extended highlights and more than 8 bit code levels") {
            val source = File(a.cacheDir, "hdr-ramp-${uid()}.mp4")
            val target = File(a.getExternalFilesDir(null), "test-output/hdr-ramp-${width}x${height}.mp4")
            target.parentFile!!.mkdirs()
            try {
                instrumentation.context.assets.open("hdr-ramp.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
                val clip = ProjectStore.inspect(source).apply { audio = false }
                val project = Project(width = width, height = height, clips = mutableListOf(clip))
                fun peak(bitmap: Bitmap): Float {
                    val data = ByteBuffer.allocateDirect(bitmap.byteCount).order(ByteOrder.nativeOrder())
                    bitmap.copyPixelsToBuffer(data)
                    return (0 until bitmap.width).maxOf { x -> Half.toFloat(data.getShort(bitmap.rowBytes * (bitmap.height / 2) + x * 8)) }
                }
                VideoDecoder(source.path, linear = true, tenBit = true).use { decoder ->
                    val bitmap = decoder.frame(0.0, 1280, 720)!!
                    try { check(peak(bitmap) > 4.7) { "Source decoder clipped: ${peak(bitmap)}" } } finally { bitmap.recycle() }
                }
                Frames().use { frames ->
                    val bitmap = frames.render(project, 0.0, width, height, keepLinear = true)
                    try { check(peak(bitmap) > 4.7) { "Working compositor clipped: ${peak(bitmap)}" } } finally { bitmap.recycle() }
                }
                Export(a.audioCache).run(project, target, width, height, ExportColor.HLG) {}
                VideoDecoder(target.path, linear = true, tenBit = true).use { decoder ->
                    val bitmap = decoder.frame(0.0, width, height) ?: error("No HDR export picture")
                    try {
                        val data = ByteBuffer.allocateDirect(bitmap.byteCount).order(ByteOrder.nativeOrder())
                        bitmap.copyPixelsToBuffer(data)
                        val row = FloatArray(bitmap.width) { x -> Half.toFloat(data.getShort(bitmap.rowBytes * (bitmap.height / 2) + x * 8)) }
                        val distinct = row.toSet().size
                        check(row.all { it.isFinite() } && row.max() > 4.7f && distinct > 600) {
                            "HDR export lost radiometry or precision: peak=${row.max()}, levels=$distinct"
                        }
                        var error = 0.0
                        for (x in 16 until bitmap.width - 16) {
                            val code = (x * 876.0 / (bitmap.width - 1)).roundToInt() / 876.0
                            val scene = if (code <= .5) code * code / 3 else (exp((code - .55991073) / .17883277) + .28466892) / 12
                            error += abs(row[x] - scene.pow(1.2) * 1000 / 203)
                        }
                        check(error / (bitmap.width - 32) < .03) { "HLG export mean linear error: ${error / (bitmap.width - 32)}" }
                        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream",
                            "HDR ramp ${width}x${height}: peak=${row.max()}, levels=$distinct, mean_error=${error / (bitmap.width - 32)}; ${target.path}\n") })
                    } finally { bitmap.recycle() }
                }
            } finally { source.delete() }
        }
        test("HDR export choice is available in the normal export workspace") {
            onMain {
                a.editor.load(Project(clips = mutableListOf(Clip(path = "test", sourceOut = 1.0))))
                a.panels.export()
                fun find(v: android.view.View, title: String): android.view.View? {
                    if (v.contentDescription == title || v is android.widget.TextView && v.text == title) return v
                    if (v is android.view.ViewGroup) for (index in 0 until v.childCount)
                        find(v.getChildAt(index), title)?.let { return it }
                    return null
                }
                check(checkNotNull(find(a.root, "Codec and color, SDR · H.264")).performClick())
                check(find(checkNotNull(a.choices).contentView, "HDR · HEVC 10-bit HLG") != null)
                a.choices?.dismiss()
                a.panels.close()
            }
            instrumentation.waitForIdleSync()
        }
    }
}
