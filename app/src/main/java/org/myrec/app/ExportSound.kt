package org.myrec.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.*

object ExportSound {
    internal fun samples() = ShortArray(14400) { index ->
        val time = index / 48000.0
        val envelope = min(1.0, index / 240.0) * (1 - index / 14400.0).pow(2)
        ((sin(time * 2 * PI * 880) + .4 * sin(time * 2 * PI * 1320)) * envelope * .12 * 32767).toInt().toShort()
    }

    fun play() {
        runCatching {
            val pcm = samples()
            val player = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
            val handler = Handler(Looper.getMainLooper())
            var closed = false
            fun close() {
                if (closed) return
                closed = true
                runCatching { player.stop() }; player.release()
            }
            try {
                check(player.write(pcm,0,pcm.size,AudioTrack.WRITE_BLOCKING) == pcm.size)
                player.setNotificationMarkerPosition(pcm.size)
                player.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(track: AudioTrack) { close() }
                    override fun onPeriodicNotification(track: AudioTrack) {}
                },handler)
                player.play()
                handler.postDelayed({ close() },1200)
            } catch (_: Exception) { close() }
        }
    }
}
