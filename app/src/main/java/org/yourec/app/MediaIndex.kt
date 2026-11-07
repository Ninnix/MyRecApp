package org.yourec.app

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

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
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(clip.path)
                val duration =
                    (reader
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toDoubleOrNull() ?: 0.0) / 1000
                ui.post { durations[clip.path] = duration }
                if (clip.video) {
                    val images = mutableListOf<Bitmap>()
                    for (i in 0 until 12) {
                        val image =
                            reader.getScaledFrameAtTime(
                                (duration * i / 12 * 1_000_000).toLong(),
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                144,
                                81,
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
                }
            } catch (_: Exception) {
                ui.post { changed() }
            } finally {
                reader.release()
            }
            val waveform =
                try {
                    if (clip.audio) cache.waveform(clip.path) else FloatArray(0)
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
