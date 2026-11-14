package org.myrec.app

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.max

class MediaIndex(private val cache: AudioCache, private val changed: () -> Unit) {
    private val worker = Executors.newFixedThreadPool(2)
    private val ui = Handler(Looper.getMainLooper())
    val thumbnails = mutableMapOf<String, List<Bitmap>>()
    val waves = mutableMapOf<String, FloatArray>()
    val durations = mutableMapOf<String, Double>()
    private val requested = mutableSetOf<String>()

    fun request(clip: Clip) {
        if (!requested.add(clip.path)) return
        worker.execute {
            if (clip.photo) {
                val image = try { PhotoSource.frame(clip.path, 192, 108) } catch (_: Exception) { null }
                ui.post {
                    if (image != null) thumbnails[clip.path] = listOf(image)
                    durations[clip.path] = 3.0; waves[clip.path] = FloatArray(0); changed()
                }
                return@execute
            }
            val reader = MediaMetadataRetriever()
            var duration = clip.sourceOut
            var hasAudio = clip.audio
            try {
                reader.setDataSource(clip.path)
                duration = Mp4Audio.presentedDuration(File(clip.path)) ?:
                    (reader
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toDoubleOrNull() ?: 0.0) / 1000
                hasAudio = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                val sourceDuration = duration
                ui.post { durations[clip.path] = sourceDuration }
                if (clip.video) {
                    val hdr =
                        reader
                            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)
                            ?.toIntOrNull() in listOf(6, 7)
                    val decoder =
                        if (hdr || File(clip.path + ".primaries").isFile)
                            try {
                                VideoDecoder(clip.path)
                            } catch (_: Exception) {
                                null
                            }
                        else null
                    val images = mutableListOf<Bitmap>()
                    try {
                        for (i in 0 until 12) {
                            val image =
                                decoder?.frame(duration * i / 12, 144, 81)
                                    ?: SourceMirror.frame(
                                        clip.path,
                                        reader.getScaledFrameAtTime(
                                            (duration * i / 12 * 1_000_000).toLong(),
                                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                            144,
                                            81,
                                        ),
                                    )
                            if (image != null) images.add(image)
                            if (i == 0 || i == 11) {
                                val ready = images.toList()
                                ui.post {
                                    thumbnails[clip.path] = ready
                                    changed()
                                }
                            }
                        }
                    } finally {
                        decoder?.close()
                    }
                }
            } catch (_: Exception) {
                ui.post { changed() }
            } finally {
                reader.release()
            }
            val waveform =
                try {
                    // Original-source 10 ms peaks also guide quiet-point selection after AV unlinking.
                    if (hasAudio) cache.waveform(clip.path, max(1, ceil(duration * 100).toInt())) else FloatArray(0)
                } catch (_: Exception) {
                    FloatArray(0)
                }
            ui.post {
                waves[clip.path] = waveform
                changed()
            }
        }
    }

    fun close() {
        worker.shutdown()
    }
}
